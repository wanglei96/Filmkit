package com.filmkit.app.ui

import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.FilmParamKeys
import com.filmkit.app.camera.GLOW_RADIUS_UI_PIVOT
import com.filmkit.app.camera.GLOW_THRESHOLD_UI_PIVOT
import com.filmkit.app.camera.GLOW_UI_PIVOT
import com.filmkit.app.camera.HALO_RADIUS_UI_PIVOT
import com.filmkit.app.camera.HALO_THRESHOLD_UI_PIVOT
import com.filmkit.app.camera.HALO_UI_PIVOT
import com.filmkit.app.camera.HALO_WARMTH_UI_PIVOT
import com.filmkit.app.camera.PRESET_SOURCE_BUILTIN
import com.filmkit.app.camera.PRESET_SOURCE_IMPORTED
import com.filmkit.app.camera.clarityUi
import com.filmkit.app.camera.contrastUi
import com.filmkit.app.camera.expUi
import com.filmkit.app.camera.fadeUi
import com.filmkit.app.camera.formatDecimals2
import com.filmkit.app.camera.formatEv
import com.filmkit.app.camera.formatKelvin
import com.filmkit.app.camera.formatOff100
import com.filmkit.app.camera.formatOffset100
import com.filmkit.app.camera.formatZero100
import com.filmkit.app.camera.formatZero150
import com.filmkit.app.camera.formatZero200
import com.filmkit.app.camera.latitudeUi
import com.filmkit.app.camera.monoUi
import com.filmkit.app.camera.normalizedUi
import com.filmkit.app.camera.presetDeletable
import com.filmkit.app.camera.satUi
import com.filmkit.app.camera.tempUi
import com.filmkit.app.camera.tintUi
import com.filmkit.app.camera.toneUi
import com.filmkit.app.camera.vigFeatherUi
import com.filmkit.app.camera.vigRadiusUi
import com.filmkit.app.camera.vigStrengthUi
import com.filmkit.app.camera.wbGainUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ★ U-6a：**预设详情**的纯逻辑（无 Compose / 无 Android 依赖 ⇒ JVM 单测直接驱动）。
 *
 * 用户需求原话（2026-10-11）："**预设应该展示全部可调参数**，用户可编辑和新增预设"。
 * `slots` 是 `Map<Int, Double>`（稀疏 + 索引键），用户看不到它 —— 本文件把它翻译成人话：
 * 名称（[PresetDetailRow.label]）+ 读数（[PresetDetailRow.value]，带物理单位）。
 *
 * ## 名字从哪来（**唯一维护点，逐条有出处，不新造译名**）
 *
 * | 来源 | 槽位 | 说明 |
 * |---|---|---|
 * | **编辑器参数面板的既有 label**（`EditorScreen.kt` 的 `EditorParam` 表） | `[0..4]` `[20..22]` `[62..65]` `[66..68]` `[78]` `[88..94]` `[95..97]` | 逐字照抄（"曝光"/"对比度"/"色温"/"高光"/"白色色阶"/"饱和度"/"清晰度"/"褪色"/"光晕 · 触发阈值"/"柔光 · 半径"…），只加消歧前缀（见 [DetailMeta.label]）。★ **SG-2**：光晕 `[62..65]` / 柔光 `[95..97]` 已进面板 ⇒ 从下一行**移上来**（名字不再取自设计文档，而是面板原词） |
 * | **`PresetScope.kt` 的 `SLOT_LABELS`**（P-5 已确立的用户可懂术语） | `[5..10]` `[37][38]` | "胶片曲线"（**不是**工程术语"趾肩 latitude"）/ "黑点" / "白点" |
 * | **同上表的历史术语**（★ U-1 起 `[23..25]` 已两侧生效 ⇒ 从 `SLOT_LABELS` 删除，但详情页仍要展示它们） | `[23..25]` | "分离色调" / "黑白"（与 `SLOT_LABELS` 曾经的字面一致，未新造译名） |
 * | **`preset-DESIGN.md` §1 第 2/3 层的参数表**（引擎刚补齐、**面板仍无滑杆**的那批） | `[26][27][60][61]` `[69..77]` | "颗粒 强度/尺寸/中间调偏置/枢轴"、"暗部/中间调/高光 R/G/B 增益" |
 *
 * 三条纪律：
 *  1. **读数口径沿用 E2-10**（"读数改成物理量"）：曝光 `+0.5 EV`、色温 `5200K`、颗粒尺寸
 *     `ISO 400`；能直接复用 `EditorExport.kt` 的格式化函数的**一律复用**（同一份口径，
 *     不在这里重写第二套数字格式）。
 *  2. **`[34]` LUT 强度不进参数列表**（[presetSlotIncluded] 把它排除在 `slots` 之外）：
 *     它由 [EditorPreset.lutId] / [EditorPreset.lutStrength] 承载，在详情里单独一行
 *     （[presetLutLine]）—— 不是"静默消失"，是有另一个落点。
 *  3. **表里没有名字的槽位不静默跳过**：显示 [FilmParamKeys] 的语义名（`cross[0]` /
 *     `lut_strength`）或 `槽N`，永远看得见（与 `PresetScope.slotLabel` 同一条回落链）。
 *
 * ⚠ **未真机验证**：本文件是纯逻辑，单测覆盖；UI 呈现（字号 / 行高 / 换行 / 深色底对比度）
 * 需真机确认，见 `docs/reports/u6a-preset-detail-REPORT.md`。
 */

