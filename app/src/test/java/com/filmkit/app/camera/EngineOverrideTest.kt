package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设置页「全局引擎档位」（prefs `engine_override`：`-1` 跟随预设 / `0` 强制 filmic /
 * `1` 强制 sigmoid，用户 2026-10-14 拍板）在**渲染侧**的合成判据。
 *
 * 与 [EngineScopeTest]（阶段 3 的旁路判据）的分工：那里钉"引擎 1 下 filmic 参数不生效"，
 * 这里钉"**哪个**引擎在生效"。
 *
 * ⛔ 本卡最容易漏的一条：**只旁路不改 `[98]` 的"强制引擎"根本不会换引擎** ——
 * `FrameRenderer` 读 `p[98]` 决定用哪个 program，参数被清零只说明"filmic 参数失效了"，
 * 画面跑的仍是预设那个引擎。⇒ 判据 2/3 的 `[98]` 断言是核心，不是附带检查。
 *
 * ⛔ 零回归（阶段 3 判据 1）：档位 = 跟随预设时**逐位等价于阶段 3**；预设本来就是引擎 0
 * ⇒ 返回**同一实例**。⚠ P2 卡（用户 2026-10-14 拍板默认引擎 = sigmoid）之后，"读不到 `[98]`"
 * 的短数组**不再**等价于"引擎 0"：两侧（Kotlin 读取缺省 / C++ `fromDoubles` 缺槽）
 * 都按**默认档 1** 解释 ⇒ 短数组也走旁路（副本）。未注册 slot 仍是"按 0 处理、不旁路"。
 * 编辑器链路只走"跟随预设"这一档（`EditorEngineSession` 不传 override）：
 * 编辑一张照片要用它**拍摄时**的引擎。
 *
 * ⚠ 探针取值不得等于恒等值（§8.1 E5 的假绿陷阱）：`[20]/[21]/[22]/[28]` 的恒等值**都是 1.0**，
 * `[35]/[36]` 是 0.0，`[5..10]` 是 0.01，`[37]/[38]` 是 `FilmParams{}` 的默认黑白点 ——
 * 探针若撞上，"旁路生效"与"没生效"会给出同一个数组。逐槽守卫断言见 [forceSigmoidRewritesSlot98AndNeutralizes]。
 */
class EngineOverrideTest {

    /**
     * [EngineScope.NEUTRALIZE] 全表的探针值（14 槽，逐槽 **≠ 恒等值**）。
     * 表新增槽位时本用例会因键集不等而红 —— 提示补探针。
     */
    private val probes: Map<Int, Double> = mapOf(
        28 to 0.35,   // 恒等 1.0（⛔ 不是 0.0：那是引擎 1 空转，见 EngineScopeTest）
        20 to 2.5,    // 恒等 1.0
        21 to 0.3,    // 恒等 1.0
        22 to 0.42,   // 恒等 1.0
        36 to 0.75,   // 恒等 0.0
        35 to 1.0,    // 恒等 0.0（auto_tone：靠成员副作用生效，必须显式归零）
        5 to 0.6, 6 to 0.6, 7 to 0.6, 8 to 0.6, 9 to 0.6, 10 to 0.6,   // 恒等 0.01
        37 to -3.0,   // 恒等 -7.65
        38 to 2.0,    // 恒等 4.56
    )

    /** 阶段 2 基线（[CameraPresets.standard] = 全恒等）+ 指定的预设引擎槽位。 */
    private fun preset(engineSlot: Int): DoubleArray =
        CameraPresets.standard().also { it[98] = engineSlot.toDouble() }

    // ── 判据 1：跟随预设 + 预设引擎 0 ⇒ 同一实例（阶段 3 判据 1 零回归）────────────

    @Test
    fun followPresetWithFilmicPresetReturnsSameInstance() {
        val p = preset(0)
        // 故意把**所有**会被旁路的槽位写成非恒等值：跟随预设时一个都不许动
        for (s in EngineScope.NEUTRALIZE.keys) p[s] = -12345.0
        val before = p.copyOf()

        val out = engineScoped(p, ENGINE_OVERRIDE_FOLLOW_PRESET)

        assertSame("跟随预设 + 预设 0 ⇒ 同实例（不复制、不触碰任何槽）", p, out)
        assertArrayEquals("逐位不变（阶段 2 基线不许被本卡破坏）", before, out, 0.0)
    }

