package com.filmkit.app

import com.filmkit.app.camera.StdColorMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * DNG 标准色彩链的纯数学自检（M3 §3.2-7）。
 *
 * 输入是 v0.5.45 真机探针实测的 X300 标定数据（191459 一次拍摄），期望值是同一算式
 * 在离线 Python（numpy double）里的结果——两边不一致就说明 Kotlin 实现跑偏了。
 * 另断言**白点保持**：相机白（白平衡后 (1,1,1)）必须映到工作空间白 (1,1,1)，
 * 这是"整体亮度/色偏不会被色彩矩阵偷走"的必要条件。
 */
class StdColorMatrixTest {

    // ColorMatrix1/2（D65/A，XYZ→camera，行主序）
    private val ct1 = floatArrayOf(
        0.6668f, -0.1589f, -0.0857f,
        -0.5739f, 1.3898f, 0.1430f,
        -0.1379f, 0.2652f, 0.6036f,
    )
    private val ct2 = floatArrayOf(
        1.5315f, -0.4696f, -0.2151f,
        -0.4762f, 1.4453f, 0.0067f,
        -0.0717f, 0.2387f, 0.2330f,
    )

    // ForwardMatrix1/2（camera 白平衡后→XYZ D50）
    private val fm1 = floatArrayOf(
        0.6731f, 0.1950f, 0.0960f,
        0.2762f, 0.8182f, -0.0944f,
        0.0217f, -0.2325f, 1.0360f,
    )
    private val fm2 = floatArrayOf(
        0.5745f, 0.1840f, 0.2057f,
        0.1938f, 0.7454f, 0.0608f,
        -0.0145f, -0.5287f, 1.3684f,
    )

    private val d65 = floatArrayOf(0.95047f, 1.0f, 1.08883f)
    private val illumA = floatArrayOf(1.09850f, 1.0f, 0.35585f)
    private val asn = floatArrayOf(0.4904215f, 1.0f, 0.5885057f)

    /** 离线 numpy 参照（t=0.20；与实现同式：先算各标定光源的相机白点，再插值匹配 asShotNeutral）。
     *  期 B：CCM 终点换成线性 Rec.2020（`X2S` 的真源 = `tools/rec2020-domain-matrices.py`），
     *  本期望值由**同一构造源重算** —— 不是为了变绿而就地改数。 */
    private val expect = floatArrayOf(
        0.970662f, 0.070130f, -0.040946f,
        -0.017904f, 1.188738f, -0.170771f,
        0.021165f, -0.410521f, 1.389368f,
    )

    @Test
    fun matchesOfflineReference() {
        val m = StdColorMatrix.computeFromMatrices(ct1, ct2, fm1, fm2, d65, illumA, asn)
        assertNotNull(m)
        for (i in 0..8) assertEquals("m[$i]", expect[i], m!![i], 2e-3f)
    }

    @Test
    fun keepsWhitePoint() {
        val m = StdColorMatrix.computeFromMatrices(ct1, ct2, fm1, fm2, d65, illumA, asn)!!
        for (r in 0..2) {
            val sum = m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2]
            assertEquals("row $r white", 1.0f, sum, 5e-3f)
        }
    }

    /**
     * 期 B：HAL 回退链的域补齐（`halToWorking`）。
     *
     * 为什么用**非单位**矩阵：单位阵下 `A×H` 与 `H×A` 结果相同 ⇒ 测不出乘法顺序写反。
     * 用 `diag(2,1,1)`（R 输入 ×2）就能区分：正确顺序时只有**第 0 列**被放大。
     * 这是本项目踩过两次的坑（`cst9` 注释里的 "v0.5.20 转置教训"）。
     */
    @Test
    fun halToWorkingAppliesAfterHalAndKeepsWhite() {
        val hal = floatArrayOf(2f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val m = StdColorMatrix.halToWorking(hal)
        // 正确 = SRGB_TO_WORK × hal ⇒ 第 0 列（R 输入）整体 ×2、其余列不变
        assertEquals("m[0] (col0×2)", 2f * 0.6274038959f, m[0], 1e-5f)
        assertEquals("m[1] (col1 不放大)", 0.3292830384f, m[1], 1e-5f)
        assertEquals("m[2] (col2 不放大)", 0.0433130657f, m[2], 1e-5f)
        // 白点保持：行和必须 = 1（Rec.2020 与原域同为 D65 的必要条件）
        // ⚠ 用单位阵版本验证行和（hal 会缩放列，行和不再为 1 是正常的）
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val w = StdColorMatrix.halToWorking(identity)
        for (r in 0..2) {
            val sum = w[r * 3] + w[r * 3 + 1] + w[r * 3 + 2]
            assertEquals("col-sum $r", 1.0f, sum, 1e-5f)
        }
    }
}
