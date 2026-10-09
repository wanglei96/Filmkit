package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2-7 编辑器「局部」组的纯逻辑护栏：模式 ↔ 槽位、**预览坐标换算（含 letterbox）**、
 * 轮廓几何、A/B 开关语义、坏数据兜底。
 *
 * 为什么这些必须被单测钉住（真机跑不了的都在这儿）：
 *  1. **坐标换算的 letterbox 偏移**：预览按 Fit 显示，竖拍 3:4 在横屏预览盒里上下各有
 *     黑边。少减一次偏移，用户拖到画面顶部时 mask 中心其实在画外 —— 表现是"拖了没对准"，
 *     而且**只有非方形画幅才复现**（方形画幅黑边为 0，测不出来）。
 *  2. **轮廓与拖动必须共用同一套换算**：本测试直接验 `radialOutline` 的中心 ==
 *     `localCenterToScreen(拖动写入的中心)` —— 两处一旦分叉，轮廓与实际 mask 不重合。
 *  3. **正圆语义**：`ry = rx × W/H`（**不是** × H/W）—— 写反了竖拍图上的轮廓是椭圆，
 *     而引擎侧（FrameRenderer 的 `uLocalRadius`）也会一起错，⑬h 才照得出来。
 *  4. **A/B 语义**：`[79] ≥ 0.5` 且（`[86] ≠ 0` 或 `[87] ≠ 1`）才生效 —— 与引擎侧
 *     `uLocalOn` 同一判据；判错会让用户"选了类型却什么都没发生"或"没调强度画面却变了"。
 */
class EditorLocalUiTest {

    // ── 模式 [79] ──────────────────────────────────────────────────────────

    @Test
    fun modeMapsToSlotsAndBack() {
        assertEquals(0.0, localTypeSlot(LocalMode.OFF), 1e-9)
        assertEquals(1.0, localTypeSlot(LocalMode.RADIAL), 1e-9)
        assertEquals(2.0, localTypeSlot(LocalMode.LINEAR), 1e-9)
        assertEquals(LocalMode.OFF, localTypeUi(0.0))
        assertEquals(LocalMode.RADIAL, localTypeUi(1.0))
        assertEquals(LocalMode.LINEAR, localTypeUi(2.0))
        // 引擎判据是区间（>=0.5 / >=1.5），不是等值：UI 必须按同一区间解释，
        // 否则手改 prefs 或将来改量程时会出现"引擎生效、UI 显示关"的错位
        assertEquals(LocalMode.RADIAL, localTypeUi(0.5))
        assertEquals(LocalMode.RADIAL, localTypeUi(1.499))
        assertEquals(LocalMode.LINEAR, localTypeUi(1.5))
        assertEquals(LocalMode.OFF, localTypeUi(0.499))
    }

    @Test
    fun badModeDataFallsBackToOff() {
        // 坏数据（NaN / 负数 / 超出契约的 3）一律当"关"：显示一个引擎侧不存在的模式
        // 比"关"更糟（用户会以为在调局部，实际什么都没发生）
        assertEquals(LocalMode.OFF, localTypeUi(Double.NaN))
        assertEquals(LocalMode.OFF, localTypeUi(Double.POSITIVE_INFINITY))
        assertEquals(LocalMode.OFF, localTypeUi(-1.0))
        assertEquals(LocalMode.OFF, localTypeUi(2.5))
        assertEquals(LocalMode.OFF, localTypeUi(99.0))
        // UI 侧越界模式同样写 0（防呆）
        assertEquals(0.0, localTypeSlot(3), 1e-9)
        assertEquals(0.0, localTypeSlot(-1), 1e-9)
        // 诊断字段的字符串映射
        assertEquals("off", localTypeName(0.0))
        assertEquals("radial", localTypeName(1.0))
        assertEquals("linear", localTypeName(2.0))
    }

    // ── 中心 [80][81] ─────────────────────────────────────────────────────

    @Test
    fun centerSlotsClampAndSurviveBadData() {
        assertEquals(0.25 to 0.75, localCenterSlot(0.25f, 0.75f))
        // 夹紧：拖动只会产生 0..1，但坏快照不能把画外坐标带进引擎
        assertEquals(1.0 to 0.0, localCenterSlot(1.4f, -0.2f))
        // 坏数据 → 画面中心（不是 0,0 —— 那会把 mask 挪到左上角）
        assertEquals(0.5 to 0.5, localCenterSlot(Float.NaN, Float.POSITIVE_INFINITY))
        // 槽位 → UI 同款夹紧
        assertEquals(0.5f to 1f, localCenterUi(Double.NaN, 9.0))
        assertEquals(0f to 1f, localCenterUi(-3.0, 3.0))
    }

