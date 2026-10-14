package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 3 判据 1–4（`docs/engine-slots-design.md` §8.1 D）：引擎切换的**参数旁路**收口
 * [engineScoped]（= [EngineScope.applyEngineScope] + 从数组读 `[98]`）。
 *
 * 为什么这值得一组测试：本机制的全部价值是"切到引擎 1 后引擎 0 的参数**真的不生效**"，
 * 而它的失败方式是**静默**的 —— 少归零一个槽位不会崩、不会编译失败，只会让用户"关掉的开关
 * 还在起作用"（正是本卡要修的那个 bug）。四条判据里两条（1 零回归 / 4 无副作用）靠的是
 * **实例语义**，只能由测试钉住。
 *
 * ⛔ `[28] tone_strength` 的恒等值是 **1.0（= 它的默认值），不是 0.0** —— 卡 C 2026-10-14 用离线链
 * 实跑证明：引擎 1 的 main 注入段（`FrameRenderer.cpp:6603` 的 `kFsSigmoidMain`）把 `uToneK`
 * **复用**为整条 sigmoid 曲线的应用权重（`sigmoidTone` 全文件只有那一个调用点）⇒ 置 0 会让引擎 1
 * **完全空转**（结果与"引擎 0 + uToneK=0"**逐字节相同**，crc32 `0x189e4126`）。
 * "`mix(lin, toned, 0) = lin` ⇒ 恒等 0"的推理**只在引擎 0 成立**（引擎 0 的 `toned` 才是基准影调）。
 *
 * ⚠ `[22] sat_high` 也在表内：**一个「饱和度」滑杆同写 `[20][21][22]`**
 *（`EditorExport.writeSaturation`），只钉前两槽会留下"高光档仍跟用户值走"的**半旁路**。
 *
 * `[98]` **未注册**（不在 [EngineScope.KNOWN_ENGINE_SLOTS]）时按 0 处理 ⇒ 返回同一实例、**不旁路**，
 * 与 C++ `warnEngineSlotUnavailable` 的降级语义一致（否则会出现"引擎 0 + 参数被清零"的第三态）。
 *
 * ⚠ `[35] auto_tone` 的归零理由是**成员变量副作用**（卡 A 2026-10-14 审计）：
 * `updateFilmicEV` 在 `passFilm` 里无条件调用（`FrameRenderer.cpp:14014`），uniform 落 location=−1
 * 但 `last_still_w999_`（`:13865`，由 `auto_on` 门控）照写 → `Bridge.cpp:913` 的 `sw999` →
 * `CameraController.sceneHighlightEv()` → `ExposurePlanner` 欠曝量 ⇒ 仍会改变成片曝光。
 * （架构师同日的另一条更正：HR 的白点**已解耦** —— `:12439 (void)white_ev_input_domain`，
 * v0.10.145 起 threshold 只依赖 CCM 第二列范数 ⇒ 测试不依赖"HR 白点"这条论据。）
 *
 * 口径：判据里的"用户值"用与恒等值不同的量级（±0.3 ~ ±12），避免"恰好等于恒等值"造成的假绿。
 */
class EngineScopeTest {

    /**
     * 阶段 3 定案的旁路表（§8.1 B2 + 卡 A 的 `[35]` + 卡 C 的 `[28]` 值更正与 `[22]`）——
     * **逐槽硬编码**：表被误删一行 / 误加一行同样是静默漂移，必须让测试红。
     */
    private val expectedNeutralized: Set<Int> =
        setOf(28, 20, 21, 22, 36, 35, 5, 6, 7, 8, 9, 10, 37, 38)

    /**
     * 判据 3 的"通用槽位"（引擎 1 下必须**保留用户值**）：曝光 / 外观层 / 单色 / 影调分区。
     * 与 [EngineScope.NEUTRALIZE] 必须**不相交** —— 一个槽同时在两侧 = 语义自相矛盾。
     */
    private val generalSlots: Set<Int> = setOf(0, 19, 25, 90, 91, 92, 93)

    /** 每个用例都从**基线**出发：预置的槽位值会掩盖"逐位不变"类断言。 */
    private fun baseline(engineSlot: Int): DoubleArray =
        CameraPresets.standard().also { it[98] = engineSlot.toDouble() }

