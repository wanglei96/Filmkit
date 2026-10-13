package com.filmkit.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.BlurCircular
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Brightness5
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Brightness7
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Details
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.ExposureNeg2
import androidx.compose.material.icons.outlined.ExposurePlus2
import androidx.compose.material.icons.outlined.Flare
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MonochromePhotos
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.TripOrigin
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmkit.app.camera.CLARITY_UI_RANGE
import com.filmkit.app.camera.CONTRAST_UI_RANGE
import com.filmkit.app.camera.EXP_UI_RANGE
import com.filmkit.app.camera.FADE_UI_RANGE
import com.filmkit.app.camera.FILMIC_BLACK_EV_PIVOT
import com.filmkit.app.camera.FILMIC_BLACK_EV_UI_RANGE
import com.filmkit.app.camera.FILMIC_WHITE_EV_PIVOT
import com.filmkit.app.camera.FILMIC_WHITE_EV_UI_RANGE
import com.filmkit.app.camera.GLOW_RADIUS_UI_PIVOT
import com.filmkit.app.camera.GLOW_THRESHOLD_UI_PIVOT
import com.filmkit.app.camera.GLOW_UI_PIVOT
import com.filmkit.app.camera.HALO_GLOW_UI_RANGE
import com.filmkit.app.camera.HALO_RADIUS_UI_PIVOT
import com.filmkit.app.camera.HALO_THRESHOLD_UI_PIVOT
import com.filmkit.app.camera.HALO_UI_PIVOT
import com.filmkit.app.camera.HALO_WARMTH_UI_PIVOT
import com.filmkit.app.camera.LATITUDE_UI_PIVOT
import com.filmkit.app.camera.LATITUDE_UI_RANGE
import com.filmkit.app.camera.LUT_STRENGTH_UI_RANGE
import com.filmkit.app.camera.MONO_UI_RANGE
import com.filmkit.app.camera.SAT_UI_RANGE
import com.filmkit.app.camera.SPLIT_UI_RANGE
import com.filmkit.app.camera.TEMP_UI_PIVOT
import com.filmkit.app.camera.TEMP_UI_RANGE
import com.filmkit.app.camera.TINT_UI_RANGE
import com.filmkit.app.camera.TONE_UI_RANGE
import com.filmkit.app.camera.WB_GAIN_UI_RANGE
import com.filmkit.app.camera.ZERO_UI_PIVOT
import com.filmkit.app.camera.claritySlot
import com.filmkit.app.camera.clarityUi
import com.filmkit.app.camera.contrastSlot
import com.filmkit.app.camera.contrastUi
import com.filmkit.app.camera.expSlot
import com.filmkit.app.camera.expUi
import com.filmkit.app.camera.fadeSlot
import com.filmkit.app.camera.fadeUi
import com.filmkit.app.camera.formatDecimals2
import com.filmkit.app.camera.formatEv
import com.filmkit.app.camera.formatEvSlot
import com.filmkit.app.camera.formatKelvin
import com.filmkit.app.camera.formatOff100
import com.filmkit.app.camera.formatOffset100
import com.filmkit.app.camera.formatZero100
import com.filmkit.app.camera.formatZero150
import com.filmkit.app.camera.formatZero200
import com.filmkit.app.camera.latitudeSlot
import com.filmkit.app.camera.latitudeUi
import com.filmkit.app.camera.monoSlot
import com.filmkit.app.camera.monoUi
import com.filmkit.app.camera.normalizedSlot
import com.filmkit.app.camera.normalizedUi
import com.filmkit.app.camera.pivotFraction
import com.filmkit.app.camera.satUi
import com.filmkit.app.camera.splitSlot
import com.filmkit.app.camera.splitUi
import com.filmkit.app.camera.tempSlot
import com.filmkit.app.camera.tempUi
import com.filmkit.app.camera.tintSlot
import com.filmkit.app.camera.tintUi
import com.filmkit.app.camera.toneSlot
import com.filmkit.app.camera.toneUi
import com.filmkit.app.camera.vigFeatherSlot
import com.filmkit.app.camera.vigFeatherUi
import com.filmkit.app.camera.vigRadiusSlot
import com.filmkit.app.camera.vigRadiusUi
import com.filmkit.app.camera.vigStrengthSlot
import com.filmkit.app.camera.vigStrengthUi
import com.filmkit.app.camera.wbGainSlot
import com.filmkit.app.camera.wbGainUi
import com.filmkit.app.camera.writeLatitude
import com.filmkit.app.camera.writeSaturation

