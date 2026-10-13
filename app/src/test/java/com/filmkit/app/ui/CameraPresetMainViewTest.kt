package com.filmkit.app.ui

import com.filmkit.app.camera.BuiltinPresets
import com.filmkit.app.camera.CAMERA_PATH_SLOTS
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.applyBuiltinOverrides
import com.filmkit.app.camera.cameraPresetParams
import com.filmkit.app.camera.effectivePresetEvOffset
import com.filmkit.app.camera.expSlot
import com.filmkit.app.camera.presetDeletable
import com.filmkit.app.camera.vigStrengthSlot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 *  1. **参数映射**（`GrainStrengthParam` / `QuickLevelParam`）：每个值都有出处（内置预设逐字 /
 *     引擎中性值 / 引擎量程），且**写进去必须读得回来**（闭环）；
 *  2. **常用参数清单**（`QUICK_ADJUST_SLOTS`）：用户点名的 5 项一个不少；
 *  3. **写值口径**：滑杆 / 档位都经 `EditorParam.write` → `afterEdit` 记账 → 保存回预设
 *     （`[34]` LUT 强度必须显式带回 `lutStrength`，否则拖了存不上）。
 *
 * ★ **PS-8 口径变更**（用户 2026-10-12/13："**第一级页面不需要提供全部参数，点击查看完整参数
 * 才提供**"）：一级页原先的「当前搭配」清单连同 `presetOverviewRows` / `presetOverviewText` /
 * `presetRowTap` 三态判据**整块删除** ⇒ 本文件里盯清单与值位口径的三条用例随之退役（它们守的
 * 控件已经不存在）；一级页的新形态（横向预设条 / 一行滑杆 / 四行分段控件 / 返回键分层）由
 * [Ps8PresetUiRedesignTest] 正面钉住，本文件继续守**纯逻辑**：档位映射、写值口径、内置纪律。
 *
 * ★ **PS-9 口径变更**（用户 2026-10-12："**暗角轻微和中等跟没有一样，强烈顶多算轻微**" +
 * "**颗粒改成滑杆吧**"）：
 *  - 颗粒：`GrainLevel` 四档枚举与 `GrainLevelParam`（一条档位同写 `[26]`+`[27]`）**删除**，
 *    换成只写 `[26]` 的 `GrainStrengthParam`（UI 0..1 ↔ 槽位 0..100，读数口径沿用
 *    `PresetDetail.kt` 对 `[26]` 的 `formatZero100`）；
 *  - 暗角：档位值从 0.20 / 0.40 / 0.70 改成 **0.35 / 0.65 / 1.0**（满量程，依据见
 *    `CameraPresetPanel.kt` 里 `VIGNETTE_LEVEL_PARAM` 上方的注释）。
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

    // ── ① 颗粒强度（★ PS-9：用户 2026-10-12："**颗粒改成滑杆吧**"）────────────────

    /**
     * ★ PS-9：颗粒从"四档（一条档位同写 `[26]`+`[27]`）"改成**一条只写 `[26]` 的强度滑杆**。
     *
     * 本用例守三件事（都是"改错了会假掉"的地方）：
     *  1. **口径不是新造的**：UI 0..1 ↔ 槽位 `[26]` 0..100，读数 = `formatZero100` ——
     *     与 `PresetDetail.kt` 的 `[26]`（`formatZero100((it / 100.0).toFloat())`）**同一个数**；
     *  2. **往返闭环 + 边界**：写进去必须读回同一个值；越界夹到端点、坏数据回落 0（= 关）；
     *  3. **`[27]` 不在一级页的写路径上**：拖颗粒只改 `[26]`，预设自己的 `[27]` 一位不动
     *     （用户要的是"强度滑杆"，不是"顺手把尺寸也改了"）。
     */
    @Test
    fun grainStrengthSliderUsesTheExistingSlotScaleAndNeverTouchesTheSizeSlot() {
        val p = userPreset("p_1", mapOf(GRAIN_AMOUNT_SLOT to 20.0, GRAIN_SIZE_SLOT to 160.0))
        assertEquals("UI 量程 = 0..1（对应槽位 0..100）", 0f, GRAIN_UI_RANGE.start, 1e-6f)
        assertEquals(1f, GRAIN_UI_RANGE.endInclusive, 1e-6f)

        // 口径：内置三条的 grain_amount（槽位 20 / 30 / 40）读出来的 UI 值 = 0.2 / 0.3 / 0.4
        //（与 `PresetDetail.kt` 的 `formatZero100((it / 100.0).toFloat())` 是同一个数：读数 20 / 30 / 40）
        assertEquals(0.2f, grainAmountUi(builtin("徕卡一瞬").slots.getValue(GRAIN_AMOUNT_SLOT)), 1e-3f)
        assertEquals(0.3f, grainAmountUi(builtin("理光负片").slots.getValue(GRAIN_AMOUNT_SLOT)), 1e-3f)
        assertEquals(0.4f, grainAmountUi(builtin("徕卡黑白").slots.getValue(GRAIN_AMOUNT_SLOT)), 1e-3f)
        assertEquals("滑杆读的是当前生效数组（含预设覆盖）", 0.2f, GrainStrengthParam.read(cameraPresetParams(builtin("徕卡一瞬"))), 1e-3f)
        assertEquals("读数 = 0..100 整数（formatZero100）", "20", GrainStrengthParam.format(0.2f))
        assertEquals("0 = 关（[26] = 0 整段旁路）", "0", GrainStrengthParam.format(0f))

        // 闭环：UI 值写进数组 ⇒ 槽位 = ui×100 ⇒ 读回同一个 UI 值
        for (ui in listOf(0f, 0.2f, 0.3f, 0.4f, 0.65f, 1f)) {
            val film = CameraPresets.standard()
            GrainStrengthParam.write(film, ui)
            assertEquals("[$ui] 槽位 = ui×100", ui * 100.0, film[GRAIN_AMOUNT_SLOT], 1e-6)
            assertEquals("[$ui] 必须读回同一个值", ui, GrainStrengthParam.read(film), 1e-6f)
        }
        // 边界：越界夹到端点、坏数据回落 0
        val film = CameraPresets.standard()
        GrainStrengthParam.write(film, 3f)
        assertEquals("越界 ⇒ 夹到 100（引擎量程上限）", 100.0, film[GRAIN_AMOUNT_SLOT], 1e-9)
        GrainStrengthParam.write(film, -1f)
        assertEquals("负数 ⇒ 夹到 0", 0.0, film[GRAIN_AMOUNT_SLOT], 1e-9)
        assertEquals("坏数据 ⇒ 0（= 关，不凭空加颗粒）", 0f, grainAmountUi(Double.NaN), 1e-6f)

        // `[27]` 不受影响：拖颗粒 ⇒ 改动集里只有 `[26]`（预设自己的尺寸一位不动）
        val none: CameraParamOverrides? = null
        val ov = none.afterEdit(p, GrainStrengthParam, 0.65f)
        // 容差 1e-3：UI 值是 Float（0.65f → Double = 65.00000298…），槽位语义是 0..100 的整数级
        assertEquals(65.0, ov!!.values.getValue(GRAIN_AMOUNT_SLOT), 1e-3)
        assertFalse(
            "★ 一级页的颗粒滑杆只写 `[26]`：`[27]` 尺寸不许进改动集（保持预设原值）",
            ov.values.containsKey(GRAIN_SIZE_SLOT),
        )
        val saved = cameraPresetSlotsFor(p, ov.values, ov.removed)
        assertEquals("保存：强度进预设", 65.0, saved.getValue(GRAIN_AMOUNT_SLOT), 1e-3)
        assertEquals("保存：尺寸仍是预设自己的 160", 160.0, saved.getValue(GRAIN_SIZE_SLOT), 1e-9)
    }

    // ── ② 柔光 / 光晕 / 暗角档位 ──────────────────────────────────────────────

    /**
     * 三个档位行（柔光 / 光晕 / 暗角）：档位名 = 图 2 字面（`轻柔光` / `中等光晕` / `强烈暗角` /
     * `关闭`），四档的 UI 值写进去必须读回同一档；**关闭 = 引擎中性值 0**（整链旁路）。
     *
     * ★ PS-9 暗角档位重定（用户 2026-10-12 真机实测："**暗角轻微和中等跟没有一样，强烈顶多算
     * 轻微**"）：旧值 0.20 / 0.40 / 0.70 是"内置最近整值 + 0.2 步进"推出来的，**只用到引擎量程
     * 的 70%** ⇒ 新值按**引擎满量程**给：轻 0.35 / 中 0.65 / **强 1.0（= `[66]` 的 clamp 下限）**。
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
        // ★ PS-9：暗角档位 = 引擎满量程口径（单调递增 + 强档踩在 `[66]` 的 clamp 下限 −1 上）
        assertEquals(
            "暗角四档（关 / 轻 / 中 / 强）",
            listOf(0f, 0.35f, 0.65f, 1.0f),
            VIGNETTE_LEVEL_PARAM.levels,
        )
        assertEquals(
            "★ 强档 = 引擎满量程（`vigStrengthSlot(1f)` = −1 = `FrameRenderer` 的 clamp 下限）",
            -1.0,
            vigStrengthSlot(VIGNETTE_LEVEL_PARAM.levels.last()),
            1e-9,
        )
        for (i in 1 until VIGNETTE_LEVEL_PARAM.levels.size) {
            assertTrue(
                "档位必须严格递增（第 $i 档不能 <= 第 ${i - 1} 档）",
                VIGNETTE_LEVEL_PARAM.levels[i] > VIGNETTE_LEVEL_PARAM.levels[i - 1],
            )
        }
        assertTrue(
            "旧档位（轻 0.2 / 中 0.4 / 强 0.7）正是用户实测「跟没有一样」的那组 ⇒ 不许退回去",
            VIGNETTE_LEVEL_PARAM.levels[3] > 0.7f,
        )
        // 内置依据：三条内置的暗角（0.18 / 0.24 / 0.28）都落在"轻"档（0.35）
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

    // ── ③ 常用参数清单：用户点名的 5 项（★ PS-8：一级页直接可调的就是这一块）─────────

    /**
     * `QUICK_ADJUST_SLOTS` = 用户点名的 5 项（LUT 强度 / 颗粒 / 柔光 / 光晕 / 暗角）**＋ PS-10 的白点**
     * —— 用户点名的 5 项**一位没少**。
     *
     * ★ PS-8：一级页已无清单 ⇒ "点了有没有反应"不再由清单承担，而是"每个能调的就是控件本身"
     * （滑杆 / 分段控件）。本用例只钉"常用清单没被砍"，且每一项都真的在拍照链路上
     * （`CAMERA_PATH_SLOTS`）—— 渲染与接线见 [Ps8PresetUiRedesignTest]。
     *
     * ★ PS-9：颗粒的 `[27]` 尺寸**从集合里去掉**（一级页只剩一条只写 `[26]` 的强度滑杆）——
     * 集合口径 = "一级页真的能调的槽"。
     *
     * ★ PS-10：加 `[38]` 白点（用户 2026-10-12："黑白点要可调"；高光死白的病灶），
     * **不加 `[37]`** 黑点 —— 取舍依据见 `FILMIC_WHITE_EV_SLOT` 的 KDoc（黑点在「完整参数 >」）。
     */
    @Test
    fun quickAdjustCoversExactlyTheFiveItemsTheUserNamed() {
        assertEquals(
            "用户点名的 5 项（LUT 强度 / 颗粒强度 / 柔光 / 光晕 / 暗角）+ ★ PS-10 的白点",
            linkedSetOf(34, 26, 95, 62, 66, 38),
            QUICK_ADJUST_SLOTS,
        )
        for (s in setOf(26, 95, 62, 66, 38)) {
            assertTrue("[$s] 是拍照链路读得到的槽（常用项必须真的能调）", s in CAMERA_PATH_SLOTS)
        }
        assertTrue(
            "★ PS-9：`[27]` 颗粒尺寸仍是拍照链路读得到的槽，但**一级页不再有它的控件**",
            27 in CAMERA_PATH_SLOTS && 27 !in QUICK_ADJUST_SLOTS,
        )
        assertTrue(
            "★ PS-10：黑点 `[37]` 在拍照链路上可调，但**一级页不给它控件**（在「完整参数 >」）" +
                "—— 与白点 `[38]` 的取舍见 FILMIC_WHITE_EV_SLOT 的 KDoc",
            37 in CAMERA_PATH_SLOTS && 37 !in QUICK_ADJUST_SLOTS && 38 in QUICK_ADJUST_SLOTS,
        )
        assertFalse(
            "[34] LUT 强度**不在**拍照链路白名单里（它由 prefs 的 lut_strength + `[34]` 两路一起写，" +
                "见 pushEdit）—— 把它当普通滑杆 = 拖了画面不变（PS-6a 踩过）",
            34 in CAMERA_PATH_SLOTS,
        )
    }

    // ── ⑤ 写值口径：档位 / 滑杆 → 预设（含稀疏化与 LUT 强度）────────────────────

    /**
     * 档位写值**走的是既有那条 `EditorParam` 通路**，保存口径与拖动完全一致：
     *  - 中颗粒（`[26] = 30`）⇒ 进预设；`[27]` 保持预设自己的值（★ PS-9：滑杆不写它）；
     *  - 拖回 0（= 关）⇒ 强度回中性值 ⇒ 被稀疏化丢弃（= 这条预设不再覆盖颗粒）。
     */
    @Test
    fun levelWriteGoesThroughTheEditorParamAndSavesBackSparsely() {
        val p = userPreset("p_1", mapOf(4 to 1.2, GRAIN_SIZE_SLOT to 160.0))
        val none: CameraParamOverrides? = null
        val ov = none.afterEdit(p, GrainStrengthParam, 0.3f)
        assertEquals(30.0, ov!!.values.getValue(GRAIN_AMOUNT_SLOT), 1e-3)
        assertFalse("★ `[27]` 不进改动集（滑杆只写 `[26]`）", ov.values.containsKey(GRAIN_SIZE_SLOT))
        val saved = cameraPresetSlotsFor(p, ov.values, ov.removed)
        assertEquals("档位写进的强度进预设", 30.0, saved.getValue(GRAIN_AMOUNT_SLOT), 1e-3)
        assertEquals("尺寸仍是预设原值（一位不动）", 160.0, saved.getValue(GRAIN_SIZE_SLOT), 1e-9)
        assertEquals("其余字段一位不动", 1.2, saved.getValue(4), 1e-9)

        val off = none.afterEdit(p, GrainStrengthParam, 0f)
        assertFalse(
            "拖回 0 ⇒ 回到中性值 ⇒ 改动集里没有 `[26]`（不是「存一个 0」）",
            off!!.values.containsKey(GRAIN_AMOUNT_SLOT),
        )
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
        // ★ PS-9：颗粒改成强度滑杆 ⇒ 用滑杆值 0.2（= `[26] = 20`，原「细颗粒」档的强度）
        val ov = none.afterEdit(factory, GrainStrengthParam, 0.2f)
        val edited = factory.copy(slots = cameraPresetSlotsFor(factory, ov!!.values, ov.removed))
        assertEquals("改过的内置：颗粒强度 = 20", 20.0, edited.slots.getValue(GRAIN_AMOUNT_SLOT), 1e-3)
        // 出厂配方里 `[27] = 400` 是引擎恒等值 ⇒ 稀疏化后**不在 slots 里**（既有口径）；
        // 滑杆不写它 ⇒ 改动后仍然不在（= 保持原值 400，而不是被滑杆顺手写进去）
        assertEquals(
            "★ PS-9：尺寸 `[27]` 不被滑杆写入（原值 400 = 恒等值 ⇒ 稀疏化后不进 slots）",
            factory.slots[GRAIN_SIZE_SLOT],
            edited.slots[GRAIN_SIZE_SLOT],
        )
        val applied = applyBuiltinOverrides(listOf(factory), mapOf(factory.id to edited)).single()
        assertEquals("覆盖层生效（内置能改）", 20.0, applied.slots.getValue(GRAIN_AMOUNT_SLOT), 1e-3)
        assertFalse("内置**唯一**受限的动作：删除", presetDeletable(applied))
        assertEquals(
            "「恢复默认」= 删掉覆盖层 ⇒ 立刻回出厂配方",
            factory.slots,
            applyBuiltinOverrides(listOf(factory), emptyMap()).single().slots,
        )
    }
}
