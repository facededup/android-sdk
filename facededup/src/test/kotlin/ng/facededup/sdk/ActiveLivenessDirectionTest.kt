package ng.facededup.sdk

import ng.facededup.sdk.ActiveLiveness.Directive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the head-turn challenge direction logic against left/right (and up/down) INVERSION.
 *
 * The yaw passed here is already sign-corrected (device `YAW_SIGN` applied upstream), so these
 * tests pin the *relationship*: TurnLeft and TurnRight must be exact opposites, and moving the
 * wrong way must flag `wrong`. If anyone swaps a comparison, these fail.
 */
class ActiveLivenessDirectionTest {

    private val turnT = 18f
    private val tiltT = 12f
    private val smileT = 0.5f
    private val blinkT = 0.4f

    private fun met(dir: Directive, yaw: Float = 0f, pitch: Float = 0f, smile: Float = 0f,
                    eye: Float = 1f, neutral: Boolean = true, eyesOpen: Boolean = true) =
        ActiveLiveness.poseSatisfied(dir, yaw, pitch, smile, eye, neutral, eyesOpen,
            turnT, tiltT, smileT, blinkT)

    @Test fun turnLeft_and_turnRight_are_exact_opposites() {
        // A strong turn one way satisfies LEFT and is WRONG for RIGHT...
        assertTrue("turn-left should pass on +yaw", met(Directive.TurnLeft, yaw = 25f))
        assertFalse("turn-left must NOT pass on -yaw", met(Directive.TurnLeft, yaw = -25f))
        assertTrue("turn-right should pass on -yaw", met(Directive.TurnRight, yaw = -25f))
        assertFalse("turn-right must NOT pass on +yaw", met(Directive.TurnRight, yaw = 25f))
        // ...and the wrong-move detector agrees (no inversion).
        assertTrue(ActiveLiveness.wrongMove(Directive.TurnLeft, yaw = -25f, pitchDelta = 0f))
        assertFalse(ActiveLiveness.wrongMove(Directive.TurnLeft, yaw = 25f, pitchDelta = 0f))
        assertTrue(ActiveLiveness.wrongMove(Directive.TurnRight, yaw = 25f, pitchDelta = 0f))
    }

    @Test fun lookUp_and_lookDown_are_exact_opposites() {
        assertTrue(met(Directive.LookUp, pitch = 18f))
        assertFalse(met(Directive.LookUp, pitch = -18f))
        assertTrue(met(Directive.LookDown, pitch = -18f))
        assertFalse(met(Directive.LookDown, pitch = 18f))
        assertTrue(ActiveLiveness.wrongMove(Directive.LookUp, yaw = 0f, pitchDelta = -18f))
        assertTrue(ActiveLiveness.wrongMove(Directive.LookDown, yaw = 0f, pitchDelta = 18f))
    }

    @Test fun turn_below_threshold_does_not_pass() {
        assertFalse(met(Directive.TurnLeft, yaw = turnT - 1f))
        assertTrue(met(Directive.TurnLeft, yaw = turnT + 1f))
    }

    @Test fun turn_requires_a_neutral_baseline_first() {
        assertFalse("must return to frontal before a turn counts",
            met(Directive.TurnLeft, yaw = 30f, neutral = false))
        assertTrue(met(Directive.TurnLeft, yaw = 30f, neutral = true))
    }

    @Test fun smile_and_blink_have_no_wrong_direction() {
        assertTrue(met(Directive.Smile, smile = 0.7f))
        assertFalse(met(Directive.Smile, smile = 0.3f))
        assertTrue(met(Directive.Blink, eye = 0.2f, eyesOpen = true))
        assertFalse(met(Directive.Blink, eye = 0.9f, eyesOpen = true))
        assertFalse(ActiveLiveness.wrongMove(Directive.Smile, yaw = 50f, pitchDelta = 50f))
        assertFalse(ActiveLiveness.wrongMove(Directive.Blink, yaw = 50f, pitchDelta = 50f))
    }
}
