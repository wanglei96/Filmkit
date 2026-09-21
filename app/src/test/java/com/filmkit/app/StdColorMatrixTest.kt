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
 * 另断言**白点保持**：相机白（白平衡后 (1,1,1)）必须映到 sRGB 白 (1,1,1)，
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

    /** 离线 numpy 参照（t=0.20；与实现同式：先算各标定光源的相机白点，再插值匹配 asShotNeutral） */
    private val expect = floatArrayOf(
        1.620645f, -0.552040f, -0.068912f,
        -0.141190f, 1.341329f, -0.199966f,
        0.007890f, -0.580001f, 1.571924f,
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
}
