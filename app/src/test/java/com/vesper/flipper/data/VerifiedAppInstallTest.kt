package com.vesper.flipper.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class VerifiedAppInstallTest {
    private class Storage : AppInstallStorage {
        val files = linkedMapOf<String, ByteArray>()
        val calls = mutableListOf<String>()
        var corrupt = false
        override suspend fun exists(path: String) = files.containsKey(path)
        override suspend fun write(path: String, bytes: ByteArray) { calls += "write"; files[path] = bytes.copyOf() }
        override suspend fun read(path: String): ByteArray { calls += "read"; return if (corrupt) byteArrayOf(0) else files.getValue(path) }
        override suspend fun move(source: String, target: String) { calls += "move"; files[target] = files.remove(source)!! }
        override suspend fun delete(path: String) { calls += "delete"; files.remove(path) }
    }

    @Test fun `activates only after exact binary readback`() = runBlocking {
        val storage = Storage()
        val bytes = byteArrayOf(0, 127, -1, -128, 10)
        installVerifiedApp(storage, "/ext/apps/Tools/demo.fap", bytes)
        assertArrayEquals(bytes, storage.files.getValue("/ext/apps/Tools/demo.fap"))
        assertEquals(listOf("write", "read", "move"), storage.calls.take(3))
        assertEquals(1, storage.files.size)
    }

    @Test fun `corrupt transfer is never activated and scratch is removed`() = runBlocking {
        val storage = Storage().apply { corrupt = true }
        try {
            installVerifiedApp(storage, "/ext/apps/Tools/demo.fap", byteArrayOf(1, 2))
            fail("Corrupt upload must fail")
        } catch (_: IOException) { }
        assertFalse(storage.calls.contains("move"))
        assertTrue(storage.files.isEmpty())
    }

    @Test fun `same-name app is never overwritten`() = runBlocking {
        val storage = Storage()
        storage.files["/ext/apps/Tools/demo.fap"] = byteArrayOf(42)
        try {
            installVerifiedApp(storage, "/ext/apps/Tools/demo.fap", byteArrayOf(1))
            fail("Existing app must be preserved")
        } catch (_: IOException) { }
        assertArrayEquals(byteArrayOf(42), storage.files.getValue("/ext/apps/Tools/demo.fap"))
        assertTrue(storage.calls.isEmpty())
    }

    @Test fun `write failure cleans partial upload without touching other files`() = runBlocking {
        val storage = Storage()
        val failing = object : AppInstallStorage by storage {
            override suspend fun write(path: String, bytes: ByteArray) {
                storage.write(path, byteArrayOf(99))
                throw IOException("Disconnected")
            }
        }
        try { installVerifiedApp(failing, "/ext/apps/Tools/demo.fap", byteArrayOf(1)); fail("Must fail") }
        catch (_: IOException) { }
        assertTrue(storage.files.isEmpty())
    }
}