/**
 * ★ PS-3：**参数控件与参数表的共享位置**（拍照界面预设详情 ⇄ 编辑器参数面板）。
 *
 * 本文件的内容是从 `EditorScreen.kt` **逐字搬移**过来的（2026-10-11，PS-3）：
 * [EditorParam] / [AdjustParams] / [ColorParams] / [LutStrengthParam] / [TextureParams] /
 * [EditorSlider] / [TextShadow]。
 *
 * ## 为什么搬（用户 2026-10-11："可直接在拍照界面查看、修改预设"）
 *
 * 拍照界面的预设详情要能**逐项拖滑杆**，而"一项参数的量程 / 恒等值 / 读数口径 / 槽位读写"
 * 必须与编辑器**同一套**（否则同一条预设在两页显示成两个值 —— ADR-12 的"假读数"）。
 * 照抄一份参数表 = 两份必然漂移的真相 ⇒ 提取成共享位置，编辑器与拍照界面引用**同一个对象**。
 *
 * ## 搬移纪律（本文件的存在理由，改之前先读）
 *
 *  - **纯搬移**：数值 / 顺序 / label / format / 注释**一行都没改**，只改了可见性
 *    （`private` → `internal`）与文件位置 ⇒ `EditorScreen` 的功能 / 读数 / 顺序逐位不变；
 *  - 编辑器侧的三组参数表单测（`EditorReadoutTest` / `EditorToneColorUiTest` /
 *    `EditorHaloGlowUiTest` / `EditorU6bParamUiTest` / `EditorVignetteUiTest`）**必须全绿** ——
 *    它们是"搬移零回归"的机器判据；
 *  - 参数表的**槽位语义 / 恒等值 / 换算函数**仍全部在 `EditorExport.kt`（本文件只引用）。
 *
 * ## 拍照界面侧的额外一层（本文件末尾）
 *
 * 拍照链路读的槽位**不是**全部槽位（引擎实测白名单 [CAMERA_PATH_SLOTS]，42 槽）⇒
 * 拍照界面只给白名单内、且有滑杆的槽展开滑杆；其余**灰显并标注原因**（ADR-12：不做假滑杆）。
 * 槽位 → 参数的索引是 [CAMERA_TUNABLE_SLOT_PARAM]（只有"哪个槽归哪条滑杆"，不含第二份数值）。
 */

/**
 * 面板文字阴影（ADR-14 从 PC 源码抄的 `BoldTextWithShadow`：dx / dy / radius = 1px）。
 * 参数名 / 读数 / 提示都可能叠在预览图上，1px 黑阴影是"叠加时的可读性技巧"。
 */
internal val TextShadow = Shadow(Color.Black, Offset(1f, 1f), 1f)

/**
 * 一个可调参数（E2-10 的数据驱动核心）：图标行的一项 + 唯一那条滑杆 + 「重置」的目标。
 *
 * 为什么做成表：E2-3 / E2-7 的写法是"每个参数一个 setter + 面板签名里一个 lambda + 调用点
 * 一处绑定"（19 个参数 × 3 处）—— 新增参数漏改任何一处，表现都是"滑杆动了、画面不动"
 *（ADR-12 的假滑杆）。表里每项自带槽位读写（[read] / [write] 都是 EditorExport.kt 的纯函数），
 * 加一个参数只加一行。
 */
internal class EditorParam(
    /** 稳定 key：滑杆 state 的 `key(...)`（换参数必须重建，否则新参数沿用上一条的读数） */
    val key: String,
    val label: String,
    /** 圆形按钮里的图标。与 [letter] 二选一 */
    val icon: ImageVector? = null,
    /** 字母图标（白平衡 R/G/B）：不为三个通道硬凑三个语义不符的图标，也不新造颜色 */
    val letter: String? = null,
    val range: ClosedFloatingPointRange<Float>,
    /** 基准值（UI 单位）= **不变点 / 默认值**：中点标记画在这里，「重置」回到这里 */
    val pivot: Float,
    val format: (Float) -> String,
    val read: (DoubleArray) -> Float,
    val write: (DoubleArray, Float) -> Unit,
)

