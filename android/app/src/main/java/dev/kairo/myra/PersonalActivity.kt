package dev.kairo.myra

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.kairo.myra.brain.LocalBrain

/** Personal: how Myraa addresses you, the gender used for Hindi verbs, and your own number (optional). */
class PersonalActivity : Activity() {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val env get() = LocalBrain.get(applicationContext, Prefs(applicationContext)).env

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = env.conf.obj("personal")
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val root = ScrollView(this).apply { setBackgroundColor(Palette.BG); addView(col) }

        col.addView(label("Personal", 24f, Palette.INK, bold = true))
        col.addView(label("Your name", 14f, Palette.INK, bold = true))
        val name = field("How Myraa should address you").apply { setText(saved?.optString("name").orEmpty()) }
        col.addView(name)

        col.addView(label("You are (for Hindi verbs)", 14f, Palette.INK, bold = true))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val options = listOf("male" to "Male", "female" to "Female", "" to "Prefer not to say")
        val current = saved?.optString("gender").orEmpty()
        options.forEach { (value, text) ->
            group.addView(RadioButton(this).apply {
                this.text = text
                setTextColor(Palette.INK)
                id = View.generateViewId()
                tag = value
                isChecked = value == current
            })
        }
        col.addView(group)

        col.addView(label("Your phone number (optional)", 14f, Palette.INK, bold = true))
        val phone = field("e.g. +91 98765 43210").apply { setText(saved?.optString("phone").orEmpty()) }
        col.addView(phone)

        col.addView(Button(this).apply {
            text = "Save"
            setTextColor(Color.WHITE)
            setBackgroundColor(Palette.ORANGE)
            setOnClickListener {
                val picked = group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag?.toString().orEmpty()
                val num = phone.text.toString().trim()
                if (num.isNotEmpty() && Sos.cleanNumber(num) == null) {
                    Toast.makeText(this@PersonalActivity, "That phone number looks wrong.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val root2 = org.json.JSONObject(env.conf.root.toString())
                root2.put("personal", org.json.JSONObject()
                    .put("name", name.text.toString().trim().take(40))
                    .put("gender", picked)
                    .put("phone", num))
                env.conf.root = root2
                env.conf.save()
                Toast.makeText(this@PersonalActivity, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(20), 0, 0) })

        setContentView(root)
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        setTextColor(Palette.INK)
        setHintTextColor(Palette.FAINT)
        setBackgroundColor(Palette.FIELD)
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        gravity = Gravity.START
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(12), 0, dp(4))
    }
}
