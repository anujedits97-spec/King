package dev.kairo.myra.brain.mark

import dev.kairo.myra.Fail
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Puts a project folder (an unpacked zip, or a project from MYRA-Projects) on Anuj's own GitHub account:
 *  1. [collect] picks the files. It leaves out git history, node_modules, build caches, files named like
 *     secrets (.env, *.pem, *.keystore ...) and any file whose text holds something that looks like a real key.
 *  2. [upload] makes the repository (private unless told otherwise) or adds to an existing one, then writes
 *     ONE commit with every file through the Git Data API, so the history is clean and it is fast.
 *
 * Needs Anuj's own token (classic personal access token, scope "repo"). The token is only sent to
 * api.github.com and is never logged or returned. Uses OkHttp because the system HttpURLConnection can't send PATCH.
 */
object GitKit {
    private const val API = "https://api.github.com"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    internal val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val SKIP_DIRS = setOf(".git", ".history", ".trash", "node_modules", "__MACOSX", ".gradle", ".idea", "__pycache__")
    private val SKIP_FILES = Regex("^(\\.DS_Store|Thumbs\\.db|local\\.properties)$|\\.pyc$", RegexOption.IGNORE_CASE)

    /** Files that are secrets by their name. .env.example and friends are fine. */
    private val SECRET_NAME = Regex(
        "(^\\.env(?!\\.(example|sample|template)$)($|\\.)|\\.pem$|\\.key$|\\.p12$|\\.pfx$|\\.keystore$|\\.jks$|id_rsa|id_ed25519|^credentials?\\.json$|service[-_]?account.*\\.json$|^secrets?\\.|signing\\.properties$)",
        RegexOption.IGNORE_CASE
    )

    /** Text that looks like a real key. A file holding one is left out, so a key never reaches GitHub by accident. */
    private val SECRET_TEXT = Regex(
        "(ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{50,}|gho_[A-Za-z0-9]{36}|sk-[A-Za-z0-9_-]{32,}|AIza[0-9A-Za-z_-]{35}|AKIA[0-9A-Z]{16}|xox[baprs]-[0-9A-Za-z-]{10,}|" +
            "\\b[0-9]{8,10}:[A-Za-z0-9_-]{35}\\b|-----BEGIN ([A-Z]+ )?PRIVATE KEY-----|sbp_[a-f0-9]{40}|tvly-[A-Za-z0-9]{20,}|nfp_[A-Za-z0-9]{30,})"
    )

    private const val MAX_FILES = 1500
    private const val MAX_FILE = 25_000_000L
    private const val MAX_TOTAL = 80_000_000L
    private const val INLINE_MAX = 200_000          // text files up to this size go straight into the tree
    private const val BATCH_BYTES = 1_200_000       // one tree call carries at most about this much text
    private const val BATCH_FILES = 250

    /** True when a text holds something that looks like a real key (used before any text is sent to GitHub). */
    fun looksSecret(text: String): Boolean = SECRET_TEXT.containsMatchIn(text)

    /** True when a file NAME marks it as a secret (.env, *.pem ...). */
    fun secretName(name: String): Boolean = SECRET_NAME.containsMatchIn(name)

    class Plan(val root: File, val files: List<Pair<String, File>>, val skipped: List<String>, val bytes: Long)
    class Uploaded(val url: String, val fullName: String, val fileCount: Int, val skipped: List<String>, val created: Boolean, val branch: String)

    /* ── choosing the files ───────────────────────────────────────────────── */

    /** A zip usually holds one wrapper folder (my-app/...). The repository should start inside it. */
    fun unwrap(dir: File): File {
        var d = dir
        repeat(3) {
            val kids = (d.listFiles() ?: emptyArray()).filter { it.name != "__MACOSX" && !it.name.startsWith(".") || it.isFile }
            if (kids.size == 1 && kids[0].isDirectory) d = kids[0] else return d
        }
        return d
    }

    private fun isText(b: ByteArray): Boolean {
        val n = minOf(b.size, 4096)
        for (i in 0 until n) if (b[i].toInt() == 0) return false
        return true
    }

    private fun utf8OrNull(b: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    } catch (e: CharacterCodingException) { null }

