package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-2 护栏：**拍照链路读取集合** == 代码常量 == `preset-DESIGN.md` §1.6 的"拍照链路"列。
 *
 * 为什么需要（U-3 实测暴露的真实漏洞）：`PresetScopeTest` 只保证"**代码表 == 文档表**"
 * （它正则解析 §1.6 与 `PresetScope.kt` 的 `SLOT_SCENES` 对拍），
 * `EditorStyleWhitelistTest` 只保证"**编辑器链路**的三方一致"
 * （脚本白名单 == `passStyle` 实际读的 `p.<字段>` == KDoc）。
 * ⇒ **"文档表 == 引擎实际"这一环没人守**：§1.6 的 `[4]` 行改前就错标 ✅
 * （U-3 实测 `passFilm` 链路对 `p.contrast` **零引用**，与 §9.2 自相矛盾），
 * 而三张表各自"内部一致"、测试全绿。本测试补上这一环 —— 它才是"文档说引擎读了某槽位"
 * 这个断言的**唯一**机器判据。
 *
 * ── 抽取口径（改本文件前先读这段；"覆盖不到的部分"见下，不假装全覆盖）────────────
 *  1. 源文件 = `core/engine/src/render/FrameRenderer.cpp`（**唯一**文件；`Bridge.cpp`
 *     的 JNI 桥、Kotlin 侧 `CameraPresets`/`CameraController` 对 FilmParams 的读写不在
 *     范围内 —— 本测试管的是"引擎的渲染链路读不读"，不是"参数从哪来"）。
 *  2. 预处理：**剥离字符串字面量与注释**（`R"(...)"` 整段、普通 `"..."`、`//`、`/* */`）。
 *     必须剥离的两个理由：① shader 源码（`kFsFilm`/`kFsStyle` 等原始字符串）是 GLSL，
 *     里面的 `//` 注释与 `p.` 都不该算；② 注释里写着的 `p.contrast`（U-3 的说明文字就
 *     有）会把"文档化的空引用"误算成"实际引用"。
 *  3. 半径：抽**全文件**每个成员函数体内的 `p.<字段>`（含 `p.field[ch]` 这类下标读取），
 *     即"直接读取"与"在函数体内把 `p` 整体传给别的函数"都覆盖（被调函数也在同一文件里
 *     被扫描 ⇒ 间接读取自动被算到）。
 *  4. 排除：**编辑器链路**的两个函数 `passStyle` / `submitDisplay` 的**函数体**（编辑器链路的
 *     读取集合由 `EditorStyleWhitelistTest` 独立守着）。⚠ 是**位置级**排除（把函数体替换成
 *     `{}` 后再抽），**不是**"字段名差集"—— 后者会连带抹掉两条链路都读的字段
 *     （U-2 实测：字段名差集 ∩ 进预设 = **空集**，见 [engineCameraPathSlots] 的 KDoc）。
 *     ⚠ 将来新增"只在编辑器链路上跑"的函数时，**必须把函数名加进 [editorPathFuncs]**，
 *     否则它的读取会被算成拍照链路 ⇒ 本测试会红（红是好事：说明该同步名单了）。
 *  5. 过滤：只保留 `presetSlotIncluded(slot)` 为真的槽位（进预设的槽位）—— RAW 解码槽
 *     `[39..59]`、局部调整 `[79..87]`、`[28]/[30..36]` 这些"不进预设"的槽位不参与对拍
 *     （它们与预设的作用域无关，判据见 `presetSlotIncluded` 的 KDoc）。
 *  6. 槽位号来源 = `FilmParams.h` 的声明的注释（`float toe[3] = ...;  // 8..10`）——
 *     **数组字段**（`shoulder[3]`/`toe[3]`）展开成 3 个槽位。
 *
 * ── 覆盖不到的部分（诚实标注；这些是已知盲区，不是"等价于全覆盖"）──────────────
 *  · **只抽"某个函数体内出现过 `p.<字段>`"**。若槽位经由成员变量/中间结构体间接传递
 *    （`member_ = p.x; ... member_`），仍会被抽到（`p.x` 在函数体内）；但若经由
 *    **FilmParams 之外的通道**（JNI 参数、prefs 覆盖层、纹理/LUT 资产）进入引擎，
 *    不在范围内 —— 例如拍照界面的 `[0]/[28]/[30]/[34]/[35]` 会被 Kotlin 侧覆盖层重写，
 *    本测试只看"引擎末端读不读"。
 *  · **只看一个文件**：`Bridge.cpp` / `FrameRenderer.h`（只有声明）不参与。
 *  · **不含"读取后是否真的有视觉效果"**：`p.lut_strength` 被读了但 `[34]` 不进预设；
 *    反过来，某个字段被读进一个恒假分支也算"读了"。这一层由离线数值脚本
 *    （`tools/check-u2-editor-curve-sat.py` / `check-toeshoulder.py` / `check-u3-*.py`）守。
 *  · **§1.6 对拍只覆盖"表格里有的槽位"**：文档漏写一整行时，本测试的"反向对拍"抓不到
 *    （它只检查"表里标 ✅ 的是否真读"）—— 反向的"引擎读了但表里没有"由正向对拍补
 *    （常量里多一个槽位、表里没有 ⇒ 红）。两侧合起来才能闭合。
 *
 * 工作目录 = `app/`（gradle 单测约定，与 [EditorStyleWhitelistTest] 同口径）。
 */
