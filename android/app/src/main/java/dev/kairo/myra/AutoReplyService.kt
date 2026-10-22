package dev.kairo.myra

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Optional "I'm busy" auto-reply, the idea of IRIS-MX's notification auto-responder, but made much
 * stricter. It uses Android's own inline-reply button on the notification (the same thing Wear OS
 * and Android Auto use), not accessibility tricks.
 *
 * It does nothing unless BOTH are true: you gave Myraa Notification access in Android settings,
 * and you switched "Auto-reply" on in Myraa Settings (or said yes out loud to the auto_reply tool).
 *
 * Fixed here in code, never decided by the model:
 *  - the reply is always YOUR saved text, plus "(auto-reply)" so nobody thinks a person typed it.
 *    No AI writes it, so a message can't trick Myraa into saying something. The one exception is
 *    "smart replies", off by default and switched on only by a spoken yes: then the AI engine you
 *    chose words ONE short line, with no tools and no memory, the brain throws away anything with a
 *    link, number or e-mail in it, and if anything fails your saved text is sent instead;
 *  - only WhatsApp, WhatsApp Business, Telegram and Instagram, and only one-to-one chats (never groups);
 *  - at most one reply per person per hour, and at most 10 replies per hour in total;
 *  - messages that look like codes, OTPs or passwords are never answered;
 *  - nothing from the message is stored or logged.
 */
class AutoReplyService : NotificationListenerService() {
    private val lastReply = HashMap<String, Long>()
    private val sentAt = ArrayList<Long>()

    override fun onListenerConnected() { instance = this }

    override fun onListenerDisconnected() { if (instance === this) instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            handle(sbn ?: return)
        } catch (e: Exception) {
            Log.w(TAG, "auto-reply skipped: ${e.javaClass.simpleName}")
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val prefs = Prefs(applicationContext)
        if (!prefs.autoReply) return
        if (sbn.packageName !in APPS) return
        val n = sbn.notification ?: return
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return
        val ex = n.extras ?: return
        if (ex.getBoolean("android.isGroupConversation", false)) return

        val who = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val msg = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (who.isEmpty() || msg.isEmpty()) return
        if (SKIP.containsMatchIn(msg)) return
        // A title like "Team chat (3 messages)" or "2 new messages" is not one person.
        if (who.contains("messages", ignoreCase = true)) return

        val action = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } ?: return
        val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return

        val now = System.currentTimeMillis()
        synchronized(this) {
            sentAt.removeAll { it < now - HOUR }
            if (sentAt.size >= MAX_PER_HOUR) return
            val key = sbn.packageName + "|" + who.lowercase(Locale.ROOT)
            if ((lastReply[key] ?: 0L) > now - HOUR) return
            lastReply[key] = now
            sentAt.add(now)
            if (lastReply.size > 200) lastReply.entries.removeAll { it.value < now - HOUR }
        }

        val fixed = prefs.autoReplyText.trim().take(160)
        val pkg = sbn.packageName
        if (!prefs.autoReplySmart) {
            send(action, input, "$fixed (auto-reply)", pkg)
            return
        }
        // Smart mode waits for the AI, so it can't run on the main thread. Any failure sends the saved text.
        val brain = prefs.brainAddress
        val token = prefs.token
        Thread {
            val line = runCatching { smartLine(brain, token, who, pkg, msg) }.getOrNull()
            send(action, input, "${line ?: fixed} (auto-reply)", pkg)
        }.start()
    }

    private fun send(action: Notification.Action, input: RemoteInput, text: String, pkg: String) {
        val fill = Intent()
        val bundle = Bundle().apply { putCharSequence(input.resultKey, text) }
        RemoteInput.addResultsToIntent(arrayOf(input), fill, bundle)
        try {
            action.actionIntent.send(applicationContext, 0, fill)
            Log.i(TAG, "auto-reply sent via $pkg")
        } catch (e: Exception) {
            Log.w(TAG, "auto-reply could not be sent: ${e.javaClass.simpleName}")
        }
    }

    /** One short line from the brain's AI, or null to use the saved text. Nothing is logged or kept. */
    private fun smartLine(brain: String, token: String, who: String, pkg: String, msg: String): String? {
        val c = URL("http://$brain/reply").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 3000
            c.readTimeout = 15000
            c.doOutput = true
            c.setRequestProperty("x-myra-token", token)
            c.setRequestProperty("content-type", "application/json")
            val body = JSONObject().put("who", who).put("app", LABELS[pkg] ?: "chat").put("message", msg.take(500)).toString()
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) return null
            val text = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).optString("text").trim()
            // The brain already checked this. Checking again here costs nothing.
            return text.takeIf { it.isNotEmpty() && it.length <= 160 && !UNSAFE.containsMatchIn(it) }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        /** The connected listener while Notification access is on; PhoneTools reads the shade through it. */
        @Volatile var instance: AutoReplyService? = null

        private const val TAG = "MyraAutoReply"
        private const val HOUR = 3_600_000L
        private const val MAX_PER_HOUR = 10

        val APPS = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.instagram.android")
        private val LABELS = mapOf("com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp", "org.telegram.messenger" to "Telegram", "com.instagram.android" to "Instagram")
        private val UNSAFE = Regex("(https?:|www\\.|@\\w|\\d{4,}|\\.(com|in|org|net)\\b)", RegexOption.IGNORE_CASE)
        const val APP_NAMES = "WhatsApp, Telegram and Instagram"

        // Codes, one-time passwords and anything that sounds like a login or a payment is never answered.
        private val SKIP = Regex("(otp|one.?time|verification|passcode|password|\\bpin\\b|\\bcvv\\b|\\b\\d{4,8}\\b|upi|a/c|account)", RegexOption.IGNORE_CASE)

        /** True when Android has given this app Notification access. */
        fun listening(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return s.split(":").any { ComponentName.unflattenFromString(it)?.packageName == ctx.packageName }
        }
    }
}
