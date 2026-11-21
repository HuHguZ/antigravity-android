package com.google.antigravity.device

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class PhonePresentationSlide(val title: String, val bullets: List<String>)

/** Writes a complete PowerPoint file and closes [output]. */
internal fun writePhonePresentation(output: OutputStream, title: String, slides: List<PhonePresentationSlide>) {
    require(slides.isNotEmpty()) { "Презентация должна содержать хотя бы один слайд" }
    ZipOutputStream(output).use { zip ->
        fun part(path: String, xml: String) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + xml.trimIndent()).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val slideTypes = slides.indices.joinToString("") { index ->
            """<Override PartName="/ppt/slides/slide${index + 1}.xml" ContentType="$PRESENTATION_TYPE.slide+xml"/>"""
        }
        part("[Content_Types].xml", """
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
                <Override PartName="/ppt/presentation.xml" ContentType="$PRESENTATION_TYPE.presentation.main+xml"/>
                <Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="$PRESENTATION_TYPE.slideMaster+xml"/>
                <Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="$PRESENTATION_TYPE.slideLayout+xml"/>
                <Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
                $slideTypes
            </Types>
        """)
        part("_rels/.rels", relationships(listOf(
            "$OFFICE_REL/officeDocument" to "ppt/presentation.xml",
            "$PACKAGE_REL/metadata/core-properties" to "docProps/core.xml",
        )))
        part("docProps/core.xml", """
            <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>${xmlText(title)}</dc:title>
            </cp:coreProperties>
        """)

        val slideIds = slides.indices.joinToString("") { index ->
            """<p:sldId id="${256 + index}" r:id="rId${index + 2}"/>"""
        }
        part("ppt/presentation.xml", """
            <p:presentation $SLIDE_NAMESPACES>
                <p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
                <p:sldIdLst>$slideIds</p:sldIdLst>
                <p:sldSz cx="12192000" cy="6858000"/>
                <p:notesSz cx="6858000" cy="9144000"/>
            </p:presentation>
        """)
        part("ppt/_rels/presentation.xml.rels", relationships(
            listOf("$OFFICE_REL/slideMaster" to "slideMasters/slideMaster1.xml") +
                slides.indices.map { "$OFFICE_REL/slide" to "slides/slide${it + 1}.xml" },
        ))

        part("ppt/slideMasters/slideMaster1.xml", """
            <p:sldMaster $SLIDE_NAMESPACES>
                <p:cSld><p:spTree>$SHAPE_TREE_HEADER</p:spTree></p:cSld>
                <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2"
                    accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
                <p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>
            </p:sldMaster>
        """)
        part("ppt/slideMasters/_rels/slideMaster1.xml.rels", relationships(listOf(
            "$OFFICE_REL/slideLayout" to "../slideLayouts/slideLayout1.xml",
            "$OFFICE_REL/theme" to "../theme/theme1.xml",
        )))
        part("ppt/slideLayouts/slideLayout1.xml", """
            <p:sldLayout $SLIDE_NAMESPACES type="blank" preserve="1">
                <p:cSld name="Blank"><p:spTree>$SHAPE_TREE_HEADER</p:spTree></p:cSld>
            </p:sldLayout>
        """)
        part("ppt/slideLayouts/_rels/slideLayout1.xml.rels", relationships(listOf(
            "$OFFICE_REL/slideMaster" to "../slideMasters/slideMaster1.xml",
        )))
        part("ppt/theme/theme1.xml", theme())
        val layout = relationships(listOf("$OFFICE_REL/slideLayout" to "../slideLayouts/slideLayout1.xml"))
        slides.forEachIndexed { index, slide ->
            val name = "slide${index + 1}.xml"
            part("ppt/slides/$name", slideXml(slide))
            part("ppt/slides/_rels/$name.rels", layout)
        }
    }
}

private const val PRESENTATION_TYPE = "application/vnd.openxmlformats-officedocument.presentationml"
private const val OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
private const val DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
private const val SLIDE_NAMESPACES =
    """xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="$DRAWING_NS" xmlns:r="$OFFICE_REL""""
private const val SHAPE_TREE_HEADER = """
    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
"""

private fun relationships(targets: List<Pair<String, String>>): String {
    val entries = targets.mapIndexed { index, (type, target) ->
        """<Relationship Id="rId${index + 1}" Type="$type" Target="$target"/>"""
    }.joinToString("")
    return """<Relationships xmlns="$PACKAGE_REL">$entries</Relationships>"""
}

