package com.kirshen.traxxx.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.kirshen.traxxx.data.dao.ActivityDao
import com.kirshen.traxxx.data.dao.TrackPointDao
import com.kirshen.traxxx.data.dao.WorkoutDao
import com.kirshen.traxxx.data.dao.WorkoutExerciseDao
import com.kirshen.traxxx.data.dao.WorkoutSetDao
import com.kirshen.traxxx.data.dao.WorkoutTemplateDao
import com.kirshen.traxxx.data.entities.ActivityEntity
import com.kirshen.traxxx.data.entities.ActivityStatus
import com.kirshen.traxxx.data.entities.TemplateExerciseCacheEntity
import com.kirshen.traxxx.data.entities.TrackPointEntity
import com.kirshen.traxxx.data.entities.WorkoutEntity
import com.kirshen.traxxx.data.entities.WorkoutExerciseEntity
import com.kirshen.traxxx.data.entities.WorkoutSetEntity
import com.kirshen.traxxx.data.entities.WorkoutStatus
import com.kirshen.traxxx.data.entities.WorkoutTemplateCacheEntity

class StatusConverter {
    @TypeConverter fun toStr(s: ActivityStatus): String = s.name
    @TypeConverter fun fromStr(s: String): ActivityStatus = ActivityStatus.valueOf(s)
    @TypeConverter fun toWorkoutStr(s: WorkoutStatus): String = s.name
    @TypeConverter fun fromWorkoutStr(s: String): WorkoutStatus = WorkoutStatus.valueOf(s)
}

@Database(
    entities = [
        ActivityEntity::class, TrackPointEntity::class,
        WorkoutEntity::class, WorkoutExerciseEntity::class, WorkoutSetEntity::class,
        WorkoutTemplateCacheEntity::class, TemplateExerciseCacheEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(StatusConverter::class)
abstract class AppDb : RoomDatabase() {
    abstract fun activities(): ActivityDao
    abstract fun trackPoints(): TrackPointDao
    abstract fun workouts(): WorkoutDao
    abstract fun workoutExercises(): WorkoutExerciseDao
    abstract fun workoutSets(): WorkoutSetDao
    abstract fun workoutTemplates(): WorkoutTemplateDao

    companion object {
        @Volatile private var INSTANCE: AppDb? = null
        fun get(ctx: Context): AppDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "traxxx.db")
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }
    }
}
