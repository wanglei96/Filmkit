package com.filmkit.app.ui

import com.filmkit.app.camera.BuiltinPresets
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.applyBuiltinOverrides
import com.filmkit.app.camera.cameraPresetParams
import com.filmkit.app.camera.effectivePresetEvOffset
import com.filmkit.app.camera.expSlot
import com.filmkit.app.camera.presetDeletable
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ PS-6a：**预设主视图（图 2）+ 常用参数**的纯逻辑护栏。
 *
 * 用户 2026-10-12 真机实测（v0.11.21）："预设参数勾你妈的勾选，丑死……**点击预设按图 2 那样
 * 展示**，并提供几个常用参数调节（**lut强度，颗粒开、关、档位，柔光档位，光晕档位，暗角调节**）"。
 * ⇒ PS-5 的分组勾选树（连同 `checkSlots` / `uncheckSlots` / `sectionCheckState` 等纯函数）已删，
 * 本测试盯的是**替代它的那两条路**能不能真的调、调完能不能真的存：
 *
 *  1. **档位映射**（`GrainLevel` / `QuickLevelParam`）：每个档位的数值有出处（内置预设逐字 /
 *     引擎中性值 / 面板量程），且**写进去必须读得回来**（闭环）；
 *  2. **常用参数清单**（`QUICK_ADJUST_SLOTS` / `presetQuickPath` / `quickAdjustLabelOf`）：
 *     用户点名的 5 项一个不少，且"点了有没有反应"是**可判定**的（不做假控件）；
 *  3. **「当前搭配」清单**（`presetOverviewRows` / `presetOverviewText`）：只读概览的折叠规则
 *     （`颗粒 → 细颗粒 · 数量 20`）与"绝不用当前相机值冒充"；
 *  4. **写值口径**：档位 / 滑杆都经 `EditorParam.write` → `afterEdit` 记账 → 保存回预设
 *     （`[34]` LUT 强度必须显式带回 `lutStrength`，否则拖了存不上）。
 *
 * 真实数据来源 = `app/src/main/assets/presets/builtin.json`（工作目录 = `app/`，与
 * [CameraPresetEditTest] / [CameraPresetPanelUiTest] 同一口径）。
 */
class CameraPresetMainViewTest {

    private val builtins = BuiltinPresets.parse(File("src/main/assets/presets/builtin.json").readText())

    private fun builtin(name: String): EditorPreset =
        builtins.firstOrNull { it.name == name } ?: error("内置包里没有「$name」")

    private fun userPreset(id: String, slots: Map<Int, Double> = emptyMap()) =
        EditorPreset(id = id, name = "预设 $id", slots = slots)

    // ── ① 颗粒档位（用户点名："颗粒开、关、档位"）──────────────────────────────

