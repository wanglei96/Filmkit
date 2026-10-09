package com.filmkit.app.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv
import com.filmkit.app.ui.CameraPresets
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 编辑器导出（E1-4 / ADR-3）：参数起点、编码、**另存新片**与**覆盖原片**。
 *
 * 这里只做"参数 → 编码 → 落盘"的收尾；渲染必须先成功（调用方拿到成片 Bitmap 才进来）。
 */

/**
 * 编辑器参数数组（集成点 I-3）：以 [CameraPresets.standard]（长度 = `CameraPresets.COUNT`，
 * ADR-1 单一参数源）
 * 为底，把参数快照里**存在且有限**的槽位覆盖上去。
 *
 * 为什么不是"直接返回快照数组"：
 *  - 快照来自历史版本时长度可能少于当前契约（60 → 78 这类契约升级），缺的槽位必须是
 *    基线值而不是 0（0 在 [5..10] latitude 语义下是"极端 S 曲线"，不是"无效果"）；
 *  - 快照里的 NaN/Inf 是坏数据（JSON 写入侧已净化，读取侧仍防一手）：当作"没记录"。
 *
 * @param raw 快照参数；null = 这张没有参数记录（ADR-4：调用方要提示"已用标准基线"）
 */
internal fun editorParams(raw: DoubleArray?): DoubleArray {
    val base = CameraPresets.standard()
    if (raw == null) return base
    val n = min(raw.size, base.size)
    for (i in 0 until n) {
        val v = raw[i]
        if (v.isFinite()) base[i] = v
    }
    return base
}

/**
 * 打开编辑器时的参数起点（E2-1，架构师 2026-10-08 拍板）：**画面必须与原片完全一致**。
 *
 * 成片在拍摄时已经把 LUT 烘焙进去了，若编辑器再把快照里的同一个 LUT + 强度套一遍，
 * 用户第一眼看到的是"照片莫名其妙变浓了"。所以这里在 [editorParams] 之上把 **[34]
 * 强制置 0** —— 引擎侧 `bindLut` 见 `lut_strength = 0` ⇒ `uLutOn = 0` ⇒ LUT 整段旁路
 *（E1-3 离线验收：输出与输入 max|Δ| = 1 LSB，仅 dither）。
 *
 * 快照里的 LUT 选择与强度**不丢**：调用方把它另存为 UI 状态（"原片风格：xxx · 85%"），
 * 配一个「套用」把当初的值原样下发。
 */
internal fun editorOpenParams(snap: StillSnapshot?): DoubleArray =
    editorParams(snap?.params).also { it[34] = 0.0 }

/**
 * 「无修改」比较容差（E2-9 / ADR-13）。取 **1e-4** 的依据 —— 两侧余量都要够：
 *
 *  - **必须盖住 Float → Double 的精度损失**（判"无修改"的一侧）：槽位量级最大的是暗角
 *    半径/羽化（`ui * 100`，上限 200），而 UI 滑杆的值是 Float（24 位尾数），Float 在 2.0
 *    附近的 ulp ≈ 2.4e-7 ⇒ 换算到槽位 ≈ 2.4e-5（实测 `vigRadiusSlot(0.8f)` 与 80.0 差
 *    1.19e-6）。1e-4 是它的 4 倍，够。
 *  - **必须远小于真实修改的最小步长**（判"有修改"的一侧）：滑杆是相对拖动
 *    （`delta / widthPx * span`），手指移 1px 在 300px 宽、span = 2 的滑杆上 ⇒ 槽位变化
 *    ≈ 6.7e-3，是 1e-4 的 67 倍。
 *  ⇒ 两端各有 1 个数量级以上的余量，不存在"漏判修改"或"误判修改"的窗口。
 */
internal const val PARAM_EPS = 1e-4

/**
 * 「无修改」判据（E2-9 / ADR-13）：[current] 与**打开编辑器时**的基准 [baseline] 逐槽相同
 * ⇒ 导出可直通原片字节（不渲染、不编码）。
 *
 * 三条设计约束：
 *  1. **基准是"打开时的参数"**（[editorOpenParams] 的返回值副本），不是快照原文 ——
 *     该函数已把 `[34] lut_strength` 强制置 0（E2-1 拍板"打开即与原片一致"），拿快照
 *     原文比会把"打开时那一次必然的 LUT 归零"误判成用户修改。
 *  2. **值比较，不是"是否碰过滑杆"**：用户改了又改回原值 ⇒ 画面与原片一致 ⇒ 该直通。
 *  3. **不用 `==`**（E2-3 踩过的浮点坑：`0.01f.toDouble()` = 0.009999999776…）：容差见
 *     [PARAM_EPS]。
 *
 * **非有限值（NaN/Inf）一律判"有修改"**：基准来自 [editorParams]（已过滤坏数据）⇒ 恒有限，
 * 所以出现非有限值只可能是 current 侧被写坏。此时宁可多渲染一次（安全），也不能拿它比出
 * "无修改"而把用户的修改丢掉 —— 直通是"输出原片字节"，误判的代价是**丢修改**。
 *
 * @return true = 逐槽相同（含容差）⇒ 可直通原片字节
 */
internal fun paramsUnchanged(
    current: DoubleArray,
    baseline: DoubleArray,
    eps: Double = PARAM_EPS,
): Boolean {
    if (current.size != baseline.size) return false
    for (i in current.indices) {
        val a = current[i]
        val b = baseline[i]
        if (!a.isFinite() || !b.isFinite() || abs(a - b) > eps) return false
    }
    return true
}

/**
 * 暗角参数的 UI ↔ 槽位映射（E2-1）。**纯逻辑、无 Android 依赖** ⇒ JVM 单测直接覆盖往返。
 *
 * 槽位语义（E4-2 已实现并离线验收，见 `core/engine/src/film/FilmParams.h`）：
 *  - [66] strength ∈ [−1, 0]：**负值才是暗角**，越负越暗；0 = 关（shader 整段跳过）
 *  - [67] radius   ∈ [0, 200]：falloff 起点（%），darktable `scale` 默认 80
 *  - [68] feather  ∈ [0, 200]：过渡宽度（%），darktable `falloff_scale` 默认 50
 *
 * UI 约定（"用户不该看到负数"，同时与 LUT 强度行的百分比读数一致）：
 *  - 强度滑杆 = **0%..100%**，槽位 `[66] = −ui/100`（0% = 关、100% = 最强）
 *  - 半径/羽化滑杆 = **0%..200%**，槽位 = `ui * 100`（80% 就是 80）
 *
 * 这里的 ui 单位 = 显示读数 / 100（[com.filmkit.app.ui.StrengthRow] 的 `v`：显示恒为
 * `v * 100%`），所以强度 ui ∈ 0..1、半径/羽化 ui ∈ 0..2。
 */

/** UI 暗角强度（0..1 = 显示 0..100%）→ 槽位 [66]（−1..0）。 */
internal fun vigStrengthSlot(ui: Float): Double = -ui.coerceIn(0f, 1f).toDouble()

