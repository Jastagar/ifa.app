package com.ifanotif.ifanotif

import android.app.Application
import com.ifanotif.ifanotif.legacy.RetrofitClient
import com.ifanotif.ifanotif.ifa.IfaSettings
import com.ifanotif.ifanotif.utils.CrashLog

class IfaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        IfaSettings.initialize(this)
        RetrofitClient.initialize(this)
    }
}
