package com.filmkit.app.ui

import com.filmkit.app.camera.BuiltinPresets
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.PRESET_SOURCE_BUILTIN
import com.filmkit.app.camera.PRESET_SOURCE_USER
import com.filmkit.app.camera.mergePresets
import com.filmkit.app.camera.presetDeletable
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ PS-2b（用户 2026-10-11 第二条澄清）**编辑器（相册侧）预设面板的护栏**。
 *
 * 用户原话："**编辑器可以保存当前配置为预设，不能修改已保存的预设，可以删除**"。
 * 这份测试盯的就是这条规则表**在编辑器侧**是否成立（拍照界面是另一张卡 PS-2a 的地盘）：
 *
 * | 操作 | 编辑器侧 | 本文件里的哨兵 |
 * |---|---|---|
 * | 看配置（只读详情） | ✅ 保留 | ⑤ 摘要 / 详情读数 |
 * | 新增（保存当前为新预设） | ✅ 保留 | ③ `presetEditorButtons(isNew = true)` + 源码正向哨兵 |
 * | **修改已保存的预设** | ❌ **不存在** | ① [editorPresetActions] 清单 + ② 源码哨兵 |
 * | 删除 | ✅ 保留 | ④ `presetDeletable` + `mergePresets` 不丢内置 |
 * | 套用 + 强度滑杆 | ✅ 保留 | ① 清单里的 APPLY + 源码正向哨兵 |
 *
 * **为什么用源码哨兵**：`EditorScreen.kt` 是 Compose 文件，JVM 单测跑不了 Composable。
 * 而"编辑器里多出一个能改预设库的按钮"这件事**不会编译失败、不会崩**，只会在用户手上变成
 * 一次误操作 —— 靠人记得"别把 `updatePreset` 加回来"是不可靠的。所以：
 *  - 纯逻辑层：清单 [editorPresetActions] 是"编辑器能做什么"的**唯一维护点**（UI 从它渲染）；
 *  - 源码层：`EditorScreen.kt` 里不许出现"把已有预设写回库"的标识符与 UI 文案。
 *
 * 真实数据来源 = `app/src/main/assets/presets/builtin.json`（工作目录 = `app/`，与
 * [PresetDetailTest] / [com.filmkit.app.camera.BuiltinPresetsTest] 同一口径）。
 */
class PresetPanelUiTest {

    private val builtins = BuiltinPresets.parse(File("src/main/assets/presets/builtin.json").readText())
    private val editorSrc = File("src/main/java/com/filmkit/app/ui/EditorScreen.kt").readText()

    private fun builtin(name: String): EditorPreset =
        builtins.firstOrNull { it.name == name } ?: error("内置包里没有「$name」（实得 ${builtins.map { it.name }}）")

    private fun userPreset(id: String, name: String = "我的预设 1") = EditorPreset(id = id, name = name)

    // ── ① 编辑器侧动作清单：恰好 套用 / 查看配置 / 删除（PS-2b 的核心护栏）────────

    /**
     * 编辑器侧对一条预设能做的只有三件事，**顺序 = 渲染顺序**。
     *
     * 断言"恰好三项"是刻意的：把 `编辑` / `重命名` / `复制` / `更新为当前设置` 里任何一项
     * 加回清单 ⇒ 条数或动作集合当场变 ⇒ 这条红。
     */
    @Test
    fun editorActionsAreExactlyApplyViewDelete() {
        val items = editorPresetActions(userPreset("p_1_a"), paramsReady = true)
        assertEquals(
            "编辑器侧的动作面（PS-2b：不能修改已保存的预设）",
            listOf(PresetAction.APPLY, PresetAction.VIEW, PresetAction.DELETE),
            items.map { it.action },
        )
        assertEquals(listOf("套用", "查看配置", "删除"), items.map { it.label })
        assertTrue("用户预设 + 参数就绪 ⇒ 三项都该可点", items.all { it.enabled })
    }

