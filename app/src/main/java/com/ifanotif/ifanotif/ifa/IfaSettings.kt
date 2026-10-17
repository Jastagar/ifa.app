package com.ifanotif.ifanotif.ifa

import android.content.Context
import android.content.SharedPreferences

object IfaSettings {
    private const val PREFS_NAME = "ifa_settings"

    private lateinit var prefs: SharedPreferences

    fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var pairingCode: String
        get() = prefs.getString("pairing_code", "")!!
        set(value) = prefs.edit().putString("pairing_code", value.trim()).apply()

    /** Empty means "find the PC on the Wi-Fi automatically". */
    var manualHost: String
        get() = prefs.getString("manual_host", "")!!
        set(value) = prefs.edit().putString("manual_host", value.trim()).apply()

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_word_enabled", true)
        set(value) = prefs.edit().putBoolean("wake_word_enabled", value).apply()

    /** Empty means "use the threshold configured on the PC". */
    var wakeThreshold: String
        get() = prefs.getString("wake_threshold", "")!!
        set(value) = prefs.edit().putString("wake_threshold", value.trim()).apply()

    var wakeScreen: Boolean
        get() = prefs.getBoolean("wake_screen", true)
        set(value) = prefs.edit().putBoolean("wake_screen", value).apply()

    var forwardNotifications: Boolean
        get() = prefs.getBoolean("forward_notifications", true)
        set(value) = prefs.edit().putBoolean("forward_notifications", value).apply()

    /** The old Spring Boot bridge (POST /api/v1/notifications). Off unless turned on. */
    var legacyBackendEnabled: Boolean
        get() = prefs.getBoolean("legacy_backend_enabled", false)
        set(value) = prefs.edit().putBoolean("legacy_backend_enabled", value).apply()
}
