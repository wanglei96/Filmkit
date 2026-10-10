package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets

/**
 * P-2a：拍照界面「预设」的**纯逻辑层** —— 与 Compose / prefs / 引擎解耦，JVM 单测直接钉住。
 *
 * 数据源 = [PresetStore]（与查看页「胶片」、编辑器「预设」**同一套**；架构师裁决见
 * `docs/reports/preset-DESIGN.md` §7）。本文件只做三件事：
 *  ① **展开**：`标准基线 + 稀疏覆盖` ⇒ 完整 96 槽（[cameraPresetParams]）；
 *  ② **LUT 四态**：不含 LUT ⇒ 不动；引用的 LUT 已不在库 ⇒ **清空 + 提示**；「标准」⇒ **清空**
 *     （[PresetLutAction]，P-2c 裁决 ①②）；
 *  ③ **选中态**：持久化 id ⇒ 预设（[resolveCameraPreset]）。
 *
 * ★ **LUT 缺失口径与编辑器侧已统一为"清空 + 明确提示"**（P-2c 裁决 ②，`docs/reports/
 * p2c-builtin-wiring-REPORT.md`）：保留一个无关 LUT 会产生"**预设 A 的参数 + 预设 B 的色彩**"
 * 混合态，用户从界面上无法理解那是什么。清空后画面 = 这条预设能给出的部分，且提示明确。
 * ⚠ 与"预设不含 LUT（`lutId == null`）⇒ 不动当前 LUT"**不是**同一件事：后者是有意的
 * "缺失字段 = 保留当前值"语义（[PresetLutAction.KEEP]），别把两者一起改掉。
 */

/** 内置「标准」的固定 id。前缀 `builtin_` 与用户预设的 `p_`（[presetId]）不重叠 ⇒ 不会撞车。 */
internal const val CAMERA_STANDARD_PRESET_ID = "builtin_standard"

/**
 * 拍照界面的内置「标准」= **中性基线**（[CameraPresets.standard]：无风格、无 LUT）。
 *
 * 用一条 id 固定的 [EditorPreset] 而不是 `null` 表示"标准"：列表渲染、选中判据、套用路径
 * 因此只有**一条**代码路径（`null` 会在每个 `when` 里多长出一个分支）。
 */
internal val CAMERA_STANDARD_PRESET = EditorPreset(
    id = CAMERA_STANDARD_PRESET_ID,
    name = "标准",
    source = PRESET_SOURCE_BUILTIN,
)

/**
 * 套用到相机的**完整 96 槽数组** = `标准基线 + 预设的稀疏覆盖`。
 *
 * 为什么底是 [CameraPresets.standard] 而不是"当前参数"（编辑器侧走的是后者）：拍照界面没有
 * 风格参数滑杆，参数数组的唯一来源就是预设 —— 从标准基线重建才是"套用预设 = 完整风格"。
 * 用户在拍照界面调的曝光补偿 / 斑马纹 / 影调开关由 `CameraController.setFilmParams` 内部
 * 的 prefs 覆盖层（`[0]/[28]/[30]/[34]/[35]`）重新写回，**不经过本函数**（既有设计）。
 *
 * 展开本身复用编辑器侧的 [presetSnapshot]（"缺失 key = 保留 base 值"的唯一实现）。
 */
internal fun cameraPresetParams(preset: EditorPreset): DoubleArray =
    presetSnapshot(CameraPresets.standard(), preset.slots)

/**
 * 套用预设时对 **LUT** 的动作。
 *
 * 判据来自 `preset-DESIGN.md` §7 裁决 A4 与调研 §5.1 D2：预设的 `lutId` 是**资源引用**，
 * `null` = 这条预设不含 LUT ⇒ 保留当前值（缺失字段 = 保留），不是"关掉 LUT"。
 *
 * ★ P-2c 裁决 ①（「标准」= 无风格，必须连 LUT 一起清）与 ②（LUT 缺失口径统一为"清空 + 明确
 * 提示"）之后，本枚举从三态变四态：**"清空"有两种触发源，但提示只有一种**
 *（[MISSING] 要提示，[CLEAR] 不提示 —— 用户主动选「标准」时画面变化是预期的）。
 *
 * @property clearsCurrentLut 该动作是否**清空**当前 LUT（引擎旁路 + prefs 摘选择）。
 *   UI 的落地分支读这个属性（不是各写一遍 `when` 判断）⇒ 单测断言它 = 断言 UI 的真实行为。
 */
internal enum class PresetLutAction(val clearsCurrentLut: Boolean) {
    /** 预设不含 LUT ⇒ **不动**当前 LUT 与 LUT 强度（`[34]`）。 */
    KEEP(false),

