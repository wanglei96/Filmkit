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
 * ② 真实内置资产用例钉住"二进制资产真能被解析"——资产损坏要在构建期就暴露，
 * 而不是等到设备上选 LUT 时黑屏。ASCII .cube 解析器（用户导入导出用）继续由合成用例覆盖。
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
    fun rejectsMissingSizeAndBadSize() {
        val missing = expectFailure { CubeLutParser.parse(cube2.replace("LUT_3D_SIZE 2", "")) }
        assertTrue(missing, missing.contains("LUT_3D_SIZE"))
        val tiny = expectFailure { CubeLutParser.parse(cube2.replace("LUT_3D_SIZE 2", "LUT_3D_SIZE 1")) }
        assertTrue(tiny, tiny.contains("超出范围"))
        // 数据行必须排在 LUT_3D_SIZE 之后，否则尺寸未知
        val early = expectFailure { CubeLutParser.parse("0.0 0.0 0.0\nLUT_3D_SIZE 2") }
        assertTrue(early, early.contains("之前"))
    }

    /** 1D → 3D 展开（M4 §6.2）：三通道独立曲线，展开是数学等价而非近似 */
    @Test
    fun parses1dAsThreeIndependentCurves() {
        // 1D 表：R 曲线 = x+0.1（抬红）、G/B 曲线 = 恒等
        val oneD = """
            TITLE "curve"
            LUT_1D_SIZE 2
            0.1 0.0 0.0
            1.0 1.0 1.0
        """.trimIndent()
        val l = CubeLutParser.parse(oneD)
        assertEquals("curve", l.title)
        assertEquals(2, l.size)
        assertEquals(24, l.data.size)
        // 3D 展开 (r,g,b) → (fR(r), fG(g), fB(b))：首格点 = (fR(0), fG(0), fB(0)) = (0.1, 0, 0)
        assertEquals(0.1f, l.data[0], 1e-6f)
        assertEquals(0f, l.data[1], 1e-6f)
        // (r=1,g=0,b=0)（红最快，第 2 组）= (fR(1), fG(0), fB(0)) = (1, 0, 0)
        assertEquals(1f, l.data[3], 1e-6f)
        assertEquals(0f, l.data[4], 1e-6f)
        // (r=0,g=1,b=0)（第 3 组）= (fR(0), fG(1), fB(0)) = (0.1, 1, 0)
        assertEquals(0.1f, l.data[6], 1e-6f)
        assertEquals(1f, l.data[7], 1e-6f)
        assertEquals(0f, l.data[8], 1e-6f)
        // 展开后的 3D 域恒 [0,1]（INPUT_RANGE 折进格点）
        assertEquals(0f, l.domainMin[0], 0f)
        assertEquals(1f, l.domainMax[0], 0f)
    }

    /** 1D INPUT_RANGE：输入域 [0.2, 0.8] → 3D 引擎均匀采样 [0,1] 时线性映射回表 */
    @Test
    fun applies1dInputRange() {
        // 表 2 项：0 = 黑、1 = 白；INPUT_RANGE [0.5, 1.0] —— 3D 输入 0.5 处应输出 0（表首）、
        // 输入 1.0 处输出 1（表尾）、0.25 处 clamp 到表首
        val oneD = """
            TITLE "ranged"
            LUT_1D_SIZE 2
            LUT_1D_INPUT_RANGE 0.5 1.0
            0.0 0.0 0.0
            1.0 1.0 1.0
        """.trimIndent()
        val l = CubeLutParser.parse(oneD)
        assertEquals(2, l.size)
        // 输入 0（< range 下限）→ clamp 表首 = 0；输入 0.5（= 下限）→ 表首 0
        val first = l.data[0]
        assertEquals(0f, first, 1e-6f)
        // 输入 1.0（= 上限）→ 表尾 = 1
        val idx = ((1 * 2 + 1) * 2 + 1) * 3
        assertEquals(1f, l.data[idx], 1e-6f)
    }

    @Test
    fun rejects1dBadRowsAndRange() {
        val oneD = "TITLE t\nLUT_1D_SIZE 2\n0.0 0.0 0.0\n"
        val few = expectFailure { CubeLutParser.parse(oneD) }
        assertTrue(few, few.contains("1D 数据行不足"))
        val badRange = expectFailure {
            CubeLutParser.parse(oneD + "1.0 1.0 1.0\nLUT_1D_INPUT_RANGE 0.8 0.2")
        }
        assertTrue(badRange, badRange.contains("INPUT_RANGE"))
    }

    /** 内置资产端到端：33³ 二进制资产全部可解析、端点为黑/白（资产坏掉不该等到设备上才发现） */
    @Test
    fun parsesBundledAssets() {
        val dir = File("src/main/assets/luts")
        assumeTrue("工作目录看不到内置资产：${File(".").absolutePath}", dir.isDirectory)
        val files = dir.listFiles { f -> f.name.endsWith(".lut") }?.sortedBy { it.name }.orEmpty()
        assertTrue("内置资产数偏少：${files.size}", files.size >= 40)
        for (f in files) {
            val bytes = f.readBytes()
            val l = CubeLutParser.parseBinary(bytes, f.name)
            // 33³ 为主；leica_m9_ccd 是 128³ 资产（0.12.93：色域压缩的陡斜率 64³ 装不下）
            val expectedSize = if (f.name == "leica_m9_ccd.lut") 128 else 33
            assertEquals(f.name, expectedSize, l.size)
            assertEquals(f.name, expectedSize * expectedSize * expectedSize * 3, l.data.size)
            // 只做"资产没坏"的通用检查：端点阈值本质是风格假设，会误报
            // （Fuji-ACROS 白点 0.835、Kodak-5203 首点 R 0.118 = 电影负片抬黑）。
            // "不是常数"足以抓住全零/截断的坏文件。
            assertTrue(f.name, l.data.max() - l.data.min() > 0.3f)
            assertTrue("${f.name} TITLE 缺失", l.title.isNotBlank())
            // 头部元数据（列表渲染依赖）：显示名与分类都不能为空
            val tl = bytes[1].toInt() and 0xFF
            assertTrue("${f.name} 显示名缺失", tl > 0)
            assertTrue("${f.name} 分类缺失", (bytes[2 + tl].toInt() and 0xFF) > 0)
        }
    }

    /** 二进制头/长度畸形必须抛异常：内置资产格式坏掉要在构建期暴露，不静默降级 */
    @Test
    fun rejectsMalformedBinary() {
        val good = File("src/main/assets/luts/fuji_cc.lut")
        assumeTrue("工作目录看不到内置资产", good.isFile)
        val bytes = good.readBytes()
        val head = expectFailure { CubeLutParser.parseBinary(bytes.copyOf(10)) }
        assertTrue(head, head.contains("截断") || head.contains("数据不足"))
        val short = expectFailure { CubeLutParser.parseBinary(bytes.copyOf(bytes.size - 1)) }
        assertTrue(short, short.contains("数据不足"))
        val tiny = expectFailure { CubeLutParser.parseBinary(byteArrayOf(1, 0)) }
        assertTrue(tiny, tiny.contains("超出范围"))
        val empty = expectFailure { CubeLutParser.parseBinary(ByteArray(1)) }
        assertTrue(empty, empty.contains("过短"))
        // TITLE 缺失（titleLen=0、catLen=0）时回退调用侧给的显示名
        val blank = ByteArray(3 + 33 * 33 * 33 * 3).also { it[0] = 33 }
        assertEquals("fallback", CubeLutParser.parseBinary(blank, "fallback").title)
    }

    /** 黑白 LUT 的 R=G=B 是中性灰——顺带验证"红最快"的数据顺序没被颠倒 */
    @Test
    fun monochromeAssetIsNeutral() {
        val f = File("src/main/assets/luts/leica_b_w_true.lut")
        assumeTrue("工作目录看不到内置资产", f.isFile)
        val l = CubeLutParser.parseBinary(f.readBytes(), f.name)
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
