package dev.kairo.myra

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * One turn of listening with Android's speech recognizer (the Google app on most phones).
 * Reports loudness for the glow, partial words for the captions, and the final text.
 * Use only from the main thread.
 */
class Listener(private val context: Context, private val prefs: Prefs) {
    interface Callback {
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onText(text: String)
        /** Nothing was said, or recognition failed. [message] is set only for real problems. */
        fun onNothing(message: String?)
    }

    private var sr: SpeechRecognizer? = null
    private var cb: Callback? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(callback: Callback) {
        stop()
        cb = callback
        if (!available) { finishNothing("Speech recognition isn't available on this phone. Install or update the Google app."); return }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { cb?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> null
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need the microphone permission. Open the Myraa app to allow it."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs the internet right now."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy."
                    else -> null
                }
                finishNothing(msg)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                val c = cb
                stop()
                if (text.isEmpty()) c?.onNothing(null) else c?.onText(text)
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) cb?.onPartial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Give a slow thinker a moment before deciding they've finished.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        runCatching { r.startListening(intent) }.onFailure { finishNothing("Couldn't start listening: ${it.message}") }
    }

    private fun finishNothing(msg: String?) {
        val c = cb
        stop()
        c?.onNothing(msg)
    }

    fun stop() {
        cb = null
        sr?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        sr = null
    }
}
