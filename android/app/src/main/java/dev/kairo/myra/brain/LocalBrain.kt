package dev.kairo.myra.brain

import android.content.Context
import dev.kairo.myra.Prefs
import dev.kairo.myra.brain.files.FilesModule
import dev.kairo.myra.brain.files.IMAGE_MARK
import dev.kairo.myra.brain.life.LifeModule
import dev.kairo.myra.brain.life.JournalModule
import dev.kairo.myra.brain.life.ListsModule
import dev.kairo.myra.brain.life.PlacesModule
import dev.kairo.myra.brain.life.TasksModule
import dev.kairo.myra.brain.life.SshModule
import dev.kairo.myra.brain.life.TodoistModule
import dev.kairo.myra.brain.life.TelegramModule
import dev.kairo.myra.brain.life.NotionModule
import dev.kairo.myra.brain.life.GitLabModule
import dev.kairo.myra.brain.life.LinearModule
import dev.kairo.myra.brain.life.GoogleDriveModule
import dev.kairo.myra.brain.life.TriggersModule
import dev.kairo.myra.brain.life.VoiceModule
import dev.kairo.myra.brain.mark.DocModule
import dev.kairo.myra.brain.mark.MarkModule
import dev.kairo.myra.brain.mark.ProjectsModule
import dev.kairo.myra.brain.mark.GitHubModule
import dev.kairo.myra.brain.net.Keys
import dev.kairo.myra.brain.net.NetModule
import dev.kairo.myra.brain.net.SmartReply
import org.json.JSONObject
import java.io.InputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock

/**
 * Myraa's brain, living inside the app (it used to run in Termux). One per app process.
 *
 * It keeps the conversation, picks the tools (memory, files, mail, the phone's own hands...), asks the
 * chosen AI engine what to do, and answers the app over a small local server ([BrainServer]).
 */
class LocalBrain private constructor(private val ctx: Context, private val prefs: Prefs) {
    val env = BrainEnv(ctx, prefs)
    private val life = LifeModule(env)
    private val lists = ListsModule(env)
    private val places = PlacesModule(env)
    private val tasks = TasksModule(env)
    private val ssh = SshModule(env)
    private val todoist = TodoistModule(env)
    private val telegram = TelegramModule(env)
    private val notion = NotionModule(env)
    private val gitlab = GitLabModule(env)
    private val linear = LinearModule(env)
    private val gdrive = GoogleDriveModule(env)
    private val triggers = TriggersModule(env)
    private val voicePass = VoicePassModule(env)
    private val files = FilesModule(env)
    private val net = NetModule(env)
    private val mark = MarkModule(env)
    private val projects = ProjectsModule(env)
    private val github = GitHubModule(env)
    private val docs = DocModule(env)
    private val voice = VoiceModule(env)
    private val journal = JournalModule(env)
    private val hub = ToolHub(env, listOf(env.guard.module(env), life, lists, places, tasks, ssh, todoist, telegram, notion, gitlab, linear, gdrive, triggers, voicePass, files, net, mark, projects, github, docs, voice, journal))
    private val agent = Agent(env, hub) { systemText() }
    private val server = BrainServer(this, PORT)
    private val askLock = ReentrantLock()
    @Volatile private var memorySent = false
    @Volatile private var busy = false
    private var promptCache: String? = null

    fun token(): String = prefs.token

    /** Starts the local server and the background parts (reminder clock, automations). Safe to call again. */
    @Synchronized fun start() {
        if (prefs.token.isEmpty()) {
            val b = ByteArray(18).also { SecureRandom().nextBytes(it) }
            prefs.token = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }
        env.askFn = { text, unattended -> ask(text, unattended) {} }
        env.onStop = { agent.cancelled = true; agent.reset(); memorySent = false }
        life.start()
        net.start()
        mark.start()
        server.start()
    }

    @Synchronized fun stop() {
        life.stop()
        net.stop()
        mark.stop()
        server.stop()
    }

    val running get() = server.isRunning

