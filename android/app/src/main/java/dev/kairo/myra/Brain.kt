package dev.kairo.myra

import java.io.File

/**
 * Where Myraa's thinking happens: the brain in Termux on this phone (BrainClient), or the laptop
 * through Supabase (RelayClient). Every callback runs on the main thread.
 */
interface Brain {
    interface Listener {
        /** A tool name ("pc_status") or a ready-made label ("Searching the web…"). */
        fun onTool(name: String)
        /** [speech] is the reply already spoken in Myraa's voice by the laptop, when it could. */
        fun onReply(text: String, images: List<File>, speech: Speech? = null)
        fun onError(message: String, brainDown: Boolean)
    }

    fun ask(text: String, listener: Listener)
    fun screenshot(listener: Listener)
    fun cancel()
    fun reset(done: (Boolean) -> Unit = {})
    fun health(done: (ok: Boolean, text: String) -> Unit)
}

/** Recorded speech (an mp3) and when each word starts in it, in ms, so the glow can blink in time. */
class Speech(val file: File, val wordsMs: IntArray)
