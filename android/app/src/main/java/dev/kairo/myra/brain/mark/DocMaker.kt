package dev.kairo.myra.brain.mark

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes real Office files with nothing but java.util.zip: a PowerPoint deck (.pptx) and a Word report (.docx).
 * No Android classes in here on purpose, so it can be compiled and tested anywhere.
 */
object DocMaker {
    class Slide(val title: String, val bullets: List<String>, val notes: String = "")
    class Section(val heading: String, val paragraphs: List<String>)

    private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    /** XML-safe text: control characters dropped, the five special characters escaped. */
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\'' -> sb.append("&apos;")
                ch.code < 32 && ch != '\t' && ch != '\n' && ch != '\r' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun zip(parts: List<Pair<String, String>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, text) in parts) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /* ── PowerPoint ───────────────────────────────────────────────────────────────────────────── */

    /** A 16:9 deck: a title slide first, then one slide per [Slide]. Dark background, orange accent. */
    fun pptx(deckTitle: String, subtitle: String, slides: List<Slide>): ByteArray {
        val all = listOf(Slide(deckTitle, emptyList())) + slides
        val parts = ArrayList<Pair<String, String>>()
        val overrides = StringBuilder()
        all.indices.forEach { overrides.append("<Override PartName=\"/ppt/slides/slide${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>") }
        parts += "[Content_Types].xml" to (XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
            overrides + "</Types>")
        parts += "_rels/.rels" to (XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>")

        val sldIds = StringBuilder()
        val presRels = StringBuilder("<Relationship Id=\"rId1\" Type=\"$REL/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
        all.indices.forEach {
            sldIds.append("<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 2}\"/>")
            presRels.append("<Relationship Id=\"rId${it + 2}\" Type=\"$REL/slide\" Target=\"slides/slide${it + 1}.xml\"/>")
        }
        presRels.append("<Relationship Id=\"rId${all.size + 2}\" Type=\"$REL/theme\" Target=\"theme/theme1.xml\"/>")
        parts += "ppt/presentation.xml" to (XML + "<p:presentation xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\">" +
            "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
            "<p:sldIdLst>$sldIds</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
        parts += "ppt/_rels/presentation.xml.rels" to (XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$presRels</Relationships>")
        parts += "ppt/slideMasters/slideMaster1.xml" to MASTER
        parts += "ppt/slideMasters/_rels/slideMaster1.xml.rels" to (XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$REL/theme\" Target=\"../theme/theme1.xml\"/></Relationships>")
        parts += "ppt/slideLayouts/slideLayout1.xml" to LAYOUT
        parts += "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to (XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>")
        parts += "ppt/theme/theme1.xml" to THEME
        all.forEachIndexed { i, s ->
            parts += "ppt/slides/slide${i + 1}.xml" to (if (i == 0) titleSlide(s.title, subtitle) else contentSlide(s, i + 1))
            parts += "ppt/slides/_rels/slide${i + 1}.xml.rels" to (XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$REL/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>")
        }
        return zip(parts)
    }

    private const val BG = "0F1115"
    private const val ACCENT = "FF8A1F"
    private const val TEXT = "F2F2F2"
    private const val MUTED = "A8ADB7"

    private fun slideWrap(shapes: String) = XML + "<p:sld xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\"><p:cSld>" +
        "<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"$BG\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>" +
        "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
        shapes + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"

    private fun rect(id: Int, name: String, x: Long, y: Long, w: Long, h: Long, color: String) =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>" +
            "<a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" +
            "<a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr>" +
            "<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p></p:txBody></p:sp>"

    private fun textBox(id: Int, name: String, x: Long, y: Long, w: Long, h: Long, paragraphs: String, anchor: String = "t") =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr>" +
            "<a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\" lIns=\"0\" rIns=\"0\" anchor=\"$anchor\"><a:normAutofit/></a:bodyPr><a:lstStyle/>$paragraphs</p:txBody></p:sp>"

    private fun para(text: String, sizePt: Int, color: String, bold: Boolean = false, bullet: Boolean = false, spaceAfterPt: Int = 0): String {
        val ppr = if (bullet)
            "<a:pPr marL=\"342900\" indent=\"-342900\"><a:spcAft><a:spcPts val=\"${spaceAfterPt * 100}\"/></a:spcAft>" +
                "<a:buClr><a:srgbClr val=\"$ACCENT\"/></a:buClr><a:buFont typeface=\"Arial\"/><a:buChar char=\"&#8226;\"/></a:pPr>"
        else "<a:pPr><a:spcAft><a:spcPts val=\"${spaceAfterPt * 100}\"/></a:spcAft><a:buNone/></a:pPr>"
        return "<a:p>$ppr<a:r><a:rPr lang=\"en-US\" sz=\"${sizePt * 100}\" b=\"${if (bold) 1 else 0}\" dirty=\"0\">" +
            "<a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill><a:latin typeface=\"Calibri\"/></a:rPr><a:t>${esc(text)}</a:t></a:r></a:p>"
    }

    private fun titleSlide(title: String, subtitle: String): String = slideWrap(
        rect(2, "Accent", 838200, 2438400, 1143000, 76200, ACCENT) +
            textBox(3, "Title", 838200, 2650000, 10515600, 1600000, para(title.take(120), 44, TEXT, bold = true), anchor = "t") +
            textBox(4, "Subtitle", 838200, 4400000, 10515600, 800000, para(subtitle.take(160), 20, MUTED)),
    )

    private fun contentSlide(s: Slide, number: Int): String {
        val bullets = s.bullets.take(6).joinToString("") { para(it.take(220), 22, TEXT, bullet = true, spaceAfterPt = 10) }
        val size = if (s.bullets.take(6).sumOf { it.length } > 420) 18 else 22
        val body = if (size == 22) bullets else s.bullets.take(6).joinToString("") { para(it.take(220), size, TEXT, bullet = true, spaceAfterPt = 8) }
        return slideWrap(
            rect(2, "Accent", 838200, 640000, 76200, 640000, ACCENT) +
                textBox(3, "Title", 1100000, 560000, 10253800, 800000, para(s.title.take(100), 32, TEXT, bold = true), anchor = "ctr") +
                textBox(4, "Body", 838200, 1700000, 10515600, 4400000, body) +
                textBox(5, "Number", 10800000, 6300000, 553800, 300000, para(number.toString(), 12, MUTED)),
        )
    }

    private const val MASTER = XML + "<p:sldMaster xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\"><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg>" +
        "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>" +
        "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
        "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>"

    private const val LAYOUT = XML + "<p:sldLayout xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\" type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\">" +
        "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>" +
        "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

    private const val THEME = XML + "<a:theme xmlns:a=\"$NS_A\" name=\"Myraa\"><a:themeElements>" +
        "<a:clrScheme name=\"Myraa\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1><a:dk2><a:srgbClr val=\"1F2430\"/></a:dk2><a:lt2><a:srgbClr val=\"F2F2F2\"/></a:lt2>" +
        "<a:accent1><a:srgbClr val=\"FF8A1F\"/></a:accent1><a:accent2><a:srgbClr val=\"3B82F6\"/></a:accent2><a:accent3><a:srgbClr val=\"22C55E\"/></a:accent3><a:accent4><a:srgbClr val=\"A855F7\"/></a:accent4><a:accent5><a:srgbClr val=\"EF4444\"/></a:accent5><a:accent6><a:srgbClr val=\"14B8A6\"/></a:accent6>" +
        "<a:hlink><a:srgbClr val=\"3B82F6\"/></a:hlink><a:folHlink><a:srgbClr val=\"A855F7\"/></a:folHlink></a:clrScheme>" +
        "<a:fontScheme name=\"Myraa\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
        "<a:fmtScheme name=\"Myraa\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>" +
        "<a:lnStyleLst><a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"19050\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>" +
        "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
        "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>" +
        "</a:themeElements></a:theme>"

    /* ── Word ─────────────────────────────────────────────────────────────────────────────────── */

    /** A report: big title, optional subtitle line, then each section as a heading and paragraphs. */
    fun docx(title: String, subtitle: String, sections: List<Section>): ByteArray {
        val body = StringBuilder()
        body.append(wPara(title, style = "Title"))
        if (subtitle.isNotBlank()) body.append(wPara(subtitle, style = "Subtitle"))
        for (s in sections) {
            if (s.heading.isNotBlank()) body.append(wPara(s.heading, style = "Heading1"))
            for (p in s.paragraphs) {
                val t = p.trim()
                if (t.isEmpty()) continue
                if (t.startsWith("- ") || t.startsWith("• ")) body.append(wPara("\u2022  " + t.substring(2), style = "ListBullet"))
                else body.append(wPara(t))
            }
        }
        val document = XML + "<w:document xmlns:w=\"$NS_W\" xmlns:r=\"$NS_R\"><w:body>$body" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
        return zip(
            listOf(
                "[Content_Types].xml" to (XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                    "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>"),
                "_rels/.rels" to (XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"word/document.xml\"/></Relationships>"),
                "word/_rels/document.xml.rels" to (XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"$REL/styles\" Target=\"styles.xml\"/></Relationships>"),
                "word/document.xml" to document,
                "word/styles.xml" to DOCX_STYLES,
            ),
        )
    }

    private fun wPara(text: String, style: String? = null): String {
        val ppr = if (style != null) "<w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>" else ""
        return "<w:p>$ppr<w:r><w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r></w:p>"
    }

    private const val DOCX_STYLES = XML + "<w:styles xmlns:w=\"$NS_W\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\" w:eastAsia=\"Calibri\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-US\"/></w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:spacing w:after=\"140\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:spacing w:after=\"120\"/></w:pPr><w:rPr><w:b/><w:color w:val=\"E06A00\"/><w:sz w:val=\"52\"/><w:szCs w:val=\"52\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Subtitle\"><w:name w:val=\"Subtitle\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:spacing w:after=\"360\"/></w:pPr><w:rPr><w:color w:val=\"6B7280\"/><w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"320\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:color w:val=\"1F2430\"/><w:sz w:val=\"34\"/><w:szCs w:val=\"34\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"ListBullet\"><w:name w:val=\"List Bullet\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:style></w:styles>"
}
