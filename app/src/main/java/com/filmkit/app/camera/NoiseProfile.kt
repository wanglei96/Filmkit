package com.filmkit.app.camera

/**
 * C 专项（2026-09-29）：Camera2 SENSOR_NOISE_PROFILE → 3 通道 (S,O) 噪声模型。
 *
 * 语义（MGC/PC 取证口径）：Array<Pair<gain=ISO/100, Double[]>>，每组的 Double[]
 * 逐通道给出 (S, O) 对（S = shot、O = read）。模型 var(x) = S·x + O，x = [0,1]
 * 归一化 raw 域（黑阶后 / 白阶归一）——与渲染域一致、与写出的 DNG NoiseProfile
 * tag(51041) 同源同域（X300 实读 S≈7.06e-4 / O≈3.8e-7 @ISO1970），**不要再除
 * whiteLevel**。
 *
 * 布局（真机探测）：len ≥ 8 = 4 通道 CFA 相位序 (0,0)(1,0)(0,1)(1,1)；len == 6
 * = 3 通道 (R,G,B)。相位→RGB 映射（PC phaseToCanonical 表）：
 *   RGGB(0): R=ph0 G=ph1,ph2 B=ph3 | GRBG(1): R=ph1 G=ph0,ph3 B=ph2
 *   GBRG(2): R=ph2 G=ph3,ph0 B=ph1 | BGGR(3): R=ph3 G=ph2,ph1 B=ph0
 * （G 取两相位均值——MGC bayerNoiseModelToRgb 同口径。）
 */
object NoiseProfile {

    /**
     * Cast 兼容层（2026-09-29 真机实锤）：Camera2 的 SENSOR_NOISE_PROFILE 泛型是
     * `Pair<Double, Double[]>`——`Double[]` 是**装箱数组**，Kotlin 里 `as? DoubleArray`
     * （原始 double[]）**永远失败**。旧代码（v0.5.32 起）一直这么写，叠加更早的
     * List cast bug，σ 自适应从未真正生效。兼容 DoubleArray / Array<*> / List<*> 三种。
     */
    fun asDoubleArray(v: Any?): DoubleArray? = when (v) {
        is DoubleArray -> v
        is Array<*> -> {
            val out = DoubleArray(v.size)
            for (i in v.indices) out[i] = (v[i] as? Number)?.toDouble() ?: return null
            out
        }
        is List<*> -> {
            val out = DoubleArray(v.size)
            for (i in v.indices) out[i] = (v[i] as? Number)?.toDouble() ?: return null
            out
        }
        else -> null
    }

    /** 解析结果：model = [S_r,S_g,S_b,O_r,O_g,O_b]（null = 不可用）；供日志的原始数据 */
    class Result(
        val model: FloatArray?,
        val gain: Double,
        val raw: DoubleArray?,
        val len: Int,
        /** 第二个元素的实际类型名（诊断 cast 问题，如 "[Ljava.lang.Double;"） */
        val elemType: String = "?",
    )

    /**
     * CFA 枚举（0=RGGB..3=BGGR）→ 相位序 (R, G0, G1, B) 索引。
     * 实测依据：X300 返回 cfa=1（GRBG）→ R=ph1、G=ph0/ph3、B=ph2，与 SDK
     * 常量（SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG = 1）及引擎 cfaLutOf 一致。
     */
    fun phaseMap(cfa: Int): IntArray = when (cfa % 4) {
        0 -> intArrayOf(0, 1, 2, 3)   // RGGB
        1 -> intArrayOf(1, 0, 3, 2)   // GRBG（X300）
        2 -> intArrayOf(2, 3, 0, 1)   // GBRG
        else -> intArrayOf(3, 2, 1, 0) // BGGR
    }

