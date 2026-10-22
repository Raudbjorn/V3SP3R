package com.vesper.flipper.data.database
import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(entities = [VulnFindingEntity::class], version = 3, exportSchema = false)
abstract class FindingsDatabase : RoomDatabase() {
    abstract fun findings(): VulnDao
    companion object {
        const val MIGRATION_SQL = "ALTER TABLE vuln_findings ADD COLUMN validationNotesJson TEXT NOT NULL DEFAULT '[]'"
        const val SESSION_MIGRATION_SQL = "ALTER TABLE vuln_findings ADD COLUMN sessionId TEXT NOT NULL DEFAULT ''"
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL(SESSION_MIGRATION_SQL) }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL(MIGRATION_SQL) }
        }
    }
}
@Module @InstallIn(SingletonComponent::class)
object FindingsModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): FindingsDatabase =
        Room.databaseBuilder(context, FindingsDatabase::class.java, "vesper_findings")
            .addMigrations(FindingsDatabase.MIGRATION_1_2, FindingsDatabase.MIGRATION_2_3).build()
    @Provides fun dao(database: FindingsDatabase): VulnDao = database.findings()
}

@Entity(tableName = "vuln_findings")
data class VulnFindingEntity(
    @PrimaryKey val id: String,
    val submittedAt: Long,
    val target: String,
    val vulnType: String,
    val description: String,
    val evidence: String,
    val severity: String,
    val complexity: Int,
    val status: String,
    val reproductionAttempts: Int = 0,
    val validatedAt: Long? = null,
    /** JSON array of {timestamp, reproduced, notes} objects, appended per vuln_validate call. */
    @ColumnInfo(defaultValue = "'[]'") val validationNotesJson: String = "[]",
    @ColumnInfo(defaultValue = "''") val sessionId: String = ""
)

@Dao
interface VulnDao {
    @Query("SELECT * FROM vuln_findings WHERE sessionId = :sessionId AND (:severity IS NULL OR severity=:severity) AND (:status IS NULL OR status=:status) ORDER BY CASE severity WHEN 'critical' THEN 0 WHEN 'high' THEN 1 WHEN 'medium' THEN 2 ELSE 3 END, submittedAt DESC LIMIT 500")
    suspend fun queryFiltered(sessionId: String, severity: String?, status: String?): List<VulnFindingEntity>

    @Query("SELECT COUNT(*) FROM vuln_findings")
    suspend fun countAll(): Int

    @Upsert
    suspend fun upsert(finding: VulnFindingEntity)

    @Query("SELECT * FROM vuln_findings WHERE id = :id AND sessionId = :sessionId LIMIT 1")
    suspend fun getById(id: String, sessionId: String): VulnFindingEntity?

    @Query("SELECT * FROM vuln_findings ORDER BY submittedAt DESC LIMIT 500")
    suspend fun getAll(): List<VulnFindingEntity>

    @Query("DELETE FROM vuln_findings")
    suspend fun deleteAll()
}
