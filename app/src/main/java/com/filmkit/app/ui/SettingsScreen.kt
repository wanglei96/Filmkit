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
import com.filmkit.app.camera.CameraAudit
import com.filmkit.app.camera.CameraController
import com.filmkit.app.camera.NoiseCalibStore
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

    var rcdOn by remember { mutableStateOf(prefs.getBoolean("demosaic_quality", true)) }
    SettingValueRow(
        title = "RAW 去马赛克",
        subtitle = "RCD 质量 = 方向自适应+色比修正，边缘伪彩少；快速 = bilinear，最快。仅影响 M 档成片",
        valueText = if (rcdOn) "RCD 质量" else "快速",
        onTap = {
            val v = !rcdOn
            rcdOn = v
            prefs.edit().putBoolean("demosaic_quality", v).apply()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "settings.demosaic_quality",
                "{\"rcd\":$v}",
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
}

/** 抽屉内容：影调与色彩（基准影调 / 曝光基准 / 饱和 / 黑白点 / 局部色调 / 色彩链 / 色域 / 高光重建） */
@Composable
private fun ToneColorDrawerContent() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }

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
    // ── 基准曝光（v0.8.1）：机型 AE 基准差的校准常量 ─────────────────────────
    // 与 Lightroom/DNG 的 BaselineExposure 同语义。X300 对同场景参照渲染
    // 净差 +0.57 EV（v0.10.25 同 DNG 双渲染实测标定），默认 +0.7。
    // 用户 EV 滑杆叠加在其上（[0] = base + user），对齐参照后滑杆可归零。
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
    var localToneOn by remember { mutableStateOf(prefs.getBoolean("local_tone", true)) }
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
    // 高光重建·小波（v0.10.15）：darktable filmic 原生高光重建（à trous 小波，
    // mask/inpaint/两轮重建全链），治部分通道裁剪高光的品红残留。关 = A/B。
    var hrWaveletOn by remember { mutableStateOf(prefs.getBoolean("hr_wavelet", true)) }
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
    // RAW 域高光重建（v0.10.115）：darktable highlights 模块 "inpaint opposed"
    // —— 去马赛克**前**在 3×3 photosite 块上对被裁剪通道做对立外推 + 全图
    // 色度校正。补"小波"够不到的一环（film 域修不了 RAW 饱和丢掉的通道信息）。
    var hrRawOn by remember { mutableStateOf(prefs.getBoolean("hr_raw", true)) }
    SettingSwitchRow(
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
}

/** 抽屉内容：实验与诊断（A/B 与专项实验开关，日常拍摄不动） */
@Composable
private fun LabDrawerContent() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }

    // 频域合并（降噪专项 A，2026-09-29 立项）：HDR+ §5 式 6/7 DFT 逐频
    // 收缩合并，替换 mismatch 加权融合。并联开关保留原路径（A/B 口径）。
    var mfnrDft by remember { mutableStateOf(prefs.getBoolean("mfnr_dft", false)) }
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

/** 开关行：标题 + 次级说明 + 强调色开关（切换带 CONTEXT_CLICK 触感） */
@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
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
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                onChecked(it)
            },
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
