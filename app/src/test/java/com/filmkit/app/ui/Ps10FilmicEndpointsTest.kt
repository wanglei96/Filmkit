package com.filmkit.app.ui

import com.filmkit.app.camera.BuiltinPresets
import com.filmkit.app.camera.CAMERA_PATH_SLOTS
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.FILMIC_BLACK_EV_PIVOT
import com.filmkit.app.camera.FILMIC_BLACK_EV_UI_RANGE
import com.filmkit.app.camera.FILMIC_WHITE_EV_PIVOT
import com.filmkit.app.camera.FILMIC_WHITE_EV_UI_RANGE
import com.filmkit.app.camera.cameraPresetParams
import com.filmkit.app.camera.formatEvSlot
import java.io.File
import kotlin.math.abs
import kotlin.math.log2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ PS-10：filmic **黑白点 `[37]` / `[38]` 可调**的机器判据。
 *
 * 用户 2026-10-12 真机原话：
 * > "**黑白点要可调**，你白点默认才 2.7？那杯子高光变白的 bug 不就又出来了！！！"
 *
 * 本文件盯六件事（"真机上滑杆跟不跟手"这类感官部分见报告 §未验证）：
 *
 *  1. **量程有依据**：与 `SettingsScreen.kt` 的既有全局滑杆**逐位相同**（同一个参数的既有量程），
 *     不是参数表里另定的一套；引擎侧对这两个槽没有绝对 clamp（源码哨兵）；
 *  2. **pivot = 引擎默认值 = `CameraPresets.standard()` 基线**（三处对拍 ⇒ 不可能各自漂移）；
 *  3. **读数口径唯一**：参数表滑杆的读数与 `PresetDetail.kt` 的详情行**逐字相同**
 *     （同一个数在两页显示成两个值 = ADR-12 的假读数）；
 *  4. **两端都真生效**（不是假控件）：拍照链路 `updateFilmicEV` + 编辑器链路 `computeEditorCurve`
 *     都读这两个字段，且 `[37][38]` 在 `CAMERA_PATH_SLOTS` 里 ⇒ 拍照界面判为 `TUNABLE`；
 *  5. **编辑器链路白点的物理上限如实记录**（不藏）：`computeEditorCurve` 的显示域 clamp 使
 *     `white_ev ≥ log2(1/0.1845) ≈ 2.4377` 与 2.4377 逐位等价 —— 本卡**不改引擎**，只标注；
 *  6. **写值走既有通路**：`afterEdit` → `cameraPresetParams`（实时下发）→ `cameraPresetSlotsFor`
 *     （保存），拖回默认值 ⇒ 稀疏化丢弃（不是"存一个等于默认的数"）。
 *
 * ⚠ 内置三条的白点值由**另一张卡**评估后决定 ⇒ 本文件**不硬编码**它们的数值，只断言
 * "从 `builtin.json` 读出来的值在量程内、且两页读数一致"（那张卡改值不会让本文件变红）。
 *
 * 工作目录 = `app/`（与同目录其它用例同口径）；引擎源码哨兵读 `../core/engine/src/`。
 */
class Ps10FilmicEndpointsTest {

    private val builtins = BuiltinPresets.parse(File("src/main/assets/presets/builtin.json").readText())
    private val panel = File("src/main/java/com/filmkit/app/ui/CameraPresetPanel.kt").readText()
    private val paramControls = File("src/main/java/com/filmkit/app/ui/ParamControls.kt").readText()
    private val settings = File("src/main/java/com/filmkit/app/ui/SettingsScreen.kt").readText()
    private val filmParamsH = File("../core/engine/src/film/FilmParams.h").readText()
    private val engine = File("../core/engine/src/render/FrameRenderer.cpp").readText()

    private val black = CAMERA_TUNABLE_SLOT_PARAM.getValue(37)
    private val white = CAMERA_TUNABLE_SLOT_PARAM.getValue(38)

    // ── ① 量程的依据 ──────────────────────────────────────────────────────────

