package com.filmkit.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmkit.app.EngineBridge
import com.filmkit.app.camera.CameraAudit
import com.filmkit.app.camera.CameraController
import com.filmkit.app.camera.NoiseCalibStore
import kotlin.math.roundToInt

/**
 * 设置主页（v0.4.9 二级菜单化）：主页只放导航行与开关，
 * 镜头配置、系统颜色为子页；日志、能力 Spike 在系统区。规范见 docs/开发计划.md「UI 设计规范」。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenSpike: () -> Unit = {},
    onOpenLenses: () -> Unit = {},
    onOpenAccent: () -> Unit = {},
    onOpenFocals: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }
    var dngEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean("dng_enabled", false)) }
    var wheelZoom by rememberSaveable { mutableStateOf(prefs.getBoolean("wheel_zoom", true)) }
    var auditPath by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(FilmTheme.Body)
            .statusBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SubPageTopBar("设置", onBack)
            Spacer(Modifier.height(6.dp))

            SectionHeader("拍摄")
            SettingSwitchRow(
                title = "DNG 底片保留",
                subtitle = "M 档（RAW）拍摄时同时保存 DNG 底片（单张约 10–20MB）",
                checked = dngEnabled,
                onChecked = { on ->
                    dngEnabled = on
                    prefs.edit().putBoolean("dng_enabled", on).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "settings.dng",
                        "{\"enabled\":$on}",
                    )
                },
            )
            SettingNavRow("镜头配置", "启停探测到的物理镜头") { onOpenLenses() }
            SettingNavRow("自定义焦段", "最多 3 个，与原生焦段同显于变焦条（总 ≤6）") { onOpenFocals() }
            var rcdOn by rememberSaveable { mutableStateOf(prefs.getBoolean("demosaic_quality", true)) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val v = !rcdOn
                        rcdOn = v
                        prefs.edit().putBoolean("demosaic_quality", v).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.demosaic_quality",
                            "{\"rcd\":$v}",
                        )
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("RAW 去马赛克", color = FilmTheme.Cream, fontSize = 14.sp)
                    Text(
                        "RCD 质量 = 方向自适应+色比修正，边缘伪彩少；" +
                            "快速 = bilinear，最快。仅影响 M 档成片",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    if (rcdOn) "RCD 质量" else "快速",
                    color = FilmTheme.Accent,
                    fontSize = 13.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            var toneOn by rememberSaveable { mutableStateOf(prefs.getBoolean("base_tone", true)) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val v = !toneOn
                        toneOn = v
                        prefs.edit().putBoolean("base_tone", v).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.base_tone",
                            "{\"on\":$v}",
                        )
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("基准影调", color = FilmTheme.Cream, fontSize = 14.sp)
                    Text(
                        "黑位 + 中间调 S + 高光 rolloff（治发灰）；" +
                            "关 = 旧行为（恒等，无影调工程）。预览与成片同步",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    if (toneOn) "开" else "关",
                    color = FilmTheme.Accent,
                    fontSize = 13.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // ── 基准曝光（v0.8.1）：机型 AE 基准差的校准常量 ─────────────────────────
            // 与 Lightroom/DNG 的 BaselineExposure 同语义。X300 对同场景参照渲染
            // 净差 +0.57 EV（v0.10.25 同 DNG 双渲染实测标定），默认 +0.7。
            // 用户 EV 滑杆叠加在其上（[0] = base + user），对齐参照后滑杆可归零。
            EvSliderRow(
                "基准曝光",
                "校正机型自动曝光的基准差：成片整体偏暗拖右、偏亮拖左。" +
                    "对齐同场景参照后，EV 滑杆归零即可",
                prefs.getFloat("base_ev", 0.7f),
                -2f..2f,
            ) { v ->
                prefs.edit().putFloat("base_ev", v).apply()
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "app", "settings.base_ev", "{\"v\":$v}",
                )
            }
            // ── 饱和补偿（v0.10.21 标定旋钮）──────────────────────────────
            // 原生 gamut 色度 min 的低饱和副作用由风格层（uSat 分区饱和）补偿：
            // darktable 的 filmic → color balance、PC 的 filmic → LUT 是同一分层。
            // 只作用阴影/中间调，高光不动（防过饱和）。定值后固化进默认。
            var satComp by rememberSaveable { mutableStateOf(prefs.getFloat("sat_comp", 0f)) }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("饱和补偿", color = FilmTheme.Cream, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(
                        "+%d%%".format((satComp * 100).toInt()),
                        color = FilmTheme.Accent,
                        fontSize = 13.sp,
                        fontFamily = FilmTheme.Mono,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "标定旋钮：补偿原生色度收缩（暗部/中间调），高光不动。" +
                        "对照同场景样张调到灰感消失，定值后回传",
                    color = FilmTheme.CreamDim,
                    fontSize = 10.sp,
                    style = MaterialTheme.typography.labelSmall,
                )
                androidx.compose.material3.Slider(
                    value = satComp,
                    onValueChange = { satComp = it },
                    onValueChangeFinished = {
                        val v = (satComp * 100).toInt() / 100f
                        satComp = v
                        prefs.edit().putFloat("sat_comp", v).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.sat_comp",
                            "{\"v\":$v}",
                        )
                    },
                    valueRange = 0f..0.30f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = FilmTheme.Cream,
                        activeTrackColor = FilmTheme.Accent,
                        inactiveTrackColor = FilmTheme.ChipBorder,
                    ),
                )
            }
            var autoToneOn by rememberSaveable { mutableStateOf(prefs.getBoolean("auto_black_ev", false)) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val v = !autoToneOn
                        autoToneOn = v
                        prefs.edit().putBoolean("auto_black_ev", v).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.auto_black_ev",
                            "{\"on\":$v}",
                        )
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自适应影调", color = FilmTheme.Cream, fontSize = 14.sp)
                    Text(
                        "实验性：黑白点按场景分位自动（darktable autodetect 语义）。" +
                            "默认关 = 固定 darktable 默认黑白点（−7.65/+4.56EV，与 PC 一致）——" +
                            "三版真机证实自动端点会把 power 推离设计值，亮度校准请用基准曝光",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    if (autoToneOn) "开" else "关",
                    color = FilmTheme.Accent,
                    fontSize = 13.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // ── filmic 黑白点（v0.7.6）：像 PhotonCamera 一样交给用户配置 ─────────────
            // 单位 = 相对中灰 18.45% 的 EV —— 物理量，换机型/换曲线都成立，所以它是
            // "配置项"而不是需要标定的数值。默认 −7.65/+4.56 = darktable 默认值。
            EvSliderRow(
                "黑点（多暗算黑）",
                "比它更暗的落到黑；拖左 = 暗部更黑、层次更少",
                prefs.getFloat("filmic_black_ev", -7.65f),
                -12f..-1f,
            ) { v ->
                prefs.edit().putFloat("filmic_black_ev", v).apply()
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "app", "settings.filmic_black_ev", "{\"v\":$v}",
                )
            }
            EvSliderRow(
                "白点（多亮算白）",
                "比它更亮的压到白；拖右 = 高光更亮、更容易过曝",
                prefs.getFloat("filmic_white_ev", 4.56f),
                1f..8f,
            ) { v ->
                prefs.edit().putFloat("filmic_white_ev", v).apply()
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "app", "settings.filmic_white_ev", "{\"v\":$v}",
                )
            }
            var localToneOn by rememberSaveable { mutableStateOf(prefs.getBoolean("local_tone", true)) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val v = !localToneOn
                        localToneOn = v
                        prefs.edit().putBoolean("local_tone", v).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.local_tone",
                            "{\"on\":$v}",
                        )
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("局部色调映射", color = FilmTheme.Cream, fontSize = 14.sp)
                    Text(
                        "只提亮暗部：按画面局部亮度补光，亮区/高光强度归零。" +
                            "治「边缘有高光时中央被压暗」—— 全局曲线在逆光下两难",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    if (localToneOn) "开" else "关",
                    color = FilmTheme.Accent,
                    fontSize = 13.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // M3 P0 镜头阴影校正（v0.5.43）：HAL 增益网格治暗角 + 角落色偏。
            // 常开开关，归设置页（快捷面板只放拍摄时会频繁切的项）。
            var lscOn by rememberSaveable { mutableStateOf(prefs.getBoolean("lsc", true)) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val v = !lscOn
                        lscOn = v
                        prefs.edit().putBoolean("lsc", v).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.lsc",
                            "{\"on\":$v}",
                        )
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("镜头阴影校正", color = FilmTheme.Cream, fontSize = 14.sp)
                    Text(
                        "HAL 增益网格消暗角与角落色偏（RAW 链、CCM 前，17×13 手写双线性）；" +
                            "关 = 同机位 A/B 对照",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    if (lscOn) "开" else "关",
                    color = FilmTheme.Accent,
                    fontSize = 13.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // v0.7.9：「色彩外观层」设置项已删除 —— 该层（M3 P1 治"寡淡"）在 filmic 链下
            // 恒等（v0.7.7 起 look_sat/look_chroma = 1.0，`1+(c-1)*sk` ≡ 1），开关只控制
            // 一个空操作，还占设置页心智。FilmParams[31..33] 槽位保留（契约不移位）。
            // M3 P2 DNG 标准色彩链（§3.2-7 / §3.4-4）：用 characteristics 的标定矩阵
            // （ColorMatrix/ForwardMatrix 双光源 + Bradford D50→sRGB）自建 camera→sRGB，
            // 替代 HAL 的 COLOR_CORRECTION_TRANSFORM；关 = 用 HAL 的做同机位 A/B。
            var stdCcmOn by rememberSaveable { mutableStateOf(prefs.getBoolean("ccm_std", true)) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val v = !stdCcmOn
                        stdCcmOn = v
                        prefs.edit().putBoolean("ccm_std", v).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.ccm_std",
                            "{\"on\":$v}",
                        )
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("DNG 标准色彩链", color = FilmTheme.Cream, fontSize = 14.sp)
                    Text(
                        "用相机标定矩阵自建色彩链（与 Adobe/libraw 完整链同源，标定数据缺失" +
                            "自动回退）；关 = 用 HAL 矩阵做 A/B",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    if (stdCcmOn) "开" else "关",
                    color = FilmTheme.Accent,
                    fontSize = 13.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // RAW 降噪强度（C 专项 2026-09-29：滑杆 0–2、步长 0.01）——
            // 0 = 关、1 = 真实噪声 σ（标准做法）、2 = 2×σ。作用于 P0/P4 的逐像素
            // 值域 σ（噪声模型 σ²=A·x+B，逐通道）；写入时同步旧 keys 供回退读取。
            var dnStr by rememberSaveable {
                mutableStateOf(
                    CameraController.denoiseStrength(
                        prefs.contains("noise_reduction_strength"),
                        prefs.getFloat("noise_reduction_strength", 1f),
                        prefs.getBoolean("noise_reduction", true),
                        prefs.getInt("noise_reduction_level", 1),
                    ),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("RAW 降噪", color = FilmTheme.Cream, fontSize = 14.sp)
                        Text(
                            "RAW 域双边 + 色度平滑，σ 由噪声模型逐像素给出；" +
                                "0 = 关、1 = 真实 σ、2 = 2×σ。仅 RCD 质量档生效",
                            color = FilmTheme.CreamDim,
                            fontSize = 10.sp,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        "%.2f".format(dnStr),
                        color = FilmTheme.Accent,
                        fontSize = 13.sp,
                        fontFamily = FilmTheme.Mono,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                androidx.compose.material3.Slider(
                    value = dnStr,
                    onValueChange = { dnStr = it },
                    onValueChangeFinished = {
                        prefs.edit()
                            .putFloat("noise_reduction_strength", dnStr)
                            .putBoolean("noise_reduction", dnStr > 0f)
                            .putInt(
                                "noise_reduction_level",
                                if (dnStr <= 0f) 0 else if (dnStr <= 1.5f) 1 else 2,
                            )
                            .apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.noise_reduction",
                            "{\"strength\":\"%.2f\"}".format(dnStr),
                        )
                    },
                    valueRange = 0f..2f,
                    steps = 199,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = FilmTheme.Cream,
                        activeTrackColor = FilmTheme.Accent,
                        inactiveTrackColor = FilmTheme.ChipBorder,
                    ),
                )
            }
            // MFNR 多帧融合（v0.9.0 阶段一）：关 / 自动（暗光触发）/ 开（强制）三档。
            // 触发判定在 CameraController.mfnrShouldTrigger（与单测同源）。
            var mfnrMode by rememberSaveable { mutableStateOf(prefs.getInt("mfnr", 1)) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val v = (mfnrMode + 1) % 3
                        mfnrMode = v
                        prefs.edit().putInt("mfnr", v).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.mfnr",
                            "{\"mode\":$v}",
                        )
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("多帧融合", color = FilmTheme.Cream, fontSize = 14.sp)
                    Text(
                        "暗光连拍 4 帧对齐合成降噪（彩噪 √4 根治）；" +
                            "仅 M 档 RAW + RCD 质量档生效。「开」= 每次都连拍",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    when (mfnrMode) {
                        0 -> "关"
                        1 -> "自动"
                        else -> "开"
                    },
                    color = FilmTheme.Accent,
                    fontSize = 13.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // 频域合并（降噪专项 A，2026-09-29 立项）：HDR+ §5 式 6/7 DFT 逐频
            // 收缩合并，替换 mismatch 加权融合。并联开关保留原路径（A/B 口径）。
            var mfnrDft by rememberSaveable {
                mutableStateOf(prefs.getBoolean("mfnr_dft", false))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        mfnrDft = !mfnrDft
                        prefs.edit().putBoolean("mfnr_dft", mfnrDft).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.mfnr_dft",
                            "{\"on\":$mfnrDft}",
                        )
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("频域合并（实验）", color = FilmTheme.Cream, fontSize = 14.sp)
                    Text(
                        "HDR+ 式 DFT 逐频收缩（16×16 tile 半重叠，暗光 σ 更优）；" +
                            "关 = mismatch 加权融合",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    if (mfnrDft) "开" else "关",
                    color = FilmTheme.Accent,
                    fontSize = 13.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // 噪声模型来源（C 专项，2026-09-29；用户需求：PC 式可导入/可切换）
            //   自动 = Camera2 SENSOR_NOISE_PROFILE（默认）；或选择导入的 GCam .c
            //   标定（filesDir/noise_models/，按 ISO 求值）。作用于 P0/P4 降噪与
            //   MFNR mismatch 的噪声底；选择立即对后续拍照生效。
            var nmSel by rememberSaveable {
                mutableStateOf(
                    prefs.getString("noise_model_src", NoiseCalibStore.AUTO)
                        ?: NoiseCalibStore.AUTO,
                )
            }
            var nmDialog by remember { mutableStateOf(false) }
            var nmList by remember { mutableStateOf(NoiseCalibStore.list(context)) }
            val nmImport = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    val nm = NoiseCalibStore.importFrom(context, uri, uri.lastPathSegment)
                    if (nm != null) {
                        nmSel = nm
                        prefs.edit().putString("noise_model_src", nm).apply()
                        nmList = NoiseCalibStore.list(context)
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.noise_model",
                            "{\"src\":\"$nm\",\"act\":\"import\"}",
                        )
                    } else {
                        android.widget.Toast.makeText(
                            context, "导入失败：不是有效的 GCam 噪声标定文件（.c）",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        nmList = NoiseCalibStore.list(context)
                        nmDialog = true
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("噪声模型", color = FilmTheme.Cream, fontSize = 14.sp)
                    Text(
                        "降噪与 MFNR 的噪声底来源：自动 = 相机 HAL 标定；可导入 GCam " +
                            "标定文件（.c）按 ISO 生效",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    if (nmSel == NoiseCalibStore.AUTO) "自动" else nmSel.removeSuffix(".c"),
                    color = FilmTheme.Accent,
                    fontSize = 13.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (nmDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { nmDialog = false },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { nmDialog = false }) {
                            Text("关闭", color = FilmTheme.CreamDim, fontFamily = FilmTheme.Mono)
                        }
                    },
                    title = { Text("噪声模型", color = FilmTheme.Cream, fontFamily = FilmTheme.Serif) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            val pick: (String) -> Unit = { id ->
                                nmSel = id
                                prefs.edit().putString("noise_model_src", id).apply()
                                EngineBridge.nativeAppendLog(
                                    EngineBridge.LEVEL_INFO, "app", "settings.noise_model",
                                    "{\"src\":\"$id\",\"act\":\"select\"}",
                                )
                                nmDialog = false
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { pick(NoiseCalibStore.AUTO) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "自动（相机标定）",
                                    color = FilmTheme.Cream,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                if (nmSel == NoiseCalibStore.AUTO) {
                                    Text("✓", color = FilmTheme.Accent, fontFamily = FilmTheme.Mono)
                                }
                            }
                            nmList.forEach { cal ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { pick(cal.id) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        cal.name,
                                        color = FilmTheme.Cream,
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (nmSel == cal.id) {
                                        Text("✓", color = FilmTheme.Accent, fontFamily = FilmTheme.Mono)
                                    }
                                    Text(
                                        "删除",
                                        color = FilmTheme.CreamDim,
                                        fontSize = 11.sp,
                                        fontFamily = FilmTheme.Mono,
                                        modifier = Modifier
                                            .clickable {
                                                NoiseCalibStore.delete(context, cal.id)
                                                if (nmSel == cal.id) {
                                                    nmSel = NoiseCalibStore.AUTO
                                                    prefs.edit()
                                                        .putString(
                                                            "noise_model_src",
                                                            NoiseCalibStore.AUTO,
                                                        )
                                                        .apply()
                                                }
                                                nmList = NoiseCalibStore.list(context)
                                            }
                                            .padding(start = 12.dp),
                                    )
                                }
                            }
                            HorizontalDivider(color = FilmTheme.ChipBorder)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { nmImport.launch(arrayOf("*/*")) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "+ 导入 .c 标定文件",
                                    color = FilmTheme.Accent,
                                    fontSize = 14.sp,
                                    fontFamily = FilmTheme.Mono,
                                )
                            }
                            Text(
                                "文件为 GCam 噪声标定格式（noise_model_A/B/C/D 四数组）；" +
                                    "可从相机社区获取或自行标定生成",
                                color = FilmTheme.CreamDim,
                                fontSize = 10.sp,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    containerColor = FilmTheme.Panel,
                )
            }
            // 轻度锐化（v0.9.6）：OETF 前线性显示域 luma USM，补 MFNR 后观感细节。
            // 关 = A/B 通路（同场景对照归因）。
            var sharpenOn by rememberSaveable { mutableStateOf(prefs.getBoolean("sharpen", true)) }
            SettingSwitchRow(
                title = "轻度锐化",
                subtitle = "线性显示域 luma USM（OETF 前，暗部噪声不放大），补观感细节；关 = 同场景 A/B 对照",
                checked = sharpenOn,
                onChecked = { v ->
                    sharpenOn = v
                    prefs.edit().putBoolean("sharpen", v).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "settings.sharpen",
                        "{\"on\":$v}",
                    )
                },
            )
            // 色域映射（v0.10.13，v0.10.14 修正）：治色域外/病态像素的越界色
            // （高光偏色、过饱和过冲）——域内像素零改动。关 = A/B 通路。
            var gamutOn by rememberSaveable { mutableStateOf(prefs.getBoolean("gamut", true)) }
            SettingSwitchRow(
                title = "色域映射",
                subtitle = "filmic 色彩收尾：sRGB 色域内映射（治高光偏色/过饱和），域内像素不动；关 = 同场景 A/B 对照",
                checked = gamutOn,
                onChecked = { v ->
                    gamutOn = v
                    prefs.edit().putBoolean("gamut", v).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "settings.gamut",
                        "{\"on\":$v}",
                    )
                },
            )
            // 高光重建·小波（v0.10.15）：darktable filmic 原生高光重建（à trous 小波，
            // mask/inpaint/两轮重建全链），治部分通道裁剪高光的品红残留。关 = A/B。
            var hrWaveletOn by rememberSaveable { mutableStateOf(prefs.getBoolean("hr_wavelet", true)) }
            SettingSwitchRow(
                title = "高光重建·小波",
                subtitle = "darktable filmic 原生高光重建（à trous 小波，全分辨率），治过曝边缘品红残留；关 = 同场景 A/B 对照",
                checked = hrWaveletOn,
                onChecked = { v ->
                    hrWaveletOn = v
                    prefs.edit().putBoolean("hr_wavelet", v).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "settings.hr_wavelet",
                        "{\"on\":$v}",
                    )
                },
            )
            // 白点高光感知（v0.10.27）：自适应影调开启时，对数域统计的 P99.9 驱动
            // filmic 白点上探（≤+1.5EV）+ contrast 参数补偿，治"高光太白压不住"。
            var whiteHlOn by rememberSaveable { mutableStateOf(prefs.getBoolean("white_hl", true)) }
            SettingSwitchRow(
                title = "高光自适应白点",
                subtitle = "开自适应影调时按场景高光自动扩展 filmic 白点（含对比补偿），治高光平顶泛白；关 = 旧判据 A/B 对照",
                checked = whiteHlOn,
                onChecked = { v ->
                    whiteHlOn = v
                    prefs.edit().putBoolean("white_hl", v).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "settings.white_hl",
                        "{\"on\":$v}",
                    )
                },
            )
            SettingSwitchRow(
                title = "长按焦段轮盘变焦",
                subtitle = "长按变焦刻度弹出轮盘（含原生焦段刻度，上限 6 个），上下拖动变焦",
                checked = wheelZoom,
                onChecked = { v ->
                    wheelZoom = v
                    prefs.edit().putBoolean("wheel_zoom", v).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "settings.wheel_zoom",
                        "{\"on\":$v}",
                    )
                },
            )
            var yuv709 by rememberSaveable { mutableStateOf(prefs.getBoolean("yuv_709", false)) }
            SettingSwitchRow(
                title = "静拍 YUV 解码 BT.709",
                subtitle = "2026-09-20 实拍 A/B 定量：两种矩阵暗部 R/G 仅差 0.001，已确认 601 正确；此开关保留供未来机型复查",
                checked = yuv709,
                onChecked = { v ->
                    yuv709 = v
                    prefs.edit().putBoolean("yuv_709", v).apply()
                    EngineBridge.nativeEngineSetYuvMatrix(if (v) 1 else 0)
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "settings.yuv709",
                        "{\"on\":$v}",
                    )
                },
            )
            var jpegQ by rememberSaveable { mutableStateOf(prefs.getInt("jpeg_quality", 95)) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val v = if (jpegQ >= 100) 95 else 100
                        jpegQ = v
                        prefs.edit().putInt("jpeg_quality", v).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.jpeg_quality",
                            "{\"q\":$v}",
                        )
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("成像质量", color = FilmTheme.Cream, fontSize = 14.sp)
                    Text(
                        "95 = 标准；100 = 极高（文件体积约为 2–3 倍）",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    "$jpegQ",
                    color = FilmTheme.Accent,
                    fontSize = 15.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            var lutInName by rememberSaveable { mutableStateOf(prefs.getBoolean("lut_in_name", true)) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val v = !lutInName
                        lutInName = v
                        prefs.edit().putBoolean("lut_in_name", v).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.lut_in_name",
                            "{\"on\":$v}",
                        )
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("LUT 名写入文件名", color = FilmTheme.Cream, fontSize = 14.sp)
                    Text(
                        "成片名追加 _<LUT 名>（如 …_理光正片.jpg）；未选 LUT 时不加",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    if (lutInName) "开" else "关",
                    color = FilmTheme.Accent,
                    fontSize = 13.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = FilmTheme.ChipBorder)
            SectionHeader("系统")
            SettingNavRow("系统颜色", "按钮选中色与刻度色，默认琥珀橙") { onOpenAccent() }
            SettingNavRow("导出相机能力审计", null) {
                auditPath = CameraAudit.export(context)
            }
            auditPath?.let {
                Text(
                    "已导出：$it",
                    color = FilmTheme.CreamDim,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                )
            }
            SettingNavRow("日志", "引擎/采集 JSONL 日志查看与导出") { onOpenLogs() }
            SettingNavRow("能力 / Spike", "GL 能力与三段链基准") { onOpenSpike() }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = FilmTheme.ChipBorder)
            SectionHeader("关于")
            Text(
                "FilmKit 胶片相机 · M3 开发中 · vivo X300 专项适配",
                color = FilmTheme.CreamDim,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

/** 镜头配置子页：物理镜头启停（超广等隐藏镜头输出黑帧时在此关闭） */
@Composable
fun LensConfigScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }
    val cm = remember {
        context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
    }
    val lenses = remember { CameraController.LensDiscovery.discoverBackLenses(cm, prefs) }

    Column(
        Modifier
            .fillMaxSize()
            .background(FilmTheme.Body)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        SubPageTopBar("镜头配置", onBack)
        Text(
            "探测到的物理镜头；部分厂商隐藏镜头可能输出异常，可在此关闭",
            color = FilmTheme.CreamDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(6.dp))
        if (lenses.isEmpty()) {
            Text("（未探测到后置镜头）", color = FilmTheme.CreamDim, fontSize = 12.sp)
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            lenses.forEach { lens ->
                val key = "lens_on_${lens.id}"
                var on by rememberSaveable(lens.id) { mutableStateOf(prefs.getBoolean(key, true)) }
                SettingSwitchRow(
                    title = "ID ${lens.id} · ${lens.label()}",
                    subtitle = "物理焦距 ${lens.focalMm}mm${if (lens.rawSupported) " · RAW" else ""}",
                    checked = on,
                    onChecked = { v ->
                        on = v
                        prefs.edit().putBoolean(key, v).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "camera", "lens.enabled_set",
                            com.filmkit.app.kv("id" to lens.id, "enabled" to v),
                        )
                    },
                )
            }
        }
    }
}

