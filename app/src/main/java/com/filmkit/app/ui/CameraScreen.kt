package com.filmkit.app.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import com.filmkit.app.EngineBridge
import com.filmkit.app.camera.CameraController
import com.filmkit.app.camera.RenderClient
import com.filmkit.app.kv
import com.filmkit.app.lut.LutLibrary
import com.filmkit.app.lut.LutPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * M 档专业拍摄界面（方向 C 定稿，v0.5.1）。
 * 顶部快捷设置区：闪光灯 / 实时直方图 / 快门声 / 菜单（构图线与水平仪移入菜单悬浮面板）。
 * 参数模型：EV / AE(Tv+ISO) / WB 独立 A/手动，拖刻度进手动、点 A 线性回自动。
 * 交互反馈：快门触感+黑闪+按钮脉冲、全局按压缩放、捏合变焦、音量键拍照。
 * 规范见 docs/开发计划.md「UI 设计规范」。
 */
@Composable
fun CameraScreen(
    onOpenSettings: () -> Unit = {},
    onRegisterVolume: ((() -> Unit)?) -> Unit = {},
) {
    val context = LocalContext.current
    val androidView = LocalView.current
    var hasPerm by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPerm = granted }

    LaunchedEffect(Unit) {
        if (!hasPerm) permLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPerm) {
        Column(
            Modifier
                .fillMaxSize()
                .background(FilmTheme.Body)
                .statusBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("胶片预览需要相机权限", color = FilmTheme.Cream)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("授权")
            }
        }
        return
    }

    val prefs = remember { context.getSharedPreferences("filmkit", 0) }
    val controller = remember { CameraController(context, RenderClient()) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var resumed by remember { mutableStateOf(true) }
    var lensChips by remember { mutableStateOf(emptyList<Int>()) } // 启用镜头等效焦段（变焦刻度）
    var presetIdx by rememberSaveable { mutableIntStateOf(0) } // 默认标准
    var focalBase by remember { mutableFloatStateOf(24f) } // 当前镜头等效焦距

    var selectedControl by rememberSaveable { mutableStateOf<String?>(null) } // AF/EXP/EV/WB
    var quickOpen by rememberSaveable { mutableStateOf(false) } // 一级悬浮窗（画幅+更多设置）
    var lutOpen by rememberSaveable { mutableStateOf(false) } // LUT 二级菜单
    var flashMode by rememberSaveable { mutableIntStateOf(0) }
    var histOn by rememberSaveable { mutableStateOf(prefs.getBoolean("histogram", false)) }
    // 实时直方图（M3）：开关开时 ~7fps 从渲染线程取显示域亮度分布（引擎端 256×96
    // 下采样 + 24KB 读回；主线程不阻塞，取值在 IO 线程，采样口径与成片 OETF 一致）
    var hist by remember { mutableStateOf<IntArray?>(null) }
    LaunchedEffect(histOn) {
        if (!histOn) {
            hist = null
            return@LaunchedEffect
        }
        while (true) {
            val a = withContext(Dispatchers.IO) { controller.pollHistogram() }
            if (a != null) hist = a
            delay(150)
        }
    }
    var gridOn by rememberSaveable { mutableStateOf(prefs.getBoolean("grid", false)) }
    var levelOn by rememberSaveable { mutableStateOf(prefs.getBoolean("level", false)) }
    // 过曝提示（斑马纹）：拍摄相关开关，按约定放快捷设置（不进设置页）
    var zebraOn by rememberSaveable { mutableStateOf(prefs.getBoolean("zebra", true)) }
    // 水平仪显示角（重力方向相对屏幕下方，-180..180 连续，低通平滑后）
    var levelRoll by remember { mutableFloatStateOf(0f) }
    var levelAligned by remember { mutableStateOf(false) } // 模 90° 任一轴水平即对齐（横竖屏均可）
    var soundOn by rememberSaveable { mutableStateOf(prefs.getBoolean("shutter_sound", true)) }

    // M 档（RAW 采集）：开 = 静拍走 RAW_SENSOR 引擎胶片链；与 DNG 底片开关解耦（§0-D6 更新）
    var rawOn by rememberSaveable { mutableStateOf(prefs.getBoolean("raw_mode", false)) }
    // 画幅持久化（prefs capture_aspect）：进出设置页会销毁重建 CameraScreen，
    // 纯内存状态会丢回 4:3（v0.5.21 真机实测）
    var aspect by rememberSaveable {
        mutableStateOf(prefs.getString("capture_aspect", null) ?: "4:3")
    }
    // A/手动状态持久化（重启不丢手动档），初始值读 prefs
    var evAuto by rememberSaveable { mutableStateOf(prefs.getBoolean("ev_auto", true)) }
    var aeAuto by rememberSaveable { mutableStateOf(prefs.getBoolean("ae_auto", true)) }
    var wbAuto by rememberSaveable { mutableStateOf(prefs.getBoolean("wb_auto", true)) }
    var iso by rememberSaveable { mutableIntStateOf(prefs.getInt("iso", 100)) }
    var shutterV by rememberSaveable { mutableFloatStateOf(prefs.getFloat("shutter_v", 0.75f)) }
    var ev by rememberSaveable { mutableStateOf(prefs.getFloat("ev", 0f)) }
    var wbK by rememberSaveable { mutableIntStateOf(prefs.getInt("wb_k", 5000)) }
    var mfOn by rememberSaveable { mutableStateOf(false) } // 手动对焦（会话内）
    var mfDiopter by rememberSaveable { mutableFloatStateOf(0f) }
    var focusMark by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var liveIso by remember { mutableIntStateOf(0) }
    var liveNs by remember { mutableLongStateOf(0L) }
    var liveK by remember { mutableIntStateOf(0) }
    var liveFocus by remember { mutableFloatStateOf(0f) }
    var boxW by remember { mutableIntStateOf(1) }
    var boxH by remember { mutableIntStateOf(1) }
    var captureTick by remember { mutableIntStateOf(0) } // 每次快门 +1，驱动黑闪与脉冲
    var zoomEquiv by remember { mutableFloatStateOf(24f) } // 捏合变焦目标（35mm 等效焦段）
    // 焦段条显示内容：focal = 焦段号（15/24/70），mult = 倍率（×0.6/×1.0/×2.9）。
    // 在悬浮菜单里切换；两种信息**不并存**（v0.6.8）
    var focalStrip by rememberSaveable { mutableStateOf(prefs.getString("focal_strip", "focal") ?: "focal") }
    var wheelActive by remember { mutableStateOf(false) } // 长按轮盘变焦进行中
    val wheelOn = remember { prefs.getBoolean("wheel_zoom", true) }
    val wheelMinE = (lensChips.minOrNull() ?: 15).toFloat()
    val wheelMaxE = (lensChips.maxOrNull() ?: 70).toFloat() * controller.maxZoom
    fun wheelFracOf(e: Float): Float =
        ((ln(e.coerceAtLeast(1f)) - ln(wheelMinE)) / (ln(wheelMaxE) - ln(wheelMinE))).coerceIn(0f, 1f)
    // 半圆轮盘滚动量程：对数全程对应 1.4 屏宽的横向拖动
    val wheelSpanPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() * 1.4f }
    var wheelFrac by remember { mutableFloatStateOf(0.5f) } // 轮盘对数位置 0..1
    var lastZoomSent by remember { mutableFloatStateOf(0f) }
    // 拖动与 fling 都只写 wheelFrac（手势 scope 是 restricted suspension，不能调外部 suspend）；
    // 变焦请求经 snapshotFlow 按帧节拍派生（触摸 120Hz → setZoomEquiv 每帧至多一次，
    // 微幅量化防 rebuildRepeating 刷屏）
    LaunchedEffect(wheelMinE, wheelMaxE) {
        snapshotFlow { wheelFrac }.collect { f ->
            if (!wheelActive) return@collect
            val ze = exp(ln(wheelMinE) + f * (ln(wheelMaxE) - ln(wheelMinE)))
            if (Math.abs(ze - lastZoomSent) > ze * 0.002f) {
                lastZoomSent = ze
                zoomEquiv = ze
                controller.setZoomEquiv(ze)
                focalBase = controller.currentLensEquiv()
            }
        }
    }
    var photoUri by remember { mutableStateOf<android.net.Uri?>(null) } // 最近成片
    var thumb by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    fun doCapture() {
        androidView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        captureTick++
        controller.capture()
    }

    // ── M4：内置 3D LUT（display 域，引擎接在输出 OETF 之后）──
    // 选中项持久化；解析在 IO 线程（35937 行 ≈ 数十毫秒），纹理随会话重建由 controller 重传。
    var lutId by rememberSaveable { mutableStateOf(prefs.getString("lut_id", null)) }
    var lutPending by remember { mutableStateOf<LutLibrary.Entry?>(null) }
    // LUT 强度（§6 节点 opacity）：只写 prefs，CameraController 每帧回写 FilmParams[34]
    var lutStrength by rememberSaveable { mutableFloatStateOf(prefs.getFloat("lut_strength", 1f)) }
    var lutTab by rememberSaveable { mutableIntStateOf(0) } // 选择页分类：0 = LUT，1 = 预设
    // M4-3 分类：默认品牌来自资产头部，用户可改归属 / 新建分类（存 prefs，不动资产）
    var lutCats by remember { mutableStateOf(LutLibrary.allCategories(context, prefs)) }
    var lutCatOf by remember {
        mutableStateOf(LutLibrary.entries(context).associate { it.id to LutLibrary.categoryOf(prefs, it) })
    }
    var lutCustomCats by remember { mutableStateOf(LutLibrary.customCategories(prefs)) }
    var lutFilter by rememberSaveable { mutableStateOf<String?>(null) } // null = 全部
    // 卡片预览图：启动时后台用固定测试图 × 各 LUT 生成一次并缓存（见 LutPreview），
    // 之后开面板只读缓存 —— 一次性生成而非实时渲染，省电
    var lutPreviews by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    LaunchedEffect(Unit) {
        lutPreviews = withContext(Dispatchers.IO) {
            LutLibrary.entries(context).mapNotNull { e ->
                LutLibrary.load(context, e)?.let { lut ->
                    LutPreview.load(context, e.id, lut)?.let { bmp -> e.id to bmp }
                }
            }.toMap()
        }
    }
    LaunchedEffect(Unit) {
        // 启动恢复：按 prefs 的选择重新加载（切镜头/重进相机后引擎纹理已被释放）
        lutId?.let { id -> LutLibrary.byId(context, id)?.let { lutPending = it } }
    }
    LaunchedEffect(lutPending) {
        val e = lutPending ?: return@LaunchedEffect
        val lut = withContext(Dispatchers.IO) { LutLibrary.load(context, e) }
        if (lut == null) {
            lutId = null
            prefs.edit().remove("lut_id").apply()
        } else {
            lutId = e.id
            prefs.edit().putString("lut_id", e.id).apply()
        }
        controller.setLut(lut)
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "app", "settings.lut",
            kv("id" to e.id, "title" to (lut?.title ?: "none"), "ok" to (lut != null)),
        )
        lutPending = null
    }

    // 启动即应用选中预设与持久化的手动档（WB 走引擎补偿、AE 走手动曝光）
    LaunchedEffect(Unit) {
        controller.setFilmParams(CameraPresets.byIndex(presetIdx))
        controller.setShutterSound(soundOn)
        if (!evAuto) controller.setEv(ev)
        if (!aeAuto) {
            controller.setManualExposure(true)
            controller.setIso(iso)
            controller.setExposureNs(10f.pow(5f + 4f * shutterV).roundToLong())
        }
        if (!wbAuto) controller.setWbK(wbK) // 补偿值由轮询按实时估计应用
    }
    DisposableEffect(Unit) {
        onRegisterVolume { doCapture() }
        onDispose { onRegisterVolume(null) }
    }

    DisposableEffect(Unit) {
        onDispose { controller.stop() }
    }

    // 水平仪：加速度计重力方向 φ=atan2(gx,-gy)。
    // 对齐 = φ 距 0/90/180/270 任一轴 <0.5°（竖握/横握/倒握均可对齐，模 90° 判定）；
    // 显示角连续 360°，按最短角差做低通平滑防跳变
    DisposableEffect(levelOn) {
        if (!levelOn) return@DisposableEffect onDispose { }
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        var smooth = 0f
        fun wrap180(a: Float): Float {
            var x = a
            while (x > 180f) x -= 360f
            while (x < -180f) x += 360f
            return x
        }
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                if (e.sensor.type != android.hardware.Sensor.TYPE_ACCELEROMETER) return
                val g = e.values
                val phi = Math.toDegrees(kotlin.math.atan2(g[0].toDouble(), -g[1].toDouble())).toFloat()
                smooth += wrap180(phi - smooth) * 0.22f // 低通：响应与防抖折中
                levelRoll = smooth
                // 与最近 90° 轴的偏差（phi≡0/90/180/270 时 d=45）
                var d = (phi + 45f) % 90f
                if (d < 0f) d += 90f
                val dev = kotlin.math.abs(d - 45f)
                // 滞回对齐：进入 0.4°、退出 1.2°——阈值附近抖动不会来回闪色
                levelAligned = if (levelAligned) dev < 1.2f else dev < 0.4f
            }

            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
        }
        sm.registerListener(
            listener,
            sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER),
            android.hardware.SensorManager.SENSOR_DELAY_GAME,
        )
        onDispose { sm.unregisterListener(listener) }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    controller.stop()
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    surface?.let { controller.start(it) }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            lensChips = controller.lensEquivs()
            focalBase = controller.currentLensEquiv()
            liveIso = controller.liveIso
            liveNs = controller.liveExposureNs
            liveK = controller.liveK
            liveFocus = controller.liveFocusDiopter
            // 手动 WB：补偿 = 目标K / 当前自动估计K（跟随估计移动，目标=估计时为 1）
            if (!wbAuto && liveK > 0) controller.updateWbCompensation(wbK, liveK)
            val newUri = controller.lastPhotoUri
            if (newUri != photoUri) photoUri = newUri
        }
    }

    // 镜头集合变化（设置页启停）时把当前焦段兜底回有效范围。
    // 勿在此重置 zoomEquiv = focalBase：跨镜头连续变焦（轮盘/捏合）中 focalBase 会先变为
    // 新镜头原生焦距，重置会让缩小方向从目标值直接跳回 1x（跳变根因，2026-09-20）
    LaunchedEffect(lensChips) {
        zoomEquiv = zoomEquiv.coerceIn(wheelMinE, wheelMaxE)
    }

    // 快门黑闪 + 快门钮脉冲
    val shutterAlpha = remember { Animatable(0f) }
    val shutterPulse = remember { Animatable(1f) }
    LaunchedEffect(captureTick) {
        if (captureTick == 0) return@LaunchedEffect
        shutterAlpha.snapTo(0.85f)
        shutterAlpha.animateTo(0f, tween(durationMillis = 190, easing = LinearEasing))
        shutterPulse.snapTo(0.86f)
        shutterPulse.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 500f))
    }

    // 最近成片缩略图（保存完成后 lastPhotoUri 才会更新，此时文件已就绪）
    LaunchedEffect(photoUri) {
        val uri = photoUri ?: return@LaunchedEffect
        val bmp = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.loadThumbnail(uri, android.util.Size(128, 128), null)
            } catch (_: Throwable) {
                null
            }
        }
        thumb = bmp?.asImageBitmap()
    }

    fun secText(ns: Long): String =
        if (ns <= 0) "--" else if (ns < 1_000_000_000L) "1/${(1_000_000_000.0 / ns).roundToInt()}"
        else "%.1fS".format(ns / 1_000_000_000.0)

    val shutterNs = 10f.pow(5f + 4f * shutterV).roundToLong()
    val maxIso = controller.maxAnalogIso
    // EV 补偿实际范围/步进来自 characteristics（部分设备不支持负补偿，UI 自适应）
    val (evLo, evHi, evStp) = controller.evInfo()
    // 取景窗口 = 所选画幅（WYSIWYG：预览 center-crop 与成片裁切一致）；
    // 比例随屏幕方向取向：竖屏 4:3 = 3:4 铺满宽度、16:9 = 9:16、XPAN = 24:65 竖长条
    val portrait = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_PORTRAIT
    val guideAspect = when (aspect) {
        "16:9" -> if (portrait) 9f / 16f else 16f / 9f
        "XPAN" -> if (portrait) 24f / 65f else 65f / 24f
        else -> if (portrait) 3f / 4f else 4f / 3f
    }
    // 对焦区域映射随显示旋转补偿（X300 sensor 90，竖屏 k=1）
    val winMgr = remember {
        context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
    }
    @Suppress("DEPRECATION")
    val displayRot = winMgr.defaultDisplay.rotation
    LaunchedEffect(displayRot) {
        controller.setOrientationStep(displayRot)
    }
    // 成片按同一比例居中裁切（与取景窗口一致）
    LaunchedEffect(guideAspect) {
        controller.setCaptureAspect(guideAspect)
    }

    // 底部不留白：只让出状态栏，内容延伸到手势条之下；悬浮窗浮于整机上层（不受取景框约束）
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(FilmTheme.Body)
                .statusBarsPadding(),
        ) {
        // ── 顶部快捷设置区：闪光灯 · 直方图 · 快门声 · 菜单 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopQuickIcon(
                icon = when (flashMode) {
                    2 -> Icons.Filled.FlashOn
                    1 -> Icons.Filled.FlashAuto
                    else -> Icons.Filled.FlashOff
                },
                tint = if (flashMode != 0) FilmTheme.Accent else FilmTheme.CreamDim,
                desc = "闪光灯",
            ) {
                flashMode = (flashMode + 1) % 3
                controller.setFlash(flashMode)
            }
            TopQuickText(
                text = "M",
                tint = if (rawOn) FilmTheme.Accent else FilmTheme.CreamDim,
                desc = "M 档（RAW 采集）",
            ) {
                rawOn = !rawOn
                prefs.edit().putBoolean("raw_mode", rawOn).apply()
                controller.restartSession() // 静拍流 YUV↔RAW 切换须重建会话
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "app", "quick.raw_mode",
                    "{\"on\":$rawOn}",
                )
            }
            TopQuickIcon(
                icon = Icons.Filled.BarChart,
                tint = if (histOn) FilmTheme.Accent else FilmTheme.CreamDim,
                desc = "实时直方图",
            ) {
                histOn = !histOn
                prefs.edit().putBoolean("histogram", histOn).apply()
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "app", "quick.histogram",
                    "{\"on\":$histOn}",
                )
            }
            TopQuickIcon(
                icon = if (soundOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                tint = if (soundOn) FilmTheme.Accent else FilmTheme.CreamDim,
                desc = "快门声音",
            ) {
                soundOn = !soundOn
                controller.setShutterSound(soundOn)
                prefs.edit().putBoolean("shutter_sound", soundOn).apply()
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "app", "quick.shutter_sound",
                    "{\"on\":$soundOn}",
                )
            }
            Spacer(Modifier.weight(1f))
            TopQuickIcon(
                icon = Icons.Filled.Menu,
                tint = if (quickOpen) FilmTheme.Accent else FilmTheme.Cream,
                desc = "菜单",
            ) { quickOpen = !quickOpen }
        }

        // ── 取景窗口 = 所选画幅（WYSIWYG），窗口外为机身黑，随屏幕方向适配 ──
        // weight(fill=false)：预览框按画幅内容自适应高度——4:3 不再吃掉多余空间
        // （快门/瓦片跟随上移、底部留白），16:9 需求高过可用空间时被约束不挤压其他 UI
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .aspectRatio(guideAspect)
                    .onSizeChanged { boxW = it.width; boxH = it.height },
            ) {
                AndroidView(
                    factory = { c ->
                        SurfaceView(c).apply {
                            var pinching = false
                            val pinch = ScaleGestureDetector(
                                c,
                                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                    override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                                        pinching = true
                                        return true
                                    }

                                    override fun onScale(d: ScaleGestureDetector): Boolean {
                                        // 目标为 35mm 等效焦段：跨过其它镜头等效焦距时自动切换物理镜头
                                        val minE = (lensChips.minOrNull() ?: focalBase.roundToInt()).toFloat()
                                        val maxE = (lensChips.maxOrNull() ?: focalBase.roundToInt()) * controller.maxZoom
                                        zoomEquiv = (zoomEquiv * d.scaleFactor).coerceIn(minE, maxE)
                                        controller.setZoomEquiv(zoomEquiv)
                                        return true
                                    }
                                },
                            )
                            // 触屏对焦 + 捏合变焦：SurfaceView 原生触摸（Compose 手势部分机型不送达）。
                            // 必须消费事件（true），否则 ACTION_UP 不会送达本视图，对焦永不触发
                            setOnTouchListener { v, e ->
                                pinch.onTouchEvent(e)
                                when (e.actionMasked) {
                                    MotionEvent.ACTION_POINTER_DOWN -> pinching = true
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        val wasPinching = pinching
                                        pinching = false
                                        if (e.actionMasked == MotionEvent.ACTION_UP && !wasPinching) {
                                            val nx = (e.x / width).coerceIn(0f, 1f)
                                            val ny = (e.y / height).coerceIn(0f, 1f)
                                            controller.focusAt(nx, ny)
                                            focusMark = nx to ny
                                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        }
                                    }
                                }
                                true
                            }
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) {
                                    surface = h.surface
                                    if (resumed) controller.start(h.surface)
                                }

                                override fun surfaceChanged(
                                    h: SurfaceHolder, format: Int, width: Int, height: Int,
                                ) {
                                    surface = h.surface
                                    if (resumed) controller.start(h.surface)
                                }

                                override fun surfaceDestroyed(h: SurfaceHolder) {
                                    controller.stop()
                                    surface = null
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // 快门黑闪（模拟机械快门，覆盖画面但保留机框线）
                if (shutterAlpha.value > 0.01f) {
                    Box(Modifier.matchParentSize().alpha(shutterAlpha.value).background(Color.Black))
                }

                // 圆角机框：窗口即成片范围，机身色环带遮出圆角 + 奶油细线勾边
                Canvas(Modifier.matchParentSize()) {
                    val inset = 5.dp.toPx()
                    val radius = 22.dp.toPx()
                    val frame = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(0f, 0f, size.width, size.height))
                        addRoundRect(
                            RoundRect(
                                Rect(inset, inset, size.width - inset, size.height - inset),
                                CornerRadius(radius, radius),
                            ),
                        )
                    }
                    drawPath(frame, FilmTheme.Body)
                    drawRoundRect(
                        color = FilmTheme.CreamDim.copy(alpha = 0.35f),
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - inset * 2, size.height - inset * 2),
                        cornerRadius = CornerRadius(radius, radius),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                    )
                }

                // 三分构图线
                if (gridOn) {
                    Canvas(Modifier.matchParentSize()) {
                        val c = FilmTheme.Cream.copy(alpha = 0.28f)
                        val w = 1.dp.toPx()
                        for (i in 1..2) {
                            drawLine(
                                c,
                                Offset(size.width * i / 3f, 0f),
                                Offset(size.width * i / 3f, size.height),
                                w,
                            )
                            drawLine(
                                c,
                                Offset(0f, size.height * i / 3f),
                                Offset(size.width, size.height * i / 3f),
                                w,
                            )
                        }
                    }
                }

                // 水平仪：中心圆环 + 两侧短刻度为固定参考；长指示线随重力方向
                // 连续旋转（360°，竖握/横握均适用）。对齐 = 强调色，不对齐 = 白色
                if (levelOn) {
                    Canvas(Modifier.matchParentSize()) {
                        val lineColor = if (levelAligned) FilmTheme.Accent else Color.White
                        val refColor = if (levelAligned) FilmTheme.Accent else FilmTheme.CreamDim
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val gap = 34.dp.toPx()   // 刻度距中心
                        val tick = 7.dp.toPx()   // 刻度半长
                        // 左右固定刻度
                        drawLine(refColor, Offset(cx - gap, cy - tick), Offset(cx - gap, cy + tick), 2.dp.toPx())
                        drawLine(refColor, Offset(cx + gap, cy - tick), Offset(cx + gap, cy + tick), 2.dp.toPx())
                        // 中心圆环（对齐时实心点）
                        if (levelAligned) {
                            drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(cx, cy))
                        } else {
                            drawCircle(
                                refColor,
                                radius = 5.dp.toPx(),
                                center = Offset(cx, cy),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                            )
                        }
                        // 旋转指示线：44% 屏宽、2.5dp 圆头，360° 连续；
                        // 取反：设备右倾（顺时针）时水平线相对屏幕为逆时针
                        val len = size.width * 0.44f
                        rotate(degrees = -levelRoll, pivot = Offset(cx, cy)) {
                            drawLine(
                                lineColor,
                                Offset(cx - len / 2f, cy),
                                Offset(cx + len / 2f, cy),
                                2.5.dp.toPx(),
                                StrokeCap.Round,
                            )
                        }
                    }
                }

                // 实时直方图（M3）：显示域亮度分布（0=黑 左，255=白 右），
                // 峰值开方压缩——大片同色（天空/墙面）的尖峰不会把其余分布压平
                if (histOn) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 14.dp, top = 12.dp)
                            .size(116.dp, 44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(FilmTheme.Panel),
                    ) {
                        val h = hist
                        if (h == null) {
                            Text(
                                "直方图…",
                                color = FilmTheme.CreamDim,
                                fontSize = 9.sp,
                                fontFamily = FilmTheme.Mono,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                                var mx = 1
                                for (v in h) if (v > mx) mx = v
                                val bw = size.width / h.size
                                for (i in h.indices) {
                                    if (h[i] <= 0) continue
                                    val f = sqrt(h[i].toFloat() / mx)
                                    drawRect(
                                        color = FilmTheme.Accent,
                                        topLeft = Offset(i * bw, size.height * (1f - f)),
                                        size = Size(bw + 0.6f, size.height * f),
                                    )
                                }
                            }
                        }
                    }
                }

                // 对焦指示框（按预览比例定位；半径用 dp→px 换算，硬编码像素会让圆心偏离点击点）
                focusMark?.let { (fx, fy) ->
                    LaunchedEffect(fx, fy) {
                        delay(700)
                        focusMark = null
                    }
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (fx * boxW).roundToInt() - 28.dp.roundToPx(),
                                    (fy * boxH).roundToInt() - 28.dp.roundToPx(),
                                )
                            }
                            .size(56.dp)
                            .border(1.5.dp, Color.White, CircleShape),
                    )
                }

                // 变焦刻度 + ✦ LUT 入口：透明底叠预览下缘；长按弹轮盘变焦（设置可关）
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 10.dp, end = 10.dp, bottom = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .then(
                            if (wheelOn) {
                                Modifier.pointerInput(wheelMinE, wheelMaxE, wheelSpanPx) {
                                    // 长按（450ms）进入半圆轮盘，横向拖动沿弧滚动
                                    // （左滑放大/右滑缩小，刻度跟手）；松手按惯性衰减，
                                    // fling 停止后收起轮盘。Initial pass 先于内层横滚消费事件，
                                    // 轮盘滚动时压住焦段条横滚；指针状态自跟踪（跨 Compose 版本稳定）
                                    while (true) {
                                        val flingV = awaitPointerEventScope {
                                            var downId: androidx.compose.ui.input.pointer.PointerId? = null
                                            var lastX = 0f
                                            var lastT = 0L
                                            var downAt = 0L
                                            var vel = 0f
                                            var inWheel = false
                                            while (true) {
                                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                                val c = ev.changes.firstOrNull() ?: continue
                                                if (downId == null) {
                                                    if (c.pressed) {
                                                        downId = c.id
                                                        lastX = c.position.x
                                                        lastT = c.uptimeMillis
                                                        downAt = lastT
                                                        vel = 0f
                                                        inWheel = false
                                                    }
                                                } else if (c.id == downId) {
                                                    if (!c.pressed) {
                                                        downId = null
                                                        if (inWheel) {
                                                            inWheel = false
                                                            break // 返回惯性速度，fling 在 scope 外跑
                                                        }
                                                    } else if (inWheel || c.uptimeMillis - downAt > 450L) {
                                                        if (!inWheel) {
                                                            inWheel = true
                                                            wheelActive = true
                                                            lastZoomSent = 0f
                                                            wheelFrac = wheelFracOf(zoomEquiv)
                                                        }
                                                        c.consume()
                                                        val dx = c.position.x - lastX
                                                        val dt = (c.uptimeMillis - lastT).coerceAtLeast(1L)
                                                        lastX = c.position.x
                                                        lastT = c.uptimeMillis
                                                        wheelFrac = (wheelFrac - dx / wheelSpanPx).coerceIn(0f, 1f)
                                                        val instV = -dx / wheelSpanPx * 1000f / dt // frac/s
                                                        vel = vel * 0.65f + instV * 0.35f
                                                    }
                                                }
                                            }
                                            vel
                                        }
                                        // 惯性滚动：指数衰减（τ≈220ms），到端点即停
                                        if (kotlin.math.abs(flingV) > 0.08f) {
                                            var v = flingV
                                            var lastT = android.os.SystemClock.uptimeMillis()
                                            while (kotlin.math.abs(v) > 0.015f) {
                                                withFrameNanos { }
                                                val now = android.os.SystemClock.uptimeMillis()
                                                val dt = (now - lastT).coerceAtLeast(1L)
                                                lastT = now
                                                val next = wheelFrac + v * dt / 1000f
                                                wheelFrac = next.coerceIn(0f, 1f)
                                                if (next <= 0f || next >= 1f) break
                                                v *= exp(-dt / 220f)
                                            }
                                        }
                                        wheelActive = false
                                    }
                                }
                            } else {
                                Modifier
                            },
                        )
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 可滚动部分（焦段 / 倍率 + 分隔线）：✦ LUT 入口紧随其后、不参与滚动
                    // —— v0.6.7 修复：切长焦后焦段变多会把 ✦ 顶出屏幕
                    Row(
                        Modifier
                            // fill = false：内容窄时按内容宽 —— 整行居中、✦ 紧邻焦段条；
                            // 内容超出可用宽时才占满并内部横滚（v0.6.8）
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(16.dp))
                            .horizontalScroll(rememberScrollState(), enabled = !wheelActive),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 焦段条 = 启用镜头原生焦段 + 自定义焦段（≤3 个，设置页维护），总计 ≤6。
                        // 标签按悬浮菜单的「焦段条显示」切换：焦段号 或 倍率；
                        // 不再额外显示当前倍率 chip —— 两种信息不并存（v0.6.8）
                        val baseE = remember(lensChips) { controller.mainEquiv() }
                        val customFocals = remember {
                            prefs.getString("custom_focals", "")?.split(",")?.mapNotNull { it.toIntOrNull() }
                                ?.sorted() ?: emptyList<Int>()
                        }
                        val chips = (lensChips + customFocals).distinct().sorted().take(6)
                        chips.forEach { f ->
                            val isNative = lensChips.contains(f)
                            FilmChip(
                                selected = Math.abs(zoomEquiv - f) < 2f,
                                label = if (focalStrip == "mult") {
                                    "×%.1f".format(f / baseE.coerceAtLeast(1f))
                                } else {
                                    "$f"
                                },
                                transparent = true,
                                fontSize = 10.sp,
                                onClick = {
                                    zoomEquiv = f.toFloat()
                                    if (isNative) controller.setFocal(f) else controller.setZoomEquiv(f.toFloat())
                                },
                            )
                        }
                        Box(
                            Modifier
                                .padding(horizontal = 6.dp)
                                .width(1.dp)
                                .height(16.dp)
                                .background(FilmTheme.ChipBorder),
                        )
                    }
                    Column(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .pressScale(onTap = { lutOpen = !lutOpen }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "✦",
                            color = if (lutOpen) FilmTheme.Accent else FilmTheme.Cream,
                            fontSize = 17.sp,
                        )
                        // 非默认预设激活时的小圆点提示
                        Box(
                            Modifier
                                .padding(top = 1.dp)
                                .size(3.dp)
                                .alpha(if (presetIdx != 0) 1f else 0f)
                                .background(FilmTheme.Accent, CircleShape),
                        )
                    }
                }

                // 半圆变焦轮盘：长按进入，横向拖动沿弧滚动；指针固定弧顶，
                // 刻度 = 原生焦段（带 mm 副标签）+ 数码档，对数映射与拖动一致
            if (wheelActive) {
                ZoomWheel(
                    frac = wheelFrac,
                    equiv = zoomEquiv,
                    minE = wheelMinE,
                    maxE = wheelMaxE,
                    mainE = controller.mainEquiv(),
                    natives = lensChips,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 64.dp),
                )
            }

            // 参数面板：全局单实例，状态驱动显隐（焦段条上方展开）
                androidx.compose.animation.AnimatedVisibility(
                    visible = selectedControl != null,
                    enter = FilmMotion.panelEnter,
                    exit = FilmMotion.panelExit,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = 58.dp),
                ) {
                    ControlPanel(
                        ctrl = selectedControl,
                        maxDiopter = controller.maxDiopter,
                        liveFocusD = liveFocus,
                        mfOn = mfOn,
                        mfDiopter = mfDiopter,
                        onMfDrag = { f ->
                            mfOn = true
                            mfDiopter = f * controller.maxDiopter
                            controller.setManualFocus(true)
                            controller.setFocusDiopter(mfDiopter)
                        },
                        onMfAuto = {
                            mfOn = false
                            controller.setManualFocus(false)
                        },
                        evAuto = evAuto,
                        aeAuto = aeAuto,
                        wbAuto = wbAuto,
                        ev = ev,
                        shutterV = shutterV,
                        iso = iso,
                        wbK = wbK,
                        liveNs = liveNs,
                        liveIso = liveIso,
                        liveK = liveK,
                        maxIso = maxIso,
                        evLo = evLo,
                        evHi = evHi,
                        evStp = evStp,
                        onEvDrag = { v ->
                            evAuto = false
                            ev = v
                            controller.setEv(v)
                            prefs.edit().putBoolean("ev_auto", false).putFloat("ev", v).apply()
                        },
                        onEvAuto = {
                            evAuto = true
                            ev = 0f
                            controller.setEv(0f)
                            prefs.edit().putBoolean("ev_auto", true).putFloat("ev", 0f).apply()
                        },
                        onTvDrag = { f ->
                            // AE OFF 会同时锁快门与 ISO：从 A 进入时把另一参数冻结在当前实测值
                            if (aeAuto && liveIso > 0) {
                                iso = liveIso.coerceIn(100, maxIso)
                                prefs.edit().putInt("iso", iso).apply()
                            }
                            aeAuto = false
                            shutterV = f
                            controller.setManualExposure(true)
                            controller.setExposureNs(10f.pow(5f + 4f * f).roundToLong())
                            prefs.edit().putBoolean("ae_auto", false).putFloat("shutter_v", f).apply()
                        },
                        onIsoDrag = { i ->
                            if (aeAuto && liveNs > 0) {
                                val nsF = liveNs.coerceIn(100_000L, 1_000_000_000L).toFloat()
                                shutterV = ((log10(nsF) - 5f) / 4f).coerceIn(0f, 1f)
                                prefs.edit().putFloat("shutter_v", shutterV).apply()
                            }
                            aeAuto = false
                            iso = i
                            controller.setManualExposure(true)
                            controller.setIso(i)
                            prefs.edit().putBoolean("ae_auto", false).putInt("iso", i).apply()
                        },
                        onAeAuto = {
                            aeAuto = true
                            controller.setManualExposure(false)
                            prefs.edit().putBoolean("ae_auto", true).apply()
                        },
                        onWbDrag = { k ->
                            wbAuto = false
                            wbK = k
                            controller.setWbK(k)
                            if (liveK > 0) controller.updateWbCompensation(k, liveK)
                            prefs.edit().putBoolean("wb_auto", false).putInt("wb_k", k).apply()
                        },
                        onWbAuto = {
                            wbAuto = true
                            controller.setAutoWb() // 清引擎补偿，回到 HAL AWB 原样
                            prefs.edit().putBoolean("wb_auto", true).apply()
                        },
                        secText = ::secText,
                    )
                }

                // ── LUT / 预设选择页已移到整机层：底部弹出（见下方 LutSheet）──

                // ── 一级悬浮窗移至整机层（锚定屏幕右上、宽度随屏幕）──
            }
        }

        // ── 控制瓦片：单行紧凑，预览框下方、快门上方（v0.5.26 移出预览框）──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            fun toggle(c: String) {
                androidView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                selectedControl = if (selectedControl == c) null else c
            }
            val expActive = selectedControl == "EXP"
            ControlTile(
                value = if (evAuto) "+0.0" else (if (ev >= 0f) "+" else "") + "%.1f".format(ev),
                label = "EV",
                state = when {
                    selectedControl == "EV" -> TileState.ACTIVE
                    evAuto -> TileState.AUTO
                    else -> TileState.MANUAL
                },
                onTap = { toggle("EV") },
            )
            ControlTile(
                value = if (aeAuto) (if (liveNs > 0) secText(liveNs) else "--") else secText(shutterNs),
                label = "Tv",
                state = when {
                    expActive -> TileState.ACTIVE
                    aeAuto -> TileState.AUTO
                    else -> TileState.MANUAL
                },
                onTap = { toggle("EXP") },
            )
            ControlTile(
                value = if (aeAuto) (if (liveIso > 0) "$liveIso" else "--") else "$iso",
                label = "ISO",
                state = when {
                    expActive -> TileState.ACTIVE
                    aeAuto -> TileState.AUTO
                    else -> TileState.MANUAL
                },
                onTap = { toggle("EXP") },
            )
            ControlTile(
                value = if (mfOn) {
                    fmtDist(mfDiopter)
                } else if (liveFocus > 0.01f) {
                    fmtDist(liveFocus)
                } else {
                    "AUTO"
                },
                label = "AF",
                state = when {
                    selectedControl == "AF" -> TileState.ACTIVE
                    mfOn -> TileState.MANUAL
                    else -> TileState.AUTO
                },
                onTap = { toggle("AF") },
            )
            ControlTile(
                value = if (wbAuto) (if (liveK > 0) "${liveK}K" else "AUTO") else "${wbK}K",
                label = "WB",
                state = when {
                    selectedControl == "WB" -> TileState.ACTIVE
                    wbAuto -> TileState.AUTO
                    else -> TileState.MANUAL
                },
                onTap = { toggle("WB") },
            )
        }

        // ── 底部：胶卷位（最近样张）· 快门 · 预留翻转位 ──
        // navigationBarsPadding + 加大底距：控制瓦片移入预览框后快门整体上移，
        // 单手更好按（v0.5.22/24 反馈）
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 2.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val t = thumb
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FilmTheme.ChipBg)
                    .alpha(if (t == null) 0.5f else 1f),
                contentAlignment = Alignment.Center,
            ) {
                if (t != null) {
                    Image(
                        bitmap = t,
                        contentDescription = "最近样张",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("▦", color = FilmTheme.Cream, fontSize = 20.sp)
                }
            }
            Box(
                Modifier
                    .size(72.dp)
                    .graphicsLayer {
                        scaleX = shutterPulse.value
                        scaleY = shutterPulse.value
                    }
                    .pressScale(onTap = { doCapture() }, pressedScale = 0.94f)
                    .clip(CircleShape)
                    .border(4.dp, FilmTheme.Cream, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(FilmTheme.Cream),
                )
            }
            Spacer(Modifier.size(44.dp))
        }
        }

        // ── LUT / 预设选择页：底部弹出（浮于整机上层；点面板外任意处收起）──
        if (lutOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { lutOpen = false } },
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = lutOpen,
            enter = FilmMotion.panelEnter,
            exit = FilmMotion.panelExit,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            LutSheet(
                tab = lutTab,
                onTab = { lutTab = it },
                presetIdx = presetIdx,
                lutId = lutId,
                lutStrength = lutStrength,
                previews = lutPreviews,
                cats = lutCats,
                catOf = lutCatOf,
                filter = lutFilter,
                onFilter = { lutFilter = it },
                customCats = lutCustomCats,
                onNewCat = { name ->
                    LutLibrary.addCategory(prefs, name)
                    lutCats = LutLibrary.allCategories(context, prefs)
                    lutCustomCats = LutLibrary.customCategories(prefs)
                },
                onDelCat = { c ->
                    LutLibrary.removeCategory(context, prefs, c)
                    lutCats = LutLibrary.allCategories(context, prefs)
                    lutCustomCats = LutLibrary.customCategories(prefs)
                    lutCatOf = LutLibrary.entries(context).associate { it.id to LutLibrary.categoryOf(prefs, it) }
                    if (lutFilter == c) lutFilter = null
                },
                onMoveCat = { e, c ->
                    LutLibrary.setCategory(prefs, e.id, c)
                    lutCatOf = lutCatOf + (e.id to c)
                    lutCats = LutLibrary.allCategories(context, prefs)
                },
                onStrength = { v ->
                    lutStrength = v
                    prefs.edit().putFloat("lut_strength", v).apply()
                },
                onPick = { i ->
                    presetIdx = i
                    controller.setFilmParams(CameraPresets.byIndex(i))
                },
                onPickLut = { e ->
                    if (e == null) {
                        // 关闭（A/B 通路）：直接下发，无需解析
                        lutId = null
                        controller.setLut(null)
                        prefs.edit().remove("lut_id").apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.lut",
                            kv("id" to "none", "title" to "none", "ok" to true),
                        )
                    } else {
                        lutPending = e
                    }
                },
                onClose = { lutOpen = false },
            )
        }

        // ── 一级悬浮窗：锚定屏幕右上、宽度随屏幕（浮于整机上层，点任意处收起）──
        if (quickOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { quickOpen = false } },
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = quickOpen,
            enter = FilmMotion.dropEnter,
            exit = FilmMotion.dropExit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                .fillMaxWidth(),
        ) {
            QuickDropdown(
                aspect = aspect,
                focalStrip = focalStrip,
                onFocalStrip = { m ->
                    focalStrip = m
                    prefs.edit().putString("focal_strip", m).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "quick.focal_strip",
                        kv("mode" to m),
                    )
                },
                onAspect = { a ->
                    aspect = a
                    prefs.edit().putString("capture_aspect", a).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "quick.aspect",
                        kv("ratio" to a),
                    )
                    // 成片同步居中裁切（setCaptureAspect，与取景窗口一致）
                },
                gridOn = gridOn,
                onGrid = {
                    gridOn = !gridOn
                    prefs.edit().putBoolean("grid", gridOn).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "quick.grid",
                        "{\"on\":$gridOn}",
                    )
                },
                levelOn = levelOn,
                onLevel = {
                    levelOn = !levelOn
                    prefs.edit().putBoolean("level", levelOn).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "quick.level",
                        "{\"on\":$levelOn}",
                    )
                },
                zebraOn = zebraOn,
                onZebra = {
                    zebraOn = !zebraOn
                    prefs.edit().putBoolean("zebra", zebraOn).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "quick.zebra",
                        "{\"on\":$zebraOn}",
                    )
                },
                onMoreSettings = {
                    quickOpen = false
                    onOpenSettings()
                },
            )
        }
    }
}

