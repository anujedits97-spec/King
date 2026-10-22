package dev.kairo.myra.brain

import dev.kairo.myra.Fail
import org.json.JSONObject
import java.io.File

/**
 * The safety layer for Myraa's tools (port of guard.mjs, now covering the app's own tools too).
 *
 *  - Every tool has a risk level: LOW (reads, opens), MEDIUM (changes your data on the phone),
 *    HIGH (reaches other people, deletes, or can't be undone). HIGH tools need a spoken yes (ConfirmGate).
 *  - Emergency stop: every tool is refused until resume. The flag is a file, so it survives a restart.
 *  - Unattended mode: while a scheduled goal runs by itself, HIGH tools are refused outright.
 */
class Guard(dir: File) {
    private val flag = File(dir, "STOPPED")
    @Volatile var unattended = false

    fun stopped() = flag.exists()

    fun stop(reason: String = "", onStop: () -> Unit = {}) {
        runCatching { flag.writeText("${java.util.Date()} $reason\n") }
        onStop()
    }

    fun resume() { runCatching { flag.delete() } }

    /** Throws if the tool must not run right now. */
    fun check(name: String) {
        if (stopped() && name !in ALWAYS) throw Fail("Emergency stop is on, so I am not doing anything. Anuj has to say \"resume\" first.")
        if (unattended && riskFor(name) == Risk.HIGH) throw Fail("\"$name\" reaches other people or deletes things, so it is not allowed in a scheduled goal that runs by itself. Say it to Anuj instead.")
    }

    /** Modules register their own tools' risk here too (their [BrainTool.risk] is used when not in [RISK]). */
    fun learn(tools: List<BrainTool>) { for (t in tools) if (t.name !in RISK) learned[t.name] = t.risk }
    private val learned = HashMap<String, Risk>()
    fun riskFor(name: String): Risk = RISK[name] ?: learned[name] ?: Risk.MEDIUM

    fun module(env: BrainEnv) = object : ToolModule {
        override fun tools() = listOf(
            tool("emergency_stop", "EMERGENCY STOP. Use immediately when Anuj says stop everything, ruk jao, band karo sab, emergency, or panic. Every other tool is refused until resume is called. Do it first, then say so in a few words.",
                Risk.LOW, schema(emptyList(), "reason" to prop("string"))) { a ->
                stop(a.optString("reason").take(80)) { env.onStop() }
                "Emergency stop is ON. All tools are off until Anuj says resume."
            },
            tool("resume", "Switch tools back on after an emergency stop. Only when Anuj clearly says resume, chalu karo or continue.",
                Risk.LOW, schema()) { resume(); "Resumed. Tools are back on." },
            tool("risk_info", "Tell Anuj which tools are LOW, MEDIUM or HIGH risk and whether an emergency stop is on.",
                Risk.LOW, schema()) {
                fun by(l: Risk) = (RISK.filter { it.value == l }.keys + learned.filter { it.value == l }.keys).joinToString(", ")
                "Emergency stop: ${if (stopped()) "ON" else "off"}.\nHIGH (always asks first): ${by(Risk.HIGH)}\nMEDIUM: ${by(Risk.MEDIUM)}\nLOW: ${by(Risk.LOW)}"
            },
        )
    }

    companion object {
        private val ALWAYS = setOf("resume", "emergency_stop", "activity_log", "risk_info")

        private fun m(level: Risk, vararg names: String) = names.associateWith { level }

        /** Fixed in code, never decided by the model. Tools not listed count as MEDIUM unless the module says otherwise. */
        val RISK: Map<String, Risk> = HashMap<String, Risk>().apply {
            putAll(m(Risk.LOW, "recall", "remind_list", "contacts_find", "call_log", "sms_read", "notifications_read", "open_dialer", "whatsapp_open", "telegram_open",
                "location_get", "navigate", "device_info", "alias_list", "automation_list", "activity_log", "file_list", "file_read", "storage_info", "style_get", "risk_info",
                "verify_screen", "market_price", "watchlist_show", "watchlist_add", "watchlist_remove", "settings_open", "note_list", "zip_open", "zip_list", "zip_read", "zip_search",
                "emergency_stop", "resume", "open_app", "list_apps", "open_link", "youtube_search", "screen_read", "screen_look", "screen_ocr", "media_key", "phone_volume", "calendar_read",
                "now_playing", "check_battery", "clipboard_read", "launch_camera", "web_search", "deep_research", "dial", "compose_sms", "site_test", "github_status", "github_repos", "github_search", "github_repo_info", "github_read", "github_issues", "github_history"))
            putAll(m(Risk.MEDIUM, "remember", "forget", "remind_set", "remind_cancel", "alarm_set", "timer_set", "calendar_event_add", "alias_set", "alias_remove", "automation_switch",
                "automation_remove", "torch", "note_add", "note_remove", "file_write", "style_set", "zip_write", "zip_remove", "zip_export", "zip_upload", "attach_upload", "automation_add",
                "screen_tap", "screen_type", "screen_swipe", "screen_key", "close_app", "clipboard_write", "post_notification", "flashlight", "brightness", "do_not_disturb",
                "settings_panel", "schedule_task", "camera_look", "live_screen", "theft_guard", "github_download", "github_star"))
            putAll(m(Risk.HIGH, "sms_send", "phone_call", "telegram_bot_send", "file_delete", "zip_discard", "send_message", "call_control", "auto_reply", "routine", "site_deploy", "github_upload", "github_fork", "github_create_repo", "github_edit_file", "github_branch", "github_issue_write", "github_pr_write", "github_release", "github_repo_settings", "github_delete_repo", "backend_apply", "code_run"))
        }
    }
}
