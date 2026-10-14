package com.filmkit.app.heif

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `csd-0` 规范化 + 诊断的 JVM 判据（卡 FIX2 建立，卡 FIX3 扩展到 Annex-B 输入）。
 *
 * 覆盖四件：
 *  ① `lengthSizeMinusOne ∈ {0,1,3}` **三档都通过**，且帧数据按该档正确对齐后**原样**进容器
 *     （真机事故就是这一档被硬断言打成失败：X300 + 3072×4080 给的不是 3）；
 *  ② 非法 `csd-0`（既不是 hvcC 也转不出来 / 长度 < 23 / null）必须失败 —— 不允许退化成
 *     "什么都接受"；
 *  ③ 失败分支**也**发 `photo.heif_csd`；
 *  ④ Annex-B 形态的 `csd-0` 走转换路径（卡 FIX3 的根因，详见 [HevcAnnexB] 文件头）。
 */
class HevcCsd0Test {

    /**
     * 造一条 hvcC（ISO/IEC 14496-15 §8.3.3.1 表 1 的固定头）。
     * 与真机 `c2.mtk.hevc.encoder` 经 muxer 产出的 106 B record 同布局，只是把参数暴露出来。
     */
    private fun hvcc(
        lengthSizeMinusOne: Int,
        size: Int = 106,
        ver: Int = 1,
        profileIdc: Int = 2,
        bitDepthLumaMinus8: Int = 2,
        chromaFormat: Int = 1,
    ): ByteArray {
        val h = ByteArray(size)
        h[0] = ver.toByte()                          // configurationVersion
        h[1] = profileIdc.toByte()                   // profile_space=0 | tier=0 | profile_idc
        h[12] = 120                                   // general_level_idc（Main tier 4.0）
        h[16] = chromaFormat.toByte()                 // chroma_format_idc（低 2 位）
        h[17] = bitDepthLumaMinus8.toByte()           // bitDepthLumaMinus8（低 3 位）
        h[18] = bitDepthLumaMinus8.toByte()           // bitDepthChromaMinus8
        // constantFrameRate(2) | numTemporalLayers(3) | temporalIdNested(1) | lengthSizeMinusOne(2)
        h[21] = ((0 shl 6) or (1 shl 3) or (1 shl 2) or (lengthSizeMinusOne and 3)).toByte()
        h[22] = 3                                     // numOfArrays（VPS/SPS/PPS）
        if (size > 23) h[23] = 0x20                   // 第一组 array 的 NAL 类型字节（非零，便于肉眼核对）
        return h
    }

    /** 与 [hvcc] 的 `lengthSizeMinusOne` 同档的**单 NAL 长度前缀**帧（形态必须自洽）。 */
    private fun frame(lengthSizeMinusOne: Int): ByteArray {
        val nal = byteArrayOf(0x40, 1, 2, 3, 4, 5)    // nal_unit_type = 32 (VPS)
        val n = lengthSizeMinusOne + 1
        val out = ByteArray(n + nal.size)
        for (i in 0 until n) out[i] = ((nal.size ushr ((n - 1 - i) * 8)) and 0xFF).toByte()
        nal.copyInto(out, n)
        return out
    }

    // ══ ① 三档 lengthSizeMinusOne 都通过 + 帧按档对齐 ═══════════════════════════

    @Test
    fun `lengthSizeMinusOne 三档 0 1 3 都通过结构检查并产出容器`() {
        for (ls in listOf(0, 1, 3)) {
            val h = hvcc(ls)
            val raw = frame(ls)
            val logs = mutableListOf<String>()
            val n = HevcCsd0.accept(h, raw) { logs += it }   // 不抛 = 通过
            assertSame("已是 hvcC ⇒ 必须原样返回同一个字节数组", h, n.hvcc)
            assertSame("形态已对齐 ⇒ 帧也必须原样返回（防双转）", raw, n.frame)
            assertEquals("每档恰好一条诊断", 1, logs.size)
            assertTrue("诊断必须如实报出 lengthSizeMinusOne=$ls", logs[0].contains("\"lengthSizeMinusOne\":$ls"))
            assertTrue("诊断必须标记 ok=true（第 $ls 档）", logs[0].contains("\"ok\":true"))
            assertTrue("第 $ls 档 frameMode 必须报 len${ls + 1}", logs[0].contains("\"frameMode\":\"len${ls + 1}\""))

            // 三档都必须能真的装进容器，且 hvcC 与帧数据逐字节不变（不重打包、不改字段）
            val out = HeifContainerWriter.build(HeifContainerWriter.Image(n.hvcc, n.frame, 8, 8))
            val idx = indexOf(out, "hvcC")
            assertTrue("容器里必须能找到 hvcC", idx > 0)
            val payload = out.copyOfRange(idx + 4, idx + 4 + h.size)
            assertArrayEquals("hvcC 必须原样搬运（第 $ls 档）", h, payload)
            assertEquals("lengthSizeMinusOne 字段不得被改写", ls, payload[21].toInt() and 3)
            val mdat = indexOf(out, "mdat")
            assertArrayEquals("帧数据必须原样搬进 mdat（第 $ls 档）", raw, out.copyOfRange(mdat + 4, mdat + 4 + raw.size))
        }
    }

