package dev.kairo.myra

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * The orange light along the sides of the screen.
 *
 *  - It slides in from both side edges when Myraa wakes up, and slides back out when it's done.
 *  - Listening: it swells with your voice.
 *  - Thinking: it breathes, and bright sparks run along the edges.
 *  - Speaking: it blinks with every word.
 *
 * Every shader is made once and moved with a matrix, so a frame allocates nothing.
 */
class EdgeGlowView(context: Context) : View(context) {
    enum class Mode { LISTENING, THINKING, SPEAKING }

    var mode = Mode.LISTENING
        set(v) { field = v; if (v == Mode.SPEAKING) pulse = 0.6f }
    var onHidden: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private var enter = 0f          // 0 = off screen, 1 = fully in
    private var enterTarget = 0f
    private var level = 0f          // smoothed brightness / swell, 0..1
    private var input = 0f          // microphone loudness while listening
    private var pulse = 0f          // a word was just spoken (decays)
    private var spark = 0f          // where the bright sparks are along the edge, 0..1
    private var time = 0f
    private var lastFrame = 0L

    // Across the edge: hot amber at the very edge, orange, fading to nothing.
    private val edge = LinearGradient(0f, 0f, 1f, 0f,
        intArrayOf(0xFFFFC870.toInt(), 0xF2FF8A1F.toInt(), 0x99FF6A00.toInt(), 0x33FF5200, 0x00FF5200),
        floatArrayOf(0f, 0.16f, 0.42f, 0.72f, 1f), Shader.TileMode.CLAMP)
    private val sparkShader = RadialGradient(0f, 0f, 1f,
        intArrayOf(0xFFFFF0C8.toInt(), 0xAAFFC060.toInt(), 0x00FF9A30), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = edge }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = sparkShader }
    private val m = Matrix()

    // The coloured line that runs around the edge of the screen (gold, pink, violet, blue, mint), turning slowly.
    private val rainbowColors = intArrayOf(
        0xFFFFB347.toInt(), 0xFFFF5FA2.toInt(), 0xFFB56CFF.toInt(),
        0xFF4FC3FF.toInt(), 0xFF5BFFD0.toInt(), 0xFFFFB347.toInt()
    )
    private val rainbowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var rainbow: SweepGradient? = null
    private val rot = Matrix()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        rainbow = null // rebuilt for the new size on the next frame
    }

    fun show(newMode: Mode) {
        mode = newMode
        enterTarget = 1f
        lastFrame = 0L
        postInvalidateOnAnimation()
    }

    fun hide() {
        enterTarget = 0f
        postInvalidateOnAnimation()
    }

    val showing: Boolean get() = enterTarget > 0f

    /** How bright the glow is right now, 0..1 - the orb follows it. */
    val currentLevel: Float get() = level

    fun setInput(level: Float) { input = level }

    /** One spoken word: a blink. */
    fun pulse() { pulse = 1f }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        time += dt

        // Slide in quickly with a slight overshoot; slide out a little slower.
        val rate = if (enterTarget > enter) 9f else 6f
        enter += (enterTarget - enter) * (1f - exp(-dt * rate))
        if (enterTarget == 0f && enter < 0.01f) {
            enter = 0f; level = 0f; input = 0f; pulse = 0f
            onHidden?.invoke()
            return
        }

        val target = when (mode) {
            Mode.LISTENING -> 0.32f + 0.68f * input
            Mode.THINKING -> 0.42f + 0.16f * sin(time * 2.6f).toFloat()
            Mode.SPEAKING -> 0.34f + 0.66f * pulse
        }
        level += (target - level) * (1f - exp(-dt * if (mode == Mode.SPEAKING) 22f else 10f))
        pulse *= exp(-dt * 6f)
        spark = (spark + dt * if (mode == Mode.THINKING) 0.55f else 0.18f) % 1f

        val w = width.toFloat()
        val h = height.toFloat()
        val reveal = easeOutBack(enter.coerceIn(0f, 1f))
        val thick = (16f + 34f * level) * dp * reveal
        if (thick < 0.5f) { postInvalidateOnAnimation(); return }
        val alpha = ((0.5f + 0.5f * level) * enter.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)

        // Soft bloom, then the bright band.
        drawEdges(canvas, w, h, thick * 2.6f, (alpha * 0.32f).toInt())
        drawEdges(canvas, w, h, thick, alpha)

        // The coloured line around the whole screen.
        drawRainbowLine(canvas, w, h, reveal, alpha)

        // Sparks: one running down the left edge, one running up the right, like light circling the screen.
        if (mode != Mode.LISTENING || level > 0.6f) {
            val r = h * 0.16f
            val sparkAlpha = (alpha * if (mode == Mode.THINKING) 0.9f else 0.55f).toInt()
            drawSpark(canvas, 0f, -r + spark * (h + 2 * r), thick * 1.5f, r, sparkAlpha)
            drawSpark(canvas, w, h + r - spark * (h + 2 * r), thick * 1.5f, r, sparkAlpha)
        }
        postInvalidateOnAnimation()
    }

    private fun drawEdges(c: Canvas, w: Float, h: Float, thick: Float, alpha: Int) {
        edgePaint.alpha = alpha
        m.setScale(thick, 1f)                       // left edge: amber at x = 0, fading inwards
        edge.setLocalMatrix(m)
        c.drawRect(0f, 0f, thick, h, edgePaint)
        m.setScale(-thick, 1f); m.postTranslate(w, 0f) // right edge: mirrored
        edge.setLocalMatrix(m)
        c.drawRect(w - thick, 0f, w, h, edgePaint)
    }

    private fun drawRainbowLine(c: Canvas, w: Float, h: Float, reveal: Float, alpha: Int) {
        val strokeW = (2f + 2.5f * level) * dp * reveal.coerceIn(0f, 1f)
        if (strokeW < 0.5f) return
        val shader = rainbow ?: SweepGradient(w / 2f, h / 2f, rainbowColors, null).also { rainbow = it }
        rot.setRotate(time * 40f, w / 2f, h / 2f)
        shader.setLocalMatrix(rot)
        rainbowPaint.shader = shader
        rainbowPaint.strokeWidth = strokeW
        rainbowPaint.alpha = (alpha * 0.9f).toInt().coerceIn(0, 255)
        val inset = strokeW / 2f
        c.drawRect(inset, inset, w - inset, h - inset, rainbowPaint)
    }

    private fun drawSpark(c: Canvas, x: Float, y: Float, rx: Float, ry: Float, alpha: Int) {
        sparkPaint.alpha = alpha.coerceIn(0, 255)
        m.setScale(rx, ry); m.postTranslate(x, y)
        sparkShader.setLocalMatrix(m)
        c.drawRect(x - rx, y - ry, x + rx, y + ry, sparkPaint)
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.4f
        val u = t - 1f
        return max(0f, 1f + (c1 + 1f) * u * u * u + c1 * u * u)
    }
}
