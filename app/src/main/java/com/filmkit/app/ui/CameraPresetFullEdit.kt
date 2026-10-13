package com.filmkit.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmkit.app.camera.CAMERA_PATH_SLOTS
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.cameraPresetParams
import com.filmkit.app.lut.LutLibrary

/**
 * ★ PS-6b：**完整参数编辑页**（全屏覆盖层）。
 *
 * 用户原话（2026-10-12 真机实测 v0.11.21）："点击**完整参数**跳转类似**图1**页面提供
 * **完整、专业调节**，做好**参数分组提高屏幕利用率**。"
 *
 * ## 形态（照"图 1"做）
 *
 * ```
 * ← 编辑拍摄预设                                       保存
 * ┌────────────────────────────────────────────────────────┐
 * │ 徕卡一瞬                                                │
 * │ [全部][柯达][富士][…]              ← LUT 分类 tab        │
 * │ ▣  ▣  ▣  ▣   ← LUT 横向缩略图（可选中，复用 LutCard）    │
 * └────────────────────────────────────────────────────────┘
 *   滤镜强度 ──────●────────── 100%
 * ┌────────────────────────────────────────────────────────┐
 * │ [基础][高级]                              ⟳ 重置        │
 * │ 调整                                                    │
 * │  +0.3   6500K   +12    +20      ← 一行 4 格：数值+参数名 │
 * │  曝光   色温    色调   对比度                             │
 * │ …                                                       │
 * │ ────────────────────────────────────────────────────── │
 * │ ● 曝光                                                  │
 * │                                  +0.3 EV                │
 * │ ────────────●───────────────────────  ← 一次一项的滑杆   │
 * └────────────────────────────────────────────────────────┘
 * ```
 *
 * ## 与 PS-6a 的接口（**唯一挂载点**）
 *
 * 「完整参数 >」按钮由 PS-6a 加在 `CameraPresetPanel.kt`（`CameraPresetTab(onOpenFullEdit = …)`，
 * 参数带默认空实现）；本页由 `CameraScreen.kt` 挂载并把 `onOpenFullEdit = { fullEditOpen = true }`
 * 传进去。两侧只靠这一个回调对接：**各自落地都编译得过**，没有"必须同时改同一个调用点"的耦合
 * （签名在 PS-6a 的文件里、实参在我的文件里）。
 *
 * ## 三条口径（裁决与理由）
 *
 *  1. **参数来源唯一**：网格里的每一项都是 `ParamControls.kt` 参数表里的 [EditorParam]
 *     （`AdjustParams` / `ColorParams` / `TextureParams` 过滤到"拍照链路真的可调"的那些，
 *     判据 = `CAMERA_TUNABLE_SLOT_PARAM`）—— 量程 / 恒等值 / 读数 / 槽位读写**一个都不重写**
 *     （硬约束：不许第二份参数表）。滑杆 = [EditorSlider]，LUT 强度 = [StrengthRow]，
 *     LUT 卡片 = [LutCard]，分类 pill = [SheetPill]，全是既有组件。
 *  2. **不做假控件**（ADR-12）：拍照链路不读的槽 / 没有滑杆的槽**照样在网格里**
 *     （灰显 + [cameraParamAdjustabilityNote] 的原因），点它 ⇒ 底部显示原因而不是假滑杆。
 *  3. **重置 = 回到「这条预设的原值」**（丢弃本页全部未保存改动），**不是**回到中性基线，
 *     也**不是**"恢复出厂"（那是内置预设的 `BuiltinOverrides` 覆盖层，入口在预设列表的
 *     「⋯」→「恢复默认」）。理由：本页打开时的画面 = 这条预设的配方；把"重置"做成中性基线
 *     等于用户点一下就把整条预设的调色抹平（不可逆的破坏），而"放弃我刚才的修改"才是这个
 *     按钮在编辑语境里的含义。机器判据 = `cameraPresetParams(preset, 重置后overrides)`
 *     与 `cameraPresetParams(preset)` **逐槽相等**（`CameraPresetFullEditTest` 钉住）。
 *
 * ## 保存
 *
 * 「保存」= `saveCameraPresetSlots(预设, 改动)`（**复用唯一实现**：写 slots + 内置走覆盖层 +
 * 列表刷新）**加上**把当前 LUT 与强度写进这条预设的 `lutId` / `lutStrength`（口径 =
 * `newCameraPresetFrom` / `updatedCameraPreset`：没选 LUT ⇒ 强度回落 1.0）。理由：本页顶部
 * 卡片的第一主角就是 LUT，用户在这里选 LUT 的意图必然是"这条预设的 LUT"，存不上就是坑。
 * 内置预设**同样可保存**（只禁删，用户 2026-10-11 拍板）—— 分流在 `persistPreset` 里。
 *
 * ## 未真机验证
 *
 * 纯逻辑（分组 / 排布 / 重置 / 保存口径）有单测；布局密度（一行 4 格的读数是否会截断）、
 * 全屏覆盖层的手势层级、LUT 缩略图在深色卡片上的观感**需真机确认**，见
 * `docs/reports/ps6b-full-param-page-REPORT.md` 的验收步骤。
 */

