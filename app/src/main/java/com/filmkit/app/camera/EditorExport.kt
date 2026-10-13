package com.filmkit.app.camera

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.filmkit.app.EngineBridge
import com.filmkit.app.jsonEscape
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
 * ⚠ E2-21 起本函数**不再是编辑器打开时的起点**（那是 [editorOpenParams] 的恒等基线）：
 * 它只服务「套用原片风格」—— 用户主动点，才把快照里这一整套参数重新下发一遍。
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
 * 打开编辑器时的参数起点（E2-1 拍板，E2-21 修正为**全恒等基线**）：**画面必须与原片完全一致**。
 *
 * 成片在拍摄时已经把**当时的全部参数**烘焙进了像素 —— 引擎 `kFsStyle` 会读曝光 [0]、
 * 白平衡 [1..3]、对比度 [4]、饱和度 [20..22]、清晰度 [78]、色温 [88]、色调 [89]、
 * 影调分区 [90..93]、褪色 [94]（以及 LUT 强度 [34] / 颗粒 [26] / 暗角 [66..68] /
 * 局部 [79..87]），
 * 成片就是它们的产物。所以打开时从快照恢复任何一个槽位 = **把同一个效果再叠一遍**
 *（用户看到的"滑杆上又出现基础曝光值"只是表象，画面本身也变了）。
 * 起点 = [CameraPresets.standard]（全恒等）。
 *
 * ⚠ **[34] LUT 强度必须额外归 0**：standard 的默认是 1.0 = 全量混合，而引擎侧 `bindLut`
 * 的生效判据是 `lut_size_ > 1 && k > 0`，编辑器打开时又会上传快照那张 LUT 的资产
 *（EditorScreen 的 `engine.setLut`）⇒ 强度留在 1.0 会让 LUT 再叠一遍，正是本函数要修的
 * 那个 bug 的 LUT 版（E1-3 离线验收：强度 0 时输出与输入 max|Δ| = 1 LSB，仅 dither）。
 *
 * 快照信息**不丢**：调用方仍把它用于 UI 展示（"原片风格：xxx · 85%"）与「套用原片风格」
 *（用户主动点才把快照参数下发，见 [applySnapshotParams]）。
 */
internal fun editorOpenParams(): DoubleArray =
    CameraPresets.standard().also { it[34] = 0.0 }

/**
 * 「套用原片风格」的参数下发（E2-21）：把快照参数逐槽写进 [current]。
 *
 * **原地写、不换数组对象** —— 滑杆组件持有这个数组的引用（换对象 = 读数与槽位脱钩）。
 *
 * 为什么抽成纯函数：E2-21 起这是快照参数**唯一**的下发入口（打开编辑器时一律恒等，见
 * [editorOpenParams]），"套用后逐槽 == 快照值"这条语义必须能被 JVM 单测钉住 ——
 * Compose 里的调用点（`applySnapshotStyle`）跑不了单测。
 */
internal fun applySnapshotParams(current: DoubleArray, snapshot: DoubleArray) {
    snapshot.copyInto(current, 0, 0, min(current.size, snapshot.size))
}

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
 *     该函数返回的是**全恒等基线**（E2-21：成片已含拍摄时的全部参数，打开时一个都不重放），
 *     拿快照原文比会把"打开时那一次必然的恒等化"误判成用户修改。
 *  2. **值比较，不是"是否碰过滑杆"**：用户改了又改回原值 ⇒ 画面与原片一致 ⇒ 该直通。
 *  3. **不用 `==`**（E2-3 踩过的浮点坑：`0.01f.toDouble()` = 0.009999999776…）：容差见
 *     [PARAM_EPS]。
 *
 * **非有限值（NaN/Inf）一律判"有修改"**：基准来自 [editorOpenParams]（[CameraPresets.standard]
 * 的字面量，无外部输入）⇒ 恒有限，
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
 * 源图是否是 JPEG（E2-18b）：**文件头魔数 `FF D8 FF`**（SOI + 下一个 marker 的前缀），
 * 只看前 3 字节 ⇒ 调用方读头部即可，不必把整张图读进内存。
 *
 * 为什么必须判这个（架构师发现的 E2-9 连带 bug）：直通路径 = **复制原片字节**，
 * 而落盘时文件名固定 `.jpg`、MediaStore MIME 固定 `image/jpeg`（见 [saveEditorCopy]）
 * ⇒ 源图若是 DNG，存出来的就是"扩展名说 JPEG、内容是 DNG"的四不像，相册/系统解码器
 * 按 JPEG 解会失败或显示异常。
 *
 * **白名单，不是黑名单**：只认"是 JPEG"，其余一律不放行 —— DNG 之外还有 HEIC / PNG /
 * WebP / 未来新格式，黑名单漏一个就是同一个 bug 再来一次。判错的代价也是安全方向：
 * 误判成"非 JPEG"只是多渲染一次（像素正确），误判成"JPEG"才是内容与扩展名不符。
 *
 * 不用 MIME 兜底：`ContentResolver.getType` 对 DNG 不可靠（不少设备报 `image/jpeg`
 * 或干脆不报），而魔数读不到时 [passthroughAllowed] 会判 false 走渲染 —— 白名单下
 * "读不到"与"不是 JPEG"同义，正是想要的失败方向。
 *
 * 常见非 JPEG 源图的头部（供对照，本函数不依赖它们）：
 *  - **DNG / TIFF**：`II*\0`（49 49 2A 00，小端）或 `MM\0*`（4D 4D 00 2A，大端）
 *  - **HEIC**：`....ftypheic`（第 4~12 字节）
 *
 * @param head 文件头部字节（≥3 字节即可）；null / 过短 ⇒ false
 */
internal fun isJpegSource(head: ByteArray?): Boolean =
    head != null && head.size >= 3 &&
        head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()

/**
 * 「复制原片字节」的完整判据（E2-9 参数判据 + E2-18b 源图格式判据）。
 *
 * 两条缺一不可，理由各是一条真金白银的 bug：
 *  - [paramsUnchanged] = false（有修改）⇒ 直通会把用户的修改丢掉（E2-9 的红线）；
 *  - [jpegSource] = false（源图不是 JPEG）⇒ 直通会把 DNG 字节存成 `.jpg`（E2-18b）。
 *
 * 抽成纯函数而不是在调用处内联 `&&`：这是**两条各自出过 bug 的规则的唯一真相**，
 * JVM 单测要能直接钉住"JPEG 才直通、DNG 不直通"，而不是靠读 Compose 代码确认。
 */
internal fun passthroughAllowed(paramsUnchanged: Boolean, jpegSource: Boolean): Boolean =
    paramsUnchanged && jpegSource

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
 * 光晕（halation `[62..65]`，E4-5）与柔光（soft glow `[95..97]`，SG-1）的 UI ↔ 槽位映射
 * （**SG-2：把引擎已实现、但用户手动调不到的两组参数接进编辑器参数面板**）。
 * **纯逻辑、无 Android 依赖** ⇒ JVM 单测直接覆盖往返与 pivot。
 *
 * 槽位语义与默认值（权威定义 = `core/engine/src/film/FilmParams.h`；两条链路
 * `passOutput` / `passStyle` 消费同一个 `runHalation` / `runGlow`）：
 *  - [62] `halation_strength` 0..1：**0 = 整链旁路**（默认）；>0 = 叠加量
 *  - [63] `halation_threshold` 显示域 sRGB 0..1：**0.80**（[62] = 0 时不可达）
 *  - [64] `halation_radius` 0..1 归一：**0.45**（同上）
 *  - [65] `halation_warmth` 0..1（红橙程度）：**0.40**（同上）
 *  - [95] `glow_strength` 0..1：**0 = 整链旁路**（默认，零 pass 零纹理）
 *  - [96] `glow_radius` 0..1 归一：**0.35**（[95] = 0 时不可达）
 *  - [97] `glow_threshold` 显示域 sRGB 0..1：**0.70**（同上）
 *
 * 这七条的 UI 量程 = 引擎契约量程（0..1 归一）⇒ **直通，不需要换算函数**，只需要夹紧与坏数据
 * 兜底。**刻意不给"每条一对 `xxxSlot` / `xxxUi`"**：七条的量程与语义完全相同，七对同体函数
 * 是样板；一个 [normalizedSlot] / [normalizedUi] + 七个 pivot 常量就够 —— 量程与 pivot 由 UI
 * 参数表与本段**共用同一份定义**（否则滑杆能拖到 clamp 之外 = 假滑杆）。
 *
 * ⚠ **pivot 必须等于引擎中性值**（「重置」回到这里，`EditorParam.pivot`）：[62]/[95] 是 0 = 关，
 * 其余五条是各自的设计默认值。写错的表现是"用户一 reset 画面就变"——
 * `EditorHaloGlowUiTest` 把七个 pivot 同时与 `CameraPresets.standard()`（Kotlin 侧中性基线）和
 * `FilmParams.h` 里解析出的 C++ 默认值**逐槽对拍**（两处都改才算改对）。
 */

/** 光晕 / 柔光七条共用的 UI 量程：0..1（= 引擎契约的归一域，直通槽位）。 */
internal val HALO_GLOW_UI_RANGE = 0f..1f

/** [62] 光晕 · 强度 pivot = **0 = 关**（`halation_strength = 0.0f` ⇒ 整链旁路）。 */
internal const val HALO_UI_PIVOT = 0f

/** [63] 光晕 · 触发阈值 pivot = **0.80**（`halation_threshold = 0.80f`，显示域 sRGB）。 */
internal const val HALO_THRESHOLD_UI_PIVOT = 0.80f

/** [64] 光晕 · 半径 pivot = **0.45**（`halation_radius = 0.45f`，0..1 归一）。 */
internal const val HALO_RADIUS_UI_PIVOT = 0.45f

/** [65] 光晕 · 暖度 pivot = **0.40**（`halation_warmth = 0.40f`）。 */
internal const val HALO_WARMTH_UI_PIVOT = 0.40f

/** [95] 柔光 · 强度 pivot = **0 = 关**（`glow_strength = 0.0f` ⇒ 整链旁路、零 pass 零纹理）。 */
internal const val GLOW_UI_PIVOT = 0f

/** [96] 柔光 · 半径 pivot = **0.35**（`glow_radius = 0.35f`，0..1 归一）。 */
internal const val GLOW_RADIUS_UI_PIVOT = 0.35f

/** [97] 柔光 · 触发阈值 pivot = **0.70**（`glow_threshold = 0.70f`，显示域 sRGB）。 */
internal const val GLOW_THRESHOLD_UI_PIVOT = 0.70f

/** UI 归一量（0..1）→ 槽位（[62..65] / [95..97] 共用）。越界夹到量程端点。 */
internal fun normalizedSlot(ui: Float): Double =
    ui.coerceIn(HALO_GLOW_UI_RANGE.start, HALO_GLOW_UI_RANGE.endInclusive).toDouble()

/**
 * 槽位（0..1）→ UI 归一量。**坏数据回落 [fallback]**（= 该槽的引擎默认值，调用方**显式**传）。
 *
 * 为什么 fallback 必须逐槽给、且不给默认参数值：七条的默认值各不相同（[63] 0.80 / [64] 0.45 /
 * [97] 0.70 …），统一回 0 会把"读不出来"变成"阈值与半径被拖到最左端"（画面突变）——
 * 与 [latitudeUi] 回落 0.01、[tempUi] 越界回落 6500K 是同一条纪律。越界（>1 / <0）夹到端点：
 * UI 永远写不出越界值，快照里出现只可能是手改 prefs 的坏数据。
 */
internal fun normalizedUi(slot: Double, fallback: Float): Float = if (!slot.isFinite()) {
    fallback
} else {
    slot.coerceIn(HALO_GLOW_UI_RANGE.start.toDouble(), HALO_GLOW_UI_RANGE.endInclusive.toDouble())
        .toFloat()
}

/**
 * 「质感」组重置（SG-2）：把**三个"强度"槽**归 0 = 关 —— [62] 光晕 / [66] 暗角 / [95] 柔光；
 * 其余七条（[63..65] 光晕阈值·半径·暖度、[67][68] 暗角半径·羽化、[96][97] 柔光半径·阈值）
 * **保持原值**。
 *
 * 为什么只归强度：三条强度为 0 时各自的整链都旁路（引擎侧 `strength > 0` 才跑），其余参数
 * 不可达 ⇒ 画面回到恒等，而用户调好的阈值/半径**不必重调**（下次开强度即恢复原样）。
 * 这也是 E2-1 时代 `resetVignette` 的既有语义（"重置 = 回到恒等 = 没有暗角"），SG-2 只是把
 * 新增的两条强度一并纳入 —— 判据仍是"界面上这条滑杆在哪个组，本组重置就得把它带回恒等"。
 */
internal fun resetFilmTextureParams(p: DoubleArray) {
    p[62] = HALO_UI_PIVOT.toDouble()
    p[66] = vigStrengthSlot(0f)
    p[95] = GLOW_UI_PIVOT.toDouble()
}

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

