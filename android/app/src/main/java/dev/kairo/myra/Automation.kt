package dev.kairo.myra

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Small phone automations that need no screen tapping: alarms, timers, flashlight, navigation, dialer and SMS
 * composer, brightness, Do Not Disturb, settings panels. Every function returns the sentence Myraa says back,
 * or throws [Fail] with the reason. Nothing here sends a message or places a call by itself: the dialer and the
 * SMS app open ready, and Anuj presses the last button.
 */
object Automation {
    private fun go(ctx: Context, i: Intent, failMsg: String) {
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { throw Fail(failMsg) }
    }

    fun setAlarm(ctx: Context, a: JSONObject): String {
        if (!a.has("hour")) throw Fail("Which time? I need the hour (0 to 23) and the minute.")
        val h = a.optInt("hour", -1)
        val m = a.optInt("minute", 0)
        if (h !in 0..23 || m !in 0..59) throw Fail("That is not a valid time.")
        go(ctx, Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label").take(60))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "I couldn't find a clock app to set the alarm.")
        return "Alarm set for %02d:%02d.".format(h, m)
    }

    fun setTimer(ctx: Context, a: JSONObject): String {
        val s = a.optInt("seconds", 0)
        if (s !in 1..86400) throw Fail("The timer must be between 1 second and 24 hours.")
        go(ctx, Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label").take(60))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "I couldn't find a clock app to start the timer.")
        return "Timer started for ${if (s >= 60) "${s / 60} minutes${if (s % 60 != 0) " ${s % 60} seconds" else ""}" else "$s seconds"}."
    }

    fun flashlight(ctx: Context, a: JSONObject): String {
        val on = a.optBoolean("on", true)
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            val c = cm.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: throw Fail("This phone has no flashlight.")
        try { cm.setTorchMode(id, on) } catch (e: Exception) { throw Fail("The flashlight did not change (${e.message}).") }
        return if (on) "Flashlight is on." else "Flashlight is off."
    }

    fun navigate(ctx: Context, a: JSONObject): String {
        val dest = a.optString("destination").trim()
        if (dest.isEmpty()) throw Fail("Where to? Give me a place or an address.")
        val mode = when (a.optString("mode")) { "walk" -> "w"; "bike" -> "b"; else -> "d" }
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=$mode"))
            .setPackage("com.google.android.apps.maps")
        try {
            ctx.startActivity(nav.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}")), "I couldn't open a maps app.")
        }
        return "Navigation to $dest is open."
    }

    /** A phone number from either a number or a contact name (needs the Contacts permission for names). */
    internal fun resolve(ctx: Context, who: String): String {
        val w = who.trim()
        if (w.isEmpty()) throw Fail("Who? Give me a name or a number.")
        if (Regex("^[+0-9 ()\\-]{3,20}$").matches(w)) return w.filter { it.isDigit() || it == '+' }
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            throw Fail("I can't look up names because contacts access is off. Give me the number instead.")
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val found = LinkedHashMap<String, String>()
        ctx.contentResolver.query(
            phone,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$w%"), null,
        )?.use { c ->
            while (c.moveToNext() && found.size < 6) {
                val n = c.getString(0)
                val num = c.getString(1)
                if (n != null && !num.isNullOrBlank() && !found.containsKey(n)) found[n] = num
            }
        }
        if (found.isEmpty()) throw Fail("I found nobody called $w in the contacts.")
        val exact = found.keys.firstOrNull { it.equals(w, true) }
        if (exact == null && found.size > 1) throw Fail("More than one match: ${found.keys.joinToString(", ")}. Ask Anuj which one.")
        return (found[exact ?: found.keys.first()] ?: "").filter { it.isDigit() || it == '+' }
    }

    fun dial(ctx: Context, a: JSONObject): String {
        val num = resolve(ctx, a.optString("who"))
        go(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(num)}")), "I couldn't open the phone dialer.")
        return "The dialer is open with ${a.optString("who").trim()}. Anuj has to press the green call button."
    }

    fun composeSms(ctx: Context, a: JSONObject): String {
        val num = resolve(ctx, a.optString("who"))
        val text = a.optString("text").take(600)
        go(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(num)}")).putExtra("sms_body", text), "I couldn't open a messages app.")
        return "The message to ${a.optString("who").trim()} is written and waiting in the messages app. Anuj has to press Send."
    }

    fun brightness(ctx: Context, a: JSONObject): String {
        if (!Settings.System.canWrite(ctx)) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
            throw Fail("I need the 'Modify system settings' permission for Myraa. I opened that screen. Anuj has to allow it, then ask again.")
        }
        val cr = ctx.contentResolver
        if (a.optBoolean("auto", false)) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return "Brightness is automatic now."
        }
        val pct = a.optInt("percent", -1)
        if (pct !in 0..100) throw Fail("Tell me a brightness from 0 to 100 percent, or say auto.")
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100).coerceIn(1, 255))
        return "Brightness is $pct percent."
    }

    fun doNotDisturb(ctx: Context, a: JSONObject): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            try { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            throw Fail("I need 'Do Not Disturb access' for Myraa. I opened that screen. Anuj has to allow it, then ask again.")
        }
        val on = a.optBoolean("on", true)
        val silence = a.optString("mode") == "silence"
        nm.setInterruptionFilter(
            if (!on) NotificationManager.INTERRUPTION_FILTER_ALL
            else if (silence) NotificationManager.INTERRUPTION_FILTER_NONE
            else NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        )
        return if (!on) "Do Not Disturb is off." else if (silence) "Total silence is on." else "Do Not Disturb is on. Priority calls still ring."
    }

    /** Android does not let apps flip Wi-Fi or Bluetooth, so this opens the right panel and Anuj taps the switch. */
    fun settingsPanel(ctx: Context, a: JSONObject): String {
        val which = a.optString("panel")
        val action = when (which) {
            "wifi", "internet" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS
            "volume" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            "nfc" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery_saver" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> throw Fail("Unknown panel. Use wifi, bluetooth, airplane, volume, nfc, display, battery_saver or location.")
        }
        go(ctx, Intent(action), "I couldn't open that settings screen.")
        return "Opened the $which settings. Anuj has to tap the switch himself, Android does not let apps flip it."
    }

    fun scheduleTask(ctx: Context, a: JSONObject): String = when (a.optString("action")) {
        "add" -> Scheduler.add(ctx, a.optString("name"), a.optString("task"), a.optString("time"), a.optString("repeat") == "daily")
        "list" -> Scheduler.describe(ctx)
        "remove" -> if (Scheduler.remove(ctx, a.optString("name"))) "Removed that scheduled task." else "I have no scheduled task with that name."
        else -> throw Fail("action must be add, list or remove.")
    }
}

