package com.vesper.flipper.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vesper.flipper.data.database.FindingsDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises the production SQLCipher factory and actual Room migrations; requires Android JNI. */
@RunWith(AndroidJUnit4::class)
class EncryptedFindingsMigrationTest {
    @Test fun preservesPopulatedLegacyVersionsAndScopesNewQueries(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in listOf(1, 2)) {
            val name = "findings-v$version-${UUID.randomUUID()}"
            val file = context.getDatabasePath(name)
            file.parentFile!!.mkdirs()
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE vuln_findings(id TEXT NOT NULL PRIMARY KEY, submittedAt INTEGER NOT NULL, target TEXT NOT NULL, vulnType TEXT NOT NULL, description TEXT NOT NULL, evidence TEXT NOT NULL, severity TEXT NOT NULL, complexity INTEGER NOT NULL, status TEXT NOT NULL, reproductionAttempts INTEGER NOT NULL, validatedAt INTEGER)")
                db.execSQL("INSERT INTO vuln_findings VALUES ('legacy', 1, 'Ä-device', 'info_disclosure', 'observed', 'preserved evidence', 'low', 1, 'confirmed', 2, 3)")
                if (version == 2) db.execSQL(FindingsDatabase.MIGRATION_SQL)
                db.version = version
            }
            fun open() = Room.databaseBuilder(context, FindingsDatabase::class.java, name)
                .openHelperFactory(EncryptedDatabaseFactory.create(context, name))
                .addMigrations(FindingsDatabase.MIGRATION_1_2, FindingsDatabase.MIGRATION_2_3).build()
            val upgraded = open()
            val row = upgraded.findings().getAll().single()
            assertEquals("preserved evidence", row.evidence)
            assertEquals(2, row.reproductionAttempts)
            assertEquals("[]", row.validationNotesJson)
            assertEquals("", row.sessionId)
            assertTrue(upgraded.findings().queryFiltered("new-session", null, null).isEmpty())
            upgraded.close()
            assertFalse(EncryptedDatabaseFactory.isPlaintext(file))
            val reopened = open()
            assertEquals(row, reopened.findings().getAll().single())
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
