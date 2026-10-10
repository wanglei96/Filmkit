package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E3-1c 预设强度（Amount）的纯逻辑护栏：**逐类缩放语义**（调研 §5.6 的表）。
 *
 * 为什么每一条都要有断言：强度错了不会崩，只会**静默给出错误的画面**（最隐蔽的一类 bug）。
 * 本卡最容易错的四条，逐条点名：
 *  1. ★ **恒等值不能当 0**：`wb_* [1..3]` / `contrast [4]` / `sat_* [20..22]` /
 *     `lut_strength [34]` / `interlayer_* [69..77]` 的恒等值是 **1.0** —— 相对 0 缩放的话，
 *     对比度预设 1.5、强度 50% 会得到 **0.75（画面变灰）**，正确值是 **1.25**。
 *  2. ★ **`amount = 1.0` 必须逐位零回归**（与不加强度完全一致）：这是"本卡不改变任何既有
 *     行为"的唯一证据，且实现上是**结构性**的（入口原样返回，一个缩放分支都不经过）。
 *  3. ★ **`amount = 0` = 完全不套用**：每个可缩放槽都回到恒等值 [CameraPresets.standard]。
 *  4. ★ **> 1 是外推**：有契约边界的槽（暗角 −1..0 / LUT 强度 0..1.5 / 影调分区 ±1 …）
 *     必须被夹住 —— 否则会出现"UI 拖不到、却写进参数数组"的值（假读数的镜像）。
 *
 * 基线一律用真实的 [CameraPresets.standard]（恒等值表本身也是被测对象之一），不手搓数组。
 */
class EditorPresetAmountTest {

    private val neutral = CameraPresets.standard()

    private fun scale(slots: Map<Int, Double>, amount: Double): Map<Int, Double> =
        scalePresetSlots(slots, amount, neutral)

    /** 一份覆盖全部类别的槽位（矩阵 / 曲线 / LUT / 局部 / 强度型各来几个） */
    private fun allKinds(): Map<Int, Double> = mapOf(
        0 to 0.5,        // 曝光（恒等 0）
        1 to 1.2,        // wb_r（恒等 1.0）
        4 to 1.5,        // 对比度（恒等 1.0）
        5 to 0.05,       // latitude（曲线类，恒等 0.01）
        11 to 1.10, 12 to 0.05, 13 to -0.02,   // cross（恒等 = 单位阵）
        20 to 1.4,       // 分区饱和（恒等 1.0）
        34 to 0.8,       // LUT 强度（"0 = 关"型）
        37 to -6.0,      // filmic 黑点（曲线类，恒等 −7.65）
        66 to -0.5,      // 暗角强度（恒等 0）
        69 to 1.2,       // 层间增益（恒等 1.0）
        78 to 0.4,       // 清晰度（恒等 0）
        88 to 30.0,      // 色温 mired（恒等 0）
        90 to -0.2,      // 高光（恒等 0）
    )

    // ── ① 零回归：amount = 1.0 ───────────────────────────────────────────────

    /** ★ `amount = 1.0` ⇒ **同一个 map 原样返回**（逐位零回归，且是结构性保证） */
    @Test
    fun fullAmountIsBitIdentical() {
        val slots = allKinds() + mapOf(79 to 2.0, 86 to 0.5, 87 to 0.4)
        val out = scale(slots, 1.0)
        assertSame("amount = 1.0 必须原样返回（不经过任何缩放分支）", slots, out)
        assertEquals(slots, out)
    }

    // ── ② amount = 0 ⇒ 完全不套用 ───────────────────────────────────────────

    /**
     * ★ `amount = 0` ⇒ 每个槽都等于**恒等值**（不是 0，也不是当前值）。
     *
     * 唯一的例外是 [34] LUT 强度 —— 它是"**0 = 关**"型：效果量就是槽值本身，归零才是"无 LUT"
     *（而 [CameraPresets.standard] 里的 1.0 是拍摄链路的"全量混合"默认）。两条断言分开写，
     * 让这个特例在测试里是**显式**的，而不是被一个"全都等于 neutral"的循环悄悄盖住。
     */
    @Test
    fun zeroAmountEqualsNeutral() {
        val slots = allKinds()
        val out = scale(slots, 0.0)
        assertEquals("键集不变（曲线类也要给回恒等值）", slots.keys, out.keys)
        for ((i, v) in out) {
            if (i == 34) continue
            assertEquals("槽 $i 在 amount = 0 时必须等于恒等值", neutral[i], v, 1e-12)
        }
        // "0 = 关"型：效果量归零（= 无 LUT），而不是回到 standard() 的 1.0
        assertEquals(0.0, out[34]!!, 1e-12)
        // 点名几个"恒等值不是 0"的槽：回恒等 ≠ 回 0
        assertEquals(1.0, out[4]!!, 1e-12)
        assertEquals(1.0, out[20]!!, 1e-12)
        assertEquals(0.01, out[5]!!, 1e-12)
        assertEquals(-7.65, out[37]!!, 1e-12)
        assertEquals(1.0, out[69]!!, 1e-12)
    }

