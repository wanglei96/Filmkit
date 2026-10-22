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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.filmkit.app.EngineBridge
import com.filmkit.app.camera.BuiltinPresets
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.PresetScene
import com.filmkit.app.camera.presetScopeNotice
import com.filmkit.app.kv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/**
 * ★ PV-1（用户 2026-10-11："相册滑动图片还是会闪一下"）：预览占位档的请求尺寸 = **容器尺寸的
 * 一半**，不再用固定的 `PREVIEW_SIDE = 360`。
 *
 * **为什么 360 会闪**：[decodeFit] 先把请求尺寸按原图比例 Fit（[fitSizeFor]），再交给
 * [sampleSizeFor] 算 `inSampleSize` —— 而采样率**只能取 2 的幂**，所以真正决定清晰度的是请求
 * 尺寸落在哪个"采样台阶"上，不是请求了多少像素。12MP 横图（4000×3000）在 1080×1700 的容器里：
 * 360px 的请求落到 **sample=8 ⇒ 实际只解 500×375**，被 `ContentScale.Fit` 放大到 1080×810 的
 * 显示区是 **2.16×** 的双线性放大（竖图更糟，2.88×）；随后目标档（sample=2，2000×1500，缩到
 * 1080×810）替换上去 ⇒ **一次肉眼可见的清晰度阶跃**。用户看到的"闪一下"就是它 —— 不是空白
 * 帧、不是位置跳变、不是颜色差异（三条排除依据见 `docs/reports/pv1-flicker-REPORT.md` §1）。
 *
 * **为什么是"一半"**：[fitSizeFor] 对容器尺寸是**一次齐次**的（Fit 就是线性缩放），所以
 * "请求容器的一半" ≡ "请求目标档 Fit 尺寸的一半" ⇒ 采样台阶**必然比目标档低一档**（解码像素量
 * 1/4）。同一台 1080p 容器上预览从 sample=8（500×375）提到 sample=4（1000×750），放大倍数
 * 2.16× → **1.08×**，与目标档肉眼无差（逐画幅逐容器的台账在 `PhotoViewPreviewTest` 里跑真实
 * 的 [fitSizeFor] / [sampleSizeFor] 打印）。
 *
 * 代价（真机量级，见报告 §2）：预览解码像素 ×4（≈+15–25ms，只在相邻页预载与首次进入当前页时
 * 付），预览内存 0.75MB → 3MB/张 —— 所以 [PREVIEW_CACHE_BYTES] 必须跟着放大，否则相邻页预载
 * 会静默失效（比原来更糟）。
 *
 * 容器未测量（`viewW/viewH <= 0`）⇒ 夹到 1，调用侧另有 `<= 0` 的早退，不会拿 0 去解码。
 */
internal fun previewRequestFor(viewW: Int, viewH: Int): Pair<Int, Int> =
    (viewW / 2).coerceAtLeast(1) to (viewH / 2).coerceAtLeast(1)

/**
 * 相邻页预览缓存：字节上限 **12MB**、张数上限 **4**（★ PV-2：预载半径从"Pager 组合到谁算谁"
 * 改成**主动的 ±[PRELOAD_RADIUS]** ⇒ 需要同时装下 4 张邻页）。
 *
 * PV-1 的口径是 3 张（当前页 + 左右邻页），但那是**被动**预载时代的账：邻页什么时候被组合、
 * 什么时候解完，全看 Pager 的脸色，赶不上切页那一刻。PV-2 改成主动预载后要装的是
 * `page±1`、`page±2` 四张（当前页由主解码负责，不进这个缓存）。
 *
 * 单张字节（`PhotoViewPreviewTest` 用真实的 [fitSizeFor] / [sampleSizeFor] 跑）：
 * 1080p/1440p/1644p 横竖的 12MP 图预览档都是 **1000×750 ≈ 3MB**（采样台阶 sample=4），
 * 4 张 = 12MB —— 正好装满上限。唯一的例外是 **1644p 竖图**（预览档落在 sample=2 ⇒
 * 1500×2000 ≈ 12MB/张）⇒ 只装得下 1 张，那种设备上预载会退化（降级路径兜底，不会出错，
 * 只是回到 PV-1 的命中率）。宁可少缓存也不让预载把内存吃掉。
 */
internal const val PREVIEW_CACHE_COUNT = 4
internal const val PREVIEW_CACHE_BYTES = 12L * 1024 * 1024

/**
 * ★ PV-2：主动预载半径 —— **照抄 PC**（`ImageViewerFragment.preloadPreviews(centerPos)` 是
 * `center ± 2`，在进入查看页时与每次翻页后显式调用）。PC 的保证是"任何时刻都有一张不透明的图
 * 垫底"（`ImageAdapter.java:79` 的原注释：*small preview + native dimensions per position –
 * shown immediately under tiles so no black flash*），而不是"等 Pager 顺手组合邻页"。
 */
internal const val PRELOAD_RADIUS = 2

/**
 * ★ PV-2：转圈的**显示延迟**（ms）。未命中预载时屏上先保持纯黑底，超过这个时间才画
 * `CircularProgressIndicator`。
 *
 * 用户 2026-10-11 的实测线索是"**左右滑动一定会显示一下转圈，所以观感上就是闪一下**" ——
 * 转圈出现再消失本身就是一次闪（它是个高对比的圆环，画在纯黑底上）。而绝大多数"未命中"
 * 只持续一张预览档的解码时间（15–30ms ≈ 1–2 帧），那种情况下**黑底比转圈更不刺眼**
 * （黑底与页面背景同色，看不出变化）。真的等久了（首次点开一张没预载的图）仍然给反馈，
 * 不静默（ADR-12）。
 */
private const val SPINNER_DELAY_MS = 150L

/**
 * ★ PV-1 交叉淡入时长（ms）：预览档 → 目标档的替换**不是瞬切**，上层（目标档）alpha 在这段
 * 时间里从 0 走到 1，下层（预览）保持不动 ⇒ 残余的清晰度差被时间抹平，不再是一次"跳变"。
 * 150–250ms 是"看得出平滑、又不觉得慢"的窗口（与 CameraScreen 的快门反馈 190ms 同量级）。
 */
private const val PREVIEW_FADE_MS = 180

/**
 * ★ PV-2：上屏位图的**档位**标记（`shownTier` 的取值，越大越清晰）。
 *
 * 用命名常量而不是裸数字：它们是状态机的状态（"此刻屏上这张是什么档"），而
 * `shownTier >= target` 这种比较里的裸 `0` 读不出方向（0 是**最低**档，不是"没有"）。
 */
internal const val TIER_PREVIEW = 0
internal const val TIER_FIT = 1
internal const val TIER_HIRES = 2

/**
 * ★ PV-2：一帧位图的完整描述（uri + 档位 + 像素）。纯泛型，不碰 `android.graphics.Bitmap`
 * ⇒ 能在 JVM 单测里真跑。
 */
internal data class ViewerFrame<B>(val uri: String?, val tier: Int, val bitmap: B?)

