package dev.kairo.myra

import android.app.Activity
import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Shows the screenshots Myraa sent from the laptop, full width. "Save" puts them in Photos. */
class ShotActivity : Activity() {
    private val dp by lazy { resources.displayMetrics.density }
    private val medium by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val files = intent.getStringArrayExtra(EXTRA_FILES).orEmpty().map(::File).filter { it.exists() }
        if (files.isEmpty()) { finish(); return }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(12), px(16), px(24)) }

        // Header: icon, title, when it arrived
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageDrawable(LineIcon(LineIcon.Kind.LAPTOP, ORANGE, px(20)))
                scaleType = ImageView.ScaleType.CENTER
                background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(0xFF2A1A0C.toInt()); setStroke(px(1), 0xFF4A2C10.toInt()) }
            }, LinearLayout.LayoutParams(px(40), px(40)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = if (files.size == 1) "From your laptop" else "From your laptop · ${files.size} screenshots"
                    setTextColor(INK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = medium
                })
                addView(TextView(context).apply {
                    text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(files.maxOf { it.lastModified() }))
                    setTextColor(DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12) })
        })

        // The screenshots, in rounded frames. Tap one to close.
        for (f in files) {
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
            col.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0xFF14161B.toInt()); setStroke(px(1), 0xFF23262D.toInt()) }
                clipToOutline = true
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16) })
        }

        // Buttons
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            if (Build.VERSION.SDK_INT >= 29) {
                addView(pill("Save to Photos", LineIcon.Kind.IMAGE, primary = true) { save(files) }, LinearLayout.LayoutParams(0, px(52), 1f))
            }
            addView(pill("Close", null, primary = false) { finish() }, LinearLayout.LayoutParams(0, px(52), 1f).apply { if (Build.VERSION.SDK_INT >= 29) marginStart = px(12) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(20) })

        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isVerticalScrollBarEnabled = false; addView(col) }
        setContentView(scroll)

        // Draw under the system bars and keep the content clear of them.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else scroll.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    private fun px(v: Int) = (v * dp).toInt()

    private fun pill(label: String, icon: LineIcon.Kind?, primary: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        val fg = if (primary) 0xFF1A0D00.toInt() else INK
        val fill = if (primary) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFFFA444.toInt(), 0xFFFF7A12.toInt()))
        else GradientDrawable().apply { setColor(0xFF1B1E24.toInt()); setStroke(px(1), 0xFF2C3038.toInt()) }
        fill.cornerRadius = 100 * dp
        val mask = GradientDrawable().apply { cornerRadius = 100 * dp; setColor(Color.WHITE) }
        background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x44FFFFFF else 0x22FFFFFF), fill, mask)
        if (icon != null) addView(ImageView(context).apply { setImageDrawable(LineIcon(icon, fg, px(20))) }, LinearLayout.LayoutParams(px(20), px(20)).apply { marginEnd = px(8) })
        addView(TextView(context).apply { text = label; setTextColor(fg); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = medium })
        setOnClickListener { onClick() }
    }

    private fun save(files: List<File>) {
        if (Build.VERSION.SDK_INT < 29) return
        var n = 0
        for (f in files) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "MYRA-${f.name}")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MYRA")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: continue
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            n++
        }
        Toast.makeText(this, "Saved $n to Photos › Myraa", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_FILES = "files"
        private const val BG = 0xFF0B0C10.toInt()
        private const val INK = 0xFFF2EFEA.toInt()
        private const val DIM = 0xFF8E919A.toInt()
        private const val ORANGE = 0xFFFF8A1F.toInt()
    }
}
