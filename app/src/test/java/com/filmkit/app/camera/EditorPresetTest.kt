package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E3-1a 预设（[EditorPreset] / 稀疏化 / JSON 往返 / 部分套用 / 容错 / 版本判定）的纯逻辑护栏。
 *
 * 为什么这些必须被钉住：预设的每一条规格错了，表现都是"用户点一下、画面静默变错"，
 * 而判据全落在纯逻辑里（不碰 prefs / Compose / GL）：
 *  1. ★ **恒等值不能假设 0**：本仓库大量槽的恒等值是 **1.0**（`wb_*` / `contrast` / `sat_*` /
 *     `lut_strength` / `interlayer_*`）、**0.01**（`shoulder/toe` = filmic latitude）、
 *     **单位阵**（`cross`）、**−7.65**（`filmic_black_ev`）。把恒等值当 0 判 ⇒ 用户没动过的槽
 *     整片进库 ⇒ 套用时把画面打回默认（本卡最容易错、后果最隐蔽的一条）。
 *  2. ★ **[34] LUT 强度必须排除在 `slots` 之外**：`editorOpenParams()` 打开编辑器时把 [34]
 *     归 0（成片已含拍摄时的 LUT 混合）⇒ 若它参与稀疏化，**每条预设都会存 "34":0**，
 *     套用时把用户刚选的 LUT 静默关掉。
 *  3. ★ **部分套用**：预设里没记的槽必须保持目标当前值（RawTherapee 的"缺失 key = 保留"）。
 *  4. ★ **往返**：存 → 读 → 套用 == 原参数（含 LUT 引用与中文 / 转义字符的名称）。
 *  5. ★ **容错**：坏 JSON / 缺字段 / 未知 schema / 越界槽位 / slots 形态不对 ⇒ **不崩**，
 *     按既定粒度跳过（结构字段坏 ⇒ 跳整项；单个槽坏 ⇒ 跳该槽）。
 *  6. ★ **套用 = 一步撤销**（E2-25）且套用后 `paramsUnchanged` 为假 ⇒ 导出走渲染而非直通（E2-9）。
 *
 * 基线一律用真实的 [CameraPresets.standard] / [editorOpenParams]，不手搓数组 —— 恒等值表
 * 是这份测试的**被测对象之一**，手搓就等于把被测对象抄进断言里。
 */
class EditorPresetTest {

    private val neutral = CameraPresets.standard()

    /** 一份"用户调过的"参数：曝光 / 对比度 / 高光 / 白平衡 / 清晰度 / 暗角强度 */
    private fun edited(): DoubleArray = CameraPresets.standard().also {
        it[0] = 0.5          // 曝光
        it[4] = 1.2          // 对比度（恒等 1.0）
        it[90] = -0.2        // 高光
        it[1] = 1.05         // wb_r（恒等 1.0）
        it[78] = 0.3         // 清晰度
        it[66] = -0.4        // 暗角强度
    }

    private fun preset(
        id: String = "p_1_1",
        name: String = "午后暖调",
        group: String = "我的胶片",
        slots: Map<Int, Double> = emptyMap(),
        lutId: String? = null,
        lutStrength: Double = 1.0,
    ) = EditorPreset(
        id = id, name = name, group = group, createdAt = 1730000000L,
        slots = slots, lutId = lutId, lutStrength = lutStrength,
    )

    // ── ① 稀疏化：恒等值逐槽取 standard()，不假设 0 ──────────────────────────

    /** 未改动的标准参数 ⇒ **一个槽都不入库**（恒等值为 1.0 / 0.01 / 单位阵的槽全在内） */
    @Test
    fun standardParamsProduceEmptySlots() {
        val slots = presetSparseSlots(neutral)
        assertTrue("标准参数不该产生任何槽：$slots", slots.isEmpty())
        // 逐条点名最容易错的那几个（恒等值不是 0）
        assertFalse("wb_r 恒等 1.0", slots.containsKey(1))
        assertFalse("contrast 恒等 1.0", slots.containsKey(4))
        assertFalse("shoulder/latitude 恒等 0.01", slots.containsKey(5))
        assertFalse("toe/latitude 恒等 0.01", slots.containsKey(8))
        assertFalse("cross 单位阵", slots.containsKey(11))
        assertFalse("sat_r 恒等 1.0", slots.containsKey(20))
        assertFalse("lut_strength 恒等 1.0", slots.containsKey(34))
        assertFalse("filmic_black_ev 恒等 −7.65", slots.containsKey(37))
        assertFalse("interlayer 暗部 恒等 1.0", slots.containsKey(69))
        assertFalse("local_sat 恒等 1.0", slots.containsKey(87))
    }

