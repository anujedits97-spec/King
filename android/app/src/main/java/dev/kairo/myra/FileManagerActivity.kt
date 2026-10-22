package dev.kairo.myra

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * A real file manager for the whole phone: browse folders, search, open, share, rename, copy, move,
 * and delete (delete goes to a trash folder first). It lists what is really on the phone, unlike
 * Maya's file manager, which only shows sample files.
 *
 * Android 11 and newer only let an app list other folders after you switch on "All files access" for
 * Myraa (one settings page, shown here with a button). Android 8 to 9 use the normal storage
 * permission. Android 10 gives no way to do it, and the screen says so.
 *
 * This screen is only for YOU. The Myraa brain still reaches just its own workspace folder
 * (FilesModule), on purpose: the AI never gets the whole phone.
 */
class FileManagerActivity : Activity() {
    private val bg = 0xFF0B0C10.toInt()
    private val ink = 0xFFF2EFEA.toInt()
    private val dim = 0xFF8E919A.toInt()
    private val orange = 0xFFFF8A1F.toInt()
    private val cardColor = 0xFF14161B.toInt()
    private val line = 0xFF23262D.toInt()
    private val fieldColor = 0xFF0F1115.toInt()

    private enum class Sort(val label: String) { NAME("name"), DATE("newest first"), SIZE("biggest first") }

    private class Holder(val icon: TextView, val name: TextView, val sub: TextView)

    private val primaryRoot: File = Environment.getExternalStorageDirectory()
    private var storageRoot: File = primaryRoot
    private var dir: File = primaryRoot
    private var query = ""
    private var showHidden = false
    private var sort = Sort.NAME
    private var clip: File? = null
    private var clipMove = false
    private var cameFrom: File? = null
    private val shown = ArrayList<File>()
    private val trashDir: File by lazy { File(getExternalFilesDir(null) ?: filesDir, "trash").also { it.mkdirs() } }

    private lateinit var pathView: TextView
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: TextView
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteLabel: TextView
    private lateinit var searchBox: EditText
    private val adapter = Rows()

