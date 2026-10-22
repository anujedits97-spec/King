package dev.kairo.myra

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Every permission Myraa uses, with Granted or Not granted, and a button that asks or opens the right Settings page. */
class PermissionsActivity : Activity() {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private lateinit var col: LinearLayout

    private class Row(val title: String, val why: String, val granted: () -> Boolean, val action: () -> Unit)

    private fun runtime(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun rows(): List<Row> {
        val asked = { p: String -> requestPermissions(arrayOf(p), 900) }
        val settings = { intent: Intent -> startActivity(intent) }
        val out = mutableListOf(
            Row("Microphone", "So you can talk to Myraa.", { runtime(Manifest.permission.RECORD_AUDIO) }) { asked(Manifest.permission.RECORD_AUDIO) },
            Row("Camera", "Only when you ask Myraa to look.", { runtime(Manifest.permission.CAMERA) }) { asked(Manifest.permission.CAMERA) },
            Row("Phone calls", "To place calls you ask for.", { runtime(Manifest.permission.CALL_PHONE) }) { asked(Manifest.permission.CALL_PHONE) },
            Row("Contacts", "To find a name's number.", { runtime(Manifest.permission.READ_CONTACTS) }) { asked(Manifest.permission.READ_CONTACTS) },
            Row("Location", "For distance, navigation and SOS location.", { runtime(Manifest.permission.ACCESS_FINE_LOCATION) }) { asked(Manifest.permission.ACCESS_FINE_LOCATION) },
            Row("Texts (SMS)", "To send and read texts you ask for.", { runtime(Manifest.permission.SEND_SMS) }) { asked(Manifest.permission.SEND_SMS) },
            Row("Calendar", "To tell you what is coming up.", { runtime(Manifest.permission.READ_CALENDAR) }) { asked(Manifest.permission.READ_CALENDAR) },
            Row("Notification access", "To read notifications and run triggers.", { notificationAccess() }) { settings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            Row("Display over other apps", "So the glow and orb can show on top.", { Settings.canDrawOverlays(this) }) {
                settings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            Row("No battery optimisation", "So Myraa keeps running with the screen off.", { batteryExempt() }) {
                settings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            },
        )
        if (Build.VERSION.SDK_INT >= 33) out.add(Row("Notifications", "So Myraa can show its own notifications.", { runtime(Manifest.permission.POST_NOTIFICATIONS) }) { asked(Manifest.permission.POST_NOTIFICATIONS) })
        return out
    }

    private fun notificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return flat.contains(packageName)
    }

    private fun batteryExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val root = ScrollView(this).apply { setBackgroundColor(Palette.BG); addView(col) }
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::col.isInitialized) render()
    }

    private fun render() {
        col.removeAllViews()
        col.addView(text("Permissions", 24f, Palette.INK, bold = true))
        col.addView(text("Allow only what you want. Each one works without the others, but its feature stays off until it is granted.", 14f, Palette.DIM))
        for (r in rows()) {
            val ok = r.granted()
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Palette.CARD)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            box.addView(text(r.title + "   " + (if (ok) "Granted" else "Not granted"), 16f, if (ok) Palette.GOOD else Palette.BAD, bold = true))
            box.addView(text(r.why, 13f, Palette.DIM))
            if (!ok) box.addView(Button(this).apply {
                text = "Allow"
                setTextColor(Color.WHITE)
                setBackgroundColor(Palette.ORANGE)
                setOnClickListener { r.action() }
            })
            col.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(10), 0, 0) })
        }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        gravity = Gravity.START
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }
}
