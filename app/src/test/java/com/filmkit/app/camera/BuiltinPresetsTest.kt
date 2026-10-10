package com.filmkit.app.camera

import com.filmkit.app.lut.CubeLutParser
import com.filmkit.app.ui.CameraPresets
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-2b 内置胶片预设包（`assets/presets/builtin.json` + [BuiltinPresets]）的护栏测试。
 *
 * 为什么值得一整个测试类：内置包的配方是**架构师按 LUT 实测数据逐条定的**（对比度斜率 /
 * 通道响应 / 白场截断 + 胶片物理常识），而"数值"这种东西在代码里没有任何结构约束 ——
 * 谁"顺手调一下颗粒强度"都不会编译失败、不会崩、只会在用户成片上悄悄换一种观感。
 * 所以配方值、LUT 引用、黑白不设 `[25]` 这三类**决定**全部逐项钉在这里。
 *
 * ★ **断言口径 = "套用后的有效值"**（`presetSnapshot(标准基线, slots)`），不是裸的 `slots[i]`：
 * [EditorPreset.slots] 是**稀疏**的（只含与恒等值不同的槽，见 [presetSparseSlots]），而配方里
 * 有几项恰好等于恒等值 —— `grain_size [27] = 400`（= [CameraPresets] 的默认 ISO）、
 * `vignette_radius [67] = 80`（= darktable `scale` 默认）。它们在 `slots` 里**本就不该出现**
 *（出现才是 bug：等于把"保持默认"写成一条冗余记录），但**套用后的有效值必须等于配方**。
 * 两个口径混用会写出"测试红了但画面是对的"这种假警报，所以这里显式分成两条断言。
 *
 * 工作目录 = `app/`（gradle 单测约定，与 [EditorStyleWhitelistTest] 同口径）⇒ 源码树路径
 * 直接写 `src/main/...`。
 */
class BuiltinPresetsTest {

    private val jsonText = File("src/main/assets/${BuiltinPresets.ASSET_PATH}").readText()

    private val presets = BuiltinPresets.parse(jsonText)

    private fun preset(id: String): EditorPreset =
        presets.firstOrNull { it.id == id } ?: error("内置包里没有 $id（实得 ${presets.map { it.id }}）")

    /**
     * 逐项断言一条预设的配方。
     *
     * @param expectedSlots 该条预设**应当**出现在稀疏 `slots` 里的槽（= 配方值 ≠ 恒等值的那些）。
     *   这条断言防的是"配方里多出一个没人知道的参数"（多加一个槽不会有任何编译期/运行期反馈）。
     * @param want 槽 → 配方值（架构师给定值，逐字断言）
     */
    private fun assertRecipe(id: String, expectedSlots: Set<Int>, vararg want: Pair<Int, Double>) {
        val p = preset(id)
        val neutral = CameraPresets.standard()
        val effective = presetSnapshot(neutral, p.slots)
        for ((slot, v) in want) {
            assertEquals("$id 槽[$slot] 套用后的有效值必须等于配方", v, effective[slot], 0.0)
            // 配方值 ≠ 恒等值 ⇒ 必须真的落在 slots 里，否则这条配方等于没写
            if (abs(v - neutral[slot]) > PARAM_EPS) {
                assertEquals(
                    "$id 槽[$slot] 与恒等值不同，必须进稀疏 slots",
                    v, p.slots[slot] ?: Double.NaN, 0.0,
                )
            } else {
                assertFalse(
                    "$id 槽[$slot] 等于恒等值（${neutral[slot]}），不该出现在稀疏 slots 里",
                    p.slots.containsKey(slot),
                )
            }
        }
        assertEquals("$id 的稀疏槽集合（配方里不该有没人知道的参数）", expectedSlots, p.slots.keys)
    }

    // ── ① 三条预设都能被解析 ───────────────────────────────────────────────

