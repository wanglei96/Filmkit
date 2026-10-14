package com.filmkit.app.heif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 10bit 交付链 CPU 段的核心判据（卡 H2 · 硬判据 4 的离线部分）。
 *
 * 覆盖三件**错一眼看不出来**的事（每件都是静默错色/静默降位的典型来源）：
 *  ① `GL_UNSIGNED_INT_2_10_10_10_REV` 的**位序**（LSB-first：R 在 bit0..9）——
 *     错成 MSB-first 会让 R/B 互换而画面照常"有图"；
 *  ② RGB→YCbCr 的**矩阵与全范围零偏**（BT.709 + Cb/Cr 零偏 512）；
 *  ③ 4:2:0 的**平面顺序**（Y 每像素 / 色度写在块左上并只占 1/4 分辨率）——
 *     与 [Hevc10Encoder.fillInput] 的读取约定必须一致。
 *
 * 灰阶（v,v,v）判据一次性测掉 ①③：位错了 Y 不再恒等；平面顺序错了 Y 会读到色度值。
 */
class Heif10PipelineTest {

    /** 造 packed RGB10（10bit 值 0..1023）。 */
    private fun pack(r: Int, g: Int, b: Int) = (r and 0x3FF) or ((g and 0x3FF) shl 10) or ((b and 0x3FF) shl 20)

