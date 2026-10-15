package com.kirshen.traxxx.data

import android.content.Context
import com.kirshen.traxxx.data.entities.ActivityEntity
import com.kirshen.traxxx.data.entities.ActivityStatus
import com.kirshen.traxxx.data.entities.TrackPointEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Thin façade used by the UI, the recording service, and the sync worker. */
class Repository private constructor(ctx: Context) {

    private val db = AppDb.get(ctx)
    val activities = db.activities()
    val trackPoints = db.trackPoints()

    suspend fun startRecording(userId: Long, type: ActivityType): ActivityEntity {
        val now = System.currentTimeMillis()
        val row = ActivityEntity(
            clientUuid = UUID.randomUUID().toString(),
            userId = userId,
            typeName = type.typeName,
            name = null,
            startedAt = now,
            stoppedAt = null,
            totalSeconds = null,
            status = ActivityStatus.PENDING,
        )
        activities.upsert(row)
        return row
    }

    suspend fun inProgress(): ActivityEntity? = activities.inProgress()
    fun inProgressFlow(): Flow<ActivityEntity?> = activities.inProgressFlow()

    suspend fun updateNameAndNotes(uuid: String, name: String?, notes: String?) =
        activities.updateNameAndNotes(uuid, name?.trim()?.ifBlank { null }, notes?.trim()?.ifBlank { null })

    suspend fun finish(uuid: String, name: String?, notes: String?) {
        val existing = activities.byUuid(uuid) ?: return
        val stoppedAt = System.currentTimeMillis()
        val total = ((stoppedAt - existing.startedAt) / 1000).coerceAtLeast(0)
        activities.finish(
            uuid = uuid,
            stoppedAt = stoppedAt,
            totalSeconds = total,
            name = name?.trim()?.ifBlank { null },
            notes = notes?.trim()?.ifBlank { null },
            status = ActivityStatus.PENDING,
        )
    }

    suspend fun discard(uuid: String) {
        trackPoints.deleteFor(uuid)
        activities.delete(uuid)
    }

    suspend fun addPoint(p: TrackPointEntity): Long = trackPoints.insert(p)
    suspend fun pointsFor(uuid: String): List<TrackPointEntity> = trackPoints.forActivity(uuid)
    suspend fun pointCount(uuid: String): Int = trackPoints.countFor(uuid)

    suspend fun pendingToSync(): List<ActivityEntity> = activities.byStatus(ActivityStatus.PENDING)
    suspend fun markSynced(uuid: String, serverId: Long) =
        activities.markSynced(uuid, serverId, ActivityStatus.SYNCED)

    fun observeForUser(userId: Long): Flow<List<ActivityEntity>> = activities.observeForUser(userId)
    fun pendingCountFlow(): Flow<Int> = activities.pendingCountFlow()

    /** Insert or merge an activity record pulled from the server (reconciliation). */
    suspend fun upsertFromServer(a: ActivityEntity) {
        val existing = activities.byUuid(a.clientUuid)
        if (existing == null) activities.upsert(a)
        else activities.upsert(existing.copy(
            serverId = a.serverId,
            status = ActivityStatus.SYNCED,
            name = existing.name ?: a.name,
            notes = existing.notes ?: a.notes,
            totalSeconds = existing.totalSeconds ?: a.totalSeconds,
            stoppedAt = existing.stoppedAt ?: a.stoppedAt,
        ))
    }

    companion object {
        @Volatile private var INSTANCE: Repository? = null
        fun get(ctx: Context): Repository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Repository(ctx).also { INSTANCE = it }
        }
    }
}
