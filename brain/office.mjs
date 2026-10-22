/**
 * Office files made by voice: Word (.docx), Excel (.xlsx), PowerPoint (.pptx) and PDF.
 *
 * No npm packages: the phone's brain (Termux) has only Node, so each format is written as the plain
 * XML it is made of, zipped with Node's own zlib. The files are checked with python-docx, openpyxl,
 * python-pptx and pypdf in the test suite, so they open in Word, Excel, PowerPoint and PDF readers.
 *
 * Content is plain text only. Excel formulas start with "=". PDF uses the standard Helvetica font,
 * so text outside Latin letters (Hindi, Devanagari, emoji) is replaced with "?".
 */
import { deflateRawSync } from 'node:zlib'
import { mkdirSync, existsSync, writeFileSync, copyFileSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'

// ── zip ──────────────────────────────────────────────────────────────────────

const CRC_TABLE = (() => {
  const t = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c >>> 0
  }
  return t
})()

export function crc32(buf) {
  let c = 0xffffffff
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

/** A zip archive from { name: string | Buffer }. Entries are deflated; names are UTF-8. */
export function makeZip(files) {
  const locals = []
  const central = []
  let offset = 0
  for (const [name, content] of Object.entries(files)) {
    const data = Buffer.isBuffer(content) ? content : Buffer.from(String(content), 'utf8')
    const packed = deflateRawSync(data)
    const nameBuf = Buffer.from(name, 'utf8')
    const crc = crc32(data)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)          // version needed
    local.writeUInt16LE(0x0800, 6)      // flags: UTF-8 names
    local.writeUInt16LE(8, 8)           // deflate
    local.writeUInt16LE(0, 10)          // time
    local.writeUInt16LE(0x21, 12)       // date: 1980-01-01
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(packed.length, 18)
    local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(nameBuf.length, 26)
    local.writeUInt16LE(0, 28)
    const rec = Buffer.concat([local, nameBuf, packed])
    locals.push(rec)
    const cd = Buffer.alloc(46)
    cd.writeUInt32LE(0x02014b50, 0)
    cd.writeUInt16LE(20, 4)
    cd.writeUInt16LE(20, 6)
    cd.writeUInt16LE(0x0800, 8)
    cd.writeUInt16LE(8, 10)
    cd.writeUInt16LE(0, 12)
    cd.writeUInt16LE(0x21, 14)
    cd.writeUInt32LE(crc, 16)
    cd.writeUInt32LE(packed.length, 20)
    cd.writeUInt32LE(data.length, 24)
    cd.writeUInt16LE(nameBuf.length, 28)
    cd.writeUInt16LE(0, 30)
    cd.writeUInt16LE(0, 32)
    cd.writeUInt16LE(0, 34)
    cd.writeUInt16LE(0, 36)
    cd.writeUInt32LE(0, 38)
    cd.writeUInt32LE(offset, 42)
    central.push(Buffer.concat([cd, nameBuf]))
    offset += rec.length
  }
  const cdBuf = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(central.length, 8)
  end.writeUInt16LE(central.length, 10)
  end.writeUInt32LE(cdBuf.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...locals, cdBuf, end])
}

// ── helpers ──────────────────────────────────────────────────────────────────

const esc = (s) => String(s ?? '')
  .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '')
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;').replace(/'/g, '&apos;')

const XML_HEAD = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
const REL_NS = 'http://schemas.openxmlformats.org/package/2006/relationships'
const OFFICE_REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'

const rels = (list) => XML_HEAD + `<Relationships xmlns="${REL_NS}">` +
  list.map((r, i) => `<Relationship Id="${r.id || 'rId' + (i + 1)}" Type="${r.type}" Target="${r.target}"/>`).join('') + '</Relationships>'

const ROOT_RELS = (target) => rels([{ id: 'rId1', type: `${OFFICE_REL}/officeDocument`, target }])

