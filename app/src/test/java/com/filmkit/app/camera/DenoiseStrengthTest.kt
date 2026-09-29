package com.filmkit.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 降噪强度滑杆（C 专项 2026-09-29）：0–2、步长 0.01。
 * 0 = 关、1 = 真实 σ、2 = 2×σ；含旧三档 prefs（level 0/1/2 + bool）的一次性迁移。
 */
class DenoiseStrengthTest {

    @Test
    fun newKey_takesValueAndClamps() {
        assertEquals(1.35f, CameraController.denoiseStrength(true, 1.35f, true, 1), 1e-6f)
        assertEquals(0f, CameraController.denoiseStrength(true, -0.5f, true, 1), 1e-6f)
        assertEquals(2f, CameraController.denoiseStrength(true, 2.7f, true, 1), 1e-6f)
    }

    @Test
    fun legacyLevels_migrate() {
        // 旧三档：0 关 / 1 标准 / 2 强 → 0 / 1 / 2
        assertEquals(0f, CameraController.denoiseStrength(false, 1f, true, 0), 1e-6f)
        assertEquals(1f, CameraController.denoiseStrength(false, 1f, true, 1), 1e-6f)
        assertEquals(2f, CameraController.denoiseStrength(false, 1f, true, 2), 1e-6f)
    }

    @Test
    fun legacyBoolFalse_isOff() {
        // 旧 bool 开关 false = 关（无论 level 存的是什么）
        assertEquals(0f, CameraController.denoiseStrength(false, 1f, false, 2), 1e-6f)
        assertEquals(0f, CameraController.denoiseStrength(false, 1f, false, 1), 1e-6f)
    }
}
