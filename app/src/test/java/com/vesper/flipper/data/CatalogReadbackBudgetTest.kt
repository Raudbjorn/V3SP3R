package com.vesper.flipper.data

import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.domain.model.DeviceInfo
import com.vesper.flipper.domain.model.FileEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class CatalogReadbackBudgetTest {
    @Test fun `catalog adapter supplies expected binary size for temporary and activated readback`() = runBlocking<Unit> {
        val fileSystem = mock<FlipperFileSystem>()
        val catalog = mock<FlipperAppCatalog>()
        val info = DeviceInfo("Flipper", "test", "7", 90, false, "f7", "87.0")
        val bytes = ByteArray(512 * 1024) { (it % 251).toByte() }
        val binary = CatalogBinary("demo", "Demo", "1", bytes, "https://catalog.example/demo", "Tools")
        val files = mutableMapOf<String, ByteArray>()
        val reads = mutableListOf<String>()
        whenever(fileSystem.getDeviceInfo()).thenReturn(Result.success(info))
        whenever(catalog.download("demo", info)).thenReturn(binary)
        whenever(fileSystem.listDirectory(any())).thenAnswer { call ->
            val path = call.getArgument<String>(0)
            // Dynamic Mockito answers return Result's erased success payload.
            if (path == "/ext/apps") listOf(FileEntry("Tools", "/ext/apps/Tools", true))
                else files.keys.filter { it.substringBeforeLast('/') == path }.map { FileEntry(it.substringAfterLast('/'), it, false) }
        }
        whenever(fileSystem.writeFileBytes(any(), any())).thenAnswer { call ->
            files[call.getArgument(0)] = call.getArgument<ByteArray>(1).copyOf()
            call.getArgument<ByteArray>(1).size.toLong()
        }
        whenever(fileSystem.readFileBytes(any(), anyOrNull())).thenAnswer { call ->
            val path = call.getArgument<String>(0)
            assertEquals(bytes.size.toLong(), call.getArgument<Long>(1))
            reads += path
            files.getValue(path)
        }
        whenever(fileSystem.move(any(), any())).thenAnswer { call ->
            files[call.getArgument(1)] = requireNotNull(files.remove(call.getArgument<String>(0)))
            Unit
        }
        val result = installCatalogApp(fileSystem, catalog, "demo")
        assertTrue(result.startsWith("Installed Demo"))
        assertEquals(2, reads.size)
        assertTrue(reads.first().endsWith(".part"))
        assertEquals("/ext/apps/Tools/demo.fap", reads.last())
        assertArrayEquals(bytes, files.getValue(reads.last()))
        verify(fileSystem, never()).readFileBytes(any())
        verify(fileSystem, times(2)).readFileBytes(any(), eq(bytes.size.toLong()))
    }
}