    /**
     * 量程 = `SettingsScreen.kt` 的**既有**全局滑杆量程（黑点 −12..−1 / 白点 1..8），逐位相同。
     *
     * 为什么这条是判据而不是"抄了个数"：同一个物理量（相对中灰的 EV）在全局设置里已经有一个
     * 量程，参数表再定一套 = 两套真相。源码哨兵读 `SettingsScreen.kt` 的 `range = …` 字面量
     * ⇒ 那边改了量程，这边不同步就红（而不是"看起来都对"）。
     */
    @Test
    fun rangesComeFromTheExistingGlobalSlidersNotANewSet() {
        assertEquals("黑点量程 = 全局滑杆的 −12..−1", -12f, FILMIC_BLACK_EV_UI_RANGE.start, 1e-6f)
        assertEquals("黑点量程 = 全局滑杆的 −12..−1", -1f, FILMIC_BLACK_EV_UI_RANGE.endInclusive, 1e-6f)
        assertEquals("白点量程 = 全局滑杆的 1..8", 1f, FILMIC_WHITE_EV_UI_RANGE.start, 1e-6f)
        assertEquals("白点量程 = 全局滑杆的 1..8", 8f, FILMIC_WHITE_EV_UI_RANGE.endInclusive, 1e-6f)
        assertTrue("量程必须非退化", FILMIC_BLACK_EV_UI_RANGE.start < FILMIC_BLACK_EV_UI_RANGE.endInclusive)
        assertTrue("量程必须非退化", FILMIC_WHITE_EV_UI_RANGE.start < FILMIC_WHITE_EV_UI_RANGE.endInclusive)
        // 源码哨兵：全局设置页那两条滑杆的量程字面量仍在（量程的"依据"必须真的存在）
        assertTrue(
            "SettingsScreen.kt 的黑点滑杆量程变了 ⇒ 参数表量程的依據随之失效，必须同步",
            settings.contains("range = -12f..-1f"),
        )
        assertTrue(
            "SettingsScreen.kt 的白点滑杆量程变了 ⇒ 参数表量程的依据随之失效，必须同步",
            settings.contains("range = 1f..8f"),
        )
        // 引擎侧没有绝对 clamp（量程纯粹是 UI 决定）：FilmParams.h 直通两个槽
        assertTrue(
            "FilmParams.h 的 [37][38] 仍是直通赋值（没有夹紧）",
            filmParamsH.contains("if (n > 37) filmic_black_ev = static_cast<float>(v[37]);") &&
                filmParamsH.contains("if (n > 38) filmic_white_ev = static_cast<float>(v[38]);"),
        )
        assertTrue(
            "computeFilmicSpline 只有相对约束（black ≤ white − 0.2），没有绝对量程",
            engine.contains("std::min(static_cast<double>(black_ev), static_cast<double>(white_ev) - 0.2)"),
        )
    }

