package com.filmkit.app.heif

/**
 * `csd-0`（**HEVCDecoderConfigurationRecord**，ISO/IEC 14496-15 §8.3.3.1）的
 * **结构检查 + 诊断字段**。
 *
 * ## 为什么单独一个文件（纯 JVM、零 Android 依赖）
 * `Hevc10Encoder` 本体只能在真机跑（`MediaCodec`），而本次事故的成因恰恰是**结构判断写错**——
 * 把判断与诊断提出到这里 ⇒ 单测能直接喂合法/非法的 hvcC 字节覆盖全部分支（硬判据 1 的
 * ① ② ③ 三条都在这里测）。
 *
 * ## 本次修的过强断言（真机一手证据，2026-10-14 / 0.11.35）
 * 原实现 `check((h[21] and 3) == 3) { "hvcC.lengthSizeMinusOne != 3 …" }` —— 真机
 * `photo.heif_failed{"stage":"encode","ms":1818,"error":"hvcC.lengthSizeMinusOne != 3 …"}`
 * 两次拍摄都把 10bit 交付打死（回退 JPEG 才拿到片子）。
 *
 * **原断言错在哪**：`lengthSizeMinusOne` 是 **hvcC 自己声明的** NAL 长度前缀宽度
 * （§8.3.3.1 表 1：`byte21` 的低 2 位 = `lengthSizeMinusOne`，值 0/1/3 ⇒ 1/2/4 字节前缀）。
 * HEIF 的 `hvc1` item data 就是该 record 所描述的那条码流 ⇒ **解码端按这个字段解析**，
 * 1/2/4 字节都合法。硬要求 `==3` 是 H1 阶段基于探针样本（1024×256 测试图，真机那时恰好
 * 给 4 字节前缀）的**过强假设**，换分辨率/机型就可能不成立（本项目 X300 + 3072×4080 就是）。
 *
 * ⇒ 正确的检查只剩**"它是不是一条 hvcC"**：`configurationVersion(byte0) == 1` 且长度够到
 * `numOfArrays`（23 B，见 [MIN_SIZE]）。**不改写** `lengthSizeMinusOne`、**不重打包** NAL：
 * hvcC 与帧数据同源搬进容器，天然一致。
 */
object HevcCsd0 {

    /** 诊断日志事件名（成功与失败两条路径**都**发这一条；硬判据 1 ③）。 */
    const val EVENT = "photo.heif_csd"

    /**
     * 到 `numOfArrays` 为止的最小长度：`configurationVersion(1) + profile/tier/idc(1) +
     * compatibility(4) + constraints(6) + level(1) + minSpatialSeg(2) + parallelism(1) +
     * chromaFormat(1) + bitDepthLuma(1) + bitDepthChroma(1) + avgFrameRate(2) +
     * constantFrameRate/lengthSizeMinusOne(1) + numOfArrays(1) = 23`。
     */
    const val MIN_SIZE = 23

    /** 诊断里回显的头部字节数（够覆盖 hvcC 固定头 23 B + 第 1 组 NAL 的长度字段）。 */
    private const val HEAD_BYTES = 32

    /**
     * 结构检查：是不是一条 HEVCDecoderConfigurationRecord（§8.3.3.1）。
     * **只看 `configurationVersion == 1` + 长度**；`lengthSizeMinusOne` 合法值 0/1/3，
     * 不参与判断（见文件头）。
     */
    fun isConfigurationRecord(h: ByteArray?): Boolean =
        h != null && h.size >= MIN_SIZE && (h[0].toInt() and 0xFF) == 1

    /**
     * 诊断字段（kv JSON 片段，与 `EngineBridge.nativeAppendLog` 的 kv 形参同格式）。
     *
     * `h == null`（编码器根本没吐 `csd-0`）⇒ `len=0`、其余数值字段 `-1`、`head=""`：
     * 这条日志本身就是"卡在哪一步"的判据，不许因为拿不到字节就不发。
     */
    fun fields(h: ByteArray?): String {
        val b = h ?: ByteArray(0)
        val len = b.size
        val ver = if (len >= 1) b[0].toInt() and 0xFF else -1
        val profile = if (len >= 2) b[1].toInt() and 0x1F else -1
        val bitDepthLumaMinus8 = if (len >= 18) b[17].toInt() and 0x07 else -1
        val bitDepthChromaMinus8 = if (len >= 19) b[18].toInt() and 0x07 else -1
        val chromaFormat = if (len >= 17) b[16].toInt() and 0x03 else -1
        val lengthSizeMinusOne = if (len >= 22) b[21].toInt() and 0x03 else -1
        val arrays = if (len >= 23) b[22].toInt() and 0xFF else -1
        val head = b.take(HEAD_BYTES).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return buildString {
            append("{\"len\":").append(len)
            append(",\"ver\":").append(ver)
            append(",\"profile\":").append(profile)
            append(",\"bitDepthLumaMinus8\":").append(bitDepthLumaMinus8)
            append(",\"bitDepthChromaMinus8\":").append(bitDepthChromaMinus8)
            append(",\"chromaFormat\":").append(chromaFormat)
            append(",\"lengthSizeMinusOne\":").append(lengthSizeMinusOne)
            append(",\"arrays\":").append(arrays)
            append(",\"ok\":").append(isConfigurationRecord(h))
            append(",\"head\":\"").append(head).append("\"}")
        }
    }

    /**
     * 交付前的**唯一入口**：先发诊断（无条件），再做结构检查（不合法即抛）。
     *
     * 顺序是刻意的：`emit` 在 `check` **之前** ⇒ 失败分支也留下 csd-0 实况
     *（硬判据 1 ③ / 变异检验 ②）。
     *
     * @param emit 日志出口；生产 = `EngineBridge.nativeAppendLog(…, EVENT, fields)`，
     *   单测注入收集器。
     * @return 原样返回 [h]（调用方直接搬进容器，不做任何改写）。
     * @throws IllegalStateException `h == null`（编码器没给 csd-0）或结构不合法
     *   —— 消息里带**实际长度与首字节**，供下一轮一轮定位。
     */
    fun accept(h: ByteArray?, emit: (String) -> Unit): ByteArray {
        emit(fields(h))
        if (h == null) {
            error("编码器 outputFormat 未提供 csd-0（hvcC），无法自研封装：len=0")
        }
        if (!isConfigurationRecord(h)) {
            val first = if (h.isNotEmpty()) "0x%02x".format(h[0].toInt() and 0xFF) else "(空)"
            error(
                "csd-0 不是合法 HEVCDecoderConfigurationRecord（ISO/IEC 14496-15 §8.3.3.1）：" +
                    "len=${h.size} h[0]=$first（要求 len>=$MIN_SIZE 且 configurationVersion(byte0)==1）",
            )
        }
        return h
    }
}
