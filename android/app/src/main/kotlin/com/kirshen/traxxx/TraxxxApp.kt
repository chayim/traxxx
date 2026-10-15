package com.kirshen.traxxx

import android.app.Application
import com.kirshen.traxxx.service.RecordingService
import com.kirshen.traxxx.sync.SyncScheduler

class TraxxxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RecordingService.createChannel(this)
        SyncScheduler.ensurePeriodic(this)
    }
}
