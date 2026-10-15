package com.kirshen.traxxx.net

import android.content.Context
import com.kirshen.traxxx.data.Prefs
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/** Builds a Retrofit [Api] whose base URL tracks the user's current server pref. */
object NetModule {

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    /** Fresh Retrofit each call — cheap and avoids base-URL staleness after settings change. */
    suspend fun api(ctx: Context): Api {
        val base = Prefs(ctx).flow.first().apiBase
        return build(base)
    }

    fun build(base: String): Api {
        val normalized = if (base.endsWith("/")) base else "$base/"
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(Api::class.java)
    }
}
