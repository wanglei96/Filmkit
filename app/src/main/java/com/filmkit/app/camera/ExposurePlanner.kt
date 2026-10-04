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
 * 拉长侧的约束只有三个：ISO 下限、快门上限、运动（帧内模糊）预算。
 * 只做"拉长"；缩短仍交给既有参考帧压短逻辑（缩短直接损失 SNR）。
 *
 * v0.10.135 曝光给足（2026-10-04 用户拍板方案 A；"先压噪声，高光交给影调层"
 * 2026-10-03 拍板的完整落地）
 * --------------------------------------------------------------------------
 * 上述守恒不变式有一个死角：**ISO 已在下限时 nIso = 1，拉长被锁死**——逆光
 * 大光比场景（预览 AE 高光优先收敛，如窗景 4.8ms）暗部 SNR 永远上不去。
 * 2026-10-04 真机实锤（112025 对）：4.8ms×4 帧的低频色度噪声云（P90 3 级）
 * 在相册放大下呈密集品红斑点；同场景 73.6ms（seq1）无此问题。修法：
 * ISO 钉在下限后**继续拉快门**（亮度 +N），增量由成片 EV 反补偿压回
 * （filmParamsForStill 的 -deltaEv 对称扩展）——亮度不变、SNR 净赚 √N、
 * 高光 RAW 裁切交给影调层黑白点/肩部（seq1 同窗场景已真机验证可行）。
 * 拉长侧约束 = min(运动预算, 快门上限, 目标 333ms)，**不含 ISO 下限、不含
 * 高光上限**（拍板：噪声优先）。高光欠曝降级为保险丝：仅当连给足的物理
 * 上限都不存在（nStretch ≤ 1，基准已 ≥ 给足上限）且高光越界时才缩短。
 *
 * v0.10.110 高光保护（2026-10-02 用户拍板"从曝光策略上解决"；v0.10.135 起降级
 * 为保险丝，历史教训留档）
 * ----------------------------------------------------------
 * 曝光的第一目标曾是**刚好不烧高光**（HDR+ §4 原文："set this exposure low enough
 * to avoid blowing out highlights"）。真机实锤：预览 `w999 = 2.4383` 恰为 RAW 饱和
 * 数据白点，引擎再乘 2^(base_ev+用户EV) 就把 0.1% 的饱和像素扩成大片裁切（成片
 * `clipB 711/3072` = 23% 的块命中高光裁剪），提亮型 LUT 只是在这片压平的白上染色。
 * 故曾有第四项约束：`sceneHighlightEv + exposureCompEv ≤ 白点 − 余量`，越界即**欠曝**
 * （n < 1，只缩短曝光时间、不抬 ISO）。2026-10-03 用户拍板"先压噪声，高光交给
 * 影调层"后，欠曝与给足在大光比场景正面冲突，按后拍板降级为保险丝（见上）。
 */
object ExposurePlanner {

    /** 1 mosaic px 对应的角度（X300 主摄 4080×3072 / 等效 23mm ⇒ 86.6° / 5107px）。 */
    const val RAD_PER_PX = 0.017 * Math.PI / 180.0

    /** 帧内模糊预算（px）：**细节优先口径 tex_keep ≥ 0.95**（findings §4）——
     *  用户 2026-09-28 拍板「细节优先不可动摇，噪声与细节冲突时选细节」，纹理是红线。
     *
     *  曾一度放宽到 4.5px（tex ≥ 0.90）以让长曝光生效，属**误读用户意见后替用户做
     *  质量取舍**（"偏一点没关系"指的是不必严格 A/B，不是画质可妥协），已撤回。
     *  运动判据改用矢量平均角速度（见 CameraController.gyroMeanRadPerSec）后，
     *  正常手持（ω≈0.005 rad/s）下 2.2px 预算即允许 ~131ms 曝光，无需牺牲纹理。 */
    const val MOTION_BUDGET_PX = 2.2

    /** 目标曝光时长：Night Sight 手持单帧上限 333ms。 */
    const val TARGET_EXPOSURE_NS = 333_000_000L

    data class Plan(
        val iso: Int,
        val exposureNs: Long,
        /** 实际曝光倍数（相对基准）；**可 < 1**（高光保护欠曝） */
        val n: Double,
        /** target / motion / iso / shutter / highlight / none / invalid */
        val limitedBy: String,
        /** 运动预算允许的最长曝光（诊断用） */
        val motionLimitNs: Long,
        /** 输出亮度变化（EV）；拉长侧 ≈0（亮度守恒），高光保护侧 < 0（欠曝） */
        val deltaEv: Double,
        /** 预估帧内模糊（mosaic px）= 曝光时长 × 角速度 / RAD_PER_PX；
         *  必须 ≤ motionBudgetPx —— 每次拍照都能核对纹理代价没超预算 */
        val blurPx: Double,
    )

    /** RAW 饱和数据白点（log2 相对中灰 0.1845）= log2(1.0/0.1845)，与引擎 whiteEv 下限同值。 */
    const val WHITE_POINT_EV = 2.438307

