package com.example.randomoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.VideoView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.example.randomoverlay.databinding.OverlayLayoutBinding
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class OverlayService : Service(), ConfigurationChangeDetector.OnConfigurationChangeListener {

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val randomGenerator = Random

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var configurationChangeDetector: ConfigurationChangeDetector? = null

    private var intervalMillis: Long = 30000L
    private var durationMillis: Long = 5000L
    private var folderUris: List<String> = emptyList()
    private var showPhotos: Boolean = true
    private var showGifs: Boolean = true
    private var showVideos: Boolean = true
    private var useRandomTime: Boolean = false
    private var showCloseButton: Boolean = true
    private var scaleType: String = "fit_content"
    private var concurrentMediaCount: Int = 1
    private var enableInitialVolume: Boolean = false
    private var initialVolumePercent: Int = 50
    private var forceVolume: Boolean = false
    private lateinit var audioManager: AudioManager
    private var activeVolumeForceRunnable: Runnable? = null
    private var mediaTransparencyPercent: Int = 100
    private var touchPassthrough: Boolean = false
    private var forceHardwareDecoding: Boolean = false
    @Volatile
    private var cachedMediaFiles: List<MediaItem> = emptyList()
    private var lastCacheUpdateTime: Long = 0
    private val cacheRefreshIntervalMs = 5 * 60 * 1000
    @Volatile
    private var needsCacheRefresh = true
    private val cacheUpdateLock = Any()

    private var isVideoLockActive = false
    private var audioOverlayEnabled: Boolean = false
    private var continuousAudioEnabled: Boolean = false
    private var audioOverlayUri: Uri? = null
    private var audioOverlayPlayer: MediaPlayer? = null
    private var isAudioOverlayPlaying = false
    private var isAudioOverlayStarting = false

    private var enableCustomArea: Boolean = false
    private var isCustomAreaDefined: Boolean = false
    private var customAreaRect: RectF = RectF()

    private data class ActiveOverlay(
        val id: Long,
        val mediaBinding: OverlayLayoutBinding,
        val mediaView: View,
        val mediaWmParams: WindowManager.LayoutParams,
        val closeButtonView: ImageButton?,
        val closeButtonWmParams: WindowManager.LayoutParams?,
        var mediaItem: MediaItem?,
        var hideJob: Job?,
        var isMediaViewAdded: Boolean = false,
        var isCloseButtonViewAdded: Boolean = false,
        var intrinsicWidth: Int = 0,
        var intrinsicHeight: Int = 0
    )

    private val activeOverlays = mutableListOf<ActiveOverlay>()
    private var nextOverlayId = 0L

    private val mediaDisplayRunnable: Runnable = Runnable {
        if (!isVideoLockActive) {
            showRandomMediaCycle()
        }
    }

    private val settingsUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_SETTINGS) {
                Log.d(TAG, "Received settings update broadcast. Reloading settings.")
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                loadSettingsFromPreferences(prefs)

                if (activeOverlays.isNotEmpty()) {
                    synchronized(activeOverlays) {
                        for (overlay in activeOverlays) {
                            overlay.mediaWmParams.alpha = mediaTransparencyPercent / 100.0f
                            if (overlay.isMediaViewAdded && overlay.mediaView.isAttachedToWindow) {
                                try { windowManager.updateViewLayout(overlay.mediaView, overlay.mediaWmParams) } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "OverlayService"
        const val NOTIFICATION_CHANNEL_ID = "RandomOverlayServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP_SERVICE = "com.example.randomoverlay.ACTION_STOP_SERVICE"
        const val ACTION_START_FROM_BOOT = "com.example.randomoverlay.ACTION_START_FROM_BOOT_OS_INTERNAL"
        const val ACTION_START_VIDEO_LOCK = "com.example.randomoverlay.ACTION_START_VIDEO_LOCK"
        const val ACTION_STOP_VIDEO_LOCK = "com.example.randomoverlay.ACTION_STOP_VIDEO_LOCK"
        const val ACTION_VIDEO_LOCK_FINISHED = "com.example.randomoverlay.ACTION_VIDEO_LOCK_FINISHED"
        const val ACTION_UPDATE_SETTINGS = "com.example.randomoverlay.ACTION_UPDATE_SETTINGS"

        const val EXTRA_INTERVAL_EXTRA = "interval_extra"
        const val EXTRA_DURATION_EXTRA = "duration_extra"
        const val EXTRA_FOLDER_URIS_EXTRA = "folder_uris_extra"
        const val EXTRA_SHOW_PHOTOS_EXTRA = "show_photos_extra"
        const val EXTRA_SHOW_GIFS_EXTRA = "show_gifs_extra"
        const val EXTRA_SHOW_VIDEOS_EXTRA = "show_videos_extra"
        const val EXTRA_RANDOM_TIME_EXTRA = "random_time_extra"
        const val EXTRA_SHOW_CLOSE_BUTTON_EXTRA = "show_close_button_extra"
        const val EXTRA_SCALE_TYPE_EXTRA = "scale_type_extra"
        const val EXTRA_CONCURRENT_MEDIA_COUNT_EXTRA = "concurrent_media_count_extra"
        const val EXTRA_ENABLE_INITIAL_VOLUME_EXTRA = "enable_initial_volume_extra"
        const val EXTRA_INITIAL_VOLUME_EXTRA = "initial_volume_extra"
        const val EXTRA_FORCE_VOLUME_EXTRA = "force_volume_extra"
        const val EXTRA_MEDIA_TRANSPARENCY_EXTRA = "media_transparency_extra"
        const val EXTRA_TOUCH_PASSTHROUGH_EXTRA = "touch_passthrough_extra"
        const val EXTRA_HARDWARE_DECODING_EXTRA = "hardware_decoding_extra"
        const val EXTRA_VIDEO_LOCK_URI_EXTRA = "video_lock_uri_extra"
        const val EXTRA_AUDIO_OVERLAY_ENABLED = "audio_overlay_enabled_extra"
        const val EXTRA_AUDIO_OVERLAY_URI = "audio_overlay_uri_extra"
        const val EXTRA_CONTINUOUS_AUDIO_ENABLED = "continuous_audio_enabled_extra"
        const val EXTRA_CUSTOM_AREA_ENABLED = "custom_area_enabled_extra"
        const val EXTRA_CUSTOM_AREA_DEFINED = "custom_area_defined_extra"
        const val EXTRA_CUSTOM_AREA_LEFT = "custom_area_left_extra"
        const val EXTRA_CUSTOM_AREA_TOP = "custom_area_top_extra"
        const val EXTRA_CUSTOM_AREA_RIGHT = "custom_area_right_extra"
        const val EXTRA_CUSTOM_AREA_BOTTOM = "custom_area_bottom_extra"

        private const val PREFS_NAME = "RandomOverlayPrefs"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_DURATION = "duration"
        private const val KEY_SELECTED_FOLDERS = "selected_folders"
        private const val KEY_PHOTOS_ENABLED = "photos_enabled"
        private const val KEY_GIFS_ENABLED = "gifs_enabled"
        private const val KEY_VIDEOS_ENABLED = "videos_enabled"
        private const val KEY_RANDOM_TIME_ENABLED = "random_time_enabled"
        private const val KEY_SHOW_CLOSE_BUTTON = "show_close_button"
        private const val KEY_SCALE_TYPE = "scale_type"
        private const val KEY_CONCURRENT_MEDIA_COUNT = "concurrent_media_count"
        private const val KEY_ENABLE_INITIAL_VOLUME = "enable_initial_volume"
        private const val KEY_INITIAL_VOLUME = "initial_volume"
        private const val KEY_FORCE_VOLUME = "force_volume"
        private const val KEY_MEDIA_TRANSPARENCY = "media_transparency"
        private const val KEY_TOUCH_PASSTHROUGH = "touch_passthrough"
        private const val KEY_HARDWARE_DECODING = "hardware_decoding"
        private const val KEY_CUSTOM_AREA_ENABLED = "custom_area_enabled"
        private const val KEY_CUSTOM_AREA_DEFINED = "custom_area_defined"
        private const val KEY_CUSTOM_AREA_LEFT = "custom_area_left"
        private const val KEY_CUSTOM_AREA_TOP = "custom_area_top"
        private const val KEY_CUSTOM_AREA_RIGHT = "custom_area_right"
        private const val KEY_CUSTOM_AREA_BOTTOM = "custom_area_bottom"
        private const val KEY_AUDIO_OVERLAY_ENABLED = "audio_overlay_enabled"
        private const val KEY_AUDIO_OVERLAY_URI = "audio_overlay_uri"
        private const val KEY_CONTINUOUS_AUDIO_ENABLED = "continuous_audio_enabled"
        private const val VIDEO_ONLY_MODE_LIMIT = 10
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()

        // Setup and add the configuration change detector view
        configurationChangeDetector = ConfigurationChangeDetector(this).apply {
            listener = this@OverlayService
        }
        val detectorParams = WindowManager.LayoutParams(
            1, 1, // 1x1 pixel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // Invisible to user
            PixelFormat.TRANSPARENT
        )
        try {
            windowManager.addView(configurationChangeDetector, detectorParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding ConfigurationChangeDetector", e)
        }

        val filter = IntentFilter(ACTION_UPDATE_SETTINGS)
        ContextCompat.registerReceiver(this, settingsUpdateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun loadSettingsFromPreferences(prefs: SharedPreferences) {
        intervalMillis = prefs.getLong(KEY_INTERVAL, 30L) * 1000
        durationMillis = (prefs.getLong(KEY_DURATION, 5L)).let { if (it == -1L) -1000L else it * 1000L }
        prefs.getString(KEY_SELECTED_FOLDERS, null)?.let {
            folderUris = try {
                Gson().fromJson<List<String>>(it, object : TypeToken<ArrayList<String>>() {}.type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        } ?: run { folderUris = emptyList() }
        showPhotos = prefs.getBoolean(KEY_PHOTOS_ENABLED, true)
        showGifs = prefs.getBoolean(KEY_GIFS_ENABLED, true)
        showVideos = prefs.getBoolean(KEY_VIDEOS_ENABLED, true)
        useRandomTime = prefs.getBoolean(KEY_RANDOM_TIME_ENABLED, false)
        showCloseButton = prefs.getBoolean(KEY_SHOW_CLOSE_BUTTON, true)
        scaleType = prefs.getString(KEY_SCALE_TYPE, "fit_content") ?: "fit_content"
        concurrentMediaCount = prefs.getInt(KEY_CONCURRENT_MEDIA_COUNT, 1)
        enableInitialVolume = prefs.getBoolean(KEY_ENABLE_INITIAL_VOLUME, false)
        initialVolumePercent = prefs.getInt(KEY_INITIAL_VOLUME, 50)
        forceVolume = prefs.getBoolean(KEY_FORCE_VOLUME, false)
        mediaTransparencyPercent = prefs.getInt(KEY_MEDIA_TRANSPARENCY, 100)
        touchPassthrough = prefs.getBoolean(KEY_TOUCH_PASSTHROUGH, false)
        forceHardwareDecoding = prefs.getBoolean(KEY_HARDWARE_DECODING, false)

        audioOverlayEnabled = prefs.getBoolean(KEY_AUDIO_OVERLAY_ENABLED, false)
        prefs.getString(KEY_AUDIO_OVERLAY_URI, null)?.let { audioOverlayUri = it.toUri() }
        continuousAudioEnabled = prefs.getBoolean(KEY_CONTINUOUS_AUDIO_ENABLED, false)

        enableCustomArea = prefs.getBoolean(KEY_CUSTOM_AREA_ENABLED, false)
        isCustomAreaDefined = prefs.getBoolean(KEY_CUSTOM_AREA_DEFINED, false)
        customAreaRect = RectF(
            prefs.getInt(KEY_CUSTOM_AREA_LEFT, 0).toFloat(),
            prefs.getInt(KEY_CUSTOM_AREA_TOP, 0).toFloat(),
            prefs.getInt(KEY_CUSTOM_AREA_RIGHT, 0).toFloat(),
            prefs.getInt(KEY_CUSTOM_AREA_BOTTOM, 0).toFloat()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE, ACTION_STOP_VIDEO_LOCK -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START_VIDEO_LOCK -> {
                isVideoLockActive = true
                handler.removeCallbacks(mediaDisplayRunnable)
                removeAllOverlays()
                startForeground(NOTIFICATION_ID, buildNotification())
                handleVideoLockStart(intent)
                return START_STICKY
            }
        }

        isVideoLockActive = false
        var settingsChanged = false
        if (intent?.action == ACTION_START_FROM_BOOT || intent == null || !intent.hasExtra(EXTRA_INTERVAL_EXTRA)) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (prefs.getBoolean(MainActivity.KEY_VIDEO_LOCK_ACTIVE, false)) { stopSelf(); return START_NOT_STICKY }
            loadSettingsFromPreferences(prefs)
            settingsChanged = true
        } else {
            val oldSettings = snapshotCurrentSettings()
            intent.extras?.let { loadSettingsFromIntent(it) }
            if (settingsDiffer(oldSettings)) settingsChanged = true
        }
        if (scaleType != getString(R.string.scale_type_fit_content)) concurrentMediaCount = 1
        if (settingsChanged) { needsCacheRefresh = true; removeAllOverlays() }
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.removeCallbacks(mediaDisplayRunnable)

        if (audioOverlayEnabled && continuousAudioEnabled) {
            startAudioOverlay()
        }

        if (settingsChanged || activeOverlays.isEmpty()) mediaDisplayRunnable.run() else scheduleNextMediaDisplayCycle()
        return START_STICKY
    }

    private fun loadSettingsFromIntent(extras: Bundle) {
        intervalMillis = extras.getLong(EXTRA_INTERVAL_EXTRA, 30000L)
        durationMillis = extras.getLong(EXTRA_DURATION_EXTRA, 5000L)
        folderUris = extras.getStringArrayList(EXTRA_FOLDER_URIS_EXTRA) ?: emptyList()
        showPhotos = extras.getBoolean(EXTRA_SHOW_PHOTOS_EXTRA, true)
        showGifs = extras.getBoolean(EXTRA_SHOW_GIFS_EXTRA, true)
        showVideos = extras.getBoolean(EXTRA_SHOW_VIDEOS_EXTRA, true)
        useRandomTime = extras.getBoolean(EXTRA_RANDOM_TIME_EXTRA, false)
        showCloseButton = extras.getBoolean(EXTRA_SHOW_CLOSE_BUTTON_EXTRA, true)
        scaleType = extras.getString(EXTRA_SCALE_TYPE_EXTRA) ?: getString(R.string.scale_type_fit_content)
        concurrentMediaCount = extras.getInt(EXTRA_CONCURRENT_MEDIA_COUNT_EXTRA, 1)
        enableInitialVolume = extras.getBoolean(EXTRA_ENABLE_INITIAL_VOLUME_EXTRA, false)
        initialVolumePercent = extras.getInt(EXTRA_INITIAL_VOLUME_EXTRA, 50)
        forceVolume = extras.getBoolean(EXTRA_FORCE_VOLUME_EXTRA, false)
        mediaTransparencyPercent = extras.getInt(EXTRA_MEDIA_TRANSPARENCY_EXTRA, 100)
        touchPassthrough = extras.getBoolean(EXTRA_TOUCH_PASSTHROUGH_EXTRA, false)
        forceHardwareDecoding = extras.getBoolean(EXTRA_HARDWARE_DECODING_EXTRA, false)

        audioOverlayEnabled = extras.getBoolean(EXTRA_AUDIO_OVERLAY_ENABLED, false)
        extras.getString(EXTRA_AUDIO_OVERLAY_URI)?.let { audioOverlayUri = it.toUri() }
        continuousAudioEnabled = extras.getBoolean(EXTRA_CONTINUOUS_AUDIO_ENABLED, false)

        enableCustomArea = extras.getBoolean(EXTRA_CUSTOM_AREA_ENABLED, false)
        isCustomAreaDefined = extras.getBoolean(EXTRA_CUSTOM_AREA_DEFINED, false)
        customAreaRect = RectF(
            extras.getInt(EXTRA_CUSTOM_AREA_LEFT, 0).toFloat(),
            extras.getInt(EXTRA_CUSTOM_AREA_TOP, 0).toFloat(),
            extras.getInt(EXTRA_CUSTOM_AREA_RIGHT, 0).toFloat(),
            extras.getInt(EXTRA_CUSTOM_AREA_BOTTOM, 0).toFloat()
        )
    }

    private fun handleVideoLockStart(intent: Intent) {
        intent.getStringExtra(EXTRA_VIDEO_LOCK_URI_EXTRA)?.let {
            loadSettingsFromIntent(intent.extras ?: Bundle.EMPTY)
            if (audioOverlayEnabled) { startAudioOverlay() }
            val mediaItem = MediaItem(it.toUri(), MediaType.VIDEO)
            val (w, h) = getScreenDimensions()
            launchSingleMediaDisplay(RectF(0f, 0f, w.toFloat(), h.toFloat()), mediaItem)
        } ?: stopSelf()
    }

    private fun snapshotCurrentSettings(): Map<String, Any?> = mapOf(
        "uris" to ArrayList(folderUris), "photos" to showPhotos, "gifs" to showGifs, "videos" to showVideos,
        "count" to concurrentMediaCount, "scale" to scaleType, "volEnable" to enableInitialVolume,
        "volPercent" to initialVolumePercent, "volForce" to forceVolume, "interval" to intervalMillis,
        "duration" to durationMillis, "randomTime" to useRandomTime, "closeBtn" to showCloseButton,
        "alpha" to mediaTransparencyPercent, "touch" to touchPassthrough,
        "customAreaEnable" to enableCustomArea, "customAreaDefined" to isCustomAreaDefined, "customAreaRect" to RectF(customAreaRect),
        "hwDecoding" to forceHardwareDecoding
    )

    private fun settingsDiffer(old: Map<String, Any?>): Boolean {
        if (old.isEmpty()) return true
        return old["uris"] != ArrayList(folderUris) || old["photos"] != showPhotos ||
                old["gifs"] != showGifs || old["videos"] != showVideos || old["count"] != concurrentMediaCount ||
                old["scale"] != scaleType || old["volEnable"] != enableInitialVolume || old["volPercent"] != initialVolumePercent ||
                old["volForce"] != forceVolume || old["interval"] != intervalMillis || old["duration"] != durationMillis ||
                old["randomTime"] != useRandomTime || old["closeBtn"] != showCloseButton || old["alpha"] != mediaTransparencyPercent ||
                old["touch"] != touchPassthrough || old["customAreaEnable"] != enableCustomArea || old["customAreaDefined"] != isCustomAreaDefined ||
                old["customAreaRect"] != customAreaRect || old["hwDecoding"] != forceHardwareDecoding
    }

    private fun scheduleNextMediaDisplayCycle() {
        handler.removeCallbacks(mediaDisplayRunnable)
        if (folderUris.isEmpty() && !needsCacheRefresh) return
        val delay = if (useRandomTime && intervalMillis > 0) randomGenerator.nextLong(1, max(2, intervalMillis + 1)) else intervalMillis
        if (delay >= 0) handler.postDelayed(mediaDisplayRunnable, delay)
    }

    private fun getScreenDimensions(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Pair(windowMetrics.bounds.width() - insets.left - insets.right, windowMetrics.bounds.height() - insets.top - insets.bottom)
        } else {
            @Suppress("DEPRECATION")
            DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }.let { Pair(it.widthPixels, it.heightPixels) }
        }
    }

    private fun calculateJigsawPortions(count: Int, bounds: RectF): List<RectF> {
        if (count <= 0 || bounds.width() <= 0 || bounds.height() <= 0) return emptyList()
        if (count == 1) return listOf(bounds)

        val isHorizontalSplit = bounds.width() < bounds.height()
        val count1 = count / 2
        val count2 = count - count1
        val splitRatio = count1.toFloat() / count.toFloat()

        val rect1: RectF
        val rect2: RectF

        if (isHorizontalSplit) {
            val splitY = bounds.top + bounds.height() * splitRatio
            rect1 = RectF(bounds.left, bounds.top, bounds.right, splitY)
            rect2 = RectF(bounds.left, splitY, bounds.right, bounds.bottom)
        } else {
            val splitX = bounds.left + bounds.width() * splitRatio
            rect1 = RectF(bounds.left, bounds.top, splitX, bounds.bottom)
            rect2 = RectF(splitX, bounds.top, bounds.right, bounds.bottom)
        }
        return calculateJigsawPortions(count1, rect1) + calculateJigsawPortions(count2, rect2)
    }

    private fun showRandomMediaCycle() {
        var itemsToDisplayCount = if (scaleType == getString(R.string.scale_type_fit_content)) concurrentMediaCount else 1
        removeAllOverlays()

        val isVideoOnlyMode = showVideos && !showPhotos && !showGifs
        if (isVideoOnlyMode) {
            itemsToDisplayCount = min(itemsToDisplayCount, VIDEO_ONLY_MODE_LIMIT)
        }

        if (itemsToDisplayCount <= 0) { if (serviceScope.isActive) scheduleNextMediaDisplayCycle(); return }

        if (audioOverlayEnabled && !continuousAudioEnabled) {
            startAudioOverlay()
        }

        val canvasRect = if (enableCustomArea && isCustomAreaDefined && customAreaRect.width() > 0 && customAreaRect.height() > 0) {
            Log.d(TAG, "Using custom area for display: $customAreaRect")
            customAreaRect
        } else {
            val (w, h) = getScreenDimensions()
            RectF(0f, 0f, w.toFloat(), h.toFloat())
        }

        serviceScope.launch {
            primeMediaCache()

            val allAvailableFiles = cachedMediaFiles.shuffled(randomGenerator)
            val finalMediaList: List<MediaItem>

            if (isVideoOnlyMode) {
                finalMediaList = allAvailableFiles.filter { it.type == MediaType.VIDEO }.take(itemsToDisplayCount)
            } else {
                val maxVideosAllowed = (itemsToDisplayCount * 0.20).toInt().coerceAtLeast(0)
                val availableVideos = allAvailableFiles.filter { it.type == MediaType.VIDEO }
                val availableNonVideos = allAvailableFiles.filter { it.type != MediaType.VIDEO }

                val videosToDisplay = availableVideos.take(maxVideosAllowed)
                val remainingSlots = itemsToDisplayCount - videosToDisplay.size
                val nonVideosToDisplay = availableNonVideos.take(remainingSlots)

                finalMediaList = (videosToDisplay + nonVideosToDisplay)
            }

            if (finalMediaList.isEmpty()) {
                Log.w(TAG, "No media files found to display.")
                withContext(Dispatchers.Main) {
                    if (serviceScope.isActive) scheduleNextMediaDisplayCycle()
                }
                return@launch
            }

            val portions = calculateJigsawPortions(finalMediaList.size, canvasRect).shuffled(randomGenerator)

            withContext(Dispatchers.Main) {
                finalMediaList.zip(portions).forEach { (mediaItem, portion) ->
                    if (!serviceScope.isActive) return@forEach
                    launchSingleMediaDisplay(portion, mediaItem)
                }
                if (serviceScope.isActive) scheduleNextMediaDisplayCycle()
            }
        }
    }

    private fun launchSingleMediaDisplay(targetPortionRect: RectF, specificMediaItem: MediaItem) {
        serviceScope.launch {
            val overlayId = nextOverlayId++
            val mediaFile = specificMediaItem

            val themedContext: Context = ContextThemeWrapper(this@OverlayService, R.style.Theme_RandomOverlay)
            val mediaBinding = OverlayLayoutBinding.inflate(LayoutInflater.from(themedContext)).apply { btnCloseOverlay.visibility = View.GONE }
            val mediaWmParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE, if (touchPassthrough) (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN) else (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN), PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; alpha = 0.0f; x = -10000; y = -10000 }
            val closeButtonView = if (showCloseButton) ImageButton(themedContext).apply { setImageResource(android.R.drawable.ic_menu_close_clear_cancel); background = ContextCompat.getDrawable(themedContext, R.drawable.close_button_background); contentDescription = "Close Overlay" } else null
            val closeButtonWmParams = if (showCloseButton) WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, mediaWmParams.type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; alpha = 0.0f; x = -10000; y = -10000 } else null
            val activeOverlayEntry = ActiveOverlay(overlayId, mediaBinding, mediaBinding.root, mediaWmParams, closeButtonView, closeButtonWmParams, mediaFile, null)
            synchronized(activeOverlays) { activeOverlays.add(activeOverlayEntry) }
            if (!isActive) { removeSpecificOverlay(activeOverlayEntry); return@launch }

            withContext(Dispatchers.Main) {
                if (!this@launch.isActive) { removeSpecificOverlay(activeOverlayEntry); return@withContext }
                try {
                    windowManager.addView(activeOverlayEntry.mediaView, mediaWmParams)
                    activeOverlayEntry.isMediaViewAdded = true
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding media view", e)
                    removeSpecificOverlay(activeOverlayEntry)
                    return@withContext
                }
                closeButtonView?.let { button ->
                    button.setOnClickListener { removeSpecificOverlay(activeOverlayEntry) }
                    try {
                        windowManager.addView(button, closeButtonWmParams)
                        activeOverlayEntry.isCloseButtonViewAdded = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Error adding close button view", e)
                        removeSpecificOverlay(activeOverlayEntry)
                        return@withContext
                    }
                }

                if (mediaFile.type != MediaType.VIDEO) {
                    mediaBinding.overlayImageView.visibility = View.VISIBLE
                    mediaBinding.overlayVideoView.visibility = View.GONE

                    val requestOptions = if (forceHardwareDecoding) {
                        RequestOptions()
                            .format(DecodeFormat.PREFER_RGB_565)
                            .disallowHardwareConfig()
                    } else {
                        RequestOptions()
                    }

                    Glide.with(this@OverlayService)
                        .load(mediaFile.uri)
                        .apply(requestOptions)
                        .override(targetPortionRect.width().toInt().coerceAtLeast(1), targetPortionRect.height().toInt().coerceAtLeast(1))
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(e: GlideException?, m: Any?, t: Target<Drawable>, i: Boolean): Boolean { removeSpecificOverlay(activeOverlayEntry); return false }
                            override fun onResourceReady(r: Drawable, m: Any, t: Target<Drawable>, d: DataSource, i: Boolean): Boolean {
                                activeOverlayEntry.mediaView.post { if (activeOverlayEntry.mediaView.isAttachedToWindow) updateLayoutAndShow(activeOverlayEntry, targetPortionRect, r.intrinsicWidth, r.intrinsicHeight) else removeSpecificOverlay(activeOverlayEntry) }
                                return false
                            }
                        }).into(mediaBinding.overlayImageView)
                } else {
                    mediaBinding.overlayVideoView.visibility = View.VISIBLE
                    mediaBinding.overlayImageView.visibility = View.GONE
                    mediaBinding.overlayVideoView.apply {
                        setVideoURI(mediaFile.uri)
                        setOnPreparedListener { mp ->
                            if (!activeOverlayEntry.mediaView.isAttachedToWindow) { removeSpecificOverlay(activeOverlayEntry); return@setOnPreparedListener }
                            activeOverlayEntry.mediaView.post { updateLayoutAndShow(activeOverlayEntry, targetPortionRect, mp.videoWidth, mp.videoHeight) }

                            if (audioOverlayEnabled) {
                                mp.setVolume(0f, 0f)
                            } else if (enableInitialVolume) {
                                val maxSysVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val targetSysVolume = (initialVolumePercent / 100.0 * maxSysVolume).toInt().coerceIn(0, maxSysVolume)
                                try {
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetSysVolume, 0)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error setting initial volume for video", e)
                                }
                            }

                            mp.isLooping = !isVideoLockActive
                            mp.start()

                            if (forceVolume && !audioOverlayEnabled) {
                                startVolumeForceLoop()
                            }
                        }
                        setOnCompletionListener {
                            if (isVideoLockActive) {
                                removeSpecificOverlay(activeOverlayEntry)
                                stopVolumeForceLoop()
                            }
                        }
                        setOnErrorListener { _, _, _ -> removeSpecificOverlay(activeOverlayEntry); true }
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged() {
        // This is called from our detector view
        Log.d(TAG, "Configuration change detected. Rearranging media.")

        // Only act if we are in the correct mode and have active overlays
        if (scaleType != getString(R.string.scale_type_fit_content) || activeOverlays.isEmpty()) {
            return
        }

        // Get the list of currently displayed overlays
        val currentOverlays = synchronized(activeOverlays) {
            ArrayList(activeOverlays.filter { it.isMediaViewAdded })
        }
        if (currentOverlays.isEmpty()) return

        // Get the new screen dimensions for the jigsaw layout
        val canvasRect = if (enableCustomArea && isCustomAreaDefined && customAreaRect.width() > 0 && customAreaRect.height() > 0) {
            Log.d(TAG, "Using custom area for display: $customAreaRect")
            customAreaRect
        } else {
            val (w, h) = getScreenDimensions()
            RectF(0f, 0f, w.toFloat(), h.toFloat())
        }

        // Recalculate the jigsaw portions for the new screen size
        val newPortions = calculateJigsawPortions(currentOverlays.size, canvasRect)

        // Pair up each existing overlay with a new screen portion and update its layout
        currentOverlays.zip(newPortions).forEach { (overlay, newPortion) ->
            // We must have the intrinsic dimensions to recalculate the layout correctly
            if (overlay.intrinsicWidth > 0 && overlay.intrinsicHeight > 0) {
                updateLayoutAndShow(overlay, newPortion, overlay.intrinsicWidth, overlay.intrinsicHeight)
            }
        }
    }

    private fun updateLayoutAndShow(activeOverlayEntry: ActiveOverlay, finalCellRect: RectF, intrinsicMediaWidth: Int, intrinsicMediaHeight: Int) {
        val mediaView = activeOverlayEntry.mediaView
        val mediaWmParams = activeOverlayEntry.mediaWmParams
        val closeButtonView = activeOverlayEntry.closeButtonView
        val closeButtonWmParams = activeOverlayEntry.closeButtonWmParams
        if (!mediaView.isAttachedToWindow) { removeSpecificOverlay(activeOverlayEntry); return }

        // Store the original media dimensions so we can reuse them on rotation
        activeOverlayEntry.intrinsicWidth = intrinsicMediaWidth
        activeOverlayEntry.intrinsicHeight = intrinsicMediaHeight

        mediaView.setBackgroundColor(Color.TRANSPARENT)
        val targetView = if (activeOverlayEntry.mediaItem?.type == MediaType.VIDEO) activeOverlayEntry.mediaBinding.overlayVideoView else activeOverlayEntry.mediaBinding.overlayImageView
        val innerViewParams = targetView.layoutParams as FrameLayout.LayoutParams

        if (scaleType == getString(R.string.scale_type_fit_content)) {
            val cellWidth = finalCellRect.width()
            val cellHeight = finalCellRect.height()

            if (cellWidth > 0 && cellHeight > 0) {
                val mediaWidthF = intrinsicMediaWidth.toFloat().coerceAtLeast(1f)
                val mediaHeightF = intrinsicMediaHeight.toFloat().coerceAtLeast(1f)

                val widthRatio = cellWidth / mediaWidthF
                val heightRatio = cellHeight / mediaHeightF
                val bestRatio = min(widthRatio, heightRatio)

                val targetWidth = (mediaWidthF * bestRatio).toInt()
                val targetHeight = (mediaHeightF * bestRatio).toInt()

                mediaWmParams.width = targetWidth.coerceAtLeast(50)
                mediaWmParams.height = targetHeight.coerceAtLeast(50)

                mediaWmParams.x = (finalCellRect.left + (cellWidth - targetWidth) / 2f).toInt()
                mediaWmParams.y = (finalCellRect.top + (cellHeight - targetHeight) / 2f).toInt()

                innerViewParams.width = FrameLayout.LayoutParams.MATCH_PARENT
                innerViewParams.height = FrameLayout.LayoutParams.MATCH_PARENT
                if(targetView is ImageView) targetView.scaleType = ImageView.ScaleType.FIT_CENTER

            } else {
                mediaWmParams.width = 0; mediaWmParams.height = 0
            }

        } else { // Fullscreen modes
            mediaWmParams.x = 0; mediaWmParams.y = 0
            mediaWmParams.width = WindowManager.LayoutParams.MATCH_PARENT; mediaWmParams.height = WindowManager.LayoutParams.MATCH_PARENT
            innerViewParams.width = FrameLayout.LayoutParams.MATCH_PARENT
            innerViewParams.height = FrameLayout.LayoutParams.MATCH_PARENT
            if(targetView is ImageView){
                when (scaleType) {
                    getString(R.string.scale_type_fill_screen) -> targetView.scaleType = ImageView.ScaleType.FIT_CENTER
                    getString(R.string.scale_type_fill_stretch) -> targetView.scaleType = ImageView.ScaleType.FIT_XY
                    getString(R.string.scale_type_fill_borders) -> { targetView.scaleType = ImageView.ScaleType.FIT_CENTER; mediaView.setBackgroundColor(Color.BLACK) }
                    getString(R.string.scale_type_fill_crop) -> targetView.scaleType = ImageView.ScaleType.CENTER_CROP
                }
            }
        }
        targetView.layoutParams = innerViewParams
        mediaWmParams.alpha = mediaTransparencyPercent / 100.0f
        try { windowManager.updateViewLayout(mediaView, mediaWmParams) } catch (_: Exception) { removeSpecificOverlay(activeOverlayEntry); return }
        closeButtonView?.let { button ->
            closeButtonWmParams?.let { params ->
                val (screenWidth, _) = getScreenDimensions()
                val mediaDisplayWidth = if(mediaWmParams.width == WindowManager.LayoutParams.MATCH_PARENT) screenWidth else mediaWmParams.width
                params.x = (mediaWmParams.x + mediaDisplayWidth - button.width).coerceIn(0, screenWidth - button.width)
                params.y = mediaWmParams.y.coerceAtLeast(0)
                params.alpha = 1.0f
                try { windowManager.updateViewLayout(button, params) } catch (_: Exception) {}
            }
        }
        if (serviceScope.isActive && durationMillis > 0 && !isVideoLockActive) {
            activeOverlayEntry.hideJob?.cancel()
            activeOverlayEntry.hideJob = serviceScope.launch {
                delay(durationMillis)
                if (isActive) { withContext(Dispatchers.Main) { removeSpecificOverlay(activeOverlayEntry) } }
            }
        }
    }

    private fun startAudioOverlay() {
        if (!audioOverlayEnabled || audioOverlayUri == null || isAudioOverlayPlaying || isAudioOverlayStarting) return

        isAudioOverlayStarting = true

        try {
            audioOverlayPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build())
                setDataSource(applicationContext, audioOverlayUri!!)
                isLooping = true
                setOnPreparedListener {
                    if (enableInitialVolume) {
                        val maxSysVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val targetSysVolume = (initialVolumePercent / 100.0 * maxSysVolume).toInt().coerceIn(0, maxSysVolume)
                        try {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetSysVolume, 0)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting initial audio overlay volume", e)
                        }
                    }
                    start()
                    isAudioOverlayPlaying = true
                    isAudioOverlayStarting = false

                    if (forceVolume) {
                        startVolumeForceLoop()
                    }
                }
                prepareAsync()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to prepare audio overlay player", e)
            audioOverlayPlayer = null
            isAudioOverlayStarting = false
        }
    }

    private fun stopAudioOverlay() {
        if (!isAudioOverlayPlaying && !isAudioOverlayStarting) return
        audioOverlayPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        audioOverlayPlayer = null
        isAudioOverlayPlaying = false
        isAudioOverlayStarting = false
    }

    private fun startVolumeForceLoop() {
        if (activeVolumeForceRunnable != null) return
        Log.d(TAG, "Starting the MASTER volume force loop.")

        val runnable = object : Runnable {
            override fun run() {
                try {
                    val maxSysVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetSysVolume = (initialVolumePercent / 100.0 * maxSysVolume).toInt().coerceIn(0, maxSysVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetSysVolume, 0)
                    handler.postDelayed(this, 250)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in force volume loop, stopping.", e)
                    stopVolumeForceLoop()
                }
            }
        }
        activeVolumeForceRunnable = runnable
        handler.post(runnable)
    }

    private fun stopVolumeForceLoop() {
        activeVolumeForceRunnable?.let { handler.removeCallbacks(it) }
        activeVolumeForceRunnable = null
    }

    private fun removeSpecificOverlay(overlayToRemove: ActiveOverlay) {
        overlayToRemove.hideJob?.cancel()
        if (overlayToRemove.isMediaViewAdded && overlayToRemove.mediaView.isAttachedToWindow) {
            try { (overlayToRemove.mediaBinding.overlayVideoView as VideoView?)?.stopPlayback(); windowManager.removeView(overlayToRemove.mediaView) } catch (e: Exception) { Log.e(TAG, "Error removing media view", e) }
        }
        overlayToRemove.closeButtonView?.let { if (overlayToRemove.isCloseButtonViewAdded && it.isAttachedToWindow) try { windowManager.removeView(it) } catch (e: Exception) { Log.e(TAG, "Error removing close button", e) } }
        synchronized(activeOverlays) { activeOverlays.remove(overlayToRemove) }

        if (activeOverlays.isEmpty()) {
            if (!continuousAudioEnabled) {
                stopAudioOverlay()
            }
            stopVolumeForceLoop()
        }
        if (isVideoLockActive && activeOverlays.isEmpty()) stopSelf()
    }

    private fun removeAllOverlays() {
        stopVolumeForceLoop()
        if (!continuousAudioEnabled) {
            stopAudioOverlay()
        }
        synchronized(activeOverlays) { ArrayList(activeOverlays).forEach { removeSpecificOverlay(it) } }
    }

    private fun primeMediaCache() {
        val currentTime = System.currentTimeMillis()
        if (needsCacheRefresh || cachedMediaFiles.isEmpty() || (cacheRefreshIntervalMs > 0 && currentTime - lastCacheUpdateTime > cacheRefreshIntervalMs)) {
            synchronized(cacheUpdateLock) {
                if (needsCacheRefresh || cachedMediaFiles.isEmpty() || (cacheRefreshIntervalMs > 0 && currentTime - lastCacheUpdateTime > cacheRefreshIntervalMs)) {
                    val allowedExtensions = mutableSetOf<String>().apply {
                        if (showPhotos) addAll(listOf("jpg", "jpeg", "png", "bmp", "webp"))
                        if (showGifs) add("gif")
                        if (showVideos) addAll(listOf("mp4", "3gp", "mkv", "webm"))
                    }
                    val allFiles = mutableListOf<MediaItem>()
                    if (allowedExtensions.isNotEmpty() && folderUris.isNotEmpty()) {
                        folderUris.forEach { uriString ->
                            try {
                                DocumentFile.fromTreeUri(this, uriString.toUri())?.listFiles()?.forEach { file ->
                                    file.name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in allowedExtensions }?.let { ext ->
                                        val type = if (ext == "gif") MediaType.GIF else if (ext in listOf("jpg", "jpeg", "png", "bmp", "webp")) MediaType.PHOTO else MediaType.VIDEO
                                        allFiles.add(MediaItem(file.uri, type))
                                    }
                                }
                            } catch (_: Exception) { Log.e(TAG, "Error scanning folder" ) }
                        }
                    }
                    cachedMediaFiles = allFiles
                    lastCacheUpdateTime = System.currentTimeMillis()
                    needsCacheRefresh = false
                    Log.d(TAG, "Media cache refreshed. Found ${cachedMediaFiles.size} items.")
                }
            }
        }
    }

    override fun onDestroy() {
        configurationChangeDetector?.let {
            if (it.isAttachedToWindow) {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing ConfigurationChangeDetector", e)
                }
            }
        }
        unregisterReceiver(settingsUpdateReceiver)
        if (isVideoLockActive) {
            val intent = Intent(ACTION_VIDEO_LOCK_FINISHED).apply { setPackage(this@OverlayService.packageName) }
            sendBroadcast(intent)
        }
        handler.removeCallbacksAndMessages(null)
        stopVolumeForceLoop()
        stopAudioOverlay()
        serviceJob.cancel()
        removeAllOverlays()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit(commit = true) {
            putBoolean(MainActivity.KEY_OVERLAY_ACTIVE, false)
            putBoolean(MainActivity.KEY_VIDEO_LOCK_ACTIVE, false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notification_channel_description)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pIntentFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pIntentFlags)
        val stopIntent = Intent(this, OverlayService::class.java).apply { action = if (isVideoLockActive) ACTION_STOP_VIDEO_LOCK else ACTION_STOP_SERVICE }
        val pStopFlags = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pStopSelf = PendingIntent.getService(this, 0, stopIntent, pStopFlags)
        val contentText = if (isVideoLockActive) getString(R.string.video_lock_service_notification_text) else getString(R.string.overlay_service_notification_text)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_service_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop_placeholder, "Stop Service", pStopSelf)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private data class MediaItem(val uri: Uri, val type: MediaType)
    private enum class MediaType { PHOTO, GIF, VIDEO }
}