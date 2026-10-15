package com.kirshen.traxxx.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kirshen.traxxx.data.Prefs
import com.kirshen.traxxx.data.Repository
import com.kirshen.traxxx.data.entities.ActivityEntity
import com.kirshen.traxxx.data.entities.ActivityStatus
import com.kirshen.traxxx.data.entities.TrackPointEntity
import com.kirshen.traxxx.net.Api
import com.kirshen.traxxx.net.CreateActivityDto
import com.kirshen.traxxx.net.CreateTypeDto
import com.kirshen.traxxx.net.NetModule
import com.kirshen.traxxx.net.TrackPointDto
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val repo = Repository.get(ctx)

    override suspend fun doWork(): Result {
        return try {
            val api = NetModule.api(applicationContext)
            val typeIds = resolveTypeIds(api)

            val pending = repo.pendingToSync()
            Log.i(TAG, "sync: ${pending.size} pending")
            var retriable = false
            for (row in pending) {
                try {
                    val typeId = typeIds[row.typeName] ?: resolveSingleType(api, row.typeName, typeIds)
                    val points = repo.pointsFor(row.clientUuid)
                    val dto = buildDto(row, typeId, points)
                    val resp = api.createActivity(dto)
                    repo.markSynced(row.clientUuid, resp.id)
                    Log.i(TAG, "synced ${row.clientUuid} -> ${resp.id}")
                } catch (t: Throwable) {
                    Log.w(TAG, "sync fail for ${row.clientUuid}: ${t.message}")
                    retriable = true
                }
            }
            if (retriable) Result.retry() else Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "sync aborted: ${t.message}")
            Result.retry()
        }
    }

    private suspend fun resolveTypeIds(api: Api): MutableMap<String, Long> {
        val map = mutableMapOf<String, Long>()
        for (t in api.listTypes()) map[t.name] = t.id
        return map
    }

    private suspend fun resolveSingleType(
        api: Api,
        typeName: String,
        cache: MutableMap<String, Long>,
    ): Long {
        val created = api.createType(CreateTypeDto(typeName))
        cache[typeName] = created.id
        return created.id
    }

    private fun buildDto(row: ActivityEntity, typeId: Long, points: List<TrackPointEntity>): CreateActivityDto {
        val stopped = row.stoppedAt ?: System.currentTimeMillis()
        val total = row.totalSeconds ?: ((stopped - row.startedAt) / 1000).coerceAtLeast(0)
        return CreateActivityDto(
            userId = row.userId,
            typeId = typeId,
            name = row.name ?: defaultName(row),
            activityDate = DATE_FMT.format(Date(row.startedAt)),
            startedAt = ISO_FMT.format(Date(row.startedAt)),
            stoppedAt = ISO_FMT.format(Date(stopped)),
            totalSeconds = total,
            notes = row.notes,
            clientUuid = row.clientUuid,
            track = points.map { p ->
                TrackPointDto(
                    recordedAt = ISO_FMT.format(Date(p.recordedAt)),
                    lat = p.lat, lon = p.lon,
                    elevationM = p.elevationM, accuracyM = p.accuracyM, speedMps = p.speedMps,
                )
            },
        )
    }

    private fun defaultName(row: ActivityEntity): String {
        val label = row.typeName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        return "$label ${DATE_FMT.format(Date(row.startedAt))}"
    }

    companion object {
        private const val TAG = "TraxxxSync"
        private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

        /** Also used by the HistoryScreen pull-to-refresh. */
        suspend fun refreshHistory(ctx: Context) {
            val prefs = Prefs(ctx).flow.first()
            val userId = prefs.userId ?: return
            val api = NetModule.build(prefs.apiBase)
            val list = api.listActivities(userId)
            val repo = Repository.get(ctx)
            for (dto in list) {
                val uuid = dto.clientUuid?.takeIf { it.isNotBlank() } ?: continue
                val startedMs = dto.startedAt?.let { parseIso(it) } ?: continue
                repo.upsertFromServer(ActivityEntity(
                    clientUuid = uuid,
                    serverId = dto.id,
                    userId = dto.userId,
                    typeName = dto.typeName ?: "other",
                    name = dto.name,
                    startedAt = startedMs,
                    stoppedAt = dto.stoppedAt?.let { parseIso(it) },
                    totalSeconds = dto.totalSeconds,
                    notes = dto.notes,
                    status = ActivityStatus.SYNCED,
                ))
            }
        }

        private fun parseIso(s: String): Long? = try {
            // Server emits RFC3339-ish; try with and without fractional seconds.
            val patterns = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
            )
            var out: Long? = null
            for (p in patterns) {
                try {
                    val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    out = f.parse(s)?.time
                    if (out != null) break
                } catch (_: Throwable) {}
            }
            out
        } catch (_: Throwable) { null }
    }
}
