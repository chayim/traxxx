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