/**
 * 「调整」组（E2-3 的「基础」组改名；E2-14 加影调分区四条；E2-15 纳色温/色调；
 * **U-6b 纳胶片曲线两条**；**PS-10 纳 filmic 黑白点两条**）：
 * 曝光 [0] / 色温 [88] / 色调 [89] / 对比度 [4] / **胶片曲线·肩部 [5..7] / 胶片曲线·趾部 [8..10]** /
 * **黑点 [37] / 白点 [38]** /
 * 高光 [90] / 阴影 [91] / 白色色阶 [92] / 黑色色阶 [93] / 饱和度 [20..22] / 清晰度 [78] /
 * 红 [1] / 绿 [2] / 蓝 [3]。槽位语义与恒等值见 `EditorExport.kt` 的映射段。
 *
 * **排序理由（E2-15 立规，U-6b 沿用；用户要求"按逻辑排但要说明理由"）**——按用户调色的
 * **实际决策顺序**：
 *  1. **曝光**：先定整体明暗 —— 后面所有参数（尤其影调分区与饱和度）的观感都建立在它之上；
 *  2. **色温 → 色调**：白平衡两条紧跟曝光。它们决定"这张照片的色偏"，而色偏会污染后面
 *     每一个关于颜色的判断（饱和度、清晰度看起来"发灰/发黄"常常只是白平衡没对）；
 *     两条**必须相邻**（同一套白平衡的两轴，分开找不着）；
 *  3. **对比度 → 胶片曲线·肩部 → 胶片曲线·趾部 → 黑点 → 白点**：都是"曲线形状"。**U-6b 把这两条
 *     紧跟对比度**的理由是它们与对比度**同族**：对比度是"整体斜率"（一条直线），胶片曲线是"两端过渡区的
 *     位置"（肩部 = 亮端、趾部 = 暗端）—— 用户发现"对比度不够精细"时，下一步就是来这里调端点，
 *     中间隔着四条影调分区会把同一条曲线的两端拆散；
 *     **PS-10 把 filmic 黑白点接在趾部之后**：它们也是"曲线端点"，只是量的是**端点的位置**而不是
 *     过渡区的宽度（肩部/趾部 = 形，黑白点 = 量）—— 用户在"曲线还不够用"时的下一步就是挪端点。
 *     四条挨着放 ⇒ "曲线族"在参数表里是一整段，中间不插别的类别；
 *  4. **高光 → 阴影 → 白色色阶 → 黑色色阶**：四个影调分区按**从亮部到暗部**（Lightroom
 *     Basic 面板的既有顺序，用户肌肉记忆最省）；都在对比度/曲线之后，因为它们是"对比度不够
 *     精细"时的**分区微调**（曲线改的是过渡区形状，分区改的是区段亮度，前者更"形"、后者更"量"）；
 *  5. **饱和度**：全局色彩强度 —— 影调定完再谈色彩浓度（影调变了，饱和的观感也变）；
 *  6. **清晰度**：局部质感（中频），放在全局色彩之后 —— 它是"最后一道质感调味"；
 *  7. **红 / 绿 / 蓝**：手动通道增益放最后。它们是白平衡的"手动挡"，与色温/色调功能重叠，
 *     属于进阶微调 —— 放前面会诱导用户用三条增益去调色偏（比色温/色调难用得多）。
 *
 * 读数口径（E2-10 全卡统一，格式化函数都在 `EditorExport.kt`）：
 *  - **曝光** `+1.0 EV`（物理量，不再是 `100%`）；中点 = `0.0 EV`
 *  - **色温** `5000K`（**物理量 K，不是 mired**）；中点 = `6500K`
 *  - **色调** `+12`（±100，正 = 品红）；中点 = `0`
 *  - **红/绿/蓝** `+12`（相对增益 ±100）；中点 = `0`（= 增益 1.00 不变）
 *  - **对比度 / 饱和度** `+12`（±100）；中点 = `0`（= 1.00 不变）
 *  - **清晰度** `35`（0..100 整数，0 = 关）；基准在最左端 ⇒ 不画中点标记（端点本身就是标记）
 *  - **胶片曲线·肩部 / 趾部** `0.30`（**两位小数**，latitude 0..1）；中点 = **`0.01`**（不是 0！）
 *    —— 0 在 latitude 语义里是"过渡区紧贴中灰"的**极端 S 曲线**（详见 `latitudeSlot` 的 KDoc）
 *  - **黑点 / 白点** `-7.6 EV` / `+4.6 EV`（**一位小数 + EV**，槽位**就是** EV ⇒ 无换算；
 *    与 `PresetDetail.kt` 的 `evText` 同一个实现 [formatEvSlot]）；中点 = 引擎默认 `-7.65` / `+4.56`
 *    —— 量程与"编辑器链路白点有 2.44 EV 物理上限"这两条依据见 `FILMIC_BLACK_EV_UI_RANGE` 的 KDoc
 *  - **高光 / 阴影 / 白色色阶 / 黑色色阶** `+50`（±100，正 = 该分区提亮）；中点 = `0`
 *
 * ⚠️ 色温滑杆的值**直接就是 K**（右移 = K 大 = 画面变暖）—— 槽位 [88] 存的是 mired 偏移，
 * 方向相反，换算在 `EditorExport.kt` 的 `tempSlot` / `tempUi` 里（见那里的方向约定）。
 */
