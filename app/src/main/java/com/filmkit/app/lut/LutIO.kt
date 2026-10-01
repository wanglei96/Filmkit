package com.filmkit.app.lut

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.OutputStream

/**
 * 用户 LUT 文件 IO（M4 §6.2）：格式嗅探 → 解析 → 存档；导出 .cube / Hald PNG。
 *
 * ## 无损口径（2026-10-01 定案，对照 PC /tmp/photoncamera 的 LutConverter）
 *
 *  - .cube 文本按 **float 解析，不重采样、不量化**；DOMAIN_MIN/MAX 原样保留
 *   （引擎 shader 按 dmin/dinv 归一，见 FrameRenderer::bindLut），原文件按字节存档。
 *    PC 对 >65 的 LUT 重采样到 33 —— 这是它的有损点，**不学**。
 *  - Hald PNG 源本身就是 8bit，/255 是精确表示；Level 8（PS 默认、绝对主流）→
 *    64³ 原样。超过引擎上限 [CubeLutParser.MAX_SIZE] 的（Level 12 = 144³）按三线性
 *    重采样到 128³ —— 全链路唯一有损点，且只发生在超限场景。
 *  - PNG 解码必须 `inScaled=false` + `inPremultiplied=false`（PC 实测教训：系统的
 *    自动缩放/预乘会按屏幕密度改写像素，LUT 值被静默破坏）。
 *  - 1D .cube 的展开是数学等价（曲线间不交叉），见 [CubeLutParser] expand1d。
 *
 * 仅 [parseBytes]/[writeHaldPng] 依赖 Android（PNG 编解码）；嗅探、解包、重采样、
 * .cube 读写都是纯 Kotlin，单测直接覆盖。
 */
object LutIO {

    /** 解析结果。kind ∈ {cube, hald, unwrapped}；resampled = 是否因超限做过重采样。 */
    data class Parsed(val lut: CubeLut, val kind: String, val resampled: Boolean)

    /** PNG 文件魔数（89 50 4E 47） */
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes[0] == PNG_MAGIC[0] && bytes[1] == PNG_MAGIC[1] &&
            bytes[2] == PNG_MAGIC[2] && bytes[3] == PNG_MAGIC[3]