    // ── 判据 1：engine_slot = 0 ⇒ 同一实例 + 逐位不变（阶段 2 零回归）───────────

    @Test
    fun slotZeroReturnsSameInstanceWithEveryScopedSlotUntouched() {
        val p = baseline(0)
        // 故意把**所有**会被旁路的槽位写成非恒等值：slot = 0 时一个都不许动
        for (s in EngineScope.NEUTRALIZE.keys) p[s] = -12345.0
        val before = p.copyOf()

        val out = engineScoped(p)

        assertSame("engine_slot = 0 必须原样返回入参（同实例、不复制）—— 零回归硬要求", p, out)
        assertArrayEquals("engine_slot = 0 不得触碰任何槽位（逐位不变）", before, out, 0.0)
    }

    @Test
    fun slotZeroIsReadFromSlot98NotHardcoded() {
        // 收口的 `[98]` 读值口径：只有 0 走"同实例"分支
        val p = baseline(0)
        assertSame("读 [98] = 0 ⇒ 同实例", p, engineScoped(p))
        p[98] = 1.0
        assertNotSame("读 [98] = 1 ⇒ 副本", p, engineScoped(p))
    }

    // ── 判据 2：engine_slot = 1 ⇒ 被旁路槽位置恒等值 ──────────────────────────

    @Test
    fun slotOneNeutralizesCommonSegmentSlots() {
        // 公共段：两引擎共用 shader 段 ⇒ 不置恒等就仍然生效（"隐藏开关 ≠ 旁路"的根因）
        val p = baseline(1)
        // ⚠ 探针值必须 **≠ 恒等值**：`[20]/[21]/[22]/[28]` 的恒等值都是 1.0，探针若也写 1.0，
        //    "旁路生效"与"旁路没生效"给出同一个数组（假绿）—— 下面逐槽显式守卫。
        val probes = mapOf(
            28 to 0.35,   // tone_strength：⛔ 恒等 = 1.0（**不是 0.0**，那是引擎 1 空转）
            20 to 2.5,    // sat_shadow 乘性
            21 to 0.3,    // sat_mid 乘性
            22 to 0.42,   // sat_high 乘性（同一「饱和度」滑杆的第三分量）
            36 to 0.75,   // local_tone 强度
        )
        for ((s, v) in probes) p[s] = v

        val out = engineScoped(p)

        for ((s, v) in probes) {
            assertTrue("[$s] 探针 $v 恰好等于恒等值 ⇒ 本用例对该槽假绿", v != EngineScope.NEUTRALIZE[s])
        }
        assertEquals(
            "[28] tone_strength 恒等值 = 1.0 —— 引擎 1 的 uToneK 是 sigmoid 曲线的应用权重，" +
                "置 0 会让引擎 1 完全空转（与引擎 0 + uToneK=0 逐字节相同）",
            1.0, out[28], 0.0,
        )
        assertEquals("[20] sat_shadow 乘性恒等值 = 1.0", 1.0, out[20], 0.0)
        assertEquals("[21] sat_mid 乘性恒等值 = 1.0", 1.0, out[21], 0.0)
        assertEquals("[22] sat_high 乘性恒等值 = 1.0（只钉 [20][21] = 半旁路）", 1.0, out[22], 0.0)
        assertEquals("[36] local_tone 恒等值 = 0.0（引擎侧 0 = 关）", 0.0, out[36], 0.0)
    }

    @Test
    fun slotOneNeutralizesAutoToneSlot() {
        // [35] auto_tone：**不是**靠 uniform 生效（那是漏判的根源），而是靠成员变量副作用
        // （见类 KDoc 的漏径说明）⇒ 必须显式归零。恒等 = 0.0（FilmParams.h:141 "0 = 关"）。
        val p = baseline(1)
        p[35] = 1.0

        val out = engineScoped(p)

        assertEquals("[35] auto_tone 恒等值 = 0.0（关）", 0.0, out[35], 0.0)
    }

