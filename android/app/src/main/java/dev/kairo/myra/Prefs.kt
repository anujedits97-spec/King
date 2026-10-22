package dev.kairo.myra

import android.content.Context
import android.content.SharedPreferences

/** Everything the user sets in the app, kept on this phone only. */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("myra", Context.MODE_PRIVATE)

    init {
        // 1.3.0 brought the real Myraa voice: switch everyone to it once (they can pick a phone voice again).
        if (p.getInt("voiceVersion", 0) < 1) p.edit().putString("voice", "").remove("rate").putInt("voiceVersion", 1).apply()
        // Secrets are kept encrypted (see Vault). Older versions stored them as plain text: seal them once.
        for (k in listOf("token", "relayKey", "relaySecret")) {
            val raw = p.getString(k, "") ?: ""
            if (raw.isNotEmpty() && !raw.startsWith(Vault.PREFIX)) {
                val sealed = Vault.seal(raw)
                if (sealed != raw) p.edit().putString(k, sealed).apply()
            }
        }
    }

    var brainAddress: String
        get() = p.getString("brain", DEFAULT_BRAIN) ?: DEFAULT_BRAIN
        set(v) = p.edit().putString("brain", v.trim().ifEmpty { DEFAULT_BRAIN }).apply()

    var token: String
        get() = Vault.open(p.getString("token", "") ?: "")
        set(v) = p.edit().putString("token", Vault.seal(v.trim())).apply()

    /** What the user picked: "laptop" (think on the laptop through Supabase) or "phone" (the brain in Termux). */
    var mode: String
        get() = p.getString("mode", "phone") ?: "phone"
        set(v) = p.edit().putString("mode", v).apply()

    /** Paired with the laptop: the QR code's link, key and secret are all saved. */
    val paired: Boolean
        get() = relayUrl.isNotEmpty() && relayKey.isNotEmpty() && relaySecret.length >= 32

    /**
     * The brain that really answers. The laptop only when it is chosen AND paired; otherwise
     * the brain in Termux on this phone, so Myraa works without any pairing.
     */
    val activeMode: String
        get() = if (mode == "laptop" && paired) "laptop" else "phone"

    var relayUrl: String
        get() = p.getString("relayUrl", "") ?: ""
        set(v) = p.edit().putString("relayUrl", v.trim().trimEnd('/')).apply()

    var relayKey: String
        get() = Vault.open(p.getString("relayKey", "") ?: "")
        set(v) = p.edit().putString("relayKey", Vault.seal(v.trim())).apply()

    var relaySecret: String
        get() = Vault.open(p.getString("relaySecret", "") ?: "")
        set(v) = p.edit().putString("relaySecret", Vault.seal(v.trim())).apply()

    /** Myraa says who is calling and answers or declines when told to (needs phone permissions). */
    /** While Myraa is on, pressing the power button twice quickly starts listening. Off until you switch it on. */
    var powerDoubleTap: Boolean
        get() = p.getBoolean("powerDoubleTap", false)
        set(v) = p.edit().putBoolean("powerDoubleTap", v).apply()

    /** Myraa may take one photo with the camera when Anuj asks (camera_look). Off until switched on. */
    var cameraVision: Boolean
        get() = p.getBoolean("cameraVision", false)
        set(v) = p.edit().putBoolean("cameraVision", v).apply()

    /** Myraa taps "Start" on Android's screen-share dialog itself (through the Accessibility service). Off until switched on. */
    var autoShare: Boolean
        get() = p.getBoolean("autoShare", false)
        set(v) = p.edit().putBoolean("autoShare", v).apply()

    var announceCalls: Boolean
        get() = p.getBoolean("announceCalls", false)
        set(v) = p.edit().putBoolean("announceCalls", v).apply()

    /** Show Myraa's face on the home screen instead of the arc reactor. */
    var showAvatar: Boolean
        get() = p.getBoolean("showAvatar", true)
        set(v) = p.edit().putBoolean("showAvatar", v).apply()

    /** Anti-theft guard (see AntiTheft). Remembered so it comes back after the app restarts. */
    /** How easily the guard trips: low, medium or high. Movement needed to trip: 3.5, 2.2 or 1.2 m/s². */
    var theftSensitivity: String
        get() = p.getString("theftSensitivity", "medium") ?: "medium"
        set(v) = p.edit().putString("theftSensitivity", if (v in setOf("low", "medium", "high")) v else "medium").apply()

    var theftGuard: Boolean
        get() = p.getBoolean("theftGuard", false)
        set(v) = p.edit().putBoolean("theftGuard", v).apply()

    /** Myraa takes incoming calls herself (see CallAssistant). Off until you switch it on. */
    var callAssistant: Boolean
        get() = p.getBoolean("callAssistant", false)
        set(v) = p.edit().putBoolean("callAssistant", v).apply()

    /** When true, Myraa takes only calls from numbers that are not saved contacts. */
    var callAssistantUnknownOnly: Boolean
        get() = p.getBoolean("callAssistantUnknownOnly", true)
        set(v) = p.edit().putBoolean("callAssistantUnknownOnly", v).apply()

    /** Automatic "busy" reply to one-to-one chats (see AutoReplyService). Off until you switch it on. */
    /** Say out loud when a notification arrives (needs Notification access for "AnnounceService"). */
    var announceNotifs: Boolean
        get() = p.getBoolean("announceNotifs", false)
        set(v) = p.edit().putBoolean("announceNotifs", v).apply()

    /** When on, Myraa does not save new memories. Old ones stay until you delete them in Yaadein. */
    var incognito: Boolean
        get() = p.getBoolean("incognito", false)
        set(v) = p.edit().putBoolean("incognito", v).apply()

    var autoReply: Boolean
        get() = p.getBoolean("autoReply", false)
        set(v) = p.edit().putBoolean("autoReply", v).apply()

    /** Calling code (like "91") put in front of 10-digit numbers for WhatsApp links. India by default when the phone's region is India. */
    var countryCode: String
        get() = p.getString("countryCode", null) ?: if (java.util.Locale.getDefault().country == "IN") "91" else ""
        set(v) = p.edit().putString("countryCode", v.filter { it.isDigit() }.take(4)).apply()

    var autoReplyText: String
        get() = p.getString("autoReplyText", null) ?: "Anuj abhi busy hai, thodi der mein reply karega."
        set(v) = p.edit().putString("autoReplyText", v.trim().take(160)).apply()

    /** Let the AI engine word the auto-reply (still labelled, filtered and rate-limited). Off by default. */
    var autoReplySmart: Boolean
        get() = p.getBoolean("autoReplySmart", false)
        set(v) = p.edit().putBoolean("autoReplySmart", v).apply()

    var orb: Boolean
        get() = p.getBoolean("orb", true)
        set(v) = p.edit().putBoolean("orb", v).apply()

    var wakeWord: Boolean
        get() = p.getBoolean("wake", true)
        set(v) = p.edit().putBoolean("wake", v).apply()

    var followUp: Boolean
        get() = p.getBoolean("followUp", true)
        set(v) = p.edit().putBoolean("followUp", v).apply()

    var captions: Boolean
        get() = p.getBoolean("captions", true)
        set(v) = p.edit().putBoolean("captions", v).apply()

    var language: String
        get() = p.getString("lang", "en-IN") ?: "en-IN"
        set(v) = p.edit().putString("lang", v.trim().ifEmpty { "en-IN" }).apply()

    /** "" = the Myraa voice (recorded by the laptop); otherwise one of the phone's own voices. */
    var voiceName: String
        get() = p.getString("voice", "") ?: ""
        set(v) = p.edit().putString("voice", v).apply()

    /** Speech rate ×100 (100 = normal). */
    var speechRate: Int
        get() = p.getInt("rate", 93)
        set(v) = p.edit().putInt("rate", v.coerceIn(50, 200)).apply()

    /** Where the orb sits on the right edge, as a fraction of the screen height. */
    var orbY: Float
        get() = p.getFloat("orbY", 0.42f)
        set(v) = p.edit().putFloat("orbY", v.coerceIn(0.05f, 0.9f)).apply()

    var running: Boolean
        get() = p.getBoolean("running", false)
        set(v) = p.edit().putBoolean("running", v).apply()

    /** The one word that wakes Myraa, offline. "myra" unless you change it (Settings or by voice). */
    var wakePhrase: String
        get() = p.getString("wakePhrase", null)?.ifBlank { null }?.takeUnless { it == "myra" } ?: "myra" // "myra" was the old default: it becomes "myra"
        set(v) = p.edit().putString("wakePhrase", v.trim().lowercase(java.util.Locale.ROOT).filter { it.isLetter() }.take(20).ifEmpty { "myra" }).apply()

    /** An extra phrase that ends a conversation, like "bye bye". Empty means only the built-in ones (stop, thanks...). */
    var stopCommand: String
        get() = p.getString("stopCommand", "") ?: ""
        set(v) = p.edit().putString("stopCommand", v.trim().lowercase(java.util.Locale.ROOT).trimEnd('.', '!', '?', ',').take(40)).apply()

    /** How Myraa talks: sweet (Myraa), calm, friendly, professional or energetic. */
    var voiceStyle: String
        get() = p.getString("voiceStyle", "sweet") ?: "sweet"
        set(v) = p.edit().putString("voiceStyle", v).apply()

    /** The app-lock PIN is never kept as is: "salt:hash" (see AppLock). Empty means no lock. */
    var pinHash: String
        get() = p.getString("pinHash", "") ?: ""
        set(v) = p.edit().putString("pinHash", v).apply()

    companion object {
        const val DEFAULT_BRAIN = "127.0.0.1:8765"
    }
}
