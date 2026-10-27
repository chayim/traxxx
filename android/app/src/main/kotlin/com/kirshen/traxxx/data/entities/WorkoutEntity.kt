package com.kirshen.traxxx.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class WorkoutStatus { PENDING, SYNCED }

@Entity(tableName = "workouts", indices = [Index("userId"), Index("status")])
data class WorkoutEntity(
    @PrimaryKey val clientUuid: String,
    val serverId: Long? = null,
    val userId: Long,
    val templateServerId: Long? = null,
    val name: String,
    val workoutDate: String,
    val notes: String? = null,
    val status: WorkoutStatus = WorkoutStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "workout_exercises", indices = [Index("workoutClientUuid")])
data class WorkoutExerciseEntity(
    @PrimaryKey val clientUuid: String,
    val workoutClientUuid: String,
    val position: Int,
    val name: String,
    val targetSets: Int? = null,
    val targetReps: Int? = null,
    val targetSeconds: Int? = null,
)

@Entity(tableName = "workout_sets", indices = [Index("exerciseClientUuid")])
data class WorkoutSetEntity(
    @PrimaryKey val clientUuid: String,
    val exerciseClientUuid: String,
    val setNumber: Int,
    val reps: Int? = null,
    val seconds: Int? = null,
)

@Entity(tableName = "workout_templates_cache")
data class WorkoutTemplateCacheEntity(
    @PrimaryKey val serverId: Long,
    val userId: Long,
    val name: String,
)

@Entity(tableName = "template_exercises_cache", indices = [Index("templateServerId")])
data class TemplateExerciseCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateServerId: Long,
    val position: Int,
    val name: String,
    val targetSets: Int? = null,
    val targetReps: Int? = null,
    val targetSeconds: Int? = null,
)
