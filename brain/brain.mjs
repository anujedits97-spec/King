#!/usr/bin/env node
/**
 * MYRA brain - runs in Termux on the phone. No dependencies.
 *
 * It keeps ONE Antigravity CLI (agy) process running with the "myra-phone" agent, signed in
 * with your own Google account (your Google AI Pro plan - no API keys). Each question goes in as
 * one line of stream-json, so there is no start-up wait per question and the conversation
 * carries on.
 *
 * The MYRA app on the phone talks to it at http://127.0.0.1:8765 with a token (see
 * `myra token`). Nothing outside the phone can reach it: it only listens on 127.0.0.1.
 *
 * If you paired a PC (`myra pair`), it checks before each question whether the PC is on, and
 * switches the PC tools on or off to match, so MYRA never pretends the PC is there.
 */
import crypto from 'node:crypto'
import dgram from 'node:dgram'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { Agy } from './agy.mjs'
import { IMAGE_MARK, makeAttachments } from './attachments.mjs'
import { makeSelf } from './self.mjs'
import { makeReply } from './reply.mjs'
import { AI_CATALOG, makeDeviceTools, makeFallback, pingProvider } from './fallback.mjs'
import { keysView, keysSave } from './keys.mjs'

const VERSION = '1.5.0'
const HOME = os.homedir()
const CONF_DIR = process.env.MYRA_CONFIG_DIR || path.join(HOME, '.myra')
const CONF_FILE = path.join(CONF_DIR, 'config.json')
const PLUGIN_DIR = path.join(HOME, '.gemini', 'config', 'plugins', 'myra-phone')
const PC_TOOLS = ['pc_status', 'pc_open', 'pc_screenshot', 'pc_lock', 'pc_media', 'pc_myra', 'claude_code',
  'pc_volume', 'pc_brightness', 'pc_clipboard', 'pc_window', 'pc_files', 'pc_system_info', 'pc_proposal', 'pc_power']

// Tools that run inside the MYRA app itself (android/.../DeviceServer.kt): apps, screen, media.
const DEVICE_TOOLS = ['open_app', 'list_apps', 'open_link', 'youtube_search', 'screen_read', 'screen_look', 'screen_tap', 'screen_type', 'screen_swipe', 'screen_key', 'media_key', 'phone_volume',
  'calendar_read', 'close_app', 'call_control', 'auto_reply',
  'check_battery', 'clipboard_read', 'clipboard_write', 'post_notification', 'launch_camera', 'camera_look', 'live_screen', 'routine', 'web_search', 'deep_research', 'now_playing',
  'compose_sms', 'brightness', 'do_not_disturb', 'send_message']
const DEVICE_PORT = 8769

const stamp = () => new Date().toTimeString().slice(0, 8)
const log = (...a) => console.log(`[${stamp()}]`, ...a)

function loadConfig() {
  mkdirSync(CONF_DIR, { recursive: true })
  let c = {}
  if (existsSync(CONF_FILE)) c = JSON.parse(readFileSync(CONF_FILE, 'utf8'))
  const merged = {
    token: crypto.randomBytes(18).toString('base64url'),
    port: 8765,
    agy: 'agy',
    agent: 'myra-phone',
    model: 'gemini-3.8-flash-low',
    workspace: path.join(HOME, 'myra-workspace'),
    idleResetMinutes: 30,
    pc: null, // { host, port, token } after `myra pair`
    ...c,
  }
  if (JSON.stringify(merged) !== JSON.stringify(c)) saveConfig(merged)
  return merged
}
const saveConfig = (c) => writeFileSync(CONF_FILE, JSON.stringify(c, null, 2), { mode: 0o600 })

let CONF = loadConfig()
mkdirSync(CONF.workspace, { recursive: true })

