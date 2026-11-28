// SPDX-License-Identifier: GPL-3.0-or-later
package org.kontho.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookFormatsTest {
    @Test
    fun recognisesBookKindsByExtension() {
        assertEquals(BookKind.PDF, BookFormats.kindOf("a.PDF"))
        assertEquals(BookKind.EPUB, BookFormats.kindOf("Novel.epub"))
        assertEquals(BookKind.COMIC, BookFormats.kindOf("One Piece 01.cbz"))
        assertEquals(BookKind.COMIC, BookFormats.kindOf("x.CBR"))
        assertNull(BookFormats.kindOf("notes.txt"))
        assertNull(BookFormats.kindOf(null))
    }

    @Test
    fun titlesDropOnlyKnownExtensions() {
        assertEquals("Naruto 01", BookFormats.title("Naruto 01.cbz"))
        assertEquals("report.final", BookFormats.title("report.final.pdf"))
        assertEquals("my.notes", BookFormats.title("my.notes"))
    }

    @Test
    fun mapsMimeTypesToExtensions() {
        assertEquals("epub", BookFormats.extensionForMime("application/epub+zip"))
        assertEquals("cbz", BookFormats.extensionForMime("application/x-cbz"))
        assertEquals("cbr", BookFormats.extensionForMime("application/vnd.comicbook-rar"))
        assertNull(BookFormats.extensionForMime("text/plain"))
    }

    @Test
    fun picksOnlyRealPageImages() {
        assertTrue(BookFormats.isImageEntry("ch1/001.jpg"))
        assertTrue(BookFormats.isImageEntry("Ch1\\002.PNG"))
        assertFalse(BookFormats.isImageEntry("__MACOSX/ch1/._001.jpg"))
        assertFalse(BookFormats.isImageEntry("ch1/"))
        assertFalse(BookFormats.isImageEntry("ComicInfo.xml"))
    }

    @Test
    fun ordersPagesNaturally() {
        val sorted = listOf("p10.jpg", "p2.jpg", "p1.jpg", "P03.jpg").sortedWith { a, b -> BookFormats.naturalCompare(a, b) }
        assertEquals(listOf("p1.jpg", "p2.jpg", "P03.jpg", "p10.jpg"), sorted)
    }
}