// ─────────────────────────────────────────────────────────────────────────────
// 纯逻辑（无 Compose / 无 Context ⇒ JVM 单测直接驱动）
// ─────────────────────────────────────────────────────────────────────────────

/** 分段切换的两档。 */
internal enum class FullParamTab(val title: String) {
    BASIC("基础"),
    ADVANCED("高级"),
}

/**
 * **基础**档 = Lightroom Classic「Basic」面板对应的 10 条。
 *
 * 判据（可辩护、不拍脑袋）：这 10 条正是 LrC Basic 面板里存在的滑杆 ∩ 本引擎已有的参数项
 * （LrC 的 texture / dehaze / vibrance 本引擎没有，故不在其中）。它们是"先定整体明暗与
 * 色偏、再定影调分区、最后定色彩浓度与局部质感"这条**调色决策主干**（与 `AdjustParams`
 * 的排序理由同源）。
 *
 * ⚠ 恒等值不是 0 的项（色温 6500K / 饱和度 0 / 对比度 0 在 UI 单位下都是"不变点"）由
 * [EditorParam.pivot] 自己承载 —— 本集合只回答"哪几条进基础档"，不含第二份数值。
 */
internal val BASIC_PARAM_KEYS: Set<String> = linkedSetOf(
    "ev", "temp", "tint", "contrast", "highlights", "shadows", "whites", "blacks", "sat", "clarity",
)

/**
 * 拍照链路真的可调的**参数项**（= `CAMERA_TUNABLE_SLOT_PARAM` 的 value 去重，保持参数表顺序）。
 *
 * `by lazy` 而不是顶层 val：它读同包另一个文件（`ParamControls.kt`）的顶层 `val`，
 * 顶层属性跨文件初始化顺序由编译器决定 ⇒ 直接求值可能在 `CAMERA_TUNABLE_SLOT_PARAM`
 * 建好之前先跑（读到空 map = 网格全空）。lazy 把它推迟到第一次访问。
 */
internal val CAMERA_TUNABLE_PARAMS: List<EditorParam> by lazy {
    (AdjustParams + ColorParams + TextureParams)
        .filter { p -> CAMERA_TUNABLE_SLOT_PARAM.values.any { it.key == p.key } }
}

/** 参数 key → 它读写的槽（升序）。反查表（`CAMERA_TUNABLE_SLOT_PARAM` 是槽 → 参数）。 */
private val PARAM_SLOTS: Map<String, List<Int>> by lazy {
    CAMERA_TUNABLE_SLOT_PARAM.entries
        .groupBy({ it.value.key }, { it.key })
        .mapValues { (_, slots) -> slots.sorted() }
}

/**
 * 这条滑杆写哪些槽（一条滑杆多槽：胶片曲线各 3 槽、饱和度 3 槽）。
 * 量程 / 换算仍在 [EditorParam] 自己身上，本函数只回答"涉及哪几个槽"。
 */
