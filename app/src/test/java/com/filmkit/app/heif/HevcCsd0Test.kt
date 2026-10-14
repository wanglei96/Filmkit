package com.filmkit.app.heif

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `csd-0`（HEVCDecoderConfigurationRecord）结构检查 + 诊断的 JVM 判据（卡 FIX2）。
 *
 * 覆盖三件：
 *  ① `lengthSizeMinusOne ∈ {0,1,3}` **三档都通过**结构检查，并且**原样**进容器
 *     （真机事故就是这一档被硬断言打成失败：X300 + 3072×4080 给的不是 3）；
 *  ② 非法 `csd-0`（`h[0] != 1` / 长度 < 23 / null）必须失败 —— 不允许退化成"什么都接受"；
 *  ③ 失败分支**也**发 `photo.heif_csd`（`emit` 在结构检查之前）—— 硬判据 1 ③。
 */
class HevcCsd0Test {

    /**
     * 造一条 hvcC（ISO/IEC 14496-15 §8.3.3.1 表 1 的固定头）。
     * 与真机 `c2.mtk.hevc.encoder` 的 106 B record 同布局，只是把参数暴露出来做三档测试。
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

    private fun frame(): ByteArray = byteArrayOf(0, 0, 0, 6, 0x40, 1, 2, 3, 4, 5)

    // ══ ① 三档 lengthSizeMinusOne 都通过 + 原样进容器 ═══════════════════════════

    @Test
    fun `lengthSizeMinusOne 三档 0 1 3 都通过结构检查并产出容器`() {
        for (ls in listOf(0, 1, 3)) {
            val h = hvcc(ls)
            val logs = mutableListOf<String>()
            val accepted = HevcCsd0.accept(h) { logs += it }   // 不抛 = 通过
            assertArrayEquals("accept 必须原样返回同一个字节数组", h, accepted)
            assertEquals("每档恰好一条诊断", 1, logs.size)
            assertTrue("诊断必须如实报出 lengthSizeMinusOne=$ls", logs[0].contains("\"lengthSizeMinusOne\":$ls"))
            assertTrue("诊断必须标记 ok=true（第 $ls 档）", logs[0].contains("\"ok\":true"))

            // 三档都必须能真的装进容器，且 hvcC 与帧数据逐字节不变（不重打包、不改字段）
            val out = HeifContainerWriter.build(HeifContainerWriter.Image(h, frame(), 8, 8))
            val idx = indexOf(out, "hvcC")
            assertTrue("容器里必须能找到 hvcC", idx > 0)
            val payload = out.copyOfRange(idx + 4, idx + 4 + h.size)
            assertArrayEquals("hvcC 必须原样搬运（第 $ls 档）", h, payload)
            assertEquals("lengthSizeMinusOne 字段不得被改写", ls, payload[21].toInt() and 3)
            val mdat = indexOf(out, "mdat")
            assertArrayEquals("帧数据必须原样搬进 mdat", frame(), out.copyOfRange(mdat + 4, mdat + 4 + frame().size))
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
        val f = HevcCsd0.fields(h)
        assertTrue("len", f.contains("\"len\":${h.size}"))
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

    // ══ ② 非法 csd-0 必须失败（不许退化成"什么都接受"）═══════════════════════════

    @Test
    fun `configurationVersion 不等于 1 必须失败`() {
        val bad = hvcc(3, ver = 0)
        val e = assertThrows(IllegalStateException::class.java) { HevcCsd0.accept(bad) {} }
        assertTrue("错误消息必须带实际长度", e.message!!.contains("len=106"))
        assertTrue("错误消息必须带首字节", e.message!!.contains("h[0]=0x00"))
        assertTrue("错误消息必须点明 configurationVersion", e.message!!.contains("configurationVersion"))
    }

    @Test
    fun `长度小于 23 必须失败`() {
        for (size in intArrayOf(0, 1, 22)) {
            val bad = ByteArray(size).also { if (size > 0) it[0] = 1 }
            val e = assertThrows(IllegalStateException::class.java) { HevcCsd0.accept(bad) {} }
            assertTrue("尺寸 $size 的错误消息必须带实际长度", e.message!!.contains("len=$size"))
        }
    }

    @Test
    fun `csd-0 缺失 null 必须失败`() {
        val e = assertThrows(IllegalStateException::class.java) { HevcCsd0.accept(null) {} }
        assertTrue("必须点明编码器没给 csd-0", e.message!!.contains("csd-0"))
        assertTrue("必须报 len=0", e.message!!.contains("len=0"))
    }

    // ══ ③ 失败分支也发 photo.heif_csd ═══════════════════════════════════════════

    @Test
    fun `失败分支无条件发 photo_heif_csd`() {
        // 非法（ver != 1）：emit 在 check 之前 ⇒ 抛之前已经发过
        val logs1 = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) { HevcCsd0.accept(hvcc(3, ver = 2)) { logs1 += it } }
        assertEquals("结构非法也必须发诊断", 1, logs1.size)
        assertTrue("失败诊断也要带 lengthSizeMinusOne", logs1[0].contains("\"lengthSizeMinusOne\""))
        assertTrue("失败诊断必须标 ok=false", logs1[0].contains("\"ok\":false"))

        // 太短
        val logs2 = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) { HevcCsd0.accept(ByteArray(4)) { logs2 += it } }
        assertEquals("长度不足也必须发诊断", 1, logs2.size)

        // 缺失（编码器没吐 csd-0）
        val logs3 = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) { HevcCsd0.accept(null) { logs3 += it } }
        assertEquals("csd-0 缺失也必须发诊断", 1, logs3.size)
        assertTrue("缺失时 len=0", logs3[0].contains("\"len\":0"))
        assertTrue("缺失时数值字段取 -1", logs3[0].contains("\"ver\":-1"))
    }

    @Test
    fun `事件名与日志口径固定为 photo_heif_csd`() {
        assertEquals("photo.heif_csd", HevcCsd0.EVENT)
        assertEquals("hvcC 最小长度", 23, HevcCsd0.MIN_SIZE)
    }
}
