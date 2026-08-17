package com.example.randomoverlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.edit

@SuppressLint("WrongConstant")
class AreaSelectionService : Service() {

    private lateinit var windowManager: WindowManager
    private var containerView: FrameLayout? = null
    private lateinit var prefs: SharedPreferences

    companion object {
        const val TAG = "AreaSelectionService"
        const val NOTIFICATION_CHANNEL_ID = "AreaSelectionServiceChannel"
        const val NOTIFICATION_ID = 2
        const val ACTION_CANCEL_SELECTION = "com.example.randomoverlay.ACTION_CANCEL_SELECTION"

        private const val PREFS_NAME = "RandomOverlayPrefs"
        private const val KEY_CUSTOM_AREA_LEFT = "custom_area_left"
        private const val KEY_CUSTOM_AREA_TOP = "custom_area_top"
        private const val KEY_CUSTOM_AREA_RIGHT = "custom_area_right"
        private const val KEY_CUSTOM_AREA_BOTTOM = "custom_area_bottom"
        private const val KEY_CUSTOM_AREA_DEFINED = "custom_area_defined"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_SELECTION) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (containerView == null) {
            setupSelectionView()
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun setupSelectionView() {
        containerView = FrameLayout(this)

        val areaSelectionView = (LayoutInflater.from(this).inflate(R.layout.area_selection_layout, containerView, false) as? AreaSelectionView)
            ?: run { stopSelf(); return }

        // *** THE FIX IS HERE ***
        val instructionView = TextView(this).apply {
            text = "Tap rectangle to prime, tap again to confirm." // New text
            setTextColor(Color.BLACK) // Black text
            setBackgroundColor(Color.WHITE) // Solid white background
            setPadding(16, 8, 16, 8)
            gravity = Gravity.CENTER
        }

        val textParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 150
        }

        containerView?.addView(areaSelectionView)
        containerView?.addView(instructionView, textParams)

        areaSelectionView.onAreaConfirmedListener = {
            Log.d(TAG, "Area confirmed via double-tap.")
            saveSelectedArea(areaSelectionView.getRect())
            Toast.makeText(this, R.string.area_confirmed_toast, Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        val initialRect = if (prefs.getBoolean(KEY_CUSTOM_AREA_DEFINED, false)) {
            RectF(
                prefs.getInt(KEY_CUSTOM_AREA_LEFT, 100).toFloat(),
                prefs.getInt(KEY_CUSTOM_AREA_TOP, 100).toFloat(),
                prefs.getInt(KEY_CUSTOM_AREA_RIGHT, 400).toFloat(),
                prefs.getInt(KEY_CUSTOM_AREA_BOTTOM, 400).toFloat()
            )
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val rectSize = 300f
            val left = (screenWidth / 2f) - (rectSize / 2f)
            val top = (screenHeight / 2f) - (rectSize / 2f)
            RectF(left, top, left + rectSize, top + rectSize)
        }
        areaSelectionView.setRect(initialRect)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(containerView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding AreaSelectionView container to WindowManager", e)
            stopSelf()
        }
    }

    private fun saveSelectedArea(rect: RectF) {
        prefs.edit {
            putInt(KEY_CUSTOM_AREA_LEFT, rect.left.toInt())
            putInt(KEY_CUSTOM_AREA_TOP, rect.top.toInt())
            putInt(KEY_CUSTOM_AREA_RIGHT, rect.right.toInt())
            putInt(KEY_CUSTOM_AREA_BOTTOM, rect.bottom.toInt())
            putBoolean(KEY_CUSTOM_AREA_DEFINED, true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        containerView?.let {
            if (it.isAttachedToWindow) {
                try { windowManager.removeView(it) } catch (e: Exception) { Log.e(TAG, "Error removing container view", e) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.area_selection_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.area_selection_channel_description)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val cancelIntent = Intent(this, AreaSelectionService::class.java).apply {
            action = ACTION_CANCEL_SELECTION
        }
        val pIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pCancelIntent = PendingIntent.getService(this, 0, cancelIntent, pIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.area_selection_notification_title))
            .setContentText(getString(R.string.area_selection_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .addAction(0, "Cancel", pCancelIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}