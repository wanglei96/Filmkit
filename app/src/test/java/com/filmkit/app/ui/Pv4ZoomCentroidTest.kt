package com.filmkit.app.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ PV-4（用户 2026-10-12 真机："**相册缩放要从两指中心缩，现在都是从画面中心缩放，很奇怪**"）：
 * 绘制层 `graphicsLayer { scaleX = scaleY = z; translationX/Y = pan }` 的 pivot 是默认的
 * `TransformOrigin.Center` ⇒ 只改 `z` 就是绕画面中心缩放。修法是在捏合时**同时补偿 pan**
 * （[zoomAboutCentroid]）。
 *
 * 本文件钉的是那条补偿的**不动点性质**：两指中心处对应的内容点，在缩放前后**停在屏幕上同一个
 * 位置**（容差 1e-3）。这是"以两指中心为锚点"的数学定义 —— 把补偿去掉（回到绕中心缩放）
 * 这条断言立刻变红（mutation 证据见报告 §4）。
 *
 * 屏幕位置模型与绘制层逐字对应（居中坐标系，pivot = 容器中心 `ctr`）：
 * `screen(x) = ctr + z·(x − ctr) + pan`。
 *
 * ⚠ 覆盖不到的（本项目只有 junit，无 Robolectric / compose-ui-test）：`calculateCentroid` /
 * `calculateZoom` / `calculatePan` 在真机事件流里的实际取值、每帧 state 写入的重组/重绘节奏、
 * 手指移动时的手感（锚点是否"跟手"）、以及 clampPan 在边界上的观感 —— 见报告 §5。
 * 本文件钉的是**公式本身**，不是它在设备上的时序与手感。
 */
class Pv4ZoomCentroidTest {

    private val eps = 0.001f

    /** 真机口径：1080×1920 的图片区，中心 (540, 960)。 */
    private val center = Offset(540f, 960f)

    /** 一个缩放步：pan/z 是缩放**前**的状态，centroid 是两指中心（current 帧）。 */
    private data class Step(
        val pan: Offset,
        val centroid: Offset,
        val z1: Float,
        val z2: Float,
    )

    /** 内容坐标 `x` 在屏幕上的位置（与 `graphicsLayer` 的 pivot = center 一致）。 */
    private fun screenOf(content: Offset, z: Float, pan: Offset): Offset =
        center + (content - center) * z + pan

    /** 反解：缩放前正好落在 [centroid] 的那个内容点。 */
    private fun contentUnder(step: Step): Offset =
        center + (step.centroid - center - step.pan) / step.z1

    /** 不动点断言：`centroid` 处的内容点，套用补偿后仍落在 `centroid`。 */
    private fun assertFixedPoint(step: Step) {
        val content = contentUnder(step)
        val pan2 = zoomAboutCentroid(step.pan, step.centroid, center, step.z1, step.z2)
        val screen = screenOf(content, step.z2, pan2)
        assertEquals("x 漂移: ${step}", step.centroid.x, screen.x, eps)
        assertEquals("y 漂移: ${step}", step.centroid.y, screen.y, eps)
    }

    // ── ① 不动点性质（本卡的主修）───────────────────────────────────────

    @Test
    fun pinchAnchorsTheContentPointUnderTheFingers() {
        val steps = listOf(
            // Fit 起步、两指中心偏右下（真机最常见的"想放大右下角"）
            Step(Offset.Zero, Offset(700f, 1200f), 1f, 2f),
            // Fit 起步、两指中心偏左上
            Step(Offset.Zero, Offset(300f, 500f), 1f, 3f),
            // 放大态 + 已有平移，继续放大（z1 > 1，pan 非零）
            Step(Offset(-120f, 80f), Offset(400f, 700f), 2.5f, 4f),
            // 捏回（z2 < z1）
            Step(Offset(200f, -150f), Offset(820f, 1400f), 3f, 1.2f),
            // 顶到 MAX_ZOOM 附近再放大
            Step(Offset(90f, 30f), Offset(600f, 1100f), 4.5f, 5f),
            // 两指中心恰好落在容器中心（pan 非零 ⇒ 补偿 = pan·(z₂/z₁ − 1) ≠ 0，见 ②）
            Step(Offset(60f, -40f), center, 2f, 3f),
            // 极端：pan 已经顶到夹紧边界（内容 2× ⇒ 左右各可移 540）
            Step(Offset(540f, 0f), Offset(1080f, 960f), 2f, 2.2f),
        )
        steps.forEach { assertFixedPoint(it) }
    }

    @Test
    fun withoutCompensationTheAnchorPointDrifts() {
        // 反向守卫：证明这条补偿**确实在做事** —— 用旧的"绕画面中心缩放"（pan 原样不动），
        // 两指中心处的内容点会偏离手指。这正是用户真机看到的现象。
        val step = Step(Offset.Zero, Offset(700f, 1200f), 1f, 2f)
        val screen = screenOf(contentUnder(step), step.z2, step.pan)
        val drift = (screen - step.centroid).getDistance()
        assertTrue("旧行为应当漂移，实际 drift=$drift", drift > 1f)
        assertNotEquals(step.pan, zoomAboutCentroid(step.pan, step.centroid, center, step.z1, step.z2))
    }

