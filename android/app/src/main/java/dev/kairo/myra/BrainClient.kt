package dev.kairo.myra

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

/**
 * Talks to Myraa's brain, which runs inside this app (brain/LocalBrain.kt), at http://127.0.0.1:8765.
 * Answers arrive as newline-delimited JSON, so the screen can show what Myraa is doing.
 * Every callback runs on the main thread.
 */
class BrainClient(private val prefs: Prefs) : Brain {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var current: HttpURLConnection? = null
    // The AI engine screen gets its own thread, so a long answer never blocks Save or Test.
    private val aiIo = Executors.newSingleThreadExecutor()
    private val fileIo = Executors.newSingleThreadExecutor()

    private fun open(path: String, method: String, readTimeoutMs: Int): HttpURLConnection {
        val c = URL("http://${prefs.brainAddress}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 3000
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("x-myra-token", prefs.token)
        c.setRequestProperty("content-type", "application/json")
        return c
    }

    override fun ask(text: String, listener: Brain.Listener) {
        io.execute {
            var c: HttpURLConnection? = null
            try {
                c = open("/ask", "POST", 25 * 60_000)
                current = c
                c.doOutput = true
                c.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
                if (c.responseCode == 401) {
                    post { listener.onError("The brain inside the app refused the request. Close and open the Myraa app once and try again.", false) }
                    return@execute
                }
                if (c.responseCode != 200) {
                    post { listener.onError("The brain answered with error ${c.responseCode}.", false) }
                    return@execute
                }
                var done = false
                BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.isBlank()) continue
                        val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        when (ev.optString("type")) {
                            "tool" -> ev.optString("name").let { n -> post { listener.onTool(n) } }
                            "reply" -> { done = true; ev.optString("text").let { t -> post { listener.onReply(t, emptyList()) } } }
                            "error" -> { done = true; ev.optString("message").let { m -> post { listener.onError(m, false) } } }
                        }
                    }
                }
                if (!done && current === c) post { listener.onError("The brain stopped answering.", false) }
            } catch (e: ConnectException) {
                post { listener.onError("brain is not running", true) }
            } catch (e: SocketTimeoutException) {
                val connecting = e.message?.contains("connect", ignoreCase = true) == true
                post { listener.onError(if (connecting) "brain is not running" else "That took too long.", connecting) }
            } catch (e: Exception) {
                if (current === c) post { listener.onError(e.message ?: "Couldn't reach the brain.", e.message?.contains("ECONNREFUSED") == true) }
            } finally {
                if (current === c) current = null
                c?.disconnect()
            }
        }
    }

    /** Stop waiting for the current answer (the brain finishes it in the background). */
    override fun screenshot(listener: Brain.Listener) {
        post { listener.onError("Screenshots come from the laptop. Switch the brain to \"On my laptop\" in the app.", false) }
    }

    override fun cancel() {
        val c = current
        current = null
        if (c != null) io.execute { runCatching { c.disconnect() } }
    }

    override fun reset(done: (Boolean) -> Unit) {
        io.execute {
            val ok = runCatching { open("/reset", "POST", 5000).run { doOutput = true; outputStream.close(); val r = responseCode; disconnect(); r == 200 } }.getOrDefault(false)
            post { done(ok) }
        }
    }

    /** One line for the setup screen, e.g. "Connected · PC online". */
    override fun health(done: (ok: Boolean, text: String) -> Unit) {
        io.execute {
            val result = try {
                val c = open("/health", "GET", 5000)
                when (c.responseCode) {
                    200 -> {
                        val j = JSONObject(c.inputStream.bufferedReader().readText())
                        val pc = when (j.optString("pc")) { "online" -> "PC online"; "offline" -> "PC offline"; else -> "no PC paired" }
                        true to "Connected · ${j.optString("model")} · $pc"
                    }
                    401 -> false to "The brain inside the app refused the request. Close and open the Myraa app once."
                    else -> false to "Brain answered ${c.responseCode}"
                }.also { c.disconnect() }
            } catch (e: Exception) {
                false to "The brain inside the app isn't running yet. Open the Myraa app once."
            }
            post { done(result.first, result.second) }
        }
    }


    /** The AI engine screen: read, save or test which AI the brain uses. Callbacks: (answer, errorText). */
    fun aiGet(done: (JSONObject?, String?) -> Unit) = aiCall("/ai", "GET", null, done)
    fun aiSave(body: JSONObject, done: (JSONObject?, String?) -> Unit) = aiCall("/ai", "POST", body, done)
    /** The keys and accounts screen: what is set (never a secret), and saving new values. Callbacks: (answer, errorText). */
    fun keysGet(done: (JSONObject?, String?) -> Unit) = aiCall("/keys", "GET", null, done)
    fun keysSave(body: JSONObject, done: (JSONObject?, String?) -> Unit) = aiCall("/keys", "POST", body, done)
    fun aiTest(body: JSONObject, done: (JSONObject?, String?) -> Unit) = aiCall("/ai/test", "POST", body, done)

    private fun aiCall(path: String, method: String, body: JSONObject?, done: (JSONObject?, String?) -> Unit) {
        aiIo.execute {
            var out: JSONObject? = null
            var err: String? = null
            try {
                val c = open(path, method, 40_000)
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = c.responseCode
                if (code == 200) {
                    out = JSONObject(c.inputStream.bufferedReader().readText())
                } else if (code == 401) {
                    err = "The brain inside the app refused the request. Close and open the Myraa app once."
                } else {
                    val msg = runCatching { JSONObject(c.errorStream.bufferedReader().readText()).optString("error") }.getOrNull().orEmpty()
                    err = if (msg.isNotEmpty()) msg else "The brain answered with error $code."
                }
                c.disconnect()
            } catch (e: Exception) {
                err = "The brain inside the app isn't running yet. Open the Myraa app once."
            }
            post { done(out, err) }
        }
    }

    /** Sends a zip to the brain, which unpacks it for the zip tools. Callback: (answer, errorText). */
    fun uploadZip(name: String, input: java.io.InputStream, done: (JSONObject?, String?) -> Unit) {
        fileIo.execute {
            var out: JSONObject? = null
            var err: String? = null
            try {
                val c = URL("http://${prefs.brainAddress}/upload?name=" + java.net.URLEncoder.encode(name, "UTF-8")).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 3000
                c.readTimeout = 120_000
                c.setRequestProperty("x-myra-token", prefs.token)
                c.setRequestProperty("content-type", "application/zip")
                c.doOutput = true
                c.setChunkedStreamingMode(64 * 1024)
                input.use { src -> c.outputStream.use { dst -> src.copyTo(dst) } }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                val j = runCatching { JSONObject(text) }.getOrNull()
                if (code == 200 && j != null) {
                    out = j
                } else if (code == 401) {
                    err = "The brain inside the app refused the request. Close and open the Myraa app once."
                } else {
                    val msg = j?.optString("error").orEmpty()
                    err = if (msg.isNotEmpty()) msg else "The brain answered with error $code."
                }
                c.disconnect()
            } catch (e: ConnectException) {
                err = "The brain inside the app isn't running yet. Open the Myraa app once."
            } catch (e: Exception) {
                err = e.message ?: "Couldn't send the zip."
            }
            post { done(out, err) }
        }
    }

    /**
     * Sends any other file to the brain (brain/attachments.mjs): it reads Word, Excel, PowerPoint, OpenDocument, EPUB,
     * RTF, HTML and notebooks, saves pictures for the AI to look at, and saves whatever it can't read.
     * Blocks, so call it from a background thread. Throws with a message the user can read.
     */
    fun attachBlocking(name: String, contentType: String, bytes: ByteArray): JSONObject {
        val c = URL("http://${prefs.brainAddress}/attach?name=" + java.net.URLEncoder.encode(name, "UTF-8")).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 3000
            c.readTimeout = 120_000
            c.setRequestProperty("x-myra-token", prefs.token)
            c.setRequestProperty("content-type", contentType)
            c.doOutput = true
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            val j = runCatching { JSONObject(text) }.getOrNull()
            if (code == 200 && j != null) return j
            if (code == 401) throw Exception("The brain inside the app refused the request. Close and open the Myraa app once.")
            throw Exception(j?.optString("error").orEmpty().ifEmpty { "The brain answered with error $code." })
        } catch (e: ConnectException) {
            throw Exception("The brain inside the app isn't running yet. Open the Myraa app once.")
        } catch (e: java.io.IOException) {
            throw Exception(e.message ?: "Couldn't reach the brain.")
        } finally {
            c.disconnect()
        }
    }

    private fun post(r: () -> Unit) = main.post(r)
}
