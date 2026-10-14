package com.filmkit.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmkit.app.camera.CAMERA_PATH_SLOTS
import com.filmkit.app.camera.CAMERA_STANDARD_PRESET
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.PARAM_EPS
import com.filmkit.app.camera.PRESET_SOURCE_USER
import com.filmkit.app.camera.cameraPresetParams
import com.filmkit.app.camera.formatZero100
import com.filmkit.app.camera.pivotFraction
import com.filmkit.app.camera.presetDeletable
import com.filmkit.app.camera.presetId
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
 * ## ★ PS-8（用户 2026-10-12/13 真机，v0.11.23）：**横向滚动 + 一级页精简 + 返回键 + 实时预览**
 *
 * 用户原话："**UI做的太丑了重新设计一下，预设改成横向滚动**。**第一级页面不需要提供全部参数，
 * 点击查看完整参数才提供**。**一级页面系统返回按钮关闭预设页面，不要直接退出app**。
 * **一级参数调节要能在预览看到实时效果**"
 *
 * ⇒ 一级页（非编辑态）只剩**三个视觉块 + 一行保存**：
 *  ① **横向预设条**（[PresetStrip]，高 [PRESET_STRIP_HEIGHT_DP]）：首项「＋ 新建预设」+ 卡片；
 *  ② **名称行**：18sp 衬线半粗 + 「内置」标记 + 右侧「**完整参数 >**」（[onOpenFullEdit]）；
 *  ③ **一行副标题**（[presetSubtitleText]：来源 / 分组 / 滤镜；没有真实信息 ⇒ 整行不渲染）；
 *  ④ **常用参数**（[PresetQuickAdjustPanel]）：LUT 强度一行滑杆 + 颗粒/柔光/光晕/暗角四行**分段控件**；
 *  ⑤ 有未保存改动时出「保存到预设」（[saveSlotsWithLutStrength] 把 `[34]` 带回 `lutStrength`）。
 *
 * **PS-7 的「当前搭配」清单连同它的纯逻辑一并删除**（`presetOverviewRows` / `presetRowTap` /
 * `presetOverviewMaxHeightDp` / `presetPanelBudget` …）：用户明确"第一级页面不需要提供全部参数"
 * ⇒ 逐项参数一律走「完整参数 >」（PS-6b 那一页**一个字没动**），一级页不留解释性小字与展开控件。
 *
 * ## ★ PS-9（用户 2026-10-12 真机截图 + 三条反馈）：**间距 / 暗角档位 / 颗粒滑杆**
 *
 * 用户原话："**截图位置挤到一起了，优化布局**。**暗角轻微和中等跟没有一样，强烈顶多算轻微**。
 * **颗粒改成滑杆吧**"
 *
 *  - **布局**：建立**统一间距阶**（[PRESET_GAP_SM_DP] 8 / [PRESET_BLOCK_GAP_DP] 16 /
 *    [PRESET_GAP_LG_DP] 24，理由见常量上的 KDoc），一级页顶部留 24dp（它上方就是 `LutSheet`
 *    的 tab 行）；卡片内边距 ≥10dp；**只调间距与去重，没有加任何装饰**；
 *  - **标题去重**：`LutSheet` 顶行在预设 tab 下不再复述预设名（改为「预设」），
 *    保留一级页那一行 18sp 衬线半粗的名称（见 `CameraScreen.kt` 的同段注释）；
 *  - **暗角档位**：[VIGNETTE_LEVEL_PARAM] 从 0.20 / 0.40 / 0.70 改成 **0.35 / 0.65 / 1.0**
 *    （引擎满量程；依据写在常量上方的注释里）；
 *  - **颗粒**：四档分段控件 → **强度滑杆**（[GrainStrengthParam]，只写 `[26]`）；`[27]` 尺寸
 *    在一级页不再有控件、**保持该预设原值**（二级页仍可看它，那一页一个字没动）。
 *
 * 三条纪律（用户"每个能点的都必须有反应"，一位未改）：
 *  1. **写值只有一条路**：滑杆/档位一律经 `EditorParam.write`（[afterEdit] 记账 ⇒ 拖动实时下发
 *     `onPreview`、松手 [onOverrides] 一次提交，不新增 state 通路）；
 *     ★ PS-9：`[26]` 颗粒在 `ParamControls.kt` 里**没有既有滑杆** ⇒ 用本文件私有的
 *     [GrainStrengthParam]（只写 `[26]`，量程 / 读数口径沿用 `PresetDetail.kt` 对 `[26]` 的既有口径）；
 *  2. **LUT 强度必须两路一起写**：`[34]` 会被 `CameraController.applyLutStrengthPref` 用 prefs
 *     覆盖 ⇒ 拖动时同时写 prefs 的 `lut_strength`（引擎侧既有事实源），否则"拖了画面不变"；
 *  3. **返回键分层**：编辑面板（一级页子态）⇒ 面板内 `BackHandler`；完整参数页 / 一级面板 ⇒
 *     `CameraScreen` 的 `cameraBackTarget`（二级页优先于一级面板，见 `CameraScreen.kt`）。

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
// 暗角调节**"。本节只做一件事：**档位映射**（档位 → UI 值 / 当前值 → 档位）—— 值写进数组仍走
// `EditorParam.write`（`afterEdit` 记账 ⇒ 写值口径只有一份，见 [CameraParamOverrides.afterEdit]）。
// ★ PS-8：原先挂在同一节的「当前搭配」清单判据已随清单删除（一级页不提供全部参数）。
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
 * @param label 参数名（"柔光"）
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
//  ③ **暗角档位改用"引擎满量程"口径**（★ PS-9，用户 2026-10-12 真机实测："**暗角轻微和中等跟
//     没有一样，强烈顶多算轻微**"）：旧档位 0.20 / 0.40 / 0.70 是按"内置的最近整值 + 0.2 步进"
//     推出来的，**不是按引擎量程**——而引擎侧 `[66] ∈ [−1, 0]`（`vigStrengthSlot` 的 clamp、
//     `FrameRenderer.cpp` 的 `clamp(p.vignette_strength, -1.0f, 0.0f)`），旧"强档"只用到量程的
//     70%。更关键的是**暗角只在画面最外缘可见**：三条内置的 `vignette_radius` = 85 / 75 / 80
//     （`builtin.json`）⇒ 衰减从 75%~85% 半径才开始，画面主体（vw ≈ 0）几乎看不出差别
//     （`vw = clamp((cplen − radius/100) / feather, 0, 1)`，内置 feather = 48~55）。
//     ⇒ 新档位按**引擎满量程四档**给：轻 0.35 / 中 0.65 / 强 **1.0（满量程）**。
//     强档 `[66] = −1` 的语义是"角落权重 1 处增益归零"（`c *= 1 + 1 × (−1) = 0`）—— 量程内
//     确实还有更暗的数学空间，但**引擎不接受**（CPU 侧 clamp 到 −1），所以 1.0 就是"最强"。
internal val GLOW_LEVEL_PARAM =
    QuickLevelParam("柔光", 95, listOf(0f, 0.25f, 0.5f, 0.75f), levelNames("轻柔光", "柔光"))
