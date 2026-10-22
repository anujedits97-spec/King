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
import java.util.Calendar
import java.util.Locale

/**
 * Shopping lists, notes and expenses, kept in one small file inside Myraa's brain folder.
 * Built for Hinglish voice: "shopping list mein doodh daal do", "aaj 250 ka petrol dala", "meri note dikhao".
 * Deleting a note needs a spoken yes first (ConfirmGate). Everything stays on the phone.
 */
class ListsModule(private val env: BrainEnv) : ToolModule {
    private val file = File(env.dir, "lists.json")
    private val lock = Any()
    private val gate = ConfirmGate()

    private fun load(): JSONObject {
        val o = try { if (file.exists()) JSONObject(file.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }
        if (!o.has("lists")) o.put("lists", JSONObject())
        if (!o.has("notes")) o.put("notes", JSONArray())
        if (!o.has("expenses")) o.put("expenses", JSONArray())
        return o
    }

    private fun save(o: JSONObject) { file.writeText(o.toString(1)) }

    private fun listName(a: JSONObject) = a.optString("list", "shopping").trim().lowercase(Locale.ROOT).ifEmpty { "shopping" }

    private fun objects(arr: JSONArray): List<JSONObject> = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

    /* ── shopping and other lists ───────────────────────────────────────── */

    private fun listAdd(a: JSONObject): String {
        return synchronized(lock) {
        val item = a.optString("item").trim()
        if (item.isEmpty()) throw Fail("What should I add to the list?")
        val name = listName(a)
        val o = load()
        val lists = o.getJSONObject("lists")
        val arr = lists.optJSONArray(name) ?: JSONArray().also { lists.put(name, it) }
        if (arr.let { x -> (0 until x.length()).any { x.optString(it).equals(item, ignoreCase = true) } }) {
            return "$item is already on your $name list."
        }
        arr.put(item)
        save(o)
        "Added $item to your $name list. It has ${arr.length()} items."
    }
    }

    private fun listShow(a: JSONObject): String {
        return synchronized(lock) {
        val name = listName(a)
        val arr = load().getJSONObject("lists").optJSONArray(name)
        if (arr == null || arr.length() == 0) return "Your $name list is empty."
        (0 until arr.length()).joinToString("\n") { "${it + 1}. ${arr.optString(it)}" }
    }
    }

    private fun listRemove(a: JSONObject): String = synchronized(lock) {
        val item = a.optString("item").trim()
        if (item.isEmpty()) throw Fail("Which item should I remove?")
        val name = listName(a)
        val o = load()
        val arr = o.getJSONObject("lists").optJSONArray(name) ?: throw Fail("You don't have a $name list.")
        var idx = -1
        for (i in 0 until arr.length()) if (arr.optString(i).equals(item, ignoreCase = true)) { idx = i; break }
        if (idx < 0) throw Fail("$item is not on your $name list.")
        val kept = JSONArray()
        for (i in 0 until arr.length()) if (i != idx) kept.put(arr.optString(i))
        o.getJSONObject("lists").put(name, kept)
        save(o)
        "Removed $item from your $name list."
    }

    /* ── notes ──────────────────────────────────────────────────────────── */

    private fun noteSave(a: JSONObject): String = synchronized(lock) {
        val title = a.optString("title").trim()
        val text = a.optString("text").trim()
        if (title.isEmpty() || text.isEmpty()) throw Fail("A note needs a title and some text.")
        val o = load()
        val old = o.getJSONArray("notes")
        val kept = JSONArray()
        objects(old).filterNot { it.optString("title").equals(title, ignoreCase = true) }.forEach { kept.put(it) }
        kept.put(JSONObject().put("title", title).put("text", text).put("ts", System.currentTimeMillis()))
        o.put("notes", kept)
        save(o)
        "Saved your note \"$title\"."
    }

    private fun noteRead(a: JSONObject): String = synchronized(lock) {
        val q = a.optString("title").trim()
        val n = objects(load().getJSONArray("notes")).firstOrNull { it.optString("title").equals(q, ignoreCase = true) }
            ?: objects(load().getJSONArray("notes")).firstOrNull { it.optString("title").contains(q, ignoreCase = true) }
            ?: throw Fail("I don't have a note called $q.")
        "${n.optString("title")}: ${n.optString("text")}"
    }

    private fun noteList(): String = synchronized(lock) {
        val titles = objects(load().getJSONArray("notes")).map { it.optString("title") }
        if (titles.isEmpty()) "You have no notes yet." else titles.joinToString("\n")
    }

    private fun noteDelete(a: JSONObject): String {
        val title = a.optString("title").trim()
        if (title.isEmpty()) throw Fail("Which note should I delete?")
        gate.gate("note_delete", title, a.optString("confirm_token"), "delete the note \"$title\" for good?")?.let { return it }
        synchronized(lock) {
            val o = load()
            val arr = o.getJSONArray("notes")
            val kept = JSONArray()
            var found = false
            objects(arr).forEach {
                if (!found && it.optString("title").equals(title, ignoreCase = true)) found = true else kept.put(it)
            }
            if (!found) throw Fail("I don't have a note called $title.")
            o.put("notes", kept)
            save(o)
        }
        return "Deleted the note \"$title\"."
    }

    /* ── expenses ───────────────────────────────────────────────────────── */

    private fun expenseAdd(a: JSONObject): String = synchronized(lock) {
        val amount = a.optDouble("amount", Double.NaN)
        if (amount.isNaN() || amount <= 0) throw Fail("How much did you spend?")
        val category = a.optString("category", "other").trim().lowercase(Locale.ROOT).ifEmpty { "other" }
        val note = a.optString("note").trim()
        val o = load()
        o.getJSONArray("expenses").put(
            JSONObject().put("amount", amount).put("category", category).put("note", note).put("ts", System.currentTimeMillis())
        )
        save(o)
        "Recorded ₹${"%.0f".format(Locale.US, amount)} for $category."
    }

    private fun since(period: String): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        when (period) {
            "today" -> {}
            "week" -> c.add(Calendar.DAY_OF_YEAR, -6)
            else -> c.set(Calendar.DAY_OF_MONTH, 1)
        }
        return c.timeInMillis
    }

