package com.vesper.flipper.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vesper.flipper.ble.ConnectionState
import com.vesper.flipper.ble.FlipperDevice
import dagger.hilt.EntryPoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicit opt-in, using real app services. Does not call an AI model or transmit saved signals. */
@RunWith(AndroidJUnit4::class)
class DeviceAcceptanceTest {
    @Test fun readTransferAndReconnect() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val address = args.getString("flipperAddress")
        assumeTrue("Supply flipperAddress for an authorized paired device", !address.isNullOrBlank())
        require(address!!.matches(Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val access = EntryPoints.get(context, MaintenanceTestAccess::class.java)
        val manager = access.serviceManager()
        val files = access.fileSystem()
        val service = checkNotNull(manager.awaitService()) { "BLE service did not bind" }

        suspend fun connect() {
            withContext(Dispatchers.Main) { service.connect(FlipperDevice(address, "Acceptance test", 0)) }
            val state = withTimeout(45_000) {
                service.connectionState.first { it is ConnectionState.Connected || it is ConnectionState.Error }
            }
            check(state is ConnectionState.Connected) { "Connection failed: $state" }
        }

        try {
            connect()
            withTimeout(30_000) { files.getDeviceInfo().getOrThrow() }
            withTimeout(30_000) { files.listDirectory("/ext/apps").getOrThrow() }
            if (args.getString("writeReadback") == "true") {
                // New UUID path; never overwrite user files and retain the test file for inspection.
                val scratch = "/ext/vesper-acceptance-${UUID.randomUUID()}.txt"
                val bytes = "Vesper maintenance acceptance test\n".repeat(256).toByteArray()
                withTimeout(90_000) {
                    files.writeFileBytes(scratch, bytes).getOrThrow()
                    assertArrayEquals(bytes, files.readFileBytes(scratch).getOrThrow())
                }
            }
            withContext(Dispatchers.Main) { service.disconnect() }
            withTimeout(10_000) { service.connectionState.first { it !is ConnectionState.Connected } }
            connect()
            withTimeout(30_000) { files.getDeviceInfo().getOrThrow() }
            withTimeout(30_000) { files.listDirectory("/ext/apps").getOrThrow() }
        } finally {
            withContext(Dispatchers.Main) { service.disconnect() }
        }
        Unit
    }
}
