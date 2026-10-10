package com.filmkit.app.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2-23 护栏：`EditorEngineSession` 的 KDoc 槽位清单 / 脚本白名单 / 引擎实际读取 **三方一致**。
 *
 * 为什么值得一个测试：那段 KDoc 曾长期停在 E2-11 之前的旧白名单（"passStyle 只读
 * [26]/[27]/[34]/[66..68]"），E2-21 的"打开编辑器把成片已烘焙的参数**二次应用**"bug 的认知
 * 根源就是它 —— 照旧注释判断，会以为 [0]/[1..3]/[4]/[78]/[88..94] 这些槽位"无所谓"。
 * 注释已经腐烂过一次，就不能只靠"下次记得改"。
 *
 * 三方各自在哪（改任何一方忘了另外两方 ⇒ 本测试红）：
 *  1. **权威白名单** = `tools/check-style-passthrough.py` 第 ④ 项的 `want` 集合；
 *  2. **引擎实际读取** = `FrameRenderer.cpp::passStyle` 里的 `p.<字段>` 引用（脚本 ④ 的真判据）；
 *  3. **人读的清单** = `EditorEngineSession.kt` 文件头 KDoc 里的 `[槽位] 字段名`；
 * 槽位号本身由 `FilmParams.h` 的 `// NN` 注释核对（第 2 个用例）。
 *
 * 工作目录 = app/（gradle 单测的约定，与 EditorExportTest 读 `../test_temp` 同一口径）。
 */
class EditorStyleWhitelistTest {

    /** 权威白名单：脚本第 ④ 项的 `want = { ... }`（字符串字面量即字段名） */
    private fun scriptWhitelist(): Set<String> {
        val src = File("../tools/check-style-passthrough.py").readText()
        val body = Regex("want = \\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.groupValues?.get(1)
            ?: error("check-style-passthrough.py 里找不到 want = { ... }")
        return Regex("'([A-Za-z_][A-Za-z0-9_]*)'").findAll(body)
            .map { it.groupValues[1] }.toSet()
    }

    /** 引擎实际读取：passStyle 函数体里的 `p.<字段>` 引用 */
    private fun engineReads(): Set<String> {
        val src = File("../core/engine/src/render/FrameRenderer.cpp").readText()
        val body = Regex("void FrameRenderer::passStyle\\(.*?\\n\\}", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.value
            ?: error("FrameRenderer.cpp 里找不到 passStyle 函数体")
        return Regex("\\bp\\.(\\w+)").findAll(body).map { it.groupValues[1] }.toSet()
    }

    /** KDoc 清单：文件头到 `internal class EditorEngineSession` 之间的 `[槽位] 字段名` */
    private fun kdocSlots(): Map<Int, String> {
        val src = File("src/main/java/com/filmkit/app/camera/EditorEngineSession.kt").readText()
        val kdoc = src.substringBefore("internal class EditorEngineSession")
        return Regex("\\[(\\d+)]\\s+([a-z_][a-z0-9_]*)").findAll(kdoc)
            .associate { it.groupValues[1].toInt() to it.groupValues[2] }
    }

    /**
     * FilmParams.h 的 `float <字段>[可选 [N]] = ...;  // <槽位号>[..<末槽号>]` ⇒ 字段 → 槽位集合。
     *
     * ★ U-2（2026-10-11）：从"标量字段"扩为**数组字段** —— `[5..10]` 用的是
     * `float shoulder[3]` / `float toe[3]`（一个字段覆盖 3 个槽位，`// 5..7` / `// 8..10`），
     * 旧正则（`float\s+(\w+)\s*=`）匹配不到它们，映射会缺项。
     */
    private fun declaredSlots(): Map<String, Set<Int>> {
        val src = File("../core/engine/src/film/FilmParams.h").readText()
        return Regex(
            "float\\s+(\\w+)\\s*(?:\\[(\\d+)])?\\s*=[^;]*;\\s*//\\s*(\\d+)(?:\\.\\.(\\d+))?",
        ).findAll(src).associate { m ->
            val a = m.groupValues[3].toInt()
            val b = m.groupValues[4].ifEmpty { m.groupValues[3] }.toInt()
            m.groupValues[1] to (a..b).toSet()
        }
    }

    @Test
    fun kdocListMatchesScriptWhitelist() {
        val kdoc = kdocSlots()
        // 数组字段（`shoulder[3]` / `toe[3]`）会在 KDoc 里合法地出现多次 ⇒ 只要求
        // "重复的字段名确实是 FilmParams.h 里的数组字段"（防复制粘贴把标量标成两个槽位）。
        kdoc.values.groupingBy { it }.eachCount().filter { it.value > 1 }.forEach { (name, n) ->
            assertEquals(
                "字段 $name 在 KDoc 里标了 $n 个槽位 ⇒ 它必须是 FilmParams.h 里的数组字段",
                n,
                declaredSlots()[name]?.size ?: 0,
            )
        }
        assertEquals(
            "KDoc 槽位清单必须与脚本 want 逐项相同（新增编辑器参数时三处一起改）",
            scriptWhitelist(),
            kdoc.values.toSet(),
        )
    }

    @Test
    fun kdocSlotNumbersMatchFilmParams() {
        val declared = declaredSlots()
        kdocSlots().forEach { (slot, name) ->
            val slots = declared[name]
            assertNotNull("FilmParams.h 里没有字段 $name（KDoc 清单写错了名字？）", slots)
            assertTrue(
                "FilmParams.$name 的槽位集合 $slots 不含 KDoc 标的 [$slot]",
                slot in slots!!,
            )
        }
    }

    @Test
    fun scriptWhitelistMatchesEngineReads() {
        assertEquals(
            "脚本 want 必须 == passStyle 实际读的 p.* 字段（不一致时先报告，别擅自改 shader）",
            engineReads(),
            scriptWhitelist(),
        )
    }
}
