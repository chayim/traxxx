package com.kirshen.traxxx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kirshen.traxxx.data.entities.TrackPointEntity

@Dao
interface TrackPointDao {

    @Insert
    suspend fun insert(p: TrackPointEntity): Long

    @Query("SELECT * FROM track_points WHERE activityClientUuid = :uuid ORDER BY recordedAt")
    suspend fun forActivity(uuid: String): List<TrackPointEntity>

    @Query("SELECT COUNT(*) FROM track_points WHERE activityClientUuid = :uuid")
    suspend fun countFor(uuid: String): Int

    @Query("DELETE FROM track_points WHERE activityClientUuid = :uuid")
    suspend fun deleteFor(uuid: String)
}
