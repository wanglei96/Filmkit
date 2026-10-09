package com.filmkit.app.ui

import com.filmkit.app.camera.isJpegSource
import com.filmkit.app.camera.passthroughAllowed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2-19 的纯逻辑护栏：**「覆盖原片」前的 RAW 丢失警告什么时候弹**。
 *
 * 用户真机反馈原话："按你说的 DNG 覆盖时给个警告"。代价不对称，所以判据必须钉死：
 *  - 该弹不弹 ⇒ 用户的 RAW 数据**无声消失**（DNG 被渲染成 JPEG 写回原 URI，不可恢复）；
 *  - 不该弹乱弹 ⇒ 每次覆盖都多吓一次（JPEG 覆盖只损失一次重编码，不是 RAW 级别）。
 *
 * 三条规格（对应 [overwriteNeedsRawWarning] 的两个输入）：
 *  1. **非 JPEG（DNG）+ 有修改 ⇒ 警告**（本卡的核心场景）；
 *  2. **JPEG ⇒ 永不警告**（无论有没有修改）；
 *  3. **无修改 ⇒ 永不警告**：那一路 `exportNow` 直接拒绝（"没有修改，无需覆盖"，一个字节
 *     都不写）⇒ 警告是虚惊；这条同时保证"非 JPEG + 无修改"**仍然走原拒绝路径**。
 */
class EditorOverwriteWarnTest {

    @Test
    fun rawSourceWithEditsWarnsBeforeOverwrite() {
        assertTrue(
            "DNG（非 JPEG）改了参数再点覆盖 ⇒ 必须先弹 RAW 丢失警告",
            overwriteNeedsRawWarning(paramsUnchanged = false, jpegSource = false),
        )
    }

    @Test
    fun jpegSourceNeverWarns() {
        assertFalse(
            "JPEG 源图覆盖只是再一次有损重编码，不是 RAW 永久丢失 ⇒ 走既有确认框即可",
            overwriteNeedsRawWarning(paramsUnchanged = false, jpegSource = true),
        )
        assertFalse(
            "JPEG + 无修改同样不警告",
            overwriteNeedsRawWarning(paramsUnchanged = true, jpegSource = true),
        )
    }

    @Test
    fun unchangedParamsNeverWarn() {
        // 「无修改 + 覆盖」= exportNow 里那条"没有修改，无需覆盖"的直接拒绝路径（一个字节
        // 都不写）。源图是不是 RAW 都不该弹警告 —— 弹了挡不住任何损失，只是虚惊。
        assertFalse(
            "非 JPEG + 无修改 ⇒ 不警告（仍走原拒绝路径）",
            overwriteNeedsRawWarning(paramsUnchanged = true, jpegSource = false),
        )
    }

    @Test
    fun onlyDirtyRawSourceTriggersWarning() {
        // 四种组合穷举：警告只在 (有修改, 非 JPEG) 这一格为真 —— 多一格就是误报，少一格就是漏报。
        val warn = listOf(false, true).flatMap { u ->
            listOf(false, true).map { j -> Triple(u, j, overwriteNeedsRawWarning(u, j)) }
        }
        val expected = setOf(Triple(false, false, true))
        val actual = warn.filter { it.third }.toSet()
        assertTrue("唯一该警告的组合是 (有修改, 非 JPEG)：$actual", actual == expected)
    }

    @Test
    fun warnAndPassthroughAreMutuallyExclusive() {
        // 不变量：**"可以直通复制原片字节"与"必须警告 RAW 丢失"互斥**。
        // 两者都真 ⇒ 既复制原片字节又警告要丢 RAW（自相矛盾）；两条判据共用 isJpegSource 口径。
        listOf(false, true).forEach { u ->
            listOf(false, true).forEach { j ->
                assertFalse(
                    "直通与 RAW 警告不能同时成立（unchanged=$u, jpeg=$j）",
                    overwriteNeedsRawWarning(u, j) && passthroughAllowed(u, j),
                )
            }
        }
    }

    @Test
    fun magicBytesFeedTheWarningJudgement() {
        // 端到端一点：真实文件头（DNG `II*\0` / JPEG `FF D8 FF`）经 E2-18b 的 isJpegSource
        // 进判据 —— 钉住"复用同一份格式判断"，不另写一套。
        val dng = byteArrayOf(0x49, 0x49, 0x2A, 0x00)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertTrue(
            "DNG 文件头 + 有修改 ⇒ 警告",
            overwriteNeedsRawWarning(false, isJpegSource(dng)),
        )
        assertFalse(
            "JPEG 文件头 + 有修改 ⇒ 不警告",
            overwriteNeedsRawWarning(false, isJpegSource(jpeg)),
        )
    }
}
