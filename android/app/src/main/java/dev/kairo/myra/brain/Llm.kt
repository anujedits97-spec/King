package dev.kairo.myra.brain

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class NoEngineError(message: String) : Exception(message)

/** Which AI the brain thinks with: any OpenAI-compatible API, or Claude's native Messages API. (Port of fallback.mjs / oneshot.mjs.) */
object Llm {
    private class Provider(val url: String, val model: String)

    private val PROVIDERS = mapOf(
        "groq" to Provider("https://api.groq.com/openai/v1/chat/completions", "openai/gpt-oss-120b"),
        "openai" to Provider("https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
        "gemini" to Provider("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-2.5-flash"),
        "anthropic" to Provider("https://api.anthropic.com/v1/messages", "claude-haiku-5-5"),
        "ollama" to Provider("http://127.0.0.1:11434/v1/chat/completions", "llama3.2"),
        "huggingface" to Provider("https://router.huggingface.co/v1/chat/completions", "openai/gpt-oss-120b"),
        "openrouter" to Provider("https://openrouter.ai/api/v1/chat/completions", AUTO_FREE),
        "deepseek" to Provider("https://api.deepseek.com/v1/chat/completions", "deepseek-chat"),
        "together" to Provider("https://api.together.xyz/v1/chat/completions", "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
        "mistral" to Provider("https://api.mistral.ai/v1/chat/completions", "mistral-small-latest"),
    )

    /** Model name that means "walk down the free OpenRouter models until one answers" (idea from Mark XXXIX-OR). */
    const val AUTO_FREE = "auto-free"

    // Free OpenRouter models, best first. The free list changes often; a name that no longer exists just fails and the next one is tried.
    private val OR_TEXT = listOf(
        "openai/gpt-oss-120b:free", "meta-llama/llama-3.3-70b-instruct:free", "qwen/qwen3-next-80b-a3b-instruct:free", "nvidia/nemotron-3-super-120b-a12b:free",
        "z-ai/glm-4.5-air:free", "google/gemma-3-27b-it:free", "nousresearch/hermes-3-llama-3.1-405b:free", "qwen/qwen3-coder:free",
        "google/gemma-3-12b-it:free", "meta-llama/llama-3.2-3b-instruct:free",
    )
    private val OR_VISION = listOf(
        "google/gemma-3-27b-it:free", "nvidia/nemotron-nano-12b-v2-vl:free", "google/gemma-3-12b-it:free", "meta-llama/llama-3.3-70b-instruct:free",
    )
    private val cooldown = HashMap<String, Long>()

    private fun entry(id: String, name: String, models: List<String>, needsKey: Boolean, needsBaseUrl: Boolean) = JSONObject()
        .put("id", id).put("name", name).put("defaultModel", PROVIDERS[id]?.model ?: "").put("models", JSONArray(models))
        .put("needsKey", needsKey).put("needsBaseUrl", needsBaseUrl)

    /** What the app's AI engine screen lists. */
    val CATALOG: JSONArray = JSONArray()
        .put(entry("gemini", "Google Gemini (free key)", listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-3.1-pro-preview"), true, false))
        .put(entry("openai", "OpenAI", listOf("gpt-4o-mini", "gpt-4o", "gpt-4-turbo"), true, false))
        .put(entry("anthropic", "Anthropic Claude", listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"), true, false))
        .put(entry("groq", "Groq Cloud (ultra fast, free key)", listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.3-70b-versatile"), true, false))
        .put(entry("huggingface", "Hugging Face (open models)", listOf("openai/gpt-oss-120b", "Qwen/Qwen3-8B"), true, false))
        .put(entry("openrouter", "OpenRouter (free models, auto fallback)", listOf(AUTO_FREE, "openai/gpt-oss-120b:free", "meta-llama/llama-3.3-70b-instruct:free", "qwen/qwen3-coder:free"), true, false))
        .put(entry("deepseek", "DeepSeek", listOf("deepseek-chat", "deepseek-reasoner"), true, false))
        .put(entry("together", "Together AI (open models)", listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo", "Qwen/Qwen2.5-72B-Instruct-Turbo"), true, false))
        .put(entry("mistral", "Mistral AI", listOf("mistral-small-latest", "mistral-large-latest"), true, false))
        .put(entry("ollama", "Ollama / local AI", listOf("llama3.2", "mistral", "qwen2.5", "deepseek-coder"), false, false))
        .put(entry("custom", "Custom (any OpenAI-compatible URL)", emptyList(), false, true))

    /** Providers whose models here read text only. */
    val CANT_SEE = setOf("groq")

    class Endpoint(val url: String, val model: String)

    fun resolve(c: JSONObject?): Endpoint {
        if (c == null || c.optString("provider").isEmpty()) throw NoEngineError("No AI provider chosen.")
        val provider = c.optString("provider")
        fun clean(u: String) = u.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/') + "/chat/completions"
        if (provider == "custom") {
            val base = c.optString("baseUrl")
            if (base.isEmpty()) throw NoEngineError("The custom AI needs a base URL.")
            return Endpoint(clean(base), c.optString("model"))
        }
        val p = PROVIDERS[provider] ?: throw NoEngineError("Unknown AI provider \"$provider\".")
        if (provider == "ollama" && c.optString("baseUrl").isNotEmpty()) return Endpoint(clean(c.optString("baseUrl")), c.optString("model").ifEmpty { p.model })
        return Endpoint(p.url, c.optString("model").ifEmpty { p.model })
    }

    /** The saved engine, or null when none is usable yet. */
    fun engine(conf: BrainConfig): JSONObject? {
        val c = conf.obj("fallback") ?: return null
        val p = c.optString("provider")
        if (p.isEmpty()) return null
        if (c.optString("apiKey").isEmpty() && p != "ollama" && p != "custom") return null
        return c
    }

    fun enabled(conf: BrainConfig) = engine(conf) != null

    const val NO_ENGINE = "No AI engine is set up yet. Open the Myraa app, go to Settings, AI engine, pick one (Gemini and Groq have free keys) and paste its key."

    /**
     * POST JSON and read the JSON answer. Throws with the provider's own message on an error status.
     * With OpenRouter and the model "auto-free", it tries the free models one by one (a failed or rate-limited model rests for 30 s).
     */
    fun postJson(url: String, headers: Map<String, String>, body: JSONObject, timeoutMs: Int, who: String): JSONObject {
        if (who != "openrouter" || body.optString("model") != AUTO_FREE) return postJsonOnce(url, headers, body, timeoutMs, who)
        val pool = if (body.toString().contains("\"image_url\"")) OR_VISION else OR_TEXT
        var last: Exception? = null
        for (m in pool) {
            val rest = synchronized(cooldown) { cooldown[m] } ?: 0L
            if (System.currentTimeMillis() - rest < 30_000) continue
            try {
                val j = postJsonOnce(url, headers, JSONObject(body.toString()).put("model", m), minOf(timeoutMs, 45_000), who)
                val msg = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val usable = msg != null && (msg.optString("content").isNotBlank() || (msg.optJSONArray("tool_calls")?.length() ?: 0) > 0)
                if (usable) return j
                last = Exception("$m sent an empty answer")
            } catch (e: Exception) { last = e }
            synchronized(cooldown) { cooldown[m] = System.currentTimeMillis() }
        }
        throw Exception("OpenRouter: every free model failed or is busy right now. ${last?.message.orEmpty()}".trim())
    }

    private fun postJsonOnce(url: String, headers: Map<String, String>, body: JSONObject, timeoutMs: Int, who: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val j = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code !in 200..299) {
                val msg = j.optJSONObject("error")?.optString("message").orEmpty().ifEmpty { j.optString("message") }.ifEmpty { "error $code" }
                throw Exception("$who said: $msg")
            }
            return j
        } finally { c.disconnect() }
    }

    fun headers(provider: String, key: String): Map<String, String> =
        if (provider == "anthropic") mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        else if (provider == "openrouter") mapOf("authorization" to "Bearer $key", "HTTP-Referer" to "https://github.com/myra-phone", "X-Title" to "Myraa")
        else if (key.isNotEmpty()) mapOf("authorization" to "Bearer $key") else emptyMap()

    /** One question to the AI engine. No tools, no history, no memory. */
    fun oneShot(conf: BrainConfig, system: String, prompt: String, maxTokens: Int, timeoutMs: Int, temperature: Double): String {
        val c = engine(conf) ?: throw NoEngineError(NO_ENGINE)
        val ep = resolve(c)
        val provider = c.optString("provider")
        val anthropic = provider == "anthropic"
        val body = if (anthropic)
            JSONObject().put("model", ep.model).put("max_tokens", maxTokens).put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        else
            JSONObject().put("model", ep.model).put("max_tokens", maxTokens).put("temperature", temperature)
                .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", prompt)))
        val j = postJson(ep.url, headers(provider, c.optString("apiKey")), body, timeoutMs, provider)
        val text = if (anthropic) {
            val arr = j.optJSONArray("content") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
        } else j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        if (text.isBlank()) throw Exception("$provider sent an empty answer.")
        return text.trim()
    }

    /** Like [oneShot] but with pictures (JPEG, base64 without prefix). Throws when the engine can't take pictures. */
    fun oneShotVision(conf: BrainConfig, system: String, prompt: String, jpegs: List<String>, maxTokens: Int, timeoutMs: Int): String {
        val c = engine(conf) ?: throw NoEngineError(NO_ENGINE)
        val ep = resolve(c)
        val provider = c.optString("provider")
        val anthropic = provider == "anthropic"
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        for (p in jpegs) parts.put(
            if (anthropic) JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", p))
            else JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$p"))
        )
        val body = if (anthropic)
            JSONObject().put("model", ep.model).put("max_tokens", maxTokens).put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", parts)))
        else
            JSONObject().put("model", ep.model).put("max_tokens", maxTokens).put("temperature", 0.2)
                .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", parts)))
        val j = postJson(ep.url, headers(provider, c.optString("apiKey")), body, timeoutMs, provider)
        val text = if (anthropic) {
            val arr = j.optJSONArray("content") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
        } else j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        if (text.isBlank()) throw Exception("$provider sent an empty answer.")
        return text.trim()
    }

    /** One tiny question to check that a provider, key and model work. */
    fun ping(c: JSONObject): JSONObject {
        val ep = resolve(c)
        val provider = c.optString("provider")
        if (ep.model.isEmpty()) throw NoEngineError("Type a model name for the custom provider.")
        if (provider != "ollama" && provider != "custom" && c.optString("apiKey").isEmpty()) throw NoEngineError("Paste the API key first.")
        val anthropic = provider == "anthropic"
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with the single word OK."))
        val body = JSONObject().put("model", ep.model).put("max_tokens", 20).put("messages", msgs)
        val j = postJson(ep.url, headers(provider, c.optString("apiKey")), body, 25_000, provider)
        val text = if (anthropic) {
            val arr = j.optJSONArray("content") ?: JSONArray()
            (0 until arr.length()).joinToString("") { arr.getJSONObject(it).optString("text") }
        } else j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        if (text.isBlank()) throw Exception("The model sent an empty answer.")
        return JSONObject().put("model", ep.model).put("reply", text.trim().take(60))
    }
}