internal val HALO_LEVEL_PARAM =
    QuickLevelParam("光晕", 62, listOf(0f, 0.25f, 0.5f, 0.75f), levelNames("轻微光晕", "光晕"))
internal val VIGNETTE_LEVEL_PARAM =
    QuickLevelParam("暗角", 66, listOf(0f, 0.35f, 0.65f, 1.0f), levelNames("轻微暗角", "暗角"))

/** 图 2 的三个档位行（顺序 = 组内既有顺序：柔光/光晕同族、暗角在其后，与 `TextureParams` 一致）。 */
internal val QUICK_LEVEL_PARAMS =
    listOf(GLOW_LEVEL_PARAM, HALO_LEVEL_PARAM, VIGNETTE_LEVEL_PARAM)

/**
 * ★ PS-9：**颗粒强度滑杆**（用户 2026-10-12 真机："**颗粒改成滑杆吧**"）。
 *
 * ## 为什么只写 `[26]`、`[27]` 在一级页不再出现
 *
 * 用户原话只要"强度"这一条滑杆；`[27]`（尺寸 / ISO 语义）在一级页**不再提供控件**，
 * 但**保持该预设原值**（滑杆的 `write` 不碰它）⇒ 保存时 `[27]` 不会因为"用户动了颗粒"
 * 而被改掉（`afterEdit` 逐槽比对：只记真正变了的 `[26]`）。二级「完整参数」页仍可看它
 * （`CameraPresetFullEdit.kt` 那一页**一个字没动**）。
 *
 * ## 量程与读数口径：**沿用 `PresetDetail.kt` 对 `[26]` 的既有口径**（不另造一套）
 *
 * `ParamControls.kt` 里**没有**颗粒滑杆（`[26][27]` 在 [CAMERA_NO_SLIDER_SLOTS] 里，是
 * 既有欠账）⇒ 本参数在拍照界面自建，但**数值口径不是新的**：
 *  - 槽位量程 0..100（`FilmParams.h` 的 `grain_amount`；`PresetDetail.kt:178-180` 的注释
 *    与格式化 `formatZero100((it / 100.0).toFloat())` 就是这一条）；
 *  - 所以 UI 量程 = **0..1**、读数 = `formatZero100`（0..100 整数），与 `[26]` 在二级页
 *    / 「完整参数」页显示的是**同一个数**（同一参数两处口径不一致 = 假读数）；
 *  - `pivot = 0` = 引擎中性值（`[26] = 0` ⇒ `FrameRenderer` 的 `uGrainAmount > 0.0`
 *    整段跳过，零成本旁路）⇒ 与 LUT 强度 / 暗角强度一样是"基准在最左端 ⇒ 不画中点标记"。
 *
 * ⚠ **`[27]` 不在本参数里**：旧 [GrainLevel] 的"一条档位同写两槽"随档位控件一并删除
 * （滑杆只有一个自由度，写两个槽会让"用户只想加强颗粒"顺手改掉尺寸）。
 */
internal const val GRAIN_AMOUNT_SLOT = 26

/** 颗粒尺寸 `[27]`（**ISO 语义**，越大越粗）—— ★ PS-9：一级页不再有它的控件（值保持原样）。 */
internal const val GRAIN_SIZE_SLOT = 27