internal val AdjustParams: List<EditorParam> = listOf(
    EditorParam(
        key = "ev", label = "曝光", icon = Icons.Outlined.Exposure,
        range = EXP_UI_RANGE, pivot = 0f,
        format = { formatEv(it) },
        read = { expUi(it[0]) },
        write = { p, v -> p[0] = expSlot(v) },
    ),
    // ── E2-15：色温 / 色调从「滤镜」组**移进来**（用户反馈："色温方向正确，放的位置不对，
    // 怎么放 lut 里了应该是调整里面"）。它们是白平衡的两条，与 [1..3] 通道增益同属基础调整；
    // 两条**必须在一起**（分开会让用户找不到另一半）。
    EditorParam(
        key = "temp", label = "色温", icon = Icons.Outlined.Thermostat,
        range = TEMP_UI_RANGE, pivot = TEMP_UI_PIVOT,
        format = { formatKelvin(it) },
        read = { tempUi(it[88]) },
        write = { p, v -> p[88] = tempSlot(v) },
    ),
    EditorParam(
        key = "tint", label = "色调", icon = Icons.Outlined.InvertColors,
        range = TINT_UI_RANGE, pivot = ZERO_UI_PIVOT,
        format = { formatOffset100(it, ZERO_UI_PIVOT) },
        read = { tintUi(it[89]) },
        write = { p, v -> p[89] = tintSlot(v) },
    ),
    EditorParam(
        key = "contrast", label = "对比度", icon = Icons.Outlined.Contrast,
        range = CONTRAST_UI_RANGE, pivot = 1f,
        format = { formatOffset100(it, 1f) },
        read = { contrastUi(it[4]) },
        write = { p, v -> p[4] = contrastSlot(v) },
    ),
    // ── U-6b：胶片曲线两条（肩部 [5..7] / 趾部 [8..10]，引擎 `kFsFilm` 的 latitude 段）─────
    // 紧跟对比度、在影调分区**之前**（同属"曲线形状"，顺序理由见 [AdjustParams] 的 KDoc 第 3 条）。
    // 量程 0..1 直通槽位；**一条滑杆同写 R/G/B 三槽**（同饱和度那种"一条滑杆多槽"，见 writeLatitude）。
    // ⚠⚠ 恒等值/中点 = 0.01 而**不是 0**：0 = 过渡区紧贴中灰 = 极端 S 曲线（`latitudeSlot` 的 KDoc）。
    // 滑杆允许拖到 0（用户主动要极端曲线），但 reset 必须回 0.01 —— 单测钉死。
    EditorParam(
        key = "curveShoulder", label = "胶片曲线 · 肩部", icon = Icons.AutoMirrored.Outlined.TrendingUp,
        range = LATITUDE_UI_RANGE, pivot = LATITUDE_UI_PIVOT,
        format = { formatDecimals2(it) },
        read = { latitudeUi(it[5]) },
        write = { p, v -> writeLatitude(p, 5, v) },
    ),
    EditorParam(
        key = "curveToe", label = "胶片曲线 · 趾部", icon = Icons.AutoMirrored.Outlined.TrendingDown,
        range = LATITUDE_UI_RANGE, pivot = LATITUDE_UI_PIVOT,
        format = { formatDecimals2(it) },
        read = { latitudeUi(it[8]) },
        write = { p, v -> writeLatitude(p, 8, v) },
    ),
    // ── PS-10：filmic **黑白点**（[37] / [38]，引擎 `computeFilmicSpline` 的曲线两端）──────────
    // 接在胶片曲线两条之后（同属"曲线端点"：肩/趾 = 过渡区宽度，黑白点 = 端点位置），
    // 理由见 [AdjustParams] 的 KDoc 第 3 条。量程 / 基准 / 读数口径（含编辑器链路白点
    // 2.44 EV 物理上限的如实标注）见 `EditorExport.kt` 的 `FILMIC_BLACK_EV_UI_RANGE` 段。
    // 两个槽**两端都真生效**（拍照链路 `updateFilmicEV` + 编辑器链路 `computeEditorCurve`
    // 都读 `p.filmic_black_ev` / `p.filmic_white_ev`）⇒ 不是假滑杆。
    EditorParam(
        key = "filmicBlack", label = "黑点", icon = Icons.Outlined.ExposureNeg2,
        range = FILMIC_BLACK_EV_UI_RANGE, pivot = FILMIC_BLACK_EV_PIVOT,
        format = { formatEvSlot(it) },
        read = { it[37].toFloat() },
        write = { p, v -> p[37] = v.toDouble() },
    ),
    EditorParam(
        key = "filmicWhite", label = "白点", icon = Icons.Outlined.ExposurePlus2,
        range = FILMIC_WHITE_EV_UI_RANGE, pivot = FILMIC_WHITE_EV_PIVOT,
        format = { formatEvSlot(it) },
        read = { it[38].toFloat() },
        write = { p, v -> p[38] = v.toDouble() },
    ),
    // ── E2-14：影调分区四条（[90..93]，引擎 `kFsStyle` 的 `uToneOn` 段）────────────
    // 量程 −1..+1 直通槽位，恒等 0；读数 = ±100 整数（`+50` / `0` / `-50`），中点 0。
    // 方向：正 = 该分区提亮（引擎 `g = 1 + hl·l² + sh·s² + wh·l³ + bl·s³`，乘性保色度比）。
    EditorParam(
        key = "highlights", label = "高光", icon = Icons.Outlined.LightMode,
        range = TONE_UI_RANGE, pivot = ZERO_UI_PIVOT,
        format = { formatOffset100(it, ZERO_UI_PIVOT) },
        read = { toneUi(it[90]) },
        write = { p, v -> p[90] = toneSlot(v) },
    ),
    EditorParam(
        key = "shadows", label = "阴影", icon = Icons.Outlined.DarkMode,
        range = TONE_UI_RANGE, pivot = ZERO_UI_PIVOT,
        format = { formatOffset100(it, ZERO_UI_PIVOT) },
        read = { toneUi(it[91]) },
        write = { p, v -> p[91] = toneSlot(v) },
    ),
    EditorParam(
        key = "whites", label = "白色色阶", icon = Icons.Outlined.Brightness7,
        range = TONE_UI_RANGE, pivot = ZERO_UI_PIVOT,
        format = { formatOffset100(it, ZERO_UI_PIVOT) },
        read = { toneUi(it[92]) },
        write = { p, v -> p[92] = toneSlot(v) },
    ),
    EditorParam(
        key = "blacks", label = "黑色色阶", icon = Icons.Outlined.Brightness4,
        range = TONE_UI_RANGE, pivot = ZERO_UI_PIVOT,
        format = { formatOffset100(it, ZERO_UI_PIVOT) },
        read = { toneUi(it[93]) },
        write = { p, v -> p[93] = toneSlot(v) },
    ),
    // 全局色彩强度 → 质感 → 手动通道增益（顺序理由见 [AdjustParams] 的 KDoc）
    EditorParam(
        key = "sat", label = "饱和度", icon = Icons.Outlined.WaterDrop,
        range = SAT_UI_RANGE, pivot = 1f,
        format = { formatOffset100(it, 1f) },
        // 一个滑杆同写 [20][21][22] 三槽（ADR §5.6④ 的硬约束，见 writeSaturation 的 KDoc）
        read = { satUi(it[21]) },
        write = { p, v -> writeSaturation(p, v) },
    ),
    EditorParam(
        key = "clarity", label = "清晰度", icon = Icons.Outlined.Details,
        range = CLARITY_UI_RANGE, pivot = 0f,
        format = { formatZero100(it) },
        read = { clarityUi(it[78]) },
        write = { p, v -> p[78] = claritySlot(v) },
    ),
    EditorParam(
        key = "wbR", label = "红", letter = "R",
        range = WB_GAIN_UI_RANGE, pivot = 1f,
        format = { formatOffset100(it, 1f) },
        read = { wbGainUi(it[1]) },
        write = { p, v -> p[1] = wbGainSlot(v) },
    ),
    EditorParam(
        key = "wbG", label = "绿", letter = "G",
        range = WB_GAIN_UI_RANGE, pivot = 1f,
        format = { formatOffset100(it, 1f) },
        read = { wbGainUi(it[2]) },
        write = { p, v -> p[2] = wbGainSlot(v) },
    ),
    EditorParam(
        key = "wbB", label = "蓝", letter = "B",
        range = WB_GAIN_UI_RANGE, pivot = 1f,
        format = { formatOffset100(it, 1f) },
        read = { wbGainUi(it[3]) },
        write = { p, v -> p[3] = wbGainSlot(v) },
    ),
)

