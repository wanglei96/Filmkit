package com.filmkit.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
import com.filmkit.app.heif.HeifChroma
import com.filmkit.app.heif.HeifJpeg444
import com.filmkit.app.heif.PhotoFormat
import com.filmkit.app.hdr.HdrDelivery
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmkit.app.EngineBridge
import com.filmkit.app.camera.BuiltinPresets
import com.filmkit.app.camera.CameraAudit
import com.filmkit.app.camera.CameraController
import com.filmkit.app.camera.NoiseCalibStore
import com.filmkit.app.camera.resolveCameraPreset
import com.filmkit.app.diag.Heif10BitProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 设置主页（整理版）：主页只放拍摄高频项、四个抽屉导航行与系统区——
 * 工程项按域收进抽屉（画质 / 影调与色彩 / 暗光与曝光 / 实验与诊断），
 * 二级内容不再平铺主页（规范：二级内容一律进子页，不堆主页）。
 * 删除项：NN 降噪与 NN A/B（路线已判死，读取端默认关）、静拍 YUV BT.709（已定案 601 正确）。
 * 全部 prefs 键、写入逻辑与日志字段与整理前一致。规范见 docs/开发计划.md「UI 设计规范」。
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
    var qualityOpen by rememberSaveable { mutableStateOf(false) }
    var toneOpen by rememberSaveable { mutableStateOf(false) }
    var lowlightOpen by rememberSaveable { mutableStateOf(false) }
    var labOpen by rememberSaveable { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(FilmTheme.Body),
    ) {
        Column(
            Modifier
                .fillMaxSize()
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
            var jpegQ by rememberSaveable { mutableStateOf(prefs.getInt("jpeg_quality", 100)) }
            SettingValueRow(
                title = "成像质量",
                subtitle = "95 = 标准；100 = 极高（文件体积约为 2–3 倍）",
                valueText = "$jpegQ",
                onTap = {
                    val v = if (jpegQ >= 100) 95 else 100
                    jpegQ = v
                    prefs.edit().putInt("jpeg_quality", v).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "settings.jpeg_quality",
                        "{\"q\":$v}",
                    )
                },
            )
            // 照片格式必须**先声明**：下面的「4:4:4 色度」行要按档位灰显（HEIF 恒 4:2:0）。
            var photoFormat by remember { mutableStateOf(PhotoFormat.read(prefs)) }
            var jpeg444 by rememberSaveable { mutableStateOf(prefs.getBoolean("jpeg_444", true)) }
            SettingSwitchRow(
                title = "成片 4:4:4 色度",
                // ⚠ 措辞要点（卡 FIX2）：不是"HEIF 不支持这个选项"，而是 HEIF 走硬件编码器的
                // 10bit P010（8bit 档走 YUV420），色度**固定 4:2:0** ⇒ 此项只作用于 JPEG。
                subtitle = if (photoFormat == PhotoFormat.HEIF) {
                    "HEIF 走硬件编码器的 10bit P010（8bit 档走 YUV420），色度固定 4:2:0，" +
                        "故此项仅对 JPEG 生效（选 HEIF 时已自动关闭，切回 JPEG 会恢复原值）"
                } else {
                    "开 = 色度不降采样（默认；治强亮度边缘的紫/绿镶边，体积约 1.9×）；关 = 4:2:0 旧行为"
                },
                checked = jpeg444,
                enabled = photoFormat != PhotoFormat.HEIF,
                onChecked = { on ->
                    jpeg444 = on
                    prefs.edit().putBoolean("jpeg_444", on).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "settings.jpeg_444",
                        "{\"on\":$on}",
                    )
                },
            )
            // ── 卡 H2：照片格式（用户 2026-10-14 拍板 P3：默认 JPEG，HEIF 可选）──────
            // 两档循环，交互沿用同文件既有形态（点一次切一档，不新造控件）。
            SettingValueRow(
                title = "照片格式",
                subtitle = "JPEG = 现有交付链（默认，一字不动）；HEIF = 10bit 交付" +
                    "（HEVC Main10 + sRGB 显示域，容器 colr/nclx = 1/13/1/1，pixi 3×10bit）。" +
                    "HEIF 编码失败会自动回退 8bit HEIF，再失败回退 JPEG；落盘后读头自检不合格" +
                    "同样降级 —— 不会丢照片",
                valueText = PhotoFormat.label(photoFormat),
                onTap = {
                    val v = if (photoFormat == PhotoFormat.HEIF) PhotoFormat.JPEG else PhotoFormat.HEIF
                    photoFormat = v
                    PhotoFormat.write(prefs, v)
                    // 卡 FIX2（用户实机行为对齐）：选 HEIF 时替用户关掉 4:4:4（HEIF 恒 4:2:0，
                    // 该开关只作用于 JPEG），并把原值备份；切回 JPEG 精确恢复。
                    val p444 = object : HeifJpeg444.Prefs {
                        override fun getBoolean(key: String, def: Boolean) = prefs.getBoolean(key, def)
                        override fun has(key: String) = prefs.contains(key)
                        override fun putBoolean(key: String, value: Boolean) {
                            prefs.edit().putBoolean(key, value).apply()
                        }
                        override fun remove(key: String) {
                            prefs.edit().remove(key).apply()
                        }
                    }
                    val hadBackup = HeifJpeg444.hasBackup(p444)
                    jpeg444 = if (v == PhotoFormat.HEIF) HeifJpeg444.onHeif(p444)
                    else HeifJpeg444.onJpeg(p444)
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "settings.photo_format",
                        "{\"photo_format\":\"${if (v == PhotoFormat.HEIF) "heif" else "jpeg"}\"" +
                            ",\"chroma\":\"${HeifChroma.CHROMA}\"" +
                            ",\"jpeg444\":$jpeg444,\"jpeg444Ignored\":${!HeifChroma.honorsJpeg444(jpeg444)}" +
                            ",\"jpeg444AutoOff\":${v == PhotoFormat.HEIF && !jpeg444}" +
                            (if (hadBackup) ",\"jpeg444Restored\":true" else "") + "}",
                    )
                },
            )
            // ── HDR 成片（v0.11.50，用户 2026-10-19 要求：4:2:0 / 4:4:4 / HEIC 都能出 HDR）──
            // 默认**关**：与 HEIF 同一条红线 —— 新路径必须先经真机验收，不能默认落在
            // 未验证的链上。开启后：JPEG 4:4:4 走自研 libultrahdr 封装（保 4:4:4），
            // JPEG 4:2:0 与 HEIC 走平台 Gainmap API（Android 14+）。任何一步失败都回退
            // 普通 SDR 成片，绝不丢照片。
            var hdrOn by rememberSaveable { mutableStateOf(prefs.getBoolean("hdr_gainmap", false)) }
            SettingSwitchRow(
                title = "HDR 成片（Ultra HDR / gainmap）",
                subtitle = "只影响新拍的照片是否附带 HDR 增益图；相册里已有 HDR 图的显示由查看页" +
                    "每张图自己的开关决定，与本开关无关。系统相册/Chrome 等支持 HDR 的查看器会显示" +
                    "更亮的高光，不支持的环境自动回落 SDR。当前能力：" + HdrDelivery.capabilitySummary() +
                    "。需 Android 14+；失败自动回退普通成片",
                checked = hdrOn,
                onChecked = { on ->
                    hdrOn = on
                    prefs.edit().putBoolean("hdr_gainmap", on).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "settings.hdr_gainmap",
                        "{\"on\":$on,\"jpegR\":" + HdrDelivery.jpegRSupported +
                            ",\"platform\":" + HdrDelivery.platformGainmapSupported + "}",
                    )
                },
            )
            // ── gainmap 数学版本（v0.11.50，真机 A/B 入口）──────────────────
            // 只在 HDR 开启时有意义。两版**最终都归一化到固定 [0.25, 4.0]**，
            // 差异只在 offset（1e-4 vs 1e-7）与暗部钳位方式。
            if (hdrOn) {
                var gmMath by rememberSaveable {
                    mutableStateOf(prefs.getInt("gainmap_math", 0))
                }
                SettingValueRow(
                    title = "gainmap 数学",
                    subtitle = if (gmMath == 0) {
                        "PhotonCamera 版：offset 1e-4，靠垫高分母抑制暗部噪声。" +
                            "与 PC 成片同口径（默认）"
                    } else {
                        "libultrahdr 官方版：offset 1e-7 + 暗部 gain 钳 ≤2.3（硬钳位）。" +
                            "暗部更容易放大噪声，但高光表达更贴近原始比值"
                    },
                    valueText = if (gmMath == 0) "PC" else "官方",
                    onTap = {
                        val v = if (gmMath == 0) 1 else 0
                        gmMath = v
                        prefs.edit().putInt("gainmap_math", v).apply()
                        EngineBridge.nativeSetGainmapMath(v)
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "app", "settings.gainmap_math",
                            "{\"mode\":$v,\"name\":\"" + (if (v == 0) "pc" else "official") + "\"}",
                        )
                    },
                )
            }
            SettingNavRow("镜头配置", "启停探测到的物理镜头") { onOpenLenses() }
            SettingNavRow("自定义焦段", "最多 3 个，与原生焦段同显于变焦条（总 ≤6）") { onOpenFocals() }
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
            var lutInName by rememberSaveable { mutableStateOf(prefs.getBoolean("lut_in_name", true)) }
            SettingSwitchRow(
                title = "LUT 名写入文件名",
                subtitle = "成片名追加 _<LUT 名>（如 …_理光正片.jpg）；未选 LUT 时不加",
                checked = lutInName,
                onChecked = { v ->
                    lutInName = v
                    prefs.edit().putBoolean("lut_in_name", v).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "settings.lut_in_name",
                        "{\"on\":$v}",
                    )
                },
            )

            SectionHeader("成像")
            SettingNavRow("画质", "去马赛克 · 多帧融合 · 降噪 · 噪声模型 · 镜头校正 · 锐化") {
                qualityOpen = true
            }
            SettingNavRow("影调与色彩", "基准影调 · 曝光基准 · 黑白点 · 色彩链 · 色域 · 高光重建") {
                toneOpen = true
            }
            SettingNavRow("暗光与曝光", "长曝光进光 · 曝光上限 · 高光保护") {
                lowlightOpen = true
            }
            SettingNavRow("实验与诊断", "频域合并 · 对齐精度 · 亚像素 · 独立线程 · A/B 开关") {
                labOpen = true
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
            SettingNavRow("能力 / Spike", "GL 能力 / 三段链 / NN 推理基准") { onOpenSpike() }

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

        SettingsDrawer(visible = qualityOpen, title = "画质", onDismiss = { qualityOpen = false }) {
            QualityDrawerContent()
        }
        SettingsDrawer(visible = toneOpen, title = "影调与色彩", onDismiss = { toneOpen = false }) {
            ToneColorDrawerContent()
        }
        SettingsDrawer(visible = lowlightOpen, title = "暗光与曝光", onDismiss = { lowlightOpen = false }) {
            LowLightDrawerContent()
        }
        SettingsDrawer(visible = labOpen, title = "实验与诊断", onDismiss = { labOpen = false }) {
            LabDrawerContent()
        }
    }
}

