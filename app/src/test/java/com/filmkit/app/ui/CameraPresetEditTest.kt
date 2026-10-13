package com.filmkit.app.ui

import com.filmkit.app.camera.BuiltinPresets
import com.filmkit.app.camera.applyBuiltinOverrides
import com.filmkit.app.camera.CAMERA_ENGINE_SELECT_SLOTS
import com.filmkit.app.camera.CAMERA_PATH_SLOTS
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.PRESET_SOURCE_USER
import com.filmkit.app.camera.cameraPresetParams
import com.filmkit.app.camera.effectivePresetEvOffset
import com.filmkit.app.camera.expSlot
import com.filmkit.app.camera.presetDeletable
import com.filmkit.app.camera.presetEvOffset
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ PS-3：拍照界面预设的**逐项调整**（用户 2026-10-11 真机实测后："当前预设管理做的很差。
 * 参考截图——Filmix 相机，**可直接在拍照界面查看、修改预设**。或参考 PC **长按跳转预设配置
 * 详情**。"）的纯逻辑护栏。
 *
 * 这份测试盯的是**四条口径**（每条都对应一个会静默出错的坑）：
 *  1. **overrides 合成**：用户拖的值优先于预设自己的记录，未拖的槽一位不动；
 *  2. **切预设 = 清空微调**：上一条预设的调整不许串到新预设上（`presetId` 判据是结构性的，
 *     不靠"记得清空"的调用点）；
 *  3. **保存回预设的 slots 口径**（本卡唯一的既有口径变更）：`[0]` 曝光偏移**能存上**
 *     （用户明确在调它），而用户自己的 EV 补偿 / 机型基准曝光**仍进不了预设**（U-7 的纪律）；
 *  4. **参数表按拍照链路白名单过滤的判据**：能拖 = 引擎真的读 + 参数面板真的有滑杆，
 *     其余一律灰显 + 原因（ADR-12：不做假滑杆）。
 *
 * 真实数据来源 = `app/src/main/assets/presets/builtin.json`（工作目录 = `app/`，与
 * [CameraPresetPanelUiTest] / [PresetPanelUiTest] 同一口径）—— 用真实内置预设断言，
 * 而不是手搓一份"想象中的预设"。
 */
class CameraPresetEditTest {

    private val builtins = BuiltinPresets.parse(File("src/main/assets/presets/builtin.json").readText())

    private fun builtin(name: String): EditorPreset =
        builtins.firstOrNull { it.name == name } ?: error("内置包里没有「$name」")

    private fun userPreset(id: String, slots: Map<Int, Double>) =
        EditorPreset(id = id, name = "预设 $id", slots = slots)

    /** 一次拖动的结果（`null` = 还没有任何微调 —— 与面板的初始状态同型）。 */
    private fun drag(
        from: CameraParamOverrides?,
        preset: EditorPreset,
        slot: Int,
        uiValue: Float,
    ): CameraParamOverrides = from.afterEdit(preset, CAMERA_TUNABLE_SLOT_PARAM.getValue(slot), uiValue)

    // ── ① overrides 合成口径 ──────────────────────────────────────────────────

    /**
     * 拖动 ⇒ 只影响该参数写的槽；其余槽**逐位保持预设的配方**；预设没记的槽保持基线值。
     *
     * 反例（本卡要防的）：把 overrides 当成"整包参数"用 ⇒ 用户拖一下对比度，颗粒/暗角全被
     * 打成默认值（"改一项、丢一片"）。
     */
    @Test
    fun overridesComposeOnTopOfThePresetRecipe() {
        val p = userPreset("p_1", slots = mapOf(4 to 1.2, 26 to 20.0))
        val base = cameraPresetParams(p)
        assertEquals("预设的对比度已展开", 1.2, base[4], 1e-9)

        val none: CameraParamOverrides? = null
        val after = drag(none, p, slot = 4, uiValue = 0.8f)
        assertEquals("微调必须记在**这条预设**名下", "p_1", after.presetId)
        assertEquals("只记被拖过的那一个槽", setOf(4), after.values.keys)

        val film = cameraPresetParams(p, after.values)
        assertEquals("overrides 优先于预设的配方", contrastSlotOf(0.8f), film[4], 1e-9)
        assertEquals("未拖的槽保持预设值（颗粒不能被重置）", 20.0, film[26], 1e-9)
        assertEquals("预设没记的槽保持基线（[0] 曝光 = 0）", 0.0, film[0], 1e-9)
    }

