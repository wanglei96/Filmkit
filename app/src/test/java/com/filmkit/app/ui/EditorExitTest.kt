package com.filmkit.app.ui

import com.filmkit.app.camera.discardNeedsConfirm
import com.filmkit.app.camera.paramsUnchanged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2-24 的纯逻辑护栏：**离开编辑器的三条路径统一收口**（「取消」/ 顶栏菜单图标 / 系统返回）。
 *
 * 用户报的 bug："编辑一半直接点菜单会直接丢失全部编辑并回到相册" —— 修之前三条路径里只有
 * 「取消」走过确认，菜单与系统返回都是**直接切屏**。这里钉住四条规格（都要真机点半天才能
 * 发现的，JVM 单测直接钉死）：
 *
 *  1. 有修改 × {BACK, GALLERY} ⇒ 挂起**对应**出口（弹确认），出口不被替换；
 *  2. 无修改 × {BACK, GALLERY} ⇒ 不挂起（调用方直接走），不弹窗打扰；
 *  3. 确认后走的就是挂起的那个出口：BACK 不误走 GALLERY，反之亦然；
 *  4. ★ 参数一位没动但**裁了图**（`cropDirty`）× {BACK, GALLERY} ⇒ 仍然必须确认。
 *
 * 第 4 条为什么必须用**真实判据** [discardNeedsConfirm] 驱动、而不是直接传一个 `true`：
 * 出口决策本身只做"要不要弹"这一层选择（[pendingExitFor]），"有没有修改"来自
 * [discardNeedsConfirm]（= `paramsUnchanged` 的包装 + 裁剪）。裁剪是几何量、**不进
 * FilmParams**，`paramsUnchanged` 看不见它 —— 接线时漏传 `cropDirty` 的后果是"只裁了图 ⇒
 * 判成无修改 ⇒ 点菜单直接切屏，裁剪无声丢失"，与本卡修的是同一类事故。
 */
class EditorExitTest {

    /** 打开编辑器时的基线参数（真实预设，不用手搓数组） */
    private val baseline = CameraPresets.standard()

    /** 改过参数的副本（曝光 +1，与 EditorCropTest / EditorToneColorUiTest 同一口径） */
    private fun changedParams() = baseline.copyOf().also { it[0] = it[0] + 1.0 }

    // ── ① 有修改 × 两个出口 ⇒ 挂起对应出口（弹确认） ──────────────────────

    @Test
    fun dirtyParamsSuspendTheRequestedExit() {
        val needsConfirm = discardNeedsConfirm(changedParams(), baseline, cropDirty = false)
        assertTrue("前置：改过曝光 ⇒ 有修改", needsConfirm)
        assertEquals(
            "返回键 ⇒ 挂起 BACK（弹确认，确认后回上一页）",
            ExitTarget.BACK,
            pendingExitFor(needsConfirm, ExitTarget.BACK),
        )
        assertEquals(
            "★ 菜单图标 ⇒ 挂起 GALLERY（弹确认，确认后回相册）—— 用户报的就是这一条",
            ExitTarget.GALLERY,
            pendingExitFor(needsConfirm, ExitTarget.GALLERY),
        )
    }

    // ── ② 无修改 × 两个出口 ⇒ 不打扰，直接走 ──────────────────────────────

    @Test
    fun cleanParamsLeaveImmediatelyOnBothExits() {
        val needsConfirm = discardNeedsConfirm(baseline.copyOf(), baseline, cropDirty = false)
        assertFalse("前置：刚打开、一位没改 ⇒ 无修改", needsConfirm)
        assertNull(
            "无修改 + 返回键 ⇒ 不弹确认（直接走 BACK）",
            pendingExitFor(needsConfirm, ExitTarget.BACK),
        )
        assertNull(
            "无修改 + 菜单 ⇒ 不弹确认（直接走 GALLERY）",
            pendingExitFor(needsConfirm, ExitTarget.GALLERY),
        )
    }

    // ── ③ 确认后分流：走的就是挂起的那个出口 ──────────────────────────────

