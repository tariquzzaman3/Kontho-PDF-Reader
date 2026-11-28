// SPDX-License-Identifier: GPL-3.0-or-later
package org.kontho.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GithubReleaseCheckerTest {
    @Test
    fun readsTagFromAbsoluteAndRelativeRedirects() {
        assertEquals(
            "v0.7.3",
            GithubReleaseChecker.tagFromLocation("https://github.com/tariquzzaman3/Kontho-PDF-Reader/releases/tag/v0.7.3")
        )
        assertEquals("v0.7.3", GithubReleaseChecker.tagFromLocation("/tariquzzaman3/Kontho-PDF-Reader/releases/tag/v0.7.3"))
    }

    @Test
    fun rejectsUnexpectedLocations() {
        assertNull(GithubReleaseChecker.tagFromLocation(null))
        assertNull(GithubReleaseChecker.tagFromLocation("https://github.com/login"))
        assertNull(GithubReleaseChecker.tagFromLocation("https://github.com/x/y/releases/tag/evil..%2F..%2Fx"))
    }

    @Test
    fun comparesVersionsNumerically() {
        assertEquals(true, GithubReleaseChecker.isNewer("v0.7.10", "0.7.9"))
        assertEquals(false, GithubReleaseChecker.isNewer("v0.7.3", "0.7.3"))
    }
}
