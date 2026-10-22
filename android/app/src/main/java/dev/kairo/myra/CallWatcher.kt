package dev.kairo.myra

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager

/**
 * Notices an incoming phone call so Myraa can say who is calling and answer or decline it when
 * you tell him to. Needs the phone permissions the user grants in the app.
 *
 * [onRinging] gets the contact's name, or the number, or "" when Android doesn't tell us.
 * [onEnded] says the call stopped ringing (answered, declined, or the caller gave up).
 */
class CallWatcher(
    private val context: Context,
    private val onRinging: (String) -> Unit,
    private val onEnded: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    @Volatile private var ringing = false
    private var announced = false
    @Volatile private var number = ""
    private var pending: Runnable? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            @Suppress("DEPRECATION")
            val num = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                if (num.isNotEmpty()) number = num
                if (!ringing) { ringing = true; announced = false }
                // Android often sends the ringing news twice, the second time with the number.
                if (!announced && pending == null) {
                    val r = Runnable { pending = null; announced = true; onRinging(nameFor(number)) }
                    pending = r
                    main.postDelayed(r, 1200)
                }
            } else {
                pending?.let { main.removeCallbacks(it) }
                pending = null
                val wasAnnounced = announced
                ringing = false
                announced = false
                number = ""
                if (wasAnnounced) onEnded()
            }
        }
    }

    fun start() {
        if (registered) return
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        registered = true
        active = this
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
        if (active === this) active = null
        pending?.let { main.removeCallbacks(it) }
        pending = null
        ringing = false
        announced = false
    }

    /** True while a call is ringing and not yet answered or declined. */
    fun isRinging(): Boolean = ringing

    /** Who is calling right now: the contact's name, the number, or "" when Android doesn't tell us. */
    fun callerLabel(): String = nameFor(number)

    /** True when the caller is a saved contact. False for unknown numbers (or when contacts access is missing). */
    fun callerIsContact(): Boolean {
        val n = number
        if (n.isBlank()) return false
        return nameFor(n) != n
    }

    @SuppressLint("MissingPermission")
    fun accept(): Boolean = runCatching {
        context.getSystemService(TelecomManager::class.java).acceptRingingCall()
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun reject(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 28) context.getSystemService(TelecomManager::class.java).endCall() else false
    }.getOrDefault(false)

    /** The contact's name when the contacts permission is granted, otherwise the number itself. */
    private fun nameFor(num: String): String {
        if (num.isBlank()) return ""
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return num
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: num
    }

    companion object {
        /** The running watcher, so the call_control tool can reach it. Null when call announcing is off. */
        @Volatile var active: CallWatcher? = null

        val PERMISSIONS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
        )
    }
}
