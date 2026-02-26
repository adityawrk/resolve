package com.cssupport.companion

import android.app.Application

class ResolveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
