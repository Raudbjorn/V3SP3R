package com.vesper.flipper.security

import okhttp3.TlsVersion
import org.junit.Assert.*
import org.junit.Test

class TransportSecurityTest {
    @Test fun remoteEndpointsRequireTls() {
        assertNull(TransportSecurity.bridgeUrl("ws://example.com", false))
        assertNull(TransportSecurity.bridgeUrl("http://example.com", true))
        assertEquals("wss://example.com/path", TransportSecurity.bridgeUrl("https://example.com/path", false))
    }
    @Test fun onlyExplicitDebugLoopbackAllowsCleartext() {
        for (host in listOf("localhost", "127.0.0.1", "10.0.2.2")) {
            assertEquals("ws://$host:3000", TransportSecurity.bridgeUrl("ws://$host:3000", true))
            assertNull(TransportSecurity.bridgeUrl("ws://$host:3000", false))
        }
        for (host in listOf("192.168.1.1", "localhost.example.com", "127.0.0.1.example.com")) {
            assertNull(TransportSecurity.bridgeUrl("ws://$host", true))
        }
    }
    @Test fun malformedAndEmbeddedCredentialsAreRejected() {
        for (url in listOf("", "wss:///", "wss://user:password@example.com", "wss://example.com#fragment", "ftp://example.com", "wss://example.com:70000")) {
            assertNull(TransportSecurity.bridgeUrl(url, true))
        }
    }
    @Test fun tlsPolicyDisallowsLegacyAndCleartext() {
        assertEquals(1, TransportSecurity.tlsOnly.size)
        assertTrue(TransportSecurity.tlsOnly.single().isTls)
        assertEquals(listOf(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2), TransportSecurity.tlsOnly.single().tlsVersions)
    }
}
