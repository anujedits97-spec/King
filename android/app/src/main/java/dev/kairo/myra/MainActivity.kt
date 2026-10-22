package dev.kairo.myra

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/** The home screen: Myraa's status, pairing with the laptop, and settings. */
private const val REQ_DOC = 41

class MainActivity : Activity() {
    /** The file the user attached and will send with the next question. */
    private var attached: Doc? = null
    private lateinit var docChip: TextView

    private lateinit var prefs: Prefs
    private lateinit var brain: BrainClient
    private lateinit var relay: RelayClient
    private var speaker: Speaker? = null
    private val dp by lazy { resources.displayMetrics.density }
    private val ui = Handler(Looper.getMainLooper())
    private val refreshers = mutableListOf<() -> Unit>()

    private val bg = Palette.BG
    private val ink = Palette.INK
    private val dim = Palette.DIM
    private val faint = Palette.FAINT
    private val orange = Palette.ORANGE
    private val good = Palette.GOOD
    private val bad = Palette.BAD
    private val cardColor = Palette.CARD
    private val line = Palette.LINE
    private val fieldColor = Palette.FIELD
    private val medium by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val condensed by lazy { Typeface.create("sans-serif-condensed", Typeface.BOLD) }

    private lateinit var hero: HeroView
    private lateinit var home: HomeDashboard
    private lateinit var avatar: AvatarView
    private lateinit var statusDot: GradientDrawable
    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var lapTitle: TextView
    private lateinit var lapSub: TextView

    /** The latest check of the brain (laptop or Termux). Null until the first answer. */
    private enum class State { UNPAIRED, ONLINE, OFFLINE, ERROR }
    private class Link(val state: State, val title: String, val detail: String)
    private var link: Link? = null

    private val poll = object : Runnable {
        override fun run() { checkConnection(); ui.postDelayed(this, 20_000) }
    }

    private val languages = listOf(
        "en-IN" to "English (India)", "en-GB" to "English (UK)", "en-US" to "English (US)", "ta-IN" to "Tamil", "hi-IN" to "Hindi",
        "bn-BD" to "Bangla (Bangladesh)", "bn-IN" to "Bangla (India)",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        brain = BrainClient(prefs)
        dev.kairo.myra.brain.LocalBrain.get(this, prefs).start() // the brain lives in this app (no Termux)
        relay = RelayClient(this, prefs)
        build()
        handlePairing(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairing(intent)
    }

    /** Shows Myraa's face or the arc reactor, depending on the Settings switch. */
    private fun applyAvatarChoice() {
        if (!::avatar.isInitialized || !::hero.isInitialized) return
        avatar.visibility = if (prefs.showAvatar) View.VISIBLE else View.GONE
        hero.visibility = if (prefs.showAvatar) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        checkLock()
        refresh()
        if (::aiStatus.isInitialized && !aiLoaded) loadAi()
        hero.animating = true
        avatar.animating = true
        applyAvatarChoice()
        ui.removeCallbacks(poll)
        ui.post(poll)
    }

    override fun onPause() {
        lastPause = System.currentTimeMillis()
        hero.animating = false
        avatar.animating = false
        ui.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        speaker?.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4 || requestCode == 7) refreshService()
        // Camera access refused: keep the switch honest, so it doesn't say "on" while Myraa can't take a photo.
        if (requestCode == 6 && !hasPerm(Manifest.permission.CAMERA)) prefs.cameraVision = false
        if (requestCode == 6) refreshService() // re-declare the foreground type with the camera now allowed
        refresh()
    }

    private fun refresh() = refreshers.forEach { it() }

    /* ── pairing ──────────────────────────────────────────────────────────── */

    /** The QR code on the laptop holds myra://pair?u=…&k=…&s=… */
    private fun handlePairing(intent: Intent?) {
        val data = intent?.dataString ?: return
        pairWith(data)
    }

    private fun pairWith(text: String) {
        val p = RelayClient.parsePairing(text)
        if (p == null) { info("That isn't a pairing code", "Scan the QR code that SETUP-MYRA-LAPTOP shows on your laptop. Its link starts with myra://pair"); return }
        prefs.relayUrl = p.first; prefs.relayKey = p.second; prefs.relaySecret = p.third
        prefs.mode = "laptop"
        link = null
        refreshService()
        refresh()
        checkConnection()
        info("Paired with your laptop", "Myraa now thinks on your laptop, from anywhere. Keep Myraa running on the laptop.\n\nYou can delete the QR picture on the laptop now.")
    }

    private fun isPaired() = prefs.relayUrl.isNotEmpty() && prefs.relayKey.isNotEmpty() && prefs.relaySecret.length >= 32