    // ── ③ ★ 恒等值 1.0 的槽：相对恒等值缩放（防"相对 0 缩放"）────────────────

    /**
     * ★★ 本卡最关键的一条：对比度预设 1.5、强度 50% ⇒ **1.25**。
     *
     * 相对 0 缩放的错误实现会得到 0.75（画面变灰）—— 断言里显式排除它，让"改错了"这件事
     * 在测试名与断言上都一眼可见。
     */
    @Test
    fun contrastPresetScalesAroundOneNotZero() {
        val out = scale(mapOf(4 to 1.5), 0.5)
        assertEquals("1 + (1.5 − 1) × 0.5", 1.25, out[4]!!, 1e-12)
        assertNotEquals("相对 0 缩放会得到 0.75 = 画面变灰", 0.75, out[4]!!, 1e-9)
    }

    /** 白平衡增益（恒等 1.0）：1.2 @ 50% ⇒ 1.1（相对 0 缩放会得到 0.6 = 画面偏色） */
    @Test
    fun wbGainScalesAroundOne() {
        val out = scale(mapOf(1 to 1.2, 2 to 0.9, 3 to 1.1), 0.5)
        assertEquals(1.1, out[1]!!, 1e-12)
        assertEquals(0.95, out[2]!!, 1e-12)
        assertEquals(1.05, out[3]!!, 1e-12)
    }

    /** 分区饱和度（恒等 1.0）：1.4 @ 50% ⇒ 1.2 */
    @Test
    fun saturationScalesAroundOne() {
        val out = scale(mapOf(20 to 1.4, 21 to 1.4, 22 to 0.8), 0.5)
        assertEquals(1.2, out[20]!!, 1e-12)
        assertEquals(1.2, out[21]!!, 1e-12)
        assertEquals(0.9, out[22]!!, 1e-12)
    }

    /** 层间效应增益（恒等 1.0）：1.2 @ 50% ⇒ 1.1 */
    @Test
    fun interlayerGainScalesAroundOne() {
        val out = scale(mapOf(69 to 1.2, 75 to 0.8), 0.5)
        assertEquals(1.1, out[69]!!, 1e-12)
        assertEquals(0.9, out[75]!!, 1e-12)
    }

    // ── ④ 恒等值 0 的线性增益 / 强度型 ───────────────────────────────────────

    /** 曝光（恒等 0 EV）：0.6 @ 50% ⇒ 0.3；中点 = 恒等值与预设值的中点 */
    @Test
    fun halfAmountIsMidpointForLinearGains() {
        val out = scale(mapOf(0 to 0.6, 20 to 1.4, 90 to -0.4, 94 to 0.5, 88 to 30.0), 0.5)
        assertEquals(0.3, out[0]!!, 1e-12)
        assertEquals(1.2, out[20]!!, 1e-12)
        assertEquals(-0.2, out[90]!!, 1e-12)
        assertEquals(0.25, out[94]!!, 1e-12)
        assertEquals(15.0, out[88]!!, 1e-12)
    }

    /** 强度型（0 = 关）：暗角强度 −0.5 @ 50% ⇒ −0.25（幅度减半，符号不变） */
    @Test
    fun intensitySlotsScaleFromZero() {
        val out = scale(mapOf(66 to -0.5, 78 to 0.4, 26 to 60.0, 62 to 0.8), 0.5)
        assertEquals(-0.25, out[66]!!, 1e-12)
        assertEquals(0.2, out[78]!!, 1e-12)
        assertEquals(30.0, out[26]!!, 1e-12)
        assertEquals(0.4, out[62]!!, 1e-12)
    }

