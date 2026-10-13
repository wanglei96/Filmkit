package com.filmkit.app.ui

import com.filmkit.app.camera.BuiltinPresets
import com.filmkit.app.camera.CAMERA_STANDARD_PRESET
import com.filmkit.app.camera.EditorPreset
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ PS-8：预设界面重设计（用户 2026-10-12/13 真机 v0.11.23 原话）：
 *
 * > "**UI做的太丑了重新设计一下，预设改成横向滚动**。。
 * >  **第一级页面不需要提供全部参数，点击查看完整参数才提供**。。。
 * >  **一级页面系统返回按钮关闭预设页面，不要直接退出app**。。
 * >  **一级参数调节要能在预览看到实时效果**"
 *
 * 本文件盯**四件事**的机器判据（纯函数 + 源码哨兵；感官部分见报告 §未验证）：
 *  1. **横向条**（[presetStripItems] / [presetStripBadge] / [PRESET_STRIP_HEIGHT_DP]）：
 *     顺序（首项恒为「标准」）、选中唯一、小标记一行、高度 ≤ 72dp、条首有「＋ 新建预设」；
 *  2. **一级页精简**（[presetSubtitleText] + 源码哨兵）：只有预设条 / 名称行 / 常用参数三块
 *     （+ 有改动时的保存行），**不再有全部参数清单**（反向哨兵在 [Ps7PresetPanelLayoutTest]）；
 *  3. **返回键分层**（[CameraBackTarget] / [cameraBackTarget]）：二级页（完整参数）优先于一级面板，
 *     面板都没开 ⇒ 不拦（照旧退出 app）；编辑面板（子态）由面板内更晚注册的 BackHandler 接住；
 *  4. **视觉条目落地**：分段控件等宽 + 选中填充强调色、滑杆行 = 标签 │ 滑杆 │ 等宽数值、
 *     块间留白 12–16dp、不引入新色（只用 `FilmTheme` 既有 token）。
 */
class Ps8PresetUiRedesignTest {

    private val builtins = BuiltinPresets.parse(File("src/main/assets/presets/builtin.json").readText())
    private val panel = File("src/main/java/com/filmkit/app/ui/CameraPresetPanel.kt").readText()
    private val screen = File("src/main/java/com/filmkit/app/ui/CameraScreen.kt").readText()

    private fun builtin(name: String): EditorPreset =
        builtins.firstOrNull { it.name == name } ?: error("内置包里没有「$name」")

    private fun userPreset(id: String, name: String = "我的预设", group: String = "") =
        EditorPreset(id = id, name = name, group = group)

    // ── ① 横向预设条：顺序 / 选中 / 小标记 ────────────────────────────────────

    /**
     * 条的顺序 = **列表契约一位不改**（首项恒为内置「标准」，其后 = 用户预设 + 内置三条）；
     * 选中态**有且只有一个**（跟着 `selected.id` 走 —— 换预设 ⇒ 高亮跟着走）。
     */
    @Test
    fun stripKeepsTheListContractAndMarksExactlyOneSelected() {
        val users = listOf(userPreset("p_1", "我的 1"), userPreset("p_2", "我的 2"))
        val items = presetStripItems(users + builtins, selectedId = "p_1", builtinOverrides = emptyMap())
        assertEquals(
            "首项恒为「标准」（P-2a 契约），其后 = 用户预设 + 内置三条",
            listOf(
                "builtin_standard", "p_1", "p_2",
                "builtin_leica_m9", "builtin_ricoh_negative", "builtin_leica_bw",
            ),
            items.map { it.preset.id },
        )
        assertEquals("标准", items.first().preset.name)
        assertEquals("选中态有且只有一个", 1, items.count { it.selected })
        assertEquals("选中的就是 selectedId 那条", "p_1", items.first { it.selected }.preset.id)
        // 没有用户预设（调用侧只给内置三条）⇒ 条里仍有「标准」+ 内置三条（不会被清空）
        assertEquals(
            4,
            presetStripItems(builtins, CAMERA_STANDARD_PRESET.id, emptyMap()).size,
        )
        assertTrue(
            "没选中任何一条（坏 id）⇒ 一条都不高亮，绝不默认点亮",
            presetStripItems(users + builtins, "nope", emptyMap()).none { it.selected },
        )
    }

