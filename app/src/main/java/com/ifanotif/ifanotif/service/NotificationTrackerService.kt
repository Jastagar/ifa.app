package com.ifanotif.ifanotif.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ifanotif.ifanotif.dto.NotificationRequest
import com.ifanotif.ifanotif.ifa.IfaSettings
import com.ifanotif.ifanotif.repository.NotificationRepository
import com.ifanotif.ifanotif.utils.AppLogger
import com.ifanotif.ifanotif.utils.DeviceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class NotificationTrackerService : NotificationListenerService() {

    private val repository = NotificationRepository()

    override fun onListenerConnected() {
        super.onListenerConnected()

        AppLogger.log("Notification Listener Connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()

        AppLogger.log("Notification Listener Disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        if (sbn.packageName == packageName) return
        if ((sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = sbn.notification.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        val text =
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()

        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)

        val request = NotificationRequest(
            packageName = sbn.packageName,
            title = title,
            text = text,
            appName = appName,
            receivedAt = isoTime(sbn.postTime),
            deviceId = DeviceUtils.getDeviceId(this)
        )

        AppLogger.log("Package : ${sbn.packageName}")
        AppLogger.log("Title   : $title")
        AppLogger.log("Message : $text")

        if (IfaSettings.forwardNotifications) {
            val sent = IfaVoiceService.instance?.forwardNotification(
                JSONObject()
                    .put("packageName", request.packageName)
                    .put("appName", request.appName)
                    .put("title", request.title)
                    .put("text", request.text)
                    .put("receivedAt", request.receivedAt)
            ) ?: false
            AppLogger.log(if (sent) "➡️ Sent to Ifa" else "Ifa not connected; notification not sent")
        }

        if (IfaSettings.legacyBackendEnabled) upload(request)
    }

    private fun upload(request: NotificationRequest) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = repository.upload(request)

                if (response.isSuccessful) {
                    AppLogger.log("✅ Uploaded notification")
                } else {
                    val errorBody = response.errorBody()?.string()

                    AppLogger.log("Upload failed (${response.code()})")
                    AppLogger.log(errorBody ?: "No error body")
                }
            } catch (e: Exception) {
                AppLogger.log("❌ ${e.message}")
            }
        }
    }

    private fun isoTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))
}
