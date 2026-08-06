package com.hsucode.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvCatalogTest {
    @Test
    fun firstRunSelectsOnlyCoreNodeAndPythonTools() {
        val defaults = EnvCatalog.defaultToolIds

        assertEquals(setOf("node", "python_link", "venv", "pip"), defaults)
        assertFalse("Android SDK must never be a surprise first-run download", "android_ndk" in defaults)
        assertFalse("Rust must stay an explicit opt-in", "rust" in defaults)
    }

    @Test
    fun defaultToolIdsAlwaysReferenceCatalogEntries() {
        val ids = EnvCatalog.allTools.map { it.id }.toSet()

        assertTrue(EnvCatalog.defaultToolIds.all { it in ids })
    }
}
