package com.filmkit.app.ui

import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.applySnapshotParams
import com.filmkit.app.camera.editorOpenParams
import com.filmkit.app.camera.paramsUnchanged
import com.filmkit.app.camera.presetSnapshot
import com.filmkit.app.camera.presetsFromJson
import com.filmkit.app.camera.presetsToJson
import com.filmkit.app.camera.scalePresetSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ P-3（查看页「胶片」→ 选预设 → **直接进编辑器**）的纯逻辑护栏。
 *
 * 本卡的三条验收判据全部落在纯逻辑上（Compose 那几行跑不了单测，真机也要装包点半天）：
 *
 *  1. **预设列表读取**：查看页看到的就是 prefs 里的那套数据（[presetsFromJson] 是
 *     `PresetStore.load(prefs)` 的纯内核 —— `PresetStore` 自己只是"取字符串 + 打诊断日志"）。
 *     顺序 / 名称 / 分组 / 稀疏槽逐项钉住：列表读错了，用户点的那条就不是他要的风格。
 *  2. **回归哨兵**：`initialPresetId` 为空（或指向一条已不存在的预设）⇒ 编辑器**什么都不做**，
 *     与 P-3 之前逐位一致（起点 = [editorOpenParams] 的全恒等基线）。这条错了的表现是
 *     "普通「编辑」入口莫名其妙带上一条预设"，属于最难被发现的那类事故。
 *  3. **非空 ⇒ 参数 = 基线 + 预设稀疏覆盖**：编辑器里落地的那一行就是
 *     `applySnapshotParams(params, presetSnapshot(base, scalePresetSlots(slots, 1.0)))`
 *     （`applyPreset` → `applyPresetAt`）—— 这里逐槽断言"预设记下的槽 = 预设值、其余 = 基线值"，
 *     外加**顺序哨兵**：先基线、再预设。反过来（先预设、再基线）结果会与基线逐位相同 ⇒
 *     用户点了预设、画面还是原片。
 *
 * 基线一律用真实的 [editorOpenParams] / 真实的 JSON 往返，不手搓数组 —— 恒等值表与线格式都是
 * 被测对象（与 EditorPresetTest 同一规矩；那边测的是预设数据层本身，这里测的是**查看页这条
 * 入口链路**）。
 */
class PhotoViewPresetTest {

    /** 走真实线格式（`presets_v1` 的 JSON）构造一条预设，避免手搓 [EditorPreset] 绕过解析 */
    private fun stored(vararg items: EditorPreset): List<EditorPreset> =
        presetsFromJson(presetsToJson(items.toList())).presets

    private fun preset(
        id: String,
        name: String,
        group: String = "",
        slots: Map<Int, Double> = emptyMap(),
        lutId: String? = null,
        lutStrength: Double = 1.0,
    ) = EditorPreset(
        id = id, name = name, group = group, slots = slots,
        lutId = lutId, lutStrength = lutStrength,
    )

    // ── ① 预设列表读取（查看页面板看到的就是 prefs 里的那套）───────────────────

    @Test
    fun `预设列表读取保持存储顺序与字段`() {
        val list = stored(
            preset("p_1", "午后暖调", "我的胶片", slots = mapOf(0 to 0.5)),
            preset("p_2", "冷调夜景", "夜拍", slots = mapOf(4 to 1.2)),
        )
        assertEquals(2, list.size)
        // 顺序 = 存储顺序（最近保存的在最前，PresetStore.upsert 的语义）—— 查看页面板按这个顺序列
        assertEquals(listOf("p_1", "p_2"), list.map { it.id })
        assertEquals("午后暖调", list[0].name)
        assertEquals("我的胶片", list[0].group)
        assertEquals("夜拍", list[1].group)
        assertEquals(mapOf(0 to 0.5), list[0].slots)
        assertEquals(mapOf(4 to 1.2), list[1].slots)
    }

    @Test
    fun `空列表是合法状态而不是错误`() {
        // 查看页要在这里给出"还没有预设 · 在编辑器里保存"（ADR-12：不留点不动的按钮）
        assertTrue(presetsFromJson(null).presets.isEmpty())
        assertTrue(presetsFromJson("").presets.isEmpty())
        assertTrue(presetsFromJson(presetsToJson(emptyList())).presets.isEmpty())
    }

    // ── ② 回归哨兵：没有初始预设 ⇒ 编辑器与改前逐位一致 ────────────────────────

    @Test
    fun `普通编辑入口不套用任何预设`() {
        val presets = stored(preset("p_1", "暖调", slots = mapOf(0 to 0.5)))
        // 普通「编辑」入口：路由层传 null
        assertNull(initialPresetOf(presets, null))
        // 空列表 + 非空 id（理论上不会同时发生，但必须退化而不是崩 / 半套用）
        assertNull(initialPresetOf(emptyList(), "p_1"))
    }

