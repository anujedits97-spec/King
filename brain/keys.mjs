/**
 * The keys and accounts Anuj types into the MYRA app (Settings, Keys and accounts). They are kept in
 * the brain's config file in Termux, and the app never stores them.
 *
 *   GET  /keys  -> what is set up. Never returns a secret, only whether it is there and the plain settings (host, ids, model).
 *   POST /keys  -> saves what was sent. An empty field keeps the saved value. "clear": ["email", ...] removes a section.
 *                  Everything is checked first: if one value is wrong, nothing is saved and the reply says what to fix.
 *
 * Sections: email, images, facebook, instagram, youtube, tavily (web search), and graphVersion (the Meta API version).
 */

const HTTPS_OR_LOCAL = /^(https:\/\/|http:\/\/(127\.0\.0\.1|localhost)(:\d+)?\/)/i
const TLS_MODES = ['implicit', 'starttls', 'none']
const IMAGE_SIZES = ['1024x1024', '1536x1024', '1024x1536']
const SECRET_LIMIT = 2000
const TEXT_LIMIT = 300

const str = (v) => (v === undefined || v === null ? '' : String(v).trim())
const has = (o, k) => !!(o && str(o[k]))

/** The view for the app: what is set, never a secret. */
export function keysView(conf) {
  const e = conf.email || {}
  const i = conf.images || {}
  const s = conf.social || {}
  const fb = s.facebook || {}
  const ig = s.instagram || {}
  const yt = conf.youtube || {}
  return {
    tavily: { set: has(conf.tavily, 'apiKey') },
    ok: true,
    email: { set: has(e, 'host') && has(e, 'pass'), host: str(e.host), port: e.port || null, tls: str(e.tls), user: str(e.user), from: str(e.from) },
    images: { set: has(i, 'apiKey'), model: str(i.model), size: str(i.size), baseUrl: str(i.baseUrl) },
    facebook: { set: has(fb, 'pageId') && has(fb, 'token'), pageId: str(fb.pageId) },
    instagram: { set: has(ig, 'userId') && has(ig, 'token') && has(ig, 'publicBase'), userId: str(ig.userId), publicBase: str(ig.publicBase) },
    youtube: { set: has(yt, 'clientId') && has(yt, 'clientSecret') && has(yt, 'refreshToken') },
    graphVersion: str(s.graphVersion) || 'v21.0',
  }
}

