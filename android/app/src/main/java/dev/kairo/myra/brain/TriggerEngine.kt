package dev.kairo.myra.brain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dev.kairo.myra.Prefs
import dev.kairo.myra.brain.life.TelegramModule
import dev.kairo.myra.brain.life.TodoistModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Event triggers. Four kinds of rule:
 *  - notification: an app (WhatsApp, Gmail...) gets a notification, optionally with a keyword
 *  - battery: the battery falls to a level (e.g. 20%). Fires once, and rearms after it charges 5% up
 *  - charging: the charger is plugged in or pulled out, or the battery reaches a level while charging
 *    (event plugged / unplugged / full; "full" fires once per charge, at 100% or the rule's threshold)
 *  - time: every day at a time (HH:mm). Fires once a day
 * Each rule does ONE action: speak (Myraa says it out loud), notify (phone), telegram (your own chat)
 * or todoist (your own task list). Actions only reach you. Notification rules wait 60 s between runs.
 * The clock and battery check runs about once a minute; plugging in and out is caught at once (onPower).
 */
object TriggerEngine {
    val lock = Any()
    private val lastFired = HashMap<String, Long>()
    private val batteryArmed = HashMap<String, Boolean>()
    private val timeFiredOn = HashMap<String, String>()
    private val fullArmed = HashMap<String, Boolean>() // "full" rules: true = will fire at the level; unseen = not checked yet
    private const val COOLDOWN_MS = 60_000L
    private const val POWER_COOLDOWN_MS = 10_000L // a loose cable can plug in and out in a blink: say it once
    private const val CHANNEL = "myra_triggers"
    const val MAX_RULES = 20

    private fun file(env: BrainEnv) = File(env.dir, "triggers.json")

    fun load(env: BrainEnv): JSONArray = try {
        if (file(env).exists()) JSONArray(file(env).readText()) else JSONArray()
    } catch (e: Exception) { JSONArray() }

    fun save(env: BrainEnv, a: JSONArray) { file(env).writeText(a.toString(1)) }

    private fun kindOf(r: JSONObject) = r.optString("type", "notification")

    /** Called for every new notification (through AnnounceService, which needs Notification access). */
    fun onNotification(ctx: Context, pkg: String, label: String, title: String, text: String) {
        val env = LocalBrain.get(ctx, Prefs(ctx)).env
        val hay = "$title $text".lowercase()
        val rules = synchronized(lock) { load(env) }
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            if (!r.optBoolean("enabled", true) || kindOf(r) != "notification") continue
            val app = r.optString("app")
            if (!(pkg.lowercase().contains(app) || label.lowercase().contains(app))) continue
            val kw = r.optString("keyword")
            if (kw.isNotEmpty() && !hay.contains(kw)) continue
            val name = r.optString("name")
            val now = System.currentTimeMillis()
            val fire = synchronized(lastFired) {
                val last = lastFired[name] ?: 0L
                if (now - last < COOLDOWN_MS) false else { lastFired[name] = now; true }
            }
            if (!fire) continue
            val msg = r.optString("text").replace("{app}", label).replace("{title}", title).replace("{text}", text)
            run(ctx, env, r.optString("action"), msg)
        }
    }

    /** Checks battery and clock rules. MyraService calls this about once a minute. */
    fun tick(ctx: Context) {
        val env = LocalBrain.get(ctx, Prefs(ctx)).env
        val rules = synchronized(lock) { load(env) }
        if (rules.length() == 0) return
        val level = batteryLevel(ctx)
        val now = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            if (!r.optBoolean("enabled", true)) continue
            val name = r.optString("name")
            when (kindOf(r)) {
                "battery" -> {
                    if (level < 0) continue
                    val threshold = r.optInt("threshold", 20)
                    val armed = synchronized(lock) { batteryArmed[name] ?: true }
                    if (level <= threshold && armed) {
                        synchronized(lock) { batteryArmed[name] = false }
                        run(ctx, env, r.optString("action"), r.optString("text").ifEmpty { "Battery is at {level}%." }.replace("{level}", level.toString()))
                    } else if (level > threshold + 5) {
                        synchronized(lock) { batteryArmed[name] = true }
                    }
                }
                "charging" -> if (r.optString("event") == "full") checkFull(ctx, env, r, level)
                "time" -> {
                    if (r.optString("at") == now && synchronized(lock) { timeFiredOn[name] } != today) {
                        synchronized(lock) { timeFiredOn[name] = today }
                        run(ctx, env, r.optString("action"), r.optString("text").ifEmpty { "It is $now." }.replace("{time}", now))
                    }
                }
            }
        }
    }


    /**
     * Called the moment the charger is plugged in or pulled out (MyraService listens for it),
     * and on every battery change. Fires the matching "charging" rules.
     */
    fun onPower(ctx: Context, plugged: Boolean) {
        val env = LocalBrain.get(ctx, Prefs(ctx)).env
        val rules = synchronized(lock) { load(env) }
        if (rules.length() == 0) return
        val level = batteryLevel(ctx)
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            if (!r.optBoolean("enabled", true) || kindOf(r) != "charging") continue
            val event = r.optString("event")
            if (event == "full") {
                if (!plugged) synchronized(lock) { fullArmed[r.optString("name")] = true } // new charge next time
                continue
            }
            if ((event == "plugged") != plugged) continue
            val name = r.optString("name")
            val now = System.currentTimeMillis()
            val fire = synchronized(lastFired) {
                val last = lastFired[name] ?: 0L
                if (now - last < POWER_COOLDOWN_MS) false else { lastFired[name] = now; true }
            }
            if (!fire) continue
            val def = if (plugged) "Charger laga diya. Battery {level}% hai." else "Charger nikal diya. Battery {level}% hai."
            run(ctx, env, r.optString("action"), r.optString("text").ifEmpty { def }.replace("{level}", if (level < 0) "?" else level.toString()))
        }
    }

    /** Called when the battery level changes: checks the "full" rules without waiting for the minute tick. */
    fun onBatteryChanged(ctx: Context) {
        val env = LocalBrain.get(ctx, Prefs(ctx)).env
        val rules = synchronized(lock) { load(env) }
        if (rules.length() == 0) return
        val level = batteryLevel(ctx)
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            if (!r.optBoolean("enabled", true) || kindOf(r) != "charging" || r.optString("event") != "full") continue
            checkFull(ctx, env, r, level)
        }
    }

    /**
     * "Full" rule: fires once when the phone is charging and the level reaches the threshold.
     * It rearms when the charger is pulled out, or the level drops 3% below the threshold.
     * If the phone is already past the threshold when the rule is first looked at, it stays quiet until the next charge.
     */
    private fun checkFull(ctx: Context, env: BrainEnv, r: JSONObject, level: Int) {
        if (level < 0) return
        val name = r.optString("name")
        val threshold = r.optInt("threshold", 100)
        val plugged = isPlugged(ctx)
        val reached = plugged && level >= threshold
        val fire = synchronized(lock) {
            val armed = fullArmed[name]
            when {
                !plugged || level < threshold - 3 -> { fullArmed[name] = true; false }
                armed == null -> { fullArmed[name] = !reached; false } // first look: don't announce a charge that already happened
                reached && armed == true -> { fullArmed[name] = false; true }
                else -> false
            }
        }
        if (!fire) return
        val def = if (threshold >= 100) "Battery full ho gayi, charger nikal do." else "Battery {level}% ho gayi, charger nikal do."
        run(ctx, env, r.optString("action"), r.optString("text").ifEmpty { def }.replace("{level}", level.toString()))
    }

    private fun isPlugged(ctx: Context): Boolean =
        (ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    private fun batteryLevel(ctx: Context): Int {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = ctx.getSystemService(BatteryManager::class.java)
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            ?: run {
                val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) level * 100 / scale else -1
            }
    }

    private fun run(ctx: Context, env: BrainEnv, action: String, msg: String) {
        val text = msg.take(300)
        Thread { runCatching { act(ctx, env, action, text) } }.start()
    }

    private fun act(ctx: Context, env: BrainEnv, action: String, msg: String) {
        when (action) {
            "speak" -> if (!dev.kairo.myra.MyraService.speakAlert(msg)) localNotify(ctx, msg) // Myraa busy or off: show it instead
            "telegram" -> runTool(env, "telegram_send", JSONObject().put("text", msg))
            "todoist" -> runTool(env, "todoist_add", JSONObject().put("content", msg.take(500)))
            else -> localNotify(ctx, msg)
        }
    }

    /** Runs one tool of the Telegram or Todoist connector with the given arguments. */
    private fun runTool(env: BrainEnv, name: String, args: JSONObject) {
        val all = listOf(TelegramModule(env), TodoistModule(env)).flatMap { it.tools() }
        all.firstOrNull { it.name == name }?.run?.invoke(args)
    }

    private fun localNotify(ctx: Context, msg: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Myraa triggers", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Myraa")
            .setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(msg))
            .setAutoCancel(true)
            .build()
        nm.notify((System.nanoTime() % 100000).toInt(), n)
    }
}
