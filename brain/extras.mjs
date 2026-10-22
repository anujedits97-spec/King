/**
 * Extra phone tools for MYRA, ported from the PersonalAIAgent project:
 * alarms and timers, calendar events, location, navigation, device info,
 * contact nicknames ("Mom"), simple automations and an activity log.
 *
 * Same rules as phone.mjs: no shell strings, every command and argument is built here, and
 * everything runs through Termux (`am` and the Termux:API app). Nothing here sends a message or
 * places a call. Alarms, timers and calendar entries only touch the phone's own clock and calendar.
 */
import { execFile, spawn } from 'node:child_process'
import crypto from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { makeGate } from './confirm.mjs'

const id = () => crypto.randomBytes(3).toString('hex')
const clampInt = (v, lo, hi) => Math.max(lo, Math.min(hi, Math.round(Number(v))))

export function makeExtras({ dir, log, runGoal = null }) {
  const gate = makeGate()
  const aliasFile = path.join(dir, 'aliases.json')
  const autoFile = path.join(dir, 'automations.json')
  const actFile = path.join(dir, 'activity.json')
  const styleFile = path.join(dir, 'style.json')
  const load = (f, empty) => { try { return existsSync(f) ? JSON.parse(readFileSync(f, 'utf8')) : empty } catch { return empty } }
  const save = (f, v) => writeFileSync(f, JSON.stringify(v, null, 2), { mode: 0o600 })

  function run(cmd, args = [], timeoutMs = 15_000) {
    return new Promise((resolve, reject) => {
      execFile(cmd, args, { timeout: timeoutMs, maxBuffer: 2 << 20, encoding: 'utf8' }, (err, stdout, stderr) => {
        if (err) {
          if (err.code === 'ENOENT') return reject(new Error(`${cmd} is not available. Run this inside Termux${cmd.startsWith('termux-') ? ' with the Termux:API app installed' : ''}.`))
          return reject(new Error(String(stderr || err.message).trim().slice(0, 300) || `${cmd} failed`))
        }
        resolve(String(stdout))
      })
    })
  }
  const fire = (cmd, args) => { try { spawn(cmd, args, { stdio: 'ignore', detached: true }).on('error', () => {}).unref() } catch { /* not in Termux */ } }
  const am = (args) => run('am', ['start', ...args])

  /* ── activity log ─────────────────────────────────────────────────────── */

  const SILENT = new Set(['activity_log', 'recall', 'remind_list', 'alias_list', 'automation_list'])
  function logActivity(tool, args, ok, note) {
    if (SILENT.has(tool)) return
    try {
      const all = load(actFile, [])
      const who = args && (args.to || args.name || args.destination || args.title || args.text || args.match || args.app)
      all.push({ at: new Date().toISOString(), tool, who: who ? String(who).slice(0, 60) : '', ok: !!ok, note: String(note || '').slice(0, 80) })
      save(actFile, all.slice(-200))
    } catch { /* never let logging break a tool */ }
  }

  function activityLog({ limit } = {}) {
    const n = clampInt(limit || 10, 1, 30)
    const all = load(actFile, []).slice(-n).reverse()
    if (!all.length) return 'Nothing has been logged yet.'
    return all.map((a) => `${a.at.slice(0, 16).replace('T', ' ')} ${a.ok ? 'ok' : 'failed'} ${a.tool}${a.who ? ` (${a.who})` : ''}${a.note ? `: ${a.note}` : ''}`).join('\n')
  }

  /* ── contact nicknames ────────────────────────────────────────────────── */

  const aliases = () => load(aliasFile, {})

  /** "Mom" -> the contact name or number it points to, or null. Used by phone.mjs. */
  function resolveAlias(name) {
    const hit = aliases()[String(name || '').trim().toLowerCase()]
    return hit || null
  }

  function aliasSet({ alias, target } = {}) {
    const a = String(alias || '').trim().toLowerCase()
    const t = String(target || '').trim()
    if (!a || a.length > 30) throw new Error('Say the nickname, like "mom" or "boss" (up to 30 letters).')
    if (!t || t.length > 40) throw new Error('Say which contact name or phone number the nickname means.')
    const all = aliases()
    if (Object.keys(all).length >= 50 && !all[a]) throw new Error('Too many nicknames. Remove a few first.')
    all[a] = t
    save(aliasFile, all)
    return `Okay, "${a}" now means ${t}.`
  }

  function aliasList() {
    const all = Object.entries(aliases())
    return all.length ? all.map(([a, t]) => `${a}: ${t}`).join('\n') : 'No nicknames set.'
  }

  function aliasRemove({ alias } = {}) {
    const a = String(alias || '').trim().toLowerCase()
    const all = aliases()
    if (!all[a]) return `No nickname "${a}".`
    delete all[a]
    save(aliasFile, all)
    return `Removed "${a}".`
  }

  /* ── alarm, timer, calendar ───────────────────────────────────────────── */

  async function alarmSet({ hour, minute, label } = {}) {
    const h = Number(hour), m = Number(minute ?? 0)
    if (!Number.isInteger(h) || h < 0 || h > 23) throw new Error('hour must be 0 to 23 (24-hour clock).')
    if (!Number.isInteger(m) || m < 0 || m > 59) throw new Error('minute must be 0 to 59.')
    const text = String(label || 'Myraa alarm').slice(0, 60)
    await am(['-a', 'android.intent.action.SET_ALARM', '--ei', 'android.intent.extra.alarm.HOUR', String(h), '--ei', 'android.intent.extra.alarm.MINUTES', String(m),
      '--es', 'android.intent.extra.alarm.MESSAGE', text, '--ez', 'android.intent.extra.alarm.SKIP_UI', 'true'])
    return `Alarm set for ${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}${label ? ` (${text})` : ''}.`
  }

  async function timerSet({ minutes, seconds, label } = {}) {
    const total = clampInt((Number(minutes) || 0) * 60 + (Number(seconds) || 0), 1, 24 * 3600)
    const text = String(label || 'Myraa timer').slice(0, 60)
    await am(['-a', 'android.intent.action.SET_TIMER', '--ei', 'android.intent.extra.alarm.LENGTH', String(total),
      '--es', 'android.intent.extra.alarm.MESSAGE', text, '--ez', 'android.intent.extra.alarm.SKIP_UI', 'true'])
    const mm = Math.floor(total / 60), ss = total % 60
    return `Timer started for ${mm ? `${mm} minute${mm === 1 ? '' : 's'}` : ''}${mm && ss ? ' ' : ''}${ss ? `${ss} seconds` : ''}.`
  }

  async function calendarAdd({ title, at, in_minutes, in_hours, duration_minutes, location, notes } = {}) {
    const t = String(title || '').trim()
    if (!t) throw new Error('Say what the event is called.')
    let start
    const rel = (Number(in_hours) || 0) * 60 + (Number(in_minutes) || 0)
    if (!String(at || '').trim() && rel > 0) {
      // "2 ghante baad": counted from now, so the model does not have to work out the date.
      if (rel > 366 * 24 * 60) throw new Error('That is too far ahead. Use at with a date instead.')
      start = new Date(Date.now() + Math.round(rel) * 60_000)
    } else {
      const m = String(at || '').trim().match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})$/)
      if (!m) throw new Error('Pass at as "YYYY-MM-DD HH:MM" in the phone\'s local time, or in_hours / in_minutes for "2 hours from now".')
      start = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5]))
    }
    if (Number.isNaN(start.getTime())) throw new Error('That date and time are not valid.')
    const end = start.getTime() + clampInt(duration_minutes || 60, 5, 24 * 60) * 60_000
    const args = ['-a', 'android.intent.action.INSERT', '-d', 'content://com.android.calendar/events',
      '--es', 'title', t.slice(0, 120), '--el', 'beginTime', String(start.getTime()), '--el', 'endTime', String(end)]
    if (location) args.push('--es', 'eventLocation', String(location).slice(0, 120))
    if (notes) args.push('--es', 'description', String(notes).slice(0, 400))
    await am(args)
    return `Opened the calendar with "${t}" filled in. Anuj needs to tap Save himself.`
  }

  /* ── location, navigation, device info ────────────────────────────────── */

  async function locationGet() {
    let raw
    try { raw = await run('termux-location', ['-p', 'network', '-r', 'once'], 25_000) } catch (e) {
      if (/not available/.test(e.message)) throw e
      raw = await run('termux-location', ['-p', 'gps', '-r', 'once'], 40_000)
    }
    let j
    try { j = JSON.parse(raw) } catch { throw new Error('No location came back. Switch on location and give Termux:API the Location permission.') }
    if (typeof j.latitude !== 'number') throw new Error('No location fix yet. Switch on location and try again.')
    return `Latitude ${j.latitude.toFixed(5)}, longitude ${j.longitude.toFixed(5)}${j.accuracy ? `, accurate to about ${Math.round(j.accuracy)} metres` : ''}. Map: https://maps.google.com/?q=${j.latitude},${j.longitude}`
  }

  async function navigate({ destination, mode } = {}) {
    const d = String(destination || '').trim()
    if (!d) throw new Error('Say where to go.')
    const modes = { driving: 'd', walking: 'w', bicycling: 'b', two_wheeler: 'l' }
    const code = modes[mode] || 'd'
    try {
      await am(['-a', 'android.intent.action.VIEW', '-d', `google.navigation:q=${encodeURIComponent(d)}&mode=${code}`])
    } catch {
      await am(['-a', 'android.intent.action.VIEW', '-d', `geo:0,0?q=${encodeURIComponent(d)}`])
    }
    return `Opened Maps for ${d}. Anuj needs to tap Start if navigation doesn't begin by itself.`
  }

  async function deviceInfo() {
    const parts = [`Time: ${new Date().toLocaleString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', hour: 'numeric', minute: '2-digit' })}`]
    try {
      const b = JSON.parse(await run('termux-battery-status'))
      parts.push(`Battery: ${b.percentage}%, ${String(b.status || '').toLowerCase()}${b.temperature ? `, ${Math.round(b.temperature)}°C` : ''}`)
    } catch { /* optional */ }
    try {
      const w = JSON.parse(await run('termux-wifi-connectioninfo'))
      if (w.ssid && w.supplicant_state === 'COMPLETED') parts.push(`Wi-Fi: ${String(w.ssid).replace(/"/g, '')}`)
    } catch { /* optional */ }
    try {
      const model = (await run('getprop', ['ro.product.model'])).trim()
      const ver = (await run('getprop', ['ro.build.version.release'])).trim()
      if (model) parts.push(`Phone: ${model}, Android ${ver}`)
    } catch { /* optional */ }
    return parts.join('\n')
  }

  /* ── automations ──────────────────────────────────────────────────────── */

  // Triggers: battery_below (level), charging_started, charging_stopped, daily (time "HH:MM").
  // Actions: a notification, opening a web link, or run_goal: MYRA works on a goal by himself and
  // sends you the result as a notification. A scheduled goal runs unattended, so tools that reach
  // other people or delete things are refused during it (see guard.mjs).
  const TRIGGERS = ['battery_below', 'charging_started', 'charging_stopped', 'daily', 'every_minutes']
  const ACTIONS = ['notify', 'open_url', 'run_goal']

  function automationAdd({ name, trigger, level, time, minutes, action, text, url, goal, confirm_token } = {}) {
    const n = String(name || '').trim().slice(0, 60)
    if (!n) throw new Error('Give the automation a short name.')
    if (!TRIGGERS.includes(trigger)) throw new Error(`trigger must be one of: ${TRIGGERS.join(', ')}.`)
    if (!ACTIONS.includes(action)) throw new Error(`action must be one of: ${ACTIONS.join(', ')}.`)
    const rule = { id: id(), name: n, trigger, action, enabled: true, last: 0, armed: true }
    if (trigger === 'battery_below') {
      const l = Number(level)
      if (!Number.isFinite(l) || l < 1 || l > 99) throw new Error('level must be 1 to 99.')
      rule.level = Math.round(l)
    }
    if (trigger === 'daily') {
      if (!/^([01]?\d|2[0-3]):[0-5]\d$/.test(String(time || ''))) throw new Error('time must look like "07:30" (24-hour clock).')
      rule.time = String(time).padStart(5, '0')
    }
    if (trigger === 'every_minutes') {
      const m = Number(minutes)
      if (!Number.isFinite(m) || m < 15 || m > 1440) throw new Error('minutes must be 15 to 1440.')
      rule.minutes = Math.round(m)
    }
    if (action === 'run_goal') {
      const g = String(goal || '').trim()
      if (!g) throw new Error('Say what the goal is, like "search the top news and summarise it".')
      rule.goal = g.slice(0, 300)
      const ask = gate('automation_add', [n, trigger, rule.goal, rule.level, rule.time, rule.minutes], confirm_token, `every time "${n}" fires, I will work on this by myself and send you the result: "${rule.goal}". Shall I set it up?`)
      if (ask) return ask
    } else if (action === 'notify') {
      const t = String(text || '').trim()
      if (!t) throw new Error('Say what the notification should say.')
      rule.text = t.slice(0, 200)
    } else {
      let u
      try { u = new URL(String(url || '')) } catch { throw new Error('Pass a full web link for url.') }
      if (!/^https?:$/.test(u.protocol)) throw new Error('Only http and https links are allowed.')
      rule.url = u.toString()
    }
    const all = load(autoFile, [])
    if (all.length >= 20) throw new Error('Too many automations. Remove a few first.')
    all.push(rule)
    save(autoFile, all)
    return `Automation "${n}" is on (id ${rule.id}).`
  }

  function describe(r) {
    const when = r.trigger === 'battery_below' ? `battery below ${r.level}%` : r.trigger === 'daily' ? `every day at ${r.time}` : r.trigger === 'every_minutes' ? `every ${r.minutes} minutes` : r.trigger.replace('_', ' ')
    const what = r.action === 'notify' ? `notify "${r.text}"` : r.action === 'run_goal' ? `work on "${r.goal}"` : `open ${r.url}`
    return `${r.id} ${r.enabled ? 'on ' : 'off'} ${r.name}: when ${when}, ${what}`
  }

  const automationList = () => { const all = load(autoFile, []); return all.length ? all.map(describe).join('\n') : 'No automations set.' }

  function automationSwitch({ match, enabled } = {}) {
    const q = String(match || '').trim().toLowerCase()
    if (!q) throw new Error('Say which automation (its name or id).')
    const all = load(autoFile, [])
    const hits = all.filter((r) => r.id === q || r.name.toLowerCase().includes(q))
    if (!hits.length) return `No automation matched "${q}".`
    for (const r of hits) { r.enabled = enabled !== false; r.armed = true }
    save(autoFile, all)
    return `${enabled !== false ? 'Switched on' : 'Switched off'} ${hits.length}.`
  }

  function automationRemove({ match } = {}) {
    const q = String(match || '').trim().toLowerCase()
    if (!q) throw new Error('Say which automation (its name or id).')
    const all = load(autoFile, [])
    const keep = all.filter((r) => !(r.id === q || r.name.toLowerCase().includes(q)))
    if (keep.length === all.length) return `No automation matched "${q}".`
    save(autoFile, keep)
    return `Removed ${all.length - keep.length}.`
  }

  function doAction(r) {
    log('automation:', r.name)
    if (r.action === 'notify') {
      fire('termux-notification', ['--id', `myra-auto-${r.id}`, '--title', 'Myraa', '--content', r.text, '--priority', 'high', '--vibrate', '300,150,300'])
    } else if (r.action === 'run_goal') {
      if (!runGoal) { logActivity('automation', { name: r.name }, false, 'no brain'); return }
      runGoal(r.goal).then((reply) => {
        fire('termux-notification', ['--id', `myra-goal-${r.id}`, '--title', `Myraa: ${r.name}`, '--content', String(reply || 'Done.').slice(0, 600), '--priority', 'high', '--vibrate', '300,150,300'])
        logActivity('automation', { name: r.name }, true, 'goal done')
      }).catch((e) => logActivity('automation', { name: r.name }, false, e.message))
      return
    } else {
      fire('am', ['start', '-a', 'android.intent.action.VIEW', '-d', r.url])
    }
    logActivity('automation', { name: r.name }, true, r.action)
  }

  let prevCharging = null
  async function autoTick() {
    const all = load(autoFile, [])
    const active = all.filter((r) => r.enabled)
    if (!active.length) return
    const now = new Date()
    const hhmm = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
    let battery = null
    if (active.some((r) => r.trigger.startsWith('battery') || r.trigger.startsWith('charging'))) {
      try { battery = JSON.parse(await run('termux-battery-status', [], 8000)) } catch { /* no Termux:API */ }
    }
    const charging = battery ? ['CHARGING', 'FULL'].includes(String(battery.status).toUpperCase()) || battery.plugged !== 'UNPLUGGED' && !!battery.plugged : null
    let changed = false
    for (const r of active) {
      let fireNow = false
      if (r.trigger === 'daily') {
        if (r.time === hhmm && Date.now() - r.last > 90_000) fireNow = true
      } else if (r.trigger === 'every_minutes') {
        if (!r.last) { r.last = Date.now(); changed = true } // first run one interval from now
        else if (Date.now() - r.last >= r.minutes * 60_000) fireNow = true
      } else if (r.trigger === 'battery_below' && battery) {
        // Fires once when the level drops below, and re-arms when it is back above (or charging).
        const low = battery.percentage < r.level && !charging
        if (low && r.armed) { fireNow = true; r.armed = false; changed = true }
        if (!low && !r.armed) { r.armed = true; changed = true }
      } else if ((r.trigger === 'charging_started' || r.trigger === 'charging_stopped') && charging !== null && prevCharging !== null && charging !== prevCharging) {
        fireNow = r.trigger === 'charging_started' ? charging : !charging
      }
      if (fireNow) { r.last = Date.now(); changed = true; doAction(r) }
    }
    if (charging !== null) prevCharging = charging
    if (changed) save(autoFile, all)
  }
  const timer = setInterval(() => { autoTick().catch(() => {}) }, 30_000)
  timer.unref?.()

  /* ── style: personality and speaking speed ───────────────────────────── */

  const PERSONALITIES = {
    sweet: 'Your usual self: Myraa, sweet, shy, warm, Hinglish.',
    friendly: 'Friendly and relaxed Hinglish, like a good friend. Less shy, a bit more chatty and playful, still soft.',
    professional: 'Professional and precise. Calm, polite, clear, to the point. No cute filler, no "hehe". English or simple Hinglish.',
    venom: 'Blunt, sharp and sarcastic, like a friend who roasts him for fun. Teasing is fine; real insults, slurs, cruelty about his family or looks are not. Short, witty Hinglish.',
  }
  const SPEEDS = {
    fast: 'Keep every reply as short as possible: one short sentence unless he asks for more.',
    normal: 'Normal length: one or two short sentences.',
    slow: 'Take your time and explain a little more, still in short spoken sentences.',
  }
  const style = () => ({ personality: 'sweet', speed: 'normal', ...load(styleFile, {}) })

  function styleBlock() {
    const s = style()
    if (s.personality === 'sweet' && s.speed === 'normal') return ''
    return `[Style Anuj chose for this chat. Personality: ${PERSONALITIES[s.personality] || PERSONALITIES.sweet} Length: ${SPEEDS[s.speed] || SPEEDS.normal}]\n`
  }

  function styleSet({ personality, speed } = {}) {
    const s = style()
    if (personality) { if (!PERSONALITIES[personality]) throw new Error(`personality must be one of: ${Object.keys(PERSONALITIES).join(', ')}.`); s.personality = personality }
    if (speed) { if (!SPEEDS[speed]) throw new Error(`speed must be one of: ${Object.keys(SPEEDS).join(', ')}.`); s.speed = speed }
    if (!personality && !speed) throw new Error('Say a personality or a speed.')
    save(styleFile, s)
    return `Saved: personality ${s.personality}, replies ${s.speed}. Start talking in that style right now. ${PERSONALITIES[s.personality]} ${SPEEDS[s.speed]}`
  }

  const styleGet = () => { const s = style(); return `Personality: ${s.personality}. Replies: ${s.speed}. Options: personality ${Object.keys(PERSONALITIES).join(' / ')}; speed ${Object.keys(SPEEDS).join(' / ')}.` }

  /* ── tool list ────────────────────────────────────────────────────────── */

  const obj = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false })
  const str = (description) => ({ type: 'string', description })
  const TOOLS = [
    { name: 'alarm_set', description: 'Set a real alarm in the phone clock app. hour is 0-23 (24-hour clock), minute 0-59. Confirm it back in a few words.', inputSchema: obj({ hour: { type: 'number' }, minute: { type: 'number' }, label: str('Optional name for the alarm.') }, ['hour']) },
    { name: 'timer_set', description: 'Start a countdown timer in the phone clock app, e.g. "10 minute ka timer".', inputSchema: obj({ minutes: { type: 'number' }, seconds: { type: 'number' }, label: str('Optional name.') }) },
    { name: 'calendar_event_add', description: 'Open the phone calendar with a new event filled in; Anuj taps Save himself. Give either at (\"YYYY-MM-DD HH:MM\" in phone local time, work out the date yourself) or, for \"2 ghante baad\" / \"30 minute mein\", in_hours and/or in_minutes counted from now.', inputSchema: obj({ title: { type: 'string' }, at: { type: 'string' }, in_hours: { type: 'number', description: 'Hours from now, instead of at.' }, in_minutes: { type: 'number', description: 'Minutes from now, instead of at.' }, duration_minutes: { type: 'number', description: 'Default 60.' }, location: { type: 'string' }, notes: { type: 'string' } }, ['title']) },
    { name: 'location_get', description: "Get the phone's current location (coordinates and a map link). Needs Location on and the Termux:API permission.", inputSchema: obj({}) },
    { name: 'navigate', description: 'Open Google Maps with directions to a place. mode: driving (default), walking, bicycling or two_wheeler.', inputSchema: obj({ destination: { type: 'string' }, mode: { type: 'string', enum: ['driving', 'walking', 'bicycling', 'two_wheeler'] } }, ['destination']) },
    { name: 'device_info', description: 'Current time and date, battery level and charging state, Wi-Fi name, phone model and Android version.', inputSchema: obj({}) },
    { name: 'alias_set', description: 'Teach MYRA a nickname for a contact, e.g. "mom" -> "Maa" (a contact name) or a phone number. Then "Mom ko call karo" works for calls, texts and chats. Use when Anuj says "X means Y" about a person.', inputSchema: obj({ alias: { type: 'string' }, target: str('A contact name or a phone number.') }, ['alias', 'target']) },
    { name: 'alias_list', description: 'List the contact nicknames.', inputSchema: obj({}) },
    { name: 'alias_remove', description: 'Remove a contact nickname.', inputSchema: obj({ alias: { type: 'string' } }, ['alias']) },
    { name: 'automation_add', description: 'Create an automatic rule that runs by itself while MYRA is running. trigger: battery_below (needs level), charging_started, charging_stopped, daily (needs time "HH:MM") or every_minutes (needs minutes, 15 or more). action: notify (needs text), open_url (needs an http(s) url) or run_goal (needs goal: you work on it by yourself and notify Anuj with the result; read-only, can never call, text or delete; two steps with a confirm_token like sending). Ask once before creating one.', inputSchema: obj({ name: { type: 'string' }, trigger: { type: 'string', enum: TRIGGERS }, level: { type: 'number' }, time: { type: 'string' }, minutes: { type: 'number' }, action: { type: 'string', enum: ACTIONS }, text: { type: 'string' }, url: { type: 'string' }, goal: { type: 'string' }, confirm_token: { type: 'string' } }, ['name', 'trigger', 'action']) },
    { name: 'automation_list', description: 'List the automatic rules.', inputSchema: obj({}) },
    { name: 'automation_switch', description: 'Switch an automation on or off by name or id.', inputSchema: obj({ match: { type: 'string' }, enabled: { type: 'boolean' } }, ['match', 'enabled']) },
    { name: 'automation_remove', description: 'Delete an automation by name or id.', inputSchema: obj({ match: { type: 'string' } }, ['match']) },
    { name: 'style_set', description: 'Change how you talk, and remember it for next time. personality: sweet (default Myraa), friendly, professional or venom (blunt and sarcastic, playful only). speed: fast (very short replies), normal or slow (more explanation). Use when Anuj says "professional mode", "thoda friendly ho ja", "jaldi jaldi bol", "dheere samjhao".', inputSchema: obj({ personality: { type: 'string', enum: Object.keys(PERSONALITIES) }, speed: { type: 'string', enum: Object.keys(SPEEDS) } }) },
    { name: 'style_get', description: 'Show the current personality and speed.', inputSchema: obj({}) },
    { name: 'activity_log', description: 'Show what MYRA did recently with the tools (calls, texts, alarms, automations). Use when Anuj asks "tune kya kya kiya".', inputSchema: obj({ limit: { type: 'number', description: '1-30. Default 10.' } }) },
  ]
  const HANDLERS = {
    alarm_set: alarmSet, timer_set: timerSet, calendar_event_add: calendarAdd, location_get: locationGet, navigate, device_info: deviceInfo,
    alias_set: aliasSet, alias_list: aliasList, alias_remove: aliasRemove,
    automation_add: automationAdd, automation_list: automationList, automation_switch: automationSwitch, automation_remove: automationRemove,
    activity_log: activityLog, style_set: styleSet, style_get: styleGet,
  }
  return { TOOLS, HANDLERS, resolveAlias, logActivity, styleBlock }
}
