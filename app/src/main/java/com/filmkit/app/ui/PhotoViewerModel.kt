package com.filmkit.app.ui

import com.filmkit.app.camera.EditorPreset
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 照片查看页（ADR-15，E2-13）的**纯逻辑** + 本卡引入的路由状态机。
 *
 * 与 GalleryModel.kt 同规矩：**不 import 任何 android.\***。这里放的判定出错都是**静默事故**
 * —— 日期显示成别的日子、收藏悄悄丢了、删除走了没有系统确认的那一档（低版本直删）、
 * 返回键把用户从查看页弹回相机页 —— 所以必须能在 JVM 单测里真跑（见 PhotoViewerModelTest）。
 * Compose / MediaStore / ExifInterface 的访问留在 PhotoViewScreen.kt。
 */

// ── ① 顶栏日期时间（ADR-15：居中两行「2026.10.09 / 09:51」）────────────────

/** 查看页要展示的元数据（**原始值**，未加工；组装成展示行见 [infoRows]） */
data class PhotoMeta(
    /** 拍摄时刻（UTC 秒）；0 = 三个来源都没有（DATE_TAKEN / EXIF / 文件修改时间） */
    val takenAtSec: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val path: String = "",
    val make: String? = null,
    val model: String? = null,
    val iso: String? = null,
    val exposureTime: String? = null,
    val fNumber: String? = null,
    val focalLength: String? = null,
    val focalLength35: String? = null,
)

private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd")
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
private val EXIF_FMT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