    @Test
    fun slotOneNeutralizesEditorCurveSegmentSlots() {
        // 编辑器曲线段：`kFsStyle` 的 computeEditorCurve 不随 [98] 切换 ⇒ 也会仍然生效
        val p = baseline(1)
        for (i in 5..10) p[i] = 0.6
        p[37] = -3.0
        p[38] = 2.0

        val out = engineScoped(p)

        for (i in 5..10) {
            assertEquals(
                "[$i] latitude 恒等值 = LATITUDE_UI_PIVOT（0.01，**不是 0**：0 = 极端 S 曲线）",
                LATITUDE_UI_PIVOT.toDouble(), out[i], 0.0,
            )
        }
        assertEquals("[37] 恒等值 = FILMIC_BLACK_EV_PIVOT", FILMIC_BLACK_EV_PIVOT.toDouble(), out[37], 0.0)
        assertEquals("[38] 恒等值 = FILMIC_WHITE_EV_PIVOT", FILMIC_WHITE_EV_PIVOT.toDouble(), out[38], 0.0)
    }

    @Test
    fun slotOneNeutralizesExactlyTheTableSlots() {
        // 全表对拍：**逐槽硬编码**的预期集合（不是拿 NEUTRALIZE 自证）—— 表被误删/误加
        // 一行同样是静默漂移，本用例负责把它变成红。
        assertEquals(
            "NEUTRALIZE 的槽位集合必须逐槽等于阶段 3 定案" +
                "（14 槽：28/20/21/22/36/35 + 5..10 + 37/38）",
            expectedNeutralized, EngineScope.NEUTRALIZE.keys,
        )
        assertEquals("阶段 3 定案 = 14 槽", 14, EngineScope.NEUTRALIZE.size)

        val p = baseline(1)
        for (s in expectedNeutralized) p[s] = 777.0
        // 表外的相邻槽一并写成非恒等值：归零不得外溢（[34]/[39]/[41] 是 LUT/斑马纹/HR 的手动开关）
        p[34] = 777.0
        p[39] = 777.0
        p[41] = 777.0

        val out = engineScoped(p)

        for ((slot, v) in EngineScope.NEUTRALIZE) {
            assertTrue("探针 777.0 恰好等于 [$slot] 的恒等值 ⇒ 本用例对该槽假绿", 777.0 != v)
            assertEquals("[$slot] 必须等于 NEUTRALIZE 表里的恒等值", v, out[slot], 0.0)
        }
        for (s in intArrayOf(34, 39, 41)) {
            assertEquals("[$s] 不在表内 ⇒ 不得被归零（归零外溢 = 误旁路）", 777.0, out[s], 0.0)
        }
    }

    // ── 判据 3：engine_slot = 1 ⇒ 通用槽位保留用户值（防误旁路）────────────────

    @Test
    fun slotOneKeepsGeneralSlotsAndEngineSlotItself() {
        val p = baseline(1)
        val userValues = mapOf(
            0 to -1.25,   // 曝光（公共段但通用）
            19 to 0.37,   // 外观层 cross 矩阵末项
            25 to 1.0,    // 单色
            90 to 0.8,    // highlights
            91 to -0.4,   // shadows
            92 to 0.6,    // whites
            93 to -0.2,   // blacks
        )
        for ((s, v) in userValues) p[s] = v

        val out = engineScoped(p)

        for ((s, v) in userValues) {
            assertEquals("[$s] 是通用槽位 ⇒ 必须保留用户值（防误旁路）", v, out[s], 0.0)
        }
        assertEquals(
            "[98] engine_slot 本身必须保留 1 —— 归零它等于把引擎切回 0（自毁）",
            1.0, out[98], 0.0,
        )
    }

    @Test
    fun neutralizedAndKeptSetsAreDisjoint() {
        // 反向断言：同一个槽**不能**同时出现在"被归零"与"保留用户值"两侧 ——
        // 那两条判据会互相掩盖（[35] 正是从"保留"侧挪到"归零"侧的槽，本用例防它被挪回去
        // 或被复制到两边）。
        val overlap = EngineScope.NEUTRALIZE.keys intersect generalSlots
        assertTrue("同一槽同时被判为归零与保留：$overlap", overlap.isEmpty())
        assertTrue(
            "[35] auto_tone 必须落在**被归零**一侧（漏径：sw999 → 曝光决策）",
            35 in EngineScope.NEUTRALIZE,
        )
        assertFalse("[35] auto_tone 不得出现在保留用户值一侧", 35 in generalSlots)
        assertTrue("[22] sat_high 必须在被归零一侧（同一「饱和度」滑杆的三分量）", 22 in EngineScope.NEUTRALIZE)
        assertFalse("[22] sat_high 不得出现在保留用户值一侧", 22 in generalSlots)
        assertTrue("[28] tone_strength 必须在被归零一侧（恒等值 = 1.0）", 28 in EngineScope.NEUTRALIZE)
    }

