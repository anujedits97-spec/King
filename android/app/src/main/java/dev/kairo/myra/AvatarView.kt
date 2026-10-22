package dev.kairo.myra

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Myraa's face: an original 2D character drawn with plain canvas shapes (no image files).
 * She breathes, blinks every few seconds, and her mouth moves while Myraa speaks (call [speakPulse] on every word).
 * Tap her to talk, like the orb.
 */
class AvatarView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density

    /** 0: not ready, 1: ready, 2: Myraa is on. Only changes the glow behind her. */
    var mood = 0
        set(v) { if (field != v) { field = v; postInvalidateOnAnimation() } }

    /** Animates only while the screen is showing. */
    var animating = false
        set(v) { field = v; last = 0L; if (v) postInvalidateOnAnimation() }

    private var mouth = 0f
    private var mouthTarget = 0f
    private var nextBlink = 0L
    private var blinkStart = 0L
    private var last = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val path = Path()

    init {
        isClickable = true
        defaultFocusHighlightEnabled = false
        active = this
    }

    /** The voice says a word: open the mouth a random amount. Safe to call from the main thread. */
    fun speakPulse() { mouthTarget = 0.45f + Random.nextFloat() * 0.55f }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); active = this }
    override fun onDetachedFromWindow() { if (active === this) active = null; super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize((236 * dp).toInt(), heightMeasureSpec))

    private fun oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Int, p: Paint = fill, c: Canvas) {
        p.shader = null; p.color = color
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        c.drawOval(rect, p)
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 16L else min(now - last, 64L)
        last = now
        mouthTarget = max(0f, mouthTarget - dt * 0.004f)
        mouth += (mouthTarget - mouth) * min(1f, dt * 0.03f)
        if (nextBlink == 0L) nextBlink = now + 2500
        if (blinkStart == 0L && now >= nextBlink) blinkStart = now
        var open = 1f
        if (blinkStart != 0L) {
            val t = (now - blinkStart) / 160f
            if (t >= 1f) { blinkStart = 0L; nextBlink = now + 2500 + Random.nextLong(2500) } else open = 1f - sin(t * Math.PI.toFloat())
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val R = min(w * 0.30f, h * 0.30f)
        val cx = w / 2f
        val cy = h * 0.44f + sin(now / 900f) * 1.6f * dp // slow breathing

        // glow behind her
        val glow = when (mood) { 2 -> 0x66FF8A1F; 1 -> 0x33FF8A1F; else -> 0x14FF8A1F }
        fill.shader = RadialGradient(cx, cy, R * 2.1f, glow, 0x00FF8A1F, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R * 2.1f, fill)
        fill.shader = null

        val hair = 0xFF2A1B3D.toInt()
        val skin = 0xFFFFE2CC.toInt()

        // long hair behind, shoulders, neck
        rect.set(cx - R * 1.22f, cy - R * 1.12f, cx + R * 1.22f, cy + R * 2.0f)
        fill.color = hair; c.drawRoundRect(rect, R * 0.9f, R * 0.9f, fill)
        rect.set(cx - R * 1.35f, cy + R * 1.25f, cx + R * 1.35f, cy + R * 2.6f)
        fill.color = 0xFF1F2430.toInt(); c.drawRoundRect(rect, R * 0.5f, R * 0.5f, fill)
        rect.set(cx - R * 0.22f, cy + R * 0.85f, cx + R * 0.22f, cy + R * 1.35f)
        fill.color = skin; c.drawRect(rect, fill)
        stroke.color = 0xFFFF8A1F.toInt(); stroke.strokeWidth = 2f * dp
        rect.set(cx - R * 0.5f, cy + R * 1.05f, cx + R * 0.5f, cy + R * 1.7f)
        c.drawArc(rect, 20f, 140f, false, stroke)

        // face
        oval(cx, cy + R * 0.02f, R * 0.92f, R * 1.02f, skin, fill, c)

        // blush
        oval(cx - R * 0.6f, cy + R * 0.5f, R * 0.2f, R * 0.1f, 0x55FF7A9A, fill, c)
        oval(cx + R * 0.6f, cy + R * 0.5f, R * 0.2f, R * 0.1f, 0x55FF7A9A, fill, c)

        // eyes
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * R * 0.42f
            val ey = cy + R * 0.16f
            val rx = R * 0.17f
            val ry = R * 0.22f * max(open, 0.06f)
            oval(ex, ey, rx, ry, 0xFFFFFFFF.toInt(), fill, c)
            if (open > 0.3f) {
                oval(ex, ey + ry * 0.05f, rx * 0.78f, ry * 0.92f, 0xFFB5651D.toInt(), fill, c)
                oval(ex, ey + ry * 0.1f, rx * 0.42f, ry * 0.55f, 0xFF2A1B1B.toInt(), fill, c)
                oval(ex - rx * 0.3f, ey - ry * 0.35f, rx * 0.24f, ry * 0.24f, 0xFFFFFFFF.toInt(), fill, c)
            }
            stroke.color = 0xFF2A1B3D.toInt(); stroke.strokeWidth = 2.4f * dp
            rect.set(ex - rx * 1.05f, ey - ry * 1.05f, ex + rx * 1.05f, ey + ry * 1.05f)
            c.drawArc(rect, 200f, 140f, false, stroke)
        }

        // mouth
        val my = cy + R * 0.68f
        if (mouth < 0.08f) {
            stroke.color = 0xFFC2575F.toInt(); stroke.strokeWidth = 2.2f * dp
            rect.set(cx - R * 0.14f, my - R * 0.08f, cx + R * 0.14f, my + R * 0.08f)
            c.drawArc(rect, 20f, 140f, false, stroke)
        } else {
            oval(cx, my, R * 0.12f, R * (0.03f + 0.13f * mouth), 0xFF8E2B3A.toInt(), fill, c)
        }

        // fringe (bangs)
        path.reset()
        path.moveTo(cx - R * 0.97f, cy - R * 0.05f)
        path.cubicTo(cx - R * 1.15f, cy - R * 1.45f, cx + R * 1.15f, cy - R * 1.45f, cx + R * 0.97f, cy - R * 0.05f)
        path.lineTo(cx + R * 0.55f, cy - R * 0.5f)
        path.quadTo(cx + R * 0.3f, cy - R * 0.15f, cx + R * 0.08f, cy - R * 0.52f)
        path.quadTo(cx - R * 0.2f, cy - R * 0.1f, cx - R * 0.42f, cy - R * 0.52f)
        path.quadTo(cx - R * 0.62f, cy - R * 0.12f, cx - R * 0.97f, cy - R * 0.05f)
        path.close()
        fill.color = hair; c.drawPath(path, fill)

        // orange hair clip
        rect.set(cx + R * 0.52f, cy - R * 0.72f, cx + R * 0.82f, cy - R * 0.6f)
        fill.color = 0xFFFF8A1F.toInt(); c.drawRoundRect(rect, 6f * dp, 6f * dp, fill)

        if (animating) postInvalidateOnAnimation()
    }

    companion object {
        /** The avatar currently on screen, so the service can move her mouth without knowing about the activity. */
        @Volatile var active: AvatarView? = null
        fun pulse() { active?.post { active?.speakPulse() } }
    }
}
