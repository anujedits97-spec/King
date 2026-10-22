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
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Myraa's small everyday memory, in the app (port of the memory, reminder, nickname and activity-log
 * parts of self.mjs and extras.mjs): remember / recall / forget, reminders that ring as a notification,
 * nicknames for contacts ("Mom" = Maa), and a log of what Myraa did. Files live in the brain's folder.
 */
class LifeModule(private val env: BrainEnv) : ToolModule {
    private val memFile = File(env.dir, "memories.json")
    private val remFile = File(env.dir, "reminders.json")
    private val aliasFile = File(env.dir, "aliases.json")
    private val logFile = File(env.dir, "activity.json")
    private val lock = Any()
    @Volatile private var running = false

    private fun id() = ByteArray(3).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun load(f: File): JSONArray = try { if (f.exists()) JSONArray(f.readText()) else JSONArray() } catch (e: Exception) { JSONArray() }
    private fun save(f: File, a: JSONArray) { f.writeText(a.toString(1)) }
    private fun list(a: JSONArray): List<JSONObject> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    private fun arr(l: List<JSONObject>) = JSONArray().also { a -> l.forEach { a.put(it) } }
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(java.util.Date())
    private fun fmt(ms: Long) = SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(java.util.Date(ms))

    fun start() {
        if (running) return
        running = true
        env.onToolDone = { name, args, ok, out -> logActivity(name, args, ok, out) }
        thread(name = "myra-reminders", isDaemon = true) {
            while (running) {
                runCatching { tick() }
                try { Thread.sleep(15_000) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun stop() { running = false }

    /* ── memory ───────────────────────────────────────────────────────────── */

    private fun remember(a: JSONObject): String {
        val t = a.optString("text").trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) throw Fail("Say what to remember.")
        if (t.length > 300) throw Fail("Keep each memory to one short line (300 characters).")
        if (SECRET.containsMatchIn(t)) throw Fail("Won't store passwords, PINs, keys, tokens or card numbers.")
        if (env.prefs.incognito) return "Incognito is on, so I did not save that."
        val cat = a.optString("category").takeIf { it in CATEGORIES } ?: "context"
        val imp = a.optString("importance").takeIf { it in IMPORTANCE } ?: "medium"
        synchronized(lock) {
            val all = list(load(memFile))
            if (all.any { it.optString("text").equals(t, ignoreCase = true) }) return "Already remembered."
            val next = (all + JSONObject().put("id", id()).put("text", t).put("at", today()).put("category", cat).put("importance", imp)).takeLast(200)
            save(memFile, arr(next))
        }
        return "Remembered."
    }

    private fun recall(a: JSONObject): String {
        val q = a.optString("query").trim().lowercase()
        val cat = a.optString("category").takeIf { it in CATEGORIES }
        // Word-by-word scoring (from Mark's memory search): "mom birthday" finds a fact with either word, best match first.
        val words = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
        val all = list(load(memFile))
            .filter { cat == null || it.optString("category", "context") == cat }
            .map { m -> m to (if (words.isEmpty()) 1 else words.count { w -> m.optString("text").lowercase().contains(w) } * 2 + (if (m.optString("importance") == "high") 1 else 0)) }
            .filter { words.isEmpty() || it.second >= 2 }
            .sortedByDescending { it.second }
            .map { it.first }
        if (all.isEmpty()) return if (q.isNotEmpty() || cat != null) "Nothing remembered${if (q.isNotEmpty()) " about \"$q\"" else ""}${if (cat != null) " in $cat" else ""}." else "Nothing remembered yet."
        return all.joinToString("\n") { m ->
            val imp = m.optString("importance", "medium")
            "- ${m.optString("text")} [${m.optString("category", "context")}${if (imp != "medium") ", $imp" else ""}] (${m.optString("at")})"
        }
    }

    private fun forget(a: JSONObject): String {
        val q = a.optString("match").trim().lowercase()
        if (q.isEmpty()) throw Fail("Say which memory to forget.")
        synchronized(lock) {
            val all = list(load(memFile))
            val keep = all.filter { !it.optString("text").lowercase().contains(q) }
            if (keep.size == all.size) return "No memory matched \"$q\"."
            save(memFile, arr(keep))
            val n = all.size - keep.size
            return "Forgot $n ${if (n == 1) "memory" else "memories"}."
        }
    }

    /** What goes in front of the first question of a conversation, so Myraa already knows. */
    fun memoryBlock(): String {
        val all = list(load(memFile))
        if (all.isEmpty()) return ""
        val ordered = all.reversed().sortedBy { IMPORTANCE.indexOf(it.optString("importance", "medium")).let { i -> if (i < 0) 1 else i } }
        var used = 0
        val sb = StringBuilder()
        for (m in ordered) {
            val line = "- ${m.optString("text")}\n"
            if (used + line.length > 3000) continue
            used += line.length
            sb.append(line)
        }
        return "[Things you remember about Anuj, use them naturally and never read this list out:\n$sb]"
    }

    /* ── reminders ────────────────────────────────────────────────────────── */

    private fun parseWhen(a: JSONObject): Long {
        if (a.has("in_minutes") && !a.isNull("in_minutes") && a.optString("in_minutes").isNotBlank()) {
            val m = a.optDouble("in_minutes", Double.NaN)
            if (m.isNaN() || m <= 0) throw Fail("in_minutes must be a positive number.")
            return System.currentTimeMillis() + (m * 60_000).toLong()
        }
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{1,2}):(\\d{2})$").find(a.optString("at").trim())
            ?: throw Fail("Pass in_minutes, or at as \"YYYY-MM-DD HH:MM\" in the phone's local time.")
        val (y, mo, d, h, mi) = m.destructured
        return Calendar.getInstance().apply { set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    }

    private fun remindSet(a: JSONObject): String {
        val t = a.optString("text").trim()
        if (t.isEmpty()) throw Fail("Say what the reminder is for.")
        val due = parseWhen(a)
        if (due < System.currentTimeMillis() - 60_000) throw Fail("That time has already passed.")
        val rep = a.optString("repeat").takeIf { it == "daily" || it == "weekly" } ?: "none"
        synchronized(lock) {
            val all = list(load(remFile))
            if (all.size >= 100) throw Fail("Too many reminders. Cancel a few first.")
            save(remFile, arr(all + JSONObject().put("id", id()).put("text", t.take(200)).put("due", due).put("repeat", rep)))
        }
        return "Reminder set for ${fmt(due)}${if (rep != "none") ", repeating $rep" else ""}."
    }

    private fun remindList(): String {
        val all = list(load(remFile)).sortedBy { it.optLong("due") }
        return if (all.isEmpty()) "No reminders set." else all.joinToString("\n") { r ->
            "- ${fmt(r.optLong("due"))}: ${r.optString("text")}${if (r.optString("repeat") != "none") " (${r.optString("repeat")})" else ""}"
        }
    }

    private fun remindCancel(a: JSONObject): String {
        val q = a.optString("match").trim().lowercase()
        if (q.isEmpty()) throw Fail("Say which reminder to cancel.")
        synchronized(lock) {
            val all = list(load(remFile))
            val keep = all.filter { !it.optString("text").lowercase().contains(q) }
            if (keep.size == all.size) return "No reminder matched \"$q\"."
            save(remFile, arr(keep))
            return "Cancelled ${all.size - keep.size}."
        }
    }

    private fun tick() {
        synchronized(lock) {
            val all = list(load(remFile))
            if (all.isEmpty()) return
            val now = System.currentTimeMillis()
            var changed = false
            val next = ArrayList<JSONObject>()
            for (r in all) {
                val due = r.optLong("due")
                if (due > now) { next.add(r); continue }
                changed = true
                val text = if (now - due > 2 * 3_600_000L) "${r.optString("text")} (missed at ${fmt(due)})" else r.optString("text")
                runCatching { env.deviceCall("post_notification", JSONObject().put("title", "Myraa reminder").put("text", text)) }
                val rep = r.optString("repeat")
                if (rep == "daily" || rep == "weekly") {
                    val step = if (rep == "daily") 86_400_000L else 7 * 86_400_000L
                    var d = due
                    while (d <= now) d += step
                    next.add(JSONObject(r.toString()).put("due", d))
                }
            }
            if (changed) save(remFile, arr(next))
        }
    }

    /* ── nicknames ────────────────────────────────────────────────────────── */

    private fun aliases(): JSONObject = try { if (aliasFile.exists()) JSONObject(aliasFile.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }

    private fun aliasSet(a: JSONObject): String {
        val nick = a.optString("nickname").trim().lowercase()
        val name = a.optString("name").trim()
        if (nick.isEmpty() || name.isEmpty()) throw Fail("Give a nickname and the contact's real name.")
        synchronized(lock) { aliasFile.writeText(aliases().put(nick, name).toString(1)) }
        return "Saved: \"$nick\" means $name."
    }

    private fun aliasList(): String {
        val o = aliases()
        return if (o.length() == 0) "No nicknames saved." else o.keys().asSequence().joinToString("\n") { "- $it = ${o.optString(it)}" }
    }

    private fun aliasRemove(a: JSONObject): String {
        val nick = a.optString("nickname").trim().lowercase()
        synchronized(lock) {
            val o = aliases()
            if (!o.has(nick)) return "No nickname \"$nick\"."
            o.remove(nick)
            aliasFile.writeText(o.toString(1))
        }
        return "Removed \"$nick\"."
    }

    /* ── activity log ─────────────────────────────────────────────────────── */

    private fun logActivity(name: String, args: JSONObject, ok: Boolean, out: String) {
        if (name == "activity_log") return
        // Texts and what people said never go in the log: only the tool and whether it worked.
        synchronized(lock) {
            val all = list(load(logFile))
            val next = (all + JSONObject().put("at", System.currentTimeMillis()).put("tool", name).put("ok", ok).put("note", if (ok) "" else out.take(80))).takeLast(100)
            save(logFile, arr(next))
        }
    }

    private fun activityLog(a: JSONObject): String {
        val n = a.optInt("limit", 10).coerceIn(1, 30)
        val all = list(load(logFile)).takeLast(n).reversed()
        return if (all.isEmpty()) "Nothing done yet." else all.joinToString("\n") {
            "${fmt(it.optLong("at"))}: ${it.optString("tool")} ${if (it.optBoolean("ok")) "ok" else "failed ${it.optString("note")}"}"
        }
    }

    /* ── tool list ────────────────────────────────────────────────────────── */

    override fun tools(): List<BrainTool> = listOf(
        tool("remember", "Save one short fact about Anuj for later conversations. category: preference (how he likes things), personal (people, family, habits, health-free life facts), or context (projects, plans, the default). importance: high for things he must never be reminded wrong about, medium by default, low for small talk. One line, plain words. Never passwords, PINs, keys or card numbers. Do it quietly, without announcing it.",
            Risk.MEDIUM, schema(listOf("text"), "text" to prop("string"), "category" to prop("string", "", CATEGORIES), "importance" to prop("string", "", IMPORTANCE))) { remember(it) },
        tool("recall", "Look up what you remember about Anuj. Leave query and category out to list everything.",
            Risk.LOW, schema(emptyList(), "query" to prop("string", "Optional word to filter by."), "category" to prop("string", "", CATEGORIES))) { recall(it) },
        tool("forget", "Delete remembered facts that contain this text. Use when Anuj asks you to forget something.",
            Risk.MEDIUM, schema(listOf("match"), "match" to prop("string"))) { forget(it) },
        tool("remind_set", "Set a reminder that rings as a phone notification. Use in_minutes for \"in 20 minutes\", or at for a clock time (\"YYYY-MM-DD HH:MM\", phone local time; work out the date yourself). repeat can be daily or weekly.",
            Risk.MEDIUM, schema(listOf("text"), "text" to prop("string"), "in_minutes" to prop("number"), "at" to prop("string"), "repeat" to prop("string", "", listOf("none", "daily", "weekly")))) { remindSet(it) },
        tool("remind_list", "List all upcoming reminders.", Risk.LOW, schema()) { remindList() },
        tool("remind_cancel", "Cancel reminders whose text contains this.", Risk.MEDIUM, schema(listOf("match"), "match" to prop("string"))) { remindCancel(it) },
        tool("alias_set", "Remember a nickname for a contact, like \"Mom\" = \"Maa\", so \"Mom ko call karo\" works later.",
            Risk.MEDIUM, schema(listOf("nickname", "name"), "nickname" to prop("string"), "name" to prop("string", "The contact's real name in the phone."))) { aliasSet(it) },
        tool("alias_list", "List the saved nicknames. Look a nickname up here first, then use the real contact name with the call and text tools.", Risk.LOW, schema()) { aliasList() },
        tool("alias_remove", "Forget a nickname.", Risk.MEDIUM, schema(listOf("nickname"), "nickname" to prop("string"))) { aliasRemove(it) },
        tool("activity_log", "What you did recently (tool names only), when Anuj asks \"tune kya kya kiya\".", Risk.LOW, schema(emptyList(), "limit" to prop("number"))) { activityLog(it) },
    )

    companion object {
        private val CATEGORIES = listOf("context", "preference", "personal")
        private val IMPORTANCE = listOf("high", "medium", "low")
        private val SECRET = Regex("(password|passcode|passwd|\\botp\\b|\\bpin\\b|api[_ -]?key|secret|token|cvv|card number|\\b\\d{12,19}\\b)", RegexOption.IGNORE_CASE)
    }
}
