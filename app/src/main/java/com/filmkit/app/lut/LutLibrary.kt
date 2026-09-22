package com.filmkit.app.lut

import android.content.Context
import android.os.SystemClock
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv

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

    /** 内置资产全部预载（App 启动时后台调一次）：卡片预览需要 LUT 数据，预载后开面板即有内容 */
    fun preload(context: Context) {
        builtIn.forEach { load(context, it) }
    }
}