/**
 * 槽位 [66]（−1..0）→ UI 强度（0..1）。
 * **正值一律当"关"**：本 UI 永不产生正值（正 = 提亮四角，不是暗角），
 * 出现在快照里只可能是手改 prefs 的坏数据 —— 不能把"提亮"画成滑杆上的一段。
 */
internal fun vigStrengthUi(slot: Double): Float =
    if (!slot.isFinite() || slot > 0.0) 0f else (-slot).coerceIn(0.0, 1.0).toFloat()

/** UI 暗角半径（0..2 = 显示 0..200%）→ 槽位 [67]（0..200）。 */
internal fun vigRadiusSlot(ui: Float): Double = ui.coerceIn(0f, 2f).toDouble() * 100.0

/** 槽位 [67] → UI 半径（0..2）。坏数据/越界回落到 darktable 默认 80% 对应的 0.8。 */
internal fun vigRadiusUi(slot: Double): Float =
    if (!slot.isFinite()) 0.8f else (slot / 100.0).coerceIn(0.0, 2.0).toFloat()

/** UI 暗角羽化（0..2 = 显示 0..200%）→ 槽位 [68]（0..200）。 */
internal fun vigFeatherSlot(ui: Float): Double = ui.coerceIn(0f, 2f).toDouble() * 100.0

/** 槽位 [68] → UI 羽化（0..2）。坏数据/越界回落到 darktable 默认 50% 对应的 0.5。 */
internal fun vigFeatherUi(slot: Double): Float =
    if (!slot.isFinite()) 0.5f else (slot / 100.0).coerceIn(0.0, 2.0).toFloat()

/**
 * 基础调整的 UI ↔ 槽位映射（E2-3）。与上面暗角同一范式：**纯逻辑、无 Android 依赖**
 * ⇒ JVM 单测直接覆盖往返与边界。
 *
 * 槽位语义（E2-2 已实现并独立验收，见 ADR「架构师裁决（2026-10-09）」）：
 *  - [0]      曝光 EV（线性域 × 2^ev），恒等 **0**
 *  - [1..3]   白平衡 RGB 增益（线性域），恒等 **1,1,1**
 *  - [4]      对比度（显示域绕中灰），恒等 **1**
 *  - [20..22] 分区饱和度（引擎读 [21]），恒等 **1,1,1**
 *  - [78]     清晰度（显示域局部对比），恒等 **0 = 关**
 *
 * UI 约定：滑杆读数由 [com.filmkit.app.ui.StrengthRow] 渲染，恒为 `v * 100%`，
 * 所以下面每条都给出"百分比 ↔ 槽位"的对应（[EXP_UI_RANGE] 等量程常量由 UI 与
 * 映射函数**共用同一份定义** —— 否则滑杆能拖到 clamp 之外，读数与槽位不符 = 假滑杆）。
 */

/** 曝光 UI 量程：显示 −100%..+100%，对应槽位 −3..+3 EV（0% = 不变）。 */
internal val EXP_UI_RANGE = -1f..1f

/** 白平衡增益 UI 量程：显示 50%..200%（100% = 不变）。 */
internal val WB_GAIN_UI_RANGE = 0.5f..2f

/**
 * 对比度槽位下限（**Double** 字面量：`0.01f.toDouble()` = 0.009999999776…，会把槽位压到
 * 0.01 以下 —— 硬规格写的是 [0.01, 2]，就用精确的那个数）。
 */
private const val CONTRAST_SLOT_MIN = 0.01

/**
 * 对比度 UI 量程：显示 1%..200%（100% = 不变）。
 * **下限必须是 0.01 而不是 0**：`FilmParams::fromDoubles` 会把 [4] = 0 静默兜底成 1.0
 *（既存防"全画面同一灰度"逻辑）⇒ 滑杆若能拖到 0，用户看到的是"拖到底了画面却没变"
 *（ADR-12 的假滑杆）。
 */
internal val CONTRAST_UI_RANGE = CONTRAST_SLOT_MIN.toFloat()..2f

/** 饱和度 UI 量程：显示 0%..200%（100% = 不变）。 */
internal val SAT_UI_RANGE = 0f..2f

/** 清晰度 UI 量程：显示 0%..100%（0% = 关，引擎整段跳过）。 */
internal val CLARITY_UI_RANGE = 0f..1f

/** UI 曝光满量程对应的 EV 数（UI ±1.0 = ±3 EV）。 */
private const val EXP_UI_TO_EV = 3.0

/** UI 曝光（−1..1 = 显示 ±100%）→ 槽位 [0]（−3..+3 EV）。 */
internal fun expSlot(ui: Float): Double =
    ui.coerceIn(EXP_UI_RANGE.start, EXP_UI_RANGE.endInclusive) * EXP_UI_TO_EV

/** 槽位 [0]（−3..+3 EV）→ UI 曝光（−1..1）。坏数据（NaN/Inf）回落到恒等 0。 */
internal fun expUi(slot: Double): Float = if (!slot.isFinite()) {
    0f
} else {
    (slot / EXP_UI_TO_EV)
        .coerceIn(EXP_UI_RANGE.start.toDouble(), EXP_UI_RANGE.endInclusive.toDouble())
        .toFloat()
}

/** UI 白平衡增益（0.5..2 = 显示 50%..200%）→ 槽位 [1]/[2]/[3]。 */
internal fun wbGainSlot(ui: Float): Double =
    ui.coerceIn(WB_GAIN_UI_RANGE.start, WB_GAIN_UI_RANGE.endInclusive).toDouble()

/** 槽位 [1]/[2]/[3] → UI 白平衡增益（0.5..2）。坏数据回落到恒等 1.0（= 100%）。 */
internal fun wbGainUi(slot: Double): Float = if (!slot.isFinite()) {
    1f
} else {
    slot.coerceIn(WB_GAIN_UI_RANGE.start.toDouble(), WB_GAIN_UI_RANGE.endInclusive.toDouble())
        .toFloat()
}

/** UI 对比度（0.01..2 = 显示 1%..200%）→ 槽位 [4]。 */
internal fun contrastSlot(ui: Float): Double = ui.toDouble().coerceIn(CONTRAST_SLOT_MIN, 2.0)

/**
 * 槽位 [4] → UI 对比度（0.01..2）。
 * **0 与负数一律按 1.0（= 100% 不变）显示**：引擎的 `fromDoubles` 就是这么兜底的
 *（[4] ≤ 0 ⇒ 1.0），显示 0.01 会与画面实际行为不符（用户以为在极端低对比，其实没变）。
 */
internal fun contrastUi(slot: Double): Float = if (!slot.isFinite() || slot <= 0.0) {
    1f
} else {
    slot.coerceIn(CONTRAST_SLOT_MIN, 2.0).toFloat()
}

/** UI 饱和度（0..2 = 显示 0%..200%）→ 槽位 [20]/[21]/[22]（三槽同一个值）。 */
internal fun satSlot(ui: Float): Double =
    ui.coerceIn(SAT_UI_RANGE.start, SAT_UI_RANGE.endInclusive).toDouble()

