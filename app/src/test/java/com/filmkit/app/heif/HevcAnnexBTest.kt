package com.filmkit.app.heif

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Annex-B ⇄ hvcC 转换 + H.265 SPS 解析的 JVM 判据（卡 FIX3）。
 *
 * 数据来源是**真机 golden**（`app/src/test/resources/heif/`，vivo X300 / 0.11.x）：
 * - `probe-main10-p010-*.heic` —— 其实是 **MP4**（`ftyp/moov/trak/stbl/stsd/hvc1/hvcC` + mdat），
 *   里面是 `MediaMuxer` **转换后**的 hvcC（106 B）与 **4 字节长度前缀**帧（286 B）；
 * - `selfmade-probe-main10-p010-*.heic` —— 自研容器产物，hvcC 与上面**逐字节相同**。
 *
 * 判据分五组：① 逐字节往返 ② SPS 解析字段（含 scaling_list/VUI 跳过）③ 帧数据形态判定与
 * 双转防护 ④ 边界（无 VPS / 3 字节起始码 / trailing zeros / chroma==3）⑤ 端到端规范化。
 */
class HevcAnnexBTest {

    // ══ golden 提取（测试内自带最小 MP4 box 解析，不引依赖）══════════════════════

    private val mp4: ByteArray by lazy { res("probe-main10-p010-1791965865303.heic") }
    private val selfMade: ByteArray by lazy { res("selfmade-probe-main10-p010-1791965865303.heic") }

    private fun res(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("heif/$name")!!.use { it.readBytes() }

    /** 线性定位 box type，用前面的 4 字节 size 界定 payload（测试用，够稳）。 */
    private fun boxPayload(d: ByteArray, type: String): ByteArray {
        val t = type.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 4..d.size - 4 - t.size) {
            for (j in t.indices) if (d[i + j] != t[j]) continue@outer
            val size = u32(d, i - 4)
            val start = i + 4
            return if (size == 1) {
                val large = u64(d, i + 4)
                d.copyOfRange(i + 12, (i - 4 + large).toInt())
            } else {
                d.copyOfRange(start, i - 4 + size)
            }
        }
        error("找不到 box $type")
    }

