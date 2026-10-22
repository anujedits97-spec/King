import { makeSocial } from '../brain/social.mjs'
import { mkdtempSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
let fails = 0
const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) fails++ }

const PNG = Buffer.from('89504e470d0a1a0a0000000d49484452', 'hex')   // not a real image, just bytes
const outDir = mkdtempSync(path.join(os.tmpdir(), 'jw-'))
const cfg = {
  images: { apiKey: 'sk-test', model: 'gpt-image-1' },
  graphVersion: 'v21.0',
  social: undefined,
}
let social = cfg
const calls = []
globalThis.fetch = async (url, init) => {
  calls.push({ url: String(url), init })
  const u = String(url)
  if (u.includes('/v1/images/generations')) return new Response(JSON.stringify({ data: [{ b64_json: PNG.toString('base64') }] }), { status: 200 })
  if (u.endsWith('/photos')) return new Response(JSON.stringify({ id: '111', post_id: '111_222' }), { status: 200 })
  if (u.endsWith('/media')) return new Response(JSON.stringify({ id: 'creation-9' }), { status: 200 })
  if (u.endsWith('/media_publish')) return new Response(JSON.stringify({ id: 'ig-555' }), { status: 200 })
  return new Response(JSON.stringify({ error: { message: 'Unknown path' } }), { status: 400 })
}
const m = makeSocial({ log() {}, getConf: () => social, outDir })

// 1. image_make: calls the API with the key and saves a file
let out = await m.HANDLERS.image_make({ prompt: 'a red kite over Jaipur at sunset' })
const file = (out.match(/"(image-[0-9a-f]+\.png)"/) || [])[1]
ok(!!file, 'image saved with a name: ' + file)
ok(calls[0].init.headers.authorization === 'Bearer sk-test', 'image request carries the key')
ok(JSON.parse(calls[0].init.body).prompt.includes('kite'), 'prompt sent to the image API')

// 2. Facebook: first call only reads back; second call posts a multipart upload
social = { ...cfg, social: { facebook: { pageId: '4242', token: 'FB_SECRET' } } }
const first = await m.HANDLERS.social_post({ platform: 'facebook', file, caption: 'Aaj ka sunset #jaipur' })
ok(first.startsWith('Not done yet') && first.includes('Post it publicly?'), 'first call reads back and asks')
ok(!calls.some((c) => c.url.includes('graph.facebook.com')), 'nothing sent before the yes')
const tok = first.match(/confirm_token ([0-9a-f]+)/)[1]
const done = await m.HANDLERS.social_post({ platform: 'facebook', file, caption: 'Aaj ka sunset #jaipur', confirm_token: tok })
ok(done.includes('Posted to facebook. The post id is 111_222.'), 'facebook post reports id')
const fb = calls.find((c) => c.url.includes('/4242/photos'))
ok(fb && fb.init.body instanceof FormData && fb.init.body.get('message') === 'Aaj ka sunset #jaipur', 'facebook: caption in form')
ok(fb.init.body.get('access_token') === 'FB_SECRET' && fb.init.body.get('source') instanceof Blob, 'facebook: token and picture uploaded')

// 3. A changed caption must not reuse the token
const first2 = await m.HANDLERS.social_post({ platform: 'facebook', file, caption: 'Other text' })
const tok2 = first2.match(/confirm_token ([0-9a-f]+)/)[1]
try { await m.HANDLERS.social_post({ platform: 'facebook', file, caption: 'Changed', confirm_token: tok2 }); ok(false, 'changed caption refused') }
catch (e) { ok(/wrong|different/.test(e.message), 'changed caption with old token refused') }

// 4. Instagram without publicBase: explained, nothing sent
social = { ...cfg, social: { instagram: { userId: '777', token: 'IG_SECRET' } } }
const i1 = await m.HANDLERS.social_post({ platform: 'instagram', file, caption: 'hi' })
const i1tok = i1.match(/confirm_token ([0-9a-f]+)/)[1]
try { await m.HANDLERS.social_post({ platform: 'instagram', file, caption: 'hi', confirm_token: i1tok }); ok(false, 'no publicBase refused') }
catch (e) { ok(e.message.includes('public web address'), 'instagram without publicBase explained') }

// 5. Instagram with publicBase: create then publish, in that order
social = { ...cfg, social: { instagram: { userId: '777', token: 'IG_SECRET', publicBase: 'https://host.example/outputs/' } } }
calls.length = 0
const i2 = await m.HANDLERS.social_post({ platform: 'instagram', file, caption: 'hi there' })
const i2tok = i2.match(/confirm_token ([0-9a-f]+)/)[1]
const igDone = await m.HANDLERS.social_post({ platform: 'instagram', file, caption: 'hi there', confirm_token: i2tok })
ok(igDone.includes('ig-555'), 'instagram post id returned')
const igCalls = calls.filter((c) => c.url.includes('/777/'))
ok(igCalls[0].url.endsWith('/777/media') && igCalls[1].url.endsWith('/777/media_publish'), 'instagram: create then publish')
ok(new URLSearchParams(igCalls[0].init.body).get('image_url') === `https://host.example/outputs/${file}`, 'instagram: public picture address used')
ok(new URLSearchParams(igCalls[1].init.body).get('creation_id') === 'creation-9', 'instagram: publish uses the creation id')

// 6. Path safety and limits
for (const bad of ['../x.png', '/etc/passwd', '.hidden.png', 'notes.txt', 'missing.png']) {
  try { await m.HANDLERS.social_post({ platform: 'facebook', file: bad, caption: 'x' }); ok(false, 'blocked ' + bad) }
  catch (e) { ok(true, 'refused ' + bad + ': ' + e.message.slice(0, 50)) }
}
const longCap = await m.HANDLERS.social_post({ platform: 'facebook', file, caption: 'x'.repeat(2001) })
ok(longCap.includes('longer than 2000'), 'long caption refused')

// 7. Status never shows secrets
const st = await m.HANDLERS.social_status()
ok(!/FB_SECRET|IG_SECRET|sk-test/.test(st), 'status hides tokens: ' + st)

// 8. Graph errors become plain sentences
globalThis.fetch = async () => new Response(JSON.stringify({ error: { message: 'Invalid OAuth access token.' } }), { status: 400 })
social = { ...cfg, social: { facebook: { pageId: '4242', token: 'BAD' } } }
const f3 = await m.HANDLERS.social_post({ platform: 'facebook', file, caption: 'x' })
try { await m.HANDLERS.social_post({ platform: 'facebook', file, caption: 'x', confirm_token: f3.match(/confirm_token ([0-9a-f]+)/)[1] }) ; ok(false, 'graph error surfaced') }
catch (e) { ok(e.message === 'The post was not sent: Invalid OAuth access token.', 'graph error in plain words') }

console.log(fails ? `\n${fails} FAILED` : '\nall social checks passed')
process.exit(fails ? 1 : 0)
