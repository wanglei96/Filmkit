package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2-3 编辑器「基础」组滑杆的纯逻辑护栏：五类参数的 UI ↔ 槽位映射 + 重置 + 快照 → 滑杆初值。
 *
 * 为什么只测这些：滑杆拖动、面板展开、引擎渲染都要真机（Compose + GL），JVM 单测跑不了。
 * 这里守住三条最容易悄悄错、且错了用户直接看得见的事实：
 *  1. **对比度端点禁用 0**：[4] = 0 会被 `FilmParams::fromDoubles` 静默兜底成 1.0 ⇒ UI 值域
 *     下限必须是 0.01，且槽位为 0/负时要**如实显示 100%**（否则就是 ADR-12 的假滑杆）；
 *  2. **饱和度一个滑杆同写 [20][21][22] 三槽**（引擎当前读 [21]，将来改分区读取不许出现
 *     "滑杆只改了一档"）；
 *  3. 读数往返不漂（用户松手后读数不能跳）+ 恒等值正确（曝光 0 / WB 1 / 对比度 1 /
 *     饱和度 1 / 清晰度 0 = 关）。
 */
class EditorBasicUiTest {

    // ── 曝光 [0]：UI ±100% = ±3 EV ──────────────────────────────────────────

    @Test
    fun exposureMapsFullScaleToThreeEv() {
        assertEquals(0.0, expSlot(0f), 1e-9)
        assertEquals(3.0, expSlot(1f), 1e-9)
        assertEquals(-3.0, expSlot(-1f), 1e-9)
        // 越界夹住：手势产生不了越界值，但坏快照/未来改量程时不该把非法值带进引擎
        assertEquals(3.0, expSlot(2f), 1e-9)
        assertEquals(-3.0, expSlot(-9f), 1e-9)
        // 槽位 → UI（+3 EV 显示 +100%）
        assertEquals(1f, expUi(3.0), 1e-6f)
        assertEquals(-1f, expUi(-3.0), 1e-6f)
        assertEquals(0f, expUi(0.0), 1e-6f)
        // 坏数据 = 恒等（不能把 NaN 带进引擎：渲染整段变 NaN）
        assertEquals(0f, expUi(Double.NaN), 0f)
        assertEquals(0f, expUi(Double.POSITIVE_INFINITY), 0f)
        assertEquals(1f, expUi(99.0), 1e-6f)
    }

    @Test
    fun exposureRoundTripKeepsEveryReadout() {
        // 读数（−100..100）→ 槽位 → 读数 必须原样回来：否则用户松手后读数会跳
        for (i in -100..100) {
            val ui = i / 100f
            val back = (expUi(expSlot(ui)) * 100f).roundToInt()
            assertEquals("曝光读数 $i% 往返漂了", i, back)
        }
    }

    // ── 白平衡 [1..3]：50%..200%，恒等 100% ────────────────────────────────

    @Test
    fun wbGainReadsAsPercentDirectly() {
        // 增益语义本身就是倍率：1.0 → 显示 100%（不是 50%，也不是 200%）
        assertEquals(1.0, wbGainSlot(1f), 1e-9)
        assertEquals(2.0, wbGainSlot(2f), 1e-9)
        assertEquals(0.5, wbGainSlot(0.5f), 1e-9)
        assertEquals(1f, wbGainUi(1.0), 1e-6f)
        assertEquals(2f, wbGainUi(2.0), 1e-6f)
        // 量程 0.5..2.0（ADR §5.6④）：越界夹住
        assertEquals(2.0, wbGainSlot(9f), 1e-9)
        assertEquals(0.5, wbGainSlot(0f), 1e-9)
        assertEquals(2f, wbGainUi(999.0), 1e-6f)
        assertEquals(0.5f, wbGainUi(-1.0), 1e-6f)
        // 坏数据 = 恒等 1.0（= 100% 不变）
        assertEquals(1f, wbGainUi(Double.NaN), 0f)
    }

    @Test
    fun wbGainRoundTripKeepsEveryReadout() {
        for (i in 50..200) {
            val ui = i / 100f
            val back = (wbGainUi(wbGainSlot(ui)) * 100f).roundToInt()
            assertEquals("白平衡读数 $i% 往返漂了", i, back)
        }
    }

    // ── 对比度 [4]：**端点禁用 0**（本卡最容易踩的陷阱）─────────────────────

    @Test
    fun contrastNeverProducesZeroSlot() {
        // UI 0% 会被夹到 0.01：0 在引擎侧是"全画面同一灰度"的非法值，fromDoubles 会兜底成 1.0
        assertEquals(0.01, contrastSlot(0f), 1e-9)
        assertEquals(0.01, contrastSlot(0.01f), 1e-9)
        assertEquals(0.01, contrastSlot(-3f), 1e-9)
        assertEquals(1.0, contrastSlot(1f), 1e-9)
        assertEquals(2.0, contrastSlot(2f), 1e-9)
        assertEquals(2.0, contrastSlot(5f), 1e-9)
    }

