package dev.kairo.myra

import android.app.Notification
import android.content.Context
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Says out loud when a notification arrives: "WhatsApp par Rahul ka message: ...".
 * Off by default (Settings, "Announce notifications"). It skips this app, ongoing and group-summary
 * notifications, OTP and bank/UPI messages, private (lock screen) content, and anything during a call.
 * At most one announcement every few seconds, so a burst of messages never turns into a flood.
 */
class AnnounceService : NotificationListenerService() {
    private var speaker: Speaker? = null
    private var lastSpokenAt = 0L
    private var lastText = ""

    override fun onCreate() {
        super.onCreate()
        speaker = Speaker(this, Prefs(this))
    }

    override fun onDestroy() {
        speaker?.shutdown()
        speaker = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            sbn?.let { fireTriggers(it) }
        } catch (e: Exception) {
            Log.w(TAG, "triggers skipped: ${e.javaClass.simpleName}")
        }
        try {
            handle(sbn ?: return)
        } catch (e: Exception) {
            Log.w(TAG, "announce skipped: ${e.javaClass.simpleName}")
        }
    }

    /** Event triggers see every notification (not only when announcing is on). */
    private fun fireTriggers(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val ex = sbn.notification?.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        dev.kairo.myra.brain.TriggerEngine.onNotification(this, sbn.packageName, appLabel(sbn.packageName), title, text)
    }

    private fun handle(sbn: StatusBarNotification) {
        val prefs = Prefs(applicationContext)
        if (!prefs.announceNotifs) return
        if (sbn.packageName == packageName) return

        val n = sbn.notification ?: return
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return

        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.mode != AudioManager.MODE_NORMAL) return // in a call: stay quiet

        val app = appLabel(sbn.packageName)
        val now = System.currentTimeMillis()
        if (now - lastSpokenAt < MIN_GAP_MS) return

        // Lock-screen privacy: if the phone hides the content, say only the app name.
        val hidden = n.visibility == Notification.VISIBILITY_PRIVATE || n.visibility == Notification.VISIBILITY_SECRET
        val ex = n.extras ?: return
        val who = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()

        val sentence = when {
            hidden || (who.isEmpty() && body.isEmpty()) -> "$app par naya notification aaya hai."
            SENSITIVE.containsMatchIn("$who $body") -> "$app par ek sensitive message aaya hai, jaise OTP ya bank ka message. Mein usse nahi bolungi."
            who.isNotEmpty() && body.isNotEmpty() -> "$app par ${who} ka message: ${body.take(MAX_CHARS)}"
            else -> "$app par naya notification: ${(who + body).take(MAX_CHARS)}"
        }

        if (sentence == lastText && now - lastSpokenAt < REPEAT_MS) return
        lastText = sentence
        lastSpokenAt = now
        speaker?.speak(sentence, object : Speaker.Listener {
            override fun onWord() {}
            override fun onDone() {}
        })
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg.substringAfterLast('.')
    }

    companion object {
        private const val TAG = "MyraAnnounce"
        private const val MIN_GAP_MS = 4_000L
        private const val REPEAT_MS = 60_000L
        private const val MAX_CHARS = 200
        // OTPs, bank, UPI and card messages are never read aloud.
        private val SENSITIVE = Regex(
            "(\\botp\\b|one.time.password|verification code|\\bcode is\\b|debited|credited|\\bupi\\b|\\bbank\\b|\\bcard\\b|\\bcvv\\b|\\bpin\\b)",
            RegexOption.IGNORE_CASE
        )
    }
}
