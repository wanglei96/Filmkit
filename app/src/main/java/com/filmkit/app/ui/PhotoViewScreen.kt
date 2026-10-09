package com.filmkit.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 置灰（与编辑器「撤销/重做」留位同一档：可见但明确不可用） */
private val DisabledInk = FilmTheme.CreamDim.copy(alpha = 0.35f)

/** 「胶片」项的实话：E3 预设管理未开工，不假装能进（ADR-12） */
private const val FILM_PRESET_LATER = "胶片预设将在后续版本提供"

/**
 * 照片查看页（ADR-15，E2-13）：**网格点开先到这一页**，点「编辑」才进编辑器；编辑器退出
 * （取消 / 系统返回）回到本页 —— 用户 2026-10-09 明确要求"取消后应该回到照片查看页"。
 *
 * 五层结构照 ADR-15 截图：① 顶栏（返回 ｜ **日期 + 时间**居中两行 ｜ 收藏心形）
 * ② 照片区（**纯黑底 + 全分辨率原图**，用户 2026-10-08："点开看原图"）
 * ③ 底部操作栏（编辑 / 胶片 / 分享 / 信息 / 删除）。
 *
 * 素材分级（与相册页同一约定）：本页**自己全尺寸解码**，网格缩略图不会流到这里。
 * 权限沿用相册页那套（未授权时相册只列本应用照片），本页不新增权限流程。
 *
 * @param uri 照片的 MediaStore URI；null（进程重建后状态丢失）显示失败态而非白屏。
 * @param onEdit 点「编辑」→ 交给编辑器（路由层记录"从查看页进来的"，退出时回本页）。
 * @param onDeleted 删除成功 → 回相册（那张已不存在，留在本页只会看到失效图）。
 */