    private fun u32(d: ByteArray, o: Int) =
        ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or
            ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)

    private fun u64(d: ByteArray, o: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (d[o + k].toLong() and 0xFF)
        return v
    }

    private val goldenHvcc: ByteArray by lazy { boxPayload(mp4, "hvcC") }
    private val goldenFrame: ByteArray by lazy { boxPayload(mp4, "mdat") }

    /** 长度前缀链 → NAL 列表（golden 帧的反向拆解，与生产代码路径独立）。 */
    private fun unchunk(frame: ByteArray, lenSize: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var o = 0
        while (o < frame.size) {
            var len = 0
            for (k in 0 until lenSize) len = (len shl 8) or (frame[o + k].toInt() and 0xFF)
            o += lenSize
            out += frame.copyOfRange(o, o + len)
            o += len
        }
        return out
    }

    // ══ ① 逐字节往返：golden hvcC → Annex-B → hvcC ══════════════════════════════

    @Test
    fun `golden hvcC 反向拆成 Annex-B 再转回来逐字节相同`() {
        val h = goldenHvcc
        assertEquals("golden hvcC 长度（muxer 产物）", 106, h.size)

        val nals = HevcAnnexB.parseHvccArrays(h)
        assertEquals("VPS/SPS/PPS 各一个", 3, nals.size)
        assertEquals(listOf(32, 33, 34), nals.map { HevcAnnexB.nalType(it) })
        assertEquals("VPS 长度", 23, nals[0].size)
        assertEquals("SPS 长度", 36, nals[1].size)
        assertEquals("PPS 长度", 9, nals[2].size)

        val annexB = HevcAnnexB.toAnnexB(nals)
        assertEquals("Annex-B = 3 段 4 字节起始码 + NAL", 3 * 4 + 68, annexB.size)

        // byte21 的 numTemporalLayers/temporalIdNested 是**容器级声明**（不是 SPS 语法派生：
        // 真机 muxer 写 0/0，规范允许 1/1）⇒ 往返时按源 record 回填，其余全部由 SPS/golden 决定
        val roundTrip = HevcAnnexB.toHvcc(
            annexB, HevcAnnexB.LEN_SIZE_4,
            numTemporalLayers = (h[21].toInt() ushr 3) and 7,
            temporalIdNested = ((h[21].toInt() ushr 2) and 1) == 1,
        )
        assertArrayEquals("往返必须逐字节还原 muxer 产出的 hvcC", h, roundTrip)
    }

    @Test
    fun `默认参数（1 时间层）与 golden 的差异仅限 byte21 的 4 位声明`() {
        val h = goldenHvcc
        val def = HevcAnnexB.toHvcc(HevcAnnexB.toAnnexB(HevcAnnexB.parseHvccArrays(h)))
        assertEquals("长度一致", h.size, def.size)

        val diff = h.indices.filter { h[it] != def[it] }
        assertEquals("差异字节只有 byte21", listOf(21), diff)
        // golden = 0x03（numTemporalLayers=0/temporalIdNested=0）；默认 = 0b0000_1111? 不 ——
        // 默认 numTemporalLayers=1、temporalIdNested=1、lengthSizeMinusOne=3 ⇒ 0b0000_1111 = 0x0F
        assertEquals("golden byte21", 0x03, h[21].toInt() and 0xFF)
        assertEquals("默认 byte21", 0x0F, def[21].toInt() and 0xFF)
        assertEquals("差异只在 bit5..2（时间层声明）", 0x0C, (h[21].toInt() xor def[21].toInt()) and 0xFF)
        // 关键字段两边都必须一致
        assertEquals("lengthSizeMinusOne 不受影响", 3, def[21].toInt() and 3)
        assertEquals("profile_idc", h[1].toInt() and 0x1F, def[1].toInt() and 0x1F)
    }

    @Test
    fun `自研容器 golden 里的 hvcC 与 MP4 提取的逐字节相同`() {
        assertArrayEquals(
            "同一个探针样本的两条产出路径 hvcC 必须一致（106 B 的来源可追溯）",
            goldenHvcc, boxPayload(selfMade, "hvcC"),
        )
    }

    @Test
    fun `帧数据 4 字节前缀 → Annex-B → 4 字节前缀 逐字节往返`() {
        val f = goldenFrame
        assertEquals("golden 帧长度", 286, f.size)
        assertEquals("golden 帧是 4 字节长度前缀", HevcAnnexB.FrameMode.LEN_PREFIXED, HevcAnnexB.frameMode(f, 3))

        val nals = unchunk(f, 4)
        assertEquals("1 个 NAL", 1, nals.size)
        assertEquals("IDR_W_RADL", 19, HevcAnnexB.nalType(nals[0]))

        val annexB = HevcAnnexB.toAnnexB(nals)
        val back = HevcAnnexB.frameToLengthPrefixed(annexB, HevcAnnexB.LEN_SIZE_4)
        assertArrayEquals("帧往返必须逐字节还原", f, back)
    }

    // ══ ② SPS 解析（真机 golden + 构造样本）══════════════════════════════════════

    @Test
    fun `golden SPS 解析出 Main10 的正确字段且语法自检通过`() {
        val sps = HevcAnnexB.parseHvccArrays(goldenHvcc)[1]
        val s = HevcAnnexB.parseSps(sps)
        assertEquals("general_profile_space", 0, s.profileSpace)
        assertEquals("general_tier_flag", 0, s.tierFlag)
        assertEquals("general_profile_idc（10bit 期望 2 = Main10）", 2, s.profileIdc)
        assertEquals("general_profile_compatibility_flags", 0x20000000, s.compatFlags)
        assertEquals("general_constraint_indicator_flags", 0xb00000000000L, s.constraintFlags)
        assertEquals("general_level_idc", 123, s.levelIdc)
        assertEquals("chroma_format_idc（4:2:0）", 1, s.chromaFormatIdc)
        assertFalse("chroma != 3 ⇒ 无 separate_colour_plane_flag", s.separateColourPlane)
        assertEquals("bit_depth_luma_minus8（10bit）", 2, s.bitDepthLumaMinus8)
        assertEquals("bit_depth_chroma_minus8（10bit）", 2, s.bitDepthChromaMinus8)
        assertEquals("sps_max_sub_layers_minus1", 0, s.maxSubLayersMinus1)
        assertTrue("sps_temporal_id_nesting_flag", s.temporalIdNesting)
        assertTrue("整条 SPS 必须被语法完整消费（scaling_list/VUI 跳过正确）", s.fullyParsed)
    }

    /** golden 的 hvcC 固定头必须与从 SPS 派生的字段一致（两条独立路径互证）。 */
    @Test
    fun `hvcC 固定头字段与 SPS 解析结果一致`() {
        val h = goldenHvcc
        val s = HevcAnnexB.parseSps(HevcAnnexB.parseHvccArrays(h)[1])
        assertEquals("profile_idc", s.profileIdc, h[1].toInt() and 0x1F)
        assertEquals("compat flags", s.compatFlags, u32(h, 2))
        assertEquals("level_idc", s.levelIdc, h[12].toInt() and 0xFF)
        assertEquals("chromaFormat", s.chromaFormatIdc, h[16].toInt() and 3)
        assertEquals("bitDepthLumaMinus8", s.bitDepthLumaMinus8, h[17].toInt() and 7)
        assertEquals("bitDepthChromaMinus8", s.bitDepthChromaMinus8, h[18].toInt() and 7)
    }

    @Test
    fun `构造 SPS 带 scaling_list_data 与完整 VUI 时仍能完整消费位流`() {
        val sps = SpsBuilder(bitDepth = 2, chroma = 1, scalingList = true, vui = true).build()
        val s = HevcAnnexB.parseSps(sps)
        assertEquals(2, s.profileIdc)
        assertEquals(2, s.bitDepthLumaMinus8)
        assertEquals(2, s.bitDepthChromaMinus8)
        assertEquals(1, s.chromaFormatIdc)
        assertTrue(
            "scaling_list_data（64 个 se(v) × 多组）与 VUI（含 colour_description、" +
                "timing_info、bitstream_restriction）必须按语法精确跳过，否则位流对不上",
            s.fullyParsed,
        )
    }

    @Test
    fun `构造 SPS 覆盖 chroma_format_idc 等于 3 的 separate_colour_plane 分支`() {
        val s3 = HevcAnnexB.parseSps(SpsBuilder(bitDepth = 2, chroma = 3, separatePlane = true).build())
        assertEquals(3, s3.chromaFormatIdc)
        assertTrue("chroma_format_idc==3 ⇒ 必须读 separate_colour_plane_flag", s3.separateColourPlane)
        assertTrue("读了 extra 一位后位流仍要自洽", s3.fullyParsed)

        val s3b = HevcAnnexB.parseSps(SpsBuilder(bitDepth = 2, chroma = 3, separatePlane = false).build())
        assertFalse(s3b.separateColourPlane)
        assertTrue(s3b.fullyParsed)
    }

    @Test
    fun `构造 SPS 覆盖 10bit 之外的位深（8bit 与 12bit）`() {
        assertEquals(0, HevcAnnexB.parseSps(SpsBuilder(bitDepth = 0, chroma = 1).build()).bitDepthLumaMinus8)
        assertEquals(4, HevcAnnexB.parseSps(SpsBuilder(bitDepth = 4, chroma = 1).build()).bitDepthLumaMinus8)
    }

    /** 非 SPS 输入必须明确失败，不许静默返回默认值。 */
    @Test
    fun `parseSps 拒绝非 SPS 与非 AnnexB 输入`() {
        val pps = HevcAnnexB.parseHvccArrays(goldenHvcc)[2]
        val e = runCatching { HevcAnnexB.parseSps(pps) }.exceptionOrNull()
        assertTrue("必须是 IllegalArgumentException 且点明 nal_unit_type", e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("nal_unit_type=34"))
    }

    // ══ ③ 帧数据形态判定 + 双转防护 ══════════════════════════════════════════════

    /**
     * **关键回归**：golden 的 IDR 以 `00 00 01 1a` 开头 —— 字节上同时像 3 字节起始码。
     * 判定必须靠"长度前缀链自洽"（`len >= 2` + 合法 NAL 头 + 恰好走完），不能靠"含起始码"。
     */
    @Test
    fun `以 00 00 01 开头的 4 字节前缀帧不得被判成 AnnexB`() {
        val f = goldenFrame
        assertEquals("首 4 字节", "0000011a", f.take(4).joinToString("") { "%02x".format(it) })
        assertEquals(HevcAnnexB.FrameMode.LEN_PREFIXED, HevcAnnexB.frameMode(f, 3))
        assertTrue(HevcAnnexB.isLengthPrefixedChain(f, 4))
    }

    @Test
    fun `已是长度前缀的帧必须原样返回（防双转断言）`() {
        val f = goldenFrame
        val out = HevcAnnexB.frameToLengthPrefixed(f, HevcAnnexB.LEN_SIZE_4)
        assertSame("LEN_PREFIXED 分支必须是同一个实例（绝不二次加前缀）", f, out)
        assertArrayEquals(f, out)

        // 反面：如果实现犯"双转"，长度会变成 size + 4 且首 4 字节变成 286
        val doubleConverted = ByteArray(f.size + 4).also {
            it[0] = 0; it[1] = 0; it[2] = 1; it[3] = (f.size and 0xFF).toByte()
            f.copyInto(it, 4)
        }
        assertNotEquals("双转产物必须与正确产物不同", doubleConverted.size, out.size)
    }

    @Test
    fun `真机形态的 Annex-B 帧转成 4 字节前缀`() {
        // 造一段"真机 csd-0 风格的 Annex-B 帧"：AUD + IDR 两个 NAL
        val aud = byteArrayOf(0x46, 0x01, 0x50)
        val idr = ByteArray(64) { (it * 7 + 3).toByte() }.also { it[0] = 0x26; it[1] = 0x01 }
        val annexB = HevcAnnexB.toAnnexB(listOf(aud, idr))
        assertEquals(HevcAnnexB.FrameMode.ANNEXB, HevcAnnexB.frameMode(annexB, 3))

        val out = HevcAnnexB.frameToLengthPrefixed(annexB, HevcAnnexB.LEN_SIZE_4)
        assertEquals(4 + 3 + 4 + 64, out.size)
        assertEquals(3, u32(out, 0))
        assertEquals(35, HevcAnnexB.nalType(out.copyOfRange(4, 7)))
        assertEquals(64, u32(out, 7))
        assertEquals(19, HevcAnnexB.nalType(out.copyOfRange(11, 75)))
        assertTrue("转换后必须自称长度前缀链", HevcAnnexB.isLengthPrefixedChain(out, 4))
    }

    @Test
    fun `既无起始码也不是长度前缀链的帧必须失败`() {
        val junk = ByteArray(16) { 0x5A }
        assertEquals(HevcAnnexB.FrameMode.UNKNOWN, HevcAnnexB.frameMode(junk, 3))
        val e = runCatching { HevcAnnexB.frameToLengthPrefixed(junk, 3) }.exceptionOrNull()
        assertTrue("必须抛且带首 8 字节", e is IllegalStateException && e.message!!.contains("5a5a5a5a"))
    }

    @Test
    fun `长度前缀链判据拒绝自相矛盾的长度（链尾必须精确对齐）`() {
        // 声称 100 字节但只有 20 字节
        val truncated = byteArrayOf(0, 0, 0, 100, 0x26, 0x01) + ByteArray(14)
        assertFalse(HevcAnnexB.isLengthPrefixedChain(truncated, 4))
        // 长度 1 小于 HEVC NAL 头 2 字节 —— 这正是 Annex-B `00 00 00 01` 的形态
        val oneByte = byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0, 0)
        assertFalse(HevcAnnexB.isLengthPrefixedChain(oneByte, 4))
        // nal_unit_type == 0 非法
        val badType = byteArrayOf(0, 0, 0, 2, 0x00, 0x01)
        assertFalse(HevcAnnexB.isLengthPrefixedChain(badType, 4))
    }

    // ══ ④ 边界：起始码形态 / 无 VPS / trailing zeros ═════════════════════════════

    @Test
    fun `三字节起始码 00 00 01 与四字节混用都能切分`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5)
        val c = byteArrayOf(6)
        val stream = byteArrayOf(0, 0, 0, 1) + a + byteArrayOf(0, 0, 1) + b +
            byteArrayOf(0, 0, 0, 0, 1) + c          // 多一个前导零
        val nals = HevcAnnexB.splitAnnexB(stream)
        assertEquals(3, nals.size)
        assertArrayEquals(a, nals[0])
        assertArrayEquals(b, nals[1])
        assertArrayEquals(c, nals[2])
    }

    @Test
    fun `4 字节起始码的 zero_byte 不得留在上一个 NAL 尾部`() {
        val nals = HevcAnnexB.splitAnnexB(byteArrayOf(0, 0, 0, 1, 9, 9, 0, 0, 0, 1, 7, 7))
        assertEquals(2, nals.size)
        assertArrayEquals("上一个 NAL 尾部不能带 zero_byte", byteArrayOf(9, 9), nals[0])
        assertArrayEquals(byteArrayOf(7, 7), nals[1])
    }

    @Test
    fun `无 VPS 时 numOfArrays 按实际类型写（只有 SPS+PPS）`() {
        val sps = SpsBuilder(bitDepth = 2, chroma = 1).build()
        val pps = byteArrayOf(0x44.toByte(), 0x01, 0xC0.toByte(), 0x73, 0x16, 0x2C, 0x0E, 0xC6.toByte(), 0x40)
        val h = HevcAnnexB.toHvcc(HevcAnnexB.toAnnexB(listOf(sps, pps)))

        assertEquals("numOfArrays", 2, h[22].toInt() and 0xFF)
        assertEquals("第 1 组是 SPS", 33, h[23].toInt() and 0x3F)
        assertTrue("array_completeness=1", (h[23].toInt() and 0x80) != 0)
        assertEquals("SPS 个数", 1, ((h[24].toInt() and 0xFF) shl 8) or (h[25].toInt() and 0xFF))
        val nals = HevcAnnexB.parseHvccArrays(h)
        assertEquals(listOf(33, 34), nals.map { HevcAnnexB.nalType(it) })
        assertArrayEquals("SPS 内容原样", sps, nals[0])
        assertArrayEquals("PPS 内容原样", pps, nals[1])
    }

    @Test
    fun `Annex-B 尾部的 trailing zeros 不污染 NAL（hvcC 逐字节可复现）`() {
        val sps = SpsBuilder(bitDepth = 2, chroma = 1).build()
        val pps = byteArrayOf(0x44.toByte(), 0x01, 0xC0.toByte())
        val clean = HevcAnnexB.toHvcc(HevcAnnexB.toAnnexB(listOf(sps, pps)))
        val withZeros = HevcAnnexB.toHvcc(
            HevcAnnexB.toAnnexB(listOf(sps, pps)) + ByteArray(8),   // 尾部 8 个 0
        )
        assertArrayEquals("尾部 0 不得改变 record", clean, withZeros)
    }

    @Test
    fun `没有 SPS 或缺 SPS 多个 SPS 必须失败`() {
        val pps = byteArrayOf(0x44.toByte(), 0x01, 0xC0.toByte())
        val e = runCatching { HevcAnnexB.toHvcc(HevcAnnexB.toAnnexB(listOf(pps))) }.exceptionOrNull()
        assertTrue("必须点明需要 1 个 SPS", e is IllegalArgumentException && e.message!!.contains("1 个 SPS"))

        val sps = SpsBuilder(bitDepth = 2, chroma = 1).build()
        val e2 = runCatching { HevcAnnexB.toHvcc(HevcAnnexB.toAnnexB(listOf(sps, sps))) }.exceptionOrNull()
        assertTrue(e2 is IllegalArgumentException && e2.message!!.contains("实际 2 个"))

        val e3 = runCatching { HevcAnnexB.toHvcc(byteArrayOf(1, 2, 3, 4)) }.exceptionOrNull()
        assertTrue(e3 is IllegalArgumentException && e3.message!!.contains("没有起始码"))
    }

    @Test
    fun `长度前缀宽度 0 与 1 也按 hvcC 声明正确打包`() {
        val nal = byteArrayOf(0x26, 0x01, 9, 9)
        val annexB = HevcAnnexB.toAnnexB(listOf(nal))
        val one = HevcAnnexB.frameToLengthPrefixed(annexB, 0)
        assertArrayEquals(byteArrayOf(4, 0x26, 0x01, 9, 9), one)
        val two = HevcAnnexB.frameToLengthPrefixed(annexB, 1)
        assertArrayEquals(byteArrayOf(0, 4, 0x26, 0x01, 9, 9), two)
        for (ls in intArrayOf(0, 1, 3)) {
            val h = HevcAnnexB.toHvcc(HevcAnnexB.toAnnexB(listOf(SpsBuilder(2, 1).build())), ls)
            assertEquals("hvcC 必须如实声明", ls, h[21].toInt() and 3)
        }
        // 2 是保留值
        assertTrue(runCatching { HevcAnnexB.toHvcc(annexB, 2) }.exceptionOrNull() is IllegalArgumentException)
    }

    // ══ ⑤ 端到端：HevcCsd0 规范化 + 诊断字段 ════════════════════════════════════

    @Test
    fun `已是 hvcC 的 csd-0 直通（converted=false）且帧原样`() {
        val logs = mutableListOf<String>()
        val n = HevcCsd0.accept(goldenHvcc, goldenFrame) { logs += it }
        assertArrayEquals("hvcC 原样进容器", goldenHvcc, n.hvcc)
        assertSame("帧已是长度前缀 ⇒ 同一个实例", goldenFrame, n.frame)
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("\"converted\":false"))
        assertTrue(logs[0].contains("\"csdOutLen\":106"))
        assertTrue(logs[0].contains("\"frameMode\":\"len4\""))
        assertTrue(logs[0].contains("\"ok\":true"))
    }

    @Test
    fun `真机形态的 Annex-B csd-0 被转换且诊断字段齐全`() {
        val annexB = HevcAnnexB.toAnnexB(HevcAnnexB.parseHvccArrays(goldenHvcc))
        val annexBFrame = HevcAnnexB.toAnnexB(unchunk(goldenFrame, 4))
        val logs = mutableListOf<String>()
        val n = HevcCsd0.accept(annexB, annexBFrame) { logs += it }

        assertTrue("转换后必须是合法 hvcC", HevcCsd0.isConfigurationRecord(n.hvcc))
        assertEquals(1, logs.size)
        val f = logs[0]
        // 任务要求的全部字段（10bit 一轮定位的设施）
        for (kv in listOf(
            "\"converted\":true", "\"profile\":2", "\"bitDepthLumaMinus8\":2",
            "\"bitDepthChromaMinus8\":2", "\"chromaFormat\":1", "\"lengthSizeMinusOne\":3",
            "\"arrays\":3", "\"arrayTypes\":[32,33,34]", "\"csdInLen\":80", "\"csdOutLen\":106",
            "\"frameMode\":\"annexb\"", "\"ok\":true",
        )) {
            assertTrue("诊断必须含 $kv（实际：$f）", f.contains(kv))
        }
        // 帧也被转成 4 字节前缀
        assertArrayEquals("帧必须转成与 golden 相同的 4 字节前缀形态", goldenFrame, n.frame)
    }

    @Test
    fun `csd-0 缺失时诊断照发且 ok=false`() {
        val logs = mutableListOf<String>()
        val e = runCatching { HevcCsd0.accept(null, goldenFrame) { logs += it } }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("csd-0"))
        assertEquals("失败分支也必须发诊断", 1, logs.size)
        assertTrue(logs[0].contains("\"ok\":false"))
        assertTrue(logs[0].contains("\"csdInLen\":0"))
        assertTrue(logs[0].contains("\"head\":\"\""))
    }

    @Test
    fun `Annex-B 转换失败时诊断回落到输入字节（一轮定位不能断）`() {
        val junk = ByteArray(40) { 0x11 }
        val logs = mutableListOf<String>()
        runCatching { HevcCsd0.accept(junk, goldenFrame) { logs += it } }
        assertEquals(1, logs.size)
        assertTrue("失败诊断必须带 csdInLen", logs[0].contains("\"csdInLen\":40"))
        assertTrue("失败诊断必须回落到输入 head", logs[0].contains("\"head\":\"1111"))
        assertTrue(logs[0].contains("\"ok\":false"))
    }

    @Test
    fun `帧形态无法判定时抛且诊断记 frameMode=none`() {
        val logs = mutableListOf<String>()
        val junkFrame = ByteArray(16) { 0x5A }
        val e = runCatching { HevcCsd0.accept(goldenHvcc, junkFrame) { logs += it } }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("\"frameMode\":\"none\""))
        assertTrue("hvcC 本身合法 ⇒ 字段仍应读出", logs[0].contains("\"profile\":2"))
        assertTrue("ok 只描述 record 结构，帧失败由 photo.heif_failed 报", logs[0].contains("\"ok\":true"))
    }

    @Test
    fun `诊断字段在 hvcc 为 null 时全部取 -1 且不抛`() {
        val f = HevcCsd0.fields(null, null, false, "none")
        for (kv in listOf(
            "\"ver\":-1", "\"profile\":-1", "\"bitDepthLumaMinus8\":-1", "\"bitDepthChromaMinus8\":-1",
            "\"chromaFormat\":-1", "\"lengthSizeMinusOne\":-1", "\"arrays\":-1", "\"csdOutLen\":0",
            "\"arrayTypes\":[]", "\"head\":\"\"",
        )) {
            assertTrue("缺 csd 时必须 $kv（实际 $f）", f.contains(kv))
        }
    }
}

