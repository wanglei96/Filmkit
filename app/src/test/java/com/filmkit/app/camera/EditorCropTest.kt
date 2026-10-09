package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2-12 裁剪纯逻辑护栏（**E2-12b 收尾卡补**：E2-12 的实现子代理交付前失败，未留 Kotlin 单测）。
 *
 * 为什么这些必须被钉住：裁剪有三处**只有真机/导出才能暴露**的坑，而三处的判据全都落在纯函数里，
 * JVM 单测能直接覆盖（不需要 GL / Bitmap / MediaStore）：
 *  1. **夹紧与归一化**：框越界 ⇒ 引擎按越界 UV 采样（画面外 = 黑边/拉伸），且比例锁定在
 *     非方形画幅下要在「像素比」与「归一化比」之间换算（差一个 viewH/viewW 就锁不住）；
 *  2. **旋转与坐标换算**：UI 的框在「旋转后画面」坐标里，引擎的 `cropUV()` 在「原图 UV」里，
 *     两侧的 90° 置换必须**互为逆**——写反一个轴，导出图的方向/位置就错（[cropSpecMatchesEngineCropUv]）；
 *  3. **`crop.isFull` 与直通**：裁剪是几何量、**不进 FilmParams** ⇒ E2-9 的 `paramsUnchanged`
 *     看不见它。若"只裁了图"没被显式算作"有修改"，导出会走直通把**原片字节原样复制**出去，
 *     裁剪被静默丢弃（架构师点名的防呆点）。
 *
 * 工作目录 = app/（gradle 单测的约定，与 [EditorStyleWhitelistTest] 读 `../core/...` 同一口径）。
 */
class EditorCropTest {

    // ── ① 夹紧 / 归一化 ───────────────────────────────────────────────────

    /** 移动整框：任何位移都必须留在画面内（越界 = 引擎采样到画面外）。 */
    @Test
    fun cropViewMoveClampsToPicture() {
        val v = CropView(0.2f, 0.3f, 0.5f, 0.4f)
        val far = cropViewMove(v, 99f, 99f)
        assertEquals(1f - v.w, far.l, 1e-6f)
        assertEquals(1f - v.h, far.t, 1e-6f)
        val neg = cropViewMove(v, -99f, -99f)
        assertEquals(0f, neg.l, 1e-6f)
        assertEquals(0f, neg.t, 1e-6f)
        // 小位移按量平移、尺寸不动
        val small = cropViewMove(v, 0.1f, -0.1f)
        assertEquals(0.3f, small.l, 1e-6f)
        assertEquals(0.2f, small.t, 1e-6f)
        assertEquals(v.w, small.w, 0f)
        // 全幅无处可动（不出现负的可用位移 ⇒ 不能把框推出去）
        assertEquals(CropView.FULL, cropViewMove(CropView.FULL, 0.5f, -0.5f))
    }

    /** 8 个手柄 × 极端拖动的全组合：框不得翻转（w/h ≤ 0）、不得出画面、rot 不得被改。 */
    @Test
    fun cropViewScaleNeverFlipsOrLeavesPicture() {
        val viewW = 1200
        val viewH = 900
        for (v0 in listOf(CropView.FULL, CropView(0.2f, 0.3f, 0.5f, 0.4f))) {
            for (h in CropHandle.entries) {
                for (d in listOf(-10f, 10f)) {
                    for (aspect in listOf(null, 1f, 16f / 9f, 3f / 4f)) {
                        val tag = "$h d=$d aspect=$aspect"
                        val out = cropViewScale(v0, h, d, d, aspect, viewW, viewH)
                        assertTrue("$tag 宽必须 > 0（不得翻转）", out.w > 0f)
                        assertTrue("$tag 高必须 > 0（不得翻转）", out.h > 0f)
                        assertTrue("$tag 左边界越界", out.l >= -1e-5f)
                        assertTrue("$tag 上边界越界", out.t >= -1e-5f)
                        assertTrue("$tag 右边界越界", out.r <= 1f + 1e-5f)
                        assertTrue("$tag 下边界越界", out.b <= 1f + 1e-5f)
                        assertEquals("$tag 缩放不得改旋转", v0.rot, out.rot)
                    }
                }
            }
        }
    }