    /** ★ LUT 强度 [34] 是"**0 = 关**"型：`v × amount`，**不是**相对 1.0 缩放 */
    @Test
    fun lutStrengthScalesFromZeroNotFromOne() {
        assertEquals("1.0 @ 50% ⇒ 0.5（相对 1.0 缩放会得到 1.0 = 强度滑杆对 LUT 完全无效）",
            0.5, scale(mapOf(34 to 1.0), 0.5)[34]!!, 1e-12)
        assertEquals(0.75, scale(mapOf(34 to 1.5), 0.5)[34]!!, 1e-12)
        assertEquals(0.0, scale(mapOf(34 to 1.0), 0.0)[34]!!, 1e-12)
        assertEquals(0.6, scale(mapOf(34 to 0.8), 0.75)[34]!!, 1e-12)
    }

    // ── ⑤ 矩阵类：M_new = I + (M − I) × amount ──────────────────────────────

    /** 串扰矩阵（恒等 = 单位阵）：逐元素 `I + (M − I) × amount` */
    @Test
    fun crossMatrixScalesTowardIdentity() {
        val m = doubleArrayOf(1.10, 0.05, -0.02, 0.03, 0.95, 0.04, -0.01, 0.02, 1.05)
        val ident = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val slots = (0..8).associate { (11 + it) to m[it] }
        val out = scale(slots, 0.25)
        for (k in 0..8) {
            assertEquals(
                "矩阵元素 [${11 + k}]",
                ident[k] + (m[k] - ident[k]) * 0.25,
                out[11 + k]!!, 1e-12,
            )
        }
        // amount = 0 ⇒ 逐元素 = 单位阵（= 恒等值本身）
        val zero = scale(slots, 0.0)
        for (k in 0..8) assertEquals(ident[k], zero[11 + k]!!, 1e-12)
    }

    // ── ⑥ 非线性曲线类：v1 不缩放 ───────────────────────────────────────────

    /** [5..10] latitude / [37][38] filmic 黑白点：amount > 0 时**原值套用**（形状不随强度变） */
    @Test
    fun curveSlotsAreNotScaledAboveZeroAmount() {
        val out = scale(mapOf(5 to 0.05, 8 to 0.2, 10 to 0.5, 37 to -6.0, 38 to 3.0), 0.3)
        assertEquals(0.05, out[5]!!, 1e-12)
        assertEquals(0.2, out[8]!!, 1e-12)
        assertEquals(0.5, out[10]!!, 1e-12)
        assertEquals(-6.0, out[37]!!, 1e-12)
        assertEquals(3.0, out[38]!!, 1e-12)
        // 若被线性插值，[5] 会是 0.01 + (0.05 − 0.01) × 0.3 = 0.022（latitude 语义下是另一条曲线）
        assertNotEquals(0.022, out[5]!!, 1e-9)
    }

    /** 曲线类在 amount = 0 时**回恒等值**（与其它槽一致地"完全不套用"） */
    @Test
    fun curveSlotsReturnToNeutralAtZero() {
        val out = scale(mapOf(5 to 0.05, 37 to -6.0, 38 to 3.0), 0.0)
        assertEquals(0.01, out[5]!!, 1e-12)
        assertEquals(-7.65, out[37]!!, 1e-12)
        assertEquals(4.56, out[38]!!, 1e-12)
    }

    // ── ⑦ 离散枚举 + 局部通道：低于阈值整条不套用 ───────────────────────────

    /** ★ [79] 是离散枚举（0 关 / 1 径向 / 2 线性），不能插值：amount < 0.5 ⇒ 整条局部通道不套用 */
    @Test
    fun localChannelIsDroppedBelowThreshold() {
        val slots = mapOf(79 to 2.0, 80 to 0.2, 82 to 0.6, 86 to 0.5, 87 to 0.4)
        assertTrue("amount = 0.3 < 0.5 ⇒ 一个局部槽都不该被套用", scale(slots, 0.3).isEmpty())
        assertTrue(scale(slots, 0.0).isEmpty())
        assertTrue(scale(slots, 0.49).isEmpty())
    }

    /** 达到阈值 ⇒ [79..85]（离散枚举 + 构图几何）**原值**套用：强度不许移动用户圈好的区域 */
    @Test
    fun localChannelIsAppliedAtThreshold() {
        val out = scale(mapOf(79 to 2.0, 80 to 0.2, 82 to 0.6, 83 to 0.9), 0.5)
        assertEquals(2.0, out[79]!!, 1e-12)
        assertEquals(0.2, out[80]!!, 1e-12)
        assertEquals(0.6, out[82]!!, 1e-12)
        assertEquals(0.9, out[83]!!, 1e-12)
    }

