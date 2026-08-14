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

    @Test
    fun uvInstallAvoidsPep668SystemPythonWrite() {
        val uv = EnvCatalog.allTools.first { it.id == "uv" }

        assertTrue(uv.installCmd.contains("python3 -m venv /opt/hsucode-uv"))
        assertTrue(uv.installCmd.contains("/opt/hsucode-uv/bin/python -m pip"))
        assertFalse(uv.installCmd.contains("pip3 install -U uv"))
    }

    @Test
    fun optionalInstallCommandsRecoverFromPartialSetup() {
        val byId = EnvCatalog.allTools.associateBy { it.id }

        assertTrue(byId.getValue("pnpm").installCmd.startsWith("apt-get install -y nodejs npm"))
        assertTrue(byId.getValue("pnpm").detectCmd.contains("npm root -g"))
        assertTrue(byId.getValue("pnpm").installCmd.contains("npm config delete registry"))
        assertTrue(byId.getValue("venv").detectCmd.contains("import ensurepip"))
        assertFalse(byId.getValue("rust").installCmd.contains("/root/.cargo/bin/*"))
        assertTrue(byId.getValue("android_ndk").installCmd.contains("test -x /opt/android-sdk/cmdline-tools/cmdline-tools/bin/sdkmanager"))
    }
}
