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

/**
 * Clean, banking-grade liveness overlay: an opaque white surround with a tall oval
 * cut-out (only the capture area shows the camera), and a SINGLE neutral ring split
 * into one segment per challenge. As each challenge passes, its segment animates
 * smoothly to green — no glow halos, no pulsing, no stacked rings. Calm by design.
 */
internal class LivenessOverlay(ctx: Context) : View(ctx) {

    // Opaque white surround so only the oval (capture area) shows the camera.
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    // Neutral base ring (unfilled segments).
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(6f); strokeCap = Paint.Cap.ROUND
    }
    // Green progress fill (completed / filling segments).
    private val prog = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(6f); strokeCap = Paint.Cap.ROUND
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(7f); strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; color = GREEN
    }
    private val diag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111111"); textAlign = Paint.Align.CENTER; textSize = dp(13f)
    }

    private val oval = RectF()

    // A single low-frequency animator drives only the smooth easing of the fill —
    // it does NOT pulse anything (no breathing/glow), it just keeps invalidating
    // while shownProgress catches up to the target.
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    var ringColor: Int = GREEN            // kept for API compat; not used for the base ring
    var progress: Float = 0f              // overall progress 0..1 (target; eased)
    private var shownProgress: Float = 0f
    var segments: Int = 3                 // one ring segment per challenge
    var directionDeg: Float? = null       // kept for API compat; cues removed for a calm UI
    var success: Boolean = false
    var wrong: Boolean = false
    /** Live signal readout (shown only when diagnostics are enabled). */
    var diagnostic: String? = null

    /** Developer-configurable ring thickness + colours + scrim. */
    fun applyConfig(ringWidthDp: Float, ring: Int, success: Int, scrimHex: String?) {
        val px = dp(ringWidthDp)
        this.ring.strokeWidth = px; prog.strokeWidth = px; tick.strokeWidth = px
        successColor = success
        runCatching { scrimHex?.let { scrim.color = Color.parseColor(it) } }
    }
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
        // gentle ease toward the target — smooth + elegant, no snapping
        val target = if (success) 1f else progress
        shownProgress += (target - shownProgress) * 0.16f
        if (kotlin.math.abs(target - shownProgress) < 0.002f) shownProgress = target

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        canvas.drawOval(oval, clear)                   // punch the camera window

        val total = segments.coerceAtLeast(1)
        val gapDeg = if (total > 1) 7f else 0f         // small gap between segments
        val segDeg = 360f / total
        val fillUnits = shownProgress.coerceIn(0f, 1f) * total
        val active = fillUnits.toInt().coerceIn(0, total - 1)

        for (i in 0 until total) {
            val start = -90f + i * segDeg + gapDeg / 2f
            val sweep = segDeg - gapDeg
            // neutral base — soft red on the ACTIVE segment if the user moves the wrong way
            ring.color = if (wrong && i == active && !success) WRONG else NEUTRAL
            canvas.drawArc(oval, start, sweep, false, ring)
            // green fill for the completed portion of this segment
            val f = (fillUnits - i).coerceIn(0f, 1f)
            if (f > 0.001f && !(wrong && i == active)) {
                prog.color = successColor
                canvas.drawArc(oval, start, sweep * f, false, prog)
            }
        }

        diagnostic?.let { canvas.drawText(it, width / 2f, height - dp(70f), diag) }

        if (success) drawTick(canvas)
    }

    private fun drawTick(canvas: Canvas) {
        tick.color = successColor
        val cx = oval.centerX(); val cy = oval.centerY() + oval.height() * 0.16f; val s = dp(16f)
        val p = Path()
        p.moveTo(cx - s, cy); p.lineTo(cx - s * 0.2f, cy + s * 0.8f); p.lineTo(cx + s, cy - s * 0.8f)
        canvas.drawPath(p, tick)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        val GREEN: Int = Color.parseColor("#2CC05C")
        private val NEUTRAL: Int = Color.parseColor("#E2E5EA")   // calm light-grey default ring
        private val WRONG: Int = Color.parseColor("#E24B4A")     // subtle red on wrong move
    }
}
