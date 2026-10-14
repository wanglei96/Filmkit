package com.filmkit.app.ui

import com.filmkit.app.camera.EngineScope
import com.filmkit.app.camera.presetSlotIncluded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E4-1 契约护栏：槽位长度 + 新槽位的"无效果"默认值表。
 *
 * 为什么值得一个测试：v0.10.196 的真实事故 = C++ `FilmParams::kCount` 与 Kotlin
 * `DoubleArray(N)` 长度不同步 ⇒ 下发点写越界 ⇒ **装包后应用启动即崩**。长度与默认值
 * 都是静态事实，用断言守住远比等真机崩便宜。C++ 侧的 kCount 由 `FilmParams.h` 的
 * 注释 + `fromDoubles` 的 `if (n > idx)` 同步（JVM 单测读不到 C++ 常量，这半边靠审）。
 */
class CameraPresetsTest {

    @Test
    fun lengthMatchesContract() {
        // 阶段 0：98 → 99（[98] engine_slot）。
        // 此断言是 v0.10.196 启动崩溃的哨兵。
        assertEquals(99, CameraPresets.COUNT)
        assertEquals(CameraPresets.COUNT, CameraPresets.standard().size)
    }

    @Test
    fun e2_11SlotsHoldNoEffectDefaults() {
        // E2-11 护栏：新增的 8 个槽位在 neutral() 里必须全是恒等值 ——
        // 任一非 0 都会让"编辑器无修改导出"悄悄改图（ADR-14 硬约束 2）。
        // SG-1：区间收为 88..94（[95] 已从"预留"变为 glow_strength，见下个用例）。
        val p = CameraPresets.standard()
        for (i in 88..94) assertEquals("slot[$i]", 0.0, p[i], 0.0)
    }

    @Test
    fun sg1SoftGlowSlotsHoldNoEffectDefaults() {
        // SG-1 柔光护栏（契约扩展的第五处同步点：C++ kCount / neutral() /
        // fromDoubles / setFilmParams 兜底 / FilmParamKeys 语义名）。
        // ⚠️ 判据与 E4-1 的 [62] halation 同款：**[95] 必须为 0 = 整链旁路** ——
        //    非 0 会让所有用户成片立刻带上柔光（成片外观回归）。
        //    [96][97] 是"效果参数"（0.35 / 0.70），不是恒等值，但 [95] = 0 时不可达。
        val p = CameraPresets.standard()
        assertEquals("slot[95] glow_strength 必须为 0（整链旁路）", 0.0, p[95], 0.0)
        assertEquals("slot[96] glow_radius", 0.35, p[96], 0.0)
        assertEquals("slot[97] glow_threshold", 0.70, p[97], 0.0)
    }

    @Test
    fun defaultEngineSlotIsSigmoidAndFilmicStaysAValidSlot() {
        // ★ P2 卡（用户 2026-10-14 拍板）：默认影调引擎 **filmic → sigmoid**。
        //   用户原话（`docs/rec2020-heif-design.md` §1b P2 行）："个人观感默认拍照效果全面好于
        //   filmic；filmic 后期可能想删，本期先留"。
        //   本断言 = 默认档的机器判据；C++ 侧同一件事 = `FilmParams.h` 的
        //   `float engine_slot = 1.0f;`（跨文件对拍见 Stage3EngineSlotPresetAuditTest）。
        val p = CameraPresets.standard()
        assertEquals(
            "slot[98] engine_slot 的默认档必须 = 1（内置 sigmoid）",
            CameraPresets.DEFAULT_ENGINE_SLOT.toDouble(), p[98], 0.0,
        )
        assertEquals(
            "默认档常量本身（与 C++ `FilmParams.h` 的默认值同值；缺 [98] 的读取缺省也读它）",
            1, CameraPresets.DEFAULT_ENGINE_SLOT,
        )
        // `[98] = 0` 仍是**合法档位**（filmic 未删、行为未改）：
        //   "引擎 0 路径逐字节零回归"判据保留为守卫（离线链 `film_d5.npy` crc32 `0x589f8364`），
        //   它是将来删 filmic 时的安全网。⇒ 注册表里 0 与 1 都在。
        assertTrue("0（filmic）与 1（sigmoid）都必须仍在注册表里", 0 in EngineScope.KNOWN_ENGINE_SLOTS)
        assertTrue("1（sigmoid）必须是默认档", CameraPresets.DEFAULT_ENGINE_SLOT in EngineScope.KNOWN_ENGINE_SLOTS)
        // ★ 设计决策（架构师 2026-10-13 拍板，与 [PRESET_ENGINE_KNOB_SLOTS] 相反）：
        //   **engine_slot 属于「口味」而非「引擎状态」** —— 阶段 3 验收要求"预设 schema 支持
        //   engine_slot + 私有 KV"（`docs/engine-slots-design.md` §8），即每条预设各自记住
        //   自己的引擎。这与 [28][30..33][35][36]（标定 / 预览辅助 / 色彩科学状态）不同：
        //   那些是"引擎内部状态"，不是用户选择。故不加入任何排除集合。
        assertTrue(
            "slot[98] engine_slot 必须进预设（阶段 3 的预设 schema 要带引擎选择）",
            presetSlotIncluded(98),
        )
    }

    @Test
    fun newSlotsHoldNoEffectDefaults() {
        val p = CameraPresets.standard()
        // [5..10] = filmic latitude：必须是 0.01（0 在 latitude 语义下 = 过渡区紧贴
        // 中灰 = 极端 S 曲线，**不是**"无效果"）；0.01 = 引擎当前的硬编码默认。
        for (i in 5..10) assertEquals("slot[$i]", 0.01, p[i], 0.0)
        // [26][27] 颗粒：amount 必须为 0（ADR-10：给非零 = 所有用户成片立刻带颗粒）
        assertEquals(0.0, p[26], 0.0)
        assertEquals(400.0, p[27], 0.0)
        // [60..68]：颗粒调制（amount = 0 时不可达）/ halation 关 / 暗角关
        assertEquals(100.0, p[60], 0.0)
        assertEquals(0.0, p[61], 0.0)
        assertEquals(0.0, p[62], 0.0)
        assertEquals(0.80, p[63], 0.0)
        assertEquals(0.45, p[64], 0.0)
        assertEquals(0.40, p[65], 0.0)
        assertEquals(0.0, p[66], 0.0)
        assertEquals(80.0, p[67], 0.0)
        assertEquals(50.0, p[68], 0.0)
        // [69..77] 层间效应增益 = 1.0（恒等；0 在这里是"全黑"，是 X300 那类事故的同型坑）
        for (i in 69..77) assertEquals("slot[$i]", 1.0, p[i], 0.0)
        // [78..87] E2-2：清晰度必须为 0（= 整段跳过）；局部调整（E2-6 才实现）的
        // 默认值也必须是"无效果"组合 —— 尤其 [87] local_sat = 1.0（0 = 全去饱和）、
        // [80][81] = 0.5（中心）、[86] = 0（无曝光变化）。
        assertEquals(0.0, p[78], 0.0)
        assertEquals(0.0, p[79], 0.0)   // local_type = 关
        assertEquals(0.5, p[80], 0.0)
        assertEquals(0.5, p[81], 0.0)
        assertEquals(0.35, p[82], 0.0)
        assertEquals(0.5, p[83], 0.0)
        assertEquals(0.0, p[84], 0.0)
        assertEquals(0.25, p[85], 0.0)
        assertEquals(0.0, p[86], 0.0)
        assertEquals(1.0, p[87], 0.0)
    }
}
