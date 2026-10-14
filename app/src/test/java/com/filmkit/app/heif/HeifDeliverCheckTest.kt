package com.filmkit.app.heif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行时读头自检（卡 H2 块 4）的判据：**它是防止"静默交付错文件"的唯一设施**，
 * 所以既要证明"合规文件能过"，也要证明"每一种不合规都会红"（否则是个假守卫）。
 *
 * 用 [HeifContainerWriter] 直接造真容器（纯 JVM、零 Android 依赖）—— 编码器（MediaCodec）
 * 只能真机跑，但容器与头解析这一整段可以 100% 离线验证。
 */
class HeifDeliverCheckTest {

    /** HEVCDecoderConfigurationRecord 最小 23 字节（只填本判据读取的字段）。 */
    private fun hvcc(profileIdc: Int, bitDepthMinus8: Int): ByteArray {
        val p = ByteArray(23)
        p[0] = 0x01
        p[1] = (profileIdc and 0x1F).toByte()
        p[12] = 150.toByte()
        p[16] = 0x01
        p[17] = (bitDepthMinus8 and 0x07).toByte()
        p[18] = (bitDepthMinus8 and 0x07).toByte()
        return p
    }

    private fun image(profileIdc: Int = 2, bitDepthMinus8: Int = 2) =
        HeifContainerWriter.Image(
            hvcc(profileIdc, bitDepthMinus8),
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),   // 帧数据内容与读头自检无关
            1024, 256,
        )

    @Test
    fun `自检接受本仓写入器的 10bit 交付档输出`() {
        val bytes = HeifContainerWriter.build(image(), HeifContainerWriter.Cicp(), bitDepth = 10)
        val r = HeifDeliverCheck.check(bytes)
        assertTrue("合规文件必须通过，实际：${r.detail}", r.ok)
        assertTrue(r.detail.contains("\"nclx\":[1,13,1,1]"))
        assertTrue(r.detail.contains("\"profileIdc\":2"))
        assertTrue(r.detail.contains("\"bitDepthLumaMinus8\":2"))
        assertTrue(r.detail.contains("hvc1"))   // 卡 H2：iinf/infe 修好后 item 类型可见
    }

    @Test
    fun `自检拒绝线性档 colr（9_8_9）`() {
        // 真机探针实测的落盘口径（BT.2020 线性）**不是**本卡的交付口径（R1 选项 A）
        val bytes = HeifContainerWriter.build(
            image(), HeifContainerWriter.Cicp(primaries = 9, transfer = 8, matrix = 9), bitDepth = 10,
        )
        val r = HeifDeliverCheck.check(bytes)
        assertFalse("线性档必须被拒（交付域 = sRGB 显示域）", r.ok)
        assertTrue(r.detail.contains("primaries=9"))
        assertTrue(r.detail.contains("transfer=8"))
        assertTrue(r.detail.contains("matrix=9"))
    }

    @Test
    fun `自检拒绝 limited range`() {
        val bytes = HeifContainerWriter.build(
            image(), HeifContainerWriter.Cicp(fullRange = false), bitDepth = 10,
        )
        val r = HeifDeliverCheck.check(bytes)
        assertFalse(r.ok)
        assertTrue(r.detail.contains("fullRange=0"))
    }

    @Test
    fun `自检拒绝 8bit 编码（bitDepthLumaMinus8=0）`() {
        // 这条就是"静默降位"的守门：容器说 10bit、编码器却给了 8bit 流
        val bytes = HeifContainerWriter.build(image(bitDepthMinus8 = 0), HeifContainerWriter.Cicp(), bitDepth = 10)
        val r = HeifDeliverCheck.check(bytes)
        assertFalse(r.ok)
        assertTrue(r.detail.contains("bitDepthLumaMinus8=0"))
    }

    @Test
    fun `自检拒绝非 Main10 档次（profile_idc=1）`() {
        val bytes = HeifContainerWriter.build(image(profileIdc = 1), HeifContainerWriter.Cicp(), bitDepth = 10)
        val r = HeifDeliverCheck.check(bytes)
        assertFalse(r.ok)
        assertTrue(r.detail.contains("profileIdc=1"))
    }

    @Test
    fun `自检拒绝 MP4 结构（没有顶层 meta）`() {
        // 真机实测：MediaMuxer(MUXER_OUTPUT_HEIF) 产出 ftyp=mp42 + moov/trak，没有 meta
        // ⇒ 系统相册不认。这条判据就是那次的回归守卫。
        val ftyp = box("ftyp", "mp42".toByteArray() + byteArrayOf(0, 0, 0, 0) + "mp42isom".toByteArray())
        val moov = box("moov", box("trak", ByteArray(16)))
        val r = HeifDeliverCheck.check(ftyp + moov)
        assertFalse(r.ok)
        assertTrue(r.detail.contains("noMeta"))
    }

    @Test
    fun `自检拒绝缺 pixi 声明`() {
        // 手工拼一个"有 colr/hvcC 但 ipco 里没有 pixi"的容器（部分解码端由此读不到 10bit）
        val ipco = box(
            "ipco",
            box("hvcC", hvcc(2, 2)) +
                fbox("ispe", byteArrayOf(0, 0, 0, 0) + u32(1024) + u32(256)) +
                box("colr", "nclx".toByteArray() + u16(1) + u16(13) + u16(1) + byteArrayOf(0x80.toByte())),
        )
        val iprp = box("iprp", ipco + fbox("ipma", byteArrayOf(0, 0, 0, 0) + u32(1) + u16(1) + byteArrayOf(3, 0x81.toByte(), 0x82.toByte(), 0x83.toByte())))
        val meta = fbox("meta", fbox("hdlr", byteArrayOf(0, 0, 0, 0) + "pict".toByteArray() + ByteArray(13)) + iprp)
        val ftyp = box("ftyp", "heic".toByteArray() + byteArrayOf(0, 0, 0, 0) + "mif1heic".toByteArray())
        val r = HeifDeliverCheck.check(ftyp + meta)
        assertFalse(r.ok)
        assertTrue(r.detail.contains("pixi=-1"))
    }

    // ── 小工具（与 HeifContainerWriter 同口径的 box 原语）────────────────────

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun u32(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun box(type: String, payload: ByteArray) =
        u32(payload.size + 8) + type.toByteArray(Charsets.US_ASCII) + payload

    private fun fbox(type: String, payload: ByteArray) =
        box(type, byteArrayOf(0, 0, 0, 0) + payload)

    @Test
    fun `期望值真源 = 容器写入器默认 CICP（改口径自动跟随）`() {
        val want = HeifDeliverCheck.expected()
        assertEquals(1, want.primaries)
        assertEquals(13, want.transfer)
        assertEquals(1, want.matrix)
        assertTrue(want.fullRange)
    }
}
