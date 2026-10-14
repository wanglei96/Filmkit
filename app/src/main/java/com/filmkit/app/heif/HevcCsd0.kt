package com.filmkit.app.heif

/**
 * `csd-0` → **规范 hvcC + 长度前缀帧数据** 的规范化 + 无条件诊断。
 *
 * ## 卡 FIX3 的修法（真机一手证据，0.11.36 / vivo X300）
 * 回退链已工作（10bit 失败 → 8bit HEIF → JPEG），10bit 失败点被定位到：
 * ```
 * photo.heif_csd {"len":79,…,"arrays":3,"ok":false,"head":"0000000140010c01ffff0220000003…"}
 * photo.heif_failed {"error":"csd-0 不是合法 HEVCDecoderConfigurationRecord…"}
 * ```
 * ⇒ 编码器 `outputFormat` 的 `csd-0` 是 **Annex-B**（`00 00 00 01` + VPS/SPS/PPS），
 * 不是 hvcC record。**走 `MediaMuxer` 的两条路由 muxer 免费做了这步转换，自研容器必须自己做**
 * （详见 [HevcAnnexB] 的文件头：H1 那个"106 B hvcC"是 muxer 产物，不是编码器给的）。
 *
 * ## 两条输入形态都要吃（不许依赖 vendor 行为）
 * | 输入 | 处理 |
 * |---|---|
 * | 已是 hvcC（`configurationVersion==1`） | **原样搬运**（vendor 直给时要能用） |
 * | Annex-B（含 VPS/SPS/PPS 起始码） | [HevcAnnexB.toHvcc] 转成 hvcC |
 *
 * 帧数据同一入口对齐：[HevcAnnexB.frameToLengthPrefixed] 按 hvcC 声明的
 * `lengthSizeMinusOne` 决定是否加长度前缀，**已是长度前缀的绝不二次转换**。
 *
 * ## 为什么单独一个文件（纯 JVM、零 Android 依赖）
 * 本类只能在真机跑 `MediaCodec` 的调用方之外被单测直接覆盖：真机 golden（MP4 里 muxer 转好的
 * hvcC + 4 字节前缀帧）可以反向拆成 Annex-B 再转回来做**逐字节往返**判据。
 */
object HevcCsd0 {

    /** 诊断日志事件名（成功与失败两条路径**都**发这一条）。 */
    const val EVENT = "photo.heif_csd"

    /** 到 `numOfArrays` 为止的最小长度（§8.3.3.1 表 1 的固定头 = 23 B）。 */
    const val MIN_SIZE = HevcAnnexB.HVCC_FIXED

    /** 诊断里回显的输出 hvcC 头部字节数（够覆盖固定头 23 B + 第 1 组 NAL 的长度字段）。 */
    private const val HEAD_BYTES = 32

    /** 规范化产物：[hvcc] 直接进 `ipco/hvcC`，[frame] 直接进 `mdat`（两者天然同源一致）。 */
    class Normalized(val hvcc: ByteArray, val frame: ByteArray)

    /**
     * 结构检查：是不是一条 HEVCDecoderConfigurationRecord（§8.3.3.1）。
     * **只看 `configurationVersion == 1` + 长度**；`lengthSizeMinusOne` 合法值 0/1/3，
     * 不参与判断（1/2/4 字节前缀都合法，见历史事故记录）。
     */
    fun isConfigurationRecord(h: ByteArray?): Boolean =
        h != null && h.size >= MIN_SIZE && (h[0].toInt() and 0xFF) == 1

    /**
     * 诊断字段（kv JSON 片段，与 `EngineBridge.nativeAppendLog` 的 kv 形参同格式）。
     *
     * 字段口径（真机"一轮定位"用，**必须都打**）：
     * - `csdInLen` **输入** `csd-0` 字节数（Annex-B 时 = 79 那种）；`len` = 同一个值（历史口径）
     * - `csdOutLen` **转换后** hvcC 字节数；`converted` 是否发生了 Annex-B → hvcC 转换
     * - `profile`/`bitDepthLumaMinus8`/`bitDepthChromaMinus8`/`chromaFormat`/`lengthSizeMinusOne`/
     *   `arrays`/`arrayTypes` 全部取自**输出** hvcC（10bit 正确期望值：profile=2、
     *   bitDepth*=2、chromaFormat=1）
     * - `frameMode`：`annexb` / `len4`（= `len${lengthSizeMinusOne+1}`）/ `none`（未判定）
     * - `ok`：结构与转换是否全部成功（失败分支为 false，消息在 `photo.heif_failed`）
     * - `head`：**输出** hvcC 前 32 B；转换抛异常时回落到**输入** `csd-0` 的前 32 B
     *   （否则失败分支一个字节都看不到，"一轮定位"就断了）
     *
     * `csd-0` 完全没有 ⇒ `csdInLen=0`、`csdOutLen=0`、数值字段 `-1`、`head=""` ——
     * 这条日志本身就是"卡在哪一步"的判据，不许因为拿不到字节就不发。
     */
    fun fields(csdIn: ByteArray?, hvcc: ByteArray?, converted: Boolean, frameMode: String): String {
        val csdInLen = csdIn?.size ?: 0
        val b = hvcc ?: csdIn ?: ByteArray(0)
        val len = if (hvcc != null) hvcc.size else 0
        val ver = if (len >= 1) b[0].toInt() and 0xFF else -1
        val profile = if (len >= 2) b[1].toInt() and 0x1F else -1
        val bitDepthLumaMinus8 = if (len >= 18) b[17].toInt() and 0x07 else -1
        val bitDepthChromaMinus8 = if (len >= 19) b[18].toInt() and 0x07 else -1
        val chromaFormat = if (len >= 17) b[16].toInt() and 0x03 else -1
        val lengthSizeMinusOne = if (len >= 22) b[21].toInt() and 0x03 else -1
        val arrays = if (len >= 23) b[22].toInt() and 0xFF else -1
        val arrayTypes = if (arrays > 0) arrayTypes(b, arrays) else emptyList()
        val head = b.take(HEAD_BYTES).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return buildString {
            append("{\"len\":").append(csdInLen)
            append(",\"csdInLen\":").append(csdInLen)
            append(",\"csdOutLen\":").append(len)
            append(",\"converted\":").append(converted)
            append(",\"ver\":").append(ver)
            append(",\"profile\":").append(profile)
            append(",\"bitDepthLumaMinus8\":").append(bitDepthLumaMinus8)
            append(",\"bitDepthChromaMinus8\":").append(bitDepthChromaMinus8)
            append(",\"chromaFormat\":").append(chromaFormat)
            append(",\"lengthSizeMinusOne\":").append(lengthSizeMinusOne)
            append(",\"arrays\":").append(arrays)
            append(",\"arrayTypes\":[").append(arrayTypes.joinToString(",")).append("]")
            append(",\"frameMode\":\"").append(frameMode).append("\"")
            append(",\"ok\":").append(isConfigurationRecord(hvcc))
            append(",\"head\":\"").append(head).append("\"}")
        }
    }

