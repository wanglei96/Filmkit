package com.filmkit.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * E2-18a 方向解码的单测（纯 JVM，不依赖 Android runtime）：
 *  ① [exifOrientationTransform] 的 8 个取值全覆盖 + 非法值兜底；
 *  ② 组合语义（**先水平镜像、再顺时针旋转**）与 EXIF 定义的 8 个规范线性变换逐个对上
 *     —— 只测表值会漏掉"5/7 的旋转角写反"这类错，矩阵 golden 才抓得住；
 *  ③ [sampleSizeFor] 的采样率（2 的幂 + 不小于目标）；
 *  ④ **真实 DNG 离线实测**：文件里的 Orientation=6、存储 4080×3072，转正后 3072×4080，
 *     与同场景成片 JPEG（Orientation=1、3072×4080）同朝向。`test_temp/` 被 gitignore，
 *     文件不在时整段跳过（CI 不红）。
 */
class ImageDecodeTest {

    // ---------------------------------------------------------------- ① 映射表

    @Test
    fun orientationTableCoversAllEightValues() {
        val expected = mapOf(
            1 to (0 to false),   // 正常
            2 to (0 to true),    // 水平镜像
            3 to (180 to false), // 旋转 180°
            4 to (180 to true),  // 垂直镜像
            5 to (270 to true),  // 转置（主对角线）
            6 to (90 to false),  // 顺时针 90°
            7 to (90 to true),   // 反转置（副对角线）
            8 to (270 to false), // 顺时针 270°
        )
        assertEquals("8 个值都要在表里", 8, expected.size)
        for ((o, want) in expected) {
            assertEquals("orientation=$o", want, exifOrientationTransform(o))
        }
    }

