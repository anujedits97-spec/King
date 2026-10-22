package dev.kairo.myra.brain.life

import dev.kairo.myra.Fail
import dev.kairo.myra.brain.BrainEnv
import dev.kairo.myra.brain.BrainTool
import dev.kairo.myra.brain.ConfirmGate
import dev.kairo.myra.brain.Risk
import dev.kairo.myra.brain.ToolModule
import dev.kairo.myra.brain.TriggerEngine
import dev.kairo.myra.brain.prop
import dev.kairo.myra.brain.schema
import dev.kairo.myra.brain.tool
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tools to make, list and delete event triggers in plain words:
 *  - "jab WhatsApp par 'urgent' aaye to Telegram par bhejo"       (type notification)
 *  - "jab battery 20% se neeche aaye to batao"                     (type battery)
 *  - "battery 100% ho jaye to bolo charger nikal do"               (type charging, event full)
 *  - "charger laga ya nikla to bolo"                              (type charging, event plugged / unplugged)
 *  - "roz 9:30 par mujhe yaad dilao"                              (type time)
 * Actions reach only you (Myraa says it aloud, or a notification, your Telegram, your Todoist).
 */
class TriggersModule(private val env: BrainEnv) : ToolModule {
    private val gate = ConfirmGate()
    private val actions = setOf("speak", "notify", "telegram", "todoist")
    private val kinds = setOf("notification", "battery", "charging", "time")
    private val chargeEvents = setOf("plugged", "unplugged", "full")
    private val timeRe = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    private fun add(a: JSONObject): String {
        val name = a.optString("name").trim().take(60)
        if (name.isEmpty()) throw Fail("Give the trigger a short name.")
        val type = a.optString("type", "notification").trim().lowercase()
        if (type !in kinds) throw Fail("The type should be notification, battery, charging or time.")
        val action = a.optString("action", "notify").trim().lowercase()
        if (action !in actions) throw Fail("The action should be speak, notify, telegram or todoist.")

        val rule = JSONObject().put("name", name).put("type", type).put("action", action).put("enabled", true)
        val described: String
        when (type) {
            "notification" -> {
                val app = a.optString("app").trim().lowercase()
                if (app.isEmpty()) throw Fail("Which app should trigger it? For example WhatsApp or Gmail.")
                val keyword = a.optString("keyword").trim().lowercase()
                rule.put("app", app).put("keyword", keyword)
                rule.put("text", a.optString("text").trim().ifEmpty { "{app}: {text}" }.take(300))
                described = "when $app " + (if (keyword.isEmpty()) "sends anything" else "sends a message with \"$keyword\"")
            }
            "battery" -> {
                val threshold = a.optInt("threshold", 20)
                if (threshold !in 5..50) throw Fail("Pick a battery level between 5 and 50 percent.")
                rule.put("threshold", threshold)
                rule.put("text", a.optString("text").trim().ifEmpty { "Battery is at {level}%." }.take(300))
                described = "when the battery falls to $threshold% or lower"
            }
            "charging" -> {
                val event = a.optString("event").trim().lowercase()
                if (event !in chargeEvents) throw Fail("The event should be plugged, unplugged or full.")
                rule.put("event", event)
                if (event == "full") {
                    val threshold = a.optInt("threshold", 100)
                    if (threshold !in 50..100) throw Fail("Pick a charge level between 50 and 100 percent.")
                    rule.put("threshold", threshold)
                    rule.put("text", a.optString("text").trim().ifEmpty { if (threshold >= 100) "Battery full ho gayi, charger nikal do." else "Battery {level}% ho gayi, charger nikal do." }.take(300))
                    described = "when the battery reaches $threshold% while charging"
                } else {
                    rule.put("text", a.optString("text").trim().ifEmpty { if (event == "plugged") "Charger laga diya. Battery {level}% hai." else "Charger nikal diya. Battery {level}% hai." }.take(300))
                    described = if (event == "plugged") "when the charger is plugged in" else "when the charger is pulled out"
                }
            }
            else -> {
                val at = a.optString("at").trim()
                if (!timeRe.matches(at)) throw Fail("The time should look like 09:30 (24-hour clock).")
                rule.put("at", at)
                rule.put("text", a.optString("text").trim().ifEmpty { "Reminder: it is {time}." }.take(300))
                described = "every day at $at"
            }
        }
        synchronized(TriggerEngine.lock) {
            val arr = TriggerEngine.load(env)
            if (arr.length() >= TriggerEngine.MAX_RULES) throw Fail("You already have ${TriggerEngine.MAX_RULES} triggers. Delete one first.")
            if ((0 until arr.length()).any { arr.optJSONObject(it)?.optString("name").equals(name, ignoreCase = true) }) {
                throw Fail("A trigger called $name already exists.")
            }
            arr.put(rule)
            TriggerEngine.save(env, arr)
        }
        return "Trigger \"$name\" saved: $described, I will $action."
    }