/** 槽位 [21]（引擎读的那一档）→ UI 饱和度（0..2）。坏数据回落到恒等 1.0（= 100%）。 */
internal fun satUi(slot: Double): Float = if (!slot.isFinite()) {
    1f
} else {
    slot.coerceIn(SAT_UI_RANGE.start.toDouble(), SAT_UI_RANGE.endInclusive.toDouble()).toFloat()
}

/**
 * 一个饱和度滑杆**同写 [20][21][22] 三槽**（ADR §5.6④ 的硬约束）。
 *
 * 引擎当前只读 [21]，但将来改成按 luma 分区读取时，三槽不一致就会出现"滑杆只改了一档"
 * 的隐性错 —— 所以从 UI 侧一次性写满三槽。
 */
internal fun writeSaturation(p: DoubleArray, ui: Float) {
    val v = satSlot(ui)
    p[20] = v
    p[21] = v
    p[22] = v
}

/** UI 清晰度（0..1 = 显示 0%..100%）→ 槽位 [78]。 */
internal fun claritySlot(ui: Float): Double =
    ui.coerceIn(CLARITY_UI_RANGE.start, CLARITY_UI_RANGE.endInclusive).toDouble()

/** 槽位 [78] → UI 清晰度（0..1）。坏数据回落到恒等 0（= 关）。 */
internal fun clarityUi(slot: Double): Float = if (!slot.isFinite()) {
    0f
} else {
    slot.coerceIn(CLARITY_UI_RANGE.start.toDouble(), CLARITY_UI_RANGE.endInclusive.toDouble())
        .toFloat()
}

/**
 * 色温 / 色调 / 影调分区 / 褪色的 UI ↔ 槽位映射（E2-14）。槽位语义见 ADR 的「E2-11 槽位表」，
 * 引擎实现在 `FrameRenderer.cpp` 的 `tempTintGain`（[88][89]）与 `kFsStyle` 的
 * `uToneOn` / `uFade` 段（[90..94]）。
 *
 *  - [88] `temp_mired`：**目标白点的 mired 偏移**（基准 6500K），恒等 0
 *  - [89] `tint`：绿 ↔ 品红（正 = 品红），恒等 0
 *  - [90..93] `highlights` / `shadows` / `whites` / `blacks`：显示域影调分区（乘性 luma 增益），恒等 0
 *  - [94] `fade`：褪色（抬黑场、白点不动），恒等 0 = 关
 *
 * **⚠ 方向约定（本卡最容易做错的一条）**：`temp_mired` 存的是目标白点的 mired
 * （`mired_target = 1e6/6500 + 槽位值`、`K_target = 1e6/mired_target`）⇒ **槽位值正 =
 * 目标 K 更低 = 画面变冷**。所以 **UI 滑杆直接用 K**（右移 = K 大 = **变暖**），写入时才换算成
 * mired。若把 mired 当滑杆值，滑杆方向与读数方向正好相反。
 *
 * 量程常量由 UI 参数表与映射函数**共用同一份定义**（否则滑杆能拖到 clamp 之外 = 假滑杆）。
 */

/** 色温 UI 量程（K）：2500..12000，恒等 6500。下限 2500K 的依据见 ADR（更低时引擎 B 增益撞 3.0 clamp）。 */
internal val TEMP_UI_RANGE = 2500f..12000f

/** 色温恒等值 / 中点标记位置：6500K ≈ D65（引擎 `tempTintGain` 的参考白点 `k_ref`）。 */
internal const val TEMP_UI_PIVOT = 6500f

/** 色调 UI 量程：−0.5..+0.5（引擎侧 clamp ±1，UI 取 ADR 的建议范围）。恒等 0。 */
internal val TINT_UI_RANGE = -0.5f..0.5f

/** 影调分区（高光 / 阴影 / 白色色阶 / 黑色色阶）UI 量程：−1..+1 直通槽位。恒等 0。 */
internal val TONE_UI_RANGE = -1f..1f

/** 褪色 UI 量程：0..1（0 = 关，引擎 `uFade > 0` 才生效）。恒等/基准在最左端 ⇒ 不画中点标记。 */
internal val FADE_UI_RANGE = 0f..1f

/** 色调 / 影调分区 / 褪色共用的恒等值 = 中点标记位置（0）。 */
internal const val ZERO_UI_PIVOT = 0f

/** 参考白点 6500K（引擎 `k_ref`）。 */
private const val TEMP_REF_K = 6500.0

/**
 * 参考白点的 mired = 1e6/6500。**写成表达式而不是 `153.846` 字面量**：`tempSlot(6500f)` 必须
 * 精确得 0 —— 差 1.5e-4 就超过 ADR-13 的 `PARAM_EPS = 1e-4`，会把"滑杆停在 6500K"误判成
 * 用户修改（白渲染一次全分辨率）。
 */
private const val TEMP_REF_MIRED = 1e6 / TEMP_REF_K

/** 色温越界容差（K）：只吸收 1e-10 量级的浮点噪声，远小于滑杆的最小可辨步长。 */
private const val TEMP_K_EPS = 0.5

/** UI 色温（K）→ 槽位 [88]（mired 偏移；6500K ⇒ 0）。越界 K 夹到 2500..12000。 */
internal fun tempSlot(k: Float): Double {
    val kv = if (k.isFinite()) {
        k.coerceIn(TEMP_UI_RANGE.start, TEMP_UI_RANGE.endInclusive).toDouble()
    } else {
        TEMP_REF_K
    }
    return 1e6 / kv - TEMP_REF_MIRED
}

/**
 * 槽位 [88] → UI 色温（K）：`K = 1e6/(mired_ref + slot)`，夹紧 2500..12000。
 *
 * **坏数据兜底**：槽位非有限，或换出的 K 越出 UI 量程（含 `mired_ref + slot ≤ 0` 这种数学上
 * 失效的槽位）⇒ 回落恒等读数 **6500K**。
 *
 * 为什么越界槽位不夹到端点：夹到端点会让"画面明显偏冷"配上"6500K 不变"的读数（假读数），
 * 而越界槽位只可能来自坏快照（UI 永远写不出）。恒等值是最不容易误导用户的显示。
 */
internal fun tempUi(slot: Double): Float {
    if (!slot.isFinite()) return TEMP_UI_PIVOT
    val k = 1e6 / (TEMP_REF_MIRED + slot)   // mired + slot ≤ 0 ⇒ Inf / 负值，由下面的越界判定兜底
    val lo = TEMP_UI_RANGE.start.toDouble()
    val hi = TEMP_UI_RANGE.endInclusive.toDouble()
    if (!k.isFinite() || k < lo - TEMP_K_EPS || k > hi + TEMP_K_EPS) return TEMP_UI_PIVOT
    return k.coerceIn(lo, hi).toFloat()
}