    /** Float→Double 的精度噪声不算"改过"（与 `paramsUnchanged` 同一份 PARAM_EPS 口径） */
    @Test
    fun floatNoiseIsNotAChange() {
        val p = CameraPresets.standard()
        p[5] = 0.01f.toDouble()      // 0.009999999776482582
        p[4] = 1.0f.toDouble()
        assertTrue("Float 精度噪声不该入库：${presetSparseSlots(p)}", presetSparseSlots(p).isEmpty())
    }

    /** 改过的槽**只记改过的那些**（不是整片 96 槽） */
    @Test
    fun onlyChangedSlotsAreStored() {
        val slots = presetSparseSlots(edited())
        assertEquals(setOf(0, 1, 4, 66, 78, 90), slots.keys)
        assertEquals(0.5, slots.getValue(0), 0.0)
        assertEquals(1.2, slots.getValue(4), 0.0)
        assertEquals(-0.4, slots.getValue(66), 0.0)
    }

    /** ★ [34]（LUT 强度）、[39..59]（RAW 解码 / 拍摄链路）、[79..87]（局部调整）永不进 slots */
    @Test
    fun decodeSlotsLutStrengthAndLocalAreNeverStored() {
        val p = CameraPresets.standard()
        p[34] = 0.85
        for (i in 39..59) p[i] = 7.5
        for (i in 79..87) p[i] = 3.3
        val slots = presetSparseSlots(p)
        assertTrue("解码槽 / [34] / 局部槽都不该入库：$slots", slots.isEmpty())
        assertFalse(presetSlotIncluded(34))
        assertFalse(presetSlotIncluded(39))
        assertFalse(presetSlotIncluded(59))
        assertFalse("局部模式是构图相关，v1 不进预设（调研 D7）", presetSlotIncluded(79))
        assertFalse(presetSlotIncluded(87))
        assertTrue(presetSlotIncluded(38))
        assertTrue(presetSlotIncluded(60))
        assertTrue(presetSlotIncluded(78))
        assertTrue(presetSlotIncluded(88))
        assertFalse(presetSlotIncluded(-1))
        assertFalse(presetSlotIncluded(CameraPresets.COUNT))
    }

    /**
     * ★ 编辑器**真实起点**（`editorOpenParams()` = 恒等基线 + [34] 归 0）稀疏化后必须为空。
     * 若 [34] 参与稀疏化，这里会得到 `{34: 0.0}` ⇒ 每条预设都会带一个"关掉当前 LUT"的槽。
     */
    @Test
    fun editorOpenParamsProduceEmptySlots() {
        val slots = presetSparseSlots(editorOpenParams())
        assertTrue("打开编辑器时的起点不该产生槽（[34]=0 是内部约定，不是用户风格）：$slots", slots.isEmpty())
    }

    /** 坏数据（NaN / Inf）不入库 */
    @Test
    fun nonFiniteSlotsAreDropped() {
        val p = CameraPresets.standard()
        p[0] = Double.NaN
        p[4] = Double.POSITIVE_INFINITY
        p[90] = 0.3
        assertEquals(mapOf(90 to 0.3), presetSparseSlots(p))
    }

    // ── ② 套用映射：部分套用 ────────────────────────────────────────────────

    /** 预设只含部分槽 ⇒ 其余槽保持**当前值**不动（不是回默认） */
    @Test
    fun partialApplyKeepsUntouchedSlots() {
        val current = edited()                       // 用户已经调过一堆
        val before = current.copyOf()
        val slots = mapOf(0 to -1.5, 94 to 0.5)      // 预设只管曝光与褪色
        val out = presetSnapshot(current, slots)
        assertEquals(-1.5, out[0], 0.0)
        assertEquals(0.5, out[94], 0.0)
        // 其余槽逐位不变
        for (i in before.indices) {
            if (i == 0 || i == 94) continue
            assertEquals("槽 $i 不该被动", before[i], out[i], 0.0)
        }
    }

