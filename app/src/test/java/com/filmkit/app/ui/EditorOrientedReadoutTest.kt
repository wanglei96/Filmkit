package com.filmkit.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E2-18b：编辑器**底部尺寸读数**必须与转正后的画面一致（纯 JVM 单测）。
 *
 * 为什么必须测：`inJustDecodeBounds` 读到的是**转正前**的存储尺寸，而 [decodeOriented] 解出来
 * 的像素已按 EXIF 转正（90°/270° 时宽高互换）。两者不一致时，用户看到的是"竖画面 + 横读数"。
 * 真机解码要 Android runtime（JVM 单测跑不了），所以把**判据**抽成纯函数 [orientedReadout]
 * 钉在这里 —— 转正后的读数一旦写反，滑杆定位用的 rect 与轮廓也会跟着错。
 *
 * 数字取自 E2-18a 的真机实测：DNG 存储 4080×3072、`Orientation=6` ⇒ 转正后 3072×4080
 *（与同场景成片 JPEG 一致）。
 */
class EditorOrientedReadoutTest {

    @Test
    fun rotatedSourceSwapsReadout() {
        // ★ 本卡的核心场景：存储 4080×3072（横），EXIF=6（顺时针 90°）转正后解码出
        // 3072×4080（竖）。EXIF=8（顺时针 270°）的交换方向相同，同一判据覆盖。
        assertEquals(3072 to 4080, orientedReadout(3072, 4080, 4080, 3072))
        // 横竖两个方向都要换（不能只在"横 → 竖"时换）
        assertEquals(4080 to 3072, orientedReadout(4080, 3072, 3072, 4080))
    }

    @Test
    fun unrotatedSourceKeepsReadout() {
        // 成片 JPEG：Orientation=1，解码尺寸就是存储尺寸 ⇒ 读数原样
        assertEquals(3072 to 4080, orientedReadout(3072, 4080, 3072, 4080))
        assertEquals(4080 to 3072, orientedReadout(4080, 3072, 4080, 3072))
    }

    @Test
    fun downsampledDecodeDoesNotFakeARotation() {
        // OOM 降档：inSampleSize 是整数，只改尺寸大小、不改横竖 ——
        // 若判据拿"具体数值"比（而不是比横竖），降过档的图会被误判成"转过向" ⇒ 读数写反。
        // 转正 + 降一档（4080×3072 → 1536×2040，仍是竖的）
        assertEquals(3072 to 4080, orientedReadout(1536, 2040, 4080, 3072))
        // 没转向 + 降一档（3072×4080 → 1536×2040，仍是竖的）⇒ 读数不变
        assertEquals(3072 to 4080, orientedReadout(1536, 2040, 3072, 4080))
        // 转正 + 降两档
        assertEquals(3072 to 4080, orientedReadout(768, 1020, 4080, 3072))
    }

    @Test
    fun squareSourceIsUnaffected() {
        // 正方形（w == h）交换与不交换是同一个读数 —— 判据在退化情形下也不能崩
        assertEquals(3000 to 3000, orientedReadout(3000, 3000, 3000, 3000))
    }
}