/**
 * ★ PV-2：**换页时的原子状态转移**（纯函数，无 suspend ⇒ 天然原子）。
 *
 * 这是 `PhotoViewScreen` 解码 effect 里 `if (shownUri != u)` 那一段的唯一真相，判据只有两条：
 *
 * 1. **位图只可能来自新页**（`cacheHit`）—— 命中就是新页的预览档，未命中就是 `null`。
 *    **绝不沿用上一页的像素**：新页画上一张的图是错图，比黑底更糟。
 * 2. **uri / 档位 / 位图三者一次给全** —— 不允许"先清空位图、再由另一个协程填"的两步走。
 *    那两步之间的帧就是用户 2026-10-11 实测到的转圈（"**左右滑动一定会显示一下转圈**"）：
 *    PV-1 正是把这两步分在了两个 `LaunchedEffect` 里。
 *
 * @param cacheHit 主动预载的结果（`previews.get(u)`）；null = 未命中（走现解预览档的降级路径）
 */
internal fun <B> frameAfterPageChange(uri: String, cacheHit: B?): ViewerFrame<B> =
    ViewerFrame(uri = uri, tier = TIER_PREVIEW, bitmap = cacheHit)

/**
 * ★ PV-2：主动预载要覆盖的**相对页偏移**（纯函数），近的优先 —— 用户下一次滑动最可能落在 ±1。
 * 不含 0：当前页由主解码负责，预载它纯属白解一张。
 */
internal fun preloadOffsets(radius: Int): List<Int> =
    if (radius <= 0) emptyList() else (1..radius).flatMap { listOf(-it, it) }

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
 * ★ PV-4：**以两指中心为锚点**的缩放补偿（用户 2026-10-12 真机："相册缩放要从两指中心缩，
 * 现在都是从画面中心缩放，很奇怪"）。
 *
 * 根因在绘制层：`graphicsLayer { scaleX = scaleY = z; translationX/Y = pan }` 的 pivot 是默认的
 * `TransformOrigin.Center` ⇒ 只改 `z` 就是**绕画面中心**缩放。要让两指之间的内容点不动，必须
 * 同时改 [pan]。居中坐标系下内容点 `x` 的屏幕位置是 `ctr + z·(x − ctr) + pan`（[ctr] = 容器
 * 中心 = pivot），要求同一个 `x` 在缩放前后都落在两指中心 [centroid] 上：
 * ```
 * c = ctr + z₁·(x − ctr) + pan₁      （缩放前）
 * c = ctr + z₂·(x − ctr) + pan₂      （缩放后）
 * ⇒ pan₂ = pan₁ + (c − ctr − pan₁)·(1 − z₂/z₁)
 * ```
 * 这就是本函数，纯函数（不动点性质由 JVM 单测钉死）。[z1] == [z2]（已顶到 [MAX_ZOOM]，或
 * `calculateZoom()` 给了非法 factor）⇒ 补偿项为 0 ⇒ 只保留双指整体位移，不漂移；非有限输入
 * （`calculateCentroid()` 在指针被消费 / 无按下指针时返回 `Offset.Unspecified` = NaN）⇒
 * **原样返回**（与 [clampZoom] 的守卫同精神：宁可不动，也不能把 NaN 传给 `graphicsLayer` —— 那
 * 会让整张图消失）。
 *
 * **[centroid] 必须取 current 帧**（`calculateCentroid(useCurrent = true)`）：`calculateZoom()`
 * 与 `calculatePan()` 都是"current 相对 previous"的增量，本帧的几何状态就是 current。用
 * previous 中心会让锚点比手指慢一帧（两指整体移动时缩放中心滞后于指间内容）；而 current 中心
 * **加上先叠加的 `calculatePan()`** 恰好等价于"两指夹住的那个点跟着两指中心走" —— 数学上等价于
 * "previous 中心 + 位移补偿"（推导见报告 §2），所以这不是近似，是同一件事。
 */
internal fun zoomAboutCentroid(
    pan: Offset,
    centroid: Offset,
    center: Offset,
    z1: Float,
    z2: Float,
): Offset {
    if (!pan.finite() || !centroid.finite() || !center.finite()) return pan
    if (!z1.isFinite() || !z2.isFinite() || z1 <= 0f) return pan
    return pan + (centroid - center - pan) * (1f - z2 / z1)
}

/** 逐分量有限性（本文件用的 Compose 版本没有 `Offset.isFinite`） */
private fun Offset.finite(): Boolean = x.isFinite() && y.isFinite()

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
 * ★ PV-2（用户 2026-10-11 复测澄清："**闪一下不是糊到清晰，是屏幕闪烁**"）：单页的绘制 alpha。
 *
 * **只有"正在淡入的停稳页"才受 [fade] 影响**，其余一律 1（全不透明）—— 与 [pageScaleAt] /
 * [pageShiftAt] **同款判据**：`fade` / `zoom` / `pan` 都是**整页共享**的 state，谁跟着它走谁
 * 就会在别人的动画里被改掉。E2-23 给 `zoom` / `pan` 加这道闸时，alpha 还不存在（PV-1 才引入），
 * 于是漏了同一个判据，代价是：
 *
 * - **相邻页也一起半透明**。而相邻页**没有下层兜底**（`under` 只画在停稳页）⇒ 半透明的图
 *   直接压在 `Color.Black` 上 ⇒ **整片图片区变暗**。用户滑动时邻页正从边缘滑进视口，
 *   这个变暗就落在视口正中 —— 正是他说的"屏幕闪烁"。
 * - **停稳页在切页那一帧可能拿到中间 alpha**。PV-1 靠 `fade.snapTo(1f)` 复位，而它是
 *   **挂起**函数：实测有动画在跑时要 24ms（≈1.5 帧）才生效，见 `Pv2FlickerTest` 的
 *   `snapToOnRunningAnimationIsNotInstant`。那 1–2 帧里整页都是半透明的。
 *
 * 所以判据不能是 `fade < 1`（把"这一帧透不透明"交给一个挂起函数的结果），而是 [fading] ——
 * 由调用方传 `under != null`，而 `under` 在切页 effect 里是**同步**清掉的 ⇒ **切页那一帧的
 * alpha 必然是 1**，与 [fade] 当时停在哪个值、有没有动画正在垂死挣扎**都无关**。
 *
 * [fading] 为真时仍然返回 [fade]：淡入本身（预览档 → 目标档的清晰度过渡）保留，它只在
 * 用户停在这一页时可见 —— 手指一动就会被 `isScrollInProgress` 那条 effect 结束掉（见调用侧）。
 */
internal fun pageAlphaAt(isSettledPage: Boolean, fading: Boolean, fade: Float): Float =
    if (isSettledPage && fading) fade else 1f

/**
 * ★ PV-3：**这一页有没有资格画 [bitmap]** —— 绘制期的**归属校验**（纯函数，JVM 单测钉死）。
 *
 * [bitmap] 是**整页共享**的 state（所有页读同一个），[shownUri] 是它的**归属**（"此刻这张位图
 * 是哪张照片的像素"）。只有"停稳页 + 归属就是本页"才成立。
 *
 * **为什么必须有这道闸（PV-2 的"原子替换"消不掉它）**：[bitmap] 由 key 含 `page` 的
 * `LaunchedEffect` 写，而 `page`（= `settledPage`）由 Pager 的滚动驱动 —— 两个**独立来源**。
 * Effect 的重启只有在**重组观察到新 key** 之后才可能发生，而 `Image` 的参数在**组合期**取值
 * ⇒ **必然存在至少一次组合："读到了新页、而 [bitmap] 还是上一页的像素"**。那一次组合画出来的
 * 就是用户真机看到的"左右滑已经显示下一张图了，上一张图还是会闪现一下"。把 effect 写得多原子
 * 都消不掉这个窗口（PV-2 的原子替换只保证了它不画 null ⇒ 转圈没了，留下的是旧图）。
 *
 * 判据只有一条，三个调用点共用（位图 / 下层 `under` / 淡入 alpha）：**"是不是本页的像素"
 * 决定画不画**，而不是"p 是不是停稳页"。与 [pageScaleAt] / [pageShiftAt] 是同一道闸的精神
 * （那边管的是"放大/平移不能漏给邻页"，这边管的是"像素不能漏给邻页"）。
 */
