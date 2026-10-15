package com.kirshen.traxxx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kirshen.traxxx.data.entities.ActivityEntity
import com.kirshen.traxxx.data.entities.ActivityStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(a: ActivityEntity)

    @Update
    suspend fun update(a: ActivityEntity)

    @Query("SELECT * FROM activities WHERE clientUuid = :uuid")
    suspend fun byUuid(uuid: String): ActivityEntity?

    @Query("SELECT * FROM activities WHERE stoppedAt IS NULL LIMIT 1")
    suspend fun inProgress(): ActivityEntity?

    @Query("SELECT * FROM activities WHERE stoppedAt IS NULL LIMIT 1")
    fun inProgressFlow(): Flow<ActivityEntity?>

    @Query("SELECT * FROM activities WHERE status = :status AND stoppedAt IS NOT NULL")
    suspend fun byStatus(status: ActivityStatus): List<ActivityEntity>

    @Query("""
        SELECT * FROM activities
        WHERE userId = :userId
        ORDER BY startedAt DESC
    """)
    fun observeForUser(userId: Long): Flow<List<ActivityEntity>>

    @Query("UPDATE activities SET name = :name, notes = :notes WHERE clientUuid = :uuid")
    suspend fun updateNameAndNotes(uuid: String, name: String?, notes: String?)

    @Query("""
        UPDATE activities SET stoppedAt = :stoppedAt, totalSeconds = :totalSeconds,
          name = :name, notes = :notes, status = :status
        WHERE clientUuid = :uuid
    """)
    suspend fun finish(
        uuid: String,
        stoppedAt: Long,
        totalSeconds: Long,
        name: String?,
        notes: String?,
        status: ActivityStatus,
    )

    @Query("UPDATE activities SET serverId = :serverId, status = :status WHERE clientUuid = :uuid")
    suspend fun markSynced(uuid: String, serverId: Long, status: ActivityStatus)

    @Query("DELETE FROM activities WHERE clientUuid = :uuid")
    suspend fun delete(uuid: String)

    @Query("SELECT COUNT(*) FROM activities WHERE status = 'PENDING' AND stoppedAt IS NOT NULL")
    fun pendingCountFlow(): Flow<Int>
}
