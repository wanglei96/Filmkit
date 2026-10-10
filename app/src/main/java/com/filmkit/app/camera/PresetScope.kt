package com.filmkit.app.camera

/**
 * P-5：预设的**字段作用域** —— "这条预设的哪些参数在当前场景下不会生效"。
 *
 * 为什么需要（真实问题，不是洁癖）：引擎有**两条渲染链路**，它们读的槽位集合**不同** ——
 * 拍照 `passFilm`（+ 颗粒的 `passOutput`）/ 编辑器 `passStyle`。架构师用脚本抽取
 * `FrameRenderer.cpp` 三个 pass 的 `p.<字段>` 引用实测，结论 = `docs/reports/preset-DESIGN.md`
 * §1.6 的字段能力矩阵。实测后果：
 *  - 含 `[23][24]` 分离色调 / `[25]` 单色的预设，**在编辑器里套用不变黑白、不加分离色调**
 *    （★ U-1（2026-10-11）已补齐 ⇒ 这两组槽位现在两侧都生效，本表不再标记它们）；
 *  - 含 `[88][89]` 色温/色调的预设，**在拍照界面套用无效**
 *    （★ U-3（2026-10-11）已补齐 ⇒ 这一组现在两侧都生效，本表不再标记它们）；
 *  - 含 `[78]` 清晰度 / `[90..94]` 影调分区…的预设，**在拍照界面套用无效**；
 * ⇒ 用户套了预设"没反应"，只会以为是 bug。ADR-12 禁止假滑杆，同理禁止"假装生效"：
 * 本文件把作用域说清楚，三处入口（拍照 / 查看页「胶片」/ 编辑器）共用同一份判据。
 *
 * ★ **本文件是判据表的唯一维护点**（地位同 [FilmParamKeys]）：表 = §1.6 的能力矩阵，单测
 * `PresetScopeTest` 正则解析 §1.6 逐槽对拍（`EditorStyleWhitelistTest` 防"注释腐烂"的同一
 * 手法）⇒ 文档与代码不可能各自漂移。
 * ⚠ **若 §1.6 与代码实测冲突：以 §1.6 为准**（不在本文件自行改判据），并在报告里指出冲突。
 */

/**
 * 预设被套用的**场景**。判据是"引擎在这条链路上读不读某个槽位"，与 UI 在哪一页无关：
 *  - [CAMERA] = 拍照链路（`passFilm` / `passOutput`）—— 拍照界面「预设」列表：选中即生效于
 *    取景预览与成片；
 *  - [EDITOR] = 编辑器链路（`passStyle`）—— 编辑器预设面板，以及查看页「胶片」（P-3 裁决：
 *    选中后**直接进编辑器**看效果，两者是同一条链路）。
 */
internal enum class PresetScene { CAMERA, EDITOR }

/** 两条链路都读（§1.6 里两侧都是 ✅ / ⚠ 的槽位）。 */
private val BOTH_SCENES = setOf(PresetScene.CAMERA, PresetScene.EDITOR)

/** 只有拍照链路读。 */
private val CAMERA_ONLY = setOf(PresetScene.CAMERA)

/** 只有编辑器链路读。 */
private val EDITOR_ONLY = setOf(PresetScene.EDITOR)

/**
 * 槽位 → **生效场景**（**唯一维护点**；判据 = §1.6 的能力矩阵，逐行注明依据）。
 *
 * **不在表里的槽位 = 两条链路都生效** —— 表只记"有落点差异"的槽位，因为提示只在差异处出现
 * （§1.6 里两侧都 ✅ 的 `[0]` / `[1..3]` / `[4]` / `[20..22]` / `[26][27][60][61]` / `[66..68]`
 * 等因此不必逐条抄一遍）。
 *
 * 同样不在表里的还有两类，**都不是"场景差异"**：
 *  - `[62..65]`（光晕，等 E4-5）与 `[69..77]`（层间效应，等 E4-6）：**两侧都还没实现** ——
 *    换到哪条链路都没有落点（§1.6 的"进预设？"列标 ⏳ ⇒ 现在也进不了预设）；
 *  - `[28]`（基准影调开关）与 `[31..33]`（色彩外观层）：**根本进不了预设** ——
 *    [presetSlotIncluded] 按"引擎状态，不是用户风格"把它们排除（P-5 复核裁决；判据逐条写在
 *    `PRESET_ENGINE_KNOB_SLOTS` 的 KDoc 里）⇒ 作用域无从谈起。
 * 本表的语义是"**换个场景就会生效/失效**"，以上两类都不属于它。
 */
