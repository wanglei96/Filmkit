package com.filmkit.app.ui

import com.filmkit.app.camera.BuiltinPresets
import com.filmkit.app.camera.CAMERA_PATH_SLOTS
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.applyBuiltinOverrides
import com.filmkit.app.camera.cameraPresetParams
import com.filmkit.app.camera.effectivePresetEvOffset
import com.filmkit.app.camera.presetDeletable
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ PS-5：**分组勾选树**的纯逻辑护栏（规格来源 =
 * `docs/reports/preset-panel-interaction-RESEARCH.md` §2）。
 *
 * 用户 2026-10-11 真机投诉："预设面板交互逻辑太差，**全是滑杆**"。调研裁定（LrC / Capture One /
 * darktable / RawTherapee 四家一致）：把两件事拆成两个**正交**动作 ——
 *   (a)「这条预设**包含哪些**参数」= 勾选；
 *   (b)「已包含的某项**调到多少**」= 点开那一行再调（滑杆只出现在点开的那一行里）。
 *
 * 本测试钉住其中**可机器判定**的部分（对应调研 §2.7 的判据 1/2/3/4/5）：
 *  1. `sectionCheckState` 三态（0/n、k/n、n/n）+ 组外槽不参与计数；
 *  2. **勾选 = 加入**（值 = 当前生效值 ⇒ 画面一位不动）+ 取消勾选 = 回基线；
 *  3. 整组 / 全不选 = **一次提交**（返回单个改动集，不是一个槽一个）；
 *  4. 值等于基准 ⇒ 稀疏化丢弃 ⇒ 勾选态自动回 `☐`（不变量 I3，与 LrC **有意不同**）；
 *  5. **首屏可见滑杆数 = 0**（默认全部收起）与**未勾选的行绝不渲染滑杆**（`showsSliderRow`）；
 *  6. 内置「全不选 + 保存」之后仍能「恢复默认」回出厂（覆盖层语义不被破坏）。
 *
 * 真实数据来源 = `app/src/main/assets/presets/builtin.json`（工作目录 = `app/`，与
 * [CameraPresetEditTest] / [CameraPresetPanelUiTest] 同一口径）。
 */
class CameraPresetCheckTreeTest {

    private val builtins = BuiltinPresets.parse(File("src/main/assets/presets/builtin.json").readText())

    private fun builtin(name: String): EditorPreset =
        builtins.firstOrNull { it.name == name } ?: error("内置包里没有「$name」")

    private fun userPreset(id: String, slots: Map<Int, Double> = emptyMap()) =
        EditorPreset(id = id, name = "预设 $id", slots = slots)

    /** 没有改动时的生效参数（= 预设配方展开，未包含的槽 = 标准基线）。 */
    private fun paramsOf(p: EditorPreset): DoubleArray = cameraPresetParams(p)

    /** 带改动时的生效参数（与面板 `onPreview` 下发的同一份口径）。 */
    private fun paramsOf(p: EditorPreset, ov: CameraParamOverrides): DoubleArray =
        cameraPresetParams(p, ov.values, ov.removed)

    /** 一次勾选（`null` = 还没有任何未保存改动 —— 与面板的初始状态同型）。 */
    private fun check(p: EditorPreset, vararg slots: Int): CameraParamOverrides {
        val none: CameraParamOverrides? = null
        return none.checkSlots(p, slots.toList(), paramsOf(p))
    }

    /** 一次取消勾选。 */
    private fun uncheck(p: EditorPreset, from: CameraParamOverrides?, vararg slots: Int): CameraParamOverrides =
        from.uncheckSlots(p, slots.toList())

    // ── ① 组头三态（调研 §2.3 C4 / 不变量 I5）────────────────────────────────

    @Test
    fun sectionCheckStateIsThreeStateAndIgnoresForeignSlots() {
        val group = listOf(0, 1, 4)
        assertEquals("一项都没勾 ⇒ ☐", CheckState.NONE, sectionCheckState(emptySet(), group))
        assertEquals("勾了一部分 ⇒ ▣", CheckState.SOME, sectionCheckState(setOf(1), group))
        assertEquals("全勾 ⇒ ☑", CheckState.ALL, sectionCheckState(setOf(0, 1, 4), group))
        assertEquals(
            "组外的槽不算数（否则别的组的项会把这一组顶成全勾，整组勾选会误删/误加）",
            CheckState.SOME,
            sectionCheckState(setOf(1, 99), group),
        )
        assertEquals("☑", triCheckText(CheckState.ALL))
        assertEquals("▣", triCheckText(CheckState.SOME))
        assertEquals("☐", triCheckText(CheckState.NONE))
        assertEquals("☑", checkBoxText(true))
        assertEquals("☐", checkBoxText(false))
    }

