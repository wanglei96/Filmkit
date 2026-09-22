package com.filmkit.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.filmkit.app.ui.AccentColorScreen
import com.filmkit.app.ui.CameraScreen
import com.filmkit.app.ui.FilmTheme
import com.filmkit.app.ui.LensConfigScreen
import com.filmkit.app.ui.LogScreen
import com.filmkit.app.ui.SettingsScreen
import com.filmkit.app.ui.SpikeScreen

class MainActivity : ComponentActivity() {

    /** 相机页注册的音量键拍照动作（离开相机页即注销） */
    private var volumeCapture: (() -> Unit)? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 音量键拍照：仅在相机页拦截（volumeCapture 非空），其余页面走系统音量
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            volumeCapture != null
        ) {
            volumeCapture?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineBridge.ensureLoaded()
        // 强调色（设置-系统颜色）持久化载入，FilmTheme.Accent 为全局状态，改动即时生效
        val prefs = getSharedPreferences("filmkit", 0)
        FilmTheme.setAccent(Color(prefs.getInt("accent_argb", 0xFFE8842C.toInt())))
        setContent {
            // 必须传 colorScheme：空 MaterialTheme 会用默认浅色，Material 组件（对话框 /
            // 输入框 / 按钮）会渲染成白底 —— 与胶片暗色风格冲突。
            MaterialTheme(colorScheme = FilmTheme.colorScheme()) {
                FilmKitNav(onRegisterVolume = { volumeCapture = it })
            }
        }
    }
}

/**
 * 全局导航（无全局 Tab）：相机为主屏；设置/镜头配置/系统颜色/日志/能力 Spike 为子页，
 * 左上角返回按钮逐级回退，系统 Back 键同路。子页归属：设置页各分类。
 */
@Composable
private fun FilmKitNav(onRegisterVolume: ((() -> Unit)?) -> Unit) {
    var screen by rememberSaveable { mutableStateOf("camera") }

    BackHandler(enabled = screen != "camera") {
        screen = if (screen in setOf("logs", "spike", "lens", "accent", "focals")) {
            "settings"
        } else {
            "camera"
        }
    }

    when (screen) {
        "settings" -> SettingsScreen(
            onBack = { screen = "camera" },
            onOpenLogs = { screen = "logs" },
            onOpenSpike = { screen = "spike" },
            onOpenLenses = { screen = "lens" },
            onOpenAccent = { screen = "accent" },
            onOpenFocals = { screen = "focals" },
        )
        "lens" -> LensConfigScreen(onBack = { screen = "settings" })
        "accent" -> AccentColorScreen(onBack = { screen = "settings" })
        "focals" -> com.filmkit.app.ui.FocalScreen(onBack = { screen = "settings" })
        "logs" -> LogScreen(onBack = { screen = "settings" })
        "spike" -> SpikeScreen(onBack = { screen = "settings" })
        else -> CameraScreen(
            onOpenSettings = { screen = "settings" },
            onRegisterVolume = onRegisterVolume,
        )
    }
}
