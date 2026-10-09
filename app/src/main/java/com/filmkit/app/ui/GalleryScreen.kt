package com.filmkit.app.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 缩略图缓存上限：24MB / 300 张（12MP 成片的网格缩略图约 0.5MB/张 ⇒ 字节数先触顶） */
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
 * `ContentResolver` 全尺寸解码原图（[PhotoViewScreen.loadPhotoView] / EditorScreen.loadPreview）。
 *
 * 权限与降级：API 33+ 请求 READ_MEDIA_IMAGES、更早请求 READ_EXTERNAL_STORAGE。
 * 拿到 ⇒ 查整个 MediaStore（"所有照片"）；被拒 ⇒ **降级为本 App 的
 * Pictures/FilmKit**（免权限可见，见 [gallerySelection]），页面照常可用并给一行
 * "只看本应用的照片 + 授权"提示。查询/解码失败一律兜底成空态或占位图，不崩不白屏。
 */
@Composable
fun GalleryScreen(
    onBack: () -> Unit = {},
    /** 点缩略图：带该图的 MediaStore URI 进**查看页**（查看页自己解码原图，不用这里的缩略图） */
    onOpenPhoto: (Uri) -> Unit = {},
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
    // 网格项 ≈ 屏宽/3；按密度换算像素，交给平台缩略图接口拿"够用的那一档"
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
        SubPageTopBar("相册", onBack)

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
                        Text(
                            dayLabel(day.date),
                            color = FilmTheme.Cream,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
                        )
                    }
                    items(day.photos, key = { it.uri }) { photo ->
                        val uri = remember(photo.uri) { Uri.parse(photo.uri) }
                        ThumbCell(
                            uri = uri,
                            px = thumbPx,
                            cache = thumbs,
                            onClick = { onOpenPhoto(uri) },
                        )
                    }
                }
            }
        }
    }
}

/** 网格里的一格：缩略图（缓存命中直接显示）+ 加载中/失败的占位。 */
@Composable
private fun ThumbCell(
    uri: Uri,
    px: Int,
    cache: ThumbCache<String, Bitmap>,
    onClick: () -> Unit,
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
            .clickable { onClick() },
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
    }
}

/**
 * 读一张缩略图（IO 线程）。API 29+ 用平台 `loadThumbnail`；API 26-28 没有这个接口，
 * 退到 `MediaStore.Images.Thumbnails`（同属平台能力，仍然**只用于网格**）。
 * 失败返回 null，由调用方显示占位。
 */
private fun loadThumb(
    context: Context,
    uri: Uri,
    px: Int,
    cache: ThumbCache<String, Bitmap>,
): Bitmap? {
    cache.get(uri.toString())?.let { return it }
    val bmp = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(px, px), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver,
                ContentUris.parseId(uri),
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
        }
    } catch (_: Throwable) {
        null
    } ?: return null
    cache.put(uri.toString(), bmp)
    return bmp
}

/**
 * 查 MediaStore（IO 线程，见 [gallerySelection] 的口径）。返回 (列表, 错误文案)：
 * 任何异常都退化成空列表 + 一行提示，绝不向上抛（相册页不允许因为查询失败而白屏/崩）。
 */
private fun queryPhotos(context: Context, granted: Boolean): Pair<List<GalleryPhoto>, String> {
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

private fun hasImagePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, imageReadPermission()) ==
        PackageManager.PERMISSION_GRANTED
