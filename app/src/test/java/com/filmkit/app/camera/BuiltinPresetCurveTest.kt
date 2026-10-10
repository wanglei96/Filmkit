package com.filmkit.app.camera

import com.filmkit.app.lut.CubeLut
import com.filmkit.app.lut.CubeLutParser
import com.filmkit.app.ui.CameraPresets
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-1：内置三条预设的「目标曲线」回归测试（调研 `preset-sample-fingerprint-RESEARCH.md` §5.4
 * 的建议，架构师已采纳）。
 *
 * ## 目标曲线是什么（钉在这里，不依赖任何生成物）
 *
 * | 预设 | 目标曲线（输入 sRGB → 期望输出 sRGB，容差 ±4/255） | 证据 |
 * |---|---|---|
 * | 徕卡一瞬 | `52→28.6 85→71.8 122→122.1 160→169.2 200→205.9 243→233.4` | ⭐ 实测：IR M9 still-life **机内 JPEG** 灰阶行 |
 * | 理光负片 | `40→42.7 60→64.8 80→86.4 100→109.1 120→131.1 145→157.8 170→183.7 195→207.7 215→225.2 235→241.1 250→251.9` | ⭐ 实测：GR IIIx 同场景三联图 Negative÷Standard 净效应 |
 * | 徕卡黑白 HC | `52→20.5 85→52.7 122→117.4 160→178.7 200→219.8 243→244.4` | ⚠ **间接推断**：该模式**没有样片**，用 M9 `Contrast +2` 的实测曲线代替 |
 *
 * ## 为什么这些数字必须被测试钉住
 *
 * 曲线是"配方"的最终产物，而配方就是一堆浮点数 —— 谁调一下 `lut.strength` 或纬度，不会编译失败、
 * 不会崩，只会在用户成片上悄悄换成另一种观感（这正是调研 §8.2 的核心结论：三条 LUT 的**曲线形状**
 * 与目标不匹配，调强度救不了）。所以每次改配方都必须重新对目标曲线，本测试就是那条护栏。
 *
 * ## ★ 与离线工具的关系（本测试的三层结构）
 *
 * 曲线**系数**（`computeEditorCurve` 解的 12 个 5×5 系统）来自**引擎源码本身**：工具
 * `tools/preset-exp/recipe-fit.py` 把 `FrameRenderer.cpp` 的 `solve5` / `computeFilmicSpline` /
 * `computeEditorCurve` 括号配平提取后 `clang++` 真编译真跑，导出
 * `tools/preset-exp/fit/builtin-curves.json`。本测试：
 *  1. **参数一致性**：JSON 里记录的配方与 `assets/presets/builtin.json` **逐位一致**
 *     ⇒ 改了配方却没重跑工具 ⇒ 红（这是最重要的一条：它让"改了参数没重新对曲线"不可能发生）；
 *  2. **曲线判据**：用同一套 shader 公式（`kFsStyle` 的显示域段，逐字移植到下面
 *     [editorChainLuma]）求值，与目标曲线逐点比 **±4/255**；
 *  3. **无漂移**：本测试的求值与 JSON 里**引擎 shader 实测**的逐点值一致（≤1/255）
 *     ⇒ 证明这份纯 Kotlin 复刻与引擎没有分叉（否则第 2 层的判据就是自说自话）。
 *
 * ## 覆盖范围（诚实标注）
 *
 * 求值链 = **编辑器链路的显示域段**：`[4] contrast → [5..10]/[37][38] 胶片曲线 → [20..22] 饱和
 * → [23][24]/[25] 分离色调·单色 → LUT 混合`。与目标曲线**同域**（目标就是从已成片 JPEG 的
 * 显示域测出来的）故可直接比较。**不含**空间项（清晰度/影调分区/褪色/暗角/颗粒 —— 本卡不动它们，
 * 且它们让"同一条灰阶"在画面上不再同值）。**拍照链路**（RAW → filmic → 显示域）不在本测试内：
 * 它的输入是场景线性域，口径不同，数字见 `docs/reports/r1b-recipe-refit-REPORT.md` 与
 * `docs/reports/r1c-lut-floor-REPORT.md`（R-1c 的 `lut.strength` 下限 0.30）。
 *
 * 工作目录 = `app/`（gradle 单测约定，与 [BuiltinPresetsTest] 同口径）。
 */