/** 抽屉内容：画质（去马赛克档 / 多帧融合 / 降噪 / 噪声模型 / LSC / 锐化） */
@Composable
private fun QualityDrawerContent() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }

    // v0.10.172：RCD / Amaze 二选一（用户明确要求），并保留既有"快速 = bilinear"档。
    // 取值：0 = 快速(bilinear) / 1 = RCD 质量（默认）/ 2 = Amaze。
    // 读旧 prefs（demosaic_quality 布尔）做迁移：未写过新键时按旧值推断。
    var demosaicMode by remember {
        mutableStateOf(
            prefs.getInt("demosaic_algo", -1)
                .let { if (it in 0..2) it else if (prefs.getBoolean("demosaic_quality", true)) 1 else 0 },
        )
    }
    SettingValueRow(
        title = "RAW 去马赛克",
        subtitle = "RCD 质量 = 方向自适应+色比修正（默认，快）；Amaze = AMaZE（Martinec 2010，CPU 实现，纹理/细节取向，单张约 +0.3~1s，且当前不接 RAW 域降噪 ⇒ 高 ISO 噪声更大）；快速 = bilinear。仅影响 M 档成片",
        valueText = when (demosaicMode) {
            0 -> "快速"
            2 -> "Amaze"
            else -> "RCD 质量"
        },
        onTap = {
            val v = when (demosaicMode) {
                1 -> 2
                2 -> 0
                else -> 1
            }
            demosaicMode = v
            prefs.edit().putInt("demosaic_algo", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.demosaic_quality",
                "{\"algo\":$v,\"dem\":\"" +
                    when (v) {
                        0 -> "bilinear"
                        2 -> "amaze"
                        else -> "rcd"
                    } + "\"}",
            )
        },
    )
    var mfnrMode by remember { mutableStateOf(prefs.getInt("mfnr", 1)) }
    SettingValueRow(
        title = "多帧融合",
        subtitle = "暗光连拍 4 帧对齐合成降噪（彩噪 √4 根治）；仅 M 档 RAW + RCD 质量档生效。「开」= 每次都连拍",
        valueText = when (mfnrMode) {
            0 -> "关"
            1 -> "自动"
            else -> "开"
        },
        onTap = {
            val v = (mfnrMode + 1) % 3
            mfnrMode = v
            prefs.edit().putInt("mfnr", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.mfnr",
                "{\"mode\":$v}",
            )
        },
    )
    // bracket（期 B，docs/engine-bracket-design.md）：1 短 3 长采集。默认关 ——
    // 第 1 步只验证采集链路（融合仍用长帧），第 4 步判据回归后才转默认开。
    var bracketOn by remember { mutableStateOf(prefs.getBoolean("bracket", true)) }
    SettingSwitchRow(
        title = "多曝光 bracket（1 短 3 长）",
        subtitle = "帧 0 用 1/4 快门（保高光）、其余 3 帧长曝光（保暗部）。当前阶段只采集与记录，融合仍用长帧 —— 用来真机验证短帧是否真的没烧高光。仅 M 档 RAW + 多帧融合生效",
        checked = bracketOn,
        onChecked = { v ->
            bracketOn = v
            prefs.edit().putBoolean("bracket", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.bracket", "{\"on\":$v}",
            )
        },
    )
    // RAW 降噪强度（C 专项 2026-09-29：滑杆 0–2、步长 0.01）——
    // 0 = 关、1 = 真实噪声 σ（标准做法）、2 = 2×σ。作用于 P0/P4 的逐像素
    // 值域 σ（噪声模型 σ²=A·x+B，逐通道）；写入时同步旧 keys 供回退读取。
    var dnStr by remember {
        mutableStateOf(
            CameraController.denoiseStrength(
                prefs.contains("noise_reduction_strength"),
                prefs.getFloat("noise_reduction_strength", 1f),
                prefs.getBoolean("noise_reduction", true),
                prefs.getInt("noise_reduction_level", 1),
            ),
        )
    }
    SliderRow(
        label = "RAW 降噪",
        desc = "RAW 域双边 + 色度平滑，σ 由噪声模型逐像素给出；0 = 关、1 = 真实 σ、2 = 2×σ。仅 RCD 质量档生效",
        initial = dnStr,
        range = 0f..2f,
        steps = 199,
        valueText = { "%.2f".format(it) },
    ) { v ->
        dnStr = v
        prefs.edit()
            .putFloat("noise_reduction_strength", v)
            .putBoolean("noise_reduction", v > 0f)
            .putInt(
                "noise_reduction_level",
                if (v <= 0f) 0 else if (v <= 1.5f) 1 else 2,
            )
            .apply()
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "app", "settings.noise_reduction",
            "{\"strength\":\"%.2f\"}".format(v),
        )
    }
    NoiseModelRow()

    var lscOn by remember { mutableStateOf(prefs.getBoolean("lsc", true)) }
    SettingSwitchRow(
        title = "镜头阴影校正",
        subtitle = "HAL 增益网格消暗角与角落色偏（RAW 链、CCM 前，17×13 手写双线性）；关 = 同机位 A/B 对照",
        checked = lscOn,
        onChecked = { v ->
            lscOn = v
            prefs.edit().putBoolean("lsc", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.lsc",
                "{\"on\":$v}",
            )
        },
    )
    var sharpenOn by remember { mutableStateOf(prefs.getBoolean("sharpen", true)) }
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
    // 逆光边缘紫边抑制（v0.10.134）：RawTherapee Defringe 同形态。2026-10-04
    // 样片定量：品红伪影 56~66% 贴着物体轮廓，P4/D-1 的保边判据恰好保护它。
    var defringeOn by remember { mutableStateOf(prefs.getBoolean("defringe", true)) }
    SettingSwitchRow(
        title = "边缘紫边抑制",
        subtitle = "逆光轮廓品红渐晕（Lab 域 defringe，红物体与亮度不受损）；关 = 同场景 A/B 对照",
        checked = defringeOn,
        onChecked = { v ->
            defringeOn = v
            prefs.edit().putBoolean("defringe", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.defringe",
                "{\"on\":$v}",
            )
        },
    )
    // 镜头横向色差校正（v0.10.170）：D3（去马赛克后 / CCM 前）相机 RGB 域的
    // 自动估计（1/8 降采样 + 径向 LK）+ 径向位移校正（只动 R/B，G 不变）。
    // 见 CameraController.applyCaCorrPref 与 docs/reports/cacorr-REPORT.md。
    var caCorrOn by remember { mutableStateOf(prefs.getBoolean("ca_corr", true)) }
    SettingSwitchRow(
        title = "镜头色差校正",
        subtitle = "横向色差（离轴紫/青边）自动估计 + 径向位移校正；无位移时不动作；关 = A/B 对照",
        checked = caCorrOn,
        onChecked = { v ->
            caCorrOn = v
            prefs.edit().putBoolean("ca_corr", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.ca_corr",
                "{\"on\":$v}",
            )
        },
    )
    // 强边缘色度门 κ（v0.10.174 开关 → v0.10.177 可调滑杆）：治"暗室逆光拍窗外"
    // 这类强亮度对比边缘上的紫/绿镶边。κ 直写 FilmParams[51]，0 = 关 = A/B 对照
    // （P4/D-1 的门整段不执行，输出逐位回 v0.10.172），2 = PC 标定默认。κ 最优值
    // 未定（评估方向可能调低，代价 = 高饱和彩边去饱和 25~37%），真机拖动观察定值。
    // v0.10.173 的旧形态在真机上"一片红一片黄一片紫"（绝对 cap 0.2 全图生效 +
    // D-1 五级 100% 覆盖），v2 改为逐像素门（∇≤10 必然为 0）+ 相对上限 16·σc +
    // D-1 只在最细两级。若仍见色块，拖回 0（关）即逐位回退。
    // prefs bool→float 迁移见 CameraController.chromaGatePrefValue。
    // 见 CameraController.applyChromaGatePref 与 docs/reports/chromagate2-REPORT.md。
    var chromaGateK by remember {
        mutableStateOf(CameraController.chromaGatePrefValue(prefs.all["chroma_gate"]))
    }
    SliderRow(
        label = "强边缘色度门",
        desc = "只在强亮度对比边缘（∇L 门控）的色度路上抑制紫/绿镶边，平坦区逐位不动。κ 越大强边缘处色度平滑越强；代价 = 高饱和彩边去饱和（κ=2 时 25~37%）。0 = 关 = 同场景 A/B 对照（逐位回退）",
        initial = chromaGateK,
        range = 0f..2f,
        steps = 19, // 0..2、步长 0.1（与 RAW 降噪同用法：steps = 中间刻度数）
        valueText = { if (it <= 0.05f) "关" else "%.1f".format(it) },
    ) { raw ->
        val v = (raw * 10).roundToInt() / 10f  // 对齐 0.1 步进（滑杆量化有浮点误差）
        chromaGateK = v
        prefs.edit().putFloat("chroma_gate", v).apply()
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "app", "settings.chroma_gate",
            "{\"k\":\"%.1f\"}".format(v),
        )
    }
    // PC 降噪结构（ESD3D2）v0.10.176：引擎 v0.10.175 已把 [54]/[55] 完整移植，
    // 但**此前没有任何设置页入口 ⇒ 用户无法打开它**（用户反馈"[54] 设为 2 在哪"）。
    // 本版只补 UI + 下发通路（CameraController.applyPc3dPref），不动引擎。
    // v0.10.177：默认从"关"改为"叠加档"（与 CameraPresets.neutral() 同步）——
    // 2026-10-05 新 KPI 重估（docs/reports/pc3d-reval-REPORT.md）：叠加档 core ×0.676
    // 四片全改善、色渗全绿 ⇒ 默认开，**待真机 pc3dMs/画质验证后定稿**；旧"默认关"
    // 依据（∇>60 旧口径）已被推翻。0 = 关 ⇒ FilmParams[54]=0 ⇒ 与 v0.10.174
    // 逐位相同；1 = 替换档（旁路 P4/D-1）；2 = 叠加档（叠在现有色度降噪之上）。
    // 核尺寸 `pc3d_msize`。
    // 初始态 fallback = 2（叠加档）：与 CameraPresets.neutral() 的 [54]、
    // CameraController.applyPc3dPref 的 getInt fallback 三方一致 —— 无记录的
    // 升级用户显示与实际一致（叠加档），不会出现"显示关、实际开"的状态误导。
    var pc3dMode by remember { mutableStateOf(prefs.getInt("pc3d", 2).coerceIn(0, 2)) }
    SettingValueRow(
        title = "色度降噪·多尺度",
        subtitle = "两趟降采样结构（核 11、尺度 2）作用于色度降噪：低分辨率趟先做亮度/色度分离降噪，再引导上采样回全分辨率做第二趟。离线重估（2026-10-05 新 KPI）：紫边主指标（剖面 core）四片全改善（×0.676）、色渗判据全绿，待真机验证。代价：采样 2.3×、额外显存约 240MB。叠加档 = 在现有色度降噪之上叠加；替换档 = 旁路现有色度降噪（新 KPI 下色渗指标在警戒侧，留作真机 A/B）。默认叠加档，待真机 pc3dMs/画质验证后定稿",
        valueText = when (pc3dMode) {
            1 -> "替换档"
            2 -> "叠加档"
            else -> "关"
        },
        onTap = {
            val v = (pc3dMode + 1) % 3
            pc3dMode = v
            prefs.edit().putInt("pc3d", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.pc3d",
                "{\"pc3d\":$v}",
            )
        },
    )
    var pc3dMsize by remember { mutableStateOf(prefs.getInt("pc3d_msize", 0)) }
    SettingValueRow(
        title = "色度降噪核尺寸",
        subtitle = "仅多尺度降噪非「关」时生效。自动 = 按画面尺寸自适应（本机 ⇒ 11）；核越大强边缘紫边越少，但采样与显存按核面积涨（21 约 3.6×）",
        valueText = if (pc3dMsize == 0) "自动" else pc3dMsize.toString(),
        onTap = {
            // 与 CameraController.applyPc3dPref 的允许集合一致（0 = 自动 / 奇数 7..21）
            val msizes = intArrayOf(0, 7, 11, 15, 21)
            val v = msizes[(msizes.indexOf(pc3dMsize).coerceAtLeast(0) + 1) % msizes.size]
            pc3dMsize = v
            prefs.edit().putInt("pc3d_msize", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.pc3d_msize",
                "{\"msize\":$v}",
            )
        },
    )
}