/**
 * ★ PS-10：filmic **白点** `[38]`（"多亮算白"）—— 一级页给它的**唯一一条**滑杆。
 *
 * 用户 2026-10-12 真机："**黑白点要可调**，你白点默认才 2.7？那杯子高光变白的 bug 不就又出来了"
 * ⇒ 白点是"高光死白"的直接病灶（内置三条 2.54~2.75，比引擎默认 4.56 近约 1.8 EV），
 * 必须在一级页就能自救。
 *
 * **黑点 `[37]` 不上这一级页**（判断，依据三条）：
 *  ① 用户报的是高光（白点），暗部没有报过问题；
 *  ② 一级页的既有模式 = **每类只给主控一条**（柔光只给强度、暗角只给强度、颗粒只给强度，
 *     半径 / 阈值 / 尺寸全在「完整参数 >」）—— 黑白点里白点是高光侧的主控；
 *  ③ 一级页高度是用户明确要求守住的（PS-8 / PS-9 两张卡反复量过：~354dp）⇒ 一行 30dp + 8dp
 *     间距是能接受的上限，两行就要再吃 38dp 取景面积。
 * 黑点仍在「完整参数 >」的「调整」组里（与白点相邻、量程 / 读数同一份参数表）⇒ "黑白点可调"
 * 整体成立。若真机上用户仍要黑点也上一级页：加一行 `PresetParamSliderRow` 即可（同一组件）。
 */
internal const val FILMIC_WHITE_EV_SLOT = 38

/** 颗粒强度 UI 量程（0..1 = 显示 0..100，直通槽位 `[26]` 的 0..100 语义）。 */
internal val GRAIN_UI_RANGE = 0f..1f

/** UI 颗粒强度（0..1）→ 槽位 `[26]`（0..100）。越界夹到端点（与 `[26]` 的引擎量程一致）。 */
internal fun grainAmountSlot(ui: Float): Double =
    ui.coerceIn(GRAIN_UI_RANGE.start, GRAIN_UI_RANGE.endInclusive).toDouble() * 100.0

/**
 * 槽位 `[26]`（0..100）→ UI 颗粒强度（0..1）。**坏数据回落 0 = 关**（与 `[26] = 0` 整段旁路
 * 同义：读不出来时不该凭空给画面加颗粒）；越界夹到端点（UI 写不出越界值）。
 */
internal fun grainAmountUi(slot: Double): Float =
    if (!slot.isFinite()) 0f else (slot / 100.0).coerceIn(0.0, 1.0).toFloat()

/**
 * 颗粒强度的**写入器**：复用 [EditorParam] 的 `write` 通道（[CameraParamOverrides.afterEdit]
 * 的记账因此与其它参数走在同一条路上 —— 拖动 ⇒ 实时下发 + 松手记账 ⇒ 保存回预设）。
 */