    @Test
    fun parsesExactlyThreeBuiltinPresets() {
        assertEquals("内置包必须恰好 3 条（首批）", 3, presets.size)
        assertEquals(
            "顺序 = JSON 顺序（UI 分组排序在 UI 层做）",
            listOf("builtin_leica_m9", "builtin_ricoh_negative", "builtin_leica_bw"),
            presets.map { it.id },
        )
        for (p in presets) {
            assertEquals("${p.id} 的 source", PRESET_SOURCE_BUILTIN, p.source)
            assertFalse("${p.id} 不该预置收藏", p.favorite)
            assertEquals("${p.id} 没有 createdAt（内置包不是用户创建的）", 0L, p.createdAt)
            assertTrue("${p.id} 必须有 note（列表里的一行小字）", p.note.isNotBlank())
            assertTrue("${p.id} 必须有分组", p.group.isNotBlank())
        }

        val m9 = preset("builtin_leica_m9")
        assertEquals("徕卡一瞬", m9.name)
        assertEquals("徕卡", m9.group)
        assertEquals("leica_m9", m9.lutId)

        val ricoh = preset("builtin_ricoh_negative")
        assertEquals("理光负片", ricoh.name)
        assertEquals("理光", ricoh.group)
        assertEquals("ricoh_negative", ricoh.lutId)

        val bw = preset("builtin_leica_bw")
        assertEquals("徕卡黑白", bw.name)
        assertEquals("徕卡", bw.group)
        assertEquals("leica_b_w_hc", bw.lutId)

        for (p in presets) {
            assertEquals("${p.id} 的 LUT 强度（内置包全量混合）", 1.0, p.lutStrength, 0.0)
        }

        // 报告用的逐槽打印（test-results XML 的 system-out 里可读）
        for (p in presets) {
            val eff = presetSnapshot(CameraPresets.standard(), p.slots)
            val dump = p.slots.keys.sorted().joinToString(", ") { "[$it]=${eff[it]}" }
            println("BUILTIN ${p.id} name=${p.name} group=${p.group} lut=${p.lutId} slots=${p.slots.size} :: $dump")
        }
    }

    /** [BuiltinPresets.ASSET_PATH] 必须与源码树里的真实资产一致（路径漂移 = 真机上永远加载不到）。 */
    @Test
    fun assetPathPointsAtAnExistingAsset() {
        assertEquals("presets/builtin.json", BuiltinPresets.ASSET_PATH)
        assertTrue(
            "资产必须存在：app/src/main/assets/${BuiltinPresets.ASSET_PATH}",
            File("src/main/assets/${BuiltinPresets.ASSET_PATH}").isFile,
        )
    }

    // ── ② 参数值逐项等于架构师配方（防"顺手调参"）─────────────────────────

    @Test
    fun leicaM9RecipeMatchesSpec() {
        // ISO 160（M9 基础感光度，CCD 低 ISO 噪点少）⇒ 细颗粒；M 系镜头暗角小 ⇒ −0.18；
        // 正片的"暖阴影 + 冷高光"分离 ⇒ split 0.10 / 0.05
        assertRecipe(
            "builtin_leica_m9",
            setOf(23, 24, 26, 27, 66, 67, 68),
            26 to 20.0,      // grain_amount（%）
            27 to 160.0,     // grain_size（ISO）
            66 to -0.18,     // vignette_strength（负 = 暗角）
            67 to 85.0,      // vignette_radius（%）
            68 to 55.0,      // vignette_feather（%）
            23 to 0.10,      // split_warm
            24 to 0.05,      // split_cool
        )
    }

    @Test
    fun ricohNegativeRecipeMatchesSpec() {
        // 负片典型 ISO 400 ⇒ 30%；GR 镜头暗角明显 ⇒ −0.28；负片的冷调阴影 ⇒ −0.10 / 0.10
        assertRecipe(
            "builtin_ricoh_negative",
            // ⚠ [27] 不在 slots 里：400 = 恒等值（保持默认，无需记录）
            setOf(23, 24, 26, 66, 67, 68),
            26 to 30.0,
            27 to 400.0,
            66 to -0.28,
            67 to 75.0,
            68 to 48.0,
            23 to -0.10,
            24 to 0.10,
        )
    }

    @Test
    fun leicaBwRecipeMatchesSpec() {
        // 黑白胶片（Tri-X / HP5）的银盐颗粒是标志 ⇒ 40% 最明显；ISO 400
        assertRecipe(
            "builtin_leica_bw",
            // ⚠ [27]=400 与 [67]=80 都不在 slots 里：两者都等于恒等值
            setOf(26, 66, 68),
            26 to 40.0,
            27 to 400.0,
            66 to -0.24,
            67 to 80.0,
            68 to 52.0,
        )
    }

    // ── ③ LUT id 真实存在（防"预设引用了不存在的 LUT"这条静默降级路径）────

    /**
     * 预设引用了不存在的 LUT ⇒ 运行时是**静默降级**（"LUT 已不在库中"，用户只看到效果不对）。
     * 这里在编译期数据上把它堵住：资产文件必须在，且能被**生产解析器** [CubeLutParser.parseBinary]
     * 整表解出（标题 + 33³ 数据完整 ⇒ 顺带覆盖"资产被截断"这种坏法），
     * category 按 `LutLibrary.headOf` 的同一口径（`[size][titleLen][title][catLen][category]`）解出。
     */
    @Test
    fun everyReferencedLutAssetExistsAndIsReadable() {
        val expectedTitle = mapOf(
            "leica_m9" to "徕卡 M9",
            "ricoh_negative" to "理光 负片",
            "leica_b_w_hc" to "徕卡 黑白 HC",
        )
        val expectedCategory = mapOf(
            "leica_m9" to "徕卡",
            "ricoh_negative" to "理光",
            "leica_b_w_hc" to "徕卡",
        )
        for (p in presets) {
            val lutId = p.lutId ?: error("${p.id} 没有 LUT 引用")
            val file = File("src/main/assets/luts/$lutId.lut")
            assertTrue("${p.id} 引用的 LUT 资产不存在：${file.path}", file.isFile)
            val bytes = file.readBytes()

            val lut = CubeLutParser.parseBinary(bytes, fallbackTitle = lutId)
            assertEquals("$lutId 的 TITLE", expectedTitle[lutId], lut.title)
            assertEquals("$lutId 的 LUT_3D_SIZE", 33, lut.size)

            val titleLen = bytes[1].toInt() and 0xFF
            val catLen = bytes[2 + titleLen].toInt() and 0xFF
            val category = String(bytes, 3 + titleLen, catLen, Charsets.UTF_8)
            assertEquals("$lutId 的 category", expectedCategory[lutId], category)

            println("LUT_OK id=$lutId title=${lut.title} category=$category size=${lut.size} bytes=${bytes.size}")
        }
    }

