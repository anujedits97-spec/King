package dev.kairo.myra

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Listens for the wake word ("Myra", or the word you chose) with Vosk, fully offline: the sound never leaves the phone.
 * The Indian-English model (36 MB, Apache 2.0, from alphacephei.com) downloads once, when you
 * switch the wake word on. It only has to tell "myra" from everything else, so it's told
 * to expect just that word (and the words it sounds like).
 */
class WakeWord(
    private val context: Context,
    private val onWake: () -> Unit,
    private val onSleep: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val dir = File(context.filesDir, MODEL_NAME)
    private val readyMark = File(dir, ".ready")
    @Volatile private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var wanted = false
    private var lastWake = 0L
    // After "bye myra" only "hey myra" or "okay myra" wakes it. Lives in memory: a restart wakes it again.
    @Volatile private var sleeping = false
    // The word that wakes Myraa ("myra" unless changed in Settings or by voice), as it was when the mic was opened.
    @Volatile private var word = "myra"

    val modelReady: Boolean get() = readyMark.exists()
    val listening: Boolean get() = service != null

    /** Start listening (loading the model first if needed). Safe to call repeatedly. */
    fun start() {
        wanted = true
        if (!modelReady || service != null) return
        val m = model
        if (m != null) { open(m); return }
        Thread {
            runCatching {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                Model(dir.absolutePath)
            }.onSuccess { loaded -> model = loaded; main.post { if (wanted && service == null) open(loaded) } }
             .onFailure { Log.e(TAG, "model load failed", it) }
        }.start()
    }

    private fun open(m: Model) {
        try {
            word = Prefs(context).wakePhrase
            val r = Recognizer(m, 16000f, grammarFor(namesFor(word)))
            val s = SpeechService(r, 16000f)
            recognizer = r
            service = s
            s.startListening(listener)
        } catch (e: Exception) {
            // The microphone may be busy (a call, another app). Try again shortly.
            Log.w(TAG, "can't open mic: ${e.message}")
            close()
            main.postDelayed({ if (wanted) start() }, 3000)
        }
    }

    /** Call after the wake word was changed: starts listening for the new word right away. */
    fun refreshWord() {
        if (service != null && word != Prefs(context).wakePhrase) {
            close()
            start()
        }
    }

    /** Let go of the microphone so the main speech recognizer can use it. */
    fun pause() {
        wanted = false
        close()
    }

    private fun close() {
        service?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        service = null
        recognizer?.let { runCatching { it.close() } }
        recognizer = null
    }

    fun release() {
        pause()
        model?.let { runCatching { it.close() } }
        model = null
    }

    private fun heard(json: String?, key: String) {
        val text = runCatching { JSONObject(json ?: "{}").optString(key) }.getOrDefault("")
        val now = System.currentTimeMillis()
        if (now - lastWake < 2500) return
        val names = namesFor(word)
        if (names.any { text.contains("bye $it") || text.contains("goodbye $it") }) {
            if (sleeping) return
            sleeping = true
            lastWake = now
            main.post { onSleep() }
            return
        }
        if (names.none { text.contains(it) }) return
        if (sleeping && !names.any { text.contains("hey $it") || text.contains("okay $it") || text.contains("bolo $it") }) return
        sleeping = false
        lastWake = now
        main.post { onWake() }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = heard(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = heard(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {
            Log.w(TAG, "wake word error: ${exception?.message}")
            main.post { close(); if (wanted) main.postDelayed({ start() }, 2000) }
        }
        override fun onTimeout() { main.post { close(); if (wanted) start() } }
    }

    /** Download and unpack the model. Calls back on the main thread. */
    fun download(progress: (Int) -> Unit, done: (ok: Boolean, error: String?) -> Unit) {
        Thread {
            val tmp = File(context.cacheDir, "$MODEL_NAME.zip")
            try {
                val c = URL(MODEL_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            val pct = if (total > 0) (got * 100 / total).toInt() else -1
                            if (pct != lastPct) { lastPct = pct; main.post { progress(pct) } }
                        }
                    }
                }
                c.disconnect()
                dir.deleteRecursively()
                dir.mkdirs()
                ZipInputStream(tmp.inputStream().buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        // Drop the zip's top folder, and refuse anything that would land outside ours.
                        val rel = e.name.substringAfter('/', "")
                        if (rel.isEmpty()) continue
                        val f = File(dir, rel)
                        if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) continue
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); FileOutputStream(f).use { zip.copyTo(it) } }
                    }
                }
                if (!File(dir, "am/final.mdl").exists()) throw IllegalStateException("The download wasn't a speech model.")
                readyMark.writeText(MODEL_NAME)
                main.post { done(true, null) }
            } catch (e: Exception) {
                dir.deleteRecursively()
                main.post { done(false, e.message ?: e.javaClass.simpleName) }
            } finally {
                tmp.delete()
            }
        }.start()
    }

    companion object {
        private const val TAG = "MyraWake"
        const val MODEL_NAME = "vosk-model-small-en-in-0.4"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        const val MODEL_MB = 36
        /**
         * The speech model only knows ordinary English words, and "Myra" may not be one of them, so it also listens for
         * the words it sounds like. Any of them wakes Myraa. A name you pick yourself is listened for as it is.
         */
        private fun namesFor(w: String): List<String> = if (w == "myra") listOf("myra", "mira", "maira", "meera", "myraa") else listOf(w)
        private fun grammarFor(names: List<String>): String =
            JSONArray(names.flatMap { listOf(it, "hey $it", "okay $it", "bolo $it", "bye $it", "goodbye $it") } + "[unk]").toString()
    }
}
