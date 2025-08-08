package com.example.controlsassist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.d("BootReceiver", "Received action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {

            // Load saved preferences
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val musicLock = prefs.getBoolean("lock_music", false)
            val alarmLock = prefs.getBoolean("lock_alarm", false)
            val ringLock = prefs.getBoolean("lock_ring", false)
            val notifLock = prefs.getBoolean("lock_notification", false)
            val sysLock = prefs.getBoolean("lock_system", false)
            val callLock = prefs.getBoolean("lock_call", false)
            val brightLock = prefs.getBoolean("lock_brightness", false)

            // Only start service if at least one lock is active
            if (musicLock || alarmLock || ringLock || notifLock || sysLock || callLock || brightLock) {
                Log.d("BootReceiver", "Locks found — starting ControlService")

                val serviceIntent = Intent(context, ControlService::class.java).apply {
                    putExtra("lock_music", musicLock)
                    putExtra("lock_alarm", alarmLock)
                    putExtra("lock_ring", ringLock)
                    putExtra("lock_notification", notifLock)
                    putExtra("lock_system", sysLock)
                    putExtra("lock_call", callLock)
                    putExtra("lock_brightness", brightLock)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                Log.d("BootReceiver", "No locks enabled — service not started")
            }
        }
    }
}