    /**
     * pivot（中点标记 / 「重置」目标）= **引擎默认值**，且与 `CameraPresets.standard()` 的基线
     * **逐位相同** —— 三处（`FilmParams.h` / Kotlin 常量 / standard 基线）对拍。
     *
     * 为什么必须对拍 standard：`afterEdit` / `presetSparseSlots` 的"这条预设没改这一项"判据就是
     * 与 standard 比 ⇒ pivot 与 standard 差一点，用户"拖回默认位置"就撤销不掉（改动集里留一条
     * 几乎等于默认的值）。
     */
    @Test
    fun pivotsAreTheEngineDefaultsAndTheKotlinBaseline() {
        assertEquals("黑点 pivot = 引擎默认 −7.65", -7.65f, FILMIC_BLACK_EV_PIVOT, 1e-6f)
        assertEquals("白点 pivot = 引擎默认 +4.56", 4.56f, FILMIC_WHITE_EV_PIVOT, 1e-6f)
        // 源码哨兵：FilmParams.h 的默认值（唯一维护点）
        assertTrue(
            "FilmParams.h 的 filmic_black_ev 默认值变了 ⇒ pivot 必须跟着改",
            filmParamsH.contains("float filmic_black_ev = -7.65f;  // 37"),
        )
        assertTrue(
            "FilmParams.h 的 filmic_white_ev 默认值变了 ⇒ pivot 必须跟着改",
            filmParamsH.contains("float filmic_white_ev = 4.56f;   // 38"),
        )
        // Kotlin 侧中性基线（稀疏化 / afterEdit 的比较基准）
        // ⚠ 容差 1e-6 而不是 1e-9：pivot 是 **Float** 常量（−7.65f = −7.650000095367432），
        // 与 standard() 里的 Double 字面量差 9.5e-8 —— 这是 Float→Double 的精度噪声，
        // 远小于 PARAM_EPS = 1e-4 ⇒ 「拖回默认值 ⇒ 稀疏化丢弃」的判据不受影响（本文件另有用例钉它）。
        val standard = CameraPresets.standard()
        assertEquals("standard()[37] = 引擎默认", FILMIC_BLACK_EV_PIVOT.toDouble(), standard[37], 1e-6)
        assertEquals("standard()[38] = 引擎默认", FILMIC_WHITE_EV_PIVOT.toDouble(), standard[38], 1e-6)
        assertTrue(
            "Float 精度噪声必须远小于稀疏化容差（否则拖回默认值撤销不掉）",
            abs(FILMIC_BLACK_EV_PIVOT.toDouble() - standard[37]) < com.filmkit.app.camera.PARAM_EPS &&
                abs(FILMIC_WHITE_EV_PIVOT.toDouble() - standard[38]) < com.filmkit.app.camera.PARAM_EPS,
        )
        // pivot 必须落在各自量程内（否则中点标记不画、reset 会写出越界值）
        for ((p, r) in listOf(black to FILMIC_BLACK_EV_UI_RANGE, white to FILMIC_WHITE_EV_UI_RANGE)) {
            assertTrue("「${p.label}」的 pivot ${p.pivot} 必须落在量程 $r 内", p.pivot in r)
        }
    }

    // ── ② 读数口径：两页同一个数 ──────────────────────────────────────────────

    /**
     * 参数表滑杆的读数 == `PresetDetail.kt` 详情行的读数（**逐字**，不是"看着差不多"）。
     *
     * 这条是 PS-10 的 ADR-12 判据：同一个槽在「完整参数 >」的网格、底部滑杆、详情清单、
     * 预设列表摘要里必须是同一个字符串。实现上由 [formatEvSlot] 单一实现保证，本用例把它钉住
     * （含坏数据与量程端点）。
     */
    @Test
    fun readoutsMatchTheDetailTableCharacterForCharacter() {
        val samples = listOf(
            -12.0, -7.65, -7.6, -4.24, -1.0,          // 黑点：量程两端 + 引擎默认 + 内置的实测值形态
            1.0, 2.4377, 2.5438, 2.6138, 2.7471, 4.56, 6.2, 8.0,  // 白点：量程两端 + 默认 + 实测档
            0.0, Double.NaN,                          // 零 / 坏数据
        )
        for (v in samples) {
            for (p in listOf(black, white)) {
                val slot = if (p.key == "filmicBlack") 37 else 38
                val other = if (slot == 37) 38 else 37
                // ⚠ 另一个槽设成**不同的**哨兵值：读错槽（例如白点滑杆读 [37]）必须在这里红，
                // 两个槽设成同一个值会让"读错槽"照样绿（mutation 实测踩过）。
                val film = CameraPresets.standard().also { it[slot] = v; it[other] = v + 1.0 }
                assertEquals(
                    "槽 $slot 的值 $v：滑杆读数与详情页读数必须逐字相同（读错槽会显示 ${v + 1.0} 的读数）",
                    presetDetailRowOf(slot, v).value,
                    p.format(p.read(film)),
                )
            }
        }
        // 一位小数 + EV 的形态（与 PresetDetailTest 的既有断言同形，不是新口径）
        assertEquals("-7.6 EV", formatEvSlot(-7.65f))
        assertEquals("+4.6 EV", formatEvSlot(4.56f))
        assertEquals("0.0 EV", formatEvSlot(0f))
        assertEquals("+2.7 EV", formatEvSlot(2.7471f))
        // 零不带符号（−0.04 显示 0.0 而不是 -0.0）
        assertEquals("0.0 EV", formatEvSlot(-0.04f))
        // 坏数据不显示 NaN
        assertEquals("0.0 EV", formatEvSlot(Float.NaN))
        // ⚠ 与 formatEv（UI 域 ×3）**不是**同一条：混用会把 −7.65 显示成 −3.0
        assertEquals("-3.0 EV", com.filmkit.app.camera.formatEv(-7.65f))
    }

