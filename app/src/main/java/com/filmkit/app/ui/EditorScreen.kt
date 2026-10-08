package com.filmkit.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmkit.app.camera.StillSnapshotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 内建编辑器（E1-1 骨架）：胶卷位点进来，全屏看成片，底部预留操作区。
 *
 * 本轮**只读图与展示**：参数面板 / 渲染 / 导出是 E1-3、E1-4 的事，这里一概不接。
 * 素材分级（ADR-2）：编辑对象是**成片 JPEG**（OETF 之后），只能做后段风格化，
 * 改不了 WB/曝光 —— 底部文案必须把这条讲清楚，否则用户会以为在"重新显影"。
 *
 * @param uri 待编辑成片的 MediaStore URI；为 null（如进程重建后状态丢失）时显示失败态而非白屏。
 */
@Composable
fun EditorScreen(
    uri: Uri?,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // 目标显示尺寸 = 屏幕像素尺寸。全屏 ContentScale.Fit 下再高的分辨率也看不出差别，
    // 而原样解码一张 5000×4000 的成片要 ~80MB —— 采样是防 OOM 的第一道闸。
    val targetW = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val targetH = with(density) { configuration.screenHeightDp.dp.roundToPx() }

    var state by remember { mutableStateOf<EditorImageState>(EditorImageState.Loading) }
    LaunchedEffect(uri, targetW, targetH) {
        state = EditorImageState.Loading
        state = withContext(Dispatchers.IO) { loadForDisplay(context, uri, targetW, targetH) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(FilmTheme.Body)
            .statusBarsPadding(),
    ) {
        SubPageTopBar("编辑", onBack)

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                EditorImageState.Loading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = FilmTheme.Accent)
                        Spacer(Modifier.height(12.dp))
                        Text("正在读取成片…", color = FilmTheme.CreamDim, fontSize = 13.sp)
                    }
                }
                is EditorImageState.Failed -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(s.reason, color = FilmTheme.Cream, fontSize = 14.sp)
                        Text("可返回相机页重新拍摄", color = FilmTheme.CreamDim, fontSize = 12.sp)
                    }
                }
                is EditorImageState.Ready -> {
                    val image = remember(s.bitmap) { s.bitmap.asImageBitmap() }
                    Image(
                        bitmap = image,
                        contentDescription = "成片预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // ── 底部操作区（E1-1 占位）：E1-3/E1-4 的参数面板与保存按钮落在这条里 ──
        Column(
            Modifier
                .fillMaxWidth()
                .background(FilmTheme.PanelSolid)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            val ready = state as? EditorImageState.Ready
            Text(
                text = ready?.let { "${it.name} · ${it.width} × ${it.height}" } ?: "—",
                color = FilmTheme.Cream,
                fontFamily = FilmTheme.Mono,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "成片编辑 · 仅后段风格化（LUT / 颗粒 / 暗角），不改曝光与白平衡",
                color = FilmTheme.CreamDim,
                fontSize = 11.sp,
            )
        }
    }
}

/** 编辑器图片加载状态：三态显式建模，避免"位图与错误同时非空"这类非法组合 */
private sealed interface EditorImageState {
    data object Loading : EditorImageState

    /** [width]/[height] 是**原图**像素尺寸（底部读数用），不是采样后的解码尺寸 */
    data class Ready(
        val bitmap: Bitmap,
        val name: String,
        val width: Int,
        val height: Int,
    ) : EditorImageState

    data class Failed(val reason: String) : EditorImageState
}

/**
 * 读图（IO 线程）：先只读尺寸（inJustDecodeBounds）算采样比，再按采样比解码。
 * 先解码再缩是不行的 —— 大图在解码那一步就已经 OOM 了。
 */
private fun loadForDisplay(
    context: Context,
    uri: Uri?,
    targetW: Int,
    targetH: Int,
): EditorImageState {
    if (uri == null) return EditorImageState.Failed("没有可编辑的成片")
    val resolver = context.contentResolver

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    } catch (_: Throwable) {
        return EditorImageState.Failed("图片已失效或无法读取")
    }
    val srcW = bounds.outWidth
    val srcH = bounds.outHeight
    if (srcW <= 0 || srcH <= 0) return EditorImageState.Failed("图片已失效或无法读取")

    // Fit 到显示框后的实际尺寸：采样按它算，才不会为只显示 1080 宽的竖屏解出 4000 宽的位图
    val fit = min(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
    val fitW = srcW * fit
    val fitH = srcH * fit
    // 解码器的 inSampleSize 只认 2 的幂（给别的值也会向下取整），所以按 2 的幂递增；
    // 条件是"再降一档仍不小于显示尺寸"，即解出来的位图刚好够清晰
    var sample = 1
    while (srcW / (sample * 2) >= fitW && srcH / (sample * 2) >= fitH) sample *= 2

    // 文件名一律走 StillSnapshotStore.fileNameOf（ADR 集成点 I-1）：参数快照的键就是它，
    // 两处各写一份实现，一旦行为不一致，编辑器查到的键就不是落盘时那个（静默取不到快照）。
    // 查不到时的兜底保留原样（URI 末段 → "成片"）。
    val name = StillSnapshotStore.fileNameOf(context, uri) ?: uri.lastPathSegment ?: "成片"
    // OOM 兜底：极端大图 / 低内存设备上降一档重试一次，仍失败才报错（宁可糊也不崩）
    repeat(2) {
        try {
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return EditorImageState.Failed("图片已失效或无法读取")
            return EditorImageState.Ready(bitmap, name, srcW, srcH)
        } catch (_: OutOfMemoryError) {
            sample *= 2
        } catch (_: Throwable) {
            return EditorImageState.Failed("图片已失效或无法读取")
        }
    }
    return EditorImageState.Failed("图片过大，无法加载")
}
