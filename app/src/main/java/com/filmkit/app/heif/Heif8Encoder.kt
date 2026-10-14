package com.filmkit.app.heif

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer

/**
 * **8bit HEIF** 编码器（三级回退链的第二级：10bit 自研容器 → **8bit HEIF** → JPEG）。
 *
 * ## 路线选择与真机一手证据
 * 走探针第三臂 `muxer-image-heic` 的路（`Heif10BitProbe.encodeHeif(heicMime = true)` 已验证）：
 * 8bit HEVC（`COLOR_FormatYUV420Flexible`）+ `MediaMuxer(MUXER_OUTPUT_HEIF)` + 把
 * `outputFormat` 的 `KEY_MIME` 改成 HEIC 图片 mime + 补 W/H。AOSP `MPEG4Writer.cpp` 里
 * `mIsHeic` 只在 mime 命中图片 mime 时为真（否则只写 `moov`、brand 落 `mp42` —— 就是
 * muxer 走 MP4 的老路）。真机（vivo X300 / Android 16）该臂产出的是**真 HEIF 图片**：
 * `topLevel: ftyp,meta,free,mdat`、`majorBrand: heic`、`ilocItems:1`、`itemTypes: hvc1`、
 * `bitDepthLumaMinus8=0`。MIME 用探针实测的那一个（[MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC]）。
 *
 * ⚠ 该路线**没有 `colr`**（AOSP 里被 `camera_heif_gainmap()` flag 门控）。对 8bit 档无害：
 * 装进去的就是 sRGB 显示域数据，下游按默认 sRGB 解释恰好正确 ⇒ 自检口径单独一套
 *（[HeifDeliverCheck.check8Bit]），不套用 10bit 的 `colr` 判据。
 *
 * ## 色度与位深（与「HEIF ⇄ 4:4:4」互斥同一件事）
 * 8bit 档恒 **4:2:0**（[CHROMA]）。HEIF 走硬件编码器的 YUV420/P010 输入，色度不可能
 * 是 4:4:4 ⇒ 设置页的「4:4:4 色度」开关只对 JPEG 生效（见 `CameraController` 的日志
 * 明示与单测）。
 *
 * ## 与 10bit 档共用同一套色彩数学（单一真源）
 * RGB→YCbCr（BT.709 全范围、8 位定点）与 4:2:0 盒式下采样**不在本文件**：调用
 * [Heif10Pipeline.toP010]（分块调用控内存），再把 10bit 样本右移 2 位写入 8bit 平面。
 * 这样两份交付共用同一组 KR/KG/KB/KCB/KCR ⇒ 不会出现"8bit 档悄悄换了矩阵"。
 *
 * ## 只能真机跑
 * 本类依赖 `MediaCodec`/`MediaMuxer` ⇒ 单测覆盖不到；可离线验证的部分是
 * [HeifDelivery]（三级顺序与日志）与 [HeifDeliverCheck.check8Bit]（8bit 口径）。
 */
object Heif8Encoder {

    /** 8bit 档位深与色度（色度真源 = [HeifChroma.CHROMA]；硬判据里断言 HEIF 路径恒 4:2:0 8bit）。 */
    const val BIT_DEPTH = 8
    const val CHROMA = HeifChroma.CHROMA

    private const val FRAME_RATE = 30
    private const val I_FRAME_INTERVAL = 1
    private const val TIMEOUT_US = 10_000L
    private const val DEADLINE_MS = 20_000L

    /** 分块行数（偶数）：12MP 时把 toP010 的峰值内存从 ~96 MB 压到 ~4 MB。 */
    private const val CHUNK_ROWS = 128

    /**
     * 一次 8bit 编码的结果。`codec`/`mime` 回给调用方进日志（真机核对 muxer 走的是 HEIC
     * 还是 MP4 老路）；日志统一由调用方发（本类不自己打日志 ⇒ 单一真源）。
     */
    class Encoded(val bytes: ByteArray, val codec: String, val mime: String, val ms: Long)