    /** 拖回**预设原值** ⇒ 该槽从微调里消失（= 撤销这次调整，而不是"存一个等于原值的覆盖"）。 */
    @Test
    fun draggingBackToTheRecipeValueDropsTheOverride() {
        val p = userPreset("p_1", slots = mapOf(4 to 1.2))
        val none: CameraParamOverrides? = null
        val after = drag(none, p, slot = 4, uiValue = contrastUiOf(1.2))
        assertTrue("拖回原值 ⇒ 没有微调（按钮该是灰的）", after.values.isEmpty())
    }

    /**
     * 多槽参数（饱和度一条滑杆写 `[20..22]` 三槽）⇒ 三个槽一起记账。
     *
     * 理由：引擎读的是**三个槽**，只记一个会让"用户调了饱和度、套用时只有中间调变了"。
     */
    @Test
    fun multiSlotParamsRecordEverySlotTheyWrite() {
        val p = userPreset("p_1", slots = mapOf(21 to 1.05))
        val none: CameraParamOverrides? = null
        val after = drag(none, p, slot = 20, uiValue = 1.4f)
        assertEquals("一条滑杆写几槽就记几槽", setOf(20, 21, 22), after.values.keys)
        val film = cameraPresetParams(p, after.values)
        assertEquals("三个槽都生效", film[20], film[22], 1e-9)
        assertNotEquals("确实改了", 1.05, film[21], 1e-6)
    }

    // ── ② 切预设 = 清空（验收判据：微调不许串味）────────────────────────────────

    /**
     * 微调带 `presetId` ⇒ **换了预设就取不到**（[CameraParamOverrides.forPreset] 返回空表）。
     *
     * 这是"切换预设必须清空 overrides"的**结构性**实现：即使某个调用点忘了清，
     * 合成与保存也读不到上一条预设的调整（画面与列表里选中的那条永远一致）。
     */
    @Test
    fun overridesDieWithThePresetTheyBelongTo() {
        val a = userPreset("p_a", slots = mapOf(4 to 1.2))
        val b = userPreset("p_b", slots = mapOf(4 to 0.9))
        val none: CameraParamOverrides? = null
        val ov = drag(none, a, slot = 4, uiValue = 0.7f)
        assertEquals("同一条预设 ⇒ 改动生效", ov, ov.forPreset(a))
        assertNull("换一条预设 ⇒ 取不到（结构性清空）", ov.forPreset(b))

        // 合成路径也必须走 forPreset：B 的数组里不能出现 A 的改动
        val filmB = cameraPresetParams(b)
        assertEquals("B 的画面 = B 的配方", 0.9, filmB[4], 1e-9)
        // 保存路径同理：保存 B 不能把 A 的改动写进 B
        assertEquals(b.slots, cameraPresetSlotsFor(b, emptyMap(), emptySet()))
    }

    // ── ③ 保存回预设：`[0]` 曝光那个坑 ────────────────────────────────────────

