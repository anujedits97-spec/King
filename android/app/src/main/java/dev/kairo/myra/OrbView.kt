package dev.kairo.myra

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The orange orb on the right edge of the screen. Tap it to talk; it spins and glows while
 * Myraa is awake. Drawn in code: a dark disc, a turning orange ring, and a warm core.
 */
class OrbView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    val sizePx = (54 * dp).toInt()

    var active = false
        set(v) { field = v; postInvalidateOnAnimation() }
    /** Where to read loudness or the speech pulse from, 0..1, while active. */
    var levelSource: (() -> Float)? = null
    private val level: Float get() = if (active) (levelSource?.invoke() ?: 0f) else 0f
    private var glow = 0f
    private var angle = 0f
    private var lastFrame = 0L

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 14, 15, 18) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.6f * dp; strokeCap = Paint.Cap.ROUND }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.1f * dp; color = Color.argb(110, 255, 181, 71) }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private var sweep: SweepGradient? = null
    private var coreShader: RadialGradient? = null
    private var haloShader: RadialGradient? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(sizePx, sizePx)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        sweep = SweepGradient(cx, cy, intArrayOf(0xFFFF6A00.toInt(), 0xFFFFC870.toInt(), 0xFFFF8A1F.toInt(), 0x33FF8A1F, 0xFFFF6A00.toInt()), floatArrayOf(0f, 0.25f, 0.5f, 0.8f, 1f))
        ring.shader = sweep
        coreShader = RadialGradient(0f, 0f, 1f, intArrayOf(0xFFFFE6B0.toInt(), 0xFFFFA43A.toInt(), 0x00FF7A10), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        core.shader = coreShader
        haloShader = RadialGradient(0f, 0f, 1f, intArrayOf(0x66FF8A1F, 0x00FF8A1F), null, Shader.TileMode.CLAMP)
        halo.shader = haloShader
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        glow += ((if (active) 1f else 0f) - glow) * (1f - exp(-dt * 6f))
        angle = (angle + dt * (if (active) 260f else 40f)) % 360f

        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 2f * dp

        if (glow > 0.01f) {
            val hr = r * (1.0f + 0.15f * level)
            m.setScale(hr, hr); m.postTranslate(cx, cy); haloShader?.setLocalMatrix(m)
            halo.alpha = (255 * glow).toInt()
            canvas.drawCircle(cx, cy, hr, halo)
        }
        canvas.drawCircle(cx, cy, r * 0.86f, disc)

        m.setRotate(angle, cx, cy); sweep?.setLocalMatrix(m)
        ring.alpha = (170 + 85 * glow).toInt()
        canvas.drawCircle(cx, cy, r * 0.72f, ring)
        canvas.drawCircle(cx, cy, r * 0.47f, inner)

        val breathe = if (active) 0.12f * level + 0.03f * sin(now / 160.0).toFloat() else 0f
        val cr = r * (0.26f + breathe + 0.06f * glow)
        m.setScale(cr, cr); m.postTranslate(cx, cy); coreShader?.setLocalMatrix(m)
        canvas.drawCircle(cx, cy, cr, core)

        // Animate only while Myraa is awake (and while fading back): an idle orb costs no battery.
        if (active || glow > 0.01f) postInvalidateOnAnimation() else lastFrame = 0L
    }
}
