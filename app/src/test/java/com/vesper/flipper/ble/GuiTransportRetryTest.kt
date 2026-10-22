package com.vesper.flipper.ble

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class GuiTransportRetryTest {
    @Test fun `RPC non-retry policy reaches BLE transport while release may retry`() = runBlocking<Unit> {
        val service = mock<FlipperBleService>()
        whenever(service.sendData(any(), any(), any(), any())).thenReturn(false)
        val protocol = FlipperProtocol()
        FlipperProtocol::class.java.getDeclaredField("bleService").apply { isAccessible = true }.set(protocol, service)
        val collect = FlipperProtocol::class.java.getDeclaredMethod(
            "collectRpcMainResponsesOnce", ByteArray::class.java, Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Continuation::class.java
        ).apply { isAccessible = true }
        val payload = byteArrayOf(1, 2, 3)
        suspend fun invoke(allowRetry: Boolean) = suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            collect.invoke(protocol, payload, 1, 10L, allowRetry, continuation)
        }
        invoke(false)
        verify(service).sendData(payload, false, false, false)
        invoke(true)
        verify(service).sendData(payload, false, false, true)
    }
}
