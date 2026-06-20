package ng.facededup.sdk

import com.google.mlkit.vision.face.Face
import java.security.SecureRandom
import kotlin.math.abs

/**
 * Native active-liveness challenge (ML Kit head pose + smile + eye-open). Pool: turn
 * left/right, look up/down, smile, blink — a randomised distinct sequence per run.
 *
 * IMPORTANT pose handling (fixes "I follow the instruction but still fail"):
 *  • Turns gate on YAW returning near-frontal first (`|yaw| < neutralYaw`) — they do NOT
 *    require near-zero pitch. Phones are held BELOW the face, so resting pitch is biased
 *    (~−15°); the old "neutral = |pitch|<8" baseline was never met, so turns never
 *    registered. Fixed.
 *  • Up/down are judged as a DELTA from a tracked neutral pitch (not absolute pitch),
 *    so the phone-height bias can't make them impossible.
 *
 * The server still makes the authoritative decision; this only drives the UX + which
 * frames to submit. All thresholds come from [FacededupLivenessConfig].
 */
internal class ActiveLiveness(private val cfg: FacededupLivenessConfig) {

    enum class Directive(val proves: String?) {
        Positioning(null), TurnLeft("turn_left"), TurnRight("turn_right"),
        LookUp("look_up"), LookDown("look_down"), Smile("smile"), Blink("blink"), Done(null)
    }

    private companion object {
        const val EYE_OPEN = 0.7f   // both-eyes-open baseline before a blink counts
        const val YAW_SIGN = 1f     // flip to -1 if turn left/right are swapped on a device
        const val PITCH_SIGN = 1f   // ML Kit: +headEulerAngleX = looking up
    }

    private val motion = setOf(Directive.TurnLeft, Directive.TurnRight, Directive.LookUp, Directive.LookDown)
    private val full = listOf(
        Directive.TurnLeft, Directive.TurnRight, Directive.LookUp,
        Directive.LookDown, Directive.Smile, Directive.Blink)

    private val steps: List<Directive> = run {
        val map = mapOf(
            "turn_left" to Directive.TurnLeft, "turn_right" to Directive.TurnRight,
            "look_up" to Directive.LookUp, "look_down" to Directive.LookDown,
            "smile" to Directive.Smile, "blink" to Directive.Blink)
        val explicit = cfg.actions.mapNotNull { map[it] }
        if (explicit.isNotEmpty()) explicit else randomSequence(cfg.sequenceLength.coerceIn(1, full.size))
    }

    private fun randomSequence(n: Int): List<Directive> {
        val r = SecureRandom()
        val motions = motion.toMutableList(); val rest = full.filter { it !in motion }.toMutableList()
        val out = ArrayList<Directive>()
        out.add(motions.removeAt(r.nextInt(motions.size)))           // guarantee ≥1 head-motion
        val remaining = (motions + rest).toMutableList()
        while (out.size < n && remaining.isNotEmpty()) out.add(remaining.removeAt(r.nextInt(remaining.size)))
        for (i in out.size - 1 downTo 1) { val j = r.nextInt(i + 1); val t = out[i]; out[i] = out[j]; out[j] = t }
        return out
    }

    private var index = 0
    private var sawNeutral = false      // yaw returned near-frontal (for turns/tilts)
    private var sawEyesOpen = false
    private var neutralPitch: Float? = null   // EMA resting pitch (phone-bias baseline)

    // live values (for the diagnostic overlay)
    var dbgYaw = 0f; private set
    var dbgPitchDelta = 0f; private set
    var dbgSmile = 0f; private set
    var dbgEyeOpen = 1f; private set

    var subProgress = 0f; private set
    var directionDeg: Float? = null; private set