    /**
     * ★ **本卡的核心裁决**：详情面板里对曝光 `[0]` 的调整**能存进 `preset.slots[0]`**，
     * 且存进去的是**偏移量本身**（用户在滑杆上看到的那个数），不是
     * `机型基准曝光 + 用户 EV 补偿 + 偏移` 那个绝对值。
     *
     * 背景：旧口径（[cameraPresetSlots]）**无条件丢弃 `[0]`**，理由是"从已展开的相机数组里
     * 分不清 `[0]` 是预设自带还是用户刚调的 EV"（U-7）。PS-3 起用户在详情面板里**明确地**
     * 在调"这条预设的曝光偏移"（读数就是偏移量，见 [effectivePresetEvOffset]）⇒ 语义清楚，
     * 丢弃它会让用户困惑"调了存不上"（用户 2026-10-11 的原话诉求）。
     *
     * 三条断言分别钉住：① 存得上；② 存的是偏移不是拍摄态；③ 拖回 0 ⇒ 该槽消失（= 不改变
     * 曝光，与"缺失字段 = 保留当前值"的既有语义一致）。
     */
    @Test
    fun savingKeepsTheExposureOffsetTheUserDialedIn() {
        val p = userPreset("p_1", slots = mapOf(4 to 1.2))
        val none: CameraParamOverrides? = null
        val ov = drag(none, p, slot = 0, uiValue = 1.0f)
        val offset = expSlot(1.0f)
        assertEquals("微调记的是**偏移量**", offset, ov.values.getValue(0), 1e-9)

        val slots = cameraPresetSlotsFor(p, ov.values)
        assertEquals("① `[0]` 的调整能存上（不再是「调了存不上」）", offset, slots.getValue(0), 1e-9)
        assertEquals("② 其余槽照常带走", 1.2, slots.getValue(4), 1e-9)
        assertEquals(
            "② 存的必须**只是偏移**：机型基准曝光（+0.7）与用户 EV 补偿都不在其中" +
                "（它们活在 CameraController 里，本函数看不到）",
            offset,
            slots.getValue(0),
            1e-9,
        )
        assertEquals(
            "下发用的生效偏移 == 保存的值（同一份口径，不可能一个显示一个存）",
            offset,
            effectivePresetEvOffset(p.slots, ov.values),
            1e-9,
        )

        // ③ 拖回 0 = 不改变曝光 ⇒ 稀疏化丢掉该槽（不是"把曝光打到 0"）
        val zero = drag(none, p, slot = 0, uiValue = 0.0f)
        assertFalse("拖回中性 ⇒ 曝光槽从预设里消失", cameraPresetSlotsFor(p, zero.values).containsKey(0))
    }

    /**
     * **旧口径的回归哨兵**：[cameraPresetSlots]（"从已展开的相机数组构造"那条路径）**仍然**
     * 丢弃 `[0]` —— 那是 U-7 的裁决，PS-3 只改了"详情面板明确调整"这一条路径，没有放松纪律。
     *
     * 任何"顺手把 `- CAMERA_PRESET_EV_SLOT` 删掉"的改动都必须让这条红。
     */
    @Test
    fun legacySlotsStillDropTheExposureSlot() {
        val film = CameraPresets.standard().also {
            it[0] = 1.5      // 模拟"已展开的当前曝光"（分不清来源 ⇒ 不该入库）
            it[4] = 1.2
        }
        val slots = cameraPresetSlots(film)
        assertFalse("旧路径仍丢 [0]（U-7 纪律不变）", slots.containsKey(CAMERA_PRESET_EV_SLOT))
        assertEquals("其余改动照常入库", mapOf(4 to 1.2), slots)
    }

    /** 「另存为新预设」（内置只读的唯一保存路径）：配方 = 内置配方 + 微调，身份 = 新用户预设。 */
    @Test
    fun saveAsCopiesTheRecipePlusTheOverrides() {
        val src = builtin("徕卡一瞬")
        val none: CameraParamOverrides? = null
        val ov = drag(none, src, slot = 4, uiValue = 1.4f)
        val copy = newCameraPresetFrom(
            base = src, name = "  我的徕卡  ", group = " 街拍 ", overrides = ov.values,
            lutId = src.lutId, lutStrength = src.lutStrength,
            now = 1_700_000_000_000L, salt = 5,
        )
        assertTrue("新 id（不撞内置）", copy.id.startsWith("p_1700000000000_"))
        assertEquals("另存的永远是用户预设（可编辑）", PRESET_SOURCE_USER, copy.source)
        assertEquals("名称去空格", "我的徕卡", copy.name)
        assertEquals("分组去空格", "街拍", copy.group)
        assertEquals("LUT 跟随被另存的那条预设", src.lutId, copy.lutId)
        assertEquals(src.lutStrength, copy.lutStrength, 1e-9)
        assertEquals("配方 = 内置配方 + 微调", cameraPresetSlotsFor(src, ov.values), copy.slots)
        assertNotEquals("微调确实进了新预设", src.slots[4], copy.slots[4])
    }

    // ── ④ 参数表按拍照链路白名单过滤的判据（ADR-12：不做假滑杆）──────────────────

