package com.ifanotif.ifanotif

import android.app.Application
import com.ifanotif.ifanotif.api.RetrofitClient
import com.ifanotif.ifanotif.ifa.IfaSettings

class IfaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        IfaSettings.initialize(this)
        RetrofitClient.initialize(this)
    }
}