    // ── 判据 4：无副作用（返回副本，调用方数组不被修改）────────────────────────

    @Test
    fun slotOneReturnsCopyAndLeavesCallerArrayUntouched() {
        val p = baseline(1)
        // [28] 的恒等值是 1.0 ⇒ 探针用 0.35（写 1.0 会与本用例的"副本里已归零"断言同值 = 假绿）
        p[28] = 0.35
        p[36] = 0.5
        p[35] = 1.0
        p[5] = 0.5
        p[37] = -3.0
        val before = p.copyOf()

        val out = engineScoped(p)

        assertNotSame("engine_slot != 0 必须返回**副本**：调用方数组（滑杆/后续组装持有引用）不得被改", p, out)
        assertArrayEquals("调用方原数组必须逐位不变（否则用户值被永久污染，切回引擎 0 就丢了）", before, p, 0.0)
        assertEquals("副本里已归零（[28] 恒等 = 1.0）", 1.0, out[28], 0.0)
        assertEquals("副本里已归零", 0.0, out[36], 0.0)
        assertEquals("副本里已归零", 0.0, out[35], 0.0)

        // 判据 5 的纯函数部分：切回引擎 0 时下发的是**用户原数组** ⇒ 值原样回来（无串扰）
        val back = engineScoped(p.copyOf().also { it[98] = 0.0 })
        assertEquals("切回引擎 0 必须拿到用户原值", 0.35, back[28], 0.0)
        assertEquals("切回引擎 0 必须拿到用户原值", 0.5, back[5], 0.0)
    }

    // ── 边界：历史快照更短 / [98] 是坏数据或未注册 slot ────────────────────────

    @Test
    fun shortArrayIsTreatedAsSlotZeroWithoutCrash() {
        val short = DoubleArray(50)   // 契约升级前的历史快照
        assertSame("读不到 [98] ⇒ 视作引擎 0（不归零、不越界）", short, engineScoped(short))
    }

    @Test
    fun shortArrayWithSlotOneDoesNotOverrun() {
        val short = DoubleArray(50)
        short[36] = 0.5
        val out = EngineScope.applyEngineScope(short, 1)
        assertEquals("长度不足时不得越界，长度内的槽位照常归零", 0.0, out[36], 0.0)
    }

    @Test
    fun unregisteredEngineSlotIsTreatedAsSlotZero() {
        // 未注册 slot（预设 JSON / 历史快照可手改成任意值）⇒ 按 0 处理、**不旁路**，
        // 与 C++ `warnEngineSlotUnavailable` 的"降级到引擎 0 + 记 WARN"一致。
        // 否则会出现"引擎 0 + filmic 参数被清零"的**第三种状态**（两端语义不一致）。
        val p = baseline(7)
        p[28] = 0.35
        val out = engineScoped(p)
        assertSame("未注册的 [98]=7 ⇒ 同实例、不旁路（委托 C++ 侧降级）", p, out)
        assertEquals("未注册 ⇒ [28] 保持用户值，绝不能被清零", 0.35, out[28], 0.0)
        assertFalse("注册表只有 0/1", 7 in EngineScope.KNOWN_ENGINE_SLOTS)
    }

    @Test
    fun nonFiniteEngineSlotIsTreatedAsSlotZero() {
        val p = baseline(0)
        p[98] = Double.NaN
        p[28] = 0.35
        val out = engineScoped(p)
        assertSame("NaN.toInt() == 0 ⇒ 按引擎 0 走同实例分支（不猜引擎）", p, out)
        assertEquals("同实例分支不归零 ⇒ [28] 保持用户值", 0.35, out[28], 0.0)
    }
}
