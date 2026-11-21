package com.google.antigravity.device

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption

internal fun writePhoneDownload(
    context: Context,
    name: String,
    mimeType: String,
    write: (OutputStream) -> Unit
): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Antigravity")
        Files.createDirectories(folder.toPath())
        val file = File(folder, name)
        val output = Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try {
            output.use(write)
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType), null)
        return name
    }

    val resolver = context.contentResolver
    val metadata = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Antigravity")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, metadata)
        ?: throw IOException("Android не создал файл в Загрузках.")
    try {
        val stream = resolver.openOutputStream(uri) ?: throw IOException("Android не открыл файл для записи.")
        stream.use(write)
        val finalized = resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        if (finalized != 1) throw IOException("Android не опубликовал файл в Загрузках.")
    } catch (failure: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw failure
    }
    return runCatching {
        resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: name
}