/** 详情里的一行：**一个槽位** + 中文名 + 读数（含单位）。 */
internal data class PresetDetailRow(
    val slot: Int,
    val label: String,
    val value: String,
)

/**
 * 详情里的一个分组：标题取自 `preset-DESIGN.md` §9.2 的四组
 * （基础调整 / 色彩 / 影调曲线 / 胶片质感）+ 兜底组「其它」。只输出**非空**组。
 */
internal data class PresetDetailSection(
    val title: String,
    val rows: List<PresetDetailRow>,
)

/** §9.2 的分组顺序（不是字典序：与用户从基础到质感的调色决策顺序一致）。 */
private const val SEC_BASE = "基础调整"
private const val SEC_COLOR = "色彩"
private const val SEC_CURVE = "影调曲线"
private const val SEC_FILM = "胶片质感"

/** 兜底组：表里没有名字的槽位（`cross[0..8]` / `reserved_95` / 未来新增槽 / 坏数据）。 */
private const val SEC_OTHER = "其它"

private val SECTION_ORDER = listOf(SEC_BASE, SEC_COLOR, SEC_CURVE, SEC_FILM, SEC_OTHER)

/** 一个槽位的展示元数据（[label] 见文件头的"名字从哪来"表）。 */
private class DetailMeta(
    val section: String,
    val label: String,
    val format: (Double) -> String,
)

/**
 * 三个通道的后缀（白平衡增益 / 胶片曲线 / 分区饱和 / 层间效应共用）。
 *
 * ★ 用**面板的原词**（`EditorParam(label = "红")`）而不是 `R/G/B`：白平衡那三行就是面板
 * 「调整」组的三条滑杆，用户认的是"红 / 绿 / 蓝"；单测
 * `detailLabelsReuseEditorPanelWording` 逐槽断言详情名包含面板 label（露 `R` 会直接红）。
 */
private val RGB = listOf("红", "绿", "蓝")

/**
 * 槽位 → 展示元数据（**本文件的唯一维护点**）。只覆盖"名字与格式都不是机械推导"的槽；
 * 其余槽走 [fallbackRow]（语义名 + 原值）。
 *
 * 为什么 label 带"颗粒 ·"/"暗角 ·"这类前缀：面板的 label 在同一组内不歧义（一组滑杆并列），
 * 详情里却是一张**跨组的平铺清单** —— "强度"出现三次（颗粒 / 暗角 / LUT）会分不清是在说
 * 哪一个。前缀取**分组名**，不新造参数名。
 */