    /* ── small view helpers ───────────────────────────────────────────────── */

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
        if (stroke != null) setStroke(maxOf(1, dp(1)), stroke)
    }

    private fun tv(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun lp(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun chip(label: String, onClick: () -> Unit) = tv(label, 13f, ink).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rounded(cardColor, 100f, line)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun pill(label: String, onClick: () -> Unit) = tv(label, 14f, 0xFF1A0D00.toInt(), true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = rounded(orange, 100f)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /* ── screen ───────────────────────────────────────────────────────────── */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(4))
        }
        head.addView(tv("‹", 30f, ink).apply {
            setPadding(dp(12), 0, dp(12), dp(4))
            isClickable = true
            setOnClickListener { goUp() }
        })
        head.addView(tv("Files", 22f, ink, true), lp(0, wrap, weight = 1f))
        head.addView(tv("⋮", 26f, ink).apply {
            setPadding(dp(14), 0, dp(14), dp(2))
            isClickable = true
            setOnClickListener { mainMenu() }
        })
        root.addView(head, lp(match, wrap))

        pathView = tv("", 12f, dim).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }
        root.addView(pathView, lp(match, wrap, 20, 0, 20, 4))

        bannerText = tv("", 13f, ink)
        bannerButton = pill("Allow access") { askAccess() }
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(0xFF2A1A0C.toInt(), 16f, 0xFF4A2C10.toInt())
            visibility = View.GONE
            addView(bannerText)
            addView(bannerButton, lp(wrap, wrap, t = 10))
        }
        root.addView(banner, lp(match, wrap, 16, 6, 16, 6))

        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        for ((label, action) in places()) chipRow.addView(chip(label, action), lp(wrap, wrap, r = 8))
        chipScroll.addView(chipRow)
        root.addView(chipScroll, lp(match, wrap))

        searchBox = EditText(this).apply {
            hint = "Search in this folder"
            setHintTextColor(dim)
            setTextColor(ink)
            textSize = 14f
            isSingleLine = true
            background = rounded(fieldColor, 14f, 0xFF2E3139.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    reload()
                }
            })
        }
        root.addView(searchBox, lp(match, wrap, 16, 6, 16, 6))

        pasteLabel = tv("", 13f, ink).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        pasteBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
            background = rounded(cardColor, 16f, line)
            visibility = View.GONE
            addView(pasteLabel, lp(0, wrap, weight = 1f))
            addView(pill("Paste here") { paste() }, lp(wrap, wrap, r = 8))
            addView(chip("✕") { clip = null; updatePasteBar() })
        }
        root.addView(pasteBar, lp(match, wrap, 16, 4, 16, 4))

        val listBox = FrameLayout(this)
        listView = ListView(this).apply {
            divider = ColorDrawable(line)
            dividerHeight = 1
            selector = ColorDrawable(0x22FFFFFF)
            cacheColorHint = Color.TRANSPARENT
        }
        listView.adapter = adapter
        emptyView = tv("", 14f, dim).apply { gravity = Gravity.CENTER }
        listBox.addView(listView, FrameLayout.LayoutParams(match, match))
        listBox.addView(emptyView, FrameLayout.LayoutParams(match, match).apply { gravity = Gravity.CENTER })
        listView.emptyView = emptyView
        listView.setOnItemClickListener { _, _, pos, _ ->
            val f = shown.getOrNull(pos) ?: return@setOnItemClickListener
            if (f.isDirectory) go(f) else openFile(f)
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            shown.getOrNull(pos)?.let { itemMenu(it) }
            true
        }
        root.addView(listBox, LinearLayout.LayoutParams(match, 0, 1f))

        edgeToEdge(root)
        setContentView(root)
        refreshAccess()
        reload()
    }

    override fun onResume() {
        super.onResume()
        refreshAccess()
        reload()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        goUp()
    }

    /** Draws under the status and navigation bars and pads the content clear of them and the keyboard. */
    @Suppress("DEPRECATION")
    private fun edgeToEdge(root: View) {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            } else {
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    /* ── permission ───────────────────────────────────────────────────────── */

    private fun hasAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == 29 -> false
        else -> checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshAccess() {
        val ok = hasAccess()
        banner.visibility = if (ok) View.GONE else View.VISIBLE
        bannerButton.visibility = if (Build.VERSION.SDK_INT == 29) View.GONE else View.VISIBLE
        bannerText.text = when {
            Build.VERSION.SDK_INT >= 30 -> "To browse the files on your phone, Android needs \"All files access\" switched on for Myraa. The button opens one settings page: turn the switch on, then come back."
            Build.VERSION.SDK_INT == 29 -> "Android 10 does not let this app browse other folders, and there is no switch for it. Android 9 or older, and Android 11 or newer, can."
            else -> "Myraa needs permission to read and change files on this phone."
        }
        bannerButton.text = if (Build.VERSION.SDK_INT >= 30) "Open the settings page" else "Allow access"
    }

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) { toast("Open Android Settings, Apps, Myraa, and allow all files access.") }
            }
        } else if (Build.VERSION.SDK_INT < 29) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 7)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshAccess()
        reload()
    }

    /* ── places (the chips) ───────────────────────────────────────────────── */

    private fun places(): List<Pair<String, () -> Unit>> {
        val out = ArrayList<Pair<String, () -> Unit>>()
        out += "Internal" to { storageRoot = primaryRoot; go(primaryRoot) }
        val folders = listOf(
            "Download" to Environment.DIRECTORY_DOWNLOADS,
            "DCIM" to Environment.DIRECTORY_DCIM,
            "Pictures" to Environment.DIRECTORY_PICTURES,
            "Documents" to Environment.DIRECTORY_DOCUMENTS,
            "Music" to Environment.DIRECTORY_MUSIC,
            "Movies" to Environment.DIRECTORY_MOVIES,
        )
        for ((label, type) in folders) {
            out += label to { storageRoot = primaryRoot; go(Environment.getExternalStoragePublicDirectory(type)) }
        }
        // Memory cards: every external volume after the first one.
        getExternalFilesDirs(null).drop(1).forEachIndexed { i, f ->
            val p = f?.absolutePath ?: return@forEachIndexed
            val cut = p.indexOf("/Android/")
            if (cut > 0) {
                val r = File(p.substring(0, cut))
                out += (if (i == 0) "SD card" else "SD card ${i + 1}") to { storageRoot = r; go(r) }
            }
        }
        out += "Trash" to { openTrash() }
        return out
    }

    /* ── navigation and listing ───────────────────────────────────────────── */

    private fun go(d: File) {
        dir = d
        query = ""
        if (searchBox.text.isNotEmpty()) searchBox.setText("")
        reload()
        listView.setSelection(0)
    }

    private fun goUp() {
        val p = dir.parentFile
        when {
            dir == trashDir -> {
                val back = cameFrom ?: storageRoot
                cameFrom = null
                go(back)
            }
            dir == storageRoot || p == null -> finish()
            else -> go(p)
        }
    }

    private fun openTrash() {
        if (dir != trashDir) cameFrom = dir
        go(trashDir)
    }

    private fun comparator(): Comparator<File> = when (sort) {
        Sort.NAME -> compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) })
        Sort.DATE -> compareBy<File> { !it.isDirectory }.thenByDescending { it.lastModified() }
        Sort.SIZE -> compareBy<File> { !it.isDirectory }.thenByDescending { if (it.isDirectory) 0L else it.length() }
    }

    private fun reload() {
        shown.clear()
        val listed: Array<File>? = try { dir.listFiles() } catch (_: SecurityException) { null }
        if (listed != null) {
            val q = query.trim()
            var items = listed.asSequence().filter { showHidden || !it.name.startsWith(".") }
            if (q.isNotEmpty()) items = items.filter { it.name.contains(q, ignoreCase = true) }
            shown.addAll(items.sortedWith(comparator()).toList())
        }
        adapter.notifyDataSetChanged()
        pathView.text = dir.absolutePath
        emptyView.text = when {
            listed == null -> if (hasAccess()) "This folder can't be opened." else "Allow file access above to see your files."
            query.isNotBlank() -> "Nothing matches."
            else -> "This folder is empty."
        }
    }

    private inner class Rows : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val f = shown[position]
            val row: View
            val h: Holder
            if (convertView != null && convertView.tag is Holder) {
                row = convertView
                h = convertView.tag as Holder
            } else {
                val icon = tv("", 24f, ink).apply { gravity = Gravity.CENTER }
                val name = tv("", 15f, ink).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                }
                val sub = tv("", 12f, dim).apply { maxLines = 1 }
                val col = LinearLayout(this@FileManagerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(name)
                    addView(sub)
                }
                row = LinearLayout(this@FileManagerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    addView(icon, LinearLayout.LayoutParams(dp(40), wrap))
                    addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
                }
                h = Holder(icon, name, sub)
                row.tag = h
            }
            h.icon.text = iconFor(f)
            h.name.text = f.name
            h.sub.text = describe(f)
            return row
        }
    }

    private fun iconFor(f: File): String {
        if (f.isDirectory) return "📁"
        return when (f.extension.lowercase(Locale.ROOT)) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> "🖼️"
            "mp4", "mkv", "webm", "avi", "mov", "3gp" -> "🎬"
            "mp3", "m4a", "wav", "ogg", "flac", "aac", "opus" -> "🎵"
            "pdf" -> "📕"
            "zip", "rar", "7z", "tar", "gz" -> "🗜️"
            "apk" -> "📦"
            else -> "📄"
        }
    }

    private fun dateText(f: File): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.lastModified()))

    private fun describe(f: File): String =
        if (f.isDirectory) dateText(f) else sizeText(f.length()) + "  ·  " + dateText(f)

    private fun sizeText(b: Long): String = when {
        b < 1024L -> "$b B"
        b < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
    }

    /* ── menus ────────────────────────────────────────────────────────────── */

    private fun mainMenu() {
        val labels = arrayOf(
            "New folder",
            "Sort by: ${sort.label}",
            if (showHidden) "Hide hidden files" else "Show hidden files",
            "Trash",
            "Empty trash",
        )
        AlertDialog.Builder(this).setItems(labels) { _, i ->
            when (i) {
                0 -> newFolder()
                1 -> {
                    sort = Sort.values()[(sort.ordinal + 1) % Sort.values().size]
                    reload()
                    toast("Sorted by ${sort.label}")
                }
                2 -> { showHidden = !showHidden; reload() }
                3 -> openTrash()
                4 -> confirmEmptyTrash()
                else -> {}
            }
        }.show()
    }

    private fun itemMenu(f: File) {
        val inTrash = f.path.startsWith(trashDir.path + File.separator)
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        fun add(l: String, a: () -> Unit) { labels.add(l); acts.add(a) }
        if (f.isFile) {
            add("Open") { openFile(f) }
            add("Share") { shareFile(f) }
        }
        add("Rename") { renameDialog(f) }
        add("Copy") { setClip(f, false) }
        add("Move") { setClip(f, true) }
        if (inTrash) add("Delete forever") { confirmDeleteForever(f) } else add("Move to trash") { confirmTrash(f) }
        add("Details") { details(f) }
        AlertDialog.Builder(this).setTitle(f.name).setItems(labels.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    private fun details(f: File) {
        val sb = StringBuilder()
        sb.append(f.absolutePath).append("\n\n")
        if (f.isFile) {
            sb.append("Size: ").append(sizeText(f.length())).append(" (").append(f.length()).append(" bytes)\n")
            sb.append("Type: ").append(mimeOf(f)).append("\n")
        } else {
            sb.append("Folder, ").append(f.listFiles()?.size?.toString() ?: "can't be read").append(" items\n")
        }
        sb.append("Changed: ").append(dateText(f))
        AlertDialog.Builder(this).setTitle("Details").setMessage(sb.toString()).setPositiveButton("OK", null).show()
    }

    /* ── open and share ───────────────────────────────────────────────────── */

    private fun mimeOf(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase(Locale.ROOT)) ?: "*/*"

    private fun uriOf(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", f)

    private fun openFile(f: File) {
        if (f.extension.equals("apk", ignoreCase = true)) {
            toast("Installing apps from here is not supported.")
            return
        }
        try {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(f), mimeOf(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (_: ActivityNotFoundException) {
            toast("No app on this phone can open this kind of file.")
        } catch (e: Exception) {
            toast("Could not open it: ${e.message ?: "unknown error"}")
        }
    }

    private fun shareFile(f: File) {
        try {
            val i = Intent(Intent.ACTION_SEND).setType(mimeOf(f)).putExtra(Intent.EXTRA_STREAM, uriOf(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share ${f.name}"))
        } catch (e: Exception) {
            toast("Could not share it: ${e.message ?: "unknown error"}")
        }
    }

    /* ── changing files ───────────────────────────────────────────────────── */

    private fun badName(n: String) = n.isBlank() || n.contains('/') || n == "." || n == ".." || n.length > 200

    private fun inputDialog(title: String, initial: String, selectLen: Int, ok: (String) -> Unit) {
        val box = EditText(this).apply {
            setText(initial)
            isSingleLine = true
            setTextColor(ink)
            setSelection(0, selectLen.coerceIn(0, initial.length))
        }
        val wrapBox = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(box, FrameLayout.LayoutParams(match, wrap))
        }
        AlertDialog.Builder(this).setTitle(title).setView(wrapBox)
            .setPositiveButton("OK") { _, _ -> ok(box.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun newFolder() {
        inputDialog("New folder", "", 0) { n ->
            val target = File(dir, n)
            when {
                badName(n) -> toast("That is not a usable name.")
                target.exists() -> toast("That name is already used.")
                target.mkdirs() -> reload()
                else -> toast("Could not make the folder here.")
            }
        }
    }

    private fun renameDialog(f: File) {
        val sel = if (f.isDirectory) f.name.length else f.nameWithoutExtension.length
        inputDialog("Rename", f.name, sel) { n ->
            val target = File(f.parentFile, n)
            when {
                badName(n) -> toast("That is not a usable name.")
                n == f.name -> {}
                target.exists() -> toast("That name is already used.")
                f.renameTo(target) -> {
                    if (clip == f) { clip = null; updatePasteBar() }
                    reload()
                }
                else -> toast("Could not rename it.")
            }
        }
    }

    private fun setClip(f: File, move: Boolean) {
        clip = f
        clipMove = move
        updatePasteBar()
    }

    private fun updatePasteBar() {
        val c = clip
        pasteBar.visibility = if (c == null) View.GONE else View.VISIBLE
        if (c != null) pasteLabel.text = (if (clipMove) "Moving: " else "Copying: ") + c.name
    }

    private fun paste() {
        val src = clip ?: return
        val into = dir
        if (!src.exists()) {
            toast("That file is gone.")
            clip = null
            updatePasteBar()
            return
        }
        val srcPath = src.canonicalPath
        val intoPath = into.canonicalPath
        if (src.isDirectory && (intoPath == srcPath || intoPath.startsWith(srcPath + File.separator))) {
            toast("A folder can't go inside itself.")
            return
        }
        if (clipMove && src.parentFile?.canonicalPath == intoPath) {
            toast("It is already here.")
            clip = null
            updatePasteBar()
            return
        }
        val move = clipMove
        background(if (move) "Moving..." else "Copying...", {
            val dst = uniqueIn(into, src.name)
            if (move) moveTo(src, dst) else copyTo(src, dst)
        }) {
            clip = null
            updatePasteBar()
            toast("Done.")
        }
    }

    private fun confirmTrash(f: File) {
        AlertDialog.Builder(this).setTitle("Move to trash?")
            .setMessage("\"${f.name}\" goes to Myraa's trash. Open Trash (the chip above) to get it back with Move, or to delete it for good.")
            .setPositiveButton("Move to trash") { _, _ ->
                background("Moving to trash...", { moveTo(f, uniqueIn(trashDir, f.name)) })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteForever(f: File) {
        AlertDialog.Builder(this).setTitle("Delete forever?")
            .setMessage("\"${f.name}\" will be gone for good. This cannot be undone.")
            .setPositiveButton("Delete forever") { _, _ ->
                background("Deleting...", { deleteAll(f) })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmEmptyTrash() {
        val n = trashDir.listFiles()?.size ?: 0
        if (n == 0) {
            toast("The trash is empty.")
            return
        }
        AlertDialog.Builder(this).setTitle("Empty the trash?")
            .setMessage("$n item(s) will be deleted for good. This cannot be undone.")
            .setPositiveButton("Empty trash") { _, _ ->
                background("Emptying...", { trashDir.listFiles()?.forEach { deleteAll(it) } })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* ── file work (runs off the main thread) ─────────────────────────────── */

    private fun background(label: String, work: () -> Unit, done: () -> Unit = {}) {
        toast(label)
        Thread {
            val err = try { work(); null } catch (e: Exception) { e.message ?: "That did not work." }
            runOnUiThread {
                if (!isFinishing) {
                    if (err != null) toast(err) else done()
                    reload()
                }
            }
        }.start()
    }

    private fun uniqueIn(d: File, name: String): File {
        val first = File(d, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val f = File(d, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }

    private fun copyTo(src: File, dst: File) {
        if (!src.copyRecursively(dst)) {
            dst.deleteRecursively()
            throw IOException("Some files could not be copied.")
        }
    }

    private fun moveTo(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return
        // Another storage volume: rename cannot cross it, so copy, then remove the original.
        copyTo(src, dst)
        if (!src.deleteRecursively()) throw IOException("Copied, but could not remove the original ${src.name}.")
    }

    private fun deleteAll(f: File) {
        if (!f.deleteRecursively()) throw IOException("Could not delete ${f.name}.")
    }
}
