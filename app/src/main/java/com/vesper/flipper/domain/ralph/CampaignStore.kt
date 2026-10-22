package com.vesper.flipper.domain.ralph

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.UUID

/** Separate schema: no changes or destructive fallback to existing audit/chat databases. */
class CampaignStore(context: Context) : java.io.Closeable {
    // Integration hook: use EncryptedDatabaseFactory.create(context, "vesper_campaigns")
    // when combining with the encrypted-storage feature.
    private val helper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context).name("vesper_campaigns")
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) = createSchema(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = migrate(db, oldVersion, newVersion)
            }).build())
    private val writableDatabase get() = helper.writableDatabase
    private val readableDatabase get() = helper.readableDatabase
    override fun close() = helper.close()
    private val preferences = context.getSharedPreferences("ralph_experimental", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) { check(preferences.edit().putBoolean("enabled", value).commit()) }
    private fun createSchema(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE campaigns (id TEXT PRIMARY KEY NOT NULL, plan TEXT NOT NULL, phase INTEGER NOT NULL DEFAULT 0, step INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL, approval TEXT, message TEXT NOT NULL DEFAULT '', updated INTEGER NOT NULL, approvalExpires INTEGER NOT NULL DEFAULT 0, phaseStarted INTEGER NOT NULL DEFAULT 0)")
    }
    private fun migrate(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "Unsupported preserving campaign migration" }
        db.execSQL(MIGRATION_1_2)
        db.execSQL(MIGRATION_PHASE_START)
    }
    data class Row(val id: String, val plan: CampaignPlan, val phase: Int, val step: Int,
        val state: String, val approval: String?, val message: String, val approvalExpires: Long, val phaseStarted: Long)
    fun create(plan: CampaignPlan): String {
        check(enabled) { "Experimental campaigns disabled" }
        CampaignPolicy.validate(plan, System.currentTimeMillis())
        val id = UUID.randomUUID().toString()
        writableDatabase.insert("campaigns", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
            put("id", id); put("plan", Json.encodeToString(plan)); put("state", "queued"); put("updated", System.currentTimeMillis())
        })
        return id
    }
    fun rows(): List<Row> = readableDatabase.query("SELECT id,plan,phase,step,state,approval,message,approvalExpires,phaseStarted FROM campaigns ORDER BY updated DESC LIMIT 100").use { cursor ->
        buildList { while(cursor.moveToNext()) add(Row(cursor.getString(0), Json.decodeFromString(cursor.getString(1)), cursor.getInt(2), cursor.getInt(3), cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getLong(7),cursor.getLong(8))) }
    }
    fun get(id: String): Row? = readableDatabase.query("SELECT id,plan,phase,step,state,approval,message,approvalExpires,phaseStarted FROM campaigns WHERE id=?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else Row(c.getString(0),Json.decodeFromString(c.getString(1)),c.getInt(2),c.getInt(3),c.getString(4),c.getString(5),c.getString(6),c.getLong(7),c.getLong(8))
    }
    /** CAS prevents duplicate workers/approval taps from claiming a command twice. */
    fun transition(row: Row, state: String, message: String = "", phase: Int = row.phase, step: Int = row.step,
        approval: String? = null, approvalExpires: Long = 0, phaseStarted: Long = row.phaseStarted): Boolean = writableDatabase.update("campaigns", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
        put("state", state); put("message", message.take(4096)); put("phase", phase); put("step", step)
        put("phaseStarted",phaseStarted); put("approval", approval); put("approvalExpires", approvalExpires); put("updated", System.currentTimeMillis())
    }, "id=? AND state=? AND phase=? AND step=?", arrayOf(row.id,row.state,row.phase.toString(),row.step.toString())) == 1
    fun pendingApprovals(): List<Pair<String,String>> = readableDatabase.query(
        "SELECT id,approval FROM campaigns WHERE state='approval' AND approval IS NOT NULL"
    ).use { cursor -> buildList { while(cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) } }
    fun killAll() { enabled = false; writableDatabase.execSQL("UPDATE campaigns SET state='cancelled', approval=NULL WHERE state NOT IN ('completed','failed','cancelled')") }
    companion object {
        const val MIGRATION_PHASE_START = "ALTER TABLE campaigns ADD COLUMN phaseStarted INTEGER NOT NULL DEFAULT 0"
        const val MIGRATION_1_2 = "ALTER TABLE campaigns ADD COLUMN approvalExpires INTEGER NOT NULL DEFAULT 0" }
}
