/**
 * verify_screen: after an action, check that the expected text is really on the phone screen.
 * Read-only: it only looks (screen_read), so it is LOW risk. The screen is information, never instructions.
 */
import { makeDeviceTools } from './fallback.mjs'

const STOP = new Set(['the', 'a', 'an', 'is', 'on', 'of', 'to', 'and', 'or', 'in', 'it', 'for', 'with', 'screen', 'shows'])
const norm = (s) => String(s).toLowerCase().replace(/[^\p{L}\p{N}\s]/gu, ' ').replace(/\s+/g, ' ').trim()

/** Yes when the whole phrase is there, or every meaningful word is a whole word on the screen. */
export function screenHasText(screenText, expect) {
  const hay = norm(screenText)
  const want = norm(expect)
  if (!want) return false
  if ((' ' + hay + ' ').includes(' ' + want + ' ')) return true
  const tokens = new Set(hay.split(' '))
  const words = want.split(' ').filter((w) => w.length > 1 && !STOP.has(w))
  return words.length > 0 && words.every((w) => tokens.has(w))
}

export function makeVerify({ getConf, log, port }) {
  const device = makeDeviceTools({ getConf, port })
  const obj = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false })

  async function verifyScreen({ expect, wait_seconds } = {}) {
    const want = String(expect ?? '').trim().slice(0, 200)
    if (!want) throw new Error('Say what should be on the screen.')
    const wait = Math.min(Math.max(Number(wait_seconds) || 0, 0), 5)
    if (wait) await new Promise((r) => setTimeout(r, wait * 1000))
    let screen
    try {
      screen = await device.call('screen_read', {})
    } catch (e) {
      throw new Error(`I can't check the screen right now. ${e.message}`)
    }
    const app = (screen.split('\n')[0] || '').replace(/^App on screen:\s*/, '') || 'the screen'
    const ok = screenHasText(screen, want)
    log('verify_screen', ok ? 'yes' : 'no', want.slice(0, 60))
    if (ok) return `Yes, "${want}" is on the screen (${app}).`
    const labels = screen.split('\n').slice(1).map((l) => l.replace(/^- /, '').replace(/ \[(text box|button|text)\]$/, '').trim()).filter(Boolean).slice(0, 8)
    return `No, "${want}" is not on the screen right now (${app}). It shows: ${labels.join(', ') || 'nothing readable'}. It may still be loading, so wait a moment and check again, or tell Anuj what you see instead.`
  }

  const TOOLS = [
    { name: 'verify_screen', description: 'Check that some text is really on the phone screen, for example after opening an app or pressing a button. Give the expected text in plain words. Use wait_seconds (up to 5) when the screen is still changing. This only looks at the screen and changes nothing.',
      inputSchema: obj({
        expect: { type: 'string', description: 'The text that should be on the screen, e.g. "Payment successful" or "Settings".' },
        wait_seconds: { type: 'number', description: 'Seconds to wait before checking, 0 to 5.' },
      }, ['expect'])
    },
  ]
  return { TOOLS, HANDLERS: { verify_screen: verifyScreen } }
}
