package com.vesper.flipper.security

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseMigrationLocksTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun blockedConversionDoesNotBlockTheOtherDatabase() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val first = executor.submit {
                DatabaseMigrationLocks.withLock(File(temporary.root, "chat"), temporary.root) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals("opened", DatabaseMigrationLocks.withLock(File(temporary.root, "devices"), temporary.root, 200) { "opened" })
            try {
                DatabaseMigrationLocks.withLock(File(temporary.root, "chat"), temporary.root, 50) { fail("Same database must remain locked") }
                fail("Expected bounded contention error")
            } catch (_: IOException) { }
            release.countDown()
            first.get(2, TimeUnit.SECONDS)
            assertEquals("retry", DatabaseMigrationLocks.withLock(File(temporary.root, "chat"), temporary.root) { "retry" })
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun conversionFailureReleasesBothLocksWithoutChangingData() {
        val source = temporary.newFile("chat").apply { writeText("original") }
        try {
            DatabaseMigrationLocks.withLock(source, temporary.root) { throw IOException("conversion failed") }
        } catch (_: IOException) { }
        assertEquals("original", source.readText())
        assertEquals("retried", DatabaseMigrationLocks.withLock(source, temporary.root) { "retried" })
    }
    @Test fun fileLockContentionTimesOutAndCanBeRetried() {
        val source = temporary.newFile("file-lock-chat").apply { writeText("original") }
        DatabaseMigrationLocks.withLock(source, temporary.root) { }
        val lockFile = temporary.root.listFiles()!!.single { it.name.endsWith(".lock") }
        java.io.RandomAccessFile(lockFile, "rw").use { file ->
            file.channel.lock().use {
                try {
                    DatabaseMigrationLocks.withLock(source, temporary.root, 30) { fail("Must not bypass file lock") }
                    fail("Expected bounded file contention")
                } catch (_: IOException) { }
            }
        }
        assertEquals("original", DatabaseMigrationLocks.withLock(source, temporary.root) { source.readText() })
    }

}
