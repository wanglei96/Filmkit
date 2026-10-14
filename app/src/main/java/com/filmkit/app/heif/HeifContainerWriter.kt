package com.filmkit.app.heif

import java.io.ByteArrayOutputStream

/**
 * 自研 HEIF 静态图容器写入器（`heic`/`mif1`，单帧 `hvc1` item）。
 *
 * ## 为什么自研（一手实测：vivo X300 / Android 16 / SDK 36，2026-10-14）
 * `MediaMuxer(MUXER_OUTPUT_HEIF)` 产出的是 **MP4**（`ftyp=mp42` + `moov/trak` 视频轨），
 * 没有顶层 `meta/iinf/iloc/iprp` ⇒ 相册不认。自研写入器是 10bit 路线的唯一容器方案。
 * 路线已端到端验证：原型 `tools/heif10-container-ref.py` 手拼的 HEIF（650 B）
 * 被 macOS 原生解码器强制解码出正确像素（1024×256 RGB，左上亮度 208）。
 *
 * ## 与编码器的接口约定
 * `frame` 必须是 **4 字节长度前缀的 NAL 单元**（`hvcC.lengthSizeMinusOne == 3`）——
 * 真机实测 `c2.mtk.hevc.encoder` 的输出天然满足，**直接搬运、无需重打包**。
 * `hvcc` 取自编码器 `outputFormat` 的 `csd-0`（实测 106 B，`profile_idc=2` Main10、
 * `bitDepthLumaMinus8=2`）。
 *
 * ## 关键实现约束（照抄原型，勿改）
 * - `iloc` 的 extent offset 是 **绝对文件偏移** ⇒ 两遍构造：先按 0 占位算出 `meta` 长度，
 *   再回填 `mdat` 数据偏移；回填**不得改变** `meta` 长度（下面有 `check`，长度变了就崩）。
 * - `ipma` 的关联是 **1-based**，顺序与 `ipco` 内 property 顺序一致（0x81..0x84 = 1..4 且 essential）。
 * - CICP **必须显式写**：Android 的 `COLOR_STANDARD_*`/`COLOR_TRANSFER_*` 键 **不是** CICP
 *   （真机探针里 `BT2020=6`/`LINEAR=1` 被平台映射成落盘的 9/8/9）。自研容器**不读** Android 键，
 *   一律按 [Cicp] 显式写 —— 交付口径的 **sRGB 显示域**是默认值，线性档只是换参数（见 [Cicp]）。
 * - **不需要** `MediaMuxer` 那个 `free` box（它只是 muxer 的预留空间）。
 *
 * ## 多帧/burst 挂点
 * `iinf`/`iloc`/`ipma` 的 item 数由 [Image] 列表参数化（公开入口 [build] 只传 1 帧）。
 * burst 还差 `iref`（`dimg`/`thmb` 关联）—— 需要时再加，不提前实现。
 *
 * 纯函数、**零 Android 依赖**（只用 `java.nio`/`ByteArray`）⇒ JVM 单测可逐字节验证。
 */
object HeifContainerWriter {

    /**
     * CICP 色彩描述（`colr/nclx` 四元组）。
     *
     * **默认 = sRGB 显示域（2026-10-14 用户拍板，选项 A）**：`primaries=1`(BT.709/sRGB) /
     * `transfer=13`(IEC 61966-2-1 sRGB) / `matrix=1`(BT.709) / full range(1)。
     * 取数点是现有 `tex_cap_`（OETF 之后、LUT/颗粒/锐化之后）—— 成片本来就是显示域，
     * 10bit 的价值是避免 8bit 量化损失，且绕开"下游如何解释线性 HEIF"的风险。
     *
     * **线性档**（真机探针实测落盘口径）需要显式传 `Cicp(9, 8, 9, true)`
     * （BT.2020 primaries / 线性 transfer / BT.2020 NCL matrix）—— 只是换参数，别写死。
     */
    data class Cicp(
        val primaries: Int = 1,
        val transfer: Int = 13,
        val matrix: Int = 1,
        val fullRange: Boolean = true,
    )

    /** 一个 HEVC 图像 item：`hvcC`（含 VPS/SPS/PPS）+ 4 字节长度前缀 NAL 帧数据 + 显示尺寸。 */
    class Image(
        val hvcc: ByteArray,
        val frame: ByteArray,
        val width: Int,
        val height: Int,
    )

    /**
     * 单帧 HEIF：`ftyp(heic/mif1)` + `meta(hdlr/pitm/iinf/infe/iloc/iprp(ipco+ipma))` + `mdat`。
     *
     * @param bitDepth `pixi` 声明的通道位深（3 通道同值）；10bit 路线传 10。
     */
    fun build(image: Image, cicp: Cicp = Cicp(), bitDepth: Int = 10): ByteArray =
        build(listOf(image), cicp, bitDepth)

