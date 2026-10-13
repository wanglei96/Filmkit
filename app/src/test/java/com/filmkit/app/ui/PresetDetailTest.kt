package com.filmkit.app.ui

import com.filmkit.app.camera.BuiltinPresets
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.PRESET_SOURCE_BUILTIN
import com.filmkit.app.camera.PRESET_SOURCE_IMPORTED
import com.filmkit.app.camera.PRESET_SOURCE_USER
import com.filmkit.app.camera.PresetScene
import com.filmkit.app.camera.presetScopeNotice
import com.filmkit.app.camera.presetSlotIncluded
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-6a 预设详情的纯逻辑护栏（[presetDetailSections] / [presetLutLine] / [presetSourceText]）。
 *
 * 四条硬要求（用户需求"预设应该展示全部可调参数"的验收点）：
 *  1. **稀疏语义**：只列出这条预设真正包含的槽（内置三条各只有 5–7 项，不是 96 项全列）；
 *  2. **文案 / 单位正确**：中文名与编辑器参数面板**同一份**文案（本测试**正则解析
 *     `EditorScreen.kt` 的 `EditorParam` 表逐槽对拍** —— 与 `PresetScopeTest` 解析文档 §1.6、
 *     `EditorStyleWhitelistTest` 对拍 `p.<字段>` 是同一个手法：不靠"下次记得同步"）；
 *  3. **未知槽位不静默消失**：表里没名字的槽显示 [com.filmkit.app.camera.FilmParamKeys] 的
 *     语义名或 `槽N`，永远看得见；
 *  4. **[34] LUT 强度不进参数列表**：它由 `lut.strength` 承载（详情里单独一行）。
 *
 * 真实数据来源 = `app/src/main/assets/presets/builtin.json`（工作目录 = `app/`）：
 * 用真实内置预设断言，而不是手搓一份"想象中的预设"。
 */
class PresetDetailTest {

    private fun allRows(slots: Map<Int, Double>): Map<Int, PresetDetailRow> =
        presetDetailSections(slots).flatMap { it.rows }.associateBy { it.slot }

    private fun render(slots: Map<Int, Double>): List<String> =
        presetDetailSections(slots).map { sec ->
            sec.title + "|" + sec.rows.joinToString(";") { "${it.label}=${it.value}" }
        }

    private fun builtin(name: String): EditorPreset {
        val json = File("src/main/assets/presets/builtin.json").readText()
        return BuiltinPresets.parse(json).first { it.name == name }
    }

    // ── ① 稀疏：只列出这条预设真正包含的槽 ────────────────────────────────────

    @Test
    fun sparsePresetListsOnlyItsOwnSlots() {
        val slots = mapOf(26 to 20.0, 66 to -0.18)
        val rows = allRows(slots)
        assertEquals("稀疏预设只该有这两行", setOf(26, 66), rows.keys)
        assertEquals("颗粒 · 强度", rows.getValue(26).label)
        assertEquals("20", rows.getValue(26).value)
        assertEquals("暗角 · 强度", rows.getValue(66).label)
        assertEquals("18", rows.getValue(66).value)
        // 未包含的槽一个都不能冒出来（尤其是恒等值 1.0 的那批）
        for (absent in listOf(0, 4, 20, 21, 22, 27, 67, 68, 78, 88)) {
            assertNull("槽 $absent 不在预设里 ⇒ 详情不得出现", rows[absent])
        }
        assertEquals("空预设 ⇒ 空列表（UI 显示「不含参数」）", emptyList<String>(), render(emptyMap()))
    }

    /** 分组的顺序固定（§9.2 的决策顺序），且只输出非空组。 */
    @Test
    fun sectionsFollowDesignDocOrder() {
        val slots = mapOf(66 to -0.2, 4 to 1.2, 23 to 0.1, 37 to -7.65)
        assertEquals(
            listOf("基础调整", "色彩", "影调曲线", "胶片质感"),
            presetDetailSections(slots).map { it.title },
        )
    }

    // ── ② 真实内置预设的实际输出（验收判据 1）──────────────────────────────────