internal fun paramSlotsOf(param: EditorParam): List<Int> = PARAM_SLOTS[param.key].orEmpty()

/** 组标题（**分组依据 = `ParamControls.kt` 的三张参数表** + 一个只读兜底组）。 */
private const val GRP_ADJUST = "调整"
private const val GRP_COLOR = "色彩"
private const val GRP_FILM = "质感"
private const val GRP_READONLY = "只读"

/** 参数项属于哪一组（写在参数表里的位置 = 它的身份，不另立一套分类）。 */
private fun groupOf(param: EditorParam): String = when {
    AdjustParams.any { it.key == param.key } -> GRP_ADJUST
    ColorParams.any { it.key == param.key } -> GRP_COLOR
    else -> GRP_FILM
}

/**
 * 网格里的一格。
 *
 * @property key 稳定标识（参数项 = [EditorParam.key]；只读槽 = `slot:N`）—— 选中态 / 滑杆
 *   `key(...)` 都按它走，换格必然重建（否则新参数沿用上一条的读数）。
 * @property slot 主槽位（可调性判据 [cameraParamAdjustability] 与只读读数都用它）
 * @property param 参数项；**null = 该槽没有滑杆**（点开只显示原因，不出现假滑杆）
 * @property label 参数名（参数项 = `param.label`；只读槽 = `PresetDetail.kt` 的既有名字）
 */
internal data class FullParamCell(
    val key: String,
    val slot: Int,
    val param: EditorParam?,
    val label: String,
)

/** 一个分组 + 已经**按行切好**的格子（[rows] 每行 ≤ [FULL_GRID_COLUMNS] 格，最后一组可不满）。 */
internal data class FullParamSection(val title: String, val rows: List<List<FullParamCell>>)

/** 一行几格（用户："提高屏幕利用率"，图 1 是一行 4 格）。 */
internal const val FULL_GRID_COLUMNS = 4

/**
 * 只读槽（灰显 + 原因）= **没有滑杆的槽** ∪ **这条预设包含的、拍照链路不读的槽**
 * （与 `cameraTreeSlots` 同型："全量显示可调的 + 这条预设里存在的其它槽"）。
 *
 * `[34]` LUT 强度排除（它由顶部 LUT 卡片与滤镜强度滑杆承载，与 `presetDetailSections`
 * 同一条排除）—— 否则参数网格里会多出一个"能看不能调"的 LUT 强度。
 *
 * `[98]` 引擎插槽**不需要在这里显式排除**（引擎插槽重构阶段 1）：它不是可调参数（是
 * "选哪个引擎"；阶段 3 的切换入口是**引擎选择面**，见 `docs/engine-slots-design.md` §8），
 * 而它属于 `CAMERA_PATH_SLOTS`（`passFilm` 读它做非 0 降级判据）⇒ 上面的
 * `includedSlots.filter { it !in CAMERA_PATH_SLOTS }` 已经把它挡在只读组之外。既有
 * "引擎状态类"槽位（`[28]` auto_tone / `[30]` 过曝 / `[31..33]` / `[35][36]` / 解码段
 * `[39..59]`）同理不入只读组 —— 这正是本函数"参数面板只展示参数"的既有约定。
 */
internal fun fullEditReadonlySlots(includedSlots: Set<Int>): List<Int> =
    (CAMERA_NO_SLIDER_SLOTS + includedSlots.filter { it !in CAMERA_PATH_SLOTS })
        .filter { it != LUT_STRENGTH_SLOT }
        .sorted()

/**
 * 参数网格（纯函数）：按 [tab] 选参数项、按三组分段、组内按 [FULL_GRID_COLUMNS] 切行。
 *
 *  - **基础**：`BASIC_PARAM_KEYS` 里的参数项（只有「调整」组有内容）；
 *  - **高级**：全部可调参数项 + 末尾的「只读」组（[fullEditReadonlySlots]，[includedSlots]
 *    用来决定"这条预设包含的、拍照链路不读的槽"要不要出现）。
 */