    /** 预设引用的 LUT 在库中 ⇒ 选中它 + 下发 [EditorPreset.lutStrength]。 */
    APPLY(false),

    /**
     * 预设引用的 LUT **已不在库中** ⇒ **清空**当前 LUT + 明确提示（裁决 ②，与编辑器侧统一）。
     *
     * 为什么是清空而不是保留：保留会让画面 = "这条预设的参数 + 上一个 LUT 的色彩"，
     * 而用户刚点的那条预设**并不包含**那个 LUT ⇒ 混合态无法从界面解释。
     */
    MISSING(true),

    /**
     * 「标准」= 中性基线 = **无风格** ⇒ 清空 LUT（裁决 ①）。
     *
     * 为什么必须清：只把参数打回基线、留着上一个预设的 LUT，是**假标准** —— 画面仍带风格，
     * 而 UI 上写着"标准"。提示不加：用户主动选"无风格"，LUT 消失正是这条语义本身。
     */
    CLEAR(true),
}

/**
 * LUT 动作判定（纯逻辑；`lutExists` 由调用侧注入 `LutLibrary.byId(context, id) != null`，
 * 于是本函数在 JVM 单测里可直接驱动"资产还在 / 已被删"两条路径）。
 *
 * 判定顺序（**标准在最前**，不能挪到后面）：[CAMERA_STANDARD_PRESET] 的 `lutId` 是 null，
 * 若先走 `lutId == null ⇒ KEEP` 就永远到不了 [PresetLutAction.CLEAR]（裁决 ① 静默失效）。
 *
 * 之所以 [MISSING] 与 [KEEP] 必须可分辨（尽管"保留"那条已改成清空）：前者要给用户一句
 * "LUT 已不在库中"，后者不该有任何提示。
 */
internal fun presetLutAction(preset: EditorPreset, lutExists: (String) -> Boolean): PresetLutAction {
    if (preset.id == CAMERA_STANDARD_PRESET_ID) return PresetLutAction.CLEAR
    val id = preset.lutId ?: return PresetLutAction.KEEP
    return if (lutExists(id)) PresetLutAction.APPLY else PresetLutAction.MISSING
}

/**
 * 冷启动恢复：由持久化的 id 在预设列表里定位**当前选中项**。
 *
 * 三种输入都收敛到一条确定的结果（绝不返回 null、绝不抛）：
 *  - `null` / 空 ⇒ 标准；
 *  - 命中列表 ⇒ 那一条；
 *  - **id 悬空**（预设已被删除 / 列表被截断）⇒ 标准。
 *
 * 返回 [CAMERA_STANDARD_PRESET] 而调用侧只需比对 `restored.id != savedId` 即可发现悬空，
 * 顺手把 prefs 里的脏 id 清掉（下次冷启动少一次无用的查找）。
 */
internal fun resolveCameraPreset(savedId: String?, presets: List<EditorPreset>): EditorPreset =
    when {
        savedId.isNullOrEmpty() || savedId == CAMERA_STANDARD_PRESET_ID -> CAMERA_STANDARD_PRESET
        else -> presets.firstOrNull { it.id == savedId } ?: CAMERA_STANDARD_PRESET
    }

/**
 * 预设卡片的小字（`LutCard` 的 `sub`）：**分组**（有则显示）+ **是否含 LUT**。
 *
 * 两者都没有 ⇒ `null`（不占一行，卡片高度与纯名称项一致）。例：`"柯达 · LUT"`。
 */
internal fun presetSubtitle(preset: EditorPreset): String? =
    listOfNotNull(
        preset.group.takeIf { it.isNotBlank() },
        "LUT".takeIf { preset.lutId != null },
    ).joinToString(" · ").ifEmpty { null }

/**
 * 诊断日志用：把预设的稀疏覆盖写成 `语义名=值;…`（`grain_amount=55.0;contrast=1.2`）。
 *
 * 用途是**验收证据**：`camera.preset.apply` 日志里带上它，就能与 [cameraPresetParams] 展开后
 * 的数组逐槽核对（"引擎收到的改动值 == 预设里记的值"）。语义名走 [FilmParamKeys]（与线格式
 * 同一个维护点），未知槽退回 `slot<idx>`（不静默丢字段）。
 */
internal fun presetSlotsText(preset: EditorPreset, limit: Int = 8): String =
    preset.slots.entries.take(limit).joinToString(";") { (i, v) ->
        "${FilmParamKeys.bySlot[i] ?: "slot$i"}=$v"
    }