    // ── ② 退化与边界（不许引入新的跳变）────────────────────────────────

    @Test
    fun centroidAtTheViewportCenterWithNoPanIsTheOldBehaviour() {
        // Fit 态（pan = 0）+ 两指中心正好是画面中心 ⇒ 补偿项为 0 ⇒ 与"绕中心缩放"逐位相同。
        assertEquals(Offset.Zero, zoomAboutCentroid(Offset.Zero, center, center, 1f, 3f))
    }

    @Test
    fun existingPanIsRescaledEvenWhenTheCentroidIsTheViewportCenter() {
        // 已有平移时**补偿不为 0**，这是对的：pivot 处的内容点早被 pan 推离容器中心，要让
        // "屏幕中心处的内容点"不动，pan 必须按 z₂/z₁ 等比放大（p₂ = p₁·z₂/z₁）。
        // 若这里返回原 pan，图会绕着容器中心"滑走"一格 —— 不动点断言（①）覆盖同一件事。
        val pan = Offset(120f, -60f)
        val p = zoomAboutCentroid(pan, center, center, 2f, 4f)
        assertEquals(240f, p.x, eps)
        assertEquals(-120f, p.y, eps)
        val content = contentUnder(Step(pan, center, 2f, 4f))
        assertEquals(center.x, screenOf(content, 4f, p).x, eps)
        assertEquals(center.y, screenOf(content, 4f, p).y, eps)
    }

    @Test
    fun zoomFactorOneLeavesPanUntouched() {
        // z1 == z2：已顶到 MAX_ZOOM 还继续捏 / factor 非法（clampZoom 原值返回）⇒ 补偿为 0。
        // 没有这条，用户顶到 5× 后继续捏，图会以两指中心为轴持续漂移。
        val pan = Offset(200f, -80f)
        assertEquals(pan, zoomAboutCentroid(pan, Offset(300f, 400f), center, 5f, 5f))
        assertEquals(pan, zoomAboutCentroid(pan, Offset(300f, 400f), center, 2f, 2f))
    }

    @Test
    fun nonFiniteInputNeverProducesNaN() {
        // calculateCentroid() 在指针被消费 / 无按下指针时返回 Offset.Unspecified（NaN,NaN）。
        // 一旦把 NaN 传进 graphicsLayer，整张图直接消失（见 clampZoom 的同款守卫）。
        val pan = Offset(10f, 20f)
        val nanPan = Offset.Unspecified
        assertEquals(pan, zoomAboutCentroid(pan, Offset.Unspecified, center, 1f, 2f))
        assertEquals(nanPan, zoomAboutCentroid(nanPan, Offset(1f, 2f), center, 1f, 2f))
        assertEquals(pan, zoomAboutCentroid(pan, Offset(1f, 2f), Offset.Unspecified, 1f, 2f))
        assertEquals(pan, zoomAboutCentroid(pan, Offset(1f, 2f), center, 0f, 2f))
        assertEquals(pan, zoomAboutCentroid(pan, Offset(1f, 2f), center, Float.NaN, 2f))
    }

    // ── ③ 方向（锚点在右半 ⇒ 内容被推向左侧放大，符合直觉）──────────────

    @Test
    fun zoomingInAroundTheRightHalfPushesContentLeft() {
        // 两指中心在画面右侧 ⇒ 放大后想看清的是右半部分 ⇒ 内容整体左移（pan.x < 0），
        // 右下同理（pan.y < 0）。方向反了就是"缩放往反方向跑"，比不修还糟。
        val p = zoomAboutCentroid(Offset.Zero, Offset(900f, 1500f), center, 1f, 2f)
        assertTrue("pan.x 应为负，实际 ${p.x}", p.x < 0f)
        assertTrue("pan.y 应为负，实际 ${p.y}", p.y < 0f)
        // 补偿量 = (c − ctr)·(1 − z2/z1) = (360, 540)·(−1) = (−360, −540)
        assertEquals(-360f, p.x, eps)
        assertEquals(-540f, p.y, eps)
    }

    @Test
    fun zoomingOutAroundTheLeftHalfPullsContentLeft() {
        // 捏回（z2 < z1）同理，只是补偿量减半：anchor.x = 180 − 540 = −360、factor = 1 − 1/2 = 0.5
        // ⇒ pan.x = −180（内容左移）。若这里写成右移，左侧那个内容点就会被"向中心收拢"带跑
        // —— 不动点断言（①）与这条方向断言互相印证。
        val p = zoomAboutCentroid(Offset.Zero, Offset(180f, 960f), center, 2f, 1f)
        assertEquals(-180f, p.x, eps)
        assertEquals(0f, p.y, eps)
    }
}
