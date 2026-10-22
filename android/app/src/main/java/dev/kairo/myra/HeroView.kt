package dev.kairo.myra

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The big arc-reactor on the home screen. It turns slowly when Myraa can't reach its brain,
 * glows when the brain is ready, and spins up when Myraa is on. Tap it to talk.
 */
class HeroView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density

    /** 0: not ready (offline or not paired), 1: ready, 2: Myraa is on. */
    var mood = 0
        set(v) { if (field != v) { field = v; postInvalidateOnAnimation() } }

    /** Animates only while the screen is showing. */
    var animating = false
        set(v) { field = v; lastFrame = 0L; if (v) postInvalidateOnAnimation() }

    private var energy = 0.25f
    private var spin = 0f
    private var lastFrame = 0L
    private var r = 0f

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * dp; strokeCap = Paint.Cap.ROUND; color = 0xFFFF8A1F.toInt() }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * dp; color = 0xFFFF8A1F.toInt() }
    private val arcA = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.2f * dp; strokeCap = Paint.Cap.ROUND; color = 0xFFFF9A3C.toInt() }
    private val arcB = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * dp; strokeCap = Paint.Cap.ROUND; color = 0xFFFFC870.toInt() }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E0F13.toInt() }
    private val discRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.4f * dp }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val oval = RectF()
    private var ticks = FloatArray(0)
    private var sweep: SweepGradient? = null
    private var coreShader: RadialGradient? = null

    init { isClickable = true; defaultFocusHighlightEnabled = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize((230 * dp).toInt(), heightMeasureSpec))

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        r = min(cx, cy) - 6 * dp
        // 72 ticks around the outside, every sixth one longer.
        ticks = FloatArray(72 * 4)
        for (i in 0 until 72) {
            val a = Math.toRadians(i * 5.0)
            val inner = r * (if (i % 6 == 0) 0.9f else 0.95f)
            val outer = r * 0.99f
            ticks[i * 4] = cx + cos(a).toFloat() * inner
            ticks[i * 4 + 1] = cy + sin(a).toFloat() * inner
            ticks[i * 4 + 2] = cx + cos(a).toFloat() * outer
            ticks[i * 4 + 3] = cy + sin(a).toFloat() * outer
        }
        sweep = SweepGradient(cx, cy, intArrayOf(0xFFFF6A00.toInt(), 0xFFFFC870.toInt(), 0xFFFF8A1F.toInt(), 0x22FF8A1F, 0xFFFF6A00.toInt()), floatArrayOf(0f, 0.25f, 0.5f, 0.8f, 1f))
        discRing.shader = sweep
        coreShader = RadialGradient(cx, cy, r * 0.3f, intArrayOf(0xFFFFF1D0.toInt(), 0xFFFFA43A.toInt(), 0x00FF7A10), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        core.shader = coreShader
        halo.shader = RadialGradient(cx, cy, r, intArrayOf(0x55FF8A1F, 0x18FF8A1F, 0x00FF8A1F), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().scaleX(if (pressed) 0.95f else 1f).scaleY(if (pressed) 0.95f else 1f).setDuration(140).start()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        val target = when (mood) { 2 -> 1f; 1 -> 0.6f; else -> 0.25f }
        energy = if (ValueAnimator.areAnimatorsEnabled()) energy + (target - energy) * (1f - exp(-dt * 3f)) else target
        spin = (spin + dt * (8f + 70f * energy)) % 360f
        val e = energy
        val cx = width / 2f
        val cy = height / 2f

        halo.alpha = (255 * (0.25f + 0.75f * e)).toInt()
        canvas.drawCircle(cx, cy, r, halo)

        tickPaint.alpha = (40 + 90 * e).toInt()
        canvas.save(); canvas.rotate(spin * 0.2f, cx, cy); canvas.drawLines(ticks, tickPaint); canvas.restore()

        thin.alpha = (30 + 40 * e).toInt()
        canvas.drawCircle(cx, cy, r * 0.84f, thin)

        // Three bright arcs turning one way, two thin ones the other.
        arcA.alpha = (90 + 165 * e).toInt()
        val ra = r * 0.76f
        oval.set(cx - ra, cy - ra, cx + ra, cy + ra)
        for (k in 0 until 3) canvas.drawArc(oval, spin + k * 120f, 70f, false, arcA)
        arcB.alpha = (60 + 140 * e).toInt()
        val rb = r * 0.64f
        oval.set(cx - rb, cy - rb, cx + rb, cy + rb)
        for (k in 0 until 2) canvas.drawArc(oval, -spin * 1.6f + k * 180f, 120f, false, arcB)

        // The core.
        canvas.drawCircle(cx, cy, r * 0.5f, disc)
        m.setRotate(spin * 2f, cx, cy); sweep?.setLocalMatrix(m)
        discRing.alpha = (120 + 135 * e).toInt()
        canvas.drawCircle(cx, cy, r * 0.5f, discRing)
        val pulse = 1f + 0.07f * e * sin(now / 420.0).toFloat()
        m.setScale(pulse, pulse, cx, cy); coreShader?.setLocalMatrix(m)
        core.alpha = (110 + 145 * e).toInt()
        canvas.drawCircle(cx, cy, r * 0.3f * pulse, core)

        // Smooth while Myraa is on; 30 frames a second is plenty for the slow idle turn and saves battery.
        // Holds still when "Remove animations" is on in Android's settings.
        if (!animating || !ValueAnimator.areAnimatorsEnabled()) lastFrame = 0L
        else if (mood == 2) postInvalidateOnAnimation()
        else postInvalidateDelayed(33)
    }
}