    /** 把 packed 值按 `GL_UNSIGNED_INT_2_10_10_10_REV` 的读回字节序（小端 uint32）写成字节流。 */
    private fun toReadbackBytes(px: IntArray): ByteArray {
        val out = ByteArray(px.size * 4)
        for (i in px.indices) {
            val v = px[i]
            out[i * 4] = (v and 0xFF).toByte()
            out[i * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        return out
    }

    // ── ① 解包（位序）────────────────────────────────────────────────────────

    @Test
    fun `解包 2_10_10_10_REV 位序：R 在低位段`() {
        // 关键值：每个通道单独拉满 ⇒ 位段错位/通道互换都会与期望不符
        val px = intArrayOf(
            pack(0, 0, 0),
            pack(1023, 0, 0),          // R 满 ⇒ 只能是 LSB-first
            pack(0, 1023, 0),
            pack(0, 0, 1023),
            pack(1, 512, 1023),
            pack(1023, 1023, 1023),
        )
        val got = Heif10Pipeline.unpackRgb10(toReadbackBytes(px), px.size, 1)
        for (i in px.indices) {
            assertEquals("R[$i]", px[i] and 0x3FF, got[i] and 0x3FF)
            assertEquals("G[$i]", (px[i] ushr 10) and 0x3FF, (got[i] ushr 10) and 0x3FF)
            assertEquals("B[$i]", (px[i] ushr 20) and 0x3FF, (got[i] ushr 20) and 0x3FF)
        }
    }

    @Test
    fun `解包按小端 uint32 读（高位字节是位 24 到 31）`() {
        // 只设 bit30（alpha 最高位）+ bit0：若误按大端读，得到的是完全不同的值
        val v = (1 shl 30) or 1
        val bytes = byteArrayOf(
            (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte(),
        )
        val got = Heif10Pipeline.unpackRgb10(bytes, 1, 1)
        assertEquals(1, got[0] and 0x3FF)
        assertEquals(0, (got[0] ushr 10) and 0x3FF)
        assertEquals(0, (got[0] ushr 20) and 0x3FF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `长度不符必须抛（不许静默截断）`() {
        Heif10Pipeline.unpackRgb10(ByteArray(4 * 3 - 1), 3, 1)
    }

    // ── ②③ 灰阶：位对齐 + 平面顺序 + 全范围零偏 ───────────────────────────────

    @Test
    fun `灰阶严格单调线性且色度恒为 512（一次测掉位对齐与平面顺序）`() {
        val n = 256
        val w = 16
        val h = 16
        val rgb = IntArray(w * h) { i ->
            val v = i * 1023 / (w * h - 1)   // 0..1023 覆盖全量程
            pack(v, v, v)
        }
        val p = Heif10Pipeline.toP010(rgb, w, h)

        // Y 必须严格单调递增且**逐点等于输入灰阶**（BT.709 系数和 = 65536 ⇒ 恒等）
        var prev = -1
        for (i in 0 until w * h) {
            val y = Heif10Pipeline.y10(p[i])
            assertTrue("Y[$i]=$y 必须严格大于前一个 $prev", y > prev)
            prev = y
        }
        for (i in 0 until w * h) {
            assertEquals("灰阶 Y 必须恒等于输入 v", Heif10Pipeline.y10(rgb[i]), Heif10Pipeline.y10(p[i]))
        }
        // 中性灰 ⇒ 全范围 Cb/Cr 的零偏 = 512，**块左上**像素携带色度
        var row = 0
        while (row < h) {
            var col = 0
            while (col < w) {
                val p0 = p[row * w + col]
                assertEquals("Cb@$row,$col", 512, Heif10Pipeline.cb10(p0))
                assertEquals("Cr@$row,$col", 512, Heif10Pipeline.cr10(p0))
                col += 2
            }
            row += 2
        }
        // 未携带色度的高位必须是干净的 0（否则会把别的数据当色度喂给编码器）
        assertEquals(0, Heif10Pipeline.cb10(p[1]))
        assertEquals(0, Heif10Pipeline.cr10(p[1]))
        assertTrue(n >= 256)
    }

    @Test
    fun `色度方向正确：R 强则 Cr 高 Cb 低，B 强反之`() {
        val w = 2
        val h = 2
        val rOnly = IntArray(w * h) { pack(1023, 0, 0) }
        val bOnly = IntArray(w * h) { pack(0, 0, 1023) }
        val pr = Heif10Pipeline.toP010(rOnly, w, h)
        val pb = Heif10Pipeline.toP010(bOnly, w, h)
        // BT.709：R↔Cr、B↔Cb（写反了就是整幅串色）
        assertTrue("R 满 ⇒ Cr 应 > 512（实际 ${Heif10Pipeline.cr10(pr[0])}）", Heif10Pipeline.cr10(pr[0]) > 512)
        assertTrue("R 满 ⇒ Cb 应 < 512（实际 ${Heif10Pipeline.cb10(pr[0])}）", Heif10Pipeline.cb10(pr[0]) < 512)
        assertTrue("B 满 ⇒ Cb 应 > 512（实际 ${Heif10Pipeline.cb10(pb[0])}）", Heif10Pipeline.cb10(pb[0]) > 512)
        assertTrue("B 满 ⇒ Cr 应 < 512（实际 ${Heif10Pipeline.cr10(pb[0])}）", Heif10Pipeline.cr10(pb[0]) < 512)
        // 全量程不溢出（clamp 到 10bit 边界）
        for (p in pr + pb) {
            assertTrue(Heif10Pipeline.y10(p) in 0..1023)
            assertTrue(Heif10Pipeline.cb10(p) in 0..1023)
            assertTrue(Heif10Pipeline.cr10(p) in 0..1023)
        }
    }

    @Test
    fun `白点与黑点落在全范围端点上`() {
        val black = Heif10Pipeline.toP010(IntArray(4) { pack(0, 0, 0) }, 2, 2)
        val white = Heif10Pipeline.toP010(IntArray(4) { pack(1023, 1023, 1023) }, 2, 2)
        assertEquals(0, Heif10Pipeline.y10(black[0]))
        assertEquals(1023, Heif10Pipeline.y10(white[0]))
        assertEquals(512, Heif10Pipeline.cb10(white[0]))
        assertEquals(512, Heif10Pipeline.cr10(white[0]))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `奇数尺寸必须抛（4x2x0 色度采样要求偶数）`() {
        Heif10Pipeline.toP010(IntArray(3), 3, 1)
    }

    // ── ④ 12MP 耗时实测（报告 §2.2 的数据来源）────────────────────────────────

    /**
     * 12MP（4000×3000）一张的 CPU 段耗时实测。**不做性能断言**（JIT/机器负载会让它抖动），
     * 只把数字打出来供报告引用 —— 项目规矩：性能取舍必须实测，不许拍脑袋。
     * JVM 环境与 ART 有差异（报告里已标注"待真机复核"）。
     */
    @Test
    fun `12MP 耗时实测（打印，供报告引用）`() {
        val w = 4000
        val h = 3000
        val n = w * h
        val rgb = IntArray(n) { i ->
            val v = (i * 1023) / n
            pack(v, (v * 3) % 1024, (v * 7) % 1024)
        }
        val bytes = toReadbackBytes(rgb)
        // 预热（JIT）
        Heif10Pipeline.toP010(Heif10Pipeline.unpackRgb10(ByteArray(4 * 4) {
            bytes[it]
        }, 2, 2), 2, 2)
        val t0 = System.nanoTime()
        val unpacked = Heif10Pipeline.unpackRgb10(bytes, w, h)
        val t1 = System.nanoTime()
        val pattern = Heif10Pipeline.toP010(unpacked, w, h)
        val t2 = System.nanoTime()
        println(
            "[H2-bench] 12MP(${w}x$h): unpack=${(t1 - t0) / 1_000_000}ms " +
                "rgb2yuv420p010=${(t2 - t1) / 1_000_000}ms total=${(t2 - t0) / 1_000_000}ms " +
                "(first=${Heif10Pipeline.y10(pattern[0])})",
        )
        assertEquals(n, pattern.size)
    }
}