internal val GrainStrengthParam = EditorParam(
    key = "grainAmount", label = "颗粒",
    range = GRAIN_UI_RANGE, pivot = 0f,
    format = { formatZero100(it) },
    read = { p -> if (GRAIN_AMOUNT_SLOT in p.indices) grainAmountUi(p[GRAIN_AMOUNT_SLOT]) else 0f },
    write = { p, v -> p[GRAIN_AMOUNT_SLOT] = grainAmountSlot(v) },
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
 * 常用参数覆盖的槽（图 2 的 5 项 + ★ PS-10 的 filmic 白点）：`[34]` LUT 强度 / `[26]` 颗粒 /
 * `[95]` 柔光 / `[62]` 光晕 / `[66]` 暗角 / `[38]` 白点。
 *
 * ★ PS-9：`[27]` 颗粒**尺寸**从集合里去掉 —— 用户 2026-10-12："**颗粒改成滑杆吧**"，
 * 一级页的颗粒只剩一条**只写 `[26]`** 的强度滑杆（`[27]` 保持预设原值，二级页仍可看）。
 * 集合的口径 = "一级页真的能调的槽"，因此不含它。
 *
 * ★ PS-10：加 `[38]`（白点）而**不加 `[37]`**（黑点）—— 取舍依据见 [FILMIC_WHITE_EV_SLOT] 的 KDoc。
 * 集合口径不变（"一级页真的能调的槽"），只是这一级页多了一条滑杆。
 */
internal val QUICK_ADJUST_SLOTS: Set<Int> =
    linkedSetOf(LUT_STRENGTH_SLOT, GRAIN_AMOUNT_SLOT, 95, 62, 66, FILMIC_WHITE_EV_SLOT)

/**
 * ★ PS-8：**横向预设条**的一项 —— 预设 + 小标记（`内置` / `已改` / 分组名）+ 选中态。
 *
 * 用户 2026-10-12（v0.11.23 真机）："**预设改成横向滚动**"（参考 Filmix 的底部横向卡片条）。
 * 条的顺序 = **列表契约不变**（首项恒为内置「标准」，其余 = 用户预设 + 内置三条）。
 */
internal data class PresetStripItem(
    val preset: EditorPreset,
    /** 名称下面一行小标记（10sp）：`内置` / `已改` / 分组名；null ⇒ 那一行只留 `⋯` */
    val badge: String?,
    val selected: Boolean,
)

/**
 * 横向条的内容与顺序（**纯函数**：UI 与单测读同一份判据，不在 Composable 里各拼一套）。
 *
 * @param presets `presets`（用户预设 + 内置三条；**不含**「标准」，由本函数补在首项 —— P-2a 契约）
 * @param selectedId 当前生效的预设 id（选中态 = 强调色描边 + 底色）
 * @param builtinOverrides 内置覆盖表（判「已改」）
 */
internal fun presetStripItems(
    presets: List<EditorPreset>,
    selectedId: String,
    builtinOverrides: Map<String, EditorPreset>,
): List<PresetStripItem> =
    (listOf(CAMERA_STANDARD_PRESET) + presets).map { p ->
        PresetStripItem(
            preset = p,
            badge = presetStripBadge(p, edited = builtinOverrides.containsKey(p.id)),
            selected = p.id == selectedId,
        )
    }

/**
 * 一项的小标记：`内置`（判据仍是 [presetDeletable] 这一个实现 ⇒ 不另写 source 比较）/ `已改`
 * （内置被覆盖过）/ 分组名。都有 ⇒ ` · ` 连成一行（用户要的"可选的小标记"，不是三行）。
 */
internal fun presetStripBadge(p: EditorPreset, edited: Boolean): String? =
    listOfNotNull(
        // 判据仍是 presetDeletable 这一个实现（不另写 source 比较）
        if (!presetDeletable(p)) "内置" else null,
        // 「已改」= **出厂值**被改过 ⇒ 只对内置成立（用户预设没有出厂值，它本来就是自己改的）
        if (edited && !presetDeletable(p)) "已改" else null,
        p.group.ifBlank { null },
    ).joinToString(" · ").ifEmpty { null }

/**
 * ★ PS-8：一级页的**一行副标题**（可选 —— 没有真实信息 ⇒ null ⇒ 整行不渲染）。
 *
 * 用户："第一级页面不需要提供全部参数" ⇒ 一行说清"这条预设是什么"（分组 / 滤镜）就够；
 * **来源**（内置 / 用户）由名称行的「内置」标记承担；**参数项数**（"17 项参数"）是量的展示
 * ⇒ 归「完整参数 >」那一页。
 */
internal fun presetSubtitleText(p: EditorPreset, lutLabel: String? = null): String? =
    listOfNotNull(
        // 来源（内置 / 用户）不进副标题：名称行的「内置」标记已经说了这一条（PS-7 的"不重复"纪律）
        p.group.ifBlank { null }?.let { "分组「$it」" },
        p.lutId?.let { "滤镜 ${lutLabel ?: it}" },
    ).joinToString(" · ").ifEmpty { null }

// ─────────────────────────────────────────────────────────────────────────────
// ★ PS-8：一级页的**尺寸口径**（集中在这里 ⇒ 单测直接断言"横向条高度 ≤ 72dp"）
// ★ PS-9：**统一间距阶**（用户 2026-10-12 真机截图："**截图位置挤到一起了，优化布局**"）
// ─────────────────────────────────────────────────────────────────────────────

/** 横向预设条的高度（用户："高度要克制（≤ 72dp 量级）"—— 省下的纵向空间留给取景与常用参数）。 */
internal const val PRESET_STRIP_HEIGHT_DP = 64f

/** 横向卡片的宽度（等宽 ⇒ 条上每一项对齐；长名字省略号截断）。 */
internal const val PRESET_STRIP_CARD_WIDTH_DP = 124f

/**
 * ★ PS-9：**统一间距阶**——三档，别处一律引用常量，不许再出现散落的魔数 dp。
 *
 * 为什么三档就够（用户："**只调间距与去重，不许加装饰**"）：
 *  - [PRESET_GAP_SM_DP] **8dp**：**同一块内**相邻元素（名称行与其副标题、块内行距）；
 *  - [PRESET_BLOCK_GAP_DP] **16dp**：一级页**各块之间**（横向条 / 名称行 / 常用参数 / 保存行）；
 *  - [PRESET_GAP_LG_DP] **24dp**：**面板顶部**（`LutSheet` 的 tab 行与横向条之间）—— 用户截图
 *    里最挤的一处就是这里（tab 与预设条贴在一起）。
 *
 * 为什么块间距从 PS-8 的 12dp 提到 16dp：用户实测"各区块贴在一起"，而 12dp 与块内行距（8dp）
 * 只差 4dp ⇒ 层次在真机上分不出来；16dp 让"块"这件事看得见（间距阶的下一档 24dp 太松，
 * 会把一级页推高 ~40dp，挤压取景区）。
 */
internal const val PRESET_GAP_SM_DP = 8f

/** 一级页各"块"之间的留白（★ PS-9：12dp → 16dp，理由见 [PRESET_GAP_SM_DP] 的 KDoc）。 */
internal const val PRESET_BLOCK_GAP_DP = 16f

/** 面板顶部留白（`LutSheet` 的 tab 行 → 横向预设条；用户截图里最挤的一处）。 */
internal const val PRESET_GAP_LG_DP = 24f

/** 参数行左侧标签列宽（跨行对齐：标签左、滑杆中、数值右）。 */
internal const val PRESET_LABEL_WIDTH_DP = 60f

/**
 * 参数行右侧数值列宽（等宽字体 + 右对齐 ⇒ 数值列对齐）。
 *
 * ★ PS-10：**46dp → 64dp**。46dp 是按 `0..150` 这类 3 字符读数定的（PS-8）；而 PS-10 的
 * 「白点」读数带物理单位（`+2.7 EV` / `-12.0 EV`，7~8 字符）—— 12sp 等宽字体下每字符 ≈7.2dp
 * ⇒ 8 字符 ≈58dp，沿用 46dp 会把读数裁成 `+2.7 E`（单位被截掉 = 用户看不出这是 EV）。
 * 数值列仍**所有行同一个宽度**（跨行对齐正是这个常量的目的），代价是滑杆短 18dp。
 */
internal const val PRESET_VALUE_WIDTH_DP = 64f

/**
 * ★ PS-9：卡片 / 输入框的**内边距**（用户："卡片内文字与边框的内边距，都要重看"）。
 * 水平 12dp / 垂直 10dp：PS-8 的 8dp 垂直内边距在 64dp 条高里让上下文字贴边
 * （卡片高 64 − 2×8 = 48dp 内容区，13sp 名称 + 10sp 标记两行挤在中间）。
 */
internal const val PRESET_CARD_PAD_H_DP = 12f
internal const val PRESET_CARD_PAD_V_DP = 10f

// ─────────────────────────────────────────────────────────────────────────────
// Compose：拍照界面「预设」tab（宿主 = LutSheet，父层已给 16dp 水平 padding）
// ─────────────────────────────────────────────────────────────────────────────

/** 不可用文字（与编辑器同一条观感；EditorScreen 那份是 private ⇒ 各写一行常量，不是逻辑复制）。 */
private val CamDisabledInk = FilmTheme.CreamDim.copy(alpha = 0.35f)

/**
 * 拍照界面「预设」tab 的全部内容（列表 / 编辑面板 / 菜单 / 删除确认）。
 *
 * ## 布局（★ PS-8：一级页最多四个视觉块）
 *
 * ```
 * ┌ 横向预设条（高 ${PRESET_STRIP_HEIGHT_DP}dp，可横滑）─────────────────┐
 * │ [＋ 新建预设] [标准 内置] [徕卡一瞬 内置·徕卡] [我的预设 1] …       │  ← 点 = 套用；长按 = 详情
 * └──────────────────────────────────────────────────────────────┘
 * 徕卡一瞬  [内置]                                   [完整参数 >]   ← 18sp 衬线 + 唯一入口
 * 内置预设 · 分组「徕卡」· 滤镜 徕卡 M9                              ← 一行副标题（可选）
 * 滤镜强度  ──────▮──────                                    30     ← 标签 │ 滑杆 │ 等宽数值
 * 颗粒      [ 关闭 │ 细颗粒 │ 中颗粒 │ 粗颗粒 ]                      ← 分段控件（等宽、选中填充）
 * 柔光      [ 关闭 │ 轻柔光 │ 中等柔光 │ 强烈柔光 ]
 * 光晕      [ 关闭 │ 轻微光晕 │ 中等光晕 │ 强烈光晕 ]
 * 暗角      [ 关闭 │ 轻微暗角 │ 中等暗角 │ 强烈暗角 ]
 * 未保存的调整                              [保存到预设]             ← 有改动才出现
 * ```
 *
 * 点卡片 = **套用**；**长按卡片 = 打开详情 / 编辑面板**（用户："参考 PC **长按跳转预设配置详情**"）；
 * 卡片右下「⋯」= 操作菜单（**不可用的项置灰但可见** + 一句实话，绝不隐藏）。
 *
 * ## ★ PS-7 修好的东西一位没回退
 *
 * 面板底 = `LutSheet` 的 `FilmTheme.PanelSolid`（不透明 ⇒ 取景界面的读数不会透上来）；一级页
 * **没有任何 `verticalScroll`**（用户上一轮骂的"点了没反应"= 内容在滚动区屏幕外，本页已无滚动区）；
 * 每个能点的都有可见反应（套用 / 详情 / 菜单 / 新建 / 档位 / 滑杆 / 完整参数 / 保存）。
 *
 * ## 四条纪律
 *
 *  1. **不做假控件**：能调 = 真的会变（`EditorParam.write` → `onPreview`）；这条预设不含 LUT
 *     ⇒ **不给假滑杆**，只写一行实话（ADR-12）。
 *  2. **拖动不写上层 state**：拖动每帧只调 [onPreview]（直通引擎），松手才 [onOverrides] ——
 *     每帧写 `CameraScreen` 的 state 会把整页拖进重组（v0.10.96 的 LUT 强度滑杆踩过同型坑）。
 *  3. **未保存不落盘**：只有保存动作写库（用户预设 → `PresetStore`；内置 → 覆盖层）。
 *  4. **编辑对象 == 生效对象**：一级页的名称行与常用参数永远对应 `selected`（见 [openEdit] 的
 *     不变量）—— 画面只有一份，"实时效果"必须有明确归属 ⇒ [onPreview] 因此永远作用于 `selectedPreset`。
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

    // ★ PS-8：**系统返回键分层**（用户 2026-10-12："一级页面系统返回按钮关闭预设页面，不要直接
    // 退出 app"）。编辑面板是一级页的**子态** ⇒ 返回先关它（照 `PhotoViewScreen.kt` 的先例：
    // 面板打开时返回键先关面板）。它注册在 `CameraPresetTab` 里 ⇒ 比 `CameraScreen` 的
    // 覆盖层 BackHandler **更晚注册 ⇒ 更优先**，两级不打架。
    BackHandler(enabled = editorOpen) { closeEditor() }

    // ★ PS-3：拖动中的改动持有者 —— **普通对象**（不参与 Compose 快照）⇒ 拖动每帧写它
    // 不会触发任何重组（只有 [RelativeSlider] 自己的拖动 state 在变）。松手时才经
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
        // ★ PS-8：一级页 = **三个视觉块**（横向预设条 / 名称行（含副标题与「完整参数 >」）/ 常用参数）
        // + 有改动时的一行保存。用户 2026-10-12（v0.11.23）："**第一级页面不需要提供全部参数，
        // 点击查看完整参数才提供**" ⇒ 「当前搭配」长清单 / 展开收起 / 选中项滑杆 / 行下小字说明
        // **全部删除**（完整参数一律走「完整参数 >」，PS-6b 那一页一个字没动）。
        //
        // ★ PS-9：**间距阶**（用户 2026-10-12 真机截图："**截图位置挤到一起了，优化布局**"）——
        // 顶部留 [PRESET_GAP_LG_DP]（它上方就是 `LutSheet` 的 LUT/预设 tab 行，用户截图里
        // tab 与预设条贴在一起），块间一律 [PRESET_BLOCK_GAP_DP]，块内相邻元素 [PRESET_GAP_SM_DP]。
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = PRESET_GAP_LG_DP.dp),
        ) {
            // ① 横向预设条（用户："预设改成横向滚动"）—— 首项 = 「＋ 新建预设」
            PresetStrip(
                items = presetStripItems(presets, selected.id, builtinOverrides),
                onNew = { openNew() },
                onApply = onApply,
                onDetail = { openEdit(it) },
                onMenu = { menuFor = it },
            )
            // ② 名称行 + 「完整参数 >」—— 18sp 衬线半粗；右侧 = 逐项参数的唯一入口
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = PRESET_BLOCK_GAP_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selected.name,
                    color = FilmTheme.Cream,
                    fontSize = 18.sp,
                    fontFamily = FilmTheme.Serif,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!presetDeletable(selected)) {
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
                    Spacer(Modifier.width(6.dp))
                }
                CamPresetActionButton("完整参数 >", enabled = true, onTap = onOpenFullEdit)
            }
            // ③ 一行副标题（可选：分组 / 滤镜；没有真实信息 ⇒ 整行不渲染）
            // ★ PS-9：它是**名称行这块的一部分** ⇒ 用块内小间距（PS-8 的 2dp 让副标题贴着标题，
            // 用户截图里"副标题与滑杆"那一处正是这么挤出来的）。
            presetSubtitleText(selected, selected.lutId?.let { id -> LutLibrary.byId(context, id)?.label })?.let { line ->
                Text(
                    line,
                    color = FilmTheme.CreamDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = PRESET_GAP_SM_DP.dp),
                )
            }
            // ④ 常用参数（用户点名的 5 项）—— 能力与口径一字未改，只重做视觉
            PresetQuickAdjustPanel(
                presetId = selected.id,
                params = params,
                lutStrength = lutStrengthNow,
                hasLut = hasLut,
                onEditParam = { param, v -> pushEdit(param, v) },
                onEditFinished = { finishEdit() },
                onPickLevel = { param, v -> pickLevel(param, v) },
            )
            // ⑤ 有未保存改动 ⇒ 一行「保存到预设」（调完即存；落盘后上层清掉改动，这一行随之消失）
            if (mine != null && !mine.isEmpty) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = PRESET_BLOCK_GAP_DP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "未保存的调整",
                        color = FilmTheme.CreamDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    CamPresetActionButton("保存到预设", enabled = true, primary = true, onTap = {
                        // ★ 保存口径：`[34]` 不进 slots ⇒ 显式带回 `lutStrength`（见 saveSlotsWithLutStrength）
                        onSaveSlots(saveSlotsWithLutStrength(selected, mine), mine)
                        onOverrides(null)
                    })
                }
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
 * ★ PS-6a/PS-8：**常用参数区**（用户点名的 5 项：LUT 强度 / 颗粒 / 柔光 / 光晕 / 暗角
 * ＋ ★ PS-10 的 filmic 白点）—— 一级页与编辑面板**共用同一个组件**（一处实现、两处调用；
 * 不是两份控件）。
 *
 * ★ PS-8 视觉（用户："UI 做得太丑了重新设计一下"）：
 *  - **每个参数一块、块间留白一致**（[PRESET_BLOCK_GAP_DP]，不挤在一起）；
 *  - LUT 强度 = **一行**：标签 + 细滑杆 + 右侧等宽数值（[PresetParamSliderRow]）；
 *  - 四档 = **分段控件**：一行内等宽均分、选中项填充强调色（[PresetLevelSegments]）。
 *    PS-7 的 `highlight`（清单选中 ⇒ 整行点亮）随清单一并删除 —— 一级页已没有清单。
 *
 * ★ PS-9（用户 2026-10-12："**颗粒改成滑杆吧**"）：颗粒从四档分段控件改成
 * **与 LUT 强度同一套视觉的强度滑杆**（[PresetParamSliderRow] + [GrainStrengthParam]，
 * 只写 `[26]`）；`[27]` 尺寸在一级页不再有控件、保持预设原值。其余三行（柔光 / 光晕 / 暗角）
 * 仍是分段控件，档位值见 [QuickLevelParam.levels]（暗角已按引擎满量程重定，见那里的注释）。
 *
 * 写值通路**一字未改**：滑杆经 [onEditParam]（`EditorParam.write`），档位经 [onPickLevel]
 * （同一个 `write` + 立即提交）。本组件**不碰** [CameraParamOverrides]。
 */
@Composable
private fun PresetQuickAdjustPanel(
    /** 归属预设 id（换预设 ⇒ 滑杆重建，不沿用上一条的读数） */
    presetId: String,
    /** 滑杆读写数组（= `cameraPresetParams(target, values, removed)`） */
    params: DoubleArray,
    lutStrength: Float,
    hasLut: Boolean,
    onEditParam: (EditorParam, Float) -> Unit,
    onEditFinished: () -> Unit,
    onPickLevel: (EditorParam, Float) -> Unit,
) {
    // ★ 阶段 3（引擎插槽）：**这条预设的引擎** = 本面板正在读写的那份数组的 `[98]`（与下发给
    // 引擎的是同一个 `cameraPresetParams(...)`）⇒ 不必另找真相源。判据见 [slotActiveInEngine]。
    // `getOrElse` 兜底短数组（同 E4-1 的长度纪律：读不到 ⇒ 按默认档 0 处理，不崩）。
    // ★ 阶段 3b：再 ⊕ 设置页的三档全局开关（prefs `engine_override`，缺省「跟随预设」）
    // ⇒ **生效引擎**。合成走契约函数（[effectiveEngineSlotOf] ⇒ `EngineScope.effectiveEngineSlot`），
    // 与设置页 /「完整参数」页**同一个入口**：否则一级页会按预设引擎显示、而渲染按生效引擎
    //（= "拖了画面不动"的假控件，ADR-12）。
    val presetEngineSlot = params.getOrElse(ENGINE_SLOT) { 0.0 }.toInt()
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("filmkit", 0) }
    val engineSlot = effectiveEngineSlotOf(prefs, presetEngineSlot)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = PRESET_BLOCK_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PRESET_GAP_SM_DP.dp),
    ) {
        if (hasLut) {
            // `[34]` 不进 `slots` ⇒ 借一份视图数组给滑杆读初值（换预设 / 换参数 ⇒ key 让它重建）
            key(presetId, LutStrengthParam.key) {
                PresetParamSliderRow(
                    label = "滤镜强度",
                    param = LutStrengthParam,
                    params = params.copyOf().also { it[LUT_STRENGTH_SLOT] = lutStrength.toDouble() },
                    onCommit = onEditParam,
                    onFinished = onEditFinished,
                )
            }
        } else {
            // 没有 LUT ⇒ **不给假滑杆**（拖了画面不会变）：一行实话（套用时保持当前 LUT）
            PresetParamLabelRow(label = "滤镜强度", note = "不含 LUT（套用时保持当前）")
        }
        // 颗粒强度（★ PS-9：档位 → **滑杆**，与上面 LUT 强度同一套视觉；只写 `[26]`）
        key(presetId, GrainStrengthParam.key) {
            PresetParamSliderRow(
                label = "颗粒",
                param = GrainStrengthParam,
                params = params,
                onCommit = onEditParam,
                onFinished = onEditFinished,
            )
        }
        // ★ PS-10：filmic **白点** `[38]`（用户 2026-10-12："黑白点要可调" —— 高光死白的病灶）。
        // 参数项**复用 `ParamControls.kt` 的那一条**（量程 / 读数 / write 全在它自己身上），
        // 与「完整参数 >」和编辑器是同一个对象 ⇒ 三个页面同一个数（不是第二份滑杆实现）。
        // ⚠ 黑点 `[37]` 不在这里（取舍依据见 [FILMIC_WHITE_EV_SLOT] 的 KDoc）。
        // ★ 阶段 3：`[38]` 是引擎 0（filmic）独占槽 ⇒ 切到别的引擎后**整行不进 UI 树**
        //（与「完整参数」页同一判据 —— 留着它拖了画面不动 = 假控件，ADR-12）。
        if (slotActiveInEngine(FILMIC_WHITE_EV_SLOT, engineSlot)) {
            key(presetId, FILMIC_WHITE_EV_SLOT) {
                PresetParamSliderRow(
                    label = "白点",
                    param = CAMERA_TUNABLE_SLOT_PARAM.getValue(FILMIC_WHITE_EV_SLOT),
                    params = params,
                    onCommit = onEditParam,
                    onFinished = onEditFinished,
                )
            }
        }
        // 柔光 / 光晕 / 暗角（写值仍走这三条既有滑杆的 `write`；档位值 = [QuickLevelParam.levels]）
        for (q in QUICK_LEVEL_PARAMS) {
            val param = CAMERA_TUNABLE_SLOT_PARAM.getValue(q.slot)
            PresetLevelSegments(
                label = q.label,
                names = q.names,
                current = nearestLevel(param.read(params), q.levels),
                onPick = { i -> onPickLevel(param, q.levels[i]) },
            )
        }
    }
}