/** UI 色调（−0.5..+0.5）→ 槽位 [89]。坏数据回落恒等 0。 */
internal fun tintSlot(ui: Float): Double = if (ui.isFinite()) {
    ui.coerceIn(TINT_UI_RANGE.start, TINT_UI_RANGE.endInclusive).toDouble()
} else {
    0.0
}

/** 槽位 [89] → UI 色调（−0.5..+0.5）。坏数据回落恒等 0。 */
internal fun tintUi(slot: Double): Float = if (!slot.isFinite()) {
    0f
} else {
    slot.coerceIn(TINT_UI_RANGE.start.toDouble(), TINT_UI_RANGE.endInclusive.toDouble()).toFloat()
}

/** UI 影调分区（−1..+1）→ 槽位 [90..93]（高光 / 阴影 / 白色色阶 / 黑色色阶四条共用这一对函数）。 */
internal fun toneSlot(ui: Float): Double = if (ui.isFinite()) {
    ui.coerceIn(TONE_UI_RANGE.start, TONE_UI_RANGE.endInclusive).toDouble()
} else {
    0.0
}

/** 槽位 [90..93] → UI 影调分区（−1..+1）。坏数据回落恒等 0。 */
internal fun toneUi(slot: Double): Float = if (!slot.isFinite()) {
    0f
} else {
    slot.coerceIn(TONE_UI_RANGE.start.toDouble(), TONE_UI_RANGE.endInclusive.toDouble()).toFloat()
}

/** UI 褪色（0..1）→ 槽位 [94]。坏数据回落恒等 0 = 关。 */
internal fun fadeSlot(ui: Float): Double = if (ui.isFinite()) {
    ui.coerceIn(FADE_UI_RANGE.start, FADE_UI_RANGE.endInclusive).toDouble()
} else {
    0.0
}

/** 槽位 [94] → UI 褪色（0..1）。**负值当"关"**（引擎 `uFade > 0` 才生效，负值不产生任何画面变化）。 */
internal fun fadeUi(slot: Double): Float = if (!slot.isFinite()) {
    0f
} else {
    slot.coerceIn(FADE_UI_RANGE.start.toDouble(), FADE_UI_RANGE.endInclusive.toDouble()).toFloat()
}

/** 色温读数：`5000K`（整数 K，无空格）。坏数据回落恒等读数 `6500K`。 */
internal fun formatKelvin(ui: Float): String {
    val k = if (ui.isFinite()) {
        ui.coerceIn(TEMP_UI_RANGE.start, TEMP_UI_RANGE.endInclusive).roundToInt()
    } else {
        TEMP_UI_PIVOT.roundToInt()
    }
    return "${k}K"
}

/**
 * 读数格式化（E2-10）。**纯函数、无 Android 依赖** ⇒ JVM 单测逐条钉住（每个参数的格式 +
 * 中点值 + 边界 + 坏数据）。
 *
 * 为什么单独抽出来：ADR-14 的差距第 3 条 —— "读数恒为 `%`（曝光显示 `100%` 而不是
 * `+1.0 EV`）**这条最直接决定"专业感"**"。旧实现把读数写死在滑杆组件里（`v * 100%`），
 * 既改不动也测不了；现在每个参数的读数格式是参数表里的一格（见 `EditorScreen.kt`）。
 *
 * 口径（全卡统一，研究 §3.3.3）：
 *  - **有物理单位的一律用物理单位**（曝光 → `EV`，方向 → `°`）
 *  - **相对量用 ±100 整数**（对比度 / 饱和度 / 白平衡增益；0 = 不变）
 *  - **无符号量用 0..100 / 0..200 整数**（清晰度 / 暗角强度 / 半径 / 羽化 / LUT 强度）
 *  - **不再出现 `%`**（百分比是"设置页"的语言，不是"调色台"的语言）
 *
 * 坏数据（NaN/Inf）一律回落到恒等读数（`0.0 EV` / `0` / `0°`）：读数绝不能显示 `NaN`。
 */

/** 一位小数（无 Locale 依赖、无 `String.format` 的分配开销）：`1.5` / `-0.5` / `0.0` */
private fun oneDecimal(v: Float): String {
    val n = (v * 10f).roundToInt()
    val a = abs(n)
    return "${if (n < 0) "-" else ""}${a / 10}.${a % 10}"
}

/**
 * 曝光读数：UI（−1..1 = ±3 EV）→ `+1.0 EV` / `0.0 EV` / `-1.5 EV`。
 *
 * 零值不带正号；先四舍五入到一位小数再判零 ⇒ `-0.04 EV` 显示 `0.0 EV` 而不是 `-0.0 EV`。
 */
internal fun formatEv(ui: Float): String {
    val ev = if (ui.isFinite()) (ui * 3f).coerceIn(-3f, 3f) else 0f
    val r = (ev * 10f).roundToInt() / 10f
    val s = oneDecimal(r)
    return if (r > 0f) "+$s EV" else "$s EV"
}

/**
 * 相对量读数：相对 [pivot] 的 ±100 整数偏移 → `+12` / `0` / `-34`。
 *
 * 适用：对比度 / 饱和度 / 白平衡增益（[pivot] = 1.0 = 不变）。量程不对称时（对比度
 * 0.01..2.0）负数侧到不了 −100 —— 如实显示，不硬凑对称。
 */
internal fun formatOffset100(ui: Float, pivot: Float = 1f): String {
    val v = if (ui.isFinite() && pivot.isFinite()) {
        ((ui - pivot) * 100f).roundToInt().coerceIn(-100, 100)
    } else {
        0
    }
    return if (v > 0) "+$v" else "$v"
}

/**
 * 0..100 整数读数（清晰度 / 暗角强度 / LUT 强度 / 局部半径·羽化·宽度）：UI 0..1 × 100。
 * 这是"清晰度"的口径（研究 §3.3.3 的目标显示就是 `0..100` 无符号整数）。
 */
internal fun formatZero100(ui: Float): String =
    if (ui.isFinite()) (ui * 100f).roundToInt().coerceIn(0, 100).toString() else "0"

/** 0..200 整数读数（暗角半径 / 羽化）：UI 0..2 × 100 —— darktable 的 `scale` 本身就是 0..200 语义。 */
internal fun formatZero200(ui: Float): String =
    if (ui.isFinite()) (ui * 100f).roundToInt().coerceIn(0, 200).toString() else "0"

/** 角度读数（局部调整的条带法线方向）：UI 0..1 = 0..180° → `90°`。 */
internal fun formatDegree(ui: Float): String =
    if (ui.isFinite()) "${(ui * 180f).roundToInt().coerceIn(0, 180)}°" else "0°"

/**
 * 基准值（"不变点" / 默认值）在滑杆上的位置（0..1）。返回 null = **不画中点标记**：
 *  - 基准落在量程**端点**上（清晰度 0 / 暗角强度 0 / LUT 强度 0 都在最左端）—— 端点本身就是标记
 *  - 基准在量程外，或量程退化（span ≤ 0）/ 非有限（坏数据）
 *
 * 这是"三条关键修正"第 2 条（中点标记）的**唯一**位置计算：滑杆绘制侧（`RelativeSlider`
 * 只接收算好的 fraction）与单测共用同一份 —— 两处各算一次，必然出现"标记画在 0 处、
 * 读数却是 +12"的错位。
 */