    @Test
    fun zeroContrastSlotShowsIdentityNotExtremeLow() {
        // 槽位 0/负 = 引擎实际按 1.0 跑（fromDoubles 兜底）⇒ UI 必须显示 100% 不变，
        // 显示 1% 就是"读数说极端低对比、画面却没变"的假滑杆
        assertEquals(1f, contrastUi(0.0), 0f)
        assertEquals(1f, contrastUi(-0.5), 0f)
        assertEquals(1f, contrastUi(Double.NaN), 0f)
        // 正常值照常映射
        assertEquals(1f, contrastUi(1.0), 1e-6f)
        assertEquals(0.01f, contrastUi(0.01), 1e-6f)
        assertEquals(2f, contrastUi(2.0), 1e-6f)
        assertEquals(2f, contrastUi(999.0), 1e-6f)
    }

    @Test
    fun contrastRoundTripKeepsEveryReadout() {
        // 1..200（0 不可达：UI 下限就是 0.01）
        for (i in 1..200) {
            val ui = i / 100f
            val back = (contrastUi(contrastSlot(ui)) * 100f).roundToInt()
            assertEquals("对比度读数 $i% 往返漂了", i, back)
        }
    }

    // ── 饱和度 [20..22]：一个滑杆同写三槽 ─────────────────────────────────

    @Test
    fun saturationWritesAllThreeSlots() {
        val p = CameraPresets.standard()
        writeSaturation(p, 1.5f)
        // ★ 三槽必须完全一致（引擎读 [21]，将来可能改分区读取）
        assertEquals(1.5, p[20], 1e-9)
        assertEquals(1.5, p[21], 1e-9)
        assertEquals(1.5, p[22], 1e-9)
        // 越界夹到 0..2
        writeSaturation(p, 5f)
        assertEquals(2.0, p[20], 1e-9)
        assertEquals(2.0, p[21], 1e-9)
        assertEquals(2.0, p[22], 1e-9)
        writeSaturation(p, -1f)
        assertEquals(0.0, p[20], 1e-9)
        assertEquals(0.0, p[21], 1e-9)
        assertEquals(0.0, p[22], 1e-9)
        // 恒等：UI 100% ⇒ 三槽都是 1.0（= 默认值，零回归）
        writeSaturation(p, 1f)
        assertEquals(1.0, p[20], 1e-9)
        assertEquals(1.0, p[21], 1e-9)
        assertEquals(1.0, p[22], 1e-9)
    }

    @Test
    fun saturationRoundTripKeepsEveryReadout() {
        for (i in 0..200) {
            val ui = i / 100f
            val back = (satUi(satSlot(ui)) * 100f).roundToInt()
            assertEquals("饱和度读数 $i% 往返漂了", i, back)
        }
        // 坏数据 = 恒等 1.0（= 100% 不变）
        assertEquals(1f, satUi(Double.NaN), 0f)
        assertEquals(2f, satUi(9.0), 1e-6f)
    }

    // ── 清晰度 [78]：0 = 关 ───────────────────────────────────────────────

    @Test
    fun clarityIsOffAtZeroAndCapsAtOne() {
        assertEquals(0.0, claritySlot(0f), 1e-9)
        assertEquals(1.0, claritySlot(1f), 1e-9)
        assertEquals(1.0, claritySlot(3f), 1e-9)
        assertEquals(0.0, claritySlot(-1f), 1e-9)
        assertEquals(0f, clarityUi(0.0), 0f)
        assertEquals(1f, clarityUi(1.0), 1e-6f)
        assertEquals(1f, clarityUi(5.0), 1e-6f)
        // 坏数据 = 关（0 才是恒等；清晰度没有"负值"这一档）
        assertEquals(0f, clarityUi(Double.NaN), 0f)
    }

    @Test
    fun clarityRoundTripKeepsEveryReadout() {
        for (i in 0..100) {
            val ui = i / 100f
            val back = (clarityUi(claritySlot(ui)) * 100f).roundToInt()
            assertEquals("清晰度读数 $i% 往返漂了", i, back)
        }
    }

    // ── 重置 + 快照初值 ───────────────────────────────────────────────────

