package com.filmkit.app.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成片居中裁剪尺寸（[stillCropSize]）。
 *
 * 这里钉住的是两条**用户可见**的行为：

 *  1. 非原生焦段（M 档 RAW，HAL 不套 zoom）成片必须真的变小 = 裁切生效 —— 用户
 *     2026-10-22 报"从没生效过"，回归时最可能坏在这里（把 zoom 传成 1）；
 *  2. 变焦与画幅同时存在时只裁一次（合成居中矩形），不是先缩后裁留一圈黑边。
 */
class StillCropTest {

    private val w = 4080
    private val h = 3072 // X300 RAW 全幅

    @Test
    fun zoomOnlyShrinksBothAxes() {
        assertArrayEquals(intArrayOf(2040, 1536), stillCropSize(w, h, 2f, 0f))
        assertEquals("长宽比必须保持 4:3", 4080f / 3072f, 2040f / 1536f, 1e-6f)
    }

    @Test
    fun noZoomNoAspectIsIdentity() {
        assertEquals(w, stillCropSize(w, h, 1f, 0f)[0])
        assertEquals(h, stillCropSize(w, h, 1f, 0f)[1])
    }

    @Test
    fun aspectOnlyCropsTheLongSide() {
        // 4:3 → 1:1：高度不变、宽度收窄（与旧实现逐位一致）
        assertEquals(3072, stillCropSize(w, h, 1f, 1f)[0])
        assertEquals(3072, stillCropSize(w, h, 1f, 1f)[1])
        // 4:3 源直接要 4:3：不动
        assertEquals(w, stillCropSize(w, h, 1f, 4f / 3f)[0])
    }

    @Test
    fun zoomAndAspectCompose() {
        // 2× 之后是 2040×1536（4:3），再按 1:1 裁 → 1536×1536
        assertArrayEquals(intArrayOf(1536, 1536), stillCropSize(w, h, 2f, 1f))
    }

    @Test
    fun nonNativeFocalOn24mm() {
        // 用户真实场景：24mm 镜头拍 46mm ⇒ z = 46/24 = 1.9167
        val out = stillCropSize(w, h, 46f / 24f, 0f)
        assertEquals(2128, out[0])
        assertTrue("宽必须真的变小", out[0] < w)
    }

    @Test
    fun degenerateInputsStayLegal() {
        val tiny = stillCropSize(100, 80, 99f, 0f)
        assertTrue("Bitmap.createBitmap 要求 >= 1，这里兜到 16", tiny[0] >= 16 && tiny[1] >= 16)
        val weird = stillCropSize(w, h, 1f, 0.01f)
        assertTrue(weird[0] in 16..w && weird[1] in 16..h)
    }
}