private val DETAIL_META: Map<Int, DetailMeta> = buildMap {
    fun meta(slot: Int, section: String, label: String, format: (Double) -> String) {
        this[slot] = DetailMeta(section, label, format)
    }

    // ── 基础调整：面板「调整」组的既有 label 逐字照抄 ────────────────────────────
    // [0] 槽位本身就是 EV（−3..+3），而 formatEv 的入参是 UI 值（±1 = ±3 EV）⇒ 必须过 expUi
    meta(0, SEC_BASE, "曝光") { formatEv(expUi(it)) }
    // [1..3] 面板 label = "红"/"绿"/"蓝"（字母图标 R/G/B），读数 = 相对增益 ±100
    for (i in 0..2) meta(1 + i, SEC_BASE, RGB[i]) { formatOffset100(wbGainUi(it), 1f) }
    meta(4, SEC_BASE, "对比度") { formatOffset100(contrastUi(it), 1f) }
    meta(78, SEC_BASE, "清晰度") { formatZero100(clarityUi(it)) }
    // [88..93] 面板「调整」组（E2-15 / E2-14）；[88] 槽位是 mired 偏移 ⇒ 必须过 tempUi 才是 K
    meta(88, SEC_BASE, "色温") { formatKelvin(tempUi(it)) }
    meta(89, SEC_BASE, "色调") { formatOffset100(tintUi(it), 0f) }
    meta(90, SEC_BASE, "高光") { formatOffset100(toneUi(it), 0f) }
    meta(91, SEC_BASE, "阴影") { formatOffset100(toneUi(it), 0f) }
    meta(92, SEC_BASE, "白色色阶") { formatOffset100(toneUi(it), 0f) }
    meta(93, SEC_BASE, "黑色色阶") { formatOffset100(toneUi(it), 0f) }

    // ── 色彩 ──────────────────────────────────────────────────────────────────
    // [20..22] 分区饱和三档：面板是**一个**滑杆（label "饱和度"）同写三槽（writeSaturation），
    // 详情按槽拆开 ⇒ 加§1 第 2 层的"暗 / 中 / 高"后缀区分。三槽同值时显示也一致（如实）。
    meta(20, SEC_COLOR, "饱和度 · 暗部") { formatOffset100(satUi(it), 1f) }
    meta(21, SEC_COLOR, "饱和度 · 中间调") { formatOffset100(satUi(it), 1f) }
    meta(22, SEC_COLOR, "饱和度 · 高光") { formatOffset100(satUi(it), 1f) }
    // [23][24] 分离色调：★ U-1 已把这组接到编辑器链路 ⇒ 两侧都生效 ⇒ `PresetScope` 不再为它们
    // 出作用域提示（名字沿用 P-5 当年的字面术语，现溯源到 §9.2 第 13 项）；量程 −1..1
    meta(23, SEC_COLOR, "分离色调 · 暖") { signed2(it) }
    meta(24, SEC_COLOR, "分离色调 · 冷") { signed2(it) }
    // [25] 黑白：★ U-1 把 `[25]` 接到编辑器链路（§9.2 第 14 项"单色（黑白）"）；★ U-6b 面板
    // 补上"黑白"滑杆 ⇒ 读数**必须跟面板同口径**（`formatOff100`：0 → `关`、否则 0..100 整数；
    // SG-2 起该函数同时服务光晕/柔光强度，名字从 `formatMono` 改过来）。
    // ⚠ 旧口径（`>= 0.5` 才算"开"）在面板能连续调之后是**假读数**：0.35 会被显示成"关"。
    // ⚠ 名字**不因提示消失而退回**工程名 `monochrome`（单测 `detailLabelsMatchScopeNoticeWording` 钉住）
    meta(25, SEC_COLOR, "黑白") { formatOff100(monoUi(it)) }
    // [94] 褪色：面板「滤镜」组 label（0..100，0 = 关）
    meta(94, SEC_COLOR, "褪色") { formatZero100(fadeUi(it)) }

    // ── 影调曲线 ──────────────────────────────────────────────────────────────
    // [5..10] filmic latitude（`FilmParams.h:58-59`：**5..7 = shoulder、8..10 = toe**；
    // ⚠ preset-DESIGN.md §1 的表格把这两行写反了，以引擎字段名为准）。
    // 名字 = 面板 label（U-6b 面板已有"胶片曲线 · 肩部 / 趾部"两条滑杆，逐字同文案）+ 通道后缀；
    // 读数**与面板同一个函数**（`formatDecimals2(latitudeUi(...))`，两位小数、**不套 formatZero100**）：
    // latitude 的语义在 0 附近敏感（0.01 = darktable 默认、0 = 极端 S 曲线）⇒ 整数化会把
    // 0.01 / 0.02 都显示成 1 / 2。过 latitudeUi 而不是直读槽位 ⇒ 坏数据与面板回落同一个恒等值 0.01。
    for (i in 0..2) meta(5 + i, SEC_CURVE, "胶片曲线 · 肩部 ${RGB[i]}") { formatDecimals2(latitudeUi(it)) }
    for (i in 0..2) meta(8 + i, SEC_CURVE, "胶片曲线 · 趾部 ${RGB[i]}") { formatDecimals2(latitudeUi(it)) }
    // [37][38] filmic 黑白点：PresetScope 用"黑点"/"白点"（= SettingsScreen「黑点（多暗算黑）」）
    meta(37, SEC_CURVE, "黑点") { evText(it) }
    meta(38, SEC_CURVE, "白点") { evText(it) }

    // ── 胶片质感（§1 第 3 层；引擎 E4-3/E4-2/E4-5/SG-1 已实现）──────────────────
    // [26][60] 的槽位量程是 **0..100**（`FilmParams.h:55-58` / `:369`），
    // 借 formatZero100（入参 0..1）的整数化与夹紧口径 ⇒ 先 /100。
    meta(26, SEC_FILM, "颗粒 · 强度") { formatZero100((it / 100.0).toFloat()) }
    meta(27, SEC_FILM, "颗粒 · 尺寸") { "ISO ${it.roundToInt()}" }
    meta(60, SEC_FILM, "颗粒 · 中间调偏置") { formatZero100((it / 100.0).toFloat()) }
    // [61] 枢轴是**形状/位置**参数（0.5 = 峰值不移）⇒ 两位小数，与 latitude 同口径
    meta(61, SEC_FILM, "颗粒 · 枢轴") { decimals2(it) }
    // [62..65] 光晕（halation）：★ **SG-2 起面板有滑杆** ⇒ 名字逐字 = 面板 label，读数口径
    // 也必须与面板逐字相同（两边调的是同一个 `normalizedUi(slot, pivot)`，坏数据回落同一个
    // pivot = 引擎默认值）。[62] 强度是"0 就是关"型 ⇒ `formatOff100`（0 → `关`）；
    // [63..65] 是归一化形态量（显示域阈值 / 相对短边半径 / 红橙程度）⇒ **两位小数**，
    // 整数化会把 0.45 与 0.50 显示成同一个数（SG-2 前这里用 formatZero100，已改）。
    meta(62, SEC_FILM, "光晕 · 强度") { formatOff100(normalizedUi(it, HALO_UI_PIVOT)) }
    meta(63, SEC_FILM, "光晕 · 触发阈值") { formatDecimals2(normalizedUi(it, HALO_THRESHOLD_UI_PIVOT)) }
    meta(64, SEC_FILM, "光晕 · 半径") { formatDecimals2(normalizedUi(it, HALO_RADIUS_UI_PIVOT)) }
    meta(65, SEC_FILM, "光晕 · 暖度") { formatDecimals2(normalizedUi(it, HALO_WARMTH_UI_PIVOT)) }
    // [66..68] 暗角：面板「质感」组 label = "暗角 · 强度/半径/羽化"（★ SG-2 起带组内前缀 ——
    // 扩组后组里有三条"强度"，不带前缀分不清；槽位 66 负值 = 变暗 ⇒ 过 vig*Ui）
    meta(66, SEC_FILM, "暗角 · 强度") { formatZero100(vigStrengthUi(it)) }
    meta(67, SEC_FILM, "暗角 · 半径") { formatZero200(vigRadiusUi(it)) }
    meta(68, SEC_FILM, "暗角 · 羽化") { formatZero200(vigFeatherUi(it)) }
    // [69..77] 层间效应：§1 第 2 层"暗部 / 中间调 / 高光 R/G/B 增益"（0.5..1.5，恒等 1.0）
    val zones = listOf("暗部" to 69, "中间调" to 72, "高光" to 75)
    for ((zone, start) in zones) {
        for (i in 0..2) meta(start + i, SEC_FILM, "层间 · $zone ${RGB[i]}") { formatOffset100(it.toFloat(), 1f) }
    }
    // [95..97] 柔光（soft glow）：★ **SG-2 起面板有滑杆**（引擎 SG-1 已实现）。三条与光晕
    // [62..65] 是同族（共享模糊金字塔）⇒ 口径逐条对应：强度 `formatOff100`（0 → `关`）、
    // 半径 / 触发阈值两位小数。名字逐字 = 面板 label（"柔光 · 强度/半径/触发阈值"）。
    meta(95, SEC_FILM, "柔光 · 强度") { formatOff100(normalizedUi(it, GLOW_UI_PIVOT)) }
    meta(96, SEC_FILM, "柔光 · 半径") { formatDecimals2(normalizedUi(it, GLOW_RADIUS_UI_PIVOT)) }
    meta(97, SEC_FILM, "柔光 · 触发阈值") { formatDecimals2(normalizedUi(it, GLOW_THRESHOLD_UI_PIVOT)) }
}

