package com.filmkit.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
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
import androidx.compose.material.icons.filled.Settings
import com.filmkit.app.camera.CameraController
import com.filmkit.app.camera.RenderClient
import kotlinx.coroutines.delay
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.roundToInt

/**
 * M 档专业拍摄界面（方向 C 定稿，v0.4.9）。
 * 参数模型：EV / AE(Tv+ISO) / WB 三组独立 A(自动)/手动；默认 A，拖动刻度条进手动，
 * 点 A 回自动；刻度条随参数切换自适应刻度（Tv 对数快门、ISO 对数、EV/WB 线性）。
 * 组件与颜色规范见 docs/开发计划.md「UI 设计规范」。
 */
@Composable
fun CameraScreen(onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
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

    val controller = remember { CameraController(context, RenderClient()) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var resumed by remember { mutableStateOf(true) }
    var lensChips by remember { mutableStateOf(emptyList<Int>()) } // 启用镜头等效焦段（变焦刻度）
    var presetIdx by rememberSaveable { mutableIntStateOf(0) } // 默认标准
    var focalBase by remember { mutableFloatStateOf(24f) } // 当前镜头等效焦距

    var selectedControl by rememberSaveable { mutableStateOf<String?>(null) } // AF/EXP/EV/WB
    var quickOpen by rememberSaveable { mutableStateOf(false) } // 快捷设置下拉
    var lutOpen by rememberSaveable { mutableStateOf(false) } // LUT 二级菜单
    // A(自动)/手动状态：EV 与 AE(Tv+ISO 联动，Camera2 AE OFF 同时定死两参数)、WB 各自独立
    var evAuto by rememberSaveable { mutableStateOf(true) }
    var aeAuto by rememberSaveable { mutableStateOf(true) }
    var wbAuto by rememberSaveable { mutableStateOf(true) }
    var iso by rememberSaveable { mutableIntStateOf(100) }
    var shutterV by rememberSaveable { mutableFloatStateOf(0.75f) }
    var ev by rememberSaveable { mutableStateOf(0f) }
    var wbK by rememberSaveable { mutableIntStateOf(5000) }
    var focusMark by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var liveIso by remember { mutableIntStateOf(0) }
    var liveNs by remember { mutableLongStateOf(0L) }
    var liveK by remember { mutableIntStateOf(0) }
    var boxW by remember { mutableIntStateOf(1) }
    var boxH by remember { mutableIntStateOf(1) }

    // 启动即应用选中预设，保证界面选中态与引擎参数一致
    LaunchedEffect(Unit) {
        controller.setFilmParams(CameraPresets.byIndex(presetIdx))
    }

    DisposableEffect(Unit) {
        onDispose { controller.stop() }
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
        }
    }

    fun secText(ns: Long): String =
        if (ns <= 0) "--" else if (ns < 1_000_000_000L) "1/${(1_000_000_000.0 / ns).roundToInt()}"
        else "%.1fS".format(ns / 1_000_000_000.0)

    val shutterNs = 10f.pow(5f + 4f * shutterV).roundToLong()
    val maxIso = controller.maxAnalogIso

    // 底部不留白：只让出状态栏，内容延伸到手势条之下
    Column(
        Modifier
            .fillMaxSize()
            .background(FilmTheme.Body)
            .statusBarsPadding(),
    ) {
        // ── 顶栏：仅右上 ⚙（打开变强调色）──
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(40.dp)
                    .pointerInput(Unit) { detectTapGestures { quickOpen = !quickOpen } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = if (quickOpen) FilmTheme.Accent else FilmTheme.CreamDim,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // ── 取景器（3:4 居中，圆角机框）──
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .aspectRatio(3f / 4f)
                .onSizeChanged { boxW = it.width; boxH = it.height },
        ) {
            AndroidView(
                factory = { c ->
                    SurfaceView(c).apply {
                        // 触屏对焦：SurfaceView 原生触摸（Compose 手势部分机型不送达）。
                        // 必须消费事件（true），否则 ACTION_UP 不会送达本视图，对焦永不触发
                        setOnTouchListener { _, e ->
                            if (e.action == MotionEvent.ACTION_UP) {
                                val nx = (e.x / width).coerceIn(0f, 1f)
                                val ny = (e.y / height).coerceIn(0f, 1f)
                                controller.focusAt(nx, ny)
                                focusMark = nx to ny
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

            // 圆角机框：SurfaceView 不响应 Compose clip，用机身色环带遮出圆角 + 奶油细线勾边
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

            // 变焦刻度 + ✦ LUT 入口：透明底叠预览下缘
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 10.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val chips = lensChips.ifEmpty { listOf(focalBase.roundToInt()) }
                chips.forEach { f ->
                    FilmChip(
                        selected = focalBase.roundToInt() == f,
                        label = "$f",
                        transparent = true,
                        onClick = { controller.setFocal(f) },
                    )
                }
                Box(
                    Modifier
                        .padding(horizontal = 6.dp)
                        .width(1.dp)
                        .height(16.dp)
                        .background(FilmTheme.ChipBorder),
                )
                Column(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .pointerInput(Unit) { detectTapGestures { lutOpen = !lutOpen } },
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

            // 参数面板：全局单实例，状态驱动显隐
            androidx.compose.animation.AnimatedVisibility(
                visible = selectedControl != null,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 60.dp),
            ) {
                ControlPanel(
                    ctrl = selectedControl,
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
                    onEvDrag = { v ->
                        evAuto = false
                        ev = v
                        controller.setEv(v)
                    },
                    onEvAuto = {
                        evAuto = true
                        ev = 0f
                        controller.setEv(0f)
                    },
                    onTvDrag = { f ->
                        // AE OFF 会同时锁快门与 ISO：从 A 进入时把另一参数冻结在当前实测值
                        if (aeAuto && liveIso > 0) iso = liveIso.coerceIn(100, maxIso)
                        aeAuto = false
                        shutterV = f
                        controller.setManualExposure(true)
                        controller.setExposureNs(10f.pow(5f + 4f * f).roundToLong())
                    },
                    onIsoDrag = { i ->
                        if (aeAuto && liveNs > 0) {
                            val nsF = liveNs.coerceIn(100_000L, 1_000_000_000L).toFloat()
                            shutterV = ((log10(nsF) - 5f) / 4f).coerceIn(0f, 1f)
                        }
                        aeAuto = false
                        iso = i
                        controller.setManualExposure(true)
                        controller.setIso(i)
                    },
                    onAeAuto = {
                        aeAuto = true
                        controller.setManualExposure(false)
                    },
                    onWbDrag = { k ->
                        wbAuto = false
                        wbK = k
                        controller.setWbK(k)
                    },
                    onWbAuto = {
                        wbAuto = true
                        controller.setAutoWb()
                    },
                    secText = ::secText,
                )
            }

            // ── LUT 二级菜单（✦ 展开；点画面收起）──
            if (lutOpen) {
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) { detectTapGestures { lutOpen = false } },
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = lutOpen,
                enter = fadeIn() + slideInVertically { it / 4 },
                exit = fadeOut() + slideOutVertically { it / 4 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 64.dp),
            ) {
                LutMenu(
                    presetIdx = presetIdx,
                    onPick = { i ->
                        presetIdx = i
                        controller.setFilmParams(CameraPresets.byIndex(i))
                        lutOpen = false
                    },
                )
            }

            // ── 快捷设置下拉（⚙ 展开；点画面收起；预留画面比例/HDR 等入口）──
            if (quickOpen) {
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) { detectTapGestures { quickOpen = false } },
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = quickOpen,
                enter = fadeIn() + slideInVertically { -it / 6 },
                exit = fadeOut() + slideOutVertically { -it / 6 },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 10.dp, top = 8.dp),
            ) {
                QuickDropdown(onMoreSettings = {
                    quickOpen = false
                    onOpenSettings()
                })
            }
        }

        // ── 控制瓦片：A(自动)/手动值实时显示（选中强调色，Tv/ISO 共用 AE 组）──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            fun toggle(c: String) {
                selectedControl = if (selectedControl == c) null else c
            }
            val expActive = selectedControl == "EXP"
            ControlTile(
                value = if (evAuto) "A" else (if (ev >= 0f) "+" else "") + "%.1f".format(ev),
                label = "EV",
                state = when {
                    selectedControl == "EV" -> TileState.ACTIVE
                    evAuto -> TileState.AUTO
                    else -> TileState.MANUAL
                },
            ) { toggle("EV") }
            ControlTile(
                value = if (aeAuto) "A" else secText(shutterNs),
                label = "Tv",
                state = when {
                    expActive -> TileState.ACTIVE
                    aeAuto -> TileState.AUTO
                    else -> TileState.MANUAL
                },
            ) { toggle("EXP") }
            ControlTile(
                value = if (aeAuto) "A" else "$iso",
                label = "ISO",
                state = when {
                    expActive -> TileState.ACTIVE
                    aeAuto -> TileState.AUTO
                    else -> TileState.MANUAL
                },
            ) { toggle("EXP") }
            ControlTile("A", "AF", state = if (selectedControl == "AF") TileState.ACTIVE else TileState.AUTO) {
                toggle("AF")
            }
            ControlTile(
                value = if (wbAuto) "A" else "${wbK}K",
                label = "WB",
                state = when {
                    selectedControl == "WB" -> TileState.ACTIVE
                    wbAuto -> TileState.AUTO
                    else -> TileState.MANUAL
                },
            ) { toggle("WB") }
        }

        // ── 底部：胶卷位（相册接入前占位）· 快门 · 预留翻转位 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .alpha(0.35f),
                contentAlignment = Alignment.Center,
            ) {
                Text("▦", color = FilmTheme.Cream, fontSize = 22.sp)
            }
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .border(4.dp, FilmTheme.Cream, CircleShape)
                    .pointerInput(Unit) { detectTapGestures { controller.capture() } },
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
}