    /**
     * 可拖的槽 **恰好** = 拍照链路白名单 − 没有滑杆的槽。
     *
     * 这条是"参数表按拍照链路过滤"的机器判据：白名单加了槽却忘了加滑杆（或反过来，
     * 给一个引擎不读的槽配了滑杆）都会红。
     */
    @Test
    fun tunableSlotsAreExactlyTheCameraPathMinusTheSliderlessOnes() {
        // ★ 引擎插槽重构（阶段 1）：本式原为 `PATH − NO_SLIDER`，隐含前提"白名单里除'无滑杆'
        // 之外**都是可调参数**"。`[98]` 引擎插槽打破了这个前提 —— 它**进预设、拍照链路读、
        // 但不是可调参数**（是"选哪个引擎"；阶段 3 走**引擎选择面**）⇒ 必须再减去
        // [CAMERA_ENGINE_SELECT_SLOTS]。三者语义区分：
        //   PATH          = 拍照链路读的槽（**含非参数槽**，如 [98]）
        //   NO_SLIDER     = **是参数**、但暂无滑杆（颗粒 [26][27][60][61]）⇒ 只读组
        //   ENGINE_SELECT = **不是参数**（[98]）⇒ 既无滑杆，也不进只读组
        // 判据强度不变：仍是**精确相等**（多一个 = 假滑杆，少一个 = 能力被藏）。
        assertEquals(
            "可拖的槽必须 = 拍照链路读的槽 − 没有滑杆的槽 − 非参数槽" +
                "（多一个 = 假滑杆，少一个 = 能力被藏）",
            CAMERA_PATH_SLOTS - CAMERA_NO_SLIDER_SLOTS - CAMERA_ENGINE_SELECT_SLOTS,
            CAMERA_TUNABLE_SLOT_PARAM.keys,
        )
        // 没有滑杆的槽确实在拍照链路上（它们不是"不生效"，是"UI 还没给滑杆"）
        assertTrue("无滑杆集合必须是白名单的子集", CAMERA_PATH_SLOTS.containsAll(CAMERA_NO_SLIDER_SLOTS))
        // 非参数槽也在拍照链路上（[98] 由 passFilm 读取，用于"非 0 ⇒ 降级 + WARN"判据）
        assertTrue("非参数槽必须是白名单的子集", CAMERA_PATH_SLOTS.containsAll(CAMERA_ENGINE_SELECT_SLOTS))
    }

    /**
     * 每个"可拖"的槽与它的滑杆**真的绑定在这个槽上**：把量程两端写进数组，该槽的值必须不同。
     *
     * 为什么需要（集合相等抓不到的错）：把 `[63]` 绑到 `[62]` 的滑杆上时，键集合完全正确，
     * 但用户拖"光晕 · 触发阈值"改的是强度 —— 典型的"滑杆动了、画面动的不是那一项"。
     */
    @Test
    fun everyTunableSlotIsBoundToItsOwnParam() {
        for ((slot, param) in CAMERA_TUNABLE_SLOT_PARAM) {
            val lo = CameraPresets.standard().also { param.write(it, param.range.start) }
            val hi = CameraPresets.standard().also { param.write(it, param.range.endInclusive) }
            assertNotEquals(
                "滑杆「${param.key}」的 write 没有写到槽 [$slot]（绑定错了 ⇒ 拖了没反应/改了别的项）",
                lo[slot],
                hi[slot],
                1e-9,
            )
            // 读数必须能反映该槽（否则清单里的值不会跟着变）
            assertNotEquals(
                "滑杆「${param.key}」的 read 没有读槽 [$slot]",
                param.read(lo),
                param.read(hi),
                1e-6f,
            )
        }
    }

    /** 灰显判据 + 原因文案：可调 ⇒ 无提示；不可调 ⇒ 必须有原因（不许有一排没反应的灰行）。 */
    @Test
    fun everyNonTunableRowCarriesAReason() {
        assertEquals(CameraParamAdjustability.TUNABLE, cameraParamAdjustability(4))
        assertNull("可调项不占提示行", cameraParamAdjustabilityNote(4))
        assertEquals(CameraParamAdjustability.NO_SLIDER, cameraParamAdjustability(26))
        assertNotNull("颗粒没有滑杆 ⇒ 要说清", cameraParamAdjustabilityNote(26))
        assertEquals(CameraParamAdjustability.CAMERA_IGNORED, cameraParamAdjustability(69))
        assertNotNull("拍照不读的槽 ⇒ 要说清", cameraParamAdjustabilityNote(69))

        // `[69..77]` 层间效应：拍照链路（passFilm/passOutput）零引用 ⇒ 一律灰显
        for (s in 69..77) {
            assertEquals(
                "[$s] 不在拍照链路白名单里 ⇒ 必须标成 CAMERA_IGNORED（给它滑杆 = ADR-12 的假滑杆）",
                CameraParamAdjustability.CAMERA_IGNORED,
                cameraParamAdjustability(s),
            )
        }
        // 白名单里的每一项都必须落到"可调"或"无滑杆"两档之一（不许有第三态）
        for (s in CAMERA_PATH_SLOTS) {
            assertNotEquals(
                "[$s] 在拍照链路白名单里 ⇒ 不该被判成 CAMERA_IGNORED",
                CameraParamAdjustability.CAMERA_IGNORED,
                cameraParamAdjustability(s),
            )
        }
    }

