package org.claudeproxy.api

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InternalAddressTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `rejects CGNAT and tailnet ranges`() {
        assertTrue(isInternalAddress(ip("100.64.0.1")))
        assertTrue(isInternalAddress(ip("100.127.255.254")))
        assertTrue(isInternalAddress(ip("fd7a:115c:a1e0::1")))
    }

    @Test
    fun `rejects private and loopback`() {
        listOf("127.0.0.1", "10.0.0.1", "172.17.0.1", "192.168.1.1", "169.254.1.1", "0.0.0.0", "::1")
            .forEach { assertTrue(isInternalAddress(ip(it)), it) }
    }

    @Test
    fun `accepts public addresses next to CGNAT`() {
        assertFalse(isInternalAddress(ip("100.63.255.255")))
        assertFalse(isInternalAddress(ip("100.128.0.1")))
        assertFalse(isInternalAddress(ip("1.1.1.1")))
    }
}
