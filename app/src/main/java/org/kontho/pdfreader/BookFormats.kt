// SPDX-License-Identifier: GPL-3.0-or-later
package org.kontho.pdfreader

import android.net.Uri
import java.security.MessageDigest
import java.util.Locale

enum class BookKind { PDF, EPUB, COMIC }

/** Which files Kontho can read, and small helpers shared by the PDF, EPUB and comic readers. */
object BookFormats {
    const val EXTRA_URI = "org.kontho.pdfreader.extra.BOOK_URI"
    const val EXTRA_NAME = "org.kontho.pdfreader.extra.BOOK_NAME"

    /** Shown by the file picker. Comics are often labelled zip/rar/octet-stream, so those are allowed too. */
    val PICKER_TYPES = arrayOf(
        "application/pdf",
        "application/epub+zip",
        "application/vnd.comicbook+zip",
        "application/vnd.comicbook-rar",
        "application/x-cbz",
        "application/x-cbr",
        "application/zip",
        "application/x-rar-compressed",
        "application/vnd.rar",
        "application/octet-stream"
    )

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun extensionOf(name: String?): String =
        name.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun kindOf(name: String?): BookKind? = when (extensionOf(name)) {
        "pdf" -> BookKind.PDF
        "epub" -> BookKind.EPUB
        "cbz", "cbr" -> BookKind.COMIC
        else -> null
    }

    fun extensionForMime(mime: String?): String? = when (mime?.lowercase(Locale.ROOT)) {
        "application/epub+zip" -> "epub"
        "application/vnd.comicbook+zip", "application/x-cbz" -> "cbz"
        "application/vnd.comicbook-rar", "application/x-cbr" -> "cbr"
        else -> null
    }

    /** "Naruto 01.cbz" → "Naruto 01". Names with an unknown ending are left alone. */
    fun title(name: String): String = if (kindOf(name) != null) name.substringBeforeLast('.') else name

    /** True for a real page image inside an archive (not a folder, a hidden file or macOS metadata). */
    fun isImageEntry(path: String): Boolean {
        val normal = path.replace('\\', '/')
        if (normal.endsWith("/") || normal.contains("__MACOSX/")) return false
        val file = normal.substringAfterLast('/')
        if (file.startsWith(".")) return false
        return extensionOf(file) in imageExtensions
    }

    /** Orders "page2" before "page10", ignoring case. */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ie = i
                while (ie < a.length && a[ie].isDigit()) ie++
                var je = j
                while (je < b.length && b[je].isDigit()) je++
                val na = a.substring(i, ie).trimStart('0')
                val nb = b.substring(j, je).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ie
                j = je
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /** Short stable id for saving a book's reading position. */
    fun key(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
