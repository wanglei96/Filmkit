package com.filmkit.app.ui

import com.filmkit.app.camera.BuiltinPresets
import com.filmkit.app.camera.CAMERA_STANDARD_PRESET
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
 * ★ PS-2a 拍照界面预设管理（用户 2026-10-11 **方向纠正**："预设的配置 / 新增 / 编辑 / 删除
 * **我指的是拍照界面的预设**。这个**不能让用户在相册里面改这些配置**。"）的纯逻辑护栏。
 *
 * 这份测试盯的是**"能力真的在拍照界面这一层、且没有把既有能力做丢"**：
 *  1. 「⋯」菜单里内置预设的受限项**置灰但在清单里** + 一句实话（`presetMenuItems` /
 *     `presetReadOnlyNote` —— 隐藏正是用户"没法删"投诉的根因）；
 *  2. **参数摘要**用真实 `builtin.json` 给出**逐字**输出（"这条预设包含什么"的行内答案）；
 *  3. **新建的稀疏槽集合不含 `[0]` 曝光**（U-7 的"拍摄态不入库"纪律，[cameraPresetSlots]）；
 *  4. 新建 / 更新 / 复制三条构造路径的**身份纪律**（id / 名称 / 来源 / 配方各归各位）；
 *  5. 编辑面板按钮差异（含**内置只读修正**：`presetEditorButtons` 在编辑态没查可删性 ⇒
 *     不修正就是一个点了没反应的假按钮）；
 *  6. **删除后内置三条不消失**（`mergePresets` 语义 + `CameraScreen.kt` 的源码哨兵 ——
 *     P-2c 踩过的坑：只赋 `PresetStore` 的返回值会让内置三条当场消失）。
 *
 * 真实数据来源 = `app/src/main/assets/presets/builtin.json`（工作目录 = `app/`，与
 * [PresetPanelUiTest] 同一口径）—— 用真实内置预设断言，而不是手搓一份"想象中的预设"。
 */
class CameraPresetPanelUiTest {

    private val builtins = BuiltinPresets.parse(File("src/main/assets/presets/builtin.json").readText())

    private fun builtin(name: String): EditorPreset =
        builtins.firstOrNull { it.name == name } ?: error("内置包里没有「$name」（实得 ${builtins.map { it.name }}）")

    private fun userPreset(id: String, name: String = "我的预设 1") = EditorPreset(id = id, name = name)

    /** 当前相机参数（拍照界面口径：中性基线 + 当前预设的稀疏覆盖，见 `cameraPresetParams`）。 */
    private fun cameraParams(vararg overrides: Pair<Int, Double>): DoubleArray =
        CameraPresets.standard().also { a -> overrides.forEach { (i, v) -> a[i] = v } }

    // ── ① 「⋯」菜单可用性：内置 vs 用户（验收判据 2）──────────────────────────

    /**
     * 内置预设：`套用` / `查看配置` 可用，`编辑 / 重命名 / 复制 / 删除` **置灰但必须在清单里**。
     *
     * 断言 `size == 6` 是刻意的：投诉的根因就是"把不可用的项藏起来"（旧版对内置**根本不渲染**
     * 删除按钮），任何"过滤掉灰项"的改动都必须让这条测试红。
     */
    @Test
    fun builtinMenuKeepsDisabledItemsVisible() {
        val p = builtin("徕卡一瞬")
        val items = presetMenuItems(p, paramsReady = true)
        assertEquals(
            "菜单项与顺序（主操作在前、危险操作在后）",
            listOf("套用", "查看配置", "编辑", "重命名", "复制", "删除"),
            items.map { it.label },
        )
        assertEquals(
            "内置预设：只有套用 / 查看配置可点，其余**置灰但可见**（不许隐藏）",
            listOf(true, true, false, false, false, false),
            items.map { it.enabled },
        )
        val note = presetReadOnlyNote(p)
        assertNotNull("内置预设必须给一句实话（用户要知道为什么灰）", note)
        assertTrue("实话要写清哪些不能做：$note", note!!.contains("只读") && note.contains("删除"))
    }

    /** 用户预设：六项全可用；没有"只读"这回事 ⇒ 菜单不占那行。 */
    @Test
    fun userMenuIsFullyEnabled() {
        val p = userPreset("p_1_a")
        assertTrue("用户预设六项都该可点", presetMenuItems(p, paramsReady = true).all { it.enabled })
        assertNull("用户预设不该有只读提示", presetReadOnlyNote(p))
    }

    // ── ② 参数摘要：真实内置预设的**实际输出**（验收判据 3）────────────────────

