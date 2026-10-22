package com.vesper.flipper.ble
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.*

class TypedUsbTransportTest {
    @Test fun typedCommandRefusesBleWithoutWriting() = runBlocking {
        val service=mock<FlipperBleService>()
        whenever(service.usesUsbCommandTransport()).thenReturn(false)
        val protocol=FlipperProtocol().apply { setBleService(service) }
        assertTrue(protocol.sendTypedUsbCommand("gpio read PA7") is ProtocolResponse.Error)
        verify(service,never()).sendUsbDataOnly(any())
        verify(service,never()).sendData(any(),any(),any())
    }
    @Test fun failedUsbWriteNeverRetriesOrFallsBackToBle() = runBlocking {
        val service=mock<FlipperBleService>()
        whenever(service.usesUsbCommandTransport()).thenReturn(true)
        whenever(service.sendUsbDataOnly(any())).thenReturn(false)
        val protocol=FlipperProtocol().apply { setBleService(service) }
        assertTrue(protocol.sendTypedUsbCommand("gpio set PA7 1") is ProtocolResponse.Error)
        verify(service,times(1)).sendUsbDataOnly(any())
        verify(service,never()).sendData(any(),any(),any())
    }
}