/**
 * 预设的**参数清单**（稀疏语义）：只包含 `slots` 里真正记下的槽，按 §9.2 的四组 + 兜底组
 * 分组、组内按槽位升序。`slots` 为空 ⇒ 返回空列表（UI 显示"这条预设不含参数"）。
 *
 * 未知槽位（表里没名字）**不静默跳过**：进「其它」组，名字从 [FilmParamKeys] → `槽N` 回落。
 */
internal fun presetDetailSections(slots: Map<Int, Double>): List<PresetDetailSection> {
    val bySection = LinkedHashMap<String, MutableList<PresetDetailRow>>()
    for ((slot, v) in slots.entries.sortedBy { it.key }) {
        // [34] LUT 强度不在这里（详情里由 [presetLutLine] 单独一行承载）：它是"混合了多少"这个
        // 资源引用的一部分，不是风格参数；`presetSlotIncluded` 本来就不会把它写进 slots，
        // 这道判定是防御性的（手改 JSON / 旧数据）。
        if (slot == LUT_STRENGTH_SLOT) continue
        val meta = DETAIL_META[slot]
        val row = if (meta != null) {
            PresetDetailRow(slot, meta.label, meta.format(v))
        } else {
            fallbackRow(slot, v)
        }
        bySection.getOrPut(meta?.section ?: SEC_OTHER) { mutableListOf() }.add(row)
    }
    return SECTION_ORDER.mapNotNull { title ->
        bySection[title]?.let { PresetDetailSection(title, it) }
    }
}

