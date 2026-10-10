package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets

/**
 * P-2a：拍照界面「预设」的**纯逻辑层** —— 与 Compose / prefs / 引擎解耦，JVM 单测直接钉住。
 *
 * 数据源 = [PresetStore]（与查看页「胶片」、编辑器「预设」**同一套**；架构师裁决见
 * `docs/reports/preset-DESIGN.md` §7）。本文件只做三件事：
 *  ① **展开**：`标准基线 + 稀疏覆盖` ⇒ 完整 96 槽（[cameraPresetParams]）；
 *  ② **LUT 三态**：不含 LUT ⇒ 不动；引用的 LUT 已不在库 ⇒ 不动 + 提示（[PresetLutAction]）；
 *  ③ **选中态**：持久化 id ⇒ 预设（[resolveCameraPreset]）。
 *
 * ⚠ 与编辑器侧（`EditorScreen.applyPreset`）**唯一**的语义差异，是架构师裁决的口径：
 * 拍照侧"LUT 已不在库中"降级为**不动当前 LUT**（编辑器侧清空 LUT）—— 拍照界面的当前 LUT 是
 * 用户自己的选择，套一条引用已失效资产的预设不该把它一起抹掉。
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
 * 套用预设时对 **LUT** 的三态动作。
 *
 * 判据来自 `preset-DESIGN.md` §7 裁决 A4 与调研 §5.1 D2：预设的 `lutId` 是**资源引用**，
 * `null` = 这条预设不含 LUT ⇒ 保留当前值（缺失字段 = 保留），不是"关掉 LUT"。
 */
internal enum class PresetLutAction {
    /** 预设不含 LUT ⇒ **不动**当前 LUT 与 LUT 强度（`[34]`）。 */
    KEEP,

    /** 预设引用的 LUT 在库中 ⇒ 选中它 + 下发 [EditorPreset.lutStrength]。 */
    APPLY,

    /** 预设引用的 LUT **已不在库中** ⇒ 降级为不动当前 LUT + 明确提示（不静默、不清空）。 */
    MISSING,
}

/**
 * LUT 动作判定（纯逻辑；`lutExists` 由调用侧注入 `LutLibrary.byId(context, id) != null`，
 * 于是本函数在 JVM 单测里可直接驱动"资产还在 / 已被删"两条路径）。
 *
 * 之所以是**三态**而不是布尔：`MISSING` 与 `KEEP` 在参数域完全同型（都不动 LUT），
 * 但**必须可分辨** —— 前者要给用户一句"LUT 已不在库中"，后者不该有任何提示。
 */
internal fun presetLutAction(preset: EditorPreset, lutExists: (String) -> Boolean): PresetLutAction {
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
