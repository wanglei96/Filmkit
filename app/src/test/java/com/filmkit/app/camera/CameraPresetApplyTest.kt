package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-2a / P-2c：拍照界面预设（[CAMERA_STANDARD_PRESET] / [cameraPresetParams] / [presetLutAction] /
 * [resolveCameraPreset]）的纯逻辑护栏。
 *
 * 为什么这四条必须被钉住 —— 每一条错了的表现都是"用户点一下、画面静默变错"：
 *  1. ★ **标准 = 中性基线**：它是"切回标准"的唯一落点，底错了就是整条链的默认观感错；
 *  2. ★ **稀疏展开只覆盖预设里记下的槽**：其余槽必须逐位等于基线（本仓库大量槽的恒等值不是 0，
 *     展开时把它们打成 0 = 对比度归零 / 串扰矩阵清零那类事故）；
 *  3. ★ **LUT 四态**（P-2c 裁决 ①② 之后的**新**口径）：`lutId == null` ⇒ 不动（不是"关掉"）；
 *     引用的 LUT 已不在库 ⇒ **清空 + 提示**；「标准」⇒ **清空**；在库 ⇒ 选中。
 *     ⚠ "不含 LUT ⇒ 不动"与"清空"两条**必须同时有护栏** —— 只测清空，把 KEEP 也改成清空
 *     （= 裁决 ② 改过头）不会有任何测试变红；只测 KEEP，则裁决 ② 静默退化回旧行为。
 *  4. ★ **选中态往返**：prefs 里的 id 读回必须命中同一条预设；预设被删后**回退标准**而不是崩 /
 *     悬空（ADR §5.2 的 G-1：冷启动丢状态的同型坑）。
 */
class CameraPresetApplyTest {

    private val base = CameraPresets.standard()

    private fun preset(
        id: String = "p_1_1",
        name: String = "午后暖调",
        group: String = "",
        slots: Map<Int, Double> = emptyMap(),
        lutId: String? = null,
        lutStrength: Double = 1.0,
    ) = EditorPreset(
        id = id, name = name, group = group, createdAt = 1730000000L,
        slots = slots, lutId = lutId, lutStrength = lutStrength,
    )

    // ── ① 标准 = 中性基线 ────────────────────────────────────────────────────

    @Test
    fun standardIsTheNeutralBaseline() {
        val p = cameraPresetParams(CAMERA_STANDARD_PRESET)
        assertEquals("长度 = C++ kCount", CameraPresets.COUNT, p.size)
        assertArrayEquals("标准必须逐位等于中性基线", base, p, 0.0)
        assertNull("标准不含 LUT ⇒ 套用时不动当前 LUT", CAMERA_STANDARD_PRESET.lutId)
        assertEquals(PRESET_SOURCE_BUILTIN, CAMERA_STANDARD_PRESET.source)
    }

    // ── ② 稀疏展开：只覆盖预设里记下的槽 ─────────────────────────────────────

    @Test
    fun sparseSlotsOnlyOverrideListedSlots() {
        val out = cameraPresetParams(preset(slots = mapOf(26 to 55.0)))
        assertEquals("预设里记的槽必须原样下发", 55.0, out[26], 0.0)
        for (i in base.indices) {
            if (i == 26) continue
            assertEquals("未记入预设的槽 $i 必须保持基线值", base[i], out[i], 0.0)
        }
    }

    @Test
    fun nonZeroIdentitySlotsAreNotClobberedByExpansion() {
        // 恒等值不是 0 的槽（对比度 / 白平衡 / LUT 强度 / 层间增益）：预设没记它们时，
        // 展开结果必须仍是 1.0 而不是 DoubleArray 的零初值。
        val out = cameraPresetParams(preset(slots = mapOf(90 to -0.2)))
        assertEquals(1.0, out[4], 0.0)   // contrast
        assertEquals(1.0, out[1], 0.0)   // wb_r
        assertEquals(1.0, out[34], 0.0)  // lut_strength
        assertEquals(1.0, out[69], 0.0)  // interlayer_shadow_r
        assertEquals(-0.2, out[90], 0.0) // highlights
    }

    // ── ③ LUT 四态（含「标准」清空 / "已不在库中"清空 / 回归哨兵）────────────

    @Test
    fun lutWithoutReferenceKeepsCurrentLut() {
        // ★ **回归哨兵**（防裁决 ② 改过头）：不含 LUT ≠ 关掉 LUT —— 缺失字段 = 保留当前值
        //（与编辑器侧 `p.lutId == null -> 不动 LUT` 同一口径）。把 presetLutAction 的
        // `lutId ?: return KEEP` 一并改成 CLEAR 后，本用例必须红。
        val a = presetLutAction(preset(lutId = null)) { true }
        assertEquals(PresetLutAction.KEEP, a)
        assertFalse("KEEP 绝不清空当前 LUT", a.clearsCurrentLut)
        // 资产查询函数不该被调用（不含 LUT 的预设没有任何 id 可查）—— 顺便钉住"不误查"
        assertEquals(PresetLutAction.KEEP, presetLutAction(preset(lutId = null)) { error("不该查 LUT") })
    }

    @Test
    fun lutInLibraryIsApplied() {
        val p = preset(lutId = "kodak_portra_400", lutStrength = 0.8)
        val a = presetLutAction(p) { it == "kodak_portra_400" }
        assertEquals(PresetLutAction.APPLY, a)
        assertFalse("APPLY 不清空当前 LUT", a.clearsCurrentLut)
    }