    /**
     * **修改类动作对任何来源的预设都不存在**（内置 / 用户 / 导入都一样）。
     *
     * 这条是"编辑器不能修改已保存的预设"的直接翻译：可改性不是"来源"的函数 —— 编辑器侧
     * 压根没有那条路径（修改在拍照界面做）。
     */
    @Test
    fun editorActionsHaveNoModifyActionForAnyPreset() {
        val modify = listOf(PresetAction.EDIT, PresetAction.RENAME, PresetAction.COPY)
        val samples = builtins + listOf(
            userPreset("p_1_a"),
            EditorPreset(id = "i_1_a", name = "导入的", source = "imported"),
        )
        for (p in samples) {
            for (ready in listOf(true, false)) {
                val actions = editorPresetActions(p, ready).map { it.action }
                for (a in modify) {
                    assertFalse("编辑器侧不许有「$a」这类「改已保存预设」的动作：${p.name}", a in actions)
                }
                assertEquals("动作面恒为三项（与来源 / 参数就绪无关）：${p.name}", 3, actions.size)
            }
        }
    }

    /** 参数没就绪时**只有套用**置灰：看配置与删除不需要当前参数，跟着灰就是无谓地砍掉能干的事。 */
    @Test
    fun editorApplyIsGatedByParamsReadyButViewAndDeleteAreNot() {
        val items = editorPresetActions(userPreset("p_1_a"), paramsReady = false).associateBy { it.action }
        assertFalse("套用要写当前参数 ⇒ 未就绪时置灰", items.getValue(PresetAction.APPLY).enabled)
        assertTrue("查看配置不需要当前参数 ⇒ 仍可点", items.getValue(PresetAction.VIEW).enabled)
        assertTrue("删除不需要当前参数 ⇒ 仍可点", items.getValue(PresetAction.DELETE).enabled)
    }

    /** 删除的可用性必须与 [presetDeletable] 逐条对齐（内置只读的唯一实现，P-2c 立的规矩）。 */
    @Test
    fun editorDeleteFollowsPresetDeletable() {
        for (p in builtins) {
            assertFalse(
                "内置预设不可删（判据 presetDeletable）：${p.name}",
                editorPresetActions(p, paramsReady = true).first { it.action == PresetAction.DELETE }.enabled,
            )
        }
        for (p in listOf(userPreset("p_1_a"), EditorPreset(id = "i_1_a", name = "导入的", source = "imported"))) {
            assertEquals(
                "${p.name}（${p.source}）的删除可用性必须跟着 presetDeletable",
                presetDeletable(p),
                editorPresetActions(p, paramsReady = true).first { it.action == PresetAction.DELETE }.enabled,
            )
        }
    }

    // ── ② 源码哨兵：编辑器里没有"改已保存预设"的路径（mutation 的靶子）──────────

    /**
     * `EditorScreen.kt` 里**不许出现**"改已保存的预设"的实现 / 接线 / UI 文案。
     *
     * 逐项对应被撤除的三条路径（U-6a 的 `updatePreset`、PS-1 的 `renamePreset` / `copyPreset`）
     * 与它们的编辑面板：只要有人把入口加回来（哪怕只是接线或一个按钮文案），这条立刻红 ——
     * 这正是本卡"mutation 自检"要证明的事。
     */
    @Test
    fun editorSourceHasNoModifyPresetPath() {
        val forbidden = listOf(
            // 三条"把已有预设写回库"的实现
            "fun updatePreset(",
            "fun renamePreset(",
            "fun copyPreset(",
            // 编辑态面板与它的接线点
            "PresetEditorPanel(",
            "onUpdate =",
            "onRename =",
            "onCopy =",
            // 界面上曾经出现过、能点进"改预设"的文案
            "\"更新为当前设置\"",
            "\"从当前参数更新\"",
            "\"保存名称 / 分组\"",
            "\"编辑预设\"",
            "\"重命名\"",
            "\"复制\"",
        )
        for (t in forbidden) {
            assertFalse("编辑器侧不许出现「$t」（PS-2b：编辑器不能修改已保存的预设）", editorSrc.contains(t))
        }
    }