/**
 * 按压缩放 + 波纹点击（规范：自绘可点元素一律用本修饰符，保证「按下有反应」）。
 * 仅供 @Composable 上下文使用。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.pressScale(
    pressedScale: Float = 0.92f,
    onTap: () -> Unit,
    onLongTap: (() -> Unit)? = null,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s = if (pressed) pressedScale else 1f
    return this
        .graphicsLayer {
            scaleX = s
            scaleY = s
        }
        .then(
            if (onLongTap == null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = ripple(bounded = false),
                    onClick = onTap,
                )
            } else {
                Modifier.combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(bounded = false),
                    onClick = onTap,
                    onLongClick = onLongTap,
                )
            },
        )
}

/** 顶部快捷区图标钮：40dp 点击区，强调色=开启态 */
@Composable
private fun TopQuickIcon(icon: ImageVector, tint: Color, desc: String, onTap: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .pressScale(onTap = onTap, pressedScale = 0.85f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(21.dp))
    }
}

/** 顶部快捷区文字钮（M 档）：40dp 点击区，强调色=开启态 */
@Composable
private fun TopQuickText(text: String, tint: Color, desc: String, onTap: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .pressScale(onTap = onTap, pressedScale = 0.85f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = tint,
            fontFamily = FilmTheme.Mono,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clearAndSetSemantics { contentDescription = desc },
        )
    }
}

