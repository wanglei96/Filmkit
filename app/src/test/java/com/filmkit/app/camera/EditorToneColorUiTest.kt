package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2-14「七个新参数接进新面板」的纯逻辑护栏：色温 [88] / 色调 [89] / 高光 [90] / 阴影 [91] /
 * 白色色阶 [92] / 黑色色阶 [93] / 褪色 [94]。
 *
 * 为什么只测这些：滑杆拖动、图标行、面板布局要真机（Compose + GL），JVM 单测跑不了。这里守住
 * 三条最容易悄悄错、错了用户直接看得见的事实：
 *
 *  1. **⚠ 方向约定（本卡最容易做错的一条）**：`[88]` 存的是**目标白点的 mired 偏移** ——
 *     槽位**正 = 目标 K 更低 = 画面变冷**，与 K 的直觉方向相反。所以滑杆值必须是 **K**
 *     （右移 = K 大 = 变暖），写入时才换算成 mired。若把 mired 当滑杆值，滑杆方向与读数方向
 *     正好相反（`tempSlot` 的符号测试就是为了钉住这条）。
 *  2. **6500K ⇒ 槽位精确 0**：参考 mired 必须用 `1e6/6500` 表达式而不是 `153.846` 字面量 ——
 *     差 1.5e-4 就超过 ADR-13 的 `PARAM_EPS = 1e-4`，会把"滑杆停在 6500K"误判成用户修改
 *     （白渲染一次全分辨率）。
 *  3. **读数与中点**：色温 `5000K`（物理量 K）/ 色调与影调分区 `+50` / 褪色 `35`；中点位置
 *     由 `pivotFraction` 算（褪色的基准在端点 ⇒ 不画）。
 */
class EditorToneColorUiTest {

    // ── 色温 [88]：K ↔ mired 槽位（方向 + 端点 + 往返）───────────────────────

    @Test
    fun kelvinAtReferencePointMapsToZeroSlot() {
        // 6500K = 引擎的参考白点 ⇒ 槽位必须**精确** 0（不是 1.5e-4）
        assertEquals(0.0, tempSlot(6500f), 0.0)
        // 槽位 0 ⇒ 读数 6500K
        assertEquals(6500f, tempUi(0.0), 0f)
    }

    @Test
    fun kelvinEndpointsMapToExpectedMiredOffsets() {
        // 槽位 = 目标白点 mired − 6500K 的 mired = 1e6/K − 1e6/6500
        //（参考 mired = 1e6/6500 = 153.84615384615384）
        assertEquals(246.15384615384616, tempSlot(2500f), 1e-9)     // 400 − 153.846…
        assertEquals(-70.51282051282051, tempSlot(12000f), 1e-9)    // 83.333… − 153.846…
        assertEquals(1e6 / 2500.0 - 1e6 / 6500.0, tempSlot(2500f), 1e-12)
        assertEquals(1e6 / 12000.0 - 1e6 / 6500.0, tempSlot(12000f), 1e-12)
        // 两个端点都能原样读回来（夹紧容差内）
        assertEquals(2500f, tempUi(tempSlot(2500f)), 0.01f)
        assertEquals(12000f, tempUi(tempSlot(12000f)), 0.01f)
    }

    @Test
    fun kelvinSlotSignIsOppositeToKelvin() {
        // ★ 方向约定：比 6500K 冷 ⇒ 槽位为正（= 目标白点 mired 更大 = 画面变冷）
        assertTrue("4000K 比 6500K 冷 ⇒ 槽位必须为正", tempSlot(4000f) > 0.0)
        assertTrue("9000K 比 6500K 暖 ⇒ 槽位必须为负", tempSlot(9000f) < 0.0)
        // 滑杆值（K）与读数（K）同向：右移 K 大 ⇒ 读数也大。
        // 若滑杆直接用 mired，这条会反过来（这正是本卡最容易做错的地方）。
        assertTrue(
            "K 滑杆右移必须变暖（读数变大）",
            tempUi(tempSlot(9000f)) > tempUi(tempSlot(4000f)),
        )
    }

