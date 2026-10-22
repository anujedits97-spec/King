package dev.kairo.myra

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Journal chip and the Study chip on the home screen.
 *  kind "journal": a private diary. Type, or speak, and save. Entries are listed by day.
 *  kind "study":   study notes, plus a focus timer (15, 25 or 45 minutes) that uses Android's own timer.
 * Entries live in myra/journal.json on this phone (see JournalStore), the same file the Myraa tools use,
 * so "diary mein likho ..." by voice shows up here too. Nothing is sent anywhere.
 */
class JournalActivity : Activity() {
    private lateinit var col: LinearLayout
    private lateinit var box: EditText
    private lateinit var kind: String
    private val dir by lazy { File(filesDir, "myra") }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val study get() = kind == "study"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = if (intent.getStringExtra(EXTRA_KIND) == "study") "study" else "journal"
        val root = ScrollView(this).apply { setBackgroundColor(Palette.BG); isFillViewport = true }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(col)
        setContentView(root)
        // Keep the text field clear of the keyboard and the status bar.
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        build()
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    private lateinit var listBox: LinearLayout

    private fun build() {
        col.removeAllViews()
        col.addView(label(if (study) "Study" else "Journal", 26f, Palette.INK, bold = true))
        col.addView(label(
            if (study) "Notes for what you are learning, and a focus timer. Stays on this phone."
            else "Write how the day went, or speak it. Private, on this phone only.",
            14f, Palette.DIM,
        ))

        if (study) {
            col.addView(label("Focus timer", 16f, Palette.INK, bold = true).apply { setPadding(0, dp(18), 0, dp(6)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (m in listOf(15, 25, 45)) {
                row.addView(
                    chip("$m min") { startFocus(m) },
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (m != 15) marginStart = dp(10) },
                )
            }
            col.addView(row)
            col.addView(label("Starts Android's timer and silences nothing else. Put your phone down and learn.", 12.5f, Palette.FAINT))
        }

        col.addView(label(if (study) "Add a note" else "Today", 16f, Palette.INK, bold = true).apply { setPadding(0, dp(18), 0, dp(6)) })
        box = EditText(this).apply {
            hint = if (study) "What did you learn?" else "What happened today? How do you feel?"
            setHintTextColor(Palette.FAINT)
            setTextColor(Palette.INK)
            textSize = 16f
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = round(Palette.FIELD, 16f, Palette.LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(chip("🎙️ Speak") { speak() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        buttons.addView(
            chip("Save", primary = true) { save() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(10) },
        )
        col.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        col.addView(label(if (study) "Your study notes" else "Your diary", 16f, Palette.INK, bold = true).apply { setPadding(0, dp(24), 0, dp(4)) })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)
    }

    private fun renderList() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()
        val entries = JournalStore.list(dir, kind)
        if (entries.isEmpty()) {
            listBox.addView(label("Nothing here yet.", 15f, Palette.DIM).apply { setPadding(0, dp(8), 0, 0) })
            return
        }
        val clock = SimpleDateFormat("h:mm a", Locale.getDefault())
        var lastDay = ""
        for (e in entries) {
            if (e.day != lastDay) {
                lastDay = e.day
                listBox.addView(label(prettyDay(e.day), 13f, Palette.ORANGE, bold = true).apply { setPadding(0, dp(16), 0, dp(2)) })
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = round(Palette.CARD, 16f, Palette.LINE)
                setPadding(dp(14), dp(12), dp(14), dp(10))
            }
            card.addView(label(e.text, 15.5f, Palette.INK))
            val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            foot.addView(label(clock.format(Date(e.at)), 12f, Palette.DIM), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            foot.addView(label("Delete", 13f, Palette.BAD).apply {
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { confirmDelete(e) }
            })
            card.addView(foot)
            listBox.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    private fun prettyDay(day: String): String {
        if (day == HomeStats.today()) return "Today"
        return runCatching {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day)!!
            SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(d)
        }.getOrDefault(day)
    }

    private fun confirmDelete(e: JournalStore.Entry) {
        AlertDialog.Builder(this)
            .setTitle("Delete this entry?")
            .setMessage(e.text.take(120))
            .setPositiveButton("Delete") { _, _ -> JournalStore.delete(dir, e.id); renderList() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        val t = box.text.toString().trim()
        if (t.isEmpty()) { Toast.makeText(this, "Pehle kuch likho ya bolo.", Toast.LENGTH_SHORT).show(); return }
        JournalStore.add(dir, kind, t)
        HomeStats.bump(this)
        box.setText("")
        Toast.makeText(this, "Save ho gaya.", Toast.LENGTH_SHORT).show()
        renderList()
    }

    /* ── focus timer ───────────────────────────────────────────────────── */

    private fun startFocus(minutes: Int) {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Focus time khatam")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        try {
            startActivity(i)
            HomeStats.bump(this)
            Toast.makeText(this, "Focus timer shuru: $minutes min. Best of luck!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Timer nahi khula")
                .setMessage("Is phone ki clock app ne timer shuru nahi kiya. Aap Myraa se bol sakte ho: \"$minutes minute ka timer lagao\".")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /* ── speaking an entry ─────────────────────────────────────────────── */

    private fun speak() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs(this).language)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, if (study) "Bolo, kya seekha?" else "Bolo, diary me kya likhna hai?")
        try {
            startActivityForResult(i, REQ_SPEAK)
        } catch (e: ActivityNotFoundException) {
            askMyraToWrite()
        } catch (e: Exception) {
            askMyraToWrite()
        }
    }

    /** No speech screen on this phone: let Myraa listen and write the entry with its own tool. */
    private fun askMyraToWrite() {
        val ok = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && Settings.canDrawOverlays(this)
        if (!ok) {
            Toast.makeText(this, "Pehle Myraa app me microphone aur overlay allow karo, ya yahan type karo.", Toast.LENGTH_LONG).show()
            return
        }
        val ask = if (study) {
            "Mujhe study note likhwana hai. Pehle poochho 'boliye, kya seekha?', phir meri baat sunke journal_add se (kind study) save kar do."
        } else {
            "Mujhe aaj ki diary likhwani hai. Pehle poochho 'boliye, aaj kya hua?', phir meri baat sunke journal_add se (kind journal) save kar do."
        }
        MyraService.send(this, MyraService.ACTION_ASK, ask)
        Toast.makeText(this, "Myraa sun raha hai. Bolo.", Toast.LENGTH_LONG).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SPEAK || resultCode != RESULT_OK) return
        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (heard.isEmpty()) return
        val now = box.text.toString().trim()
        box.setText(if (now.isEmpty()) heard else "$now $heard")
        box.setSelection(box.text.length)
    }

    /* ── small view helpers ────────────────────────────────────────────── */

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun round(fill: Int, radiusDp: Float, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = radiusDp * resources.displayMetrics.density
        setColor(fill)
        if (stroke != 0) setStroke(maxOf(1, dp(1)), stroke)
    }

    private fun chip(text: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.parseColor("#1A0D00") else Palette.INK)
        background = if (primary) round(Palette.ORANGE, 100f) else round(0xFF1B1E24.toInt(), 100f, 0xFF2C3038.toInt())
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    companion object {
        const val EXTRA_KIND = "kind"
        private const val REQ_SPEAK = 71
    }
}