private fun slideXml(slide: PhonePresentationSlide): String {
    val cover = slide.bullets.isEmpty()
    val title = if (cover) {
        textBox(2, "Title", 1300000, 4250000, paragraph(slide.title, 4800, "F6F8F3", "Cambria"), "ctr")
    } else {
        textBox(2, "Title", 600000, 1500000, paragraph(slide.title, 3600, "133B36", "Cambria"))
    }
    val body = if (cover) "" else textBox(3, "Bullet points", 2230000, 3950000,
        slide.bullets.joinToString("") { paragraph(it, 2400, "243D38", "Arial", bullet = true) },
    )
    val background = if (cover) "133B36" else "F6F8F3"
    return """
        <p:sld $SLIDE_NAMESPACES>
            <p:cSld>
                <p:bg><p:bgPr><a:solidFill><a:srgbClr val="$background"/></a:solidFill></p:bgPr></p:bg>
                <p:spTree>$SHAPE_TREE_HEADER$title$body</p:spTree>
            </p:cSld>
        </p:sld>
    """
}

private fun textBox(id: Int, name: String, top: Int, height: Int, paragraphs: String, anchor: String = "t") = """
    <p:sp>
        <p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
        <p:spPr>
            <a:xfrm><a:off x="836000" y="$top"/><a:ext cx="10520000" cy="$height"/></a:xfrm>
            <a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln>
        </p:spPr>
        <p:txBody>
            <a:bodyPr wrap="square" anchor="$anchor" lIns="0" rIns="0" tIns="0" bIns="0"><a:normAutofit/></a:bodyPr>
            <a:lstStyle/>$paragraphs
        </p:txBody>
    </p:sp>
"""

private fun paragraph(text: String, size: Int, color: String, font: String, bullet: Boolean = false): String {
    val properties = if (bullet) """
        <a:pPr marL="280000" indent="-225000">
            <a:spcAft><a:spcPts val="1000"/></a:spcAft>
            <a:buClr><a:srgbClr val="28756A"/></a:buClr><a:buFont typeface="Arial"/><a:buChar char="•"/>
        </a:pPr>
    """ else "<a:pPr><a:buNone/></a:pPr>"
    val bold = if (bullet) "0" else "1"
    return """
        <a:p>$properties<a:r><a:rPr sz="$size" b="$bold">
            <a:solidFill><a:srgbClr val="$color"/></a:solidFill><a:latin typeface="$font"/>
        </a:rPr><a:t>${xmlText(text)}</a:t></a:r></a:p>
    """
}

private fun theme(): String {
    val fill = """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>"""
    val effect = "<a:effectStyle><a:effectLst/></a:effectStyle>"
    return """
        <a:theme xmlns:a="$DRAWING_NS" name="Forest">
            <a:themeElements>
                <a:clrScheme name="Forest">
                    <a:dk1><a:srgbClr val="133B36"/></a:dk1><a:lt1><a:srgbClr val="F6F8F3"/></a:lt1>
                    <a:dk2><a:srgbClr val="243D38"/></a:dk2><a:lt2><a:srgbClr val="DDE9E3"/></a:lt2>
                    <a:accent1><a:srgbClr val="28756A"/></a:accent1><a:accent2><a:srgbClr val="5A8D74"/></a:accent2>
                    <a:accent3><a:srgbClr val="A88346"/></a:accent3><a:accent4><a:srgbClr val="A46C58"/></a:accent4>
                    <a:accent5><a:srgbClr val="526A82"/></a:accent5><a:accent6><a:srgbClr val="6F6077"/></a:accent6>
                    <a:hlink><a:srgbClr val="146F66"/></a:hlink><a:folHlink><a:srgbClr val="6F6077"/></a:folHlink>
                </a:clrScheme>
                <a:fontScheme name="Forest">
                    <a:majorFont><a:latin typeface="Cambria"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>
                    <a:minorFont><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont>
                </a:fontScheme>
                <a:fmtScheme name="Forest">
                    <a:fillStyleLst>${fill.repeat(3)}</a:fillStyleLst>
                    <a:lnStyleLst>${"<a:ln>$fill</a:ln>".repeat(3)}</a:lnStyleLst>
                    <a:effectStyleLst>${effect.repeat(3)}</a:effectStyleLst>
                    <a:bgFillStyleLst>${fill.repeat(3)}</a:bgFillStyleLst>
                </a:fmtScheme>
            </a:themeElements>
        </a:theme>
    """
}

private fun xmlText(text: String): String = buildString {
    var offset = 0
    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        when (codePoint) {
            '&'.code -> append("&amp;")
            '<'.code -> append("&lt;")
            '>'.code -> append("&gt;")
            '\r'.code -> append("&#13;")
            '\n'.code, '\t'.code, in 0x20..0xD7FF, in 0xE000..0xFFFD, in 0x10000..0x10FFFF ->
                appendCodePoint(codePoint)
            else -> append('\uFFFD')
        }
        offset += Character.charCount(codePoint)
    }
}
