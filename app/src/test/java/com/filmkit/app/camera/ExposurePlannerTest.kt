package com.filmkit.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曝光决策器自检（曝光决策专项 ②）。
 *
 * 期望值来自离线实测口径 `test_temp/exposure_lever/findings.md`：
 * 曝光 ×N 收益 √N；细节优先预算 2.2px；亮度守恒（deltaEv≈0）。
 */
class ExposurePlannerTest {

    private val baseIso = 4781
    private val baseSec = 38_461_538L          // 场景 1 真机实测（ISO 4781 / 38.46ms）

    /** 无运动限制：应打满 333ms 目标，ISO 按亮度守恒降到 ~552。 */
    @Test
    fun reachesTargetExposureAndPreservesBrightness() {
        val p = ExposurePlanner.plan(
            baseIso = baseIso, baseExposureNs = baseSec,
            isoLower = 50, exposureUpperNs = 1_000_000_000L,
            motionRadPerSec = 0.0,
        )
        assertEquals("target", p.limitedBy)
        assertEquals(333_000_000L, p.exposureNs)
        assertEquals(552, p.iso)
        assertEquals(8.66, p.n, 0.01)
        assertEquals(0.0, p.deltaEv, 0.01)      // 亮度守恒
        assertTrue(p.iso >= 50)
    }

    /** 运动限制：0.5°/s ⇒ 细节优先预算 2.2px 只允许 ~74.8ms（250ms 目标被运动否决）。 */
    @Test
    fun motionBudgetCapsExposureBeforeTarget() {
        val omega = 0.5 * Math.PI / 180.0       // 0.5°/s = 0.00873 rad/s
        val p = ExposurePlanner.plan(
            baseIso = baseIso, baseExposureNs = baseSec,
            isoLower = 50, exposureUpperNs = 1_000_000_000L,
            motionRadPerSec = omega,
        )
        assertEquals("motion", p.limitedBy)
        assertTrue("motionLimit≈74.8ms, got ${p.motionLimitNs}", Math.abs(p.motionLimitNs - 74_800_000L) < 1_000_000L)
        assertTrue("exposure=${p.exposureNs}", p.exposureNs in 74_000_000L..75_500_000L)
        assertEquals(1.94, p.n, 0.02)
        assertEquals(0.0, p.deltaEv, 0.01)
    }

    /** 正常手持（矢量平均 0.005 rad/s）⇒ 2.2px 预算仍允许 ~131ms（expN≈3.4）。 */
    @Test
    fun typicalHandheldStillGetsLongerExposure() {
        val p = ExposurePlanner.plan(
            baseIso = baseIso, baseExposureNs = baseSec,
            isoLower = 50, exposureUpperNs = 1_000_000_000L,
            motionRadPerSec = 0.005,
        )
        assertEquals("motion", p.limitedBy)
        assertTrue("exposure=${p.exposureNs}", p.exposureNs in 128_000_000L..134_000_000L)
        assertEquals(3.4, p.n, 0.05)
        assertEquals(0.0, p.deltaEv, 0.01)
        // 纹理红线：拉长后的预估帧内模糊必须仍在细节优先预算内
        assertTrue("blurPx=${p.blurPx} 超预算", p.blurPx <= ExposurePlanner.MOTION_BUDGET_PX + 1e-9)
    }

    /** ISO 下限限制：base ISO 400 / 下限 50 ⇒ 最多 8×。 */
    @Test
    fun isoFloorCapsExposure() {
        val p = ExposurePlanner.plan(
            baseIso = 400, baseExposureNs = 38_461_538L,
            isoLower = 50, exposureUpperNs = 1_000_000_000L,
            motionRadPerSec = 0.0,
        )
        assertEquals("iso", p.limitedBy)
        assertEquals(8.0, p.n, 0.01)
        assertEquals(50, p.iso)
        assertEquals(0.0, p.deltaEv, 0.01)
    }

