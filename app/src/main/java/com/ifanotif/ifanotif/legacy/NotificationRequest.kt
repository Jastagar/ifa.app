package com.ifanotif.ifanotif.legacy

data class NotificationRequest(
    val packageName: String,
    val title: String?,
    val text: String?,
    val deviceId: String,
    val appName: String,
    val receivedAt: String
)