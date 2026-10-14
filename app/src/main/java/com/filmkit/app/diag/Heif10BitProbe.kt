package com.filmkit.app.diag

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * 10bit HEIF 交付链 · **真机能力探针**（实现卡 A0）。
 *
 * 方案定义：`docs/rec2020-heif-design.md` §4.1 —— 三个只有真机能答的问题：
 *   1. X300 上是否有可用于 still image 的 **HEVC Main10** 编码器、是否支持 **P010** 输入；
 *   2. `MediaFormat` 的 `color-standard`/`color-transfer`/`color-range` 是否**真的写进**产出的
 *      HEIF `colr/nclx` box；
 *   3. 产出的 10bit HEIF 是否编码成功、文件结构与位深（`bit_depth_luma_minus8`）如何。
 *
 * 探针口径（**非交付口径**，只求"最小可用 + 结论可靠"）：
 *   - 分辨率 1024×256（2 的幂，满足任意对齐；水平 1024 级灰阶需要宽度 ≥ 1024）；
 *   - 1 帧（HEIF = still image；多帧会变成多 item 容器，不是本 gate 的目标）；
 *   - 码率 4 Mbps / 帧率 30 / I 帧间隔 1s —— 只保证编码器不因参数过小而拒绝；
 *   - 不依赖拍照、不依赖 native 渲染、不碰 `core/engine/`。
 *
 * 与交付链的关系：本文件**只诊断**，不接入拍照路径；期 A（方案 §4.3）拿到本探针结论后才动
 * `FrameRenderer.cpp` 的降位点与 `CameraController.kt` 的编码路径。
 *
 * ── Android 常量核实（铁律：键名/取值不许凭记忆）────────────────────────────
 * 来源：本机 `javap -constants -classpath \
 *   /Users/wl/Library/Android/sdk/platforms/android-36/android.jar <类>`（2026-10-14 实测输出，
 *   逐条抄录；SDK 为 `platforms/android-36`）。仅列本文件用到的：
 *
 *   MediaFormat（android.media.MediaFormat）
 *     KEY_PROFILE          = "profile"            (API 21)
 *     KEY_LEVEL            = "level"              (API 23)
 *     KEY_COLOR_FORMAT     = "color-format"
 *     KEY_COLOR_STANDARD   = "color-standard"     (API 24)
 *     KEY_COLOR_TRANSFER   = "color-transfer"     (API 24)
 *     KEY_COLOR_RANGE      = "color-range"        (API 24)
 *     KEY_STRIDE           = "stride"             (API 23, public)
 *     KEY_SLICE_HEIGHT     = "slice-height"       (API 23, public)
 *     KEY_I_FRAME_INTERVAL = "i-frame-interval"
 *     KEY_BIT_RATE         = "bitrate"   KEY_FRAME_RATE = "frame-rate"
 *     MIMETYPE_VIDEO_HEVC  = "video/hevc"
 *     COLOR_STANDARD_BT709 = 1 · COLOR_STANDARD_BT601_PAL = 2 · COLOR_STANDARD_BT601_NTSC = 4
 *     COLOR_STANDARD_BT2020 = 6
 *     COLOR_TRANSFER_LINEAR = 1 · COLOR_TRANSFER_SDR_VIDEO = 3
 *     COLOR_TRANSFER_ST2084 = 6 (PQ) · COLOR_TRANSFER_HLG = 7
 *     COLOR_RANGE_FULL = 1 · COLOR_RANGE_LIMITED = 2
 *
 *   MediaCodecInfo.CodecCapabilities
 *     COLOR_FormatYUVP010            = 54            (0x36, API 33)
 *     COLOR_FormatYUVP210            = 60            (0x3C)
 *     COLOR_FormatYUV420Flexible     = 2135033992    (0x7F420888)
 *     COLOR_FormatYUV420SemiPlanar   = 21
 *     COLOR_Format32bitABGR2101010   = 2130750114    (0x7F00AAA2)
 *     COLOR_FormatSurface            = 2130708361    (0x7F000789)
 *     FEATURE_HdrEditing             = "hdr-editing" · FEATURE_HlgEditing = "hlg-editing"
 *
 *   MediaCodecInfo.CodecProfileLevel
 *     HEVCProfileMain = 1 · HEVCProfileMain10 = 2 · HEVCProfileMainStill = 4
 *     HEVCProfileMain10HDR10 = 4096 · HEVCProfileMain10HDR10Plus = 8192
 *     HEVCMainTierLevel4 = 1024 · HEVCMainTierLevel41 = 4096 · HEVCMainTierLevel5 = 16384
 *
 *   MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF = 3   (API 28)
 *
 *   ⚠ **关键映射警告（本探针 Q2 的核心）**：Android 的 `COLOR_STANDARD_*` /
 *   `COLOR_TRANSFER_*` **不是 CICP 值**，中间隔着一层平台映射：
 *     Android COLOR_STANDARD_BT2020 = 6   ↔  HEIF/CICP colour_primaries BT.2020 = 9
 *     Android COLOR_TRANSFER_LINEAR = 1   ↔  HEIF/CICP transfer_characteristics linear = 8
 *     （CICP 值表一手来源：libheif `libheif/api/libheif/heif_color.h:140-193`）
 *   所以"设了 `color-standard=6` ⇒ 落盘 `primaries=9`"**必须实测**，不能推导。
 *
 *   ⚠ **P010 样本对齐**：P010 的 10bit 样本放在 16bit word 的**高 10 位**（MSB-aligned，
 *   低 6 位为 0），这是 DXGI_FORMAT_P010 与 AOSP `HAL_PIXEL_FORMAT_YCbCr_420_P010` 的约定。
 *   本探针按此写入（`value10 shl 6`）。**该对齐方式未在本机验证**，列入报告"未验证项"。
 *
 * 日志事件名（JSONL，走项目既有 Logger → `EngineBridge.nativeAppendLog`）：
 *   `heif10.probe`（总流程/每步结果）· `heif10.encoder`（编码器能力）· `heif10.nclx`（回读容器）
 */
