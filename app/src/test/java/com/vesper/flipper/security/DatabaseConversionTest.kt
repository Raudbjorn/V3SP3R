package com.vesper.flipper.security

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseConversionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun migration(file: File, crash: String? = null, invalid: Boolean = false) =
        DatabaseConversion(file, { it.readText().startsWith("plain:") },
            { source, target -> target.writeText("encrypted:" + source.readText().removePrefix("plain:")) },
            { check(!invalid && it.readText().startsWith("encrypted:")) }, {},
            { if (it == crash) error("simulated process death") },
            verifyKey = { check(!invalid && it.readText().startsWith("encrypted:")) })

    @Test fun ordinaryEncryptedOpenDoesNotRunFullIntegrityScans() {
        val file = temporary.newFile("ordinary").apply { writeText("encrypted:rows") }
        var scans = 0
        var keyChecks = 0
        DatabaseConversion(file, { false }, { _, _ -> error("Already encrypted") },
            { scans++ }, { error("Ordinary open must not sync or change files") },
            verifyKey = { keyChecks++ }).run()
        assertEquals(1, keyChecks)
        assertEquals("Normal opening must not scan every data page", 0, scans)
        assertEquals("encrypted:rows", file.readText())
    }

    @Test fun recoveryArtifactsRequireFullValidationBeforeCleanup() {
        for (suffix in listOf(".plaintext-backup", ".encrypting", ".encrypting-wal")) {
            val file = temporary.newFile().apply { writeText("encrypted:rows") }
            val artifact = File(file.path + suffix).apply { writeText("recovery evidence") }
            var scans = 0
            DatabaseConversion(file, { false }, { _, _ -> error("No export") },
                { assertTrue(artifact.exists()); scans++ }, {},
                verifyKey = { error("Recovery requires full validation") }).run()
            assertEquals(1, scans)
            assertFalse(artifact.exists())
            assertEquals("encrypted:rows", file.readText())
        }
    }

    @Test fun normalWrongKeyDoesNotModifyTheInstalledDatabase() {
        val file = temporary.newFile().apply { writeText("encrypted:preserve") }
        try {
            DatabaseConversion(file, { false }, { _, _ -> error("No export") },
                { error("No full scan") }, { error("No modification") },
                verifyKey = { throw IllegalArgumentException("Wrong key") }).run()
            fail("Expected key failure")
        } catch (_: IllegalArgumentException) { }
        assertEquals("encrypted:preserve", file.readText())
        assertEquals(listOf(file.name), temporary.root.listFiles()!!.map { it.name })
    }

    @Test fun conversionStillFullyValidatesBothStagedAndInstalledCopies() {
        val file = temporary.newFile().apply { writeText("plain:rows") }
        val validated = mutableListOf<String>()
        DatabaseConversion(file, { it.readText().startsWith("plain:") },
            { _, target -> target.writeText("encrypted:rows") },
            { validated += it.name }, {}, verifyKey = { error("Conversion requires full validation") }).run()
        assertEquals(listOf(file.name + ".encrypting", file.name), validated)
        assertEquals("encrypted:rows", file.readText())
    }

    @Test fun everyInterruptionRecoversOriginalRowsAndRemovesPlaintext() {
        for (step in listOf("exported", "original-renamed", "encrypted-installed", "complete")) {
            val file = temporary.newFile(step)
            file.writeText("plain:chat\naudit\ndevice")
            try { migration(file, step).run(); fail("Crash was not reached") } catch (_: IllegalStateException) {}
            migration(file).run()
            assertEquals("encrypted:chat\naudit\ndevice", file.readText())
            assertFalse(File(file.path + ".plaintext-backup").exists())
            assertFalse(File(file.path + ".encrypting").exists())
            migration(file).run() // idempotent subsequent open
            assertEquals("encrypted:chat\naudit\ndevice", file.readText())
        }
    }
    @Test fun exportFailureRetainsOriginalAndRetryDiscardsPartialCopy() {
        val file = temporary.newFile("partial").apply { writeText("plain:rows") }
        val failing = DatabaseConversion(file, { true }, { _, target ->
            target.writeText("partial"); error("disk full")
        }, {}, {}, verifyKey = {})
        try { failing.run(); fail("Expected failure") } catch (_: IllegalStateException) {}
        assertEquals("plain:rows", file.readText())
        migration(file).run()
        assertEquals("encrypted:rows", file.readText())
    }
    @Test fun failedStagingValidationNeverRenamesOriginal() {
        val file = temporary.newFile("invalid").apply { writeText("plain:rows") }
        try { migration(file, invalid = true).run(); fail("Expected failure") } catch (_: IllegalStateException) {}
        assertEquals("plain:rows", file.readText())
        assertTrue(File(file.path + ".encrypting").exists())
        migration(file).run() // Invalid staging is disposable while the original remains.
        assertEquals("encrypted:rows", file.readText())
        assertFalse(File(file.path + ".encrypting").exists())
    }
    @Test fun wrongKeyPreservesInstalledCopyAndOriginalBackup() {
        val file = temporary.newFile("wrongkey").apply { writeText("plain:rows") }
        try { migration(file, "encrypted-installed").run() } catch (_: IllegalStateException) {}
        try { migration(file, invalid = true).run(); fail("Expected failure") } catch (_: IllegalStateException) {}
        assertEquals("encrypted:rows", file.readText())
        assertEquals("plain:rows", File(file.path + ".plaintext-backup").readText())
    }
    @Test fun orphanAndAmbiguousFilesAreNeverDiscarded() {
        val file = File(temporary.root, "orphan")
        val staged = File(file.path + ".encrypting").apply { writeText("valuable") }
        try { migration(file).run(); fail("Expected failure") } catch (_: IllegalStateException) {}
        assertEquals("valuable", staged.readText())
        file.writeText("plain:new")
        val backup = File(file.path + ".plaintext-backup").apply { writeText("plain:old") }
        try { migration(file).run(); fail("Expected failure") } catch (_: IllegalStateException) {}
        assertEquals("plain:old", backup.readText())
        assertEquals("plain:new", file.readText())
    }
}