internal fun fullParamSections(tab: FullParamTab, includedSlots: Set<Int>): List<FullParamSection> {
    val params = when (tab) {
        FullParamTab.BASIC -> CAMERA_TUNABLE_PARAMS.filter { it.key in BASIC_PARAM_KEYS }
        FullParamTab.ADVANCED -> CAMERA_TUNABLE_PARAMS
    }
    val out = ArrayList<FullParamSection>(FULL_GRID_COLUMNS)
    for (g in listOf(GRP_ADJUST, GRP_COLOR, GRP_FILM)) {
        val cells = params.filter { groupOf(it) == g }
            .map { FullParamCell(it.key, paramSlotsOf(it).firstOrNull() ?: 0, it, it.label) }
        if (cells.isNotEmpty()) out += FullParamSection(g, cells.chunked(FULL_GRID_COLUMNS))
    }
    if (tab == FullParamTab.ADVANCED) {
        val cells = fullEditReadonlySlots(includedSlots)
            .map { FullParamCell("slot:$it", it, null, presetDetailRowOf(it, 0.0).label) }
        if (cells.isNotEmpty()) out += FullParamSection(GRP_READONLY, cells.chunked(FULL_GRID_COLUMNS))
    }
    return out
}

/**
 * 一格的读数：参数项 = **它自己的** [EditorParam.format]（与编辑器滑杆 / 详情页同一份口径）；
 * 只读槽 = `PresetDetail.kt` 的既有格式化（颗粒显示 `ISO 400` 那种）。
 */
internal fun fullParamCellValue(cell: FullParamCell, film: DoubleArray): String {
    val p = cell.param
    if (p != null) return p.format(p.read(film))
    val v = if (cell.slot in film.indices) film[cell.slot] else 0.0
    return presetDetailRowOf(cell.slot, v).value
}

/**
 * 这条预设**是否包含**该参数（任一槽被包含即算 —— 一条滑杆多槽时按"涉及"判）。
 * `param == null`（只读格）⇒ false（没有滑杆的格不参与这条提示）。
 */
internal fun paramIncludedInPreset(param: EditorParam?, includedSlots: Set<Int>): Boolean =
    param != null && paramSlotsOf(param).any { it in includedSlots }

/**
 * 「⟳ 重置」= **丢弃本页全部未保存改动** ⇒ 一切回到这条预设的原值（裁决与理由见文件头）。
 *
 * 返回**带归属的空改动**（不是 null）：结构上与"用户对这条预设的改动集"一致，`isEmpty`
 * 为 true ⇒ 「保存 / 重置」两个按钮同时置灰（没有改动可存可丢）。
 */
internal fun resetFullParamOverrides(overrides: CameraParamOverrides?): CameraParamOverrides? =
    overrides?.let { CameraParamOverrides(it.presetId) }

/** 「保存」是否可点：**有未保存改动才可点**（没改动 ⇒ 置灰，不做点了没反应的按钮）。 */
internal fun fullEditSaveEnabled(overrides: CameraParamOverrides?): Boolean = overrides?.isEmpty == false

/**
 * 保存目标：**按 id 在最新列表里取那一条**，而不是用打开本页时的旧对象。
 *
 * 为什么：保存一次之后 `presets` 会刷新（`BuiltinPresets.all` 重建），旧对象已经落后于库；
 * 拿旧对象继续保存会把上一次的结果覆盖回去（丢改动）。id 找不到（预设刚被删）⇒ 回落旧对象
 * （保存等于"以它为准再写一次"，界面不会崩）。
 */
internal fun fullEditSaveTarget(open: EditorPreset, presets: List<EditorPreset>): EditorPreset =
    presets.firstOrNull { it.id == open.id } ?: open

/**
 * 本页保存时写进预设的 **LUT 两个字段**：没选 LUT ⇒ 强度回落 1.0
 * （与 `newCameraPresetFrom` / `updatedCameraPreset` 逐字同口径 —— 没选 LUT 时"强度"与预设无关）。
 */
