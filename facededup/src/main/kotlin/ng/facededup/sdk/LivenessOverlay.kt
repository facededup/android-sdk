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
 * Liveness overlay styled to match the original hosted (WebView) flow: a light page with
 * a white rounded "stage" card, an oval camera window with a thin border that turns green,
 * the instruction ABOVE the oval with a dark-navy directional arrow, a green progress arc,
 * a filled green check badge on success, and pulsing brand dots while verifying.
 *
 * The instruction text is a View placed by the host activity via [ovalTopPx].
 */
internal class LivenessOverlay(ctx: Context) : View(ctx) {

    private val surround = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PAGE }   // light page
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE } // white stage (configurable)
    private val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.parseColor("#E6EAEC")
    }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(5f); strokeCap = Paint.Cap.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arrowBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NAVY }   // dark-navy arrow chip
    private val arrowFg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.6f); strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; color = Color.WHITE
    }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN }     // filled green check circle
    private val badgeTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.WHITE; strokeWidth = dp(5f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BRAND }       // pulsing guide dots
    private val diag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B"); textAlign = Paint.Align.CENTER; textSize = dp(13f)
    }

    private val cardRect = RectF()
    private val ovalRect = RectF()
    private var ccx = 0f; private var ccy = 0f
    private var cardCorner = dp(24f)
    private var phase = 0f

    var ringColor: Int = GREEN                 // kept for API compat
    var actionProgress: Float = 0f             // current action 0..1 (drives the arc)
    private var shownAction: Float = 0f
    var directionDeg: Float? = null            // 0=right,90=up,180=left,270=down; null = no direction
    var present: Boolean = false               // a single face is in frame
    var glowAction: Boolean = false            // current action is smile/blink → glow the oval
    var success: Boolean = false
    var verifying: Boolean = false             // capture done, check in flight → pulsing dots
    var wrong: Boolean = false
    var diagnostic: String? = null
    /** Invoked after geometry is computed in onSizeChanged — host uses it to place views. */
    var onLaidOut: (() -> Unit)? = null

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    /** Arc/border thickness + success colour + card colour (ring param kept for compat). */
    fun applyConfig(ringWidthDp: Float, ring: Int, success: Int, scrimHex: String?) {
        val px = dp(ringWidthDp)
        arc.strokeWidth = px; border.strokeWidth = (px * 0.5f).coerceAtLeast(dp(2f))
        successColor = success
        runCatching { scrimHex?.let { card.color = Color.parseColor(it) } }
    }
    private var successColor = GREEN

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    fun cardTopPx(): Float = cardRect.top
    fun cardBottomPx(): Float = cardRect.bottom
    fun ovalBottomPx(): Float = ovalRect.bottom
    fun ovalTopPx(): Float = ovalRect.top

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val ovw = w * 0.58f
        val ovh = ovw * 1.34f                        // ovoid proportions like the web flow
        ccx = w / 2f
        ccy = h * 0.46f
        ovalRect.set(ccx - ovw / 2, ccy - ovh / 2, ccx + ovw / 2, ccy + ovh / 2)
        val m = w * 0.06f
        // card wraps: instruction + arrow above, oval, guide dots below.
        cardRect.set(m, ovalRect.top - dp(118f), w - m, ovalRect.bottom + dp(78f))
        post { onLaidOut?.invoke() }
    }

    override fun onDraw(canvas: Canvas) {
        shownAction += ((if (success) 1f else actionProgress) - shownAction) * 0.16f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), surround)
        canvas.drawRoundRect(cardRect, cardCorner, cardCorner, card)
        canvas.drawRoundRect(cardRect, cardCorner, cardCorner, cardStroke)
        canvas.drawOval(ovalRect, clear)                                   // camera window

        val g = dp(12f)
        val arcRect = RectF(ovalRect.left - g, ovalRect.top - g, ovalRect.right + g, ovalRect.bottom + g)

        if (success) {
            border.color = successColor
            canvas.drawOval(arcRect, border)
            drawBadge(canvas)
            if (verifying) drawDots(canvas)
        } else {
            val a = shownAction.coerceIn(0f, 1f)
            // thin oval border: neutral until a face is present, then green (red on wrong move)
            border.color = when { !present -> NEUTRAL; wrong -> WRONG; else -> successColor }
            canvas.drawOval(arcRect, border)
            if (present) {
                if (glowAction) {                  // smile/blink → glow the oval
                    glow.color = successColor
                    glow.strokeWidth = arc.strokeWidth + dp(10f)
                    glow.alpha = (60 + 150 * a).toInt().coerceIn(40, 210)
                    canvas.drawOval(arcRect, glow); glow.alpha = 255
                }
                val screenCenter = -(directionDeg ?: 90f)
                val sweep = 70f + 110f * a          // starts long, grows
                arc.color = if (wrong) WRONG else successColor
                canvas.drawArc(arcRect, screenCenter - sweep / 2f, sweep, false, arc)
                directionDeg?.let { drawArrow(canvas, it) }
            }
        }

        diagnostic?.let { canvas.drawText(it, width / 2f, height - dp(36f), diag) }
    }

    // Dark-navy circular arrow chip above the oval, glyph rotated to the direction.
    private fun drawArrow(canvas: Canvas, deg: Float) {
        val ax = ccx; val ay = ovalRect.top - dp(38f); val r = dp(16f)
        canvas.drawCircle(ax, ay, r, arrowBg)
        canvas.save(); canvas.rotate(-deg, ax, ay)
        val s = dp(6.5f)
        val p = Path()
        p.moveTo(ax - s * 0.5f, ay - s); p.lineTo(ax + s * 0.7f, ay); p.lineTo(ax - s * 0.5f, ay + s)
        canvas.drawPath(p, arrowFg)
        canvas.restore()
    }

    // Filled green check badge (centre of the oval), like the web flow's ovcheck.
    private fun drawBadge(canvas: Canvas) {
        val r = dp(30f); val cx = ccx; val cy = ccy
        canvas.drawCircle(cx, cy, r, badge)
        val s = r * 0.5f
        val p = Path()
        p.moveTo(cx - s, cy + s * 0.1f); p.lineTo(cx - s * 0.15f, cy + s * 0.7f); p.lineTo(cx + s, cy - s * 0.6f)
        canvas.drawPath(p, badgeTick)
    }

    // Three pulsing brand dots below the oval while the check is in flight.
    private fun drawDots(canvas: Canvas) {
        val y = ovalRect.bottom + dp(34f); val gap = dp(18f); val r = dp(4.5f)
        for (i in -1..1) {
            val pulse = (0.5 + 0.5 * sin((phase + i * 0.16f) * 2 * Math.PI)).toFloat()
            dot.alpha = (60 + 195 * pulse).toInt().coerceIn(60, 255)
            canvas.drawCircle(ccx + i * gap, y, r * (0.7f + 0.3f * pulse), dot)
        }
        dot.alpha = 255
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        val GREEN: Int = Color.parseColor("#22A447")            // web flow success/border green
        private val NEUTRAL: Int = Color.parseColor("#E6EAED")  // oval border before a face is detected
        private val WRONG: Int = Color.parseColor("#E24B4A")
        private val PAGE: Int = Color.parseColor("#F1F5F5")     // light page surround
        private val NAVY: Int = Color.parseColor("#0B1F3A")     // arrow chip
        private val BRAND: Int = Color.parseColor("#0F585B")    // brand teal (dots)
    }
}
