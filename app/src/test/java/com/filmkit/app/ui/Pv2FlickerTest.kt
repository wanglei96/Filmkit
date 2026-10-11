package com.filmkit.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ PV-2（用户 2026-10-11 复测的两条澄清）：
 * ① "**闪一下不是糊到清晰，是屏幕闪烁**" —— PV-1 的"清晰度阶跃"根因判断被推翻；
 * ② "**左右滑动一定会显示一下转圈，所以观感上就是闪一下**" —— 那条闪就是转圈。
 *
 * 本文件钉住 PV-2 的四条判据（都是**能算的部分**）：
 * - [pageAlphaAt]：alpha 与缩放/平移**同一道闸**（相邻页恒 1；停稳页在"没有淡入"时恒 1，
 *   与 `fade` 当时停在哪个值无关）；
 * - [frameAfterPageChange]：换页时 uri/档位/位图**一次给全**、位图只来自新页（不沿用上一页）；
 * - [preloadOffsets]：主动预载覆盖 `page±2`（PC 口径）、近的优先、不含当前页；
 * - 缓存装得下 ±2 的四张预览档（用**真实的** [fitSizeFor] / [sampleSizeFor] 算字节）。
 *
 * 外加两条**设计依据**的实验（不是被测代码的行为，而是"为什么这么设计"的证据）：
 * - [snapToOnRunningAnimationIsNotInstant]：`Animatable.snapTo` 是**挂起**函数，
 *   动画在跑时要 1–2 帧才生效 ⇒ 不能拿它保证"切页那一帧 alpha 必然是 1"；
 * - [aDyingAnimationOverwritesASyncResetOnTheNextFrame]：所以"把 Animatable 换成
 *   mutableFloatStateOf + animate()、复位改成同步赋值"这条候选**也不够** —— 还没被取消的
 *   动画协程会在下一帧把同步赋的值覆盖掉。两条合起来解释了 [pageAlphaAt] 为什么用
 *   "`under != null` 门控"而不是"看 `fade` 的值"。
 *
 * ⚠ 覆盖不到的（本项目只有 junit，无 Robolectric / compose-ui-test）：真实的帧调度、
 * `LaunchedEffect` 的启动时机、`AndroidImageBitmap` 的纹理上传、真机观感 —— 见报告 §5/§6。
 */
class Pv2FlickerTest {

    // ── ① alpha 判据：与 pageScaleAt / pageShiftAt 同一道闸 ────────────────

    @Test
    fun neighborPagesNeverInheritTheFadeAlpha() {
        // 相邻页恒 1（全不透明）—— 这正是 PV-1 漏掉的那道闸：`viewerLayer` 的调用点当时对
        // **所有页**读 `fade.value`，于是淡入的 180ms 里相邻页也半透明。相邻页没有下层兜底
        // （`under` 只画停稳页）⇒ 半透明压在 Color.Black 上 ⇒ 整片图片区变暗。
        // 用户滑动时邻页正从边缘滑进视口 ⇒ 变暗就落在视口正中。
        for (fade in listOf(0f, 0.25f, 0.5f, 0.99f, 1f)) {
            assertEquals(
                "相邻页的 alpha 必须是 1（fade=$fade 也不例外）—— 邻页没有下层兜底，半透明就是露黑底",
                1f,
                pageAlphaAt(isSettledPage = false, fading = true, fade = fade),
                0f,
            )
            assertEquals(
                1f,
                pageAlphaAt(isSettledPage = false, fading = false, fade = fade),
                0f,
            )
        }
    }

    @Test
    fun settledPageIsOpaqueWheneverNoFadeIsRunning() {
        // ★ 切页那一帧的判据：切页 effect 里 `under = null` 是**同步**赋值 ⇒ fading=false
        // ⇒ alpha 必然是 1，**与 `fade` 停在哪个中间值无关**。
        // PV-1 靠 `fade.snapTo(1f)` 复位，而它是挂起函数（见下面那条实验）⇒ 那一帧可能半透明。
        for (fade in listOf(0f, 0.37f, 0.8f, 1f)) {
            assertEquals(
                "没有淡入在跑时，停稳页必须全不透明（fade=$fade 是垂死动画可能留下的中间值）",
                1f,
                pageAlphaAt(isSettledPage = true, fading = false, fade = fade),
                0f,
            )
        }
    }