    // ── 判据 2：强制 sigmoid + 预设 filmic ⇒ 改写 [98] 且 filmic 参数旁路 ──────────

    @Test
    fun forceSigmoidRewritesSlot98AndNeutralizes() {
        val p = preset(0)
        for ((s, v) in probes) p[s] = v
        // 表外的相邻槽一并写成非恒等值：归零不得外溢
        p[40] = 0.7    // gamut（引擎 1 的段里没这个 uniform ⇒ 靠结构旁路，不入表）
        p[34] = 0.8    // LUT 强度（手动开关）
        val before = p.copyOf()

        val out = engineScoped(p, override = 1)

        assertNotSame("档位与预设不同 ⇒ 必须是副本", p, out)
        assertEquals(
            "⛔ [98] 必须真的写成 effective = 1：FrameRenderer 读 p[98] 决定 program —— " +
                "只旁路不改 [98] 的话引擎根本没换（只是 filmic 参数失效）",
            1.0, out[98], 0.0,
        )
        assertEquals("探针表必须覆盖 NEUTRALIZE 全表（表变了本用例要红）", probes.keys, EngineScope.NEUTRALIZE.keys)
        for ((s, v) in probes) {
            assertTrue("探针 $v 恰好等于 [$s] 的恒等值 ⇒ 本用例对该槽假绿", v != EngineScope.NEUTRALIZE.getValue(s))
            assertEquals(
                "[$s] 必须置恒等值（filmic 独占 + 公共段 + 编辑器曲线段都算旁路面）",
                EngineScope.NEUTRALIZE.getValue(s), out[s], 0.0,
            )
        }
        for (s in intArrayOf(40, 34)) {
            assertEquals("[$s] 在表外 ⇒ 不得被归零（归零外溢 = 误旁路）", before[s], out[s], 0.0)
        }
        assertArrayEquals(
            "调用方数组（filmParams / 滑杆持有其引用）不得被污染：[98] 是改在副本里的",
            before, p, 0.0,
        )
    }

    // ── 判据 3：强制 filmic + 预设 sigmoid ⇒ [98] = 0 且无槽被归零 ────────────────

    @Test
    fun forceFilmicRewritesSlot98AndKeepsEverySlot() {
        val p = preset(1)
        for ((s, v) in probes) p[s] = v
        p[40] = 0.7
        val before = p.copyOf()

        val out = engineScoped(p, override = 0)

        assertEquals(
            "⛔ [98] 必须写成 0 —— 否则 FrameRenderer 仍跑 sigmoid program（" +
                "只把 filmic 参数清零 = 用户看到的还是旧引擎）",
            0.0, out[98], 0.0,
        )
        assertNotSame(
            "要改 [98] 就必须是副本：强制到 0 时 applyEngineScope 按「0 = 不旁路」返回同一实例，" +
                "原地改会把调用方数组的引擎槽位永久改掉",
            p, out,
        )
        for ((s, v) in probes) {
            assertEquals("[$s] 在引擎 0 下照常生效 ⇒ 不得归零（防误旁路）", v, out[s], 0.0)
        }
        assertEquals("[40] gamut 同理", 0.7, out[40], 0.0)
        assertArrayEquals("调用方数组逐位不变", before, p, 0.0)

        // 唯一的差异就是引擎槽位本身（不是"一个副本里顺手改了别的"）
        assertArrayEquals("强制到 0 时唯一的改动就是 [98]", before.copyOf().also { it[98] = 0.0 }, out, 0.0)
    }

    @Test
    fun overrideEqualToPresetFallsBackToStageThreeBehaviour() {
        // 用户把档位设成"和预设一致"时不得有任何多余动作（也不得平白多一次复制语义变化）
        val zeroPreset = preset(0).also { it[28] = 0.35 }
        assertSame("强制 0 + 预设 0 ⇒ 仍是同实例（零回归）", zeroPreset, engineScoped(zeroPreset, 0))

        val onePreset = preset(1).also { it[28] = 0.35 }
        val out = engineScoped(onePreset, 1)
        assertArrayEquals(
            "强制 1 + 预设 1 ⇒ 与阶段 3 单参口径逐位相同（[98] 不被二次改写）",
            engineScoped(onePreset), out, 0.0,
        )
        assertEquals("[98] 保持 1.0", 1.0, out[98], 0.0)
        assertEquals("[28] 照常旁路", 1.0, out[28], 0.0)
    }

