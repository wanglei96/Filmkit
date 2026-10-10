package com.filmkit.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import com.filmkit.app.camera.BuiltinPresets
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.PresetScene
import com.filmkit.app.camera.presetScopeNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── 缩放 / 平移 / 手势仲裁的纯逻辑（E2-20；JVM 单测见 PhotoViewZoomTest）────────
// 与 PhotoViewerModel.kt 同规矩：**不 import 任何 android.\***。这里算错全是静默事故
// （图能被拖出画面外、能缩到比 Fit 还小、Fit 下横滑切不了图），所以必须能在 JVM 里真跑。

/** 缩放下限 = **Fit（原片默认展示大小）**：用户 2026-10-10 明确要求"最小为原片默认展示大小" */
internal const val MIN_ZOOM = 1f

/** 缩放上限：12MP 原图在 1080p 屏上 Fit ≈0.27×，5× 已是 1:1 像素的 1.35 倍，再多只剩糊 */
internal const val MAX_ZOOM = 5f

/**
 * 放大到这一档就补解一张 `viewSize × 2` 的位图（E2-22）。**两档足够**：12MP 的 4000px 长边
 * 在 2× 档就已经落到 sample=1（全分辨率），再往上没有更多像素可解。必须落在
 * `(MIN_ZOOM, MAX_ZOOM]` 区间内，否则升档永远不触发（或一放大就到顶）。
 */
internal const val ZOOM_HIRES = 2f

/** 预览占位边长（px）：与 PC 的 `PREVIEW_SIDE = 360` 同口径 —— 先上屏一张小图消灭等待感 */
private const val PREVIEW_SIDE = 360

/** 相邻页预览缓存：3 张（≈0.5–0.75MB/张），PC `previewCache(3)` 的等价物 */
private const val PREVIEW_CACHE_COUNT = 3
private const val PREVIEW_CACHE_BYTES = 4L * 1024 * 1024

/**
 * 手势仲裁结果（本卡最容易出错的地方，故用枚举把仲裁表钉死）：
 *
 * | 状态 | 单指 | 双指 |
 * |---|---|---|
 * | Fit（[MIN_ZOOM]） | [Swipe] ⇒ **不消费事件**，交给 Pager 切换图片 | [Zoom] |
 * | 放大（> [MIN_ZOOM]） | [Pan] ⇒ 平移，**消费事件**（Pager 收不到 ⇒ 不切图） | [Zoom] |
 *
 * 关键在 Fit 下的单指：手势层必须**一个事件都不消费**，否则 Pager 的滚动永远拿不到横滑，
 * 左右切图整个失效（这是把 `detectTransformGestures` 直接挂上去最经典的翻车点：它单指
 * 超过 touchSlop 就 consume）。
 */
internal enum class ViewerGesture { Zoom, Pan, Swipe }

internal fun viewerGesture(pressedPointers: Int, zoomed: Boolean): ViewerGesture = when {
    pressedPointers >= 2 -> ViewerGesture.Zoom
    // 手指全抬起（pressed = 0，最后一个 UP 事件）⇒ 什么都不做：不消费、不改平移量
    pressedPointers <= 0 -> ViewerGesture.Swipe
    zoomed -> ViewerGesture.Pan
    else -> ViewerGesture.Swipe
}

/** 乘性缩放 + 夹紧到 [MIN_ZOOM]..[MAX_ZOOM]；非法 factor（NaN / 0 / 负）保持原值不跳变 */
internal fun clampZoom(current: Float, factor: Float): Float {
    val next = if (factor.isFinite() && factor > 0f) current * factor else current
    return next.coerceIn(MIN_ZOOM, MAX_ZOOM)
}

/**
 * `ContentScale.Fit` 之后图片在容器里的显示尺寸（像素）。图片尺寸未知（还没解码 / 解不开）
 * 时退化成容器尺寸 —— 此时缩放平移本来也不该动。
 */
internal fun fitDisplaySize(imgW: Int, imgH: Int, viewW: Float, viewH: Float): Pair<Float, Float> {
    if (imgW <= 0 || imgH <= 0 || viewW <= 0f || viewH <= 0f) return viewW to viewH
    val s = minOf(viewW / imgW, viewH / imgH)
    return imgW * s to imgH * s
}

