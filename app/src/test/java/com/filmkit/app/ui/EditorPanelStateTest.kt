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
 *  3. **原图两个来源独立**：按钮锁定与按住偷看互不干扰 —— 一个 Boolean 的写法会让
 *     "锁定后随手长按一下再松手"把锁定态一起清掉（原图莫名消失）。
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

    // ── 原图对比（按钮锁定 + 按住偷看）─────────────────────────────────────

    @Test
    fun originalViewIsOffByDefaultAndTogglesByButton() {
        val off = OriginalViewState()
        assertFalse("默认显示效果图", off.showing)
        val on = off.toggleLocked()
        assertTrue("按钮点一下 ⇒ 持续显示原图", on.showing)
        assertFalse("再点一下 ⇒ 回效果图", on.toggleLocked().showing)
    }

    @Test
    fun longPressShowsOriginalOnlyWhilePressed() {
        val s = OriginalViewState().press(true)
        assertTrue("按住即见", s.showing)
        assertFalse("松手即回", s.press(false).showing)
    }

    @Test
    fun releaseOfLongPressDoesNotClearButtonLock() {
        // ★ 本卡最容易错的一条：按钮锁定后随手长按再松手，不能把锁定态一起清掉
        val locked = OriginalViewState().toggleLocked()
        val pressed = locked.press(true)
        assertTrue(pressed.showing)
        val released = pressed.press(false)
        assertTrue("松手只解除按住那一路，按钮锁定仍在", released.showing)
        assertTrue(released.locked)
        assertFalse(released.pressed)
    }

    @Test
    fun buttonToggleDoesNotDisturbActiveLongPress() {
        val pressed = OriginalViewState().press(true)
        val toggled = pressed.toggleLocked()
        assertTrue(toggled.showing)
        assertTrue("长按还在进行中", toggled.pressed)
        // 松手后仍显示（按钮已锁定）
        assertTrue(toggled.press(false).showing)
    }
}