    // ── 坐标换算（本卡最容易错的一处）──────────────────────────────────────

    @Test
    fun fitRectPutsLetterboxOnTheShortSide() {
        // 竖拍 3:4（300×400）放进 100×100 的预览盒：scale = min(100/300, 100/400) = 0.25
        // ⇒ 图像 75×100，剩余宽度 25 均分到**左右**（竖拍在方形盒里是左右黑边）
        val portrait = imageFitRect(100f, 100f, 300, 400)
        assertEquals(12.5f, portrait.left, 1e-4f)
        assertEquals(0f, portrait.top, 1e-4f)
        assertEquals(75f, portrait.width, 1e-4f)
        assertEquals(100f, portrait.height, 1e-4f)
        // 横拍 4:3 放进同一个盒子：黑边改到上下（这一条防"只处理左右黑边"）
        val landscape = imageFitRect(100f, 100f, 400, 300)
        assertEquals(0f, landscape.left, 1e-4f)
        assertEquals(12.5f, landscape.top, 1e-4f)
        assertEquals(100f, landscape.width, 1e-4f)
        assertEquals(75f, landscape.height, 1e-4f)
        // 退化输入（尺寸为 0 / 非法）不得 NaN：返回空矩形，换算函数会退回画面中心
        val degenerate = imageFitRect(0f, 0f, 0, 0)
        assertEquals(0f, degenerate.width, 1e-4f)
        assertEquals(0f, degenerate.height, 1e-4f)
    }

    @Test
    fun screenToImageSubtractsLetterboxOffset() {
        // 竖拍 3:4 放进 1080×1920 的预览盒：scale = min(1080/300, 1920/400) = 3.6
        // ⇒ 图像 1080×1440，上下各 240px 黑边
        val rect = imageFitRect(1080f, 1920f, 300, 400)
        assertEquals(0f, rect.left, 1e-3f)
        assertEquals(240f, rect.top, 1e-3f)
        assertEquals(1080f, rect.width, 1e-3f)
        assertEquals(1440f, rect.height, 1e-3f)
        // 图像左上角（黑边下沿）⇒ (0,0)；图像正中 ⇒ (0.5,0.5)
        assertEquals(0f to 0f, localCenterFromScreen(0f, 240f, rect))
        assertEquals(0.5f to 0.5f, localCenterFromScreen(540f, 960f, rect))
        // ★ 关键：屏幕 (540, 240) 在**黑边上**，换算后 y = 0（图像顶行），不是 0.125
        //（少减偏移就会得到 0.125 ⇒ 用户拖到画面顶部时 mask 中心其实在画内偏下）
        val (u, v) = localCenterFromScreen(540f, 240f, rect)
        assertEquals(0.5f, u, 1e-5f)
        assertEquals(0f, v, 1e-5f)
    }

    @Test
    fun screenToImageClampsToImageBounds() {
        val rect = imageFitRect(1080f, 1920f, 300, 400)
        // 拖到黑边里（画外）：夹到 0 / 1，不能让中心跑出画面（引擎侧 mask 会整块消失）
        assertEquals(0f to 0f, localCenterFromScreen(-500f, -500f, rect))
        assertEquals(1f to 1f, localCenterFromScreen(5000f, 5000f, rect))
        // 右下角内侧一点：接近 1 但不越界
        val (u, v) = localCenterFromScreen(1079f, 1919f, rect)
        assertTrue(u > 0.99f && u <= 1f)
        assertTrue(v > 0.99f && v <= 1f)
    }

    @Test
    fun screenRoundTripKeepsCenter() {
        // 拖动写入的中心 → 轮廓中心 必须回到同一个屏幕点（轮廓与 mask 重合的前提）
        val rect = imageFitRect(1080f, 1920f, 300, 400)
        for (su in listOf(0f, 270f, 540f, 810f, 1080f)) {
            for (sv in listOf(240f, 600f, 960f, 1320f, 1680f)) {
                val (u, v) = localCenterFromScreen(su, sv, rect)
                val (bx, by) = localCenterToScreen(u, v, rect)
                assertEquals("x 往返漂了", su, bx, 1e-3f)
                assertEquals("y 往返漂了", sv, by, 1e-3f)
            }
        }
    }

    // ── 轮廓几何（正圆 + 条带方向）────────────────────────────────────────