internal fun fullEditLutFields(lutId: String?, lutStrength: Double): Pair<String?, Double> =
    lutId to (if (lutId != null) lutStrength else 1.0)

/** 这条预设的 LUT 字段是否需要因为本页的选择而改写（相同 ⇒ 不白写一次库）。 */
internal fun fullEditLutChanged(p: EditorPreset, lutId: String?, lutStrength: Double): Boolean {
    val (id, s) = fullEditLutFields(lutId, lutStrength)
    return p.lutId != id || p.lutStrength != s
}

// ─────────────────────────────────────────────────────────────────────────────
// Compose：全屏覆盖层（**不用 M3 ModalBottomSheet** —— 真机返回要按两次才关，项目已有裁决）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 拖动中的最后一个改动（**不是 Compose state**：每帧写 state 会把整页拖进重组，
 * 与 `CameraPresetPanel.kt` 的 `DragHolder` 同款 —— 那是 PS-3 踩过的坑）。
 */
private class FullEditDrag {
    var value: CameraParamOverrides? = null
}

/**
 * 完整参数编辑页（全屏）。调用点 = `CameraScreen.kt`（在 `lutOpen` 的 sheet **之上**）。
 *
 * @param preset 当前生效的那条预设（PS-6a 的入口保证"编辑对象 == 生效对象"）
 * @param overrides 该预设**未保存**的改动（本页与预设详情面板共用同一份 —— 从详情进来能接着调）
 * @param lutId / lutStrength 当前生效 LUT 与强度（事实源 = 全局选择，与 LUT sheet 同一个）
 * @param lutCats / lutCatOf LUT 分类（`LutLibrary.allCategories` / 每条的归属）
 * @param lutPreviews LUT 缩略图（`LutPreview` 生成并缓存的那一份，不在这里重渲染）
 * @param onPreview 拖动中 ⇒ 立即下发取景（**不要**在这里写上层 state）
 * @param onOverrides 松手 / 重置 ⇒ 提交改动（null = 无改动）
 * @param onPickLut 选 LUT（null = 「无」）—— 走既有的 `lutPending` / `clearCameraLut` 链路
 * @param onStrength / onStrengthFinished 滤镜强度：拖动直通引擎，松手写 prefs
 * @param onSave 「保存」= 写回这条预设（含 LUT 两字段，口径见文件头）
 * @param onClose 「←」返回（未保存改动**保留**在 overrides 里，与 PS-3 的详情面板一致）
 */