/**
 * 「滤镜」组的**引擎参数**（E2-14；E2-15 只剩褪色 [94]；**U-6b 纳入黑白 [25] + 分离色调 [23][24]**）：
 * **黑白** / **分离色调 · 暖** / **分离色调 · 冷** / **褪色**。
 *
 * **不依赖 LUT**（引擎侧这四条在 `kFsStyle` 内独立生效）⇒ 没选 LUT 时照样可调 —— 这也是
 * 本组从"未选 LUT 就空表"变成常驻的原因。LUT 强度 [34] 是单独一条（[LutStrengthParam]），
 * 选了 LUT 才进表。
 *
 * 色温 [88] / 色调 [89] 已随 E2-15 移入「调整」组（用户反馈见 [AdjustParams] 的 KDoc）；
 * 胶片曲线 [5..10] 随 U-6b 归入「调整」组（它们是**曲线形状**，不是风格化调色）；
 * **这四条留在这里** —— 它们是风格化参数（去色 / 分区染色 / 抬黑场做"褪色胶片感"），
 * 不是基础调整。
 *
 * **排序理由（U-6b）**：**黑白在前、分离色调在后** —— "整体去色"（把三个通道抹成一条灰阶）
 * 在概念上先于"分区调色"（给暗部/亮部各染一个色）；顺序反了会诱导用户先分区染色再整体去色，
 * 那样辛苦调的分离色调会被去色抹掉（引擎里 `uMono` 的 `mix` 在分离色调之后）。
 * **分离色调两条与褪色相邻**：三者都是"胶片风格化"（暖/冷/洗白），挨着放便于一起比对；
 * 且分离色调·暖 / 冷是一对（正负两半），必须相邻。
 *
 * 读数口径（E2-10 全卡统一，格式化函数都在 `EditorExport.kt`）：
 *  - **黑白** `关` / `35`（0..100 整数；**0 = 彩色原样 ⇒ 显示"关"**，非零显示数值而不是"开"
 *    —— 它是**连续**混合比，`formatOff100` 的 KDoc 解释了为什么不用开关口径）；
 *    基准在最左端 ⇒ 不画中点标记
 *  - **分离色调 · 暖 / 冷** `+50`（±100，正 = 该半边染色增强）；中点 = `0`
 *  - **褪色** `35`（0..100 整数，0 = 关）；基准在最左端 ⇒ 不画中点标记
 */
internal val ColorParams: List<EditorParam> = listOf(
    // ── U-6b：黑白 [25]（整体去色，放在分区调色之前，理由见本段 KDoc）──────────────
    // 引擎 `out3 = mix(out3, vec3(luma(out3)), uMono)` ⇒ **连续**混合，不是开关（U-1 已接线）。
    EditorParam(
        key = "mono", label = "黑白", icon = Icons.Outlined.MonochromePhotos,
        range = MONO_UI_RANGE, pivot = ZERO_UI_PIVOT,
        format = { formatOff100(it) },
        read = { monoUi(it[25]) },
        write = { p, v -> p[25] = monoSlot(v) },
    ),
    // ── U-6b：分离色调 [23][24]（暖 / 冷一对，必须相邻）──────────────────────────
    // 量程 −1..+1 直通槽位，恒等 0；读数 ±100 整数（`+50` / `0` / `-50`），中点 0。
    EditorParam(
        key = "splitWarm", label = "分离色调 · 暖", icon = Icons.Outlined.WbSunny,
        range = SPLIT_UI_RANGE, pivot = ZERO_UI_PIVOT,
        format = { formatOffset100(it, ZERO_UI_PIVOT) },
        read = { splitUi(it[23]) },
        write = { p, v -> p[23] = splitSlot(v) },
    ),
    EditorParam(
        key = "splitCool", label = "分离色调 · 冷", icon = Icons.Outlined.AcUnit,
        range = SPLIT_UI_RANGE, pivot = ZERO_UI_PIVOT,
        format = { formatOffset100(it, ZERO_UI_PIVOT) },
        read = { splitUi(it[24]) },
        write = { p, v -> p[24] = splitSlot(v) },
    ),
    // ── E2-15：褪色 [94]（与上面的风格化调色相邻）────────────────────────────────
    EditorParam(
        key = "fade", label = "褪色", icon = Icons.Outlined.Gradient,
        range = FADE_UI_RANGE, pivot = ZERO_UI_PIVOT,
        format = { formatZero100(it) },
        read = { fadeUi(it[94]) },
        write = { p, v -> p[94] = fadeSlot(v) },
    ),
)

