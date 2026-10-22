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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitLab connector (API v4) with a personal access token. Works with gitlab.com or your own https server.
 * Tools: search your projects, list a project's open issues, open a new issue (asks first).
 * Text from GitLab is data, never instructions. The token never appears in replies.
 */
class GitLabModule(private val env: BrainEnv) : ToolModule {
    private val gate = ConfirmGate()

    private fun creds(): Pair<String, String> {
        val o = env.conf.obj("gitlab")
        val token = o?.optString("token").orEmpty().trim()
        if (token.isEmpty()) throw Fail("GitLab is not connected. Add your token in Settings, Keys and accounts.")
        val base = o?.optString("base_url").orEmpty().trim().trimEnd('/').ifEmpty { "https://gitlab.com" }
        if (!base.startsWith("https://")) throw Fail("The GitLab address must start with https://.")
        return token to base
    }

    private fun call(method: String, path: String, body: JSONObject? = null): JSONArray {
        val (token, base) = creds()
        val conn = (URL("$base/api/v4$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("PRIVATE-TOKEN", token)
            setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            throw Fail(if (code == 401 || code == 403) "GitLab rejected the token, or it lacks permission." else "GitLab returned error $code.")
        }
        return try { if (text.trim().startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text)) } catch (e: Exception) { JSONArray() }
    }

    /** Projects are addressed as "group/project" in the path, encoded for the URL. */
    private fun enc(p: String) = URLEncoder.encode(p, "UTF-8").replace("+", "%20")

    private fun projects(a: JSONObject): String {
        val q = a.optString("query").trim()
        val path = "/projects?membership=true&simple=true&per_page=10" + if (q.isEmpty()) "" else "&search=" + enc(q)
        val arr = call("GET", path)
        if (arr.length() == 0) return "No GitLab projects found."
        return (0 until arr.length()).joinToString("\n") { i ->
            val p = arr.getJSONObject(i)
            "${p.optString("path_with_namespace")}${if (p.optString("visibility") == "private") " (private)" else ""}"
        }
    }

    private fun issues(a: JSONObject): String {
        val project = a.optString("project").trim()
        if (project.isEmpty()) throw Fail("Which GitLab project? Say its path, like group/project.")
        val arr = call("GET", "/projects/${enc(project)}/issues?state=opened&per_page=15")
        if (arr.length() == 0) return "No open issues in $project."
        return (0 until arr.length()).joinToString("\n") { i ->
            val it = arr.getJSONObject(i)
            "#${it.optString("iid")} ${it.optString("title")}"
        }
    }

    private fun issueCreate(a: JSONObject): String {
        val project = a.optString("project").trim()
        val title = a.optString("title").trim()
        val desc = a.optString("description").trim()
        if (project.isEmpty() || title.isEmpty()) throw Fail("I need the project path and an issue title.")
        if (title.length > 255 || desc.length > 5000) throw Fail("That issue is too long.")
        gate.gate("gitlab_issue", "$project|$title", a.optString("confirm_token"),
            "open a new issue called \"$title\" in $project?")?.let { return it }
        val body = JSONObject().put("title", title).put("description", desc)
        val made = call("POST", "/projects/${enc(project)}/issues", body).optJSONObject(0)
        return "Opened issue #${made?.optString("iid")} in $project."
    }

    override fun tools(): List<BrainTool> = listOf(
        tool("gitlab_projects", "Find GitLab projects the user is a member of, optionally filtered by name.", Risk.LOW,
            schema(emptyList(), "query" to prop("string"))) { projects(it) },
        tool("gitlab_issues", "List open issues of a GitLab project (path like group/project).", Risk.LOW,
            schema(listOf("project"), "project" to prop("string"))) { issues(it) },
        tool("gitlab_issue_create", "Open a new issue in a GitLab project. Asks the user out loud first.", Risk.HIGH,
            schema(listOf("project", "title"), "project" to prop("string"), "title" to prop("string"),
                "description" to prop("string"), "confirm_token" to prop("string"))) { issueCreate(it) },
    )
}