private enum class TileState { AUTO, MANUAL, ACTIVE }

/** 底部控制瓦片：自动挡实时读数（次级色）、手动设定值（奶油白）、ACTIVE 强调色+下划线 */
@Composable
private fun ControlTile(value: String, label: String, state: TileState, onTap: () -> Unit) {
    val color = when (state) {
        TileState.AUTO -> FilmTheme.CreamDim
        TileState.MANUAL -> FilmTheme.Cream
        TileState.ACTIVE -> FilmTheme.Accent
    }
    Column(
        Modifier
            .width(50.dp)
            .pressScale(onTap = onTap, pressedScale = 0.9f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            color = color,
            fontSize = 12.sp,
            fontFamily = FilmTheme.Mono,
            maxLines = 1,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            label,
            color = color,
            fontSize = 9.sp,
            fontFamily = FilmTheme.Mono,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .size(width = 16.dp, height = 2.dp)
                .background(if (state == TileState.ACTIVE) FilmTheme.Accent else Color.Transparent),
        )
    }
}

/** 参数面板（单实例）：AF（MF 屈光度）/ EXP（SEC+ISO，共用 AE 组）/ EV / WB，统一 ParamRow 风格 */
@Composable
private fun ControlPanel(
    ctrl: String?,
    maxDiopter: Float,
    liveFocusD: Float,
    evLo: Float,
    evHi: Float,
    evStp: Float,
    mfOn: Boolean,
    mfDiopter: Float,
    onMfDrag: (Float) -> Unit,
    onMfAuto: () -> Unit,
    evAuto: Boolean,
    aeAuto: Boolean,
    wbAuto: Boolean,
    ev: Float,
    shutterV: Float,
    iso: Int,
    wbK: Int,
    liveNs: Long,
    liveIso: Int,
    liveK: Int,
    maxIso: Int,
    onEvDrag: (Float) -> Unit,
    onEvAuto: () -> Unit,
    onTvDrag: (Float) -> Unit,
    onIsoDrag: (Int) -> Unit,
    onAeAuto: () -> Unit,
    onWbDrag: (Int) -> Unit,
    onWbAuto: () -> Unit,
    secText: (Long) -> String,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(FilmTheme.Panel)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        when (ctrl) {
            "AF" -> {
                // 手动对焦：屈光度滑杆，刻度为对焦距离（HAL 上报 0 时控制器用 10D 试值）；
                // A=连续自动对焦，滑杆停在实测对焦距离（此前误显示 ∞ = 屈光度 0）
                val maxD = maxDiopter
                val d = if (mfOn) mfDiopter else liveFocusD.coerceAtLeast(0f)
                ParamRow(
                    label = "MF",
                    auto = !mfOn,
                    onAuto = onMfAuto,
                    value = if (mfOn || liveFocusD > 0.01f) fmtDist(d) else "AUTO",
                    fraction = (d / maxD).coerceIn(0f, 1f),
                    ticks = ticksDiopter(maxD),
                    onFraction = onMfDrag,
                )
            }
            "EXP" -> {
                // A 时滑杆停在实测值并跟随测光；拖动即进手动
                val curNs = if (aeAuto) {
                    if (liveNs > 0) liveNs else 10_000_000L
                } else {
                    10f.pow(5f + 4f * shutterV).roundToLong()
                }.coerceIn(100_000L, 1_000_000_000L)
                val tvF = ((log10(curNs.toFloat()) - 5f) / 4f).coerceIn(0f, 1f)
                ParamRow(
                    label = "SEC",
                    auto = aeAuto,
                    onAuto = onAeAuto,
                    value = secText(curNs),
                    fraction = tvF,
                    ticks = ticksTv,
                    onFraction = onTvDrag,
                )
                Spacer(Modifier.height(2.dp))
                val curIso = if (aeAuto) liveIso.coerceIn(100, maxIso) else iso
                val lnMax = ln(maxIso / 100f)
                val isoF = if (lnMax > 0f && curIso >= 100) (ln(curIso / 100f) / lnMax).coerceIn(0f, 1f) else 0f
                ParamRow(
                    label = "ISO",
                    auto = aeAuto,
                    onAuto = onAeAuto,
                    value = "$curIso",
                    fraction = isoF,
                    ticks = ticksIso(maxIso),
                    // 刻度比例 → ISO 值（对数反演），此前误把比例直接取整导致 ISO 无法拖动
                    onFraction = { f ->
                        val v = if (lnMax > 0f) {
                            (100f * exp(f * lnMax)).roundToInt().coerceIn(100, maxIso)
                        } else {
                            100
                        }
                        onIsoDrag(v)
                    },
                )
            }
            "EV" -> {
                // 范围/步进取自 HAL 实际能力（此前写死 ±4，部分设备负补偿无效）
                val span = (evHi - evLo).coerceAtLeast(0.5f)
                val f = ((ev - evLo) / span).coerceIn(0f, 1f)
                ParamRow(
                    label = "EV",
                    auto = evAuto,
                    onAuto = onEvAuto,
                    value = (if (ev >= 0f) "+" else "") + "%.1f".format(ev),
                    fraction = f,
                    ticks = evTicks(evLo, evHi),
                    onFraction = { fr ->
                        val raw = evLo + fr * span
                        onEvDrag((raw / evStp).roundToInt() * evStp)
                    },
                )
            }
            "WB" -> {
                val curK = if (wbAuto) (if (liveK > 0) liveK else 5000) else wbK
                val f = ((curK - 2500f) / 5000f).coerceIn(0f, 1f)
                ParamRow(
                    label = "WB",
                    auto = wbAuto,
                    onAuto = onWbAuto,
                    value = "${curK}K",
                    fraction = f,
                    ticks = ticksWb,
                    // 刻度比例 → K 值（此前误把比例直接取整导致 WB 无法拖动）
                    onFraction = { f -> onWbDrag((2500f + 5000f * f).roundToInt()) },
                )
            }
        }
    }
}

