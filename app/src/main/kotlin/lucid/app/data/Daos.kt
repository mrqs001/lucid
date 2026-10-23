package lucid.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(s: SessionEntity)

    @Update
    suspend fun update(s: SessionEntity)

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM session WHERE id = :id")
    fun observe(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM session ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM session ORDER BY startedAt DESC")
    suspend fun all(): List<SessionEntity>

    @Query("SELECT * FROM session WHERE status = 'RECORDING'")
    suspend fun recording(): List<SessionEntity>

    @Query("DELETE FROM session WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM session")
    suspend fun deleteAll()
}

@Dao
interface SampleDao {
    @Insert
    suspend fun insertAll(rows: List<SampleEntity>)

    @Query("SELECT * FROM sample WHERE sessionId = :sessionId ORDER BY t")
    suspend fun forSession(sessionId: String): List<SampleEntity>

    @Query("SELECT * FROM sample WHERE sessionId = :sessionId AND kind = :kind ORDER BY t")
    suspend fun forSessionKind(sessionId: String, kind: Int): List<SampleEntity>

    @Query("SELECT COUNT(*) FROM sample WHERE sessionId = :sessionId")
    suspend fun count(sessionId: String): Int

    @Query("DELETE FROM sample WHERE sessionId = :sessionId")
    suspend fun deleteFor(sessionId: String)

    @Query("DELETE FROM sample")
    suspend fun deleteAll()
}

@Dao
interface EpochDao {
    @Upsert
    suspend fun upsertAll(rows: List<EpochEntity>)

    @Query("SELECT * FROM epoch WHERE sessionId = :sessionId ORDER BY idx")
    fun observe(sessionId: String): Flow<List<EpochEntity>>

    @Query("SELECT * FROM epoch WHERE sessionId = :sessionId ORDER BY idx")
    suspend fun forSession(sessionId: String): List<EpochEntity>

    @Query("DELETE FROM epoch WHERE sessionId = :sessionId")
    suspend fun deleteFor(sessionId: String)

    @Query("DELETE FROM epoch")
    suspend fun deleteAll()
}

@Dao
interface CueDao {
    @Insert
    suspend fun insert(row: CueEventEntity): Long

    @Query("UPDATE cue_event SET outcome = :outcome, ackAt = :ackAt, ackDetail = :detail WHERE cueId = :cueId")
    suspend fun setOutcome(cueId: String, outcome: String, ackAt: Long, detail: String)

    @Query("UPDATE cue_event SET response = :response, responseDetail = :detail WHERE cueId = :cueId")
    suspend fun setResponse(cueId: String, response: String, detail: String)

    @Query("SELECT * FROM cue_event WHERE sessionId = :sessionId ORDER BY t")
    fun observe(sessionId: String): Flow<List<CueEventEntity>>

    @Query("SELECT * FROM cue_event WHERE sessionId = :sessionId ORDER BY t")
    suspend fun forSession(sessionId: String): List<CueEventEntity>

    @Query("DELETE FROM cue_event WHERE sessionId = :sessionId")
    suspend fun deleteFor(sessionId: String)

    @Query("DELETE FROM cue_event")
    suspend fun deleteAll()
}

@Dao
interface TruthDao {
    @Upsert
    suspend fun upsertAll(rows: List<SimTruthEntity>)

    @Query("SELECT * FROM sim_truth WHERE sessionId = :sessionId ORDER BY idx")
    fun observe(sessionId: String): Flow<List<SimTruthEntity>>

    @Query("SELECT * FROM sim_truth WHERE sessionId = :sessionId ORDER BY idx")
    suspend fun forSession(sessionId: String): List<SimTruthEntity>

    @Query("DELETE FROM sim_truth WHERE sessionId = :sessionId")
    suspend fun deleteFor(sessionId: String)

    @Query("DELETE FROM sim_truth")
    suspend fun deleteAll()
}

@Dao
interface BatteryDao {
    @Insert
    suspend fun insert(row: BatteryEntity)

    @Query("SELECT * FROM battery WHERE sessionId = :sessionId ORDER BY t")
    fun observe(sessionId: String): Flow<List<BatteryEntity>>

    @Query("SELECT * FROM battery WHERE sessionId = :sessionId ORDER BY t")
    suspend fun forSession(sessionId: String): List<BatteryEntity>

    @Query("SELECT * FROM battery ORDER BY t")
    fun observeAll(): Flow<List<BatteryEntity>>

    @Query("DELETE FROM battery WHERE sessionId = :sessionId")
    suspend fun deleteFor(sessionId: String)

    @Query("DELETE FROM battery")
    suspend fun deleteAll()
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(row: SessionEventEntity)

    @Query("SELECT * FROM session_event WHERE sessionId = :sessionId ORDER BY t")
    suspend fun forSession(sessionId: String): List<SessionEventEntity>

    @Query("SELECT * FROM session_event WHERE sessionId = :sessionId ORDER BY t DESC LIMIT :limit")
    fun observeRecent(sessionId: String, limit: Int): Flow<List<SessionEventEntity>>

    @Query("DELETE FROM session_event WHERE sessionId = :sessionId")
    suspend fun deleteFor(sessionId: String)

    @Query("DELETE FROM session_event WHERE sessionId = :sessionId AND type = :type")
    suspend fun deleteType(sessionId: String, type: String)

    @Query("DELETE FROM session_event")
    suspend fun deleteAll()
}

@Dao
interface JournalDao {
    @Insert
    suspend fun insert(row: JournalEntity): Long

    @Update
    suspend fun update(row: JournalEntity)

    @Query("DELETE FROM journal WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM journal WHERE id = :id")
    suspend fun get(id: Long): JournalEntity?

    @Query("SELECT * FROM journal ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<JournalEntity>>

    @Query("SELECT * FROM journal ORDER BY createdAt DESC")
    suspend fun all(): List<JournalEntity>

    @Query("SELECT * FROM journal WHERE sessionId = :sessionId")
    suspend fun forSession(sessionId: String): List<JournalEntity>
}

@Dao
interface TrainingDao {
    @Insert
    suspend fun insert(row: TrainingEntity)

    @Query("SELECT * FROM training WHERE t >= :since ORDER BY t DESC")
    fun observeSince(since: Long): Flow<List<TrainingEntity>>

    @Query("SELECT * FROM training ORDER BY t DESC")
    suspend fun all(): List<TrainingEntity>
}

@Dao
interface ExperimentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ExperimentEntity)

    @Query("SELECT * FROM experiment ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ExperimentEntity>>

    @Query("SELECT * FROM experiment WHERE active = 1 LIMIT 1")
    suspend fun active(): ExperimentEntity?

    @Query("SELECT * FROM experiment WHERE id = :id")
    suspend fun get(id: String): ExperimentEntity?

    @Query("UPDATE experiment SET active = 0")
    suspend fun deactivateAll()

    @Query("DELETE FROM experiment WHERE id = :id")
    suspend fun delete(id: String)
}
