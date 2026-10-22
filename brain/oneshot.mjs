/**
 * One question to the AI engine Anuj picked on the phone. No tools, no history, no memory.
 * Used by generate_code and by the smart auto-reply; each caller decides what to do with the text.
 */
import { resolveEndpoint } from './fallback.mjs'

export class NoEngineError extends Error {}

export async function oneShot(getConf, system, prompt, { maxTokens = 4096, timeoutMs = 90_000, temperature = 0.2 } = {}) {
  const c = getConf().fallback || null
  if (!c || !c.provider || (!c.apiKey && c.provider !== 'ollama' && c.provider !== 'custom')) {
    throw new NoEngineError('No AI engine is set up on the phone yet. Pick one and paste its key in the AI engine screen first.')
  }
  const { url, model } = resolveEndpoint(c)
  const anthropic = c.provider === 'anthropic'
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), timeoutMs)
  try {
    const r = await fetch(url, {
      method: 'POST',
      headers: anthropic
        ? { 'content-type': 'application/json', 'x-api-key': c.apiKey, 'anthropic-version': '2023-06-01' }
        : { 'content-type': 'application/json', ...(c.apiKey ? { authorization: `Bearer ${c.apiKey}` } : {}) },
      body: JSON.stringify(anthropic
        ? { model, max_tokens: maxTokens, system, messages: [{ role: 'user', content: prompt }] }
        : { model, max_tokens: maxTokens, temperature, messages: [{ role: 'system', content: system }, { role: 'user', content: prompt }] }),
      signal: ctrl.signal,
    })
    const j = await r.json().catch(() => ({}))
    if (!r.ok) throw new Error(`${c.provider} said: ${j.error?.message || r.status}`)
    const text = anthropic
      ? (j.content || []).filter((b) => b.type === 'text').map((b) => b.text).join('')
      : j.choices?.[0]?.message?.content
    if (!String(text || '').trim()) throw new Error(`${c.provider} sent an empty answer.`)
    return String(text).trim()
  } finally {
    clearTimeout(timer)
  }
}