    @Test
    fun radialOutlineIsACircleInPixels() {
        // 竖拍 3:4：W=300 H=400 ⇒ aspect = H/W = 4/3；半径 0.2 ⇒ rx = 0.2×1080 = 216px、
        // ry = 216 ÷ (4/3) = 162px ⇒ 换算回**图像像素**两个方向都是 0.2×300 = 60px ⇒ 正圆。
        // 写反（×H/W）会得到 ry = 288 ⇒ 竖椭圆，正是 E2-7 要修的那个 bug。
        val rect = imageFitRect(1080f, 1920f, 300, 400)
        val aspect = 400f / 300f
        val o = radialOutline(0.5f, 0.5f, 0.2f, 0.5f, rect, aspect)
        assertEquals(540f, o.cx, 1e-3f)
        assertEquals(960f, o.cy, 1e-3f)
        assertEquals(216f, o.rx, 1e-3f)
        assertEquals(162f, o.ry, 1e-3f)
        // 正圆判据：rx / aspect == ry（= rx × W/H），且 rx == r × 显示宽
        assertEquals(o.rx / aspect, o.ry, 1e-3f)
        assertFalse(o.linear)
        // 中心与拖动换算一致（轮廓中心 == 用户拖到的地方）
        val (u, v) = localCenterFromScreen(540f, 960f, rect)
        val o2 = radialOutline(u, v, 0.2f, 0.5f, rect, aspect)
        assertEquals(o.cx, o2.cx, 1e-3f)
        assertEquals(o.cy, o2.cy, 1e-3f)
        // 坏 aspect 不得产生 NaN（退回正圆）
        val bad = radialOutline(0.5f, 0.5f, 0.2f, 0.5f, rect, Float.NaN)
        assertEquals(bad.rx, bad.ry, 1e-3f)
    }

    @Test
    fun linearOutlineEdgesArePerpendicularToTheNormal() {
        // [84] = 0 ⇒ 条带**竖直**（法线水平）⇒ 边线竖直（dx=0, dy=±1），
        // 两条边线沿法线（x 方向）偏移 ±宽度
        val rect = imageFitRect(1080f, 1920f, 300, 400)
        val v0 = linearOutline(0.5f, 0.5f, 0.25f, 0.0, 0.5f, rect)
        assertTrue(v0.linear)
        assertEquals(0f, v0.lineDx, 1e-5f)
        assertEquals(1f, abs(v0.lineDy), 1e-5f)
        assertEquals(0.25f * 1080f, v0.half, 1e-3f)
        // [84] = π/2 ⇒ 条带水平（法线竖直）⇒ 边线水平
        val h = linearOutline(0.5f, 0.5f, 0.25f, Math.PI / 2, 0.5f, rect)
        assertEquals(1f, abs(h.lineDx), 1e-5f)
        assertEquals(0f, h.lineDy, 1e-5f)
        // 坏角度按 0 处理（不产生 NaN 轮廓）
        val bad = linearOutline(0.5f, 0.5f, 0.25f, Double.NaN, 0.5f, rect)
        assertEquals(0f, bad.lineDx, 1e-5f)
    }

    // ── 六条滑杆的槽位往返 ────────────────────────────────────────────────

    @Test
    fun radiusFeatherWidthRoundTripKeepsEveryReadout() {
        for (i in 0..100) {
            val ui = i / 100f
            assertEquals("半径 $i% 往返漂了", i, (localRadiusUi(localRadiusSlot(ui)) * 100).roundToInt())
            assertEquals("羽化 $i% 往返漂了", i, (localFeatherUi(localFeatherSlot(ui)) * 100).roundToInt())
            assertEquals("宽度 $i% 往返漂了", i, (localWidthUi(localWidthSlot(ui)) * 100).roundToInt())
        }
    }

    @Test
    fun radiusFeatherWidthClampToRange() {
        assertEquals(0.0, localRadiusSlot(-1f), 1e-9)
        assertEquals(1.0, localRadiusSlot(3f), 1e-9)
        assertEquals(0.0, localFeatherSlot(-1f), 1e-9)
        assertEquals(1.0, localFeatherSlot(3f), 1e-9)
        assertEquals(0.0, localWidthSlot(-1f), 1e-9)
        assertEquals(1.0, localWidthSlot(3f), 1e-9)
        // 坏数据回落默认（半径 0.35 / 羽化 0.5 / 宽度 0.25 —— 与 CameraPresets 占位值一致）
        assertEquals(0.35f, localRadiusUi(Double.NaN), 1e-6f)
        assertEquals(0.5f, localFeatherUi(Double.POSITIVE_INFINITY), 1e-6f)
        assertEquals(0.25f, localWidthUi(Double.NaN), 1e-6f)
        // 越界槽位夹到量程（显示不越界）
        assertEquals(1f, localRadiusUi(9.0), 1e-6f)
        assertEquals(0f, localFeatherUi(-9.0), 1e-6f)
    }

