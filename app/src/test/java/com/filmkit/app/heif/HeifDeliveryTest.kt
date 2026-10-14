package com.filmkit.app.heif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三级回退状态机（卡 FIX2 追加块）的 JVM 判据：**顺序**与**逐级留痕**。
 *
 * 三条路径各一条测试（硬判据）：
 *  ① 10bit 成功 ⇒ 不碰 8bit、无失败日志；
 *  ② 10bit 失败 ⇒ 8bit 成功（必须留下 `photo.heif8_queued`，证明"没有跳过 8bit 那一级"）；
 *  ③ 两级都失败 ⇒ 落 JPEG（`from=8bit`），且三级日志齐。
 * 另外覆盖"空数组算失败"（绝不交付可疑文件）与"8bit 抛异常文本必须进日志"。
 */
class HeifDeliveryTest {

    private val logs = mutableListOf<Triple<Int, String, String>>()

    private fun log(level: Int, event: String, fields: String) {
        logs += Triple(level, event, fields)
    }

    private fun events() = logs.map { it.second }
    private fun fieldsOf(event: String) = logs.first { it.second == event }.third

    @Test
    fun `10bit 成功不碰 8bit 那一级`() {
        val r = HeifDelivery.run(
            attempt10 = { byteArrayOf(1, 2, 3) },
            attempt8 = { error("10bit 成功时不得尝试 8bit") },
            log = ::log,
        )
        val ten = r as? HeifDelivery.Result.TenBit
        assertTrue("必须是 10bit 结果，实际 $r", ten != null)
        assertEquals(listOf<Byte>(1, 2, 3), ten!!.bytes.toList())
        assertTrue("成功路径不该有任何失败/回退日志：$logs", logs.isEmpty())
    }

    @Test
    fun `10bit 失败转 8bit 成功——必须留下 heif8_queued`() {
        val r = HeifDelivery.run(
            attempt10 = { error("csd-0 不是合法 HEVCDecoderConfigurationRecord") },
            attempt8 = { byteArrayOf(9, 9, 9, 9) },
            log = ::log,
        )
        val eight = r as? HeifDelivery.Result.EightBit
        assertTrue("必须是 8bit 结果，实际 $r", eight != null)
        assertEquals(listOf<Byte>(9, 9, 9, 9), eight!!.bytes.toList())
        assertEquals(
            "顺序必须是 10bit 失败 → 8bit 尝试",
            listOf("photo.heif_failed", "photo.heif8_queued"), events(),
        )
        assertTrue("10bit 失败原因必须进日志", fieldsOf("photo.heif_failed").contains("HEVCDecoderConfigurationRecord"))
        assertTrue("必须标明是从 10bit 落下来的", fieldsOf("photo.heif8_queued").contains("\"from\":\"10bit\""))
        assertTrue("queued 里要带 10bit 的失败原因", fieldsOf("photo.heif8_queued").contains("reason"))
    }

    @Test
    fun `两级都失败落 JPEG 且 from 为 8bit`() {
        val r = HeifDelivery.run(
            attempt10 = { error("no Main10 encoder") },
            attempt8 = { ByteArray(0) },   // 空数组 = 失败（不交付可疑文件）
            log = ::log,
        )
        val jpeg = r as? HeifDelivery.Result.Jpeg
        assertTrue("必须是 JPEG 结果，实际 $r", jpeg != null)
        assertEquals("8bit", jpeg!!.from)
        assertEquals("empty", jpeg.reason)
        assertEquals(
            "三级必须逐级留痕（不允许 10bit 失败后直接 JPEG）",
            listOf("photo.heif_failed", "photo.heif8_queued", "photo.heif8_failed"), events(),
        )
        assertTrue("8bit 失败日志要标明 from", fieldsOf("photo.heif8_failed").contains("\"from\":\"8bit\""))
    }

    @Test
    fun `10bit 返回空数组同样算失败`() {
        val r = HeifDelivery.run({ ByteArray(0) }, { byteArrayOf(7) }, ::log)
        assertTrue("空数组不得当成功交付", r is HeifDelivery.Result.EightBit)
        assertEquals("empty", fieldsOf("photo.heif_failed").substringAfter("\"error\":\"").substringBefore('"'))
    }

    @Test
    fun `8bit 抛异常时异常文本必须进日志`() {
        val r = HeifDelivery.run(
            attempt10 = { error("boom10") },
            attempt8 = { error("boom8: MediaMuxer addTrack failed") },
            log = ::log,
        )
        val jpeg = r as? HeifDelivery.Result.Jpeg
        assertTrue(jpeg != null)
        assertEquals("boom8: MediaMuxer addTrack failed", jpeg!!.reason)
        assertTrue("异常文本必须进日志（铁律）", fieldsOf("photo.heif8_failed").contains("addTrack failed"))
    }

    @Test
    fun `失败日志的 level 是 ERROR、queued 是 WARN`() {
        HeifDelivery.run({ error("x") }, { byteArrayOf(1) }, ::log)
        assertEquals(3, logs.first { it.second == "photo.heif_failed" }.first)   // EngineBridge.LEVEL_ERROR
        assertEquals(2, logs.first { it.second == "photo.heif8_queued" }.first)  // EngineBridge.LEVEL_WARN
    }

    // ── HEIF ⇄ 4:4:4 互斥（追加块）：唯一判定入口的守卫 ────────────────────────

    @Test
    fun `HEIF 色度恒 4 2 0 且不受 jpeg_444 影响`() {
        assertEquals("HEIF 交付的色度真源", "420", HeifChroma.CHROMA)
        assertEquals("8bit 档引用同一真源", HeifChroma.CHROMA, Heif8Encoder.CHROMA)
        assertFalse("开关开 ⇒ HEIF 仍是 4:2:0", HeifChroma.honorsJpeg444(true))
        assertFalse("开关关 ⇒ HEIF 仍是 4:2:0", HeifChroma.honorsJpeg444(false))
    }
}
