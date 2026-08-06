package com.hsucode.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProotInstallSafetyTest {
    @Test
    fun selectsFirstPackaged64BitAbi() {
        assertEquals("arm64-v8a", ProotInstallSafety.selectSupportedAbi(listOf("armeabi-v7a", "arm64-v8a")))
        assertEquals("x86_64", ProotInstallSafety.selectSupportedAbi(listOf("x86_64")))
        assertNull(ProotInstallSafety.selectSupportedAbi(listOf("armeabi-v7a", "x86")))
    }

    @Test
    fun normalizesHarmlessTarPaths() {
        assertEquals("usr/bin/bash", ProotInstallSafety.normalizeTarPath("./usr/bin/bash"))
        assertEquals("etc/hosts", ProotInstallSafety.normalizeTarPath("/etc/hosts"))
        assertEquals("usr/share/doc", ProotInstallSafety.normalizeTarPath("usr\\share\\doc"))
    }

    @Test
    fun rejectsArchiveTraversalAndBlankPaths() {
        assertThrows(IllegalArgumentException::class.java) { ProotInstallSafety.normalizeTarPath("../../data/data") }
        assertThrows(IllegalArgumentException::class.java) { ProotInstallSafety.normalizeTarPath("usr/../etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { ProotInstallSafety.normalizeTarPath("   ") }
    }
}