    /** 缩放到极限时边长收敛到最小像素尺寸（不是 0）：零面积框会让引擎输出退化。 */
    @Test
    fun cropViewScaleRespectsMinPxOnCollapse() {
        val viewW = 1000
        val viewH = 800
        val v = CropView(0.4f, 0.4f, 0.2f, 0.2f)
        val minPx = 32f
        // R 手柄向左狠拖 ⇒ 宽度收到下限 l + minPx/viewW
        val narrow = cropViewScale(v, CropHandle.R, -99f, 0f, null, viewW, viewH, minPx)
        assertEquals(minPx / viewW, narrow.w, 1e-6f)
        // B 手柄向上狠拖 ⇒ 高度收到下限
        val flat = cropViewScale(v, CropHandle.B, 0f, -99f, null, viewW, viewH, minPx)
        assertEquals(minPx / viewH, flat.h, 1e-6f)
    }

    /** 比例锁定：非方形画幅下锁的是**像素**比（归一化空间里要除以 viewW/viewH 的比）。 */
    @Test
    fun cropViewWithAspectKeepsPixelRatioOnNonSquareView() {
        val viewW = 1200
        val viewH = 900
        for (aspect in listOf(1f, 4f / 3f, 3f / 4f, 16f / 9f)) {
            val out = cropViewWithAspect(CropView.FULL, aspect, viewW, viewH)
            val px = (out.w * viewW) / (out.h * viewH)
            assertEquals("像素宽高比必须等于锁定值", aspect, px, 1e-4f)
            assertTrue(out.w <= 1f + 1e-6f && out.h <= 1f + 1e-6f)
        }
        // 「原图」= 当前（旋转后）画面比例 ⇒ 不裁
        assertEquals(4f / 3f, CropAspect.ORIGINAL.ratio(viewW, viewH)!!, 1e-6f)
        assertNull("自由 = 不锁", CropAspect.FREE.ratio(viewW, viewH))
        val free = CropView(0.1f, 0.1f, 0.5f, 0.5f)
        assertEquals("不锁比例 ⇒ 原样返回", free, cropViewWithAspect(free, null, viewW, viewH))
    }

    /** 切换比例：中心保持、放不下时整体缩小（不得靠平移把框挤出画面）。 */
    @Test
    fun cropViewWithAspectKeepsCenterAndShrinksWhenTooBig() {
        // 竖幅视图 + 16:9 ⇒ 归一化高度远小于 1，必须先缩宽
        val viewW = 800
        val viewH = 1600
        val v = CropView(0.2f, 0.35f, 0.6f, 0.3f)
        val out = cropViewWithAspect(v, 16f / 9f, viewW, viewH)
        assertEquals("中心不得漂移", (v.l + v.r) / 2f, (out.l + out.r) / 2f, 1e-5f)
        assertEquals("中心不得漂移", (v.t + v.b) / 2f, (out.t + out.b) / 2f, 1e-5f)
        assertEquals(16f / 9f, (out.w * viewW) / (out.h * viewH), 1e-4f)
        assertTrue(out.l >= 0f && out.t >= 0f && out.r <= 1f + 1e-5f && out.b <= 1f + 1e-5f)
    }

    /** 退化输入（w/h ≈ 0）不得产出 0 宽或越界的框 —— 有 1e-4 兜底就必须守住它。 */
    @Test
    fun cropViewWithAspectClampsDegenerateBox() {
        val out = cropViewWithAspect(CropView(0.5f, 0.5f, 0f, 0f), 1f, 1000, 1000)
        assertTrue("退化框必须被抬到正尺寸", out.w > 0f && out.h > 0f)
        assertTrue(out.l >= 0f && out.t >= 0f && out.r <= 1f + 1e-5f && out.b <= 1f + 1e-5f)
        assertEquals(1f, out.w / out.h, 1e-3f)
    }

    /** UI 框 → 引擎入参时的夹紧：越界框的右/下边界必须收到 1，不能让引擎采到画面外。 */
    @Test
    fun cropSpecOfClampsOutOfRangeBox() {
        val spec = cropSpecOf(CropView(0.6f, 0.7f, 0.8f, 0.9f), 4080, 3072)
        assertEquals(0.4f, spec.cw, 1e-6f)
        assertEquals(0.3f, spec.ch, 1e-6f)
        assertEquals(1f, spec.x0 + spec.cw, 1e-6f)
        assertEquals(1f, spec.y0 + spec.ch, 1e-6f)
        // 完全在画面外的框（零面积）不抛异常、退化为 0 尺寸（引擎侧再兜到 1px）
        val zero = cropSpecOf(CropView(0.3f, 0.3f, 0f, 0f), 4080, 3072)
        assertEquals(0f, zero.cw, 0f)
        assertEquals(0f, zero.ch, 0f)
    }

