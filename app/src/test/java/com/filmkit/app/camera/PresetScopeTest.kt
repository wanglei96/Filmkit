package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-5 护栏：预设的**字段作用域**（[presetScopeNotice]）必须逐槽等于
 * `docs/reports/preset-DESIGN.md` §1.6 的字段能力矩阵。
 *
 * 为什么值得一个测试类：作用域表是**照文档抄的一张表**，抄错一个槽位不会编译失败、不会崩 ——
 * 只会让 UI 在一类预设上说错话（"拍照时不生效"说反 = 用户按提示操作反而更糊涂）。而 §1.6
 * 本身是架构师用脚本从 `FrameRenderer.cpp` 抽出来的**实测结论**，会随 E4-5/E4-6 接线而变。
 * 所以这里照 [EditorStyleWhitelistTest] 的做法：**正则解析文档表格逐槽对拍** ——
 * 文档改了代码没改（或反过来）本测试直接红，不靠"下次记得同步"。
 *
 * 工作目录 = `app/`（gradle 单测约定）⇒ 源码树路径写 `src/main/...`、文档写 `../docs/...`。
 */
class PresetScopeTest {

    private fun preset(vararg slots: Int): EditorPreset =
        EditorPreset(id = "p_test", name = "测试预设", slots = slots.associateWith { 1.0 })

    // ── ① 全部生效 ⇒ null（UI 据此不显示任何东西）─────────────────────────────

    @Test
    fun fullyEffectivePresetHasNoNotice() {
        // §1.6 里两侧都读的槽位（逐组取自矩阵：曝光 / WB / 对比度 / 分区饱和 / 颗粒 / 暗角）
        val p = preset(0, 1, 2, 3, 4, 20, 21, 22, 26, 27, 60, 61, 66, 67, 68)
        assertNull("全部槽位两侧都生效 ⇒ 拍照界面不得有任何提示", presetScopeNotice(p, PresetScene.CAMERA))
        assertNull("全部槽位两侧都生效 ⇒ 编辑器不得有任何提示", presetScopeNotice(p, PresetScene.EDITOR))
        assertNull(
            "空 slots（内置「标准」= 中性基线）也要 null",
            presetScopeNotice(CAMERA_STANDARD_PRESET, PresetScene.CAMERA),
        )
    }

    /** [34] 不在预设 `slots` 里（由 `lut.strength` 承载）⇒ 即使坏数据里出现也不算"不生效"。 */
    @Test
    fun lutStrengthSlotIsNeverReported() {
        val p = EditorPreset(id = "p_test", name = "测试预设", slots = mapOf(34 to 0.5))
        assertNull(presetScopeNotice(p, PresetScene.CAMERA))
        assertNull(presetScopeNotice(p, PresetScene.EDITOR))
    }

    // ── ② 仅拍照参数在编辑器场景 ⇒ 提示且带正确参数名 ─────────────────────────

    /**
     * ★ U-1（2026-10-11）：`[23][24]` 分离色调 + `[25]` 单色**已两侧生效**（kFsStyle 内与
     * kFsFilm 同式接线）⇒ 编辑器里不再有"仅拍照"提示 —— 这正是用户"不支持的（黑白预设不变
     * 黑白）要补上"的直接判据。旧断言（`含 2 项仅拍照参数（分离色调/黑白）`）随 U-1 作废。
     */
    @Test
    fun splitToneAndMonochromeAreEffectiveInEditor() {
        val p = preset(25, 23, 24)
        assertNull(
            "[23..25] U-1 起两侧都生效 ⇒ 编辑器里不该再提示",
            presetScopeNotice(p, PresetScene.EDITOR),
        )
        assertNull("这三项在拍照链路也读 ⇒ 拍照界面不提示", presetScopeNotice(p, PresetScene.CAMERA))
        assertEquals(
            "判据表必须报「两侧都生效」（不在 SLOT_SCENES 里 = 两侧）",
            setOf(PresetScene.CAMERA, PresetScene.EDITOR),
            presetSlotScenes(23),
        )
        assertEquals(
            "[24] 同 [23]（同一条分离色调的两个方向）",
            setOf(PresetScene.CAMERA, PresetScene.EDITOR),
            presetSlotScenes(24),
        )
        assertEquals(
            "[25] 单色同判据",
            setOf(PresetScene.CAMERA, PresetScene.EDITOR),
            presetSlotScenes(25),
        )
    }