/** [34]：由 [EditorPreset.lutStrength] 承载，不进参数列表（与 `presetSlotIncluded` 同一条口径）。 */
private const val LUT_STRENGTH_SLOT = 34

/**
 * 表里没有名字的槽位：显示**语义名**（[FilmParamKeys]）→ `槽N` 回落，值用原值文本。
 * "难看但看得见"比"静默消失"好 —— 前者是评审能发现的缺口，后者是用户永远不知道丢了什么。
 */
private fun fallbackRow(slot: Int, v: Double): PresetDetailRow =
    PresetDetailRow(slot, FilmParamKeys.bySlot[slot] ?: "槽$slot", rawText(v))

/**
 * 详情里的 LUT 一行（**缺失 = 不套用**，与套用语义一致 ⇒ 返回 null，UI 不渲染该行）。
 * `lutId` 的**中文名由 UI 层查** `LutLibrary`（纯逻辑层不碰 Context）；查不到时 UI 显示 id
 * （资产被删的降级提示，见 `applyPreset` 的第 ② 分支）。
 */
internal data class PresetLutLine(val lutId: String, val strength: String)

/** LUT + 强度（[EditorPreset.lutStrength] 0..1.5，读数与 LUT 强度滑杆同口径 0..150）。 */
internal fun presetLutLine(p: EditorPreset): PresetLutLine? =
    p.lutId?.let { PresetLutLine(it, formatZero150(p.lutStrength.toFloat())) }

