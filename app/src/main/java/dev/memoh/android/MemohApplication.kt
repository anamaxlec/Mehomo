package dev.memoh.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MemohApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LocalCrashReports.install(this)
    }
}
