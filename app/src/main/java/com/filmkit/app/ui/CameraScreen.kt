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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.filmkit.app.EngineBridge
import com.filmkit.app.camera.CameraController
import com.filmkit.app.camera.RenderClient
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.roundToInt

/**
 * M 档专业拍摄界面（UI 专项定稿方向 C：PhotonCamera 结构骨架 + DAZZ 胶片质感）。
 * 结构约定：参数面板全局单实例（叠加取景器底部、状态驱动显隐）；焦段条固定不随面板跳位。
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
                .windowInsetsPadding(WindowInsets.safeDrawing)
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
    var fps by remember { mutableIntStateOf(0) }
    var lensChips by remember { mutableStateOf(emptyList<Int>()) } // 启用镜头等效焦段（焦段条）
    var presetIdx by rememberSaveable { mutableIntStateOf(1) }
    var focalBase by remember { mutableFloatStateOf(24f) } // 当前镜头等效焦距

    var selectedControl by rememberSaveable { mutableStateOf<String?>(null) } // AF/EXP/EV/WB
    var manualAE by rememberSaveable { mutableStateOf(false) }
    var iso by rememberSaveable { mutableIntStateOf(100) }
    var shutterV by rememberSaveable { mutableFloatStateOf(0.75f) }
    var ev by remember { mutableStateOf(0f) }
    var manualWb by rememberSaveable { mutableStateOf(false) }
    var wbK by rememberSaveable { mutableIntStateOf(5000) }
    var focusMark by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var liveIso by remember { mutableIntStateOf(0) }
    var liveNs by remember { mutableLongStateOf(0L) }
    var liveK by remember { mutableIntStateOf(0) }
    var boxW by remember { mutableIntStateOf(1) }
    var boxH by remember { mutableIntStateOf(1) }

    // 此前预设只在点按时应用，界面默认选中「负片400」而引擎仍是默认参数——启动即对齐
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
        var lastFrames = 0L
        var lastT = SystemClock.elapsedRealtime()
        while (true) {
            delay(400)
            lensChips = controller.lensEquivs()
            focalBase = controller.currentLensEquiv()
            liveIso = controller.liveIso
            liveNs = controller.liveExposureNs
            liveK = controller.liveK
            val s = EngineBridge.nativeEngineGetStats() ?: continue
            val n = JSONObject(s).optLong("frames", 0)
            val now = SystemClock.elapsedRealtime()
            if (now > lastT) fps = ((n - lastFrames) * 1000 / (now - lastT)).toInt()
            lastFrames = n
            lastT = now
        }
    }

    fun secText(ns: Long): String =
        if (ns <= 0) "--" else if (ns < 1_000_000_000L) "1/${(1_000_000_000.0 / ns).roundToInt()}"
        else "%.1fS".format(ns / 1_000_000_000.0)

    val shutterNs = 10f.pow(5f + 4f * shutterV).roundToLong()

    Column(
        Modifier
            .fillMaxSize()
            .background(FilmTheme.Body)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // ── 顶栏：胶片型号条（胶卷仓选择）──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraPresets.names.forEachIndexed { i, name ->
                FilmChip(
                    selected = presetIdx == i,
                    label = name,
                    serif = true,
                    fontSize = 13.sp,
                    onClick = {
                        presetIdx = i
                        controller.setFilmParams(CameraPresets.byIndex(i))
                    },
                )
            }
        }

        // ── 实测参数条（CaptureResult 回填读数，等宽字）──
        Row(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val meter = FilmTheme.CreamDim.copy(alpha = 0.85f)
            Text("+/- %.1f".format(ev), color = meter, fontSize = 13.sp, fontFamily = FilmTheme.Mono)
            Text(secText(liveNs), color = meter, fontSize = 13.sp, fontFamily = FilmTheme.Mono)
            Text("ISO $liveIso", color = meter, fontSize = 13.sp, fontFamily = FilmTheme.Mono)
            if (liveK > 0) {
                Text("${liveK}K", color = meter, fontSize = 13.sp, fontFamily = FilmTheme.Mono)
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
                    size = androidx.compose.ui.geometry.Size(
                        size.width - inset * 2,
                        size.height - inset * 2,
                    ),
                    cornerRadius = CornerRadius(radius, radius),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                )
            }

            // fps（调试读数，功能验证期保留）
            Text(
                "fps $fps",
                Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 12.dp),
                color = FilmTheme.CreamDim.copy(alpha = 0.4f),
                fontSize = 9.sp,
                fontFamily = FilmTheme.Mono,
            )

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

            // 焦段条：常量 padding 固定于取景器下缘（此前随面板开合 10↔108dp 跳位）
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(FilmTheme.Panel)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val chips = lensChips.ifEmpty { listOf(focalBase.roundToInt()) }
                chips.forEach { f ->
                    FilmChip(
                        selected = focalBase.roundToInt() == f,
                        label = "$f",
                        onClick = { controller.setFocal(f) },
                    )
                }
            }

            // 参数面板：全局单实例（旧版在预览内、按钮下各写一份重复实现），状态驱动显隐
            androidx.compose.animation.AnimatedVisibility(
                visible = selectedControl != null,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
            ) {
                val ctrl = selectedControl
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PanelLabel("AE")
                                FilmChip(selected = !manualAE, label = "AUTO", onClick = {
                                    manualAE = false
                                    controller.setManualExposure(false)
                                })
                                Spacer(Modifier.width(6.dp))
                                FilmChip(selected = manualAE, label = "OFF", onClick = {
                                    manualAE = true
                                    controller.setManualExposure(true)
                                })
                            }
                            PanelRow("SEC", secText(shutterNs)) {
                                Slider(
                                    value = shutterV,
                                    onValueChange = {
                                        shutterV = it
                                        controller.setExposureNs(10f.pow(5f + 4f * shutterV).roundToLong())
                                    },
                                    valueRange = 0f..1f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = FilmTheme.Cream,
                                        activeTrackColor = FilmTheme.Amber,
                                        inactiveTrackColor = FilmTheme.ChipBorder,
                                    ),
                                )
                            }
                            PanelRow("ISO", "$iso") {
                                Slider(
                                    value = iso.toFloat(),
                                    onValueChange = {
                                        iso = it.roundToInt()
                                        controller.setIso(iso)
                                    },
                                    valueRange = 100f..controller.maxAnalogIso.toFloat(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = FilmTheme.Cream,
                                        activeTrackColor = FilmTheme.Amber,
                                        inactiveTrackColor = FilmTheme.ChipBorder,
                                    ),
                                )
                            }
                        }
                        "EV" -> {
                            PanelRow("EV", "%.1f".format(ev)) {
                                Slider(
                                    value = ev.coerceIn(-4f, 4f),
                                    onValueChange = {
                                        ev = it
                                        manualAE = false
                                        controller.setManualExposure(false)
                                        controller.setEv(ev)
                                    },
                                    valueRange = -4f..4f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = FilmTheme.Cream,
                                        activeTrackColor = FilmTheme.Amber,
                                        inactiveTrackColor = FilmTheme.ChipBorder,
                                    ),
                                )
                            }
                        }
                        "WB" -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PanelLabel("WB")
                                FilmChip(selected = !manualWb, label = "AUTO", onClick = {
                                    manualWb = false
                                    controller.setAutoWb()
                                })
                                Spacer(Modifier.width(6.dp))
                                FilmChip(selected = manualWb, label = "${wbK}K", onClick = {
                                    manualWb = true
                                    controller.setWbK(wbK)
                                })
                            }
                            if (manualWb) {
                                PanelRow("K", "$wbK") {
                                    Slider(
                                        value = wbK.toFloat(),
                                        onValueChange = {
                                            wbK = it.roundToInt()
                                            controller.setWbK(wbK)
                                        },
                                        valueRange = 2500f..7500f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = FilmTheme.Cream,
                                            activeTrackColor = FilmTheme.Amber,
                                            inactiveTrackColor = FilmTheme.ChipBorder,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 控制圆钮（选中琥珀底）──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            listOf("AF", "EXP", "EV", "WB").forEach { c ->
                val on = selectedControl == c
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (on) FilmTheme.Amber else FilmTheme.ChipBg)
                        .border(
                            1.dp,
                            if (on) FilmTheme.Amber else FilmTheme.ChipBorder,
                            CircleShape,
                        )
                        .pointerInput(c) {
                            detectTapGestures {
                                selectedControl = if (selectedControl == c) null else c
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        c,
                        color = if (on) FilmTheme.OnAccent else FilmTheme.Cream,
                        fontSize = 13.sp,
                        fontFamily = FilmTheme.Mono,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // ── 底部三段：胶卷位（相册接入前占位）· 快门 · 设置 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
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
            Box(
                Modifier
                    .size(44.dp)
                    .pointerInput(Unit) { detectTapGestures { onOpenSettings() } },
                contentAlignment = Alignment.Center,
            ) {
                Text("⚙", color = FilmTheme.Cream, fontSize = 22.sp)
            }
        }
    }
}

/** 面板行首标签：琥珀等宽小字 */
@Composable
private fun PanelLabel(text: String) {
    Text(
        text,
        color = FilmTheme.Amber,
        fontSize = 11.sp,
        fontFamily = FilmTheme.Mono,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.width(38.dp),
    )
}

/** 面板内一行：琥珀标签 + 滑杆 + 奶油读数 */
@Composable
private fun PanelRow(label: String, value: String, slider: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PanelLabel(label)
        Box(Modifier.weight(1f)) { slider() }
        Text(
            value,
            color = FilmTheme.Cream,
            fontSize = 11.sp,
            fontFamily = FilmTheme.Mono,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
    }
}

/** 胶片风选择片：未选中炭黑奶油字，选中琥珀底深字 */
@Composable
private fun FilmChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    serif: Boolean = false,
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
            containerColor = FilmTheme.ChipBg,
            labelColor = FilmTheme.Cream,
            selectedContainerColor = FilmTheme.Amber,
            selectedLabelColor = FilmTheme.OnAccent,
        ),
    )
}
