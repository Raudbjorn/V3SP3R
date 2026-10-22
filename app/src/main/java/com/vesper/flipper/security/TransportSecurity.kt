package com.vesper.flipper.security

import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import java.net.URI

object TransportSecurity {
    val tlsOnly = listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2).build())

    /** Preserve explicit local debug bridges. Never silently upgrade a remote cleartext endpoint. */
    fun bridgeUrl(raw: String, debug: Boolean): String? = runCatching {
        val value = raw.trim()
        require(value.length in 1..2048)
        val normalized = when {
            value.startsWith("https://", true) -> "wss://" + value.substring(8)
            value.startsWith("http://", true) -> "ws://" + value.substring(7)
            "://" !in value -> "wss://$value"
            else -> value
        }
        val uri = URI(normalized)
        require(uri.scheme.lowercase() in setOf("ws", "wss"))
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        if (uri.scheme.equals("ws", true)) {
            require(debug && uri.host.lowercase() in setOf("localhost", "127.0.0.1", "10.0.2.2"))
        }
        normalized
    }.getOrNull()
}
