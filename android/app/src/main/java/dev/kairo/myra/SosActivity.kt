package dev.kairo.myra

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * SOS settings: on/off, the contacts who get the text (one per line: "name, number" or just "number"),
 * and the message. Five quick power presses trigger it; it can be cancelled during the countdown.
 */
class SosActivity : Activity() {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val root = ScrollView(this).apply { setBackgroundColor(Palette.BG); addView(col) }

        col.addView(label("Emergency SOS", 24f, Palette.INK, bold = true))
        col.addView(label("Press the power button 5 times quickly. A 5-second countdown starts, and you can cancel from the notification. If you don't cancel, your contacts get a text with your last known location.", 14f, Palette.DIM))

        val enable = Switch(this).apply {
            text = "SOS is on"
            setTextColor(Palette.INK)
            isChecked = Sos.enabled(this@SosActivity)
        }
        col.addView(enable, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(16), 0, 0) })

        col.addView(label("Contacts (one per line: name, number)", 14f, Palette.INK, bold = true))
        val contacts = EditText(this).apply {
            setTextColor(Palette.INK)
            setHintTextColor(Palette.FAINT)
            setBackgroundColor(Palette.FIELD)
            hint = "Mom, +919876543210\nBrother, 9123456789"
            minLines = 3
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setText(Sos.contacts(this@SosActivity).joinToString("\n") { (n, num) -> if (n.isEmpty()) num else "$n, $num" })
        }
        col.addView(contacts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        col.addView(label("Message", 14f, Palette.INK, bold = true))
        val msg = EditText(this).apply {
            setTextColor(Palette.INK)
            setHintTextColor(Palette.FAINT)
            setBackgroundColor(Palette.FIELD)
            hint = "I need help. This is an SOS from my phone."
            minLines = 2
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setText(Sos.load(this@SosActivity).optString("message"))
        }
        col.addView(msg, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        col.addView(Button(this).apply {
            text = "Save"
            setTextColor(Color.WHITE)
            setBackgroundColor(Palette.ORANGE)
            setOnClickListener {
                val list = ArrayList<Pair<String, String>>()
                var bad = 0
                for (line in contacts.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }) {
                    val parts = line.split(",", limit = 2).map { it.trim() }
                    val name = if (parts.size == 2) parts[0] else ""
                    val num = Sos.cleanNumber(parts.last())
                    if (num == null) bad++ else list.add(name to num)
                }
                if (bad > 0) {
                    Toast.makeText(this@SosActivity, "$bad number(s) look wrong. Use digits, with +country code if you like.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (enable.isChecked && list.isEmpty()) {
                    Toast.makeText(this@SosActivity, "Add at least one contact before turning SOS on.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                Sos.save(this@SosActivity, enable.isChecked, list, msg.text.toString().trim())
                Toast.makeText(this@SosActivity, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(20), 0, 0) })

        col.addView(label("Texts use your own SIM and may cost your normal SMS charges. Test it only with a contact who knows.", 12f, Palette.DIM).apply { setPadding(0, dp(16), 0, 0) })

        setContentView(root)
    }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        gravity = Gravity.START
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(8), 0, dp(4))
    }
}
