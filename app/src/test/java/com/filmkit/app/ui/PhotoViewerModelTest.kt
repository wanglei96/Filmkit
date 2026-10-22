package com.filmkit.app.ui

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 照片查看页纯逻辑护栏（PhotoViewerModel.kt，E2-13）。这里出错都是**静默事故**：
 * 毫秒当秒用导致日期偏到 1970、ISO 100 显示成 1、收藏悄悄丢、删除落到没有系统确认的那一档、
 * 返回键把用户从查看页弹回相机页 —— Compose 部分在 JVM 里测不了，这些必须测。
 */
class PhotoViewerModelTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun sec(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toEpochSecond()

    // ── ① 顶栏日期时间（ADR-15：「2026.10.09 / 09:51」）──────────────────

    @Test
    fun dateLabelMatchesAdrFormat() {
        assertEquals("2026.10.09", photoDateLabel(sec(2026, 10, 9, 9, 51), zone))
    }

    @Test
    fun timeLabelIs24HourZeroPadded() {
        assertEquals("09:51", photoTimeLabel(sec(2026, 10, 9, 9, 51), zone))
        assertEquals("23:05", photoTimeLabel(sec(2026, 10, 9, 23, 5), zone))
    }

    @Test
    fun labelsUseLocalZoneNotUtc() {
        // 同一时刻：UTC 10-08 17:00 = 东八区 10-09 01:00（按 UTC 显示会差一天）
        val utc = LocalDateTime.of(2026, 10, 8, 17, 0).atZone(ZoneOffset.UTC).toEpochSecond()
        assertEquals(utc, sec(2026, 10, 9, 1, 0))
        assertEquals("2026.10.09", photoDateLabel(utc, zone))
        assertEquals("01:00", photoTimeLabel(utc, zone))
    }

    @Test
    fun missingTimeSaysUnknownNot1970() {
        assertEquals("时间未知", photoDateLabel(0, zone))
        assertEquals("", photoTimeLabel(0, zone))
    }

    // ── ② 拍摄时间的三个来源（DATE_TAKEN → EXIF → 文件修改时间）───────────

    @Test
    fun dateTakenWinsAndIsMilliseconds() {
        // DATE_TAKEN 的单位是**毫秒**（DATE_ADDED / DATE_MODIFIED 是秒）：混用会把 2026 显示成 1970
        val ms = sec(2026, 10, 9, 9, 51) * 1000 + 500
        assertEquals(
            sec(2026, 10, 9, 9, 51),
            photoTakenAtSec(ms, "2020:01:01 00:00:00", sec(2019, 1, 1, 0, 0), zone),
        )
    }

    @Test
    fun exifUsedWhenDateTakenMissing() {
        assertEquals(
            sec(2026, 10, 9, 9, 51),
            photoTakenAtSec(0, "2026:10:09 09:51:00", sec(2019, 1, 1, 0, 0), zone),
        )
    }

    @Test
    fun fileTimeUsedWhenExifMissingOrBroken() {
        val file = sec(2026, 10, 7, 20, 0)
        assertEquals(file, photoTakenAtSec(0, null, file, zone))
        assertEquals(file, photoTakenAtSec(0, "0000:00:00 00:00:00", file, zone))
        assertEquals(file, photoTakenAtSec(0, "不是时间", file, zone))
    }

    @Test
    fun noSourceAtAllGivesZero() {
        assertEquals(0L, photoTakenAtSec(0, null, 0, zone))
    }

    @Test
    fun exifWithSubsecondsStillParses() {
        // 有些设备写 "2026:10:09 09:51:00.123"；只取前 19 位
        assertEquals(sec(2026, 10, 9, 9, 51), parseExifDateTime("2026:10:09 09:51:00.123", zone))
    }

    // ── ③ 信息（EXIF 展示行组装）────────────────────────────────────────

    private val full = PhotoMeta(
        takenAtSec = sec(2026, 10, 9, 9, 51),
        width = 4080,
        height = 3072,
        sizeBytes = 4_404_019L,
        path = "/storage/emulated/0/Pictures/FilmKit/FILMKIT_20261009-095100.jpg",
        make = "Xiaomi",
        model = "23127PN0CC",
        iso = "100",
        exposureTime = "1/250",
        fNumber = "1.800000",
        focalLength = "5.400000",
        focalLength35 = "24",
    )

    @Test
    fun infoRowsFollowUserOrder() {
        assertEquals(
            listOf("机型", "ISO", "快门", "光圈", "焦距", "尺寸", "文件大小", "路径"),
            infoRows(full).map { it.first },
        )
    }

    @Test
    fun infoRowsFormatValues() {
        val rows = infoRows(full).toMap()
        assertEquals("Xiaomi 23127PN0CC", rows["机型"])
        assertEquals("100", rows["ISO"]) // 整数 ISO 不能被修成 "1"
        assertEquals("1/250 s", rows["快门"])
        assertEquals("f/1.8", rows["光圈"])
        assertEquals("5.4 mm（等效 24 mm）", rows["焦距"])
        assertEquals("4080 × 3072", rows["尺寸"])
        assertEquals("4.2 MB", rows["文件大小"])
        assertEquals(full.path, rows["路径"])
    }

    @Test
    fun infoRowsSkipMissingInsteadOfShowingNull() {
        // 什么都没有时一行都不出（不是显示 "null" / 空值行）
        assertTrue(infoRows(PhotoMeta()).isEmpty())
        assertEquals(listOf("尺寸"), infoRows(PhotoMeta(width = 100, height = 50)).map { it.first })
    }

    @Test
    fun focalLabelOmitsRedundantEquivalent() {
        assertEquals("24 mm", focalLabel("24", "24"))
        assertEquals("24 mm", focalLabel("24", null))
        assertNull(focalLabel(null, "24"))
    }

    @Test
    fun trimExifNumberOnlyTouchesDecimals() {
        assertEquals("5.4", trimExifNumber("5.400000"))
        assertEquals("1.8", trimExifNumber("1.800000"))
        assertEquals("100", trimExifNumber("100")) // trimEnd('0') 会把它修成 "1" —— 钉死
        assertEquals("1/250", trimExifNumber("1/250"))
    }

    @Test
    fun byteSizeLabelPicksUnit() {
        assertEquals("512 B", byteSizeLabel(512L))
        assertEquals("1.5 KB", byteSizeLabel(1536L))
        assertEquals("4.2 MB", byteSizeLabel(4_404_019L))
        assertEquals("1.0 GB", byteSizeLabel(1L shl 30))
    }

    // ── ④ 收藏（prefs 里的 id 集合）─────────────────────────────────────

    @Test
    fun favoritesRoundTripKeepsEveryUri() {
        val ids = linkedSetOf(
            "content://media/external/images/media/1",
            "content://media/external/images/media/2",
        )
        assertEquals(ids, favoritesFrom(favoritesTo(ids)))
    }

    @Test
    fun favoritesFromToleratesNullAndBlank() {
        assertTrue(favoritesFrom(null).isEmpty())
        assertTrue(favoritesFrom("").isEmpty())
        assertTrue(favoritesFrom("\n\n").isEmpty())
        assertEquals(setOf("a"), favoritesFrom("a\n\n"))
    }

    @Test
    fun favoriteToggleAddsThenRemoves() {
        val uri = "content://media/external/images/media/7"
        val on = favoriteToggle(emptySet(), uri)
        assertEquals(setOf(uri), on)
        assertTrue(favoriteToggle(on, uri).isEmpty())
    }

    @Test
    fun favoriteToggleKeepsOtherEntriesAndOrder() {
        val a = "content://media/external/images/media/1"
        val b = "content://media/external/images/media/2"
        val c = "content://media/external/images/media/3"
        assertEquals(listOf(a, b, c), favoriteToggle(linkedSetOf(a, b), c).toList())
        assertEquals(listOf(a, c), favoriteToggle(linkedSetOf(a, c, b), b).toList())
    }

    // ── ⑤ 删除（安全红线：绝不能误删）──────────────────────────────────

    @Test
    fun systemDeleteRequestStartsAtAndroid11() {
        // MediaStore.createDeleteRequest 是 API 30 引入的（29 只有 createWriteRequest）。
        // 常量写小了 ⇒ Android 10 上落到**没有系统确认**的直删档（App 内对话框成唯一闸门）。
        assertEquals(30, SYSTEM_DELETE_REQUEST_API)
    }

    // ── ⑥ 路由状态机（网格 → 查看页 → 编辑器 → 退出回查看页）─────────────

    @Test
    fun editorReturnsToViewerWhenOpenedFromIt() {
        // 本卡的核心闭环：编辑器退出（取消 / 系统返回）回**查看页**，不掉回相册网格
        assertEquals("viewer", backTarget("editor", "viewer", "camera"))
    }

    @Test
    fun viewerReturnsToGallery() {
        assertEquals("gallery", backTarget("viewer", "viewer", "camera"))
    }

    @Test
    fun viewerReturnsToCameraWhenOpenedFromCameraThumbnail() {
        // v0.12.34：相机页胶卷位直接看大图 ⇒ 返回必须回相机，不能半路进相册
        assertEquals("camera", backTarget("viewer", "camera", "camera", "camera"))
        // 从相册进的那条路不受影响
        assertEquals("gallery", backTarget("viewer", "camera", "camera", "gallery"))
    }

    @Test
    fun galleryReturnsToItsSource() {
        assertEquals("camera", backTarget("gallery", "camera", "camera"))
        assertEquals("editor", backTarget("gallery", "viewer", "editor"))
    }

    @Test
    fun editorStillReturnsToCameraAndGalleryForOlderFlows() {
        assertEquals("camera", backTarget("editor", "camera", "camera"))
        assertEquals("gallery", backTarget("editor", "gallery", "camera"))
    }

    @Test
    fun settingsSubPagesReturnToSettings() {
        SETTINGS_SUB_PAGES.forEach {
            assertEquals("settings", backTarget(it, "camera", "camera"))
        }
    }

    @Test
    fun unknownScreenReturnsToCamera() {
        assertEquals("camera", backTarget("camera", "camera", "camera"))
        assertEquals("camera", backTarget("whatever", "camera", "camera"))
    }
}