    // ── ② 旋转与坐标换算 ──────────────────────────────────────────────────

    /** 旋转后画面尺寸：奇数步交换宽高，偶数步不变（含负数/超界 rot 的取模）。 */
    @Test
    fun cropViewSizeSwapsOnlyOnOddRotation() {
        assertEquals(4080 to 3072, cropViewSize(4080, 3072, 0))
        assertEquals(3072 to 4080, cropViewSize(4080, 3072, 1))
        assertEquals(4080 to 3072, cropViewSize(4080, 3072, 2))
        assertEquals(3072 to 4080, cropViewSize(4080, 3072, 3))
        assertEquals("超界 rot 取模", cropViewSize(4080, 3072, 5), cropViewSize(4080, 3072, 1))
        // ★ E2-12c：负 rot 先归一化再判奇偶（Kotlin `%` 保号 ⇒ `-1 % 2 == -1` 会被判成偶数）
        assertEquals("-1 ≡ 3（奇数）", 3072 to 4080, cropViewSize(4080, 3072, -1))
        assertEquals("-2 ≡ 2（偶数）", 4080 to 3072, cropViewSize(4080, 3072, -2))
        assertEquals("-3 ≡ 1（奇数）", 3072 to 4080, cropViewSize(4080, 3072, -3))
        assertEquals("-4 ≡ 0（偶数）", 4080 to 3072, cropViewSize(4080, 3072, -4))
    }

    /** 旋转按钮：4 步回到原状态，且 rot 恒在 0..3（不能让 rot 无界增长到引擎未定义分支）。 */
    @Test
    fun cropViewRotateCyclesFourSteps() {
        var v = CropView.FULL
        val seen = mutableListOf<Int>()
        repeat(4) {
            v = cropViewRotate(v)
            seen += v.rot
        }
        assertEquals(listOf(1, 2, 3, 0), seen)
        assertEquals("4 步必须回到原状态", CropView.FULL, v)

        // ★ E2-12c：画面转 ⇒ 框**跟随内容**转（以前"只改 rot、框不动" ⇒ 框住的画面区域
        // 变了，锁定比例时更会直接锁不住 —— 见 cropViewRotateKeepsLockedPixelRatio）。
        val boxed = CropView(0.1f, 0.2f, 0.3f, 0.4f)
        assertEquals(CropView(0.4f, 0.1f, 0.4f, 0.3f, 1), cropViewRotate(boxed))
        val before = cropSpecOf(boxed, 4080, 3072)
        val after = cropSpecOf(cropViewRotate(boxed), 4080, 3072)
        assertEquals("覆盖的原图区域 x0 不变", before.x0, after.x0, 1e-6f)
        assertEquals("覆盖的原图区域 y0 不变", before.y0, after.y0, 1e-6f)
        assertEquals("覆盖的原图区域 cw 不变", before.cw, after.cw, 1e-6f)
        assertEquals("覆盖的原图区域 ch 不变", before.ch, after.ch, 1e-6f)
        assertEquals("rot 只前进一步", normalizeRot(before.rot + 1), after.rot)
    }

