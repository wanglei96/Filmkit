package com.filmkit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmkit.app.camera.CAMERA_PATH_SLOTS
import com.filmkit.app.camera.CAMERA_STANDARD_PRESET
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.PARAM_EPS
import com.filmkit.app.camera.PRESET_SOURCE_USER
import com.filmkit.app.camera.PresetScene
import com.filmkit.app.camera.cameraPresetParams
import com.filmkit.app.camera.formatZero150
import com.filmkit.app.camera.presetDeletable
import com.filmkit.app.camera.presetId
import com.filmkit.app.camera.presetScopeNotice
import com.filmkit.app.camera.presetSparseSlots
import com.filmkit.app.lut.LutLibrary
import kotlin.math.abs

/**
 * ★ PS-2a（用户 2026-10-11 **方向纠正**："预设的配置 / 新增 / 编辑 / 删除**我指的是拍照界面的
 * 预设**。这个**不能让用户在相册里面改这些配置**。"）：**拍照界面 = 预设的管理场所**。
 *
 * 上一张卡（PS-1）把这套交互做进了编辑器（宿主错了，组件是好的）⇒ 本文件把那套交互搬到
 * 拍照界面 `LutSheet` 的「预设」tab：**看配置 / 新增 / 编辑 / 删除**都在这里；
 * 相册与编辑器只负责**套用**（编辑器侧的降级由 PS-2b 做）。
 *
 * ## ★ PS-3（用户 2026-10-11 真机实测后："当前预设管理做的很差。参考截图——Filmix 相机，
 * **可直接在拍照界面查看、修改预设**。或参考 PC **长按跳转预设配置详情**。"）
 *
 * 本卡在 PS-2a 的骨架上补上**"修改"**这一半（PS-2a 只有"看 / 增 / 改名 / 删"）：
 *  - **参数清单逐项可点** ⇒ 展开该项滑杆（一次一项）⇒ 拖动**取景实时生效**；
 *  - 微调（`CameraParamOverrides`）**未保存不落盘**；保存口径见 [cameraPresetSlotsFor]；
 *  - **长按预设行** = 打开详情面板（点行仍是套用）；
 *  - 参数**能不能拖**由拍照链路白名单判（[cameraParamAdjustability]，ADR-12：不做假滑杆）。
 *
 * 滑杆控件与参数表**不是照抄**：`EditorParam` / 三组参数表 / `EditorSlider` 已提取到
 * `ParamControls.kt`（**逐字搬移**），编辑器与本文件引用同一个对象 ⇒ 量程 / 读数 / 中点标记
 * 不可能漂移（编辑器侧的三组参数表单测是"搬移零回归"的机器判据）。
 *
 * ## 与 PS-1 的关系（结构参考，宿主解耦）
 *
 *  - **纯逻辑一律复用 [PresetDetail] 里那套**（`presetMenuItems` / `presetReadOnlyNote` /
 *    `presetSummaryText` / `presetDetailSections` / `presetEditorButtons` / `defaultPresetName`）
 *    —— 那是 PS-1 留下的**唯一维护点**，本文件不另写第二份中文名 / 可用性 / 摘要逻辑；
 *  - **Compose 渲染本文件自己写**：PS-1 的 `PresetRow` / `PresetParamList` / `PresetEditorPanel`
 *    是 `EditorScreen.kt` 的 **private** 组件，且宿主尺寸完全不同（编辑器是常驻面板，这里是
 *    底部弹出层、与 LUT tab 共用一个高度预算）⇒ 提升为 internal 会与正在改编辑器的另一张卡
 *    （PS-2b）撞车，照抄又会把"编辑器参数面板"的耦合带过来。**只借结构，不借耦合**。
 *
 * ## 三条不回退的既有能力
 *
 *  1. **P-2a 的套用**（含 LUT 四态）：本文件只调 `onApply`，落地仍是 `applyCameraPreset`
 *     （`CameraScreen.kt`）—— 判定与提示一个字都没改；
 *  2. **U-7 的"拍摄态不入库"**：★ PS-3 起口径更精确 —— `[0]` **可以**进预设，但只进
 *     "用户在详情面板里明确调出的**偏移量**"（[cameraPresetSlotsFor]）；用户自己的 EV 补偿 /
 *     机型基准曝光仍进不了（它们活在 `CameraController` 里，本层看不到）。旧口径
 *     [cameraPresetSlots]（从已展开的数组构造）**仍然丢弃 `[0]`**，一个字没改；
 *  3. **P-2c 的内置只读保护**：判据仍是 `presetDeletable` 这一个实现（内置的滑杆可拖（试看），
 *     但保存只能「另存为新预设」）。
 *
 * ## ⚠ 未真机验证
 *
 * 本文件的纯逻辑部分由 `CameraPresetPanelUiTest` / `CameraPresetEditTest` 覆盖；**UI 呈现**
 * （底部弹出层的实际高度预算、滑杆展开后的清单高度、长预设名的换行、深色底对比度、`⋯` 的
 * 触摸热区、**拖动滑杆的跟手度与取景延迟**）需真机确认 —— 见
 * `docs/reports/ps2a-camera-preset-ui-REPORT.md` 与 `docs/reports/ps3-camera-preset-edit-REPORT.md`
 * 的「未做 / 存疑项」。
 */

// ─────────────────────────────────────────────────────────────────────────────
// 纯逻辑（无 Compose / 无 Context ⇒ JVM 单测直接驱动）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 曝光槽 `[0]`。
 *
 * ★ PS-3 起它在拍照界面有**两条**路径，别再混为一谈：
 *  - [cameraPresetSlots]（从"已展开的相机数组"构造）⇒ **丢弃**它（分不清来源）；
 *  - [cameraPresetSlotsFor]（从"预设记录 + 用户明确调整"构造）⇒ **保留**它（那是预设自己的
 *    曝光偏移，用户就在详情面板里调它）。
 */
internal const val CAMERA_PRESET_EV_SLOT = 0

/**
 * 拍照界面保存预设用的**稀疏槽集** = [presetSparseSlots] **减去 `[0]` 曝光**。
 *
 * 为什么减：这条路径的入参是**已展开的当前相机参数数组**，其中 `[0]` = 用户基准 + 用户 EV
 * 补偿 + 预设偏移（[effectiveExposureEv]）—— 从数组里**分不清**这一槽是预设自带还是用户刚调
 * 的 EV 补偿，而**用户的 EV 补偿是"拍摄态"不是风格**（`preset-DESIGN.md` §9.4，U-7）：
 * 把它写进预设，用户下次套用这条预设时会莫名多出一次曝光（而且他调 EV 的当下就会污染预设库）。
 *
 * ⚠ **PS-3 起这不是拍照界面唯一的保存口径**：详情面板里的逐项调整走 [cameraPresetSlotsFor]
 * （入参是"预设记录 + 明确调整" ⇒ `[0]` 语义清楚 ⇒ 保留）。两条路径并存不是重复：
 * 本函数服务"没有预设上下文的展开数组"（U-7 的原场景），另一条服务"明确在调这条预设"。
 */
internal fun cameraPresetSlots(film: DoubleArray): Map<Int, Double> =
    presetSparseSlots(film) - CAMERA_PRESET_EV_SLOT