class BuiltinPresetCurveTest {

    // ──────────────────────────────────────────────── 目标曲线（★ 唯一维护点）
    private val targets: Map<String, List<Pair<Int, Double>>> = mapOf(
        "builtin_leica_m9" to listOf(
            52 to 28.6, 85 to 71.8, 122 to 122.1, 160 to 169.2, 200 to 205.9, 243 to 233.4,
        ),
        "builtin_ricoh_negative" to listOf(
            40 to 42.7, 60 to 64.8, 80 to 86.4, 100 to 109.1, 120 to 131.1, 145 to 157.8,
            170 to 183.7, 195 to 207.7, 215 to 225.2, 235 to 241.1, 250 to 251.9,
        ),
        "builtin_leica_bw" to listOf(
            52 to 20.5, 85 to 52.7, 122 to 117.4, 160 to 178.7, 200 to 219.8, 243 to 244.4,
        ),
    )

    /** 判据容差（调研 §5 定的 ±4/255）—— **目标，不放宽**。 */
    private val tolerance = 4.0 / 255.0

    /**
     * ★★ R-1c 实测偏差上限（**显式声明的"已知偏差"，不是新的容差**）。
     *
     * 这不是"把 ±4 放宽到 10"：下面每条断言都是**钉住本次实测值**的回归护栏 —— 谁把配方改坏、
     * 或者引擎的曲线段被改动，实测值一旦超过这里声明的数就会红。声明值 = 本测试的
     * **Kotlin 复刻实测值**（R-1c：M9 **10.36** / 理光 **8.40** / 黑白 **8.95**，见每条用例的
     * R1_CURVE 行）+ 0.2 左右的余量（吸收 float16 帧缓冲与 dither 的噪声、以及工具重跑的抖动）。
     *
     * ⚠ **M9 的声明值从 R-1b 的 8.85 抬到 10.6 不是放松判据**，而是 R-1c 裁决 1 的**记账**：
     * `lut.strength` 有下限 **0.30、不接受 0**（理由见下第 4 条）⇒ 引擎实测从 8.84 涨到 10.46
     * 是**已知且已接受**的代价。谁把这个数再抬上去（例如把 LUT 强度调高"顺手救暗部"、
     * 或改动 `[5..10]`），这条就会红。
     *
     * ## 为什么三条都到不了 ±4（R-1b/R-1c 实测结论，证据见
     * `docs/reports/r1b-recipe-refit-REPORT.md` §3 与 `docs/reports/r1c-lut-floor-REPORT.md`）
     *
     * 1. **两条链路的最优解互斥**：预设的同一组参数同时喂给编辑器链路（kFsStyle，显示域）和
     *    拍照链路（filmic → kFsOut）。[37] `filmic_black_ev` 决定拍照链路的动态范围
     *    `dr = white_ev − black_ev`：编辑器链路的最优解把它压到 ~2.8 EV ⇒ 拍照链路暗部整段
     *    clamp。实测：M9 拍照最优 0.39 的那组参数在编辑器上是 21.55；编辑器最优 5.59 的那组
     *    在拍照上是 **65.77**。本测试判的是编辑器链路，而配方必须两条链路共用 ⇒ 取两者的
     *    minimax 折中（报告 §3.1 的 Pareto 表；R-1c 裁决 3 再次确认接受这个折中）。
     * 2. **拍照链路有硬天花板**：`kFsOut` 的 `highlightRolloff`（knee=0.80 + 指数肩）把线性
     *    显示域封在 `0.80+0.20·(1−1/e)=0.9264` ⇒ `srgbEncode` 后最高 **246.53/255**（实测
     *    cam=1.0）。理光目标在输入 250 处要 **251.9** ⇒ 拍照链路**必然**超差 ≥5.4（可证明）。
     * 3. **徕卡黑白的目标曲线是间接推断**（该模式没有样片，用 M9 `Contrast +2` 的实测曲线
     *    代替，调研 §4.5）⇒ 它的残差里含"目标本身可能不对"的成分，可信度最低。
     * 4. **M9 的 LUT 强度不可归零**（R-1c 裁决 1）：`lut = 0` 会让"徕卡一瞬"只剩影调，而 M9 的
     *    色彩（红浓 / 蓝绿偏青 / 品红）是**色相选择性**偏移，只有 3D LUT 能表达 ⇒ 下限 0.30。
     *    代价 = 本条的编辑器偏差 8.84（R-1b 的 `lut = 0` 解）→ **10.46**（引擎实测；Kotlin
     *    复刻 10.36）；理光**无损**（8.47 → 8.38），黑白不变（其 LUT 强度本就是 1.0）。
     *    三档（0.30 / 0.40 / 0.50）的权衡表见 R-1c 报告 §1 —— 0.30 是三档里最优的。
     *
     * ## 覆盖范围
     * 本测试判的是**编辑器链路**（`editorChainLuma` 逐字移植 kFsStyle 的显示域段）。拍照链路的
     * 逐点值记录在 `fit/builtin-curves.json` 的 `camera` 字段与报告 §3 的表里。
     */
    private val declared = mapOf(
        "builtin_leica_m9" to 10.6,
        "builtin_ricoh_negative" to 8.6,
        "builtin_leica_bw" to 9.15,
    )

