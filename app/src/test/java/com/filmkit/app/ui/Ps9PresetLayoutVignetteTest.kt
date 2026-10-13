package com.filmkit.app.ui

import com.filmkit.app.camera.BuiltinPresets
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.cameraPresetParams
import com.filmkit.app.camera.vigStrengthSlot
import com.filmkit.app.camera.vigStrengthUi
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ PS-9：预设面板**布局间距 + 暗角档位 + 颗粒滑杆**的机器判据（用户 2026-10-12 真机截图 +
 * 三条反馈原话）：
 *
 * > "**截图位置挤到一起了，优化布局**。**暗角轻微和中等跟没有一样，强烈顶多算轻微**。
 * >  **颗粒改成滑杆吧**"
 *
 * 本文件盯三件事（感官部分 —— 真机上"看起来松不松" —— 见报告 §未验证）：
 *
 *  1. **统一间距阶**（[PRESET_GAP_SM_DP] / [PRESET_BLOCK_GAP_DP] / [PRESET_GAP_LG_DP]）：
 *     三档常量 + 单调递增 + 一级页各块真的引用它们（不是写了常量没人用）+ 卡片内边距 ≥10dp；
 *  2. **暗角档位按引擎满量程**（[VIGNETTE_LEVEL_PARAM]）：新值 0 / 0.35 / 0.65 / **1.0**、
 *     严格递增、强档踩在 `[66]` 的 clamp 下限 −1 上，且**引擎源码哨兵**证明 −1 就是最大暗角
 *     （`vigStrengthSlot` 的 clamp + `FrameRenderer.cpp` 的 `clamp(p.vignette_strength, -1, 0)`
 *     + shader 的 `c *= (1.0 + vw * uVigBrightness)`）；
 *  3. **颗粒强度滑杆**（[GrainStrengthParam]）：只写 `[26]`、读数口径 = `PresetDetail.kt` 的
 *     既有口径、`[27]` 不在一级页的写路径上，且一级页真的渲染成滑杆（不是残留的档位控件）。
 *
 * 真实数据来源 = `app/src/main/assets/presets/builtin.json`；引擎源码哨兵读
 * `../core/engine/src/render/FrameRenderer.cpp`（工作目录 = `app/`，与同目录其它用例同口径）。
 */
class Ps9PresetLayoutVignetteTest {

    private val builtins = BuiltinPresets.parse(File("src/main/assets/presets/builtin.json").readText())
    private val panel = File("src/main/java/com/filmkit/app/ui/CameraPresetPanel.kt").readText()
    private val screen = File("src/main/java/com/filmkit/app/ui/CameraScreen.kt").readText()
    private val engine = File("../core/engine/src/render/FrameRenderer.cpp").readText()

    private fun builtin(name: String): EditorPreset =
        builtins.firstOrNull { it.name == name } ?: error("内置包里没有「$name」")

    // ── ① 统一间距阶（用户："截图位置挤到一起了，优化布局"）──────────────────────

    /**
     * 三档间距阶：**块内 8dp / 块间 16dp / 面板顶 24dp**。
     *
     * 判据不只是"常量等于某个数"，而是**单调性 + 一级页真的引用**：写了常量却到处硬编码 dp
     * 正是用户看到的"各区块贴在一起"（每一处都差一点点，合起来就散了）。
     */
    @Test
    fun spacingScaleIsThreeMonotonicStepsAndTheFirstLevelActuallyUsesIt() {
        assertEquals("块内（同一块里相邻元素）", 8f, PRESET_GAP_SM_DP, 1e-4f)
        assertEquals("块间（一级页各块之间；PS-8 的 12dp → 16dp）", 16f, PRESET_BLOCK_GAP_DP, 1e-4f)
        assertEquals("面板顶（tab 行 → 横向预设条）", 24f, PRESET_GAP_LG_DP, 1e-4f)
        assertTrue(
            "间距阶必须严格递增（否则三档分不出层次）",
            PRESET_GAP_SM_DP < PRESET_BLOCK_GAP_DP && PRESET_BLOCK_GAP_DP < PRESET_GAP_LG_DP,
        )
        assertTrue("块间距在用户要求的 16–24dp 区间内", PRESET_BLOCK_GAP_DP in 16f..24f)
        assertTrue("卡片内边距 ≥ 10dp（用户：'卡片内文字与边框的内边距，都要重看'）",
            PRESET_CARD_PAD_H_DP >= 10f && PRESET_CARD_PAD_V_DP >= 10f)

        // 一级页（非编辑态）那一段：顶部用大间距、块间用块间距、副标题用块内小间距
        val main = panel.substringAfter("    } else {\n").substringBefore("    // 「⋯」操作菜单（显式入口）")
        assertTrue("一级页顶部 = 面板顶间距（tab 行与预设条之间不再贴住）", main.contains("top = PRESET_GAP_LG_DP.dp"))
        assertTrue("块间 = 块间距常量", main.contains("top = PRESET_BLOCK_GAP_DP.dp"))
        assertTrue("副标题 = 块内小间距（PS-8 的 2dp 让副标题贴着标题）", main.contains("top = PRESET_GAP_SM_DP.dp"))
        assertTrue("卡片内边距 = 常量（不是散落的 dp）",
            panel.contains("horizontal = PRESET_CARD_PAD_H_DP.dp, vertical = PRESET_CARD_PAD_V_DP.dp"))
        // 常用参数区的行距 = 块内小间距（块间距留给"块与块之间"，不再一行一个块间距）
        val quick = panel.substringAfter("private fun PresetQuickAdjustPanel(").substringBefore("private fun PresetParamLabelRow(")
        assertTrue("常用参数区行距 = 块内小间距", quick.contains("Arrangement.spacedBy(PRESET_GAP_SM_DP.dp)"))
    }

