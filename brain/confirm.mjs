/**
 * Two-step confirmation for tools that reach people or delete things.
 * The first call returns a token and does nothing; MYRA must ask out loud and only after a
 * clear yes call again with the same details and the token. A token fits one exact request and
 * expires in a minute. (Same idea as the gate in phone.mjs.)
 */
import crypto from 'node:crypto'

export function makeGate(who = 'Anuj') {
  const tokens = new Map()
  const fingerprint = (o) => crypto.createHash('sha256').update(JSON.stringify(o)).digest('hex')
  /** Returns null when confirmed, otherwise the sentence that tells MYRA to ask first. */
  return function gate(kind, payload, confirm_token, sentence) {
    const now = Date.now()
    for (const [k, v] of tokens) if (v.exp < now) tokens.delete(k)
    const fp = fingerprint([kind, payload])
    if (!confirm_token) {
      const tok = crypto.randomBytes(5).toString('hex')
      tokens.set(tok, { fp, exp: now + 60_000 })
      return `Not done yet. Ask ${who} out loud: ${sentence} Only if he clearly says yes, call ${kind} again within one minute with the same details and confirm_token ${tok}.`
    }
    const e = tokens.get(String(confirm_token))
    tokens.delete(String(confirm_token))
    if (!e || e.fp !== fp || e.exp < now) throw new Error('That confirmation is wrong, expired, or for different details. Ask again to start over.')
    return null
  }
}
