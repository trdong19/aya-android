package io.liriliri.aya

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AyaApplication : Application() {
    companion object {
        lateinit var instance: AyaApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
