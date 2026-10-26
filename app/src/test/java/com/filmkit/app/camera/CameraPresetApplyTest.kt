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
 *  5. ★ **U-7 预设曝光 = 偏移**（方案 B）：含 `[0]` 的预设取出偏移、不含 ⇒ 0、切预设/切标准 ⇒
 *     归零、用户调 EV ⇒ 偏移叠加保留。旧行为（覆盖层丢弃预设 `[0]`）下第 ①④ 条必红。
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

    // ── ⑦ U-7：预设曝光 `[0]` = 相对用户基准的**偏移**（方案 B，DESIGN §9.4）────
    //
    // 背景：拍照侧的 `[0]` 是"拍摄曝光补偿"（用户实时控制），编辑器侧是"后期曝光"（直接生效）
    // —— §9.2 里唯一一个语义天然冲突的参数。用户 2026-10-11 拍板方案 B：拍照侧
    // `[0] = base_ev + 用户 EV + 预设偏移`（拍前看得到预设的曝光，又不冲掉用户的实时调整）。
    // 旧实现把预设的 `[0]` 整槽丢弃（防预设冲掉用户曝光）⇒ 预设的曝光**静默失效**。

    @Test
    fun presetWithExposureYieldsThatOffset() {
        // ① 含曝光的预设 ⇒ 取出正确偏移（正负都要）
        assertEquals("预设记 +1.0EV ⇒ 偏移 +1.0", 1.0, presetEvOffset(mapOf(0 to 1.0)), 0.0)
        assertEquals(-0.7, presetEvOffset(mapOf(0 to -0.7, 26 to 55.0)), 0.0)
        // 稀疏记录里只有 `[0]` 时也一样（偏移不与其它槽耦合）
        assertEquals(0.35, presetEvOffset(mapOf(0 to 0.35)), 1e-12)
        // 与展开数组的一致性：slots 说的值 = 下发数组的 `[0]`（两条通路不能各说一套）
        assertEquals(1.0, cameraPresetParams(preset(slots = mapOf(0 to 1.0)))[0], 0.0)
    }

    @Test
    fun presetWithoutExposureYieldsZeroOffset() {
        // ② 不含曝光 ⇒ 0（缺失字段 = 不改变该参数，**不是**"把曝光打到 0"）
        assertEquals("空 slots（= 标准基线）⇒ 0", 0.0, presetEvOffset(emptyMap()), 0.0)
        assertEquals(0.0, presetEvOffset(mapOf(26 to 55.0, 4 to 1.2, 90 to -0.2)), 0.0)
        // 显式记 0.0 与"没记"同义（都是 0）—— 预设编辑器把滑杆拖回 0 时不该有别的作用
        assertEquals(0.0, presetEvOffset(mapOf(0 to 0.0)), 0.0)
    }

    @Test
    fun switchingToExposureFreePresetOrDefaultZeroesTheOffset() {
        // ③ 切预设 / 切回「标准」⇒ 偏移归零，**不残留**上一条预设的曝光。
        // 纯逻辑层钉的是"每条预设各自算出什么"；CameraController 侧 `presetEvOffset = evOffset`
        // 是**无条件覆盖**（不是"没值就保留旧值"）⇒ 新预设算出 0 就一定下发 0。
        // 本用例是那句覆盖的护栏：若哪次重构把"缺失 = 保留上一条"引进 presetEvOffset（或让
        // 调用侧只在非零时下发），下面两个 0 立刻变红。
        val withEv = preset(id = "p_1_a", slots = mapOf(0 to 1.0))
        val noEv = preset(id = "p_2_b", slots = mapOf(26 to 55.0))
        assertEquals(1.0, presetEvOffset(withEv.slots), 0.0)
        assertEquals("切到不含曝光的预设 ⇒ 归零", 0.0, presetEvOffset(noEv.slots), 0.0)
        assertEquals("切回「标准」⇒ 归零", 0.0, presetEvOffset(CAMERA_STANDARD_PRESET.slots), 0.0)
    }

    @Test
    fun userEvAddsOnTopOfPresetOffset() {
        // ④ ★ 方案 B 的核心：用户调曝光补偿时预设偏移**保留**（叠加，不是覆盖）。
        // 旧行为（预设 `[0]` 被覆盖层丢弃）= 每个断言都少 1.0 ⇒ 本用例必须红。
        assertEquals(
            "偏移 +1.0 叠在 base 0.7 + 用户 −0.3 之上",
            1.4, effectiveExposureEv(0.7, -0.3, 1.0), 1e-12,
        )
        // 用户继续调 EV：偏移那一份始终在（差值恒定）
        assertEquals(1.7, effectiveExposureEv(0.7, 0.0, 1.0), 1e-12)
        assertEquals(2.2, effectiveExposureEv(0.7, 0.5, 1.0), 1e-12)
        // 负向偏移同理（预设欠曝）
        assertEquals(-0.9, effectiveExposureEv(0.7, -0.6, -1.0), 1e-12)
        // ★ 回归哨兵：偏移 0（标准 / 不含曝光的预设）⇒ 逐位等于 v0.8.1 以来的旧行为。
        // 既有三条内置预设都不含 `[0]` ⇒ 这条保证本卡对绝大多数用户是**零行为变化**。
        assertEquals(0.4, effectiveExposureEv(0.7, -0.3, 0.0), 1e-12)
        assertEquals(0.0, effectiveExposureEv(0.0, 0.0, 0.0), 1e-12)
    }

    // ── ⑧ 0.12.90：预设影调（`[37]/[38]` 黑白点）必须随预设存活 ──────────────
    //
    // 背景：`applyFilmicRangePrefs` 原来无条件以设置页 prefs 覆盖 `[37]/[38]` ⇒ 冷启动
    // （相机重建 / Activity 重建）路径上会把预设的紧黑位冲回默认 —— 0.12.89 真机 3/4 张
    // 就是这样跑错了影调（日志 `blackEv -7.80` 而非预设的 −4.879）。
    // 本组钉"从预设提取黑白点"的口径；控制器侧的优先级由 `setFilmParams` 第三个实参承载。

    @Test
    fun presetWithBothFilmicEndpointsYieldsThePair() {
        val ev = presetFilmicEvOf(mapOf(37 to -4.879, 38 to 4.0, 4 to 1.188))
        assertEquals(-4.879, ev!!.first, 0.0)
        assertEquals(4.0, ev.second, 0.0)
    }

    @Test
    fun missingOneFilmicEndpointYieldsNull() {
        assertNull("只有黑点 ⇒ 不做半套覆盖", presetFilmicEvOf(mapOf(37 to -4.879)))
        assertNull("只有白点 ⇒ 同上", presetFilmicEvOf(mapOf(38 to 4.0)))
        assertNull("标准 / 空 slots ⇒ null（走设置页 prefs）", presetFilmicEvOf(emptyMap()))
    }

    @Test
    fun filmicOverridesBeatPresetAndRemovedMeansAbsent() {
        val slots = mapOf(37 to -4.879, 38 to 4.0)
        val dragged = presetFilmicEvOf(slots, overrides = mapOf(37 to -6.0, 38 to 4.8))
        assertEquals(-6.0, dragged!!.first, 0.0)
        assertEquals(4.8, dragged.second, 0.0)
        assertNull("取消勾选白点 ⇒ 整对视为缺失", presetFilmicEvOf(slots, removed = setOf(38)))
    }
}
