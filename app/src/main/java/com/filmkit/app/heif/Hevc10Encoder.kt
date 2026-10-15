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
 * ## csd-0 与帧数据的形态（卡 FIX3 认知修正，勿再重踩）
 * 真机 X300 的 `outputFormat` 给 `csd-0` 是 **Annex-B**（`00 00 00 01` + VPS/SPS/PPS，79 B），
 * **不是** hvcC record；帧数据同样是 Annex-B。此前"编码器实测给 106 B hvcC"的结论来自
 * `MediaMuxer` 产出的 MP4 里的 `stsd/hvc1/hvcC` box —— 那是 **muxer 转换后的产物**。
 * ⇒ 本类把两种形态原样收下，统一交给 [HevcCsd0]/[HevcAnnexB] 规范化（hvcC + 帧长度前缀化），
 * **不依赖 vendor 行为**（`prepend-sps-pps-to-idr-frames=0` 只是低成本尝试）。
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

    /** 探针已验证的口径（1024×256 测试图）。**交付时按分辨率/质量目标覆盖**（见 [HeifQuality]）。 */
    const val DEFAULT_BITRATE = 4_000_000

    /**
     * 与 [HeifQuality.FRAME_RATE] **同一个**值（**恒为 1**，见那边的表格）。
     *
     * ⚠ 两处必须一致：`KEY_BIT_RATE` 的单位是 bit / 秒，而静态图只编 **1 帧** ⇒ 单帧预算
     *   = `bitrate / frame-rate`。[HeifQuality.bitrateFor] 按这个值反算码率，这里落键
     *   ⇒ 改一处忘另一处 = 单帧预算整体偏 30 倍（`HeifQualityTest` 锁住）。
     */
    private const val FRAME_RATE = HeifQuality.FRAME_RATE
    /** 每 1 秒一个 I 帧 ⇒ [FRAME_RATE]=1 时即 **all-intra**（静态图要的就是这个）。 */
    private const val I_FRAME_INTERVAL = 1
    private const val TIMEOUT_US = 10_000L
    private const val DEADLINE_MS = 20_000L

    /**
     * 编码器 `start()` 之后**首次提供输入缓冲**的等待上限 —— 见 [encode] 里 `awaitInput` 的注释：
     * 这一步**不能**用 [TIMEOUT_US]（10 ms）单次试探，否则真机随机失败。
     * 取值 5 s：正常情况下硬件编码器在数百毫秒内就绪；5 s 仍拿不到就说明它真没起来，
     * 该失败就失败（回退链兜住照片），不要让用户干等 [DEADLINE_MS]（20 s）。
     */
    private const val INPUT_READY_MS = 5_000L

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
    fun buildFormat(
        info: MediaCodecInfo,
        width: Int,
        height: Int,
        bitrate: Int,
        strategy: HeifQuality.Strategy? = null,
    ): MediaFormat {
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
        // 卡 FIX3 的低成本尝试（可回退）：探针的 outputFormat 里出现过这个键 ⇒ 编码器认它。
        // 置 0 表示"不要在 IDR 前重复插入 SPS/PPS"——不过**即使 vendor 因此改给 hvcC 形态的
        // csd-0，也照样走 [HevcCsd0] 的通用规范化路径**（两种形态都吃），不依赖 vendor 行为。
        runCatching { fmt.setInteger("prepend-sps-pps-to-idr-frames", 0) }
        applyQuality(fmt, strategy)
        return fmt
    }

    /**
     * 把 [HeifQuality.Strategy] 落成 `MediaFormat` 键（**卡 FIX4 的质量驱动挂点**）。
     *
     * - `bitrate-mode` + QP/`quality` 用 `runCatching` 包：
     *   · 这些键在**旧平台/非 MTK**上不存在或不接受 ⇒ 抛异常不能打死照片（回退链兜住片子）；
     *   · 但**"设成功"不等于"生效"** ⇒ 真正判定生效的是 [HeifQuality.verdict]（产出体积
     *     是否进目标区间）。编码器忽略这些键是**静默**的，只有体积判据能抓到。
     * - QP 走**官方** I 帧区间键（`video-qp-i-min`/`-max`，见 [HeifQuality.QP_I_MIN_KEY]）：
     *   静态图 all-intra ⇒ 两键同值 = 恒定质量；此前用的 MTK vendor 键真机实测失败
     *   （设了连 `csd-0` 都拿不到），已删。
     */
    fun applyQuality(fmt: MediaFormat, strategy: HeifQuality.Strategy?) {
        if (strategy == null) return
        runCatching { fmt.setInteger(HeifQuality.BITRATE_MODE_KEY, strategy.bitrateMode) }
        strategy.qp?.let { qp ->
            runCatching { fmt.setInteger(HeifQuality.QP_I_MIN_KEY, qp) }
            runCatching { fmt.setInteger(HeifQuality.QP_I_MAX_KEY, qp) }
            runCatching { fmt.setInteger(HeifQuality.QP_AVERAGE_KEY, qp) }
        }
        strategy.quality?.let { runCatching { fmt.setInteger(HeifQuality.QUALITY_KEY, it) } }
    }

    /** 编码器声明的码率上限（bit/s）；拿不到 ⇒ `null`（由 [HeifQuality.bitrateFor] 兜底）。 */
    fun bitrateCap(info: MediaCodecInfo): Int? =
        runCatching {
            info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                .videoCapabilities?.bitrateRange?.upper
        }.getOrNull()

    /**
     * 编码器**声明的能力**进日志（每张 HEIF 一条，`photo.heif_enc_caps`）。
     *
     * 为什么值得单开一条：0.11.39 那次真机只能从 `bitrate:100000000` **反推**"被夹了"，
     * 而"我们设的 `bitrate-mode=CQ` 到底被不被支持"根本无从判断（`isBitrateModeSupported`
     * 是**平台自述**，比任何体积推断都硬）。有这一条，下一轮不用再猜：
     * · `bitrateMax` = 真实上限（0.11.40 的 20.4 Mbps 目标是否被夹，一眼可见）；
     * · `cqSupported=false` ⇒ 路 ①② 的 CQ 模式是无效键 ⇒ 只能靠路 ③ 的码率预算
     *   （这正好也是 `FRAME_RATE=1` 的适用场景）；
     * · `fpsMin/fpsMax` ⇒ `FRAME_RATE=1` 是否落在编码器接受的范围（**本改动唯一的真机风险**）；
     * · `qpMin/qpMax` ⇒ 官方 QP 键的取值范围（API 31+ 才有）。
     */
    private fun logCaps(info: MediaCodecInfo, w: Int, h: Int) {
        val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC) }
            .getOrNull() ?: return
        val br = runCatching { caps.videoCapabilities?.bitrateRange }.getOrNull()
        val fr = runCatching { caps.videoCapabilities?.getSupportedFrameRatesFor(w, h) }.getOrNull()
        val cq = runCatching {
            caps.encoderCapabilities?.isBitrateModeSupported(HeifQuality.MODE_CQ)
        }.getOrNull()
        val cbr = runCatching {
            caps.encoderCapabilities?.isBitrateModeSupported(HeifQuality.MODE_CBR)
        }.getOrNull()
        val vbr = runCatching {
            caps.encoderCapabilities?.isBitrateModeSupported(HeifQuality.MODE_VBR)
        }.getOrNull()
        // qualityRange 是 API 31+ 的方法：旧设备上直接调用会 NoSuchMethodError ⇒ 限版本再取
        val qp = if (android.os.Build.VERSION.SDK_INT >= 31) {
            runCatching { caps.encoderCapabilities?.qualityRange }.getOrNull()
        } else null
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "photo.heif_enc_caps",
            "{\"codec\":\"${info.name}\",\"hw\":${info.isHardwareAccelerated}," +
                "\"bitrateMin\":${br?.lower ?: -1},\"bitrateMax\":${br?.upper ?: -1}," +
                "\"fpsMin\":${fr?.lower ?: -1},\"fpsMax\":${fr?.upper ?: -1}," +
                "\"cqSupported\":${cq ?: "null"},\"cbrSupported\":${cbr ?: "null"}," +
                "\"vbrSupported\":${vbr ?: "null"}," +
                "\"qpMin\":${qp?.lower ?: -1},\"qpMax\":${qp?.upper ?: -1}}",
        )
    }

    /**
     * 编码一帧 10bit HEVC（**质量优先**：按 [HeifQuality.strategies] 逐路尝试，
     * 产出体积进目标区间即停），返回 [HeifContainerWriter.build] 的直接输入。
     *
     * ## 卡 FIX4：为什么要"逐路尝试"
     * 只允许一次编码的话，"设了 QP 但 vendor 静默忽略"和"设了就生效"从外面看不出区别
     * （都不报错）⇒ 就会重演"压过头却没人知道"。本函数对每一路都跑完编码再用
     * [HeifQuality.verdict] 判体积，不达标就换下一路（"设了不生效"的检测就在这里）；
     * 全都不达标 ⇒ 留下 [HeifQuality.pickBest] 最接近的那份，并把 `verdict != OK`
     * 回给调用方进日志 —— **不许静默压过头**。
     *
     * @param pattern 每像素打包 `y10 | (u10 shl 10) | (v10 shl 20)`，各分量 0..1023
     *   （全范围，与探针 `buildPattern()` 同一约定）。
     *   **必须是已经转换好的 YCbCr**：本类不做 RGB→YCbCr 转换
     *   （挂点见类注释「RGB→YCbCr 挂点」），且必须与容器 `colr` 的 `matrix_coefficients`
     *   一致 —— 默认口径是 **BT.709（matrix=1）**。
     * @param outStats 输出：本次真正生效的路与统计（进 `photo.heif_encoded`）。
     * @throws IllegalStateException 没有可用的 10bit 硬件编码器 / 编码器未给 `csd-0` / 没有输出帧。
     */
    fun encode(
        width: Int,
        height: Int,
        pattern: IntArray,
        bitrate: Int = DEFAULT_BITRATE,
        outStats: (HeifQuality.Stats) -> Unit = {},
    ): HeifContainerWriter.Image = encodeWithQuality(
        width, height, pattern, bitrate, strategies = null, outStats = outStats,
    ).first

    /** 同 [encode]，额外返回统计（供调用方直接进日志）。 */
    fun encodeWithStats(
        width: Int,
        height: Int,
        pattern: IntArray,
        bitrate: Int = DEFAULT_BITRATE,
    ): Pair<HeifContainerWriter.Image, HeifQuality.Stats?> {
        var stats: HeifQuality.Stats? = null
        val img = encodeWithQuality(width, height, pattern, bitrate, null) { stats = it }.first
        return img to stats
    }

    /** 本类的**唯一**编码实现（[encode]/[encodeWithStats] 都走它）。 */
    private fun encodeWithQuality(
        width: Int,
        height: Int,
        pattern: IntArray,
        bitrate: Int,
        strategies: List<HeifQuality.Strategy>?,
        outStats: (HeifQuality.Stats) -> Unit,
    ): Pair<HeifContainerWriter.Image, HeifQuality.Stats?> {
        require(width > 0 && height > 0) { "尺寸非法: ${width}x$height" }
        require(pattern.size == width * height) { "pattern 大小 ${pattern.size} ≠ ${width}x$height" }
        val info = selectEncoder()
            ?: error("没有硬件 HEVC 编码器声明 Main10 + P010(54)（10bit 路线不可用，请回退）")
        val cap = bitrateCap(info)
        logCaps(info, width, height)
        val plans = (strategies ?: HeifQuality.strategies(width, height, cap)).toMutableList()
        val t0 = System.currentTimeMillis()
        val tried = mutableListOf<Triple<HeifQuality.Strategy, Int, Long>>()
        var best: Pair<HeifContainerWriter.Image, HeifQuality.Stats>? = null
        var lastEmitted: HeifQuality.Stats? = null
        var i = 0
        var qpTries = 0
        while (i < plans.size && tried.size < HeifQuality.MAX_TOTAL_TRIES) {
            val plan = plans[i]
            val t = System.currentTimeMillis()
            val attempt = runCatching { encodeOnce(info, width, height, pattern, plan.bitrate, plan) }
            val img = attempt.getOrNull()
            if (img == null) {
                // 该路失败（含 configure 拒绝）⇒ 换下一路，但**必须留痕**：0.11.40 真机 qp 路
                // （CQ + 官方 QP 键）失败时只有一条 `photo.heif_csd {len:0}`，异常信息被
                // runCatching 吞掉 ⇒ 只能猜"是不是 QP 键与 CQ 冲突"。原因本身就是判据。
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_WARN, "camera", "photo.heif_try_failed",
                    "{\"mode\":\"${plan.mode}\",\"qp\":${plan.qp ?: -1}," +
                        "\"bitrate\":${plan.bitrate},\"bitrateMode\":${plan.bitrateMode}," +
                        "\"error\":\"${(attempt.exceptionOrNull()?.message ?: "?").replace("\"", "'")}\"}",
                )
                ++i
                continue
            }
            val bytes = img.frame.size
            val ms = System.currentTimeMillis() - t
            val v = HeifQuality.verdict(bytes, width, height)
            tried += Triple(plan, bytes, ms)
            val r = HeifQuality.targetRange(width, height)
            val st = HeifQuality.Stats(
                mode = plan.mode, qp = plan.qp, bitrate = plan.bitrate, bytes = bytes, ms = ms,
                verdict = v, tries = tried.size, targetMin = r.first, targetMax = r.last, cap = cap,
            )
            // **每一路都进日志**（不只最终生效的那路）：0.11.39 的真机日志只留下最后一路，
            // 于是"哪条路真的驱动了质量"只能靠反推。多条 `photo.heif_encoded` = 一张照片
            // 就能横向比较三路（mode/qp/bitrate/bytes/verdict 逐条对齐）。
            outStats(st)
            lastEmitted = st
            if (v == HeifQuality.Verdict.OK) {
                return img to st
            }
            // 该留还是该换 + 这一路还要不要再试一次（规则在 HeifQuality 里 ⇒ 纯函数可测）
            if (HeifQuality.isBetter(best?.second?.bytes, bytes, width, height)) {
                best = img to st
            }
            val again = HeifQuality.refine(plan, v, qpTries)
            if (again != null) {
                qpTries = again.first
                plans[i] = again.second
                continue   // 同一格原地重试（不前进到下一路）
            }
            ++i
            qpTries = 0
        }
        // 一路都没达标：留下最接近目标区间的那份 + 把 `verdict != OK` 说清楚（不许静默）
        val b = best
        if (b == null) error("全部 ${plans.size} 路编码策略都失败（${info.name}）")
        val r = HeifQuality.targetRange(width, height)
        val st = b.second.copy(
            tries = tried.size,
            ms = System.currentTimeMillis() - t0,
            targetMin = r.first,
            targetMax = r.last,
        )
        // 最终选定那份若已在上面逐路记过（同一份产出），就不再重复一条
        if (lastEmitted?.bytes != st.bytes) outStats(st)
        return b.first to st
    }

    /**
     * 单次编码（[encodeWithQuality] 的一路）。
     *
     * @param strategy 质量策略（落键见 [applyQuality]）。
     */
    private fun encodeOnce(
        info: MediaCodecInfo,
        width: Int,
        height: Int,
        pattern: IntArray,
        bitrate: Int,
        strategy: HeifQuality.Strategy?,
    ): HeifContainerWriter.Image {
        var codec: MediaCodec? = null
        var csdRaw: ByteArray? = null
        var csdLogged = false
        try {
            codec = MediaCodec.createByCodecName(info.name)
            codec.configure(
                buildFormat(info, width, height, bitrate, strategy),
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            codec.start()

            // 喂 1 帧 + EOS（HEIF 是 still image）
            //
            // ⚠ **等输入缓冲必须用循环等到 [INPUT_READY_MS]，不能用 [TIMEOUT_US] 单次试探**：
            // `TIMEOUT_US`（10 ms）是给**输出侧轮询**用的（那本来就在 while 循环里）；
            // 而硬件编码器 `start()` 之后**首次**提供输入缓冲要先初始化硬件 + 分配缓冲，
            // 耗时随系统负载/温度波动 ⇒ 真机实测同一颗 `c2.mtk.hevc.encoder` 同一份配置，
            // 有时 10 ms 内就绪、有时远超（2026-10-15 表现为 08:13 成功 / 16:37、16:38 连续失败，
            // 且旧文案写"10s 内拿不到"是错的，实际只等了 10 ms）。
            fun awaitInput(what: String): Int {
                val deadline = System.currentTimeMillis() + INPUT_READY_MS
                while (true) {
                    val idx = codec!!.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) return idx
                    if (System.currentTimeMillis() >= deadline) {
                        error("编码器 ${info.name} 在 ${INPUT_READY_MS}ms 内未提供$what（硬件编码器初始化超时）")
                    }
                }
            }
            val inIdx = awaitInput("输入缓冲")
            val fed = fillInput(codec, inIdx, pattern, width, height)
            check(fed > 0) { "输入缓冲填充失败（${info.name}）" }
            codec.queueInputBuffer(inIdx, 0, fed, 0, 0)
            val eosIdx = awaitInput("EOS 输入缓冲")
            codec.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)

            val raw = ByteArrayOutputStream()
            val bufInfo = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + DEADLINE_MS
            while (System.currentTimeMillis() < deadline) {
                val outIdx = codec.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 卡 FIX3：这里的 csd-0 **不保证**是 hvcC —— 真机 X300 给的是 Annex-B
                    // （79 B，`00 00 00 01` + VPS/SPS/PPS）。形态判定与转换统一交给 HevcCsd0。
                    val of = codec.outputFormat
                    logOutputFormat(of)   // 0.11.42：把"有哪些旋钮"列出来（含 vendor 键）
                    csdRaw = csd0(of)
                    continue
                }
                if (outIdx < 0) continue
                val size = bufInfo.size
                val isConfig = bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val buf = codec.getOutputBuffer(outIdx)
                if (size > 0 && !isConfig && buf != null) {
                    // 帧数据原样收集：形态（Annex-B / 长度前缀）在 HevcCsd0 里实测判定后再对齐
                    buf.position(bufInfo.offset)
                    buf.limit(bufInfo.offset + size)
                    val chunk = ByteArray(size)
                    buf.get(chunk)
                    raw.write(chunk)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }

            val f = raw.toByteArray()
            check(f.isNotEmpty()) { "编码器没有输出帧数据（${info.name}）" }

            // ── 规范化：Annex-B → hvcC + 帧数据长度前缀化（+ **无条件**诊断）──────────
            // 卡 FIX3 的根因在这里：走 MediaMuxer 的两条路由 muxer 免费做了这步转换，自研容器
            // 必须自己做。此前直接把 csd-0 搬进 ipco/hvcC ⇒ 真机 `csd-0 不是合法
            // HEVCDecoderConfigurationRecord` ⇒ 10bit 交付 100% 失败（回退链兜住了片子）。
            val n = try {
                HevcCsd0.accept(csdRaw, f) { fields -> csdLog(fields) }
            } finally {
                csdLogged = true // accept 无论成败都发过诊断（emit 在 finally）
            }
            return HeifContainerWriter.Image(n.hvcc, n.frame, width, height)
        } finally {
            // 连 outputFormat 都没到（编码器早期失败 / 异常）时也留一条 csd 诊断（全空）
            if (!csdLogged) csdLog(HevcCsd0.fields(null, null, false, "none"))
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    /** `photo.heif_csd` 的唯一出口（[HevcCsd0.EVENT]）。 */
    private fun csdLog(fields: String) {
        EngineBridge.nativeAppendLog(EngineBridge.LEVEL_INFO, "camera", HevcCsd0.EVENT, fields)
    }

    /**
     * **把 `outputFormat` 的全部键 dump 进日志**（`photo.heif_out_format`，每路一条）。
     *
     * 为什么值得单开一条：0.11.40 / 0.11.41 两轮把**标准**旋钮全试死了 ——
     * 三种 `bitrate-mode` × 20.4/100 Mbps 预算 ⇒ 产出全在 187~649 KB（`vbr-hi` 给满 100 Mbps
     * 反而更小），证明编码器**不读 `KEY_BIT_RATE`**；官方 QP 键在 CQ 下失败；`quality` 键待证。
     * 剩下唯一可能生效的是 **vendor 私有键**（MTK 会在 `outputFormat` 里**回显**它们 ——
     * 上轮就见过 `vendor.mtk.ext.venc.visual.quant`）⇒ 先把"有哪些旋钮"列出来，再决定拧哪个。
     * **回显 = 编码器认这个键**，这本身就是判据（比猜键名强得多）。
     */
    private fun logOutputFormat(fmt: MediaFormat) {
        val sb = StringBuilder("{")
        for (k in fmt.keys.sorted()) {
            // MediaFormat **没有** getValue(String)（javap 实测：只有 getValueTypeForKey）⇒
            // 按类型逐个试；类型不匹配时 getter 会抛（ClassCastException / NPE）⇒ 用 runCatching 兜。
            val v = runCatching { fmt.getByteBuffer(k) }.getOrNull()?.let { "bytes(${it.remaining()})" }
                ?: runCatching { fmt.getInteger(k) }.getOrNull()?.toString()
                ?: runCatching { fmt.getLong(k) }.getOrNull()?.toString()
                ?: runCatching { fmt.getFloat(k) }.getOrNull()?.toString()
                ?: runCatching { fmt.getString(k) }.getOrNull()
                ?: "?"
            if (sb.length > 1) sb.append(',')
            sb.append('"').append(k).append("\":\"").append(v.replace("\"", "'")).append('"')
        }
        sb.append('}')
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "photo.heif_out_format", sb.toString(),
        )
    }

    /** `outputFormat` 的 `csd-0` **原始字节**（可能是 hvcC，也可能是 Annex-B —— 交由 [HevcCsd0] 判定）。 */
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
