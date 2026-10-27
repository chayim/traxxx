package com.kirshen.traxxx.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fixed catalog of activity types shown in the UI. `name` matches the server type name. */
enum class ActivityType(val typeName: String, val label: String, val gps: Boolean) {
    WALK("walk", "Walk", true),
    RUN("run", "Run", true),
    RIDE("ride", "Bike", true),
    INDOOR_RIDE("indoor_ride", "Indoor cycling", false),
    ROLLERBLADE("rollerblade", "Inline skate", true),
    HIKE("hike", "Hike", true),
    SWIM("swim", "Swim", false),
    OTHER("other", "Other", false);

    companion object {
        fun fromName(name: String?): ActivityType? = entries.firstOrNull { it.typeName == name }

        /** "Tuesday Walk"-style default name derived from the start timestamp and type. */
        fun defaultName(typeName: String, startedAtMs: Long): String {
            val label = fromName(typeName)?.label
                ?: typeName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            return "${DOW_FMT.format(Date(startedAtMs))} $label"
        }

        private val DOW_FMT = SimpleDateFormat("EEEE", Locale.getDefault())
    }
}