    /**
     * 档位数值的依据**逐条对着 `builtin.json` 与引擎中性值验**（不是自说自话）：
     *  - 细颗粒 20/160 = 内置「徕卡一瞬」（note "细颗粒"）；
     *  - 中颗粒 30/400 = 内置「理光负片」（note "中颗粒"），400 同时是 `[27]` 的恒等值；
     *  - 粗颗粒 40/800 = 内置「徕卡黑白」的强度 40（note "明显颗粒"）+ 400 的下一 ISO 标准档；
     *  - 关闭 0/400 = 引擎中性值（`[26] = 0` 整段旁路）。
     */
    @Test
    fun grainLevelsFollowTheBuiltinPresetsAndRoundTripThroughBothSlots() {
        assertEquals(GrainLevel.FINE, grainLevelOf(20.0))
        assertEquals(GrainLevel.MEDIUM, grainLevelOf(30.0))
        assertEquals(GrainLevel.COARSE, grainLevelOf(40.0))
        assertEquals(GrainLevel.OFF, grainLevelOf(0.0))
        assertEquals("关 = 引擎中性值（[26] = 0 整段旁路）", 0.0, GrainLevel.OFF.amount, 1e-9)
        assertEquals(
            "中颗粒的尺寸 = 引擎恒等值（CameraPresets 的标定起点）",
            CameraPresets.standard()[GRAIN_SIZE_SLOT],
            GrainLevel.MEDIUM.iso,
            1e-9,
        )
        assertEquals(
            "档位名与图 2 一致",
            listOf("关闭", "细颗粒", "中颗粒", "粗颗粒"),
            GrainLevel.entries.map { it.label },
        )
        // 三条内置的 grain_amount 读出来的档位必须与 JSON 的 note 一致（20/30/40）
        assertEquals(GrainLevel.FINE, grainLevelOf(builtin("徕卡一瞬").slots.getValue(GRAIN_AMOUNT_SLOT)))
        assertEquals(GrainLevel.MEDIUM, grainLevelOf(builtin("理光负片").slots.getValue(GRAIN_AMOUNT_SLOT)))
        assertEquals(GrainLevel.COARSE, grainLevelOf(builtin("徕卡黑白").slots.getValue(GRAIN_AMOUNT_SLOT)))

        // 闭环：档位写进数组 ⇒ 两个槽都是档位自己的值 ⇒ 读回同一档（写值走 EditorParam.write）
        for (lv in GrainLevel.entries) {
            val film = CameraPresets.standard()
            GrainLevelParam.write(film, lv.ordinal.toFloat())
            assertEquals("[$lv] 强度", lv.amount, film[GRAIN_AMOUNT_SLOT], 1e-9)
            assertEquals("[$lv] 尺寸（ISO 语义）", lv.iso, film[GRAIN_SIZE_SLOT], 1e-9)
            assertEquals("[$lv] 必须读回同一档", lv, grainLevelOf(film))
        }

        // 一条档位 = **两个槽一起**进改动集（与"一条滑杆同写三槽"的饱和度同型）
        val none: CameraParamOverrides? = null
        val ov = none.afterEdit(userPreset("p_1"), GrainLevelParam, GrainLevel.COARSE.ordinal.toFloat())
        assertEquals(40.0, ov!!.values.getValue(GRAIN_AMOUNT_SLOT), 1e-9)
        assertEquals(800.0, ov.values.getValue(GRAIN_SIZE_SLOT), 1e-9)
    }

    // ── ② 柔光 / 光晕 / 暗角档位 ──────────────────────────────────────────────

    /**
     * 三个档位行（柔光 / 光晕 / 暗角）：档位名 = 图 2 字面（`轻柔光` / `中等光晕` / `强烈暗角` /
     * `关闭`），四档的 UI 值写进去必须读回同一档；**关闭 = 引擎中性值 0**（整链旁路）。
     *
     * 暗角还有一条**内置依据**：三条内置的 `vignette_strength`（0.18 / 0.24 / 0.28）都落在"轻"档。
     */
    @Test
    fun levelChipsRoundTripAndTheirNamesMatchTheSketch() {
        assertEquals(
            "档位名 = 图 2 字面（轻柔光 / 中等光晕 / 关闭）",
            listOf("关闭", "轻柔光", "中等柔光", "强烈柔光"),
            GLOW_LEVEL_PARAM.names,
        )
        assertEquals(listOf("关闭", "轻微光晕", "中等光晕", "强烈光晕"), HALO_LEVEL_PARAM.names)
        assertEquals(listOf("关闭", "轻微暗角", "中等暗角", "强烈暗角"), VIGNETTE_LEVEL_PARAM.names)
        assertEquals("档位名与档位数一一对应", QUICK_LEVEL_PARAMS.map { it.names.size }, QUICK_LEVEL_PARAMS.map { it.levels.size })
        for (q in QUICK_LEVEL_PARAMS) {
            val param = CAMERA_TUNABLE_SLOT_PARAM.getValue(q.slot)
            assertEquals("${q.label}：四档（关 / 轻 / 中 / 强）", 4, q.levels.size)
            assertEquals("${q.label}：关 = 引擎中性值 0", 0f, q.levels[0], 1e-6f)
            for (i in q.levels.indices) {
                val film = CameraPresets.standard()
                param.write(film, q.levels[i])
                assertEquals(
                    "${q.label} 第 $i 档写进去"
                        + "（槽 ${q.slot}）后 UI 读数必须一致",
                    q.levels[i],
                    param.read(film),
                    1e-6f,
                )
                assertEquals("${q.label} 第 $i 档必须读回同一档", i, nearestLevel(param.read(film), q.levels))
            }
        }
        // 内置依据：三条内置的暗角（0.18 / 0.24 / 0.28）都落在"轻"档（0.2）
        for (b in builtins) {
            if (b.slots[VIGNETTE_LEVEL_PARAM.slot] == null) continue
            val ui = CAMERA_TUNABLE_SLOT_PARAM.getValue(VIGNETTE_LEVEL_PARAM.slot).read(cameraPresetParams(b))
            assertEquals(
                "内置「${b.name}」的暗角强度 $ui 应落在轻档",
                1,
                nearestLevel(ui, VIGNETTE_LEVEL_PARAM.levels),
            )
        }
    }