private val SLOT_SCENES: Map<Int, Set<PresetScene>> = buildMap {
    // ── 仅拍照链路（§1.6：`passFilm` 读、`passStyle` 零引用）──────────────────
    // [5..10] 趾肩 latitude（E4-4 已接线）：passFilm → updateFilmicEV → computeFilmicSpline
    // → kFsFilm 逐通道三段多项式。编辑器链路 kFsStyle 里**没有 filmic**（输入已是成片 sRGB
    // 显示域，重跑影调曲线没有域依据），且规格不新增曲线 ⇒ **永远无视觉效果**，不是"待接线"。
    for (s in 5..10) put(s, CAMERA_ONLY)
    // [23][24] 分离色调 / [25] 单色：★ U-1 已接线（kFsStyle 内与 kFsFilm 同式）⇒ **不在表里**
    //（= 两侧都生效）。留在这里会变成"死条目"：提示永远走不到，还会让下一个读表的人以为
    // 它们"仅拍照有效"（同 excludedSlotsHaveNoScope 要防的那类腐烂）。
    // [37][38] filmic 黑点 / 白点。（U-2 待补：编辑器侧无落点）
    for (s in 37..38) put(s, CAMERA_ONLY)

    // ── 仅编辑器链路（§1.6：`passStyle` 读、`passFilm` 零引用）────────────────
    // [78] 清晰度（显示域 USM，输入必须是已成片）。
    put(78, EDITOR_ONLY)
    // [88][89] 色温/色调：★ U-3 已接线（拍照链路 `wbTempTint` → `wbCompGNorm` +
    // `prepareRawCommon` 的两个调用点，与 passStyle 的 `wb_tot` 同一个 `tempTintGain`、
    // 同一次乘法）⇒ **不在表里**（= 两侧都生效）。留在这里会变成"死条目"：提示永远走
    // 不到，还会让下一个读表的人以为它们"仅后期有效"（同 U-1 的 [23..25] 与
    // excludedSlotsHaveNoScope 要防的那类腐烂）。
    // [90..94] 影调分区 + 褪色（display 域；拍照侧落点 = passOutput，U-4 才补）。
    for (s in 90..94) put(s, EDITOR_ONLY)
}

/**
 * 槽位在两条链路上的生效场景。不在 [SLOT_SCENES] 里 = 两侧都生效。
 *
 * 注意 **[34] LUT 强度不在表里也不该被提示**：它由 [EditorPreset.lutId] /
 * [EditorPreset.lutStrength] 承载，[presetSlotIncluded] 把它排除在 `slots` 之外（§1.6 同款
 * 结论）⇒ 正常路径下它不会出现在预设里，坏数据里出现了也不提示（不是"场景"问题）。
 * **[28] / [31..33]** 同理（P-5 复核后也被 [presetSlotIncluded] 排除）。
 */
internal fun presetSlotScenes(slot: Int): Set<PresetScene> = SLOT_SCENES[slot] ?: BOTH_SCENES

/**
 * 槽位 → 提示里用的**中文参数名**。名字**取自既有 UI 文案**（`EditorScreen` 的「调整 / 滤镜」
 * 参数滑杆 label、`SettingsScreen` 的「影调与色彩」条目），找不到既有文案的按项目文档的统一
 * 术语（见 `docs/reports/p5-preset-scope-REPORT.md` 的"名字来源"一表）。
 *
 * 同名槽位合并成一项（`[23]/[24]` 都是"分离色调"、`[5..10]` 都是"胶片曲线"）—— 提示里的
 * "N 项"按**去重后的名字数**数，与括号里列出的名字对得上（用户能数）。
 *
 * ⚠ `[5..10]` 用**"胶片曲线"**而不是工程术语"趾肩 latitude"（P-5 复核裁决：普通用户不认
 * "趾肩"）—— 它是 filmic 曲线两端的 latitude，UI 上没有滑杆，用户能感知的是"整条胶片曲线
 * 的形态"。
 */
