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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
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

/** M 档专业拍摄界面（布局参照 DAZZ / PhotonCamera）。 */
@Composable
fun CameraScreen() {
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
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("胶片预览需要相机权限", color = Color.White)
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
    var fillTest by rememberSaveable { mutableStateOf(false) }
    var orientation by rememberSaveable { mutableIntStateOf(0) }
    var focalBase by remember { mutableFloatStateOf(24f) } // 当前镜头等效焦距

    var selectedControl by rememberSaveable { mutableStateOf<String?>(null) } // AF/EXP/EV/WB
    var manualAE by rememberSaveable { mutableStateOf(false) }
    var iso by rememberSaveable { mutableIntStateOf(100) }
    var shutterV by rememberSaveable { mutableFloatStateOf(0.75f) }
    var ev by rememberSaveable { mutableStateOf(0f) }
    var manualWb by rememberSaveable { mutableStateOf(false) }
    var wbK by rememberSaveable { mutableIntStateOf(5000) }
    var focusMark by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var liveIso by remember { mutableIntStateOf(0) }
    var liveNs by remember { mutableLongStateOf(0L) }
    var liveK by remember { mutableIntStateOf(0) }
    var boxW by remember { mutableIntStateOf(1) }
    var boxH by remember { mutableIntStateOf(1) }

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
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // ── 预览区（3:4 居中）──
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .align(Alignment.CenterHorizontally)
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

            // 顶部实时参数条
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xB3141414))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("+/- %.1f".format(ev), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(secText(liveNs), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("ISO $liveIso", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (liveK > 0) {
                    Text("${liveK}K", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            // fps
            Text(
                "fps $fps",
                Modifier.align(Alignment.TopStart).padding(6.dp),
                color = Color(0x88FFFFFF),
                fontSize = 10.sp,
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

            // 焦段条（预览内底部）：按发现的启用镜头等效焦段生成，不写死档位
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (selectedControl != null) 108.dp else 10.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xB3141414))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val chips = lensChips.ifEmpty { listOf(focalBase.roundToInt()) }
                chips.forEach { f ->
                    FilterChip(
                        selected = focalBase.roundToInt() == f,
                        onClick = { controller.setFocal(f) },
                        label = { Text("$f", fontSize = 12.sp, color = Color.White) },
                    )
                }
            }

            // 参数滑杆面板（叠加预览底部）
            selectedControl?.let { ctrl ->
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xE6141414))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    when (ctrl) {
                        "AF" -> {
                            Text(
                                "AF · 点按画面对焦（连续自动对焦）",
                                color = Color.White, fontSize = 12.sp,
                            )
                        }
                        "EXP" -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("AE", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(30.dp))
                                FilterChip(
                                    selected = !manualAE,
                                    onClick = {
                                        manualAE = false
                                        controller.setManualExposure(false)
                                    },
                                    label = { Text("AUTO", fontSize = 10.sp) },
                                )
                                FilterChip(
                                    selected = manualAE,
                                    onClick = {
                                        manualAE = true
                                        controller.setManualExposure(true)
                                    },
                                    label = { Text("OFF", fontSize = 10.sp) },
                                )
                            }
                            PanelRow("SEC", secText(shutterNs)) {
                                Slider(value = shutterV, onValueChange = {
                                    shutterV = it
                                    controller.setExposureNs(10f.pow(5f + 4f * shutterV).roundToLong())
                                }, valueRange = 0f..1f)
                            }
                            PanelRow("ISO", "$iso") {
                                Slider(value = iso.toFloat(), onValueChange = {
                                    iso = it.roundToInt()
                                    controller.setIso(iso)
                                }, valueRange = 100f..controller.maxAnalogIso.toFloat())
                            }
                        }
                        "EV" -> {
                            PanelRow("EV", "%.1f".format(ev)) {
                                Slider(value = ev.coerceIn(-4f, 4f), onValueChange = {
                                    ev = it
                                    manualAE = false
                                    controller.setManualExposure(false)
                                    controller.setEv(ev)
                                }, valueRange = -4f..4f)
                            }
                        }
                        "WB" -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("WB", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(30.dp))
                                FilterChip(
                                    selected = !manualWb,
                                    onClick = {
                                        manualWb = false
                                        controller.setAutoWb()
                                    },
                                    label = { Text("AUTO", fontSize = 10.sp) },
                                )
                                FilterChip(
                                    selected = manualWb,
                                    onClick = {
                                        manualWb = true
                                        controller.setWbK(wbK)
                                    },
                                    label = { Text("${wbK}K", fontSize = 10.sp) },
                                )
                            }
                            if (manualWb) {
                                PanelRow("K", "$wbK") {
                                    Slider(value = wbK.toFloat(), onValueChange = {
                                        wbK = it.roundToInt()
                                        controller.setWbK(wbK)
                                    }, valueRange = 2500f..7500f)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 控制按钮行 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            listOf("AF", "EXP", "EV", "WB").forEach { c ->
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (selectedControl == c) Color.White else Color(0xFF222222))
                        .pointerInput(c) {
                            detectTapGestures {
                                selectedControl = if (selectedControl == c) null else c
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        c,
                        color = if (selectedControl == c) Color.Black else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // ── 选中控制项的滑杆面板（按钮下方）──
        selectedControl?.let { ctrl ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C1C1C))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                when (ctrl) {
                    "AF" -> Text("AF · 点按画面对焦", color = Color.White, fontSize = 12.sp)
                    "EXP" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("AE", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(34.dp))
                            FilterChip(
                                selected = !manualAE,
                                onClick = {
                                    manualAE = false
                                    controller.setManualExposure(false)
                                },
                                label = { Text("AUTO", fontSize = 10.sp) },
                            )
                            Spacer(Modifier.width(6.dp))
                            FilterChip(
                                selected = manualAE,
                                onClick = {
                                    manualAE = true
                                    controller.setManualExposure(true)
                                },
                                label = { Text("OFF", fontSize = 10.sp) },
                            )
                        }
                        PanelRow("SEC", secText(shutterNs)) {
                            Slider(value = shutterV, onValueChange = {
                                shutterV = it
                                controller.setExposureNs(10f.pow(5f + 4f * shutterV).roundToLong())
                            }, valueRange = 0f..1f)
                        }
                        PanelRow("ISO", "$iso") {
                            Slider(value = iso.toFloat(), onValueChange = {
                                iso = it.roundToInt()
                                controller.setIso(iso)
                            }, valueRange = 100f..controller.maxAnalogIso.toFloat())
                        }
                    }
                    "EV" -> {
                        PanelRow("EV", "%.1f".format(ev)) {
                            Slider(value = ev.coerceIn(-4f, 4f), onValueChange = {
                                ev = it
                                manualAE = false
                                controller.setManualExposure(false)
                                controller.setEv(ev)
                            }, valueRange = -4f..4f)
                        }
                    }
                    "WB" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("WB", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(30.dp))
                            FilterChip(
                                selected = !manualWb,
                                onClick = {
                                    manualWb = false
                                    controller.setAutoWb()
                                },
                                label = { Text("AUTO", fontSize = 10.sp) },
                            )
                            Spacer(Modifier.width(6.dp))
                            FilterChip(
                                selected = manualWb,
                                onClick = {
                                    manualWb = true
                                    controller.setWbK(wbK)
                                },
                                label = { Text("${wbK}K", fontSize = 10.sp) },
                            )
                        }
                        if (manualWb) {
                            PanelRow("K", "$wbK") {
                                Slider(value = wbK.toFloat(), onValueChange = {
                                    wbK = it.roundToInt()
                                    controller.setWbK(wbK)
                                }, valueRange = 2500f..7500f)
                            }
                        }
                    }
                }
            }
        }

        // ── 预设 + 快门 ──
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C1C1C))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CameraPresets.names.forEachIndexed { i, name ->
                    FilterChip(
                        selected = presetIdx == i,
                        onClick = {
                            presetIdx = i
                            controller.setFilmParams(CameraPresets.byIndex(i))
                        },
                        label = { Text(name, fontSize = 11.sp) },
                    )
                }
            }
            Box(
                Modifier
                    .size(70.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .pointerInput(Unit) { detectTapGestures { controller.capture() } },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
        }
    }
}

/** 面板内一行：标签 + 滑杆 + 值 */
@Composable
private fun PanelRow(label: String, value: String, slider: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.width(34.dp))
        Box(Modifier.weight(1f)) { slider() }
        Text(value, color = Color.White, fontSize = 11.sp, modifier = Modifier.width(44.dp))
    }
}
