package com.filmkit.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 2026-11-13 真机回归：「标准」这条内置预设的修改必须能活过冷启动。
 *
 * 现场（logcat）：用户在「标准」里改 engine_slot + auto_white_pt 并保存，
 * `camera.preset.save_slots` 落了 2 个槽（slots=2），但同一毫秒的
 * `camera.preset.save_full` 读到 `target.slots=0`，界面与画面全回默认。
 *
 * 根因 = 「标准」是 [CAMERA_STANDARD_PRESET] 硬编码的出厂定义，**不在 `builtin.json` 里**，
 * 而 [applyBuiltinOverrides] 只对出厂列表里存在的 id 生效 ⇒ 覆盖写进去了却永远匹配不上。
 * 本测试钉住数据层的两条判据（拍照页的数据源见 `BuiltinPresets.forCamera`）。
 */
class CameraStandardOverrideTest {

    private val edited = CAMERA_STANDARD_PRESET.copy(
        slots = mapOf(98 to 0.0, 102 to 1.0), // [98] engine_slot=0、[102] auto_white_pt=1
    )

    @Test
    fun standardOverrideSurvivesRoundTrip() {
        val out = applyBuiltinOverrides(
            listOf(CAMERA_STANDARD_PRESET),
            mapOf(CAMERA_STANDARD_PRESET_ID to edited),
        )
        assertEquals(1, out.size)
        assertEquals("覆盖必须应用到「标准」", 2, out.single().slots.size)
        assertEquals(0.0, out.single().slots.getValue(98), 0.0)
        assertEquals(1.0, out.single().slots.getValue(102), 0.0)
        // 身份字段仍取出厂值：覆盖层不能把内置变成可删除的用户预设
        assertEquals(CAMERA_STANDARD_PRESET_ID, out.single().id)
        assertEquals(PRESET_SOURCE_BUILTIN, out.single().source)
    }

    @Test
    fun resolvePrefersListedStandardWithOverrides() {
        val listed = listOf(edited) + listOf(EditorPreset(id = "p_1", name = "用户"))
        val got = resolveCameraPreset(CAMERA_STANDARD_PRESET_ID, listed)
        assertSame("savedId = 标准时必须返回列表里那条（带覆盖）", edited, got)
    }

    @Test
    fun emptySavedIdAlsoResolvesListedStandard() {
        // ★ 真机路径：选中「标准」会把 camera_preset_id 删掉（标准 = 默认档不落盘）
        // ⇒ 冷启动恒走 savedId = null，这一支同样必须拿到覆盖版，否则重开还是空 slots。
        val listed = listOf(edited) + listOf(EditorPreset(id = "p_1", name = "用户"))
        assertSame("空 savedId 也要返回列表里的「标准+覆盖」", edited, resolveCameraPreset(null, listed))
        assertSame(edited, resolveCameraPreset("", listed))
    }

    @Test
    fun resolveFallsBackToFactoryStandard() {
        // 旧调用方（编辑器 / 查看页）列表里没有「标准」⇒ 仍回落硬编码出厂值，向后兼容
        val got = resolveCameraPreset(CAMERA_STANDARD_PRESET_ID, emptyList())
        assertSame(CAMERA_STANDARD_PRESET, got)
        assertSame(CAMERA_STANDARD_PRESET, resolveCameraPreset(null, emptyList()))
        assertSame(CAMERA_STANDARD_PRESET, resolveCameraPreset("gone", emptyList()))
    }
}
