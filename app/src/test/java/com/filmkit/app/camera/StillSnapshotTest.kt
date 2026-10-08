package com.filmkit.app.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 快照里 FilmParams 的编解码单测（E1-2 / ADR-4）。
 *
 * 只测**纯逻辑**那层：整个 JSON 文档要走 `org.json`，而 JVM 单测里 org.json 不可用
 *（见 `JsonKv.kt` 顶部说明）。生产链路的两侧分别是：
 * - 写入：`sanitizeParams`（净化）→ `JSONArray.put(double)`（结构化写入，无解析歧义）；
 * - 读取：`JSONArray.toString()` → `decodeParams`（解析）。
 *
 * 所以这里覆盖的是**净化与解析**这两个纯逻辑步骤；中间那两步是 org.json 自己的行为
 *（`JSONArray.put(double)` 的序列化即 `Double.toString`，与下面往返用例构造文本的方式
 * 一致）。没被覆盖的只剩 prefs 读写与 JSON 文档结构。
 */
class StillSnapshotTest {

    /** 与 org.json 序列化同形的文本（`JSONArray.put(double)` 内部就是 Double.toString） */
    private fun jsonOf(p: DoubleArray): String =
        StillSnapshotStore.sanitizeParams(p).joinToString(",", "[", "]")

    @Test
    fun `整槽参数经净化与解析后逐位还原`() {
        // 长度只是样例（编解码与长度无关，契约 kCount 变动不影响本测试）
        val p = DoubleArray(78) { i -> (i - 39) * 0.125 }
        p[0] = -7.65
        p[34] = 1.0
        p[37] = 4.56
        val back = StillSnapshotStore.decodeParams(jsonOf(p))
        assertArrayEquals(p, back!!, 0.0)
    }

    @Test
    fun `科学计数法与负数可解析`() {
        assertArrayEquals(
            doubleArrayOf(1.0, -0.5, 2.0E-5),
            StillSnapshotStore.decodeParams("[1.0,-0.5,2.0E-5]")!!,
            0.0,
        )
    }

    @Test
    fun `非有限值净化成 0 而不是让写入抛异常`() {
        // JSON 没有 NaN/Infinity，JSONArray.put(double) 会抛 JSONException ⇒ 必须净化
        assertArrayEquals(
            doubleArrayOf(0.0, 0.0, 0.0, 1.5),
            StillSnapshotStore.sanitizeParams(
                doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.5),
            ),
            0.0,
        )
        assertEquals(0, StillSnapshotStore.decodeParams(jsonOf(DoubleArray(0)))!!.size)
    }

    @Test
    fun `畸形输入返回 null 而不是半份参数`() {
        assertNull(StillSnapshotStore.decodeParams(""))
        assertNull(StillSnapshotStore.decodeParams("1.0,2.0"))
        assertNull(StillSnapshotStore.decodeParams("[1.0,2.0"))
        assertNull(StillSnapshotStore.decodeParams("1.0,2.0]"))
        assertNull(StillSnapshotStore.decodeParams("[1.0,abc]"))
        assertNull(StillSnapshotStore.decodeParams("[1.0,null]"))
    }

    @Test
    fun `容忍空白`() {
        assertArrayEquals(
            doubleArrayOf(1.0, 2.0),
            StillSnapshotStore.decodeParams(" [ 1.0 , 2.0 ] ")!!,
            0.0,
        )
    }
}
