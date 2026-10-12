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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmkit.app.camera.CAMERA_PATH_SLOTS
import com.filmkit.app.camera.CAMERA_STANDARD_PRESET
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.PARAM_EPS
import com.filmkit.app.camera.PRESET_SOURCE_USER
import com.filmkit.app.camera.PresetScene
import com.filmkit.app.camera.cameraPresetParams
import com.filmkit.app.camera.presetDeletable
import com.filmkit.app.camera.presetId
import com.filmkit.app.camera.presetScopeNotice
import com.filmkit.app.camera.presetSparseSlots
import com.filmkit.app.lut.LutLibrary
import kotlin.math.abs
import kotlin.math.roundToInt

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
 * ## ★ PS-6a（用户 2026-10-12 真机实测，v0.11.21）：**主视图按「图 2」重做 + 常用参数可调**
 *
 * 用户原话："**预设里滤镜强度都不可调？预设界面全部重做！！预设参数勾你妈的勾选，丑死……
 * 瞎搞**。**点击预设按图 2 那样展示**，并提供几个常用参数调节（**lut强度，颗粒开、关、档位，
 * 柔光档位，光晕档位，暗角调节**）。点击**完整参数**跳转类似**图1**页面提供完整、专业调节。"
 *
 * ⇒ **PS-5 的分组勾选树整体废弃**（用户否决的是"勾选框"这个形态，不是"能调参数"）：
 * `CamParamCheckTree` / `CheckAllRow` / `SectionTriCheck` / `CamParamRow` / `CamParamExpand` 与
 * 仅供它使用的纯函数（`CheckState` / `sectionCheckState` / `checkBoxText` / `triCheckText` /
 * `cameraTreeSlots` / `showsSliderRow` / `checkSlots` / `uncheckSlots` / `removeSlot`）**全删**。
 *
 * 新形态 = **选中的那条预设的详情卡**（图 2）：
 *  - 名称（大字）+ 副标题（来源 · 分组）+ **描述段落**（可展开收起，内容全部来自真实数据）；
 *  - 「当前搭配」+ 右上「**完整参数 >**」（回调 [CameraPresetTab] 的 `onOpenFullEdit`，本卡不做那页）；
 *  - **两列只读清单**（[presetOverviewRows]）：常用项折叠成"档位名 · 数值"（`颗粒 → 中颗粒 · 数量 30`）；
 *  - 一行提示「选择一项开始调整」+「实时效果 开」；
 *  - **常用参数调节区**（用户点名的 5 项）：LUT 强度滑杆 / 颗粒四档 / 柔光四档 / 光晕四档 / 暗角四档。
 *
 * 三条纪律（用户"每个能点的都必须有反应"）：
 *  1. **点清单里的一项就有反应**：有滑杆的项 ⇒ 下方出那一条滑杆（一次一项，`presetQuickPath`）；
 *     属于常用 5 项的 ⇒ 提示去下面哪个档位调；**两样都没有的 ⇒ 显示"为什么点不动"**（不做假控件）；
 *  2. **写值只有一条路**：档位/滑杆一律经 `EditorParam.write`（[afterEdit] 记账 ⇒ 拖动实时下发
 *     `onPreview`、松手 [onOverrides] 一次提交，不新增 state 通路）；
 *     `[26]/[27]` 颗粒没有既有滑杆 ⇒ 用本文件私有的 [GrainLevelParam]（档位自己的两个真实值）；
 *  3. **LUT 强度必须两路一起写**：`[34]` 会被 `CameraController.applyLutStrengthPref` 用 prefs
 *     覆盖 ⇒ 拖动时同时写 prefs 的 `lut_strength`（引擎侧既有事实源），否则"拖了画面不变"。
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
 * ★ PS-6a：**LUT 强度 `[34]` 的记账**（与其它槽不同，必须特判 —— 否则"拖到 100%"会丢账）。
 *
 * 为什么不能直接信任 [afterEdit] 的结果：它把"用户值"与**这条预设自己的配方**逐槽比对，而
 * `[34]` 由 [EditorPreset.lutStrength] 承载、**不进 `slots`**（`EditorExport.kt` 的
 * `presetSlotIncluded` 排除它）⇒ [cameraPresetParams] 展开出来的 `[34]` 恒为中性基线 1.0，
 * 不是这条预设的强度。后果：预设强度 0.3、用户拖到 1.0（恰好等于基线值）⇒ [afterEdit] 认为
 * "没改动"而丢账 ⇒「保存到预设」把 100% 存成 30%（**点了没反应**，正是用户投诉的那类假控件）。
 *
 * 本条口径：与这条预设**自己的** [EditorPreset.lutStrength] 比 ——
 *  - 不同 ⇒ 记进 [CameraParamOverrides.values]`[34]`（保存时写回 `lutStrength`，见
 *    [saveSlotsWithLutStrength]）；
 *  - 拖回预设原值 ⇒ 撤销这次调整（与 [afterEdit] 的"拖回原值 = 撤销"同一条纪律）。
 */
internal fun CameraParamOverrides?.afterLutStrengthEdit(
    preset: EditorPreset,
    uiValue: Float,
): CameraParamOverrides {
    val next = afterEdit(preset, LutStrengthParam, uiValue)
    val v = uiValue.coerceIn(0f, 1.5f).toDouble()
    return if (abs(v - preset.lutStrength) <= PARAM_EPS) {
        next.copy(values = next.values - LUT_STRENGTH_SLOT)
    } else {
        next.copy(values = next.values + (LUT_STRENGTH_SLOT to v))
    }
}

/**
 * ★ PS-6a：「保存到预设」前把 `[34]`（LUT 强度）**带回** [EditorPreset.lutStrength]。
 *
 * 它不进 `slots`（同上）⇒ 保存通路必须显式带上，否则用户在预设详情里拖了强度、点了保存、
 * 重新打开发现强度没变（= 假控件）。`[34]` 与 `lutStrength` 是**同一个数的两个落点**，不是两份。
 */
internal fun saveSlotsWithLutStrength(
    preset: EditorPreset,
    mine: CameraParamOverrides?,
): EditorPreset = mine?.values?.get(LUT_STRENGTH_SLOT)
    ?.takeIf { it.isFinite() }
    ?.let { preset.copy(lutStrength = it) }
    ?: preset

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
// ★ PS-6a：**常用参数（图 2 的 5 项）**的纯逻辑
//
// 用户 2026-10-12 真机实测点名要这五项："**lut强度，颗粒开、关、档位，柔光档位，光晕档位，
// 暗角调节**"。本节只做两件事：
//   (a) **档位映射**（档位 → UI 值 / 当前值 → 档位），值写进数组仍走 `EditorParam.write`
//       （`afterEdit` 记账 ⇒ 写值口径只有一份，见 [CameraParamOverrides.afterEdit]）；
//   (b) **「当前搭配」清单**（[presetOverviewRows]）：图 2 的只读概览，常用项折叠成
//       "档位名 · 数值"（`颗粒 → 中颗粒 · 数量 30`），数据源仍是稀疏集的唯一维护点
//       [presetDetailSections]。
//
// ⚠ **不做"包含 / 不包含"这个概念**（PS-5 的勾选树连同它的 `checkSlots` / `uncheckSlots` /
// `removed` 入口一并废弃）：用户否决的就是"勾选"。要"这条预设不管某一项" ⇒ 把该项调回中性值
// 再保存（稀疏化会自动丢弃它 —— 与 PS-5 的 I3 是同一条既有口径，不是本卡新引入的行为）。
// ─────────────────────────────────────────────────────────────────────────────

/** 四档位的名字：`关闭` / 轻档（逐项给字面）/ `中等…` / `强烈…`。 */
private fun levelNames(light: String, label: String): List<String> =
    listOf("关闭", light, "中等$label", "强烈$label")