    @Test
    fun kelvinRoundTripIsExactAcrossTheWholeRange() {
        // 每一个整数 K 都要能原样回来（读数不能跳）
        for (k in 2500..12000) {
            assertEquals("${k}K 往返漂了", k.toFloat(), tempUi(tempSlot(k.toFloat())), 1e-3f)
        }
        // 反向往返：槽位 → K → 槽位（坏数据判定不能把正常槽位吃掉）
        var slot = tempSlot(2500f)
        val end = tempSlot(12000f)
        while (slot <= end) {
            assertEquals("槽位 $slot 反向往返漂了", slot, tempSlot(tempUi(slot)), 1e-6)
            slot += 7.3
        }
    }

    @Test
    fun kelvinWriteClampsToUiRange() {
        // 手势产生不了越界值，但坏快照/未来改量程时不该把非法 K 带进引擎
        assertEquals(tempSlot(2500f), tempSlot(1000f), 0.0)
        assertEquals(tempSlot(2500f), tempSlot(-9999f), 0.0)
        assertEquals(tempSlot(12000f), tempSlot(99999f), 0.0)
        // 坏数据 = 恒等（6500K ⇒ 0）
        assertEquals(0.0, tempSlot(Float.NaN), 0.0)
        assertEquals(0.0, tempSlot(Float.POSITIVE_INFINITY), 0.0)
        assertEquals(0.0, tempSlot(Float.NEGATIVE_INFINITY), 0.0)
    }

    @Test
    fun kelvinBadSlotFallsBackToIdentity() {
        // 非有限 ⇒ 6500K（读数绝不能出现 NaN，滑杆也不能被 NaN 顶飞）
        assertEquals(6500f, tempUi(Double.NaN), 0f)
        assertEquals(6500f, tempUi(Double.POSITIVE_INFINITY), 0f)
        assertEquals(6500f, tempUi(Double.NEGATIVE_INFINITY), 0f)
        // 换出的 K 越出 UI 量程的坏槽位（UI 永远写不出）⇒ 恒等读数，而不是夹到端点
        assertEquals(6500f, tempUi(1000.0), 0f)     // ⇒ 866K，比下限还冷
        assertEquals(6500f, tempUi(-500.0), 0f)     // mired_ref + slot < 0 ⇒ 数学失效（K 为负）
        assertEquals(6500f, tempUi(-1e6 / 6500.0), 0f)   // mired 恰好 0 ⇒ 1e6/0 = Inf
        // 但浮点噪声级别的越界仍然夹到端点（端点往返一致性的前提）
        assertEquals(2500f, tempUi(tempSlot(2500f) - 1e-9), 0.01f)
        assertEquals(12000f, tempUi(tempSlot(12000f) + 1e-9), 0.01f)
    }

    @Test
    fun kelvinReadoutIsIntegerKelvin() {
        assertEquals("5000K", formatKelvin(5000f))
        assertEquals("6500K", formatKelvin(6500f))
        assertEquals("2500K", formatKelvin(2500f))
        assertEquals("12000K", formatKelvin(12000f))
        assertEquals("5050K", formatKelvin(5049.7f))
        // 越界夹到量程；坏数据回落恒等读数（读数绝不能出现 NaN）
        assertEquals("12000K", formatKelvin(99999f))
        assertEquals("2500K", formatKelvin(1f))
        assertEquals("6500K", formatKelvin(Float.NaN))
        assertEquals("6500K", formatKelvin(Float.POSITIVE_INFINITY))
    }

    // ── 色调 [89]：±0.5 直通，读数 ±100 ────────────────────────────────────

