package com.filmkit.app.lut

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv

/**
 * 内置 LUT 资产库（M4）。
 *
 * 资产 = `assets/luts/ 下的 .lut`（33³ uint8 二进制，由 `tools/gen-lut-assets.py` 从 64³ 原始
 * .cube 重采样生成）。**清单不硬编码**：直接扫目录，显示名与分类取资产头部。
 *
 * **打包格式 ≠ 交换格式**：内置用二进制（42 个只要 4.3MB，ASCII .cube 要 40MB），
 * 用户导入导出仍走 §6.2 的 .cube —— [CubeLutParser.parse] 与它的单测完整保留。
 *
 * 分类（M4-3）：默认来自资产内置的品牌，用户可把某个 LUT 改到别的分类，也可新建分类。
 * 覆盖与自定义分类都存在 prefs，**不动资产**（重新生成资产不会冲掉用户分类）。
 *
 * 解析只在首次选中时做一次，结果缓存；失败返回 null 并记日志，调用侧回退「无 LUT」
 * —— 不静默降级到某个猜测的资产。
 */
object LutLibrary {

    /**
     * @param id       prefs 里持久化的标识（`lut_id`）= 文件名去扩展名
     * @param asset    assets 内路径
     * @param label    显示名（资产 TITLE；缺失时回退 id）
     * @param category 内置默认分类（资产里的品牌；用户覆盖见 [categoryOf]）
     */
    data class Entry(val id: String, val asset: String, val label: String, val category: String)

    private const val KEY_CATS = "lut_cats" // 用户新建的分类，`\n` 分隔
    private const val KEY_CAT_PREFIX = "lut_cat_" // 单个 LUT 的分类覆盖

    private var cacheEntries: List<Entry>? = null
    private val cache = HashMap<String, CubeLut>()

    /** 清单（扫描目录 + 读头部，进程内缓存一次） */
    fun entries(context: Context): List<Entry> = cacheEntries ?: run {
        val list = (context.assets.list("luts") ?: emptyArray())
            .filter { it.endsWith(".lut") }
            .sorted()
            .map { file ->
                val path = "luts/$file"
                val id = file.removeSuffix(".lut")
                val head = headOf(context, path)
                Entry(
                    id, path,
                    head?.title?.takeIf { it.isNotEmpty() } ?: id,
                    head?.category.orEmpty(),
                )
            }
        cacheEntries = list
        list
    }

    private data class Head(val title: String, val category: String)

    /** 只读头部（两字节长度 + title + category），不解析整个 LUT —— 列表渲染每项都要显示名 */
    private fun headOf(context: Context, path: String): Head? = try {
        context.assets.open(path).use { ins ->
            val head = ByteArray(2)
            if (ins.read(head) != 2) return@use null
            val tl = head[1].toInt() and 0xFF
            val tb = ByteArray(tl)
            if (tl > 0 && ins.read(tb) != tl) return@use null
            val cb = ByteArray(1)
            if (ins.read(cb) != 1) return@use null
            val cl = cb[0].toInt() and 0xFF
            val catb = ByteArray(cl)
            if (cl > 0 && ins.read(catb) != cl) return@use null
            Head(
                if (tl > 0) String(tb, Charsets.UTF_8) else "",
                if (cl > 0) String(catb, Charsets.UTF_8) else "",
            )
        }
    } catch (t: Throwable) {
        null
    }

    fun byId(context: Context, id: String?): Entry? =
        id?.let { want -> entries(context).firstOrNull { it.id == want } }

    // ── 分类（M4-3）：默认品牌来自资产，用户可覆盖 + 新建 ────────────────────

    /** 用户新建的分类（顺序即创建顺序） */
    fun customCategories(prefs: SharedPreferences): List<String> =
        prefs.getString(KEY_CATS, "").orEmpty().split('\n').filter { it.isNotBlank() }

    /** 某个 LUT 的分类：用户覆盖优先，否则资产内置品牌 */
    fun categoryOf(prefs: SharedPreferences, entry: Entry): String =
        prefs.getString(KEY_CAT_PREFIX + entry.id, null)?.takeIf { it.isNotBlank() }
            ?: entry.category

    fun setCategory(prefs: SharedPreferences, id: String, category: String) {
        prefs.edit().putString(KEY_CAT_PREFIX + id, category).apply()
    }

    fun addCategory(prefs: SharedPreferences, name: String) {
        val n = name.trim()
        if (n.isEmpty() || n.contains('\n')) return
        val cur = customCategories(prefs)
        if (n in cur) return
        prefs.edit().putString(KEY_CATS, (cur + n).joinToString("\n")).apply()
    }

    /** 删除用户新建的分类：同时把归入它的 LUT 覆盖清掉（回到资产内置品牌，LUT 本身不动） */
    fun removeCategory(context: Context, prefs: SharedPreferences, name: String) {
        val e = prefs.edit().putString(KEY_CATS, customCategories(prefs).filter { it != name }.joinToString("\n"))
        entries(context).forEach {
            if (categoryOf(prefs, it) == name) e.remove(KEY_CAT_PREFIX + it.id)
        }
        e.apply()
    }

    /** 全部分类（资产内置品牌 + 用户新建，去重保序） */
    fun allCategories(context: Context, prefs: SharedPreferences): List<String> =
        (entries(context).map { categoryOf(prefs, it) } + customCategories(prefs))
            .filter { it.isNotBlank() }
            .distinct()

    /** 加载 + 解析（IO 线程调用）。失败返回 null（已记 `lut.parse_failed`）。 */
    fun load(context: Context, entry: Entry): CubeLut? {
        cache[entry.id]?.let { return it }
        return try {
            val t0 = SystemClock.elapsedRealtime()
            val bytes = context.assets.open(entry.asset).use { it.readBytes() }
            val lut = CubeLutParser.parseBinary(bytes, entry.label)
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
        entries(context).forEach { load(context, it) }
    }
}
