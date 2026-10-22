package dev.kairo.myra.brain.mark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.kairo.myra.Fail
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Three things code_project and the project tools were missing for websites:
 *  1. [staticCheck]: missing linked files, cut-off HTML, broken JSON, getElementById ids that don't exist.
 *  2. [runPages]: opens every HTML page in a real (invisible) Android WebView and collects JavaScript errors,
 *     files that fail to load, and blank pages. This is a real run, but it can't judge looks or whether a
 *     button does the right thing, and ES-module scripts can't load from file:// so those errors are ignored.
 *  3. [deploy]: publishes a static site (index.html at the top) to Netlify as a zip. Needs the user's own token.
 */
object SiteKit {
    private val SECRET = Regex("(\\.env($|\\.)|\\.pem$|\\.key$|id_rsa|credential|secret|token|password)", RegexOption.IGNORE_CASE)
    private val SKIP_DIRS = setOf(".history", ".trash", ".git", "node_modules")
    private val TEXT_EXT = setOf("html", "htm", "css", "js", "mjs", "json", "txt", "md", "svg", "xml")
    private val REMOTE = Regex("^(https?:|//|mailto:|tel:|data:|javascript:|blob:)", RegexOption.IGNORE_CASE)

    fun files(dir: File): List<File> =
        dir.walkTopDown().onEnter { it == dir || it.name !in SKIP_DIRS }.filter { it.isFile && !it.name.startsWith(".") }.toList()

    private fun rel(dir: File, f: File) = f.relativeTo(dir).path.replace('\\', '/')

    /* ── 1. static checks ─────────────────────────────────────────────────── */

    fun staticCheck(dir: File): List<String> {
        val out = ArrayList<String>()
        val all = files(dir)
        if (all.isEmpty()) return listOf("The project has no files.")
        val texts = HashMap<File, String>()
        for (f in all) if (f.length() < 600_000 && f.extension.lowercase() in TEXT_EXT) texts[f] = runCatching { f.readText() }.getOrDefault("")
        val allText = texts.values.joinToString("\n")

        for ((f, t) in texts) {
            val ext = f.extension.lowercase()
            if (t.trim().length < 10) out.add("${rel(dir, f)} is empty or almost empty.")
            if (ext == "html" || ext == "htm") {
                if (!t.contains("</html>", ignoreCase = true)) out.add("${rel(dir, f)} has no closing </html>, so it looks cut off.")
                Regex("""(?:src|href)\s*=\s*["']([^"'#?]+)""", RegexOption.IGNORE_CASE).findAll(t).forEach { m ->
                    val ref = m.groupValues[1].trim()
                    if (ref.isEmpty() || REMOTE.containsMatchIn(ref)) return@forEach
                    val target = if (ref.startsWith("/")) File(dir, ref.removePrefix("/")) else File(f.parentFile, ref)
                    if (!target.exists()) out.add("${rel(dir, f)} points to \"$ref\", which does not exist.")
                }
            }
            if (ext == "css") {
                Regex("""url\(\s*["']?([^)"'#?]+)""", RegexOption.IGNORE_CASE).findAll(t).forEach { m ->
                    val ref = m.groupValues[1].trim()
                    if (ref.isEmpty() || REMOTE.containsMatchIn(ref)) return@forEach
                    val target = if (ref.startsWith("/")) File(dir, ref.removePrefix("/")) else File(f.parentFile, ref)
                    if (!target.exists()) out.add("${rel(dir, f)} uses \"$ref\", which does not exist.")
                }
            }
            if (ext == "json") {
                runCatching { val s = t.trim(); if (s.startsWith("[")) JSONArray(s) else JSONObject(s) }
                    .onFailure { out.add("${rel(dir, f)} is not valid JSON.") }
            }
        }

        val codeText = texts.filter { it.key.extension.lowercase() in setOf("js", "mjs", "html", "htm") }.values.joinToString("\n")
        val used = Regex("""getElementById\(\s*["']([^"']+)["']\s*\)""").findAll(codeText).map { it.groupValues[1] }.toSet()
        for (id in used) {
            val exists = Regex("""id\s*[=:]\s*\\?["']${Regex.escape(id)}\\?["']""").containsMatchIn(allText)
            if (!exists) out.add("The code uses getElementById(\"$id\") but no element has that id.")
        }
        return out.distinct().take(25)
    }

    /* ── 2. real run in a WebView ─────────────────────────────────────────── */

    /** One tiny script per test step. It returns "ok" or a short reason. */
    private const val STEP_JS = """(function(s){try{
var q=function(x){return document.querySelector(x)};var e=s.sel?q(s.sel):null;
if(s.do==='click'){if(!e)return 'no element '+s.sel;e.click();return 'ok'}
if(s.do==='type'||s.do==='select'){if(!e)return 'no element '+s.sel;e.focus();e.value=s.text;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'ok'}
if(s.do==='expect_text'){if(!e)return 'no element '+s.sel;return ((e.innerText||e.value||'').indexOf(s.text)>=0)?'ok':'text not found in '+s.sel}
if(s.do==='expect_exists'){return e?'ok':'no element '+s.sel}
if(s.do==='expect_hidden'){return (!e||e.offsetParent===null)?'ok':s.sel+' is still visible'}
return 'unknown step '+s.do}catch(err){return 'error: '+err.message}})(__STEP__)"""

