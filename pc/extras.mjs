/**
 * Extra PC tools for the MYRA PC bridge, ported from the Myraa desktop agent:
 * volume level, brightness, clipboard, window control, safe file tools, system/GPU info,
 * proposal files, and confirmed power actions.
 *
 * Same rules as the rest of the bridge: no shells, no model-written commands. Every action is
 * fixed here in code. Files are limited to the folders in pc.json "fileRoots", nothing is ever
 * deleted, moving files and power actions are OFF unless you switch them on in pc.json
 * ("fileEdits": true, "powerActions": true).
 */
import crypto from 'node:crypto'
import { existsSync, mkdirSync, readdirSync, readFileSync, realpathSync, renameSync, statSync, writeFileSync } from 'node:fs'
import path from 'node:path'

const USER32 = `Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class JUser { [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra); [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd); [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h); }';`

const PROTECTED = new Set(['explorer', 'dwm', 'winlogon', 'csrss', 'lsass', 'services', 'system', 'smss', 'wininit', 'svchost', 'taskmgr'])
const SECRET_NAME = /(\.(env|pem|key|pfx|p12|kdbx|ppk)$|password|passwd|secret|token|credential|id_rsa|wallet)/i
const TEXT_EXT = /\.(txt|md|json|csv|log|py|js|mjs|ts|tsx|jsx|html|css|xml|yml|yaml|ini|cfg|bat|sh|kt|java|c|cpp|h|cs|sql|rtf|tex)$/i
const SKIP_DIRS = new Set(['node_modules', '.git', 'appdata', '$recycle.bin', 'system volume information'])

const clamp = (n, lo, hi) => Math.max(lo, Math.min(hi, Math.round(Number(n))))
const num = (v) => Number.isFinite(Number(v))

