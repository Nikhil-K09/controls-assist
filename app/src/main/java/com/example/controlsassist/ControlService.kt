package com.example.controlsassist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import android.content.SharedPreferences

class ControlService : Service() {

    private lateinit var audioManager: AudioManager
    private lateinit var handler: Handler
    private lateinit var prefs: SharedPreferences

    private val lockStatus = mutableMapOf<Int, Boolean>() // Stream type to lock status
    private val targetVolumes = mutableMapOf<Int, Int>()  // Stream type to desired volume

    private var brightnessLocked = false
    private var lockedBrightness = -1 // -1 means not set

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        handler = Handler(Looper.getMainLooper())
        prefs = getSharedPreferences("ControlPrefs", MODE_PRIVATE)

        createNotificationChannel()
        startForeground(1, buildNotification())
        startControlMonitor()

        // Initialize Call stream lock status so it's recognized by monitor loop
        lockStatus[AudioManager.STREAM_VOICE_CALL] = false
        targetVolumes[AudioManager.STREAM_VOICE_CALL] =
            audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startControlMonitor() {
        handler.post(object : Runnable {
            override fun run() {
                // Volume Locking (including Call)
                for ((streamType, isLocked) in lockStatus) {
                    if (isLocked) {
                        val current = audioManager.getStreamVolume(streamType)
                        val target = targetVolumes[streamType] ?: continue

                        if (current != target) {
                            if (streamType == AudioManager.STREAM_RING && target == 0) {
                                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                                audioManager.setStreamVolume(streamType, 1, 0)
                            } else {
                                try {
                                    audioManager.setStreamVolume(streamType, target, 0)
                                } catch (e: SecurityException) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                }

                // Brightness Locking
                if (brightnessLocked && lockedBrightness >= 0) {
                    try {
                        val currentBrightness = Settings.System.getInt(
                            contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS
                        )
                        if (currentBrightness != lockedBrightness) {
                            Settings.System.putInt(
                                contentResolver,
                                Settings.System.SCREEN_BRIGHTNESS,
                                lockedBrightness
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                handler.postDelayed(this, 500)
            }
        })
    }

    fun setVolumeLock(streamType: Int, volume: Int, locked: Boolean) {
        targetVolumes[streamType] = volume
        lockStatus[streamType] = locked
    }

    fun setBrightnessLock(locked: Boolean) {
        brightnessLocked = locked
        if (locked) {
            try {
                lockedBrightness = Settings.System.getInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                )
            } catch (e: Exception) {
                e.printStackTrace()
                lockedBrightness = -1
            }
        } else {
            lockedBrightness = -1
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "control_service_channel",
                "Control Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows service running for volume and brightness control"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, "control_service_channel")
        .setContentTitle("Controls Assist")
        .setContentText("Volume and brightness lock active")
        .setSmallIcon(R.drawable.ic_notifications)
        .setOngoing(true)
        .build()
}