    @Test
    fun `预设已不存在时退化成普通编辑入口`() {
        val presets = stored(preset("p_1", "暖调", slots = mapOf(0 to 0.5)))
        // 面板打开期间预设被删 / 进程重建后数据变了 ⇒ 找不到 ⇒ 调用方走原来的 rerender()
        assertNull(initialPresetOf(presets, "p_不存在的"))
        assertNull(initialPresetOf(presets, ""))
        // 反证：id 对得上时**必须**找得到（否则上面两条会因为"函数永远返回 null"而假通过）
        assertEquals("p_1", initialPresetOf(presets, "p_1")?.id)
    }

    // ── ③ 非空 ⇒ 参数 = 基线 + 预设稀疏覆盖 ───────────────────────────────────

    @Test
    fun `初始预设的参数等于基线加稀疏覆盖`() {
        val presets = stored(
            preset("p_1", "暖调", slots = mapOf(0 to 0.5, 4 to 1.2, 66 to -0.4)),
        )
        val picked = initialPresetOf(presets, "p_1")
        assertEquals("p_1", picked?.id)

        val base = editorOpenParams()          // 编辑器打开时的起点：全恒等基线
        val film = base.copyOf()
        // ★ 编辑器里的落地行（applyPreset → applyPresetAt），顺序：基线为底、预设稀疏覆盖
        applySnapshotParams(film, presetSnapshot(base, scalePresetSlots(picked!!.slots, 1.0)))

        // 预设记下的槽 = 预设值
        assertEquals(0.5, film[0], 0.0)      // 曝光
        assertEquals(1.2, film[4], 0.0)      // 对比度（恒等值 1.0）
        assertEquals(-0.4, film[66], 0.0)    // 暗角强度
        // 预设没记的槽 = **基线值**（不是 0、也不是别的预设的残留）
        assertEquals(base[5], film[5], 0.0)   // shoulder/latitude（恒等 0.01）
        assertEquals(base[11], film[11], 0.0) // cross（单位阵）
        assertEquals(base[37], film[37], 0.0) // filmic_black_ev（恒等 −7.65）
        // 顺序哨兵：反过来（先预设、再基线）结果会与基线逐位相同 ⇒ 这里必须**不同**
        assertNotEquals(base[0], film[0])
        // 套用后参数变了 ⇒ 导出走渲染而不是直通（E2-9 的既有判据自动生效，本卡没改）
        assertFalse(paramsUnchanged(film, base))
    }

    @Test
    fun `不含LUT的预设不动LUT强度槽`() {
        // 预设带 LUT 引用时，强度由 lutStrength 承载、**不进 slots**（[34] 归 applyPreset 的
        // LUT 分支下发）⇒ 打开编辑器套用这条预设时，[34] 仍等于基线（0 = 引擎整段旁路），
        // 不会出现"进编辑器就凭空套上一层 LUT"。
        val presets = stored(
            preset("p_1", "富士", slots = mapOf(0 to 0.3), lutId = "fuji_400", lutStrength = 1.2),
        )
        val picked = initialPresetOf(presets, "p_1")!!
        assertFalse("LUT 强度不该出现在 slots 里", picked.slots.containsKey(34))

        val base = editorOpenParams()
        val film = base.copyOf()
        applySnapshotParams(film, presetSnapshot(base, scalePresetSlots(picked.slots, 1.0)))
        assertEquals(0.0, base[34], 0.0)     // 基线口径（E2-21）
        assertEquals(base[34], film[34], 0.0)
    }

    // ── 端到端（纯逻辑）：查看页读到的那条 == 编辑器按 id 找到的那条 ─────────────

    @Test
    fun `查看页选中的预设进编辑器后按id找到的是同一条`() {
        val raw = presetsToJson(
            listOf(
                preset("p_1", "暖调", "我的胶片", slots = mapOf(0 to 0.5)),
                preset("p_2", "冷调", "夜拍", slots = mapOf(4 to 1.2), lutId = "night", lutStrength = 0.8),
            ),
        )
        val viewerList = presetsFromJson(raw).presets   // 查看页面板打开时读的
        val picked = viewerList[1]                      // 用户点了第二条
        val editorList = presetsFromJson(raw).presets   // 编辑器打开时再读一次（同一份 prefs）
        assertEquals(picked, initialPresetOf(editorList, picked.id))
        assertEquals(picked.slots, initialPresetOf(editorList, picked.id)?.slots)
    }
}
