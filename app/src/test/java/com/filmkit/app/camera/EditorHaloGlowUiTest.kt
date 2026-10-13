package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import com.filmkit.app.ui.presetDetailSections
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **SG-2**：光晕 `[62..65]`（E4-5）与柔光 `[95..97]`（SG-1）接进编辑器参数面板的纯逻辑护栏。
 *
 * 为什么本卡必须有这组测试：用户 2026-10-11 说"光晕、柔光要先做"，但引擎做完之后**用户在
 * 面板里手动调不到**（没有滑杆）⇒ 只能靠套用预设间接生效，等于没做。本卡的验收判据是
 * "7 条滑杆都能改到引擎参数"，而滑杆本体（Compose）JVM 测不了 ⇒ 把**能机械化验证的三条**
 * 全部钉在这里：
 *
 *  1. **读写往返**（[normalizedSlot] / [normalizedUi]）：0..1 全量程 101 档往返不丢读数、
 *     越界夹紧、坏数据回落**该槽的引擎默认值**（不是统一的 0）；
 *  2. **pivot 与引擎中性值一致**（本卡最容易做错、且错了用户直接看得见的一条）：七个 pivot
 *     同时与 **Kotlin 侧**（`CameraPresets.standard()`）和 **C++ 侧**（解析
 *     `core/engine/src/film/FilmParams.h` 的 `float <字段> = <默认值>f;  // <槽位>`）对拍 ——
 *     写错的表现是"用户一 reset 画面就变"（[62]/[95] 尤其：它们的 pivot 必须是 **0 = 关**，
 *     不是某个"看起来像默认值"的数）；
 *  3. **面板参数表真的接上了这 7 条**（正则解析 `ParamControls.kt` 的 `EditorParam` 表（★ PS-3：参数表从 `EditorScreen.kt` **逐字搬移**到这里，编辑器与拍照界面共用），
 *     与 `PresetDetailTest` 同一手法）：槽位 / 量程 / pivot 三格逐条对拍 —— 只定义常量而忘了
 *     加滑杆，或者 read 与 write 写到了不同槽位（复制粘贴的经典错），本条立刻红。
 *
 * 另有两条**文案**护栏（与 `PresetDetailTest` 的跨文件对拍互补）：
 *  - 详情名与面板 label **逐字相同**（质感组 10 条；面板扩组后组内有 3 条"强度"，
 *    只靠"包含"关系挡不住"光晕 · 强度"被写成"柔光 · 强度"这类错位）；
 *  - 读数口径（`formatOff100` 的 `关` / `formatDecimals2` 的两位小数）。
 *
 * ⚠ **未真机验证**：本文件全是纯逻辑。滑杆手感、10 条参数的横向滚动、组名/图标更换后的
 * 观感都要真机确认，见 `docs/reports/sg2-halo-glow-ui-REPORT.md`。
 */
class EditorHaloGlowUiTest {

    /** 本卡的七个槽位 → pivot 常量（**表驱动**：新增参数只加一行，不留"忘了测某条"的缺口）。 */
    private val pivots = linkedMapOf(
        62 to HALO_UI_PIVOT,             // 光晕 · 强度（0 = 关）
        63 to HALO_THRESHOLD_UI_PIVOT,   // 光晕 · 触发阈值
        64 to HALO_RADIUS_UI_PIVOT,      // 光晕 · 半径
        65 to HALO_WARMTH_UI_PIVOT,      // 光晕 · 暖度
        95 to GLOW_UI_PIVOT,             // 柔光 · 强度（0 = 关）
        96 to GLOW_RADIUS_UI_PIVOT,      // 柔光 · 半径
        97 to GLOW_THRESHOLD_UI_PIVOT,   // 柔光 · 触发阈值
    )

    // ── ① 读写往返 ───────────────────────────────────────────────────────────

