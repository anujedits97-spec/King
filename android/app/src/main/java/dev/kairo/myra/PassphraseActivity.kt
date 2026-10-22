package dev.kairo.myra

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.kairo.myra.brain.LocalBrain
import dev.kairo.myra.brain.VoicePass

/** Sets the voice passphrase. It is typed here (not spoken), stored only as a hash, and never shown again. */
class PassphraseActivity : Activity() {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        col.addView(label("Voice passphrase", 24f, Palette.INK, bold = true))
        col.addView(label("Sensitive actions (sending messages, deleting, deploying, creating issues) ask for this phrase first. Use a few simple words you can say clearly. It is stored only as a hash, and you can change it here any time.", 14f, Palette.DIM))

        val pass = field("Passphrase (at least 6 characters)")
        val again = field("Type it again")
        col.addView(pass)
        col.addView(again)

        col.addView(Button(this).apply {
            text = "Save"
            setTextColor(Color.WHITE)
            setBackgroundColor(Palette.ORANGE)
            setOnClickListener {
                val a = pass.text.toString()
                if (a != again.text.toString()) {
                    Toast.makeText(this@PassphraseActivity, "The two passphrases do not match.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                try {
                    val env = LocalBrain.get(applicationContext, Prefs(applicationContext)).env
                    VoicePass.set(env, a)
                    Toast.makeText(this@PassphraseActivity, "Passphrase saved.", Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(this@PassphraseActivity, e.message ?: "Could not save.", Toast.LENGTH_LONG).show()
                }
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(20), 0, 0) })

        setContentView(col)
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        setTextColor(Palette.INK)
        setHintTextColor(Palette.FAINT)
        setBackgroundColor(Palette.FIELD)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(12), 0, 0) }
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
