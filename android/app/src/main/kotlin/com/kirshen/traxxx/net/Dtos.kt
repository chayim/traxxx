package com.kirshen.traxxx.net

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class UserDto(
    val id: Long,
    val name: String,
)

@JsonClass(generateAdapter = true)
data class CreateUserDto(val name: String)

@JsonClass(generateAdapter = true)
data class TypeDto(
    val id: Long,
    val name: String,
    val retired: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class CreateTypeDto(val name: String)

@JsonClass(generateAdapter = true)
data class ActivityDto(
    val id: Long,
    @Json(name = "user_id") val userId: Long,
    @Json(name = "type_id") val typeId: Long,
    @Json(name = "type_name") val typeName: String? = null,
    val name: String? = null,
    @Json(name = "activity_date") val activityDate: String,
    @Json(name = "started_at") val startedAt: String? = null,
    @Json(name = "stopped_at") val stoppedAt: String? = null,
    @Json(name = "total_seconds") val totalSeconds: Long? = null,
    val notes: String? = null,
    @Json(name = "client_uuid") val clientUuid: String? = null,
    @Json(name = "has_track") val hasTrack: Boolean = false,
    @Json(name = "track_point_count") val trackPointCount: Long = 0,
)

@JsonClass(generateAdapter = true)
data class TrackPointDto(
    @Json(name = "recorded_at") val recordedAt: String,
    val lat: Double,
    val lon: Double,
    @Json(name = "elevation_m") val elevationM: Double? = null,
    @Json(name = "accuracy_m") val accuracyM: Double? = null,
    @Json(name = "speed_mps") val speedMps: Double? = null,
)

@JsonClass(generateAdapter = true)
data class CreateActivityDto(
    @Json(name = "user_id") val userId: Long,
    @Json(name = "type_id") val typeId: Long,
    val name: String,
    @Json(name = "activity_date") val activityDate: String,
    @Json(name = "started_at") val startedAt: String,
    @Json(name = "stopped_at") val stoppedAt: String,
    @Json(name = "total_seconds") val totalSeconds: Long,
    val notes: String? = null,
    @Json(name = "client_uuid") val clientUuid: String,
    val track: List<TrackPointDto>,
)

// --- Workouts ---

@JsonClass(generateAdapter = true)
data class TemplateExerciseDto(
    val id: Long = 0,
    @Json(name = "template_id") val templateId: Long = 0,
    val position: Int,
    val name: String,
    @Json(name = "target_sets") val targetSets: Int? = null,
    @Json(name = "target_reps") val targetReps: Int? = null,
    @Json(name = "target_seconds") val targetSeconds: Int? = null,
)

@JsonClass(generateAdapter = true)
data class WorkoutTemplateDto(
    val id: Long,
    @Json(name = "user_id") val userId: Long,
    val name: String,
    @Json(name = "created_at") val createdAt: String? = null,
    val exercises: List<TemplateExerciseDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class CreateWorkoutTemplateDto(
    @Json(name = "user_id") val userId: Long,
    val name: String,
    val exercises: List<TemplateExerciseDto>,
)

@JsonClass(generateAdapter = true)
data class CloneTemplateDto(
    @Json(name = "workout_date") val workoutDate: String? = null,
    val name: String? = null,
)

@JsonClass(generateAdapter = true)
data class WorkoutSetDto(
    val id: Long = 0,
    @Json(name = "exercise_id") val exerciseId: Long = 0,
    @Json(name = "set_number") val setNumber: Int,
    val reps: Int? = null,
    val seconds: Int? = null,
)

@JsonClass(generateAdapter = true)
data class WorkoutExerciseDto(
    val id: Long = 0,
    @Json(name = "workout_id") val workoutId: Long = 0,
    val position: Int,
    val name: String,
    @Json(name = "target_sets") val targetSets: Int? = null,
    @Json(name = "target_reps") val targetReps: Int? = null,
    @Json(name = "target_seconds") val targetSeconds: Int? = null,
    val sets: List<WorkoutSetDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class WorkoutDto(
    val id: Long,
    @Json(name = "user_id") val userId: Long,
    @Json(name = "template_id") val templateId: Long? = null,
    val name: String,
    @Json(name = "workout_date") val workoutDate: String,
    val notes: String? = null,
    @Json(name = "client_uuid") val clientUuid: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    val exercises: List<WorkoutExerciseDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class WeightDto(
    val id: Long = 0,
    @Json(name = "user_id") val userId: Long = 0,
    @Json(name = "weight_kg") val weightKg: Double,
    val date: String,
    @Json(name = "created_at") val createdAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class CreateWeightDto(
    @Json(name = "user_id") val userId: Long,
    @Json(name = "weight_kg") val weightKg: Double,
    val date: String,
)

@JsonClass(generateAdapter = true)
data class UpdateWeightDto(
    @Json(name = "weight_kg") val weightKg: Double,
    val date: String,
)

@JsonClass(generateAdapter = true)
data class WeightsPageDto(
    val items: List<WeightDto> = emptyList(),
    val total: Long = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@JsonClass(generateAdapter = true)
data class CreateWorkoutDto(
    @Json(name = "user_id") val userId: Long,
    @Json(name = "template_id") val templateId: Long? = null,
    val name: String,
    @Json(name = "workout_date") val workoutDate: String,
    val notes: String? = null,
    @Json(name = "client_uuid") val clientUuid: String,
    val exercises: List<WorkoutExerciseDto>,
)
