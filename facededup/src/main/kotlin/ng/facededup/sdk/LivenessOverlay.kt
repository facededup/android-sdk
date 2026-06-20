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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated camera overlay (2.0): dimmed scrim + oval cut-out, a faint track ring, a
 * PROGRESS arc that fills as the user completes the challenge, a live rotating "sweep"
 * that shows it's working, a directional chevron (turn left/right) that nudges, and a
 * success tick. Native replacement for the web flow's animated arc — no WebView.
 */
internal class LivenessOverlay(ctx: Context) : View(ctx) {

    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC0B1F3A") }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(4f); color = Color.parseColor("#33FFFFFF")
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(6f); strokeCap = Paint.Cap.ROUND
    }
    private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(6f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(7f); strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; color = Color.parseColor("#22A447")
    }
    private val oval = RectF()
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    var ringColor: Int = Color.parseColor("#1E9C69")
    /** 0..1 overall challenge completion. */
    var progress: Float = 0f
    /** Arc-highlight angle (0 = right, 180 = left) for a directional cue, or null. */
    var directionDeg: Float? = null
    var success: Boolean = false

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val ow2 = w * 0.66f; val oh2 = ow2 * 1.32f
        val cx = w / 2f; val cy = h * 0.46f
        oval.set(cx - ow2 / 2, cy - oh2 / 2, cx + ow2 / 2, cy + oh2 / 2)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        canvas.drawOval(oval, clear)                 // punch the hole
        canvas.drawOval(oval, track)                 // faint full track

        val col = if (success) Color.parseColor("#22A447") else ringColor
        arc.color = col
        if (progress > 0f) canvas.drawArc(oval, -90f, 360f * progress.coerceIn(0f, 1f), false, arc)

        if (success) { drawTick(canvas); return }

        // breathing brand ring while positioning / low progress — always-visible "alive" cue
        if (progress < 0.02f) {
            val pulse = 0.30f + 0.30f * (0.5f + 0.5f * sin(phase * 2 * Math.PI).toFloat())
            arc.alpha = (pulse * 255).toInt()
            canvas.drawOval(oval, arc)
            arc.alpha = 255
        }
        // live "sweep" comet rotating around the ring
        val sweepStart = -90f + phase * 360f
        arc.alpha = 170
        canvas.drawArc(oval, sweepStart, 46f, false, arc)
        arc.alpha = 255

        directionDeg?.let { deg -> drawChevron(canvas, deg, col) }
    }

    private fun drawChevron(canvas: Canvas, deg: Float, color: Int) {
        chevron.color = color
        val rad = Math.toRadians(deg.toDouble())
        val rx = oval.width() / 2f; val ry = oval.height() / 2f
        val cx = oval.centerX(); val cy = oval.centerY()
        val nudge = dp(6f) * (0.5f + 0.5f * sin(phase * 2 * Math.PI).toFloat())
        val ex = cx + (rx + dp(18f) + nudge) * cos(rad).toFloat()
        val ey = cy - (ry + dp(18f) + nudge) * sin(rad).toFloat()
        val s = dp(13f)
        val dir = if (deg >= 90f) -1f else 1f   // left chevron points left, right points right
        val p = Path()
        p.moveTo(ex - dir * s, ey - s); p.lineTo(ex + dir * s, ey); p.lineTo(ex - dir * s, ey + s)
        canvas.drawPath(p, chevron)
    }

    private fun drawTick(canvas: Canvas) {
        val cx = oval.centerX(); val cy = oval.centerY() + oval.height() * 0.18f; val s = dp(16f)
        val p = Path()
        p.moveTo(cx - s, cy); p.lineTo(cx - s * 0.2f, cy + s * 0.8f); p.lineTo(cx + s, cy - s * 0.8f)
        canvas.drawPath(p, tick)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