internal fun pivotFraction(range: ClosedFloatingPointRange<Float>, pivot: Float): Float? {
    val span = range.endInclusive - range.start
    if (!span.isFinite() || span <= 0f || !pivot.isFinite()) return null
    val f = (pivot - range.start) / span
    return if (f > 0f && f < 1f) f else null
}

/**
 * 「调整」组重置（E2-3；E2-14 扩了范围）：本组全部参数回**恒等值** —— 曝光 0 / WB 1,1,1 /
 * 对比度 1 / 饱和度 1,1,1 / 清晰度 0 / 高光·阴影·白·黑色阶 0。只动这 11 个槽位，其余槽位
 * （快照继承来的风格参数、色温/色调/褪色/暗角/LUT）原样保留。
 */
internal fun resetBasicParams(p: DoubleArray) {
    p[0] = 0.0
    p[1] = 1.0
    p[2] = 1.0
    p[3] = 1.0
    p[4] = 1.0
    writeSaturation(p, 1f)
    p[78] = 0.0
    p[90] = 0.0
    p[91] = 0.0
    p[92] = 0.0
    p[93] = 0.0
}

/**
 * 「色彩」组的**引擎参数**重置（E2-14）：色温 [88] / 色调 [89] / 褪色 [94] 回恒等值。
 *
 * LUT 不在这里 —— 清 [34] 与卸载纹理是 `applyLut(null, 0f)` 的职责（两者在
 * `EditorScreen.resetColor` 里合并成**一次**渲染，避免画面白闪一下）。
 */
internal fun resetColorParams(p: DoubleArray) {
    p[88] = 0.0
    p[89] = 0.0
    p[94] = 0.0
}

/**
 * 局部调整的 UI ↔ 槽位映射 + **预览坐标换算**（E2-7）。与上面两组同一范式：纯逻辑、
 * 无 Android / Compose 依赖 ⇒ JVM 单测直接覆盖往返、letterbox 偏移与边界夹紧。
 *
 * 槽位语义（E2-6 已实现并独立验收，见 ADR「E2-7 必读的槽位与语义」表）：
 *  - [79] local_type  0 = 关 / 1 = 径向 / 2 = 线性
 *  - [80][81] 中心（归一化图像坐标，**顶行 = 0**）
 *  - [82] 半径（UV，**正圆语义**：x 方向 = 相对图像宽，y 方向 = 相对图像高）
 *  - [83] 羽化（相对半径）、[84] 线性法线角（弧度）、[85] 线性半宽（UV）
 *  - [86] 局部曝光 EV（恒等 **0**）、[87] 局部饱和度（恒等 **1**）
 *
 * 为什么坐标换算必须是纯函数：**轮廓绘制与拖动定位必须共用同一套换算** —— 两处各写一份，
 * 一旦有偏差，用户看到的是"拖了没对准"（轮廓中心与真实 mask 中心不重合）。放在这里也
 * 让 letterbox 偏移、边界夹紧这些最容易错的地方能被单测逐条钉住。
 */

/** 局部调整模式（UI 枚举 = [79] 的三个取值）。 */
internal object LocalMode {
    const val OFF = 0
    const val RADIAL = 1
    const val LINEAR = 2
}

/** UI 模式（0/1/2）→ 槽位 [79]。越界/坏数据回落到 0 = 关。 */
internal fun localTypeSlot(ui: Int): Double =
    if (ui == LocalMode.RADIAL || ui == LocalMode.LINEAR) ui.toDouble() else 0.0

/**
 * 槽位 [79] → UI 模式。**只有 [0.5, 2.5) 才认**：快照里 2.7 / −1 / NaN 这类坏数据一律当"关"，
 * 否则会显示一个引擎侧根本不存在的模式（引擎判据是 `>= 1.5` = 线性、`[0.5,1.5)` = 径向）。
 */
internal fun localTypeUi(slot: Double): Int = when {
    !slot.isFinite() -> LocalMode.OFF
    slot >= 0.5 && slot < 1.5 -> LocalMode.RADIAL
    slot >= 1.5 && slot < 2.5 -> LocalMode.LINEAR
    else -> LocalMode.OFF
}

/** 槽位 → 模式名（诊断日志用；UI 文案见 EditorScreen）。 */
internal fun localTypeName(slot: Double): String = when (localTypeUi(slot)) {
    LocalMode.RADIAL -> "radial"
    LocalMode.LINEAR -> "linear"
    else -> "off"
}

/** 归一化中心（0..1，顶行 = 0）→ 槽位 [80]/[81]。坏数据（NaN/Inf）回落到画面中心。 */
internal fun localCenterSlot(uiX: Float, uiY: Float): Pair<Double, Double> =
    (if (uiX.isFinite()) uiX.coerceIn(0f, 1f).toDouble() else 0.5) to
        (if (uiY.isFinite()) uiY.coerceIn(0f, 1f).toDouble() else 0.5)

/** 槽位 [80]/[81] → 归一化中心（0..1）。坏数据/越界回落到 0.5（= 画面中心）。 */
internal fun localCenterUi(slotX: Double, slotY: Double): Pair<Float, Float> =
    (if (slotX.isFinite()) slotX.coerceIn(0.0, 1.0).toFloat() else 0.5f) to
        (if (slotY.isFinite()) slotY.coerceIn(0.0, 1.0).toFloat() else 0.5f)

/** 半径 UI 量程（**UV 单位 = 图像宽/高的比例**，恒等/默认 0.35；见 [radialOutline] 的正圆语义）。 */
internal val LOCAL_RADIUS_UI_RANGE = 0f..1f

/** 羽化 UI 量程：0 = 极窄过渡（引擎侧 `max(_,1e-3)` 兜底），1 = 与半径等宽。 */
internal val LOCAL_FEATHER_UI_RANGE = 0f..1f

/** 角度 UI 量程：显示 0%..100% = 0..π 弧度（法线方向；π 与 0 是同一条轴，不必到 2π）。 */
internal val LOCAL_ANGLE_UI_RANGE = 0f..1f

/** 线性半宽 UI 量程（UV 单位，默认 0.25）。 */
internal val LOCAL_WIDTH_UI_RANGE = 0f..1f

/** 局部曝光 UI 量程：显示 −100%..+100% = ±3 EV（0% = 不变，与全局曝光同一口径）。 */
internal val LOCAL_EV_UI_RANGE = -1f..1f

/** 局部饱和度 UI 量程：显示 0%..200%（100% = 不变）。 */
internal val LOCAL_SAT_UI_RANGE = 0f..2f