    /** 传感器快门上限限制。 */
    @Test
    fun shutterUpperCapsExposure() {
        val p = ExposurePlanner.plan(
            baseIso = 4781, baseExposureNs = baseSec,
            isoLower = 50, exposureUpperNs = 100_000_000L,   // 只有 100ms
            motionRadPerSec = 0.0,
        )
        assertEquals("shutter", p.limitedBy)
        assertEquals(100_000_000L, p.exposureNs)
        assertEquals(2.6, p.n, 0.01)
    }

    /** 剧烈运动（10 rad/s）：绝不允许缩到基准以下（缩短直接损失 SNR）。 */
    @Test
    fun neverShortensBelowBaseExposure() {
        val p = ExposurePlanner.plan(
            baseIso = baseIso, baseExposureNs = baseSec,
            isoLower = 50, exposureUpperNs = 1_000_000_000L,
            motionRadPerSec = 10.0,
        )
        assertEquals("none", p.limitedBy)
        assertEquals(baseSec, p.exposureNs)
        assertEquals(baseIso, p.iso)
        assertEquals(0.0, p.deltaEv, 1e-9)
    }

    /** 无效输入不崩、原样返回。 */
    @Test
    fun invalidInputIsPassedThrough() {
        val p = ExposurePlanner.plan(
            baseIso = 0, baseExposureNs = baseSec,
            isoLower = 50, exposureUpperNs = 1_000_000_000L,
        )
        assertEquals("invalid", p.limitedBy)
        assertEquals(1.0, p.n, 1e-9)
    }

    // ── v0.10.110 高光保护（2026-10-02）────────────────────────────────────
    // 真机口径：预览 w999 = 2.4383（恰为 RAW 饱和数据白点）、base_ev+用户 EV = 0.503、
    // 成片 clipB 711/3072 = 23% 的块命中高光裁剪 ⇒ 曝光没有余量。

    /** 高光贴顶 + 提亮 ⇒ 欠曝：只缩短曝光时间，ISO 不变（降 ISO 救不回已饱和的高光）。 */
    @Test
    fun highlightProtectionUnderexposesWithoutRaisingIso() {
        val p = ExposurePlanner.plan(
            baseIso = 984, baseExposureNs = 50_000_000L,
            isoLower = 50, exposureUpperNs = 1_000_000_000L,
            targetExposureNs = 100_000_000L,
            sceneHighlightEv = 2.4383,      // 真机预览 w999
            highlightHeadroomEv = 0.35,
            exposureCompEv = 0.503,
        )
        assertEquals("highlight", p.limitedBy)
        assertEquals(984, p.iso)                        // 不抬 ISO
        assertTrue("应缩短曝光", p.exposureNs < 50_000_000L)
        // over = 2.4383 + 0.503 − (2.4383 − 0.35) = 0.853 ⇒ n = 2^−0.853 = 0.5535
        assertEquals(0.5535, p.n, 0.01)
        assertEquals(-0.853, p.deltaEv, 0.02)
        assertEquals((50_000_000L * p.n).toLong(), p.exposureNs)
    }

    /** 高光有余量 ⇒ 高光保护不介入，拉长侧照常工作。 */
    @Test
    fun highlightProtectionStaysOutOfTheWayWhenHeadroomExists() {
        val p = ExposurePlanner.plan(
            baseIso = 984, baseExposureNs = 50_000_000L,
            isoLower = 50, exposureUpperNs = 1_000_000_000L,
            targetExposureNs = 100_000_000L,
            sceneHighlightEv = 1.20,        // 提亮后 1.70 < 2.4383 − 0.35
            exposureCompEv = 0.503,
        )
        assertEquals("target", p.limitedBy)
        assertEquals(100_000_000L, p.exposureNs)
        assertEquals(492, p.iso)                        // 亮度守恒（984 ÷ 2）
    }

    /** 无统计（NaN）⇒ 不做高光保护（行为与历史逐位一致）。 */
    @Test
    fun missingSceneStatsDisableHighlightProtection() {
        val p = ExposurePlanner.plan(
            baseIso = 984, baseExposureNs = 50_000_000L,
            isoLower = 50, exposureUpperNs = 1_000_000_000L,
            targetExposureNs = 100_000_000L,
        )
        assertEquals("target", p.limitedBy)
        assertEquals(100_000_000L, p.exposureNs)
    }
}