    /** 遍历 hvcC 的数组取 `NAL_unit_type` 清单（诊断用；越界即停，不抛）。 */
    private fun arrayTypes(h: ByteArray, arrays: Int): List<Int> {
        val out = ArrayList<Int>(arrays)
        var o = MIN_SIZE
        repeat(arrays) {
            if (o >= h.size) return out
            out += h[o].toInt() and 0x3F
            o++
            if (o + 1 >= h.size) return out
            val cnt = ((h[o].toInt() and 0xFF) shl 8) or (h[o + 1].toInt() and 0xFF)
            o += 2
            repeat(cnt) {
                if (o + 1 >= h.size) return out
                val ln = ((h[o].toInt() and 0xFF) shl 8) or (h[o + 1].toInt() and 0xFF)
                o += 2 + ln
            }
        }
        return out
    }

    /**
     * 交付前的**唯一入口**：把 `csd-0` 与帧数据规范化成"hvcC + 长度前缀 NAL"，
     * 并**无条件**发一条 [EVENT] 诊断（成功/失败都有 ⇒ 失败分支也留下实况）。
     *
     * @param csd `outputFormat` 的 `csd-0` 原始字节（Annex-B 或 hvcC 都可以）。
     * @param frame 编码器输出缓冲拼起来的帧数据（Annex-B 或长度前缀都可以）。
     * @param emit 日志出口；生产 = `EngineBridge.nativeAppendLog(…, EVENT, fields)`，单测注入收集器。
     * @throws IllegalStateException `csd == null` / 转换失败 / 结构不合法 / 帧数据形态无法判定
     *   —— 调用方必须回退（照片优先红线），不许吞掉异常当成功。
     */
    fun accept(csd: ByteArray?, frame: ByteArray, emit: (String) -> Unit): Normalized {
        var hvcc: ByteArray? = null
        var converted = false
        var mode = "none"
        try {
            if (csd == null || csd.isEmpty()) {
                error("编码器 outputFormat 未提供 csd-0（hvcC 或 Annex-B），无法自研封装：len=0")
            }
            val h = if (isConfigurationRecord(csd)) {
                csd
            } else {
                // 统一失败类型：调用方只需 catch IllegalStateException（编码器给的 csd-0 不可用），
                // cause 里保留具体是哪条语法/边界不满足（"一轮定位"用）
                val convertedHvcc = try {
                    HevcAnnexB.toHvcc(csd)
                } catch (t: IllegalArgumentException) {
                    throw IllegalStateException("csd-0 是 Annex-B 形态但转换 hvcC 失败：${t.message}", t)
                }
                converted = true
                convertedHvcc
            }
            hvcc = h
            if (!isConfigurationRecord(h)) {
                error(
                    "csd-0 既不是合法 HEVCDecoderConfigurationRecord（§8.3.3.1）也无法由 Annex-B 转换：" +
                        "in=${csd.size} out=${h.size} h[0]=0x%02x".format(h[0].toInt() and 0xFF),
                )
            }
            val ls = h[21].toInt() and 3
            mode = when (HevcAnnexB.frameMode(frame, ls)) {
                HevcAnnexB.FrameMode.ANNEXB -> "annexb"
                HevcAnnexB.FrameMode.LEN_PREFIXED -> "len${ls + 1}"
                HevcAnnexB.FrameMode.UNKNOWN -> "none"
            }
            val f = HevcAnnexB.frameToLengthPrefixed(frame, ls)
            return Normalized(h, f)
        } finally {
            // emit 放在 finally ⇒ 任何失败分支都留下 csd-0 实况（诊断在结构/转换检查之后才拿到
            // 输出字节，所以不能像 FIX2 那样"先 emit 再 check"，只能保证"必发且只发一次"）。
            emit(fields(csd, hvcc, converted, mode))
        }
    }
}
