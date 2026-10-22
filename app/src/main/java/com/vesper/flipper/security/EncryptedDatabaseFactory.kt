package com.vesper.flipper.security

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/** Converts before Room opens; never uses destructive migration or regenerates a missing key. */
object EncryptedDatabaseFactory {
    fun create(context: Context, name: String): SupportSQLiteOpenHelper.Factory =
        SupportSQLiteOpenHelper.Factory { config ->
            // Hilt builds Room on the UI thread. Defer all key/disk work until Room's
            // first DAO access on its query executor, before opening SQLite.
            object : SupportSQLiteOpenHelper {
                private var walEnabled = false
                private val opener = lazy {
                    val password = prepare(context, name)
                    val delegate = config.callback
                    // Stock SQLCipher SupportHelper installs a deleting error handler.
                    object : SQLiteOpenHelper(config.context, config.name, password,
                        null, delegate.version, 0,
                        DatabaseErrorHandler { _, _ -> throw IllegalStateException("Database is unreadable; preserved for recovery") },
                        null, false) {
                        override fun onCreate(db: SQLiteDatabase) = delegate.onCreate(db)
                        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = delegate.onUpgrade(db, old, new)
                        override fun onDowngrade(db: SQLiteDatabase, old: Int, new: Int) = delegate.onDowngrade(db, old, new)
                        override fun onConfigure(db: SQLiteDatabase) = delegate.onConfigure(db)
                        override fun onOpen(db: SQLiteDatabase) = delegate.onOpen(db)
                    }.also { it.setWriteAheadLoggingEnabled(walEnabled) }
                }
                override val databaseName: String? get() = config.name
                override val writableDatabase: SupportSQLiteDatabase
                    get() = synchronized(this) { opener.value.writableDatabase }
                override val readableDatabase: SupportSQLiteDatabase
                    get() = synchronized(this) { opener.value.readableDatabase }
                override fun setWriteAheadLoggingEnabled(enabled: Boolean) = synchronized(this) {
                    walEnabled = enabled
                    if (opener.isInitialized()) opener.value.setWriteAheadLoggingEnabled(enabled)
                }
                override fun close() = synchronized(this) {
                    if (opener.isInitialized()) opener.value.close()
                }
            }
        }

    // Keystore preference creation is short; exports never hold this shared lock.
    private val keyStorageLock = Any()

    private fun prepare(context: Context, name: String): ByteArray {
        System.loadLibrary("sqlcipher")
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        return DatabaseMigrationLocks.withLock(file, context.noBackupFilesDir) {
            val passphrase = synchronized(keyStorageLock) {
                val storage = EncryptedStorage(context)
                val key = "database_passphrase_$name"
                val saved = storage.getString(key)
                check(saved == null || saved.isNotBlank()) {
                    "Database key is invalid; existing data has been preserved"
                }
                check(saved != null || (!file.exists() || isPlaintext(file)) &&
                    !File(file.path + ".plaintext-backup").exists() &&
                    !File(file.path + ".encrypting").exists()) {
                    "Database key is unavailable; existing data has been preserved"
                }
                saved ?: ByteArray(32).also(SecureRandom()::nextBytes)
                    .joinToString("") { "%02x".format(it) }.also {
                        check(storage.putStringSync(key, it)) { "Cannot persist database key" }
                    }
            }
            DatabaseConversion(file, ::isPlaintext,
                    { source, target -> export(source, target, passphrase) },
                    { validate(it, passphrase) },
                    { syncDirectory(file.parentFile!!) }).run()
            passphrase.toByteArray(Charsets.UTF_8)
        }
    }

    internal fun isPlaintext(file: File): Boolean = file.inputStream().use {
        val header = ByteArray(16)
        it.read(header) == 16 && header.contentEquals("SQLite format 3\u0000".toByteArray())
    }

    private fun open(file: File, passphrase: String, flags: Int): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, passphrase.toByteArray(), null, flags,
            DatabaseErrorHandler { _, _ -> throw IllegalStateException("Database is unreadable; preserved for recovery") }, null)

    internal fun export(source: File, target: File, passphrase: String) {
        open(source, "", SQLiteDatabase.OPEN_READWRITE).use { db ->
            // Include every committed WAL row before renaming; refuse if another connection is busy.
            db.rawQuery("PRAGMA wal_checkpoint(FULL)", emptyArray<String>()).use {
                check(it.moveToFirst() && it.getInt(0) == 0) { "Database checkpoint is busy" }
            }
            db.rawQuery("PRAGMA journal_mode=DELETE", emptyArray<String>()).use {
                check(it.moveToFirst() && it.getString(0).equals("delete", true)) { "Cannot close database WAL" }
            }
            val version = db.version
            db.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf(target.path, passphrase))
            try {
                db.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray<String>()).use { it.moveToFirst() }
                db.execSQL("PRAGMA encrypted.user_version = $version")
                val tables = mutableListOf<String>()
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", emptyArray<String>()).use {
                    while (it.moveToNext()) tables += it.getString(0)
                }
                for (table in tables) {
                    val quoted = "\"" + table.replace("\"", "\"\"") + "\""
                    fun count(schema: String): Long = db.rawQuery("SELECT count(*) FROM $schema.$quoted", emptyArray<String>()).use {
                        check(it.moveToFirst()); it.getLong(0)
                    }
                    check(count("main") == count("encrypted")) { "Database export row count differs" }
                }
            } finally {
                db.execSQL("DETACH DATABASE encrypted")
            }
        }
        RandomAccessFile(target, "rw").use { it.fd.sync() }
        syncDirectory(target.parentFile!!)
    }

    internal fun validate(file: File, passphrase: String) {
        check(!isPlaintext(file)) { "Expected encrypted database; original data has been preserved" }
        open(file, passphrase, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", emptyArray<String>()).use {
                check(it.moveToFirst() && it.getString(0) == "ok") { "Database integrity validation failed" }
            }
            db.rawQuery("PRAGMA cipher_integrity_check", emptyArray<String>()).use {
                check(!it.moveToFirst()) { "Encrypted database authentication failed" }
            }
        }
    }

    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
}
