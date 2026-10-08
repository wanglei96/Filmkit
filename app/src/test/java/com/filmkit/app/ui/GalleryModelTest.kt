package com.filmkit.app.ui

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 相册页纯逻辑护栏（GalleryModel.kt）。这三组判定出错都是**静默事故**：
 * 分组错了照片跑到别的日期段、权限降级错了页面空白、缓存淘汰错了内存只涨不掉 ——
 * 所以哪怕 Compose 部分在 JVM 里测不了，这些必须测。
 */
class GalleryModelTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun sec(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toEpochSecond()

    private fun photo(id: String, y: Int, m: Int, d: Int, h: Int, min: Int) =
        GalleryPhoto("content://media/external/images/media/$id", sec(y, m, d, h, min))

    // ── 日期分组 ────────────────────────────────────────────────────────

    @Test
    fun groupsByLocalDayNewestFirst() {
        val days = groupByDay(
            listOf(
                photo("1", 2026, 10, 8, 10, 0),
                photo("2", 2026, 10, 7, 23, 0),
                photo("3", 2026, 10, 8, 8, 0),
            ),
            zone,
        )
        assertEquals(2, days.size)
        assertEquals(LocalDate.of(2026, 10, 8), days[0].date)
        assertEquals(LocalDate.of(2026, 10, 7), days[1].date)
        // 段内也要倒序：10:00 在 08:00 前面
        assertEquals(listOf("1", "3"), days[0].photos.map { it.uri.substringAfterLast('/') })
    }

    @Test
    fun groupingUsesLocalZoneNotUtc() {
        // 同一时刻：UTC 的 10-07 17:00 = 东八区的 10-08 01:00
        val utcSec = LocalDateTime.of(2026, 10, 7, 17, 0).atZone(ZoneOffset.UTC).toEpochSecond()
        assertEquals(utcSec, sec(2026, 10, 8, 1, 0))
        val days = groupByDay(
            listOf(GalleryPhoto("content://x/1", utcSec)),
            zone,
        )
        // 按 UTC 分组会落到 10-07 —— 东八区用户凌晨拍的照片就被算进前一天了
        assertEquals(LocalDate.of(2026, 10, 8), days[0].date)
    }

    @Test
    fun emptyInputGivesEmptyDays() {
        assertEquals(0, groupByDay(emptyList(), zone).size)
    }

    // ── 日期段标题 ──────────────────────────────────────────────────────

    @Test
    fun dayLabelTodayYesterdayAndOlder() {
        val today = LocalDate.of(2026, 10, 8)
        assertEquals("今天", dayLabel(today, today))
        assertEquals("昨天", dayLabel(today.minusDays(1), today))
        assertEquals("10月1日", dayLabel(LocalDate.of(2026, 10, 1), today))
        // 跨年才带年份（同年只给月日，避免每段都挂年份）
        assertEquals("2025年12月31日", dayLabel(LocalDate.of(2025, 12, 31), today))
    }

    // ── 权限降级口径 ────────────────────────────────────────────────────

    @Test
    fun grantedScansWholeStore() {
        assertNull(gallerySelection(granted = true, sdkInt = 34))
        assertNull(gallerySelection(granted = true, sdkInt = 28))
    }

    @Test
    fun deniedFallsBackToOwnAlbum() {
        // API 29+ 有 relative_path 列
        assertEquals(
            "relative_path LIKE 'Pictures/FilmKit%'",
            gallerySelection(granted = false, sdkInt = 29),
        )
        // 更早版本只有 _data 绝对路径
        assertEquals(
            "_data LIKE '%/Pictures/FilmKit/%'",
            gallerySelection(granted = false, sdkInt = 28),
        )
    }

    // ── 缩略图缓存 ──────────────────────────────────────────────────────

    private fun cache(maxBytes: Long, maxCount: Int) =
        ThumbCache<String, String>(maxBytes, maxCount) { it.length }

    @Test
    fun evictsByByteBudget() {
        val c = cache(maxBytes = 10, maxCount = 100)
        c.put("a", "12345")          // 5B
        c.put("b", "12345")          // 10B，正好到上限
        assertEquals(2, c.count)
        assertEquals(10L, c.usedBytes)
        c.put("c", "12345")          // 15B > 10B ⇒ 淘汰最久未用的 a
        assertEquals(2, c.count)
        assertEquals(10L, c.usedBytes)
        assertNull(c.get("a"))
        assertNotNull(c.get("c"))
    }

    @Test
    fun evictsByCountBudget() {
        val c = cache(maxBytes = 1_000, maxCount = 2)
        c.put("a", "1")
        c.put("b", "2")
        c.put("c", "3")
        assertEquals(2, c.count)
        assertNull(c.get("a"))
        assertNotNull(c.get("b"))
    }

    @Test
    fun getRefreshesRecency() {
        val c = cache(maxBytes = 1_000, maxCount = 2)
        c.put("a", "1")
        c.put("b", "2")
        c.get("a")                   // a 变成最近使用 ⇒ 下一个淘汰的是 b
        c.put("c", "3")
        assertNotNull(c.get("a"))
        assertNull(c.get("b"))
    }

    @Test
    fun overwriteSameKeyDoesNotDoubleCountBytes() {
        val c = cache(maxBytes = 1_000, maxCount = 10)
        c.put("a", "12345")
        c.put("a", "12")
        assertEquals(1, c.count)
        assertEquals(2L, c.usedBytes)
    }

    @Test
    fun clearDropsReferencesAndResetsAccounting() {
        val c = cache(maxBytes = 1_000, maxCount = 10)
        c.put("a", "12345")
        c.clear()
        assertEquals(0, c.count)
        assertEquals(0L, c.usedBytes)
        assertNull(c.get("a"))
    }
}