@Composable
fun PhotoViewScreen(
    uri: Uri?,
    onBack: () -> Unit = {},
    onEdit: (Uri) -> Unit = {},
    onDeleted: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var meta by remember(uri) { mutableStateOf(PhotoMeta()) }
    var error by remember(uri) { mutableStateOf("") }
    var status by remember(uri) { mutableStateOf("") }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 收藏的唯一数据源是这份 state；prefs 只负责持久化（见 KDoc 的"没有统一收藏 API"）
    var favorites by remember {
        mutableStateOf(favoritesFrom(prefs.getString(FAVORITES_KEY, null)))
    }
    val key = uri?.toString()
    val favorite = key != null && key in favorites

    // 系统删除确认弹窗（API 30+）的返回：OK = 已删除；其余（用户取消 / 系统拒绝）如实说一句
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) onDeleted() else status = "已取消删除"
    }

    /**
     * 删除（安全红线：绝不能误删）。**两道闸**：
     * ① 本对话框（二次确认，两档都有）；② API 30+ 再叠一层系统确认弹窗。
     * 低版本（26-29）没有系统弹窗，只能 `contentResolver.delete` —— 此时失败必须如实报，
     * 不许静默（删没删掉用户必须知道）。
     */
    fun deletePhoto() {
        val u = uri ?: return
        if (Build.VERSION.SDK_INT >= SYSTEM_DELETE_REQUEST_API) {
            try {
                val req = MediaStore.createDeleteRequest(context.contentResolver, listOf(u))
                deleteLauncher.launch(IntentSenderRequest.Builder(req.intentSender).build())
            } catch (t: Throwable) {
                status = "删除失败：${t.message?.take(60) ?: "系统拒绝了删除请求"}"
            }
        } else {
            var rows = -1
            val err = try {
                rows = context.contentResolver.delete(u, null, null)
                null
            } catch (t: Throwable) {
                t.message?.take(60) ?: "无权限或该照片不可删除"
            }
            when {
                rows > 0 -> onDeleted()
                err != null -> status = "删除失败：$err"
                else -> status = "删除失败：系统没有删除这张照片"
            }
        }
    }

    LaunchedEffect(uri) {
        if (uri == null) {
            error = "照片已失效"
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) { loadPhotoView(context, uri) }
        bitmap = loaded.bitmap
        meta = loaded.meta
        error = loaded.error
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black) // ② 纯黑底（ADR-15 实测）
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ── ① 顶栏：返回 ｜ 日期 + 时间（居中两行）｜ 收藏 ──────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = FilmTheme.Cream,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(photoDateLabel(meta.takenAtSec), color = FilmTheme.Cream, fontSize = 13.sp)
                val time = photoTimeLabel(meta.takenAtSec)
                if (time.isNotEmpty()) {
                    Text(time, color = FilmTheme.CreamDim, fontSize = 12.sp)
                }
            }
            Box(
                Modifier
                    .size(48.dp)
                    .clickable {
                        val k = key ?: return@clickable
                        val next = favoriteToggle(favorites, k)
                        favorites = next
                        prefs.edit().putString(FAVORITES_KEY, favoritesTo(next)).apply()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (favorite) "取消收藏" else "收藏",
                    tint = if (favorite) FilmTheme.Accent else FilmTheme.Cream,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // ── ② 照片区：全分辨率原图（纯黑底上下留黑）────────────────────────
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val b = bitmap
            when {
                error.isNotEmpty() -> Text(error, color = FilmTheme.CreamDim, fontSize = 13.sp)
                b != null -> Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = "照片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> CircularProgressIndicator(color = FilmTheme.Accent)
            }
        }

        // 失败 / 置灰项的提示行（不静默：删除失败、分享失败都落在这里）
        if (status.isNotEmpty()) {
            Text(
                status,
                color = FilmTheme.CreamDim,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // ── ③ 底部操作栏：编辑 / 胶片 / 分享 / 信息 / 删除 ──────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 10.dp),
        ) {
            val enabled = uri != null
            BottomAction(Icons.Filled.Edit, "编辑", enabled) { uri?.let(onEdit) }
            // 胶片（E3 预设管理未开工）：**置灰 + 一句实话**，不做假入口（ADR-12）
            BottomAction(Icons.Filled.PhotoFilter, "胶片", enabled, dimmed = true) {
                status = FILM_PRESET_LATER
            }
            BottomAction(Icons.Filled.Share, "分享", enabled) {
                uri?.let { sharePhoto(context, it)?.let { msg -> status = msg } }
            }
            BottomAction(Icons.Filled.Info, "信息", enabled) { showInfo = true }
            BottomAction(
                Icons.Filled.Delete,
                "删除",
                enabled,
                tint = MaterialTheme.colorScheme.error,
            ) { confirmDelete = true }
        }
    }

    // ── 「信息」：EXIF 展示（取不到的字段不出现在表里）────────────────────
    if (showInfo) {
        val rows = infoRows(meta)
        AlertDialog(
            onDismissRequest = { showInfo = false },
            shape = RoundedCornerShape(18.dp),
            title = { Text("信息", color = FilmTheme.Cream, fontFamily = FilmTheme.Serif) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (rows.isEmpty()) {
                        Text("没有可读的拍摄信息", color = FilmTheme.CreamDim, fontSize = 13.sp)
                    }
                    rows.forEach { (k, v) ->
                        Row {
                            Text(k, color = FilmTheme.CreamDim, fontSize = 12.sp, modifier = Modifier.width(66.dp))
                            Text(v, color = FilmTheme.Cream, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text("关闭", color = FilmTheme.Accent, fontFamily = FilmTheme.Mono)
                }
            },
        )
    }

    // ── 删除的第一道闸：二次确认 ────────────────────────────────────────
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = RoundedCornerShape(18.dp),
            title = { Text("删除这张照片？", color = FilmTheme.Cream, fontFamily = FilmTheme.Serif) },
            text = {
                Text(
                    if (Build.VERSION.SDK_INT >= SYSTEM_DELETE_REQUEST_API) {
                        "删除后无法恢复。系统还会再弹一次确认。"
                    } else {
                        "删除后无法恢复。此操作不可撤销。"
                    },
                    color = FilmTheme.CreamDim,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        deletePhoto()
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error, fontFamily = FilmTheme.Mono) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消", color = FilmTheme.CreamDim, fontFamily = FilmTheme.Mono)
                }
            },
        )
    }
}

/** 底栏一项：图标 + 文字（[dimmed] = 可见但明确不可用，如未开工的「胶片」） */
@Composable
private fun RowScope.BottomAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    tint: Color = FilmTheme.Cream,
    dimmed: Boolean = false,
    onClick: () -> Unit,
) {
    val ink = if (dimmed) DisabledInk else tint
    Column(
        Modifier
            .weight(1f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(22.dp))
        Text(label, color = ink, fontSize = 11.sp)
    }
}

/**
 * 系统分享。用 **MediaStore URI + 临时读权限**，不引 FileProvider：照片本来就在 MediaStore
 * 里（本页/相册拿到的都是它的 URI），复制一份进 cache 只为分享纯属多余（还要管清理）。
 * 失败返回一句实话给调用方显示（没有可用应用 / 系统拒绝），不静默。
 */
private fun sharePhoto(context: Context, uri: Uri): String? {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        context.startActivity(Intent.createChooser(send, "分享照片"))
        null
    } catch (t: Throwable) {
        "分享失败：${t.message?.take(60) ?: "没有可用的应用"}"
    }
}

private data class LoadedPhoto(val bitmap: Bitmap?, val meta: PhotoMeta, val error: String)

