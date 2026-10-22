/**
 * Email you send by voice. Only sending: MYRA writes the mail, reads it back to Anuj, and sends it
 * only after a clear yes (the same two-step gate as the other tools that reach people).
 *
 * Settings live in the brain's config file, under "email":
 *   { "host": "smtp.gmail.com", "port": 465, "tls": "implicit", "user": "you@gmail.com",
 *     "pass": "app password", "from": "you@gmail.com" }
 * tls: "implicit" (port 465), "starttls" (port 587) or "none" (only for a local test server).
 * For Gmail, the password is an App Password (Google Account, Security, App passwords), not your login.
 *
 * Attachments can only be files MYRA made in the outputs folder (see office.mjs), never anything else on disk.
 */
import net from 'node:net'
import tls from 'node:tls'
import crypto from 'node:crypto'
import path from 'node:path'
import { existsSync, readFileSync, statSync } from 'node:fs'
import { makeGate } from './confirm.mjs'

const EMAIL = /^[^\s@<>,;"'()[\]]+@[^\s@<>,;"'()[\]]+\.[^\s@<>,;"'()[\]]+$/
const MIME = {
  '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  '.pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  '.pdf': 'application/pdf',
  '.txt': 'text/plain',
}

/** A header value without line breaks (no header injection), in UTF-8 when it is not plain ASCII. */
const oneLine = (s) => String(s ?? '').replace(/[\r\n]+/g, ' ').trim()
const encodeHeader = (s) => /^[\x20-\x7e]*$/.test(s) ? s : `=?UTF-8?B?${Buffer.from(s, 'utf8').toString('base64')}?=`
const wrap76 = (s) => s.replace(/(.{76})/g, '$1\r\n')

/** Builds the raw message (headers + body) as CRLF text. */
export function buildMessage({ from, to, subject, body, attachments = [] }) {
  const head = [
    `From: ${oneLine(from)}`,
    `To: ${oneLine(to)}`,
    `Subject: ${encodeHeader(oneLine(subject))}`,
    `Date: ${new Date().toUTCString()}`,
    `Message-ID: <${crypto.randomBytes(12).toString('hex')}@myra>`,
    'MIME-Version: 1.0',
  ]
  const text = wrap76(Buffer.from(String(body ?? ''), 'utf8').toString('base64'))
  if (!attachments.length) {
    return [...head, 'Content-Type: text/plain; charset=UTF-8', 'Content-Transfer-Encoding: base64', '', text].join('\r\n') + '\r\n'
  }
  const boundary = 'myra-' + crypto.randomBytes(12).toString('hex')
  const parts = [
    `--${boundary}`,
    'Content-Type: text/plain; charset=UTF-8',
    'Content-Transfer-Encoding: base64',
    '',
    text,
  ]
  for (const a of attachments) {
    parts.push(
      `--${boundary}`,
      `Content-Type: ${MIME[path.extname(a.name).toLowerCase()] || 'application/octet-stream'}; name="${a.name.replace(/"/g, '')}"`,
      'Content-Transfer-Encoding: base64',
      `Content-Disposition: attachment; filename="${a.name.replace(/"/g, '')}"`,
      '',
      wrap76(a.bytes.toString('base64')),
    )
  }
  parts.push(`--${boundary}--`, '')
  return [...head, `Content-Type: multipart/mixed; boundary="${boundary}"`, '', ...parts].join('\r\n') + '\r\n'
}

/** A line reader on one socket, so the SMTP replies can be read one by one. */
class Line {
  constructor(sock) {
    this.sock = sock
    this.buf = ''
    this.queue = []
    this.waiter = null
    this.error = null
    sock.setEncoding('utf8')
    sock.setTimeout(20_000, () => sock.destroy(new Error('The mail server did not answer in time.')))
    sock.on('data', (d) => {
      this.buf += d
      let i
      while ((i = this.buf.indexOf('\n')) >= 0) {
        this.queue.push(this.buf.slice(0, i).replace(/\r$/, ''))
        this.buf = this.buf.slice(i + 1)
      }
      this.wake()
    })
    sock.on('error', (e) => { this.error = e; this.wake() })
    sock.on('close', () => { this.error ||= new Error('The mail server closed the connection.'); this.wake() })
  }
  wake() { if (this.waiter && (this.queue.length || this.error)) { const w = this.waiter; this.waiter = null; w() } }
  next() {
    return new Promise((resolve, reject) => {
      const take = () => {
        if (this.queue.length) return resolve(this.queue.shift())
        if (this.error) return reject(this.error)
        this.waiter = take
      }
      take()
    })
  }
  /** One reply: "250 ok", or several lines "250-..." ending with "250 ...". */
  async reply() {
    const lines = []
    for (;;) {
      const l = await this.next()
      lines.push(l)
      if (/^\d{3} /.test(l) || /^\d{3}$/.test(l)) break
    }
    return { code: Number(lines[lines.length - 1].slice(0, 3)), text: lines.join('\n') }
  }
}

class Smtp {
  constructor(sock) { this.sock = sock; this.line = new Line(sock) }
  write(s) { this.sock.write(s) }
  async cmd(s, ok) {
    this.write(s + '\r\n')
    const r = await this.line.reply()
    if (!ok.includes(r.code)) throw new Error(`The mail server said: ${r.text.split('\n')[0].slice(0, 160)}`)
    return r
  }
  close() { try { this.sock.end() } catch { /* already closed */ } }
}

