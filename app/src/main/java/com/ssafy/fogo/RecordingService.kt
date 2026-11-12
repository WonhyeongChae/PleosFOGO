package com.ssafy.fogo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class RecordingService : Service() {
    private val fogo get() = application as FogoApplication

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("recording", "주행 기록", NotificationManager.IMPORTANCE_LOW),
            )
            val notification = Notification.Builder(this, "recording")
                .setContentTitle("FOGO 주행 기록")
                .setContentText("방문한 1km 블록을 기기에 저장하고 있어요")
                .setSmallIcon(R.drawable.fogo_icon)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(1, notification)
            fogo.startLocation()
        } catch (error: Exception) {
            fogo.locationError(error)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        fogo.stopLocation()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
