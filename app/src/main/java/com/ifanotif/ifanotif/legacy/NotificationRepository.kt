package com.ifanotif.ifanotif.legacy

import retrofit2.Response

class NotificationRepository {

    suspend fun upload(request: NotificationRequest): Response<Unit> {
        return RetrofitClient.api.sendNotification(request)
    }
}