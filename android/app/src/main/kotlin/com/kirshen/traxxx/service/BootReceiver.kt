package com.kirshen.traxxx.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kirshen.traxxx.data.Repository
import com.kirshen.traxxx.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** On boot: if a recording was in-progress at shutdown, start the service so it continues. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val repo = Repository.get(context)
                if (repo.inProgress() != null) {
                    Log.i(TAG, "resuming recording after boot/replace")
                    RecordingService.start(context)
                }
                // Always nudge sync in case anything is pending.
                SyncScheduler.enqueueOneShot(context)
                SyncScheduler.ensurePeriodic(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object { private const val TAG = "TraxxxBoot" }
}
