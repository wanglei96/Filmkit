package com.filmkit.app

import android.app.Application
import java.io.File

class FilmKitApp : Application() {

    override fun onCreate() {
        super.onCreate()
        EngineBridge.ensureLoaded()
        val dir = (getExternalFilesDir("logs") ?: File(filesDir, "logs")).absolutePath
        File(dir).mkdirs()
        EngineBridge.nativeInitLogger(dir)
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "app", "app.start",
            kv("version" to "0.4.6-m2", "logDir" to dir),
        )
    }
}
