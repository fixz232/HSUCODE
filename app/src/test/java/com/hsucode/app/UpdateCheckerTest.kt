package com.hsucode.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun releaseChannelPointsToHsucodeRepository() {
        assertTrue(UpdateChecker.RELEASES_API.contains("fixz232/HSUCODE"))
        assertTrue(UpdateChecker.RELEASES_PAGE.contains("fixz232/HSUCODE"))
        assertFalse(UpdateChecker.RELEASES_API.contains("XINCODE"))
    }

    @Test
    fun comparesNumericVersionSegments() {
        assertTrue(UpdateChecker.isNewer("v1.10", "1.09"))
        assertTrue(UpdateChecker.isNewer("2.0.1", "2.0"))
        assertFalse(UpdateChecker.isNewer("1.09", "1.09"))
        assertFalse(UpdateChecker.isNewer("1.08", "1.09"))
    }
}
