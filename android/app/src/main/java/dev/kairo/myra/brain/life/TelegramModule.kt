package dev.kairo.myra.brain.life

import dev.kairo.myra.Fail
import dev.kairo.myra.brain.BrainEnv
import dev.kairo.myra.brain.BrainTool
import dev.kairo.myra.brain.Risk
import dev.kairo.myra.brain.ToolModule
import dev.kairo.myra.brain.prop
import dev.kairo.myra.brain.schema
import dev.kairo.myra.brain.tool
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Telegram bot connector. Myraa only sends messages to YOUR own chat (the chat id you saved),
 * never to anyone else. Tools: check the bot works, and send yourself a message.
 * The bot token is never printed in replies or logs.
 */
class TelegramModule(private val env: BrainEnv) : ToolModule {
    private fun creds(): Pair<String, String> {
        val o = env.conf.obj("telegram")
        val token = o?.optString("token").orEmpty().trim()
        val chat = o?.optString("chat_id").orEmpty().trim()
        if (token.isEmpty() || chat.isEmpty()) throw Fail("Telegram is not connected. Add the bot token and your chat id in Settings, Keys and accounts.")
        return token to chat
    }

    private fun api(method: String, body: JSONObject? = null): JSONObject {
        val token = creds().first
        val conn = (URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection).apply {
            requestMethod = if (body != null) "POST" else "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            if (body != null) {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        if (code !in 200..299 || !json.optBoolean("ok", false)) {
            throw Fail(if (code == 401 || code == 404) "Telegram rejected the bot token." else "Telegram returned an error. Check the chat id and try again.")
        }
        return json
    }

    private fun check(): String {
        val me = api("getMe").optJSONObject("result")
        return "Telegram works. The bot is @${me?.optString("username").orEmpty().ifEmpty { "your bot" }}."
    }

    private fun send(a: JSONObject): String {
        val text = a.optString("text").trim()
        if (text.isEmpty()) throw Fail("What should I send?")
        if (text.length > 3500) throw Fail("That message is too long for Telegram.")
        val chat = creds().second
        api("sendMessage", JSONObject().put("chat_id", chat).put("text", text))
        return "Sent to your Telegram."
    }

    override fun tools(): List<BrainTool> = listOf(
        tool("telegram_check", "Check that the Telegram bot token works.", Risk.LOW, schema(emptyList())) { check() },
        tool("telegram_send", "Send a message to the user's own Telegram chat (only that chat).", Risk.MEDIUM,
            schema(listOf("text"), "text" to prop("string"))) { send(it) },
    )
}
