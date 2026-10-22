package com.filmkit.app.ui

import com.filmkit.app.camera.ENGINE_OVERRIDE_FOLLOW_PRESET
import com.filmkit.app.camera.EngineScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ 阶段 3b（设置页**全局引擎开关**）：三档 `engine_override` + 三处 gate 改读**生效引擎**。
 *
 * 盯五件事（= 本卡验收判据）：
 *  1. `override = -1`（跟随预设）⇒ gate 读预设 `[98]`，与阶段 3 行为**逐项一致**；
 *  2. `override = 1` + 预设 `[98] = 0` ⇒ 参数面板的 filmic 项**隐藏**（按生效引擎 1）；
 *  3. `override = 0` + 预设 `[98] = 1` ⇒ filmic 项**显示**（按生效引擎 0）；
 *  4. `override = 7`（未注册）⇒ 按**跟随预设**处理（契约 [EngineScope.effectiveEngineSlot]）；
 *  5. 三档写入的 prefs 值 = `-1 / 0 / 1`，且"完整参数"页在跟随档下行为不变
 *     （选择面仍写**预设的** `[98]`，被强制覆盖时另有提示）。
 *
 * 术语沿用既有实现：段落名「引擎插槽」、选项读数「引擎 N」（[presetDetailRowOf]）—— 不新造译名。
 * 合成规则**只许**走 `EngineScope.effectiveEngineSlot`（UI 侧唯一入口 = [effectiveEngineSlotOf]）：
 * 本测试同时钉住"三处 gate 都没有自己拼 `if (override >= 0) ...`"。
 */
class EngineOverrideUiTest {

    private fun param(key: String) = CAMERA_TUNABLE_PARAMS.first { it.key == key }

    private fun cells(tab: FullParamTab, included: Set<Int> = emptySet(), engineSlot: Int) =
        fullParamSections(tab, included, engineSlot).flatMap { it.rows.flatten() }

    /** 引擎 0（filmic）独占的四条参数项（与 `EngineSlotUiTest` 同一集合）。 */
    private val filmicKeys = setOf("curveShoulder", "curveToe", "filmicBlack", "filmicWhite")

    private fun srcOf(name: String) = File("src/main/java/com/filmkit/app/ui/$name").readText()

    // ── ① 跟随预设（默认档）＝ 阶段 3 行为 ─────────────────────────────────────

    @Test
    fun followOverrideReadsThePresetSlot() {
        for (presetSlot in EngineScope.KNOWN_ENGINE_SLOTS) {
            assertEquals(
                "跟随档（-1）⇒ 生效引擎就是预设 [98]",
                presetSlot,
                EngineScope.effectiveEngineSlot(presetSlot, ENGINE_OVERRIDE_FOLLOW_PRESET),
            )
        }
        // 「完整参数」页在跟随档下与阶段 3 逐项一致（engineSlot 参数就是预设 [98]）
        for (tab in FullParamTab.entries) {
            for (presetSlot in listOf(0, 1)) {
                val phase3 = cells(tab, setOf(37, 38, 70), presetSlot)
                val viaOverride = cells(tab, setOf(37, 38, 70), EngineScope.effectiveEngineSlot(presetSlot, -1))
                assertEquals(phase3.map { it.key }, viaOverride.map { it.key })
                assertEquals(phase3.map { it.label }, viaOverride.map { it.label })
                assertEquals(phase3.map { it.slot }, viaOverride.map { it.slot })
            }
        }
    }

    // ── ② 强制引擎 1 压过预设的 0 ─────────────────────────────────────────────