internal fun bitmapBelongsToPage(
    isSettledPage: Boolean,
    pageUri: String?,
    shownUri: String?,
): Boolean = isSettledPage && pageUri != null && pageUri == shownUri

/**
 * ★ PV-3：**这一页这一帧到底画哪张位图**（纯函数）。归属相符 ⇒ [bitmap]；否则 ⇒ [cached]
 * （这一页**自己的**预载档，通常是主动预载 `page±[PRELOAD_RADIUS]` 的结果；没有就是 null
 * ⇒ 纯黑底，配合 [SPINNER_DELAY_MS] 不画转圈）。
 *
 * 关键：切页的那一帧（`page` 已经变了、[bitmap] 还是上一页的）**画的是新页的预载档**，
 * 而不是旧页的像素，也不是黑底 —— 下一帧解码 effect 把 [bitmap] 也换成**同一个对象**
 * （`previews.get(u)`），前后两帧逐像素相同 ⇒ 用户看到的是"新图（预览档）直接接上新图"。
 */
internal fun <B> visibleBitmapFor(
    isSettledPage: Boolean,
    pageUri: String?,
    shownUri: String?,
    bitmap: B?,
    cached: B?,
): B? = if (bitmapBelongsToPage(isSettledPage, pageUri, shownUri)) bitmap else cached

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
 * ① 按**显示尺寸**解码（[decodeFit]，Fit 档 ≈3MP ≈12MB）；② 预览档先上屏（[previewRequestFor]）；
 * ③ 相邻页只解预览（`beyondViewportPageCount = 1`）；④ 放大到 [ZOOM_HIRES] 补解一档。
 *
 * ★ **PV-1 修**（用户 2026-10-11 报："相册滑动图片还是会闪一下"）：E2-22 的预览档是固定
 * 360px ⇒ 实际只解 500×375，被 `ContentScale.Fit` 放大 2.16–2.88× 才铺满显示区，与目标档之间
 * 有一次**清晰度阶跃** —— 那就是用户说的"闪"。两道修：
 * ① 预览请求改为**容器的一半**（[previewRequestFor]）⇒ 采样台阶比目标档低一档，放大倍数降到
 *    1.08–1.44×（剩余差由 ② 兜住）；
 * ② 换档时**交叉淡入** [PREVIEW_FADE_MS]ms —— 下层预览保持不动，上层目标档 alpha 0→1。
 * E2-22 的两段式节奏、E2-20 的手势仲裁、E2-23 的缩放边界（[pageScaleAt] / [pageShiftAt] /
 * `clipToBounds`）**一行未改**。
 *
 * ★ **PV-2 修**（用户 2026-10-11 复测，两条澄清把根因锁死：
 * ① "**闪一下不是糊到清晰，是屏幕闪烁**" ⇒ PV-1 的"清晰度阶跃"根因判断是错的；
 * ② "**左右滑动一定会显示一下转圈，所以观感上就是闪一下**" ⇒ 那条闪就是
 * `CircularProgressIndicator`）。三条修，对应三个独立缺陷：
 *
 * ① **转圈（主因，用户点名的现象）**：PV-1 把"丢旧像素"（`bitmap = null`）放在切页 effect、
 *    把"上新像素"（`bitmap = previews.get(u)`）放在**另一个**解码 effect —— 两个独立协程之间
 *    只要有任何调度窗口，就会渲染出"新页已选中、位图为 null"的帧，而那一帧画的正是转圈。
 *    修法：`bitmap` 的**所有写入收敛到解码 effect 的一段同步代码**里（`if (shownUri != u)` 块，
 *    其中没有任何 suspend 调用）⇒ 判据是"任何时刻都不存在新页已选中但位图为 null 的可见帧"；
 *    再加 [SPINNER_DELAY_MS] 的显示延迟兜底（短暂未命中时保持纯黑底，不画转圈）。
 * ② **预载时机**：PV-1 依赖"Pager 组合邻页时顺手解一张"，时机不由我们决定，赶不上切页那一刻。
 *    改成**主动预载 `page ± [PRELOAD_RADIUS]`**（PC `preloadPreviews(centerPos)` 的口径：
 *    进入查看页与每次翻页后显式调度）⇒ 切页时 `previews.get(u)` 通常命中 ⇒ 同步上屏、零等待。
 * ③ **alpha 缺闸**：PV-1 的 [fade] 漏了 E2-23 给 zoom/pan 加的那道闸 —— `viewerLayer` 的调用点
 *    对**所有页**读 `fade.value` ⇒ 淡入的 180ms 里相邻页也半透明，而相邻页没有下层兜底
 *    （`under` 只画停稳页）⇒ 半透明压在黑底上 ⇒ 变暗；且停稳页的复位靠 `fade.snapTo(1f)`，
 *    它是**挂起**函数（实测 24ms ≈ 1.5 帧）⇒ 切页那一帧仍可能用中间 alpha 绘制。
 *    修法：alpha 收进同一道闸（[pageAlphaAt]），复位改成"同步清 `under`"的状态而非挂起函数的结果；
 *    手指一动就结束淡入（`isScrollInProgress` 那条 effect）。
 * 淡入本身**保留**（只在静止时可见，仍有抹平清晰度过渡的价值）；手势 / 缩放边界 / 页裁剪
 * （[viewerGesture] / [clampZoom] / [clampPan] / [pageScaleAt] / [pageShiftAt] / `clipToBounds` /
 * `userScrollEnabled`）**一行未改**。
 *
 * ★ **PV-3 修**（用户 2026-10-11 真机复测 v0.11.19："**没有转圈了，但是左右滑已经显示下一张图了，
 * 上一张图还是会闪现一下**"）：转圈（PV-2 ①）确实没了，但**同一处时序缺口换了个面** —— 过去是
 * "新页位置上画 null ⇒ 转圈"，现在是"新页位置上画**上一张的像素** ⇒ 旧图闪现"。PV-2 的
 * "原子替换"只保证了**不会画 null**，没有（也**不可能**）保证**不会画旧值**。
 *
 * 根因：[bitmap] 是**整页共享**的 state，由 key 含 `page` 的 `LaunchedEffect` 写；而 `page`
 * （= `settledPage`）由 Pager 的滚动驱动 —— 它在**滚动结束那一帧**才从旧页跳到新页
 *（`isScrollInProgress` 期间冻结在上一次滚动的页）。Effect 的重启必然晚于"重组第一次读到新
 * `page`"，而 `Image` 的参数在**组合期**取值 ⇒ **必然存在至少一帧："新页已选中、[bitmap]
 * 还是上一页的像素"** ⇒ 那一帧画在视口正中的就是上一张的图（Pager 的位移已经把新页摆好了，
 * 所以用户看到的是"新页已经显示出来，旧图又闪一下"）。
 *
 * 修法：绘制期**归属校验**（[bitmapBelongsToPage] / [visibleBitmapFor]）—— 停稳页**只画属于
 * 这一页的位图**（判据 = [shownUri]），归属不符时退回该页**自己的预载档**（主动预载命中 ⇒
 * 那一帧直接是新图；未命中 ⇒ 黑底，仍不画旧图）。`under` 层与淡入 alpha 共用同一道闸
 * （`pageAlphaAt(owns, …)`）；缩放/平移的页判据（[pageScaleAt] / [pageShiftAt] 的调用参数）
 * **逐位未改**。
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
 * @param onOpenGallery 顶栏「相册」入口；**非空才显示**。只在"从相机页直接看大图"时传
 *   （v0.12.35 用户反馈：那样进来后返回是回相机，就再也没有去相册的路了）。
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
    onOpenGallery: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }
    // ── HDR 查看开关（v0.11.60：**按单图**记忆）──────────────────────────────
    // v0.11.50 曾把它做成全局 prefs（`view_hdr`），结果"关掉一张 = 全部关掉"——
    // 用户 2026-10-20 明确纠正：这个开关属于**单张图片**，开关与否由用户对这张图决定。
    // 现在每张图各自一个 key（`view_hdr:<uri>`），默认开；设置页的「HDR 成片」只决定
    // **新拍**是否附带 gainmap，与查看已有图片完全无关（两件事不要互相污染）。
    // HDR **编辑**面板（单张图，用户 2026-10-20 定案：开关属于单张图片，不是全局）
    var showHdrPanel by remember { mutableStateOf(false) }
    var hdrExportMsg by remember { mutableStateOf<String?>(null) }
    var hdrExporting by remember { mutableStateOf(false) }
    val pageCount = uris.size
    val pagerState = rememberPagerState(initialPage = clampIndex(index, pageCount)) { pageCount }
    val hdrUri = uris.getOrNull(pagerState.settledPage)
    val hdrKey = if (hdrUri != null) "view_hdr_$hdrUri" else "view_hdr"
    var hdrOn by rememberSaveable(hdrKey) { mutableStateOf(prefs.getBoolean(hdrKey, true)) }

    // HDR 开关 → 窗口色彩模式（Android 14+ 且屏幕支持 HDR 才生效）。
    // 这是 HDR 显示的**前提**：窗口不在 HDR 模式时，即使 Bitmap 带 gainmap，
    // 系统也只会按 SDR 合成（用户实测"app相册还是sdr显示"就是这个原因之一）。
    androidx.compose.runtime.LaunchedEffect(hdrOn) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // ⚠ 必须**解包 ContextWrapper**：Compose 的 LocalContext 常常不是 Activity
            // 本身（ContextThemeWrapper / ContextWrapper 层层包着），`as? Activity` 会
            // 直接拿到 null ⇒ 整段跳过 ⇒ 窗口永远停在 sRGB（真机"app 相册看不了 HDR"的
            // 候选根因之一）。
            val act = context.findActivity() ?: return@LaunchedEffect
            runCatching {
                // ⚠ **不要用 `configuration.isScreenHdr` 当条件**（v0.11.56 真机实测）：
                // 它在用户这台设备上返回 **false**，而系统相册明明能显示 HDR ——
                // 用它是硬条件 ⇒ 窗口永远停在 COLOR_MODE_DEFAULT（日志 `colorMode:0`），
                // 整条 HDR 显示链被静默挡死。
                // 官方文档/示例只按 **`bitmap.hasGainmap()`** 决定（见
                // developer.android.com/media/grow/ultra-hdr/display）：设备不支持 HDR 时
                // 系统自己会降级，不需要调用方预先判断。
                act.window.colorMode = if (hdrOn) {
                    android.content.pm.ActivityInfo.COLOR_MODE_HDR
                } else {
                    android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
                }
            }
        }
    }

    // **只有"停稳"的那一页解大图**（settledPage：滚动动画结束后才更新）。E2-22 起按显示尺寸解
    // （12MP 只解 ≈3MP ≈12MB），但滑动过程中 Pager 仍会组合相邻页，按 currentPage 解码就会同时压两三张。
    val page = pagerState.settledPage
    // remember 必须**无条件**调用（放在 getOrNull 的 let 里会随 null 与否改变 slot 结构 ⇒
    // 组合期报错），所以先取值再 remember 它
    val currentKey = uris.getOrNull(page)
    val currentUri = remember(currentKey) { currentKey?.let { Uri.parse(it) } }

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    // ★ PV-1：被 [bitmap] 盖住的**下层位图**（只有"同页升档"那一下才有：预览档 → 目标档）。
    // 交叉淡入期间两层同时在屏上，[fade] 结束后立刻清掉（只多占 180ms 的一张预览内存）。
    // 切页时**必须**清空 —— 上一张的像素哪怕只当淡出层也不能留在新页上。
    //
    // ★ PV-2：它同时是**"是否正在淡入"的唯一判据**（[pageAlphaAt] 的 `fading` 参数）。
    // 这样"alpha 该不该复位"就不再依赖 [fade] 的值（`Animatable` 的复位是挂起操作），
    // 而是依赖一次**同步赋值** —— 切页 effect 里 `under = null` 一执行，alpha 立刻是 1。
    // 手指开始滑动时也会被清掉（见 `isScrollInProgress` 那条 effect）。
    var under by remember { mutableStateOf<Bitmap?>(null) }
    // ★ PV-1：上层（[bitmap]）的淡入进度，1 = 全显。只在 `graphicsLayer` 的**绘制 block** 里读
    // ⇒ 动画期间零重组（与 zoom/pan 同一套做法）。
    // ★ PV-2 更正：**不再靠"切页时把它复位"来保证不透明**。`Animatable.snapTo` 是挂起函数，
    // 实测有动画在跑时要 24ms ≈ 1.5 帧才生效（`Pv2FlickerTest` 钉住了这个事实）—— 靠它复位
    // 等于把"切页那一帧透不透明"交给一个异步操作。现在由 [pageAlphaAt] 的 `fading`（= `under != null`）
    // 门控：`under == null` 时 alpha 恒 1，这个值停在哪儿都无所谓。
    val fade = remember { Animatable(1f) }
    // bitmap 对应的**是哪张图、哪个档位**（0 = 预览档，1 = Fit，2 = 放大档）。
    // 用来区分"切页要重解"与"同页升档只需补解更高档"：缩回 Fit 时 shownTier=2 ≥ 目标 1 ⇒ 不重解
    // （高清留着更好，降回去反而更糊）。
    var shownUri by remember { mutableStateOf<String?>(null) }
    var shownTier by remember { mutableStateOf(TIER_PREVIEW) }
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
    // 相邻页预载的预览档（★ PV-2：主动预载 page±[PRELOAD_RADIUS]，见下面的预载 effect）：
    // 滑过去的那一瞬间就有内容。只丢引用不 recycle（可能正被画在屏幕上，见 ThumbCache.clear 的 KDoc）。
    val previews = remember {
        ThumbCache<String, Bitmap>(PREVIEW_CACHE_BYTES, PREVIEW_CACHE_COUNT) { it.byteCount }
    }
    // ★ PV-2：预载完成的信号。`ThumbCache` 是普通对象（不是 snapshot state）—— 主动预载把位图
    // 填进去之后，**已经组合好的邻页不会自己重组**，也就取不到刚填进去的那张。这个计数器把
    // "缓存有更新"告诉它们（`rememberNeighborPreview` 用它当 remember key）。每次预载完成 +1，
    // 频率是"每张照片一次"，不是每帧。
    var previewsVersion by remember { mutableIntStateOf(0) }
    // ★ PV-2：转圈是否该显示（见 [SPINNER_DELAY_MS]）。未命中预载时先黑着，等久了才给反馈。
    var showSpinner by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { previews.clear() }
    }

    val key = currentUri?.toString()
    val favorite = key != null && key in favorites

    // 系统删除确认弹窗（API 30+）的返回。
    // ★ v0.12.35：`RESULT_OK` 只代表"用户点了同意"，**不代表 MediaProvider 真的执行了**
    // （真机反馈"删除后照片还在"）。所以必须用一次单条查询核实；只有确认查不到才 onDeleted。
    val deleteScope = androidx.compose.runtime.rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<Uri?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val target = deleteTarget
            if (target == null) {
                onDeleted()
            } else {
                deleteScope.launch {
                    val gone = withContext(Dispatchers.IO) { !mediaStillExists(context, target) }
                    EngineBridge.nativeAppendLog(
                        if (gone) EngineBridge.LEVEL_INFO else EngineBridge.LEVEL_WARN,
                        "camera", "photo.delete_result",
                        kv("gone" to gone, "uri" to target.toString()),
                    )
                    if (gone) {
                        dropLastPhotoUri(context, listOf(target.toString()))
                        onDeleted()
                    } else {
                        status = "系统没有删除这张照片（可重试）"
                    }
                }
            }
        } else {
            status = "已取消删除"
        }
    }

    /**
     * 删除（安全红线：绝不能误删）。
     *
     * v0.12.36：回到 [MediaStore.createDeleteRequest]，**不承诺回收站** —— 曾试
     * `createTrashRequest` 想让照片进系统「最近删除」，vivo OriginOS 实测被直接落成真删，
     * 用户拍板"那就不做恢复、也不自建回收站"。所以文案统一为"删除后无法恢复"。
     *
     * **两道闸**：① 本对话框（二次确认，两档都有）；② API 30+ 再叠一层系统确认弹窗。
     * 低版本（26-29）没有系统弹窗，只能 `contentResolver.delete` —— 此时失败必须如实报，
     * 不许静默（删没删掉用户必须知道）。删完都会**核实**（RESULT_OK ≠ 已删）。
     */
    fun deletePhoto() {
        val u = currentUri ?: return
        deleteTarget = u
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "photo.delete_request",
            kv("count" to 1, "api" to Build.VERSION.SDK_INT),
        )
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

    // ── 切页：**只做文字**（元数据 + 提示复位）────────────────────────────────
    // ★ PV-2：这个 effect **一个字节的位图状态都不碰**。PV-1 把"丢旧像素"（`bitmap = null`）
    // 放在这里、把"上新像素"（`bitmap = previews.get(u)`）放在下面的解码 effect —— 两个
    // `LaunchedEffect` 是两个独立协程，它们之间只要有任何调度窗口，就会渲染出
    // "新页已选中、位图为 null"的帧，而那一帧屏上画的正是 `CircularProgressIndicator`。
    // 用户 2026-10-11 实测："**左右滑动一定会显示一下转圈，所以观感上就是闪一下**" ——
    // "一定"说明这不是偶发的竞态，而是结构性的：`bitmap` 被两个协程分别写。
    // 现在它只在解码 effect 的那一段同步代码里被写（见下），中间没有挂起点。
    LaunchedEffect(page, uris) {
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

    // ── ★ PV-2：主动预载 page±[PRELOAD_RADIUS]（PC `preloadPreviews(centerPos)` 的等价物）──
    // 用户点名"相册你可以参考 PC 的实现"：PC 的保证是**任何时刻都有一张不透明的图垫底**
    //（`ImageAdapter.java:79`：*small preview + native dimensions per position – shown
    // immediately under tiles so no black flash*），手段是**主动**预载 ——
    // `ImageViewerFragment` 在进入查看页时与每次翻页后显式调用 `preloadPreviews(center)`，
    // 半径 2。PV-1 依赖的是"Pager 把邻页组合出来时顺手解一张"：**时机不由我们决定**
    //（`beyondViewportPageCount = 1` 只管组合，不管什么时候解完），赶不上切页那一刻 ⇒
    // `previews.get(u)` 未命中 ⇒ 那一帧就是转圈。
    //
    // **顺序预载，不并发**：① 不与当前页的目标档解码抢 CPU；② 不让 4 张预览同时解码
    //（瞬时内存 ×4）。近的优先（±1 在 ±2 之前）—— 用户下一次滑动最可能落在 ±1。
    // 已经缓存过的直接跳过（`previews.get(u) != null`）⇒ 反复翻页不重复解码。
    LaunchedEffect(page, uris, viewSize) {
        val vw = viewSize.width
        val vh = viewSize.height
        // 容器未测量 ⇒ 算不出预览请求。viewSize 是 key，测量完会重启。
        if (vw <= 0 || vh <= 0) return@LaunchedEffect
        val (pw, ph) = previewRequestFor(vw, vh)
        for (d in preloadOffsets(PRELOAD_RADIUS)) {
            val p = page + d
            val u = uris.getOrNull(p) ?: continue
            if (previews.get(u) != null) continue
            val b = withContext(Dispatchers.IO) { decodeFit(context, Uri.parse(u), pw, ph) }
            if (b != null) {
                previews.put(u, b)
                previewsVersion++ // 告诉已经组合好的邻页：缓存里有新东西了
            }
        }
    }

    // ── ★ PV-2：手指一动就结束淡入（用户 2026-10-11："闪一下不是糊到清晰，是屏幕闪烁"）──
    // 交叉淡入只在"用户停在这一页看"时才有价值（抹平预览档 → 目标档的清晰度过渡）。一旦开始
    // 滑动，它的唯一效果是让**停稳页**在整段滑动里半透明（露黑底）—— 淡入 180ms 与下一次
    // 滑动重叠时必然出现，正是用户报的"屏幕闪烁"。
    // 清掉下层 ⇒ [pageAlphaAt] 的判据立刻失效 ⇒ alpha 回到 1；正在跑的 `animateTo` 继续写
    // [fade] 也无所谓（判据已经不看它了），它跑完把 `under` 再置一次 null 是幂等的。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }
            .collect { scrolling -> if (scrolling) under = null }
    }

    // ── ★ PV-2：转圈的显示延迟（见 [SPINNER_DELAY_MS]）──────────────────────────
    // 未命中预载时屏上先保持纯黑底；真的等久了才画转圈。`bitmap` 是 key ⇒ 一有内容就取消
    // 这个协程并把转圈收掉（不会出现"图已经出来了转圈还挂着"）。
    LaunchedEffect(page, bitmap) {
        showSpinner = false
        if (bitmap != null) return@LaunchedEffect
        delay(SPINNER_DELAY_MS)
        if (bitmap == null) showSpinner = true
    }

    // ── 解码（E2-22 两段式 + 两档；★ PV-1 改预览档 + 交叉淡入；★ PV-2 切页原子替换）────
    // ① 预览档先上屏（命中主动预载就零成本）→ ② 按显示尺寸解目标档（Fit / viewSize×2）。
    // 目标档解码期间**旧位图一直占位**，解完才替换（★ PV-1：替换走 [fade]，不再是瞬切）。
    //
    // ★ PV-2：`bitmap` / `shownUri` / `shownTier` **只在这个 effect 里被写**，而且"换页复位 +
    // 丢旧像素 + 上新像素"是**同一段没有挂起点的同步代码**（见下面的 `if (shownUri != u)` 块）。
    // 判据：**任何时刻都不存在"新页已选中但位图为 null"的可见帧** —— 命中预载就同步换上
    // 新页的预览档，未命中就同步置 null（配合 [SPINNER_DELAY_MS]，那一帧是纯黑底而不是转圈）。
    LaunchedEffect(page, uris, hiRes, viewSize, hdrOn) {   // hdrOn：切换后必须重解（gainmap 在解码时决定）
        val u = uris.getOrNull(page) ?: return@LaunchedEffect
        val vw = viewSize.width
        val vh = viewSize.height
        // 容器还没测量（首帧 onSizeChanged 之前）⇒ 算不出 Fit 尺寸。viewSize 是 key，测量完会重启；
        // 这里直接返回，好过拿 0 去解码（那会退化成全分辨率 48MB）。
        if (vw <= 0 || vh <= 0) return@LaunchedEffect
        val target = if (hiRes) TIER_HIRES else TIER_FIT

        val parsed = Uri.parse(u)
        if (shownUri != u) {
            // ★ PV-2 切页的**原子替换**（这一段里没有任何 suspend 调用 ⇒ 不可能渲染出中间态）：
            //   ① 复位缩放/平移（新图从 Fit 开始）—— 与位图替换同一帧生效；
            //   ② `under = null`（alpha 判据立刻失效 ⇒ 这一帧必然不透明，见 [pageAlphaAt]）；
            //   ③ [frameAfterPageChange]：uri / 档位 / 位图**一次给全**。命中主动预载 ⇒ 新页的
            //      预览档立刻上屏（零解码、零等待）；未命中 ⇒ null（纯黑底，配合
            //      [SPINNER_DELAY_MS] 不画转圈），由下面**同一个协程**里补一张。
            // 这里**不能**沿用 PV-1 的"先把 bitmap 置 null、等另一个 effect 来填"：那正是转圈的
            // 来源。也不能"未命中时保留上一张"：那会让新页显示**上一张的像素**（错图比黑底更糟）。
            // 判据本身在 [frameAfterPageChange] 里（纯函数，JVM 单测钉死）。
            zoom.floatValue = MIN_ZOOM
            pan.value = Offset.Zero
            under = null
            val frame = frameAfterPageChange(u, previews.get(u))
            bitmap = frame.bitmap
            shownUri = frame.uri
            shownTier = frame.tier
        }
        // 已经有"不差于目标档"的位图（同页缩回 Fit / 无关重组）⇒ 什么都不做。
        // （走到这里时 `shownUri == u` 恒成立：换页分支刚设过，没进分支说明本来就相等。）
        if (shownTier >= target && bitmap != null) return@LaunchedEffect

        if (bitmap == null) {
            // 未命中预载（首次点开一张 / 快速连滑 / 容器尺寸刚变）⇒ 现解一张预览档顶上。
            // ★ PV-2：它**不写缓存** —— 缓存的语义是"page±[PRELOAD_RADIUS] 的预载档"，
            // 把现解的这张塞进去会把还没滑到的邻页挤掉（12MB 只装 4 张）。
            val preview = withContext(Dispatchers.IO) {
                val (pw, ph) = previewRequestFor(vw, vh)
                decodeFit(context, parsed, pw, ph)
            }
            if (preview != null) bitmap = preview
        }
        // 目标档：Fit（12MP ⇒ 2000×1500 ≈12MB）或放大档（viewSize×2；12MP 上这档等价全分辨率）
        // HDR 开 ⇒ ImageDecoder（保留 gainmap，主图与 gainmap 同步缩放）；
        // HDR 关 ⇒ BitmapFactory（inSampleSize 会丢 gainmap ⇒ 纯 SDR，正是"关"要的效果）
        val b = withContext(Dispatchers.IO) {
            if (hdrOn) decodeFitHdr(context, parsed, vw * target, vh * target)
            else decodeFit(context, parsed, vw * target, vh * target)
        }
        if (b != null) {
            // ★ PV-1 交叉淡入，三步顺序**不能换**（换了下场是"闪一帧全黑"）：
            // ① 先摆下层 —— `prev` 就是此刻屏上那一张，这一帧即使立刻渲染也看不出任何变化；
            // ② 再归零上层 —— 屏上剩下的是 prev，与①之前**逐像素相同**（同一张位图）；
            // ③ 最后换上目标档 —— 从 alpha 0 起，由下面的 [LaunchedEffect] 渐显到 1。
            // 反例（先归零再摆下层）：上层 alpha=0 且下层还是 null ⇒ 中间渲染出**一帧纯黑**，
            // 比原来的清晰度阶跃更刺眼。`snapTo` 是挂起函数，它让不让出主线程不由这里决定，
            // 所以只能靠"顺序本身在任何中断点都自洽"来保证不黑屏。
            val prev = bitmap
            under = prev
            if (prev != null) fade.snapTo(0f)
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

    // ── ★ PV-1 交叉淡入的驱动（key = [bitmap] ⇒ 只有"换图"才重启）────────────────────
    // 归零在换图前就做完了（见上面解码协程的①②③），这里只负责把上层推到 1。
    // **中断也安全**：协程被取消（用户又滑走 / 又放大）时 `fade` 停在中间值，下一次 effect 会
    // 从当前值继续走到 1；切页则被上面那个 effect 直接 snapTo(1f) 复位。两条路都不会留下
    // 半透明的页面。
    // 收尾把 under 交回 GC —— 那张（3MB 量级）只在换档的这 180ms 里有存在价值。
    // ⚠ 整段是**绘制层行为，JVM 单测覆盖不到**：真机确认步骤见报告 §5。
    LaunchedEffect(bitmap) {
        if (under != null) fade.animateTo(1f, tween(PREVIEW_FADE_MS)) else fade.snapTo(1f)
        under = null
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
    BackHandler(enabled = showHdrPanel) { showHdrPanel = false }

    // ── 「导出 SDR 版」（v0.11.54，用户 2026-10-20 定案）─────────────────
    // HDR 开关本质是**单张图的编辑功能**：这张图带 gainmap（HDR），用户可能想要一张
    // **纯 SDR** 的版本（发给不支持 HDR 的人 / 微信 / 老设备）。
    // 走 `nativeExtractSdrBase` 从 JPEG/R 里**无损取出 base**，不是重新压缩 ——
    // Bitmap.compress 会对 12MP 再压一次 JPEG（二次量化），而 base 本来就是完整 JPEG。
    val hdrScope = androidx.compose.runtime.rememberCoroutineScope()
    val exportSdr: () -> Unit = {
        val u = currentUri
        if (u != null && !hdrExporting) {
            hdrExporting = true
            hdrExportMsg = null
            hdrScope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val src = context.contentResolver.openInputStream(u)?.use { it.readBytes() }
                            ?: return@runCatching false
                        val sdr = com.filmkit.app.EngineBridge.nativeExtractSdrBase(src)
                            ?: return@runCatching false
                        saveSdrJpeg(context, sdr)
                    }.getOrDefault(false)
                }
                hdrExporting = false
                hdrExportMsg = if (ok) "已导出 SDR 版到相册" else "导出失败（这张可能不是 HDR 图）"
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black) // ② 纯黑底（ADR-15 实测）
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ── ① 顶栏：返回 ｜ 日期 + 时间（居中两行）｜ 收藏 ──────────────────
        // 布局用 Box 叠层：左右按钮各自靠边，日期用 align(Center) **严格居中**
        // （v0.12.36 用户要求："按钮靠左在返回箭头右边，日期还是居中"）。
        // 用 Row + weight 做不到 —— 两侧按钮宽度不等时日期会偏。
        Box(Modifier.fillMaxWidth().height(56.dp)) {
        Row(
            Modifier.fillMaxSize(),
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
            // ── 「相册」入口（v0.12.35）：紧挨返回键，只有"从相机直接看大图"时才出现 ──
            if (onOpenGallery != null) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clickable { onOpenGallery() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PhotoLibrary,
                        contentDescription = "相册",
                        tint = FilmTheme.Cream,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // ── HDR 开关（v0.11.50）：对齐 PC 的查看页交互 ────────────────
            // 文字按钮而非图标：HDR 是**状态**不是动作，文字 + 高亮比图标更明确
            // （PC 也是这么做的，见其 GalleryDetailScreen 的 HDR 按钮）。
            Box(
                Modifier
                    .height(48.dp)
                    .clickable { showHdrPanel = true }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "HDR",
                    color = if (hdrOn) FilmTheme.Accent else FilmTheme.CreamDim,
                    fontSize = 13.sp,
                    fontWeight = if (hdrOn) FontWeight.Bold else FontWeight.Normal,
                )
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
            // 日期 + 时间：叠在中间，严格水平居中
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(photoDateLabel(meta.takenAtSec), color = FilmTheme.Cream, fontSize = 13.sp)
                val time = photoTimeLabel(meta.takenAtSec)
                if (time.isNotEmpty()) {
                    Text(time, color = FilmTheme.CreamDim, fontSize = 12.sp)
                }
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
                                                // ★ PV-4：**以两指中心为锚点**缩放（此前只改 zoom
                                                // ⇒ 绕画面中心缩放，用户真机："很奇怪"）。
                                                // 顺序：① 先跟随两指整体位移（calculatePan，
                                                // current − previous）；② 再按 [zoomAboutCentroid]
                                                // 补掉"绕 pivot 缩放"多出来的那段平移。
                                                // 锚点 = current 帧两指中心（理由见 [zoomAboutCentroid]）；
                                                // 容器中心 = 本手势节点中心 = graphicsLayer 的 pivot
                                                // （页 Box / Image 都是 fillMaxSize，同尺寸）。
                                                // 倍率先夹紧再补偿：顶到 [MAX_ZOOM] 后 z₁ == z₂ ⇒
                                                // 补偿项为 0，不会继续漂移。
                                                val z1 = zoom.floatValue
                                                val z2 = clampZoom(z1, event.calculateZoom())
                                                val moved = pan.value + event.calculatePan()
                                                zoom.floatValue = z2
                                                pan.value = clampPan(
                                                    zoomAboutCentroid(
                                                        moved,
                                                        event.calculateCentroid(useCurrent = true),
                                                        Offset(size.width / 2f, size.height / 2f),
                                                        z1,
                                                        z2,
                                                    ),
                                                    z2,
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
                        // 当前页 = 主解码的位图（Fit / 放大档）；相邻页 = 主动预载的预览档
                        // （不占全尺寸内存）。★ E2-23 更正：**"相邻页必然是 Fit 态所以等于无变换"
                        // 是错的** —— 放大态下 Pager 只是不滚，相邻页照样被组合/绘制，而 zoom/pan
                        // 是整页共享的 state ⇒ 邻页被一起放大后越界画进视口（见 [pageScaleAt]）。
                        // ★ PV-2：这里不再自己解码，只读预载缓存（见 [rememberNeighborPreview]）。
                        // ★ PV-3：**位图要校验归属**（见 [visibleBitmapFor]）。`bitmap` 是整页共享
                        // 的 state，而它由 key 含 `page` 的 effect 写 ⇒ "重组第一次读到新 `page`"
                        // 的那一帧它还是**上一张的像素**（用户真机："左右滑已经显示下一张图了，
                        // 上一张图还是会闪现一下"）。归属不符 ⇒ 画这一页**自己的**预载档
                        // （通常命中主动预载 ⇒ 那一帧直接是新图），**绝不画旧页的像素**。
                        // 停稳页也要读缓存：切页那一帧它就是靠这份预载档接上的。
                        val pageUri = uris.getOrNull(p)
                        val cached = pageUri?.let {
                            rememberNeighborPreview(previews, it, previewsVersion)
                        }
                        // 位图 / 下层 `under` / 淡入 alpha 共用这一条判据（缩放平移的判据不变）
                        val owns = bitmapBelongsToPage(p == page, pageUri, shownUri)
                        val b = visibleBitmapFor(p == page, pageUri, shownUri, bitmap, cached)
                        when {
                            p == page && error.isNotEmpty() ->
                                Text(error, color = FilmTheme.CreamDim, fontSize = 13.sp)
                            b != null -> {
                                // ★ PV-1：下层 = 被换下去的那张（只有同页升档才有，切页恒为 null）。
                                // 它必须用**同一个** [viewerLayer] 变换 —— 缩放态下两层错位就是重影，
                                // 比原来的闪更难查。contentDescription 留空：它是装饰层，不是内容。
                                // ★ PV-3：`under` 也是**整页共享**的 state，同样要过归属校验 ——
                                // 切页那一帧 `under` 可能还是上一页的（清它的是 effect，异步），
                                // 只判 `p == page` 就会把上一张的像素画到新页上（旧图闪现的第二条路径）。
                                if (owns) under?.let { u ->
                                    Image(
                                        bitmap = u.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.viewerLayer(p == page, zoom, pan),
                                    )
                                }
                                Image(
                                    bitmap = b.asImageBitmap(),
                                    contentDescription = "照片",
                                    contentScale = ContentScale.Fit,
                                    // graphicsLayer 的 block 在绘制阶段读 state ⇒ 手势/淡入只触发重绘，
                                    // 不触发重组（每帧拖动都重组整页会很卡）。
                                    // ★ E2-23：缩放/平移**只给停稳的那一页**（[pageScaleAt]）——
                                    // 相邻页跟着放大就会从视口外越界画进来（别张照片的像素盖在本页上，
                                    // 正是用户报的"入侵视野"）。此前这里对每页一视同仁，是这次 bug 的根因。
                                    // ★ PV-1：alpha 也走同一个 block（[fade]），换档时 0→1 渐显。
                                    // ★ PV-2：但 alpha 必须与缩放/平移**同一道闸**（[pageAlphaAt]）——
                                    // PV-1 在这里对**所有页**读 `fade.value`，于是淡入期间相邻页也半透明，
                                    // 而相邻页没有下层兜底 ⇒ 半透明压在黑底上 ⇒ 整片图片区变暗（用户报的
                                    // "屏幕闪烁"）。判据是 `under != null`（不是 `fade < 1`），
                                    // 原因见 [pageAlphaAt]。
                                    // ★ PV-3：闸门参数从 `p == page` 收紧成 `owns`（归属校验）——
                                    // 切页那一帧 `under` 可能还是上一页的、`fade` 可能停在中间值，
                                    // 只判 `p == page` 会让**新页的预载档**半透明地压在黑底上（变暗一闪）。
                                    // 几何判据（[pageScaleAt] / [pageShiftAt]）仍然是 `p == page`，未改。
                                    modifier = Modifier.viewerLayer(p == page, zoom, pan) {
                                        pageAlphaAt(owns, under != null, fade.value)
                                    },
                                )
                            }
                            // ★ PV-2：停稳页还没有内容 —— **先保持纯黑底**，只有真的等久了
                            // （[SPINNER_DELAY_MS]）才画转圈。用户实测"左右滑动一定会显示一下
                            // 转圈，所以观感上就是闪一下"：转圈是个高对比圆环，画在纯黑底上
                            // 出现再消失本身就是一次闪；而绝大多数"没内容"只持续一张预览档的
                            // 解码时间（15–30ms ≈ 1–2 帧），那种情况黑底与页面背景同色，看不出
                            // 变化。等久了仍然给反馈，不静默（ADR-12）。
                            p == page && showSpinner -> CircularProgressIndicator(color = FilmTheme.Accent)
                            // 相邻页预览还没解出来 / 停稳页还在解码的短暂窗口：保持黑底
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

    // ── HDR 面板（v0.11.54）：单张图的显示 + 导出入口 ────────────────────
    if (showHdrPanel) {
        // ⚠ **遮罩必须全透明**（v0.11.58，用户实测要求）：面板打开时要能**实时**
        // 看到图片在 HDR 开/关下的差别，半透明黑罩会把高光压暗、对比全失
        //（0.55 alpha 时 HDR 与 SDR 几乎看不出区别）。
        // 透明不影响"点外部关闭"：clickable 覆盖整屏，只吃 tap（不吃滑动），
        // 面板自身有 FilmTheme.Panel 不透明背景，视觉上仍是一块浮窗。
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = MutableInteractionSource(), indication = null) {
                    showHdrPanel = false
                },
        ) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(FilmTheme.Panel)
                    .clickable(interactionSource = MutableInteractionSource(), indication = null) {}
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                // 紧凑布局（v0.11.58）：说明文字压成一行、开关与按钮并排 ——
                // 面板是全宽贴底，越高挡得越多；用户要"实时看图"，可视面积优先。
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "HDR 显示",
                        color = FilmTheme.Cream, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Switch(
                        checked = hdrOn,
                        onCheckedChange = {
                            hdrOn = it
                            // 只写这张图的 key（v0.11.60：按单图记忆，不再一关全关）
                            prefs.edit().putBoolean(hdrKey, it).apply()
                        },
                    )
                }
                Text(
                    // 按单图提示（v0.11.60）：SDR 图即使开关开着也没有增益可显示，
                    // 说清楚比让用户对着"开了却没变化"猜要好。
                    if (android.os.Build.VERSION.SDK_INT >= 34 && bitmap?.hasGainmap() == true) {
                        "这张图带 HDR 增益图 · 不支持 HDR 的查看器自动回落 SDR"
                    } else {
                        "这张图没有 HDR 数据（按 SDR 显示）"
                    },
                    color = FilmTheme.CreamDim, fontSize = 11.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (hdrExporting) FilmTheme.ChipBg else FilmTheme.Accent)
                            .clickable(enabled = !hdrExporting) { exportSdr() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (hdrExporting) "导出中…" else "导出 SDR 版",
                            color = if (hdrExporting) FilmTheme.CreamDim else FilmTheme.OnAccent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Box(
                        Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(FilmTheme.ChipBg)
                            .clickable { showHdrPanel = false }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("关闭", color = FilmTheme.Cream, fontSize = 14.sp)
                    }
                }
                hdrExportMsg?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = FilmTheme.CreamDim, fontSize = 12.sp)
                }
            }
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
            title = {
                Text("删除这张照片？", color = FilmTheme.Cream, fontFamily = FilmTheme.Serif)
            },
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
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontFamily = FilmTheme.Mono)
                }
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
 * 照片的绘制层变换：**缩放/平移**（E2-20）+ **只给停稳页**（E2-23 [pageScaleAt] / [pageShiftAt]）
 * + **淡入 alpha**（★ PV-1，★ PV-2 补上同一道闸 [pageAlphaAt]）。
 *
 * **为什么必须抽成一个函数**：PV-1 的交叉淡入要同时画两层（下层被换下去的预览 + 上层目标档），
 * 两层各抄一遍变换代码迟早会抄歪 —— 缩放态下两层错位就是重影，比它要修的"闪"更难查。
 *
 * block 在**绘制阶段**执行（读 [FloatState] / [State] 不触发重组）：捏合时每帧改 zoom 只重绘
 * 不重组；淡入时每帧改 alpha 同理。
 *
 * **两个调用点的 alpha 不一样，这是故意的**（交叉淡入的定义就是"下层不动、上层渐显"）：
 * - 下层（`under`，只画在停稳页）：用默认 `alpha = { 1f }` —— 它是**不动的底**，一旦跟着渐显
 *   就会在 180ms 里露出黑底；
 * - 上层（`bitmap`）：`{ pageAlphaAt(p == page, under != null, fade.value) }` —— 相邻页恒 1，
 *   停稳页只在淡入期间用 [fade]。
 *
 * 两层的**几何变换**逐位相同（同一个 [pageScaleAt] / [pageShiftAt] + 同一份 zoom/pan）
 * ⇒ 缩放态下不会错位、不会重影（真机观感见报告 §6 判据 D）。
 */