    /**
     * **正向哨兵**：撤"修改"时不许顺手把"看 / 套用 / 新增 / 删除"一起撤掉。
     *
     * 用户同一句话里也说了"可以保存当前配置为预设，可以删除" —— 这条盯的就是那两件事，
     * 外加套用（含强度滑杆）与只读详情（看配置）。
     */
    @Test
    fun editorSourceStillHasCreateDeleteApplyAndReadOnlyDetail() {
        val required = listOf(
            // 新增（保存当前为新预设）
            "fun savePreset(",
            "PresetStore.upsert(",
            "private fun PresetNewPanel(",
            "\"+ 新建预设\"",
            // 删除（二次确认 + 内置只读）
            "fun deletePreset(",
            "PresetStore.delete(",
            "\"删除预设？\"",
            // 套用 + 强度滑杆
            "fun applyPreset(",
            "fun onPresetAmountRelease(",
            "PRESET_AMOUNT_UI_RANGE",
            // 只读详情（看配置）
            "private fun PresetDetailPanel(",
            "private fun PresetParamList(",
            // 动作清单与引导文案（本卡的两处新单一来源）
            "editorPresetActions(",
            "editorPresetManageHint()",
        )
        for (t in required) {
            assertTrue("编辑器侧必须有「$t」（PS-2b 保留：看 / 套用 / 新增 / 删除）", editorSrc.contains(t))
        }
    }

    /**
     * **源码哨兵**：`EditorScreen.kt` 里每一条 `presets = …` 刷新都必须带上内置包 ——
     * 要么 `mergePresets(..., BuiltinPresets.load(context))`（写路径），要么
     * `BuiltinPresets.all(context, prefs)`（打开面板）。
     *
     * 为什么值得用正则读源码：这个坑（只赋 `PresetStore` 的返回值 ⇒ 内置三条当场消失）
     * 已经犯过一次（P-2c），而它**不会编译失败、不会崩**，只在界面上少三条 ——
     * 靠人记得在每条新路径上写 `mergePresets` 是不可靠的。
     *
     * ★ PS-2b：写路径从五条（保存 / 更新 / 改名 / 复制 / 删除）收窄到**两条**
     *（新增 + 删除）⇒ 条数下限跟着改；若将来有人把赋值写成跨行，条数会掉到 3 以下 ⇒ 红
     *（哨兵失效必须自己喊出来，而不是静默放过）。
     */
    @Test
    fun everyPresetListRefreshCarriesTheBuiltinPackage() {
        val assigns = Regex("""(?m)^\s*presets = .*\(.*$""").findAll(editorSrc).map { it.value.trim() }.toList()
        assertTrue(
            "只解析到 ${assigns.size} 条 presets = … ⇒ 正则或源码结构变了（本哨兵已失效）",
            assigns.size >= 3,
        )
        for (a in assigns) {
            val ok = a.contains("BuiltinPresets.all(") ||
                (a.contains("mergePresets(") && a.contains("BuiltinPresets.load("))
            assertTrue("列表刷新必须带内置包（否则内置三条当场消失）：$a", ok)
        }
        // 反向哨兵：两条写路径（新增 / 删除）都在
        for (fn in listOf("PresetStore.upsert", "PresetStore.delete")) {
            assertTrue("$fn 的调用点消失了 ⇒ 本哨兵覆盖的写路径变了，需同步更新", editorSrc.contains(fn))
        }
    }

    // ── ③ 新增（保存当前为新预设）────────────────────────────────────────────

    /** 新建态只有「保存当前参数为新预设」：没有"改已有预设"的三个动作（那是拍照界面的）。 */
    @Test
    fun newPresetPanelOffersSaveNewOnly() {
        val b = presetEditorButtons(isNew = true, preset = null, paramsReady = true, name = "我的预设 1")
        assertTrue("新建态：「保存当前参数为新预设」可点", b.saveNew)
        assertFalse("新建态没有「更新 / 改名 / 删除」这三件事", b.updateFromCurrent || b.saveMeta || b.delete)
        // 置灰（不是隐藏）：名称为空 / 参数没就绪
        assertFalse("名称为空 ⇒ 保存置灰", presetEditorButtons(true, null, true, "   ").saveNew)
        assertFalse("参数没就绪 ⇒ 保存置灰", presetEditorButtons(true, null, false, "x").saveNew)
    }

