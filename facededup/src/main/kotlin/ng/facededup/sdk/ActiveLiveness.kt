package ng.facededup.sdk

import com.google.mlkit.vision.face.Face
import java.security.SecureRandom
import kotlin.math.abs

/**
 * Native active-liveness challenge driven by ML Kit head pose + smile + eye-open
 * probabilities. Pool: turn left/right, look up/down, smile, blink — a RANDOMISED
 * distinct sequence each run (anti-replay), guaranteeing ≥1 head-motion action. The
 * server (/v1/offline/submit) still makes the authoritative decision; this only drives
 * the on-device UX and selects which frames to submit.
 */
internal class ActiveLiveness(actions: List<String>) {

    enum class Directive(val proves: String?) {
        Positioning(null), TurnLeft("turn_left"), TurnRight("turn_right"),
        LookUp("look_up"), LookDown("look_down"), Smile("smile"), Blink("blink"), Done(null)
    }

    private companion object {
        const val TURN_YAW = 22f      // deg — a clear head turn (left/right)
        const val TILT_PITCH = 14f    // deg — a clear head tilt (up/down)
        const val SMILE = 0.62f       // ML Kit smiling probability
        const val EYE_OPEN = 0.65f    // both eyes "open" baseline
        const val EYE_SHUT = 0.35f    // both eyes "closed" -> blink
        const val NEUTRAL_YAW = 10f   // return near-frontal between turns
        const val NEUTRAL_PITCH = 8f  // return near-level between tilts
        // ML Kit sign vs the user's directions on a FRONT camera. Flip if a device test
        // shows a direction inverted (turns ⇄ YAW_SIGN, up/down ⇄ PITCH_SIGN).
        const val YAW_SIGN = 1f
        const val PITCH_SIGN = 1f     // ML Kit: positive headEulerAngleX = looking up
    }

    private val pool = listOf(
        Directive.TurnLeft, Directive.TurnRight, Directive.LookUp,
        Directive.LookDown, Directive.Smile, Directive.Blink)
    private val motion = setOf(Directive.TurnLeft, Directive.TurnRight, Directive.LookUp, Directive.LookDown)

    // Build the sequence: explicit actions if given, else a random distinct set of 3
    // (≥1 head-motion), order-shuffled.
    private val steps: List<Directive> = run {
        val map = mapOf(
            "turn_left" to Directive.TurnLeft, "turn_right" to Directive.TurnRight,
            "look_up" to Directive.LookUp, "look_down" to Directive.LookDown,
            "smile" to Directive.Smile, "blink" to Directive.Blink)
        val explicit = actions.mapNotNull { map[it] }
        if (explicit.isNotEmpty()) explicit else randomSequence(3)
    }

    private fun randomSequence(n: Int): List<Directive> {
        val r = SecureRandom()
        val motions = motion.toMutableList(); val rest = pool.filter { it !in motion }.toMutableList()
        val out = ArrayList<Directive>()
        out.add(motions.removeAt(r.nextInt(motions.size)))   // guarantee ≥1 head-motion
        val remaining = (motions + rest).toMutableList()
        while (out.size < n && remaining.isNotEmpty()) out.add(remaining.removeAt(r.nextInt(remaining.size)))
        for (i in out.size - 1 downTo 1) { val j = r.nextInt(i + 1); val t = out[i]; out[i] = out[j]; out[j] = t }
        return out
    }

    private var index = 0
    private var sawNeutral = false   // frontal/level baseline before a head-motion counts
    private var sawEyesOpen = false  // eyes-open baseline before a blink counts

    var subProgress = 0f; private set
    /** Movement-direction angle for the arrow cue (math: 0=right, 90=up, 180=left, 270=down), or null. */
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

    /** Feed one detected face (or null). Returns true the moment the current directive is satisfied. */
    fun onFace(face: Face?): Boolean {
        if (isFinished || face == null) return false
        val yaw = face.headEulerAngleY * YAW_SIGN
        val pitch = face.headEulerAngleX * PITCH_SIGN
        val smile = face.smilingProbability ?: 0f
        val eyeOpen = minOf(face.leftEyeOpenProbability ?: 1f, face.rightEyeOpenProbability ?: 1f)

        if (abs(yaw) < NEUTRAL_YAW && abs(pitch) < NEUTRAL_PITCH) sawNeutral = true
        if (eyeOpen > EYE_OPEN) sawEyesOpen = true

        when (current) {
            Directive.TurnLeft  -> { subProgress = (yaw / TURN_YAW).coerceIn(0f, 1f); directionDeg = 180f }
            Directive.TurnRight -> { subProgress = (-yaw / TURN_YAW).coerceIn(0f, 1f); directionDeg = 0f }
            Directive.LookUp    -> { subProgress = (pitch / TILT_PITCH).coerceIn(0f, 1f); directionDeg = 90f }
            Directive.LookDown  -> { subProgress = (-pitch / TILT_PITCH).coerceIn(0f, 1f); directionDeg = 270f }
            Directive.Smile     -> { subProgress = (smile / SMILE).coerceIn(0f, 1f); directionDeg = null }
            Directive.Blink     -> { subProgress = ((EYE_OPEN - eyeOpen) / (EYE_OPEN - EYE_SHUT)).coerceIn(0f, 1f); directionDeg = null }
            else -> { subProgress = 0f; directionDeg = null }
        }

        val satisfied = when (current) {
            Directive.TurnLeft  -> sawNeutral && yaw >  TURN_YAW
            Directive.TurnRight -> sawNeutral && yaw < -TURN_YAW
            Directive.LookUp    -> sawNeutral && pitch >  TILT_PITCH
            Directive.LookDown  -> sawNeutral && pitch < -TILT_PITCH
            Directive.Smile     -> smile > SMILE
            Directive.Blink     -> sawEyesOpen && eyeOpen < EYE_SHUT
            else -> false
        }
        if (satisfied) { index++; sawNeutral = false; sawEyesOpen = false; subProgress = 0f }
        return satisfied
    }
}
