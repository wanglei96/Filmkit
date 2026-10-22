package com.filmkit.app.ui

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 缩略图缓存上限：24MB / 300 张。12MP 成片按"屏宽/3"采样后约 0.5~0.8MB/张（典型手机 ⇒
 * 字节数先触顶）；大屏上目标边长顶到 512 时采样率只到 4，单张 ≈3MB、缓存深度掉到个位数
 * —— 这是 E2-18a 改用自解码（方向正确优先）后的代价，见交付报告。
 */
private const val THUMB_MAX_BYTES = 24L * 1024 * 1024
private const val THUMB_MAX_COUNT = 300

/** 相册网格列数（固定 3 列，与系统相册一致） */
private const val GRID_COLUMNS = 3

/**
 * 相册页（用户报的第 5/6 点）：网格显示**所有照片的缩略图**（按时间倒序 + 日期分组）。
 *
 * 点缩略图 → **照片查看页**（E2-13，ADR-15：传统相册逻辑「网格看缩略图、点开看原图」）；
 * 查看页里点「编辑」才进编辑器。此前是点缩略图直接进编辑器 —— 用户 2026-10-09 要求补上
 * 查看页这一层，本页因此只负责"挑图"，不再直接把 URI 交给编辑器。
 *
 * 素材分级（用户明确要求，不可混）：**缩略图只喂网格**；查看页与编辑器各自去
 * `ContentResolver` 全尺寸解码原图（PhotoViewScreen / EditorScreen.loadPreview）。
 *
 * 权限与降级：API 33+ 请求 READ_MEDIA_IMAGES、更早请求 READ_EXTERNAL_STORAGE。
 * 拿到 ⇒ 查整个 MediaStore（"所有照片"）；被拒 ⇒ **降级为本 App 的
 * Pictures/FilmKit**（免权限可见，见 [gallerySelection]），页面照常可用并给一行
 * "只看本应用的照片 + 授权"提示。查询/解码失败一律兜底成空态或占位图，不崩不白屏。
 */
