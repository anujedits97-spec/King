package dev.kairo.myra.brain.life

import dev.kairo.myra.Fail
import dev.kairo.myra.brain.BrainEnv
import dev.kairo.myra.brain.BrainTool
import dev.kairo.myra.brain.ConfirmGate
import dev.kairo.myra.brain.Risk
import dev.kairo.myra.brain.ToolModule
import dev.kairo.myra.brain.prop
import dev.kairo.myra.brain.schema
import dev.kairo.myra.brain.tool
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Google Drive connector using a SERVICE ACCOUNT key (no browser login needed). You create the key in Google Cloud,
 * attach the JSON file in chat, and share the Drive folders you want with the service account's email.
 * Myraa signs a short token from that key, then reads only files shared with it (read-only scope).
 * The key is stored encrypted, and the attachment is deleted after it is saved.
 */
class GoogleDriveModule(private val env: BrainEnv) : ToolModule {
    private val scope = "https://www.googleapis.com/auth/drive.readonly"
    private val tokenUrl = "https://oauth2.googleapis.com/token"
    private val gate = ConfirmGate()
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedUntil = 0L

    private fun keyJson(): JSONObject {
        val raw = env.conf.obj("gdrive")?.optString("key").orEmpty()
        if (raw.isEmpty()) throw Fail("Google Drive is not connected. Attach the service account key file and ask me to connect Drive.")
        return JSONObject(raw)
    }

    private fun b64url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Makes a short access token from the service account key (JWT, RS256). Cached until it nearly expires. */
    private fun accessToken(): String {
        val now = System.currentTimeMillis() / 1000
        cachedToken?.let { if (now < cachedUntil - 60) return it }
        val key = keyJson()
        val email = key.optString("client_email")
        val pem = key.optString("private_key")
        if (email.isEmpty() || pem.isEmpty()) throw Fail("The Drive key file is not valid.")
        val header = b64url("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = JSONObject().put("iss", email).put("scope", scope).put("aud", tokenUrl)
            .put("iat", now).put("exp", now + 3600)
        val unsigned = header + "." + b64url(claims.toString().toByteArray())
        val der = Base64.getDecoder().decode(pem.replace(Regex("-----[A-Z ]+-----"), "").replace(Regex("\\s"), ""))
        val priv = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(priv); update(unsigned.toByteArray()) }.sign()
        val assertion = unsigned + "." + b64url(sig)

        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") +
            "&assertion=" + URLEncoder.encode(assertion, "UTF-8")
        val conn = (URL(tokenUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            doOutput = true
            outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        val token = json.optString("access_token")
        if (code !in 200..299 || token.isEmpty()) throw Fail("Google would not accept the Drive key. Check the key file and that Drive API is enabled.")
        cachedToken = token
        cachedUntil = now + json.optLong("expires_in", 3600)
        return token
    }

    private fun get(path: String): String {
        val conn = (URL("https://www.googleapis.com/drive/v3$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer ${accessToken()}")
        }
        val code = conn.responseCode
        if (code == 401 || code == 403) { cachedToken = null; throw Fail("Google Drive refused access. Share the folder or file with the service account email.") }
        if (code !in 200..299) throw Fail("Google Drive returned error $code.")
        return conn.inputStream.bufferedReader().readText()
    }

    private fun search(a: JSONObject): String {
        // Quotes would break the Drive query, so they are removed from the search words.
        val q = a.optString("query").replace("'", "").replace("\\", "").trim()
        if (q.isEmpty()) throw Fail("What file should I look for?")
        val query = URLEncoder.encode("name contains '$q' and trashed = false", "UTF-8").replace("+", "%20")
        val files = JSONObject(get("/files?q=$query&pageSize=10&fields=files(id,name,mimeType,modifiedTime)")).optJSONArray("files") ?: JSONArray()
        if (files.length() == 0) return "No Drive files found for \"$q\" (only files shared with the service account are visible)."
        return (0 until files.length()).joinToString("\n") { i ->
            val f = files.getJSONObject(i)
            "${f.optString("name")} (id ${f.optString("id")}, ${f.optString("mimeType")})"
        }
    }

    private fun read(a: JSONObject): String {
        val id = a.optString("file_id").trim()
        if (!Regex("^[A-Za-z0-9_-]{5,120}$").matches(id)) throw Fail("That file id does not look right. Search for the file first.")
        val meta = JSONObject(get("/files/$id?fields=name,mimeType,size"))
        val mime = meta.optString("mimeType")
        val text = when {
            mime == "application/vnd.google-apps.document" ->
                get("/files/$id/export?mimeType=text%2Fplain")
            mime.startsWith("text/") || mime == "application/json" -> {
                if (meta.optString("size").toLongOrNull()?.let { it > 300_000 } == true) throw Fail("That file is too big to read here.")
                get("/files/$id?alt=media")
            }
            else -> throw Fail("I can read Google Docs and text files. This one is ${mime}.")
        }
        return "${meta.optString("name")}:\n" + text.take(3000)
    }

    /** Saves the attached key file (encrypted) and removes the plain copy. Asks first. */
    private fun connect(a: JSONObject): String {
        val name = a.optString("file").trim()
        if (!Regex("^[A-Za-z0-9 _.()-]{1,120}\\.json$").matches(name)) throw Fail("Attach the service account JSON file to the chat, then tell me its name.")
        val f = File(env.workspace, "attachments/$name").canonicalFile
        if (!f.path.startsWith(env.workspace.canonicalPath + File.separator) || !f.isFile) throw Fail("I can't find $name. Attach it to the chat again.")
        if (f.length() > 20_000) throw Fail("That file is too big to be a service account key.")
        val raw = f.readText()
        val key = try { JSONObject(raw) } catch (e: Exception) { throw Fail("That file is not a valid JSON key.") }
        if (key.optString("client_email").isEmpty() || key.optString("private_key").isEmpty()) throw Fail("That is not a service account key (missing client_email or private_key).")
        gate.gate("gdrive_connect", key.optString("client_email"), a.optString("confirm_token"),
            "connect Google Drive with the service account ${key.optString("client_email")}? Myraa will read only files you share with it.")?.let { return it }
        val root = JSONObject(env.conf.root.toString())
        root.put("gdrive", JSONObject().put("key", raw))
        env.conf.root = root
        env.conf.save()
        f.delete()
        cachedToken = null
        return "Google Drive is connected. Share the folders you want with ${key.optString("client_email")}."
    }

    override fun tools(): List<BrainTool> = listOf(
        tool("gdrive_search", "Search Google Drive files shared with the service account, by name.", Risk.LOW,
            schema(listOf("query"), "query" to prop("string"))) { search(it) },
        tool("gdrive_read", "Read a Google Doc or text file from Drive, by its id from gdrive_search.", Risk.LOW,
            schema(listOf("file_id"), "file_id" to prop("string"))) { read(it) },
        tool("gdrive_connect", "Connect Google Drive from a service account JSON key the user attached to the chat. Asks the user out loud first.", Risk.HIGH,
            schema(listOf("file"), "file" to prop("string", "Name of the attached .json key file"), "confirm_token" to prop("string"))) { connect(it) },
    )
}
