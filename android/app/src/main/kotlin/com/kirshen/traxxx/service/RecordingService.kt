package com.kirshen.traxxx.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kirshen.traxxx.MainActivity
import com.kirshen.traxxx.R
import com.kirshen.traxxx.data.ActivityType
import com.kirshen.traxxx.data.Repository
import com.kirshen.traxxx.data.entities.ActivityEntity
import com.kirshen.traxxx.data.entities.TrackPointEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that owns the GPS listener and persists each fix to the DB. Lives
 * exactly as long as there is an in-progress activity row. START_STICKY + BootReceiver
 * guarantee it resumes after process death or reboot.
 */
class RecordingService : Service(), LocationListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo by lazy { Repository.get(this) }
    private val lm by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }

    private var activeUuid: String? = null
    private var activeType: ActivityType? = null
    private val pointCount = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start foreground immediately so Android doesn't ANR us.
        startForegroundCompat(NOTIF_ID, buildNotification("Recording…", "Looking for in-progress run"))
        scope.launch {
            val row = repo.inProgress()
            if (row == null) {
                stopForegroundAndSelf()
                return@launch
            }
            attach(row)
        }
        return START_STICKY
    }

    private suspend fun attach(row: ActivityEntity) {
        activeUuid = row.clientUuid
        activeType = ActivityType.fromName(row.typeName)
        pointCount.set(repo.pointCount(row.clientUuid))

        RecordingState.set(RecordingState.Snapshot(
            clientUuid = row.clientUuid,
            typeName = row.typeName,
            startedAt = row.startedAt,
            pointCount = pointCount.get(),
        ))

        updateNotification(row)
        if (activeType?.gps == true) startGps()
    }

    private fun startGps() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "FINE_LOCATION not granted; recording time only")
            return
        }
        try {
            val providers = buildList {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            }
            for (p in providers) {
                lm.requestLocationUpdates(p, 1_000L, 0f, this)
            }
            if (providers.isEmpty()) Log.w(TAG, "No location providers enabled")
        } catch (se: SecurityException) {
            Log.w(TAG, "requestLocationUpdates denied: ${se.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        val uuid = activeUuid ?: return
        val fix = TrackPointEntity(
            activityClientUuid = uuid,
            recordedAt = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            lat = location.latitude,
            lon = location.longitude,
            elevationM = if (location.hasAltitude()) location.altitude else null,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
        )
        scope.launch {
            repo.addPoint(fix)
            val n = pointCount.incrementAndGet()
            RecordingState.update { it.copy(
                pointCount = n,
                lastAccuracyM = fix.accuracyM,
                lastFixAt = fix.recordedAt,
            ) }
            repo.inProgress()?.let { updateNotification(it, n) }
        }
    }

    private fun updateNotification(row: ActivityEntity, pts: Int = pointCount.get()) {
        val label = ActivityType.fromName(row.typeName)?.label ?: row.typeName
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(
            title = "Recording $label",
            body = "${pts} points · tap to open",
        ))
    }

    private fun buildNotification(title: String, body: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun startForegroundCompat(id: Int, notif: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, id, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            super.startForeground(id, notif)
        }
    }

    private fun stopForegroundAndSelf() {
        try { lm.removeUpdates(this) } catch (_: Throwable) {}
        RecordingState.set(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { lm.removeUpdates(this) } catch (_: Throwable) {}
        RecordingState.set(null)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TraxxxService"
        const val CHANNEL_ID = "recording"
        const val NOTIF_ID = 42

        fun start(ctx: Context) {
            val i = Intent(ctx, RecordingService::class.java)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RecordingService::class.java))
        }

        fun createChannel(ctx: Context) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.recording_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = ctx.getString(R.string.recording_channel_desc)
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
    }
}