    /**
     * 小标记 = **一行**：`内置`（判据 = `presetDeletable`，不另写 source 比较）/ `已改` / 分组名。
     * 用户预设没有分组 ⇒ **null**（卡片上那一行只留 `⋯`，不写废话）。
     */
    @Test
    fun stripBadgeCarriesBuiltinEditedAndGroupOnOneLine() {
        assertNull("一条纯用户预设没有可说的标记", presetStripBadge(userPreset("p_1"), edited = false))
        assertEquals("有分组 ⇒ 分组名", "街拍", presetStripBadge(userPreset("p_2", group = "街拍"), edited = false))
        assertEquals(
            "内置（带分组）⇒ 内置 · 分组",
            "内置 · 徕卡",
            presetStripBadge(builtin("徕卡一瞬"), edited = false),
        )
        assertEquals(
            "内置且被改过 ⇒ 多一个「已改」（用户要知道这条已不是出厂值）",
            "内置 · 已改 · 徕卡",
            presetStripBadge(builtin("徕卡一瞬"), edited = true),
        )
        assertEquals(
            "「已改」只对内置出现（用户预设本来就是自己改的）",
            "街拍",
            presetStripBadge(userPreset("p_3", group = "街拍"), edited = true),
        )
        // 内置三条的小标记都不为空（用户要能一眼分出厂 / 自己的）
        for (b in builtins) assertTrue("内置「${b.name}」应有小标记", !presetStripBadge(b, false).isNullOrEmpty())
    }

    /**
     * 「＋ 新建预设」= 条里的**第一项**（用户点名的常驻入口），且卡片等宽（对齐）：
     * 高度 [PRESET_STRIP_HEIGHT_DP] 是"克制"口径的唯一实现点。
     */
    @Test
    fun stripIsHorizontalBoundedInHeightAndStartsWithTheNewPresetEntry() {
        assertTrue("横向条高度要克制（用户：≤ 72dp 量级）", PRESET_STRIP_HEIGHT_DP <= 72f)
        assertEquals(64f, PRESET_STRIP_HEIGHT_DP, 1e-4f)
        assertTrue("卡片等宽（对齐）", PRESET_STRIP_CARD_WIDTH_DP >= 96f)
        val strip = panel.substringAfter("private fun PresetStrip(").substringBefore("private fun PresetNewCard(")
        assertTrue("横向条 = LazyRow（横向滚动）", strip.contains("LazyRow("))
        assertTrue("条高真的读了 PRESET_STRIP_HEIGHT_DP（不是写了常量没人用）", strip.contains("height(PRESET_STRIP_HEIGHT_DP.dp)"))
        assertTrue("首项 = 「＋ 新建预设」", strip.contains("item(key = \"preset_new\") { PresetNewCard(onNew) }"))
        assertTrue(
            "卡片等宽（宽度常量用在卡片上）",
            panel.contains("width(PRESET_STRIP_CARD_WIDTH_DP.dp)"),
        )
        assertTrue("卡片双向能力：点 = 套用 / 长按 = 详情", panel.contains("onTap = onApply, onLongTap = onDetail"))
        assertTrue("卡片上的「⋯」= 既有操作菜单", panel.contains(".pressScale(pressedScale = 0.94f, onTap = onMenu)"))
        assertFalse("预设面板里不许再有纵向 LazyColumn（横向条取代了它）", panel.contains("LazyColumn("))
    }

    // ── ② 一级页精简：一行副标题（可选）────────────────────────────────────────

