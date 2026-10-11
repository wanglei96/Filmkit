package com.filmkit.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ PV-1（用户 2026-10-11："相册滑动图片还是会闪一下"）的核心判据：**预览档与目标档之间的
 * 清晰度阶跃有多大**。
 *
 * 用户看到的"闪"不是空白帧、不是位置跳变、不是颜色差异，而是**放大倍数**：
 * `ContentScale.Fit` 把预览档铺满显示区时的放大倍率决定了它有多糊。E2-22 的固定 360px 请求在
 * 12MP 原图上落到 `inSampleSize = 8`（实际只解 500×375）⇒ 放大 **2.12–3.29×** 才铺满，随后
 * 目标档（缩小 0.54×）替换上去 ⇒ 一次肉眼可见的清晰度跳变。
 *
 * 这里钉死修复后的口径：预览请求 = 容器的一半（[previewRequestFor]）⇒ 采样台阶比目标档低一档
 * ⇒ 放大倍数 ≤ **2.0×**。**这条断言是有牙齿的**：把 [previewRequestFor] 改回 `(360, 360)`，
 * 同一批条件下每一格都 ≥ 2.12×，测试立刻红（mutation 证据见报告 §4）。
 *
 * ⚠ 本文件只覆盖**能算的部分**（解码尺寸 / 放大倍数 / 缓存容量）。淡入的观感、真机解码耗时、
 * 内存峰值 JVM 测不到 —— 那些在报告 §5 标了"需真机确认"。
 */
class PhotoViewPreviewTest {

    // ── 判据的输入：真机口径的容器（与 ImageDecodeTest / PhotoViewZoomTest 同一批数字）──

    /** 三种常见屏宽的**图片区**像素尺寸（顶栏/底栏已扣掉） */
    private val viewports = listOf(
        "1080p" to (1080 to 1700),
        "1440p" to (1440 to 2260),
        "1644p" to (1644 to 2600),
    )

    /** 三种画幅的 12MP 原图 + 一张比容器还小的小图（退化档） */
    private val sources = listOf(
        "横 12MP" to (4000 to 3000),
        "竖 12MP" to (3000 to 4000),
        "全景" to (8000 to 1000),
        "小图" to (800 to 600),
    )

    /**
     * [decodeFit] 的**纯计算部分**（`ImageDecode.kt:91-96` 去掉 Context / IO 的那两行）：
     * 请求尺寸 → Fit 尺寸 → 采样率 → 实际解码尺寸。
     *
     * ⚠ 这是**复刻**（`decodeFit` 要 Context 才能读原图尺寸，JVM 里调不了）。复刻的只有"先 Fit
     * 再算采样率"这一个组合 —— [fitSizeFor] / [sampleSizeFor] 本身是**被测的真实实现**（它们在
     * `ImageDecodeTest` 里另有直测）。下面的判据因此仍然有牙齿：判据是"放大倍数"，它的两个输入
     * （显示尺寸、解码尺寸）一个来自真实 [fitSizeFor]、一个来自真实 [sampleSizeFor]。
     */
    private data class Plan(val sample: Int, val w: Int, val h: Int)

