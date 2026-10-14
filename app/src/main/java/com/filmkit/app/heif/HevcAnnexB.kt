package com.filmkit.app.heif

import java.io.ByteArrayOutputStream

/**
 * **Annex-B → HEVCDecoderConfigurationRecord（hvcC）** 转换 + H.265 SPS 解析 + 帧数据长度前缀化。
 *
 * ## 为什么必须有这一步（卡 FIX3 的根因，别再重踩）
 * 走 `MediaMuxer` 的两条路（探针三臂、8bit 回退档）里，"Annex-B → hvcC + 帧数据长度前缀化"
 * 是 **muxer 免费做的**。**自研容器没有这一步** ⇒ `csd-0` 直接搬进 `ipco/hvcC` 会得到
 * "不是合法 hvcC"（真机 0.11.36：`photo.heif_csd{"len":79,…,"ok":false,
 * "head":"0000000140010c01ffff…"}` ⇒ `csd-0 不是合法 HEVCDecoderConfigurationRecord`）。
 *
 * ⚠ **认知修正（H1 的 106 B 来源）**：此前以为"编码器实测给 106 B 的 hvcC"是**错的** ——
 * 那 106 B 是从 `MediaMuxer` 产出的 MP4 里 `stsd/hvc1/hvcC` box 提取的，是 **muxer 转换后的
 * 产物**，不是 `outputFormat` 的 `csd-0`。别再用它当"编码器会给 hvcC"的证据。
 *
 * ## 转换是双向可验证的
 * 真机 MP4 golden（`app/src/test/resources/heif/probe-main10-p010-*.heic`）里同时有
 * muxer 产出的 hvcC 与 4 字节长度前缀帧 ⇒ 可以**反向拆成 Annex-B 再转回来**做逐字节往返
 * 判据（`HevcAnnexBTest`）。
 *
 * ## 纯 JVM、零 Android 依赖
 * 只用 `ByteArray`/`ByteArrayOutputStream` ⇒ 单测可直接喂真机 golden 与构造样本。
 */
object HevcAnnexB {

    /** `nal_unit_type`（H.265 表 7-1）：VPS / SPS / PPS。 */
    const val NAL_VPS = 32
    const val NAL_SPS = 33
    const val NAL_PPS = 34

    /** hvcC 固定头长度（到 `numOfArrays` 为止），见 ISO/IEC 14496-15 §8.3.3.1 表 1。 */
    const val HVCC_FIXED = 23

    /** 转换后统一使用的 NAL 长度前缀宽度（`lengthSizeMinusOne = 3` ⇒ 4 字节）。 */
    const val LEN_SIZE_4 = 3

    private const val ZERO = 0.toByte()
    private const val ONE = 1.toByte()

    /** 帧数据的 NAL 打包形态。 */
    enum class FrameMode { ANNEXB, LEN_PREFIXED, UNKNOWN }

    /**
     * SPS 解析结果。前 9 项是 hvcC 固定头需要的；后 4 项供诊断与语法自检。
     *
     * @param fullyParsed 整条 SPS 是否被语法完整消费并停在合法的 `rbsp_trailing_bits`
     *   —— **只用于自检/诊断**：hvcC 需要的字段在 SPS 前段，`fullyParsed=false` 不影响
     *   转换结果（真机若出现，说明本解析器有未覆盖的语法分支，是下一轮的线索）。
     */
    data class Sps(
        val profileSpace: Int,
        val tierFlag: Int,
        val profileIdc: Int,
        val compatFlags: Int,
        val constraintFlags: Long,
        val levelIdc: Int,
        val maxSubLayersMinus1: Int,
        val temporalIdNesting: Boolean,
        val chromaFormatIdc: Int,
        val separateColourPlane: Boolean,
        val bitDepthLumaMinus8: Int,
        val bitDepthChromaMinus8: Int,
        val fullyParsed: Boolean,
    )

    /** `nal_unit_type` = NAL 头（2 B）的高 6 位（`forbidden_zero_bit` 之后）。 */
    fun nalType(nal: ByteArray): Int {
        require(nal.size >= 2) { "NAL 太短：${nal.size} B（HEVC NAL 头固定 2 B）" }
        return (nal[0].toInt() and 0x7E) ushr 1
    }

    // ══ Annex-B 切分 ═══════════════════════════════════════════════════════════

