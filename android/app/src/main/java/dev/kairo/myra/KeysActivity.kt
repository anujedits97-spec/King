package dev.kairo.myra

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keys and accounts, typed in the app. They go to the Myraa brain inside this app (its sealed settings file),
 * and this screen keeps nothing: it only shows what is set. A box left empty keeps the saved value.
 * Secrets are never shown back, not even partly.
 */
class KeysActivity : Activity() {
    private class Field(val key: String, val label: String, val hint: String = "", val secret: Boolean = false, val number: Boolean = false)
    private class Section(val id: String, val title: String, val fields: List<Field>)

    private val sections = listOf(
        Section("email", "Email (sending)", listOf(
            Field("host", "Mail server", "smtp.gmail.com"),
            Field("port", "Port", "465", number = true),
            Field("tls", "Connection: implicit, starttls or none", "implicit"),
            Field("user", "Login (your address)", "you@gmail.com"),
            Field("pass", "App password", secret = true),
            Field("from", "From address", "you@gmail.com"),
        )),
        Section("images", "Picture making (image API)", listOf(
            Field("apiKey", "API key", secret = true),
            Field("model", "Model", "gpt-image-1"),
            Field("size", "Size", "1024x1024"),
            Field("baseUrl", "API address (empty for OpenAI)"),
        )),
        Section("facebook", "Facebook Page", listOf(
            Field("pageId", "Page id (numbers)"),
            Field("token", "Page access token", secret = true),
        )),
        Section("instagram", "Instagram Business", listOf(
            Field("userId", "Account id (numbers)"),
            Field("token", "Access token", secret = true),
            Field("publicBase", "Public web folder (https://...)"),
        )),
        Section("youtube", "YouTube", listOf(
            Field("clientId", "Client id"),
            Field("clientSecret", "Client secret", secret = true),
            Field("refreshToken", "Refresh token", secret = true),
        )),
        Section("tavily", "Web search (Tavily)", listOf(
            Field("apiKey", "Tavily API key (tvly-...)", secret = true),
        )),
        Section("netlify", "Website publishing (Netlify)", listOf(
            Field("token", "Netlify personal access token", secret = true),
        )),
        Section("github", "Code on GitHub", listOf(
            Field("token", "GitHub personal access token (ghp_..., classic, scope repo; tick delete_repo too only if Myraa may delete repositories)", secret = true),
        )),
        Section("todoist", "Todoist (your task list)", listOf(
            Field("token", "Todoist API token (Todoist Settings, Integrations, Developer)", secret = true),
        )),
        Section("telegram", "Telegram (message yourself)", listOf(
            Field("token", "Bot token from @BotFather (123456:ABC...)", secret = true),
            Field("chat_id", "Your chat id (get it from @userinfobot on Telegram)", "123456789"),
        )),
        Section("notion", "Notion (pages and notes)", listOf(
            Field("token", "Notion integration token (secret_... or ntn_...), with the pages shared to it", secret = true),
            Field("parent_page_id", "Optional: page id where new Myraa notes go (from the page link)", ""),
        )),
        Section("gitlab", "GitLab (projects and issues)", listOf(
            Field("token", "GitLab personal access token (scopes: api, read_api)", secret = true),
            Field("base_url", "Only for your own GitLab server (https://...). Leave empty for gitlab.com", ""),
        )),
        Section("linear", "Linear (issues and cycles)", listOf(
            Field("token", "Linear personal API key (lin_api_...), from Linear Settings, API", secret = true),
        )),
        Section("supabase", "Backend (Supabase: login and database)", listOf(
            Field("token", "Supabase access token (sbp_...)", secret = true),
            Field("ref", "Project id (Project Settings, General)", "abcdefghijklmnopqrst"),
        )),
        Section("meta", "Meta API version", listOf(
            Field("graphVersion", "Version, e.g. v21.0", "v21.0"),
        )),
    )

