// SPDX-License-Identifier: GPL-3.0-or-later
package org.kontho.pdfreader

import android.content.Context
import android.net.Uri
import android.os.Build
import com.github.junrar.Archive
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** The pages of a comic book, in reading order. */
interface ComicSource : Closeable {
    val pageCount: Int
    fun open(index: Int): InputStream
}

/** Opens CBZ (ZIP) and CBR (RAR) comics. A ".cbr" that is really a ZIP, or the reverse, still works. */
object ComicArchive {
    fun open(context: Context, uri: Uri, name: String, progress: (Int) -> Unit): ComicSource {
        val extension = BookFormats.extensionOf(name).ifEmpty { "cbz" }
        val file = ArchiveCache.fileFor(context, uri, extension) { progress(it / 2) }
        return when (sniff(file)) {
            Container.ZIP -> ZipComicSource(openZip(file))
            Container.RAR -> extractRar(context, file) { progress(50 + it / 2) }
            Container.UNKNOWN -> throw IOException("This file is not a ZIP or RAR comic archive.")
        }
    }

    private enum class Container { ZIP, RAR, UNKNOWN }

    private fun sniff(file: File): Container = try {
        val head = ByteArray(8)
        val read = FileInputStream(file).use { it.read(head) }
        when {
            read >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> Container.ZIP
            read >= 4 && head[0] == 'R'.code.toByte() && head[1] == 'a'.code.toByte() &&
                head[2] == 'r'.code.toByte() && head[3] == '!'.code.toByte() -> Container.RAR
            else -> Container.UNKNOWN
        }
    } catch (_: Exception) {
        Container.UNKNOWN
    }

    fun openZip(file: File): ZipFile = try {
        ZipFile(file)
    } catch (error: Exception) {
        // Some comics use file names in an older encoding. Reading them as Latin-1 never fails.
        if (Build.VERSION.SDK_INT >= 24) ZipFile(file, Charsets.ISO_8859_1) else throw error
    }

    private fun extractRar(context: Context, rar: File, progress: (Int) -> Unit): ComicSource {
        val outDir = File(ArchiveCache.dir(context), rar.name.substringBeforeLast('.') + "_pages")
        val done = File(outDir, ".done")
        if (!done.isFile) {
            outDir.deleteRecursively()
            outDir.mkdirs()
            try {
                Archive(rar).use { archive ->
                    val headers = archive.fileHeaders.filter { !it.isDirectory && BookFormats.isImageEntry(it.fileName) }
                    // Extract in the archive's own order (solid archives need that), then sort by name.
                    val extracted = ArrayList<Pair<String, String>>()
                    headers.forEachIndexed { index, header ->
                        val fileName = "%06d".format(index)
                        FileOutputStream(File(outDir, fileName)).use { archive.extractFile(header, it) }
                        extracted += header.fileName.replace('\\', '/') to fileName
                        progress(((index + 1) * 100) / headers.size.coerceAtLeast(1))
                    }
                    val order = extracted.sortedWith { a, b -> BookFormats.naturalCompare(a.first, b.first) }
                    done.writeText(order.joinToString("\n") { it.second })
                }
            } catch (error: Throwable) {
                outDir.deleteRecursively()
                val kind = error.javaClass.simpleName
                if (kind.contains("V5") || error.message.orEmpty().contains("RAR5", ignoreCase = true)) {
                    throw IOException(
                        "This comic uses the newer RAR 5 format, which Kontho cannot read yet. " +
                            "Convert it to CBZ (ZIP) and try again."
                    )
                }
                throw IOException("Could not read this RAR comic: " + (error.message ?: kind))
            }
        }
        done.setLastModified(System.currentTimeMillis())
        outDir.setLastModified(System.currentTimeMillis())
        val files = done.readLines().filter { it.isNotBlank() }.map { File(outDir, it) }.filter { it.isFile }
        return FileComicSource(files)
    }
}

private class ZipComicSource(private val zip: ZipFile) : ComicSource {
    private val entries: List<ZipEntry> = zip.entries().toList()
        .filter { !it.isDirectory && BookFormats.isImageEntry(it.name) }
        .sortedWith { a, b -> BookFormats.naturalCompare(a.name, b.name) }

    override val pageCount: Int get() = entries.size
    override fun open(index: Int): InputStream = zip.getInputStream(entries[index])
    override fun close() = zip.close()
}

private class FileComicSource(private val files: List<File>) : ComicSource {
    override val pageCount: Int get() = files.size
    override fun open(index: Int): InputStream = FileInputStream(files[index])
    override fun close() {}
}
