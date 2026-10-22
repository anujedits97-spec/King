import { makeYoutube, videoFile } from '../brain/youtube.mjs'
import { mkdtempSync, writeFileSync, mkdirSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
let fails = 0
const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) fails++ }

const root = mkdtempSync(path.join(os.tmpdir(), 'yt-'))
const outDir = path.join(root, 'outputs'); mkdirSync(outDir)
writeFileSync(path.join(outDir, 'trip.mp4'), Buffer.from('fake video bytes'))
writeFileSync(path.join(outDir, 'notes.txt'), 'x')
const cfg = { clientId: 'CID', clientSecret: 'CSECRET', refreshToken: 'RTOKEN_SECRET' }
let social = { youtube: cfg }

// 1. file safety
for (const bad of ['../trip.mp4', '/etc/passwd', '.x.mp4', 'notes.txt', 'nope.mp4'])
  { try { videoFile(outDir, bad, [outDir]); ok(false, 'blocked ' + bad) } catch (e) { ok(true, 'refused ' + bad) } }
ok(videoFile(outDir, 'trip.mp4', [outDir]).endsWith('trip.mp4'), 'allowed file found')

// 2. fake Google
const calls = []
globalThis.fetch = async (url, init) => {
  const u = String(url); calls.push({ url: u, init })
  if (u.startsWith('https://oauth2.googleapis.com/token')) return new Response(JSON.stringify({ access_token: 'AT_123' }), { status: 200 })
  if (u.includes('/upload/youtube/v3/videos')) return new Response('{}', { status: 200, headers: { location: 'https://upload.example/session/42' } })
  if (u === 'https://upload.example/session/42') return new Response(JSON.stringify({ id: 'vid_abc' }), { status: 200 })
  return new Response('{}', { status: 404 })
}

const m = makeYoutube({ log() {}, getConf: () => social, outDir })

// 3. two-step, private by default
const first = await m.HANDLERS.youtube_upload({ file: 'trip.mp4', title: 'Jaipur trip' })
ok(first.startsWith('Not done yet') && first.includes('private, so only Anuj'), 'default privacy is private and read back')
ok(!calls.length, 'nothing sent before yes')
const tok = first.match(/confirm_token ([0-9a-f]+)/)[1]
const done = await m.HANDLERS.youtube_upload({ file: 'trip.mp4', title: 'Jaipur trip', confirm_token: tok })
ok(done === 'Uploaded "Jaipur trip" as private. Link: https://youtu.be/vid_abc', 'upload reports the link')

// 4. the upload itself
const tokenCall = calls.find((c) => c.url.startsWith('https://oauth2'))
ok(new URLSearchParams(tokenCall.init.body).get('grant_type') === 'refresh_token', 'refresh token used for a login')
const init = calls.find((c) => c.url.includes('uploadType=resumable'))
ok(init.init.headers.authorization === 'Bearer AT_123', 'upload uses the access token')
ok(init.init.headers['x-upload-content-length'] === String('fake video bytes'.length), 'content length declared')
const meta = JSON.parse(init.init.body)
ok(meta.status.privacyStatus === 'private' && meta.snippet.title === 'Jaipur trip', 'metadata: private and title')
const put = calls.find((c) => c.url === 'https://upload.example/session/42')
ok(Buffer.from(put.init.body).toString() === 'fake video bytes', 'video bytes sent to the upload address')

// 5. public needs its own warning; changing privacy with an old token is refused
const pub = await m.HANDLERS.youtube_upload({ file: 'trip.mp4', title: 'Jaipur trip', privacy: 'public' })
ok(pub.includes('PUBLIC, so anyone on YouTube can see it'), 'public warned clearly')
try { await m.HANDLERS.youtube_upload({ file: 'trip.mp4', title: 'Jaipur trip', privacy: 'private', confirm_token: pub.match(/confirm_token ([0-9a-f]+)/)[1] }); ok(false, 'privacy change with old token refused') }
catch (e) { ok(/wrong|different/.test(e.message), 'privacy change with old token refused') }

// 6. limits and setup
ok((await m.HANDLERS.youtube_upload({ file: 'trip.mp4', title: 'x'.repeat(101) })).includes('longer than 100'), 'long title refused')
ok((await m.HANDLERS.youtube_upload({ file: 'trip.mp4', title: 'a', privacy: 'secret' })).includes('private, unlisted or public'), 'bad privacy refused')
social = { youtube: {} }
ok((await m.HANDLERS.youtube_upload({ file: 'trip.mp4', title: 'a' })).includes('not set up'), 'unset config explained')
social = { youtube: cfg }

// 7. Google's errors in plain words
globalThis.fetch = async (url) => String(url).startsWith('https://oauth2') ? new Response(JSON.stringify({ error: 'invalid_grant', error_description: 'Token has been expired or revoked.' }), { status: 400 }) : new Response('{}', { status: 500 })
const f2 = await m.HANDLERS.youtube_upload({ file: 'trip.mp4', title: 'again' })
try { await m.HANDLERS.youtube_upload({ file: 'trip.mp4', title: 'again', confirm_token: f2.match(/confirm_token ([0-9a-f]+)/)[1] }); ok(false, 'login error surfaced') }
catch (e) { ok(e.message === 'YouTube did not accept the login: Token has been expired or revoked.', 'login error in plain words') }

// 8. status never shows secrets
const st = await m.HANDLERS.youtube_status()
ok(!/RTOKEN|CSECRET|CID/.test(st), 'status hides secrets: ' + st)

console.log(fails ? `\n${fails} FAILED` : '\nall youtube checks passed')
process.exit(fails ? 1 : 0)
