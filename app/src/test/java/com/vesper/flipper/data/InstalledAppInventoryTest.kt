package com.vesper.flipper.data

import com.vesper.flipper.domain.model.FileEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class InstalledAppInventoryTest {
    private fun entry(path: String, directory: Boolean = false) =
        FileEntry(path.substringAfterLast('/'), path, directory, 0)

    @Test fun `finds nested apps and preserves duplicate locations`() = runBlocking {
        val tree = mapOf(
            "/ext/apps" to listOf(entry("/ext/apps/tools", true), entry("/ext/apps/demo.fap")),
            "/ext/apps/tools" to listOf(entry("/ext/apps/tools/nested", true)),
            "/ext/apps/tools/nested" to listOf(entry("/ext/apps/tools/nested/demo.fap"))
        )
        val result = readInstalledAppInventory { tree.getValue(it) }
        assertEquals(setOf("demo"), result.keys)
        assertEquals(listOf("/ext/apps/demo.fap", "/ext/apps/tools/nested/demo.fap"), result.getValue("demo").sorted())
    }

    @Test fun `rejects paths outside inventory and traversal`() = runBlocking {
        for (path in listOf("/int/secret.fap", "/ext/apps/../secret.fap", "/ext/apps-other/evil.fap")) {
            try {
                readInstalledAppInventory { listOf(entry(path)) }
                fail("Must reject unsafe device entry: $path")
            } catch (_: IOException) { }
        }
    }

    @Test fun `directory failures propagate instead of reporting empty inventory`() = runBlocking {
        try {
            readInstalledAppInventory { throw IOException("disconnected") }
            fail("Must report disconnected inventory")
        } catch (error: IOException) { assertEquals("disconnected", error.message) }
    }

    @Test fun `bounds cyclic or oversized directory trees`() = runBlocking {
        var calls = 0
        try {
            readInstalledAppInventory { path -> calls++; listOf(entry("$path/deeper", true)) }
            fail("Must bound recursion")
        } catch (_: IOException) { assertTrue(calls <= 10) }
    }
}
