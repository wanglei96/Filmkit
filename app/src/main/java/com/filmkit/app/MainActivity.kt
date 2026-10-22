package com.filmkit.app

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.filmkit.app.ui.PhotoViewScreen
import com.filmkit.app.ui.SettingsScreen
import com.filmkit.app.ui.SpikeScreen
import com.filmkit.app.ui.backTarget

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

    /** 窗口 HDR 模式（相册 HDR 显示的前提）。失败静默 —— 不影响其他功能。 */
    private fun applyWindowHdrMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            // 同样**不检查 `isScreenHdr`**（理由见 PhotoViewScreen 的注释：该值在用户
            // 这台设备上返回 false，而系统相册能正常显示 HDR ⇒ 它是不可靠的硬条件）。
            window.colorMode = if (enabled) {
                ActivityInfo.COLOR_MODE_HDR
            } else {
                ActivityInfo.COLOR_MODE_DEFAULT
            }
        }.onFailure {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "app", "hdr.window_mode_failed",
                "{\"error\":\"" + (it.message ?: it.toString()).replace("\"", "'") + "\"}",
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineBridge.ensureLoaded()
        // 强调色（设置-系统颜色）持久化载入，FilmTheme.Accent 为全局状态，改动即时生效
        val prefs = getSharedPreferences("filmkit", 0)
        FilmTheme.setAccent(Color(prefs.getInt("accent_argb", 0xFFE8842C.toInt())))
        // 相册/查看页的 HDR 显示（v0.11.50，对齐 PhotonCamera 的 applyPreferredWindowColorMode）：
        // 只有把窗口切到 COLOR_MODE_HDR，系统的 BitmapFactory / ImageDecoder 才会把
        // gainmap 应用到显示 —— 否则 HDR 成片看起来与普通 SDR 一样（元数据在但不生效）。
        // 条件与 PC 一致：Android 14+ 且屏幕本身支持 HDR（isScreenHdr）。
        // 用户可关（prefs hdr_screen，默认开 —— 与 PC 的默认 true 一致）。
        applyWindowHdrMode(prefs.getBoolean("hdr_screen", true))
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
 * 编辑器（E1-1）、相册页、照片查看页（E2-13）都是从相机页进入的全屏页。
 *
 * 相册页（用户报的第 5/6 点）有两个入口：相机页胶卷位、编辑器左上角菜单图标。
 * **返回归属 = 来的那一页**（[editorFrom] / [galleryFrom]）：从编辑器进的相册回编辑器，
 * 从相机进的回相机 —— 否则"相机 → 相册 → 编辑器 → 返回"会被一路弹回相机页，
 * 用户在相册里挑图的动作就白做了。
 *
 * 照片查看页（E2-13，ADR-15）：相册点缩略图 → **查看页**（全屏原图）→ 点「编辑」才进编辑器。
 * 编辑器退出（取消 / 系统返回）**回到查看页**而不是掉回相册网格 —— 用户 2026-10-09 明确
 * 要求。实现方式就在这一层：编辑器记下 [editorFrom]（"viewer"），退出时回那一页；
 * EditorScreen 一行未动（另一张卡 E2-15 正在改它）。
 *
 * E2-20：查看页可左右滑动切换（[viewerUris] + [viewerIndex] 也留在这一层）——
 * 滑到第 7 张再进编辑器，退出时回的仍是**第 7 张**（查看页重建时 initialPage = viewerIndex）。
 *
 * ★ P-3：查看页底栏「胶片」→ 面板选一条预设 → **同样进编辑器**，只是 [editorPresetId] 带上该
 * 预设的 id（用户 2026-10-11 拍板："选预设 → 直接进编辑器"，不是保存副本）。两条入口的返回
 * 语义完全一致（`editorFrom = "viewer"` ⇒ 退出回同一张）；普通「编辑」入口把 id 置 null ⇒
 * 编辑器的行为与 P-3 之前逐位一致（起点仍是全恒等基线）。
 *
 * 返回键的分层归属统一由纯函数 [backTarget] 决定（单测见 PhotoViewerModelTest）。
 */
@Composable
private fun FilmKitNav(onRegisterVolume: ((() -> Unit)?) -> Unit) {
    var screen by rememberSaveable { mutableStateOf("camera") }
    // 编辑器当前打开的成片。Uri 是 Parcelable，rememberSaveable 能跨进程重建保住；
    // 万一恢复后 URI 已失效，EditorScreen 自己会显示"图片已失效"，不会白屏。
    var editorUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    // ★ P-3：本次进编辑器要套用的预设 id（查看页「胶片」面板选中时带上；普通「编辑」入口
    // 一律置 null）。只带 **id** 不带整条预设：预设在 prefs 里是唯一真源，编辑器自己按 id 取
    // —— 跨进程重建时不会拿着一份过期副本。String? 是 Bundle 能存的可序列化类型。
    var editorPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    // 来源页：编辑器与相册各自记住"从哪进来的"，返回时回到那一页（见上面的 KDoc）
    var editorFrom by rememberSaveable { mutableStateOf("camera") }
    var galleryFrom by rememberSaveable { mutableStateOf("camera") }
    // 查看页的照片列表 + 当前索引（E2-20 左右滑动切图）。列表存 URI **字符串**：ArrayList<String>
    // 是 Bundle 能存的可序列化类型，跨进程重建不丢（与 editorUri 用 Parcelable 同理）。
    // **索引必须和列表一起留在这一层**：从查看页进编辑器再退出时，查看页是重新组合的
    // （initialPage = 这个索引），不带索引就会回到第一张 —— 本卡最容易漏的一条。
    var viewerUris by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var viewerIndex by rememberSaveable { mutableIntStateOf(0) }
    // 查看页的来源页（v0.12.34）：相册网格 or 相机页的胶卷位。
    // 从相机直接看大图时返回必须回相机 —— 不能半路掉进相册（用户没去过那里）。
    var viewerFrom by rememberSaveable { mutableStateOf("gallery") }

    BackHandler(enabled = screen != "camera") {
        screen = backTarget(screen, editorFrom, galleryFrom, viewerFrom)
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
        // 相册：点缩略图进**查看页**（E2-13）—— 不再直接进编辑器。E2-20 起带上整条扁平
        // 列表 + 点中的索引，查看页据此左右滑动切换。
        "gallery" -> GalleryScreen(
            onBack = { screen = backTarget("gallery", editorFrom, galleryFrom) },
            onOpenPhoto = { uris, index ->
                viewerUris = uris
                viewerIndex = index
                viewerFrom = "gallery"
                screen = "viewer"
            },
        )
        // 查看页：全屏原图 + 左右滑动切图 + 双指缩放 + 底栏五项。点「编辑」才进编辑器，并记下
        // "从查看页来的"；删除成功后回相册（那张已经不存在，留在查看页只会看到失效图）。
        // ★ P-3：底栏「胶片」→ 面板选一条预设 → **同样进编辑器**，但带上预设 id（直接看效果，
        // 不是保存副本 —— 用户 2026-10-11 拍板）。两条入口的返回语义完全一致（回本页同一张）。
        "viewer" -> PhotoViewScreen(
            uris = viewerUris,
            index = viewerIndex,
            onIndexChange = { viewerIndex = it }, // 滑到新的一张 ⇒ 路由层跟着走（编辑器回同一张）
            onBack = { screen = backTarget("viewer", editorFrom, galleryFrom, viewerFrom) },
            onEdit = { uri ->
                editorUri = uri
                editorFrom = "viewer"
                editorPresetId = null // 普通「编辑」入口：起点仍是全恒等基线（与 P-3 之前一致）
                screen = "editor"
            },
            onEditWithPreset = { uri, presetId ->
                editorUri = uri
                editorFrom = "viewer"
                editorPresetId = presetId
                screen = "editor"
            },
            onDeleted = { screen = backTarget("viewer", editorFrom, galleryFrom, viewerFrom) },
        )
        // 编辑器：uri 为 null 时 EditorScreen 显示失败态（见其 KDoc），不做额外拦截
        "editor" -> EditorScreen(
            uri = editorUri,
            initialPresetId = editorPresetId,
            onBack = { screen = backTarget("editor", editorFrom, galleryFrom) },
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
            // 胶卷位：有最近成片 ⇒ 直接看这张大图（单张列表；左右滑动没有邻居，
            // 那是相册入口的语义）；返回回相机。
            onOpenLastPhoto = { uri ->
                viewerUris = listOf(uri)
                viewerIndex = 0
                viewerFrom = "camera"
                screen = "viewer"
            },
            onRegisterVolume = onRegisterVolume,
        )
    }
}