    // ──────────────────────────────────────────────── 数据加载
    private val builtinJson = File("src/main/assets/${BuiltinPresets.ASSET_PATH}").readText()
    private val presets = BuiltinPresets.parse(builtinJson)
    private val report: Map<*, *> =
        MiniJson.parse(File("../tools/preset-exp/fit/builtin-curves.json").readText()) as Map<*, *>
    private val reported = report["presets"] as Map<*, *>

    private fun preset(id: String): EditorPreset =
        presets.firstOrNull { it.id == id } ?: error("内置包里没有 $id")

    private fun entry(id: String): Map<*, *> =
        (reported[id] as? Map<*, *>) ?: error("离线工具的报告里没有 $id（重跑 recipe-fit.py）")

    // ──────────────────────────────────────────────── ① 参数一致性（改了配方必须重跑工具）
    @Test
    fun reportParamsMatchBuiltinJson() {
        for ((id, pts) in targets) {
            val p = preset(id)
            val e = entry(id)
            val neutral = CameraPresets.standard()
            // 有效值 = 标准基线 + 稀疏 slots（与 BuiltinPresetsTest 同口径）
            val eff = presetSnapshot(neutral, p.slots)
            val want = e["slots"] as Map<*, *>
            // 只比对本卡白名单内的槽（grain/vignette 不在本卡的调参范围，也不进曲线）
            for (slot in intArrayOf(4, 5, 6, 7, 8, 9, 10, 20, 21, 22, 23, 24, 37, 38)) {
                val w = num(want["$slot"] ?: neutral[slot])
                assertEquals(
                    "$id 槽[$slot]：builtin.json 与离线工具报告不一致 ⇒ 改了配方必须重跑 " +
                        "tools/preset-exp/recipe-fit.py 并覆盖 fit/builtin-curves.json",
                    w, eff[slot], 1e-9,
                )
            }
            assertEquals(
                "$id 的 LUT 强度：builtin.json 与报告不一致（同一条纪律）",
                num(e["lut_strength"]), p.lutStrength, 1e-9,
            )
            assertEquals("$id 的目标曲线在报告里被改写了（本文件的 targets 才是真源）",
                pts.map { it.first }, (e["target"] as List<*>).map { num((it as List<*>)[0]).toInt() })
        }
    }