/**
 * 档位型常用参数（图 2 的四行档位）——**写值走 `CAMERA_TUNABLE_SLOT_PARAM[slot]` 那条既有滑杆**
 * （[EditorParam.write]），本节只提供"档位 → UI 值"的映射表。
 *
 * @param label 清单行名（"柔光"）
 * @param slot 引擎槽位（`[95]` / `[62]` / `[66]`）
 * @param levels 四档的 **UI 值**（与 [names] 同序；依据逐条见下面的常量注释）
 * @param names 四档的**档位名**（图 2 字面）—— 逐项给字面而不是"前缀 + 参数名"拼：
 *   中文里"轻柔光"顺口（图 2 的字面），而"轻" + "光晕" = "轻光晕"是生造词（摄影惯用"轻微光晕"）
 */
internal class QuickLevelParam(
    val label: String,
    val slot: Int,
    val levels: List<Float>,
    val names: List<String>,
)

// ★ 档位数值的依据（用户要求"每个档位写明依据"）——三类各有一句实话，不编造"行业惯例"：
//  ① **关闭 = 引擎中性值**：`[62]`/`[95]` 的 0 = 整链旁路（零 pass、零纹理分配），
//     `[66]` 的 0 = `kFsOut` 内整段跳过（来源 = `CameraPresets.neutral()` 的逐行注释）；
//  ② **内置三条胶片预设没有开过光晕 / 柔光**（`app/src/main/assets/presets/builtin.json` 里
//     没有 halation / glow 字段）⇒ 这两项的轻/中/强**没有"参考内置"可依据**，只能按
//     **面板量程 0..1 四等分**（0.25 / 0.50 / 0.75）—— 如实写在报告里；
//  ③ **暗角有内置依据**：三条内置的 `vignette_strength` = −0.18 / −0.28 / −0.24
//     （UI 读数 = 正值 0.18 / 0.28 / 0.24）⇒ 轻档 0.20（= 内置最小档的最近整值）、
//     中档 0.40（内置之上再加一档，0.2 步进）、强档 0.70（同一步进的第三档 = 明显但不糊）。
internal val GLOW_LEVEL_PARAM =
    QuickLevelParam("柔光", 95, listOf(0f, 0.25f, 0.5f, 0.75f), levelNames("轻柔光", "柔光"))
internal val HALO_LEVEL_PARAM =
    QuickLevelParam("光晕", 62, listOf(0f, 0.25f, 0.5f, 0.75f), levelNames("轻微光晕", "光晕"))
internal val VIGNETTE_LEVEL_PARAM =
    QuickLevelParam("暗角", 66, listOf(0f, 0.2f, 0.4f, 0.7f), levelNames("轻微暗角", "暗角"))

/** 图 2 的三个档位行（顺序 = 组内既有顺序：柔光/光晕同族、暗角在其后，与 `TextureParams` 一致）。 */
internal val QUICK_LEVEL_PARAMS =
    listOf(GLOW_LEVEL_PARAM, HALO_LEVEL_PARAM, VIGNETTE_LEVEL_PARAM)

/** 颗粒强度 `[26]`（0..100，**0 = 整段旁路**）与尺寸 `[27]`（**ISO 语义**，20..6400，越大越粗）。 */
internal const val GRAIN_AMOUNT_SLOT = 26
internal const val GRAIN_SIZE_SLOT = 27

/**
 * ★ 颗粒档位（用户点名："颗粒开、关、档位"）—— 档位 = **[26] 强度 + [27] 尺寸的组合**，
 * 依据逐条写死在这里（数值全部有出处）：
 *
 * | 档位 | `[26]` 强度 | `[27]` 尺寸 | 依据 |
 * |---|---|---|---|
 * | 关闭 | 0 | 400 | **引擎中性值**（`CameraPresets.neutral()`：`[26] = 0` 整段旁路、`[27] = 400` 标定起点）|
 * | 细颗粒 | 20 | 160 | 内置「徕卡一瞬」逐字（`grain_amount 20` / `grain_size 160`，note = "细颗粒"）|
 * | 中颗粒 | 30 | 400 | 内置「理光负片」逐字（`30` / `400`，note = "中颗粒"；400 同时是引擎恒等值）|
 * | 粗颗粒 | 40 | 800 | 强度 = 内置「徕卡黑白」逐字 40（note = "明显颗粒"）；尺寸 = 400 的**下一 ISO 标准档** 800 |
 *
 * ⚠ 一条档位**同时写两个槽**（与饱和度的"一条滑杆同写三槽"同型）⇒ 档位是"组合"，不是两个控件。
 */
internal enum class GrainLevel(val label: String, val amount: Double, val iso: Double) {
    OFF("关闭", 0.0, 400.0),
    FINE("细颗粒", 20.0, 160.0),
    MEDIUM("中颗粒", 30.0, 400.0),
    COARSE("粗颗粒", 40.0, 800.0),
}

/**
 * 当前颗粒档位（按 `[26]` **强度**分档：分界 25 / 35 = 内置三条 20 / 30 / 40 的中点）。
 *
 * 为什么按强度而不是尺寸：尺寸只决定"颗粒长什么样"，决定"有没有 / 多明显"的是强度
 * （ADR-10：`[26] = 0` 整段旁路）—— 三条内置按本函数读出的档位与 JSON 的 note 逐条一致。
 */
internal fun grainLevelOf(amount: Double): GrainLevel {
    if (!amount.isFinite() || amount <= 0.0) return GrainLevel.OFF
    return when {
        amount < 25.0 -> GrainLevel.FINE
        amount < 35.0 -> GrainLevel.MEDIUM
        else -> GrainLevel.COARSE
    }
}

/** [grainLevelOf] 的数组形态（读"当前生效参数"用）。 */
internal fun grainLevelOf(params: DoubleArray): GrainLevel =
    grainLevelOf(if (GRAIN_AMOUNT_SLOT in params.indices) params[GRAIN_AMOUNT_SLOT] else 0.0)

/**
 * 颗粒档位的**写入器**：复用 [EditorParam] 的 `write` 通道（[CameraParamOverrides.afterEdit]
 * 的记账因此与其它参数走在同一条路上）。
 *
 * 它不是"第二份参数表"：颗粒在编辑器参数面板里**从来没有滑杆**（`CAMERA_NO_SLIDER_SLOTS`），
 * 写进数组的就是 [GrainLevel] 自己的两个真实值 —— 量程 / 读数 / 写值口径全部来自那个枚举，
 * 不存在第二套数字。`range` 只是"档位序号 0..3"（界面上是 4 个档位按钮，不是滑杆）。
 */
internal val GrainLevelParam = EditorParam(
    key = "grainLevel", label = "颗粒",
    range = 0f..3f, pivot = 0f,
    format = { v -> GrainLevel.entries[v.roundToInt().coerceIn(0, GrainLevel.entries.lastIndex)].label },
    read = { p -> grainLevelOf(p).ordinal.toFloat() },
    write = { p, v ->
        val lv = GrainLevel.entries[v.roundToInt().coerceIn(0, GrainLevel.entries.lastIndex)]
        p[GRAIN_AMOUNT_SLOT] = lv.amount
        p[GRAIN_SIZE_SLOT] = lv.iso
    },
)

/**
 * **最近档**判定（[levels] 与档位同序）：写进去的档位值必须能读回同一档（闭环由单测钉住）。
 * 坏值（NaN）⇒ 0（关闭）—— NaN 的任何比较都是 false，不会污染结果。
 */
internal fun nearestLevel(ui: Float, levels: List<Float>): Int {
    var best = 0
    var bestD = Float.MAX_VALUE
    for (i in levels.indices) {
        val d = abs(levels[i] - ui)
        if (d < bestD) {
            bestD = d
            best = i
        }
    }
    return best
}

