package com.vesper.flipper.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vesper.flipper.data.database.VesperDatabase
import com.vesper.flipper.data.database.ChatMessageEntity
import com.vesper.flipper.data.database.AuditEntryEntity
import com.vesper.flipper.data.db.DeviceDatabase
import com.vesper.flipper.data.db.TrackedDevice
import com.vesper.flipper.data.db.DeviceType
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Requires an Android emulator/device: exercises the actual SQLCipher JNI and Room schemas. */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun freshDatabaseIsEncryptedAndWrongStoredKeyPreservesItsBytes(): Unit = runBlocking {
        val name = "fresh-" + UUID.randomUUID()
        val encrypted = Room.databaseBuilder(context, VesperDatabase::class.java, name)
            .openHelperFactory(EncryptedDatabaseFactory.create(context, name)).build()
        encrypted.chatDao().insert(ChatMessageEntity("new", "user", "fresh secret", 1,
            null, null, "COMPLETE", null, "session"))
        encrypted.close()
        val file = context.getDatabasePath(name)
        assertFalse(EncryptedDatabaseFactory.isPlaintext(file))
        val before = file.readBytes()
        val storage = EncryptedStorage(context)
        val slot = "database_passphrase_$name"
        val original = requireNotNull(storage.getString(slot))
        check(storage.putStringSync(slot, "wrong-key"))
        try {
            val wrong = Room.databaseBuilder(context, VesperDatabase::class.java, name)
                .openHelperFactory(EncryptedDatabaseFactory.create(context, name)).build()
            try { wrong.chatDao().getMessagesForSessionSync("session") } finally { wrong.close() }
            fail("Expected wrong-key failure")
        } catch (_: Exception) { }
        assertArrayEquals(before, file.readBytes())
        check(storage.putStringSync(slot, original))
        val reopened = Room.databaseBuilder(context, VesperDatabase::class.java, name)
            .openHelperFactory(EncryptedDatabaseFactory.create(context, name)).build()
        assertEquals("fresh secret", reopened.chatDao().getMessagesForSessionSync("session").single().content)
        reopened.close()
        context.deleteDatabase(name)
    }

    @Test fun preservesRealRoomChatAndAuditRowsAcrossPlaintextUpgrade(): Unit = runBlocking {
        val name = "migration-" + UUID.randomUUID()
        val plaintext = Room.databaseBuilder(context, VesperDatabase::class.java, name).build()
        val message = ChatMessageEntity("msg", "user", "private conversation 🔒", 42,
            null, null, "COMPLETE", null, "session", "[image]")
        val entry = AuditEntryEntity("audit", 43, "COMMAND", "{command}", "{result}",
            "LOW", true, "tap", "session", "{}")
        plaintext.chatDao().insert(message)
        plaintext.auditDao().insert(entry)
        plaintext.close()
        val encrypted = Room.databaseBuilder(context, VesperDatabase::class.java, name)
            .openHelperFactory(EncryptedDatabaseFactory.create(context, name)).build()
        assertEquals(listOf(message), encrypted.chatDao().getMessagesForSessionSync("session"))
        assertEquals(listOf(entry), encrypted.auditDao().getEntriesForSessionSync("session"))
        encrypted.close()
        assertFalse(EncryptedDatabaseFactory.isPlaintext(context.getDatabasePath(name)))
        assertFalse(java.io.File(context.getDatabasePath(name).path + ".plaintext-backup").exists())
        val reopened = Room.databaseBuilder(context, VesperDatabase::class.java, name)
            .openHelperFactory(EncryptedDatabaseFactory.create(context, name)).build()
        assertEquals(listOf(message), reopened.chatDao().getMessagesForSessionSync("session"))
        reopened.close()
        context.deleteDatabase(name)
    }

    @Test fun upgradesVersionOneWithoutDiscardingMessages(): Unit = runBlocking {
        val name = "v1-" + UUID.randomUUID()
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE audit_entries (id TEXT NOT NULL PRIMARY KEY, timestamp INTEGER NOT NULL, actionType TEXT NOT NULL, commandJson TEXT, resultJson TEXT, riskLevel TEXT, userApproved INTEGER, approvalMethod TEXT, sessionId TEXT NOT NULL, metadataJson TEXT NOT NULL)")
            db.execSQL("CREATE TABLE chat_messages (id TEXT NOT NULL PRIMARY KEY, role TEXT NOT NULL, content TEXT NOT NULL, timestamp INTEGER NOT NULL, toolCallsJson TEXT, toolResultsJson TEXT, status TEXT NOT NULL, metadataJson TEXT, sessionId TEXT NOT NULL)")
            db.execSQL("INSERT INTO chat_messages VALUES('old', 'user', 'preserve me', 1, NULL, NULL, 'COMPLETE', NULL, 'session')")
            db.version = 1
        }
        val upgraded = Room.databaseBuilder(context, VesperDatabase::class.java, name)
            .openHelperFactory(EncryptedDatabaseFactory.create(context, name))
            .addMigrations(VesperDatabase.MIGRATION_1_2).build()
        val message = upgraded.chatDao().getMessagesForSessionSync("session").single()
        assertEquals("preserve me", message.content)
        assertNull(message.imageAttachmentsJson)
        upgraded.close()
        context.deleteDatabase(name)
    }

    @Test fun nativeExportRecoversAfterEachRenameBoundary() {
        System.loadLibrary("sqlcipher")
        for (crash in listOf("exported", "original-renamed", "encrypted-installed")) {
            val source = context.getDatabasePath("crash-" + UUID.randomUUID())
            source.parentFile!!.mkdirs()
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(source, null).use {
                it.execSQL("CREATE TABLE evidence(value TEXT)")
                it.execSQL("INSERT INTO evidence VALUES('preserve after crash')")
                it.version = 2
            }
            fun conversion(stop: Boolean) = DatabaseConversion(source,
                EncryptedDatabaseFactory::isPlaintext,
                { original, target -> EncryptedDatabaseFactory.export(original, target, "test-key") },
                { EncryptedDatabaseFactory.validate(it, "test-key") }, {},
                { if (stop && it == crash) error("simulated death") })
            try { conversion(true).run(); fail("Expected interruption") } catch (_: IllegalStateException) {}
            conversion(false).run()
            SQLiteDatabase.openDatabase(source.path, "test-key", null, SQLiteDatabase.OPEN_READONLY, null).use { db ->
                db.rawQuery("SELECT value FROM evidence", emptyArray<String>()).use {
                    assertTrue(it.moveToFirst()); assertEquals("preserve after crash", it.getString(0))
                }
            }
            source.delete()
        }
    }

    @Test fun preservesTrackedDeviceDatabase(): Unit = runBlocking {
        val name = "devices-" + UUID.randomUUID()
        val original = Room.databaseBuilder(context, DeviceDatabase::class.java, name).build()
        val device = TrackedDevice(identifier = "AA:BB:CC", name = "test device", type = DeviceType.BLE)
        original.deviceDao().insertDevice(device)
        original.close()
        val encrypted = Room.databaseBuilder(context, DeviceDatabase::class.java, name)
            .openHelperFactory(EncryptedDatabaseFactory.create(context, name)).build()
        assertEquals(device, encrypted.deviceDao().getDeviceById(device.id))
        encrypted.close()
        context.deleteDatabase(name)
    }

    @Test fun walExportPreservesBlobsVersionAndRoomIdentityAndRejectsWrongKey() {
        System.loadLibrary("sqlcipher")
        val source = context.getDatabasePath("wal-" + UUID.randomUUID())
        source.parentFile!!.mkdirs()
        val target = java.io.File(source.path + ".target")
        val plain = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(source, null)
        plain.enableWriteAheadLogging()
        plain.execSQL("CREATE TABLE sample(id INTEGER PRIMARY KEY, value BLOB)")
        plain.execSQL("INSERT INTO sample VALUES(1, ?)", arrayOf(byteArrayOf(0, 1, -1)))
        plain.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity_hash TEXT)")
        plain.execSQL("INSERT INTO room_master_table VALUES(42, 'test-identity')")
        plain.version = 2
        // Snapshot a committed WAL while the writer remains open, then recover that copy.
        val snapshot = java.io.File(source.path + ".snapshot")
        source.copyTo(snapshot)
        java.io.File(source.path + "-wal").copyTo(java.io.File(snapshot.path + "-wal"))
        plain.close()
        try {
            EncryptedDatabaseFactory.validate(snapshot, "")
            fail("Plaintext must never pass encrypted validation")
        } catch (_: IllegalStateException) { }
        EncryptedDatabaseFactory.export(snapshot, target, "correct-passphrase")
        EncryptedDatabaseFactory.validate(target, "correct-passphrase")
        SQLiteDatabase.openDatabase(target.path, "correct-passphrase", null, SQLiteDatabase.OPEN_READONLY, null).use { db ->
            assertEquals(2, db.version)
            db.rawQuery("SELECT value FROM sample", emptyArray<String>()).use {
                assertTrue(it.moveToFirst()); assertArrayEquals(byteArrayOf(0, 1, -1), it.getBlob(0))
            }
            db.rawQuery("SELECT identity_hash FROM room_master_table", emptyArray<String>()).use {
                assertTrue(it.moveToFirst()); assertEquals("test-identity", it.getString(0))
            }
        }
        val before = target.readBytes()
        try {
            EncryptedDatabaseFactory.validate(target, "wrong-passphrase")
            fail("Expected wrong-key failure")
        } catch (_: Exception) { }
        assertArrayEquals(before, target.readBytes())
        source.delete(); snapshot.delete(); target.delete()
    }
}
