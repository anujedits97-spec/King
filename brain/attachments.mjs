/**
 * Attachments: any file you attach in the app (the paperclip button) is read here.
 *
 *   text, code, CSV, JSON, Markdown, logs ...... read as text (UTF-8, UTF-16 or Latin-1)
 *   Word / Excel / PowerPoint (docx xlsx pptx) .. text pulled out of the XML inside
 *   OpenDocument (odt ods odp), EPUB, RTF, HTML, Jupyter (ipynb)
 *   old Word / Excel / PowerPoint (doc xls ppt) . rough text only (binary format)
 *   images (jpg png gif webp) ................... saved; the AI looks at them (vision)
 *   anything else ............................... saved, with name, type and size, so MYRA
 *                                                 knows it is there and can move or list it
 *
 * PDFs are read in the app (PDFBox); scanned PDFs arrive here as page images.
 * Zips go to zips.mjs, not here.
 *
 * Safe by design: no dependencies, nothing inside a file is ever run, everything inside is
 * information and never instructions, size and entry limits on every archive, and files are
 * saved only under <workspace>/attachments (the newest MAX_KEEP are kept).
 */
import { existsSync, mkdirSync, readdirSync, readFileSync, realpathSync, rmSync, statSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { entryData, readEntries } from './zips.mjs'

const MAX_UPLOAD = 25 * 1024 * 1024
const MAX_ENTRY = 40 * 1024 * 1024 // one file inside an office zip, after unpacking
const MAX_TEXT = 15_000 // what goes into the question (the brain's request limit is 64 KB)
const MAX_FULL = 2_000_000 // the full text saved next to the file for later reading
const MAX_KEEP = 40
const MAX_SHEET_ROWS = 2000
const MAX_IMAGE = 5 * 1024 * 1024

const IMAGE_MIME = { '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.gif': 'image/gif', '.webp': 'image/webp' }

const TEXT_EXT = new Set(['.txt', '.md', '.markdown', '.rst', '.csv', '.tsv', '.json', '.jsonl', '.jsonc', '.log', '.xml', '.yml', '.yaml', '.toml', '.ini', '.cfg', '.conf', '.properties', '.env',
  '.js', '.mjs', '.cjs', '.ts', '.tsx', '.jsx', '.py', '.kt', '.kts', '.java', '.gradle', '.c', '.h', '.cpp', '.hpp', '.cs', '.go', '.rs', '.rb', '.php', '.swift', '.dart', '.lua', '.r', '.sql',
  '.sh', '.bash', '.bat', '.cmd', '.ps1', '.css', '.scss', '.vue', '.svelte', '.srt', '.vtt', '.tex', '.bib', '.gitignore', '.lock', '.svg', '.diff', '.patch', '.eml', '.ics', '.vcf', ''])

/* ── small helpers ───────────────────────────────────────────────────────── */

/** &amp; &lt; &gt; &quot; &apos; and numeric entities (&#169; &#xA9;) turned back into characters. */
export function decodeEntities(s) {
  return String(s).replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos|nbsp);/gi, (m, e) => {
    const k = e.toLowerCase()
    if (k === 'amp') return '&'
    if (k === 'lt') return '<'
    if (k === 'gt') return '>'
    if (k === 'quot') return '"'
    if (k === 'apos') return "'"
    if (k === 'nbsp') return ' '
    const n = k[1] === 'x' ? parseInt(k.slice(2), 16) : parseInt(k.slice(1), 10)
    try { return n > 0 && n <= 0x10ffff ? String.fromCodePoint(n) : '' } catch { return '' }
  })
}

const size = (n) => (n > 1 << 20 ? `${(n / (1 << 20)).toFixed(1)} MB` : n > 1024 ? `${Math.round(n / 1024)} KB` : `${n} B`)
const tidy = (s) => s.replace(/\r\n?/g, '\n').replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim()

