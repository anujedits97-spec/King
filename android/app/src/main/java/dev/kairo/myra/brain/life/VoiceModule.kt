package dev.kairo.myra.brain.life

import dev.kairo.myra.Fail
import dev.kairo.myra.MyraService
import dev.kairo.myra.brain.BrainEnv
import dev.kairo.myra.brain.BrainTool
import dev.kairo.myra.brain.Risk
import dev.kairo.myra.brain.ToolModule
import dev.kairo.myra.brain.prop
import dev.kairo.myra.brain.schema
import dev.kairo.myra.brain.tool
import java.util.Locale

/**
 * Lets Anuj change how Myraa listens and sounds just by asking, like Maya's CHANGE_WAKE_PHRASE,
 * CHANGE_STOP_COMMAND, CHANGE_LANGUAGE and voice-style intents: "wake word maya kar do",
 * "bye bye bolun to ruk jaana", "Bangla mein baat karo", "thoda calm ho ja".
 *
 * Only these four settings can be changed, from fixed lists or plain words. Nothing here reaches other
 * people or touches system settings, so it is MEDIUM risk (no spoken yes needed).
 */
class VoiceModule(private val env: BrainEnv) : ToolModule {
    private val prefs get() = env.prefs

    private fun refresh() {
        if (MyraService.running) MyraService.send(env.ctx, MyraService.ACTION_START)
    }

    /** The spoken style hint and, for Bangla or Tamil, the language hint, added to Myraa's instructions. */
    fun promptExtra(): String {
        val sb = StringBuilder()
        val style = STYLES[prefs.voiceStyle]
        if (prefs.voiceStyle != "sweet" && style != null) sb.append("\n\nSpeaking style right now: ").append(style)
        val lang = prefs.language
        if (lang.startsWith("bn") || lang.startsWith("ta")) {
            val name = Locale.forLanguageTag(lang).getDisplayLanguage(Locale.ENGLISH)
            sb.append("\n\nAnuj set the listening and speaking language to ").append(name)
                .append(". Answer in ").append(name).append(" (in its own script) unless he speaks to you in another language.")
        }
        return sb.toString()
    }

    private fun langCode(v: String): String? {
        val q = v.trim().lowercase(Locale.ROOT)
        LANGS.keys.firstOrNull { it.lowercase(Locale.ROOT) == q }?.let { return it }
        return when {
            q.contains("bangla") || q.contains("bengali") || q.contains("bangladesh") -> if (q.contains("india")) "bn-IN" else "bn-BD"
            q.contains("hindi") -> "hi-IN"
            q.contains("tamil") -> "ta-IN"
            Regex("\\b(uk|british)\\b").containsMatchIn(q) -> "en-GB"
            Regex("\\b(us|usa|american)\\b").containsMatchIn(q) -> "en-US"
            q.contains("english") -> "en-IN"
            else -> null
        }
    }

    private fun change(a: org.json.JSONObject): String {
        val value = a.optString("value").trim()
        return when (a.optString("action")) {
            "get" -> "Wake word: ${prefs.wakePhrase}. Extra stop phrase: ${prefs.stopCommand.ifEmpty { "none" }}. " +
                "Language: ${LANGS[prefs.language] ?: prefs.language}. Style: ${prefs.voiceStyle}."
            "wake_word" -> {
                val w = value.lowercase(Locale.ROOT).filter { it.isLetter() }
                if (w.length < 2) throw Fail("Say one word to wake me with, like maya or aria.")
                prefs.wakePhrase = w
                refresh()
                "Done. Now \"${prefs.wakePhrase}\" or \"hey ${prefs.wakePhrase}\" wakes me. The offline model knows ordinary English words only, so if I don't wake up, Anuj should pick a more common word."
            }
            "stop_phrase" -> {
                prefs.stopCommand = value
                if (prefs.stopCommand.isEmpty()) "Okay, only the usual words like stop and thanks end a conversation now."
                else "Done. Saying \"${prefs.stopCommand}\" ends the conversation now."
            }
            "language" -> {
                val code = langCode(value) ?: throw Fail("I can listen and speak: ${LANGS.values.joinToString(", ")}.")
                prefs.language = code
                prefs.voiceName = ""
                refresh()
                "Language is now ${LANGS[code]}. For Bangla or Tamil the phone needs that language's voice installed (Settings, Language, Text-to-speech). Answer in the new language from now on."
            }
            "style" -> {
                val s = value.lowercase(Locale.ROOT)
                val hit = STYLES.keys.firstOrNull { it == s } ?: throw Fail("Styles: ${STYLES.keys.joinToString(", ")}.")
                prefs.voiceStyle = hit
                "Style is now $hit. Start talking like that right now: ${STYLES[hit]}"
            }
            else -> throw Fail("action must be get, wake_word, stop_phrase, language or style.")
        }
    }

    override fun tools(): List<BrainTool> = listOf(
        tool(
            "voice_settings",
            "Show or change how Myraa listens and sounds. wake_word: the one word that wakes you offline (\"wake word maya kar do\"). " +
                "stop_phrase: an extra phrase that ends a conversation, like \"bye bye\" (empty value removes it). " +
                "language: English (India/UK/US), Hindi, Tamil or Bangla (Bangladesh or India) for listening and speaking. " +
                "style: sweet (your usual), calm, friendly, professional or energetic. get shows the current values. " +
                "This changes only these four settings.",
            Risk.MEDIUM,
            schema(
                listOf("action"),
                "action" to prop("string", "", listOf("get", "wake_word", "stop_phrase", "language", "style")),
                "value" to prop("string", "The new wake word, stop phrase, language or style."),
            ),
        ) { change(it) },
    )

    companion object {
        val LANGS = linkedMapOf(
            "en-IN" to "English (India)", "en-GB" to "English (UK)", "en-US" to "English (US)", "hi-IN" to "Hindi",
            "ta-IN" to "Tamil", "bn-BD" to "Bangla (Bangladesh)", "bn-IN" to "Bangla (India)",
        )
        val STYLES = linkedMapOf(
            "sweet" to "your usual Myraa voice: sweet, calm, a little shy.",
            "calm" to "very calm and slow, soft words, reassuring, never rushed.",
            "friendly" to "warm and chatty like a close friend, a light joke is fine.",
            "professional" to "polite, precise and businesslike, no pet names, no jokes.",
            "energetic" to "upbeat, quick and enthusiastic, short punchy sentences.",
        )
    }
}
