package com.filmkit.app.heif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「HEIF ⇄ 4:4:4」自动关/恢复的三态判据（卡 FIX2 追加块，用户实机行为对齐）。
 *
 * ① 切 HEIF ⇒ `jpeg_444` 变 false 且原值被记住；
 * ② 切回 JPEG ⇒ **精确恢复**（含"原值本来是 false"的边界）；
 * ③ 备份键缺失/类型非法 ⇒ 回落默认且不抛（设置页不许因为脏键崩）。
 *
 * 变异检验（报告 §5 ③）：把 [HeifJpeg444.onJpeg] 的恢复改成写死 [HeifJpeg444.DEFAULT]
 * ⇒ ② 与"原值 false"边界必须变红。
 */
class HeifJpeg444Test {

    /** Map fake：类型不符时抛 ClassCastException（模拟真机 SharedPreferences 的类型校验）。 */
    private class FakePrefs : HeifJpeg444.Prefs {
        val map = mutableMapOf<String, Any?>()
        override fun getBoolean(key: String, def: Boolean): Boolean = when (val v = map[key]) {
            null -> def
            is Boolean -> v
            else -> throw ClassCastException("$key is ${v::class.java.name}")
        }

        override fun has(key: String) = map.containsKey(key)
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun `切到 HEIF 关掉 444 并记住原值`() {
        val p = FakePrefs()
        p.putBoolean(HeifJpeg444.KEY, true)
        val now = HeifJpeg444.onHeif(p)
        assertFalse("切 HEIF 后开关必须是关的", now)
        assertFalse(p.getBoolean(HeifJpeg444.KEY, true))
        assertTrue("原值(true)必须被记住", p.getBoolean(HeifJpeg444.KEY_BACKUP, false))
        assertTrue(HeifJpeg444.hasBackup(p))
    }

    @Test
    fun `切回 JPEG 精确恢复——原值 true`() {
        val p = FakePrefs()
        p.putBoolean(HeifJpeg444.KEY, true)
        HeifJpeg444.onHeif(p)
        val back = HeifJpeg444.onJpeg(p)
        assertTrue("必须恢复成 true", back)
        assertTrue(p.getBoolean(HeifJpeg444.KEY, false))
        assertFalse("备份键必须清掉（不留脏状态）", p.has(HeifJpeg444.KEY_BACKUP))
    }

    @Test
    fun `切回 JPEG 精确恢复——原值本来是 false`() {
        val p = FakePrefs()
        p.putBoolean(HeifJpeg444.KEY, false)
        HeifJpeg444.onHeif(p)
        val back = HeifJpeg444.onJpeg(p)
        assertFalse("用户本来就关着 ⇒ 恢复后仍是 false（不许被改成默认 true）", back)
        assertFalse(p.getBoolean(HeifJpeg444.KEY, true))
    }

    @Test
    fun `连续切档不覆盖备份`() {
        val p = FakePrefs()
        p.putBoolean(HeifJpeg444.KEY, true)
        HeifJpeg444.onHeif(p)
        HeifJpeg444.onHeif(p) // 第二次（例如用户在设置页反复切）
        assertTrue("备份键是原始值，不该被 false 覆盖", p.getBoolean(HeifJpeg444.KEY_BACKUP, false))
        assertFalse(p.getBoolean(HeifJpeg444.KEY, true))
    }

    @Test
    fun `备份键缺失或非法时回落默认且不抛`() {
        val missing = FakePrefs()
        assertEquals("缺失 ⇒ 默认 true", true, HeifJpeg444.onJpeg(missing))

        val dirty = FakePrefs()
        dirty.map[HeifJpeg444.KEY_BACKUP] = "yes"   // 类型非法
        assertEquals("非法 ⇒ 默认 true，不抛", true, HeifJpeg444.onJpeg(dirty))
        assertTrue(dirty.getBoolean(HeifJpeg444.KEY, false))

        val dirtyKey = FakePrefs()
        dirtyKey.map[HeifJpeg444.KEY] = 444          // 主键类型非法 ⇒ 备份回落默认，也不许抛
        HeifJpeg444.onHeif(dirtyKey)
        assertTrue(dirtyKey.getBoolean(HeifJpeg444.KEY_BACKUP, false))
    }

    @Test
    fun `HEIF 档的色度口径与 444 无关（回顾守卫）`() {
        assertEquals("420", HeifChroma.CHROMA)
        assertFalse(HeifChroma.honorsJpeg444(true))
    }
}
