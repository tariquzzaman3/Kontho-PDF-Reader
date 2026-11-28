// SPDX-License-Identifier: GPL-3.0-or-later
package org.kontho.pdfreader

import android.util.Xml
import android.webkit.MimeTypeMap
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

/** Path helpers for the zip-style addresses inside an EPUB. */
object EpubPaths {
    /** Resolves [href] (as written in a book file inside [baseDir]) to a plain zip entry path. */
    fun resolve(baseDir: String, href: String): String {
        val clean = href.substringBefore('#')
        val decoded = try {
            URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            clean
        }
        val joined = when {
            decoded.startsWith("/") -> decoded.removePrefix("/")
            baseDir.isEmpty() -> decoded
            else -> "$baseDir/$decoded"
        }
        val parts = ArrayList<String>()
        for (segment in joined.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    fun directoryOf(path: String): String = if (path.contains('/')) path.substringBeforeLast('/') else ""
}

/**
 * A read-only view of an EPUB: its chapters in reading order, contents list, cover and files.
 * Only the standard container/OPF/NCX/nav files are read; scripts in a book are never run.
 */
class EpubBook private constructor(
    private val zip: ZipFile,
    val title: String,
    val spine: List<String>,
    val toc: List<TocEntry>,
    private val mediaTypes: Map<String, String>,
    private val coverPath: String?,
    private val entryNames: Map<String, String>
) : Closeable {

    data class TocEntry(val title: String, val path: String, val fragment: String?)

    fun read(path: String): ByteArray? {
        val real = entryNames[path.lowercase(Locale.ROOT)] ?: return null
        val entry = zip.getEntry(real) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun mimeOf(path: String): String {
        mediaTypes[path]?.let { return it }
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    /** The cover image path, or the first picture in the book when none is declared. */
    fun coverImagePath(): String? =
        coverPath ?: mediaTypes.entries.firstOrNull { it.value.startsWith("image/") }?.key

    fun chapterTitle(path: String): String? = toc.firstOrNull { it.path == path }?.title

    override fun close() = zip.close()

    private class Item(val path: String, val mediaType: String, val properties: String)
    private class Opf(
        val title: String,
        val manifest: Map<String, Item>,
        val spineIds: List<String>,
        val coverId: String?,
        val tocId: String?
    )

    companion object {
        fun open(file: File): EpubBook {
            val zip = ComicArchive.openZip(file)
            try {
                val names = HashMap<String, String>()
                val all = zip.entries()
                while (all.hasMoreElements()) {
                    val entry = all.nextElement()
                    if (!entry.isDirectory) names[entry.name.lowercase(Locale.ROOT)] = entry.name
                }
                fun bytes(path: String): ByteArray? {
                    val real = names[path.lowercase(Locale.ROOT)] ?: return null
                    val entry = zip.getEntry(real) ?: return null
                    return zip.getInputStream(entry).use { it.readBytes() }
                }

                val container = bytes("META-INF/container.xml") ?: throw IOException("This is not a valid EPUB (no container file).")
                val opfPath = rootFilePath(container) ?: throw IOException("This is not a valid EPUB (no package file).")
                val opfBytes = bytes(opfPath) ?: throw IOException("This EPUB's package file is missing.")
                val opfDir = EpubPaths.directoryOf(opfPath)
                val opf = parseOpf(opfBytes, opfDir)

                val spine = opf.spineIds.mapNotNull { opf.manifest[it]?.path }
                    .filter { path -> opf.manifest.values.any { it.path == path && it.mediaType.contains("html") } }
                if (spine.isEmpty()) throw IOException("This EPUB has no readable chapters.")

                val mediaTypes = LinkedHashMap<String, String>()
                opf.manifest.values.forEach { mediaTypes[it.path] = it.mediaType }

                val cover = opf.manifest.values.firstOrNull { it.properties.split(' ').contains("cover-image") }?.path
                    ?: opf.coverId?.let { opf.manifest[it]?.path }

                var toc: List<TocEntry> = emptyList()
                val ncx = opf.tocId?.let { opf.manifest[it] }
                    ?: opf.manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
                if (ncx != null) {
                    toc = try {
                        bytes(ncx.path)?.let { parseNcx(it, EpubPaths.directoryOf(ncx.path)) }.orEmpty()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                if (toc.isEmpty()) {
                    val nav = opf.manifest.values.firstOrNull { it.properties.split(' ').contains("nav") }
                    if (nav != null) {
                        toc = try {
                            bytes(nav.path)?.let { parseNav(it, EpubPaths.directoryOf(nav.path)) }.orEmpty()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }
                val title = opf.title.ifBlank { file.nameWithoutExtension }
                return EpubBook(zip, title, spine, toc, mediaTypes, cover, names)
            } catch (error: Throwable) {
                zip.close()
                throw error
            }
        }

        private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
            setInput(ByteArrayInputStream(bytes), null)
        }

        private fun rootFilePath(bytes: ByteArray): String? {
            val parser = parser(bytes)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name.substringAfter(':') == "rootfile") {
                    parser.getAttributeValue(null, "full-path")?.let { return it }
                }
                event = parser.next()
            }
            return null
        }

        private fun parseOpf(bytes: ByteArray, opfDir: String): Opf {
            val parser = parser(bytes)
            val manifest = LinkedHashMap<String, Item>()
            val spineIds = ArrayList<String>()
            var title = ""
            var coverId: String? = null
            var tocId: String? = null
            var inMetadata = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.substringAfter(':')) {
                        "metadata" -> inMetadata = true
                        "title" -> if (inMetadata && title.isEmpty()) {
                            title = try { parser.nextText().trim() } catch (_: Exception) { "" }
                        }
                        "meta" -> if (parser.getAttributeValue(null, "name") == "cover") {
                            coverId = parser.getAttributeValue(null, "content")
                        }
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            if (id != null && href != null) {
                                manifest[id] = Item(
                                    EpubPaths.resolve(opfDir, href),
                                    parser.getAttributeValue(null, "media-type").orEmpty(),
                                    parser.getAttributeValue(null, "properties").orEmpty()
                                )
                            }
                        }
                        "spine" -> tocId = parser.getAttributeValue(null, "toc")
                        "itemref" -> parser.getAttributeValue(null, "idref")?.let { spineIds += it }
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name.substringAfter(':') == "metadata") {
                    inMetadata = false
                }
                event = parser.next()
            }
            return Opf(title, manifest, spineIds, coverId, tocId)
        }

        private fun parseNcx(bytes: ByteArray, baseDir: String): List<TocEntry> {
            val parser = parser(bytes)
            val result = ArrayList<TocEntry>()
            var pendingTitle = ""
            var inLabel = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.substringAfter(':')) {
                        "navLabel" -> inLabel = true
                        "text" -> if (inLabel) pendingTitle = try { parser.nextText().trim() } catch (_: Exception) { "" }
                        "content" -> {
                            val src = parser.getAttributeValue(null, "src")
                            if (src != null) {
                                result += TocEntry(
                                    pendingTitle.ifBlank { "…" },
                                    EpubPaths.resolve(baseDir, src),
                                    src.substringAfter('#', "").ifEmpty { null }
                                )
                                pendingTitle = ""
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name.substringAfter(':') == "navLabel") {
                    inLabel = false
                }
                event = parser.next()
            }
            return result
        }

        /** EPUB 3 contents list: the links inside the <nav epub:type="toc"> element. */
        private fun parseNav(bytes: ByteArray, baseDir: String): List<TocEntry> {
            val parser = parser(bytes)
            val result = ArrayList<TocEntry>()
            var inToc = false
            var navDepth = 0
            var href: String? = null
            val label = StringBuilder()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                        "nav" -> {
                            val type = parser.getAttributeValue(null, "epub:type").orEmpty()
                            if (!inToc && type.split(' ').contains("toc")) {
                                inToc = true
                                navDepth = 1
                            } else if (inToc) navDepth++
                        }
                        "a" -> if (inToc) {
                            href = parser.getAttributeValue(null, "href")
                            label.setLength(0)
                        }
                    }
                    XmlPullParser.TEXT -> if (inToc && href != null) label.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                        "a" -> if (inToc && href != null) {
                            val src = href
                            result += TocEntry(
                                label.toString().trim().replace(Regex("\\s+"), " ").ifBlank { "…" },
                                EpubPaths.resolve(baseDir, src),
                                src.substringAfter('#', "").ifEmpty { null }
                            )
                            href = null
                        }
                        "nav" -> if (inToc) {
                            navDepth--
                            if (navDepth == 0) return result
                        }
                    }
                }
                event = parser.next()
            }
            return result
        }
    }
}
