package com.filmkit.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 同帧 A/B 的输出摘要（v0.10.85）：CRC32 全量 + 16×16 网格采样均值。
 * 用途 = 验证"拍照换执行路径不改画质"；摘要本身必须对单像素差异敏感（CRC 全量），
 * 并在尺寸与缓冲区不一致时不越界（真机旋转尺寸算错时曾有越界隐患）。
 */
class OutDigestTest {

    /** 2×2 RGBA：黑 / 中灰 / 白 / 红（intArrayOf → Byte，避免字面量类型不匹配） */
    private fun rgba2x2(): ByteArray = intArrayOf(
        0, 0, 0, 255,        // (0,0) 黑
        128, 128, 128, 255,  // (1,0) 中灰
        255, 255, 255, 255,  // (0,1) 白
        255, 0, 0, 255,      // (1,1) 红
    ).map { it.toByte() }.toByteArray()

    @Test
    fun mean_coversAllThreeChannels() {
        // (0+0+0 + 128*3 + 255*3 + 255+0+0) / 12 = (0 + 384 + 765 + 255) / 12
        val d = CameraController.outDigest(rgba2x2(), 2, 2)
        assertEquals((0 + 384 + 765 + 255) / 12.0, d.mean, 1e-9)
    }

    @Test
    fun crc_isDeterministicAndSinglePixelSensitive() {
        val a = CameraController.outDigest(rgba2x2(), 2, 2)
        val b = CameraController.outDigest(rgba2x2(), 2, 2)
        assertEquals(a.crc, b.crc)  // 同输入同输出（无随机性）

        val tweaked = rgba2x2().also { it[1] = 129.toByte() }  // 单通道 +1
        assertNotEquals(a.crc, CameraController.outDigest(tweaked, 2, 2).crc)
    }

    @Test
    fun sizeMismatch_doesNotThrow() {
        // 标称 4×4 但缓冲区只够前两个采样点：越界点被守卫跳过（不越界读），
        // 均值只用有效样本 —— (黑 0 + 灰 128×3) / 6 = 64
        val d = CameraController.outDigest(rgba2x2().copyOf(8), 4, 4)
        assertEquals(64.0, d.mean, 1e-9)
    }

    @Test
    fun emptyBuffer_yieldsSentinel() {
        val d = CameraController.outDigest(ByteArray(0), 0, 0)
        assertEquals(-1.0, d.mean, 1e-9)
    }
}
