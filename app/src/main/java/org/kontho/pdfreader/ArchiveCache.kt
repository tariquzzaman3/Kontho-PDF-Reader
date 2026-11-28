// SPDX-License-Identifier: GPL-3.0-or-later
package org.kontho.pdfreader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * ZIP and RAR files need random access, which a content address cannot give. The book is copied into
 * the app cache once (and reused next time). Only the few most recently read books are kept.
 */
object ArchiveCache {
    private const val KEEP = 6

    fun dir(context: Context): File = File(context.cacheDir, "archives").apply { mkdirs() }

    private fun sizeOf(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    fun fileFor(context: Context, uri: Uri, extension: String, progress: ((Int) -> Unit)? = null): File {
        if (uri.scheme == "file") {
            uri.path?.let { path ->
                val direct = File(path)
                if (direct.isFile && direct.canRead()) return direct
            }
        }
        val size = sizeOf(context, uri)
        val key = BookFormats.key(uri) + (if (size > 0) "-$size" else "")
        val target = File(dir(context), "$key.$extension")
        if (target.isFile && target.length() > 0 && (size <= 0 || target.length() == size)) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        trim(context, KEEP - 1)
        val part = File(dir(context), "$key.part")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not read this file.")
        input.use { source ->
            FileOutputStream(part).use { output ->
                val buffer = ByteArray(128 * 1024)
                var copied = 0L
                var reported = -1
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    if (size > 0 && progress != null) {
                        val percent = (copied * 100L / size).toInt().coerceIn(0, 100)
                        if (percent != reported) {
                            reported = percent
                            progress(percent)
                        }
                    }
                }
            }
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Could not store the book for reading.")
        return target
    }

    /** Keeps only the [keep] most recently used cached books (files and extracted page folders). */
    fun trim(context: Context, keep: Int) {
        val items = dir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: return
        items.drop(keep).forEach { it.deleteRecursively() }
    }
}
