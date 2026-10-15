package com.kirshen.traxxx.net

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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
}
