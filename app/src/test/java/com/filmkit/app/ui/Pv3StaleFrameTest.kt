package com.filmkit.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ★ PV-3（用户 2026-10-11 真机 v0.11.19 复测："**没有转圈了，但是左右滑已经显示下一张图了，
 * 上一张图还是会闪现一下**"）：转圈（PV-2 ①）修好了，但**同一处时序缺口换了个面** ——
 * 过去是"新页位置上画 null ⇒ 转圈"，现在是"新页位置上画**上一张的像素** ⇒ 旧图闪现"。
 *
 * 根因：[`bitmap`] 是**整页共享**的 state，由 key 含 `page` 的 `LaunchedEffect` 写；`page`
 * （= `settledPage`）由 Pager 的滚动驱动。Effect 的重启必然晚于"重组第一次读到新 `page`"，
 * 而 `Image` 的参数在**组合期**取值 ⇒ **必然存在至少一帧："新页已选中、位图还是上一页的"**。
 * 把 effect 写得多原子都消不掉这个窗口 —— 只能**绘制期校验归属**。
 *
 * 本文件钉住的就是那道闸（[bitmapBelongsToPage] / [visibleBitmapFor]，都是纯函数）：
 * - 停稳页 + 归属不符 ⇒ **绝不返回那个位图**（哪怕它非 null），退回该页**自己的**预载档；
 * - 停稳页 + 归属相符 ⇒ 返回它（不许一律降级到预载档 —— 那会让升档/缩放失去意义）；
 * - 邻页 ⇒ 恒走缓存档（共享位图只属于停稳页）；
 * - `under` 层与淡入 alpha 共用同一条判据（旧页的 `under` / 中间 alpha 都不许漏到新页上）。
 *
 * ⚠ 覆盖不到的（本项目只有 junit，无 Robolectric / compose-ui-test）：真实的**重组次数与帧
 * 调度**（"那一帧到底是 1 帧还是 2 帧"）、`Image` 参数在组合期取值的运行时行为、真机观感 ——
 * 见报告 §4。本文件钉的是**判据本身**，不是判据在设备上的时序。
 */
class Pv3StaleFrameTest {

    // ── ① 停稳页的归属校验（本卡的主修）──────────────────────────────────

    @Test
    fun settledPageNeverDrawsABitmapOwnedByAnotherPage() {
        // 切页那一帧的真实输入：page = 新页、shownUri / bitmap 还是**上一页**的。
        // 这正是用户看到的"上一张图闪现一下"—— 判据必须把它挡掉。
        val stale = "fit-of-1" // 上一页留在共享 state 里的像素
        val fresh = "preview-of-2" // 新页**自己的**预载档（主动预载 page±2 通常命中）
        val drawn = visibleBitmapFor(
            isSettledPage = true,
            pageUri = "content://media/2",
            shownUri = "content://media/1", // ← 归属不符
            bitmap = stale,
            cached = fresh,
        )
        assertSame("归属不符时必须画新页自己的预载档", fresh, drawn)
        assertTrue("绝不返回那个位图（哪怕它非 null）", drawn !== stale)
    }

    @Test
    fun aStaleBitmapIsNeverDrawnEvenWhenTheCacheMisses() {
        // 预载未命中（快速连滑超出 ±2 / 刚进页面）⇒ 画缓存档的降级结果是 null ⇒ **纯黑底**。
        // 底线：宁可黑底（配合 SPINNER_DELAY_MS 连转圈都不画），也**绝不画上一张的像素**。
        assertNull(
            "未命中预载也不是退回旧图的理由 —— 错图比黑底更糟",
            visibleBitmapFor(true, "content://media/2", "content://media/1", "fit-of-1", null),
        )
    }

    @Test
    fun settledPageKeepsItsOwnBitmapInsteadOfDowngradingToTheCache() {
        // 防过度修正：归属相符时必须画**位图本身**（Fit / 放大档），不能一律降级成预载档 ——
        // 那等于把 E2-22 的两段式与放大升档一起废掉（图会一直糊）。
        val own = "fit-of-2"
        val cached = "preview-of-2"
        assertSame(
            "归属相符 ⇒ 画自己的位图，不是缓存档",
            own,
            visibleBitmapFor(true, "content://media/2", "content://media/2", own, cached),
        )
        // 同页升档 / 缩放态的常规路径（`shownTier >= target` 早退那一条）不能被这道闸碰到
        assertTrue(bitmapBelongsToPage(true, "content://media/2", "content://media/2"))
    }

    @Test
    fun neighborPageAlwaysUsesTheCachedPreview() {
        // 共享位图讲的是"停稳页那一张"：邻页拿它就会画错图（与 pageScaleAt / pageShiftAt
        // 是同一道闸的精神）。哪怕 uri 恰好相等（用户滑走、切页 effect 还没跑完），也走缓存档。
        val cached = "preview-of-2"
        assertSame(
            "邻页恒用缓存档",
            cached,
            visibleBitmapFor(false, "content://media/2", "content://media/2", "bitmap", cached),
        )
        assertFalse(bitmapBelongsToPage(false, "content://media/2", "content://media/2"))
        assertFalse(bitmapBelongsToPage(false, "content://media/2", "content://media/1"))
    }

