package com.filmkit.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 查看页缩放 / 平移 / 手势仲裁的纯逻辑护栏（E2-20）。这里算错都是**静默事故**：
 * 图被拖出画面外留下黑边、捏合能缩到比 Fit 还小（用户明确禁止）、Fit 尺寸下横滑切不了图、
 * 滑到第 7 张进编辑器退出却回到第 1 张 —— Compose 部分在 JVM 里测不了，这些必须测。
 *
 * 数值取真机口径：4000×3000 的 12MP 原图在 1080×1920 的容器里 Fit 后是 1080×810。
 */
class PhotoViewZoomTest {

    private val eps = 0.001f

    // ── ① 缩放范围（用户 2026-10-10："最小为原片默认展示大小"）──────────────

    @Test
    fun zoomLowerBoundIsFitExactly() {
        // 下限必须是 1×（= ContentScale.Fit 的显示尺寸）。改成 0.9 就能缩到比原片默认展示更小，
        // 直接违背用户要求 —— 单测钉死这条。
        assertEquals(1f, MIN_ZOOM, eps)
        assertEquals(1f, clampZoom(1f, 0.5f), eps)
        assertEquals(1f, clampZoom(2f, 0.1f), eps)
        assertEquals(1f, clampZoom(1f, 0.99f), eps)
    }

    @Test
    fun zoomUpperBoundIsFive() {
        assertEquals(5f, MAX_ZOOM, eps)
        assertEquals(5f, clampZoom(4f, 2f), eps)
        assertEquals(5f, clampZoom(5f, 1.01f), eps)
    }

    @Test
    fun zoomIsMultiplicativeInRange() {
        assertEquals(2f, clampZoom(1f, 2f), eps)
        assertEquals(1.5f, clampZoom(3f, 0.5f), eps)
        assertEquals(1f, clampZoom(1f, 1f), eps)
    }

    @Test
    fun zoomIgnoresGarbageFactorInsteadOfJumping() {
        // calculateZoom 理论上不会给这些值，但一旦给了 NaN/0/负，coerceIn 会把 NaN 传下去
        // ⇒ 整张图消失（graphicsLayer 收到 NaN 后什么都不画）。必须保持原值。
        assertEquals(2f, clampZoom(2f, Float.NaN), eps)
        assertEquals(2f, clampZoom(2f, 0f), eps)
        assertEquals(2f, clampZoom(2f, -1f), eps)
        assertEquals(2f, clampZoom(2f, Float.POSITIVE_INFINITY), eps)
    }

    // ── ② 平移夹紧（放大后单指拖动不能拖出画面）──────────────────────────

    @Test
    fun panIsClampedToContentOverflow() {
        // 内容 2160 宽、视口 1080 ⇒ 左右各能移 (2160-1080)/2 = 540
        assertEquals(500f, clampAxis(500f, 1080f, 2160f), eps)
        assertEquals(540f, clampAxis(900f, 1080f, 2160f), eps)
        assertEquals(-540f, clampAxis(-900f, 1080f, 2160f), eps)
    }

    @Test
    fun contentFittingInsideViewportCannotPanAtAll() {
        // 内容不超过视口 ⇒ 上限 0。**这就是"缩回 Fit 尺寸时平移量归零"的保证**：
        // Fit 的显示尺寸必然 <= 视口，夹紧规则自己就把平移压回 0，不需要额外分支。
        assertEquals(0f, clampAxis(50f, 1080f, 1080f), eps)
        assertEquals(0f, clampAxis(-50f, 1080f, 810f), eps)
        assertEquals(0f, clampAxis(0f, 1080f, 1080f), eps)
    }

    @Test
    fun nanPanCollapsesToZero() {
        assertEquals(0f, clampAxis(Float.NaN, 1080f, 2160f), eps)
    }

    // ── ③ Fit 几何（ContentScale.Fit 的显示尺寸）────────────────────────

    @Test
    fun wideImageFitsByWidth() {
        val (w, h) = fitDisplaySize(4000, 3000, 1080f, 1920f)
        assertEquals(1080f, w, eps)
        assertEquals(810f, h, eps)
    }

    @Test
    fun tallImageFitsByHeight() {
        val (w, h) = fitDisplaySize(3000, 4000, 1920f, 1080f)
        assertEquals(810f, w, eps)
        assertEquals(1080f, h, eps)
    }

    @Test
    fun unknownImageSizeFallsBackToViewport() {
        // 还没解码完 / 解不开：退化成容器尺寸 ⇒ 上限 0 ⇒ 不能平移，不崩
        assertEquals(1080f to 1920f, fitDisplaySize(0, 0, 1080f, 1920f))
        // 容器还没测量（0×0）：没有可视区域 ⇒ 0（不是把图撑到不存在的地方）
        assertEquals(0f to 0f, fitDisplaySize(4000, 3000, 0f, 0f))
    }

    @Test
    fun zoomedWideImagePansHorizontallyButNotVertically() {
        // 真机最典型的一档：4000×3000 在 1080×1920 容器里 Fit = 1080×810。
        // 放大 2× ⇒ 内容 2160×1620：左右可移 540，上下**仍然不能移**（1620 < 1920）
        // —— 否则一拖就露出上下黑边（"拖出画面外"）。
        val (w, h) = fitDisplaySize(4000, 3000, 1080f, 1920f)
        assertEquals(540f, clampAxis(9999f, 1080f, w * 2f), eps)
        assertEquals(0f, clampAxis(9999f, 1920f, h * 2f), eps)
    }

    // ── ④ 手势仲裁状态机（本卡最容易出错的地方）──────────────────────────

    @Test
    fun singleFingerAtFitSwipesInsteadOfPanning() {
        // Fit 下单指必须交给 Pager 切图（手势层不消费事件）。判成 Pan ⇒ 切图功能整个失效。
        assertEquals(ViewerGesture.Swipe, viewerGesture(pressedPointers = 1, zoomed = false))
    }

    @Test
    fun singleFingerWhenZoomedPans() {
        // 放大后单指 = 平移，绝不能切图（用户明确要求）
        assertEquals(ViewerGesture.Pan, viewerGesture(pressedPointers = 1, zoomed = true))
    }

    @Test
    fun twoFingersAlwaysZoom() {
        // 两态下双指都是缩放（Fit 时放大、放大时继续缩放/捏回）
        assertEquals(ViewerGesture.Zoom, viewerGesture(pressedPointers = 2, zoomed = false))
        assertEquals(ViewerGesture.Zoom, viewerGesture(pressedPointers = 2, zoomed = true))
        assertEquals(ViewerGesture.Zoom, viewerGesture(pressedPointers = 3, zoomed = false))
    }

    @Test
    fun noFingerIsNotPan() {
        // 手指全抬起后（pressed=0）不能被当成平移去改平移量
        assertEquals(ViewerGesture.Swipe, viewerGesture(pressedPointers = 0, zoomed = true))
    }

    // ── ⑤ 索引边界（相册点第 N 张 ⇒ 查看页从第 N 张开始）────────────────

    @Test
    fun indexIsClampedIntoRange() {
        assertEquals(1, clampIndex(1, 3))
        assertEquals(2, clampIndex(9, 3))
        assertEquals(0, clampIndex(-1, 3))
    }

    @Test
    fun emptyListIndexCollapsesToZero() {
        // 空列表：Pager 不接受越界页，路由层也不能拿到负索引（initialPage 传负数会崩）
        assertEquals(0, clampIndex(0, 0))
        assertEquals(0, clampIndex(5, 0))
    }
}