/** UI 半径（0..1，相对图像宽）→ 槽位 [82]。 */
internal fun localRadiusSlot(ui: Float): Double =
    ui.coerceIn(LOCAL_RADIUS_UI_RANGE.start, LOCAL_RADIUS_UI_RANGE.endInclusive).toDouble()

/** 槽位 [82] → UI 半径（0..1）。坏数据回落到默认 0.35（= `CameraPresets` 的占位值）。 */
internal fun localRadiusUi(slot: Double): Float =
    if (!slot.isFinite()) 0.35f else {
        slot.coerceIn(LOCAL_RADIUS_UI_RANGE.start.toDouble(), LOCAL_RADIUS_UI_RANGE.endInclusive.toDouble())
            .toFloat()
    }

/** UI 羽化（0..1）→ 槽位 [83]。 */
internal fun localFeatherSlot(ui: Float): Double =
    ui.coerceIn(LOCAL_FEATHER_UI_RANGE.start, LOCAL_FEATHER_UI_RANGE.endInclusive).toDouble()

/** 槽位 [83] → UI 羽化（0..1）。坏数据回落到 0.5。 */
internal fun localFeatherUi(slot: Double): Float =
    if (!slot.isFinite()) 0.5f else {
        slot.coerceIn(LOCAL_FEATHER_UI_RANGE.start.toDouble(), LOCAL_FEATHER_UI_RANGE.endInclusive.toDouble())
            .toFloat()
    }

/** UI 角度（0..1 = 显示 0..100%）→ 槽位 [84]（0..π 弧度）。 */
internal fun localAngleSlot(ui: Float): Double =
    ui.coerceIn(LOCAL_ANGLE_UI_RANGE.start, LOCAL_ANGLE_UI_RANGE.endInclusive).toDouble() * Math.PI

/** 槽位 [84]（弧度）→ UI 角度（0..1）。坏数据回落到 0（= 条带竖直）。 */
internal fun localAngleUi(slot: Double): Float =
    if (!slot.isFinite()) 0f else {
        (slot / Math.PI)
            .coerceIn(LOCAL_ANGLE_UI_RANGE.start.toDouble(), LOCAL_ANGLE_UI_RANGE.endInclusive.toDouble())
            .toFloat()
    }

/** UI 线性半宽（0..1）→ 槽位 [85]。 */
internal fun localWidthSlot(ui: Float): Double =
    ui.coerceIn(LOCAL_WIDTH_UI_RANGE.start, LOCAL_WIDTH_UI_RANGE.endInclusive).toDouble()

/** 槽位 [85] → UI 线性半宽（0..1）。坏数据回落到 0.25。 */
internal fun localWidthUi(slot: Double): Float =
    if (!slot.isFinite()) 0.25f else {
        slot.coerceIn(LOCAL_WIDTH_UI_RANGE.start.toDouble(), LOCAL_WIDTH_UI_RANGE.endInclusive.toDouble())
            .toFloat()
    }

/** UI 局部曝光（−1..1 = 显示 ±100%）→ 槽位 [86]（−3..+3 EV，与全局曝光同一换算）。 */
internal fun localEvSlot(ui: Float): Double = expSlot(ui)

/** 槽位 [86] → UI 局部曝光（−1..1）。坏数据回落到恒等 0。 */
internal fun localEvUi(slot: Double): Float = expUi(slot)

/** UI 局部饱和度（0..2 = 显示 0%..200%）→ 槽位 [87]。 */
internal fun localSatSlot(ui: Float): Double =
    ui.coerceIn(LOCAL_SAT_UI_RANGE.start, LOCAL_SAT_UI_RANGE.endInclusive).toDouble()

/** 槽位 [87] → UI 局部饱和度（0..2）。坏数据回落到恒等 1.0（= 100%）。 */
internal fun localSatUi(slot: Double): Float =
    if (!slot.isFinite()) 1f else {
        slot.coerceIn(LOCAL_SAT_UI_RANGE.start.toDouble(), LOCAL_SAT_UI_RANGE.endInclusive.toDouble())
            .toFloat()
    }

/**
 * 局部调整是否**生效**（= 引擎侧 `uLocalOn` 的判据，ADR 交互规格第 6 条）：
 * `[79] ≥ 0.5`（选了类型）**且**（`[86] ≠ 0` 或 `[87] ≠ 1`）（调过强度）。
 *
 * UI 用它给出"选了类型但没调强度 ⇒ 画面不会变"的提示 —— 与引擎同一判据，两处不会打架。
 */
internal fun localActive(p: DoubleArray): Boolean =
    localTypeUi(p[79]) != LocalMode.OFF && (p[86] != 0.0 || p[87] != 1.0)

/**
 * 「局部」组重置：中心回画面正中、半径/羽化/角度/宽度回默认，**模式与强度不动**。
 *
 * 为什么不把 [79]/[86]/[87] 也清零：用户点的是"把这块区域挪回正中/恢复默认大小"，不是
 * "关掉局部调整" —— 强度清零会让画面**突然变回去**（用户会以为重置把编辑弄丢了）。
 * 关掉局部调整的入口是模式选「关」。
 */
internal fun resetLocalGeometry(p: DoubleArray) {
    p[80] = 0.5
    p[81] = 0.5
    p[82] = 0.35
    p[83] = 0.5
    p[84] = 0.0
    p[85] = 0.25
}

/**
 * 预览显示盒内的**图像矩形**（Fit 缩放后的位置与尺寸，像素）—— letterbox 黑边的唯一来源。
 *
 * 预览用 `ContentScale.Fit`：等比缩放到**装得下**（`scale = min(boxW/imgW, boxH/imgH)`），
 * 剩余空间均分成左右/上下黑边。屏幕坐标 → 图像归一化坐标必须**先减这个偏移、再除以
 * 显示尺寸**，否则用户在竖拍图上拖动会偏（黑边越宽偏得越多）。
 */
internal data class ImageFitRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** [ImageFitRect] 的纯计算（Fit 语义 + 非方形画幅 + 尺寸退化保护）。 */
internal fun imageFitRect(
    boxW: Float,
    boxH: Float,
    imgW: Int,
    imgH: Int,
): ImageFitRect {
    if (boxW <= 0f || boxH <= 0f || imgW <= 0 || imgH <= 0) {
        return ImageFitRect(0f, 0f, maxOf(boxW, 0f), maxOf(boxH, 0f))
    }
    val scale = minOf(boxW / imgW, boxH / imgH)
    val w = imgW * scale
    val h = imgH * scale
    return ImageFitRect((boxW - w) * 0.5f, (boxH - h) * 0.5f, w, h)
}

/**
 * 屏幕坐标（预览盒内，px）→ **归一化图像坐标**（0..1，**顶行 = 0**，与槽位 [80]/[81] 同口径）。
 * 结果夹紧到 0..1：拖到黑边上（letterbox 区域）不能让中心跑出画面（引擎的 mask 会整块消失，
 * 用户以为"拖没了"）。
 */
