package dev.kairo.myra

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Reaches Myraa on the laptop from anywhere, through your Supabase project (pc/worker.mjs is
 * the other end). Only the database functions in supabase/relay.sql are used, and every call
 * carries the shared secret.
 */
class RelayClient(context: Context, private val prefs: Prefs) : Brain {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val shots = File(context.cacheDir, "shots")
    private val voice = File(context.cacheDir, "voice")
    @Volatile private var waiting = 0 // bumps on cancel, so an old poll loop stops

    private fun rpc(fn: String, args: JSONObject, timeoutMs: Int = 15_000): String {
        val c = URL("${prefs.relayUrl}/rest/v1/rpc/$fn").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.setRequestProperty("apikey", prefs.relayKey)
            if (prefs.relayKey.startsWith("eyJ")) c.setRequestProperty("Authorization", "Bearer ${prefs.relayKey}")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(args.put("secret", prefs.relaySecret).toString().toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw IllegalStateException("Supabase answered $code: ${body.take(160)}")
            return body
        } finally {
            c.disconnect()
        }
    }

    private fun paired() = prefs.relayUrl.isNotEmpty() && prefs.relayKey.isNotEmpty() && prefs.relaySecret.length >= 32

    /** Is the laptop worker checking in? Returns null if it is, or what to say if not. */
    private fun laptopProblem(): String? {
        val rows = JSONArray(rpc("myra_status", JSONObject()))
        val row = rows.optJSONObject(0) ?: return "The laptop has never connected. Run Myraa on the laptop first."
        return if (row.optBoolean("online")) null else "Your laptop isn't connected right now. Make sure it's on and Myraa is running on it."
    }

    override fun ask(text: String, listener: Brain.Listener) = submit("ask", text, listener)

    override fun screenshot(listener: Brain.Listener) = submit("screenshot", "", listener)

    private fun submit(kind: String, input: String, listener: Brain.Listener) {
        val my = ++waiting
        io.execute {
            try {
                if (!paired()) { post { listener.onError("Myraa isn't paired with your laptop yet. Open the app and scan the QR code from the laptop.", false) }; return@execute }
                laptopProblem()?.let { msg -> post { if (my == waiting) listener.onError(msg, false) }; return@execute }
                val id = JSONObject().put("p_kind", kind).put("p_input", input).let { rpc("myra_submit", it).trim().trim('"') }
                var lastProgress = ""
                val deadline = System.currentTimeMillis() + 25 * 60_000
                while (my == waiting) {
                    Thread.sleep(700)
                    if (my != waiting) return@execute
                    val row = runCatching { JSONArray(rpc("myra_get", JSONObject().put("job", id))).optJSONObject(0) }.getOrNull() ?: continue
                    when (row.optString("status")) {
                        "working", "pending" -> {
                            val p = row.optString("progress").takeIf { it.isNotBlank() && it != "null" }
                            if (p != null && p != lastProgress) { lastProgress = p; post { if (my == waiting) listener.onTool(p) } }
                        }
                        "done" -> {
                            val out = row.optJSONObject("output") ?: JSONObject()
                            val files = saveImages(out.optJSONArray("images"))
                            val speech = saveSpeech(out.optJSONObject("voice"))
                            val reply = out.optString("text", "Done.")
                            post { if (my == waiting) listener.onReply(reply, files, speech) }
                            return@execute
                        }
                        "error" -> {
                            val msg = row.optString("progress").ifBlank { "The laptop couldn't do that." }
                            post { if (my == waiting) listener.onError(msg, false) }
                            return@execute
                        }
                    }
                    if (System.currentTimeMillis() > deadline) { post { if (my == waiting) listener.onError("The laptop took too long.", false) }; return@execute }
                }
            } catch (e: Exception) {
                post { if (my == waiting) listener.onError("I couldn't reach the laptop. Check your internet connection.", false) }
            }
        }
    }

