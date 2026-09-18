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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
 * M 档专业拍摄界面（UI 专项方向 C：PhotonCamera 结构骨架 + DAZZ 胶片质感）。
 * v0.4.7 按真机反馈：顶部胶片条并入取景器内底部「变焦刻度 + 滤镜」透明行；默认标准；
 * ⚙ 移到右上角，点开半透明快捷设置悬浮窗（DNG/镜头开关 + 更多设置入口）；底部不留白。
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
    var fps by remember { mutableIntStateOf(0) }
    var lensChips by remember { mutableStateOf(emptyList<Int>()) } // 启用镜头等效焦段（变焦刻度）
    var presetIdx by rememberSaveable { mutableIntStateOf(0) } // 默认标准
    var focalBase by remember { mutableFloatStateOf(24f) } // 当前镜头等效焦距

    var selectedControl by rememberSaveable { mutableStateOf<String?>(null) } // AF/EXP/EV/WB
    var quickOpen by rememberSaveable { mutableStateOf(false) } // 快捷设置悬浮窗
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

    // 底部不留白：只让出状态栏，内容延伸到手势条之下
    Column(
        Modifier
            .fillMaxSize()
            .background(FilmTheme.Body)
            .statusBarsPadding(),
    ) {
        // ── 顶栏：实测参数居中，⚙ 右上角 ──
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        ) {
            Row(
                Modifier.align(Alignment.Center),
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
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .size(40.dp)
                    .pointerInput(Unit) { detectTapGestures { quickOpen = !quickOpen } },
                contentAlignment = Alignment.Center,
            ) {
                Text("⚙", color = if (quickOpen) FilmTheme.Amber else FilmTheme.Cream, fontSize = 20.sp)
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

            // 变焦刻度 + 滤镜：透明底叠预览下缘（变焦左、滤镜右，选中琥珀）
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
                CameraPresets.names.forEachIndexed { i, name ->
                    FilmChip(
                        selected = presetIdx == i,
                        label = name,
                        serif = true,
                        transparent = true,
                        fontSize = 13.sp,
                        onClick = {
                            presetIdx = i
                            controller.setFilmParams(CameraPresets.byIndex(i))
                        },
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

            // ── 快捷设置悬浮窗（半透明，点⚙展开；参考 vivo/徕卡快捷面板）──
            if (quickOpen) {
                // 悬浮窗外的透明拦截层：点画面任意处收起（打开期间不触发对焦）。
                // 必须先于面板绘制（Box 后绘者在上），否则会挡住面板自身的点击
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
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                QuickSettingsPanel(
                    onDismiss = { quickOpen = false },
                    onMoreSettings = {
                        quickOpen = false
                        onOpenSettings()
                    },
                )
            }
        }

        // ── 控制圆钮（选中琥珀底）──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
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

/**
 * 快捷设置悬浮窗：格式（DNG 开关）+ 镜头启停 + 底部「更多设置」入口。
 * 与设置页共享 prefs 键（dng_enabled / lens_on_<id>），此处改动设置页即时同步。
 */
@Composable
private fun QuickSettingsPanel(
    onDismiss: () -> Unit,
    onMoreSettings: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }
    var dngEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean("dng_enabled", false)) }
    val cm = remember {
        context.getSystemService(android.content.Context.CAMERA_SERVICE)
            as android.hardware.camera2.CameraManager
    }
    val lenses = remember { CameraController.LensDiscovery.discoverBackLenses(cm, prefs) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(FilmTheme.Panel)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("格式", color = FilmTheme.Amber, fontSize = 11.sp, fontFamily = FilmTheme.Mono, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("DNG 原始格式存储", color = FilmTheme.Cream, fontSize = 13.sp)
                Text(
                    "快门同时保存 DNG 底片 + 胶片 JPEG（单张约 10–20MB）",
                    color = FilmTheme.CreamDim,
                    fontSize = 10.sp,
                )
            }
            Switch(
                checked = dngEnabled,
                onCheckedChange = { on ->
                    dngEnabled = on
                    prefs.edit().putBoolean("dng_enabled", on).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "settings.dng",
                        "{\"enabled\":$on}",
                    )
                },
                colors = SwitchDefaults.colors(checkedTrackColor = FilmTheme.Amber),
            )
        }

        Spacer(Modifier.height(10.dp))
        Text("镜头", color = FilmTheme.Amber, fontSize = 11.sp, fontFamily = FilmTheme.Mono, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
            lenses.forEach { lens ->
                val key = "lens_on_${lens.id}"
                var on by rememberSaveable(lens.id) { mutableStateOf(prefs.getBoolean(key, true)) }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "ID ${lens.id} · ${lens.label()}",
                        color = FilmTheme.Cream,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = on,
                        onCheckedChange = { v ->
                            on = v
                            prefs.edit().putBoolean(key, v).apply()
                            EngineBridge.nativeAppendLog(
                                EngineBridge.LEVEL_INFO, "camera", "lens.enabled_set",
                                com.filmkit.app.kv("id" to lens.id, "enabled" to v),
                            )
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = FilmTheme.Amber),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = FilmTheme.ChipBorder)
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { onMoreSettings() } }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("更多设置", color = FilmTheme.Cream, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("›", color = FilmTheme.CreamDim, fontSize = 18.sp)
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

/**
 * 胶片风选择片：选中琥珀底深字；未选中炭黑（transparent 时全透明、仅奶油字），
 * 透明态用于叠加预览的变焦刻度/滤镜行。
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
            selectedContainerColor = FilmTheme.Amber,
            selectedLabelColor = FilmTheme.OnAccent,
        ),
    )
}
