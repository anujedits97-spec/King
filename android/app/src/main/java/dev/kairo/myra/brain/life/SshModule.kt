package dev.kairo.myra.brain.life

import dev.kairo.myra.Fail
import dev.kairo.myra.brain.BrainEnv
import dev.kairo.myra.brain.BrainTool
import dev.kairo.myra.brain.ConfirmGate
import dev.kairo.myra.brain.Risk
import dev.kairo.myra.brain.ToolModule
import dev.kairo.myra.brain.prop
import dev.kairo.myra.brain.schema
import dev.kairo.myra.brain.tool
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.json.JSONObject
import java.io.File
import java.security.PublicKey
import java.util.concurrent.TimeUnit

/**
 * SSH to your own servers, but never with free-form shell. Myraa can only run fixed recipes built from
 * the server settings you saved (bot folder, site folder, pm2 name), plus read-only status and logs.
 * Rules: the host key must be trusted once (ssh_trust, with a spoken yes); deploy, restart, test and
 * install ask first; packages must be on the server's allowed list; names and paths are checked so
 * nothing can be injected into a command. Secrets never go to chat or logs.
 */
class SshModule(private val env: BrainEnv) : ToolModule {
    private val file = File(env.dir, "servers.json")
    private val gate = ConfirmGate()
    private val nameRe = Regex("^[A-Za-z0-9_.-]{1,40}$")
    private val pathRe = Regex("^[A-Za-z0-9_./~-]{1,200}$")
    private val pkgRe = Regex("^[a-z0-9][a-z0-9.+-]{0,60}$")
    private val repoRe = Regex("^https://github\\.com/[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}(\\.git)?$")
    private val zipRe = Regex("^[A-Za-z0-9 _.()-]{1,120}\\.zip$")
    private val maxZipBytes = 50L * 1024 * 1024

