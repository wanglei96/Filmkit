package com.filmkit.app.lut

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.net.Uri
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv
import java.io.File

/**
 * LUT 库（M4）：内置资产 + **用户导入**（§6.2/G5 资产互通，v0.10.94 落地）。
 *
 * 内置 = `assets/luts/ 下的 .lut`（33³ uint8 二进制，由 `tools/gen-lut-assets.py` 生成），
 * 清单不硬编码：直接扫目录，显示名与分类取资产头部。
 *
 * **打包格式 ≠ 交换格式**：内置用二进制（42 个只要 4.3MB，ASCII .cube 要 40MB），
 * 用户导入导出走 §6.2 的 .cube / Hald PNG（无损口径见 [LutIO]）。
 *
 * 用户 LUT：原文件按字节存档到 `filesDir/luts/`（导出可取回原样），id 加 `u_` 前缀
 * 防与内置冲突；解析结果与内置共用 cache。删除只动用户文件（内置不可删）。
 *
 * 分类（M4-3）：默认来自资产内置的品牌（用户导入默认「导入」），用户可把某个 LUT
 * 改到别的分类，也可新建分类。覆盖与自定义分类都存在 prefs，**不动资产**。
 *
 * 解析只在首次选中时做一次，结果缓存；失败返回 null 并记日志，调用侧回退「无 LUT」
 * —— 不静默降级到某个猜测的资产。
 */
object LutLibrary {

    /**
     * @param id       prefs 里持久化的标识（`lut_id`）：内置 = 文件名去扩展名，
     *                 用户 = `u_` + 文件名去扩展名（前缀防冲突）
     * @param asset    内置 = assets 内路径；用户 = `filesDir/luts/` 下的文件名
     * @param label    显示名（资产 TITLE / 导入时的文件名；缺失时回退 id）
     * @param category 内置默认分类（资产里的品牌；用户覆盖见 [categoryOf]）
     * @param user     true = 用户导入（可删除/导出，删库随文件）
     */
    data class Entry(
        val id: String,
        val asset: String,
        val label: String,
        val category: String,
        val user: Boolean = false,
    )

    private const val KEY_CATS = "lut_cats" // 用户新建的分类，`\n` 分隔
    private const val KEY_CAT_PREFIX = "lut_cat_" // 单个 LUT 的分类覆盖
    private const val USER_CAT = "导入" // 用户 LUT 的默认分类

    private var cacheEntries: List<Entry>? = null
    // 并发访问（IO）：预览后台生成与用户点卡片加载可能同时进入（v0.10.94 顺手
    // 从 HashMap 换 ConcurrentHashMap —— 导入后"即选中"会放大这个碰撞窗口）
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CubeLut>()

    private fun userDir(context: Context): File = File(context.filesDir, "luts")

    /** 清单（扫描目录 + 读头部，进程内缓存一次；导入/删除后 [invalidate] 重建） */
    fun entries(context: Context): List<Entry> = cacheEntries ?: run {
        val builtIn = (context.assets.list("luts") ?: emptyArray())
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
        val users = userDir(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.map { file ->
                Entry(
                    id = "u_" + file.nameWithoutExtension,
                    asset = file.name,
                    label = file.nameWithoutExtension,
                    category = USER_CAT,
                    user = true,
                )
            }
            .orEmpty()
        cacheEntries = builtIn + users
        cacheEntries!!
    }

    /** 导入/删除后调用：清单与解析缓存一起失效，下次 [entries]/[load] 重建 */
    private fun invalidate(id: String?) {
        cacheEntries = null
        id?.let { cache.remove(it) }
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

    // ── 用户 LUT：导入 / 删除（M4 §6.2，v0.10.94）─────────────────────────

    /** 文件名白名单：保留中英文/数字/_-空格与扩展名点，其余替换下划线（防路径逃逸） */
    private fun sanitizeName(name: String): String =
        name.map { c ->
            if (c.isLetterOrDigit() || c == '_' || c == '-' || c == ' ' || c == '.') c else '_'
        }.joinToString("").trim().take(64).ifEmpty { "lut" }

    /**
     * 导入（IO 线程调用）：内容嗅探 + 解析校验（非法文件在此拒绝）→ **原文件字节存档**
     * （真无损：导出/分享可取回原样）→ 重建清单。重名自动加 (1)/(2) 后缀。
     * @return 新增的 Entry
     * @throws IllegalArgumentException 格式不支持 / 内容非法
     */
    fun import(context: Context, bytes: ByteArray, displayName: String): Entry {
        val t0 = SystemClock.elapsedRealtime()
        val base = sanitizeName(displayName.substringBeforeLast('.'))
        val ext = when {
            LutIO.isPng(bytes) -> ".png"
            else -> ".cube"
        }
        // 先解析校验（不合法不落盘），顺便拿到真实 kind 供日志
        val parsed = LutIO.parseBytes(bytes, base)
        val dir = userDir(context)
        dir.mkdirs()
        var file = File(dir, "$base$ext")
        var n = 1
        while (file.exists()) {
            file = File(dir, "$base(${n++})$ext")
        }
        file.writeBytes(bytes)
        invalidate(null)
        val entry = Entry(
            id = "u_" + file.nameWithoutExtension,
            asset = file.name,
            label = file.nameWithoutExtension,
            category = USER_CAT,
            user = true,
        )
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "engine", "lut.import",
            kv(
                "id" to entry.id, "kind" to parsed.kind, "size" to parsed.lut.size,
                "resampled" to parsed.resampled, "bytes" to bytes.size,
                "ms" to (SystemClock.elapsedRealtime() - t0),
            ),
        )
        return entry
    }

    /** SAF Uri 便捷入口（读全部字节后走 [import]） */
    fun import(context: Context, uri: Uri): Entry {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取所选文件")
        // 上限防呆：128³ 文本 .cube 最大 ~56MB，超限的必然不是合法 LUT
        require(bytes.size <= 80 * 1024 * 1024) { "文件过大：${bytes.size} 字节" }
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: "lut"
        return import(context, bytes, name)
    }

    /**
     * 删除用户 LUT（内置返回 false）。文件、解析缓存、分类覆盖一起清；
     * 若它是当前选中，调用侧负责把 `lut_id` 摘掉并旁路引擎 LUT。
     */
    fun deleteUser(context: Context, prefs: SharedPreferences, id: String): Boolean {
        val entry = entries(context).firstOrNull { it.id == id } ?: return false
        if (!entry.user) return false
        val f = File(userDir(context), entry.asset)
        val ok = !f.exists() || f.delete()
        if (ok) {
            prefs.edit().remove(KEY_CAT_PREFIX + id).apply()
            invalidate(id)
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "engine", "lut.delete", kv("id" to id),
            )
        }
        return ok
    }