    @Test
    fun tintMapsToHalfRangeAndReadsAsOffset100() {
        assertEquals(0.0, tintSlot(0f), 0.0)
        assertEquals(0.5, tintSlot(0.5f), 1e-9)
        assertEquals(-0.5, tintSlot(-0.5f), 1e-9)
        // 越界夹住（引擎侧 clamp ±1，UI 只给 ADR 建议的 ±0.5）
        assertEquals(0.5, tintSlot(9f), 1e-9)
        assertEquals(-0.5, tintSlot(-9f), 1e-9)
        assertEquals(0.0, tintSlot(Float.NaN), 0.0)
        // 槽位 → UI
        assertEquals(0f, tintUi(0.0), 0f)
        assertEquals(0.12f, tintUi(0.12), 1e-6f)
        assertEquals(0.5f, tintUi(9.0), 1e-6f)
        assertEquals(-0.5f, tintUi(-9.0), 1e-6f)
        assertEquals(0f, tintUi(Double.NaN), 0f)
        // 读数：`+12` / `0` / `-12`（±100 相对量，中点 0）
        assertEquals("+12", formatOffset100(0.12f, ZERO_UI_PIVOT))
        assertEquals("-12", formatOffset100(-0.12f, ZERO_UI_PIVOT))
        assertEquals("0", formatOffset100(0f, ZERO_UI_PIVOT))
        assertEquals("+50", formatOffset100(0.5f, ZERO_UI_PIVOT))
        // 往返不漂
        for (i in -50..50) {
            val ui = i / 100f
            assertEquals("色调读数 $i 往返漂了", i, (tintUi(tintSlot(ui)) * 100f).roundToInt())
        }
    }

    // ── 影调分区 [90..93]：−1..+1 直通，读数 ±100（四条共用一对映射）──────────

    @Test
    fun toneQuartetMapsFullRangeAndReadsAsOffset100() {
        assertEquals(0.0, toneSlot(0f), 0.0)
        assertEquals(1.0, toneSlot(1f), 1e-9)
        assertEquals(-1.0, toneSlot(-1f), 1e-9)
        assertEquals(1.0, toneSlot(9f), 1e-9)
        assertEquals(-1.0, toneSlot(-9f), 1e-9)
        assertEquals(0.0, toneSlot(Float.NaN), 0.0)
        assertEquals(0f, toneUi(0.0), 0f)
        assertEquals(0.5f, toneUi(0.5), 1e-6f)
        assertEquals(-1f, toneUi(-9.0), 1e-6f)
        assertEquals(1f, toneUi(9.0), 1e-6f)
        assertEquals(0f, toneUi(Double.NaN), 0f)
        // 读数：`+50` / `0` / `-50`（高光 / 阴影 / 白色色阶 / 黑色色阶 四条同口径）
        assertEquals("+50", formatOffset100(0.5f, ZERO_UI_PIVOT))
        assertEquals("-50", formatOffset100(-0.5f, ZERO_UI_PIVOT))
        assertEquals("+100", formatOffset100(1f, ZERO_UI_PIVOT))
        assertEquals("-100", formatOffset100(-1f, ZERO_UI_PIVOT))
        assertEquals("0", formatOffset100(0f, ZERO_UI_PIVOT))
        // 往返不漂
        for (i in -100..100) {
            val ui = i / 100f
            assertEquals("影调分区读数 $i 往返漂了", i, (toneUi(toneSlot(ui)) * 100f).roundToInt())
        }
    }

    // ── 褪色 [94]：0..1 直通，读数 0..100 ──────────────────────────────────