const ct = (defaults, overrides) => XML_HEAD + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
  Object.entries(defaults).map(([ext, type]) => `<Default Extension="${ext}" ContentType="${type}"/>`).join('') +
  overrides.map(([part, type]) => `<Override PartName="${part}" ContentType="${type}"/>`).join('') + '</Types>'

/** Excel: sheet names are at most 31 characters and may not hold []:*?/\ */
export function safeSheetName(name, used = new Set()) {
  let base = String(name || 'Sheet').replace(/[\[\]:*?\/\\]/g, ' ').trim().slice(0, 31) || 'Sheet'
  let out = base
  for (let i = 2; used.has(out.toLowerCase()); i++) out = `${base.slice(0, 31 - String(i).length - 1)} ${i}`
  used.add(out.toLowerCase())
  return out
}

const colName = (i) => { let s = ''; for (let n = i + 1; n > 0; n = Math.floor((n - 1) / 26)) s = String.fromCharCode(65 + ((n - 1) % 26)) + s; return s }

// ── Word ─────────────────────────────────────────────────────────────────────

/**
 * blocks: [{ type: 'h1'|'h2'|'p'|'bullet'|'table', text?, rows? }]. Tables take rows: string[][]; the first row is the header.
 */
export function docxFiles(blocks, title = '') {
  const run = (text, { bold = false, size = 22 } = {}) =>
    `<w:r><w:rPr>${bold ? '<w:b/>' : ''}<w:sz w:val="${size}"/><w:szCs w:val="${size}"/></w:rPr><w:t xml:space="preserve">${esc(text)}</w:t></w:r>`
  const para = (inner, ppr = '') => `<w:p><w:pPr>${ppr}</w:pPr>${inner}</w:p>`
  const parts = []
  if (title) parts.push(para(run(title, { bold: true, size: 36 }), '<w:spacing w:after="240"/>'))
  for (const b of blocks || []) {
    if (b.type === 'h1') parts.push(para(run(b.text, { bold: true, size: 32 }), '<w:keepNext/><w:spacing w:before="240" w:after="120"/>'))
    else if (b.type === 'h2') parts.push(para(run(b.text, { bold: true, size: 26 }), '<w:keepNext/><w:spacing w:before="200" w:after="80"/>'))
    else if (b.type === 'bullet') parts.push(para(run('\u2022  ' + b.text), '<w:spacing w:after="60"/><w:ind w:left="360" w:hanging="360"/>'))
    else if (b.type === 'table' && Array.isArray(b.rows) && b.rows.length) {
      const cols = Math.max(...b.rows.map((r) => r.length))
      const width = Math.floor(9000 / cols)
      const border = (n) => `<w:${n} w:val="single" w:sz="4" w:space="0" w:color="999999"/>`
      const rowsXml = b.rows.map((r, ri) => '<w:tr>' + Array.from({ length: cols }, (_, ci) =>
        `<w:tc><w:tcPr><w:tcW w:w="${width}" w:type="dxa"/>${ri === 0 ? '<w:shd w:val="clear" w:color="auto" w:fill="E7ECF5"/>' : ''}</w:tcPr>` +
        para(run(r[ci] ?? '', { bold: ri === 0 }), '<w:spacing w:before="40" w:after="40"/>') + '</w:tc>').join('') + '</w:tr>').join('')
      parts.push(`<w:tbl><w:tblPr><w:tblW w:w="${width * cols}" w:type="dxa"/><w:tblBorders>${['top', 'left', 'bottom', 'right', 'insideH', 'insideV'].map(border).join('')}</w:tblBorders></w:tblPr><w:tblGrid>${Array.from({ length: cols }, () => `<w:gridCol w:w="${width}"/>`).join('')}</w:tblGrid>${rowsXml}</w:tbl>`)
      parts.push(para(''))
    } else if (b.text !== undefined) parts.push(para(run(b.text), '<w:spacing w:after="120"/>'))
  }
  if (!parts.length) parts.push(para(''))
  const doc = XML_HEAD + '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>' +
    parts.join('') +
    '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="567" w:footer="567" w:gutter="0"/></w:sectPr></w:body></w:document>'
  return {
    '[Content_Types].xml': ct({ rels: 'application/vnd.openxmlformats-package.relationships+xml', xml: 'application/xml' },
      [['/word/document.xml', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml']]),
    '_rels/.rels': ROOT_RELS('word/document.xml'),
    'word/document.xml': doc,
  }
}

// ── Excel ────────────────────────────────────────────────────────────────────

/** sheets: [{ name, rows: (string|number|null)[][] }]. Text starting with "=" becomes a formula. The first row is bold. */
export function xlsxFiles(sheets) {
  const list = (sheets && sheets.length ? sheets : [{ name: 'Sheet1', rows: [] }])
  const used = new Set()
  const named = list.map((s) => ({ name: safeSheetName(s.name, used), rows: Array.isArray(s.rows) ? s.rows : [] }))
  const files = {}
  named.forEach((s, si) => {
    const widths = []
    const body = s.rows.map((row, ri) => {
      const cells = (Array.isArray(row) ? row : []).map((v, ci) => {
        const ref = colName(ci) + (ri + 1)
        const bold = ri === 0 ? ' s="1"' : ''
        const len = String(v ?? '').length
        widths[ci] = Math.max(widths[ci] || 8, Math.min(60, len + 3))
        if (v === null || v === undefined || v === '') return ''
        if (typeof v === 'number' && Number.isFinite(v)) return `<c r="${ref}"${bold}><v>${v}</v></c>`
        const text = String(v)
        if (text.startsWith('=')) return `<c r="${ref}"${bold}><f>${esc(text.slice(1))}</f></c>`
        return `<c r="${ref}" t="inlineStr"${bold}><is><t xml:space="preserve">${esc(text)}</t></is></c>`
      }).join('')
      return `<row r="${ri + 1}">${cells}</row>`
    }).join('')
    const cols = widths.length ? '<cols>' + widths.map((w, i) => `<col min="${i + 1}" max="${i + 1}" width="${w || 10}" customWidth="1"/>`).join('') + '</cols>' : ''
    files[`xl/worksheets/sheet${si + 1}.xml`] = XML_HEAD + '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
      `<sheetViews><sheetView workbookViewId="0"${si === 0 ? ' tabSelected="1"' : ''}/></sheetViews>` + '<sheetFormatPr defaultRowHeight="15"/>' + cols +
      `<sheetData>${body}</sheetData></worksheet>`
  })
  files['xl/workbook.xml'] = XML_HEAD + '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>' +
    named.map((s, i) => `<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('') +
    '</sheets><calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>'
  files['xl/_rels/workbook.xml.rels'] = rels([
    ...named.map((_, i) => ({ id: `rId${i + 1}`, type: `${OFFICE_REL}/worksheet`, target: `worksheets/sheet${i + 1}.xml` })),
    { id: `rId${named.length + 1}`, type: `${OFFICE_REL}/styles`, target: 'styles.xml' },
  ])
  files['xl/styles.xml'] = XML_HEAD + '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
    '<fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><sz val="11"/><name val="Arial"/></font></fonts>' +
    '<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>' +
    '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>' +
    '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>' +
    '<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>' +
    '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>' +
    '</styleSheet>'
  files['[Content_Types].xml'] = ct({ rels: 'application/vnd.openxmlformats-package.relationships+xml', xml: 'application/xml' }, [
    ['/xl/workbook.xml', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml'],
    ...named.map((_, i) => [`/xl/worksheets/sheet${i + 1}.xml`, 'application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml']),
    ['/xl/styles.xml', 'application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml'],
  ])
  files['_rels/.rels'] = ROOT_RELS('xl/workbook.xml')
  return files
}

// ── PowerPoint ───────────────────────────────────────────────────────────────

const PNS = 'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"'

const THEME = XML_HEAD + '<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="MYRA"><a:themeElements>' +
  '<a:clrScheme name="MYRA"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>' +
  '<a:dk2><a:srgbClr val="1F2A44"/></a:dk2><a:lt2><a:srgbClr val="E7ECF5"/></a:lt2>' +
  '<a:accent1><a:srgbClr val="2F5597"/></a:accent1><a:accent2><a:srgbClr val="C55A11"/></a:accent2><a:accent3><a:srgbClr val="548235"/></a:accent3>' +
  '<a:accent4><a:srgbClr val="7030A0"/></a:accent4><a:accent5><a:srgbClr val="2E75B6"/></a:accent5><a:accent6><a:srgbClr val="BF9000"/></a:accent6>' +
  '<a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>' +
  '<a:fontScheme name="MYRA"><a:majorFont><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>' +
  '<a:fmtScheme name="MYRA"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>' +
  '<a:lnStyleLst><a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="28575"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>' +
  '<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>' +
  '<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst>' +
  '</a:fmtScheme></a:themeElements></a:theme>'

const LAYOUT = XML_HEAD + `<p:sldLayout ${PNS} type="titleAndBody" preserve="1"><p:cSld name="Title and content"><p:spTree>` +
  '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>' +
  '<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title 1"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>' +
  '<p:spPr><a:xfrm><a:off x="457200" y="228600"/><a:ext cx="8229600" cy="857250"/></a:xfrm></p:spPr>' +
  '<p:txBody><a:bodyPr anchor="ctr"/><a:lstStyle/><a:p><a:endParaRPr lang="en-US"/></a:p></p:txBody></p:sp>' +
  '<p:sp><p:nvSpPr><p:cNvPr id="3" name="Content 2"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr>' +
  '<p:spPr><a:xfrm><a:off x="457200" y="1200150"/><a:ext cx="8229600" cy="3600000"/></a:xfrm></p:spPr>' +
  '<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="en-US"/></a:p></p:txBody></p:sp>' +
  '</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>'

const MASTER = XML_HEAD + `<p:sldMaster ${PNS}><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree>` +
  '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>' +
  '<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>' +
  '<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>' +
  '<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="3200" b="1"><a:solidFill><a:schemeClr val="tx2"/></a:solidFill></a:defRPr></a:lvl1pPr></p:titleStyle>' +
  '<p:bodyStyle><a:lvl1pPr><a:defRPr sz="2000"/></a:lvl1pPr></p:bodyStyle><p:otherStyle><a:defPPr/></p:otherStyle></p:txStyles></p:sldMaster>'

/** slides: [{ title, bullets: string[] }] */
export function pptxFiles(slides, title = '') {
  const list = (slides && slides.length) ? slides : [{ title: title || 'Slide 1', bullets: [] }]
  const files = {
    'ppt/theme/theme1.xml': THEME,
    'ppt/slideLayouts/slideLayout1.xml': LAYOUT,
    'ppt/slideLayouts/_rels/slideLayout1.xml.rels': rels([{ id: 'rId1', type: `${OFFICE_REL}/slideMaster`, target: '../slideMasters/slideMaster1.xml' }]),
    'ppt/slideMasters/slideMaster1.xml': MASTER,
    'ppt/slideMasters/_rels/slideMaster1.xml.rels': rels([
      { id: 'rId1', type: `${OFFICE_REL}/slideLayout`, target: '../slideLayouts/slideLayout1.xml' },
      { id: 'rId2', type: `${OFFICE_REL}/theme`, target: '../theme/theme1.xml' },
    ]),
  }
  list.forEach((s, i) => {
    const bullets = (Array.isArray(s.bullets) ? s.bullets : []).map((b) =>
      `<a:p><a:pPr marL="285750" indent="-285750"><a:buFont typeface="Arial"/><a:buChar char="&#8226;"/></a:pPr><a:r><a:rPr lang="en-US" sz="2000"/><a:t>${esc(b)}</a:t></a:r></a:p>`).join('') ||
      '<a:p><a:endParaRPr lang="en-US"/></a:p>'
    files[`ppt/slides/slide${i + 1}.xml`] = XML_HEAD + `<p:sld ${PNS}><p:cSld><p:spTree>` +
      '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>' +
      '<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title 1"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/>' +
      `<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="en-US"/><a:t>${esc(s.title || '')}</a:t></a:r></a:p></p:txBody></p:sp>` +
      '<p:sp><p:nvSpPr><p:cNvPr id="3" name="Content 2"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:spPr/>' +
      `<p:txBody><a:bodyPr/><a:lstStyle/>${bullets}</p:txBody></p:sp>` +
      '</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>'
    files[`ppt/slides/_rels/slide${i + 1}.xml.rels`] = rels([{ id: 'rId1', type: `${OFFICE_REL}/slideLayout`, target: '../slideLayouts/slideLayout1.xml' }])
  })
  files['ppt/presentation.xml'] = XML_HEAD + `<p:presentation ${PNS} saveSubsetFonts="1">` +
    '<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>' +
    `<p:sldIdLst>${list.map((_, i) => `<p:sldId id="${256 + i}" r:id="rId${i + 3}"/>`).join('')}</p:sldIdLst>` +
    '<p:sldSz cx="9144000" cy="5143500" type="screen16x9"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>'
  files['ppt/_rels/presentation.xml.rels'] = rels([
    { id: 'rId1', type: `${OFFICE_REL}/slideMaster`, target: 'slideMasters/slideMaster1.xml' },
    { id: 'rId2', type: `${OFFICE_REL}/theme`, target: 'theme/theme1.xml' },
    ...list.map((_, i) => ({ id: `rId${i + 3}`, type: `${OFFICE_REL}/slide`, target: `slides/slide${i + 1}.xml` })),
  ])
  files['[Content_Types].xml'] = ct({ rels: 'application/vnd.openxmlformats-package.relationships+xml', xml: 'application/xml' }, [
    ['/ppt/presentation.xml', 'application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml'],
    ['/ppt/slideMasters/slideMaster1.xml', 'application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml'],
    ['/ppt/slideLayouts/slideLayout1.xml', 'application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml'],
    ['/ppt/theme/theme1.xml', 'application/vnd.openxmlformats-officedocument.theme+xml'],
    ...list.map((_, i) => [`/ppt/slides/slide${i + 1}.xml`, 'application/vnd.openxmlformats-officedocument.presentationml.slide+xml']),
  ])
  files['_rels/.rels'] = ROOT_RELS('ppt/presentation.xml')
  return files
}

// ── PDF ──────────────────────────────────────────────────────────────────────

/** Helvetica (WinAnsi) can only show Latin text: anything else becomes "?". */
export function pdfSafe(s) {
  return String(s ?? '').normalize('NFKD').replace(/[\u0300-\u036f]/g, '')
    .replace(/[\u2018\u2019]/g, "'").replace(/[\u201c\u201d]/g, '"').replace(/[\u2013\u2014]/g, '-').replace(/\u2022/g, '-')
    .replace(/[^\x09\x0a\x0d\x20-\x7e\u00a0-\u00ff]/g, '?')
}

function wrap(text, width) {
  const out = []
  for (const para of pdfSafe(text).split(/\r?\n/)) {
    if (para.length <= width) { out.push(para); continue }
    let line = ''
    for (const word of para.split(' ')) {
      const next = line ? line + ' ' + word : word
      if (next.length > width && line) { out.push(line); line = word } else line = next
      while (line.length > width) { out.push(line.slice(0, width)); line = line.slice(width) }
    }
    out.push(line)
  }
  return out
}

const pdfText = (s) => s.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)')

/** blocks: same shape as docx. Returns a Buffer with an A4 PDF, text pages with page numbers. */
export function pdfBuffer(blocks, title = '') {
  const items = []
  if (title) items.push({ size: 18, bold: true, text: title, gap: 12 })
  for (const b of blocks || []) {
    if (b.type === 'h1') items.push({ size: 15, bold: true, text: b.text, gap: 8 })
    else if (b.type === 'h2') items.push({ size: 12.5, bold: true, text: b.text, gap: 4 })
    else if (b.type === 'bullet') items.push({ size: 11, text: '- ' + b.text, indent: 14 })
    else if (b.type === 'table' && Array.isArray(b.rows)) {
      b.rows.forEach((r, ri) => items.push({ size: 10.5, bold: ri === 0, text: r.map((c) => String(c ?? '')).join('  |  ') }))
      items.push({ size: 11, text: '' })
    } else if (b.text !== undefined) items.push({ size: 11, text: String(b.text), gap: 6 })
  }
  if (!items.length) items.push({ size: 11, text: '' })

  // Lay out lines on A4 pages (595 x 842 pt, 60 pt margins).
  const pages = []
  let page = []
  let y = 782
  const newPage = () => { pages.push(page); page = []; y = 782 }
  for (const it of items) {
    const lineH = it.size * 1.35
    const width = Math.floor((475 - (it.indent || 0)) / (it.size * 0.52))
    for (const line of wrap(it.text, width)) {
      if (y - lineH < 60) newPage()
      page.push({ ...it, line, y })
      y -= lineH
    }
    y -= it.gap || 0
  }
  pages.push(page)

  // Objects: 1 catalog, 2 pages, 3 Helvetica, 4 Helvetica-Bold, then one page + one content stream per page.
  const objs = []
  const pageIds = pages.map((_, i) => 5 + i * 2)
  objs[1] = '<< /Type /Catalog /Pages 2 0 R >>'
  objs[2] = `<< /Type /Pages /Kids [${pageIds.map((id) => `${id} 0 R`).join(' ')}] /Count ${pages.length} >>`
  objs[3] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>'
  objs[4] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>'
  pages.forEach((pg, i) => {
    const id = pageIds[i]
    const ops = pg.map((l) => `BT /${l.bold ? 'F2' : 'F1'} ${l.size} Tf ${(60 + (l.indent || 0)).toFixed(2)} ${l.y.toFixed(2)} Td (${pdfText(l.line)}) Tj ET`)
    ops.push(`BT /F1 9 Tf 270 30 Td (${i + 1} / ${pages.length}) Tj ET`)
    const stream = ops.join('\n')
    objs[id] = `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents ${id + 1} 0 R >>`
    objs[id + 1] = `<< /Length ${Buffer.byteLength(stream, 'latin1')} >>\nstream\n${stream}\nendstream`
  })
  let out = '%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n'
  const offsets = []
  for (let i = 1; i < objs.length; i++) {
    if (objs[i] === undefined) continue
    offsets[i] = Buffer.byteLength(out, 'latin1')
    out += `${i} 0 obj\n${objs[i]}\nendobj\n`
  }
  const xref = Buffer.byteLength(out, 'latin1')
  const count = objs.length
  out += `xref\n0 ${count}\n0000000000 65535 f \n`
  for (let i = 1; i < count; i++) out += offsets[i] !== undefined ? `${String(offsets[i]).padStart(10, '0')} 00000 n \n` : '0000000000 65535 f \n'
  out += `trailer\n<< /Size ${count} /Root 1 0 R /Info << /Title (${pdfText(pdfSafe(title || 'Document'))}) /Producer (MYRA) >> >>\nstartxref\n${xref}\n%%EOF\n`
  return Buffer.from(out, 'latin1')
}

// ── saving ───────────────────────────────────────────────────────────────────

const FORMATS = ['docx', 'xlsx', 'pptx', 'pdf']

function safeFileBase(name) {
  const base = String(name || 'document').replace(/[\\/:*?"<>|\x00-\x1f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 80)
  return base || 'document'
}

/** Builds one file in the chosen format. Returns { bytes, ext }. Throws a plain sentence when the content is wrong. */
export function buildOffice(format, { title = '', blocks, sheets, slides } = {}) {
  const f = String(format || '').toLowerCase()
  if (!FORMATS.includes(f)) throw new Error('format must be docx, xlsx, pptx or pdf.')
  if (f === 'docx') return { bytes: makeZip(docxFiles(blocks || [], title)), ext: '.docx' }
  if (f === 'xlsx') {
    if (!Array.isArray(sheets) || !sheets.length) throw new Error('For an Excel file give sheets: [{ name, rows }].')
    return { bytes: makeZip(xlsxFiles(sheets)), ext: '.xlsx' }
  }
  if (f === 'pptx') {
    if (!Array.isArray(slides) || !slides.length) throw new Error('For a PowerPoint give slides: [{ title, bullets }].')
    return { bytes: makeZip(pptxFiles(slides, title)), ext: '.pptx' }
  }
  return { bytes: pdfBuffer(blocks || [], title), ext: '.pdf' }
}

/**
 * Saves the file in the outputs folder, and in Downloads too when Termux can see shared storage
 * (run termux-setup-storage once). Returns the paths.
 */
export function saveOffice({ outDir, downloadsDir, name, format, content }) {
  const { bytes, ext } = buildOffice(format, content)
  mkdirSync(outDir, { recursive: true })
  const file = safeFileBase(name || content?.title) + ext
  const outPath = path.join(outDir, file)
  writeFileSync(outPath, bytes)
  const saved = { path: outPath, file, size: bytes.length, downloads: null }
  const dl = downloadsDir || path.join(os.homedir(), 'storage', 'downloads')
  if (existsSync(dl)) {
    try { copyFileSync(outPath, path.join(dl, file)); saved.downloads = path.join(dl, file) } catch { /* not writable: keep the outputs copy */ }
  }
  return saved
}

export { FORMATS }

/** The folder where MYRA keeps what it makes (inside the brain's workspace). */
export function officeOutDir(getConf) {
  return path.join(getConf().workspace || path.join(os.homedir(), 'myra-workspace'), 'outputs')
}

/** The tool for voice: "Word file banao", "Excel sheet banao", "PPT banao", "PDF banao". */
export function makeOffice({ getConf, log = () => {} }) {
  const TOOLS = [
    {
      name: 'office_make',
      description: 'Make a Word (docx), Excel (xlsx), PowerPoint (pptx) or PDF file from what Anuj asked for, and save it where he can find it (the MYRA outputs folder, and Downloads when Termux can see storage). docx and pdf: blocks, a list of {type: "h1" | "h2" | "p" | "bullet" | "table", text} and for a table rows (the first row is the header). xlsx: sheets, a list of {name, rows}; text that starts with = is a formula (=SUM(B2:B9)), and the first row is bold. pptx: slides, a list of {title, bullets}. Plain text only; PDF has no Hindi script. After it is made, say the file name and where it is, and offer to email it.',
      inputSchema: {
        type: 'object',
        properties: {
          format: { type: 'string', enum: ['docx', 'xlsx', 'pptx', 'pdf'] },
          title: { type: 'string', description: 'Title of the document or deck.' },
          filename: { type: 'string', description: 'A short file name, without the extension.' },
          blocks: { type: 'array', items: { type: 'object' }, description: 'For docx and pdf.' },
          sheets: { type: 'array', items: { type: 'object' }, description: 'For xlsx.' },
          slides: { type: 'array', items: { type: 'object' }, description: 'For pptx.' },
        },
        required: ['format'],
        additionalProperties: false,
      },
    },
  ]
  const HANDLERS = {
    async office_make(args) {
      const saved = saveOffice({
        outDir: officeOutDir(getConf),
        name: args.filename || args.title || 'document',
        format: args.format,
        content: { title: args.title || '', blocks: args.blocks, sheets: args.sheets, slides: args.slides },
      })
      log('office file made:', saved.file)
      const where = saved.downloads ? 'in the MYRA outputs folder and in Downloads' : 'in the MYRA outputs folder (run termux-setup-storage once to see it in Downloads)'
      return `Made "${saved.file}" (${Math.max(1, Math.round(saved.size / 1024))} KB), saved ${where}.`
    },
  }
  return { TOOLS, HANDLERS }
}