/**
 * 单轴平移夹紧（居中坐标系：0 = 居中，正 = 向右/向下）。内容比视口大多少，才允许移多少。
 * 内容不超过视口 ⇒ 上限 0 ⇒ **Fit 尺寸下平移量必然归零**（不需要额外写一条"缩回 Fit 时
 * 归零"的分支，夹紧规则本身就保证了）。
 */
internal fun clampAxis(offset: Float, view: Float, content: Float): Float {
    if (!offset.isFinite()) return 0f
    val max = ((content - view) / 2f).coerceAtLeast(0f)
    return offset.coerceIn(-max, max)
}

/** 索引边界（列表为空 / 越界都收敛到 0：Pager 不接受越界页，路由层也不能拿到越界索引） */
internal fun clampIndex(index: Int, size: Int): Int = if (size <= 0) 0 else index.coerceIn(0, size - 1)

/**
 * 单页该套用的缩放（E2-23，修用户报的"相册放大图片隔壁图片会入侵视野"）：
 * **只有"停稳"的那一页（`settledPage`）跟随用户的捏合**，相邻页恒为 1×。
 *
 * 为什么必须有这条：`beyondViewportPageCount = 1` 让相邻页也被组合/绘制（E2-22 的预载），
 * 而 `zoom` / `pan` 是**整页共享**的 state —— 相邻页的 Image 若跟着 3× 放大，它会以自己
 * 的页心（在视口外 ±1 个页宽处）为中心向两侧各扩 1.5 页宽 ⇒ **跨过页边界画进视口**，
 * 视口里就出现别张照片的像素。页宽 = 视口宽（`PageSize.Fill` 是 HorizontalPager 默认值），
 * 所以相邻页只要保持恒等变换，就必然整个待在视口之外。
 *
 * 放大态下 Pager 本来就不滚（`userScrollEnabled = !zoomed`），切页时 `zoom` 又被复位成
 * [MIN_ZOOM] ⇒ 恒等变换不会让"滑动切图"少画任何东西。
 */
internal fun pageScaleAt(isSettledPage: Boolean, zoom: Float): Float =
    if (isSettledPage) zoom else 1f

/** 同 [pageScaleAt]：相邻页也不跟随当前页的平移量（平移讲的是"当前这张在视口里怎么挪"）。 */
internal fun pageShiftAt(isSettledPage: Boolean, shift: Float): Float =
    if (isSettledPage) shift else 0f

