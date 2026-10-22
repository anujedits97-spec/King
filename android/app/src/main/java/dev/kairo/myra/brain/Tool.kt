package dev.kairo.myra.brain

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** How much a tool can hurt. HIGH ones reach other people or delete, and always need a spoken yes. */
enum class Risk { LOW, MEDIUM, HIGH }

/**
 * One tool the AI can call. [run] gets the arguments the model sent and returns the sentence Myraa says back.
 * It throws [dev.kairo.myra.Fail] (or any Exception) with a plain-words reason when it can't do it;
 * the agent turns that into "Failed: ..." for the model.
 */
class BrainTool(
    val name: String,
    val description: String,
    val schema: JSONObject,
    val risk: Risk = Risk.MEDIUM,
    val run: (JSONObject) -> String,
)

/** A group of tools. [tools] is asked on every question, so it may change (e.g. the PC going on or offline). */
interface ToolModule {
    fun tools(): List<BrainTool>
}

/* ── schema helpers, so modules write tools in one or two lines ─────────────────────────────── */

fun prop(type: String, desc: String = "", options: List<String>? = null): JSONObject {
    val o = JSONObject().put("type", type)
    if (desc.isNotEmpty()) o.put("description", desc)
    if (options != null) o.put("enum", JSONArray(options))
    return o
}

/** An object schema: `schema(listOf("to"), "to" to prop("string"), "text" to prop("string"))`. */
fun schema(required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>): JSONObject {
    val p = JSONObject()
    for ((k, v) in props) p.put(k, v)
    return JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required)).put("additionalProperties", false)
}

/** Shorthand: a tool whose risk and schema are given in place. */
fun tool(name: String, description: String, risk: Risk, schema: JSONObject, run: (JSONObject) -> String) =
    BrainTool(name, description, schema, risk, run)

/**
 * Two-step confirmation for tools that reach people or delete things (port of confirm.mjs).
 * The first call returns a token and does nothing; Myraa must ask out loud and only after a clear yes
 * call again with the same details and the token. A token fits one exact request and expires in a minute.
 */
class ConfirmGate(private val who: String = "Anuj") {
    private val tokens = HashMap<String, Pair<String, Long>>()

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Returns null when confirmed, otherwise the sentence that tells Myraa to ask first. Throws on a wrong token. */
    @Synchronized
    fun gate(kind: String, payload: String, token: String?, sentence: String): String? {
        val now = System.currentTimeMillis()
        tokens.values.removeAll { it.second < now }
        val fp = sha("$kind|$payload")
        if (token.isNullOrEmpty()) {
            val t = ByteArray(5).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            tokens[t] = fp to (now + 60_000)
            return "Not done yet. Ask $who out loud: $sentence Only if he clearly says yes, call $kind again within one minute with the same details and confirm_token $t."
        }
        val e = tokens.remove(token)
        if (e == null || e.first != fp || e.second < now) throw dev.kairo.myra.Fail("That confirmation is wrong, expired, or for different details. Ask again to start over.")
        return null
    }
}

/** An HTTP-level error the server turns into a status code (used by smart reply and uploads). */
class BrainHttpError(val status: Int, message: String) : Exception(message)

/** A picture handed to the AI: [data] is base64 (no prefix), [mime] like image/jpeg. */
class Img(val mime: String, val data: String, val path: String = "")
