package dev.kairo.myra.brain.mark

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Looper
import dev.kairo.myra.Fail
import dev.kairo.myra.Termux
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the project runner script (assets/run_project.sh) inside Termux and brings its output back, so a project in
 * any language Termux has (Python, Node, Go, Rust, Java, C, C++, shell) can really be run and tested.
 * Termux's RUN_COMMAND answers through a PendingIntent. This needs, once: Termux installed, Myraa allowed to run
 * commands in it, `allow-external-apps=true` in ~/.termux/termux.properties, and storage access (termux-setup-storage).
 */
object TermuxRun {
    class Result(val ok: Boolean, val stdout: String, val stderr: String, val exitCode: Int, val error: String)

    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val HOME = "/data/data/com.termux/files/home"

    /** Blocks until Termux answers or [timeoutMs] passes. Not on the main thread. */
    fun run(ctx: Context, script: String, args: List<String>, timeoutMs: Long): Result {
        if (Looper.myLooper() == Looper.getMainLooper()) throw Fail("I can't wait for Termux on the app's main thread.")
        if (!Termux.installed(ctx)) throw Fail("Termux is not installed. Install Termux (from F-Droid or its GitHub page), then try again.")
        if (!Termux.permitted(ctx)) throw Fail("Myraa is not allowed to run commands in Termux yet. In Android settings, Apps, Myraa, Permissions, Additional permissions, allow Run commands in Termux environment. Also, in Termux, run: mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties and then close and reopen Termux.")
        val action = "dev.kairo.myra.TERMUX_RESULT." + System.nanoTime()
        val latch = CountDownLatch(1)
        var result: Result? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val b = i.getBundleExtra("result")
                result = if (b == null) Result(false, "", "", -1, "Termux sent no result.") else {
                    val msg = b.getString("errmsg").orEmpty()
                    Result(b.containsKey("exitCode") && msg.isEmpty(), b.getString("stdout").orEmpty(), b.getString("stderr").orEmpty(), b.getInt("exitCode", -1), msg)
                }
                latch.countDown()
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(receiver, filter)
        try {
            val pi = PendingIntent.getBroadcast(
                ctx, (System.nanoTime() and 0xffff).toInt(), Intent(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            val i = Intent()
                .setClassName(Termux.PACKAGE, "com.termux.app.RunCommandService")
                .setAction("com.termux.RUN_COMMAND")
                .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script, "runner") + args)
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            val started = runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
            if (started.isFailure) throw Fail("Termux refused the command. In Termux run: mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties and then close and reopen Termux.")
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS))
                return Result(false, "", "", -1, "Termux did not answer in time. Check that allow-external-apps=true is set and that Termux is allowed to run in the background.")
            return result ?: Result(false, "", "", -1, "Termux sent no result.")
        } finally {
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }

    /* ── reading the runner's output ──────────────────────────────────────── */

    class Step(val name: String, val code: Int, val out: String)
    class Report(val lang: String, val steps: List<Step>, val flags: List<String>)

    fun parse(text: String): Report {
        var lang = ""
        val steps = ArrayList<Step>()
        val flags = ArrayList<String>()
        var inStep = false
        val buf = StringBuilder()
        for (l in text.lines()) {
            when {
                l.startsWith("RUNNER:LANG ") -> lang = l.removePrefix("RUNNER:LANG ").trim()
                l.startsWith("RUNNER:STEP ") -> { inStep = true; buf.setLength(0) }
                l.startsWith("RUNNER:EXIT ") -> {
                    val p = l.removePrefix("RUNNER:EXIT ").trim().split(" ")
                    steps.add(Step(p[0], p.getOrNull(1)?.toIntOrNull() ?: -1, buf.toString().trim()))
                    inStep = false
                }
                l.startsWith("RUNNER:DONE") -> {}
                l.startsWith("RUNNER:") -> flags.add(l.removePrefix("RUNNER:").trim())
                inStep -> buf.appendLine(l)
            }
        }
        // A step that ran twice (after Myraa installed something) counts by its last run.
        val last = LinkedHashMap<String, Step>()
        for (st in steps) { last.remove(st.name); last[st.name] = st }
        return Report(lang, last.values.toList(), flags)
    }

    /** The environment is the problem (nothing to fix in the code): a plain sentence, or null. */
    fun environmentProblem(r: Report): String? = when {
        r.flags.any { it.startsWith("NO_SOURCE") } -> "Termux could not find the project folder."
        r.flags.any { it.startsWith("COPY_FAILED") } -> "Termux can't read the shared projects folder yet. In Termux run termux-setup-storage and allow storage access, then try again."
        r.flags.any { it.startsWith("MISSING") } -> {
            val m = r.flags.first { it.startsWith("MISSING") }.split(" ")
            val tool = m.getOrNull(1) ?: "a needed tool"
            val pk = m.getOrNull(2) ?: ""
            if (m.getOrNull(3) == "tried") "Termux could not install $tool by itself (package $pk). Check the internet and free storage, or in Termux run: pkg update && pkg install $pk."
            else "Termux does not have $tool yet. Ask again with install_tools true (the default), or in Termux run: pkg install $pk."
        }
        r.lang == "static" -> "This is a static website, not a program. Use site_test instead."
        r.lang == "unknown" -> "I could not tell what language this project is, so I could not run it."
        else -> null
    }

    /** What failed, in plain words with the end of the output. Empty list means nothing failed. A run that hits the time limit (exit 124) is normal for servers and bots. */
    fun problems(r: Report): List<String> {
        val out = ArrayList<String>()
        for (s in r.steps) {
            val tail = s.out.takeLast(1200)
            when {
                s.name == "install" && s.code != 0 -> out.add("Installing the dependencies failed (exit ${s.code}):\n$tail")
                s.name == "build" && s.code != 0 -> out.add("The build failed (exit ${s.code}):\n$tail")
                s.name == "tests" && s.code != 0 -> out.add("The tests failed (exit ${s.code}):\n$tail")
                s.name == "run" && s.code != 0 && s.code != 124 -> out.add("The program crashed (exit ${s.code}):\n$tail")
            }
        }
        for (f in r.flags) if (f.startsWith("INSTALLED ") && f.contains("-not-listed-in-")) {
            val p = f.removePrefix("INSTALLED ").substringBefore("-not-listed-in-")
            val file = f.substringAfter("-not-listed-in-")
            out.add("The program needs the package \"$p\" but $file does not list it, so it only worked because Termux installed it just now. Add it to $file.")
        }
        if (r.flags.any { it.startsWith("NO_ENTRY") }) out.add("There is no file to start the program (for example main.py, index.js, a main class).")
        return out
    }

    /** One honest line about what was really done. */
    fun describe(r: Report): String {
        val parts = ArrayList<String>()
        for (s in r.steps) parts.add(when (s.name) {
            "install" -> if (s.code == 0) "dependencies installed" else "install failed"
            "build" -> if (s.code == 0) "built" else "build failed"
            "tests" -> if (s.code == 0) "tests passed" else "tests failed"
            "run" -> when (s.code) { 0 -> "ran and finished normally"; 124 -> "ran until the time limit without crashing (normal for servers and bots)"; else -> "crashed" }
            else -> s.name
        })
        if (r.steps.none { it.name == "tests" }) parts.add("no tests in the project")
        val inst = r.flags.filter { it.startsWith("INSTALLED ") && !it.contains("-not-listed-in-") }.map { it.removePrefix("INSTALLED ") }
        if (inst.isNotEmpty()) parts.add("Termux installed what was missing: ${inst.joinToString(", ")}")
        return "${r.lang}: " + parts.joinToString(", ")
    }
}
