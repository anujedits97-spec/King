package dev.kairo.myra.brain

import dev.kairo.myra.Fail
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Voice passphrase gate (option 2: it checks a phrase, it does NOT recognise your voice).
 * The phrase is stored only as a salted PBKDF2 hash, inside the encrypted settings file.
 * Sensitive tools (sending messages, deleting, deploying, creating issues...) are refused until you say the
 * passphrase, and the unlock lasts 10 minutes. Say "lock" to lock again.
 * Weakness: anyone who knows the phrase, or hears it, can unlock. Speech recognition may also mishear it.
 */
object VoicePass {
    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val MIN_LENGTH = 6
    @Volatile private var unlockedUntil = 0L

    /** Tools that must be unlocked first. Checked in ToolHub.call before anything runs. */
    val GATED = setOf(
        "telegram_send", "trigger_delete", "note_delete", "task_remove",
        "ssh_trust", "ssh_recipe", "ssh_deploy", "ssh_install", "ssh_clone", "ssh_upload_zip",
        "gdrive_connect", "gitlab_issue_create", "linear_issue_create",
        "send_sms", "sms_send", "whatsapp_send", "email_send", "place_call",
        // phone's own hands (DeviceServer): sending messages, calling, and turning off the anti-theft guard
        "send_message", "phone_call", "dial", "theft_guard"
    )

    private fun norm(p: String) = p.trim().lowercase()

    private fun derive(phrase: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(norm(phrase).toCharArray(), salt, 120_000, 256)).encoded

    fun isSet(env: BrainEnv): Boolean {
        val o = env.conf.obj("voicepass") ?: return false
        return o.optString("salt").isNotEmpty() && o.optString("hash").isNotEmpty()
    }

    fun set(env: BrainEnv, phrase: String) {
        if (norm(phrase).length < MIN_LENGTH) throw Fail("Use at least $MIN_LENGTH characters, and pick something you can say easily.")
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(phrase, salt)
        val root = JSONObject(env.conf.root.toString())
        root.put("voicepass", JSONObject()
            .put("salt", Base64.getEncoder().encodeToString(salt))
            .put("hash", Base64.getEncoder().encodeToString(hash)))
        env.conf.root = root
        env.conf.save()
        unlockedUntil = 0L
    }

    fun unlock(env: BrainEnv, phrase: String): String {
        if (!isSet(env)) throw Fail("No passphrase is set yet. Set one in Myraa app, Settings, Voice passphrase.")
        val o = env.conf.obj("voicepass")!!
        val salt = Base64.getDecoder().decode(o.optString("salt"))
        val want = Base64.getDecoder().decode(o.optString("hash"))
        val got = derive(phrase, salt)
        if (!MessageDigest.isEqual(want, got)) throw Fail("That is not your passphrase.")
        unlockedUntil = System.currentTimeMillis() + WINDOW_MS
        return "Unlocked for 10 minutes."
    }

    fun lock(): String {
        unlockedUntil = 0L
        return "Locked. Sensitive actions need your passphrase again."
    }

    fun isUnlocked(): Boolean = System.currentTimeMillis() < unlockedUntil

    /** Called by ToolHub before every tool. Throws when a sensitive tool is used while locked. */
    fun checkGate(env: BrainEnv, tool: String) {
        if (tool !in GATED || isUnlocked()) return
        throw Fail(if (isSet(env)) "Say your passphrase first, then I can do that."
            else "Set a voice passphrase in the Myraa app first, under Settings.")
    }
}

/** Tools to unlock and lock. The phrase is spoken once here and never repeated back. */
class VoicePassModule(private val env: BrainEnv) : ToolModule {
    override fun tools(): List<BrainTool> = listOf(
        tool("voice_unlock", "Unlock sensitive actions for 10 minutes with the user's spoken passphrase. Use only when the user says the passphrase. Never repeat the passphrase in a reply.", Risk.LOW,
            schema(listOf("passphrase"), "passphrase" to prop("string"))) { a ->
            VoicePass.unlock(env, a.optString("passphrase"))
        },
        tool("voice_lock", "Lock sensitive actions again right away, when the user asks.", Risk.LOW, schema(emptyList())) { VoicePass.lock() },
    )
}