private enum class TileState { AUTO, MANUAL, ACTIVE }

/** 底部控制瓦片：上实时读数（A=自动）、下标签；AUTO 次级色 / MANUAL 奶油白 / ACTIVE 强调色+下划线 */
@Composable
private fun ControlTile(value: String, label: String, state: TileState, onTap: () -> Unit) {
    val color = when (state) {
        TileState.AUTO -> FilmTheme.CreamDim
        TileState.MANUAL -> FilmTheme.Cream
        TileState.ACTIVE -> FilmTheme.Accent
    }
    Column(
        Modifier
            .width(62.dp)
            .pointerInput(Unit) { detectTapGestures { onTap() } },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            color = color,
            fontSize = 14.sp,
            fontFamily = FilmTheme.Mono,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = color,
            fontSize = 10.sp,
            fontFamily = FilmTheme.Mono,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .size(width = 16.dp, height = 2.dp)
                .background(if (state == TileState.ACTIVE) FilmTheme.Accent else Color.Transparent),
        )
    }
}

/** 参数面板（单实例）：AF 提示 / EXP（SEC+ISO，共用 AE 组）/ EV / WB，统一 ParamRow 风格 */
@Composable
private fun ControlPanel(
    ctrl: String?,
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
                Text("点按画面对焦（连续自动对焦）", color = FilmTheme.Cream, fontSize = 12.sp)
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
                    onFraction = { onIsoDrag(it.roundToInt()) },
                )
            }
            "EV" -> {
                val f = ((ev + 4f) / 8f).coerceIn(0f, 1f)
                ParamRow(
                    label = "EV",
                    auto = evAuto,
                    onAuto = onEvAuto,
                    value = (if (ev >= 0f) "+" else "") + "%.1f".format(ev),
                    fraction = f,
                    ticks = ticksEv,
                    onFraction = onEvDrag,
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
                    onFraction = { onWbDrag(it.roundToInt()) },
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
            .clip(CircleShape)
            .background(if (active) FilmTheme.Accent else Color.Transparent)
            .border(1.dp, if (active) FilmTheme.Accent else FilmTheme.ChipBorder, CircleShape)
            .pointerInput(Unit) { detectTapGestures { onTap() } },
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
 * 刻度滑条：活动轨/刻度线用强调色，刻度标签随参数传入（自适应刻度），
 * 拖动/点按更新 fraction；奶油白竖条拇指。
 */
@Composable
private fun TickSlider(
    fraction: Float,
    ticks: List<Pair<Float, String>>,
    onFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier
            .height(46.dp)
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    onFraction((off.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onFraction((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val w = size.width
        val trackY = size.height * 0.32f
        val stroke = 3.dp.toPx()
        drawLine(FilmTheme.ChipBorder, Offset(0f, trackY), Offset(w, trackY), stroke, StrokeCap.Round)
        if (fraction > 0f) {
            drawLine(
                FilmTheme.Accent,
                Offset(0f, trackY),
                Offset(fraction * w, trackY),
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
            topLeft = Offset(fraction * w - 3.dp.toPx(), trackY - 10.dp.toPx()),
            size = Size(6.dp.toPx(), 20.dp.toPx()),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
        )
    }
}

private val ticksEv = listOf(0f to "-4", 0.25f to "-2", 0.5f to "0", 0.75f to "+2", 1f to "+4")

private val ticksTv = listOf(
    0.25f to "1/1000",
    0.4f to "1/250",
    0.555f to "1/60",
    0.706f to "1/15",
    0.85f to "1/4",
    1f to "1S",
)

private val ticksWb = listOf(0.1f to "3000K", 0.5f to "5000K", 0.9f to "7000K")

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

/** LUT 二级菜单：内置胶片预设 + LUT（.cube，M4 接入） */
@Composable
private fun LutMenu(presetIdx: Int, onPick: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(FilmTheme.Panel)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "胶片预设",
            color = FilmTheme.Accent,
            fontSize = 11.sp,
            fontFamily = FilmTheme.Mono,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        CameraPresets.names.forEachIndexed { i, name ->
            val selected = i == presetIdx
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(i) { detectTapGestures { onPick(i) } }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name,
                    color = if (selected) FilmTheme.Accent else FilmTheme.Cream,
                    fontSize = 15.sp,
                    fontFamily = FilmTheme.Serif,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Text("✓", color = FilmTheme.Accent, fontSize = 13.sp, fontFamily = FilmTheme.Mono)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = FilmTheme.ChipBorder)
        Spacer(Modifier.height(6.dp))
        Text(
            "LUT",
            color = FilmTheme.Accent,
            fontSize = 11.sp,
            fontFamily = FilmTheme.Mono,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "理光系 .cube · M4 接入",
            color = FilmTheme.CreamDim,
            fontSize = 12.sp,
            fontFamily = FilmTheme.Serif,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

/** 快捷设置下拉（⚙）：后续增加画面比例 / HDR 等入口 */
@Composable
private fun QuickDropdown(onMoreSettings: () -> Unit) {
    Column(
        Modifier
            .width(200.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(FilmTheme.Panel)
            .padding(vertical = 4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { onMoreSettings() } }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("更多设置", color = FilmTheme.Cream, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("›", color = FilmTheme.CreamDim, fontSize = 16.sp)
        }
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
                .pointerInput(Unit) { detectTapGestures { onBack() } },
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