    // ── ③ 两端都真生效（ADR-12：不做假控件）──────────────────────────────────

    /**
     * `[37][38]` 在拍照链路白名单里、且有了滑杆 ⇒ 拍照界面判为 `TUNABLE`（不是 `NO_SLIDER`）。
     *
     * 同时用**引擎源码哨兵**证明两端都真的被读：拍照链路 `updateFilmicEV(..., p.filmic_black_ev,
     * p.filmic_white_ev, ...)`、编辑器链路 `computeEditorCurve(p.shoulder, p.toe,
     * p.filmic_black_ev, p.filmic_white_ev)`。若哪天某条链路不再读，本用例红 —— 那时按 ADR-12
     * 必须改成灰显 + 标注原因（而不是留着假滑杆）。
     */
    @Test
    fun bothEndsAreReallyReadByBothChainsSoTheyAreNotFakeSliders() {
        for (slot in listOf(37, 38)) {
            assertTrue("[$slot] 必须在拍照链路白名单里", slot in CAMERA_PATH_SLOTS)
            assertFalse("[$slot] 不该再出现在「没有滑杆」的欠账集合里", slot in CAMERA_NO_SLIDER_SLOTS)
            assertEquals("[$slot] 必须判为可调", CameraParamAdjustability.TUNABLE, cameraParamAdjustability(slot))
            assertEquals("[$slot] 可调 ⇒ 不该有「为什么点不动」的说明", null, cameraParamAdjustabilityNote(slot))
        }
        assertTrue(
            "拍照链路（成片）读 [37][38]",
            engine.contains("updateFilmicEV(src_tex, still, p.auto_tone > 0.5f, p.filmic_black_ev,") &&
                engine.contains("p.filmic_white_ev, p.white_hl, p.white_floor, p.white_probe,"),
        )
        assertTrue(
            "编辑器链路（显示域曲线）读 [37][38]",
            engine.contains("computeEditorCurve(p.shoulder, p.toe, p.filmic_black_ev,"),
        )
    }

    /**
     * ★ **如实记录**编辑器链路白点的物理上限（`computeEditorCurve` 的显示域 clamp）——
     * 本卡不改引擎，但绝不能不写：
     *
     * `wh = clamp(srgbEncode(grey·2^white_ev), grey_disp + 0.05, 1.0)`，而 `white_ev ≥
     * log2(1/0.1845) ≈ 2.4377` 时 `srgbEncode(...) ≥ 1.0` ⇒ `wh` 恒为 1.0 ⇒ 高光段仿射 `a = 1,
     * b = 0` = **恒等** ⇒ 编辑器里白点在 2.44 以上拖动与 2.44 逐位相同（显示域没有比 1.0 更亮
     * 的像素 —— 物理上限，不是 bug）。默认 4.56 与内置三条的 2.54~2.75 都已在该上限之上。
     *
     * 量程仍取 1..8：这两个槽的**主战场是拍照链路**（预设的白点就是给相机取景 / 成片用的），
     * 而 8 覆盖了真机实测有效的 6.2 档。
     */
    @Test
    fun editorChainWhiteCeilingIsRecordedNotHidden() {
        val ceilingEv = log2(1.0 / 0.1845)          // 显示域线性 1.0 对应的 EV（darktable grey 0.1845）
        assertEquals("显示域白点上限 ≈ 2.4377 EV", 2.4377, ceilingEv, 1e-3)
        assertTrue("白点量程下限（1 EV）在编辑器链路里有效", FILMIC_WHITE_EV_UI_RANGE.start < ceilingEv)
        assertTrue(
            "如实记录：参数表白点量程上限（8 EV）高于编辑器链路的物理上限（$ceilingEv EV）",
            FILMIC_WHITE_EV_UI_RANGE.endInclusive > ceilingEv,
        )
        assertTrue(
            "引擎默认 4.56 已在编辑器链路的上限之上 ⇒ 编辑器里它与 2.44 等价（拍照链路里不等价）",
            FILMIC_WHITE_EV_PIVOT > ceilingEv,
        )
        // 源码哨兵：那条 clamp 还在（引擎侧改了 ⇒ 本段"如实记录"的结论要重新评估）
        assertTrue(
            "computeEditorCurve 的显示域白点 clamp 变了 ⇒ 本用例的结论必须重新评估",
            engine.contains("wh = std::clamp(wh, grey_disp + 0.05, 1.0);"),
        )
        assertTrue("黑点在编辑器链路里没有对应的上限（clamp 到 −0.5 / grey_disp − 0.05）",
            engine.contains("bl = std::clamp(bl, -0.5, grey_disp - 0.05);"))
    }

