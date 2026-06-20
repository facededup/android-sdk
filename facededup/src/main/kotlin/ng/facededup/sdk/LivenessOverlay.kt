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
 * Card-style liveness overlay (banking-grade, original implementation):
 *
 *   • a subtly dimmed live-camera background,
 *   • a floating white rounded CARD,
 *   • a CIRCULAR window punched in the card (the camera shows through),
 *   • a small directional ARROW above the circle, and
 *   • a SINGLE green progress ARC on the side the user must turn toward, growing
 *     smoothly with the current action (no full ring, no glow, no pulsing).
 *
 * The instruction text (top of card) and status toast (below the card) are Views
 * placed by the host activity using [cardTopPx]/[cardBottomPx].
 */
internal class LivenessOverlay(ctx: Context) : View(ctx) {

    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#22000000") }
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(7f); strokeCap = Paint.Cap.ROUND
    }
    private val arrowBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#16181C") }
    private val arrowFg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.6f); strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; color = Color.WHITE
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(7f); strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; color = GREEN
    }
    private val diag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF"); textAlign = Paint.Align.CENTER; textSize = dp(13f)
    }

    private val cardRect = RectF()
    private var ccx = 0f; private var ccy = 0f; private var rad = 0f
    private var cardCorner = dp(28f)

    var ringColor: Int = GREEN                 // kept for API compat
    var actionProgress: Float = 0f             // current action 0..1 (drives the arc)
    private var shownAction: Float = 0f
    var directionDeg: Float? = null            // 0=right,90=up,180=left,270=down; null = no direction
    var present: Boolean = false               // a single face is in frame
    var success: Boolean = false
    var wrong: Boolean = false
    var diagnostic: String? = null

    // A low-frequency animator only keeps invalidating so shownAction eases smoothly.
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    /** Arc thickness + success colour + dim/scrim colour (ring param kept for compat). */
    fun applyConfig(ringWidthDp: Float, ring: Int, success: Int, scrimHex: String?) {
        val px = dp(ringWidthDp)
        arc.strokeWidth = px; tick.strokeWidth = px
        successColor = success
        runCatching { scrimHex?.let { dim.color = Color.parseColor(it) } }
    }
    private var successColor = GREEN

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    // Geometry anchors the host uses to place the instruction + status views.
    fun cardTopPx(): Float = cardRect.top
    fun cardBottomPx(): Float = cardRect.bottom
    fun circleTopPx(): Float = ccy - rad
    // legacy accessors (kept so existing callers compile)
    fun ovalBottomPx(): Float = cardRect.bottom
    fun ovalTopPx(): Float = cardRect.top

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        rad = w * 0.27f
        ccx = w / 2f
        ccy = h * 0.46f
        val m = w * 0.06f
        cardRect.set(m, ccy - rad - dp(140f), w - m, ccy + rad + dp(44f))
    }

    override fun onDraw(canvas: Canvas) {
        shownAction += ((if (success) 1f else actionProgress) - shownAction) * 0.16f
        if (kotlin.math.abs((if (success) 1f else actionProgress) - shownAction) < 0.002f)
            shownAction = if (success) 1f else actionProgress

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)    // dim the camera bg
        canvas.drawRoundRect(cardRect, cardCorner, cardCorner, card)       // white card
        canvas.drawCircle(ccx, ccy, rad, clear)                           // punch camera window

        val arcRect = RectF(ccx - rad - dp(7f), ccy - rad - dp(7f), ccx + rad + dp(7f), ccy + rad + dp(7f))

        if (success) {
            // full green ring + tick
            arc.color = successColor
            canvas.drawArc(arcRect, 0f, 360f, false, arc)
            drawTick(canvas)
        } else if (present) {
            // single directional arc growing with the current action
            val a = shownAction.coerceIn(0f, 1f)
            val screenCenter = -(directionDeg ?: 90f)   // math→screen; null → top
            val sweep = (24f + 132f * a)                // a calm, growing arc
            arc.color = if (wrong) WRONG else successColor
            canvas.drawArc(arcRect, screenCenter - sweep / 2f, sweep, false, arc)
            // directional arrow above the circle (only for directional actions)
            directionDeg?.let { drawArrow(canvas, it) }
        }

        diagnostic?.let { canvas.drawText(it, width / 2f, height - dp(40f), diag) }
    }

    private fun drawArrow(canvas: Canvas, deg: Float) {
        val ax = ccx; val ay = ccy - rad - dp(40f); val r = dp(15f)
        canvas.drawCircle(ax, ay, r, arrowBg)
        // a right-pointing chevron, rotated to the direction (screen angle = -deg)
        canvas.save()
        canvas.rotate(-deg, ax, ay)
        val s = dp(6.5f)
        val p = Path()
        p.moveTo(ax - s * 0.5f, ay - s); p.lineTo(ax + s * 0.7f, ay); p.lineTo(ax - s * 0.5f, ay + s)
        canvas.drawPath(p, arrowFg)
        canvas.restore()
    }

    private fun drawTick(canvas: Canvas) {
        tick.color = successColor
        val cx = ccx; val cy = ccy + rad * 0.10f; val s = dp(15f)
        val p = Path()
        p.moveTo(cx - s, cy); p.lineTo(cx - s * 0.2f, cy + s * 0.8f); p.lineTo(cx + s, cy - s * 0.8f)
        canvas.drawPath(p, tick)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        val GREEN: Int = Color.parseColor("#7BCB7E")           // soft, calm green (per reference)
        private val WRONG: Int = Color.parseColor("#E24B4A")
    }
}