    @Test
    fun everySlotRoundTripsAcrossFullRange() {
        for ((slot, pivot) in pivots) {
            for (i in 0..100) {
                val ui = i / 100f
                val back = normalizedUi(normalizedSlot(ui), pivot)
                assertEquals("槽 $slot 在 $i% 处往返漂了", ui, back, 1e-6f)
            }
        }
    }

    @Test
    fun writeClampsToUnitRangeAndReadClampsOutOfRangeSlots() {
        // 手势产生不了越界值（range = HALO_GLOW_UI_RANGE），但坏快照 / 未来改量程不该把非法值
        // 带进引擎：写入侧夹到端点
        assertEquals(1.0, normalizedSlot(9f), 0.0)
        assertEquals(0.0, normalizedSlot(-9f), 0.0)
        // 读取侧同理（快照里出现越界只可能是手改 prefs）
        assertEquals(1f, normalizedUi(9.0, HALO_UI_PIVOT), 0f)
        assertEquals(0f, normalizedUi(-9.0, HALO_UI_PIVOT), 0f)
        // 量程常量与映射函数共用同一份定义（滑杆拖得到 = 槽位收得下）
        assertEquals(0f, HALO_GLOW_UI_RANGE.start, 0f)
        assertEquals(1f, HALO_GLOW_UI_RANGE.endInclusive, 0f)
        assertEquals(HALO_GLOW_UI_RANGE.endInclusive.toDouble(), normalizedSlot(HALO_GLOW_UI_RANGE.endInclusive), 0.0)
        assertEquals(HALO_GLOW_UI_RANGE.start.toDouble(), normalizedSlot(HALO_GLOW_UI_RANGE.start), 0.0)
    }

    /**
     * ★ 坏数据回落**该槽自己的**引擎默认值，不是统一的 0。
     *
     * 统一回 0 会把"读不出来"变成"阈值与半径被拖到最左端"（画面突变）—— 与 `latitudeUi`
     * 回落 0.01、`tempUi` 越界回落 6500K 是同一条纪律。这条测试把七个槽**逐条**钉住：
     * 谁把 fallback 参数化错了（例如 [63] 传了 `HALO_UI_PIVOT`），这里立刻红。
     */
    @Test
    fun badSlotDataFallsBackToEachSlotsOwnEngineDefault() {
        for ((slot, pivot) in pivots) {
            assertEquals("槽 $slot 的 NaN 回落值", pivot, normalizedUi(Double.NaN, pivot), 0f)
            assertEquals("槽 $slot 的 +Inf 回落值", pivot, normalizedUi(Double.POSITIVE_INFINITY, pivot), 0f)
            assertEquals("槽 $slot 的 −Inf 回落值", pivot, normalizedUi(Double.NEGATIVE_INFINITY, pivot), 0f)
        }
        // 两个"0 = 关"的强度槽：坏数据必须回 0（回落成 0.8 会让画面凭空多出一层光晕）
        assertEquals(0f, normalizedUi(Double.NaN, HALO_UI_PIVOT), 0f)
        assertEquals(0f, normalizedUi(Double.NaN, GLOW_UI_PIVOT), 0f)
        // 非零默认值的三条：回落值就是各自的默认值，不是 0
        assertEquals(0.80f, normalizedUi(Double.NaN, HALO_THRESHOLD_UI_PIVOT), 0f)
        assertEquals(0.45f, normalizedUi(Double.NaN, HALO_RADIUS_UI_PIVOT), 0f)
        assertEquals(0.70f, normalizedUi(Double.NaN, GLOW_THRESHOLD_UI_PIVOT), 0f)
    }

    // ── ② pivot 与引擎中性值一致（本卡最关键的一条）───────────────────────────

    /**
     * Kotlin 侧中性基线：`CameraPresets.standard()` 就是"所有参数无效果"的那份数组
     * （引擎下发与预设缩放的 `neutral` 都取它）⇒ pivot 必须逐槽等于它。
     */
    @Test
    fun everyPivotEqualsTheNeutralValueInCameraPresets() {
        val neutral = CameraPresets.standard()
        assertEquals("契约长度（阶段 0 引擎插槽起 99）", 99, neutral.size)
        for ((slot, pivot) in pivots) {
            assertEquals(
                "槽 $slot 的 pivot 与 CameraPresets.standard() 的中性值不一致 —— " +
                    "「重置」会把画面改到非恒等状态",
                neutral[slot].toFloat(),
                pivot,
                0f,
            )
        }
    }