    /**
     * 副标题 = 一行真实信息（分组 / 滤镜）；**没有真实信息 ⇒ null ⇒ 整行不渲染**。
     * 来源（内置 / 用户）不进这一行（名称行的「内置」标记已经说了）；参数项数也不进
     * （"全部参数"归「完整参数 >」）。
     */
    @Test
    fun subtitleIsOneLineAndDisappearsWhenThereIsNothingToSay() {
        val p = builtin("徕卡一瞬").copy(lutId = "leica_m9")
        val t = presetSubtitleText(p, "徕卡 M9")!!
        assertEquals("分组「徕卡」 · 滤镜 徕卡 M9", t)
        assertFalse("来源不在副标题里（名称行的「内置」标记承担）", t.contains("预设"))
        assertFalse("参数项数不许进一级页（量的展示归「完整参数 >」）", t.contains("项参数"))
        assertNull("没有分组、没有滤镜 ⇒ 整行不渲染（不写废话）", presetSubtitleText(userPreset("p_1")))
        assertEquals(
            "只有分组 ⇒ 只报分组",
            "分组「街拍」",
            presetSubtitleText(userPreset("p_2", group = "街拍")),
        )
        assertEquals(
            "有 LUT 但查不到中文名 ⇒ 回落 id（资产被删的降级路径），不是空串",
            "滤镜 leica_m9",
            presetSubtitleText(EditorPreset(id = "p_3", name = "n", lutId = "leica_m9")),
        )
        // 源码接线：这一行真的渲染在一级页（不是只有纯函数没有界面）
        val main = firstLevelBlock()
        assertTrue("副标题必须接在一级页上", main.contains("presetSubtitleText(selected"))
    }

    // ── ③ 返回键分层 ──────────────────────────────────────────────────────────

    /**
     * 用户："**一级页面系统返回按钮关闭预设页面，不要直接退出 app**"。
     *
     * 判据 = 关**最上层**：完整参数页（二级）→ 快捷菜单 → LUT / 预设面板（一级）。
     * 都没开 ⇒ `null` ⇒ **不拦**（面板没开时返回键仍是退出 app，一位不改）。
     */
    @Test
    fun backKeyClosesTheInnermostLayerFirstAndNeverSwallowsTheLastBack() {
        assertNull("面板都没开 ⇒ 不拦返回（照旧退出 app）", cameraBackTarget(false, false, false))
        assertEquals(
            "★ 二级页（完整参数）优先于一级面板：先关它，再按才关一级",
            CameraBackTarget.FULL_EDIT,
            cameraBackTarget(fullEditOpen = true, quickOpen = false, lutOpen = true),
        )
        assertEquals(
            "全开 ⇒ 关最上层（完整参数页）",
            CameraBackTarget.FULL_EDIT,
            cameraBackTarget(true, true, true),
        )
        assertEquals("快捷菜单在 LUT 面板之上（声明顺序决定）", CameraBackTarget.QUICK_MENU, cameraBackTarget(false, true, true))
        assertEquals("只剩一级面板 ⇒ 关它", CameraBackTarget.LUT_SHEET, cameraBackTarget(false, false, true))
        assertEquals("三态之外不许有第四态", 3, CameraBackTarget.entries.size)
    }

    /** 源码哨兵：`CameraScreen` 真的挂了 `BackHandler`，且编辑面板（一级页子态）有它自己那一层。 */
    @Test
    fun screenWiresTheBackHandlerAndThePanelClosesItsOwnSubState() {
        assertTrue(
            "★ CameraScreen 必须有 BackHandler —— 少了它，面板打开时按返回直接退出 app（用户报的真 bug）",
            screen.contains("BackHandler(enabled = fullEditOpen || quickOpen || lutOpen)"),
        )
        assertTrue(
            "分层必须走纯函数 cameraBackTarget（优先级不许在 UI 里手写第二份）",
            screen.contains("cameraBackTarget(fullEditOpen, quickOpen, lutOpen)"),
        )
        assertTrue(
            "关二级页的分支必须存在",
            screen.contains("CameraBackTarget.FULL_EDIT -> fullEditOpen = false"),
        )
        assertTrue(
            "关一级面板的分支必须存在",
            screen.contains("CameraBackTarget.LUT_SHEET -> lutOpen = false"),
        )
        assertTrue(
            "★ 预设编辑面板（一级页子态）：返回先关它（注册更晚 ⇒ 更优先，两级不打架）",
            panel.contains("BackHandler(enabled = editorOpen) { closeEditor() }"),
        )
    }

