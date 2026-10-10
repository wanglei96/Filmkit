package com.filmkit.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2-25 撤销 / 重做的纯逻辑护栏（[EditorSnapshot] + [EditorHistory]）。
 *
 * 为什么这些必须被钉住：撤销栈的每一条规格错了，表现都是**用户点一下就看得见**、但要在真机上
 * 点半天才能复现的那类问题，而判据全落在纯逻辑里（不碰 GL / Bitmap / Compose）：
 *  1. **入栈 / 出栈 / redo 失效**：新操作之后 redo 必须清空，否则"撤销 → 改一笔 → 重做"
 *     会把改的那一笔吃掉（重做回一个已经被覆盖的状态）；
 *  2. **深度限制 30 步**：超限丢**最旧**的（丢最新 = 刚做的操作撤不回来，方向反了）；
 *  3. **无变化不入栈**：否则出现"点撤销画面不动"的空步（本卡明令禁止）；
 *  4. ★ **快照是副本**：滑杆是**原地改写** `params` 数组的（`EditorParam.write` 直接写槽位）
 *     ⇒ 存引用 = 栈里那份快照跟着当前状态一起变，撤销永远回到现在 —— 本卡最容易踩的坑；
 *  5. ★ **撤销回基线 ⇒ 直通重新成立**：`paramsUnchanged` 为真、`cropDirty` 为假
 *     ⇒ 导出仍走直通（E2-9）、「取消」不再弹「放弃修改？」（E2-24）。这是本卡与既有机制
 *     唯一的硬交互，接线漏一处就是"我撤销回原样了，导出却还是二代损失"。
 *
 * 基线用真实的 [editorOpenParams]（不是手搓数组）：撤销"回到最初"必须精确等于**打开编辑器
 * 时的那个起点**，而那正是 `openParams` 的取值来源。
 */
class EditorHistoryTest {

    /** 打开编辑器时的参数起点（全恒等基线 + [34] LUT 强度归 0） */
    private val baseline = editorOpenParams()

    /** 一份快照（默认 = 基线 / 全幅 / 无 LUT） */
    private fun snap(
        params: DoubleArray = baseline,
        crop: CropSpec = CropSpec.FULL,
        lutId: String? = null,
    ) = EditorSnapshot(params, crop, lutId)

    /** 改一个槽位后的参数副本（模拟一次用户操作落地后的状态） */
    private fun edited(slot: Int, value: Double) = baseline.copyOf().also { it[slot] = value }

    // ── ① 入栈 / 出栈 / redo 失效 ─────────────────────────────────────────

    @Test
    fun recordThenUndoReturnsToPreviousState() {
        val h = EditorHistory()
        h.reset(snap())
        assertFalse("刚打开：没得撤销（顶栏按钮置灰）", h.canUndo)
        assertFalse("刚打开：没得重做", h.canRedo)

        assertTrue("有变化 ⇒ 入栈", h.record(snap(edited(0, 1.0))))
        assertTrue(h.canUndo)
        assertFalse("新操作之后没有可重做的", h.canRedo)

        val back = h.undo()
        assertNotNull(back)
        assertEquals("撤销回到操作前的曝光 0", 0.0, back!!.params[0], 0.0)
        assertFalse("只剩基线那一步时已无可撤销", h.canUndo)
        assertTrue(h.canRedo)

        assertNull("栈空 ⇒ null（调用方什么都不做，画面不动）", h.undo())
        assertTrue("空撤销没吃掉 redo", h.canRedo)
    }

    @Test
    fun redoReappliesTheUndoneState() {
        val h = EditorHistory()
        h.reset(snap())
        h.record(snap(edited(0, 1.0)))
        h.undo()
        val fwd = h.redo()
        assertNotNull(fwd)
        assertEquals("重做回到撤销前的曝光 +1", 1.0, fwd!!.params[0], 0.0)
        assertTrue("重做之后又能撤销回来", h.canUndo)
        assertFalse("redo 栈已空", h.canRedo)
    }