    /** 越界 / 非有限的槽逐槽跳过（不是整条预设作废） */
    @Test
    fun outOfRangeSlotsInMapAreSkipped() {
        val current = CameraPresets.standard()
        val out = presetSnapshot(
            current,
            mapOf(0 to 0.0, -1 to 5.0, 999 to 5.0, 4 to Double.NaN),
        )
        assertEquals(0.0, out[0], 0.0)   // [0] 被合法写入（值 = 恒等 0）
        assertEquals(1.0, out[4], 0.0)   // NaN 被跳过 ⇒ 保持恒等
        assertArrayEquals(CameraPresets.standard(), out, 0.0)
    }

    /** `presetSnapshot` 返回**新数组**（不原地改 base）—— 调用侧统一走 applySnapshotParams */
    @Test
    fun presetSnapshotDoesNotMutateBase() {
        val base = CameraPresets.standard()
        val out = presetSnapshot(base, mapOf(0 to 2.0))
        assertEquals(0.0, base[0], 0.0)
        assertEquals(2.0, out[0], 0.0)
    }

    // ── ③ JSON 往返 ────────────────────────────────────────────────────────

    /** 存 → 读 ⇒ 逐字段相等（含中文 / 引号 / 反斜杠 / 换行的转义与 Double 精度） */
    @Test
    fun jsonRoundTripPreservesEveryField() {
        val p = EditorPreset(
            id = "p_1730000000_1a",
            name = "午后\"暖\"调\\ 一",
            group = "我的\n胶片",
            favorite = true,
            createdAt = 1730000000L,
            slots = mapOf(0 to 0.5, 34 to 0.85, 88 to 153.84615384615384, 90 to -0.2),
            lutId = "kodak_portra_400",
            lutStrength = 0.85,
        )
        // [34] 是构造进来的坏数据（正常路径不会产生）⇒ 读取侧应把它洗掉
        val back = presetsFromJson(presetsToJson(listOf(p))).presets.single()
        assertEquals(p.id, back.id)
        assertEquals(p.name, back.name)
        assertEquals(p.group, back.group)
        assertTrue(back.favorite)
        assertEquals(p.createdAt, back.createdAt)
        assertEquals("user", back.source)
        assertEquals(mapOf(0 to 0.5, 88 to 153.84615384615384, 90 to -0.2), back.slots)
        assertEquals("kodak_portra_400", back.lutId)
        assertEquals(0.85, back.lutStrength, 0.0)
    }

    /** 存 → 读 → 套用 == 原参数（从**恒等底**出发时逐槽精确相等） */
    @Test
    fun roundTripThenApplyEqualsOriginalParams() {
        val original = edited()
        val p = preset(slots = presetSparseSlots(original))
        val back = presetsFromJson(presetsToJson(listOf(p))).presets.single()
        val applied = presetSnapshot(CameraPresets.standard(), back.slots)
        assertArrayEquals("存→读→套用必须逐槽回到原参数", original, applied, 0.0)
    }

    /** 不含 LUT 的预设序列化时**省略 lut 键**（读取侧"缺失 = 无 LUT"） */
    @Test
    fun presetWithoutLutOmitsLutKey() {
        val json = presetToJson(preset(slots = mapOf(0 to 0.5)))
        assertFalse(json.contains("\"lut\""))
        val back = presetsFromJson("[$json]").presets.single()
        assertNull(back.lutId)
        assertEquals(1.0, back.lutStrength, 0.0)
    }

    /** 键序固定（便于 diff / 人工核对）：schema 在最前、slots 键升序 */
    @Test
    fun jsonShapeIsStable() {
        val json = presetToJson(preset(slots = mapOf(90 to -0.2, 0 to 0.5), lutId = "x", lutStrength = 0.5))
        assertTrue(json, json.startsWith("{\"schema\":1,\"id\":\"p_1_1\",\"name\":\"午后暖调\""))
        assertTrue(json, json.contains("\"slots\":{\"0\":0.5,\"90\":-0.2}"))
        assertTrue(json, json.contains("\"lut\":{\"id\":\"x\",\"strength\":0.5}"))
    }

