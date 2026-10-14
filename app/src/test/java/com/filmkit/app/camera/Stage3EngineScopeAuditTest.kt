package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 3 **独立审计**：判据 1–4 的**边界与完备性**（`docs/engine-slots-design.md` §8.1 D）。
 *
 * 与 `EngineScopeTest`（同一契约的**主用例**，钉"表里每个槽都置成了表里的值"）**分工不同**：
 * 本文件只问三个主用例问不到的问题 ——
 *
 *  1. **完备性（判据 2/3 的真正含义）**：判据要的不是"值等于表里的值"，而是"**改了这个槽，
 *     成片逐字节不变**"。⇒ 本文件对 [EngineScope.NEUTRALIZE] 的**每一个**槽做成对构造
 *     （只在该槽上取不同值）⇒ 证明它是**死参数**；并对 `0 until kCount` 的**其余全部**槽位
 *     断言**逐位保留** ⇒ 证明没有**误旁路**（判据 4）。逐槽手写清单会随契约漂移，这一条不会。
 *  2. **边界**：空数组 / 各种截断长度 / 超出 `kCount` 的数组 / `engineSlot` 为负数·越界·极值
 *     （**未注册 ⇒ 按 0 处理、不旁路**）/ 恒等槽上的 NaN·±Inf / 入参非有限值。
 *  3. **单一真源**：B2 表逐字抄成可执行断言，并**回到 `FilmParams.h` 读默认值**对拍 ——
 *     "恒等值 = 默认值"这条纪律（例外**只剩 [36] 一条**）由机器守，不靠注释。
 *  4. **回归护栏**：把 2026-10-14 审计抓到的三处漏径各自钉一条 —— §P0（`[28]` 置 0 会让引擎 1
 *     空转）、§P1（`uSat` 三分量必须整组在表里）、§P2（未注册 slot 不得走旁路）。
 *
 * ⚠ 判据 5（A→B→A 无串扰）与判据 6（编辑器侧旁路）**不在这里** —— 它们要真机成片，
 * 清单见 `docs/reports/stage3-acceptance-CHECKLIST.md`。本文件只覆盖**纯函数可达**的部分。
 *
 * 工作目录 = `app/`（gradle 单测约定，与 `CameraStyleWhitelistTest` 读 `../core/...` 同口径）。
 */
class Stage3EngineScopeAuditTest {

    /** `FilmParams` 契约的长度（`CameraPresets.COUNT` = 99，含 `[98] engine_slot`）。 */
    private val count = CameraPresets.COUNT

