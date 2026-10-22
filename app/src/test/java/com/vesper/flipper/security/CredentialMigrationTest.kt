package com.vesper.flipper.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CredentialMigrationTest {
    @Test fun migrationCommitsAndReadsBackBeforeCleanup() = runBlocking {
        var secure: String? = null
        var removed = false
        assertEquals("legacy", CredentialMigration.read("legacy", { secure }, {
            assertFalse(removed); secure = it; true
        }, { assertEquals("legacy", secure); removed = true }))
        assertTrue(removed)
    }
    @Test fun failedCommitOrReadbackRetainsPlaintext() = runBlocking {
        for (commit in listOf(false, true)) {
            var removed = false
            try {
                CredentialMigration.read("legacy", { null }, { commit }, { removed = true })
                fail("Expected verification failure")
            } catch (_: IllegalStateException) { }
            assertFalse(removed)
        }
    }
    @Test fun interruptedCleanupNeverOverwritesNewerSecureKey() = runBlocking {
        var secure: String? = "new"
        assertEquals("new", CredentialMigration.read("old", { secure }, { secure = it; true }, {}))
        assertEquals("new", secure)
    }
    @Test fun failedSaveRetainsLegacyValue() = runBlocking {
        var removed = false
        try {
            CredentialMigration.write("new", { "old" }, { false }, { removed = true })
            fail("Expected failure")
        } catch (_: IllegalStateException) { }
        assertFalse(removed)
    }
    @Test fun cleanupFailureCanBeRetried() = runBlocking {
        var secure: String? = null
        try {
            CredentialMigration.read("old", { secure }, { secure = it; true }, { error("interrupted") })
            fail("Expected interruption")
        } catch (_: IllegalStateException) { }
        assertEquals("old", secure)
        assertEquals("old", CredentialMigration.read("old", { secure }, { true }, {}))
    }
}