    /**
     * ★★ U-2（2026-10-11）：`[5..10]` 趾肩 latitude + `[37][38]` filmic 黑白点**已两侧生效**
     * —— 编辑器侧落点 = **显示域胶片曲线**（`kFsStyle` 的端点重映射 + cur/base 差值多项式，
     * 系数由 `computeEditorCurve` 求解）⇒ **判据表清空、编辑器里不再有"仅拍照"提示**。
     *
     * 本测试**取代**三条随 U-2 作废的用例：
     *  · `stillCameraOnlyParamsAreReportedInEditor`（断言 `含 2 项仅拍照参数（黑点/白点）…`）；
     *  · `filmCurveCountsAsOneItem`（断言 `含 1 项仅拍照参数（胶片曲线）…`）；
     *  · `singleSideNoticeFitsInOneLine`（断言三类名字拼成一行 —— U-4 删截断时留下的上界）。
     * 口径从"某个提示文案对"升级为"**这一类提示不可能再出现**"：遍历全部槽位，任何槽位都
     * 不允许是单侧生效。若将来新增只对一条链路生效的参数，本条会直接红（连同 §1.6 的对拍
     * 一起要求改）—— 那时 `SLOT_SCENES` 加条目，提示机制自动恢复（保留函数的价值就在这里）。
     */
    @Test
    fun noSingleSideSlotsRemain() {
        val p = preset(5, 6, 7, 8, 9, 10, 37, 38)
        assertNull(
            "U-2 起 [5..10]/[37][38] 两侧都生效 ⇒ 编辑器里不该再提示",
            presetScopeNotice(p, PresetScene.EDITOR),
        )
        assertNull("拍照链路本来就读这八项 ⇒ 拍照界面也不提示", presetScopeNotice(p, PresetScene.CAMERA))
        for (slot in 0 until CameraPresets.COUNT) {
            assertEquals(
                "槽位 [$slot] 不该是「单侧生效」（U-1 消 [23..25]、U-3 消 [88][89]、" +
                    "U-4 消 [78]/[90..94]、U-2 消 [5..10]/[37][38]）—— 新增单侧参数时" +
                    "本条与 §1.6 一起改",
                setOf(PresetScene.CAMERA, PresetScene.EDITOR),
                presetSlotScenes(slot),
            )
        }
    }

    /**
     * ★ U-2 的"终局"断言：**判据表为空** ⇒ 任何预设、任何场景都拿不到提示。
     *
     * 为什么单列一条：`noSingleSideSlotsRemain` 走的是 `presetSlotScenes`（判据），
     * 这条走的是**用户可见的输出**（`presetScopeNotice`）——"P-5 的提示还剩几条"的答案
     * 必须是 0（本卡交付口径）。用一个含全部"历史单侧槽位"的预设压测：U-1 的 `[23..25]`、
     * U-3 的 `[88][89]`、U-4 的 `[78]/[90..94]`、U-2 的 `[5..10]/[37][38]` 全放进去。
     */
    @Test
    fun scopeNoticeIsAlwaysNullAfterU2() {
        val everything = preset(23, 24, 25, 78, 88, 89, 90, 91, 92, 93, 94, 5, 6, 7, 8, 9, 10, 37, 38)
        for (scene in PresetScene.entries) {
            assertNull(
                "U-2 后判据表为空 ⇒ $scene 场景下不该有任何作用域提示（P-5 实例数 = 0）",
                presetScopeNotice(everything, scene),
            )
        }
    }

    // ── ③ U-4（2026-10-11）：判据表里**再没有"仅后期"槽位** ───────────────────

    /**
     * ★ U-4：`[78]` 清晰度 + `[90..94]` 影调分区/褪色**已两侧生效**（拍照侧落点 =
     * `passOutput` 的 `srgbEncode` 之后 = **显示域**，与 kFsStyle 同域同式）⇒ 拍照界面
     * 不再有"仅后期参数"这类提示。
     *
     * 本测试**取代**原来的 `editorOnlyParamsAreReportedInCamera`（那条断言
     * `含 1 项仅后期参数（清晰度），拍照时不生效` 随 U-4 作废）：口径从"提示文案对"升级为
     * "这一类提示**不可能再出现**"—— 遍历全部槽位，任何槽位都不允许是 editor-only。
     * 若将来新增只对编辑器生效的参数，本条会直接红（连同 §1.6 的对拍一起要求改）。
     */
    @Test
    fun noEditorOnlySlotsRemain() {
        val p = preset(78, 90, 91, 92, 93, 94)
        assertNull(
            "U-4 起 [78]/[90..94] 两侧都生效 ⇒ 拍照界面不该再提示",
            presetScopeNotice(p, PresetScene.CAMERA),
        )
        assertNull("编辑器链路本来就读这六项 ⇒ 也不提示", presetScopeNotice(p, PresetScene.EDITOR))
        for (slot in 0 until CameraPresets.COUNT) {
            assertTrue(
                "槽位 [$slot] 不该是「仅后期」（U-1 消 [23..25]、U-3 消 [88][89]、" +
                    "U-4 消 [78]/[90..94]）—— 新增 editor-only 参数时本条与 §1.6 一起改",
                PresetScene.CAMERA in presetSlotScenes(slot),
            )
        }
    }

