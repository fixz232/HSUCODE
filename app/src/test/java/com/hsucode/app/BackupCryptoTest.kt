/*
 * Modification notice (2026-08-04 17:26 UTC+08:00): HSUCODE is a modified work based on
 * https://github.com/kusesad-1122/XINCODE-Public.
 * Change: tests encrypted backup envelope round trips and wrong-password rejection.
 * Existing copyright, license, and author notices are retained.
 */
package com.hsucode.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupCryptoTest {
    @Test
    fun encryptedEnvelopeRoundTrips() {
        val plain = ByteArray(32_000) { (it % 251).toByte() }
        val encoded = ByteArrayOutputStream().also { output ->
            BackupCrypto.encryptedOutput(output, "correct horse".toCharArray()).use { it.write(plain) }
        }.toByteArray()

        val decoded = BackupCrypto.decryptedInput(
            ByteArrayInputStream(encoded), "correct horse".toCharArray()
        ).use { it.readBytes() }
        assertArrayEquals(plain, decoded)
    }

    @Test
    fun wrongPasswordDoesNotYieldData() {
        val encoded = ByteArrayOutputStream().also { output ->
            BackupCrypto.encryptedOutput(output, "right-password".toCharArray()).use {
                it.write("private".toByteArray())
            }
        }.toByteArray()

        assertThrows(Exception::class.java) {
            BackupCrypto.decryptedInput(ByteArrayInputStream(encoded), "wrong-password".toCharArray())
                .use { it.readBytes() }
        }
    }
}
