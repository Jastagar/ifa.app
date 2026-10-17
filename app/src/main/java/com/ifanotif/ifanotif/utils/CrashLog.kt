package com.ifanotif.ifanotif.utils

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves the stack trace of a crash so the app can show it on the next launch. */
object CrashLog {
    private lateinit var file: File

    fun install(context: Context) {
        file = File(context.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val recent = AppLogger.logs.value.take(15).reversed().joinToString("\n") { "[${it.timestamp}] ${it.message}" }
                file.writeText("$time on thread ${thread.name}\n$trace\nLast log lines:\n$recent")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(): String? = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear() {
        runCatching { file.delete() }
    }
}
