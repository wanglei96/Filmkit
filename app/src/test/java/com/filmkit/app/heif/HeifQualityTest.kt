package com.filmkit.app.heif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡 FIX4 的质量口径真源测试（纯函数 ⇒ JVM 可跑）。
 *
 * 为什么必须有：`HeifQuality` 决定 HEIF 交付档的**目标体积**与**码率**，是"压过头"这个
 * 缺陷的唯一防线；而它的关键换算是 `KEY_BIT_RATE`(bit/s) ÷ `frame-rate` = 单帧预算 ——
 * **漏掉这个折算就是本卡根因的一半**（旧代码上限 240 Mbps ⇒ 单帧仅 1 MB ⇒ 198 KB 成片）。
 * 所以下面第 ② 组用真机 12MP 数字**精确**锁住这个换算。
 */
class HeifQualityTest {

    // 真机参照：3072×4080 的 JPEG q100 实测 5,109,807 B（模型算出 5,091,840 B，差 0.35%）。
    private val w = 3072
    private val h = 4080

    /** ① 目标区间 = 同场景 JPEG 估算 × 40~60% ⇒ 12MP 落在 2.0~3.1 MB。 */
    @Test
    fun targetRange_matchesJpegModel() {
        val r = HeifQuality.targetRange(w, h)
        assertEquals("JPEG 估算体积（12MP × 3.25 bpp）", 5_091_840L, (r.first / 0.40f).toLong())
        assertEquals("下界 = JPEG × 0.40", 2_036_736L, r.first)
        assertEquals("上界 = JPEG × 0.60", 3_055_104L, r.last)
    }

    /** ② **核心**：码率必须按 frame-rate 折算成单帧预算给。 */
    @Test
    fun bitrateFor_scalesByFrameRate() {
        val want = HeifQuality.bitrateFor(w, h, null)
        // 目标区间中值 2,545,920 B × 8 bit × 30 fps = 611,020,800 bps
        assertEquals("12MP 目标中值对应码率", 611_020_800, want)
        // 漏掉 FRAME_RATE 折算只有 1/30（≈20 Mbps）⇒ 必然压过头，这里锁死它
        assertTrue("必须显著高于旧上限 240 Mbps", want > 240_000_000)
    }

    /** ③ 编码器声明的上限与兜底上限都要夹住（夹小不报错 ⇒ 必须靠日志 + 本测试）。 */
    @Test
    fun bitrateFor_clampsToCaps() {
        assertEquals("编码器上限更低 ⇒ 夹到它", 100_000_000, HeifQuality.bitrateFor(w, h, 100_000_000))
        assertEquals("编码器上限极高 ⇒ 仍按目标给（兜底 2 Gbps 只是上界，不会把 want 抬上去）",
            611_020_800, HeifQuality.bitrateFor(w, h, Int.MAX_VALUE))
        assertEquals("编码器上限极低 ⇒ 抬到下界", 8_000_000, HeifQuality.bitrateFor(w, h, 1))
        assertEquals("拿不到上限 ⇒ 用兜底", 611_020_800, HeifQuality.bitrateFor(w, h, null))
    }

    /** ④ 判定边界（闭区间：正好落在端点算达标）。 */
    @Test
    fun verdict_boundaries() {
        val r = HeifQuality.targetRange(w, h)
        assertEquals(HeifQuality.Verdict.OK, HeifQuality.verdict(r.first.toInt(), w, h))
        assertEquals(HeifQuality.Verdict.OK, HeifQuality.verdict(r.last.toInt(), w, h))
        assertEquals(HeifQuality.Verdict.TOO_SMALL, HeifQuality.verdict((r.first - 1).toInt(), w, h))
        assertEquals(HeifQuality.Verdict.TOO_BIG, HeifQuality.verdict((r.last + 1).toInt(), w, h))
        // 真机事故那一张：198 KB 必须被判成压过头
        assertEquals(HeifQuality.Verdict.TOO_SMALL, HeifQuality.verdict(197_926, w, h))
    }

    /** ⑤ QP 自校正方向 + 撞界即放弃（不许无限调）。 */
    @Test
    fun stepQp_directionAndLimits() {
        assertEquals("压过头 ⇒ 降 QP 提质量", 12, HeifQuality.stepQp(16, HeifQuality.Verdict.TOO_SMALL)!!)
        assertEquals("太胖 ⇒ 升 QP 降体积", 20, HeifQuality.stepQp(16, HeifQuality.Verdict.TOO_BIG)!!)
        assertNull("达标 ⇒ 停", HeifQuality.stepQp(16, HeifQuality.Verdict.OK))
        assertNull("已到下界", HeifQuality.stepQp(HeifQuality.QP_MIN, HeifQuality.Verdict.TOO_SMALL))
        assertNull("已到上界", HeifQuality.stepQp(HeifQuality.QP_MAX, HeifQuality.Verdict.TOO_BIG))
    }