    /**
     * 三态框点一下的语义（I5，与 RawTherapee `everythingToggled` 同义）：**非全勾 ⇒ 全勾**，
     * **全勾 ⇒ 全不勾** —— 半勾绝不停在半勾（那样用户点一下会看不出任何变化）。
     */
    @Test
    fun tappingASectionTriStateNeverStaysInconsistent() {
        val group = listOf(0, 1, 4)
        for (included in listOf(emptySet(), setOf(1), setOf(0, 1, 4))) {
            val wantCheck = sectionCheckState(included, group) != CheckState.ALL
            val next = if (wantCheck) included + group else included - group.toSet()
            val state = sectionCheckState(next, group)
            assertEquals(
                "点三态框之后必须落到 ☑ 或 ☐（$included ⇒ $state）",
                if (wantCheck) CheckState.ALL else CheckState.NONE,
                state,
            )
        }
    }

    // ── ② 勾选 = 加入 / 取消 = 移除（调研 §2.4 #1/#2）────────────────────────

    /** **勾选 = 加入，且画面一位不动**（"值就是当前值"）。 */
    @Test
    fun checkingASlotAddsItWithoutTouchingThePicture() {
        val p = builtin("徕卡一瞬")
        val before = paramsOf(p)
        val ov = check(p, 20, 21, 22, 26, 66)
        for (s in listOf(20, 21, 22, 26, 66)) {
            assertTrue("[$s] 勾上之后必须算「已包含」", ov.values.containsKey(s))
            assertEquals("[$s] 记的是**当前生效值**", before[s], ov.values.getValue(s), 1e-9)
        }
        assertArrayEquals("勾选不改变画面（套用时保持不动的语义不变）", before, paramsOf(p, ov), 1e-9)
        assertTrue("有未保存改动 ⇒ 「保存到预设」可点", !ov.isEmpty)
    }

    /** 取消勾选 = 回基线：画面立刻回标准基线值，且保存时该槽不进 `slots`。 */
    @Test
    fun uncheckingASlotBringsThePictureBackToBaseline() {
        val p = userPreset("p_1", mapOf(4 to 1.2, 26 to 20.0))
        assertEquals("前提：预设里带着这两项", 1.2, paramsOf(p)[4], 1e-9)
        val ov = uncheck(p, null, 4)
        assertEquals("移除后画面回基线", CameraPresets.standard()[4], paramsOf(p, ov)[4], 1e-9)
        assertEquals("其余项一位不动", 20.0, paramsOf(p, ov)[26], 1e-9)
        assertFalse("保存后这条预设不再包含对比度", cameraPresetSlotsFor(p, ov.values, ov.removed).containsKey(4))
        assertTrue("对比度进了「已移除」", 4 in ov.removed)
    }

    /** 移除之后又勾回来 ⇒ 撤销那次移除（用户刚勾上，预设显然又包含它了）。 */
    @Test
    fun recheckingASlotCancelsTheRemoval() {
        val p = userPreset("p_1", mapOf(4 to 1.2))
        val removed = uncheck(p, null, 4)
        val back = removed.checkSlots(p, listOf(4), paramsOf(p, removed))
        assertFalse("勾回来 ⇒ 不再是「已移除」", 4 in back.removed)
        assertTrue("值取**当前生效值**（移除后 = 基线 1.0，不是原来的 1.2）", back.values.containsKey(4))
        assertEquals(CameraPresets.standard()[4], back.values.getValue(4), 1e-9)
    }

    /**
     * 不可调的项**照样可勾选**（不变量 I6，与 LrC 灰显 `Masking` 同型）：颗粒 `[26]` 在拍照
     * 链路里生效但面板没有滑杆 ⇒ 能进 / 能出预设，只是值灰显 + 一行原因。
     */
    @Test
    fun slotsWithoutASliderCanStillBeCheckedIn() {
        assertEquals(CameraParamAdjustability.NO_SLIDER, cameraParamAdjustability(26))
        assertNotNull("值灰显 + 原因小字（不做假控件）", cameraParamAdjustabilityNote(26))
        assertFalse("没有滑杆 ⇒ 永不渲染滑杆", showsSliderRow(checked = true, tunable = 26 in CAMERA_TUNABLE_SLOT_PARAM, expanded = true))

        val p = builtin("徕卡一瞬")
        val slots = cameraTreeSlots(p.slots.keys)
        assertTrue("[26] 在树上（拍照链路读它）", 26 in slots)
        assertFalse("[26] 没有滑杆", 26 in CAMERA_TUNABLE_SLOT_PARAM)
        val ov = check(p, 26)
        assertTrue("照样能勾进预设", ov.values.containsKey(26))
    }