    /**
     * 每个槽一个与该槽恒等值**必然不同**的探针值。
     *
     * 为什么不用同一个常数：`[20]`/`[21]` 的恒等值是 **1.0**、`[5..10]` 是 **0.01** ——
     * 若探针恰好等于恒等值，"旁路生效了"与"旁路没生效"给出同一个数组（假绿）。
     */
    private fun probe(n: Int): DoubleArray = DoubleArray(n) { i ->
        val id = EngineScope.NEUTRALIZE[i]
        if (id == null) 100.0 + i else id + 1.0 + i
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 判据 1：engine_slot = 0 ⇒ 同一实例、逐位不变（阶段 2 零回归的硬要求）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun slotZeroIsTheOnlyIdentityPathAndReturnsTheSameInstance() {
        val a = probe(count)
        val before = a.copyOf()
        val out = EngineScope.applyEngineScope(a, 0)
        assertSame("engine_slot = 0 必须返回**同一实例**（复制一份都会让调用方引用脱钩）", a, out)
        assertArrayEquals("engine_slot = 0 不得触碰任何槽位", before, a, 0.0)
    }

    /**
     * **已注册**的非 0 引擎（当前只有 1）⇒ 走旁路：返回副本 + 表内槽位置恒等。
     *
     * 判据 2/3 的前提：`applyEngineScope` 对引擎 1 必须真的动手。
     */
    @Test
    fun registeredNonZeroEngineSlotTakesTheBypassPath() {
        val a = probe(count)
        for (slot in EngineScope.KNOWN_ENGINE_SLOTS.filter { it != 0 }) {
            val out = EngineScope.applyEngineScope(a, slot)
            assertNotSame("engineSlot=$slot 必须返回副本（不能原地改）", a, out)
            for ((s, v) in EngineScope.NEUTRALIZE) {
                assertEquals("engineSlot=$slot ⇒ [$s] 必须置恒等", v, out[s], 0.0)
            }
        }
    }

    /**
     * **未注册**的 `engine_slot` ⇒ **按 0 处理、不旁路**（P2 修复；2026-10-14）。
     *
     * 为什么这条边界必须有机器判据：`[98]` 是**数据** —— 预设 JSON 是手可改的文本，
     * `engine_slot` 又是已知语义名（`presetsFromJson` 不会拒绝任意数值）⇒ 写入 `2.0` 是可能的。
     * 两种语义二选一：
     *  - ⛔ 旧实现（"非 0 即旁路"）：Kotlin 先把 filmic 参数清零、C++ 再降级到引擎 0
     *    ⇒ 得到**第三种状态**"引擎 0 + filmic 参数被清零"（既不是引擎 0，也不是引擎 1）；
     *  - ✅ 现实现：按 0 处理 ⇒ 与 C++ 的 `warnEngineSlotUnavailable`（降级到引擎 0 + WARN）**同语义**。
     *
     * 所以这里断言的是**同一实例、逐位不变** —— 不是"也走旁路"。
     */
    @Test
    fun unregisteredEngineSlotIsTreatedAsSlotZeroAndNotBypassed() {
        val a = probe(count)
        val before = a.copyOf()
        val unregistered = listOf(-1, -98, Int.MIN_VALUE, 2, 3, 97, 98, 99, 1000, Int.MAX_VALUE)
            .filter { it !in EngineScope.KNOWN_ENGINE_SLOTS }
        assertTrue("本用例的前提是这些值都未注册", unregistered.isNotEmpty())
        for (slot in unregistered) {
            val out = EngineScope.applyEngineScope(a, slot)
            assertSame("未注册 slot=$slot 必须按 0 处理（同实例、不复制）", a, out)
        }
        assertArrayEquals("未注册 slot 不得改写任何槽位", before, a, 0.0)
    }

    @Test
    fun knownEngineSlotsSnapshotMatchesTheContractUnderTest() {
        // 注册表是"哪些值走旁路"的判据来源 ⇒ 它的内容必须显式可见（加第 3 个引擎时两处同步：
        // C++ 的 EngineRegistry 表 + 本常量；EngineSlotUiTest 有直接解析 C++ 表的对拍）。
        assertEquals("已知引擎槽位 = {0, 1}（见 EngineRegistry.cpp 的 kEngines）", setOf(0, 1), EngineScope.KNOWN_ENGINE_SLOTS)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 判据 2/3：被旁路的槽是**死参数** —— 改它，结果逐字节不变
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun changingAnyNeutralizedSlotCannotChangeTheResultAtAll() {
        for (slot in EngineScope.NEUTRALIZE.keys.sorted()) {
            // 成对构造：两个输入**只**在 [slot] 上不同，其余槽逐位相同。
            val x = DoubleArray(count) { 1.0 }
            val y = x.copyOf().also { it[slot] = -12345.5 }
            val rx = EngineScope.applyEngineScope(x, 1)
            val ry = EngineScope.applyEngineScope(y, 1)
            assertArrayEquals(
                "[$slot] 在 engine 1 下必须完全失效（判据 2/3：改它成片逐字节不变）",
                rx, ry, 0.0,
            )
        }
    }

    @Test
    fun theBypassIsNotANoOpForAnyTableSlot() {
        // 反向保险：表里的槽必须**真的**被改写（表值恰好等于探针值时本用例会红，
        // 那说明测试本身假绿，不是实现的问题）。
        val a = probe(count)
        val out = EngineScope.applyEngineScope(a, 1)
        for ((slot, v) in EngineScope.NEUTRALIZE) {
            assertEquals("[$slot] 必须被写成恒等值 $v", v, out[slot], 0.0)
            assertTrue(
                "[$slot] 的探针值 ${a[slot]} 恰好等于恒等值 $v ⇒ 本用例对该槽假绿",
                a[slot] != v,
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 判据 4：防误旁路 —— 表外的**每一个**槽都必须逐位保留
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun everySlotOutsideTheTableIsPreservedBitwise() {
        val a = probe(count)
        val out = EngineScope.applyEngineScope(a, 1)
        val touched = EngineScope.NEUTRALIZE.keys
        val wronglyTouched = ArrayList<Int>()
        for (i in 0 until count) {
            if (i in touched) continue
            if (a[i] != out[i]) wronglyTouched += i
        }
        assertTrue("[$wronglyTouched] 不在 NEUTRALIZE 表里却被改写 ⇒ 误旁路（判据 4）", wronglyTouched.isEmpty())

        // [98] 自己是被旁路的**主语**，归零它 = 把引擎切回 0（自毁）——单列一条，读报错时看得见。
        assertEquals("engine_slot [98] 本身必须原样保留（它得留着让 C++ 选路）", a[98], out[98], 0.0)
        assertFalse("[98] 绝不许出现在 NEUTRALIZE 表里（加进去 = 每次旁路都把引擎切回 0）", 98 in touched)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 边界：数组长度（历史快照更短 / 未来契约更长）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun everyTruncatedLengthIsSafeAndRewritesExactlyTheInRangeTableSlots() {
        val maxTableSlot = EngineScope.NEUTRALIZE.keys.max()
        for (n in 0..maxTableSlot + 2) {
            val a = DoubleArray(n) { 500.0 + it }
            val out = EngineScope.applyEngineScope(a, 1)
            assertEquals("长度 $n：返回数组长度必须与入参相同", n, out.size)
            for (i in 0 until n) {
                val id = EngineScope.NEUTRALIZE[i]
                if (id == null) {
                    assertEquals("长度 $n：[$i] 表外槽必须逐位保留", a[i], out[i], 0.0)
                } else {
                    assertEquals("长度 $n：[$i] 表内槽必须置恒等", id, out[i], 0.0)
                }
            }
        }
    }

    @Test
    fun arraysLongerThanTheContractOnlyHaveTheTableSlotsRewritten() {
        val n = count + 8
        val a = probe(n)
        val out = EngineScope.applyEngineScope(a, 1)
        assertEquals("kCount 之外的尾巴也必须原样带回来（不能悄悄截断）", n, out.size)
        for (i in 0 until n) {
            val id = EngineScope.NEUTRALIZE[i]
            if (id == null) {
                assertEquals("[$i] 表外槽必须逐位保留（含 kCount 之外的尾巴）", a[i], out[i], 0.0)
            } else {
                assertEquals("[$i] 表内槽必须置恒等", id, out[i], 0.0)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 边界：非有限值（NaN / ±Inf）的传播与清理
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun nonFiniteValuesOnTableSlotsAreOverwrittenByTheIdentity() {
        val a = DoubleArray(count) { 1.0 }
        a[28] = Double.NaN
        a[36] = Double.NEGATIVE_INFINITY
        a[5] = Double.POSITIVE_INFINITY
        val out = EngineScope.applyEngineScope(a, 1)
        assertEquals(
            "[28] 的 NaN 必须被恒等值覆盖（NaN 直通 shader = 整幅 NaN，X300 同型事故）" +
                "；⚠ 恒等值是 1.0 不是 0.0（sigmoid 的应用权重，见 §P0）",
            EngineScope.NEUTRALIZE[28]!!, out[28], 0.0,
        )
        assertEquals("[36] 的 −Inf 必须被恒等值覆盖", 0.0, out[36], 0.0)
        assertEquals("[5] 的 +Inf 必须被恒等值覆盖", LATITUDE_UI_PIVOT.toDouble(), out[5], 0.0)
    }

    @Test
    fun nonFiniteValuesOnGeneralSlotsArePropagatedUnchanged() {
        // 表外槽**不**做净化 —— 净化是 [FilmParams] 组装层（`fromDoubles`）与预设读取层的职责，
        // 本函数只做"按引擎作用域归零"。若哪天要求它顺手净化，必须同步改这条用例的意图。
        val a = DoubleArray(count) { 1.0 }
        a[0] = Double.NaN
        a[19] = Double.POSITIVE_INFINITY
        val out = EngineScope.applyEngineScope(a, 1)
        assertTrue("[0] 表外槽的 NaN 必须原样传播（不在本函数的职责内）", out[0].isNaN())
        assertEquals("[19] 表外槽的 +Inf 必须原样传播", Double.POSITIVE_INFINITY, out[19], 0.0)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 纯函数性质：确定性 / 幂等 / 无副作用
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun isPureDeterministicAndIdempotent() {
        val a = probe(count)
        val before = a.copyOf()
        val r1 = EngineScope.applyEngineScope(a, 1)
        val r2 = EngineScope.applyEngineScope(a, 1)
        assertArrayEquals("入参数组不得被修改（滑杆/后续组装持有它的引用）", before, a, 0.0)
        assertArrayEquals("同一输入必须给出同一输出（无隐藏状态）", r1, r2, 0.0)
        assertArrayEquals("幂等：对副本再旁路一次结果不变", r1, EngineScope.applyEngineScope(r1, 1), 0.0)
        // 返回值不得与入参共享底层数组
        assertNotSame("必须返回副本", a, r1)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // §8.1 B2 = 可执行断言（恒等值表的**唯一真源**是 FilmParams.h 的默认值）
    // ══════════════════════════════════════════════════════════════════════════

    private val filmParamsH: String by lazy { File("../core/engine/src/film/FilmParams.h").readText() }

    /**
     * 读 `float <field> = <v>f;` 的默认值。
     *
     * ⚠ **必须经 `Float`**：`FilmParams.h` 的字面量是 `float`（`0.01f`），C++ 侧存进 `float`
     * 字段；Kotlin 侧 `NEUTRALIZE` 的值来自 `LATITUDE_UI_PIVOT: Float` ⇒ 两边都是 float32。
     * 直接用 `String.toDouble()` 会拿到 double 的 `0.01`（= 0.010000000000000000208…），
     * 与 `0.01f.toDouble()`（0.009999999776482582）**不相等** ⇒ 假红。
     */
    private fun defaultOf(field: String): Double {
        val m = Regex("""float\s+${Regex.escape(field)}\s*=\s*(-?[\d.]+)f\s*;""").find(filmParamsH)
        assertNotNull("FilmParams.h 里找不到 `float $field = …f;`（字段被改名？本用例需同步）", m)
        return m!!.groupValues[1].toFloat().toDouble()
    }

    /** 读 `float <field>[3] = {a, b, c};` 的默认值（三通道同值才返回；同样经 `Float`）。 */
    private fun defaultOfArray3(field: String): Double {
        val m = Regex("""float\s+${Regex.escape(field)}\[3\]\s*=\s*\{([^}]*)\}\s*;""").find(filmParamsH)
        assertNotNull("FilmParams.h 里找不到 `float $field[3] = {…};`", m)
        val vals = m!!.groupValues[1].split(',').map { it.trim().removeSuffix("f").toFloat().toDouble() }
        assertEquals("$field 的 R/G/B 默认值不同 ⇒ 本用例的「三槽同值」前提失效，需改成逐通道", 1, vals.distinct().size)
        return vals[0]
    }

    @Test
    fun neutralizeValuesComeFromFilmParamsDefaultsExceptTheSingleDocumentedException() {
        // ── 常规：恒等值 == `FilmParams{}` 的默认值（B2 的"唯一真源"口径）──────────
        assertEquals("[$20] sat_shadow", defaultOf("sat_shadow"), EngineScope.NEUTRALIZE[20]!!, 0.0)
        assertEquals("[$21] sat_mid", defaultOf("sat_mid"), EngineScope.NEUTRALIZE[21]!!, 0.0)
        assertEquals("[$22] sat_high", defaultOf("sat_high"), EngineScope.NEUTRALIZE[22]!!, 0.0)
        assertEquals("shoulder 与 toe 的默认 latitude 必须相同", defaultOfArray3("shoulder"), defaultOfArray3("toe"), 0.0)
        val lat = defaultOfArray3("shoulder")
        for (s in 5..10) {
            assertEquals(
                "[$s] latitude 恒等值必须 = FilmParams.h 的默认 latitude（0.01；**不是 0**：0 = 极端 S 曲线）",
                lat, EngineScope.NEUTRALIZE[s]!!, 0.0,
            )
        }
        assertEquals("[$37] filmic_black_ev", defaultOf("filmic_black_ev"), EngineScope.NEUTRALIZE[37]!!, 0.0)
        assertEquals("[$38] filmic_white_ev", defaultOf("filmic_white_ev"), EngineScope.NEUTRALIZE[38]!!, 0.0)
        // [35] auto_tone：恒等值 = 它的默认值 0.0（"auto_tone 关"），**这条不靠 uniform**
        //（`uFilmic*` 在引擎 1 里 location = −1）而靠**成员变量副作用**：
        // `updateFilmicEV` 在 `passFilm` 里无条件调用 ⇒ `last_still_w999_`(:13865，由 auto_on
        // 门控) → `stillW999()` → `Bridge.cpp:913` → `ExposurePlanner` 的欠曝量 → 成片 `[0]`。
        // ⇒ 它在引擎 1 下**仍会改曝光**，必须显式归零（2026-10-14 卡 A 审计抓出，见 §8.1 B）。
        assertEquals("auto_tone 的默认值必须仍是 0.0（= 黑白点用 [37]/[38]）", 0.0, defaultOf("auto_tone"), 0.0)
        assertEquals("[35] 恒等值 = 0.0（「auto_tone 关」）且必须**在表里**", 0.0, EngineScope.NEUTRALIZE[35]!!, 0.0)
        // [28] tone_strength：**恒等值 = 它的默认值 1.0**（不是 0）。
        // 这是 2026-10-14 卡 C 审计（§P0）的结论：`uToneK` 在引擎 1 的 main 注入段里是 **sigmoid
        // 整条曲线的应用权重**（`kFsSigmoidMain:6603` 的 `if (uToneK > 0.0)`，`sigmoidTone` 全文件
        // 单一调用点）⇒ 置 0 会让引擎 1 **一次都不执行 sigmoid**（与"引擎 0 + uToneK=0"逐字节相同，
        // crc32 `0x189e4126`）。⚠ 原推理 `mix(lin, toned, 0) = lin ⇒ 恒等 = 0` 只在**引擎 0** 成立。
        assertEquals("tone_strength 的默认值必须仍是 1.0（= 基准影调开）", 1.0, defaultOf("tone_strength"), 0.0)
        assertEquals("[28] 恒等值 = 1.0（= 默认值；置 0 会让引擎 1 空转）", 1.0, EngineScope.NEUTRALIZE[28]!!, 0.0)

        // ── 唯一的例外：[36] 恒等 = 0.0，**不是**它的默认值 0.25（引擎侧 0 = 关/A-B 通路）────
        //（P0 修复后 [28] 已回归"恒等 = 默认值"的常规组，例外从两条降为**一条**）
        assertEquals("local_tone 的默认值必须仍是 0.25（标定旋钮）", 0.25, defaultOf("local_tone"), 0.0)
        assertEquals("[36] 恒等值 = 0.0（引擎侧 0 = 关）", 0.0, EngineScope.NEUTRALIZE[36]!!, 0.0)
        // 例外集合**推导**出来，不手抄（手抄等于把被测对象再写一遍）：
        // 定义 = [NEUTRALIZE] 里"恒等值 ≠ FilmParams.h 默认值"的槽。逐个与默认值对拍。
        val fieldOf = mapOf(
            28 to "tone_strength", 20 to "sat_shadow", 21 to "sat_mid", 22 to "sat_high",
            36 to "local_tone", 35 to "auto_tone",
            37 to "filmic_black_ev", 38 to "filmic_white_ev",
        )
        val diverging = EngineScope.NEUTRALIZE.filter { (slot, v) ->
            val default = if (slot in 5..10) defaultOfArray3("shoulder") else defaultOf(fieldOf.getValue(slot))
            v != default
        }.keys
        assertEquals(
            "例外**必须只有 [36]**（恒等 0 = 关 ≠ 默认 0.25）；[28] 已随 §P0 修复回归常规组" +
                "（恒等 = 默认 1.0）—— 若它又出现在这里，先读审计报告 §P0",
            setOf(36), diverging,
        )

        // ── 表形状：B2 表逐条抄录（B2 改了 ⇒ 本断言 + 文档 + KDoc 三处必须同改）─────
        // ⚠ 浮点口径：latitude / 黑白点的恒等值来自 **`Float` 常量**（`LATITUDE_UI_PIVOT`
        //   = `0.01f`、`FILMIC_*_EV_PIVOT` = `-7.65f` / `4.56f`）⇒ 这里也必须写 `..f.toDouble()`，
        //   否则与 `NEUTRALIZE` 差 1e-16 量级而假红（**这不是测试洁癖**：[defaultOf] 一侧同理，
        //   引擎的 FilmParams 字段本身就是 float32 —— 写 double 反而与引擎口径不符）。
        // ⚠ `[20]/[21]/[22]` 的"乘性恒等"是**近似表述**：三槽全 1.0 时
        //   `sat = ws + wm + wh`，而高光端 `ws+wh ≈ 1.0498 > 1` ⇒ `sat ≈ 1.0498`（不是数学恒等）。
        //   但那正是**出厂默认**（`CameraPresets.neutral()` 的 `put3(this,20,1,1,1)`）的行为，
        //   也正是"旁路"要的口径 —— 判据是"与引擎 0 的默认完全一致"，不是数学恒等。
        val b2 = mapOf(
            28 to 1.0,                                  // 公共段：= 默认值（sigmoid 的应用权重！见上）
            20 to 1.0, 21 to 1.0, 22 to 1.0,            // 公共段：乘性恒等（近似；三槽必须**整组**）
            36 to 0.0,                                  // 公共段：局部色调 0 = 关（唯一例外）
            35 to 0.0,                                  // 公共段（副作用通道）：auto_tone 关
            5 to 0.01f.toDouble(), 6 to 0.01f.toDouble(), 7 to 0.01f.toDouble(),   // latitude shoulder
            8 to 0.01f.toDouble(), 9 to 0.01f.toDouble(), 10 to 0.01f.toDouble(),  // latitude toe
            37 to (-7.65f).toDouble(), 38 to 4.56f.toDouble(),                     // 黑白点端点
        )
        assertEquals("NEUTRALIZE 表与 §8.1 B2 不一致（多/少/改了槽位）", b2.keys, EngineScope.NEUTRALIZE.keys)
        for ((slot, v) in b2) {
            assertEquals("[$slot] 恒等值与 §8.1 B2 不符", v, EngineScope.NEUTRALIZE[slot]!!, 0.0)
        }
        assertEquals("表应为 14 槽（6 公共段 + 6 latitude + 2 黑白点）", 14, EngineScope.NEUTRALIZE.size)
    }

    @Test
    fun filmicOwnedSlotsAreEachAccountedForByBypassOrByShaderStructure() {
        // §8.1 B 的处置只有两种：进 NEUTRALIZE（两引擎都读同一个档位 / 靠副作用生效）或
        // "声明段与使用段都被引擎 1 的整体替换吞掉 ⇒ uniform location = −1"（隐藏即可）。
        // 第三种情况 = 漏旁路。
        val shaderAutoBypassed = setOf(40)   // 只剩 [40] gamut：uGamut 声明在 engine_def、
                                             // 唯一使用在 engine_main，两段都被替换
        val unaccounted = EngineScope.FILMIC_OWNED_SLOTS - EngineScope.NEUTRALIZE.keys - shaderAutoBypassed
        assertTrue(
            "filmic 独占槽 $unaccounted 既不在 NEUTRALIZE、也不在「shader 结构自动旁路」名单 ⇒ 漏旁路（判据 2/3 不完备）",
            unaccounted.isEmpty(),
        )
        // 反向：NEUTRALIZE 里的 filmic 侧条目必须真的来自 FILMIC_OWNED_SLOTS（不许凭空加槽）
        assertTrue(
            "NEUTRALIZE 的 [5..10]/[35]/[37]/[38] 必须都是 FILMIC_OWNED_SLOTS 的成员",
            EngineScope.FILMIC_OWNED_SLOTS.containsAll((5..10).toSet() + setOf(35, 37, 38)),
        )
        // [40] 刻意不进表（它由 shader 结构旁路）—— 有人"顺手"加进去时提醒他同步 §8.1 B
        assertFalse("[40] 按 §8.1 B 只隐藏、不进 NEUTRALIZE（uGamut 已被引擎 1 的注入段吞掉）", EngineScope.NEUTRALIZE.containsKey(40))
        // [35] 则相反：**必须**在表里（它不走 uniform，靠成员变量副作用）—— 这条是 2026-10-14 审计抓出的漏径
        assertTrue("[35] auto_tone 必须进 NEUTRALIZE（副作用通道，不受 uniform location 保护）", EngineScope.NEUTRALIZE.containsKey(35))
        // ⚠ **uSat 三分量必须整组在表里**（2026-10-14 卡 C 抓到的 P1 回归护栏）：
        //    一个「饱和度」滑杆同写 [20][21][22]（`EditorExport.writeSaturation`），而 `:14138`
        //    把三槽一起下发到**公共 `tail`** 的 `sat = uSat.x*ws + uSat.y*wm + uSat.z*wh`
        //    ⇒ 只钉两槽会留下"高光档仍跟用户值走"的**半旁路**（`sat = (ws+wm+wh) + wh·(sat_high−1)`）。
        assertTrue(
            "uSat 的三分量必须整组在 NEUTRALIZE（半旁路 = 同一滑杆的槽位拿到互相矛盾的判据）",
            EngineScope.NEUTRALIZE.keys.containsAll(setOf(20, 21, 22)),
        )
        // 引擎 1 现阶段无独占可调参数（固定默认 contrast=1.5 = darktable/PC 官方默认，方案 §7）
        assertTrue("阶段 3 引擎 1 无 UI 参数 ⇒ SIGMOID_OWNED_SLOTS 必须为空", EngineScope.SIGMOID_OWNED_SLOTS.isEmpty())
        // [4] contrast 两条链路都生效 ⇒ 不许被旁路（§8.1 B 的"保留"行）
        assertFalse("[4] contrast 必须保留（编辑器 kFsStyle 仍生效）", EngineScope.NEUTRALIZE.containsKey(4))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 跨卡接口：旁路后的数组必须仍被预设稀疏化认作「恒等」
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 本文件唯一一条**跨模块**判据，守的是阶段 3 两张卡的接缝。
     *
     * 数据流：`engineScoped()` 组装的数组 → 用户在编辑器「保存预设」→
     * `presetSparseSlots(params)`（恒等基线 = [CameraPresets.standard]，容差 [PARAM_EPS]）
     * 决定哪些槽写进 JSON。
     *
     * 若某个 `NEUTRALIZE` 的恒等值与 `standard()` 的差 **超过** `PARAM_EPS`，那么"被旁路的槽"
     * 会被当成"用户改过的值"**写进预设** ⇒ 套用该预设时把 `[5..10]`/`[37]`/`[38]` 打回旁路值，
     * 用户在引擎 0 下的胶片曲线被静默改写（跨引擎污染，且完全静默）。
     *
     * ⚠ 这条**必须**成立：[NEUTRALIZE] 的 `[5..10]` 是 `0.01f.toDouble()`（0.009999999776…），
     * 而 `standard()[5]` 是 double 字面量 `0.01` —— 两者只差 2.2e-10，靠的是 `PARAM_EPS`
     * （1e-4）盖住。**这不是巧合，是把浮点口径写进契约的后果**（见 [defaultOf] 的 KDoc）。
     */
    @Test
    fun neutralizedValuesAreStillRecognizedAsIdentityByPresetSparsity() {
        val engine1 = EngineScope.applyEngineScope(
            CameraPresets.standard().also { it[98] = 1.0 }, 1,
        )
        val sparse = presetSparseSlots(engine1)
        val leaked = sparse.keys.filter { it in EngineScope.NEUTRALIZE }.sorted()
        assertTrue(
            "$leaked 属于 NEUTRALIZE（已被旁路）却被写进预设稀疏集 ⇒ 切回引擎 0 时会把用户的" +
                "「胶片曲线 / 黑白点」打回旁路值（跨引擎污染，且完全静默）。" +
                "判据 = 恒等值与 standard() 的差必须 ≤ PARAM_EPS",
            leaked.isEmpty(),
        )
        // 第二道保险：[28]/[35]/[36] 本来就被 presetSlotIncluded 排除（PRESET_ENGINE_KNOB_SLOTS）
        // ⇒ 即使它们的恒等值与 standard() 差 1.0/0.25（远超 PARAM_EPS）也进不了预设。
        // ⚠ 这道保险**只在拍照/编辑器保存侧**成立；套用侧靠的是 [presetSnapshot] 的"缺失 key = 保留"。
        for (s in listOf(28, 35, 36)) {
            assertFalse("[$s] 必须被 presetSlotIncluded 排除（引擎旋钮，不是风格）", presetSlotIncluded(s))
        }
        // [98] 自己：★ P2 卡（默认档 1 = sigmoid）后**默认档就是恒等值 ⇒ 不入库**（稀疏不变式）；
        // 而**非默认档**（0 = filmic）必须进稀疏集 —— 否则"这条预设用 filmic"的决定被静默丢掉。
        // （老实现默认档 = 0 时，`[98]=0` 不入库正是同一不变式的另一侧。）
        assertFalse(
            "默认档 [98] = standard()[98] ⇒ 必须被稀疏化识别为恒等、不入库" +
                "（否则每条预设都白背一个引擎键）",
            98 in sparse,
        )
        val filmic = presetSparseSlots(CameraPresets.standard().also { it[98] = 0.0 })
        assertEquals(
            "[98] = 0（filmic，非默认档）必须进稀疏集（否则预设带不动引擎选择）",
            0.0, filmic[98]!!, 0.0,
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // §P0：`[28]` 在引擎 1 下是 sigmoid 的应用权重 —— 文件级源事实（常绿守卫）
    // ══════════════════════════════════════════════════════════════════════════

    private val engineCpp: String by lazy { File("../core/engine/src/render/FrameRenderer.cpp").readText() }

    /** 取 `const char* <name> = R"( … )";` 的原始字符串内容。 */
    private fun rawShaderLiteral(src: String, name: String): String {
        val marker = "$name = R\"("
        val start = src.indexOf(marker)
        assertTrue("FrameRenderer.cpp 里找不到 `$name = R\"(…)\"`（字面量被改名？）", start >= 0)
        val from = start + marker.length
        val end = src.indexOf(")\";", from)
        assertTrue("`$name` 的原始字符串没有闭合 `)\";`", end > from)
        return src.substring(from, end)
    }

    @Test
    fun toneKIsAPublicSlotAndGatesBothEnginesToneCurves() {
        val film = rawShaderLiteral(engineCpp, "kFsFilm")

        // ① [28] 的 uniform 声明落在**公共 head 段**（engine_def 切分锚点之前）⇒ 两引擎都看得见
        val declAt = film.indexOf("uniform float uToneK;")
        assertTrue("kFsFilm 里必须声明 `uniform float uToneK;`（[28] 的落点）", declAt >= 0)
        val anchor = "// ── filmic 基准影调（v0.7.3）：darktable v3 spline 取代 Reinhard ──"
        val anchorAt = film.indexOf(anchor)
        assertTrue("kFsFilm 里找不到 engine_def 切分锚点 ⇒ filmSlices() 会退回整体字面量", anchorAt >= 0)
        assertTrue(
            "[28] uToneK 的声明必须在切分锚点之前（公共 head 段）—— 否则它就不是「两引擎共用」的档位",
            declAt < anchorAt,
        )

        // ② 两个引擎的 main 注入段**都**门控在同一个 uToneK 上 ⇒ [28] 确实被两条引擎消费
        val e0MainAt = film.indexOf("    if (uToneK > 0.0) {")
        assertTrue("引擎 0 的 main 段必须以 `if (uToneK > 0.0)` 门控", e0MainAt >= 0)
        val e1Main = rawShaderLiteral(engineCpp, "kFsSigmoidMain")
        assertTrue(
            "引擎 1 的 main 段必须以 `if (uToneK > 0.0)` 门控 —— 这就是 §P0 的源事实",
            e1Main.trimStart().startsWith("if (uToneK > 0.0) {"),
        )

        // ③ 引擎 1 的影调**只有这一个落点**：`sigmoidTone` 全文件只被调用一次，且在门控块内
        assertEquals(
            "sigmoidTone(out3) 只允许出现一次（引擎 1 的影调唯一落点）——多于一次说明还有别的通路，§P0 结论需重审",
            1, Regex("""sigmoidTone\s*\(\s*out3\s*\)""").findAll(engineCpp).count(),
        )
        assertTrue("sigmoidTone 的调用必须在 uToneK 门控块**之内**", e1Main.indexOf("sigmoidTone(out3)") > e1Main.indexOf("if (uToneK > 0.0)"))
    }

    /**
     * §P0 的**回归护栏** —— 2026-10-14 审计发现、架构师当日修复（`put(28, 0.0)` → `put(28, 1.0)`）。
     *
     * 为什么必须留下这条（而不是修完就删）：它的失败方式是**静默**的 —— 把 `[28]` 归零不会崩、
     * 不会编译失败，只会让**引擎 1 一次都不执行 sigmoid**，而**六条验收判据全部照样通过**
     *（判据 2/3 只要求"改 filmic 槽位成片不变"，空转当然满足）。⇒ 这正是 §判据 0 存在的理由，
     * 也是本断言必须在测试里常驻的理由。
     *
     * 机制（与上一个用例的源事实配套）：`uToneK` 在引擎 1 的 main 注入段里是 sigmoid 的
     * **应用权重**；`sigmoidTone` 全文件只有 `kFsSigmoidMain` 内那一个调用点。
     *
     * 实测指纹（`tools/engine-shader-offline.py`，修前/修后）：
     *  - 修前 `NEUTRALIZE[28] = 0.0`：引擎 1@uToneK=0 的 `film_d5_sigmoid.npy` crc32 = **`0x189e4126`**，
     *    与**引擎 0@uToneK=0 逐字节相同** ⇒ 引擎 1 空转（该值与 sigmoid 参数无关，改默认值后不变）；
     *  - 修后 `= 1.0`：引擎 1@uToneK=1.0 = **`0x7c2646ef`**
     *    （2026-10-14 起 contrast=1.5 与 PC 一致；1.2 时期是 `0x8fdb4e38`），
     *    引擎 1@uToneK=0 → 1.0 的差异 max **13.25 LSB** / **59.7%** 通道 >1 LSB。
     */
    @Test
    fun p0ToneKIdentityMustNotDisableTheSigmoidEngine() {
        val v = EngineScope.NEUTRALIZE[28]
        assertNotNull("[28] 必须在 NEUTRALIZE 表里（它是两引擎共用的档位）", v)
        assertTrue(
            "P0 回归：NEUTRALIZE[28] = $v 会让引擎 1 的 `if (uToneK > 0.0)` 恒假 ⇒ sigmoid 整段不执行" +
                "（引擎选择在成片上不可见，且丢掉基准影调）。它必须 > 0。",
            v!! > 0.0,
        )
        assertEquals(
            "P0 修复口径：[28] 的恒等值 = 1.0 = `FilmParams.h` 的默认值（sigmoid 全强度）",
            1.0, v, 0.0,
        )
    }
}