    // ── ④ 写值走既有通路 + 稀疏化 ─────────────────────────────────────────────

    /** 量程两端写进去必须读回同一个数（滑杆拖到底 = 真的到端点，不是"差一点"）。 */
    @Test
    fun sliderRoundTripCoversBothRangeEnds() {
        for (p in listOf(black, white)) {
            for (end in listOf(p.range.start, p.range.endInclusive, p.pivot)) {
                val film = CameraPresets.standard()
                p.write(film, end)
                assertEquals("「${p.label}」写入 $end 后必须读回同一个数", end, p.read(film), 1e-6f)
            }
            // 两端写出来必须不同（否则这条滑杆拖了没变化 = 假滑杆）
            val lo = CameraPresets.standard().also { p.write(it, p.range.start) }
            val hi = CameraPresets.standard().also { p.write(it, p.range.endInclusive) }
            val slot = if (p.key == "filmicBlack") 37 else 38
            assertTrue("「${p.label}」量程两端必须写到不同值", abs(lo[slot] - hi[slot]) > 1e-6)
            // 只写自己那一个槽（黑白点是一条滑杆一个槽，不是"顺手改另一个"）
            val other = if (slot == 37) 38 else 37
            assertEquals("「${p.label}」不许碰另一个端点", CameraPresets.standard()[other], hi[other], 1e-9)
        }
    }

    /**
     * 拖动 → `afterEdit` 记账 → `cameraPresetParams`（下发给引擎的那一份）→ `cameraPresetSlotsFor`
     * （保存进预设）；拖回**引擎默认值** ⇒ 稀疏化丢弃（= 这条预设不再覆盖该项）。
     */
    @Test
    fun editsGoThroughTheExistingAfterEditPathAndSparsifyBack() {
        val p = EditorPreset(id = "p_ps10", name = "测试预设", slots = mapOf(4 to 1.2))
        val none: CameraParamOverrides? = null

        // 白点 4.56（预设没记 ⇒ 展开 = 基线）→ 用户拖到 2.0（治高光死白的方向）
        val ov = none.afterEdit(p, white, 2.0f)
        assertEquals("记账写进 [38]", 2.0, ov.values.getValue(38), 1e-6)
        assertEquals(
            "下发给引擎的那一份（实时预览走的同一个函数）",
            2.0,
            cameraPresetParams(p, ov.values, ov.removed)[38],
            1e-6,
        )
        assertEquals("保存进预设", 2.0, cameraPresetSlotsFor(p, ov.values, ov.removed).getValue(38), 1e-6)
        assertFalse("拖白点不许碰黑点 [37]", ov.values.containsKey(37))

        // 黑点同样一条通路
        val ovB = none.afterEdit(p, black, -6.0f)
        assertEquals(-6.0, ovB.values.getValue(37), 1e-6)
        assertEquals(-6.0, cameraPresetSlotsFor(p, ovB.values, ovB.removed).getValue(37), 1e-6)

        // 拖回引擎默认 ⇒ 与预设配方（= standard 基线）一致 ⇒ 撤销这次改动
        val back = none.afterEdit(p, white, FILMIC_WHITE_EV_PIVOT)
        assertFalse(
            "拖回默认值 ⇒ 改动集里没有 [38]（不是「存一个等于默认的数」）",
            back.values.containsKey(38),
        )
        val backB = none.afterEdit(p, black, FILMIC_BLACK_EV_PIVOT)
        assertFalse("拖回默认值 ⇒ 改动集里没有 [37]", backB.values.containsKey(37))

        // 两个槽都能进预设（调得动又存得下 —— 否则滑杆是"调了存不下"的假参数）
        assertTrue("presetSlotIncluded(37)", com.filmkit.app.camera.presetSlotIncluded(37))
        assertTrue("presetSlotIncluded(38)", com.filmkit.app.camera.presetSlotIncluded(38))
    }