    /**
     * ★ E2-12c 核心：**锁定比例 + 旋转 ⇒ 框的像素比仍等于锁定比例**。
     *
     * 缺陷原状（E2-12b 上报）：锁 1:1（源图 4080×3072 ⇒ 框 3072×3072）后旋转 90°，画面
     * 宽高交换而框没重排 ⇒ 引擎入参变成 2313×4080（比值 0.567，期望 1.0）—— 用户直接点
     * 「确认」就导出非 1:1。这里按**导出口径**（[cropSpecOf] + [CropSpec.outputSize]）断言，
     * 正是缺陷实测的那条路径。
     */
    @Test
    fun cropViewRotateKeepsLockedPixelRatio() {
        val srcW = 4080
        val srcH = 3072
        val locked = listOf(
            CropAspect.SQUARE, CropAspect.R4_3, CropAspect.R3_4, CropAspect.R16_9, CropAspect.ORIGINAL,
        )
        for (a in locked) {
            for (steps in 1..3) {
                // 模拟「点比例锁定 → 连点 steps 次旋转」
                var rot = 0
                var v = cropViewWithAspect(CropView.FULL, a.ratio(srcW, srcH), srcW, srcH)
                repeat(steps) {
                    rot = (rot + 1) % 4
                    val (vw, vh) = cropViewSize(srcW, srcH, rot)
                    v = cropViewRotate(v, a.ratio(vw, vh), vw, vh)
                }
                val (vw, vh) = cropViewSize(srcW, srcH, rot)
                val aspect = a.ratio(vw, vh)!!
                val tag = "${a.label} 旋转 $steps 步"
                assertEquals("$tag 框的像素比", aspect, (v.w * vw) / (v.h * vh), 1e-3f)
                // 导出口径：引擎入参会按 outputSize 出图，这里的比值必须还是锁定值
                val (ow, oh) = cropSpecOf(v, srcW, srcH).outputSize(srcW, srcH)
                assertEquals("$tag 导出的像素比", aspect, ow.toFloat() / oh, 5e-3f)
                assertEquals("$tag rot", rot, v.rot)
                assertNoOverflow(tag, v)
            }
        }

        // 缺陷实测口径（源图 4080×3072）：锁 1:1 ⇒ 框 3072×3072；旋转 90° 后必须**还是正方形**。
        // 修正前：画面宽高交换而框没重排 ⇒ 导出的引擎入参 2313×4080（比值 0.567）；
        // 修正后：保持中心按新朝向重排 ⇒ 2313×2313（转 180° 回 3072×3072）。
        val (vw1, vh1) = cropViewSize(srcW, srcH, 1)
        val locked11 = cropViewWithAspect(CropView.FULL, 1f, srcW, srcH)
        assertEquals("锁 1:1（未旋转）", 3072 to 3072, specSize(locked11, srcW, srcH))
        val square = cropViewRotate(locked11, 1f, vw1, vh1)
        assertEquals("★ 旋转 90° 后必须还是正方形", 2313 to 2313, specSize(square, srcW, srcH))
        val (vw2, vh2) = cropViewSize(srcW, srcH, 2)
        val square180 = cropViewRotate(square, 1f, vw2, vh2)
        assertEquals("旋转 180°", 3072 to 3072, specSize(square180, srcW, srcH))
    }

    /** 导出口径的像素尺寸（= 引擎入参 [CropSpec] + [CropSpec.outputSize]）。 */
    private fun specSize(v: CropView, srcW: Int, srcH: Int): Pair<Int, Int> =
        cropSpecOf(v, srcW, srcH).outputSize(srcW, srcH)

    /** 锁定比例旋转：**中心不漂**（用户选的那块地儿不跳走）+ 任何方向都不越界。 */
    @Test
    fun cropViewRotateLockedKeepsCenterAndStaysInside() {
        val srcW = 4080
        val srcH = 3072
        val start = CropView(0.25f, 0.25f, 0.5f, 0.5f)
        val cx = (start.l + start.r) / 2f
        val cy = (start.t + start.b) / 2f
        // 锁定比例才有"保持中心"的约定；自由是"框跟随内容转"（中心本来就会随画面走）
        for (a in CropAspect.entries.filter { it != CropAspect.FREE }) {
            var v = start
            var rot = 0
            repeat(3) {
                rot = (rot + 1) % 4
                val (vw, vh) = cropViewSize(srcW, srcH, rot)
                v = cropViewRotate(v, a.ratio(vw, vh), vw, vh)
                val tag = "${a.label} rot=$rot"
                assertEquals("$tag 中心 x 不得漂移", cx, (v.l + v.r) / 2f, 1e-5f)
                assertEquals("$tag 中心 y 不得漂移", cy, (v.t + v.b) / 2f, 1e-5f)
                assertNoOverflow(tag, v)
            }
        }
    }

