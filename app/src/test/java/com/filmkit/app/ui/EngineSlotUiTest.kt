package com.filmkit.app.ui

import com.filmkit.app.camera.EngineScope
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.cameraPresetParams
import com.filmkit.app.camera.presetSlotIncluded
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ 阶段 3（引擎插槽：切换与预设）：**按引擎显隐** + **引擎选择入口** 的机器判据。
 *
 * 盯四件事（正是本卡的验收判据）：
 *  1. `engine_slot = 1` ⇒ filmic 独占的参数项（`[5..7]` 肩 / `[8..10]` 趾 / `[37]` 黑点 /
 *     `[38]` 白点）**不渲染**（不进网格，不是置灰），且**对比度 `[4]` 必须留下**
 *     （它在编辑器链路 `kFsStyle` 的 `uContrast` 里通用）；`engine_slot = 0` ⇒ 逐项恢复；
 *     ⚠ 另有 **1 条用户裁决**（2026-10-14 拍板）：「饱和度」`sat` 在引擎 1 下**整体隐藏**
 *     —— 它由**通用**槽位判据达成（`[22]` 纳入旁路表后三槽全失效），**没有**产品级例外，
 *     由 `saturationIsHiddenUnderEngineOneByTheGenericSlotRule` 钉住；
 *  2. 隐藏后**不留空分组**（组空即整组不出现）；
 *  3. 判据只读单一真源 `EngineScope`（本测试同时钉住"设置页那五项真的挂了 gate"）；
 *  4. 引擎选择入口写 `[98]` 之后，改动真的流过渲染路径的入参（`cameraPresetParams`）
 *     并进预设（`cameraPresetSlotsFor`），且**可选项与 C++ 注册表一致**（源码对拍）。
 *
 * 术语与文案沿用既有实现：段落名「引擎插槽」（`PresetScope.kt:144`）、选项读数「引擎 N」
 * （`PresetDetail` 的 `[98]` 格式）—— 本卡不新造译名。
 */
class EngineSlotUiTest {

    private fun preset(vararg slots: Pair<Int, Double>): EditorPreset =
        EditorPreset(id = "p_engine", name = "引擎测试", slots = slots.toMap())

    /** 本卡点名要"切引擎即消失"的四条参数项。 */
    private val filmicOwnedKeys = setOf("curveShoulder", "curveToe", "filmicBlack", "filmicWhite")

    private fun cells(tab: FullParamTab, included: Set<Int> = emptySet(), engineSlot: Int) =
        fullParamSections(tab, included, engineSlot).flatMap { it.rows.flatten() }

    // ── ① 按引擎显隐 ──────────────────────────────────────────────────────────

    @Test
    fun engineOneHidesEveryFilmicOwnedParam() {
        val adv = cells(FullParamTab.ADVANCED, engineSlot = 1).map { it.key }
        for (k in filmicOwnedKeys) {
            assertFalse("engine_slot=1 ⇒ $k 不得进网格", k in adv)
        }
        // 「黑点 / 白点」是槽位级判断（它们在 [37]/[38] 有滑杆）⇒ 只读组里也不许冒出来
        val ro = fullParamSections(FullParamTab.ADVANCED, setOf(37, 38), 1)
            .firstOrNull { it.title == "只读" }
        assertTrue("引擎 1 下 [37]/[38] 不许出现在只读组", ro == null || ro.rows.flatten().none { it.slot in setOf(37, 38) })
        // 基础档同样过滤（虽然这四条本来不在基础档，防将来被塞进去）
        val basic = cells(FullParamTab.BASIC, engineSlot = 1).map { it.key }
        assertTrue("基础档也不得出现 filmic 独占项", basic.none { it in filmicOwnedKeys })
    }