    // ── 判据 4：跟随预设 + 预设 sigmoid ⇒ [98] 保持 1（不许被改写）────────────────

    @Test
    fun followPresetWithSigmoidPresetKeepsSlot98Untouched() {
        val p = preset(1)
        p[28] = 0.35

        val out = engineScoped(p, ENGINE_OVERRIDE_FOLLOW_PRESET)

        assertEquals("[98] 必须保持 1.0（跟随预设 ⇒ 不改写、不产生新副本）", 1.0, out[98], 0.0)
        assertArrayEquals(
            "跟随预设 = 阶段 3 原路径（编辑器链路的口径也在这里）：逐位相同",
            engineScoped(p), out, 0.0,
        )
        assertNotSame("旁路仍要复制一份（阶段 3 语义不变）", p, out)
        assertEquals("[28] 旁路到 1.0", 1.0, out[28], 0.0)
    }

    // ── 判据 5：未注册档位 ⇒ 按 -1（跟随预设）处理 ───────────────────────────────

    @Test
    fun unregisteredOverrideFallsBackToPreset() {
        val unregistered = 7
        assertTrue("前提：$unregistered 必须是未注册档位", unregistered !in EngineScope.KNOWN_ENGINE_SLOTS)

        val one = preset(1)
        val oneOut = engineScoped(one, unregistered)
        assertArrayEquals(
            "未注册档位按「跟随预设」处理 ⇒ 与 -1 逐位相同",
            engineScoped(one), oneOut, 0.0,
        )
        assertEquals("…且不得把 [98] 改写成 7（否则 C++ 侧只能降级 + 记 WARN）", 1.0, oneOut[98], 0.0)

        val zero = preset(0)
        assertSame("未注册档位 + 预设 0 ⇒ 同实例", zero, engineScoped(zero, unregistered))
    }

    // ── ★ P2 卡：出厂默认模板上的三档语义（默认档 = 1）────────────────────────────

    /**
     * 三档开关 × **出厂默认模板**（`CameraPresets.standard()`：[98] = 1 = sigmoid）。
     *
     * 与 [forceSigmoidRewritesSlot98AndNeutralizes] / [forceFilmicRewritesSlot98AndKeepsEverySlot]
     * 的分工：那两条用"预设 0 / 预设 1"做输入，测的是**强制改写**；本条用默认模板，测的是
     * "用户什么都不动时三档各自给出什么画面"——即本卡的用户可见行为：
     *  - 跟随预设（缺省）⇒ sigmoid（**这就是本卡的改动本身**）；
     *  - 强制 0 ⇒ filmic，且**filmic 参数一个都不能被归零**（`[5]` 仍是 0.01）；
     *  - 强制 1 ⇒ 与跟随逐位相同（默认档本来就是 1 ⇒ 不产生多余的复制语义差异）。
     */
    @Test
    fun theThreeOverrideLevelsOnTheStockDefaultTemplate() {
        val stock = CameraPresets.standard()
        assertEquals("前提：出厂默认档 = 1", CameraPresets.DEFAULT_ENGINE_SLOT.toDouble(), stock[98], 0.0)

        val followed = engineScoped(stock, ENGINE_OVERRIDE_FOLLOW_PRESET)
        assertEquals("跟随预设 ⇒ 默认档 1（sigmoid）", 1.0, followed[98], 0.0)
        assertNotSame("…且走旁路（副本）", stock, followed)

        val forcedFilmic = engineScoped(stock, 0)
        assertEquals("强制 0 ⇒ filmic", 0.0, forcedFilmic[98], 0.0)
        assertEquals("强制 0 ⇒ filmic 参数不得被归零（[5] 仍是标准基线的 0.01）", 0.01, forcedFilmic[5], 0.0)

        assertArrayEquals(
            "强制 1 与跟随在默认模板上逐位相同（默认档就是 1 ⇒ 无多余改写）",
            followed, engineScoped(stock, 1), 0.0,
        )
    }

