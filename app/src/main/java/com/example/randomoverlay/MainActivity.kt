package com.example.randomoverlay

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.example.randomoverlay.databinding.ActivityMainBinding
import com.google.android.material.slider.Slider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale
import java.util.concurrent.TimeUnit

private fun hideSystemUI(window: Window) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN)
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private val gson = Gson()

    private val selectedFolderUris = mutableListOf<String>()
    private var lockCountDownTimer: CountDownTimer? = null

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponentName: ComponentName

    private enum class PermissionRequestType { REGULAR_OVERLAY, AREA_SELECTION, VIDEO_LOCK }
    private var pendingPermissionRequest: PermissionRequestType? = null

    private val videoLockStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OverlayService.ACTION_VIDEO_LOCK_FINISHED) {
                Log.d(TAG, "Received broadcast to update UI from video lock completion.")
                updateAllUiStates()
            }
        }
    }


    private val secondsMultiplier = 1L
    private val minutesMultiplier = 60 * secondsMultiplier
    private val hoursMultiplier = 60 * minutesMultiplier
    private val daysMultiplier = 24 * hoursMultiplier

    companion object {
        private const val TAG = "MainActivity"
        private const val FEEDBACK_FORM_URL = "https://docs.google.com/forms/d/e/1FAIpQLScDqbXuKvdYvKatx1Vj6apXVcD1MAFQzAWXS_IKzAbZb0ayFw/viewform?usp=dialog"
        private const val PREFS_NAME = "RandomOverlayPrefs"
        private const val KEY_INTERVAL_VALUE = "interval_value"
        private const val KEY_INTERVAL_UNIT_INDEX = "interval_unit_index"
        private const val KEY_DURATION = "duration"
        private const val KEY_SELECTED_FOLDERS = "selected_folders"
        private const val KEY_PHOTOS_ENABLED = "photos_enabled"
        private const val KEY_GIFS_ENABLED = "gifs_enabled"
        private const val KEY_VIDEOS_ENABLED = "videos_enabled"
        private const val KEY_RANDOM_TIME_ENABLED = "random_time_enabled"
        const val KEY_OVERLAY_ACTIVE = "overlay_active"
        const val KEY_VIDEO_LOCK_ACTIVE = "video_lock_active"
        private const val KEY_SHOW_CLOSE_BUTTON = "show_close_button"
        private const val KEY_SCALE_TYPE = "scale_type"
        private const val KEY_CONCURRENT_MEDIA_COUNT = "concurrent_media_count"
        private const val KEY_ENABLE_INITIAL_VOLUME = "enable_initial_volume"
        private const val KEY_INITIAL_VOLUME = "initial_volume"
        private const val KEY_FORCE_VOLUME = "force_volume"
        private const val KEY_MEDIA_TRANSPARENCY = "media_transparency"
        private const val KEY_TOUCH_PASSTHROUGH = "touch_passthrough"
        private const val KEY_HARDWARE_DECODING = "hardware_decoding"
        private const val KEY_TIMER_LOCK_ENABLED = "timer_lock_enabled"
        private const val KEY_LOCK_DURATION_VALUE = "lock_duration_value"
        private const val KEY_LOCK_DURATION_UNIT_INDEX = "lock_duration_unit_index"
        const val KEY_LOCK_END_TIME_MILLIS = "lock_end_time_millis"
        private const val KEY_DEVICE_ADMIN_REQUESTED_STATE = "device_admin_requested_active"
        const val KEY_CUSTOM_AREA_ENABLED = "custom_area_enabled"
        const val KEY_CUSTOM_AREA_LEFT = "custom_area_left"
        const val KEY_CUSTOM_AREA_TOP = "custom_area_top"
        const val KEY_CUSTOM_AREA_RIGHT = "custom_area_right"
        const val KEY_CUSTOM_AREA_BOTTOM = "custom_area_bottom"
        const val KEY_CUSTOM_AREA_DEFINED = "custom_area_defined"
        const val REQUEST_CODE_STORAGE_PERMISSION = 102
        const val REQUEST_CODE_NOTIFICATIONS_PERMISSION = 103
        private const val MAX_CONCURRENT_MEDIA = 50
        private const val MAX_CONCURRENT_VIDEOS = 10
        private const val UNIT_SECONDS = 0
        private const val UNIT_MINUTES = 1
        private const val UNIT_HOURS = 2
        private const val UNIT_DAYS = 3

        private const val KEY_AUDIO_OVERLAY_ENABLED = "audio_overlay_enabled"
        private const val KEY_AUDIO_OVERLAY_URI = "audio_overlay_uri"
        private const val KEY_CONTINUOUS_AUDIO_ENABLED = "continuous_audio_enabled"
    }

    private val currentMaxConcurrentLimit: Int
        get() {
            val isVideoOnly = binding.cbVideos.isChecked && !binding.cbPhotos.isChecked && !binding.cbGifs.isChecked
            return if (isVideoOnly) MAX_CONCURRENT_VIDEOS else MAX_CONCURRENT_MEDIA
        }

    private val deviceAdminResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == RESULT_OK) {
                Toast.makeText(this, R.string.device_admin_activated, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.device_admin_activation_failed, Toast.LENGTH_SHORT).show()
            }
            updateDeviceAdminSwitchState()
            sharedPreferences.edit { putBoolean(KEY_DEVICE_ADMIN_REQUESTED_STATE, isDeviceAdminActive()) }
        }

    private val openDirectoryLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
                try {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val uriString = it.toString()
                    if (!selectedFolderUris.contains(uriString)) {
                        selectedFolderUris.add(uriString)
                        updateSelectedFoldersTextView()
                        savePreferences()
                    } else {
                        Toast.makeText(this, "Folder already selected", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: SecurityException) {
                    Toast.makeText(this, "Failed to get permission for folder", Toast.LENGTH_LONG).show()
                }
            }
        }

    private val selectVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startVideoLockService(it)
            } ?: run { Toast.makeText(this, R.string.video_lock_no_video_selected, Toast.LENGTH_SHORT).show() }
        }

    private val selectAudioLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                sharedPreferences.edit {
                    putString(KEY_AUDIO_OVERLAY_URI, it.toString())
                }
                updateAudioOverlayUi()
            }
        }

    private val drawOverlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            val requestType = pendingPermissionRequest
            pendingPermissionRequest = null
            if (Settings.canDrawOverlays(this)) {
                when (requestType) {
                    PermissionRequestType.AREA_SELECTION -> startAreaSelectionService()
                    PermissionRequestType.VIDEO_LOCK -> launchVideoPicker()
                    PermissionRequestType.REGULAR_OVERLAY -> checkAndRequestNextPermission()
                    null -> {}
                }
            } else {
                Toast.makeText(this, "Overlay permission is required.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI(window)
        setSupportActionBar(binding.toolbar)
        // We set the title in the XML layout via app:title, so this is no longer needed.
        // supportActionBar?.title = getString(R.string.random_overlay_settings)

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        if (savedInstanceState == null && sharedPreferences.getBoolean(KEY_VIDEO_LOCK_ACTIVE, false)) {
            Log.i(TAG, "Stale Video Lock flag found on fresh app start. Cleaning up.")
            sharedPreferences.edit(commit = true) {
                putBoolean(KEY_VIDEO_LOCK_ACTIVE, false)
                putBoolean(KEY_OVERLAY_ACTIVE, false)
            }
        }

        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponentName = ComponentName(this, MyDeviceAdminReceiver::class.java)
        setupAllControls()
        loadPreferences()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_help -> {
                startActivity(Intent(this, HelpActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupAllControls() {
        setupClickListeners()
        setupSpinners()
        setupInputValidators()
        setupSwitchesAndSliders()
    }

    private fun setupClickListeners() {
        binding.btnFeedback.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, FEEDBACK_FORM_URL.toUri())
            try {
                startActivity(intent)
            } catch (_: Exception) {
                // This will catch if the user has no web browser installed
                Toast.makeText(this, "Could not open feedback form.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSelectFolders.setOnClickListener { openDirectoryLauncher.launch(null) }
        binding.btnClearFolders.setOnClickListener {
            selectedFolderUris.clear()
            sharedPreferences.edit { putString(KEY_SELECTED_FOLDERS, gson.toJson(selectedFolderUris)) }
            updateSelectedFoldersTextView()
            Toast.makeText(this, getString(R.string.folders_cleared), Toast.LENGTH_SHORT).show()
        }
        binding.btnStartStop.setOnClickListener {
            if (isOverlayServiceRunning() && !isVideLockModeActive()) {
                stopOverlayService()
            } else if (!isVideLockModeActive()){
                savePreferences()
                checkAndRequestOverlayPermission(PermissionRequestType.REGULAR_OVERLAY)
            }
        }
        binding.btnVideoLock.setOnClickListener {
            checkAndRequestOverlayPermission(PermissionRequestType.VIDEO_LOCK)
        }
        binding.btnDefineCustomArea.setOnClickListener {
            checkAndRequestOverlayPermission(PermissionRequestType.AREA_SELECTION)
        }
        binding.tvVolumeValue.setOnClickListener {
            showVolumeInputDialog()
        }
        binding.btnSelectAudioFile.setOnClickListener {
            selectAudioLauncher.launch(arrayOf("audio/*", "video/*"))
        }
        binding.tvTransparencyValue.setOnClickListener {
            showTransparencyInputDialog()
        }
    }

    private fun setupSpinners() {
        ArrayAdapter.createFromResource(this, R.array.time_units_array, android.R.layout.simple_spinner_item).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerIntervalUnits.adapter = adapter
            binding.spinnerLockDurationUnits.adapter = adapter
        }
        ArrayAdapter.createFromResource(this, R.array.scale_type_options, android.R.layout.simple_spinner_item).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerScaleType.adapter = adapter
        }
        binding.spinnerScaleType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateConcurrentMediaSettingVisibility()
                updateCustomAreaControlsVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupInputValidators() {
        binding.etConcurrentMediaCount.addTextChangedListener(object : TextWatcher {
            private var selfChange = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (selfChange) return
                s?.toString()?.toIntOrNull()?.let { countValue ->
                    selfChange = true
                    val validValue = countValue.coerceIn(1, currentMaxConcurrentLimit)
                    if (validValue != countValue) {
                        binding.etConcurrentMediaCount.setText(validValue.toString())
                        binding.etConcurrentMediaCount.setSelection(binding.etConcurrentMediaCount.text.length)
                    }
                    selfChange = false
                }
            }
        })
    }

    private fun setupSwitchesAndSliders() {
        val mediaTypeCheckListener = CompoundButton.OnCheckedChangeListener { _, _ ->
            enforceVideoCountLimit()
        }
        binding.cbPhotos.setOnCheckedChangeListener(mediaTypeCheckListener)
        binding.cbGifs.setOnCheckedChangeListener(mediaTypeCheckListener)
        binding.cbVideos.setOnCheckedChangeListener(mediaTypeCheckListener)

        binding.switchEnableInitialVolume.setOnCheckedChangeListener { _, isChecked -> updateVideoVolumeControlsVisibility(isChecked) }
        binding.switchTimerLock.setOnCheckedChangeListener { _, isChecked -> updateTimerLockControlsVisibility(isChecked) }
        binding.switchCustomArea.setOnCheckedChangeListener { _, isChecked -> updateCustomAreaControlsVisibility() }
        binding.switchDeviceAdmin.setOnCheckedChangeListener { _, isChecked ->
            val currentlyAdmin = isDeviceAdminActive()
            if (isChecked && !currentlyAdmin) {
                requestDeviceAdminActivation()
            } else if (!isChecked && currentlyAdmin) {
                if (isTimerLockActuallyActive()) {
                    Toast.makeText(this, R.string.device_admin_deactivation_while_locked_message, Toast.LENGTH_LONG).show()
                    binding.switchDeviceAdmin.isChecked = true
                } else {
                    Toast.makeText(this, "To deactivate, please go to device Settings > Security > Device admin apps.", Toast.LENGTH_LONG).show()
                    binding.switchDeviceAdmin.isChecked = true
                }
            }
        }
        binding.sliderInitialVolume.addOnSliderTouchListener(object: Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                binding.tvVolumeValue.text = String.format(Locale.US, getString(R.string.volume_value_format), slider.value.toInt())
                savePreferences()
            }
        })
        binding.sliderTransparency.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                binding.tvTransparencyValue.text = String.format(Locale.US, getString(R.string.transparency_value_format), slider.value.toInt())
                savePreferences()
                if (isOverlayServiceRunning()) {
                    val intent = Intent(OverlayService.ACTION_UPDATE_SETTINGS)
                    intent.setPackage(this@MainActivity.packageName)
                    sendBroadcast(intent)
                }
            }
        })
        binding.sliderTransparency.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.tvTransparencyValue.text = String.format(Locale.US, getString(R.string.transparency_value_format), value.toInt())
            }
        }
        binding.switchAudioOverlay.setOnCheckedChangeListener { _, isChecked ->
            updateAudioOverlayUi()
            savePreferences()
        }
        binding.switchContinuousAudio.setOnCheckedChangeListener { _, _ ->
            savePreferences()
        }
    }

    private fun enforceVideoCountLimit() {
        val maxLimit = currentMaxConcurrentLimit
        val currentCount = binding.etConcurrentMediaCount.text.toString().toIntOrNull() ?: 1

        if (currentCount > maxLimit) {
            binding.etConcurrentMediaCount.setText(maxLimit.toString())
            if (maxLimit == MAX_CONCURRENT_VIDEOS) {
                Toast.makeText(this, "Concurrent media limited to $MAX_CONCURRENT_VIDEOS for video-only mode.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showVolumeInputDialog() {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(binding.sliderInitialVolume.value.toInt().toString())
        }
        AlertDialog.Builder(this)
            .setTitle("Set Volume (0-100)")
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newValue = editText.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: binding.sliderInitialVolume.value.toInt()
                binding.sliderInitialVolume.value = newValue.toFloat()
                binding.tvVolumeValue.text = String.format(Locale.US, getString(R.string.volume_value_format), newValue)
                savePreferences()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTransparencyInputDialog() {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(binding.sliderTransparency.value.toInt().toString())
        }
        AlertDialog.Builder(this)
            .setTitle("Set Transparency (0-100)")
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newValue = editText.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: binding.sliderTransparency.value.toInt()

                // Update the UI
                binding.sliderTransparency.value = newValue.toFloat()
                binding.tvTransparencyValue.text = String.format(Locale.US, getString(R.string.transparency_value_format), newValue)

                // Save the preference
                savePreferences()

                // If the service is running, broadcast an update to apply the change live
                if (isOverlayServiceRunning()) {
                    val intent = Intent(OverlayService.ACTION_UPDATE_SETTINGS)
                    intent.setPackage(this@MainActivity.packageName)
                    sendBroadcast(intent)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateAllUiStates() {
        val isVideoLock = isVideLockModeActive()

        // --- Main UI Enable/Disable ---
        // Disable regular settings during Video Lock
        binding.groupRegularSettings.referencedIds.forEach { id ->
            findViewById<View>(id).isEnabled = !isVideoLock
            findViewById<View>(id).alpha = if (isVideoLock) 0.5f else 1.0f
        }
        // Also disable settings not in the group
        binding.switchShowCloseButton.isEnabled = !isVideoLock
        binding.spinnerScaleType.isEnabled = !isVideoLock
        binding.tvScaleTypeLabel.isEnabled = !isVideoLock
        binding.switchEnableInitialVolume.isEnabled = !isVideoLock
        binding.tvVideoVolumeOptionsLabel.isEnabled = !isVideoLock
        binding.sliderTransparency.isEnabled = !isVideoLock
        binding.tvTransparencyLabel.isEnabled = !isVideoLock
        binding.switchTouchPassthrough.isEnabled = !isVideoLock
        binding.switchHardwareDecoding.isEnabled = !isVideoLock
        binding.tvAudioOverlayTitle.isEnabled = !isVideoLock
        binding.switchAudioOverlay.isEnabled = !isVideoLock
        binding.switchDeviceAdmin.isEnabled = !isVideoLock
        binding.tvDeviceAdminExplanation.isEnabled = !isVideoLock

        // Handle buttons
        binding.btnVideoLock.isEnabled = !isVideoLock
        updateStartStopButtonText() // This handles the Start/Stop button state

        // --- Visibility Updates for All Dependent Controls ---
        // These calls ensure the UI state is correct on app launch and on state changes.
        updateVideoVolumeControlsVisibility(binding.switchEnableInitialVolume.isChecked)
        updateConcurrentMediaSettingVisibility()
        updateTimerLockControlsVisibility(binding.switchTimerLock.isChecked)
        updateCustomAreaControlsVisibility()
        updateAudioOverlayUi()

        // --- Informational UI Updates ---
        updateDeviceAdminSwitchState()
        updateCustomAreaInfoDisplay()
    }

    private fun updateAudioOverlayUi() {
        val isEnabled = binding.switchAudioOverlay.isChecked
        binding.btnSelectAudioFile.isVisible = isEnabled
        binding.tvSelectedAudioFile.isVisible = isEnabled
        binding.switchContinuousAudio.isVisible = isEnabled
        if (isEnabled) {
            val uriString = sharedPreferences.getString(KEY_AUDIO_OVERLAY_URI, null)
            if (uriString != null) {
                val fileName = getFileName(uriString.toUri())
                binding.tvSelectedAudioFile.text = getString(R.string.selected_audio_file_info, fileName)
            } else {
                binding.tvSelectedAudioFile.text = getString(R.string.no_audio_file_selected)
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val colIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if(colIndex >= 0) result = cursor.getString(colIndex)
                }
            } finally {
                cursor?.close()
            }
        }
        return result ?: uri.path?.substringAfterLast('/') ?: "Unknown File"
    }

    private fun updateStartStopButtonText() {
        val lockEndTime = sharedPreferences.getLong(KEY_LOCK_END_TIME_MILLIS, 0L)
        lockCountDownTimer?.cancel()
        if (isVideLockModeActive()) {
            binding.btnStartStop.text = getString(R.string.start_overlay)
            binding.btnStartStop.isEnabled = false
            return
        }
        if (isOverlayServiceRunning() && binding.switchTimerLock.isChecked && lockEndTime > System.currentTimeMillis()) {
            binding.btnStartStop.isEnabled = false
            val remainingTime = lockEndTime - System.currentTimeMillis()
            lockCountDownTimer = object : CountDownTimer(remainingTime, 1000) {
                override fun onTick(millisUntilFinished: Long) { binding.btnStartStop.text = getString(R.string.unlocks_in_format, formatMillisToHMS(millisUntilFinished)) }
                override fun onFinish() {
                    binding.btnStartStop.text = getString(R.string.stop_overlay)
                    binding.btnStartStop.isEnabled = true
                    sharedPreferences.edit { remove(KEY_LOCK_END_TIME_MILLIS) }
                }
            }.start()
        } else if (isOverlayServiceRunning()) {
            binding.btnStartStop.text = getString(R.string.stop_overlay)
            binding.btnStartStop.isEnabled = true
            if (lockEndTime > 0) sharedPreferences.edit { remove(KEY_LOCK_END_TIME_MILLIS) }
        } else {
            binding.btnStartStop.text = getString(R.string.start_overlay)
            binding.btnStartStop.isEnabled = true
            if (lockEndTime > 0) sharedPreferences.edit { remove(KEY_LOCK_END_TIME_MILLIS) }
        }
    }

    private fun updateVideoVolumeControlsVisibility(isVolumeOptionEnabled: Boolean) {
        binding.tvInitialVolumeLabel.isVisible = isVolumeOptionEnabled
        binding.sliderInitialVolume.isVisible = isVolumeOptionEnabled
        binding.tvVolumeValue.isVisible = isVolumeOptionEnabled
        binding.switchForceVolume.isVisible = isVolumeOptionEnabled
    }
    private fun updateConcurrentMediaSettingVisibility() {
        val isFitContentSelected = binding.spinnerScaleType.selectedItemPosition == 0
        binding.tvConcurrentMediaLabel.isVisible = isFitContentSelected
        binding.etConcurrentMediaCount.isVisible = isFitContentSelected
    }
    private fun updateTimerLockControlsVisibility(isTimerLockEnabled: Boolean) {
        binding.tvLockDurationLabel.isVisible = isTimerLockEnabled
        binding.llLockDurationContainer.isVisible = isTimerLockEnabled
    }
    private fun updateCustomAreaControlsVisibility() {
        val useCustomArea = binding.switchCustomArea.isChecked
        val isFitContentMode = binding.spinnerScaleType.selectedItemPosition == 0

        binding.btnDefineCustomArea.isVisible = useCustomArea && isFitContentMode
        binding.tvCustomAreaInfo.isVisible = useCustomArea && isFitContentMode

        if (!isFitContentMode && useCustomArea) {
            binding.switchCustomArea.isChecked = false
            Toast.makeText(this, "Custom Area can only be used with 'Fit to Content' display mode.", Toast.LENGTH_LONG).show()
        }
    }
    private fun updateDeviceAdminSwitchState() {
        binding.switchDeviceAdmin.isChecked = isDeviceAdminActive()
    }
    private fun updateSelectedFoldersTextView() {
        if (selectedFolderUris.isEmpty()) {
            binding.tvSelectedFolders.text = getString(R.string.no_folders_selected)
        } else {
            val paths = selectedFolderUris.joinToString("\n") { it.toUri().lastPathSegment?.let { s -> Uri.decode(s) } ?: it }
            binding.tvSelectedFolders.text = if (selectedFolderUris.size > 1) getString(R.string.selected_folders_info_multiple, selectedFolderUris.size, paths) else getString(R.string.selected_folders_info_single, paths)
        }
    }
    private fun updateCustomAreaInfoDisplay() {
        if (sharedPreferences.getBoolean(KEY_CUSTOM_AREA_DEFINED, false)) {
            val left = sharedPreferences.getInt(KEY_CUSTOM_AREA_LEFT, 0)
            val top = sharedPreferences.getInt(KEY_CUSTOM_AREA_TOP, 0)
            val right = sharedPreferences.getInt(KEY_CUSTOM_AREA_RIGHT, 0)
            val bottom = sharedPreferences.getInt(KEY_CUSTOM_AREA_BOTTOM, 0)
            binding.tvCustomAreaInfo.text = getString(R.string.custom_area_defined_format, left, top, right, bottom)
        } else {
            binding.tvCustomAreaInfo.text = getString(R.string.custom_area_not_defined)
        }
    }

    private fun getTotalIntervalSecondsFromUi(): Long {
        val value = binding.etIntervalValue.text.toString().toLongOrNull() ?: 30L
        return convertToSeconds(value, binding.spinnerIntervalUnits.selectedItemPosition)
    }
    private fun getTotalLockDurationSecondsFromUi(): Long {
        val value = binding.etLockDurationValue.text.toString().toLongOrNull() ?: 10L
        return convertToSeconds(value, binding.spinnerLockDurationUnits.selectedItemPosition)
    }
    private fun convertToSeconds(value: Long, unitIndex: Int): Long {
        return when (unitIndex) {
            UNIT_MINUTES -> value * minutesMultiplier; UNIT_HOURS -> value * hoursMultiplier
            UNIT_DAYS -> value * daysMultiplier; else -> value * secondsMultiplier
        }
    }
    private fun formatMillisToHMS(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return when {
            hours > 0 -> String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            minutes > 0 -> String.format(Locale.US, "%02d:%02d", minutes, seconds)
            else -> String.format(Locale.US, "%2ds", seconds)
        }
    }

    private fun checkAndRequestOverlayPermission(requestType: PermissionRequestType) {
        pendingPermissionRequest = requestType
        if (!Settings.canDrawOverlays(this)) {
            drawOverlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
        } else {
            when (requestType) {
                PermissionRequestType.AREA_SELECTION -> startAreaSelectionService()
                PermissionRequestType.VIDEO_LOCK -> launchVideoPicker()
                PermissionRequestType.REGULAR_OVERLAY -> checkAndRequestStoragePermissions()
            }
        }
    }
    private fun checkAndRequestStoragePermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), REQUEST_CODE_STORAGE_PERMISSION)
        } else { checkAndRequestNotificationsPermission() }
    }
    private fun checkAndRequestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_NOTIFICATIONS_PERMISSION)
        } else { startOverlayServiceWithCollectedSettings() }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_STORAGE_PERMISSION -> if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) checkAndRequestNextPermission() else Toast.makeText(this, "Storage permission is required.", Toast.LENGTH_LONG).show()
            REQUEST_CODE_NOTIFICATIONS_PERMISSION -> checkAndRequestNextPermission()
        }
    }
    private fun checkAndRequestNextPermission() {
        if (!Settings.canDrawOverlays(this)) { checkAndRequestOverlayPermission(PermissionRequestType.REGULAR_OVERLAY); return }
        val storagePermGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) else (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
        if (!storagePermGranted) { checkAndRequestStoragePermissions(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { checkAndRequestNotificationsPermission(); return }
        startOverlayServiceWithCollectedSettings()
    }

    private fun isDeviceAdminActive(): Boolean = devicePolicyManager.isAdminActive(adminComponentName)
    private fun requestDeviceAdminActivation() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponentName)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.device_admin_activation_request_explanation))
        }
        try { deviceAdminResultLauncher.launch(intent) } catch (_: Exception) { updateDeviceAdminSwitchState() }
    }
    private fun isTimerLockActuallyActive(): Boolean = binding.switchTimerLock.isChecked && sharedPreferences.getLong(KEY_LOCK_END_TIME_MILLIS, 0L) > System.currentTimeMillis() && isOverlayServiceRunning()

    private fun startAreaSelectionService() {
        if (!Settings.canDrawOverlays(this)) { checkAndRequestOverlayPermission(PermissionRequestType.AREA_SELECTION); return }
        try { ContextCompat.startForegroundService(this, Intent(this, AreaSelectionService::class.java)) } catch (_: Exception) { Toast.makeText(this, "Could not start area selection mode.", Toast.LENGTH_SHORT).show() }
    }
    private fun launchVideoPicker() = selectVideoLauncher.launch(arrayOf("video/*"))

    private fun startVideoLockService(videoUri: Uri) {
        savePreferences()
        val scaleTypeIdentifier = resources.getStringArray(R.array.scale_type_options_internal)[binding.spinnerScaleType.selectedItemPosition]
        sharedPreferences.edit { putBoolean(KEY_OVERLAY_ACTIVE, true); putBoolean(KEY_VIDEO_LOCK_ACTIVE, true) }
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START_VIDEO_LOCK
            putExtra(OverlayService.EXTRA_VIDEO_LOCK_URI_EXTRA, videoUri.toString())
            putExtra(OverlayService.EXTRA_SHOW_CLOSE_BUTTON_EXTRA, binding.switchShowCloseButton.isChecked)
            putExtra(OverlayService.EXTRA_SCALE_TYPE_EXTRA, scaleTypeIdentifier)
            putExtra(OverlayService.EXTRA_ENABLE_INITIAL_VOLUME_EXTRA, binding.switchEnableInitialVolume.isChecked)
            putExtra(OverlayService.EXTRA_INITIAL_VOLUME_EXTRA, binding.sliderInitialVolume.value.toInt())
            putExtra(OverlayService.EXTRA_FORCE_VOLUME_EXTRA, binding.switchForceVolume.isChecked)
            putExtra(OverlayService.EXTRA_MEDIA_TRANSPARENCY_EXTRA, binding.sliderTransparency.value.toInt())
            putExtra(OverlayService.EXTRA_TOUCH_PASSTHROUGH_EXTRA, binding.switchTouchPassthrough.isChecked)
            putExtra(OverlayService.EXTRA_HARDWARE_DECODING_EXTRA, binding.switchHardwareDecoding.isChecked)
            putExtra(OverlayService.EXTRA_AUDIO_OVERLAY_ENABLED, binding.switchAudioOverlay.isChecked)
            putExtra(OverlayService.EXTRA_AUDIO_OVERLAY_URI, sharedPreferences.getString(KEY_AUDIO_OVERLAY_URI, null))
            putExtra(OverlayService.EXTRA_CONTINUOUS_AUDIO_ENABLED, binding.switchContinuousAudio.isChecked)
        }
        ContextCompat.startForegroundService(this, intent)
        updateAllUiStates()
        Toast.makeText(this, "Video Lock started", Toast.LENGTH_SHORT).show()
    }

    private fun startOverlayServiceWithCollectedSettings() {
        if (selectedFolderUris.isEmpty()) { Toast.makeText(this, "Please select at least one media folder.", Toast.LENGTH_LONG).show(); return }
        val durationSeconds = binding.etDuration.text.toString().toLongOrNull() ?: 5L
        if (durationSeconds == -1L && !binding.switchShowCloseButton.isChecked) { Toast.makeText(this, "Indefinite duration (-1) requires the close button to be enabled.", Toast.LENGTH_LONG).show(); return }
        savePreferences()
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_INTERVAL_EXTRA, getTotalIntervalSecondsFromUi() * 1000)
            putExtra(OverlayService.EXTRA_DURATION_EXTRA, if (durationSeconds == -1L) -1000L else durationSeconds * 1000L)
            putStringArrayListExtra(OverlayService.EXTRA_FOLDER_URIS_EXTRA, ArrayList(selectedFolderUris))
            putExtra(OverlayService.EXTRA_SHOW_PHOTOS_EXTRA, binding.cbPhotos.isChecked)
            putExtra(OverlayService.EXTRA_SHOW_GIFS_EXTRA, binding.cbGifs.isChecked)
            putExtra(OverlayService.EXTRA_SHOW_VIDEOS_EXTRA, binding.cbVideos.isChecked)
            putExtra(OverlayService.EXTRA_RANDOM_TIME_EXTRA, binding.switchRandomTime.isChecked)
            putExtra(OverlayService.EXTRA_SHOW_CLOSE_BUTTON_EXTRA, binding.switchShowCloseButton.isChecked)
            putExtra(OverlayService.EXTRA_SCALE_TYPE_EXTRA, resources.getStringArray(R.array.scale_type_options_internal)[binding.spinnerScaleType.selectedItemPosition])
            putExtra(OverlayService.EXTRA_CONCURRENT_MEDIA_COUNT_EXTRA, if (binding.spinnerScaleType.selectedItemPosition == 0) binding.etConcurrentMediaCount.text.toString().toIntOrNull() ?: 1 else 1)
            putExtra(OverlayService.EXTRA_ENABLE_INITIAL_VOLUME_EXTRA, binding.switchEnableInitialVolume.isChecked)
            putExtra(OverlayService.EXTRA_INITIAL_VOLUME_EXTRA, binding.sliderInitialVolume.value.toInt())
            putExtra(OverlayService.EXTRA_FORCE_VOLUME_EXTRA, binding.switchForceVolume.isChecked)
            putExtra(OverlayService.EXTRA_MEDIA_TRANSPARENCY_EXTRA, binding.sliderTransparency.value.toInt())
            putExtra(OverlayService.EXTRA_TOUCH_PASSTHROUGH_EXTRA, binding.switchTouchPassthrough.isChecked)
            putExtra(OverlayService.EXTRA_HARDWARE_DECODING_EXTRA, binding.switchHardwareDecoding.isChecked)
            putExtra(OverlayService.EXTRA_AUDIO_OVERLAY_ENABLED, binding.switchAudioOverlay.isChecked)
            putExtra(OverlayService.EXTRA_AUDIO_OVERLAY_URI, sharedPreferences.getString(KEY_AUDIO_OVERLAY_URI, null))
            putExtra(OverlayService.EXTRA_CONTINUOUS_AUDIO_ENABLED, binding.switchContinuousAudio.isChecked)

            val useCustomArea = binding.switchCustomArea.isChecked
            putExtra(OverlayService.EXTRA_CUSTOM_AREA_ENABLED, useCustomArea)
            if (useCustomArea) {
                putExtra(OverlayService.EXTRA_CUSTOM_AREA_DEFINED, sharedPreferences.getBoolean(KEY_CUSTOM_AREA_DEFINED, false))
                putExtra(OverlayService.EXTRA_CUSTOM_AREA_LEFT, sharedPreferences.getInt(KEY_CUSTOM_AREA_LEFT, 0))
                putExtra(OverlayService.EXTRA_CUSTOM_AREA_TOP, sharedPreferences.getInt(KEY_CUSTOM_AREA_TOP, 0))
                putExtra(OverlayService.EXTRA_CUSTOM_AREA_RIGHT, sharedPreferences.getInt(KEY_CUSTOM_AREA_RIGHT, 0))
                putExtra(OverlayService.EXTRA_CUSTOM_AREA_BOTTOM, sharedPreferences.getInt(KEY_CUSTOM_AREA_BOTTOM, 0))
            }
        }
        ContextCompat.startForegroundService(this, intent)
        sharedPreferences.edit {
            putBoolean(KEY_OVERLAY_ACTIVE, true)
            putBoolean(KEY_VIDEO_LOCK_ACTIVE, false)
            if (binding.switchTimerLock.isChecked) {
                putLong(KEY_LOCK_END_TIME_MILLIS, System.currentTimeMillis() + (getTotalLockDurationSecondsFromUi() * 1000))
            } else {
                remove(KEY_LOCK_END_TIME_MILLIS)
            }
        }
        updateAllUiStates()
        Toast.makeText(this, "Overlay service started", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlayService() {
        if (binding.switchTimerLock.isChecked && sharedPreferences.getLong(KEY_LOCK_END_TIME_MILLIS, 0L) > System.currentTimeMillis()) {
            Toast.makeText(this, "Timer lock is active. Cannot stop overlay yet.", Toast.LENGTH_LONG).show()
            return
        }
        lockCountDownTimer?.cancel()

        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP_SERVICE
        }
        startService(intent)

        sharedPreferences.edit(commit = true) {
            putBoolean(KEY_OVERLAY_ACTIVE, false)
            remove(KEY_LOCK_END_TIME_MILLIS)
        }
        updateAllUiStates()
        Toast.makeText(this, "Overlay service stopped", Toast.LENGTH_SHORT).show()
    }
    private fun isOverlayServiceRunning(): Boolean = sharedPreferences.getBoolean(KEY_OVERLAY_ACTIVE, false)
    private fun isVideLockModeActive(): Boolean = sharedPreferences.getBoolean(KEY_VIDEO_LOCK_ACTIVE, false)

    private fun savePreferences() {
        sharedPreferences.edit {
            putLong(KEY_INTERVAL_VALUE, binding.etIntervalValue.text.toString().toLongOrNull() ?: 30L)
            putInt(KEY_INTERVAL_UNIT_INDEX, binding.spinnerIntervalUnits.selectedItemPosition)
            putLong(KEY_DURATION, binding.etDuration.text.toString().toLongOrNull() ?: 5L)
            putString(KEY_SELECTED_FOLDERS, gson.toJson(selectedFolderUris))
            putBoolean(KEY_PHOTOS_ENABLED, binding.cbPhotos.isChecked)
            putBoolean(KEY_GIFS_ENABLED, binding.cbGifs.isChecked)
            putBoolean(KEY_VIDEOS_ENABLED, binding.cbVideos.isChecked)
            putBoolean(KEY_RANDOM_TIME_ENABLED, binding.switchRandomTime.isChecked)
            putBoolean(KEY_SHOW_CLOSE_BUTTON, binding.switchShowCloseButton.isChecked)
            putString(KEY_SCALE_TYPE, resources.getStringArray(R.array.scale_type_options_internal)[binding.spinnerScaleType.selectedItemPosition])
            putInt(KEY_CONCURRENT_MEDIA_COUNT, binding.etConcurrentMediaCount.text.toString().toIntOrNull() ?: 1)
            putBoolean(KEY_ENABLE_INITIAL_VOLUME, binding.switchEnableInitialVolume.isChecked)
            putInt(KEY_INITIAL_VOLUME, binding.sliderInitialVolume.value.toInt())
            putBoolean(KEY_FORCE_VOLUME, binding.switchForceVolume.isChecked)
            putInt(KEY_MEDIA_TRANSPARENCY, binding.sliderTransparency.value.toInt())
            putBoolean(KEY_TOUCH_PASSTHROUGH, binding.switchTouchPassthrough.isChecked)
            putBoolean(KEY_HARDWARE_DECODING, binding.switchHardwareDecoding.isChecked)
            putBoolean(KEY_TIMER_LOCK_ENABLED, binding.switchTimerLock.isChecked)
            putLong(KEY_LOCK_DURATION_VALUE, binding.etLockDurationValue.text.toString().toLongOrNull() ?: 10L)
            putInt(KEY_LOCK_DURATION_UNIT_INDEX, binding.spinnerLockDurationUnits.selectedItemPosition)
            putBoolean(KEY_DEVICE_ADMIN_REQUESTED_STATE, binding.switchDeviceAdmin.isChecked)
            putBoolean(KEY_CUSTOM_AREA_ENABLED, binding.switchCustomArea.isChecked)
            putBoolean(KEY_AUDIO_OVERLAY_ENABLED, binding.switchAudioOverlay.isChecked)
            putBoolean(KEY_CONTINUOUS_AUDIO_ENABLED, binding.switchContinuousAudio.isChecked)
        }
    }

    private fun loadPreferences() {
        binding.etIntervalValue.setText(sharedPreferences.getLong(KEY_INTERVAL_VALUE, 30L).toString())
        binding.spinnerIntervalUnits.setSelection(sharedPreferences.getInt(KEY_INTERVAL_UNIT_INDEX, UNIT_SECONDS), false)
        binding.etDuration.setText((sharedPreferences.getLong(KEY_DURATION, 5L)).toString())
        sharedPreferences.getString(KEY_SELECTED_FOLDERS, null)?.let {
            try {
                val uris: List<String> = gson.fromJson(it, object : TypeToken<ArrayList<String>>() {}.type) ?: emptyList()
                selectedFolderUris.clear(); selectedFolderUris.addAll(uris)
            } catch (_: Exception) { selectedFolderUris.clear() }
        }
        updateSelectedFoldersTextView()
        binding.cbPhotos.isChecked = sharedPreferences.getBoolean(KEY_PHOTOS_ENABLED, true)
        binding.cbGifs.isChecked = sharedPreferences.getBoolean(KEY_GIFS_ENABLED, true)
        binding.cbVideos.isChecked = sharedPreferences.getBoolean(KEY_VIDEOS_ENABLED, true)
        binding.switchRandomTime.isChecked = sharedPreferences.getBoolean(KEY_RANDOM_TIME_ENABLED, false)
        binding.switchShowCloseButton.isChecked = sharedPreferences.getBoolean(KEY_SHOW_CLOSE_BUTTON, true)
        val scaleTypeIdentifiers = resources.getStringArray(R.array.scale_type_options_internal)
        val savedScaleType = sharedPreferences.getString(KEY_SCALE_TYPE, scaleTypeIdentifiers[0])
        binding.spinnerScaleType.setSelection((scaleTypeIdentifiers.indexOf(savedScaleType).takeIf { it >= 0 } ?: 0), false)
        binding.etConcurrentMediaCount.setText((sharedPreferences.getInt(KEY_CONCURRENT_MEDIA_COUNT, 1)).toString())
        val enableInitialVolume = sharedPreferences.getBoolean(KEY_ENABLE_INITIAL_VOLUME, false)
        binding.switchEnableInitialVolume.isChecked = enableInitialVolume
        val loadedVolume = sharedPreferences.getInt(KEY_INITIAL_VOLUME, 50)
        binding.sliderInitialVolume.value = loadedVolume.toFloat()
        binding.tvVolumeValue.text = String.format(Locale.US, getString(R.string.volume_value_format), loadedVolume)
        binding.switchForceVolume.isChecked = sharedPreferences.getBoolean(KEY_FORCE_VOLUME, false)
        val mediaTransparency = sharedPreferences.getInt(KEY_MEDIA_TRANSPARENCY, 100)
        binding.sliderTransparency.value = mediaTransparency.toFloat()
        binding.tvTransparencyValue.text = String.format(Locale.US, getString(R.string.transparency_value_format), mediaTransparency)
        binding.switchTouchPassthrough.isChecked = sharedPreferences.getBoolean(KEY_TOUCH_PASSTHROUGH, false)
        val timerLockEnabled = sharedPreferences.getBoolean(KEY_TIMER_LOCK_ENABLED, false)
        binding.switchHardwareDecoding.isChecked = sharedPreferences.getBoolean(KEY_HARDWARE_DECODING, false)
        binding.switchTimerLock.isChecked = timerLockEnabled
        binding.etLockDurationValue.setText(sharedPreferences.getLong(KEY_LOCK_DURATION_VALUE, 10L).toString())
        binding.spinnerLockDurationUnits.setSelection(sharedPreferences.getInt(KEY_LOCK_DURATION_UNIT_INDEX, UNIT_SECONDS), false)
        updateTimerLockControlsVisibility(timerLockEnabled)
        binding.switchCustomArea.isChecked = sharedPreferences.getBoolean(KEY_CUSTOM_AREA_ENABLED, false)
        binding.switchAudioOverlay.isChecked = sharedPreferences.getBoolean(KEY_AUDIO_OVERLAY_ENABLED, false)
        binding.switchContinuousAudio.isChecked = sharedPreferences.getBoolean(KEY_CONTINUOUS_AUDIO_ENABLED, false)
        updateAllUiStates()
        enforceVideoCountLimit()
    }

    override fun onResume() {
        super.onResume()
        val videoFilter = IntentFilter(OverlayService.ACTION_VIDEO_LOCK_FINISHED)
        ContextCompat.registerReceiver(this, videoLockStateReceiver, videoFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        updateAllUiStates()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(videoLockStateReceiver)
        savePreferences()
    }

    override fun onDestroy() {
        super.onDestroy()
        lockCountDownTimer?.cancel()
    }
}