    /** 多帧挂点：item 数参数化（见类注释）。当前调用方 [build] 只传 1 帧。 */
    private fun build(items: List<Image>, cicp: Cicp, bitDepth: Int): ByteArray {
        require(items.isNotEmpty()) { "至少需要 1 个 item" }
        require(bitDepth in 1..16) { "pixi 位深非法: $bitDepth" }
        items.forEachIndexed { i, it ->
            require(it.hvcc.isNotEmpty()) { "item[$i] 的 hvcC 为空" }
            require(it.frame.isNotEmpty()) { "item[$i] 的帧数据为空" }
            require(it.width > 0 && it.height > 0) { "item[$i] 尺寸非法: ${it.width}x${it.height}" }
        }

        val ftyp = box("ftyp", ascii("heic") + u32(0) + ascii("mif1") + ascii("heic"))
        // 两遍构造：iloc 的 extent offset 是绝对文件偏移
        val metaEmpty = meta(items, dataStart = 0, cicp = cicp, bitDepth = bitDepth)
        val dataStart = ftyp.size + metaEmpty.size + 8 // + mdat box header
        require(dataStart >= 0) { "文件过大，iloc 的 4 字节绝对偏移溢出" }
        val meta = meta(items, dataStart = dataStart, cicp = cicp, bitDepth = bitDepth)
        check(meta.size == metaEmpty.size) { "iloc 回填改变了 meta 长度（${metaEmpty.size} → ${meta.size}）" }

        return ftyp + meta + box("mdat", items.map { it.frame }.concat())
    }

    /**
     * `meta` FullBox：`hdlr(pict)` + `pitm` + `iinf/infe(v2,hvc1)` + `iloc` + `iprp(ipco + ipma)`。
     * `iloc` 的 extent offset 由 [dataStart] 起的**连续排布**推出（多帧时逐个累加）。
     */
    private fun meta(items: List<Image>, dataStart: Int, cicp: Cicp, bitDepth: Int): ByteArray {
        val offsets = IntArray(items.size)
        var acc = dataStart
        items.forEachIndexed { i, it -> offsets[i] = acc; acc += it.frame.size }

        val hdlr = fbox("hdlr", 0, u32(0) + ascii("pict") + ByteArray(13))
        val pitm = fbox("pitm", 0, u16(1))
        val iinf = fbox(
            "iinf", 0,
            u16(items.size) + items.indices.map { i ->
                // infe v2: item_ID, protection_index, item_type, item_name
                fbox("infe", 2, u16(i + 1) + u16(0) + ascii("hvc1") + u8(0))
            }.concat(),
        )
        // iloc v0: offset_size=4|length_size=4, base_offset_size=0, 每 item 1 个 extent
        val iloc = fbox(
            "iloc", 0,
            u8(0x44) + u8(0x00) + u16(items.size) + items.indices.map { i ->
                u16(i + 1) + u16(0) + u16(1) + u32(offsets[i]) + u32(items[i].frame.size)
            }.concat(),
        )
        val ipco = box(
            "ipco",
            // property 顺序即 ipma 的 1-based 索引顺序，勿调换
            box("hvcC", items[0].hvcc) +
                fbox("ispe", 0, u32(items[0].width) + u32(items[0].height)) +
                fbox("pixi", 0, u8(3) + u8(bitDepth) + u8(bitDepth) + u8(bitDepth)) +
                box("colr", ascii("nclx") + u16(cicp.primaries) + u16(cicp.transfer) + u16(cicp.matrix) +
                    u8(if (cicp.fullRange) 0x80 else 0x00)),
        )
        val ipma = fbox(
            "ipma", 0,
            u32(items.size) + items.indices.map { i ->
                u16(i + 1) + u8(4) + u8(0x81) + u8(0x82) + u8(0x83) + u8(0x84) // 1-based, essential
            }.concat(),
        )
        return fbox("meta", 0, hdlr + pitm + iinf + iloc + box("iprp", ipco + ipma))
    }

    // ══ box 原语（big-endian，与原型 struct.pack('>...') 等价）════════════════

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 8)
        out.write(u32(payload.size + 8))
        out.write(ascii(type))
        out.write(payload)
        return out.toByteArray()
    }

    /** FullBox：version + 3 字节 flags（本写入器 flags 恒 0，与原型一致）。 */
    private fun fbox(type: String, version: Int, payload: ByteArray): ByteArray =
        box(type, u8(version) + ByteArray(3) + payload)

    private fun u8(v: Int) = byteArrayOf(v.toByte())
    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun u32(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun List<ByteArray>.concat(): ByteArray {
        val out = ByteArrayOutputStream(sumOf { it.size })
        forEach { out.write(it) }
        return out.toByteArray()
    }
}
