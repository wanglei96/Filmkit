package com.filmkit.app.ui

import com.filmkit.app.camera.CAMERA_PATH_SLOTS
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.cameraPresetParams
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ PS-6b 完整参数编辑页的纯逻辑护栏（用户 2026-10-12："点击**完整参数**跳转类似**图1**页面
 * 提供**完整、专业调节**，做好**参数分组提高屏幕利用率**"）。
 *
 * 这份测试盯四件事（正是任务点名的四条纯逻辑）：
 *  1. **基础 / 高级分组**：基础 = LR Basic 那 10 条、高级 = 全部可调参数 + 只读组；
 *  2. **网格排布**：一行 [FULL_GRID_COLUMNS] 格、末行可不满、组内不丢格；
 *  3. **重置口径**：回到**这条预设的原值**（与 `cameraPresetParams(preset)` 逐槽相等），
 *     **不是**中性基线 —— 这是本卡最容易做错的一处（点一下"重置"把整条预设抹平）；
 *  4. **保存口径**：有改动才可点、按 id 取最新对象、写回只含用户改过的槽 + LUT 两字段。
 *
 * 另有两条**源码哨兵**（UI 不可在 JVM 跑，但"有没有接线 / 有没有复制第二份参数表"能看源码）：
 *  - `CameraScreen.kt` 真的挂载了本页、并把入口接到预设面板的「完整参数 >」（`onOpenFullEdit`）；
 *  - `CameraPresetFullEdit.kt` **只引用**既有控件（`EditorSlider` / `StrengthRow` / `LutCard` /
 *    `SheetPill`）且**不出现** `EditorParam(` 构造 / 量程常量 / 槽位换算函数（= 没有第二份参数表）。
 */
class CameraPresetFullEditTest {

    private fun preset(vararg slots: Pair<Int, Double>): EditorPreset =
        EditorPreset(id = "p_test", name = "测试预设", slots = slots.toMap())

    // ── ① 基础 / 高级分组 ─────────────────────────────────────────────────────

    @Test
    fun basicTabIsTheLightroomBasicTen() {
        val secs = fullParamSections(FullParamTab.BASIC, emptySet())
        assertEquals(listOf("调整"), secs.map { it.title })
        val cells = secs.flatMap { it.rows.flatten() }
        assertEquals(10, cells.size)
        // 顺序 = 参数表顺序（AdjustParams 的决策主干），不是集合的哈希顺序
        assertEquals(BASIC_PARAM_KEYS.toList(), cells.map { it.key })
        assertTrue("基础档每一项都必须有滑杆", cells.all { it.param != null })
        // 只在高级档出现的那些：白平衡通道 / 胶片曲线 / 分离色调 / 质感
        for (k in listOf("wbR", "curveShoulder", "splitWarm", "haloStrength", "vigStrength", "fade")) {
            assertFalse("$k 不该进基础档", cells.any { it.key == k })
        }
    }

    @Test
    fun advancedTabCoversEveryTunableParamPlusReadonly() {
        val secs = fullParamSections(FullParamTab.ADVANCED, setOf(70))
        assertEquals(listOf("调整", "色彩", "质感", "只读"), secs.map { it.title })
        val tunable = secs.dropLast(1).flatMap { it.rows.flatten() }
        // 29 = AdjustParams 15 + ColorParams 4 + TextureParams 10 里"拍照链路真的可调"的那些
        assertEquals(29, CAMERA_TUNABLE_PARAMS.size)
        assertEquals(29, tunable.size)
        assertTrue(tunable.all { it.param != null })
        // 高级 ⊃ 基础（切档不丢项）
        assertTrue(tunable.map { it.key }.containsAll(BASIC_PARAM_KEYS))
        // 只读组 = 无滑杆的 6 槽 + 这条预设包含的、拍照链路不读的槽（[70] 层间效应）
        val ro = secs.last().rows.flatten()
        assertEquals((CAMERA_NO_SLIDER_SLOTS + 70).sorted(), ro.map { it.slot })
        assertTrue("只读格不许带滑杆", ro.all { it.param == null })
        assertEquals("拍照生效 · 暂无滑杆", cameraParamAdjustabilityNote(26))
        assertEquals("仅编辑器生效 · 拍照不变", cameraParamAdjustabilityNote(70))
        // 只读格的名字必须来自既有的详情表（不新造译名）
        assertEquals("颗粒 · 尺寸", ro.first { it.slot == 27 }.label)
    }