    @Test
    fun engineOneKeepsTheUniversalParams() {
        val adv = cells(FullParamTab.ADVANCED, engineSlot = 1).map { it.key }
        // ⚠ 对比度 [4] 是本卡最容易误伤的一条：它在编辑器链路（kFsStyle 的 uContrast）里通用
        // ⇒ 切引擎后照样生效 ⇒ 必须留（用户当场纠正过"对比度怎么会是 filmic 专属"）
        assertTrue("对比度必须在引擎 1 下留下", "contrast" in adv)
        // 公共段/外观层的其余项一条都不许少
        for (k in listOf("ev", "temp", "tint", "highlights", "shadows", "whites", "blacks",
                         "clarity", "wbR", "wbG", "wbB",
                         "mono", "splitWarm", "splitCool", "fade",
                         "vigStrength", "haloStrength", "glowStrength")) {
            assertTrue("引擎 1 下 $k 不该消失", k in adv)
        }
        // 基础档 = Lightroom Basic 十项 ∩ 引擎 1 下仍活着的那些 —— 唯一被摘掉的是 `sat`
        // （用户裁决"引擎 1 下整体隐藏"，由通用槽位判据达成：`[20][21][22]` 三槽全失效）
        assertEquals(
            (BASIC_PARAM_KEYS - setOf("sat")).toList(),
            cells(FullParamTab.BASIC, engineSlot = 1).map { it.key },
        )
        assertFalse("★ 产品决定（2026-10-14 用户拍板）：引擎 1 下「饱和度」整体隐藏", "sat" in adv)
    }

    /**
     * ★ 「饱和度」：用户 2026-10-14 拍板"引擎 1 下整体隐藏"（宁可丢掉 `[22]` 的真能力，也不留
     * 一条只调高光的「饱和度」）—— 本用例钉住它，并**如实记录它由哪条判据达成**。
     *
     * ⚠ 达成方式在并发审计中变过一次：`[22] sat_high` 起初不在归零表里（⇒ 需要一条产品级的
     * 显式例外 `UI_HIDDEN_PARAM_KEYS`），卡 C 抓到这条 P1 半旁路后把 `[22]` 纳入
     * [EngineScope.NEUTRALIZE] ⇒ 三槽**全部**失效 ⇒ **通用**判据自动隐藏它，显式例外被删除
     * （`EngineScope` 里"能自动推导的判据不要显式声明"的原则）。
     *
     * ⇒ 本用例断言的是**用户要的行为**（引擎 1 隐藏 / 引擎 0 显示），而不是某条特定判据；
     * 同时钉住"三槽都被旁路"这个事实，防止 `[22]` 的归零被静默撤销（那会让「饱和度」在引擎 1
     * 下重新变成只调高光的半假滑杆）。
     */
    @Test
    fun saturationIsHiddenUnderEngineOneByTheGenericSlotRule() {
        val sat = CAMERA_TUNABLE_PARAMS.first { it.key == "sat" }
        assertEquals("[20][21][22] 三槽", listOf(20, 21, 22), paramSlotsOf(sat))
        assertFalse("引擎 1 下不渲染", paramActiveInEngine(sat, 1))
        assertTrue("引擎 0 下照常渲染", paramActiveInEngine(sat, 0))
        assertTrue("引擎 0 下网格里真的有它", "sat" in cells(FullParamTab.ADVANCED, engineSlot = 0).map { it.key })
        assertFalse("引擎 1 下网格里没有它", "sat" in cells(FullParamTab.ADVANCED, engineSlot = 1).map { it.key })
        // 三槽在引擎 1 下都被旁路（`[22]` 的归零是"整体隐藏"能由通用判据达成的前提）
        assertTrue(
            "三槽在引擎 1 下都应被旁路（卡 C 的 P1 修正）",
            listOf(20, 21, 22).none { slotActiveInEngine(it, 1) },
        )
        // 隐藏不是"槽位判据之外的产品例外"：把它当作普通参数，判据给出的结论也是隐藏
        assertFalse(
            "通用判据独立地也能隐藏它（不需要产品级例外）",
            paramSlotsOf(sat).any { slotActiveInEngine(it, 1) },
        )
    }