/**
 * 「+ 新建预设」的构造（纯函数 ⇒ 单测直接钉住 id / 来源 / 稀疏槽 / LUT 三条口径）。
 *
 * ⚠ **PS-3 起拍照界面的「+ 新建预设」不再走这里**：它改走 [newCameraPresetFrom]（以当前预设
 * 为底 + 未保存的微调 ⇒ `[0]` 是明确的曝光偏移，能存上）。本函数保留原契约（"从已展开的数组
 * 构造 ⇒ 丢 `[0]`"），仍被单测与其它调用场景使用。
 *
 * @param film **当前相机参数**（拍照界面 = [cameraPresetParams]`(selectedPreset)` 展开的完整 96 槽）
 * @param lutId 当前生效的 LUT（null = 没有 ⇒ 预设不含 LUT，套用时"缺失字段 = 保留当前值"）
 * @param lutStrength 当前 LUT 强度（仅 `lutId != null` 时有意义）
 * @param salt [presetId] 的随机盐（调用侧给 `Random.nextInt()`，本函数保持纯逻辑）
 */
internal fun newCameraPreset(
    name: String,
    group: String,
    film: DoubleArray,
    lutId: String?,
    lutStrength: Double,
    now: Long,
    salt: Int,
): EditorPreset = EditorPreset(
    id = presetId(now, salt),
    name = name.trim().ifEmpty { "未命名" },
    group = group.trim(),
    createdAt = now,
    source = PRESET_SOURCE_USER,
    slots = cameraPresetSlots(film),
    lutId = lutId,
    // 没选 LUT 时 `[34]` 是"当前相机参数"里那个与预设无关的值 ⇒ 回落 1.0（与编辑器 savePreset 同口径）
    lutStrength = if (lutId != null) lutStrength else 1.0,
)

/**
 * 编辑面板的「**从当前相机参数更新**」：只换**配方**（slots / LUT），
 * id / 名称 / 分组 / 创建时间 / 来源**一个都不动**（改配方与改身份是两件事）。
 *
 * 与 [newCameraPreset] 共用 [cameraPresetSlots] ⇒ "更新后套用会把画面打回默认"这类漂移
 * 在结构上不存在。
 */
internal fun updatedCameraPreset(
    p: EditorPreset,
    film: DoubleArray,
    lutId: String?,
    lutStrength: Double,
): EditorPreset = p.copy(
    slots = cameraPresetSlots(film),
    lutId = lutId,
    lutStrength = if (lutId != null) lutStrength else 1.0,
)

/**
 * 「复制」：另存为新的一条（新 id + 「副本」后缀 + **用户来源**）。
 *
 * 副本永远是用户预设 —— ★ PS-3 起内置的「复制」也是**可点的**（用户："内置预设为什么不让
 * 修改？"），复制内置配方 = 以它为起点建一条可自由改的用户预设。本函数保证"被调用时行为
 * 正确"（权限判断不在这一层重写）。
 */
internal fun copiedCameraPreset(p: EditorPreset, now: Long, salt: Int): EditorPreset = p.copy(
    id = presetId(now, salt),
    name = "${p.name} 副本",
    source = PRESET_SOURCE_USER,
    favorite = false,
    createdAt = now,
)

// ★ PS-3：`cameraPresetEditorButtons`（"内置只读修正"那个 wrapper）**已删除** ——
// 用户 2026-10-11 拍板"内置最多限制不能删除"之后，`presetEditorButtons` 本身已经是正确口径
//（只有 `delete` 受 `presetDeletable` 限制），再包一层就是无价值的抽象。
// 面板直接调 `presetEditorButtons(...)`；`paramsReady` 在拍照界面恒为 true
//（`cameraPresetParams` 是纯函数，没有"首帧未就绪"）。

// ─────────────────────────────────────────────────────────────────────────────
// ★ PS-3：**逐项调整**（overrides）—— 用户 2026-10-11 真机实测后：
//    "当前预设管理做的很差。参考截图——Filmix 相机，可直接在拍照界面查看、修改预设。"
//
// 本节全是纯逻辑（无 Compose / 无 Context ⇒ JVM 单测直接驱动）：
//   ① [CameraParamOverrides]：**未保存**的临时微调（不落盘）；
//   ② [afterEdit]：一次拖动 ⇒ 新的微调集（复用编辑器同一份 read / write）；
//   ③ [cameraPresetSlotsFor]：保存回预设的 slots 口径（含 `[0]` 那个坑）；
//   ④ [cameraParamAdjustability]：**能不能拖**的判据（拍照链路白名单 + 有没有滑杆）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 拍照界面里**尚未保存**的参数改动（临时态）。
 *
 * ## 两种改动（"稀疏子集"的两个方向）
 *
 *  - [values]：用户拖滑杆设的值（**新增或修改**一个字段）；
 *  - [removed]：用户点「移除该项」删掉的字段（这条预设**不再包含**它 ⇒ 套用时保持不动）。
 *
 * 两者合起来就是"预设 = 稀疏字段子集"的完整编辑能力：能加、能改、能删。
 *
 * ## 为什么带 [presetId]
 *
 * **切换预设必须清空改动** —— 否则上一条预设的调整会串到新预设上（用户看到的画面与列表里
 * 选中的那条对不上，且一旦保存就把 A 的调整写进 B）。把"这份改动属于哪条预设"做进数据结构
 * 之后，**串味在结构上不可能发生**：生效值一律经 [forPreset] 取，id 不匹配 ⇒ null
 *（不需要任何"记得清空"的调用点）。
 *
 * 不落盘：保存动作才写库（[cameraPresetSlotsFor]）；不保存 ⇒ 关面板 / 切预设 / 杀进程都不会
 * 污染预设库（用户诉求"改完能存回预设"，不是"改完自动存"）。
 */
internal data class CameraParamOverrides(
    /** 这份改动属于哪条预设（[EditorPreset.id]） */
    val presetId: String,
    /** 槽位 → 用户设的值（只含**真正被改过**的槽；拖回预设原值时该项被移除，见 [afterEdit]） */
    val values: Map<Int, Double> = emptyMap(),
    /** ★ PS-3 P1：用户从这条预设里**移除**的字段（"未设置"的反向操作） */
    val removed: Set<Int> = emptySet(),
) {
    /** 有没有任何未保存的改动（UI 用它决定「保存到预设」是否可点 —— 没有改动就置灰，诚实）。 */
    val isEmpty: Boolean get() = values.isEmpty() && removed.isEmpty()
}

/**
 * 取当前预设的生效改动：**id 不匹配 ⇒ null**（切预设 = 自动失效，见 [CameraParamOverrides]）。
 */
internal fun CameraParamOverrides?.forPreset(preset: EditorPreset): CameraParamOverrides? =
    if (this != null && this.presetId == preset.id) this else null

