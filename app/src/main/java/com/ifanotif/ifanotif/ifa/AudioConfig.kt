package com.ifanotif.ifanotif.ifa

import org.json.JSONObject

/** Phone mic setup, set in Ifa's launcher. Defaults match what the app did before it was configurable. */
data class AudioConfig(
    val source: String = "voice_communication",
    val noiseSuppression: Boolean = true,
    val echoCancel: Boolean = true,
    val autoGain: Boolean = true,
    val boost: Float = 0f, // 0 = learn it
) {
    fun toJson(): JSONObject = JSONObject()
        .put("source", source).put("noiseSuppression", noiseSuppression).put("echoCancel", echoCancel)
        .put("autoGain", autoGain).put("boost", boost.toDouble())

    companion object {
        fun fromJson(raw: String?): AudioConfig = raw?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() } ?: AudioConfig()

        fun fromJson(json: JSONObject): AudioConfig {
            val d = AudioConfig()
            return AudioConfig(
                source = json.optString("source", d.source),
                noiseSuppression = json.optBoolean("noiseSuppression", d.noiseSuppression),
                echoCancel = json.optBoolean("echoCancel", d.echoCancel),
                autoGain = json.optBoolean("autoGain", d.autoGain),
                boost = json.optDouble("boost", 0.0).toFloat(),
            )
        }
    }
}
