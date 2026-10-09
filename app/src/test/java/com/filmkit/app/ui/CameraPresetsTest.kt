package com.filmkit.app.ui

import org.junit.Assert.assertEquals
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
        // E2-11：88 → 96（一次扩 8 槽，ADR-14 裁决 2）。此断言是 v0.10.196 启动崩溃的哨兵。
        assertEquals(96, CameraPresets.COUNT)
        assertEquals(CameraPresets.COUNT, CameraPresets.standard().size)
    }

    @Test
    fun e2_11SlotsHoldNoEffectDefaults() {
        // E2-11 护栏：新增的 8 个槽位在 neutral() 里必须全是恒等值 ——
        // 任一非 0 都会让"编辑器无修改导出"悄悄改图（ADR-14 硬约束 2）。
        val p = CameraPresets.standard()
        for (i in 88..95) assertEquals("slot[$i]", 0.0, p[i], 0.0)
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