    /**
     * 「徕卡一瞬」逐字（17 项参数 ⇒ 分组轮转取 4 项 + 「…」+ LUT 行）。
     *
     * 挑选策略 = 分组轮转（每组先取第一项）⇒ 前四项分别来自基础调整 / 色彩 / 影调曲线 /
     * 胶片质感，用户最认的"颗粒 · 强度 20"不会被槽位顺序挤掉。
     */
    @Test
    fun summaryOfLeicaMomentIsExact() {
        val p = builtin("徕卡一瞬")
        assertEquals(17, p.slots.size)
        assertEquals(
            "对比度 -3 · 饱和度 · 中间调 +5 · 胶片曲线 · 肩部 红 0.34 · 颗粒 · 强度 20 · … · LUT leica_m9 30",
            presetSummaryText(p),
        )
        // UI 层查到中文名 ⇒ 用中文名（纯逻辑层不碰 Context，所以由调用侧传进来）
        assertTrue(
            "LUT 中文名要能替换掉 id",
            presetSummaryText(p, lutLabel = "徕卡 M9").endsWith("LUT 徕卡 M9 30"),
        )
    }

    /**
     * 「理光负片」逐字：低对比 + 黄绿调 + 中颗粒。
     *
     * ⚠ `slots.size = 14` 而 JSON 里有 15 个键 —— 少的那个是 `grain_size: 400`，它**恰好等于
     * 恒等值** `CameraPresets.standard()[27] = 400`（颗粒尺寸的标定起点）⇒ 被解析器的
     * "恒等值不入库"那道洗掉（`parsePreset` 的 `abs(d - neutral[idx]) <= PARAM_EPS`）。
     * 这不是丢数据：等于恒等值 = 套用时保持当前值，与"记下来"逐位等价。
     */
    @Test
    fun summaryOfRicohNegativeIsExact() {
        val p = builtin("理光负片")
        assertEquals(14, p.slots.size)
        assertEquals(
            "对比度 +6 · 饱和度 · 暗部 +15 · 胶片曲线 · 肩部 绿 0.00 · 颗粒 · 强度 30 · … · LUT ricoh_negative 30",
            presetSummaryText(p),
        )
    }

    /**
     * 「徕卡黑白」逐字（**没有色彩组** ⇒ 轮转从基础调整直接跳到影调曲线）：
     * 高反差黑白 + 明显颗粒，LUT 强度 100%（黑白 LUT 自带通道混合，见 `BuiltinPresets` 的
     * "为什么不加 [25] monochrome"）。
     *
     * ⚠ `slots.size = 12` 而 JSON 里有 14 个键：`grain_size: 400`（= 恒等值 [27]）与
     * `vignette_radius: 80`（= 恒等值 [67]，darktable 的 `scale` 默认）都被"恒等值不入库"洗掉 ——
     * 与理光负片同一条机制。
     */
    @Test
    fun summaryOfLeicaBwIsExact() {
        val p = builtin("徕卡黑白")
        assertEquals(12, p.slots.size)
        assertEquals(
            "对比度 +9 · 胶片曲线 · 肩部 红 0.66 · 颗粒 · 强度 40 · 胶片曲线 · 肩部 绿 0.00 · … · LUT leica_b_w_hc 100",
            presetSummaryText(p),
        )
        assertTrue("黑白预设不该有色彩组的行", presetDetailSections(p.slots).none { it.title == "色彩" })
    }

    /** 摘要里的读数必须与参数清单**逐字相同**（唯一维护点 = `presetDetailSections`）。 */
    @Test
    fun summaryReadoutsMatchDetailRowsForEveryBuiltin() {
        for (p in builtins) {
            val rows = presetDetailSections(p.slots).flatMap { it.rows }.map { "${it.label} ${it.value}" }
            // 用 presetSummaryParts 而不是把文本 split(" · ")：读数本身含 " · "
            //（"饱和度 · 中间调 +5"）⇒ 拆文本会把它切成两半，测出来的是假失败。
            for (part in presetSummaryParts(p)) {
                if (part == "…" || part.startsWith("LUT ")) continue
                assertTrue("摘要项「$part」在清单里找不到同名读数（格式漂移）：${p.name}", part in rows)
            }
        }
    }

    // ── ③ 新建：稀疏槽集合**不含 [0] 曝光**（U-7 的"拍摄态"纪律，验收判据 1）──────