/**
 * 一次拖动 ⇒ 新的改动集（**复用编辑器参数表的 [EditorParam.write]**，量程 / 夹紧 / 多槽写入
 * 与编辑器逐位同一套 ⇒ 拍照界面不可能出现"第二个读数口径"）。
 *
 * 算法：把 UI 值写进"当前生效参数数组"，再与**这条预设自己的配方**逐槽比对 —— 与预设原值相同
 * 的槽**不进** [CameraParamOverrides.values]（拖回原值 = 撤销这次调整，而不是"存一个等于原值的
 * 覆盖"）。多槽参数（饱和度 `[20..22]` / 胶片曲线 `[5..10]`）按 `write` 实际写的槽逐个记账 ⇒
 * "一条滑杆改三槽"在改动集里也是三槽（引擎读什么就记什么）。
 *
 * 给某个槽设值 = **撤销**它之前那次「移除该项」（[CameraParamOverrides.removed] 里划掉它）：
 * 用户刚设了值，这条预设显然又包含它了。
 *
 * ⚠ `[0]` 曝光：它写进数组后会被 `setFilmParams` 覆盖（引擎侧 `[0] = base + 用户 EV + 偏移`）
 * ⇒ 生效必须走 [effectivePresetEvOffset] 的第二个实参；本函数把用户拖出的**偏移绝对值**记进
 * `values[0]`，保存时写回 `preset.slots[0]`（见 [cameraPresetSlotsFor]）。
 */
internal fun CameraParamOverrides?.afterEdit(
    preset: EditorPreset,
    param: EditorParam,
    uiValue: Float,
): CameraParamOverrides {
    val mine = forPreset(preset)
    val values0 = mine?.values ?: emptyMap()
    val removed0 = mine?.removed ?: emptySet()
    val film = cameraPresetParams(preset, values0, removed0)   // 当前生效参数（96 槽）
    param.write(film, uiValue)                                 // 编辑器同一套写入（含夹紧 / 多槽）
    val recipe = cameraPresetParams(preset)                    // 这条预设**自己的**配方（不含改动）
    val values = LinkedHashMap(values0)
    for (i in film.indices) {
        val v = film[i]
        if (!v.isFinite()) continue
        if (abs(v - recipe[i]) > PARAM_EPS) values[i] = v else values.remove(i)
    }
    // 设过值的槽不再算"已移除"（用户刚刚把它加回来了）
    return CameraParamOverrides(preset.id, values, removed0 - values.keys)
}

/**
 * ★ PS-3 P1：「**移除该项**」= 这条预设**不再包含**这个字段（稀疏子集的可视化操作）。
 *
 * 语义（与"缺失字段 = 套用时保持不动"完全一致）：把该槽从预设的记录里摘掉 ⇒ 画面回到基线
 * （用户立刻看得到"这项没了"），保存时它自然不会写进 `slots`。
 *
 * 同时把该槽从 [CameraParamOverrides.values] 里去掉 —— 否则"移除"之后又被那条改值覆盖回去，
 * 用户会看到"点了移除、画面没变"（ADR-12 的假控件）。
 */
internal fun CameraParamOverrides?.removeSlot(preset: EditorPreset, slot: Int): CameraParamOverrides {
    val mine = forPreset(preset)
    return CameraParamOverrides(
        presetId = preset.id,
        values = (mine?.values ?: emptyMap()) - slot,
        removed = (mine?.removed ?: emptySet()) + slot,
    )
}

/**
 * ★ **保存回预设的 slots 口径**（PS-3 的核心裁决，也是本卡唯一一处改变既有保存口径的地方）。
 *
 * = `preset.slots` 先摘掉 [removed]、再合并 [overrides]（overrides 优先），最后**重新稀疏化**
 * （丢恒等值）。
 *
 * ## 与 [cameraPresetSlots] 的唯一差别：`[0]` 曝光（**那个必须处理的坑**）
 *
 * [cameraPresetSlots] 故意丢弃 `[0]`，理由写在它的 KDoc 里：拍照界面这一层拿到的 `[0]` 是
 * "**已展开的当前曝光**"，分不清"预设自带"还是"用户刚调的 EV 补偿" ⇒ 宁可少存一个槽，也不把
 * 拍摄态写进风格（U-7，`preset-DESIGN.md` §9.4）。
 *
 * PS-3 起用户在详情面板里**明确地**在调"这条预设的曝光偏移"（滑杆读数就是偏移量本身，
 * 见 [effectivePresetEvOffset]）⇒ 语义是清楚的：
 *  - **存进 `slots[0]` 的值 = 用户拖出的偏移量**（`overrides[0]`），**不是** `base_ev + 用户 EV
 *    + 偏移`那个绝对值 —— 用户自己的 EV 补偿 / 机型基准曝光**一个字节都不会进预设**
 *    （它们活在 `CameraController` 内部，本函数看不到也不该看到）；
 *  - 拖回 0（= 不改变曝光）⇒ `[0]` 被稀疏化丢掉 ⇒ 该预设恢复"不含曝光偏移"（与"缺失字段 =
 *    保留当前值"的既有语义一致，不是"把曝光打到 0"）。
 *
 * 两条路径因此**同时成立**：`[0]` 的明确调整能存上（用户不再困惑"调了存不上"），拍摄态
 * 仍然进不了预设库（U-7 的纪律不变）。这条口径由 `CameraPresetEditTest` 的两条用例钉住。
 *
 * @param preset 目标预设（保存到预设 / 新建都以它为底）
 * @param overrides 该预设的生效改动（**必须**经 [forPreset] 取，保证不是别的预设的）
 * @param removed 用户从这条预设里移除的字段
 */
internal fun cameraPresetSlotsFor(
    preset: EditorPreset,
    overrides: Map<Int, Double>,
    removed: Set<Int> = emptySet(),
): Map<Int, Double> = presetSparseSlots(cameraPresetParams(preset, overrides, removed))

/**
 * ★ PS-3：「+ 新建预设」的构造 = **以某条预设为底 + 当前改动**。
 *
 * 与 [newCameraPreset] 的差别：那条从"已展开的相机数组"构造（分不清 `[0]` 来源 ⇒ 丢 `[0]`），
 * 这条从"预设自己的记录 + 用户明确的改动"构造 ⇒ `[0]` 是明确的预设偏移，**保留**
 *（口径与理由见 [cameraPresetSlotsFor]）。两条构造路径的**身份纪律完全一致**：新 id、
 * 用户来源、名称 / 分组去空格、空名回落「未命名」、没选 LUT ⇒ 强度回落 1.0。
 */
internal fun newCameraPresetFrom(
    base: EditorPreset,
    name: String,
    group: String,
    overrides: Map<Int, Double>,
    lutId: String?,
    lutStrength: Double,
    now: Long,
    salt: Int,
    removed: Set<Int> = emptySet(),
): EditorPreset = copiedCameraPreset(base, now, salt).copy(
    name = name.trim().ifEmpty { "未命名" },
    group = group.trim(),
    slots = cameraPresetSlotsFor(base, overrides, removed),
    lutId = lutId,
    // 没选 LUT 时强度与预设无关 ⇒ 回落 1.0（与 newCameraPreset / 编辑器 savePreset 同口径）
    lutStrength = if (lutId != null) lutStrength else 1.0,
)

/** 参数项在拍照界面的**可调性**（ADR-12：能拖 = 拖了画面真的会变）。 */
internal enum class CameraParamAdjustability {
    /** 拍照链路读该槽，且参数面板里有对应滑杆 ⇒ 可点开滑杆、拖动即时生效。 */
    TUNABLE,

