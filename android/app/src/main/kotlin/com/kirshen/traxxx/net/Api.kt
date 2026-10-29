package com.kirshen.traxxx.net

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface Api {

    @GET("api/users")
    suspend fun listUsers(): List<UserDto>

    @POST("api/users")
    suspend fun createUser(@Body body: CreateUserDto): UserDto

    @GET("api/types")
    suspend fun listTypes(@Query("all") all: Int = 1): List<TypeDto>

    @POST("api/types")
    suspend fun createType(@Body body: CreateTypeDto): TypeDto

    @GET("api/activities")
    suspend fun listActivities(@Query("user_id") userId: Long): List<ActivityDto>

    @POST("api/activities")
    suspend fun createActivity(@Body body: CreateActivityDto): ActivityDto

    @DELETE("api/activities/{id}")
    suspend fun deleteActivity(@Path("id") id: Long)

    // --- Workout templates ---

    @GET("api/templates/workouts")
    suspend fun listWorkoutTemplates(@Query("user_id") userId: Long): List<WorkoutTemplateDto>

    @POST("api/templates/workouts")
    suspend fun createWorkoutTemplate(@Body body: CreateWorkoutTemplateDto): WorkoutTemplateDto

    @GET("api/templates/workouts/{id}")
    suspend fun getWorkoutTemplate(@Path("id") id: Long): WorkoutTemplateDto

    @PUT("api/templates/workouts/{id}")
    suspend fun updateWorkoutTemplate(@Path("id") id: Long, @Body body: CreateWorkoutTemplateDto): WorkoutTemplateDto

    @DELETE("api/templates/workouts/{id}")
    suspend fun deleteWorkoutTemplate(@Path("id") id: Long)

    @POST("api/templates/workouts/{id}/clone")
    suspend fun cloneWorkoutTemplate(@Path("id") id: Long, @Body body: CloneTemplateDto): WorkoutDto

    // --- Workouts ---

    @GET("api/workouts")
    suspend fun listWorkouts(
        @Query("user_id") userId: Long,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): List<WorkoutDto>

    @POST("api/workouts")
    suspend fun createWorkout(@Body body: CreateWorkoutDto): WorkoutDto

    @GET("api/workouts/{id}")
    suspend fun getWorkout(@Path("id") id: Long): WorkoutDto

    @PUT("api/workouts/{id}")
    suspend fun updateWorkout(@Path("id") id: Long, @Body body: CreateWorkoutDto): WorkoutDto

    @DELETE("api/workouts/{id}")
    suspend fun deleteWorkout(@Path("id") id: Long)

    // --- Weights ---

    @GET("api/weights")
    suspend fun listWeights(
        @Query("user_id") userId: Long,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): WeightsPageDto

    @POST("api/weights")
    suspend fun createWeight(@Body body: CreateWeightDto): WeightDto

    @PUT("api/weights/{id}")
    suspend fun updateWeight(@Path("id") id: Long, @Body body: UpdateWeightDto): WeightDto

    @DELETE("api/weights/{id}")
    suspend fun deleteWeight(@Path("id") id: Long)
}