/** 抽屉内容：影调与色彩（基准影调 / 曝光基准 / 饱和 / 黑白点 / 局部色调 / 色彩链 / 色域 / 高光重建） */
@Composable
private fun ToneColorDrawerContent() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }

    /**
     * ★ 阶段 3（引擎插槽）：**这条预设的引擎** `[98]` —— 本抽屉里 filmic 专属项显隐判据的来源之一。
     *
     * 读法与 `CameraScreen` 的冷启动恢复**逐字同一套**（prefs 的 `camera_preset_id` + 同一个
     * "唯一列表源" [BuiltinPresets.all] + [resolveCameraPreset]）⇒ 不新引入"当前引擎"的第二份
     * 持久化（第二份 = 必然漂移的真相），也不改 `CameraController`（引擎槽位的运行期真源在那
     * 边，属另一张卡的"参数旁路"范围）。
     *
     * `slots[98]` 缺失 ⇒ [CameraPresets.DEFAULT_ENGINE_SLOT]（P2 卡起 = 1 = sigmoid）——
     * 与 `cameraPresetParams` 展开时的基线取值（`CameraPresets.standard()`）**同一条口径**：
     * 缺失 = 默认档。⛔ 不许退回硬编码 0：内置预设（`builtin.json` 不含该键）在本抽屉里会显示
     * "引擎 0"，而实际渲染走引擎 1 —— 正是卡 C 2026-10-14 报过的"UI 按 A 引擎、渲染按 B 引擎"同型假读数。
     */
    val presetEngineSlot = remember {
        resolveCameraPreset(
            prefs.getString("camera_preset_id", null),
            BuiltinPresets.all(context, prefs),
        ).slots[ENGINE_SLOT]?.toInt() ?: CameraPresets.DEFAULT_ENGINE_SLOT
    }
    // ★ 阶段 3b（用户 2026-10-14 拍板）：**生效引擎** = 上面的预设 `[98]` ⊕ 下面那个三档开关。
    // 合成规则**不在这里**（唯一真源 = `EngineScope.effectiveEngineSlot`，含"未注册档位按跟随
    // 预设处理"）—— 本抽屉与另外两处 gate 走**同一个** [effectiveEngineSlotOf]。
    // `engineOverride`（state）驱动本行读数与重组，判据仍从 prefs 读：onTap 里先写 prefs 再更新
    // state ⇒ 重组时读到的必然是新档，两者不会不同步。
    var engineOverride by remember { mutableStateOf(engineOverrideOf(prefs)) }
    val engineSlot = effectiveEngineSlotOf(prefs, presetEngineSlot)

    // ── ★ 阶段 3b：全局引擎开关（三档：跟随预设 / 强制引擎 N）────────────────────
    // 用户 2026-10-14 拍板：设置页给一个"这台相机现在用哪个引擎"的全局开关；
    // 「完整参数」页的引擎选择面**保留**（它写的是**这条预设的** `[98]`，是配方的一部分，
    // 强制档下仍可编辑 —— 那边另有一行提示说明"当前被设置页覆盖"）。
    // 档名沿用既有命名：段落名「引擎插槽」、选项读数「引擎 N」（[engineOverrideLabel]）。
    // 交互沿用同抽屉里「色度降噪·多尺度」的三档循环（点一次进一档，不新造控件）。
    SettingValueRow(
        title = "引擎插槽",
        subtitle = "跟随预设 = 每条预设用自己存的引擎（在「完整参数」页改）；强制 = 全局覆盖，" +
            "只作用于拍照链路（编辑器仍按照片拍摄时的引擎）。filmic（胶片曲线）与 sigmoid" +
            "（暗table 对数逻辑）是两条不同的影调曲线",
        valueText = engineOverrideLabel(engineOverride),
        onTap = {
            val v = nextEngineOverride(engineOverride)
            engineOverride = v
            prefs.edit().putInt(ENGINE_OVERRIDE_PREFS_KEY, v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.engine_override",
                "{\"engine_override\":$v}",
            )
        },
    )

    // ★ 阶段 3：下面这些旋钮分别写进 `[28]` / `[20][21]` / `[37]` / `[38]` / `[36]`，而
    // `engine_slot != 0` 时这些槽在参数组装处被置**恒等**（`EngineScope.NEUTRALIZE`）⇒
    // 留在界面上就是"拖了画面不动"的假控件（ADR-12）⇒ 整块**不进 UI 树**（不是置灰）。
    // 判据 = 单一真源 `EngineScope`（本文件不另抄一份"哪些参数属于 filmic"的清单）。
    if (slotActiveInEngine(28, engineSlot)) {
        var toneOn by remember { mutableStateOf(prefs.getBoolean("base_tone", true)) }
        SettingSwitchRow(
            title = "基准影调",
            subtitle = "黑位 + 中间调 S + 高光 rolloff（治发灰）；关 = 旧行为（恒等，无影调工程）。预览与成片同步",
            checked = toneOn,
            onChecked = { on ->
                toneOn = on
                prefs.edit().putBoolean("base_tone", on).apply()
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "app", "settings.base_tone",
                    "{\"on\":$on}",
                )
            },
        )
    }
    // ── 基准曝光（v0.8.1）：机型 AE 基准差的校准常量 ─────────────────────────
    // 与 Lightroom/DNG 的 BaselineExposure 同语义。X300 对同场景参照渲染
    // 净差 +0.57 EV（v0.10.25 同 DNG 双渲染实测标定），默认 +0.7。
    // 用户 EV 滑杆叠加在其上（[0] = base + user），对齐参照后滑杆可归零。
    // ★ 阶段 3：本项**不按引擎显隐** —— `[0]` 是公共段参数（两个引擎都吃，架构师定案 §8.1
    // 的"保留（通用）"一条）。
    SliderRow(
        label = "基准曝光",
        desc = "校正机型自动曝光的基准差：成片整体偏暗拖右、偏亮拖左。对齐同场景参照后，EV 滑杆归零即可",
        initial = prefs.getFloat("base_ev", 0.7f),
        range = -2f..2f,
        steps = 0,
        valueText = { "%+.2f EV".format(it) },
    ) { v ->
        prefs.edit().putFloat("base_ev", v).apply()
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "app", "settings.base_ev", "{\"v\":$v}",
        )
    }
    // ★ 阶段 3：以下四项都是"引擎 0（filmic）语义"的旋钮 ⇒ 同一道 gate。
    // 饱和补偿写 `[20]/[21]`（判据取 `[20]`：两槽同命运，见 `EngineScope.NEUTRALIZE`）。
    if (slotActiveInEngine(20, engineSlot)) {
        // ── 饱和补偿（v0.10.21 标定旋钮）──────────────────────────────
        // 原生 gamut 色度 min 的低饱和副作用由风格层（uSat 分区饱和）补偿：
        // darktable 的 filmic → color balance、PC 的 filmic → LUT 是同一分层。
        // 只作用阴影/中间调，高光不动（防过饱和）。定值后固化进默认。
        SliderRow(
            label = "饱和补偿",
            desc = "标定旋钮：补偿原生色度收缩（暗部/中间调），高光不动。对照同场景样张调到灰感消失，定值后回传",
            initial = prefs.getFloat("sat_comp", 0f),
            range = 0f..0.30f,
            steps = 0,
            valueText = { "+%d%%".format((it * 100).toInt()) },
        ) { v ->
            val snap = (v * 100).toInt() / 100f
            prefs.edit().putFloat("sat_comp", snap).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.sat_comp", "{\"v\":$snap}",
            )
        }
    }
    if (slotActiveInEngine(37, engineSlot)) {
        // ── filmic 黑白点（v0.7.6）：像 PhotonCamera 一样交给用户配置 ─────────────
        // 单位 = 相对中灰 18.45% 的 EV —— 物理量，换机型/换曲线都成立，所以它是
        // "配置项"而不是需要标定的数值。默认 −8.0/+4.0 = darktable filmic 出厂默认
        // （= v0.10.113 起标准模式的固定值；v0.10.118 起滑杆恢复生效，默认值不变）。
        SliderRow(
            label = "黑点（多暗算黑）",
            desc = "比它更暗的落到黑；拖左 = 暗部更黑、层次更少",
            initial = prefs.getFloat("filmic_black_ev", -8.0f),
            range = -12f..-1f,
            steps = 0,
            valueText = { "%+.2f EV".format(it) },
        ) { v ->
            prefs.edit().putFloat("filmic_black_ev", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.filmic_black_ev", "{\"v\":$v}",
            )
        }
    }
    if (slotActiveInEngine(38, engineSlot)) {
        SliderRow(
            label = "白点（多亮算白）",
            desc = "拖右 = 白点更远 ⇒ 影调曲线更平、噪声更不明显（真机 6.2 时红釉白点消失），代价是对比降低、画面更柔",
            initial = prefs.getFloat("filmic_white_ev", 4.0f),
            range = 1f..8f,
            steps = 0,
            valueText = { "%+.2f EV".format(it) },
        ) { v ->
            prefs.edit().putFloat("filmic_white_ev", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.filmic_white_ev", "{\"v\":$v}",
            )
        }
    }
    if (slotActiveInEngine(36, engineSlot)) {
        // v0.12.28：默认关（与 PC 的 ShadowsHighlightsParams(0,0) 对齐；它是暗部过亮的元凶）
        var localToneOn by remember { mutableStateOf(prefs.getBoolean("local_tone", false)) }
        SettingSwitchRow(
            title = "局部色调映射",
            subtitle = "只提亮暗部：按画面局部亮度补光，亮区/高光强度归零。治「边缘有高光时中央被压暗」—— 全局曲线在逆光下两难",
            checked = localToneOn,
            onChecked = { on ->
                localToneOn = on
                prefs.edit().putBoolean("local_tone", on).apply()
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "app", "settings.local_tone",
                    "{\"on\":$on}",
                )
            },
        )
    }
    // v0.7.9：「色彩外观层」设置项已删除 —— 该层（M3 P1 治"寡淡"）在 filmic 链下
    // 恒等（v0.7.7 起 look_sat/look_chroma = 1.0，`1+(c-1)*sk` ≡ 1），开关只控制
    // 一个空操作，还占设置页心智。FilmParams[31..33] 槽位保留（契约不移位）。
    // M3 P2 DNG 标准色彩链（§3.2-7 / §3.4-4）：用 characteristics 的标定矩阵
    // （ColorMatrix/ForwardMatrix 双光源 + Bradford D50→sRGB）自建 camera→sRGB，
    // 替代 HAL 的 COLOR_CORRECTION_TRANSFORM；关 = 用 HAL 的做同机位 A/B。
    var stdCcmOn by remember { mutableStateOf(prefs.getBoolean("ccm_std", true)) }
    SettingSwitchRow(
        title = "DNG 标准色彩链",
        subtitle = "用相机标定矩阵自建色彩链（与 Adobe/libraw 完整链同源，标定数据缺失自动回退）；关 = 用 HAL 矩阵做 A/B",
        checked = stdCcmOn,
        onChecked = { v ->
            stdCcmOn = v
            prefs.edit().putBoolean("ccm_std", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.ccm_std",
                "{\"on\":$v}",
            )
        },
    )
    // 期 B：`[40]` 是**引擎 0 私有**（`uGamut` 只在 filmic 段被消费，引擎 1 的 program 里
    // 根本没有这个 uniform ⇒ location = −1 ⇒ 下发落空）。此前漏了 gate ⇒ 引擎 1 下"显示但
    // 完全无效"（用户实测："剩余开关全关了没影响"）。与 [37][38] 等私有项同一处理。
    if (slotActiveInEngine(40, engineSlot)) {
        var gamutOn by remember { mutableStateOf(prefs.getBoolean("gamut", true)) }
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
    }
    // 高光重建·小波（v0.10.15）：darktable filmic 原生高光重建（à trous 小波，
    // mask/inpaint/两轮重建全链），治部分通道裁剪高光的品红残留。关 = A/B。
    // v0.10.162：默认关（与 CameraController.applyHrWaveletPref 的 prefs 默认值
    // 必须一致，否则 UI 显示"开"而引擎实际"关"）。
    // v0.11.96：本项是 **filmic 原生**模块（白点判据吃 filmic 的 ev_white_），
    // 引擎 != 0 时参数被 EngineScope 归零 ⇒ 留在界面上就是"拖了没反应"的假控件。
    var hrWaveletOn by remember { mutableStateOf(prefs.getBoolean("hr_wavelet", false)) }
    if (slotActiveInEngine(41, engineSlot)) SettingSwitchRow(
        title = "高光重建·小波",
        subtitle = "darktable filmic 原生高光重建（à trous 小波，全分辨率），治过曝边缘品红残留；默认关（v0.10.162，与 RAW 域高光重建职责重叠）；开 = A/B 对照",
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
    // 色差域中值滤波（v0.10.196）：darktable demosaic 模块的 color_smoothing 移植
    // —— 在 (R−G)/(B−G) 色差域做 3×3 中值，G 不变。**唯一不判色相**的边缘伪彩解法：
    // 紫/蓝/青/绿任何方向的 1~3px 窄带色差异常都会被中值替换；中值对阶跃保边，
    // 真实色度边界不受影响。代价 = 1px 尺度的色度细节会被抹掉（darktable 因此
    // 默认关）。关 = 同场景 A/B 对照。
    var colorSmoothOn by remember { mutableStateOf(prefs.getBoolean("color_smooth", true)) }
    SettingSwitchRow(
        title = "色差中值滤波",
        subtitle = "darktable color_smoothing：色差域 3×3 中值，治**任意色相**的边缘窄带伪彩（紫/蓝/青/绿）；代价是 1px 色度细节被抹平",
        checked = colorSmoothOn,
        onChecked = { v ->
            colorSmoothOn = v
            prefs.edit().putBoolean("color_smooth", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.color_smooth",
                "{\"on\":$v}",
            )
        },
    )
    // RAW 域高光重建（v0.10.115）：darktable highlights 模块 "inpaint opposed"
    // —— 去马赛克**前**在 3×3 photosite 块上对被裁剪通道做对立外推 + 全图
    // 色度校正。补"小波"够不到的一环（film 域修不了 RAW 饱和丢掉的通道信息）。
    // v0.11.96（用户 2026-10-21）：bracket 默认开时，这两项**已经不参与成像** ——
    // 成片链在入口按"短帧有没有真丢信息"判据直接置 0（FrameRenderer::finishStillChain），
    // 高光由短帧的真实值接管。留在界面上只会让人以为"关掉它能改变画面"。
    // 判据与采集侧同一个 prefs 键（"bracket"，默认 true）。
    val bracketOn = remember { prefs.getBoolean("bracket", true) }
    var hrRawOn by remember { mutableStateOf(prefs.getBoolean("hr_raw", true)) }
    if (!bracketOn) SettingSwitchRow(
        title = "高光重建·RAW 域",
        subtitle = "darktable highlights（inpaint opposed）：去马赛克前恢复被裁剪通道，治高光边缘彩色伪影；关 = 同场景 A/B 对照",
        checked = hrRawOn,
        onChecked = { v ->
            hrRawOn = v
            prefs.edit().putBoolean("hr_raw", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.hr_raw",
                "{\"on\":$v}",
            )
        },
    )
    // 高光重建算法（v0.10.169 引入，v0.12.4 转正为**算法选择**）：RAW 域高光重建
    // apply 支走哪条路。默认 = darktable 原版（opposed.c 的 `MAX(inval, refavg+chroma)`，
    // **只抬不压** + 加性 chroma 补偿）；开 = 自创档（allSat 三通道替换，用满阱 luma
    // 当锚，0.12.3 及以前的行为）。
    // v0.12.4 起**不再随 bracket 隐藏**：这正是 bracket 场景下要 A/B 的开关（灰斑来自
    // 自创档压低 sat-fix 真值 + 去色，与 bracket 本身无关）。
    // 日志 raw.render.hlGRecon 显示 1（自创）/ 0（原版）。
    var hlGReconOn by remember { mutableStateOf(prefs.getBoolean("hl_grecon", true)) }
    SettingSwitchRow(
        title = "高光重建·自创 allSat 档",
        subtitle = "关（默认）= darktable 原版 opposed：只抬不压 + 加性色度补偿；开 = 自创档（按参考比替换三通道、用满阱亮度当锚），过曝高光会被压暗并去色。同场景 A/B 看高光带是否还有灰斑",
        checked = hlGReconOn,
        onChecked = { v ->
            hlGReconOn = v
            prefs.edit().putBoolean("hl_grecon", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.hl_grecon",
                "{\"on\":$v}",
            )
        },
    )
}

/** 抽屉内容：暗光与曝光（长曝光进光 / 上限 / 高光保护） */
@Composable
private fun LowLightDrawerContent() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }

    // 曝光决策专项（v0.10.107）：长曝光进光 —— 曝光 ×N + ISO ÷N（亮度与
    // 高光裁切不变，纯赚 SNR；离线实测收益 = √N）。上限由陀螺仪 motion
    // metering 的运动预算 + ISO 下限 + 快门范围共同决定。
    var expPlanOn by remember { mutableStateOf(prefs.getInt("exp_plan", 1)) }
    SettingSwitchRow(
        title = "长曝光进光",
        subtitle = "暗光按陀螺仪运动量拉长曝光（≤250ms）并等比降 ISO：亮度与高光裁切不变、噪声按 √N 下降（N=6.5 → 2.5×）；仅 M 档 RAW + 多帧融合触发时生效",
        checked = expPlanOn >= 1,
        onChecked = { on ->
            val v = if (on) 1 else 0
            expPlanOn = v
            prefs.edit().putInt("exp_plan", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.exp_plan",
                "{\"on\":$v}",
            )
        },
    )
    // 曝光上限档：自动（陀螺仪运动预算）/ 固定值（跳过运动限制，仍记录 blurPx）。
    // Filmix 同机用 ISO166+1/4s 拍出远超我们的暗部纹理，而"陀螺仪 × 时间"模型
    // 未计 OIS（实测实际像面位移仅机身转动的 0.71）⇒ 实证 > 模型，由用户试上限。
    var expMaxMs by remember { mutableStateOf(prefs.getInt("exp_max_ms", 0)) }
    val expCapOpts = listOf(0, 100, 250, 500)
    SettingValueRow(
        title = "长曝光上限",
        subtitle = "自动 = 按陀螺仪运动预算（纹理代价 <5%）；固定值 = 跳过运动限制，直接试 Filmix 同款 1/4s（日志 blurPx 仍记录预估帧内模糊）",
        valueText = if (expMaxMs == 0) "自动" else "${expMaxMs}ms",
        onTap = {
            val i = (expCapOpts.indexOf(expMaxMs).coerceAtLeast(0) + 1) % expCapOpts.size
            expMaxMs = expCapOpts[i]
            prefs.edit().putInt("exp_max_ms", expMaxMs).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.exp_max_ms",
                "{\"ms\":$expMaxMs}",
            )
        },
    )
    // 高光保护（v0.10.110）：曝光的第一目标是"**刚好不烧高光**"（HDR+ §4 原文
    // "set this exposure low enough to avoid blowing out highlights"），不是
    // "画面亮度达标"——亮度是后处理提亮的事，前提是 RAW 里还留着余量。
    var hlProtect by remember { mutableStateOf(prefs.getInt("hl_protect", 1)) }
    SettingSwitchRow(
        title = "高光保护",
        subtitle = "先看高光余量再决定曝光：场景高光（P99.9）加上将要施加的提亮会撞 RAW 白点时自动欠曝（只缩曝光时间、不抬 ISO），避免高光被压平成死白、LUT 在死白上染色；代价是暗部光子略少（由多帧补）",
        checked = hlProtect >= 1,
        onChecked = { on ->
            val v = if (on) 1 else 0
            hlProtect = v
            prefs.edit().putInt("hl_protect", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.hl_protect",
                "{\"on\":$v}",
            )
        },
    )
    // 自动曝光（v0.11.61）：本张 RAW 统计 ⇒ 渲染端 EV。
    // 用户 2026-10-20 定方案：高光不过曝、暗部有细节、亮度接近肉眼；这一层负责
    // "亮度接近现实"（中灰锚定 18.45%）与"高光不撞 RAW 白点"（高光优先）。
    var aeAuto by remember { mutableStateOf(prefs.getBoolean("ae_auto", true)) }
    SettingSwitchRow(
        title = "自动曝光（本张统计）",
        subtitle = "按这张照片自己的 RAW 统计决定成片曝光：中灰锚定 18.45%（亮度接近肉眼），" +
            "高光 P99.9 不撞 RAW 白点（高光优先）。与上面的「高光保护」是两层 —— " +
            "那层改采集（少收光子、且只能拿上一张预测），这层只改渲染（光子全保留、第一张也有效）。默认开",
        checked = aeAuto,
        onChecked = { on ->
            aeAuto = on
            prefs.edit().putBoolean("ae_auto", on).apply()
            EngineBridge.nativeSetAutoExposure(on)
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.ae_auto", "{\"on\":$on}",
            )
        },
    )
    // 局部影调（tone equalizer，2026-10-20）：按引导 mask 的亮度分区做曝光补偿
    // （darktable 口径）。解决"主体亮 + 天空蓝/高光有层次"在全局曲线下的互斥。
    var toneEq by remember { mutableStateOf(prefs.getBoolean("tone_eq", true)) }
    SettingSwitchRow(
        title = "局部影调（分区曝光）",
        subtitle = "按画面亮度分区做曝光补偿：高光区（天空/窗/灯）压暗约 0.6EV、暗部提亮约 0.3EV、" +
            "中灰锁定。用曝光无关的引导滤波做 mask（darktable tone equalizer 口径），" +
            "只改曝光不改局部对比。关 = 完全跳过（与之前逐位一致）。默认开",
        checked = toneEq,
        onChecked = { on ->
            toneEq = on
            prefs.edit().putBoolean("tone_eq", on).apply()
            EngineBridge.nativeSetToneEq(on)
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.tone_eq", "{\"on\":$on}",
            )
        },
    )
}

