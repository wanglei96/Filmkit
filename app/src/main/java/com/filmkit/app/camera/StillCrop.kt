package com.filmkit.app.camera

import kotlin.math.abs

/**
 * 成片最终**居中裁剪**的尺寸（先数码变焦、再画幅），纯函数便于 JVM 单测。
 *
 * 两步都是居中的 ⇒ 可以合成一次裁切（两次居中裁 = 直接裁到更小的居中矩形），
 * 交付侧只做一次 Bitmap/RGBA 拷贝。
 *
 * @param zoomCrop 数码变焦倍率（>1 才裁；M 档 RAW 需要自己裁，见 CameraController.stillZoom）
 * @param aspect   成片画幅（宽/高；<=0 = 不裁画幅）
 * @return [宽, 高]，均 >= 16 且不超过源尺寸（Bitmap.createBitmap 的硬要求）
 */
fun stillCropSize(w: Int, h: Int, zoomCrop: Float, aspect: Float): IntArray {
    val z = if (zoomCrop > 1.001f) zoomCrop else 1f
    var cw = (w / z).toInt()
    var ch = (h / z).toInt()
    if (aspect > 0f && cw > 0 && ch > 0) {
        val curA = cw.toFloat() / ch.toFloat()
        if (abs(curA - aspect) > 0.01f) {
            if (curA > aspect) cw = (ch * aspect).toInt() else ch = (cw / aspect).toInt()
        }
    }
    return intArrayOf(cw.coerceIn(16, w), ch.coerceIn(16, h))
}