    @Test
    fun illegalOrientationFallsBackToNormal() {
        for (o in intArrayOf(0, 9, -1, 99, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals("orientation=$o 应按 1 处理", 0 to false, exifOrientationTransform(o))
        }
    }

    // ------------------------------------------- ② 几何：组合顺序必须与 EXIF 定义一致

    /**
     * 2×2 线性部分（图像坐标 y 向下）：x' = a·x + b·y，y' = c·x + d·y。
     *
     * 手写 `equals`（不用 data class）：矩阵乘会算出 `-0.0f`（如 0·(-1) + (-1)·0），
     * 而 data class 的 `equals` 对装箱 Float 走 `Float.equals` ⇒ `-0.0f != 0.0f`，
     * 会报出与几何无关的假失败。这里用 `==`（IEEE 数值比较，`-0.0f == 0.0f`）。
     */
    private class Mat2(val a: Float, val b: Float, val c: Float, val d: Float) {
        operator fun times(o: Mat2) = Mat2(
            a * o.a + b * o.c, a * o.b + b * o.d,
            c * o.a + d * o.c, c * o.b + d * o.d,
        )

        override fun equals(other: Any?): Boolean =
            other is Mat2 && a == other.a && b == other.b && c == other.c && d == other.d

        override fun hashCode() = listOf(a, b, c, d).hashCode()

        override fun toString() = "[$a $b; $c $d]"
    }

    private val identity = Mat2(1f, 0f, 0f, 1f)

    /** 水平镜像（左右翻转） */
    private val mirrorH = Mat2(-1f, 0f, 0f, 1f)

    private fun rotate(deg: Int) = when (deg) {
        0 -> identity
        90 -> Mat2(0f, -1f, 1f, 0f)  // 顺时针 90°
        180 -> Mat2(-1f, 0f, 0f, -1f)
        270 -> Mat2(0f, 1f, -1f, 0f) // 顺时针 270°
        else -> throw IllegalArgumentException("bad rotation $deg")
    }

    /** 8 个 orientation 的**规范**变换（照 EXIF 定义独立写死，不用实现里的表推） */
    private val canonical = mapOf(
        1 to identity,
        2 to Mat2(-1f, 0f, 0f, 1f),  // 水平镜像：x'=-x, y'=y
        3 to Mat2(-1f, 0f, 0f, -1f), // 180°：x'=-x, y'=-y
        4 to Mat2(1f, 0f, 0f, -1f),  // 垂直镜像：x'=x, y'=-y
        5 to Mat2(0f, 1f, 1f, 0f),   // 转置：x'=y, y'=x
        6 to Mat2(0f, -1f, 1f, 0f),  // 顺时针 90°：x'=-y, y'=x
        7 to Mat2(0f, -1f, -1f, 0f), // 反转置：x'=-y, y'=-x
        8 to Mat2(0f, 1f, -1f, 0f),  // 顺时针 270°：x'=y, y'=-x
    )

    @Test
    fun transformMatchesExifGeometry() {
        for ((o, want) in canonical) {
            val (rot, flip) = exifOrientationTransform(o)
            // 与 ImageDecode.orient 的 Matrix 顺序同构：先 postScale(-1,1) 再 postRotate
            // ⇒ M = R(rot) · H（先镜像，后旋转）
            val got = rotate(rot) * (if (flip) mirrorH else identity)
            assertEquals("orientation=$o 的几何变换不对", want, got)
        }
    }

    @Test
    fun rotate90SwapsWidthAndHeight() {
        // 转正后宽高交换是"DNG 从横变竖"的全部依据（Bitmap.createBitmap 取变换后包围盒）
        val (rot, _) = exifOrientationTransform(6)
        assertEquals(90, rot)
        val w = 4080
        val h = 3072
        val (ow, oh) = if (rot % 180 != 0) h to w else w to h
        assertEquals(3072, ow)
        assertEquals(4080, oh)
    }

    // ---------------------------------------------------------------- ③ 采样率

    @Test
    fun sampleSizeIsPowerOfTwoAndNotSmallerThanTarget() {
        assertEquals("12MP → 512 格子：再降一档就不够", 4, sampleSizeFor(4080, 3072, 512, 512))
        assertEquals("12MP → 360 格子（典型手机屏宽/3）", 8, sampleSizeFor(4080, 3072, 360, 360))
        assertEquals("req<=0 = 不采样（照片查看页全分辨率）", 1, sampleSizeFor(4080, 3072, 0, 0))
        assertEquals("原图比目标还小", 1, sampleSizeFor(100, 100, 512, 512))
        assertEquals(1, sampleSizeFor(1000, 1000, 512, 512))
        assertEquals(2, sampleSizeFor(1024, 1024, 512, 512))
        assertEquals(4, sampleSizeFor(2048, 2048, 512, 512))
        // 任何输入都必须是 2 的幂（BitmapFactory 会把非 2 的幂向下取整，先自己算准）
        for (w in intArrayOf(1, 100, 1023, 4080, 12000)) {
            val s = sampleSizeFor(w, w, 512, 512)
            assertTrue("w=$w s=$s", s > 0 && (s and (s - 1)) == 0)
        }
    }

    // ------------------------------------------- ④ 真实文件离线实测（缺失则跳过）

    @Test
    fun realDngOrientationRotatesToPortraitLikeTheStill() {
        val dng = File("../test_temp/FILMKIT_20261009-090803.dng")
        val jpg = File("../test_temp/FILMKIT_20261009-090810.jpg")
        assumeTrue("test_temp 不在（已 gitignore），跳过真实文件实测", dng.isFile && jpg.isFile)

        val d = tiffHead(head(dng, 256 * 1024), 0) // DNG 本身就是 TIFF
        val j = jpegExif(head(jpg, 256 * 1024))    // 成片是 JPEG，EXIF 在 APP1

        // 文件里实际写着什么（这一层解析与实现用的 androidx ExifInterface 无关，属交叉验证）
        assertEquals("DNG 存储像素是横幅", 4080, d.width)
        assertEquals(3072, d.height)
        assertEquals("DNG 的 Orientation 应为 6（顺时针 90°）", 6, d.orientation)
        assertEquals("成片像素已转正 + Orientation=1", 1, j.orientation)
        assertEquals(3072, j.width)
        assertEquals(4080, j.height)

        // 应用方向：宽高交换 ⇒ 3072×4080，与成片同朝向
        val (rot, flip) = exifOrientationTransform(d.orientation)
        assertEquals(90 to false, rot to flip)
        val (ow, oh) = if (rot % 180 != 0) d.height to d.width else d.width to d.height
        assertEquals(3072, ow)
        assertEquals(4080, oh)
        assertEquals("DNG 转正后应与成片同尺寸/同朝向", j.width to j.height, ow to oh)
        assertTrue("两者转正后都应是竖幅", ow < oh)
    }

    // ------------------------------------------------------- 测试专用的极简 TIFF/EXIF 读取

    private data class Tiff(val width: Int, val height: Int, val orientation: Int)

    private fun head(f: File, n: Int): ByteArray {
        val len = minOf(n.toLong(), f.length()).toInt()
        val out = ByteArray(len)
        f.inputStream().use { ins ->
            var off = 0
            while (off < len) {
                val r = ins.read(out, off, len - off)
                if (r <= 0) break
                off += r
            }
        }
        return out
    }

    /** 读 TIFF 头（DNG 直接就是）IFD0 里的 ImageWidth/ImageLength/Orientation */
    private fun tiffHead(b: ByteArray, base: Int): Tiff {
        val le = b[base] == 'I'.code.toByte()
        fun u16(o: Int): Int {
            val x = b[o].toInt() and 0xFF
            val y = b[o + 1].toInt() and 0xFF
            return if (le) x or (y shl 8) else (x shl 8) or y
        }
        fun u32(o: Int) = if (le) u16(o) or (u16(o + 2) shl 16) else (u16(o) shl 16) or u16(o + 2)

        require(u16(base + 2) == 42) { "不是 TIFF（magic=${u16(base + 2)}）" }
        val ifd0 = base + u32(base + 4)
        val n = u16(ifd0)
        var w = 0
        var h = 0
        var o = 0
        for (i in 0 until n) {
            val e = ifd0 + 2 + i * 12
            val value = when (u16(e + 2)) { // type
                3 -> u16(e + 8) // SHORT
                4 -> u32(e + 8) // LONG
                else -> -1
            }
            when (u16(e)) { // tag
                0x0100 -> w = value
                0x0101 -> h = value
                0x0112 -> o = value
            }
        }
        return Tiff(w, h, o)
    }

    /** 扫 JPEG 段找 APP1 的 Exif，返回其中的 TIFF 头 */
    private fun jpegExif(b: ByteArray): Tiff {
        var i = 2
        while (i + 4 < b.size) {
            if (b[i].toInt() and 0xFF != 0xFF) break
            val marker = b[i + 1].toInt() and 0xFF
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            val exif = i + 10 < b.size &&
                b[i + 4] == 'E'.code.toByte() && b[i + 5] == 'x'.code.toByte() &&
                b[i + 6] == 'i'.code.toByte() && b[i + 7] == 'f'.code.toByte()
            if (marker == 0xE1 && exif) return tiffHead(b, i + 10)
            i += 2 + len
        }
        throw IllegalStateException("JPEG 里没找到 EXIF APP1")
    }
}
