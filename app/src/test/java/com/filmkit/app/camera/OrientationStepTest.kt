package com.filmkit.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拍摄方向映射的对照测试（v0.6.41）。
 *
 * 为什么值得单测：X300 的 `sensorOrientation = 90`，竖拍与倒竖下"我们的公式"和"PhotonCamera 的公式"
 * 恰好给出相同结果，**只有横拍会差 180°** —— 这个 bug 因此藏了几十个版本，直到 `orientationStep`
 * 真正开始跟随设备才暴露（v0.6.40 真机成片倒置）。四个姿态全验一遍比真机试错便宜得多。
 *
 * 口径对照：
 * - PhotonCamera（`CameraViewModel.kt:2995`，后置）：`rotation = (sensorOrientation + deviceRotation) % 360`
 * - 本仓库：`effectiveRot = (orientationStep + sensorOrientation / 90) % 4`，`orientationStep` 必须是
 *   **设备物理**旋转步进（`orientationToStep` 的输出），不是 `Display.getRotation()`。
 */
class OrientationStepTest {

    private val sensorSteps = 90 / 90 // X300 sensorOrientation = 90

    private fun ours(orientationDegrees: Int): Int =
        (CameraController.orientationToStep(orientationDegrees) + sensorSteps) % 4

    private fun photonCamera(deviceRotationDegrees: Int): Int =
        ((90 + deviceRotationDegrees) % 360) / 90

    @Test
    fun `四个姿态都与 PhotonCamera 公式一致`() {
        for (deg in intArrayOf(0, 90, 180, 270)) {
            assertEquals("姿态 $deg° 的像素旋转步进不一致", photonCamera(deg), ours(deg))
        }
    }

    @Test
    fun `竖拍与倒竖的绝对取值`() {
        // 竖拍输出竖幅（旋转步进为奇数 → 交换宽高），这是用户长期验证过的行为，不能被改坏
        assertEquals(1, ours(0))
        assertEquals(3, ours(180))
    }

    @Test
    fun `横拍必须与竖拍差 180 度`() {
        // 这条断言正是 v0.6.40 那个 bug 的守卫：横拍曾被算成 0/2（与竖拍差 90°）→ 成片倒置
        assertEquals(2, ours(90))  // 左侧朝上：设备顺时针 90° → 需顺时针 180°
        assertEquals(0, ours(270)) // 右侧朝上：设备逆时针 90° → 不需旋转
    }

    @Test
    fun `区间边界归属`() {
        assertEquals(0, CameraController.orientationToStep(0))
        assertEquals(0, CameraController.orientationToStep(44))
        assertEquals(1, CameraController.orientationToStep(45))
        assertEquals(1, CameraController.orientationToStep(134))
        assertEquals(2, CameraController.orientationToStep(135))
        assertEquals(2, CameraController.orientationToStep(224))
        assertEquals(3, CameraController.orientationToStep(225))
        assertEquals(3, CameraController.orientationToStep(314))
        assertEquals(0, CameraController.orientationToStep(315))
        assertEquals(0, CameraController.orientationToStep(359))
    }
}
