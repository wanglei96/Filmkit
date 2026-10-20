package com.filmkit.app

import android.app.Application
import java.io.File

class FilmKitApp : Application() {

    companion object {
        /**
         * 版本号 = `app/build.gradle.kts` 的 versionName（Gradle 生成的 BuildConfig 常量）。
         *
         * v0.10.23 修根因：此前这里是**另一处硬编码字面量**，注释写着"必须与
         * build.gradle.kts 一致（单一来源）"——实际并未做到，两处各漂各的：
         * versionCode 174 / versionName 0.10.23 的包，`app.start` 日志与崩溃快照里
         * 仍报 0.10.22-m4，真机版本判读（以及"这版到底测没测"）被直接误导。
         * 现在字面量只剩 build.gradle.kts 一处，漂不了。
         */
        val VERSION = BuildConfig.VERSION_NAME
    }

    override fun onCreate() {
        super.onCreate()
        EngineBridge.ensureLoaded()
        // NN 推理引擎（v0.10.102）：注入 application context（JNI 回调栈里拿不到）
        com.filmkit.app.nn.NnInferEngine.attach(this)
        val dir = (getExternalFilesDir("logs") ?: File(filesDir, "logs")).absolutePath
        File(dir).mkdirs()
        EngineBridge.nativeInitLogger(dir)
        // gainmap 数学版本（v0.11.50）：native 侧是进程级原子变量，重启后归 0，
        // 必须按 prefs 恢复 —— 否则用户选了"官方"、重开 App 又变回 PC（静默行为漂移）。
        runCatching {
            val mode = getSharedPreferences("filmkit", 0).getInt("gainmap_math", 0)
            EngineBridge.nativeSetGainmapMath(mode)
        }
        // 自动曝光（v0.11.61）：同样是进程级原子变量，按 prefs 恢复。默认开。
        runCatching {
            val on = getSharedPreferences("filmkit", 0).getBoolean("ae_auto", true)
            EngineBridge.nativeSetAutoExposure(on)
        }
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