/**
 * LUT 强度 [34]（E2-1）：**只有选了 LUT 才有意义** ⇒ 未选时整条不进参数表（ADR-12：不给假滑杆）。
 *
 * 量程 **0..1.5**（E2-15 用户反馈："LUT强度应该统一可调至150"）：与拍照页的 LUT 强度滑杆
 * 同一量程（`RelativeSlider` 默认就是 `0f..1.5f`）。读数 `150`（整数 0..150，[formatZero150]）；
 * 基准 0 = 关 ⇒ 不画中点标记。
 */
internal val LutStrengthParam = EditorParam(
    key = "lutK", label = "强度", icon = Icons.Outlined.PhotoFilter,
    range = LUT_STRENGTH_UI_RANGE, pivot = 0f,
    format = { formatZero150(it) },
    read = { p -> if (p[34].isFinite()) p[34].toFloat().coerceIn(0f, 1.5f) else 0f },
    write = { p, v -> p[34] = v.coerceIn(0f, 1.5f).toDouble() },
)

/**
 * 「质感」组（E2-1 的「暗角」组，**SG-2 扩组并改名**）：**暗角** [66..68] + **光晕** [62..65]
 * + **柔光** [95..97]，共 10 条。
 *
 * **为什么组名从「暗角」改成「质感」**：这三类都是"胶片质感"层（光学渐晕 / 乳剂背反射光晕 /
 * 柔光镜 bloom），而"暗角"这个名字会让用户**根本不会点进来找光晕与柔光**（用户 2026-10-11：
 * "光晕、柔光要先做" —— 引擎已实现，但滑杆藏在名为"暗角"的组里 = 等于没做）。
 * 肌肉记忆的影响被压到最小：**组的位置（第三格）与组内第一条（"暗角 · 强度"）都没变**，
 * 老用户"点第三格 → 拖第一条"的动作路径逐字不变。理由与图标更换见 [EditorTab] 的 KDoc。
 *
 * **为什么每条 label 都带组内前缀**（"暗角 · 强度" / "光晕 · 强度" / "柔光 · 强度"）：
 * 扩组后组内有 **3 条"强度"、3 条"半径"、2 条"阈值"** —— 不加前缀，参数名行（[ParamNameRow]
 * 居中大字）会显示成三条一模一样的"强度"，用户不知道自己在调哪一个。前缀不是新名字，
 * 就是**子类名**（与 `PresetDetail.kt` 的 `DETAIL_META` 逐字相同 —— 那边是跨组平铺清单，
 * 本来就必须带前缀；两处文案由 `PresetDetailTest` 的护栏对拍，不可能漂移）。
 *
 * **排序理由**：**暗角 → 光晕 → 柔光**。暗角三条保持原位置（肌肉记忆 + 它是本组的历史主体）；
 * 光晕与柔光相邻放在后面 —— 两者共享同一套模糊金字塔（`soft-glow-SPEC.md` §5），是同一族
 * "高光扩散"，分开会让用户无法 A/B 对比（"夜景点光源的红色外晕 vs 白色柔光"是规格 §1 的判据）。
 * 组内按**槽位升序**，与 `DETAIL_META` 的行序一致。
 *
 * 读数口径（E2-10 全卡统一，格式化函数都在 `EditorExport.kt`）：
 *  - **暗角强度** `50`（0..100，0 = 关，引擎侧 [66] ≥ 0 整段跳过）；**暗角半径 / 羽化**
 *    `80` / `50`（0..200 —— darktable 的 `scale` / `falloff_scale` 本身就是百分比语义）；
 *    基准 = darktable 默认 80 / 50（画中点标记，告诉用户"默认在哪"）
 *  - **光晕 / 柔光的强度** `关` / `35`（[formatOff100]：0 → `关`，否则 0..100 整数；
 *    0 = 整链旁路）；基准在最左端 ⇒ 不画中点标记
 *  - **阈值 / 半径 / 暖度** `0.80` / `0.45` / `0.40`（**两位小数**，[formatDecimals2]）：
 *    这五条是**归一化形态量**（0..1 的显示域阈值 / 相对短边的半径 / 红橙程度），整数化会把
 *    0.45 与 0.50 显示成同一个数，而它们对应明显不同的形态。基准 = 各自的引擎默认值
 *    （画中点标记，告诉用户"重置会回到哪"）—— pivot 与引擎中性值的一致性由
 *    `EditorHaloGlowUiTest` 逐槽对拍 `FilmParams.h` 与 `CameraPresets.standard()` 钉死。
 */
