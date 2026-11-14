package local.cooldown.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "journal")
data class JournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val text: String,
    val mood: String,
    val reason: String,
    val kind: String = "note",
)

@Entity(tableName = "locks")
data class LockRecord(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val plannedEnd: Long,
    val endedAt: Long? = null,
    val reason: String,
    val source: String,
    val outcome: String = "active",
)

@Entity(tableName = "sessions")
data class UsageSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val durationMillis: Long,
)

data class HistoryStats(val lockCount: Int = 0, val noteCount: Int = 0, val usageMillis: Long = 0)

@Dao
interface CooldownDao {
    @Query("SELECT * FROM journal ORDER BY at DESC LIMIT 300") fun journal(): Flow<List<JournalEntry>>
    @Query("SELECT * FROM locks ORDER BY startedAt DESC LIMIT 100") fun locks(): Flow<List<LockRecord>>
    @Query("SELECT * FROM sessions ORDER BY endedAt DESC LIMIT 2000") fun sessions(): Flow<List<UsageSession>>
    @Query("""SELECT
        (SELECT COUNT(*) FROM locks WHERE startedAt >= :since) AS lockCount,
        (SELECT COUNT(*) FROM journal WHERE at >= :since AND kind = 'note') AS noteCount,
        (SELECT COALESCE(SUM(durationMillis), 0) FROM sessions WHERE endedAt >= :since) AS usageMillis""")
    fun statistics(since: Long): Flow<HistoryStats>
    @Insert suspend fun insert(entry: JournalEntry): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(lock: LockRecord)
    @Insert suspend fun insert(session: UsageSession)
    @Query("UPDATE locks SET endedAt=:at, outcome=:outcome WHERE id=:id") suspend fun finishLock(id: String, at: Long, outcome: String)
    @Query("DELETE FROM journal WHERE id=:id") suspend fun deleteEntry(id: Long)
    @Query("DELETE FROM journal") suspend fun clearJournal()
    @Query("DELETE FROM locks") suspend fun clearLocks()
    @Query("DELETE FROM sessions") suspend fun clearSessions()
    @Query("DELETE FROM sessions WHERE id NOT IN (SELECT id FROM sessions ORDER BY endedAt DESC LIMIT 2000)") suspend fun trimSessions()
    @Transaction suspend fun eraseHistory() { clearJournal(); clearLocks(); clearSessions() }
}

@Database(entities = [JournalEntry::class, LockRecord::class, UsageSession::class], version = 1, exportSchema = true)
abstract class CooldownDatabase : RoomDatabase() { abstract fun dao(): CooldownDao }
