package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-1 护栏：[FilmParamKeys]（槽位 ↔ 语义名）必须与 `FilmParams.h` **逐字一致**。
 *
 * 为什么值得一个测试：这张表是**线格式的键名来源**，两类错误都不会被"自己的往返测试"发现 ——
 *  1. **名字写错一个字符**（`grain_amout`）：存下去还能读回来（读写用同一张表），但别的工具
 *     与将来版本的迁移函数认不出它；
 *  2. **名字与槽位对错位**（把 `wb_g` 连到槽 3）：表现是"套预设后颜色静默错位"，比崩溃难查。
 *
 * 三方对拍（照 [EditorStyleWhitelistTest] 的做法；单测工作目录 = `app/`）：
 *  1. **本表** [FilmParamKeys.bySlot] = 全代码唯一维护点；
 *  2. **权威定义** = `../core/engine/src/film/FilmParams.h` 的 `float <名字> = …; // <槽位>`
 *     （数组字段按 `float x[N] = {…}; // <起>..<末>` 展开成 `x[下标]`）；
 *  3. **策略层** [presetSlotIncluded]（"哪些槽进预设"）—— 只要求**单向**："可入库 ⇒ 有语义名"。
 *     反方向不成立（[30]/[35]/[36]/[39..59]/[79..87] 有名字但不进预设），这是设计不是缺陷。
 */
class FilmParamKeysTest {

    /**
     * `FilmParams.h` 的权威抽取：`float <名字> = …; // <槽位>`（标量）与
     * `float <名字>[N] = {…}; // <起>..<末>`（数组，逐元素展开成 `名字[i]`）。
     *
     * ⚠ `[^;\n]*` **不跨行**：该文件的注释里出现过 `float latitude = 0.01f)`（引用
     * `FrameRenderer.h` 的默认参数），跨行匹配会把它当成一个声明、再连到后面某行的 `// 5..7`
     * 上 ⇒ 凭空多出一个假条目。
     */
    private fun declaredSlots(): Map<Int, String> {
        val src = File("../core/engine/src/film/FilmParams.h").readText()
        val re = Regex(
            """float\s+(\w+)(?:\[(\d+)])?\s*=\s*(?:\{[^}\n]*}|[^;\n]*);\s*//\s*(\d+)(?:\.\.(\d+))?""",
        )
        val out = LinkedHashMap<Int, String>()
        re.findAll(src).forEach { m ->
            val name = m.groupValues[1]
            val len = m.groupValues[2]
            val start = m.groupValues[3].toInt()
            val end = m.groupValues[4]
            if (len.isEmpty()) {
                assertTrue("标量声明不该带 `..` 区间：${m.value}", end.isEmpty())
                out[start] = name
            } else {
                val n = len.toInt()
                assertEquals("数组声明的区间长度必须等于下标数：${m.value}", n, end.toInt() - start + 1)
                for (i in 0 until n) out[start + i] = "$name[$i]"
            }
        }
        return out
    }

    /** ① ★ 双向一致：名→槽→名恒等；表内无重复槽位、无重复名字 */
    @Test
    fun mappingIsBijective() {
        val bySlot = FilmParamKeys.bySlot
        assertEquals("名字不得重复（重复 = 两条槽位抢一个键，往返必错）", bySlot.size, bySlot.values.toSet().size)
        assertEquals("byName 必须与 bySlot 同规模", bySlot.size, FilmParamKeys.byName.size)
        bySlot.forEach { (slot, name) ->
            assertEquals("名→槽→名必须恒等：$name", slot, FilmParamKeys.byName.getValue(name))
            assertEquals("槽→名→槽必须恒等：$slot", name, bySlot.getValue(slot))
        }
    }

    /** ② ★ 槽位号与 `FilmParams.h` 的 `// NN` 注释逐项一致（名字也逐字一致） */
    @Test
    fun slotsMatchFilmParamsHeader() {
        val declared = declaredSlots()
        assertTrue("抽取器没读到任何槽位 ⇒ 正则或文件路径坏了", declared.size > 90)
        assertEquals(
            "FilmParamKeys.bySlot 必须 == FilmParams.h 里有名字的槽位（名字逐字一致）",
            declared,
            FilmParamKeys.bySlot,
        )
    }

    /** ③ 除 [29] 外**每个**契约槽位都有语义名（[29] 是已删除的 hl_recon，索引留空不复用） */
    @Test
    fun everySlotHasANameExceptTheRetiredOne() {
        val named = (0 until CameraPresets.COUNT).filter { FilmParamKeys.bySlot.containsKey(it) }
        assertEquals(
            "除 [29] 外每个槽位都必须有语义名（新增契约槽位时同步加进 FilmParamKeys）",
            (0 until CameraPresets.COUNT).filter { it != 29 },
            named,
        )
        assertFalse("[29] 无字段名 ⇒ 不该被映射", FilmParamKeys.bySlot.containsKey(29))
        assertFalse(FilmParamKeys.byName.containsKey("hl_recon"))
    }

    /** ④ ★ 单向策略约束：**能进预设的槽必然有语义名**（否则序列化时会静默丢数据） */
    @Test
    fun everyStorableSlotHasASemanticName() {
        val orphans = (0 until CameraPresets.COUNT).filter {
            presetSlotIncluded(it) && !FilmParamKeys.bySlot.containsKey(it)
        }
        assertTrue("这些槽可入库却没有语义名（存得进去、写不出来）⇒ 必须补映射或排除：$orphans", orphans.isEmpty())
    }

    /** ⑤ 数组字段的展开规则（机械可校验，别改成 R/G/B 这类只存在于注释里的后缀） */
    @Test
    fun arrayFieldsExpandByIndex() {
        assertEquals("shoulder = latitude R/G/B", "shoulder[0]", FilmParamKeys.bySlot[5])
        assertEquals("shoulder[2]", FilmParamKeys.bySlot[7])
        assertEquals("toe = latitude R/G/B", "toe[0]", FilmParamKeys.bySlot[8])
        assertEquals("cross = 3×3 染料串扰", "cross[0]", FilmParamKeys.bySlot[11])
        assertEquals("cross[8]", FilmParamKeys.bySlot[19])
        assertEquals("interlayer_shadow R/G/B", "interlayer_shadow[0]", FilmParamKeys.bySlot[69])
        assertEquals("interlayer_high[2]", FilmParamKeys.bySlot[77])
        // 标量字段：名字就是字段名本身
        assertEquals("exposure_ev", FilmParamKeys.bySlot[0])
        assertEquals("grain_amount", FilmParamKeys.bySlot[26])
        assertEquals("vignette_strength", FilmParamKeys.bySlot[66])
        assertEquals("reserved_95（预留槽，有字段名 ⇒ 在表内）", "reserved_95", FilmParamKeys.bySlot[95])
    }
}
