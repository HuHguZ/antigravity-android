package com.google.antigravity

import com.google.antigravity.device.PhoneProjectFile
import com.google.antigravity.device.createPhoneProject
import com.google.antigravity.device.importPhoneProjectZip
import com.google.antigravity.device.phoneProjectPath
import com.google.antigravity.device.writePhoneProjectFile
import com.google.antigravity.device.writePhoneProjectZip
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PhoneProjectFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun publishesAllNestedFilesAndArchivesTheirExactContent() {
        val files = listOf(PhoneProjectFile("README.md", "Привет\n```sh\nls\n```\n"),
            PhoneProjectFile("src/app.js", "export const ready = true;\n"), PhoneProjectFile(".gitignore", ""))
        val project = createPhoneProject(temporary.root, "phone-demo", files)
        files.forEach { assertEquals(it.content, File(project, it.path).readText()) }
        val output = ByteArrayOutputStream()
        writePhoneProjectZip(project, output)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                entries[entry.name] = archive.readBytes().decodeToString()
                archive.closeEntry()
            }
        }
        assertEquals(files.associate { it.path to it.content }, entries)
        val imported = importPhoneProjectZip(temporary.root, "roundtrip", ByteArrayInputStream(output.toByteArray()))
        files.forEach { assertEquals(it.content, File(imported, it.path).readText()) }
    }

    @Test fun rejectsTraversalConflictingPathsAndOverwritingExistingProjects() {
        val existing = createPhoneProject(temporary.root, "existing", listOf(PhoneProjectFile("main.txt", "old")))
        assertThrows(Exception::class.java) {
            createPhoneProject(temporary.root, "existing", listOf(PhoneProjectFile("main.txt", "new")))
        }
        assertEquals("old", File(existing, "main.txt").readText())
        assertThrows(IllegalArgumentException::class.java) {
            createPhoneProject(temporary.root, "invalid", listOf(PhoneProjectFile("../escape.txt", "bad")))
        }
        assertThrows(Exception::class.java) {
            createPhoneProject(temporary.root, "invalid", listOf(PhoneProjectFile("a", "1"), PhoneProjectFile("a/b", "2")))
        }
        assertEquals(listOf("existing"), temporary.root.list()?.toList())
    }

    @Test fun importsAndEditsAnExistingProjectWithoutFlatteningItsTree() {
        val archive = zipFixture("src/main.js" to "export const value = 1;", ".gitignore" to "dist/\n")
        val project = importPhoneProjectZip(temporary.root, "imported", ByteArrayInputStream(archive))
        val source = phoneProjectPath(project, "src/main.js")
        assertEquals("export const value = 1;", source.readText())
        writePhoneProjectFile(project, "src/main.js", "export const value = 2;")
        writePhoneProjectFile(project, "test/checks.js", "// tests")
        assertEquals("export const value = 2;", source.readText())
        assertTrue(File(project, "test/checks.js").isFile)
        assertThrows(IllegalArgumentException::class.java) { phoneProjectPath(project, "../external.txt") }
    }

    @Test fun rejectsZipTraversalWithoutLeavingAnyPartialProject() {
        val archive = zipFixture("valid.txt" to "unpublished", "../escape.txt" to "must not be written")
        assertThrows(IllegalArgumentException::class.java) {
            importPhoneProjectZip(temporary.root, "invalid", ByteArrayInputStream(archive))
        }
        assertTrue(temporary.root.list().orEmpty().isEmpty())
    }

    private fun zipFixture(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((path, content) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