@Composable
fun GalleryScreen(
    onBack: () -> Unit = {},
    /**
     * 点缩略图：带**整个扁平列表 + 点中的索引**进查看页（E2-20 左右滑动切图要用），
     * 查看页自己解码原图，不用这里的缩略图。
     */
    onOpenPhoto: (List<String>, Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val permission = remember { imageReadPermission() }
    var granted by rememberSaveable { mutableStateOf(hasImagePermission(context)) }
    var photos by remember { mutableStateOf<List<GalleryPhoto>?>(null) } // null = 读取中
    var error by remember { mutableStateOf("") }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok } // 拒绝不改任何状态：查询按 granted=false 重跑 ⇒ 自动降级

    // 进页面即请求（仅在还没授权时）；用户点提示行的"授权"可再请求
    LaunchedEffect(Unit) {
        if (!granted) permLauncher.launch(permission)
    }

    LaunchedEffect(granted) {
        photos = null
        error = ""
        val res = withContext(Dispatchers.IO) { queryPhotos(context, granted) }
        photos = res.first
        error = res.second
    }

    // ── 多选 / 批量删除（v0.12.34，用户要求："相册新增多选功能（批量删除），可按日期全选"）──
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    // 已提交给系统删除请求的那批（回调里要知道该从列表移除哪些）
    var doomed by remember { mutableStateOf(emptyList<String>()) }
    val scope = rememberCoroutineScope()

    // API 30+：系统弹一次确认后批量删除（与查看页单张删除同一套 API，权限模型一致）。
    // ★ v0.12.35：RESULT_OK 只代表"用户点了同意"，**不代表真的删掉了** —— 必须核实。
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val requested = doomed
            scope.launch {
                val goneSet = withContext(Dispatchers.IO) {
                    requested.filterNot { mediaStillExists(context, Uri.parse(it)) }
                }.toSet()
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "camera", "photo.trash_result",
                    kv("gone" to goneSet.size, "requested" to requested.size),
                )
                photos = photos?.filterNot { it.uri in goneSet }
                doomed = emptyList()
                if (goneSet.size == requested.size && requested.isNotEmpty()) {
                    selected = emptySet()
                    selecting = false
                    dropLastPhotoUri(context, goneSet)
                    // API 30+ 是"移入最近删除"（可恢复），低版本是真删 —— 文案不能撒谎
                    status = if (Build.VERSION.SDK_INT >= SYSTEM_DELETE_REQUEST_API) {
                        "已移入「最近删除」，可在系统相册中恢复"
                    } else {
                        ""
                    }
                } else {
                    // 部分/全部没删掉：留在列表里，别让用户以为删干净了
                    selected = selected.filterNot { it in goneSet }.toSet()
                    status = "${requested.size - goneSet.size} 张没有删除成功（可重试）"
                }
            }
        } else {
            status = "已取消删除"
        }
    }

    /**
     * 批量删除（安全红线：绝不能误删**且要能反悔**）。
     *
     * ★ v0.12.34：API 30+ 一律走 [MediaStore.createTrashRequest] —— 照片进系统
     * 「最近删除」（MediaProvider 的 trash，30 天后自动清理），用户可在系统相册里
     * 一键恢复。这正是"和系统相册一样"的做法，比自建回收站省一整套代码（也省了
     * 私有目录的空间与恢复入口）。
     *
     * 三道闸：① 本页二次确认（写清张数、是否全库、能否恢复）；
     * ② 系统确认弹窗（API 30+）；③ 回收站本身（30 天内可撤销）。
     * API 26-29 没有 trash 与系统弹窗 ⇒ 只有 ①，此时文案明说"不可恢复"，
     * 删除失败必须如实报（不许静默）。
     */
    fun deleteSelected() {
        val uris = selected.toList()
        if (uris.isEmpty()) return
        doomed = uris
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "photo.trash_request",
            kv("count" to uris.size, "api" to Build.VERSION.SDK_INT),
        )
        if (Build.VERSION.SDK_INT >= SYSTEM_DELETE_REQUEST_API) {
            try {
                val req = MediaStore.createTrashRequest(
                    context.contentResolver, uris.map { Uri.parse(it) }, true,
                )
                deleteLauncher.launch(IntentSenderRequest.Builder(req.intentSender).build())
            } catch (t: Throwable) {
                status = "删除失败：${t.message?.take(60) ?: "系统拒绝了删除请求"}"
            }
        } else {
            scope.launch {
                var deleted = 0
                var err = ""
                withContext(Dispatchers.IO) {
                    for (u in uris) {
                        val n = try {
                            context.contentResolver.delete(Uri.parse(u), null, null)
                        } catch (t: Throwable) {
                            err = t.message?.take(60) ?: "无权限或该照片不可删除"
                            0
                        }
                        if (n > 0) deleted++ else if (err.isEmpty()) err = "系统没有删除这张照片"
                    }
                }
                if (deleted > 0) {
                    val gone = uris.toSet()
                    photos = photos?.filterNot { it.uri in gone }
                    selected = emptySet()
                    selecting = false
                    status = if (deleted == uris.size) "" else "部分照片删除失败：$err"
                } else {
                    status = "删除失败：$err"
                }
            }
        }
    }

    // 离页即释放缓存（只丢引用，见 ThumbCache.clear 的 KDoc）。不做进程级常驻：
    // 相册页退出后没人再看这些缩略图，留着只是白占内存。
    val thumbs = remember {
        ThumbCache<String, Bitmap>(THUMB_MAX_BYTES, THUMB_MAX_COUNT) { it.byteCount }
    }
    DisposableEffect(Unit) {
        onDispose { thumbs.clear() }
    }

    val days = remember(photos) {
        photos?.let { groupByDay(it) }.orEmpty()
    }
    // 网格是**按天分组**画的，查看页的 Pager 却是一条扁平列表（E2-20）⇒ 这里把分组后的顺序
    // 摊平一次，点第 N 格就传扁平索引 N：两侧口径必须完全一致，否则点开的是另一张。
    val flatUris = remember(days) { days.flatMap { it.photos }.map { it.uri } }
    // 网格项 ≈ 屏宽/3；按密度换算像素，作为自解码的目标边长（采样率由 decodeOriented 算）
    val thumbPx = with(density) {
        (configuration.screenWidthDp.dp / GRID_COLUMNS).roundToPx().coerceIn(160, 512)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(FilmTheme.Body)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (selecting) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    selecting = false
                    selected = emptySet()
                }) { Text("取消", color = FilmTheme.Cream, fontSize = 13.sp) }
                Text(
                    if (selected.isEmpty()) "选择照片" else "已选 ${selected.size} 张",
                    color = FilmTheme.Cream,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    selected = if (selected.size == flatUris.size) emptySet() else flatUris.toSet()
                }) {
                    Text(
                        if (selected.size == flatUris.size && flatUris.isNotEmpty()) "取消全选" else "全选",
                        color = FilmTheme.Accent,
                        fontSize = 13.sp,
                    )
                }
                TextButton(
                    onClick = { if (selected.isNotEmpty()) confirmDelete = true },
                    enabled = selected.isNotEmpty(),
                ) {
                    Text(
                        "删除",
                        color = if (selected.isEmpty()) FilmTheme.CreamDim else Color(0xFFE05B4B),
                        fontSize = 13.sp,
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SubPageTopBar("相册", onBack) }
                TextButton(onClick = {
                    selecting = true
                    status = ""
                }) { Text("选择", color = FilmTheme.Accent, fontSize = 13.sp) }
            }
        }

        // 降级提示（仅未授权时）：既解释为什么照片少，也给回授权的入口
        if (!granted) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "未授权相册，仅显示本应用拍摄的照片",
                    color = FilmTheme.CreamDim,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { permLauncher.launch(permission) }) {
                    Text("查看全部照片", color = FilmTheme.Accent, fontSize = 12.sp)
                }
            }
        }
        if (error.isNotEmpty()) {
            Text(
                error,
                color = FilmTheme.CreamDim,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (status.isNotEmpty()) {
            Text(
                status,
                color = FilmTheme.CreamDim,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        when {
            photos == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FilmTheme.Accent)
            }
            photos!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (granted) "相册里还没有照片" else "本应用还没有照片，授权后可查看全部",
                    color = FilmTheme.CreamDim,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                days.forEach { day ->
                    item(key = "day-${day.date}", span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                dayLabel(day.date),
                                color = FilmTheme.Cream,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (selecting) {
                                val dayUris = day.photos.map { it.uri }
                                val allOn = dayUris.all { it in selected }
                                TextButton(onClick = {
                                    selected = if (allOn) selected - dayUris.toSet()
                                    else selected + dayUris
                                }) {
                                    Text(
                                        if (allOn) "取消本日" else "全选本日",
                                        color = FilmTheme.Accent,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                    items(day.photos, key = { it.uri }) { photo ->
                        val uri = remember(photo.uri) { Uri.parse(photo.uri) }
                        ThumbCell(
                            uri = uri,
                            px = thumbPx,
                            cache = thumbs,
                            selecting = selecting,
                            selected = photo.uri in selected,
                            onClick = {
                                if (selecting) {
                                    selected = if (photo.uri in selected) selected - photo.uri
                                    else selected + photo.uri
                                } else {
                                    // 索引在**扁平列表**里取（与查看页的 Pager 同一口径）
                                    val i = flatUris.indexOf(photo.uri)
                                    if (i >= 0) onOpenPhoto(flatUris, i)
                                }
                            },
                            // 长按进入多选并选中这一张（与系统相册一致）
                            onLongClick = {
                                if (!selecting) selecting = true
                                selected = selected + photo.uri
                            },
                        )
                    }
                }
            }
        }
    }

    // 删除的第一道闸（第二道是 API 30+ 的系统弹窗，见 [deleteSelected]）。
    // 文案必须**如实**区分两条路：30+ 是移入回收站（可恢复），26-29 是真删（不可恢复）。
    if (confirmDelete) {
        val n = selected.size
        val trash = Build.VERSION.SDK_INT >= SYSTEM_DELETE_REQUEST_API
        // 全库一次性清空的场景单独说一句 —— 用户最怕的就是这个（2026-10-22 特别叮嘱）
        val wholeLibrary = n > 0 && n == flatUris.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(
                    (if (trash) "移入最近删除 $n 张照片？" else "删除 $n 张照片？"),
                    color = FilmTheme.Cream,
                    fontFamily = FilmTheme.Serif,
                )
            },
            text = {
                Text(
                    buildString {
                        if (wholeLibrary) append("这会清空相册当前显示的全部照片。\n")
                        if (trash) {
                            append("系统还会再弹一次确认。移入后可在系统相册的「最近删除」里恢复")
                            append("（30 天后自动清除）。")
                        } else {
                            append("此设备不支持「最近删除」，删除后无法恢复。")
                        }
                    },
                    color = FilmTheme.CreamDim,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleteSelected()
                }) {
                    Text(
                        if (trash) "移入最近删除" else "删除",
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FilmTheme.Mono,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消", color = FilmTheme.CreamDim)
                }
            },
            containerColor = FilmTheme.ChipBg,
        )
    }
}

/** 网格里的一格：缩略图（缓存命中直接显示）+ 加载中/失败的占位。 */
@Composable
private fun ThumbCell(
    uri: Uri,
    px: Int,
    cache: ThumbCache<String, Bitmap>,
    selecting: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val key = uri.toString()
    // 初值先查缓存：滚回已看过的位置时不必再等一次 IO（也不需要重组后的二次刷新）
    var bmp by remember(key) { mutableStateOf(cache.get(key)) }
    LaunchedEffect(key, px) {
        if (bmp == null) {
            bmp = withContext(Dispatchers.IO) { loadThumb(context, uri, px, cache) }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(FilmTheme.ChipBg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "照片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 加载中与加载失败（如 DNG 这类解不开的格式）共用占位：不闪、不崩
            Text("▦", color = FilmTheme.CreamDim, fontSize = 18.sp)
        }
        // 多选态：选中蒙层 + 右上角勾选圈（未选中画空心圈，让"长按可多选"有可点暗示）
        if (selecting) {
            if (selected) {
                Box(Modifier.fillMaxSize().background(Color(0x33120E0A)))
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (selected) FilmTheme.Accent else Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (selected) "✓" else "",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * URI 指向的照片是否**仍然存在**（v0.12.35）。
 *
 * 为什么要这一步：`createTrashRequest` 的系统弹窗返回 `RESULT_OK` **只代表用户同意了**，
 * 不代表 MediaProvider 真的执行了（真机反馈"删除了但照片还在"）。删除成功后必须用一次
 * 单条查询核实 —— `contentResolver.query(uri, ...)` 在媒体已移入回收站/被删后返回 0 行。
 *
 * 查询抛异常（FileNotFoundException / SecurityException）同样按"已不存在"处理：
 * provider 连这条记录都拿不出来，用户也就看不到它了。
 */
internal fun mediaStillExists(context: Context, uri: Uri): Boolean = try {
    val cols = if (Build.VERSION.SDK_INT >= 30) {
        arrayOf(MediaStore.Images.Media._ID, "is_trashed")
    } else {
        arrayOf(MediaStore.Images.Media._ID)
    }
    context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
        if (!c.moveToFirst()) {
            false
        } else {
            // API 30+：进了回收站的记录**可能仍在**（某些 ROM 不过滤单条查询）⇒ 读列判断
            val ti = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex("is_trashed") else -1
            !(ti >= 0 && c.getInt(ti) == 1)
        }
    } ?: false
} catch (t: Throwable) {
    // provider 连这条记录都拿不出来（FileNotFound / Security）⇒ 用户也看不到它了
    false
}

/**
 * 删掉（含移入回收站）的正是胶卷位那张 ⇒ 清掉 prefs 里的 `last_photo_uri`，
 * 否则相机页下次冷启动会拿一个已失效的 URI 去解码、缩略图只能显示占位。
 */
internal fun dropLastPhotoUri(context: Context, gone: Collection<String>) {
    val p = context.getSharedPreferences("filmkit", 0)
    val cur = p.getString("last_photo_uri", null) ?: return
    if (gone.contains(cur)) p.edit().remove("last_photo_uri").apply()
}

/**
 * 读一张缩略图（IO 线程）：**自解码 + EXIF 方向转正**（[decodeOriented]）。
 *
 * E2-18a 由平台 API（API 29+ `loadThumbnail` / API 26-28 `Thumbnails.getThumbnail`）改过来：
 * 平台缩略图对 **DNG** 返回的方向不对（用户真机反馈"相册里 dng 方向不对，要和成片一致"），
 * 而方向正确优先于平台的缩略图缓存。`px` 是格子的目标边长，采样率（2 的幂）由
 * [sampleSizeFor] 按它算 —— 12MP 原图在典型手机上采样率 ≈8，解出来 ≈0.5MP。
 *
 * 失败返回 null，由调用方显示占位。
 */
private fun loadThumb(
    context: Context,
    uri: Uri,
    px: Int,
    cache: ThumbCache<String, Bitmap>,
): Bitmap? {
    cache.get(uri.toString())?.let { return it }
    val bmp = decodeOriented(context, uri, px, px) ?: return null
    cache.put(uri.toString(), bmp)
    return bmp
}

/**
 * 查 MediaStore（IO 线程，见 [gallerySelection] 的口径）。返回 (列表, 错误文案)：
 * 任何异常都退化成空列表 + 一行提示，绝不向上抛（相册页不允许因为查询失败而白屏/崩）。
 */
internal fun queryPhotos(context: Context, granted: Boolean): Pair<List<GalleryPhoto>, String> {
    val selection = gallerySelection(granted, Build.VERSION.SDK_INT)
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED,
    )
    val out = ArrayList<GalleryPhoto>()
    return try {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out += GalleryPhoto(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        .toString(),
                    c.getLong(dateCol),
                )
            }
        }
        out to ""
    } catch (t: Throwable) {
        emptyList<GalleryPhoto>() to "读取相册失败：${t.message?.take(40) ?: "未知错误"}"
    }
}

/** 当前系统版本该请求的读图权限（API 33 起细分媒体权限） */
private fun imageReadPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

internal fun hasImagePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, imageReadPermission()) ==
        PackageManager.PERMISSION_GRANTED
