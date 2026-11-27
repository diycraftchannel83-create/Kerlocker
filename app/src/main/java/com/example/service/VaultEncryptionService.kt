package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.BrankasKuApp
import com.example.MainActivity
import com.example.R
import com.example.ui.ImportProgressState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VaultEncryptionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private var sawActiveProgress = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? BrankasKuApp
        val coordinator = app?.backgroundEncryptionCoordinator
        val initialProgress = coordinator?.progressFlow?.value

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            PROGRESS_NOTIFICATION_ID,
            buildProgressNotification(initialProgress),
            foregroundType
        )

        if (coordinator == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        if (observerJob == null) {
            observerJob = serviceScope.launch {
                coordinator.progressFlow.collect { progress ->
                    if (progress != null) {
                        sawActiveProgress = true
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(PROGRESS_NOTIFICATION_ID, buildProgressNotification(progress))
                    } else if (sawActiveProgress) {
                        val hasPendingDelete = coordinator.pendingSourceDeleteFlow.value != null
                        if (hasPendingDelete) {
                            postCompletionNotification(
                                title = "Enkripsi Locker selesai",
                                text = "Ketuk untuk konfirmasi hapus permanen file asli."
                            )
                        }
                        ServiceCompat.stopForeground(this@VaultEncryptionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }

        if (initialProgress == null && !coordinator.isBusy) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Enkripsi Latar Belakang",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Menampilkan progres enkripsi file ke Locker di latar belakang"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun createOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildProgressNotification(progress: ImportProgressState?): Notification {
        val total = progress?.total ?: 0
        val processed = progress?.processed ?: 0
        val contentText = progress?.currentFileName
            ?: "Menyiapkan enkripsi AES-256-GCM..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Locker • Mengenkripsi di Background")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), processed.coerceIn(0, total.coerceAtLeast(1)), total <= 0)
            .setContentIntent(createOpenAppPendingIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun postCompletionNotification(title: String, text: String) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(createOpenAppPendingIntent())
                .build()
            nm.notify(COMPLETION_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "locker_encryption_channel"
        private const val PROGRESS_NOTIFICATION_ID = 4201
        private const val COMPLETION_NOTIFICATION_ID = 4202

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, VaultEncryptionService::class.java)
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