@Composable
internal fun CameraPresetFullEdit(
    preset: EditorPreset,
    overrides: CameraParamOverrides?,
    lutId: String?,
    lutStrength: Float,
    lutCats: List<String>,
    lutCatOf: Map<String, String>,
    lutPreviews: Map<String, Bitmap>,
    onPreview: (CameraParamOverrides) -> Unit,
    onOverrides: (CameraParamOverrides?) -> Unit,
    onPickLut: (LutLibrary.Entry?) -> Unit,
    onStrength: (Float) -> Unit,
    onStrengthFinished: (Float) -> Unit,
    onSave: (EditorPreset, CameraParamOverrides?) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val mine = overrides.forPreset(preset)
    val values = mine?.values ?: emptyMap()
    val removed = mine?.removed ?: emptySet()
    // 当前生效参数（预设配方 + 未保存改动）：与下发给引擎的**同一个函数**
    // ⇒ 网格里的读数就是画面上的值（ADR-12：不显示第二套读数）
    val film = remember(preset, values, removed) { cameraPresetParams(preset, values, removed) }
    val included = remember(preset, values, removed) { ((preset.slots - removed) + values).keys }
    var tab by remember { mutableStateOf(FullParamTab.BASIC) }
    var selected by remember { mutableStateOf<String?>(null) }
    var cat by remember { mutableStateOf<String?>(null) }
    val drag = remember { FullEditDrag() }
    val sections = remember(tab, included) { fullParamSections(tab, included) }
    val canSave = fullEditSaveEnabled(mine)
    val entries = remember(cat, lutCatOf) {
        LutLibrary.entries(context).filter { cat == null || lutCatOf[it.id] == cat }
    }
    val selectedCell = sections.asSequence().flatMap { it.rows.asSequence().flatten() }
        .firstOrNull { it.key == selected }

    Box(
        Modifier
            .fillMaxSize()
            .background(FilmTheme.Body)
            // 吃掉点击：本页盖在 LUT sheet 之上，不能让手势穿到底下的卡片
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp),
        ) {
            // ── 顶栏：← 编辑拍摄预设 | 保存 ──────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "← 编辑拍摄预设",
                    color = FilmTheme.Cream,
                    fontSize = 15.sp,
                    fontFamily = FilmTheme.Serif,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .pressScale(pressedScale = 0.96f, onTap = onClose)
                        .padding(vertical = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "保存",
                    color = if (canSave) FilmTheme.Accent else DisabledInk,
                    fontSize = 14.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .pressScale(enabled = canSave, onTap = {
                            onSave(preset, mine)
                            // 值已经落进预设 ⇒ 上层不再有"未保存改动"（保存按钮当场置灰）
                            onOverrides(null)
                            selected = null
                            android.widget.Toast.makeText(
                                context, "已保存到预设「${preset.name}」", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        })
                        .padding(vertical = 4.dp),
                )
            }

            // ── 顶部卡片：预设名 + LUT 分类 + LUT 横向缩略图 ────────────────────
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(FilmTheme.PanelSolid)
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    preset.name,
                    color = FilmTheme.Cream,
                    fontSize = 17.sp,
                    fontFamily = FilmTheme.Serif,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                ) {
                    item { SheetPill("全部", selected = cat == null) { cat = null } }
                    items(lutCats) { c -> SheetPill(c, selected = cat == c) { cat = c } }
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp),
                ) {
                    item {
                        LutCard(label = "无", selected = lutId == null, preview = null) { onPickLut(null) }
                    }
                    items(entries, key = { it.id }) { e ->
                        LutCard(
                            label = e.label,
                            selected = lutId == e.id,
                            preview = lutPreviews[e.id],
                        ) { onPickLut(e) }
                    }
                }
            }

            // ── 「编辑」区：滤镜强度（0–150%，右侧百分比）──────────────────────
            // 复用 StrengthRow（与 LUT sheet 同一行组件、同一量程 = LutStrengthParam.range）
            StrengthRow(
                initial = lutStrength,
                onCommit = onStrength,
                onFinished = onStrengthFinished,
                label = "滤镜强度",
                range = LutStrengthParam.range,
            )

            // ── 参数卡片：分段 + 重置 + 网格 + 底部滑杆 ────────────────────────
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(FilmTheme.PanelSolid),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FullParamTab.entries.forEach { t ->
                        SheetPill(t.title, selected = tab == t) {
                            // 切档 ⇒ 收起滑杆（选中的那格在新档里可能根本不存在）
                            if (tab != t) {
                                tab = t
                                selected = null
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "⟳ 重置",
                        color = if (canSave) FilmTheme.Cream else DisabledInk,
                        fontSize = 12.sp,
                        fontFamily = FilmTheme.Mono,
                        modifier = Modifier
                            .pressScale(enabled = canSave, onTap = {
                                val reset = resetFullParamOverrides(mine)
                                onOverrides(reset)
                                // 立刻下发：只清 state 不改画面的话，「重置」就是点了没反应
                                reset?.let { onPreview(it) }
                                // 滑杆内部持有拖动 state（key 不变不重读）⇒ 收起，重开即读新值
                                selected = null
                            })
                            .padding(vertical = 4.dp),
                    )
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    sections.forEach { sec ->
                        Text(
                            sec.title,
                            color = FilmTheme.CreamDim,
                            fontSize = 10.sp,
                            fontFamily = FilmTheme.Mono,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                        )
                        sec.rows.forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { cell ->
                                    FullParamGridCell(
                                        cell = cell,
                                        value = fullParamCellValue(cell, film),
                                        selectedCell = selected == cell.key,
                                        modifier = Modifier.weight(1f),
                                        onTap = { selected = if (selected == cell.key) null else cell.key },
                                    )
                                }
                                // 末行不满 ⇒ 补空位（否则最后一行的格子会被拉宽）
                                repeat(FULL_GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                // 底部：点中的那一项的滑杆（一次一项）或不可调的原因
                selectedCell?.let { cell ->
                    FullParamSliderBar(
                        cell = cell,
                        film = film,
                        included = paramIncludedInPreset(cell.param, included),
                        onCommit = { param, v ->
                            val next = overrides.afterEdit(preset, param, v)
                            drag.value = next
                            onPreview(next)
                        },
                        onFinished = {
                            drag.value?.let { onOverrides(it) }
                            drag.value = null
                        },
                    )
                }
            }
        }
    }
}