/**
 * 照片查看页（ADR-15，E2-13）：**网格点开先到这一页**，点「编辑」才进编辑器；编辑器退出
 * （取消 / 系统返回）回到本页 —— 用户 2026-10-09 明确要求"取消后应该回到照片查看页"。
 *
 * 五层结构照 ADR-15 截图：① 顶栏（返回 ｜ **日期 + 时间**居中两行 ｜ 收藏心形）
 * ② 照片区（**纯黑底 + 原图**，用户 2026-10-08："点开看原图"）
 * ③ 底部操作栏（编辑 / 胶片 / 分享 / 信息 / 删除）。
 *
 * **E2-20 新增**（用户 2026-10-10）：② 换成 `HorizontalPager` ⇒ 左右滑动切换图片；
 * 双指捏合/展开缩放（下限 = Fit，见 [MIN_ZOOM]）、放大后单指拖动平移。
 *
 * **E2-22 优化**（用户报"切图慢"，ADR-18 方案 B）：不再解全分辨率（12MP ≈48MB），改为
 * ① 按**显示尺寸**解码（[decodeFit]，Fit 档 ≈3MP ≈12MB）；② 360px 预览先上屏（[PREVIEW_SIDE]）；
 * ③ 相邻页只解预览（`beyondViewportPageCount = 1`）；④ 放大到 [ZOOM_HIRES] 补解一档。
 *
 * ★ **E2-23 修**（用户 2026-10-13 报："相册放大图片隔壁图片会入侵视野"）：E2-22 的
 * `beyondViewportPageCount = 1` 让相邻页被组合/绘制，而 `zoom` / `pan` 是**整页共享**的
 * state ⇒ 相邻页跟着当前页一起被放大，从视口外（页心在 ±1 页宽处）越界画进视口，
 * 视口里出现别张照片的像素。两道闸修掉：
 * ① [pageScaleAt] / [pageShiftAt] —— 缩放平移**只给停稳页**（根因）；
 * ② 每页根容器 `clipToBounds()` —— 页边界裁剪（像素出不去本页，兜底）。
 * 手势仲裁表（E2-20）与 P-3 的两个开关一行未改（见 [viewerGesture]）。
 *
 * ★ **P-3**（用户 2026-10-11 拍板）：底栏「胶片」由**置灰留位**改为**可点** —— 弹出
 * [FilmPresetSheet]（自绘底部面板，与拍照界面 `LutSheet` 同一套视觉语言），列出
 * `BuiltinPresets.all` 的全部预设 = **用户预设 + 内置三条**（**三处入口同一套数据**，ADR-7
 * 裁决；P-2c 起统一走这一个入口）。选一条 ⇒
 * `onEditWithPreset(uri, presetId)` ⇒ 路由层带着该 id 进编辑器，编辑器在基线就绪后套用一次
 * ⇒ **直接看到成片效果**（不做"保存副本"：用户拍板的是"进编辑器"）。
 * 列表为空时给一句实话（"还没有预设 · 在编辑器里保存"），**不留点不动的按钮**（ADR-12）。
 *
 * 面板与 E2-20 两个手势的归属（本卡最容易出事故的地方）：面板打开时
 * ① Pager 的 `userScrollEnabled` 关掉、② 缩放/平移的手势层在 [awaitEachGesture] 里直接返回
 * ⇒ 面板区域的任何滑动/捏合都**不会**传到图片上（也不会"选着预设把图滑走了"）；
 * 面板关闭后两个开关原样恢复，左右切图与双指缩放的行为与 P-3 之前逐位一致。
 *
 * 素材分级（与相册页同一约定）：本页**自己解码原图**（按显示尺寸，不是网格缩略图那一档）。
 * 权限沿用相册页那套（未授权时相册只列本应用照片），本页不新增权限流程。
 *
 * @param uris 可左右滑动的照片列表（**相册网格的扁平顺序**，URI 字符串）；空列表显示失败态。
 * @param index 初始第几张（相册点第 N 张就从第 N 张开始）。
 * @param onIndexChange 滑动切到新的一张时回调 —— 路由层必须跟着更新，否则"从查看页进编辑器、
 *   退出回查看页"会回到**上一张**（见 MainActivity 的 viewerIndex）。
 * @param onEdit 点「编辑」→ 交给编辑器（路由层记录"从查看页进来的"+当前索引，退出时回本页同一张）。
 * @param onEditWithPreset 点「胶片」选中的预设 → 同样进编辑器，但**带上预设 id**（P-3）；
 *   语义与 [onEdit] 完全一致（同一张照片、退出回本页），只是编辑器会先套用这条预设。
 * @param onDeleted 删除成功 → 回相册（那张已不存在，留在本页只会看到失效图）。
 */