    /**
     * 新建预设的默认名：`我的预设 N`，N = 第一个不与现有预设重名的编号。
     *
     * 默认名让"新建 → 保存"一路可点（PS-1 的投诉之一就是"要先填名称才可点"这件事没人告诉他）。
     */
    @Test
    fun defaultNameIsPrefilledAndNeverCollides() {
        assertEquals("我的预设 1", defaultPresetName(emptyList()))
        assertEquals("我的预设 1", defaultPresetName(builtins))          // 内置三条不占这个名字
        assertEquals("我的预设 2", defaultPresetName(listOf(userPreset("a", "我的预设 1"))))
        assertEquals(
            "跳过被占用的编号（不是「数量 + 1」，删过一条后不能撞名）",
            "我的预设 3",
            defaultPresetName(listOf(userPreset("a", "我的预设 1"), userPreset("b", "我的预设 2"))),
        )
        assertEquals("我的预设 2", defaultPresetName(listOf(userPreset("a", "我的预设 1"), userPreset("b", "我的预设 3"))))
        // 预填的名字必须让"保存"可点（否则又回到"要先填名称"那条投诉）
        assertTrue(presetEditorButtons(true, null, true, defaultPresetName(emptyList())).saveNew)
    }

    // ── ④ 删除：用户预设可删、内置只读、删完内置三条不消失 ──────────────────────

    /**
     * 合并口径：删掉一条用户预设后，**内置三条必须仍在列表里**。
     *
     * `PresetStore` 需要 `SharedPreferences`（JVM 单测里不可用）⇒ 这里钉的是"删一条用户预设"
     * 这一步的纯逻辑语义（按 id 过滤用户库）与随后的 [mergePresets]。真正的"忘了 merge"是
     * 调用侧的错误，由上面那条源码哨兵盯。
     */
    @Test
    fun deletingAUserPresetKeepsAllBuiltins() {
        val users = listOf(userPreset("p_1_a", "我的预设 1"), userPreset("p_2_b", "我的预设 2"))
        val before = mergePresets(users, builtins)
        assertEquals(5, before.size)

        val after = mergePresets(users.filterNot { it.id == "p_1_a" }, builtins)
        assertEquals("删一条用户预设 ⇒ 只剩另一条 + 三条内置", 4, after.size)
        assertEquals(
            "内置三条一条都不能少（P-2c 踩过的坑）",
            listOf("builtin_leica_m9", "builtin_ricoh_negative", "builtin_leica_bw"),
            after.filter { it.source == PRESET_SOURCE_BUILTIN }.map { it.id },
        )
        // 用户预设的删除**只影响用户那部分**（顺序也保持不变：用户在前 + 内置在后）
        assertEquals(listOf("p_2_b", "builtin_leica_m9", "builtin_ricoh_negative", "builtin_leica_bw"), after.map { it.id })
    }

    /** 用户预设的既有语义没被改动（PS-2b 不该回退 P-2c 的用户侧行为）。 */
    @Test
    fun userPresetSourceSemanticsUnchanged() {
        val p = EditorPreset(id = "p_1_a", name = "我的", source = PRESET_SOURCE_USER)
        assertTrue("用户预设仍可删", presetDeletable(p))
        assertTrue("用户预设的删除仍可点", editorPresetActions(p, true).first { it.action == PresetAction.DELETE }.enabled)
    }

    // ── ⑤ 看配置：参数摘要 / 详情读数（编辑器侧保留的只读能力）──────────────────

    /**
     * 「徕卡一瞬」的摘要**逐字**（真实 `builtin.json`）。
     *
     * 挑选策略 = 分组轮转（每组先取第一项）⇒ 前四项分别来自基础调整 / 色彩 / 影调曲线 /
     * 胶片质感，用户最认的"颗粒 · 强度 20"不会被槽位顺序挤掉；17 项参数 ⇒ 必然带「…」；
     * LUT 行在最后（强度 0.3 ⇒ 读数 30，与 LUT 强度滑杆同一口径）。
     */
    @Test
    fun summaryOfLeicaMomentIsExact() {
        val p = builtin("徕卡一瞬")
        assertEquals(21, p.slots.size)  // 0.12.90：新增 sat_mid / temp_mired / fade
        assertEquals(
            // 0.12.91：大晴天"不够浓"回归（暗 -22/中 -20/高 -18）、暖调、LUT 95
            "对比度 +20 · 饱和度 · 暗部 -22 · 胶片曲线 · 肩部 红 0.01 · 颗粒 · 强度 20 · … · LUT leica_m9_ccd 95",
            presetSummaryText(p),
        )
        // UI 层查到中文名 ⇒ 用中文名（纯逻辑层不碰 Context，所以由调用侧传进来）
        assertTrue(
            "LUT 中文名要能替换掉 id",
            presetSummaryText(p, lutLabel = "徕卡 M9 CCD").endsWith("LUT 徕卡 M9 CCD 95"),
        )
    }