    /**
     * `[0]` 是**拍摄态**（用户基准 + 用户 EV 补偿 + 预设偏移，`preset-DESIGN.md` §9.4）：
     * 无论当前曝光是多少，新建 / 更新的预设里**都不能有它** —— 否则用户下次套用这条预设会莫名
     * 多出一次曝光，而且他调 EV 的当下就污染了预设库。
     */
    @Test
    fun newPresetNeverCarriesTheExposureSlot() {
        // 模拟"用户把 EV 拖到 +1.5、对比度调到 1.2、颗粒开到 20"的当前相机参数
        val film = cameraParams(0 to 1.5, 4 to 1.2, 26 to 20.0)
        val slots = cameraPresetSlots(film)
        assertFalse("曝光槽 [0] 绝不能被存进预设（U-7）", slots.containsKey(CAMERA_PRESET_EV_SLOT))
        assertEquals("其余改动照常入库（不是把整条预设清空）", mapOf(4 to 1.2, 26 to 20.0), slots)
        // 曝光是**唯一**被剔除的槽：中性参数下其余槽一个都不进（稀疏化仍然生效）
        assertTrue("中性参数 ⇒ 空稀疏集", cameraPresetSlots(cameraParams(0 to -2.0)).isEmpty())
    }

    /** 新建的预设：新 id + 用户来源 + 名称/分组去空格 + LUT 两条口径。 */
    @Test
    fun newPresetIdentityAndLutRules() {
        val film = cameraParams(26 to 20.0)
        val p = newCameraPreset(
            name = "  我的风格  ", group = " 街拍 ", film = film,
            lutId = "leica_m9", lutStrength = 0.3, now = 1_700_000_000_000L, salt = 7,
        )
        assertTrue("id 走 presetId（p_ 前缀）", p.id.startsWith("p_1700000000000_"))
        assertEquals("名称去首尾空格", "我的风格", p.name)
        assertEquals("分组去首尾空格", "街拍", p.group)
        assertEquals("新建的永远是用户预设（可删可改）", PRESET_SOURCE_USER, p.source)
        assertEquals("创建时间 = 调用侧给的 now", 1_700_000_000_000L, p.createdAt)
        assertEquals(mapOf(26 to 20.0), p.slots)
        assertEquals("带 LUT ⇒ 记下 id 与强度", "leica_m9", p.lutId)
        assertEquals(0.3, p.lutStrength, 1e-9)

        // 没选 LUT ⇒ 预设不含 LUT（"缺失字段 = 套用时保留当前值"），强度回落 1.0
        val noLut = newCameraPreset("A", "", film, lutId = null, lutStrength = 1.4, now = 1L, salt = 1)
        assertNull("没选 LUT ⇒ lutId = null（不是空串）", noLut.lutId)
        assertEquals("没选 LUT 时强度回落 1.0（[34] 是当前相机的值，与预设无关）", 1.0, noLut.lutStrength, 1e-9)
        assertEquals("空名称回落「未命名」（写路径的兜底）", "未命名", newCameraPreset("   ", "", film, null, 1.0, 1L, 1).name)
    }

    // ── ④ 更新 / 复制：身份与配方各归各位 ─────────────────────────────────────

    /** 「从当前相机参数更新」只换**配方**（slots / LUT），身份字段一个都不动。 */
    @Test
    fun updateReplacesRecipeButKeepsIdentity() {
        val p = EditorPreset(
            id = "p_9_z", name = "老名字", group = "老分组",
            createdAt = 123L, source = PRESET_SOURCE_USER,
            slots = mapOf(26 to 5.0), lutId = "old_lut", lutStrength = 0.5,
        )
        val next = updatedCameraPreset(p, cameraParams(4 to 1.3, 0 to 2.0), lutId = "new_lut", lutStrength = 0.8)
        assertEquals("id 不动（重命名 / 更新都不改身份）", "p_9_z", next.id)
        assertEquals("名称不动", "老名字", next.name)
        assertEquals("分组不动", "老分组", next.group)
        assertEquals("创建时间不动", 123L, next.createdAt)
        assertEquals("来源不动", PRESET_SOURCE_USER, next.source)
        assertEquals("配方换成当前相机参数（仍不含 [0]）", mapOf(4 to 1.3), next.slots)
        assertEquals("LUT 跟着当前状态走", "new_lut", next.lutId)
        assertEquals(0.8, next.lutStrength, 1e-9)
        // 用户中途把 LUT 摘了 ⇒ 预设也不含 LUT（不留上一次的引用）
        val dropped = updatedCameraPreset(p, cameraParams(26 to 5.0), lutId = null, lutStrength = 0.5)
        assertNull("摘掉 LUT 后更新 ⇒ 预设不含 LUT", dropped.lutId)
        assertEquals(1.0, dropped.lutStrength, 1e-9)
    }

