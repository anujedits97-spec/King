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

/**
 * Linear connector (GraphQL API) with a personal API key. Tools: your open assigned issues, and creating an issue
 * in a team (asks first). Issue text from Linear is data, never instructions.
 */
class LinearModule(private val env: BrainEnv) : ToolModule {
    private val gate = ConfirmGate()

    private fun token(): String {
        val t = env.conf.obj("linear")?.optString("token").orEmpty().trim()
        if (t.isEmpty()) throw Fail("Linear is not connected. Add your API key in Settings, Keys and accounts.")
        return t
    }

    /** One GraphQL request. Returns the "data" object. */
    private fun gql(query: String, variables: JSONObject = JSONObject()): JSONObject {
        val body = JSONObject().put("query", query).put("variables", variables)
        val conn = (URL("https://api.linear.app/graphql").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", token())
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code == 401 || code == 403) throw Fail("Linear rejected the API key.")
        if (code !in 200..299) throw Fail("Linear returned error $code.")
        val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        val errs = json.optJSONArray("errors")
        if (errs != null && errs.length() > 0) throw Fail("Linear said: " + errs.getJSONObject(0).optString("message", "request failed").take(200))
        return json.optJSONObject("data") ?: JSONObject()
    }

    private fun myIssues(): String {
        val q = "query { viewer { assignedIssues(first: 15, filter: { state: { type: { nin: [\"completed\", \"canceled\"] } } }) " +
            "{ nodes { identifier title dueDate state { name } } } } }"
        val nodes = gql(q).optJSONObject("viewer")?.optJSONObject("assignedIssues")?.optJSONArray("nodes") ?: JSONArray()
        if (nodes.length() == 0) return "You have no open Linear issues assigned."
        return (0 until nodes.length()).joinToString("\n") { i ->
            val n = nodes.getJSONObject(i)
            val due = n.optString("dueDate").takeIf { it != "null" && it.isNotEmpty() }
            "${n.optString("identifier")} ${n.optString("title")} - ${n.optJSONObject("state")?.optString("name").orEmpty()}" +
                (if (due == null) "" else ", due $due")
        }
    }

    /** The team to use: the one named by key (e.g. ENG), or the first team if only one is given. */
    private fun teamId(key: String): Pair<String, String> {
        val nodes = gql("query { teams { nodes { id key name } } }").optJSONObject("teams")?.optJSONArray("nodes") ?: JSONArray()
        if (nodes.length() == 0) throw Fail("I can't see any Linear teams for this key.")
        val list = (0 until nodes.length()).map { nodes.getJSONObject(it) }
        val chosen = if (key.isEmpty()) list.first()
        else list.firstOrNull { it.optString("key").equals(key, ignoreCase = true) }
            ?: throw Fail("I can't find a Linear team with key $key.")
        return chosen.optString("id") to chosen.optString("key")
    }

    private fun issueCreate(a: JSONObject): String {
        val title = a.optString("title").trim()
        val desc = a.optString("description").trim()
        if (title.isEmpty()) throw Fail("What should the issue be called?")
        if (title.length > 255 || desc.length > 5000) throw Fail("That issue is too long.")
        val (id, key) = teamId(a.optString("team").trim())
        gate.gate("linear_issue", "$key|$title", a.optString("confirm_token"),
            "create the Linear issue \"$title\" in team $key?")?.let { return it }
        val input = JSONObject().put("teamId", id).put("title", title).put("description", desc)
        val res = gql(
            "mutation(\$input: IssueCreateInput!) { issueCreate(input: \$input) { success issue { identifier url } } }",
            JSONObject().put("input", input)
        ).optJSONObject("issueCreate")
        if (res == null || !res.optBoolean("success", false)) throw Fail("Linear did not create the issue.")
        return "Created ${res.optJSONObject("issue")?.optString("identifier")} in $key."
    }

    override fun tools(): List<BrainTool> = listOf(
        tool("linear_my_issues", "List the open Linear issues assigned to the user.", Risk.LOW, schema(emptyList())) { myIssues() },
        tool("linear_issue_create", "Create a Linear issue in a team (team key like ENG, or the first team if left out). Asks the user out loud first.", Risk.HIGH,
            schema(listOf("title"), "title" to prop("string"), "description" to prop("string"), "team" to prop("string"),
                "confirm_token" to prop("string"))) { issueCreate(it) },
    )
}