/** 读原图 + 元数据（IO 线程）。解不出来给失败态，不抛。 */
private fun loadPhotoView(context: Context, uri: Uri): LoadedPhoto {
    val meta = queryMeta(context, uri)
    // **全分辨率解码**（用户 2026-10-08："点开看原图"；与编辑器 loadPreview 同口径）：
    // reqW/reqH = 0 ⇒ 不采样。方向由 [decodeOriented] 统一转正（E2-18a：DNG 写的是真实
    // sensor 方向，BitmapFactory 自己不读 EXIF，不转正就会横竖颠倒）。
    val bmp = decodeOriented(context, uri, 0, 0)
    return LoadedPhoto(bmp, meta, if (bmp == null) "照片已失效或无法读取" else "")
}

/**
 * MediaStore 元数据 + EXIF（IO 线程，全部 best-effort：单列缺失/单次查询失败只丢那一项）。
 * 路径优先 `_data`（API 29 起 deprecated 但仍是用户能看懂的绝对路径），取不到退回
 * `relative_path` + 文件名。
 */
private fun queryMeta(context: Context, uri: Uri): PhotoMeta {
    var takenMs = 0L
    var modifiedSec = 0L
    var addedSec = 0L
    var width = 0
    var height = 0
    var size = 0L
    var path = ""
    try {
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                takenMs = c.longOr0(MediaStore.Images.Media.DATE_TAKEN)
                modifiedSec = c.longOr0(MediaStore.Images.Media.DATE_MODIFIED)
                addedSec = c.longOr0(MediaStore.Images.Media.DATE_ADDED)
                width = c.longOr0(MediaStore.Images.Media.WIDTH).toInt()
                height = c.longOr0(MediaStore.Images.Media.HEIGHT).toInt()
                size = c.longOr0(MediaStore.Images.Media.SIZE)
                path = c.stringOrEmpty(MediaStore.Images.Media.DATA)
                if (path.isEmpty()) {
                    path = c.stringOrEmpty(MediaStore.Images.Media.RELATIVE_PATH) +
                        c.stringOrEmpty(MediaStore.Images.Media.DISPLAY_NAME)
                }
            }
        }
    } catch (_: Throwable) {
        // 查询失败 ⇒ 元数据留空，EXIF 那趟仍会试（信息面板宁可少几行也不报错弹窗）
    }

    val exif = queryExif(context, uri)
    return PhotoMeta(
        takenAtSec = photoTakenAtSec(
            dateTakenMs = takenMs,
            exifDateTimeOriginal = exif[ExifInterface.TAG_DATETIME_ORIGINAL],
            fileTimeSec = if (modifiedSec > 0) modifiedSec else addedSec,
        ),
        width = width,
        height = height,
        sizeBytes = size,
        path = path,
        make = exif[ExifInterface.TAG_MAKE],
        model = exif[ExifInterface.TAG_MODEL],
        iso = exif[ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY] ?: exif[ISO_FALLBACK_TAG],
        exposureTime = exif[ExifInterface.TAG_EXPOSURE_TIME],
        fNumber = exif[ExifInterface.TAG_F_NUMBER],
        focalLength = exif[ExifInterface.TAG_FOCAL_LENGTH],
        focalLength35 = exif[ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM],
    )
}

/** 读 EXIF（只需这几个标签；ExifInterface 的流式解析读完 EXIF 段即止，不整份读图） */
private fun queryExif(context: Context, uri: Uri): Map<String, String> = try {
    context.contentResolver.openInputStream(uri)?.use { ins ->
        val exif = ExifInterface(ins)
        EXIF_TAGS.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()
    }.orEmpty()
} catch (_: Throwable) {
    emptyMap()
}

/**
 * 老设备把 ISO 写在 `ISOSpeedRatings`（EXIF 2.2），新设备写在 `PhotographicSensitivity`
 * ⇒ 两个都读。**必须声明在 [EXIF_TAGS] 之前**：顶层属性按文件顺序初始化，反过来这里会
 * 被读到 null（静默丢掉 ISO）。
 */
@Suppress("DEPRECATION")
private val ISO_FALLBACK_TAG = ExifInterface.TAG_ISO_SPEED_RATINGS

private val EXIF_TAGS = listOf(
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ISO_FALLBACK_TAG,
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
)

private fun Cursor.longOr0(column: String): Long {
    val i = getColumnIndex(column)
    return if (i < 0 || isNull(i)) 0L else getLong(i)
}

private fun Cursor.stringOrEmpty(column: String): String {
    val i = getColumnIndex(column)
    return if (i < 0 || isNull(i)) "" else getString(i).orEmpty()
}
