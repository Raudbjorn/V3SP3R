package com.vesper.flipper.ble

/** Conservative readback allowance, not a guarantee of physical BLE throughput. */
internal object RpcReadBudget {
    const val MAX_EXPECTED_BYTES = 10L * 1024 * 1024
    const val MAX_TIMEOUT_MS = 25L * 60 * 1000
    const val BASE_TIMEOUT_MS = 4_000L
    private const val BYTES_PER_SECOND = 8L * 1024

    fun accepts(expectedSizeBytes: Long?): Boolean =
        expectedSizeBytes == null || expectedSizeBytes in 0..MAX_EXPECTED_BYTES

    fun timeoutMs(expectedSizeBytes: Long?): Long {
        require(accepts(expectedSizeBytes)) { "Expected read size must be between 0 and 10 MiB" }
        if (expectedSizeBytes == null) return BASE_TIMEOUT_MS
        val transferMs = (expectedSizeBytes * 1000L + BYTES_PER_SECOND - 1) / BYTES_PER_SECOND
        return (BASE_TIMEOUT_MS + transferMs).coerceAtMost(MAX_TIMEOUT_MS)
    }
}