    /** Opens up to 6 HTML pages one by one. [steps] (clicks, typing, checks) are run on index.html. Not on the main thread. */
    fun runPages(ctx: Context, dir: File, waitMs: Long = 3500, steps: JSONArray? = null): List<String> {
        if (Looper.myLooper() == Looper.getMainLooper()) throw Fail("I can't test a page on the app's main thread.")
        val pages = files(dir).filter { it.extension.lowercase() in setOf("html", "htm") }
            .sortedBy { if (it.parentFile == dir && it.name == "index.html") "" else it.path }.take(6)
        val out = ArrayList<String>()
        for ((i, p) in pages.withIndex()) out.addAll(session(ctx, dir, p, waitMs, 390, 844, null, if (i == 0) steps else null))
        return out.distinct().take(25)
    }

    /** Screenshots of index.html: a phone-width view and a desktop-width view. A blank or failed capture is left out. */
    fun shots(ctx: Context, dir: File, outDir: File): List<File> {
        if (Looper.myLooper() == Looper.getMainLooper()) throw Fail("I can't take screenshots on the app's main thread.")
        val idx = File(dir, "index.html")
        if (!idx.isFile) return emptyList()
        outDir.mkdirs()
        val phone = File(outDir, "phone.jpg").also { it.delete() }
        val desk = File(outDir, "desktop.jpg").also { it.delete() }
        session(ctx, dir, idx, 3000, 390, 844, phone, null)
        session(ctx, dir, idx, 3000, 1100, 760, desk, null)
        return listOf(phone, desk).filter { it.isFile }
    }

