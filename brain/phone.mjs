/**
 * Phone tools that live in the brain. Contacts, call log, texts, notifications, the dialer, WhatsApp/
 * Telegram chat opening, sending a text and placing a call are now NATIVE tools inside the MYRA
 * app (android/.../PhoneTools.kt, served by DeviceServer), so Termux:API is no longer needed.
 *
 * What stays here is only the Telegram message through YOUR OWN bot, which is plain HTTPS.
 * Sending is two steps: the first call returns a confirm token and does nothing; MYRA must ask
 * you out loud and only after a clear yes call again with the token. Tokens expire in a minute and
 * fit one exact bot and text.
 *
 * Telegram messages from your own account and automatic WhatsApp sending are deliberately not
 * here: they need accessibility hacks or user-bot libraries that can get an account banned.
 */
import crypto from 'node:crypto'


export function makePhone({ log, getConf, resolveAlias = () => null }) {
  /* ── sending: always two steps ────────────────────────────────────────── */

  const tokens = new Map()
  const fingerprint = (o) => crypto.createHash('sha256').update(JSON.stringify(o)).digest('hex')
  function gate(kind, payload, confirm_token, sentence) {
    const now = Date.now()
    for (const [k, v] of tokens) if (v.exp < now) tokens.delete(k)
    const fp = fingerprint([kind, payload])
    if (!confirm_token) {
      const tok = crypto.randomBytes(5).toString('hex')
      tokens.set(tok, { fp, exp: now + 60_000 })
      return `Not done yet. Ask Anuj out loud: ${sentence} Only if he clearly says yes, call ${kind} again within one minute with the same details and confirm_token ${tok}.`
    }
    const e = tokens.get(String(confirm_token))
    tokens.delete(String(confirm_token))
    if (!e || e.fp !== fp || e.exp < now) throw new Error('That confirmation is wrong, expired, or for different details. Ask again to start over.')
    return null
  }

  async function telegramBotSend({ bot, text, confirm_token } = {}) {
    const bots = getConf().telegramBots || {}
    const names = Object.keys(bots)
    if (!names.length) throw new Error('No Telegram bot is set up. Add one under "telegramBots" in ~/.myra/config.json, like {"me": {"token": "...", "chat_id": "..."}}.')
    const name = bot && bots[bot] ? bot : names[0]
    const b = bots[name]
    if (!b || !b.token || !b.chat_id) throw new Error(`Telegram bot "${name}" needs a token and a chat_id in config.json.`)
    const body = String(text || '').trim()
    if (!body) throw new Error('Say what the message should say.')
    if (body.length > 3500) throw new Error('That message is too long.')
    const ask = gate('telegram_bot_send', [name, body], confirm_token, `send this Telegram message through your bot "${name}": "${body.slice(0, 200)}"?`)
    if (ask) return ask
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 15_000)
    try {
      const r = await fetch(`https://api.telegram.org/bot${b.token}/sendMessage`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ chat_id: b.chat_id, text: body }), signal: ctrl.signal })
      const j = await r.json().catch(() => ({}))
      if (!r.ok || !j.ok) throw new Error(`Telegram refused it: ${j.description || r.status}`)
    } finally { clearTimeout(timer) }
    return `Message sent through Telegram bot "${name}".`
  }

  /* ── tool list ────────────────────────────────────────────────────────── */

  const obj = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false })
  const TOOLS = [
    { name: 'telegram_bot_send', description: "Send a Telegram message through Anuj's own bot to his own chat (set up in config.json). Two steps: the first call returns a confirm_token and does nothing; ask Anuj out loud; only after a clear yes call again with the same text and the token.", inputSchema: obj({ bot: { type: 'string', description: 'Bot name from config. Optional.' }, text: { type: 'string' }, confirm_token: { type: 'string', description: 'Only for the second call.' } }, ['text']) },
  ]
  const HANDLERS = { telegram_bot_send: telegramBotSend }
  return { TOOLS, HANDLERS }
}