    @Test
    fun confirmGoesToTheSuspendedExit() {
        assertEquals("挂起的是 BACK ⇒ 确认后走 BACK", ExitTarget.BACK, exitAfterConfirm(ExitTarget.BACK))
        assertEquals(
            "挂起的是 GALLERY ⇒ 确认后走 GALLERY",
            ExitTarget.GALLERY,
            exitAfterConfirm(ExitTarget.GALLERY),
        )
        assertNull("出口已被清掉（框被点掉 / 已经离开）⇒ 不动作，不能凭空切屏", exitAfterConfirm(null))
    }

    @Test
    fun confirmNeverSwapsTheExit() {
        // 显式反例（串了就是一眼可见的错：点菜单确认后回上一页 / 按返回确认后跳相册）
        assertNotEquals(ExitTarget.BACK, exitAfterConfirm(ExitTarget.GALLERY))
        assertNotEquals(ExitTarget.GALLERY, exitAfterConfirm(ExitTarget.BACK))
    }

    // ── ④ ★ 裁剪改了但参数没动 ⇒ 两个出口都仍要确认 ────────────────────────

    @Test
    fun cropOnlyChangeStillConfirmsOnBothExits() {
        val untouched = baseline.copyOf()
        assertTrue("前置：参数确实一位没动", paramsUnchanged(untouched, baseline))

        val needsConfirm = discardNeedsConfirm(untouched, baseline, cropDirty = true)
        assertTrue("前置：裁了图 ⇒ 判为有修改（裁剪不在 FilmParams 里，必须显式并进来）", needsConfirm)
        assertEquals(
            "★ 只裁了图 + 返回键 ⇒ 仍要确认（否则裁剪无声丢失）",
            ExitTarget.BACK,
            pendingExitFor(needsConfirm, ExitTarget.BACK),
        )
        assertEquals(
            "★ 只裁了图 + 菜单 ⇒ 仍要确认（否则裁剪无声丢失）",
            ExitTarget.GALLERY,
            pendingExitFor(needsConfirm, ExitTarget.GALLERY),
        )

        // 对照：同样"参数没动"，但没裁剪 ⇒ 真的不打扰（证明上面两条不是判据恒真）
        assertNull(
            "参数没动 + 没裁剪 ⇒ 不弹确认",
            pendingExitFor(
                discardNeedsConfirm(untouched, baseline, cropDirty = false),
                ExitTarget.GALLERY,
            ),
        )
    }

    // ── ⑤ 返回手势分层（E2-15 验收点）在 E2-24 之后仍然成立 ────────────────

    /**
     * 这条是 **BackHandler 分支的镜像**（不是重复实现，与 [com.filmkit.app.camera.EditorCropTest]
     * 里"镜像 exportNow 那一行"同一套路）：E2-24 把 `BackHandler` 从 `enabled = panel.expanded`
     * 改成**无条件启用**，最容易写坏的地方就是"忘了先判面板" —— 那会让面板开着时一次返回直接
     * 退出编辑器（E2-15 的验收点破了，用户明确说过"返回手势没问题"）。
     */
    @Test
    fun expandedPanelConsumesBackWithoutAskingToExit() {
        val expanded = EditorPanelState(expanded = true)
        val collapsed = EditorPanelState.onBack(expanded)
        assertNotNull("★ 面板展开 ⇒ 返回被面板消费，绝不退出编辑器", collapsed)
        assertFalse("消费的结果 = 面板已收起", collapsed!!.expanded)

        // 完整链路（有修改）：第一次返回只收面板（不弹框、不切屏）→ 第二次返回才轮到出口决策
        assertNull("面板已收起 ⇒ 这次返回交给出口决策", EditorPanelState.onBack(collapsed))
        assertEquals(
            "第二次返回 + 有修改 ⇒ 挂起 BACK 弹确认（不是直接退出）",
            ExitTarget.BACK,
            pendingExitFor(
                discardNeedsConfirm(changedParams(), baseline, cropDirty = false),
                ExitTarget.BACK,
            ),
        )
    }
}
