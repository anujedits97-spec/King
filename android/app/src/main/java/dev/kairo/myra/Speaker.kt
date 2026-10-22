package dev.kairo.myra

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Speaks Myraa's replies and reports every word as it is spoken, so the glow can pulse with it.
 *
 * The real Myraa voice is a recording: the laptop sends each reply already spoken (Microsoft's
 * British "Ryan" voice), and a few things the phone says by itself ship with the app
 * (VoiceClips). Anything else uses the phone's own text-to-speech, set to a British male voice.
 * Choosing a phone voice in the app turns the recordings off.
 */
class Speaker(context: Context, private val prefs: Prefs) {
    interface Listener {
        fun onWord()
        fun onDone()
    }

    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: Pair<String, Listener>? = null
    private var listener: Listener? = null
    private var lastId = ""
    private var serial = 0
    private var player: MediaPlayer? = null
    private var tick: Runnable? = null

    private val tts: TextToSpeech = TextToSpeech(ctx) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            if (ready) applySettings()
            pending?.let { (t, l) -> pending = null; speak(t, l) }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onRangeStart(id: String, start: Int, end: Int, frame: Int) { main.post { listener?.onWord() } }
            override fun onDone(id: String) { main.post { if (id == lastId) finish() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { main.post { if (id == lastId) finish() } }
            override fun onError(id: String, errorCode: Int) { main.post { if (id == lastId) finish() } }
            override fun onStop(id: String, interrupted: Boolean) {}
        })
    }

    /** True while the Myraa voice is chosen (the default), rather than one of the phone's voices. */
    // The Myraa voice (recorded in English or Hindi) only fits those languages. For Bangla, Tamil... the phone's own voice for that language speaks.
    private val myraVoice: Boolean get() = prefs.voiceName.isEmpty() && prefs.language.substringBefore('-') in setOf("en", "hi")

    fun applySettings() {
        if (!ready) return
        val lang = prefs.language.split('-')
        val locale = if (lang.size > 1) Locale(lang[0], lang[1]) else Locale(lang[0])
        if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
        else tts.language = Locale.UK
        val all = runCatching { tts.voices }.getOrNull().orEmpty()
        val pick = if (myraVoice) hindiFemale(all) ?: britishMale(all) else all.firstOrNull { it.name == prefs.voiceName }
        if (pick != null) runCatching { tts.voice = pick }
                tts.setPitch(1.3f) // Myraa: sweet, light voice (+30% pitch)
        tts.setSpeechRate(prefs.speechRate / 100f)
    }

    /** Myraa's own voice: an installed Hindi (or Indian English) female voice. Google marks these "hia"/"hic" (hi-IN) and "ena" (en-IN). */
    private fun hindiFemale(all: Collection<Voice>): Voice? {
        val ok = all.filter {
            !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }
        val hi = ok.filter { it.locale.language == "hi" && it.locale.country == "IN" }
        for (id in listOf("hia", "hic")) hi.firstOrNull { it.name.contains("-x-$id") }?.let { return it }
        hi.firstOrNull()?.let { return it }
        val en = ok.filter { it.locale.language == "en" && it.locale.country == "IN" }
        for (id in listOf("ena", "enc")) en.firstOrNull { it.name.contains("-x-$id") }?.let { return it }
        return en.firstOrNull()
    }

    /** Google's British male voices first, then any installed British voice. */
    private fun britishMale(all: Collection<Voice>): Voice? {
        val gb = all.filter {
            it.locale.language == "en" && it.locale.country == "GB" && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }
        for (id in listOf("x-rjs", "x-gbd", "x-gbb")) gb.firstOrNull { it.name.contains(id) }?.let { return it }
        return gb.firstOrNull()
    }

    /** English voices installed on this phone, best quality first. */
    fun voices(): List<Voice> = runCatching {
        tts.voices.orEmpty().filter { it.locale.language in setOf("en", "hi", "bn", "ta") && !it.isNetworkConnectionRequired }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.locale.toString() }.thenBy { it.name })
    }.getOrDefault(emptyList())

    /** Says [text]: the laptop's recording [speech] when there is one, else a bundled clip, else text-to-speech. */
    fun speak(text: String, l: Listener, speech: Speech? = null) {
        stop()
        if (myraVoice) {
            if (speech != null && speech.file.exists() && play(text, l) { it.setDataSource(speech.file.absolutePath); speech.wordsMs }) return
            VoiceClips.forText(clean(text))?.let { clip ->
                if (play(text, l) { mp -> ctx.resources.openRawResourceFd(clip.res).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }; clip.words }) return
            }
        }
        speakTts(text, l)
    }

    /** The phone's own text-to-speech. */
    private fun speakTts(text: String, l: Listener) {
        if (!ready) { listener = l; pending = text to l; return }
        listener = l
        val chunks = chunk(clean(text))
        if (chunks.isEmpty()) { finish(); return }
        serial++
        chunks.forEachIndexed { i, c ->
            val id = "u$serial-$i"
            if (i == chunks.lastIndex) lastId = id
            tts.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
        }
    }

    /**
     * Plays a recording of [text]; [load] sets the source and returns when each word starts (ms).
     * False if it can't start. If it breaks while playing, the phone's own voice says [text] instead.
     */
    private fun play(text: String, l: Listener, load: (MediaPlayer) -> IntArray): Boolean {
        val mp = MediaPlayer()
        val words: IntArray
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            words = load(mp)
            mp.prepare()
            val speed = prefs.speechRate / 100f
            if (speed != 1f) runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
        } catch (e: Exception) {
            mp.release()
            return false
        }
        listener = l
        player = mp
        mp.setOnCompletionListener { if (player === mp) { release(); finish() } }
        mp.setOnErrorListener { _, _, _ -> if (player === mp) { release(); speakTts(text, l) }; true }
        mp.start()
        // Blink on each word, following the playback position (so a slow start stays in sync).
        var next = 0
        val t = object : Runnable {
            override fun run() {
                if (player !== mp) return
                val pos = runCatching { mp.currentPosition }.getOrDefault(0)
                while (next < words.size && pos >= words[next]) { next++; listener?.onWord() }
                main.postDelayed(this, 30)
            }
        }
        tick = t
        main.post(t)
        return true
    }

    private fun release() {
        tick?.let { main.removeCallbacks(it) }
        tick = null
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
    }

    fun stop() {
        listener = null
        lastId = ""
        pending = null
        release()
        if (ready) tts.stop()
    }

    val speaking: Boolean get() = listener != null

    private fun finish() {
        val l = listener
        listener = null
        l?.onDone()
    }

    fun shutdown() { stop(); tts.shutdown() }

    companion object {
        /** Markdown and links read badly aloud: strip them. */
        fun clean(s: String): String = s
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("https?://\\S+"), "a link")
            .replace(Regex("[*_#`>|~]"), "")
            .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        /** TTS engines cap one utterance at about 4000 characters: split at sentence ends. */
        fun chunk(s: String, max: Int = 3000): List<String> {
            if (s.isEmpty()) return emptyList()
            val out = mutableListOf<String>()
            var rest = s
            while (rest.length > max) {
                val cut = rest.lastIndexOf(". ", max).takeIf { it > max / 2 }?.plus(1) ?: max
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out += rest
            return out
        }
    }
}