class CameraStyleWhitelistTest {

    /**
     * 拍照链路读取集合的**唯一维护点**（代码侧白名单）。判据 = `FrameRenderer.cpp` 的
     * 实际读取 ⇒ 本常量与引擎、§1.6 三者必须一致（三个用例分别钉住）。
     *
     * U-2（2026-10-11）起 = **34 个槽位**，逐组来源：
     *  - `[0]` 曝光 / `[1..3]` WB / `[4]` 对比度（U-3 接线）/ `[5..10]` 趾肩 latitude
     *    （E4-4）/ `[20..22]` 分区饱和 / `[23..25]` 分离色调+单色（U-1）/
     *    `[26][27][60][61]` 颗粒（E4-3）/ `[37][38]` filmic 黑白点 /
     *    `[66..68]` 暗角（E4-2）/ `[78]` 清晰度 + `[90..94]` 影调分区·褪色（U-4）/
     *    `[88][89]` 色温色调（U-3）。
     *  - **不含** `[28]`（基准影调）/ `[30]`（过曝提示）/ `[31..33]`（色彩外观层）/
     *    `[34]`（LUT 强度，由 `lut.strength` 承载）/ `[35][36]`（引擎标定）：`presetSlotIncluded`
     *    把它们排除在预设之外 ⇒ 与"预设作用域"无关，不在本集合的定义域内。
     */
    private val cameraPathSlots: Set<Int> = buildSet {
        add(0)
        addAll(1..3)
        add(4)
        addAll(5..10)      // [5..7] shoulder[3] / [8..10] toe[3]（数组字段）
        addAll(20..22)     // 分区饱和三档（U-2 补 [20][22]）
        addAll(23..25)     // 分离色调 + 单色
        add(26); add(27); add(60); add(61)
        add(37); add(38)
        addAll(66..68)
        add(78)
        add(88); add(89)
        addAll(90..94)
    }

    /** 编辑器链路的函数（抽取时排除；新增编辑器专属函数时必须加进来，见类 KDoc 第 4 条）。 */
    private val editorPathFuncs = listOf("passStyle", "submitDisplay")

    // ── 抽取工具 ──────────────────────────────────────────────────────────────

