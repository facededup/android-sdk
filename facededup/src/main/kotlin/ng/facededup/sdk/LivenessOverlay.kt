package ng.facededup.sdk

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * Light-themed liveness overlay: a light-grey scrim with a tall oval cut-out (camera
 * shows through), a solid ring around the oval (green when the face is present, red when
 * not / multiple faces), a progress arc that fills as the challenge completes, a curved
 * directional cue OUTSIDE the oval on the side to move toward, and a success tick.
 * Background stays light so the host page reads as a clean white screen.
 */
internal class LivenessOverlay(ctx: Context) : View(ctx) {

    // Opaque white surround so ONLY the oval (the capture area) shows the camera — the rest
    // of the screen reads as a clean white frame, not a dimmed live view of the room.
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(5f); strokeCap = Paint.Cap.ROUND
    }
    private val prog = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(7f); strokeCap = Paint.Cap.ROUND
    }
    private val cue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(7f); strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#2D2B2A")
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(7f); strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; color = GREEN
    }
    private val oval = RectF()
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    private val diag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111111"); textAlign = Paint.Align.CENTER; textSize = dp(13f)
    }

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    var ringColor: Int = GREEN
    var progress: Float = 0f                 // target; the ring eases toward it (smooth)
    private var shownProgress: Float = 0f
    /** Current action's live sub-progress (0..1) — drives the smart oval glow (smile/blink/turn). */
    var actionProgress: Float = 0f
    private var shownAction: Float = 0f
    var directionDeg: Float? = null
    var success: Boolean = false
    var wrong: Boolean = false
    /** Live signal readout (shown only when diagnostics are enabled). */
    var diagnostic: String? = null

    /** Developer-configurable ring thickness + colours + scrim. */
    fun applyConfig(ringWidthDp: Float, ring: Int, success: Int, scrimHex: String?) {
        val px = dp(ringWidthDp)
        this.ring.strokeWidth = px * 0.78f; prog.strokeWidth = px; cue.strokeWidth = px; tick.strokeWidth = px
        defaultRing = ring; successColor = success
        runCatching { scrimHex?.let { scrim.color = Color.parseColor(it) } }
    }
    private var defaultRing = GREEN
    private var successColor = GREEN

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    fun ovalBottomPx(): Float = oval.bottom
    fun ovalTopPx(): Float = oval.top

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val ow2 = w * 0.66f; val oh2 = ow2 * 1.32f
        val cx = w / 2f; val cy = h * 0.44f
        oval.set(cx - ow2 / 2, cy - oh2 / 2, cx + ow2 / 2, cy + oh2 / 2)
    }

    override fun onDraw(canvas: Canvas) {
        shownProgress += (progress - shownProgress) * 0.25f      // ease toward target (smooth)
        shownAction += ((if (success) 1f else actionProgress) - shownAction) * 0.30f
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        canvas.drawOval(oval, clear)                   // punch the camera window

        val col = if (success) successColor else ringColor
        // SMART GLOW: the oval glows greener + brighter + wider as the current action
        // (smile, blink, turn…) gets closer to satisfied — instant positive feedback.
        val act = shownAction.coerceIn(0f, 1f)
        val pulse = (0.5 + 0.5 * sin(phase * 2 * Math.PI)).toFloat()
        glow.color = if (wrong) col else lerpColor(col, successColor, act)
        glow.strokeWidth = ring.strokeWidth + dp(10f) + dp(16f) * act
        glow.alpha = (40 + 30 * pulse + 150 * act).toInt().coerceIn(20, 220)
        canvas.drawOval(oval, glow); glow.alpha = 255

        // solid ring (green when placed/active; red on wrong move; breathes while positioning)
        ring.color = if (wrong) col else lerpColor(col, successColor, act)
        ring.alpha = if (shownProgress < 0.02f && !success && act < 0.02f)
            (170 + 70 * sin(phase * 2 * Math.PI)).toInt().coerceIn(90, 255) else 255
        canvas.drawOval(oval, ring); ring.alpha = 255

        diagnostic?.let { canvas.drawText(it, width / 2f, height - dp(70f), diag) }

        if (success) {
            // Completed: fill the WHOLE ring green, then the tick.
            prog.color = successColor
            canvas.drawArc(oval, -90f, 360f, false, prog)
            drawTick(canvas)
            return
        }

        // progress arc fills (eased) from the top as the action completes
        if (shownProgress > 0.02f) {
            prog.color = if (wrong) col else successColor
            canvas.drawArc(oval, -90f, 360f * shownProgress.coerceIn(0f, 1f), false, prog)
        }
        // curved directional cue OUTSIDE the oval on the move side
        directionDeg?.let { drawCue(canvas, it) }
    }

    /** ARGB lerp a→b by t (0..1) — used to tint the ring/glow toward green as an action completes. */
    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        val ar = (a ushr 16) and 0xFF; val ag = (a ushr 8) and 0xFF; val ab = a and 0xFF
        val br = (b ushr 16) and 0xFF; val bg = (b ushr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * u).toInt(); val g = (ag + (bg - ag) * u).toInt(); val bl = (ab + (bb - ab) * u).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun drawCue(canvas: Canvas, deg: Float) {
        val pad = dp(14f) + dp(6f) * (0.5f + 0.5f * sin(phase * 2 * Math.PI).toFloat())
        val r = RectF(oval.left - pad, oval.top - pad, oval.right + pad, oval.bottom + pad)
        // math angle (0=right,90=up,180=left,270=down) → screen sweep angle (0=right, CW+)
        val center = -deg
        canvas.drawArc(r, center - 27f, 54f, false, cue)
    }

    private fun drawTick(canvas: Canvas) {
        tick.color = successColor
        val cx = oval.centerX(); val cy = oval.centerY() + oval.height() * 0.16f; val s = dp(16f)
        val p = Path()
        p.moveTo(cx - s, cy); p.lineTo(cx - s * 0.2f, cy + s * 0.8f); p.lineTo(cx + s, cy - s * 0.8f)
        canvas.drawPath(p, tick)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object { val GREEN: Int = Color.parseColor("#2CC05C") }
}