    /**
     * ★ E2-12c ②：负 rot（`-1` / `-3`）下 [cropViewSize] / `CropSpec.outputSize` 的判据
     * 必须与 [cropSpecOf] 一致 —— 三处都走 [normalizeRot]，不能再出现"一处归一化、
     * 另一处 `rot % 2 == 1` 判反"的静默不一致。
     */
    @Test
    fun negativeRotNormalizedEverywhere() {
        val box = CropSpec(0.25f, 0.25f, 0.5f, 0.5f)
        assertEquals("rot=-1 ≡ 3（奇数 ⇒ 交换）", 1536 to 2040, box.copy(rot = -1).outputSize(4080, 3072))
        assertEquals("rot=-2 ≡ 2（偶数）", 2040 to 1536, box.copy(rot = -2).outputSize(4080, 3072))
        assertEquals("rot=-3 ≡ 1（奇数 ⇒ 交换）", 1536 to 2040, box.copy(rot = -3).outputSize(4080, 3072))
        assertEquals("rot=-4 ≡ 0（偶数）", 2040 to 1536, box.copy(rot = -4).outputSize(4080, 3072))

        for (rot in listOf(-1, -3, -4, -5, 4, 5, 7)) {
            val v = CropView(0.12f, 0.30f, 0.45f, 0.25f, rot)
            assertEquals(
                "rot=$rot 的 cropSpecOf 必须与归一化值一致",
                cropSpecOf(v.copy(rot = normalizeRot(rot)), 4080, 3072),
                cropSpecOf(v, 4080, 3072),
            )
            assertEquals(
                "rot=$rot 的 outputSize 必须与归一化值一致",
                box.copy(rot = normalizeRot(rot)).outputSize(4080, 3072),
                box.copy(rot = rot).outputSize(4080, 3072),
            )
            assertEquals(
                "rot=$rot 的 cropViewSize 必须与归一化值一致",
                cropViewSize(4080, 3072, normalizeRot(rot)),
                cropViewSize(4080, 3072, rot),
            )
            assertEquals(
                "rot=$rot 旋转后 rot 仍须落在 0..3（引擎只认这四路）",
                normalizeRot(rot + 1),
                cropViewRotate(v).rot,
            )
        }
        // 负 rot 与它的等价正 rot 旋转结果完全相同（几何变换与当前 rot 无关）
        assertEquals(
            cropViewRotate(CropView(0.1f, 0.2f, 0.3f, 0.4f, 3)),
            cropViewRotate(CropView(0.1f, 0.2f, 0.3f, 0.4f, -1)),
        )
    }

    /** 框必须留在画面内（越界 ⇒ 引擎按越界 UV 采样 = 黑边/拉伸）。 */
    private fun assertNoOverflow(tag: String, v: CropView) {
        assertTrue("$tag 左边界越界", v.l >= -1e-5f)
        assertTrue("$tag 上边界越界", v.t >= -1e-5f)
        assertTrue("$tag 右边界越界", v.r <= 1f + 1e-5f)
        assertTrue("$tag 下边界越界", v.b <= 1f + 1e-5f)
    }

    /** 已确认裁剪 → 再次进入裁剪模式的草稿框：4 个方向都必须精确往返（否则"框会跳"）。 */
    @Test
    fun cropRoundTripThroughSpecAllRotations() {
        for (rot in 0..3) {
            val v = CropView(0.12f, 0.30f, 0.45f, 0.25f, rot)
            val back = cropViewOf(cropSpecOf(v, 4080, 3072))
            assertEquals("rot=$rot l", v.l, back.l, 1e-6f)
            assertEquals("rot=$rot t", v.t, back.t, 1e-6f)
            assertEquals("rot=$rot w", v.w, back.w, 1e-6f)
            assertEquals("rot=$rot h", v.h, back.h, 1e-6f)
            assertEquals("rot=$rot rot", v.rot, back.rot)
        }
    }

