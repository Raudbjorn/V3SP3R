package com.vesper.flipper.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

internal interface AppInstallStorage {
    suspend fun exists(path: String): Boolean
    suspend fun write(path: String, bytes: ByteArray)
    suspend fun read(path: String): ByteArray
    suspend fun move(source: String, target: String)
    suspend fun delete(path: String)
}

private val catalogInstallMutex = Mutex()

/** Serialize the UI and AI install paths; only an exact readback may become a runnable app. */
internal suspend fun installVerifiedApp(storage: AppInstallStorage, target: String, bytes: ByteArray) {
    catalogInstallMutex.withLock {
        if (storage.exists(target)) throw IOException("Already present at $target; existing app was preserved")
        val temporary = "$target.vesper-${UUID.randomUUID()}.part"
        var activated = false
        try {
            storage.write(temporary, bytes)
            if (!storage.read(temporary).contentEquals(bytes)) {
                throw IOException("Upload verification failed; app was not activated")
            }
            if (storage.exists(target)) throw IOException("App appeared during upload; existing app was preserved")
            storage.move(temporary, target)
            activated = true
        } finally {
            if (!activated) withContext(NonCancellable) {
                // Best effort after disconnect/cancellation. Never delete the destination or mask the cause.
                withTimeoutOrNull(5_000) { runCatching { storage.delete(temporary) } }
            }
        }
    }
}
