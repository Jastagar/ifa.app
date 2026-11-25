package com.ifanotif.ifanotif.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ifanotif.ifanotif.legacy.NotificationRequest
import com.ifanotif.ifanotif.ifa.IfaSettings
import com.ifanotif.ifanotif.legacy.NotificationRepository
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

        connected = true
        report("Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()

        connected = false
        report("Notification listener disconnected; asking Android to reconnect it")
        rebind(this)
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

        val rule = IfaSettings.appRule(sbn.packageName)
        report("Notification from $appName (${sbn.packageName})")

        if (IfaSettings.forwardNotifications && !rule.allowed) {
            report("Not sent: $appName is off in the Apps tab")
        } else if (IfaSettings.forwardNotifications) {
            val sent = IfaVoiceService.instance?.forwardNotification(
                JSONObject()
                    .put("packageName", request.packageName)
                    .put("appName", request.appName)
                    .put("title", request.title)
                    .put("text", request.text)
                    .put("receivedAt", request.receivedAt)
                    .put("priority", "normal")
                    .put("appNote", rule.note)
            ) ?: false
            AppLogger.log(if (sent) "➡️ Sent to Ifa" else "Ifa not connected; notification not sent")
        } else {
            report("Not sent: 'Send notifications to Ifa' is off")
        }

        if (IfaSettings.legacyBackendEnabled) upload(request)
    }

    companion object {
        @Volatile var connected = false

        /** Log on the phone and, when connected, in Ifa's console too, so problems show up on the PC. */
        fun report(text: String) {
            AppLogger.log(text)
            IfaVoiceService.instance?.sendLog(text)
        }

        /**
         * Android often unbinds a notification listener after the app is updated or killed, and then never
         * calls it again until access is toggled. Asking for a rebind brings it back (no-op without access).
         */
        fun rebind(context: Context) {
            runCatching { requestRebind(ComponentName(context, NotificationTrackerService::class.java)) }
                .onFailure { AppLogger.log("Couldn't reconnect the notification listener: ${it.message}") }
        }
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
