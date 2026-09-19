package com.filmkit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.filmkit.app.ui.CameraScreen
import com.filmkit.app.ui.LogScreen
import com.filmkit.app.ui.SettingsScreen
import com.filmkit.app.ui.SpikeScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineBridge.ensureLoaded()
        setContent {
            MaterialTheme {
                FilmKitNav()
            }
        }
    }
}

/**
 * 全局导航（v0.4.8 去全局 Tab）：相机为主屏；设置/日志/能力 Spike 为子页，
 * 左上角返回按钮逐级回退，系统 Back 键同路。子页归属：设置-系统 分类。
 */
@Composable
private fun FilmKitNav() {
    var screen by rememberSaveable { mutableStateOf("camera") }

    BackHandler(enabled = screen != "camera") {
        screen = if (screen == "logs" || screen == "spike") "settings" else "camera"
    }

    when (screen) {
        "settings" -> SettingsScreen(
            onBack = { screen = "camera" },
            onOpenLogs = { screen = "logs" },
            onOpenSpike = { screen = "spike" },
        )
        "logs" -> LogScreen(onBack = { screen = "settings" })
        "spike" -> SpikeScreen(onBack = { screen = "settings" })
        else -> CameraScreen(onOpenSettings = { screen = "settings" })
    }
}