    private fun planFor(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Plan {
        val (fw, fh) = fitSizeFor(srcW, srcH, reqW, reqH)
        val s = sampleSizeFor(srcW, srcH, fw, fh)
        return Plan(s, srcW / s, srcH / s)
    }

    // ── ① 预览请求 = 容器的一半 ────────────────────────────────────────────

    @Test
    fun previewRequestIsHalfTheViewport() {
        assertEquals(540 to 850, previewRequestFor(1080, 1700))
        assertEquals(720 to 1130, previewRequestFor(1440, 2260))
        // 奇数边向下取整（不向上 —— 宁可少解一行像素，也不越过"低一档"的台阶）
        assertEquals(541 to 851, previewRequestFor(1083, 1703))
    }

    @Test
    fun degenerateViewportNeverYieldsZeroRequest() {
        // 容器未测量（首帧 onSizeChanged 之前）⇒ 夹到 1，**绝不返回 0**：
        // `decodeFit(ctx, uri, 0, 0)` 的 fitSizeFor 返回 (0,0) ⇒ 走"不采样"兜底 = 全分辨率 48MB
        // 解码 —— 那正是 E2-22 要消灭的东西，也是本页最贵的一次事故。
        for ((w, h) in listOf(0 to 0, 1 to 1, -100 to -100, 0 to 1700, 1080 to 0)) {
            val (rw, rh) = previewRequestFor(w, h)
            assertTrue("previewRequestFor($w, $h) = $rw×$rh 必须都 ≥ 1", rw >= 1 && rh >= 1)
        }
    }

    // ── ② 核心回归：预览档被放大到显示尺寸的倍数 ───────────────────────────

    @Test
    fun previewTierIsNeverUpscaledBeyondTwoTimes() {
        for ((vn, v) in viewports) {
            for ((sn, src) in sources) {
                val (vw, vh) = v
                val (srcW, srcH) = src
                val (pw, ph) = previewRequestFor(vw, vh)
                val p = planFor(srcW, srcH, pw, ph)
                // 预览档被 `ContentScale.Fit` 铺满容器后的显示宽（用**解码后**的尺寸算 ——
                // 显示的永远是位图，不是原图；取整会让两者差 1px 量级）
                val displayW = fitSizeFor(p.w, p.h, vw, vh).first
                val upscale = displayW.toDouble() / p.w
                // 原图本身就小 ⇒ 预览与目标档落在同一个采样台阶（预览**就是**目标档）⇒
                // 根本没有"换档"，也就没有阶跃可谈（那种糊是原图的糊，不是闪）
                val t = planFor(srcW, srcH, vw, vh)
                if (p.sample == t.sample) {
                    println("$vn / $sn: 显示宽 $displayW | 预览 = 目标档 ${p.w}×${p.h}(sample=${p.sample}) 跳过")
                    continue
                }
                println(
                    "$vn / $sn: 显示宽 $displayW | 预览 ${p.w}×${p.h}(sample=${p.sample}) " +
                        "放大 ${"%.2f".format(upscale)}×",
                )
                assertTrue(
                    "$vn / $sn：预览档被放大 ${"%.2f".format(upscale)}× 才铺满显示区（上限 2.0×）" +
                        " —— 超过就是用户报的\"闪一下\"（旧口径 360px 在同一批条件下是 2.12–3.29×）",
                    upscale <= 2.0,
                )
            }
        }
    }

    @Test
    fun previewTierSitsExactlyOneSamplingStepBelowTheTarget() {
        for ((vn, v) in viewports) {
            for ((sn, src) in sources) {
                val (vw, vh) = v
                val (srcW, srcH) = src
                val (pw, ph) = previewRequestFor(vw, vh)
                val p = planFor(srcW, srcH, pw, ph)   // 预览档
                val t = planFor(srcW, srcH, vw, vh)   // 目标档（Fit）
                // 原图本身就比预览请求还小 ⇒ 两档落在同一个采样台阶，预览**就是**目标档
                // （800×600 在 1080p 容器上：两档都是 sample=1）⇒ 零阶跃，没什么可断言的
                if (p.sample == t.sample) {
                    assertEquals("$vn / $sn：同台阶时两档必须逐像素相同", t.w to t.h, p.w to p.h)
                    continue
                }
                assertEquals(
                    "$vn / $sn：预览档必须比目标档低**整整一档**（采样率 ×2 —— sample 越大档越低）",
                    p.sample,
                    t.sample * 2,
                )
                // 低一档的物理含义 = 解码像素量 1/4（这是"预览先上屏"之所以快的全部依据）
                assertTrue(
                    "$vn / $sn：预览档像素量应 ≤ 目标档的 1/4",
                    p.w.toLong() * p.h * 4 <= t.w.toLong() * t.h,
                )
            }
        }
    }

    // ── ③ 相邻页预载缓存装得下 3 张新预览档 ─────────────────────────────────

    @Test
    fun previewCacheHoldsThreePreviewsOfTheNewTier() {
        assertEquals("缓存要容下 当前页 + 左右邻页", 3, PREVIEW_CACHE_COUNT)
        // 用**真实函数**算 1080p 容器上的预览档字节数（不是拍一个数字进来）
        val (pw, ph) = previewRequestFor(1080, 1700)
        val p = planFor(4000, 3000, pw, ph)
        val bytes = p.w.toLong() * p.h * 4 // ARGB_8888
        assertTrue(
            "3 张预览（${bytes * PREVIEW_CACHE_COUNT / 1024 / 1024}MB）装不进上限" +
                "（${PREVIEW_CACHE_BYTES / 1024 / 1024}MB）—— 预载会静默塌成 1 张，滑动时邻页没有" +
                "现成预览，反而要现解（空白帧）",
            bytes * PREVIEW_CACHE_COUNT <= PREVIEW_CACHE_BYTES,
        )
    }
}
