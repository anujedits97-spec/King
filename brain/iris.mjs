/**
 * Tools that the IRIS-MX app lists (flashlight, settings panels, notes) and MYRA did not have.
 * Written fresh for MYRA from IRIS-MX's public feature list; no IRIS-MX code is used here.
 *
 * Same rules as extras.mjs and phone.mjs: no shell strings, every command and argument is built
 * here, and everything runs through Termux (`am` and the Termux:API app).
 *
 *  - torch:         the flashlight, on or off (Termux:API).
 *  - settings_open: opens a Settings PANEL (Wi-Fi, Bluetooth, location, hotspot ...) from a fixed
 *                   list. It only opens the screen. Anuj flips the switch himself, so nothing in
 *                   system settings is changed behind his back.
 *  - note_*:        a small notes list (IRIS-MX has a Notes tab). Kept in ~/.myra/notes.json.
 *                   Passwords, PINs, keys and card numbers are never stored.
 */
import { execFile } from 'node:child_process'
import crypto from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'

const newId = () => crypto.randomBytes(3).toString('hex')
const clampInt = (v, lo, hi) => Math.max(lo, Math.min(hi, Math.round(Number(v))))

// Same idea as the memory tool: if it looks like a secret, it is not saved.
const SECRET = /(password|passcode|passwd|\bpin\b|\botp\b|\bcvv\b|card number|api[ _-]?key|secret|private key|seed phrase|\b\d{12,19}\b)/i

// A fixed list. The model can only pick a name; it can never pass its own action or component.
export const PANELS = {
  wifi: { args: ['-a', 'android.settings.WIFI_SETTINGS'], label: 'Wi-Fi' },
  bluetooth: { args: ['-a', 'android.settings.BLUETOOTH_SETTINGS'], label: 'Bluetooth' },
  location: { args: ['-a', 'android.settings.LOCATION_SOURCE_SETTINGS'], label: 'Location' },
  hotspot: { args: ['-n', 'com.android.settings/.TetherSettings'], fallback: ['-a', 'android.settings.WIRELESS_SETTINGS'], label: 'Hotspot and tethering' },
  airplane: { args: ['-a', 'android.settings.AIRPLANE_MODE_SETTINGS'], label: 'Airplane mode' },
  mobile_data: { args: ['-a', 'android.settings.DATA_ROAMING_SETTINGS'], label: 'Mobile data' },
  display: { args: ['-a', 'android.settings.DISPLAY_SETTINGS'], label: 'Display' },
  sound: { args: ['-a', 'android.settings.SOUND_SETTINGS'], label: 'Sound' },
  battery: { args: ['-a', 'android.intent.action.POWER_USAGE_SUMMARY'], label: 'Battery' },
  nfc: { args: ['-a', 'android.settings.NFC_SETTINGS'], label: 'NFC' },
  date: { args: ['-a', 'android.settings.DATE_SETTINGS'], label: 'Date and time' },
}

// Spoken or typed variants that mean one of the panels above.
const ALIAS = { wi_fi: 'wifi', data: 'mobile_data', mobiledata: 'mobile_data', tethering: 'hotspot', flight: 'airplane', flight_mode: 'airplane', airplane_mode: 'airplane', time: 'date' }

