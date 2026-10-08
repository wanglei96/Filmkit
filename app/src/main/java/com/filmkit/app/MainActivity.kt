package com.filmkit.app

import android.net.Uri
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
import com.filmkit.app.ui.EditorScreen
import com.filmkit.app.ui.FilmTheme
import com.filmkit.app.ui.GalleryScreen
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
 * 编辑器（E1-1）与相册页都是从相机页进入的全屏页。
 *
 * 相册页（用户报的第 5/6 点）有两个入口：相机页胶卷位、编辑器左上角菜单图标。
 * **返回归属 = 来的那一页**（[editorFrom] / [galleryFrom]）：从编辑器进的相册回编辑器，
 * 从相机进的回相机 —— 否则"相机 → 相册 → 编辑器 → 返回"会被一路弹回相机页，
 * 用户在相册里挑图的动作就白做了。
 */
@Composable
private fun FilmKitNav(onRegisterVolume: ((() -> Unit)?) -> Unit) {
    var screen by rememberSaveable { mutableStateOf("camera") }
    // 编辑器当前打开的成片。Uri 是 Parcelable，rememberSaveable 能跨进程重建保住；
    // 万一恢复后 URI 已失效，EditorScreen 自己会显示"图片已失效"，不会白屏。
    var editorUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    // 来源页：编辑器与相册各自记住"从哪进来的"，返回时回到那一页（见上面的 KDoc）
    var editorFrom by rememberSaveable { mutableStateOf("camera") }
    var galleryFrom by rememberSaveable { mutableStateOf("camera") }

    BackHandler(enabled = screen != "camera") {
        screen = when (screen) {
            "editor" -> editorFrom
            "gallery" -> galleryFrom
            in setOf("logs", "spike", "lens", "accent", "focals") -> "settings"
            else -> "camera"
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
        // 相册：点缩略图把该图的 URI 交给编辑器（编辑器自己解码**原图**，不用网格缩略图）
        "gallery" -> GalleryScreen(
            onBack = { screen = galleryFrom },
            onOpenEditor = { uri ->
                editorUri = uri
                editorFrom = "gallery"
                screen = "editor"
            },
        )
        // 编辑器：uri 为 null 时 EditorScreen 显示失败态（见其 KDoc），不做额外拦截
        "editor" -> EditorScreen(
            uri = editorUri,
            onBack = { screen = editorFrom },
            onOpenGallery = {
                galleryFrom = "editor"
                screen = "gallery"
            },
        )
        else -> CameraScreen(
            onOpenSettings = { screen = "settings" },
            onOpenGallery = {
                galleryFrom = "camera"
                screen = "gallery"
            },
            onRegisterVolume = onRegisterVolume,
        )
    }
}