    /**
     * "徕卡一瞬"的逐行渲染 —— R-1c 之后配方 = 在 `lut.strength ≥ 0.30` 下界下重拟合的产物
     * （`builtin.json`：对比度 1.03 / sat_shadow 1.15 + sat_high 0.85 / shoulder 0.53–0.58 /
     * toe 0.82–0.95 / 黑白点 −11.9726 + **4.0**），LUT 强度 **0.3085**。
     * ★ R-3 之后：白点 4.0（死白起点 3.55EV），曲线在 `--hi-floor 3.5` 约束下重拟合，
     * 代价见 `docs/reports/r3-white-point-refit-REPORT.md`。本断言逐行钉住"配方 → 用户可读读数"的换算。
     */
    @Test
    fun builtinLeicaMomentRendersHumanReadableRows() {
        val p = builtin("徕卡一瞬")
        assertEquals(
            listOf(
                // 1.03 ⇒ (1.03−1)×100 = +3
                "基础调整|对比度=+3",
                // ★ R-3：[21] sat_mid 回恒等 1.0 ⇒ 不进 slots（稀疏），[20]/[22] 重新拟合
                "色彩|饱和度 · 暗部=+15;饱和度 · 高光=-15;分离色调 · 暖=+0.10;分离色调 · 冷=+0.05",
                // ★ R-3：shoulder/toe **解耦**（0.5278/0.4253/0.5806 与 0.9219/0.8216/0.9488）
                "影调曲线|胶片曲线 · 肩部 红=0.53;胶片曲线 · 肩部 绿=0.43;胶片曲线 · 肩部 蓝=0.58;" +
                    "胶片曲线 · 趾部 红=0.92;胶片曲线 · 趾部 绿=0.82;胶片曲线 · 趾部 蓝=0.95;" +
                    "黑点=-12.0 EV;白点=+4.0 EV",
                "胶片质感|颗粒 · 强度=20;颗粒 · 尺寸=ISO 160;暗角 · 强度=18;暗角 · 半径=85;暗角 · 羽化=55",
            ),
            render(p.slots),
        )
        // LUT 强度 0.3085 ⇒ 读数 31（0..150 口径，与 LUT 强度滑杆同一函数）
        assertEquals(PresetLutLine("leica_m9", "31"), presetLutLine(p))
    }

    /** 三条内置预设都要能渲染（不许有"表里缺名字"的槽 —— 缺了就会露出英文语义名）。 */
    @Test
    fun allBuiltinPresetsHaveChineseNamesAndUnits() {
        val json = File("src/main/assets/presets/builtin.json").readText()
        val list = BuiltinPresets.parse(json)
        assertEquals("内置包应有三条（P-2b）", 3, list.size)
        for (p in list) {
            val rows = presetDetailSections(p.slots).flatMap { it.rows }
            assertEquals("${p.name}：行数必须与槽位数相等（一个都不许丢）", p.slots.size, rows.size)
            for (r in rows) {
                assertTrue("${p.name} 槽 ${r.slot} 露出了非中文名「${r.label}」", Regex("[\\u4e00-\\u9fa5]").containsMatchIn(r.label))
                assertTrue("${p.name} 槽 ${r.slot} 的读数不该为空", r.value.isNotBlank())
            }
        }
    }

    /** 单位口径（E2-10"读数改成物理量"）：曝光 EV / 色温 K / 颗粒尺寸 ISO / 黑点 EV。 */
    @Test
    fun physicalUnitsAreAttached() {
        val rows = allRows(mapOf(0 to 1.5, 88 to 0.0, 27 to 400.0, 37 to -7.65, 94 to 0.35))
        assertEquals("+1.5 EV", rows.getValue(0).value)   // 槽位 1.5 EV ⇒ UI 0.5 ⇒ "+1.5 EV"
        assertEquals("6500K", rows.getValue(88).value)    // 槽位 0（mired 偏移）= 参考白点
        assertEquals("ISO 400", rows.getValue(27).value)
        // -7.65 EV：与 formatEv 同口径（roundToInt 是 half-up ⇒ -76.5 → -76 ⇒ -7.6）
        assertEquals("-7.6 EV", rows.getValue(37).value)
        assertEquals("35", rows.getValue(94).value)
    }

    // ── ③ 未知槽位不静默消失 ─────────────────────────────────────────────────

    @Test
    fun unknownSlotsFallBackToSemanticNamesAndNeverDisappear() {
        // [11] 有语义名无中文名（cross 矩阵）；[29] 已退役、[99] 越界 ⇒ 连语义名都没有。
        // ⚠ 阶段 0（v0.11.27）：越界例子从 [98] 改为 [99] —— kCount 98 → 99 后 [98] 已是
        // 合法槽（engine_slot，有语义名）⇒ 继续用它会测的是"新槽有没有名字"而不是"越界"。
        val rows = allRows(mapOf(11 to 0.5, 29 to 1.0, 99 to 2.0))
        assertEquals("三个槽一个都不许消失", setOf(11, 29, 99), rows.keys)
        assertEquals("cross[0]", rows.getValue(11).label)
        assertEquals("0.50", rows.getValue(11).value)
        assertEquals("槽29", rows.getValue(29).label)
        assertEquals("1", rows.getValue(29).value)
        assertEquals("槽99", rows.getValue(99).label)
        assertEquals("2", rows.getValue(99).value)
        assertEquals("未映射槽位统一进「其它」组", listOf("其它"), presetDetailSections(mapOf(11 to 0.5, 99 to 2.0)).map { it.title })
    }

