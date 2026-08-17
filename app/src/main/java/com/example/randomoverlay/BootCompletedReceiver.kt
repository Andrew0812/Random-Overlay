package com.example.randomoverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
        // SharedPreferences keys needed by the receiver
        private const val PREFS_NAME = "RandomOverlayPrefs" // Must match MainActivity
        // KEY_OVERLAY_ACTIVE should be public in MainActivity's companion object or defined here consistently
        // For now, assuming MainActivity.KEY_OVERLAY_ACTIVE is accessible or define it here
        // Let's assume MainActivity.KEY_OVERLAY_ACTIVE is the source of truth and accessible
        // If not, we'd use: private const val KEY_OVERLAY_ACTIVE = "overlay_active"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Device boot completed.") // Use Info log for better visibility

            val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Access KEY_OVERLAY_ACTIVE via MainActivity.KEY_OVERLAY_ACTIVE if it's public there
            // Or, if you prefer, define it again in this Companion Object for local clarity
            val overlayWasActive = sharedPreferences.getBoolean(MainActivity.KEY_OVERLAY_ACTIVE, false)
            val videoLockWasActive = sharedPreferences.getBoolean(MainActivity.KEY_VIDEO_LOCK_ACTIVE, false)

            if (overlayWasActive && !videoLockWasActive) {
                Log.i(TAG, "Regular overlay was active. Attempting to restart OverlayService.")

                val serviceIntent = Intent(context, OverlayService::class.java).apply {
                    // Use the action string defined in OverlayService.kt
                    action = OverlayService.ACTION_START_FROM_BOOT
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.i(TAG, "OverlayService start command issued from boot receiver with action: ${serviceIntent.action}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting OverlayService from BootCompletedReceiver", e)
                }
            } else {
                Log.i(TAG, "OverlayService was not active before reboot. Not restarting.")
            }
        }
    }
}