export function makeIris({ dir, log = () => {}, exec = execFile } = {}) {
  const notesFile = path.join(dir, 'notes.json')
  const torchFile = path.join(dir, 'torch.json')
  const load = (f, empty) => { try { return existsSync(f) ? JSON.parse(readFileSync(f, 'utf8')) : empty } catch { return empty } }
  const save = (f, v) => writeFileSync(f, JSON.stringify(v, null, 2), { mode: 0o600 })

  function run(cmd, args = [], timeoutMs = 10_000) {
    return new Promise((resolve, reject) => {
      exec(cmd, args, { timeout: timeoutMs, maxBuffer: 1 << 20, encoding: 'utf8' }, (err, stdout, stderr) => {
        if (err) {
          if (err.code === 'ENOENT') return reject(new Error(`${cmd} is not available. Run this inside Termux${cmd.startsWith('termux-') ? ' with the Termux:API app installed' : ''}.`))
          return reject(new Error(String(stderr || err.message).trim().slice(0, 300) || `${cmd} failed`))
        }
        resolve(String(stdout || '') + String(stderr || ''))
      })
    })
  }

  /* ── torch ────────────────────────────────────────────────────────────── */

  async function torch({ action } = {}) {
    const a = String(action || 'toggle').toLowerCase()
    if (!['on', 'off', 'toggle'].includes(a)) throw new Error('action must be on, off or toggle.')
    // termux-torch can only switch, not read, so "toggle" goes by the last state MYRA set.
    const was = !!load(torchFile, { on: false }).on
    const want = a === 'toggle' ? !was : a === 'on'
    await run('termux-torch', [want ? 'on' : 'off'])
    save(torchFile, { on: want })
    return `Torch is ${want ? 'on' : 'off'}.`
  }

  /* ── settings panels ──────────────────────────────────────────────────── */

  const startPanel = async (args) => {
    const out = await run('am', ['start', ...args])
    if (/error|does not exist|unable to resolve|not started/i.test(out)) throw new Error('That screen did not open on this phone.')
  }

  async function settingsOpen({ panel } = {}) {
    const raw = String(panel || '').toLowerCase().replace(/[\s-]+/g, '_')
    const p = PANELS[ALIAS[raw] || raw]
    if (!p) throw new Error(`panel must be one of: ${Object.keys(PANELS).join(', ')}.`)
    try {
      await startPanel(p.args)
    } catch (e) {
      if (!p.fallback) throw e
      await startPanel(p.fallback)
    }
    return `Opened the ${p.label} settings. Anuj switches it himself, I can't change it from here.`
  }

  /* ── notes ────────────────────────────────────────────────────────────── */

  const notes = () => load(notesFile, [])

  function noteAdd({ text, title } = {}) {
    const body = String(text || '').trim()
    if (!body) throw new Error('Say what to write in the note.')
    if (body.length > 2000) throw new Error('That note is too long. Keep it under 2000 letters.')
    const head = String(title || '').trim().slice(0, 60)
    if (SECRET.test(body) || SECRET.test(head)) throw new Error('That looks like a password, PIN, key or card number. I never save those in notes.')
    const all = notes()
    if (all.length >= 200) throw new Error('Notes are full (200). Remove a few first.')
    const n = { id: newId(), at: new Date().toISOString(), title: head, text: body }
    all.push(n)
    save(notesFile, all)
    return `Saved note ${n.id}${head ? ` "${head}"` : ''}.`
  }

  function noteList({ query, limit } = {}) {
    const q = String(query || '').trim().toLowerCase()
    const n = clampInt(limit || 10, 1, 30)
    let all = notes()
    if (q) all = all.filter((x) => `${x.title} ${x.text}`.toLowerCase().includes(q))
    if (!all.length) return q ? `No notes match "${q}".` : 'No notes yet.'
    return all.slice(-n).reverse().map((x) => `${x.id} (${x.at.slice(0, 10)})${x.title ? ` ${x.title}:` : ''} ${x.text.length > 200 ? `${x.text.slice(0, 200)}...` : x.text}`).join('\n')
  }

  function noteRemove({ match } = {}) {
    const q = String(match || '').trim().toLowerCase()
    if (!q) throw new Error('Say which note: its id or a word from it.')
    const all = notes()
    const keep = all.filter((x) => x.id !== q && !`${x.title} ${x.text}`.toLowerCase().includes(q))
    const gone = all.length - keep.length
    if (!gone) return `No note matches "${q}".`
    // A loose word could match many notes. Delete one at a time, unless an exact id was given.
    if (gone > 1 && !all.some((x) => x.id === q)) return `${gone} notes match "${q}". Say the id of the one to remove (use note_list).`
    save(notesFile, keep)
    return `Removed ${gone} note${gone > 1 ? 's' : ''}.`
  }

  /* ── tool list ────────────────────────────────────────────────────────── */

  const obj = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false })
  const str = (description) => ({ type: 'string', description })
  const TOOLS = [
    { name: 'torch', description: 'Switch the phone flashlight on or off ("torch jalao", "flashlight band karo"). Needs the Termux:API app. Prefer on or off over toggle.', inputSchema: obj({ action: { type: 'string', enum: ['on', 'off', 'toggle'] } }, ['action']) },
    { name: 'settings_open', description: `Open a phone Settings screen so Anuj can switch something himself: ${Object.keys(PANELS).join(', ')}. This only opens the screen. You cannot flip the switch, so say "settings khol di, switch aap dabana Anuj". Never claim Wi-Fi, Bluetooth or location was turned on or off.`, inputSchema: obj({ panel: { type: 'string', enum: Object.keys(PANELS) } }, ['panel']) },
    { name: 'note_add', description: 'Save a short note for Anuj (a list, an idea, something to read later). Different from remember: notes are things he wants to look at again, not facts about him. Never passwords, PINs, keys or card numbers.', inputSchema: obj({ text: str('The note, up to 2000 letters.'), title: str('Optional short title.') }, ['text']) },
    { name: 'note_list', description: 'Read Anuj his notes, newest first. query filters by a word.', inputSchema: obj({ query: str('Optional word to filter by.'), limit: { type: 'number', description: '1-30. Default 10.' } }) },
    { name: 'note_remove', description: 'Delete a note by its id, or by a word that matches exactly one note.', inputSchema: obj({ match: str('A note id or a word from it.') }, ['match']) },
  ]
  const HANDLERS = { torch, settings_open: settingsOpen, note_add: noteAdd, note_list: noteList, note_remove: noteRemove }
  log('iris tools ready')
  return { TOOLS, HANDLERS }
}
