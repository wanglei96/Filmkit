package com.filmkit.app

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
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
import com.filmkit.app.ui.hasImagePermission
import com.filmkit.app.ui.queryPhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    // v0.12.74：查看页"处理中"态 —— 按快门后点左下角占位进来，等成片落盘原地变图。
    // 不阻塞相机：进的是独立页面，返回后可以继续构图。
    var viewerPending by rememberSaveable { mutableStateOf(false) }
    // 进入处理中时的 last_photo_uri 基线：只有**变化**才采纳，避免立刻显示上一张
    var viewerPendingBase by remember { mutableStateOf<String?>(null) }
    // 处理中 → 完成后原地变图：轮询 CameraController 写的 prefs（跨页面唯一可靠通道）。
    // 进的是独立页面，不阻塞相机；30s 超时退回相机。
    val navPrefs = context.getSharedPreferences("filmkit", 0)
    LaunchedEffect(screen, viewerPending) {
        if (screen != "viewer" || !viewerPending) return@LaunchedEffect
        var waited = 0L
        while (waited < 30_000L) {
            val u = navPrefs.getString("last_photo_uri", null)
            if (!u.isNullOrBlank() && u != viewerPendingBase) {
                // 与 onOpenLastPhoto 同一口径：查**整个相册列表**再定位索引。
                // 只塞一张会让 Pager 只有 1 页 —— 成片后首次从胶卷位点开大图左右滑不动，
                // 必须返回再进才行（用户 2026-11-13 报的 bug）。
                val list = withContext(Dispatchers.IO) {
                    runCatching { queryPhotos(context, hasImagePermission(context)).first }
                        .getOrDefault(emptyList()).map { it.uri }
                }
                val i = list.indexOf(u)
                viewerUris = if (i >= 0) list else listOf(u)
                viewerIndex = if (i >= 0) i else 0
                viewerPending = false
                return@LaunchedEffect
            }
            delay(200)
            waited += 200
        }
        viewerPending = false
        screen = "camera"
    }

    BackHandler(enabled = screen != "camera") {
        screen = backTarget(screen, editorFrom, galleryFrom, viewerFrom)
    }

    // 相册 / 查看页是**覆盖层**：盖在相机页之上，相机页不销毁 ⇒ 返回时预览不重建，
    // 不再黑 1~2s（整页替换会连 SurfaceView 一起销毁，重进要重开相机 + 重建 EGL）。
    val overlay = screen == "gallery" || screen == "viewer"
    // 覆盖层退场期间 screen 已经变了，内容要按"最后一次的覆盖页"渲染，否则退场会空白
    var overlayScreen by remember { mutableStateOf("gallery") }
    LaunchedEffect(screen) {
        if (screen == "gallery" || screen == "viewer") overlayScreen = screen
    }
    // 其余整页路由（设置/编辑器/日志…）仍是替换 + 单页淡入
    val pageAlpha = remember { Animatable(1f) }
    LaunchedEffect(screen) {
        if (screen != "camera" && !overlay) {
            pageAlpha.snapTo(0f)
            pageAlpha.animateTo(1f, tween(durationMillis = 200, easing = LinearEasing))
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ── 相机页：常驻底层（相册打开时也保留）──
        if (screen == "camera" || overlay) {
            CameraScreen(
                onOpenSettings = { screen = "settings" },
                onOpenGallery = {
                    galleryFrom = "camera"
                    screen = "gallery"
                },
                // 胶卷位：有最近成片 ⇒ 直接看这张大图，返回回相机。
                // ★ v0.12.35：还要能左右切图 —— 先把相册（与相册页同一条查询口径）查出来，
                // 把这张定位到它的索引上；查不到（未授权且不在本应用目录 / 刚被删）才退化成
                // 单张列表。查询在 IO 线程，本地 MediaStore 通常 <100ms。
                onOpenPendingPhoto = {
                    viewerPendingBase = context.getSharedPreferences("filmkit", 0)
                        .getString("last_photo_uri", null)
                    viewerPending = true
                    viewerUris = emptyList()
                    viewerIndex = 0
                    viewerFrom = "camera"
                    screen = "viewer"
                },
                onOpenLastPhoto = { uri ->
                    scope.launch {
                        val list = withContext(Dispatchers.IO) {
                            runCatching {
                                queryPhotos(context, hasImagePermission(context)).first
                            }.getOrDefault(emptyList()).map { it.uri }
                        }
                        val i = list.indexOf(uri)
                        // ★ 2026-11-13：**失效 uri 不进查看页**（否则进去就是"图片不存在"）。
                        // 相册删了那张、相机页的 state 还没来得及同步时会走到这里 ⇒ 兜底顶上
                        // 相册最新一张；连列表都空（未授权且本应用目录也空）才什么都不做。
                        if (i < 0 && list.isEmpty()) return@launch
                        viewerUris = list
                        viewerIndex = if (i >= 0) i else 0
                        viewerFrom = "camera"
                        viewerPending = false
                        screen = "viewer"
                    }
                },
                onRegisterVolume = onRegisterVolume,
            )
        }

        // ── 覆盖层：相册 / 大图，从底部滑上来（iOS 相册感），关闭滑下去 ──
        AnimatedVisibility(
            visible = overlay,
            enter = slideInVertically(initialOffsetY = { it / 10 }) + fadeIn(tween(220)),
            exit = slideOutVertically(targetOffsetY = { it / 10 }) + fadeOut(tween(200)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    // 占住命中路径：覆盖层空白处的触摸不能穿透到底下的相机页
                    .pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent() }
                    },
            ) {
                when (overlayScreen) {
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
                    // 查看页：全屏原图 + 左右滑动切图 + 双指缩放 + 底栏五项。点「编辑」才进编辑器
                    // ★ P-3：底栏「胶片」→ 面板选一条预设 → **同样进编辑器**，但带上预设 id
                    else -> PhotoViewScreen(
                        uris = viewerUris,
                        pending = viewerPending,
                        index = viewerIndex,
                        onIndexChange = { viewerIndex = it }, // 滑到新的一张 ⇒ 路由层跟着走
                        onBack = { screen = backTarget("viewer", editorFrom, galleryFrom, viewerFrom) },
                        onEdit = { uri ->
                            editorUri = uri
                            editorFrom = "viewer"
                            editorPresetId = null
                            screen = "editor"
                        },
                        onEditWithPreset = { uri, presetId ->
                            editorUri = uri
                            editorFrom = "viewer"
                            editorPresetId = presetId
                            screen = "editor"
                        },
                        // ★ 2026-11-13：删除当前这张后**留在查看页切到下一张**（用户要求），
                        // 不再直接退出。摘掉被删的索引即可 —— 后面的整体前移，Pager 的
                        // currentPage 原地显示的就是原来的下一张；删最后一张时把索引夹到
                        // 新的最后一张（PhotoViewScreen 里同步 scrollToPage）。删空才退出。
                        onDeleted = { removed ->
                            val left = viewerUris.filterIndexed { i, _ -> i != removed }
                            if (left.isEmpty()) {
                                // 一张不剩 ⇒ 原逻辑：清空并回来源页（gallery / camera）
                                viewerUris = emptyList()
                                viewerIndex = 0
                                screen = backTarget("viewer", editorFrom, galleryFrom, viewerFrom)
                            } else {
                                viewerUris = left
                                viewerIndex = removed.coerceAtMost(left.size - 1)
                            }
                        },
                        // 从相机页直接看大图时，"返回"只会回相机 ⇒ 顶栏必须有去相册的入口。
                        onOpenGallery = if (viewerFrom == "camera") {
                            {
                                galleryFrom = "camera"
                                screen = "gallery"
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        // ── 其余整页路由 ──
        if (screen != "camera" && !overlay) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = pageAlpha.value
                    translationY = (1f - pageAlpha.value) * 24.dp.toPx()
                },
            ) {
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
                }
            }
        }
    }
}