internal fun localCenterFromScreen(
    xPx: Float,
    yPx: Float,
    rect: ImageFitRect,
): Pair<Float, Float> {
    if (rect.width <= 0f || rect.height <= 0f) return 0.5f to 0.5f
    val u = ((xPx - rect.left) / rect.width).coerceIn(0f, 1f)
    val v = ((yPx - rect.top) / rect.height).coerceIn(0f, 1f)
    return u to v
}

/** [localCenterFromScreen] 的逆：归一化图像坐标 → 屏幕坐标（轮廓绘制用**同一套**换算）。 */
internal fun localCenterToScreen(
    u: Float,
    v: Float,
    rect: ImageFitRect,
): Pair<Float, Float> =
    (rect.left + u.coerceIn(0f, 1f) * rect.width) to
        (rect.top + v.coerceIn(0f, 1f) * rect.height)

/**
 * 辅助轮廓的几何（屏幕 px，`0` 号点 = 中心）。**轮廓只画在预览上，绝不进导出** ——
 * 导出走的是引擎输出位图，这里的东西是 Compose 叠加层（结构性保证，不是"记得别画"）。
 *
 * 两种模式共用同一个类：径向 = [rx]/[ry]（椭圆半轴，正圆修正后 rx = ry×W/H）；
 * 线性 = 条带的两条边线（[lineDx]/[lineDy] 为**单位**方向，边线长 2×[half]）。
 */
internal data class LocalOutline(
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float,
    val feather: Float,
    val linear: Boolean,
    val lineDx: Float,
    val lineDy: Float,
    val half: Float,
)

/**
 * 径向轮廓：**正圆语义**（E2-7 裁决 1）—— 半径以**图像宽度**为单位（x 方向 = [LOCAL_RADIUS_UI_RANGE]），
 * 像素空间的半轴是 `rx = r×W`（水平）、`ry = r×W×W/H`（垂直）⇒ 屏幕上是一个**正圆**
 *（`ImageFitRect` 是等比缩放，画出来仍正圆；竖拍 3:4 不再变椭圆）。
 *
 * 与 shader 的对应（同一套换算的两种表述）：引擎下发 `uLocalRadius = (r, r×W/H)`，
 * 于是 `length((u−cu)/r, (v−cv)/(r×W/H))` 在像素空间等距 —— 也就是这里的 `rx/ry`。
 * **注意 ry 是"乘 W/H"而不是"H/W"**：像素坐标 P = (u×W, v×H) ⇒ 要 `d = |P|/R` 需
 * `rx×W = ry×H = R`。写反了轮廓会与真实 mask 不重合（E2-7 实测踩过：垂直半径大 2.25 倍）。
 *
 * @param radiusUi 槽位 [82]（UV，相对图像宽）
 * @param featherUi 槽位 [83]（相对半径）
 * @param aspect 图像**高/宽**（像素，= H/W）—— 正圆修正的唯一输入
 */
internal fun radialOutline(
    centerU: Float,
    centerV: Float,
    radiusUi: Float,
    featherUi: Float,
    rect: ImageFitRect,
    aspect: Float,
): LocalOutline {
    val (cx, cy) = localCenterToScreen(centerU, centerV, rect)
    val r = radiusUi.coerceIn(0f, 1f)
    // rx 直接用显示宽（= 图像宽的等比缩放）⇒ 不必再乘 scale；ry 由**像素**宽高比换算，
    // 这样"正圆"这件事与显示尺寸无关（转屏/换机型都不会变成椭圆）
    val rx = r * rect.width
    val ry = if (aspect.isFinite() && aspect > 0f) rx / aspect else rx
    return LocalOutline(
        cx = cx, cy = cy, rx = rx, ry = ry,
        feather = featherUi.coerceIn(0f, 1f),
        linear = false, lineDx = 0f, lineDy = 0f, half = 0f,
    )
}

/**
 * 线性轮廓：条带的两条边线。`dir = (cos a, sin a)` 是**法线**（[84] 的语义，ADR 表：
 * 0 ⇒ 条带竖直）⇒ 边线方向与之垂直 = `(−sin a, cos a)`，两条边线分别在中心沿法线
 * 偏移 ±half（像素）= ±`width×W`。
 *
 * 与 shader 的对应：`d = |dot(p, dir)| / (width×W)`（像素空间），边线在 `d = 1`
 * （= 硬边位置）；羽化带再向外 `feather` 倍，轮廓用第二条虚线示意。
 *
 * @param widthUi 槽位 [85]（UV，相对图像宽）
 * @param angleSlot 槽位 [84]（弧度，条带法线方向）
 */
internal fun linearOutline(
    centerU: Float,
    centerV: Float,
    widthUi: Float,
    angleSlot: Double,
    featherUi: Float,
    rect: ImageFitRect,
): LocalOutline {
    val (cx, cy) = localCenterToScreen(centerU, centerV, rect)
    val a = if (angleSlot.isFinite()) angleSlot else 0.0
    val half = widthUi.coerceIn(0f, 1f) * rect.width
    // 屏幕方向 = 图像方向（Fit 是等比缩放 ⇒ 角度不变）；像素宽高比在**归一化空间**里
    // 才会改变角度，而这里已经换算到像素/屏幕空间了，所以直接用 (cos a, sin a)
    val ndx = kotlin.math.cos(a).toFloat()
    val ndy = kotlin.math.sin(a).toFloat()
    return LocalOutline(
        cx = cx, cy = cy, rx = 0f, ry = 0f,
        feather = featherUi.coerceIn(0f, 1f),
        linear = true, lineDx = -ndy, lineDy = ndx, half = half,
    )
}

/** 另存文件名：`FILMKIT_20261008-213000_edit.jpg`（同拍摄链路的命名规则，`_edit` 区分原片） */
internal fun editorExportName(timeMs: Long): String =
    "FILMKIT_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timeMs))}_edit.jpg"

/**
 * 编码成片 JPEG。与拍摄链路同口径：native **4:4:4** 优先（4:2:0 的色度下采样会在强
 * 亮度边缘两侧串色），native 不可用/失败时回退 [Bitmap.compress]（宁可退到 4:2:0，
 * 不可丢成片）。
 */
internal fun encodeEditorJpeg(bmp: Bitmap, quality: Int): ByteArray? {
    runCatching { EngineBridge.nativeEncodeJpeg444(bmp, quality) }.getOrNull()?.let {
        if (it.isNotEmpty()) return it
    }
    return runCatching {
        ByteArrayOutputStream().also { bos ->
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)) return null
        }.toByteArray()
    }.getOrNull()
}

/**
 * 另存为新片（`Pictures/FilmKit`，MediaStore `image/jpeg`）。
 *
 * 与原片完全无关：新建条目 + `IS_PENDING` 写完再放开可见（写一半被系统杀进程也不会
 * 在相册里留下半张图）。失败只记日志并返回 null —— 用户的原片不受任何影响。
 *
 * [rewriteExif] = false（E2-9 直通路径）：**一个字节都不动**。此时 [jpeg] 就是原片字节，
 * 拍摄参数（ISO/机型/光圈/拍摄时间）本来就在里面 —— 再走一遍 [writeEditorExif] 反而会
 * 把拍摄时间改成"导出时间"、把方向硬写成 1，比原样保留更差。渲染路径仍必须重写：
 * 像素变了、方向在渲染端已转正，旧 EXIF 不再描述这张图。
 */