    /** 局部的**强度**（[86] EV / [87] 饱和度）仍是线性量 ⇒ 与其它槽同一公式缩放 */
    @Test
    fun localStrengthScalesAtThreshold() {
        val out = scale(mapOf(86 to 0.5, 87 to 0.4), 0.5)
        assertEquals(0.25, out[86]!!, 1e-12)
        assertEquals("1 + (0.4 − 1) × 0.5", 0.7, out[87]!!, 1e-12)
    }

    // ── ⑧ 外推（amount > 1）与 clamp ────────────────────────────────────────

    /** ★ 有契约边界的槽在外推时**逐槽夹紧**（否则会出现"滑杆拖不到、却写进参数数组"的值） */
    @Test
    fun extrapolationClampsToContractRanges() {
        val out = scale(
            mapOf(
                0 to 3.0,    // 曝光 ±3 EV（UI ±100%）
                4 to 2.0,    // 对比度上界 2.0
                34 to 1.5,   // LUT 强度上界 1.5
                66 to -1.0,  // 暗角强度 −1..0
                78 to 1.0,   // 清晰度 0..1
                89 to 0.5,   // 色调 ±0.5
                90 to 1.0,   // 影调分区 ±1
                94 to 1.0,   // 褪色 0..1
            ),
            1.5,
        )
        assertEquals(3.0, out[0]!!, 1e-12)
        assertEquals(2.0, out[4]!!, 1e-12)
        assertEquals(1.5, out[34]!!, 1e-12)
        assertEquals(-1.0, out[66]!!, 1e-12)
        assertEquals(1.0, out[78]!!, 1e-12)
        assertEquals(0.5, out[89]!!, 1e-12)
        assertEquals(1.0, out[90]!!, 1e-12)
        assertEquals(1.0, out[94]!!, 1e-12)
    }

    /** 表里没有的槽（引擎无硬边界）⇒ 外推照常生效，不夹 */
    @Test
    fun extrapolationDoesNotClampUnboundedSlots() {
        // [36] 局部色调映射：恒等 0.25、无 UI 上界 ⇒ 0.25 + (0.4 − 0.25) × 1.5 = 0.475
        val out = scale(mapOf(36 to 0.4, 88 to 100.0), 1.5)
        assertEquals("局部色调映射无 UI 上界 ⇒ 外推生效", 0.475, out[36]!!, 1e-12)
        assertEquals("mired 在 UI 量程内 ⇒ 不夹", 150.0, out[88]!!, 1e-12)
    }

    /** `0 ≤ amount ≤ 1` 的结果必须落在"恒等值与预设值之间"（凸组合，不会越界） */
    @Test
    fun interpolationStaysBetweenNeutralAndPreset() {
        val slots = mapOf(0 to 0.6, 4 to 1.5, 20 to 1.4, 66 to -0.5, 90 to -0.4, 94 to 0.5)
        for (amount in listOf(0.0, 0.1, 0.25, 0.5, 0.75, 1.0)) {
            val out = scale(slots, amount)
            for ((i, v) in out) {
                val lo = minOf(neutral[i], slots[i]!!)
                val hi = maxOf(neutral[i], slots[i]!!)
                assertTrue(
                    "amount = $amount 时槽 $i = $v 越出 [$lo, $hi]",
                    v >= lo - 1e-12 && v <= hi + 1e-12,
                )
            }
        }
    }

    // ── ⑨ 边界 / 坏数据 ────────────────────────────────────────────────────

    /** 非有限的 amount（坏数据）⇒ 按满强度处理：绝不能让 NaN 乘进参数数组（画面崩坏） */
    @Test
    fun nonFiniteAmountFallsBackToFullStrength() {
        val slots = mapOf(4 to 1.5)
        assertSame(slots, scalePresetSlots(slots, Double.NaN, neutral))
        assertSame(slots, scalePresetSlots(slots, Double.POSITIVE_INFINITY, neutral))
        assertSame(slots, scalePresetSlots(slots, Double.NEGATIVE_INFINITY, neutral))
    }

    /** 负强度按 0 处理（不做"反向预设"） */
    @Test
    fun negativeAmountBehavesAsZero() {
        val slots = mapOf(4 to 1.5, 66 to -0.4)
        assertEquals(scale(slots, 0.0), scale(slots, -0.5))
    }

    /** 槽值非有限 ⇒ 丢弃该槽（与 presetSnapshot 的坏数据口径一致） */
    @Test
    fun nonFiniteSlotValuesAreDropped() {
        val out = scale(mapOf(4 to 1.5, 90 to Double.NaN, 0 to Double.POSITIVE_INFINITY), 0.5)
        assertEquals(setOf(4), out.keys)
    }

