package com.filmkit.app.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Heif10BitProbe` 的 HEIF box 解析器单测（纯字节输入，无 Android / JNI 依赖）。
 *
 * 为什么要测：`colr/nclx` 与 `hvcC.bitDepthLumaMinus8` 是本探针**唯一的自动化判据**
 * （方案 `docs/rec2020-heif-design.md` §4.5 判据 A）。解析器一旦错位，真机上会把
 * "编码器没写标签"和"我们没解析到"混为一谈 —— 那是会误导期 A 决策的错。
 *
 * 测试字节按 ISO/IEC 23008-12（`meta`→`iprp`→`ipco`→`colr`/`hvcC`/`ispe`）与
 * ISO/IEC 14496-15（HEVCDecoderConfigurationRecord 字段偏移）手工构造。
 */
class Heif10BitProbeTest {

    private fun u32(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    /** 普通 box：size(4) + type(4) + payload */
    private fun box(type: String, payload: ByteArray): ByteArray =
        u32(8 + payload.size) + type.toByteArray(Charsets.US_ASCII) + payload

    /** fullbox：payload 前加 version/flags(4) */
    private fun fullBox(type: String, payload: ByteArray): ByteArray =
        box(type, ByteArray(4) + payload)

    /** fullbox（指定 version；卡 H2 的 iinf v0/v1 分支要用） */
    private fun fullBoxV(type: String, version: Int, payload: ByteArray): ByteArray =
        box(type, byteArrayOf(version.toByte()) + ByteArray(3) + payload)

    /** infe v2：item_ID(2) + protection_index(2) + item_type(4) + item_name(NUL) */
    private fun infeV2(id: Int, type: String, name: String): ByteArray =
        fullBoxV("infe", 2, u16(id) + u16(0) + type.toByteArray(Charsets.US_ASCII) +
            name.toByteArray(Charsets.UTF_8) + byteArrayOf(0))

    /** infe v0：无 item_type（item_name + content_type + content_encoding） */
    private fun infeV0(id: Int, name: String): ByteArray =
        fullBoxV("infe", 0, u16(id) + u16(0) + name.toByteArray(Charsets.UTF_8) + byteArrayOf(0))

    /**
     * 带 `iinf` 的完整 HEIF（卡 H2：`iinf` 是 FullBox **且** 子 box 前还有 entry_count）。
     * `version == 0` ⇒ entry_count 是 **2 字节**（父代理报的缺陷正是它没被跳过）。
     */
    private fun heifWithIinf(vararg infes: ByteArray, version: Int = 0): ByteArray {
        val ipco = box("ipco", nclx(1, 13, 1, true) + hvcC(2, 0, 150, 2, 1))
        val iprp = box("iprp", ipco)
        val count = if (version == 0) u16(infes.size) else u32(infes.size)
        val iinf = fullBoxV("iinf", version, count + infes.reduce { a, b -> a + b })
        val meta = fullBox("meta", iinf + iprp)
        val ftyp = box("ftyp", "heic".toByteArray() + u32(0) + "mif1heic".toByteArray())
        return ftyp + meta
    }

    /** colr/nclx：type 'nclx' + primaries(2) + transfer(2) + matrix(2) + full_range(1) */
    private fun nclx(primaries: Int, transfer: Int, matrix: Int, fullRange: Boolean): ByteArray =
        box("colr", "nclx".toByteArray(Charsets.US_ASCII) + u16(primaries) + u16(transfer) +
            u16(matrix) + byteArrayOf(if (fullRange) 0x80.toByte() else 0x00))

    /** HEVCDecoderConfigurationRecord 最小 23 字节（只填本探针读取的字段） */
    private fun hvcC(profileIdc: Int, tier: Int, levelIdc: Int, bitDepthLumaMinus8: Int, chromaFormat: Int): ByteArray {
        val p = ByteArray(23)
        p[0] = 0x01
        p[1] = (((tier shl 5) or profileIdc) and 0xFF).toByte()
        p[12] = levelIdc.toByte()
        p[16] = (chromaFormat and 0x03).toByte()
        p[17] = (bitDepthLumaMinus8 and 0x07).toByte()
        p[18] = (bitDepthLumaMinus8 and 0x07).toByte()
        return box("hvcC", p)
    }

    private fun ispe(w: Int, h: Int): ByteArray = fullBox("ispe", u32(w) + u32(h))

    private fun heif(vararg properties: ByteArray): ByteArray {
        val ipco = box("ipco", properties.reduce { a, b -> a + b })
        val iprp = box("iprp", ipco)
        val meta = fullBox("meta", iprp)
        val ftyp = box("ftyp", "heic".toByteArray() + u32(0) + "mif1heic".toByteArray())
        return ftyp + meta
    }

    @Test
    fun `解析 nclx BT2020 线性全范围`() {
        // 期 A 请求值：BT.2020 线性全范围 = CICP 9/8/9/full（Android 侧枚举是 6/1/1，见文件头）
        val b = heif(
            ispe(1024, 256),
            nclx(9, 8, 9, true),
            hvcC(profileIdc = 2, tier = 0, levelIdc = 150, bitDepthLumaMinus8 = 2, chromaFormat = 1),
        )
        val f = Heif10BitProbe.parseBoxes(b)
        assertEquals("nclx", f.colrType)
        assertEquals(9, f.primaries)
        assertEquals(8, f.transfer)
        assertEquals(9, f.matrix)
        assertEquals(1, f.fullRange)
        assertEquals(1024, f.width)
        assertEquals(256, f.height)
        // Main10 profile_idc=2 + bitDepthLumaMinus8=2 ⇒ 10bit
        assertEquals(2, f.profileIdc)
        assertEquals(2, f.bitDepthLumaMinus8)
        assertEquals(1, f.chromaFormat)
        assertEquals(listOf("ftyp", "meta"), f.topLevel)
    }

    @Test
    fun `解析 8bit 与 limited range`() {
        val b = heif(
            ispe(1024, 256),
            nclx(1, 13, 6, false),
            hvcC(profileIdc = 1, tier = 0, levelIdc = 120, bitDepthLumaMinus8 = 0, chromaFormat = 1),
        )
        val f = Heif10BitProbe.parseBoxes(b)
        assertEquals(1, f.primaries)
        assertEquals(13, f.transfer)
        assertEquals(6, f.matrix)
        assertEquals(0, f.fullRange)
        assertEquals(0, f.bitDepthLumaMinus8)
    }

    @Test
    fun `没有 colr box 时字段保持未知`() {
        // 这条对应"编码器根本没写标签"：colrType=null 必须能与"解析失败"区分开
        val b = heif(ispe(64, 64), hvcC(2, 0, 120, 2, 1))
        val f = Heif10BitProbe.parseBoxes(b)
        assertNull(f.colrType)
        assertEquals(-1, f.primaries)
        assertEquals(-1, f.transfer)
        assertEquals(-1, f.fullRange)
        assertEquals(2, f.bitDepthLumaMinus8) // hvcC 仍在，位深判据不受 colr 影响
    }

    @Test
    fun `截断或垃圾字节不抛异常`() {
        val f = Heif10BitProbe.parseBoxes(byteArrayOf(0, 0, 0, 99, 1, 2, 3))
        assertNull(f.colrType)
        assertEquals(0, f.topLevel.size)
    }

    @Test
    fun `解析 iinf 的 infe v2 item 类型与名字（卡 H2 修复）`() {
        // 官方 muxer 到底写了哪些 item —— 第三臂的核心问题；此前 iinf 不在容器白名单
        // ⇒ infe 永远读不到，这条结论缺失。
        val b = heifWithIinf(
            infeV2(1, "hvc1", "image"),
            infeV2(2, "Exif", "exif"),
            version = 0,
        )
        val f = Heif10BitProbe.parseBoxes(b)
        assertEquals(listOf("hvc1", "Exif"), f.itemTypes)
        assertEquals(listOf("image", "exif"), f.itemNames)
        // colr/hvcC 仍要照常解析（iinf 的加入不得影响既有提取）
        assertEquals(1, f.primaries)
        assertEquals(13, f.transfer)
        assertEquals(2, f.bitDepthLumaMinus8)
    }

    @Test
    fun `iinf v0 的 entry_count 是 2 字节（跳过后才能读到子 box）`() {
        // v0：FullBox(4) + entry_count(2) —— 不跳过它，遍历器把 0x0001 当 size=1 < header
        // ⇒ break，infe 静默丢失。本测试就是那条缺陷的回归守卫。
        val f0 = Heif10BitProbe.parseBoxes(heifWithIinf(infeV2(1, "hvc1", "a"), version = 0))
        assertEquals(listOf("hvc1"), f0.itemTypes)
        // v1+：entry_count 是 4 字节（分支必须分开，否则 v1 的 iinf 会整体错位 2 字节）
        val f1 = Heif10BitProbe.parseBoxes(heifWithIinf(infeV2(1, "hvc1", "a"), version = 1))
        assertEquals(listOf("hvc1"), f1.itemTypes)
    }

    @Test
    fun `infe v0 v1 没有 item_type 时仍记录占位并给出名字`() {
        val f = Heif10BitProbe.parseBoxes(heifWithIinf(infeV0(1, "legacy"), version = 0))
        assertEquals(1, f.itemTypes.size)
        assertEquals("legacy", f.itemNames[0])
    }

    @Test
    fun `没有 iinf 时 item 清单为空（不误报）`() {
        val b = heif(ispe(64, 64), nclx(1, 13, 1, true), hvcC(2, 0, 120, 2, 1))
        val f = Heif10BitProbe.parseBoxes(b)
        assertEquals(0, f.itemTypes.size)
    }
}
