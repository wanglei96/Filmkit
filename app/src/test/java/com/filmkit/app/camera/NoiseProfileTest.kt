package com.filmkit.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * C 专项（2026-09-29）：SENSOR_NOISE_PROFILE → (S,O) 模型解析。
 * 用例覆盖：8 值相位序（X300/GRBG 与其余三种 CFA）、6 值 RGB 序、ISO 最近组选择、
 * 无效布局（全零 O / 负值 / 长度不足 / 空表）→ null。
 */
class NoiseProfileTest {

    /** X300 实读量级（ISO1970 → gain 19.7；DNG 51041 同源值写为 R/G/B） */
    private val sR = 7.06e-4
    private val sG = 6.18e-4
    private val sB = 7.05e-4
    private val oR = 3.79e-7
    private val oG = 3.91e-7
    private val oB = 3.81e-7

    private fun tol(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-9

    @Test
    fun grbg8_phaseOrder_mapsToRgb() {
        // GRBG（cfa=1，X300）：相位序 [Gr, R, B, Gb] → R=ph1, G=(ph0+ph3)/2, B=ph2
        val profile = doubleArrayOf(sG, oG, sR, oR, sB, oB, sG * 1.05, oG * 1.02)
        val r = NoiseProfile.parse(listOf(19.7 to profile), cfa = 1, iso = 1970)
        val m = r.model
        assertNotNull(m)
        assertEquals(sR, m!![0].toDouble(), 1e-9)
        assertEquals(0.5 * (sG + sG * 1.05), m[1].toDouble(), 1e-9)
        assertEquals(sB, m[2].toDouble(), 1e-9)
        assertEquals(oR, m[3].toDouble(), 1e-9)
        assertEquals(0.5 * (oG + oG * 1.02), m[4].toDouble(), 1e-9)
        assertEquals(oB, m[5].toDouble(), 1e-9)
        assertEquals(true, tol(r.gain, 19.7))
        // 8 值相位序下 R 必须来自相位 1 而不是相位 0（相位 0 是 Gr）
        assertEquals(false, tol(m[0].toDouble(), sG))
    }

    @Test
    fun rggb8_identityMapping() {
        // RGGB：相位序 [R, Gr, Gb, B] → identity
        val profile = doubleArrayOf(sR, oR, sG, oG, sG * 1.02, oG * 1.01, sB, oB)
        val m = NoiseProfile.parse(listOf(2.0 to profile), cfa = 0, iso = 200).model!!
        assertEquals(sR, m[0].toDouble(), 1e-9)
        assertEquals(0.5 * (sG + sG * 1.02), m[1].toDouble(), 1e-9)
        assertEquals(sB, m[2].toDouble(), 1e-9)
    }

    @Test
    fun bggr8_mapsToRgb() {
        // BGGR：相位序 [B, Gb, Gr, R] → R=ph3, G=(ph2+ph1)/2, B=ph0
        val profile = doubleArrayOf(sB, oB, sG, oG, sG * 1.02, oG, sR, oR)
        val m = NoiseProfile.parse(listOf(2.0 to profile), cfa = 3, iso = 200).model!!
        assertEquals(sR, m[0].toDouble(), 1e-9)
        assertEquals(0.5 * (sG + sG * 1.02), m[1].toDouble(), 1e-9)
        assertEquals(sB, m[2].toDouble(), 1e-9)
    }

    @Test
    fun sixValueLayout_isRgbDirect() {
        // 6 值 = 3 通道 (R,G,B)×(S,O)：与 DNG NoiseProfile 同序、与 cfa 无关
        val profile = doubleArrayOf(sR, oR, sG, oG, sB, oB)
        for (cfa in 0..3) {
            val m = NoiseProfile.parse(listOf(2.0 to profile), cfa = cfa, iso = 200).model!!
            assertEquals(sR, m[0].toDouble(), 1e-9)
            assertEquals(sG, m[1].toDouble(), 1e-9)
            assertEquals(sB, m[2].toDouble(), 1e-9)
        }
    }

    @Test
    fun scalarPairs_x300Shape_mapsToRgb() {
        // 结构 A（真机实测形态）：4 个 (S,O) 标量对 = CFA 相位序。
        // GRBG(cfa=1) 相位序 = [Gr, R, B, Gb] → R=p1, G=(p0+p3)/2, B=p2
        val pairs = listOf(
            sG to oG,          // ph0 = Gr
            sR to oR,          // ph1 = R
            sB to oB,          // ph2 = B
            sG * 1.05 to oG * 1.02, // ph3 = Gb
        )
        val m = NoiseProfile.fromScalarPairs(pairs, cfa = 1)!!
        assertEquals(sR, m[0].toDouble(), 1e-9)
        assertEquals(0.5 * (sG + sG * 1.05), m[1].toDouble(), 1e-9)
        assertEquals(sB, m[2].toDouble(), 1e-9)
        assertEquals(oR, m[3].toDouble(), 1e-9)
        assertEquals(0.5 * (oG + oG * 1.02), m[4].toDouble(), 1e-9)
        assertEquals(oB, m[5].toDouble(), 1e-9)
        // RGGB(cfa=0) 直取
        val m0 = NoiseProfile.fromScalarPairs(
            listOf(sR to oR, sG to oG, sG * 1.02 to oG, sB to oB), cfa = 0,
        )!!
        assertEquals(sR, m0[0].toDouble(), 1e-9)
        assertEquals(sB, m0[2].toDouble(), 1e-9)
    }

    @Test
    fun scalarPairs_invalidReturnNull() {
        assertNull(NoiseProfile.fromScalarPairs(emptyList(), 1))
        assertNull(NoiseProfile.fromScalarPairs(listOf(1.0 to 1.0), 1))
        // 全零 O
        assertNull(
            NoiseProfile.fromScalarPairs(
                listOf(sR to 0.0, sG to 0.0, sB to 0.0, sG to 0.0), 1,
            ),
        )
        // 负 S
        assertNull(
            NoiseProfile.fromScalarPairs(
                listOf(-sG to oG, sR to oR, sB to oB, sG to oG), 1,
            ),
        )
        // NaN
        assertNull(
            NoiseProfile.fromScalarPairs(
                listOf(sG to oG, sR to Double.NaN, sB to oB, sG to oG), 1,
            ),
        )
    }

    @Test
    fun picksNearestGainEntry() {
        val p1 = doubleArrayOf(sR, oR, sG, oG, sB, oB)
        val p2 = doubleArrayOf(sR * 2, oR * 2, sG * 2, oG * 2, sB * 2, oB * 2)
        val entries = listOf(1.0 to p1, 20.0 to p2)
        // ISO 1970 → wantGain 19.7 → 选 20.0 组
        val m = NoiseProfile.parse(entries, cfa = 1, iso = 1970).model!!
        assertEquals(sR * 2, m[0].toDouble(), 1e-9)
        // ISO 120 → wantGain 1.2 → 选 1.0 组
        val m2 = NoiseProfile.parse(entries, cfa = 1, iso = 120).model!!
        assertEquals(sR, m2[0].toDouble(), 1e-9)
    }

    @Test
    fun invalidLayouts_returnNull() {
        // 空表
        assertNull(NoiseProfile.parse(emptyList(), cfa = 1, iso = 200).model)
        // 长度不足（4 值 = 2 通道，不可识别）
        assertNull(NoiseProfile.parse(listOf(2.0 to doubleArrayOf(sR, oR, sG, oG)), 1, 200).model)
        // 全零 O（HAL 缺省，无法驱动读噪声传播）
        assertNull(
            NoiseProfile.parse(
                listOf(2.0 to doubleArrayOf(sR, 0.0, sG, 0.0, sB, 0.0)), 1, 200,
            ).model,
        )
        // 负值 / 非有限
        assertNull(
            NoiseProfile.parse(
                listOf(2.0 to doubleArrayOf(-sR, oR, sG, oG, sB, oB)), 1, 200,
            ).model,
        )
        assertNull(
            NoiseProfile.parse(
                listOf(2.0 to doubleArrayOf(sR, oR, Double.NaN, oG, sB, oB)), 1, 200,
            ).model,
        )
        // S 有 0 通道
        assertNull(
            NoiseProfile.parse(
                listOf(2.0 to doubleArrayOf(0.0, oR, sG, oG, sB, oB)), 1, 200,
            ).model,
        )
    }

    @Test
    fun gainSelection_ignoresShortEntries() {
        // 短条目（<6）跳过；好条目可用
        val entries = listOf(
            19.0 to doubleArrayOf(1.0, 1.0),  // 太短，跳过
            20.0 to doubleArrayOf(sR, oR, sG, oG, sB, oB),
        )
        val r = NoiseProfile.parse(entries, cfa = 1, iso = 1970)
        assertNotNull(r.model)
        assertEquals(20.0, r.gain, 1e-9)
    }
}