    @Test
    fun angleMapsToRadiansAndBack() {
        // UI 0..1 = 显示 0..180°；槽位 0..π 弧度
        assertEquals(0.0, localAngleSlot(0f), 1e-9)
        assertEquals(Math.PI, localAngleSlot(1f), 1e-9)
        assertEquals(Math.PI / 2, localAngleSlot(0.5f), 1e-9)
        assertEquals(0f, localAngleUi(0.0), 1e-6f)
        assertEquals(1f, localAngleUi(Math.PI), 1e-6f)
        // 读数（度）往返不漂
        for (deg in 0..180 step 15) {
            val ui = deg / 180f
            assertEquals(deg, (localAngleUi(localAngleSlot(ui)) * 180f).roundToInt())
        }
        // 坏数据 / 越界
        assertEquals(0f, localAngleUi(Double.NaN), 1e-6f)
        assertEquals(1f, localAngleUi(9.0), 1e-6f)
        assertEquals(0.0, localAngleSlot(-1f), 1e-9)
    }

    @Test
    fun localEvSharesGlobalExposureMapping() {
        // 局部曝光与全局曝光**同一换算**（都是线性域 EV，±100% = ±3 EV）
        assertEquals(0.0, localEvSlot(0f), 1e-9)
        assertEquals(3.0, localEvSlot(1f), 1e-9)
        assertEquals(-3.0, localEvSlot(-1f), 1e-9)
        assertEquals(0f, localEvUi(Double.NaN), 1e-6f)
        assertEquals(-1f, localEvUi(-3.0), 1e-6f)
        for (i in -100..100) {
            val ui = i / 100f
            assertEquals("局部曝光 $i% 往返漂了", i, (localEvUi(localEvSlot(ui)) * 100f).roundToInt())
        }
    }

    @Test
    fun localSatRoundTripAndIdentityIsOne() {
        assertEquals(0.0, localSatSlot(0f), 1e-9)
        assertEquals(2.0, localSatSlot(2f), 1e-9)
        assertEquals(1f, localSatUi(1.0), 1e-6f)
        // 坏数据回落到**恒等 1.0**（不是 0 —— 0 = 完全去饱和，是"有效果"的那一端）
        assertEquals(1f, localSatUi(Double.NaN), 1e-6f)
        assertEquals(2f, localSatUi(9.0), 1e-6f)
        assertEquals(0f, localSatUi(-1.0), 1e-6f)
    }

    // ── A/B 开关语义（ADR 交互规格第 6 条）─────────────────────────────────

    @Test
    fun localIsActiveOnlyWithModeAndStrength() {
        val p = CameraPresets.standard()
        // 默认（[79]=0，ev=0，sat=1）⇒ 关
        assertFalse(localActive(p))
        // 选了径向但强度全恒等 ⇒ **仍不生效**（"选了类型但没调强度，画面不该变"）
        p[79] = 1.0
        assertFalse(localActive(p))
        // 局部曝光非 0 ⇒ 生效
        p[86] = -0.5
        assertTrue(localActive(p))
        p[86] = 0.0
        // 局部饱和度非 1 ⇒ 生效
        p[87] = 0.0
        assertTrue(localActive(p))
        // 模式关回去 ⇒ 无论强度如何都不生效（A/B 开关 = [79]）
        p[79] = 0.0
        assertFalse(localActive(p))
        // 线性模式同样成立
        p[79] = 2.0
        assertTrue(localActive(p))
    }

    // ── 重置 ──────────────────────────────────────────────────────────────

    @Test
    fun resetLocalRestoresGeometryButKeepsModeAndStrength() {
        val p = CameraPresets.standard()
        p[79] = 1.0
        p[80] = 0.12
        p[81] = 0.9
        p[82] = 0.8
        p[83] = 0.1
        p[84] = 2.5
        p[85] = 0.9
        p[86] = -2.0
        p[87] = 0.3
        resetLocalGeometry(p)
        // 几何回默认
        assertEquals(0.5, p[80], 1e-9)
        assertEquals(0.5, p[81], 1e-9)
        assertEquals(0.35, p[82], 1e-9)
        assertEquals(0.5, p[83], 1e-9)
        assertEquals(0.0, p[84], 1e-9)
        assertEquals(0.25, p[85], 1e-9)
        // ★ 模式与强度**不动**：重置不是"关掉局部"（强度清零会让画面突然变回去）
        assertEquals(1.0, p[79], 1e-9)
        assertEquals(-2.0, p[86], 1e-9)
        assertEquals(0.3, p[87], 1e-9)
        // 其它组一个槽位都不许碰（快照继承来的风格参数）
        assertEquals(0.0, p[0], 1e-9)
        assertEquals(1.0, p[34], 1e-9)
        assertEquals(0.0, p[66], 1e-9)
    }
}