/** 一行参数：A 片 + 标签 + 自适应刻度滑条 + 值（统一 EV 行风格） */
@Composable
private fun ParamRow(
    label: String,
    auto: Boolean,
    onAuto: () -> Unit,
    value: String,
    fraction: Float,
    ticks: List<Pair<Float, String>>,
    onFraction: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AChip(active = auto, onTap = onAuto)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = FilmTheme.Accent,
            fontSize = 11.sp,
            fontFamily = FilmTheme.Mono,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(32.dp),
        )
        TickSlider(fraction, ticks, onFraction, Modifier.weight(1f))
        Text(
            value,
            color = FilmTheme.Cream,
            fontSize = 11.sp,
            fontFamily = FilmTheme.Mono,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp),
        )
    }
}

/** A(自动)片：激活=强调色底深字；手动态描边灰，点按回自动 */
@Composable
private fun AChip(active: Boolean, onTap: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .pressScale(onTap = onTap, pressedScale = 0.85f)
            .clip(CircleShape)
            .background(if (active) FilmTheme.Accent else Color.Transparent)
            .border(1.dp, if (active) FilmTheme.Accent else FilmTheme.ChipBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "A",
            color = if (active) FilmTheme.OnAccent else FilmTheme.CreamDim,
            fontSize = 13.sp,
            fontFamily = FilmTheme.Mono,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 刻度滑条：活动轨/刻度线用强调色，刻度标签随参数传入（自适应刻度）。
 * 非拖动变化（点 A 回自动、测光实时移动）经 260ms 线性过渡，拖动即时跟手。
 */
@Composable
private fun TickSlider(
    fraction: Float,
    ticks: List<Pair<Float, String>>,
    onFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var dragging by remember { mutableStateOf(false) }
    val animF by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (dragging) snap() else tween(durationMillis = 260, easing = LinearEasing),
        label = "tickFraction",
    )
    Canvas(
        modifier
            .height(46.dp)
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    onFraction((off.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    onFraction((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val w = size.width
        val trackY = size.height * 0.32f
        val stroke = 3.dp.toPx()
        drawLine(FilmTheme.ChipBorder, Offset(0f, trackY), Offset(w, trackY), stroke, StrokeCap.Round)
        if (animF > 0f) {
            drawLine(
                FilmTheme.Accent,
                Offset(0f, trackY),
                Offset(animF * w, trackY),
                stroke,
                StrokeCap.Round,
            )
        }
        ticks.forEach { (f, label) ->
            drawLine(
                FilmTheme.Accent.copy(alpha = 0.7f),
                Offset(f * w, trackY - 5.dp.toPx()),
                Offset(f * w, trackY + 5.dp.toPx()),
                1.5.dp.toPx(),
            )
            val tr = measurer.measure(
                label,
                TextStyle(fontSize = 9.sp, color = FilmTheme.CreamDim, fontFamily = FilmTheme.Mono),
            )
            val x = (f * w - tr.size.width / 2f).coerceIn(0f, w - tr.size.width)
            drawText(tr, topLeft = Offset(x, trackY + 6.dp.toPx()))
        }
        drawRoundRect(
            FilmTheme.Cream,
            topLeft = Offset(animF * w - 3.dp.toPx(), trackY - 10.dp.toPx()),
            size = Size(6.dp.toPx(), 20.dp.toPx()),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
        )
    }
}

/** EV 刻度：范围随 HAL 实际能力（5 个等分点） */
private fun evTicks(lo: Float, hi: Float): List<Pair<Float, String>> =
    listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { f ->
        val v = lo + f * (hi - lo)
        f to ((if (v >= 0f) "+" else "") + "%.1f".format(v))
    }

private val ticksTv = listOf(
    0.25f to "1/1000",
    0.4f to "1/250",
    0.555f to "1/60",
    0.706f to "1/15",
    0.85f to "1/4",
    1f to "1S",
)

private val ticksWb = listOf(0.1f to "3000K", 0.5f to "5000K", 0.9f to "7000K")

/** 屈光度 → 对焦距离显示（∞ / 米 / 厘米） */
private fun fmtDist(diopter: Float): String =
    if (diopter <= 0.01f) "∞"
    else {
        val cm = 100f / diopter
        if (cm >= 100f) "%.1fm".format(cm / 100f) else "%.0fcm".format(cm)
    }

private fun ticksDiopter(maxD: Float): List<Pair<Float, String>> =
    listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { f -> f to fmtDist(f * maxD) }

/** ISO 刻度：对数（相机 ISO 感光度按档位），端点对齐实际量程 */
private fun ticksIso(maxIso: Int): List<Pair<Float, String>> {
    val stops = listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800).filter { it <= maxIso }
        .toMutableList()
    if (stops.isEmpty()) return listOf(0f to "100", 1f to "$maxIso")
    if (stops.last() != maxIso) stops.add(maxIso)
    val lnMax = ln(maxIso / 100f)
    if (lnMax <= 0f) return listOf(0f to "100", 1f to "$maxIso")
    return stops.map { v -> (ln(v / 100f) / lnMax).coerceIn(0f, 1f) to "$v" }
}

/**
 * LUT / 预设选择页（M4）：底部弹出，参考专业相机 App 的「分类 pill + 横向卡片」布局。
 * 面板外点击关闭由整机层的拦截层负责；这里只管内容与交互。
 * 选中项**不自动收起**面板 —— 便于连续切换、当场比较风格。
 */
@Composable
private fun LutSheet(
    tab: Int,
    onTab: (Int) -> Unit,
    presetIdx: Int,
    lutId: String?,
    lutStrength: Float,
    previews: Map<String, Bitmap>,
    cats: List<String>,
    catOf: Map<String, String>,
    filter: String?,
    onFilter: (String?) -> Unit,
    customCats: List<String>,
    onNewCat: (String) -> Unit,
    onDelCat: (String) -> Unit,
    onMoveCat: (LutLibrary.Entry, String) -> Unit,
    onStrength: (Float) -> Unit,
    onPick: (Int) -> Unit,
    onPickLut: (LutLibrary.Entry?) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var catNewOpen by remember { mutableStateOf(false) }
    var catMove by remember { mutableStateOf<LutLibrary.Entry?>(null) }
    var catDel by remember { mutableStateOf<String?>(null) } // 待确认删除的分类名
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(FilmTheme.Panel)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        // 顶行：当前选中名（左）+ 收起（右）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (tab == 0) {
                    LutLibrary.byId(context, lutId)?.label ?: "无 LUT"
                } else {
                    CameraPresets.names.getOrElse(presetIdx) { "标准" }
                },
                color = FilmTheme.Cream,
                fontSize = 20.sp,
                fontFamily = FilmTheme.Serif,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                "收起",
                color = FilmTheme.CreamDim,
                fontSize = 12.sp,
                fontFamily = FilmTheme.Mono,
                modifier = Modifier
                    .pressScale(onTap = onClose)
                    .padding(6.dp),
            )
        }
        // 分类
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            SheetPill("LUT", selected = tab == 0) { onTab(0) }
            SheetPill("预设", selected = tab == 1) { onTab(1) }
        }
        // 分类筛选（M4-3）：默认品牌；长按卡片改归属，＋ 新建分类
        if (tab == 0) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                item { SheetPill("全部", selected = filter == null) { onFilter(null) } }
                items(cats) { c ->
                    // 长按自定义分类即删除（内置品牌分类不可删）；删完 LUT 回到默认分类
                    val del: (() -> Unit)? = if (c in customCats) ({ catDel = c }) else null
                    SheetPill(c, selected = filter == c, onTap = { onFilter(c) }, onLongTap = del)
                }
                item { SheetPill("＋", selected = false) { catNewOpen = true } }
            }
        }
        // 强度（仅 LUT 分类且已选中时）：拖到 0 = 旁路，用于在风格与噪声之间权衡
        if (tab == 0 && lutId != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(
                    "强度",
                    color = FilmTheme.CreamDim,
                    fontSize = 11.sp,
                    fontFamily = FilmTheme.Mono,
                )
                androidx.compose.material3.Slider(
                    value = lutStrength,
                    onValueChange = onStrength,
                    valueRange = 0f..1.5f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = FilmTheme.Cream,
                        activeTrackColor = FilmTheme.Accent,
                        inactiveTrackColor = FilmTheme.ChipBorder,
                    ),
                )
                Text(
                    "${(lutStrength * 100).roundToInt()}%",
                    color = FilmTheme.Accent,
                    fontSize = 12.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        // 横向卡片
        if (tab == 0) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                item {
                    LutCard(label = "无", selected = lutId == null, preview = null) { onPickLut(null) }
                }
                items(LutLibrary.entries(context).filter { filter == null || catOf[it.id] == filter }) { e ->
                    LutCard(
                        label = e.label,
                        selected = lutId == e.id,
                        preview = previews[e.id],
                        onLongTap = { catMove = e },
                    ) { onPickLut(e) }
                }
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                itemsIndexed(CameraPresets.names) { i, name ->
                    LutCard(label = name, selected = i == presetIdx, preview = null) { onPick(i) }
                }
            }
        }
    }
    if (catNewOpen) {
        var name by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { catNewOpen = false },
            shape = RoundedCornerShape(18.dp),
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { if (name.isNotBlank()) onNewCat(name); catNewOpen = false },
                ) { Text("新建", color = FilmTheme.Accent, fontFamily = FilmTheme.Mono) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { catNewOpen = false }) {
                    Text("取消", color = FilmTheme.CreamDim, fontFamily = FilmTheme.Mono)
                }
            },
            title = { Text("新建分类", color = FilmTheme.Cream, fontFamily = FilmTheme.Serif) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("分类名", color = FilmTheme.CreamDim, fontFamily = FilmTheme.Serif) },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = FilmTheme.Cream,
                        unfocusedTextColor = FilmTheme.Cream,
                        cursorColor = FilmTheme.Accent,
                        focusedBorderColor = FilmTheme.Accent,
                        unfocusedBorderColor = FilmTheme.ChipBorder,
                        focusedContainerColor = FilmTheme.ChipBg,
                        unfocusedContainerColor = FilmTheme.ChipBg,
                    ),
                )
            },
        )
    }
    catMove?.let { e ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { catMove = null },
            shape = RoundedCornerShape(18.dp),
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { catMove = null }) {
                    Text("取消", color = FilmTheme.CreamDim, fontFamily = FilmTheme.Mono)
                }
            },
            title = { Text("「${e.label}」归入分类", color = FilmTheme.Cream, fontFamily = FilmTheme.Serif) },
            text = {
                Column {
                    cats.forEach { c ->
                        val cur = catOf[e.id] == c
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (cur) "$c  ✓" else c,
                                color = if (cur) FilmTheme.Accent else FilmTheme.Cream,
                                fontSize = 15.sp,
                                fontFamily = FilmTheme.Serif,
                                modifier = Modifier
                                    .weight(1f)
                                    .pressScale(onTap = { onMoveCat(e, c); catMove = null })
                                    .padding(vertical = 10.dp),
                            )
                            // 只有用户新建的分类可删；资产内置的品牌分类不允许删
                            if (c in customCats) {
                                Text(
                                    "删除",
                                    color = FilmTheme.CreamDim,
                                    fontSize = 12.sp,
                                    fontFamily = FilmTheme.Mono,
                                    modifier = Modifier
                                        .pressScale(onTap = { catDel = c })
                                        .padding(8.dp),
                                )
                            }
                        }
                    }
                }
            },
        )
    }
    // 删除分类二次确认：不可逆的动作必须确认（LUT 归属会回默认，但分类本身消失）
    catDel?.let { c ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { catDel = null },
            shape = RoundedCornerShape(18.dp),
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { onDelCat(c); catDel = null },
                ) { Text("删除", color = FilmTheme.Accent, fontFamily = FilmTheme.Mono) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { catDel = null }) {
                    Text("取消", color = FilmTheme.CreamDim, fontFamily = FilmTheme.Mono)
                }
            },
            title = { Text("删除分类", color = FilmTheme.Cream, fontFamily = FilmTheme.Serif) },
            text = {
                Text(
                    "「$c」将被删除，归入它的 LUT 会回到默认分类（LUT 本身不受影响）。",
                    color = FilmTheme.CreamDim,
                    fontSize = 14.sp,
                    fontFamily = FilmTheme.Serif,
                )
            },
        )
    }
}