    // ── 分类（M4-3）：默认品牌来自资产，用户可覆盖 + 新建 ────────────────────

    /** 用户新建的分类（顺序即创建顺序） */
    fun customCategories(prefs: SharedPreferences): List<String> =
        prefs.getString(KEY_CATS, "").orEmpty().split('\n').filter { it.isNotBlank() }

    /** 某个 LUT 的分类：用户覆盖优先，否则默认（资产品牌 / 用户导入） */
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

    /**
     * 重命名用户新建的分类（v0.10.96）：KEY_CATS 清单替换 + 归入旧名的覆盖值一起改
     * （遍历 prefs 的 lut_cat_* 键，不需要 context/清单）。目标名已存在或旧名不是
     * 自定义分类时返回 false（内置品牌分类不可改名，与删除同规则）。
     */
    fun renameCategory(prefs: SharedPreferences, old: String, new: String): Boolean {
        val o = old.trim()
        val n = new.trim()
        if (o.isEmpty() || n.isEmpty() || n.contains('\n') || o == n) return false
        val cur = customCategories(prefs)
        if (o !in cur || n in cur) return false
        val e = prefs.edit()
        e.putString(KEY_CATS, cur.map { if (it == o) n else it }.joinToString("\n"))
        prefs.all.keys.filter { it.startsWith(KEY_CAT_PREFIX) }
            .forEach { k -> if (prefs.getString(k, null) == o) e.putString(k, n) }
        e.apply()
        return true
    }

    /** 删除用户新建的分类：同时把归入它的 LUT 覆盖清掉（回默认分类，LUT 本身不动） */
    fun removeCategory(context: Context, prefs: SharedPreferences, name: String) {
        val e = prefs.edit().putString(KEY_CATS, customCategories(prefs).filter { it != name }.joinToString("\n"))
        entries(context).forEach {
            if (categoryOf(prefs, it) == name) e.remove(KEY_CAT_PREFIX + it.id)
        }
        e.apply()
    }

    /** 全部分类（资产内置品牌 + 用户新建 + 用户导入，去重保序） */
    fun allCategories(context: Context, prefs: SharedPreferences): List<String> =
        (entries(context).map { categoryOf(prefs, it) } + customCategories(prefs))
            .filter { it.isNotBlank() }
            .distinct()

    // ── 加载与解析 ──────────────────────────────────────────────────────────

    /** 加载 + 解析（IO 线程调用）。失败返回 null（已记 `lut.parse_failed`）。 */
    fun load(context: Context, entry: Entry): CubeLut? {
        cache[entry.id]?.let { return it }
        return try {
            val t0 = SystemClock.elapsedRealtime()
            val lut = if (entry.user) {
                val f = File(userDir(context), entry.asset)
                LutIO.parseBytes(f.readBytes(), entry.label).lut
            } else {
                val bytes = context.assets.open(entry.asset).use { it.readBytes() }
                CubeLutParser.parseBinary(bytes, entry.label)
            }
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