    /**
     * 按起始码切分 Annex-B 字节流（兼容 `00 00 01` 与 `00 00 00 01`，允许更多前导零）。
     *
     * NAL 结束位置的处理：从下一个起始码 `00 00 01` 的起点回退 3 B 后，**把尾部所有 `0x00`
     * 一并归下一段**（4 字节起始码的 `zero_byte`、多写的前导零、以及 `trailing_zero_8bits`）。
     * 漏掉哪怕一个 0 都会污染上一个 NAL —— 参数集 NAL 差一位整条 hvcC 就作废。保留至少
     * 2 字节（HEVC NAL 头固定 2 B），不会把 NAL 削空。
     *
     * @return 按出现顺序的 NAL 单元（不含起始码与前导/尾随零）；没有起始码时返回空表。
     */
    fun splitAnnexB(b: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 2 < b.size) {
            if (b[i] == ZERO && b[i + 1] == ZERO && b[i + 2] == ONE) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>(starts.size)
        for (k in starts.indices) {
            val s = starts[k]
            var e = if (k + 1 < starts.size) starts[k + 1] - 3 else b.size
            while (e > s + 2 && b[e - 1] == ZERO) e--
            if (e > s) out += b.copyOfRange(s, e)
        }
        return out
    }

    /** NAL 单元 → Annex-B（4 字节起始码）。[splitAnnexB] 的逆（帧往返测试用）。 */
    fun toAnnexB(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in nals) {
            out.write(0); out.write(0); out.write(0); out.write(1)
            out.write(n)
        }
        return out.toByteArray()
    }

    // ══ SPS 解析（H.265 7.3.2.2.1，按语法逐字段读，不扫字节）═══════════════════════

    /**
     * 解析 SPS 的 `profile_tier_level()` + `chroma_format_idc` + 两个 `bit_depth_*_minus8`，
     * 并**按 H.265 语法继续走完整条 SPS**（含 `scaling_list_data()` 与 `vui_parameters()`）
     * 以自检位流消费是否自洽。
     *
     * 为什么必须真解析而不能"扫字节"：`profile_tier_level` 之后全是 **ue(v)/变长**字段
     * （`chroma_format_idc`、宽高、`bit_depth_*_minus8` 都是 ue(v)），任何"找固定偏移"的
     * 做法在换编码器/换 profile 时都会静默读错位，而读错位的**症状是 hvcC 里 profile/位深
     * 声明错**（解码端按错位深解释 ⇒ 颜色全错，且不报错）。
     *
     * @throws IllegalArgumentException 不是 SPS / 位流越界（必需的 12 个字段读不出来）
     */
    fun parseSps(sps: ByteArray): Sps {
        require(nalType(sps) == NAL_SPS) { "不是 SPS：nal_unit_type=${nalType(sps)}" }
        val br = Bits(unescape(sps, 2))
        br.skip(4)                                       // sps_video_parameter_set_id
        val sml = br.u(3)                                // sps_max_sub_layers_minus1
        val nesting = br.u(1) == 1                       // sps_temporal_id_nesting_flag
        val profileSpace = br.u(2)
        val tier = br.u(1)
        val profileIdc = br.u(5)
        val compat = br.u(32)
        var cons = 0L
        repeat(6) { cons = (cons shl 8) or br.u(8).toLong() }   // general_constraint_indicator_flags
        val level = br.u(8)
        if (sml > 0) {                                   // profile_tier_level 的 sub-layer 部分
            repeat(sml) { br.skip(2) }                   // sub_layer_profile_present / level_present
            repeat(8 - sml) { br.skip(2) }               // reserved_zero_2bits
            repeat(sml) { repeat(4) { br.ue() } }        // sub_layer profile/level 字段
        }
        br.ue()                                          // sps_seq_parameter_set_id
        val chroma = br.ue()
        val sep = chroma == 3 && br.u(1) == 1            // separate_colour_plane_flag
        br.ue(); br.ue()                                 // pic_width/height_in_luma_samples
        if (br.u(1) == 1) repeat(4) { br.ue() }           // conformance_window_flag + 4 个 offset
        val bdl = br.ue()
        val bdc = br.ue()
        val log2MaxPocLsb = br.ue() + 4

        // 以下属于**语法自检**：失败只降级 fullyParsed，不影响上面已读出的字段（hvcC 只用上面）
        val ok = runCatching {
            parseSpsRest(br, sml, log2MaxPocLsb)
            trailingBitsOk(br)
        }.getOrDefault(false)
        return Sps(
            profileSpace, tier, profileIdc, compat, cons, level, sml, nesting,
            chroma, sep, bdl, bdc, ok,
        )
    }

    /** SPS 从 `sps_sub_layer_ordering_info_present_flag` 到 `sps_extension_present_flag`。 */
    private fun parseSpsRest(br: Bits, sml: Int, log2MaxPocLsb: Int) {
        val all = br.u(1) == 1                           // sps_sub_layer_ordering_info_present_flag
        for (i in (if (all) 0 else sml)..sml) { br.ue(); br.ue(); br.ue() }
        repeat(6) { br.ue() }                            // log2_min/diff luma CB & TB、两个 hierarchy depth
        if (br.u(1) == 1 && br.u(1) == 1) scalingListData(br)   // scaling_list_enabled / data_present
        br.skip(2)                                       // amp_enabled_flag + sample_adaptive_offset_enabled_flag
        if (br.u(1) == 1) {                              // pcm_enabled_flag
            br.skip(8)                                   // pcm_sample_bit_depth_luma/chroma_minus1
            br.ue(); br.ue(); br.skip(1)                 // log2 min/diff PCM CB、pcm_loop_filter_disabled
        }
        val nStRps = br.ue()
        check(nStRps <= 64) { "num_short_term_ref_pic_sets=$nStRps 非法" }
        val deltaPocs = IntArray(nStRps)
        for (i in 0 until nStRps) {
            val inter = i != 0 && br.u(1) == 1           // inter_ref_pic_set_prediction_flag
            if (inter) {
                if (i == nStRps) br.ue()                 // delta_idx_minus1（i==nStRps 时才在（规范笔误保留））
                br.skip(1); br.ue()                      // delta_rps_sign、abs_delta_rps_minus1
                for (j in 0..deltaPocs[i - 1]) if (br.u(1) == 0) br.skip(1)  // used_by_curr_pic / use_delta
                deltaPocs[i] = deltaPocs[i - 1] + 1
            } else {
                val neg = br.ue(); val pos = br.ue()
                check(neg + pos <= 32) { "st_ref_pic_set 条目数 ${neg + pos} 非法" }
                repeat(neg) { br.ue(); br.skip(1) }
                repeat(pos) { br.ue(); br.skip(1) }
                deltaPocs[i] = neg + pos
            }
        }
        if (br.u(1) == 1) {                              // long_term_ref_pics_present_flag
            repeat(br.ue()) { br.skip(log2MaxPocLsb); br.skip(1) }   // lt_ref_pic_poc_lsb_sps u(v)
        }
        br.skip(2)                                       // sps_temporal_mvp_enabled_flag + strong_intra_smoothing
        if (br.u(1) == 1) vuiParameters(br)              // vui_parameters_present_flag
        check(br.u(1) == 0) { "SPS 带 sps_extension_*（本解析器未覆盖），位流自检降级" }
    }

    /** `scaling_list_data()`（H.265 7.3.4）—— 纯跳过，但长度必须按语法算。 */
    private fun scalingListData(br: Bits) {
        for (sizeId in 0..3) {
            val step = if (sizeId == 3) 3 else 1
            var matrixId = 0
            while (matrixId < 6) {
                if (br.u(1) == 0) {
                    br.ue()                              // scaling_list_pred_matrix_id_delta
                } else {
                    val coefNum = minOf(64, 1 shl (4 + (sizeId shl 1)))
                    if (sizeId > 1) br.se()              // scaling_list_dc_coef_minus8
                    repeat(coefNum) { br.se() }          // scaling_list_delta_coef
                }
                matrixId += step
            }
        }
    }

    /** `vui_parameters()`（H.265 E.2.1）。`vui_hrd_parameters_present_flag=1` 时拒绝自检（未覆盖）。 */
    private fun vuiParameters(br: Bits) {
        if (br.u(1) == 1 && br.u(8) == 255) { br.skip(16); br.skip(16) }   // aspect_ratio_idc == EXTENDED_SAR
        if (br.u(1) == 1) br.skip(1)                     // overscan_appropriate_flag
        if (br.u(1) == 1) {                              // video_signal_type_present_flag
            br.skip(4)                                   // video_format(3) + video_full_range_flag(1)
            if (br.u(1) == 1) br.skip(24)                // colour_primaries/transfer/matrix_coeffs
        }
        if (br.u(1) == 1) { br.ue(); br.ue() }           // chroma_loc_info（top/bottom_field）
        br.skip(3)                                       // neutral_chroma / field_seq / frame_field_info
        if (br.u(1) == 1) repeat(4) { br.ue() }           // default_display_window_flag + 4 个 offset
        if (br.u(1) == 1) {                              // vui_timing_info_present_flag
            br.skip(64)                                  // vui_num_units_in_tick + vui_time_scale
            if (br.u(1) == 1) br.ue()                    // vui_num_ticks_poc_diff_one_minus1
            check(br.u(1) == 0) { "VUI 带 hrd_parameters（本解析器未覆盖），位流自检降级" }
        }
        if (br.u(1) == 1) {                              // bitstream_restriction_flag
            br.skip(3)                                   // tiles_fixed / mv_over_pic_boundaries / restricted_ref_lists
            repeat(5) { br.ue() }                        // min_spatial_segmentation_idc … log2_max_mv_length_vertical
        }
    }

    /** 停在合法的 `rbsp_trailing_bits`（`1` + 全 `0`，最多 8 位）⇒ 位流消费自洽。 */
    private fun trailingBitsOk(br: Bits): Boolean {
        val left = br.left
        if (left <= 0 || left > 8) return false
        if (br.u(1) != 1) return false
        while (br.left > 0) if (br.u(1) != 0) return false
        return true
    }

    /** NAL 的 EBSP → RBSP：去掉 emulation prevention byte（`00 00 03 xx` → `00 00 xx`）。 */
    private fun unescape(nal: ByteArray, from: Int): ByteArray {
        val out = ByteArrayOutputStream(nal.size - from)
        var i = from
        while (i < nal.size) {
            if (i + 2 < nal.size && nal[i] == ZERO && nal[i + 1] == ZERO && nal[i + 2] == 3.toByte()) {
                out.write(0); out.write(0); i += 3
            } else {
                out.write(nal[i].toInt() and 0xFF); i++
            }
        }
        return out.toByteArray()
    }

    /** 按位读（大端、MSB-first），越界即抛（不允许静默读 0 位）。 */
    private class Bits(private val b: ByteArray) {
        var p = 0
        val left: Int get() = b.size * 8 - p

        fun u(n: Int): Int {
            if (n == 0) return 0
            if (p + n > b.size * 8) error("SPS 位流越界：需要 $n 位，仅剩 $left 位（pos=$p）")
            var v = 0
            repeat(n) {
                v = (v shl 1) or ((b[p shr 3].toInt() shr (7 - (p and 7))) and 1)
                p++
            }
            return v
        }

        fun skip(n: Int) {
            if (p + n > b.size * 8) error("SPS 位流越界：跳过 $n 位，仅剩 $left 位（pos=$p）")
            p += n
        }

        /** ue(v)：前导零个数 z ⇒ 值 = 2^z − 1 + 后续 z 位。 */
        fun ue(): Int {
            var z = 0
            while (u(1) == 0) {
                z++
                if (z > 31) error("ue(v) 前导零过多（pos=$p）")
            }
            return if (z == 0) 0 else (1 shl z) - 1 + u(z)
        }

        /** se(v)：ue(v) 的符号映射（奇数 → 正）。 */
        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }

    // ══ Annex-B → hvcC ═════════════════════════════════════════════════════════

    /**
     * Annex-B（VPS/SPS/PPS 参数集）→ `HEVCDecoderConfigurationRecord`。
     *
     * @param numTemporalLayers `byte21` 的 3 位字段。默认 1（"本码流有 1 个时间层"）。
     *   ⚠ 真机 `MediaMuxer` 产物写的是 **0**（见 [HevcAnnexBTest] 的往返判据）—— 两者都被
     *   解码端接受（该字段只影响时间层子码流提取，单帧静态图无子码流）；需要逐字节对齐
     *   muxer 产物时显式传 0。
     * @param temporalIdNested `byte21` 的 1 位字段，默认 true（同 SPS 的
     *   `sps_temporal_id_nesting_flag`）。
     */
    fun toHvcc(
        annexB: ByteArray,
        lengthSizeMinusOne: Int = LEN_SIZE_4,
        numTemporalLayers: Int = 1,
        temporalIdNested: Boolean = true,
    ): ByteArray {
        require(lengthSizeMinusOne in intArrayOf(0, 1, 3)) {
            "lengthSizeMinusOne=$lengthSizeMinusOne 非法（0/1/3 ⇒ 1/2/4 字节前缀）"
        }
        val nals = splitAnnexB(annexB)
        require(nals.isNotEmpty()) { "Annex-B 里没有起始码（len=${annexB.size}），无法切出参数集" }

        val vps = nals.filter { nalType(it) == NAL_VPS }
        val sps = nals.filter { nalType(it) == NAL_SPS }
        val pps = nals.filter { nalType(it) == NAL_PPS }
        require(sps.size == 1) {
            "需要恰好 1 个 SPS，实际 ${sps.size} 个（NAL 总数 ${nals.size}，" +
                "类型 ${nals.map { nalType(it) }}）"
        }
        // 边界：只有 SPS+PPS（无 VPS）也允许 —— hvcC 的 numOfArrays 按实际有的类型写
        val arrays = listOf(NAL_VPS to vps, NAL_SPS to sps, NAL_PPS to pps).filter { it.second.isNotEmpty() }

        val s = parseSps(sps[0])
        val out = ByteArrayOutputStream(HVCC_FIXED + 16 + arrays.sumOf { it.second.sumOf { n -> n.size } })
        out.write(1)                                                   // configurationVersion
        out.write((s.profileSpace shl 6) or (s.tierFlag shl 5) or s.profileIdc)
        out.write(u32(s.compatFlags))                                  // general_profile_compatibility_flags
        for (i in 5 downTo 0) out.write(((s.constraintFlags ushr (i * 8)) and 0xFF).toInt())
        out.write(s.levelIdc)                                          // general_level_idc
        out.write(u16(0xF000))                                         // min_spatial_segmentation_idc=0 + reserved
        out.write(0xFC)                                                // parallelismType=0 + reserved
        out.write(0xFC or s.chromaFormatIdc)
        out.write(0xF8 or s.bitDepthLumaMinus8)
        out.write(0xF8 or s.bitDepthChromaMinus8)
        out.write(u16(0))                                              // avgFrameRate=0
        out.write(
            (0 shl 6) or ((numTemporalLayers and 7) shl 3) or
                ((if (temporalIdNested) 1 else 0) shl 2) or (lengthSizeMinusOne and 3),
        )
        out.write(arrays.size)                                         // numOfArrays
        for ((type, list) in arrays) {
            out.write(0x80 or type)                                    // array_completeness=1
            out.write(u16(list.size))
            for (n in list) { out.write(u16(n.size)); out.write(n) }
        }
        return out.toByteArray()
    }

    /** hvcC → 参数集 NAL（VPS/SPS/PPS，按数组顺序）—— 往返测试的反向提取。 */
    fun parseHvccArrays(hvcc: ByteArray): List<ByteArray> {
        require(hvcc.size >= HVCC_FIXED) { "hvcC 长度 ${hvcc.size} < $HVCC_FIXED" }
        require((hvcc[0].toInt() and 0xFF) == 1) { "configurationVersion != 1" }
        val out = ArrayList<ByteArray>()
        var o = HVCC_FIXED
        repeat(hvcc[22].toInt() and 0xFF) {
            o++                                                        // array_completeness | NAL_unit_type
            val cnt = (u16At(hvcc, o)); o += 2
            repeat(cnt) {
                val len = u16At(hvcc, o); o += 2
                require(o + len <= hvcc.size) { "hvcC 里第 $it 个 NAL 长度 $len 越界" }
                out += hvcc.copyOfRange(o, o + len); o += len
            }
        }
        return out
    }

    // ══ 帧数据：Annex-B → NAL 长度前缀 ═════════════════════════════════════════

    /**
     * 判定帧数据形态（**实测探测**，不靠猜）。
     *
     * 顺序刻意如此：**先试长度前缀链**。因为 4 字节前缀 `00 00 01 XX` 与 3 字节起始码
     * `00 00 01` 在字节上无法直接区分（真机 golden 的 IDR 就以 `00 00 01 1a` 开头，
     * 而 `1a` 作 NAL 头是 RASL_N —— 不能作为图像首 NAL）。链式解析的判据是
     * "长度前缀 + 合法 NAL 头 + **恰好走完**整个 buffer"：
     *   · `len >= 2`：HEVC NAL 头固定 2 B ⇒ 任何 `< 2` 的长度都不可能是长度前缀；
     *     这一条就足以否掉 Annex-B 的 `00 00 00 01`（按 4 字节长度解读 = 1）。
     *   · NAL 类型 `!= 0`（0 是保留/非法）。
     *   · 链尾必须**精确等于** buffer 长度（多一字节少一字节都不认）。
     */
    fun frameMode(frame: ByteArray, lengthSizeMinusOne: Int): FrameMode {
        val lenSize = lengthSizeMinusOne + 1
        if (isLengthPrefixedChain(frame, lenSize)) return FrameMode.LEN_PREFIXED
        if (splitAnnexB(frame).isNotEmpty()) return FrameMode.ANNEXB
        return FrameMode.UNKNOWN
    }

    /** 长度前缀链自洽性（判据见 [frameMode]）。 */
    fun isLengthPrefixedChain(frame: ByteArray, lenSize: Int): Boolean {
        if (lenSize !in 1..4 || frame.size < lenSize + 2) return false
        var o = 0
        var n = 0
        while (o < frame.size) {
            if (o + lenSize + 2 > frame.size) return false
            var len = 0
            for (k in 0 until lenSize) len = (len shl 8) or (frame[o + k].toInt() and 0xFF)
            if (len < 2 || o + lenSize + len > frame.size) return false
            val hdr = frame[o + lenSize].toInt() and 0xFF
            if ((hdr and 0x80) != 0) return false                     // forbidden_zero_bit
            if (((hdr ushr 1) and 0x3F) == 0) return false            // nal_unit_type == 0
            o += lenSize + len
            n++
        }
        return n > 0 && o == frame.size
    }

    /**
     * 帧数据对齐到 hvcC 声明的 `lengthSizeMinusOne`：
     * Annex-B ⇒ 切 NAL 后逐个加长度前缀；已是长度前缀 ⇒ **原样返回**（防双转）。
     *
     * @param assertSelfCheck 转换后**再判定一次**形态，必须变成长度前缀链，
     *   否则抛 —— 这是"双转 / 误判"的兜底断言（生产路径开启）。
     */
    fun frameToLengthPrefixed(
        frame: ByteArray,
        lengthSizeMinusOne: Int,
        assertSelfCheck: Boolean = true,
    ): ByteArray {
        require(lengthSizeMinusOne in intArrayOf(0, 1, 3)) {
            "lengthSizeMinusOne=$lengthSizeMinusOne 非法（0/1/3 ⇒ 1/2/4 字节前缀）"
        }
        val lenSize = lengthSizeMinusOne + 1
        when (frameMode(frame, lengthSizeMinusOne)) {
            FrameMode.LEN_PREFIXED -> return frame          // 防双转：绝不二次加前缀
            FrameMode.ANNEXB -> {
                val nals = splitAnnexB(frame)
                require(nals.isNotEmpty()) { "Annex-B 帧切不出 NAL（len=${frame.size}）" }
                val out = ByteArrayOutputStream(frame.size + nals.size * lenSize)
                for (n in nals) {
                    for (i in lenSize - 1 downTo 0) out.write((n.size ushr (i * 8)) and 0xFF)
                    out.write(n)
                }
                val r = out.toByteArray()
                if (assertSelfCheck) {
                    check(isLengthPrefixedChain(r, lenSize)) {
                        "帧数据 Annex-B → 长度前缀转换后自检失败（in=${frame.size} out=${r.size} " +
                            "nals=${nals.size} lenSize=$lenSize）—— 拒绝把不确定的字节写进 HEIF item data"
                    }
                }
                return r
            }
            FrameMode.UNKNOWN -> error(
                "帧数据既不是 Annex-B（无起始码）也不是 ${lenSize} 字节长度前缀链（len=${frame.size}，" +
                    "首 8 字节 ${frame.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }}）",
            )
        }
    }

    // ── 小工具 ────────────────────────────────────────────────────────────────

    private fun u16(v: Int) = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun u32(v: Int) = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    private fun u16At(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
}
