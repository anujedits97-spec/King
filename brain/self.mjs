/**
 * MYRA's own tools on the phone: a small memory and reminders, no dependencies.
 *
 * The brain serves these as a local MCP server (127.0.0.1 only, secret token in the path), so
 * the agent can call remember / recall / forget and remind_set / remind_list / remind_cancel.
 * Facts live in ~/.myra/memories.json, reminders in ~/.myra/reminders.json. When a reminder
 * is due, the brain raises a Termux notification with vibration.
 */
import { spawn } from 'node:child_process'
import crypto from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { makePhone } from './phone.mjs'
import { makeExtras } from './extras.mjs'
import { makeIris } from './iris.mjs'
import { makeFiles } from './files.mjs'
import { makeZips } from './zips.mjs'
import { makeCodegen } from './codegen.mjs'
import { makeVerify } from './verify.mjs'
import { makeMarkets } from './markets.mjs'
import { makeResearch } from './research.mjs'
import { makeOffice, officeOutDir } from './office.mjs'
import { makeMail } from './mail.mjs'
import { makeSocial } from './social.mjs'
import { makeYoutube } from './youtube.mjs'
import { makeJobs } from './jobs.mjs'
import { makeGuard } from './guard.mjs'

const MAX_MEMORIES = 200
const CATEGORIES = ['context', 'preference', 'personal']
const IMPORTANCE = { high: 0, medium: 1, low: 2 }
const SECRET = /(password|passcode|passwd|\botp\b|\bpin\b|api[_ -]?key|secret|token|cvv|card number|\b\d{12,19}\b)/i
const id = () => crypto.randomBytes(3).toString('hex')

