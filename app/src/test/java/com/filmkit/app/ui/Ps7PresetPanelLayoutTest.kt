package com.filmkit.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ PS-7 的守护（用户 2026-10-12 真机截图："**你做这一坨屎是什么**" —— 面板半透明，取景界面的
 * `1/60 1539 29cm 290`、`EV Tv ISO AF WB` 透上来与预设内容混叠）**+ PS-8 的反向哨兵**。
 *
 * ★ PS-8（用户 2026-10-12/13："**第一级页面不需要提供全部参数，点击查看完整参数才提供**"）把
 * 一级页的「当前搭配」清单整块删掉 ⇒ PS-7 原先那几条"清单限高 / 三态反应 / 值位口径"的**正面**
 * 判据随清单退役（它们守的控件已不存在），本文件只留两件事：
 *
 *  1. **透视不许回来**：`LutSheet` 必须是不透明底（`PanelSolid`）；
 *  2. **清单形态不许回来**：清单 / 展开收起 / 选中项滑杆 / 解释性小字，一个符号都不许复活
 *     —— 一级页的**正面**判据在 [Ps8PresetUiRedesignTest]（横向条 / 名称行 / 常用参数 / 返回键）。
 */
class Ps7PresetPanelLayoutTest {

    private val panel = File("src/main/java/com/filmkit/app/ui/CameraPresetPanel.kt").readText()
    private val screen = File("src/main/java/com/filmkit/app/ui/CameraScreen.kt").readText()

    /** `LutSheet` 函数体（锚点 = 它自己的签名与紧邻的下一个顶层函数）。 */
    private val lutSheet =
        screen.substringAfter("private fun LutSheet(").substringBefore("internal fun RelativeSlider(")

    /**
     * 截图缺陷 1：能看见取景界面的拍摄参数行透过面板混叠。根因 = `FilmTheme.Panel`（`0xE6141414`，
     * 90% alpha）；`FilmTheme.PanelSolid`（`0xFF141414`）就是为"半透明会透出下层"准备的既有常量。
     */
    @Test
    fun lutSheetUsesTheOpaquePanelSoTheViewfinderTextCannotBleedThrough() {
        assertTrue(
            "★ LutSheet 必须是不透明底（半透明 ⇒ 取景界面的拍摄参数行透过来）",
            lutSheet.contains("background(FilmTheme.PanelSolid)"),
        )
        assertFalse(
            "LutSheet 里不许再有半透明底（90% alpha 会让下层文字透上来）",
            lutSheet.contains("background(FilmTheme.Panel)"),
        )
        assertFalse(
            "预设面板（一级页所在这一层）也不许引用半透明 Panel —— 它继承宿主的 PanelSolid",
            panel.contains("FilmTheme.Panel)"),
        )
    }

    /**
     * ★ PS-8：一级页**不再提供全部参数** ⇒ 清单形态的符号一个都不许回来。
     *
     * 为什么值得逐个钉：这些符号是**能力**的落点而不是样式 —— 清单在，就意味着"一级页又要把
     * 35 项参数摊开"，用户下一轮还会骂回来。逐项参数从此只有一条路：「完整参数 >」（PS-6b）。
     */
    @Test
    fun theOverviewChecklistAndItsExplanatoryNoiseAreGoneForGood() {
        for (dead in listOf(
            // 清单 + 它的纯逻辑（PS-6a/PS-7 的判据）
            "presetOverviewRows(", "presetOverviewText(", "presetRowTap(", "PresetRowTap",
            "presetRowValueText(", "presetOverviewShownRows(", "presetOverviewMaxHeightDp(",
            "PRESET_OVERVIEW_ROWS_", "presetPanelBudget(", "PRESET_PANEL_BUDGET_DP",
            "presetQuickPath(", "PresetQuickPath", "quickAdjustLabelOf(",
            "CameraPresetDetailCard(", "PresetOverviewRowText(", "CameraPresetRow(",
            // 界面上的旧形态与解释性噪音
            "\"当前搭配\"", "选择一项开始调整", "实时效果 开", "展开 ", "\"收起\"",
        )) {
            assertFalse("「$dead」是用户砍掉的一级页清单形态 ⇒ 不许回来", panel.contains(dead))
        }
    }
}
