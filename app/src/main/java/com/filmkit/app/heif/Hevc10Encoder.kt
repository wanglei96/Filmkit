package com.filmkit.app.heif

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.filmkit.app.EngineBridge
import java.io.ByteArrayOutputStream

/**
 * HEVC **Main10 + P010** 单帧编码器（10bit HEIF 路线）。
 *
 * 逻辑是从真机已验证的探针 `com.filmkit.app.diag.Heif10BitProbe` **提炼**的（同一套选型规则、
 * 同一组 `MediaFormat` 键、同一种 P010 缓冲填充、同一个 drain 循环）——逐段对应关系：
 *
 * | 本文件 | 探针对应 |
 * |---|---|
 * | [selectEncoder] | `probeEncoders()` 的枚举过滤 + `runArm()` 的选型 |
 * | [buildFormat] | `encodeHeif()` 里的 `MediaFormat` 键设置 |
 * | [encode] 的 drain | `encodeHeif()` 的 EOS/drain 循环 |
 * | [fillInput] / [putSample] | 同名函数 |
 *
 * 探针保持原样（它是诊断入口，硬约束要求不动）⇒ 两份实现目前是**受控重复**；
 * 消除重复需要改探针，留待允许改探针的卡。
 *
 * ## 为什么必须挑硬件编码器（真机实测）
 * 软件臂不支持 10bit（`c2.android.hevc.encoder` 无 Main10/P010）⇒ 选不到就返回明确失败，
 * **不静默回退** 8bit（那会毁掉 10bit 路线），由调用方决定回退策略。
 *
 * ## Android 常量 ≠ CICP（易错点）
 * `KEY_COLOR_STANDARD=BIT2020(6)` / `KEY_COLOR_TRANSFER=LINEAR(1)` / `KEY_COLOR_RANGE=FULL(1)`
 * 是 **Android 枚举**；CICP 的对应值是 **primaries=9 / transfer=8 / matrix=9**。
 * 平台负责这个映射（实测 `colr/nclx` 落盘 = 9/8/9/1），但自研容器**不读**这些键 ——
 * [HeifContainerWriter.Cicp] 里必须写同样的 CICP 值，两处保持一致。
 *
 * ## P010 样本对齐
 * 10bit 样本放在 16bit word 的**高 10 位**（MSB-aligned，低 6 位为 0），小端序 ⇒
 * `put(off, ((v10 and 3) shl 6)); put(off+1, v10 ushr 2)`。该对齐方式与 DXGI_FORMAT_P010 /
 * `HAL_PIXEL_FORMAT_YCbCr_420_P010` 约定一致（真机已验证可解码出正确像素）。
 *
 * ## RGB→YCbCr 挂点（**不在本卡范围**）
 * 本类只吃打包好的 YCbCr [IntArray] 图案，**不做** RGB→YCbCr 4:2:0 转换。交付链路上这一步属于
 * 相机管线（取数点 = `tex_cap_`，OETF 之后 / LUT / 颗粒 / 锐化之后的 **sRGB 显示域** RGB10），
 * 由管线侧实现：**BT.709 矩阵**（全范围，Y' 用 BT.709 系数、Cb/Cr 除以 2(1−K_b)/2(1−K_r)），
 * 并把结果按上面的打包约定喂给 [encode]。
 * ⚠ 该矩阵**必须与容器 `colr` 的 `matrix_coefficients` 一致** —— 默认口径是
 * [HeifContainerWriter.Cicp] 的 `matrix = 1`（BT.709）。两处一起改，否则解码端会串色。
 *
 * **卡 H2 落地**：上述转换的**唯一实现** = [Heif10Pipeline.toP010]（BT.709 全范围、16 位定点、
 * 4:2:0 盒式平均），交付组装入口 = [Heif10Pipeline.toHeifFile]。改系数时必须同时看三处：
 * [Heif10Pipeline] 的 KR/KG/KB/KCB/KCR ⇄ [HeifContainerWriter.Cicp] 的 `matrix`/`fullRange`
 * ⇄ 本类的 P010 打包（[putSample] 的 MSB 对齐，与 readback 的 2_10_10_10_REV LSB-first
 * **不是**同一套约定）。三处的回归判据 = `Heif10PipelineTest` + `HeifDeliverCheckTest`。
 */
object Hevc10Encoder {

    /** 探针已验证的口径（1024×256 测试图）。**交付时按分辨率/质量目标覆盖**：码率直接决定成片质量。 */
    const val DEFAULT_BITRATE = 4_000_000