    @Test
    fun fadeMapsZeroToOneAndReadsAsUnsigned100() {
        assertEquals(0.0, fadeSlot(0f), 0.0)
        assertEquals(1.0, fadeSlot(1f), 1e-9)
        assertEquals(1.0, fadeSlot(9f), 1e-9)
        assertEquals(0.0, fadeSlot(-9f), 1e-9)
        assertEquals(0.0, fadeSlot(Float.NaN), 0.0)
        assertEquals(0f, fadeUi(0.0), 0f)
        assertEquals(0.35f, fadeUi(0.35), 1e-6f)
        assertEquals(1f, fadeUi(9.0), 1e-6f)
        // 负槽位 = 关（引擎 `uFade > 0` 才生效，负值不产生任何画面变化）
        assertEquals(0f, fadeUi(-1.0), 0f)
        assertEquals(0f, fadeUi(Double.NaN), 0f)
        // 读数：`35` / `0` / `100`（无符号 0..100 整数）
        assertEquals("35", formatZero100(0.35f))
        assertEquals("0", formatZero100(0f))
        assertEquals("100", formatZero100(1f))
        // 往返不漂
        for (i in 0..100) {
            val ui = i / 100f
            assertEquals("褪色读数 $i 往返漂了", i, (fadeUi(fadeSlot(ui)) * 100f).roundToInt())
        }
    }

    // ── 中点标记（ADR-14 的"三条关键修正"第 2 条）────────────────────────────

    @Test
    fun midpointMarkersMatchTheSpecifiedPivots() {
        // 色温：中点 6500K 在 2500..12000 内 ⇒ 必须画（位置 = (6500−2500)/9500 = 0.421053）
        val tempMid = pivotFraction(TEMP_UI_RANGE, TEMP_UI_PIVOT)
        assertTrue("色温中点必须画出来", tempMid != null)
        assertEquals(4000f / 9500f, tempMid!!, 1e-6f)
        // 色调 / 高光 / 阴影 / 白 / 黑：恒等值 0 在量程正中
        assertEquals(0.5f, pivotFraction(TINT_UI_RANGE, ZERO_UI_PIVOT)!!, 1e-6f)
        assertEquals(0.5f, pivotFraction(TONE_UI_RANGE, ZERO_UI_PIVOT)!!, 1e-6f)
        // 褪色：基准 0 落在量程端点 ⇒ 不画（端点本身就是标记）
        assertNull(pivotFraction(FADE_UI_RANGE, ZERO_UI_PIVOT))
    }

    // ── 七条滑杆只写自己的槽位 + 面板读数 ────────────────────────────────────

    @Test
    fun sevenSlidersWriteOnlyTheirOwnSlots() {
        val p = CameraPresets.standard()
        val before = p.copyOf()
        // 七条滑杆各拖一次（值取任务卡表格里的典型落点）
        p[88] = tempSlot(5000f)
        p[89] = tintSlot(0.12f)
        p[90] = toneSlot(0.5f)
        p[91] = toneSlot(-0.5f)
        p[92] = toneSlot(1f)
        p[93] = toneSlot(-1f)
        p[94] = fadeSlot(0.35f)
        // 只有这七个槽位动了（尤其 [0..4] / [34] / [78] 一个都不许碰）
        for (i in p.indices) {
            if (i in 88..94) continue
            assertEquals("槽位 $i 被误改", before[i], p[i], 0.0)
        }
        assertEquals(1e6 / 5000.0 - 1e6 / 6500.0, p[88], 1e-9)
        assertEquals(0.12, p[89], 1e-6)
        assertEquals(0.5, p[90], 1e-6)
        assertEquals(-0.5, p[91], 1e-6)
        assertEquals(1.0, p[92], 1e-6)
        assertEquals(-1.0, p[93], 1e-6)
        assertEquals(0.35, p[94], 1e-6)
        // 面板读数（滑杆 → 槽位 → 读数的完整回路）
        assertEquals("5000K", formatKelvin(tempUi(p[88])))
        assertEquals("+12", formatOffset100(tintUi(p[89]), ZERO_UI_PIVOT))
        assertEquals("+50", formatOffset100(toneUi(p[90]), ZERO_UI_PIVOT))
        assertEquals("-50", formatOffset100(toneUi(p[91]), ZERO_UI_PIVOT))
        assertEquals("+100", formatOffset100(toneUi(p[92]), ZERO_UI_PIVOT))
        assertEquals("-100", formatOffset100(toneUi(p[93]), ZERO_UI_PIVOT))
        assertEquals("35", formatZero100(fadeUi(p[94])))
    }

