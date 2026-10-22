/**
 * Live prices and a personal watchlist. Prices come from Yahoo's public chart data (no key needed).
 * It is unofficial, so it can fail or rate-limit; the tools say so instead of guessing a number.
 * Symbols: RELIANCE.NS (NSE India), TCS.NS, AAPL, BTC-USD, ^NSEI (index).
 */
import fs from 'node:fs'
import path from 'node:path'

const SYMBOL = /^[A-Za-z0-9.\-^=]{1,15}$/
const MAX_WATCH = 30

export function makeMarkets({ dir, log }) {
  const file = path.join(dir, 'watchlist.json')
  const load = () => { try { return JSON.parse(fs.readFileSync(file, 'utf8')) } catch { return [] } }
  const save = (list) => fs.writeFileSync(file, JSON.stringify(list, null, 2))
  const obj = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false })
  const clean = (s) => {
    const sym = String(s ?? '').trim().toUpperCase()
    if (!SYMBOL.test(sym)) throw new Error('That does not look like a stock or coin symbol. Examples: RELIANCE.NS, AAPL, BTC-USD.')
    return sym
  }

  async function quote(sym) {
    const r = await fetch(`https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(sym)}?range=5d&interval=1d`, {
      headers: { 'user-agent': 'Mozilla/5.0 (MYRA assistant)' },
      signal: AbortSignal.timeout(10_000),
    })
    if (!r.ok) throw new Error(`no live price for ${sym} right now`)
    const m = (await r.json()).chart?.result?.[0]?.meta
    if (!m || typeof m.regularMarketPrice !== 'number') throw new Error(`no live price for ${sym}`)
    const prev = m.chartPreviousClose ?? m.previousClose
    const change = prev ? ((m.regularMarketPrice - prev) / prev) * 100 : null
    const pct = change == null ? '' : ` (${change >= 0 ? '+' : ''}${change.toFixed(2)}% today)`
    return `${sym}: ${m.regularMarketPrice} ${m.currency || ''}${pct}`.replace(/\s+/g, ' ').trim()
  }

  async function marketPrice({ symbol } = {}) {
    const sym = clean(symbol)
    log('market_price', sym)
    return await quote(sym)
  }

  function watchlistAdd({ symbol } = {}) {
    const sym = clean(symbol)
    const list = load()
    if (list.includes(sym)) return `${sym} is already on the watchlist.`
    if (list.length >= MAX_WATCH) throw new Error(`The watchlist holds ${MAX_WATCH} symbols. Remove one first.`)
    list.push(sym)
    save(list)
    return `Added ${sym} to the watchlist.`
  }

  function watchlistRemove({ symbol } = {}) {
    const sym = clean(symbol)
    const list = load()
    if (!list.includes(sym)) return `${sym} is not on the watchlist.`
    save(list.filter((s) => s !== sym))
    return `Removed ${sym} from the watchlist.`
  }

  async function watchlistShow() {
    const list = load()
    if (!list.length) return 'The watchlist is empty. Say a symbol to add, for example RELIANCE.NS.'
    const lines = await Promise.all(list.map((s) => quote(s).catch((e) => `${s}: ${e.message}`)))
    return 'Watchlist:\n' + lines.map((l) => `- ${l}`).join('\n')
  }

  const TOOLS = [
    { name: 'market_price', description: 'Live price of a stock, index or coin, with today\'s change. Use NSE symbols with .NS (RELIANCE.NS), US stocks by ticker (AAPL), crypto as BTC-USD. Say clearly when a price is live data and when it could not be fetched. Never invent a price.',
      inputSchema: obj({ symbol: { type: 'string' } }, ['symbol']) },
    { name: 'watchlist_add', description: 'Add a stock, index or coin to Anuj\'s watchlist.', inputSchema: obj({ symbol: { type: 'string' } }, ['symbol']) },
    { name: 'watchlist_remove', description: 'Remove a symbol from Anuj\'s watchlist.', inputSchema: obj({ symbol: { type: 'string' } }, ['symbol']) },
    { name: 'watchlist_show', description: 'Live prices of everything on Anuj\'s watchlist.', inputSchema: obj({}) },
  ]
  const HANDLERS = {
    market_price: marketPrice,
    watchlist_add: watchlistAdd,
    watchlist_remove: watchlistRemove,
    watchlist_show: watchlistShow,
  }
  return { TOOLS, HANDLERS }
}
