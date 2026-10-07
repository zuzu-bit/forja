package com.forja.app.core.research

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "lab_events", indices = [Index(value = ["deviceId", "sequenceNumber"], unique = true), Index(value = ["ownerUid", "labSessionId", "syncState"])])
data class LabEvent(
    @PrimaryKey val eventId: String,
    val deviceId: String,
    val ownerUid: String,
    val labSessionId: String,
    val source: String,
    val type: String,
    val sourceTimestamp: Long,
    val receivedTimestamp: Long,
    val serverReceivedTimestamp: Long? = null,
    val sequenceNumber: Long,
    val payload: String,
    val syncState: String = "pending"
)

@Entity(tableName = "lab_sequences")
data class LabSequence(@PrimaryKey val deviceId: String, val nextSequence: Long)

@Dao
interface LabEventDao {
    @Insert suspend fun insert(event: LabEvent)
    @Query("SELECT * FROM lab_sequences WHERE deviceId = :deviceId") suspend fun sequence(deviceId: String): LabSequence?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setSequence(sequence: LabSequence)
    @Query("SELECT * FROM lab_events WHERE ownerUid = :uid AND labSessionId = :sessionId AND deviceId = :deviceId AND syncState = 'pending' ORDER BY sequenceNumber ASC LIMIT :limit")
    suspend fun pending(uid: String, sessionId: String, deviceId: String, limit: Int = 50): List<LabEvent>
    @Query("UPDATE lab_events SET syncState = 'synced', serverReceivedTimestamp = :serverTime WHERE eventId IN (:ids) AND ownerUid = :uid AND labSessionId = :sessionId AND deviceId = :deviceId AND syncState = 'pending'")
    suspend fun acknowledge(ids: List<String>, uid: String, sessionId: String, deviceId: String, serverTime: Long?)
    @Query("SELECT COUNT(*) FROM lab_events WHERE ownerUid = :uid AND labSessionId = :sessionId AND deviceId = :deviceId AND syncState = 'pending'")
    suspend fun pendingCount(uid: String, sessionId: String, deviceId: String): Int
    @Query("SELECT COUNT(*) FROM lab_events WHERE ownerUid = :uid AND labSessionId = :sessionId AND deviceId = :deviceId AND syncState = 'pending'")
    fun pendingFlow(uid: String, sessionId: String, deviceId: String): Flow<Int>
}

/** Independent database: no migration, schema or lifecycle changes to the fitness database. */
@Database(entities = [LabEvent::class, LabSequence::class], version = 1, exportSchema = false)
abstract class LabDatabase : RoomDatabase() {
    abstract fun events(): LabEventDao
    companion object {
        @Volatile private var instance: LabDatabase? = null
        fun get(context: Context): LabDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LabDatabase::class.java, "forja-lab-journal.db")
                .build().also { instance = it }
        }
    }
}
