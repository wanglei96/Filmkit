package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E1-4 编辑器纯逻辑护栏：参数起点映射（集成点 I-3）与导出命名（ADR-3）。
 *
 * 为什么只测这两块：MediaStore 写入 / Bitmap 解码 / GL 渲染都需要真机（Android 依赖），
 * 在 JVM 单测里跑不了 —— 不为测试去改设计（任务卡明确要求）。这里守住的是"参数不会
 * 因为快照畸形而变成半份/错值"和"导出命名规则不会漂"这两条静态事实。
 */
class EditorExportTest {

    @Test
    fun noSnapshotFallsBackToStandardBaseline() {
        val p = editorParams(null)
        assertEquals(CameraPresets.COUNT, p.size)
        assertArrayEquals(CameraPresets.standard(), p, 0.0)
    }

    @Test
    fun snapshotSlotsWinAndMissingSlotsKeepBaseline() {
        // 契约升级（60 → 78 这类）前落盘的老快照：只有前 60 槽。
        // 语义 = 快照里**存在**的槽位一律优先，**缺的**槽位补基线值 ——
        // 不能补 0：[5..10] 在 latitude 语义下 0 = 极端 S 曲线（不是"无效果"）。
        val old = DoubleArray(60)
        old[1] = 1.0
        old[2] = 1.0
        old[3] = 1.0
        old[27] = 1.0 // 老语义的 grain_size（此时 [26]=0，颗粒关，值本身不生效）
        old[34] = 0.5 // 当初的 LUT 强度
        val p = editorParams(old)
        assertEquals(CameraPresets.COUNT, p.size)
        assertEquals("快照里的强度必须保留", 0.5, p[34], 0.0)
        assertEquals("快照里的颗粒开关保留（0 = 关，ADR-10 红线）", 0.0, p[26], 0.0)
        // [60..77] 是新槽位（老快照里不存在）⇒ 必须是基线值
        val base = CameraPresets.standard()
        for (i in 60 until CameraPresets.COUNT) {
            assertEquals("slot[$i] 缺省必须取基线", base[i], p[i], 0.0)
        }
    }

    @Test
    fun nonFiniteSnapshotValuesAreIgnored() {
        // 坏快照（NaN/Inf）当作"没记录"：保留基线值，不能把 NaN 带进引擎
        val bad = CameraPresets.standard()
        bad[34] = Double.NaN
        bad[26] = Double.POSITIVE_INFINITY
        val p = editorParams(bad)
        assertEquals(CameraPresets.standard()[34], p[34], 0.0)
        assertEquals(CameraPresets.standard()[26], p[26], 0.0)
    }

    @Test
    fun oversizedSnapshotIsTruncatedToContract() {
        // 反向防呆：比契约长的数组只取前 COUNT 槽（不会把越界值带进来）
        val long = DoubleArray(CameraPresets.COUNT + 5) { 2.0 }
        val p = editorParams(long)
        assertEquals(CameraPresets.COUNT, p.size)
        assertEquals(2.0, p[0], 0.0)
    }

    @Test
    fun exportNameKeepsShootingNamingWithEditMarker() {
        // 命名规则 = 拍摄链路同款时间戳 + `_edit` 标记（ADR-3：另存的新片要与原片可区分）。
        // 用正则断言格式而非固定字符串：SimpleDateFormat 依赖本机时区，写死时间会随 CI 时区挂。
        val name = editorExportName(1_760_000_000_000L)
        assertTrue("实际=$name", Regex("^FILMKIT_\\d{8}-\\d{6}_edit\\.jpg$").matches(name))
        assertTrue(name.startsWith("FILMKIT_"))
        assertTrue(name.endsWith("_edit.jpg"))
        // 不同秒必须给出不同名字（同秒重名由 MediaStore 自动加 (1)，与拍摄链路一致）
        assertNotEquals(editorExportName(1_760_000_000_000L), editorExportName(1_760_000_001_000L))
    }
}