/**
 * ★ U-6b：**分离色调 [23][24] / 黑白 [25]** 的 UI ↔ 槽位映射。槽位语义见 ADR 的「E2-11 槽位表」，
 * 引擎实现在 `FrameRenderer.cpp` 的 `kFsFilm`（U-1 已把这三个槽接到编辑器链路 ⇒ 两侧都生效）。
 *
 *  - [23] `split_warm`：暖部分离色调强度（给暗部加暖 / 亮部减暖的成对色向量），恒等 0
 *  - [24] `split_cool`：冷部分离色调强度，恒等 0
 *  - [25] `monochrome`：黑白混合比（引擎 `out3 = mix(out3, vec3(luma(out3)), uMono)`）
 *    —— **0 = 彩色原样、1 = 全黑白，中间值 = 部分去色**（连续量，不是一个开关）
 *
 * ⚠ **为什么 [25] 是连续滑杆而不是开关**：引擎侧本来就是 `mix()` 的混合系数，预设 JSON 里
 * 也真的出现过中间值 ⇒ 做成 0/1 开关会把"部分去色"这个既有能力从 UI 上抹掉（U-1 接线 `[25]`
 * 的收益正是"黑白预设能生效"，但"能调多黑"同样要能调）。
 *
 * ⚠ 与「褪色」[94] 的区别（两者数值域都是 0..1，别混）：褪色抬**黑场**、白点不动（"洗白"观感），
 * 黑白抹**色度**、亮度不变。因此它们是两条独立滑杆，各写各的槽，不共用映射函数。
 */

/** 分离色调 UI 量程：−1..+1 直通槽位（引擎 `uSplit` 无 clamp；UI 取与影调分区同口径的 ±1）。恒等 0。 */
internal val SPLIT_UI_RANGE = -1f..1f

/** 黑白 [25] UI 量程：0..1 直通（0 = 彩色原样、1 = 全黑白）。基准在最左端 ⇒ 不画中点标记。 */
internal val MONO_UI_RANGE = 0f..1f

/** UI 分离色调（−1..+1）→ 槽位 [23]（暖）/ [24]（冷）。坏数据回落恒等 0。 */
internal fun splitSlot(ui: Float): Double = if (ui.isFinite()) {
    ui.coerceIn(SPLIT_UI_RANGE.start, SPLIT_UI_RANGE.endInclusive).toDouble()
} else {
    0.0
}

/** 槽位 [23] / [24] → UI 分离色调（−1..+1）。坏数据回落恒等 0。 */
internal fun splitUi(slot: Double): Float = if (!slot.isFinite()) {
    0f
} else {
    slot.coerceIn(SPLIT_UI_RANGE.start.toDouble(), SPLIT_UI_RANGE.endInclusive.toDouble()).toFloat()
}

/** UI 黑白（0..1）→ 槽位 [25]。坏数据回落恒等 0 = 彩色原样。 */
internal fun monoSlot(ui: Float): Double = if (ui.isFinite()) {
    ui.coerceIn(MONO_UI_RANGE.start, MONO_UI_RANGE.endInclusive).toDouble()
} else {
    0.0
}

/** 槽位 [25] → UI 黑白（0..1）。**负值当"关"**（`mix()` 的负系数无意义）。坏数据回落 0。 */
internal fun monoUi(slot: Double): Float = if (!slot.isFinite()) {
    0f
} else {
    slot.coerceIn(MONO_UI_RANGE.start.toDouble(), MONO_UI_RANGE.endInclusive.toDouble()).toFloat()
}

/**
 * "**0 就是关**"型连续量的读数：**0 → `关`**，否则 0..100 整数（`35`）。
 *
 * 为什么零值显示 `关` 而不是 `0`：它是"完全没启用"（引擎侧 0 = 整段旁路 / 恒等），与清晰度 /
 * 褪色那些"0 就是关"的参数同一口径，只是多一个更直白的词。
 *
 * 为什么非零**不做成"开/关"**：[25] 黑白 / [62] 光晕强度 / [95] 柔光强度都是**连续**的
 * 混合比例 —— 0.35 显示"开"会把量级吞掉（0.4 与 0.9 看起来一样），那正是 U-6b 要修掉的假读数。
 *
 * 服务三条：黑白 [25]（`monoUi`）/ 光晕·强度 [62] / 柔光·强度 [95]（`normalizedUi`）。
 * SG-2 前它叫 `formatMono`；改名是因为它现在服务的不止黑白 —— 名字必须说真话。
 */
internal fun formatOff100(ui: Float): String {
    if (!ui.isFinite()) return "关"
    val n = (ui.coerceIn(0f, 1f) * 100f).roundToInt()
    return if (n == 0) "关" else n.toString()
}

/**
 * ★ U-6b：**胶片曲线 [5..10]** 的 UI ↔ 槽位映射（U-2 已把这条曲线接到编辑器链路）。
 *
 * 槽位语义 = **filmic latitude**（`FilmParams.h:58-59`：`shoulder[3]` = [5..7]、`toe[3]` = [8..10]；
 * ⚠ `preset-DESIGN.md` §1 的表格把这两行写反了，以引擎字段名为准）。
 *
 * 一条滑杆**同写三通道**（R/G/B）同一个值 —— 与饱和度 [20..22] 同一种"一条滑杆多槽"的形态
 * （[writeSaturation]），理由也一样：普通用户不逐通道调曲线；引擎是 per-channel ⇒ 写三槽让
 * "三通道一致"成为默认，逐通道差异仍可由预设（数据层）表达。
 *
 * ## ⚠⚠ 默认值是 0.01 而不是 0（本卡最容易踩的坑）
 *
 * latitude 语义下 **`0` = 过渡区紧贴中灰 = 极端 S 曲线**，**不是"无效果"**
 * （`CameraPresets.kt` 的 `[5..10]` 专章有同样的警告，`presetSlotCurve` 的非线性缩放也依赖它）。
 * 由此三条硬结论：
 *  - 恒等值 / reset 目标 / 中点标记 = **[LATITUDE_UI_PIVOT] = 0.01**（不是 0）；
 *  - 滑杆**允许**拖到 0 —— 用户主动要极端曲线是**有意的能力**，不是 bug；
 *  - 坏数据（NaN/Inf）回落 **0.01**：回落 0 会把"读不出来"变成"极端曲线"，
 *    这是最容易造成画面突变的失败模式（[tempUi] 越界回落 6500K 是同一条纪律）。
 *
 * 读数两位小数（[formatDecimals2]）：latitude 在 0 附近敏感（0.01 / 0.02 是两档不同的曲线），
 * 整数化会把它们都显示成 `1` / `2`。
 */

/** 胶片曲线 UI 量程：0..1（= `FilmParams.h` 的 latitude 取值域）。 */
internal val LATITUDE_UI_RANGE = 0f..1f

/**
 * 胶片曲线恒等值 / reset 目标 / 中点标记位置 = **0.01**（darktable 默认 latitude）。
 *
 * ⚠ **不是 0**：0 在 latitude 语义里是"过渡区紧贴中灰"的极端 S 曲线（见上一段 KDoc）。
 */
internal const val LATITUDE_UI_PIVOT = 0.01f

/** UI 胶片曲线（0..1）→ 槽位值。坏数据回落恒等 [LATITUDE_UI_PIVOT]（**不是 0**）。 */
internal fun latitudeSlot(ui: Float): Double = if (ui.isFinite()) {
    ui.coerceIn(LATITUDE_UI_RANGE.start, LATITUDE_UI_RANGE.endInclusive).toDouble()
} else {
    LATITUDE_UI_PIVOT.toDouble()
}

/** 槽位值 → UI 胶片曲线（0..1）。坏数据回落恒等 [LATITUDE_UI_PIVOT]（**不是 0**）。 */
internal fun latitudeUi(slot: Double): Float = if (!slot.isFinite()) {
    LATITUDE_UI_PIVOT
} else {
    slot.coerceIn(
        LATITUDE_UI_RANGE.start.toDouble(),
        LATITUDE_UI_RANGE.endInclusive.toDouble(),
    ).toFloat()
}

/**
 * 一条胶片曲线滑杆**同写三通道**：`[offset..offset + 2]` 写同一个值
 * （肩部 `offset = 5`、趾部 `offset = 8`）。与 [writeSaturation] 同一形态（"一条滑杆多槽"），
 * 区别只是这里三槽的语义是 R/G/B。
 */
internal fun writeLatitude(p: DoubleArray, offset: Int, ui: Float) {
    val v = latitudeSlot(ui)
    p[offset] = v
    p[offset + 1] = v
    p[offset + 2] = v
}

/**
 * 两位小数读数：`0.30` / `0.01` / `1.00`（胶片曲线 [5..10] 专用，见那一段的 KDoc）。
 *
 * 只做格式化、**不做量程夹紧**：调用方传进来的已经是 UI 值（`read` 侧已回落恒等）；
 * 坏数据回落 `0.00`（读数绝不能出现 `NaN`）。
 */