    /** Google's QR scanner from Play services: no camera permission, nothing to install from the store. */
    private fun scanPairing() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        val scanner = GmsBarcodeScanning.getClient(this, opts)
        val installer = ModuleInstall.getClient(this)
        installer.areModulesAvailable(scanner)
            .addOnSuccessListener { if (it.areModulesAvailable()) startScan(scanner) else installScanner(installer, scanner) }
            .addOnFailureListener { noScanner(it.message) }
    }

    private fun installScanner(installer: ModuleInstallClient, scanner: GmsBarcodeScanner) {
        Toast.makeText(this, "Getting the scanner ready…", Toast.LENGTH_SHORT).show()
        val listener = object : InstallStatusListener {
            override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                when (update.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> { installer.unregisterListener(this); startScan(scanner) }
                    ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                    ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> { installer.unregisterListener(this); noScanner() }
                }
            }
        }
        installer.installModules(ModuleInstallRequest.newBuilder().addApi(scanner).setListener(listener).build())
            .addOnSuccessListener { if (it.areModulesAlreadyInstalled()) { installer.unregisterListener(listener); startScan(scanner) } }
            .addOnFailureListener { noScanner(it.message) }
    }

    /** Right after the scanner downloads, Play services needs a moment before it opens: retry a few times. */
    private fun startScan(scanner: GmsBarcodeScanner, attempt: Int = 0) {
        scanner.startScan()
            .addOnSuccessListener { code -> pairWith(code.rawValue.orEmpty()) }
            .addOnFailureListener { e -> if (attempt < 4) ui.postDelayed({ startScan(scanner, attempt + 1) }, 1500) else noScanner(e.message) }
    }

    private fun noScanner(why: String? = null) = info(
        "The scanner didn't open",
        "Check that you're online and try again. If it still won't open, scan the QR code with Google Lens, copy the link it shows (it starts with myra://pair), then tap \"Paste link instead\"." +
            (why?.let { "\n\n($it)" } ?: ""),
    )

    private fun pastePairing() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (clip.isBlank()) info("Nothing copied", "Copy the pairing link first (it starts with myra://pair), then tap this again.")
        else pairWith(clip)
    }

    /* ── documents ───────────────────────────────────────────────────────── */

    /** Any file: the picker is not limited to a few types. See Documents.route for where each kind goes. */
    private fun pickDocument() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_DOC)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_DOC || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        attachFile(uri)
    }

    private fun attachFile(uri: Uri) {
        val name = Documents.nameOf(this, uri)
        val route = Documents.route(this, uri)
        if (route == Documents.Route.ZIP) { attachZip(uri); return }
        val phone = prefs.activeMode == "phone"
        attached = null
        docChip.visibility = View.VISIBLE
        docChip.text = "📎 Reading $name…"
        Thread {
            val result = runCatching { readAttachment(uri, name, route, phone) }
            runOnUiThread {
                result.onSuccess { attached = it; showDoc() }
                    .onFailure { attached = null; showDoc(); info("Couldn't read that file", it.message ?: "Try another file.") }
            }
        }.start()
    }

    /**
     * Turns the picked file into a Doc. Text and PDFs with text are read here; photos and scanned PDFs become pictures on the
     * brain; Word, Excel, PowerPoint, ODF, EPUB, RTF, HTML, notebooks and everything else are sent to the brain. Runs on a background thread.
     */
    private fun readAttachment(uri: Uri, name: String, first: Documents.Route, phone: Boolean): Doc {
        var route = first
        if (route == Documents.Route.UNKNOWN) route = if (Documents.looksText(this, uri)) Documents.Route.TEXT else Documents.Route.SERVER
        val needPhone = "That works when Myraa thinks on This phone (Where Myraa thinks → This phone)."
        val stem = name.substringBeforeLast('.', name)
        return when (route) {
            Documents.Route.TEXT -> Documents.read(this, uri)
            Documents.Route.PDF -> try {
                Documents.read(this, uri)
            } catch (e: ScannedPdf) {
                if (!phone) error("This PDF is a scan (pictures of pages), so there is no text to read. $needPhone")
                val pages = Documents.pdfPages(this, uri)
                val total = Documents.pdfPageCount(this, uri)
                val paths = pages.mapIndexed { i, jpg -> brain.attachBlocking("$stem-page${i + 1}.jpg", "image/jpeg", jpg).optString("path") }
                Doc(name, "", false, images = paths, kind = "pages", note = if (total > pages.size) "Only the first ${pages.size} of $total pages are included." else null)
            }
            Documents.Route.IMAGE -> {
                if (!phone) error("Pictures are looked at by the AI on the phone brain. $needPhone")
                val j = brain.attachBlocking("$stem.jpg", "image/jpeg", Documents.imageJpeg(this, uri))
                Doc(name, "", false, images = listOf(j.optString("path")), kind = "image")
            }
            else -> {
                if (!phone) {
                    // Text-like files (HTML, RTF, ...) can still be read as they are, without the phone brain.
                    if (Documents.looksText(this, uri)) return Documents.read(this, uri)
                    error("$name is read by the brain on the phone. $needPhone")
                }
                val j = brain.attachBlocking(name, "application/octet-stream", Documents.bytes(this, uri))
                when (j.optString("kind")) {
                    "text" -> Doc(name, j.optString("text"), j.optBoolean("truncated"), savedAt = j.optString("fullTextPath").ifEmpty { null }, note = j.optString("note").ifEmpty { null })
                    "image" -> Doc(name, "", false, images = listOf(j.optString("path")), kind = "image")
                    else -> Doc(name, "", false, savedAt = j.optString("path"), note = j.optString("note").ifEmpty { null }, kind = "file")
                }
            }
        }
    }

    /** A zip goes to the brain, which unpacks it; Myraa then looks inside with its zip tools. */
    private fun attachZip(uri: Uri) {
        if (prefs.activeMode != "phone") {
            info("Zips need the phone brain", "Zip files work when Myraa thinks on This phone (Where Myraa thinks → This phone).")
            return
        }
        val name = Documents.nameOf(this, uri)
        val input = contentResolver.openInputStream(uri)
        if (input == null) { info("Couldn't read that file", "I couldn't open the zip."); return }
        attached = null
        docChip.visibility = View.VISIBLE
        docChip.text = "🗜️ Sending $name to Myraa…"
        brain.uploadZip(name, input) { j, err ->
            if (j == null) {
                attached = null
                showDoc()
                info("Couldn't load the zip", err ?: "Try again.")
            } else {
                attached = Doc(name, "", false, zip = j.optString("project"), zipFiles = j.optInt("files"))
                showDoc()
            }
        }
    }

    /** Shows the attached file above the question box, or hides the row when nothing is attached. */
    private fun showDoc() {
        val d = attached
        docChip.visibility = if (d == null) View.GONE else View.VISIBLE
        docChip.text = when {
            d == null -> ""
            d.zip != null -> "🗜️ ${d.name} · ${d.zipFiles} files loaded · tap to remove"
            d.kind == "image" -> "🖼️ ${d.name} · tap to remove"
            d.kind == "pages" -> "📑 ${d.name} · ${d.images.size} page${if (d.images.size == 1) "" else "s"} as pictures · tap to remove"
            d.kind == "file" -> "📎 ${d.name} · saved, but I can't read inside · tap to remove"
            else -> "📄 ${d.name}${if (d.truncated) " (first part only)" else ""} · tap to remove"
        }
    }

    /* ── status ───────────────────────────────────────────────────────────── */

    private fun checkConnection() {
        if (prefs.activeMode == "laptop") {
            relay.laptop { s ->
                link = when {
                    !s.paired -> Link(State.UNPAIRED, "Not connected yet", "Pair with your laptop below to begin.")
                    s.problem != null -> Link(State.ERROR, "Can't reach the internet", s.problem)
                    s.online -> Link(State.ONLINE, "Online · ${s.host.ifEmpty { "your laptop" }}", "")
                    s.lastSeen.isEmpty() -> Link(State.OFFLINE, "Laptop not connected", "Run START-MYRA-LAPTOP on your laptop.")
                    else -> Link(State.OFFLINE, "Laptop offline", "Last seen ${ago(s.lastSeen)}. Turn it on and start Myraa on it.")
                }
                lapTitle.text = s.host.ifEmpty { "Your laptop" }
                lapSub.text = when {
                    !s.paired -> "Not paired yet"
                    s.problem != null -> "Can't check right now"
                    s.online -> "Online · ready"
                    s.lastSeen.isEmpty() -> "Paired · never connected"
                    else -> "Offline · last seen ${ago(s.lastSeen)}"
                }
                lapSub.setTextColor(if (s.online) good else dim)
                renderStatus()
            }
        } else if (prefs.token.isEmpty()) {
            link = Link(State.UNPAIRED, "Brain not set up",
                if (prefs.mode == "laptop") "Not paired with your laptop, so Myraa thinks on this phone."
                else "The brain starts by itself inside the app.")
            renderStatus()
        } else {
            brain.health { ok, msg ->
                link = if (ok) Link(State.ONLINE, "Brain ready", "") else Link(State.OFFLINE, "Brain not running", msg)
                renderStatus()
            }
        }
    }

    private fun renderStatus() {
        val l = link
        val running = MyraService.running
        val ready = if (running) (if (prefs.wakeWord) "Tap the orb or say \"Hey Myraa\"." else "Tap the orb to talk.") else "Tap Start to put Myraa on your screen."
        val (color, title, sub) = if (l == null) Triple(faint, "Connecting…", "") else when (l.state) {
            State.UNPAIRED -> Triple(faint, l.title, l.detail)
            State.ONLINE -> Triple(good, l.title, ready)
            State.OFFLINE -> Triple(orange, l.title, l.detail)
            State.ERROR -> Triple(bad, l.title, l.detail)
        }
        statusDot.setColor(color)
        statusText.text = title
        statusSub.text = sub
        statusSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        hero.mood = when { l?.state == State.ONLINE && running -> 2; l?.state == State.ONLINE -> 1; else -> 0 }
        avatar.mood = hero.mood
    }

    private fun ago(iso: String): String = runCatching {
        val m = Duration.between(OffsetDateTime.parse(iso).toInstant(), Instant.now()).toMinutes()
        when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 60 * 24 -> "${m / 60} h ago"
            else -> "${m / (60 * 24)} days ago"
        }
    }.getOrDefault("a while ago")

    /* ── layout ───────────────────────────────────────────────────────────── */

    private fun build() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(10), px(20), px(28))
        }

        // Top bar
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Myraa", 15f, ink, condensed).apply { letterSpacing = 0.34f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("v${BuildConfig.VERSION_NAME}", 12f, faint))
        })

        // Home: "Good evening, Anuj" and the energy streak
        home = HomeDashboard(this, prefs) { t -> if (!canRun()) askForMissing() else MyraService.send(this, MyraService.ACTION_ASK, t) }
        col.addView(home.greeting, lp(top = 10))
        refreshers += { home.refresh() }

        // The arc reactor and the status under it
        hero = HeroView(this).apply { contentDescription = "Talk to Myraa"; setOnClickListener { talk() } }
        col.addView(hero, lp(h = px(236), top = 6))
        avatar = AvatarView(this).apply { contentDescription = "Myraa. Tap to talk"; setOnClickListener { talk() } }
        col.addView(avatar, lp(h = px(236), top = 6))
        applyAvatarChoice()
        statusDot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(faint) }
        statusText = text("Connecting…", 18f, ink, medium)
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(View(context).apply { background = statusDot }, LinearLayout.LayoutParams(px(9), px(9)).apply { marginEnd = px(9) })
            addView(statusText)
        }, lp(top = 4))
        statusSub = text("", 13.5f, dim).apply { gravity = Gravity.CENTER }
        col.addView(statusSub, lp(top = 4))

        // Start / talk
        val start = Pill("Start Myraa", LineIcon.Kind.POWER, Style.PRIMARY) { toggleRunning() }
        val talk = Pill("", LineIcon.Kind.MIC, Style.SECONDARY) { talk() }.apply { contentDescription = "Talk now"; setPadding(0, 0, 0, 0) }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(start, LinearLayout.LayoutParams(0, px(56), 1f))
            addView(talk, LinearLayout.LayoutParams(px(56), px(56)).apply { marginStart = px(12) })
        }, lp(top = 22))
        refreshers += {
            val on = MyraService.running
            start.setLabel(if (on) "Stop Myraa" else "Start Myraa")
            start.setStyle(if (on) Style.SECONDARY else Style.PRIMARY)
            renderStatus()
        }

        // Type instead of talking
        val ask = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cardColor, 100f, line)
            setPadding(px(20), px(4), px(6), px(4))
        }
        val q = EditText(this).apply {
            hint = "Ask Myraa anything…"
            setHintTextColor(faint); setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            styleCursor(this)
        }
        val send = {
            val t = q.text.toString().trim()
            val doc = attached
            if (t.isNotEmpty() || doc != null) {
                if (!canRun()) askForMissing()
                else {
                    MyraService.send(this, MyraService.ACTION_ASK, t.ifEmpty { Documents.defaultQuestion(doc) }, doc)
                    q.setText("")
                    attached = null
                    showDoc()
                    getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(q.windowToken, 0)
                }
            }
        }
        q.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        ask.addView(Pill("", LineIcon.Kind.PASTE, Style.QUIET) { pickDocument() }.apply {
            contentDescription = "Attach a document"; setPadding(0, 0, 0, 0); minimumHeight = 0
        }, LinearLayout.LayoutParams(px(44), px(44)))
        ask.addView(q, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ask.addView(Pill("", LineIcon.Kind.SEND, Style.PRIMARY) { send() }.apply { contentDescription = "Send"; setPadding(0, 0, 0, 0); minimumHeight = 0 }, LinearLayout.LayoutParams(px(44), px(44)))
        docChip = text("", 13f, dim).apply {
            visibility = View.GONE
            setPadding(px(20), px(8), px(20), 0)
            setOnClickListener { attached = null; showDoc() }
        }
        col.addView(docChip, lp(top = 14))
        col.addView(ask, lp(top = 6))

        // Quick buttons (Music, Study, Journal, Scan) and the Weather, Today and Mood cards
        col.addView(home.panel, lp(top = 16))

        // Permissions, only while something is missing
        val setup = card("Finish setting up", "Myraa needs these to hear you and to show the orb.")
        setup.addView(permissionRow("Microphone", "To hear you.", { hasPerm(Manifest.permission.RECORD_AUDIO) }) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })
        if (Build.VERSION.SDK_INT >= 33) setup.addView(permissionRow("Notifications", "Android shows one while Myraa is on.", { hasPerm(Manifest.permission.POST_NOTIFICATIONS) }) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        })
        setup.addView(permissionRow("Display over other apps", "For the orb and the orange glow.", { Settings.canDrawOverlays(this) }) { openOverlaySettings() })
        refreshers += { setup.visibility = if (requiredGranted()) View.GONE else View.VISIBLE }

        // The laptop
        val lap = card(null)
        lapTitle = text("Your laptop", 16f, ink, medium)
        lapSub = text("", 13f, dim)
        lap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconTile(LineIcon.Kind.LAPTOP), LinearLayout.LayoutParams(px(44), px(44)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(lapTitle); addView(lapSub)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(14) })
            addView(Pill("", LineIcon.Kind.REFRESH, Style.QUIET) { link = null; renderStatus(); checkConnection() }.apply {
                contentDescription = "Check again"; setPadding(0, 0, 0, 0); minimumHeight = 0
            }, LinearLayout.LayoutParams(px(40), px(40)))
        })
        val unpaired = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        unpaired.addView(steps("On your laptop, double-click SETUP-MYRA-LAPTOP.", "A QR code opens. Tap Scan and point your phone at it."), lp(top = 16))
        unpaired.addView(Pill("Scan QR code", LineIcon.Kind.SCAN, Style.PRIMARY) { scanPairing() }, lp(h = px(52), top = 16))
        unpaired.addView(Pill("Paste link instead", LineIcon.Kind.PASTE, Style.GHOST) { pastePairing() }, lp(top = 6))
        val paired = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        paired.addView(row(
            Pill("Screenshot", LineIcon.Kind.IMAGE, Style.SECONDARY) {
                if (!canRun()) askForMissing() else MyraService.send(this, MyraService.ACTION_ASK, "send me a screenshot")
            },
            Pill("Live screen", LineIcon.Kind.SCREEN, Style.SECONDARY) { openRemoteDesktop() },
        ), lp(top = 16))
        paired.addView(Pill("Pair again", LineIcon.Kind.SCAN, Style.GHOST) { scanPairing() }, lp(top = 6))
        lap.addView(unpaired); lap.addView(paired)
        col.addView(lap, lp(top = 18))

        // Termux on this phone (only in phone mode)
        val ph = card("Brain on this phone", "The brain runs inside this app. Termux is not needed.")
        val tokenStatus = text("", 13f, dim).apply { setPadding(0, px(8), 0, 0) }
        val token = input(prefs.token, "Token from: myra token", secret = true)
        token.setOnFocusChangeListener { _, f -> if (!f) prefs.token = token.text.toString() }
        ph.addView(token, lp(top = 14))
        ph.addView(row(
            Pill("Paste", LineIcon.Kind.PASTE, Style.SECONDARY) {
                val clip = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
                if (!clip.isNullOrEmpty()) { token.setText(clip); prefs.token = clip; checkBrain(tokenStatus) }
            },
            Pill("Test", LineIcon.Kind.REFRESH, Style.SECONDARY) { prefs.token = token.text.toString(); checkBrain(tokenStatus) },
        ), lp(top = 10))
        ph.addView(tokenStatus)
        ph.addView(Pill("Start the brain in Termux", LineIcon.Kind.POWER, Style.GHOST) {
            if (Termux.startBrain(this)) { tokenStatus.text = "Starting… (a few seconds)"; tokenStatus.postDelayed({ checkBrain(tokenStatus) }, 7000) }
            else tokenStatus.text = "Allow \"Start the brain for you\" below first, or run myra start in Termux."
        }, lp(top = 4))
        ph.addView(divider())
        ph.addView(permissionRow("Start the brain for you", "Lets Myraa run \"myra start\" in Termux.", { Termux.permitted(this) }) {
            if (Termux.installed(this)) requestPermissions(arrayOf(Termux.PERMISSION), 3)
            else info("Termux isn't installed", "Install Termux from F-Droid (f-droid.org), then run the Myraa installer in it. The Play Store version of Termux is outdated and won't work.")
        })
        ph.addView(text("Address", 13f, dim).apply { setPadding(0, px(14), 0, 0) })
        val addr = input(prefs.brainAddress, Prefs.DEFAULT_BRAIN, secret = false)
        addr.setOnFocusChangeListener { _, f -> if (!f) prefs.brainAddress = addr.text.toString() }
        ph.addView(addr, lp(top = 6))
        ph.addView(text("New phone? In Termux run:\ncurl -sL dynamox-dev677.github.io/myra-phone/i | bash", 12f, faint).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(0, px(14), 0, 0)
        })
        col.addView(ph, lp(top = 18))
        col.addView(setup, lp(top = 14))
        refreshers += {
            lap.visibility = if (prefs.mode == "laptop") View.VISIBLE else View.GONE
            ph.visibility = View.GONE // the brain is inside the app now; the Termux token and address are not needed
            unpaired.visibility = if (isPaired()) View.GONE else View.VISIBLE
            paired.visibility = if (isPaired()) View.VISIBLE else View.GONE
        }

        // Voice and behaviour
        val s = card("Voice & behaviour")
        s.addView(switchRow("Orb on the screen edge", "Tap it to talk, drag it up or down, hold it to open this screen.", prefs.orb) { prefs.orb = it; refreshService() })
        s.addView(divider())
        val wakeNote = text("", 12.5f, orange).apply {
            visibility = View.GONE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }
            })
        }
        s.addView(switchRow("Wake word \"Hey Myraa\"", "Listens offline for the word Myraa. Uses a little battery.", prefs.wakeWord) { on -> setWake(on, wakeNote) })
        s.addView(wakeNote)
        s.addView(divider())
        s.addView(switchRow("Listen again after a question", "When Myraa asks you something, it waits for your answer.", prefs.followUp) { prefs.followUp = it })
        s.addView(divider())
        s.addView(switchRow("Captions", "Show what you said and what Myraa says.", prefs.captions) { prefs.captions = it })
        s.addView(divider())
        s.addView(switchRow("Announce incoming calls", "Myraa tells you who is calling and picks up or declines when you say so. Asks for phone, contacts and call log access.", prefs.announceCalls) { on ->
            prefs.announceCalls = on
            if (on && CallWatcher.PERMISSIONS.any { !hasPerm(it) }) requestPermissions(CallWatcher.PERMISSIONS, 4) else refreshService()
        })
        s.addView(divider())
        s.addView(switchRow("Show Myraa's face", "A 2D character on the home screen that blinks and moves her mouth while she talks. Off shows the arc reactor instead.", prefs.showAvatar) { prefs.showAvatar = it; applyAvatarChoice() })
        s.addView(divider())
        s.addView(switchRow("Myraa takes my calls", "Myraa picks up on the loudspeaker, asks who is calling and why, says she will tell you, hangs up and leaves a summary notification. Needs 'Announce incoming calls' on. Best effort: some phones block the microphone during calls.", prefs.callAssistant) { prefs.callAssistant = it })
        s.addView(divider())
        s.addView(switchRow("Only for unknown numbers", "Myraa leaves your saved contacts alone and takes only numbers that are not in your contacts.", prefs.callAssistantUnknownOnly) { prefs.callAssistantUnknownOnly = it })
        s.addView(divider())
        s.addView(switchRow("Anti-theft guard", "Put the phone down and switch this on. If somebody moves it or pulls the charger, a loud siren rings and the front camera takes their photo. Disarm from the notification (needs your screen lock) or here. Needs camera access.", prefs.theftGuard) { on ->
            if (on) {
                if (!hasPerm(Manifest.permission.CAMERA)) requestPermissions(arrayOf(Manifest.permission.CAMERA), 6)
                info("Guard on", AntiTheft.arm(this, prefs))
            } else AntiTheft.disarm(prefs)
        })
        s.addView(divider())
        s.addView(valueRow("Touch Guard sensitivity", { prefs.theftSensitivity.replaceFirstChar { it.uppercase() } + " (tap to change)" }) {
            val next = when (prefs.theftSensitivity) { "low" -> "medium"; "medium" -> "high"; else -> "low" }
            prefs.theftSensitivity = next
            if (AntiTheft.armed) info("Touch Guard", "Sensitivity is now ${next}. It applies the next time the guard is switched on.")
            else info("Touch Guard", "Sensitivity is now ${next}.")
            refresh()
        })
        s.addView(divider())
        s.addView(switchRow("Double-press power to talk", "While Myraa is on, press the power button twice quickly to start listening. Works with the screen on or off.", prefs.powerDoubleTap) { prefs.powerDoubleTap = it })
        s.addView(divider())
        s.addView(switchRow("Let Myraa read my calendar", "Myraa can tell you what is coming up. It reads event titles, times and places only. Asks for calendar access.", hasPerm(Manifest.permission.READ_CALENDAR)) { on ->
            if (on && !hasPerm(Manifest.permission.READ_CALENDAR)) requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 5)
            else if (!on && hasPerm(Manifest.permission.READ_CALENDAR)) info("Take it back in Android", "Android only lets you remove this permission in the phone's Settings, under Apps, Myraa, Permissions.")
        })
        s.addView(divider())
        s.addView(valueRow("Keys and accounts", { "Email, images, Facebook, Instagram, YouTube, Tavily" }) {
            startActivity(Intent(this, KeysActivity::class.java))
        })
        s.addView(divider())
        s.addView(switchRow("Incognito memory", "When on, Myraa does not save new memories. Old ones stay until you delete them.", prefs.incognito) { prefs.incognito = it })
        s.addView(divider())
        s.addView(valueRow("Yaadein (memories)", { "See and delete what Myraa remembers" }) {
            startActivity(Intent(this, MemoriesActivity::class.java))
        })
        s.addView(divider())
        s.addView(valueRow("Emergency SOS", { "Press power 5 times fast to text your contacts" }) {
            startActivity(Intent(this, SosActivity::class.java))
        })
        s.addView(divider())
        s.addView(valueRow("Voice passphrase", { "Say it to unlock sensitive actions for 10 minutes" }) {
            startActivity(Intent(this, PassphraseActivity::class.java))
        })
        s.addView(divider())
        s.addView(valueRow("Personal", { "Your name, how Myraa addresses you, your number" }) {
            startActivity(Intent(this, PersonalActivity::class.java))
        })
        s.addView(divider())
        s.addView(valueRow("Permissions", { "See what Myraa may use, and allow each one" }) {
            startActivity(Intent(this, PermissionsActivity::class.java))
        })
        s.addView(divider())
        s.addView(switchRow("Let Myraa use the camera", "When you ask, Myraa takes one photo with the back or front camera and looks at it. No preview, nothing saved to Photos. Android still shows its green camera dot while the camera is in use. Asks for camera access.", prefs.cameraVision) { on ->
            prefs.cameraVision = on
            if (on && !hasPerm(Manifest.permission.CAMERA)) requestPermissions(arrayOf(Manifest.permission.CAMERA), 6)
            else refreshService()
        })
        s.addView(divider())
        s.addView(switchRow("Screen share without tapping", "When you say \"screen share karo\", Myraa taps Start on Android's own sharing dialog itself. Needs Myraa switched on in Accessibility settings. Android still shows its sharing icon while it runs.", prefs.autoShare) { on ->
            prefs.autoShare = on
            if (on && !MyraAccess.enabled(this)) {
                info("One more step", "Turn on Myraa in the Accessibility screen that opens next. Until then you still have to tap Start yourself.")
                try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
            }
        })
        s.addView(divider())
        s.addView(permissionRow("Contacts, calls and texts", "Lets Myraa look up contacts, read the call log and texts, and send a text or place a call only after your spoken yes. Replaces Termux:API.", { PhoneTools.PERMISSIONS.all { hasPerm(it) } }) {
            requestPermissions(PhoneTools.PERMISSIONS, 7)
        })
        s.addView(permissionRow("Read notifications", "Lets Myraa read what is on your notification shade, like new WhatsApp or Telegram messages. Opens Android's Notification access screen.", { AutoReplyService.listening(this) }) {
            try { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) {}
        })
        s.addView(divider())
        s.addView(switchRow("Auto-reply when you're busy", "Answers one-to-one chats in WhatsApp, Telegram and Instagram with your own text plus \"(auto-reply)\", once per person per hour. Never groups, never codes. Needs Notification access.", prefs.autoReply) { on ->
            prefs.autoReply = on
            if (on && !AutoReplyService.listening(this)) {
                info("One more step", "Turn on Myraa in the Notification access screen that opens next. Until then auto-reply does nothing.")
                try { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) {}
            }
        })
        s.addView(valueRow("Auto-reply text", { prefs.autoReplyText.take(40) }) {
            val box = android.widget.EditText(this).apply {
                setText(prefs.autoReplyText)
                filters = arrayOf(android.text.InputFilter.LengthFilter(160))
            }
            AlertDialog.Builder(this).setTitle("Auto-reply text").setMessage("\"(auto-reply)\" is added after it, so people know a person did not type it.").setView(box)
                .setPositiveButton("Save") { _, _ -> if (box.text.isNotBlank()) prefs.autoReplyText = box.text.toString(); refresh() }
                .setNegativeButton("Cancel", null).show()
        })
        s.addView(divider())
        val speedValue = text("", 14f, dim)
        s.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(14), 0, 0)
            addView(text("Speaking speed", 15f, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(speedValue)
        })
        s.addView(SeekBar(this).apply {
            max = 150; progress = prefs.speechRate - 50
            progressTintList = ColorStateList.valueOf(orange)
            thumbTintList = ColorStateList.valueOf(orange)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF3A3E47.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { prefs.speechRate = p + 50; refresh() } }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { tts().applySettings(); refreshService() }
            })
        }, lp(top = 6))
        refreshers += { speedValue.text = String.format(Locale.US, "%.2f×", prefs.speechRate / 100f) }
        s.addView(divider())
        s.addView(valueRow("Voice", { voiceLabel() }) { chooseVoice() })
        s.addView(divider())
        s.addView(valueRow("Language", { languageLabel() }) { chooseLanguage() })
        s.addView(divider())
        s.addView(valueRow("Wake word", { prefs.wakePhrase }) { editWakeWord() })
        s.addView(divider())
        s.addView(valueRow("Extra stop phrase", { prefs.stopCommand.ifEmpty { "none" } }) { editStopCommand() })
        s.addView(divider())
        s.addView(valueRow("App lock (PIN)", { if (AppLock.isSet(prefs)) "On" else "Off" }) { pinMenu() })
        s.addView(divider())
        s.addView(valueRow("Privacy check", { "" }) { privacyCheck() })
        s.addView(divider())
        s.addView(valueRow("File manager", { "Browse and manage phone files" }) { startActivity(Intent(this, FileManagerActivity::class.java)) })
        s.addView(divider())
        s.addView(permissionRow("Keep Myraa awake", "Unrestricted battery, so Android doesn't put it to sleep. Optional.", {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        }) {
            @Suppress("BatteryLife") startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        s.addView(divider())
        s.addView(permissionRow("Control other apps", "Lets Myraa open apps, read the screen, tap, type and scroll when you ask (YouTube, Instagram, Telegram...). Turn on Myraa under Accessibility. It skips banking apps and passwords, and asks first before sending or posting.", {
            MyraAccess.enabled(this)
        }) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        s.addView(Pill("Test voice", LineIcon.Kind.MIC, Style.GHOST) { testVoice() }, lp(top = 10))
        col.addView(s, lp(top = 14))

        // Where Myraa thinks
        val m = card("Where Myraa thinks")
        val modeNote = text("", 13f, dim).apply { setPadding(0, px(10), 0, 0) }
        m.addView(segmented(listOf("laptop" to "My laptop", "phone" to "This phone"), { prefs.mode }) { v ->
            prefs.mode = v; link = null; refreshService(); refresh(); checkConnection()
        }, lp(top = 14))
        m.addView(modeNote)
        refreshers += {
            modeNote.text = when {
                prefs.activeMode == "laptop" -> "Your laptop does the thinking. Works from anywhere, and Myraa can use the laptop: open apps, take screenshots, ask Claude Code."
                prefs.mode == "laptop" -> "Not paired with your laptop yet, so the brain inside this app does the thinking. Pair the laptop to switch."
                else -> "The brain inside this app does the thinking. No laptop needed, but Myraa can only reach the PC on home Wi-Fi."
            }
        }
        col.addView(m, lp(top = 14))
        col.addView(buildAiCard(), lp(top = 14))

        col.addView(text("Myraa ${BuildConfig.VERSION_NAME} · open source\ngithub.com/Dynamox-DEV677/myra-phone", 12f, faint).apply {
            gravity = Gravity.CENTER; setLineSpacing(px(2).toFloat(), 1f)
        }, lp(top = 26))

        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; setBackgroundColor(bg); addView(col) }
        setContentView(scroll)
        edgeToEdge(scroll)
        refresh()
        if (prefs.wakeWord && !WakeWord(this, onWake = {}).modelReady) wakeNote.text = "The wake word's model isn't downloaded. Switch it off and on again."
    }

    /** Draws under the status and navigation bars, and pads the content clear of them and the keyboard. */
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

    /* ── actions ──────────────────────────────────────────────────────────── */

    private fun requiredGranted() = hasPerm(Manifest.permission.RECORD_AUDIO) && Settings.canDrawOverlays(this) &&
        (Build.VERSION.SDK_INT < 33 || hasPerm(Manifest.permission.POST_NOTIFICATIONS))

    private fun canRun() = hasPerm(Manifest.permission.RECORD_AUDIO) && Settings.canDrawOverlays(this)

    private fun askForMissing() {
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        else if (!Settings.canDrawOverlays(this)) AlertDialog.Builder(this)
            .setTitle("One more permission")
            .setMessage("Allow \"Display over other apps\" so Myraa can show the orb and the glow.")
            .setPositiveButton("Allow") { _, _ -> openOverlaySettings() }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun openOverlaySettings() = startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))

    private fun toggleRunning() {
        if (MyraService.running) startService(MyraService.intent(this, MyraService.ACTION_STOP))
        else if (!canRun()) { askForMissing(); return }
        else MyraService.send(this, MyraService.ACTION_START)
        ui.postDelayed({ refresh() }, 400)
    }

    private fun talk() {
        if (!canRun()) { askForMissing(); return }
        MyraService.send(this, MyraService.ACTION_ACTIVATE)
        ui.postDelayed({ refresh() }, 400)
    }

    private fun refreshService() { if (MyraService.running) MyraService.send(this, MyraService.ACTION_START) }

    private fun checkBrain(status: TextView) {
        status.text = "Checking…"
        status.setTextColor(dim)
        brain.health { ok, msg -> status.text = msg; status.setTextColor(if (ok) good else orange); checkConnection() }
    }

    /** Live screen: Google's free Chrome Remote Desktop. */
    private fun openRemoteDesktop() {
        val pkg = "com.google.chromeremotedesktop"
        val app = packageManager.getLaunchIntentForPackage(pkg)
        if (app != null) { startActivity(app); return }
        AlertDialog.Builder(this)
            .setTitle("See your laptop live")
            .setMessage(
                "This uses Google's free Chrome Remote Desktop.\n\n" +
                    "1. On the laptop, open remotedesktop.google.com/access in Chrome and turn on remote access (you pick a PIN).\n" +
                    "2. Install Chrome Remote Desktop on this phone and sign in with the same Google account.",
            )
            .setPositiveButton("Get the app") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) }
                    .onFailure { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))) }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun setWake(on: Boolean, note: TextView) {
        if (!on) { prefs.wakeWord = false; note.text = ""; refreshService(); renderStatus(); return }
        val wake = WakeWord(this, onWake = {})
        if (wake.modelReady) { prefs.wakeWord = true; note.text = ""; refreshService(); renderStatus(); return }
        note.text = "Downloading the offline speech model (${WakeWord.MODEL_MB} MB)…"
        wake.download(progress = { p -> if (p >= 0) note.text = "Downloading the offline speech model… $p%" }) { ok, err ->
            if (ok) { prefs.wakeWord = true; note.text = "Ready. Say \"Hey Myraa\"."; refreshService(); renderStatus() }
            else { prefs.wakeWord = false; note.text = "Download failed: $err. Switch it on again to retry." }
        }
    }

    private fun tts(): Speaker = speaker ?: Speaker(this, prefs).also { speaker = it }

    private fun voiceLabel() = if (prefs.voiceName.isEmpty()) "Myraa" else prefs.voiceName.substringAfterLast('#').let { if (it.length > 22) it.take(21) + "…" else it }

    private fun languageLabel() = languages.firstOrNull { it.first == prefs.language }?.second ?: Locale.forLanguageTag(prefs.language).displayName

    /** "Myraa" (the laptop's British neural voice) first, then the phone's own voices. */
    private fun chooseVoice() {
        val voices = tts().voices()
        val names = (listOf("Myraa · British, sent by your laptop (best)") +
            voices.map { v -> "Phone · ${v.locale.displayName} · ${v.name.substringAfterLast('#').replace('-', ' ')}" }).toTypedArray()
        AlertDialog.Builder(this).setTitle("Voice").setItems(names) { _, i ->
            prefs.voiceName = if (i == 0) "" else voices[i - 1].name
            tts().applySettings()
            refreshService()
            refresh()
            testVoice()
        }.show()
    }

    private fun testVoice() {
        tts().applySettings()
        val line = if (prefs.voiceName.isEmpty()) "At your service. Myraa is online and ready when you are." else "This is how I sound now."
        tts().speak(line, object : Speaker.Listener { override fun onWord() {}; override fun onDone() {} })
    }

    /* ── wake word, stop phrase, app lock and privacy check (ideas from Maya) ───────────────────── */

    private var lockDialog: AlertDialog? = null
    private var unlocked = false
    private var lastPause = 0L

    private fun content(): View = window.decorView.findViewById(android.R.id.content)

    /** With a PIN set, the app screens stay hidden until it is typed, and again after 30 seconds away. */
    private fun checkLock() {
        if (!AppLock.isSet(prefs)) {
            content().visibility = View.VISIBLE
            return
        }
        if (unlocked && System.currentTimeMillis() - lastPause < 30_000) return
        unlocked = false
        showLock()
    }

    private fun pinBox(): EditText = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(8))
    }

    private fun showLock() {
        if (lockDialog?.isShowing == true) return
        content().visibility = View.INVISIBLE
        val box = pinBox().apply { hint = "PIN" }
        val d = AlertDialog.Builder(this).setTitle("Myraa is locked").setMessage("Type your PIN.").setView(box)
            .setCancelable(false)
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Close") { _, _ -> finish() }
            .setNeutralButton("Fingerprint", null)
            .create()
        d.setOnShowListener {
            if (android.os.Build.VERSION.SDK_INT >= 28) d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { askFingerprint(box, d) }
            else d.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = View.GONE
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val wait = AppLock.waitSeconds()
                if (wait > 0) {
                    box.error = "Too many tries. Wait $wait seconds."
                } else if (AppLock.check(prefs, box.text.toString())) {
                    unlockNow(d)
                } else {
                    box.setText("")
                    val w2 = AppLock.waitSeconds()
                    box.error = if (w2 > 0) "Too many tries. Wait $w2 seconds." else "Wrong PIN."
                }
            }
        }
        lockDialog = d
        d.show()
    }

    private fun unlockNow(d: AlertDialog) {
        unlocked = true
        lastPause = System.currentTimeMillis()
        content().visibility = View.VISIBLE
        d.dismiss()
    }

    /** Android's own fingerprint or face screen. It is a second way in, next to the PIN, and does nothing if none is set up. */
    @android.annotation.TargetApi(28)
    private fun askFingerprint(box: EditText, d: AlertDialog) {
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle("Unlock Myraa")
            .setNegativeButton("Use PIN", mainExecutor) { _, _ -> }
            .build()
        prompt.authenticate(android.os.CancellationSignal(), mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                unlockNow(d)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                // 1 = not available, 11 = none enrolled, 12 = no hardware. Cancelling is silent.
                if (errorCode == 1 || errorCode == 11 || errorCode == 12) box.error = "No fingerprint or face is set up on this phone."
            }
        })
    }

    private fun pinMenu() {
        if (!AppLock.isSet(prefs)) { askNewPin("Set a PIN"); return }
        AlertDialog.Builder(this).setTitle("App lock").setItems(arrayOf("Change PIN", "Turn off lock")) { _, i ->
            askCurrentPin { if (i == 0) askNewPin("New PIN") else { AppLock.clear(prefs); refresh() } }
        }.show()
    }

    private fun askCurrentPin(then: () -> Unit) {
        val box = pinBox()
        AlertDialog.Builder(this).setTitle("Current PIN").setView(box)
            .setPositiveButton("OK") { _, _ ->
                val wait = AppLock.waitSeconds()
                if (wait > 0) info("Wait a little", "Too many wrong tries. Try again in $wait seconds.")
                else if (AppLock.check(prefs, box.text.toString())) then()
                else info("Wrong PIN", "That is not your PIN.")
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun askNewPin(title: String) {
        val box = pinBox()
        AlertDialog.Builder(this).setTitle(title)
            .setMessage("4 to 8 digits. It keeps the Myraa app screens (settings, keys, accounts) closed. The orb and the wake word still work. If you forget it, you have to clear the app's data, which also removes your keys.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val pin = box.text.toString()
                val why = AppLock.problem(pin)
                if (why != null) {
                    info("Can't use that PIN", why)
                } else {
                    AppLock.set(prefs, pin)
                    unlocked = true
                    lastPause = System.currentTimeMillis()
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun editWakeWord() {
        val box = EditText(this).apply {
            setText(prefs.wakePhrase)
            filters = arrayOf(android.text.InputFilter.LengthFilter(20))
        }
        AlertDialog.Builder(this).setTitle("Wake word")
            .setMessage("One word, like myra or maya. \"Hey\" and \"okay\" before it work too. The offline listener only knows ordinary English words, so pick a common one and test it.")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> prefs.wakePhrase = box.text.toString(); refreshService(); refresh() }
            .setNegativeButton("Reset") { _, _ -> prefs.wakePhrase = "myra"; refreshService(); refresh() }
            .show()
    }

    private fun editStopCommand() {
        val box = EditText(this).apply {
            setText(prefs.stopCommand)
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        }
        AlertDialog.Builder(this).setTitle("Extra stop phrase")
            .setMessage("Say this to end a conversation, like \"bye bye\". Stop, cancel and thanks always work.")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> prefs.stopCommand = box.text.toString(); refresh() }
            .setNegativeButton("Clear") { _, _ -> prefs.stopCommand = ""; refresh() }
            .show()
    }

    /** One screen that says what Myraa may do, what is switched on, and where your words go. */
    private fun privacyCheck() {
        fun yn(b: Boolean) = if (b) "on" else "off"
        val sb = StringBuilder()
        sb.append("Allowed on this phone:\n")
        sb.append("- Microphone: ").append(yn(hasPerm(Manifest.permission.RECORD_AUDIO))).append('\n')
        sb.append("- Contacts: ").append(yn(hasPerm(Manifest.permission.READ_CONTACTS))).append('\n')
        sb.append("- Call log and answering calls: ").append(yn(hasPerm(Manifest.permission.READ_CALL_LOG) && hasPerm(Manifest.permission.ANSWER_PHONE_CALLS))).append('\n')
        sb.append("- Calendar: ").append(yn(hasPerm(Manifest.permission.READ_CALENDAR))).append('\n')
        sb.append("- Control other apps (Accessibility): ").append(yn(MyraAccess.enabled(this))).append('\n')
        sb.append("- Read notifications: ").append(yn(AutoReplyService.listening(this))).append('\n')
        sb.append("- Show over other apps: ").append(yn(Settings.canDrawOverlays(this))).append("\n\n")
        sb.append("Switched on in MYRA:\n")
        sb.append("- Wake word ").append(yn(prefs.wakeWord)).append(", auto-reply ").append(yn(prefs.autoReply))
            .append(", announce calls ").append(yn(prefs.announceCalls)).append(", theft guard ").append(yn(prefs.theftGuard))
            .append(", camera vision ").append(yn(prefs.cameraVision)).append(", app lock ").append(yn(AppLock.isSet(prefs))).append("\n\n")
        sb.append("Where your words go:\n")
        sb.append("- The wake word is recognised on the phone. Speech to text uses your phone's speech recognizer (usually Google's).\n")
        sb.append("- Your questions go to the AI engine you picked, and to services you added a key for (search, email, YouTube...). In laptop mode they travel through your own Supabase project.\n")
        sb.append("- Memories, notes, files and keys stay on this phone. Keys are sealed with the Android Keystore.")
        AlertDialog.Builder(this).setTitle("Privacy check").setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .setNeutralButton("Android permissions") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .show()
    }

    private fun chooseLanguage() {
        val names = languages.map { it.second }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Language for listening and speaking").setItems(names) { _, i ->
            prefs.language = languages[i].first
            prefs.voiceName = ""
            tts().applySettings()
            refreshService()
            refresh()
        }.show()
    }

    // ── AI engine: which AI the brain inside the app uses (Gemini, OpenAI, Claude, Groq, Ollama, custom) ──
    // The API key is sent to the brain and kept sealed inside the app (Android Keystore).
    private var aiCatalog = JSONArray()
    private var aiProvider = "off"
    private var aiSavedProvider = "off"
    private var aiHasKey = false
    private var aiFirst = false
    private var aiLoaded = false
    private lateinit var aiStatus: TextView
    private lateinit var aiKey: EditText
    private lateinit var aiModel: EditText
    private lateinit var aiUrl: EditText
    private lateinit var aiDetails: LinearLayout
    private lateinit var aiUrlBox: LinearLayout

    private fun aiEntry(id: String): JSONObject? {
        for (i in 0 until aiCatalog.length()) {
            val o = aiCatalog.optJSONObject(i)
            if (o != null && o.optString("id") == id) return o
        }
        return null
    }

    private fun aiName(id: String): String =
        if (id == "off") "Off (Antigravity only)" else (aiEntry(id)?.optString("name") ?: id)

    private fun aiSay(msg: String, color: Int) { aiStatus.text = msg; aiStatus.setTextColor(color) }

    private fun buildAiCard(): View {
        val ai = card("AI engine", "This is the AI that does the thinking. Pick one (Gemini and Groq have free keys), paste its key and tap Save. The key stays sealed inside this app.")
        aiStatus = text("", 13f, dim).apply { setPadding(0, px(10), 0, 0) }
        aiKey = input("", "Paste API key", secret = true)
        aiModel = input("", "Model", secret = false)
        aiUrl = input("", "https://your-server/v1", secret = false)
        aiUrlBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Base URL", 13f, dim).apply { setPadding(0, px(14), 0, 0) })
            addView(aiUrl, lp(top = 6))
        }
        aiDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("API key", 13f, dim).apply { setPadding(0, px(14), 0, 0) })
            addView(aiKey, lp(top = 6))
            addView(aiUrlBox)
            addView(text("Model", 13f, dim).apply { setPadding(0, px(14), 0, 0) })
            addView(aiModel, lp(top = 6))
            addView(valueRow("Suggested models", { "" }) { chooseAiModel() })
            addView(text("Which one answers first?", 13f, dim).apply { setPadding(0, px(14), 0, 0) })
            addView(segmented(listOf("agy" to "Antigravity first", "backup" to "This AI first"), { if (aiFirst) "backup" else "agy" }) { v ->
                aiFirst = v == "backup"
                refresh()
            }, lp(top = 8))
        }
        ai.addView(valueRow("AI", { aiName(aiProvider) }) { chooseAiProvider() }, lp(top = 8))
        ai.addView(aiDetails)
        ai.addView(row(
            Pill("Save", LineIcon.Kind.CHECK, Style.PRIMARY) { saveAi() },
            Pill("Test", LineIcon.Kind.REFRESH, Style.SECONDARY) { testAi() },
        ), lp(top = 14))
        ai.addView(aiStatus)
        refreshers += { ai.visibility = if (prefs.activeMode == "phone") View.VISIBLE else View.GONE }
        renderAi()
        loadAi()
        return ai
    }

    private fun renderAi() {
        val e = aiEntry(aiProvider)
        val needsKey = e?.optBoolean("needsKey", true) ?: true
        aiDetails.visibility = if (aiProvider == "off") View.GONE else View.VISIBLE
        aiUrlBox.visibility = if (aiProvider == "custom" || aiProvider == "ollama") View.VISIBLE else View.GONE
        aiUrl.hint = if (aiProvider == "ollama") "Blank = this phone (127.0.0.1:11434)" else "https://your-server/v1"
        aiKey.hint = when {
            aiHasKey -> "Key saved. Leave blank to keep it"
            needsKey -> "Paste API key"
            else -> "API key (optional)"
        }
        val def = e?.optString("defaultModel").orEmpty()
        aiModel.hint = if (def.isNotEmpty()) "Default: $def" else "Model name"
    }

    private fun loadAi() {
        brain.aiGet { j, err ->
            if (j == null) {
                if (!aiLoaded) aiSay(err ?: "Couldn't read the AI settings.", orange)
            } else {
                aiCatalog = j.optJSONArray("catalog") ?: JSONArray()
                aiSavedProvider = j.optString("provider", "off")
                aiProvider = aiSavedProvider
                aiHasKey = j.optBoolean("hasKey")
                aiHasKeyAtServer = aiHasKey
                aiFirst = j.optBoolean("fallbackFirst")
                aiModel.setText(j.optString("model"))
                aiUrl.setText(j.optString("baseUrl"))
                aiKey.setText("")
                aiLoaded = true
                renderAi()
                refresh()
                aiSay(if (aiProvider == "off") "No AI engine is set. Pick one to start." else "Saved: ${aiName(aiProvider)}.", dim)
            }
        }
    }

    private fun chooseAiProvider() {
        val ids = mutableListOf("off")
        for (i in 0 until aiCatalog.length()) aiCatalog.optJSONObject(i)?.optString("id")?.let { ids.add(it) }
        if (ids.size == 1) {
            aiSay("Loading the list…", dim)
            loadAi()
            return
        }
        val names = ids.map { aiName(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("AI engine").setItems(names) { _, i ->
            val id = ids[i]
            if (id != aiProvider) {
                aiProvider = id
                aiKey.setText("")
                aiModel.setText("")
                aiUrl.setText("")
            }
            aiHasKey = aiSavedProvider == id && id != "off" && aiHasKeyAtServer
            renderAi()
            refresh()
        }.show()
    }

    /** What the brain said last time about a saved key (so choosing the saved AI again keeps it). */
    private var aiHasKeyAtServer = false

    private fun chooseAiModel() {
        val list = aiEntry(aiProvider)?.optJSONArray("models")
        val names = ArrayList<String>()
        if (list != null) for (i in 0 until list.length()) names.add(list.optString(i))
        if (names.isEmpty()) { info("Model", "Type the model name your server uses."); return }
        AlertDialog.Builder(this).setTitle("Model").setItems(names.toTypedArray()) { _, i -> aiModel.setText(names[i]) }.show()
    }

    private fun aiBody(): JSONObject = JSONObject()
        .put("provider", aiProvider)
        .put("apiKey", aiKey.text.toString().trim())
        .put("model", aiModel.text.toString().trim())
        .put("baseUrl", aiUrl.text.toString().trim())
        .put("fallbackFirst", aiFirst)

    private fun saveAi() {
        aiSay("Saving…", dim)
        brain.aiSave(aiBody()) { j, err ->
            if (j == null) {
                aiSay(err ?: "Couldn't save.", bad)
            } else {
                aiSavedProvider = j.optString("provider", "off")
                aiProvider = aiSavedProvider
                aiHasKey = j.optBoolean("hasKey")
                aiHasKeyAtServer = aiHasKey
                aiFirst = j.optBoolean("fallbackFirst")
                aiKey.setText("")
                renderAi()
                refresh()
                aiSay(if (aiProvider == "off") "Saved. No AI engine." else "Saved ✓ ${aiName(aiProvider)}. Takes effect on the next question.", good)
            }
        }
    }

    private fun testAi() {
        if (aiProvider == "off") { aiSay("Pick an AI first.", orange); return }
        aiSay("Testing ${aiName(aiProvider)}…", dim)
        brain.aiTest(aiBody()) { j, err ->
            if (j == null) aiSay(err ?: "Test failed.", bad)
            else if (j.optBoolean("ok")) aiSay("Works ✓ ${j.optString("model")} answered: ${j.optString("reply")}", good)
            else aiSay(j.optString("error", "Test failed."), bad)
        }
    }

    private fun info(title: String, msg: String) = AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /* ── little view helpers ──────────────────────────────────────────────── */

    private fun px(v: Int) = (v * dp).toInt()

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = px(top) }

    private fun text(s: String, sp: Float, color: Int, face: Typeface? = null) = TextView(this).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (face != null) typeface = face
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = radiusDp * dp
        setColor(fill)
        if (stroke != 0) setStroke(maxOf(1, px(1)), stroke)
    }

    private fun ripple(content: Drawable?, radiusDp: Float, color: Int) =
        RippleDrawable(ColorStateList.valueOf(color), content, rounded(Color.WHITE, radiusDp))

    private fun card(title: String?, subtitle: String? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(18), px(16), px(18), px(16))
        background = rounded(cardColor, 22f, line)
        if (title != null) addView(text(title, 16f, ink, medium))
        if (subtitle != null) addView(text(subtitle, 13f, dim).apply { setPadding(0, px(3), 0, 0) })
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, px(1) / 2 + 1)).apply { topMargin = px(12) }
    }

    private fun iconTile(kind: LineIcon.Kind) = ImageView(this).apply {
        setImageDrawable(LineIcon(kind, orange, px(22)))
        scaleType = ImageView.ScaleType.CENTER
        background = rounded(Palette.ORANGE_DEEP, 14f, Palette.ORANGE_EDGE)
    }

    private fun steps(vararg lines: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        lines.forEachIndexed { i, s ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text("${i + 1}", 12f, orange, medium).apply {
                    gravity = Gravity.CENTER
                    background = rounded(Palette.ORANGE_DEEP, 100f)
                }, LinearLayout.LayoutParams(px(22), px(22)))
                addView(text(s, 14f, dim).apply { setPadding(px(12), px(1), 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, lp(top = if (i == 0) 0 else 10))
        }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = px(10) })
        }
    }

    private fun styleCursor(e: EditText) {
        e.highlightColor = 0x55FF8A1F
        if (Build.VERSION.SDK_INT >= 29) e.textCursorDrawable = GradientDrawable().apply { setColor(orange); setSize(px(2), 0) }
    }

    private fun input(value: String, hint: String, secret: Boolean) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(faint)
        setTextColor(ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        isSingleLine = true
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = Typeface.MONOSPACE
        setPadding(px(14), px(12), px(14), px(12))
        background = rounded(fieldColor, 14f, 0xFF2E3139.toInt())
        styleCursor(this)
    }

    private fun permissionRow(title: String, why: String, granted: () -> Boolean, ask: () -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(14), 0, 0) }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, ink))
            addView(text(why, 12.5f, dim).apply { setPadding(0, px(2), px(10), 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val done = ImageView(this).apply { setImageDrawable(LineIcon(LineIcon.Kind.CHECK, good, px(22))); scaleType = ImageView.ScaleType.CENTER }
        val btn = Pill("Allow", null, Style.PRIMARY) { ask() }.apply { minimumHeight = px(36); setPadding(px(18), 0, px(18), 0) }
        box.addView(FrameLayout(this).apply {
            addView(done, FrameLayout.LayoutParams(px(36), px(36), Gravity.CENTER))
            addView(btn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36), Gravity.CENTER))
        })
        refreshers += {
            val ok = granted()
            done.visibility = if (ok) View.VISIBLE else View.GONE
            btn.visibility = if (ok) View.GONE else View.VISIBLE
        }
        return box
    }

    private fun switchRow(title: String, why: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(12), 0, 0) }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, ink))
            addView(text(why, 12.5f, dim).apply { setPadding(0, px(2), px(10), 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        box.addView(Switch(this).apply {
            isChecked = value
            thumbTintList = ColorStateList(states, intArrayOf(orange, 0xFFB8BBC2.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(0x88FF8F2E.toInt(), 0xFF3A3E47.toInt()))
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
        return box
    }

    private fun valueRow(title: String, value: () -> String, onClick: () -> Unit): View {
        val v = text("", 14f, dim)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(12), 0, px(2))
            background = ripple(null, 10f, 0x22FFFFFF)
            isClickable = true
            setOnClickListener { onClick() }
            addView(text(title, 15f, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(v)
            addView(ImageView(context).apply { setImageDrawable(LineIcon(LineIcon.Kind.CHEVRON, faint, px(18))); scaleType = ImageView.ScaleType.CENTER },
                LinearLayout.LayoutParams(px(22), px(22)).apply { marginStart = px(4) })
        }
        refreshers += { v.text = value() }
        return box
    }

    private fun segmented(options: List<Pair<String, String>>, current: () -> String, pick: (String) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(fieldColor, 16f, line)
            setPadding(px(4), px(4), px(4), px(4))
        }
        for ((value, label) in options) {
            val t = text(label, 14f, dim, medium).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { pick(value) }
            }
            box.addView(t, LinearLayout.LayoutParams(0, px(42), 1f))
            refreshers += {
                val on = current() == value
                t.setTextColor(if (on) orange else dim)
                t.background = if (on) rounded(Palette.ORANGE_DEEP, 12f, 0xFF7A4515.toInt()) else ripple(null, 12f, 0x22FFFFFF)
            }
        }
        return box
    }

    private enum class Style { PRIMARY, SECONDARY, GHOST, QUIET }

    /** A rounded button with an optional line icon. */
    private inner class Pill(label: String, icon: LineIcon.Kind?, style: Style, onClick: () -> Unit) : LinearLayout(this@MainActivity) {
        private val labelView = TextView(context)
        private val iconDrawable = icon?.let { LineIcon(it, ink, px(20)) }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minimumHeight = px(48)
            setPadding(px(16), 0, px(16), 0)
            if (iconDrawable != null) addView(ImageView(context).apply { setImageDrawable(iconDrawable) }, LayoutParams(px(20), px(20)))
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            labelView.typeface = medium
            addView(labelView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (iconDrawable != null) marginStart = px(8) })
            setLabel(label)
            setStyle(style)
            setOnClickListener { onClick() }
        }

        fun setLabel(s: String) {
            labelView.text = s
            labelView.visibility = if (s.isEmpty()) GONE else VISIBLE
        }

        fun setStyle(s: Style) {
            val fg: Int
            background = when (s) {
                Style.PRIMARY -> {
                    fg = 0xFF1A0D00.toInt()
                    val fill = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFFFA444.toInt(), 0xFFFF7A12.toInt())).apply { cornerRadius = 100 * dp }
                    ripple(fill, 100f, 0x44FFFFFF)
                }
                Style.SECONDARY -> { fg = ink; ripple(rounded(0xFF1B1E24.toInt(), 100f, 0xFF2C3038.toInt()), 100f, 0x22FFFFFF) }
                Style.GHOST -> { fg = orange; ripple(null, 100f, 0x33FF8A1F) }
                Style.QUIET -> { fg = dim; ripple(null, 100f, 0x22FFFFFF) }
            }
            labelView.setTextColor(fg)
            iconDrawable?.color = fg
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            alpha = if (enabled) 1f else 0.4f
        }
    }
}
