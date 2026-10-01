package com.filmkit.app.lut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * LUT 文件 IO 自检（M4 §6.2）：Hald/Unwrapped 解包布局、.cube 导出往返、
 * 重采样、内容嗅探白名单。纯 JVM（PNG 编解码依赖 Android，不入单测）。
 */
class LutIOTest {

    /** ARGB 像素（保持 LutIO 的取通道方式） */
    private fun argb(r: Int, g: Int, b: Int) = 0xFF shl 24 or (r shl 16) or (g shl 8) or b

    /** Hald Level 2：size = 4³，图 8×8。格点 (r,g,b) 的值 = (r,g,b)/(size-1) 恒等 LUT。 */
    private fun haldPixels(level: Int): Pair<IntArray, Int> {
        val size = level * level
        val img = size * level
        val px = IntArray(img * img)
        for (b in 0 until size) {
            val sx = b % level * size
            val sy = b / level * size
            for (g in 0 until size) {
                for (r in 0 until size) {
                    px[(sy + g) * img + sx + r] =
                        argb(r * 255 / (size - 1), g * 255 / (size - 1), b * 255 / (size - 1))
                }
            }
        }
        return px to img
    }

    @Test
    fun parsesHaldLayout() {
        val (px, img) = haldPixels(2)  // 8×8 图 → 4³ LUT
        val p = LutIO.parsePixels(px, img, img, "hald2")
        assertEquals("hald", p.kind)
        assertEquals(4, p.lut.size)
        assertFalse(p.resampled)
        // 恒等 LUT：data[r + g·N + b·N²] = (r, g, b)/(N-1)
        val n = 4
        var idx = 0
        for (b in 0 until n) {
            for (g in 0 until n) {
                for (r in 0 until n) {
                    assertEquals(r / 3f, p.lut.data[idx], 1e-4f)
                    assertEquals(g / 3f, p.lut.data[idx + 1], 1e-4f)
                    assertEquals(b / 3f, p.lut.data[idx + 2], 1e-4f)
                    idx += 3
                }
            }
        }
    }

    @Test
    fun parsesUnwrappedLayout() {
        // Unwrapped：宽 = 高²（高 4 → 宽 16），x = b·N + r、y = g
        val n = 4
        val px = IntArray(n * n * n)
        for (g in 0 until n) {
            for (b in 0 until n) {
                for (r in 0 until n) {
                    px[g * (n * n) + b * n + r] =
                        argb(r * 255 / (n - 1), g * 255 / (n - 1), b * 255 / (n - 1))
                }
            }
        }
        val p = LutIO.parsePixels(px, n * n, n, "unwrapped")
        assertEquals("unwrapped", p.kind)
        assertEquals(n, p.lut.size)
        // 红最快 + 恒等：第 2 组（r 先动）= (1/3, 0, 0)
        assertEquals(1f / 3, p.lut.data[3], 1e-4f)
        assertEquals(0f, p.lut.data[4], 1e-4f)
    }

    @Test
    fun rejectsUnknownPngShape() {
        val err = try {
            LutIO.parsePixels(IntArray(7 * 5), 7, 5, "bad")
            throw AssertionError("应当解析失败")
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }
        assertTrue(err, err.contains("Hald") || err.contains("Unwrapped"))
    }

    /** .cube 往返：parse → writeCube → parse，网格值在 6 位小数精度内逐点一致 */
    @Test
    fun cubeRoundTrip() {
        val src = CubeLutParser.parse(sampleCube())
        val out = ByteArrayOutputStream()
        LutIO.writeCube(src, out, "display")
        val text = out.toString("UTF-8")
        assertTrue(text.contains("TITLE \"round\""))
        assertTrue(text.contains("domain: display"))
        assertTrue(text.contains("LUT_3D_SIZE 2"))
        val back = CubeLutParser.parse(text)
        assertEquals(src.size, back.size)
        assertEquals(src.data.size, back.data.size)
        for (i in src.data.indices) {
            assertEquals(src.data[i], back.data[i], 1e-6f)
        }
        // 源域 [0,1] 默认不写 DOMAIN 行；改域后要原样写回
        val shifted = CubeLut(src.title, src.size, src.data, floatArrayOf(0.1f, 0f, 0f), floatArrayOf(0.9f, 1f, 1f))
        val out2 = ByteArrayOutputStream()
        LutIO.writeCube(shifted, out2)
        val back2 = CubeLutParser.parse(out2.toString("UTF-8"))
        assertEquals(0.1f, back2.domainMin[0], 1e-6f)
        assertEquals(0.9f, back2.domainMax[0], 1e-6f)
    }

    /** 超限重采样：144³（Hald Level 12）→ 128³，端点保持、内部为三线性插值 */
    @Test
    fun resampleOversizeKeepsEndpoints() {
        // 3³ 恒等 LUT 重采样到 2³：角点必须落在原网格值上（三线性在角点处无混合）
        val n = 3
        val data = FloatArray(n * n * n * 3)
        var idx = 0
        for (b in 0 until n) {
            for (g in 0 until n) {
                for (r in 0 until n) {
                    data[idx] = r / 2f; data[idx + 1] = g / 2f; data[idx + 2] = b / 2f
                    idx += 3
                }
            }
        }
        val src = CubeLut("src", n, data, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        val down = LutIO.resampleTrilinear(src, 2)
        assertEquals(2, down.size)
        // (0,0,0) 与 (1,1,1) 角点保持
        assertEquals(0f, down.data[0], 1e-6f)
        assertEquals(1f, down.data[down.data.size - 1], 1e-6f)
        // 同尺寸重采样是恒等（返回原对象，不做无谓拷贝）
        assertSame(src, LutIO.resampleTrilinear(src, n))
    }

    /** 内容嗅探：.cube 文本走文本分支（PNG 分支依赖 Android，不入单测） */
    @Test
    fun parseBytesRoutesByText() {
        val bytes = sampleCube().toByteArray(Charsets.UTF_8)
        val p = LutIO.parseBytes(bytes, "route")
        assertEquals("cube", p.kind)
        assertEquals("round", p.lut.title)
        val bad = try {
            LutIO.parseBytes("NOT A LUT AT ALL".toByteArray(), "bad")
            throw AssertionError("应当解析失败")
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }
        assertTrue(bad, bad.isNotEmpty())
    }

    private fun sampleCube(): String = buildString {
        appendLine("TITLE \"round\"")
        appendLine("LUT_3D_SIZE 2")
        for (b in 0 until 2) for (g in 0 until 2) for (r in 0 until 2) {
            appendLine("${r / 2f} ${g / 2f} ${b / 2f}")
        }
    }
}