    /**
     * C++ 侧权威默认值：解析 `FilmParams.h` 的 `float <字段> = <值>f;  // <槽位>`。
     *
     * 为什么两处都要对拍：`CameraPresets.standard()` 是**手抄**的镜像，抄错了它自己不会红；
     * 而 `FilmParams.h` 是引擎真正读的默认值（`fromDoubles` 只在长度不足时才用它，但
     * "pivot = 引擎中性值"的定义就是它）。两边同时相等 ⇒ 抄写错误无处可藏。
     */
    @Test
    fun everyPivotEqualsTheDefaultDeclaredInFilmParamsHeader() {
        val defaults = engineScalarDefaults()
        for ((slot, pivot) in pivots) {
            val want = defaults[slot]
            assertTrue("FilmParams.h 里没有槽 $slot 的标量默认值（解析失败或槽位写错）", want != null)
            assertEquals("槽 $slot 的 pivot 与 FilmParams.h 的默认值不一致", want!!.toFloat(), pivot, 0f)
        }
        // 顺带把两条链路的"关"钉死：0 = 整链旁路（零 pass 零纹理），不是"很小的效果"
        assertEquals(0.0, defaults[62]!!, 0.0)
        assertEquals(0.0, defaults[95]!!, 0.0)
    }

    /**
     * `FilmParams.h` 的 `float <字段> = <值>f;  // <槽位>` ⇒ 槽位 → 默认值。
     * 数组字段（`float shoulder[3] = …;  // 5..7`）**不匹配**（名字后面是 `[`，不是 `=`）
     * ⇒ 只取标量，正是本卡要的那七个。
     */
    private fun engineScalarDefaults(): Map<Int, Double> {
        val src = File("../core/engine/src/film/FilmParams.h").readText()
        val re = Regex("""float\s+(\w+)\s*=\s*(-?[\d.]+)f?\s*;\s*//\s*(\d+)\b""")
        return re.findAll(src).associate { m -> m.groupValues[3].toInt() to m.groupValues[2].toDouble() }
    }

    // ── ③ 面板参数表真的接上了这 7 条（正则解析 ParamControls.kt；★ PS-3 搬移后）───────────────

    private class PanelParam(
        val key: String,
        val label: String,
        val rangeExpr: String,
        val pivotExpr: String,
        val readSlot: Int,
        val writeSlot: Int,
    )