    @Test
    fun forcedEngineOneHidesFilmicParamsEvenWhenThePresetSaysZero() {
        val effective = EngineScope.effectiveEngineSlot(presetSlot = 0, override = 1)
        assertEquals("强制档压过预设", 1, effective)
        for (k in filmicKeys) {
            assertFalse("$k 必须按生效引擎 1 隐藏", paramActiveInEngine(param(k), effective))
        }
        val adv = cells(FullParamTab.ADVANCED, engineSlot = effective).map { it.key }
        for (k in filmicKeys) assertFalse("$k 不得进网格", k in adv)
        // 网格 == 「引擎 1」那一套（生效引擎说了算，与预设值无关）
        assertEquals(cells(FullParamTab.ADVANCED, engineSlot = 1).map { it.key }, adv)
        // 对照：同一预设 `[98] = 0` 在跟随档下四项都在（说明差别确实来自强制档）
        val followed = EngineScope.effectiveEngineSlot(0, ENGINE_OVERRIDE_FOLLOW_PRESET)
        for (k in filmicKeys) assertTrue("跟随档下 $k 照旧显示", paramActiveInEngine(param(k), followed))
    }

    // ── ③ 强制引擎 0 压过预设的 1 ─────────────────────────────────────────────

    @Test
    fun forcedEngineZeroShowsFilmicParamsEvenWhenThePresetSaysOne() {
        val effective = EngineScope.effectiveEngineSlot(presetSlot = 1, override = 0)
        assertEquals("强制档压过预设", 0, effective)
        for (k in filmicKeys) {
            assertTrue("$k 必须按生效引擎 0 显示", paramActiveInEngine(param(k), effective))
        }
        val adv = cells(FullParamTab.ADVANCED, engineSlot = effective).map { it.key }
        for (k in filmicKeys) assertTrue("$k 必须进网格", k in adv)
        // 引擎 0 下被旁路的三槽（饱和）也一起回来
        assertTrue("引擎 0 下「饱和度」照旧显示", "sat" in adv)
        // 对照：同一预设 `[98] = 1` 在跟随档下四项都消失
        val followed = EngineScope.effectiveEngineSlot(1, ENGINE_OVERRIDE_FOLLOW_PRESET)
        for (k in filmicKeys) assertFalse("跟随档下 $k 应隐藏", paramActiveInEngine(param(k), followed))
    }

    // ── ④ 未注册档位 ⇒ 按跟随预设 ─────────────────────────────────────────────

    @Test
    fun unregisteredOverrideFallsBackToFollowingThePreset() {
        for (presetSlot in EngineScope.KNOWN_ENGINE_SLOTS) {
            assertEquals(
                "未注册档位（7）必须与跟随档同解",
                EngineScope.effectiveEngineSlot(presetSlot, ENGINE_OVERRIDE_FOLLOW_PRESET),
                EngineScope.effectiveEngineSlot(presetSlot, 7),
            )
        }
        // 读数不许显示成「引擎 7」（假读数）；点一下进「引擎 0」（不卡在坏值上）
        assertEquals("跟随预设", engineOverrideLabel(7))
        assertEquals(0, nextEngineOverride(7))
        // 面板层：预设 1 + 坏档位 == 预设 1 + 跟随档
        assertEquals(
            cells(FullParamTab.ADVANCED, engineSlot = EngineScope.effectiveEngineSlot(1, -1)).map { it.key },
            cells(FullParamTab.ADVANCED, engineSlot = EngineScope.effectiveEngineSlot(1, 7)).map { it.key },
        )
    }

    // ── ⑤ 三档的取值 / 文案 / 推进 ────────────────────────────────────────────

