package dev.kairo.myra

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * Myraa itself: the orb, the wake word, and one conversation turn at a time -
 * listen, think (the brain in Termux), speak, and listen again if Myraa asked a question.
 */
class MyraService : Service() {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var lastScreenEvent = 0L
    // Power button: each press turns the screen on or off, so two screen changes within 600 ms is a double press.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            // Five quick screen changes (power button) start the SOS countdown, if SOS is on.
            if (Sos.onPowerPress(this@MyraService)) { main.post { Sos.start(this@MyraService) }; return }
            if (Sos.enabled(this@MyraService)) return // SOS on: the two-press talk shortcut is off so presses count for SOS
            if (!prefs.powerDoubleTap) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastScreenEvent < 600) {
                lastScreenEvent = 0L
                main.post { activate() }
            } else {
                lastScreenEvent = now
            }
        }
    }
    // Charger plugged in / pulled out, and battery level changes: the "charging" triggers (TriggerEngine).
    private var wasPlugged: Boolean? = null
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val ctx = this@MyraService
            val plugged = when (i?.action) {
                Intent.ACTION_POWER_CONNECTED -> true
                Intent.ACTION_POWER_DISCONNECTED -> false
                Intent.ACTION_BATTERY_CHANGED -> {
                    // Sticky: also arrives at once on registering. Plug changes seen here count too (some phones skip the other two).
                    val now = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                    val before = wasPlugged
                    wasPlugged = now
                    Thread { runCatching { dev.kairo.myra.brain.TriggerEngine.onBatteryChanged(ctx) } }.start()
                    if (before == null || before == now) return
                    now
                }
                else -> return
            }
            wasPlugged = plugged
            Thread { runCatching { dev.kairo.myra.brain.TriggerEngine.onPower(ctx, plugged) } }.start()
        }
    }
    private lateinit var overlays: Overlays
    private lateinit var termux: BrainClient
    private lateinit var relay: RelayClient
    private val brain: Brain get() = if (prefs.activeMode == "laptop") relay else termux
    private lateinit var speaker: Speaker
    private lateinit var listener: Listener
    private lateinit var wake: WakeWord
    private var device: DeviceServer? = null // Myraa's hands: tools that run inside this app (see DeviceServer)
    private var state = State.IDLE
    private var calls: CallWatcher? = null // tells Myraa a call is ringing
    private var pendingCall = false // Myraa asked "pick up or decline?" and waits for the answer
    private var turn = 0 // bumps on every new turn, so a late answer from an old turn is ignored

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        if (!goForeground()) { stopSelf(); return }
        instance = this
        prefs.running = true
        runCatching { AntiTheft.restore(this, prefs) }
        runCatching { Scheduler.rescheduleAll(this) } // saved scheduled tasks back on the clock
        val screenFilter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_EXPORTED)
        else registerReceiver(screenReceiver, screenFilter)
        main.post(triggerTick) // battery and clock triggers, about once a minute
        val powerFilter = IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(Intent.ACTION_BATTERY_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(powerReceiver, powerFilter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(powerReceiver, powerFilter)
        overlays = Overlays(this, prefs, onOrbTap = { activate() }, onOrbLongPress = { openApp() })
        termux = BrainClient(prefs)
        relay = RelayClient(this, prefs)
        speaker = Speaker(this, prefs)
        listener = Listener(this, prefs)
        wake = WakeWord(
            this,
            onWake = { if (state == State.IDLE) listen() },
            onSleep = { if (state == State.LISTENING) finish(quiet = true) },
        )
        device = DeviceServer(this, prefs).also { it.start() }
        dev.kairo.myra.brain.LocalBrain.get(this, prefs).start() // the brain lives in this app now (no Termux)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (instance == null) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_ACTIVATE -> activate()
            ACTION_ASK -> intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { finish(quiet = true); handle(it, docFrom(intent)) }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else -> {
                // Camera switch or permission may have changed: re-declare the foreground type (cheap, same notification).
                if (prefs.cameraVision != cameraReady) goForeground()
                applySettings()
            }
        }
        return START_NOT_STICKY
    }

    private fun applySettings() {
        if (prefs.orb) overlays.showOrb() else overlays.hideOrb()
        speaker.applySettings()
        updateCalls()
        if (state == State.IDLE) {
            wake.refreshWord()
            if (prefs.wakeWord && wake.modelReady && hasMic()) wake.start() else wake.pause()
            // First run: fetch the offline "Hey Myraa" model by itself, then start listening.
            if (prefs.wakeWord && !wake.modelReady && hasMic() && !wakeDownloading) {
                wakeDownloading = true
                wake.download(progress = {}) { ok, _ ->
                    wakeDownloading = false
                    if (ok) applySettings()
                }
            }
        }
    }

    private var wakeDownloading = false

    /* ── incoming calls ───────────────────────────────────────────────────── */

    private fun updateCalls() {
        val ok = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        if (prefs.announceCalls && ok) {
            val w = calls ?: CallWatcher(this, { who -> main.post { callRinging(who) } }, { main.post { callEnded() } }).also { calls = it }
            w.start()
        } else {
            calls?.stop()
        }
    }

    private fun callRinging(who: String) {
        if (state != State.IDLE) finish(quiet = true)
        // Myraa takes the call herself: only unknown numbers by default, every call when "unknown only" is off.
        if (prefs.callAssistant && !(prefs.callAssistantUnknownOnly && calls?.callerIsContact() == true)) {
            val label = who
            if (calls?.accept() == true) { CallAssistant(this, prefs, label, { calls?.reject() == true }, {}).start(); return }
        }
        pendingCall = true
        say(if (who.isBlank()) "Anuj, koi call aa raha hai. Uthaun, kaat doon, ya main baat kar loon?" else "Anuj, $who ka call aa raha hai. Uthaun, kaat doon, ya main baat kar loon?", listenAfter = true)
    }

    private fun callEnded() {
        if (!pendingCall) return
        pendingCall = false
        finish(quiet = true)
    }

    /** Anuj's answer to "pick up or decline?" */
    private fun callReply(t: String) {
        when {
            CALL_ASSISTANT.containsMatchIn(t) -> {
                pendingCall = false
                val who = calls?.callerLabel().orEmpty()
                if (calls?.accept() == true) {
                    finish(quiet = true)
                    CallAssistant(this, prefs, who, { calls?.reject() == true }, {}).start()
                } else say("Mujhse call uthaya nahi gaya, aap utha lijiye.")
            }
            CALL_NO.containsMatchIn(t) -> {
                pendingCall = false
                if (calls?.reject() == true) finish(quiet = true) else say("Mujhse call kata nahi, aap kaat dijiye.")
            }
            CALL_YES.containsMatchIn(t) -> {
                pendingCall = false
                if (calls?.accept() == true) finish(quiet = true) else say("Mujhse call uthaya nahi gaya, aap utha lijiye.")
            }
            else -> say("Uthaun, kaat doon, ya main baat kar loon?", listenAfter = true)
        }
    }

    /* ── one turn ─────────────────────────────────────────────────────────── */

    /** The orb, the tile, the shortcut and the notification all land here: talk, or stop. */
    fun activate() {
        if (state == State.IDLE) listen() else finish()
    }

    private fun listen() {
        if (!hasMic()) { openApp(); return }
        val my = ++turn
        state = State.LISTENING
        wake.pause()
        speaker.stop()
        buzz()
        overlays.orb.active = true
        overlays.showGlow(EdgeGlowView.Mode.LISTENING)
        overlays.caption("Listening…")
        listener.start(object : Listener.Callback {
            override fun onLevel(level: Float) { if (my == turn) overlays.glow.setInput(level) }
            override fun onPartial(text: String) { if (my == turn) overlays.caption(text) }
            override fun onText(text: String) { if (my == turn) handle(text) }
            override fun onNothing(message: String?) { if (my == turn) { if (message != null) say(message) else finish() } }
        })
    }

    /** The attached document that came with an ask, if any. */
    private fun docFrom(i: Intent): Doc? {
        val name = i.getStringExtra(EXTRA_DOC_NAME) ?: return null
        val text = i.getStringExtra(EXTRA_DOC_TEXT) ?: return null
        return Doc(
            name, text, i.getBooleanExtra(EXTRA_DOC_TRUNC, false), i.getStringExtra(EXTRA_DOC_ZIP), i.getIntExtra(EXTRA_DOC_ZIPFILES, 0),
            images = i.getStringArrayExtra(EXTRA_DOC_IMAGES)?.toList() ?: emptyList(),
            savedAt = i.getStringExtra(EXTRA_DOC_SAVED), note = i.getStringExtra(EXTRA_DOC_NOTE), kind = i.getStringExtra(EXTRA_DOC_KIND) ?: "text",
        )
    }

    private fun handle(text: String, doc: Doc? = null) {
        val t = text.lowercase().trim().trimEnd('.', '!', '?', ',')
        when {
            t in STOP_WORDS || (prefs.stopCommand.isNotEmpty() && t == prefs.stopCommand) -> { pendingCall = false; finish() }
            pendingCall -> callReply(t)
            NEW_CHAT.matches(t) -> {
                say("Starting a new conversation.")
                brain.reset()
            }
            prefs.activeMode == "laptop" && SCREENSHOT.containsMatchIn(t) -> screenshot()
            else -> ask(text, retried = false, doc = doc)
        }
    }

    private fun ask(text: String, retried: Boolean, doc: Doc? = null) {
        val my = ++turn
        state = State.THINKING
        overlays.orb.active = true
        overlays.showGlow(EdgeGlowView.Mode.THINKING)
        overlays.setGlowMode(EdgeGlowView.Mode.THINKING)
        overlays.caption(if (doc == null) "“$text”" else "${Documents.icon(doc)} ${doc.name} · “$text”")
        brain.ask(if (doc == null) text else Documents.prompt(doc, text), object : Brain.Listener {
            override fun onTool(name: String) { if (my == turn) overlays.caption(toolLabel(name)) }
            override fun onReply(text: String, images: List<java.io.File>, speech: Speech?) { if (my == turn) { showShots(images); say(text.ifBlank { "Done." }, speech) } }
            override fun onError(message: String, brainDown: Boolean) {
                if (my != turn) return
                if (brainDown && !retried && prefs.activeMode == "phone") {
                    // The brain is part of this app: start it again and retry once.
                    dev.kairo.myra.brain.LocalBrain.get(this@MyraService, prefs).start()
                    overlays.caption("Waking my brain…")
                    main.postDelayed({ if (my == turn) ask(text, retried = true, doc = doc) }, 1500)
                } else if (brainDown) {
                    say("My brain inside the app isn't answering. Please open the Myraa app once and try again.")
                } else {
                    say(message)
                }
            }
        })
    }

    /** "Send me a screenshot": straight to the laptop, no thinking needed. */
    private fun screenshot() {
        val my = ++turn
        state = State.THINKING
        overlays.orb.active = true
        overlays.showGlow(EdgeGlowView.Mode.THINKING)
        overlays.setGlowMode(EdgeGlowView.Mode.THINKING)
        overlays.caption("Getting your laptop screen…")
        brain.screenshot(object : Brain.Listener {
            override fun onTool(name: String) { if (my == turn) overlays.caption(toolLabel(name)) }
            override fun onReply(text: String, images: List<java.io.File>, speech: Speech?) { if (my == turn) { showShots(images); say(text, speech) } }
            override fun onError(message: String, brainDown: Boolean) { if (my == turn) say(message) }
        })
    }

    private fun showShots(images: List<java.io.File>) {
        if (images.isEmpty()) return
        // Allowed from the background because Myraa may draw over other apps.
        runCatching {
            startActivity(Intent(this, ShotActivity::class.java)
                .putExtra(ShotActivity.EXTRA_FILES, images.map { it.absolutePath }.toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        }
    }

    /** [speech]: the laptop's recording of [text] in Myraa's voice, when it sent one. */
    private fun say(text: String, speech: Speech? = null, listenAfter: Boolean = false) {
        val my = ++turn
        state = State.SPEAKING
        wake.pause() // his own voice says "Myraa" too; finish() brings the wake word back after a short pause
        overlays.orb.active = true
        overlays.showGlow(EdgeGlowView.Mode.SPEAKING)
        overlays.setGlowMode(EdgeGlowView.Mode.SPEAKING)
        overlays.caption(Speaker.clean(text))
        speaker.speak(text, speech = speech, l = object : Speaker.Listener {
            override fun onWord() { if (my == turn) { overlays.glow.pulse(); AvatarView.pulse() } }
            override fun onDone() {
                if (my != turn) return
                // Myraa asked something: listen for the answer straight away.
                if (listenAfter || (prefs.followUp && text.trim().endsWith("?"))) main.postDelayed({ if (my == turn) listen() }, 250)
                else finish()
            }
        })
    }

    private fun finish(quiet: Boolean = false) {
        turn++
        state = State.IDLE
        listener.stop()
        speaker.stop()
        brain.cancel()
        overlays.orb.active = false
        overlays.hideGlow()
        if (quiet) overlays.hideCaption() else overlays.hideCaption(2500)
        if (prefs.wakeWord && wake.modelReady) main.postDelayed({ if (state == State.IDLE) wake.start() }, 400)
    }

    private fun toolLabel(name: String) = if (name.contains(' ')) name else when (name) {
        "pc_status" -> "Checking your PC…"
        "pc_open" -> "Opening it on your PC…"
        "pc_screenshot" -> "Looking at your PC screen…"
        "pc_lock" -> "Locking your PC…"
        "pc_media" -> "Media keys on your PC…"
        "pc_myra" -> "Handing it to Myraa on your PC…"
        "claude_code" -> "Asking Claude Code on your PC…"
        "search_web" -> "Searching the web…"
        "read_url_content" -> "Reading a page…"
        "run_command" -> "Using the phone…"
        else -> "Working…"
    }

    /* ── plumbing ─────────────────────────────────────────────────────────── */

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun buzz() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        runCatching { v.vibrate(VibrationEffect.createOneShot(18, 120)) }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Myraa", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Myraa is ready to listen"
            setShowBadge(false)
        })
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val talk = PendingIntent.getService(this, 1, intent(this, ACTION_ACTIVATE), flags)
        val stop = PendingIntent.getService(this, 2, intent(this, ACTION_STOP), flags)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), flags)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_orb)
            .setContentTitle("Myraa is ready")
            .setContentText(if (prefs.wakeWord) "Say \"Hey Myraa\" or tap the orb." else "Tap the orb to talk.")
            .setContentIntent(open)
            .setOngoing(true)
            .setColor(0xFFFF8A1F.toInt())
            .addAction(Notification.Action.Builder(null, "Talk", talk).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        // Camera in the background only works if the service itself holds a "camera" foreground type, and Android
        // grants that only when it is started while the app is on screen. So try mic+camera first and fall back to
        // mic only (the invisible CameraCaptureActivity then does the photo).
        cameraReady = false
        if (Build.VERSION.SDK_INT >= 30 && prefs.cameraVision &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                cameraReady = true
                return true
            } catch (e: Exception) {
                Log.w("Myraa", "camera foreground type refused, using mic only: ${e.message}")
            }
        }
        return try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION_ID, n)
            true
        } catch (e: Exception) {
            // No microphone permission yet, or Android won't let the mic start from the background.
            Log.w("Myraa", "can't start in the foreground: ${e.message}")
            false
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (instance != null) overlays.onScreenChanged()
    }

    private val triggerTick = object : Runnable {
        override fun run() {
            Thread { runCatching { dev.kairo.myra.brain.TriggerEngine.tick(this@MyraService) } }.start()
            main.postDelayed(this, 60_000L)
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(triggerTick)
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(powerReceiver) }
        if (instance === this) {
            instance = null
            cameraReady = false
            prefs.running = false
            finish(quiet = true)
            overlays.removeAll()
            calls?.stop()
            device?.stop()
            wake.release()
            speaker.shutdown()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.kairo.myra.START"
        const val ACTION_ACTIVATE = "dev.kairo.myra.ACTIVATE"
        const val ACTION_ASK = "dev.kairo.myra.ASK"
        const val ACTION_STOP = "dev.kairo.myra.STOP"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DOC_NAME = "doc_name"
        const val EXTRA_DOC_TEXT = "doc_text"
        const val EXTRA_DOC_ZIP = "doc_zip"
        const val EXTRA_DOC_ZIPFILES = "doc_zipfiles"
        const val EXTRA_DOC_TRUNC = "doc_truncated"
        const val EXTRA_DOC_IMAGES = "doc_images"
        const val EXTRA_DOC_SAVED = "doc_saved"
        const val EXTRA_DOC_NOTE = "doc_note"
        const val EXTRA_DOC_KIND = "doc_kind"
        private const val CHANNEL = "myra"
        private const val NOTIFICATION_ID = 7
        private val STOP_WORDS = setOf("stop", "cancel", "never mind", "nevermind", "nothing", "shut up", "be quiet", "that's all", "thats all", "thank you", "thanks")
        private val CALL_ASSISTANT = Regex("(baat kar|baat karo|sambhal|sambhaal|handle|assistant|myraa|tum utha|tum hi|you talk|you take|screen)", RegexOption.IGNORE_CASE)
        private val CALL_NO = Regex("(\\b(no|nahi|nahin|nope|kaat|kato|cut|reject|decline|mat)\\b)|नहीं|नही|काट|कट|मत")
        private val CALL_YES = Regex("(\\b(yes|yeah|haan|han|ha|uthao|utha|pick|accept|answer)\\b)|हाँ|हां|उठा")
        private val SCREENSHOT = Regex("\\b(screenshot|screen ?shot|my (laptop|pc|computer) screen|what'?s on (my|the) (laptop|pc|computer|screen))\\b")
        private val NEW_CHAT = Regex("^(start )?(a )?new (chat|conversation)|start over|reset( the)? (chat|conversation)$")

        /** True while the service holds the camera foreground type, so a photo can be taken from the background. */
        @Volatile var cameraReady = false
            private set

        @Volatile var instance: MyraService? = null
            private set
        val running: Boolean get() = instance != null

        /**
         * Says [text] out loud in Myraa's voice (used by the "speak" trigger action).
         * False if he can't right now: not running, mid-conversation, or the phone is in a call. The caller then shows a notification instead.
         */
        fun speakAlert(text: String): Boolean {
            val svc = instance ?: return false
            val audio = svc.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (svc.state != State.IDLE || audio.mode != android.media.AudioManager.MODE_NORMAL) return false
            svc.main.post { if (svc.state == State.IDLE) svc.say(text) }
            return true
        }

        fun intent(c: Context, action: String): Intent = Intent(c, MyraService::class.java).setAction(action)

        /** Start (or poke) the service. Call from something the user is looking at. */
        fun send(c: Context, action: String, text: String? = null, doc: Doc? = null) {
            val i = intent(c, action)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            if (doc != null) i.putExtra(EXTRA_DOC_NAME, doc.name).putExtra(EXTRA_DOC_TEXT, doc.text).putExtra(EXTRA_DOC_ZIP, doc.zip).putExtra(EXTRA_DOC_ZIPFILES, doc.zipFiles)
                .putExtra(EXTRA_DOC_TRUNC, doc.truncated).putExtra(EXTRA_DOC_IMAGES, doc.images.toTypedArray()).putExtra(EXTRA_DOC_SAVED, doc.savedAt).putExtra(EXTRA_DOC_NOTE, doc.note).putExtra(EXTRA_DOC_KIND, doc.kind)
            // Already running in the foreground: a plain start. Otherwise Android requires the
            // foreground start, and the service promotes itself in onCreate.
            runCatching { if (running) c.startService(i) else c.startForegroundService(i) }
        }
    }
}
