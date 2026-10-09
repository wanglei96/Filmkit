package com.filmkit.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2-10 编辑器面板「读数 + 中点标记」的纯逻辑护栏。
 *
 * 为什么这些必须测：ADR-14 把"读数恒为 `%`（曝光显示 `100%` 而不是 `+1.0 EV`）"列为
 * 三条最关键差距之一，而读数是**用户每拖一帧都在看**的东西 —— 格式错了（少一个符号、
 * 多一位小数、中点读数不是 0）用户立刻能看出来，但代码评审看不出来。
 *
 * 覆盖四类（任务卡要求）：**每个参数的读数格式 + 中点值 + 量程边界 + 坏数据**。
 * 滑杆拖动、面板布局要真机（Compose + GL），JVM 单测跑不了，这里只守纯函数。
 */
class EditorReadoutTest {

    // ── 曝光：物理量 EV（不再是百分比）───────────────────────────────────────

    @Test
    fun exposureReadsAsEvWithOneDecimal() {
        // 恒等值不带符号（0.0 EV 而不是 +0.0 EV）
        assertEquals("0.0 EV", formatEv(0f))
        // UI ±1 = ±3 EV（EXP_UI_RANGE 的口径）
        assertEquals("+1.0 EV", formatEv(1f / 3f))
        assertEquals("-1.5 EV", formatEv(-0.5f))
        assertEquals("+3.0 EV", formatEv(1f))
        assertEquals("-3.0 EV", formatEv(-1f))
        // 一位小数（0.05 EV 这一档是滑杆的常见落点）
        assertEquals("+0.1 EV", formatEv(0.05f / 3f))
        assertEquals("-0.3 EV", formatEv(-0.1f))
    }

    @Test
    fun exposureClampsToFullScaleAndHidesNegativeZero() {
        // 越界夹到 ±3 EV（手势产生不了越界值，但坏快照/未来改量程不该把非法读数带上屏）
        assertEquals("+3.0 EV", formatEv(9f))
        assertEquals("-3.0 EV", formatEv(-9f))
        // −0.04 EV 四舍五入到 0.0 ⇒ 必须显示 "0.0 EV"，不能是 "-0.0 EV"
        assertEquals("0.0 EV", formatEv(-0.01f))
        assertEquals("0.0 EV", formatEv(-0.001f))
    }

    @Test
    fun exposureFallsBackToIdentityOnBadData() {
        // 读数绝不能显示 NaN：坏数据一律当"不变"
        assertEquals("0.0 EV", formatEv(Float.NaN))
        assertEquals("0.0 EV", formatEv(Float.POSITIVE_INFINITY))
        assertEquals("0.0 EV", formatEv(Float.NEGATIVE_INFINITY))
    }

    // ── 相对量：±100 整数（对比度 / 饱和度 / 白平衡增益）─────────────────────

    @Test
    fun relativeReadoutIsSignedOffsetFromPivot() {
        // 恒等值（pivot = 1.0 = 增益 1.00 不变）显示 0
        assertEquals("0", formatOffset100(1f, 1f))
        assertEquals("+12", formatOffset100(1.12f, 1f))
        assertEquals("-12", formatOffset100(0.88f, 1f))
        // 白平衡增益量程 0.5..2.0：负数侧只到 −50（量程不对称，如实显示、不硬凑）
        assertEquals("-50", formatOffset100(0.5f, 1f))
        assertEquals("+100", formatOffset100(2f, 1f))
        // 对比度量程 0.01..2.0：下限是 −99 而不是 −100（CONTRAST_UI_RANGE 的端点禁用 0）
        assertEquals("-99", formatOffset100(0.01f, 1f))
        // 饱和度量程 0..2.0：正好对称
        assertEquals("-100", formatOffset100(0f, 1f))
    }

    @Test
    fun relativeReadoutClampsAndSurvivesBadData() {
        assertEquals("+100", formatOffset100(5f, 1f))
        assertEquals("-100", formatOffset100(-5f, 1f))
        assertEquals("0", formatOffset100(Float.NaN, 1f))
        assertEquals("0", formatOffset100(Float.POSITIVE_INFINITY, 1f))
        // pivot 本身是坏数据时也不能输出 NaN
        assertEquals("0", formatOffset100(1.5f, Float.NaN))
    }

    // ── 无符号量：0..100 / 0..200 整数 ──────────────────────────────────────

