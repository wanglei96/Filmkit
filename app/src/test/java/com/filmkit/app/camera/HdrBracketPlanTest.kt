package com.filmkit.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** HDR 融合 P1（v0.12.76）：侧帧曝光规划 —— 照抄 PC HdrBracketConfig.planManualExposure
 *  语义：先动快门（正 EV 快门顶 = min(range 顶, 10ms)）、钳制后 ISO 补偿、
 *  ISO 也钳制后反算快门（乘积尽量精确，双贴顶返回实际可达值）。
 *  docs/engine-hdr-exposure-fusion-design.md §4.1。 */
class HdrBracketPlanTest {

    // X300 量级的测试范围：ISO 50..3200、快门 100µs..1s
    private val isoLo = 50
    private val isoHi = 3200
    private val secLo = 100_000L      // 0.1ms
    private val secHi = 1_000_000_000L

    @Test
    fun zeroEv_returnsBaseUnchanged() {
        val (iso, sec) = CameraController.planBracketExposure(
            200, 10_000_000L, 0f, isoLo, isoHi, secLo, secHi)
        assertEquals(200, iso)
        assertEquals(10_000_000L, sec)
    }

    @Test
    fun longEv_shutterHasRoom_isoUnchanged() {
        // base 2ms + 2.2EV → 快门 2ms×4.598 ≈ 9.2ms（10ms 顶内）⇒ ISO 不动
        val (iso, sec) = CameraController.planBracketExposure(
            200, 2_000_000L, CameraController.HDR_LONG_EV, isoLo, isoHi, secLo, secHi)
        assertEquals(200, iso)
        assertEquals(Math.round(2_000_000L * Math.pow(2.0, 2.2)), sec)
    }

    @Test
    fun longEv_shutterHits10msCap_isoCompensates() {
        // base 30ms + 2.2EV：快门要 138ms，被 10ms 顶钳 ⇒ ISO 补偿（×13.78）。
        // PC 第三步反算的 10003179 > 10ms 顶 ⇒ 被 coerceIn 再钳回整 10ms
        //（乘积误差 0.03%，EV 达 2.1995 —— 实测核对走 bracket.actual）。
        val target = 100.0 * 30_000_000L * Math.pow(2.0, 2.2)
        val (iso, sec) = CameraController.planBracketExposure(
            100, 30_000_000L, CameraController.HDR_LONG_EV, isoLo, isoHi, secLo, secHi)
        assertEquals(Math.round(target / 10_000_000.0).toInt(), iso)
        assertEquals(10_000_000L, sec)
        val ev = CameraController.evOffsetSecIso(sec, iso, 30_000_000L, 100)
        assertEquals(2.2f, ev, 0.001f)
    }

    @Test
    fun longEv_bothCapped_returnsReachable() {
        // base ISO 800/50ms + 2.2EV：快门钳 10ms 后 ISO 要 800×23 = 18400 > 3200
        // ⇒ ISO 贴顶、快门按顶 ISO 反算（实际 EV 达不到 +2.2，由 bracket.actual 核对）
        val (iso, sec) = CameraController.planBracketExposure(
            800, 50_000_000L, CameraController.HDR_LONG_EV, isoLo, isoHi, secLo, secHi)
        assertEquals(isoHi, iso)
        assertEquals(10_000_000L, sec)
    }

    @Test
    fun shortEv_shutterOnly_isoUnchanged() {
        // base 10ms − 1.5EV → 快门 ≈ 3.54ms、ISO 不变（设计 §4.1：负 EV 只动快门）
        val (iso, sec) = CameraController.planBracketExposure(
            200, 10_000_000L, CameraController.HDR_SHORT_EV, isoLo, isoHi, secLo, secHi)
        assertEquals(200, iso)
        assertEquals(Math.round(10_000_000L * Math.pow(2.0, -1.5)), sec)
    }

    @Test
    fun shortEv_shutterFloorHit_isoDropsToKeepProduct() {
        // base 0.2ms − 1.5EV → 想要 70.7µs < 100µs 下限 ⇒ 快门贴下限、ISO 降
        //（PC 语义：钳制后 ISO 补偿维持乘积，不自创"ISO 不动"）。
        // ISO 舍入(141)后第三步反算 ⇒ sec = 100300（非整 100µs）。
        val target = 200.0 * 200_000L * Math.pow(2.0, -1.5)
        val (iso, sec) = CameraController.planBracketExposure(
            200, 200_000L, CameraController.HDR_SHORT_EV, isoLo, isoHi, secLo, secHi)
        assertEquals(Math.round(target / secLo.toDouble()).toInt(), iso)
        assertEquals(Math.round(target / iso.toDouble()).toLong(), sec)
        val ev = CameraController.evOffsetSecIso(sec, iso, 200_000L, 200)
        assertEquals(-1.5f, ev, 0.001f)
    }

    @Test
    fun longEvCap_neverBelowShutterFloor() {
        // 极端：快门顶（10ms）< 下限（比如 range [50ms, 1s]）⇒ effective 顶 =
        // max(10ms, 50ms) = 50ms（PC coerceAtLeast 语义）
        val (_, sec) = CameraController.planBracketExposure(
            200, 60_000_000L, CameraController.HDR_LONG_EV, isoLo, isoHi,
            50_000_000L, 1_000_000_000L)
        assertEquals(50_000_000L, sec)
    }

    @Test
    fun pcConstants_areExact() {
        // PC 一手常量（HdrBracketConfig.kt:8-10）—— 防手抖改错
        assertEquals(2.2f, CameraController.HDR_LONG_EV, 0f)
        assertEquals(-1.5f, CameraController.HDR_SHORT_EV, 0f)
        assertEquals(10_000_000L, CameraController.HDR_LONG_MAX_SHUTTER_NS)
    }
}
