package dev.kairo.myra

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The Scan chip on the home screen: show Myraa a paper, a board, a bill or any thing with the camera.
 *  - "Padh ke batao": the pictures go to the brain on this phone and Myraa reads them out loud (like attaching a photo).
 *  - "Text nikalo": reads the words on the phone itself (ML Kit, offline, English and Hindi) so you can copy them or keep them as a study note.
 *  - "PDF banao": puts the pages into one PDF you can share.
 * Photos are taken by the phone's own camera app, so Myraa never needs to hold the camera. Nothing leaves the phone
 * except what you ask Myraa to read, which goes to the AI engine you chose.
 */
class ScanActivity : Activity() {
    private val pages = ArrayList<Uri>()
    private var pendingFile: File? = null
    private lateinit var strip: LinearLayout
    private lateinit var status: TextView
    private lateinit var question: EditText
    private lateinit var prefs: Prefs

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        savedInstanceState?.getStringArrayList("pages")?.forEach { runCatching { pages.add(Uri.parse(it)) } }
        savedInstanceState?.getString("pending")?.let { pendingFile = File(it) }
        val root = ScrollView(this).apply { setBackgroundColor(Palette.BG); isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(col)
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        col.addView(label("Scan", 26f, Palette.INK, bold = true))
        col.addView(label("Take a photo of a paper, bill, board or anything. Myraa can read it, copy its words, or make a PDF.", 14f, Palette.DIM))

        val pick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pick.addView(chip("📷 Photo lo", primary = true) { takePhoto() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        pick.addView(chip("🖼️ Gallery se") { pickImages() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(10) })
        col.addView(pick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })

        strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        question = EditText(this).apply {
            hint = "Kuch poochna hai? (optional) jaise: kitna total hai?"
            setHintTextColor(Palette.FAINT)
            setTextColor(Palette.INK)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = round(Palette.FIELD, 16f, Palette.LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        col.addView(question, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        col.addView(chip("🗣️ Padh ke batao", primary = true) { readAloud() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })
        val two = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        two.addView(chip("🔤 Text nikalo") { extractText() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        two.addView(chip("📄 PDF banao") { makePdf() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(10) })
        col.addView(two, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        col.addView(label("Saaf karo (sab pages hatao)", 13.5f, Palette.DIM).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(6))
            setOnClickListener { pages.clear(); renderStrip() }
        })
        status = label("", 13.5f, Palette.ORANGE).apply { gravity = Gravity.CENTER }
        col.addView(status)
        renderStrip()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("pages", ArrayList(pages.map { it.toString() }))
        pendingFile?.let { outState.putString("pending", it.absolutePath) }
    }

    /* ── getting pictures ──────────────────────────────────────────────── */

    private fun takePhoto() {
        // The camera app is opened for one photo. Because Myraa lists the camera permission, Android wants it granted first.
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERM)
            return
        }
        val dir = File(cacheDir, "scans").also { it.mkdirs() }
        val f = File(dir, "scan-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + pages.size + ".jpg")
        pendingFile = f
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(i, REQ_CAMERA)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera app nahi khul paya. Gallery se photo chuno.", Toast.LENGTH_LONG).show()
        }
    }

    private fun pickImages() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(i, REQ_PICK) } catch (e: Exception) {
            Toast.makeText(this, "Gallery nahi khuli.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA_PERM) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) takePhoto()
            else Toast.makeText(this, "Camera allow nahi hua. Gallery se photo chun sakte ho.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_CAMERA -> {
                val f = pendingFile
                if (f != null && f.exists() && f.length() > 0) {
                    pages.add(FileProvider.getUriForFile(this, "$packageName.files", f))
                    renderStrip()
                }
            }
            REQ_PICK -> {
                val clip = data?.clipData
                if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { pages.add(it) }
                else data?.data?.let { pages.add(it) }
                renderStrip()
            }
        }
    }

    private fun renderStrip() {
        strip.removeAllViews()
        if (pages.isEmpty()) {
            strip.addView(label("No pages yet. Take a photo to start.", 14f, Palette.FAINT))
            return
        }
        pages.forEachIndexed { index, uri ->
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = round(Palette.CARD, 12f, Palette.LINE)
                contentDescription = "Page ${index + 1}. Tap to remove"
                setOnClickListener {
                    AlertDialog.Builder(this@ScanActivity)
                        .setTitle("Page ${index + 1}")
                        .setMessage("Is page ko hata dun?")
                        .setPositiveButton("Hatao") { _, _ -> pages.remove(uri); renderStrip() }
                        .setNegativeButton("Rehne do", null)
                        .show()
                }
            }
            strip.addView(iv, LinearLayout.LayoutParams(dp(90), dp(120)).apply { if (index > 0) marginStart = dp(10) })
            Thread {
                val bmp = thumb(uri)
                runOnUiThread { if (bmp != null) iv.setImageBitmap(bmp) }
            }.start()
        }
    }

    private fun thumb(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 320) sample *= 2
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    } catch (e: Exception) { null }