private fun Modifier.viewerLayer(
    isSettledPage: Boolean,
    zoom: FloatState,
    pan: State<Offset>,
    alpha: () -> Float = { 1f },
): Modifier = fillMaxSize().graphicsLayer {
    val z = pageScaleAt(isSettledPage, zoom.floatValue)
    scaleX = z
    scaleY = z
    translationX = pageShiftAt(isSettledPage, pan.value.x)
    translationY = pageShiftAt(isSettledPage, pan.value.y)
    this.alpha = alpha()
}

/**
 * 相邻页要画的位图：**只读 [cache]**（★ PV-2 —— PV-1 这里还兼着"按需自解一张"，已删）。
 *
 * **为什么删掉自解**：预载改成主动的 `page±[PRELOAD_RADIUS]`（见预载 effect）之后，邻页的位图
 * 由那一条 effect 负责生产，这里再解一遍就是**同一个 key 解两次**（谁先谁后不定），白白多占
 * 一份解码 CPU 与瞬时内存 —— 而 PV-1 的"顺手解一张"恰恰是赶不上切页那一刻的那条路径。
 * 现在职责单一：预载负责**生产**，这里负责**显示**。
 *
 * **为什么需要 [version]**：`ThumbCache` 是普通对象，不是 snapshot state —— 预载把它填满之后，
 * 已经组合好的邻页不会自己重组，也就取不到刚填进去的那张。`version` 是"缓存有更新"的信号
 *（每次预载完成 +1），当 remember key 用。命中缓存时这里是零解码、零 IO 的纯读取。
 *
 * **只在 `p != page` 时调用** —— 当前页由主解码负责。取不到就返回 null，调用方保持黑底
 *（相邻页不值得显示错误文案）。
 *
 * ★ PV-3 更正：**停稳页现在也调用它**（`p == page` 时）—— 切页那一帧 [bitmap] 还是上一页的
 * 像素，[visibleBitmapFor] 的归属校验会把它挡掉，此时这一页要画的正是这里的预载档
 *（主动预载 `page±[PRELOAD_RADIUS]` 命中 ⇒ 那一帧直接是新图）。原判据"当前页由主解码负责"
 * 说的是**生产**（谁解这张图），不是**显示**（这一帧画哪张）：生产仍然只有主解码与主动预载两条路，
 * 这里始终是零解码、零 IO 的纯读取。
 */
@Composable
private fun rememberNeighborPreview(
    cache: ThumbCache<String, Bitmap>,
    uri: String,
    version: Int,
): Bitmap? = remember(uri, version) { cache.get(uri) }

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

/** 从任意 Context 解包出 Activity（Compose 的 LocalContext 可能被多层 ContextWrapper 包裹）。 */
internal fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}
