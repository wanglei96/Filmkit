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

    /** 用户自建预设（合并顺序 / 去重 / 可删判据的测试输入：与内置包无关，故手工构造） */
    private fun userPreset(
        id: String,
        name: String = "用户预设",
        source: String = PRESET_SOURCE_USER,
    ) = EditorPreset(id = id, name = name, source = source)

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
        // 2026-10-25 徕卡一瞬一期：固件配方烘焙 LUT（tools/bake-m9-lut.py）
        assertEquals("leica_m9_ccd", m9.lutId)

        val ricoh = preset("builtin_ricoh_negative")
        assertEquals("理光负片", ricoh.name)
        assertEquals("理光", ricoh.group)
        assertEquals("ricoh_negative", ricoh.lutId)

        val bw = preset("builtin_leica_bw")
        assertEquals("徕卡黑白", bw.name)
        assertEquals("徕卡", bw.group)
        assertEquals("leica_b_w_hc", bw.lutId)

        // ★ R-3：白点提到 4.0（死白起点 3.55EV）+ 在 `--hi-floor 3.5` 约束下重拟合。
        //   R-1c 裁决 1（LUT 不许归零）的本意仍然成立：色彩特征只有 3D LUT 能表达。
        //   ★ 2026-10-25 徕卡一瞬一期：M9 换固件配方烘焙 LUT leica_m9_ccd —— 纯色彩
        //   矩阵（行和=1 中性恒等、影调归 filmic），满强度 = 配方原意；旧 leica_m9 的
        //   0.3085 是"影调斜率 1.19 与 filmic 抢职责、只能低强度混合"的旧账，不再适用。
        //   R-3 的代价见 `docs/reports/r3-white-point-refit-REPORT.md`（含残差取舍曲线与
        //   "不死白 vs 高光软肩"两套方案的数据）。
        assertEquals("builtin_leica_m9 的 LUT 强度（0.12.92：压高反光荧光，收到 0.82）", 0.82, preset("builtin_leica_m9").lutStrength, 0.0)
        assertEquals("builtin_ricoh_negative 的 LUT 强度（贴下限）", 0.3, preset("builtin_ricoh_negative").lutStrength, 0.0)
        assertEquals(
            "builtin_leica_bw 的 LUT 强度（R-3：从 1.0 降到 0.61 —— 全量会让高光更早到顶，" +
                "与死白起点约束冲突；黑白靠 LUT 的通道混合仍是这条预设的核心）",
            0.6123, preset("builtin_leica_bw").lutStrength, 0.0,
        )

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
        // ★ 0.12.90 三版（真机"艳俗/不油润"反馈）：白点 4.0 → 4.2 + 高光层次判据
        //   1.0 → 1.5 LSB（实测 8.80 LSB，修"高光发平"），黑点 −4.879 → −5.1725
        //   （暗部更透气），单链拍照残差 3.06 LSB。分区饱和按拟合器的色度实测
        //   （LUT 增益 1.53×）压到 暗 0.7 / 中 0.6 / 高 0.7；新增 sat_mid[21] /
        //   temp_mired[88] / fade[94]（暖调 + 褪色 = "油润厚重"的低对比层）。
        assertRecipe(
            "builtin_leica_m9",
            setOf(0, 4, 5, 6, 7, 8, 9, 10, 20, 21, 22, 23, 24, 26, 27, 37, 38, 66, 67, 68, 88, 94),
            0 to -0.3,       // ★ 0.12.92：M9 适度压暗（相对用户 EV 的偏移）
            4 to 1.1974,    // contrast（0.12.90 三版）
            5 to 0.0056, 6 to 0.0013, 7 to 0.1064,      // shoulder R/G/B
            8 to 0.2286, 9 to 0.1519, 10 to 0.7734,     // toe R/G/B（★ R-3 起与 shoulder **解耦**）
            20 to 0.76, 21 to 0.76, 22 to 0.65,         // 分区饱和（0.12.92：高光收 0.65）
            23 to 0.12, 24 to 0.04,     // 分离色调（暖调稍强）
            26 to 20.0,      // grain_amount（%）
            27 to 160.0,     // grain_size（ISO）
            37 to -5.1725, 38 to 4.2,                   // filmic 黑白点（★ 白点 4.0 → 4.2）
            66 to -0.21,     // vignette_strength（负 = 暗角，略强）
            67 to 85.0,      // vignette_radius（%）
            68 to 55.0,      // vignette_feather（%）
            88 to 1.5,       // temp_mired（+1.5 mired，轻微暖偏色）
            94 to 0.04,      // fade（提黑位，油润感）
        )
    }

    @Test
    fun ricohNegativeRecipeMatchesSpec() {
        // ★ R-3：白点 2.6138 → **4.0**（死白起点 2.45 → 3.55EV）。**这一条是三条里代价最大的**
        // （高光目标 251.9 本就超过拍照链路 246.53 的硬上限，白点提高后"不死白"与"高光够亮"
        // 直接冲突 ⇒ 残差 8.40/11.85 → 17.42/19.55，见 R-3 报告 §3）。
        assertRecipe(
            "builtin_ricoh_negative",
            // ⚠ [22]（sat_high = 1.0）与 [27]（400 = 恒等值）不在 slots 里：都等于恒等值
            setOf(4, 5, 6, 7, 8, 9, 10, 20, 21, 23, 24, 26, 37, 38, 66, 67, 68),
            4 to 1.2,
            5 to 0.0406, 6 to 0.0, 7 to 0.9906,    // shoulder（★ R-3 解耦后 R/G/B 各自独立）
            8 to 0.0, 9 to 0.0, 10 to 0.0,         // toe：贴 0（早压暗部，与"晚弯肩部"相反方向）
            20 to 0.85, 21 to 0.65, 22 to 1.0,     // sat：高光回恒等（1.0）
            23 to 0.41, 24 to 0.6,                 // 分离色调（b* 目标 −1.7）
            26 to 30.0,
            27 to 400.0,
            37 to -12.0, 38 to 4.0,                // ⚠ [37] 贴 −12 滑杆下界；[38] = 4.0
            66 to -0.28,
            67 to 75.0,
            68 to 48.0,
        )
    }

    @Test
    fun leicaBwRecipeMatchesSpec() {
        // ★ R-3：白点 2.5438 → **4.0**（死白起点 2.40 → 3.55EV）。目标曲线仍是**间接推断**
        // （该模式没有样片，用 M9 Contrast +2 代替）⇒ 可信度最低。颗粒仍是"黑白胶片的标志"
        // ⇒ 40% 不动；LUT 强度从 1.0 降到 0.6123（全量会让高光更早到顶，与死白约束冲突）。
        assertRecipe(
            "builtin_leica_bw",
            // ⚠ [27]=400 与 [67]=80 都不在 slots 里：两者都等于恒等值
            setOf(4, 5, 6, 7, 8, 9, 10, 26, 37, 38, 66, 68),
            4 to 1.3448,
            5 to 0.1928, 6 to 0.0449, 7 to 0.1985,
            8 to 0.311, 9 to 0.3028, 10 to 0.3148,
            26 to 40.0,
            27 to 400.0,
            37 to -5.0725, 38 to 4.0,
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
            // 2026-10-25 徕卡一瞬一期重标：固件配方烘焙 LUT（tools/bake-m9-lut.py）
            "leica_m9_ccd" to "徕卡 M9 CCD",
            "ricoh_negative" to "理光 负片",
            "leica_b_w_hc" to "徕卡 黑白 HC",
        )
        val expectedCategory = mapOf(
            "leica_m9" to "徕卡",
            "leica_m9_ccd" to "徕卡",
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
            // 33³ 为主；leica_m9_ccd 是首个 64³ 资产（调研 19 号定案：分支矩阵色彩
            // 映射在暖肤色/饱和区 64³ 更稳，引擎 MAX_SIZE=128 支持）
            assertEquals("$lutId 的 LUT_3D_SIZE", if (lutId == "leica_m9_ccd") 64 else 33, lut.size)

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
        assertEquals("三条预设的参数键总数（0.12.92 后：22 + 18 + 14）", 54, names.size)
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

    // ── ⑦ P-2c：三处列表的数据源（合并顺序 + 去重）与"内置不可删"的判据 ────────

    /**
     * [mergePresets] = `BuiltinPresets.all` 的**纯内核**（`all` 自己只多一层 Android 的 assets 读，
     * 三处 UI 走的是同一个 `all`）。顺序与去重都不是洁癖：
     *  - **顺序**（用户在前 + 内置在后）= 数据层给 UI 的稳定顺序，用户自己存的更常用；
     *  - **去重**：id 是 UI 的**唯一判据** —— 列表 `key = { it.id }`（Compose 的 `LazyColumn`
     *    遇到重复 key **直接抛异常**）、选中态比对 id、`initialPresetOf` 按 id 取第一条。
     *    而 prefs 反序列化出来的 `id` 字段**不受任何格式约束**（将来还有导入功能）⇒ 撞车是可达的。
     */
    @Test
    fun mergePutsUserFirstAndKeepsIdsUnique() {
        val user = listOf(userPreset("p_1_a", "我的暖调"), userPreset("p_2_b", "我的冷调"))
        val merged = mergePresets(user, presets)
        assertEquals(
            "用户预设在前 + 内置三条在后（顺序 = UI 呈现顺序，UI 不再排一次）",
            listOf("p_1_a", "p_2_b") + presets.map { it.id },
            merged.map { it.id },
        )

        // 同 id 撞车：只留**用户那份**（内置的被丢掉）
        val clash = mergePresets(user, listOf(userPreset("p_1_a", "内置同 id", PRESET_SOURCE_BUILTIN)) + presets)
        assertEquals("同 id 不得出现两次（LazyColumn 的 key 会崩）", clash.size, clash.map { it.id }.distinct().size)
        assertEquals("同 id 时留下的是用户那份（用户数据优先）", "我的暖调", clash.first { it.id == "p_1_a" }.name)
        assertEquals(
            "总数 = 用户 2 + 内置（1 条伪造同 id + 真内置 3）− 重复 1",
            5,
            clash.size,
        )

        // 内置包内部重复（资产文件写重了）同样收敛成一条 —— 去重不能只对"用户 vs 内置"生效
        val selfDup = mergePresets(emptyList(), presets + presets)
        assertEquals("内置包内部重复也要收敛", presets.size, selfDup.size)

        // 内置包坏掉（load 降级为空列表）时，用户预设一条不少、顺序不变
        assertEquals("内置包不可用不能连累用户数据", user, mergePresets(user, emptyList()))
    }

    /**
     * **内置预设不可删**（P-2c 任务 2）：判据 = [EditorPreset.source]（[presetDeletable] 是唯一
     * 实现，编辑器预设面板按它决定是否渲染删除按钮）。这里钉住判据的两端：
     *  - 内置包每一条都**必须**标 [PRESET_SOURCE_BUILTIN] —— 漏标一条，面板就会给它渲染删除按钮，
     *    而删掉下次启动又回来（"删了个假的"）；
     *  - 用户自建预设**必须**仍可删（行为不变，本卡不许把它一起保护掉）。
     */
    @Test
    fun builtinPresetsAreNotDeletable() {
        for (p in presets) {
            assertEquals("${p.id} 的 source（保护逻辑的唯一判据）", PRESET_SOURCE_BUILTIN, p.source)
            assertFalse("内置预设不得可删：${p.id}", presetDeletable(p))
        }
        assertTrue("用户预设必须仍可删（本卡不改变用户预设行为）", presetDeletable(userPreset("p_1_a")))
        assertFalse("「标准」也是内置条目（拍照页基线，不是可删对象）", presetDeletable(CAMERA_STANDARD_PRESET))
        // 从外部文件导入的预设（E3-2 的 .fkpreset）不在本卡范围，但它同样不是内置 ⇒ 可删
        assertTrue("imported 预设不属于内置保护范围", presetDeletable(userPreset("i_1_a", source = PRESET_SOURCE_IMPORTED)))
    }
}