    // ── ④ 黑白预设不含 monochrome（反直觉决定，用测试钉死理由）────────────

    /**
     * `leica_b_w_hc` LUT **自带通道混合**（实测：纯绿 → 233 最亮、纯蓝 → 0 全黑、纯红 → 154
     * = 黑白胶片配黄绿滤镜的响应）。先 `[25] monochrome` 去色会把三通道抹成同一条灰阶 ⇒
     * LUT 的通道混合作用在灰上仍是灰 ⇒ 黄绿滤镜的响应被整段抹平，出来的是一张平淡灰照片。
     * 所以**故意不设** `[25]`，且这条口径要能挡住"顺手补上"。
     */
    @Test
    fun bwPresetHasNoMonochrome() {
        for (p in presets) {
            assertFalse(
                "${p.id} 不得带 [25] monochrome（黑白靠 LUT 的通道混合，去色会把它抹平）",
                p.slots.containsKey(FilmParamKeys.byName.getValue("monochrome")),
            )
        }
        assertFalse(
            "JSON 原文里也不得出现 monochrome 键（含被拒后看不出效果的写法）",
            jsonText.contains("\"monochrome\""),
        )
    }

    // ── ⑤ 所有参数名都在 FilmParamKeys.byName 里（防拼写错误）─────────────

    /**
     * `params` 的键是语义名；**未知键的失败模式是"整条预设被丢"**（[presetsFromJson] 第 2 档），
     * 而拼错一个字母不会有任何编译期反馈 —— 这里直接从**JSON 原文**取键集合（不是解析结果：
     * 被拒的键根本不会出现在解析结果里），逐个人 [FilmParamKeys.byName] 对账。
     */
    @Test
    fun everyParamNameInJsonIsKnown() {
        val arr = MiniJson.parse(jsonText) as List<*>
        val names = arr.flatMap { item ->
            val params = (item as Map<*, *>)["params"] as Map<*, *>
            params.keys.map { it as String }
        }
        assertEquals("三条预设的参数键总数", 7 + 7 + 5, names.size)
        for (name in names) {
            assertTrue(
                "未知参数名 \"$name\" —— 拼错会让**整条预设**被拒（静默少一条）",
                FilmParamKeys.byName.containsKey(name),
            )
        }
    }

    /** 内置包必须**零跳过**：任何一条 skipped 都是"内置包与当前代码版本不匹配"的信号。 */
    @Test
    fun builtinPackageParsesWithNoSkips() {
        val res = presetsFromJson(jsonText)
        assertEquals("内置包不得有被跳过的条目/槽：${res.skipped}", emptyList<String>(), res.skipped)
        assertEquals(3, res.presets.size)
    }

    // ── ⑥ 解析失败降级（坏 JSON ⇒ 空列表且不抛）──────────────────────────

    @Test
    fun badJsonDegradesToEmptyListWithoutThrowing() {
        val bad = listOf(
            "",                                                     // 空串
            "   \n  ",                                              // 全空白
            "{",                                                    // 截断
            "not json at all",                                      // 不是 JSON
            "{}",                                                   // 顶层不是数组
            "[]",                                                   // 合法但空
            "[{\"schema\":2,\"id\":\"a\"}]",                        // 缺 name ⇒ 整项拒绝
            "[{\"schema\":1,\"id\":\"a\",\"name\":\"x\"}]",         // 旧 schema ⇒ 整项拒绝
            "[{\"schema\":2,\"id\":\"a\",\"name\":\"x\",\"params\":{\"typo_key\":1.0}}]",  // 未知参数名
            "[{\"schema\":2,\"id\":\"a\",\"name\":\"x\",\"params\":[]}]",                  // params 不是对象
        )
        for (raw in bad) {
            val out = BuiltinPresets.parse(raw)
            assertTrue("坏输入 ${raw.take(40).ifEmpty { "<空>" }} 必须降级为空列表，实得 ${out.size} 条", out.isEmpty())
        }
    }
}