internal fun saveEditorCopy(
    context: Context, jpeg: ByteArray, name: String, timeMs: Long, rewriteExif: Boolean = true,
): Uri? {
    var inserted: Uri? = null
    try {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilmKit")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            inserted = uri
            context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: return null
            cv.clear()
            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, cv, null, null)
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FilmKit")
            dir.mkdirs()
            val f = File(dir, name)
            f.outputStream().use { it.write(jpeg) }
            inserted = Uri.fromFile(f)
        }
        if (rewriteExif) writeEditorExif(context, inserted, timeMs, null)
        return inserted
    } catch (t: Throwable) {
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_ERROR, "camera", "photo.edit_save_failed",
            kv("name" to name, "error" to (t.message ?: t.toString())),
        )
        // 写失败时把刚插入的 pending 条目删掉，别在相册里留垃圾（删除失败也无所谓）
        inserted?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        return null
    }
}

/** 覆盖原片的结果：三种状态对应三句**必须不同**的用户文案（不许糊成一句"失败"） */
internal sealed interface OverwriteResult {
    /** 已覆盖成功 */
    data object Ok : OverwriteResult

    /** 覆盖失败，**原片完好**（没写进去，或写坏了但已用备份回写成功） */
    data object FailedOriginalIntact : OverwriteResult

    /** 覆盖失败且**原片可能已损坏**（必须如实告知用户，不能假装没事） */
    data object FailedOriginalDamaged : OverwriteResult
}

/**
 * 覆盖原片（危险操作，ADR-3）。**安全顺序**（本函数的全部意义所在）：
 *  1. 调用方已经拿到"渲染成功 + 编码成功"的 JPEG 字节 —— 本函数只负责写；
 *  2. 写之前先把原片字节读进内存做**备份**（成片 JPEG 通常 3~8MB）；备份失败 = 直接
 *     放弃覆盖（拿不到退路就不开这一枪），原片必然完好；
 *  3. `openOutputStream(uri, "wt")` 覆盖写；
 *  4. 任何异常 → 用备份回写原片，并**如实回报**恢复是否成功。
 *
 * [backup] 是调用方**已经读出来**的原片字节（覆盖成功后要拿它拷 EXIF，所以无论如何
 * 都得在覆盖前读一次）；传 null 时本函数自己读一次 —— 同一个文件不为同一件事读两遍。
 *
 * 为什么不用 `IS_PENDING`：它是"**新插入**条目的可见性开关"，对已存在条目没有原子
 * 替换语义；MediaStore 也不提供原地原子替换。`"wt"`（truncate）是唯一能改写同一 URI
 * 内容的模式 —— 所以原子性只能靠"先渲染编码成功 + 写前备份 + 失败回滚"来兜，
 * 绝不允许"先删后写/先截断再想办法"。
 */
internal fun overwriteOriginalPhoto(
    context: Context, uri: Uri, jpeg: ByteArray, backup: ByteArray?,
): OverwriteResult {
    val bak = backup ?: runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()
    if (bak == null || bak.isEmpty()) {
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_WARN, "camera", "photo.edit_overwrite_backup_failed", "{}",
        )
        return OverwriteResult.FailedOriginalIntact // 一个字节都没写 = 原片必然完好
    }

    val wrote = runCatching {
        val os = context.contentResolver.openOutputStream(uri, "wt")
        if (os == null) false else {
            os.use { it.write(jpeg) }
            true
        }
    }.getOrDefault(false)
    if (wrote) return OverwriteResult.Ok

    // 写失败：回写备份（尽力恢复原片）
    val restored = runCatching {
        val os = context.contentResolver.openOutputStream(uri, "wt")
        if (os == null) false else {
            os.use { it.write(bak) }
            true
        }
    }.getOrDefault(false)
    EngineBridge.nativeAppendLog(
        if (restored) EngineBridge.LEVEL_WARN else EngineBridge.LEVEL_ERROR,
        "camera", "photo.edit_overwrite_failed",
        kv("restored" to restored, "backupBytes" to bak.size),
    )
    return if (restored) OverwriteResult.FailedOriginalIntact else OverwriteResult.FailedOriginalDamaged
}

/** 覆盖成功后刷新的元数据：时间 + 方向（像素已转正 ⇒ 恒为 1） */
internal fun refreshOverwrittenExif(context: Context, uri: Uri, timeMs: Long, srcJpeg: ByteArray?) {
    writeEditorExif(context, uri, timeMs, srcJpeg)
}

/**
 * 补写 EXIF：时间 + `Orientation=1`（成片像素在渲染端已转正，方向恒为标准值）。
 * [srcJpeg] 非空时（覆盖原片）把**原片的拍摄信息**一并带过来 —— 覆盖只该改像素，
 * 不该让用户丢掉 ISO/机型/光圈这些拍摄记录。
 */
private fun writeEditorExif(context: Context, uri: Uri?, timeMs: Long, srcJpeg: ByteArray?) {
    if (uri == null) return
    runCatching {
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: return
        val exif = ExifInterface(pfd.fileDescriptor)
        val dt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(timeMs))
        exif.setAttribute(ExifInterface.TAG_DATETIME, dt)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dt)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
        if (srcJpeg != null) {
            // 逐个 tag 拷贝：单个字段格式不被 ExifInterface 接受时只丢那一个（runCatching），
            // 不影响其它字段与整张图。ExposureTime 这类"兼容标签"必须是十进制秒数字符串，
            // 从源 EXIF 读出来的可能是 "1/125" ⇒ setAttribute 内部 parse 失败静默丢弃，
            // 属可接受损失（不为了一个字段把整段拷贝搞崩）。
            val src = runCatching {
                ExifInterface(java.io.ByteArrayInputStream(srcJpeg))
            }.getOrNull()
            src?.let { s ->
                KEEP_EXIF_TAGS.forEach { tag ->
                    runCatching {
                        s.getAttribute(tag)?.let { v -> exif.setAttribute(tag, v) }
                    }
                }
                // 方向必须是我们写的 1（上面的循环可能把原方向又拷回来）
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
            }
        }
        exif.saveAttributes()
    }.onFailure {
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_WARN, "camera", "photo.edit_exif_failed",
            kv("error" to (it.message ?: it.toString())),
        )
    }
}

/** 覆盖时保留的拍摄元数据（像素变了，拍摄记录不该跟着丢） */
private val KEEP_EXIF_TAGS = listOf(
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_LENS_MODEL,
    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_WHITE_BALANCE,
    ExifInterface.TAG_EXPOSURE_PROGRAM,
    ExifInterface.TAG_FLASH,
    ExifInterface.TAG_SOFTWARE,
)