    /** `Box` 头之后就是 payload：`size(4) + type(4)` ⇒ 找到 type 的位置即 payload 起点。 */
    private fun indexOf(b: ByteArray, type: String): Int {
        val t = type.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..b.size - t.size) {
            for (j in t.indices) if (b[i + j] != t[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun `诊断字段含 len ver profile bitDepthLumaMinus8 lengthSizeMinusOne head`() {
        val h = hvcc(3)
        val f = HevcCsd0.fields(h, h, false, "len4")
        assertTrue("len（= csd-0 输入长度）", f.contains("\"len\":${h.size}"))
        assertTrue("ver", f.contains("\"ver\":1"))
        assertTrue("profile", f.contains("\"profile\":2"))
        assertTrue("bitDepthLumaMinus8", f.contains("\"bitDepthLumaMinus8\":2"))
        assertTrue("lengthSizeMinusOne", f.contains("\"lengthSizeMinusOne\":3"))
        assertTrue("head", f.contains("\"head\":\""))
        // head = 前 32 字节 hex ⇒ 64 个 hex 字符
        val head = f.substringAfter("\"head\":\"").substringBefore('"')
        assertEquals("head 必须是前 32 字节的 hex", 64, head.length)
        assertTrue("head 内容应为 hex", head.all { it in "0123456789abcdef" })
        // ver == 1 ⇒ 结构检查通过
        assertTrue(HevcCsd0.isConfigurationRecord(h))
    }

    @Test
    fun `诊断必须报出转换前后的长度与是否转换（FIX3 的一轮定位设施）`() {
        val h = hvcc(3)
        val f = HevcCsd0.fields(h, h, false, "len4")
        for (kv in listOf("\"csdInLen\":106", "\"csdOutLen\":106", "\"converted\":false", "\"arrayTypes\":[32,")) {
            assertTrue("缺 $kv（实际 $f）", f.contains(kv))
        }
    }

    // ══ ② 非法 csd-0 必须失败（不许退化成"什么都接受"）═══════════════════════════

    @Test
    fun `configurationVersion 不等于 1 且转不出 hvcC 时必须失败`() {
        // (a) 既不是 hvcC 也不是 Annex-B（无起始码 ⇒ 切不出参数集）：消息必须带输入长度与形态
        val junk = ByteArray(106) { 0x11 }
        val e = assertThrows(IllegalStateException::class.java) { HevcCsd0.accept(junk, frame(3)) {} }
        assertTrue("错误消息必须带输入长度（实际：${e.message}）", e.message!!.contains("len=106"))
        assertTrue("错误消息必须点明按 Annex-B 转过", e.message!!.contains("Annex-B"))

        // (b) 头部像 hvcC 但 configurationVersion != 1：必须走失败而不是"什么都接受"
        val bad = hvcc(3, ver = 0)
        val e2 = assertThrows(IllegalStateException::class.java) { HevcCsd0.accept(bad, frame(3)) {} }
        assertTrue("必须说明 csd-0 不可用（实际：${e2.message}）", e2.message!!.contains("csd-0"))
    }

    @Test
    fun `长度小于 23 必须失败`() {
        for (size in intArrayOf(1, 22)) {
            val bad = ByteArray(size).also { it[0] = 1 }
            assertThrows(IllegalStateException::class.java) { HevcCsd0.accept(bad, frame(3)) {} }
        }
    }

    @Test
    fun `csd-0 缺失 null 与空数组必须失败`() {
        val e = assertThrows(IllegalStateException::class.java) { HevcCsd0.accept(null, frame(3)) {} }
        assertTrue("必须点明编码器没给 csd-0", e.message!!.contains("csd-0"))
        assertTrue("必须报 len=0", e.message!!.contains("len=0"))
        assertThrows(IllegalStateException::class.java) { HevcCsd0.accept(ByteArray(0), frame(3)) {} }
    }

    // ══ ③ 失败分支也发 photo.heif_csd ═══════════════════════════════════════════

    @Test
    fun `失败分支无条件发 photo_heif_csd`() {
        // ver != 1 且不是 Annex-B：诊断必须在抛之前发出
        val logs1 = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            HevcCsd0.accept(hvcc(3, ver = 2), frame(3)) { logs1 += it }
        }
        assertEquals("结构非法也必须发诊断", 1, logs1.size)
        assertTrue("失败诊断也要带 lengthSizeMinusOne", logs1[0].contains("\"lengthSizeMinusOne\""))
        assertTrue("失败诊断必须标 ok=false", logs1[0].contains("\"ok\":false"))

        // 太短
        val logs2 = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            HevcCsd0.accept(ByteArray(4), frame(3)) { logs2 += it }
        }
        assertEquals("长度不足也必须发诊断", 1, logs2.size)

        // 缺失（编码器没吐 csd-0）
        val logs3 = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) { HevcCsd0.accept(null, frame(3)) { logs3 += it } }
        assertEquals("csd-0 缺失也必须发诊断", 1, logs3.size)
        assertTrue("缺失时 len=0", logs3[0].contains("\"len\":0"))
        assertTrue("缺失时数值字段取 -1", logs3[0].contains("\"ver\":-1"))
    }