    @Test
    fun settledPageUsesFadeOnlyWhileFading() {
        // 淡入本身保留（只在静止时可见，抹平预览档 → 目标档的清晰度过渡）。
        // 这条断言同时防"把淡入整段删掉"这种过度修正。
        assertEquals(0f, pageAlphaAt(isSettledPage = true, fading = true, fade = 0f), 0f)
        assertEquals(0.4f, pageAlphaAt(isSettledPage = true, fading = true, fade = 0.4f), 1e-6f)
        assertEquals(1f, pageAlphaAt(isSettledPage = true, fading = true, fade = 1f), 0f)
    }

    // ── ② 换页的原子状态转移 ───────────────────────────────────────────────

    @Test
    fun pageChangeFrameNeverReusesThePreviousBitmap() {
        // 未命中主动预载 ⇒ 位图必须是 null（纯黑底 + 延迟转圈），**不能**沿用上一张的像素。
        // "新页画上一张的图"是错图，比黑底更糟；而 PV-1 的"先置 null、等另一个 effect 来填"
        // 中间那一帧就是用户实测的转圈。
        assertNull(
            "未命中预载时必须是空帧（黑底），不允许沿用上一页的像素",
            frameAfterPageChange<String>("content://media/2", null).bitmap,
        )
        // 命中 ⇒ 一次调用里就是**新页**的内容
        val hit = frameAfterPageChange("content://media/2", "preview-of-2")
        assertEquals("preview-of-2", hit.bitmap)
        assertEquals("content://media/2", hit.uri)
        // 档位标记：缓存里存的是预览档（容器的一半），不是目标档
        assertEquals(TIER_PREVIEW, hit.tier)
        // 档位序：预览 < Fit < 放大（`shownTier >= target` 的判据靠这个方向）
        assertTrue("档位必须递增：$TIER_PREVIEW < $TIER_FIT < $TIER_HIRES", TIER_PREVIEW < TIER_FIT)
        assertTrue(TIER_FIT < TIER_HIRES)
    }

    @Test
    fun pageChangeFrameIsIndependentOfWhatWasOnScreenBefore() {
        // "原子"的另一个说法：结果只由 (新 uri, 新页的缓存命中) 决定 —— 与上一帧是什么无关。
        // 这正是"不可能渲染出中间态"的形式化：不存在"旧位图 + 新 uri"的组合。
        val before = ViewerFrame("content://media/1", TIER_FIT, "fit-of-1")
        val after = frameAfterPageChange("content://media/2", "preview-of-2")
        assertEquals("content://media/2", after.uri)
        assertEquals("preview-of-2", after.bitmap)
        assertTrue("新帧绝不能带着上一帧的位图", after.bitmap != before.bitmap)
    }

    // ── ③ 主动预载范围（PC `preloadPreviews` 口径）────────────────────────

    @Test
    fun preloadCoversBothSidesNearestFirstAndSkipsCurrentPage() {
        // PC 的 `ImageViewerFragment.preloadPreviews(centerPos)` 是 center±2
        assertEquals("预载半径必须与 PC 的 preloadPreviews 一致（±2）", 2, PRELOAD_RADIUS)
        // 近的优先：用户下一次滑动最可能落在 ±1，先解它
        assertEquals(listOf(-1, 1, -2, 2), preloadOffsets(PRELOAD_RADIUS))
        assertTrue("当前页由主解码负责，预载它纯属白解一张", 0 !in preloadOffsets(PRELOAD_RADIUS))
        // 边界：半径非正 ⇒ 不预载（不返回 null 元素，调用侧不用额外判空）
        assertEquals(emptyList<Int>(), preloadOffsets(0))
        assertEquals(emptyList<Int>(), preloadOffsets(-3))
        // 覆盖张数 = 2 × 半径（缓存容量要按这个数算）
        assertEquals(4, preloadOffsets(PRELOAD_RADIUS).size)
    }

