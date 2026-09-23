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
            var autoToneOn by rememberSaveable { mutableStateOf(prefs.getBoolean("auto_tone", true)) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val v = !autoToneOn
                        autoToneOn = v
                        prefs.edit().putBoolean("auto_tone", v).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.auto_tone",
                            "{\"on\":$v}",
                        )
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自适应影调", color = FilmTheme.Cream, fontSize = 14.sp)
                    Text(
                        "按场景中位亮度自动算曝光增益（归一到中间调不动点，" +
                            "限幅 1–4×、只提亮不压暗）；关 = 固定 +0.35EV",
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
            var hlOn by rememberSaveable { mutableStateOf(prefs.getBoolean("hl_recon", true)) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val v = !hlOn
                        hlOn = v
                        prefs.edit().putBoolean("hl_recon", v).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.hl_recon",
                            "{\"on\":$v}",
                        )
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("高光重建", color = FilmTheme.Cream, fontSize = 14.sp)
                    Text(
                        "CCM 前用未饱和通道外推饱和通道（治高光偏色，如灯泡发品红）；" +
                            "仅 RCD 质量档生效",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    if (hlOn) "开" else "关",
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
            // M3 P1 色彩外观层（治"寡淡"）：亮度保持的饱和增益 + 色度 S 曲线。
            // 常开项 → 设置页；关 = 同机位 A/B 对照（参数在 CameraPresets[32]/[33]）。
            var lookOn by rememberSaveable { mutableStateOf(prefs.getBoolean("color_look", true)) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val v = !lookOn
                        lookOn = v
                        prefs.edit().putBoolean("color_look", v).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.color_look",
                            "{\"on\":$v}",
                        )
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("色彩外观层", color = FilmTheme.Cream, fontSize = 14.sp)
                    Text(
                        "亮度保持的饱和增益 + 色度 S 曲线；当前参数为中性占位（真机复核证明" +
                            "「寡淡」是测量口径造成的），浓淡留给 M4 的理光系 LUT",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    if (lookOn) "开" else "关",
                    color = FilmTheme.Accent,
                    fontSize = 13.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                )
            }
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
            // M3-2 降噪两档（v0.5.33）：关 → 标准 → 强 循环。旧 bool key 为 false
            // 时视为关（与 CameraController 读取逻辑一致）；写入时同步两个 key。
            var dnLevel by rememberSaveable {
                mutableStateOf(
                    if (prefs.getBoolean("noise_reduction", true)) {
                        prefs.getInt("noise_reduction_level", 1)
                    } else {
                        0
                    },
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val v = (dnLevel + 1) % 3
                        dnLevel = v
                        prefs.edit()
                            .putInt("noise_reduction_level", v)
                            .putBoolean("noise_reduction", v > 0)
                            .apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.noise_reduction",
                            "{\"level\":$v}",
                        )
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("RAW 降噪", color = FilmTheme.Cream, fontSize = 14.sp)
                    Text(
                        "RAW 域双边滤波 + 色度平滑，重点压彩噪；" +
                            "标准保细节 / 强更干净。仅 RCD 质量档生效",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    when (dnLevel) {
                        0 -> "关"
                        1 -> "标准"
                        else -> "强"
                    },
                    color = FilmTheme.Accent,
                    fontSize = 13.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                )
            }
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