@Composable
fun PhotoViewScreen(
    uris: List<String>,
    index: Int = 0,
    onIndexChange: (Int) -> Unit = {},
    onBack: () -> Unit = {},
    onEdit: (Uri) -> Unit = {},
    onEditWithPreset: (Uri, String) -> Unit = { _, _ -> },
    onDeleted: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }
    val pageCount = uris.size
    val pagerState = rememberPagerState(initialPage = clampIndex(index, pageCount)) { pageCount }

    // **只有"停稳"的那一页解大图**（settledPage：滚动动画结束后才更新）。E2-22 起按显示尺寸解
    // （12MP 只解 ≈3MP ≈12MB），但滑动过程中 Pager 仍会组合相邻页，按 currentPage 解码就会同时压两三张。
    val page = pagerState.settledPage
    // remember 必须**无条件**调用（放在 getOrNull 的 let 里会随 null 与否改变 slot 结构 ⇒
    // 组合期报错），所以先取值再 remember 它
    val currentKey = uris.getOrNull(page)
    val currentUri = remember(currentKey) { currentKey?.let { Uri.parse(it) } }

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    // bitmap 对应的**是哪张图、哪个档位**（0 = 360px 预览，1 = Fit，2 = 放大档）。
    // 用来区分"切页要重解"与"同页升档只需补解更高档"：缩回 Fit 时 shownTier=2 ≥ 目标 1 ⇒ 不重解
    // （高清留着更好，降回去反而更糊）。
    var shownUri by remember { mutableStateOf<String?>(null) }
    var shownTier by remember { mutableStateOf(0) }
    var meta by remember { mutableStateOf(PhotoMeta()) }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // ── ★ P-3「胶片」预设面板 ─────────────────────────────────────────────
    // `showFilmPanel` = 面板是否打开（自绘覆盖层，**不用 M3 ModalBottomSheet**：真机返回要按
    // 两次才关，用户 2026-10-03 实测，见 SettingsScreen 的 SettingsDrawer）；`presets` = 打开
    // 面板时从 prefs 读一次的镜像（与 EditorScreen.togglePresets 同一节奏、同一数据源）。
    var showFilmPanel by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf<List<EditorPreset>>(emptyList()) }
    // 收藏的唯一数据源是这份 state；prefs 只负责持久化（见 KDoc 的"没有统一收藏 API"）
    var favorites by remember {
        mutableStateOf(favoritesFrom(prefs.getString(FAVORITES_KEY, null)))
    }
    // 缩放 / 平移：state 对象本身被手势协程捕获（读 .value 永远是最新值，不需要 rememberUpdatedState）
    val zoom = remember { mutableFloatStateOf(MIN_ZOOM) }
    val pan = remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // 是否放大态（决定 Pager 还能不能滚）。用 derivedStateOf：捏合时 zoom 每帧都在变，
    // 直接读 float 会让整页每帧重组；这里只有**跨越 1× 那一下**才通知重组。
    val zoomed by remember { derivedStateOf { zoom.floatValue > MIN_ZOOM } }
    // 是否到了"补解一档"的放大程度（同理只在跨阈值那一下通知，捏合过程不重启解码）
    val hiRes by remember { derivedStateOf { zoom.floatValue >= ZOOM_HIRES } }
    // 相邻页预载的 360px 预览（PC `previewCache(3)` 的等价物）：滑过去的那一瞬间就有内容。
    // 只丢引用不 recycle（可能正被画在屏幕上，见 ThumbCache.clear 的 KDoc）。
    val previews = remember {
        ThumbCache<String, Bitmap>(PREVIEW_CACHE_BYTES, PREVIEW_CACHE_COUNT) { it.byteCount }
    }
    DisposableEffect(Unit) {
        onDispose { previews.clear() }
    }

    val key = currentUri?.toString()
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
        val u = currentUri ?: return
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

    // ── 切页：**缩放/平移复位**（新图从 Fit 开始）+ 元数据先到（顶栏立刻更新）────────
    // 与解码协程分开：解码的 key 含 hiRes/viewSize，放大或窗口尺寸变化时它会重启 ——
    // 复位/元数据**不能**跟着重启（放大一下就把用户的缩放弹回 Fit 是灾难）。
    LaunchedEffect(page, uris) {
        zoom.floatValue = MIN_ZOOM
        pan.value = Offset.Zero
        bitmap = null // 上一张的像素内容不对，必须丢（不留作占位）
        error = ""
        status = "" // 上一张的提示（分享失败等）不跟着跨图
        val u = uris.getOrNull(page)
        if (u == null) {
            error = "照片已失效"
            return@LaunchedEffect
        }
        // 元数据（MediaStore + EXIF）几十毫秒就回来，先更新顶栏，别让日期停在上一张
        meta = withContext(Dispatchers.IO) { queryMeta(context, Uri.parse(u)) }
    }

    // ── 解码（E2-22 两段式 + 两档）────────────────────────────────────────
    // ① 360px 预览先上屏（命中相邻页预载就零成本）→ ② 按显示尺寸解目标档（Fit / viewSize×2）。
    // 目标档解码期间**旧位图一直占位**，解完才替换 —— 全程没有"转圈等大图"。
    LaunchedEffect(page, uris, hiRes, viewSize) {
        val u = uris.getOrNull(page) ?: return@LaunchedEffect
        val vw = viewSize.width
        val vh = viewSize.height
        // 容器还没测量（首帧 onSizeChanged 之前）⇒ 算不出 Fit 尺寸。viewSize 是 key，测量完会重启；
        // 这里直接返回，好过拿 0 去解码（那会退化成全分辨率 48MB）。
        if (vw <= 0 || vh <= 0) return@LaunchedEffect
        val target = if (hiRes) 2 else 1
        // 已经有"不差于目标档"的位图（同页缩回 Fit / 无关重组）⇒ 什么都不做
        if (shownUri == u && shownTier >= target && bitmap != null) return@LaunchedEffect

        val parsed = Uri.parse(u)
        if (shownUri != u) {
            // 切页：相邻页预载过这张就直接上屏（零解码），否则下面补一张
            bitmap = previews.get(u)
            shownUri = u
            shownTier = 0
        }
        if (bitmap == null) {
            val preview = withContext(Dispatchers.IO) {
                decodeFit(context, parsed, PREVIEW_SIDE, PREVIEW_SIDE)
            }
            if (preview != null) {
                bitmap = preview
                previews.put(u, preview)
            }
        }
        // 目标档：Fit（12MP ⇒ 2000×1500 ≈12MB）或放大档（viewSize×2；12MP 上这档等价全分辨率）
        val b = withContext(Dispatchers.IO) {
            decodeFit(context, parsed, vw * target, vh * target)
        }
        if (b != null) {
            bitmap = b
            shownTier = target
        } else if (bitmap == null) {
            error = "照片已失效或无法读取"
        } else {
            // 预览在、目标档没解出来（decodeOriented 内部已按 OOM 降档重试过一次，仍失败）。
            // 如实说一句，别让用户以为"这图就是这么糊" —— 不静默。
            status = "大图解码失败，当前是低清预览"
        }
    }

    // 当前是哪张要回写路由层：从查看页进编辑器、退出回查看页时必须回到**同一张**（E2-20）。
    // 用 settledPage（与解码同一页）而不是 currentPage：滑到一半又滑回去不算换页。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { onIndexChange(it) }
    }

    // ★ P-3：面板打开时返回键**先关面板**（与编辑器预设面板 E3-1a 同一分层精神）—— 用户按
    // 返回的意图是"退出预设"，不是"退出查看页回相册"。本页此前没有 BackHandler，返回键由
    // MainActivity 的导航层接管；这里注册的是**内层**，Compose 的返回回调后进先出 ⇒ 面板打开
    // 时它优先，关闭后交还导航层（行为与 P-3 之前逐位一致）。
    BackHandler(enabled = showFilmPanel) { showFilmPanel = false }

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

        // ── ② 照片区：HorizontalPager（左右滑动切图）+ 双指缩放 / 放大后单指平移 ──
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewSize = it }, // 平移夹紧要用容器尺寸
            contentAlignment = Alignment.Center,
        ) {
            if (pageCount == 0) {
                Text("照片已失效", color = FilmTheme.CreamDim, fontSize = 13.sp)
            } else {
                HorizontalPager(
                    state = pagerState,
                    // 相邻页也组合出来（只解 360px 预览占位，见下）—— 滑过去时不是空白，
                    // 这是 PC `preloadPreviews` 的等价物（E2-22）
                    beyondViewportPageCount = 1,
                    // 放大态关掉 Pager 的滚动（仲裁表第二行）；手势层自己也会消费放大态的事件，
                    // 这里是第二道保险 —— 两道都失效才会出现"放大后拖动把图切走"。
                    // ★ P-3：预设面板打开时同样关掉（第三道：面板打开时图片区手势全停，
                    // 见下面手势层的早退）—— 否则"选预设时手一滑，图被切走"。
                    userScrollEnabled = !zoomed && !showFilmPanel,
                    modifier = Modifier.fillMaxSize(),
                ) { p ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            // ★ E2-23：**页边界裁剪**，修"隔壁图片入侵视野"。graphicsLayer 的
                            // 缩放/平移是绘制层变换，默认 `clip = false`（Java 层 RenderNode 的
                            // 裁剪也在变换之前生效）⇒ 页内放大的图会一路画到本页之外，落进相邻页
                            // 的可见区域。裁到本页边界后，像素**出不去本页**（与 pageScaleAt 是
                            // 两道独立的闸：一个治"邻页被放大"，一个治"任何越界绘制"）。
                            .clipToBounds()
                            .pointerInput(p, showFilmPanel) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    // ★ P-3：预设面板打开时图片区**一个手势都不处理**（也不消费）
                                    // —— 面板区域的手势归面板（拦截层负责点外收起），图片既不会
                                    // 被捏合缩放也不会被拖动平移。本块返回后 awaitEachGesture 会
                                    // 等所有手指抬起再开下一轮，不会残留半截手势状态。
                                    if (showFilmPanel) return@awaitEachGesture
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.count { it.pressed }
                                        when (
                                            viewerGesture(pressed, zoomed)
                                        ) {
                                            // 双指：缩放（顺带支持双指拖动平移）
                                            ViewerGesture.Zoom -> {
                                                zoom.floatValue =
                                                    clampZoom(zoom.floatValue, event.calculateZoom())
                                                pan.value = clampPan(
                                                    pan.value + event.calculatePan(),
                                                    zoom.floatValue,
                                                    viewSize,
                                                    bitmap,
                                                )
                                                event.changes.forEach {
                                                    if (it.positionChanged()) it.consume()
                                                }
                                            }
                                            // 放大态单指：平移，消费掉 ⇒ Pager 收不到 ⇒ 不切图
                                            ViewerGesture.Pan -> {
                                                pan.value = clampPan(
                                                    pan.value + event.calculatePan(),
                                                    zoom.floatValue,
                                                    viewSize,
                                                    bitmap,
                                                )
                                                event.changes.forEach {
                                                    if (it.positionChanged()) it.consume()
                                                }
                                            }
                                            // Fit 单指：**一个事件都不消费**，横滑留给 Pager 切图
                                            ViewerGesture.Swipe -> Unit
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        // 当前页 = 主解码的位图（Fit / 放大档）；相邻页 = 只解 360px 预览当占位
                        // （不占全尺寸内存）。★ E2-23 更正：**"相邻页必然是 Fit 态所以等于无变换"
                        // 是错的** —— 放大态下 Pager 只是不滚，相邻页照样被组合/绘制，而 zoom/pan
                        // 是整页共享的 state ⇒ 邻页被一起放大后越界画进视口（见 [pageScaleAt]）。
                        val neighbor = if (p == page) {
                            null
                        } else {
                            uris.getOrNull(p)?.let { rememberPreview(previews, it, context) }
                        }
                        val b = if (p == page) bitmap else neighbor
                        when {
                            p == page && error.isNotEmpty() ->
                                Text(error, color = FilmTheme.CreamDim, fontSize = 13.sp)
                            b != null -> Image(
                                bitmap = b.asImageBitmap(),
                                contentDescription = "照片",
                                contentScale = ContentScale.Fit,
                                // graphicsLayer 的 block 在绘制阶段读 state ⇒ 手势只触发重绘，
                                // 不触发重组（每帧拖动都重组整页会很卡）。
                                // ★ E2-23：缩放/平移**只给停稳的那一页**（[pageScaleAt]）——
                                // 相邻页跟着放大就会从视口外越界画进来（别张照片的像素盖在本页上，
                                // 正是用户报的"入侵视野"）。此前这里对每页一视同仁，是这次 bug 的根因。
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val z = pageScaleAt(p == page, zoom.floatValue)
                                        scaleX = z
                                        scaleY = z
                                        translationX = pageShiftAt(p == page, pan.value.x)
                                        translationY = pageShiftAt(p == page, pan.value.y)
                                    },
                            )
                            p == page -> CircularProgressIndicator(color = FilmTheme.Accent)
                            // 相邻页预览还没解出来：保持黑底（滑动动画里几乎看不见），
                            // 不值得为它显示错误文案
                            else -> Unit
                        }
                    }
                }
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
            val enabled = currentUri != null
            BottomAction(Icons.Filled.Edit, "编辑", enabled) { currentUri?.let(onEdit) }
            // 胶片（★ P-3 已开工）：打开预设面板 —— 打开时读一次（与编辑器「预设」面板同一
            // 数据源，ADR-7 裁决：三处入口一套数据）。★ P-2c：走 `BuiltinPresets.all`，
            // 内置三条与用户预设一起列出（直接用 PresetStore.load 会让内置包在界面上消失）。
            BottomAction(Icons.Filled.PhotoFilter, "胶片", enabled) {
                presets = BuiltinPresets.all(context, prefs)
                showFilmPanel = true
            }
            BottomAction(Icons.Filled.Share, "分享", enabled) {
                currentUri?.let { sharePhoto(context, it)?.let { msg -> status = msg } }
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

    // ── ★ P-3「胶片」：选一条预设 ⇒ **直接进编辑器**（带预设 id，不是保存副本）──────
    // 传的是 id 而不是整条预设：预设数据在 prefs 里是唯一真源，路由层只带一个字符串
    //（rememberSaveable 可存），编辑器自己按 id 取 —— 跨进程重建也不会带一份过期副本。
    if (showFilmPanel) {
        FilmPresetSheet(
            presets = presets,
            onPick = { p ->
                showFilmPanel = false
                currentUri?.let { onEditWithPreset(it, p.id) }
            },
            onDismiss = { showFilmPanel = false },
        )
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

/**
 * 平移量的夹紧（Compose 侧包装，核心数学是纯函数 [clampAxis] / [fitDisplaySize]）。
 * 图片尺寸未知 ⇒ 归零（还没解码时拖动没有意义）。
 */
private fun clampPan(pan: Offset, scale: Float, view: IntSize, bitmap: Bitmap?): Offset {
    if (bitmap == null || view.width <= 0 || view.height <= 0) return Offset.Zero
    val (w, h) = fitDisplaySize(bitmap.width, bitmap.height, view.width.toFloat(), view.height.toFloat())
    return Offset(
        clampAxis(pan.x, view.width.toFloat(), w * scale),
        clampAxis(pan.y, view.height.toFloat(), h * scale),
    )
}

/**
 * 相邻页的 360px 预览（E2-22）：命中 [cache] 直接返回，否则后台解一张（解完刷新并回填缓存）。
 *
 * **只在 `p != page` 时调用** —— 当前页由主解码负责，同一张图解两次纯属浪费。失败返回 null，
 * 调用方保持黑底（相邻页不值得显示错误文案）。方向转正沿用 [decodeFit]（8 值含镜像）。
 */
@Composable
private fun rememberPreview(cache: ThumbCache<String, Bitmap>, uri: String, context: Context): Bitmap? {
    var bmp by remember(uri) { mutableStateOf(cache.get(uri)) }
    LaunchedEffect(uri) {
        if (bmp != null) return@LaunchedEffect
        val b = withContext(Dispatchers.IO) {
            decodeFit(context, Uri.parse(uri), PREVIEW_SIDE, PREVIEW_SIDE)
        }
        if (b != null) {
            cache.put(uri, b)
            bmp = b
        }
    }
    return bmp
}

/**
 * ★ P-3「胶片」预设选择面板（查看页底栏第二项）。列出 `BuiltinPresets.all` 的全部预设
 *（用户预设 + 内置三条），点一条 ⇒ [onPick]
 * ⇒ 路由层带着这条预设的 id 进编辑器（**直接看效果**，不是保存副本 —— 用户 2026-10-11 拍板）。
 *
 * 形态与拍照界面 `LutSheet` 同一套视觉语言：`FilmTheme.Panel` 圆角面板贴底 + 标题 + 列表
 *（名称 + 分组）。**不用 M3 ModalBottomSheet**：真机返回要按两次才关（用户 2026-10-03 实测，
 * 见 SettingsScreen 的 SettingsDrawer），本页沿用"自绘覆盖层 + BackHandler"那一套。
 *
 * 手势归属（E2-20 的两条手势不许被面板破坏）：整屏拦截层只吃 **tap**（点面板外收起）；
 * 面板容器的 `clickable`（无涟漪）吃掉面板内点击、不透传给拦截层。**滑动/捏合不在这里处理**
 * —— 图片区的 Pager 与缩放/平移在面板打开时已被显式关掉（见 PhotoViewScreen 的
 * `userScrollEnabled` 与手势层的早退），所以不存在"面板打开时手势漏到图片上"的路径。
 *
 * **不做选中态**：选中即离开本页（[onPick] 后路由层立刻切到编辑器），面板不常驻，
 * 没有"当前选中"可言（做出来只会是一闪而过的假状态，ADR-12）。
 *
 * 空列表给一句实话（ADR-12：不留点不动的按钮、也不假装有内容）。
 */
@Composable
private fun FilmPresetSheet(
    presets: List<EditorPreset>,
    onPick: (EditorPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // 拦截层：点画面任意处收起（与 SettingsDrawer 同一浮层规则）
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(FilmTheme.Panel)
                .navigationBarsPadding()
                // 吃掉面板内的点击，不让它透传到拦截层（否则点面板空白处 = 收起）
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "胶片预设",
                    color = FilmTheme.Cream,
                    fontSize = 20.sp,
                    fontFamily = FilmTheme.Serif,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "收起",
                    color = FilmTheme.CreamDim,
                    fontSize = 12.sp,
                    fontFamily = FilmTheme.Mono,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(6.dp),
                )
            }
            if (presets.isEmpty()) {
                Text(
                    "还没有预设 · 在编辑器里保存",
                    color = FilmTheme.CreamDim,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            } else {
                // 预设上限 100 条（PRESET_MAX）⇒ 必须限高可滚，否则长列表会把图片区挤没
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    presets.forEach { p -> FilmPresetRow(p) { onPick(p) } }
                }
            }
        }
    }
}

