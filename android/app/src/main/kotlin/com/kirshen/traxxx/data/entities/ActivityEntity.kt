package com.kirshen.traxxx.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ActivityStatus { PENDING, SYNCED }

/**
 * A recorded (or in-progress) activity. The on-device UUID is the primary key and also
 * the idempotency key the server dedupes on. `stoppedAt == null` means in-progress; there
 * is at most one such row at a time (enforced by the UI and surveyed on service start).
 */
@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey val clientUuid: String,
    val serverId: Long? = null,
    val userId: Long,
    val typeName: String,
    val name: String? = null,
    val startedAt: Long,
    val stoppedAt: Long? = null,
    val totalSeconds: Long? = null,
    val notes: String? = null,
    val status: ActivityStatus = ActivityStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
)
