package dev.kairo.myra.brain

import android.util.Log
import dev.kairo.myra.Fail
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The brain's HTTP face on 127.0.0.1:8765, speaking exactly what the Termux brain spoke, so the app's
 * voice screen, AI engine screen, keys screen and attach buttons keep working unchanged.
 * Only this phone can reach it, and every request needs the secret token.
 */
class BrainServer(private val brain: LocalBrain, private val port: Int) {
    @Volatile private var running = false
    private var server: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "myra-brain", isDaemon = true) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                server = s
                while (running) {
                    val c = try { s.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) { serve(c) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "brain server stopped: ${e.message}")
            }
            running = false
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    val isRunning get() = running

    /* ── HTTP plumbing ───────────────────────────────────────────────────── */

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: InputStream)

    private fun serve(c: Socket) {
        c.use {
            try {
                c.soTimeout = 60_000
                val inp = BufferedInputStream(c.getInputStream())
                val out = c.getOutputStream()
                val head = readHead(inp) ?: return
                val lines = head.split("\r\n")
                val first = lines[0].split(" ")
                if (first.size < 2) return respond(out, 400, JSONObject().put("error", "Bad request"))
                val rawPath = first[1]
                val path = rawPath.substringBefore('?')
                val query = HashMap<String, String>()
                rawPath.substringAfter('?', "").split('&').filter { it.contains('=') }.forEach {
                    query[URLDecoder.decode(it.substringBefore('='), "UTF-8")] = URLDecoder.decode(it.substringAfter('='), "UTF-8")
                }
                val headers = HashMap<String, String>()
                for (l in lines.drop(1)) {
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase(Locale.ROOT)] = l.substring(i + 1).trim()
                }
                if (!tokenOk(headers["x-myra-token"] ?: "")) {
                    return respond(out, 401, JSONObject().put("error", "Wrong or missing token."))
                }
                val body: InputStream = when {
                    headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> ChunkedInput(inp)
                    else -> LimitedInput(inp, headers["content-length"]?.toLongOrNull() ?: 0L)
                }
                c.soTimeout = 0 // a long answer or a big upload may take a while
                route(Req(first[0], path, query, headers, body), out)
            } catch (e: Exception) {
                runCatching { respond(c.getOutputStream(), 500, JSONObject().put("error", e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    private fun tokenOk(t: String): Boolean {
        val want = brain.token()
        return want.isNotEmpty() && MessageDigest.isEqual(t.toByteArray(Charsets.UTF_8), want.toByteArray(Charsets.UTF_8))
    }

    private fun readHead(inp: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (buf.size() < 16_384) {
            val b = inp.read()
            if (b < 0) return null
            buf.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) return buf.toString("UTF-8").trimEnd()
        }
        return null
    }

    private fun respond(out: OutputStream, status: Int, body: JSONObject) {
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 413 -> "Payload Too Large"; 500 -> "Internal Server Error"; 502 -> "Bad Gateway"; else -> "OK" }
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun readJson(r: Req): JSONObject {
        val bytes = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = r.body.read(buf)
            if (n < 0) break
            bytes.write(buf, 0, n)
            if (bytes.size() > 64 * 1024) throw Fail("That request is too big.")
        }
        val s = bytes.toString("UTF-8")
        return if (s.isBlank()) JSONObject() else try { JSONObject(s) } catch (e: Exception) { throw Fail("Bad JSON") }
    }

    /* ── routes (same as brain.mjs) ──────────────────────────────────────── */

    private fun route(r: Req, out: OutputStream) {
        try {
            when {
                r.method == "GET" && r.path == "/health" -> respond(out, 200, brain.health())
                r.path == "/keys" && r.method == "GET" -> respond(out, 200, brain.keysView())
                r.path == "/keys" && r.method == "POST" -> {
                    val res = brain.keysSave(readJson(r))
                    if (res.first) respond(out, 200, res.second) else respond(out, 400, JSONObject().put("ok", false).put("error", res.third))
                }
                r.path == "/ai" && r.method == "GET" -> respond(out, 200, brain.aiView())
                r.path == "/ai" && r.method == "POST" -> {
                    val res = brain.aiSave(readJson(r))
                    if (res.first) respond(out, 200, res.second) else respond(out, 400, JSONObject().put("ok", false).put("error", res.third))
                }
                r.path == "/ai/test" && r.method == "POST" -> respond(out, 200, brain.aiTest(readJson(r)))
                r.path == "/upload" && r.method == "POST" -> {
                    try {
                        brain.env.guard.check("zip_upload")
                        respond(out, 200, brain.ingestZip(r.query["name"] ?: "upload.zip", r.body).put("ok", true))
                    } catch (e: Exception) { respond(out, 400, JSONObject().put("ok", false).put("error", e.message ?: "Upload failed.")) }
                }
                r.path == "/attach" && r.method == "POST" -> {
                    try {
                        brain.env.guard.check("attach_upload")
                        respond(out, 200, brain.ingestAttachment(r.query["name"] ?: "file", r.body).put("ok", true))
                    } catch (e: Exception) { respond(out, 400, JSONObject().put("ok", false).put("error", e.message ?: "Upload failed.")) }
                }
                r.path == "/stop" && r.method == "POST" -> { brain.env.guard.stop("http") { brain.env.onStop() }; respond(out, 200, JSONObject().put("ok", true).put("stopped", true)) }
                r.path == "/resume" && r.method == "POST" -> { brain.env.guard.resume(); respond(out, 200, JSONObject().put("ok", true).put("stopped", false)) }
                r.path == "/reset" && r.method == "POST" -> { brain.reset(); respond(out, 200, JSONObject().put("ok", true)) }
                r.path == "/reply" && r.method == "POST" -> {
                    try { respond(out, 200, brain.smartReply(readJson(r))) }
                    catch (e: BrainHttpError) { respond(out, e.status, JSONObject().put("error", e.message)) }
                    catch (e: Exception) { respond(out, 502, JSONObject().put("error", e.message ?: "Reply failed.")) }
                }
                r.path == "/ask" && r.method == "POST" -> ask(readJson(r), out)
                else -> respond(out, 404, JSONObject().put("error", "Not found"))
            }
        } catch (e: IOException) {
            // the app went away; nothing to tell
        } catch (e: Exception) {
            runCatching { respond(out, 500, JSONObject().put("error", e.message ?: e.javaClass.simpleName)) }
        }
    }

    /** Newline-delimited JSON, so the app can show what Myraa is doing while it works. */
    private fun ask(body: JSONObject, out: OutputStream) {
        val q = body.optString("text").trim()
        if (q.isEmpty()) return respond(out, 400, JSONObject().put("error", "No text"))
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        var gone = false
        fun send(o: JSONObject) {
            if (gone) return
            try { out.write((o.toString() + "\n").toByteArray(Charsets.UTF_8)); out.flush() }
            catch (e: IOException) { gone = true; brain.cancelCurrent() }
        }
        send(JSONObject().put("type", "thinking"))
        try {
            val reply = brain.ask(q, unattended = false) { name -> send(JSONObject().put("type", "tool").put("name", name)) }
            send(JSONObject().put("type", "reply").put("text", reply))
        } catch (e: Exception) {
            send(JSONObject().put("type", "error").put("message", e.message ?: e.javaClass.simpleName))
        }
    }

    /* ── request bodies ──────────────────────────────────────────────────── */

    private class LimitedInput(private val src: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val b = src.read(); if (b >= 0) left--; return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    private class ChunkedInput(private val src: InputStream) : InputStream() {
        private var left = 0L
        private var done = false

        private fun line(): String {
            val sb = StringBuilder()
            while (true) {
                val b = src.read()
                if (b < 0) return sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }

        private fun next(): Boolean {
            if (done) return false
            if (left == 0L) {
                var l = line()
                if (l.isEmpty()) l = line() // the CRLF after the previous chunk
                left = l.substringBefore(';').trim().toLongOrNull(16) ?: 0L
                if (left == 0L) { done = true; return false }
            }
            return true
        }

        override fun read(): Int {
            if (!next()) return -1
            val b = src.read()
            if (b >= 0) left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!next()) return -1
            val n = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    companion object { private const val TAG = "MyraBrainServer" }
}