/** 不可用文字（与预设面板同一条观感：`CamDisabledInk` 是 private ⇒ 这里各写一行常量，不是逻辑复制）。 */
private val DisabledInk = FilmTheme.CreamDim.copy(alpha = 0.35f)

/**
 * 网格里的一格：**数值（大字）+ 参数名（小字）**，选中 ⇒ 强调色。
 *
 * 不可调（没有滑杆 / 拍照链路不读）⇒ 整格灰显，但**依然可点**：点它 ⇒ 底部给出原因
 * （ADR-12：不做假控件，也不做"点了没反应"）。
 */
@Composable
private fun FullParamGridCell(
    cell: FullParamCell,
    value: String,
    selectedCell: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val tunable = cell.param != null && cameraParamAdjustability(cell.slot) == CameraParamAdjustability.TUNABLE
    Column(
        modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selectedCell) FilmTheme.ChipBg else FilmTheme.PanelSolid)
            .pressScale(pressedScale = 0.94f, onTap = onTap)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            color = when {
                !tunable -> DisabledInk
                selectedCell -> FilmTheme.Accent
                else -> FilmTheme.Cream
            },
            fontSize = 14.sp,
            fontFamily = FilmTheme.Mono,
            fontWeight = if (selectedCell) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            cell.label,
            color = if (tunable) FilmTheme.CreamDim else DisabledInk,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 卡片底部的那一条（图 1 的 `● 曝光 0.0` + 刻度滑杆）：**一次一项**。
 *
 *  ① 可调 ⇒ 标题 + （这条预设没包含它时的说明）+ [EditorSlider]（量程 / 读数 / 中点标记全部
 *    来自 [EditorParam]）；
 *  ② 不可调 ⇒ 标题 + [cameraParamAdjustabilityNote] 的原因（**不画滑杆** —— 假滑杆的边界）。
 */
@Composable
private fun FullParamSliderBar(
    cell: FullParamCell,
    film: DoubleArray,
    included: Boolean,
    onCommit: (EditorParam, Float) -> Unit,
    onFinished: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(FilmTheme.ChipBg)
            .padding(vertical = 8.dp),
    ) {
        Text(
            "● ${cell.label}",
            color = FilmTheme.Cream,
            fontSize = 12.sp,
            fontFamily = FilmTheme.Mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        val p = cell.param
        if (p != null && cameraParamAdjustability(cell.slot) == CameraParamAdjustability.TUNABLE) {
            if (!included) {
                // 稀疏语义的可视化：这条预设没记这一项 ⇒ 套用它时该字段保持不动；拖一下才加入
                Text(
                    "这条预设未包含此项 · 拖动即加入（保存后写入预设）",
                    color = FilmTheme.CreamDim,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
                )
            }
            // 换项 ⇒ 重建（[EditorSlider] 用 remember 取初值）；拖动**不写 Compose state**（drag 直通）
            key(p.key) {
                EditorSlider(param = p, params = film, onCommit = onCommit, onFinished = onFinished)
            }
        } else {
            Text(
                cameraParamAdjustabilityNote(cell.slot) ?: "不可调",
                color = FilmTheme.CreamDim,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
            )
        }
    }
}
