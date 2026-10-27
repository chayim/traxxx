package com.kirshen.traxxx.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "traxxx")

class Prefs(private val ctx: Context) {

    data class Snapshot(
        val apiBase: String,
        val userId: Long?,
        val userName: String?,
        val lastActivityType: String?,
    )

    val flow: Flow<Snapshot> = ctx.dataStore.data.map { p ->
        Snapshot(
            apiBase = p[K_API_BASE] ?: DEFAULT_API_BASE,
            userId = p[K_USER_ID],
            userName = p[K_USER_NAME],
            lastActivityType = p[K_LAST_TYPE],
        )
    }

    suspend fun setApiBase(url: String) = ctx.dataStore.edit { it[K_API_BASE] = url }

    suspend fun setUser(id: Long, name: String) = ctx.dataStore.edit {
        it[K_USER_ID] = id
        it[K_USER_NAME] = name
    }

    suspend fun clearUser() = ctx.dataStore.edit {
        it.remove(K_USER_ID); it.remove(K_USER_NAME)
    }

    suspend fun setLastActivityType(typeName: String) = ctx.dataStore.edit {
        it[K_LAST_TYPE] = typeName
    }

    companion object {
        const val DEFAULT_API_BASE = "http://10.0.2.2:8080"
        private val K_API_BASE  = stringPreferencesKey("api_base")
        private val K_USER_ID   = longPreferencesKey("user_id")
        private val K_USER_NAME = stringPreferencesKey("user_name")
        private val K_LAST_TYPE = stringPreferencesKey("last_activity_type")
    }
}