/**
 * 常用参数覆盖的槽（图 2 的 5 项）：`[34]` LUT 强度 / `[26][27]` 颗粒 / `[95]` 柔光 /
 * `[62]` 光晕 / `[66]` 暗角。
 */
internal val QUICK_ADJUST_SLOTS: Set<Int> =
    linkedSetOf(LUT_STRENGTH_SLOT, GRAIN_AMOUNT_SLOT, GRAIN_SIZE_SLOT, 95, 62, 66)

/** 常用的**参数组名**（清单行 → 快捷控件名）：`[26][27]` ⇒ 颗粒、`[95]` ⇒ 柔光…。 */
internal fun quickAdjustLabelOf(slot: Int): String? = when (slot) {
    LUT_STRENGTH_SLOT -> "滤镜强度"
    GRAIN_AMOUNT_SLOT, GRAIN_SIZE_SLOT -> "颗粒"
    // 其余三槽的名字从档位表派生（**不写第二份字面**）
    else -> QUICK_LEVEL_PARAMS.firstOrNull { it.slot == slot }?.label
}

/**
 * 清单里的一项**点下去会发生什么**（用户"每个能点的都必须有反应"）：
 *  - [QUICK] ⇒ 下面常用参数区里有对应的档位/滑杆（图 2 的 5 项）；
 *  - [SLIDER] ⇒ 下方出这一项的滑杆（一次一项，`EditorSlider`）；
 *  - [NONE] ⇒ 两项都没有 ⇒ **不许注册手势**，只显示"为什么点不动"（ADR-12，不做假控件）。
 */
internal enum class PresetQuickPath { QUICK, SLIDER, NONE }

/** [PresetQuickPath] 的判据（唯一实现：UI 与单测都读它）。 */
internal fun presetQuickPath(slot: Int): PresetQuickPath = when {
    slot in QUICK_ADJUST_SLOTS -> PresetQuickPath.QUICK
    slot in CAMERA_TUNABLE_SLOT_PARAM -> PresetQuickPath.SLIDER
    else -> PresetQuickPath.NONE
}

/** 「当前搭配」清单的一行（图 2 的只读概览）：参数名 + 值。 */
internal data class PresetOverviewRow(val slot: Int, val label: String, val value: String)

/**
 * ★ 图 2 的「**当前搭配**」清单：这条预设**包含**的项逐行（数据源 = [presetDetailSections]，
 * 稀疏集的唯一维护点），并把常用项折叠成"**档位名 · 数值**"（图 2 的 `颗粒 → 中颗粒 · 数量 30`）。
 *
 * 三条折叠规则：
 *  - `[27]` 颗粒尺寸**并进** `[26]` 那一行（图 2 里颗粒是一行，不是两行）；
 *  - `[34]` LUT 强度**不在 `slots` 里**（由 [EditorPreset.lutStrength] 承载）⇒ 由 [lut] 参数
 *    单独出第一行「滤镜 → 名字 · 强度」（图 2 的第一行）；
 *  - 关闭档只显示「关闭」（`关闭 · 数量 0` 是废话）。
 *
 * ⚠ 只读：清单里的值**只来自这条预设**（含未保存的调整），**绝不显示当前相机值冒充** ——
 * 未包含的项根本不出现（"当前搭配"的语义），不是显示成「—」也不是显示成当前值。
 *
 * @param slots 这条预设生效的槽（= `preset.slots` − 移除 + 用户刚设的值）
 * @param lut [presetLutLine]（null = 这条预设不含 LUT ⇒ 不出滤镜行）
 * @param lutLabel LUT 的中文名（UI 层查 `LutLibrary`，纯逻辑层不碰 Context）
 */
internal fun presetOverviewRows(
    slots: Map<Int, Double>,
    lut: PresetLutLine? = null,
    lutLabel: String? = null,
): List<PresetOverviewRow> {
    val out = ArrayList<PresetOverviewRow>()
    lut?.let { out += PresetOverviewRow(LUT_STRENGTH_SLOT, "滤镜", "${lutLabel ?: it.lutId} · ${it.strength}") }
    for (row in presetDetailSections(slots).flatMap { it.rows }) {
        val raw = slots[row.slot]
        when (row.slot) {
            // 尺寸并进颗粒那一行（图 2 的一行 = 档位名 + 数量）
            GRAIN_SIZE_SLOT -> Unit
            GRAIN_AMOUNT_SLOT -> {
                val lv = grainLevelOf(raw ?: 0.0)
                out += PresetOverviewRow(
                    row.slot, "颗粒",
                    if (lv == GrainLevel.OFF) lv.label else "${lv.label} · 数量 ${row.value}",
                )
            }
            95 -> out += levelOverviewRow(GLOW_LEVEL_PARAM, raw, row.value)
            62 -> out += levelOverviewRow(HALO_LEVEL_PARAM, raw, row.value)
            66 -> out += levelOverviewRow(VIGNETTE_LEVEL_PARAM, raw, row.value)
            else -> out += PresetOverviewRow(row.slot, row.label, row.value)
        }
    }
    return out
}

/** 档位行（柔光 / 光晕 / 暗角）：档位名 + （非关时）数值。UI 读数与滑杆**同一个换算**。 */
private fun levelOverviewRow(
    quick: QuickLevelParam,
    raw: Double?,
    detail: String,
): PresetOverviewRow {
    val param = CAMERA_TUNABLE_SLOT_PARAM.getValue(quick.slot)
    // 用滑杆自己的 read（`vigStrengthUi` / `normalizedUi`）算读数：坏值回落 pivot ⇒ 不会读出 NaN
    val arr = DoubleArray(quick.slot + 1)
    if (raw != null && raw.isFinite()) arr[quick.slot] = raw
    val idx = nearestLevel(param.read(arr), quick.levels)
    // 档位名与档位按钮**同一份字面**（[QuickLevelParam.names]）；关闭档不带数值（"关闭 · 0" 是废话）
    val text = if (idx == 0) quick.names[0] else "${quick.names[idx]} · $detail"
    return PresetOverviewRow(quick.slot, quick.label, text)
}

/**
 * ★ PS-7：详情卡描述 = **一行真实信息**（用户 2026-10-12 真机截图直斥"一坨屎"，逐条点名：
 * "副标题与描述重复" + "描述文案是机器拼接"）。
 *
 * 旧实现把来源 / 分组 / 项数 / 分组名清单 / LUT / 套用语义拼成一段
 * （`内置预设 · 分组「徕卡」· 包含 17 项参数（基础调整 / 色彩 / …`）—— 前半句与副标题逐字重复，
 * 后半句是机器拼接且被截断。现在：
 *  - **副标题已说的不再重复**（来源 / 分组归副标题）；
 *  - 只留两项**预设记录里真实存在**的信息：参数项数 + 滤镜名；
 *  - 两样都没有 ⇒ **返回 null ⇒ 整行不渲染**（没有真实描述就不显示，不拼字符串凑）。
 */
internal fun presetOverviewText(p: EditorPreset, lutLabel: String? = null): String? {
    val n = presetDetailSections(p.slots).sumOf { it.rows.size }
    val lut = presetLutLine(p)?.let { "滤镜 ${lutLabel ?: it.lutId}" }
    return listOfNotNull(if (n > 0) "$n 项参数" else null, lut)
        .joinToString(" · ")
        .ifEmpty { null }
}

