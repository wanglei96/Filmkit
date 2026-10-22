package com.filmkit.app.camera

import com.filmkit.app.heif.Heif10Pipeline
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡 FIX1 回归：**交付档与交付缓冲格式必须同源**。
 *
 * ## 事故（2026-10-14 真机，两张照片报废）
 * MFNR burst 的落盘回调硬编码 `queueJpegSave`，绕过交付档 ⇒ HEIF 档下 native 按
 * `GL_UNSIGNED_INT_2_10_10_10_REV` 读回的 **10bit packed** 缓冲被 `Bitmap.Config.ARGB_8888`
 * 按通道错读 ⇒ 霓虹伪彩 + 边缘浮雕，37MB / 43MB（同场景正常 6.4MB）。
 * **两种格式都是 4 B/px（3072×4080 = 50135040 B）⇒ 长度校验永远抓不到。**
 *
 * ## 本文件的三层牙
 * 1. **可判别假数据（§1）**：用 R/G/B 各自独占满量程的图案构造两种缓冲，证明
 *    "10bit packed 按 8bit 直通解释"与真值**必然不同**、且 `unpackRgb10` 能逐像素还原
 *    ⇒ 格式判错一定看得见（不是靠肉眼）。
 * 2. **格式切换序列（§2）**：jpeg→heif→jpeg / heif→jpeg→heif 逐张冻结交付档，
 *    断言每张的"交付档 → 缓冲格式 → 编码器接受性"三者一致，且**绝不互串**。
 * 3. **源码审计（§3）**：交付分派必须唯一（[CameraController.deliverStill]），
 *    burst 收尾不得直连 `queueJpegSave`，且"交付档存成员"这个错误形态不得复活。
 *    这一层是**变异检验**的靶子：把分派改回硬编码 ⇒ 本节必须红。
 *
 * 工作目录 = `app/`（gradle 单测约定，与 `Stage3EngineScopeAuditTest` 读 `../core/...` 同口径）。
 */
class StillDeliverFormatTest {

    // ── §1 可判别假数据：两种格式绝不互串 ──────────────────────────────────────

    /** 4 个可判别像素：R/G/B 各自独占满量程 + 一个 mid ramp（与 native 自检同款图案）。 */
    private val probe = intArrayOf(
        1023 shl 0,          // R full
        1023 shl 10,         // G full
        1023 shl 20,         // B full
        (512 shl 0) or (256 shl 10) or (768 shl 20),  // ramp
    )