/**
 * "Roz subah 8 baje meri calendar bata dena": a saved line of text that Myraa is handed at a set time, exactly as if
 * Anuj had said it. It goes through the normal brain, so anything risky still asks him out loud first.
 * It only runs while Myraa is on; when Myraa is off the time still passes and a notification says what was missed.
 */
object Scheduler {
    private const val PREFS = "myra_tasks"
    private const val MAX_TASKS = 20
    private const val CHANNEL = "myra_tasks"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun load(ctx: Context): JSONArray = runCatching { JSONArray(p(ctx).getString("all", "[]") ?: "[]") }.getOrDefault(JSONArray())
    private fun save(ctx: Context, j: JSONArray) { p(ctx).edit().putString("all", j.toString()).apply() }
    private fun key(name: String) = name.trim().lowercase().replace(Regex("\\s+"), " ").take(40)

    private fun pending(ctx: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.hashCode(),
        Intent(ctx, TaskReceiver::class.java).setAction("dev.kairo.myra.TASK").putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun nextAt(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis() + 1000) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    private fun arm(ctx: Context, id: String, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, id)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun clock(t: JSONObject) = "%02d:%02d".format(t.optInt("hour"), t.optInt("minute"))

    fun add(ctx: Context, name: String, task: String, time: String, daily: Boolean): String {
        val id = key(name)
        if (id.isEmpty()) throw Fail("Give the task a short name.")
        if (task.isBlank()) throw Fail("What should Myraa do at that time? Give me the sentence.")
        val m = Regex("^\\s*(\\d{1,2})[:.](\\d{2})\\s*$").find(time) ?: throw Fail("Give the time as 24-hour HH:MM, for example 08:00.")
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) throw Fail("That is not a valid time.")
        val all = load(ctx)
        val kept = JSONArray()
        for (i in 0 until all.length()) all.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let { kept.put(it) }
        if (kept.length() >= MAX_TASKS) throw Fail("There are already $MAX_TASKS scheduled tasks. Remove one first.")
        val at = nextAt(h, min)
        kept.put(JSONObject().put("id", id).put("name", name.trim().take(40)).put("text", task.trim().take(300))
            .put("hour", h).put("minute", min).put("daily", daily).put("at", at))
        save(ctx, kept)
        arm(ctx, id, at)
        return "Scheduled \"${name.trim()}\" at %02d:%02d, ${if (daily) "every day" else "once"}. It runs only while Myraa is on.".format(h, min)
    }