    @Test
    fun theThreeGearValuesAreMinusOneZeroOne() {
        assertEquals("prefs 键 = 契约 KDoc 里写的那个", "engine_override", ENGINE_OVERRIDE_PREFS_KEY)
        assertEquals("跟随档哨兵值 = -1", -1, ENGINE_OVERRIDE_FOLLOW_PRESET)
        // ★ 与渲染侧**同一个键、同一个缺省哨兵**：哨兵常量用的是 `camera/EditorExport.kt` 那一份
        //（UI 侧不另立 -1），键名在渲染侧是硬编码 ⇒ 这里源码对拍一次（那两张卡的文件不在本卡改动范围）。
        val controller = File("src/main/java/com/filmkit/app/camera/CameraController.kt").readText()
        assertTrue(
            "渲染侧必须读同一个 prefs 键 + 同一个缺省哨兵",
            controller.contains("getInt(\"$ENGINE_OVERRIDE_PREFS_KEY\", ENGINE_OVERRIDE_FOLLOW_PRESET)"),
        )
        assertEquals("三档 = 跟随预设 + 契约的已注册引擎（不另立引擎表）",
            listOf(-1, 0, 1), ENGINE_OVERRIDE_CHOICES)
        assertEquals(EngineScope.KNOWN_ENGINE_SLOTS.sorted(), ENGINE_OVERRIDE_CHOICES.drop(1))
        // 循环一圈写出的三个值恰好是 -1 / 0 / 1，且回到跟随档
        var v = ENGINE_OVERRIDE_FOLLOW_PRESET
        val seen = mutableListOf<Int>()
        repeat(ENGINE_OVERRIDE_CHOICES.size) {
            v = nextEngineOverride(v)
            seen += v
        }
        assertEquals(listOf(0, 1, -1), seen)
        assertEquals("转一圈回到出发档", ENGINE_OVERRIDE_FOLLOW_PRESET, v)
        assertEquals(
            "最后一档绕回「跟随预设」",
            ENGINE_OVERRIDE_FOLLOW_PRESET,
            nextEngineOverride(ENGINE_OVERRIDE_CHOICES.last()),
        )
        for (slot in ENGINE_OVERRIDE_CHOICES) {
            assertTrue("任何档位点一下都还在这三档里", nextEngineOverride(slot) in ENGINE_OVERRIDE_CHOICES)
        }
    }

    @Test
    fun gearLabelsReuseExistingWording() {
        assertEquals("跟随预设", engineOverrideLabel(ENGINE_OVERRIDE_FOLLOW_PRESET))
        for (slot in ENGINE_SLOT_CHOICES) {
            assertEquals(
                "强制档文案必须逐字等于详情页给 [98] 的读数（不新造译名）",
                presetDetailRowOf(ENGINE_SLOT, slot.toDouble()).value,
                engineOverrideLabel(slot),
            )
        }
        assertEquals("引擎插槽", presetDetailRowOf(ENGINE_SLOT, 0.0).label)
    }

    // ── ⑥ 接线：设置页写 prefs + 三处 gate 走同一入口 ──────────────────────────

    @Test
    fun settingsScreenWritesTheGearToPrefsAndLogsIt() {
        val settings = srcOf("SettingsScreen.kt")
        val drawer = settings.substringAfter("private fun ToneColorDrawerContent(")
        assertTrue("三档开关必须落在「影调与色彩」抽屉里", drawer.contains("nextEngineOverride(engineOverride)"))
        assertTrue("必须写 prefs（值 = 档位）", drawer.contains("putInt(ENGINE_OVERRIDE_PREFS_KEY, v)"))
        assertTrue("档位推进走共用函数（不内联 indexOf/%）", drawer.contains("val v = nextEngineOverride(engineOverride)"))
        assertTrue("与同抽屉「基准影调」同款记一条日志", settings.contains("\"settings.engine_override\""))
        assertTrue("读数用共用文案", drawer.contains("valueText = engineOverrideLabel(engineOverride)"))
        // 三档开关**不进** `slotActiveInEngine` 的 gate 计数。期 B 起是 **6 项**：原 5 项
        // + `[40]` 色域映射（它本就在 `EngineScope.FILMIC_OWNED_SLOTS` 里，属设置页漏挂 ⇒
        // 引擎 1 下显示却完全无效；补挂见 EngineSlotUiTest 的同名判据）。
        assertEquals(
            listOf(28, 20, 37, 38, 36, 40, 41),
            Regex("""slotActiveInEngine\((\d+),""").findAll(settings).map { it.groupValues[1].toInt() }.toList(),
        )
    }

