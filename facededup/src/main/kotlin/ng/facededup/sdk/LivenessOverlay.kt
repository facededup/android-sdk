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

    // Opaque WHITE surround (hides the camera everywhere except the oval).
    private val surround = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    // The card panel — a dark, translucent grey over the white surround (configurable).
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33000000") }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(7f); strokeCap = Paint.Cap.ROUND
    }
    // Soft glow around the oval — used for smile/blink (which have no directional arc).
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
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
        color = Color.parseColor("#444444"); textAlign = Paint.Align.CENTER; textSize = dp(13f)
    }

    private val cardRect = RectF()
    private val ovalRect = RectF()
    private var ccx = 0f; private var ccy = 0f
    private var cardCorner = dp(28f)

    var ringColor: Int = GREEN                 // kept for API compat
    var actionProgress: Float = 0f             // current action 0..1 (drives the arc)
    private var shownAction: Float = 0f
    var directionDeg: Float? = null            // 0=right,90=up,180=left,270=down; null = no direction
    var present: Boolean = false               // a single face is in frame
    var glowAction: Boolean = false            // current action is smile/blink → glow the oval
    var success: Boolean = false
    var wrong: Boolean = false
    var diagnostic: String? = null
    /** Invoked after geometry is computed in onSizeChanged — host uses it to place views. */
    var onLaidOut: (() -> Unit)? = null

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
        runCatching { scrimHex?.let { card.color = Color.parseColor(it) } }
    }
    private var successColor = GREEN

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    // Geometry anchors the host uses to place the instruction + status views.
    fun cardTopPx(): Float = cardRect.top
    fun cardBottomPx(): Float = cardRect.bottom
    fun ovalBottomPx(): Float = ovalRect.bottom
    fun ovalTopPx(): Float = ovalRect.top

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val ovw = w * 0.62f                          // oval — position the face inside it
        val ovh = ovw * 1.30f                        // tall oval (not a circle)
        ccx = w / 2f
        ccy = h * 0.44f
        ovalRect.set(ccx - ovw / 2, ccy - ovh / 2, ccx + ovw / 2, ccy + ovh / 2)
        val m = w * 0.06f
        // card wraps the oval: room for the arrow above + the instruction below.
        cardRect.set(m, ovalRect.top - dp(64f), w - m, ovalRect.bottom + dp(96f))
        post { onLaidOut?.invoke() }
    }

    override fun onDraw(canvas: Canvas) {
        shownAction += ((if (success) 1f else actionProgress) - shownAction) * 0.16f
        if (kotlin.math.abs((if (success) 1f else actionProgress) - shownAction) < 0.002f)
            shownAction = if (success) 1f else actionProgress

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), surround)  // white surround
        canvas.drawRoundRect(cardRect, cardCorner, cardCorner, card)         // translucent grey card
        canvas.drawOval(ovalRect, clear)                                    // punch oval camera window

        val g = dp(13f)                              // spaced out from the oval edge
        val arcRect = RectF(ovalRect.left - g, ovalRect.top - g, ovalRect.right + g, ovalRect.bottom + g)

        if (success) {
            // full green ring + tick
            arc.color = successColor
            canvas.drawArc(arcRect, 0f, 360f, false, arc)
            drawTick(canvas)
        } else if (present) {
            val a = shownAction.coerceIn(0f, 1f)
            // smile/blink have no direction → glow the whole oval, intensifying with progress
            if (glowAction) {
                glow.color = successColor
                glow.strokeWidth = arc.strokeWidth + dp(10f)
                glow.alpha = (60 + 150 * a).toInt().coerceIn(40, 210)
                canvas.drawOval(arcRect, glow)
                glow.alpha = 255
            }
            // single progress arc growing with the current action
            val screenCenter = -(directionDeg ?: 90f)   // math→screen; null → top
            val sweep = (24f + 132f * a)                // a calm, growing arc
            arc.color = if (wrong) WRONG else successColor
            canvas.drawArc(arcRect, screenCenter - sweep / 2f, sweep, false, arc)
            // directional arrow above the oval (only for directional actions)
            directionDeg?.let { drawArrow(canvas, it) }
        }

        diagnostic?.let { canvas.drawText(it, width / 2f, height - dp(40f), diag) }
    }

    private fun drawArrow(canvas: Canvas, deg: Float) {
        val ax = ccx; val ay = ovalRect.top - dp(34f); val r = dp(15f)
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
        val cx = ccx; val cy = ccy + ovalRect.height() * 0.07f; val s = dp(15f)
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