    /**
     * ★ 标题去重（用户："标题重复：顶部一个「徕卡一瞬」，详情里又一个 —— **二选一**"）。
     *
     * 保留**详情里那个大的**（一级页名称行 18sp 衬线半粗）；`LutSheet` 顶行在预设 tab 下
     * 不再复述预设名。判据 = 顶行那一段源码里**没有** `selectedPreset.name`，且字面是「预设」。
     */
    @Test
    fun theSheetHeaderStopsRepeatingThePresetNameThatTheDetailRowAlreadyShows() {
        val header = screen.substringAfter("// 顶行：当前选中名（左）+ 收起（右）").substringBefore("// 分类")
        assertTrue("★ 预设 tab 的顶行标题 = 「预设」（不再复述预设名）", header.contains("\"预设\""))
        assertFalse(
            "★ 顶行不许再渲染 selectedPreset.name（与一级页名称行重复 —— 用户点名的那个缺陷）",
            header.contains("selectedPreset.name"),
        )
        assertTrue(
            "LUT tab 保持原样（它的名字没有第二个落点，去掉就没人说了）",
            header.contains("LutLibrary.byId(context, lutId)?.label"),
        )
        assertTrue("一级页名称行（18sp 衬线半粗）仍在 —— 去重保留的是这一处",
            panel.contains("fontSize = 18.sp") && panel.contains("selected.name"))
    }

    // ── ② 暗角档位：按引擎满量程（用户："强烈顶多算轻微"）──────────────────────

