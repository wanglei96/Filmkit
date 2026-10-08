package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2-1 编辑器参数面板的纯逻辑护栏：暗角 UI ↔ 槽位映射 + 快照 → 滑杆初值。
 *
 * 为什么只测这些：滑杆拖动、面板展开、引擎渲染都要真机（Compose + GL），JVM 单测跑不了。
 * 这里守住两条最容易悄悄错、且错了用户直接看得见的事实：
 *  1. **用户看不到负数**（UI 0..100% ↔ 槽位 −1..0），往返不丢读数、方向不写反；
 *  2. 打开编辑器时初值 = 参数快照（含暗角三条），且 [34] 恒 0（画面 = 原片，E2-1 拍板）。
 */
class EditorVignetteUiTest {

    @Test
    fun strengthMapsZeroToOffAndHundredToStrongest() {
        // UI 0% = 关（槽位 0 ⇒ 引擎整段跳过）；100% = 最强（槽位 −1，越负越暗）
        assertEquals(0.0, vigStrengthSlot(0f), 1e-9)
        assertEquals(-1.0, vigStrengthSlot(1f), 1e-9)
        // 越界夹住：手势产生不了越界值，但坏快照/未来改量程时不该把非法值带进引擎
        assertEquals(-1.0, vigStrengthSlot(2f), 1e-9)
        assertEquals(0.0, vigStrengthSlot(-0.5f), 1e-9)
    }

    @Test
    fun strongerUiMeansMoreNegativeSlot() {
        // 方向单调：UI 越大 ⇒ 槽位越负（越暗）。写反 = "越调越亮"，属于本卡最致命的错
        assertTrue(vigStrengthSlot(0.2f) > vigStrengthSlot(0.8f))
        assertTrue(vigStrengthSlot(0.8f) > -1.0)
    }

    @Test
    fun strengthRoundTripKeepsEveryReadout() {
        // 读数（0..100 的整数）→ 槽位 → 读数 必须原样回来：否则用户松手后读数会跳
        for (i in 0..100) {
            val ui = i / 100f
            val back = (vigStrengthUi(vigStrengthSlot(ui)) * 100f).roundToInt()
            assertEquals("强度读数 $i% 往返漂了", i, back)
        }
    }

    @Test
    fun positiveOrBadStrengthIsTreatedAsOff() {
        // 正槽位 = 提亮四角（不是暗角），本 UI 永不产生；快照里出现时按"关"显示，
        // 不能把它画到滑杆上（那会让用户以为自己在做暗角）
        assertEquals(0f, vigStrengthUi(0.5), 0f)
        assertEquals(0f, vigStrengthUi(Double.NaN), 0f)
    }

    @Test
    fun radiusAndFeatherReadAsPercentDirectly() {
        // 半径/羽化的槽位语义本身就是百分比（darktable `scale` / `falloff_scale`）：
        // 80 → 0.8 → 读数 "80%"（不是 40%，也不是 8000%）
        assertEquals(0.8f, vigRadiusUi(80.0), 1e-6f)
        assertEquals(80.0, vigRadiusSlot(0.8f), 1e-3)
        assertEquals(0.5f, vigFeatherUi(50.0), 1e-6f)
        assertEquals(50.0, vigFeatherSlot(0.5f), 1e-3)
        // 量程 0..200%（CameraPresets 里 [67]/[68] 声明 0..200）
        assertEquals(2f, vigRadiusUi(200.0), 1e-6f)
        assertEquals(200.0, vigRadiusSlot(2f), 1e-3)
        // 越界/坏数据
        assertEquals(2f, vigRadiusUi(999.0), 1e-6f)
        assertEquals(200.0, vigFeatherSlot(9f), 1e-3)
        assertEquals(0f, vigRadiusUi(-5.0), 1e-6f)
        assertEquals(0f, vigFeatherUi(-5.0), 1e-6f)
    }

    @Test
    fun openParamsTakeVignetteFromSnapshotAndForceLutOff() {
        // 快照 = 这张成片当初真正下发给引擎的参数 + LUT 选择与强度（E1-2 口径）
        val snapParams = CameraPresets.standard()
        snapParams[34] = 0.85   // 原片当初用的 LUT 强度
        snapParams[66] = -0.36  // 暗角强度 36%
        snapParams[67] = 120.0  // 半径 120%
        snapParams[68] = 30.0   // 羽化 30%
        val p = editorOpenParams(StillSnapshot(snapParams, "kodak_portra", 0.85, 0L))

        // 暗角三条来自快照 ⇒ 滑杆初值就是这张片子当初的值（36% / 120% / 30%）
        assertEquals(36, (vigStrengthUi(p[66]) * 100f).roundToInt())
        assertEquals(120, (vigRadiusUi(p[67]) * 100f).roundToInt())
        assertEquals(30, (vigFeatherUi(p[68]) * 100f).roundToInt())
        // ★ [34] 强制 0：打开编辑器看到的 = 原片本身（不再把烘焙过的 LUT 又叠一遍）
        assertEquals(0.0, p[34], 0.0)
        // 其余槽位照旧从快照继承（比如 LUT 之外的风格参数），契约长度不变
        assertEquals(CameraPresets.COUNT, p.size)
    }

    @Test
    fun openParamsWithoutSnapshotFallBackToStandardBaseline() {
        val p = editorOpenParams(null)
        assertEquals(CameraPresets.COUNT, p.size)
        // 无快照 = 标准基线：暗角 0 / 80 / 50（ADR-4 的回退口径）
        assertEquals(0, (vigStrengthUi(p[66]) * 100f).roundToInt())
        assertEquals(80, (vigRadiusUi(p[67]) * 100f).roundToInt())
        assertEquals(50, (vigFeatherUi(p[68]) * 100f).roundToInt())
        assertEquals(0.0, p[34], 0.0)
        // editorOpenParams 只动 [34]，基线其余槽位一个都没改（ADR-10：颗粒必须仍是 0）
        val base = CameraPresets.standard()
        for (i in base.indices) {
            if (i == 34) continue
            assertEquals("slot[$i] 不该被编辑器初值改动", base[i], p[i], 0.0)
        }
    }
}
