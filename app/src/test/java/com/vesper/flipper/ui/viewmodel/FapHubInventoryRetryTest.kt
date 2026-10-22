package com.vesper.flipper.ui.viewmodel

import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.data.FlipperAppCatalog
import com.vesper.flipper.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FapHubInventoryRetryTest {
    @Test fun `retry skips paths already removed before a partial failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fs = mock<FlipperFileSystem>()
            val first = "/ext/apps/demo.fap"
            val second = "/ext/apps/tools/demo.fap"
            whenever(fs.listDirectory("/ext/apps")).thenReturn(Result.success(listOf(
                FileEntry("demo.fap", first, false), FileEntry("tools", "/ext/apps/tools", true)
            )))
            whenever(fs.listDirectory("/ext/apps/tools")).thenReturn(Result.success(listOf(FileEntry("demo.fap", second, false))))
            whenever(fs.deleteFile(first)).thenReturn(Result.success(Unit))
            whenever(fs.deleteFile(second)).thenReturn(Result.failure(IOException("disconnected")), Result.success(Unit))
            val catalog = mock<FlipperAppCatalog>()
            whenever(catalog.apps()).thenReturn(emptyList())
            val vm = FapHubViewModel(fs, catalog)
            advanceUntilIdle()
            assertTrue(vm.inventoryLoaded.value)
            val app = FapApp("demo", "Demo", "", FapCategory.TOOLS, "", "", "")
            vm.uninstallApp(app)
            advanceUntilIdle()
            vm.uninstallApp(app)
            advanceUntilIdle()
            verify(fs, times(1)).deleteFile(first)
            verify(fs, times(2)).deleteFile(second)
        } finally { Dispatchers.resetMain() }
    }
}