    /**
     * 新档位值 = **引擎满量程四档**（关 0 / 轻 0.35 / 中 0.65 / 强 **1.0**）。
     *
     * 依据两条（都在 `CameraPresetPanel.kt` 的 `VIGNETTE_LEVEL_PARAM` 上方写死）：
     *  1. **引擎量程** `[66] ∈ [−1, 0]`（`vigStrengthSlot` 的 clamp；引擎 CPU 侧再 clamp 一次）
     *     ⇒ UI 1.0 就是"最强"，旧强档 0.70 只用到量程的 70%；
     *  2. **暗角只在画面最外缘可见**：三条内置的 radius = 75~85 ⇒ 衰减从 75%~85% 半径才开始，
     *     旧档位在中景就几乎看不出差别（用户实测"跟没有一样"）。
     */
    @Test
    fun vignetteLevelsUseTheFullEngineRangeAndTheStrongStepHitsItsClamp() {
        assertEquals(
            "暗角四档（关 / 轻 / 中 / 强）",
            listOf(0f, 0.35f, 0.65f, 1.0f),
            VIGNETTE_LEVEL_PARAM.levels,
        )
        assertEquals("关 = 引擎中性值（[66] = 0 ⇒ 整段跳过）", 0f, VIGNETTE_LEVEL_PARAM.levels[0], 1e-6f)
        for (i in 1 until VIGNETTE_LEVEL_PARAM.levels.size) {
            assertTrue(
                "档位必须严格递增（第 $i 档 = ${VIGNETTE_LEVEL_PARAM.levels[i]}）",
                VIGNETTE_LEVEL_PARAM.levels[i] > VIGNETTE_LEVEL_PARAM.levels[i - 1],
            )
        }
        assertEquals(
            "★ 强档 = 引擎满量程：写进槽位是 −1.0（= 引擎 clamp 下限）",
            -1.0,
            vigStrengthSlot(VIGNETTE_LEVEL_PARAM.levels.last()),
            1e-9,
        )
        assertTrue(
            "★ 旧强档 0.70 正是用户实测「顶多算轻微」的那一档 ⇒ 新值必须高于它",
            VIGNETTE_LEVEL_PARAM.levels.last() > 0.7f,
        )
        assertTrue("★ 中档也要明显强于旧中档 0.40", VIGNETTE_LEVEL_PARAM.levels[2] > 0.4f)

        // 档位值 → 画面角落的**增益**（shader 同式）：越强越暗，强档把角落压到 0
        fun cornerGain(ui: Float) = (1.0 + 1.0 * vigStrengthSlot(ui)).toFloat()
        assertEquals("关 ⇒ 角落不变", 1f, cornerGain(0f), 1e-6f)
        assertEquals("轻 0.35 ⇒ 角落 −35%（内置 0.18 只有 −18%）", 0.65f, cornerGain(0.35f), 1e-6f)
        assertEquals("中 0.65 ⇒ 角落 −65%", 0.35f, cornerGain(0.65f), 1e-6f)
        assertEquals("强 1.0 ⇒ 角落归零（量程内能到的最暗）", 0f, cornerGain(1.0f), 1e-6f)
        for (i in 1 until VIGNETTE_LEVEL_PARAM.levels.size) {
            assertTrue(
                "角落增益必须随档位单调变暗",
                cornerGain(VIGNETTE_LEVEL_PARAM.levels[i]) < cornerGain(VIGNETTE_LEVEL_PARAM.levels[i - 1]),
            )
        }

        // 内置三条（0.18 / 0.24 / 0.28）仍落在轻档 —— 新档位没有把内置的观感打乱
        for (b in builtins) {
            if (b.slots[VIGNETTE_LEVEL_PARAM.slot] == null) continue
            val ui = vigStrengthUi(cameraPresetParams(b)[VIGNETTE_LEVEL_PARAM.slot])
            assertEquals(
                "内置「${b.name}」的暗角 $ui 应落在轻档（0.35）",
                1,
                nearestLevel(ui, VIGNETTE_LEVEL_PARAM.levels),
            )
        }
    }

    /**
     * ★ **引擎端核对**（用户："不要只调数字掩盖引擎问题"）：源码哨兵证明 `[66] = −1.0` 确实
     * 是**最大暗角**，且没有第二处更严的钳制把强度压回去。
     *
     * 三处证据（都在 `core/engine/src/render/FrameRenderer.cpp`）：
     *  1. CPU 下发：`std::clamp(p.vignette_strength, -1.0f, 0.0f)` ⇒ 引擎**接受** −1，且不接受更负；
     *  2. shader 门控：`if (uVigBrightness < 0.0)` ⇒ `[66] = 0` 整段跳过（关 = 零成本）；
     *  3. shader 公式：`c *= (1.0 + vw * uVigBrightness)` ⇒ `vw = 1` 处增益 = `1 + (−1) = 0`
     *     （角落压到全黑）—— 量程内没有比它更暗的写法。
     */
    @Test
    fun engineSourceConfirmsMinusOneIsTheStrongestVignetteThePipelineAccepts() {
        assertTrue(
            "① CPU 侧把 [66] 夹到 −1..0（引擎接受的量程就是这个）",
            engine.contains("std::clamp(p.vignette_strength, -1.0f, 0.0f)"),
        )
        assertEquals(
            "① 两条链路（passStyle / passOutput）都要夹 —— 少一条就会有链路接受越界值",
            2,
            Regex("""std::clamp\(p\.vignette_strength, -1\.0f, 0\.0f\)""").findAll(engine).count(),
        )
        assertTrue("② shader 门控：>= 0 ⇒ 整段跳过（关 = 零成本旁路）", engine.contains("if (uVigBrightness < 0.0)"))
        assertTrue(
            "③ shader 公式：角落权重 1 处增益 = 1 + uVigBrightness ⇒ −1 时归零（量程内最暗）",
            engine.contains("c *= (1.0 + vw * uVigBrightness);"),
        )
        assertTrue(
            "③ 半径 / 羽化也要在（暗角形状由 [67][68] 决定，它们没有被本次改动碰过）",
            engine.contains("std::clamp(p.vignette_radius, 0.0f, 200.0f)") &&
                engine.contains("std::max(p.vignette_feather, 100.0f / vig_min)"),
        )
        // Kotlin 侧的读写往返：UI 1.0 ↔ 槽位 −1.0（写进去读回来是同一个数，没有二次换算）
        val param = CAMERA_TUNABLE_SLOT_PARAM.getValue(VIGNETTE_LEVEL_PARAM.slot)
        assertEquals("UI 1.0 ⇒ 槽位 −1.0", -1.0, vigStrengthSlot(1f), 1e-9)
        assertEquals("槽位 −1.0 ⇒ UI 1.0（往返无二次换算）", 1f, vigStrengthUi(vigStrengthSlot(1f)), 1e-6f)
        assertTrue("UI 量程不许超过 1.0（越界只会被引擎夹回去 = 假控件）", param.range.endInclusive <= 1f)
        assertEquals(
            "档位值必须落在滑杆自己的量程内（否则档位按钮能点、滑杆却显示不到那一档）",
            1f,
            param.range.endInclusive,
            1e-6f,
        )
        assertEquals("标准基线里 [66] = 0（默认关）", 0.0, CameraPresets.standard()[66], 1e-9)
    }

