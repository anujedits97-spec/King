package dev.kairo.myra

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Routines taught by doing. "Ye routine sikho": Anuj does the steps once, Myraa watches the taps and
 * the typing through accessibility and saves them. Later "routine chalao" plays them back.
 *
 * What is recorded: which app is open, what was tapped (by its label), and what was typed.
 * What is never recorded: password boxes, payment and banking apps, system screens, the lock screen.
 * What it stores: a list of steps in this app's own preferences, on this phone only.
 *
 * A step is one of: {"k":"app","pkg":"..."}, {"k":"tap","label":"..."}, {"k":"type","text":"..."}.
 */
object Routines {
    private const val PREFS = "myra_routines"
    private const val MAX_STEPS = 150

    /** The routine being recorded right now, or null. */
    @Volatile var recording: String? = null
        private set

    private val pending = ArrayList<JSONObject>()
    private var lastPkg = ""

    /** Packages whose screens are never recorded: payment and banking apps, system screens, the lock screen. */
    private val SKIP = Regex("^(com\\.android\\.systemui|com\\.android\\.settings|com\\.samsung\\.android\\.settings|com\\.android\\.packageinstaller|com\\.google\\.android\\.packageinstaller|com\\.google\\.android\\.permissioncontroller|com\\.android\\.permissioncontroller|com\\.android\\.vending)$")

    fun key(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), " ").take(40)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun all(ctx: Context): JSONObject = runCatching { JSONObject(prefs(ctx).getString("all", "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun write(ctx: Context, j: JSONObject) { prefs(ctx).edit().putString("all", j.toString()).apply() }

    fun names(ctx: Context): List<String> {
        val j = all(ctx)
        return j.keys().asSequence().sorted().toList()
    }

    fun steps(ctx: Context, name: String): JSONArray? = all(ctx).optJSONArray(key(name))

    fun remove(ctx: Context, name: String): Boolean {
        val j = all(ctx)
        val k = key(name)
        if (!j.has(k)) return false
        j.remove(k)
        write(ctx, j)
        return true
    }

    /** Starts recording. The caller checks that accessibility is on and that nothing else is recording. */
    fun start(name: String) {
        pending.clear()
        lastPkg = ""
        recording = key(name)
    }

    /** Saves what was recorded. Returns the number of steps saved (0 when nothing usable was seen). */
    fun stop(ctx: Context): Int {
        val name = recording ?: return 0
        recording = null
        if (pending.isEmpty()) return 0
        val arr = JSONArray()
        pending.forEach { arr.put(it) }
        val j = all(ctx)
        j.put(name, arr)
        write(ctx, j)
        val n = pending.size
        pending.clear()
        return n
    }

    /** Called for every accessibility event while recording. Never throws. */
    fun onEvent(e: AccessibilityEvent) {
        if (recording == null) return
        runCatching {
            val pkg = e.packageName?.toString().orEmpty()
            if (pkg.isEmpty() || SKIP.containsMatchIn(pkg) || DeviceServer.PAYMENT.containsMatchIn(pkg)) return
            when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (pkg != lastPkg) app(pkg)
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    if (e.isPassword) return
                    val label = (e.text?.joinToString(" ") ?: "").trim().ifEmpty { e.contentDescription?.toString().orEmpty().trim() }
                    if (label.isEmpty()) return
                    if (pkg != lastPkg) app(pkg)
                    add(JSONObject().put("k", "tap").put("label", label.take(80)))
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    if (e.isPassword) return
                    val text = e.text?.joinToString("") ?: return
                    if (text.isEmpty()) return
                    // Typing fires once per letter: keep only the final text of one run of typing.
                    val last = pending.lastOrNull()
                    if (last != null && last.optString("k") == "type") {
                        last.put("text", text.take(500))
                    } else add(JSONObject().put("k", "type").put("text", text.take(500)))
                }
            }
        }
    }

    private fun app(pkg: String) {
        lastPkg = pkg
        add(JSONObject().put("k", "app").put("pkg", pkg))
    }

    private fun add(s: JSONObject) {
        if (pending.size >= MAX_STEPS) return
        pending.add(s)
    }

    /** Lines for the read-back: what the routine will do, step by step. */
    fun summary(arr: JSONArray, appName: (String) -> String): List<String> = (0 until arr.length()).map { i ->
        val s = arr.getJSONObject(i)
        when (s.optString("k")) {
            "app" -> "open ${appName(s.optString("pkg"))}"
            "tap" -> "tap \"${s.optString("label")}\""
            "type" -> "type \"${s.optString("text").take(80)}\""
            else -> "something"
        }
    }
}
