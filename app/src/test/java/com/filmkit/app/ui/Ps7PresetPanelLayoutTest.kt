package com.filmkit.app.ui

import com.filmkit.app.camera.CAMERA_PATH_SLOTS
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ PS-7：预设面板显示崩坏的护栏（用户 2026-10-12 真机截图两轮，原话"**你做这一坨屎是什么**" /
 * "**这个界面的参数都动不了，要进完整参数才能调节？那我要这个界面干嘛？**"）。
 *
 * 盯的是**从截图读出来的五个缺陷**在代码层面不再复发（判据 = 纯函数 + 源码哨兵，
 * 与仓库里 PS-6a 的 `CameraPresetMainViewTest` / `CameraPresetPanelUiTest` 同一套做法）：
 *
 *  1. 面板半透明 ⇒ 取景界面的拍摄参数行透过来混叠（`LutSheet` 必须不透明底）；
 *  2. 用户点名的**常用参数 5 项**被挤出首屏（详情卡不再整卡滚动，常用参数在清单之前）；
 *  3. 副标题与描述**重复**（描述已缩成一行真实信息，无信息 ⇒ 不显示）；
 *  4. 描述是**机器拼接**（`包含 N 项参数（分组 / …）`这类拼接已删）；
 *  5. 「当前搭配」的**值位混入内部标注**（值位只放真实值，说明走行下小字）；
 *  ★ 追加：**每一项都有可见反应**，且"点了才出现"的滑杆渲染在**滚动区之外**的固定槽。
 */
class Ps7PresetPanelLayoutTest {

    private val panel = File("src/main/java/com/filmkit/app/ui/CameraPresetPanel.kt").readText()
    private val screen = File("src/main/java/com/filmkit/app/ui/CameraScreen.kt").readText()

    /** `LutSheet` 函数体（锚点 = 它自己的签名与紧邻的下一个顶层 Composable）。 */
    private val lutSheet =
        screen.substringAfter("private fun LutSheet(").substringBefore("internal fun RelativeSlider(")

    /** `CameraPresetDetailCard` 函数体（锚点 = 它自己的签名与紧邻的下一个 Composable）。 */
    private val detailCard =
        panel.substringAfter("private fun CameraPresetDetailCard(").substringBefore("private fun PresetOverviewRowText(")

    // ── ① 杜绝透视：面板与详情卡都不许用半透明底 ──────────────────────────────

    /**
     * 截图缺陷 1：能看见取景界面的 `1/60 1539 29cm 290` 与 `EV Tv ISO AF WB` 透过面板与本页
     * 内容混叠。根因 = `FilmTheme.Panel`（`0xE6141414`，90% alpha）；`FilmTheme.PanelSolid`
     * （`0xFF141414`）就是为"半透明会透出下层"准备的既有常量。
     *
     * 判据：`LutSheet` 用 [FilmTheme.PanelSolid]；预设面板这一层不出现半透明 `FilmTheme.Panel`。
     */
    @Test
    fun lutSheetUsesTheOpaquePanelSoTheViewfinderTextCannotBleedThrough() {
        assertTrue(
            "★ LutSheet 必须改成不透明底（半透明 ⇒ 取景界面的拍摄参数行透过来）",
            lutSheet.contains("background(FilmTheme.PanelSolid)"),
        )
        assertFalse(
            "LutSheet 里不许再有半透明底（90% alpha 会让下层文字透上来）",
            lutSheet.contains("background(FilmTheme.Panel)"),
        )
        assertFalse(
            "预设面板（详情卡所在这一层）也不许引用半透明 Panel —— 它继承宿主的 PanelSolid",
            panel.contains("FilmTheme.Panel)"),
        )
    }

    // ── ② 常用参数首屏可见 + 卡片不整卡滚动 ─────────────────────────────────

    /**
     * 截图缺陷 2：详情卡 `heightIn(262.dp)` + `verticalScroll`，内容远超 ⇒ 常用参数（用户上一轮
     * 点名要的 5 项）被挤出首屏，"必须滚动才看得到"。
     *
     * 判据 = **渲染顺序**（名称 → 副标题 → 一行描述 → **常用参数** → 清单标题 → 滑杆 → 清单 →
     * 提示行）+ **整卡不滚动**（唯一的 `verticalScroll` 挂在清单区自己身上）。
     */
    @Test
    fun detailCardRendersQuickAdjustBeforeTheOverviewListAndNeverScrollsAsAWhole() {
        val order = listOf(
            "presetSourceText(p.source)}预设" to "副标题（一行）",
            "presetOverviewText(p, lutLabel)?.let" to "一行描述（null ⇒ 不渲染）",
            "PresetQuickAdjustPanel(" to "★ 常用参数 5 项（首屏固定区）",
            "\"当前搭配\"" to "「当前搭配」标题行",
            "pickedTap == PresetRowTap.SLIDER" to "选中项滑杆（固定槽）",
            "presetOverviewMaxHeightDp(rows.size, overviewExpanded)" to "清单区（限高）",
            "\"选择一项开始调整\"" to "提示行",
        )
        var prev = -1
        for ((token, what) in order) {
            val at = detailCard.indexOf(token)
            assertTrue("详情卡少了 $what（token = $token）", at >= 0)
            assertTrue("详情卡的 $what 顺序不对（用户要求常用参数在清单之前、提示行在最后）", at > prev)
            prev = at
        }
        // 整卡不滚动：卡片体内 `verticalScroll(` 只允许出现一次，且 = 清单区那一条
        assertEquals(
            "详情卡只允许清单区滚动（整卡 verticalScroll 会把'点了才出现'的滑杆推到屏幕外 = " +
                "用户感知为'点了没反应'）",
            1,
            Regex("""verticalScroll\(""").findAll(detailCard).count(),
        )
        assertTrue(
            "唯一的滚动必须挂在清单区自己身上（同一 Modifier 链：限高 + 内部滚动）",
            detailCard.substringAfter("presetOverviewMaxHeightDp(rows.size, overviewExpanded)")
                .take(300)
                .contains("verticalScroll("),
        )
    }

