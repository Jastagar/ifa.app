package com.ifanotif.ifanotif.service

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
import com.ifanotif.ifanotif.dto.NotificationRequest
import com.ifanotif.ifanotif.repository.NotificationRepository
import com.ifanotif.ifanotif.utils.AppLogger
import com.ifanotif.ifanotif.utils.DeviceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

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

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        val extras = sbn.notification.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        val text =
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()

        val request = NotificationRequest(
            packageName = sbn.packageName,
            title = title,
            text = text,
            appName = sbn.packageName,
            receivedAt = Instant.ofEpochMilli(sbn.postTime).toString(),
            deviceId = DeviceUtils.getDeviceId(this)
        )

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

        AppLogger.log("Package : ${sbn.packageName}")
        AppLogger.log("Title   : $title")
        AppLogger.log("Message : $text")
    }
}