    @Test
    fun zeroTo100ReadsAsUnsignedInteger() {
        assertEquals("0", formatZero100(0f))
        assertEquals("35", formatZero100(0.35f))
        assertEquals("100", formatZero100(1f))
        // 边界与坏数据
        assertEquals("100", formatZero100(1.5f))
        assertEquals("0", formatZero100(-1f))
        assertEquals("0", formatZero100(Float.NaN))
        assertEquals("0", formatZero100(Float.NEGATIVE_INFINITY))
        // 四舍五入（0.999 → 100，不是 99）
        assertEquals("100", formatZero100(0.999f))
    }

    @Test
    fun zeroTo200ReadsAsUnsignedInteger() {
        // 暗角半径 / 羽化：darktable 的 scale / falloff_scale 本身就是 0..200 语义
        assertEquals("80", formatZero200(0.8f))
        assertEquals("50", formatZero200(0.5f))
        assertEquals("200", formatZero200(2f))
        assertEquals("200", formatZero200(2.5f))
        assertEquals("0", formatZero200(Float.NaN))
    }

    @Test
    fun degreeReadoutIsUnsignedIntegerDegree() {
        // 局部调整的条带法线方向：0° = 条带竖直、90° = 条带水平（E2-7 定的口径）
        assertEquals("0°", formatDegree(0f))
        assertEquals("45°", formatDegree(0.25f))
        assertEquals("90°", formatDegree(0.5f))
        assertEquals("180°", formatDegree(1f))
        assertEquals("180°", formatDegree(1.5f))
        assertEquals("0°", formatDegree(Float.NaN))
    }

    // ── 中点标记位置（三条关键修正的第 2 条）────────────────────────────────

    @Test
    fun pivotFractionLocatesEveryIdentityPoint() {
        // 曝光 / 局部曝光（−1..1，不变点 0）⇒ 正中
        assertEquals(0.5f, pivotFraction(-1f..1f, 0f)!!, 1e-6f)
        // 饱和度 / 局部饱和度（0..2，不变点 1）⇒ 正中
        assertEquals(0.5f, pivotFraction(0f..2f, 1f)!!, 1e-6f)
        // 白平衡（0.5..2，不变点 1）⇒ 三分之一处
        assertEquals(1f / 3f, pivotFraction(0.5f..2f, 1f)!!, 1e-6f)
        // 对比度（0.01..2，不变点 1）⇒ 略偏左（端点禁用 0 导致的非对称）
        assertEquals((1f - 0.01f) / (2f - 0.01f), pivotFraction(0.01f..2f, 1f)!!, 1e-6f)
        // 暗角半径 / 羽化：基准 = darktable 默认 80 / 50（告诉用户"默认在哪"）
        assertEquals(0.4f, pivotFraction(0f..2f, 0.8f)!!, 1e-6f)
        assertEquals(0.25f, pivotFraction(0f..2f, 0.5f)!!, 1e-6f)
        // 局部半径 / 宽度 / 羽化：基准 = 各自的默认值
        assertEquals(0.35f, pivotFraction(0f..1f, 0.35f)!!, 1e-6f)
        assertEquals(0.25f, pivotFraction(0f..1f, 0.25f)!!, 1e-6f)
    }

    @Test
    fun pivotFractionSkipsEndpointsAndBadRanges() {
        // 基准落在端点 = **不画**（清晰度 0 / 暗角强度 0 / LUT 强度 0 / 角度 0 都在最左端，
        // 端点本身就是标记，再画一条只会糊）
        assertNull(pivotFraction(0f..1f, 0f))
        assertNull(pivotFraction(0f..1f, 1f))
        assertNull(pivotFraction(-1f..1f, -1f))
        assertNull(pivotFraction(-1f..1f, 1f))
        // 基准在量程外
        assertNull(pivotFraction(0f..1f, 5f))
        assertNull(pivotFraction(0f..1f, -5f))
        // 量程退化 / 反向 / 坏数据
        assertNull(pivotFraction(1f..1f, 1f))
        assertNull(pivotFraction(2f..1f, 1.5f))
        assertNull(pivotFraction(0f..1f, Float.NaN))
        assertNull(pivotFraction(Float.NaN..1f, 0.5f))
    }

    // ── 读数口径总表（每个参数的格式 + 中点值 + 两端）───────────────────────

