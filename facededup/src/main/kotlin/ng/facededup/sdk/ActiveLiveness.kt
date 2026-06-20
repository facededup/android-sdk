package ng.facededup.sdk

import com.google.mlkit.vision.face.Face
import kotlin.math.abs

/**
 * Native active-liveness challenge driven by ML Kit head pose + smile probability.
 * No WebView: the camera analyzer feeds [onFace] each frame; the activity reads the
 * current [Directive]/[hint] for the overlay and captures a proving frame whenever a
 * directive is satisfied (a deliberate movement FROM a neutral, frontal pose).
 *
 * The server (POST /v1/offline/submit) still makes the authoritative liveness decision
 * from the submitted frames — this only drives the on-device UX + which frames to send.
 */
internal class ActiveLiveness(actions: List<String>) {

    enum class Directive(val proves: String?) {
        Positioning(null), TurnLeft("turn_left"), TurnRight("turn_right"),
        Smile("smile"), Done(null)
    }

    private companion object {
        const val TURN_YAW = 22f      // deg — a clear head turn
        const val SMILE = 0.62f       // ML Kit smiling probability
        const val NEUTRAL_YAW = 10f   // must return near-frontal between actions
        // ML Kit headEulerAngleY sign vs the user's left/right on a FRONT camera.
        // Flip to -1 if a device test shows turn left/right swapped.
        const val YAW_SIGN = 1f
    }

    // Build the directive sequence from the requested actions (default: turn + smile).
    private val steps: List<Directive> = run {
        val map = mapOf(
            "turn_left" to Directive.TurnLeft, "turn_right" to Directive.TurnRight,
            "smile" to Directive.Smile,
        )
        val picked = actions.mapNotNull { map[it] }
        (if (picked.isEmpty()) listOf(Directive.TurnLeft, Directive.TurnRight, Directive.Smile)
         else picked)
    }

    private var index = 0
    private var sawNeutral = false   // require a frontal baseline before counting a turn

    val isFinished: Boolean get() = index >= steps.size
    val current: Directive get() = if (isFinished) Directive.Done else steps[index]
    val total: Int get() = steps.size
    val progress: Int get() = index

    /** The instruction to show for the current directive. */
    fun hint(facePresent: Boolean): String = when {
        !facePresent -> "Center your face in the oval"
        else -> when (current) {
            Directive.TurnLeft -> "Slowly turn your head left"
            Directive.TurnRight -> "Slowly turn your head right"
            Directive.Smile -> "Smile"
            else -> "Hold still"
        }
    }

    /**
     * Feed one detected face (or null). Returns true exactly when the current directive
     * was just satisfied — the caller should capture a proving frame and advance.
     */
    fun onFace(face: Face?): Boolean {
        if (isFinished || face == null) return false
        val yaw = (face.headEulerAngleY) * YAW_SIGN
        val smile = face.smilingProbability ?: 0f

        // Need a near-frontal baseline before a turn counts (no holding a pose / rushing).
        if (abs(yaw) < NEUTRAL_YAW) sawNeutral = true

        val satisfied = when (current) {
            Directive.TurnLeft  -> sawNeutral && yaw >  TURN_YAW
            Directive.TurnRight -> sawNeutral && yaw < -TURN_YAW
            Directive.Smile     -> smile > SMILE
            else -> false
        }
        if (satisfied) { index++; sawNeutral = false }
        return satisfied
    }

    /** Action keys (in order) for the submit payload's `client_actions`. */
    fun actionKeys(): List<String> = steps.mapNotNull { it.proves }
}
