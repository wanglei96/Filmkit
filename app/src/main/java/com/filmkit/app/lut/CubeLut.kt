package com.filmkit.app.lut

/**
 * .cube（Iridas/Resolve）3D LUT 资产。
 *
 * 数据顺序与 .cube 文本一致：**红通道变化最快**（idx = r + g*N + b*N*N），
 * 因此上传 GL_TEXTURE_3D 时 x=r / y=g / z=b 可直接对应，无需重排。
 *
 * @param title     TITLE 字段（内置资产即原版名，如「理光负片」；不做中性化）
 * @param size      3D 边长 N（本项目内置资产为 33）
 * @param data      RGB 交错浮点，长度 = N³×3
 * @param domainMin 输入域下限（DOMAIN_MIN，通常 0）
 * @param domainMax 输入域上限（DOMAIN_MAX，通常 1）—— 采样前按 (c-min)/(max-min) 归一化
 */
class CubeLut(
    val title: String,
    val size: Int,
    val data: FloatArray,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
)

/**
 * .cube 文本解析（纯 Kotlin，无 Android 依赖 —— 单测覆盖正常与畸形输入）。
 *
 * 解析失败一律抛 [IllegalArgumentException]，由调用侧回退「无 LUT」并记日志；
 * 不做静默降级（静默失败是本项目的高危模式：EXIF 曝光时间、参数全零都栽在这上面）。
 */
object CubeLutParser {

    /** 边长上限：33³ 是常规上限，64³ 只为挡住畸形文件导致的巨型分配 */
    private const val MAX_SIZE = 64

    fun parse(text: String, fallbackTitle: String = "LUT"): CubeLut {
        var title: String? = null
        var size = 0
        val dmin = floatArrayOf(0f, 0f, 0f)
        val dmax = floatArrayOf(1f, 1f, 1f)
        var acc: FloatArray? = null
        var filled = 0
        var lineNo = 0

        for (raw in text.lineSequence()) {
            lineNo++
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            when {
                line.startsWith("TITLE") ->
                    title = line.removePrefix("TITLE").trim().trim('"')
                line.startsWith("LUT_1D_SIZE") ->
                    throw IllegalArgumentException("1D LUT 暂未支持（第 $lineNo 行）")
                line.startsWith("LUT_3D_INPUT_RANGE") -> {
                    // Resolve 写法：3 个 min + 3 个 max，与 DOMAIN_MIN/MAX 同义
                    val t = FloatArray(6)
                    parseN(line.substringAfter(' '), t, 6, lineNo)
                    for (c in 0..2) {
                        dmin[c] = t[c]
                        dmax[c] = t[3 + c]
                    }
                }
                line.startsWith("LUT_3D_SIZE") -> {
                    size = line.removePrefix("LUT_3D_SIZE").trim().toIntOrNull()
                        ?: throw IllegalArgumentException("LUT_3D_SIZE 非法（第 $lineNo 行）：$line")
                    require(size in 2..MAX_SIZE) { "LUT_3D_SIZE 超出范围（第 $lineNo 行）：$size" }
                    acc = FloatArray(size * size * size * 3)
                }
                line.startsWith("DOMAIN_MIN") -> parseN(line.substringAfter(' '), dmin, 3, lineNo)
                line.startsWith("DOMAIN_MAX") -> parseN(line.substringAfter(' '), dmax, 3, lineNo)
                else -> {
                    val a = acc
                        ?: throw IllegalArgumentException("数据行出现在 LUT_3D_SIZE 之前（第 $lineNo 行）")
                    val t = FloatArray(3)
                    parseN(line, t, 3, lineNo)
                    if (filled + 3 > a.size) {
                        throw IllegalArgumentException("数据行多于 LUT_3D_SIZE³（第 $lineNo 行）")
                    }
                    a[filled] = t[0]
                    a[filled + 1] = t[1]
                    a[filled + 2] = t[2]
                    filled += 3
                }
            }
        }

        val d = acc ?: throw IllegalArgumentException("缺少 LUT_3D_SIZE")
        require(filled == d.size) { "数据行不足：期望 ${d.size / 3} 个 RGB 三元组，实得 ${filled / 3}" }
        require(dmin[0] < dmax[0] && dmin[1] < dmax[1] && dmin[2] < dmax[2]) {
            "DOMAIN_MIN/MAX 非法：${dmin.toList()} / ${dmax.toList()}"
        }
        return CubeLut(title?.takeIf { it.isNotEmpty() } ?: fallbackTitle, size, d, dmin, dmax)
    }

    /**
     * 二进制内置资产解析（`assets/luts/ 下的 .lut`，由 `tools/gen-lut-assets.py` 生成）：
     * `[size:1][titleLen:1][title][catLen:1][category][size³×3 RGB(uint8)]`。
     *
     * **内置打包格式与用户交换格式是两件事**：42 个 33³ 资产用 ASCII .cube 要 40MB，
     * uint8 只要 4.3MB；源 LUT 数据本身是 8-bit 精度（值落在 1/255 或 1/512 网格）、
     * 成片也是 8-bit JPEG，量化误差 ≤0.5 LSB 在成片上不可见。
     * §6.2 的 .cube 导入导出不受影响 —— 见 [parse]。
     *
     * 本函数只取 LUT 数据；title/category 由 [LutLibrary] 单独读头部（列表渲染不需要整表）。
     */
    fun parseBinary(bytes: ByteArray, fallbackTitle: String = "LUT"): CubeLut {
        require(bytes.size >= 2) { "资产过短：${bytes.size} 字节" }
        val size = bytes[0].toInt() and 0xFF
        require(size in 2..MAX_SIZE) { "LUT_3D_SIZE 超出范围：$size" }
        val titleLen = bytes[1].toInt() and 0xFF
        require(bytes.size >= 3 + titleLen) { "头部截断：${bytes.size} 字节" }
        val catLen = bytes[2 + titleLen].toInt() and 0xFF
        val need = 3 + titleLen + catLen + size * size * size * 3
        require(bytes.size >= need) { "数据不足：期望 $need 字节，实得 ${bytes.size}" }
        val title = if (titleLen > 0) String(bytes, 2, titleLen, Charsets.UTF_8) else ""
        val data = FloatArray(size * size * size * 3)
        val o = 3 + titleLen + catLen
        for (i in data.indices) data[i] = (bytes[o + i].toInt() and 0xFF) / 255f
        return CubeLut(
            title.takeIf { it.isNotEmpty() } ?: fallbackTitle,
            size, data, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f),
        )
    }

    /**
     * 取 [count] 个浮点。手写扫描而非 split(Regex)：内置资产是 35937 个数据行，
     * 正则拆分在加载时是纯浪费（解析只做一次，但要占用首帧时间）。
     *
     * 注意 [body] 必须是**已去掉关键字前缀**的数值段（数据行本身就是纯数值）。
     */
    private fun parseN(body: String, out: FloatArray, count: Int, lineNo: Int) {
        var i = 0
        var k = 0
        while (k < count) {
            while (i < body.length && body[i].isWhitespace()) i++
            val st = i
            while (i < body.length && !body[i].isWhitespace()) i++
            if (st == i) {
                throw IllegalArgumentException("需要 $count 个数值，只找到 $k 个（第 $lineNo 行）：$body")
            }
            out[k] = body.substring(st, i).toFloatOrNull()
                ?: throw IllegalArgumentException("数值非法（第 $lineNo 行）：$body")
            k++
        }
    }
}
