package dev.kairo.myra.brain.net

import dev.kairo.myra.brain.BrainConfig
import org.json.JSONObject

/**
 * The keys and accounts typed into the app (Settings, Keys and accounts), port of keys.mjs. They are
 * kept in the brain's sealed settings file. GET never returns a secret, only whether it is set and the
 * plain settings. Saving checks everything first: if one value is wrong, nothing is saved.
 * An empty field keeps the saved value; "clear": ["email", ...] removes a section.
 *
 * GitHub: one personal access token (classic, scope "repo"); github_upload uses it to put a zip or project in Anuj's account.
 * Note: the tools that USE mail, picture, Facebook, Instagram and YouTube keys are not in the app yet.
 */
object Keys {
    class Result(val ok: Boolean, val error: String? = null, val view: JSONObject? = null)

    private val HTTPS = Regex("^https://", RegexOption.IGNORE_CASE)
    private val TLS = listOf("implicit", "starttls", "none")
    private val SIZES = listOf("1024x1024", "1536x1024", "1024x1536")

    private fun s(o: JSONObject?, k: String): String = if (o == null || o.isNull(k)) "" else o.opt(k).toString().trim()
    private fun has(o: JSONObject?, k: String) = s(o, k).isNotEmpty()

    fun view(conf: BrainConfig): JSONObject {
        val e = conf.obj("email"); val i = conf.obj("images"); val soc = conf.obj("social")
        val fb = soc?.optJSONObject("facebook"); val ig = soc?.optJSONObject("instagram"); val yt = conf.obj("youtube")
        return JSONObject().put("ok", true)
            .put("tavily", JSONObject().put("set", has(conf.obj("tavily"), "apiKey")))
            .put("netlify", JSONObject().put("set", has(conf.obj("netlify"), "token")))
            .put("github", JSONObject().put("set", has(conf.obj("github"), "token")))
            .put("todoist", JSONObject().put("set", has(conf.obj("todoist"), "token")))
            .put("telegram", JSONObject().put("set", has(conf.obj("telegram"), "token") && has(conf.obj("telegram"), "chat_id")))
            .put("notion", JSONObject().put("set", has(conf.obj("notion"), "token")))
            .put("gitlab", JSONObject().put("set", has(conf.obj("gitlab"), "token")))
            .put("linear", JSONObject().put("set", has(conf.obj("linear"), "token")))
            .put("gdrive", JSONObject().put("set", has(conf.obj("gdrive"), "key")))
            .put("supabase", JSONObject().put("set", has(conf.obj("supabase"), "token") && has(conf.obj("supabase"), "ref")).put("ref", s(conf.obj("supabase"), "ref")))
            .put("email", JSONObject().put("set", has(e, "host") && has(e, "pass")).put("host", s(e, "host")).put("port", e?.opt("port") ?: JSONObject.NULL).put("tls", s(e, "tls")).put("user", s(e, "user")).put("from", s(e, "from")))
            .put("images", JSONObject().put("set", has(i, "apiKey")).put("model", s(i, "model")).put("size", s(i, "size")).put("baseUrl", s(i, "baseUrl")))
            .put("facebook", JSONObject().put("set", has(fb, "pageId") && has(fb, "token")).put("pageId", s(fb, "pageId")))
            .put("instagram", JSONObject().put("set", has(ig, "userId") && has(ig, "token") && has(ig, "publicBase")).put("userId", s(ig, "userId")).put("publicBase", s(ig, "publicBase")))
            .put("youtube", JSONObject().put("set", has(yt, "clientId") && has(yt, "clientSecret") && has(yt, "refreshToken")))
            .put("graphVersion", s(soc, "graphVersion").ifEmpty { "v21.0" })
    }

