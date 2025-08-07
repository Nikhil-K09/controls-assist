package com.example.controlsassist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ControlService : Service() {

    private val CHANNEL_ID = "control_service_channel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Controls Assist Running")
            .setContentText("Your background controls are active.")
            .setSmallIcon(R.drawable.ic_notifications)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        // TODO: Add your background logic here (e.g. call monitor, settings lock, etc.)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Control Assist Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Control Assist running in the background"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