    // ── ④ 视觉条目落地（"太丑"要落到可执行的条目）────────────────────────────

    /** 一级页 = 三个视觉块（横向条 / 名称行 / 常用参数）+ 有改动时的保存行；没有整页滚动。 */
    @Test
    fun firstLevelIsThreeBlocksPlusTheSaveRowAndNeverScrollsAsAWhole() {
        val main = firstLevelBlock()
        for (token in listOf(
            "PresetStrip(",                       // ① 横向预设条
            "\"完整参数 >\"",                      // ② 名称行右侧的唯一入口
            "PresetQuickAdjustPanel(",            // ③ 常用参数
            "if (mine != null && !mine.isEmpty)", // ④ 有改动才出现的保存行
        )) {
            assertTrue("一级页少了「$token」", main.contains(token))
        }
        assertTrue("名称行 = 18sp 衬线半粗（信息层次第一段）", main.contains("fontSize = 18.sp") && main.contains("fontFamily = FilmTheme.Serif"))
        assertFalse(
            "★ 一级页不许再有整页 verticalScroll（用户上一轮骂的「点了没反应」就是内容在滚动区屏幕外）",
            main.contains("verticalScroll("),
        )
        assertTrue("块间留白 = 统一常量（不是各写各的 dp）", main.contains("PRESET_BLOCK_GAP_DP.dp"))
        assertTrue("留白在 16–24dp 之间", PRESET_BLOCK_GAP_DP in 16f..24f)
    }

