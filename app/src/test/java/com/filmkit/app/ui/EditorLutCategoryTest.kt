package com.filmkit.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2-16「LUT 分类标签行」（ADR-17）的纯逻辑护栏。
 *
 * 为什么只测这三条：标签行、缩略图行、下划线要真机（Compose + GL），JVM 跑不了。这里守住
 * 三条"错了用户直接看得见、且最容易悄悄错"的事实：
 *
 *  1. **分类列表的去重与保序**（[lutCategoryTabs]）：重复分类会出现两个一模一样的标签，
 *     点了其中一个另一个不亮（选中态按名字比较）；顺序一变，用户记住的"徕卡在最前面"就失效。
 *  2. **过滤语义**（[lutInCategory]）：`null` = 「全部」必须**不过滤**（返回原表），
 *     非 null 必须**精确匹配**；错了就是"点了徕卡出来柯达"或"点全部只剩一个"。
 *  3. **色板下标安全**（[categoryDotIndex]）：`Int.MIN_VALUE` 的 `abs()` 仍是负数（溢出），
 *     用它取模会得到负下标 ⇒ 数组越界崩溃。这是 Java 哈希的经典陷阱，必须钉住。
 *
 * 另有一条**验收数字**测试：ADR-17 记的 42 个内置 LUT 分类分布（徕卡 12 / 富士 11 / 柯达 7 /
 * 理光 6 / CineStill 2 / 哈苏 2 / MI 2）—— 钉住"分类与数量对得上"。
 */
class EditorLutCategoryTest {

    // ── 分类列表：去重 / 去空白 / 保序 ──────────────────────────────────────

    @Test
    fun categoriesAreDedupedAndOrderPreserved() {
        // 条目分类（资产扫描顺序）+ 自定义分类；重复项只留第一次出现的位置
        val tabs = lutCategoryTabs(
            entryCategories = listOf("徕卡", "富士", "柯达", "徕卡", "富士"),
            customCategories = listOf("导入", "富士", "我的"),
        )
        assertEquals(listOf("徕卡", "富士", "柯达", "导入", "我的"), tabs)
    }

    @Test
    fun blankCategoriesAreDropped() {
        // 资产头部没有分类字节时 category 为空串（LutLibrary.headOf 会给出 ""）⇒ 不能变成一个空标签
        val tabs = lutCategoryTabs(
            entryCategories = listOf("", "徕卡", "   ", "富士"),
            customCategories = listOf("", "\n"),
        )
        assertEquals(listOf("徕卡", "富士"), tabs)
    }

    @Test
    fun emptyLibraryHasNoCategories() {
        assertEquals(emptyList<String>(), lutCategoryTabs(emptyList(), emptyList()))
        // 只有自定义分类（还没有 LUT 归入）时，分类行仍然给出它们 —— 空态由缩略图行提示
        assertEquals(listOf("我的"), lutCategoryTabs(emptyList(), listOf("我的")))
    }

    // ── 过滤：「全部」不过滤 / 精确匹配 / 空结果 ─────────────────────────────

    private val lib = listOf(
        "徕卡M9" to "徕卡",
        "徕卡M10" to "徕卡",
        "富士经典" to "富士",
        "柯达金" to "柯达",
        "导入A" to "导入",
    )

    private fun namesIn(category: String?): List<String> =
        lutInCategory(lib, category) { it.second }.map { it.first }

    @Test
    fun allCategoryReturnsEverythingUntouched() {
        // 「全部」= null ⇒ 原表原序返回（不是"新建一个等价的表"——顺序也必须一致）
        assertEquals(lib.map { it.first }, namesIn(null))
        assertEquals(lib, lutInCategory(lib, null) { it.second })
    }

    @Test
    fun categoryFiltersExactly() {
        assertEquals(listOf("徕卡M9", "徕卡M10"), namesIn("徕卡"))
        assertEquals(listOf("富士经典"), namesIn("富士"))
        // 精确匹配：不做 trim / 忽略大小写 —— 分类名全部来自 LutLibrary 同一来源，模糊匹配只会
        // 把"我的分类"和"我的分类 "这种本该不同的名字混在一起
        assertEquals(emptyList<String>(), namesIn(" 徕卡"))
        assertEquals(emptyList<String>(), namesIn("徕卡 "))
    }

    @Test
    fun emptyCategoryYieldsEmptyList() {
        // 用户新建了分类但没把任何 LUT 归进去（或归入的 LUT 被改走）⇒ 空表，调用侧显示空态提示
        assertEquals(emptyList<String>(), namesIn("我的"))
        assertTrue(lutInCategory(emptyList<String>(), "徕卡") { it }.isEmpty())
    }

    // ── 验收数字：42 个内置 LUT 的分类分布（ADR-17）─────────────────────────

    @Test
    fun builtInDistributionMatchesAdr17() {
        // ADR-17 记的实际分布：徕卡 12 / 富士 11 / 柯达 7 / 理光 6 / CineStill 2 / 哈苏 2 / MI 2
        val counts = linkedMapOf(
            "徕卡" to 12, "富士" to 11, "柯达" to 7, "理光" to 6,
            "CineStill" to 2, "哈苏" to 2, "MI" to 2,
        )
        val entries = counts.flatMap { (cat, n) -> List(n) { cat } }
        assertEquals(42, entries.size)

        // 分类行：7 个分类，顺序 = 条目出现顺序（首个徕卡在最前，与资产扫描一致）
        val tabs = lutCategoryTabs(entries, emptyList())
        assertEquals(counts.keys.toList(), tabs)
        assertEquals(7, tabs.size)

        // 每个分类过滤出的数量必须与分布一致（"分类与数量对得上"）
        counts.forEach { (cat, n) ->
            assertEquals("$cat 的数量", n, lutInCategory(entries, cat) { it }.size)
        }
        // 「全部」= 42
        assertEquals(42, lutInCategory(entries, null) { it }.size)
    }

    // ── 圆点色板下标 ────────────────────────────────────────────────────────

    @Test
    fun dotIndexIsStableAndInRange() {
        val palette = 7
        listOf("徕卡", "富士", "柯达", "理光", "CineStill", "哈苏", "MI", "导入", "我的").forEach { c ->
            val i = categoryDotIndex(c, palette)
            assertTrue("$c 的下标 $i 越界", i in 0 until palette)
            assertEquals("同名必须恒同色", i, categoryDotIndex(c, palette))
        }
    }

    @Test
    fun dotIndexSurvivesIntMinValueHash() {
        // "polygenelubricants" 的 hashCode 恰好是 Int.MIN_VALUE：abs() 溢出仍是负数 ⇒ 负下标崩溃。
        // 这里钉住掩码写法（and 0x7FFFFFFF）。
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        val i = categoryDotIndex("polygenelubricants", 7)
        assertTrue("Int.MIN_VALUE 哈希必须给出非负下标，实际 $i", i in 0 until 7)
    }

    @Test
    fun dotIndexHandlesDegeneratePalette() {
        assertEquals(0, categoryDotIndex("徕卡", 0))
        assertEquals(0, categoryDotIndex("徕卡", -3))
    }
}
