package com.vesper.flipper.diagnostics

import com.vesper.flipper.ble.BleServiceManager
import com.vesper.flipper.ble.FlipperFileSystem
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** In-process instrumentation access only. No exported activity, service, or remote endpoint. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MaintenanceTestAccess {
    fun serviceManager(): BleServiceManager
    fun fileSystem(): FlipperFileSystem
}
