#!/usr/bin/env node
/**
 * MYRA PC bridge - lets MYRA on your phone use this Windows PC over home Wi-Fi.
 *
 * It is a small MCP server (Streamable HTTP, JSON responses) with no dependencies. MYRA on the
 * phone (the Antigravity CLI in Termux) connects to it like any other MCP server and gets a
 * handful of PC tools.
 *
 * Who can use it:
 *  - only devices on your own network (private addresses: 192.168.x, 10.x, 172.16-31.x, and
 *    100.64-127.x for Tailscale) - anything else is refused;
 *  - and only if they know the secret token, which is part of the URL. The token is made the first
 *    time this runs and kept in %USERPROFILE%\.myra-phone\pc.json. It never goes to GitHub.
 *
 * What it can do is fixed here in code, not decided by the model: status, opening apps and links
 * (shells, scripts and C:\Windows refused), a screenshot, locking the PC, media keys, asking
 * MYRA on this PC to do a task, and asking Claude Code (read, search, and edit files inside the
 * project folders you list - it can never run commands).
 */
import { spawn } from 'node:child_process'
import crypto from 'node:crypto'
import dgram from 'node:dgram'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { makeExtras } from './extras.mjs'
import { captureScreen, ps } from './screen.mjs'

const VERSION = '1.1.0'
const HOME = os.homedir()
const CONF_DIR = path.join(HOME, '.myra-phone')
const CONF_FILE = path.join(CONF_DIR, 'pc.json')
const SCRATCH = path.join(HOME, '.gemini', 'antigravity', 'scratch')
const OUT = path.join(SCRATCH, 'MYRA', 'phone')
const DISCOVERY_PORT = 8767
const PROTOCOLS = ['2025-06-18', '2025-03-26']

const stamp = () => new Date().toTimeString().slice(0, 8)
const log = (...a) => console.log(`[${stamp()}]`, ...a)

/* ── config ───────────────────────────────────────────────────────────────── */

function loadConfig() {
  mkdirSync(CONF_DIR, { recursive: true })
  let c = {}
  if (existsSync(CONF_FILE)) {
    try { c = JSON.parse(readFileSync(CONF_FILE, 'utf8')) } catch (e) { throw new Error(`${CONF_FILE} is not valid JSON: ${e.message}`) }
  }
  const npm = path.join(process.env.APPDATA || path.join(HOME, 'AppData', 'Roaming'), 'npm')
  const defaults = {
    token: crypto.randomBytes(18).toString('base64url'),
    port: 8766,
    // MYRA on this PC (the Antigravity CLI and its "myra" agent).
    pcMyra: true,
    agy: path.join(process.env.LOCALAPPDATA || path.join(HOME, 'AppData', 'Local'), 'agy', 'bin', 'agy.exe'),
    agyAgent: 'myra',
    // Claude Code. It may read, search the web, and edit files inside these folders only.
    claude: path.join(npm, 'node_modules', '@anthropic-ai', 'claude-code', 'bin', 'claude.exe'),
    claudeEdits: true,
    claudeProjects: { scratch: path.join(OUT, 'claude') },
    // Extra tools (pc/extras.mjs). Files: only inside these folders. Moving files and power actions are off until you switch them on.
    fileRoots: [path.join(HOME, 'Desktop'), path.join(HOME, 'Documents'), path.join(HOME, 'Downloads'), OUT],
    fileEdits: false,
    powerActions: false,
  }
  const merged = { ...defaults, ...c }
  if (JSON.stringify(merged) !== JSON.stringify(c)) writeFileSync(CONF_FILE, JSON.stringify(merged, null, 2))
  return merged
}

const CONF = loadConfig()
mkdirSync(OUT, { recursive: true })
for (const p of Object.values(CONF.claudeProjects)) if (p.startsWith(OUT)) mkdirSync(p, { recursive: true })

/* ── helpers ──────────────────────────────────────────────────────────────── */