    /**
     * ★ 本卡最硬的一条：**UI 的 [cropSpecOf] 与引擎 `cropUV()` 的 90° 置换必须互为逆**。
     *
     * 判据（不靠人工目视、也不是把公式抄两遍）：从 `FrameRenderer.cpp` 里解析出 `cropUV` 的
     * 4 条分支（= 引擎把「输出坐标」映射回「原图 UV」的置换），再用**同一个**置换验证恒等式 ——
     *
     *   用户框内任一点 o（旋转后画面坐标）⇒ 它在输出图上的位置 o′ = ((o.x−l)/w, (o.y−t)/h)
     *   引擎把 o′ 映射回的原图 UV  `spec.origin + perm(o′)·spec.size`
     *   必须等于该点在整幅旋转画面里的原图 UV  `perm(o)`。
     *
     * 任一侧写反一个轴 / 用错一个 rot 分支 ⇒ 立刻红。引擎改了 `cropUV` 而 UI 没跟 ⇒ 也红。
     */
    @Test
    fun cropSpecMatchesEngineCropUv() {
        val perm = engineCropUvPermutations()
        var worstWrong = 0f
        for (rot in 0..3) {
            val v = CropView(0.12f, 0.30f, 0.45f, 0.25f, rot)
            val spec = cropSpecOf(v, 4080, 3072)
            val m = perm.getValue(rot)
            val wrong = perm.getValue((rot + 2) % 4) // 区分度对照：换成对面那个方向的置换
            for (i in 0..4) {
                for (j in 0..4) {
                    val ox = v.l + v.w * i / 4f
                    val oy = v.t + v.h * j / 4f
                    val px = (ox - v.l) / v.w
                    val py = (oy - v.t) / v.h
                    val (ma, mb) = m(px, py)
                    val ux = spec.x0 + ma * spec.cw
                    val uy = spec.y0 + mb * spec.ch
                    val (ex, ey) = m(ox, oy)
                    assertEquals("rot=$rot 采样($i,$j) 原图 UV.x", ex, ux, 1e-5f)
                    assertEquals("rot=$rot 采样($i,$j) 原图 UV.y", ey, uy, 1e-5f)
                    val (wa, wb) = wrong(px, py)
                    worstWrong = maxOf(
                        worstWrong,
                        abs(ex - (spec.x0 + wa * spec.cw)),
                        abs(ey - (spec.y0 + wb * spec.ch)),
                    )
                }
            }
        }
        // 恒真检查：判据必须能区分"置换写反"（对面方向的偏差量级 ≈ 0.1~1，远大于 1e-5 容差）
        assertTrue("区分度对照：置换写反时偏差必须显著（实测 $worstWrong）", worstWrong > 0.1f)
    }

    /** 奇数步旋转后：引擎入参的宽高必须交换（否则输出尺寸与内容朝向对不上）。 */
    @Test
    fun cropSpecSwapsSizeOnOddRotation() {
        val v = CropView(0.1f, 0.2f, 0.5f, 0.3f)
        assertEquals("rot=0 不交换", 0.5f, cropSpecOf(v.copy(rot = 0), 4080, 3072).cw, 1e-6f)
        assertEquals("rot=2 不交换", 0.5f, cropSpecOf(v.copy(rot = 2), 4080, 3072).cw, 1e-6f)
        // 奇数步：引擎入参的 cw == 框的 h、ch == 框的 w
        for (rot in listOf(1, 3)) {
            val s = cropSpecOf(v.copy(rot = rot), 4080, 3072)
            assertEquals("rot=$rot cw", v.h, s.cw, 1e-6f)
            assertEquals("rot=$rot ch", v.w, s.ch, 1e-6f)
        }
    }

    // ── ③ crop.isFull 判据（直通/取消确认的判据基石） ──────────────────────

    /** 全幅只有"恒等"一种：任何一项偏离都必须判为非全幅（漏判 = 修改被丢弃）。 */
    @Test
    fun cropSpecIsFullOnlyWhenIdentity() {
        assertTrue(CropSpec.FULL.isFull)
        assertTrue(CropSpec().isFull)
        assertFalse("裁剪原点 x", CropSpec(x0 = 0.01f).isFull)
        assertFalse("裁剪原点 y", CropSpec(y0 = 0.01f).isFull)
        assertFalse("裁剪宽度", CropSpec(cw = 0.99f).isFull)
        assertFalse("裁剪高度", CropSpec(ch = 0.99f).isFull)
        assertFalse("只旋转不裁剪", CropSpec(rot = 1).isFull)
        assertFalse("只旋转 270°", CropSpec(rot = 3).isFull)
    }

    /** JNI 入参：全幅走 null（引擎恒等零回归路径）、非全幅按 {x0,y0,cw,ch} 下发。 */
    @Test
    fun cropSpecUvOrNullOnlyWhenFull() {
        assertNull("全幅必须传 null（= 引擎 nullptr 恒等路径）", CropSpec.FULL.uvOrNull())
        assertArrayEquals(
            floatArrayOf(0.1f, 0.2f, 0.5f, 0.3f),
            CropSpec(0.1f, 0.2f, 0.5f, 0.3f).uvOrNull(),
            0f,
        )
    }