    @Test
    fun newOperationClearsRedoStack() {
        val h = EditorHistory()
        h.reset(snap())
        h.record(snap(edited(0, 1.0)))
        h.record(snap(edited(0, 2.0)))
        h.undo()
        assertTrue("撤销一次后可重做", h.canRedo)
        h.record(snap(edited(4, 1.5)))          // ★ 撤销之后又做了新操作
        assertFalse("★ 新操作必须让 redo 失效（否则重做会吃掉这一笔）", h.canRedo)
        assertNull(h.redo())
    }

    // ── ② 深度限制 ────────────────────────────────────────────────────────

    @Test
    fun depthIsCappedAtThirtyDroppingTheOldest() {
        val h = EditorHistory()
        h.reset(snap())
        for (i in 1..40) h.record(snap(edited(0, i.toDouble())))   // 40 次互不相同的修改

        assertEquals("上限 = EditorSnapshot.MAX_STEPS", 30, EditorSnapshot.MAX_STEPS)
        assertEquals("超限后深度封顶在 30", 30, h.undoDepth)

        var last: EditorSnapshot? = null
        repeat(30) { last = h.undo() }
        assertNotNull(last)
        assertEquals(
            "丢的是**最旧**的 10 步（曝光 0..9）⇒ 撤到底停在曝光 10；" +
                "若丢最新，这里会是 40（刚做的操作撤不回来）",
            10.0, last!!.params[0], 0.0,
        )
        assertFalse(h.canUndo)
        assertNull(h.undo())
    }

    // ── ③ 无变化不入栈 ────────────────────────────────────────────────────

    @Test
    fun identicalStateIsNotPushed() {
        val h = EditorHistory()
        h.reset(snap())
        assertFalse("与当前状态相同 ⇒ 丢弃（防'点撤销画面不动'的空步）", h.record(snap()))
        assertFalse(h.canUndo)

        // 容差内（< PARAM_EPS）的差异同样不算一次修改：复用 E2-9 的容差，Float→Double 的噪声
        // 不该变成一步撤销
        assertFalse(h.record(snap(baseline.copyOf().also { it[0] = PARAM_EPS / 2 })))
        assertFalse(h.canUndo)

        // 超过容差 ⇒ 真修改
        assertTrue(h.record(snap(baseline.copyOf().also { it[0] = PARAM_EPS * 10 })))
        assertTrue(h.canUndo)
    }

    @Test
    fun cropAndLutDifferenceCountsAsChange() {
        val h = EditorHistory()
        h.reset(snap())
        assertTrue(
            "参数一位没动、只裁了图 ⇒ 也是一步（裁剪是几何量，**不在** FilmParams 里）",
            h.record(snap(crop = CropSpec(0.1f, 0.1f, 0.5f, 0.5f, 0))),
        )
        assertTrue(
            "参数与裁剪都没动、只换了 LUT ⇒ 也是一步（LUT **资产 id 不在** FilmParams 里）",
            h.record(snap(lutId = "fuji_superia")),
        )
    }

    // ── ④ 快照是副本 ──────────────────────────────────────────────────────

    @Test
    fun snapshotCopiesTheParamsArray() {
        val live = baseline.copyOf()
        val s = EditorSnapshot(live, CropSpec.FULL, null)
        live[0] = 99.0                          // 滑杆原地改写同一个数组
        assertEquals("★ 快照必须是副本（存引用 = 撤销永远回到现在）", 0.0, s.params[0], 0.0)
        assertTrue("副本与基线逐槽相同", paramsUnchanged(s.params, baseline))
    }

    @Test
    fun historyHoldsItsOwnCopyOfEachStep() {
        val h = EditorHistory()
        val live = baseline.copyOf()
        h.reset(EditorSnapshot(live, CropSpec.FULL, null))
        live[0] = 1.0
        h.record(EditorSnapshot(live, CropSpec.FULL, null))   // 曝光 +1 这一步
        live[0] = 2.0                                         // 又拖一次（未记录）

        val back = h.undo()!!
        assertEquals("撤销回到基线曝光 0", 0.0, back.params[0], 0.0)
        val fwd = h.redo()!!
        assertEquals(
            "redo 里存的是 record **当时**的副本（1.0），不是被后续改写后的 2.0",
            1.0, fwd.params[0], 0.0,
        )
    }

