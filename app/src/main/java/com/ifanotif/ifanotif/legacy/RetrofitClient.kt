package com.ifanotif.ifanotif.legacy

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    private const val PREFS_NAME = "ifa_settings"
    private const val BASE_URL_KEY = "base_url"
    private const val DEFAULT_BASE_URL = "http://192.168.1.11:8080/"

    private lateinit var appContext: Context
    @Volatile private var retrofitInstance: Retrofit? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private fun preferences() =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun currentBaseUrl(): String =
        preferences().getString(BASE_URL_KEY, DEFAULT_BASE_URL)!!

    fun saveBaseUrl(url: String) {
        val normalized = url.trim().let {
            if (it.endsWith("/")) it else "$it/"
        }

        require(
            normalized.startsWith("http://") ||
                    normalized.startsWith("https://")
        ) { "URL must start with http:// or https://" }

        require(
            android.util.Patterns.WEB_URL.matcher(
                normalized.removeSuffix("/")
            ).matches()
        ) { "Enter a valid backend URL" }

        preferences().edit().putString(BASE_URL_KEY, normalized).apply()

        synchronized(this) {
            retrofitInstance = null
        }
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            Log.d("HTTP", "➡️ ${request.method} ${request.url}")

            request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                Log.d("HTTP", "Body: ${buffer.readUtf8()}")
            }

            val response = chain.proceed(request)
            Log.d("HTTP", "⬅️ ${response.code} ${response.message}")
            response
        }
        .build()

    private fun retrofit(): Retrofit {
        check(::appContext.isInitialized) {
            "Call RetrofitClient.initialize(context) before using the API"
        }

        retrofitInstance?.let { return it }

        return synchronized(this) {
            retrofitInstance ?: Retrofit.Builder()
                .baseUrl(currentBaseUrl())
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .also { retrofitInstance = it }
        }
    }

    val api: NotificationApi
        get() = retrofit().create(NotificationApi::class.java)
}