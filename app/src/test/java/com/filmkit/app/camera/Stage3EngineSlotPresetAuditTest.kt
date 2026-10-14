package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 3 预设 schema：**`[98] engine_slot` 进预设之后的向后兼容**（P2 卡后口径更新）。
 *
 * 判据（架构师 §8.1 阶段表第 3 行）：**旧预设（JSON 里不含 `engine_slot`）加载后必须逐位等价**，
 * `[98]` 落到**展开基线**（`CameraPresets.standard()`）的值。
 *
 * ★ **P2 卡（用户 2026-10-14 拍板）把"默认档"从 0（filmic）改为 1（sigmoid）** ⇒ 本文件的口径
 * 随之翻转，且**两件事都要钉**：
 *  - 缺 `[98]` ⇒ 落 `CameraPresets.DEFAULT_ENGINE_SLOT` = **1**（默认档，不是硬编码 0）；
 *  - `[98] = 0` 从"恒等值（不入库）"变为**合法非默认档位（要入库）** —— 否则用户"这条预设
 *    用 filmic"的决定会被静默丢掉。
 *
 * 为什么值得单独一组：`[98]` 是**第一个进预设的"引擎状态"槽**（其余同类 `[28]/[30..33]/[35]/[36]`
 * 都被 `PRESET_ENGINE_KNOB_SLOTS` 挡在库外）⇒ 它是线格式里唯一能"改变渲染引擎"的键。
 * 它的失败方式是**静默**的：老预设若被解读成"引擎 = 0"或"引擎 = 未定义"，都会让用户在默认档
 * （引擎 1）下套一条旧预设时**被悄悄切回 filmic**（或反之），而画面变化只表现为"风格不对"。
 *
 * 与 `EditorPresetTest`（线格式 / 稀疏化 / 容错的**主用例**）分工：本文件只问 `[98]` 的三件事
 * —— ① 旧预设**不带**它时行为是否逐位不变；② 它自己能不能完整往返；③ 它进了库之后，
 * `engineScoped` 的旁路是否仍然成立（**两卡接缝**）。
 *
 * 工作目录 = `app/`（gradle 单测约定）。
 */
class Stage3EngineSlotPresetAuditTest {

    private val neutral = CameraPresets.standard()

    /** 一条**阶段 3 之前**就存在的预设：没有 `engine_slot` 键（线格式与 `presetToJson` 的旧输出一致）。 */
    private fun legacyJson(params: String = """{"grain_amount":55.0,"vignette_strength":-0.18}""") =
        """[{"schema":2,"id":"p_1728543600000_ab12","name":"CineStill 800T","group":"CineStill",""" +
            """"favorite":false,"createdAt":1728543600000,"source":"user","note":"","params":$params}]"""

    /** 一条**含 filmic 参数**的旧预设 —— 老库里的真实形态（`shoulder`/`filmic_*` 本来就在白名单里）。 */
    private fun legacyFilmicJson() = legacyJson(
        """{"shoulder[0]":0.6,"shoulder[1]":0.6,"shoulder[2]":0.6,"toe[0]":0.6,"toe[1]":0.6,""" +
            """"toe[2]":0.6,"filmic_black_ev":-3.0,"filmic_white_ev":2.0,"grain_amount":55.0}""",
    )