    /**
     * 剥离**字符串字面量与注释**（`R"(...)"` / `"..."` / `//` / `/* */`）。
     * 顺序要紧：先认 `R"(` 整段原始字符串（shader 源码里全是 `//` 与 `"`），再认普通字符串，
     * 最后才是注释 —— 否则 shader 里的 `//` 会把后面半个文件当注释吃掉。
     */
    private fun stripLiteralsAndComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == 'R' && i + 1 < src.length && src[i + 1] == '"') {
                val open = src.indexOf('(', i + 2)
                val close = if (open >= 0) src.indexOf(")\"", open + 1) else -1
                if (open > 0 && close > open) {
                    out.append(' ')
                    i = close + 2
                    continue
                }
            }
            if (c == '"') {
                i++
                while (i < src.length && src[i] != '"') {
                    if (src[i] == '\\') i++
                    i++
                }
                i++
                out.append(' ')
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** `FrameRenderer::<name>(...) { ... }` 的**函数体**（含大括号）；括号配平抽取。 */
    private fun bodyOf(src: String, name: String): String {
        val m = Regex("FrameRenderer::$name\\s*\\(").find(src)
            ?: error("FrameRenderer.cpp 里找不到函数 $name")
        var i = src.indexOf('(', m.range.first)
        var depth = 0
        while (i < src.length) {
            if (src[i] == '(') depth++
            if (src[i] == ')') {
                depth--
                if (depth == 0) break
            }
            i++
        }
        val b = src.indexOf('{', i)
        check(b > 0) { "函数 $name 没有函数体（声明？）" }
        var j = b
        depth = 0
        while (j < src.length) {
            if (src[j] == '{') depth++
            if (src[j] == '}') {
                depth--
                if (depth == 0) break
            }
            j++
        }
        return src.substring(b, j + 1)
    }

    /** `FilmParams.h` 的 `float <字段>[可选 [N]] = ...;  // <槽位>[..<末槽>]` ⇒ 字段 → 槽位集合。 */
    private fun fieldSlots(): Map<String, Set<Int>> {
        val src = File("../core/engine/src/film/FilmParams.h").readText()
        return Regex(
            "float\\s+(\\w+)\\s*(?:\\[(\\d+)])?\\s*=[^;]*;\\s*//\\s*(\\d+)(?:\\.\\.(\\d+))?",
        ).findAll(src).associate { m ->
            val a = m.groupValues[3].toInt()
            val b = m.groupValues[4].ifEmpty { m.groupValues[3] }.toInt()
            m.groupValues[1] to (a..b).toSet()
        }
    }

    /**
     * 引擎真值：拍照链路实际读到的**进预设**槽位集合。口径见类 KDoc。
     *
     * ⚠ **"排除编辑器链路"必须是位置级的**（U-2 实测踩过）：把 `passStyle` / `submitDisplay`
     * 的**函数体替换成 `{}`** 后再抽，**不是**做"字段名差集"（`allRefs − editorRefs`）。
     * 后者会把**两条链路都读**的字段（`contrast` / `toe` / `shoulder` / `sat_mid` / `split_*` /
     * `grain_*` / `highlights` …）从拍照链路一侧也抹掉 —— 实测那样得到 27 个字段，全部是
     * `[28]/[30..36]/[39..59]` 这些"不进预设"的槽位 ⇒ 集合 ∩ 进预设 = **空集**
     * （U-2 第一版就是这么写的，被本测试自己抓住：`expected 34 slots but was []`）。
     */
    private fun engineCameraPathSlots(): Set<Int> {
        val src = stripLiteralsAndComments(
            File("../core/engine/src/render/FrameRenderer.cpp").readText(),
        )
        val allRefs = Regex("\\bp\\.(\\w+)").findAll(src).map { it.groupValues[1] }.toSet()
        var cameraSrc = src
        for (fn in editorPathFuncs) cameraSrc = cameraSrc.replace(bodyOf(src, fn), "{}")
        val refs = Regex("\\bp\\.(\\w+)").findAll(cameraSrc).map { it.groupValues[1] }.toSet()
        val declared = fieldSlots()
        val unknown = allRefs - declared.keys
        assertTrue(
            "FrameRenderer.cpp 里出现了 FilmParams.h 没有的字段 $unknown —— " +
                "要么是拼错，要么有个不叫 p 的局部变量被误抽（先查清再改断言）",
            unknown.isEmpty(),
        )
        return refs.flatMap { declared.getValue(it) }
            .filter { presetSlotIncluded(it) }
            .toSet()
    }

    /** §1.6 表格：槽位 → (拍照链路读?, 编辑器链路读?)（解析口径与 [PresetScopeTest] 一致）。 */
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

    // ── 三个用例（正向 / 反向 / 存在性）────────────────────────────────────────

    /** ① 引擎实际读取 == 代码白名单（抓"新增读取却没登记"与"登记了却没读"）。 */
    @Test
    fun engineCameraPathMatchesWhitelist() {
        assertEquals(
            "拍照链路实际读到的预设槽位必须逐槽等于 cameraPathSlots（改引擎读取集合时" +
                "同步改本常量 + §1.6 + tools/check-style-passthrough.py）",
            cameraPathSlots,
            engineCameraPathSlots(),
        )
    }

    /** ② §1.6 标"拍照链路读"的槽位，引擎必须真的读（**这一条才抓得住 U-3 发现的 `[4]` 漏洞**）。 */
    @Test
    fun designMatrixCameraColumnIsBackedByEngine() {
        val declared = engineCameraPathSlots()
        val missing = designMatrix().filter { (slot, ce) ->
            ce.first && presetSlotIncluded(slot) && slot !in declared
        }.keys
        assertTrue(
            "§1.6 的「拍照链路」列在 $missing 上标了 ✅，但 FrameRenderer.cpp 的拍照链路" +
                "零引用 —— 这正是 U-3 在 [4] 上发现的「文档表 ≠ 引擎实际」（改文档或改引擎，不要放着）",
            missing.isEmpty(),
        )
    }

    /** ③ 代码白名单里的每个槽位，§1.6 必须存在且标"拍照链路读"（抓"表里漏行/表里标 ❌"）。 */
    @Test
    fun whitelistIsCoveredByDesignMatrix() {
        val matrix = designMatrix()
        for (slot in cameraPathSlots) {
            val ce = matrix[slot]
            assertTrue("§1.6 里没有槽位 [$slot] 这一行（表格漏行？）", ce != null)
            assertTrue("§1.6 的「拍照链路」列把 [$slot] 标成了 ❌，但引擎确实读它", ce!!.first)
        }
        // 反向的完整性：§1.6 里"进预设且拍照链路读"的槽位数 == 本常量的大小
        //（两侧相等 ⇒ "表里有、代码里没有"与"代码里有、表里没有"都被抓住）。
        val fromMatrix = matrix.filter { (slot, ce) -> ce.first && presetSlotIncluded(slot) }.keys
        assertEquals(
            "§1.6 的「进预设 + 拍照链路读」槽位集合必须与 cameraPathSlots 相等",
            fromMatrix,
            cameraPathSlots,
        )
    }

    /** ④ 定义域健全性：本集合不得含"不进预设"的槽位（否则它在与 §1.6 对拍时必然自相矛盾）。 */
    @Test
    fun whitelistOnlyContainsPresetSlots() {
        for (slot in cameraPathSlots) {
            assertTrue(
                "槽位 [$slot] 不在 presetSlotIncluded 的定义域里 ⇒ 它不进预设，" +
                    "作用域/读取集合都不该讨论它",
                presetSlotIncluded(slot),
            )
            assertTrue("槽位 [$slot] 越界（CameraPresets.COUNT = ${CameraPresets.COUNT}）", slot < CameraPresets.COUNT)
        }
    }
}