    // ── ③ 整组 / 全选：一次提交（调研 §2.4 #8/#10/#11）────────────────────────

    /** 整组勾选 = **一个**改动集（不是每槽一个），组内每项都进。 */
    @Test
    fun checkingAWholeSectionYieldsOneOverrideSet() {
        val p = builtin("徕卡一瞬")
        val groupSlots = cameraParamSections(p.slots, cameraTreeSlots(p.slots.keys))
            .first { it.title == "基础调整" }.rows.map { it.slot }
        assertTrue("前提：基础调整组里确实有未包含的项", groupSlots.size > p.slots.keys.count { it in groupSlots })

        val ov = (null as CameraParamOverrides?).checkSlots(p, groupSlots, paramsOf(p))
        assertEquals("整组勾选后该组必须是全勾", CheckState.ALL, sectionCheckState(ov.values.keys, groupSlots))
        assertEquals("组内每个槽都在改动集里（一次算完）", groupSlots.toSet(), groupSlots.filter { it in ov.values }.toSet())
        assertTrue("组内槽一个都不该留在「已移除」", groupSlots.none { it in ov.removed })
    }

    /** `[全不选]` = LrC 的 `Check None`：画面回到"无预设覆盖"的相机参数。 */
    @Test
    fun checkNoneBringsEverySlotBackToBaseline() {
        val p = builtin("徕卡一瞬")
        val tree = cameraTreeSlots(p.slots.keys)
        val ov = (null as CameraParamOverrides?).uncheckSlots(p, tree.toList())
        assertArrayEquals(
            "全不选 ⇒ 画面 == 标准基线（这条预设不再覆盖任何参数）",
            CameraPresets.standard(),
            paramsOf(p, ov),
            1e-9,
        )
        assertTrue("保存后这条预设不含任何参数", cameraPresetSlotsFor(p, ov.values, ov.removed).isEmpty())
        assertEquals("[0] 曝光偏移一并回 0（不是「把曝光打到 0」而是「不含这一项」）", 0.0, paramsOf(p, ov)[0], 1e-9)
        // 全不选之后**树还在**（未包含的项以「☐」出现）⇒ 用户勾得回来，不会把自己锁在门外
        val again = cameraParamSections(emptyMap(), tree).flatMap { it.rows }
        assertEquals("树上每一行仍在（未包含 ⇒ 值位「—」）", tree.size, again.size)
        assertTrue(again.all { it.value == null })
    }

    /** `[全选]` = LrC 的 `Check All`：树上每一项都进（值位全部出现读数）。 */
    @Test
    fun checkAllIncludesEveryRowOnTheTree() {
        val p = builtin("徕卡黑白")
        val tree = cameraTreeSlots(p.slots.keys)
        val ov = (null as CameraParamOverrides?).checkSlots(p, tree.toList(), paramsOf(p))
        val rows = cameraParamSections(ov.values, tree).flatMap { it.rows }
        assertTrue("全选后值位全部有读数（没有「—」）", rows.all { it.value != null })
        assertEquals("树上所有槽都在改动集里", tree, ov.values.keys)
    }

    // ── ④ 稀疏化 ⇒ 勾选态自动回 ☐（不变量 I3，与 LrC 有意不同）────────────────