    val isFinished: Boolean get() = index >= steps.size
    val current: Directive get() = if (isFinished) Directive.Done else steps[index]
    val total: Int get() = steps.size
    val progress: Int get() = index
    fun actionKeys(): List<String> = steps.mapNotNull { it.proves }
    val overallProgress: Float get() =
        if (total == 0) 0f else ((index + subProgress) / total).coerceIn(0f, 1f)

    fun hint(facePresent: Boolean): String = when {
        !facePresent -> "Center your face in the oval"
        else -> when (current) {
            Directive.TurnLeft -> "Turn your head left"
            Directive.TurnRight -> "Turn your head right"
            Directive.LookUp -> "Tilt your head up"
            Directive.LookDown -> "Tilt your head down"
            Directive.Smile -> "Smile"
            Directive.Blink -> "Blink your eyes"
            else -> "Hold still"
        }
    }

    fun onFace(face: Face?): Boolean {
        if (isFinished || face == null) return false
        val yaw = face.headEulerAngleY * YAW_SIGN
        val pitch = face.headEulerAngleX * PITCH_SIGN
        val smile = face.smilingProbability ?: 0f
        val eyeOpen = minOf(face.leftEyeOpenProbability ?: 1f, face.rightEyeOpenProbability ?: 1f)

        val frontal = abs(yaw) < cfg.neutralYawDeg
        if (frontal) sawNeutral = true
        if (eyeOpen > EYE_OPEN) sawEyesOpen = true
        // Track resting pitch as an EMA when frontal and NOT mid up/down action.
        val vertical = current == Directive.LookUp || current == Directive.LookDown
        if (frontal && !vertical) {
            neutralPitch = if (neutralPitch == null) pitch else neutralPitch!! + (pitch - neutralPitch!!) * 0.1f
        }
        val pitchDelta = pitch - (neutralPitch ?: pitch)

        dbgYaw = yaw; dbgPitchDelta = pitchDelta; dbgSmile = smile; dbgEyeOpen = eyeOpen

        when (current) {
            Directive.TurnLeft  -> { subProgress = (yaw / cfg.turnYawDeg).coerceIn(0f, 1f); directionDeg = 180f }
            Directive.TurnRight -> { subProgress = (-yaw / cfg.turnYawDeg).coerceIn(0f, 1f); directionDeg = 0f }
            Directive.LookUp    -> { subProgress = (pitchDelta / cfg.tiltPitchDeg).coerceIn(0f, 1f); directionDeg = 90f }
            Directive.LookDown  -> { subProgress = (-pitchDelta / cfg.tiltPitchDeg).coerceIn(0f, 1f); directionDeg = 270f }
            Directive.Smile     -> { subProgress = (smile / cfg.smileThreshold).coerceIn(0f, 1f); directionDeg = null }
            Directive.Blink     -> { subProgress = ((EYE_OPEN - eyeOpen) / (EYE_OPEN - cfg.blinkThreshold)).coerceIn(0f, 1f); directionDeg = null }
            else -> { subProgress = 0f; directionDeg = null }
        }

        val satisfied = when (current) {
            Directive.TurnLeft  -> sawNeutral && yaw >  cfg.turnYawDeg
            Directive.TurnRight -> sawNeutral && yaw < -cfg.turnYawDeg
            Directive.LookUp    -> sawNeutral && pitchDelta >  cfg.tiltPitchDeg
            Directive.LookDown  -> sawNeutral && pitchDelta < -cfg.tiltPitchDeg
            Directive.Smile     -> smile > cfg.smileThreshold
            Directive.Blink     -> sawEyesOpen && eyeOpen < cfg.blinkThreshold
            else -> false
        }
        if (satisfied) { index++; sawNeutral = false; sawEyesOpen = false; subProgress = 0f }
        return satisfied
    }

    /** One-line live readout for the diagnostic overlay. */
    fun debugLine(): String = "yaw %.0f  dPitch %.0f  smile %.2f  eye %.2f  [%s]".format(
        dbgYaw, dbgPitchDelta, dbgSmile, dbgEyeOpen, current.name)
}
