package com.filmkit.app.camera

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * GCam 传感器噪声标定（`*.c` 文件，社区标准格式）—— PC 式"可外部导入、可切换"的
 * 噪声模型来源（2026-09-29 用户需求）。
 *
 * 文件由 GCam 的噪声标定工具生成，核心是四个静态数组（R, Gr, Gb, B 四通道）：
 * ```
 * double compute_noise_model_entry_S(int plane, int sens) {
 *     static double noise_model_A[] = { ... };   // S = A·iso + B（clamp ≥0）
 *     static double noise_model_B[] = { ... };
 * }
 * double compute_noise_model_entry_O(int plane, int sens) {
 *     static double noise_model_C[] = { ... };   // O = C·iso² + D·dg²（clamp ≥0）
 *     static double noise_model_D[] = { ... };
 *     double digital_gain = (sens / <maxAnalog>) < 1.0 ? 1.0 : (sens / <maxAnalog>);
 * }
 * ```
 * 求值语义与 PhotonCamera 的 `CalibratedRawNoiseProfile`（ISO_POLYNOMIAL）同构：
 *   S(iso) = max(A·iso + B, 0)
 *   O(iso) = max(C·iso² + D·digitalGain², 0)，digitalGain = max(iso / maxAnalog, 1)
 * 输出域 = [0,1] 归一化 raw 域（与 DNG NoiseProfile / Camera2 同域），通道序对外
 * 固定 R,G,B（G = (Gr+Gb)/2，与 NoiseProfile 的 MGC 口径一致）。
 */
class GcamNoiseCalib private constructor(
    val id: String,
    val name: String,
    private val a: DoubleArray,
    private val b: DoubleArray,
    private val c: DoubleArray,
    private val d: DoubleArray,
    private val maxAnalog: Double,
) {

    /** ISO → [S_r,S_g,S_b,O_r,O_g,O_b]；null = 该 ISO 下模型退化（全零） */
    fun evaluate(iso: Int): FloatArray? {
        val isoD = iso.coerceAtLeast(1).toDouble()
        // dg = max(iso / maxAnalog, 1)；maxAnalog 缺失（非有限）= 不启用数字增益缩放
        val dg = if (maxAnalog.isFinite() && maxAnalog > 0.0) maxOf(isoD / maxAnalog, 1.0) else 1.0
        val dg2 = dg * dg
        val s4 = DoubleArray(4)
        val o4 = DoubleArray(4)
        for (p in 0 until 4) {
            s4[p] = (a[p] * isoD + b[p]).coerceAtLeast(0.0)
            o4[p] = (c[p] * isoD * isoD + d[p] * dg2).coerceAtLeast(0.0)
        }
        val sg = 0.5 * (s4[1] + s4[2])
        val og = 0.5 * (o4[1] + o4[2])
        if (s4[0] <= 0.0 || sg <= 0.0 || s4[3] <= 0.0) return null
        if (o4[0] <= 0.0 && og <= 0.0 && o4[3] <= 0.0) return null
        return floatArrayOf(
            s4[0].toFloat(), sg.toFloat(), s4[3].toFloat(),
            o4[0].toFloat(), og.toFloat(), o4[3].toFloat(),
        )
    }

    companion object {
        private val NUMBER = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")

        private fun parseArray(src: String, name: String): DoubleArray? {
            val init = Regex(
                """noise_model_$name\s*\[\s*\d*\s*\]\s*=\s*\{([^}]*)\}""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(src)?.groupValues?.get(1)
                ?: Regex(
                    """static\s+double\s+noise_model_$name\b(.*?)(?=static\s+double\s+noise_model_[ABCD]\b|\z)""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                ).find(src)?.groupValues?.get(1)
                ?: return null
            val vals = NUMBER.findAll(init)
                .mapNotNull { m -> m.value.toDoubleOrNull()?.takeIf { it.isFinite() } }
                .toList()
            return if (vals.size >= 4) vals.take(4).toDoubleArray() else null
        }

        /** 解析 .c 全文；任何结构缺失/非法 → null（调用方回退自动来源） */
        fun parse(id: String, name: String, text: String): GcamNoiseCalib? {
            if (text.isBlank()) return null
            val a = parseArray(text, "A") ?: return null
            val b = parseArray(text, "B") ?: return null
            val c = parseArray(text, "C") ?: return null
            val d = parseArray(text, "D") ?: return null
            // 数字增益除数（生成文件里内嵌为 sens / <maxAnalog>）。缺失/非法时取
            // +∞ = 不启用数字增益缩放（**不能取 1**：dg=iso 会让 read 项随 ISO² 爆炸）。
            val divisor = Regex("""sens\s*/\s*(${NUMBER.pattern})""")
                .find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            val maxAnalog = divisor?.takeIf { it.isFinite() && it > 0.0 } ?: Double.POSITIVE_INFINITY
            return GcamNoiseCalib(id, name, a, b, c, d, maxAnalog)
        }
    }
}

/**
 * 噪声模型标定库：`filesDir/noise_models/` 下的 `*.c`（外部导入）+ prefs
 * `noise_model_src` 记录选择（"auto" = Camera2 SENSOR_NOISE_PROFILE；否则 = 文件名）。
 * 扫目录带缓存（按 file.lastModified 失效），解析失败的文件跳过。
 */
object NoiseCalibStore {
    const val AUTO = "auto"
    private const val DIR = "noise_models"

    private class Entry(val modified: Long, val calib: GcamNoiseCalib?)

    private val cache = HashMap<String, Entry>()

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** 列出可用标定（按文件名排序；解析失败的跳过） */
    fun list(context: Context): List<GcamNoiseCalib> {
        val d = dir(context)
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".c", ignoreCase = true) }
            ?: return emptyList()
        val out = ArrayList<GcamNoiseCalib>(files.size)
        for (f in files.sortedBy { it.name }) {
            val e = loadFile(context, f.name) ?: continue
            out.add(e)
        }
        return out
    }

    /** 按选择加载（"auto" 返回 null = 调用方走 Camera2） */
    fun load(context: Context, id: String): GcamNoiseCalib? {
        if (id == AUTO) return null
        return loadFile(context, id)
    }

    private fun loadFile(context: Context, fileName: String): GcamNoiseCalib? {
        val f = File(dir(context), fileName)
        if (!f.exists()) return null
        val key = fileName
        cache[key]?.let { if (it.modified == f.lastModified()) return it.calib }
        val calib = runCatching {
            GcamNoiseCalib.parse(fileName, fileName.removeSuffix(".c"), f.readText())
        }.getOrNull()
        cache[key] = Entry(f.lastModified(), calib)
        return calib
    }

    /** 导入一个 .c 文件（SAF uri）；同名覆盖。返回落盘文件名（失败 null） */
    fun importFrom(context: Context, uri: Uri, displayName: String?): String? {
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (raw.isEmpty() || raw.size > 1024 * 1024) return null
        val text = String(raw, Charsets.UTF_8)
        // 先验证能解析，再落盘（避免坏文件进库）
        var name = (displayName ?: "noise_model").substringAfterLast('/').ifBlank { "noise_model" }
        if (!name.endsWith(".c", ignoreCase = true)) name += ".c"
        GcamNoiseCalib.parse(name, name, text) ?: return null
        return runCatching {
            File(dir(context), name).writeText(text)
            cache.remove(name)
            name
        }.getOrNull()
    }

    fun delete(context: Context, id: String): Boolean = runCatching {
        val ok = File(dir(context), id).delete()
        cache.remove(id)
        ok
    }.getOrDefault(false)
}
