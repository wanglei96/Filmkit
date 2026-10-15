package com.filmkit.app.heif

/**
 * 10bit 交付链的 **CPU 段**：引擎 readback 的 packed RGB10 → **BT.709 全范围** YCbCr 4:2:0
 * → P010 图案（[Hevc10Encoder.encode] 的直接输入）。
 *
 * ## 挂点与一致性（改一处必须改另一处）
 * 交付域 = **sRGB 显示域 10bit**（用户拍板 R1 选项 A），取数点 = 现有 `tex_cap_`
 * （OETF 后、LUT/颗粒/锐化之后）。因此这里的输入就是**已成片显示域**的 RGB10。
 * ⚠ 色彩矩阵必须是 **BT.709**，且与容器 `colr.matrix_coefficients = 1` **同档**
 *   （[HeifContainerWriter.Cicp] 的默认 `matrix = 1`）。两处一起改，否则解码端串色：
 *   `matrix=1`(BT.709) ⇄ 本文件的 KR/KG/KB；改任意一处都要同时改另一处 + 重跑本文件单测。
 * ⚠ 全范围（full range）：`colr` 的 full_range 位 = 1（[HeifContainerWriter.Cicp.fullRange]），
 *   容器 pixi 声明 3×10bit；Cb/Cr 零偏 = 512。
 *
 * ## 为什么用 CPU（而不是 GPU pass）—— 见报告 §2.2 的实测与选项
 * 本卡判定 GPU 方案不划算：GPU 做色彩转换后仍要 CPU 侧做 P010 的 16bit MSB 对齐打包
 * （glReadPixels 不支持 P010 输出），收益被抵消；且新增 shader 会触碰 shader 冻结契约
 * （`tools/validate-shaders.sh` 逐字提取 + `FILM_SRC_BASELINE` 锚定），而 JVM 侧离线
 * 单测能直接覆盖位对齐与平面顺序这两项最容易错的点（硬判据 4）。
 * **CPU 耗时实测见报告 §2.2（12MP 一张）**，标"待优化"；GPU 化的选项与成本已在报告里
 * 交用户拍板（项目规矩：性能取舍必须实测 + 用户拍板，不许拍脑袋）。
 *
 * ## 定点实现（性能与正确性同保）
 * 全部用整数乘加 + 16 位定点系数（不引入浮点误差，且比 float 快 ~3 倍）：
 *   · KR/KG/KB = 13933/46871/4732，**和恰好 = 65536** ⇒ 灰阶 (v,v,v) 时 Y 恒等 = v
 *     （这是"灰阶严格线性单调"能一次性测掉位对齐与平面顺序的前提）；
 *   · 色差系数 = 1023/(2(1−Kb)) 与 1023/(2(1−Kr)) 的 16 位定点。
 */
object Heif10Pipeline {

    // BT.709 亮度系数（×65536，和 = 65536 —— 改动必须保持该恒等式，见类注释）
    private const val KR = 13933   // 0.2126
    private const val KG = 46871   // 0.7152
    private const val KB = 4732    // 0.0722

    // 色差定点系数：1/(2(1−Kb)) = 0.53891…、1/(2(1−Kr)) = 0.63500…（×65536）
    // 推导：cb10 = 512 + (B − Y)，其中 B/Y 是 10bit 值，系数含 1023 归一化抵消
    private const val KCB = 35319  // 0.538910
    private const val KCR = 41615  // 0.635000

    private const val HALF16 = 32768  // 16 位定点的四舍五入项

    /**
     * 解包引擎 readback 的 10bit 字节流 → 每像素 `r | (g shl 10) | (b shl 20)`（各 0..1023）。
     *
     * ⚠ 位序 = `GL_UNSIGNED_INT_2_10_10_10_REV` = **LSB-first**：bit0..9 = R、bit10..19 = G、
     *   bit20..29 = B、bit30..31 = alpha（GL_RGB10_A2 只有 2 bit alpha，交付不用）。
     *   这与 P010 的 **MSB-aligned 16bit word**（[Hevc10Encoder.putSample]）是两套约定，
     *   别互相套用 —— 位序错误的典型症状是整幅 R/B 互换且**不报错**。
     * 字节流是小端 uint32（4 B/像素，与 8bit 链的缓冲尺寸相同）。
     */
    fun unpackRgb10(bytes: ByteArray, w: Int, h: Int): IntArray {
        require(w > 0 && h > 0) { "尺寸非法: ${w}x$h" }
        val n = w * h
        require(bytes.size == n * 4) { "10bit 字节流长度 ${bytes.size} ≠ ${n}×4" }
        val out = IntArray(n)
        var i = 0
        var o = 0
        while (i < n) {
            // 小端 uint32（不依赖 ByteBuffer：单测纯 JVM、无平台字节序假设）
            val v = (bytes[o].toInt() and 0xFF) or
                ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                ((bytes[o + 2].toInt() and 0xFF) shl 16) or
                ((bytes[o + 3].toInt() and 0xFF) shl 24)
            out[i] = (v and 0x3FF) or (((v ushr 10) and 0x3FF) shl 10) or
                (((v ushr 20) and 0x3FF) shl 20)
            ++i
            o += 4
        }
        return out
    }

