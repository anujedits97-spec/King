import net from 'node:net'
import tls from 'node:tls'
import { readFileSync, existsSync } from 'node:fs'
import { execSync } from 'node:child_process'
import { buildOffice } from '../brain/office.mjs'
if (!existsSync('key.pem')) execSync('openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 1 -subj /CN=localhost', { stdio: 'ignore' })
import { smtpSend, buildMessage } from '../brain/mail.mjs'
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0' // test certificate only

function fakeServer({ starttls }) {
  return new Promise((resolve) => {
    const srv = net.createServer((sock) => {
      let mode = 'cmd', data = '', auth = [], rcpt = [], from = '', tlsUp = false
      let s = sock
      const say = (x) => s.write(x + '\r\n')
      const handle = (sock2) => {
        sock2.setEncoding('utf8')
        sock2.on('data', (d) => {
          data += d
          let i
          while ((i = data.indexOf('\r\n')) >= 0) {
            const line = data.slice(0, i); data = data.slice(i + 2)
            if (mode === 'data') {
              if (line === '.') { mode = 'cmd'; say('250 queued'); resultRef.msg = resultRef.buf; resultRef.buf = ''; continue }
              resultRef.buf += line + '\n'; continue
            }
            if (mode === 'authuser') { auth.push(Buffer.from(line, 'base64').toString()); mode = 'authpass'; say('334 UGFzc3dvcmQ6'); continue }
            if (mode === 'authpass') { auth.push(Buffer.from(line, 'base64').toString()); mode = 'cmd'; say('235 ok'); continue }
            const cmd = line.toUpperCase()
            if (cmd.startsWith('EHLO')) { say('250-fake'); if (starttls && !tlsUp) say('250-STARTTLS'); say('250 AUTH LOGIN'); }
            else if (cmd === 'STARTTLS') {
              say('220 go ahead')
              const t = new tls.TLSSocket(sock2, { isServer: true, key: readFileSync('key.pem'), cert: readFileSync('cert.pem') })
              tlsUp = true; s = t
              t.setEncoding('utf8'); t.on('data', (dd) => { data += dd; }) // reuse same loop below
              sock2.removeAllListeners('data')
              t.removeAllListeners('data')
              t.on('data', (d2) => { handleChunk(d2) })
              return
            }
            else if (cmd === 'AUTH LOGIN') { say('334 VXNlcm5hbWU6'); mode = 'authuser' }
            else if (cmd.startsWith('MAIL FROM')) { from = line; say('250 ok') }
            else if (cmd.startsWith('RCPT TO')) { rcpt.push(line); say('250 ok') }
            else if (cmd === 'DATA') { mode = 'data'; resultRef.buf = ''; say('354 send') }
            else if (cmd === 'QUIT') { say('221 bye'); s.end() }
            else say('500 unknown')
          }
        })
      }
      const handleChunk = (d) => { data += d; drain() }
      const drain = () => {
        let i
        while ((i = data.indexOf('\r\n')) >= 0) {
          const line = data.slice(0, i); data = data.slice(i + 2)
          if (mode === 'data') { if (line === '.') { mode = 'cmd'; say('250 queued'); resultRef.msg = resultRef.buf; resultRef.buf = '' } else resultRef.buf += line + '\n'; continue }
          if (mode === 'authuser') { auth.push(Buffer.from(line, 'base64').toString()); mode = 'authpass'; say('334 UGFzc3dvcmQ6'); continue }
          if (mode === 'authpass') { auth.push(Buffer.from(line, 'base64').toString()); mode = 'cmd'; say('235 ok'); continue }
          const cmd = line.toUpperCase()
          if (cmd.startsWith('EHLO')) { say('250-fake'); if (starttls && !tlsUp) say('250-STARTTLS'); say('250 AUTH LOGIN') }
          else if (cmd === 'STARTTLS') {
            say('220 go ahead')
            tlsUp = true
            const t = new tls.TLSSocket(s, { isServer: true, key: readFileSync('key.pem'), cert: readFileSync('cert.pem') })
            s.removeAllListeners('data')
            s = t; t.setEncoding('utf8'); t.on('data', handleChunk)
            return
          }
          else if (cmd === 'AUTH LOGIN') { say('334 VXNlcm5hbWU6'); mode = 'authuser' }
          else if (cmd.startsWith('MAIL FROM')) { from = line; say('250 ok') }
          else if (cmd.startsWith('RCPT TO')) { rcpt.push(line); say('250 ok') }
          else if (cmd === 'DATA') { mode = 'data'; resultRef.buf = ''; say('354 send') }
          else if (cmd === 'QUIT') { say('221 bye'); s.end() }
          else say('500 unknown')
        }
      }
      s.on('error', () => {})
      s.on('data', () => {})
      s.removeAllListeners('data')
      s.setEncoding('utf8'); s.on('data', handleChunk)
      say('220 fake ready')
      sock.on('error', () => {})
      resultRef.auth = auth; resultRef.rcpt = rcpt; resultRef.from = from
    })
    const resultRef = { buf: '', msg: '', auth: [], rcpt: [], from: '' }
    srv.listen(0, '127.0.0.1', () => resolve({ srv, port: srv.address().port, resultRef }))
  })
}

const att = [{ name: 'report.pdf', bytes: buildOffice('pdf', { title: 'Report', blocks: [{ type: 'p', text: 'hi' }] }).bytes }]
for (const mode of ['none', 'starttls']) {
  const { srv, port, resultRef } = await fakeServer({ starttls: mode === 'starttls' })
  await smtpSend({ host: '127.0.0.1', port, tls: mode, user: 'me@x.com', pass: 'secret', from: 'me@x.com' },
    { to: 'you@y.com', subject: 'Report — aaj ka', body: 'Line one\n.starts with dot\nHello नमस्ते', attachments: att })
  await new Promise((r) => setTimeout(r, 100))
  console.log(mode, '-> auth:', resultRef.auth, 'from:', resultRef.from, 'rcpt:', resultRef.rcpt)
  const raw = resultRef.msg
  const body = raw.split('\n\n')[0]
  console.log('  headers:', raw.split('\n').filter(l => /^(From|To|Subject|MIME|Content-Type):/.test(l)).join(' | ').slice(0, 300))
  console.log('  has attachment part:', raw.includes('filename="report.pdf"'), ' dot-stuffed line kept:', raw.includes('.starts with dot') )
  srv.close()
}
