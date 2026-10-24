package com.filmkit.app.camera

/**
 * 显示归一化坐标 ↔ 传感器归一化坐标的纯换算（无 Android 依赖，可 JVM 单测）。
 *
 * 传感器横置（X300 sensorOrientation=90）时，显示图 = 传感器顺时针旋转；
 * 视图坐标必须按总旋转 `k = (sensorOrientation/90 + orientationStep) % 4` 反推回传感器坐标，
 * 否则 AF/AE 区域落点是转置的——点哪儿都对不上。
 */
object MeteringTargets {

    fun viewToSensor(nx: Float, ny: Float, k: Int): Pair<Float, Float> =
        when (k and 3) {
            1 -> ny to 1f - nx
            2 -> 1f - nx to 1f - ny
            3 -> 1f - ny to nx
            else -> nx to ny
        }

    /**
     * 显示域矩形（归一化）→ 传感器域矩形 `[x0, y0, x1, y1]`。
     * 旋转 90° 的整数倍 ⇒ 轴对齐矩形转置后仍轴对齐，取两角点 min/max 即可。
     */
    fun rectToSensor(x0: Float, y0: Float, x1: Float, y1: Float, k: Int): FloatArray {
        val (ax, ay) = viewToSensor(x0, y0, k)
        val (bx, by) = viewToSensor(x1, y1, k)
        return floatArrayOf(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by))
    }
}