    /**
     * E2-9 的直通判据（`paramsUnchanged`）必须能把"七条滑杆都拖回恒等值"认成**无修改** ——
     * 用户改回原样后导出仍应直通原片字节（零二代损失 / 零体积膨胀 / 零耗时）。
     *
     * 这条同时是"色温参考 mired 必须用 `1e6/6500` 表达式而不是 `153.846` 字面量"的**理由测试**：
     * 若参考值写成字面量，`tempSlot(6500f)` = 1.538e-4 > `PARAM_EPS = 1e-4` ⇒ 用户把色温拖回
     * 6500K 会被误判成"有修改"，白渲染一次全分辨率。
     */
    @Test
    fun sevenSlidersBackToIdentityCountAsUnchanged() {
        val baseline = CameraPresets.standard()
        val p = baseline.copyOf()
        // 先七条全拖走
        p[88] = tempSlot(4000f); p[89] = tintSlot(0.3f)
        p[90] = toneSlot(0.6f); p[91] = toneSlot(-0.6f)
        p[92] = toneSlot(0.4f); p[93] = toneSlot(-0.4f); p[94] = fadeSlot(0.5f)
        assertTrue("拖走后必须判为有修改", !paramsUnchanged(p, baseline))
        // 再全部拖回恒等值（色温 6500K / 其余 0）
        p[88] = tempSlot(6500f); p[89] = tintSlot(0f)
        p[90] = toneSlot(0f); p[91] = toneSlot(0f)
        p[92] = toneSlot(0f); p[93] = toneSlot(0f); p[94] = fadeSlot(0f)
        assertTrue("七条滑杆全回恒等值 ⇒ 应判为无修改（直通原片）", paramsUnchanged(p, baseline))
    }

    @Test
    fun resetCoversTheNewSlotsAndLeavesOtherGroupsAlone() {
        val p = CameraPresets.standard()
        p[0] = 1.5; p[4] = 0.6; p[78] = 0.7
        p[88] = tempSlot(4000f); p[89] = 0.3
        p[90] = 0.8; p[91] = -0.8; p[92] = 0.5; p[93] = -0.5; p[94] = 0.6
        p[66] = -0.5   // 暗角（不属于这两组）
        p[26] = 0.3    // 颗粒（同上）
        p[34] = 0.9    // LUT 强度（同上）

        // 「调整」组重置：E2-14 起含高光 / 阴影 / 白色色阶 / 黑色色阶
        resetBasicParams(p)
        assertEquals(0.0, p[90], 1e-9)
        assertEquals(0.0, p[91], 1e-9)
        assertEquals(0.0, p[92], 1e-9)
        assertEquals(0.0, p[93], 1e-9)
        // 色彩组三条与别的组一个都不动
        assertEquals(tempSlot(4000f), p[88], 1e-9)
        assertEquals(0.3, p[89], 1e-9)
        assertEquals(0.6, p[94], 1e-9)
        assertEquals(-0.5, p[66], 1e-9)
        assertEquals(0.3, p[26], 1e-9)
        assertEquals(0.9, p[34], 1e-9)

        // 「色彩」组重置（引擎参数部分）：色温 / 色调 / 褪色回恒等
        p[90] = 0.8   // 再设一次：确认 resetColorParams 不碰影调分区
        resetColorParams(p)
        assertEquals(0.0, p[88], 1e-9)
        assertEquals(0.0, p[89], 1e-9)
        assertEquals(0.0, p[94], 1e-9)
        assertEquals(0.8, p[90], 1e-9)
        assertEquals(-0.5, p[66], 1e-9)
        assertEquals(0.9, p[34], 1e-9)
    }
}
