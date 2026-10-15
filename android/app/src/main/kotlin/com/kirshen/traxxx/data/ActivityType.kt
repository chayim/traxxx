package com.kirshen.traxxx.data

/** Fixed catalog of activity types shown in the UI. `name` matches the server type name. */
enum class ActivityType(val typeName: String, val label: String, val gps: Boolean) {
    WALK("walk", "Walk", true),
    RUN("run", "Run", true),
    ROLLERBLADE("rollerblade", "Inline skate", true),
    HIKE("hike", "Hike", true),
    RIDE("ride", "Ride", false),
    SWIM("swim", "Swim", false),
    OTHER("other", "Other", false);

    companion object {
        fun fromName(name: String?): ActivityType? = entries.firstOrNull { it.typeName == name }
    }
}