    @Test
    fun `事件名与日志口径固定为 photo_heif_csd`() {
        assertEquals("photo.heif_csd", HevcCsd0.EVENT)
        assertEquals("hvcC 最小长度", 23, HevcCsd0.MIN_SIZE)
    }

    // ══ ④ Annex-B 形态（卡 FIX3 的根因路径）══════════════════════════════════════

    /** 真机 `csd-0` 的形态：`00 00 00 01` + VPS/SPS/PPS（79 B 那类）。必须能转成 hvcC。 */
    @Test
    fun `Annex-B csd-0 被转换成 hvcC 且帧同步长度前缀化`() {
        val sps = SpsBuilder(bitDepth = 2, chroma = 1).build()
        val vps = byteArrayOf(0x40, 0x01, 0x0C, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x02, 0x20)
        val pps = byteArrayOf(0x44, 0x01, 0xC0.toByte(), 0x73, 0x16, 0x2C, 0x0E, 0xC6.toByte(), 0x40)
        val annexB = HevcAnnexB.toAnnexB(listOf(vps, sps, pps))
        val annexBFrame = HevcAnnexB.toAnnexB(listOf(byteArrayOf(0x26, 0x01, 7, 7, 7)))

        val logs = mutableListOf<String>()
        val n = HevcCsd0.accept(annexB, annexBFrame) { logs += it }

        assertTrue("转换后必须是合法 hvcC", HevcCsd0.isConfigurationRecord(n.hvcc))
        assertEquals("numOfArrays", 3, n.hvcc[22].toInt() and 0xFF)
        assertEquals("profile（Main10）", 2, n.hvcc[1].toInt() and 0x1F)
        assertEquals("bitDepthLumaMinus8", 2, n.hvcc[17].toInt() and 7)
        assertEquals("lengthSizeMinusOne 固定 3（4 字节前缀）", 3, n.hvcc[21].toInt() and 3)
        assertArrayEquals(
            "帧必须转成 4 字节前缀",
            byteArrayOf(0, 0, 0, 5, 0x26, 0x01, 7, 7, 7), n.frame,
        )
        val f = logs.single()
        assertTrue(f.contains("\"converted\":true"))
        assertTrue(f.contains("\"csdInLen\":${annexB.size}"))
        assertTrue(f.contains("\"csdOutLen\":${n.hvcc.size}"))
        assertTrue(f.contains("\"frameMode\":\"annexb\""))
    }
}
