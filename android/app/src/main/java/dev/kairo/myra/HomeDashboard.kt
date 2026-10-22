package dev.kairo.myra

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.location.Location
import android.location.LocationManager
import android.provider.CalendarContract
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.kairo.myra.brain.LocalBrain
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The home dashboard on the main screen:
 *  - [greeting]: "Good evening, Anuj" and the energy chip (day streak).
 *  - [panel]: four quick buttons (Music, Study, Journal, Scan) and three small cards (Weather, Today, Mood).
 *
 * Everything shown is on this phone or fetched on demand: the weather comes from Open-Meteo (no key), the next event from
 * the phone's calendar (only if you allowed it), the mood from your own pick or a rough guess from your diary words.
 * [ask] sends a sentence to Myraa, the same as typing it in the question box.
 */
class HomeDashboard(
    private val act: Activity,
    private val prefs: Prefs,
    private val ask: (String) -> Unit,
) {
    private val dp = act.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private class Mini(val box: LinearLayout, val value: TextView, val sub: TextView)

    private val hello = text("", 14f, Palette.DIM)
    private val nameView = text("", 28f, Palette.INK, medium).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val energyValue = text("⚡ 1", 17f, Palette.ORANGE, medium)

    private val weatherCard = mini("Weather")
    private val todayCard = mini("Today")
    private val moodCard = mini("Mood")

    @Volatile private var fetching = false

    val greeting: View = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val names = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(hello)
            addView(nameView)
        }
        addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val energy = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(px(14), px(7), px(14), px(7))
            background = rounded(Palette.ORANGE_DEEP, 100f, Palette.ORANGE_EDGE)
            contentDescription = "Energy and day streak. Tap for details"
            isClickable = true
            addView(energyValue)
            addView(text("day streak", 10.5f, Palette.DIM).apply { gravity = Gravity.CENTER })
            setOnClickListener { showEnergy() }
        }
        addView(energy)
    }

    val panel: View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val defs = listOf(
            Triple("🎵", "Music", { music() }),
            Triple("📚", "Study", { open("study") }),
            Triple("📓", "Journal", { open("journal") }),
            Triple("📷", "Scan", { act.startActivity(Intent(act, ScanActivity::class.java)) }),
        )
        defs.forEachIndexed { i, (icon, label, go) ->
            chips.addView(chip(icon, label, go), LinearLayout.LayoutParams(0, px(70), 1f).apply { if (i > 0) marginStart = px(8) })
        }
        addView(chips)
        val cards = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; minimumHeight = px(104) }
        cards.addView(weatherCard.box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        cards.addView(todayCard.box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = px(8) })
        cards.addView(moodCard.box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = px(8) })
        addView(cards, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
    }

    init {
        weatherCard.box.setOnClickListener { editCity() }
        todayCard.box.setOnClickListener { showEvents() }
        moodCard.box.setOnClickListener { pickMood() }
        moodCard.box.setOnLongClickListener { moodInfo(); true }
    }

    /** Call when the screen comes back, and whenever something it shows may have changed (a permission, a setting). */
    fun refresh() {
        HomeStats.touch(act)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        hello.text = when {
            hour < 5 -> "Still up?"
            hour < 12 -> "Good morning,"
            hour < 17 -> "Good afternoon,"
            hour < 21 -> "Good evening,"
            else -> "Good night,"
        }
        val name = runCatching {
            LocalBrain.get(act.applicationContext, prefs).env.conf.obj("personal")?.optString("name")?.trim().orEmpty()
        }.getOrDefault("")
        nameView.text = name.ifEmpty { "there" }
        energyValue.text = "⚡ " + HomeStats.streak(act).coerceAtLeast(1)
        showMood()
        loadToday()
        loadWeather()
    }

    /* ── quick buttons ─────────────────────────────────────────────────── */

    private fun music() {
        val m = HomeStats.mood(act)
        val mood = if (m.label == "Not set") "mood nahi bataya, koi achha popular gaana chunao" else "aaj ka mood: ${m.label}"
        ask(
            "Mere liye gaana chala do ($mood). Koi music app (YouTube Music, Spotify, JioSaavn) installed ho to wahi kholo, " +
                "warna youtube_search se gaana chalao. Ek chhoti line me batao kya chala rahe ho.",
        )
    }

    private fun open(kind: String) {
        act.startActivity(Intent(act, JournalActivity::class.java).putExtra(JournalActivity.EXTRA_KIND, kind))
    }

    /* ── energy ────────────────────────────────────────────────────────── */

    private fun showEnergy() {
        AlertDialog.Builder(act)
            .setTitle("⚡ Energy")
            .setMessage(
                "Streak: ${HomeStats.streak(act).coerceAtLeast(1)} din lagataar Myraa kholna.\n" +
                    "Sabse lamba streak: ${maxOf(HomeStats.best(act), 1)} din.\n" +
                    "Points: ${HomeStats.points(act)}\n\n" +
                    "Har din app kholne par 1 point, aur har diary ya study note, focus timer ya scan par 1 point. " +
                    "Ye bas ek chhota khel hai. Isse kuch lock ya unlock nahi hota.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    /* ── weather ───────────────────────────────────────────────────────── */

    private fun source() = HomeStats.city(act).trim().lowercase(Locale.ROOT).ifEmpty { "gps" }

    private fun loadWeather() {
        val (cached, at) = HomeStats.weatherCache(act)
        val usable = cached.isNotEmpty() && runCatching { JSONObject(cached).optString("src") == source() }.getOrDefault(false)
        if (usable) showWeather(cached) else showWeatherEmpty("tap to set your city")
        val fresh = usable && System.currentTimeMillis() - at < 30 * 60_000L
        if (fresh || fetching) return
        fetching = true
        val src = source()
        Thread {
            try {
                val here = resolvePlace()
                if (here == null) {
                    act.runOnUiThread { if (!usable) showWeatherEmpty("tap to set your city") }
                    return@Thread
                }
                val url = "https://api.open-meteo.com/v1/forecast?latitude=${here.lat}&longitude=${here.lon}" +
                    "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
                val o = JSONObject(http(url))
                val cur = o.getJSONObject("current")
                val daily = o.getJSONObject("daily")
                val res = JSONObject()
                    .put("src", src)
                    .put("place", here.name)
                    .put("t", Math.round(cur.getDouble("temperature_2m")).toInt())
                    .put("code", cur.getInt("weather_code"))
                    .put("hi", Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt())
                    .put("lo", Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt())
                HomeStats.setWeatherCache(act, res.toString())
                act.runOnUiThread { showWeather(res.toString()) }
            } catch (e: Exception) {
                act.runOnUiThread { if (!usable) showWeatherEmpty("offline, tap to retry") }
            } finally {
                fetching = false
            }
        }.start()
    }

    private class Spot(val lat: Double, val lon: Double, val name: String)

    /** The city you typed, or else the phone's last known location (only if you allowed it). Runs on a background thread. */
    private fun resolvePlace(): Spot? {
        val city = HomeStats.city(act).trim()
        if (city.isNotEmpty()) {
            val geo = JSONObject(http("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=" + URLEncoder.encode(city, "UTF-8")))
            val hit = geo.optJSONArray("results")?.optJSONObject(0) ?: return null
            return Spot(hit.getDouble("latitude"), hit.getDouble("longitude"), hit.optString("name", city))
        }
        if (act.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = act.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } ?: continue
            if (best == null || l.time > best.time) best = l
        }
        val b = best ?: return null
        return Spot(b.latitude, b.longitude, "Near you")
    }

    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun showWeather(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        weatherCard.value.text = "${o.optInt("t")}°C"
        weatherCard.value.setTextColor(Palette.INK)
        weatherCard.sub.text = "${wmo(o.optInt("code", -1))}\n${o.optInt("lo")}° to ${o.optInt("hi")}°"
    }

    private fun showWeatherEmpty(note: String) {
        weatherCard.value.text = "—"
        weatherCard.value.setTextColor(Palette.DIM)
        weatherCard.sub.text = note
    }

    private fun wmo(c: Int) = when (c) {
        0 -> "Clear sky"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"
        45, 48 -> "Fog"; 51, 53, 55, 56, 57 -> "Drizzle"
        61, 80 -> "Light rain"; 63, 81 -> "Rain"; 65, 82, 66, 67 -> "Heavy rain"
        71, 73, 75, 77, 85, 86 -> "Snow"; 95, 96, 99 -> "Thunderstorm"
        else -> "Weather"
    }

    private fun editCity() {
        val box = EditText(act).apply {
            setText(HomeStats.city(act))
            hint = "City, e.g. Pune"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        val pad = LinearLayout(act).apply { setPadding(px(22), px(8), px(22), 0); addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(act)
            .setTitle("Weather ke liye city")
            .setView(pad)
            .setPositiveButton("Save") { _, _ ->
                HomeStats.setCity(act, box.text.toString())
                loadWeather()
            }
            .setNeutralButton("Meri location") { _, _ ->
                HomeStats.setCity(act, "")
                val granted = act.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!granted) act.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION) else loadWeather()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* ── today and the calendar ────────────────────────────────────────── */

    private class Ev(val title: String, val begin: Long, val end: Long, val allDay: Boolean)

    private fun hasCalendar() = act.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun events(hours: Int, max: Int): List<Ev> {
        if (!hasCalendar()) return emptyList()
        val from = System.currentTimeMillis()
        val to = from + hours * 3_600_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val cols = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY)
        val out = ArrayList<Ev>()
        try {
            act.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && out.size < max) {
                    out.add(Ev((c.getString(0) ?: "").trim().ifEmpty { "(no title)" }.take(60), c.getLong(1), c.getLong(2), c.getInt(3) == 1))
                }
            }
        } catch (e: Exception) { /* a broken calendar app must not break the home screen */ }
        return out
    }

    private fun loadToday() {
        val now = Date()
        todayCard.value.text = SimpleDateFormat("d MMM", Locale.getDefault()).format(now)
        val weekday = SimpleDateFormat("EEEE", Locale.getDefault()).format(now)
        if (!hasCalendar()) { todayCard.sub.text = "$weekday\ntap to show events"; return }
        Thread {
            val next = events(48, 1).firstOrNull()
            val clock = SimpleDateFormat("h:mm a", Locale.getDefault())
            val line = when {
                next == null -> "$weekday\nnothing planned"
                next.allDay -> "All day\n${next.title}"
                else -> "${clock.format(Date(next.begin))}\n${next.title}"
            }
            act.runOnUiThread { todayCard.sub.text = line }
        }.start()
    }

    private fun showEvents() {
        if (!hasCalendar()) {
            act.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ_CALENDAR)
            return
        }
        Thread {
            val list = events(72, 12)
            val day = SimpleDateFormat("EEE d MMM", Locale.getDefault())
            val clock = SimpleDateFormat("h:mm a", Locale.getDefault())
            val msg = if (list.isEmpty()) "Agle 3 din me kuch planned nahi hai." else list.joinToString("\n\n") {
                val d = Date(it.begin)
                "${it.title}\n${day.format(d)}, " + if (it.allDay) "all day" else clock.format(d)
            }
            act.runOnUiThread { AlertDialog.Builder(act).setTitle("Aane wale events").setMessage(msg).setPositiveButton("OK", null).show() }
        }.start()
    }

    /* ── mood ──────────────────────────────────────────────────────────── */

    private fun showMood() {
        val m = HomeStats.mood(act)
        moodCard.value.text = m.label
        moodCard.value.setTextColor(if (m.label == "Not set") Palette.DIM else Palette.INK)
        moodCard.sub.text = m.note
    }

    private fun pickMood() {
        val items = HomeStats.MOODS + listOf("Clear my pick", "How does this work?")
        AlertDialog.Builder(act)
            .setTitle("Aaj kaisa lag raha hai?")
            .setItems(items.toTypedArray<CharSequence>()) { _, which ->
                when {
                    which < HomeStats.MOODS.size -> { HomeStats.setMood(act, HomeStats.MOODS[which]); showMood() }
                    which == HomeStats.MOODS.size -> { HomeStats.setMood(act, null); showMood() }
                    else -> moodInfo()
                }
            }
            .show()
    }

    private fun moodInfo() {
        AlertDialog.Builder(act)
            .setTitle("Mood")
            .setMessage(
                "Ye sirf aapka apna label hai, aaj ka din kaisa lag raha hai. Aap khud chuno. Na chuno to aaj ki diary ke shabdon se halka sa andaaza lagta hai " +
                    "(phone par hi, bina AI ke). Ye koi medical ya mental-health ki jaanch nahi hai. Agar aap sach me bahut pareshan ho, kisi apne se ya doctor se baat karo.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    /* ── small view helpers ────────────────────────────────────────────── */

    private fun text(s: String, sp: Float, color: Int, face: Typeface? = null): TextView = TextView(act).apply {
        text = s
        setTextColor(color)
        textSize = sp
        if (face != null) typeface = face
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = radiusDp * dp
        setColor(fill)
        if (stroke != 0) setStroke(maxOf(1, px(1)), stroke)
    }

    private fun ripple(content: GradientDrawable, radiusDp: Float) =
        RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), content, rounded(0xFFFFFFFF.toInt(), radiusDp))

    private fun chip(icon: String, label: String, go: () -> Unit) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = ripple(rounded(Palette.CARD, 18f, Palette.LINE), 18f)
        isClickable = true
        isFocusable = true
        contentDescription = label
        addView(text(icon, 22f, Palette.INK).apply { gravity = Gravity.CENTER })
        addView(text(label, 12.5f, Palette.INK, medium).apply { gravity = Gravity.CENTER })
        setOnClickListener { go() }
    }

    private fun mini(title: String): Mini {
        val value = text("—", 20f, Palette.INK, medium).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val sub = text("", 11.5f, Palette.DIM).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(10), px(12), px(10))
            background = ripple(rounded(Palette.CARD, 18f, Palette.LINE), 18f)
            isClickable = true
            isFocusable = true
            addView(text(title, 11.5f, Palette.DIM))
            addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(2) })
            addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(1) })
        }
        return Mini(box, value, sub)
    }

    companion object {
        /** Permission request codes. 5 is also what Settings uses for the calendar, so MainActivity already refreshes after it. */
        const val REQ_CALENDAR = 5
        const val REQ_LOCATION = 9
    }
}
