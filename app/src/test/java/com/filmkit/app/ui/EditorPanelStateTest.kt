package com.filmkit.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2-15 的纯逻辑护栏：**面板收起/展开状态机** + **原图对比状态机**。
 *
 * 为什么只测这些：把手拖动、BackHandler 分层、预览区布局要真机（Compose + 手势），
 * JVM 单测跑不了。这里守住三条"错了用户一眼看见"的规格：
 *
 *  1. **返回键分层**（用户原话："系统返回手势要可以收起编辑面板…收不起来"）：展开 ⇒
 *     先收起（消费返回）；已收起 ⇒ 交宿主退出编辑器。搞反的后果是"面板还开着就退出了"
 *     或者"收起后返回键失灵，退不出去"。
 *  2. **把手阈值**：抖动不切换、超过阈值才切换，且**方向正确**（下拉收起 / 上拉展开）。
 *  3. **看原图 = 按住看（E2-17）**：按下即见、松手即回，**不是 toggle**；松手路径唯一
 *     （手势被取消也复位）—— 错的后果是"按了没反应"或"画面永久钉在原图"。
 */
class EditorPanelStateTest {

    // ── 返回键分层 ────────────────────────────────────────────────────────

    @Test
    fun backCollapsesPanelWhenExpanded() {
        val next = EditorPanelState.onBack(EditorPanelState(expanded = true))
        assertNotNull("展开时返回必须被面板消费（先收起，不退编辑器）", next)
        assertFalse("消费后的状态 = 已收起", next!!.expanded)
    }

    @Test
    fun backFallsThroughToHostWhenAlreadyCollapsed() {
        assertNull(
            "已收起时面板不消费返回 ⇒ 事件落到宿主（退出编辑器）",
            EditorPanelState.onBack(EditorPanelState(expanded = false)),
        )
    }

    @Test
    fun backIsLayeredAcrossAWholeGesture() {
        // 完整链路：展开 → 第一次返回收起 → 第二次返回交给宿主（一次返回绝不直接退出）
        var s = EditorPanelState()
        assertTrue(s.expanded)
        s = EditorPanelState.onBack(s) ?: error("第一次返回必须收起面板")
        assertFalse(s.expanded)
        assertNull("第二次返回才轮到退出编辑器", EditorPanelState.onBack(s))
    }

    // ── 把手拖动 / 点击 ───────────────────────────────────────────────────

    @Test
    fun dragBeyondThresholdCollapsesAndExpands() {
        val expanded = EditorPanelState(expanded = true)
        val collapsed = EditorPanelState(expanded = false)
        // 下拉超过阈值（dy 为正）⇒ 收起
        assertEquals(false, EditorPanelState.afterDrag(expanded, 30f, 24f)?.expanded)
        // 上拉超过阈值（dy 为负）⇒ 展开
        assertEquals(true, EditorPanelState.afterDrag(collapsed, -30f, 24f)?.expanded)
        // 恰好等于阈值 ⇒ 切换（判据是 >= 阈值）
        assertEquals(false, EditorPanelState.afterDrag(expanded, 24f, 24f)?.expanded)
    }

    @Test
    fun dragWithinThresholdKeepsState() {
        val expanded = EditorPanelState(expanded = true)
        assertNull("抖动不该收起面板", EditorPanelState.afterDrag(expanded, 10f, 24f))
        assertNull("反方向抖动同理", EditorPanelState.afterDrag(expanded, -10f, 24f))
        assertNull("零位移不切换", EditorPanelState.afterDrag(expanded, 0f, 24f))
    }

    @Test
    fun dragDirectionIsIdempotentAtEnds() {
        // 已经展开还继续上拉 / 已经收起还继续下拉：状态不变（不产生"反向弹回"）
        assertEquals(true, EditorPanelState.afterDrag(EditorPanelState(true), -99f, 24f)?.expanded)
        assertEquals(false, EditorPanelState.afterDrag(EditorPanelState(false), 99f, 24f)?.expanded)
    }

    @Test
    fun badDragDataNeverSwitches() {
        val expanded = EditorPanelState(expanded = true)
        assertNull(EditorPanelState.afterDrag(expanded, Float.NaN, 24f))
        assertNull(EditorPanelState.afterDrag(expanded, Float.POSITIVE_INFINITY, 24f))
        assertNull("阈值非正 = 坏配置，宁可不动", EditorPanelState.afterDrag(expanded, 100f, 0f))
        assertNull(EditorPanelState.afterDrag(expanded, 100f, Float.NaN))
    }

    @Test
    fun handleTapTogglesBothWays() {
        val a = EditorPanelState().toggled()
        assertFalse(a.expanded)
        assertTrue("点击是往返切换，不是单向收起", a.toggled().expanded)
        assertEquals("默认打开编辑器 = 展开", true, EditorPanelState().expanded)
    }

    // ── 看原图：按住看 / 松手回（E2-17）─────────────────────────────────────

    @Test
    fun originalViewIsOffByDefaultAndFollowsPress() {
        val off = OriginalViewState()
        assertFalse("默认显示效果图（没人按着按钮）", off.showing)
        val held = off.press(true)
        assertTrue("按住按钮 ⇒ 显示原图", held.showing)
        assertFalse("松手 ⇒ 立刻回效果图", held.press(false).showing)
    }

    @Test
    fun pressIsNotAToggle() {
        // ★ 本卡的核心回归哨兵：**连续两次按住-松手**必须每次都"按住显示、松手恢复"。
        // toggle 语义（E2-15 的旧行为）下第二次按住会变成"切回"，用户看到的就是"按了没反应"。
        var s = OriginalViewState()
        repeat(2) { round ->
            s = s.press(true)
            assertTrue("第 ${round + 1} 次按住要看得到原图", s.showing)
            s = s.press(false)
            assertFalse("第 ${round + 1} 次松手要回到效果图", s.showing)
        }
    }

    @Test
    fun releaseAlwaysRestoresEffectEvenWhenGestureCancelled() {
        // 手指滑出按钮 / 手势被抢：tryAwaitRelease 返回 false，调用方**照样**走 press(false)
        //（松手路径只有一条）。重复松手幂等 —— 不能出现"按下了没复位"把画面永久钉在原图。
        val cancelled = OriginalViewState().press(true).press(false).press(false)
        assertFalse("取消 / 重复松手都必须回到效果图", cancelled.showing)
    }

    @Test
    fun repeatedPressWhileHeldStaysShowing() {
        // 按住期间重复收到 press(true)（手势事件抖动）不能翻转成"不显示"
        assertTrue(OriginalViewState().press(true).press(true).showing)
    }

    @Test
    fun showingIsExactlyPressed() {
        // 显示判据只剩**一路**来源（E2-15 的 locked 字段已随 toggle 语义一起删除）：
        // 任何时刻 showing ⇔ pressed —— 防止"残留锁定态让原图一直显示"的旧行为回归。
        val seq = listOf(false, true, false, true, false)
        var s = OriginalViewState()
        seq.forEach { v ->
            s = s.press(v)
            assertEquals("showing 必须等于 pressed（v=$v）", v, s.showing)
            assertEquals("pressed 字段就是唯一来源", v, s.pressed)
        }
    }
}