    @Test
    fun engineZeroRestoresEverything() {
        // 默认参数（既有全部调用点的形态）== 显式 engineSlot = 0 ⇒ "引擎 0 一切原样"
        for (tab in FullParamTab.entries) {
            val implicit = fullParamSections(tab, setOf(70))
            val explicit = fullParamSections(tab, setOf(70), 0)
            assertEquals(implicit.map { it.title }, explicit.map { it.title })
            assertEquals(
                implicit.flatMap { it.rows.flatten() }.map { it.key },
                explicit.flatMap { it.rows.flatten() }.map { it.key },
            )
        }
        // 与引擎 1 的差别**恰好**是四条 filmic 独占项 + `sat`（产品决定）—— 不多不少，防误伤
        val zero = cells(FullParamTab.ADVANCED, engineSlot = 0).map { it.key }.toSet()
        val one = cells(FullParamTab.ADVANCED, engineSlot = 1).map { it.key }.toSet()
        assertEquals((filmicOwnedKeys + "sat").sorted(), (zero - one).sorted())
        assertTrue("引擎 1 不该多出任何参数", (one - zero).isEmpty())
    }

    @Test
    fun hidingLeavesNoEmptyGroupAndNoEmptyTitle() {
        for (tab in FullParamTab.entries) {
            for (slot in listOf(0, 1)) {
                val secs = fullParamSections(tab, setOf(35, 40, 70), slot)
                assertTrue("$tab/$slot ⇒ 不许出现空网格", secs.isNotEmpty())
                assertTrue("$tab/$slot ⇒ 不许留下空分组（空标题）", secs.all { it.rows.isNotEmpty() })
                assertTrue("$tab/$slot ⇒ 每行都必须有格子", secs.all { it.rows.all { r -> r.isNotEmpty() } })
                // 标题不重复（组只加一次）
                assertEquals(secs.map { it.title }.distinct(), secs.map { it.title })
            }
        }
        // 引擎 1 下「调整」组仍在（12 项 = 17 − 4 条 filmic 独占 − 1 条产品决定 sat），
        // 不是"整组消失"；组内其余项按原顺序（`sat` 在末尾被摘掉）
        val adjust = fullParamSections(FullParamTab.ADVANCED, emptySet(), 1)
            .first { it.title == "调整" }.rows.flatten()
        assertEquals(17 - 4 - 1, adjust.size)
        assertEquals("高光", adjust.first { it.key == "highlights" }.label)
    }

    @Test
    fun readonlyGroupAlsoFiltersFilmicOwnedSlots() {
        // [35] auto_tone / [40] gamut 是 filmic 独占但没有滑杆 ⇒ 预设显式含它们时落进只读组。
        // 引擎 1 下它们既无效也不该出现在界面上（判据 = 同一个 FILMIC_OWNED_SLOTS）。
        val one = fullParamSections(FullParamTab.ADVANCED, setOf(35, 40, 70), 1)
            .first { it.title == "只读" }.rows.flatten().map { it.slot }
        assertTrue("[35] 不该出现在引擎 1 的只读组", 35 !in one)
        assertTrue("[40] 不该出现在引擎 1 的只读组", 40 !in one)
        assertTrue("[70]（无滑杆但拍照不读）仍要如实显示", 70 in one)

        val zero = fullParamSections(FullParamTab.ADVANCED, setOf(35, 40, 70), 0)
            .first { it.title == "只读" }.rows.flatten().map { it.slot }
        assertTrue("引擎 0 下 [35]/[40] 照旧显示", 35 in zero && 40 in zero)
    }

    // ── ② 判据只读单一真源 ────────────────────────────────────────────────────