    @Test
    fun readonlyGroupExcludesLutStrengthAndEmptyPresetStillShowsNoSliderSlots() {
        // [34] LUT 强度由顶部 LUT 卡片 + 滤镜强度滑杆承载 ⇒ 不进只读组（与 presetDetailSections 同口径）
        assertEquals(CAMERA_NO_SLIDER_SLOTS.sorted(), fullEditReadonlySlots(setOf(34)))
        // 空预设 ⇒ 只读组恰好 = 无滑杆的那 6 槽（其余"拍照不读"的槽不该凭空出现）
        assertEquals(CAMERA_NO_SLIDER_SLOTS.sorted(), fullEditReadonlySlots(emptySet()))
        // 拍照链路读的槽不该落进只读组（它们要么可调、要么已在 NO_SLIDER 里）
        assertTrue(CAMERA_NO_SLIDER_SLOTS.all { it in CAMERA_PATH_SLOTS })
    }

    // ── ② 网格排布（一行 4 格 = 用户要的"提高屏幕利用率"）──────────────────────

    @Test
    fun gridIsFourCellsPerRowAndKeepsEveryCell() {
        val secs = fullParamSections(FullParamTab.ADVANCED, emptySet())
        for (s in secs) {
            assertTrue("$s 有空格子行", s.rows.all { it.isNotEmpty() })
            assertTrue("一行超过 $FULL_GRID_COLUMNS 格", s.rows.all { it.size <= FULL_GRID_COLUMNS })
            assertEquals(
                "切行不能丢格",
                s.rows.flatten().size,
                s.rows.sumOf { it.size },
            )
        }
        val adjust = secs.first()
        assertEquals(15, adjust.rows.flatten().size)
        assertEquals(4, adjust.rows.size)                       // 15 项 ⇒ 4/4/4/3
        assertEquals(listOf(4, 4, 4, 3), adjust.rows.map { it.size })
        assertEquals(1, secs[1].rows.size)                      // 色彩 4 项 ⇒ 1 行
        assertEquals(3, secs[2].rows.size)                      // 质感 10 项 ⇒ 3 行
        // 每格的 key 唯一（选中态按 key 走，重复 key 会让两格一起高亮）
        val keys = secs.flatMap { it.rows.flatten() }.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun cellValueComesFromTheSharedReadout() {
        val film = CameraPresets.standard()
        val cell = fullParamSections(FullParamTab.BASIC, emptySet())
            .first().rows.first().first()
        assertEquals("曝光", cell.label)
        // 读数 = EditorParam 自己的 format(read(数组))，与编辑器 / 详情页同一个函数
        assertEquals(cell.param!!.format(cell.param!!.read(film)), fullParamCellValue(cell, film))
        // 只读格走 PresetDetail 的既有格式化（颗粒尺寸 = ISO 400）
        val size = fullParamSections(FullParamTab.ADVANCED, emptySet())
            .last().rows.flatten().first { it.slot == 27 }
        assertEquals("ISO 400", fullParamCellValue(size, film))
    }

    // ── ③ 重置口径：回到**这条预设的原值** ────────────────────────────────────

    @Test
    fun resetReturnsToThePresetsOwnValuesNotToNeutralBaseline() {
        val p = preset(0 to 0.5, 4 to 1.2, 66 to 0.3)
        val edits = CameraParamOverrides(p.id, mapOf(0 to 1.0, 78 to 0.4), setOf(66))
        assertEquals(1.0, cameraPresetParams(p, edits.values, edits.removed)[0], 1e-9)
        assertEquals(0.0, cameraPresetParams(p, edits.values, edits.removed)[66], 1e-9)

        val reset = resetFullParamOverrides(edits)!!
        assertEquals(p.id, reset.presetId)          // 归属不变（防串到别的预设）
        assertTrue(reset.isEmpty)
        assertFalse(fullEditSaveEnabled(reset))     // 没有改动了 ⇒ 保存 / 重置同时置灰
        // 机器判据：重置后的生效参数与"这条预设自己的配方"逐槽相等
        assertArrayEquals(cameraPresetParams(p), cameraPresetParams(p, reset.values, reset.removed), 0.0)
        // 且**不是**中性基线（否则用户点一下重置就把整条预设抹平了）
        assertTrue(abs(cameraPresetParams(p)[4] - CameraPresets.standard()[4]) > 1e-9)
        // 没有改动 ⇒ 重置是 no-op（null 进 null 出，界面据此置灰）
        assertNull(resetFullParamOverrides(null))
    }

    // ── ④ 保存口径 ────────────────────────────────────────────────────────────

    @Test
    fun saveIsEnabledOnlyWithPendingEdits() {
        assertFalse(fullEditSaveEnabled(null))
        assertFalse(fullEditSaveEnabled(CameraParamOverrides("p_test")))
        assertTrue(fullEditSaveEnabled(CameraParamOverrides("p_test", mapOf(0 to 1.0))))
        assertTrue(fullEditSaveEnabled(CameraParamOverrides("p_test", removed = setOf(4))))
    }

    @Test
    fun saveTargetFollowsTheIdSoARefreshDoesNotLoseTheLastSave() {
        val old = preset(4 to 1.2)
        val fresh = old.copy(slots = mapOf(4 to 1.5))
        assertEquals(fresh, fullEditSaveTarget(old, listOf(fresh)))
        // 预设刚被删（列表里找不到）⇒ 回落打开时那个对象，而不是崩 / 静默不存
        assertEquals(old, fullEditSaveTarget(old, emptyList()))
    }

    @Test
    fun saveWritesOnlyTheEditedSlotsAndKeepsLutFieldsInSync() {
        val p = preset(4 to 1.2, 78 to 0.4)
        val edits = CameraParamOverrides(p.id, mapOf(0 to 1.0, 78 to 0.9), setOf(4))
        // 参数口径 = cameraPresetSlotsFor（唯一实现）：改的进、移除的掉、没碰的不动
        assertEquals(mapOf(0 to 1.0, 78 to 0.9), cameraPresetSlotsFor(p, edits.values, edits.removed))
        // LUT 两字段：没选 LUT ⇒ 强度回落 1.0（与 newCameraPresetFrom / updatedCameraPreset 同口径）
        assertEquals(null to 1.0, fullEditLutFields(null, 1.4))
        assertEquals("u_x" to 1.4, fullEditLutFields("u_x", 1.4))
        assertFalse("没选 LUT 时强度与预设无关", fullEditLutChanged(p, null, 1.4))
        assertTrue(fullEditLutChanged(p, "u_x", 1.0))
        assertFalse(fullEditLutChanged(p.copy(lutId = "u_x", lutStrength = 1.4), "u_x", 1.4))
    }

    // ── ⑤ 源码哨兵（接线 + 复用不许复制）──────────────────────────────────────

    @Test
    fun screenMountsTheFullEditPageAndHandsTheEntryToThePresetPanel() {
        val s = File("src/main/java/com/filmkit/app/ui/CameraScreen.kt").readText()
        assertTrue("完整参数页没有被挂载", s.contains("CameraPresetFullEdit("))
        assertTrue("入口回调没有接到预设面板的「完整参数 >」", s.contains("onOpenFullEdit = {"))
        assertTrue("保存没有接上", s.contains("saveCameraFullEdit("))
        // 用**自绘覆盖层**而不是 M3 ModalBottomSheet：后者真机返回要按两次才关（项目已有裁决）
        assertFalse("全屏页不许用 ModalBottomSheet", s.contains("ModalBottomSheet("))
    }

    @Test
    fun fullEditPageReusesSharedControlsInsteadOfACopy() {
        val s = File("src/main/java/com/filmkit/app/ui/CameraPresetFullEdit.kt").readText()
        for (shared in listOf("EditorSlider(", "StrengthRow(", "LutCard(", "SheetPill(", "cameraParamAdjustability(")) {
            assertTrue("没有复用 $shared", s.contains(shared))
        }
        assertFalse("不许在这里造第二份参数表", s.contains("EditorParam("))
        assertFalse("量程必须引用参数表", s.contains("_UI_RANGE"))
        assertFalse("槽位换算必须引用既有函数", s.contains("Slot("))
    }
}