    private fun firstSlots(json: String): Map<Int, Double> {
        val res = presetsFromJson(json)
        assertEquals("这条预设必须能被读出来（诊断：${res.skipped}）", 1, res.presets.size)
        assertTrue("不该有被跳过的项：${res.skipped}", res.skipped.isEmpty())
        return res.presets.first().slots
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ① 旧预设（无 engine_slot 键）⇒ 逐位等价（默认档口径 = P2 卡后的 1）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun legacyPresetWithoutEngineSlotFallsBackToTheDefaultEngine() {
        val slots = firstSlots(legacyJson())
        assertFalse("[98] 不得凭空出现在只有老键的预设里：${slots.keys}", 98 in slots)

        val applied = presetSnapshot(neutral, slots)
        // ★ P2 卡：默认档 = 1 = sigmoid。缺 key ⇒ 取展开基线的值（**不是**"历史默认 0"）——
        //   这正是"缺键 ⇒ 重置为默认档"这条行为在拍照链路上的落点（报告 §2）。
        assertEquals(
            "[98] 缺键必须落到默认档 1（= sigmoid）",
            CameraPresets.DEFAULT_ENGINE_SLOT.toDouble(), applied[98], 0.0,
        )
        // "逐字节等价"的精确表述：**预设提到的槽照常生效，其余每一个槽（含 [98]）逐位不变**
        // ⚠ 不能整条 `assertArrayEquals(neutral, applied)`：这条旧预设**本来就**要改 `[26]` 颗粒，
        //   那样断言会把"预设正常生效"当成失败。
        assertEquals("[26] 颗粒是这条预设的正当效果，必须照常生效", 55.0, applied[26], 0.0)
        for (i in neutral.indices) {
            if (i in slots.keys) continue
            assertEquals("[$i] 不在旧预设里 ⇒ 必须保持默认（含 [98]）", neutral[i], applied[i], 0.0)
        }
    }

    @Test
    fun legacyPresetDoesNotDragAPhotoBackToEngineZero() {
        // 语义 = "缺失 key ⇒ 保留目标当前值"（RawTherapee 口径，见 presetSnapshot 的 KDoc）。
        // 为什么单列一条：这正是 `[98]` 与其它槽**不同**的地方 —— 用户在默认档（引擎 1）下套一条
        // 旧预设，预期是"套风格"，不是"被切回 filmic"。断言它是有意的，而不是碰巧。
        val base = neutral.copyOf().also { it[98] = 1.0 }
        val applied = presetSnapshot(base, firstSlots(legacyJson()))
        assertEquals("旧预设不得改动目标照片的引擎选择", 1.0, applied[98], 0.0)

        // 反向（P2 卡新增）：目标照片是 filmic（用户在完整参数页显式选的 0）时同样不得被改。
        val filmicBase = neutral.copyOf().also { it[98] = 0.0 }
        assertEquals(
            "旧预设也不得把 filmic 照片拽到默认档",
            0.0, presetSnapshot(filmicBase, firstSlots(legacyJson()))[98], 0.0,
        )
    }

    /**
     * ★ P2 卡的**行为实测**（报告 §2 的机器判据）：**真实** `builtin.json` 的三条内置预设都
     * 不含 `engine_slot` 键（用户 2026-10-14 要求"预设相关先不动"）⇒ 经 `cameraPresetParams`
     * （拍照链路实际下发的那条路径）展开后，`[98]` = **默认档 1**。
     *
     * 为什么必须用真实资产而不是造一条 JSON：报告要回答的正是"内置预设这种形态会怎样"，
     * 造出来的 JSON 只能证明 `presetSnapshot` 的语义，证明不了"内置预设确实缺这个键"
     *（后者可能被将来某张卡改掉，届时本用例会红，正确）。
     */
    @Test
    fun builtinPresetsWithoutTheEngineSlotKeyExpandToTheDefaultEngine() {
        val json = File("src/main/assets/presets/builtin.json").readText()
        val builtins = BuiltinPresets.parse(json)
        assertTrue("前提：内置预设至少三条，实际 ${builtins.size}", builtins.size >= 3)
        for (b in builtins) {
            assertFalse("${b.id} 不得含 [98]（P2 卡要求内置预设零改动）", 98 in b.slots)
            assertEquals(
                "${b.id} 缺 engine_slot ⇒ 展开后必须是默认档（不是硬编码 0）",
                CameraPresets.DEFAULT_ENGINE_SLOT.toDouble(), cameraPresetParams(b)[98], 0.0,
            )
        }
    }

    /**
     * ★ P2 卡：**两个默认值必须同值**（本卡的改动核心就是这两个 + 读取缺省）。
     *
     * C++ `FilmParams.h` 的 `float engine_slot = 1.0f;` 是"缺槽时 C++ 用哪一档"的唯一真源；
     * Kotlin `CameraPresets.DEFAULT_ENGINE_SLOT` 是"缺键/短数组时 Kotlin 按哪一档解释"的唯一真源。
     * 两者分叉 ⇒ "C++ 走引擎 1、Kotlin 按引擎 0 判断旁路"的第三态（filmic 参数在引擎 1 下未归零）。
     */
    @Test
    fun cppFilmParamsDefaultEngineSlotMatchesTheKotlinDefault() {
        val src = File("../core/engine/src/film/FilmParams.h").readText()
        val m = Regex("""float\s+engine_slot\s*=\s*(-?[\d.]+)f\s*;""").find(src)
        assertNotNull("FilmParams.h 里找不到 `float engine_slot = …f;`（字段被改名？本用例需同步）", m)
        assertEquals(
            "C++ 默认档必须与 Kotlin 的 CameraPresets.DEFAULT_ENGINE_SLOT 同值",
            CameraPresets.DEFAULT_ENGINE_SLOT.toDouble(), m!!.groupValues[1].toFloat().toDouble(), 0.0,
        )
        assertEquals(
            "engine_slot 的**默认值定义**只许有一处（本卡纪律：两个默认值 + 测试口径）",
            1, Regex("""float\s+engine_slot\s*=""").findAll(src).count(),
        )
    }

    @Test
    fun legacyPresetSendsNoEngineSlotBackToTheWireFormat() {
        // 反向的"逐字节等价"：老预设读进来再写出去，**不得多出 `engine_slot` 键**
        // （多出来 = 每条老预设都开始携带引擎选择，用户没做过的决定被写进库）
        val p = EditorPreset(
            id = "p_1728543600000_ab12", name = "旧", group = "G", source = PRESET_SOURCE_USER,
            note = "", createdAt = 1728543600000L, slots = firstSlots(legacyJson()),
        )
        val json = presetToJson(p)
        assertFalse("旧预设重新序列化后不得多出 engine_slot：$json", json.contains("engine_slot"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ② `[98]` 自己的往返（稀疏 → JSON → 读回 → 套用）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun nonDefaultEngineSlotSurvivesTheFullSparseJsonRoundTrip() {
        // ★ P2 卡后的探针口径：**非默认档**才进稀疏集（默认档 1 = 恒等值，见下一个用例）。
        //   用 0（filmic）—— 它同时是"将来删 filmic"时最需要被预设记住的那一档。
        val engine0 = neutral.copyOf().also { it[98] = 0.0 }
        val sparse = presetSparseSlots(engine0)
        assertEquals("[98] = 0（≠ 默认档）必须进稀疏集（否则预设带不动引擎选择）", 0.0, sparse[98]!!, 0.0)

        val p = EditorPreset(
            id = "p_1_1", name = "filmic 档", group = "G", source = PRESET_SOURCE_USER,
            note = "", createdAt = 1730000000L, slots = sparse,
        )
        val json = presetToJson(p)
        assertTrue("线格式必须用语义名 engine_slot：$json", json.contains("\"engine_slot\":0.0"))

        val back = firstSlots("[$json]")
        assertEquals("[98] 必须原值读回", 0.0, back[98]!!, 0.0)
        assertEquals("往返后套用必须回到 filmic 档", 0.0, presetSnapshot(neutral, back)[98], 0.0)
    }

    @Test
    fun engineSlotIsStoredOnlyWhenItDiffersFromTheDefault() {
        // 稀疏不变式（P2 卡后语义翻转：恒等值 = **默认档 1 = sigmoid**）。这条同时守两件事：
        //  ① `"engine_slot":1`（= 默认档）**不入库** ⇒ 套用时走"缺失 key = 保留目标当前值"，
        //     不会把别人的引擎选择打成 1；且它不该产生 skipped 诊断（不是坏数据）；
        //  ② `"engine_slot":0`（filmic，**合法非默认档位**）**必须入库** —— 否则用户"这条预设
        //     用 filmic"的决定会被静默丢掉。⚠ 老实现（默认档 = 0）正是靠"0 = 恒等值"把它丢掉的，
        //     P2 卡后这条不再成立，本用例是那次口径翻转的回归护栏。
        val defaults = presetsFromJson(legacyJson("""{"engine_slot":1.0,"grain_amount":55.0}"""))
        assertEquals("不该被跳过", 1, defaults.presets.size)
        assertTrue("恒等值被丢弃不该产生诊断：${defaults.skipped}", defaults.skipped.isEmpty())
        assertFalse("[98]=1 是恒等值（= 默认档）⇒ 不入库", 98 in defaults.presets.first().slots)

        val filmic = presetsFromJson(legacyJson("""{"engine_slot":0.0,"grain_amount":55.0}"""))
        assertEquals("不该被跳过", 1, filmic.presets.size)
        assertTrue("合法档位不该产生诊断：${filmic.skipped}", filmic.skipped.isEmpty())
        assertEquals("[98]=0（filmic）≠ 默认档 ⇒ 必须入库", 0.0, filmic.presets.first().slots[98]!!, 0.0)
    }

    @Test
    fun unregisteredEngineSlotFromPresetDataIsTreatedAsEngineZero() {
        // `engine_slot` 是**已知语义名** ⇒ 值不在注册表里也**不拒绝整项**（与"未知语义名 ⇒ 拒整项"不同档）。
        // 数据照收（渲染侧由 C++ 的 `warnEngineSlotUnavailable` 降级到引擎 0 + WARN），
        // 而 Kotlin 侧的旁路判据必须**与之一致**：未注册 ⇒ **按 0 处理、不旁路**（P2 修复，2026-10-14）。
        // ⛔ 旧行为（`!= 0` 就旁路）会得到第三种状态"引擎 0 + filmic 参数被清零" —— 本用例是它的回归护栏。
        val slots = firstSlots(legacyJson("""{"engine_slot":7.0}"""))
        assertEquals("[98]=7 会被当作数据收下（由渲染侧降级 + WARN）", 7.0, slots[98]!!, 0.0)

        val base = neutral.copyOf().also { it[98] = 7.0 }
        assertSame("未注册 ⇒ 必须按 0 处理（同实例、不旁路）", base, engineScoped(base))
        assertEquals("filmic 参数不得被清零（否则就是 P2 的第三种状态）", 1.0, engineScoped(base)[28], 0.0)
    }

    @Test
    fun engineSlotIsInThePresetWhitelistAndHasASemanticName() {
        // 三方对齐：白名单 / 语义名 / 反向查名（线格式的两个方向都要能用）
        assertTrue("[98] 必须参与预设（它是这条预设的引擎选择）", presetSlotIncluded(98))
        assertEquals("engine_slot", FilmParamKeys.bySlot[98])
        assertEquals(98, FilmParamKeys.byName["engine_slot"])
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ③ 两卡接缝：旧预设带了 filmic 参数 ⇒ 在引擎 1 下必须被旁路掉
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 判据 6 的**参数级**（JVM 可达）形式：一条含 filmic latitude / 黑白点的**旧预设**，
     * 套到 `[98] = 1` 的照片上之后，经 `engineScoped` 出来的数组必须与"没套这条预设"**逐位相同**。
     *
     * ⇒ 它把"编辑器侧旁路"和"预设 schema"两张卡钉在一起：只要有一侧漏了（预设没读进 `[98]`、
     * 或 `engineScoped` 没归零 `[5..10]/[37]/[38]`），本条就会红。
     * （像素级版本只能真机 —— 见 `stage3-acceptance-CHECKLIST.md` 判据 6。）
     */
    @Test
    fun legacyFilmicPresetIsFullyBypassedOnAnEngineOnePhoto() {
        val slots = firstSlots(legacyFilmicJson())
        // 前提：这条预设**确实**含非恒等的 filmic 参数（否则本用例测的是空集，假绿）
        for (s in (5..10)) assertTrue("前提失效：[$s] 应在这条旧预设里", s in slots)
        assertTrue("前提失效：[37] 应在这条旧预设里", 37 in slots)
        assertTrue("前提失效：[38] 应在这条旧预设里", 38 in slots)

        val engine1Base = neutral.copyOf().also { it[98] = 1.0 }
        val reference = engineScoped(engine1Base)
        val applied = engineScoped(presetSnapshot(engine1Base, slots))

        // ① filmic 槽必须被旁路到恒等值（= 判据 6 的参数级形式）
        for (s in 5..10) assertEquals("[$s] 必须回到恒等 latitude", LATITUDE_UI_PIVOT.toDouble(), applied[s], 0.0)
        assertEquals("[37] 必须回到恒等黑点", FILMIC_BLACK_EV_PIVOT.toDouble(), applied[37], 0.0)
        assertEquals("[38] 必须回到恒等白点", FILMIC_WHITE_EV_PIVOT.toDouble(), applied[38], 0.0)
        // ② 预设里的**通用**槽必须照常生效（防"把整条预设都吃掉"的误旁路）
        assertEquals("[26] 颗粒（通用槽）必须保留预设值", 55.0, applied[26], 0.0)
        // ③ 其余每一个槽都必须与"没套这条预设"逐位相同
        val filmic = (5..10).toSet() + setOf(37, 38)
        for (i in neutral.indices) {
            if (i in filmic || i in slots.keys || i == 98) continue
            assertEquals("[$i] 既不在预设里、也不该被旁路 ⇒ 必须与「没套预设」逐位相同", reference[i], applied[i], 0.0)
        }
        assertEquals("预设的引擎选择必须保留", 1.0, applied[98], 0.0)

        // **正对照**：同一条预设套到引擎 0 的照片上必须**真的改变**参数
        //（否则"引擎 1 下相同"可能只是因为预设压根没生效）
        // ⚠ P2 卡后 `neutral` 的 [98] 已是默认档 1 ⇒ 必须**显式**写 0 才是引擎 0 的照片
        //   （这正是"默认档基线变了、但 `[98]=0` 的路径没变"的现场）。
        val engine0Applied = engineScoped(presetSnapshot(neutral.copyOf().also { it[98] = 0.0 }, slots))
        assertEquals("[5] 在引擎 0 下必须保留预设值（正对照）", 0.6, engine0Applied[5], 0.0)
        assertEquals("[37] 在引擎 0 下必须保留预设值（正对照）", -3.0, engine0Applied[37], 0.0)
        assertEquals("正对照的照片仍是引擎 0（未被改写）", 0.0, engine0Applied[98], 0.0)
    }
}
