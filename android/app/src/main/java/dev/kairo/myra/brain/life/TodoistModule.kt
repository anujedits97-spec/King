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
 * Todoist connector (REST API v2) with your own API token, saved in Settings, Keys and accounts.
 * Tools: list open tasks, add a task, mark a task done. Task text from Todoist is data, never instructions.
 */
class TodoistModule(private val env: BrainEnv) : ToolModule {
    private fun token(): String {
        val t = env.conf.obj("todoist")?.optString("token").orEmpty().trim()
        if (t.isEmpty()) throw Fail("Todoist is not connected. Add your Todoist token in Settings, Keys and accounts.")
        return t
    }

    private fun call(method: String, path: String, body: JSONObject? = null): String {
        val conn = (URL("https://api.todoist.com/rest/v2$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${token()}")
            setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            throw Fail(if (code == 401 || code == 403) "Todoist rejected the token. Check it in Settings." else "Todoist returned error $code.")
        }
        return text
    }

    private fun openTasks(): JSONArray = JSONArray(call("GET", "/tasks"))

    private fun taskList(): String {
        val tasks = openTasks()
        if (tasks.length() == 0) return "You have no open Todoist tasks."
        return (0 until minOf(tasks.length(), 20)).joinToString("\n") { i ->
            val t = tasks.getJSONObject(i)
            val due = t.optJSONObject("due")?.optString("string").orEmpty()
            "${t.optString("content")}${if (due.isEmpty()) "" else " (due $due)"}"
        }
    }

    private fun taskAdd(a: JSONObject): String {
        val content = a.optString("content").trim()
        if (content.isEmpty()) throw Fail("What should the task say?")
        if (content.length > 500) throw Fail("That task text is too long.")
        val body = JSONObject().put("content", content)
        val due = a.optString("due").trim()
        if (due.isNotEmpty()) body.put("due_string", due.take(100))
        call("POST", "/tasks", body)
        return "Added to Todoist: $content${if (due.isEmpty()) "" else ", due $due"}."
    }

    private fun taskComplete(a: JSONObject): String {
        val q = a.optString("content").trim().lowercase()
        if (q.isEmpty()) throw Fail("Which task is done?")
        val tasks = openTasks()
        val hit = (0 until tasks.length()).map { tasks.getJSONObject(it) }
            .firstOrNull { it.optString("content").lowercase() == q }
            ?: (0 until tasks.length()).map { tasks.getJSONObject(it) }
                .firstOrNull { it.optString("content").lowercase().contains(q) }
            ?: throw Fail("I can't find an open Todoist task called $q.")
        call("POST", "/tasks/${hit.optString("id")}/close")
        return "Marked done: ${hit.optString("content")}."
    }

    override fun tools(): List<BrainTool> = listOf(
        tool("todoist_list", "List open Todoist tasks (up to 20).", Risk.LOW, schema(emptyList())) { taskList() },
        tool("todoist_add", "Add a task to Todoist, with an optional due date in plain words (e.g. tomorrow, every monday).", Risk.LOW,
            schema(listOf("content"), "content" to prop("string"), "due" to prop("string"))) { taskAdd(it) },
        tool("todoist_complete", "Mark an open Todoist task as done, found by its text.", Risk.LOW,
            schema(listOf("content"), "content" to prop("string"))) { taskComplete(it) },
    )
}