/** 抽屉内容：实验与诊断（A/B 与专项实验开关，日常拍摄不动） */
@Composable
private fun LabDrawerContent() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }

    // 10bit HEIF 交付链能力探针（实现卡 A0；方案 docs/rec2020-heif-design.md §4.1）：
    // 点一次 = 枚举 HEVC 编码器能力 → CPU 造已知测试图 → MediaCodec HEVC Main10 编 1 帧 →
    // MediaMuxer(MUXER_OUTPUT_HEIF) 落盘 → 回读解析 colr/nclx 与 hvcC 位深。
    // 全过程结论进 JSONL 日志（事件 heif10.probe / heif10.encoder / heif10.nclx），
    // .heic 落在应用外部私有目录 heif10/ 下（报告里给出取文件路径）。
    val heif10Scope = rememberCoroutineScope()
    var heif10Out by remember { mutableStateOf<String?>(null) }
    var heif10Running by remember { mutableStateOf(false) }
    SettingNavRow(
        if (heif10Running) "10bit HEIF 探针（运行中…）" else "10bit HEIF 能力探针",
        "查 HEVC Main10/P010 编码器 → 编 1 帧测试图写 HEIF → 回读 colr/nclx 与位深；结论看日志 heif10.*",
    ) {
        if (!heif10Running) {
            heif10Running = true
            heif10Scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching { Heif10BitProbe.run(context) }
                        .getOrElse { "探针异常：$it" }
                }
                heif10Out = r
                heif10Running = false
            }
        }
    }
    heif10Out?.let {
        Text(
            "10bit HEIF 探针输出：$it",
            color = FilmTheme.CreamDim,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        )
    }

    // 频域合并（降噪专项 A，2026-09-29 立项）：HDR+ §5 式 6/7 DFT 逐频
    // 收缩合并，替换 mismatch 加权融合。并联开关保留原路径（A/B 口径）。
    //
    // ── 2026-10-22 真机定案：**默认开**，细节明显更好 ─────────────────────────
    // 这条路径此前因 native shader 的一处 `const` 语法错误**从未真正执行过**
    // （每张都静默回退到空域 mismatch+fuse）；FatConst 修好后首次能跑。
    // 同机位实测（1777×2360）：
    //   · **细节**：频域明显更好（用户实拍对比，肉眼可辨）—— 逐频收缩对低频结构
    //     的保护优于块级加权平均，这是它的设计初衷。
    //   · **噪声**：白墙亮部 σ 5.99 vs 3.05（频域高近一倍），暗部相当。
    // 结论按"质量优先"取细节：**保留开启**。上一版本注释曾写"净损失、别开"，
    // 那个判断只量了噪声、没量细节，是片面的，此处更正。
    // 若某场景噪声难以接受，可临时关掉它做对照。
    var mfnrDft by remember { mutableStateOf(prefs.getBoolean("mfnr_dft", true)) }
    SettingSwitchRow(
        title = "频域合并（实验）",
        subtitle = "HDR+ 式 DFT 逐频收缩（16×16 tile 半重叠，暗光 σ 更优）；关 = mismatch 加权融合",
        checked = mfnrDft,
        onChecked = { v ->
            mfnrDft = v
            prefs.edit().putBoolean("mfnr_dft", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.mfnr_dft",
                "{\"on\":$v}",
            )
        },
    )
    // 对齐精度 A/B（D-2 专项，2026-09-30）：最细对齐层 1/2 域（新）vs
    // 1/4 域（旧）。同一场景切换各拍一张即可对比；日志 alignL1 = 3(新)/0(旧)。
    var alignL1 by remember { mutableStateOf(prefs.getBoolean("align_l1", true)) }
    SettingSwitchRow(
        title = "对齐精度（A/B 对比）",
        subtitle = "开 = 高精度对齐（新，中光更少错位，拍照慢约 40%）；关 = 旧版行为。日志 alignL1：3 = 新 / 0 = 旧",
        checked = alignL1,
        onChecked = { v ->
            alignL1 = v
            prefs.edit().putBoolean("align_l1", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.align_l1",
                "{\"on\":$v}",
            )
        },
    )
    // P0 亚像素采样（D-2 杠杆 B，v0.10.75）：时域样本不再 round 到偶数
    var p0Sub by remember { mutableStateOf(prefs.getBoolean("p0_sub", true)) }
    SettingSwitchRow(
        title = "P0 亚像素采样",
        subtitle = "开 = 时域样本按亚像素取（更准，GPU 采样成本 ×4）；关 = 旧版按 2 像素网格取整",
        checked = p0Sub,
        onChecked = { v ->
            p0Sub = v
            prefs.edit().putBoolean("p0_sub", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.p0_sub",
                "{\"on\":$v}",
            )
        },
    )
    // 同帧 A/B 诊断（v0.10.74）：同一帧数据再跑一遍旧层对齐 + P0，
    // 两组降噪倍数写进同一条 raw.render —— 唯一无混杂的 A/B 口径。
    var alignAb by remember { mutableStateOf(prefs.getBoolean("align_ab", false)) }
    SettingSwitchRow(
        title = "对齐 A/B 诊断（同帧对比）",
        subtitle = "开 = 每张照片额外算一遍旧版对齐，日志里同时给出两层的降噪倍数（abNzPostMed / abP0w）；拍照慢约 0.3 秒",
        checked = alignAb,
        onChecked = { v ->
            alignAb = v
            prefs.edit().putBoolean("align_ab", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.align_ab",
                "{\"on\":$v}",
            )
        },
    )
    // RCD 色差段回归原版（v0.10.117）：darktable/RawTherapee 的 RCD 在
    // 色差段靠**梯度反比加权**实现边缘感知；我们此前额外加的 clamp 会把
    // 跨边界邻居的色差锁进允许区间 ⇒ 红色扩散进白瓷/过曝区。
    var rcdCdFixOn by remember { mutableStateOf(prefs.getBoolean("rcd_cdfix", true)) }
    SettingSwitchRow(
        title = "去马赛克·色差原版",
        subtitle = "RCD 色差段回归 RawTherapee 原版（梯度三项 + 无限幅），治红白边界与过曝区的彩色块；关 = 复现旧行为 A/B",
        checked = rcdCdFixOn,
        onChecked = { v ->
            rcdCdFixOn = v
            prefs.edit().putBoolean("rcd_cdfix", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.rcd_cdfix",
                "{\"on\":$v}",
            )
        },
    )
    // RCD P2/G 位回归原版（v0.10.178）：与上面的色差段同型 —— P2 的 G 估计
    // 限幅到四邻实测 G 范围、G 位轴向填充混 ±3 远对并限幅，都是原版没有的
    // 结构；限幅范围取自跨边界邻居，候选解释亮暗交界暗侧品红窄峰。
    // 默认关 = 旧行为逐位不变；开 = A/B 通路（真机数据说话后再定默认）。
    var rcdGOrigOn by remember { mutableStateOf(prefs.getBoolean("rcd_g_orig", false)) }
    SettingSwitchRow(
        title = "去马赛克·G 段原版",
        subtitle = "RCD 绿色段与 G 位色度填充回归原版（无限幅、无 ±3 远对混合）；针对亮暗交界暗侧品红窄峰的 A/B；关 = 旧行为",
        checked = rcdGOrigOn,
        onChecked = { v ->
            rcdGOrigOn = v
            prefs.edit().putBoolean("rcd_g_orig", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.rcd_g_orig",
                "{\"on\":$v}",
            )
        },
    )
    // 高光重建·R/B 反推（v0.10.179）：亮暗交界 clip 过渡带的品红窄带 —— 过渡带
    // photosite 本通道（R/B）满阱、其余通道未满，allSat 与普通 G 反推都够不着，
    // 满阱比例（WB 增益伪影）经 CCM 后成 4~6px 品红窄带。开 = 按参考比反推替换
    // （替换语义）；关 = 旧行为逐位不变。默认关 = 旧行为；开 = A/B 通路
    //（真机数据说话后再定默认）。
    var hlRbReconOn by remember { mutableStateOf(prefs.getBoolean("hl_rb_recon", false)) }
    SettingSwitchRow(
        title = "高光重建·R/B 反推",
        subtitle = "亮暗交界品红窄带修复 A/B（clip 过渡带 R/B 满阱时按参考比反推替换）；开 = 启用，关 = 旧行为",
        checked = hlRbReconOn,
        onChecked = { v ->
            hlRbReconOn = v
            prefs.edit().putBoolean("hl_rb_recon", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.hl_rb_recon",
                "{\"on\":$v}",
            )
        },
    )
    // 去马赛克·G 位双 pass（v0.10.180）：与上面同族但根因不同 —— 单 pass 的
    // P3 里 G 位轴向填充并行读不到本 pass 内对角段刚填好的对侧色，轴向估计
    // 只剩同轴 ±1 实测信息，把原版就有的 B 缓 G 陡错位放大 2 倍（品红峰
    // +43.8 → +85.6 WB 域）。开 = 拆 3a/3b 双 pass 恢复原版四正邻双轴估计；
    // 关 = 旧行为逐位不变。默认关 = 旧行为；开 = A/B 通路（真机数据说话
    // 后再定默认）。
    var rcdP3SplitOn by remember { mutableStateOf(prefs.getBoolean("rcd_p3_split", false)) }
    SettingSwitchRow(
        title = "去马赛克·G 位双 pass",
        subtitle = "RCD 色度段拆双 pass 对齐原版四邻估计，针对亮暗交界品红窄带（拍照约慢 0.2 秒）；关 = 旧行为",
        checked = rcdP3SplitOn,
        onChecked = { v ->
            rcdP3SplitOn = v
            prefs.edit().putBoolean("rcd_p3_split", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.rcd_p3_split",
                "{\"on\":$v}",
            )
        },
    )
    // 拍照独立渲染线程（v0.10.85）：合成链 1.6~3.4s 原来跑在预览渲染线程上，
    // 预览的 updateTexImage 全排在它后面 → "拍完挪手机画面完全冻住 1~2 秒"。
    // 开 = 独立线程 + 独立 EGL context（PhotonCamera 同构）；默认关 = 旧行为。
    var stillThread by remember { mutableStateOf(prefs.getBoolean("still_thread", false)) }
    SettingSwitchRow(
        title = "拍照独立渲染线程",
        subtitle = "开 = 多帧合成走独立线程/上下文，拍完预览不再冻住 1~2 秒（首张多付一次引擎初始化）；关 = 旧行为（拍照期间预览停更）",
        checked = stillThread,
        onChecked = { v ->
            stillThread = v
            prefs.edit().putBoolean("still_thread", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.still_thread",
                "{\"on\":$v}",
            )
        },
    )
    // 同帧 A/B（v0.10.85 诊断）：同一帧数据先走拍照线程、再走预览线程，
    // 比较两遍输出的 CRC32 与采样均值 —— 验证"换路径不改画质"。
    var stillAb by remember { mutableStateOf(prefs.getBoolean("still_ab", false)) }
    SettingSwitchRow(
        title = "独立线程 A/B 诊断（同帧对比）",
        subtitle = "仅在「拍照独立渲染线程」打开时生效：每张照片额外在预览线程算一遍，日志 render.still_ab 给出两遍的 CRC32/均值；拍照慢约 1.6 秒",
        checked = stillAb,
        onChecked = { v ->
            stillAb = v
            prefs.edit().putBoolean("still_ab", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.still_ab",
                "{\"on\":$v}",
            )
        },
    )
}