    /**
     * 拍照链路读该槽，但**当前没有对应滑杆**（编辑器参数面板也没提供：颗粒 `[26][27][60][61]`、
     * filmic 黑白点 `[37][38]`）⇒ 只读。这是**既有欠账**（不是本卡引入），如实标注而不是
     * 造一条没有对应引擎语义的滑杆。
     */
    NO_SLIDER,

    /**
     * 拍照链路**不读**该槽（引擎实测白名单 [CAMERA_PATH_SLOTS] 之外，例如 `[69..77]` 层间效应）
     * ⇒ 拖了取景画面不会有任何变化 ⇒ 只读 + 标注原因（ADR-12 禁止假滑杆）。
     */
    CAMERA_IGNORED,
}

/**
 * 某个槽在拍照界面的可调性（**唯一判据**；UI 只负责按它渲染）。
 *
 * 判据顺序要紧：**先看拍照链路读不读**（[CAMERA_PATH_SLOTS]），再看有没有滑杆 —— 反过来的话
 * "有滑杆但拍照不生效"会被误判成可调（那正是假滑杆）。
 */
internal fun cameraParamAdjustability(slot: Int): CameraParamAdjustability = when {
    slot !in CAMERA_PATH_SLOTS -> CameraParamAdjustability.CAMERA_IGNORED
    slot in CAMERA_TUNABLE_SLOT_PARAM -> CameraParamAdjustability.TUNABLE
    else -> CameraParamAdjustability.NO_SLIDER
}

/**
 * 不可调项的**一句实话**（TUNABLE ⇒ null，不占行）—— 用户必须知道"为什么点不动"，
 * 而不是遇到一排没反应的灰行（ADR-12）。
 */
internal fun cameraParamAdjustabilityNote(slot: Int): String? = when (cameraParamAdjustability(slot)) {
    CameraParamAdjustability.TUNABLE -> null
    CameraParamAdjustability.NO_SLIDER -> "拍照生效 · 暂无滑杆"
    CameraParamAdjustability.CAMERA_IGNORED -> "仅编辑器生效 · 拍照不变"
}

// ─────────────────────────────────────────────────────────────────────────────
// Compose：拍照界面「预设」tab（宿主 = LutSheet，父层已给 16dp 水平 padding）
// ─────────────────────────────────────────────────────────────────────────────

/** 不可用文字（与编辑器同一条观感；EditorScreen 那份是 private ⇒ 各写一行常量，不是逻辑复制）。 */
private val CamDisabledInk = FilmTheme.CreamDim.copy(alpha = 0.35f)

/**
 * 拍照界面「预设」tab 的全部内容（列表 / 编辑面板 / 菜单 / 删除确认）。
 *
 * ## 布局（用户三条诉求的落点）
 *
 * ```
 * [+ 新建预设]  点行套用 · 长按看配置并调整 · 「⋯」菜单     ← 常驻入口，不再靠用户猜
 * ┌──────────────────────────────────────────────┐
 * │ 标准                              [内置]      │  ← 点行 = 套用（P-2a 不变）
 * │ 不含参数（套用时其余参数保持不动）        [⋯] │  ← **长按行 = 打开详情（PS-3）**
 * ├──────────────────────────────────────────────┤
 * │ 徕卡一瞬  [内置]  徕卡                        │  ← 名称 + 来源 + 分组
 * │ 对比度 -3 · 饱和度 · 中间调 +5 · … · LUT …    │  ← 参数摘要（"这条包含什么"）
 * └──────────────────────────────────────────────┘
 * ```
 *
 * 点行 = **套用**；**长按行 = 打开详情面板**（用户："参考 PC **长按跳转预设配置详情**"）；
 * 行尾「⋯」= 操作菜单（**不可用的项置灰但可见** + 一句实话，绝不隐藏）。
 *
 * ## ★ PS-3：详情面板 = **参数可逐项修改**（用户："可直接在拍照界面查看、修改预设"）
 *
 *  - 参数表是**全量**的（`cameraParamSections`：拍照链路可调的 35 项一个不少），
 *    按 §9.2 的四组 + 兜底组**分组可折叠**（面板限高 200 多 dp，全展开放不下）；
 *  - 每项两种状态：**已包含**（显示这条预设的值 + 「移除该项」）/ **未设置**
 *    （显示"未设置"，**绝不用当前相机值冒充** —— 套用时该字段保持不动）；
 *  - 点一行 ⇒ 展开该项滑杆（一次一项）⇒ 拖动**取景实时生效**；
 *  - 改动活在 [CameraParamOverrides] 里（**未保存不落盘**），面板底部：
 *    新建态「保存当前相机参数为新预设」/ 编辑态「保存到预设」+「从当前相机参数更新」；
 *  - **内置预设可改**（用户 2026-10-11："内置预设为什么不让修改？…最多限制不能删除就行"），
 *    改过之后有「恢复默认」；**删除**仍置灰（唯一限制）。
 *
 * ## 四条纪律
 *
 *  1. **不做假滑杆**：只有拍照链路真的读、且参数面板真的有滑杆的槽才可点
 *    （`cameraParamAdjustability`，判据 = 引擎实测白名单 `CAMERA_PATH_SLOTS`）。
 *  2. **拖动不写上层 state**：拖动每帧只调 [onPreview]（直通引擎），松手才 [onOverrides] ——
 *     每帧写 `CameraScreen` 的 state 会把整页拖进重组（v0.10.96 的 LUT 强度滑杆踩过同型坑）。
 *  3. **未保存不落盘**：只有保存动作写库（用户预设 → `PresetStore`；内置 → 覆盖层）。
 *  4. **编辑对象 == 生效对象**：进详情（长按 / 「查看配置」/「编辑」）会把该预设**套用**成当前
 *     生效的那条（见 [openEdit] 的不变量）—— 画面只有一份，"实时效果"必须有明确归属。
 *     ⇒ [onPreview] 因此永远作用于 `selectedPreset`（= 详情面板正在编辑的那条）。
 *
 * @param presets `BuiltinPresets.all(context, prefs)`（用户预设在前 + 内置三条在后，含覆盖层）
 * @param selected 当前生效的预设（列表首项恒为 [CAMERA_STANDARD_PRESET]）
 * @param lutId / lutStrength 当前 LUT 与强度（新建态的清单要显示这一行，`[34]` 不进 slots）
 * @param overrides ★ PS-3：当前**未保存**的改动（null = 没有；带归属 id ⇒ 切预设自动失效）
 * @param builtinOverrides ★ PS-3：内置覆盖表（判「恢复默认」是否可点：改过才需要恢复）
 * @param onPreview ★ PS-3：拖动中 ⇒ 立即下发取景（**不要**在这里写 Compose state）
 * @param onOverrides ★ PS-3：提交改动（松手 / 移除一项时；null = 清空）
 * @param onSave 新建（列表「+ 新建预设」）：名称 / 分组 / 改动
 * @param onUpdate 「从当前相机参数更新」：整包重写该预设的配方（既有能力，PS-3 带上改动）
 * @param onSaveSlots ★ PS-3：「保存到预设」= 把改动写进该预设（含移除项与 `[0]` 曝光偏移）
 * @param onRestore ★ PS-3：「恢复默认」（内置）—— 删掉覆盖层，回到出厂定义
 */