    /**
     * 格式嗅探 + 解析（M4 §6.2 白名单：PNG(Hald/Unwrapped) 与 .cube 文本，其余拒绝）。
     * 解析失败抛 IllegalArgumentException（调用侧记日志回退，不静默降级）。
     */
    fun parseBytes(bytes: ByteArray, fallbackTitle: String): Parsed {
        require(bytes.isNotEmpty()) { "空文件" }
        if (isPng(bytes)) {
            val opts = BitmapFactory.Options().apply {
                inScaled = false          // 禁止按密度缩放（见类注释）
                inPremultiplied = false   // 禁止 alpha 预乘改写 RGB
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: throw IllegalArgumentException("PNG 解码失败（文件损坏或非位图）")
            try {
                return parseBitmap(bmp, fallbackTitle)
            } finally {
                bmp.recycle()
            }
        }
        // 白名单：文本入口只认 .cube 结构（TITLE/LUT_*/DOMAIN/数据行），解析失败即拒绝
        val text = bytes.toString(Charsets.UTF_8)
        val lut = CubeLutParser.parse(text, fallbackTitle)
        return Parsed(lut, "cube", resampled = false)
    }

    /** PNG 位图 → Hald / Unwrapped Cube 自动识别解包（PC 同款尺寸判据）。 */
    fun parseBitmap(bmp: Bitmap, fallbackTitle: String): Parsed {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        return parsePixels(pixels, w, h, fallbackTitle)
    }

    /** 像素数组 → LUT（纯函数，单测覆盖布局判据与排列）。 */
    fun parsePixels(pixels: IntArray, w: Int, h: Int, fallbackTitle: String): Parsed {
        require(w > 0 && h > 0 && pixels.size >= w * h) { "PNG 尺寸非法：${w}x$h" }
        // Hald Level N：正方形且边长 = N³，格点数 = N²（Level 8 → 64³，Level 12 → 144³）
        val level = if (w == h) cubeRootInt(w) else 0
        val size: Int
        val kind: String
        when {
            level >= 2 -> {
                size = level * level
                kind = "hald"
            }
            w == h * h && h >= 2 -> {  // Unwrapped Cube：宽 = 高²，高即格点数
                size = h
                kind = "unwrapped"
            }
            else -> throw IllegalArgumentException("PNG 不是 Hald CLUT 也不是 Unwrapped Cube：${w}x$h")
        }
        require(size in 2..CubeLutParser.MAX_SIZE) { "LUT 边长超范围：$size" }
        val data = FloatArray(size * size * size * 3)
        var idx = 0
        // 数据序与 .cube 一致（红最快）：idx = r + g·N + b·N²。Hald 的 tile 布局：
        // tile(b) 在 (b%level, b/level)，块内 x = squareX·N + r、y = squareY·N + g。
        if (kind == "hald") {
            for (b in 0 until size) {
                val sx = b % level * size
                val sy = b / level * size
                for (g in 0 until size) {
                    val row = (sy + g) * w + sx
                    for (r in 0 until size) {
                        val px = pixels[row + r]
                        data[idx] = (px ushr 16 and 0xFF) / 255f
                        data[idx + 1] = (px ushr 8 and 0xFF) / 255f
                        data[idx + 2] = (px and 0xFF) / 255f
                        idx += 3
                    }
                }
            }
        } else {
            for (g in 0 until size) {
                val row = g * w
                for (b in 0 until size) {
                    val col = b * size
                    for (r in 0 until size) {
                        val px = pixels[row + col + r]
                        data[idx] = (px ushr 16 and 0xFF) / 255f
                        data[idx + 1] = (px ushr 8 and 0xFF) / 255f
                        data[idx + 2] = (px and 0xFF) / 255f
                        idx += 3
                    }
                }
            }
        }
        var lut = CubeLut(fallbackTitle, size, data, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        var resampled = false
        if (size > CubeLutParser.MAX_SIZE) {
            lut = resampleTrilinear(lut, CubeLutParser.MAX_SIZE)
            resampled = true
        }
        return Parsed(lut, kind, resampled)
    }

    /** 整数立方根（Hald 判据）：level³ == n 时返回 level，否则 0。 */
    private fun cubeRootInt(n: Int): Int {
        if (n < 8) return 0
        val l = Math.round(Math.cbrt(n.toDouble())).toInt()
        return if (l in 2..64 && l * l * l == n) l else 0
    }

    /**
     * 三线性重采样（目标 ≤ [CubeLutParser.MAX_SIZE]）。仅用于超限 LUT（Hald Level 12）
     * 与 Hald 导出的尺寸归一（64³）。红最快序，边界 clamp。
     */
    fun resampleTrilinear(src: CubeLut, target: Int): CubeLut {
        require(target in 2..CubeLutParser.MAX_SIZE) { "目标边长非法：$target" }
        val n = src.size
        if (n == target) return src
        val out = FloatArray(target * target * target * 3)
        var idx = 0
        for (b in 0 until target) {
            val z = mapCoord(b, target, n)
            for (g in 0 until target) {
                val y = mapCoord(g, target, n)
                for (r in 0 until target) {
                    val x = mapCoord(r, target, n)
                    val x0 = x.toInt(); val y0 = y.toInt(); val z0 = z.toInt()
                    val x1 = (x0 + 1).coerceAtMost(n - 1)
                    val y1 = (y0 + 1).coerceAtMost(n - 1)
                    val z1 = (z0 + 1).coerceAtMost(n - 1)
                    val dx = x - x0; val dy = y - y0; val dz = z - z0
                    for (c in 0..2) {
                        val v000 = src.data[(z0 * n + y0) * n * 3 + x0 * 3 + c]
                        val v100 = src.data[(z0 * n + y0) * n * 3 + x1 * 3 + c]
                        val v010 = src.data[(z0 * n + y1) * n * 3 + x0 * 3 + c]
                        val v110 = src.data[(z0 * n + y1) * n * 3 + x1 * 3 + c]
                        val v001 = src.data[(z1 * n + y0) * n * 3 + x0 * 3 + c]
                        val v101 = src.data[(z1 * n + y0) * n * 3 + x1 * 3 + c]
                        val v011 = src.data[(z1 * n + y1) * n * 3 + x0 * 3 + c]
                        val v111 = src.data[(z1 * n + y1) * n * 3 + x1 * 3 + c]
                        val v00 = v000 + (v100 - v000) * dx
                        val v10 = v010 + (v110 - v010) * dx
                        val v01 = v001 + (v101 - v001) * dx
                        val v11 = v011 + (v111 - v011) * dx
                        val v0 = v00 + (v10 - v00) * dy
                        val v1 = v01 + (v11 - v01) * dy
                        out[idx + c] = v0 + (v1 - v0) * dz
                    }
                    idx += 3
                }
            }
        }
        return CubeLut(src.title, target, out, src.domainMin, src.domainMax)
    }

    /** 目标格点 i → 源浮点坐标（均匀网格映射，clamp 到 [0, n-1]）。 */
    private fun mapCoord(i: Int, target: Int, n: Int): Float {
        val v = i.toFloat() / (target - 1) * (n - 1)
        return v.coerceIn(0f, (n - 1).toFloat())
    }

    /**
     * 导出 3D .cube（§6.2 主格式）：红最快序、6 位小数；源域标签写入注释
     * （域感知依据，§6.2"导出选项"）。DOMAIN 非默认 [0,1] 时按原样写出。
     * 流式写出 —— 128³ 文本 ~56MB，整串拼接会双倍占内存。
     */
    fun writeCube(lut: CubeLut, out: OutputStream, domainTag: String = "display") {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("TITLE \"")
        w.write(lut.title.replace("\"", "'"))
        w.write("\"\n")
        w.write("# Exported by FilmKit (filmkit app); domain: ")
        w.write(domainTag)
        w.write("\n")
        w.write("LUT_3D_SIZE ${lut.size}\n")
        val defDomain = lut.domainMin.contentEquals(floatArrayOf(0f, 0f, 0f)) &&
            lut.domainMax.contentEquals(floatArrayOf(1f, 1f, 1f))
        if (!defDomain) {
            w.write("DOMAIN_MIN ${lut.domainMin.joinToString(" ")}\n")
            w.write("DOMAIN_MAX ${lut.domainMax.joinToString(" ")}\n")
        }
        for (b in 0 until lut.size) {
            for (g in 0 until lut.size) {
                val rowBase = (b * lut.size + g) * lut.size * 3
                for (r in 0 until lut.size) {
                    val o = rowBase + r * 3
                    w.write("%.6f %.6f %.6f\n".format(lut.data[o], lut.data[o + 1], lut.data[o + 2]))
                }
            }
        }
        w.flush()
    }

    /**
     * 导出 Hald CLUT PNG（§6.2：与 PS / 手机调色 App 互通）。统一重采样到 64³
     * （Level 8，PS「颜色查找表」导出的默认规格；level = √64 = 8，图 8³ = 512²）。
     * 8bit PNG 是交换惯例（读取端同样 /255 精确还原网格值）。
     */
    fun writeHaldPng(lut: CubeLut, out: OutputStream) {
        val src = if (lut.size == 64) lut else resampleTrilinear(lut, 64)
        val n = src.size
        val level = intSqrt(n).coerceAtLeast(2)  // 64 → 8（Hald：size = level²，图宽 = level³）
        require(level * level == n) { "Hald 导出要求边长为完全平方数：$n" }
        val img = n * level  // 512
        val pixels = IntArray(img * img)
        var idx = 0
        for (b in 0 until n) {
            val sx = b % level * n
            val sy = b / level * n
            for (g in 0 until n) {
                val row = (sy + g) * img + sx
                for (r in 0 until n) {
                    val v = src.data
                    val ri = ((v[idx] * 255f + 0.5f).toInt()).coerceIn(0, 255)
                    val gi = ((v[idx + 1] * 255f + 0.5f).toInt()).coerceIn(0, 255)
                    val bi = ((v[idx + 2] * 255f + 0.5f).toInt()).coerceIn(0, 255)
                    pixels[row + r] = 0xFF shl 24 or (ri shl 16) or (gi shl 8) or bi
                    idx += 3
                }
            }
        }
        val bmp = Bitmap.createBitmap(pixels, img, img, Bitmap.Config.ARGB_8888)
        try {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            bmp.recycle()
        }
        out.flush()
    }

    /** 整数平方根：s² == n 时返回 s，否则 0。 */
    private fun intSqrt(n: Int): Int {
        if (n < 4) return 0
        val s = Math.round(Math.sqrt(n.toDouble())).toInt()
        return if (s * s == n) s else 0
    }
}