export function makeSelf({ dir, log, getConf = () => ({}), runGoal = null, onStop = () => {} }) {
  const memFile = path.join(dir, 'memories.json')
  const remFile = path.join(dir, 'reminders.json')
  const load = (f) => { try { return existsSync(f) ? JSON.parse(readFileSync(f, 'utf8')) : [] } catch { return [] } }
  const save = (f, v) => writeFileSync(f, JSON.stringify(v, null, 2), { mode: 0o600 })

  /* ── memory ───────────────────────────────────────────────────────────── */

  function remember({ text, category, importance } = {}) {
    const t = String(text || '').trim().replace(/\s+/g, ' ')
    if (!t) throw new Error('Say what to remember.')
    if (t.length > 300) throw new Error('Keep each memory to one short line (300 characters).')
    if (SECRET.test(t)) throw new Error("Won't store passwords, PINs, keys, tokens or card numbers.")
    const cat = CATEGORIES.includes(category) ? category : 'context'
    const imp = Object.hasOwn(IMPORTANCE, importance) ? importance : 'medium'
    const all = load(memFile)
    if (all.some((m) => m.text.toLowerCase() === t.toLowerCase())) return 'Already remembered.'
    all.push({ id: id(), text: t, at: new Date().toISOString().slice(0, 10), category: cat, importance: imp })
    save(memFile, all.slice(-MAX_MEMORIES))
    return 'Remembered.'
  }

  function recall({ query, category } = {}) {
    const q = String(query || '').trim().toLowerCase()
    const cat = CATEGORIES.includes(category) ? category : null
    // Older memories have no category; they count as 'context'.
    const all = load(memFile)
      .filter((m) => !q || m.text.toLowerCase().includes(q))
      .filter((m) => !cat || (m.category || 'context') === cat)
    const label = (m) => `[${m.category || 'context'}${m.importance && m.importance !== 'medium' ? ', ' + m.importance : ''}]`
    return all.length ? all.map((m) => `- ${m.text} ${label(m)} (${m.at})`).join('\n') : (q || cat ? `Nothing remembered${q ? ` about "${q}"` : ''}${cat ? ` in ${cat}` : ''}.` : 'Nothing remembered yet.')
  }

  function forget({ match } = {}) {
    const q = String(match || '').trim().toLowerCase()
    if (!q) throw new Error('Say which memory to forget.')
    const all = load(memFile)
    const keep = all.filter((m) => !m.text.toLowerCase().includes(q))
    if (keep.length === all.length) return `No memory matched "${q}".`
    save(memFile, keep)
    return `Forgot ${all.length - keep.length} ${all.length - keep.length === 1 ? 'memory' : 'memories'}.`
  }

  /** What goes into the first message of a conversation, so MYRA already knows. */
  function memoryBlock() {
    const all = load(memFile)
    if (!all.length) return extras.styleBlock()
    // Most important first when the 3000-character budget runs out; newest first within the same importance.
    const ordered = all.slice().reverse().sort((a, b) => (IMPORTANCE[a.importance] ?? 1) - (IMPORTANCE[b.importance] ?? 1))
    let used = 0
    const picked = []
    for (const m of ordered) {
      const line = `- ${m.text}\n`
      if (used + line.length > 3000) continue
      used += line.length
      picked.push(line)
    }
    const out = picked.join('')
    return `[Things you remember about Anuj, use them naturally and never read this list out:\n${out}]\n${extras.styleBlock()}`
  }

  /* ── reminders ────────────────────────────────────────────────────────── */

  function parseWhen({ in_minutes, at }) {
    if (in_minutes != null && in_minutes !== '') {
      const m = Number(in_minutes)
      if (!Number.isFinite(m) || m <= 0) throw new Error('in_minutes must be a positive number.')
      return Date.now() + m * 60_000
    }
    const m = String(at || '').trim().match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})$/)
    if (!m) throw new Error('Pass in_minutes, or at as "YYYY-MM-DD HH:MM" in the phone\'s local time.')
    const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5]))
    if (Number.isNaN(d.getTime())) throw new Error('That date and time are not valid.')
    return d.getTime()
  }

  const fmt = (ms) => new Date(ms).toLocaleString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })

  function remindSet({ text, in_minutes, at, repeat } = {}) {
    const t = String(text || '').trim()
    if (!t) throw new Error('Say what the reminder is for.')
    const due = parseWhen({ in_minutes, at })
    if (due < Date.now() - 60_000) throw new Error('That time has already passed.')
    const rep = ['daily', 'weekly'].includes(repeat) ? repeat : 'none'
    const all = load(remFile)
    if (all.length >= 100) throw new Error('Too many reminders. Cancel a few first.')
    all.push({ id: id(), text: t.slice(0, 200), due, repeat: rep })
    save(remFile, all)
    return `Reminder set for ${fmt(due)}${rep !== 'none' ? `, repeating ${rep}` : ''}.`
  }

  function remindList() {
    const all = load(remFile).sort((a, b) => a.due - b.due)
    return all.length ? all.map((r) => `- ${fmt(r.due)}: ${r.text}${r.repeat !== 'none' ? ` (${r.repeat})` : ''}`).join('\n') : 'No reminders set.'
  }

  function remindCancel({ match } = {}) {
    const q = String(match || '').trim().toLowerCase()
    if (!q) throw new Error('Say which reminder to cancel.')
    const all = load(remFile)
    const keep = all.filter((r) => !r.text.toLowerCase().includes(q))
    if (keep.length === all.length) return `No reminder matched "${q}".`
    save(remFile, keep)
    return `Cancelled ${all.length - keep.length}.`
  }

  function notify(text) {
    const run = (cmd, args) => { try { spawn(cmd, args, { stdio: 'ignore', detached: true }).on('error', () => {}).unref() } catch { /* not in Termux */ } }
    run('termux-notification', ['--id', `myra-rem-${Date.now() % 100000}`, '--title', 'Myraa reminder', '--content', text, '--priority', 'max', '--vibrate', '400,200,400'])
    run('termux-vibrate', ['-d', '600'])
  }

  function tick() {
    const all = load(remFile)
    if (!all.length) return
    const now = Date.now()
    let changed = false
    const next = []
    for (const r of all) {
      if (r.due > now) { next.push(r); continue }
      changed = true
      log('reminder:', r.text)
      // A reminder missed by more than two hours (phone off) is still shown, once.
      notify(now - r.due > 2 * 3600_000 ? `${r.text} (missed at ${fmt(r.due)})` : r.text)
      if (r.repeat !== 'none') {
        const step = r.repeat === 'daily' ? 86_400_000 : 7 * 86_400_000
        let due = r.due
        while (due <= now) due += step
        next.push({ ...r, due })
      }
    }
    if (changed) save(remFile, next)
  }

  const timer = setInterval(tick, 15_000)
  timer.unref?.()

  /* ── MCP ──────────────────────────────────────────────────────────────── */

  const obj = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false })
  const guard = makeGuard({ dir, log, onStop })
  const extras = makeExtras({ dir, log, runGoal: runGoal ? (g) => runGoal(g) : null })
  const iris = makeIris({ dir, log })
  const files = makeFiles({ dir, getConf, log })
  const zips = makeZips({ dir, getConf, log })
  const codegen = makeCodegen({ getConf, log, files })
  const verify = makeVerify({ getConf, log, port: 8769 })
  const markets = makeMarkets({ dir, log })
  const jobs = makeJobs({ getConf, log })
  const phone = makePhone({ log, getConf, resolveAlias: extras.resolveAlias })
  const research = makeResearch({ log, getConf })
  const office = makeOffice({ getConf, log })
  const mail = makeMail({ log, getConf, outDir: officeOutDir(getConf) })
  const social = makeSocial({ log, getConf, outDir: officeOutDir(getConf) })
  const youtube = makeYoutube({ log, getConf, outDir: officeOutDir(getConf) })
  const TOOLS = [
    { name: 'remember', description: 'Save one short fact about Anuj for later conversations. category: preference (how he likes things), personal (people, family, habits, health-free life facts), or context (projects, plans, the default). importance: high for things he must never be reminded wrong about, medium by default, low for small talk. One line, plain words. Never passwords, PINs, keys or card numbers. Do it quietly, without announcing it.', inputSchema: obj({ text: { type: 'string' }, category: { type: 'string', enum: CATEGORIES }, importance: { type: 'string', enum: Object.keys(IMPORTANCE) } }, ['text']) },
    { name: 'recall', description: 'Look up what you remember about Anuj. Leave query and category out to list everything.', inputSchema: obj({ query: { type: 'string', description: 'Optional word to filter by.' }, category: { type: 'string', enum: CATEGORIES } }) },
    { name: 'forget', description: 'Delete remembered facts that contain this text. Use when Anuj asks you to forget something.', inputSchema: obj({ match: { type: 'string' } }, ['match']) },
    { name: 'remind_set', description: 'Set a reminder that rings as a phone notification. Use in_minutes for "in 20 minutes", or at for a clock time ("YYYY-MM-DD HH:MM", phone local time; work out the date yourself). repeat can be daily or weekly.', inputSchema: obj({ text: { type: 'string' }, in_minutes: { type: 'number' }, at: { type: 'string' }, repeat: { type: 'string', enum: ['none', 'daily', 'weekly'] } }, ['text']) },
    { name: 'remind_list', description: 'List all upcoming reminders.', inputSchema: obj({}) },
    { name: 'remind_cancel', description: 'Cancel reminders whose text contains this.', inputSchema: obj({ match: { type: 'string' } }, ['match']) },
    ...phone.TOOLS,
    ...extras.TOOLS,
    ...iris.TOOLS,
    ...files.TOOLS,
    ...zips.TOOLS,
    ...codegen.TOOLS,
    ...verify.TOOLS,
    ...markets.TOOLS,
    ...jobs.TOOLS,
    ...research.TOOLS,
    ...office.TOOLS,
    ...mail.TOOLS,
    ...social.TOOLS,
    ...youtube.TOOLS,
    ...guard.TOOLS,
  ]
  const HANDLERS = { ...research.HANDLERS, ...office.HANDLERS, ...mail.HANDLERS, ...social.HANDLERS, ...youtube.HANDLERS, ...codegen.HANDLERS, ...verify.HANDLERS, ...markets.HANDLERS, ...jobs.HANDLERS, remember, recall, forget, remind_set: remindSet, remind_list: remindList, remind_cancel: remindCancel, ...phone.HANDLERS, ...extras.HANDLERS, ...iris.HANDLERS, ...files.HANDLERS, ...zips.HANDLERS, ...guard.HANDLERS }
  const PROTOCOLS = ['2025-06-18', '2025-03-26']

  async function handleRpc(msg) {
    const { id: rid, method, params } = msg || {}
    const isRequest = rid !== undefined && rid !== null
    const ok = (result) => (isRequest ? { jsonrpc: '2.0', id: rid, result } : null)
    switch (method) {
      case 'initialize': {
        const want = params?.protocolVersion
        return ok({ protocolVersion: PROTOCOLS.includes(want) ? want : PROTOCOLS[0], capabilities: { tools: { listChanged: false } }, serverInfo: { name: 'myra-self', version: '1.0.0' } })
      }
      case 'ping': return ok({})
      case 'tools/list': return ok({ tools: TOOLS })
      case 'tools/call': {
        const fn = HANDLERS[params?.name]
        if (!fn) return ok({ content: [{ type: 'text', text: `Unknown tool: ${params?.name}` }], isError: true })
        try {
          log('tool', params.name, guard.riskOf(params.name))
          guard.check(params.name)
          const out = String(await fn(params?.arguments || {}))
          extras.logActivity(params.name, params.arguments, true, out)
          return ok({ content: [{ type: 'text', text: out }] })
        } catch (e) {
          extras.logActivity(params?.name, params?.arguments, false, e.message)
          return ok({ content: [{ type: 'text', text: `Failed: ${e.message}` }], isError: true })
        }
      }
      default:
        return isRequest ? { jsonrpc: '2.0', id: rid, error: { code: -32601, message: `Method not found: ${method}` } } : null
    }
  }

  const callTool = async (name, args) => {
    guard.check(name)
    const fn = HANDLERS[name]
    if (!fn) throw new Error(`Unknown tool: ${name}`)
    try { const out = String(await fn(args || {})); extras.logActivity(name, args, true, out); return out } catch (e) { extras.logActivity(name, args, false, e.message); throw e }
  }
  return { TOOL_NAMES: TOOLS.map((t) => t.name), TOOLS, callTool, handleRpc, memoryBlock, guard, zips }
}