/** UTF-8, or UTF-16 when there is a byte-order mark, or Latin-1 when the bytes are not valid UTF-8. */
export function decodeText(buf) {
  if (buf.length >= 2 && buf[0] === 0xff && buf[1] === 0xfe) return buf.toString('utf16le', 2)
  if (buf.length >= 2 && buf[0] === 0xfe && buf[1] === 0xff) return Buffer.from(buf.subarray(2)).swap16().toString('utf16le')
  const start = buf.length >= 3 && buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf ? 3 : 0
  try { return new TextDecoder('utf-8', { fatal: true }).decode(buf.subarray(start)) } catch { return buf.toString('latin1', start) }
}

/** True when the bytes look like text (no NUL bytes in the first 4 KB, except UTF-16 with a BOM). */
export function looksText(buf) {
  if (buf.length >= 2 && ((buf[0] === 0xff && buf[1] === 0xfe) || (buf[0] === 0xfe && buf[1] === 0xff))) return true
  return !buf.subarray(0, 4096).includes(0)
}

/** The files inside an office-style zip: name → reader. Refuses anything unreasonable. */
function openZip(buf) {
  const entries = readEntries(buf)
  const map = new Map()
  for (const e of entries) {
    const name = e.name.replace(/\\/g, '/')
    if (name.endsWith('/') || name.includes('..')) continue
    map.set(name, e)
  }
  return {
    names: [...map.keys()],
    has: (n) => map.has(n),
    text(n) {
      const e = map.get(n)
      if (!e) return ''
      if (e.flags & 1) throw new Error('That file is password-protected, so I can\'t read it.')
      if (e.usize > MAX_ENTRY) throw new Error(`A part of that file is too big to open (${size(e.usize)}).`)
      return entryData(buf, e).toString('utf8')
    },
  }
}

const numericSort = (a, b) => (Number(a.match(/(\d+)\.xml$/)?.[1]) || 0) - (Number(b.match(/(\d+)\.xml$/)?.[1]) || 0)

/* ── Word ────────────────────────────────────────────────────────────────── */

/** Text out of WordprocessingML: paragraphs, tabs, line breaks, table cells and rows. */
export function wordXmlToText(xml) {
  let out = '', heading = '', inCell = false
  const re = /<w:t(?:\s[^>]*)?>([^<]*)<\/w:t>|<w:tab\s*\/>|<w:(?:br|cr)\b[^>]*\/>|<\/w:p>|<w:tc>|<w:tc\s[^>]*>|<\/w:tc>|<\/w:tr>|<w:pStyle w:val="(Heading\d|Title)"\s*\/>/g
  for (const m of xml.matchAll(re)) {
    const tok = m[0]
    if (m[1] !== undefined) out += decodeEntities(m[1])
    else if (m[2]) heading = m[2]
    else if (tok.startsWith('<w:tab')) out += '\t'
    else if (tok.startsWith('<w:br') || tok.startsWith('<w:cr')) out += inCell ? ' ' : '\n'
    else if (tok === '</w:p>') {
      if (heading) { const lvl = heading === 'Title' ? 1 : Number(heading.slice(7)) || 1; out = out.replace(/([^\n]*)$/, (line) => (line.trim() ? `${'#'.repeat(Math.min(lvl, 6))} ${line.trim()}` : line)); heading = '' }
      out += inCell ? ' ' : '\n'
    } else if (tok.startsWith('<w:tc')) inCell = true
    else if (tok === '</w:tc>') { inCell = false; out = out.replace(/[ \t]+$/, '') + '\t' }
    else if (tok === '</w:tr>') out = out.replace(/\t+$/, '') + '\n'
  }
  return out
}

function docxText(zip) {
  if (!zip.has('word/document.xml')) throw new Error('This .docx has no document body.')
  let out = wordXmlToText(zip.text('word/document.xml'))
  const extra = zip.names.filter((n) => /^word\/(footnotes|endnotes)\.xml$/.test(n))
  for (const n of extra) {
    const t = tidy(wordXmlToText(zip.text(n)))
    if (t) out += `\n\n## ${n.includes('foot') ? 'Footnotes' : 'Endnotes'}\n${t}`
  }
  return out
}

/* ── Excel ───────────────────────────────────────────────────────────────── */

const colIndex = (ref) => { let n = 0; for (const ch of String(ref).replace(/[^A-Za-z]/g, '').toUpperCase()) n = n * 26 + ch.charCodeAt(0) - 64; return Math.max(0, n - 1) }
const attr = (s, name) => s.match(new RegExp(`(?:^|\\s)${name}="([^"]*)"`))?.[1]