    /**
     * 选型：声明 `COLOR_FormatYUV420Flexible` 的 HEVC 编码器，硬件加速优先。
     * 返回 `null` = 本机没有 8bit HEVC 编码器 ⇒ 调用方往下落 JPEG（不静默换档）。
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
                    ?.colorFormats
                    ?.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) == true
            }
            .sortedByDescending { it.isHardwareAccelerated }
            .firstOrNull()

    /** 8bit HEVC 的输入格式（`KEY_COLOR_FORMAT` 必须是编码器声明支持的 8bit YUV420）。 */
    fun buildFormat(width: Int, height: Int, bitrate: Int): MediaFormat {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        // 交付域 = sRGB 显示域（全范围）。⚠ `MediaFormat` **没有** `COLOR_TRANSFER_SRGB` 常量
        // ⇒ 只声明 BT.709 primaries + 全范围，transfer 走平台默认。这些键只作为编码器 hint：
        // 8bit 档产物（平台 muxer）**不写 colr**（AOSP 里被 flag 门控，真机探针实测），落盘
        // 元数据不受它们影响 —— 下游按默认 sRGB 解释恰好正确（见类注释与报告 §3）。
        fmt.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        fmt.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        fmt.setInteger(MediaFormat.KEY_STRIDE, width)
        fmt.setInteger(MediaFormat.KEY_SLICE_HEIGHT, height)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3)
        return fmt
    }

    /**
     * RGBA8 → 8bit HEVC → `MediaMuxer(MUXER_OUTPUT_HEIF)` → **完整 HEIF 文件字节**。
     *
     * 输入必须是 8bit RGBA（= [Heif10Pipeline.toRgba8] 的输出，即 10bit 失败时已经算好的
     * 那份降位结果），**不重渲染、不重新 readback**。
     *
     * @param outFile 临时文件（muxer 只能写文件）；本函数读完即删。
     * @throws IllegalStateException 没有 8bit HEVC 编码器 / 编码失败 / 产物为空
     *   —— 调用方**必须**往下落 JPEG（绝不交付可疑文件）。
     */
    fun encodeToBytes(
        rgba8: ByteArray,
        width: Int,
        height: Int,
        outFile: File,
        bitrate: Int = Heif10Pipeline.defaultBitrate(width, height),
    ): Encoded {
        require(width > 0 && height > 0) { "尺寸非法: ${width}x$height" }
        require(width % 2 == 0 && height % 2 == 0) { "4:2:0 要求偶数尺寸，实际 ${width}x$height" }
        require(rgba8.size == width * height * 4) { "RGBA8 长度 ${rgba8.size} ≠ ${width}x$height×4" }
        // MediaMuxer(MUXER_OUTPUT_HEIF) 与 MIMETYPE_IMAGE_ANDROID_HEIC 都是 API 28+；
        // minSdk 26/27 上直接给明确失败（否则是 NoSuchFieldError 这种看不懂的错），
        // 由 HeifDelivery 统一落 JPEG。
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            "MediaMuxer(MUXER_OUTPUT_HEIF) 需要 API 28+（本机 ${Build.VERSION.SDK_INT}）⇒ 8bit HEIF 不可用"
        }
        val info = selectEncoder()
            ?: error("没有 HEVC 编码器声明 8bit YUV420Flexible（8bit HEIF 不可用，请回退）")

        val t0 = System.currentTimeMillis()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var mimeUsed = "?"
        try {
            codec = MediaCodec.createByCodecName(info.name)
            codec.configure(buildFormat(width, height, bitrate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            // 喂 1 帧 + EOS（HEIF 是 still image）
            val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
            check(inIdx >= 0) { "10s 内拿不到输入缓冲（${info.name}）" }
            val fed = fillInput8(codec, inIdx, rgba8, width, height)
            check(fed > 0) { "输入缓冲填充失败（${info.name}）" }
            codec.queueInputBuffer(inIdx, 0, fed, 0, 0)
            val eosIdx = codec.dequeueInputBuffer(TIMEOUT_US)
            check(eosIdx >= 0) { "10s 内拿不到 EOS 输入缓冲（${info.name}）" }
            codec.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)

            outFile.delete()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF)
            val bufInfo = MediaCodec.BufferInfo()
            var track = -1
            var muxStarted = false
            var samples = 0
            val deadline = System.currentTimeMillis() + DEADLINE_MS
            while (System.currentTimeMillis() < deadline) {
                val outIdx = codec.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // AOSP `MPEG4Writer.cpp` 的 mIsHeic 只在 mime 命中图片 mime 时为真 ⇒
                    // 不改 mime 就只会写 moov（MP4），相册不认。W/H 必须补（HeifEncoder.java 同款）。
                    val of = codec.outputFormat
                    of.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC)
                    of.setInteger(MediaFormat.KEY_WIDTH, width)
                    of.setInteger(MediaFormat.KEY_HEIGHT, height)
                    mimeUsed = of.getString(MediaFormat.KEY_MIME) ?: "?"
                    track = muxer.addTrack(of)
                    muxer.start()
                    muxStarted = true
                    continue
                }
                if (outIdx < 0) continue
                val size = bufInfo.size
                val isConfig = bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val buf = codec.getOutputBuffer(outIdx)
                if (size > 0 && !isConfig && buf != null && muxStarted && track >= 0) {
                    buf.position(bufInfo.offset)
                    buf.limit(bufInfo.offset + size)
                    muxer.writeSampleData(track, buf, bufInfo) // csd 由 muxer 从 outputFormat 取
                    samples++
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            check(muxStarted && samples > 0) { "编码器没有输出样本（${info.name}，samples=$samples）" }
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }

        val bytes = runCatching { outFile.takeIf { it.length() > 0 }?.readBytes() }.getOrNull()
        runCatching { outFile.delete() }
        check(bytes != null && bytes.isNotEmpty()) { "8bit HEIF 产物为空（${info.name}）" }
        return Encoded(bytes, info.name, mimeUsed, System.currentTimeMillis() - t0)
    }

    /**
     * 把 RGBA8 填进编码器输入缓冲，返回填充字节数（0 = 失败）。
     *
     * 优先 `getInputImage()`（用编码器给的 rowStride/pixelStride，布局最可靠）；
     * 拿不到 Image 时按 `KEY_STRIDE`×`KEY_SLICE_HEIGHT` 声明的 YUV420 布局手填
     *（Y 平面 + 交错 UV）。
     *
     * ⚠ 逐 [CHUNK_ROWS] 行分块：每块仍走 [Heif10Pipeline.toP010]（同一套系数与 4:2:0
     * 下采样），块起点是偶行且块高为偶数 ⇒ 2×2 色度分组与整幅一致。
     */
    private fun fillInput8(codec: MediaCodec, idx: Int, rgba8: ByteArray, width: Int, height: Int): Int {
        val img = runCatching { codec.getInputImage(idx) }.getOrNull()
        if (img != null && img.planes.size >= 3) {
            val y = img.planes[0]
            val u = img.planes[1]
            val v = img.planes[2]
            var end = 0
            var row0 = 0
            while (row0 < height) {
                val rows = minOf(CHUNK_ROWS, height - row0)
                val p = toP010Chunk(rgba8, width, row0, rows)
                for (r in 0 until rows) {
                    for (c in 0 until width) {
                        val off = (row0 + r) * y.rowStride + c * y.pixelStride
                        y.buffer.put(off, (Heif10Pipeline.y10(p[r * width + c]) ushr 2).toByte())
                        if (off + 1 > end) end = off + 1
                    }
                }
                // 色度：toP010 写在块左上像素 ⇒ 每 2×2 一个样本
                for (r in 0 until rows step 2) {
                    for (c in 0 until width step 2) {
                        val px = p[r * width + c]
                        val uo = (row0 + r) / 2 * u.rowStride + c / 2 * u.pixelStride
                        val vo = (row0 + r) / 2 * v.rowStride + c / 2 * v.pixelStride
                        u.buffer.put(uo, (Heif10Pipeline.cb10(px) ushr 2).toByte())
                        v.buffer.put(vo, (Heif10Pipeline.cr10(px) ushr 2).toByte())
                        if (uo + 1 > end) end = uo + 1
                        if (vo + 1 > end) end = vo + 1
                    }
                }
                row0 += rows
            }
            return end
        }
        // 兜底：手填（Y 平面 + 交错 UV，1 B/样本）
        val buf = codec.getInputBuffer(idx) ?: return 0
        buf.clear()
        val stride = width
        val yBytes = stride * height
        val need = yBytes + yBytes / 2
        if (buf.capacity() < need) return 0
        var row0 = 0
        while (row0 < height) {
            val rows = minOf(CHUNK_ROWS, height - row0)
            val p = toP010Chunk(rgba8, width, row0, rows)
            for (r in 0 until rows) {
                for (c in 0 until width) {
                    buf.put((row0 + r) * stride + c, (Heif10Pipeline.y10(p[r * width + c]) ushr 2).toByte())
                }
            }
            for (r in 0 until rows step 2) {
                for (c in 0 until width step 2) {
                    val px = p[r * width + c]
                    val base = yBytes + (row0 + r) / 2 * stride + c
                    buf.put(base, (Heif10Pipeline.cb10(px) ushr 2).toByte())
                    buf.put(base + 1, (Heif10Pipeline.cr10(px) ushr 2).toByte())
                }
            }
            row0 += rows
        }
        return need
    }

    /**
     * 取 [row0, row0+rows) 行的 RGB10 图案（P010 约定，供上面两个写入分支共用）。
     * RGBA8 → 10bit：`(v shl 2) or (v ushr 6)`（= round(v×1023/255)，端点 0/255 精确）。
     */
    private fun toP010Chunk(rgba8: ByteArray, width: Int, row0: Int, rows: Int): IntArray {
        val rgb10 = IntArray(width * rows)
        for (r in 0 until rows) {
            var o = ((row0 + r) * width) * 4
            var i = r * width
            for (c in 0 until width) {
                val rr = rgba8[o].toInt() and 0xFF
                val gg = rgba8[o + 1].toInt() and 0xFF
                val bb = rgba8[o + 2].toInt() and 0xFF
                rgb10[i] = ((rr shl 2) or (rr ushr 6)) or
                    (((gg shl 2) or (gg ushr 6)) shl 10) or
                    (((bb shl 2) or (bb ushr 6)) shl 20)
                o += 4
                i++
            }
        }
        return Heif10Pipeline.toP010(rgb10, width, rows)
    }
}