    private fun describe(r: JSONObject): String = when (r.optString("type", "notification")) {
        "battery" -> "battery at or below ${r.optInt("threshold", 20)}%"
        "charging" -> when (r.optString("event")) {
            "plugged" -> "charger plugged in"
            "unplugged" -> "charger pulled out"
            else -> "battery reaches ${r.optInt("threshold", 100)}% while charging"
        }
        "time" -> "every day at ${r.optString("at")}"
        else -> {
            val kw = r.optString("keyword")
            "${r.optString("app")}${if (kw.isEmpty()) "" else " with \"$kw\""}"
        }
    }

    private fun list(): String {
        val arr: JSONArray = synchronized(TriggerEngine.lock) { TriggerEngine.load(env) }
        if (arr.length() == 0) return "You have no triggers yet."
        return (0 until arr.length()).joinToString("\n") { i ->
            val r = arr.getJSONObject(i)
            "${r.optString("name")}: ${describe(r)} -> ${r.optString("action")}"
        }
    }

    private fun delete(a: JSONObject): String {
        val name = a.optString("name").trim()
        if (name.isEmpty()) throw Fail("Which trigger should I delete?")
        gate.gate("trigger_delete", name, a.optString("confirm_token"), "delete the trigger \"$name\"?")?.let { return it }
        synchronized(TriggerEngine.lock) {
            val arr = TriggerEngine.load(env)
            val kept = JSONArray()
            var found = false
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                if (!found && r.optString("name").equals(name, ignoreCase = true)) found = true else kept.put(r)
            }
            if (!found) throw Fail("I don't have a trigger called $name.")
            TriggerEngine.save(env, kept)
        }
        return "Deleted the trigger \"$name\"."
    }

    override fun tools(): List<BrainTool> = listOf(
        tool("trigger_add", "Make an event trigger. type: notification (an app gets a notification, optional keyword), battery (level falls to threshold percent), charging (event: plugged = charger connected, unplugged = charger removed, full = battery reaches threshold percent, default 100, while charging) or time (every day at 'at', HH:mm). action: speak (Myraa says it out loud), notify (phone), telegram (your own chat) or todoist (your task list). For 'battery full, take the charger out' use type charging, event full, action speak. text is optional.", Risk.LOW,
            schema(listOf("name", "type", "action"), "name" to prop("string"),
                "type" to prop("string", "", listOf("notification", "battery", "charging", "time")),
                "event" to prop("string", "For charging type", listOf("plugged", "unplugged", "full")),
                "app" to prop("string", "Needed for notification type, e.g. WhatsApp"),
                "keyword" to prop("string"), "threshold" to prop("integer", "Battery percent: for battery type (5-50), or for charging/full (50-100, default 100)"),
                "at" to prop("string", "HH:mm for time type"),
                "action" to prop("string", "", listOf("speak", "notify", "telegram", "todoist")),
                "text" to prop("string", "Optional message. {app}, {title}, {text}, {level}, {time} are filled in."))) { add(it) },
        tool("trigger_list", "List the user's event triggers.", Risk.LOW, schema(emptyList())) { list() },
        tool("trigger_delete", "Delete an event trigger by name. Asks the user out loud first.", Risk.HIGH,
            schema(listOf("name"), "name" to prop("string"), "confirm_token" to prop("string"))) { delete(it) },
    )
}
