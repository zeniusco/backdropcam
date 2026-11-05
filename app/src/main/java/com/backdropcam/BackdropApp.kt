package com.backdropcam

import android.app.Application

class BackdropApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: BackdropApp
            private set
    }
}