    /** 生效偏移的回落链：`overrides[0]` → `slots[0]` → 0.0（缺失 = 不改变曝光）。 */
    @Test
    fun effectiveEvOffsetFallsBackToThePresetRecord() {
        assertEquals("没有预设记录、没有微调 ⇒ 0", 0.0, effectivePresetEvOffset(emptyMap(), emptyMap()), 1e-9)
        assertEquals("预设自带 ⇒ 用它", 0.5, effectivePresetEvOffset(mapOf(0 to 0.5), emptyMap()), 1e-9)
        assertEquals(
            "用户拖过 ⇒ 用用户的（绝对值，不是叠加）",
            -1.0,
            effectivePresetEvOffset(mapOf(0 to 0.5), mapOf(0 to -1.0)),
            1e-9,
        )
        assertEquals("与旧函数同源（无微调时逐位相等）", presetEvOffset(mapOf(0 to 0.5)), effectivePresetEvOffset(mapOf(0 to 0.5), emptyMap()), 1e-9)
    }

    // ── ⑤ 接线哨兵（源码级）：切预设清空 + 拖动直通引擎 ─────────────────────────

    // ── ⑥ ★ PS-3 P0：内置预设**可改**（覆盖层）+「恢复默认」────────────────────

    /**
     * 用户 2026-10-11 拍板："内置预设为什么不让修改？我觉得没有必要，**最多限制不能删除就行**"。
     *
     * 覆盖层（[applyBuiltinOverrides]）只取**用户可改的字段**（名称 / 分组 / 配方 / LUT），
     * **身份字段永远取出厂值** ⇒ 两条性质同时成立：
     *  ① 改过的内置**仍然不可删除**（`source` 还是 builtin —— 删除保护在数据层被钉住，
     *     不靠 UI 自觉，手改 prefs 也绕不过去）；
     *  ② `id` / `createdAt` 不漂移（列表 key、冷启动恢复选中项、`createdAt <= 0` 显示 `—`）。
     */
    @Test
    fun builtinOverridesKeepFactoryIdentitySoTheyStayUndeletable() {
        val src = builtin("徕卡一瞬")
        assertFalse("前提：出厂内置不可删", presetDeletable(src))
        // 用户把内置改了名 + 换了配方（模拟「保存到预设」写进覆盖层的那一条）
        val edited = src.copy(
            name = "我的徕卡",
            group = "自定义",
            slots = mapOf(4 to 1.3),
            lutId = null,
            lutStrength = 1.0,
            createdAt = 1L,          // 用户数据里的脏值：覆盖层不许把它带进来
        )
        val applied = applyBuiltinOverrides(listOf(src), mapOf(src.id to edited)).single()
        assertEquals("用户改的字段生效", "我的徕卡", applied.name)
        assertEquals("用户改的字段生效", "自定义", applied.group)
        assertEquals("用户改的配方生效", mapOf(4 to 1.3), applied.slots)
        assertNull("用户摘掉的 LUT 生效", applied.lutId)
        assertEquals("① id 仍取出厂值（列表 key / 冷启动恢复都认它）", src.id, applied.id)
        assertEquals("① source 仍取出厂值（删除保护的唯一判据）", src.source, applied.source)
        assertFalse("① 改过的内置**仍然不可删除**", presetDeletable(applied))
        assertEquals("② createdAt 取出厂（内置没有「创建时刻」这个语义）", src.createdAt, applied.createdAt)
    }

