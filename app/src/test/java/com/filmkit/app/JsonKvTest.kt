package com.filmkit.app

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonKvTest {

    @Test
    fun `kv 构造扁平对象`() {
        assertEquals("{\"a\":1,\"b\":true,\"c\":\"x\"}", kv("a" to 1, "b" to true, "c" to "x"))
    }

    @Test
    fun `kv 转义引号与换行`() {
        assertEquals("{\"s\":\"a\\\"b\\\\c\\nd\"}", kv("s" to "a\"b\\c\nd"))
    }

    @Test
    fun `kv null 输出 null`() {
        assertEquals("{\"v\":null}", kv("v" to null))
    }

    @Test
    fun `kv 空参数输出空对象`() {
        assertEquals("{}", kv())
    }

    @Test
    fun `kv 浮点输出数字`() {
        assertEquals("{\"ms\":1.5}", kv("ms" to 1.5))
    }
}