@Composable
internal fun CameraPresetTab(
    presets: List<EditorPreset>,
    selected: EditorPreset,
    lutId: String?,
    lutStrength: Float,
    overrides: CameraParamOverrides?,
    builtinOverrides: Map<String, EditorPreset>,
    onPreview: (CameraParamOverrides) -> Unit,
    onOverrides: (CameraParamOverrides?) -> Unit,
    onApply: (EditorPreset) -> Unit,
    onSave: (String, String, CameraParamOverrides?) -> Unit,
    onUpdate: (EditorPreset, CameraParamOverrides?) -> Unit,
    onSaveSlots: (EditorPreset, CameraParamOverrides?) -> Unit,
    onRestore: (EditorPreset) -> Unit,
    onRename: (EditorPreset, String, String) -> Unit,
    onCopy: (EditorPreset) -> Unit,
    onDelete: (EditorPreset) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    // 编辑面板：`editorNew` = 新建态（没有 id），`editorId` = 编辑态。存 **id** 而不是对象 ——
    // 保存 / 更新 / 改名后 `presets` 刷新，面板自动显示新内容；被删 ⇒ 自动回列表（PS-1 同款）。
    var editorNew by remember { mutableStateOf(false) }
    var editorId by remember { mutableStateOf<String?>(null) }
    val editor = editorId?.let { id -> presets.firstOrNull { it.id == id } }
    val editorOpen = editorNew || editor != null
    var menuFor by remember { mutableStateOf<EditorPreset?>(null) }
    var pendingDelete by remember { mutableStateOf<EditorPreset?>(null) }

    /** 打开新建面板：**预填默认名**（用户不必先想名字，保存按钮一路可点） */
    fun openNew() {
        name = defaultPresetName(presets)
        group = ""
        editorNew = true
        editorId = null
    }

    /**
     * 打开编辑面板（「⋯」→「编辑 / 重命名」/「查看配置」/ **长按行** 都是这个落点）。
     *
     * ★ **PS-3 不变量**：进详情 ⇒ 这条预设**成为当前生效的那条**（不是 ⇒ 先走 [onApply] 套用它）。
     *
     * 为什么必须：详情面板里的滑杆要"拖动即改、取景实时生效"，而**画面只有一个** ——
     * 若面板在编辑一条**未选中**的预设，滑杆改的到底是哪条预设的画面就无从谈起（下发的
     * [onPreview] 只能作用于当前生效的预设）。让"编辑对象 == 生效对象"之后，画面、顶栏名字、
     * 列表高亮、改动归属、保存目标**全部指向同一条预设**，不存在混合态。
     *
     * 已经选中的 ⇒ **不重复套用**（避免白跑一次 LUT 四态判定与参数下发，也不会把用户刚调好的
     * 改动清掉 —— 套用会清空改动，那是"换预设"的语义）。
     */
    fun openEdit(p: EditorPreset) {
        if (p.id != selected.id) onApply(p)
        name = p.name
        group = p.group
        editorNew = false
        editorId = p.id
    }

    fun closeEditor() {
        editorNew = false
        editorId = null
    }

    if (editorOpen) {
        // ★ PS-3：拖动中的改动持有者 —— **普通对象**（不参与 Compose 快照）⇒ 拖动每帧写它
        // 不会触发任何重组（只有 [EditorSlider] 自己的拖动 state 在变）。松手时才经
        // [onOverrides] 提交一次（上层 state 因此每帧都不动）。
        val drag = remember { DragHolder() }
        val p = editor
        // **编辑对象**：编辑态 = 那条预设；新建态 = 当前生效的预设（"新建 = 以当前画面为起点"，
        // 用户 2026-10-11："新建预设时目前参数居然不可配置？？"—— 新建态同样能逐项调）
        val target = p ?: selected
        val mine = overrides.forPreset(target)
        val values = mine?.values ?: emptyMap()
        val removed = mine?.removed ?: emptySet()
        // 清单显示的"这条预设包含什么" = 预设记录 − 用户移除 + 用户设的值
        //（**不含**那些没设过值的槽 ⇒ 它们显示"未设置"，不用当前相机值冒充）
        val shown = (target.slots - removed) + values
        CameraPresetEditorPanel(
            p = p,
            target = target,
            slots = shown,
            params = cameraPresetParams(target, values, removed),
            overrides = mine,
            lut = if (editorNew) {
                lutId?.let { PresetLutLine(it, formatZero150(lutStrength)) }
            } else {
                p?.let { presetLutLine(it) }
            },
            name = name,
            onName = { name = it },
            group = group,
            onGroup = { group = it },
            // 拍照界面的参数恒可就绪（cameraPresetParams 是纯函数）⇒ paramsReady = true
            buttons = presetEditorButtons(editorNew, p, paramsReady = true, name = name),
            readOnlyNote = p?.let { presetReadOnlyNote(it) },
            // 内置改过 ⇒ 「恢复默认」可点（覆盖表里有这个 id）
            hasOverride = p != null && builtinOverrides.containsKey(p.id),
            onEditParam = { param, v ->
                val next = overrides.afterEdit(target, param, v)
                drag.value = next
                onPreview(next)
            },
            onEditFinished = {
                drag.value?.let { onOverrides(it) }
                drag.value = null
            },
            onRemoveSlot = { slot ->
                // 「移除该项」= 一次性动作（不是拖动）⇒ 直接提交 + 立即下发
                val next = overrides.removeSlot(target, slot)
                onOverrides(next)
                onPreview(next)
            },
            onBack = { closeEditor() },
            onSaveNew = {
                // 新建 = 当前生效预设 + 当前改动（含 `[0]` 曝光偏移，见 newCameraPresetFrom）
                onSave(name, group, overrides.forPreset(target))
                closeEditor()
            },
            onUpdate = { p?.let { onUpdate(it, overrides.forPreset(it)) } },
            onSaveSlots = { p?.let { onSaveSlots(it, overrides.forPreset(it)) } },
            onRestore = { p?.let { onRestore(it) } },
            onSaveMeta = { p?.let { onRename(it, name, group) } },
            onDelete = { p?.let { pendingDelete = it } },
        )
    } else {
        Column(Modifier.fillMaxWidth()) {
            // ① 常驻的「+ 新建预设」+ 入口提示（"哪里能点"写在界面上，不靠用户猜）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "+ 新建预设",
                    color = FilmTheme.OnAccent,
                    fontSize = 12.sp,
                    maxLines = 1,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(FilmTheme.Accent)
                        .pressScale(pressedScale = 0.94f, onTap = { openNew() })
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    // PS-3：长按 = 详情（用户："参考 PC 长按跳转预设配置详情"）—— 入口写在界面上
                    "点行套用 · 长按看配置并调整 · 「⋯」菜单",
                    color = FilmTheme.CreamDim,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            // ② 列表：首项恒为内置「标准」（P-2a 的列表契约），其余 = 用户预设 + 内置三条。
            //    限高：面板与 LUT tab 共用一个高度预算，列表再长也不该把预览挤没。
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 224.dp)
                    .padding(top = 8.dp),
            ) {
                items(listOf(CAMERA_STANDARD_PRESET) + presets, key = { it.id }) { p ->
                    CameraPresetRow(
                        p = p,
                        selected = p.id == selected.id,
                        // 参数摘要：LUT 的中文名要 Context 查 ⇒ 在这里查（纯逻辑层不碰 Context）
                        summary = presetSummaryText(
                            p,
                            lutLabel = p.lutId?.let { id -> LutLibrary.byId(context, id)?.label },
                        ),
                        // 内置被改过 ⇒ 行上标一句（否则用户不知道"这条已不是出厂值"）
                        edited = builtinOverrides.containsKey(p.id),
                        onApply = { onApply(p) },
                        // ★ PS-3：长按 = 打开详情面板（点行仍是套用，P-2a 行为一位不动）
                        onDetail = { openEdit(p) },
                        onMenu = { menuFor = p },
                    )
                }
            }
            // ③ P-5 字段作用域：当前这条预设里有几项只在编辑器链路生效 ⇒ 套用前先说清楚
            //   （ADR-12：不假装生效）。全部生效 ⇒ null ⇒ 不显示。
            presetScopeNotice(selected, PresetScene.CAMERA)?.let { notice ->
                Text(
                    notice,
                    color = FilmTheme.CreamDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    // 「⋯」操作菜单（显式入口）：不可用的项**置灰但保留在清单里**，底部一句实话。
    // 用 AlertDialog 而不是 DropdownMenu：本页既有浮层全是 AlertDialog（删除 LUT / 分类），
    // 一套层级语言；且菜单项要多行文本，窄条会挤成一团。
    menuFor?.let { p ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            shape = RoundedCornerShape(18.dp),
            title = {
                Text(
                    p.name,
                    color = FilmTheme.Cream,
                    fontFamily = FilmTheme.Serif,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    presetMenuItems(p, paramsReady = true).forEach { item ->
                        Text(
                            item.label,
                            color = if (item.enabled) FilmTheme.Cream else CamDisabledInk,
                            fontSize = 14.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .pressScale(
                                    pressedScale = 0.97f,
                                    enabled = item.enabled,
                                    onTap = {
                                        menuFor = null
                                        when (item.action) {
                                            PresetAction.APPLY -> onApply(p)
                                            // 「查看配置」与「编辑」在拍照界面是**同一个面板**（清单
                                            // 本来就完整可读）—— 两者现在对所有预设都可用（PS-3：
                                            // 内置也能改，唯一限制是不能删）
                                            PresetAction.VIEW, PresetAction.EDIT, PresetAction.RENAME -> openEdit(p)
                                            PresetAction.COPY -> onCopy(p)
                                            // ★ PS-3：「恢复默认」= 删掉内置覆盖层（回到出厂定义）
                                            PresetAction.RESTORE -> onRestore(p)
                                            PresetAction.DELETE -> pendingDelete = p
                                        }
                                    },
                                )
                                .padding(horizontal = 6.dp, vertical = 9.dp),
                        )
                    }
                    // 一句实话（内置预设：唯一限制是不能删）；用户预设 ⇒ null ⇒ 不占行
                    presetReadOnlyNote(p)?.let { note ->
                        Text(
                            note,
                            color = FilmTheme.CreamDim,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuFor = null }) {
                    Text("取消", color = FilmTheme.CreamDim, fontFamily = FilmTheme.Mono)
                }
            },
        )
    }

    // 删除二次确认：取消 = 什么都不做（预设、参数、画面全不动）
    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = RoundedCornerShape(18.dp),
            title = { Text("删除预设？", color = FilmTheme.Cream, fontFamily = FilmTheme.Serif) },
            text = {
                Text(
                    "「${p.name}」将被删除，无法恢复。\n当前画面与相机参数不受影响。",
                    color = FilmTheme.CreamDim,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(p)
                }) {
                    Text("删除", color = FilmTheme.Accent, fontFamily = FilmTheme.Mono)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = FilmTheme.CreamDim, fontFamily = FilmTheme.Mono)
                }
            },
        )
    }
}

