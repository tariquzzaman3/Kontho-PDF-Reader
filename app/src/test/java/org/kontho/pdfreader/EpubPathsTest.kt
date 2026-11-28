// SPDX-License-Identifier: GPL-3.0-or-later
package org.kontho.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubPathsTest {
    @Test
    fun resolvesRelativeLinks() {
        assertEquals("OEBPS/text/ch1.xhtml", EpubPaths.resolve("OEBPS", "text/ch1.xhtml"))
        assertEquals("OEBPS/images/a.png", EpubPaths.resolve("OEBPS/text", "../images/a.png"))
        assertEquals("ch1.xhtml", EpubPaths.resolve("", "./ch1.xhtml"))
    }

    @Test
    fun dropsFragmentsAndDecodesNames() {
        assertEquals("OEBPS/ch 1.xhtml", EpubPaths.resolve("OEBPS", "ch%201.xhtml#part2"))
        assertEquals("a+b.xhtml", EpubPaths.resolve("", "a+b.xhtml"))
    }

    @Test
    fun cannotEscapeTheBook() {
        assertEquals("secret.txt", EpubPaths.resolve("OEBPS", "../../../secret.txt"))
        assertEquals("x/y.html", EpubPaths.resolve("a", "/x/y.html"))
    }

    @Test
    fun findsDirectory() {
        assertEquals("OEBPS/text", EpubPaths.directoryOf("OEBPS/text/ch1.xhtml"))
        assertEquals("", EpubPaths.directoryOf("content.opf"))
    }
}
