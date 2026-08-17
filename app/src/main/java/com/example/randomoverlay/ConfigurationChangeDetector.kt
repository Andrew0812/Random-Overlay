package com.example.randomoverlay

import android.content.Context
import android.content.res.Configuration
import android.view.View

/**
 * A tiny, invisible view whose only purpose is to be attached to the WindowManager
 * to receive onConfigurationChanged events and forward them to a listener.
 */
class ConfigurationChangeDetector(context: Context) : View(context) {

    // A simple interface to communicate the change event back to the service.
    interface OnConfigurationChangeListener {
        fun onConfigurationChanged()
    }

    var listener: OnConfigurationChangeListener? = null

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        // When a configuration change is detected, notify the listener.
        listener?.onConfigurationChanged()
    }
}