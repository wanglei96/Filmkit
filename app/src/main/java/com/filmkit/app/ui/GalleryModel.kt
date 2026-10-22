package com.filmkit.app.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 相册（Gallery）的**纯逻辑**：查询口径、日期分组、缩略图缓存。
 *
 * 为什么单独一个文件、且不 import 任何 android.*：这里放的是"权限降级"与"缓存淘汰"
 * 的判定 —— 两处出错都会静默（页面空白 / 内存涨），必须能在 JVM 单测里真跑
 * （见 GalleryModelTest）。Compose 与 MediaStore 访问留在 GalleryScreen.kt。
 */

/** 本应用照片的相册目录，与 CameraController / EditorExport 落盘时的 RELATIVE_PATH 一致 */
const val FILMKIT_ALBUM_DIR = "Pictures/FilmKit"

/** 相册里的一张照片。[uri] 存字符串而非 android.net.Uri：后者在 JVM 单测里是 stub */
data class GalleryPhoto(val uri: String, val dateAddedSec: Long)

/** 按天分组的一段（[photos] 已按时间倒序） */
data class GalleryDay(val date: LocalDate, val photos: List<GalleryPhoto>)

/**
 * 查询范围（selection）：授权 = null ⇒ 整个相册（用户要的"所有照片"）；
 * 未授权 = 只查本应用目录 ⇒ 拒绝权限时降级为"本 App 拍的"，页面不空白也不崩。
 *
 * 列名写成字面量而不是 MediaStore 常量：`relative_path` 是 API 29 才有的列，更早只有
 * `_data`（绝对路径）—— 这里要表达的是"哪个 API 口径"，字面量反而更直白（常量会在这个
 * 文件里引入 android 依赖，见文件头 KDoc）。selection 内全是固定字面量、无用户输入
 * ⇒ 无注入面。
 */
fun gallerySelection(granted: Boolean, sdkInt: Int): String? = when {
    granted -> null
    sdkInt >= 29 -> "relative_path LIKE '$FILMKIT_ALBUM_DIR%'"
    else -> "_data LIKE '%/$FILMKIT_ALBUM_DIR/%'"
}

/**
 * 按本地日期分组；日期段之间与段内**都是时间倒序**（传统相册：最新在最上面）。
 *
 * 用 [zone] 而非固定 UTC：MediaStore 的 DATE_ADDED 是 UTC 秒，按 UTC 分组会让东八区
 * 用户在早上 8 点前看到的照片被算进"前一天"。
 *
 * 排序用稳定排序 + groupBy（LinkedHashMap 保持首次出现顺序）⇒ 段序天然倒序。
 */
fun groupByDay(photos: List<GalleryPhoto>, zone: ZoneId = ZoneId.systemDefault()): List<GalleryDay> =
    photos.sortedByDescending { it.dateAddedSec }
        .groupBy { Instant.ofEpochSecond(it.dateAddedSec).atZone(zone).toLocalDate() }
        .map { (day, list) -> GalleryDay(day, list) }

/** 日期段标题：今天 / 昨天 / 10月8日 / 2025年10月8日（跨年才带年份） */
fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when {
    date == today -> "今天"
    date == today.minusDays(1) -> "昨天"
    date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
    else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
}

/**
 * 缩略图内存缓存（LRU + 双上限：字节数与张数）。
 *
 * 为什么不用 android.util.LruCache：它的淘汰单位是"条数"，而缩略图的大小随机型/密度
 * 变化（360×360 与 720×720 差 4 倍），只按条数上限在高端机上照样能吃掉几百 MB；
 * 且它在 JVM 单测里是 stub，淘汰策略就没法测了。
 *
 * [sizeOf] 由调用方给（UI 侧传 `Bitmap::byteCount`），泛型因此与 android 无关。
 */
class ThumbCache<K, V>(
    private val maxBytes: Long,
    private val maxCount: Int,
    private val sizeOf: (V) -> Int,
) {
    // accessOrder = true：get 命中即算"最近使用"，淘汰的是最久没看的那些
    private val map = LinkedHashMap<K, V>(0, 0.75f, true)
    private var bytes = 0L

    // ── v0.12.26：**整个类必须同步**（相册闪退的根因）────────────────────────────
    // 相册页并发加载多张缩略图（IO 线程池），多个线程同时进 put/get。而：
    //   ① accessOrder=true 的 LinkedHashMap 连 `get` 都会重排链表结构；
    //   ② put 的淘汰循环持有 `map.entries.iterator()`，另一个线程一改 map 迭代器即失效。
    // 真机堆栈（20261022(8)，两次，主线程）：
    //   java.util.NoSuchElementException
    //     at java.util.LinkedHashMap$LinkedHashIterator.nextNode(LinkedHashMap.java:1063)
    //     at com.filmkit.app.ui.ThumbCache.put(GalleryModel.kt:89)
    //     at com.filmkit.app.ui.GalleryScreenKt.load...
    // 另有一次 ConcurrentModificationException（同一原因，表现不同）。
    // 全部方法加 @Synchronized：粒度是单次 get/put（微秒级），不构成瓶颈；
    // 绝不能只同步 put —— get 改 accessOrder 同样会破坏正在进行的迭代。
    val count: Int
        @Synchronized get() = map.size
    val usedBytes: Long
        @Synchronized get() = bytes

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map.remove(key)?.let { bytes -= sizeOf(it) }
        map[key] = value
        bytes += sizeOf(value)
        while (map.isNotEmpty() && (bytes > maxBytes || map.size > maxCount)) {
            val it = map.entries.iterator()
            bytes -= sizeOf(it.next().value)
            it.remove()
        }
    }

    /**
     * 丢引用，**绝不 recycle**：被淘汰的那张很可能正被 Compose 画在屏幕上（滚动时
     * 淘汰刚好命中可见项），recycle 会直接 "Canvas: trying to use a recycled bitmap" 崩掉。
     * 交回 GC 即可（Android 8+ 位图像素在 native 堆，GC 同样会回收）。
     */
    @Synchronized
    fun clear() {
        map.clear()
        bytes = 0
    }
}