    /** 摘要必须**覆盖到每一个分组**（轮转的价值：不许出现"四个位置全被基础调整占满"）。 */
    @Test
    fun summaryCoversEverySectionOfEveryBuiltin() {
        for (p in builtins) {
            val parts = presetSummaryParts(p)
            assertTrue("${p.name} 的摘要不该为空", parts.isNotEmpty())
            val sections = presetDetailSections(p.slots).map { it.title }
            assertTrue("${p.name} 分组数应 ≥ 2（轮转才有意义）", sections.size >= 2)
            // 每组的第一行都必须出现在摘要里（4 个位置 ≥ 组数）
            val firstOfEach = presetDetailSections(p.slots).map { sec -> "${sec.rows.first().label} ${sec.rows.first().value}" }
            for (head in firstOfEach) {
                assertTrue("${p.name} 的摘要漏掉了分组首项「$head」：${parts.joinToString(" · ")}", head in parts)
            }
        }
    }

    /** 「…」只在**真的截断**时出现；短预设不许带省略号。 */
    @Test
    fun ellipsisAppearsOnlyWhenTruncated() {
        val short = EditorPreset(id = "p", name = "短", slots = mapOf(26 to 20.0, 66 to -0.18))
        assertEquals(listOf("颗粒 · 强度 20", "暗角 · 强度 18"), presetSummaryParts(short))
        assertFalse("两项没超过上限 ⇒ 不许有省略号", presetSummaryText(short).contains("…"))
        // 上限之内的边界：正好 4 项
        val four = EditorPreset(
            id = "p", name = "四",
            slots = mapOf(26 to 20.0, 66 to -0.18, 27 to 160.0, 67 to 85.0),
        )
        assertEquals(4, presetSummaryParts(four).size)
        assertFalse(presetSummaryText(four).contains("…"))
        // 第 5 项 ⇒ 截断
        val five = EditorPreset(
            id = "p", name = "五",
            slots = mapOf(26 to 20.0, 66 to -0.18, 27 to 160.0, 67 to 85.0, 68 to 55.0),
        )
        assertEquals(PRESET_SUMMARY_MAX + 1, presetSummaryParts(five).size)
        assertTrue(presetSummaryText(five).endsWith("…"))
    }

    /** 空预设：摘要为空串（UI 显示"不含参数"），不是 null / 不是 "…"。 */
    @Test
    fun emptyPresetHasEmptySummary() {
        assertEquals("", presetSummaryText(EditorPreset(id = "p", name = "空")))
        assertTrue(presetSummaryParts(EditorPreset(id = "p", name = "空")).isEmpty())
    }

    /**
     * 摘要里的读数必须与**详情页逐字相同**（唯一维护点 = [presetDetailSections]）：
     * 摘要只负责"挑哪几项"，任何一处自己格式化数字都会让这条红。
     */
    @Test
    fun summaryReadoutsAreIdenticalToDetailRows() {
        for (p in builtins) {
            val rows = presetDetailSections(p.slots).flatMap { it.rows }.associateBy { "${it.label} ${it.value}" }
            for (part in presetSummaryParts(p)) {
                if (part == "…" || part.startsWith("LUT ")) continue
                assertNotNull("摘要项「$part」在详情里找不到同名读数（格式漂移）", rows[part])
            }
        }
    }

    // ── ⑥ 引导文案：告诉用户"改预设去哪"（不然又是一次"找不到入口"的投诉）──────

    /**
     * 编辑器不能改已保存的预设 ⇒ 界面上必须说清楚"去哪改"。
     *
     * 断言三件事：指到**拍照界面**、点明是**修改**这一类在那边、同时说清**新增 / 删除在这里**。
     */
    @Test
    fun manageHintPointsToCameraScreen() {
        val hint = editorPresetManageHint()
        assertTrue("必须告诉用户去哪改：$hint", hint.contains("拍照界面"))
        assertTrue("必须点明'修改'这一类在那边：$hint", hint.contains("修改"))
        assertTrue("也必须说清新增 / 删除在这里能做：$hint", hint.contains("新增") && hint.contains("删除"))
        assertTrue("界面真的渲染了这句（不是只定义不显示）", editorSrc.contains("editorPresetManageHint()"))
    }