/** 来源文案（内置只读 / 用户自建 / 外部导入）。未知取值按"用户"显示（数据层的默认值）。 */
internal fun presetSourceText(source: String): String = when (source) {
    PRESET_SOURCE_BUILTIN -> "内置"
    PRESET_SOURCE_IMPORTED -> "导入"
    else -> "用户"
}

/**
 * 创建时间（`yyyy-MM-dd HH:mm`）。**`createdAt <= 0` ⇒ `—`**：内置三条的 JSON 没有
 * `createdAt` 字段（随包分发，没有"创建时刻"这个语义），显示 1970 比显示"—"更容易误导。
 */
internal fun presetCreatedAtText(ms: Long): String = if (ms <= 0L) {
    "—"
} else {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
}

// ── 数字格式化（只补 EditorExport 里没有的那几种；已有的**一律复用**，不重写第二套）──────

/** 两位小数：`0.01` / `-0.10`（latitude / 枢轴 / 分离色调）。 */
private fun decimals2(v: Double): String {
    val n = if (v.isFinite()) (v * 100).roundToInt() else 0
    val a = abs(n)
    return "${if (n < 0) "-" else ""}${a / 100}.${(a % 100).toString().padStart(2, '0')}"
}

/** 两位小数 + 正号：`+0.10` / `0.00` / `-0.50`（零不带符号，与 `formatOffset100` 同形）。 */
private fun signed2(v: Double): String = if (v.isFinite() && v > 0) "+${decimals2(v)}" else decimals2(v)

/** EV 读数（**槽位本身就是 EV**，不是 UI 值）：`+4.0 EV` / `0.0 EV` / `-7.6 EV`（`formatEv` 同形）。 */
private fun evText(v: Double): String {
    val n = if (v.isFinite()) (v * 10).roundToInt() else 0
    val a = abs(n)
    val s = "${a / 10}.${a % 10}"
    return when {
        n > 0 -> "+$s EV"
        n < 0 -> "-$s EV"
        else -> "$s EV"
    }
}

/** 原值文本（未映射槽位）：整数不带小数点（`1` / `0.5` / `-0.18`）。 */
private fun rawText(v: Double): String = when {
    !v.isFinite() -> "0"
    v % 1.0 == 0.0 && abs(v) < 1e9 -> v.toLong().toString()
    else -> decimals2(v)
}

// ─────────────────────────────────────────────────────────────────────────────
// ★ PS-1（用户 2026-10-11："预设还是没有展示预设包含的配置面板。还是没办法新增、编辑、
// 删除预设。"）：列表行「⋯」菜单 / 参数摘要 / 编辑面板按钮 —— **全是纯逻辑**
//（无 Compose / 无 Context ⇒ JVM 单测直接驱动）。
//
// 为什么可用性要抽成函数而不是写在 Composable 里：投诉的根因就是"UI 把能力藏起来"——
// 内置预设的删除按钮此前**根本不渲染**（`if (presetDeletable(p))` 的 else 只有一枚「内置」
// 标记），而用户手上只有内置三条 ⇒ 一个删除按钮都看不到，自然得出"没法删"的结论。
// 抽成 `List<PresetMenuItem>` 之后，单测能逐项断言"内置：套用 / 查看配置可用，
// 编辑 / 重命名 / 复制 / 删除**置灰但在列表里**"，UI 只负责把这份清单画出来。
// ─────────────────────────────────────────────────────────────────────────────

