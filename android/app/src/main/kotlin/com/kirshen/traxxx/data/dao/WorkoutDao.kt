package com.kirshen.traxxx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kirshen.traxxx.data.entities.TemplateExerciseCacheEntity
import com.kirshen.traxxx.data.entities.WorkoutEntity
import com.kirshen.traxxx.data.entities.WorkoutExerciseEntity
import com.kirshen.traxxx.data.entities.WorkoutSetEntity
import com.kirshen.traxxx.data.entities.WorkoutStatus
import com.kirshen.traxxx.data.entities.WorkoutTemplateCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(w: WorkoutEntity)

    @Query("SELECT * FROM workouts WHERE clientUuid = :uuid")
    suspend fun byUuid(uuid: String): WorkoutEntity?

    @Query("SELECT * FROM workouts WHERE clientUuid = :uuid")
    fun observeByUuid(uuid: String): Flow<WorkoutEntity?>

    @Query("SELECT * FROM workouts WHERE userId = :userId ORDER BY workoutDate DESC, createdAt DESC")
    fun observeForUser(userId: Long): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE status = :status")
    suspend fun byStatus(status: WorkoutStatus): List<WorkoutEntity>

    @Query("UPDATE workouts SET serverId = :serverId, status = :status WHERE clientUuid = :uuid")
    suspend fun markSynced(uuid: String, serverId: Long, status: WorkoutStatus)

    @Query("DELETE FROM workouts WHERE clientUuid = :uuid")
    suspend fun delete(uuid: String)

    @Query("SELECT COUNT(*) FROM workouts WHERE status = 'PENDING'")
    fun pendingCountFlow(): Flow<Int>
}

@Dao
interface WorkoutExerciseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: WorkoutExerciseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(e: List<WorkoutExerciseEntity>)

    @Query("SELECT * FROM workout_exercises WHERE workoutClientUuid = :uuid ORDER BY position")
    suspend fun forWorkout(uuid: String): List<WorkoutExerciseEntity>

    @Query("SELECT * FROM workout_exercises WHERE workoutClientUuid = :uuid ORDER BY position")
    fun observeForWorkout(uuid: String): Flow<List<WorkoutExerciseEntity>>

    @Query("DELETE FROM workout_exercises WHERE workoutClientUuid = :uuid")
    suspend fun deleteFor(uuid: String)

    @Query("DELETE FROM workout_exercises WHERE clientUuid = :uuid")
    suspend fun delete(uuid: String)
}

@Dao
interface WorkoutSetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: WorkoutSetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(s: List<WorkoutSetEntity>)

    @Query("SELECT * FROM workout_sets WHERE exerciseClientUuid = :uuid ORDER BY setNumber")
    suspend fun forExercise(uuid: String): List<WorkoutSetEntity>

    @Query("SELECT * FROM workout_sets WHERE exerciseClientUuid IN (:exerciseUuids) ORDER BY setNumber")
    fun observeForExercises(exerciseUuids: List<String>): Flow<List<WorkoutSetEntity>>

    @Query("DELETE FROM workout_sets WHERE exerciseClientUuid = :uuid")
    suspend fun deleteFor(uuid: String)

    @Query("DELETE FROM workout_sets WHERE clientUuid = :uuid")
    suspend fun delete(uuid: String)
}

@Dao
interface WorkoutTemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplate(t: WorkoutTemplateCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplates(t: List<WorkoutTemplateCacheEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExercises(e: List<TemplateExerciseCacheEntity>)

    @Query("SELECT * FROM workout_templates_cache WHERE userId = :userId ORDER BY name")
    fun observeForUser(userId: Long): Flow<List<WorkoutTemplateCacheEntity>>

    @Query("SELECT * FROM workout_templates_cache WHERE serverId = :id")
    suspend fun byId(id: Long): WorkoutTemplateCacheEntity?

    @Query("SELECT * FROM template_exercises_cache WHERE templateServerId = :id ORDER BY position")
    suspend fun exercisesForTemplate(id: Long): List<TemplateExerciseCacheEntity>

    @Query("DELETE FROM template_exercises_cache WHERE templateServerId = :id")
    suspend fun deleteExercisesForTemplate(id: Long)

    @Query("DELETE FROM workout_templates_cache WHERE serverId = :id")
    suspend fun deleteTemplate(id: Long)
}
