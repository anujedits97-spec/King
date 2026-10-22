import { makeResearch, tavilySearch } from '../brain/research.mjs'
let fails = 0
const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) fails++ }
const LONG = 'The Ganga is a major river in South Asia that flows through India and Bangladesh. '.repeat(8)
const calls = []
let tavilyMode = 'ok'
globalThis.fetch = async (url, init) => {
  const u = String(url); calls.push({ u, init })
  if (u === 'https://api.tavily.com/search') {
    if (tavilyMode === 'fail') return new Response(JSON.stringify({ error: 'Invalid API key' }), { status: 401 })
    return new Response(JSON.stringify({ results: [
      { title: 'Ganga', url: 'https://en.wikipedia.org/wiki/Ganga', content: LONG, score: 0.9 },
      { title: 'Blocked page', url: 'https://paywall.example/x', content: 'Summary of the blocked page, long enough to be used as a summary for the reader.', score: 0.5 },
      { title: 'Local', url: 'http://127.0.0.1/admin', content: 'should be dropped entirely here', score: 0.1 },
    ] }), { status: 200 })
  }
  if (u.includes('duckduckgo')) return new Response('<html></html>', { status: 200 })
  if (u.includes('wikipedia.org/w/api')) return new Response('{"query":{"search":[]}}', { status: 200 })
  if (u === 'https://en.wikipedia.org/wiki/Ganga') return new Response(`<title>Ganga</title><p>${LONG}</p>`, { status: 200, headers: { 'content-type': 'text/html' } })
  return new Response('no', { status: 500 })
}

// 1. the Tavily call itself
const t = await tavilySearch('tvly-KEY', 'ganga river', 3)
ok(calls[0].u === 'https://api.tavily.com/search', 'Tavily endpoint used')
ok(calls[0].init.headers.authorization === 'Bearer tvly-KEY', 'Tavily key sent as bearer')
const body = JSON.parse(calls[0].init.body)
ok(body.query === 'ganga river' && body.max_results === 3, 'query and max_results sent')
ok(t.hits.length === 2 && !t.hits.some((h) => h.url.includes('127.0.0.1')), 'private addresses dropped from Tavily results')

// 2. research uses Tavily first and reads the page
calls.length = 0
const { HANDLERS } = makeResearch({ getConf: () => ({ tavily: { apiKey: 'tvly-KEY' } }) })
let out = await HANDLERS.research_pages({ query: 'ganga river', pages: 2 })
ok(calls[0].u === 'https://api.tavily.com/search', 'research asks Tavily first')
ok(!calls.some((c) => c.u.includes('duckduckgo')), 'DuckDuckGo not needed when Tavily answers')
ok(out.includes('[1] Ganga') && out.includes('Ganga is a major river'), 'page read and used as source')
ok(out.includes('(summary only, the page could not be read)') && out.includes('[2] Blocked page'), 'unreadable page falls back to Tavily summary, marked')

// 3. Tavily refuses: falls back to the free search, still works
tavilyMode = 'fail'; calls.length = 0
out = await HANDLERS.research_pages({ query: 'ganga river', pages: 1 })
ok(calls.some((c) => c.u.includes('duckduckgo')) || calls.some((c) => c.u.includes('wikipedia.org/w/api')), 'falls back to free search when Tavily fails')

// 4. no key: exactly the old path, Tavily never called
calls.length = 0; tavilyMode = 'ok'
const h2 = makeResearch({ getConf: () => ({}) }).HANDLERS
await h2.research_pages({ query: 'ganga river', pages: 1 })
ok(!calls.some((c) => c.u.includes('tavily')), 'no key means Tavily is never called')

console.log(fails ? `\n${fails} FAILED` : '\nall Tavily checks passed')
process.exit(fails ? 1 : 0)
