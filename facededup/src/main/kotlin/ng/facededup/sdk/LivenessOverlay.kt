package ng.facededup.sdk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.View

/**
 * Camera overlay: a dimmed scrim with a centred oval cut-out + a coloured oval ring
 * (turns green on success, red on "wrong" / no-face). Drawn over the CameraX preview.
 * Native replacement for the web flow's oval — no WebView.
 */
internal class LivenessOverlay(ctx: Context) : View(ctx) {

    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC0B1F3A") }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(5f)
    }
    private val oval = RectF()

    /** Ring colour as a hex string ("#1E9C69"); set per state. */
    var ringColor: Int = Color.parseColor("#1E9C69")
        set(v) { field = v; invalidate() }

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    fun ovalRect(): RectF = RectF(oval)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val ow2 = w * 0.66f; val oh2 = ow2 * 1.32f
        val cx = w / 2f; val cy = h * 0.46f
        oval.set(cx - ow2 / 2, cy - oh2 / 2, cx + ow2 / 2, cy + oh2 / 2)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        canvas.drawOval(oval, clear)          // punch the oval hole
        ring.color = ringColor
        canvas.drawOval(oval, ring)           // coloured ring
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