    // ──────────────────────────────────────────────── ② 曲线判据（目标 ±4/255；实测按 [declared] 回归）
    @Test
    fun everyPresetStaysWithinItsDeclaredDeviation() {
        val lines = StringBuilder()
        for ((id, pts) in targets) {
            val e = entry(id)
            val measured = measure(id, e)
            val msg = StringBuilder()
            var worst = 0.0
            var worstAt = 0
            for ((i, pt) in pts.withIndex()) {
                val d = measured[i] - pt.second
                if (abs(d) > abs(worst)) { worst = d; worstAt = pt.first }
                msg.append("  ${pt.first}→${"%.1f".format(measured[i])} (期望 ${pt.second}, Δ${"%+.1f".format(d)})")
            }
            lines.append("R1_CURVE $id max|Δ|=${"%.2f".format(abs(worst))}/255 @输入$worstAt ::$msg")
            println(lines.last())
            lines.append("\n")
            assertTrue(
                "$id 目标曲线超出**已声明**的偏差上限：最大偏差 ${"%.2f".format(abs(worst))}/255 " +
                    "@输入 $worstAt（声明 ${"%.2f".format(declared.getValue(id))}/255，" +
                    "目标 ${"%.2f".format(tolerance * 255)}/255）\n$msg" +
                    "\n★ 为什么不是 ±4：见类头 KDoc「为什么三条都到不了 ±4」（两条链路最优解互斥 + " +
                    "拍照链路 highlightRolloff 硬上限 + 黑白目标为间接推断）",
                abs(worst) <= declared.getValue(id),
            )
        }
        println(lines.toString().trimEnd())
    }

    // ──────────────────────────────────────────────── ③ 与引擎 shader 实测一致（防"复刻漂移"）
    @Test
    fun kotlinReimplementationMatchesEngineShaderWithinOneLsb() {
        for ((id, pts) in targets) {
            val e = entry(id)
            val measured = measure(id, e)
            val engine = nums(e["editor"])
            for ((i, pt) in pts.withIndex()) {
                assertTrue(
                    "$id 输入 ${pt.first}：Kotlin 复刻 ${"%.3f".format(measured[i])} 与引擎 shader 实测 " +
                        "${"%.3f".format(engine[i])} 相差 ${"%.3f".format(abs(measured[i] - engine[i]))} > 1/255 " +
                        "⇒ 这份纯函数已经与引擎分叉，第 ② 层的判据不再可信",
                    abs(measured[i] - engine[i]) <= 1.0,
                )
            }
        }
    }

    // ──────────────────────────────────────────────── ④ 曲线系数确实来自引擎（不是手抄的常数）
    @Test
    fun curveCoefficientsComeFromTheEngineAndAreNotDefault() {
        for ((id, _) in targets) {
            val e = entry(id)
            val ec = e["ec"] as Map<*, *>
            assertEquals("$id 的曲线段必须是真的打开了（否则 [37][38] 只在拍照链路生效）",
                1.0, num(ec["on"]), 0.0)
            // 端点重映射 (a,b) 非 (1,0) ⇒ [37][38] 真的参与了（默认时 `1.0*x+0.0` 逐位恒等）
            val lo = nums(ec["lo"]); val hi = nums(ec["hi"])
            val untouched = lo[0] == 1.0 && lo[1] == 0.0 && hi[0] == 1.0 && hi[1] == 0.0
            val latDefault = intArrayOf(5, 6, 7, 8, 9, 10).all {
                abs(presetEffective(id)[it] - 0.01) < 1e-9
            }
            assertTrue(
                "$id 的 [5..10] 与 [37][38] 全默认 ⇒ 曲线段根本不进（这条预设等于没重调）",
                !(untouched && latDefault),
            )
            println("R1_EC $id on=${num(ec["on"]).toInt()} lo=${lo.toList()} hi=${hi.toList()} " +
                "toeAt=${nums2(ec["cat"]).map { it[0] }} shAt=${nums2(ec["cat"]).map { it[1] }}")
        }
    }

    // ──────────────────────────────────────────────── 求值（kFsStyle 显示域段，逐字移植）
    private fun presetEffective(id: String): DoubleArray =
        presetSnapshot(CameraPresets.standard(), preset(id).slots)

