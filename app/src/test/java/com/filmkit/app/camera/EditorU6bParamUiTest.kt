package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ U-6b：**把引擎已支持、预设能承载、但面板没有滑杆的 5 条风格参数接进参数面板**的纯逻辑护栏。
 *
 *  - **分离色调 · 暖 [23] / 冷 [24]**（「滤镜」组）—— −1..+1 直通，读数 ±100
 *  - **黑白 [25]**（「滤镜」组）—— 0..1 **连续**混合比（0 = 彩色原样、1 = 全黑白），读数 `关` / 0..100
 *  - **胶片曲线 · 肩部 [5..7] / 趾部 [8..10]**（「调整」组，紧跟对比度）—— 0..1 latitude，
 *    一条滑杆同写 R/G/B 三槽，读数两位小数
 *
 * 三条最容易悄悄错、错了用户直接看得见的事实（本文件的三个专章）：
 *  1. **⚠⚠ [5..10] 的恒等值是 `0.01` 而不是 `0`** —— latitude 语义下 `0` = 过渡区紧贴中灰 =
 *     **极端 S 曲线**（`CameraPresets.kt` 的 `[5..10]` 专章同样警告）。滑杆**允许**拖到 0
 *     （用户主动要极端曲线），但 **reset 必须回 0.01**、中点标记也在 0.01。
 *     这是本卡最容易踩的坑，`resetRestoresLatitudeToDefault...` 那条用例就是钉它的。
 *  2. **[25] 是连续量不是开关**：0.35 必须读成 `35`（旧详情的"`>= 0.5` 才算开"在滑杆能连续调
 *     之后是假读数）。
 *  3. **分组/顺序**：分离色调/黑白进「滤镜」组（黑白在前），胶片曲线进「调整」组且紧跟对比度
 *     —— 直接正则解析 `EditorScreen.kt` 的参数表（与 `PresetDetailTest` 同一手法，
 *     不靠"下次记得同步"）。
 *
 * 滑杆拖动、图标行、面板布局要真机（Compose + GL），JVM 单测跑不了 —— 这里守的是**数值链路**
 * （UI 值 ↔ 槽位 ↔ 读数 ↔ 重置），也就是"滑杆动了画面到底变不变"的那一段。
 */
class EditorU6bParamUiTest {

    // ── 面板参数表：分组 + 顺序 + 槽位（解析 EditorScreen.kt，不硬编码副本）────────────

    private data class PanelParam(val label: String, val slot: Int)