    fun collect(root: File): Plan {
        val files = ArrayList<Pair<String, File>>()
        val skipped = ArrayList<String>()
        var total = 0L
        val base = root.canonicalFile
        base.walkTopDown().onEnter { it == base || it.name !in SKIP_DIRS }.filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(base).path.replace('\\', '/')
            if (SKIP_FILES.containsMatchIn(f.name)) return@forEach
            if (SECRET_NAME.containsMatchIn(f.name)) { skipped.add(rel); return@forEach }
            if (f.length() > MAX_FILE) { skipped.add("$rel (too big)"); return@forEach }
            if (f.length() <= 1_000_000) {
                val head = f.readBytes()
                if (isText(head) && SECRET_TEXT.containsMatchIn(String(head, Charsets.ISO_8859_1))) { skipped.add("$rel (holds something that looks like a key)"); return@forEach }
            }
            files.add(rel to f)
            total += f.length()
        }
        if (files.size > MAX_FILES) throw Fail("That project has ${files.size} files; the limit is $MAX_FILES.")
        if (total > MAX_TOTAL) throw Fail("That project is too big to upload this way (over 80 MB).")
        return Plan(base, files.sortedBy { it.first }, skipped, total)
    }

    /** A GitHub repository name from anything: letters, numbers, dot, dash, underscore. */
    fun repoName(raw: String): String {
        val n = raw.trim().removeSuffix(".zip").replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-', '.').take(100)
        if (n.isEmpty() || n == "." || n == ".." || n.endsWith(".git")) throw Fail("Tell me a repository name made of letters and numbers, like myra-phone.")
        return n
    }

    /* ── GitHub calls ─────────────────────────────────────────────────────── */

    internal class Reply(val code: Int, val text: String) {
        fun json(): JSONObject = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        fun jsonArray(): JSONArray = try { JSONArray(text) } catch (e: Exception) { JSONArray() }
        val ok get() = code in 200..299
        fun message(): String = json().optString("message").take(160)
    }

    internal fun call(method: String, path: String, token: String, body: JSONObject? = null): Reply {
        val needsBody = method == "POST" || method == "PUT" || method == "PATCH"
        val rb = when {
            body != null && (needsBody || method == "DELETE") -> body.toString().toRequestBody(JSON)
            needsBody && method == "PUT" -> "".toRequestBody(JSON)   // e.g. starring: GitHub wants an empty body
            needsBody -> JSONObject().toString().toRequestBody(JSON)
            else -> null
        }
        val req = Request.Builder().url(API + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "MYRA-phone")
            .method(method, rb)
            .build()
        return try {
            http.newCall(req).execute().use { r -> Reply(r.code, r.body?.string().orEmpty()) }
        } catch (e: IOException) {
            throw Fail("I couldn't reach GitHub: ${e.message}")
        }
    }

    internal fun refused(r: Reply): Nothing {
        when (r.code) {
            401 -> throw Fail("GitHub refused the token. Make a new classic personal access token with the \"repo\" scope and save it in Keys and accounts.")
            403 -> throw Fail("GitHub said no (${r.message().ifEmpty { "403" }}). The token may be missing the \"repo\" scope, or GitHub is slowing me down; try again in a minute.")
            else -> throw Fail("GitHub did not accept that (error ${r.code}: ${r.message().ifEmpty { "no reason given" }}).")
        }
    }

    /** The account name this token belongs to. Also proves the token works. */
    fun who(token: String): String {
        val r = call("GET", "/user", token)
        if (!r.ok) refused(r)
        return r.json().optString("login").ifEmpty { throw Fail("GitHub did not say whose token this is.") }
    }

    /** The newest repositories of the account: "name (private|public)". */
    fun repos(token: String, limit: Int = 15): List<String> {
        val r = call("GET", "/user/repos?per_page=$limit&sort=updated&affiliation=owner", token)
        if (!r.ok) refused(r)
        val a = r.jsonArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { "${it.optString("name")} (${if (it.optBoolean("private")) "private" else "public"})" }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private fun isExec(rel: String) = rel.endsWith(".sh") || rel == "gradlew" || rel.endsWith("/gradlew")

    /**
     * Makes (or finds) the repository, then writes every file as one commit on its default branch.
     * New repository: it ends up holding exactly the project. Existing repository: the project's files are
     * laid over what is there (same names are replaced, other files stay).
     */
    fun upload(token: String, plan: Plan, repo: String, isPrivate: Boolean, description: String, existing: Boolean, label: String): Uploaded {
        val login = who(token)
        val full = "$login/$repo"
        val created: Boolean
        val html: String
        val branch: String
        if (existing) {
            val r = call("GET", "/repos/$full", token)
            if (r.code == 404) throw Fail("There is no repository called $repo in your account. Leave out \"existing\" to make a new one.")
            if (!r.ok) refused(r)
            html = r.json().optString("html_url"); branch = r.json().optString("default_branch").ifEmpty { "main" }; created = false
        } else {
            val body = JSONObject().put("name", repo).put("private", isPrivate).put("auto_init", true)
            if (description.isNotBlank()) body.put("description", description.trim().take(300))
            val r = call("POST", "/user/repos", token, body)
            if (r.code == 422) throw Fail("A repository called $repo already exists in your account (or the name is not allowed). Pick another name, or ask me to add to the existing one.")
            if (!r.ok) refused(r)
            html = r.json().optString("html_url"); branch = r.json().optString("default_branch").ifEmpty { "main" }; created = true
        }

        // The branch tip. A brand new repository can take a moment before its first commit is readable.
        var tip = ""
        for (i in 0 until 6) {
            val r = call("GET", "/repos/$full/git/ref/heads/$branch", token)
            if (r.ok) { tip = r.json().optJSONObject("object")?.optString("sha").orEmpty(); break }
            if (r.code != 404 && r.code != 409) refused(r)
            Thread.sleep(1500)
        }
        if (tip.isEmpty()) throw Fail("The repository exists but I can't read its first commit yet. Try again in a minute and say existing true.")
        val c = call("GET", "/repos/$full/git/commits/$tip", token)
        if (!c.ok) refused(c)
        val baseTree = c.json().optJSONObject("tree")?.optString("sha").orEmpty()

        // Big or binary files become blobs first; text goes straight into the tree in batches.
        val entries = ArrayList<JSONObject>()
        for ((rel, f) in plan.files) {
            val bytes = f.readBytes()
            val mode = if (isExec(rel)) "100755" else "100644"
            val entry = JSONObject().put("path", rel).put("mode", mode).put("type", "blob")
            val text = if (bytes.isNotEmpty() && bytes.size <= INLINE_MAX && isText(bytes)) utf8OrNull(bytes) else null
            if (text != null) {
                entry.put("content", text)
            } else {
                val r = call("POST", "/repos/$full/git/blobs", token, JSONObject().put("content", b64(bytes)).put("encoding", "base64"))
                if (!r.ok) refused(r)
                entry.put("sha", r.json().optString("sha").ifEmpty { throw Fail("GitHub did not accept the file $rel.") })
            }
            entries.add(entry)
        }

        var tree: String? = if (created) null else baseTree
        var i = 0
        while (i < entries.size) {
            val chunk = JSONArray()
            var bytes = 0
            var n = 0
            while (i < entries.size && n < BATCH_FILES && (n == 0 || bytes < BATCH_BYTES)) {
                val e = entries[i]
                chunk.put(e); bytes += e.optString("content").length; n++; i++
            }
            val body = JSONObject().put("tree", chunk)
            if (tree != null) body.put("base_tree", tree)
            val r = call("POST", "/repos/$full/git/trees", token, body)
            if (!r.ok) refused(r)
            tree = r.json().optString("sha").ifEmpty { throw Fail("GitHub did not build the file tree.") }
        }
        val finalTree = tree ?: throw Fail("There was nothing to upload.")

        val msg = if (created) "Add $label (uploaded by Myraa)" else "Update $label (uploaded by Myraa)"
        val commit = call("POST", "/repos/$full/git/commits", token, JSONObject().put("message", msg).put("tree", finalTree).put("parents", JSONArray().put(tip)))
        if (!commit.ok) refused(commit)
        val sha = commit.json().optString("sha").ifEmpty { throw Fail("GitHub did not make the commit.") }
        val move = call("PATCH", "/repos/$full/git/refs/heads/$branch", token, JSONObject().put("sha", sha).put("force", false))
        if (!move.ok) refused(move)
        return Uploaded(html.ifEmpty { "https://github.com/$full" }, full, plan.files.size, plan.skipped, created, branch)
    }
}