    /** 空列表往返（首次运行 / 全删光） */
    @Test
    fun emptyListRoundTrip() {
        assertEquals("[]", presetsToJson(emptyList()))
        val res = presetsFromJson("[]")
        assertTrue(res.presets.isEmpty())
        assertTrue(res.skipped.isEmpty())
    }

    // ── ④ 容错（坏数据不崩） ────────────────────────────────────────────────

    /** prefs 里没存过 ⇒ 空列表，**不产生诊断**（这不是错误） */
    @Test
    fun nullAndBlankRawAreNotErrors() {
        for (raw in listOf(null, "", "   ")) {
            val res = presetsFromJson(raw)
            assertTrue(res.presets.isEmpty())
            assertTrue("首次运行不该报错：${res.skipped}", res.skipped.isEmpty())
        }
    }

    /** 坏 JSON / 顶层不是数组 ⇒ 空列表 + 诊断，**绝不抛异常** */
    @Test
    fun malformedJsonIsReportedNotThrown() {
        for (raw in listOf("{", "not json", "{\"a\":1}", "[1,2]", "[{\"schema\":1,]", "null")) {
            val res = presetsFromJson(raw)
            assertTrue("坏输入不该产出预设：$raw", res.presets.isEmpty())
            assertTrue("坏输入必须有诊断：$raw", res.skipped.isNotEmpty())
        }
    }

    /** 缺 id / 缺 name ⇒ 跳过整项（定位与展示都靠它们） */
    @Test
    fun missingIdOrNameSkipsItem() {
        val noId = """[{"schema":1,"name":"x","slots":{}}]"""
        val noName = """[{"schema":1,"id":"a","slots":{}}]"""
        assertEquals(1, presetsFromJson(noId).skipped.size)
        assertEquals(1, presetsFromJson(noName).skipped.size)
        assertTrue(presetsFromJson(noId).presets.isEmpty())
        assertTrue(presetsFromJson(noName).presets.isEmpty())
    }

    /** schema：缺失 / 未知 / 非整数 ⇒ 一律拒绝（策略见 presetSchemaUsable） */
    @Test
    fun unknownSchemaIsRejected() {
        val cases = listOf(
            """{"id":"a","name":"x"}""",
            """{"schema":0,"id":"a","name":"x"}""",
            """{"schema":2,"id":"a","name":"x"}""",
            """{"schema":1.5,"id":"a","name":"x"}""",
            """{"schema":"1","id":"a","name":"x"}""",
        )
        cases.forEach { one ->
            val res = presetsFromJson("[$one]")
            assertTrue("必须拒绝：$one", res.presets.isEmpty())
            assertEquals(1, res.skipped.size)
        }
        assertTrue(presetSchemaUsable(1))
        assertFalse(presetSchemaUsable(0))
        assertFalse(presetSchemaUsable(2))
        assertFalse(presetSchemaUsable(null))
    }

    /** slots 形态不对（数组 / 字符串 / 数字）⇒ 跳过整项（本格式没有"定长数组"形态） */
    @Test
    fun slotsMustBeAnObject() {
        for (bad in listOf("[]", "[1,2,3]", "\"x\"", "3", "true")) {
            val res = presetsFromJson("""[{"schema":1,"id":"a","name":"x","slots":$bad}]""")
            assertTrue("slots=$bad 必须跳项", res.presets.isEmpty())
            assertEquals(1, res.skipped.size)
        }
    }

    /** 缺 slots ⇒ 合法（只有 LUT 的预设）；缺 group / favorite / createdAt ⇒ 走默认值 */
    @Test
    fun missingOptionalFieldsFallBack() {
        val res = presetsFromJson("""[{"schema":1,"id":"a","name":"x","lut":{"id":"l"}}]""")
        val p = res.presets.single()
        assertTrue(p.slots.isEmpty())
        assertEquals("", p.group)
        assertFalse(p.favorite)
        assertEquals(0L, p.createdAt)
        assertEquals("l", p.lutId)
        assertEquals(1.0, p.lutStrength, 0.0)
    }

