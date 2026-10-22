package com.filmkit.app.camera

/**
 * 镜头去重（v0.12.42）。
 *
 * 背景（2026-10-22 真机）：X300 的 `0..6` 里有 **三个** 等效 23.6mm 的相机 ——
 * id 0 / id 6 是**逻辑相机**（caps 含 `LOGICAL_MULTI_CAMERA`，能跨物理镜头自动变焦，
 * 变焦范围 0.67–10 / 0.6–100），id 2 才是**物理主摄**。
 *
 * 不去重的后果：
 *  - 设置页的镜头配置列出三个都标 "24MM" 的镜头，用户无法区分；
 *  - `setFocal` / `setZoomEquiv` 在等效焦距相同时取到不确定的那一个 ⇒ 同一个焦段
 *    在不同会话可能落到不同 id，行为随会话漂移（逻辑相机的 zoomRange 完全不同）。
 *
 * 规则：**同等效焦距只保留一个**，物理相机优先（我们自己的 [CameraController.setZoomEquiv]
 * 已经会物理切镜头，不需要 HAL 再替我们切）；同优先级时保留 id 较小的，保证稳定。
 */
data class LensCandidate(
    val id: String,
    val equivMm: Float,
    val isLogical: Boolean,
)

fun dedupeLenses(candidates: List<LensCandidate>): List<LensCandidate> {
    // 按等效焦距取整分组（23.6 与 23.6 必须同组；70.3 与 165 不会撞）
    val best = LinkedHashMap<Int, LensCandidate>()
    for (c in candidates.sortedBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }) {
        val key = Math.round(c.equivMm)
        val prev = best[key]
        best[key] = when {
            prev == null -> c
            // 物理优先：逻辑相机只是"能自动切镜头"的包装，我们不需要它
            prev.isLogical && !c.isLogical -> c
            prev.isLogical == c.isLogical -> prev // 同优先级保留先到的（id 小）
            else -> prev
        }
    }
    return best.values.sortedBy { it.equivMm }
}