    /** 下标越界 ⇒ 丢弃该槽（未知槽位忽略，调研 §5.7 第 3 条） */
    @Test
    fun outOfRangeSlotIndicesAreDropped() {
        val out = scale(mapOf(4 to 1.5, CameraPresets.COUNT to 0.5, -1 to 0.5), 0.5)
        assertEquals(setOf(4), out.keys)
    }

    /** 纯函数：不改入参 map，返回新对象 */
    @Test
    fun inputMapIsNotMutated() {
        val slots = mapOf(4 to 1.5, 66 to -0.4)
        val out = scale(slots, 0.5)
        assertNotSame(slots, out)
        assertEquals(1.5, slots[4]!!, 0.0)
        assertEquals(-0.4, slots[66]!!, 0.0)
    }

    // ── ⑩ 与套用链路组合（部分套用 / 幂等）──────────────────────────────────

    /** 缩放后的套用仍然只动预设记下的槽：其余槽保持 base（RawTherapee"缺失 = 保留"语义） */
    @Test
    fun scaledApplyTouchesOnlyPresetSlots() {
        val base = CameraPresets.standard().also { it[0] = 0.2 }   // 用户自己调过曝光
        val slots = mapOf(4 to 1.5, 66 to -0.5)
        val applied = presetSnapshot(base, scale(slots, 0.5))
        assertEquals(1.25, applied[4], 1e-12)
        assertEquals(-0.25, applied[66], 1e-12)
        assertEquals("预设没管的槽保持用户当前值", 0.2, applied[0], 0.0)
        assertEquals(1.0, applied[20], 0.0)
        assertEquals("base 本身不被修改", 1.0, base[4], 0.0)
    }

    /**
     * 强度每次从 base 重算 ⇒ **幂等**：0.8 → 0.6 → 0.4 与直接 0.4 得到完全相同的参数
     *（UI 侧 [EditorScreen] 的 dragPresetAmount 就是这么做的，不累积浮点误差）。
     */
    @Test
    fun recomputeFromBaseIsIdempotent() {
        val base = CameraPresets.standard()
        val slots = mapOf(4 to 1.5, 0 to 0.6, 34 to 0.9)
        // UI 侧拖动 = 每次都从 base 重算（不是在上一次结果上继续乘）
        val walked = listOf(0.8, 0.6, 0.4).fold(base) { _, a -> presetSnapshot(base, scale(slots, a)) }
        val direct = presetSnapshot(base, scale(slots, 0.4))
        assertArrayEquals(direct, walked, 0.0)
        // 不同强度确实给出不同结果（否则上面的相等没有意义）
        val other = presetSnapshot(base, scale(slots, 0.6))
        assertNotEquals(direct[4], other[4], 1e-9)
    }

    // ── ⑪ 强度不是预设的一部分 ──────────────────────────────────────────────

    /**
     * 强度**不写进预设 JSON**：预设存的是满强度的槽值，缩放不改变预设对象本身。
     *
     * ⚠ 断言必须用 `"amount":`（带引号 + 冒号 = 一个**独立字段名**）：P-1 改语义名后
     * `grain_amount` 这个**参数名**里含 "amount" 子串，裸 `contains("amount")` 会把合法参数名
     * 误判成"写了强度字段"。
     */
    @Test
    fun presetJsonCarriesNoAmount() {
        val p = EditorPreset(id = "p_1_1", name = "午后暖调", slots = mapOf(4 to 1.5, 66 to -0.5))
        val json = presetToJson(p)
        assertFalse("预设 JSON 里不该有 amount 字段：$json", json.contains("\"amount\":"))
        scalePresetSlots(p.slots, 0.5)
        assertEquals("缩放不改变预设对象", mapOf(4 to 1.5, 66 to -0.5), p.slots)
    }

    // ── ⑫ UI 口径（纯函数部分）──────────────────────────────────────────────

    /** 强度滑杆：量程 0..1.5、读数 0..150、中点标记画在 **100%**（= 预设原样） */
    @Test
    fun amountSliderMarksFullStrength() {
        val f = pivotFraction(PRESET_AMOUNT_UI_RANGE, 1f)
        assertNotNull("100% 必须能画出中点标记", f)
        assertEquals(2f / 3f, f!!, 1e-6f)
        assertEquals("0", formatZero150(PRESET_AMOUNT_UI_RANGE.start))
        assertEquals("100", formatZero150(1f))
        assertEquals("150", formatZero150(PRESET_AMOUNT_UI_RANGE.endInclusive))
        assertEquals(0.5, PRESET_LOCAL_MIN_AMOUNT, 0.0)
    }
}
