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
 * E3-1a / P-1 预设（[EditorPreset] / 稀疏化 / JSON 往返 / 部分套用 / 容错 / 版本判定）的纯逻辑护栏。
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
 *  5. ★ **容错**：坏 JSON / 缺字段 / 未知 schema / **未知语义名** / 已知名但值坏 ⇒ **不崩**，
 *     按既定粒度跳过（结构字段坏 / 未知语义名 ⇒ 跳整项；单个槽坏 ⇒ 跳该槽）。
 *  6. ★ **套用 = 一步撤销**（E2-25）且套用后 `paramsUnchanged` 为假 ⇒ 导出走渲染而非直通（E2-9）。
 *  7. ★ **P-1 线格式**：`params` 的键是**语义名**（[FilmParamKeys]），schema = 2；schema=1 的旧
 *     数据一律拒绝（不写迁移函数，理由见 [presetSchemaUsable]）。线格式样例与体积实测见
 *     `docs/reports/p1-preset-schema-REPORT.md`。
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
        source: String = PRESET_SOURCE_USER,
        note: String = "",
        slots: Map<Int, Double> = emptyMap(),
        lutId: String? = null,
        lutStrength: Double = 1.0,
    ) = EditorPreset(
        id = id, name = name, group = group, source = source, note = note, createdAt = 1730000000L,
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

    /**
     * ★ 排除槽**一个都不进 slots**：`[34]`（LUT 强度）、`[28]`（基准影调开关）、
     * `[30]`（过曝斑马纹阈值）、`[31..33]`（色彩外观层）、`[35]`（`auto_tone`）/ `[36]`
     * （`local_tone`）（引擎模式 / 标定 / **色彩科学状态**）、`[39..59]`（RAW 解码 / 拍摄链路）、
     * `[79..87]`（局部调整）。
     *
     * ⚠ [30] 这条是 P-1 修掉的**真实缺陷**：它是**预览辅助开关**（`FilmParams.h:72`：仅预览
     * `passOutput` 使用，成片一律 0），但编辑器链路 `passStyle` **也读它** ⇒ 存进预设后套用会
     * 凭空打开 / 关闭别人的过曝斑马纹。判据逐条写在 `PRESET_ENGINE_KNOB_SLOTS` 的 KDoc 里。
     *
     * ⚠ [28] / [31..33] 是 **P-5 复核**新加的排除（架构师裁决）：判据 = "引擎色彩科学状态，
     * 不是用户风格"（[28] 还被 `CameraController.applyTonePref` 按 prefs 重写；[31..33] 在
     * v0.7.7 已回恒等、设置页开关 v0.7.9 已删除 ⇒ UI 上根本没有入口能改它们）。
     */
    @Test
    fun engineKnobsDecodeSlotsLutStrengthAndLocalAreNeverStored() {
        val p = CameraPresets.standard()
        // ⚠ 每个都必须是**非恒等值**：等于恒等值时稀疏化本来就会丢它 ⇒ 用例会被恒等值"兜底"，
        // 即使把该槽从排除列表里拿掉也照样绿（mutation 自检会发现这种恒真断言）。
        p[34] = 0.85    // 恒等 1.0
        p[30] = 0.42    // 恒等 0.95（斑马纹阈值）
        p[35] = 1.0     // 恒等 0.0
        p[36] = 0.5     // 恒等 0.25
        p[28] = 0.0     // 恒等 1.0（基准影调开关，P-5 复核新增排除）
        for (i in 31..33) p[i] = 1.5  // 恒等 1.0（色彩外观层，P-5 复核新增排除）
        for (i in 39..59) p[i] = 7.5
        for (i in 79..87) p[i] = 3.3
        val slots = presetSparseSlots(p)
        assertTrue("排除槽一个都不该入库：$slots", slots.isEmpty())
        assertFalse("LUT 强度由 lutId/lutStrength 承载", presetSlotIncluded(34))
        assertFalse("过曝斑马纹阈值 = 预览辅助开关（passStyle 也读它）", presetSlotIncluded(30))
        assertFalse("auto_tone = 引擎模式旋钮", presetSlotIncluded(35))
        assertFalse("local_tone = 引擎标定旋钮", presetSlotIncluded(36))
        assertFalse("基准影调 = 色彩科学状态（且被 prefs 覆盖层重写），P-5 复核排除", presetSlotIncluded(28))
        assertFalse("色彩外观层 = 色彩科学标定层，P-5 复核排除", presetSlotIncluded(31))
        assertFalse(presetSlotIncluded(32))
        assertFalse(presetSlotIncluded(33))
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

        // ★ P-5 复核的**前提**（实测，不是假设）：[28] / [31..33] 的恒等值都是 **1.0**
        //（不是 0）⇒ 把它们排除**不会**改变"用户没改过的参数"的行为：稀疏化本来就按恒等值
        // 丢弃（[presetSparseSlots]）。恒等值哪天变成别的数，这条会红 ⇒ 提醒重审排除决定。
        val neutral = CameraPresets.standard()
        for (i in listOf(28, 31, 32, 33)) {
            assertEquals("槽位 [$i] 的恒等值必须是 1.0（P-5 排除它的前提）", 1.0, neutral[i], 0.0)
        }
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

    /**
     * ★ 存 → 读 ⇒ **逐字段**相等（含中文 / 引号 / 反斜杠 / 换行的转义、Double 精度、LUT 与元数据）。
     * P-1 的主往返用例：线格式换了键名（索引键 → 语义名），字段一个都不能丢。
     */
    @Test
    fun jsonRoundTripPreservesEveryField() {
        val p = EditorPreset(
            id = "p_1730000000_1a",
            name = "午后\"暖\"调\\ 一",
            group = "我的\n胶片",
            favorite = true,
            createdAt = 1730000000L,
            source = PRESET_SOURCE_BUILTIN,
            note = "室内 1/60s，颗粒 +2 档",
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
        assertEquals(PRESET_SOURCE_BUILTIN, back.source)
        assertEquals(p.note, back.note)
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

    /** 键序固定（便于 diff / 人工核对）：schema 在最前、`params` 按**槽位号升序**且键是语义名 */
    @Test
    fun jsonShapeIsStable() {
        val json = presetToJson(preset(slots = mapOf(90 to -0.2, 0 to 0.5), lutId = "x", lutStrength = 0.5))
        assertTrue(json, json.startsWith("{\"schema\":2,\"id\":\"p_1_1\",\"name\":\"午后暖调\""))
        assertTrue(json, json.contains("\"params\":{\"exposure_ev\":0.5,\"highlights\":-0.2}"))
        assertTrue(json, json.contains("\"lut\":{\"id\":\"x\",\"strength\":0.5}"))
        // v2 固定字段：note 恒写（空串也写）⇒ 线格式的字段集合稳定，diff 时不会忽有忽无
        assertTrue(json, json.contains("\"note\":\"\""))
    }

    /** 没有语义名的槽**不写进线格式**（[29] 已删除的 hl_recon，索引留空不复用 ⇒ 不为它发明名字） */
    @Test
    fun unmappedSlotIsNotWrittenToJson() {
        // 只有 [29] ⇒ params 为空对象。这条同时钉住"第一项被跳过时不能写出 `{,`"
        //（逗号必须用独立标志判，不能用 forEachIndexed 的下标 —— 下标会跳号）。
        val only29 = presetToJson(preset(slots = mapOf(29 to 0.5)))
        assertTrue(only29, only29.contains("\"params\":{}"))
        val mixed = presetToJson(preset(slots = mapOf(29 to 0.5, 0 to 0.25)))
        assertTrue(mixed, mixed.contains("\"params\":{\"exposure_ev\":0.25}"))
    }

    /**
     * ★ **体积实测**（架构师要求"变大的代价要实测，不拍脑袋"）：一条**典型**预设（20 槽 + LUT +
     * 元数据）的 JSON 长度落在合理区间，且语义名的 `params` 段确实比索引键长。
     *
     * 区间 `500..1200` 只挡"数量级错了"（例如退化成 96 槽全量 ≈1.3 KB+ 或字段被写重）；
     * 精确数字打在 stdout（`PRESET_JSON_LEN=…`），报告引用的是同一份输出。
     */
    @Test
    fun presetJsonSizeIsReasonable() {
        val slots = linkedMapOf(
            0 to 0.35, 1 to 1.04, 4 to 1.18, 20 to 0.92, 21 to 1.06, 22 to 1.1, 23 to 0.08,
            26 to 55.0, 27 to 800.0, 60 to 60.0, 62 to 0.25, 63 to 0.75, 64 to 0.5, 65 to 0.3,
            66 to -0.18, 67 to 70.0, 68 to 60.0, 78 to 0.15, 90 to -0.12, 94 to 0.06,
        )
        // ⚠ 每个值都必须**不等于** CameraPresets.standard() 的恒等值：真实预设是稀疏化的产物，
        // 等于恒等值的槽在读取侧会被洗掉 ⇒ 上面那条"往返后 JSON 逐字相同"会红。
        //（[60][63][64][65][67][68] 的恒等值分别是 100 / 0.80 / 0.45 / 0.40 / 80 / 50，不是 0。）
        assertEquals("典型预设 = 20 槽", 20, slots.size)
        val json = presetToJson(
            preset(name = "CineStill 800T", group = "CineStill", slots = slots, lutId = "cinestill_800t", lutStrength = 0.9),
        )
        // 对照口径：同一条预设用 v1 的**索引键**写 params（其余字段逐字相同）
        val v1Params = slots.entries.joinToString(",", "{\"slots\":{", "}}") { (k, v) -> "\"$k\":$v" }
        val v2Params = json.substringAfter("\"params\":").substringBefore(",\"lut\"")
        println(
            "PRESET_JSON_LEN=${json.length} V2_PARAMS_LEN=${v2Params.length} " +
                "V1_PARAMS_LEN=${v1Params.length} SLOTS=${slots.size}",
        )
        // 真实序列化输出（报告 `docs/reports/p1-preset-schema-REPORT.md` 引用的是这一行）
        println("PRESET_JSON_SAMPLE=$json")
        assertTrue("整条预设 JSON 长度应在 500..1200：${json.length}", json.length in 500..1200)
        assertTrue(
            "params 段：语义名(${v2Params.length} B) 必须比索引键(${v1Params.length} B) 长 —— 这就是改语义名的体积代价",
            v2Params.length > v1Params.length,
        )
        // 往返之后体积不变（同一份槽位与元数据）
        val back = presetsFromJson("[$json]").presets.single()
        assertEquals("往返后 JSON 必须逐字相同", json, presetToJson(back))
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
        for (raw in listOf("{", "not json", "{\"a\":1}", "[1,2]", "[{\"schema\":2,]", "null")) {
            val res = presetsFromJson(raw)
            assertTrue("坏输入不该产出预设：$raw", res.presets.isEmpty())
            assertTrue("坏输入必须有诊断：$raw", res.skipped.isNotEmpty())
        }
    }

    /** 缺 id / 缺 name ⇒ 跳过整项（定位与展示都靠它们） */
    @Test
    fun missingIdOrNameSkipsItem() {
        val noId = """[{"schema":2,"name":"x","params":{}}]"""
        val noName = """[{"schema":2,"id":"a","params":{}}]"""
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
            """{"schema":3,"id":"a","name":"x"}""",
            """{"schema":1.5,"id":"a","name":"x"}""",
            """{"schema":"2","id":"a","name":"x"}""",
        )
        cases.forEach { one ->
            val res = presetsFromJson("[$one]")
            assertTrue("必须拒绝：$one", res.presets.isEmpty())
            assertEquals(1, res.skipped.size)
        }
        assertTrue("当前版本 = 2", presetSchemaUsable(2))
        assertFalse(presetSchemaUsable(0))
        assertFalse(presetSchemaUsable(1))
        assertFalse(presetSchemaUsable(3))
        assertFalse(presetSchemaUsable(null))
    }

    /**
     * ★ **schema=1（槽位索引键格式）一律拒绝，且不写迁移函数**（裁决 A1）。这里喂的是一段
     * **真实的 v1 形态数据**（`"slots":{"26":55.0}` = 颗粒 55），断言：跳过 + 不产生预设 +
     * 有可读诊断。
     *
     * 为什么不能"尽力迁移"：v1 的键是槽位号，而 `[5..10]`（shoulder→latitude）与 `[26][27]`
     * （颗粒）语义都漂移过 ⇒ `"5":0.0` 到底是旧 shoulder 还是新 latitude **无法分辨**，
     * 猜错 = 静默给出错误观感。真实设备上也不存在 v1 数据（预设首发于 v0.11.11，未安装测试）
     * ⇒ 拒绝的代价是零。
     */
    @Test
    fun legacySchemaOneIsRejected() {
        val v1 = """
            [{"schema":1,"id":"p_1728543600000_ab12","name":"旧格式预设","group":"G",
              "favorite":false,"createdAt":1728543600000,"source":"user",
              "slots":{"26":55.0,"90":-0.2},"lut":{"id":"cinestill_800t","strength":0.9}}]
        """.trimIndent()
        val res = presetsFromJson(v1)
        assertTrue("v1 数据一条都不该读出来：${res.presets}", res.presets.isEmpty())
        assertEquals(1, res.skipped.size)
        assertTrue("诊断必须点名 schema：${res.skipped}", res.skipped.single().contains("schema"))
        // 实际会进 `preset.load_skipped` 日志的 why 串（报告引用的是这一行）
        println("SKIP_LEGACY=${res.skipped}")
    }

    /** params 形态不对（数组 / 字符串 / 数字）⇒ 跳过整项（本格式没有"定长数组"形态） */
    @Test
    fun paramsMustBeAnObject() {
        for (bad in listOf("[]", "[1,2,3]", "\"x\"", "3", "true")) {
            val res = presetsFromJson("""[{"schema":2,"id":"a","name":"x","params":$bad}]""")
            assertTrue("params=$bad 必须跳项", res.presets.isEmpty())
            assertEquals(1, res.skipped.size)
        }
    }

    /** 缺 params ⇒ 合法（只有 LUT 的预设）；缺 group / favorite / createdAt / note / source ⇒ 默认值 */
    @Test
    fun missingOptionalFieldsFallBack() {
        val res = presetsFromJson("""[{"schema":2,"id":"a","name":"x","lut":{"id":"l"}}]""")
        val p = res.presets.single()
        assertTrue(p.slots.isEmpty())
        assertEquals("", p.group)
        assertFalse(p.favorite)
        assertEquals(0L, p.createdAt)
        assertEquals(PRESET_SOURCE_USER, p.source)
        assertEquals("", p.note)
        assertEquals("l", p.lutId)
        assertEquals(1.0, p.lutStrength, 0.0)
    }

    /**
     * **已知**语义名但值坏 / 槽位被策略排除 ⇒ **逐槽跳过**，预设本身仍有效。
     *
     * 覆盖六类：策略排除（`lut_strength` [34] / `zebra_thr` [30] / `auto_tone` [35] /
     * `local_tone` [36] / 解码槽 `sharpen` [39] / 局部槽 `local_ev` [86]）、非数字、null、
     * 非有限（`1e999` ⇒ Inf）、布尔。
     *
     * ⚠ 被排除的槽一律给**非恒等值**（`zebra_thr` 的恒等值是 0.95、`local_tone` 是 0.25、
     * `sharpen` 是 0.5）：给了恒等值就等于让"恒等值洗白"来兜底，排除列表失效也照样绿。
     */
    @Test
    fun badValuesAreDroppedOneByOne() {
        val raw = """
            [{"schema":2,"id":"a","name":"x","params":{
              "exposure_ev":0.5, "lut_strength":0.85, "zebra_thr":0.42, "auto_tone":1.0,
              "local_tone":0.5, "sharpen":3.0, "local_ev":1.0,
              "contrast":"x", "highlights":null, "shadows":1e999, "fade":true
            }}]
        """.trimIndent()
        val res = presetsFromJson(raw)
        val p = res.presets.single()
        assertEquals(mapOf(0 to 0.5), p.slots)
        assertTrue("坏槽位必须有诊断：${res.skipped}", res.skipped.isNotEmpty())
    }

    /**
     * ★ **未知语义名 ⇒ 整项拒绝**（不是逐槽跳过）。理由：不知道这个键代表什么参数，跳过它
     * 可能给出**另一种风格**且用户无从察觉；而"少一条预设 + 一条可读诊断"是可解释、可恢复的
     * 失败（与"未知 schema 一律拒绝"同一逻辑）。
     */
    @Test
    fun unknownParamNameRejectsWholeItem() {
        val cases = listOf(
            """{"nonexistent_key":1.0}""",
            """{"exposure_ev":0.5,"grain_amout":55.0}""",   // 拼错一个字母（真实会发生的手改错误）
            """{"26":55.0}""",                               // v1 的索引键（旧格式混进来）
            """{"shoulder":0.02}""",                         // 数组字段必须带下标
        )
        cases.forEach { params ->
            val res = presetsFromJson("""[{"schema":2,"id":"a","name":"x","params":$params}]""")
            assertTrue("未知语义名必须拒绝整项：$params ⇒ ${res.presets}", res.presets.isEmpty())
            assertEquals("必须有一条诊断：$params", 1, res.skipped.size)
            assertTrue("诊断必须点名那个键：${res.skipped}", res.skipped.single().contains("未知参数名"))
        }
        // 实际会进 `preset.load_skipped` 日志的 why 串（报告引用的是这一行）
        println("SKIP_UNKNOWN=${presetsFromJson("""[{"schema":2,"id":"a","name":"x","params":{"nonexistent_key":1.0}}]""").skipped}")
        // 反证：同一个槽位换成**已知**名字就能读出来（证明上面拒的是名字，不是别的）
        val ok = presetsFromJson("""[{"schema":2,"id":"a","name":"x","params":{"grain_amount":55.0}}]""")
        assertEquals(mapOf(26 to 55.0), ok.presets.single().slots)
    }

    /** 手改的 JSON 里等于恒等值的槽在**读取时**被洗掉（保住"slots 只含非恒等槽"的不变式） */
    @Test
    fun identityValuesAreWashedOutOnLoad() {
        val raw = """[{"schema":2,"id":"a","name":"x","params":{
            "contrast":1.0,"shoulder[0]":0.01,"cross[0]":1.0,"exposure_ev":0.5}}]"""
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
            val res = presetsFromJson("""[{"schema":2,"id":"a","name":"x","params":{"exposure_ev":0.5},"lut":$lut}]""")
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
            val res = presetsFromJson("""[{"schema":2,"id":"a","name":"x","lut":{"id":"l","strength":$s}}]""")
            val p = res.presets.single()
            assertEquals("l", p.lutId)
            assertEquals("strength=$s 应回默认 1.0", 1.0, p.lutStrength, 0.0)
        }
    }

    /** LUT 强度夹到 UI 量程 0..1.5（手改的 5.0 会让引擎把 LUT 变换外推得离谱） */
    @Test
    fun lutStrengthIsClampedToUiRange() {
        val hi = presetsFromJson("""[{"schema":2,"id":"a","name":"x","lut":{"id":"l","strength":5}}]""")
        assertEquals(1.5, hi.presets.single().lutStrength, 0.0)
        val lo = presetsFromJson("""[{"schema":2,"id":"a","name":"x","lut":{"id":"l","strength":-2}}]""")
        assertEquals(0.0, lo.presets.single().lutStrength, 0.0)
    }

    /** 超过上限 ⇒ 截断保留最新的 [PRESET_MAX] 条 + 诊断 */
    @Test
    fun overLimitIsTruncated() {
        val many = (1..PRESET_MAX + 5).joinToString(",") {
            """{"schema":2,"id":"p$it","name":"n$it","params":{"exposure_ev":$it}}"""
        }
        val res = presetsFromJson("[$many]")
        assertEquals(PRESET_MAX, res.presets.size)
        assertEquals("p1", res.presets.first().id)          // 列表顺序 = 存储顺序（新→旧），保留前 N
        assertTrue(res.skipped.isNotEmpty())
    }

    /** 一项坏、一项好 ⇒ 好的照常读出（不因为邻居坏就整包丢弃）—— 三种坏法各来一条 */
    @Test
    fun oneBadItemDoesNotKillTheRest() {
        val raw = """
            [{"schema":9,"id":"bad-schema","name":"x"},
             {"schema":2,"id":"bad-key","name":"x","params":{"nonexistent_key":1.0}},
             {"schema":1,"id":"bad-legacy","name":"x","slots":{"0":0.5}},
             {"schema":2,"id":"good","name":"好","params":{"exposure_ev":0.5}}]
        """.trimIndent()
        val res = presetsFromJson(raw)
        assertEquals(1, res.presets.size)
        assertEquals("good", res.presets.single().id)
        assertEquals(3, res.skipped.size)
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

    /** 预设默认值：分组 / 说明空串、source = user（内置 / 导入的**常量**留给 E3-4 / E3-2 用） */
    @Test
    fun presetDefaults() {
        val p = EditorPreset(id = "a", name = "n")
        assertEquals("", p.group)
        assertFalse(p.favorite)
        assertEquals(0L, p.createdAt)
        assertEquals(PRESET_SOURCE_USER, p.source)
        assertEquals("", p.note)
        assertTrue(p.slots.isEmpty())
        assertNull(p.lutId)
        assertEquals(1.0, p.lutStrength, 0.0)
        // source 的三个取值（本卡只定常量，**不实现**内置包 / 导入功能）
        assertEquals(
            setOf("user", "builtin", "imported"),
            setOf(PRESET_SOURCE_USER, PRESET_SOURCE_BUILTIN, PRESET_SOURCE_IMPORTED),
        )
    }
}
