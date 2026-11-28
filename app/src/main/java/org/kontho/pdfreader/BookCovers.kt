// SPDX-License-Identifier: GPL-3.0-or-later
package org.kontho.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.github.junrar.Archive
import java.io.ByteArrayOutputStream
import java.io.File

/** Library thumbnails for EPUB and comic files: the cover, or the first page. */
object BookCovers {
    fun load(context: Context, uri: Uri, name: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        val extension = BookFormats.extensionOf(name)
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                // A seekable file descriptor can be opened as a path, which ZIP and RAR readers need.
                val file = File("/proc/self/fd/${descriptor.fd}")
                val bytes = if (extension == "epub") epubCover(file) else comicCover(file)
                bytes?.let { decode(it, maxWidth, maxHeight) }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun epubCover(file: File): ByteArray? {
        val book = EpubBook.open(file)
        try {
            val path = book.coverImagePath() ?: return null
            return book.read(path)
        } finally {
            book.close()
        }
    }

    private fun comicCover(file: File): ByteArray? {
        val head = ByteArray(4)
        val read = file.inputStream().use { it.read(head) }
        if (read < 4) return null
        return if (head[0] == 'R'.code.toByte() && head[1] == 'a'.code.toByte()) rarCover(file) else zipCover(file)
    }

    private fun zipCover(file: File): ByteArray? {
        val zip = ComicArchive.openZip(file)
        try {
            val entry = zip.entries().toList()
                .filter { !it.isDirectory && BookFormats.isImageEntry(it.name) }
                .minWithOrNull { a, b -> BookFormats.naturalCompare(a.name, b.name) } ?: return null
            return zip.getInputStream(entry).use { it.readBytes() }
        } finally {
            zip.close()
        }
    }

    private fun rarCover(file: File): ByteArray? {
        Archive(file).use { archive ->
            val header = archive.fileHeaders
                .filter { !it.isDirectory && BookFormats.isImageEntry(it.fileName) }
                .minWithOrNull { a, b ->
                    BookFormats.naturalCompare(a.fileName.replace('\\', '/'), b.fileName.replace('\\', '/'))
                } ?: return null
            val output = ByteArrayOutputStream()
            archive.extractFile(header, output)
            return output.toByteArray()
        }
    }

    private fun decode(bytes: ByteArray, maxWidth: Int, maxHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth && bounds.outHeight / (sample * 2) >= maxHeight) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
