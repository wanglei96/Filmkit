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
import com.filmkit.app.camera.CAMERA_STANDARD_PRESET
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.PRESET_SOURCE_USER
import com.filmkit.app.camera.PresetScene
import com.filmkit.app.camera.formatZero150
import com.filmkit.app.camera.presetDeletable
import com.filmkit.app.camera.presetId
import com.filmkit.app.camera.presetScopeNotice
import com.filmkit.app.camera.presetSparseSlots
import com.filmkit.app.lut.LutLibrary

/**
 * ★ PS-2a（用户 2026-10-11 **方向纠正**："预设的配置 / 新增 / 编辑 / 删除**我指的是拍照界面的
 * 预设**。这个**不能让用户在相册里面改这些配置**。"）：**拍照界面 = 预设的管理场所**。
 *
 * 上一张卡（PS-1）把这套交互做进了编辑器（宿主错了，组件是好的）⇒ 本文件把那套交互搬到
 * 拍照界面 `LutSheet` 的「预设」tab：**看配置 / 新增 / 编辑 / 删除**都在这里；
 * 相册与编辑器只负责**套用**（编辑器侧的降级由 PS-2b 做）。
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
 * ## 三条不回退的既有能力（本卡一行都没动）
 *
 *  1. **P-2a 的套用**（含 LUT 四态）：本文件只调 `onApply`，落地仍是 `applyCameraPreset`
 *     （`CameraScreen.kt`）—— 判定与提示一个字都没改；
 *  2. **U-7 的曝光偏移**：本文件**永不把 `[0]` 写进预设**（[cameraPresetSlots]），套用路径
 *     仍走 `presetEvOffset`；
 *  3. **P-2c 的内置只读保护**：判据仍是 `presetDeletable` 这一个实现。
 *
 * ## ⚠ 未真机验证
 *
 * 本文件的纯逻辑部分由 `CameraPresetPanelUiTest` 覆盖；**UI 呈现**（底部弹出层的实际高度预算、
 * 长预设名的换行、深色底对比度、`⋯` 的触摸热区）需真机确认 —— 见
 * `docs/reports/ps2a-camera-preset-ui-REPORT.md` 的「未做 / 存疑项」。
 */

// ─────────────────────────────────────────────────────────────────────────────
// 纯逻辑（无 Compose / 无 Context ⇒ JVM 单测直接驱动）
// ─────────────────────────────────────────────────────────────────────────────

/** 曝光槽 [0]：**永不写进预设**（见 [cameraPresetSlots]）。 */
internal const val CAMERA_PRESET_EV_SLOT = 0

/**
 * 拍照界面保存预设用的**稀疏槽集** = [presetSparseSlots] **减去 `[0]` 曝光**。
 *
 * 为什么减：拍照界面的"当前曝光"= 用户基准 + 用户 EV 补偿 + 预设偏移（[effectiveExposureEv]），
 * 其中**用户的 EV 补偿是"拍摄态"不是风格**（`preset-DESIGN.md` §9.4，U-7）—— 把它写进预设，
 * 用户下次套用这条预设时会莫名多出一次曝光（而且他调 EV 的当下就会污染预设库）。
 *
 * ⚠ 代价（如实记录）：预设**自己**的曝光偏移（内置三条里「徕卡一瞬」没有、将来可能有）也一并
 * 不被保存 —— 拍照界面这一层拿到的参数是"已展开的当前生效参数"（[cameraPresetParams]），
 * 无法从中分辨"这一槽是预设自带"还是"用户刚调的"。宁可少存一个槽，也不把拍摄态写进风格。
 * 想保留曝光偏移的配方请在编辑器侧维护（那边 `[0]` 是**后期曝光**，语义不同）。
 */
internal fun cameraPresetSlots(film: DoubleArray): Map<Int, Double> =
    presetSparseSlots(film) - CAMERA_PRESET_EV_SLOT

/**
 * 「+ 新建预设」的构造（纯函数 ⇒ 单测直接钉住 id / 来源 / 稀疏槽 / LUT 三条口径）。
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
 * 副本永远是用户预设 —— 内置预设的「复制」在菜单里是灰的（`presetMenuItems` 按
 * `presetDeletable` 判，PS-1 的口径），但**复制内置配方**恰恰是用户最需要的动作；
 * 本函数保证"被调用时行为正确"（权限判断不在这一层重写）。
 */
internal fun copiedCameraPreset(p: EditorPreset, now: Long, salt: Int): EditorPreset = p.copy(
    id = presetId(now, salt),
    name = "${p.name} 副本",
    source = PRESET_SOURCE_USER,
    favorite = false,
    createdAt = now,
)