    private fun expenseSummary(a: JSONObject): String {
        return synchronized(lock) {
        val period = a.optString("period", "month").lowercase(Locale.ROOT).let { if (it in listOf("today", "week", "month")) it else "month" }
        val start = since(period)
        val byCat = LinkedHashMap<String, Double>()
        var total = 0.0
        objects(load().getJSONArray("expenses")).filter { it.optLong("ts") >= start }.forEach {
            val amt = it.optDouble("amount", 0.0)
            total += amt
            val cat = it.optString("category", "other")
            byCat[cat] = (byCat[cat] ?: 0.0) + amt
        }
        if (byCat.isEmpty()) return "No expenses recorded for this $period."
        val lines = byCat.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ₹${"%.0f".format(Locale.US, it.value)}" }
        "Total this $period: ₹${"%.0f".format(Locale.US, total)}. By category: $lines."
    }
    }

    override fun tools(): List<BrainTool> = listOf(
        tool("list_add", "Add an item to a list (shopping by default). Use for things like \"shopping list mein doodh daal do\".", Risk.LOW,
            schema(listOf("item"), "item" to prop("string"), "list" to prop("string", "List name, e.g. shopping, todo. Default shopping."))) { listAdd(it) },
        tool("list_show", "Read out a list (shopping by default).", Risk.LOW,
            schema(emptyList(), "list" to prop("string"))) { listShow(it) },
        tool("list_remove", "Remove one item from a list.", Risk.LOW,
            schema(listOf("item"), "item" to prop("string"), "list" to prop("string"))) { listRemove(it) },
        tool("note_save", "Save a note with a title and text, or replace the note with the same title.", Risk.LOW,
            schema(listOf("title", "text"), "title" to prop("string"), "text" to prop("string"))) { noteSave(it) },
        tool("note_read", "Read a note by title (also matches part of the title).", Risk.LOW,
            schema(listOf("title"), "title" to prop("string"))) { noteRead(it) },
        tool("note_list", "List the titles of all saved notes.", Risk.LOW, schema(emptyList())) { noteList() },
        tool("note_delete", "Delete a saved note. Asks the user out loud first.", Risk.HIGH,
            schema(listOf("title"), "title" to prop("string"), "confirm_token" to prop("string"))) { noteDelete(it) },
        tool("expense_add", "Record money spent, with a category (food, petrol, bills, travel, shopping...) and an optional note.", Risk.LOW,
            schema(listOf("amount"), "amount" to prop("number", "Amount in rupees"), "category" to prop("string"), "note" to prop("string"))) { expenseAdd(it) },
        tool("expense_summary", "Total expenses for today, this week or this month, by category.", Risk.LOW,
            schema(emptyList(), "period" to prop("string", "today, week or month", listOf("today", "week", "month")))) { expenseSummary(it) },
    )
}
