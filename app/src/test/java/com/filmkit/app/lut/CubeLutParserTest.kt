package com.filmkit.app.lut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * .cube 解析器自检（M4 §6：解析器需覆盖畸形输入）。
 *
 * 两条线索：① 合成用例钉住格式规则（顺序、DOMAIN、注释、CRLF、各类畸形）；
 * ② 真实内置资产用例钉住"6 个 .cube 真能被解析"——资产损坏要在构建期就暴露，
 * 而不是等到设备上选 LUT 时黑屏。
 */
class CubeLutParserTest {

    /** 2³：红最快 → 第 1 组 (0,0,0)，第 8 组 (1,1,1) */
    private val cube2 = """
        TITLE "测试 LUT"
        LUT_3D_SIZE 2
        DOMAIN_MIN 0.0 0.0 0.0
        DOMAIN_MAX 1.0 1.0 1.0
        0.0 0.0 0.0
        0.1 0.0 0.0
        0.0 0.1 0.0
        0.1 0.1 0.0
        0.0 0.0 0.1
        0.1 0.0 0.1
        0.0 0.1 0.1
        0.1 0.1 0.1
    """.trimIndent()

    @Test
    fun parsesBasic3d() {
        val l = CubeLutParser.parse(cube2)
        assertEquals("测试 LUT", l.title)
        assertEquals(2, l.size)
        assertEquals(24, l.data.size)
        assertEquals(0f, l.data[0], 0f)
        // 第 2 组 = r 先动：红通道变化最快
        assertEquals(0.1f, l.data[3], 1e-6f)
        assertEquals(0f, l.data[4], 1e-6f)
        // 末组 = (1,1,1)
        assertEquals(0.1f, l.data[23], 1e-6f)
    }

    @Test
    fun titleFallsBackWhenMissing() {
        val l = CubeLutParser.parse(cube2.replace("TITLE \"测试 LUT\"\n", ""), "ricoh_neg.cube")
        assertEquals("ricoh_neg.cube", l.title)
    }

    @Test
    fun parsesDomainAndInputRange() {
        val l = CubeLutParser.parse(
            cube2.replace("DOMAIN_MIN 0.0 0.0 0.0", "DOMAIN_MIN 0.1 0.2 0.3")
                .replace("DOMAIN_MAX 1.0 1.0 1.0", "DOMAIN_MAX 0.9 0.8 0.7"),
        )
        assertEquals(0.1f, l.domainMin[0], 1e-6f)
        assertEquals(0.7f, l.domainMax[2], 1e-6f)

        val r = CubeLutParser.parse(
            cube2.replace("DOMAIN_MIN 0.0 0.0 0.0", "LUT_3D_INPUT_RANGE 0.0 0.0 0.0 0.5 0.5 0.5")
                .replace("DOMAIN_MAX 1.0 1.0 1.0", ""),
        )
        assertEquals(0.5f, r.domainMax[1], 1e-6f)
    }

    @Test
    fun toleratesCommentsBlankLinesAndCrlf() {
        val messy = cube2.replace("\n", " \r\n") + "\r\n\r\n# 尾部注释\r\n"
        val l = CubeLutParser.parse(messy)
        assertEquals(2, l.size)
        assertEquals(24, l.data.size)
    }

    @Test
    fun rejectsTooFewRows() {
        val few = cube2.lines().dropLast(2).joinToString("\n")
        val msg = expectFailure { CubeLutParser.parse(few) }
        assertTrue(msg, msg.contains("数据行不足"))
    }

    @Test
    fun rejectsTooManyRows() {
        val msg = expectFailure { CubeLutParser.parse("$cube2\n0.1 0.1 0.1") }
        assertTrue(msg, msg.contains("多于"))
    }

    @Test
    fun rejectsNonNumericAndShortRows() {
        val bad = expectFailure { CubeLutParser.parse(cube2.replace("0.1 0.1 0.1", "0.1 0.1 x")) }
        assertTrue(bad, bad.contains("数值非法"))
        val short = expectFailure { CubeLutParser.parse(cube2.replace("0.1 0.1 0.1", "0.1 0.1")) }
        assertTrue(short, short.contains("只找到"))
    }

    @Test
    fun rejectsMissingSizeAnd1dAndBadSize() {
        val missing = expectFailure { CubeLutParser.parse(cube2.replace("LUT_3D_SIZE 2", "")) }
        assertTrue(missing, missing.contains("LUT_3D_SIZE"))
        val oneD = expectFailure { CubeLutParser.parse(cube2.replace("LUT_3D_SIZE 2", "LUT_1D_SIZE 2")) }
        assertTrue(oneD, oneD.contains("1D"))
        val tiny = expectFailure { CubeLutParser.parse(cube2.replace("LUT_3D_SIZE 2", "LUT_3D_SIZE 1")) }
        assertTrue(tiny, tiny.contains("超出范围"))
        // 数据行必须排在 LUT_3D_SIZE 之后，否则尺寸未知
        val early = expectFailure { CubeLutParser.parse("0.0 0.0 0.0\nLUT_3D_SIZE 2") }
        assertTrue(early, early.contains("之前"))
    }

    /** 内置资产端到端：6 个 33³ 全部可解析、端点为黑/白（资产坏掉不该等到设备上才发现） */
    @Test
    fun parsesBundledAssets() {
        val dir = File("src/main/assets/luts")
        assumeTrue("工作目录看不到内置资产：${File(".").absolutePath}", dir.isDirectory)
        val files = dir.listFiles { f -> f.name.endsWith(".cube") }?.sortedBy { it.name }.orEmpty()
        assertEquals(6, files.size)
        for (f in files) {
            val l = CubeLutParser.parse(f.readText(), f.name)
            assertEquals(f.name, 33, l.size)
            assertEquals(f.name, 33 * 33 * 33 * 3, l.data.size)
            assertTrue(f.name, l.data[0] < 0.05f)
            assertTrue(f.name, l.data.last() > 0.9f)
            assertTrue("${f.name} TITLE 缺失", l.title.isNotBlank())
        }
    }

    /** 黑白 LUT 的 R=G=B 是中性灰——顺带验证"红最快"的数据顺序没被颠倒 */
    @Test
    fun monochromeAssetIsNeutral() {
        val f = File("src/main/assets/luts/leica_bw.cube")
        assumeTrue("工作目录看不到内置资产", f.isFile)
        val l = CubeLutParser.parse(f.readText(), f.name)
        val idx = ((7 * 33 + 11) * 33 + 23) * 3 // 任取一个内部格点
        assertEquals(l.data[idx], l.data[idx + 1], 1e-6f)
        assertEquals(l.data[idx], l.data[idx + 2], 1e-6f)
    }

    private fun expectFailure(block: () -> Unit): String = try {
        block()
        throw AssertionError("应当解析失败但成功了")
    } catch (e: IllegalArgumentException) {
        e.message.orEmpty()
    }
}