    private const val FRAME_RATE = 30
    private const val I_FRAME_INTERVAL = 1
    private const val TIMEOUT_US = 10_000L
    private const val DEADLINE_MS = 20_000L

    /**
     * 选型：声明 **Main10（或 Main10HDR10）+ P010(54)** 的**硬件** HEVC 编码器。
     * 返回 `null` = 本机没有这条路线 ⇒ 调用方回退（不静默降 8bit）。
     */
    fun selectEncoder(): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info ->
                info.isEncoder &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }
            }
            .filter { info ->
                runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC) }
                    .getOrNull()
                    ?.let { caps ->
                        caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010) &&
                            caps.profileLevels.any {
                                it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                            }
                    } == true
            }
            .firstOrNull { it.isHardwareAccelerated }

    /** HEVC Main10 + P010 的输入格式（键与探针 10bit 臂一致）。 */
    fun buildFormat(info: MediaCodecInfo, width: Int, height: Int, bitrate: Int): MediaFormat {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        // Android 枚举（不是 CICP！映射见类注释）
        fmt.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
        fmt.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_LINEAR)
        fmt.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        fmt.setInteger(MediaFormat.KEY_STRIDE, width * 2) // P010 = 2 B/样本
        fmt.setInteger(MediaFormat.KEY_SLICE_HEIGHT, height)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3)
        // 官方文档：设 profile 必须同时设 level（KEY_PROFILE 本身只是 hint）
        fmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
        fmt.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41)
        return fmt
    }

    /**
     * 编码一帧 10bit HEVC，返回 [HeifContainerWriter.build] 的直接输入。
     *
     * @param pattern 每像素打包 `y10 | (u10 shl 10) | (v10 shl 20)`，各分量 0..1023
     *   （全范围，与探针 `buildPattern()` 同一约定）。
     *   **必须是已经转换好的 YCbCr**：本类不做 RGB→YCbCr 转换
     *   （挂点见类注释「RGB→YCbCr 挂点」），且必须与容器 `colr` 的 `matrix_coefficients`
     *   一致 —— 默认口径是 **BT.709（matrix=1）**。
     * @throws IllegalStateException 没有可用的 10bit 硬件编码器 / 编码器未给 `csd-0` / 没有输出帧。
     */
    fun encode(
        width: Int,
        height: Int,
        pattern: IntArray,
        bitrate: Int = DEFAULT_BITRATE,
    ): HeifContainerWriter.Image {
        require(width > 0 && height > 0) { "尺寸非法: ${width}x$height" }
        require(pattern.size == width * height) { "pattern 大小 ${pattern.size} ≠ ${width}x$height" }
        val info = selectEncoder()
            ?: error("没有硬件 HEVC 编码器声明 Main10 + P010(54)（10bit 路线不可用，请回退）")

        var codec: MediaCodec? = null
        var hvcc: ByteArray? = null
        var csdLogged = false
        try {
            codec = MediaCodec.createByCodecName(info.name)
            codec.configure(buildFormat(info, width, height, bitrate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            // 喂 1 帧 + EOS（HEIF 是 still image）
            val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
            check(inIdx >= 0) { "10s 内拿不到输入缓冲（${info.name}）" }
            val fed = fillInput(codec, inIdx, pattern, width, height)
            check(fed > 0) { "输入缓冲填充失败（${info.name}）" }
            codec.queueInputBuffer(inIdx, 0, fed, 0, 0)
            val eosIdx = codec.dequeueInputBuffer(TIMEOUT_US)
            check(eosIdx >= 0) { "10s 内拿不到 EOS 输入缓冲（${info.name}）" }
            codec.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)

            val frame = ByteArrayOutputStream()
            val bufInfo = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + DEADLINE_MS
            while (System.currentTimeMillis() < deadline) {
                val outIdx = codec.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    hvcc = csd0(codec.outputFormat) // 实测 = 106 B 的 hvcC record
                    continue
                }
                if (outIdx < 0) continue
                val size = bufInfo.size
                val isConfig = bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val buf = codec.getOutputBuffer(outIdx)
                if (size > 0 && !isConfig && buf != null) {
                    // 帧数据原样搬进 HEIF item data：NAL 长度前缀宽度 = hvcC 自己声明的
                    // lengthSizeMinusOne（1/2/4 字节都合法，见 HevcCsd0 的文件头）。
                    buf.position(bufInfo.offset)
                    buf.limit(bufInfo.offset + size)
                    val chunk = ByteArray(size)
                    buf.get(chunk)
                    frame.write(chunk)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }

            // ── csd-0：结构检查（ISO/IEC 14496-15 §8.3.3.1）+ **无条件**诊断日志 ──────
            // 卡 FIX2：此前这里硬要求 `h[21] & 3 == 3`（lengthSizeMinusOne == 3），真机上
            // X300 + 3072×4080 恰好不是 3 ⇒ 10bit 交付被自己的断言打死两次。该字段是
            // hvcC **自己声明**的长度前缀宽度，1/2/4 字节都合法；只要 hvcC 与帧数据同源
            // 搬运，解码端就按它解析。现在只检查"它是不是一条 hvcC"，并如实搬运。
            val h = try {
                HevcCsd0.accept(hvcc) { fields -> csdLog(fields) }
            } finally {
                csdLogged = true // accept 无论成败都发过诊断（emit 在 check 之前）
            }
            val f = frame.toByteArray()
            check(f.isNotEmpty()) { "编码器没有输出帧数据（${info.name}）" }
            return HeifContainerWriter.Image(h, f, width, height)
        } finally {
            // 连 outputFormat 都没到（编码器早期失败 / 异常）时也留一条 csd 诊断（len=0）
            if (!csdLogged) csdLog(HevcCsd0.fields(null))
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    /** `photo.heif_csd` 的唯一出口（[HevcCsd0.EVENT]）。 */
    private fun csdLog(fields: String) {
        EngineBridge.nativeAppendLog(EngineBridge.LEVEL_INFO, "camera", HevcCsd0.EVENT, fields)
    }

    /** `outputFormat` 的 `csd-0` → hvcC 字节；不改动调用方 ByteBuffer 的 position。 */
    private fun csd0(format: MediaFormat): ByteArray? {
        val b = format.getByteBuffer("csd-0") ?: return null
        val d = b.duplicate()
        val out = ByteArray(d.remaining())
        d.get(out)
        return if (out.isEmpty()) null else out
    }

    /**
     * 把测试图案填进编码器输入缓冲，返回填充字节数（0 = 失败）。
     * 优先 `getInputImage()`（用编码器自己给的 rowStride/pixelStride，布局最可靠）；
     * P010 上常拿不到 Image 时，按 `KEY_STRIDE`×`KEY_SLICE_HEIGHT` 声明的 P010 布局手填（Y 平面 + 交错 UV）。
     */
    private fun fillInput(codec: MediaCodec, idx: Int, pattern: IntArray, width: Int, height: Int): Int {
        val img = runCatching { codec.getInputImage(idx) }.getOrNull()
        if (img != null && img.planes.size >= 3) {
            val y = img.planes[0]
            val u = img.planes[1]
            val v = img.planes[2]
            var end = 0
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val off = row * y.rowStride + col * y.pixelStride
                    putSample(y.buffer, off, pattern[row * width + col] and 1023)
                    if (off + 2 > end) end = off + 2
                }
            }
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val p = pattern[(row * 2) * width + col * 2]
                    val uo = row * u.rowStride + col * u.pixelStride
                    val vo = row * v.rowStride + col * v.pixelStride
                    putSample(u.buffer, uo, (p ushr 10) and 1023)
                    putSample(v.buffer, vo, (p ushr 20) and 1023)
                    if (uo + 2 > end) end = uo + 2
                    if (vo + 2 > end) end = vo + 2
                }
            }
            return end
        }
        // 兜底：手填（Y 平面 + 交错 UV，2 B/样本）
        val buf = codec.getInputBuffer(idx) ?: return 0
        buf.clear()
        val stride = width * 2
        val yBytes = stride * height
        val need = yBytes + yBytes / 2
        if (buf.capacity() < need) return 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                putSample(buf, row * stride + col * 2, pattern[row * width + col] and 1023)
            }
        }
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val p = pattern[(row * 2) * width + col * 2]
                val base = yBytes + row * stride + col * 4
                putSample(buf, base, (p ushr 10) and 1023)
                putSample(buf, base + 2, (p ushr 20) and 1023)
            }
        }
        return need
    }

    /**
     * 写一个 P010 样本：10bit 值在 16bit word 的**高 10 位**（小端序，低 6 位为 0）。
     */
    private fun putSample(buf: java.nio.ByteBuffer, off: Int, v10: Int) {
        buf.put(off, ((v10 and 0x3) shl 6).toByte())
        buf.put(off + 1, (v10 ushr 2).toByte())
    }
}
