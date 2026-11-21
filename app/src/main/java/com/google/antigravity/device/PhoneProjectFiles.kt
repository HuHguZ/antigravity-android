package com.google.antigravity.device

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class PhoneProjectFile(val path: String, val content: String)

private fun requireProjectRelativePath(path: String) {
    require(path.length in 1..240 && path.split('/').none { part ->
        part.isBlank() || part != part.trim() || part == "." || part == ".." || part.endsWith('.') ||
            part.any { it.isISOControl() || it in "\\:<>\"|?*" }
    }) { "Используйте путь относительно проекта без переходов в родительские папки." }
}

internal fun phoneArtifactName(rawName: String): String {
    val name = rawName.trim()
    require(name.matches(Regex("[\\p{L}\\p{N}_-][\\p{L}\\p{N} ._()-]{0,79}")) && !name.endsWith('.')) {
        "Укажите имя без слешей и специальных символов, до 80 знаков."
    }
    return name
}

internal fun createPhoneProject(root: File, rawName: String, files: List<PhoneProjectFile>): File {
    val name = phoneArtifactName(rawName)
    require(files.size in 1..64) { "В одном проекте укажите от 1 до 64 файлов." }
    val paths = mutableSetOf<String>()
    val entries = files.map { file ->
        requireProjectRelativePath(file.path)
        require(paths.add(file.path.lowercase(Locale.ROOT))) { "В проекте есть повторяющийся путь." }
        file to file.content.encodeToByteArray()
    }
    require(entries.sumOf { it.second.size.toLong() } <= 2L * 1024L * 1024L) { "Размер файлов проекта превышает 2 МБ." }
    require(paths.none { path -> path.split('/').dropLast(1).runningReduce { prefix, part -> "$prefix/$part" }.any(paths::contains) }) {
        "Один путь используется одновременно как файл и папка."
    }

    return publishPhoneProject(root, name) { staged ->
        for ((file, bytes) in entries) {
            val target = staged.resolve(file.path)
            Files.createDirectories(target.parent)
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW)
        }
    }
}

internal fun importPhoneProjectZip(root: File, name: String, input: InputStream): File =
    publishPhoneProject(root, phoneArtifactName(name)) { staged ->
        val seen = mutableSetOf<String>()
        var entries = 0
        var files = 0
        var totalBytes = 0L
        val buffer = ByteArray(32 * 1024)
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 5_000) { "В архиве больше 5 000 записей." }
                val path = if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name
                requireProjectRelativePath(path)
                require(seen.add(path.lowercase(Locale.ROOT))) { "В архиве есть повторяющийся путь." }
                val target = staged.resolve(path)
                if (entry.isDirectory) {
                    require(zip.read() == -1) { "Запись каталога в ZIP содержит данные." }
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            require(totalBytes <= 128L * 1024 * 1024) { "Распакованный проект превышает 128 МБ." }
                            output.write(buffer, 0, count)
                        }
                    }
                    files++
                }
            }
        }
        require(files > 0) { "В архиве нет файлов проекта." }
    }

private fun publishPhoneProject(root: File, name: String, populate: (Path) -> Unit): File {
    val parent = Files.createDirectories(root.toPath()).toRealPath()
    val destination = parent.resolve(name)
    require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { "Проект с таким именем уже есть. Выберите другое имя." }
    val staged = Files.createTempDirectory(parent, ".project-")
    try {
        populate(staged)
        return Files.move(staged, destination).toFile()
    } finally {
        staged.toFile().deleteRecursively()
    }
}

internal fun listPhoneProjects(root: File): List<String> = root.listFiles()
    ?.filter { !it.name.startsWith('.') && Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
    ?.map { it.name }?.sorted().orEmpty()

internal fun openPhoneProject(root: File, name: String): File {
    val folder = root.toPath().resolve(phoneArtifactName(name))
    require(Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) { "Проект не найден." }
    return folder.toRealPath().toFile()
}

internal fun phoneProjectPath(project: File, path: String): File {
    requireProjectRelativePath(path)
    var target = project.toPath().toRealPath()
    for (segment in path.split('/')) {
        target = target.resolve(segment)
        require(!Files.isSymbolicLink(target)) { "Символические ссылки в проекте недоступны." }
    }
    return target.toFile()
}

internal fun listPhoneProjectFiles(project: File, offset: Int, limit: Int): List<String> {
    val root = project.toPath()
    return Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.sorted()
            .skip(offset.toLong()).limit(limit.toLong()).map { root.relativize(it).joinToString("/") }
            .iterator().asSequence().toList()
    }
}

internal fun writePhoneProjectFile(project: File, path: String, content: String): File {
    val target = phoneProjectPath(project, path).toPath()
    val bytes = content.encodeToByteArray()
    require(bytes.size <= 2 * 1024 * 1024) { "Размер файла превышает 2 МБ." }
    Files.createDirectories(target.parent)
    val staged = Files.createTempFile(target.parent, ".project-file-", ".part")
    try {
        Files.write(staged, bytes)
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(staged)
    }
    return target.toFile()
}

internal fun writePhoneProjectZip(project: File, output: OutputStream) {
    val root = project.toPath()
    ZipOutputStream(output).use { zip ->
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.sorted().forEach { file ->
                val path = root.relativize(file).joinToString("/")
                zip.putNextEntry(ZipEntry(path))
                Files.newInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
