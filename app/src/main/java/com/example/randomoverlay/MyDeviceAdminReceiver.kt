package com.example.randomoverlay

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "MyDeviceAdminReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device Admin enabled")
        Toast.makeText(context, R.string.device_admin_activated, Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Device Admin disabled")
        Toast.makeText(context, R.string.device_admin_deactivated, Toast.LENGTH_SHORT).show()
        // You might want to update a preference here if the app didn't initiate the deactivation
        // For example, if the user disabled it from settings.
        val prefs = context.getSharedPreferences("RandomOverlayPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("device_admin_requested_active", false).apply()

    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // This message is shown to the user when they try to disable the admin from settings
        return "Disabling this might make the app's timer lock easier to bypass. Are you sure?"
    }
}