/** Checks the new values. Returns a list of plain sentences; empty when all is fine. */
function problems(body) {
  const out = []
  const text = (name, v, limit = TEXT_LIMIT) => {
    if (v === undefined) return
    if (typeof v !== 'string' && typeof v !== 'number') out.push(`${name} must be text.`)
    else if (/[\r\n\u0000]/.test(String(v))) out.push(`${name} has a line break in it.`)
    else if (String(v).length > limit) out.push(`${name} is too long.`)
  }
  const noSpaces = (name, v) => { if (v !== undefined && /\s/.test(String(v))) out.push(`${name} must not have spaces in it.`) }
  if (body.email) {
    const e = body.email
    if (e.host !== undefined) { text('The mail server', e.host); if (e.host && !/^[a-z0-9.-]+$/i.test(e.host)) out.push('The mail server should look like smtp.gmail.com.') }
    if (e.port !== undefined && e.port !== '') { const p = Number(e.port); if (!Number.isInteger(p) || p < 1 || p > 65535) out.push('The port must be a number from 1 to 65535.') }
    if (e.tls !== undefined && e.tls !== '' && !TLS_MODES.includes(String(e.tls))) out.push('The connection type must be implicit, starttls or none.')
    text('The login', e.user); text('The from address', e.from); text('The password', e.pass, SECRET_LIMIT)
  }
  if (body.images) {
    const i = body.images
    text('The image key', i.apiKey, SECRET_LIMIT); noSpaces('The image key', i.apiKey)
    text('The image model', i.model, 60)
    if (i.size !== undefined && i.size !== '' && !IMAGE_SIZES.includes(String(i.size))) out.push('The picture size must be 1024x1024, 1536x1024 or 1024x1536.')
    if (i.baseUrl !== undefined && i.baseUrl !== '' && !HTTPS_OR_LOCAL.test(String(i.baseUrl))) out.push('The image API address must start with https://.')
  }
  if (body.facebook) {
    const f = body.facebook
    if (f.pageId !== undefined && f.pageId !== '' && !/^\d+$/.test(String(f.pageId))) out.push('The Facebook Page id is a number.')
    text('The Facebook token', f.token, SECRET_LIMIT); noSpaces('The Facebook token', f.token)
  }
  if (body.instagram) {
    const g = body.instagram
    if (g.userId !== undefined && g.userId !== '' && !/^\d+$/.test(String(g.userId))) out.push('The Instagram account id is a number.')
    text('The Instagram token', g.token, SECRET_LIMIT); noSpaces('The Instagram token', g.token)
    if (g.publicBase !== undefined && g.publicBase !== '' && !/^https:\/\//i.test(String(g.publicBase))) out.push('The public web folder must start with https://.')
  }
  if (body.youtube) {
    const y = body.youtube
    text('The YouTube client id', y.clientId); text('The YouTube client secret', y.clientSecret, SECRET_LIMIT); text('The YouTube refresh token', y.refreshToken, SECRET_LIMIT)
    noSpaces('The YouTube refresh token', y.refreshToken)
  }
  if (body.tavily) text('The Tavily key', body.tavily.apiKey, SECRET_LIMIT)
  if (body.graphVersion !== undefined && body.graphVersion !== '' && !/^v\d+\.\d+$/.test(String(body.graphVersion))) out.push('The Meta API version looks like v21.0.')
  return out
}

/** Merges the new values into a copy of the saved settings. An empty value keeps the saved one. */
function merged(conf, body) {
  const pick = (oldObj, input, keys) => {
    const next = { ...(oldObj || {}) }
    for (const k of keys) {
      const v = input?.[k]
      if (v === undefined || v === null || str(v) === '') continue
      next[k] = typeof v === 'string' ? v.trim() : v
    }
    return next
  }
  const next = { ...conf }
  const clear = new Set(Array.isArray(body.clear) ? body.clear.map(String) : [])
  if (body.email || clear.has('email')) {
    next.email = clear.has('email') ? null : pick(conf.email, body.email, ['host', 'port', 'tls', 'user', 'pass', 'from'])
    if (next.email && next.email.port !== undefined) next.email.port = Number(next.email.port)
    if (next.email && !next.email.tls) next.email.tls = Number(next.email.port) === 465 ? 'implicit' : 'starttls'
  }
  if (body.images || clear.has('images')) {
    next.images = clear.has('images') ? null : pick(conf.images, body.images, ['apiKey', 'model', 'size', 'baseUrl'])
  }
  const social = { ...(conf.social || {}) }
  if (body.graphVersion !== undefined && str(body.graphVersion)) social.graphVersion = str(body.graphVersion)
  if (body.facebook || clear.has('facebook')) social.facebook = clear.has('facebook') ? null : pick(social.facebook, body.facebook, ['pageId', 'token'])
  if (body.instagram || clear.has('instagram')) social.instagram = clear.has('instagram') ? null : pick(social.instagram, body.instagram, ['userId', 'token', 'publicBase'])
  next.social = social
  if (body.youtube || clear.has('youtube')) next.youtube = clear.has('youtube') ? null : pick(conf.youtube, body.youtube, ['clientId', 'clientSecret', 'refreshToken'])
  if (body.tavily || clear.has('tavily')) next.tavily = clear.has('tavily') ? null : pick(conf.tavily, body.tavily, ['apiKey'])
  return next
}

/**
 * Saves the keys into conf (in place) when everything checks out. Returns { ok, error?, view? }.
 * Nothing changes when a value is wrong.
 */
export function keysSave(conf, body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return { ok: false, error: 'Send the keys as a settings object.' }
  const errors = problems(body)
  if (errors.length) return { ok: false, error: errors.join(' ') }
  const next = merged(conf, body)
  // Apply in place, so every module that reads the config sees the new keys at once.
  for (const k of Object.keys(next)) conf[k] = next[k]
  return { ok: true, view: keysView(conf) }
}