/**
 * 编辑面板底部按钮的可用性 = `presetEditorButtons` + **内置只读修正**。
 *
 * 为什么要包一层：`presetEditorButtons` 在**编辑态**只按 `paramsReady` / 名称是否非空决定
 * `updateFromCurrent` / `saveMeta`，**没查可删性** ⇒ 内置预设的「从当前相机参数更新」与
 * 「保存名称 / 分组」都会是**亮的**（`updatePreset` / `renamePreset` 内部虽有 `presetDeletable`
 * 守卫，用户看到的是两个点了没反应的按钮 —— ADR-12 明令禁止的假按钮）。这里按同一条判据
 * （`presetDeletable`）把它们置灰，**不重写** `source == "builtin"` 的比较。
 *
 * 内置预设仍可用的两件事：**套用**（行本身 / 菜单）与**看配置**（菜单「查看配置」进同一个面板，
 * 清单照常渲染）。
 *
 * 拍照界面的 `paramsReady` 恒为 true（`cameraPresetParams` 是纯函数，没有"首帧未就绪"），
 * 保留该参数是为了与 PS-1 的同一份口径对齐。
 */
internal fun cameraPresetEditorButtons(
    isNew: Boolean,
    preset: EditorPreset?,
    paramsReady: Boolean,
    name: String,
): PresetEditorButtons {
    val b = presetEditorButtons(isNew, preset, paramsReady, name)
    return if (!isNew && preset != null && !presetDeletable(preset)) {
        b.copy(updateFromCurrent = false, saveMeta = false)
    } else {
        b
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Compose：拍照界面「预设」tab（宿主 = LutSheet，父层已给 16dp 水平 padding）
// ─────────────────────────────────────────────────────────────────────────────

/** 不可用文字（与编辑器同一条观感；EditorScreen 那份是 private ⇒ 各写一行常量，不是逻辑复制）。 */
private val CamDisabledInk = FilmTheme.CreamDim.copy(alpha = 0.35f)

/**
 * 拍照界面「预设」tab 的全部内容（列表 / 编辑面板 / 菜单 / 删除确认）。
 *
 * ## 布局（三条用户诉求的落点）
 *
 * ```
 * [+ 新建预设]  点行尾「⋯」可套用 / 编辑 / 复制 / 删除     ← 常驻入口，不再靠用户猜
 * ┌──────────────────────────────────────────────┐
 * │ 标准                              [内置]      │  ← 点行 = 套用（P-2a 不变）
 * │ 不含参数（套用时其余参数保持不动）        [⋯] │
 * ├──────────────────────────────────────────────┤
 * │ 徕卡一瞬  [内置]  徕卡                        │  ← 名称 + 来源 + 分组
 * │ 对比度 -3 · 饱和度 · 中间调 +5 · … · LUT …    │  ← 参数摘要（"这条包含什么"）
 * └──────────────────────────────────────────────┘
 * ```
 *
 * 点行 = **套用**（与 P-2a 的卡片同一条路：`onApply` → `applyCameraPreset`）；
 * 行尾「⋯」= 操作菜单（**内置的受限项置灰但可见** + 一句实话，绝不隐藏）。
 *
 * @param presets `BuiltinPresets.all(context, prefs)`（用户预设在前 + 内置三条在后）
 * @param selected 当前生效的预设（列表首项恒为 [CAMERA_STANDARD_PRESET]）
 * @param currentSlots **当前相机参数**的稀疏集（新建态的"将要保存什么"预览；已剔除 `[0]`）
 * @param lutId / lutStrength 当前 LUT 与强度（新建态的清单要显示这一行，`[34]` 不进 slots）
 */
@Composable
internal fun CameraPresetTab(
    presets: List<EditorPreset>,
    selected: EditorPreset,
    currentSlots: Map<Int, Double>,
    lutId: String?,
    lutStrength: Float,
    onApply: (EditorPreset) -> Unit,
    onSave: (String, String) -> Unit,
    onUpdate: (EditorPreset) -> Unit,
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

    /** 打开编辑面板（「⋯」→「编辑 / 重命名」同一个落点：改名就在面板的名称框里） */
    fun openEdit(p: EditorPreset) {
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
        // 编辑面板**取代列表**（同一层，不叠控件）—— 面板空间只够一件事，叠起来两边都看不清
        CameraPresetEditorPanel(
            p = editor,
            slots = if (editorNew) currentSlots else editor?.slots.orEmpty(),
            lut = if (editorNew) {
                lutId?.let { PresetLutLine(it, formatZero150(lutStrength)) }
            } else {
                editor?.let { presetLutLine(it) }
            },
            name = name,
            onName = { name = it },
            group = group,
            onGroup = { group = it },
            // 拍照界面的参数恒可就绪（cameraPresetParams 是纯函数）⇒ paramsReady = true
            buttons = cameraPresetEditorButtons(editorNew, editor, paramsReady = true, name = name),
            readOnlyNote = editor?.let { presetReadOnlyNote(it) },
            onBack = { closeEditor() },
            onSaveNew = {
                onSave(name, group)
                closeEditor()
            },
            onUpdate = { editor?.let { onUpdate(it) } },
            onSaveMeta = { editor?.let { onRename(it, name, group) } },
            onDelete = { editor?.let { pendingDelete = it } },
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
                    "点行尾「⋯」可套用 / 编辑 / 复制 / 删除",
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
                        onApply = { onApply(p) },
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
                                            // 本来就完整可读）—— 内置预设走这条路看配置（编辑项是灰的）
                                            PresetAction.VIEW, PresetAction.EDIT, PresetAction.RENAME -> openEdit(p)
                                            PresetAction.COPY -> onCopy(p)
                                            PresetAction.DELETE -> pendingDelete = p
                                        }
                                    },
                                )
                                .padding(horizontal = 6.dp, vertical = 9.dp),
                        )
                    }
                    // 一句实话（内置预设）；用户预设 ⇒ null ⇒ 不占行
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
 * 选中项用琥珀描边 + 底色标出（"当前生效的是哪条"一眼可见，旧卡片版靠边框色）。
 */