internal fun formatDecimals2(ui: Float): String {
    val n = (if (ui.isFinite()) ui else 0f) * 100f
    val r = n.roundToInt()
    val a = abs(r)
    return "${if (r < 0) "-" else ""}${a / 100}.${(a % 100).toString().padStart(2, '0')}"
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

// ─────────────────────────────────────────────────────────────────────────────
// ★ PS-10：filmic **黑白点**（`[37]` / `[38]`）—— 量程 / 基准 / 读数
//
// 这两个槽是胶片曲线的**两端**（`FilmParams.h:145-156`）：黑点 = "多暗算黑"（toe 端点）、
// 白点 = "多亮算白"（显示上限的锚点），单位 = **相对中灰 18.45% 的 EV**（物理量，与机型 /
// 曲线无关）。此前它们在 `CAMERA_NO_SLIDER_SLOTS` 里（只有显示、没有滑杆），PS-10 补滑杆。
//
// **量程的依据（不是自造）**：与 `SettingsScreen.kt` 的既有全局滑杆**逐位相同**
// （黑点 `-12f..-1f` / 白点 `1f..8f`）—— 那是**同一个参数**的既有量程，用户已经在那里调它；
// 参数表另定一套 = 同一个物理量有两个范围（ADR-12 的"两套真相"）。引擎侧对这两个槽
// **没有绝对 clamp**（`FilmParams.h:550-551` 直通；`computeFilmicSpline` 只有相对约束
// `black ≤ white − 0.2`）⇒ 量程纯粹是 UI 决定。
//
// **pivot = 引擎默认值**（`FilmParams.h` 的 `-7.65f` / `+4.56f`，也是 `CameraPresets.standard()`
// 的基线 = 稀疏化的中性基准）：中点标记画在"重置会回到哪"，与 `EditorParam.pivot` 的定义一致。
//
// ⚠ **如实记录一条引擎侧的非对称**（本卡**不改引擎**）：拍照链路（`computeFilmicSpline`）两个
// 端点在 1..8 全量程有效；**编辑器链路**（`computeEditorCurve`，显示域端点重映射）里白点有
// 物理上限 —— `wh = clamp(srgbEncode(grey·2^white_ev), grey_disp + 0.05, 1.0)`，当
// `white_ev ≥ log2(1/0.1845) ≈ 2.4377` 时 `wh` 恒为 `1.0` ⇒ **编辑器里白点拖到 2.44 以上与
// 2.44 逐位相同**（显示域没有比 1.0 更亮的像素 —— 这是显示域曲线的物理上限，不是 bug）。
// 默认 4.56 与内置三条的 2.54~2.75 **都已在该上限之上** ⇒ 编辑器里它们的白点效果相同。
// 量程仍取 1..8：这两个槽的**主战场是拍照链路**（预设的白点就是给相机取景 / 成片用的），
// 而 8 覆盖了真机实测有效的 6.2 档（见 `SettingsScreen.kt` 白点那一行的 desc）。
// ─────────────────────────────────────────────────────────────────────────────

/** `[37]` filmic 黑点（"多暗算黑"）的 UI 量程 —— 槽位**就是** EV ⇒ 直通，没有换算对。 */
internal val FILMIC_BLACK_EV_UI_RANGE = -12f..-1f

/** `[38]` filmic 白点（"多亮算白"）的 UI 量程 —— 槽位**就是** EV ⇒ 直通，没有换算对。 */
internal val FILMIC_WHITE_EV_UI_RANGE = 1f..8f

/** `[37]` 恒等值 / reset 目标 = 引擎默认（`FilmParams.h` 的 `filmic_black_ev = -7.65f`）。 */
internal const val FILMIC_BLACK_EV_PIVOT = -7.65f

/** `[38]` 恒等值 / reset 目标 = 引擎默认（`FilmParams.h` 的 `filmic_white_ev = 4.56f`）。 */
internal const val FILMIC_WHITE_EV_PIVOT = 4.56f

/**
 * **槽位本身就是 EV** 的读数（`[37]` 黑点 / `[38]` 白点）：`+4.6 EV` / `-7.6 EV` / `0.0 EV`。
 *
 * 与 [formatEv] 的区别（混用会把 −7.65 显示成 `-3.0 EV`）：那条的入参是 **UI 域**
 * （−1..1 = ±3 EV，内部 ×3 并 clamp 到 ±3），本条的入参就是槽位 EV 本身。
 *
 * **唯一实现**：`PresetDetail.kt` 的 `evText`（详情页 / 二级页只读格 / 列表摘要的读数）
 * 委托本函数 ⇒ 同一个数在滑杆与详情页**必然同形**，不是"两处各自格式化后碰巧一样"。
 */
internal fun formatEvSlot(v: Float): String {
    val r = if (v.isFinite()) (v * 10f).roundToInt() / 10f else 0f
    return if (r > 0f) "+${oneDecimal(r)} EV" else "${oneDecimal(r)} EV"
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
 * 0..100 整数读数（清晰度 / 暗角强度 / 局部半径·羽化·宽度）：UI 0..1 × 100。
 * 这是"清晰度"的口径（研究 §3.3.3 的目标显示就是 `0..100` 无符号整数）。
 */
internal fun formatZero100(ui: Float): String =
    if (ui.isFinite()) (ui * 100f).roundToInt().coerceIn(0, 100).toString() else "0"

/**
 * LUT 强度 UI 量程（E2-15 用户反馈："LUT强度应该统一可调至150"）：0..1.5。
 *
 * 为什么能超过 1：引擎侧是 `c = mix(c, lutApply(c), uLutStrength)` —— `t > 1` 是**外推**
 *（把 LUT 定义的变换推过头），数学上天然成立；且**拍照页早就在用这个量程**
 *（`RelativeSlider` 的默认 `range = 0f..1.5f`，拍照页 LUT 强度滑杆走的就是它）⇒
 * 编辑器只是**统一**到既有生产口径，不是新开一条通路。读数整数 0..150，见 [formatZero150]。
 */
internal val LUT_STRENGTH_UI_RANGE = 0f..1.5f

/**
 * 0..150 整数读数（LUT 强度）：UI 0..1.5 × 100 —— 与 [formatZero100] 同一口径，
 * 只是量程上限不同（150% 档位）。超出量程的坏数据夹到 150，不显示 `200` 这种越界读数。
 */
internal fun formatZero150(ui: Float): String =
    if (ui.isFinite()) (ui * 100f).roundToInt().coerceIn(0, 150).toString() else "0"

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
 * 「调整」组重置（E2-3；E2-14 扩影调分区；E2-15 再纳色温/色调；**U-6b 再纳胶片曲线**）：
 * 本组全部参数回**恒等值** —— 曝光 0 / 色温 [88] 0（= 6500K）/ 色调 [89] 0 / WB 1,1,1 /
 * 对比度 1 / 饱和度 1,1,1 / 清晰度 0 / 高光·阴影·白·黑色阶 0 / **胶片曲线 [5..10] = 0.01**。
 * 只动这 19 个槽位，其余槽位（快照继承来的风格参数、褪色/分离色调/黑白/暗角/LUT）原样保留。
 *
 * 色温/色调为什么在**这里**（E2-15 用户反馈："色温方向正确，放的位置不对，怎么放 lut 里了
 * 应该是调整里面"）：它们是白平衡的两条，与 [1..3] 通道增益同属"基础调整"；界面把它们放进
 * 「调整」组之后，重置入口也必须跟着走 —— 否则用户看到"调整组里有色温滑杆、重置本组却不动它"
 *（= 假重置，与 ADR-12 的假滑杆同一类问题）。**U-6b 的胶片曲线两条按同一条纪律并入本组**。
 *
 * ⚠ **胶片曲线回 [LATITUDE_UI_PIVOT] = 0.01 而不是 0**：latitude 语义下 0 = 极端 S 曲线
 *（见 [latitudeSlot] 的 KDoc）。重置写成 `p[i] = 0.0` 会让"重置本组"把画面变成极端反差 ——
 * 那不是恒等值。单测 `resetRestoresLatitudeToDefaultAndMonoSplitToZero` 钉死这一条。
 */
internal fun resetBasicParams(p: DoubleArray) {
    p[0] = 0.0
    p[1] = 1.0
    p[2] = 1.0
    p[3] = 1.0
    p[4] = 1.0
    for (i in 5..10) p[i] = LATITUDE_UI_PIVOT.toDouble()   // ← 0.01，不是 0（见 KDoc）
    writeSaturation(p, 1f)
    p[78] = 0.0
    p[88] = 0.0
    p[89] = 0.0
    p[90] = 0.0
    p[91] = 0.0
    p[92] = 0.0
    p[93] = 0.0
}

/**
 * 「色彩」组的**引擎参数**重置（E2-14；E2-15 缩到只剩褪色；**U-6b 纳入分离色调/黑白**）：
 * 褪色 [94] → 0、分离色调 [23][24] → 0、黑白 [25] → 0（= 彩色原样）。
 *
 * 色温 [88] / 色调 [89] 已随 E2-15 归入「调整」组（见 [resetBasicParams]）；胶片曲线 [5..10]
 * 随 U-6b 归入「调整」组（同上）。**判据只有一条**：界面上这条滑杆在哪个组，本组重置就得
 * 把它带回恒等值 —— 否则就是"组里有滑杆、重置却不动它"的假重置。
 *
 * LUT **不在这里、也不在 UI 的重置里** —— LUT 是用户选的"风格素材"，重置参数不该丢掉素材
 * 选择本身（E2-15 用户确认："这个你判断的对，不应该清"）。强度归 0 由 UI 侧写 [34] 完成。
 */
internal fun resetColorParams(p: DoubleArray) {
    p[23] = 0.0
    p[24] = 0.0
    p[25] = 0.0
    p[94] = 0.0
}

/**
 * 「取消」是否需要二次确认（E2-15 用户反馈："如果改过参数，弹一句『放弃本次修改？』→ 确认才退出"）。
 *
 * **判据直接复用 [paramsUnchanged]**（E2-9 / ADR-13 的同一份逻辑）：当前参数 vs **打开编辑器时**
 * 的基准（`editorOpenParams` 的副本）。不另写一套比较 —— 两套比较必然出现"导出认为无修改、
 * 取消却弹窗"这种自相矛盾。
 *
 * 任一侧为 null（首帧就绪前点取消 / 状态丢失）⇒ **不弹窗直接退出**：此时没有任何可丢的修改，
 * 弹窗只会挡住用户的退路。
 */
internal fun discardNeedsConfirm(
    current: DoubleArray?,
    baseline: DoubleArray?,
    /** E2-12：裁剪是否非全幅（裁剪**不在** FilmParams 里，见 [CropSpec] 的 KDoc） */
    cropDirty: Boolean = false,
): Boolean {
    // ⚠ E2-12：裁剪是几何变换、不进 FilmParams ⇒ `paramsUnchanged` 看不见它。**必须**
    // 在这里显式并进来，否则"只裁了图、没动参数"会被判成"无修改"：取消不弹确认、
    // 导出还会走直通（把原片字节原样复制出去，裁剪被静默丢弃）。
    if (cropDirty) return true
    if (current == null || baseline == null) return false
    return !paramsUnchanged(current, baseline)
}

/**
 * 撤销 / 重做的**一步**（E2-25）：一次可回退的编辑状态。
 *
 * 三个字段缺一不可 —— 少任何一个都会出现"撤销了但画面没变"：
 *  - [params]：`FilmParams` 数组。★ **必须是副本**：滑杆是**原地改写**这个数组的
 *    （`EditorParam.write` 直接写槽位、`dragLocalCenter` 直接写 [80]/[81]）⇒ 存引用等于
 *    存了个"跟着当前状态一起变"的快照，撤销永远回到现在（经典 bug）。构造时统一
 *    `copyOf()`，调用方不必记得。
 *  - [crop]：裁剪是**几何变换、不进 FilmParams**（ADR-16）⇒ 参数比不出它，
 *    漏了它"裁了图撤不回全幅"，连带直通与「放弃修改」确认都会判错（见 [discardNeedsConfirm]）。
 *  - [lutId]：LUT **资产 id 不在 FilmParams 里**（数组里只有强度 [34]）⇒ 漏了它
 *    "换了 LUT 撤不回去"。强度 [34] 随 [params] 一起回退，不必单列。
 *
 * [sameAs] = "画面是否相同"，也就是**无变化不入栈**的判据：参数走 [paramsUnchanged]
 * （复用 E2-9 的容差 —— `0.01f.toDouble()` 这类 Float→Double 噪声不会被误判成一次修改），
 * 裁剪与 LUT id 是离散值 ⇒ 精确比。
 */
internal class EditorSnapshot(
    params: DoubleArray,
    val crop: CropSpec,
    val lutId: String?,
) {
    /** ★ 副本（见类 KDoc 第 1 条） */
    val params: DoubleArray = params.copyOf()

    /** 画面是否与 [other] 相同（= 无变化判据；容差与导出直通同一份） */
    fun sameAs(other: EditorSnapshot): Boolean =
        crop == other.crop && lutId == other.lutId && paramsUnchanged(params, other.params)

    companion object {
        /** 撤销栈深度上限（E2-25：30 步；内存账见 [EditorHistory] 的 KDoc） */
        const val MAX_STEPS = 30
    }
}

/**
 * 撤销 / 重做栈（E2-25）。纯逻辑、无 Android / Compose 依赖 ⇒ JVM 单测直接钉住
 * 入栈 / 出栈 / redo 失效 / 深度裁剪 / 无变化不入栈 / 快照是副本。
 *
 * **记录时机 = "变更落地之后记一步"**（调用方在操作生效后调 [record]）：`current` 恒等于
 * 当前画面，[record] 把**变更前**的那个状态压进 undo 栈 —— 与"离散操作前记一步"是同一件事
 *（撤销回到操作前的状态），但多一条保证：**操作其实没改变任何东西时不产生空步**。
 * 若反过来在操作前无条件记，用户连点两次「重置本组」而中间无变化 ⇒ 栈里多一个与当前相同的
 * 状态 ⇒ 点撤销画面不动（正是本卡要避免的"点了撤销没反应"）。
 * 滑杆同理：松手时参数**已经**改完（拖动中写入），这里记的是松手后的状态。
 *
 * 为什么存**状态快照**而不是"反向操作"：一个快照 ≈ 96 个 double（768B）+ 3 个字段
 * ≈ 800B，30 步 ≈ 24KB —— 可忽略；而反向操作要给 19 个参数 × 4 组重置 × LUT × 裁剪
 * 各写一遍逆运算（裁剪的逆、LUT 资产的重新上传…），是 bug 农场。
 *
 * @param limit 深度上限：超限丢**最旧**的（[ArrayDeque.removeFirst]）。redo 栈不必单独限制
 *   —— 它的元素只来自 undo 栈 / current，规模天然 ≤ limit。
 */
internal class EditorHistory(private val limit: Int = EditorSnapshot.MAX_STEPS) {
    private var current: EditorSnapshot? = null
    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 栈深（单测断言深度裁剪用） */
    val undoDepth: Int get() = undoStack.size
    val redoDepth: Int get() = redoStack.size

    /** 落地基线（打开编辑器 / 换图完成时）：两个栈一起清空 —— 新图没有可撤销的历史。 */
    fun reset(baseline: EditorSnapshot) {
        current = baseline
        undoStack.clear()
        redoStack.clear()
    }

    /**
     * 记一步（**操作生效之后**调）。
     *
     * @return true = 真的入栈；false = 与当前状态画面相同 ⇒ 丢弃（"无变化不入栈"，
     *   避免出现"点撤销画面不动"的空步）
     */
    fun record(s: EditorSnapshot): Boolean {
        val cur = current
        if (cur == null) {
            // 基线还没落地（首帧就绪前的理论路径）：只当基线，不产生可撤销步
            current = s
            return false
        }
        if (cur.sameAs(s)) return false
        undoStack.addLast(cur)
        while (undoStack.size > limit) undoStack.removeFirst()   // 超限丢最旧的
        current = s
        redoStack.clear()                                        // ★ 新操作后 redo 必须失效
        return true
    }

    /** 撤销：当前状态进 redo 栈，返回**要恢复到的**状态；没得撤 ⇒ null（调用方不动画面） */
    fun undo(): EditorSnapshot? {
        val cur = current ?: return null
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(cur)
        current = prev
        return prev
    }

    /** 重做：与 [undo] 对称（撤销过的状态重新进 undo 栈，所以"重做后再撤销"能原路返回） */
    fun redo(): EditorSnapshot? {
        val cur = current ?: return null
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(cur)
        while (undoStack.size > limit) undoStack.removeFirst()
        current = next
        return next
    }
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

// ─────────────────────────────────────────────────────────────────────────────
// E2-12 裁剪（ADR-16）：**几何变换**，与色彩参数无关 ⇒ 不进 FilmParams，走
// submitDisplay 的独立参数通道（[CropSpec]）。交互坐标 = **旋转后画面**的归一化坐标
// （用户看到的就是旋转后的画面，框在其中拖），提交前换算成"**原图** UV 子矩形 + rot"。
// 全部纯函数 ⇒ JVM 单测覆盖（CropGeometryTest）。
//
// 为什么不直接在原图坐标里框：rot≠0 时用户看到的画面已经转了 90°，在原图坐标里拖动
// 会出现"往右拖、框往上走"。这里让交互坐标跟着用户看到的画面走，只在提交那一刻做
// 一次 90° 置换换算（[cropSpecOf]）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 裁剪比例锁定（工具栏那一行）。[ratio] 返回目标**像素**宽高比（宽/高），null = 不锁。
 * 「原图」= 当前（旋转后）画面的比例 ⇒ 选它即"整幅不裁"。
 */
internal enum class CropAspect(val label: String) {
    FREE("自由"),
    SQUARE("1:1"),
    R4_3("4:3"),
    R3_4("3:4"),
    R16_9("16:9"),
    ORIGINAL("原图"),
    ;

    fun ratio(viewW: Int, viewH: Int): Float? = when (this) {
        FREE -> null
        SQUARE -> 1f
        R4_3 -> 4f / 3f
        R3_4 -> 3f / 4f
        R16_9 -> 16f / 9f
        ORIGINAL -> if (viewH > 0) viewW.toFloat() / viewH else 1f
    }
}

/** 裁剪框（**旋转后画面**的归一化坐标：l/t = 左上角，w/h = 宽高，全 ∈ [0,1]）。 */
internal data class CropView(
    val l: Float = 0f,
    val t: Float = 0f,
    val w: Float = 1f,
    val h: Float = 1f,
    /** 顺时针 90° 步进（0..3）；旋转**画面**而框不动（用户看到图转了、框还在原处） */
    val rot: Int = 0,
) {
    val r: Float get() = l + w
    val b: Float get() = t + h

    companion object {
        /** 全幅（不裁剪、不旋转） */
        val FULL = CropView()
    }
}

/** 裁剪框的 8 个手柄（4 角 + 4 边） */
internal enum class CropHandle { TL, T, TR, R, BR, B, BL, L }

/**
 * rot 归一化到 0..3。**凡是要判 rot（奇偶 / 分支 / 全幅）的地方都必须先过它**：
 * Kotlin 的 `%` 保号（`-3 % 2 == -1`、`-3 % 4 == -3`）⇒ 直接判会把负 rot 判成偶数 / 判成
 * 未定义分支。★ E2-12c：以前 [cropViewSize] 与 `CropSpec.outputSize` 漏了这一步，而
 * [cropSpecOf] 归一化了 ⇒ 两处判据不一致（当前 rot 只来自 `(rot + 1) % 4` 所以不可达，
 * 但 rot 一旦从快照 / 预设 / EXIF 进来就会**静默判反**）。
 */
internal fun normalizeRot(rot: Int): Int = ((rot % 4) + 4) % 4

/** 旋转后画面的像素尺寸（rot 奇数 ⇒ 宽高交换）—— 比例锁定与最小尺寸的换算基准。 */
internal fun cropViewSize(srcW: Int, srcH: Int, rot: Int): Pair<Int, Int> =
    if (normalizeRot(rot) % 2 == 1) srcH to srcW else srcW to srcH

/** 拖动移动：dx/dy = 屏幕位移 ÷ 画面显示尺寸（归一化）。夹紧在画面内（不越界）。 */
internal fun cropViewMove(v: CropView, dx: Float, dy: Float): CropView {
    val maxL = (1f - v.w).coerceAtLeast(0f)
    val maxT = (1f - v.h).coerceAtLeast(0f)
    return v.copy(l = (v.l + dx).coerceIn(0f, maxL), t = (v.t + dy).coerceIn(0f, maxT))
}

/**
 * 以 (ax, ay) 为锚、按比例 k（w = k·h）放一个 w0 宽的框，并整体缩放到画面内。
 * [growX]/[growY]：框相对锚点的生长方向（+1 = 右/下，−1 = 左/上，0 = 居中）。
 */
private fun cropAnchored(ax: Float, ay: Float, w0: Float, k: Float, growX: Int, growY: Int): CropView {
    var w = w0.coerceAtLeast(1e-4f)
    var h = w / k
    val availW = when (growX) {
        1 -> 1f - ax
        -1 -> ax
        else -> 2f * minOf(ax, 1f - ax)
    }
    val availH = when (growY) {
        1 -> 1f - ay
        -1 -> ay
        else -> 2f * minOf(ay, 1f - ay)
    }
    val scale = minOf(1f, availW / w, availH / h)
    w = (w * scale).coerceIn(0f, 1f)
    h = (w / k).coerceIn(0f, 1f)
    val l = when (growX) {
        1 -> ax
        -1 -> ax - w
        else -> ax - w / 2f
    }
    val t = when (growY) {
        1 -> ay
        -1 -> ay - h
        else -> ay - h / 2f
    }
    return CropView(l.coerceIn(0f, (1f - w).coerceAtLeast(0f)),
        t.coerceIn(0f, (1f - h).coerceAtLeast(0f)), w, h)
}

/**
 * 缩放手柄（拖动角/边）。[dx]/[dy] = 屏幕位移 ÷ 画面显示尺寸。
 *
 * 比例锁定时：角手柄以**对角**为锚、按位移较大的轴主导；边手柄以**对边**为锚、
 * 另一个方向**居中生长**（这是主流修图 App 的手感）。[minPx] = 最小边长（像素）。
 */
internal fun cropViewScale(
    v: CropView,
    handle: CropHandle,
    dx: Float,
    dy: Float,
    aspect: Float?,
    viewW: Int,
    viewH: Int,
    minPx: Float = 32f,
): CropView {
    val minW = (minPx / maxOf(viewW, 1)).coerceAtMost(1f)
    val minH = (minPx / maxOf(viewH, 1)).coerceAtMost(1f)
    val left = handle == CropHandle.TL || handle == CropHandle.L || handle == CropHandle.BL
    val right = handle == CropHandle.TR || handle == CropHandle.R || handle == CropHandle.BR
    val top = handle == CropHandle.TL || handle == CropHandle.T || handle == CropHandle.TR
    val bottom = handle == CropHandle.BL || handle == CropHandle.B || handle == CropHandle.BR
    var l = v.l
    var t = v.t
    var r = v.r
    var b = v.b
    if (left) l = (l + dx).coerceIn(0f, r - minW)
    if (right) r = (r + dx).coerceIn(l + minW, 1f)
    if (top) t = (t + dy).coerceIn(0f, b - minH)
    if (bottom) b = (b + dy).coerceIn(t + minH, 1f)
    if (aspect == null) return CropView(l, t, r - l, b - t, v.rot)

    // 归一化空间里的目标宽高比：像素比 = (w·viewW)/(h·viewH) ⇒ w/h = ratio·viewH/viewW
    val k = aspect * maxOf(viewH, 1).toFloat() / maxOf(viewW, 1).toFloat()
    if ((left || right) && (top || bottom)) {
        val anchorX = if (left) r else l
        val anchorY = if (top) b else t
        val w0 = if (abs(dx) * viewW >= abs(dy) * viewH) (r - l) else (b - t) * k
        return cropAnchored(anchorX, anchorY, maxOf(w0, minW), k,
            if (left) -1 else 1, if (top) -1 else 1).copy(rot = v.rot)
    }
    if (left || right) {
        return cropAnchored((l + r) / 2f, (t + b) / 2f, maxOf(r - l, minW), k, 0, 0)
            .copy(rot = v.rot)
    }
    return cropAnchored((l + r) / 2f, (t + b) / 2f, maxOf((b - t) * k, minW), k, 0, 0)
        .copy(rot = v.rot)
}

/** 切换比例锁定：保持中心，按新比例重排当前框（放不下则整体缩小）。 */
internal fun cropViewWithAspect(v: CropView, aspect: Float?, viewW: Int, viewH: Int): CropView {
    if (aspect == null) return v
    val k = aspect * maxOf(viewH, 1).toFloat() / maxOf(viewW, 1).toFloat()
    var w = v.w.coerceIn(1e-4f, 1f)
    var h = w / k
    if (h > 1f) {
        h = 1f
        w = h * k
    }
    w = w.coerceAtMost(1f)
    val cx = (v.l + v.r) / 2f
    val cy = (v.t + v.b) / 2f
    return CropView(
        (cx - w / 2f).coerceIn(0f, (1f - w).coerceAtLeast(0f)),
        (cy - h / 2f).coerceIn(0f, (1f - h).coerceAtLeast(0f)),
        w, h, v.rot,
    )
}

/**
 * ★ 旋转 90°（E2-12c 修正）：画面转，框**跟着走**。
 *
 * - [aspect] == null（自由）：框**跟随内容**转 —— 保持"框住哪块原图区域"的语义。
 *   画面顺时针转 90° 后，旧画面的点 (u,v) 落在新画面的 (1−v, u) ⇒ 框 (l,t,w,h) 覆盖的
 *   原图区域在新坐标里是 **(1−t−h, l, h, w)**（宽高互换 + 位置随画面转）。
 *   数学上恒不越界（右边界 = 1−t ≤ 1、下边界 = l+w ≤ 1，前提是原框不越界）。
 * - 锁定比例：保持**中心**不变、按**新朝向**重排成该比例（[cropViewWithAspect]，放不下则
 *   整体缩小）⇒ 旋转后导出的像素比仍等于锁定比例。**不跟随内容**：跟随内容会把框挪走，
 *   用户选的比例反而"跳"到别处。
 *
 * [viewW]/[viewH] = **旋转后**画面的像素尺寸（rot 奇数 ⇒ 宽高交换），仅供锁定比例换算是用。
 *
 * 为什么必须重排（E2-12c 的真 bug）：以前这里只改 rot、框不动 ⇒「锁 1:1 → 旋转 90° → 直接
 * 确认」导出 2313×4080（比值 0.567）——画面朝向变了而框没重排，锁定比例静默失效。
 */
internal fun cropViewRotate(
    v: CropView,
    aspect: Float? = null,
    viewW: Int = 1,
    viewH: Int = 1,
): CropView {
    val r = normalizeRot(v.rot + 1)
    if (aspect == null) return CropView(1f - v.t - v.h, v.l, v.h, v.w, r)
    return cropViewWithAspect(v.copy(rot = r), aspect, viewW, viewH)
}

/**
 * 裁剪框（旋转后画面归一化）→ 引擎入参 [CropSpec]（**原图** UV 子矩形 + rot）。
 *
 * 推导：输出坐标 o → 原图 UV 的映射是 `origin + R(o)·size`，其中 R 是 kFsStyle 的
 * `cropUV` 用的那个 90° 置换。R 作用在矩形上仍是矩形 ⇒ 只需换算四个边界。
 */
internal fun cropSpecOf(v: CropView, srcW: Int, srcH: Int): CropSpec {
    val l = v.l.coerceIn(0f, 1f)
    val t = v.t.coerceIn(0f, 1f)
    val w = v.w.coerceIn(0f, 1f - l)
    val h = v.h.coerceIn(0f, 1f - t)
    return when (normalizeRot(v.rot)) {
        1 -> CropSpec(t, 1f - l - w, h, w, 1)
        2 -> CropSpec(1f - l - w, 1f - t - h, w, h, 2)
        3 -> CropSpec(1f - t - h, l, h, w, 3)
        else -> CropSpec(l, t, w, h, 0)
    }
}

/** [cropSpecOf] 的逆：已确认的裁剪 → 再次进入裁剪模式时的初始草稿框。 */
internal fun cropViewOf(spec: CropSpec): CropView = when (normalizeRot(spec.rot)) {
    1 -> CropView(1f - spec.y0 - spec.ch, spec.x0, spec.ch, spec.cw, 1)
    2 -> CropView(1f - spec.x0 - spec.cw, 1f - spec.y0 - spec.ch, spec.cw, spec.ch, 2)
    3 -> CropView(spec.y0, 1f - spec.x0 - spec.cw, spec.ch, spec.cw, 3)
    else -> CropView(spec.x0, spec.y0, spec.cw, spec.ch, 0)
}

/** 裁剪框在屏幕上的矩形（[fit] = **旋转后画面**的 Fit 显示矩形，见 [imageFitRect]）。 */
internal fun cropScreenRect(v: CropView, fit: ImageFitRect): ImageFitRect = ImageFitRect(
    fit.left + v.l * fit.width,
    fit.top + v.t * fit.height,
    v.w * fit.width,
    v.h * fit.height,
)

/** 8 个手柄的屏幕坐标（顺序与 [CropHandle] 一致；框太小时手柄会重叠，属可接受）。 */
internal fun cropHandlePoints(v: CropView, fit: ImageFitRect): List<Pair<Float, Float>> {
    val s = cropScreenRect(v, fit)
    val cx = s.left + s.width / 2f
    val cy = s.top + s.height / 2f
    val r = s.left + s.width
    val b = s.top + s.height
    return listOf(
        s.left to s.top, cx to s.top, r to s.top,
        r to cy,
        r to b, cx to b, s.left to b,
        s.left to cy,
    )
}

/**
 * 命中测试：触摸点落在哪个手柄上（距离 ≤ [touchPx]，取最近的一个）。
 * null = 不在任何手柄上（调用方按"框内拖动 / 框外忽略"处理）。
 */
internal fun cropHitHandle(
    v: CropView,
    fit: ImageFitRect,
    x: Float,
    y: Float,
    touchPx: Float,
): CropHandle? {
    val pts = cropHandlePoints(v, fit)
    var best: CropHandle? = null
    var bestD = touchPx * touchPx
    CropHandle.entries.forEachIndexed { i, h ->
        val (px, py) = pts[i]
        val d = (px - x) * (px - x) + (py - y) * (py - y)
        if (d <= bestD) {
            bestD = d
            best = h
        }
    }
    return best
}

/** 触摸点是否在裁剪框内（框内拖动 = 移动整个框） */
internal fun cropInside(v: CropView, fit: ImageFitRect, x: Float, y: Float): Boolean {
    val s = cropScreenRect(v, fit)
    return x >= s.left && x <= s.left + s.width && y >= s.top && y <= s.top + s.height
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

// ─────────────────────────────────────────────────────────────────────────────
// E3-1a 预设（Preset）：**稀疏字段集** + schema 版本 + prefs 单键 JSON + 保存 / 套用 / 删除
// P-1：线格式的键从**槽位索引**改为**语义名**（schema 2，见 [PRESET_SCHEMA] / [FilmParamKeys]）；
//      内存模型（`Map<Int, Double>`）与套用 / 强度链路**零改动**。
//
// 为什么不是"整包 96 槽快照"（`ADR-19` 那版，**已被用户否掉**，见调研报告 §5.2 方案 A）：
//  ① 全量覆盖会**清掉用户当前的调整**，而专业语义是"只改这条预设管的那几项"——
//     Lightroom 的 Check All / Check None 逐项勾选、RawTherapee 的 `.pp3` 只写被编辑过的字段；
//  ② `[39..59]` 是 **RAW 解码 / 拍摄链路**参数（sharpen / gamut / hr_raw / rcd_* / pc3d …），
//     它们是"相机属性"而不是"风格"，套用会把别人的解码参数一起带过来；
//  ③ 96 槽全量 ≈1.3 KB/个，稀疏 v1 ≈300–700 B/个（调研 §5.3）；P-1 改语义名后**实测 ≈750 B/个**
//     （口径与数字见 `docs/reports/p1-preset-schema-REPORT.md`）。
//
// 语义来源：RawTherapee `saveToKeyfile(!pedited || edited.x)` —— **缺失的 key = 保留目标当前值**。
// 这就是"部分套用"的全部实现：按 idx 覆盖 [presetSnapshot] 里记下的那几个槽，其余不动。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 预设格式版本。沿革：
 *  - **1**（E3-1a）：线格式用**槽位索引**当键（`"slots":{"26":55.0}`）；
 *  - **2**（P-1）：线格式改用**语义名**（`"params":{"grain_amount":55.0}`）。
 *
 * ★ 为什么改语义名（`docs/reports/preset-DESIGN.md` §7 裁决 A1）：本仓库**槽位语义漂移已真实
 * 发生两次** —— `[5..10]` 从"高光软肩 / 阴影 toe"重定义为 filmic latitude（`FilmParams.h:34-46`）、
 * `[26][27]` 颗粒语义修正（`:54-64`）。索引键 JSON 在漂移后**无法分辨** `"5":0.0` 是旧 shoulder
 * 还是新 latitude ⇒ **信息丢失、无法自动迁移**；语义名自带含义，迁移函数才写得出来。
 *
 * ★ **schema 2 起，槽位语义变更不再需要 +1**（那正是本卡的目的）：读取侧按**名字**认参数。
 * 纯线格式字段增删（如 v2 新增 `note`）也**不**升版本：读取侧对未知**结构**字段本就宽容。
 * 会改变既有名字含义的改动（重命名 / 重定义某槽语义）仍需 +1 并写迁移（见 [presetSchemaUsable]）。
 */
internal const val PRESET_SCHEMA = 2

/**
 * 预设在 prefs 里的键：单键 JSON 数组（与 `LutLibrary` 的 `lut_cats` 等同一套 prefs 风格）。
 *
 * 键名**不跟着 schema 改**：版本判定由每项的 `schema` 字段承担，改键名等于主动丢弃用户数据
 * 且没有任何收益（旧数据反正一律被拒绝）。
 */
internal const val PRESET_KEY = "presets_v1"

/**
 * 预设数量上限：语义名格式**实测 ≈750 B/个**（v1 索引键格式 ≈450 B/个）⇒ 100 个 ≈ 75 KB，
 * SharedPreferences 无压力。实测口径与数字见 `docs/reports/p1-preset-schema-REPORT.md`。
 */
internal const val PRESET_MAX = 100

/** [EditorPreset.source] 的取值：用户自建。 */
internal const val PRESET_SOURCE_USER = "user"

/** [EditorPreset.source] 的取值：**随包分发的内置胶片预设**（E3-4 用；本卡只定常量）。 */
internal const val PRESET_SOURCE_BUILTIN = "builtin"

/** [EditorPreset.source] 的取值：**从外部文件导入**（E3-2 的 `.fkpreset` 用；本卡只定常量）。 */
internal const val PRESET_SOURCE_IMPORTED = "imported"

/**
 * 不进预设的槽位区间：**[39..59] = RAW 解码 / 拍摄链路**（`FilmParams.h`：
 * sharpen / gamut / white_hl / hr_raw / rcd_cdfix / defringe / moire / hl_grecon / ca_corr /
 * chroma_gate / df_gate / pc3d / rcd_g_orig / hl_rb_recon / color_smooth / rcd_p3_split）。
 *
 * 判据是"这是**解码属性**还是**风格**"（调研 §5.1 D1）：解码参数描述的是"这张 RAW 怎么解"，
 * 与用户想要的观感无关；把它们存进预设，套用时会把拍摄者的解码设定强加给另一张照片。
 */
private val PRESET_DECODE_SLOTS = 39..59

/** [34] LUT 强度在 `slots` 里的下标（**不参与稀疏化**，见 [presetSlotIncluded]）。 */
private const val PRESET_LUT_STRENGTH_SLOT = 34

/**
 * 不进预设的槽位区间：**[79..87] = 局部调整**（`local_type` / 中心 / 半径 / 羽化 / 角度 /
 * 宽度 / 局部 EV / 局部饱和度）。
 *
 * 判据是"**构图相关**还是**风格**"（调研 §5.1 D7）：局部调整的坐标虽然归一化，但"画面哪块
 * 该亮"依赖**具体画面内容** —— 套到另一张照片上，那块区域通常什么都不是。
 * 证据：Lightroom 真实预设文件里**没有** crop / mask 字段（§3.1b 实测），Adobe 官方也把
 * 几何类设置排除在 Amount 之外；Capture One 的局部走的是另一条通道（Style Brush）。
 * ⇒ **v1 不做局部调整入预设**（要局部风格请用「套用原片风格」，那是同一张片子的参数）。
 */
private val PRESET_LOCAL_SLOTS = 79..87

/**
 * 不进预设的槽位：**引擎模式旋钮 / 标定旋钮 / 预览辅助开关 / 色彩科学状态**
 * （P-1 修正 + P-5 复核新增，见 `preset-DESIGN.md` §7 裁决 A3）。逐条判据如下，都不是"口味"问题：
 *
 *  - **[30] `zebra_thr`**（`FilmParams.h:72-73` 原文："过曝提示阈值（显示域亮度，0 = 关）：
 *    **仅预览 passOutput 使用**，成片一律 0"）—— 它是**预览辅助开关**。而编辑器链路的
 *    `passStyle` **也读它** ⇒ 存进预设后套用会**凭空打开 / 关闭别人的过曝斑马纹**：
 *    用户看到的画面变化与"风格"毫无关系，且从 UI 上找不到原因（本卡修掉的真实缺陷）。
 *  - **[35] `auto_tone`**（`FilmParams.h:96` 原文："0 = 关（黑白点用 [37]/[38]，默认）；
 *    1 = 成对自动"）—— **引擎模式旋钮**（黑白点自动 / 手动二选一），不是风格；且它的实际
 *    取值受用户设置与拍摄链路影响（`CameraController` 的 prefs 覆盖层）⇒ 存进预设 =
 *    把别人的引擎开关带过来。
 *  - **[36] `local_tone`**（`FilmParams.h:102` 原文："0 = 关；>0 = 暗部线性提亮比例
 *    （**标定旋钮**）"）—— **引擎标定参数**：同一张照片在不同标定下观感不同，它不是
 *    "可分享的风格"，而是"这台设备这条链路的调校量"。
 *  - **[28] `tone_strength`**（`CameraPresets.kt:59-61` 原文："1.0 = 开（黑位 + 中灰锚定 S +
 *    高光 rolloff，**非风格化**；治'发灰'）。0 = 恒等/旧行为"）—— 同类的**色彩科学状态**：
 *    它是"这条链路的基础影调修不修"的开关，不是风格。且 `CameraController.applyTonePref`
 *    按 prefs（`base_tone`，设置页「基准影调」）**重写它** ⇒ 存进预设等于把别人的引擎开关
 *    带过来（与 [35] 同型），用户从 UI 上找不到画面变化的原因。
 *  - **[31..33] `look_strength` / `look_sat` / `look_chroma`**（`CameraPresets.kt:66-88`
 *    原文："31..33 = 色彩外观层（M3 P1）…… v0.7.7：1.35 → **1.0（恒等）**…… v0.7.9：
 *    设置页「色彩外观层」开关已删除；31..33 槽位保留（FilmParams 契约不移位）"）——
 *    同类的**色彩科学标定层**：它是对"寡淡"的补偿量（随机型 / 影调曲线标定），不是用户
 *    可分享的风格；且 v0.7.7 已回恒等、设置页开关 v0.7.9 已删除 ⇒ **UI 上根本没有入口能改
 *    它**，存进预设只会是"从别处悄悄带来的标定值"。
 *
 * ⚠ **[28] / [31..33] 的恒等值都是 1.0**（不是 0，见 [CameraPresets.standard]）⇒ 排除它们
 * **不会**改变"用户没改过的参数"的行为：稀疏化本来就按恒等值丢弃（[presetSparseSlots]），
 * 且 [28] 在拍照侧还会被 `CameraController` 的 prefs 覆盖层重写。实测见
 * `docs/reports/p5-preset-scope-REPORT.md`（内置三条不含这些槽）。
 *
 * 为什么单列一个集合而不是散在 [presetSlotIncluded] 的布尔表达式里：这些的判据是**同一类**
 *（"引擎状态，不是用户风格"），将来再加同类槽位只需往这里加一个数字。
 */
private val PRESET_ENGINE_KNOB_SLOTS = setOf(28, 30, 31, 32, 33, 35, 36)

/**
 * [29] = **已退役的槽位**（原 `hl_recon`，v0.10.15 删除；`FilmParams.h:68-71`："索引 29 留空不复用"）。
 *
 * 为什么必须排除（P-1 由护栏测试 `FilmParamKeysTest.everyStorableSlotHasASemanticName` 发现）：
 * 引擎里已经没有这个参数，[FilmParamKeys] 也**不给它名字**（不映射）⇒ 它一旦进了 `slots`，
 * 序列化时只能被跳过 = **静默丢数据**。而 `CameraPresets.standard()[29]` 仍是历史遗留的 **1.0**
 * ⇒ 只要有人在编辑器里改到它（或将来某个版本复用索引），这个洞就会真的漏。
 * 三层口径因此对齐：`FilmParams.h` 无名 → [FilmParamKeys] 不映射 → **不进预设**。
 */
private const val PRESET_RETIRED_SLOT = 29

/**
 * 槽位是否参与预设（稀疏化的白名单判据）。排除**十类**：
 *  - **[34] LUT 强度**：由 [EditorPreset.lutId] / [EditorPreset.lutStrength] 承载。
 *    ⚠ 这条不是洁癖，是**必须**：[editorOpenParams] 打开编辑器时把 [34] 归 **0**（成片已含
 *    拍摄时的 LUT 混合，不能再叠一遍）⇒ 若 [34] 参与稀疏化，**每一条预设都会存下 "34":0**，
 *    套用时把当前 LUT 的强度打成 0 —— 用户刚选的 LUT 被静默关掉（"套了预设，滤镜没了"）。
 *  - **[28] / [30] / [31..33] / [35] / [36]**：基准影调开关 / 预览辅助开关 / 色彩外观层 /
 *    引擎模式旋钮 / 标定旋钮（[PRESET_ENGINE_KNOB_SLOTS]，逐条判据与证据写在该常量的
 *    KDoc 里）。判据是**同一类**：**引擎状态，不是用户风格**。
 *  - **[29]**：已退役的槽位，无语义名（[PRESET_RETIRED_SLOT]）。
 *  - **[39..59]**：RAW 解码 / 拍摄链路（[PRESET_DECODE_SLOTS]）。
 *  - **[79..87]**：局部调整（[PRESET_LOCAL_SLOTS]，构图相关，v1 不做）。
 *  - 数组越界（坏数据 / 未来契约变更）。
 *
 * ⚠ 本函数是**策略层**（"哪些槽进预设"），与 [FilmParamKeys]（"槽位叫什么名字"，机械的一层）
 * 是两件事：进了预设的槽**必然**有语义名（单测钉住这条），但有语义名的槽**不一定**进预设。
 */
internal fun presetSlotIncluded(i: Int): Boolean =
    i in 0 until CameraPresets.COUNT &&
        i != PRESET_LUT_STRENGTH_SLOT &&
        i != PRESET_RETIRED_SLOT &&
        i !in PRESET_ENGINE_KNOB_SLOTS &&
        i !in PRESET_DECODE_SLOTS &&
        i !in PRESET_LOCAL_SLOTS

/**
 * ★ 槽位 ↔ **语义名**映射（`docs/reports/preset-DESIGN.md` §7 裁决 A1）：线格式的键名来源，
 * **全代码唯一维护点**。
 *
 * 名字**逐字取自 `core/engine/src/film/FilmParams.h` 的字段名**（该文件形如
 * `float grain_amount = 0.0f;  // 26`，字段名与槽位号写在同一行）。这样做的收益是**别的工具能
 * 复用同一套名字**：`tools/check-style-passthrough.py` 的白名单、`EditorStyleWhitelistTest`
 * 的 `p.<字段>` 对拍、本表的单测，三方认的是同一个标识符。
 *
 * 规则（机械的一层，无例外）：
 *  - **覆盖所有在 `FilmParams.h` 里有名字的槽位**（95 个），不只是进预设的那些 ——
 *    "哪些槽进预设"是 [presetSlotIncluded] 的**另一层独立策略**，两者不要混在一起；
 *  - 数组字段（`float shoulder[3]` / `float cross[9]` / `float interlayer_*[3]`）逐元素展开成
 *    `字段名[下标]`（`shoulder[0]` … `cross[8]`）。**不用** R/G/B 这类只存在于注释里的后缀：
 *    从注释里读语义不可机械校验，而 `字段名[下标]` 能由 `float x[N] = …; // <起>..<末>` 一行
 *    正则直接对拍（见 `FilmParamKeysTest`）；
 *  - **[29] 不映射**：它曾经是 `hl_recon`，v0.10.15 删除后**留空不复用**（`FilmParams.h:68-71`），
 *    现在没有任何字段名 ⇒ 没有名字可给，也不该为它发明一个；
 *  - **[95] `glow_strength` 在表内**：它在 `FilmParams.h` 里**确实有字段名**。
 *    ⚠️ SG-1（v0.11.17）前它叫 `reserved_95`（恒等占位、零引用）；本卡把预留槽启用为
 *    柔光强度 ⇒ 名字随之改为字段名（表 = "有名字的槽位"这条规则不变，只是那个槽位
 *    现在真的有消费者了）。它默认 0 = 整链旁路 ⇒ 稀疏化时被恒等值丢弃，正常路径下
 *    仍不会出现在任何预设的 `params` 里。
 *
 * 内存模型**不变**：`EditorPreset.slots` 仍是 `Map<Int, Double>`（索引键），本表只在
 * [presetToJson] / [presetFromJson] 两个边界上做翻译 ⇒ `presetSnapshot` / `scalePresetSlots` /
 * `applySnapshotParams` / `editorOpenParams` 零改动。
 */
internal object FilmParamKeys {

    /** 槽位 → 语义名（**唯一维护点**；名字逐字等于 `FilmParams.h` 的字段名） */
    val bySlot: Map<Int, String> = mapOf(
        // [0..4] 基础调整（曝光 / 白平衡增益 / 对比度）
        0 to "exposure_ev",
        1 to "wb_r",
        2 to "wb_g",
        3 to "wb_b",
        4 to "contrast",
        // [5..10] filmic latitude（per-channel：R/G/B 两层乳剂）
        5 to "shoulder[0]",
        6 to "shoulder[1]",
        7 to "shoulder[2]",
        8 to "toe[0]",
        9 to "toe[1]",
        10 to "toe[2]",
        // [11..19] log 域染料串扰（3×3 矩阵）
        11 to "cross[0]",
        12 to "cross[1]",
        13 to "cross[2]",
        14 to "cross[3]",
        15 to "cross[4]",
        16 to "cross[5]",
        17 to "cross[6]",
        18 to "cross[7]",
        19 to "cross[8]",
        // [20..28] 分区饱和 / 分离色调 / 单色 / 颗粒 / 基准影调
        20 to "sat_shadow",
        21 to "sat_mid",
        22 to "sat_high",
        23 to "split_warm",
        24 to "split_cool",
        25 to "monochrome",
        26 to "grain_amount",
        27 to "grain_size",
        28 to "tone_strength",
        // [29] 无字段名（已删除的 hl_recon，索引留空不复用）⇒ 不映射
        // [30..36] 预览辅助 / 外观层 / LUT 强度 / 引擎模式旋钮
        30 to "zebra_thr",
        31 to "look_strength",
        32 to "look_sat",
        33 to "look_chroma",
        34 to "lut_strength",
        35 to "auto_tone",
        36 to "local_tone",
        // [37..59] filmic 黑白点 / 锐化 / gamut / RAW 解码链路
        37 to "filmic_black_ev",
        38 to "filmic_white_ev",
        39 to "sharpen",
        40 to "gamut",
        41 to "hr_wavelet",
        42 to "white_hl",
        43 to "white_floor",
        44 to "white_probe",
        45 to "hr_raw",
        46 to "rcd_cdfix",
        47 to "defringe",
        48 to "moire",
        49 to "hl_grecon",
        50 to "ca_corr",
        51 to "chroma_gate",
        52 to "cg_scope",
        53 to "df_gate",
        54 to "pc3d",
        55 to "pc3d_msize",
        56 to "rcd_g_orig",
        57 to "hl_rb_recon",
        58 to "rcd_p3_split",
        59 to "color_smooth",
        // [60..68] 颗粒调制 / halation / 暗角
        60 to "grain_midtone_bias",
        61 to "grain_pivot",
        62 to "halation_strength",
        63 to "halation_threshold",
        64 to "halation_radius",
        65 to "halation_warmth",
        66 to "vignette_strength",
        67 to "vignette_radius",
        68 to "vignette_feather",
        // [69..77] 层间效应（三区 × R/G/B）
        69 to "interlayer_shadow[0]",
        70 to "interlayer_shadow[1]",
        71 to "interlayer_shadow[2]",
        72 to "interlayer_mid[0]",
        73 to "interlayer_mid[1]",
        74 to "interlayer_mid[2]",
        75 to "interlayer_high[0]",
        76 to "interlayer_high[1]",
        77 to "interlayer_high[2]",
        // [78..87] 清晰度 + 局部调整
        78 to "clarity",
        79 to "local_type",
        80 to "local_center_x",
        81 to "local_center_y",
        82 to "local_radius",
        83 to "local_feather",
        84 to "local_angle",
        85 to "local_width",
        86 to "local_ev",
        87 to "local_sat",
        // [88..94] 色温 / 色调 / 影调分区 / 褪色
        88 to "temp_mired",
        89 to "tint",
        90 to "highlights",
        91 to "shadows",
        92 to "whites",
        93 to "blacks",
        94 to "fade",
        // SG-1（v0.11.17）柔光：**[95] 是预留槽 `reserved_95` 的正式启用**（名字随之
        // 改为字段名 `glow_strength`），+[96] 半径 / [97] 阈值。
        95 to "glow_strength",
        96 to "glow_radius",
        97 to "glow_threshold",
    )

    /**
     * 语义名 → 槽位。**由 [bySlot] 派生**（不是第二份手写表）⇒ 结构上不可能与它不一致；
     * 单测仍断言双向恒等，防的是"有人把这里改成手写表"。
     */
    val byName: Map<String, Int> = bySlot.entries.associate { (i, name) -> name to i }
}

/**
 * 一条预设（调研 §5.2 方案 B 的 Kotlin 形态）。
 *
 * 字段与 JSON 一一对应（见 [presetToJson]），**结构上就能直接序列化成单文件**（E3-2 的
 * `.fkpreset` 直接复用本结构，不依赖 prefs 的键命名）。
 *
 * @param slots ★ **稀疏**：只含与恒等值（[CameraPresets.standard] 的逐槽值）不同的槽。
 *   "缺失 = 不套用该槽"（RawTherapee 语义）⇒ 天然支持"这条预设只改色彩"。
 *   ★ **内存里是索引键**（`Map<Int, Double>`）：线格式用语义名（[FilmParamKeys]），翻译只发生在
 *   [presetToJson] / [presetFromJson] 两个边界上 ⇒ 套用 / 强度 / 打开编辑器那几条链路零改动。
 * @param note 用户说明（可选，缺省空串）。v2 新增字段：读取侧缺失即空，写出侧**恒写**
 *（空串也写）—— 线格式字段固定，diff / 人工核对更省事，代价只有 11 字节。
 * @param lutId 资源引用（调研 §5.1 D2：预设必须带资源引用）。**null = 这条预设不含 LUT**
 *   ⇒ 套用时**不动**当前 LUT（缺失字段 = 保留当前值）；非 null 但资产已不在库中 ⇒
 *   降级为"无 LUT + 提示"（见 EditorScreen 的 applyPreset）。
 * @param lutStrength LUT 强度（0..1.5，与 [LUT_STRENGTH_UI_RANGE] 同量程）。
 */
internal data class EditorPreset(
    val id: String,
    val name: String,
    val group: String = "",
    val favorite: Boolean = false,
    val createdAt: Long = 0L,
    val source: String = PRESET_SOURCE_USER,
    val note: String = "",
    val slots: Map<Int, Double> = emptyMap(),
    val lutId: String? = null,
    val lutStrength: Double = 1.0,
)

/**
 * 预设 id（**稳定**：重命名不改，删除/更新按它定位）：`p_<毫秒>_<salt 的 36 进制>`。
 *
 * 带 salt 的理由：id 撞车会让"删一条"变成"删两条"。salt 由调用侧给（`Random.nextInt()`），
 * 本函数保持纯逻辑 ⇒ JVM 单测可断言格式与稳定性。
 */
internal fun presetId(now: Long, salt: Int): String =
    "p_${now}_${Integer.toString(salt and 0xFFF, 36)}"

/**
 * schema 版本判定：**只认 [PRESET_SCHEMA]，其它版本一律拒绝该预设**（跳过 + 诊断日志），
 * **不做"尽力降级套用"**。
 *
 * 为什么拒绝而不是降级：`FilmParams.h:34-46` 记录过一次真实的**槽位语义漂移** ——
 * `[5..10]` 从"高光软肩 / 阴影 toe（0 = 关）"**重定义**为"filmic latitude（0.01 = darktable
 * 默认，**0 = 极端 S 曲线**）"：同一批数值在新语义下是**完全不同的画面**。
 * 不知道语义就套用 = 静默给出错误观感（用户根本不知道画面为什么不对），而"少一条预设 +
 * 一条可读的诊断日志"是可解释、可恢复的失败。
 *
 * ★ **schema=1 一律拒绝，且本卡不写迁移函数**（裁决 A1）：v1 的键是槽位索引，`"5":0.0` 在
 * 漂移后**无法分辨**是旧 shoulder 还是新 latitude ⇒ 迁移函数在信息论上就写不出来（这正是
 * 改语义名的原因）。而真实设备上**不存在** v1 数据：预设功能首次出现在 v0.11.11（versionCode
 * 360），用户明确未安装该包测试 ⇒ 拒绝的代价是零。
 *
 * 将来若真要迁移（E3-2 导入外部文件），本函数变成"**有迁移路径**的旧版本也返回 true"，
 * 迁移在 [parsePreset] 里按版本分支做（darktable `legacy_params` 模式）。
 */
internal fun presetSchemaUsable(schema: Int?): Boolean = schema == PRESET_SCHEMA

/**
 * 稀疏化（保存侧的核心）：把当前参数压成"**只含与恒等值不同的槽**"的字典。
 *
 * ⚠ **恒等值必须逐槽取 [CameraPresets.standard]，绝不能假设 0**：本仓库大量槽位的恒等值
 * 不是 0 —— `wb_* [1..3]` / `contrast [4]` / `sat_* [20..22]` / `lut_strength [34]` /
 * `interlayer_* [69..77]` 是 **1.0**，`shoulder/toe [5..10]` 是 **0.01**（latitude 语义下
 * 0 = 极端 S 曲线），`cross [11..19]` 是**单位阵**，`filmic_black_ev [37]` 是 −7.65。
 * 用 0 当恒等值会把"用户没动过的槽"整片存进预设，套用时把画面打回默认。
 *
 * 容差用 [PARAM_EPS]（与 `paramsUnchanged` **同一份口径**）：Float→Double 的精度噪声
 *（`0.01f.toDouble()` = 0.009999999776…）不会被误判成"改过"。
 *
 * 非有限值（NaN/Inf）一律丢弃：坏数据不进库（读取侧还有第二道防线）。
 */
internal fun presetSparseSlots(
    params: DoubleArray,
    neutral: DoubleArray = CameraPresets.standard(),
): Map<Int, Double> {
    val out = LinkedHashMap<Int, Double>()
    val n = min(params.size, neutral.size)
    for (i in 0 until n) {
        if (!presetSlotIncluded(i)) continue
        val v = params[i]
        if (!v.isFinite()) continue
        if (abs(v - neutral[i]) > PARAM_EPS) out[i] = v
    }
    return out
}

/**
 * 套用映射（**部分套用的全部逻辑**）：以 [base] 为底，只把 [slots] 里记下的槽覆盖上去，
 * 其余槽保持 [base] 的值 —— 这就是 RawTherapee 的"缺失 key = 保留当前值"。
 *
 * 返回**新数组**（不是原地写）：调用侧统一走 `applySnapshotParams(current, presetSnapshot(...))`
 *（E2-21 的整槽下发入口，复用不重写），于是"原地写、不换数组对象"这条纪律只有一处实现
 *（滑杆组件持有 `params` 的引用，换对象 = 读数与槽位脱钩）。
 *
 * 越界 / 非有限的槽**逐槽跳过**（不是整条预设作废）：未知槽位忽略正是调研 §5.7 第 3 条。
 */
internal fun presetSnapshot(base: DoubleArray, slots: Map<Int, Double>): DoubleArray {
    val out = base.copyOf()
    for ((i, v) in slots) {
        if (i in out.indices && v.isFinite()) out[i] = v
    }
    return out
}

// ─────────────────────────────────────────────────────────────────────────────
// E3-1c 预设强度（Amount）：**参数域缩放**（调研报告 `docs/reports/editor-preset-RESEARCH.md` §5.6）
//
// 强度有两条互斥路线，走哪条**由参数的编码方式决定**（不是口味问题）：
//  - **输出混合**（darktable `out = a*(1-mask)+b*mask`、RawTherapee `intp(strength, clut, 原像素)`）：
//    它们的参数是二进制 blob（只能整块替换）⇒ 只能把"原像素"与"套用后的像素"混合；
//  - **参数域缩放**（Lightroom `SupportsAmount` / 150% 参数算例）：参数是命名键值 ⇒ 逐槽插值。
// 我们的 96 槽是**命名槽位**（每槽有明确语义与恒等值）⇒ 与 Lightroom 同构 ⇒ 走参数缩放。
// 收益：**零额外渲染** —— 不需要第二个 pass，也不会与 LUT / 颗粒这些"引擎内本来就带混合系数"
// 的效果叠加两次（`[34] lut_strength` 本身就是引擎里的混合系数）。
//
// 公式（§5.6）：`p_new[i] = neutral[i] + (p_preset[i] - neutral[i]) * amount`
// ⚠ **相对恒等值 [neutral]，不是相对 0、也不是相对当前值**：本仓库大量槽的恒等值是 **1.0**
//（`wb_* [1..3]` / `contrast [4]` / `sat_* [20..22]` / `lut_strength [34]` /
// `interlayer_* [69..77]`）—— 相对 0 缩放的后果是：对比度预设 1.5、强度 50% ⇒ 0.75
//（**画面变灰**），而正确值是 **1.25**。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 强度 UI 量程 **0..1.5**（读数 0..150，[formatZero150]）：与 LUT 强度
 * [LUT_STRENGTH_UI_RANGE] / 拍照页的 LUT 强度滑杆**同一口径**（E2-15 用户拍板"统一可调至 150"）。
 *
 * `> 1` 是**外推**：把预设相对恒等值的偏离量放大到 1.5 倍。数学上天然成立（与 LUT 强度
 * `t > 1` 同理），但一部分槽位有**契约边界**（暗角强度 −1..0、LUT 强度 0..1.5、影调分区 ±1 …）
 * ⇒ 外推路径逐槽夹紧（[presetSlotClamp]）。
 */
internal val PRESET_AMOUNT_UI_RANGE = 0f..1.5f

/**
 * 局部调整（[79..87]）的最低套用强度：低于它 ⇒ **整条局部通道不套用**（保持目标当前值）。
 *
 * 为什么需要阈值：局部通道里 [79] 是**离散枚举**（0 关 / 1 径向 / 2 线性），枚举不能插值
 *（§5.6 表末行："amount < 阈值 ⇒ 整体不套用局部"）；而 [80..85] 是**构图几何**（中心 / 半径 /
 * 羽化 / 角度 / 宽度）—— 强度不该移动用户圈好的区域（否则"减弱预设"会变成"挪走我的局部"）。
 * 两者都只能"要么按原值、要么不套用"，阈值取 **0.5**：半强度以下不值得开启一个离散效果，
 * 且这是保守方向（不套用 = 不动用户当前的设置）。
 *
 * ⚠ v1 的预设**不含局部通道**（[presetSlotIncluded] 排除了 [79..87]）⇒ 这段判定是防御性的
 *（手改 JSON、或未来把局部纳入预设时才走到）。
 */
internal const val PRESET_LOCAL_MIN_AMOUNT = 0.5

/** 局部通道里"按公式缩放"的第一槽：[79..85] 是离散枚举与几何（原值），[86][87] 是强度。 */
private const val PRESET_LOCAL_STRENGTH_SLOT = 86

/**
 * 非线性曲线 / 形状类槽位：**[5..10]** filmic latitude（0.01 = darktable 默认，**0 = 极端
 * S 曲线**）+ **[37][38]** filmic 黑白点（EV）。
 *
 * 为什么不缩放（§5.6 表："建议 v1 不参与缩放"）：这些槽的语义是**非线性**的 —— latitude
 * 0.01 → 0.02 的视觉差异远大于 0.5 → 0.51，线性插值在 0 附近会产生剧烈且方向不可预期的变化。
 * 规则：**amount > 0 ⇒ 原值套用**（形状不随强度变）；**amount = 0 ⇒ 回恒等值**（与其它槽一致地
 * "完全不套用"）。这样从 100% 往左拖时曲线形状不动、其余参数变弱，1.0 附近没有跳变。
 */
private fun presetSlotCurve(i: Int): Boolean = i in 5..10 || i == 37 || i == 38

/**
 * "**0 = 关**"型的槽：效果量**就是槽值本身**（不是"相对恒等值的偏离"）⇒ 缩放用 `v * amount`。
 *
 * 只有 **[34] LUT 强度**一条：它的恒等值在 [CameraPresets.standard] 里写的是 **1.0**（拍摄链路
 * 默认全量混合），但**编辑器语义里 0 才是"无 LUT"**（[editorOpenParams] 打开编辑器时把 [34]
 * 归 0，引擎侧 `uLutOn = 0` 整段旁路）—— 效果量是"混合了多少"，不是"偏离 1.0 多少"。
 * 若套用通用公式：预设 [34] = 1.0、强度 50% ⇒ 结果仍是 1.0（**强度滑杆对 LUT 完全无效**）。
 */
private fun presetSlotZeroBased(i: Int): Boolean = i == PRESET_LUT_STRENGTH_SLOT

/**
 * 逐槽合法范围（**只在 `amount > 1` 的外推路径上使用**）。取值两个来源：
 *  - **UI 量程常量与映射函数**（[EXP_UI_RANGE] / [WB_GAIN_UI_RANGE] / [SAT_UI_RANGE] /
 *    [CLARITY_UI_RANGE] / [LUT_STRENGTH_UI_RANGE] / [TINT_UI_RANGE] / [TONE_UI_RANGE] /
 *    [FADE_UI_RANGE] / [LOCAL_EV_UI_RANGE] / [LOCAL_SAT_UI_RANGE]、[vigStrengthSlot] /
 *    [vigRadiusSlot] / [vigFeatherSlot] / [expSlot] / [tempSlot]）—— **与滑杆同一份定义**，
 *    不另抄一遍数字（两处各写一份必然漂移，而漂移的表现是"滑杆拖不到的值却出现在画面里"）；
 *  - **`FilmParams.h` 的契约边界**（Kotlin 侧没有量程常量的那几条：颗粒 / 相纸响应），
 *    逐条标注出处。
 *
 * 为什么只在外推时夹：`0 ≤ amount ≤ 1` 的结果是 `neutral` 与预设值的**凸组合** ⇒ 预设值本身在
 * 范围内时结果必然在范围内（无需夹）。这也让 **`amount = 1.0` 的零回归成为结构性保证**：
 * 外推路径根本不经过 `amount = 1`（[scalePresetSlots] 在入口就原样返回）。
 */
private val PRESET_SLOT_CLAMP: Map<Int, ClosedFloatingPointRange<Double>> = buildMap {
    put(0, expSlot(EXP_UI_RANGE.start)..expSlot(EXP_UI_RANGE.endInclusive))    // ±3 EV
    for (i in 1..3) put(i, slotRange(WB_GAIN_UI_RANGE))
    put(4, CONTRAST_SLOT_MIN..2.0)
    for (i in 20..22) put(i, slotRange(SAT_UI_RANGE))
    put(26, 0.0..100.0)        // FilmParams.h:55-58 颗粒 `strength`（0..100）
    put(27, 20.0..6400.0)      // 同上 `scale` = ISO（20..6400，越大越粗）
    put(34, slotRange(LUT_STRENGTH_UI_RANGE))
    put(60, 0.0..100.0)        // FilmParams.h:369 颗粒相纸响应 midtones_bias（%）
    for (i in 62..65) put(i, slotRange(HALO_GLOW_UI_RANGE))  // 光晕 强度/阈值/半径/暖度（FilmParams.h [62..65]）
    for (i in 95..97) put(i, slotRange(HALO_GLOW_UI_RANGE))  // SG-1 柔光 强度/半径/阈值（FilmParams.h [95..97]）
    put(66, vigStrengthSlot(1f)..vigStrengthSlot(0f))   // −1..0（强度越大越负）
    put(67, vigRadiusSlot(0f)..vigRadiusSlot(2f))       // 0..200（%）
    put(68, vigFeatherSlot(0f)..vigFeatherSlot(2f))     // 0..200（%）
    put(78, slotRange(CLARITY_UI_RANGE))
    put(86, expSlot(LOCAL_EV_UI_RANGE.start)..expSlot(LOCAL_EV_UI_RANGE.endInclusive))  // ±3 EV
    put(87, slotRange(LOCAL_SAT_UI_RANGE))
    put(88, tempSlot(TEMP_UI_RANGE.endInclusive)..tempSlot(TEMP_UI_RANGE.start))  // mired 域（递减 ⇒ 反写）
    put(89, slotRange(TINT_UI_RANGE))
    for (i in 90..93) put(i, slotRange(TONE_UI_RANGE))
    put(94, slotRange(FADE_UI_RANGE))
}

/** UI 量程（Float）→ 槽位量程（Double）：槽位 = UI 值**直通**的那批参数用（见 [PRESET_SLOT_CLAMP]）。 */
private fun slotRange(r: ClosedFloatingPointRange<Float>): ClosedFloatingPointRange<Double> =
    r.start.toDouble()..r.endInclusive.toDouble()

/** 外推夹紧：表里没有的槽原样返回（引擎对它们没有硬边界，越界只是"更浓"）。 */
private fun presetSlotClamp(i: Int, v: Double): Double =
    PRESET_SLOT_CLAMP[i]?.let { v.coerceIn(it.start, it.endInclusive) } ?: v

/**
 * 预设强度（Amount）的**逐类缩放**（§5.6 的表；逐类语义都有单测钉住）。
 *
 * @param slots 预设的稀疏槽位（**满强度**的原值；强度不是预设的一部分）
 * @param amount 强度：0 = 完全不套用 / 1 = 预设原样（零回归）/ 0..1.5（> 1 = 外推）
 * @param neutral 恒等值表（默认 [CameraPresets.standard]，**逐槽取，绝不假设 0**）
 * @return **新 map**（不改入参）：`idx → 缩放后的槽值`。缺键 = 不套用该槽（RawTherapee 语义）。
 *
 * 逐类规则（§5.6 表 + 本卡的三个决定）：
 *
 * | 类别 | 槽位 | 规则 |
 * |---|---|---|
 * | 线性增益 / 强度型（含恒等 1.0 的乘性系数、恒等 0 的强度） | [0..4] [20..33] [35..36] [60..78] [88..95] | ✅ 相对恒等值插值 |
 * | LUT 强度 | [34] | ✅ `v * amount`（"0 = 关"型，见 [presetSlotZeroBased]） |
 * | 矩阵类 | [11..19] [69..77] | ✅ 同一公式（恒等值 = 单位阵 / 1.0 ⇒ 即 `I+(M−I)·k`） |
 * | 非线性曲线 | [5..10] [37] [38] | ⚠️ 不缩放：amount > 0 原值、amount = 0 回恒等 |
 * | 离散枚举 + 构图几何（局部通道） | [79..85] | ❌ 不缩放：amount < 0.5 整条不套用，≥ 0.5 原值 |
 * | 局部强度（仍是线性量） | [86] [87] | ✅ 与第一类同（但同受 0.5 阈值约束） |
 *
 * 三条边界行为：
 *  - `amount` 非有限（NaN/Inf，坏数据）⇒ **原样返回**（按满强度处理）：宁可"没减强度"，也不能让
 *    `neutral + (v−neutral)*NaN = NaN` 写进参数数组（NaN 进引擎 = 画面崩坏）；
 *  - `amount < 0` ⇒ 按 0 处理（不做"反向预设"）；
 *  - 槽值非有限 / 下标越界 ⇒ **丢弃该槽**（与 [presetSnapshot] 的坏数据口径一致）。
 */
internal fun scalePresetSlots(
    slots: Map<Int, Double>,
    amount: Double,
    neutral: DoubleArray = CameraPresets.standard(),
): Map<Int, Double> {
    // ★ amount = 1 ⇒ 原样返回：零回归是**结构性**的（一个缩放分支都不经过），而不是"算出来
    //   恰好相等"。曲线类与局部通道也一并原样通过（含它们的原值语义）。
    if (!amount.isFinite() || amount == 1.0) return slots
    val a = amount.coerceAtLeast(0.0)
    val extrapolate = a > 1.0
    val out = LinkedHashMap<Int, Double>(slots.size)
    for ((i, v) in slots) {
        if (!v.isFinite() || i !in neutral.indices) continue
        // 曲线 / 形状类：不缩放（amount = 0 ⇒ 回恒等；amount > 0 ⇒ 原值）
        if (presetSlotCurve(i)) {
            out[i] = if (a > 0.0) v else neutral[i]
            continue
        }
        // 局部通道：低于阈值整条不套用（离散枚举不能插值 + 几何不该被强度移动）
        if (i in PRESET_LOCAL_SLOTS) {
            if (a < PRESET_LOCAL_MIN_AMOUNT) continue
            if (i < PRESET_LOCAL_STRENGTH_SLOT) {
                out[i] = v          // [79..85]：原值（离散枚举 / 构图几何都不参与缩放）
                continue
            }
        }
        // 通用：相对恒等值插值（外推时逐槽夹紧）
        val scaled = if (presetSlotZeroBased(i)) {
            v * a
        } else {
            neutral[i] + (v - neutral[i]) * a
        }
        out[i] = if (extrapolate) presetSlotClamp(i, scaled) else scaled
    }
    return out
}

/** JSON 数字字面量：坏数据（NaN/Inf）写成 0 —— 绝不写出 `NaN` / `Infinity` 这种非法 JSON。 */
private fun jsonNum(v: Double): String = if (v.isFinite()) v.toString() else "0"

/**
 * 单条预设 → JSON（**键序固定**：便于 diff 与单测逐字断言）。线格式（v2）长这样：
 *
 * ```jsonc
 * {"schema":2,"id":"p_1728543600000_ab12","name":"CineStill 800T","group":"CineStill",
 *  "favorite":false,"createdAt":1728543600000,"source":"user","note":"",
 *  "params":{"grain_amount":55.0,"vignette_strength":-0.18},
 *  "lut":{"id":"cinestill_800t","strength":0.9}}
 * ```
 *
 * 三个刻意的形态（架构师裁决，别顺手改）：
 *  - **`params` 的键是语义名**（[FilmParamKeys]），不是槽位索引 —— 理由见 [PRESET_SCHEMA]；
 *  - **`lut` 是对象** `{id, strength}`（不是 `lut` + `lut_strength` 两个平铺字段）：资源引用天然
 *    是一组，拆平后"有 id 没强度"这种非法组合在结构上就存在了；
 *  - **没有 `base` 段**：恒等值永远取 [CameraPresets.standard]（[presetSnapshot] 的 base），
 *    结构上不可能出现"全 0 数组"这种错误基线。
 *
 * `lut` 为 null 时**省略该键**（不是写 `"lut":null`）：读取侧"缺失 = 无 LUT"，
 * 与"缺失 key = 不套用该字段"的整套语义一致，也少 20 字节。
 *
 * 槽位按**槽位号升序**写出（不是名字字典序）：与 `FilmParams.h` 的物理顺序一致，人工核对时
 * 可以一行一行对着看。
 */
internal fun presetToJson(p: EditorPreset): String = buildString {
    append("{\"schema\":").append(PRESET_SCHEMA)
    append(",\"id\":\"").append(jsonEscape(p.id)).append('"')
    append(",\"name\":\"").append(jsonEscape(p.name)).append('"')
    append(",\"group\":\"").append(jsonEscape(p.group)).append('"')
    append(",\"favorite\":").append(p.favorite)
    append(",\"createdAt\":").append(p.createdAt)
    append(",\"source\":\"").append(jsonEscape(p.source)).append('"')
    append(",\"note\":\"").append(jsonEscape(p.note)).append('"')
    append(",\"params\":{")
    // ⚠ 逗号不能用 forEachIndexed 的下标判：没有语义名的槽会被跳过 ⇒ 下标会跳号（`{,` 非法 JSON）。
    var first = true
    for ((k, v) in p.slots.entries.sortedBy { it.key }) {
        // 没有语义名的槽不写进线格式（[29] / 越界）。正常路径不会出现：[presetSparseSlots] 只产出
        // [presetSlotIncluded] 的槽，而"可入库 ⇒ 有语义名"被单测钉死 ⇒ 这里是防御性跳过。
        val key = FilmParamKeys.bySlot[k] ?: continue
        if (!first) append(',')
        first = false
        append('"').append(key).append("\":").append(jsonNum(v))
    }
    append('}')
    p.lutId?.let {
        append(",\"lut\":{\"id\":\"").append(jsonEscape(it))
            .append("\",\"strength\":").append(jsonNum(p.lutStrength)).append('}')
    }
    append('}')
}

/** 全部预设 → JSON 数组（prefs 单键的存储形态）。 */
internal fun presetsToJson(list: List<EditorPreset>): String =
    list.joinToString(",", "[", "]") { presetToJson(it) }

/**
 * 读取结果：**成功的列表 + 被跳过的项及原因**。
 *
 * 为什么把诊断信息返回而不是直接写日志：日志接口（`EngineBridge.nativeAppendLog`）是 native
 * external，JVM 单测里不可用 ⇒ 由调用侧（[PresetStore]）落日志，纯逻辑这层保持可测
 *（单测直接断言 skipped 的内容，容错行为才钉得住）。
 */
internal data class PresetLoadResult(
    val presets: List<EditorPreset>,
    /** 每项一句人话（含下标 / 原因），进 `preset.load_skipped` 诊断日志 */
    val skipped: List<String> = emptyList(),
)

/**
 * JSON 数组 → 预设列表（**容错读取，绝不抛异常**）。
 *
 * 容错分**三档**（粒度不同，理由也不同）：
 *  - **结构字段坏了 ⇒ 跳过整项**：`schema` 不认识 / 缺 `id` / 缺 `name` / `params` 不是对象
 *    —— 这条预设要么没法用，要么没法被定位（删除 / 更新按 id），留下只会是幽灵条目；
 *  - ★ **未知语义名 ⇒ 跳过整项**（v2 新增的一档）：`params` 的键是 [FilmParamKeys] 里的语义名，
 *    遇到不认识的键 = **这条数据比本版本新**（或被人手改坏）。不能逐槽跳过：那个键代表的参数
 *    我们不知道是什么，忽略它可能给出**另一种风格**且用户无从察觉；而"少一条预设 + 一条可读的
 *    诊断日志"是可解释、可恢复的失败。这与"未知 schema 一律拒绝"（[presetSchemaUsable]）是
 *    同一个逻辑 —— 不知道语义就不要猜；
 *  - **单个槽 / 资源坏了 ⇒ 逐槽降级**：**已知**语义名但槽位被策略排除（[presetSlotIncluded]
 *    为假 —— 这是我们自己的策略，不是数据损坏）、值非数字 / 非有限、等于恒等值（保住"slots 只含
 *    非恒等槽"这条不变式）、`lut` 坏了当"无 LUT" —— 一个槽坏了不该让用户丢掉整条预设。
 *
 * @param raw prefs 原文；null / 空白 = 还没存过（**不是错误**，不产生诊断）
 */
internal fun presetsFromJson(raw: String?): PresetLoadResult {
    if (raw.isNullOrBlank()) return PresetLoadResult(emptyList())
    val skipped = mutableListOf<String>()
    val root = try {
        MiniJson.parse(raw)
    } catch (t: Throwable) {
        return PresetLoadResult(emptyList(), listOf("JSON 解析失败：${t.message}"))
    }
    val arr = root as? List<*> ?: return PresetLoadResult(emptyList(), listOf("顶层不是数组"))
    val out = ArrayList<EditorPreset>(min(arr.size, PRESET_MAX))
    arr.forEachIndexed { i, v ->
        val p = parsePreset(v) { why -> skipped += "item#$i：$why" }
        if (p != null) out += p
    }
    if (out.size > PRESET_MAX) {
        skipped += "超出上限 $PRESET_MAX，丢弃最旧的 ${out.size - PRESET_MAX} 条"
    }
    return PresetLoadResult(out.take(PRESET_MAX), skipped)
}

/** 单项解析（[presetsFromJson] 的每一项）。[why] = 诊断回调；返回 null = 跳过该项。 */
private fun parsePreset(v: Any?, why: (String) -> Unit): EditorPreset? {
    val o = v as? Map<*, *> ?: run { why("不是 JSON 对象"); return null }
    // ① schema：未知版本拒绝（理由见 presetSchemaUsable）
    val schemaD = (o["schema"] as? Double)?.takeIf { it.isFinite() && it == Math.floor(it) }
    val schema = schemaD?.toInt()
    if (!presetSchemaUsable(schema)) {
        why("schema=${o["schema"]} 不是本版本支持的 $PRESET_SCHEMA")
        return null
    }
    // ② id / name：定位与展示都靠它们，缺了这条预设没有意义
    val id = (o["id"] as? String)?.trim().orEmpty()
    if (id.isEmpty()) {
        why("缺少 id")
        return null
    }
    val name = (o["name"] as? String)?.trim().orEmpty()
    if (name.isEmpty()) {
        why("缺少 name")
        return null
    }
    // ③ params：必须是对象（数组 / 字符串 / 数字一律当坏项 —— 本格式没有"定长数组"形态）
    val rawParams = o["params"]
    if (rawParams != null && rawParams !is Map<*, *>) {
        why("params 不是对象")
        return null
    }
    val neutral = CameraPresets.standard()
    val slots = LinkedHashMap<Int, Double>()
    // 上面的形态检查让编译器把 rawParams 智能转换成 Map? ⇒ 直接遍历（无需再 cast）
    if (rawParams != null) {
        for ((k, value) in rawParams) {
            // ★ 未知语义名 ⇒ **整项拒绝**（不是逐槽跳过）。理由见 presetsFromJson 的 KDoc 第 2 档：
            //   不知道这个键代表什么参数，跳过它可能给出另一种风格且用户无从察觉。
            val idx = (k as? String)?.let { FilmParamKeys.byName[it] }
            if (idx == null) {
                why("未知参数名 \"$k\"（本版本没有这个语义）⇒ 整项拒绝")
                return null
            }
            // 已知语义名但槽位被策略排除 ⇒ 逐槽跳过（这是我们自己的策略，不是数据损坏）
            if (!presetSlotIncluded(idx)) {
                why("参数 $k（槽 $idx）不参与预设，已忽略")
                continue
            }
            val d = (value as? Double)?.takeIf { it.isFinite() }
            if (d == null) {
                why("参数 $k 的值非法，已忽略")
                continue
            }
            // 恒等值不入库（保住稀疏不变式：手改过的 JSON 也洗一遍）
            if (abs(d - neutral[idx]) <= PARAM_EPS) continue
            slots[idx] = d
        }
    }
    // ④ lut：资源引用坏了降级为"无 LUT"（资产丢失本来就有这条降级语义）
    var lutId: String? = null
    var lutStrength = 1.0
    when (val rawLut = o["lut"]) {
        null -> Unit
        is Map<*, *> -> {
            val lid = (rawLut["id"] as? String)?.trim().orEmpty()
            if (lid.isEmpty()) {
                why("lut.id 为空，按无 LUT 处理")
            } else {
                lutId = lid
                val s = (rawLut["strength"] as? Double)?.takeIf { it.isFinite() } ?: 1.0
                // 夹到 UI 量程（0..1.5）：手改的 5.0 会让引擎把 LUT 变换外推得离谱
                lutStrength = s.coerceIn(
                    LUT_STRENGTH_UI_RANGE.start.toDouble(),
                    LUT_STRENGTH_UI_RANGE.endInclusive.toDouble(),
                )
            }
        }
        else -> why("lut 不是对象，按无 LUT 处理")
    }
    return EditorPreset(
        id = id,
        name = name,
        group = (o["group"] as? String)?.trim().orEmpty(),
        favorite = o["favorite"] as? Boolean ?: false,
        createdAt = ((o["createdAt"] as? Double)?.takeIf { it.isFinite() && it >= 0 })?.toLong() ?: 0L,
        source = (o["source"] as? String)?.takeIf { it.isNotBlank() } ?: PRESET_SOURCE_USER,
        // note 是可选的自由文本（不 trim：用户写的说明原样保存）；形态坏了降级为空串，不跳整项
        note = (o["note"] as? String).orEmpty(),
        slots = slots,
        lutId = lutId,
        lutStrength = lutStrength,
    )
}

/**
 * 预设的 prefs 持久化（**唯一**的落盘入口）。薄到只剩"读字符串 / 写字符串"，
 * 全部判定都在上面的纯函数里（JVM 单测覆盖），这一层不被单测覆盖也不该有逻辑。
 *
 * 写入用 `apply()`（异步落盘，与 `LutLibrary` 同一口径）：预设是用户主动点保存的低频操作，
 * 不需要 `commit()` 的同步等待。
 */
internal object PresetStore {

    /** 读取（含容错）：坏项跳过并记 `preset.load_skipped` 诊断日志 */
    fun load(prefs: SharedPreferences): List<EditorPreset> {
        val res = presetsFromJson(prefs.getString(PRESET_KEY, null))
        if (res.skipped.isNotEmpty()) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "app", "preset.load_skipped",
                kv("count" to res.skipped.size, "why" to res.skipped.joinToString("；").take(500)),
            )
        }
        return res.presets
    }

    /** 全量写回（顺带做上限截断），返回**实际落盘**的列表 */
    fun save(prefs: SharedPreferences, list: List<EditorPreset>): List<EditorPreset> {
        val cut = list.take(PRESET_MAX)
        prefs.edit().putString(PRESET_KEY, presetsToJson(cut)).apply()
        return cut
    }

    /** 新增 / 覆盖同 id：新的放**最前**（最近保存的在列表顶部，超上限时丢最旧的） */
    fun upsert(prefs: SharedPreferences, p: EditorPreset): List<EditorPreset> =
        save(prefs, listOf(p) + load(prefs).filter { it.id != p.id })

    /** 按 id 删除，返回删除后的列表 */
    fun delete(prefs: SharedPreferences, id: String): List<EditorPreset> =
        save(prefs, load(prefs).filter { it.id != id })
}

/**
 * 极简 JSON 解析器（E3-1a）：只服务预设的读写，支持对象 / 数组 / 字符串 / 数字 / 布尔 / null。
 *
 * 为什么不用 `org.json`：**JVM 单测里它不可用**（android.jar 里是 stub，调用即抛），
 * 而本卡最需要护栏的恰恰是"存 → 读 → 套用"这条往返链（往返一致、坏数据不崩）。
 * 手写 ~90 行换来整条链可测，且与 `jsonEscape`（`JsonKv.kt`，同样是纯 Kotlin 手写）对称。
 *
 * 口径：数字一律 Double（槽位是 double，毫秒时间戳 < 2^53 ⇒ 精确）；解析失败抛
 * [IllegalArgumentException]，由 [presetsFromJson] 兜住（**绝不把异常漏给 UI**）。
 */
internal object MiniJson {

    fun parse(text: String): Any? = JsonReader(text).parse()
}

private class JsonReader(private val s: String) {
    private var i = 0

    fun parse(): Any? {
        val v = value()
        ws()
        if (i != s.length) fail("尾部有多余字符")
        return v
    }

    private fun fail(msg: String): Nothing = throw IllegalArgumentException("$msg @$i")

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

    private fun expect(c: Char) {
        if (peek() != c) fail("期待 '$c'")
        i++
    }

    private fun value(): Any? {
        ws()
        if (i >= s.length) fail("内容意外结束")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            else -> if (c == '-' || c.isDigit()) num() else fail("非法字符 '$c'")
        }
    }

    private fun lit(word: String, v: Any?): Any? {
        if (!s.startsWith(word, i)) fail("非法字面量")
        i += word.length
        return v
    }

    private fun obj(): Map<String, Any?> {
        expect('{')
        val m = LinkedHashMap<String, Any?>()
        ws()
        if (peek() == '}') {
            i++
            return m
        }
        while (true) {
            ws()
            val k = str()
            ws()
            expect(':')
            m[k] = value()
            ws()
            when (peek()) {
                ',' -> i++
                '}' -> {
                    i++
                    return m
                }
                else -> fail("对象里期待 ',' 或 '}'")
            }
        }
    }

    private fun arr(): List<Any?> {
        expect('[')
        val out = ArrayList<Any?>()
        ws()
        if (peek() == ']') {
            i++
            return out
        }
        while (true) {
            out += value()
            ws()
            when (peek()) {
                ',' -> i++
                ']' -> {
                    i++
                    return out
                }
                else -> fail("数组里期待 ',' 或 ']'")
            }
        }
    }

    private fun str(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail("字符串未闭合")
            when (val c = s[i++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) fail("转义未闭合")
                    when (val e = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("\\u 转义不完整")
                            val hex = s.substring(i, i + 4)
                            val code = hex.toIntOrNull(16) ?: fail("\\u 转义非法")
                            i += 4
                            sb.append(code.toChar())
                        }
                        else -> fail("未知转义 '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun num(): Double {
        val start = i
        if (peek() == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                s[i] == '+' || s[i] == '-')
        ) {
            i++
        }
        return s.substring(start, i).toDoubleOrNull() ?: fail("非法数字")
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