    // ── ③ 常用参数清单：用户点名的 5 项 + "点了有没有反应"可判定 ────────────────

    /**
     * `QUICK_ADJUST_SLOTS` = 用户点名的 5 项（LUT 强度 / 颗粒 / 柔光 / 光晕 / 暗角）。
     *
     * `presetQuickPath` 是"**每个能点的都必须有反应**"的机器判据：常用项 ⇒ QUICK（提示去下面
     * 哪个档位调）；有滑杆的项 ⇒ SLIDER（下方出那一条滑杆）；两样都没有 ⇒ NONE（**不注册手势**，
     * 行下写明原因 = `cameraParamAdjustabilityNote`）。
     */
    @Test
    fun quickAdjustCoversExactlyTheFiveItemsTheUserNamed() {
        assertEquals(
            "用户点名的 5 项：LUT 强度 / 颗粒（两个槽）/ 柔光 / 光晕 / 暗角",
            linkedSetOf(34, 26, 27, 95, 62, 66),
            QUICK_ADJUST_SLOTS,
        )
        for (s in QUICK_ADJUST_SLOTS) {
            assertNotNull("[$s] 必须能说清属于哪一项（提示文案要用）", quickAdjustLabelOf(s))
            assertEquals("[$s] 属于常用项 ⇒ 快捷路径 = QUICK", PresetQuickPath.QUICK, presetQuickPath(s))
        }
        assertEquals("滤镜强度", quickAdjustLabelOf(LUT_STRENGTH_SLOT))
        assertEquals("颗粒", quickAdjustLabelOf(GRAIN_SIZE_SLOT))
        assertEquals("柔光", quickAdjustLabelOf(GLOW_LEVEL_PARAM.slot))
        assertEquals("光晕", quickAdjustLabelOf(HALO_LEVEL_PARAM.slot))
        assertEquals("暗角", quickAdjustLabelOf(VIGNETTE_LEVEL_PARAM.slot))
        // 其余项：有滑杆 ⇒ SLIDER；两样都没有 ⇒ NONE **且必须给出原因**（不做假控件）
        assertEquals(PresetQuickPath.SLIDER, presetQuickPath(4))
        assertEquals(PresetQuickPath.NONE, presetQuickPath(38))
        assertNotNull("「白点」没有滑杆 ⇒ 行下必须写明原因", cameraParamAdjustabilityNote(38))
        assertEquals(PresetQuickPath.NONE, presetQuickPath(69))
        assertNotNull("拍照链路不读的槽 ⇒ 同样写明原因", cameraParamAdjustabilityNote(69))
    }

    // ── ④ 「当前搭配」清单（图 2 的只读概览）──────────────────────────────────

    /**
     * 清单 = **这条预设包含的项**逐行（未包含的项根本不出现 —— "当前搭配"的语义），
     * 常用项折叠成"档位名 · 数值"（图 2 的 `颗粒 → 中颗粒 · 数量 30`）：
     *  - 第一行 = 滤镜（LUT 中文名 + 强度）；
     *  - `[27]` 颗粒尺寸并进 `[26]` 那一行；
     *  - 档位行（柔光 / 光晕 / 暗角）非关时带数值，关闭时只写「关闭」。
     */
    @Test
    fun overviewRowsFoldLevelsAndNeverShowUnsetSlots() {
        val p = builtin("徕卡一瞬")
        val rows = presetOverviewRows(p.slots, presetLutLine(p), "徕卡 M9")
        assertEquals("图 2 的第一行 = 滤镜（LUT 名 + 强度）", "滤镜", rows.first().label)
        assertEquals("徕卡 M9 · 30", rows.first().value)
        val grain = rows.first { it.label == "颗粒" }
        assertEquals("内置「徕卡一瞬」= 20 ⇒ 细颗粒 + 数量（数值与清单同源）", "细颗粒 · 数量 20", grain.value)
        assertFalse("颗粒尺寸并进颗粒那一行，不再单出一行", rows.any { it.slot == GRAIN_SIZE_SLOT })
        val vig = rows.first { it.label == "暗角" }
        assertEquals("轻微暗角 · 18", vig.value)
        assertEquals(
            "一行一槽（[27] 并入颗粒行）+ 滤镜行 ⇒ 行数 = 预设项数 − 1",
            p.slots.size - 1,
            rows.count { it.slot != LUT_STRENGTH_SLOT },
        )
        assertTrue("空预设 ⇒ 清单为空（UI 显示一句实话）", presetOverviewRows(emptyMap()).isEmpty())
        // 没给 LUT 中文名 ⇒ 回落 id（资产被删的降级路径），不是空串
        assertEquals("leica_m9 · 30", presetOverviewRows(p.slots, presetLutLine(p)).first().value)
    }

