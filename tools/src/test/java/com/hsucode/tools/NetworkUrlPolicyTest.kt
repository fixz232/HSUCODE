package com.hsucode.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NetworkUrlPolicyTest {

    @Test
    fun blocksPrivateAndLoopbackAddresses() {
        listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.1.1", "100.64.0.1", "::1", "fc00::1")
            .forEach { address ->
                assertFalse("expected $address to be blocked", NetworkUrlPolicy.isPublicAddress(InetAddress.getByName(address)))
            }
    }

    @Test
    fun permitsPublicAddresses() {
        assertTrue(NetworkUrlPolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(NetworkUrlPolicy.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }
}