    /**
     * 常用参数区（用户点名的 5 项 + ★ PS-10 的 filmic 白点）：**能力与口径一字未改**，视觉换成
     * ① 三行滑杆（LUT 强度 + ★ PS-9 的颗粒强度 + ★ PS-10 的白点，标签 │ 滑杆 │ 等宽数值）
     * + ② 三行**分段控件**（等宽均分、选中填充强调色）。
     */
    @Test
    fun quickAdjustKeepsTheFiveItemsAndRendersSegmentsAndAMonoValueRow() {
        assertEquals(
            "用户点名的 5 项一个不少（LUT 强度 / 颗粒强度 / 柔光 / 光晕 / 暗角）+ ★ PS-10 的白点",
            linkedSetOf(34, 26, 95, 62, 66, 38),
            QUICK_ADJUST_SLOTS,
        )
        val quick = panel.substringAfter("private fun PresetQuickAdjustPanel(").substringBefore("private fun PresetParamLabelRow(")
        assertTrue("LUT 强度 = 一行滑杆", quick.contains("PresetParamSliderRow("))
        assertTrue("LUT 强度读的是 lutStrength（`[34]` 不进 slots）", quick.contains("it[LUT_STRENGTH_SLOT] = lutStrength.toDouble()"))
        // ★ PS-9：颗粒从四档分段控件改成**强度滑杆**（只写 `[26]`；`[27]` 保持原值）
        assertTrue("★ 颗粒 = 强度滑杆（与 LUT 强度同一套视觉）", quick.contains("param = GrainStrengthParam"))
        assertFalse("★ 颗粒不再是分段控件", quick.contains("GrainLevel.entries"))
        // ★ PS-10：白点 = 滑杆，参数项复用 ParamControls.kt 那一条（三个页面同一个数）
        assertTrue(
            "★ PS-10 白点 = 强度滑杆（复用参数表的 EditorParam，不是第二份）",
            quick.contains("param = CAMERA_TUNABLE_SLOT_PARAM.getValue(FILMIC_WHITE_EV_SLOT)"),
        )
        assertTrue("柔光 / 光晕 / 暗角 = 三行档位", quick.contains("for (q in QUICK_LEVEL_PARAMS)"))
        assertTrue("档位走 onPickLevel（点一下写值 + 立即提交）", quick.contains("onPickLevel(param, q.levels[i])"))
        assertTrue("没有 LUT ⇒ 不给假滑杆，只写一行实话", quick.contains("PresetParamLabelRow("))

        // 分段控件：等宽 + 选中填充强调色 + 不再是散落的 chip / 横向滚动
        val seg = panel.substringAfter("private fun PresetLevelSegments(").substringBefore("private fun PresetStrip(")
        assertTrue("四段都渲染（forEachIndexed）", seg.contains("names.forEachIndexed"))
        assertTrue("每段等宽均分（weight(1f)）", seg.contains(".weight(1f)"))
        assertTrue("选中段填充强调色", seg.contains("background(if (on) FilmTheme.Accent else Color.Transparent)"))
        assertTrue("选中段文字用 OnAccent（对比度）", seg.contains("if (on) FilmTheme.OnAccent else FilmTheme.CreamDim"))
        assertFalse("档位不再横向滚动（chip 形态已废弃）", seg.contains("horizontalScroll("))
        assertTrue("档位名过长 ⇒ 省略号（不换行、不挤爆）", seg.contains("overflow = TextOverflow.Ellipsis"))

        // 一行滑杆：标签 │ 滑杆 │ 等宽数值（跨行对齐）
        val row = panel.substringAfter("private fun PresetParamSliderRow(").substringBefore("private fun PresetLevelSegments(")
        assertTrue("复用 ParamControls 的参数语义（量程 / 读数 / write 都在 EditorParam 上）", row.contains("param: EditorParam"))
        assertTrue("滑杆复用既有 RelativeSlider", row.contains("RelativeSlider("))
        assertTrue("数值 = 该参数自己的 format（不是第二份读数）", row.contains("param.format(v)"))
        assertTrue("数值列等宽 + 右对齐 ⇒ 跨行对齐", row.contains("fontFamily = FilmTheme.Mono") && row.contains("textAlign = TextAlign.End"))
        assertTrue("标签 / 数值列宽都是常量（不是散落的魔数）", row.contains("width(PRESET_LABEL_WIDTH_DP.dp)") && row.contains("width(PRESET_VALUE_WIDTH_DP.dp)"))
        assertTrue("拖动只重组这一行（state 在本组件内）", row.contains("mutableFloatStateOf(param.read(params))"))
        assertTrue("松手才提交（onFinished）", row.contains("onDragEnd = { onFinished() }"))
    }

    /** 颜色只用现有一套（`FilmTheme` 的 token）—— 一级页一个硬编码色值都不许有。 */
    @Test
    fun redesignIntroducesNoNewColors() {
        for (bad in listOf("Color(0x", "Color.Red", "Color.White", "Color.Black", "Color.Gray")) {
            assertFalse("不许引入新色（只用 FilmTheme 既有 token）：$bad", panel.contains(bad))
        }
        for (token in listOf("FilmTheme.Accent", "FilmTheme.ChipBg", "FilmTheme.ChipBorder", "FilmTheme.OnAccent", "FilmTheme.CreamDim")) {
            assertTrue("既有 token 要被复用：$token", panel.contains(token))
        }
    }

    /** 一级页（非编辑态）那一段源码：从 `} else {` 到「⋯」菜单之前。 */
    private fun firstLevelBlock(): String =
        panel.substringAfter("    } else {\n").substringBefore("    // 「⋯」操作菜单（显式入口）")
}