    /** 内置三条的白点 / 黑点：读数在量程内，且滑杆读数与详情页读数逐字相同（不硬编码它们的值）。 */
    @Test
    fun builtinEndpointsSitInsideTheRangeAndReadTheSameOnBothPages() {
        var seen = 0
        for (b in builtins) {
            for ((slot, param) in listOf(37 to black, 38 to white)) {
                if (b.slots[slot] == null) continue
                seen++
                val film = cameraPresetParams(b)
                val ui = param.read(film)
                assertTrue(
                    "内置「${b.name}」的槽 $slot = ${film[slot]} 必须在量程 ${param.range} 内" +
                        "（越界值会让滑杆停在端点、读数与画面不一致）",
                    ui in param.range,
                )
                assertEquals(
                    "内置「${b.name}」槽 $slot：滑杆读数与详情页读数必须逐字相同",
                    presetDetailRowOf(slot, film[slot]).value,
                    param.format(ui),
                )
            }
        }
        assertTrue("内置包里至少要有一条记录了黑白点（否则本用例没覆盖到东西）", seen > 0)
    }

    // ── ⑤ 进组位置（二级页 / 一级页）──────────────────────────────────────────

    /**
     * 二级「完整参数」页：「黑点 / 白点」在**「调整」组**里、**紧跟胶片曲线两条**、在四条影调分区
     * 之前；**不进「基础」档**（基础档 = Lightroom Basic 十项，黑白点是曲线端点级参数）。
     */
    @Test
    fun fullEditGridPutsThemInTheAdjustGroupRightAfterTheCurve() {
        val adv = fullParamSections(FullParamTab.ADVANCED, emptySet())
        val adjust = adv.first { it.title == "调整" }.rows.flatten()
        val keys = adjust.map { it.key }
        val iToe = keys.indexOf("curveToe")
        assertTrue("胶片曲线 · 趾部必须在「调整」组里", iToe >= 0)
        assertEquals("黑点紧跟胶片曲线 · 趾部", iToe + 1, keys.indexOf("filmicBlack"))
        assertEquals("白点紧跟黑点（成对相邻）", iToe + 2, keys.indexOf("filmicWhite"))
        assertEquals("白点之后就是高光（曲线族与影调分区不交叉）", iToe + 3, keys.indexOf("highlights"))
        // 黑白点属于「调整」组（不是「色彩」/「质感」）—— 与参数表里的位置同一个判据
        assertTrue("黑点在 AdjustParams 里", AdjustParams.any { it.key == "filmicBlack" })
        assertTrue("白点在 AdjustParams 里", AdjustParams.any { it.key == "filmicWhite" })
        // 基础档不出现（仍是 Lightroom Basic 的十项）
        val basic = fullParamSections(FullParamTab.BASIC, emptySet()).flatMap { it.rows.flatten() }
        assertEquals("基础档仍是 10 项", 10, basic.size)
        assertTrue("黑白点不进基础档", basic.none { it.key == "filmicBlack" || it.key == "filmicWhite" })
        // 网格格子的 label 与参数表一致（不新造译名），且详情页的 label 包含它
        val cells = adjust.associateBy { it.key }
        assertEquals("黑点", cells.getValue("filmicBlack").label)
        assertEquals("白点", cells.getValue("filmicWhite").label)
        assertTrue(presetDetailRowOf(37, -7.65).label.contains(cells.getValue("filmicBlack").label))
        assertTrue(presetDetailRowOf(38, 4.56).label.contains(cells.getValue("filmicWhite").label))
    }

