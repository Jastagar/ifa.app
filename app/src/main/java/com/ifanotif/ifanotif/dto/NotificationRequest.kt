package com.ifanotif.ifanotif.dto

data class NotificationRequest(
    val packageName: String,
    val title: String?,
    val text: String?,
    val deviceId: String,
    val appName: String,
    val receivedAt: String
)