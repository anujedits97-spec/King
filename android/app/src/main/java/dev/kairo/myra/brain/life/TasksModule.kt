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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Project tasks: "API banana, project Alpha, in progress, deadline 30 Oct".
 * Status is pending, in_progress or done. "Kya deadline paas hai" lists tasks due soon or overdue.
 * Stored in tasks.json inside Myraa's brain folder. Deleting a task needs a spoken yes first.
 */
class TasksModule(private val env: BrainEnv) : ToolModule {
    private val file = File(env.dir, "tasks.json")
    private val lock = Any()
    private val gate = ConfirmGate()
    private val statuses = listOf("pending", "in_progress", "done")
    private val dateFmt: SimpleDateFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }

    private fun load(): JSONArray = try { if (file.exists()) JSONArray(file.readText()) else JSONArray() } catch (e: Exception) { JSONArray() }
    private fun save(a: JSONArray) { file.writeText(a.toString(1)) }
    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT)
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
    private fun plusDays(n: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, n)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }

    private fun parseDeadline(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return ""
        return try { dateFmt.parse(t); t } catch (e: Exception) { throw Fail("The deadline should look like 2026-10-30.") }
    }

    private fun statusOf(a: JSONObject, default: String = "pending"): String {
        val s = a.optString("status", default).trim().lowercase(Locale.ROOT).replace(' ', '_').replace('-', '_')
        if (s !in statuses) throw Fail("Status should be pending, in_progress or done.")
        return s
    }

    /** Finds one task by project and a part of its name. Returns its index, or throws. */
    private fun find(arr: JSONArray, project: String, task: String): Int {
        val t = norm(task)
        if (t.isEmpty()) throw Fail("Which task do you mean?")
        val hits = objects(arr).withIndex().filter { (_, o) ->
            (project.isEmpty() || norm(o.optString("project")) == norm(project)) &&
                (norm(o.optString("task")) == t || norm(o.optString("task")).contains(t))
        }
        if (hits.isEmpty()) throw Fail("I don't have a task called $task.")
        if (hits.size > 1 && hits.none { norm(it.value.optString("task")) == t }) {
            throw Fail("More than one task matches $task: " + hits.joinToString(", ") { it.value.optString("task") } + ". Say the full name.")
        }
        return hits.firstOrNull { norm(it.value.optString("task")) == t }?.index ?: hits[0].index
    }

    private fun line(o: JSONObject, today: String): String {
        val due = o.optString("deadline")
        val dueText = when {
            due.isEmpty() -> ""
            due < today && o.optString("status") != "done" -> ", OVERDUE since $due"
            else -> ", due $due"
        }
        return "${o.optString("task")} (${o.optString("project")}) - ${o.optString("status")}$dueText"
    }

    private fun taskAdd(a: JSONObject): String {
        return synchronized(lock) {
        val project = a.optString("project", "general").trim().ifEmpty { "general" }
        val task = a.optString("task").trim()
        if (task.isEmpty()) throw Fail("What is the task?")
        val deadline = parseDeadline(a.optString("deadline"))
        val arr = load()
        if (objects(arr).any { norm(it.optString("project")) == norm(project) && norm(it.optString("task")) == norm(task) }) {
            return "$task is already in $project."
        }
        val status = statusOf(a)
        arr.put(JSONObject().put("project", project).put("task", task).put("status", status)
            .put("deadline", deadline).put("ts", System.currentTimeMillis()))
        save(arr)
        val due = if (deadline.isEmpty()) "" else " Deadline $deadline."
        "Added task $task to $project, status $status.$due"
    }
    }

    private fun taskUpdate(a: JSONObject): String {
        return synchronized(lock) {
        val arr = load()
        val i = find(arr, a.optString("project").trim(), a.optString("task"))
        val o = arr.getJSONObject(i)
        if (a.has("status") && a.optString("status").isNotBlank()) o.put("status", statusOf(a))
        if (a.has("deadline")) o.put("deadline", parseDeadline(a.optString("deadline")))
        save(arr)
        "Updated ${o.optString("task")}: ${o.optString("status")}${if (o.optString("deadline").isEmpty()) "" else ", due ${o.optString("deadline")}"}."
    }
    }

    private fun taskList(a: JSONObject): String {
        return synchronized(lock) {
        val project = a.optString("project").trim()
        val includeDone = a.optBoolean("include_done", false)
        val items = objects(load()).filter {
            (project.isEmpty() || norm(it.optString("project")) == norm(project)) &&
                (includeDone || it.optString("status") != "done")
        }.sortedWith(compareBy({ statuses.indexOf(it.optString("status")) }, { it.optString("deadline").ifEmpty { "9999" } }))
        if (items.isEmpty()) return if (project.isEmpty()) "No open tasks." else "No open tasks in $project."
        val t = today()
        items.take(30).joinToString("\n") { line(it, t) }
    }
    }

    private fun taskDueSoon(a: JSONObject): String {
        return synchronized(lock) {
        val days = a.optInt("days", 3).coerceIn(0, 60)
        val t = today()
        val limit = plusDays(days)
        val items = objects(load()).filter {
            val d = it.optString("deadline")
            d.isNotEmpty() && it.optString("status") != "done" && d <= limit
        }.sortedBy { it.optString("deadline") }
        if (items.isEmpty()) return "No deadlines in the next $days days."
        items.joinToString("\n") { line(it, t) }
    }
    }

    private fun taskRemove(a: JSONObject): String {
        val project = a.optString("project").trim()
        val task = a.optString("task")
        val label = if (project.isEmpty()) task else "$task in $project"
        gate.gate("task_remove", label, a.optString("confirm_token"), "delete the task $label for good?")?.let { return it }
        synchronized(lock) {
            val arr = load()
            val i = find(arr, project, task)
            val name = arr.getJSONObject(i).optString("task")
            val kept = JSONArray()
            objects(arr).forEachIndexed { idx, o -> if (idx != i) kept.put(o) }
            save(kept)
            return "Removed task $name."
        }
    }

    override fun tools(): List<BrainTool> = listOf(
        tool("task_add", "Add a task to a project, with optional status (pending, in_progress, done) and deadline (yyyy-mm-dd).", Risk.LOW,
            schema(listOf("task"), "task" to prop("string"), "project" to prop("string", "Project name. Default general."),
                "status" to prop("string", "", listOf("pending", "in_progress", "done")),
                "deadline" to prop("string", "yyyy-mm-dd"))) { taskAdd(it) },
        tool("task_update", "Change a task's status or deadline. Find it by part of its name, plus the project if given.", Risk.LOW,
            schema(listOf("task"), "task" to prop("string"), "project" to prop("string"),
                "status" to prop("string", "", listOf("pending", "in_progress", "done")),
                "deadline" to prop("string", "yyyy-mm-dd, or empty to clear"))) { taskUpdate(it) },
        tool("task_list", "List open tasks, optionally for one project. include_done shows finished ones too.", Risk.LOW,
            schema(emptyList(), "project" to prop("string"), "include_done" to prop("boolean"))) { taskList(it) },
        tool("task_due_soon", "Tasks whose deadline is within the next few days, or overdue. Use for \"kya deadline paas hai\".", Risk.LOW,
            schema(emptyList(), "days" to prop("integer", "How many days ahead, default 3."))) { taskDueSoon(it) },
        tool("task_remove", "Delete a task. Asks the user out loud first.", Risk.HIGH,
            schema(listOf("task"), "task" to prop("string"), "project" to prop("string"), "confirm_token" to prop("string"))) { taskRemove(it) },
    )
}