    private fun measure(id: String, e: Map<*, *>): DoubleArray {
        val eff = presetEffective(id)
        val ec = EcCoefficients(e["ec"] as Map<*, *>)
        val lutId = preset(id).lutId
        val lut = lutId?.let {
            val f = File("src/main/assets/luts/$it.lut")
            if (f.isFile) CubeLutParser.parseBinary(f.readBytes(), it) else null
        }
        return targets.getValue(id).map { (x, _) ->
            editorChainLuma(
                x = x / 255.0,
                ec = ec,
                contrast = eff[4],
                sat = doubleArrayOf(eff[20], eff[21], eff[22]),
                split = doubleArrayOf(eff[23], eff[24]),
                mono = eff[25],
                lut = lut,
                lutStrength = preset(id).lutStrength,
            ) * 255.0
        }.toDoubleArray()
    }

    /** [4] → [5..10]/[37][38] → [20..22] → [23][24]/[25] → LUT 混合（顺序 = `kFsStyle` 的 main）。 */
    private fun editorChainLuma(
        x: Double,
        ec: EcCoefficients,
        contrast: Double,
        sat: DoubleArray,
        split: DoubleArray,
        mono: Double,
        lut: CubeLut?,
        lutStrength: Double,
    ): Double {
        // ① [4] 对比度：显示域绕中灰 0.5 线性拉伸（kFsStyle 5390）
        var c = DoubleArray(3) { clamp01((x - 0.5) * contrast + 0.5) }
        // ② [5..10] + [37][38] 胶片曲线（kFsStyle 5397-5412；uCurveOn=0 时整段跳过）
        if (ec.on) {
            c = DoubleArray(3) { ch ->
                val t0 = if (c[ch] < CURVE_GREY) ec.lo[0] * c[ch] + ec.lo[1]
                else ec.hi[0] * c[ch] + ec.hi[1]
                val t = clamp01(t0)
                clamp01(t + ec.evalCur(ch, t) - ec.evalBase(ch, t))
            }
        }
        // ③ [20..22] 分区饱和（kFsStyle 5418-5428）
        if (sat.any { it != 1.0 }) {
            val l = luma(c)
            val wsv = exp(-l * 3.0)
            val whv = smoothstep(0.5, 1.0, l)
            val wmv = max(0.0, 1.0 - wsv - whv)
            val s = min(max(sat[0] * wsv + sat[1] * wmv + sat[2] * whv, 0.0), 2.0)
            c = DoubleArray(3) { l + (c[it] - l) * s }
        }
        // ④ [23][24] 分离色调 + [25] 单色（kFsStyle 5493-5500）
        if (split[0] != 0.0 || split[1] != 0.0 || mono > 0.0) {
            val l = luma(c)
            val ws = exp(-l * 3.0)
            val wh = smoothstep(0.5, 1.0, l)
            val add = doubleArrayOf(0.05, 0.02, -0.04)
            val add2 = doubleArrayOf(-0.03, 0.0, 0.05)
            c = DoubleArray(3) { c[it] + split[0] * ws * add[it] + split[1] * wh * add2[it] }
            val lg = luma(c.map { clamp01(it) }.toDoubleArray())
            c = DoubleArray(3) { c[it] + (lg - c[it]) * mono }
        }
        // ⑤ LUT 混合（kFsStyle 5502）
        if (lut != null && lutStrength > 0.0) {
            val lc = lutApply(lut, c)
            c = DoubleArray(3) { c[it] + (lc[it] - c[it]) * lutStrength }
        }
        return luma(c)
    }

