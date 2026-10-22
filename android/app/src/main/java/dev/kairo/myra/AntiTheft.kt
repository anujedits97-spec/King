package dev.kairo.myra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Display
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Anti-theft guard. Arm it, put the phone down, and if somebody picks it up or pulls the charger out, it:
 *   1. rings a loud siren on the alarm volume (even if the phone is on silent),
 *   2. takes a photo with the front camera (the intruder's face),
 *   3. posts a notification with the photo. The photo is also saved in the app's "guard" folder.
 *
 * The siren stops only when Anuj disarms it: the "Disarm" button on the notification (Android asks for the screen
 * lock first), or the Myraa app. It stops by itself after 3 minutes.
 *
 * Triggers: the phone is moved (after a 10 second grace period so you can walk away) or the charger is unplugged.
 * It does NOT notice wrong unlock attempts: that needs Device Administrator rights, which this app does not ask for.
 * Needs the app's foreground service to be running (the "Myraa is ready" notification).
 *
 * NOT compiled or tested here. Test it with the volume low before trusting it.
 */
object AntiTheft {
    private const val CHANNEL = "myra_guard"
    private const val ACTION_DISARM = "dev.kairo.myra.GUARD_DISARM"
    private const val NOTIFICATION_ID = 7770
    private const val GRACE_MS = 10_000L
    private const val SIREN_MAX_MS = 180_000L
    private const val MOVE_THRESHOLD = 2.2f // m/s², change in total acceleration that counts as "picked up"
    @Volatile private var threshold = MOVE_THRESHOLD

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var armedAt = 0L
    @Volatile var armed = false
        private set
    @Volatile private var alarming = false
    private var wasPlugged = false
    private var lastMag = 0f
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private var sensors: SensorManager? = null
    private var receiversOn = false

    private val sensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(e: SensorEvent) {
            if (!armed || alarming || System.currentTimeMillis() - armedAt < GRACE_MS) { lastMag = magnitude(e); return }
            val m = magnitude(e)
            if (lastMag != 0f && abs(m - lastMag) > threshold) trigger("The phone was moved.")
            lastMag = m
        }
        private fun magnitude(e: SensorEvent) = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_POWER_DISCONNECTED -> if (armed && wasPlugged && System.currentTimeMillis() - armedAt >= GRACE_MS) trigger("The charger was pulled out.")
                Intent.ACTION_POWER_CONNECTED -> wasPlugged = true
                ACTION_DISARM -> disarm()
            }
        }
    }

    /** Switches the guard on. Returns a short sentence for Anuj. */
    @Synchronized
    fun arm(ctx: Context, prefs: Prefs): String {
        val c = ctx.applicationContext
        app = c
        if (armed) return "Guard pehle se on hai."
        threshold = when (prefs.theftSensitivity) { "low" -> 3.5f; "high" -> 1.2f; else -> MOVE_THRESHOLD }
        armed = true
        armedAt = System.currentTimeMillis()
        lastMag = 0f
        prefs.theftGuard = true
        wasPlugged = (c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val filter = IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(ACTION_DISARM) }
        if (!receiversOn) {
            if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else c.registerReceiver(receiver, filter)
            receiversOn = true
        }
        val sm = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensors = sm
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        return "Guard on. Phone ko rakh dijiye, 10 second ke baad hilne par siren bajega."
    }

    /** Switches the guard off and stops the siren. */
    @Synchronized
    fun disarm(prefs: Prefs? = null): String {
        val c = app ?: return "Guard on nahi tha."
        armed = false
        (prefs ?: Prefs(c)).theftGuard = false
        sensors?.unregisterListener(sensorListener)
        if (receiversOn) { runCatching { c.unregisterReceiver(receiver) }; receiversOn = false }
        stopSiren()
        c.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        return "Guard band kar diya."
    }

    fun status(): String = if (alarming) "Siren baj raha hai." else if (armed) "Guard on hai." else "Guard off hai."

    /** Call when the service starts, so a guard that was on survives a restart of the app. */
    fun restore(ctx: Context, prefs: Prefs) { if (prefs.theftGuard && !armed) arm(ctx, prefs) }

    private fun trigger(reason: String) {
        if (alarming) return
        alarming = true
        val c = app ?: return
        startSiren(c)
        notify(c, "Myraa guard: $reason", "Siren chal raha hai. Disarm karne ke liye phone unlock karein.", null)
        thread(name = "myra-guard-selfie", isDaemon = true) {
            val file = runCatching { selfie(c) }.getOrNull()
            if (file != null) notify(c, "Myraa guard: $reason", "Intruder ki photo mil gayi. Siren chal raha hai.", file)
        }
        main.postDelayed({ if (alarming) stopSiren() }, SIREN_MAX_MS)
    }

    private fun startSiren(c: Context) {
        runCatching {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(c, uri)
                isLooping = true
                prepare()
                start()
            }
        }
        runCatching {
            val v = c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300), 0))
        }
    }

    private fun stopSiren() {
        alarming = false
        runCatching { player?.stop(); player?.release() }
        player = null
        val c = app ?: return
        runCatching {
            if (savedVolume >= 0) (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            (c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
        }
        savedVolume = -1
    }

    /** Front camera photo, saved in the app's guard folder. Runs on a background thread. */
    private fun selfie(c: Context): File? {
        val rotation = (c.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        var res: SilentCamera.Result? = null
        if (MyraService.cameraReady) res = runCatching { SilentCamera.capture(c, true, rotation) }.getOrNull()
        if (res?.jpeg == null) res = runCatching { CameraCaptureActivity.start(c, true, rotation).get(20, TimeUnit.SECONDS) }.getOrNull()
        val jpeg = res?.jpeg ?: return null
        val dir = File(c.getExternalFilesDir(null), "guard").apply { mkdirs() }
        return File(dir, "intruder-${System.currentTimeMillis()}.jpg").apply { writeBytes(jpeg) }
    }

    private fun notify(c: Context, title: String, text: String, photo: File?) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Anti-theft guard", NotificationManager.IMPORTANCE_HIGH))
        val disarmIntent = PendingIntent.getBroadcast(
            c, 1, Intent(ACTION_DISARM).setPackage(c.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(null, "Disarm", disarmIntent).apply {
            if (Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true) // the thief cannot just tap it
        }.build()
        val b = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_orb)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .addAction(action)
        if (photo != null) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(photo.absolutePath, opts)?.let { b.setStyle(Notification.BigPictureStyle().bigPicture(it)) }
        }
        nm.notify(NOTIFICATION_ID, b.build())
    }
}
