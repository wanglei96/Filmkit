package com.filmkit.app.camera

/**
 * 曝光决策器（曝光决策专项，2026-10-02）
 *
 * 标准做法依据
 * ------------
 * * Night Sight §2.3 motion metering：按运动量决定每帧曝光时长（手持 ≤333ms / 稳定 ≤1s）
 * * HDR+ §4：曝光设到"刚好不烧高光"
 * * 我方离线实测 `test_temp/exposure_lever/findings.md`：
 *   - 曝光 ×N 的噪声收益 = **√N**（解析值与物理上限逐位吻合，N=8.66 → 2.95×）
 *   - 代价 = 帧内模糊 d 造成的纹理衰减（tex_keep 只与 d 有关，与 N 解耦）
 *   - 细节优先口径（tex_keep ≥ 0.95）⇒ 帧内模糊预算 **2.2 mosaic px**
 *
 * 核心不变式
 * ----------
 * **曝光 ×N + ISO ÷N ⇒ 输出亮度与高光裁切完全不变，纯赚 SNR。**
 * 因此约束只有三个：ISO 下限、快门上限、运动（帧内模糊）预算。
 * 只做"拉长"；缩短仍交给既有参考帧压短逻辑（缩短直接损失 SNR）。
 */
object ExposurePlanner {

    /** 1 mosaic px 对应的角度（X300 主摄 4080×3072 / 等效 23mm ⇒ 86.6° / 5107px）。 */
    const val RAD_PER_PX = 0.017 * Math.PI / 180.0

    /** 帧内模糊预算（px）：折中口径 tex_keep ≥ 0.90（findings §4）。
     *
     * 首版取"细节优先"口径 2.2px（tex ≥ 0.95），真机实测（2026-10-02 日志）在
     * 正常手持下把预算压到 22ms、比预览 AE 的 40ms 还短 ⇒ 长曝光永久不生效。
     * 4.5px 对应纹理损失 8~10%（d=4px → tex_keep 0.918），换 σ 收益 √N。 */
    const val MOTION_BUDGET_PX = 4.5

    /** 目标曝光时长：Night Sight 手持单帧上限 333ms。 */
    const val TARGET_EXPOSURE_NS = 333_000_000L

    data class Plan(
        val iso: Int,
        val exposureNs: Long,
        /** 实际曝光倍数（相对基准） */
        val n: Double,
        /** target / motion / iso / shutter / none / invalid */
        val limitedBy: String,
        /** 运动预算允许的最长曝光（诊断用） */
        val motionLimitNs: Long,
        /** 输出亮度变化（EV）；设计上 ≈0（亮度守恒） */
        val deltaEv: Double,
    )

    /**
     * @param baseIso 预览 AE 收敛 ISO（当前采集直接用它）
     * @param baseExposureNs 预览 AE 收敛曝光
     * @param isoLower 可用 ISO 下限（低于它无法靠降 ISO 保持亮度）
     * @param exposureUpperNs 传感器曝光上限
     * @param targetExposureNs 目标曝光时长
     * @param motionRadPerSec 采集前陀螺角速度 RMS（rad/s）；≤0 = 无陀螺数据（不限制）
     * @param motionBudgetPx 帧内模糊预算（px）
     */
    fun plan(
        baseIso: Int,
        baseExposureNs: Long,
        isoLower: Int,
        exposureUpperNs: Long,
        targetExposureNs: Long = TARGET_EXPOSURE_NS,
        motionRadPerSec: Double = 0.0,
        motionBudgetPx: Double = MOTION_BUDGET_PX,
    ): Plan {
        if (baseIso <= 0 || baseExposureNs <= 0L || exposureUpperNs <= 0L) {
            return Plan(baseIso.coerceAtLeast(1), baseExposureNs, 1.0, "invalid", 0L, 0.0)
        }
        val nTarget = targetExposureNs.toDouble() / baseExposureNs
        // ① ISO 下限：降 ISO 保持亮度 ⇒ N ≤ baseIso / isoLower
        val nIso = if (isoLower > 0) baseIso.toDouble() / isoLower else Double.MAX_VALUE
        // ② 快门上限
        val nShutter = exposureUpperNs.toDouble() / baseExposureNs
        // ③ 运动预算：曝光窗口内转动 ≤ budget·RAD_PER_PX ⇒ t ≤ budget·RAD_PER_PX / ω
        val hasMotion = motionRadPerSec > 1e-6
        val motionLimitNs = if (hasMotion) {
            (motionBudgetPx * RAD_PER_PX / motionRadPerSec * 1e9).toLong()
        } else {
            0L                                   // 无陀螺数据：不限制（诊断字段 0 = 未生效）
        }
        val nMotion = if (hasMotion) motionLimitNs.toDouble() / baseExposureNs else Double.MAX_VALUE

        var why = "target"
        var n = nTarget
        for ((k, v) in listOf("motion" to nMotion, "iso" to nIso, "shutter" to nShutter)) {
            if (v < n) {
                n = v
                why = k
            }
        }
        val nEff = n.coerceAtLeast(1.0)
        val upper = maxOf(exposureUpperNs, baseExposureNs)
        // 受限项直接取该约束的时长，避免浮点截断（1e8/38.46ms×38.46ms ≠ 1e8）
        val exposureNs = when {
            nEff <= 1.0 -> baseExposureNs
            why == "shutter" -> upper
            why == "motion" -> minOf(motionLimitNs, upper)
            why == "target" -> minOf(targetExposureNs, upper)
            else -> (baseExposureNs * nEff).toLong().coerceIn(baseExposureNs, upper)
        }
        val iso = Math.round(baseIso / nEff).toInt().coerceAtLeast(if (isoLower > 0) isoLower else 1)
        val nReal = exposureNs.toDouble() / baseExposureNs
        val deltaEv = Math.log(nReal * iso / baseIso) / Math.log(2.0)
        return Plan(
            iso = iso,
            exposureNs = exposureNs,
            n = nReal,
            limitedBy = if (nReal <= 1.0001) "none" else why,
            motionLimitNs = motionLimitNs,
            deltaEv = deltaEv,
        )
    }
}