    /**
     * **版本升级性质**（覆盖层的存在理由）：未被改过的内置**跟着出厂定义走**
     *（新版本改了配方 ⇒ 用户吃得到）；改过的保留用户版本。
     */
    @Test
    fun unmodifiedBuiltinsFollowTheFactoryDefinition() {
        val a = builtin("徕卡一瞬")
        val b = builtin("理光负片")
        val bEdited = b.copy(name = "我的理光", slots = mapOf(4 to 1.5))
        val applied = applyBuiltinOverrides(listOf(a, b), mapOf(b.id to bEdited))
        assertEquals("没改过的那条 = 出厂原样（升级跟得上）", a, applied[0])
        assertEquals("改过的那条 = 用户版本", "我的理光", applied[1].name)
        assertEquals(mapOf(4 to 1.5), applied[1].slots)
        // 覆盖表里有、出厂列表里没有的 id（旧版本内置已下线）⇒ 忽略，不凭空造预设
        assertEquals(
            "覆盖表里的野 id 不许变出新预设",
            listOf(a, b),
            applyBuiltinOverrides(listOf(a, b), mapOf("builtin_gone" to bEdited.copy(id = "builtin_gone"))),
        )
    }

    // ── ⑦ ★ PS-6a：没有「移除此项」这个概念之后，等效能力在哪 ──────────────────

    /**
     * ★ PS-6a 口径变更（用户 2026-10-12："预设参数勾你妈的勾选，丑死"）：勾选树连同
     * 「移除该项」（[CameraParamOverrides.removed] 的 **UI 入口**）一起废弃。**能力没有丢**：
     * 把一项调回**中性值**再保存 ⇒ 稀疏化把它丢掉 ⇒ 这条预设不再覆盖它（与"缺失字段 = 套用时
     * 保持不动"逐字同义）。本用例钉住这条替代路径。
     */
    @Test
    fun draggingASlotBackToNeutralDropsItFromTheSavedRecipe() {
        val p = userPreset("p_1", slots = mapOf(4 to 1.2, 26 to 20.0))
        val none: CameraParamOverrides? = null
        val back = none.afterEdit(p, CAMERA_TUNABLE_SLOT_PARAM.getValue(4), contrastUiOf(1.0))
        assertTrue("拖回中性值 ⇒ 记下这次调整（与预设原值 1.2 不同 ⇒ 按钮该亮）", back.values.containsKey(4))
        val slots = cameraPresetSlotsFor(p, back.values, back.removed)
        assertFalse("保存时值 == 中性基线 ⇒ 被稀疏化丢掉（= 这条预设不再覆盖对比度）", slots.containsKey(4))
        assertEquals("其余字段一位不动", mapOf(26 to 20.0), slots)
    }

    /**
     * [CameraParamOverrides.removed] 的**数据通路仍然有效**（`CameraScreen.previewCameraParams`
     * 一位未改，PS-5 修的 `effectivePresetEvOffset(..., removed)` 还在）：本卡只是不再从 UI 产生
     * removed。将来「完整参数」页若需要"不包含某一项"，直接用它即可 —— 这里钉住语义，
     * 避免通路在无人使用时被当成死代码误删。
     */
    @Test
    fun theRemovalPathStillWorksAtTheDataLayerWithoutAUiEntry() {
        val p = userPreset("p_1", slots = mapOf(0 to 0.5, 4 to 1.2))
        val removed = setOf(0, 4)
        assertEquals("数组回基线", 0.0, cameraPresetParams(p, emptyMap(), removed)[0], 1e-9)
        assertEquals(
            "偏移跟着回 0（PS-5 修的那条口径仍在数据层生效）",
            0.0,
            effectivePresetEvOffset(p.slots, emptyMap(), removed),
            1e-9,
        )
        assertTrue("保存后两项都不在预设里", cameraPresetSlotsFor(p, emptyMap(), removed).isEmpty())
    }