function openSocket(conf) {
  return new Promise((resolve, reject) => {
    const mode = conf.tls || (Number(conf.port) === 465 ? 'implicit' : 'starttls')
    const done = (sock) => resolve(sock)
    let sock
    if (mode === 'implicit') {
      sock = tls.connect({ host: conf.host, port: Number(conf.port) || 465, servername: conf.host }, () => done(sock))
    } else {
      sock = net.connect({ host: conf.host, port: Number(conf.port) || (mode === 'none' ? 25 : 587) }, () => done(sock))
    }
    sock.setTimeout(20_000, () => sock.destroy(new Error('Could not reach the mail server in time.')))
    sock.once('error', reject)
  })
}

/** Sends one message. conf: { host, port, tls, user, pass, from }. Resolves when the server accepted it. */
export async function smtpSend(conf, { to, subject, body, attachments = [] }) {
  if (!conf?.host) throw new Error('Email is not set up yet. Add the email settings to the brain config.')
  const from = conf.from || conf.user
  if (!from) throw new Error('Email needs a from address in the settings.')
  const mode = conf.tls || (Number(conf.port) === 465 ? 'implicit' : 'starttls')
  const sock = await openSocket(conf)
  let s = new Smtp(sock)
  let finished = false
  try {
    const greet = await s.line.reply()
    if (greet.code !== 220) throw new Error('The mail server did not greet us.')
    let ehlo = await s.cmd(`EHLO myra`, [250])
    if (mode === 'starttls') {
      await s.cmd('STARTTLS', [220])
      const secure = await new Promise((resolve, reject) => {
        const t = tls.connect({ socket: sock, servername: conf.host }, () => resolve(t))
        t.once('error', reject)
      })
      s = new Smtp(secure)
      ehlo = await s.cmd('EHLO myra', [250])
    }
    if (conf.user && conf.pass) {
      await s.cmd('AUTH LOGIN', [334])
      await s.cmd(Buffer.from(conf.user).toString('base64'), [334])
      await s.cmd(Buffer.from(conf.pass).toString('base64'), [235])
    }
    await s.cmd(`MAIL FROM:<${oneLine(from)}>`, [250])
    await s.cmd(`RCPT TO:<${oneLine(to)}>`, [250, 251])
    await s.cmd('DATA', [354])
    // Dot-stuffing: a line that starts with a dot is sent with an extra dot.
    const msg = buildMessage({ from, to, subject, body, attachments }).replace(/\r\n\./g, '\r\n..')
    s.write(msg + '.\r\n')
    const accepted = await s.line.reply()
    if (accepted.code !== 250) throw new Error(`The mail server did not accept the message: ${accepted.text.split('\n')[0].slice(0, 160)}`)
    try { await s.cmd('QUIT', [221]) } catch { /* the message is already accepted */ }
    finished = true
    return { ok: true }
  } finally {
    if (!finished) s.close()
    s.close()
  }
}

export function makeMail({ log, getConf, outDir }) {
  const gate = makeGate()
  const conf = () => getConf().email || null

  /** Attachments: plain file names inside the outputs folder only. */
  function attachmentsFor(name) {
    if (!name) return []
    const base = path.basename(String(name))
    if (base !== String(name) || base.startsWith('.')) throw new Error('The attachment must be the name of a file MYRA made, like "report.docx".')
    const full = path.join(outDir, base)
    if (!existsSync(full) || !statSync(full).isFile()) throw new Error(`I can't find "${base}" in the outputs folder. Make it first with the office tool.`)
    return [{ name: base, bytes: readFileSync(full) }]
  }

  const TOOLS = [
    {
      name: 'email_send',
      description: 'Send an email from Anuj\'s account. Two steps: the first call writes nothing and returns a sentence, so read the whole email back to Anuj (to, subject, message, attachment) and ask. Only after a clear yes call again with the same details and the confirm_token. attach is the name of a file made with office_make (for example "report.docx"), optional.',
      inputSchema: {
        type: 'object',
        properties: {
          to: { type: 'string', description: 'The address, e.g. boss@example.com.' },
          subject: { type: 'string' },
          body: { type: 'string', description: 'The whole message, plain text.' },
          attach: { type: 'string', description: 'Optional: a file name from office_make.' },
          confirm_token: { type: 'string', description: 'Only for the second call, after Anuj said yes.' },
        },
        required: ['to', 'subject', 'body'],
        additionalProperties: false,
      },
    },
    {
      name: 'email_status',
      description: 'Say whether email sending is set up, and from which address. Never reads or says the password.',
      inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    },
  ]

  const HANDLERS = {
    async email_send(args) {
      const to = String(args.to || '').trim()
      const subject = oneLine(args.subject)
      const body = String(args.body || '')
      if (!EMAIL.test(to)) return 'That email address does not look right. Ask Anuj for the correct address.'
      if (!subject) return 'The email needs a subject.'
      if (!body.trim()) return 'The email needs a message.'
      if (body.length > 20_000) return 'The message is too long. Shorten it.'
      const c = conf()
      if (!c?.host) return 'Email is not set up yet. Anuj has to add the email settings in the brain config first.'
      const attachments = attachmentsFor(args.attach)
      const payload = JSON.stringify({ to, subject, body, attach: args.attach || '' })
      const shown = body.length > 500 ? body.slice(0, 500) + '...' : body
      const sentence = `read this email back to him: to ${to}, subject "${subject}", message: "${shown}"${attachments.length ? `, with the file ${attachments[0].name}` : ''}. Send it?`
      const blocked = gate('email_send', payload, args.confirm_token, sentence)
      if (blocked) return blocked
      await smtpSend(c, { to, subject, body, attachments })
      log('email sent to', to.replace(/^(.).*(@.*)$/, '$1***$2'))
      return `Email sent to ${to}.`
    },
    async email_status() {
      const c = conf()
      if (!c?.host) return 'Email is not set up yet.'
      return `Email is set up. It sends from ${c.from || c.user} through ${c.host}.`
    },
  }

  return { TOOLS, HANDLERS }
}
