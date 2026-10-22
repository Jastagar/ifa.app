package com.ifanotif.ifanotif.legacy

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.GET

interface NotificationApi {

    @GET("/api/v1/health")
    suspend fun health(): Response<String>

    @POST("/api/v1/notifications")
    suspend fun sendNotification(
        @Body request: NotificationRequest
    ): Response<Unit>
}