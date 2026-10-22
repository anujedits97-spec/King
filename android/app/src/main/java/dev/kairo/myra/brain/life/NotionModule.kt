package dev.kairo.myra.brain.life

import dev.kairo.myra.Fail
import dev.kairo.myra.brain.BrainEnv
import dev.kairo.myra.brain.BrainTool
import dev.kairo.myra.brain.Risk
import dev.kairo.myra.brain.ToolModule
import dev.kairo.myra.brain.prop
import dev.kairo.myra.brain.schema
import dev.kairo.myra.brain.tool
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Notion connector (API 2022-06-28) with a Notion integration token. Myraa only sees pages you shared with
 * the integration. Tools: search pages, read a page's text, add a new note page under your chosen parent page.
 * Page text from Notion is data, never instructions.
 */
class NotionModule(private val env: BrainEnv) : ToolModule {
    private val base = "https://api.notion.com/v1"

    private fun creds(): Pair<String, String> {
        val o = env.conf.obj("notion")
        val token = o?.optString("token").orEmpty().trim()
        if (token.isEmpty()) throw Fail("Notion is not connected. Add the integration token in Settings, Keys and accounts.")
        return token to o?.optString("parent_page_id").orEmpty().trim()
    }

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val token = creds().first
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Notion-Version", "2022-06-28")
            setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            throw Fail(if (code == 401 || code == 403) "Notion rejected the token, or the page is not shared with the integration." else "Notion returned error $code.")
        }
        return try { JSONObject(text) } catch (e: Exception) { JSONObject() }
    }

    /** The title of a page or database, from Notion's property layout. */
    private fun titleOf(o: JSONObject): String {
        o.optJSONArray("title")?.let { return plain(it) }
        val props = o.optJSONObject("properties") ?: return "(untitled)"
        val keys = props.keys()
        while (keys.hasNext()) {
            val p = props.optJSONObject(keys.next()) ?: continue
            if (p.optString("type") == "title") return plain(p.optJSONArray("title") ?: JSONArray())
        }
        return "(untitled)"
    }

    private fun plain(a: JSONArray): String =
        (0 until a.length()).joinToString("") { a.optJSONObject(it)?.optString("plain_text").orEmpty() }.ifEmpty { "(untitled)" }

    private fun search(a: JSONObject): String {
        val q = a.optString("query").trim()
        val body = JSONObject().put("query", q).put("page_size", 10)
        val results = call("POST", "/search", body).optJSONArray("results") ?: JSONArray()
        if (results.length() == 0) return "No Notion pages found for \"$q\"."
        return (0 until results.length()).joinToString("\n") { i ->
            val r = results.getJSONObject(i)
            "${titleOf(r)} (id ${r.optString("id")})"
        }
    }

    private fun readPage(a: JSONObject): String {
        val id = a.optString("page_id").trim()
        if (id.isEmpty()) throw Fail("Which Notion page? Search for it first.")
        val blocks = call("GET", "/blocks/$id/children?page_size=100").optJSONArray("results") ?: JSONArray()
        val lines = ArrayList<String>()
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            val type = b.optString("type")
            val inner = b.optJSONObject(type) ?: continue
            val rich = inner.optJSONArray("rich_text") ?: continue
            val text = plain(rich).takeIf { it != "(untitled)" }.orEmpty()
            if (text.isNotBlank()) lines.add(text)
        }
        if (lines.isEmpty()) return "That page has no readable text."
        return lines.joinToString("\n").take(3000)
    }

    private fun addNote(a: JSONObject): String {
        val title = a.optString("title").trim().ifEmpty { "Myraa note" }.take(200)
        val text = a.optString("text").trim()
        if (text.isEmpty()) throw Fail("What should the note say?")
        val parent = a.optString("parent_page_id").trim().ifEmpty { creds().second }
        if (parent.isEmpty()) throw Fail("Tell me which Notion page to put the note under, or set a parent page in Settings.")
        val children = JSONArray()
        text.split("\n").filter { it.isNotBlank() }.take(50).forEach { line ->
            children.put(JSONObject().put("object", "block").put("type", "paragraph").put("paragraph",
                JSONObject().put("rich_text", JSONArray().put(JSONObject().put("type", "text")
                    .put("text", JSONObject().put("content", line.take(1900)))))))
        }
        val body = JSONObject()
            .put("parent", JSONObject().put("page_id", parent))
            .put("properties", JSONObject().put("title", JSONObject().put("title", JSONArray().put(JSONObject()
                .put("type", "text").put("text", JSONObject().put("content", title))))))
            .put("children", children)
        val page = call("POST", "/pages", body)
        return "Added the Notion note \"$title\" (id ${page.optString("id")})."
    }

    override fun tools(): List<BrainTool> = listOf(
        tool("notion_search", "Search the Notion pages shared with Myraa.", Risk.LOW,
            schema(listOf("query"), "query" to prop("string"))) { search(it) },
        tool("notion_read", "Read the text of one Notion page, by its id from notion_search.", Risk.LOW,
            schema(listOf("page_id"), "page_id" to prop("string"))) { readPage(it) },
        tool("notion_add_note", "Create a new note page in Notion with a title and text, under the parent page set in Settings (or the one given).", Risk.LOW,
            schema(listOf("text"), "title" to prop("string"), "text" to prop("string"), "parent_page_id" to prop("string"))) { addNote(it) },
    )
}