    /**
     * 面板上**每一个**参数的读数规格。这份表是 `EditorScreen.kt` 参数表里 `format` 一格的
     * 可执行版本 —— 改口径必须同时改两处，改漏了这里立刻红。
     *
     * 字段：UI 量程 / 基准值 / 左端读数 / **基准（中点）读数** / 右端读数。
     */
    private class Spec(
        val name: String,
        val range: ClosedFloatingPointRange<Float>,
        val pivot: Float,
        val left: String,
        val atPivot: String,
        val right: String,
    )

    private val specs = listOf(
        Spec("曝光", -1f..1f, 0f, "-3.0 EV", "0.0 EV", "+3.0 EV"),
        Spec("白平衡", 0.5f..2f, 1f, "-50", "0", "+100"),
        Spec("对比度", 0.01f..2f, 1f, "-99", "0", "+100"),
        Spec("饱和度", 0f..2f, 1f, "-100", "0", "+100"),
        Spec("清晰度", 0f..1f, 0f, "0", "0", "100"),
        Spec("LUT 强度", 0f..1f, 0f, "0", "0", "100"),
        Spec("暗角强度", 0f..1f, 0f, "0", "0", "100"),
        Spec("暗角半径", 0f..2f, 0.8f, "0", "80", "200"),
        Spec("暗角羽化", 0f..2f, 0.5f, "0", "50", "200"),
        Spec("局部半径", 0f..1f, 0.35f, "0", "35", "100"),
        Spec("局部角度", 0f..1f, 0f, "0°", "0°", "180°"),
        Spec("局部宽度", 0f..1f, 0.25f, "0", "25", "100"),
        Spec("局部羽化", 0f..1f, 0.5f, "0", "50", "100"),
        Spec("局部曝光", -1f..1f, 0f, "-3.0 EV", "0.0 EV", "+3.0 EV"),
        Spec("局部饱和度", 0f..2f, 1f, "-100", "0", "+100"),
    )

    /** 按参数名调用对应的格式化函数（与 EditorScreen 参数表里的 `format` 一格一一对应） */
    private fun formatOf(name: String, v: Float): String = when (name) {
        "曝光", "局部曝光" -> formatEv(v)
        "清晰度", "LUT 强度", "暗角强度", "局部半径", "局部宽度", "局部羽化" -> formatZero100(v)
        "暗角半径", "暗角羽化" -> formatZero200(v)
        "局部角度" -> formatDegree(v)
        else -> formatOffset100(v, 1f)   // 白平衡 / 对比度 / 饱和度 / 局部饱和度
    }

    @Test
    fun everyParamReadsItsEndpointsAndPivotAsSpecified() {
        for (s in specs) {
            assertEquals("${s.name} 左端读数", s.left, formatOf(s.name, s.range.start))
            assertEquals("${s.name} 基准（中点）读数", s.atPivot, formatOf(s.name, s.pivot))
            assertEquals("${s.name} 右端读数", s.right, formatOf(s.name, s.range.endInclusive))
        }
    }

    @Test
    fun everyParamReadoutIsMonotonicAcrossItsRange() {
        // 读数必须随滑杆位置**单调不降**：任何"拖到右边读数反而变小"都是把用户往错方向带
        //（旧实现把读数写死在组件里时，这正是最容易被改坏的一类）。
        for (s in specs) {
            var prev = Float.NEGATIVE_INFINITY
            var v = s.range.start
            val step = (s.range.endInclusive - s.range.start) / 200f
            repeat(201) {
                val n = numeric(formatOf(s.name, v))
                assertTrue("${s.name} 读数在 $v 处倒退了：$prev → $n", n >= prev)
                prev = n
                v += step
            }
        }
    }

    @Test
    fun everyParamPivotIsInsideItsRangeOrExplicitlyAtTheEdge() {
        // 基准值必须落在量程内（否则「重置」会把参数写到 clamp 之外 —— 读数与槽位不符）
        for (s in specs) {
            assertTrue(
                "${s.name} 的基准值 ${s.pivot} 不在量程 ${s.range} 内",
                s.pivot >= s.range.start && s.pivot <= s.range.endInclusive,
            )
        }
    }

    /** 读数 → 数值（去掉单位与正号），供单调性断言用 */
    private fun numeric(s: String): Float =
        s.removeSuffix(" EV").removeSuffix("°").removePrefix("+").toFloat()
}
