package dev.kairo.myra

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The optional PIN that keeps the Myraa app screens (settings, keys, accounts) closed to anyone who
 * picks up your phone. The idea comes from Maya's PIN security, but Maya keeps the PIN as plain text
 * with a default of 1234. Here:
 *
 *  - the PIN is never stored. Only a salted PBKDF2 hash is kept ("salt:hash");
 *  - 4 to 8 digits, and not all the same digit;
 *  - five wrong tries in a row lock the PIN box for a minute.
 *
 * It locks the app SCREENS only. Talking to Myraa (the orb, the wake word) still works, because
 * that is how you use the assistant with the phone in your pocket. It is not encryption: your keys
 * are protected separately by the Android Keystore (see Vault).
 */
object AppLock {
    private const val ITERATIONS = 60_000
    private const val MAX_TRIES = 5
    private const val LOCK_MS = 60_000L

    private var fails = 0
    private var lockedUntil = 0L

    fun isSet(prefs: Prefs): Boolean = prefs.pinHash.isNotEmpty()

    /** Null when the PIN may be used, otherwise a plain-words reason why not. */
    fun problem(pin: String): String? = when {
        pin.length !in 4..8 -> "Use 4 to 8 digits."
        !pin.all { it.isDigit() } -> "Digits only."
        pin.toSet().size == 1 -> "Please don't use one digit repeated."
        else -> null
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)).encoded

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun set(prefs: Prefs, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.pinHash = hex(salt) + ":" + hex(hash(pin, salt))
        synchronized(this) { fails = 0; lockedUntil = 0L }
    }

    fun clear(prefs: Prefs) {
        prefs.pinHash = ""
        synchronized(this) { fails = 0; lockedUntil = 0L }
    }

    /** Seconds to wait before the next try; 0 when trying is allowed. */
    @Synchronized
    fun waitSeconds(): Int {
        val left = lockedUntil - System.currentTimeMillis()
        return if (left <= 0) 0 else (left / 1000 + 1).toInt()
    }

    /** True when [pin] is right. While locked out it is always false. */
    @Synchronized
    fun check(prefs: Prefs, pin: String): Boolean {
        if (waitSeconds() > 0) return false
        val parts = prefs.pinHash.split(":")
        if (parts.size != 2) return true // nothing usable is stored, so there is nothing to guard
        val ok = try {
            MessageDigest.isEqual(hex(hash(pin, unhex(parts[0]))).toByteArray(), parts[1].toByteArray())
        } catch (e: Exception) {
            false
        }
        if (ok) {
            fails = 0
            return true
        }
        fails++
        if (fails >= MAX_TRIES) {
            fails = 0
            lockedUntil = System.currentTimeMillis() + LOCK_MS
        }
        return false
    }
}
