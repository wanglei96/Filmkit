package com.filmkit.app.heif

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `HeifContainerWriter` 的逐字节验证。
 *
 * golden 数据（真机 vivo X300 / Android 16 探针产出，见 `app/src/test/resources/heif/`）：
 * - `probe-main10-p010-1791965865303.heic` —— **真机 `MediaMuxer(MUXER_OUTPUT_HEIF)` 的产物**。
 *   扩展名是探针写的，内容其实是 **MP4**（`ftyp=major mp42` + `moov/trak` 视频轨）⇒ 相册不认。
 *   本测试从它里面取 `hvcC` + 第一帧样本，作为写入器的**真实输入**。
 * - `selfmade-probe-main10-p010-1791965865303.heic` —— 原型 `tools/heif10-container-ref.py`
 *   用同一份输入拼出的 HEIF（650 B），macOS 原生解码器已验证可解码出正确像素。
 *   本测试要求写入器的输出与它**逐字节相同**。
 */
class HeifContainerWriterTest {

    // ══ 测试用最小 box 解析（只覆盖本测试需要的容器嵌套，别当通用 ISO-BMFF 解析器用）══

    private class Box(val type: String, val start: Int, val body: Int, val end: Int)

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() shl 16 or u16(b, o + 2).toLong()).toInt()

    private fun u64(b: ByteArray, o: Int): Long =
        ((u32(b, o).toLong() and 0xffffffffL) shl 32) or (u32(b, o + 4).toLong() and 0xffffffffL)

    /** 一层 box 列表（不递归）。 */
    private fun siblings(b: ByteArray, start: Int, end: Int): List<Box> {
        val out = mutableListOf<Box>()
        var off = start
        while (off + 8 <= end) {
            var size = u32(b, off)
            var hdr = 8
            val type = String(b, off + 4, 4, Charsets.US_ASCII)
            if (size == 1) { // largesize（真机 muxer 的 mdat 就是这种）
                size = u64(b, off + 8).toInt(); hdr = 16
            } else if (size == 0) {
                size = end - off
            }
            if (size < hdr || off + size > end) break
            out += Box(type, off, off + hdr, off + size)
            off += size
        }
        return out
    }

    /** 递归找某个类型的 box（容器头部长度按类型区分）。 */
    private fun findAll(b: ByteArray, start: Int, end: Int, want: String): List<Box> {
        val out = mutableListOf<Box>()
        for (box in siblings(b, start, end)) {
            if (box.type == want) out += box
            when (box.type) {
                "moov", "trak", "mdia", "minf", "stbl", "iprp", "ipco", "dinf" ->
                    out += findAll(b, box.body, box.end, want)
                "meta" -> out += findAll(b, box.body + 4, box.end, want) // FullBox
                // iinf 是 FullBox + 2 字节 item_count（version 0，本写入器只产 v0）才是子 box
                "iinf" -> out += findAll(b, box.body + 6, box.end, want)
                "stsd" -> out += findAll(b, box.body + 8, box.end, want) // sample entry 计数
                "hvc1", "hev1" -> out += findAll(b, box.body + 78, box.end, want) // VisualSampleEntry
            }
        }
        return out
    }

    private fun golden(name: String): ByteArray =
        javaClass.getResourceAsStream("/heif/$name")?.readBytes()
            ?: error("缺测试资源 /heif/$name")

    private val mp4 = golden("probe-main10-p010-1791965865303.heic")
    private val goldenHeif = golden("selfmade-probe-main10-p010-1791965865303.heic")

    /** 原型 golden 的口径 = 真机探针落盘口径（BT.2020 线性全范围）。 */
    private val linearCicp = HeifContainerWriter.Cicp(primaries = 9, transfer = 8, matrix = 9, fullRange = true)

    /** 从真机 MP4 里取 `hvcC` + 第一帧样本 + 宽高（与原型 `extract_from_mp4` 同一取法）。 */
    private fun extractGoldenInput(): HeifContainerWriter.Image {
        val hvcc = findAll(mp4, 0, mp4.size, "hvcC").first()
        assertTrue("hvcC payload 应为 106 B（真机实测）", hvcc.end - hvcc.body == 106)
        val hvc1 = findAll(mp4, 0, mp4.size, "hvc1").first()
        val w = u16(mp4, hvc1.body + 24)
        val h = u16(mp4, hvc1.body + 26)
        val stsz = findAll(mp4, 0, mp4.size, "stsz").first()
        val sampleSize = u32(mp4, stsz.body + 4)
        val count = u32(mp4, stsz.body + 8)
        assertEquals("golden 只有 1 个样本", 1, count)
        val size = if (sampleSize != 0) sampleSize else u32(mp4, stsz.body + 12)
        val co64 = findAll(mp4, 0, mp4.size, "co64").first()
        val off = u64(mp4, co64.body + 8).toInt()
        return HeifContainerWriter.Image(
            hvcc = mp4.copyOfRange(hvcc.body, hvcc.end),
            frame = mp4.copyOfRange(off, off + size),
            width = w,
            height = h,
        )
    }

    // ══ ① 固化"官方 muxer 不可用"的一手事实 ══════════════════════════════════

    @Test
    fun `真机 muxer 产物是 MP4 而不是 HEIF`() {
        assertEquals("总分 24 B", 24, u32(mp4, 0))
        assertEquals("ftyp", String(mp4, 4, 4, Charsets.US_ASCII))
        assertEquals("major_brand", "mp42", String(mp4, 8, 4, Charsets.US_ASCII))
        assertEquals("minor_version", 0, u32(mp4, 12))
        assertEquals("compatible_brands", "isommp42", String(mp4, 16, 8, Charsets.US_ASCII))
        // 顶层是 moov（视频轨）而不是 meta（HEIF 图片）⇒ 相册不认
        val top = siblings(mp4, 0, mp4.size).map { it.type }
        assertEquals(listOf("ftyp", "moov", "free", "mdat"), top)
        assertTrue("顶层没有 meta", top.none { it == "meta" })
    }

    // ══ ② 逐字节比对：真机输入 → 写入器输出 == 原型 golden HEIF ═══════════════

    @Test
    fun `真机输入重建出的 HEIF 与原型 golden 逐字节相同`() {
        val image = extractGoldenInput()
        assertEquals("真机帧数据长度（4 字节长度前缀 NAL）", 286, image.frame.size)
        assertEquals(1024, image.width)
        assertEquals(256, image.height)

        // 显式传原型口径（不依赖默认值：交付口径已改为 sRGB 显示域，见 HeifContainerWriter.Cicp）
        val out = HeifContainerWriter.build(image, cicp = linearCicp, bitDepth = 10)
        assertEquals("产出大小应与 golden 一致", goldenHeif.size, out.size)
        assertArrayEquals("必须与原型产出逐字节相同", goldenHeif, out)
    }

    @Test
    fun `默认 CICP 是 sRGB 显示域`() {
        val out = HeifContainerWriter.build(extractGoldenInput())
        val colr = findAll(out, 0, out.size, "colr").first()
        assertEquals(1, u16(out, colr.body + 4))   // primaries BT.709/sRGB
        assertEquals(13, u16(out, colr.body + 6))  // transfer IEC 61966-2-1
        assertEquals(1, u16(out, colr.body + 8))   // matrix BT.709（RGB→YCbCr 4:2:0 用同一矩阵）
        assertEquals(0x80, out[colr.body + 10].toInt() and 0xff)
    }

    @Test
    fun `两档 CICP 只差 colr 的四元组字节`() {
        val image = extractGoldenInput()
        val linear = HeifContainerWriter.build(image, cicp = linearCicp)
        val srgb = HeifContainerWriter.build(image) // 默认 sRGB 显示域
        assertEquals("除 colr 外必须完全一致", linear.size, srgb.size)
        val diff = linear.indices.filter { linear[it] != srgb[it] }
        val colr = findAll(linear, 0, linear.size, "colr").first()
        assertTrue("两档必须真的不同", diff.isNotEmpty())
        assertTrue(
            "差异必须全落在 colr 的 CICP 四元组里（$diff）",
            diff.all { it in (colr.body + 4) until (colr.body + 10) },
        )
    }

    // ══ ③ 结构回读 ═══════════════════════════════════════════════════════════

    @Test
    fun `box 嵌套与顺序`() {
        val out = HeifContainerWriter.build(extractGoldenInput())
        val top = siblings(out, 0, out.size)
        assertEquals(listOf("ftyp", "meta", "mdat"), top.map { it.type })
        val meta = top[1]
        assertEquals("meta 子 box 顺序", listOf("hdlr", "pitm", "iinf", "iloc", "iprp"),
            siblings(out, meta.body + 4, meta.end).map { it.type })
        val iprp = siblings(out, meta.body + 4, meta.end).first { it.type == "iprp" }
        assertEquals(listOf("ipco", "ipma"), siblings(out, iprp.body, iprp.end).map { it.type })
        val ipco = siblings(out, iprp.body, iprp.end).first { it.type == "ipco" }
        assertEquals("ipco 内 property 顺序 = ipma 的 1-based 索引顺序",
            listOf("hvcC", "ispe", "pixi", "colr"), siblings(out, ipco.body, ipco.end).map { it.type })
        // 所有 box 必须严格铺满文件（无空洞、无越界）
        var off = 0
        for (box in top) { assertEquals(off, box.start); off = box.end }
        assertEquals(out.size, off)
    }

    @Test
    fun `iloc 的绝对偏移确实指向 mdat 内的数据`() {
        val image = extractGoldenInput()
        val out = HeifContainerWriter.build(image)
        val iloc = findAll(out, 0, out.size, "iloc").first()
        assertEquals("FullBox version 0", 0, out[iloc.body].toInt())
        assertEquals("offset_size=4 | length_size=4", 0x44, out[iloc.body + 4].toInt() and 0xff)
        assertEquals("base_offset_size=0", 0x00, out[iloc.body + 5].toInt() and 0xff)
        assertEquals("item_count", 1, u16(out, iloc.body + 6))
        assertEquals("item_ID", 1, u16(out, iloc.body + 8))
        assertEquals("data_reference_index", 0, u16(out, iloc.body + 10))
        assertEquals("extent_count", 1, u16(out, iloc.body + 12))
        val offset = u32(out, iloc.body + 14)
        val length = u32(out, iloc.body + 18)
        assertEquals(286, length)

        val mdat = findAll(out, 0, out.size, "mdat").first()
        assertEquals("iloc 偏移必须是绝对文件偏移，且正好落在 mdat 数据起点", mdat.body, offset)
        assertEquals("mdat payload 长度 = 帧长度", length, mdat.end - mdat.body)
        assertArrayEquals(image.frame, out.copyOfRange(offset, offset + length))
    }

    @Test
    fun `colr pixi ispe infe 与 hvcC 位深`() {
        val image = extractGoldenInput()
        val out = HeifContainerWriter.build(image, cicp = linearCicp)

        val colr = findAll(out, 0, out.size, "colr").first()
        assertEquals("nclx", String(out, colr.body, 4, Charsets.US_ASCII))
        assertEquals("primaries BT.2020", 9, u16(out, colr.body + 4))
        assertEquals("transfer linear(CICP 8)", 8, u16(out, colr.body + 6))
        assertEquals("matrix BT.2020 NCL", 9, u16(out, colr.body + 8))
        assertEquals("full_range", 0x80, out[colr.body + 10].toInt() and 0xff)

        val pixi = findAll(out, 0, out.size, "pixi").first()
        assertEquals("3 通道", 3, out[pixi.body + 4].toInt())
        assertEquals("每通道 10bit", listOf(10, 10, 10),
            (5..7).map { out[pixi.body + it].toInt() })

        val ispe = findAll(out, 0, out.size, "ispe").first()
        assertEquals(image.width, u32(out, ispe.body + 4))
        assertEquals(image.height, u32(out, ispe.body + 8))

        val infe = findAll(out, 0, out.size, "infe").first()
        assertEquals("infe version 2", 2, out[infe.body].toInt())
        assertEquals("item_ID", 1, u16(out, infe.body + 4))
        assertEquals("protection_index", 0, u16(out, infe.body + 6))
        assertEquals("item_type", "hvc1", String(out, infe.body + 8, 4, Charsets.US_ASCII))

        val pitm = findAll(out, 0, out.size, "pitm").first()
        assertEquals("主图 item_ID = 1", 1, u16(out, pitm.body + 4))

        val hdlr = findAll(out, 0, out.size, "hdlr").first()
        assertEquals("pict", String(out, hdlr.body + 8, 4, Charsets.US_ASCII))

        // ipma：1-based、顺序与 ipco 一致、全部 essential
        val ipma = findAll(out, 0, out.size, "ipma").first()
        assertEquals("entry_count", 1, u32(out, ipma.body + 4))
        assertEquals("item_ID", 1, u16(out, ipma.body + 8))
        assertEquals("association_count", 4, out[ipma.body + 10].toInt())
        assertEquals(listOf(0x81, 0x82, 0x83, 0x84),
            (11..14).map { out[ipma.body + it].toInt() and 0xff })

        // hvcC 必须真的是 10bit，且帧数据是 4 字节长度前缀（容器搬运的前提）
        val hvcc = findAll(out, 0, out.size, "hvcC").first()
        val h = out.copyOfRange(hvcc.body, hvcc.end)
        assertArrayEquals("hvcC 必须原样搬运", image.hvcc, h)
        assertEquals("configurationVersion", 1, h[0].toInt())
        assertEquals("general_profile_idc = Main10", 2, h[1].toInt() and 0x1f)
        assertEquals("bitDepthLumaMinus8 = 2 ⇒ 真 10bit", 2, h[17].toInt() and 0x07)
        assertEquals("bitDepthChromaMinus8 = 2", 2, h[18].toInt() and 0x07)
        assertEquals("lengthSizeMinusOne = 3 ⇒ 4 字节长度前缀", 3, h[21].toInt() and 0x03)
        assertEquals("VPS/SPS/PPS 三组", 3, h[22].toInt() and 0xff)
    }

    // ══ ④ 边界：长度字段与参数校验 ═══════════════════════════════════════════

    @Test
    fun `空帧与非法尺寸被拒绝`() {
        val image = extractGoldenInput()
        assertThrows(IllegalArgumentException::class.java) {
            HeifContainerWriter.build(HeifContainerWriter.Image(image.hvcc, ByteArray(0), 1024, 256))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeifContainerWriter.build(HeifContainerWriter.Image(ByteArray(0), image.frame, 1024, 256))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeifContainerWriter.build(HeifContainerWriter.Image(image.hvcc, image.frame, 0, 256))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeifContainerWriter.build(HeifContainerWriter.Image(image.hvcc, image.frame, 1024, -1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeifContainerWriter.build(image, bitDepth = 0)
        }
    }

    @Test
    fun `最小尺寸 1x1`() {
        val image = HeifContainerWriter.Image(extractGoldenInput().hvcc, byteArrayOf(0, 0, 1, 0x40), 1, 1)
        val out = HeifContainerWriter.build(image)
        val ispe = findAll(out, 0, out.size, "ispe").first()
        assertEquals(1, u32(out, ispe.body + 4))
        assertEquals(1, u32(out, ispe.body + 8))
        val mdat = findAll(out, 0, out.size, "mdat").first()
        assertEquals("mdat 只有 4 字节帧数据", 4, mdat.end - mdat.body)
        assertArrayEquals(image.frame, out.copyOfRange(mdat.body, mdat.end))
    }

    @Test
    fun `大偏移与大长度时 iloc 的 4 字节字段不溢出`() {
        // hvcC 撑到 70 KB ⇒ meta 超过 65536 ⇒ 绝对偏移必须用满 4 字节
        val hvcc = ByteArray(70_000) { (it % 251).toByte() }
        val frame = ByteArray(200_000) { (it % 97).toByte() }
        val out = HeifContainerWriter.build(HeifContainerWriter.Image(hvcc, frame, 65535, 65535))

        val iloc = findAll(out, 0, out.size, "iloc").first()
        val offset = u32(out, iloc.body + 14)
        val length = u32(out, iloc.body + 18)
        assertTrue("偏移必须超过 0xFFFF（证明需要 4 字节 offset 字段）", offset > 0xffff)
        assertEquals("长度超过 0xFFFF 也要能承载", 200_000, length)
        val mdat = findAll(out, 0, out.size, "mdat").first()
        assertEquals(mdat.body, offset)
        assertEquals(200_000, mdat.end - mdat.body)
        assertEquals("文件总长 = 数据偏移 + 数据长度", offset + length, out.size)
        assertArrayEquals(frame, out.copyOfRange(offset, offset + length))

        val ispe = findAll(out, 0, out.size, "ispe").first()
        assertEquals(65535, u32(out, ispe.body + 4))
        assertEquals(65535, u32(out, ispe.body + 8))
    }

    @Test
    fun `自定义 CICP 与位深生效`() {
        val image = extractGoldenInput()
        val out = HeifContainerWriter.build(
            image,
            cicp = HeifContainerWriter.Cicp(primaries = 12, transfer = 16, matrix = 6, fullRange = false),
            bitDepth = 12,
        )
        val colr = findAll(out, 0, out.size, "colr").first()
        assertEquals("P3 primaries", 12, u16(out, colr.body + 4))
        assertEquals("PQ transfer", 16, u16(out, colr.body + 6))
        assertEquals("BT.2020 constant luminance matrix", 6, u16(out, colr.body + 8))
        assertEquals("limited range", 0x00, out[colr.body + 10].toInt() and 0xff)
        val pixi = findAll(out, 0, out.size, "pixi").first()
        assertEquals(listOf(12, 12, 12), (5..7).map { out[pixi.body + it].toInt() })
    }

    @Test
    fun `golden HEIF 自身可被解析器完整走通`() {
        // 若这条挂了，说明测试解析器而不是写入器有问题（先自证工具链）
        val top = siblings(goldenHeif, 0, goldenHeif.size)
        assertEquals(listOf("ftyp", "meta", "mdat"), top.map { it.type })
        assertNotNull(findAll(goldenHeif, 0, goldenHeif.size, "colr").firstOrNull())
        assertEquals("1 帧 hvc1 item", "hvc1",
            String(goldenHeif, findAll(goldenHeif, 0, goldenHeif.size, "infe").first().body + 8, 4, Charsets.US_ASCII))
    }
}
