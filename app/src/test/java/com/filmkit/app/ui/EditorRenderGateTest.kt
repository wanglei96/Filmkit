package com.filmkit.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2-8 的纯逻辑护栏：滑杆"拖动中不渲染、松手渲染一次"的闸门 + 全分辨率常驻内存估算。
 *
 * 为什么只测这些：真正的渲染（Compose 手势 + GL + 50MB 位图）要真机，JVM 单测跑不了。
 * 这里守住两条**错了用户直接看得见**的事实：
 *  1. 拖动中一次都不渲染（这是规格），但**松手必须渲染**（漏了 = 用户调完参数画面纹丝不动）；
 *  2. 12MP 全分辨率预览的常驻内存就是 ~96MB 这个量级（ADR 的内存账，改分辨率时立刻能发现）。
 */
class EditorRenderGateTest {

    @Test
    fun draggingNeverRendersButReleaseDoes() {
        val gate = SliderRenderGate()
        // 拖动 60 帧：规格要求一次都不渲染（调用方在 onValueChange 里不调 rerender）
        repeat(60) { gate.onValueChange() }
        // 松手：必须渲染一次
        assertTrue("松手必须渲染一次", gate.onFinished())
        // 紧接着再松一次手（中间没有新的值变更）：不重复渲染
        assertFalse("没有值变更时不该再渲染", gate.onFinished())
    }

    @Test
    fun tappingWithoutMovingDoesNotRender() {
        // RelativeSlider 按下不改值：点一下再松手 = 什么都没变 ⇒ 不白跑一次全分辨率渲染
        val gate = SliderRenderGate()
        assertFalse(gate.onFinished())
    }

    @Test
    fun eachDragRendersExactlyOnce() {
        val gate = SliderRenderGate()
        var renders = 0
        // 三轮"拖 10 帧 + 松手" ⇒ 恰好 3 次渲染（不多不少）
        repeat(3) {
            repeat(10) { gate.onValueChange() }
            if (gate.onFinished()) renders++
        }
        assertEquals(3, renders)
    }

    @Test
    fun dragTicksCountEveryValueChangeAndResetOnTake() {
        val gate = SliderRenderGate()
        repeat(7) { gate.onValueChange() }
        assertEquals(7, gate.takeTicks())   // 诊断字段 dragTicks：拖动 7 次只渲染 1 次
        assertEquals(0, gate.takeTicks())   // 取走即清零（下一条日志只反映下一段拖动）
        gate.onFinished()
        gate.onValueChange()
        assertEquals(1, gate.takeTicks())
    }

    @Test
    fun twelveMegapixelPreviewResidentEstimate() {
        // 12MP（4080×3072）：输入 direct buffer 50MB + 输出位图 50MB ⇒ ≈96MB（向上取整）
        assertEquals(96, previewResidentMb(4080, 3072))
        // 旧预览档（1020×768）≈6MB —— 这就是 E2-8 把预览改全分辨率后抬起来的量级
        assertEquals(6, previewResidentMb(1020, 768))
        // 退化输入不炸、不返回负数
        assertEquals(0, previewResidentMb(0, 0))
    }
}