object Heif10BitProbe {

    /** 探针口径：测试图尺寸（非交付口径）。1024 列 = 水平 1024 级灰阶。 */
    private const val W = 1024
    private const val H = 256

    /** 探针口径：码率/帧率/关键帧间隔（非交付口径，仅保证编码器不拒绝过小参数）。 */
    private const val BITRATE = 4_000_000
    private const val FRAME_RATE = 30
    private const val I_FRAME_INTERVAL = 1

    private const val MODULE = "heif10"

    // ══ 入口 ══════════════════════════════════════════════════════════════

    /**
     * 跑完整探针，返回结果目录绝对路径（用户可在此取 `.heic`）。
     * **不抛异常**：任何一步失败都记 `heif10.probe` 后继续下一步（每项独立报告）。
     */
    fun run(context: Context): String {
        val outDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "heif10")
        outDir.mkdirs()
        val stamp = System.currentTimeMillis()
        log(EngineBridge.LEVEL_INFO, "heif10.probe", kv(
            "phase" to "start",
            "sdk" to Build.VERSION.SDK_INT,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "release" to Build.VERSION.RELEASE,
            "size" to "${W}x$H",
            "frames" to 1,
            "outDir" to outDir.absolutePath,
        ))

        // Q1：枚举 HEVC 编码器能力
        val encoders = try {
            probeEncoders()
        } catch (t: Throwable) {
            log(EngineBridge.LEVEL_ERROR, "heif10.probe", kv(
                "phase" to "enumEncoders", "ok" to false, "err" to errText(t),
            ))
            emptyList()
        }