/**
 * 面板里的一行：名称（主）+ 分组（次，空分组不占行）+ **字段作用域提示**（P-5，可选第三行）；
 * 点整行进编辑器。
 *
 * 提示只在"这条预设里有参数在编辑器链路不生效"时出现（[presetScopeNotice] 返回 null 就
 * 不占行）—— 本页选预设 = 直接进编辑器，用户看到的第一眼就是编辑器的效果，先在列表里
 * 说清楚"这条有 1 项在这里不会生效"，比让他进编辑器后怀疑是 bug 强。
 */
@Composable
private fun FilmPresetRow(p: EditorPreset, onPick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            p.name,
            color = FilmTheme.Cream,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (p.group.isNotBlank()) {
            Text(
                p.group,
                color = FilmTheme.CreamDim,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // ★ P-5 字段作用域：本页选一条预设 = **直接进编辑器**（场景 = 编辑器链路 `passStyle`）
        // ⇒ 含 [5..10] 肩部/趾部（胶片曲线）/ [37][38] 黑白点 的预设在这里套用**没有视觉效果**
        //（编辑器链路 `kFsStyle` 里没有 filmic，等 U-2）。
        // ⚠ ★ **U-1（2026-10-11）已接线 `[23][24]` 分离色调 + `[25]` 单色** ⇒ 它们两侧都生效、
        // 不再出现在这里的提示里，内置三条也不再命中（本注释此前把这三槽与 `[5..10]` 并列，
        // 已按接线后的实际行为更正；**判据的唯一维护点 = `PresetScope.kt`**，本文件不复述清单）。
        // 全部生效 ⇒ null ⇒ 不显示任何东西（不挂"全部生效"这种每条都有的噪音）。
        presetScopeNotice(p, PresetScene.EDITOR)?.let { notice ->
            Text(
                notice,
                color = FilmTheme.CreamDim,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 底栏一项：图标 + 文字 */
@Composable
private fun RowScope.BottomAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    tint: Color = FilmTheme.Cream,
    onClick: () -> Unit,
) {
    val ink = tint
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

/**
 * MediaStore 元数据 + EXIF（IO 线程，全部 best-effort：单列缺失/单次查询失败只丢那一项）。
 * 路径优先 `_data`（API 29 起 deprecated 但仍是用户能看懂的绝对路径），取不到退回
 * `relative_path` + 文件名。
 *
 * 解码**不在**这里：查看页按需分两步（元数据先到、全分辨率后到），见 PhotoViewScreen 的
 * LaunchedEffect（E2-20）。
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