    private fun saveImages(arr: JSONArray?): List<File> {
        if (arr == null || arr.length() == 0) return emptyList()
        shots.mkdirs()
        // Keep only the latest few on the phone.
        shots.listFiles()?.sortedBy { it.lastModified() }?.dropLast(9)?.forEach { it.delete() }
        val out = mutableListOf<File>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val bytes = runCatching { Base64.decode(o.optString("data"), Base64.DEFAULT) }.getOrNull() ?: continue
            val f = File(shots, "laptop-${System.currentTimeMillis()}-$i.jpg")
            f.writeBytes(bytes)
            out += f
        }
        return out
    }

    /** The laptop's recording of the reply in Myraa's voice, if it sent one. */
    private fun saveSpeech(v: JSONObject?): Speech? {
        if (v == null) return null
        return runCatching {
            val bytes = Base64.decode(v.optString("data"), Base64.DEFAULT)
            if (bytes.isEmpty()) return null
            voice.mkdirs()
            voice.listFiles()?.forEach { it.delete() }
            val f = File(voice, "reply-${System.currentTimeMillis()}.mp3")
            f.writeBytes(bytes)
            val w = v.optJSONArray("words") ?: JSONArray()
            Speech(f, IntArray(w.length()) { w.optInt(it) })
        }.getOrNull()
    }

    override fun cancel() { waiting++ }

    override fun reset(done: (Boolean) -> Unit) {
        io.execute {
            val ok = runCatching { rpc("myra_submit", JSONObject().put("p_kind", "reset").put("p_input", "")); true }.getOrDefault(false)
            post { done(ok) }
        }
    }

    override fun health(done: (ok: Boolean, text: String) -> Unit) {
        io.execute {
            val r = if (!paired()) false to "Not paired. Scan the QR code from the laptop (SETUP-MYRA-LAPTOP)."
            else runCatching {
                val row = JSONArray(rpc("myra_status", JSONObject())).optJSONObject(0)
                when {
                    row == null -> false to "Paired, but the laptop has never connected."
                    row.optBoolean("online") -> true to "Laptop online · ${row.optString("host")}"
                    else -> false to "Laptop offline (last seen ${row.optString("last_seen").take(16).replace('T', ' ')} UTC)"
                }
            }.getOrElse { false to "Can't reach Supabase: ${it.message}" }
            post { done(r.first, r.second) }
        }
    }

    /** What the home screen shows about the laptop. [problem] is set when Supabase can't be reached. */
    class Laptop(val paired: Boolean, val online: Boolean, val host: String, val lastSeen: String, val problem: String?)

    fun laptop(done: (Laptop) -> Unit) {
        io.execute {
            val s = if (!paired()) Laptop(false, false, "", "", null)
            else runCatching {
                val row = JSONArray(rpc("myra_status", JSONObject())).optJSONObject(0)
                fun field(name: String) = if (row == null || row.isNull(name)) "" else row.optString(name)
                Laptop(true, row?.optBoolean("online") == true, field("host"), field("last_seen"), null)
            }.getOrElse { Laptop(true, false, "", "", it.message ?: "no internet?") }
            post { done(s) }
        }
    }

    private fun post(r: () -> Unit) = main.post(r)

    companion object {
        /** Reads "myra://pair?u=…&k=…&s=…" (from the QR code). Returns (url, key, secret) or null. */
        fun parsePairing(text: String): Triple<String, String, String>? {
            val s = text.trim()
            val q = s.substringAfter("myra://pair?", "").ifEmpty { return null }
            val map = q.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") } }.toMap()
            val u = map["u"] ?: return null
            val k = map["k"] ?: return null
            val sec = map["s"] ?: return null
            if (!u.matches(Regex("^https://[a-z0-9-]+\\.supabase\\.co$")) || sec.length < 32 || k.isBlank()) return null
            return Triple(u, k, sec)
        }
    }
}