        // Q2/Q3：编码 → 落盘 → 回读。10bit 为主路径，8bit flexible 为对照路径
        // （对照臂的作用：即使机型无 P010，也能回答"color-* 键是否进 colr box"）。
        // 第三臂（路线定案卡追加）：编码参数与 main10-p010 **完全相同**，唯一区别是
        // addTrack 前把 outputFormat 的 KEY_MIME 改成 "image/heic" —— 见 encodeHeif 里的说明。
        val arms = listOf(
            runArm(outDir, stamp, "main10-p010", preferTenBit = true, encoders = encoders),
            runArm(outDir, stamp, "ctrl-8bit", preferTenBit = false, encoders = encoders),
            runArm(
                outDir, stamp, "muxer-image-heic", preferTenBit = true,
                encoders = encoders, heicMime = true,
            ),
        )
        val summary = arms.joinToString(" ｜ ") { it ?: "(未运行)" }
        log(EngineBridge.LEVEL_INFO, "heif10.probe", kv(
            "phase" to "end", "ok" to true, "outDir" to outDir.absolutePath,
            "summary" to summary,
        ))
        EngineBridge.nativeFlushLogs()
        // 返回值直接给用户看：目录 + 两条臂的一句话结论（点完即见，不必翻日志）
        return "${outDir.absolutePath}\n$summary"
    }

    // ══ 骨架（实现见下）══════════════════════════════════════════════════

    private fun log(level: Int, event: String, json: String) {
        EngineBridge.nativeAppendLog(level, MODULE, event, json)
    }

    private fun errText(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"

    // ══ Q1：枚举 HEVC 编码器 ══════════════════════════════════════════════

    /** 每个 HEVC 编码器一条 `heif10.encoder` 日志；返回列表供后续选型。 */
    private fun probeEncoders(): List<MediaCodecInfo> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } }
        log(EngineBridge.LEVEL_INFO, "heif10.probe", kv(
            "phase" to "enumEncoders", "ok" to true, "count" to infos.size,
        ))
        for (info in infos) {
            val caps = try {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            } catch (t: Throwable) {
                log(EngineBridge.LEVEL_WARN, "heif10.encoder", kv(
                    "name" to info.name, "ok" to false, "err" to errText(t),
                ))
                continue
            }
            val vc = caps.videoCapabilities
            val profiles = caps.profileLevels.map { p -> "${profileName(p.profile)}/${levelName(p.level)}" }
            val hasMain10 = caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
            val hasMain10Hdr10 =
                caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 }
            val hasMain10Hdr10Plus =
                caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus }
            log(EngineBridge.LEVEL_INFO, "heif10.encoder", kv(
                "name" to info.name,
                "hw" to info.isHardwareAccelerated,
                "sw" to info.isSoftwareOnly,
                "vendor" to info.isVendor,
                "alias" to info.isAlias,
                "profiles" to profiles.joinToString(","),
                "main10" to hasMain10,
                "main10HDR10" to hasMain10Hdr10,
                "main10HDR10Plus" to hasMain10Hdr10Plus,
                "colorFormats" to caps.colorFormats.joinToString(",") { colorFormatName(it) },
                "p010" to caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010),
                "p210" to caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP210),
                "rgba1010102" to caps.colorFormats
                    .contains(MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR2101010),
                "yuv420Flex" to caps.colorFormats
                    .contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible),
                "hdrEditing" to caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing),
                "hlgEditing" to caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_HlgEditing),
                "widths" to vc?.supportedWidths?.toString(),
                "heights" to vc?.supportedHeights?.toString(),
                "align" to (vc?.let { "${it.widthAlignment}x${it.heightAlignment}" }),
                "sizeOk" to (vc?.isSizeSupported(W, H)),
                "bitrate" to vc?.bitrateRange?.toString(),
            ))
        }
        return infos
    }

    /** 取一个可读的 profile 名（值来自上表；未列出时只报数值）。 */
    private fun profileName(p: Int): String = when (p) {
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "MainStill"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "Main10HDR10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "Main10HDR10Plus"
        else -> "p$p"
    }

    private fun levelName(l: Int): String = when (l) {
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4 -> "L4"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41 -> "L4.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5 -> "L5"
        else -> "l$l"
    }

    /** 已知色彩格式 → 符号名；未列出时给十六进制值（便于人工比对 SDK）。 */
    private fun colorFormatName(c: Int): String = when (c) {
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 -> "P010(54)"
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP210 -> "P210(60)"
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> "YUV420Flexible(0x7F420888)"
        21 -> "YUV420SemiPlanar(21)" // COLOR_FormatYUV420SemiPlanar（逐个枚举会触发 deprecation 警告）
        19 -> "YUV420Planar(19)" // COLOR_FormatYUV420Planar
        MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR2101010 -> "ABGR2101010(0x7F00AAA2)"
        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface -> "Surface(0x7F000789)"
        else -> "0x%X".format(c)
    }

    // ══ 测试图案（CPU 生成，已知值）═══════════════════════════════════════

    /**
     * 每像素打包 `y10 | (u10 shl 10) | (v10 shl 20)`（均为 0..1023 的 BT.2020 NCL 全范围值）。
     * 上半 = 水平 1024 级灰阶（第 x 列 = 码值 x）；下半 = 8 个饱和/中性色块。
     */
    private fun buildPattern(): IntArray {
        val px = IntArray(W * H)
        val neutral = 512
        val half = H / 2
        for (y in 0 until half) {
            for (x in 0 until W) px[y * W + x] = x or (neutral shl 10) or (neutral shl 20)
        }
        // 8 块：饱和红/绿/蓝/黄/青/品红 + 白 + 黑（线性 BT.2020 域 RGB，全范围）
        val blocks = arrayOf(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f),
            floatArrayOf(1f, 1f, 0f), floatArrayOf(0f, 1f, 1f), floatArrayOf(1f, 0f, 1f),
            floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 0f, 0f),
        )
        val bw = W / blocks.size
        for (y in half until H) {
            for (x in 0 until W) {
                val c = blocks[(x / bw).coerceAtMost(blocks.size - 1)]
                px[y * W + x] = rgb2020ToYuv10(c[0], c[1], c[2])
            }
        }
        return px
    }

    /**
     * 线性 BT.2020 RGB → BT.2020 NCL 全范围 10bit YCbCr。
     * 系数：Y' = 0.2627R + 0.6780G + 0.0593B（BT.2020 K_r/K_g/K_b）；
     *       Cb' = (B'−Y') / 2(1−K_b) = /1.8814；Cr' = (R'−Y') / 2(1−K_r) = /1.4746。
     */
    private fun rgb2020ToYuv10(r: Float, g: Float, b: Float): Int {
        val y = 0.2627f * r + 0.6780f * g + 0.0593f * b
        val cb = (b - y) / 1.8814f
        val cr = (r - y) / 1.4746f
        val y10 = (y * 1023f).roundToInt().coerceIn(0, 1023)
        val u10 = ((cb + 0.5f) * 1023f).roundToInt().coerceIn(0, 1023)
        val v10 = ((cr + 0.5f) * 1023f).roundToInt().coerceIn(0, 1023)
        return y10 or (u10 shl 10) or (v10 shl 20)
    }

    // ══ Q2/Q3：编码 → 落盘 → 回读 ═════════════════════════════════════════

    /**
     * 跑一条臂（10bit 主路径 / 8bit 对照路径 / 官方 muxer image-heic 臂）。任一步失败只记日志、不抛。
     * 两条臂都跑的理由：机型若没有 P010，Q2（`color-*` → `colr/nclx`）仍能被 8bit 臂回答，
     * 且两臂的 nclx 差异本身就是"位深是否影响标签"的一手证据。
     *
     * @param heicMime 为 true 时只改 addTrack 的 MIME（见 [encodeHeif]），其余参数与 10bit 臂一致。
     */
    private fun runArm(
        outDir: File,
        stamp: Long,
        tag: String,
        preferTenBit: Boolean,
        encoders: List<MediaCodecInfo>,
        heicMime: Boolean = false,
    ): String? {
        val colorFormat =
            if (preferTenBit) MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
            else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        // 选型：支持目标色彩格式的编码器，硬件加速优先
        val candidates = encoders.filter { info ->
            try {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).colorFormats.contains(colorFormat)
            } catch (_: Throwable) {
                false
            }
        }.sortedByDescending { it.isHardwareAccelerated }
        val info = candidates.firstOrNull()
        if (info == null) {
            log(EngineBridge.LEVEL_WARN, "heif10.probe", kv(
                "phase" to "arm", "arm" to tag, "ok" to false,
                "reason" to "no HEVC encoder declares ${colorFormatName(colorFormat)}",
                "candidates" to encoders.size,
            ))
            return "$tag(无编码器声明 ${colorFormatName(colorFormat)})"
        }
        val out = File(outDir, "probe-$tag-$stamp.heic")
        val pattern = buildPattern()
        log(EngineBridge.LEVEL_INFO, "heif10.probe", kv(
            "phase" to "arm", "arm" to tag, "ok" to true, "codec" to info.name,
            "tenBit" to preferTenBit, "colorFormat" to colorFormatName(colorFormat),
            "heicMime" to heicMime,
            "file" to out.absolutePath,
        ))
        val ok = encodeHeif(out, info, preferTenBit, colorFormat, pattern, heicMime)
        if (!ok) {
            log(EngineBridge.LEVEL_ERROR, "heif10.probe", kv(
                "phase" to "arm", "arm" to tag, "ok" to false, "reason" to "encode failed",
            ))
            return "$tag(${info.name} 编码失败，详见 heif10.probe)"
        }
        val f = parseHeif(out, tag) ?: return "$tag(编码成功但容器解析失败)"
        return "$tag(${info.name}): colr=${f.colrType} " +
            "primaries=${f.primaries}/transfer=${f.transfer}/matrix=${f.matrix}/full=${f.fullRange} " +
            "bitDepthLumaMinus8=${f.bitDepthLumaMinus8} hvcC后=${f.hvcCSeen}"
    }

    /** 编码 1 帧并封进 HEIF。返回是否产出非空文件。全程 try/catch：异常文本原样进日志。 */
    private fun encodeHeif(
        out: File,
        info: MediaCodecInfo,
        tenBit: Boolean,
        colorFormat: Int,
        pattern: IntArray,
        heicMime: Boolean = false,
    ): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, W, H)
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            // 显式色彩标签（方案 §4.3 主线）：BT.2020 线性 + 全范围。
            // 注意 Android 枚举值是 6/1/1，不是 CICP 的 9/8/1 —— 映射由编码器负责（见文件头警告）。
            fmt.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            fmt.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_LINEAR)
            fmt.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
            // 明确 buffer 布局（KEY_STRIDE/KEY_SLICE_HEIGHT 是公开常量，见文件头）
            fmt.setInteger(MediaFormat.KEY_STRIDE, W * (if (tenBit) 2 else 1))
            fmt.setInteger(MediaFormat.KEY_SLICE_HEIGHT, H)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, W * H * 3)
            if (tenBit) {
                // 官方文档：设 profile 必须同时设 level（KEY_PROFILE 本身只是 hint）
                fmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                fmt.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41)
            }
            val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            val supported = try {
                caps.isFormatSupported(fmt)
            } catch (t: Throwable) {
                log(EngineBridge.LEVEL_WARN, "heif10.probe", kv(
                    "phase" to "isFormatSupported", "ok" to false, "err" to errText(t),
                ))
                null
            }
            log(EngineBridge.LEVEL_INFO, "heif10.probe", kv(
                "phase" to "configure", "codec" to info.name, "tenBit" to tenBit,
                "isFormatSupported" to supported, "format" to fmt.toString(),
            ))

            // 方案 §4.3 写的是 createEncoderByType("video/hevc")；探针要测"哪个编码器真能做"，
            // 故按能力选型后 createByCodecName。若 X300 默认 HEVC 编码器即所选，两者等价
            //（codecName 已记入日志供核对）。
            codec = MediaCodec.createByCodecName(info.name)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            out.delete()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF)

            val bufInfo = MediaCodec.BufferInfo()
            var track = -1
            var muxStarted = false
            var fedBytes = 0
            var outSamples = 0
            var csdBytes = 0

            // 喂 1 帧（探针口径：HEIF 是 still image）+ EOS
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx < 0) {
                log(EngineBridge.LEVEL_ERROR, "heif10.probe", kv(
                    "phase" to "dequeueInputBuffer", "ok" to false, "reason" to "no input buffer in 10s",
                ))
                return false
            }
            fedBytes = fillInput(codec, inIdx, tenBit, pattern)
            if (fedBytes <= 0) {
                log(EngineBridge.LEVEL_ERROR, "heif10.probe", kv(
                    "phase" to "fillInput", "ok" to false, "reason" to "filled 0 bytes",
                ))
                return false
            }
            codec.queueInputBuffer(inIdx, 0, fedBytes, 0, 0)
            val eosIdx = codec.dequeueInputBuffer(10_000)
            if (eosIdx >= 0) {
                codec.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } else {
                log(EngineBridge.LEVEL_WARN, "heif10.probe", kv(
                    "phase" to "eos", "ok" to false, "reason" to "no buffer for EOS",
                ))
            }

            var spin = 0
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                spin++
                val outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    if (heicMime) {
                        // 路线定案卡一手事实（AOSP）：MediaMuxer(MUXER_OUTPUT_HEIF) 只设 kKeyFileType=3，
                        // 真正决定写 HEIF 还是 MP4 的是 MPEG4Writer.cpp 里的
                        //   mIsHeic = !strcasecmp(mime, "image/heic")
                        // 我们之前 addTrack 传的是 video/hevc ⇒ 写 moov、不写 meta、brand 落 mp42（与真机一致）。
                        // HeifEncoder.java 的唯一钥匙就是这 3 行：改 MIME 成 "image/heic" 并补 W/H。
                        of.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC) // "image/heic"
                        of.setInteger(MediaFormat.KEY_WIDTH, W)
                        of.setInteger(MediaFormat.KEY_HEIGHT, H)
                        log(EngineBridge.LEVEL_INFO, "heif10.probe", kv(
                            "phase" to "heicMime", "ok" to true,
                            "mime" to MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC,
                            "format" to of.toString(),
                        ))
                    }
                    try {
                        track = muxer.addTrack(of)
                        muxer.start()
                        muxStarted = true
                    } catch (t: Throwable) {
                        if (!heicMime) throw t // 已有臂保持原有异常路径与日志不变
                        // 异常文本必须进日志（铁律），且只废掉这一条臂
                        log(EngineBridge.LEVEL_ERROR, "heif10.probe", kv(
                            "phase" to "heicMime", "ok" to false,
                            "mime" to MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC,
                            "err" to errText(t),
                        ))
                        return false
                    }
                    log(EngineBridge.LEVEL_INFO, "heif10.probe", kv(
                        "phase" to "outputFormat", "track" to track, "format" to of.toString(),
                    ))
                    continue
                }
                if (outIdx < 0) continue
                val buf = codec.getOutputBuffer(outIdx)
                var size = bufInfo.size
                if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    csdBytes += size
                    size = 0 // csd 由 muxer 从 outputFormat 取，不进 mdat
                }
                if (size > 0 && muxStarted && buf != null && track >= 0) {
                    buf.position(bufInfo.offset)
                    buf.limit(bufInfo.offset + size)
                    muxer.writeSampleData(track, buf, bufInfo)
                    outSamples++
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            log(EngineBridge.LEVEL_INFO, "heif10.probe", kv(
                "phase" to "encode", "ok" to true, "inBytes" to fedBytes,
                "outSamples" to outSamples, "csdBytes" to csdBytes, "spin" to spin,
            ))
        } catch (t: Throwable) {
            // 铁律：MediaCodec 抛的异常文本必须进日志，不吞
            log(EngineBridge.LEVEL_ERROR, "heif10.probe", kv(
                "phase" to "encode", "ok" to false, "codec" to info.name,
                "tenBit" to tenBit, "err" to errText(t),
                "stack" to t.stackTrace.take(6).joinToString(" <- ") { "${it.fileName}:${it.lineNumber}" },
            ))
            return false
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
        val size = out.length()
        log(EngineBridge.LEVEL_INFO, "heif10.probe", kv(
            "phase" to "file", "path" to out.absolutePath, "bytes" to size,
            "ok" to (size > 0),
        ))
        return size > 0
    }

    /**
     * 把测试图案填进编码器输入缓冲，返回填充字节数（0 = 失败）。
     * 优先 `getInputImage()`（用编码器自己给的 rowStride/pixelStride，布局最可靠）；
     * 拿不到 Image（P010 上很常见）时按 KEY_STRIDE/KEY_SLICE_HEIGHT 声明的 P010/YUV420 布局手填。
     */
    private fun fillInput(codec: MediaCodec, idx: Int, tenBit: Boolean, pattern: IntArray): Int {
        val img = try {
            codec.getInputImage(idx)
        } catch (t: Throwable) {
            log(EngineBridge.LEVEL_WARN, "heif10.probe", kv(
                "phase" to "getInputImage", "ok" to false, "err" to errText(t),
            ))
            null
        }
        if (img != null && img.planes.size >= 3) {
            val planes = img.planes
            val y = planes[0]
            val u = planes[1]
            val v = planes[2]
            log(EngineBridge.LEVEL_INFO, "heif10.probe", kv(
                "phase" to "inputImage", "ok" to true, "format" to img.format,
                "yRow" to y.rowStride, "yPx" to y.pixelStride,
                "uRow" to u.rowStride, "uPx" to u.pixelStride,
                "vRow" to v.rowStride, "vPx" to v.pixelStride,
            ))
            val sample = if (tenBit) 2 else 1
            var end = 0
            for (row in 0 until H) {
                for (col in 0 until W) {
                    val off = row * y.rowStride + col * y.pixelStride
                    putSample(y.buffer, off, pattern[row * W + col] and 1023, tenBit)
                    if (off + sample > end) end = off + sample
                }
            }
            for (row in 0 until H / 2) {
                for (col in 0 until W / 2) {
                    val p = pattern[(row * 2) * W + col * 2]
                    val uo = row * u.rowStride + col * u.pixelStride
                    val vo = row * v.rowStride + col * v.pixelStride
                    putSample(u.buffer, uo, (p ushr 10) and 1023, tenBit)
                    putSample(v.buffer, vo, (p ushr 20) and 1023, tenBit)
                    if (uo + sample > end) end = uo + sample
                    if (vo + sample > end) end = vo + sample
                }
            }
            return end
        }
        if (img != null) {
            log(EngineBridge.LEVEL_WARN, "heif10.probe", kv(
                "phase" to "inputImage", "ok" to false,
                "reason" to "image has ${img.planes.size} planes, expected 3 (fallback to raw buffer)",
            ))
        }
        // 兜底：手填（布局 = KEY_STRIDE×KEY_SLICE_HEIGHT，Y 平面后接交错 UV 平面）
        val buf = codec.getInputBuffer(idx) ?: return 0
        buf.clear()
        val px = if (tenBit) 2 else 1
        val stride = W * px
        val yBytes = stride * H
        val need = yBytes + yBytes / 2
        if (buf.capacity() < need) {
            log(EngineBridge.LEVEL_ERROR, "heif10.probe", kv(
                "phase" to "inputBuffer", "ok" to false, "capacity" to buf.capacity(), "need" to need,
            ))
            return 0
        }
        for (row in 0 until H) {
            for (col in 0 until W) {
                putSample(buf, row * stride + col * px, pattern[row * W + col] and 1023, tenBit)
            }
        }
        for (row in 0 until H / 2) {
            for (col in 0 until W / 2) {
                val p = pattern[(row * 2) * W + col * 2]
                val base = yBytes + row * stride + col * px * 2
                putSample(buf, base, (p ushr 10) and 1023, tenBit)
                putSample(buf, base + px, (p ushr 20) and 1023, tenBit)
            }
        }
        log(EngineBridge.LEVEL_INFO, "heif10.probe", kv(
            "phase" to "inputBuffer", "ok" to true, "capacity" to buf.capacity(),
            "need" to need, "stride" to stride, "sliceHeight" to H, "tenBit" to tenBit,
        ))
        return need
    }

    /**
     * 写一个样本。10bit → P010（MSB-aligned：10bit 值在 16bit word 的高 10 位，小端序）；
     * 8bit → 直接写高 8 位（10bit 值右移 2）。
     */
    private fun putSample(buf: ByteBuffer, off: Int, v10: Int, tenBit: Boolean) {
        if (tenBit) {
            buf.put(off, ((v10 and 0x3) shl 6).toByte())
            buf.put(off + 1, (v10 ushr 2).toByte())
        } else {
            buf.put(off, (v10 ushr 2).toByte())
        }
    }

    // ══ 回读：HEIF box 解析（colr/nclx + hvcC 位深）═════════════════════════

    /**
     * 容器 box（需要递归进去）。`meta` / `dinf` / `iinf` 是 fullbox，子 box 起点 +4。
     *
     * 卡 H2 修（2026-10-14）：**`iinf` 此前不在白名单** ⇒ 遍历器到 meta 的下一层就跳过它，
     * `infe` 永远解析不到（"官方 muxer 到底写了哪些 item"这条第三臂的核心结论因此缺失）。
     * `iinf` 还多一层前缀：FullBox(4) + **entry_count**（v0 = 2 B、v1+ = 4 B）——
     * 不跳它，遍历器会把 entry_count 的低字节当 box size，读到垃圾后 `break`（静默丢整棵子树）。
     */
    private val CONTAINERS = setOf("meta", "iprp", "ipco", "dinf", "moov", "trak", "mdia", "minf", "stbl", "iinf")
    private val FULLBOX_CONTAINERS = setOf("meta", "dinf", "iinf")

    internal class HeifFacts {
        val topLevel = mutableListOf<String>()
        var colrType: String? = null
        var hvcCSeen = false
        var primaries = -1
        var transfer = -1
        var matrix = -1
        var fullRange = -1
        var bitDepthLumaMinus8 = -1
        var bitDepthChromaMinus8 = -1
        var chromaFormat = -1
        var profileIdc = -1
        var tier = -1
        var levelIdc = -1
        var width = -1
        var height = -1

        /** `ftyp` 的 major_brand（`mp42` = MP4/muxer 老路；`heic` = 图片）。 */
        var majorBrand: String? = null

        /** `pixi`（位深声明）是否落盘 —— AOSP 的 pixi 被 flags 门控，缺了 macOS 就读不到 10bit。 */
        var pixiSeen = false
        var pixiBitDepth = -1

        /** `iloc` 的 item 数（-1 = 没有 iloc ⇒ 不是 HEIF 图片）。 */
        var ilocItems = -1

        /**
         * `iinf/infe` 的 item 类型（顺序 = 声明顺序）—— 卡 H2 补：**官方 muxer 到底写了
         * 哪些 item**（`hvc1` 图像项 / `grid` / `Exif` / `mime`…）是第三臂
         * （`muxer-image-heic`）要回答的核心问题，读不到 `infe` 就答不了。
         */
        val itemTypes = mutableListOf<String>()

        /** 与 [itemTypes] 一一对应的 item_name（UTF-8，未命名时为空串）。 */
        val itemNames = mutableListOf<String>()
    }

    /** 遍历 box 树提取事实（纯函数、无 Android 依赖；单测直接喂字节）。 */
    internal fun parseBoxes(b: ByteArray): HeifFacts {
        val f = HeifFacts()
        walkBoxes(b, 0, b.size, 0, f)
        return f
    }

    /**
     * 解析自己产出的 `.heic`：定位 `colr`(nclx) 与 `hvcC`，把**实际写入值**与**请求值**逐项对照。
     * 请求值 = BT.2020 线性全范围 → CICP `primaries=9 / transfer=8 / full_range=1`
     * （CICP 值一手来源：libheif `heif_color.h:140-193`；注意 Android 侧枚举是 6/1/1，见文件头）。
     */
    private fun parseHeif(file: File, tag: String): HeifFacts? {
        try {
            val b = file.readBytes()
            val f = parseBoxes(b)
            // 兜底：全文搜索 box type 出现次数，用于发现遍历器漏掉的层
            val colrHits = countHits(b, "colr")
            val hvccHits = countHits(b, "hvcC")
            val nclxOk = f.primaries == 9 && f.transfer == 8 && f.fullRange == 1
            log(EngineBridge.LEVEL_INFO, "heif10.nclx", kv(
                "arm" to tag, "file" to file.absolutePath, "bytes" to b.size,
                "topLevel" to f.topLevel.joinToString(","),
                "colrType" to f.colrType,
                "primaries" to f.primaries, "transfer" to f.transfer,
                "matrix" to f.matrix, "fullRange" to f.fullRange,
                "expect" to "primaries=9,transfer=8,full_range=1",
                "nclxMatch" to nclxOk,
                "bitDepthLumaMinus8" to f.bitDepthLumaMinus8,
                "bitDepthChromaMinus8" to f.bitDepthChromaMinus8,
                "chromaFormat" to f.chromaFormat,
                "hevcProfileIdc" to f.profileIdc, "hevcTier" to f.tier, "hevcLevelIdc" to f.levelIdc,
                "ispe" to "${f.width}x${f.height}",
                "majorBrand" to f.majorBrand,
                "pixi" to f.pixiSeen, "pixiBitDepth" to f.pixiBitDepth,
                "ilocItems" to f.ilocItems,
                "colrHits" to colrHits, "hvcCHits" to hvccHits,
            ))
            // 卡 H2：item 清单**单独一条事件** —— 既有事件（heif10.nclx / heif10.probe）
            // 的字段与取值一字不动（两臂的历史产物已在 golden 里），新增信息只在这里出。
            if (f.itemTypes.isNotEmpty()) {
                log(EngineBridge.LEVEL_INFO, "heif10.iinf", kv(
                    "arm" to tag, "file" to file.absolutePath,
                    "itemCount" to f.itemTypes.size,
                    "itemTypes" to f.itemTypes.joinToString(","),
                    "itemNames" to f.itemNames.joinToString(","),
                    "ilocItems" to f.ilocItems,
                ))
            }
            if (f.colrType == null) {
                log(EngineBridge.LEVEL_WARN, "heif10.probe", kv(
                    "phase" to "nclx", "arm" to tag, "ok" to false,
                    "reason" to "no colr box parsed (colr byte hits=$colrHits)",
                ))
            }
            // 顶层有没有 meta = 这份文件到底是不是 HEIF 图片（muxer 走 MP4 老路时只有 moov）
            if (!f.topLevel.contains("meta")) {
                log(EngineBridge.LEVEL_WARN, "heif10.probe", kv(
                    "phase" to "heifStructure", "arm" to tag, "ok" to false,
                    "majorBrand" to f.majorBrand, "topLevel" to f.topLevel.joinToString(","),
                    "reason" to "no top-level meta box (muxer 写成 MP4 ⇒ 相册不认)",
                ))
            }
            // 期望位深按臂判：8bit 对照臂 0，其余臂（main10 / image-heic）2
            if (f.bitDepthLumaMinus8 >= 0 && f.bitDepthLumaMinus8 != if (tag.contains("8bit")) 0 else 2) {
                log(EngineBridge.LEVEL_WARN, "heif10.probe", kv(
                    "phase" to "bitDepth", "arm" to tag, "ok" to false,
                    "bitDepthLumaMinus8" to f.bitDepthLumaMinus8,
                    "reason" to "written bit depth differs from requested arm",
                ))
            }
            return f
        } catch (t: Throwable) {
            log(EngineBridge.LEVEL_ERROR, "heif10.probe", kv(
                "phase" to "parseHeif", "arm" to tag, "ok" to false, "err" to errText(t),
            ))
            return null
        }
    }

    /** 递归遍历 box 树（只进白名单容器，深度 ≤ 5），顺手提取 colr/hvcC/ispe。 */
    private fun walkBoxes(b: ByteArray, from: Int, to: Int, depth: Int, f: HeifFacts) {
        if (depth > 5) return
        var at = from
        while (at + 8 <= to) {
            var size = u32(b, at)
            val type = String(b, at + 4, 4, Charsets.US_ASCII)
            var header = 8
            if (size == 1 && at + 16 <= to) {
                size = u32(b, at + 8) // largesize 低 32 位（本探针文件远小于 4GB）
                header = 16
            }
            if (size == 0) size = to - at
            if (size < header || at + size > to) break
            val cs = at + header
            val ce = at + size
            if (depth == 0) f.topLevel.add(type)
            when (type) {
                "colr" -> if (cs + 11 <= ce) {
                    f.colrType = String(b, cs, 4, Charsets.US_ASCII)
                    if (f.colrType == "nclx") {
                        f.primaries = u16(b, cs + 4)
                        f.transfer = u16(b, cs + 6)
                        f.matrix = u16(b, cs + 8)
                        f.fullRange = (b[cs + 10].toInt() and 0x80) ushr 7
                    }
                }
                "hvcC" -> if (cs + 23 <= ce) {
                    f.hvcCSeen = true
                    // HEVCDecoderConfigurationRecord（ISO/IEC 14496-15）：
                    // [1] profile_space(2)|tier(1)|profile_idc(5)；[12] level_idc；
                    // [16] chromaFormat(低2位)；[17] bitDepthLumaMinus8(低3位)；[18] bitDepthChromaMinus8
                    f.profileIdc = b[cs + 1].toInt() and 0x1F
                    f.tier = (b[cs + 1].toInt() and 0x20) ushr 5
                    f.levelIdc = b[cs + 12].toInt() and 0xFF
                    f.chromaFormat = b[cs + 16].toInt() and 0x03
                    f.bitDepthLumaMinus8 = b[cs + 17].toInt() and 0x07
                    f.bitDepthChromaMinus8 = b[cs + 18].toInt() and 0x07
                }
                "ispe" -> if (cs + 12 <= ce) { // fullbox: version/flags(4) + width(4) + height(4)
                    f.width = u32(b, cs + 4)
                    f.height = u32(b, cs + 8)
                }
                "ftyp" -> if (cs + 4 <= ce) f.majorBrand = String(b, cs, 4, Charsets.US_ASCII)
                "pixi" -> if (cs + 5 <= ce) { // fullbox: version/flags(4) + num_channels(1) + 各位深(1)
                    f.pixiSeen = true
                    f.pixiBitDepth = b[cs + 5].toInt() and 0xFF
                }
                "infe" -> if (cs + 12 <= ce) {
                    // FullBox(4)：[cs]=version；v ≥ 2 才有 item_type：
                    //   item_ID(2) + protection_index(2) + item_type(4) + item_name(UTF-8, NUL)
                    // v0/v1 无 item_type（item_name + content_type + content_encoding）
                    val ver = b[cs].toInt() and 0xFF
                    if (ver >= 2) {
                        f.itemTypes.add(String(b, cs + 8, 4, Charsets.US_ASCII))
                        f.itemNames.add(cstr(b, cs + 12, ce))
                    } else {
                        f.itemTypes.add("(infe v$ver: 无 item_type)")
                        f.itemNames.add(cstr(b, cs + 8, ce))
                    }
                }
                "iloc" -> if (cs + 8 <= ce) { // fullbox(4) + offset|length size(2) + item_count(v<2: 2B / v2: 4B)
                    f.ilocItems = if ((b[cs].toInt() and 0xFF) < 2) u16(b, cs + 6) else u32(b, cs + 6)
                }
                else -> if (type in CONTAINERS) {
                    var childStart = if (type in FULLBOX_CONTAINERS) cs + 4 else cs
                    // iinf 的 entry_count（v0 = 2 B / v1+ = 4 B）在 FullBox 头之后、子 box 之前
                    if (type == "iinf" && childStart + 2 <= ce) {
                        childStart += if ((b[cs].toInt() and 0xFF) == 0) 2 else 4
                    }
                    walkBoxes(b, childStart, ce, depth + 1, f)
                }
            }
            at += size
        }
    }

    /** 读 NUL 结尾的 UTF-8 串（infe 的 item_name；无 NUL 时读到 box 末）。 */
    private fun cstr(b: ByteArray, from: Int, to: Int): String {
        var e = from
        while (e < to && b[e].toInt() != 0) ++e
        return String(b, from, e - from, Charsets.UTF_8)
    }

    /** 全文统计某个 4 字节 box type 出现次数（解析器兜底对照）。 */
    private fun countHits(b: ByteArray, tag: String): Int {
        val t = tag.toByteArray(Charsets.US_ASCII)
        var n = 0
        var i = 4
        while (i + 4 <= b.size) {
            if (b[i] == t[0] && b[i + 1] == t[1] && b[i + 2] == t[2] && b[i + 3] == t[3]) n++
            i++
        }
        return n
    }

    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
}
