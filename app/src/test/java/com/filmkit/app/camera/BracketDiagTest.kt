package com.filmkit.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** HDR 融合 P0（v0.12.75）：每帧实测 EV 偏移 + RAW 抽样统计（bracket.actual 的两个
 *  纯函数底座）。EV 用 CaptureResult 读回值算，不用请求值 —— HAL 可能 clamp。
 *  ⚠ rawSampleStats 抽样步长 16（stride16），测试图必须 ≥32×32 才能采到多个点。 */
class BracketDiagTest {

    // ── evOffsetSecIso ───────────────────────────────────────────────────

    @Test
    fun identicalFrame_isZero() {
        assertEquals(0f,
            CameraController.evOffsetSecIso(10_000_000L, 200, 10_000_000L, 200), 1e-4f)
    }

    @Test
    fun doubleExposureProduct_isPlusOneEv() {
        // sec×2（iso 同）= +1EV
        assertEquals(1f,
            CameraController.evOffsetSecIso(20_000_000L, 200, 10_000_000L, 200), 1e-4f)
        // iso×2（sec 同）= +1EV
        assertEquals(1f,
            CameraController.evOffsetSecIso(10_000_000L, 400, 10_000_000L, 200), 1e-4f)
    }

    @Test
    fun halfExposureProduct_isMinusOneEv() {
        assertEquals(-1f,
            CameraController.evOffsetSecIso(10_000_000L, 100, 10_000_000L, 200), 1e-4f)
    }

    @Test
    fun bracketSpans_matchPcConstants() {
        // PC HdrBracketConfig：长帧 +2.2EV、短帧 −1.5EV（iso 不变、sec ×2^ev）
        val baseSec = 5_000_000L
        val baseIso = 100
        assertEquals(2.2f, CameraController.evOffsetSecIso(
            Math.round(baseSec * Math.pow(2.0, 2.2)).toLong(), baseIso, baseSec, baseIso), 0.01f)
        assertEquals(-1.5f, CameraController.evOffsetSecIso(
            Math.round(baseSec * Math.pow(2.0, -1.5)).toLong(), baseIso, baseSec, baseIso), 0.01f)
    }

    @Test
    fun invalidInputs_areNaN() {
        assertTrue(CameraController.evOffsetSecIso(0L, 200, 10_000_000L, 200).isNaN())
        assertTrue(CameraController.evOffsetSecIso(10_000_000L, 0, 10_000_000L, 200).isNaN())
        assertTrue(CameraController.evOffsetSecIso(10_000_000L, 200, 0L, 200).isNaN())
        assertTrue(CameraController.evOffsetSecIso(10_000_000L, 200, 10_000_000L, 0).isNaN())
    }

    // ── rawSampleStats（从成员挪入 companion，行为必须等价） ─────────────

    /** 构造 RAW buffer：w×h 的 uint16（little-endian），stridePx = 行距（像素）。
     *  采样点为 (0,0)/(16,0)/(0,16)/(16,16)（stride16）。putShort 的 index 是
     *  **字节**偏移 ⇒ 像素坐标必须 ×2。 */
    private fun rawBuf(w: Int, h: Int, stridePx: Int, fill: (x: Int, y: Int) -> Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(h * stridePx * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until h) {
            for (x in 0 until w) {
                buf.putShort((y * stridePx + x) * 2, fill(x, y).toShort())
            }
        }
        return buf
    }

    @Test
    fun flatFrame_meanMatches_halfWhite_notSaturated() {
        // 32×32 全 512 ⇒ 4 个采样点全 v=512/1023
        val v = 512f / 1023f
        val buf = rawBuf(32, 32, 32) { _, _ -> 512 }
        val st = CameraController.rawSampleStats(buf, 32, 32, 32, 1023)
        assertEquals(v, st[0], 1e-4f)
        // 分位落在 256-bin 量化格：v*255=127.8 → bin127 → 127/255
        assertEquals(127f / 255f, st[1], 1e-3f)
        assertEquals(0f, st[3], 1e-4f)
    }

    @Test
    fun clippedPixels_countedInSaturation() {
        // 4 个采样点、1 个贴白阶（v=1.0 → bin255）⇒ sat = 1/4 = 0.25
        val buf = rawBuf(32, 32, 32) { x, y -> if (x == 0 && y == 0) 1023 else 256 }
        val st = CameraController.rawSampleStats(buf, 32, 32, 32, 1023)
        assertEquals(0.25f, st[3], 1e-3f)
        val hi = 1023f / 1023f
        val lo = 256f / 1023f
        assertEquals((hi + lo * 3) / 4f, st[0], 1e-3f)
    }

    @Test
    fun emptyFrame_returnsZeros() {
        val buf = rawBuf(32, 32, 32) { _, _ -> 0 }
        val st = CameraController.rawSampleStats(buf, 0, 0, 32, 1023)
        assertEquals(0f, st[0], 1e-6f)
        assertEquals(0f, st[3], 1e-6f)
    }

    @Test
    fun stridePadding_isSkipped() {
        // stridePx=64（32 有效像素 + 32 padding）：padding 写 1023（饱和），
        // 采样点 (0,0)/(16,0)/(0,16)/(16,16) 全在有效区 ⇒ sat 必须为 0
        val buf = rawBuf(32, 32, 64) { x, _ -> if (x >= 32) 1023 else 0 }
        val st = CameraController.rawSampleStats(buf, 32, 32, 64, 1023)
        assertEquals(0f, st[0], 1e-6f)
        assertEquals(0f, st[3], 1e-6f)
    }

    @Test
    fun dimFrame_reportsLowMean() {
        val buf = rawBuf(32, 32, 32) { _, _ -> 64 }  // 黑阶以下也按数值统计
        val st = CameraController.rawSampleStats(buf, 32, 32, 32, 1023)
        assertEquals(64f / 1023f, st[0], 1e-4f)
        assertFalse(st[3] > 0f)
    }
}