    private fun systemText(): String {
        promptCache?.let { return it + voice.promptExtra() + personalBlock() }
        val raw = try { ctx.assets.open("myra-phone.md").bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
        val body = raw.replace(Regex("^---[\\s\\S]*?---\\s*"), "").replace("{{ABOUT}}", "")
        val text = body.ifBlank { "You are Myraa, a warm, soft-spoken voice companion for Anuj on his Android phone. Everything you say is read aloud: one or two short spoken sentences, Hinglish, no markdown. Use your tools; never say something is done unless a tool showed it." }
        promptCache = text
        return text + voice.promptExtra() + personalBlock()
    }

    /** What the user told Myraa about themselves in Settings, Personal: name and the gender used for Hindi verbs. */
    private fun personalBlock(): String {
        val o = env.conf.obj("personal") ?: return ""
        val name = o.optString("name").trim()
        val gender = o.optString("gender").trim()
        val lines = ArrayList<String>()
        if (name.isNotEmpty()) lines.add("Call the user \"$name\".")
        when (gender) {
            "male" -> lines.add("The user is male: use masculine Hindi verb forms (kar raha hoon, bola).")
            "female" -> lines.add("The user is female: use feminine Hindi verb forms (kar rahi hoon, bola).")
        }
        return if (lines.isEmpty()) "" else "\n\nAbout the user (from Settings):\n" + lines.joinToString("\n")
    }

    /* ── asking ──────────────────────────────────────────────────────────── */

    /** One question in, one spoken answer out. Questions run one at a time. Throws with a reason in plain words. */
    fun ask(text: String, unattended: Boolean, onTool: (String) -> Unit): String {
        askLock.lock()
        try {
            busy = true
            env.guard.check("ask") // refuses while an emergency stop is on
            env.guard.unattended = unattended
            val idle = agent.lastUsed != 0L && System.currentTimeMillis() - agent.lastUsed > IDLE_RESET_MS
            if (idle) { agent.reset(); memorySent = false }
            var q = text
            if (!memorySent) { q = life.memoryBlock() + "\n" + q; memorySent = true }
            return agent.ask(q, attachedImages(text), onTool)
        } finally {
            env.guard.unattended = false
            busy = false
            askLock.unlock()
        }
    }

    /** The app stopped waiting; the running question stops at its next step. */
    fun cancelCurrent() { agent.cancelled = true }

    fun reset() { agent.reset(); memorySent = false }

    // Pictures the app attached are marked in the question as [[image:/path]]. Only files saved by the
    // attach upload are loaded (up to 6 pictures, about 16 MB in all).
    private fun attachedImages(text: String): List<Img> {
        val out = ArrayList<Img>()
        var total = 0L
        for (m in IMAGE_MARK.findAll(text)) {
            val img = files.imageFor(m.groupValues[1]) ?: continue
            if (out.any { it.path == img.path }) continue
            total += img.data.length
            if (out.size >= 6 || total > 16_000_000) break
            out.add(img)
        }
        return out
    }

    /* ── what the server serves ──────────────────────────────────────────── */

    fun health(): JSONObject {
        val c = Llm.engine(env.conf)
        val model = if (c == null) "no AI engine yet" else "${c.optString("provider")} ${runCatching { Llm.resolve(c).model }.getOrDefault("")}".trim()
        return JSONObject().put("ok", true).put("version", VERSION).put("agy", false).put("busy", busy).put("model", model)
            .put("stopped", env.guard.stopped()).put("backup", "off").put("pc", net.pcStatus())
    }

    fun keysView(): JSONObject = Keys.view(env.conf)

    /** (ok, view, error) */
    fun keysSave(body: JSONObject): Triple<Boolean, JSONObject, String> {
        val r = Keys.save(env.conf, body)
        if (!r.ok) return Triple(false, JSONObject(), r.error ?: "Those keys were not saved.")
        env.conf.save()
        env.log("keys updated from the app")
        return Triple(true, r.view ?: Keys.view(env.conf), "")
    }

    fun aiView(): JSONObject {
        val f = env.conf.obj("fallback")
        return JSONObject().put("ok", true).put("provider", f?.optString("provider")?.ifEmpty { "off" } ?: "off")
            .put("model", f?.optString("model").orEmpty()).put("baseUrl", f?.optString("baseUrl").orEmpty())
            .put("hasKey", !f?.optString("apiKey").isNullOrEmpty()).put("fallbackFirst", false).put("catalog", Llm.CATALOG)
    }

    private fun aiNext(b: JSONObject): Pair<JSONObject?, String> {
        val provider = b.optString("provider").trim().lowercase()
        var known = provider == "off"
        for (i in 0 until Llm.CATALOG.length()) if (Llm.CATALOG.getJSONObject(i).optString("id") == provider) known = true
        if (!known) return null to "Unknown provider \"$provider\"."
        if (provider == "off") return null to ""
        val old = env.conf.obj("fallback")
        val next = JSONObject().put("provider", provider)
        val key = b.optString("apiKey").trim()
        if (key.isNotEmpty()) next.put("apiKey", key) else if (old != null && old.optString("provider") == provider && old.optString("apiKey").isNotEmpty()) next.put("apiKey", old.optString("apiKey"))
        if (b.optString("model").trim().isNotEmpty()) next.put("model", b.optString("model").trim())
        if (b.optString("baseUrl").trim().isNotEmpty()) next.put("baseUrl", b.optString("baseUrl").trim())
        return next to ""
    }

    /** (ok, view, error) */
    fun aiSave(b: JSONObject): Triple<Boolean, JSONObject, String> {
        val (next, err) = aiNext(b)
        if (err.isNotEmpty()) return Triple(false, JSONObject(), err)
        env.conf.update { if (next == null) it.remove("fallback") else it.put("fallback", next) }
        agent.reset(); memorySent = false
        env.log("AI engine set from the app:", next?.optString("provider") ?: "off")
        return Triple(true, aiView(), "")
    }

    fun aiTest(b: JSONObject): JSONObject {
        val (next, err) = aiNext(b)
        if (err.isNotEmpty()) return JSONObject().put("ok", false).put("error", err)
        if (next == null) return JSONObject().put("ok", false).put("error", "Nothing to test: no AI engine chosen.")
        return try { Llm.ping(next).put("ok", true) } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "Test failed.") }
    }

    fun ingestZip(name: String, input: InputStream): JSONObject = files.ingestZip(name, input)
    fun ingestAttachment(name: String, input: InputStream): JSONObject = files.ingestAttachment(name, input)
    fun smartReply(body: JSONObject): JSONObject = SmartReply(env).reply(body)

    companion object {
        const val PORT = 8765
        const val VERSION = "2.1.0-inapp"
        private const val IDLE_RESET_MS = 30 * 60_000L

        @Volatile private var instance: LocalBrain? = null

        /** The brain of this app, created on first use. */
        @Synchronized fun get(ctx: Context, prefs: Prefs): LocalBrain =
            instance ?: LocalBrain(ctx.applicationContext, prefs).also { instance = it }
    }
}