    private fun snap(wv: WebView, w: Int, h: Int, to: File) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        wv.draw(Canvas(bmp))
        val first = bmp.getPixel(0, 0)
        var varied = false
        val sy = maxOf(1, h / 40); val sx = maxOf(1, w / 40)
        var y = 0
        while (y < h && !varied) { var x = 0; while (x < w && !varied) { if (bmp.getPixel(x, y) != first) varied = true; x += sx }; y += sy }
        if (varied) {
            val outW = minOf(w, 700)
            val scaled = if (outW < w) Bitmap.createScaledBitmap(bmp, outW, h * outW / w, true) else bmp
            to.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            if (scaled !== bmp) scaled.recycle()
        }
        bmp.recycle()
    }

    private fun session(ctx: Context, dir: File, page: File, waitMs: Long, wCss: Int, hCss: Int, shotTo: File?, steps: JSONArray?): List<String> {
        val label = rel(dir, page)
        val errors = java.util.Collections.synchronizedList(ArrayList<String>())
        val done = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        var view: WebView? = null

        // Always called on the main thread.
        fun finish() {
            if (finished.compareAndSet(false, true)) {
                runCatching { view?.stopLoading(); view?.destroy() }
                done.countDown()
            }
        }

        main.post {
            try {
                if (shotTo != null) runCatching { WebView.enableSlowWholeDocumentDraw() }
                val wv = WebView(ctx.applicationContext)
                view = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.allowFileAccess = true
                wv.settings.allowFileAccessFromFileURLs = true
                val dens = ctx.resources.displayMetrics.density
                val wPx = (wCss * dens).toInt(); val hPx = (hCss * dens).toInt()
                wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                wv.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY))
                wv.layout(0, 0, wPx, hPx)

                fun after() {
                    wv.evaluateJavascript("(function(){var b=document.body;return b?b.innerText.trim().length+':'+b.children.length:'none'})()") { r ->
                        val t = (r ?: "").trim('"')
                        val len = t.substringBefore(':').toIntOrNull() ?: 0
                        val kids = t.substringAfter(':', "0").toIntOrNull() ?: 0
                        if (t == "none" || (len == 0 && kids == 0)) errors.add("$label: the page is blank after loading.")
                        finish()
                    }
                }

                fun go(i: Int) {
                    if (finished.get()) return
                    if (steps == null || i >= steps.length()) { after(); return }
                    val st = steps.optJSONObject(i)
                    if (st == null) { go(i + 1); return }
                    wv.evaluateJavascript(STEP_JS.replace("__STEP__", st.toString())) { r ->
                        val res = (r ?: "").trim('"')
                        if (res != "ok") errors.add("$label: test step ${i + 1} (${st.optString("do")} ${st.optString("sel")}) failed: ${res.take(120)}")
                        main.postDelayed({ go(i + 1) }, 500)
                    }
                }

                wv.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            val msg = m.message() ?: ""
                            if (!Regex("CORS|origin 'null'|Cross origin", RegexOption.IGNORE_CASE).containsMatchIn(msg)) {
                                val src = (m.sourceId() ?: "").substringAfterLast('/')
                                errors.add("$label: JavaScript error \"${msg.take(160)}\" ($src line ${m.lineNumber()})")
                            }
                        }
                        return true
                    }
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                        if (r.url.scheme == "file") errors.add("$label: could not load ${r.url.lastPathSegment} (${e.description})")
                    }

                    override fun onPageFinished(v: WebView, url: String?) {
                        main.postDelayed({
                            if (finished.get()) return@postDelayed
                            if (shotTo != null) runCatching { snap(wv, wPx, hPx, shotTo) }
                            go(0)
                        }, waitMs)
                    }
                }
                wv.loadUrl("file://" + page.absolutePath)
            } catch (e: Exception) {
                errors.add("$label: the test could not start (${e.message})")
                finish()
            }
        }
        val steps12 = (steps?.length() ?: 0) * 1_200L
        if (!done.await(waitMs + 15_000 + steps12, TimeUnit.MILLISECONDS)) {
            errors.add("$label: did not finish loading in time.")
            main.post { finish() }
        }
        return errors.toList()
    }

    /* ── 3. deploy to Netlify ─────────────────────────────────────────────── */

    class Deployed(val siteId: String, val url: String, val fileCount: Int, val skipped: List<String>)

    /** The zip Netlify gets: the project without history, trash, git, node_modules and anything named like a secret. */
    private fun zipOf(dir: File): Triple<ByteArray, Int, List<String>> {
        val bos = ByteArrayOutputStream()
        val skipped = ArrayList<String>()
        var n = 0
        java.util.zip.ZipOutputStream(bos).use { z ->
            for (f in files(dir)) {
                if (SECRET.containsMatchIn(f.name)) { skipped.add(rel(dir, f)); continue }
                z.putNextEntry(java.util.zip.ZipEntry(rel(dir, f)))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
                n++
            }
        }
        return Triple(bos.toByteArray(), n, skipped)
    }

    /** A plain authenticated HTTP call (Bearer token). Used for Netlify and Supabase; never logs the token. */
    fun call(method: String, url: String, token: String, ctype: String?, body: ByteArray?): Pair<Int, String> {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("User-Agent", "MYRA-phone")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", ctype ?: "application/octet-stream")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } catch (e: Exception) {
            throw Fail("I couldn't reach Netlify: ${e.message}")
        }
    }

    /** [siteId] null makes a new Netlify site; otherwise the same site gets a new version. Never logs or returns the token. */
    fun deploy(token: String, dir: File, siteId: String?): Deployed {
        if (!File(dir, "index.html").isFile) throw Fail("A site needs an index.html at the top of the project folder.")
        val (zip, count, skipped) = zipOf(dir)
        if (zip.size > 40_000_000) throw Fail("The project is too big to publish (over 40 MB).")
        val api = "https://api.netlify.com/api/v1"
        var id = siteId.orEmpty()
        if (id.isEmpty()) {
            val (c, t) = call("POST", "$api/sites", token, "application/json", "{}".toByteArray())
            if (c == 401 || c == 403) throw Fail("Netlify refused the token. Make a new personal access token and save it in Keys and accounts.")
            if (c !in 200..299) throw Fail("Netlify could not make a site (error $c).")
            id = JSONObject(t).optString("id")
            if (id.isEmpty()) throw Fail("Netlify did not give a site id.")
        }
        val (c2, t2) = call("POST", "$api/sites/$id/deploys", token, "application/zip", zip)
        if (c2 == 401 || c2 == 403) throw Fail("Netlify refused the token. Make a new personal access token and save it in Keys and accounts.")
        if (c2 == 404 && !siteId.isNullOrEmpty()) throw Fail("The saved Netlify site is gone. Ask again with new_site true to make a fresh one.")
        if (c2 !in 200..299) throw Fail("Netlify did not accept the upload (error $c2).")
        val d = JSONObject(t2)
        val deployId = d.optString("id")
        var state = d.optString("state")
        var tries = 0
        while (state != "ready" && state != "error" && deployId.isNotEmpty() && tries++ < 30) {
            Thread.sleep(2000)
            val (c3, t3) = call("GET", "$api/deploys/$deployId", token, null, null)
            if (c3 in 200..299) {
                val j = JSONObject(t3)
                state = j.optString("state")
                if (state == "error") throw Fail("Netlify could not publish it: ${j.optString("error_message").ifEmpty { "unknown reason" }.take(160)}")
            }
        }
        if (state == "error") throw Fail("Netlify could not publish it.")
        if (state != "ready") throw Fail("Netlify is still working on it after a minute. Check app.netlify.com in a bit.")
        val (c4, t4) = call("GET", "$api/sites/$id", token, null, null)
        val site = if (c4 in 200..299) JSONObject(t4) else JSONObject()
        val url = site.optString("ssl_url").ifEmpty { site.optString("url") }.ifEmpty { d.optString("ssl_url") }.ifEmpty { d.optString("url") }
        return Deployed(id, url, count, skipped)
    }
}