    // ── ⑦ 拍照界面（PS-2a）复用的纯逻辑：**不是**编辑器侧的动作面 ───────────────

    /**
     * [presetMenuItems] = **拍照界面**的完整动作集（六项，含"改已保存预设"的三个）——
     * 内置预设的受限项**置灰但可见**（PS-1 的纪律：隐藏才是投诉的根因）。
     *
     * ⚠ 这份清单**不属于编辑器**（编辑器侧用 [editorPresetActions]，见 ①）：放在这里只是
     * 因为 PS-1 立的这些哨兵保护的是同一批纯逻辑函数，PS-2a 复用它们时不该被静默改坏。
     */
    @Test
    fun cameraMenuStillHasTheModifyActionsAndBuiltinOnesAreGreyedOut() {
        val p = builtin("徕卡一瞬")
        val items = presetMenuItems(p, paramsReady = true)
        assertEquals(
            "拍照界面的动作集与顺序（主操作在前、危险操作在后）",
            listOf("套用", "查看配置", "编辑", "重命名", "复制", "恢复默认", "删除"),
            items.map { it.label },
        )
        assertEquals(
            "★ PS-3（用户：「内置预设为什么不让修改？…最多限制不能删除就行」）：内置**只有删除**置灰",
            listOf(true, true, true, true, true, true, false),
            items.map { it.enabled },
        )
        val note = presetReadOnlyNote(p)
        assertNotNull("内置预设必须给一句实话", note)
        assertTrue("实话里要写清「唯一限制是不能删」：$note", note!!.contains("不能删除") && note.contains("恢复默认"))
        // 用户预设：除「恢复默认」外全可用（没有出厂定义可回退）；没有"只读"这回事
        val user = presetMenuItems(userPreset("p_1_a"), paramsReady = true).associateBy { it.action }
        assertTrue(
            "用户预设除「恢复默认」外都该可点",
            user.filterKeys { it != PresetAction.RESTORE }.values.all { it.enabled },
        )
        assertFalse("用户预设没有出厂定义 ⇒ 「恢复默认」置灰", user.getValue(PresetAction.RESTORE).enabled)
        assertNull("用户预设 ⇒ 菜单不占那行", presetReadOnlyNote(userPreset("p_1_a")))
        // 参数没就绪：只有"需要当前参数"的两项置灰
        val gated = presetMenuItems(userPreset("p_1_a"), paramsReady = false).associateBy { it.action }
        assertFalse(gated.getValue(PresetAction.APPLY).enabled)
        assertFalse(gated.getValue(PresetAction.EDIT).enabled)
        assertFalse(gated.getValue(PresetAction.RENAME).enabled)
        assertTrue(gated.getValue(PresetAction.VIEW).enabled)
        assertTrue(gated.getValue(PresetAction.COPY).enabled)
        assertTrue(gated.getValue(PresetAction.DELETE).enabled)
    }

    /** 拍照界面编辑态的按钮判据（PS-2a 复用）：用户预设三件事可点，新建态不出现。 */
    @Test
    fun cameraEditPanelButtonsCoverUpdateRenameDelete() {
        val p = userPreset("p_1_a")
        val b = presetEditorButtons(isNew = false, preset = p, paramsReady = true, name = "新名字")
        assertTrue("编辑态：「从当前参数更新」可点", b.updateFromCurrent)
        assertTrue("编辑态：「保存名称 / 分组」可点", b.saveMeta)
        assertTrue("编辑态：「删除」可点（二次确认仍在面板里）", b.delete)
        assertFalse("编辑态不该出现「另存为新预设」", b.saveNew)
        assertFalse("名称为空 ⇒ 保存名称置灰", presetEditorButtons(false, p, true, "").saveMeta)
        assertFalse("参数没就绪 ⇒ 更新置灰", presetEditorButtons(false, p, false, "n").updateFromCurrent)
        assertFalse(
            "内置预设 ⇒ 删除置灰（判据 presetDeletable）",
            presetEditorButtons(false, builtin("理光负片"), true, "n").delete,
        )
    }
}