/** 分类 pill（LUT / 预设）：选中 = 琥珀描边 + 琥珀字 */
@Composable
private fun SheetPill(
    label: String,
    selected: Boolean,
    onLongTap: (() -> Unit)? = null,
    onTap: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) FilmTheme.Accent else FilmTheme.ChipBorder,
                RoundedCornerShape(50),
            )
            .pressScale(onTap = onTap, onLongTap = onLongTap)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (selected) FilmTheme.Accent else FilmTheme.Cream,
            fontSize = 13.sp,
            fontFamily = FilmTheme.Mono,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** 选择卡片：LUT 显示「固定测试图 × 该 LUT」的预览图（一次性生成并缓存，见 LutPreview）；无图时显示名称 */
@Composable
private fun LutCard(
    label: String,
    selected: Boolean,
    preview: Bitmap?,
    onLongTap: (() -> Unit)? = null,
    onTap: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(FilmTheme.Body)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) FilmTheme.Accent else FilmTheme.ChipBorder,
                    RoundedCornerShape(14.dp),
                )
                .pressScale(onTap = onTap, onLongTap = onLongTap),
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(label, color = FilmTheme.CreamDim, fontSize = 13.sp, fontFamily = FilmTheme.Serif)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            color = if (selected) FilmTheme.Accent else FilmTheme.Cream,
            fontSize = 11.sp,
            fontFamily = FilmTheme.Serif,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** 一级悬浮窗（菜单）：宽度随屏幕；画幅选项（后续在此区继续加拍摄功能入口）+ 更多设置 */
