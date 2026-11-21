package com.google.antigravity

import com.google.antigravity.device.PhonePresentationSlide
import com.google.antigravity.device.writePhonePresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class PhonePresentationTest {
    @Test fun writesEditableSlidesAndResolvesAllPackageRelationships() {
        val title = "Отчёт & прогноз <2026> 🚀"
        val bullet = "Добро пожаловать, \"мир\" & 'привет' <XML>\u0000"
        val output = ByteArrayOutputStream()
        writePhonePresentation(output, title, listOf(
            PhonePresentationSlide(title, emptyList()),
            PhonePresentationSlide("Итоги", listOf(bullet, "Следующий шаг")),
        ))

        val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        val parts = ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, parser.parse(zip.readBytes().inputStream()))
                }
            }
        }
        val contentTypes = parts.getValue("[Content_Types].xml").getElementsByTagNameNS("*", "Override")
        assertEquals(parts.keys.filter { it.endsWith(".xml") && it != "[Content_Types].xml" }.toSet(),
            (0 until contentTypes.length).map { contentTypes.item(it).attributes.getNamedItem("PartName").nodeValue.drop(1) }.toSet())
        for ((path, document) in parts.filterKeys { it.endsWith(".rels") }) {
            val sourceDirectory = path.substringBeforeLast("_rels/")
            val relationships = document.getElementsByTagNameNS("*", "Relationship")
            for (i in 0 until relationships.length) {
                val target = relationships.item(i).attributes.getNamedItem("Target").nodeValue
                val resolved = URI("/$sourceDirectory").resolve(target).normalize().path.drop(1)
                assertTrue("$path -> $target ($resolved)", resolved in parts)
            }
        }
        assertEquals(2, parts.getValue("ppt/presentation.xml").getElementsByTagNameNS("*", "sldId").length)
        val metadata = parts.getValue("docProps/core.xml")
        assertEquals("http://schemas.openxmlformats.org/package/2006/metadata/core-properties",
            metadata.documentElement.namespaceURI)
        assertEquals(title, metadata.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "title").item(0).textContent)
        val cover = parts.getValue("ppt/slides/slide1.xml").getElementsByTagNameNS("*", "t")
        assertEquals(title, cover.item(0).textContent)
        val body = parts.getValue("ppt/slides/slide2.xml").getElementsByTagNameNS("*", "t")
        assertEquals(listOf("Итоги", bullet.dropLast(1) + "\uFFFD", "Следующий шаг"),
            (0 until body.length).map { body.item(it).textContent })
    }
}