    private fun load(): JSONObject = try { if (file.exists()) JSONObject(file.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }
    private fun save(o: JSONObject) { file.writeText(o.toString(1)) }

    private fun server(name: String): JSONObject {
        val all = load()
        return all.optJSONObject(name) ?: throw Fail("I don't have a server called $name. Add it first with server_add.")
    }

    private fun checkPath(p: String): String {
        if (!pathRe.matches(p) || p.contains("..")) throw Fail("That folder path is not allowed: $p")
        return p
    }

    private fun checkName(n: String, what: String): String {
        if (!nameRe.matches(n)) throw Fail("$what name has characters that are not allowed.")
        return n
    }

    /* ── connecting ─────────────────────────────────────────────────────── */

    private fun keyFile(s: JSONObject): File {
        // The key must be a file the user attached to Myraa (kept in the workspace attachments folder).
        val rel = s.optString("key_path")
        val f = File(env.workspace, rel).canonicalFile
        if (!f.path.startsWith(env.workspace.canonicalPath + File.separator) || !f.isFile) {
            throw Fail("I can't find the SSH key. Attach the private key file to the chat first.")
        }
        return f
    }

    private fun connect(s: JSONObject, verifier: HostKeyVerifier?): SSHClient {
        val ssh = SSHClient()
        if (verifier != null) ssh.addHostKeyVerifier(verifier)
        else {
            val fp = s.optString("host_fingerprint")
            if (fp.isEmpty()) throw Fail("This server is not trusted yet. Run ssh_trust first.")
            ssh.addHostKeyVerifier(fp)
        }
        ssh.connectTimeout = 15_000
        ssh.timeout = 60_000
        try {
            ssh.connect(s.getString("host"), s.optInt("port", 22))
            ssh.authPublickey(s.getString("user"), ssh.loadKeys(keyFile(s).path))
        } catch (e: Exception) {
            runCatching { ssh.disconnect() }
            throw Fail("Could not connect to the server. Check the address, the key and that SSH is open.")
        }
        return ssh
    }

    /** Runs one fixed command and returns (exit code, output truncated for speech). */
    private fun exec(s: JSONObject, command: String): Pair<Int, String> {
        val ssh = connect(s, null)
        try {
            ssh.startSession().use { session ->
                val cmd = session.exec(command)
                val out = cmd.inputStream.bufferedReader().readText() + cmd.errorStream.bufferedReader().readText()
                cmd.join(300, TimeUnit.SECONDS)
                return Pair(cmd.exitStatus ?: -1, out.trim().takeLast(3000))
            }
        } finally {
            runCatching { ssh.disconnect() }
        }
    }

    /* ── tools ──────────────────────────────────────────────────────────── */

    private fun serverAdd(a: JSONObject): String {
        val name = checkName(a.optString("name").trim(), "Server")
        val host = a.optString("host").trim()
        val user = checkName(a.optString("user").trim(), "User")
        if (host.isEmpty()) throw Fail("What is the server address?")
        val keyPath = a.optString("key_path").trim()
        if (keyPath.isEmpty()) throw Fail("Attach the SSH private key to the chat, then tell me its file name.")
        val s = JSONObject()
            .put("host", host).put("user", user).put("port", a.optInt("port", 22))
            .put("key_path", "attachments/" + File(keyPath).name)
            .put("bot_dir", checkPath(a.optString("bot_dir", "~/bot")))
            .put("site_dir", checkPath(a.optString("site_dir", "~/site")))
            .put("bot_name", a.optString("bot_name", "bot").let { checkName(it, "Process") })
            .put("bot_entry", checkPath(a.optString("bot_entry", "index.js")))
            .put("allowed_packages", a.optJSONArray("allowed_packages") ?: org.json.JSONArray())
        val all = load()
        all.put(name, s)
        save(all)
        return "Saved server $name. Next, run ssh_trust so I can check its fingerprint."
    }

    private fun trust(a: JSONObject): String {
        val name = a.optString("server")
        val s = server(name)
        var seen = ""
        val capture = object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
                seen = if (key == null) "" else SecurityUtils.getFingerprint(key)
                return seen.isNotEmpty()
            }
            override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> = mutableListOf()
        }
        val ssh = connect(s, capture)
        runCatching { ssh.disconnect() }
        if (seen.isEmpty()) throw Fail("I could not read the server's fingerprint.")
        gate.gate("ssh_trust", "$name|$seen", a.optString("confirm_token"),
            "the fingerprint of $name is $seen. Is that your server?")?.let { return it }
        val all = load()
        all.getJSONObject(name).put("host_fingerprint", seen)
        save(all)
        return "Trusted $name. From now on I will only connect to that exact server."
    }

    private fun status(a: JSONObject): String {
        val s = server(a.optString("server"))
        val bot = checkName(s.optString("bot_name"), "Process")
        val (_, out) = exec(s, "uptime; echo ---; df -h / | tail -1; echo ---; free -m | head -2; echo ---; pm2 status $bot 2>/dev/null || echo 'pm2 not found'")
        return out
    }

    /** Read-only: looks at the bot and site folders and the tools on the server, then reports what is missing. */
    private fun inspect(a: JSONObject): String {
        val s = server(a.optString("server"))
        val bdir = checkPath(s.optString("bot_dir"))
        val sdir = checkPath(s.optString("site_dir"))
        val cmd = listOf(
            "echo '== bot folder =='; cd $bdir 2>/dev/null && ls -1 | head -60 || echo 'missing'",
            "echo '== package.json =='; head -c 1500 $bdir/package.json 2>/dev/null || echo 'none'",
            "echo '== requirements.txt =='; head -c 800 $bdir/requirements.txt 2>/dev/null || echo 'none'",
            "echo '== site folder =='; cd $sdir 2>/dev/null && ls -1 | head -40 || echo 'missing'",
            "echo '== tools =='; for t in git node npm python3 pm2; do printf '%s: ' \$t; (command -v \$t >/dev/null && \$t --version 2>/dev/null | head -1) || echo missing; done"
        ).joinToString("; echo; ")
        val (_, out) = exec(s, cmd)
        return out
    }

    private fun logs(a: JSONObject): String {
        val s = server(a.optString("server"))
        val bot = checkName(s.optString("bot_name"), "Process")
        val lines = a.optInt("lines", 50).coerceIn(5, 200)
        val (_, out) = exec(s, "pm2 logs $bot --lines $lines --nostream 2>&1 | tail -n $lines")
        return if (out.isBlank()) "No logs." else out
    }

    /** The fixed recipes. Each one is built from the saved settings, never from free text. */
    private fun recipeCommand(name: String, s: JSONObject): Pair<String, String> {
        val bot = checkName(s.optString("bot_name"), "Process")
        val bdir = checkPath(s.optString("bot_dir"))
        val sdir = checkPath(s.optString("site_dir"))
        return when (name) {
            "bot_deploy" -> "bot_deploy" to "cd $bdir && git pull --ff-only && npm ci --omit=dev && pm2 restart $bot && pm2 save"
            "bot_test" -> "bot_test" to "cd $bdir && npm test --silent"
            "bot_restart" -> "bot_restart" to "pm2 restart $bot"
            "site_deploy" -> "site_deploy" to "cd $sdir && git pull --ff-only"
            "bot_install_node" -> "bot_install_node" to "cd $bdir && npm ci --omit=dev"
            "bot_install_python" -> "bot_install_python" to "cd $bdir && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt"
            "bot_start" -> {
                val entry = checkPath(s.optString("bot_entry", "index.js"))
                "bot_start" to "cd $bdir && pm2 start $entry --name $bot && pm2 save"
            }
            "pm2_setup" -> "pm2_setup" to "npm install -g pm2"
            else -> throw Fail("I don't know the recipe $name.")
        }
    }

    private fun recipe(a: JSONObject): String {
        val s = server(a.optString("server"))
        val (label, cmd) = recipeCommand(a.optString("recipe"), s)
        val sentence = "run the $label recipe on ${a.optString("server")}: it will run $label now. Is that OK?"
        gate.gate("ssh_recipe", "${a.optString("server")}|$label", a.optString("confirm_token"), sentence)?.let { return it }
        val (code, out) = exec(s, cmd)
        val result = if (code == 0) "$label finished successfully." else "$label failed with exit code $code."
        return "$result\n$out"
    }

    /** Puts the project on the server from a public GitHub link (clone once, then pull). Asks first. */
    private fun cloneRepo(a: JSONObject): String {
        val name = a.optString("server")
        val s = server(name)
        val url = a.optString("url").trim()
        if (!repoRe.matches(url)) throw Fail("I can only clone public GitHub links like https://github.com/owner/repo.")
        val bdir = checkPath(s.optString("bot_dir"))
        gate.gate("ssh_clone", "$name|$url|$bdir", a.optString("confirm_token"),
            "clone $url into $bdir on $name? If the folder already has the project, it will only pull the latest code.")?.let { return it }
        val cmd = "if [ -d $bdir/.git ]; then cd $bdir && git pull --ff-only; " +
            "else mkdir -p \$(dirname $bdir) && git clone --depth 1 $url $bdir; fi"
        val (code, out) = exec(s, cmd)
        return if (code == 0) "Project is on the server at $bdir.\n$out" else "Clone failed (code $code). Is the repo public?\n$out"
    }

    /** Sends a zip you attached in chat to the server over SFTP and unzips it into the bot folder. Asks first. */
    private fun uploadZip(a: JSONObject): String {
        val name = a.optString("server")
        val s = server(name)
        val zipName = a.optString("file").trim()
        if (!zipRe.matches(zipName)) throw Fail("Attach the zip to the chat first, then tell me its file name.")
        val local = File(env.workspace, "attachments/$zipName").canonicalFile
        if (!local.path.startsWith(env.workspace.canonicalPath + File.separator) || !local.isFile) {
            throw Fail("I can't find $zipName. Attach it to the chat again.")
        }
        if (local.length() > maxZipBytes) throw Fail("That zip is bigger than 50 MB. Use a GitHub link instead.")
        val bdir = checkPath(s.optString("bot_dir"))
        gate.gate("ssh_upload", "$name|$zipName|$bdir", a.optString("confirm_token"),
            "upload $zipName to $name and unzip it into $bdir? Files with the same names will be replaced.")?.let { return it }
        val remote = "/tmp/myra-upload-${System.currentTimeMillis()}.zip"
        val ssh = connect(s, null)
        try {
            ssh.newSFTPClient().use { it.put(local.path, remote) }
        } catch (e: Exception) {
            throw Fail("The upload failed. Check the connection and that there is enough disk space.")
        } finally {
            runCatching { ssh.disconnect() }
        }
        val (code, out) = exec(s, "mkdir -p $bdir && unzip -o $remote -d $bdir; rc=\$?; rm -f $remote; exit \$rc")
        return when (code) {
            0 -> "Uploaded and unzipped $zipName into $bdir.\n$out"
            127 -> "The server has no unzip tool. Ask me to install unzip first (ssh_install, if it is on the allowed list)."
            else -> "Unzip failed (code $code).\n$out"
        }
    }

    /** Read-only: works out what kind of project is in the bot folder (Docker, Node, Python, static site). */
    private data class Stack(val kind: String, val hasStart: Boolean, val hasLock: Boolean)

    private fun detect(s: JSONObject): Stack {
        val bdir = checkPath(s.optString("bot_dir"))
        val (_, out) = exec(s,
            "cd $bdir 2>/dev/null || { echo none; exit 0; }; " +
                "if [ -f Dockerfile ] || [ -f docker-compose.yml ]; then echo docker; " +
                "elif [ -f package.json ]; then echo node; " +
                "elif [ -f requirements.txt ]; then echo python; " +
                "elif [ -f index.html ]; then echo static; else echo unknown; fi; " +
                "grep -q '\"start\"' package.json 2>/dev/null && echo hasStart; " +
                "[ -f package-lock.json ] && echo hasLock; true")
        val lines = out.lines().map { it.trim() }
        val kind = lines.firstOrNull { it in setOf("docker", "node", "python", "static", "unknown", "none") } ?: "none"
        return Stack(kind, "hasStart" in lines, "hasLock" in lines)
    }

    /** The one command that installs and starts the project, chosen from what detect() found. */
    private fun planFor(s: JSONObject, st: Stack): Pair<String, String> {
        val bot = checkName(s.optString("bot_name"), "Process")
        val bdir = checkPath(s.optString("bot_dir"))
        val entry = checkPath(s.optString("bot_entry", "index.js"))
        return when (st.kind) {
            "node" -> {
                val install = if (st.hasLock) "npm ci --omit=dev" else "npm install --omit=dev"
                val start = if (st.hasStart) "pm2 delete $bot >/dev/null 2>&1; pm2 start npm --name $bot --cwd $bdir -- start"
                else "pm2 delete $bot >/dev/null 2>&1; pm2 start $bdir/$entry --name $bot"
                "Node.js: install packages, then start with pm2" to "cd $bdir && $install && $start && pm2 save"
            }
            "python" -> "Python: create a venv, install requirements.txt, then start $entry with pm2" to
                "cd $bdir && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt && " +
                "pm2 delete $bot >/dev/null 2>&1; pm2 start $bdir/$entry --name $bot --interpreter $bdir/.venv/bin/python && pm2 save"
            "docker" -> "Docker: build and start with docker compose" to "cd $bdir && docker compose up -d --build"
            "static" -> throw Fail("This is a plain website (index.html). It has no bot process. Serving it needs nginx, which I can set up separately.")
            "none" -> throw Fail("The project folder is empty. Get the code first with ssh_clone or ssh_upload_zip.")
            else -> throw Fail("I could not tell what kind of project this is (no package.json, requirements.txt or Dockerfile). Tell me what it uses and I'll add it.")
        }
    }

    private fun previewDeploy(a: JSONObject): String {
        val s = server(a.optString("server"))
        val st = detect(s)
        val (label, cmd) = planFor(s, st)
        return "Detected: ${st.kind}. Plan: $label.\nCommand: $cmd"
    }

    /** Installs and starts the project after one spoken yes. Fixed commands only, chosen from detect(). */
    private fun deploy(a: JSONObject): String {
        val name = a.optString("server")
        val s = server(name)
        val st = detect(s)
        val (label, cmd) = planFor(s, st)
        gate.gate("ssh_deploy", "$name|${st.kind}|$cmd", a.optString("confirm_token"),
            "$label on $name. Shall I go ahead?")?.let { return it }
        val (code, out) = exec(s, cmd)
        return if (code == 0) "Deployed. $label done.\n$out" else "Deploy failed (code $code).\n$out"
    }

    private fun installPkg(a: JSONObject): String {
        val s = server(a.optString("server"))
        val pkg = a.optString("package").trim().lowercase()
        if (!pkgRe.matches(pkg)) throw Fail("That package name is not valid.")
        val allowed = s.optJSONArray("allowed_packages")
        val ok = allowed != null && (0 until allowed.length()).any { allowed.optString(it) == pkg }
        if (!ok) throw Fail("$pkg is not on this server's allowed package list. Add it to allowed_packages first.")
        gate.gate("ssh_install", "${a.optString("server")}|$pkg", a.optString("confirm_token"),
            "install the package $pkg on ${a.optString("server")} with apt? This needs sudo on the server.")?.let { return it }
        val (code, out) = exec(s, "sudo -n apt-get install -y $pkg")
        return if (code == 0) "Installed $pkg.\n$out" else "Installing $pkg failed (code $code).\n$out"
    }

    override fun tools(): List<BrainTool> = listOf(
        tool("server_add", "Save one of the user's own servers (name, address, login user, the SSH key file attached to chat, bot folder, site folder, pm2 process name, allowed packages).", Risk.LOW,
            schema(listOf("name", "host", "user", "key_path"), "name" to prop("string"), "host" to prop("string"), "user" to prop("string"),
                "port" to prop("integer"), "key_path" to prop("string", "Name of the attached private key file"),
                "bot_dir" to prop("string"), "site_dir" to prop("string"), "bot_name" to prop("string", "pm2 process name"),
                "bot_entry" to prop("string", "Main file of the bot, e.g. index.js"), "allowed_packages" to prop("array"))) { serverAdd(it) },
        tool("ssh_trust", "Check a saved server's fingerprint and trust it after the user says yes. Needed once before anything else.", Risk.HIGH,
            schema(listOf("server"), "server" to prop("string"), "confirm_token" to prop("string"))) { trust(it) },
        tool("ssh_status", "Read-only: uptime, disk, memory and bot process status of a saved server.", Risk.LOW,
            schema(listOf("server"), "server" to prop("string"))) { status(it) },
        tool("ssh_inspect", "Read-only: look at a saved server's bot and site folders (files, package.json, requirements.txt) and which tools are installed, so Myraa can propose what to install.", Risk.LOW,
            schema(listOf("server"), "server" to prop("string"))) { inspect(it) },
        tool("ssh_logs", "Read-only: last log lines of the bot process on a saved server.", Risk.LOW,
            schema(listOf("server"), "server" to prop("string"), "lines" to prop("integer"))) { logs(it) },
        tool("ssh_recipe", "Run a fixed install or deploy recipe on a saved server (bot_deploy, bot_test, bot_restart, site_deploy, bot_install_node, bot_install_python, bot_start, pm2_setup). Each one asks the user out loud first. Always run ssh_inspect first and propose steps.", Risk.HIGH,
            schema(listOf("server", "recipe"), "server" to prop("string"),
                "recipe" to prop("string", "", listOf("bot_deploy", "bot_test", "bot_restart", "site_deploy", "bot_install_node", "bot_install_python", "bot_start", "pm2_setup")),
                "confirm_token" to prop("string"))) { recipe(it) },
        tool("ssh_clone", "Put a project on a saved server from a public GitHub link (https://github.com/owner/repo) into its bot folder, or pull the latest if already there. Asks the user out loud first.", Risk.HIGH,
            schema(listOf("server", "url"), "server" to prop("string"), "url" to prop("string"), "confirm_token" to prop("string"))) { cloneRepo(it) },
        tool("ssh_upload_zip", "Send a zip the user attached in chat to a saved server and unzip it into its bot folder. Asks the user out loud first.", Risk.HIGH,
            schema(listOf("server", "file"), "server" to prop("string"), "file" to prop("string", "File name of the attached zip"), "confirm_token" to prop("string"))) { uploadZip(it) },
        tool("ssh_detect", "Read-only: tell what kind of project is on a saved server (Node, Python, Docker, static site) and the exact install and start plan. Use before ssh_deploy.", Risk.LOW,
            schema(listOf("server"), "server" to prop("string"))) { previewDeploy(it) },
        tool("ssh_deploy", "Install and start the project already on a saved server (Node, Python or Docker), chosen automatically. Asks the user out loud first. Run ssh_detect before it.", Risk.HIGH,
            schema(listOf("server"), "server" to prop("string"), "confirm_token" to prop("string"))) { deploy(it) },
        tool("ssh_install", "Install an allowed apt package on a saved server. Asks the user out loud first.", Risk.HIGH,
            schema(listOf("server", "package"), "server" to prop("string"), "package" to prop("string"), "confirm_token" to prop("string"))) { installPkg(it) },
    )
}
