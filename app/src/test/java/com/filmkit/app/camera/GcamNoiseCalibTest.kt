package com.filmkit.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C 专项（2026-09-29）：GCam `.c` 噪声标定解析与求值（PC 式可导入来源）。
 * 求值语义与 PC 的 CalibratedRawNoiseProfile(ISO_POLYNOMIAL) 同构：
 *   S = max(A·iso + B, 0)；O = max(C·iso² + D·dg², 0)，dg = max(iso/maxAnalog, 1)
 */
class GcamNoiseCalibTest {

    private val sample = """
        /* Generated test code to dump a table ... */
        #include <stdio.h>
        double compute_noise_model_entry_S(int plane, int sens);
        double compute_noise_model_entry_O(int plane, int sens);
        double compute_noise_model_entry_S(int plane, int sens) {
            static double noise_model_A[] = { 1.8326389269617373e-07,1.8310841760634264e-07,1.8257826701051919e-07,1.8157910951989409e-07 };
            static double noise_model_B[] = { -2.0598894168819969e-07,-5.0396057411973582e-07,-5.3241333186416879e-07,-3.924086360945215e-07 };
            double A = noise_model_A[plane];
            double B = noise_model_B[plane];
            double s = A * sens + B;
            return s < 0.0 ? 0.0 : s;
        }
        double compute_noise_model_entry_O(int plane, int sens) {
            static double noise_model_C[] = { 1.5467896663927581e-13,1.5399684699652229e-13,1.5462212604176862e-13,1.5095015122305481e-13 };
            static double noise_model_D[] = { 2.438361775816499e-07,2.3725323086979802e-07,2.3872211548107904e-07,2.3799350248210071e-07 };
            double digital_gain = (sens / 9100.0) < 1.0 ? 1.0 : (sens / 9100.0);
            double C = noise_model_C[plane];
            double D = noise_model_D[plane];
            double o = C * sens * sens + D * digital_gain * digital_gain;
            return o < 0.0 ? 0.0 : o;
        }
    """.trimIndent()

    /** 相对容差断言（evaluate 返回 FloatArray，float 精度在 e-7 量级约 1e-14 绝对） */
    private fun assertNear(expected: Double, actual: Double) {
        assertEquals(expected, actual, kotlin.math.abs(expected) * 1e-5 + 1e-18)
    }

    @Test
    fun parseAndEvaluate_belowMaxAnalog() {
        val cal = GcamNoiseCalib.parse("t.c", "t", sample)
        assertNotNull(cal)
        val m = cal!!.evaluate(100)!!
        // S(plane0) = A0*100 + B0
        assertNear(1.8326389269617373e-07 * 100 - 2.0598894168819969e-07, m[0].toDouble())
        // G = (Gr + Gb)/2
        val sg = 0.5 * (1.8310841760634264e-07 * 100 - 5.0396057411973582e-07 +
            1.8257826701051919e-07 * 100 - 5.3241333186416879e-07)
        assertNear(sg, m[1].toDouble())
        assertNear(1.8157910951989409e-07 * 100 - 3.924086360945215e-07, m[2].toDouble())
        // O（iso ≤ maxAnalog → dg=1）：C*iso² + D
        assertNear(1.5467896663927581e-13 * 1e4 + 2.438361775816499e-07, m[3].toDouble())
        val og = 0.5 * (1.5399684699652229e-13 * 1e4 + 2.3725323086979802e-07 +
            1.5462212604176862e-13 * 1e4 + 2.3872211548107904e-07)
        assertNear(og, m[4].toDouble())
    }

    @Test
    fun digitalGain_squaredAboveMaxAnalog() {
        val cal = GcamNoiseCalib.parse("t.c", "t", sample)!!
        val lo = cal.evaluate(9100)!!
        val hi = cal.evaluate(18200)!!
        // dg = 2 → D 项 ×4（C 项 ×4 因 iso² 也 ×4；总比值 > 4 但 D 项主导暗部）
        assertTrue(hi[3] > lo[3] * 3.9)
        // S 随 iso 线性
        val sLo = lo[0].toDouble()
        val sHi = hi[0].toDouble()
        assertTrue(sHi > sLo * 1.9 && sHi < sLo * 2.1)
    }

    @Test
    fun missingDivisor_doesNotBlowUpReadNoise() {
        // 无 `sens / X`（旧生成器/手工文件）：dg 必须不启用（=1）；
        // 若误取 1 作为除数会 dg=iso、read 项 ×iso² 爆炸
        val text = "static double noise_model_A[] = {1e-7,1e-7,1e-7,1e-7};\n" +
            "static double noise_model_B[] = {0,0,0,0};\n" +
            "static double noise_model_C[] = {1e-13,1e-13,1e-13,1e-13};\n" +
            "static double noise_model_D[] = {2e-7,2e-7,2e-7,2e-7};"
        val cal = GcamNoiseCalib.parse("x", "x", text)
        assertNotNull(cal)
        val m = cal!!.evaluate(6400)!!
        assertNear(1e-13 * 6400.0 * 6400.0 + 2e-7, m[3].toDouble())
    }

    @Test
    fun invalidFiles_returnNull() {
        assertNull(GcamNoiseCalib.parse("x", "x", ""))
        assertNull(GcamNoiseCalib.parse("x", "x", "not a noise model at all"))
        // 缺 D 数组
        assertNull(
            GcamNoiseCalib.parse(
                "x", "x",
                "static double noise_model_A[] = {1,2,3,4};\n" +
                    "static double noise_model_B[] = {1,2,3,4};\n" +
                    "static double noise_model_C[] = {1,2,3,4};",
            ),
        )
    }

    @Test
    fun packedContainers_areAccepted() {
        // Camera2 泛型是装箱 Double[]（Array<*> / List<*>）——as? DoubleArray 会失败
        val packedArr: Array<Any?> = arrayOf(1.0, 2.0, 3.0, 4.0)
        val packedList: List<Any?> = listOf(1.0, 2.0, 3.0, 4.0)
        val prim = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(4, NoiseProfile.asDoubleArray(prim)?.size)
        assertEquals(4, NoiseProfile.asDoubleArray(packedArr)?.size)
        assertEquals(4, NoiseProfile.asDoubleArray(packedList)?.size)
        assertNull(NoiseProfile.asDoubleArray(null))
        assertNull(NoiseProfile.asDoubleArray("nope"))
    }
}