    /** 输出尺寸 = 裁剪矩形的像素尺寸（+ 奇数步交换），且退化输入至少 1px。 */
    @Test
    fun cropSpecOutputSize() {
        assertEquals(4080 to 3072, CropSpec.FULL.outputSize(4080, 3072))
        assertEquals(2040 to 1536, CropSpec(0.25f, 0.25f, 0.5f, 0.5f).outputSize(4080, 3072))
        assertEquals(1536 to 2040, CropSpec(0.25f, 0.25f, 0.5f, 0.5f, 1).outputSize(4080, 3072))
        assertEquals(2040 to 1536, CropSpec(0.25f, 0.25f, 0.5f, 0.5f, 2).outputSize(4080, 3072))
        // 退化：0 尺寸不得产出 0 像素输出
        val degenerate = CropSpec(0.3f, 0.3f, 0f, 0f).outputSize(4080, 3072)
        assertEquals(1 to 1, degenerate)
    }

    // ── ④ 裁剪 × 直通（E2-9）的交互：架构师点名的防呆点 ────────────────────

    /** 只裁了图（FilmParams 一位没动）⇒ 「取消」必须弹二次确认（否则修改无声丢失）。 */
    @Test
    fun cropOnlyChangeNeedsDiscardConfirm() {
        val base = CameraPresets.standard()
        val same = base.copyOf()
        assertTrue("前置：参数确实没动", paramsUnchanged(same, base))
        assertFalse("参数没动 + 没裁剪 ⇒ 不弹窗", discardNeedsConfirm(same, base, cropDirty = false))
        assertTrue("★ 参数没动但裁了图 ⇒ 必须弹窗", discardNeedsConfirm(same, base, cropDirty = true))
        // 首帧就绪前（null）：没有可丢的参数修改 —— 但裁剪已经生效时仍必须确认
        assertFalse(discardNeedsConfirm(null, null, cropDirty = false))
        assertTrue(discardNeedsConfirm(null, null, cropDirty = true))
        // 对照：参数真改了也照旧弹窗
        val changed = base.copyOf().also { it[0] = it[0] + 1.0 }
        assertTrue(discardNeedsConfirm(changed, base, cropDirty = false))
    }

    /**
     * ★ 「裁了图但 FilmParams 没动」**必须**判为"有修改" ⇒ 不得走直通。
     *
     * 判据镜像 `EditorScreen.exportNow` 的那一行（`paramsUnchanged(...) && crop.isFull`）：
     * 这一条不是重复实现，而是把"裁剪必须并进 dirty 判据"这个约定钉在测试里 ——
     * 直通 = 复制原片字节，漏判的后果是用户点「完成」拿回原图。
     */
    @Test
    fun cropOnlyChangeBlocksPassthrough() {
        val base = CameraPresets.standard()
        val snapshot = base.copyOf()
        val cropped = cropSpecOf(CropView(0.1f, 0.1f, 0.5f, 0.5f), 4080, 3072)

        assertTrue("前置：FilmParams 没动", paramsUnchanged(snapshot, base))
        assertFalse("★ 裁了图 ⇒ 不算「无修改」", exportUnchanged(snapshot, base, cropped))
        assertFalse(
            "★ 不得直通（否则导出原片字节，裁剪被静默丢弃）",
            passthroughAllowed(exportUnchanged(snapshot, base, cropped), jpegSource = true),
        )
        // 对照：没裁 ⇒ 直通收益必须保留（E2-9 的 JPEG 另存不能被本判据误伤）
        assertTrue(exportUnchanged(snapshot, base, CropSpec.FULL))
        assertTrue(passthroughAllowed(exportUnchanged(snapshot, base, CropSpec.FULL), jpegSource = true))
        // 对照：只旋转不裁剪同样不是全幅
        assertFalse(
            "只旋转 90° 也是有修改",
            exportUnchanged(snapshot, base, CropSpec.FULL.copy(rot = 1)),
        )
    }

    /** 镜像 `EditorScreen.exportNow` 的 dirty 判据（打开时的基准 + 裁剪是否全幅）。 */
    private fun exportUnchanged(snapshot: DoubleArray, open: DoubleArray, crop: CropSpec): Boolean =
        paramsUnchanged(snapshot, open) && crop.isFull

    // ── ⑤ 屏幕换算（letterbox 偏移） ──────────────────────────────────────