    /** 描述段落（可展开收起）全部由真实数据拼成：来源 / 分组 / 项数 / LUT / 套用语义。 */
    @Test
    fun overviewTextIsBuiltFromRealData() {
        val t = presetOverviewText(builtin("徕卡一瞬"), "徕卡 M9")
        assertTrue("来源 + 分组：$t", t.startsWith("内置预设 · 分组「徕卡」"))
        assertTrue("项数与覆盖的分组：$t", t.contains("包含 17 项参数") && t.contains("基础调整"))
        assertTrue("LUT 与强度：$t", t.contains("滤镜 徕卡 M9（强度 30）"))
        assertTrue("套用语义（未包含 = 保持不动）：$t", t.contains("未包含的参数保持当前值不动"))
        val empty = presetOverviewText(userPreset("p_2"))
        assertTrue("空预预设要说实话：$empty", empty.contains("不含参数（套用时其余参数保持不动）"))
        assertTrue("没有 LUT 也要说实话：$empty", empty.contains("不含滤镜"))
    }

    // ── ⑤ 写值口径：档位 / 滑杆 → 预设（含稀疏化与 LUT 强度）────────────────────

    /**
     * 档位写值**走的是既有那条 `EditorParam` 通路**，保存口径与拖动完全一致：
     *  - 中颗粒 ⇒ `[26] = 30` 进预设；`[27] = 400` 恰好等于引擎恒等值 ⇒ **被稀疏化丢弃**
     *    （既有口径：套用时该项保持当前尺寸，不是"强制 400"）；
     *  - 关闭档 ⇒ 强度回中性值 ⇒ 同样被丢弃（= 这条预设不再覆盖颗粒）。
     */
    @Test
    fun levelWriteGoesThroughTheEditorParamAndSavesBackSparsely() {
        val p = userPreset("p_1", mapOf(4 to 1.2))
        val none: CameraParamOverrides? = null
        val ov = none.afterEdit(p, GrainLevelParam, GrainLevel.MEDIUM.ordinal.toFloat())
        assertEquals(30.0, ov!!.values.getValue(GRAIN_AMOUNT_SLOT), 1e-9)
        val saved = cameraPresetSlotsFor(p, ov.values, ov.removed)
        assertEquals("档位写进的强度进预设", 30.0, saved.getValue(GRAIN_AMOUNT_SLOT), 1e-9)
        assertFalse(
            "中颗粒的尺寸 400 == 引擎恒等值 ⇒ 保存时被稀疏化丢弃（既有口径）",
            saved.containsKey(GRAIN_SIZE_SLOT),
        )
        assertEquals("其余字段一位不动", 1.2, saved.getValue(4), 1e-9)

        val off = none.afterEdit(p, GrainLevelParam, GrainLevel.OFF.ordinal.toFloat())
        assertTrue("点「关闭」⇒ 回到中性值 ⇒ 改动集为空（不是「存一个 0」）", off!!.values.isEmpty())
    }