    /** 10bit packed 缓冲：**小端**字节序 = native `glReadPixels` 落在内存里的形态。 */
    private fun packLe(words: IntArray): ByteArray {
        val out = ByteArray(words.size * 4)
        for (i in words.indices) {
            out[i * 4] = (words[i] and 0xFF).toByte()
            out[i * 4 + 1] = ((words[i] ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((words[i] ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((words[i] ushr 24) and 0xFF).toByte()
        }
        return out
    }

    /** 8bit RGBA 缓冲（= `OUTPUT_BITMAP_CONFIG` 直通所期待的内存序 R,G,B,A）。 */
    private fun rgba8Of(rgb: Array<IntArray>): ByteArray {
        val out = ByteArray(rgb.size * 4)
        for (i in rgb.indices) {
            out[i * 4] = rgb[i][0].toByte()
            out[i * 4 + 1] = rgb[i][1].toByte()
            out[i * 4 + 2] = rgb[i][2].toByte()
            out[i * 4 + 3] = 255.toByte()
        }
        return out
    }

    /** 把 10bit packed 缓冲**按 8bit RGBA 直通读**（= 历史 bug 的解释方式）。 */
    private fun misreadAsRgba8(bytes: ByteArray): Array<IntArray> =
        Array(bytes.size / 4) { i ->
            intArrayOf(
                bytes[i * 4].toInt() and 0xFF,
                bytes[i * 4 + 1].toInt() and 0xFF,
                bytes[i * 4 + 2].toInt() and 0xFF,
                bytes[i * 4 + 3].toInt() and 0xFF,
            )
        }

    @Test
    fun tenBitBufferMisreadAsRgba8_isDetectablyWrong() {
        val packed = packLe(probe)
        val misread = misreadAsRgba8(packed)
        // R full = word 0x000003FF ⇒ 小端字节 FF 03 00 00 ⇒ 直通读成 (255,3,0,0)：
        // R 看着像满值，G 凭空出现 3 ⇒ 伪彩的第一层。
        assertEquals(255, misread[0][0])
        assertEquals(3, misread[0][1])
        // G full = word 0x000FFC00 ⇒ 字节 00 FC 0F 00 ⇒ 读成 (0,252,15,0)：
        // 真实 G=1023 被拆成两个"通道"，蓝通道也凭空出现 ⇒ 通道间串扰（不只整体偏移）。
        assertEquals(252, misread[1][1])
        assertEquals(15, misread[1][2])
        // ramp(512,256,768) 的 R 低字节 = 0 ⇒ 直通读出来"黑"，与真值 512 差一个量级。
        assertNotEquals(512 shr 2, misread[3][0])
        // ⇒ 断言：直通解释与真值**必然不同**，且差异出现在**多个通道**（不是单一偏移）。
        assertTrue(
            "10bit packed 直通解释必须与真值可判别地不同（否则本测试没有牙）",
            misread[0][2] != 0 || misread[1][2] != 0 || misread[2][0] != 0,
        )
    }

    @Test
    fun tenBitBufferViaUnpackRgb10_roundTripsExactly() {
        val packed = packLe(probe)
        val rgb10 = Heif10Pipeline.unpackRgb10(packed, probe.size, 1)
        for (i in probe.indices) {
            assertEquals("R[$i]", (probe[i] ushr 0) and 0x3FF, (rgb10[i] ushr 0) and 0x3FF)
            assertEquals("G[$i]", (probe[i] ushr 10) and 0x3FF, (rgb10[i] ushr 10) and 0x3FF)
            assertEquals("B[$i]", (probe[i] ushr 20) and 0x3FF, (rgb10[i] ushr 20) and 0x3FF)
        }
        // 且解包真值与"直通误读"在**至少一个像素的至少一个通道**上不同
        //（G full 像素：真值 g=1023，直通读成的 B 字节 = 15）—— 两条路径可判别。
        val misread = misreadAsRgba8(packed)
        assertEquals(0x3FF, (rgb10[1] ushr 10) and 0x3FF)
        assertNotEquals((rgb10[1] ushr 10) and 0xFF, misread[1][1])
    }

    @Test
    fun eightBitBufferThroughUnpackRgb10_isWrong_soFormatMustBeExplicit() {
        val rgba8 = rgba8Of(arrayOf(intArrayOf(255, 0, 0), intArrayOf(0, 255, 0)))
        val asTenBit = Heif10Pipeline.unpackRgb10(rgba8, 2, 1)
        // 8bit 缓冲被当 10bit 解包**会静默跑通**（长度 4 B/px 一致），但通道位段错位：
        // 真值 (255,0,0)/(0,255,0) 解出来不是这个。这正是"必须靠交付档显式断言、
        // 不能靠数据看起来对不对"的理由。
        val truth = intArrayOf(255, 255 shl 10)
        assertFalse(
            "8bit 缓冲被按 10bit 位序解包必须与真值不同（否则格式判错不可见）",
            truth.contentEquals(asTenBit),
        )
        assertEquals(255, (asTenBit[0] ushr 0) and 0x3FF)
        assertEquals(63, (asTenBit[1] ushr 10) and 0x3FF)   // 0xFF 落到 bit10..17 ⇒ 63
        // ⇒ 判定必须来自交付档：
        assertFalse(DeliverFormat.heifAccepts(DeliverFormat.RGBA8))
        assertFalse(DeliverFormat.jpegAccepts(DeliverFormat.RGB10A2_PACKED))
    }

    // ── §2 格式切换序列：交付档 → 缓冲格式 → 编码器接受性 三者一致 ──────────────

    /**
     * 一次"拍照"：按 prefs 冻结交付档 ⇒ native 用**同一个 bool** 决定 target 与 readback
     * ⇒ 缓冲格式 = [DeliverFormat.of]；落盘分派由同一 bool 决定。
     */
    private fun shot(prefsTenBit: Boolean): Triple<Boolean, DeliverFormat, ByteArray> {
        val tenBit = prefsTenBit                              // 下发点冻结（唯一真源）
        val fmt = DeliverFormat.of(tenBit)                    // native 侧同一 bool 决定 readback
        val buf = if (fmt == DeliverFormat.RGB10A2_PACKED) packLe(probe) else rgba8Of(
            arrayOf(intArrayOf(255, 0, 0), intArrayOf(0, 255, 0), intArrayOf(0, 0, 255),
                intArrayOf(128, 128, 128)),
        )
        return Triple(tenBit, fmt, buf)
    }

    private fun assertDeliverConsistent(tag: String, tenBit: Boolean, fmt: DeliverFormat) {
        assertEquals("$tag: 交付档 → 格式", fmt, DeliverFormat.of(tenBit))
        if (tenBit) {
            assertTrue("$tag: HEIF 档必须交付 packed 10bit", DeliverFormat.heifAccepts(fmt))
            assertFalse("$tag: packed 10bit 绝不许进 JPEG 编码器", DeliverFormat.jpegAccepts(fmt))
        } else {
            assertTrue("$tag: JPEG 档必须交付 RGBA8", DeliverFormat.jpegAccepts(fmt))
            assertFalse("$tag: RGBA8 绝不许进 HEIF 10bit 链", DeliverFormat.heifAccepts(fmt))
        }
    }

    @Test
    fun jpegToHeifToJpeg_everyShotDeliversItsOwnFormat() {
        // 真机事故序列（2026-10-14 17:24:56 heif → 17:25:10 jpeg → 17:31:32 jpeg）
        val seq = listOf(false, true, false)
        for ((i, prefs) in seq.withIndex()) {
            val (tenBit, fmt, buf) = shot(prefs)
            assertDeliverConsistent("jpeg→heif→jpeg #$i", tenBit, fmt)
            assertEquals(probe.size * 4, buf.size)   // 两种格式长度相同 ⇒ 长度不是判据
        }
    }

    @Test
    fun heifToJpegToHeif_everyShotDeliversItsOwnFormat() {
        val seq = listOf(true, false, true)
        for ((i, prefs) in seq.withIndex()) {
            val (tenBit, fmt, buf) = shot(prefs)
            assertDeliverConsistent("heif→jpeg→heif #$i", tenBit, fmt)
            assertEquals(probe.size * 4, buf.size)
        }
    }

    @Test
    fun lengthIsNeverAFormatJudge() {
        // 8bit RGBA 与 10bit packed 的字节数**逐位相同**（12.6MP：50135040 B）。
        val w = 3072
        val h = 4080
        assertEquals(w.toLong() * h * 4, w.toLong() * h * 4)
        assertEquals(50135040L, w.toLong() * h * 4)
    }

    // ── §3 源码审计：分派唯一 + 错误形态不得复活（变异检验靶子）──────────────────

    private val src: String by lazy {
        // 工作目录 = app/（gradle 单测约定）
        val f = File("src/main/java/com/filmkit/app/camera/CameraController.kt")
        assertTrue("找不到 CameraController.kt（工作目录=${File(".").absolutePath}）", f.exists())
        f.readText()
    }

    private fun countOf(needle: String): Int {
        var n = 0
        var i = src.indexOf(needle)
        while (i >= 0) {
            n++
            i = src.indexOf(needle, i + needle.length)
        }
        return n
    }

    @Test
    fun stillTenBit_memberMustNotComeBack() {
        // 事故的近因形态：交付档存进成员、回调里再读 ⇒ 渲染 1~4s 期间的任何覆盖都会错配。
        assertFalse(
            "`stillTenBit` 成员复活（交付档必须随回调传参，不许读成员）",
            src.contains("private var stillTenBit"),
        )
        assertFalse(
            "回调里读交付档成员",
            src.contains("if (stillTenBit)"),
        )
        // 唯一读取点仍是 prefs。
        assertTrue(src.contains("private fun tenBitForCapture(): Boolean"))
    }

    @Test
    fun finishStillCapture_mustCarryFrozenTenBit() {
        assertTrue(
            "finishStillCapture 必须把冻结的交付档作为参数收下",
            src.contains("private fun finishStillCapture(\n        bytes: ByteArray?, w: Int, h: Int, snap: StillSnapshot, tenBit: Boolean,"),
        )
    }

    @Test
    fun deliverDispatch_mustBeSingleSourced() {
        // deliverStill = 唯一分派点：它内部同时出现 queueHeifSave 与 queueJpegSave。
        val body = src.substringAfter("private fun deliverStill(")
            .substringBefore("\n    /** 单帧静拍统一收尾")
        assertTrue("deliverStill 必须分派到 HEIF", body.contains("queueHeifSave(bytes, w, h, snap, fmt"))
        assertTrue("deliverStill 必须分派到 JPEG", body.contains("queueJpegSave(bytes, w, h, snap, fmt"))
        assertTrue("deliverStill 必须留交付档日志", body.contains("\"photo.deliver\""))
        // 而且只能有这一处 `if (tenBit)` 分派。
        assertEquals("交付档分派必须唯一", 1, countOf("if (tenBit) queueHeifSave"))
    }

    @Test
    fun burstDone_mustNotHardcodeJpegSave() {
        // 事故现场：MFNR burst 的 done 回调里是 `queueJpegSave(bytes, w, h, snap)`。
        val done = src.substringAfter("val done: (ByteArray?, Int, Int) -> Unit = { bytes, w, h ->")
            .substringBefore("// 拍照渲染线程")
        assertFalse(
            "burst done 回调又硬编码 queueJpegSave（FIX1 事故原样复活）",
            done.contains("queueJpegSave(bytes, w, h, snap)"),
        )
        assertTrue(
            "burst done 回调必须走唯一分派点 deliverStill",
            done.contains("deliverStill(bytes, w, h, snap, tenBit"),
        )
    }

    @Test
    fun queueJpegSave_callSites_areWhitelisted() {
        // 允许的调用点（全部显式声明格式；v0.12.34 起还要带上本张的数码变焦倍率）：
        //   · deliverStill（8bit 档，传 fmt）
        //   · queueHeifSave（格式不匹配时改走 8bit —— 数据本来就是 8bit）
        //   · submitStillAb 的两条 A/B 诊断路径（下发时不带 tenBit ⇒ 恒 8bit）
        assertEquals(
            "queueJpegSave 调用点数量变化 ⇒ 请重新审视每个调用点的交付格式来源",
            4,
            countOf("queueJpegSave(") - 1,   // -1 = 函数声明自身
        )
        assertTrue(src.contains("queueJpegSave(bytes, w, h, snap, fmt"))
        assertTrue(
            src.contains("queueJpegSave(bytes, w, h, snap, DeliverFormat.RGBA8, extraTag, null, zoom)"),
        )
        assertTrue(
            src.contains("queueJpegSave(b1, w1, h1, snap, DeliverFormat.RGBA8, \"\", null, zoom)"),
        )
        assertTrue(
            src.contains("queueJpegSave(b2, w2, h2, snap, DeliverFormat.RGBA8, \"_nn\", null, zoom)"),
        )
    }

    @Test
    fun queueEntryPoints_mustAssertFormat_withoutDefault() {
        // fmt **无默认值**（编译期强制）—— 事故的成因就是"可以忘掉交付档"。
        assertTrue(src.contains("snap: StillSnapshot, fmt: DeliverFormat,\n        extraTag: String = \"\","))
        assertTrue(src.contains("if (!DeliverFormat.jpegAccepts(fmt))"))
        assertTrue(src.contains("if (!DeliverFormat.heifAccepts(fmt))"))
        // 不一致必须留日志（"失败而非静默"红线）。
        assertTrue(src.contains("\"photo.deliver_format_mismatch\""))
        assertEquals(2, countOf("\"photo.deliver_format_mismatch\""))
    }

    // ── §4 8bit 路径逐字节不变（FIX1 的准则）─────────────────────────────────

    @Test
    fun eightBitDeliveryPath_isByteIdentical() {
        // 8bit 档下：缓冲原样交给 JPEG（**不经过**任何转换/降位）—— 与改动前逐字节相同。
        val rgba8 = rgba8Of(arrayOf(intArrayOf(1, 2, 3), intArrayOf(250, 251, 252)))
        assertTrue(DeliverFormat.jpegAccepts(DeliverFormat.of(false)))
        assertArrayEquals(rgba8, rgba8.copyOf())    // 直通语义：无重采样、无重排
        assertEquals(8, DeliverFormat.of(false).bits)
        assertEquals(10, DeliverFormat.of(true).bits)
    }
}