@Composable
private fun QuickDropdown(
    aspect: String,
    focalStrip: String,
    onFocalStrip: (String) -> Unit,
    onAspect: (String) -> Unit,
    gridOn: Boolean,
    onGrid: () -> Unit,
    levelOn: Boolean,
    onLevel: () -> Unit,
    zebraOn: Boolean,
    onZebra: () -> Unit,
    onMoreSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(FilmTheme.Panel)
            .padding(vertical = 10.dp),
    ) {
        // 3 列开关格（v0.6.8）：原来单列一行一个开关，宽度利用率太低
        Column(Modifier.padding(horizontal = 12.dp)) {
            QuickSectionLabel("画幅")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("4:3", "16:9", "XPAN").forEach { a ->
                    QuickTile(a, on = aspect == a) { onAspect(a) }
                }
            }
            Spacer(Modifier.height(12.dp))
            QuickSectionLabel("构图辅助")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QuickTile("三分线", on = gridOn, onTap = onGrid)
                QuickTile("水平仪", on = levelOn, onTap = onLevel)
                QuickTile("斑马纹", on = zebraOn, onTap = onZebra)
            }
            Spacer(Modifier.height(12.dp))
            QuickSectionLabel("焦段条显示")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QuickTile("焦段", on = focalStrip != "mult", onTap = { onFocalStrip("focal") })
                QuickTile("倍率", on = focalStrip == "mult", onTap = { onFocalStrip("mult") })
                Spacer(Modifier.weight(1f)) // 占位，与上面两行保持同宽
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = FilmTheme.ChipBorder)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onMoreSettings() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("更多设置", color = FilmTheme.Cream, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("›", color = FilmTheme.CreamDim, fontSize = 16.sp)
        }
    }
}