    private fun panelParams(section: String, until: String): List<PanelParam> {
        val src = File("src/main/java/com/filmkit/app/ui/EditorScreen.kt").readText()
        val body = src.substringAfter("private val $section").substringBefore("private val $until")
        val labelRe = Regex("""label = "([^"]+)"""")
        val readRe = Regex("""read = \{([^}]*)\}""")
        val slotRe = Regex("""it\[(\d+)]""")
        return body.split("EditorParam(").drop(1).mapNotNull { raw ->
            val block = raw.substringBefore("\n    ),")
            val label = labelRe.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val read = readRe.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val slot = slotRe.find(read)?.groupValues?.get(1)?.toInt() ?: return@mapNotNull null
            PanelParam(label, slot)
        }
    }

    /**
     * 「调整」组的**完整顺序**（U-6b 后）：曝光 → 白平衡两条 → 对比度 → **胶片曲线两条** →
     * 四条影调分区 → 饱和度 → 清晰度 → RGB。
     *
     * 断言的是**全序列**而不是"包含这两条"：顺序本身就是 U-6b 的设计决定（曲线与对比度同族、
     * 紧跟其后），只查包含会让"随手塞到末尾"照样绿。
     */
    @Test
    fun adjustGroupPutsFilmCurveRightAfterContrast() {
        assertEquals(
            listOf(
                "曝光", "色温", "色调", "对比度",
                "胶片曲线 · 肩部", "胶片曲线 · 趾部",
                "高光", "阴影", "白色色阶", "黑色色阶",
                "饱和度", "清晰度", "红", "绿", "蓝",
            ),
            panelParams("AdjustParams", "ColorParams").map { it.label },
        )
    }

    /**
     * 「滤镜」组：**黑白在最前、分离色调暖冷相邻、褪色在最后**。
     *
     * 顺序理由（`ColorParams` 的 KDoc）：整体去色在分区调色**之前**（引擎里 `uMono` 的 `mix`
     * 就在分离色调之后 ⇒ 顺序反了会诱导用户先染色再被去色抹掉）；暖/冷是一对必须相邻；
     * 褪色与它们同属"胶片风格化"。
     */
    @Test
    fun colorGroupOrdersMonoBeforeSplitTonesAndKeepsThePairAdjacent() {
        assertEquals(
            listOf("黑白", "分离色调 · 暖", "分离色调 · 冷", "褪色"),
            panelParams("ColorParams", "LutStrengthParam").map { it.label },
        )
    }

    /** 5 个新滑杆读的槽位必须正是设计表里的那 5 个（读错槽位 = 滑杆显示别的参数的值）。 */
    @Test
    fun newSlidersReadTheDesignedSlots() {
        val adjust = panelParams("AdjustParams", "ColorParams").associate { it.label to it.slot }
        val color = panelParams("ColorParams", "LutStrengthParam").associate { it.label to it.slot }
        assertEquals(5, adjust["胶片曲线 · 肩部"])
        assertEquals(8, adjust["胶片曲线 · 趾部"])
        assertEquals(23, color["分离色调 · 暖"])
        assertEquals(24, color["分离色调 · 冷"])
        assertEquals(25, color["黑白"])
    }

    /**
     * 这 5 条滑杆写到的槽位都**能进预设**（`presetSlotIncluded`）⇒ 详情页看得到、保存预设留得住、
     * 套用能回到画面。若哪天策略层把它们排除，滑杆就成了"调得动但存不下"的假参数。
     */
    @Test
    fun newSlotsAreCarriableByPresets() {
        for (slot in listOf(5, 6, 7, 8, 9, 10, 23, 24, 25)) {
            assertTrue("槽 $slot 必须能进预设（否则滑杆调了存不下）", presetSlotIncluded(slot))
        }
    }

    // ── 分离色调 [23][24]：−1..+1 直通，读数 ±100 ──────────────────────────────

    @Test
    fun splitToneMapsFullRangeAndReadsAsOffset100() {
        assertEquals(0.0, splitSlot(0f), 0.0)
        assertEquals(1.0, splitSlot(1f), 1e-9)
        assertEquals(-1.0, splitSlot(-1f), 1e-9)
        // 越界夹住（UI 手势产生不了越界值；坏快照不该把越界强度带进引擎）
        assertEquals(1.0, splitSlot(9f), 1e-9)
        assertEquals(-1.0, splitSlot(-9f), 1e-9)
        assertEquals(0.0, splitSlot(Float.NaN), 0.0)
        assertEquals(0.0, splitSlot(Float.POSITIVE_INFINITY), 0.0)
        // 槽位 → UI
        assertEquals(0f, splitUi(0.0), 0f)
        assertEquals(0.5f, splitUi(0.5), 1e-6f)
        assertEquals(-1f, splitUi(-9.0), 1e-6f)
        assertEquals(1f, splitUi(9.0), 1e-6f)
        assertEquals(0f, splitUi(Double.NaN), 0f)
        // 读数：`+50` / `0` / `-50`（±100 相对量，中点 0 —— 与影调分区同一口径，两边用户看到的语言一致）
        assertEquals("+50", formatOffset100(splitUi(splitSlot(0.5f)), ZERO_UI_PIVOT))
        assertEquals("0", formatOffset100(splitUi(splitSlot(0f)), ZERO_UI_PIVOT))
        assertEquals("-50", formatOffset100(splitUi(splitSlot(-0.5f)), ZERO_UI_PIVOT))
        // 中点标记：恒等 0 在量程正中
        assertEquals(0.5f, pivotFraction(SPLIT_UI_RANGE, ZERO_UI_PIVOT)!!, 1e-6f)
        // 往返不漂
        for (i in -100..100) {
            val ui = i / 100f
            assertEquals("分离色调读数 $i 往返漂了", i, (splitUi(splitSlot(ui)) * 100f).roundToInt())
        }
    }

    // ── 黑白 [25]：0..1 直通，**连续**（不是开关）─────────────────────────────

    @Test
    fun monoIsContinuousAndReadsOffOnlyAtZero() {
        assertEquals(0.0, monoSlot(0f), 0.0)
        assertEquals(1.0, monoSlot(1f), 1e-9)
        assertEquals(1.0, monoSlot(9f), 1e-9)
        assertEquals(0.0, monoSlot(-9f), 1e-9)
        assertEquals(0.0, monoSlot(Float.NaN), 0.0)
        assertEquals(0f, monoUi(0.0), 0f)
        // ★ 中间值必须原样读出来：0.35 不是"开/关"能表达的（引擎 `mix(·, ·, uMono)` 是连续混合）
        assertEquals(0.35f, monoUi(0.35), 1e-6f)
        assertEquals(0.9f, monoUi(0.9), 1e-6f)
        assertEquals(1f, monoUi(9.0), 1e-6f)
        assertEquals("负槽位 = 关（mix 的负系数无意义）", 0f, monoUi(-1.0), 0f)
        assertEquals(0f, monoUi(Double.NaN), 0f)
        // 读数：0 → `关`，否则 0..100 整数
        assertEquals("关", formatMono(0f))
        assertEquals("35", formatMono(0.35f))
        assertEquals("90", formatMono(0.9f))
        assertEquals("100", formatMono(1f))
        // 越界夹到端点、坏数据回落 `关`（读数绝不出现 `NaN` 或越界值）
        assertEquals("100", formatMono(1.8f))
        assertEquals("关", formatMono(-0.5f))
        assertEquals("关", formatMono(Float.NaN))
        // ★ 旧详情口径（`>= 0.5` 才算"开"）会把这个值读成"关" —— 这正是本卡修掉的假读数
        assertEquals("35", formatMono(monoUi(monoSlot(0.35f))))
        // 基准 0 = 关，落在量程最左端 ⇒ 不画中点标记（端点本身就是标记）
        assertNull(pivotFraction(MONO_UI_RANGE, ZERO_UI_PIVOT))
        // 往返不漂
        for (i in 0..100) {
            val ui = i / 100f
            assertEquals("黑白读数 $i 往返漂了", i, (monoUi(monoSlot(ui)) * 100f).roundToInt())
        }
    }

    // ── 胶片曲线 [5..10]：⚠ 恒等 0.01（不是 0）────────────────────────────────

    /**
     * **本卡最关键的一条**：`0.01` 是恒等值 / reset 目标 / 中点位置，`0` 是**合法的极端值**。
     *
     * 若把 [LATITUDE_UI_PIVOT] 写成 0（下一张卡"顺手简化"最容易发生的事）：
     *  - 单条 reset 与「重置本组」会把画面变成极端 S 曲线；
     *  - 中点标记会跑到最左端（`pivotFraction` 返回 null ⇒ 标记消失，用户看不出默认在哪）；
     *  - 坏数据回落 0 会把"读不出来"直接变成"极端曲线"。
     */
    @Test
    fun latitudeIdentityIsZeroPointZeroOneNotZero() {
        // 恒等值本身
        assertEquals(0.01f, LATITUDE_UI_PIVOT, 0f)
        assertTrue("恒等值必须严格大于量程左端（0 = 极端 S 曲线，不是无效果）", LATITUDE_UI_PIVOT > LATITUDE_UI_RANGE.start)
        // 默认基线（`CameraPresets.standard()` 的 [5..10]）读出来必须正好是恒等值
        val base = CameraPresets.standard()
        for (i in 5..10) assertEquals("标准基线 [$i] 读出来必须是 0.01", LATITUDE_UI_PIVOT, latitudeUi(base[i]), 1e-9f)
        // UI ↔ 槽位
        assertEquals(LATITUDE_UI_PIVOT.toDouble(), latitudeSlot(0.01f), 0.0)
        assertEquals(0.3f.toDouble(), latitudeSlot(0.3f), 0.0)
        assertEquals(0.0, latitudeSlot(0f), 0.0)          // 允许拖到最左端 = 极端曲线（有意的能力）
        assertEquals(1.0, latitudeSlot(9f), 1e-9)
        assertEquals(0.0, latitudeSlot(-9f), 0.0)
        assertEquals(0f, latitudeUi(0.0), 0f)
        assertEquals(0.3f, latitudeUi(0.3), 1e-6f)
        assertEquals(1f, latitudeUi(9.0), 1e-6f)
        // 坏数据回落**恒等 0.01**（不是 0）
        assertEquals(LATITUDE_UI_PIVOT.toDouble(), latitudeSlot(Float.NaN), 0.0)
        assertEquals(0.01f, latitudeUi(Double.NaN), 0f)
        assertEquals(0.01f, latitudeUi(Double.POSITIVE_INFINITY), 0f)
        // 中点标记画在 0.01 处（不是端点 ⇒ 不是 null；也不是 0）
        assertEquals(0.01f, pivotFraction(LATITUDE_UI_RANGE, LATITUDE_UI_PIVOT)!!, 1e-6f)
        // 读数两位小数（整数化会把 0.01 / 0.02 都显示成 1 / 2）
        assertEquals("0.01", formatDecimals2(LATITUDE_UI_PIVOT))
        assertEquals("0.30", formatDecimals2(0.3f))
        assertEquals("1.00", formatDecimals2(1f))
        assertEquals("0.00", formatDecimals2(0f))
        assertEquals("0.10", formatDecimals2(0.099f))
        assertEquals("0.00", formatDecimals2(Float.NaN))
        // 往返不漂（含 0.01 这一档）
        for (i in 0..100) {
            val ui = i / 100f
            assertEquals("latitude 读数 $i 往返漂了", i, (latitudeUi(latitudeSlot(ui)) * 100f).roundToInt())
        }
    }

    /** 一条滑杆**同写 R/G/B 三槽**（引擎是 per-channel；逐通道差异仍由预设数据层表达）。 */
    @Test
    fun latitudeWritesThreeChannelsAtOnceAndTouchesNothingElse() {
        val p = CameraPresets.standard()
        val before = p.copyOf()
        writeLatitude(p, 5, 0.3f)      // 肩部
        writeLatitude(p, 8, 0.7f)      // 趾部
        for (i in 5..7) assertEquals("肩部 [$i] 必须是同一个值", 0.3, p[i], 1e-6)
        for (i in 8..10) assertEquals("趾部 [$i] 必须是同一个值", 0.7, p[i], 1e-6)
        for (i in p.indices) {
            if (i in 5..10) continue
            assertEquals("槽位 $i 被误改", before[i], p[i], 0.0)
        }
        // 越界 / 坏数据：夹到端点、回落 0.01（不是 0）
        writeLatitude(p, 5, 9f)
        assertEquals(1.0, p[7], 1e-9)
        writeLatitude(p, 5, Float.NaN)
        for (i in 5..7) assertEquals("坏数据必须回落恒等 0.01", LATITUDE_UI_PIVOT.toDouble(), p[i], 0.0)
    }

    // ── 重置：本组两条入口都必须回恒等值（0.01 / 0）──────────────────────────

    /**
     * 「调整」组重置：`[5..10]` → **0.01**（这条就是 mutation 自检锁定的用例 ——
     * 把 [LATITUDE_UI_PIVOT] 改成 0 会让 `paramsUnchanged` 那条断言红）。
     *
     * 断言用 `paramsUnchanged(p, CameraPresets.standard())` 而不是逐槽比字面量：
     * **标准基线就是"恒等"的定义**（ADR-1 单一参数源），逐槽硬编码 0.01 只是它的副本。
     */
    @Test
    fun resetRestoresLatitudeToDefaultAndMonoSplitToZero() {
        val q = CameraPresets.standard()
        for (i in 5..10) q[i] = 0.5
        assertFalse("拖走 latitude ⇒ 必须判为有修改", paramsUnchanged(q, CameraPresets.standard()))
        resetBasicParams(q)
        assertTrue(
            "「调整」组重置后必须与标准基线逐槽相同（latitude 回 0.01；回 0 会变成极端 S 曲线）",
            paramsUnchanged(q, CameraPresets.standard()),
        )

        val p = CameraPresets.standard()
        writeLatitude(p, 5, 0.8f)      // 肩部
        writeLatitude(p, 8, 0.9f)      // 趾部
        p[23] = splitSlot(0.6f); p[24] = splitSlot(-0.4f); p[25] = monoSlot(0.35f); p[94] = fadeSlot(0.5f)
        p[66] = -0.5   // 暗角（不属于这两组）
        p[26] = 0.3    // 颗粒（同上）
        p[34] = 0.9    // LUT 强度（同上）

        // 单条 reset 走参数表的 pivot（`resetParam` = `write(it, pivot)`）⇒ 这里等价地写一遍
        writeLatitude(p, 5, LATITUDE_UI_PIVOT)
        for (i in 5..7) assertEquals("单条 reset 必须回 0.01", LATITUDE_UI_PIVOT.toDouble(), p[i], 0.0)
        assertEquals("单条 reset 不碰另一条曲线", 0.9f.toDouble(), p[8], 0.0)
        writeLatitude(p, 5, 0.8f)      // 还原，继续测「本组重置」

        // 「调整」组重置：胶片曲线回 0.01；色彩组与别的组一个都不动
        resetBasicParams(p)
        for (i in 5..10) assertEquals("[$i] 本组重置后必须是 0.01", LATITUDE_UI_PIVOT.toDouble(), p[i], 0.0)
        assertEquals(splitSlot(0.6f), p[23], 0.0)
        assertEquals(splitSlot(-0.4f), p[24], 0.0)
        assertEquals(monoSlot(0.35f), p[25], 0.0)
        assertEquals(fadeSlot(0.5f), p[94], 0.0)
        assertEquals(-0.5, p[66], 1e-9)
        assertEquals(0.3, p[26], 1e-9)
        assertEquals(0.9, p[34], 1e-9)

        // 「滤镜」组重置：分离色调 / 黑白 / 褪色回 0；胶片曲线（属于调整组）**不动**
        writeLatitude(p, 5, 0.7f)
        p[23] = splitSlot(0.6f); p[24] = splitSlot(-0.4f); p[25] = monoSlot(0.35f); p[94] = fadeSlot(0.5f)
        resetColorParams(p)
        assertEquals(0.0, p[23], 1e-9)
        assertEquals(0.0, p[24], 1e-9)
        assertEquals(0.0, p[25], 1e-9)
        assertEquals(0.0, p[94], 1e-9)
        assertEquals("胶片曲线属于「调整」组 ⇒ 滤镜组重置不动它", 0.7f.toDouble(), p[5], 0.0)
        assertEquals(-0.5, p[66], 1e-9)
        assertEquals("LUT 强度不由重置清（素材选择保留）", 0.9, p[34], 1e-9)
    }

    // ── 五条滑杆的完整回路：滑杆 → 槽位 → 读数 ────────────────────────────────

    @Test
    fun fiveNewSlidersWriteOnlyTheirOwnSlotsAndReadBack() {
        val p = CameraPresets.standard()
        val before = p.copyOf()
        // 五条各拖一次（值取任务卡表格里的典型落点）
        writeLatitude(p, 5, 0.30f)
        writeLatitude(p, 8, 0.70f)
        p[25] = monoSlot(0.35f)
        p[23] = splitSlot(0.5f)
        p[24] = splitSlot(-0.5f)
        for (i in p.indices) {
            if (i in 5..10 || i in 23..25) continue
            assertEquals("槽位 $i 被误改", before[i], p[i], 0.0)
        }
        // 面板读数（滑杆值 → 槽位 → 读数的完整回路 = 用户在屏幕上看到的那串字符）
        assertEquals("0.30", formatDecimals2(latitudeUi(p[5])))
        assertEquals("0.70", formatDecimals2(latitudeUi(p[8])))
        assertEquals("35", formatMono(monoUi(p[25])))
        assertEquals("+50", formatOffset100(splitUi(p[23]), ZERO_UI_PIVOT))
        assertEquals("-50", formatOffset100(splitUi(p[24]), ZERO_UI_PIVOT))
        // 端点读数也是用户可见的（拖到最左/最右）
        assertEquals("0.00", formatDecimals2(latitudeUi(latitudeSlot(0f))))
        assertEquals("1.00", formatDecimals2(latitudeUi(latitudeSlot(1f))))
        assertEquals("100", formatMono(monoUi(monoSlot(1f))))
        assertEquals("+100", formatOffset100(splitUi(splitSlot(1f)), ZERO_UI_PIVOT))
        assertEquals("-100", formatOffset100(splitUi(splitSlot(-1f)), ZERO_UI_PIVOT))
        // 改回恒等值 ⇒ 判为无修改（导出直通原片字节）
        val q = CameraPresets.standard()
        writeLatitude(q, 5, 0.4f)
        assertFalse("拖走 latitude ⇒ 判为有修改", paramsUnchanged(q, CameraPresets.standard()))
        writeLatitude(q, 5, LATITUDE_UI_PIVOT)
        assertTrue(paramsUnchanged(q, CameraPresets.standard()))
    }
}