    @Test
    fun previewCacheHoldsTheWholePreloadWindow() {
        assertEquals("缓存要装下 page±2 的四张邻页", 4, PREVIEW_CACHE_COUNT)
        // 用**真实函数**算 1080p 容器上 12MP 横图的预览档字节数（不是拍一个数字进来）
        val (vw, vh) = 1080 to 1700
        val (pw, ph) = previewRequestFor(vw, vh)
        val (fw, fh) = fitSizeFor(4000, 3000, pw, ph)
        val sample = sampleSizeFor(4000, 3000, fw, fh)
        val decodedW = 4000 / sample
        val decodedH = 3000 / sample
        val bytes = decodedW.toLong() * decodedH * 4 // ARGB_8888
        println("PV-2 缓存台账：1080p/12MP 预览档 = ${decodedW}×${decodedH}(sample=$sample) = ${bytes / 1024}KB/张")
        assertTrue(
            "预载窗口（${preloadOffsets(PRELOAD_RADIUS).size} 张 × ${bytes / 1024}KB = " +
                "${bytes * PREVIEW_CACHE_COUNT / 1024 / 1024}MB）装不进上限" +
                "（${PREVIEW_CACHE_BYTES / 1024 / 1024}MB）—— 预载会静默塌掉，滑动时邻页没有现成" +
                "预览，又回到「未命中 ⇒ 转圈」的老路",
            bytes * PREVIEW_CACHE_COUNT <= PREVIEW_CACHE_BYTES,
        )
    }

    // ── ④ 设计依据的两个实验（Compose 的真实行为，不是我们的代码）──────────

    /** 手写帧钟：一帧 = 真实 16ms（`Animatable.animateTo` 需要 MonotonicFrameClock）。 */
    private class FakeFrameClock : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(16)
            return onFrame(System.nanoTime())
        }
    }

    @Test
    fun snapToOnRunningAnimationIsNotInstant() = runBlocking(FakeFrameClock()) {
        // 采样 8 次：`Animatable.snapTo` 在有动画在跑时**不是同步的** —— 它要等旧动画协程响应
        // 取消并释放 mutex。实测抖动很大（本机 5–24ms，一帧 = 16ms），而这个**不确定性本身**
        // 就是问题："切页那一帧 alpha 有没有复位"取决于当次调度 ⇒ 偶发的一两帧半透明
        // ⇒ 用户看到的"闪一下"。PV-1 正是拿它当"新页必须从全显开始"的保证。
        val samples = mutableListOf<Long>()
        repeat(8) {
            val a = Animatable(1f)
            val job = launch { a.animateTo(0f, tween(500)) }
            delay(120) // 让动画真的跑起来（持锁）
            val t0 = System.nanoTime()
            a.snapTo(1f)
            samples += (System.nanoTime() - t0) / 1_000_000
            job.cancel()
            delay(40) // 让取消落地，不影响下一次采样
        }
        println("PV-2 实验：动画运行时 snapTo 耗时样本 = $samples ms（一帧 = 16ms）")
        assertTrue(
            "有动画在跑时 `Animatable.snapTo` 不是同步的（样本 $samples ms）—— 所以 PV-1 用 " +
                "`fade.snapTo(1f)` 保证「切页那一帧 alpha = 1」不成立，而且延迟不确定 ⇒ 偶发" +
                "半透明帧。[pageAlphaAt] 改用 `under != null` 门控正是为了绕开这个挂起",
            samples.any { it >= 1 },
        )
    }

    @Test
    fun aDyingAnimationOverwritesASyncResetOnTheNextFrame() = runBlocking(FakeFrameClock()) {
        // 候选方案（"把 Animatable 换成 mutableFloatStateOf + animate()，复位改成同步赋值"）
        // 的真实缺口：`LaunchedEffect` 的取消发生在**下一帧的组合**，在那之前动画协程还活着，
        // 它下一帧照样写值 ⇒ 同步赋的 1f 被覆盖回中间值 ⇒ 仍然闪一帧。
        val state = mutableFloatStateOf(1f)
        val job = launch { animate(0f, 1f, animationSpec = tween(500)) { v, _ -> state.floatValue = v } }
        delay(120)
        state.floatValue = 1f // 模拟"切页那一帧的同步复位"
        val rightAfterReset = state.floatValue
        delay(20) // 模拟"LaunchedEffect 重启发生在下一帧"—— 这期间动画协程还没被取消
        val afterOneFrame = state.floatValue
        job.cancel()
        println("PV-2 实验：同步复位后立刻=$rightAfterReset，一帧后=$afterOneFrame")
        assertEquals("同步赋值本身是立刻生效的", 1f, rightAfterReset, 0f)
        assertTrue(
            "还没被取消的动画协程会把同步复位的值覆盖成 $afterOneFrame —— 所以「同步赋值复位」这条" +
                "候选不成立；[pageAlphaAt] 用 `under != null` 门控（不看 fade 的值）才是零窗口的",
            afterOneFrame < 1f,
        )
    }
}
