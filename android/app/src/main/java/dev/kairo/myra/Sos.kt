package dev.kairo.myra

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Emergency SOS. Five quick presses of the power button (screen on/off) start a 5-second countdown with a
 * notification that has a "Cancel" button. If it is not cancelled, Myraa texts your saved contacts a message
 * with your last known location. Off until you switch it on in SOS settings. Contacts and the message stay on the phone.
 */
object Sos {
    private const val ACTION_CANCEL = "dev.kairo.myra.SOS_CANCEL"
    private const val CHANNEL = "myra_sos"
    private const val NOTE_ID = 4411
    private const val COUNTDOWN_MS = 5_000L
    private const val PRESS_WINDOW_MS = 3_000L
    private const val PRESSES_NEEDED = 5
    private val main = Handler(Looper.getMainLooper())
    private val presses = ArrayList<Long>()
    private var countdown: Runnable? = null
    private var cancelReceiver: BroadcastReceiver? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "myra/sos.json")

    fun load(ctx: Context): JSONObject = try {
        if (file(ctx).exists()) JSONObject(file(ctx).readText()) else JSONObject()
    } catch (e: Exception) { JSONObject() }

    fun enabled(ctx: Context): Boolean = load(ctx).optBoolean("enabled", false)

    fun contacts(ctx: Context): List<Pair<String, String>> {
        val arr = load(ctx).optJSONArray("contacts") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { it.optString("name") to it.optString("number") }
        }
    }

    fun message(ctx: Context): String =
        load(ctx).optString("message").ifEmpty { "I need help. This is an SOS from my phone." }

    fun save(ctx: Context, enabled: Boolean, contacts: List<Pair<String, String>>, message: String) {
        val arr = JSONArray()
        contacts.forEach { (n, num) -> arr.put(JSONObject().put("name", n).put("number", num)) }
        val o = JSONObject().put("enabled", enabled).put("contacts", arr).put("message", message.take(300))
        file(ctx).parentFile?.mkdirs()
        file(ctx).writeText(o.toString(1))
    }

    /** A number is kept only if it looks like a phone number (optional +, 8 to 15 digits). */
    fun cleanNumber(raw: String): String? {
        val n = raw.replace(Regex("[\\s-]"), "")
        return if (Regex("^\\+?\\d{8,15}$").matches(n)) n else null
    }

    /** Called for every power press (screen on or off). True when five quick presses have happened. */
    @Synchronized
    fun onPowerPress(ctx: Context): Boolean {
        if (!enabled(ctx)) { presses.clear(); return false }
        val now = SystemClock.elapsedRealtime()
        presses.add(now)
        presses.removeAll { now - it > PRESS_WINDOW_MS }
        if (presses.size >= PRESSES_NEEDED) { presses.clear(); return true }
        return false
    }

    /** Starts the countdown. A second start while one is running is ignored. */
    @Synchronized
    fun start(ctx: Context) {
        if (countdown != null) return
        val app = ctx.applicationContext
        makeChannel(app)
        registerCancel(app)
        val cancelIntent = PendingIntent.getBroadcast(
            app, 0, Intent(ACTION_CANCEL).setPackage(app.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val note = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SOS in 5 seconds")
            .setContentText("Your contacts will be texted unless you cancel.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent).build())
            .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTE_ID, note)
        val task = Runnable { countdown = null; send(app) }
        countdown = task
        main.postDelayed(task, COUNTDOWN_MS)
    }

    /** Stops a pending SOS. Safe to call when nothing is pending. */
    @Synchronized
    fun cancel(ctx: Context) {
        val app = ctx.applicationContext
        countdown?.let { main.removeCallbacks(it) }
        countdown = null
        unregisterCancel(app)
        app.getSystemService(NotificationManager::class.java).cancel(NOTE_ID)
        notify(app, "SOS cancelled", "Nobody was texted.")
    }

    private fun send(app: Context) {
        unregisterCancel(app)
        app.getSystemService(NotificationManager::class.java).cancel(NOTE_ID)
        val list = contacts(app)
        if (list.isEmpty()) { notify(app, "SOS not sent", "You have no emergency contacts saved."); return }
        val where = location(app)
        val body = message(app) + (if (where == null) "" else " My location: $where")
        var sent = 0
        var failed = 0
        for ((_, num) in list) {
            try { PhoneTools.sendSms(app, num, body); sent++ } catch (e: Exception) { failed++ }
        }
        notify(app, "SOS sent", "Texted $sent contact(s)." + if (failed > 0) " $failed failed." else "")
    }

    /** Last known location as a map link, or null when location is off or not allowed. */
    private fun location(ctx: Context): String? {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val loc = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) { null }
        return loc?.let { "https://maps.google.com/?q=${it.latitude},${it.longitude}" }
    }

    private fun makeChannel(app: Context) {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Emergency SOS", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun notify(app: Context, title: String, text: String) {
        makeChannel(app)
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTE_ID + 1, n)
    }

    private fun registerCancel(app: Context) {
        if (cancelReceiver != null) return
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { cancel(c) }
        }
        cancelReceiver = rcv
        val filter = IntentFilter(ACTION_CANCEL)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rcv, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(rcv, filter)
    }

    private fun unregisterCancel(app: Context) {
        cancelReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        cancelReceiver = null
    }
}