/**
 * 预设列表的一行：**名称 + 来源 / 分组 + 参数摘要** + 行尾「⋯」。
 *
 * 点行（名称区与摘要区） = **套用** —— 与 P-2a 的卡片点击同一条语义，主操作不需要进菜单。
 * **长按行 = 打开详情面板**（PS-3；用户："参考 PC 长按跳转预设配置详情"）—— 与点行不冲突：
 * 短按仍是套用（老用户肌肉记忆不变），长按是新增的第二入口，等价于菜单里的「查看配置」。
 * 选中项用琥珀描边 + 底色标出（"当前生效的是哪条"一眼可见，旧卡片版靠边框色）。
 */
@Composable
private fun CameraPresetRow(
    p: EditorPreset,
    selected: Boolean,
    /** 参数摘要（含 LUT 行）；空串 ⇒ 显示"不含参数" */
    summary: String,
    /** ★ PS-3：内置且被用户改过（覆盖层里有它）⇒ 行上标「已改」 */
    edited: Boolean,
    onApply: () -> Unit,
    /** ★ PS-3：长按 = 详情（与菜单「查看配置」同一个落点） */
    onDetail: () -> Unit,
    /** 行尾「⋯」= 操作菜单 */
    onMenu: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) FilmTheme.ChipBg else Color.Transparent)
            .border(
                1.dp,
                if (selected) FilmTheme.Accent else FilmTheme.ChipBorder,
                RoundedCornerShape(10.dp),
            )
            // ★ PS-3：长按 = 详情（combinedClickable 由 pressScale 的 onLongTap 提供）
            .pressScale(pressedScale = 0.98f, onTap = onApply, onLongTap = onDetail)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.name,
                    color = if (selected) FilmTheme.Accent else FilmTheme.Cream,
                    fontSize = 13.sp,
                    fontFamily = FilmTheme.Serif,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 来源标记（判据仍是 presetDeletable —— 内置删除保护的唯一实现）
                if (!presetDeletable(p)) {
                    Text(
                        " 内置",
                        color = CamDisabledInk,
                        fontSize = 10.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, CamDisabledInk, RoundedCornerShape(6.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    // ★ PS-3：改过的内置 —— 说清楚"这条已不是出厂值，可「恢复默认」"
                    if (edited) {
                        Text(
                            " 已改",
                            color = FilmTheme.Accent,
                            fontSize = 10.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, FilmTheme.Accent, RoundedCornerShape(6.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                if (p.group.isNotEmpty()) {
                    Text(
                        "  ${p.group}",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // **这条预设包含什么**（用户第 1 条诉求的行内答案）；空预设 ⇒ 一句实话，不是空白行
            Text(
                summary.ifEmpty { "不含参数（套用时其余参数保持不动）" },
                color = FilmTheme.CreamDim,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        // 「⋯」= 显式操作菜单入口。内置预设这一枚**照样渲染**（菜单里的受限项置灰可见）——
        // 隐藏正是用户"没法删"投诉的根因。
        Text(
            "⋯",
            color = FilmTheme.Cream,
            fontSize = 15.sp,
            maxLines = 1,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(FilmTheme.ChipBg)
                .border(1.dp, FilmTheme.ChipBorder, RoundedCornerShape(9.dp))
                .pressScale(pressedScale = 0.94f, onTap = onMenu)
                .padding(horizontal = 12.dp, vertical = 3.dp),
        )
    }
}

/** 编辑面板的单行输入（名称 / 分组）：自绘底 + 描边，颜色只用 FilmTheme 的既有 token */
@Composable
private fun CamPresetField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(FilmTheme.ChipBg)
            .border(1.dp, FilmTheme.ChipBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) {
            Text(hint, color = CamDisabledInk, fontSize = 12.sp, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = FilmTheme.Cream, fontSize = 12.sp),
            cursorBrush = SolidColor(FilmTheme.Accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 编辑面板里的一个动作按钮：禁用 ⇒ **置灰但看得见**（隐藏正是投诉的根因）。 */
@Composable
private fun CamPresetActionButton(
    label: String,
    enabled: Boolean,
    primary: Boolean = false,
    onTap: () -> Unit,
) {
    Text(
        label,
        color = when {
            !enabled -> CamDisabledInk
            primary -> FilmTheme.OnAccent
            else -> FilmTheme.Accent
        },
        fontSize = 12.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled && primary) FilmTheme.Accent else Color.Transparent)
            .border(1.dp, if (enabled) FilmTheme.Accent else CamDisabledInk, RoundedCornerShape(9.dp))
            .pressScale(pressedScale = 0.94f, enabled = enabled, onTap = onTap)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * **参数表**（★ PS-3：全量 + 分组折叠 + 每项可调）。
 *
 * 数据源 = [cameraParamSections]（中文名 / 单位 / 分组 / 格式化的**唯一维护点**仍是
 * `PresetDetail.kt` 的 `DETAIL_META`）—— 本文件只负责画出来与收集交互。
 *
 * ## 三种行的状态
 *
 * | 状态 | 显示 | 交互 |
 * |---|---|---|
 * | **已包含** | 参数名 + 这条预设的值 | 点 ⇒ 展开滑杆（拖动实时生效）+ 「移除该项」 |
 * | **未设置** | 参数名 + "未设置"（灰） | 点 ⇒ 展开滑杆 ⇒ 设值 = 加入这条预设 |
 * | **拍照不生效** | 灰显 + 原因小字 | 点不动（ADR-12：不做假滑杆） |
 *
 * ⚠ "未设置"**绝不显示当前相机值**：套用这条预设时该字段保持不动，显示当前值会让用户以为
 * 预设里存着它（那是在骗用户）。
 *
 * 分组默认**展开"这条预设已包含项所在的分组"**、其余收起（面板限高 200 多 dp，全展开放不下）；
 * 点分组标题随时展开 / 收起。
 */
@Composable
private fun CamPresetParamList(
    /** 生效的"这条预设包含什么"（= 预设记录 − 移除 + 用户设的值） */
    slots: Map<Int, Double>,
    /** 滑杆读写数组（= `cameraPresetParams(target, values, removed)`） */
    params: DoubleArray,
    /** 归属预设 id：换预设 ⇒ 折叠态与滑杆 state 重建 */
    presetId: String,
    onEditParam: (EditorParam, Float) -> Unit,
    onEditFinished: () -> Unit,
    /** ★ P1：「移除该项」= 这条预设不再包含该字段 */
    onRemoveSlot: (Int) -> Unit,
    lut: PresetLutLine?,
    maxHeight: Dp,
) {
    val context = LocalContext.current
    // 展示的槽 = 拍照链路**可调**的全部 35 项 + **这条预设实际包含的不可调项**（灰显 + 原因）。
    // 后者不能省：用户必须知道"这条预设里还有几项在拍照时不生效"（ADR-12：既不假装生效，
    // 也不把它们藏起来 —— 藏起来的表现是"预设里有东西，界面上找不到"）。
    val tunable = CAMERA_TUNABLE_SLOT_PARAM.keys
    val sections = cameraParamSections(slots, tunable + slots.keys.filter { it !in tunable })
    // 展开的是哪一项 —— 记 `param.key` 而不是槽号：一条滑杆覆盖多槽（肩部 `[5..7]` /
    // 饱和度 `[20..22]`）时点其中任一行都是同一条滑杆，不能展开成三条。
    var openKey by remember(presetId) { mutableStateOf<String?>(null) }
    // 折叠态：初值 = **含已包含项的分组**（只算一次；拖动不该把用户折叠起来的分组又弹开）
    var openSections by remember(presetId) {
        mutableStateOf(
            sections.filter { sec -> sec.rows.any { it.value != null } }.map { it.title }.toSet(),
        )
    }
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
    ) {
        sections.forEach { sec ->
            val expanded = sec.title in openSections
            item(key = "sec_${sec.title}") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .pressScale(pressedScale = 0.98f, onTap = {
                            openSections = if (expanded) openSections - sec.title else openSections + sec.title
                        })
                        .padding(top = 5.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (expanded) "▾ " else "▸ ",
                        color = FilmTheme.Accent,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                    Text(
                        sec.title,
                        color = FilmTheme.Accent,
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    // "这一组里这条预设包含了几项"（一眼看出哪些组有内容）
                    Text(
                        "${sec.rows.count { it.value != null }}/${sec.rows.size}",
                        color = FilmTheme.CreamDim,
                        fontSize = 10.sp,
                        fontFamily = FilmTheme.Mono,
                        maxLines = 1,
                    )
                }
            }
            if (expanded) {
                items(sec.rows, key = { "row_${it.slot}" }) { r ->
                    // null = 这一项没有滑杆（拍照不生效 / 参数面板还没给滑杆）⇒ 灰显 + 原因
                    val param = CAMERA_TUNABLE_SLOT_PARAM[r.slot]
                    val note = cameraParamAdjustabilityNote(r.slot)
                    val open = param != null && openKey == param.key
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                // 可调 ⇒ 点一下展开 / 收起；不可调 ⇒ 手势不注册（按下去也没反馈，
                                // 不会有"点了没反应"的假控件感）
                                .pressScale(
                                    pressedScale = 0.98f,
                                    enabled = param != null,
                                    onTap = { openKey = if (open) null else param?.key },
                                )
                                .padding(vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                r.label,
                                color = if (note == null) FilmTheme.Cream else CamDisabledInk,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                // ★ "未设置"是**必需**的一档：它是稀疏子集的可视化，
                                // 也是"套用时未包含的字段保持不动"能被用户理解的前提
                                r.value ?: "未设置",
                                color = when {
                                    note != null -> CamDisabledInk
                                    r.value == null -> CamDisabledInk
                                    else -> FilmTheme.Cream
                                },
                                fontSize = 12.sp,
                                fontFamily = FilmTheme.Mono,
                                maxLines = 1,
                            )
                            if (param != null) {
                                Text(
                                    if (open) " ▾" else " ▸",
                                    color = FilmTheme.Accent,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                        // 不可调的原因（可调 ⇒ null ⇒ 不占行）
                        note?.let {
                            Text(
                                it,
                                color = CamDisabledInk,
                                fontSize = 9.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 2.dp, bottom = 1.dp),
                            )
                        }
                        // 展开的滑杆：`key(param.key)` ⇒ 换项 / 换预设时重建（[EditorSlider] 内部
                        // 用 remember 取初值，不重建就会沿用上一条参数的读数）
                        if (param != null && open) {
                            key(param.key) {
                                EditorSlider(
                                    param = param,
                                    params = params,
                                    onCommit = onEditParam,
                                    onFinished = onEditFinished,
                                )
                            }
                            // 「移除该项」：只有**已包含**的字段才谈得上移除
                            //（未设置的字段本来就不在这条预设里）
                            if (r.value != null) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, bottom = 3.dp),
                                ) {
                                    CamPresetActionButton("移除该项", enabled = true, onTap = { onRemoveSlot(r.slot) })
                                }
                            }
                        }
                    }
                }
            }
        }
        // LUT 与强度（若有）：[34] 由 lut.strength 承载 ⇒ 单独一行，不进参数清单
        lut?.let { line ->
            item(key = "lut") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "LUT",
                        color = FilmTheme.Cream,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${LutLibrary.byId(context, line.lutId)?.label ?: line.lutId} · 强度 ${line.strength}",
                        color = FilmTheme.Cream,
                        fontSize = 12.sp,
                        fontFamily = FilmTheme.Mono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 预设**编辑面板**（新建态 / 编辑态同一个面板）= 用户诉求的"配置面板"。
 *
 * 内容：**名称 / 分组输入框** + **全量参数表**（逐项可调，见 [CamPresetParamList]）+
 * 底部动作：
 *  - **新建态**（`p == null`）：「保存当前相机参数为新预设」（[newCameraPresetFrom]：当前预设 +
 *    当前改动，含 `[0]` 曝光偏移）+ 「取消」；
 *  - **编辑态**：「**保存到预设**」（把改动写进这条预设，含移除项与曝光偏移；无改动 ⇒ 置灰）、
 *    「从当前相机参数更新」（整包重写）、「保存名称 / 分组」、
 *    「**恢复默认**」（仅内置：删掉覆盖层回到出厂定义；改过才可点）、「删除」
 *    （**内置唯一受限的动作** —— 置灰 + 一句实话）。
 */
@Composable
private fun CameraPresetEditorPanel(
    /** null = 新建态 */
    p: EditorPreset?,
    /** ★ PS-3：滑杆的**归属**预设 —— 编辑态 = 那条预设；新建态 = 当前生效的预设 */
    target: EditorPreset,
    /** 参数表的数据源（= 预设记录 − 移除 + 用户设的值） */
    slots: Map<Int, Double>,
    /** 滑杆读写数组（= `cameraPresetParams(target, values, removed)`） */
    params: DoubleArray,
    /** 该 target 的未保存改动（null = 没有） */
    overrides: CameraParamOverrides?,
    lut: PresetLutLine?,
    name: String,
    onName: (String) -> Unit,
    group: String,
    onGroup: (String) -> Unit,
    buttons: PresetEditorButtons,
    readOnlyNote: String?,
    /** ★ PS-3：内置**改过** ⇒ 「恢复默认」可点（没改过就没有可恢复的东西 ⇒ 置灰） */
    hasOverride: Boolean,
    onEditParam: (EditorParam, Float) -> Unit,
    onEditFinished: () -> Unit,
    onRemoveSlot: (Int) -> Unit,
    onBack: () -> Unit,
    onSaveNew: () -> Unit,
    onUpdate: () -> Unit,
    onSaveSlots: () -> Unit,
    onRestore: () -> Unit,
    onSaveMeta: () -> Unit,
    onDelete: () -> Unit,
) {
    val isNew = p == null
    val hasChanges = overrides != null && !overrides.isEmpty
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        // ① 标题：返回 + 面板身份（新建 / 编辑）
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ 返回",
                color = FilmTheme.Accent,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, FilmTheme.ChipBorder, RoundedCornerShape(9.dp))
                    .pressScale(pressedScale = 0.94f, onTap = onBack)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (isNew) "新建预设" else "编辑预设",
                color = FilmTheme.Cream,
                fontSize = 14.sp,
                fontFamily = FilmTheme.Serif,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (!isNew) {
                Text(
                    "${presetSourceText(p.source)} · ${presetCreatedAtText(p.createdAt)}",
                    color = FilmTheme.CreamDim,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // ② 名称 / 分组（新建时预填默认名 ⇒ 保存按钮一路可点，不必先想名字）
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CamPresetField(name, onName, "预设名称", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            CamPresetField(group, onGroup, "分组（可空）", Modifier.weight(1f))
        }
        // ③ 参数表（★ PS-3：全量 + 逐项可调）+ 一行说明"这一行能做什么"
        Text(
            // 参考截图的「选择一项开始调整」+「实时效果 开」两句，在这里合成一句实话：
            // 拖动即时生效 + 未保存不落盘（用户最需要知道的两件事）
            "点一项开始调整 · 拖动即时生效（未保存不写入预设）",
            color = FilmTheme.CreamDim,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
        CamPresetParamList(
            slots = slots,
            params = params,
            presetId = target.id,
            onEditParam = onEditParam,
            onEditFinished = onEditFinished,
            onRemoveSlot = onRemoveSlot,
            lut = lut,
            maxHeight = 150.dp,
        )
        // ④ 动作：横向可滚（中文按钮在窄屏上放不下时不该被裁掉）
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isNew) {
                CamPresetActionButton("保存当前相机参数为新预设", buttons.saveNew, primary = true, onTap = onSaveNew)
                Spacer(Modifier.width(8.dp))
                CamPresetActionButton("取消", enabled = true, onTap = onBack)
            } else {
                // ★ PS-3：用户预设与内置预设**同一套按钮**（内置也能改）；差别只有：
                //   内置多一枚「恢复默认」、删除置灰（判据仍是 presetDeletable 这一个实现）
                CamPresetActionButton("保存到预设", hasChanges, primary = true, onTap = onSaveSlots)
                Spacer(Modifier.width(8.dp))
                CamPresetActionButton("从当前相机参数更新", buttons.updateFromCurrent, onTap = onUpdate)
                Spacer(Modifier.width(8.dp))
                CamPresetActionButton("保存名称 / 分组", buttons.saveMeta, onTap = onSaveMeta)
                Spacer(Modifier.width(8.dp))
                // 编辑态（isNew == false）⇒ p 一定非空
                if (!presetDeletable(p)) {
                    CamPresetActionButton("恢复默认", buttons.restore && hasOverride, onTap = onRestore)
                    Spacer(Modifier.width(8.dp))
                }
                CamPresetActionButton("删除", buttons.delete, onTap = onDelete)
            }
        }
        // 两个保存动作的差别（用户不必猜）：一句话说清，避免"点了没反应"的错觉
        if (!isNew) {
            Text(
                "「保存到预设」写入本次改动（含移除项与曝光偏移）·「从当前相机参数更新」整包重写（不含曝光偏移）",
                color = FilmTheme.CreamDim,
                fontSize = 10.sp,
                maxLines = 2,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        // 一句实话：内置预设唯一的限制是不能删（与菜单里那句同一条判据 / 同一份文案）
        readOnlyNote?.let { note ->
            Text(
                note,
                color = FilmTheme.CreamDim,
                fontSize = 10.sp,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

/**
 * 拖动中的改动持有者（PS-3）：**普通对象**，不参与 Compose 快照 ⇒ 拖动每帧写它不会触发
 * 任何重组（只有 [EditorSlider] 自己的拖动 state 在变）。松手时读它一次并提交上层 state。
 */
private class DragHolder {
    var value: CameraParamOverrides? = null
}