/** 噪声模型行 + 选择/导入对话框（C 专项，2026-09-29；用户需求：PC 式可导入/可切换） */
@Composable
private fun NoiseModelRow() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }
    var nmSel by remember {
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
    SettingValueRow(
        title = "噪声模型",
        subtitle = "降噪与 MFNR 的噪声底来源：自动 = 相机 HAL 标定；可导入 GCam 标定文件（.c）按 ISO 生效",
        valueText = if (nmSel == NoiseCalibStore.AUTO) "自动" else nmSel.removeSuffix(".c"),
        onTap = {
            nmList = NoiseCalibStore.list(context)
            nmDialog = true
        },
    )
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
}

/**
 * 设置抽屉(自绘底部面板):透明拦截层(点画面任意处收起)+ PanelSolid 面板,
 * 出入场统一走 FilmMotion.panelEnter/panelExit;BackHandler 保证**一次返回直接关闭**
 * ——此前用 M3 ModalBottomSheet,真机返回要按两次才关(用户 2026-10-03 实测),已弃用。
 */
@Composable
private fun SettingsDrawer(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(visible = visible, enter = FilmMotion.panelEnter, exit = FilmMotion.panelExit) {
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
        Box(Modifier.fillMaxSize()) {
            // 拦截层先绘制:点画面任意处收起(浮层规则)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxH)
                    .background(FilmTheme.PanelSolid, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .verticalScroll(rememberScrollState())
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {} // 吃掉面板内点击,不透传到拦截层
                    .padding(horizontal = 20.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(FilmTheme.ChipBorder),
                    )
                }
                Text(
                    title,
                    color = FilmTheme.Cream,
                    fontSize = 17.sp,
                    fontFamily = FilmTheme.Serif,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                content()
                Spacer(Modifier.height(28.dp))
            }
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
 * 区块标题：强调色等宽小字（全 App 统一）。
 *
 * internal 而非 private：编辑器参数面板（E2-1）的分组标题复用同一形态 ——
 * 两处各写一份，字号/颜色迟早漂移。
 */
@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        color = FilmTheme.Accent,
        fontSize = 11.sp,
        fontFamily = FilmTheme.Mono,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

/** 开关行：标题 + 次级说明 + 强调色开关（切换带 CONTEXT_CLICK 触感） */
@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    /** `false` = 灰显不可点（卡 FIX2：HEIF 档下「4:4:4 色度」对它无效）。 */
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) FilmTheme.Cream else FilmTheme.CreamDim, fontSize = 14.sp)
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = if (enabled) FilmTheme.CreamDim else FilmTheme.ChipBorder,
                    fontSize = 10.sp,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                onChecked(it)
            },
            colors = if (enabled) {
                SwitchDefaults.colors(
                    checkedTrackColor = FilmTheme.Accent,
                    checkedThumbColor = FilmTheme.Cream,
                    uncheckedTrackColor = FilmTheme.ChipBg,
                    uncheckedThumbColor = FilmTheme.CreamDim,
                    uncheckedBorderColor = FilmTheme.ChipBorder,
                )
            } else {
                // 灰显：轨道/滑块统一降到 ChipBorder（不可点状态一眼可辨）
                SwitchDefaults.colors(
                    checkedTrackColor = FilmTheme.ChipBorder,
                    checkedThumbColor = FilmTheme.CreamDim,
                    uncheckedTrackColor = FilmTheme.ChipBg,
                    uncheckedThumbColor = FilmTheme.ChipBorder,
                    uncheckedBorderColor = FilmTheme.ChipBorder,
                )
            },
        )
    }
}