export function makeExtras({ CONF, OUT, ps, run, log }) {
  const dry = () => process.env.MYRA_DRY_RUN === '1'

  /* ── volume / brightness ────────────────────────────────────────────────── */

  async function pcVolume({ level } = {}) {
    if (!num(level)) throw new Error('Pass a volume level from 0 to 100.')
    const target = clamp(level, 0, 100)
    if (dry()) return `(dry run) would set volume to ${target}%`
    // Windows has no keyboard shortcut for an exact volume, so go to zero, then up. Each key press is about 2%.
    const ups = Math.round(target / 2)
    await ps(`${USER32} for ($i = 0; $i -lt 50; $i++) { [JUser]::keybd_event(0xae, 0, 0, [UIntPtr]::Zero); [JUser]::keybd_event(0xae, 0, 2, [UIntPtr]::Zero); Start-Sleep -Milliseconds 15 }
for ($i = 0; $i -lt ${ups}; $i++) { [JUser]::keybd_event(0xaf, 0, 0, [UIntPtr]::Zero); [JUser]::keybd_event(0xaf, 0, 2, [UIntPtr]::Zero); Start-Sleep -Milliseconds 15 }`, { timeoutMs: 30_000 })
    return `PC volume set to about ${target} percent.`
  }

  async function pcBrightness({ level } = {}) {
    const NO = "This screen doesn't allow brightness control (desktop monitors usually don't)."
    if (level == null || level === '') {
      const out = await ps('(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightness | Select-Object -First 1).CurrentBrightness', { timeoutMs: 15_000 }).catch(() => '')
      return out.trim() ? `Screen brightness is ${out.trim()} percent.` : NO
    }
    if (!num(level)) throw new Error('Pass a brightness level from 0 to 100.')
    const target = clamp(level, 0, 100)
    if (dry()) return `(dry run) would set brightness to ${target}%`
    try {
      await ps('$m = Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods | Select-Object -First 1; if (-not $m) { throw "none" }; Invoke-CimMethod -InputObject $m -MethodName WmiSetBrightness -Arguments @{ Timeout = 1; Brightness = [byte]$env:J_LEVEL } | Out-Null',
        { env: { J_LEVEL: String(target) }, timeoutMs: 15_000 })
    } catch { return NO }
    return `Screen brightness set to ${target} percent.`
  }

  /* ── clipboard ──────────────────────────────────────────────────────────── */

  async function pcClipboard({ action, text } = {}) {
    if (action === 'get') {
      const out = (await ps('Get-Clipboard -Raw', { timeoutMs: 10_000 })).trim()
      if (!out) return 'The PC clipboard is empty.'
      return out.length > 2000 ? `Clipboard (first 2000 characters): ${out.slice(0, 2000)}` : `Clipboard: ${out}`
    }
    if (action === 'set') {
      const t = String(text ?? '')
      if (!t) throw new Error('Pass the text to copy.')
      if (t.length > 20_000) throw new Error('That text is too long for the clipboard tool.')
      if (dry()) return '(dry run) would set the clipboard'
      await ps('Set-Clipboard -Value $env:J_TEXT', { env: { J_TEXT: t }, timeoutMs: 10_000 })
      return 'Copied to the PC clipboard.'
    }
    if (action === 'clear') {
      if (dry()) return '(dry run) would clear the clipboard'
      await ps('Set-Clipboard -Value $null', { timeoutMs: 10_000 })
      return 'PC clipboard cleared.'
    }
    throw new Error('Use action get, set or clear.')
  }

  /* ── windows ────────────────────────────────────────────────────────────── */

  const WINDOW_ACTIONS = { minimize: 6, maximize: 3, restore: 9 }

  async function pcWindow({ action, title } = {}) {
    if (action === 'list') {
      const out = await ps('Get-Process | Where-Object { $_.MainWindowTitle } | ForEach-Object { $_.ProcessName + " | " + $_.MainWindowTitle }', { timeoutMs: 15_000 })
      const lines = out.split('\n').map((l) => l.trim()).filter(Boolean).slice(0, 25)
      return lines.length ? `Open windows:\n${lines.join('\n')}` : 'No open windows found.'
    }
    const t = String(title || '').trim()
    if (!t) throw new Error('Pass part of the window title or the app name.')
    if (!['minimize', 'maximize', 'restore', 'focus', 'close'].includes(action)) throw new Error('Use action list, minimize, maximize, restore, focus or close.')
    if (PROTECTED.has(t.toLowerCase())) throw new Error(`Won't touch ${t}: it is part of Windows.`)
    if (dry()) return `(dry run) would ${action} the window matching "${t}"`
    const cmd = action === 'close' ? '$null = $p.CloseMainWindow()'
      : action === 'focus' ? '[JUser]::ShowWindow($p.MainWindowHandle, 9) | Out-Null; [JUser]::SetForegroundWindow($p.MainWindowHandle) | Out-Null'
      : `[JUser]::ShowWindow($p.MainWindowHandle, ${WINDOW_ACTIONS[action]}) | Out-Null`
    const protectedList = [...PROTECTED].map((n) => `'${n}'`).join(',')
    const out = await ps(`${USER32}
$q = "*" + $env:J_TITLE + "*"
$p = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and ($_.MainWindowTitle -like $q -or $_.ProcessName -like $q) -and @(${protectedList}) -notcontains $_.ProcessName.ToLower() } | Select-Object -First 1
if (-not $p) { 'NONE' } else { ${cmd}; $p.ProcessName + ' | ' + $p.MainWindowTitle }`, { env: { J_TITLE: t }, timeoutMs: 15_000 })
    const r = out.trim()
    if (r === 'NONE') return `No window matching "${t}" was found.`
    return `Done: ${action} ${r}.`
  }

  /* ── files (read-only by default, inside the chosen folders) ────────────── */

  const roots = () => (CONF.fileRoots || []).map((r) => path.resolve(r))
  const real = (p) => { try { return realpathSync(p) } catch { return path.resolve(p) } }
  const within = (p) => {
    const r = real(p).toLowerCase()
    return roots().some((x) => { const b = real(x).toLowerCase(); return r === b || r.startsWith(b + path.sep) })
  }
  function resolveIn(p) {
    const s = String(p || '').trim().replace(/^["']+|["']+$/g, '')
    if (!s) throw new Error('Pass a path.')
    const named = roots().find((r) => path.basename(r).toLowerCase() === s.toLowerCase())
    const full = named || (path.isAbsolute(s) ? path.resolve(s) : path.resolve(roots()[0] || '.', s))
    if (!within(full)) throw new Error(`That path is outside the folders MYRA may use (${roots().map((r) => path.basename(r)).join(', ')}).`)
    return full
  }

  function walk(dir, query, out, state, depth = 0) {
    if (depth > 5 || out.length >= 20 || state.seen > 4000) return
    let items
    try { items = readdirSync(dir, { withFileTypes: true }) } catch { return }
    for (const it of items) {
      state.seen++
      if (SKIP_DIRS.has(it.name.toLowerCase())) continue
      const full = path.join(dir, it.name)
      if (it.name.toLowerCase().includes(query)) out.push(full)
      if (it.isDirectory()) walk(full, query, out, state, depth + 1)
      if (out.length >= 20 || state.seen > 4000) return
    }
  }

  async function pcFiles({ action, path: p, query, to } = {}) {
    if (action === 'roots') return `MYRA may look in: ${roots().join(', ')}.`
    if (action === 'list') {
      const dir = resolveIn(p || path.basename(roots()[0] || ''))
      if (!statSync(dir).isDirectory()) throw new Error('That is a file, not a folder.')
      const items = readdirSync(dir, { withFileTypes: true }).slice(0, 40)
      if (!items.length) return `${dir} is empty.`
      return `${dir}:\n${items.map((i) => `${i.isDirectory() ? '[folder] ' : ''}${i.name}`).join('\n')}`
    }
    if (action === 'search') {
      const q = String(query || '').trim().toLowerCase()
      if (!q) throw new Error('Pass part of a file name to search for.')
      const found = []
      const state = { seen: 0 }
      for (const r of p ? [resolveIn(p)] : roots()) walk(r, q, found, state)
      return found.length ? `Found:\n${found.join('\n')}` : `Nothing named like "${q}" in the allowed folders.`
    }
    if (action === 'read') {
      const f = resolveIn(p)
      if (SECRET_NAME.test(path.basename(f))) throw new Error("Won't read files that look like passwords, keys or secrets.")
      if (!statSync(f).isFile()) throw new Error('That is a folder, not a file.')
      if (!TEXT_EXT.test(f)) throw new Error('MYRA reads text and code files only.')
      if (statSync(f).size > 300_000) throw new Error('That file is too big to read aloud.')
      const t = readFileSync(f, 'utf8')
      return t.length > 3000 ? `${f} (first 3000 characters):\n${t.slice(0, 3000)}` : `${f}:\n${t}`
    }
    if (action === 'move') {
      if (!CONF.fileEdits) throw new Error('Moving and renaming files is switched off. Set "fileEdits": true in pc.json to allow it.')
      const from = resolveIn(p), dest = resolveIn(to)
      if (!existsSync(from)) throw new Error('The file to move does not exist.')
      if (existsSync(dest)) throw new Error('Something already exists at the destination. Nothing was overwritten.')
      if (dry()) return `(dry run) would move ${from} to ${dest}`
      mkdirSync(path.dirname(dest), { recursive: true })
      renameSync(from, dest)
      return `Moved ${path.basename(from)} to ${dest}.`
    }
    throw new Error('Use action roots, list, search, read or move.')
  }

  /* ── system info ────────────────────────────────────────────────────────── */

  async function pcSystemInfo() {
    const raw = await ps(`
$cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1).Name
$os = (Get-CimInstance Win32_OperatingSystem).Caption
$gpus = @(Get-CimInstance Win32_VideoController | ForEach-Object { $_.Name })
$temps = @()
try { $temps = @(Get-CimInstance -Namespace root/wmi -ClassName MSAcpi_ThermalZoneTemperature -ErrorAction Stop | ForEach-Object { [math]::Round($_.CurrentTemperature / 10 - 273.15, 1) }) } catch {}
[pscustomobject]@{ cpu = $cpu; os = $os; gpus = $gpus; temps = $temps } | ConvertTo-Json -Compress`, { timeoutMs: 20_000 })
    const j = JSON.parse(raw)
    const L = [`System: ${String(j.os || 'Windows').trim()}`, `Processor: ${String(j.cpu || '?').trim()}`]
    const gpus = [].concat(j.gpus || [])
    if (gpus.length) L.push(`Graphics: ${gpus.join(', ')}`)
    const temps = [].concat(j.temps || [])
    L.push(temps.length ? `Temperature: ${temps.join(', ')} degrees Celsius` : 'Temperature: not available (Windows only shares it on some PCs)')
    const smi = await run('nvidia-smi', ['--query-gpu=utilization.gpu,temperature.gpu,memory.used,memory.total', '--format=csv,noheader,nounits'], { timeoutMs: 8000 }).catch(() => null)
    if (smi && smi.code === 0 && smi.out.trim()) {
      const [util, temp, used, total] = smi.out.trim().split('\n')[0].split(',').map((s) => s.trim())
      L.push(`NVIDIA GPU: ${util}% busy, ${temp} degrees, ${used} of ${total} MB memory used`)
    }
    return L.join('\n')
  }

  /* ── proposals: write the plan, open it in Notepad, wait for a yes ───────── */

  async function pcProposal({ title, text } = {}) {
    const t = String(text || '').trim()
    if (!t) throw new Error('Pass the plan text.')
    const dir = path.join(OUT, 'proposals')
    mkdirSync(dir, { recursive: true })
    const slug = String(title || 'plan').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40) || 'plan'
    const file = path.join(dir, `${new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-')}-${slug}.txt`)
    writeFileSync(file, `${String(title || 'Plan')}\n\n${t}\n`, 'utf8')
    if (dry()) return `(dry run) wrote ${file}`
    await ps('Start-Process -FilePath notepad.exe -ArgumentList ("`"" + $env:J_FILE + "`"")', { env: { J_FILE: file }, timeoutMs: 10_000 })
    return `The plan is written and open in Notepad on the PC (${file}). Ask for a clear yes before doing any of it.`
  }

  /* ── power: shutdown, restart, sleep, only with a confirm token ──────────── */

  const tokens = new Map()
  const LABEL = { shutdown: 'shut down the PC', restart: 'restart the PC', sleep: 'put the PC to sleep' }

  async function pcPower({ action, confirm_token } = {}) {
    if (!CONF.powerActions) throw new Error('Power actions are switched off. Set "powerActions": true in pc.json to allow them.')
    if (action === 'abort') {
      await run('shutdown', ['/a'], { timeoutMs: 8000 }).catch(() => {})
      return 'Cancelled any pending shutdown or restart.'
    }
    if (!LABEL[action]) throw new Error('Use action shutdown, restart, sleep or abort.')
    const now = Date.now()
    for (const [k, v] of tokens) if (v.exp < now) tokens.delete(k)
    if (!confirm_token) {
      const tok = crypto.randomBytes(6).toString('hex')
      tokens.set(tok, { action, exp: now + 60_000 })
      return `Not done yet. Ask Anuj out loud whether to ${LABEL[action]}. Only if he clearly says yes, call pc_power again within one minute with action ${action} and confirm_token ${tok}.`
    }
    const entry = tokens.get(String(confirm_token))
    tokens.delete(String(confirm_token))
    if (!entry || entry.action !== action || entry.exp < now) throw new Error('That confirmation is wrong or expired. Ask again to start over.')
    if (dry()) return `(dry run) would ${LABEL[action]}`
    log('power:', action)
    if (action === 'shutdown') await run('shutdown', ['/s', '/t', '15'], { timeoutMs: 8000 })
    else if (action === 'restart') await run('shutdown', ['/r', '/t', '15'], { timeoutMs: 8000 })
    else await run('rundll32.exe', ['powrprof.dll,SetSuspendState', '0,1,0'], { timeoutMs: 8000 })
    return action === 'sleep' ? 'Putting the PC to sleep.' : `The PC will ${action === 'shutdown' ? 'shut down' : 'restart'} in 15 seconds. Say abort to cancel.`
  }

  /* ── tool list ──────────────────────────────────────────────────────────── */

  const obj = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false })
  const EXTRA_TOOLS = [
    { name: 'pc_volume', description: 'Set the PC volume to an exact level from 0 to 100. For play, pause, next or mute use pc_media.', inputSchema: obj({ level: { type: 'number', description: '0 to 100' } }, ['level']) },
    { name: 'pc_brightness', description: 'Get or set the PC screen brightness (laptop screens). Leave level out to just read it.', inputSchema: obj({ level: { type: 'number', description: '0 to 100. Optional.' } }) },
    { name: 'pc_clipboard', description: "Read, set or clear the PC clipboard. Never read a clipboard aloud if it looks like a password, key or card number.", inputSchema: obj({ action: { type: 'string', enum: ['get', 'set', 'clear'] }, text: { type: 'string', description: 'For set: the text to copy.' } }, ['action']) },
    { name: 'pc_window', description: 'See and control PC windows: list what is open, or minimize, maximize, restore, focus or close one by part of its title or app name. Close asks the app to close politely, so unsaved work still gets its save prompt.', inputSchema: obj({ action: { type: 'string', enum: ['list', 'minimize', 'maximize', 'restore', 'focus', 'close'] }, title: { type: 'string', description: 'Part of the window title or app name, e.g. "chrome".' } }, ['action']) },
    { name: 'pc_files', description: 'Look at files on the PC, only inside the allowed folders (Desktop, Documents, Downloads by default). Actions: roots (which folders), list, search (by part of a name), read (text and code files only), move (also renames; off unless allowed). It can never delete.', inputSchema: obj({ action: { type: 'string', enum: ['roots', 'list', 'search', 'read', 'move'] }, path: { type: 'string', description: 'A folder or file inside the allowed folders. A bare "Downloads" works.' }, query: { type: 'string', description: 'For search: part of a file name.' }, to: { type: 'string', description: 'For move: the new path.' } }, ['action']) },
    { name: 'pc_system_info', description: 'The PC model details: Windows version, processor, graphics card, temperature and NVIDIA GPU load when available. For CPU, memory, battery and disk use pc_status.', inputSchema: obj({}) },
    { name: 'pc_proposal', description: 'Before any bigger file cleanup or multi-step job, write the plan to a text file and open it in Notepad on the PC so Anuj can review it. Then ask for a clear yes.', inputSchema: obj({ title: { type: 'string' }, text: { type: 'string', description: 'The plan, in plain lines.' } }, ['text']) },
    { name: 'pc_power', description: 'Shut down, restart or sleep the PC, or abort a pending shutdown. Two steps: the first call returns a confirm_token and does nothing; ask Anuj out loud; only after a clear yes call again with that token. Switched off unless allowed in pc.json.', inputSchema: obj({ action: { type: 'string', enum: ['shutdown', 'restart', 'sleep', 'abort'] }, confirm_token: { type: 'string', description: 'Only for the second call.' } }, ['action']) },
  ]
  const EXTRA_HANDLERS = { pc_volume: pcVolume, pc_brightness: pcBrightness, pc_clipboard: pcClipboard, pc_window: pcWindow, pc_files: pcFiles, pc_system_info: pcSystemInfo, pc_proposal: pcProposal, pc_power: pcPower }
  return { EXTRA_TOOLS, EXTRA_HANDLERS }
}