    /** 「复制」：新 id + 「副本」后缀 + **用户来源**（从内置复制出来的副本是可删可改的用户预设）。 */
    @Test
    fun copyBecomesAUserPresetWithFreshId() {
        val src = builtin("理光负片")
        val copy = copiedCameraPreset(src, now = 1_700_000_000_001L, salt = 3)
        assertTrue("新 id（不撞原 id）", copy.id != src.id && copy.id.startsWith("p_1700000000001_"))
        assertEquals("名称加「副本」后缀", "${src.name} 副本", copy.name)
        assertEquals("副本永远是用户预设", PRESET_SOURCE_USER, copy.source)
        assertTrue("副本可删可改", presetDeletable(copy))
        assertEquals("配方原样带走（复制 = 拿这条当起点改）", src.slots, copy.slots)
        assertEquals(src.lutId, copy.lutId)
        assertEquals(src.lutStrength, copy.lutStrength, 1e-9)
        assertEquals("创建时间 = 复制时刻", 1_700_000_000_001L, copy.createdAt)
    }

    // ── ⑤ 编辑面板按钮（验收判据 4）────────────────────────────────────────────

    /**
     * **内置只读修正**：`presetEditorButtons` 在编辑态只按 `paramsReady` / 名称非空决定
     * `updateFromCurrent` / `saveMeta`，没查可删性 ⇒ 不修正的话内置预设的「从当前相机参数更新」
     * 与「保存名称 / 分组」都是**亮的**（点了没反应 —— ADR-12 禁止的假按钮）。
     */
    @Test
    fun builtinEditorDisablesUpdateFromCurrent() {
        val p = builtin("徕卡黑白")
        val b = cameraPresetEditorButtons(isNew = false, preset = p, paramsReady = true, name = p.name)
        assertFalse("内置只读 ⇒ 「从当前相机参数更新」必须置灰", b.updateFromCurrent)
        assertFalse("内置只读 ⇒ 「保存名称 / 分组」也置灰（改名同样是改库）", b.saveMeta)
        assertFalse("内置只读 ⇒ 「删除」置灰（判据 presetDeletable）", b.delete)
        assertNotNull("内置编辑面板要带那句实话", presetReadOnlyNote(p))
    }

    /** 内置预设的编辑面板仍能**看配置**（清单数据源是这条预设自己的 slots，与只读无关）。 */
    @Test
    fun builtinEditorStillShowsItsFullParamList() {
        val p = builtin("徕卡黑白")
        val rows = presetDetailSections(p.slots).flatMap { it.rows }
        assertEquals("12 项参数一条都不能少（只读 ≠ 看不见；= JSON 的 14 键 − 2 个恒等值槽）", 12, rows.size)
        assertTrue("分组标题要在（基础调整 / 影调曲线 / 胶片质感）", presetDetailSections(p.slots).size >= 3)
    }

    /** 用户预设的编辑态：三项动作都可用（参数就绪 + 名称非空）。 */
    @Test
    fun userEditorEnablesEveryAction() {
        val p = userPreset("p_1_a")
        val b = cameraPresetEditorButtons(isNew = false, preset = p, paramsReady = true, name = "新名字")
        assertTrue(b.updateFromCurrent)
        assertTrue(b.saveMeta)
        assertTrue(b.delete)
        assertFalse("编辑态不该出现「另存为新预设」", b.saveNew)
        // 名称为空 ⇒ 「保存名称 / 分组」置灰（但按钮仍在）
        assertFalse("名称为空 ⇒ 改名置灰", cameraPresetEditorButtons(false, p, true, "   ").saveMeta)
    }

    /** 新建态：只有「保存当前相机参数为新预设」+「取消」；默认名预填 ⇒ 一路可点。 */
    @Test
    fun newStateOffersSaveOnlyAndDefaultNameIsUsable() {
        val name = defaultPresetName(builtins)
        assertEquals("内置三条不占默认名", "我的预设 1", name)
        val b = cameraPresetEditorButtons(isNew = true, preset = null, paramsReady = true, name = name)
        assertTrue("新建态：保存可点（默认名已预填）", b.saveNew)
        assertFalse("新建态没有更新 / 改名 / 删除", b.updateFromCurrent || b.saveMeta || b.delete)
    }

    // ── ⑥ 删除后内置三条不消失（P-2c 那个坑的回归哨兵，验收判据 5）──────────────