    /** 越界槽位 / 非数字值 / 非有限值 / 解码槽 / [34] ⇒ **逐槽跳过**，预设本身仍有效 */
    @Test
    fun badSlotsAreDroppedOneByOne() {
        val raw = """
            [{"schema":1,"id":"a","name":"x","slots":{
              "0":0.5, "999":1.0, "-1":1.0, "abc":1.0, "39":3.0, "34":0.85,
              "4":"x", "90":null, "91":1e999, "94":true
            }}]
        """.trimIndent()
        val res = presetsFromJson(raw)
        val p = res.presets.single()
        assertEquals(mapOf(0 to 0.5), p.slots)
        assertTrue("坏槽位必须有诊断：${res.skipped}", res.skipped.isNotEmpty())
    }

    /** 手改的 JSON 里等于恒等值的槽在**读取时**被洗掉（保住"slots 只含非恒等槽"的不变式） */
    @Test
    fun identityValuesAreWashedOutOnLoad() {
        val raw = """[{"schema":1,"id":"a","name":"x","slots":{"4":1.0,"5":0.01,"11":1.0,"0":0.5}}]"""
        assertEquals(mapOf(0 to 0.5), presetsFromJson(raw).presets.single().slots)
    }

    /** lut 形态 / 内容坏了 ⇒ 降级"无 LUT"（**不跳整项**：参数部分仍然可用） */
    @Test
    fun brokenLutDegradesToNoLut() {
        val cases = listOf(
            "\"x\"", "3", "true", "[]",
            "{}", """{"id":""}""", """{"id":123}""",
        )
        cases.forEach { lut ->
            val res = presetsFromJson("""[{"schema":1,"id":"a","name":"x","slots":{"0":0.5},"lut":$lut}]""")
            val p = res.presets.single()
            assertEquals("lut=$lut 应降级为无 LUT", null, p.lutId)
            assertEquals("lut=$lut 参数部分必须保住", mapOf(0 to 0.5), p.slots)
        }
    }

    /** LUT id 在但强度坏了 ⇒ 强度回默认 1.0（LUT 本身仍然套用，不因一个数字丢掉资源引用） */
    @Test
    fun brokenLutStrengthFallsBackToOne() {
        val cases = listOf("\"x\"", "null", "1e999", "true")
        cases.forEach { s ->
            val res = presetsFromJson("""[{"schema":1,"id":"a","name":"x","lut":{"id":"l","strength":$s}}]""")
            val p = res.presets.single()
            assertEquals("l", p.lutId)
            assertEquals("strength=$s 应回默认 1.0", 1.0, p.lutStrength, 0.0)
        }
    }

    /** LUT 强度夹到 UI 量程 0..1.5（手改的 5.0 会让引擎把 LUT 变换外推得离谱） */
    @Test
    fun lutStrengthIsClampedToUiRange() {
        val hi = presetsFromJson("""[{"schema":1,"id":"a","name":"x","lut":{"id":"l","strength":5}}]""")
        assertEquals(1.5, hi.presets.single().lutStrength, 0.0)
        val lo = presetsFromJson("""[{"schema":1,"id":"a","name":"x","lut":{"id":"l","strength":-2}}]""")
        assertEquals(0.0, lo.presets.single().lutStrength, 0.0)
    }

    /** 超过上限 ⇒ 截断保留最新的 [PRESET_MAX] 条 + 诊断 */
    @Test
    fun overLimitIsTruncated() {
        val many = (1..PRESET_MAX + 5).joinToString(",") {
            """{"schema":1,"id":"p$it","name":"n$it","slots":{"0":$it}}"""
        }
        val res = presetsFromJson("[$many]")
        assertEquals(PRESET_MAX, res.presets.size)
        assertEquals("p1", res.presets.first().id)          // 列表顺序 = 存储顺序（新→旧），保留前 N
        assertTrue(res.skipped.isNotEmpty())
    }