    /**
     * ★ PS-3 P1：参数表是**全量**的 —— 拍照链路可调的 35 项一个不少，且
     * **未包含的项显示"未设置"（value = null），绝不拿当前相机值冒充**。
     *
     * 为什么这条重要：套用这条预设时未包含的字段**保持不动**，显示当前值会让用户以为预设里
     * 存着它（那是在骗用户）。
     */
    @Test
    fun paramTableShowsEveryTunableSlotAndNeverFakesUnsetOnes() {
        val p = builtin("徕卡一瞬")
        val tunable = CAMERA_TUNABLE_SLOT_PARAM.keys
        val sections = cameraParamSections(p.slots, tunable)
        val rows = sections.flatMap { it.rows }
        assertEquals("全量：可调的每一项都在表里", tunable.size, rows.size)
        assertEquals("槽位不重复", tunable.size, rows.map { it.slot }.toSet().size)
        assertEquals(
            "分组顺序沿用 §9.2 的四组（+ 兜底组），不新造一套",
            listOf("基础调整", "色彩", "影调曲线", "胶片质感"),
            sections.map { it.title },
        )
        // 已包含 ⇒ 读数 == 详情页的读数（同一套格式化）；未包含 ⇒ null（"未设置"）
        val detail = presetDetailSections(p.slots).flatMap { it.rows }.associateBy { it.slot }
        for (r in rows) {
            if (p.slots.containsKey(r.slot)) {
                assertEquals("槽 ${r.slot} 的读数必须与详情页逐字一致", detail.getValue(r.slot).value, r.value)
            } else {
                assertNull("槽 ${r.slot} 未包含 ⇒ 必须显示「未设置」，不许用当前相机值冒充", r.value)
            }
        }
        // 内置「徕卡一瞬」确实同时有"已包含"与"未设置"两种行（这条测试才有意义）
        assertTrue("应有已包含项", rows.any { it.value != null })
        assertTrue("应有未设置项", rows.any { it.value == null })
    }

    /**
     * **切预设清空微调**必须真的写在套用路径上（`applyCameraPreset`），不能只靠数据结构的
     * 兜底 —— 因为"清空"还承担一件事：让画面立刻回到新预设的配方（state 清了但画面没重设
     * 就会出现"UI 说 A、画面是 B"）。
     *
     * 同时钉住"拖动直通引擎"的接线：`previewCameraParams` 存在且**不写 state**（否则每帧
     * 重组整个 CameraScreen）。
     */
    @Test
    fun cameraScreenWiresClearingAndLivePreview() {
        val src = File("src/main/java/com/filmkit/app/ui/CameraScreen.kt").readText()
        val applyFn = src.substringAfter("fun applyCameraPreset(").take(4000)
        assertTrue(
            "套用预设时必须清空上一条的微调（画面与列表选中的那条必须一致）",
            applyFn.contains("paramOverrides = null"),
        )
        assertTrue("拖动实时下发必须有独立入口（不写 state）", src.contains("fun previewCameraParams("))
        val previewFn = src.substringAfter("fun previewCameraParams(").take(1200)
        assertFalse(
            "实时下发里不许写 Compose state（每帧写会把整个 CameraScreen 拖进重组）",
            previewFn.contains("paramOverrides ="),
        )
        assertTrue(
            "实时下发必须同时传 evOffset（`[0]` 只写数组会被 setFilmParams 覆盖掉）",
            previewFn.contains("effectivePresetEvOffset("),
        )
        assertTrue("松手提交微调的回调必须接上", src.contains("onOverrides = { paramOverrides = it }"))

        // ★ PS-3 不变量：**编辑对象 == 生效对象** —— 进详情会把该预设套用成当前生效的那条。
        //   没有它，"拖动即改、取景实时生效"就没有明确归属（画面只有一份，滑杆改的可能是
        //   另一条预设的画面）。
        val panel = File("src/main/java/com/filmkit/app/ui/CameraPresetPanel.kt").readText()
        assertTrue(
            "进详情必须让该预设成为当前生效的（见 openEdit 的不变量）",
            panel.contains("if (p.id != selected.id) onApply(p)"),
        )
        // ★ PS-3 P0：**新建态也必须能调参数**（用户："新建预设时目前参数居然不可配置？？（参数
        //   点了没反应）"）—— 新建态以当前生效预设为底，参数表同样给滑杆（params 不能为 null）。
        assertTrue(
            "新建态 / 主视图的参数表必须以当前生效预设为底（否则清单只读 = 用户点不动）",
            panel.contains("val target = editor ?: selected"),
        )
        assertTrue(
            "新建态也要给滑杆（params 恒非 null）",
            panel.contains("params = cameraPresetParams(target, values, removed)"),
        )
    }
}

/** 测试内的槽位换算助手（与参数表同源：`contrastSlot` 是 UI 值 → 槽位值的唯一实现）。 */
private fun contrastSlotOf(ui: Float): Double = com.filmkit.app.camera.contrastSlot(ui)

/** 槽位值 → UI 值（[contrastSlotOf] 的逆，用于"拖回原值"的用例）。 */
private fun contrastUiOf(slot: Double): Float = com.filmkit.app.camera.contrastUi(slot)