    @Test
    fun visibilityIsDrivenByEngineScopeNotByALocalCopy() {
        // 参数级判据 == "该参数涉及的槽还有活着的"，逐条与 EngineScope 对拍（**无产品级例外**）
        for (p in CAMERA_TUNABLE_PARAMS) {
            val slots = paramSlotsOf(p)
            val expected = slots.isEmpty() ||
                slots.any { it !in EngineScope.FILMIC_OWNED_SLOTS && it !in EngineScope.NEUTRALIZE }
            assertEquals("${p.key} 的显隐判据必须等于 EngineScope 推导的结果", expected, paramActiveInEngine(p, 1))
        }
        // 四条 filmic 独占参数：全部槽都被引擎 0 独占
        for (k in filmicOwnedKeys) {
            val p = CAMERA_TUNABLE_PARAMS.first { it.key == k }
            assertTrue("$k 的槽应全在 FILMIC_OWNED_SLOTS", paramSlotsOf(p).all { it in EngineScope.FILMIC_OWNED_SLOTS })
            assertFalse(paramActiveInEngine(p, 1))
        }
        // 对比度 / 曝光：槽位判据保留它们（`sat` 在引擎 1 下也隐藏，见专测）
        for (k in listOf("contrast", "ev")) {
            assertTrue("$k 与任一引擎无关，必须一直显示", paramActiveInEngine(CAMERA_TUNABLE_PARAMS.first { it.key == k }, 1))
        }
        // 槽位级判据（设置页用）：引擎 0 全活；引擎 1 只死"独占 ∪ 旁路"；
        // **未注册值按 0 处理**（与 EngineScope.applyEngineScope 的 effective 同口径，卡 C 的 P2）
        for (slot in 0 until CameraPresets.COUNT) {
            assertTrue("engine_slot=0 ⇒ 一切原样（槽 $slot）", slotActiveInEngine(slot, 0))
            assertEquals(
                "槽 $slot 的判据必须等于 EngineScope 推导的结果",
                slot !in EngineScope.FILMIC_OWNED_SLOTS && slot !in EngineScope.NEUTRALIZE,
                slotActiveInEngine(slot, 1),
            )
            assertTrue(
                "未注册的 engine_slot（如 2）按 0 处理 ⇒ 槽 $slot 照旧显示",
                slotActiveInEngine(slot, 2) && slotActiveInEngine(slot, -7),
            )
        }
        // 未注册值在参数级也走同一条路（不是"非 0 即隐藏"）
        for (p in CAMERA_TUNABLE_PARAMS) {
            assertEquals("${p.key} 在未注册引擎值下必须按引擎 0 显示", paramActiveInEngine(p, 0), paramActiveInEngine(p, 2))
        }
    }

    @Test
    fun settingsScreenGatesExactlyTheFiveFilmicKnobsAndKeepsBaseEv() {
        val src = File("src/main/java/com/filmkit/app/ui/SettingsScreen.kt").readText()
        val gated = Regex("""slotActiveInEngine\((\d+),""").findAll(src).map { it.groupValues[1].toInt() }.toList()
        // 恰好五项挂了 gate：基准影调 [28] / 饱和补偿 [20] / 黑点 [37] / 白点 [38] / 局部色调 [36]
        assertEquals(listOf(28, 20, 37, 38, 36), gated)
        // ⚠「基准曝光」[0] 必须**不在** gate 里（通用能力，架构师定案 §8.1 的"保留"一条）：
        // 上面那条"恰好五项"就是它的机器判据（多挂一项就红），这里再钉一次槽位号不在列表里
        assertFalse("基准曝光 [0] 不许被 gate", 0 in gated)
        assertTrue("「基准曝光」条目本身不许被删", src.contains("label = \"基准曝光\""))
        for (t in listOf("基准影调", "饱和补偿", "黑点（多暗算黑）", "白点（多亮算白）", "局部色调映射")) {
            assertTrue("设置页缺了「$t」", src.contains("title = \"$t\"") || src.contains("label = \"$t\""))
        }
    }

    @Test
    fun firstLevelPanelGatesTheWhitePointWithTheSameJudge() {
        // 一级页（拍照预设详情卡 / 编辑面板共用 `PresetQuickAdjustPanel`）也渲染白点 `[38]`
        // —— 同一个 `EditorParam`、同一根因 ⇒ 必须同一道 gate；否则一级页仍留着假滑杆。
        val src = File("src/main/java/com/filmkit/app/ui/CameraPresetPanel.kt").readText()
        val quick = src.substringAfter("private fun PresetQuickAdjustPanel(")
            .substringBefore("private fun PresetParamLabelRow(")
        assertTrue(
            "一级页的白点行必须挂 slotActiveInEngine 判据",
            Regex("""if \(slotActiveInEngine\(FILMIC_WHITE_EV_SLOT,""").containsMatchIn(quick),
        )
        assertTrue(
            "一级页当前引擎取自它正在读写的那份数组",
            quick.contains("params.getOrElse(ENGINE_SLOT)"),
        )
        // 集合本身**不动**（它是"一级页有哪些可调槽"的定义，被多条既有测试钉住）——
        // 本卡只加"当前引擎下是否渲染"这一层，不改能力清单
        assertEquals(
            linkedSetOf(LUT_STRENGTH_SLOT, GRAIN_AMOUNT_SLOT, 95, 62, 66, FILMIC_WHITE_EV_SLOT),
            QUICK_ADJUST_SLOTS,
        )
    }

