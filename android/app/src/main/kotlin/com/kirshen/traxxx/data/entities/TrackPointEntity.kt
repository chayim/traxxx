package com.kirshen.traxxx.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "track_points",
    foreignKeys = [ForeignKey(
        entity = ActivityEntity::class,
        parentColumns = ["clientUuid"],
        childColumns = ["activityClientUuid"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["activityClientUuid", "recordedAt"])],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityClientUuid: String,
    val recordedAt: Long,
    val lat: Double,
    val lon: Double,
    val elevationM: Double? = null,
    val accuracyM: Double? = null,
    val speedMps: Double? = null,
)