// ─────────────────────────────────────────────────────────────────────────────
// ★ PS-7：详情卡**点得动**的判据 + 高度预算
//
// 用户 2026-10-12 第二轮真机实测（v0.11.22）："**这个界面的参数都动不了，要进完整参数才能调节？
// 那我要这个界面干嘛？**" —— 病根是**布局**：PS-6a 的详情卡 `heightIn(262.dp)` + `verticalScroll`
// 早就装不下内容，点一行之后滑杆确实插到了那一行下方，但**插在滚动区的屏幕外** ⇒ 用户什么都
// 没看见 ⇒ 结论就是"动不了"。所以详情卡本身必须是"**能直接调的清单**"：
//   (a) [presetRowTap]：任何槽位点下去都落在三态之一（档位高亮 / 一条滑杆 / 原因小字），
//       **没有第四态** ⇒ "点了什么都不发生"在判据层面就不存在；
//   (b) [presetPanelBudget] / [presetOverviewMaxHeightDp]：常用参数与选中项滑杆都放在**固定区**
//       （卡片不再整卡滚动），清单才限高可展开 —— 两条都同屏，列表也不被挤没。
// ─────────────────────────────────────────────────────────────────────────────

/** 点清单里一行会发生什么（[PresetQuickPath] 的界面口径：三态都必须有**看得见**的反应）。 */
internal enum class PresetRowTap {
    /** 常用档位项 ⇒ 常用参数区里对应那一行**高亮**（就在卡片首屏，不用滚）。 */
    LEVEL,

    /** 有滑杆的项 ⇒ 卡片固定区出**这一条**滑杆（一次一项，紧贴清单上方）。 */
    SLIDER,

    /** 确实没有滑杆的项 ⇒ 行下小字写明原因（[cameraParamAdjustabilityNote] 必须非空）。 */
    NOTE,
}

/**
 * ★ 判据（用户："不许出现'点了一项、界面没有任何变化'的路径"）：[presetQuickPath] 的三态
 * QUICK / SLIDER / NONE **一一对应**三态反应 ⇒ 任何槽位点下去都有落点，没有"无反应"分支。
 */
internal fun presetRowTap(slot: Int): PresetRowTap = when (presetQuickPath(slot)) {
    PresetQuickPath.QUICK -> PresetRowTap.LEVEL
    PresetQuickPath.SLIDER -> PresetRowTap.SLIDER
    PresetQuickPath.NONE -> PresetRowTap.NOTE
}

/**
 * 值位文本：**只放真实值**，没有值就「—」。
 *
 * 用户截图里 `分离色调 · 暖 → 拍照生效 · 暂无需滑杆` 的毛病 = "这一项为什么点不动"的说明混进了
 * 值位 ⇒ 值位语义被污染。说明一律走行下小字（[PresetOverviewRowText]），值位只认值。
 */
internal fun presetRowValueText(value: String): String = value.ifBlank { "—" }

/** 清单收起时可见的行数（超过 ⇒ 出「展开」）。 */
internal const val PRESET_OVERVIEW_ROWS_COLLAPSED = 3

/** 清单展开后的行数上限（再多则清单区内部滚动，卡片高度不涨）。 */
internal const val PRESET_OVERVIEW_ROWS_EXPANDED = 8

/** 清单行的**估算行高**（限高换算用；带小字的行更高，清单区本身可滚 ⇒ 只影响"露几行"）。 */
internal const val PRESET_OVERVIEW_ROW_DP = 24f

/** 清单区最多显示几行（收起 3 / 展开 8；总行数更少 ⇒ 按总行数）。 */
internal fun presetOverviewShownRows(total: Int, expanded: Boolean): Int = minOf(
    total.coerceAtLeast(0),
    if (expanded) PRESET_OVERVIEW_ROWS_EXPANDED else PRESET_OVERVIEW_ROWS_COLLAPSED,
)

/** 清单区的高度上限（0 行 ⇒ 0dp ⇒ 不占位）。 */
internal fun presetOverviewMaxHeightDp(total: Int, expanded: Boolean): Float =
    presetOverviewShownRows(total, expanded) * PRESET_OVERVIEW_ROW_DP

/** 详情卡与预设列表的高度预算（dp）——两条**同屏**，展开清单时列表让位。 */
internal data class PresetPanelBudget(val detailDp: Float, val listDp: Float)

/**
 * ★ 面板高度分配（用户："别让列表被挤没，也别让常用参数被挤走"）：
 *  - 详情卡 = 名称 / 一行副标题 / 一行描述 / **常用参数（含 LUT 强度滑杆）** / 「当前搭配」标题 /
 *    选中项滑杆 / 清单 / 提示行 / 「保存到预设」全部放得下的预算（展开清单多给 40dp）；
 *  - 预设列表 = 剩下的（≥ 110dp = 两行可见，自身可滚）。
 *
 * 两者之和恒为 [PRESET_PANEL_BUDGET_DP] ⇒ 展开只让两者**此消彼长**，不会把列表挤出屏幕。
 */
internal fun presetPanelBudget(overviewExpanded: Boolean): PresetPanelBudget =
    if (overviewExpanded) {
        PresetPanelBudget(detailDp = 510f, listDp = 110f)
    } else {
        PresetPanelBudget(detailDp = 470f, listDp = 150f)
    }

