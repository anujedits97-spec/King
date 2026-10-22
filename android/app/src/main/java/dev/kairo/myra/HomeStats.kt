package dev.kairo.myra

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Small things the home screen shows: the day streak ("energy") and today's mood.
 * Everything lives on this phone, in its own small preferences file. Nothing is sent anywhere.
 */
object HomeStats {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("myra_home", Context.MODE_PRIVATE)

    private val dayFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun today(): String = dayFormat.format(Date())

    private fun yesterday(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        return dayFormat.format(cal.time)
    }

    /** Called whenever the home screen opens. Counts a day once: consecutive days make the streak grow, a missed day restarts it at 1. */
    @Synchronized fun touch(c: Context) {
        val p = sp(c)
        val last = p.getString("lastDay", "") ?: ""
        val now = today()
        if (last == now) return
        val streak = if (last == yesterday()) p.getInt("streak", 0) + 1 else 1
        p.edit()
            .putString("lastDay", now)
            .putInt("streak", streak)
            .putInt("best", maxOf(streak, p.getInt("best", 0)))
            .putInt("points", p.getInt("points", 0) + 1)
            .apply()
    }

    /** Days in a row Myraa was opened (0 if a day was missed and the app was not opened yet today). */
    fun streak(c: Context): Int {
        val p = sp(c)
        val last = p.getString("lastDay", "") ?: ""
        return if (last == today() || last == yesterday()) p.getInt("streak", 0) else 0
    }

    fun best(c: Context): Int = sp(c).getInt("best", 0)

    /** Energy points: one for each day Myraa was opened, one for each diary or study entry, one for each focus session. */
    fun points(c: Context): Int = sp(c).getInt("points", 0)

    @Synchronized fun bump(c: Context, n: Int = 1) {
        val p = sp(c)
        p.edit().putInt("points", p.getInt("points", 0) + n).apply()
    }

    /* ── city for the weather card ────────────────────────────────────── */

    fun city(c: Context): String = sp(c).getString("city", "") ?: ""
    fun setCity(c: Context, v: String) { sp(c).edit().putString("city", v.trim().take(60)).apply() }

    /** The last weather answer, so the card shows something right away: JSON text and when it was fetched. */
    fun weatherCache(c: Context): Pair<String, Long> = (sp(c).getString("wx", "") ?: "") to sp(c).getLong("wxAt", 0L)
    fun setWeatherCache(c: Context, json: String) { sp(c).edit().putString("wx", json).putLong("wxAt", System.currentTimeMillis()).apply() }

    /* ── mood ─────────────────────────────────────────────────────────── */

    /** The moods you can pick. These are your own words for how the day feels, not a medical or mental-health reading. */
    val MOODS = listOf("Happy", "Calm", "Tired", "Stressed", "Low")

    fun setMood(c: Context, mood: String?) {
        val e = sp(c).edit()
        if (mood == null) e.remove("mood_" + today()) else e.putString("mood_" + today(), mood)
        e.apply()
    }

    class Mood(val label: String, val note: String, val chosen: Boolean)

    private val HAPPY = listOf("happy", "khush", "great", "awesome", "mast", "maza", "badhiya", "achha", "accha", "good day", "excited", "proud", "love", "enjoy", "thank")
    private val CALM = listOf("calm", "relax", "peace", "shanti", "sukoon", "chill", "theek", "fine", "easy")
    private val TIRED = listOf("tired", "thak", "sleepy", "neend", "exhaust", "drained", "bore")
    private val STRESSED = listOf("stress", "tension", "worried", "chinta", "anxious", "pressure", "deadline", "ghabra", "angry", "gussa", "irritat")
    private val LOW = listOf("sad", "dukhi", "udaas", "udas", "upset", "lonely", "akela", "down", "cry", "ro ", "hopeless", "bura lag")

    /**
     * Today's mood label. Your pick wins. With no pick, a rough guess from the words in today's diary entries
     * (a small word list, on the phone, no AI). With neither, the card says so and invites you to pick.
     */
    fun mood(c: Context): Mood {
        val picked = sp(c).getString("mood_" + today(), null)
        if (picked != null && picked in MOODS) return Mood(picked, noteFor(picked, "your pick"), true)
        val text = JournalStore.todayText(File(c.filesDir, "myra")).lowercase(Locale.ROOT)
        if (text.isNotBlank()) {
            val scores = listOf(
                "Happy" to HAPPY, "Calm" to CALM, "Tired" to TIRED, "Stressed" to STRESSED, "Low" to LOW,
            ).map { (name, words) -> name to words.count { text.contains(it) } }
            val top = scores.maxByOrNull { it.second }
            if (top != null && top.second > 0) return Mood(top.first, noteFor(top.first, "a guess from your diary"), false)
        }
        return Mood("Not set", "tap to pick", false)
    }

    private fun noteFor(label: String, source: String) = when (label) {
        "Low" -> "be gentle today"
        "Stressed" -> "one thing at a time"
        "Tired" -> "rest when you can"
        else -> source
    }
}

/**
 * The diary and the study notes: one small file (myra/journal.json) shared by the app screen and the Myraa tools,
 * so "diary mein likho ..." by voice and the Journal screen show the same entries. Kept on this phone only.
 */
object JournalStore {
    private val lock = Any()

    class Entry(val id: String, val kind: String, val text: String, val day: String, val at: Long)

    private fun file(dir: File) = File(dir, "journal.json")

    private fun read(dir: File): JSONArray = try {
        val f = file(dir)
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (e: Exception) { JSONArray() }

    private fun write(dir: File, a: JSONArray) {
        dir.mkdirs()
        file(dir).writeText(a.toString(1))
    }

    /** Entries of one kind ("journal" or "study"), newest first. */
    fun list(dir: File, kind: String): List<Entry> = synchronized(lock) {
        val a = read(dir)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter { it.optString("kind", "journal") == kind }
            .map { Entry(it.optString("id"), it.optString("kind", "journal"), it.optString("text"), it.optString("day"), it.optLong("at")) }
            .sortedByDescending { it.at }
    }

    fun add(dir: File, kind: String, text: String): Entry = synchronized(lock) {
        val clean = text.trim().take(4000)
        require(clean.isNotEmpty()) { "Nothing to write." }
        val k = if (kind == "study") "study" else "journal"
        val now = System.currentTimeMillis()
        val e = Entry("j" + now.toString(36) + (0..99).random(), k, clean, HomeStats.today(), now)
        val a = read(dir)
        a.put(JSONObject().put("id", e.id).put("kind", e.kind).put("text", e.text).put("day", e.day).put("at", e.at))
        write(dir, a)
        e
    }

    fun delete(dir: File, id: String) = synchronized(lock) {
        val a = read(dir)
        val keep = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("id") != id) keep.put(o)
        }
        write(dir, keep)
    }

    /** All of today's diary text joined, for the mood guess. */
    fun todayText(dir: File): String = list(dir, "journal").filter { it.day == HomeStats.today() }.joinToString("\n") { it.text }
}