/** 系统颜色子页：强调色配置（按钮选中色、刻度色），默认琥珀橙 */
@Composable
fun AccentColorScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }
    val current = FilmTheme.Accent

    Column(
        Modifier
            .fillMaxSize()
            .background(FilmTheme.Body)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        SubPageTopBar("系统颜色", onBack)
        Text(
            "控制按钮选中色、参数刻度色与全 App 强调色",
            color = FilmTheme.CreamDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        AccentSwatches.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                row.forEach { (name, c) ->
                    Column(
                        Modifier
                            .size(76.dp)
                            .pointerInput(c) {
                                detectTapGestures {
                                    FilmTheme.setAccent(c)
                                    prefs.edit().putInt("accent_argb", c.toArgb()).apply()
                                    EngineBridge.nativeAppendLog(
                                        EngineBridge.LEVEL_INFO, "app", "settings.accent",
                                        "\"argb\":${c.toArgb()}",
                                    )
                                }
                            }
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    2.dp,
                                    if (c == current) FilmTheme.Cream else FilmTheme.ChipBorder,
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            name,
                            color = if (c == current) FilmTheme.Cream else FilmTheme.CreamDim,
                            fontSize = 9.sp,
                        )
                    }
                }
            }
        }
    }
}

private val AccentSwatches = listOf(
    "琥珀橙" to Color(0xFFE8842C),
    "胶片红" to Color(0xFFD94F3D),
    "苔原绿" to Color(0xFF6FA85A),
    "湖水青" to Color(0xFF3FA8A8),
    "晴空蓝" to Color(0xFF4E8FD9),
    "薰衣草紫" to Color(0xFF9B7BD4),
    "芥末金" to Color(0xFFD9B44A),
    "银盐灰" to Color(0xFFA8A8A0),
)

