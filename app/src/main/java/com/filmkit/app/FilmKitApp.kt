package com.filmkit.app

import android.app.Application
import java.io.File

class FilmKitApp : Application() {

    companion object {
        /** 必须与 app/build.gradle.kts 的 versionName 一致（单一来源，避免两处字面量各漂各的） */
        const val VERSION = "0.6.50-m4"
    }

    override fun onCreate() {
        super.onCreate()
        EngineBridge.ensureLoaded()
        val dir = (getExternalFilesDir("logs") ?: File(filesDir, "logs")).absolutePath
        File(dir).mkdirs()
        EngineBridge.nativeInitLogger(dir)
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "app", "app.start",
            kv("version" to VERSION, "logDir" to dir),
        )
        installCrashCapture(dir)
    }

    /** 未捕获异常落盘 crash-latest.txt + JSONL 日志（闪退排查用），随后交回系统默认处理 */
    private fun installCrashCapture(dir: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val stack = "thread=${t.name}\n" + android.util.Log.getStackTraceString(e)
                File(dir, "crash-latest.txt").writeText("version=$VERSION\n$stack")
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "app", "app.crash",
                    kv("thread" to t.name, "stack" to android.util.Log.getStackTraceString(e)),
                )
            }
            previous?.uncaughtException(t, e)
        }
    }
}