    // ── ③ 引擎选择入口：可选值 + 写入 [98] + 落进渲染路径 ────────────────────────
    @Test
    fun engineChoicesMatchTheCppRegistry() {
        // 选择面可选值 = `EngineScope.KNOWN_ENGINE_SLOTS` 的有序投影（**不另立引擎表**），
        // 而契约又必须与 C++ 注册表一致 —— 三者在这里一次对拍（与 CameraStyleWhitelistTest 同纪律）
        val src = File("../core/engine/src/film/EngineRegistry.cpp").readText()
        val table = src.substringAfter("const EngineEntry kEngines").substringBefore("\n};")
        val slots = Regex("""\{\s*(\d+)\s*,""").findAll(table).map { it.groupValues[1].toInt() }.toList()
        assertEquals("契约的已注册引擎必须与 C++ kEngines 表逐项一致", slots, EngineScope.KNOWN_ENGINE_SLOTS.sorted())
        assertEquals("选择面只是契约的有序投影", EngineScope.KNOWN_ENGINE_SLOTS.sorted(), ENGINE_SLOT_CHOICES)
        assertTrue("注册表至少要有两个引擎（阶段 3 的切换才有意义）", ENGINE_SLOT_CHOICES.size >= 2)
        assertTrue("引擎 0 = 内置 filmic（默认档）", 0 in ENGINE_SLOT_CHOICES)
    }

    @Test
    fun engineChoiceLandsInSlot98AndIsReadByTheRenderPath() {
        val p = preset()
        // ① 选择面 → 改动集（复用参数滑杆那一条 afterEdit 记账）
        val next = (null as CameraParamOverrides?).afterEdit(p, EngineSlotParam, 1f)
        assertEquals("选择面必须写 [98]", 1.0, next.values.getValue(98), 1e-9)
        // ② 改动集 → **下发引擎的同一个数组**（`previewCameraParams` → `setFilmParams` 的入参）
        assertEquals(1, cameraPresetParams(p, next.values, next.removed)[98].toInt())
        // ③ 数组 → 预设（「保存」走 cameraPresetSlotsFor；[98] 在进预设的白名单里）
        assertTrue("[98] 必须能进预设", presetSlotIncluded(98))
        assertEquals(1.0, cameraPresetSlotsFor(p, next.values, next.removed).getValue(98), 1e-9)
        // ④ 选回原引擎 ⇒ 撤销这次改动（不是"存一个等于原值的覆盖"），保存按钮当场置灰
        val back = next.afterEdit(p, EngineSlotParam, 0f)
        assertTrue("选回引擎 0 = 无改动", back.values.isEmpty())
    }

    @Test
    fun selectorWordingIsReusedFromTheDetailPage() {
        // 同一个槽在两页显示同一个字符串（不新造译名）：选择面的 pill 文案 = PresetDetail 的 [98] 读数
        val film = CameraPresets.standard()
        for (slot in ENGINE_SLOT_CHOICES) {
            film[ENGINE_SLOT] = slot.toDouble()
            assertEquals(
                "引擎 $slot 的选择面文案必须等于详情页读数",
                presetDetailRowOf(ENGINE_SLOT, slot.toDouble()).value,
                EngineSlotParam.format(EngineSlotParam.read(film)),
            )
            assertTrue(EngineSlotParam.format(slot.toFloat()).startsWith("引擎 "))
        }
        assertEquals("引擎插槽", EngineSlotParam.label)
        assertEquals(98, ENGINE_SLOT)
        assertEquals("引擎插槽", presetDetailRowOf(98, 0.0).label)
    }
}
