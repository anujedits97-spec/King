package dev.kairo.myra

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * The three things Myraa draws over other apps: the glow along the sides, the orb on the
 * right edge, and a small caption. Needs "Display over other apps".
 */
class Overlays(
    private val context: Context,
    private val prefs: Prefs,
    private val onOrbTap: () -> Unit,
    private val onOrbLongPress: () -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val dp = context.resources.displayMetrics.density

    val glow = EdgeGlowView(context)
    val orb = OrbView(context)
    private val caption = TextView(context)
    private var glowAdded = false
    private var orbAdded = false
    private var captionAdded = false
    private val hideCaptionRunnable = Runnable { removeCaption() }

    val allowed: Boolean get() = Settings.canDrawOverlays(context)

    init {
        glow.onHidden = { removeGlow() }
        orb.levelSource = { glow.currentLevel }
        caption.apply {
            setTextColor(0xFFF2EFEA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18 * dp
                // Fully opaque: the window itself is drawn at 80% (Android's limit for an overlay
                // that lets touches through), so this already ends up slightly see-through.
                setColor(0xFF101115.toInt())
                setStroke((1 * dp).toInt(), 0xAAFF8A1F.toInt())
            }
        }
    }

    private fun screen(): Point {
        val p = Point()
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            p.set(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
        }
        return p
    }

    private fun baseParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }

    /* ── glow ── */

    fun showGlow(mode: EdgeGlowView.Mode) {
        if (!allowed) return
        if (!glowAdded) {
            val s = screen()
            val p = baseParams(s.x, s.y, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).apply {
                gravity = Gravity.TOP or Gravity.START
                // Android only lets touches pass through an overlay drawn at 80% opacity or less.
                alpha = 0.8f
            }
            runCatching { wm.addView(glow, p); glowAdded = true }
        }
        glow.show(mode)
    }

    fun setGlowMode(mode: EdgeGlowView.Mode) { if (glowAdded) glow.mode = mode }

    fun hideGlow() { if (glowAdded) glow.hide() }

    private fun removeGlow() {
        if (glowAdded) { runCatching { wm.removeView(glow) }; glowAdded = false }
    }

    /* ── orb ── */

    @SuppressLint("ClickableViewAccessibility")
    fun showOrb() {
        if (!allowed || orbAdded || !prefs.orb) return
        val s = screen()
        val size = orb.sizePx
        val p = baseParams(size, size, 0).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (4 * dp).toInt()
            y = (prefs.orbY * s.y).toInt()
        }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downY = 0f
        var startY = 0
        var dragging = false
        var longPressed = false
        val longPress = Runnable { longPressed = true; onOrbLongPress() }
        orb.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY; startY = p.y; dragging = false; longPressed = false
                    main.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && abs(e.rawY - downY) > slop) { dragging = true; main.removeCallbacks(longPress) }
                    if (dragging) {
                        p.y = (startY + (e.rawY - downY)).toInt().coerceIn(0, s.y - size)
                        runCatching { wm.updateViewLayout(orb, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (dragging) prefs.orbY = p.y.toFloat() / s.y
                    else if (!longPressed) { orb.performClick(); onOrbTap() }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            true
        }
        orb.contentDescription = "Myraa. Tap to talk."
        runCatching { wm.addView(orb, p); orbAdded = true }
    }

    fun hideOrb() {
        if (orbAdded) { runCatching { wm.removeView(orb) }; orbAdded = false }
    }

    /* ── caption ── */

    fun caption(text: String, holdMs: Long = 0) {
        if (!allowed || !prefs.captions || text.isBlank()) return
        caption.text = text
        if (!captionAdded) {
            val s = screen()
            val p = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (s.y * 0.12f).toInt()
                alpha = 0.8f
            }
            caption.maxWidth = (s.x * 0.84f).toInt()
            runCatching { wm.addView(caption, p); captionAdded = true }
        }
        main.removeCallbacks(hideCaptionRunnable)
        if (holdMs > 0) main.postDelayed(hideCaptionRunnable, holdMs)
    }

    fun hideCaption(afterMs: Long = 0) {
        main.removeCallbacks(hideCaptionRunnable)
        if (afterMs > 0) main.postDelayed(hideCaptionRunnable, afterMs) else removeCaption()
    }

    private fun removeCaption() {
        if (captionAdded) { runCatching { wm.removeView(caption) }; captionAdded = false }
    }

    /** After a rotation: the glow must cover the new screen size. */
    fun onScreenChanged() {
        if (glowAdded) {
            val s = screen()
            val p = glow.layoutParams as WindowManager.LayoutParams
            p.width = s.x; p.height = s.y
            runCatching { wm.updateViewLayout(glow, p) }
        }
        if (orbAdded) { hideOrb(); showOrb() }
    }

    fun removeAll() {
        hideOrb(); removeCaption()
        glow.onHidden = null
        removeGlow()
        glow.onHidden = { removeGlow() }
    }
}
