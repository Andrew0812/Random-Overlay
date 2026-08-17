package com.example.randomoverlay

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.randomoverlay.databinding.ActivityHelpBinding

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

class HelpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpBinding

    private data class HelpItem(val title: String, val content: String, val isExpandable: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI(window)

        setSupportActionBar(binding.toolbar)
        // We no longer set the home indicator here. It's handled by the menu.
        supportActionBar?.title = getString(R.string.app_help)

        val helpItems = createHelpItems()
        populateHelpItems(helpItems)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the new menu for this activity
        menuInflater.inflate(R.menu.help_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            // Handle the click on the home icon in the menu
            R.id.action_home -> {
                finish() // Simply finish the activity to go back
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun populateHelpItems(items: List<HelpItem>) {
        val container = binding.helpItemsContainer
        val inflater = LayoutInflater.from(this)

        for (item in items) {
            val itemView = inflater.inflate(R.layout.expandable_item_layout, container, false)

            val titleView: TextView = itemView.findViewById(R.id.item_title)
            val contentView: TextView = itemView.findViewById(R.id.item_content)
            val arrowView: ImageView = itemView.findViewById(R.id.item_arrow)

            titleView.text = item.title
            contentView.text = item.content

            if (item.isExpandable) {
                itemView.setOnClickListener {
                    val isVisible = contentView.isVisible
                    contentView.visibility = if (isVisible) View.GONE else View.VISIBLE
                    arrowView.animate().rotation(if (isVisible) 0f else 180f).setDuration(300).start()
                }
            } else {
                arrowView.visibility = View.GONE
                // Set background to black and text to white for non-expandable items
                itemView.setBackgroundColor(ContextCompat.getColor(this, R.color.pitch_black))
                titleView.setTextColor(Color.WHITE)
            }
            container.addView(itemView)
        }
    }

    private fun createHelpItems(): List<HelpItem> {
        return listOf(
            HelpItem("Show every:", "How often media is displayed (e.g. every hour).", true),
            HelpItem("Show for (seconds):", "How long media is displayed for each time.", true),
            HelpItem("Select/Clear Media Folder Buttons", "Allows you to select what folders media will be played from and clear your selection.", true),
            HelpItem("Media Types to Display:", "Choose either to display Photos, GIFs, Videos, or a combination of them.", true),
            HelpItem("Show at random time within interval", "If enabled, media will appear at a random point within each interval instead of exactly at the end of it.", true),
            HelpItem("Show (X) close button on media", "Choose to display an (X) button in the top-right to close media early. This does not stop the overlay service.", true),
            HelpItem(
                "Media Display Mode:",
                "• Fit to Content: Displays multiple media at once, each scaled to fit within a unique, randomly-assigned portion of the screen.\n" +
                        "  - Max Concurrent Media: Sets how many items (1-50) appear at once in this mode.\n\n" +
                        "• Fill Screen: Fills the screen with a single media item, preserving its aspect ratio (may leave black bars).\n\n" +
                        "• Fill Screen (Stretch): Stretches a single media item to fill the screen completely, ignoring its aspect ratio.\n\n" +
                        "• Fill Screen (Borders): Same as 'Fill Screen' but adds a black background, ensuring no part of the underlying screen is visible.\n\n" +
                        "• Fill Screen (Crop): Zooms and crops a single media item to fill the screen without leaving bars or stretching.",
                true
            ),
            HelpItem(
                "Set Initial Volume",
                "Controls the device's media volume when a video or audio track begins to play.\n\n" +
                        "• Initial Volume Slider: Slide to adjust the volume or tap the number to type a value directly.\n\n" +
                        "• Force Volume During Playback: Continuously resets the volume to the selected level, preventing manual volume changes while media is active.",
                true
            ),
            HelpItem("Media Transparency Slider", "Set the opacity of the media overlays. You can slide the handle or tap the percentage value to type a number directly.", true),
            HelpItem("Allow Touch Through Media", "Allows you to interact with the content underneath the media overlays. The close button is hidden in this mode.", true),
            HelpItem( // THIS IS THE NEWLY ADDED ITEM
                "Force High-Performance Decoding (Smoother GIFs)",
                "This instructs the app to use a more memory-efficient decoding format for images and GIFs. Enable this if you experience stuttering or lag, especially when displaying many GIFs at once.",
                true
            ),
            HelpItem(
                "Audio Overlay",
                "Allows you to play a separate audio track alongside your visual media.\n\n" +
                        "• Select Audio/Video File: Choose a sound or video file from your device to use as the audio track.\n\n" +
                        "• Continuous Audio (Looping):\n" +
                        "  - When OFF, the audio track only plays when visual media is on-screen.\n" +
                        "  - When ON, the audio track starts with the service and loops continuously until the service is stopped.",
                true
            ),
            HelpItem("Enable Timer Lock", "Locks the 'Stop Overlay' button for a specified duration, preventing the service from being stopped prematurely. The timer is not reset by a phone reboot.", true),
            HelpItem("Enable Enhanced App Protection (Device Admin)", "This optional setting makes the app a 'device administrator,' which makes it harder to uninstall or force-stop. This is intended to strengthen the Timer Lock feature. It can be disabled at any time from your phone's system settings.", true),
            HelpItem("Use Custom Media Area", "Restricts all media to a specific, user-defined rectangle on the screen. This only works with the 'Fit to Content' display mode.\n\n" +
                    "• Define Media Area: Tap this to enter selection mode, where you can move and resize the rectangle. Double-tap the area to confirm.", true),
            HelpItem("Start Overlay", "Starts the Random Overlay Service with the current settings.", false),
            HelpItem("Start Video Lock", "Lets you immediately play a single video of your choice as a full-screen overlay, using the current volume and visual settings but ignoring the interval and duration timers.", true)
        )
    }
}