    private fun problems(b: JSONObject): List<String> {
        val out = ArrayList<String>()
        fun text(name: String, o: JSONObject?, k: String, limit: Int = 300) {
            if (o == null || !o.has(k) || o.isNull(k)) return
            val v = o.opt(k).toString()
            if (Regex("[\\r\\n\\u0000]").containsMatchIn(v)) out.add("$name has a line break in it.") else if (v.length > limit) out.add("$name is too long.")
        }
        fun noSpaces(name: String, o: JSONObject?, k: String) { if (o != null && o.has(k) && Regex("\\s").containsMatchIn(o.opt(k).toString())) out.add("$name must not have spaces in it.") }
        val e = b.optJSONObject("email")
        if (e != null) {
            text("The mail server", e, "host")
            if (s(e, "host").isNotEmpty() && !Regex("^[a-z0-9.-]+$", RegexOption.IGNORE_CASE).matches(s(e, "host"))) out.add("The mail server should look like smtp.gmail.com.")
            if (s(e, "port").isNotEmpty()) { val p = s(e, "port").toIntOrNull(); if (p == null || p !in 1..65535) out.add("The port must be a number from 1 to 65535.") }
            if (s(e, "tls").isNotEmpty() && s(e, "tls") !in TLS) out.add("The connection type must be implicit, starttls or none.")
            text("The login", e, "user"); text("The from address", e, "from"); text("The password", e, "pass", 2000)
        }
        val i = b.optJSONObject("images")
        if (i != null) {
            text("The image key", i, "apiKey", 2000); noSpaces("The image key", i, "apiKey"); text("The image model", i, "model", 60)
            if (s(i, "size").isNotEmpty() && s(i, "size") !in SIZES) out.add("The picture size must be 1024x1024, 1536x1024 or 1024x1536.")
            if (s(i, "baseUrl").isNotEmpty() && !HTTPS.containsMatchIn(s(i, "baseUrl"))) out.add("The image API address must start with https://.")
        }
        val f = b.optJSONObject("facebook")
        if (f != null) {
            if (s(f, "pageId").isNotEmpty() && !Regex("^\\d+$").matches(s(f, "pageId"))) out.add("The Facebook Page id is a number.")
            text("The Facebook token", f, "token", 2000); noSpaces("The Facebook token", f, "token")
        }
        val g = b.optJSONObject("instagram")
        if (g != null) {
            if (s(g, "userId").isNotEmpty() && !Regex("^\\d+$").matches(s(g, "userId"))) out.add("The Instagram account id is a number.")
            text("The Instagram token", g, "token", 2000); noSpaces("The Instagram token", g, "token")
            if (s(g, "publicBase").isNotEmpty() && !HTTPS.containsMatchIn(s(g, "publicBase"))) out.add("The public web folder must start with https://.")
        }
        val y = b.optJSONObject("youtube")
        if (y != null) {
            text("The YouTube client id", y, "clientId"); text("The YouTube client secret", y, "clientSecret", 2000); text("The YouTube refresh token", y, "refreshToken", 2000)
            noSpaces("The YouTube refresh token", y, "refreshToken")
        }
        text("The Tavily key", b.optJSONObject("tavily"), "apiKey", 2000)
        val sup = b.optJSONObject("supabase")
        if (sup != null) {
            text("The Supabase token", sup, "token", 2000); noSpaces("The Supabase token", sup, "token")
            if (s(sup, "ref").isNotEmpty() && !Regex("^[a-z0-9]{10,30}$").matches(s(sup, "ref"))) out.add("The Supabase project id is 20 letters and numbers, like abcdefghijklmnopqrst.")
        }
        text("The Netlify token", b.optJSONObject("netlify"), "token", 2000); noSpaces("The Netlify token", b.optJSONObject("netlify"), "token")
        val gh = b.optJSONObject("github")
        text("The GitHub token", gh, "token", 2000); noSpaces("The GitHub token", gh, "token")
        if (gh != null && s(gh, "token").isNotEmpty() && !Regex("^(ghp_|github_pat_|gho_|ghu_|ghs_)[A-Za-z0-9_]+$").matches(s(gh, "token"))) out.add("The GitHub token should start with ghp_ (classic token) or github_pat_.")
        if (s(b, "graphVersion").isNotEmpty() && !Regex("^v\\d+\\.\\d+$").matches(s(b, "graphVersion"))) out.add("The Meta API version looks like v21.0.")
        return out
    }