// Memory and reminders (brain/self.mjs), served to the agent as a local MCP server.
// runGoal: scheduled goals ("every morning, summarise the news") run through ask() below, unattended.
// onStop: an emergency stop kills the running agent straight away.
const self = makeSelf({
  dir: CONF_DIR, log, getConf: () => CONF,
  runGoal: (goal) => ask(`[Scheduled goal. Nobody is listening, so work alone with read-only tools and answer with a short plain summary of the result, which will be shown as a notification. Never call, text, post or delete.]\n${goal}`, () => {}, { unattended: true }),
  onStop: () => { try { agy.stop() } catch {} try { fallback.reset() } catch {} },
})
const guard = self.guard
const attachments = makeAttachments({ getConf: () => CONF, log })
const smartReply = makeReply({ getConf: () => CONF, log })
const fallback = makeFallback({ getConf: () => CONF, self, log, deviceTools: makeDeviceTools({ getConf: () => CONF, port: DEVICE_PORT }) })

/* ── the PC ───────────────────────────────────────────────────────────────── */

async function pcOnline() {
  if (!CONF.pc) return false
  const ctrl = new AbortController()
  const t = setTimeout(() => ctrl.abort(), 1500)
  try {
    const r = await fetch(`http://${CONF.pc.host}:${CONF.pc.port}/health/${CONF.pc.token}`, { signal: ctrl.signal })
    return r.ok
  } catch { return false } finally { clearTimeout(t) }
}

// The MYRA app answers on 127.0.0.1 only while it is running. Same token as this brain.
async function deviceOnline() {
  const ctrl = new AbortController()
  const t = setTimeout(() => ctrl.abort(), 800)
  try {
    const r = await fetch(`http://127.0.0.1:${CONF.devicePort || DEVICE_PORT}/health/${CONF.token}`, { signal: ctrl.signal })
    return r.ok
  } catch { return false } finally { clearTimeout(t) }
}

// The router may give the PC a new address. Ask the network; the PC answers with its port.
let lastDiscovery = 0
function discoverPc() {
  if (!CONF.pc || Date.now() - lastDiscovery < 60_000) return Promise.resolve(null)
  lastDiscovery = Date.now()
  return new Promise((resolve) => {
    const s = dgram.createSocket('udp4')
    const done = (v) => { try { s.close() } catch {} resolve(v) }
    const t = setTimeout(() => done(null), 1500)
    s.on('message', (m, rinfo) => {
      const port = Number(String(m).match(/^MYRA-PC (\d+)/)?.[1])
      if (port) { clearTimeout(t); done({ host: rinfo.address, port }) }
    })
    s.on('error', () => { clearTimeout(t); done(null) })
    s.bind(() => { s.setBroadcast(true); s.send('MYRA-PC?', 8767, '255.255.255.255') })
  })
}

async function checkPc() {
  if (!CONF.pc) return false
  if (await pcOnline()) return true
  const found = await discoverPc()
  if (found && (found.host !== CONF.pc.host || found.port !== CONF.pc.port)) {
    CONF.pc = { ...CONF.pc, ...found }
    saveConfig(CONF)
    log(`PC moved to ${found.host}:${found.port}`)
    return pcOnline()
  }
  return false
}

// agy reads the plugin's MCP servers when it starts, so the file must match reality first.
function writeMcpConfig(withPc, withDev) {
  if (!existsSync(PLUGIN_DIR)) return
  // "eager" puts each PC tool straight into the model's tool list, which saves a lookup step
  // (a few seconds) every time MYRA uses the PC.
  const eager = Object.fromEntries(PC_TOOLS.map((t) => [t, { eager: true }]))
  const eagerSelf = Object.fromEntries(self.TOOL_NAMES.map((t) => [t, { eager: true }]))
  const servers = { self: { url: `http://127.0.0.1:${CONF.port}/mcp/${CONF.token}`, timeoutSeconds: 30, tools: eagerSelf } }
  if (withDev) servers.device = { url: `http://127.0.0.1:${CONF.devicePort || DEVICE_PORT}/mcp/${CONF.token}`, timeoutSeconds: 60, tools: Object.fromEntries(DEVICE_TOOLS.map((t) => [t, { eager: true }])) }
  if (withPc) servers.pc = { url: `http://${CONF.pc.host}:${CONF.pc.port}/mcp/${CONF.pc.token}`, timeoutSeconds: 1200, tools: eager }
  writeFileSync(path.join(PLUGIN_DIR, 'mcp_config.json'), JSON.stringify({ mcpServers: servers }, null, 2))
}

