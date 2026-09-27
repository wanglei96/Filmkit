package com.filmkit.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MFNR 触发判定三档（0 关 / 1 自动暗光 / 2 强制）——与设置页「多帧融合」同源 */
class MfnrTriggerTest {

    private val raw = true
    private val rcd = true

    @Test
    fun mode0_off_neverTriggers() {
        // 关档：即使暗光、即使 RAW+RCD 也不触发
        assertFalse(CameraController.mfnrShouldTrigger(0, raw, rcd, 3200, 500_000_000L))
        assertFalse(CameraController.mfnrShouldTrigger(-1, raw, rcd, 3200, 500_000_000L))
    }

    @Test
    fun mode1_auto_requiresLowLight() {
        // 自动档：亮光不触发
        assertFalse(CameraController.mfnrShouldTrigger(1, raw, rcd, 50, 4_000_000L))
        assertFalse(CameraController.mfnrShouldTrigger(1, raw, rcd, 599, 24_999_999L))
        // 暗光（ISO ≥ 600 或曝光 ≥ 25ms）触发
        assertTrue(CameraController.mfnrShouldTrigger(1, raw, rcd, 600, 4_000_000L))
        assertTrue(CameraController.mfnrShouldTrigger(1, raw, rcd, 3200, 8_000_000L))
        assertTrue(CameraController.mfnrShouldTrigger(1, raw, rcd, 50, 25_000_000L))
        assertTrue(CameraController.mfnrShouldTrigger(1, raw, rcd, 100, 333_000_000L))
    }

    @Test
    fun mode2_force_triggersEvenInBrightLight() {
        // 强制档：白天也 4 帧（A/B 验收用）
        assertTrue(CameraController.mfnrShouldTrigger(2, raw, rcd, 50, 4_000_000L))
        assertTrue(CameraController.mfnrShouldTrigger(3, raw, rcd, 50, 4_000_000L))
    }

    @Test
    fun nonRawOrBilinear_neverTriggers() {
        // MFNR 只接 RAW 链 + RCD 质量档（bilinear 保持纯净基线，与降噪同约定）
        assertFalse(CameraController.mfnrShouldTrigger(2, false, rcd, 3200, 500_000_000L))
        assertFalse(CameraController.mfnrShouldTrigger(2, raw, false, 3200, 500_000_000L))
        assertFalse(CameraController.mfnrShouldTrigger(1, false, false, 3200, 500_000_000L))
    }
}