internal val TextureParams: List<EditorParam> = listOf(
    EditorParam(
        key = "vigStrength", label = "暗角 · 强度", icon = Icons.Outlined.Vignette,
        range = 0f..1f, pivot = 0f,
        format = { formatZero100(it) },
        read = { vigStrengthUi(it[66]) },
        write = { p, v -> p[66] = vigStrengthSlot(v) },
    ),
    EditorParam(
        key = "vigRadius", label = "暗角 · 半径", icon = Icons.Outlined.RadioButtonUnchecked,
        range = 0f..2f, pivot = 0.8f,
        format = { formatZero200(it) },
        read = { vigRadiusUi(it[67]) },
        write = { p, v -> p[67] = vigRadiusSlot(v) },
    ),
    EditorParam(
        key = "vigFeather", label = "暗角 · 羽化", icon = Icons.Outlined.BlurOn,
        range = 0f..2f, pivot = 0.5f,
        format = { formatZero200(it) },
        read = { vigFeatherUi(it[68]) },
        write = { p, v -> p[68] = vigFeatherSlot(v) },
    ),
    // ── SG-2：光晕 halation [62..65]（E4-5 引擎已实现，本卡接进面板）──────────────
    // 量程 0..1 直通槽位（`HALO_GLOW_UI_RANGE`，与 `normalizedSlot` 共用同一份定义）。
    // ⚠ pivot = 引擎中性值：[62] = 0（关）、[63] 0.80、[64] 0.45、[65] 0.40。
    EditorParam(
        key = "haloStrength", label = "光晕 · 强度", icon = Icons.Outlined.Flare,
        range = HALO_GLOW_UI_RANGE, pivot = HALO_UI_PIVOT,
        format = { formatOff100(it) },
        read = { normalizedUi(it[62], HALO_UI_PIVOT) },
        write = { p, v -> p[62] = normalizedSlot(v) },
    ),
    EditorParam(
        key = "haloThreshold", label = "光晕 · 触发阈值", icon = Icons.Outlined.Brightness6,
        range = HALO_GLOW_UI_RANGE, pivot = HALO_THRESHOLD_UI_PIVOT,
        format = { formatDecimals2(it) },
        read = { normalizedUi(it[63], HALO_THRESHOLD_UI_PIVOT) },
        write = { p, v -> p[63] = normalizedSlot(v) },
    ),
    EditorParam(
        key = "haloRadius", label = "光晕 · 半径", icon = Icons.Outlined.Adjust,
        range = HALO_GLOW_UI_RANGE, pivot = HALO_RADIUS_UI_PIVOT,
        format = { formatDecimals2(it) },
        read = { normalizedUi(it[64], HALO_RADIUS_UI_PIVOT) },
        write = { p, v -> p[64] = normalizedSlot(v) },
    ),
    EditorParam(
        key = "haloWarmth", label = "光晕 · 暖度", icon = Icons.Outlined.LocalFireDepartment,
        range = HALO_GLOW_UI_RANGE, pivot = HALO_WARMTH_UI_PIVOT,
        format = { formatDecimals2(it) },
        read = { normalizedUi(it[65], HALO_WARMTH_UI_PIVOT) },
        write = { p, v -> p[65] = normalizedSlot(v) },
    ),
    // ── SG-2：柔光 soft glow [95..97]（SG-1 引擎已实现，本卡接进面板）─────────────
    // ⚠ pivot：[95] = 0（关，零 pass 零纹理）、[96] 0.35、[97] 0.70。
    EditorParam(
        key = "glowStrength", label = "柔光 · 强度", icon = Icons.Outlined.BlurCircular,
        range = HALO_GLOW_UI_RANGE, pivot = GLOW_UI_PIVOT,
        format = { formatOff100(it) },
        read = { normalizedUi(it[95], GLOW_UI_PIVOT) },
        write = { p, v -> p[95] = normalizedSlot(v) },
    ),
    EditorParam(
        key = "glowRadius", label = "柔光 · 半径", icon = Icons.Outlined.TripOrigin,
        range = HALO_GLOW_UI_RANGE, pivot = GLOW_RADIUS_UI_PIVOT,
        format = { formatDecimals2(it) },
        read = { normalizedUi(it[96], GLOW_RADIUS_UI_PIVOT) },
        write = { p, v -> p[96] = normalizedSlot(v) },
    ),
    EditorParam(
        key = "glowThreshold", label = "柔光 · 触发阈值", icon = Icons.Outlined.Brightness5,
        range = HALO_GLOW_UI_RANGE, pivot = GLOW_THRESHOLD_UI_PIVOT,
        format = { formatDecimals2(it) },
        read = { normalizedUi(it[97], GLOW_THRESHOLD_UI_PIVOT) },
        write = { p, v -> p[97] = normalizedSlot(v) },
    ),
)

/**
 * ④ 单滑杆（E2-10 的核心控件）：**满宽 + 密集刻度 + 中点标记 + 物理量读数**。
 *
 * 与旧形态（`StrengthRow` 的"标签｜细滑杆｜百分比"三段式）的三点区别，正好是 ADR-14 的
 * 三条关键修正：标签已在上一行居中大字；滑杆满宽；读数在上方右侧且是物理量。
 *
 * 拖动 state 留在本组件内（[RelativeSlider] 的相对拖动要求手势内累加、不依赖重组回流）；
 * 外层 `key(param.key, refreshTick)` 决定它何时重读槽位（换参数 / 重置 / 套用风格）。
 */