    /**
     * ★ U-3（2026-10-11）：`[88][89]` 色温/色调**已两侧生效**（拍照链路接入 `wbTempTint` →
     * `wbCompGNorm` + `prepareRawCommon`，与编辑器 `wb_tot` 同一个 `tempTintGain`）⇒ 拍照
     * 界面不再提示 —— 这正是用户"缺的补上"在"套了预设没反应"这一侧的判据。
     *
     * ⚠ 与 `[23..25]`（U-1）的分工：那批是"编辑器里没反应"，这批是"拍照时没反应"。
     */
    @Test
    fun tempTintAreEffectiveInCamera() {
        val p = preset(88, 89)
        assertNull(
            "[88][89] U-3 起两侧都生效 ⇒ 拍照界面不该再提示",
            presetScopeNotice(p, PresetScene.CAMERA),
        )
        assertNull("编辑器链路 E2-11 起就读 ⇒ 编辑器也不提示", presetScopeNotice(p, PresetScene.EDITOR))
        assertEquals(
            "判据表必须报「两侧都生效」（不在 SLOT_SCENES 里 = 两侧）",
            setOf(PresetScene.CAMERA, PresetScene.EDITOR),
            presetSlotScenes(88),
        )
        assertEquals("[89] 同 [88]（同一条白点偏移的两个轴）", setOf(PresetScene.CAMERA, PresetScene.EDITOR), presetSlotScenes(89))
        assertEquals(
            "[90] 影调分区同 U-4 起两侧都生效（拍照侧落点 = passOutput 的显示域段）" +
                "—— 与 [88][89] 同判据，不再是「仅后期」",
            setOf(PresetScene.CAMERA, PresetScene.EDITOR),
            presetSlotScenes(90),
        )
        assertEquals(
            "[94] 褪色同判据（同一条显示域段）",
            setOf(PresetScene.CAMERA, PresetScene.EDITOR),
            presetSlotScenes(94),
        )
        assertEquals(
            "[78] 清晰度同判据（passOutput 的 16-tap 显示域 USM）",
            setOf(PresetScene.CAMERA, PresetScene.EDITOR),
            presetSlotScenes(78),
        )
    }

    /**
     * 单侧生效槽位**一个都不剩**（U-4 时是"名字去重后 ≤ 3 项 ⇒ 一行放得下"；
     * U-2 把最后三类也消掉了 ⇒ 上界收紧到 0）。
     *
     * ⚠ 保留这条而不是删掉：它是"提示又回来时"的第一道报警 —— 一旦有槽位重新变成单侧，
     * `count` 立刻 > 0 且本条红，逼着一起恢复截断/名字表（那时 `SLOT_LABELS` 的名字表
     * 仍然在，见 `PresetScope.kt` 的 KDoc）。
     */
    @Test
    fun noSingleSideSlotsAtAll() {
        val single = (0 until CameraPresets.COUNT).filter { presetSlotScenes(it).size == 1 }
        assertEquals(
            "U-2 起判据表为空 ⇒ 单侧槽位数必须是 0（有的话本条 + §1.6 对拍 + PresetScope 一起改）",
            emptyList<Int>(),
            single,
        )
    }