    /** 被 `presetSlotIncluded` 排除的槽（引擎旋钮 / 解码 / 局部）若出现在坏数据里，同样不许消失。 */
    @Test
    fun excludedSlotsFromBadDataStillShowUp() {
        val rows = allRows(mapOf(28 to 1.0, 39 to 0.5, 84 to 0.5))
        assertEquals(setOf(28, 39, 84), rows.keys)
        assertEquals("tone_strength", rows.getValue(28).label)
        assertEquals("sharpen", rows.getValue(39).label)
        assertEquals("local_angle", rows.getValue(84).label)
    }

    // ── ④ [34] LUT 强度：由 lut.strength 承载，不进参数列表 ────────────────────

    @Test
    fun lutStrengthSlotIsNotAParamRow() {
        assertTrue("槽 34 不进参数列表（它由 lut.strength 承载）", allRows(mapOf(34 to 0.8)).isEmpty())
        // 但"不进参数列表"不等于消失：LUT 行给的是同一条信息（id + 强度 0..150）
        val p = EditorPreset(id = "p", name = "n", slots = mapOf(34 to 0.8), lutId = "leica_m9", lutStrength = 0.8)
        assertEquals(PresetLutLine("leica_m9", "80"), presetLutLine(p))
        assertNull("预设不含 LUT ⇒ 详情不渲染 LUT 行（缺失 = 不套用）", presetLutLine(EditorPreset(id = "p", name = "n")))
    }

    // ── ⑤ 文案与既有 UI 同源（正则解析面板参数表逐槽对拍）─────────────────────

    private data class PanelParam(val key: String, val label: String, val slot: Int)

