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
        // 目标区间中值 2,545,920 B × 8 bit × 1 fps（静态图只编 1 帧）= 20,367,360 bps
        assertEquals("12MP 目标中值对应码率", 20_367_360, want)
        // ⚠ **核心守护**：这个值必须**低于真机编码器声明的上限**（X300 实测 100 Mbps）。
        // frame-rate 一旦被改回 30，这里就是 611 Mbps > 100 Mbps ⇒ 被夹 ⇒ 单帧预算只剩
        // 416 KB ⇒ 真机落盘 392 KB、高频掉到 JPEG 的 32%（0.11.39 的真机事实）。
        assertTrue("必须低于真机上限 100 Mbps（否则被夹成 416 KB/帧）", want < 100_000_000)
    }

    /** ③ 编码器声明的上限与兜底上限都要夹住（夹小不报错 ⇒ 必须靠日志 + 本测试）。 */
    @Test
    fun bitrateFor_clampsToCaps() {
        assertEquals("真机上限 100 Mbps 高于目标 ⇒ 不夹，按目标给",
            20_367_360, HeifQuality.bitrateFor(w, h, 100_000_000))
        assertEquals("编码器上限极高 ⇒ 仍按目标给（兜底 2 Gbps 只是上界，不会把 want 抬上去）",
            20_367_360, HeifQuality.bitrateFor(w, h, Int.MAX_VALUE))
        assertEquals("编码器上限极低 ⇒ 抬到下界", 8_000_000, HeifQuality.bitrateFor(w, h, 1))
        assertEquals("拿不到上限 ⇒ 用兜底", 20_367_360, HeifQuality.bitrateFor(w, h, null))
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
        val d = HeifQuality.QP_DEFAULT
        assertEquals("压过头 ⇒ 降 QP 提质量", d - HeifQuality.QP_STEP, HeifQuality.stepQp(d, HeifQuality.Verdict.TOO_SMALL)!!)
        assertEquals("太胖 ⇒ 升 QP 降体积", d + HeifQuality.QP_STEP, HeifQuality.stepQp(d, HeifQuality.Verdict.TOO_BIG)!!)
        assertNull("达标 ⇒ 停", HeifQuality.stepQp(d, HeifQuality.Verdict.OK))
        assertNull("已到下界", HeifQuality.stepQp(HeifQuality.QP_MIN, HeifQuality.Verdict.TOO_SMALL))
        assertNull("已到上界", HeifQuality.stepQp(HeifQuality.QP_MAX, HeifQuality.Verdict.TOO_BIG))
    }

    /** ⑥ 只有 QP 路能自校正，且受 MAX_QP_TRIES 约束。 */
    @Test
    fun refine_onlyQpPathAndBounded() {
        val qp = HeifQuality.strategies(w, h, 100_000_000)[0]
        assertEquals("qp", qp.mode)
        assertEquals("起点 = 真机标定值", HeifQuality.QP_DEFAULT, qp.qp)
        val once = HeifQuality.refine(qp, HeifQuality.Verdict.TOO_SMALL, 0)
        assertNotNull("第一次还能再试", once)
        assertEquals("降一个步长", HeifQuality.QP_DEFAULT - HeifQuality.QP_STEP, once!!.second.qp)
        assertNull("试满 MAX_QP_TRIES ⇒ 换路", HeifQuality.refine(qp, HeifQuality.Verdict.TOO_SMALL, HeifQuality.MAX_QP_TRIES - 1))
        val cq = HeifQuality.Strategy("cq", HeifQuality.MODE_CQ, 20_367_360, quality = HeifQuality.CQ_QUALITY)
        val vbr = HeifQuality.Strategy("vbr", HeifQuality.MODE_VBR, 20_367_360)
        assertNull("cq 没有旋钮 ⇒ 试一次就换路", HeifQuality.refine(cq, HeifQuality.Verdict.TOO_SMALL, 0))
        assertNull("vbr 同上", HeifQuality.refine(vbr, HeifQuality.Verdict.TOO_SMALL, 0))
    }

    /**
     * ⑦ 两路顺序 = qp → cq（0.11.43 起；QP 起点已是真机标定值）。
     *
     * 三轮真机把"哪条路真的驱动质量"问清楚了（详见 `HeifQuality.strategies` 的表格）：
     * 官方 QP 键 + VBR 是**唯一**的强旋钮（QP12 ⇒ 1,653 KB / 高频 0.594），
     * `quality` 键天花板低（100 ⇒ 649 KB），码率键完全无效。
     */
    @Test
    fun strategies_orderAndQpStart() {
        val s = HeifQuality.strategies(w, h, 100_000_000)
        assertEquals(listOf("qp", "cq"), s.map { it.mode })
        assertEquals("qp 路必须走 VBR（CQ 下 QP 键会被编码器拒绝）", HeifQuality.MODE_VBR, s[0].bitrateMode)
        assertEquals("QP 起点 = 真机标定 10（= 目标区间中值 2.45 MB 对应的外推值）", 10, HeifQuality.QP_DEFAULT)
        assertEquals("自校正步长 = 2（细收敛，避免跳过 8/12）", 2, HeifQuality.QP_STEP)
        assertEquals("cq 路保留 quality=100 作兜底", HeifQuality.CQ_QUALITY, s[1].quality)
        assertEquals("兜底路走 CQ", HeifQuality.MODE_CQ, s[1].bitrateMode)
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
     * 没有它时最坏 = qp×MAX_QP_TRIES + cq + cbr（旧值 3+1+1 = 5 次编码 ≈ 9 s 落盘等待，用户可感）。
     * 这条守护的意义是"以后有人把它改大必须先看见这个测试变红"。
     */
    @Test
    fun totalTriesIsBounded() {
        assertTrue("总尝试上限必须 ≤ 3（≈5.4 s）", HeifQuality.MAX_TOTAL_TRIES <= 3)
        assertTrue(
            "总上限必须容得下三路各一次（qp / cq / cbr）",
            HeifQuality.MAX_TOTAL_TRIES >= 3,
        )
    }

    /** ⑩ 进日志的字段齐（下一轮定位靠它，不许少）。 */
    @Test
    fun statsKvHasDiagnosticFields() {
        val r = HeifQuality.targetRange(w, h)
        val kv = HeifQuality.Stats(
            "qp", 16, 20_367_360, 2_500_000, 1800, HeifQuality.Verdict.OK, 1,
            r.first, r.last, cap = 100_000_000,
        ).kv()
        for (k in listOf("bitrateMode", "qp", "bitrate", "bytes", "ms", "verdict", "tries",
                         "targetMin", "targetMax", "frameRate", "cap")) {
            assertTrue("kv 缺少字段 $k", kv.contains("\"$k\""))
        }
    }

    /**
     * ⑪ **根因守护**（0.11.40）：静态图的 `frame-rate` 必须是 1。
     *
     * 改回 30 ⇒ 换算出的码率 611 Mbps 撞真机上限 100 Mbps ⇒ 被夹 ⇒ 单帧预算 416 KB
     * ⇒ 落盘 392 KB、高频只剩 JPEG 的 32%（"细节还是不行"）。这一条与 ② 一起构成
     * "改错立刻红"的双保险。
     */
    @Test
    fun frameRateIsOneAndQpKeysAreOfficial() {
        assertEquals("静态图只编 1 帧 ⇒ frame-rate 必须是 1", 1, HeifQuality.FRAME_RATE)
        // QP 键必须是**官方** MediaFormat 键（vendor 键真机实测失败：设了拿不到 csd-0）
        assertEquals("video-qp-i-min", HeifQuality.QP_I_MIN_KEY)
        assertEquals("video-qp-i-max", HeifQuality.QP_I_MAX_KEY)
    }

    /**
     * ⑫ `capBitrate` 的边界（0.11.41 用于 `vbr-hi` 路；路已撤，函数保留给"给满预算"的场合）。
     */
    @Test
    fun capBitrateIsUpperBound() {
        assertEquals("BITRATE_MODE_VBR 官方取值 = 1", 1, HeifQuality.MODE_VBR)
        assertEquals("给满编码器上限", 100_000_000, HeifQuality.capBitrate(100_000_000))
        assertEquals("拿不到上限 ⇒ 兜底 2 Gbps", 2_000_000_000, HeifQuality.capBitrate(null))
        assertEquals("上限极低 ⇒ 抬到下界", 8_000_000, HeifQuality.capBitrate(1))
        assertEquals("极高上限 ⇒ 夹到兜底", 2_000_000_000, HeifQuality.capBitrate(Int.MAX_VALUE))
        assertTrue(
            "capBitrate 必须 ≥ 目标反算码率（否则它没有存在意义）",
            HeifQuality.capBitrate(100_000_000) >= HeifQuality.bitrateFor(w, h, 100_000_000),
        )
    }
}