    // ── 判据 6：短数组（契约升级前的历史快照）不越界 ──────────────────────────────

    @Test
    fun shortArrayIsTreatedAsTheDefaultEngineWithoutOverrun() {
        val short = DoubleArray(50).also { it[36] = 0.5; it[5] = 0.5 }   // 读不到 [98]

        // ① 跟随预设 + 读不到 [98] ⇒ 按**默认档 1** 解释（P2 卡起；与 C++ 缺槽 = 1 同值）
        //    ⇒ 走旁路：副本 + 长度内的表内槽归零，且**不越界**（长度不变）。
        val followed = engineScoped(short, ENGINE_OVERRIDE_FOLLOW_PRESET)
        assertNotSame("缺 [98] ⇒ 默认档 1 ⇒ 必须走旁路（副本）", short, followed)
        assertEquals("长度必须原样带回（不越界写 [98]）", 50, followed.size)
        assertEquals("[36] 长度内 ⇒ 照常归零", 0.0, followed[36], 0.0)
        assertEquals("[5] 长度内 ⇒ 照常归零", EngineScope.NEUTRALIZE[5]!!, followed[5], 0.0)
        assertEquals("调用方数组不被污染", 0.5, short[36], 0.0)

        // ② 强制 1 = 与"缺 [98] 的默认档"同值 ⇒ 同一条路径（逐位相同）
        assertArrayEquals("强制 1 与默认档同值 ⇒ 结果逐位相同", followed, engineScoped(short, 1), 0.0)

        // ③ 强制 0（filmic）⇒ 引擎 0 不旁路；[98] 不在长度内 ⇒ 只复制、不写越界
        val forced0 = engineScoped(short, 0)
        assertNotSame("要改 [98] 就必须复制（长度内无可写处 ⇒ 只剩复制这一步）", short, forced0)
        assertEquals("长度不变", 50, forced0.size)
        assertEquals("[36] 引擎 0 下不得归零（防误旁路）", 0.5, forced0[36], 0.0)
        assertArrayEquals("调用方数组逐位不变", short, forced0, 0.0)
    }

    // ── 接缝：档位只许进拍照链（[98] 的写法与读法都必须收口）──────────────────────

    /**
     * 两条结构性纪律（本卡的"只做渲染侧、编辑器不接"是**设计约束**，不是实现细节）：
     *  1. prefs 键 `engine_override` 只有**一个**读点（`CameraController.engineScopedForCapture`）
     *     —— 多一处读点 = 多一处漂移面；
     *  2. `CameraController` 的 5 个拍照下发点**全部**走它，一个都不许裸调 `engineScoped(`
     *     （裸调 = 那一链悄悄退回"跟随预设"）；
     *  3. 编辑器链路必须裸调单参 `engineScoped(film)` —— 编辑一张照片要用它**拍摄时**的引擎，
     *     接了设置页档位就会"编辑旧照片突然变样"。
     */
    @Test
    fun overridePrefIsReadOnlyByTheCaptureChain() {
        val camera = File("src/main/java/com/filmkit/app/camera/CameraController.kt").readText()
        val editor = File("src/main/java/com/filmkit/app/camera/EditorEngineSession.kt").readText()

        assertEquals(
            "prefs 键只许有一个读点（engineScopedForCapture 内）",
            1, Regex("\"engine_override\"").findAll(camera).count(),
        )
        assertEquals(
            "裸 engineScoped( 在 CameraController 里只许是 engineScopedForCapture 的定义体",
            1, Regex("engineScoped\\(").findAll(camera).count(),
        )
        assertEquals(
            "拍照链 5 个下发点（预览帧 / submitStill / submitRaw / submitRawBurst×2）必须全覆盖：1 定义 + 5 调用",
            6, Regex("engineScopedForCapture\\(").findAll(camera).count(),
        )
        assertTrue(
            "编辑器链路必须仍是单参 engineScoped(film)",
            "engineScoped(film)," in editor,
        )
        assertEquals(
            "编辑器链路不得出现拍照链的档位入口",
            0, Regex("engineScopedForCapture").findAll(editor).count(),
        )
    }
}