/**
 * 测试用 H.265 SPS 位流构造器（变异检验的对照组）。
 *
 * 为什么需要：真机 golden 的 SPS 是"最简形态"（无 scaling_list、无 VUI、chroma=1），
 * **覆盖不到**解析器里最容易写错的两段可变长语法。这里按 H.265 7.3.2.2.1 逐字段构造，
 * 于是"跳过长度算错"能立刻表现为 `fullyParsed=false`。
 */
internal class SpsBuilder(
    private val bitDepth: Int,
    private val chroma: Int,
    private val separatePlane: Boolean = false,
    private val scalingList: Boolean = false,
    private val vui: Boolean = false,
    private val profileIdc: Int = 2,
) {
    private val out = ByteArrayOutputStream()
    private var acc = 0
    private var nbits = 0

    private fun u(v: Int, n: Int) {
        for (i in n - 1 downTo 0) {
            acc = (acc shl 1) or ((v ushr i) and 1)
            nbits++
            if (nbits == 8) { out.write(acc); acc = 0; nbits = 0 }
        }
    }

    private fun ue(v: Int) {
        val n = 32 - Integer.numberOfLeadingZeros(v + 1)   // v+1 的位宽
        u(0, n - 1)
        u(v + 1, n)
    }

    private fun se(v: Int) = ue(if (v > 0) 2 * v - 1 else -2 * v)

    private fun flush() {
        if (nbits > 0) {
            u(1, 1); while (nbits != 0) u(0, 1)            // rbsp_trailing_bits
        } else {
            u(0x80, 8)
        }
    }

    fun build(): ByteArray {
        u(0x42, 8); u(0x01, 8)                             // NAL 头：type=33, layer=0, tid=1
        u(0, 4); u(0, 3); u(1, 1)                          // vps_id / max_sub_layers_minus1=0 / nesting=1
        u(0, 2); u(0, 1); u(profileIdc, 5)                 // profile_tier_level
        u(0x20000000, 32); u(0xB000, 16); u(0, 16); u(0, 16)
        u(123, 8)
        ue(0)                                              // sps_seq_parameter_set_id
        ue(chroma)
        if (chroma == 3) u(if (separatePlane) 1 else 0, 1)
        ue(1024); ue(256)
        u(0, 1)                                            // conformance_window_flag
        ue(bitDepth); ue(bitDepth)
        ue(4)                                              // log2_max_pic_order_cnt_lsb_minus4
        u(1, 1)                                            // sps_sub_layer_ordering_info_present_flag
        ue(1); ue(0); ue(0)
        ue(0); ue(0); ue(0); ue(0); ue(0); ue(0)           // 6 个 CB/TB/hierarchy 参数
        u(if (scalingList) 1 else 0, 1)
        if (scalingList) { u(1, 1); scalingListData() }
        u(1, 1); u(1, 1)                                   // amp / sao
        u(0, 1)                                            // pcm_enabled_flag
        ue(0)                                              // num_short_term_ref_pic_sets
        u(0, 1)                                            // long_term_ref_pics_present_flag
        u(1, 1); u(1, 1)                                   // temporal_mvp / strong_intra_smoothing
        u(if (vui) 1 else 0, 1)
        if (vui) vuiParameters()
        u(0, 1)                                            // sps_extension_present_flag
        flush()
        return escape(out.toByteArray())
    }

    /** 覆盖 `sizeId>1` 的 dc 系数与 64 个 delta 系数两条路径。 */
    private fun scalingListData() {
        for (sizeId in 0..3) {
            val step = if (sizeId == 3) 3 else 1
            var m = 0
            while (m < 6) {
                u(1, 1)                                    // scaling_list_pred_mode_flag
                val coefNum = minOf(64, 1 shl (4 + (sizeId shl 1)))
                if (sizeId > 1) se(0)
                repeat(coefNum) { se(0) }
                m += step
            }
        }
    }

    /** 走遍 VUI 的绝大多数分支（含 colour_description / timing_info / bitstream_restriction）。 */
    private fun vuiParameters() {
        u(0, 1)                                            // aspect_ratio_info_present_flag
        u(0, 1)                                            // overscan_info_present_flag
        u(1, 1); u(5, 3); u(1, 1); u(1, 1)                 // video_signal_type + full_range + colour_desc
        u(9, 8); u(16, 8); u(9, 8)                         // primaries / transfer / matrix (BT.2020)
        u(0, 1)                                            // chroma_loc_info_present_flag
        u(0, 1); u(0, 1); u(1, 1)                          // neutral / field_seq / frame_field_info
        u(0, 1)                                            // default_display_window_flag
        u(1, 1); u(1, 32); u(30, 32); u(0, 1); u(0, 1)     // timing_info（含 hrd 缺席）
        u(1, 1)                                            // bitstream_restriction_flag
        u(0, 1); u(1, 1); u(0, 1)
        ue(0); ue(2); ue(1); ue(15); ue(15)
    }

    /** EBSP：`00 00 0{0,1,2,3}` → `00 00 03 0{0,1,2,3}`（覆盖生产路径的 unescape）。 */
    private fun escape(b: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(b.size + 8)
        var zeros = 0
        for (x in b) {
            val v = x.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) { o.write(3); zeros = 0 }
            o.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return o.toByteArray()
    }
}