/** 顶栏第一行：`2026.10.09`（ADR-15 截图口径）。无时间信息时给一句实话，不留空白。 */
fun photoDateLabel(takenAtSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (takenAtSec <= 0) "时间未知" else local(zone, takenAtSec).format(DATE_FMT)

/** 顶栏第二行：`09:51`（24 小时制，本地时区） */
fun photoTimeLabel(takenAtSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (takenAtSec <= 0) "" else local(zone, takenAtSec).format(TIME_FMT)

private fun local(zone: ZoneId, sec: Long) = Instant.ofEpochSecond(sec).atZone(zone)

/**
 * 拍摄时刻的来源优先级（用户 2026-10-09 规格）：**MediaStore DATE_TAKEN（毫秒）→ EXIF
 * `DateTimeOriginal` → 文件修改时间**。三档全缺返回 0（调用方显示"时间未知"）。
 *
 * DATE_TAKEN 的单位是**毫秒**（与 DATE_ADDED / DATE_MODIFIED 的秒不同）—— 混用会让日期
 * 偏到 1970 年或 5 万年以后，是这类代码最经典的静默错误，故本函数是唯一换算点。
 *
 * EXIF 不带时区 ⇒ 按 [zone] 解释（与相册按本地日期分组同一口径）。
 */
fun photoTakenAtSec(
    dateTakenMs: Long,
    exifDateTimeOriginal: String?,
    fileTimeSec: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Long = when {
    dateTakenMs > 0 -> dateTakenMs / 1000
    else -> parseExifDateTime(exifDateTimeOriginal, zone).takeIf { it > 0 }
        ?: fileTimeSec.takeIf { it > 0 }
        ?: 0L
}

/** EXIF `yyyy:MM:dd HH:mm:ss` → UTC 秒；解析不了返回 0（调用方继续往下回退），绝不抛 */
fun parseExifDateTime(raw: String?, zone: ZoneId = ZoneId.systemDefault()): Long {
    val s = raw?.trim().orEmpty()
    if (s.length < 19) return 0L
    return try {
        LocalDateTime.parse(s.take(19), EXIF_FMT).atZone(zone).toEpochSecond()
    } catch (_: Throwable) {
        0L
    }
}

// ── ② 「信息」对话框（EXIF 展示字段组装）──────────────────────────────────

/**
 * 展示行，顺序照用户规格：机型 / ISO / 快门 / 光圈 / 焦距 / 尺寸 / 文件大小 / 路径。
 * **取不到的字段直接不出现在表里**（不是显示 "null" / "—"）—— 空行比缺行更像 bug。
 */
fun infoRows(meta: PhotoMeta): List<Pair<String, String>> = buildList {
    val camera = listOfNotNull(clean(meta.make), clean(meta.model)).joinToString(" ")
    if (camera.isNotEmpty()) add("机型" to camera)
    clean(meta.iso)?.let { add("ISO" to trimExifNumber(it)) }
    clean(meta.exposureTime)?.let { add("快门" to "${trimExifNumber(it)} s") }
    clean(meta.fNumber)?.let { add("光圈" to "f/${trimExifNumber(it)}") }
    focalLabel(meta.focalLength, meta.focalLength35)?.let { add("焦距" to it) }
    if (meta.width > 0 && meta.height > 0) add("尺寸" to "${meta.width} × ${meta.height}")
    if (meta.sizeBytes > 0) add("文件大小" to byteSizeLabel(meta.sizeBytes))
    if (meta.path.isNotBlank()) add("路径" to meta.path)
}

/** `5.4 mm（等效 24 mm）`；等效焦距缺失或与原值相同 ⇒ 只给一个（不重复废话） */
fun focalLabel(focal: String?, focal35: String?): String? {
    val f = clean(focal) ?: return null
    val e = clean(focal35)
    return if (e != null && e != f) "${trimExifNumber(f)} mm（等效 ${trimExifNumber(e)} mm）"
    else "${trimExifNumber(f)} mm"
}

/**
 * EXIF 数值串的常见尾巴：`5.400000` → `5.4`。**只修剪纯数字写法**（含 `.` 的十进制），
 * 别的形态（`1/250`、`1.8 EV`）原样返回 —— 快门那类分数值不能被误伤。
 *
 * ⚠ 没有小数点的整数**原样返回**：`trimEnd('0')` 会把 ISO `100` 修成 `1`（静默错误，
 * 单测钉死这条）。
 */
fun trimExifNumber(s: String): String {
    val t = s.trim()
    if (t.isEmpty() || !t.all { it.isDigit() || it == '.' }) return t
    if ('.' !in t) return t
    return t.trimEnd('0').trimEnd('.').ifEmpty { t }
}

/** 字节数 → `4.2 MB`（一位小数；无 Locale 依赖、无 `String.format`，同 EditorExport 口径） */
fun byteSizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "${oneDecimal(bytes, 1L shl 30)} GB"
    bytes >= 1L shl 20 -> "${oneDecimal(bytes, 1L shl 20)} MB"
    bytes >= 1L shl 10 -> "${oneDecimal(bytes, 1L shl 10)} KB"
    else -> "$bytes B"
}

private fun oneDecimal(v: Long, unit: Long): String {
    val tenths = (v * 10 + unit / 2) / unit // 四舍五入到 0.1
    return "${tenths / 10}.${tenths % 10}"
}

private fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

// ── ③ 收藏（Android 没有统一收藏 API ⇒ 一份 prefs 里的 id 集合）────────────

/** prefs 键（与相机页 `lut_id` / 设置页 `accent_argb` 同一份 "filmkit" prefs） */
const val FAVORITES_KEY = "fav_ids"

/** 存的是**照片 URI 字符串**、换行分隔（URI 里不会出现换行 ⇒ 不需要转义/编码） */
fun favoritesFrom(raw: String?): Set<String> =
    raw?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty()

fun favoritesTo(ids: Set<String>): String = ids.filter { it.isNotBlank() }.joinToString("\n")

/**
 * 点心形：在集合里就移除、不在就加入。返回**新集合**（`+`/`-` 给 LinkedHashSet，保序
 * ⇒ 存取往返稳定，不会因为"顺序变了"写回一堆无意义的 diff）。
 */
fun favoriteToggle(ids: Set<String>, uri: String): Set<String> =
    if (uri in ids) ids - uri else ids + uri

// ── ④ 删除方式（安全红线：绝不能误删）────────────────────────────────────

/**
 * 系统删除确认弹窗（`MediaStore.createDeleteRequest`）的起始 API = **30（Android 11）**。
 *
 * ⚠ 不是 29：`createDeleteRequest` 是 R 才引入的（Q 只有 `createWriteRequest`）。写小了这个
 * 常量，Android 10 上会去调不存在的接口（崩溃）或落到无系统确认的直删档 —— 所以这里单测
 * 直接钉死它的值。低版本没有系统级弹窗，**App 内的二次确认对话框是唯一一道闸**。
 */
const val SYSTEM_DELETE_REQUEST_API = 30

// ── ⑤ 路由状态机（本卡：网格 → 查看页 → 编辑器，编辑器退出回查看页）──────────

/** 设置页的子页（返回键回设置，不回相机） */
val SETTINGS_SUB_PAGES = setOf("logs", "spike", "lens", "accent", "focals")

/**
 * 系统返回键的目标页。各层归属：
 * - `editor` → 它进来的那一页 [editorFrom]（本卡的关键：从查看页进来就**回查看页**，
 *   而不是掉回相册网格 —— 用户 2026-10-09 明确要求）；
 * - `viewer` → 相册网格（查看页目前只有相册一个入口）；
 * - `gallery` → 它进来的那一页 [galleryFrom]（相机页或编辑器）；
 * - 设置子页 → 设置；其余 → 相机。
 */
fun backTarget(screen: String, editorFrom: String, galleryFrom: String): String = when (screen) {
    "editor" -> editorFrom
    "gallery" -> galleryFrom
    "viewer" -> "gallery"
    in SETTINGS_SUB_PAGES -> "settings"
    else -> "camera"
}

// ── ⑥ 查看页「胶片」→ 选预设 → 直接进编辑器（★ P-3）─────────────────────────

/**
 * ★ P-3：打开编辑器时"要不要套用一条初始预设"的**纯判定** —— 把查看页「胶片」面板传来的
 * [initialPresetId] 在预设列表里找出来（`null` = 普通「编辑」入口）。
 *
 * 为什么是"按 id 现取"而不是"把整条预设带过去"：预设在 prefs 里是**唯一真源**（ADR-7 裁决：
 * 拍照 / 查看页 / 编辑器三处入口同一套数据），路由层只带一个字符串（`rememberSaveable` 能存，
 * 跨进程重建也不丢）⇒ 不存在"面板打开时读到的那份副本"与"编辑器读到的"不一致的窗口。
 *
 * 为什么放在这个文件：本卡的**回归哨兵**是"查不到 ⇒ 什么都不做"这一条 —— `null` id、空列表、
 * 预设已被删（面板打开期间被删 / 进程重建后数据变了）三种情形都必须退化成**与 P-3 之前逐位
 * 一致的编辑器**（起点 = 全恒等基线、首帧渲染原片），绝不允许半套用。Compose 里那段分支跑不了
 * 单测；判定留在纯逻辑文件（无 android import）就能钉住（见 PhotoViewPresetTest）。
 *
 * ⚠ 拿到非空结果后**必须**在基线就绪之后才套用（先基线、再预设的稀疏覆盖）—— 顺序反了会把
 * 预设整个抹掉，用户点一下预设、画面还是原片。
 */
internal fun initialPresetOf(presets: List<EditorPreset>, initialPresetId: String?): EditorPreset? =
    initialPresetId?.let { id -> presets.firstOrNull { it.id == id } }
