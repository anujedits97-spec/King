package dev.kairo.myra

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Yaadein": everything Myraa has remembered about you, on this phone. You can delete one memory or all of them.
 * The file is the same one the brain uses (myra/memories.json). Nothing here is sent anywhere.
 */
class MemoriesActivity : Activity() {
    private lateinit var col: LinearLayout

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun file() = File(filesDir, "myra/memories.json")

    private fun load(): JSONArray = try {
        if (file().exists()) JSONArray(file().readText()) else JSONArray()
    } catch (e: Exception) { JSONArray() }

    private fun save(a: JSONArray) {
        file().parentFile?.mkdirs()
        file().writeText(a.toString(1))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = ScrollView(this).apply { setBackgroundColor(BG) }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        root.addView(col)
        setContentView(root)
        render()
    }

    private fun render() {
        col.removeAllViews()
        col.addView(text("Yaadein", 24f, INK, bold = true))
        col.addView(text("What Myraa remembers about you. It lives only on this phone.", 14f, DIM))
        val items = load()
        val list = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.reversed()
        if (list.isEmpty()) {
            col.addView(text("Nothing remembered yet.", 16f, DIM).apply { setPadding(0, dp(24), 0, 0) })
            return
        }
        for (m in list) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(CARD)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(10), 0, 0)
            card.addView(text(m.optString("text"), 16f, INK))
            val meta = "${m.optString("category", "context")}" +
                (if (m.optString("importance", "medium") != "medium") ", ${m.optString("importance")}" else "") +
                " · ${m.optString("at")}"
            card.addView(text(meta, 12f, DIM))
            card.addView(Button(this).apply {
                text = "Delete"
                setTextColor(ORANGE)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    val keep = JSONArray()
                    val all = load()
                    for (i in 0 until all.length()) {
                        val o = all.optJSONObject(i) ?: continue
                        if (o.optString("id") != m.optString("id")) keep.put(o)
                    }
                    save(keep)
                    render()
                }
            })
            col.addView(card, lp)
        }
        val clear = Button(this).apply {
            text = "Delete all memories"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFFB3261E.toInt())
            setOnClickListener {
                AlertDialog.Builder(this@MemoriesActivity)
                    .setTitle("Delete everything?")
                    .setMessage("Myraa will forget all remembered facts. This cannot be undone.")
                    .setPositiveButton("Delete all") { _, _ -> save(JSONArray()); render() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        col.addView(clear, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(24), 0, 0) })
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        gravity = Gravity.START
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }

    private val BG = Palette.BG
    private val CARD = Palette.CARD
    private val INK = Palette.INK
    private val DIM = Palette.DIM
    private val ORANGE = Palette.ORANGE
}