    /**
     * @param baseIso 预览 AE 收敛 ISO（当前采集直接用它）
     * @param baseExposureNs 预览 AE 收敛曝光
     * @param isoLower 可用 ISO 下限（低于它无法靠降 ISO 保持亮度）
     * @param exposureUpperNs 传感器曝光上限
     * @param targetExposureNs 目标曝光时长
     * @param motionRadPerSec 采集前陀螺角速度 RMS（rad/s）；≤0 = 无陀螺数据（不限制）
     * @param motionBudgetPx 帧内模糊预算（px）
     * @param sceneHighlightEv 场景高光分位（log2 相对中灰，**测于提亮之前**）；
     *   NaN = 无统计 ⇒ 不做高光保护
     * @param highlightHeadroomEv 提亮之后要留给高光的余量（EV）
     * @param exposureCompEv 本次将要施加的提亮（base_ev + 用户 EV）——判据必须把它算进去，
     *   否则"曝光贴顶 + 后处理提亮"仍会撞白点
     * @param whitePointEv RAW 饱和数据白点（log2 相对中灰）
     */
    fun plan(
        baseIso: Int,
        baseExposureNs: Long,
        isoLower: Int,
        exposureUpperNs: Long,
        targetExposureNs: Long = TARGET_EXPOSURE_NS,
        motionRadPerSec: Double = 0.0,
        motionBudgetPx: Double = MOTION_BUDGET_PX,
        sceneHighlightEv: Double = Double.NaN,
        highlightHeadroomEv: Double = 0.1,
        exposureCompEv: Double = 0.0,
        whitePointEv: Double = WHITE_POINT_EV,
    ): Plan {
        if (baseIso <= 0 || baseExposureNs <= 0L || exposureUpperNs <= 0L) {
            return Plan(baseIso.coerceAtLeast(1), baseExposureNs, 1.0, "invalid", 0L, 0.0, 0.0)
        }
        val nTarget = targetExposureNs.toDouble() / baseExposureNs
        // ① ISO 下限：降 ISO 保持亮度 ⇒ N ≤ baseIso / isoLower（只决定 ISO 钉
        //    下限的位置 = 守恒段与给足段的分界，不再是拉长上限）
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
        // 给足拉长上限（v0.10.135）：ISO 下限与高光上限**都不在列**——
        // ① nIso 不封顶：ISO 钉在下限后继续拉（亮度 +N，成片 EV 反补偿）；
        // ② nHl 不封顶：拉长侧高光裁切交给影调层（2026-10-04 拍板方案 A）。
        val nStretch = minOf(nMotion, nShutter, nTarget)
        // ④ 高光越界分位（v0.10.110）：nHl < 1 = 基准下高光将越白点。
        //    v0.10.135 起只在**无给足空间**（nStretch ≤ 1，基准已 ≥ 给足上限，
        //    极暗长曝场景）时作为欠曝保险丝；给足可行时高光交给影调层。
        val nHl = if (sceneHighlightEv.isFinite()) {
            val over = sceneHighlightEv + exposureCompEv - (whitePointEv - highlightHeadroomEv)
            if (over > 0.0) Math.pow(2.0, -over) else 1.0
        } else {
            1.0
        }

        // 保险丝：给足没有物理空间（基准已 ≥ motion/shutter/target 全部上限）且
        // 高光越界 ⇒ 缩短到刚好不烧（v0.10.110 原行为）。给足可行时不走这里。
        if (nHl < 1.0 && nStretch <= 1.0) {
            val exposureNs = (baseExposureNs * nHl).toLong().coerceIn(1L, baseExposureNs)
            val nReal = exposureNs.toDouble() / baseExposureNs
            val blurPx = if (hasMotion) exposureNs / 1e9 * motionRadPerSec / RAD_PER_PX else 0.0
            return Plan(
                iso = baseIso,
                exposureNs = exposureNs,
                n = nReal,
                limitedBy = "highlight",
                motionLimitNs = motionLimitNs,
                deltaEv = Math.log(nReal) / Math.log(2.0),
                blurPx = blurPx,
            )
        }

        // 给足链：n = min(运动, 快门, 目标)。why 取最小项（与既有命名一致）。
        var why = "target"
        var n = nTarget
        for ((k, v) in listOf("motion" to nMotion, "shutter" to nShutter)) {
            if (v < n) {
                n = v
                why = k
            }
        }
        val upper = maxOf(exposureUpperNs, baseExposureNs)
        // 受限项直接取该约束的时长，避免浮点截断；coerceAtLeast(base)：
        // 运动预算比基准还紧时不缩短（历史语义，缩短直接损失 SNR）。
        val exposureNs = when {
            n <= 1.0 -> baseExposureNs
            why == "shutter" -> upper
            why == "motion" -> minOf(motionLimitNs, upper)
            else -> minOf(targetExposureNs, upper)
        }.coerceIn(baseExposureNs, upper)
        // ISO：守恒段（n ≤ nIso）按比例降，触到下限后钉死（给足段）。
        val iso = Math.round(baseIso / n).toInt()
            .coerceIn(if (isoLower > 0) isoLower else 1, baseIso)
        val nReal = exposureNs.toDouble() / baseExposureNs
        // 亮度变化：守恒段 = 0；给足段 = log2(n × isoLower / baseIso) > 0，
        // 由成片 EV 反补偿（CameraController.burstHlUnderEv = -deltaEv）。
        val deltaEv = Math.log(nReal * iso / baseIso) / Math.log(2.0)
        // 预估帧内模糊（mosaic px）= 曝光秒数 × 角速度 ÷ 每像素角度；必 ≤ motionBudgetPx
        val blurPx = if (hasMotion) {
            exposureNs / 1e9 * motionRadPerSec / RAD_PER_PX
        } else {
            0.0
        }
        return Plan(
            iso = iso,
            exposureNs = exposureNs,
            n = nReal,
            limitedBy = if (nReal <= 1.0001) {
                if (nHl < 1.0) "highlight" else "none"
            } else {
                why
            },
            motionLimitNs = motionLimitNs,
            deltaEv = deltaEv,
            blurPx = blurPx,
        )
    }
}