    /**
     * ★ 已知语义差异（调研 §2.5 I3）：值是**差异**不是快照 ⇒ "包含一个等于基准的值"在相机链路里
     * 与"不包含"没有任何可观察差异。勾选能立刻看到 `☑`（有反应），但**保存时**该槽被稀疏化丢弃
     * ⇒ 重新打开时它又显示 `☐`。拖动路径（`afterEdit`）同样：拖回原值 ⇒ 行自动变 `☐`。
     */
    @Test
    fun valuesEqualToBaselineAreSparsifiedAwayOnSave() {
        val p = userPreset("p_1", mapOf(4 to 1.2))
        val neutral = check(p, 26) // [26] 当前 = 标准基线 ⇒ 值没有差异
        assertTrue("勾选立刻有反应（☑）", neutral.values.containsKey(26))
        assertFalse(
            "保存时被稀疏化丢掉 ⇒ 该行回到 ☐（与 LrC「包含但值 = 0」有意不同）",
            cameraPresetSlotsFor(p, neutral.values, neutral.removed).containsKey(26),
        )

        val real = check(p, 4) // [4] 当前 = 1.2 ≠ 基线 ⇒ 有差异，留得住
        assertEquals(1.2, cameraPresetSlotsFor(p, real.values, real.removed).getValue(4), 1e-9)

        // 拖动路径：拖回预设原值 ⇒ 改动集里没有它 ⇒ 行自动变 ☐（同一条机制，另一条入口）
        val none: CameraParamOverrides? = null
        val dragged = none.afterEdit(p, CAMERA_TUNABLE_SLOT_PARAM.getValue(4), contrastUiFromSlot(1.2))
        assertTrue("拖回原值 ⇒ 该槽从改动集消失", dragged.values.isEmpty())
    }

    /**
     * ★ PS-5：取消勾选**曝光**项 ⇒ 偏移回 0（不只是数组里的 `[0]`）。
     *
     * `setFilmParams(p, evOffset)` 会把 `p[0]` 整槽覆盖成 `base + 用户 EV + evOffset` ⇒ 只把
     * 数组 `[0]` 打回基线、而 evOffset 仍传预设原值时，**屏幕上的曝光不会变**（= 点了没反应）。
     * 数组与偏移两条口径必须同时回基线（PS-3 就写下的纪律，PS-5 的"取消勾选"把它逼出来了）。
     */
    @Test
    fun uncheckingTheExposureRowBringsTheOffsetBackToZero() {
        val p = userPreset("p_1", mapOf(0 to 0.5, 4 to 1.2))
        assertEquals("前提：预设自带 +0.5 EV 偏移", 0.5, effectivePresetEvOffset(p.slots, emptyMap()), 1e-9)

        val ov = uncheck(p, null, 0)
        assertEquals("数组 [0] 回基线", 0.0, paramsOf(p, ov)[0], 1e-9)
        assertEquals(
            "下发的偏移也必须回 0（否则曝光偷偷留着预设偏移 = 假控件）",
            0.0,
            effectivePresetEvOffset(p.slots, ov.values, ov.removed),
            1e-9,
        )
        // 点「全不选」时同理（曝光一并移除）
        val all = (null as CameraParamOverrides?).uncheckSlots(p, cameraTreeSlots(p.slots.keys).toList())
        assertEquals(0.0, effectivePresetEvOffset(p.slots, all.values, all.removed), 1e-9)
        // 反例：没移除时偏移照旧（这条修复不许把正常的预设偏移也打成 0）
        assertEquals(0.5, effectivePresetEvOffset(p.slots, emptyMap(), emptySet()), 1e-9)
    }

    /** 接线哨兵：实时下发必须把 `removed` 一起交给偏移计算（否则同上 = 假控件）。 */
    @Test
    fun previewPathPassesRemovalToTheEvOffset() {
        val src = File("src/main/java/com/filmkit/app/ui/CameraScreen.kt").readText()
        val fn = src.substringAfter("fun previewCameraParams(").take(1200)
        assertTrue(
            "previewCameraParams 必须传 removed（取消勾选曝光后偏移才会回 0）",
            fn.contains("effectivePresetEvOffset(selectedPreset.slots, values, removed)"),
        )
    }

    /**
     * 内置「全不选 + 保存」之后仍能「恢复默认」回出厂 —— 覆盖层语义**不被破坏**
     *（调研 §2.7 判据 4；这是"内置可改 + 只禁删"那条既有能力的回归哨兵）。
     */
    @Test
    fun builtinCanBeEmptiedAndThenRestoredToFactory() {
        val factory = builtin("徕卡一瞬")
        val emptied = factory.copy(slots = cameraPresetSlotsFor(factory, emptyMap(), cameraTreeSlots(factory.slots.keys)))
        assertTrue("「全不选 + 保存」之后配方为空", emptied.slots.isEmpty())

        val applied = applyBuiltinOverrides(listOf(factory), mapOf(factory.id to emptied)).single()
        assertTrue("改过的内置照样生效（空配方）", applied.slots.isEmpty())
        assertFalse("改过的内置**仍然不可删除**（覆盖层只换可改字段）", presetDeletable(applied))
        assertEquals(
            "「恢复默认」= 删掉覆盖层 ⇒ 立刻回出厂配方",
            factory.slots,
            applyBuiltinOverrides(listOf(factory), emptyMap()).single().slots,
        )
    }