/* ── agy ──────────────────────────────────────────────────────────────────── */

const agy = new Agy({ bin: CONF.agy, agent: CONF.agent, model: CONF.model, extraArgs: CONF.agyArgs, cwd: CONF.workspace, log })
let agyWithPc = false
let agyWithDev = false
let memorySent = false

// agy reads the plugin's MCP servers when it starts, so write them first.
function startAgy(withPc, withDev) {
  writeMcpConfig(withPc, withDev)
  agyWithPc = withPc
  agyWithDev = withDev
  memorySent = false
  log(`PC tools ${withPc ? 'on' : 'off'}, app-control tools ${withDev ? 'on' : 'off'}`)
  agy.start()
}

let queue = Promise.resolve()

function ask(text, onEvent, opts = {}) {
  const job = queue.then(async () => {
    guard.check('ask') // refuses while an emergency stop is on
    guard.setUnattended(!!opts.unattended)
    try { return await think(text, onEvent) } finally { guard.setUnattended(false) }
  })
  queue = job.catch(() => {})
  return job
}

// Pictures the app attached are marked in the question as [[image:/path]]. Only files saved by the
// attach upload are loaded (up to 6 pictures, about 12 MB in all), for AIs that can see.
function attachedImages(text) {
  const out = []
  let total = 0
  for (const m of String(text).matchAll(IMAGE_MARK)) {
    const img = attachments.image(m[1])
    if (!img || out.some((o) => o.path === img.path)) continue
    total += img.data.length
    if (out.length >= 6 || total > 16_000_000) break
    out.push(img)
  }
  return out
}

// agy first; when it fails (no quota, not installed, crashed) the backup brain answers if it is set up.
// agy gets the picture's path in the question and opens it with its own file viewer.
async function think(text, onEvent) {
  const images = attachedImages(text)
  if (fallback.first()) {
    try { return await fallback.ask(text, onEvent, { images }) } catch (e) { log('backup brain failed, trying agy:', e.message) }
  }
  try {
    return await askAgy(text, onEvent)
  } catch (e) {
    if (guard.stopped() || !fallback.enabled() || /too long/.test(e.message)) throw e
    log('agy failed, using the backup brain:', e.message)
    onEvent({ type: 'tool', name: 'backup brain' })
    return fallback.ask(text, onEvent, { images })
  }
}

async function askAgy(text, onEvent) {
  {
    const withPc = await checkPc()
    const withDev = await deviceOnline()
    const idle = agy.lastUsed && Date.now() - agy.lastUsed > CONF.idleResetMinutes * 60_000
    if (!agy.running || withPc !== agyWithPc || withDev !== agyWithDev || idle) startAgy(withPc, withDev)
    let note = ''
    if (!withDev) note += '[Note from the app: the MYRA app is not running, so the app, screen and media tools are not available right now.]\n'
    if (CONF.pc && !withPc) note += '[Note from the app: the PC is off or not on this Wi-Fi right now, so the pc tools are not available.]\n'
    // A new conversation starts by handing MYRA what he remembers.
    let mem = ''
    if (!memorySent) { mem = self.memoryBlock(); memorySent = true }
    return agy.ask(mem + note + text, onEvent)
  }
}

/* ── HTTP for the app ─────────────────────────────────────────────────────── */

const tokenOk = (t) => { const a = Buffer.from(String(t || '')), b = Buffer.from(CONF.token); return a.length === b.length && crypto.timingSafeEqual(a, b) }