/** 数值行：标题 + 次级说明 + 强调色等宽值；点按切换/进入（带 CONTEXT_CLICK 触感） */
@Composable
private fun SettingValueRow(
    title: String,
    subtitle: String?,
    valueText: String,
    onTap: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                onTap()
            }
            .padding(vertical = 8.dp),
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
        Text(
            valueText,
            color = FilmTheme.Accent,
            fontSize = 13.sp,
            fontFamily = FilmTheme.Mono,
            fontWeight = FontWeight.SemiBold,
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

/**
 * 一行滑杆：拖动时只更新本地状态，**松手才提交**（避免每帧写 prefs）。
 * valueText 把当前值渲染成右侧等宽读数（EV / 百分比 / 强度等各域自带格式）。
 */
@Composable
private fun SliderRow(
    label: String,
    desc: String?,
    initial: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var v by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = FilmTheme.Cream, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(
                valueText(v),
                color = FilmTheme.Accent,
                fontSize = 13.sp,
                fontFamily = FilmTheme.Mono,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (desc != null) {
            Text(
                desc,
                color = FilmTheme.CreamDim,
                fontSize = 10.sp,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        androidx.compose.material3.Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onCommit(v) },
            valueRange = range,
            steps = steps,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = FilmTheme.Cream,
                activeTrackColor = FilmTheme.Accent,
                inactiveTrackColor = FilmTheme.ChipBorder,
            ),
        )
    }
}