    /**
     * 一级页（拍照预设面板）：白点 `[38]` 进「常用参数」，**复用参数表那一条** `EditorParam`
     * （不是第二份滑杆 / 第二套量程），黑点 `[37]` 不在一级页（依据见 `FILMIC_WHITE_EV_SLOT` 的 KDoc）。
     */
    @Test
    fun firstLevelGivesTheWhitePointASliderAndReusesTheSameParam() {
        assertTrue("白点在一级页的常用参数集合里", FILMIC_WHITE_EV_SLOT in QUICK_ADJUST_SLOTS)
        assertFalse("黑点不在一级页（在「完整参数 >」）", 37 in QUICK_ADJUST_SLOTS)
        assertEquals("一级页用的是参数表那一条（同一个对象）", white, CAMERA_TUNABLE_SLOT_PARAM.getValue(38))
        // 源码哨兵：一级页那一段真的渲染了白点行，且从 CAMERA_TUNABLE_SLOT_PARAM 取参数项
        val quick = panel
            .substringAfter("private fun PresetQuickAdjustPanel(")
            .substringBefore("private fun PresetParamLabelRow(")
        assertTrue(
            "一级页必须真的渲染白点滑杆（常量进集合但没渲染 = 用户看不到）",
            quick.contains("label = \"白点\"") && quick.contains("CAMERA_TUNABLE_SLOT_PARAM.getValue(FILMIC_WHITE_EV_SLOT)"),
        )
        // 一级页那行的 label 必须与参数表那条**逐字一致**（不许第二份译名 —— 与 PresetDetail 的
        // 详情名护栏同一条纪律；参数表改名而一级页不跟 ⇒ 同一个参数两个名字）
        assertTrue(
            "一级页白点行的 label「白点」必须等于参数表那条的 label「${white.label}」",
            quick.contains("label = \"${white.label}\""),
        )
        assertTrue("白点行走的是既有滑杆组件 PresetParamSliderRow", quick.contains("PresetParamSliderRow("))
        // 不许第二份参数项（量程 / 读数 / write 必须只有 ParamControls.kt 那一份）
        assertFalse(
            "CameraPresetPanel.kt 里不许再定义一条 filmicWhite 参数项（第二份必然漂移）",
            panel.contains("key = \"filmicWhite\""),
        )
        assertTrue(
            "参数表里两条都在（唯一维护点）",
            paramControls.contains("key = \"filmicBlack\"") && paramControls.contains("key = \"filmicWhite\""),
        )
        // 一级页的读数口径 = 参数表（同一个 format）—— 白点滑杆显示的就是 formatEvSlot
        assertTrue(
            "两条参数项的读数都用 formatEvSlot（槽位即 EV，不是 formatEv 的 UI 域 ×3）",
            paramControls.contains("format = { formatEvSlot(it) }"),
        )
        // 数值列宽必须容得下带单位的 EV 读数：`-12.0 EV` = 8 字符 × ≈7.2dp @12sp 等宽 ≈ 58dp。
        // 46dp（PS-8 给 `0..150` 定的）会把读数裁成 `+2.7 E`（单位被截掉 = 用户看不出是 EV）。
        assertTrue(
            "一级页数值列宽 ${PRESET_VALUE_WIDTH_DP}dp 必须容得下 `-12.0 EV`（≈58dp）",
            PRESET_VALUE_WIDTH_DP >= 58f,
        )
    }
}
