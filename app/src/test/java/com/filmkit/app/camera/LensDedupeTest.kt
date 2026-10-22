package com.filmkit.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 镜头去重（[dedupeLenses]）。
 *
 * 钉住的是真机上真实存在的一组 id（2026-10-22 `camera.isz_probe` / `camera.lens_list`）：
 * X300 有 0/2/6 三个 23.6mm，其中 0 与 6 是逻辑相机、2 是物理主摄。
 */
class LensDedupeTest {

    private val x300 = listOf(
        LensCandidate("4", 15.4f, isLogical = false),
        LensCandidate("0", 23.6f, isLogical = true),
        LensCandidate("2", 23.6f, isLogical = false),
        LensCandidate("6", 23.6f, isLogical = true),
        LensCandidate("3", 70.3f, isLogical = false),
        LensCandidate("5", 165.1f, isLogical = false),
    )

    @Test
    fun threeCameraIdsWithSameEquivCollapseToOne() {
        val out = dedupeLenses(x300)
        assertEquals("15.4 一跳、23.6 三个、70.3、165 ⇒ 4 个", 4, out.size)
        assertEquals(listOf("4", "2", "3", "5"), out.map { it.id })
    }

    @Test
    fun physicalCameraWinsOverLogical() {
        // id 2 是物理主摄；即使 id 0 排在前面（id 更小）也必须选 2
        assertEquals(
            "2",
            dedupeLenses(
                listOf(
                    LensCandidate("0", 23.6f, isLogical = true),
                    LensCandidate("2", 23.6f, isLogical = false),
                ),
            ).single().id,
        )
    }

    @Test
    fun logicalKeptWhenNoPhysicalExists() {
        // 只有逻辑相机（某些机型没有暴露物理 id）⇒ 不能把它删光
        val out = dedupeLenses(listOf(LensCandidate("0", 23.6f, isLogical = true)))
        assertEquals("0", out.single().id)
    }

    @Test
    fun stableOrderAndNoAccidentalMerge() {
        // 15.4 与 15.6 不该被合并（四舍五入是 15 vs 16）；70.3 与 165.1 各自独立
        val out = dedupeLenses(
            listOf(
                LensCandidate("3", 70.3f, false),
                LensCandidate("5", 165.1f, false),
                LensCandidate("a", 15.4f, false),
                LensCandidate("b", 15.6f, false),
            ),
        )
        assertEquals(4, out.size)
        assertEquals(listOf("a", "b", "3", "5"), out.map { it.id })
    }

    @Test
    fun equalPriorityKeepsSmallerId() {
        val out = dedupeLenses(
            listOf(
                LensCandidate("9", 23.6f, false),
                LensCandidate("2", 23.6f, false),
            ),
        )
        assertEquals("2", out.single().id)
    }
}
