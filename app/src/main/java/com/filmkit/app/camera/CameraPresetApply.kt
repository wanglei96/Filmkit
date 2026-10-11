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
 * ★ PS-3：**拍照链路（`passFilm` + `passOutput`）实际读取的槽位集合**（41 槽）——
 * 拍照界面"哪些参数调了真的有效果"的**唯一判据**。
 *
 * 来源 = 引擎实测：`FrameRenderer.cpp` 的 `p.<字段>` 引用（排除编辑器链路函数
 * `passStyle` / `submitDisplay` 的函数体）∩ `presetSlotIncluded`。抽取口径 / 盲区逐条写在
 * `CameraStyleWhitelistTest` 的类 KDoc 里，那份测试**直接引用本常量**做三方对拍
 * （引擎实际 == 本常量 == `preset-DESIGN.md` §1.6 的「拍照链路」列）⇒ 三处不可能各自漂移。
 *
 * 为什么要有这个常量（PS-3 的实际用途）：拍照界面的预设详情允许用户**逐项拖滑杆**，
 * 而"能拖"必须等于"拖了画面会变"。引擎不读的槽（例如 `[69..77]` 层间效应）拖了没有任何
 * 效果 —— ADR-12 明令禁止假滑杆 ⇒ 那些项在拍照界面**灰显 + 标注原因**，判据就是本集合。
 *
 * ⚠ **不含** `[28]`（基准影调）/ `[30]`（过曝提示）/ `[31..33]`（色彩外观层）/ `[34]`（LUT
 * 强度，由 `lut.strength` 承载）/ `[35][36]`（引擎标定）：`presetSlotIncluded` 把它们排除在
 * 预设之外 ⇒ 与"预设作用域"无关，不在本集合的定义域内（同 `CameraStyleWhitelistTest`）。
 */
internal val CAMERA_PATH_SLOTS: Set<Int> = buildSet {
    add(0)                  // 曝光（**相对偏移**语义，见 presetEvOffset）
    addAll(1..3)            // 白平衡 R/G/B 增益
    add(4)                  // 对比度（U-3 接线）
    addAll(5..10)           // [5..7] shoulder[3] / [8..10] toe[3]（胶片曲线两端 latitude）
    addAll(20..22)          // 分区饱和三档
    addAll(23..25)          // 分离色调 + 单色
    add(26); add(27); add(60); add(61)   // 颗粒（强度 / 尺寸 / 中间调偏置 / 枢轴）
    add(37); add(38)        // filmic 黑白点
    addAll(62..65)          // 光晕（E4-5）
    addAll(66..68)          // 暗角（E4-2）
    add(78)                 // 清晰度（U-4）
    add(88); add(89)        // 色温 / 色调（U-3）
    addAll(90..94)          // 影调分区四条 + 褪色（U-4）
    addAll(95..97)          // 柔光（SG-1）
}

/**
 * 套用到相机的**完整 96 槽数组** = `标准基线 + 预设的稀疏覆盖`。
 *
 * 为什么底是 [CameraPresets.standard] 而不是"当前参数"（编辑器侧走的是后者）：拍照界面没有
 * 风格参数滑杆，参数数组的唯一来源就是预设 —— 从标准基线重建才是"套用预设 = 完整风格"。
 * 用户在拍照界面调的曝光补偿 / 斑马纹 / 影调开关由 `CameraController.setFilmParams` 内部
 * 的 prefs 覆盖层（`[0]/[28]/[30]/[34]/[35]`）重新写回，**不经过本函数**（既有设计）。
 *
 * ★ **U-7：`[0]` 是唯一例外** —— 覆盖层不再把它整槽丢掉，而是转成**相对偏移**叠加在用户的
 * 曝光补偿上（方案 B）：调用侧用 [presetEvOffset]（或带用户调整的
 * [effectivePresetEvOffset]）把这条预设的 `[0]` 提取出来交给 `setFilmParams(p, evOffset)`，
 * `[0] = base_ev + 用户 EV + 该偏移`。
 *
 * 展开本身复用编辑器侧的 [presetSnapshot]（"缺失 key = 保留 base 值"的唯一实现）。
 *
 * ★ **PS-3：`[0]` 的调整必须走 `evOffset` 参数，不能只写进返回数组** —— `setFilmParams`
 * 随即把 `p[0]` 整槽覆盖成 `base + userEv + evOffset`（`CameraController.kt` 的
 * `filmParams[0] = exposureEv()`）⇒ 只写数组的 `[0]` 会被静默丢掉。所以带 overrides 的
 * 调用点必须**同时**用 [effectivePresetEvOffset] 算第二个实参。
 *
 * @param overrides ★ PS-3：用户在预设详情面板里**临时**拖出来的值（未保存）—— 优先于预设记录
 * @param removed ★ PS-3：用户从这条预设里**移除**的字段（"未设置"的反向操作）—— 先摘掉它们，
 *   等价于"这条预设不含该字段"（套用时保持不动）。同一个槽同时出现在两个集合里 ⇒ overrides 胜
 *   （用户刚设了值 = 撤销那次移除）。
 */
