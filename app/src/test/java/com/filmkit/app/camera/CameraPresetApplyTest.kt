package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-2a：拍照界面预设（[CAMERA_STANDARD_PRESET] / [cameraPresetParams] / [presetLutAction] /
 * [resolveCameraPreset]）的纯逻辑护栏。
 *
 * 为什么这四条必须被钉住 —— 每一条错了的表现都是"用户点一下、画面静默变错"：
 *  1. ★ **标准 = 中性基线**：它是"切回标准"的唯一落点，底错了就是整条链的默认观感错；
 *  2. ★ **稀疏展开只覆盖预设里记下的槽**：其余槽必须逐位等于基线（本仓库大量槽的恒等值不是 0，
 *     展开时把它们打成 0 = 对比度归零 / 串扰矩阵清零那类事故）；
 *  3. ★ **LUT 三态**：`lutId == null` ⇒ 不动（不是"关掉"）；**引用的 LUT 已不在库 ⇒ 降级为不动 +
 *     提示**（不是静默、也不是清空）—— 这是本卡最容易退化成"恒真断言"的一条，见用例内注释；
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

    // ── ③ LUT 三态（含"已不在库中"的降级路径）──────────────────────────────

    @Test
    fun lutWithoutReferenceKeepsCurrentLut() {
        // 不含 LUT ≠ 关掉 LUT：缺失字段 = 保留当前值（与编辑器侧同一口径）
        assertEquals(PresetLutAction.KEEP, presetLutAction(preset(lutId = null)) { true })
    }

    @Test
    fun lutInLibraryIsApplied() {
        val p = preset(lutId = "kodak_portra_400", lutStrength = 0.8)
        assertEquals(PresetLutAction.APPLY, presetLutAction(p) { it == "kodak_portra_400" })
    }

    @Test
    fun missingLutDegradesToKeepInsteadOfClearing() {
        // ★ 本用例是**降级分支的专属护栏**：把 presetLutAction 的 else 分支改成 KEEP（= 去掉
        // MISSING 三态）后，这条断言必然变红 —— 即"降级存在"这件事本身被钉住了，不是恒真。
        val p = preset(lutId = "deleted_lut", lutStrength = 0.5)
        assertEquals(
            "引用的 LUT 已不在库中 ⇒ 降级为不动当前 LUT（不静默、不清空）",
            PresetLutAction.MISSING,
            presetLutAction(p) { false },
        )
        // 同一份预设、资产还在 ⇒ 必须 APPLY（证明上面那条不是"任何输入都 MISSING"）
        assertEquals(PresetLutAction.APPLY, presetLutAction(p) { true })
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