/** 整块面板的既定高度预算（= 详情卡 + 预设列表；见 [presetPanelBudget]）。 */
internal const val PRESET_PANEL_BUDGET_DP = 620f

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
 * ## ★ PS-7：详情卡 = **能直接调的清单**（用户 2026-10-12 真机截图两轮）
 *
 *  第一轮"你做这一坨屎是什么"（半透明底透出取景界面 / 常用参数被挤出首屏 / 副标题与描述重复 /
 *  描述是机器拼接 / 值位混入内部标注）与第二轮"**这个界面的参数都动不了，要进完整参数才能调节？
 *  那我要这个界面干嘛？**"是同一条病根：**卡片自己滚，用户要的东西在屏幕外**。
 *
 *  主视图（非编辑态）= 列表**上方**的详情卡（顶部到底部的顺序 = 视觉优先级）：
 *   ① 名称（大字）+「内置」标记；
 *   ② 副标题**一行**：来源 · 分组；
 *   ③ 描述**一行真实信息**：[presetOverviewText]（项数 · 滤镜名；没有 ⇒ null ⇒ 整行不渲染）；
 *   ④ **常用参数**（用户点名的 5 项）：LUT 强度滑杆 / 颗粒四档 / 柔光四档 / 光晕四档 / 暗角四档
 *      —— **首屏固定区**（在清单之前，且卡片不整卡滚动 ⇒ 不用滚就能调）；
 *   ⑤ 「当前搭配」+「展开 N 项 / 收起」+「**完整参数 >**」（[onOpenFullEdit]，PS-6b 的页面）；
 *   ⑥ **选中项的滑杆**（固定槽，在清单上方 ⇒ 点了立刻看得见，见下）；
 *   ⑦ 两列清单（[presetOverviewRows]）：**限高** 3 行 ↔ 8 行（[presetOverviewMaxHeightDp]）；
 *   ⑧ 提示行「选择一项开始调整」/ 已选项的去处 +「实时效果 开」；
 *   ⑨ 有未保存改动时出「保存到预设」（[saveSlotsWithLutStrength] 把 `[34]` 带回 `lutStrength`）。
 *
 *  **点点看**（判据 [presetRowTap]，三态 = 三态反应，**没有第四态**）：
 *   - 常用档位项（[PresetRowTap.LEVEL]）⇒ 常用参数区里对应那一行**高亮**；
 *   - 有滑杆的项（[PresetRowTap.SLIDER]）⇒ 卡片固定槽出那**一条**滑杆（一次一项，带「选中项」标头）；
 *   - 没有滑杆的项（[PresetRowTap.NOTE]）⇒ 行下小字写明原因（ADR-12，不做假控件）。
 *
 *  ## 四条纪律
 *
 *  1. **不做假控件**：能点 = 真的会变（[presetRowTap] / `cameraParamAdjustability`）；不能调的
 *     项不给滑杆，但**必须给出可见反应**（原因小字 + 提示行），不许"点了什么都不发生"。
 *  2. **拖动不写上层 state**：拖动每帧只调 [onPreview]（直通引擎），松手才 [onOverrides] ——
 *     每帧写 `CameraScreen` 的 state 会把整页拖进重组（v0.10.96 的 LUT 强度滑杆踩过同型坑）。
 *  3. **未保存不落盘**：只有保存动作写库（用户预设 → `PresetStore`；内置 → 覆盖层）。
 *  4. **编辑对象 == 生效对象**：详情卡永远对应 `selected`（见 [openEdit] 的不变量）—— 画面只有
 *     一份，"实时效果"必须有明确归属 ⇒ [onPreview] 因此永远作用于 `selectedPreset`。
 *
 * ## LUT 强度 `[34]` 为什么必须同时写 prefs
 *
 * 引擎侧 `CameraController.setFilmParams` 之后会走 `applyLutStrengthPref()`：只要 prefs 的
 * `lut_strength` 与上次记录不同，就把 `filmParams[34]` **覆盖**成 prefs 的值 ⇒ 只把 `[34]`
 * 放进下发数组是**无效**的（拖了画面不变 = 假控件）。所以拖动时同时写 prefs 这条**既有事实源**
 * （LUT tab 的强度滑杆走的是同一条），保存时再经 [saveSlotsWithLutStrength] 落到预设的
 * [EditorPreset.lutStrength]。
 *
 * @param presets `BuiltinPresets.all(context, prefs)`（用户预设在前 + 内置三条在后，含覆盖层）
 * @param selected 当前生效的预设（列表首项恒为 [CAMERA_STANDARD_PRESET]）
 * @param lutId / lutStrength 当前相机选中的 LUT 与强度（新建态 / "这条预设不含 LUT"的判据）
 * @param overrides ★ PS-3：当前**未保存**的改动（null = 没有；带归属 id ⇒ 切预设自动失效）
 * @param builtinOverrides ★ PS-3：内置覆盖表（列表标「已改」+「恢复默认」可用性）
 * @param onPreview ★ PS-3：拖动中 ⇒ 立即下发取景（**不要**在这里写 Compose state）
 * @param onOverrides ★ PS-3：提交改动（松手 / 点档位时；null = 清空）
 * @param onSave 新建（列表「+ 新建预设」）：名称 / 分组 / 改动
 * @param onUpdate 「从当前相机参数更新」：整包重写该预设的配方（既有能力，PS-3 带上改动）
 * @param onSaveSlots ★ PS-3：「保存到预设」= 把改动写进该预设（含 `[0]` 曝光偏移与 `[34]` 强度）
 * @param onRestore ★ PS-3：「恢复默认」（内置）—— 删掉覆盖层，回到出厂定义
 * @param onOpenFullEdit ★ PS-6a：「完整参数 >」—— 打开完整参数页（PS-6b 在 `CameraScreen` 里实现）
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
    /**
     * ★ PS-6a：「**完整参数 >**」—— 打开完整参数页（PS-6b 在 `CameraScreen` 里传实际实现）。
     * 默认空实现 ⇒ 本卡不依赖那一页，也不做它（两张卡只靠这一个回调对接）。
     */
    onOpenFullEdit: () -> Unit = {},
) {
    val context = LocalContext.current
    // ★ PS-6a：LUT 强度的**既有事实源**（`CameraController.applyLutStrengthPref` 每帧读它覆盖
    // `filmParams[34]`）⇒ 预设详情里拖强度必须同时写这里，否则下发无效（见 [pushEdit] 的 KDoc）。
    val prefs = remember(context) { context.getSharedPreferences("filmkit", 0) }
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
    // ★ PS-7：详情卡「当前搭配」是否展开（换预设 ⇒ 回到收起）。状态放在这里的原因只有一个：
    // 详情卡与下面的预设列表**共用一份高度预算**（见 [presetPanelBudget]），展开要让列表让位。
    var overviewExpanded by remember(selected.id) { mutableStateOf(false) }

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

    // ★ PS-3：拖动中的改动持有者 —— **普通对象**（不参与 Compose 快照）⇒ 拖动每帧写它
    // 不会触发任何重组（只有 [EditorSlider] 自己的拖动 state 在变）。松手时才经
    // [onOverrides] 提交一次（上层 state 因此每帧都不动）。主视图与编辑面板共用（同时只有一个在屏上）。
    val drag = remember { DragHolder() }
    // **编辑对象**：编辑态 = 那条预设；新建态 / 主视图 = 当前生效的预设
    //（"新建 = 以当前画面为起点"；主视图的详情卡永远对应 `selected`，见 `openEdit` 的不变量）
    val target = editor ?: selected
    val mine = overrides.forPreset(target)
    val values = mine?.values ?: emptyMap()
    val removed = mine?.removed ?: emptySet()
    // 「当前搭配」显示的"这条预设包含什么" = 预设记录 − 移除 + 用户刚设的值
    //（**不含**没设过值的槽 ⇒ 它们不出现在清单里，绝不用当前相机值冒充）
    val shown = (target.slots - removed) + values
    // 滑杆读写数组（= 当前生效参数：中性基线 + 稀疏覆盖）
    val params = cameraPresetParams(target, values, removed)
    // LUT 强度 `[34]`：不进 `slots` ⇒ 界面上的当前值 = 未保存的改动 → 预设自己的 → 新建态的当前相机值
    val lutStrengthNow = values[LUT_STRENGTH_SLOT]?.toFloat()
        ?: if (editorNew) lutStrength else target.lutStrength.toFloat()
    // 有没有 LUT：新建态 = 当前相机选的 LUT；其余 = 这条预设自己的 LUT
    val hasLut = (if (editorNew) lutId else target.lutId) != null

    /**
     * ★ PS-6a：**写值的唯一路径**（滑杆拖动 / 档位按钮都走这里；不新增 state 通路）。
     *
     * `[34]` LUT 强度是**唯一**一条特例：引擎侧 `CameraController.setFilmParams` 之后会调用
     * `applyLutStrengthPref()`，只要 prefs 的 `lut_strength` 变了就把 `filmParams[34]` **覆盖**
     * 成 prefs 的值 ⇒ 只把 `[34]` 放进下发数组是**无效**的（拖动画面不变 = 假控件）。
     * 所以这里同时写 prefs 这条**既有事实源**（LUT tab 的强度滑杆走的是同一条），
     * 先写 prefs 再 [onPreview]，`setFilmParams` 读到的就是新值。
     */
    fun pushEdit(param: EditorParam, v: Float) {
        val next = if (param.key == LutStrengthParam.key) {
            prefs.edit().putFloat("lut_strength", v.coerceIn(0f, 1.5f)).apply()
            overrides.afterLutStrengthEdit(target, v)
        } else {
            overrides.afterEdit(target, param, v)
        }
        drag.value = next
        onPreview(next)
    }

    /** 松手（[EditorSlider] 的 onFinished）⇒ **一次提交**（拖动中一位 state 都不写）。 */
    fun finishEdit() {
        drag.value?.let { onOverrides(it) }
        drag.value = null
    }

    /** 档位按钮 = **一次性动作**（不是拖动）⇒ 写值 + 立刻提交（否则「保存到预设」不会亮）。 */
    fun pickLevel(param: EditorParam, uiValue: Float) {
        pushEdit(param, uiValue)
        finishEdit()
    }

    if (editorOpen) {
        val p = editor
        CameraPresetEditorPanel(
            p = p,
            target = target,
            params = params,
            lutStrength = lutStrengthNow,
            hasLut = hasLut,
            hasChanges = mine != null && !mine.isEmpty,
            name = name,
            onName = { name = it },
            group = group,
            onGroup = { group = it },
            // 拍照界面的参数恒可就绪（cameraPresetParams 是纯函数）⇒ paramsReady = true
            buttons = presetEditorButtons(editorNew, p, paramsReady = true, name = name),
            readOnlyNote = p?.let { presetReadOnlyNote(it) },
            // 内置改过 ⇒ 「恢复默认」可点（覆盖表里有这个 id）
            hasOverride = p != null && builtinOverrides.containsKey(p.id),
            onEditParam = { param, v -> pushEdit(param, v) },
            onEditFinished = { finishEdit() },
            onPickLevel = { param, v -> pickLevel(param, v) },
            onOpenFullEdit = onOpenFullEdit,
            onBack = { closeEditor() },
            onSaveNew = {
                // 新建 = 当前生效预设 + 当前改动（含 `[0]` 曝光偏移，见 newCameraPresetFrom）
                onSave(name, group, overrides.forPreset(target))
                closeEditor()
            },
            onUpdate = { p?.let { onUpdate(it, overrides.forPreset(it)) } },
            onSaveSlots = {
                p?.let {
                    onSaveSlots(saveSlotsWithLutStrength(it, overrides.forPreset(it)), overrides.forPreset(it))
                    onOverrides(null)
                }
            },
            onRestore = { p?.let { onRestore(it) } },
            onSaveMeta = { p?.let { onRename(it, name, group) } },
            onDelete = { p?.let { pendingDelete = it } },
        )
    } else {
        // ★ PS-7：清单展开状态提升到这里 —— 详情卡与预设列表**共用一份高度预算**
        //（展开清单 ⇒ 详情多给 40dp、列表让出 40dp，两者之和恒定 ⇒ 列表不会被挤出屏幕）
        Column(Modifier.fillMaxWidth()) {
            // ① ★ PS-6a/PS-7：详情卡（图 2）—— 名称 / 一行副标题 / 一行描述 / **常用参数** /
            //    「当前搭配」（限高可展开）/ 选中项滑杆 / 提示行 —— 卡片本身不滚
            CameraPresetDetailCard(
                p = selected,
                params = params,
                shown = shown,
                lutStrength = lutStrengthNow,
                hasLut = hasLut,
                lutLabel = selected.lutId?.let { id -> LutLibrary.byId(context, id)?.label },
                hasChanges = mine != null && !mine.isEmpty,
                overviewExpanded = overviewExpanded,
                onOverviewExpanded = { overviewExpanded = it },
                onEditParam = { param, v -> pushEdit(param, v) },
                onEditFinished = { finishEdit() },
                onPickLevel = { param, v -> pickLevel(param, v) },
                onOpenFullEdit = onOpenFullEdit,
                onSaveSlots = {
                    // ★ 保存口径：`[34]` 不进 slots ⇒ 显式带回 `lutStrength`（见 saveSlotsWithLutStrength）
                    onSaveSlots(saveSlotsWithLutStrength(selected, mine), mine)
                    // 改动已落盘 ⇒ 清掉（按钮随之消失；**画面不变**：下发数组本来就是这些值）
                    onOverrides(null)
                },
            )
            // ② 常驻的「+ 新建预设」+ 入口提示（"哪里能点"写在界面上，不靠用户猜）
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
                    // 点行 = 套用（详情卡随之切换）；长按 = 编辑面板（改名 / 分组 / 删除）
                    "点行套用 · 长按改名或删除 · 「⋯」菜单",
                    color = FilmTheme.CreamDim,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            // ③ 列表：首项恒为内置「标准」（P-2a 的列表契约），其余 = 用户预设 + 内置三条。
            //    ★ PS-7：限高来自**同屏预算**（[presetPanelBudget]）—— 详情卡展开清单 ⇒ 列表让出
            //    40dp；两者之和恒定 ⇒ 常用参数与列表谁也不被挤没（列表自身可滚）。
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = presetPanelBudget(overviewExpanded).listDp.dp)
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
                        // ★ PS-3：长按 = 打开编辑面板（点行仍是套用，P-2a 行为一位不动）
                        onDetail = { openEdit(p) },
                        onMenu = { menuFor = p },
                    )
                }
            }
            // ④ P-5 字段作用域：当前这条预设里有几项只在编辑器链路生效 ⇒ 套用前先说清楚
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
 * ★ PS-6a：**预设详情卡**（用户 2026-10-12："点击预设按图 2 那样展示"）—— 主视图的上半部分。
 *
 * ```
 * 徕卡一瞬  [内置]                              ← ① 名称（大字）
 * 内置预设 · 分组「徕卡」                        ← ② 副标题（来源 · 分组）
 * 包含 17 项参数（基础调整 / 色彩 / …）  展开    ← ③ 描述段落（可展开收起，全部是真实数据）
 * 当前搭配                          完整参数 >  ← ④ 标题 + 完整参数页入口（PS-6b 的页面）
 * 滤镜  徕卡 M9 · 30      对比度  -3            ← ⑤ 两列只读清单（[presetOverviewRows]）
 * 颗粒  中颗粒 · 数量 30  …                     ←    常用项折叠成"档位名 · 数值"
 * 选择一项开始调整                 实时效果 开   ← ⑥ 提示 + 实时标记（无手势，不是假开关）
 * ▬▬▬▬▬▬  ← ⑦ 点清单里"有滑杆的项" ⇒ 出这一条的滑杆（**一次一项**）
 * 常用参数                                      ← ⑧ 常用参数区（用户点名的 5 项）
 * 滤镜强度 ▬▬▬▬  30
 * 颗粒  [关闭][细颗粒][中颗粒][粗颗粒]
 * 柔光  [关闭][轻柔光][中等柔光][强烈柔光]
 * 光晕  [关闭][轻微光晕][中等光晕][强烈光晕]
 * 暗角  [关闭][轻微暗角][中等暗角][强烈暗角]
 * 未保存的调整                    [保存到预设]  ← ⑨ 有改动才出现（调完即存）
 * ```
 *
 * **点点看**（判据 [presetQuickPath]，每个能点的都必须有反应）：常用 5 项 ⇒ 提示去下面哪个档位调；
 * 有滑杆的项 ⇒ ⑦ 出那一条滑杆；两样都没有的项 ⇒ 行下写明"为什么点不动"（ADR-12，不做假控件）。
 */
