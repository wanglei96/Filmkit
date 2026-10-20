package com.filmkit.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** bracket 实际曝光比（长/短）—— 以 CaptureResult 读回值为准（设计 §3.1/§3.5）。
 *  返回 1.0 表示"无短帧/无效"，调用方据此回退到同曝光路径。 */
class BracketRatioTest {

    @Test
    fun nominal_4x() {
        // 长 40ms / 短 10ms = 4×
        assertEquals(4f, CameraController.bracketRatio(10_000_000L, 40_000_000L), 1e-4f)
    }

    @Test
    fun halClampedShort_tooSmall_ratioFallsBack() {
        // HAL 把短帧 clamp 到 35ms ⇒ 实际只有 1.14×，不值得当短帧用
        assertEquals(1f, CameraController.bracketRatio(35_000_000L, 40_000_000L), 1e-4f)
    }

    @Test
    fun missingOrInvalidValues_fallBack() {
        assertEquals(1f, CameraController.bracketRatio(0L, 40_000_000L), 1e-4f)
        assertEquals(1f, CameraController.bracketRatio(10_000_000L, 0L), 1e-4f)
        // 短帧比长帧还长 = 参数错位，不能当有效短帧
        assertEquals(1f, CameraController.bracketRatio(40_000_000L, 10_000_000L), 1e-4f)
    }

    @Test
    fun absurdRatio_rejected() {
        // 短帧 1ms / 长帧 500ms = 500× ⇒ 超出 [1.5,16] 的合理区间
        assertEquals(1f, CameraController.bracketRatio(1_000_000L, 500_000_000L), 1e-4f)
    }

    @Test
    fun boundary_1p5_accepted() {
        assertEquals(1.5f, CameraController.bracketRatio(10_000_000L, 15_000_000L), 1e-4f)
    }
}