    /** 每个"单侧生效"的槽位都必须有中文名（防名字表漏项 ⇒ 提示里冒出英文语义名）。 */
    @Test
    fun everySingleSideSlotHasAChineseLabel() {
        for (slot in 0 until CameraPresets.COUNT) {
            val scenes = presetSlotScenes(slot)
            if (scenes.size != 1) continue
            val scene = if (PresetScene.CAMERA in scenes) PresetScene.EDITOR else PresetScene.CAMERA
            val p = EditorPreset(id = "p_test", name = "测试预设", slots = mapOf(slot to 1.0))
            val notice = presetScopeNotice(p, scene) ?: error("槽位 [$slot] 在 $scene 下应当有提示")
            val label = notice.substringAfter("（").substringBefore("）")
            assertFalse("槽位 [$slot] 的名称缺失，回落成了语义名：$notice", label.contains("["))
            assertTrue("槽位 [$slot] 的提示名必须是中文：$notice", label.any { it.code in 0x4E00..0x9FFF })
        }
    }

    // ── ④ 判据表 == §1.6 的能力矩阵（防"文档与代码漂移"）──────────────────────

    /**
     * 解析 §1.6 的表格 → `槽位 → (拍照链路读?, 编辑器链路读?)`。
     *
     * 判定口径：单元格以 `❌` 开头 = 不生效；`✅` / `⚠`（如 `[20..22]` 编辑器侧"只读 [21]"）
     * 以及文字描述（`[30][35][36]` 的"工程旋钮"/"部分"）都算**生效** —— 与任务书口径一致
     *（"两侧都生效 … 以 §1.6 为准"）。
     */
    private fun designMatrix(): Map<Int, Pair<Boolean, Boolean>> {
        val src = File("../docs/reports/preset-DESIGN.md").readText()
        val section = src.substringAfter("### ★ 1.6").substringBefore("\n### ")
        assertTrue("§1.6 小节没解析出来（标题或文档结构变了）", section.length > 200)
        val out = LinkedHashMap<Int, Pair<Boolean, Boolean>>()
        for (line in section.lines()) {
            if (!line.startsWith("|")) continue
            val cells = line.split("|").map { it.trim() }
            if (cells.size < 6) continue
            val slots = Regex("\\[(\\d+)(?:\\.\\.(\\d+))?]").findAll(cells[1]).flatMap { m ->
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
                (a..b).asSequence()
            }.toList()
            if (slots.isEmpty()) continue
            val cam = !cells[3].startsWith("❌")
            val ed = !cells[4].startsWith("❌")
            for (s in slots) out[s] = cam to ed
        }
        return out
    }

    @Test
    fun scopeTableMatchesDesignMatrix() {
        val matrix = designMatrix()
        assertTrue("§1.6 表格解析出的槽位太少（${matrix.size}）—— 正则或文档结构漂移", matrix.size >= 55)
        var singleSide = 0
        var excluded = 0
        matrix.forEach { (slot, ce) ->
            // 不进预设的槽位**无所谓作用域**：§1.6 里标"仅拍照"的 [28]/[31..33] 已被
            // presetSlotIncluded 排除（P-5 复核裁决），[30][35][36]/[34] 本来就不进预设 ⇒
            // 不参与对拍。⚠ 跳过的代价 = "排除项与作用域表的同步"没人管 ⇒ 见下一条断言。
            if (!presetSlotIncluded(slot)) {
                excluded++
                return@forEach
            }
            val (cam, ed) = ce
            val expected = when {
                cam && ed -> setOf(PresetScene.CAMERA, PresetScene.EDITOR)
                cam -> setOf(PresetScene.CAMERA)
                ed -> setOf(PresetScene.EDITOR)
                // 两侧都 ❌ = 引擎还没实现（§1.6 的"进预设？"列标 ⏳ 等 E4-5 / E4-6）⇒ 换到哪条
                // 链路都没有落点，不是"场景差异"，本表按"两侧都生效"处理（UI 不提示）。
                else -> setOf(PresetScene.CAMERA, PresetScene.EDITOR)
            }
            assertEquals(
                "槽位 [$slot] 的作用域必须与 preset-DESIGN.md §1.6 的矩阵一致" +
                    "（以 §1.6 为准；冲突时改代码 + 报告，不要改文档）",
                expected,
                presetSlotScenes(slot),
            )
            if (cam != ed) singleSide++
        }
        assertEquals(
            "§1.6 里单侧生效**且进预设**的槽位数（★ U-2 后 = 0；历史：19 − [23..25]（U-1，3）" +
                "− [88][89]（U-3，2）− [78]+[90..94]（U-4，6）− [5..10]+[37][38]（U-2，8）= 0）",
            0,
            singleSide,
        )
        assertEquals(
            "§1.6 里被 presetSlotIncluded 排除的槽位数（[28]+[30]+[31..33]+[34]+[35][36]）",
            8,
            excluded,
        )
    }

