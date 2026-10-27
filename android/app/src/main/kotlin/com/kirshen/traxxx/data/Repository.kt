package com.kirshen.traxxx.data

import android.content.Context
import com.kirshen.traxxx.data.entities.ActivityEntity
import com.kirshen.traxxx.data.entities.ActivityStatus
import com.kirshen.traxxx.data.entities.TemplateExerciseCacheEntity
import com.kirshen.traxxx.data.entities.TrackPointEntity
import com.kirshen.traxxx.data.entities.WorkoutEntity
import com.kirshen.traxxx.data.entities.WorkoutExerciseEntity
import com.kirshen.traxxx.data.entities.WorkoutSetEntity
import com.kirshen.traxxx.data.entities.WorkoutStatus
import com.kirshen.traxxx.data.entities.WorkoutTemplateCacheEntity
import com.kirshen.traxxx.net.WorkoutDto
import com.kirshen.traxxx.net.WorkoutTemplateDto
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Thin façade used by the UI, the recording service, and the sync worker. */
class Repository private constructor(ctx: Context) {

    private val db = AppDb.get(ctx)
    val activities = db.activities()
    val trackPoints = db.trackPoints()
    val workouts = db.workouts()
    val workoutExercises = db.workoutExercises()
    val workoutSets = db.workoutSets()
    val workoutTemplates = db.workoutTemplates()

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
        val finalName = name?.trim()?.ifBlank { null }
            ?: ActivityType.defaultName(existing.typeName, existing.startedAt)
        activities.finish(
            uuid = uuid,
            stoppedAt = stoppedAt,
            totalSeconds = total,
            name = finalName,
            notes = notes?.trim()?.ifBlank { null },
            status = ActivityStatus.PENDING,
        )
    }

    suspend fun discard(uuid: String) {
        trackPoints.deleteFor(uuid)
        activities.delete(uuid)
    }

    suspend fun byUuid(uuid: String): ActivityEntity? = activities.byUuid(uuid)

    /** Delete locally; also remove on the server when the row has been synced. */
    suspend fun deleteActivity(ctx: Context, uuid: String) {
        val row = activities.byUuid(uuid)
        if (row?.serverId != null) {
            runCatching { com.kirshen.traxxx.net.NetModule.api(ctx).deleteActivity(row.serverId) }
        }
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

    // ---------- Workouts ----------

    data class WorkoutDraft(
        val workout: WorkoutEntity,
        val exercises: List<Pair<WorkoutExerciseEntity, List<WorkoutSetEntity>>>,
    )

    fun observeWorkouts(userId: Long): Flow<List<WorkoutEntity>> = workouts.observeForUser(userId)
    fun pendingWorkoutCountFlow(): Flow<Int> = workouts.pendingCountFlow()
    fun observeWorkout(uuid: String): Flow<WorkoutEntity?> = workouts.observeByUuid(uuid)
    fun observeWorkoutExercises(uuid: String): Flow<List<WorkoutExerciseEntity>> =
        workoutExercises.observeForWorkout(uuid)

    suspend fun workoutDetail(uuid: String): WorkoutDraft? {
        val w = workouts.byUuid(uuid) ?: return null
        val exs = workoutExercises.forWorkout(uuid)
        val groups = exs.map { e -> e to workoutSets.forExercise(e.clientUuid) }
        return WorkoutDraft(w, groups)
    }

    suspend fun saveWorkout(draft: WorkoutDraft, markPending: Boolean = true) {
        val w = if (markPending) draft.workout.copy(status = WorkoutStatus.PENDING)
                else draft.workout
        workouts.upsert(w)
        workoutExercises.deleteFor(w.clientUuid)
        for ((ex, sets) in draft.exercises) {
            workoutExercises.upsert(ex.copy(workoutClientUuid = w.clientUuid))
            workoutSets.deleteFor(ex.clientUuid)
            for (s in sets) {
                workoutSets.upsert(s.copy(exerciseClientUuid = ex.clientUuid))
            }
        }
    }

    suspend fun discardWorkout(uuid: String) {
        for (ex in workoutExercises.forWorkout(uuid)) {
            workoutSets.deleteFor(ex.clientUuid)
        }
        workoutExercises.deleteFor(uuid)
        workouts.delete(uuid)
    }

    suspend fun pendingWorkoutsToSync(): List<WorkoutEntity> =
        workouts.byStatus(WorkoutStatus.PENDING)

    suspend fun markWorkoutSynced(uuid: String, serverId: Long) =
        workouts.markSynced(uuid, serverId, WorkoutStatus.SYNCED)

    /** Insert or merge a workout pulled from the server. */
    suspend fun upsertWorkoutFromServer(dto: WorkoutDto) {
        val uuid = dto.clientUuid?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val existing = workouts.byUuid(uuid)
        val w = WorkoutEntity(
            clientUuid = uuid,
            serverId = dto.id,
            userId = dto.userId,
            templateServerId = dto.templateId,
            name = dto.name,
            workoutDate = dto.workoutDate,
            notes = dto.notes,
            status = WorkoutStatus.SYNCED,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        workouts.upsert(w)
        workoutExercises.deleteFor(uuid)
        for ((i, exDto) in dto.exercises.withIndex()) {
            val exUuid = UUID.randomUUID().toString()
            workoutExercises.upsert(
                WorkoutExerciseEntity(
                    clientUuid = exUuid,
                    workoutClientUuid = uuid,
                    position = if (exDto.position == 0) i + 1 else exDto.position,
                    name = exDto.name,
                    targetSets = exDto.targetSets,
                    targetReps = exDto.targetReps,
                    targetSeconds = exDto.targetSeconds,
                )
            )
            for ((j, sDto) in exDto.sets.withIndex()) {
                workoutSets.upsert(
                    WorkoutSetEntity(
                        clientUuid = UUID.randomUUID().toString(),
                        exerciseClientUuid = exUuid,
                        setNumber = if (sDto.setNumber == 0) j + 1 else sDto.setNumber,
                        reps = sDto.reps,
                        seconds = sDto.seconds,
                    )
                )
            }
        }
    }

    // ---------- Templates (server-id-keyed cache) ----------

    fun observeTemplates(userId: Long): Flow<List<WorkoutTemplateCacheEntity>> =
        workoutTemplates.observeForUser(userId)

    suspend fun templateDetail(id: Long): Pair<WorkoutTemplateCacheEntity, List<TemplateExerciseCacheEntity>>? {
        val t = workoutTemplates.byId(id) ?: return null
        return t to workoutTemplates.exercisesForTemplate(id)
    }

    suspend fun upsertTemplateFromServer(dto: WorkoutTemplateDto) {
        workoutTemplates.upsertTemplate(
            WorkoutTemplateCacheEntity(serverId = dto.id, userId = dto.userId, name = dto.name)
        )
        workoutTemplates.deleteExercisesForTemplate(dto.id)
        workoutTemplates.upsertExercises(
            dto.exercises.mapIndexed { i, e ->
                TemplateExerciseCacheEntity(
                    templateServerId = dto.id,
                    position = if (e.position == 0) i + 1 else e.position,
                    name = e.name,
                    targetSets = e.targetSets,
                    targetReps = e.targetReps,
                    targetSeconds = e.targetSeconds,
                )
            }
        )
    }

    suspend fun deleteTemplateLocal(id: Long) {
        workoutTemplates.deleteExercisesForTemplate(id)
        workoutTemplates.deleteTemplate(id)
    }

    companion object {
        @Volatile private var INSTANCE: Repository? = null
        fun get(ctx: Context): Repository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Repository(ctx).also { INSTANCE = it }
        }
    }
}