/** 一行「标签 + 说明」（没有可调的控件时用：例如这条预设不含 LUT —— 不给假滑杆）。 */
@Composable
private fun PresetParamLabelRow(label: String, note: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = FilmTheme.CreamDim,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.width(PRESET_LABEL_WIDTH_DP.dp),
        )
        Text(
            note,
            color = CamDisabledInk,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * ★ PS-8：**一行的参数滑杆**：`标签 │ 滑杆 │ 数值`（数值列 = 等宽 + 右对齐 ⇒ 跨行对齐）。
 *
 * 复用 `ParamControls.kt` 的 [EditorParam]（量程 / 读数 / `write` 全在它自己身上）与既有
 * [RelativeSlider]（相对拖动 + 密集刻度 + 基准中点标记）—— **不是第二份滑杆实现**。
 * 拖动 state 留在这里（拖动每帧只重组这一行；上层 state 一位不写，松手才 [onFinished]）。
 */
@Composable
private fun PresetParamSliderRow(
    label: String,
    param: EditorParam,
    params: DoubleArray,
    onCommit: (EditorParam, Float) -> Unit,
    onFinished: () -> Unit,
) {
    var v by remember { mutableFloatStateOf(param.read(params)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = FilmTheme.CreamDim,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.width(PRESET_LABEL_WIDTH_DP.dp),
        )
        RelativeSlider(
            value = v,
            onValueChange = { nv ->
                v = nv
                onCommit(param, nv)
            },
            onDragEnd = { onFinished() },
            range = param.range,
            modifier = Modifier.weight(1f),
            rowHeight = 30.dp,
            ticks = true,
            // 中点标记由纯函数算（基准落在端点 / 量程外 ⇒ null = 不画）
            midFraction = pivotFraction(param.range, param.pivot),
            barThumb = true,
        )
        Text(
            param.format(v),
            color = FilmTheme.Cream,
            fontSize = 12.sp,
            fontFamily = FilmTheme.Mono,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(PRESET_VALUE_WIDTH_DP.dp),
        )
    }
}

/**
 * ★ PS-8：**分段控件**（四档：`关闭 | 细颗粒 | 中颗粒 | 粗颗粒`）—— 一行内 **等宽均分**，
 * 选中项**填充强调色**（用户："档位改用分段控件，不要散落的小 chip"）。
 *
 * 档位 = **一次性动作**（点一下写值并立即提交），不是拖动 ⇒ 走 [onPick]（不是滑杆）。
 * 段名过长 ⇒ 省略号（等宽分段让四档**永远同屏可选**，不再有横向滚动）。
 */
@Composable
private fun PresetLevelSegments(
    label: String,
    names: List<String>,
    current: Int,
    onPick: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = FilmTheme.CreamDim,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.width(PRESET_LABEL_WIDTH_DP.dp),
        )
        Row(
            Modifier
                .weight(1f)
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, FilmTheme.ChipBorder, RoundedCornerShape(8.dp)),
        ) {
            names.forEachIndexed { i, n ->
                val on = i == current
                // 段间分隔线（控件结构线，不是装饰：四档的边界要看得见）
                if (i > 0) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(FilmTheme.ChipBorder),
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (on) FilmTheme.Accent else Color.Transparent)
                        .pressScale(pressedScale = 0.96f, onTap = { onPick(i) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        n,
                        color = if (on) FilmTheme.OnAccent else FilmTheme.CreamDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * ★ PS-8：**横向预设条**（用户 2026-10-12："预设改成横向滚动"，参考 Filmix 的底部横向卡片）。
 *
 * `[＋ 新建预设] [标准] [徕卡一瞬] [我的预设 1] …` 一行横向可滑，高度 [PRESET_STRIP_HEIGHT_DP]。
 * 能力一个都没少：**点卡片 = 套用**、**长按卡片 = 详情 / 编辑面板**（`openEdit` 的不变量仍成立：
 * 进详情 ⇒ 这条成为生效的那条）、卡片右下 `⋯` = 既有操作菜单。
 */
@Composable
private fun PresetStrip(
    items: List<PresetStripItem>,
    onNew: () -> Unit,
    onApply: (EditorPreset) -> Unit,
    onDetail: (EditorPreset) -> Unit,
    onMenu: (EditorPreset) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(PRESET_STRIP_HEIGHT_DP.dp),
    ) {
        // 「＋ 新建预设」= 条里的**第一项**（用户点名的常驻入口）
        item(key = "preset_new") { PresetNewCard(onNew) }
        items(items, key = { it.preset.id }) { stripItem ->
            PresetStripCard(
                item = stripItem,
                onApply = { onApply(stripItem.preset) },
                onDetail = { onDetail(stripItem.preset) },
                onMenu = { onMenu(stripItem.preset) },
            )
        }
    }
}

/** 条首的「＋ 新建预设」卡片（与预设卡片等宽等高 ⇒ 条上基线一致）。 */
@Composable
private fun PresetNewCard(onTap: () -> Unit) {
    Box(
        Modifier
            .width(PRESET_STRIP_CARD_WIDTH_DP.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, FilmTheme.Accent, RoundedCornerShape(10.dp))
            .pressScale(pressedScale = 0.96f, onTap = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+ 新建预设",
            color = FilmTheme.Accent,
            fontSize = 12.sp,
            maxLines = 1,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 横向条上的一张预设卡片：**名称（13sp 衬线）+ 小标记（[PresetStripItem.badge]）+ `⋯`**。
 * 卡片等宽 ⇒ 条上每一项对齐；选中态 = **强调色描边 + `ChipBg` 底色**（用户："选中态要明显"，
 * 不靠字色）。
 */
@Composable
private fun PresetStripCard(
    item: PresetStripItem,
    onApply: () -> Unit,
    onDetail: () -> Unit,
    onMenu: () -> Unit,
) {
    Column(
        Modifier
            .width(PRESET_STRIP_CARD_WIDTH_DP.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(if (item.selected) FilmTheme.ChipBg else Color.Transparent)
            .border(
                1.dp,
                if (item.selected) FilmTheme.Accent else FilmTheme.ChipBorder,
                RoundedCornerShape(10.dp),
            )
            // 点 = 套用；长按 = 详情（PS-3 的第二入口）
            .pressScale(pressedScale = 0.96f, onTap = onApply, onLongTap = onDetail)
            // ★ PS-9：内边距走常量（PS-8 的 8dp 垂直内边距让两行文字贴着上下边框 —— 用户
            // "卡片内文字与边框的内边距，都要重看"）
            .padding(horizontal = PRESET_CARD_PAD_H_DP.dp, vertical = PRESET_CARD_PAD_V_DP.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            item.preset.name,
            color = if (item.selected) FilmTheme.Accent else FilmTheme.Cream,
            fontSize = 13.sp,
            fontFamily = FilmTheme.Serif,
            fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.badge.orEmpty(),
                color = CamDisabledInk,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "⋯",
                color = FilmTheme.CreamDim,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .pressScale(pressedScale = 0.94f, onTap = onMenu)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
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
