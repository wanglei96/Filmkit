package com.filmkit.app.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MeteringTargetsTest {

    @Test
    fun k0IsIdentity() {
        val (x, y) = MeteringTargets.viewToSensor(0.2f, 0.3f, 0)
        assertEquals(0.2f, x, 1e-6f)
        assertEquals(0.3f, y, 1e-6f)
    }

    @Test
    fun k1SwapsAxes() {
        // 顺时针 90°：显示 (0.2, 0.3) → 传感器 (0.3, 0.8)
        val (x, y) = MeteringTargets.viewToSensor(0.2f, 0.3f, 1)
        assertEquals(0.3f, x, 1e-6f)
        assertEquals(0.8f, y, 1e-6f)
    }

    @Test
    fun k2Rotates180() {
        val (x, y) = MeteringTargets.viewToSensor(0.2f, 0.3f, 2)
        assertEquals(0.8f, x, 1e-6f)
        assertEquals(0.7f, y, 1e-6f)
    }

    @Test
    fun k3IsCounterClockwise() {
        val (x, y) = MeteringTargets.viewToSensor(0.2f, 0.3f, 3)
        assertEquals(0.7f, x, 1e-6f)
        assertEquals(0.2f, y, 1e-6f)
    }

    @Test
    fun skyRectIsAxisAlignedAfterRotation() {
        // 显示域顶部中央 25%~75% / 2%~26%
        val r = MeteringTargets.rectToSensor(0.25f, 0.02f, 0.75f, 0.26f, 1)
        assertArrayEquals(floatArrayOf(0.02f, 0.25f, 0.26f, 0.75f), r, 1e-6f)
    }
}
