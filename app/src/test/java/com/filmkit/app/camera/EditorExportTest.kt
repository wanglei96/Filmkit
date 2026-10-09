package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

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

    // ── E2-9：「无修改」判据（ADR-13 ⇒ 直通原片字节） ────────────────────────────────

    /** 基准 = **打开编辑器时**的参数（`editorOpenParams` 的返回值），快照里带一个非 0 的 LUT 强度 */
    private fun openBaseline(): DoubleArray = editorOpenParams(
        StillSnapshot(CameraPresets.standard(), "kodak_portra", 0.85, 0L),
    )

    @Test
    fun identicalParamsAreUnchanged() {
        val base = openBaseline()
        assertTrue(paramsUnchanged(base.copyOf(), base))
    }

    @Test
    fun anySingleSlotChangeIsDirty() {
        // 88 个槽位逐个改一次：漏判任何一槽 = 用户的修改被直通丢掉（ADR-13 里最严重的错法）
        val base = openBaseline()
        for (i in base.indices) {
            val cur = base.copyOf()
            cur[i] = base[i] + 0.5   // 对所有槽位量级（最大 200）都远超容差
            assertFalse("slot[$i] 改了必须算有修改", paramsUnchanged(cur, base))
        }
    }

    @Test
    fun sliderRoundTripBackToOriginalIsUnchanged() {
        // ★ 核心场景 + E2-3 浮点坑的实证：滑杆是 Float、槽位是 Double。
        // 用户把暗角半径拖走再拖回 0.8（= 80%）：vigRadiusSlot(0.8f) = 80.0000011920929，
        // 与快照来的 80.0 **不相等** ⇒ 用 `==` 比较会误判成"有修改"（白渲染 + 白编码一次）。
        val base = openBaseline()
        assertEquals(80.0, base[67], 0.0)
        val cur = base.copyOf()
        cur[67] = vigRadiusSlot(0.8f)
        assertNotEquals("前提：Float 往返确实不精确相等", base[67], cur[67])
        assertTrue("改了又改回原值 ⇒ 无修改", paramsUnchanged(cur, base))

        // 全链路"改走再改回"：基础五类 + 暗角三条 + LUT 强度，全部经 UI 映射函数往返
        val back = base.copyOf()
        back[0] = expSlot(0f)
        back[1] = wbGainSlot(1f)
        back[2] = wbGainSlot(1f)
        back[3] = wbGainSlot(1f)
        back[4] = contrastSlot(1f)
        writeSaturation(back, 1f)
        back[78] = claritySlot(0f)
        back[66] = vigStrengthSlot(0f)
        back[67] = vigRadiusSlot(vigRadiusUi(base[67]))
        back[68] = vigFeatherSlot(vigFeatherUi(base[68]))
        back[34] = 0.0
        assertTrue(paramsUnchanged(back, base))
    }

    @Test
    fun lutStrengthBackToZeroIsUnchanged() {
        // 试了一下 LUT 又拖回 0（= 关，引擎 uLutOn = 0 整段旁路）⇒ 画面回到原片 ⇒ 该直通
        val base = openBaseline()
        val cur = base.copyOf()
        cur[34] = 1.0
        assertFalse(paramsUnchanged(cur, base))
        cur[34] = 0f.toDouble()
        assertTrue(paramsUnchanged(cur, base))
    }

    @Test
    fun epsilonCoversFloatNoiseButNotRealEdits() {
        val base = openBaseline()
        val inside = base.copyOf()
        inside[0] = base[0] + PARAM_EPS / 2
        assertTrue("容差内的浮点噪声 ⇒ 无修改", paramsUnchanged(inside, base))

        val outside = base.copyOf()
        outside[0] = base[0] + PARAM_EPS * 2
        assertFalse("超出容差 ⇒ 有修改", paramsUnchanged(outside, base))

        // 真实修改的最小步长（手指移 1px ≈ 6.7e-3 槽位）必须判成"有修改"
        val onePixel = base.copyOf()
        onePixel[67] = base[67] + 6.7e-3
        assertFalse(paramsUnchanged(onePixel, base))
    }

    @Test
    fun nonFiniteParamsAreAlwaysDirty() {
        // 坏数据（NaN/Inf）一律算"有修改"：宁可多渲染一次（安全），也不能比出"无修改"
        // 而把用户的修改丢掉。基准侧同理（防御性）。
        val base = openBaseline()
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val cur = base.copyOf()
            cur[0] = bad
            assertFalse("current 有 $bad ⇒ 有修改", paramsUnchanged(cur, base))
            val badBase = base.copyOf()
            badBase[0] = bad
            assertFalse("baseline 有 $bad ⇒ 有修改", paramsUnchanged(base.copyOf(), badBase))
        }
    }

    @Test
    fun sizeMismatchIsDirty() {
        val base = openBaseline()
        assertFalse(paramsUnchanged(DoubleArray(base.size - 1), base))
        assertFalse(paramsUnchanged(DoubleArray(base.size + 1), base))
    }

    @Test
    fun baselineIsOpenParamsNotSnapshotRaw() {
        // ★ 判据的语义护栏：基准必须是 [editorOpenParams] 的返回值（[34] 已强制 0），
        // **不是快照原文**。快照里 [34] = 1.0（standard 默认）/ lutK = 0.85，打开编辑器时
        // 被强制归零（E2-1"打开即与原片一致"）—— 若拿快照原文当基准，这一次**必然的归零**
        // 会被误判成用户修改 ⇒ 直通永远失效（本卡白做）。
        val snap = StillSnapshot(CameraPresets.standard(), "kodak_portra", 0.85, 0L)
        val raw = editorParams(snap.params)
        val base = editorOpenParams(snap)
        assertEquals(0.0, base[34], 0.0)
        assertTrue("打开后立刻导出 ⇒ 无修改（直通）", paramsUnchanged(base.copyOf(), base))
        assertFalse("拿快照原文当基准是错的", paramsUnchanged(base.copyOf(), raw))

        // 用户点「套用原片风格」（[34] = 快照强度）⇒ 真的改了 ⇒ 必须走渲染
        val applied = base.copyOf()
        applied[34] = 0.85
        assertFalse(paramsUnchanged(applied, base))
    }

    // ── E2-18b：直通只对 JPEG 源图合法（架构师发现的 E2-9 连带 bug）──────────────

    /**
     * 真实文件头字节（前 3~12 字节）—— 不是编的，是各自格式的规范起始：
     *  - JPEG：`FF D8 FF`（SOI + 下一个 marker 前缀），第三字节按 APPn 不同而不同；
     *  - DNG：TIFF 头 `II*\0`（小端）/ `MM\0*`（大端），DNG 就是 TIFF 容器；
     *  - HEIC：`ftyp` box（第 4 字节起），前 4 字节是大端长度。
     */
    private val jpegHead = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte())
    private val dngHeadLe = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00)
    private val dngHeadBe = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08)
    private val heicHead = byteArrayOf(
        0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63,
    )

    @Test
    fun jpegMagicIsRecognizedAndNonJpegIsNot() {
        assertTrue("JPEG 头必须认出来", isJpegSource(jpegHead))
        // 白名单：非 JPEG 一律 false（DNG 两种字节序 + HEIC + 空/过短/读不到）
        assertFalse("DNG（小端 TIFF 头）", isJpegSource(dngHeadLe))
        assertFalse("DNG（大端 TIFF 头）", isJpegSource(dngHeadBe))
        assertFalse("HEIC", isJpegSource(heicHead))
        assertFalse("null（流打不开）", isJpegSource(null))
        assertFalse("空数组", isJpegSource(ByteArray(0)))
        assertFalse("只有 2 字节", isJpegSource(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertFalse("FF D8 后不是 FF", isJpegSource(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00)))
    }

    @Test
    fun jpegSourceWithoutEditsIsAllowedToPassthrough() {
        // ★ 用户报的 bug 的另一半：JPEG 源图 + 参数没动 ⇒ 必须仍然直通（E2-9 的收益不能丢）
        assertTrue(passthroughAllowed(paramsUnchanged = true, jpegSource = true))
    }

    @Test
    fun dngSourceIsNeverAllowedToPassthrough() {
        // ★ 架构师发现的连带 bug：DNG 字节被当成"无修改 ⇒ 复制原片"存成 .jpg
        //（MediaStore MIME 也写 image/jpeg）⇒ 内容与扩展名不符。参数**一个都没动**也不行。
        assertFalse(passthroughAllowed(paramsUnchanged = true, jpegSource = false))
        // 有修改时两条判据都拦（任一为 false 即不直通）
        assertFalse(passthroughAllowed(paramsUnchanged = false, jpegSource = true))
        assertFalse(passthroughAllowed(paramsUnchanged = false, jpegSource = false))
    }

    @Test
    fun dngSourceEndToEndFallsIntoRenderPath() {
        // 串起两条判据的实际组合（exportNow 里的调用形态）：DNG 源图打开后立刻导出
        val base = openBaseline()
        val unchanged = paramsUnchanged(base.copyOf(), base)
        assertTrue("参数确实没动", unchanged)
        assertFalse(
            "DNG 源图没改参数也必须走渲染（dirty = true）",
            passthroughAllowed(unchanged, isJpegSource(dngHeadLe)),
        )
        assertTrue(
            "JPEG 源图没改参数才直通",
            passthroughAllowed(unchanged, isJpegSource(jpegHead)),
        )
    }

    @Test
    fun realFilesConfirmTheMagicNumberVerdict() {
        // 交叉验证：不只信上面硬编码的字节，用**真机拍的文件**再看一遍魔数。
        // test_temp/ 被 gitignore，文件不在时跳过（CI 不红）—— 与 ImageDecodeTest 同一口径。
        val dng = File("../test_temp/FILMKIT_20261009-090803.dng")
        val jpg = File("../test_temp/FILMKIT_20261009-090810.jpg")
        assumeTrue("test_temp 不在（已 gitignore），跳过真实文件实测", dng.isFile && jpg.isFile)

        val dngHead = head4(dng)
        val jpgHead = head4(jpg)
        // DNG 就是 TIFF 容器：小端头 `II*\0` = 49 49 2A 00
        assertArrayEquals(byteArrayOf(0x49, 0x49, 0x2A, 0x00), dngHead)
        // JPEG：SOI + 下一个 marker 前缀 = FF D8 FF（第 4 字节是 marker 种类，随 APPn 而变）
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), jpgHead.copyOf(3),
        )
        assertFalse("真机 DNG 必须判为非 JPEG（否则它会被直通存成 .jpg）", isJpegSource(dngHead))
        assertTrue("成片 JPEG 必须判为 JPEG（直通收益不能丢）", isJpegSource(jpgHead))
    }

    /** 读文件前 4 字节（不把 25MB 的 DNG 拉进内存） */
    private fun head4(f: File): ByteArray = f.inputStream().use { ins ->
        val b = ByteArray(4)
        var off = 0
        while (off < 4) {
            val r = ins.read(b, off, 4 - off)
            if (r <= 0) break
            off += r
        }
        b
    }
}
