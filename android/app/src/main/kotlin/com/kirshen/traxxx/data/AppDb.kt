package com.kirshen.traxxx.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.kirshen.traxxx.data.dao.ActivityDao
import com.kirshen.traxxx.data.dao.TrackPointDao
import com.kirshen.traxxx.data.entities.ActivityEntity
import com.kirshen.traxxx.data.entities.ActivityStatus
import com.kirshen.traxxx.data.entities.TrackPointEntity

class StatusConverter {
    @TypeConverter fun toStr(s: ActivityStatus): String = s.name
    @TypeConverter fun fromStr(s: String): ActivityStatus = ActivityStatus.valueOf(s)
}

@Database(
    entities = [ActivityEntity::class, TrackPointEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(StatusConverter::class)
abstract class AppDb : RoomDatabase() {
    abstract fun activities(): ActivityDao
    abstract fun trackPoints(): TrackPointDao

    companion object {
        @Volatile private var INSTANCE: AppDb? = null
        fun get(ctx: Context): AppDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "traxxx.db")
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }
    }
}