    /**
     * RGB10 → BT.709 全范围 YCbCr **4:2:0** → P010 图案
     * （每像素 `y | (cb shl 10) | (cr shl 20)`，各分量 0..1023，供 [Hevc10Encoder.encode]）。
     *
     * 色度下采样 = 2×2 盒式平均：YCbCr 变换对 RGB 是**线性**的 ⇒ `avg(Cb) == Cb(avg(RGB))`，
     * 故按 4 个像素的 R/B/Y 分别取平均后再变换，与"逐像素算 Cb/Cr 再平均"等价（省 4 倍算力）。
     * 灰阶下 avg(B) == avg(Y) ⇒ cb = cr = 512（中性色恒等，这是单测的核心判据）。
     *
     * @throws IllegalArgumentException 宽或高为奇数（4:2:0 要求偶数；HEIF 成片尺寸恒为偶数）
     */
    fun toP010(rgb10: IntArray, w: Int, h: Int, out: IntArray = IntArray(w * h)): IntArray {
        require(w > 0 && h > 0) { "尺寸非法: ${w}x$h" }
        require(w % 2 == 0 && h % 2 == 0) { "4:2:0 要求偶数尺寸，实际 ${w}x$h" }
        require(rgb10.size == w * h) { "rgb 大小 ${rgb10.size} ≠ ${w}x$h" }
        require(out.size == w * h) { "out 大小 ${out.size} ≠ ${w}x$h" }

        // ① Y 平面（每像素）：先写低 10 位，色度在 ② 里 or 进高 20 位
        for (i in 0 until w * h) {
            val v = rgb10[i]
            val r = v and 0x3FF
            val g = (v ushr 10) and 0x3FF
            val b = (v ushr 20) and 0x3FF
            out[i] = (KR * r + KG * g + KB * b + HALF16) ushr 16
        }

        // ② 色度平面（2×2 块共用一组 Cb/Cr），写进块内**左上**像素的高 20 位
        //    （P010 的色度平面是 1/4 分辨率，[Hevc10Encoder.fillInput] 按左上像素取值）
        var row = 0
        while (row < h) {
            var col = 0
            while (col < w) {
                val i0 = row * w + col
                val i1 = i0 + 1
                val i2 = i0 + w
                val i3 = i2 + 1
                val r0 = rgb10[i0]; val r1 = rgb10[i1]
                val r2 = rgb10[i2]; val r3 = rgb10[i3]
                val rAvg = (((r0 and 0x3FF) + (r1 and 0x3FF) + (r2 and 0x3FF) + (r3 and 0x3FF)) + 2) shr 2
                val bAvg = ((((r0 ushr 20) and 0x3FF) + ((r1 ushr 20) and 0x3FF) +
                    ((r2 ushr 20) and 0x3FF) + ((r3 ushr 20) and 0x3FF)) + 2) shr 2
                val yAvg = (out[i0] + out[i1] + out[i2] + out[i3] + 2) shr 2
                val cb = clamp10(512 + (((bAvg - yAvg) * KCB + HALF16) shr 16))
                val cr = clamp10(512 + (((rAvg - yAvg) * KCR + HALF16) shr 16))
                out[i0] = out[i0] or (cb shl 10) or (cr shl 20)
                col += 2
            }
            row += 2
        }
        return out
    }

    private fun clamp10(v: Int): Int = if (v < 0) 0 else if (v > 1023) 1023 else v

    // ── 交付组装 ──────────────────────────────────────────────────────────────