    /** ⑥ 只有 QP 路能自校正，且受 MAX_QP_TRIES 约束。 */
    @Test
    fun refine_onlyQpPathAndBounded() {
        val qp = HeifQuality.strategies(w, h, null)[0]
        assertEquals("qp", qp.mode)
        val once = HeifQuality.refine(qp, HeifQuality.Verdict.TOO_SMALL, 0)
        assertNotNull("第一次还能再试", once)
        assertEquals(12, once!!.second.qp)
        assertNull("试满 MAX_QP_TRIES ⇒ 换路", HeifQuality.refine(qp, HeifQuality.Verdict.TOO_SMALL, HeifQuality.MAX_QP_TRIES - 1))
        val cq = HeifQuality.strategies(w, h, null)[1]
        val cbr = HeifQuality.strategies(w, h, null)[2]
        assertNull("cq 没有旋钮 ⇒ 试一次就换路", HeifQuality.refine(cq, HeifQuality.Verdict.TOO_SMALL, 0))
        assertNull("cbr 同上", HeifQuality.refine(cbr, HeifQuality.Verdict.TOO_SMALL, 0))
    }

    /** ⑦ 三路顺序 = qp → cq → cbr（稳妥度排），且都带同一码率。 */
    @Test
    fun strategies_orderAndSharedBitrate() {
        val s = HeifQuality.strategies(w, h, null)
        assertEquals(listOf("qp", "cq", "cbr"), s.map { it.mode })
        assertEquals(HeifQuality.QP_DEFAULT, s[0].qp)
        assertEquals(HeifQuality.CQ_QUALITY, s[1].quality)
        assertTrue("三路共用同一码率预算", s.map { it.bitrate }.distinct().size == 1)
        assertEquals("qp/cq 走 CQ 模式，cbr 走 CBR", listOf(HeifQuality.MODE_CQ, HeifQuality.MODE_CQ, HeifQuality.MODE_CBR), s.map { it.bitrateMode })
    }

    /** ⑧ 都没达标时留"最接近区间"的那份（不是最大也不是最小）。 */
    @Test
    fun pickBest_choosesClosest() {
        val s = HeifQuality.strategies(w, h, null)
        val r = HeifQuality.targetRange(w, h)
        val tooSmall = r.first.toInt() / 4
        val tooBig = (r.last * 2).toInt()
        val best = HeifQuality.pickBest(
            listOf(Triple(s[0], tooSmall, 10L), Triple(s[1], tooBig, 20L)), w, h,
        )
        assertEquals("两边都远 ⇒ 取距离小的那个", tooSmall, best!!.second)
        assertTrue(HeifQuality.isBetter(null, 1, w, h))
        assertTrue("更接近 ⇒ 换", HeifQuality.isBetter(tooSmall, (r.first + 10).toInt(), w, h))
        assertTrue("更远 ⇒ 不换", !HeifQuality.isBetter((r.first + 10).toInt(), tooSmall, w, h))
    }

    /**
     * ⑨ 防回归：最坏尝试次数必须有上限。
     *
     * 没有它时最坏 = qp×MAX_QP_TRIES + cq + cbr = 5 次编码 ≈ 9 s 落盘等待（用户可感）。
     * 这条守护的意义是"以后有人把它改大必须先看见这个测试变红"。
     */
    @Test
    fun totalTriesIsBounded() {
        assertTrue("总尝试上限必须 ≤ 3（≈5.4 s）", HeifQuality.MAX_TOTAL_TRIES <= 3)
        assertTrue("总上限不得小于单路上限", HeifQuality.MAX_TOTAL_TRIES <= HeifQuality.MAX_QP_TRIES)
    }

    /** ⑩ 进日志的字段齐（下一轮定位靠它，不许少）。 */
    @Test
    fun statsKvHasDiagnosticFields() {
        val r = HeifQuality.targetRange(w, h)
        val kv = HeifQuality.Stats("qp", 16, 611_020_800, 2_500_000, 1800, HeifQuality.Verdict.OK, 1, r.first, r.last).kv()
        for (k in listOf("bitrateMode", "qp", "bitrate", "bytes", "ms", "verdict", "tries", "targetMin", "targetMax")) {
            assertTrue("kv 缺少字段 $k", kv.contains("\"$k\""))
        }
    }
}