function xlsxText(zip) {
  // Shared strings: each <si> is one string, maybe split into rich-text runs. Phonetic hints are skipped.
  const shared = []
  if (zip.has('xl/sharedStrings.xml')) {
    for (const si of zip.text('xl/sharedStrings.xml').matchAll(/<si\b[^>]*>([\s\S]*?)<\/si>/g)) {
      const body = si[1].replace(/<rPh\b[\s\S]*?<\/rPh>/g, '')
      shared.push(decodeEntities([...body.matchAll(/<t(?:\s[^>]*)?>([^<]*)<\/t>/g)].map((m) => m[1]).join('')))
    }
  }
  // Sheet names, in order, and which file each one is.
  const rels = new Map()
  if (zip.has('xl/_rels/workbook.xml.rels')) {
    for (const m of zip.text('xl/_rels/workbook.xml.rels').matchAll(/<Relationship\b([^>]*)\/?>/g)) {
      const id = attr(m[1], 'Id'), target = attr(m[1], 'Target')
      if (id && target) rels.set(id, target.startsWith('/') ? target.slice(1) : `xl/${target}`.replace(/xl\/\.\.\//, ''))
    }
  }
  const sheets = []
  if (zip.has('xl/workbook.xml')) {
    for (const m of zip.text('xl/workbook.xml').matchAll(/<sheet\b([^>]*)\/?>/g)) {
      const rid = attr(m[1], 'r:id')
      const file = rels.get(rid)
      if (file && zip.has(file)) sheets.push({ name: decodeEntities(attr(m[1], 'name') || 'Sheet'), file, hidden: /state="(hidden|veryHidden)"/.test(m[1]) })
    }
  }
  if (!sheets.length) zip.names.filter((n) => /^xl\/worksheets\/sheet\d+\.xml$/.test(n)).sort(numericSort).forEach((file, i) => sheets.push({ name: `Sheet${i + 1}`, file }))
  if (!sheets.length) throw new Error('This .xlsx has no sheets I can read.')

  const parts = []
  for (const sh of sheets) {
    const xml = zip.text(sh.file)
    const rows = []
    let rowCount = 0
    for (const r of xml.matchAll(/<row\b[^>]*?(?:\/>|>([\s\S]*?)<\/row>)/g)) {
      if (!r[1]) continue
      if (++rowCount > MAX_SHEET_ROWS) break
      const cells = []
      for (const c of r[1].matchAll(/<c\b([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g)) {
        const ref = attr(c[1], 'r'), t = attr(c[1], 't')
        const inner = c[2] || ''
        let v = inner.match(/<v>([^<]*)<\/v>/)?.[1]
        let val = ''
        if (t === 's') val = shared[Number(v)] ?? ''
        else if (t === 'inlineStr') val = decodeEntities([...inner.matchAll(/<t(?:\s[^>]*)?>([^<]*)<\/t>/g)].map((m) => m[1]).join(''))
        else if (t === 'b') val = v === '1' ? 'TRUE' : 'FALSE'
        else if (v !== undefined) val = decodeEntities(v)
        const f = inner.match(/<f(?:\s[^>]*)?>([^<]*)<\/f>/)?.[1]
        if (f && val === '') val = `=${decodeEntities(f)}`
        const i = ref ? colIndex(ref) : cells.length
        while (cells.length < i) cells.push('')
        cells[i] = val.replace(/[\t\r\n]+/g, ' ')
      }
      while (cells.length && cells[cells.length - 1] === '') cells.pop()
      rows.push(cells.join('\t'))
    }
    while (rows.length && !rows[rows.length - 1]) rows.pop()
    parts.push(`## Sheet: ${sh.name}${sh.hidden ? ' (hidden)' : ''}\n${rows.join('\n') || '(empty)'}${rowCount > MAX_SHEET_ROWS ? `\n… (only the first ${MAX_SHEET_ROWS} rows are read)` : ''}`)
  }
  return parts.join('\n\n')
}

/* ── PowerPoint ──────────────────────────────────────────────────────────── */

const drawingXmlToText = (xml) => {
  let out = ''
  for (const m of xml.matchAll(/<a:t>([^<]*)<\/a:t>|<a:br\s*\/>|<\/a:p>/g)) out += m[1] !== undefined ? decodeEntities(m[1]) : '\n'
  return tidy(out)
}

function pptxText(zip) {
  const slides = zip.names.filter((n) => /^ppt\/slides\/slide\d+\.xml$/.test(n)).sort(numericSort)
  if (!slides.length) throw new Error('This .pptx has no slides I can read.')
  return slides.map((n, i) => {
    const num = Number(n.match(/(\d+)\.xml$/)[1])
    let s = `## Slide ${i + 1}\n${drawingXmlToText(zip.text(n))}`
    const notes = `ppt/notesSlides/notesSlide${num}.xml`
    if (zip.has(notes)) { const t = drawingXmlToText(zip.text(notes)).replace(/^\d+$/gm, '').trim(); if (t) s += `\n\nSpeaker notes: ${t}` }
    return s
  }).join('\n\n')
}

/* ── OpenDocument, EPUB, HTML, RTF, notebooks ────────────────────────────── */

function odfText(zip) {
  if (!zip.has('content.xml')) throw new Error('This file has no content I can read.')
  const xml = zip.text('content.xml')
    .replace(/<text:tab\s*\/>/g, '\t').replace(/<text:line-break\s*\/>/g, '\n').replace(/<text:s(?:\s+text:c="(\d+)")?\s*\/>/g, (m, n) => ' '.repeat(Number(n) || 1))
    .replace(/<\/text:(?:p|h)>/g, '\n').replace(/<\/table:table-cell>/g, '\t').replace(/<\/table:table-row>/g, '\n').replace(/<\/draw:page>/g, '\n\n')
    .replace(/<[^>]+>/g, '')
  return decodeEntities(xml)
}

export function htmlToText(html) {
  return decodeEntities(String(html)
    .replace(/<(script|style|head|svg|noscript)\b[\s\S]*?<\/\1>/gi, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<h([1-6])\b[^>]*>/gi, (m, n) => `\n\n${'#'.repeat(Number(n))} `)
    .replace(/<li\b[^>]*>/gi, '\n- ').replace(/<br\s*\/?>/gi, '\n').replace(/<\/t[dh]>/gi, '\t').replace(/<\/tr>/gi, '\n')
    .replace(/<\/(p|div|section|article|ul|ol|table|blockquote|pre|h[1-6])>/gi, '\n\n')
    .replace(/<[^>]+>/g, ''))
}

function epubText(zip) {
  let order = []
  try {
    const container = zip.text('META-INF/container.xml')
    const opfPath = attr(container.match(/<rootfile\b([^>]*)\/?>/)?.[1] || '', 'full-path')
    if (opfPath && zip.has(opfPath)) {
      const opf = zip.text(opfPath)
      const dir = opfPath.includes('/') ? opfPath.slice(0, opfPath.lastIndexOf('/') + 1) : ''
      const manifest = new Map()
      for (const m of opf.matchAll(/<item\b([^>]*)\/?>/g)) { const id = attr(m[1], 'id'), href = attr(m[1], 'href'); if (id && href) manifest.set(id, decodeURIComponent(dir + href)) }
      order = [...opf.matchAll(/<itemref\b([^>]*)\/?>/g)].map((m) => manifest.get(attr(m[1], 'idref'))).filter((n) => n && zip.has(n))
    }
  } catch { /* fall back to file order */ }
  if (!order.length) order = zip.names.filter((n) => /\.(xhtml|html|htm)$/i.test(n)).sort()
  if (!order.length) throw new Error('This ebook has no chapters I can read.')
  let out = '', total = 0
  for (const n of order) { const t = tidy(htmlToText(zip.text(n))); if (t) { out += `${t}\n\n`; total += t.length } if (total > MAX_FULL) break }
  return out
}

/** RTF without its control words: handles \'hh, \uN, \par, \tab and skips font tables and other non-text groups. */
export function rtfToText(src) {
  const s = String(src)
  const SKIP = new Set(['fonttbl', 'colortbl', 'stylesheet', 'info', 'pict', 'header', 'footer', 'footnote', 'themedata', 'colorschememapping', 'latentstyles', 'datastore', 'listtable', 'listoverridetable', 'rsidtbl', 'generator'])
  let out = '', i = 0, depth = 0, skipDepth = -1, uc = 1
  const stack = []
  while (i < s.length) {
    const ch = s[i]
    if (ch === '{') { stack.push(uc); depth++; i++; if (s[i] === '\\' && s[i + 1] === '*') { if (skipDepth < 0) skipDepth = depth; i += 2 } continue }
    if (ch === '}') { uc = stack.pop() ?? 1; if (skipDepth === depth) skipDepth = -1; depth--; i++; continue }
    if (ch === '\\') {
      const m = s.slice(i + 1, i + 40).match(/^([a-zA-Z]+)(-?\d+)? ?/)
      if (m) {
        const word = m[1], arg = m[2] !== undefined ? Number(m[2]) : undefined
        i += 1 + m[0].length
        if (SKIP.has(word) && skipDepth < 0) { skipDepth = depth; continue }
        if (skipDepth >= 0) continue
        if (word === 'par' || word === 'line' || word === 'row') out += '\n'
        else if (word === 'tab' || word === 'cell') out += '\t'
        else if (word === 'u' && arg !== undefined) { out += String.fromCharCode(arg < 0 ? arg + 65536 : arg); i += uc } // skip the fallback character
        else if (word === 'uc' && arg !== undefined) uc = arg
        else if (word === 'emdash') out += '—'
        else if (word === 'endash') out += '–'
        else if (word === 'bullet') out += '•'
        else if (word === 'lquote') out += '‘'
        else if (word === 'rquote') out += '’'
        else if (word === 'ldblquote') out += '“'
        else if (word === 'rdblquote') out += '”'
        continue
      }
      const n = s[i + 1]
      if (n === "'") { if (skipDepth < 0) out += Buffer.from([parseInt(s.slice(i + 2, i + 4), 16) || 63]).toString('latin1'); i += 4; continue }
      if (n === '\\' || n === '{' || n === '}') { if (skipDepth < 0) out += n; i += 2; continue }
      if (n === '~') { if (skipDepth < 0) out += ' '; i += 2; continue }
      i += 2
      continue
    }
    if (skipDepth < 0 && ch !== '\n' && ch !== '\r') out += ch
    i++
  }
  return out
}

function ipynbText(buf) {
  const nb = JSON.parse(decodeText(buf))
  const lang = nb.metadata?.language_info?.name || nb.metadata?.kernelspec?.language || ''
  const src = (c) => (Array.isArray(c.source) ? c.source.join('') : String(c.source || ''))
  return (nb.cells || []).map((c, i) => {
    if (c.cell_type === 'markdown') return src(c)
    let s = `\`\`\`${lang}\n${src(c)}\n\`\`\``
    const outs = (c.outputs || []).map((o) => (Array.isArray(o.text) ? o.text.join('') : o.text) || (Array.isArray(o.data?.['text/plain']) ? o.data['text/plain'].join('') : o.data?.['text/plain']) || '').filter(Boolean)
    if (outs.length) s += `\nOutput:\n${outs.join('\n').slice(0, 1500)}`
    return `# Cell ${i + 1}\n${s}`
  }).join('\n\n')
}

/** Old binary Office files (doc, xls, ppt): the readable runs of characters, nothing more. */
function roughStrings(buf) {
  const runs = []
  const ascii = buf.toString('latin1').match(/[\x20-\x7e\xa0-\xff\t\r\n]{6,}/g) || []
  for (const r of ascii) runs.push(r)
  const u16 = buf.toString('utf16le').match(/[\x20-\x7e\u00a0-\u024f\u0900-\u097f\u2018-\u201d\u2013\u2014\t\r\n]{6,}/g) || []
  for (const r of u16) runs.push(r)
  return runs.map((r) => r.trim()).filter((r) => /[A-Za-z\u0900-\u097f]{3,}/.test(r)).join('\n')
}

/* ── what kind of file is this? ──────────────────────────────────────────── */

/** { kind, ext } from the name, and from the contents when the name is no help. */
export function sniff(buf, name) {
  const ext = path.extname(String(name)).toLowerCase()
  if (IMAGE_MIME[ext]) return { kind: 'image', ext }
  if (buf.subarray(0, 3).toString('hex') === 'ffd8ff' || buf.subarray(0, 8).toString('hex') === '89504e470d0a1a0a' || /^GIF8[79]a/.test(buf.subarray(0, 6).toString('latin1')) || (buf.subarray(0, 4).toString('latin1') === 'RIFF' && buf.subarray(8, 12).toString('latin1') === 'WEBP')) return { kind: 'image', ext: ext || '.jpg' }
  if (['.docx', '.docm', '.dotx'].includes(ext)) return { kind: 'docx', ext }
  if (['.xlsx', '.xlsm', '.xltx'].includes(ext)) return { kind: 'xlsx', ext }
  if (['.pptx', '.pptm', '.potx', '.ppsx'].includes(ext)) return { kind: 'pptx', ext }
  if (['.odt', '.ods', '.odp', '.fodt'].includes(ext)) return { kind: 'odf', ext }
  if (ext === '.epub') return { kind: 'epub', ext }
  if (ext === '.rtf' || buf.subarray(0, 5).toString('latin1') === '{\\rtf') return { kind: 'rtf', ext }
  if (['.html', '.htm', '.xhtml', '.mhtml'].includes(ext)) return { kind: 'html', ext }
  if (ext === '.ipynb') return { kind: 'ipynb', ext }
  if (['.doc', '.xls', '.ppt', '.dot', '.pps'].includes(ext) && buf.subarray(0, 4).toString('hex') === 'd0cf11e0') return { kind: 'ole', ext }
  if (buf.subarray(0, 4).toString('latin1') === 'PK\x03\x04') {
    try { // right type, wrong or missing extension
      const z = openZip(buf)
      if (z.has('word/document.xml')) return { kind: 'docx', ext }
      if (z.has('xl/workbook.xml')) return { kind: 'xlsx', ext }
      if (z.has('ppt/presentation.xml')) return { kind: 'pptx', ext }
      if (z.has('content.xml') && z.has('mimetype')) return { kind: 'odf', ext }
      if (z.has('META-INF/container.xml')) return { kind: 'epub', ext }
    } catch { /* just a file */ }
    return { kind: 'binary', ext }
  }
  if (buf.subarray(0, 4).toString('latin1') === '%PDF') return { kind: 'pdf', ext }
  if (TEXT_EXT.has(ext) && looksText(buf)) return { kind: 'text', ext }
  if (looksText(buf)) return { kind: 'text', ext } // unknown extension, but it reads as text
  return { kind: 'binary', ext }
}

/** Pulls the text out of a file. Returns { kind, text, rough? }. Throws with a message the user can read. */
export function extract(buf, name) {
  const { kind } = sniff(buf, name)
  switch (kind) {
    case 'text': return { kind, text: decodeText(buf) }
    case 'docx': return { kind, text: docxText(openZip(buf)) }
    case 'xlsx': return { kind, text: xlsxText(openZip(buf)) }
    case 'pptx': return { kind, text: pptxText(openZip(buf)) }
    case 'odf': return { kind, text: odfText(openZip(buf)) }
    case 'epub': return { kind, text: epubText(openZip(buf)) }
    case 'rtf': return { kind, text: rtfToText(decodeText(buf)) }
    case 'html': return { kind, text: htmlToText(decodeText(buf)) }
    case 'ipynb': return { kind, text: ipynbText(buf) }
    case 'ole': return { kind, text: roughStrings(buf), rough: true }
    default: return { kind, text: '' }
  }
}

/* ── the upload ──────────────────────────────────────────────────────────── */

export function makeAttachments({ getConf, log = () => {} }) {
  const dir = () => path.join(getConf().workspace || path.join(os.homedir(), 'myra-workspace'), 'attachments')

  const safeName = (raw) => {
    const base = path.basename(String(raw || 'file').replace(/\\/g, '/')).replace(/[^\p{L}\p{N}._ -]+/gu, '_').replace(/^[.\s]+/, '').slice(0, 80)
    return base || 'file'
  }

  function prune() {
    try {
      const all = readdirSync(dir(), { withFileTypes: true }).filter((e) => e.isFile()).map((e) => ({ f: path.join(dir(), e.name), t: statSync(path.join(dir(), e.name)).mtimeMs })).sort((a, b) => b.t - a.t)
      for (const old of all.slice(MAX_KEEP * 2)) rmSync(old.f, { force: true }) // each attachment can have a .txt beside it
    } catch { /* nothing to prune */ }
  }

  /** Reads the body of POST /attach and answers { kind, name, path, text, truncated, ... }. */
  async function ingest(req, rawName) {
    const len = Number(req.headers['content-length'] || 0)
    if (len > MAX_UPLOAD) throw new Error(`That file is ${size(len)}. The limit is ${size(MAX_UPLOAD)}.`)
    const chunks = []
    let got = 0
    await new Promise((resolve, reject) => {
      req.on('data', (c) => { got += c.length; if (got > MAX_UPLOAD) { reject(new Error(`That file is bigger than ${size(MAX_UPLOAD)}.`)); req.destroy(); return } chunks.push(c) })
      req.on('end', resolve)
      req.on('error', reject)
    })
    const buf = Buffer.concat(chunks)
    if (!buf.length) throw new Error('That file is empty.')
    return save(buf, rawName)
  }

  function save(buf, rawName) {
    const name = safeName(rawName)
    const info = sniff(buf, name)
    mkdirSync(dir(), { recursive: true, mode: 0o700 })
    const stamp = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`
    const file = path.join(dir(), `${stamp}-${name}`)

    if (info.kind === 'pdf') throw new Error('PDFs are read in the app. If this one has no text, the app sends its pages as pictures.')
    if (info.kind === 'image') {
      if (buf.length > MAX_IMAGE) throw new Error(`That picture is ${size(buf.length)}. The limit is ${size(MAX_IMAGE)}.`)
      writeFileSync(file, buf, { mode: 0o600 })
      prune()
      return { kind: 'image', name, path: file, bytes: buf.length }
    }
    writeFileSync(file, buf, { mode: 0o600 })
    let result
    try { result = extract(buf, name) } catch (e) { rmSync(file, { force: true }); throw e }
    prune()
    if (result.kind === 'binary') {
      return { kind: 'file', name, path: file, bytes: buf.length, note: `I can't read what is inside ${path.extname(name) || 'this kind of'} files (it isn't text, a document or a picture), but it is saved on the phone.` }
    }
    const text = tidy(result.text)
    if (!text) {
      const why = result.kind === 'ole' ? 'No readable text found. Saving it as .docx / .xlsx / .pptx first usually works.' : `No readable text in ${name}. It may only hold pictures.`
      return { kind: 'file', name, path: file, bytes: buf.length, note: why }
    }
    const out = { kind: 'text', format: result.kind, name, path: file, bytes: buf.length, chars: text.length, text: text.slice(0, MAX_TEXT), truncated: text.length > MAX_TEXT }
    if (result.rough) out.note = 'This is an old binary Office file, so the text is rough. For exact text save it as .docx / .xlsx / .pptx.'
    if (out.truncated) { // the full text stays beside the file so MYRA can read on with the file tools
      const full = `${file}.txt`
      writeFileSync(full, text.slice(0, MAX_FULL), { mode: 0o600 })
      out.fullTextPath = full
    }
    return out
  }

  /** An image the app attached, ready for a vision AI: { mime, data (base64) }, or null. Only files this module saved. */
  function image(p) {
    try {
      const root = realpathSync(dir())
      const real = realpathSync(String(p))
      if (!real.startsWith(root + path.sep)) return null
      const mime = IMAGE_MIME[path.extname(real).toLowerCase()]
      if (!mime) return null
      const st = statSync(real)
      if (!st.isFile() || st.size > MAX_IMAGE) return null
      return { mime, data: readFileSync(real).toString('base64'), path: real }
    } catch { return null }
  }

  return { ingest, save, image, dir }
}

/** The image markers in a question: [[image:/path]]. */
export const IMAGE_MARK = /\[\[image:([^\]\n]+)\]\]/g
