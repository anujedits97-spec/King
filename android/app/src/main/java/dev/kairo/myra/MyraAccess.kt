package dev.kairo.myra

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Lets Myraa use other apps directly, with no Termux and no ADB: read what is on the screen,
 * tap, type, scroll and press Back/Home. It does nothing on its own. Every action is a tool call
 * made through [DeviceServer], which also decides what is off limits (banking apps, system
 * settings, password fields) and what needs a spoken "yes" first.
 *
 * The user switches it on in Android's Accessibility settings, and can switch it off there at any time.
 */
class MyraAccess : AccessibilityService() {

    /** One thing visible on screen. */
    class Elem(val label: String, val clickable: Boolean, val editable: Boolean, val x: Int, val y: Int, val node: AccessibilityNodeInfo)

    /** What is on screen right now, and which app owns it. */
    class Snapshot(val pkg: String, val elems: List<Elem>)

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Myraa looks when a tool asks. The only exception is a routine being taught: then taps and typing are recorded.
        if (event != null) Routines.onEvent(event)
        if (event != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            ScreenShareActivity.autoUntil > System.currentTimeMillis() &&
            event.packageName?.toString()?.contains("systemui") == true) acceptShareDialog()
    }

    private val ui = Handler(Looper.getMainLooper())
    private var accepting = false

    /**
     * Taps Start on Android's screen-share dialog, only inside the short window ScreenShareActivity opens right after
     * Myraa asked for it (and only when the user switched "Screen share without tapping" on). The buttons can show up a
     * moment after the window, so it tries a few times.
     */
    private fun acceptShareDialog() {
        if (accepting) return
        accepting = true
        val delays = longArrayOf(0, 250, 600, 1000, 1500, 2200)
        delays.forEachIndexed { i, d ->
            ui.postDelayed({
                if (!accepting) return@postDelayed
                if (ScreenShareActivity.autoUntil < System.currentTimeMillis()) { accepting = false; return@postDelayed }
                if (pressStart()) { ScreenShareActivity.autoUntil = 0L; accepting = false }
                else if (i == delays.lastIndex) accepting = false
            }, d)
        }
    }

    private fun pressStart(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString()?.contains("systemui") != true) return false
        val labels = setOf("start now", "start", "share screen", "start recording", "अभी शुरू करें", "शुरू करें", "स्क्रीन शेयर करें")
        val hit = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 25) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
            if (t != null && t in labels) hit.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        for (n in hit) {
            var c: AccessibilityNodeInfo? = n
            var hops = 0
            while (c != null && hops < 4) {
                if (c.isClickable && c.isEnabled && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                c = c.parent
                hops++
            }
        }
        return false
    }

    /** Turns on the taps and typing events while a routine is being recorded. Off again the rest of the time. */
    fun setWatching(on: Boolean) {
        val info = serviceInfo ?: return
        info.eventTypes = if (on) {
            AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        } else {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        serviceInfo = info
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /* ── looking ─────────────────────────────────────────────────────────── */

    fun snapshot(): Snapshot? {
        val root = rootInActiveWindow ?: return null
        val out = ArrayList<Elem>()
        walk(root, out, 0)
        return Snapshot(root.packageName?.toString() ?: "", out)
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<Elem>, depth: Int) {
        if (n == null || depth > 40 || out.size >= 300) return
        if (n.isVisibleToUser) {
            // Never read what is typed into a password field.
            val text = if (n.isPassword) "" else (n.text?.toString() ?: "")
            val desc = n.contentDescription?.toString() ?: ""
            val label = (if (text.isNotBlank()) text else desc).trim().replace('\n', ' ')
            val r = Rect()
            n.getBoundsInScreen(r)
            val shown = label.isNotEmpty() || n.isClickable || n.isEditable
            if (shown && r.width() > 0 && r.height() > 0) {
                out.add(Elem(label.take(160), n.isClickable, n.isEditable, r.centerX(), r.centerY(), n))
            }
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), out, depth + 1)
    }

    /**
     * A picture of what is on screen now, shrunk so its long side is at most [maxSide] pixels.
     * Null when Android refuses (older than Android 11, too soon after the last one, or the
     * service was switched on before it was allowed to take screenshots).
     */
    fun screenshot(maxSide: Int = 1280): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        val box = AtomicReference<Bitmap?>(null)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                try {
                    val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    box.set(hw?.copy(Bitmap.Config.ARGB_8888, false))
                    hw?.recycle()
                } finally {
                    buffer.close()
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode: Int) { latch.countDown() }
        })
        latch.await(5, TimeUnit.SECONDS)
        val bmp = box.get() ?: return null
        val longSide = maxOf(bmp.width, bmp.height)
        if (longSide <= maxSide) return bmp
        val w = (bmp.width.toLong() * maxSide / longSide).toInt().coerceAtLeast(1)
        val h = (bmp.height.toLong() * maxSide / longSide).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true).also { if (it !== bmp) bmp.recycle() }
    }

    fun foregroundPackage(): String = rootInActiveWindow?.packageName?.toString() ?: ""

    /** True when the text box that has focus is a password field. */
    fun focusIsPassword(): Boolean = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isPassword == true

    /* ── acting ──────────────────────────────────────────────────────────── */

    /** Clicks the element, or the nearest clickable parent, or taps its centre as a last resort. */
    fun click(e: Elem): Boolean {
        var n: AccessibilityNodeInfo? = e.node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable && n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            hops++
        }
        return tap(e.x, e.y)
    }

    fun tap(x: Int, y: Int): Boolean {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gesture(p, 60)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean {
        val p = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return gesture(p, ms)
    }

    private fun gesture(path: Path, durationMs: Long): Boolean {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val cb = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok.set(true); latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }
        if (!dispatchGesture(g, cb, Handler(Looper.getMainLooper()))) return false
        latch.await(4, TimeUnit.SECONDS)
        return ok.get()
    }

    /** Types into the focused text box. Returns an error sentence, or null when it worked. */
    fun type(text: String, replace: Boolean): String? {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "No text box is selected. Tap the text box first."
        if (f.isPassword) return "That is a password field."
        if (!f.isEditable) return "The selected item is not a text box."
        val base = if (replace || f.isShowingHintText) "" else (f.text?.toString() ?: "")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, base + text) }
        return if (f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) null else "The text box would not take the text."
    }

    fun global(name: String): Boolean = when (name) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "lock_screen" -> Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "screenshot" -> Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        "power_menu" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        else -> false
    }

    companion object {
        @Volatile
        var instance: MyraAccess? = null

        /** Is the service switched on in Android's Accessibility settings? */
        fun enabled(ctx: Context): Boolean {
            val cn = ComponentName(ctx, MyraAccess::class.java)
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true) }
        }
    }
}