    private val fields = HashMap<String, EditText>()
    private val statusViews = HashMap<String, TextView>()
    private lateinit var root: LinearLayout
    private lateinit var message: TextView
    private val brain by lazy { BrainClient(Prefs(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        root.addView(text("Keys and accounts", 22f, Color.BLACK, bold = true))
        root.addView(text("Type each key here. It is sent to the Myraa brain on this phone (inside this app), which keeps it sealed. This app keeps nothing. A box you leave empty keeps what is already saved. Keys are never shown back.", 13f, Color.DKGRAY))
        message = text("", 13f, Color.DKGRAY)
        root.addView(message)
        for (s in sections) addSection(s)
        root.addView(Button(this).apply { text = "Save"; setOnClickListener { save() } })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun addSection(s: Section) {
        root.addView(text(s.title, 16f, Color.BLACK, bold = true).apply { setPadding(0, dp(18), 0, dp(4)) })
        val status = text("checking...", 13f, Color.DKGRAY)
        statusViews[s.id] = status
        root.addView(status)
        for (f in s.fields) {
            root.addView(text(f.label, 12f, Color.DKGRAY))
            val e = EditText(this).apply {
                hint = f.hint
                isSingleLine = true
                inputType = when {
                    f.secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    f.number -> InputType.TYPE_CLASS_NUMBER
                    else -> InputType.TYPE_CLASS_TEXT
                }
            }
            fields["${s.id}.${f.key}"] = e
            root.addView(e)
        }
        if (s.id != "meta") root.addView(Button(this).apply { text = "Remove ${s.title} keys"; setOnClickListener { clear(s.id, s.title) } })
    }

    /** Reads what is set from the brain. Shows only plain settings, never a secret. */
    private fun load() {
        brain.keysGet { j, err ->
            if (j == null) {
                message.text = err ?: "The brain did not answer."
                message.setTextColor(Color.RED)
                return@keysGet
            }
            message.text = ""
            statusViews["email"]?.text = state(j.optJSONObject("email"), "set") { "Saved: sends from ${it.optString("from").ifEmpty { it.optString("user") }} via ${it.optString("host")}" }
            statusViews["images"]?.text = state(j.optJSONObject("images"), "set") { "Saved (model ${it.optString("model").ifEmpty { "default" }})" }
            statusViews["facebook"]?.text = state(j.optJSONObject("facebook"), "set") { "Saved (Page ${it.optString("pageId")})" }
            statusViews["instagram"]?.text = state(j.optJSONObject("instagram"), "set") { "Saved (account ${it.optString("userId")})" }
            statusViews["youtube"]?.text = state(j.optJSONObject("youtube"), "set") { "Saved" }
            statusViews["netlify"]?.text = state(j.optJSONObject("netlify"), "set") { "Saved: Myraa can publish static sites" }
            statusViews["github"]?.text = state(j.optJSONObject("github"), "set") { "Saved: Myraa can put projects and zips on your GitHub" }
            statusViews["todoist"]?.text = state(j.optJSONObject("todoist"), "set") { "Saved: Myraa can read and add your Todoist tasks" }
            statusViews["telegram"]?.text = state(j.optJSONObject("telegram"), "set") { "Saved: Myraa can send you Telegram messages" }
            statusViews["notion"]?.text = state(j.optJSONObject("notion"), "set") { "Saved: Myraa can read and add Notion pages" }
            statusViews["gitlab"]?.text = state(j.optJSONObject("gitlab"), "set") { "Saved: Myraa can read GitLab projects and issues" }
            statusViews["linear"]?.text = state(j.optJSONObject("linear"), "set") { "Saved: Myraa can read and create Linear issues" }
            statusViews["gdrive"]?.text = state(j.optJSONObject("gdrive"), "set") { "Saved: Myraa can read files shared with the Drive service account" }
            statusViews["supabase"]?.text = state(j.optJSONObject("supabase"), "set") { "Saved: project ${it.optString("ref")}" }
            statusViews["meta"]?.text = "Version ${j.optString("graphVersion", "v21.0")}"
        }
    }

    private fun state(o: JSONObject?, key: String, saved: (JSONObject) -> String): String =
        if (o != null && o.optBoolean(key)) saved(o) else "Not set yet"

    private fun save() {
        val body = JSONObject()
        for (s in sections) {
            if (s.id == "meta") continue
            val o = JSONObject()
            for (f in s.fields) {
                val v = fields["${s.id}.${f.key}"]?.text?.toString()?.trim().orEmpty()
                if (v.isEmpty()) continue
                o.put(f.key, if (f.number) (v.toIntOrNull() ?: v) else v)
            }
            if (o.length() > 0) body.put(s.id, o)
        }
        val version = fields["meta.graphVersion"]?.text?.toString()?.trim().orEmpty()
        if (version.isNotEmpty()) body.put("graphVersion", version)
        if (body.length() == 0) {
            show("Nothing new to save.", Color.DKGRAY)
            return
        }
        send(body, "Saved.")
    }

    private fun clear(id: String, title: String) {
        send(JSONObject().put("clear", JSONArray().put(id)), "$title keys removed.")
    }

    private fun send(body: JSONObject, ok: String) {
        brain.keysSave(body) { j, err ->
            if (err != null || j == null) {
                show(err ?: "The brain did not answer.", Color.RED)
                return@keysSave
            }
            // Secret boxes are emptied after saving, so nothing stays on screen.
            for ((k, e) in fields) if (k.endsWith(".pass") || k.endsWith(".apiKey") || k.endsWith(".token") || k.endsWith(".clientSecret") || k.endsWith(".refreshToken")) e.setText("")
            show(ok, Color.DKGRAY)
            load()
        }
    }

    private fun show(msg: String, color: Int) {
        message.text = msg
        message.setTextColor(color)
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