    /**
     * 结构 A —— **真机实测（X300，2026-09-29）的 Camera2 实际形态**：
     * 4 个 (S, O) 标量对 = 4 个 CFA 相位 (0,0)(1,0)(0,1)(1,1) 的模型（PC 源码同款
     * 语义：MGC "reads exactly four Camera2 pairs in CFA phase order"，无 gain
     * 字段——HAL 按帧上报当前增益的模型，不需要 ISO 选择）。
     * 注意：与 SDK 声明类型 `Pair<Double, Double[]>` 不同（文档与实际不符，实测
     * `pair.second` 运行时是 java.lang.Double；旧的 `as? DoubleArray` 因此永远失败）。
     * 输入用 Kotlin Pair（android.util.Pair 解包在调用方，便于纯 JVM 单测）。
     */
    fun fromScalarPairs(pairs: List<Pair<Double, Double>>, cfa: Int): FloatArray? {
        if (pairs.size < 4) return null
        val s = DoubleArray(4)
        val o = DoubleArray(4)
        for (c in 0 until 4) {
            s[c] = pairs[c].first
            o[c] = pairs[c].second
        }
        if (s.any { !it.isFinite() || it < 0.0 } || o.any { !it.isFinite() || it < 0.0 }) {
            return null
        }
        val (ri, g0, g1, bi) = phaseMap(cfa)
        val sg = 0.5 * (s[g0] + s[g1])
        val og = 0.5 * (o[g0] + o[g1])
        // 有效条件（MGC 口径）：S 全 >0 且 O 有 >0
        if (s[ri] <= 0.0 || sg <= 0.0 || s[bi] <= 0.0) return null
        if (o[ri] <= 0.0 && og <= 0.0 && o[bi] <= 0.0) return null
        return floatArrayOf(
            s[ri].toFloat(), sg.toFloat(), s[bi].toFloat(),
            o[ri].toFloat(), og.toFloat(), o[bi].toFloat(),
        )
    }

    fun parse(
        entries: List<Pair<Double, DoubleArray>>,
        cfa: Int,
        iso: Int,
        elemType: String = "?",
    ): Result {
        var bestDist = Double.MAX_VALUE
        var bestGain = 0.0
        var bestS: DoubleArray? = null
        val wantGain = iso.coerceAtLeast(50) / 100.0
        entries.forEach { (g, s) ->
            if (s.size < 6) return@forEach
            val dist = kotlin.math.abs(g - wantGain)
            if (dist < bestDist) {
                bestDist = dist
                bestGain = g
                bestS = s
            }
        }
        val s = bestS ?: return Result(null, 0.0, null, 0, elemType)
        // 相位序 → (R, G0, G1, B) 索引；6 值布局（3 通道 R,G,B）则直取
        val ph = phaseMap(cfa)
        val (ri, g0, g1, bi) = ph
        val c6 = s.size < 8
        val idxR = if (c6) 0 else ri
        val idxG0 = if (c6) 1 else g0
        val idxG1 = if (c6) 1 else g1
        val idxB = if (c6) 2 else bi
        val sr = s[idxR * 2]
        val orr = s[idxR * 2 + 1]
        val sg = 0.5 * (s[idxG0 * 2] + s[idxG1 * 2])
        val og = 0.5 * (s[idxG0 * 2 + 1] + s[idxG1 * 2 + 1])
        val sb = s[idxB * 2]
        val ob = s[idxB * 2 + 1]
        // 有效条件（MGC 口径）：S 全 >0 且 O 有 >0（全零 O 是 HAL 缺省）
        val allF = listOf(sr, sg, sb, orr, og, ob).all { it.isFinite() && it >= 0.0 }
        val model = if (allF && sr > 0.0 && sg > 0.0 && sb > 0.0 &&
            (orr > 0.0 || og > 0.0 || ob > 0.0)
        ) {
            floatArrayOf(
                sr.toFloat(), sg.toFloat(), sb.toFloat(),
                orr.toFloat(), og.toFloat(), ob.toFloat(),
            )
        } else {
            null
        }
        return Result(model, bestGain, s, s.size, elemType)
    }
}