    // ── ③ 清单限高 + 展开（纯函数）──────────────────────────────────────────

    /** 截图缺陷 5 / 追加要求：清单**限高**（3 行 ↔ 8 行），别把卡片撑爆，也别让列表被挤没。 */
    @Test
    fun overviewListIsHeightCappedAndExpandable() {
        assertEquals("收起 = 3 行", 3, presetOverviewShownRows(17, false))
        assertEquals("展开 = 8 行上限", 8, presetOverviewShownRows(17, true))
        assertEquals("行数不到上限 ⇒ 按实际行数（不占空位）", 2, presetOverviewShownRows(2, false))
        assertEquals("空清单 ⇒ 0 行", 0, presetOverviewShownRows(0, true))
        assertEquals("负数（坏输入）⇒ 0 行", 0, presetOverviewShownRows(-5, false))
        assertEquals(72f, presetOverviewMaxHeightDp(17, false), 1e-4f)
        assertEquals(192f, presetOverviewMaxHeightDp(17, true), 1e-4f)
        assertEquals("空清单不占位", 0f, presetOverviewMaxHeightDp(0, true), 1e-4f)
        assertTrue(
            "界面上必须有「展开 / 收起」（超过 3 行才出现）",
            detailCard.contains("if (rows.size > PRESET_OVERVIEW_ROWS_COLLAPSED)") &&
                detailCard.contains("展开 ") &&
                detailCard.contains("\"收起\""),
        )
    }

    // ── ④ 面板总限高重新分配（详情 + 列表同屏）──────────────────────────────

    /**
     * 截图缺陷 2 + 硬约束「别让列表被挤没，也别让常用参数被挤走」：详情卡与预设列表共用一份
     * **恒定**预算 —— 展开清单只是两者此消彼长，列表不会被挤出屏幕。
     */
    @Test
    fun detailAndListShareOneBudgetSoNeitherIsSqueezedOut() {
        val collapsed = presetPanelBudget(false)
        val expanded = presetPanelBudget(true)
        assertEquals(
            "详情 + 列表 = 恒定预算（否则总高随展开漂移，窄屏上列表会被挤出屏幕）",
            PRESET_PANEL_BUDGET_DP,
            collapsed.detailDp + collapsed.listDp,
            1e-4f,
        )
        assertEquals(
            PRESET_PANEL_BUDGET_DP,
            expanded.detailDp + expanded.listDp,
            1e-4f,
        )
        assertTrue("展开清单 ⇒ 详情多给", expanded.detailDp > collapsed.detailDp)
        assertTrue("展开清单 ⇒ 列表让位", expanded.listDp < collapsed.listDp)
        assertTrue("列表不许被挤没（至少两行可见，且自身可滚）", expanded.listDp >= 100f)
        assertTrue(
            "详情预算要装得下名称 / 副标题 / 常用参数（含强度滑杆）/ 清单 3 行 / 提示行",
            collapsed.detailDp >= 460f,
        )
        // 源码接线：列表的限高真的读这份预算（不是写了函数没人用）
        assertTrue(
            "预设列表的限高必须来自 presetPanelBudget（否则两个数会各自漂移）",
            panel.contains("heightIn(max = presetPanelBudget(overviewExpanded).listDp.dp)"),
        )
    }

    // ── ④b 值位只放真实值（截图缺陷 5：`分离色调 · 暖 → 拍照生效 · 暂无需滑杆`）──────