@Composable
private fun CameraPresetDetailCard(
    /** 当前生效的那条预设（详情卡永远对应它，见 [CameraPresetTab] 的不变量 ④） */
    p: EditorPreset,
    /** 滑杆读写数组（= `cameraPresetParams(target, values, removed)`） */
    params: DoubleArray,
    /** "这条预设包含什么"（= 预设记录 − 移除 + 用户刚设的值） */
    shown: Map<Int, Double>,
    /** `[34]` 的当前值（未保存改动 → 预设自己的 → 新建态的当前相机值） */
    lutStrength: Float,
    hasLut: Boolean,
    lutLabel: String?,
    /** 有没有未保存的改动（决定「保存到预设」出不出现） */
    hasChanges: Boolean,
    /** ★ PS-7：清单是否展开（3 行 ↔ 8 行）—— 状态**提升到父层**：列表要按它让位（同屏预算） */
    overviewExpanded: Boolean,
    onOverviewExpanded: (Boolean) -> Unit,
    onEditParam: (EditorParam, Float) -> Unit,
    onEditFinished: () -> Unit,
    onPickLevel: (EditorParam, Float) -> Unit,
    onOpenFullEdit: () -> Unit,
    onSaveSlots: () -> Unit,
) {
    // 选中的那一项（换预设自动清空）：**一次只出一条滑杆** —— 用户否决的是"全是滑杆"
    var picked by remember(p.id) { mutableStateOf<Int?>(null) }
    val rows = presetOverviewRows(shown, presetLutLine(p), lutLabel)
    val half = (rows.size + 1) / 2
    val pickedRow = rows.firstOrNull { it.slot == picked }
    val pickedTap = pickedRow?.let { presetRowTap(it.slot) }
    val budget = presetPanelBudget(overviewExpanded)
    // ★ PS-7：卡片**不再整卡滚动**（用户："这个界面的参数都动不了" —— 点一行后滑杆插在滚动区
    //   屏幕外 = 感知为"点了没反应"）。固定区 = 名称 / 副标题 / 一行描述 / **常用参数** /
    //   「当前搭配」标题 / **选中项滑杆** / 提示行 / 保存；只有清单区限高可滚。
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = budget.detailDp.dp)
            .padding(top = 8.dp),
    ) {
        // ① 名称（大字）+ 来源标记（内置预设的唯一限制：不能删）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                p.name,
                color = FilmTheme.Cream,
                fontSize = 18.sp,
                fontFamily = FilmTheme.Serif,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
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
            }
        }
        // ② 副标题：**一行**「来源 · 分组」（描述段不再重复这句 —— 见 presetOverviewText）
        Text(
            "${presetSourceText(p.source)}预设" + if (p.group.isNotEmpty()) " · 分组「${p.group}」" else "",
            color = FilmTheme.CreamDim,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // ③ 描述：一行真实信息（项数 · 滤镜）；没有真实信息 ⇒ 整行不显示（旧的机器拼接段已删）
        presetOverviewText(p, lutLabel)?.let { line ->
            Text(
                line,
                color = FilmTheme.CreamDim,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // ④ ★ 常用参数（用户点名的 5 项）—— **首屏固定区**：在清单之前，且卡片不滚 ⇒ 不用滚
        //    就能调。清单里选中常用项时，对应档位行随之高亮（[PresetRowTap.LEVEL]）。
        PresetQuickAdjustPanel(
            presetId = p.id,
            params = params,
            lutStrength = lutStrength,
            hasLut = hasLut,
            highlightSlot = pickedRow?.takeIf { pickedTap == PresetRowTap.LEVEL }?.slot,
            onEditParam = onEditParam,
            onEditFinished = onEditFinished,
            onPickLevel = onPickLevel,
        )
        // ⑤ 「当前搭配」+「展开 / 收起」+「完整参数 >」（PS-6b 的入口；本卡只调回调）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "当前搭配",
                color = FilmTheme.Accent,
                fontSize = 11.sp,
                maxLines = 1,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (rows.size > PRESET_OVERVIEW_ROWS_COLLAPSED) {
                Text(
                    if (overviewExpanded) "收起" else "展开 ${rows.size} 项",
                    color = FilmTheme.Accent,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .pressScale(pressedScale = 0.94f, onTap = { onOverviewExpanded(!overviewExpanded) })
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                "完整参数 >",
                color = FilmTheme.Accent,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, FilmTheme.Accent, RoundedCornerShape(8.dp))
                    .pressScale(pressedScale = 0.94f, onTap = onOpenFullEdit)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        // ⑥ ★ 选中项的滑杆：**紧贴清单上方的固定槽**（在滚动区之外）—— 点一行 ⇒ 这里立刻出现，
        //    不可能被限高/滚动推到屏幕外（用户这轮骂的根子就是"点了看不到变化"）
        if (pickedRow != null && pickedTap == PresetRowTap.SLIDER) {
            val param = CAMERA_TUNABLE_SLOT_PARAM.getValue(pickedRow.slot)
            Text(
                "选中项：${pickedRow.label} · ${presetRowValueText(pickedRow.value)}",
                color = FilmTheme.Accent,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            key(param.key, p.id) {
                EditorSlider(
                    param = param,
                    params = params,
                    onCommit = onEditParam,
                    onFinished = onEditFinished,
                )
            }
        }
        // ⑦ 两列只读清单（空预设 ⇒ 一句实话，不是空白区）：**限高**（3 行 ↔ 8 行）可滚，
        //    点任一行都有反应（[presetRowTap] 三态：档位高亮 / 滑杆 / 行下小字说明原因）
        if (rows.isEmpty()) {
            Text(
                "不含参数（套用时其余参数保持不动）",
                color = FilmTheme.CreamDim,
                fontSize = 11.sp,
                maxLines = 2,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = presetOverviewMaxHeightDp(rows.size, overviewExpanded).dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 2.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    rows.take(half).forEach { r ->
                        PresetOverviewRowText(r, picked = picked == r.slot) {
                            picked = if (picked == r.slot) null else r.slot
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    rows.drop(half).forEach { r ->
                        PresetOverviewRowText(r, picked = picked == r.slot) {
                            picked = if (picked == r.slot) null else r.slot
                        }
                    }
                }
            }
        }
        // ⑧ 提示行（**实话**：选中了什么、去哪儿调）+「实时效果 开」（纯标记，无手势 ⇒ 不是假开关）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (pickedRow) {
                    null -> "选择一项开始调整"
                    else -> when (presetRowTap(pickedRow.slot)) {
                        PresetRowTap.LEVEL ->
                            "已选「${pickedRow.label}」· 上面「${quickAdjustLabelOf(pickedRow.slot) ?: pickedRow.label}」档位已高亮"
                        PresetRowTap.SLIDER -> "已选「${pickedRow.label}」· 滑杆在上面「选中项」处"
                        PresetRowTap.NOTE ->
                            "「${pickedRow.label}」没有快捷滑杆（${cameraParamAdjustabilityNote(pickedRow.slot) ?: "拍照链路不读"}）· 用「完整参数 >」逐项调"
                    }
                },
                color = FilmTheme.CreamDim,
                fontSize = 10.sp,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            Text(
                "实时效果 开",
                color = FilmTheme.Accent,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        // ⑨ 有未保存改动 ⇒ 「保存到预设」（调完即存；落盘后上层清掉改动，这一行随之消失）
        if (hasChanges) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "未保存的调整",
                    color = FilmTheme.CreamDim,
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                CamPresetActionButton("保存到预设", enabled = true, primary = true, onTap = onSaveSlots)
            }
        }
    }
}

/**
 * 清单里的一行（图 2 的只读概览）：`参数名 …… 值`。
 *
 * **点不动的项写明原因**（[cameraParamAdjustabilityNote]：拍照链路不读 / 参数面板还没有滑杆）——
 * ★ PS-7：那句实话只在**行下小字**，值位永远留给真实值（[presetRowValueText]，没有值就「—」）——
 * 用户截图里的 `分离色调 · 暖 → 拍照生效 · 暂无需滑杆` 就是说明混进值位的毛病。
 */
@Composable
private fun PresetOverviewRowText(
    row: PresetOverviewRow,
    picked: Boolean,
    onTap: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (picked) FilmTheme.ChipBg else Color.Transparent)
            .pressScale(pressedScale = 0.98f, onTap = onTap)
            .padding(horizontal = 3.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.label,
                color = if (picked) FilmTheme.Accent else FilmTheme.Cream,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                // ★ PS-7：值位只放真实值（没有 ⇒ 「—」），不塞"为什么点不动"的说明
                presetRowValueText(row.value),
                color = FilmTheme.CreamDim,
                fontSize = 11.sp,
                fontFamily = FilmTheme.Mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (presetRowTap(row.slot) == PresetRowTap.NOTE) {
            cameraParamAdjustabilityNote(row.slot)?.let { note ->
                Text(note, color = CamDisabledInk, fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}

/**
 * ★ PS-6a：**常用参数区**（用户点名的 5 项）—— 详情卡与编辑面板**共用同一个组件**
 * （一处实现、两处调用；不是两份控件）。
 *
 * 写值通路只有一条：滑杆经 [onEditParam]（`EditorSlider` → `EditorParam.write`），档位经
 * [onPickLevel]（同一个 `write` + 立即提交）。本组件**不碰** [CameraParamOverrides]。
 *
 * `[34]` LUT 强度是唯一例外：它的界面当前值不在 `slots` 里（由 [EditorPreset.lutStrength]
 * 承载）⇒ 借一份视图数组给 `EditorSlider` 读初值。
 */
@Composable
private fun PresetQuickAdjustPanel(
    /** 归属预设 id（换预设 ⇒ 滑杆重建，不沿用上一条的读数） */
    presetId: String,
    /** 滑杆读写数组（= `cameraPresetParams(target, values, removed)`） */
    params: DoubleArray,
    lutStrength: Float,
    hasLut: Boolean,
    /**
     * ★ PS-7：清单里选中的**常用项**的槽 ⇒ 对应档位行 / 强度滑杆**高亮**。
     * 这是"点了一行 ⇒ 看得见反应"的落点之一（[PresetRowTap.LEVEL]）；null = 没有选中常用项。
     */
    highlightSlot: Int? = null,
    onEditParam: (EditorParam, Float) -> Unit,
    onEditFinished: () -> Unit,
    onPickLevel: (EditorParam, Float) -> Unit,
) {
    Text(
        "常用参数",
        color = FilmTheme.Accent,
        fontSize = 11.sp,
        maxLines = 1,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp),
    )
    if (hasLut) {
        val view = params.copyOf().also { it[LUT_STRENGTH_SLOT] = lutStrength.toDouble() }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (highlightSlot == LUT_STRENGTH_SLOT) FilmTheme.ChipBg else Color.Transparent),
        ) {
            key(presetId, LutStrengthParam.key) {
                EditorSlider(
                    param = LutStrengthParam,
                    params = view,
                    onCommit = onEditParam,
                    onFinished = onEditFinished,
                )
            }
        }
    } else {
        // 没有 LUT ⇒ **不给假滑杆**（拖了画面不会变）：写明原因（套用时保持当前 LUT）
        Text(
            "滤镜强度：这条预设不含 LUT（套用时保持当前 LUT）",
            color = CamDisabledInk,
            fontSize = 10.sp,
            maxLines = 2,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    // 颗粒（★ 用户点名："颗粒开、关、档位"）—— 档位 = `[26]` 强度 + `[27]` 尺寸的组合
    QuickLevelChips(
        label = "颗粒",
        names = GrainLevel.entries.map { it.label },
        current = grainLevelOf(params).ordinal,
        highlight = highlightSlot == GRAIN_AMOUNT_SLOT,
        onPick = { i -> onPickLevel(GrainLevelParam, i.toFloat()) },
    )
    // 柔光 / 光晕 / 暗角（写值仍走这三条既有滑杆的 `write`；档位值 = [QuickLevelParam.levels]）
    for (q in QUICK_LEVEL_PARAMS) {
        val param = CAMERA_TUNABLE_SLOT_PARAM.getValue(q.slot)
        QuickLevelChips(
            label = q.label,
            names = q.names,
            current = nearestLevel(param.read(params), q.levels),
            highlight = highlightSlot == q.slot,
            onPick = { i -> onPickLevel(param, q.levels[i]) },
        )
    }
}

/**
 * 一行档位按钮（图 2 的 `[关闭][细颗粒][中颗粒][粗颗粒]`）：当前档位高亮。
 *
 * 档位 = **一次性动作**（点一下写值并立即提交），不是拖动 ⇒ 走 [onPick]（不是 `EditorSlider`）。
 * 窄屏放不下时横向可滚（中文档名比英文长）。
 *
 * ★ PS-7：[highlight] = 清单里选中了这一项 ⇒ **整行点亮**（"点了一行 ⇒ 看得见反应"的落点）。
 */
@Composable
private fun QuickLevelChips(
    label: String,
    names: List<String>,
    current: Int,
    onPick: (Int) -> Unit,
    highlight: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlight) FilmTheme.ChipBg else Color.Transparent)
            .padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = FilmTheme.Cream,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.width(48.dp),
        )
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            names.forEachIndexed { i, n ->
                val on = i == current
                Text(
                    n,
                    color = if (on) FilmTheme.OnAccent else FilmTheme.CreamDim,
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) FilmTheme.Accent else Color.Transparent)
                        .border(
                            1.dp,
                            if (on) FilmTheme.Accent else FilmTheme.ChipBorder,
                            RoundedCornerShape(8.dp),
                        )
                        .pressScale(pressedScale = 0.94f, onTap = { onPick(i) })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * 预设**编辑面板**（新建态 / 编辑态同一个面板）= **名称 / 分组 / 保存 / 删除**的落点。
 *
 * ★ PS-6a：参数区改成**常用参数**（与详情卡**共用** [PresetQuickAdjustPanel]，一处实现两处调用）
 * —— 新建态也能调参数（用户 2026-10-11 投诉过"新建预设时参数不可配置"），逐项参数（35 条）走
 * 「完整参数 >」（PS-6b）。底部动作：
 *  - **新建态**（`p == null`）：「保存当前相机参数为新预设」（[newCameraPresetFrom]：当前预设 +
 *    当前改动，含 `[0]` 曝光偏移）+ 「取消」；
 *  - **编辑态**：「**保存到预设**」（把改动写进这条预设，含曝光偏移与 `[34]` 强度；无改动 ⇒ 置灰）、
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
    /** 滑杆读写数组（= `cameraPresetParams(target, values, removed)`） */
    params: DoubleArray,
    /** `[34]` 的当前值（未保存改动 → 预设自己的 → 新建态的当前相机值） */
    lutStrength: Float,
    /** 有没有 LUT（新建态 = 当前相机选的；编辑态 = 这条预设的）⇒ 决定滤镜强度滑杆出不出现 */
    hasLut: Boolean,
    /** 有没有未保存的改动（「保存到预设」可点 / 「恢复默认」叠加判据） */
    hasChanges: Boolean,
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
    /** ★ PS-6a：档位按钮（点一下写值 + **立即提交**，不是拖动通路） */
    onPickLevel: (EditorParam, Float) -> Unit,
    /** ★ PS-6a：「完整参数 >」—— 打开完整参数页（PS-6b 的实现） */
    onOpenFullEdit: () -> Unit,
    onBack: () -> Unit,
    onSaveNew: () -> Unit,
    onUpdate: () -> Unit,
    onSaveSlots: () -> Unit,
    onRestore: () -> Unit,
    onSaveMeta: () -> Unit,
    onDelete: () -> Unit,
) {
    val isNew = p == null
    Column(
        Modifier
            .fillMaxWidth()
            // 限高 + 可滚：编辑面板与 LUT tab 共用一个高度预算（窄屏 / 大字号下不该被裁掉）
            .heightIn(max = 430.dp)
            .verticalScroll(rememberScrollState())
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
        // ③ 常用参数（★ PS-6a，与详情卡共用同一个组件）+ 逐项参数的入口（PS-6b 的完整参数页）
        Text(
            "拖动即时生效、未保存不写入预设 · 逐项参数（35 条）在「完整参数」里调",
            color = FilmTheme.CreamDim,
            fontSize = 10.sp,
            maxLines = 2,
            modifier = Modifier.padding(top = 5.dp),
        )
        PresetQuickAdjustPanel(
            presetId = target.id,
            params = params,
            lutStrength = lutStrength,
            hasLut = hasLut,
            onEditParam = onEditParam,
            onEditFinished = onEditFinished,
            onPickLevel = onPickLevel,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            CamPresetActionButton("完整参数 >", enabled = true, onTap = onOpenFullEdit)
        }
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
                "「保存到预设」写入本次改动（含曝光偏移与 LUT 强度）·「从当前相机参数更新」整包重写（不含曝光偏移）",
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
