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
        const val YAW_SIGN = -1f    // flip to +1 if turn left/right are swapped on a device
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
    private var neutralPitch: Float? = null   // resting pitch (phone-bias baseline)

    // Positioning phase: hold a stable, frontal face FIRST to capture the resting pose
    // (seeds neutralPitch) before any action — fixes "look up/down never registers"
    // when a vertical action is first (dPitch was stuck at 0).
    private var positioned = false
    private var posStable = 0
    private var posPitchSum = 0f
    private var posPitchN = 0
    private val posNeeded = 12          // ~stable frames frontal before starting

    // live values (for the diagnostic overlay)
    var dbgYaw = 0f; private set
    var dbgPitchDelta = 0f; private set
    var dbgSmile = 0f; private set
    var dbgEyeOpen = 1f; private set

    var subProgress = 0f; private set
    var directionDeg: Float? = null; private set
    /** True when the user is clearly doing the OPPOSITE of the asked move (→ red + nudge). */
    var wrong = false; private set

    val isFinished: Boolean get() = positioned && index >= steps.size
    val current: Directive get() = when {
        !positioned -> Directive.Positioning
        index >= steps.size -> Directive.Done
        else -> steps[index]
    }
    val total: Int get() = steps.size
    val progress: Int get() = index
    fun actionKeys(): List<String> = steps.mapNotNull { it.proves }
    val overallProgress: Float get() =
        if (!positioned || total == 0) 0f else ((index + subProgress) / total).coerceIn(0f, 1f)

    fun hint(facePresent: Boolean): String = when {
        !facePresent -> cfg.str("center_face")
        else -> when (current) {
            Directive.Positioning -> cfg.str("center_face")
            Directive.TurnLeft -> cfg.str("turn_left")
            Directive.TurnRight -> cfg.str("turn_right")
            Directive.LookUp -> cfg.str("look_up")
            Directive.LookDown -> cfg.str("look_down")
            Directive.Smile -> cfg.str("smile")
            Directive.Blink -> cfg.str("blink")
            else -> cfg.str("hold_still")
        }
    }

    fun onFace(face: Face?, qualityOk: Boolean = true): Boolean {
        if (isFinished || face == null) return false
        val yaw = face.headEulerAngleY * YAW_SIGN
        val pitch = face.headEulerAngleX * PITCH_SIGN
        val smile = face.smilingProbability ?: 0f
        val eyeOpen = minOf(face.leftEyeOpenProbability ?: 1f, face.rightEyeOpenProbability ?: 1f)
        val frontal = abs(yaw) < cfg.neutralYawDeg

        // ---- POSITIONING: capture resting pose, then start ----
        if (!positioned) {
            wrong = false; directionDeg = null
            dbgYaw = yaw; dbgPitchDelta = 0f; dbgSmile = smile; dbgEyeOpen = eyeOpen
            // Only count toward "ready" when frontal AND quality is good (bright + well
            // framed) — so the resting baseline is captured under good conditions.
            if (frontal && qualityOk) { posStable++; posPitchSum += pitch; posPitchN++ } else posStable = 0
            subProgress = (posStable.toFloat() / posNeeded).coerceIn(0f, 1f)
            if (posStable >= posNeeded) {
                neutralPitch = if (posPitchN > 0) posPitchSum / posPitchN else pitch
                positioned = true; subProgress = 0f; actionStartMs = System.currentTimeMillis()
            }
            return false
        }

        if (frontal) sawNeutral = true
        if (eyeOpen > EYE_OPEN) sawEyesOpen = true
        // keep refining the resting pitch when frontal and NOT mid up/down action
        val vertical = current == Directive.LookUp || current == Directive.LookDown
        if (frontal && !vertical) {
            neutralPitch = neutralPitch!! + (pitch - neutralPitch!!) * 0.08f
        }
        val pitchDelta = pitch - (neutralPitch ?: pitch)

        dbgYaw = yaw; dbgPitchDelta = pitchDelta; dbgSmile = smile; dbgEyeOpen = eyeOpen

        // ADAPTIVE thresholds: start at the configured value, then gently relax (down to ~70%)
        // the longer the user works at the current action — precise users pass instantly;
        // strugglers still succeed. Keeps the flow forgiving without being trivially spoofable.
        val k = ease()
        val turnT  = cfg.turnYawDeg * k
        val tiltT  = cfg.tiltPitchDeg * k
        val smileT = cfg.smileThreshold * k
        val blinkT = (cfg.blinkThreshold / k).coerceAtMost(EYE_OPEN - 0.05f)

        // DIRECTION-AWARE turns/tilts: the head must move toward the asked side. Sign is
        // corrected per device via YAW_SIGN / PITCH_SIGN. Moving the opposite way flags `wrong`
        // (red + nudge) and does NOT progress.
        when (current) {
            Directive.TurnLeft  -> { subProgress = (yaw / turnT).coerceIn(0f, 1f); directionDeg = 180f }
            Directive.TurnRight -> { subProgress = (-yaw / turnT).coerceIn(0f, 1f); directionDeg = 0f }
            Directive.LookUp    -> { subProgress = (pitchDelta / tiltT).coerceIn(0f, 1f); directionDeg = 90f }
            Directive.LookDown  -> { subProgress = (-pitchDelta / tiltT).coerceIn(0f, 1f); directionDeg = 270f }
            Directive.Smile     -> { subProgress = (smile / smileT).coerceIn(0f, 1f); directionDeg = null }
            Directive.Blink     -> { subProgress = ((EYE_OPEN - eyeOpen) / (EYE_OPEN - blinkT)).coerceIn(0f, 1f); directionDeg = null }
            else -> { subProgress = 0f; directionDeg = null }
        }
        // moving clearly the WRONG way → red ring + corrective nudge
        wrong = when (current) {
            Directive.TurnLeft  -> yaw < -6f
            Directive.TurnRight -> yaw >  6f
            Directive.LookUp    -> pitchDelta < -6f
            Directive.LookDown  -> pitchDelta >  6f
            else -> false
        }

        val satisfied = when (current) {
            Directive.TurnLeft  -> sawNeutral && yaw >  turnT
            Directive.TurnRight -> sawNeutral && yaw < -turnT
            Directive.LookUp    -> sawNeutral && pitchDelta >  tiltT
            Directive.LookDown  -> sawNeutral && pitchDelta < -tiltT
            Directive.Smile -> smile > smileT
            Directive.Blink -> sawEyesOpen && eyeOpen < blinkT
            else -> false
        }
        if (satisfied) {
            index++; sawNeutral = false; sawEyesOpen = false; subProgress = 0f; wrong = false
            actionStartMs = System.currentTimeMillis()   // reset the adaptive timer for the next action
        }
        return satisfied
    }

    private var actionStartMs = 0L
    /** Relaxation factor 1.0 → 0.70 over ~6s on the current action (adaptive difficulty). */
    private fun ease(): Float {
        if (actionStartMs == 0L) return 1f
        val t = ((System.currentTimeMillis() - actionStartMs) / 6000f).coerceIn(0f, 1f)
        return 1f - 0.30f * t
    }

    /** One-line live readout for the diagnostic overlay. */
    fun debugLine(): String = "yaw %.0f  dPitch %.0f  smile %.2f  eye %.2f  [%s]".format(
        dbgYaw, dbgPitchDelta, dbgSmile, dbgEyeOpen, current.name)
}