@Composable
private fun CameraPresetRow(
    p: EditorPreset,
    selected: Boolean,
    /** 参数摘要（含 LUT 行）；空串 ⇒ 显示"不含参数" */
    summary: String,
    onApply: () -> Unit,
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
            .pressScale(pressedScale = 0.98f, onTap = onApply)
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
                // 来源标记（判据仍是 presetDeletable —— 内置只读的唯一实现）
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
 * **参数清单**（分组标题 + 「名称 读数」+ LUT 行）：只读。
 *
 * 数据源 = `presetDetailSections`（中文名 / 单位 / 分组的**唯一维护点**，PS-1 立的规矩）——
 * 本文件只负责把它画出来，**不重写任何一条格式化**。
 *
 * ⚠ 本面板**不做逐项拖滑杆**（架构师明确划定）：调参是参数面板的职责；拍照界面这一层根本
 * 没有风格参数滑杆 ⇒ 清单里的值只可能来自"当前相机参数"或"这条预设存着什么"。
 */
@Composable
private fun CamPresetParamList(slots: Map<Int, Double>, lut: PresetLutLine?, maxHeight: Dp) {
    val context = LocalContext.current
    val sections = presetDetailSections(slots)
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
    ) {
        sections.forEach { sec ->
            item(key = "sec_${sec.title}") {
                Text(
                    sec.title,
                    color = FilmTheme.Accent,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 5.dp, bottom = 1.dp),
                )
            }
            items(sec.rows, key = { "row_${it.slot}" }) { r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        r.label,
                        color = FilmTheme.Cream,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        r.value,
                        color = FilmTheme.Cream,
                        fontSize = 12.sp,
                        fontFamily = FilmTheme.Mono,
                        maxLines = 1,
                    )
                }
            }
        }
        if (sections.isEmpty()) {
            item(key = "noParams") {
                Text(
                    "这条预设不含参数（套用时其余参数全部保持不动）",
                    color = FilmTheme.CreamDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
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
 * 预设**编辑面板**（新建态 / 编辑态同一个面板）= 用户第 1 条诉求的"配置面板"。
 *
 * 内容：**名称 / 分组输入框** + **完整参数清单**（只读）+ 底部动作：
 *  - **新建态**（`p == null`）：「保存当前参数为新预设」（用**当前相机参数**，见 [newCameraPreset]）
 *    + 「取消」；
 *  - **编辑态**：「从当前相机参数更新」（[updatedCameraPreset]）、「保存名称 / 分组」、
 *    「删除」（仅 user 预设；内置 ⇒ 置灰 + 一句实话）。
 */
@Composable
private fun CameraPresetEditorPanel(
    /** null = 新建态 */
    p: EditorPreset?,
    /** 参数清单的数据源：新建态 = 当前相机参数的稀疏集；编辑态 = 这条预设的 slots */
    slots: Map<Int, Double>,
    lut: PresetLutLine?,
    name: String,
    onName: (String) -> Unit,
    group: String,
    onGroup: (String) -> Unit,
    buttons: PresetEditorButtons,
    readOnlyNote: String?,
    onBack: () -> Unit,
    onSaveNew: () -> Unit,
    onUpdate: () -> Unit,
    onSaveMeta: () -> Unit,
    onDelete: () -> Unit,
) {
    val isNew = p == null
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
        // ③ 参数清单（只读）+ 一行说明"参数从哪来"
        Text(
            if (isNew) {
                "将要保存的参数（当前相机参数 · 只与默认值不同的项）"
            } else {
                "这条预设包含的参数（只读）"
            },
            color = FilmTheme.CreamDim,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
        CamPresetParamList(slots = slots, lut = lut, maxHeight = 150.dp)
        // ④ 动作：横向可滚（三个中文按钮在窄屏上放不下时不该被裁掉）
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
                CamPresetActionButton("从当前相机参数更新", buttons.updateFromCurrent, onTap = onUpdate)
                Spacer(Modifier.width(8.dp))
                CamPresetActionButton("保存名称 / 分组", buttons.saveMeta, onTap = onSaveMeta)
                Spacer(Modifier.width(8.dp))
                CamPresetActionButton("删除", buttons.delete, onTap = onDelete)
            }
        }
        // 一句实话：内置预设为什么有一半按钮是灰的（与菜单里那句同一条判据 / 同一份文案）
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