/** 「⋯」菜单里的动作（枚举顺序 = [presetMenuItems] 的渲染顺序）。 */
internal enum class PresetAction { APPLY, VIEW, EDIT, RENAME, COPY, DELETE }

/**
 * 菜单里的一项：[label] 是给用户看的词，[enabled] = 能不能点。
 *
 * **不可用的项照样在清单里**（UI 置灰渲染）—— 隐藏正是 PS-1 投诉的根因。
 */
internal data class PresetMenuItem(val action: PresetAction, val label: String, val enabled: Boolean)

/**
 * 列表行「⋯」菜单的完整清单（顺序固定：主操作在前、危险操作在后）。
 *
 * @param paramsReady 编辑器参数是否已就绪（首帧前谈不上套用 / 编辑）—— 与「保存当前」的
 *   `canSave` 是**同一个判据**，调用侧传同一个值，不另算一份。
 *
 * 可删判据 = [presetDeletable]（内置只读的**唯一**实现，P-2c 立的规矩）：内置预设的
 * 编辑 / 重命名 / 复制 / 删除四项全部由它置灰。"复制内置"技术上可行（产出一条用户预设副本），
 * 但本卡按架构师口径把内置整条路径设为只读 —— 想改内置的配方就「+ 新建预设」+「从当前参数更新」。
 */
internal fun presetMenuItems(p: EditorPreset, paramsReady: Boolean): List<PresetMenuItem> {
    val user = presetDeletable(p)
    return listOf(
        PresetMenuItem(PresetAction.APPLY, "套用", paramsReady),
        PresetMenuItem(PresetAction.VIEW, "查看配置", true),
        PresetMenuItem(PresetAction.EDIT, "编辑", user && paramsReady),
        PresetMenuItem(PresetAction.RENAME, "重命名", user && paramsReady),
        PresetMenuItem(PresetAction.COPY, "复制", user),
        PresetMenuItem(PresetAction.DELETE, "删除", user),
    )
}

/**
 * 菜单底部的一句**实话**（内置预设 ⇒ 说明为什么上面四项是灰的）；用户预设 ⇒ null（不占行）。
 *
 * 与 [presetMenuItems] 同一条判据（[presetDeletable]）：文案与置灰状态不可能各说各话。
 */
internal fun presetReadOnlyNote(p: EditorPreset): String? = if (presetDeletable(p)) {
    null
} else {
    "内置预设只读：可套用 / 查看配置，不能编辑、重命名、复制或删除"
}

/** 列表行的参数摘要最多显示几项（超出加「…」；LUT 那一项**不占**这个额度）。 */
internal const val PRESET_SUMMARY_MAX = 4

/**
 * 列表行的**参数摘要**（用户第 1 条诉求"看不到这条预设包含什么"的行内答案）：
 * 「标签 读数」若干项 + 「…」+ LUT 行。
 *
 * 数据源是 [presetDetailSections]（**唯一维护点**：中文名 / 单位 / 格式化全在那边，
 * 这里只做"挑哪几项"）⇒ 摘要与详情页的读数**逐字相同**，不可能漂移。
 *
 * 挑选策略 = **分组轮转**（每组先取第一项，再取第二项…）：直接按槽位顺序取前 4 项，
 * 内置「徕卡一瞬」会取到 `对比度 / 饱和度 / 分离色调×2`（全在基础与色彩两组），
 * 用户最认的"颗粒 / 暗角"反而被截掉 —— 轮转让四个分组都有代表（实测输出见
 * `PresetPanelUiTest`）。
 *
 * @param lutLabel LUT 的中文名（UI 层查 `LutLibrary`；纯逻辑层不碰 Context）——
 *   null ⇒ 回落成 id（资产被删 / 单测）。
 */