    /**
     * 解析 `ParamControls.kt` 的 `EditorParam` 表（★ PS-3：参数表从 `EditorScreen.kt` **逐字搬移**到这里，编辑器与拍照界面共用；与 `PresetDetailTest.editorParamTable` 同一
     * 手法：块内取 `label` / `range` / `pivot` / `read` / `write`）。`LutStrengthParam` 的
     * read 是 `p[34]`（没有 `it[N]`）⇒ 自然被跳过。
     */
    private fun editorParamTable(): List<PanelParam> {
        val src = File("src/main/java/com/filmkit/app/ui/ParamControls.kt").readText()
        val keyRe = Regex("""key = "(\w+)"""")
        val labelRe = Regex("""label = "([^"]+)"""")
        val rangeRe = Regex("""range = ([\w.]+)""")
        val pivotRe = Regex("""pivot = ([\w.]+)""")
        val readRe = Regex("""read = \{([^}]*)\}""")
        val writeRe = Regex("""write = \{([^}]*)\}""")
        val itSlotRe = Regex("""it\[(\d+)]""")
        val pSlotRe = Regex("""p\[(\d+)]""")
        return src.split("EditorParam(").drop(1).mapNotNull { raw ->
            val block = raw.substringBefore("\n    ),")
            val label = labelRe.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val read = readRe.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val readSlot = itSlotRe.find(read)?.groupValues?.get(1)?.toInt() ?: return@mapNotNull null
            val write = writeRe.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val writeSlot = pSlotRe.find(write)?.groupValues?.get(1)?.toInt() ?: return@mapNotNull null
            PanelParam(
                keyRe.find(block)?.groupValues?.get(1) ?: "",
                label,
                rangeRe.find(block)?.groupValues?.get(1) ?: "",
                pivotRe.find(block)?.groupValues?.get(1) ?: "",
                readSlot,
                writeSlot,
            )
        }
    }

    /** pivot 常量名（**面板表里必须写常量名**，不能写数字字面量 —— 否则本测试没法把它接回常量）。 */
    private val pivotExprOf = mapOf(
        62 to "HALO_UI_PIVOT",
        63 to "HALO_THRESHOLD_UI_PIVOT",
        64 to "HALO_RADIUS_UI_PIVOT",
        65 to "HALO_WARMTH_UI_PIVOT",
        95 to "GLOW_UI_PIVOT",
        96 to "GLOW_RADIUS_UI_PIVOT",
        97 to "GLOW_THRESHOLD_UI_PIVOT",
    )

    @Test
    fun allSevenSlidersAreWiredIntoThePanelTableWithCorrectSlots() {
        val bySlot = editorParamTable().associateBy { it.readSlot }
        for ((slot, pivotExpr) in pivotExprOf) {
            val p = bySlot[slot]
            assertTrue("槽 $slot 没有滑杆进 EditorScreen 的 EditorParam 表（用户手动调不到）", p != null)
            assertEquals("槽 $slot 的 read 与 write 必须落在同一个槽位（复制粘贴的经典错）", slot, p!!.writeSlot)
            assertEquals("槽 $slot 的量程必须用与映射函数同一份定义", "HALO_GLOW_UI_RANGE", p.rangeExpr)
            assertEquals("槽 $slot 的 pivot 必须写成常量名（好让单测把它接回引擎默认值）", pivotExpr, p.pivotExpr)
        }
        // 质感组 10 条（暗角 3 + 光晕 4 + 柔光 3）：一条不多一条不少
        val textureSlots = editorParamTable().map { it.readSlot }.filter { it in 62..68 || it in 95..97 }.sorted()
        assertEquals(listOf(62, 63, 64, 65, 66, 67, 68, 95, 96, 97), textureSlots)
    }

    /**
     * 七个槽位都能进预设（`presetSlotIncluded`）—— 与 `preset-DESIGN.md` §1.6 能力矩阵里
     * "进预设？"列的 ✅ 是**同一个判据**：面板调的这七个槽，预设**存得下**
     * （被排除的只有 `[34]` LUT 强度 / `[29]` 退役槽 / 引擎旋钮 / 解码 / 局部那几类）。
     * 不成立的话，"用户调好 → 存成预设 → 换台设备套用"这条链路会静默丢参数。
     */
    @Test
    fun allSevenSlotsCanBeStoredInPresets() {
        for (slot in pivots.keys) {
            assertTrue("槽 $slot 被 presetSlotIncluded 排除 ⇒ 面板能调但预设存不下", presetSlotIncluded(slot))
        }
    }

    // ── ④ 文案：详情名与面板 label 逐字相同 + 读数口径 ─────────────────────────

    /**
     * 质感组 10 条的详情名必须与面板 label **逐字相同**（`PresetDetailTest` 的护栏只要求
     * "包含"，挡不住"光晕 · 强度"被写成"柔光 · 强度"—— 扩组后组内有 3 条"强度"）。
     */
    @Test
    fun textureDetailLabelsMatchPanelLabelsExactly() {
        val bySlot = editorParamTable().associateBy { it.readSlot }
        for (slot in listOf(62, 63, 64, 65, 66, 67, 68, 95, 96, 97)) {
            val panelLabel = bySlot[slot]?.label
            assertTrue("槽 $slot 不在面板表里", panelLabel != null)
            val row = presetDetailSections(mapOf(slot to 0.5)).single().rows.single()
            assertEquals("槽 $slot 的详情名必须逐字等于面板 label", panelLabel, row.label)
        }
    }

    @Test
    fun readoutsFollowTheTwoDeclaredFormats() {
        // 强度：0 → `关`，否则 0..100 整数（与黑白 [25] 同一口径 = formatOff100）
        assertEquals("关", formatOff100(normalizedUi(0.0, HALO_UI_PIVOT)))
        assertEquals("关", formatOff100(normalizedUi(0.0, GLOW_UI_PIVOT)))
        assertEquals("35", formatOff100(normalizedUi(0.35, HALO_UI_PIVOT)))
        assertEquals("100", formatOff100(normalizedUi(1.0, GLOW_UI_PIVOT)))
        assertEquals("关", formatOff100(normalizedUi(Double.NaN, HALO_UI_PIVOT)))
        // 阈值 / 半径 / 暖度：两位小数（整数化会把 0.45 与 0.50 显示成同一个数）
        assertEquals("0.80", formatDecimals2(normalizedUi(0.80, HALO_THRESHOLD_UI_PIVOT)))
        assertEquals("0.45", formatDecimals2(normalizedUi(0.45, HALO_RADIUS_UI_PIVOT)))
        assertEquals("0.40", formatDecimals2(normalizedUi(0.40, HALO_WARMTH_UI_PIVOT)))
        assertEquals("0.35", formatDecimals2(normalizedUi(0.35, GLOW_RADIUS_UI_PIVOT)))
        assertEquals("0.70", formatDecimals2(normalizedUi(0.70, GLOW_THRESHOLD_UI_PIVOT)))
        // 坏数据回落各自默认值 ⇒ 读数显示的是"默认"，绝不是 `NaN`
        assertEquals("0.80", formatDecimals2(normalizedUi(Double.NaN, HALO_THRESHOLD_UI_PIVOT)))
    }

    // ── ⑤ 「质感」组重置 ──────────────────────────────────────────────────────

    @Test
    fun groupResetZeroesTheThreeStrengthsAndKeepsTheRest() {
        val p = CameraPresets.standard()
        p[62] = 0.70; p[63] = 0.50; p[64] = 0.90; p[65] = 0.10
        p[66] = -0.50; p[67] = 150.0; p[68] = 20.0
        p[95] = 0.60; p[96] = 0.80; p[97] = 0.30
        p[4] = 1.5          // 组外槽位（对照组）
        resetFilmTextureParams(p)
        // 三条强度归 0 = 关（引擎侧各自整链旁路）
        assertEquals(0.0, p[62], 0.0)
        assertEquals(0.0, p[66], 0.0)
        assertEquals(0.0, p[95], 0.0)
        // 其余七条保持原值：强度为 0 时它们不可达，用户调好的阈值/半径不必重调
        assertEquals(0.50, p[63], 0.0)
        assertEquals(0.90, p[64], 0.0)
        assertEquals(0.10, p[65], 0.0)
        assertEquals(150.0, p[67], 0.0)
        assertEquals(20.0, p[68], 0.0)
        assertEquals(0.80, p[96], 0.0)
        assertEquals(0.30, p[97], 0.0)
        // 组外一个都不许动
        assertEquals(1.5, p[4], 0.0)
    }

    /** 重置后**整组回到画面恒等**：三条强度都是 0（引擎的旁路条件），读数都显示 `关`。 */
    @Test
    fun groupResetBringsTheWholeGroupBackToIdentity() {
        val p = CameraPresets.standard()
        p[62] = 0.9; p[66] = -0.9; p[95] = 0.9
        resetFilmTextureParams(p)
        assertEquals("关", formatOff100(normalizedUi(p[62], HALO_UI_PIVOT)))
        assertEquals("关", formatOff100(vigStrengthUi(p[66])))
        assertEquals("关", formatOff100(normalizedUi(p[95], GLOW_UI_PIVOT)))
    }
}