    /** A copy of [old] with the non-empty fields of [input] laid over it. */
    private fun pick(old: JSONObject?, input: JSONObject?, keys: List<String>): JSONObject {
        val next = JSONObject((old ?: JSONObject()).toString())
        for (k in keys) {
            if (input == null || !input.has(k) || input.isNull(k) || s(input, k).isEmpty()) continue
            val v = input.get(k)
            next.put(k, if (v is String) v.trim() else v)
        }
        return next
    }

    fun save(conf: BrainConfig, body: JSONObject): Result {
        val errors = problems(body)
        if (errors.isNotEmpty()) return Result(false, errors.joinToString(" "))
        val clear = HashSet<String>().also { set -> body.optJSONArray("clear")?.let { a -> for (n in 0 until a.length()) set.add(a.optString(n)) } }
        val root = JSONObject(conf.root.toString())
        fun setOrClear(key: String, name: String, value: () -> JSONObject) { if (name in clear) root.remove(key) else root.put(key, value()) }
        if (body.has("email") || "email" in clear) {
            if ("email" in clear) root.remove("email") else {
                val e = pick(conf.obj("email"), body.optJSONObject("email"), listOf("host", "port", "tls", "user", "pass", "from"))
                if (e.has("port")) e.put("port", e.optString("port").toIntOrNull() ?: 0)
                if (s(e, "tls").isEmpty()) e.put("tls", if (e.optInt("port") == 465) "implicit" else "starttls")
                root.put("email", e)
            }
        }
        if (body.has("images") || "images" in clear) setOrClear("images", "images") { pick(conf.obj("images"), body.optJSONObject("images"), listOf("apiKey", "model", "size", "baseUrl")) }
        val social = JSONObject((conf.obj("social") ?: JSONObject()).toString())
        if (s(body, "graphVersion").isNotEmpty()) social.put("graphVersion", s(body, "graphVersion"))
        if (body.has("facebook") || "facebook" in clear) { if ("facebook" in clear) social.remove("facebook") else social.put("facebook", pick(social.optJSONObject("facebook"), body.optJSONObject("facebook"), listOf("pageId", "token"))) }
        if (body.has("instagram") || "instagram" in clear) { if ("instagram" in clear) social.remove("instagram") else social.put("instagram", pick(social.optJSONObject("instagram"), body.optJSONObject("instagram"), listOf("userId", "token", "publicBase"))) }
        root.put("social", social)
        if (body.has("youtube") || "youtube" in clear) setOrClear("youtube", "youtube") { pick(conf.obj("youtube"), body.optJSONObject("youtube"), listOf("clientId", "clientSecret", "refreshToken")) }
        if (body.has("tavily") || "tavily" in clear) setOrClear("tavily", "tavily") { pick(conf.obj("tavily"), body.optJSONObject("tavily"), listOf("apiKey")) }
        if (body.has("supabase") || "supabase" in clear) setOrClear("supabase", "supabase") { pick(conf.obj("supabase"), body.optJSONObject("supabase"), listOf("token", "ref")) }
        if (body.has("netlify") || "netlify" in clear) setOrClear("netlify", "netlify") { pick(conf.obj("netlify"), body.optJSONObject("netlify"), listOf("token")) }
        if (body.has("github") || "github" in clear) setOrClear("github", "github") { pick(conf.obj("github"), body.optJSONObject("github"), listOf("token")) }
        if (body.has("todoist") || "todoist" in clear) setOrClear("todoist", "todoist") { pick(conf.obj("todoist"), body.optJSONObject("todoist"), listOf("token")) }
        if (body.has("telegram") || "telegram" in clear) setOrClear("telegram", "telegram") { pick(conf.obj("telegram"), body.optJSONObject("telegram"), listOf("token", "chat_id")) }
        if (body.has("notion") || "notion" in clear) setOrClear("notion", "notion") { pick(conf.obj("notion"), body.optJSONObject("notion"), listOf("token", "parent_page_id")) }
        if (body.has("gitlab") || "gitlab" in clear) setOrClear("gitlab", "gitlab") { pick(conf.obj("gitlab"), body.optJSONObject("gitlab"), listOf("token", "base_url")) }
        if (body.has("linear") || "linear" in clear) setOrClear("linear", "linear") { pick(conf.obj("linear"), body.optJSONObject("linear"), listOf("token")) }
        conf.root = root
        return Result(true, null, view(conf))
    }
}