    fun describe(ctx: Context): String {
        val all = load(ctx)
        if (all.length() == 0) return "No scheduled tasks."
        return (0 until all.length()).mapNotNull { all.optJSONObject(it) }
            .joinToString("; ") { "${it.optString("name")} at ${clock(it)} ${if (it.optBoolean("daily")) "daily" else "once"}" }
    }

    fun remove(ctx: Context, name: String): Boolean {
        val id = key(name)
        val all = load(ctx)
        val kept = JSONArray()
        var found = false
        for (i in 0 until all.length()) {
            val t = all.optJSONObject(i) ?: continue
            if (t.optString("id") == id) found = true else kept.put(t)
        }
        if (!found) return false
        save(ctx, kept)
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))
        return true
    }

    /** After a reboot or when exact-alarm permission changes: put every saved task back on the clock. */
    fun rescheduleAll(ctx: Context) {
        val all = load(ctx)
        val kept = JSONArray()
        for (i in 0 until all.length()) {
            val t = all.optJSONObject(i) ?: continue
            if (t.optBoolean("daily")) {
                val at = nextAt(t.optInt("hour"), t.optInt("minute"))
                t.put("at", at); kept.put(t); arm(ctx, t.optString("id"), at)
            } else if (t.optLong("at") > System.currentTimeMillis()) {
                kept.put(t); arm(ctx, t.optString("id"), t.optLong("at"))
            } // a one-time task whose time already passed while the phone was off is dropped
        }
        save(ctx, kept)
    }

    fun onFire(ctx: Context, id: String) {
        val all = load(ctx)
        var task: JSONObject? = null
        val kept = JSONArray()
        for (i in 0 until all.length()) {
            val t = all.optJSONObject(i) ?: continue
            if (t.optString("id") == id) {
                task = t
                if (t.optBoolean("daily")) { val at = nextAt(t.optInt("hour"), t.optInt("minute")); t.put("at", at); kept.put(t); arm(ctx, id, at) }
            } else kept.put(t)
        }
        save(ctx, kept)
        val t = task ?: return
        val text = t.optString("text")
        if (MyraService.running) {
            MyraService.send(ctx, MyraService.ACTION_ASK, text)
        } else {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Myraa scheduled tasks", NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(id.hashCode(), Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_orb)
                .setContentTitle("Myraa was off: ${t.optString("name")}").setContentText(text).setAutoCancel(true).build())
        }
    }
}

class TaskReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            when (intent.action) {
                "dev.kairo.myra.TASK" -> intent.getStringExtra("id")?.let { Scheduler.onFire(ctx, it) }
                else -> Scheduler.rescheduleAll(ctx) // boot finished, or exact-alarm permission changed
            }
        } catch (e: Exception) {
            android.util.Log.w("Myraa", "scheduled task failed: ${e.message}")
        }
    }
}
