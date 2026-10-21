package com.ifanotif.ifanotif.ifa

import android.content.Context
import android.content.SharedPreferences
import com.ifanotif.ifanotif.voice.MicGain
import org.json.JSONObject

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

    /** Empty means "learn it automatically". */
    var micBoost: String
        get() = prefs.getString("mic_boost", "")!!
        set(value) = prefs.edit().putString("mic_boost", value.trim()).apply()

    var learnedMicGain: Float
        get() = prefs.getFloat("learned_mic_gain", MicGain.DEFAULT)
        set(value) = prefs.edit().putFloat("learned_mic_gain", value).apply()

    fun micGain(): Float = micBoost.toFloatOrNull()?.coerceIn(MicGain.MIN, MicGain.MAX) ?: learnedMicGain

    /** How long a pause ends what you're saying. */
    var silenceSec: Float
        get() = prefs.getFloat("silence_sec", 1.5f)
        set(value) = prefs.edit().putFloat("silence_sec", value.coerceIn(0.3f, 5f)).apply()

    /** Ifa's VAD threshold for phone audio; lower keeps soft speech from counting as silence. */
    var speechThreshold: Float
        get() = prefs.getFloat("speech_threshold", 0.5f)
        set(value) = prefs.edit().putFloat("speech_threshold", value.coerceIn(0.1f, 0.95f)).apply()

    var wakeScreen: Boolean
        get() = prefs.getBoolean("wake_screen", true)
        set(value) = prefs.edit().putBoolean("wake_screen", value).apply()

    var forwardNotifications: Boolean
        get() = prefs.getBoolean("forward_notifications", true)
        set(value) = prefs.edit().putBoolean("forward_notifications", value).apply()

    // Notification settings. The phone owns them and sends them to Ifa on every connect.

    val PRIORITIES = listOf("high", "normal", "low", "off")

    private val DEFAULT_APP_PRIORITIES = mapOf(
        "com.whatsapp" to "high",
        "com.whatsapp.w4b" to "high",
        "org.telegram.messenger" to "high",
        "com.google.android.apps.messaging" to "high",
        "com.google.android.dialer" to "high",
        "com.samsung.android.dialer" to "high",
        "com.google.android.gm" to "normal",
        "com.Slack" to "normal",
    )

    private const val MAX_SEEN_APPS = 80

    /** For apps without their own setting. */
    var defaultAppPriority: String
        get() = prefs.getString("default_app_priority", "low")!!
        set(value) = prefs.edit().putString("default_app_priority", value).apply()

    fun appPriorities(): Map<String, String> =
        prefs.getString("app_priorities", null)?.let(::readMap) ?: DEFAULT_APP_PRIORITIES

    fun setAppPriority(packageName: String, priority: String) {
        val updated = appPriorities().toMutableMap().apply { put(packageName, priority) }
        prefs.edit().putString("app_priorities", JSONObject(updated as Map<*, *>).toString()).apply()
    }

    fun priorityFor(packageName: String): String = appPriorities()[packageName] ?: defaultAppPriority

    /** Apps that have posted a notification, package name to label, so the settings can list them. */
    fun seenApps(): Map<String, String> = prefs.getString("seen_apps", null)?.let(::readMap) ?: emptyMap()

    fun rememberApp(packageName: String, label: String) {
        val seen = seenApps()
        if (seen[packageName] == label) return
        val updated = LinkedHashMap(seen).apply { remove(packageName); put(packageName, label) }
        while (updated.size > MAX_SEEN_APPS) updated.remove(updated.keys.first())
        prefs.edit().putString("seen_apps", JSONObject(updated as Map<*, *>).toString()).apply()
    }

    /** Wait this long after a sender's last message before Ifa judges the group. */
    var notifSettleSec: Float
        get() = prefs.getFloat("notif_settle_sec", 4f)
        set(value) = prefs.edit().putFloat("notif_settle_sec", value.coerceIn(1f, 60f)).apply()

    /** How long a notification stays "new" before it moves to the older list. */
    var notifTtlSec: Float
        get() = prefs.getFloat("notif_ttl_sec", 90f)
        set(value) = prefs.edit().putFloat("notif_ttl_sec", value.coerceIn(10f, 3600f)).apply()

    var notifMaxRecent: Int
        get() = prefs.getInt("notif_max_recent", 7)
        set(value) = prefs.edit().putInt("notif_max_recent", value.coerceIn(1, 50)).apply()

    var notifMaxDiscarded: Int
        get() = prefs.getInt("notif_max_discarded", 50)
        set(value) = prefs.edit().putInt("notif_max_discarded", value.coerceIn(1, 500)).apply()

    /** After a nudge, how long Ifa listens without the wake word. */
    var notifListenSec: Float
        get() = prefs.getFloat("notif_listen_sec", 8f)
        set(value) = prefs.edit().putFloat("notif_listen_sec", value.coerceIn(2f, 30f)).apply()

    fun notificationSettingsMessage(): JSONObject = JSONObject()
        .put("type", "notification_settings")
        .put("settleSec", notifSettleSec.toDouble())
        .put("ttlSec", notifTtlSec.toDouble())
        .put("maxRecent", notifMaxRecent)
        .put("maxDiscarded", notifMaxDiscarded)
        .put("listenSec", notifListenSec.toDouble())

    private fun readMap(json: String): Map<String, String> = runCatching {
        val obj = JSONObject(json)
        obj.keys().asSequence().associateWith { obj.getString(it) }
    }.getOrDefault(emptyMap())

    /** The old Spring Boot bridge (POST /api/v1/notifications). Off unless turned on. */
    var legacyBackendEnabled: Boolean
        get() = prefs.getBoolean("legacy_backend_enabled", false)
        set(value) = prefs.edit().putBoolean("legacy_backend_enabled", value).apply()
}
