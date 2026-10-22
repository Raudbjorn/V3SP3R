package com.vesper.flipper.domain.ralph

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.security.EncryptedDatabaseFactory
import com.vesper.flipper.security.EncryptedStorage
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampaignStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "campaign-test-${UUID.randomUUID()}.db"
    private val preferencesName = "campaign-test-${UUID.randomUUID()}"
    private fun store() = CampaignStore(context, databaseName, preferencesName)
    private val plan = CampaignPlan("Migration", "/ext/nfc", System.currentTimeMillis()+60_000,
        listOf(CampaignPhase("Write", listOf(ExecuteCommand(CommandAction.WRITE_FILE,
            CommandArgs(path="/ext/nfc/test", content=""), "Requested", "Create empty file")))))

    @Before fun reset() { context.deleteDatabase(databaseName) }
    @After fun cleanup() {
        context.deleteDatabase(databaseName)
        // Clean only this fixture's migration files; never touch production campaign data/settings.
        for (suffix in listOf(".encrypting", ".plaintext-backup")) {
            val path = context.getDatabasePath(databaseName).path + suffix
            for (sidecar in listOf("", "-wal", "-shm", "-journal")) File(path + sidecar).delete()
        }
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        EncryptedStorage(context).remove("database_passphrase_$databaseName")
    }

    @Test fun realHelperUpgradePreservesFullVersionOneRowAndReopensVersionTwo() {
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE campaigns (id TEXT PRIMARY KEY NOT NULL, plan TEXT NOT NULL, phase INTEGER NOT NULL DEFAULT 0, step INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL, approval TEXT, message TEXT NOT NULL DEFAULT '', updated INTEGER NOT NULL)")
            db.execSQL("INSERT INTO campaigns(id,plan,phase,step,state,approval,message,updated) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf("existing", Json.encodeToString(plan), 0, 0, "approval", "approval-one", "Preserve me", 123L))
            db.version = 1
        }
        repeat(2) {
            // Production first converts legacy plaintext with user_version intact, then invokes onUpgrade.
            store().use { store ->
                val row = requireNotNull(store.get("existing"))
                assertEquals(plan, row.plan)
                assertEquals("approval", row.state)
                assertEquals("approval-one", row.approval)
                assertEquals("Preserve me", row.message)
                assertEquals(0, row.phase)
                assertEquals(0, row.step)
                assertEquals(0L, row.approvalExpires)
                assertEquals(0L, row.phaseStarted)
                assertEquals(1, store.rows().size)
                assertFalse(EncryptedDatabaseFactory.isPlaintext(context.getDatabasePath(databaseName)))
            }
        }
    }

    @Test fun staleDisplayedApprovalCannotClaimReplacementOrRunTwice() {
        store().use { store ->
            store.enabled = true
            val id = store.create(plan)
            assertTrue(store.transition(requireNotNull(store.get(id)), "executing"))
            assertTrue(store.transition(requireNotNull(store.get(id)), "approval", approval="shown", approvalExpires=plan.expiresAt))
            val displayed = requireNotNull(store.get(id))
            assertFalse(store.claimApproval(displayed, "different", System.currentTimeMillis()))
            assertTrue(store.claimApproval(displayed, "shown", System.currentTimeMillis()))
            assertFalse(store.claimApproval(displayed, "shown", System.currentTimeMillis()))
            assertTrue(store.transition(requireNotNull(store.get(id)), "queued"))
            assertTrue(store.transition(requireNotNull(store.get(id)), "executing"))
            assertTrue(store.transition(requireNotNull(store.get(id)), "approval", approval="replacement", approvalExpires=plan.expiresAt))
            // Same phase/step/state (an ABA change), but a different approval identity.
            assertFalse(store.claimApproval(displayed, "shown", System.currentTimeMillis()))
            assertFalse(store.claimApproval(requireNotNull(store.get(id)), "shown", System.currentTimeMillis()))
            assertEquals("replacement", store.get(id)?.approval)
            assertTrue(store.claimApproval(requireNotNull(store.get(id)), "replacement", System.currentTimeMillis()))
            store.cancel(id)
            assertFalse(store.transition(requireNotNull(store.get(id)), "queued"))
        }
    }
}