/** Run a program (never through a shell), feed it stdin, collect stdout. */
function run(file, args, { cwd, input, timeoutMs }) {
  return new Promise((resolve, reject) => {
    const p = spawn(file, args, { cwd, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
    let out = '', err = ''
    const timer = setTimeout(() => { p.kill(); reject(new Error(`Timed out after ${Math.round(timeoutMs / 60000)} min.`)) }, timeoutMs)
    p.stdout.on('data', (d) => { out += d })
    p.stderr.on('data', (d) => { err += d })
    p.on('error', (e) => { clearTimeout(timer); reject(e) })
    p.on('close', (code) => { clearTimeout(timer); resolve({ code, out, err }) })
    if (input != null) p.stdin.end(input)
    else p.stdin.end()
  })
}

const pad = (s) => (s + ':').padEnd(10)

/* ── pc_status ────────────────────────────────────────────────────────────── */

async function cpuPercent(sampleMs = 400) {
  const a = os.cpus()
  await new Promise((r) => setTimeout(r, sampleMs))
  const b = os.cpus()
  let idle = 0, total = 0
  b.forEach((c, i) => {
    const d = (k) => c.times[k] - a[i].times[k]
    idle += d('idle')
    total += d('user') + d('nice') + d('sys') + d('idle') + d('irq')
  })
  return total ? Math.round(100 * (1 - idle / total)) : null
}

const BATTERY = { 1: 'on battery', 2: 'plugged in', 3: 'full', 4: 'low', 5: 'critical', 6: 'charging', 7: 'charging', 8: 'charging', 9: 'charging', 11: 'partly charged' }

async function pcStatus() {
  const [cpu, raw] = await Promise.all([
    cpuPercent(),
    ps(`
$d = @(Get-CimInstance Win32_LogicalDisk -Filter "DriveType=3" | ForEach-Object { [pscustomobject]@{ id = $_.DeviceID; size = [math]::Round($_.Size / 1GB, 1); free = [math]::Round($_.FreeSpace / 1GB, 1) } })
$b = Get-CimInstance Win32_Battery | Select-Object -First 1 | ForEach-Object { [pscustomobject]@{ pct = $_.EstimatedChargeRemaining; status = $_.BatteryStatus } }
$p = @(Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First 6 | ForEach-Object { [pscustomobject]@{ name = $_.ProcessName; mb = [int]($_.WorkingSet64 / 1MB) } })
[pscustomobject]@{ disks = $d; battery = $b; top = $p } | ConvertTo-Json -Depth 4 -Compress`).catch(() => null),
  ])
  const info = raw ? JSON.parse(raw) : {}
  const totalGb = os.totalmem() / 2 ** 30, usedGb = totalGb - os.freemem() / 2 ** 30, up = os.uptime()
  const L = [`PC status · ${os.hostname()}`]
  L.push(`${pad('CPU')}${cpu ?? '?'}%`)
  L.push(`${pad('Memory')}${usedGb.toFixed(1)} of ${totalGb.toFixed(1)} GB used`)
  if (info.battery && info.battery.pct != null) L.push(`${pad('Battery')}${info.battery.pct}%, ${BATTERY[info.battery.status] || 'status unknown'}`)
  for (const d of [].concat(info.disks || [])) L.push(`${pad(`Disk ${String(d.id).replace(/:$/, '')}`)}${d.free} GB free of ${d.size} GB`)
  L.push(`${pad('Uptime')}${Math.floor(up / 3600)} h ${Math.floor((up % 3600) / 60)} min`)
  const top = [].concat(info.top || [])
  if (top.length) L.push(`${pad('Heaviest')}${top.map((p) => `${p.name} ${p.mb} MB`).join(', ')}`)
  return L.join('\n')
}

/* ── pc_open ──────────────────────────────────────────────────────────────── */

// The one tool that launches things, so it refuses - in code, where no web page can argue with
// it - anything that runs commands or reaches into Windows itself.
const WINDIR = path.resolve(process.env.SystemRoot || process.env.windir || 'C:\\Windows').toLowerCase()
const NO_LAUNCH = new Set(['powershell', 'pwsh', 'cmd', 'conhost', 'wt', 'bash', 'wsl', 'wscript', 'cscript', 'mshta', 'rundll32', 'regsvr32', 'msiexec',
  'regedit', 'regedt32', 'reg', 'schtasks', 'sc', 'bcdedit', 'diskpart', 'netsh', 'taskkill', 'format', 'shutdown'])
const SCRIPT_FILE = /\.(bat|cmd|ps1|psm1|vbs|vbe|js|jse|wsf|wsh|hta|msi|scr|com|reg)$/i
const RUNNABLE = /\.(exe|lnk)$/i
const UNTRUSTED_DIR = /\\(downloads|temp|tmp)\\/i
const BAD_SCHEME = new Set(['javascript', 'vbscript', 'data', 'file', 'shell', 'ms-settings', 'ms-msdt', 'search-ms', 'search', 'ms-officecmd'])
const unquote = (s) => String(s || '').trim().replace(/^["']+|["']+$/g, '')
const schemeOf = (s) => { const m = unquote(s).match(/^([a-z][a-z0-9+.-]+):/i); return m ? m[1].toLowerCase() : '' }
const programName = (s) => unquote(s).split(/[\\/]/).pop().replace(/\.exe$/i, '').toLowerCase()
const MENTIONS_WINDOWS = /[a-z]:\\windows(\\|$)|%(windir|systemroot)%|\$env:(windir|systemroot)/i

function insideWindows(s) {
  const v = unquote(s)
  if (!v) return false
  if (MENTIONS_WINDOWS.test(v)) return true
  if (/[\\/]/.test(v) && !schemeOf(v)) {
    const r = path.resolve(v).toLowerCase()
    if (r === WINDIR || r.startsWith(WINDIR + '\\')) return true
  }
  return false
}

function refusal(s) {
  const v = unquote(s)
  if (!v) return ''
  const scheme = schemeOf(v)
  if (BAD_SCHEME.has(scheme)) return `Won't open ${scheme}: links.`
  if (scheme) return ''
  if (NO_LAUNCH.has(programName(v))) return `Won't launch ${programName(v)}: shells and system tools would run commands. Use pc_myra for tasks.`
  if (SCRIPT_FILE.test(v)) return "Won't run script or installer files."
  if (RUNNABLE.test(v) && UNTRUSTED_DIR.test(v)) return 'Refusing to run a program from Downloads or Temp.'
  if (insideWindows(v)) return 'C:\\Windows is off limits.'
  return ''
}

async function pcOpen({ target, args } = {}) {
  const t = String(target || '').trim(), a = String(args || '').trim()
  if (!t) throw new Error('Nothing to open: pass an app name, a link, a file or a folder.')
  if (MENTIONS_WINDOWS.test(a)) throw new Error('C:\\Windows is off limits.')
  for (const piece of [t, ...t.split(/\s+/), ...a.split(/\s+/)]) {
    const why = refusal(piece)
    if (why) throw new Error(why)
  }
  if (process.env.MYRA_DRY_RUN === '1') return `(dry run) would open ${t}${a ? ` ${a}` : ''}`
  // The target travels as an environment variable, never inside the script text.
  await ps(a ? 'Start-Process -FilePath $env:J_TARGET -ArgumentList $env:J_ARGS' : 'Start-Process -FilePath $env:J_TARGET',
    { env: { J_TARGET: t, J_ARGS: a }, timeoutMs: 15_000 })
  return `Opened ${t}${a ? ` ${a}` : ''} on the PC.`
}

/* ── pc_screenshot ────────────────────────────────────────────────────────── */

async function pcScreenshot({ question } = {}) {
  const r = await captureScreen(path.join(OUT, 'screenshots'))
  // The Antigravity CLI doesn't hand images from MCP tools to the model, so the PC looks at the
  // picture itself and answers in words.
  const ask = String(question || '').trim() || 'What is on the screen? Name the apps and what they show.'
  const look = await run(CONF.agy, ['--output-format', 'json', '--model', 'gemini-3.8-flash-low',
    `-p=Open the image file ${r.preview} with view_file and look at it. It is a screenshot of my PC screen. ${ask} Answer in at most two plain sentences, no markdown.`],
  { cwd: SCRATCH, timeoutMs: 3 * 60_000 }).catch((e) => ({ out: '', err: e.message }))
  const line = String(look.out || '').trim().split('\n').reverse().find((l) => l.trim().startsWith('{'))
  let seen = ''
  try { seen = line ? String(JSON.parse(line).response || '').trim() : '' } catch { /* below */ }
  return seen
    ? `Screenshot taken (saved on the PC at ${r.file}). What it shows: ${seen}`
    : `Screenshot saved on the PC at ${r.file}, but I couldn't look at it: ${String(look.err || '').trim().slice(-200) || 'no answer'}.`
}

/* ── pc_lock, pc_media ────────────────────────────────────────────────────── */

const USER32 = `Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class JUser { [DllImport("user32.dll")] public static extern bool LockWorkStation(); [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra); }';`

async function pcLock() {
  if (process.env.MYRA_DRY_RUN === '1') return '(dry run) would lock the PC'
  await ps(USER32 + '[JUser]::LockWorkStation() | Out-Null')
  return 'The PC is locked.'
}

const MEDIA = { play_pause: 0xb3, next: 0xb0, previous: 0xb1, volume_up: 0xaf, volume_down: 0xae, mute: 0xad }

async function pcMedia({ action, steps } = {}) {
  const vk = MEDIA[action]
  if (!vk) throw new Error(`Unknown media action. Use one of: ${Object.keys(MEDIA).join(', ')}.`)
  const n = action.startsWith('volume_') ? Math.max(1, Math.min(25, Math.round(Number(steps) || 5))) : 1
  if (process.env.MYRA_DRY_RUN === '1') return `(dry run) would press ${action} ×${n}`
  await ps(`${USER32} for ($i = 0; $i -lt ${n}; $i++) { [JUser]::keybd_event(${vk}, 0, 0, [UIntPtr]::Zero); [JUser]::keybd_event(${vk}, 0, 2, [UIntPtr]::Zero); Start-Sleep -Milliseconds 40 }`)
  return `Pressed ${action.replace('_', ' ')}${n > 1 ? ` ${n} times` : ''} on the PC.`
}

/* ── pc_myra, claude_code: one heavy job at a time ─────────────────────── */

let busy = null
async function oneAtATime(label, fn) {
  if (busy) throw new Error(`The PC is still busy with ${busy}. Try again when it finishes.`)
  busy = label
  try { return await fn() } finally { busy = null }
}

const VOICE_NOTE = 'This request came by voice from my phone, through MYRA. Keep the answer short: it will be read aloud. ' +
  'In this run do not delete, uninstall, or change system settings; if the task needs that, stop and tell me what you would do instead.\n\nTask: '

async function pcMyra({ task } = {}) {
  if (!CONF.pcMyra) throw new Error('pc_myra is switched off in pc.json.')
  const t = String(task || '').trim()
  if (!t) throw new Error('Say what the task is.')
  if (!existsSync(CONF.agy)) throw new Error(`The Antigravity CLI isn't at ${CONF.agy}.`)
  return oneAtATime('a MYRA task', async () => {
    log('pc_myra:', t.slice(0, 120))
    const r = await run(CONF.agy, ['--agent', CONF.agyAgent, '--output-format', 'json', `-p=${VOICE_NOTE}${t}`], { cwd: SCRATCH, timeoutMs: 15 * 60_000 })
    const line = r.out.trim().split('\n').reverse().find((l) => l.trim().startsWith('{'))
    let j = null
    try { j = line ? JSON.parse(line) : null } catch { /* fall through */ }
    if (!j) throw new Error(`MYRA on the PC gave no answer (exit ${r.code}). ${r.err.trim().slice(-300)}`)
    if (j.status !== 'SUCCESS') throw new Error(`MYRA on the PC stopped: ${j.error || j.status}`)
    return String(j.response || '').trim() || 'Done, with nothing to report.'
  })
}

async function claudeCode({ prompt, project } = {}) {
  const p = String(prompt || '').trim()
  if (!p) throw new Error('Say what to ask Claude Code.')
  const names = Object.keys(CONF.claudeProjects)
  const name = project && CONF.claudeProjects[project] ? project : names[0]
  const cwd = CONF.claudeProjects[name]
  if (!existsSync(cwd)) throw new Error(`Project folder for "${name}" doesn't exist: ${cwd}`)
  if (!existsSync(CONF.claude)) throw new Error(`Claude Code isn't at ${CONF.claude}.`)
  // Read, search and (if allowed) edit. Never Bash: it could run anything.
  const tools = ['WebSearch', 'WebFetch', 'Read', 'Glob', 'Grep', ...(CONF.claudeEdits ? ['Edit', 'Write', 'MultiEdit'] : [])]
  const args = ['-p', '--output-format', 'json', `--allowedTools=${tools.join(',')}`, `--disallowedTools=Bash,PowerShell`]
  if (CONF.claudeEdits) args.push('--permission-mode', 'acceptEdits')
  return oneAtATime('a Claude Code task', async () => {
    log(`claude_code [${name}]:`, p.slice(0, 120))
    const r = await run(CONF.claude, args, { cwd, input: `${p}\n\n(Asked by voice from my phone. Answer briefly in plain sentences: it will be read aloud.)`, timeoutMs: 20 * 60_000 })
    let j = null
    try { j = JSON.parse(r.out.trim().split('\n').pop()) } catch { /* fall through */ }
    if (!j) throw new Error(`Claude Code gave no answer (exit ${r.code}). ${r.err.trim().slice(-300)}`)
    if (j.is_error) throw new Error(`Claude Code stopped: ${j.result || j.subtype}`)
    return `Claude Code (${name}): ${String(j.result || '').trim()}`
  })
}

/* ── MCP ──────────────────────────────────────────────────────────────────── */

const NO_ARGS = { type: 'object', properties: {}, additionalProperties: false }
const TOOLS = [
  { name: 'pc_status', description: "Measure Anuj's Windows PC right now: CPU, memory, battery, disk space, uptime and the heaviest programs.", inputSchema: NO_ARGS },
  {
    name: 'pc_open',
    description: 'Open an app, website, file or folder on the PC. Examples: "chrome", "spotify", "code", "https://youtube.com", "C:\\Users\\sathy\\Downloads". For a web search on the PC, pass a search link such as https://www.google.com/search?q=... Refuses shells, scripts, installers and C:\\Windows.',
    inputSchema: { type: 'object', properties: { target: { type: 'string', description: 'App name, link, file or folder.' }, args: { type: 'string', description: 'Optional arguments, e.g. a link to open in chrome.' } }, required: ['target'], additionalProperties: false },
  },
  {
    name: 'pc_screenshot',
    description: 'Take a screenshot of the PC screen; the PC looks at it and describes it in words. Use it for "what is on my PC screen", "is my download done", and so on. Takes about 20 seconds.',
    inputSchema: { type: 'object', properties: { question: { type: 'string', description: 'What to look for on the screen. Optional.' } }, additionalProperties: false },
  },
  { name: 'pc_lock', description: 'Lock the PC (Windows+L).', inputSchema: NO_ARGS },
  {
    name: 'pc_media',
    description: 'Media keys on the PC: play_pause, next, previous, volume_up, volume_down, mute.',
    inputSchema: { type: 'object', properties: { action: { type: 'string', enum: Object.keys(MEDIA) }, steps: { type: 'number', description: 'For volume: how many steps, 1-25 (each is about 2%).' } }, required: ['action'], additionalProperties: false },
  },
  {
    name: 'pc_myra',
    description: 'Hand a task to MYRA on the PC, which has the PC terminal, files, browser and all his skills. Use it for anything on the PC beyond the other pc_ tools: making or editing files, running a build, checking a project, Kyno status. Takes from seconds to several minutes. Pass the task in one clear sentence or two.',
    inputSchema: { type: 'object', properties: { task: { type: 'string' } }, required: ['task'], additionalProperties: false },
  },
  {
    name: 'claude_code',
    description: `Ask Claude Code on the PC. It can search the web, read code, and ${CONF.claudeEdits ? 'edit files in' : 'read'} the chosen project folder. It cannot run commands. Use it when Anuj says "ask Claude" or "Claude Code", or for deep web research. Takes up to several minutes.`,
    inputSchema: {
      type: 'object',
      properties: { prompt: { type: 'string' }, project: { type: 'string', enum: Object.keys(CONF.claudeProjects), description: 'Which project folder Claude Code works in.' } },
      required: ['prompt'],
      additionalProperties: false,
    },
  },
]
const HANDLERS = { pc_status: pcStatus, pc_open: pcOpen, pc_screenshot: pcScreenshot, pc_lock: pcLock, pc_media: pcMedia, pc_myra: pcMyra, claude_code: claudeCode }
{
  const { EXTRA_TOOLS, EXTRA_HANDLERS } = makeExtras({ CONF, OUT, ps, run: (f, a, o) => run(f, a, { timeoutMs: 15_000, ...o }), log })
  TOOLS.push(...EXTRA_TOOLS)
  Object.assign(HANDLERS, EXTRA_HANDLERS)
}

async function handleRpc(msg) {
  const { id, method, params } = msg || {}
  const isRequest = id !== undefined && id !== null
  const ok = (result) => (isRequest ? { jsonrpc: '2.0', id, result } : null)
  try {
    switch (method) {
      case 'initialize': {
        const want = params?.protocolVersion
        return ok({
          protocolVersion: PROTOCOLS.includes(want) ? want : PROTOCOLS[0],
          capabilities: { tools: { listChanged: false } },
          serverInfo: { name: 'myra-pc', version: VERSION },
          instructions: `Tools for Anuj's Windows PC (${os.hostname()}). They work only while the PC is on and on the same Wi-Fi.`,
        })
      }
      case 'ping': return ok({})
      case 'tools/list': return ok({ tools: TOOLS })
      case 'tools/call': {
        const fn = HANDLERS[params?.name]
        if (!fn) return ok({ content: [{ type: 'text', text: `Unknown tool: ${params?.name}` }], isError: true })
        log('tool', params.name)
        try {
          const r = await fn(params?.arguments || {})
          return ok({ content: Array.isArray(r) ? r : [{ type: 'text', text: String(r) }] })
        } catch (e) {
          log(params.name, 'failed:', e.message)
          return ok({ content: [{ type: 'text', text: `Failed: ${e.message}` }], isError: true })
        }
      }
      default:
        return isRequest ? { jsonrpc: '2.0', id, error: { code: -32601, message: `Method not found: ${method}` } } : null
    }
  } catch (e) {
    return isRequest ? { jsonrpc: '2.0', id, error: { code: -32603, message: e.message } } : null
  }
}

/* ── HTTP ─────────────────────────────────────────────────────────────────── */

function isPrivate(addr) {
  const a = String(addr || '').replace(/^::ffff:/, '')
  if (a === '::1' || a.startsWith('127.')) return true
  if (/^(fe80|fc|fd)/i.test(a)) return true
  const m = a.match(/^(\d+)\.(\d+)\.(\d+)\.(\d+)$/)
  if (!m) return false
  const [x, y] = [Number(m[1]), Number(m[2])]
  return x === 10 || (x === 192 && y === 168) || (x === 172 && y >= 16 && y <= 31) || (x === 100 && y >= 64 && y <= 127)
}

const TOKEN = Buffer.from(CONF.token)
const tokenOk = (t) => { const b = Buffer.from(String(t || '')); return b.length === TOKEN.length && crypto.timingSafeEqual(b, TOKEN) }

function send(res, status, body, headers = {}) {
  const data = body == null ? '' : typeof body === 'string' ? body : JSON.stringify(body)
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...headers })
  res.end(data)
}

const server = http.createServer((req, res) => {
  if (!isPrivate(req.socket.remoteAddress)) { log('refused', req.socket.remoteAddress); return send(res, 403, { error: 'Only devices on this network.' }) }
  const parts = new URL(req.url, 'http://x').pathname.split('/').filter(Boolean)
  const [kind, token] = parts
  if (parts.length !== 2 || !['mcp', 'health'].includes(kind) || !tokenOk(token)) {
    // Same answer for a wrong token and a wrong path, a little late, so guessing gets nowhere.
    return setTimeout(() => send(res, 404, { error: 'Not found' }), 400)
  }
  if (kind === 'health') return send(res, 200, { ok: true, host: os.hostname(), version: VERSION, busy })
  if (req.method !== 'POST') return send(res, 405, { error: 'POST only' }, { Allow: 'POST' })
  let body = ''
  req.setEncoding('utf8')
  req.on('data', (d) => { body += d; if (body.length > 1 << 20) req.destroy() })
  req.on('end', async () => {
    let msg
    try { msg = JSON.parse(body) } catch { return send(res, 400, { jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } }) }
    const batch = Array.isArray(msg)
    const answers = (await Promise.all((batch ? msg : [msg]).map(handleRpc))).filter(Boolean)
    if (!answers.length) return send(res, 202, null)
    send(res, 200, batch ? answers : answers[0])
  })
})

// The phone finds this PC by asking the whole Wi-Fi network "MYRA-PC?" - so it still finds it
// after the router hands the PC a new address. The answer carries only the port, never the token.
const udp = dgram.createSocket({ type: 'udp4', reuseAddr: true })
udp.on('message', (m, rinfo) => {
  if (!isPrivate(rinfo.address) || String(m).trim() !== 'MYRA-PC?') return
  udp.send(`MYRA-PC ${CONF.port}`, rinfo.port, rinfo.address)
})
udp.on('error', (e) => log('discovery off:', e.message))
udp.bind(DISCOVERY_PORT)

const lanIps = () => Object.values(os.networkInterfaces()).flat().filter((n) => n && n.family === 'IPv4' && !n.internal && isPrivate(n.address)).map((n) => n.address)

server.listen(CONF.port, '0.0.0.0', () => {
  const ips = lanIps()
  console.log('')
  console.log('  MYRA PC bridge ' + VERSION + ' is running. Keep this window open.')
  console.log('')
  console.log('  Pair your phone once. In Termux, run:')
  console.log('')
  console.log(`      myra pair ${ips[0] || '<this-PC-IP>'}:${CONF.port} ${CONF.token}`)
  console.log('')
  if (ips.length > 1) console.log(`  (This PC has several addresses: ${ips.join(', ')}. Use the one on your Wi-Fi.)`)
  console.log('  The token is a password for your PC. Never share it or put it on GitHub.')
  console.log(`  Settings: ${CONF_FILE}`)
  console.log('')
})