    // ── ③ 颗粒滑杆（用户："颗粒改成滑杆吧"）────────────────────────────────────

    /**
     * 一级页的颗粒 = **强度滑杆**（只写 `[26]`）；`[27]` 尺寸不再有控件、保持预设原值。
     *
     * 判据分两层：**纯逻辑**（写值 / 读数 / 记账）在 [CameraPresetMainViewTest]，
     * 这里盯**源码接线**（渲染成滑杆、不再是档位控件）+ 反向哨兵（旧档位形态不许回来）。
     */
    @Test
    fun grainIsASliderThatWritesOnlyTheAmountSlotAndTheOldLevelControlIsGone() {
        val quick = panel.substringAfter("private fun PresetQuickAdjustPanel(").substringBefore("private fun PresetParamLabelRow(")
        assertTrue("颗粒 = 一行滑杆（与 LUT 强度同一套视觉）", quick.contains("param = GrainStrengthParam"))
        assertTrue("颗粒滑杆读的是当前生效参数数组（拖动有反应）", quick.contains("params = params"))
        assertTrue("颗粒滑杆在换预设时重建（不沿用上一条的读数）", quick.contains("key(presetId, GrainStrengthParam.key)"))
        // 反向哨兵：旧的四档形态与它的两个槽的写入器都不许回来
        for (dead in listOf("GrainLevelParam(", "GrainLevel.entries", "grainLevelOf(")) {
            assertFalse("「$dead」是用户砍掉的档位形态（'颗粒改成滑杆吧'）⇒ 不许回来", panel.contains(dead))
        }
        assertFalse(
            "★ 一级页的写路径不许碰 `[27]`（GrainStrengthParam 的 write 里只能有 GRAIN_AMOUNT_SLOT）",
            panel.substringAfter("internal val GrainStrengthParam = EditorParam(")
                .substringBefore(")\n\n")
                .contains("GRAIN_SIZE_SLOT"),
        )
        assertTrue(
            "★ `[27]` 尺寸仍由「完整参数」页只读展示（`PresetDetail.kt` 的 meta(27, …) 一位未动）",
            File("src/main/java/com/filmkit/app/ui/PresetDetail.kt").readText().contains("meta(27, SEC_FILM, \"颗粒 · 尺寸\")"),
        )
        // 柔光 / 光晕 / 暗角三行仍是分段控件（只有颗粒改了形态）
        assertTrue("柔光 / 光晕 / 暗角 = 三行分段控件", quick.contains("for (q in QUICK_LEVEL_PARAMS)"))
        assertEquals("QUICK_LEVEL_PARAMS 恰好三行（颗粒不在其中）", 3, QUICK_LEVEL_PARAMS.size)
        assertFalse("颗粒的槽 [26] 不属于三行档位", QUICK_LEVEL_PARAMS.any { it.slot == GRAIN_AMOUNT_SLOT })
    }
}
