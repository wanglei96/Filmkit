package com.filmkit.app.heif

import com.filmkit.app.diag.Heif10BitProbe
import java.io.File

/**
 * HEIF 落盘后的**运行时读头自检**（卡 H2 / 设计 §4.5 判据 A）。
 *
 * ## 为什么必须有（防"静默交付错文件"）
 * 10bit HEIF 的交付口径全在**文件头**里：`colr/nclx`（1/13/1/1 = sRGB 显示域）、
 * `hvcC`（bitDepthLumaMinus8=2 + profile_idc=2 = 真 10bit）、顶层 `meta`（是 HEIF 图片
 * 而不是 MP4）。这三项**任一写错都不会报错**：系统相册照常打开、颜色/位深悄悄不对 ——
 * 这是最难查的一类事故（设计 §2.7 的 muxer 走 MP4 老路就是这么发生的）。
 * 因此每次 HEIF 落盘后**立即回读文件头**核对；不合规 ⇒ 记 `kError` 并按回退链降级。
 *
 * ## 期望值的真源
 * 不在这里再抄一份常量：期望的 CICP 直接取 [HeifContainerWriter.Cicp] 的默认值
 * （改交付口径只改那一处，本自检自动跟着走）。
 *
 * ## 成本
 * 只读文件**前 64 KiB**（`ftyp` + `meta` 全部落在这里；`mdat` 的兆字节不读）。
 * 自检本身 < 1 ms。
 *
 * ## 解析器
 * 复用真机探针的 box 遍历器 [Heif10BitProbe.parseBoxes]（`internal`，同模块可见）——
 * 探针已经在真机上验证过 `colr/hvcC/ispe/pixi/iloc/iinf` 的解析，再写一份解析器就是
 * 第二个真源（本项目明令禁止）。探针那份的 `iinf` 缺陷（不在容器白名单 + v0 的
 * item_count 是 2 字节）已在卡 H2 一并修好（见探针内注释）。
 */
object HeifDeliverCheck {

    /** 读头上限：`ftyp` + `meta` 恒在前部；64 KiB 足够，且与 mdat 体积无关。 */
    private const val HEAD_BYTES = 64 * 1024

    /** 一次自检的结论。[detail] 直接进日志（含逐项实测值，便于用户导出日志后定位）。 */
    class Result(val ok: Boolean, val detail: String)

    /** 交付档的期望值（= 容器写入器的默认 CICP；单一真源）。 */
    fun expected(): HeifContainerWriter.Cicp = HeifContainerWriter.Cicp()

    /**
     * 读 [file] 的头 [HEAD_BYTES] 字节并核对交付口径。
     * 文件不存在/读失败 ⇒ `ok = false`（调用方按回退链降级；绝不静默放过）。
     */
    fun check(file: File): Result = try {
        file.inputStream().use { check(it) }
    } catch (t: Throwable) {
        Result(false, "{\"read\":false,\"err\":\"${escape(t.message ?: t.toString())}\"}")
    }

    /**
     * 流版本（MediaStore 的 `openInputStream(uri)` 与本地 File 共用同一条路径）。
     * 只读前 [HEAD_BYTES] 字节，读完即关（调用方负责关流）。
     */
    fun check(input: java.io.InputStream): Result {
        val head = try {
            val buf = ByteArray(HEAD_BYTES)
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            if (off == buf.size) buf else buf.copyOf(off)
        } catch (t: Throwable) {
            return Result(false, "{\"read\":false,\"err\":\"${escape(t.message ?: t.toString())}\"}")
        }
        return check(head)
    }

    /** 纯函数版（单测直接喂字节；不触文件系统）。 */
    fun check(head: ByteArray): Result {
        val f = Heif10BitProbe.parseBoxes(head)
        val want = expected()
        val problems = mutableListOf<String>()

        // ① 顶层 meta —— 这份文件到底是不是 HEIF **图片**（muxer 走 MP4 老路时只有 moov）
        if (!f.topLevel.contains("meta")) problems += "noMeta"
        // ② colr/nclx 四元组（sRGB 显示域：1/13/1/1）
        if (f.colrType != "nclx") {
            problems += "colrType=${f.colrType ?: "none"}"
        } else {
            if (f.primaries != want.primaries) problems += "primaries=${f.primaries}"
            if (f.transfer != want.transfer) problems += "transfer=${f.transfer}"
            if (f.matrix != want.matrix) problems += "matrix=${f.matrix}"
            val wantRange = if (want.fullRange) 1 else 0
            if (f.fullRange != wantRange) problems += "fullRange=${f.fullRange}"
        }
        // ③ hvcC 位深与档次（bitDepthLumaMinus8 = 2 = 10bit；profile_idc = 2 = Main10）
        if (!f.hvcCSeen) {
            problems += "noHvcC"
        } else {
            if (f.bitDepthLumaMinus8 != 2) problems += "bitDepthLumaMinus8=${f.bitDepthLumaMinus8}"
            if (f.bitDepthChromaMinus8 != 2) problems += "bitDepthChromaMinus8=${f.bitDepthChromaMinus8}"
            if (f.profileIdc != 2) problems += "profileIdc=${f.profileIdc}"
        }
        // ④ pixi 声明（缺了 macOS/部分解码端读不到 10bit —— 探针里已作为独立字段观测）
        if (!f.pixiSeen || f.pixiBitDepth != 10) problems += "pixi=${if (f.pixiSeen) f.pixiBitDepth else -1}"

        val ok = problems.isEmpty()
        val detail = buildString {
            append("{\"ok\":").append(ok)
            append(",\"boxes\":\"").append(f.topLevel.joinToString(",")).append('"')
            append(",\"colr\":\"").append(f.colrType ?: "none").append('"')
            append(",\"nclx\":[").append(f.primaries).append(',').append(f.transfer)
            append(',').append(f.matrix).append(',').append(f.fullRange).append(']')
            append(",\"want\":[").append(want.primaries).append(',').append(want.transfer)
            append(',').append(want.matrix).append(',').append(if (want.fullRange) 1 else 0).append(']')
            append(",\"profileIdc\":").append(f.profileIdc)
            append(",\"bitDepthLumaMinus8\":").append(f.bitDepthLumaMinus8)
            append(",\"bitDepthChromaMinus8\":").append(f.bitDepthChromaMinus8)
            append(",\"pixi\":").append(f.pixiSeen).append(',').append(f.pixiBitDepth)
            append(",\"ispe\":\"").append(f.width).append('x').append(f.height).append('"')
            append(",\"majorBrand\":\"").append(f.majorBrand ?: "?").append('"')
            append(",\"items\":").append(f.itemTypes.size)
            append(",\"itemTypes\":\"").append(f.itemTypes.joinToString(",")).append('"')
            append(",\"ilocItems\":").append(f.ilocItems)
            append(",\"problems\":\"").append(problems.joinToString(",")).append('"')
            append('}')
        }
        return Result(ok, detail)
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