function readBody(req) {
  return new Promise((resolve, reject) => {
    let s = ''
    req.setEncoding('utf8')
    req.on('data', (d) => { s += d; if (s.length > 64 << 10) req.destroy() })
    req.on('end', () => { try { resolve(s ? JSON.parse(s) : {}) } catch { reject(new Error('Bad JSON')) } })
    req.on('error', reject)
  })
}

const json = (res, status, body) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)) }

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x')
  // The agent's own tools (memory, reminders): secret token in the path, like the PC bridge.
  const mcp = url.pathname.split('/').filter(Boolean)
  if (mcp[0] === 'mcp') {
    if (mcp.length !== 2 || !tokenOk(mcp[1]) || req.method !== 'POST') return json(res, 404, { error: 'Not found' })
    try {
      const msg = await readBody(req)
      const batch = Array.isArray(msg)
      const answers = (await Promise.all((batch ? msg : [msg]).map((m) => self.handleRpc(m)))).filter(Boolean)
      if (!answers.length) { res.writeHead(202); return res.end() }
      return json(res, 200, batch ? answers : answers[0])
    } catch (e) { return json(res, 400, { error: e.message }) }
  }
  if (!tokenOk(req.headers['x-myra-token'])) return json(res, 401, { error: 'Wrong or missing token. In Termux run: myra token' })
  try {
    if (req.method === 'GET' && url.pathname === '/health') {
      return json(res, 200, { ok: true, version: VERSION, agy: agy.running, busy: agy.busy, model: CONF.model, stopped: guard.stopped(), backup: fallback.enabled() ? CONF.fallback.provider : 'off', pc: CONF.pc ? ((await pcOnline()) ? 'online' : 'offline') : 'not paired' })
    }
    // The app's "AI engine" screen: which provider/model/key the backup brain uses. The key is
    // never sent back to the app, only whether one is saved.
    // The keys and accounts typed into the app's Settings. Secrets are never sent back; only what is set.
    if (url.pathname === '/keys') {
      if (req.method === 'GET') return json(res, 200, keysView(CONF))
      if (req.method === 'POST') {
        const r = keysSave(CONF, await readBody(req))
        if (!r.ok) return json(res, 400, { ok: false, error: r.error })
        saveConfig(CONF)
        log('keys updated from the app')
        return json(res, 200, r.view)
      }
    }
    if (url.pathname === '/ai' || url.pathname === '/ai/test') {
      const fb = CONF.fallback || null
      const view = () => { const f = CONF.fallback || null; return { ok: true, provider: f?.provider || 'off', model: f?.model || '', baseUrl: f?.baseUrl || '', hasKey: !!f?.apiKey, fallbackFirst: !!CONF.fallbackFirst, catalog: AI_CATALOG } }
      if (req.method === 'GET' && url.pathname === '/ai') return json(res, 200, view())
      if (req.method === 'POST') {
        const b = await readBody(req)
        const provider = String(b.provider || '').trim().toLowerCase()
        if (provider !== 'off' && !AI_CATALOG.some((p) => p.id === provider)) return json(res, 400, { ok: false, error: `Unknown provider "${provider}".` })
        // Blank key = keep the saved one (only when the provider hasn't changed).
        const next = provider === 'off' ? null : {
          provider,
          ...(String(b.apiKey || '').trim() ? { apiKey: String(b.apiKey).trim() } : (fb?.provider === provider && fb.apiKey ? { apiKey: fb.apiKey } : {})),
          ...(String(b.model || '').trim() ? { model: String(b.model).trim() } : {}),
          ...(String(b.baseUrl || '').trim() ? { baseUrl: String(b.baseUrl).trim() } : {}),
        }
        if (url.pathname === '/ai/test') {
          if (!next) return json(res, 200, { ok: false, error: 'Nothing to test: the backup brain is off.' })
          try { const r = await pingProvider(next); return json(res, 200, { ok: true, model: r.model, reply: r.reply }) } catch (e) { return json(res, 200, { ok: false, error: e.message }) }
        }
        CONF.fallback = next
        if (typeof b.fallbackFirst === 'boolean') CONF.fallbackFirst = b.fallbackFirst
        if (!next) CONF.fallbackFirst = false
        saveConfig(CONF)
        fallback.reset()
        log('AI engine set from the app:', next ? `${next.provider} ${next.model || '(default)'}` : 'off')
        return json(res, 200, view())
      }
    }
    // A zip from the app (the attach button): unpacked into a private working copy for the zip tools.
    if (req.method === 'POST' && url.pathname === '/upload') {
      try {
        self.guard.check('zip_upload')
        const r = await self.zips.ingest(req, url.searchParams.get('name') || 'upload.zip')
        return json(res, 200, { ok: true, ...r })
      } catch (e) { return json(res, 400, { ok: false, error: e.message }) }
    }
    // Any other file from the app (the attach button): text pulled out of Word / Excel / PowerPoint / ODF /
    // EPUB / RTF / HTML / notebooks, or a picture saved for the AI to look at. See attachments.mjs.
    if (req.method === 'POST' && url.pathname === '/attach') {
      try {
        self.guard.check('attach_upload')
        const r = await attachments.ingest(req, url.searchParams.get('name') || 'file')
        log(`attached ${r.kind}${r.format ? `/${r.format}` : ''}: ${r.name} (${r.bytes} bytes)`)
        return json(res, 200, { ok: true, ...r })
      } catch (e) { return json(res, 400, { ok: false, error: e.message }) }
    }
    // Emergency stop and resume, for a button in the app or `curl`.
    if (req.method === 'POST' && url.pathname === '/stop') { guard.stop('http'); return json(res, 200, { ok: true, stopped: true }) }
    if (req.method === 'POST' && url.pathname === '/resume') { guard.resume(); return json(res, 200, { ok: true, stopped: false }) }
    if (req.method === 'POST' && url.pathname === '/reset') {
      fallback.reset()
      agy.stop()
      log('new conversation')
      return json(res, 200, { ok: true })
    }
    if (req.method === 'POST' && url.pathname === '/reply') {
      let body
      try { body = await readBody(req) } catch { return json(res, 400, { error: 'Bad JSON' }) }
      try { return json(res, 200, await smartReply(body)) } catch (e) { return json(res, e.status || 502, { error: e.message }) }
    }
    if (req.method === 'POST' && url.pathname === '/ask') {
      const { text } = await readBody(req)
      const q = String(text || '').trim()
      if (!q) return json(res, 400, { error: 'No text' })
      log('>', q.slice(0, 160))
      // Newline-delimited JSON, so the app can show what MYRA is doing while it works.
      res.writeHead(200, { 'Content-Type': 'application/x-ndjson', 'Cache-Control': 'no-store' })
      const out = (o) => { try { res.write(JSON.stringify(o) + '\n') } catch {} }
      out({ type: 'thinking' })
      try {
        const reply = await ask(q, (ev) => { log('  tool', ev.name); out(ev) })
        log('<', reply.slice(0, 160))
        out({ type: 'reply', text: reply })
      } catch (e) {
        log('error:', e.message)
        out({ type: 'error', message: e.message })
      }
      return res.end()
    }
    json(res, 404, { error: 'Not found' })
  } catch (e) {
    json(res, 500, { error: e.message })
  }
})

server.listen(CONF.port, process.env.MYRA_BRAIN_HOST || '127.0.0.1', () => {
  log(`MYRA brain ${VERSION} on http://127.0.0.1:${CONF.port} (agent ${CONF.agent})`)
  // Warm up now so the first question doesn't wait for agy to start.
  Promise.all([checkPc(), deviceOnline()]).then(([withPc, withDev]) => { if (!agy.running) startAgy(withPc, withDev) })
})

const shutdown = () => { agy.stop(); process.exit(0) }
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