    @Test
    fun missingLutClearsInsteadOfKeeping() {
        // ★ **P-2c 裁决 ②**：引用的 LUT 已不在库 ⇒ **清空 + 明确提示**（与编辑器侧
        // `EditorScreen.applyPreset` 的 ② 分支统一）。
        // 本用例原名 `missingLutDegradesToKeepInsteadOfClearing`，断言旧口径"降级为不动当前 LUT"；
        // 改名与改断言都是**裁决本身**，不是为了让测试变绿 —— 旧行为会产生"预设 A 的参数 +
        // 预设 B 的色彩"这种用户无法从界面解释的混合态。
        val p = preset(lutId = "deleted_lut", lutStrength = 0.5)
        val missing = presetLutAction(p) { false }
        assertEquals(
            "引用的 LUT 已不在库中 ⇒ MISSING（清空 + 提示）",
            PresetLutAction.MISSING,
            missing,
        )
        assertTrue("MISSING 必须清空当前 LUT（裁决 ②）", missing.clearsCurrentLut)
        // 同一份预设、资产还在 ⇒ 必须 APPLY（证明上面那条不是"任何输入都 MISSING"）
        assertEquals(PresetLutAction.APPLY, presetLutAction(p) { true })
    }

    @Test
    fun standardClearsCurrentLut() {
        // ★ **P-2c 裁决 ①**：选「标准」= 无风格 ⇒ 参数回基线 **且 LUT 一起清**。
        // 只回参数会留下"假标准"：画面仍带着上一个预设的 LUT 色彩，UI 上却写着"标准"。
        // ⚠ 判定顺序陷阱：[CAMERA_STANDARD_PRESET] 的 `lutId` 是 null，若 presetLutAction 先走
        // `lutId == null ⇒ KEEP`（或标准判断被挪到后面），本断言立刻红 —— 这正是要钉住的那一行。
        val a = presetLutAction(CAMERA_STANDARD_PRESET) { true }
        assertEquals(PresetLutAction.CLEAR, a)
        assertTrue("CLEAR 必须清空当前 LUT（裁决 ①）", a.clearsCurrentLut)
        // 「标准」与"不含 LUT 的普通预设"必须**可分辨**（前者清空、后者保留）—— 同型不同义。
        assertFalse(
            "普通的不含 LUT 预设不得被误判成 CLEAR",
            presetLutAction(preset(id = "p_1_1", lutId = null)) { true }.clearsCurrentLut,
        )
    }

    @Test
    fun onlyClearingActionsReportClearing() {
        // 清空判据只由枚举属性承担（UI 读它，不重写一遍 when 判断）⇒ 四个值的语义在此一次钉死。
        assertEquals(
            "只有 CLEAR / MISSING 清空当前 LUT（KEEP 保留 / APPLY 换 LUT 都不是清空）",
            listOf(PresetLutAction.MISSING, PresetLutAction.CLEAR),
            PresetLutAction.entries.filter { it.clearsCurrentLut },
        )
    }

    // ── ④ 选中态持久化往返 ──────────────────────────────────────────────────

    @Test
    fun selectionRoundTripThroughPersistedId() {
        val list = listOf(
            preset(id = "p_100_a", name = "暖调"),
            preset(id = "p_200_b", name = "冷调"),
        )
        // 存"标准"（= 键被移除 / 空串）⇒ 读回标准
        assertEquals(CAMERA_STANDARD_PRESET_ID, resolveCameraPreset(null, list).id)
        assertEquals(CAMERA_STANDARD_PRESET_ID, resolveCameraPreset("", list).id)
        assertEquals(CAMERA_STANDARD_PRESET_ID, resolveCameraPreset(CAMERA_STANDARD_PRESET_ID, list).id)
        // 存用户预设 id ⇒ 读回同一条（名称 / 槽位一起回来，不是只有 id）
        val back = resolveCameraPreset("p_200_b", list)
        assertEquals("p_200_b", back.id)
        assertEquals("冷调", back.name)
        // 预设已被删除（id 悬空）⇒ 回退标准，不崩不悬空
        assertEquals(CAMERA_STANDARD_PRESET_ID, resolveCameraPreset("p_999_gone", list).id)
        assertEquals(CAMERA_STANDARD_PRESET_ID, resolveCameraPreset("p_999_gone", emptyList()).id)
    }

    // ── ⑤ 卡片小字（分组 + 是否含 LUT）──────────────────────────────────────

    @Test
    fun subtitleShowsGroupAndLutPresence() {
        assertNull("都没有 ⇒ 不占一行", presetSubtitle(preset(group = "", lutId = null)))
        assertEquals("柯达", presetSubtitle(preset(group = "柯达", lutId = null)))
        assertEquals("LUT", presetSubtitle(preset(group = "", lutId = "x")))
        assertEquals("柯达 · LUT", presetSubtitle(preset(group = "柯达", lutId = "x")))
        assertEquals("空白分组不算分组", "LUT", presetSubtitle(preset(group = "  ", lutId = "x")))
    }

    // ── ⑥ 日志文本 = 引擎实际收到的改动值（验收证据 2）──────────────────────

    @Test
    fun slotsTextNamesTheOverriddenSlotsWithTheirRealValues() {
        val p = preset(slots = linkedMapOf(4 to 1.2, 26 to 55.0))
        val text = presetSlotsText(p)
        assertEquals("contrast=1.2;grain_amount=55.0", text)
        // 日志里的数值必须与展开后下发给引擎的数组逐槽一致（证据链闭合）
        val out = cameraPresetParams(p)
        assertTrue(out[4] == 1.2 && out[26] == 55.0)
    }
}