/** 区块标题：强调色等宽小字（全 App 统一） */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = FilmTheme.Accent,
        fontSize = 11.sp,
        fontFamily = FilmTheme.Mono,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

/** 开关行：标题 + 次级说明 + 强调色开关 */
@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = FilmTheme.Cream, fontSize = 14.sp)
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = FilmTheme.CreamDim,
                    fontSize = 10.sp,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = FilmTheme.Accent,
                checkedThumbColor = FilmTheme.Cream,
                uncheckedTrackColor = FilmTheme.ChipBg,
                uncheckedThumbColor = FilmTheme.CreamDim,
                uncheckedBorderColor = FilmTheme.ChipBorder,
            ),
        )
    }
}

/** 导航行：标题 + 次级说明 + ›（进入子页 / 触发动作） */
@Composable
private fun SettingNavRow(title: String, subtitle: String?, onTap: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onTap() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = FilmTheme.Cream, fontSize = 14.sp)
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = FilmTheme.CreamDim,
                    fontSize = 10.sp,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Text("›", color = FilmTheme.CreamDim, fontSize = 16.sp)
    }
}

/** 自定义焦段子页：最多 3 个（20–120mm，步进 5），与原生焦段同显于变焦条（总 ≤6） */
@Composable
fun FocalScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }
    // CSV 字符串作为可保存状态（List<Int> 不能进 Bundle）
    var focalsCsv by rememberSaveable { mutableStateOf(prefs.getString("custom_focals", "") ?: "") }
    val focals = focalsCsv.split(",").mapNotNull { it.toIntOrNull() }.sorted()
    var addOpen by remember { mutableStateOf(false) }
    var sliderV by remember { mutableStateOf(35f) }

    fun save(list: List<Int>) {
        focalsCsv = list.distinct().sorted().take(3).joinToString(",")
        prefs.edit().putString("custom_focals", focalsCsv).apply()
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "app", "settings.custom_focals",
            com.filmkit.app.kv("focals" to focalsCsv),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(FilmTheme.Body)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        SubPageTopBar("自定义焦段", onBack)
        Text(
            "在原生镜头焦段之外添加常用焦段（点按 = 无缝变焦到该焦段）；最多 3 个，20–120mm",
            color = FilmTheme.CreamDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        focals.forEach { f ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${f}mm",
                    color = FilmTheme.Cream,
                    fontSize = 16.sp,
                    fontFamily = FilmTheme.Serif,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "移除",
                    color = FilmTheme.Accent,
                    fontSize = 12.sp,
                    fontFamily = FilmTheme.Mono,
                    modifier = Modifier.clickable { save(focals - f) },
                )
            }
        }
        if (focals.size < 3) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { sliderV = 35f; addOpen = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("+ 添加焦段", color = FilmTheme.Cream, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("›", color = FilmTheme.CreamDim, fontSize = 16.sp)
            }
        }

        if (addOpen) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { addOpen = false },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            save(focals + sliderV.roundToInt())
                            addOpen = false
                        },
                    ) { Text("添加", color = FilmTheme.Accent, fontFamily = FilmTheme.Mono) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { addOpen = false }) {
                        Text("取消", color = FilmTheme.CreamDim, fontFamily = FilmTheme.Mono)
                    }
                },
                title = { Text("添加焦段", color = FilmTheme.Cream, fontFamily = FilmTheme.Serif) },
                text = {
                    Column {
                        Text(
                            "${sliderV.roundToInt()}mm",
                            color = FilmTheme.Accent,
                            fontSize = 24.sp,
                            fontFamily = FilmTheme.Mono,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        androidx.compose.material3.Slider(
                            value = sliderV,
                            onValueChange = { sliderV = it },
                            valueRange = 20f..120f,
                            steps = 19,
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = FilmTheme.Cream,
                                activeTrackColor = FilmTheme.Accent,
                                inactiveTrackColor = FilmTheme.ChipBorder,
                            ),
                        )
                    }
                },
                containerColor = FilmTheme.Panel,
            )
        }
    }
}

/**
 * 一行 EV 滑杆（v0.7.6）：用于 filmic 黑白点这类"物理量配置项"。
 * 拖动时只更新本地状态，**松手才提交**（避免每帧写 prefs）。
 */
@Composable
private fun EvSliderRow(
    label: String,
    desc: String,
    initial: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit,
) {
    var v by rememberSaveable { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = FilmTheme.Cream, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(
                "%+.2f EV".format(v),
                color = FilmTheme.Accent,
                fontSize = 13.sp,
                fontFamily = FilmTheme.Mono,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            desc,
            color = FilmTheme.CreamDim,
            fontSize = 10.sp,
            style = MaterialTheme.typography.labelSmall,
        )
        androidx.compose.material3.Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onCommit(v) },
            valueRange = range,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = FilmTheme.Cream,
                activeTrackColor = FilmTheme.Accent,
                inactiveTrackColor = FilmTheme.ChipBorder,
            ),
        )
    }
}