internal fun presetSummaryParts(
    p: EditorPreset,
    lutLabel: String? = null,
    max: Int = PRESET_SUMMARY_MAX,
): List<String> {
    val sections = presetDetailSections(p.slots)
    val total = sections.sumOf { it.rows.size }
    val out = ArrayList<String>(max + 2)
    var depth = 0
    while (out.size < max) {
        var picked = false
        for (sec in sections) {
            if (out.size >= max) break
            val row = sec.rows.getOrNull(depth) ?: continue
            out += "${row.label} ${row.value}"
            picked = true
        }
        if (!picked) break          // 所有分组都取空了
        depth++
    }
    if (total > out.size) out += "…"
    presetLutLine(p)?.let { out += "LUT ${lutLabel ?: it.lutId} ${it.strength}" }
    return out
}

/** [presetSummaryParts] 的单行形态（列表行的副标题）。空预设 ⇒ 空串（UI 显示"不含参数"）。 */
internal fun presetSummaryText(
    p: EditorPreset,
    lutLabel: String? = null,
    max: Int = PRESET_SUMMARY_MAX,
): String = presetSummaryParts(p, lutLabel, max).joinToString(" · ")

/**
 * 预设编辑面板底部按钮的可用性（**新建态 / 编辑态两套**，PS-1 的纯逻辑）。
 *
 * 与 [presetMenuItems] 分开的理由：菜单是"能对这条预设做什么"，这里是"面板里现在能提交什么"，
 * 两者的输入不同（前者不知道输入框里有没有名字，后者不知道菜单开着没有）。
 */
internal data class PresetEditorButtons(
    /** 新建态：「保存当前参数为新预设」（= E3-1a 的「保存当前」） */
    val saveNew: Boolean,
    /** 编辑态：「从当前参数更新」（U-6a 的 updatePreset） */
    val updateFromCurrent: Boolean,
    /** 编辑态：「保存名称 / 分组」（重命名 = 本卡新增的 renamePreset） */
    val saveMeta: Boolean,
    /** 编辑态：「删除」（二次确认在面板里，仅 user 预设） */
    val delete: Boolean,
)

/**
 * @param isNew 新建态（true）/ 编辑态（false）
 * @param preset 编辑态的那条预设（新建态传 null）
 * @param paramsReady 当前参数已就绪（首帧前存不了也更新不了）
 * @param name 名称输入框的**当前内容**（空 ⇒ 保存类按钮置灰，但按钮仍在）
 */
internal fun presetEditorButtons(
    isNew: Boolean,
    preset: EditorPreset?,
    paramsReady: Boolean,
    name: String,
): PresetEditorButtons {
    val named = name.isNotBlank()
    return if (isNew) {
        PresetEditorButtons(
            saveNew = paramsReady && named,
            updateFromCurrent = false,
            saveMeta = false,
            delete = false,
        )
    } else {
        PresetEditorButtons(
            saveNew = false,
            updateFromCurrent = paramsReady,
            saveMeta = named,
            delete = preset != null && presetDeletable(preset),
        )
    }
}

/**
 * 新建预设的默认名：`我的预设 N`，N = 第一个不与**现有预设重名**的编号。
 *
 * 为什么给默认名而不是留空：留空时"保存"按钮是灰的 —— 用户看到的是一个点不动的按钮，
 * 而 PS-1 投诉的第一句正是"要先填名称才可点"这件事没人告诉他。默认名让"新建 → 保存"
 * 一路可点（用户想改名就在同一个面板里改）。
 */
internal fun defaultPresetName(existing: List<EditorPreset>): String {
    val used = existing.mapTo(HashSet(existing.size * 2)) { it.name }
    var n = 1
    while ("我的预设 $n" in used) n++
    return "我的预设 $n"
}