    /**
     * 值位的唯一来源 = [presetRowValueText]（没有值 ⇒ 「—」）；"为什么点不动"必须在**行下小字**，
     * 且在 [PresetRowTap.NOTE] 态才出现 —— 不许占用值位。
     */
    @Test
    fun overviewRowValueSlotOnlyShowsRealValuesAndTheNoteMovesBelowTheRow() {
        val rowText = panel
            .substringAfter("private fun PresetOverviewRowText(")
            .substringBefore("private fun PresetQuickAdjustPanel(")
        assertTrue(
            "值位必须过 presetRowValueText（否则空值会在值位留白，说明文本会趁虚而入）",
            rowText.contains("presetRowValueText(row.value)"),
        )
        assertTrue(
            "「为什么点不动」必须在行下小字里（NOTE 态才渲染）",
            rowText.contains("if (presetRowTap(row.slot) == PresetRowTap.NOTE)") &&
                rowText.contains("cameraParamAdjustabilityNote(row.slot)"),
        )
        assertTrue(
            "行下小字必须排在值位之后（值位在上、说明在下）",
            rowText.indexOf("cameraParamAdjustabilityNote(row.slot)") >
                rowText.indexOf("presetRowValueText(row.value)"),
        )
    }

    // ── ⑤ 每一项点下去都有**看得见**的反应 ──────────────────────────────────
    /**
     * ★ 追加要求（用户："**这个界面的参数都动不了**"）：清单里**任何一项**点下去都必须落在三态
     * 之一 —— 档位高亮 / 一条滑杆 / 行下小字说明原因。**没有第四态** ⇒ "点了什么都不发生"在判据
     * 层面不存在；且 [PresetRowTap.NOTE] 必须有非空的原因文案（否则等于没反应）。
     */
    @Test
    fun everyOverviewRowTapHasAVisibleOutcome() {
        assertEquals("三态之外不许有第四态", 3, PresetRowTap.entries.size)
        for (slot in CAMERA_PATH_SLOTS) {
            when (presetRowTap(slot)) {
                PresetRowTap.LEVEL -> assertTrue(
                    "LEVEL 态必须真的在常用参数区里（[$slot]）",
                    slot in QUICK_ADJUST_SLOTS,
                )
                PresetRowTap.SLIDER -> assertTrue(
                    "SLIDER 态必须真的有滑杆（[$slot]）",
                    slot in CAMERA_TUNABLE_SLOT_PARAM,
                )
                // NOTE 态：行下必须有那句实话 —— 空文案 = 用户看到"点了没反应"
                PresetRowTap.NOTE -> assertNotNull(
                    "[$slot] 落在 NOTE 态却没有原因文案 ⇒ 点了等于没反应",
                    cameraParamAdjustabilityNote(slot),
                )
            }
        }
        // 用户点名的常用 5 项（[34] / [26][27] / [95] / [62] / [66]）一个都不许落在 NOTE 态
        for (slot in QUICK_ADJUST_SLOTS) {
            assertNotEquals("常用项 [$slot] 必须是「能直接调」的那两态", PresetRowTap.NOTE, presetRowTap(slot))
        }
        // 三态都**真实存在**（判据不是空转：至少各有一个槽）
        assertTrue("没有 LEVEL 项 ⇒ 档位高亮白写", CAMERA_PATH_SLOTS.any { presetRowTap(it) == PresetRowTap.LEVEL })
        assertTrue("没有 SLIDER 项 ⇒ 提示文案白写", CAMERA_PATH_SLOTS.any { presetRowTap(it) == PresetRowTap.SLIDER })
        assertTrue("没有 NOTE 项 ⇒ 原因小字白写", CAMERA_PATH_SLOTS.any { presetRowTap(it) == PresetRowTap.NOTE })
        // 源码接线：三态各有渲染落点（不是只有判据没有界面）
        assertTrue("档位高亮没接上", detailCard.contains("pickedTap == PresetRowTap.LEVEL"))
        assertTrue("滑杆没接上", detailCard.contains("pickedTap == PresetRowTap.SLIDER"))
        assertTrue("提示行的原因文案没接上", detailCard.contains("cameraParamAdjustabilityNote(pickedRow.slot)"))
    }

    // ── ⑥ 追加要求：滑杆渲染在**滚动区之外**的固定槽 ────────────────────────

    /**
     * 用户这轮骂的根子：点一行 ⇒ 滑杆确实插到了那一行下方，但**插在滚动区的屏幕外**。
     *
     * 判据：详情卡里唯一的 `verticalScroll` 出现在滑杆块**之后**（= 滑杆不在滚动内容里，
     * 而在清单上方的固定槽），所以它不可能被限高滚出视野。
     */
    @Test
    fun pickedSliderLivesInTheFixedAreaAboveTheList() {
        val sliderAt = detailCard.indexOf("pickedTap == PresetRowTap.SLIDER")
        val scrollAt = detailCard.indexOf("verticalScroll(")
        assertTrue(sliderAt >= 0 && scrollAt >= 0)
        assertTrue(
            "★ 滑杆必须渲染在滚动区之外（否则点一行后滑杆出现在屏幕外 = 用户看到“没反应”）",
            sliderAt < scrollAt,
        )
        assertTrue(
            "滑杆上必须有『选中项：名字 · 值』标头（否则用户不知道这条滑杆调的是哪一项）",
            detailCard.contains("\"选中项：${'$'}{pickedRow.label} · ${'$'}{presetRowValueText(pickedRow.value)}\""),
        )
    }
}