    /**
     * ★ LUT 强度 `[34]`（用户点名第一项："lut强度"）—— 两条容易假掉的地方都钉住：
     *
     *  1. **记账**：`[34]` 由 `lutStrength` 承载、不进 `slots` ⇒ [cameraPresetParams] 展开出来的
     *     `[34]` 恒为基线 1.0，`afterEdit` 的 recipe 比对会把"拖到 100%"当成没改
     *     ⇒ 必须用 [afterLutStrengthEdit] 与**预设自己的强度**比；
     *  2. **保存**：`[34]` 不进 `slots` ⇒ 保存时必须显式带回 `lutStrength`（[saveSlotsWithLutStrength]），
     *     否则用户拖了强度、点了保存、重新打开发现没变（假控件）。
     */
    @Test
    fun lutStrengthEditIsRecordedEvenAtTheBaselineValueAndSavedBack() {
        val p = userPreset("p_1").copy(lutId = "leica_m9", lutStrength = 0.3)
        val none: CameraParamOverrides? = null
        val ov = none.afterLutStrengthEdit(p, 1.0f)
        assertEquals("拖到 100%（恰好等于展开基线）也必须记账", 1.0, ov!!.values.getValue(LUT_STRENGTH_SLOT), 1e-9)
        assertFalse(
            "拖回预设原值 ⇒ 撤销这次调整（与 afterEdit 同一条纪律）",
            none.afterLutStrengthEdit(p, 0.3f)!!.values.containsKey(LUT_STRENGTH_SLOT),
        )
        assertEquals("保存：带回 lutStrength", 1.0, saveSlotsWithLutStrength(p, ov).lutStrength, 1e-9)
        assertEquals("没有改动 ⇒ 原样", 0.3, saveSlotsWithLutStrength(p, null).lutStrength, 1e-9)
        assertEquals("id 不变（保存通路按 id 找那条预设）", p.id, saveSlotsWithLutStrength(p, ov).id)
    }

    /**
     * 曝光 `[0]`：改值 ⇒ 数组与下发的 `evOffset` **两条口径同时**跟上（PS-5 修的那条）；
     * 拖回 0 ⇒ 这条预设不再改变曝光（`[0] = 0` 被稀疏化丢弃，与"缺失字段 = 不改变"同义）。
     */
    @Test
    fun exposureOffsetFollowsTheEditedValue() {
        val p = userPreset("p_1", mapOf(0 to 0.5, 4 to 1.2))
        val none: CameraParamOverrides? = null
        assertEquals("前提：预设自带 +0.5 EV 偏移", 0.5, effectivePresetEvOffset(p.slots, emptyMap()), 1e-9)
        val ev = CAMERA_TUNABLE_SLOT_PARAM.getValue(0)
        val ui = 0.25f                                   // UI 域（±1 = ±3 EV）
        val up = none.afterEdit(p, ev, ui)
        assertEquals(expSlot(ui), up!!.values.getValue(0), 1e-9)
        assertEquals(
            "下发的偏移 = 用户拖出的值（数组与偏移同一条口径）",
            expSlot(ui),
            effectivePresetEvOffset(p.slots, up.values, up.removed),
            1e-9,
        )
        val back = none.afterEdit(p, ev, 0f)             // 拖回 0 = 这条预设不改变曝光
        assertEquals(0.0, back!!.values.getValue(0), 1e-9)
        assertEquals(0.0, cameraPresetParams(p, back.values, back.removed)[0], 1e-9)
        assertEquals(0.0, effectivePresetEvOffset(p.slots, back.values, back.removed), 1e-9)
        assertFalse(
            "保存后预设不再含曝光（= 不改变曝光，不是把曝光打到 0）",
            cameraPresetSlotsFor(p, back.values, back.removed).containsKey(0),
        )
    }

    // ── ⑥ 不回退的既有能力：内置可改 / 只禁删 / 恢复默认 ───────────────────────

    /**
     * 用户 2026-10-11 拍板"内置最多限制不能删除"：本卡把参数区从勾选树换成常用参数之后，
     * 这条能力必须一条不少（改内置 ⇒ 覆盖层；「恢复默认」⇒ 回出厂；删除仍置灰）。
     */
    @Test
    fun builtinsStayEditableRestorableAndUndeletable() {
        val factory = builtin("徕卡黑白")
        val none: CameraParamOverrides? = null
        val ov = none.afterEdit(factory, GrainLevelParam, GrainLevel.FINE.ordinal.toFloat())
        val edited = factory.copy(slots = cameraPresetSlotsFor(factory, ov!!.values, ov.removed))
        assertEquals("改过的内置：颗粒强度 = 细颗粒档", 20.0, edited.slots.getValue(GRAIN_AMOUNT_SLOT), 1e-9)
        val applied = applyBuiltinOverrides(listOf(factory), mapOf(factory.id to edited)).single()
        assertEquals("覆盖层生效（内置能改）", 20.0, applied.slots.getValue(GRAIN_AMOUNT_SLOT), 1e-9)
        assertFalse("内置**唯一**受限的动作：删除", presetDeletable(applied))
        assertEquals(
            "「恢复默认」= 删掉覆盖层 ⇒ 立刻回出厂配方",
            factory.slots,
            applyBuiltinOverrides(listOf(factory), emptyMap()).single().slots,
        )
    }
}