    private fun needPages(): Boolean {
        if (pages.isEmpty()) { Toast.makeText(this, "Pehle photo lo ya gallery se chuno.", Toast.LENGTH_SHORT).show(); return true }
        return false
    }

    private fun say(s: String) { runOnUiThread { status.text = s } }

    /* ── 1. Myraa reads it ────────────────────────────────────────────── */

    private fun readAloud() {
        if (needPages()) return
        if (prefs.activeMode != "phone") {
            AlertDialog.Builder(this).setTitle("Phone brain chahiye")
                .setMessage("Photo padhna tab chalta hai jab Myraa \"This phone\" par sochta hai (Where Myraa thinks → This phone). Tab tak \"Text nikalo\" ya \"PDF banao\" use karo.")
                .setPositiveButton("OK", null).show()
            return
        }
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && Settings.canDrawOverlays(this)
        if (!micOk) {
            Toast.makeText(this, "Myraa app me pehle microphone aur overlay allow karo.", Toast.LENGTH_LONG).show()
            return
        }
        val use = pages.take(MAX_SEND)
        val extra = if (pages.size > use.size) " Sirf pehle ${use.size} pages bheje." else ""
        val ask = question.text.toString().trim()
        say("Pictures Myraa ko bhej rahi hoon…")
        Thread {
            try {
                val client = BrainClient(prefs)
                val paths = use.mapIndexed { i, uri ->
                    client.attachBlocking("scan-${i + 1}.jpg", "image/jpeg", Documents.imageJpeg(this, uri)).optString("path")
                }
                val doc = Doc("Scan", "", false, images = paths, kind = if (paths.size == 1) "image" else "pages")
                val text = ask.ifEmpty { DEFAULT_QUESTION }
                runOnUiThread {
                    MyraService.send(this, MyraService.ACTION_ASK, text, doc)
                    HomeStats.bump(this)
                    Toast.makeText(this, "Myraa padh raha hai.$extra", Toast.LENGTH_LONG).show()
                    finish()
                }
            } catch (e: Exception) {
                say("")
                runOnUiThread { AlertDialog.Builder(this).setTitle("Bhej nahi paya").setMessage(e.message ?: "Phir se try karo.").setPositiveButton("OK", null).show() }
            }
        }.start()
    }

    /* ── 2. the words on the page, read on the phone ───────────────────── */

