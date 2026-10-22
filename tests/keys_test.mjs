import { keysView, keysSave } from '../brain/keys.mjs'
import { makeMail } from '../brain/mail.mjs'
import { makeYoutube } from '../brain/youtube.mjs'
import { makeSocial } from '../brain/social.mjs'
let fails = 0
const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) fails++ }

const conf = { workspace: '/tmp/none', social: { graphVersion: 'v21.0' } }

// 1. empty: nothing set
let v = keysView(conf)
ok(!v.email.set && !v.images.set && !v.facebook.set && !v.instagram.set && !v.youtube.set, 'empty config shows nothing set')

// 2. save email with password; the view shows it set but never the password
let r = keysSave(conf, { email: { host: 'smtp.gmail.com', port: '465', user: 'me@gmail.com', pass: 'APP_PW_SECRET', from: 'me@gmail.com' } })
ok(r.ok, 'email saved')
const txt = JSON.stringify(keysView(conf))
ok(!/APP_PW_SECRET/.test(txt), 'view never contains the password')
ok(keysView(conf).email.set && keysView(conf).email.port === null || keysView(conf).email.set, 'email shows as set')
ok(conf.email.port === 465 && conf.email.tls === 'implicit', 'port is a number and tls defaults to implicit for 465')

// 3. a later save with an empty password keeps the old one
r = keysSave(conf, { email: { host: 'smtp.gmail.com', pass: '' , user: 'new@gmail.com' } })
ok(r.ok && conf.email.pass === 'APP_PW_SECRET' && conf.email.user === 'new@gmail.com', 'empty field keeps saved secret, changes the rest')

// 4. all-or-nothing: one bad value and nothing changes
const before = JSON.stringify(conf)
r = keysSave(conf, { images: { apiKey: 'sk-1', baseUrl: 'http://evil.example/' }, email: { port: 99999 } })
ok(!r.ok && r.error.includes('https://') && r.error.includes('port'), 'bad values reported together: ' + r.error.slice(0, 90))
ok(JSON.stringify(conf) === before, 'nothing saved when a value is wrong')

// 5. the other sections, with validation
ok(keysSave(conf, { facebook: { pageId: 'abc', token: 'T' } }).error.includes('number'), 'facebook page id must be a number')
ok(keysSave(conf, { instagram: { userId: '777', token: 'T', publicBase: 'http://host/' } }).error.includes('https'), 'instagram public folder must be https')
ok(keysSave(conf, { graphVersion: '21' }).error.includes('v21.0'), 'graph version format checked')
ok(keysSave(conf, { youtube: { clientId: 'a\nb', clientSecret: 's', refreshToken: 'r' } }).error.includes('line break'), 'line breaks refused')
ok(keysSave(conf, { images: { size: '999x999' } }).error.includes('picture size'), 'picture size must be a known one')

// 6. a full valid set
r = keysSave(conf, {
  images: { apiKey: 'sk-IMG', model: 'gpt-image-1', size: '1024x1024' },
  facebook: { pageId: '4242', token: 'FB_TOK' },
  instagram: { userId: '777', token: 'IG_TOK', publicBase: 'https://host.example/outputs' },
  youtube: { clientId: 'CID', clientSecret: 'CSEC', refreshToken: 'RTOK' },
  graphVersion: 'v22.0',
})
ok(r.ok, 'full set saved')
v = keysView(conf)
ok(v.images.set && v.facebook.set && v.instagram.set && v.youtube.set, 'every section shows as set')
ok(v.graphVersion === 'v22.0', 'graph version updated')
ok(!/sk-IMG|FB_TOK|IG_TOK|CSEC|RTOK|APP_PW/.test(JSON.stringify(v)), 'no secret anywhere in the view')

// 7. clear removes a section
r = keysSave(conf, { clear: ['youtube'] })
ok(r.ok && !keysView(conf).youtube.set && conf.youtube === null, 'clear removes a section')

// 8. the existing modules read the same live config
const getConf = () => conf
const mail = makeMail({ log() {}, getConf, outDir: '/tmp/none' })
ok((await mail.HANDLERS.email_status()).includes('smtp.gmail.com'), 'email_status sees the new host')
const yt = makeYoutube({ log() {}, getConf, outDir: '/tmp/none' })
ok((await yt.HANDLERS.youtube_status()).includes('not set up'), 'youtube status: cleared means not set up')
const soc = makeSocial({ log() {}, getConf, outDir: '/tmp/none' })
ok((await soc.HANDLERS.social_status()).includes('Instagram posting is set up'), 'social status sees instagram key')
ok(!/IG_TOK|FB_TOK|sk-IMG/.test(await soc.HANDLERS.social_status()), 'social status still hides secrets')

console.log(fails ? `\n${fails} FAILED` : '\nall key-settings checks passed')
process.exit(fails ? 1 : 0)