    /** 合并口径：删掉一条用户预设后，**内置三条必须仍在列表里**（顺序也不变）。 */
    @Test
    fun deletingAUserPresetKeepsAllBuiltins() {
        val users = listOf(userPreset("p_1_a", "我的预设 1"), userPreset("p_2_b", "我的预设 2"))
        val after = mergePresets(users.filterNot { it.id == "p_1_a" }, builtins)
        assertEquals("删一条用户预设 ⇒ 只剩另一条 + 三条内置", 4, after.size)
        assertEquals(
            "内置三条一条都不能少（P-2c 踩过的坑）",
            listOf("builtin_leica_m9", "builtin_ricoh_negative", "builtin_leica_bw"),
            after.filter { it.source == PRESET_SOURCE_BUILTIN }.map { it.id },
        )
        // 拍照界面的列表首项恒为内置「标准」⇒ 删除后仍应是「标准 + 1 用户 + 3 内置」
        val listed = listOf(CAMERA_STANDARD_PRESET) + after
        assertEquals(5, listed.size)
        assertEquals("「标准」必须还在列表首项", "builtin_standard", listed.first().id)
    }

    /**
     * **源码哨兵**：`CameraScreen.kt` 里每一条 `presets = …` 刷新都必须带上内置包 ——
     * 要么 `mergePresets(..., BuiltinPresets.load(context))`（五条写路径：保存 / 更新 / 改名 /
     * 复制 / 删除），要么 `BuiltinPresets.all(context, prefs)`（初始 remember）。
     *
     * 为什么值得用正则读源码：这个坑（只赋 `PresetStore` 的返回值 ⇒ 内置三条当场消失）
     * 已经犯过一次（P-2c），而它**不会编译失败、不会崩**，只在界面上少三条 ——
     * 靠人记得在每条新路径上写 `mergePresets` 是不可靠的（PS-2a 一次新增了四条写路径）。
     */
    @Test
    fun everyPresetListRefreshInCameraScreenCarriesTheBuiltinPackage() {
        val src = File("src/main/java/com/filmkit/app/ui/CameraScreen.kt").readText()
        val assigns = Regex("""(?m)^\s*presets = .*\(.*$""").findAll(src).map { it.value.trim() }.toList()
        assertEquals(
            "拍照界面的五条写路径（保存 / 更新 / 改名 / 复制 / 删除）都该在这里；条数变了 ⇒ 哨兵失效，需同步",
            5,
            assigns.size,
        )
        for (a in assigns) {
            val ok = a.contains("BuiltinPresets.all(") ||
                (a.contains("mergePresets(") && a.contains("BuiltinPresets.load("))
            assertTrue("列表刷新必须带内置包（否则内置三条当场消失）：$a", ok)
        }
        assertTrue("初始列表也走 BuiltinPresets.all", src.contains("BuiltinPresets.all(context, prefs)"))
        for (fn in listOf("PresetStore.upsert", "PresetStore.delete")) {
            assertTrue("$fn 的调用点消失了 ⇒ 写路径变了", src.contains(fn))
        }
        // 写路径的第二道防线（UI 置灰之外）：内置只读判据仍是 presetDeletable 这一个实现
        assertTrue("写路径必须用 presetDeletable 守卫（不许另写 source 比较）", src.contains("presetDeletable("))
    }

    /**
     * **能力可见性哨兵**：拍照界面这一层必须真的渲染"菜单 / 实话 / 摘要 / 完整清单 / 默认名"——
     * 它们是本次投诉五条的落点，任何一处被删掉都该红（而不是靠肉眼在 2700 行里找）。
     */
    @Test
    fun cameraPanelRendersEveryCapabilityWithoutRewritingTheLogic() {
        val panel = File("src/main/java/com/filmkit/app/ui/CameraPresetPanel.kt").readText()
        for (fn in listOf(
            "presetMenuItems(",        // 「⋯」菜单
            "presetReadOnlyNote(",     // 内置只读那句实话
            "presetSummaryText(",      // 每行的参数摘要
            "presetDetailSections(",   // 编辑面板的完整参数清单（唯一维护点）
            "presetEditorButtons(",    // 新建态 / 编辑态的按钮差异
            "defaultPresetName(",      // 新建时的默认名（不必先填名字）
        )) {
            assertTrue("CameraPresetPanel.kt 少了 $fn ⇒ 某条能力被藏起来了", panel.contains(fn))
        }
        // 「+ 新建预设」常驻 + 「⋯」按钮字面（用户看得见的入口）
        assertTrue(panel.contains("+ 新建预设"))
        assertTrue(panel.contains("⋯"))
    }
}