    @Test
    fun resetBasicRestoresIdentityAndLeavesOtherSlotsAlone() {
        val p = CameraPresets.standard()
        p[0] = 1.5      // 曝光 +1.5 EV
        p[1] = 1.4      // WB R
        p[2] = 0.8      // WB G
        p[3] = 1.1      // WB B
        p[4] = 0.6      // 对比度
        p[20] = 1.8; p[21] = 1.8; p[22] = 1.8   // 饱和度
        p[78] = 0.7     // 清晰度
        p[66] = -0.5    // 暗角（**不属于基础组**，重置不该碰）
        p[26] = 0.3     // 颗粒（同上）
        p[34] = 0.9     // LUT 强度（同上）

        resetBasicParams(p)

        assertEquals(0.0, p[0], 1e-9)
        assertEquals(1.0, p[1], 1e-9)
        assertEquals(1.0, p[2], 1e-9)
        assertEquals(1.0, p[3], 1e-9)
        assertEquals(1.0, p[4], 1e-9)
        assertEquals(1.0, p[20], 1e-9)
        assertEquals(1.0, p[21], 1e-9)
        assertEquals(1.0, p[22], 1e-9)
        assertEquals(0.0, p[78], 1e-9)
        // 重置 = 只回基础五类的恒等值，别的组一个都不动
        assertEquals(-0.5, p[66], 1e-9)
        assertEquals(0.3, p[26], 1e-9)
        assertEquals(0.9, p[34], 1e-9)
    }

    @Test
    fun openParamsExposeBasicValuesAsSliderStarts() {
        // 快照 = 这张成片当初真正下发给引擎的参数（E1-2 口径）
        val snap = CameraPresets.standard()
        snap[0] = 1.5    // +1.5 EV ⇒ 滑杆 +50%
        snap[1] = 1.2    // WB 红 120%
        snap[2] = 0.9    // WB 绿 90%
        snap[3] = 1.05   // WB 蓝 105%
        snap[4] = 0.6    // 对比度 60%
        snap[20] = 1.4; snap[21] = 1.4; snap[22] = 1.4   // 饱和度 140%
        snap[78] = 0.35  // 清晰度 35%
        val p = editorOpenParams(StillSnapshot(snap, "kodak_portra", 0.85, 0L))

        assertEquals(50, (expUi(p[0]) * 100f).roundToInt())
        assertEquals(120, (wbGainUi(p[1]) * 100f).roundToInt())
        assertEquals(90, (wbGainUi(p[2]) * 100f).roundToInt())
        assertEquals(105, (wbGainUi(p[3]) * 100f).roundToInt())
        assertEquals(60, (contrastUi(p[4]) * 100f).roundToInt())
        assertEquals(140, (satUi(p[21]) * 100f).roundToInt())
        assertEquals(35, (clarityUi(p[78]) * 100f).roundToInt())
        // 契约长度不变；[34] 仍被强制 0（打开编辑器 = 原片本身）
        assertEquals(CameraPresets.COUNT, p.size)
        assertEquals(0.0, p[34], 0.0)
    }

    @Test
    fun openParamsWithoutSnapshotFallBackToIdentitySliders() {
        val p = editorOpenParams(null)
        // 无快照 = 标准基线：五条滑杆全部落在恒等读数（曝光 0% / WB 100% / 对比度 100% /
        // 饱和度 100% / 清晰度 0%）—— 用户打开面板看到的是"什么都没改"
        assertEquals(0, (expUi(p[0]) * 100f).roundToInt())
        assertEquals(100, (wbGainUi(p[1]) * 100f).roundToInt())
        assertEquals(100, (wbGainUi(p[2]) * 100f).roundToInt())
        assertEquals(100, (wbGainUi(p[3]) * 100f).roundToInt())
        assertEquals(100, (contrastUi(p[4]) * 100f).roundToInt())
        assertEquals(100, (satUi(p[21]) * 100f).roundToInt())
        assertEquals(0, (clarityUi(p[78]) * 100f).roundToInt())
    }

    @Test
    fun everySlotStaysInsideAdrRange() {
        // 硬规格（ADR 裁决段）：滑杆无论怎么拖，落到槽位的值都不许越界
        val probes = listOf(-9f, -1f, -0.01f, 0f, 0.5f, 1f, 1.5f, 2f, 9f)
        for (ui in probes) {
            assertTrue("曝光越界: $ui", expSlot(ui) in -3.0..3.0)
            assertTrue("WB 越界: $ui", wbGainSlot(ui) in 0.5..2.0)
            assertTrue("对比度越界: $ui", contrastSlot(ui) in 0.01..2.0)
            assertTrue("对比度产生了 0（引擎会兜底成 1.0）: $ui", contrastSlot(ui) > 0.0)
            assertTrue("饱和度越界: $ui", satSlot(ui) in 0.0..2.0)
            assertTrue("清晰度越界: $ui", claritySlot(ui) in 0.0..1.0)
        }
    }
}