    private fun luma(c: DoubleArray): Double = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]

    private fun clamp01(v: Double): Double = if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v

    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = clamp01((x - e0) / (e1 - e0))
        return t * t * (3.0 - 2.0 * t)
    }

    /** `lutApply` 的四面体插值（`kFsStyle` 5555-5570 逐字；域 = 0..1、size = N）。 */
    private fun lutApply(lut: CubeLut, c: DoubleArray): DoubleArray {
        val n = lut.size
        val t = DoubleArray(3) { clamp01(c[it]) * (n - 1) }
        val i = IntArray(3) { min(max(floor(t[it]).toInt(), 0), n - 2) }
        val f = DoubleArray(3) { t[it] - i[it] }
        fun tex(dx: Int, dy: Int, dz: Int, ch: Int): Double {
            val r = min(max(i[0] + dx, 0), n - 1)
            val g = min(max(i[1] + dy, 0), n - 1)
            val b = min(max(i[2] + dz, 0), n - 1)
            return lut.data[((b * n + g) * n + r) * 3 + ch].toDouble()
        }
        val out = DoubleArray(3)
        for (ch in 0..2) {
            val c000 = tex(0, 0, 0, ch); val c100 = tex(1, 0, 0, ch)
            val c010 = tex(0, 1, 0, ch); val c001 = tex(0, 0, 1, ch)
            val c110 = tex(1, 1, 0, ch); val c101 = tex(1, 0, 1, ch)
            val c011 = tex(0, 1, 1, ch); val c111 = tex(1, 1, 1, ch)
            out[ch] = when {
                f[0] >= f[1] && f[1] >= f[2] ->
                    (1 - f[0]) * c000 + (f[0] - f[1]) * c100 + (f[1] - f[2]) * c110 + f[2] * c111
                f[0] >= f[1] && f[0] >= f[2] ->
                    (1 - f[0]) * c000 + (f[0] - f[2]) * c100 + (f[2] - f[1]) * c101 + f[1] * c111
                f[0] >= f[1] ->
                    (1 - f[2]) * c000 + (f[2] - f[0]) * c001 + (f[0] - f[1]) * c101 + f[1] * c111
                f[2] >= f[1] ->
                    (1 - f[2]) * c000 + (f[2] - f[1]) * c001 + (f[1] - f[0]) * c011 + f[0] * c111
                f[2] >= f[0] ->
                    (1 - f[1]) * c000 + (f[1] - f[2]) * c010 + (f[2] - f[0]) * c011 + f[0] * c111
                else ->
                    (1 - f[1]) * c000 + (f[1] - f[0]) * c010 + (f[0] - f[2]) * c110 + f[2] * c111
            }
        }
        return out
    }

    /** `computeEditorCurve` 的系数（离线工具从引擎源码导出，见类头 KDoc 第 ★ 段）。 */
    private class EcCoefficients(v: Map<*, *>) {
        val on: Boolean = num(v["on"]) != 0.0
        val lo = nums(v["lo"])
        val hi = nums(v["hi"])
        private val cat = nums2(v["cat"])
        private val cbat = nums2(v["cbat"])
        private val ctoe = nums2(v["ctoe"])
        private val csh = nums2(v["csh"])
        private val clin = nums2(v["clin"])
        private val btoe = nums2(v["btoe"])
        private val bsh = nums2(v["bsh"])
        private val blin = nums2(v["blin"])

        fun evalCur(ch: Int, t: Double): Double =
            seg(cat[ch][0], cat[ch][1], ctoe[ch], csh[ch], clin[ch], t)

        fun evalBase(ch: Int, t: Double): Double =
            seg(cbat[ch][0], cbat[ch][1], btoe[ch], bsh[ch], blin[ch], t)

        private fun seg(
            toeAt: Double, shAt: Double,
            toe: DoubleArray, sh: DoubleArray, lin: DoubleArray, t: Double,
        ): Double {
            fun h5(m: DoubleArray) = m[0] + t * (m[1] + t * (m[2] + t * (m[3] + t * m[4])))
            return when {
                t < toeAt -> h5(toe)
                t > shAt -> h5(sh)
                else -> lin[0] + t * lin[1]
            }
        }
    }

    private companion object {
        /** `kFsStyle` 的 `kCurveGrey` = srgbEncode(0.1845)（显示域中灰，分段界）。 */
        const val CURVE_GREY = 0.4666961

        fun num(v: Any?): Double = (v as Number).toDouble()
        fun nums(v: Any?): DoubleArray = (v as List<*>).map { num(it) }.toDoubleArray()
        fun nums2(v: Any?): Array<DoubleArray> = (v as List<*>).map { nums(it) }.toTypedArray()
    }
}
