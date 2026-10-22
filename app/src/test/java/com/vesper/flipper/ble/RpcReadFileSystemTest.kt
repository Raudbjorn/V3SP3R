package com.vesper.flipper.ble

import com.vesper.flipper.security.SecurityUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class RpcReadFileSystemTest {
    @Test fun `verified binary timeout preserves failure without textual CLI fallback`() = runBlocking<Unit> {
        val protocol = mock<FlipperProtocol>()
        val security = mock<SecurityUtils>()
        val path = "/ext/apps/Tools/example.fap"
        whenever(security.validatePath(path)).thenReturn(path)
        whenever(protocol.readFileBinary(path, 512L * 1024)).thenReturn(
            ProtocolResponse.Error("RPC storage read timed out before completion"))
        val result = FlipperFileSystem(protocol, security).readFileBytes(path, 512L * 1024)
        assertTrue(result.isFailure)
        verify(protocol).readFileBinary(path, 512L * 1024)
        verify(protocol, never()).sendCliCommand(any())
    }
}