    /**
     * **排除项与作用域表的同步**（P-5 复核新增）：不进预设的槽位在作用域表里必须是"两侧都生效"
     *（= 不在表里）。
     *
     * 为什么值得一条：把 `[28]` / `[31..33]` 加进排除列表时，如果忘了从 `SLOT_SCENES` 删掉它们，
     * **功能上没有任何影响**（它们进不了预设，提示永远走不到）—— 但表里留下**死条目**，下次
     * 有人读 `SLOT_SCENES` 会以为"这几个槽位还能进预设、只是仅拍照生效"。上一条对拍测试
     * 恰好跳过了它们，所以只有这条能发现。
     */
    @Test
    fun excludedSlotsHaveNoScope() {
        var checked = 0
        for (slot in 0 until CameraPresets.COUNT) {
            if (presetSlotIncluded(slot)) continue
            checked++
            assertEquals(
                "槽位 [$slot] 不进预设 ⇒ 判据表不该给它标作用域（[28]/[31..33] 是 P-5 复核新排除的）",
                setOf(PresetScene.CAMERA, PresetScene.EDITOR),
                presetSlotScenes(slot),
            )
        }
        assertEquals(
            "排除槽位数（[28][29][30][31][32][33][34][35][36] + [39..59] + [79..87]）",
            9 + 21 + 9,
            checked,
        )
    }

    // ── ⑤ 内置三条（真实数据）的实际提示 ──────────────────────────────────────

    /**
     * ★ 内置三条是**唯一在真机上一定会被套用**的预设，所以它们的提示必须逐条对（不是"跑通就算"）。
     * 数据从源码树资产读（与 [BuiltinPresetsTest] 同一口径）。
     *
     * 实测结论（**不要假设**）：
     *  - 徕卡一瞬 / 理光负片含 `[23][24]` 分离色调 ⇒ ★ U-1 起**编辑器里也生效** ⇒ 不提示；
     *  - **徕卡黑白不含 `[25]`**：黑白靠 `leica_b_w_hc` LUT 的通道混合实现（先 `[25]` 去色会把
     *    LUT 的通道混合作用在灰上 ⇒ 抹平黄绿滤镜响应，见 [BuiltinPresetsTest.bwPresetHasNoMonochrome]）
     *    ⇒ 编辑器里**全部生效**，不该有任何提示；
     *  - ★ U-4（2026-10-11）起**拍照界面不可能再有"仅后期"提示**（判据表里已无 editor-only
     *    槽位）—— 三条内置预设照旧一条提示都不该有。
     */
    @Test
    fun builtinPresetsNoticesMatchTheirData() {
        val json = File("src/main/assets/${BuiltinPresets.ASSET_PATH}").readText()
        val presets = BuiltinPresets.parse(json)
        assertEquals("内置包必须恰好 3 条", 3, presets.size)

        for (p in presets) {
            assertNull(
                "${p.id} 拍照界面不该有任何提示（U-4 起判据表里无 editor-only 槽位）",
                presetScopeNotice(p, PresetScene.CAMERA),
            )
            // ★ P-5 复核**实测**（不是假设）：内置配方不含新排除的 [28] / [31..33]
            //（引擎色彩科学状态）⇒ 这次排除对内置包零影响。
            for (slot in listOf(28, 31, 32, 33)) {
                assertFalse(
                    "${p.id} 不该含 P-5 新排除的槽 [$slot]（实测内置配方不含色彩科学状态）",
                    p.slots.containsKey(slot),
                )
            }
        }
        for (id in listOf("builtin_leica_m9", "builtin_ricoh_negative")) {
            val p = presets.first { it.id == id }
            assertNull(
                "$id 含 [23][24] 分离色调 ⇒ ★ U-1 起编辑器里也生效 ⇒ 不该提示",
                presetScopeNotice(p, PresetScene.EDITOR),
            )
            println("BUILTIN_SCOPE $id editor=\"${presetScopeNotice(p, PresetScene.EDITOR)}\"")
        }
        val bw = presets.first { it.id == "builtin_leica_bw" }
        assertFalse("徕卡黑白不得带 [25]（黑白由 LUT 承载）", bw.slots.containsKey(25))
        assertNull(
            "徕卡黑白不含 [25] ⇒ 编辑器里全部生效，不该提示",
            presetScopeNotice(bw, PresetScene.EDITOR),
        )
    }
}
