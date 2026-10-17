package com.ifanotif.ifanotif.repository

import com.ifanotif.ifanotif.api.RetrofitClient
import com.ifanotif.ifanotif.dto.NotificationRequest
import retrofit2.Response

class NotificationRepository {

    suspend fun upload(request: NotificationRequest): Response<Unit> {
        return RetrofitClient.api.sendNotification(request)
    }
}