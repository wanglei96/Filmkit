package com.filmkit.app.camera

import android.hardware.camera2.CameraCharacteristics

/**
 * DNG 标准色彩链（M3 §3.2-7 / §3.4-4：「与 Adobe 冲图观感可比」）。
 *
 * 现状：引擎直接用 HAL 的 `COLOR_CORRECTION_TRANSFORM`。标定数据其实齐全
 * （v0.5.45 探针实测 X300：ColorMatrix1/2 = D65/A、ForwardMatrix1/2、CameraCalibration1/2
 * = 单位阵、illuminant 21/17），本文件用它自建「相机白平衡后 → 线性 sRGB」矩阵：
 *
 * ```
 * cam(wb) --[CameraCalibration]--> 参考相机 --[ForwardMatrix(t)]--> XYZ(D50)
 *         --[Bradford D50→D65 + sRGB 矩阵]--> 线性 sRGB
 * ```
 *
 * 双光源插值权重 t 由 `asShotNeutral` 反查（比较各标定光源下的相机白点）：
 * **t 只影响色彩形状**——ForwardMatrix1/2 都把 (1,1,1) 映到 D50 白点，插值后依然如此，
 * 所以白点/整体亮度与 t 无关（实测 True）。
 *
 * 与 HAL CCM 的实测差异（191459 一次拍摄）：标准链对角增益更大
 * （1.62/1.34/1.58 vs 1.39/1.19/1.55）→ 更接近 libraw 完整链的亮度与通道分离度。
 * 数据缺失或未知光源 → 返回 null，调用方回退 HAL 矩阵（跨机型兜底）。
 */
object StdColorMatrix {

    /** XYZ(D50) → 线性 sRGB：Bradford D50→D65 与 sRGB 原色矩阵的乘积（离线算好）。 */
    private val X2S = floatArrayOf(
        3.133996f, -1.616931f, -0.490698f,
        -0.978413f, 1.915921f, 0.033398f,
        0.071996f, -0.228962f, 1.404947f,
    )

    /** 标定光源（DNG CalibrationIlluminant 码）→ XYZ 白点。只列本机型会用到的。 */
    private fun illumXyz(code: Int?): FloatArray? = when (code) {
        21 -> floatArrayOf(0.95047f, 1.0f, 1.08883f)   // D65
        17 -> floatArrayOf(1.09850f, 1.0f, 0.35585f)   // Standard Light A
        else -> null
    }

    private fun mul(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    private fun mulVec(m: FloatArray, v: FloatArray): FloatArray = FloatArray(3) { r ->
        m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2]
    }

    private fun cst9(t: android.hardware.camera2.params.ColorSpaceTransform?): FloatArray? {
        t ?: return null
        // 构造收行主序、读取 getElement(col,row)：必须 (row,col) 双循环（v0.5.20 转置教训）
        return FloatArray(9).also { out ->
            for (row in 0..2) for (col in 0..2) {
                out[row * 3 + col] = t.getElement(col, row).toFloat()
            }
        }
    }

    /**
     * 自建标准链矩阵（行主序 9 元素）。
     * @param ch 当前相机 characteristics；@param neutral asShotNeutral (r,g,b)
     * @return null = 标定数据不全/光源未知 → 调用方回退 HAL CCM
     */
    fun compute(ch: CameraCharacteristics?, neutral: FloatArray): FloatArray? {
        ch ?: return null
        val ct1 = cst9(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)) ?: return null
        val ct2 = cst9(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)) ?: return null
        val fm1 = cst9(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)) ?: return null
        val fm2 = cst9(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)) ?: return null
        val w1 = illumXyz(ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)) ?: return null
        // illuminant2 的键类型是 Byte（illuminant1 是 Integer）——javap 核实过
        val w2 = illumXyz(ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt())
            ?: return null
        if (neutral[0] <= 0f || neutral[1] <= 0f || neutral[2] <= 0f) return null
        return computeFromMatrices(ct1, ct2, fm1, fm2, w1, w2, neutral)
    }

    /** 纯数学部分（无 Android 依赖，JVM 单测直接调）：全部矩阵为行主序 9 元素。 */
    internal fun computeFromMatrices(
        ct1: FloatArray, ct2: FloatArray, fm1: FloatArray, fm2: FloatArray,
        w1: FloatArray, w2: FloatArray, neutral: FloatArray,
    ): FloatArray? {
        // 插值权重：各标定光源下的相机白点（G 归一）与 asShotNeutral 的距离最小者
        val cw1 = mulVec(ct1, w1)
        val cw2 = mulVec(ct2, w2)
        if (cw1[1] <= 0f || cw2[1] <= 0f) return null
        var bestT = 0f
        var bestE = Float.MAX_VALUE
        for (k in 0..100) {
            val t = k / 100f
            var e = 0f
            for (c in 0..2) {
                val interp = (1f - t) * cw1[c] / cw1[1] + t * cw2[c] / cw2[1]
                val d = interp - neutral[c] / neutral[1]
                e += d * d
            }
            if (e < bestE) {
                bestE = e
                bestT = t
            }
        }

        // FM(t) → XYZ(D50) → 线性 sRGB
        val fm = FloatArray(9) { i -> (1f - bestT) * fm1[i] + bestT * fm2[i] }
        return mul(X2S, fm)
    }
}
