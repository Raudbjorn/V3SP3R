package com.vesper.flipper.security

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Coordinate only callers opening the same database, with bounded lock acquisition. */
internal object DatabaseMigrationLocks {
    private val processLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> withLock(database: File, lockDirectory: File, timeoutMs: Long = 10_000, block: () -> T): T {
        require(timeoutMs > 0)
        val identity = database.canonicalPath
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val local = processLocks.computeIfAbsent(identity) { ReentrantLock() }
        if (!local.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) throw IOException("Database migration is busy; retry opening it")
        try {
            check(lockDirectory.isDirectory || lockDirectory.mkdirs()) { "Cannot create database lock directory" }
            val token = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return RandomAccessFile(File(lockDirectory, "database-encryption-$token.lock"), "rw").use { file ->
                while (true) {
                    val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock != null) return@use lock.use { block() }
                    if (System.nanoTime() >= deadline) throw IOException("Database migration is busy; retry opening it")
                    Thread.sleep(10)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
        } finally { local.unlock() }
    }
}