    @Test
    fun allThreeGatesReadTheEffectiveEngineThroughTheSingleEntryPoint() {
        val settings = srcOf("SettingsScreen.kt")
        val full = srcOf("CameraPresetFullEdit.kt")
        val panel = srcOf("CameraPresetPanel.kt")

        // ① 设置页：预设 [98] 与 ⊕ override 分开，合成走 [effectiveEngineSlotOf]
        assertTrue("设置页 gate 必须读生效引擎", settings.contains("effectiveEngineSlotOf(prefs, presetEngineSlot)"))
        // ② 完整参数页：同上；网格按生效引擎，选择面仍写预设的 [98]
        assertTrue("完整参数页 gate 必须读生效引擎", full.contains("effectiveEngineSlotOf(prefs, presetEngineSlot)"))
        assertTrue("选择面仍编辑预设的 [98]（用户要求保留）", full.contains("engineSlot = presetEngineSlot,"))
        // ③ 一级页：同上，且"先读它正在读写的那份数组"那条既有判据不许破
        val quick = panel.substringAfter("private fun PresetQuickAdjustPanel(")
            .substringBefore("private fun PresetParamLabelRow(")
        assertTrue("一级页 gate 必须读生效引擎", quick.contains("effectiveEngineSlotOf(prefs, presetEngineSlot)"))
        assertTrue("一级页仍先读它正在读写的那份数组", quick.contains("params.getOrElse(ENGINE_SLOT)"))

        // ★ 覆盖提示（完整参数页）：只在**真的强制**时出现，且仍可编辑（不是禁用）
        assertTrue("提示判据 = 已注册引擎的强制档", full.contains("if (engineOverride in ENGINE_SLOT_CHOICES)"))
        assertTrue("提示文案", full.contains("设置页已强制"))
        assertTrue("提示是一行作用域样式的 Text（既有 PresetScope 提示的做法）",
            Regex("""设置页已强制[\s\S]{0,400}?color = FilmTheme\.CreamDim""").containsMatchIn(full))

        // ★ 三处都不许自拼 override 判据；也不许绕过单一入口直调契约
        for ((name, src) in listOf(
            "SettingsScreen.kt" to settings,
            "CameraPresetFullEdit.kt" to full,
            "CameraPresetPanel.kt" to panel,
        )) {
            assertFalse("$name 不许自拼 `engineOverride >= 0` 之类的判据",
                Regex("""engineOverride\s*>=?\s*0""").containsMatchIn(src))
            assertFalse("$name 不许绕过 [effectiveEngineSlotOf] 直调契约",
                src.contains("EngineScope.effectiveEngineSlot("))
        }
        // 单一入口自己才调契约
        val controls = srcOf("ParamControls.kt")
        assertTrue("唯一入口 = ParamControls.effectiveEngineSlotOf",
            controls.contains("EngineScope.effectiveEngineSlot(presetSlot, engineOverrideOf(prefs))"))
    }

    @Test
    fun fullEditEngineSelectorStaysEditableUnderAForcedOverride() {
        val full = srcOf("CameraPresetFullEdit.kt")
        // 选择面**无条件**渲染（用户 2026-10-14：强制档下仍可编辑 —— 可以先把预设设好），
        // 提示行排在它**后面** ⇒ 提示不是"替换控件"，只是多一行实话。
        val selector = full.indexOf("EngineSlotSelector(")
        val notice = full.indexOf("设置页已强制")
        assertTrue("选择面必须在（保留）", selector > 0)
        assertTrue("提示行必须在选择面之后（不是替换它）", notice > selector)
        assertTrue("选择面必须仍可写 [98]", full.contains("overrides.afterEdit(preset, EngineSlotParam, slot.toFloat())"))
        // 提示只在强制档出现（跟随档 / 未注册档位一行都不占）
        assertTrue("提示的判据只在强制档为真", full.contains("if (engineOverride in ENGINE_SLOT_CHOICES)"))
        // 档位**不缓存**（与渲染侧 `engineScopedForCapture` 的"每次下发前读一次"同款口径）
        assertTrue("档位必须每次组合读一次 prefs（不做 remember 缓存）",
            full.contains("val engineOverride = engineOverrideOf(prefs)"))
    }
}