    /** 框与手柄的屏幕位置必须带上 Fit 的 letterbox 偏移与显示缩放（漏了偏移 = 框画偏）。 */
    @Test
    fun cropScreenRectAndHandlesFollowFitOffset() {
        // 竖幅图（1000×2000）放进横向预览盒（1000×500）⇒ 左右黑边各 375px
        val fit = imageFitRect(1000f, 500f, 1000, 2000)
        assertEquals(375f, fit.left, 1e-3f)
        assertEquals(250f, fit.width, 1e-3f)
        val v = CropView(0.2f, 0.4f, 0.5f, 0.1f)
        val s = cropScreenRect(v, fit)
        assertEquals(fit.left + 0.2f * fit.width, s.left, 1e-3f)
        assertEquals(fit.top + 0.4f * fit.height, s.top, 1e-3f)
        assertEquals(0.5f * fit.width, s.width, 1e-3f)
        assertEquals(0.1f * fit.height, s.height, 1e-3f)

        val pts = cropHandlePoints(v, fit)
        assertEquals("8 个手柄（4 角 + 4 边）", 8, pts.size)
        assertEquals("0 号 = 左上", s.left to s.top, pts[0])
        assertEquals("2 号 = 右上", s.left + s.width to s.top, pts[2])
        assertEquals("4 号 = 右下", s.left + s.width to s.top + s.height, pts[4])
        assertEquals("6 号 = 左下", s.left to s.top + s.height, pts[6])
        assertEquals("1 号 = 上边中点", s.left + s.width / 2f to s.top, pts[1])

        // 命中：贴近左上角 ⇒ TL；框正中（离所有手柄 > 阈值）⇒ null；框外 ⇒ 不在框内
        assertEquals(CropHandle.TL, cropHitHandle(v, fit, s.left + 2f, s.top + 2f, 8f))
        assertNull(
            "框正中不该抢手柄",
            cropHitHandle(v, fit, s.left + s.width / 2f, s.top + s.height / 2f, 8f),
        )
        assertTrue(cropInside(v, fit, s.left + s.width / 2f, s.top + s.height / 2f))
        assertFalse("黑边区域（框外）不算框内", cropInside(v, fit, s.left - 5f, s.top + 5f))
    }

    // ── 从 FrameRenderer.cpp 解析引擎的 cropUV 置换（结构哨兵，不靠人工抄公式） ──

    /** rot → 「输出坐标 o（裁剪后归一化）→ 原图局部 UV」的置换（**不含** origin/size）。 */
    private fun engineCropUvPermutations(): Map<Int, (Float, Float) -> Pair<Float, Float>> {
        val src = File("../core/engine/src/render/FrameRenderer.cpp").readText()
        val body = Regex("vec2 cropUV\\(vec2 o\\) \\{(.*?)\\n\\}", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.groupValues?.get(1)
            ?: error("FrameRenderer.cpp 里找不到 cropUV 函数体")
        val thresholds = mapOf(2.5 to 3, 1.5 to 2, 0.5 to 1)
        val out = HashMap<Int, (Float, Float) -> Pair<Float, Float>>()
        Regex("uCropRot > ([0-9.]+)\\) return uCropOrigin \\+ vec2\\(([^,]+), ([^)]+)\\) \\* uCropSize")
            .findAll(body).forEach { m ->
                val rot = thresholds[m.groupValues[1].toDouble()]
                    ?: error("cropUV 出现未知的 rot 阈值 ${m.groupValues[1]}")
                val fx = glslTerm(m.groupValues[2])
                val fy = glslTerm(m.groupValues[3])
                out[rot] = { ox, oy -> fx(ox, oy) to fy(ox, oy) }
            }
        if (Regex("return uCropOrigin \\+ o \\* uCropSize").containsMatchIn(body)) {
            out[0] = { ox, oy -> ox to oy }
        }
        assertEquals("cropUV 必须解析出 rot 0..3 四路", setOf(0, 1, 2, 3), out.keys)
        return out
    }

    /** 只认 cropUV 里实际出现过的四个分量表达式；出现别的 ⇒ 说明 shader 改了写法，测试要跟上。 */
    private fun glslTerm(e: String): (Float, Float) -> Float = when (e.trim()) {
        "o.x" -> { ox, _ -> ox }
        "o.y" -> { _, oy -> oy }
        "1.0 - o.x" -> { ox, _ -> 1f - ox }
        "1.0 - o.y" -> { _, oy -> 1f - oy }
        else -> error("cropUV 出现未预期的表达式：$e")
    }
}