    // ── ⑤ ★ 两条机器判据：首屏 0 滑杆 / 未勾选不渲染滑杆（调研 §2.7 判据 1/5）───

    /**
     * **判据②**：**未勾选的行不渲染 `EditorSlider`** —— 覆盖树上每一个槽 × 可调 / 不可调 ×
     * 展开 / 收起四种组合。"不是全是滑杆"就靠这条。
     */
    @Test
    fun uncheckedRowsNeverRenderASlider() {
        val rows = cameraTreeSlots(builtin("徕卡一瞬").slots.keys)
        for (slot in rows) {
            for (expanded in listOf(true, false)) {
                assertFalse(
                    "[$slot] 未勾选却渲染了滑杆（= 又回到「全是滑杆」）",
                    showsSliderRow(checked = false, tunable = slot in CAMERA_TUNABLE_SLOT_PARAM, expanded = expanded),
                )
            }
            assertFalse(
                "[$slot] 没有滑杆的项永远不渲染（ADR-12 禁止假滑杆）",
                showsSliderRow(checked = true, tunable = false, expanded = true),
            )
        }
    }

    /**
     * **判据①**：进入详情**首屏可见滑杆数 = 0** —— 面板的行级展开默认全部收起
     *（`openKey = null`）⇒ 每一行的 `expanded` 都是 false ⇒ 没有一行满足渲染条件。
     *
     * 源码侧的同一判据见 [CameraPresetPanelUiTest.firstScreenOfTheDetailPanelHasNoSlider]。
     */
    @Test
    fun firstScreenOfTheDetailPanelShowsZeroSliders() {
        for (p in builtins + userPreset("p_1", mapOf(4 to 1.2, 26 to 20.0))) {
            val rows = cameraTreeSlots(p.slots.keys)
            val visible = rows.count { slot ->
                showsSliderRow(
                    checked = slot in p.slots,
                    tunable = slot in CAMERA_TUNABLE_SLOT_PARAM,
                    expanded = false,        // ★ 默认 = 全部收起
                )
            }
            assertEquals("「${p.name}」首屏不该有任何滑杆（默认全收起）", 0, visible)
            // 反面：只要用户点开**一项**，那一项的滑杆就该出现（有反应，不是假控件）
            val one = rows.first { it in CAMERA_TUNABLE_SLOT_PARAM && it in p.slots }
            assertTrue(
                "[$one] 点开之后必须出现滑杆（有反应，不是假控件）",
                showsSliderRow(checked = one in p.slots, tunable = true, expanded = true),
            )
        }
    }

    /** 未包含的项**照样出现在树上**（值位「—」），绝不是"藏起来"（调研 §1 表 3 的裁决）。 */
    @Test
    fun theTreeKeepsEveryRowAndNeverFakesUnsetValues() {
        val p = builtin("徕卡一瞬")
        val tree = cameraTreeSlots(p.slots.keys)
        assertTrue("拍照链路读的槽一个不少", tree.containsAll(CAMERA_PATH_SLOTS))
        assertFalse("[34] LUT 强度由 lutStrength 承载 ⇒ 不进树", LUT_STRENGTH_SLOT in tree)

        val sections = cameraParamSections(p.slots, tree)
        val rows = sections.flatMap { it.rows }
        assertEquals("树上每一槽一行（未包含的也在）", tree.size, rows.size)
        assertEquals("槽位不重复", tree.size, rows.map { it.slot }.toSet().size)
        for (r in rows) {
            if (p.slots.containsKey(r.slot)) {
                assertNotNull("已包含 ⇒ 必须有读数", r.value)
            } else {
                assertNull("未包含 ⇒ 值位为 null（UI 显示「—」），绝不用当前相机值冒充", r.value)
            }
        }
        assertTrue("这条预设同时有已包含与未包含的项（这条测试才有意义）", rows.any { it.value == null })
    }
}

/** UI 值 → 槽位值（与参数表同源，用于"拖回原值"的用例）。 */
private fun contrastUiFromSlot(slot: Double): Float = com.filmkit.app.camera.contrastUi(slot)