    /**
     * 解析 `ParamControls.kt` 的 `EditorParam` 表（★ PS-3：参数表从 `EditorScreen.kt` **逐字搬移**到这里，编辑器与拍照界面共用）：块内取 `label = "…"` 与 `read = { … it[N] }`。
     * `LutStrengthParam` 的 read 里是 `p[34]`（没有 `it[N]`）⇒ 自然被跳过（[34] 本来就不该在
     * 详情里，见 ④）。
     */
    private fun editorParamTable(): List<PanelParam> {
        val src = File("src/main/java/com/filmkit/app/ui/ParamControls.kt").readText()
        val keyRe = Regex("""key = "(\w+)"""")
        val labelRe = Regex("""label = "([^"]+)"""")
        val readRe = Regex("""read = \{([^}]*)\}""")
        val slotRe = Regex("""it\[(\d+)]""")
        return src.split("EditorParam(").drop(1).mapNotNull { raw ->
            val block = raw.substringBefore("\n    ),")
            val label = labelRe.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val read = readRe.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val slot = slotRe.find(read)?.groupValues?.get(1)?.toInt() ?: return@mapNotNull null
            PanelParam(keyRe.find(block)?.groupValues?.get(1) ?: "", label, slot)
        }
    }

    /**
     * **详情里的名字必须包含参数面板的 label**（不许为同一个槽发明第二个译名）。
     * 面板 label 有组内歧义（"强度"/"半径"/"羽化"各出现两次），所以用**包含**而不是相等 ——
     * 详情名 = 分组前缀 + 面板原词（"暗角 · 强度"），前缀是消歧用的，不是新名字。
     */
    @Test
    fun detailLabelsReuseEditorPanelWording() {
        val panel = editorParamTable()
        assertTrue("解析 ParamControls.kt 的 EditorParam 表失败（只有 ${panel.size} 条）", panel.size >= 15)
        // ★ 只喂**真能进预设**的槽（`presetSlotIncluded`）：局部调整 [79..87] 与 LUT 强度 [34]
        //   被策略排除 ⇒ 详情里本来就不会有它们的行（"坏数据里出现了"是另一条测试）。
        // ★ 阶段 0：上界不再写死（`0 until CameraPresets.COUNT`）—— 扩槽后自动覆盖新槽，
        //   不会再漏检（旧写死的上界 97 是 kCount = 98 时代的残留）。
        val rows = allRows((0 until CameraPresets.COUNT).filter { presetSlotIncluded(it) }.associateWith { 0.5 })
        var checked = 0
        for (p in panel) {
            val row = rows[p.slot] ?: continue      // 局部调整 [79..87] 不进预设 ⇒ 详情里没有行
            checked++
            assertTrue(
                "槽 ${p.slot}（面板 label「${p.label}」）的详情名「${row.label}」必须复用面板文案",
                row.label.contains(p.label),
            )
        }
        assertTrue("真正比到的槽位太少（$checked）⇒ 解析或表本身有问题", checked >= 10)
    }

    /**
     * 与 P-5 的 [presetScopeNotice] 用词一致（两张表都描述同一批槽位，不许各叫各的）。
     *
     * ★ **清单不硬编码**：见循环上方的注释（U-1 接线 `[23..25]` 就是这条纪律的真实触发点）。
     */
    @Test
    fun detailLabelsMatchScopeNoticeWording() {
        // ⚠ 清单**不硬编码**：作用域表随引擎接线而变（U-1 把 `[23..25]` 接到编辑器链路后
        // P-5 就不再提示它们；U-2 接线 `[5..10]` / `[37][38]` 时同理）⇒ 遍历全部
        // `CameraPresets.COUNT` 个槽，
        // **由 `presetScopeNotice` 自己决定**哪些槽还有"跨链路差异"、提示里用的是什么词，
        // 再要求详情用同一个词。写死清单会在每次接线时假红（把"接线完成"报成"文案漂移"）。
        var checked = 0
        for (slot in 0 until CameraPresets.COUNT) {
            val slots = mapOf(slot to 0.5)
            val p = EditorPreset(id = "p", name = "n", slots = slots)
            val notice = presetScopeNotice(p, PresetScene.EDITOR)
                ?: presetScopeNotice(p, PresetScene.CAMERA)
                ?: continue                        // 两侧都生效 ⇒ 没有提示，没什么可比
            val name = notice.substringAfter("（").substringBefore("）")
            val label = allRows(slots).getValue(slot).label
            assertTrue("槽 $slot：P-5 提示用「$name」，详情却叫「$label」", label.contains(name))
            checked++
        }
        // ★ U-2（2026-10-11）：判据表清空 ⇒ `presetScopeNotice` 恒 `null` ⇒ **没有可比项**
        // （原断言 `checked >= 5` 随 U-2 作废 —— 那时还有 [5..10]/[37][38] 三类单侧槽位）。
        // 改成"必须为 0"：一旦又有单侧槽位，`checked` 会 > 0 ⇒ 本条红，逼着一起恢复上面的
        // 逐槽比对（这正是保留这段循环的价值）。
        assertEquals(
            "U-2 起判据表为空 ⇒ 没有「跨链路差异」可比（重新出现时本条与 PresetScopeTest 一起红）",
            0,
            checked,
        )

        // ★ U-1 口径对齐（架构师 2026-10-11 同步）：`[23][24]` 分离色调 / `[25]` 单色已接到编辑器
        // 链路 ⇒ **两侧都生效** ⇒ 作用域提示里**不得**再出现它们（"仅拍照"是旧口径）。
        // 反向断言，防"接线完成"被重新写回灰显/提示（届时本测试与 `PresetScopeTest` 同时红）。
        for (slot in listOf(23, 24, 25)) {
            val p = EditorPreset(id = "p", name = "n", slots = mapOf(slot to 0.5))
            assertNull(
                "槽 $slot 两侧都生效（U-1 已接线）⇒ 拍照场景不得提示它不生效",
                presetScopeNotice(p, PresetScene.CAMERA),
            )
            assertNull(
                "槽 $slot 两侧都生效（U-1 已接线）⇒ 编辑器场景不得提示它不生效",
                presetScopeNotice(p, PresetScene.EDITOR),
            )
        }
        // ★ U-1：[23][24] 分离色调 / [25] 单色 —— 两侧都生效（不再有 P-5 提示），但详情页
        // 仍必须用 P-5 已确立的用户可懂术语，**不是**工程名（`split_warm` / `monochrome`）。
        val u1 = allRows(mapOf(23 to 0.5, 24 to 0.5, 25 to 0.5))
        assertTrue(
            "槽 23 详情名应含「分离色调」，实测「${u1.getValue(23).label}」",
            u1.getValue(23).label.contains("分离色调"),
        )
        assertTrue(
            "槽 24 详情名应含「分离色调」，实测「${u1.getValue(24).label}」",
            u1.getValue(24).label.contains("分离色调"),
        )
        assertTrue(
            "槽 25 详情名应为「黑白」，实测「${u1.getValue(25).label}」",
            u1.getValue(25).label.contains("黑白"),
        )
    }

    // ── ⑥ 元数据 ─────────────────────────────────────────────────────────────

    @Test
    fun metadataTexts() {
        assertEquals("内置", presetSourceText(PRESET_SOURCE_BUILTIN))
        assertEquals("用户", presetSourceText(PRESET_SOURCE_USER))
        assertEquals("导入", presetSourceText(PRESET_SOURCE_IMPORTED))
        assertEquals("未知来源按用户显示（数据层默认值）", "用户", presetSourceText("whatever"))
        assertEquals("内置三条没有 createdAt（随包分发）⇒ 不显示 1970", "—", presetCreatedAtText(0L))
        assertEquals("—", presetCreatedAtText(-1L))
        assertTrue(
            "真实时间戳要渲染成可读日期",
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(presetCreatedAtText(1_728_543_600_000L)),
        )
    }
}
