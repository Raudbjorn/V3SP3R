package com.vesper.flipper.ble

import org.junit.Assert.*
import org.junit.Test

class RpcReadBudgetTest {
    @Test fun `sized reads scale beyond command timeout within a documented cap`() {
        assertEquals(4_000L, RpcReadBudget.timeoutMs(null))
        assertEquals(4_000L, RpcReadBudget.timeoutMs(0))
        assertEquals(12_000L, RpcReadBudget.timeoutMs(64 * 1024))
        assertEquals(68_000L, RpcReadBudget.timeoutMs(512 * 1024))
        assertTrue(RpcReadBudget.timeoutMs(RpcReadBudget.MAX_EXPECTED_BYTES) <= RpcReadBudget.MAX_TIMEOUT_MS)
        assertFalse(RpcReadBudget.accepts(-1))
        assertFalse(RpcReadBudget.accepts(Long.MAX_VALUE))
    }
}
