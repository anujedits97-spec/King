package dev.kairo.myra

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Simple line icons drawn in code on a 24×24 grid, so the app needs no icon library. */
class LineIcon(private val kind: Kind, color: Int, private val sizePx: Int) : Drawable() {
    enum class Kind { SCAN, MIC, SEND, LAPTOP, IMAGE, SCREEN, REFRESH, POWER, CHECK, PASTE, CHEVRON }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 2f; this.color = color
    }
    private val path = Path()
    private val rect = RectF()

    var color: Int
        get() = paint.color
        set(v) { paint.color = v; invalidateSelf() }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f)
        canvas.scale(s, s)
        path.reset()
        when (kind) {
            Kind.SCAN -> {
                path.moveTo(4f, 9f); path.lineTo(4f, 6.5f); path.quadTo(4f, 4f, 6.5f, 4f); path.lineTo(9f, 4f)
                path.moveTo(15f, 4f); path.lineTo(17.5f, 4f); path.quadTo(20f, 4f, 20f, 6.5f); path.lineTo(20f, 9f)
                path.moveTo(20f, 15f); path.lineTo(20f, 17.5f); path.quadTo(20f, 20f, 17.5f, 20f); path.lineTo(15f, 20f)
                path.moveTo(9f, 20f); path.lineTo(6.5f, 20f); path.quadTo(4f, 20f, 4f, 17.5f); path.lineTo(4f, 15f)
                path.moveTo(7.5f, 12f); path.lineTo(16.5f, 12f)
            }
            Kind.MIC -> {
                path.addRoundRect(9f, 3f, 15f, 14.5f, 3f, 3f, Path.Direction.CW)
                path.moveTo(5.5f, 11f); path.cubicTo(5.5f, 15f, 8.5f, 17.5f, 12f, 17.5f); path.cubicTo(15.5f, 17.5f, 18.5f, 15f, 18.5f, 11f)
                path.moveTo(12f, 17.5f); path.lineTo(12f, 21f)
            }
            Kind.SEND -> {
                path.moveTo(12f, 19f); path.lineTo(12f, 5f)
                path.moveTo(6f, 11f); path.lineTo(12f, 5f); path.lineTo(18f, 11f)
            }
            Kind.LAPTOP -> {
                path.addRoundRect(5f, 5f, 19f, 15.5f, 1.8f, 1.8f, Path.Direction.CW)
                path.moveTo(2.5f, 19f); path.lineTo(21.5f, 19f)
            }
            Kind.IMAGE -> {
                path.addRoundRect(3.5f, 4.5f, 20.5f, 19.5f, 2.5f, 2.5f, Path.Direction.CW)
                path.addCircle(9f, 9.5f, 1.6f, Path.Direction.CW)
                path.moveTo(3.8f, 16.5f); path.lineTo(9f, 12f); path.lineTo(13f, 15.5f); path.lineTo(16f, 13f); path.lineTo(20.2f, 16.5f)
            }
            Kind.SCREEN -> {
                path.addRoundRect(3f, 4f, 21f, 16f, 2f, 2f, Path.Direction.CW)
                path.moveTo(12f, 16f); path.lineTo(12f, 20f)
                path.moveTo(8f, 20f); path.lineTo(16f, 20f)
            }
            Kind.REFRESH -> {
                rect.set(5f, 5f, 19f, 19f)
                path.addArc(rect, 30f, 300f)
                arrowHead(330f)
            }
            Kind.POWER -> {
                rect.set(4.5f, 4.5f, 19.5f, 19.5f)
                path.addArc(rect, 300f, 300f)
                path.moveTo(12f, 3f); path.lineTo(12f, 11f)
            }
            Kind.CHECK -> {
                path.moveTo(5f, 12.5f); path.lineTo(10f, 17f); path.lineTo(19f, 7f)
            }
            Kind.PASTE -> {
                path.addRoundRect(5.5f, 5f, 18.5f, 21f, 2f, 2f, Path.Direction.CW)
                path.addRoundRect(9f, 3f, 15f, 7f, 1.2f, 1.2f, Path.Direction.CW)
                path.moveTo(9f, 12f); path.lineTo(15f, 12f)
                path.moveTo(9f, 16f); path.lineTo(13f, 16f)
            }
            Kind.CHEVRON -> {
                path.moveTo(9.5f, 6f); path.lineTo(15.5f, 12f); path.lineTo(9.5f, 18f)
            }
        }
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    /** A small arrow at the end of the refresh circle (radius 7 around the middle), pointing along it. */
    private fun arrowHead(deg: Float) {
        val a = Math.toRadians(deg.toDouble())
        val tx = 12f + 7f * cos(a).toFloat()
        val ty = 12f + 7f * sin(a).toFloat()
        val dir = a + Math.PI / 2 // the way the circle is heading at its end
        for (side in listOf(-1, 1)) {
            val b = dir + Math.PI + side * 0.75
            path.moveTo(tx, ty)
            path.lineTo(tx + 4f * cos(b).toFloat(), ty + 4f * sin(b).toFloat())
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
