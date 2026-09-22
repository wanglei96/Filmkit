package com.filmkit.app.lut

import android.content.Context
import android.os.SystemClock
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv
import kotlin.math.roundToInt

/**
 * 内置 LUT 资产库（M4-1）。
 *
 * 资产放在 `assets/luts/` 目录（33³ .cube，display 域 —— 引擎按 §0-D10 接在输出 OETF 之后）。
 * 文件名用 ASCII 以免资产路径编码问题，**显示名取 .cube 的 TITLE**：即用户给的原版名
 * （理光负片 / 富士CC / 徕卡M9 …），按 2026-09-21 拍板**暂不做中性化**。
 *
 * 解析只在首次选中时做一次（35937 行 ≈ 数十毫秒），结果缓存；失败返回 null 并记日志，
 * 调用侧回退「无 LUT」——不静默降级到某个猜测的资产。
 */
object LutLibrary {

    /**
     * @param id    prefs 里持久化的标识（`lut_id`）
     * @param asset assets 内路径
     * @param label 显示名（资产 TITLE 缺失时的回退，与 TITLE 保持一致）
     */
    data class Entry(val id: String, val asset: String, val label: String)

    /** 清单顺序 = UI 显示顺序 */
    val builtIn: List<Entry> = listOf(
        Entry("ricoh_neg", "luts/ricoh_neg.cube", "理光负片"),
        Entry("ricoh_pos", "luts/ricoh_pos.cube", "理光正片"),
        Entry("fuji_cc", "luts/fuji_cc.cube", "富士CC"),
        Entry("fuji_nc", "luts/fuji_nc.cube", "富士NC"),
        Entry("leica_m9", "luts/leica_m9.cube", "徕卡M9"),
        Entry("leica_bw", "luts/leica_bw.cube", "徕卡黑白"),
    )

    private val cache = HashMap<String, CubeLut>()

    fun byId(id: String?): Entry? = id?.let { want -> builtIn.firstOrNull { it.id == want } }

    /** 加载 + 解析（IO 线程调用）。失败返回 null（已记 `lut.parse_failed`）。 */
    fun load(context: Context, entry: Entry): CubeLut? {
        cache[entry.id]?.let { return it }
        return try {
            val t0 = SystemClock.elapsedRealtime()
            val text = context.assets.open(entry.asset).bufferedReader().use { it.readText() }
            val lut = CubeLutParser.parse(text, entry.label)
            cache[entry.id] = lut
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "engine", "lut.parse",
                kv(
                    "id" to entry.id, "size" to lut.size, "title" to lut.title,
                    "ms" to (SystemClock.elapsedRealtime() - t0),
                ),
            )
            lut
        } catch (t: Throwable) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "engine", "lut.parse_failed",
                kv("id" to entry.id, "error" to (t.message ?: t.toString())),
            )
            null
        }
    }

    /** 内置资产全部预载（App 启动时后台调一次）：选择页的色卡需要数据，预载后打开面板即时有内容 */
    fun preload(context: Context) {
        builtIn.forEach { load(context, it) }
    }

    /**
     * 代表色卡（5 个色块）：对典型输入（暗/中/亮灰 + 暖肤色 + 冷调）采样 LUT 输出，
     * 供选择页卡片做视觉标识。引擎侧的**实时缩略图**是后续项（需要额外一个小 pass 渲染
     * 当前帧 × 每套 LUT），本函数是零成本的替代：纯 Kotlin、可缓存、无 GPU 开销。
     */
    fun swatch(lut: CubeLut): IntArray {
        val probes = floatArrayOf(
            0.12f, 0.12f, 0.12f,
            0.45f, 0.45f, 0.45f,
            0.82f, 0.82f, 0.82f,
            0.62f, 0.42f, 0.32f,
            0.32f, 0.48f, 0.66f,
        )
        val out = IntArray(probes.size / 3)
        for (i in out.indices) {
            val c = sample(lut, probes[i * 3], probes[i * 3 + 1], probes[i * 3 + 2])
            out[i] = (0xFF shl 24) or (to255(c[0]) shl 16) or (to255(c[1]) shl 8) or to255(c[2])
        }
        return out
    }

    private fun to255(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt()

    /** 三线性采样（仅用于色卡；渲染侧是 shader 里的四面体插值，两者不必一致） */
    private fun sample(lut: CubeLut, r: Float, g: Float, b: Float): FloatArray {
        val n = lut.size
        val max = n - 1
        val pr = r.coerceIn(0f, 1f) * max
        val pg = g.coerceIn(0f, 1f) * max
        val pb = b.coerceIn(0f, 1f) * max
        val r0 = pr.toInt().coerceIn(0, max - 1)
        val g0 = pg.toInt().coerceIn(0, max - 1)
        val b0 = pb.toInt().coerceIn(0, max - 1)
        val fr = pr - r0
        val fg = pg - g0
        val fb = pb - b0
        fun at(rr: Int, gg: Int, bb: Int): FloatArray {
            val i = ((bb * n + gg) * n + rr) * 3
            return floatArrayOf(lut.data[i], lut.data[i + 1], lut.data[i + 2])
        }
        val c000 = at(r0, g0, b0)
        val c100 = at(r0 + 1, g0, b0)
        val c010 = at(r0, g0 + 1, b0)
        val c110 = at(r0 + 1, g0 + 1, b0)
        val c001 = at(r0, g0, b0 + 1)
        val c101 = at(r0 + 1, g0, b0 + 1)
        val c011 = at(r0, g0 + 1, b0 + 1)
        val c111 = at(r0 + 1, g0 + 1, b0 + 1)
        val o = FloatArray(3)
        for (k in 0..2) {
            val c00 = c000[k] * (1 - fr) + c100[k] * fr
            val c10 = c010[k] * (1 - fr) + c110[k] * fr
            val c01 = c001[k] * (1 - fr) + c101[k] * fr
            val c11 = c011[k] * (1 - fr) + c111[k] * fr
            val c0 = c00 * (1 - fg) + c10 * fg
            val c1 = c01 * (1 - fg) + c11 * fg
            o[k] = c0 * (1 - fb) + c1 * fb
        }
        return o
    }
}