    @Test
    fun aBitmapWithoutAnOwnerIsNeverDrawn() {
        // 判据要保守：归属未知（shownUri 还没设过 / 该页 uri 已失效）时一律不画。
        // 两种输入在真实代码里都被 `frameAfterPageChange` 的成对赋值排除掉了，但判据本身
        // 不能依赖调用方的自律（否则将来某处漏一次赋值就是一张错图）。
        assertNull(visibleBitmapFor(true, "content://media/1", null, "mystery", null))
        assertNull(visibleBitmapFor(true, null, "content://media/1", "mystery", null))
        assertFalse(bitmapBelongsToPage(true, "content://media/1", null))
        assertFalse(bitmapBelongsToPage(true, null, "content://media/1"))
    }

    // ── ② 同一道闸的另外两个调用点（under 层 / 淡入 alpha）────────────────

    @Test
    fun underLayerFollowsTheSameOwnershipGate() {
        // `under` 也是**整页共享**的 state，清它的是解码 effect（异步）⇒ 切页那一帧它可能还是
        // 上一页的。调用点若只判 `p == page`，就会把上一张的像素当"下层"画到新页上 ——
        // 这是旧图闪现的**第二条独立路径**，与位图那条共用同一个判据。
        val owns = bitmapBelongsToPage(
            isSettledPage = true,
            pageUri = "content://media/2",
            shownUri = "content://media/1",
        )
        assertFalse("切页那一帧不画 under（它属于上一页）", owns)
        assertTrue(
            "同页升档（预览 → Fit）时 under 照画：它属于本页，是交叉淡入的下层",
            bitmapBelongsToPage(true, "content://media/2", "content://media/2"),
        )
    }

    @Test
    fun staleSettledPageIsFullyOpaque() {
        // 切页那一帧 `under` 可能还非 null、`fade` 可能停在中间值（垂死动画）。alpha 的闸门
        // 参数从 `p == page` 收紧成 `owns` 之后：归属不符 ⇒ 恒 1（新页的预载档不透明），
        // 不会出现"新图画出来了但半透明压在黑底上"的变暗一闪。
        val owns = bitmapBelongsToPage(true, "content://media/2", "content://media/1")
        for (fade in listOf(0f, 0.3f, 0.99f, 1f)) {
            assertEquals(
                "归属不符时 alpha 必须是 1（fade=$fade 是上一页垂死动画可能留下的值）",
                1f,
                pageAlphaAt(owns, fading = true, fade = fade),
                0f,
            )
        }
        // 归属相符时淡入行为不变（PV-2 的判据原样保留，不许回退）
        assertTrue(bitmapBelongsToPage(true, "content://media/2", "content://media/2"))
        assertEquals(0.4f, pageAlphaAt(true, fading = true, fade = 0.4f), 1e-6f)
        assertEquals(1f, pageAlphaAt(true, fading = false, fade = 0.4f), 0f)
    }
    /**
     * ★ 补 PV-3 自己上报的 **M3 覆盖缺口**：单测能钉住判据（纯函数），钉不住
     * "**组合产物里传的到底是不是那个判据的结果**" —— 把调用点整体绕开判据时，
     * 上面 7 例纯函数**全绿**（PV-3 实测 M3 没红）。本项目没有 Robolectric /
     * compose-ui-test，跑不了真实组合与帧调度，所以这里用**源码哨兵**钉住调用点
     * （与 `CameraPresetPanelUiTest` 的既有手法同源）。
     *
     * 判据：绘制点必须**真的**走 [visibleBitmapFor] / `owns`，而不是自己写
     * `if (p == page)`。谁把这三处改回旧口径，这里就红 —— 不用靠人肉审 1400 行。
     */
    @Test
    fun drawingSiteActuallyRoutesThroughTheOwnershipGate() {
        val src = File("src/main/java/com/filmkit/app/ui/PhotoViewScreen.kt").readText()
        assertTrue(
            "绘制点必须用 visibleBitmapFor 取这一帧要画的位图（绕开它 ⇒ 新页会画上一张的像素）",
            src.contains("visibleBitmapFor(p == page, pageUri, shownUri, bitmap, cached)"),
        )
        assertTrue(
            "`under` 层必须过归属校验 —— 它是旧页像素漏到新页的第二条路径",
            src.contains("if (owns) under?.let"),
        )
        assertTrue(
            "淡入 alpha 的闸门必须是 owns（只判 p == page 会让新页的预载档半透明压在黑底上）",
            src.contains("pageAlphaAt(owns, under != null, fade.value)"),
        )
        assertTrue(
            "几何判据必须仍是 p == page —— 缩放/平移是 E2-23 的另一道闸，不许被这次收紧带走",
            src.contains("viewerLayer(p == page, zoom, pan)"),
        )
    }
}
