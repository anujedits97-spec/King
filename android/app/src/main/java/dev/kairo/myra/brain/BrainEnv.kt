package dev.kairo.myra.brain

import android.content.Context
import android.util.Log
import dev.kairo.myra.DeviceServer
import dev.kairo.myra.Prefs
import dev.kairo.myra.Vault
import org.json.JSONObject
import java.io.File

/**
 * The brain's settings file (what ~/.myra/config.json was in Termux): the AI engine, keys and accounts,
 * Telegram bots, the paired PC. The file is sealed with the Android Keystore ([Vault]), so keys are
 * never readable on disk. Everything is a plain JSONObject so the ported modules can keep their shape.
 */
class BrainConfig(private val file: File) {
    @Volatile var root: JSONObject = load()

    private fun load(): JSONObject = try {
        if (file.exists()) JSONObject(Vault.open(file.readText())) else JSONObject()
    } catch (e: Exception) { JSONObject() }

    /** Writes the file. Call after changing [root] (or use [update]). */
    @Synchronized fun save() {
        file.parentFile?.mkdirs()
        file.writeText(Vault.seal(root.toString()))
    }

    @Synchronized fun update(block: (JSONObject) -> Unit) { block(root); save() }

    /** A sub-object, or null when it isn't set. */
    fun obj(key: String): JSONObject? = root.optJSONObject(key)
    fun str(key: String, def: String = ""): String = if (root.isNull(key)) def else root.optString(key, def)
}

/**
 * Everything a ported module needs from the app. One per running brain.
 *
 *  - [dir]: the brain's private folder (memories.json, reminders.json, notes.json ... live here)
 *  - [workspace]: where files Myraa makes live (the old ~/myra-workspace)
 *  - [outDir]: office files, zips and pictures Myraa makes (the old "outputs" folder)
 */
class BrainEnv(val ctx: Context, val prefs: Prefs) {
    val dir: File = File(ctx.filesDir, "myra").also { it.mkdirs() }
    val workspace: File = File(ctx.filesDir, "myra-workspace").also { it.mkdirs() }
    val outDir: File = File(dir, "outputs").also { it.mkdirs() }
    val conf = BrainConfig(File(dir, "config.json"))
    val guard = Guard(dir)

    fun log(vararg parts: Any?) { Log.i("MyraBrain", parts.joinToString(" ") { it.toString() }) }

    /** One tool of the Myraa app itself (screen, apps, media...), called in-process. Returns the text it said. */
    fun deviceCall(name: String, args: JSONObject = JSONObject()): String {
        val d = DeviceServer.instance ?: throw dev.kairo.myra.Fail("The Myraa app's hands aren't running right now.")
        return d.invoke(name, args).text
    }

    /** One question to the chosen AI engine. No tools, no history. Throws [NoEngineError] when none is set up. */
    fun oneShot(system: String, prompt: String, maxTokens: Int = 4096, timeoutMs: Int = 90_000, temperature: Double = 0.2): String =
        Llm.oneShot(conf, system, prompt, maxTokens, timeoutMs, temperature)

    /** One question with pictures (JPEG base64). Throws if the chosen engine can't see pictures. */
    fun oneShotVision(system: String, prompt: String, jpegs: List<String>, maxTokens: Int = 2000, timeoutMs: Int = 120_000): String =
        Llm.oneShotVision(conf, system, prompt, jpegs, maxTokens, timeoutMs)

    /**
     * Asks the full agent a question (set by [LocalBrain]). Scheduled goals use this with [unattended] true,
     * so HIGH-risk tools are refused because nobody is there to say yes.
     */
    @Volatile var askFn: (text: String, unattended: Boolean) -> String = { _, _ -> throw dev.kairo.myra.Fail("The brain isn't ready yet.") }

    /** Modules set this to hear about every finished tool call (the activity log). */
    @Volatile var onToolDone: (name: String, args: JSONObject, ok: Boolean, out: String) -> Unit = { _, _, _, _ -> }

    /** Called when an emergency stop happens, so the agent forgets its running conversation. */
    @Volatile var onStop: () -> Unit = {}
}