@Composable
internal fun EditorSlider(
    param: EditorParam,
    params: DoubleArray,
    onCommit: (EditorParam, Float) -> Unit,
    onFinished: () -> Unit,
) {
    var v by remember { mutableFloatStateOf(param.read(params)) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                param.format(v),
                color = FilmTheme.Accent,
                fontSize = 13.sp,
                fontFamily = FilmTheme.Mono,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(shadow = TextShadow),
            )
        }
        RelativeSlider(
            value = v,
            onValueChange = { nv ->
                v = nv
                onCommit(param, nv)
            },
            onDragEnd = { onFinished() },
            range = param.range,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            rowHeight = 44.dp,
            ticks = true,
            // 中点标记的位置由**纯函数**算（EditorExport.kt 的 pivotFraction）：基准落在端点
            // 或量程外时返回 null = 不画（清晰度 0 / 暗角强度 0 都在最左端，画了只是糊）
            midFraction = pivotFraction(param.range, param.pivot),
            barThumb = true,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ★ PS-3：拍照界面的**可调项索引**（槽位 → 参数表的项）
//
// 为什么需要：拍照界面的参数清单是**槽位级**的（`presetDetailSections` 逐槽一行：肩部 R/G/B
// 三行、饱和度暗/中/高三行），而滑杆是**参数级**的（一条"胶片曲线 · 肩部"滑杆同写 `[5..7]`
// 三槽）。用户点清单里的一行 ⇒ 要找到"管这个槽的那条滑杆"。
//
// ⚠ 这**不是第二份参数表**：本映射只回答"哪个槽归哪条滑杆"，量程 / 恒等值 / 读数格式 / 槽位
// 读写全部来自 [EditorParam] 自己（照抄一份 = 两份必然漂移的真相，正是本卡要消灭的东西）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 拍照链路读、但**当前没有滑杆**的槽（编辑器参数面板也没提供 ⇒ 只读 + 标注）。
 *
 * 逐条来源（`CameraStyleWhitelistTest` 的白名单里确实有它们）：
 *  - 颗粒 `[26]` 强度 / `[27]` 尺寸 / `[60]` 中间调偏置 / `[61]` 枢轴（E4-3 引擎已实现，
 *    但 E2 系列参数面板从未给它滑杆）。
 *
 * **PS-10 从本集合移除了 filmic 黑白点 `[37]` / `[38]`**（它们已在 `AdjustParams` 里有了
 * 滑杆 ⇒ 进 [CAMERA_TUNABLE_SLOT_PARAM]）。这两个槽**拍照链路真的读**
 * （`FrameRenderer.cpp` 的 `updateFilmicEV(..., p.filmic_black_ev, p.filmic_white_ev, ...)`）
 * ⇒ 属于"可调"，不再欠账。
 *
 * 这是**既有欠账**的如实标注（"拍照生效 · 暂无滑杆"）而不是造一条没有引擎语义对应的
 * 假滑杆；将来给颗粒补滑杆时，**删掉本集合里的对应项 + 在 [CAMERA_TUNABLE_SLOT_PARAM] 里
 * 加一行**即可（`CameraPresetEditTest` 会要求两处同步）。
 *
 * ★ 引擎插槽重构（阶段 1）：`[98]` 引擎插槽**不进本集合** —— 它不是**参数**（是"选哪个
 * 引擎"；阶段 3 的切换入口是**引擎选择面**，见 `docs/engine-slots-design.md` §8），而本
 * 集合的语义是"**是参数、但暂无滑杆**"。它不出现在参数面板靠的是**另一侧**过滤：`[98]`
 * 属于 `CAMERA_PATH_SLOTS`（`passFilm` 读它做非 0 降级判据）⇒ `fullEditReadonlySlots()`
 * 的 `includedSlots.filter { it !in CAMERA_PATH_SLOTS }` 不会把它带进只读组。
 */
internal val CAMERA_NO_SLIDER_SLOTS: Set<Int> = setOf(26, 27, 60, 61)

/**
 * 槽位 → 参数表的项（**拍照界面可拖的槽 = [CAMERA_PATH_SLOTS] − [CAMERA_NO_SLIDER_SLOTS]
 * − [CAMERA_ENGINE_SELECT_SLOTS]** —— 最后一项是"**根本不是参数**"的槽（引擎选择
 * `[98]`，阶段 3 走引擎选择面），见该常量的 KDoc；三项的语义区分也见 `CameraPresetEditTest`）。
 *
 * 用 `key` 查找（[EditorParam.key] 是稳定 key）而不是下标：参数表顺序变化时**不会静默错位**
 * （下标引用会让"肩部"滑杆绑到"趾部"的槽上，且编译不报错）。
 */
internal val CAMERA_TUNABLE_SLOT_PARAM: Map<Int, EditorParam> = buildMap {
    val all = AdjustParams + ColorParams + TextureParams
    fun bind(key: String, vararg slots: Int) {
        val p = all.first { it.key == key }
        for (s in slots) this[s] = p
    }
    // 基础调整
    bind("ev", 0)
    bind("wbR", 1); bind("wbG", 2); bind("wbB", 3)
    bind("contrast", 4)
    // 影调曲线（一条滑杆三槽 —— 与编辑器 writeLatitude 同一条 write）
    bind("curveShoulder", 5, 6, 7)
    bind("curveToe", 8, 9, 10)
    // ★ PS-10：filmic 黑白点（一条滑杆一槽；量程 / 读数 / 写值全在参数表那两条自己身上）
    bind("filmicBlack", 37); bind("filmicWhite", 38)
    // 色彩
    bind("sat", 20, 21, 22)          // 一条滑杆三槽（writeSaturation）
    bind("splitWarm", 23); bind("splitCool", 24); bind("mono", 25)
    bind("fade", 94)
    // 基础调整（影调分区）+ 清晰度 + 白平衡色温色调
    bind("clarity", 78)
    bind("temp", 88); bind("tint", 89)
    bind("highlights", 90); bind("shadows", 91); bind("whites", 92); bind("blacks", 93)
    // 胶片质感：暗角 / 光晕 / 柔光
    bind("vigStrength", 66); bind("vigRadius", 67); bind("vigFeather", 68)
    bind("haloStrength", 62); bind("haloThreshold", 63); bind("haloRadius", 64); bind("haloWarmth", 65)
    bind("glowStrength", 95); bind("glowRadius", 96); bind("glowThreshold", 97)
}