/** 悬浮菜单的分区小标题 */
@Composable
private fun QuickSectionLabel(text: String) {
    Text(
        text,
        color = FilmTheme.Accent,
        fontSize = 11.sp,
        fontFamily = FilmTheme.Mono,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
}

/** 悬浮菜单的 3 列开关格：底色表达状态（琥珀 = 开 / 选中） */
@Composable
private fun RowScope.QuickTile(label: String, on: Boolean, onTap: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) FilmTheme.Accent else FilmTheme.ChipBg)
            .pressScale(onTap = onTap)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (on) FilmTheme.OnAccent else FilmTheme.Cream,
            fontSize = 12.sp,
            fontFamily = FilmTheme.Mono,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** 子页顶栏：左上返回 + 标题（设置 / 日志 / 能力 Spike / 镜头配置 / 系统颜色 共用） */
@Composable
fun SubPageTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = FilmTheme.Cream,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            title,
            color = FilmTheme.Cream,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 胶片风选择片：选中强调色底深字；未选中炭黑（transparent 时全透明、仅奶油字），
 * 透明态用于叠加预览的变焦刻度行。
 */
@Composable
private fun FilmChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    serif: Boolean = false,
    transparent: Boolean = false,
    fontSize: TextUnit = 12.sp,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                fontSize = fontSize,
                fontFamily = if (serif) FilmTheme.Serif else FilmTheme.Mono,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = if (transparent) Color.Transparent else FilmTheme.ChipBg,
            labelColor = FilmTheme.Cream,
            selectedContainerColor = FilmTheme.Accent,
            selectedLabelColor = FilmTheme.OnAccent,
        ),
    )
}