internal fun cameraPresetParams(
    preset: EditorPreset,
    overrides: Map<Int, Double> = emptyMap(),
    removed: Set<Int> = emptySet(),
): DoubleArray =
    presetSnapshot(
        CameraPresets.standard(),
        run {
            val base = if (removed.isEmpty()) preset.slots else preset.slots - removed
            if (overrides.isEmpty()) base else base + overrides
        },
    )

/**
 * U-7：预设曝光 `[0]` 的**偏移量**（方案 B，`preset-DESIGN.md` §9.4；用户 2026-10-11 拍板）。
 *
 * 语义统一（§9.2 第 1 行唯一一个天然冲突的参数）：
 *  - **拍照侧**：`[0] = 用户基准曝光 + 本偏移 + 其它修正`（见 [effectiveExposureEv]）——
 *    拍前就能看到预设的曝光效果，又不冲掉用户正在调的曝光补偿；
 *  - **编辑器侧**：`[0]` 仍是**后期曝光**（直接生效）—— 它作用在已成片上，没有"用户的基准"
 *    可叠加；两边的**含义**因此一致（都是"相对当前画面再亮/暗多少 EV"），只是叠加对象不同。
 *
 * 为什么从 `slots` 取而不是从 [cameraPresetParams] 展开后数组的 `[0]` 取：展开时未记入的槽
 * 取基线值（中性基线的 `[0]` = 0.0），两者当前等价；但 `slots` 是**预设自己的记录**，不随
 * 基线的将来改动漂移，也不受 `setFilmParams` 的引用赋值（ADR §5.1 I-5）影响。
 *
 * **不含 `[0]`（缺失 key）⇒ 偏移 0**：与"缺失字段 = 不改变该参数"的既有口径一致，不是
 * "把曝光打到 0"。[CAMERA_STANDARD_PRESET] 的 slots 为空 ⇒ 切回「标准」必然归零，
 * 不残留上一条预设的曝光。
 */
internal fun presetEvOffset(slots: Map<Int, Double>): Double = slots[0] ?: 0.0

/**
 * ★ PS-3：**当前生效的**预设曝光偏移 = 用户在预设详情里拖过的值优先，否则预设自己的记录。
 *
 * 语义（与 [presetEvOffset] 同一件事，多一层"临时调整"）：
 *  - `overrides[0]` 存在 ⇒ 用户正在详情面板里调"这条预设的曝光偏移"，**它就是这个偏移的
 *    当前值**（绝对值，不是增量）⇒ 直接作为 `setFilmParams` 的 `evOffset`；
 *  - 否则回落 [presetEvOffset]`(slots)`（缺 key ⇒ 0.0，与"缺失字段 = 不改变该参数"同口径）。
 *
 * ⚠ **用户自己的 EV 补偿 / 基准曝光不在这个值里**：那两项活在 `CameraController` 内部
 * （`baseEvPref` / `evSteps`），由 `effectiveExposureEv` 在引擎侧叠加。本函数只回答
 * "这条预设要额外偏移多少 EV" ⇒ 保存回预设时写进 `slots[0]` 的也正是这个数
 *（[cameraPresetSlotsFor]），**不会**把拍摄态混进风格（U-7 的纪律不变）。
 */
internal fun effectivePresetEvOffset(slots: Map<Int, Double>, overrides: Map<Int, Double>): Double =
    overrides[0] ?: presetEvOffset(slots)

/**
 * U-7：拍照侧 `[0]` 的**唯一算法** = 基准曝光 + 用户 EV + 预设偏移（方案 B 的"叠加而非覆盖"）。
 *
 * [CameraController] 的三处 `[0]` 计算全部走这里，一处都不能漏：
 *  ① 套用预设（`setFilmParams`）—— 预设偏移的注入点；
 *  ② 用户拖曝光补偿（`setEv`）—— 偏移**保留**（用户调的是自己的基准，预设叠在上面）；
 *  ③ 高光保护判据的"将要施加的提亮"（`hlCompEv`）—— 漏了偏移就少算预设的提亮 ⇒ 成片高光裁切。
 *
 * [presetOffset] == 0（标准 / 不含曝光的预设）时逐位等于 v0.8.1 以来的旧行为
 * `baseEv + userEv` ⇒ 没有预设曝光的场景一个数值都不会变。
 */
internal fun effectiveExposureEv(baseEv: Double, userEv: Double, presetOffset: Double): Double =
    baseEv + userEv + presetOffset

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