    /** 一项坏、一项好 ⇒ 好的照常读出（不因为邻居坏就整包丢弃） */
    @Test
    fun oneBadItemDoesNotKillTheRest() {
        val raw = """
            [{"schema":9,"id":"bad","name":"x"},
             {"schema":1,"id":"good","name":"好","slots":{"0":0.5}}]
        """.trimIndent()
        val res = presetsFromJson(raw)
        assertEquals(1, res.presets.size)
        assertEquals("good", res.presets.single().id)
        assertEquals(1, res.skipped.size)
    }

    // ── ⑤ 套用 = 一步撤销 + 直通失效 ───────────────────────────────────────

    /**
     * 套用预设 = **一步撤销**（E2-25）：落地后 `record` 一次 ⇒ 撤销一次回到套用前。
     * 同时断言套用后 `paramsUnchanged` 为假 ⇒ 导出走**渲染**而不是直通（E2-9）。
     */
    @Test
    fun applyIsOneUndoStepAndDisablesPassthrough() {
        val open = editorOpenParams()                       // 打开时的基准（直通判据的另一侧）
        val params = editorOpenParams()
        val history = EditorHistory()
        history.reset(EditorSnapshot(params, CropSpec.FULL, null))

        // 用户先改一笔（第一步）
        params[78] = 0.3
        assertTrue(history.record(EditorSnapshot(params, CropSpec.FULL, null)))
        val beforeApply = params.copyOf()

        // 套用预设（第二步）：按 idx 覆盖 slots，其余不动
        val p = preset(slots = mapOf(0 to -1.5, 94 to 0.4))
        applySnapshotParams(params, presetSnapshot(params, p.slots))
        assertTrue(history.record(EditorSnapshot(params, CropSpec.FULL, null)))
        assertEquals(2, history.undoDepth)

        // ★ 套用后不再是"无修改" ⇒ 导出走渲染（直通被自动关掉）
        assertFalse(paramsUnchanged(params, open))

        // 撤销一次 ⇒ 精确回到套用前
        val back = history.undo()!!
        assertArrayEquals(beforeApply, back.params, 0.0)
        // 再撤销一次 ⇒ 回到打开时的基线 ⇒ 直通重新成立（E2-9 / E2-25 的硬交互）
        val first = history.undo()!!
        assertTrue(paramsUnchanged(first.params, open))
    }

    /** 套用"什么都不改"的预设 ⇒ 不入撤销栈（无变化不入栈，避免"点撤销画面不动"） */
    @Test
    fun applyingNoOpPresetDoesNotPushUndoStep() {
        val params = editorOpenParams()
        val history = EditorHistory()
        history.reset(EditorSnapshot(params, CropSpec.FULL, null))
        // 一条"把 [0] 设成当前值"的预设：套用后参数逐槽不变
        applySnapshotParams(params, presetSnapshot(params, mapOf(0 to params[0])))
        assertFalse(history.record(EditorSnapshot(params, CropSpec.FULL, null)))
        assertFalse(history.canUndo)
    }

    // ── ⑥ 杂项：id / 版本判定 ──────────────────────────────────────────────

    /** id 稳定且带 salt（重命名不改 id；同毫秒两次保存不撞车） */
    @Test
    fun presetIdIsStableAndSalted() {
        assertEquals(presetId(1730000000L, 0), presetId(1730000000L, 0))
        assertNotEquals(presetId(1730000000L, 1), presetId(1730000000L, 2))
        assertTrue(presetId(1730000000L, 26).startsWith("p_1730000000_"))
        // 负 salt 不能产生带 '-' 的 id（删除 / 更新按 id 定位，格式必须稳定）
        assertFalse(presetId(1L, -1).contains('-'))
    }

    /** 预设默认值：分组空串、source = user（导入 / 内置留给 E3-2 / E3-4） */
    @Test
    fun presetDefaults() {
        val p = EditorPreset(id = "a", name = "n")
        assertEquals("", p.group)
        assertFalse(p.favorite)
        assertEquals(0L, p.createdAt)
        assertEquals("user", p.source)
        assertTrue(p.slots.isEmpty())
        assertNull(p.lutId)
        assertEquals(1.0, p.lutStrength, 0.0)
    }
}