private val SLOT_LABELS: List<Pair<IntRange, String>> = listOf(
    5..10 to "胶片曲线",      // [5..10] filmic latitude（趾肩；用户看得懂的说法，UI 上无滑杆）
    // ⚠ 曾经的 23..24「分离色调」/ 25..25「黑白」两行随 U-1 接线删除：[23..25] 现在
    // 两侧都生效 ⇒ 永远不进提示 ⇒ 留着就是死条目（同 excludedSlotsHaveNoScope 的判据）。
    37..37 to "黑点",          // [37] filmic_black_ev（SettingsScreen「黑点（多暗算黑）」）
    38..38 to "白点",          // [38] filmic_white_ev（SettingsScreen「白点（多亮算白）」）
    78..78 to "清晰度",        // [78] clarity（EditorScreen「调整」组 label）
    // ⚠ 曾经的 88..88「色温」/ 89..89「色调」两行随 U-3 接线删除：拍照链路接入
    // `wbTempTint` 后两侧都生效 ⇒ 永远不进提示 ⇒ 留着就是死条目（同 U-1 的判据）。
    90..90 to "高光",          // [90] highlights
    91..91 to "阴影",          // [91] shadows
    92..92 to "白色色阶",      // [92] whites
    93..93 to "黑色色阶",      // [93] blacks
    94..94 to "褪色",          // [94] fade（EditorScreen「滤镜」组 label）
)

/** 提示里最多列出的名字数（再多就换成"…"：一行放得下，且不淹没卡片副标题）。 */
private const val MAX_NAMES = 3

/**
 * 预设的**字段作用域提示**：当前场景下这条预设有哪些参数不会生效（人话一句）。
 *
 * 全部生效 ⇒ `null`（**UI 在 null 时不显示任何东西** —— 不挂"全部生效"这种每条都有的噪音）。
 * 例（`docs/reports/preset-DESIGN.md` §1.6 的两类真实后果）：
 *  - [PresetScene.CAMERA] + 含 `[78]/[88..94]` ⇒ `含 3 项仅后期参数（清晰度/色温/色调），拍照时不生效`
 *  - [PresetScene.EDITOR] + 含 `[5..10]/[37][38]` ⇒ `含 2 项仅拍照参数（胶片曲线/黑点），编辑器里不生效`
 *    （★ U-1 后 `[23..25]` 已两侧生效，不再是这条提示的实例）
 *
 * 这是**说明性文字**（设计使然，不是故障），UI 侧不要用错误/警告色，也不要弹窗打断操作。
 */
internal fun presetScopeNotice(preset: EditorPreset, scene: PresetScene): String? {
    val dead = preset.slots.keys.filter { scene !in presetSlotScenes(it) }.sorted()
    if (dead.isEmpty()) return null
    // 名字去重（保持槽位序）⇒ "项"数 = 名字数，与括号里列出的名字一一对应
    val names = dead.map { slotLabel(it) }.distinct()
    val shown = names.take(MAX_NAMES).joinToString("/") + if (names.size > MAX_NAMES) "…" else ""
    return when (scene) {
        PresetScene.CAMERA -> "含 ${names.size} 项仅后期参数（$shown），拍照时不生效"
        PresetScene.EDITOR -> "含 ${names.size} 项仅拍照参数（$shown），编辑器里不生效"
    }
}

/**
 * 名字查找。回落顺序 = 中文名表 → [FilmParamKeys] 语义名 → `槽N`：**绝不静默丢**（名字表漏了
 * 一项时，提示里会出现一个英文语义名 —— 难看但看得见，单测也会红）。
 */
private fun slotLabel(slot: Int): String =
    SLOT_LABELS.firstOrNull { slot in it.first }?.second
        ?: FilmParamKeys.bySlot[slot]
        ?: "槽$slot"