    private fun extractText() {
        if (needPages()) return
        say("Words padh rahi hoon…")
        Thread {
            val out = StringBuilder()
            val latin = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
            val hindi = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions.Builder().build())
            val devanagari = Regex("[\\u0900-\\u097F]")
            try {
                pages.forEachIndexed { index, uri ->
                    val bytes = try { Documents.imageJpeg(this, uri, 2000) } catch (e: Exception) { return@forEachIndexed }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@forEachIndexed
                    val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)
                    val lines = ArrayList<String>()
                    for ((client, isHindi) in listOf(latin to false, hindi to true)) {
                        val result = try { com.google.android.gms.tasks.Tasks.await(client.process(image), 25, TimeUnit.SECONDS) } catch (e: Exception) { null } ?: continue
                        for (block in result.textBlocks) for (line in block.lines) {
                            val t = line.text.trim()
                            if (t.isEmpty()) continue
                            if (isHindi && !devanagari.containsMatchIn(t)) continue // the English reader already has these lines
                            lines.add(t)
                        }
                    }
                    bmp.recycle()
                    if (lines.isNotEmpty()) {
                        if (pages.size > 1) out.append("— Page ${index + 1} —\n")
                        out.append(lines.joinToString("\n")).append("\n\n")
                    }
                }
            } finally {
                runCatching { latin.close() }
                runCatching { hindi.close() }
            }
            val text = out.toString().trim()
            say("")
            runOnUiThread { showText(text) }
        }.start()
    }

    private fun showText(text: String) {
        if (text.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Koi text nahi mila")
                .setMessage("Photo saaf aur seedhi lo, roshni me. Ya \"Padh ke batao\" try karo, wo tasveer ko samajhta hai.")
                .setPositiveButton("OK", null).show()
            return
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Page ka text")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("scan", text))
                Toast.makeText(this, "Copy ho gaya.", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Study note me") { _, _ ->
                JournalStore.add(File(filesDir, "myra"), "study", text.take(3900))
                HomeStats.bump(this)
                Toast.makeText(this, "Study notes me save ho gaya.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Band", null)
            .show()
    }

    /* ── 3. one PDF ────────────────────────────────────────────────────── */

    private fun makePdf() {
        if (needPages()) return
        say("PDF ban raha hai…")
        Thread {
            try {
                val dir = (getExternalFilesDir(null) ?: filesDir).let { File(it, "Scans") }.also { it.mkdirs() }
                val file = File(dir, "scan-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".pdf")
                val pdf = PdfDocument()
                try {
                    pages.forEachIndexed { index, uri ->
                        val bytes = Documents.imageJpeg(this, uri, 2000)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Page ${index + 1} padh nahi paya.")
                        val info = PdfDocument.PageInfo.Builder(A4_W, A4_H, index + 1).create()
                        val page = pdf.startPage(info)
                        page.canvas.drawColor(Color.WHITE)
                        // Fit the picture inside the page with a small margin, keeping its shape.
                        val maxW = A4_W - 2 * MARGIN
                        val maxH = A4_H - 2 * MARGIN
                        val scale = minOf(maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height)
                        val w = (bmp.width * scale).toInt()
                        val h = (bmp.height * scale).toInt()
                        val left = (A4_W - w) / 2
                        val top = (A4_H - h) / 2
                        page.canvas.drawBitmap(bmp, null, Rect(left, top, left + w, top + h), null)
                        pdf.finishPage(page)
                        bmp.recycle()
                    }
                    FileOutputStream(file).use { pdf.writeTo(it) }
                } finally {
                    pdf.close()
                }
                val copy = copyToDocuments(file)
                HomeStats.bump(this)
                say("")
                runOnUiThread { pdfDone(file, copy) }
            } catch (e: Exception) {
                say("")
                runOnUiThread { AlertDialog.Builder(this).setTitle("PDF nahi bana").setMessage(e.message ?: "Phir se try karo.").setPositiveButton("OK", null).show() }
            }
        }.start()
    }

    /** With "All files access" on, also put a copy in Documents/MYRA-Scans where file managers show it. Quietly skipped otherwise. */
    private fun copyToDocuments(src: File): File? = try {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "MYRA-Scans").also { it.mkdirs() }
            File(d, src.name).also { src.copyTo(it, overwrite = true) }
        } else null
    } catch (e: Exception) { null }

    private fun pdfDone(file: File, copy: File?) {
        val shown = copy ?: file
        val where = if (copy != null) "Documents/MYRA-Scans/${copy.name}" else "Myraa app folder (Scans/${file.name}). Share karke kahin bhi save kar sakte ho."
        AlertDialog.Builder(this)
            .setTitle("PDF ban gaya")
            .setMessage("${pages.size} page${if (pages.size == 1) "" else "s"}.\n\n$where")
            .setPositiveButton("Share") { _, _ -> sendPdf(shown, share = true) }
            .setNeutralButton("Kholo") { _, _ -> sendPdf(shown, share = false) }
            .setNegativeButton("Band", null)
            .show()
    }

    private fun sendPdf(f: File, share: Boolean) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        try {
            if (share) {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "PDF bhejo",
                ))
            } else {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Koi app nahi mili. File: ${f.name}", Toast.LENGTH_LONG).show()
        }
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
        private const val REQ_CAMERA = 81
        private const val REQ_PICK = 82
        private const val REQ_CAMERA_PERM = 83
        private const val MAX_SEND = 6
        private const val A4_W = 595
        private const val A4_H = 842
        private const val MARGIN = 24
        private const val DEFAULT_QUESTION =
            "Ye mera scan kiya hua kagaz ya cheez hai. Isme jo likha ya dikh raha hai wo padho, batao ye kya hai aur sabse zaroori baatein kya hain. Chhota jawab bolo."
    }
}