    /**
     * 本次交付的码率（bit/s）——**按目标体积反算**，不再是"拍脑袋估算"。
     *
     * ## 来历（卡 FIX4；0.11.40 修正 frame-rate 口径）
     * ① 旧公式 `w·h·30·2` 夹 [8, 240] Mbps：对 12MP 算出 737 Mbps 却被夹到 240 ⇒ 单帧预算
     *    1 MB ⇒ 真机 **198 KB**（同场景 JPEG 5.1 MB 的 1/25），高频只剩 30%。
     * ② 卡 FIX4 改成"目标中值 × 8 × frame-rate"，但 `frame-rate` 仍照抄视频的 **30** ⇒
     *    要报 **611 Mbps**，而真机 `bitrateRange.upper` = **100 Mbps** ⇒ 被夹 ⇒ 单帧预算
     *    只剩 416 KB ⇒ 真机 **392 KB**（0.11.39 实测，高频 32%）。
     * ③ **0.11.40 定案**：[HeifQuality.FRAME_RATE] = **1**（静态图 1 帧）⇒ 要报的码率 =
     *    2.45 MB × 8 × 1 = **20.4 Mbps**，低于上限、**不再被夹**。这才是"细节不行"的根因。
     *
     * ⚠ **仍然要真机标定**的量只有两个：[HeifQuality.JPEG_REFERENCE_BPP]（JPEG 基准
     * bit/px，随场景噪声涨跌）与 [HeifQuality.QP_DEFAULT]（QP 起点）。标定后只改那两处。
     */
    fun defaultBitrate(w: Int, h: Int): Int = HeifQuality.bitrateFor(w, h, null)

    /**
     * 引擎 10bit 交付档的 readback 字节流 → **完整 HEIF 文件字节**
     * （解包 → BT.709 全范围 YCbCr 4:2:0 → P010 → HEVC Main10 编码 → 自研容器）。
     *
     * 这是"10bit HEIF 交付链"的单一组装点：调用方（`CameraController.saveHeif`）只负责
     * 落盘与回退，不重复任何一步。
     *
     * @param outStats 编码统计出口（`photo.heif_encoded` 的 `bitrateMode`/`qp`/`bitrate`/`bytes`/`ms`）。
     * @throws IllegalStateException 编码器不可用 / 编码失败 / 容器构造失败
     *   —— 调用方**必须**回退 JPEG（照片优先红线），不许把异常吞掉后当成功。
     */
    fun toHeifFile(
        bytes: ByteArray,
        w: Int,
        h: Int,
        bitrate: Int = defaultBitrate(w, h),
        outStats: (HeifQuality.Stats) -> Unit = {},
    ): ByteArray {
        require(w % 2 == 0 && h % 2 == 0) { "HEIF 成片尺寸必须偶数（4:2:0），实际 ${w}x$h" }
        val rgb = unpackRgb10(bytes, w, h)
        val pattern = toP010(rgb, w, h)
        val image = Hevc10Encoder.encode(w, h, pattern, bitrate, outStats)
        return HeifContainerWriter.build(
            image,
            HeifContainerWriter.Cicp(),   // 交付口径真源：1/13/1/1（sRGB 显示域，R1 选项 A）
            bitDepth = 10,
        )
    }

    /**
     * 10bit readback 字节流 → **RGBA8 字节流**（HEIF 失败时回退 JPEG 用）。
     *
     * 位深降级用 `round(v × 255 / 1023)` 而不是 `v ushr 2`：后者在暗部系统性偏暗
     * （最大 1 LSB 偏差但方向恒定），而回退路径没有任何理由再引入一次偏差。
     */
    fun toRgba8(bytes: ByteArray, w: Int, h: Int): ByteArray {
        val rgb = unpackRgb10(bytes, w, h)
        val out = ByteArray(w * h * 4)
        for (i in rgb.indices) {
            val v = rgb[i]
            out[i * 4] = ((((v and 0x3FF) * 255) + 511) / 1023).toByte()
            out[i * 4 + 1] = (((((v ushr 10) and 0x3FF) * 255) + 511) / 1023).toByte()
            out[i * 4 + 2] = (((((v ushr 20) and 0x3FF) * 255) + 511) / 1023).toByte()
            out[i * 4 + 3] = 0xFF.toByte()   // 交付档不携带 alpha（HEVC 3 通道）
        }
        return out
    }

    /** 从 P010 图案取分量（单测/诊断用；生产链直接按位取）。 */
    fun y10(p: Int) = p and 0x3FF
    fun cb10(p: Int) = (p ushr 10) and 0x3FF
    fun cr10(p: Int) = (p ushr 20) and 0x3FF
}