    // ── ⑤ 与直通 / 裁剪 / 放弃确认的交互（本卡的关键验收）──────────────────

    @Test
    fun undoBackToBaselineRestoresPassthroughAndSilencesExitConfirm() {
        val h = EditorHistory()
        h.reset(snap())
        val p = baseline.copyOf()
        h.record(snap(p.copyOf().also { it[0] = 1.0 }))                     // 曝光 +1
        h.record(snap(p.copyOf().also { it[4] = 1.5 }))                     // 对比度 1.5
        h.record(snap(p.copyOf().also { it[34] = 0.8 }, lutId = "kodak_portra"))  // 换 LUT + 强度
        val crop = CropSpec(0.1f, 0.2f, 0.5f, 0.5f, 1)
        h.record(snap(p.copyOf(), crop = crop, lutId = "kodak_portra"))     // 裁剪确认

        // 中途：确认框该弹、直通不该走（既有机制在"有修改"这一侧的对照）
        val mid = snap(p.copyOf(), crop = crop, lutId = "kodak_portra")
        assertTrue(
            "对照：改到一半时确实需要确认",
            discardNeedsConfirm(mid.params, baseline, cropDirty = !mid.crop.isFull),
        )
        assertFalse(
            "对照：改到一半时不该直通",
            passthroughAllowed(
                !discardNeedsConfirm(mid.params, baseline, cropDirty = !mid.crop.isFull),
                jpegSource = true,
            ),
        )

        // ★ 全部撤销（4 步）⇒ 精确回到打开时的状态
        var cur: EditorSnapshot = mid
        repeat(4) { cur = h.undo()!! }
        assertTrue("参数逐槽回到基线", paramsUnchanged(cur.params, baseline))
        assertTrue("裁剪回到全幅", cur.crop.isFull)
        assertNull("LUT 回到打开时的选择（基线没有 LUT）", cur.lutId)

        val needsConfirm = discardNeedsConfirm(cur.params, baseline, cropDirty = !cur.crop.isFull)
        assertFalse("★ 撤销回基线 ⇒「取消」不再弹「放弃修改？」（E2-24）", needsConfirm)
        assertTrue(
            "★ 撤销回基线 ⇒ 导出仍走直通（E2-9：不再白付一次二代编码损失）",
            passthroughAllowed(!needsConfirm, jpegSource = true),
        )
    }

    @Test
    fun cropRollsBackWithUndo() {
        val h = EditorHistory()
        h.reset(snap())
        h.record(snap(baseline, crop = CropSpec(0f, 0f, 0.5f, 0.5f, 0)))   // 裁掉一半
        assertTrue("裁了 ⇒ 需要确认（cropDirty）", discardNeedsConfirm(baseline, baseline, cropDirty = true))
        val back = h.undo()!!
        assertTrue("★ 撤销裁剪 ⇒ 回到全幅 ⇒ 直通 / 放弃确认都跟着回退", back.crop.isFull)
        assertFalse(
            "参数没动 + 裁剪已撤销 ⇒ 无修改",
            discardNeedsConfirm(back.params, baseline, cropDirty = !back.crop.isFull),
        )
    }

    @Test
    fun lutSelectionRollsBackWithUndo() {
        val h = EditorHistory()
        h.reset(snap())
        h.record(snap(baseline, lutId = "fuji_superia"))
        assertNull("★ 撤销把 LUT 选择一起退回（资产 id 不在 FilmParams 里）", h.undo()!!.lutId)
        assertEquals("重做把 LUT 选择装回来", "fuji_superia", h.redo()!!.lutId)
    }

    // ── ⑥ reset（换图 / 重新打开）─────────────────────────────────────────

    @Test
    fun resetClearsBothStacks() {
        val h = EditorHistory()
        h.reset(snap())
        h.record(snap(edited(0, 1.0)))
        h.undo()
        assertTrue(h.canRedo)
        h.reset(snap(edited(4, 1.2)))
        assertFalse("reset 后没有可撤销的（新图没有历史）", h.canUndo)
        assertFalse("reset 后没有可重做的", h.canRedo)
        assertEquals(0, h.undoDepth)
        assertEquals(0, h.redoDepth)
    }
}
