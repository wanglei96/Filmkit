package com.filmkit.app.heif

/**
 * HEIF 交付的**质量口径真源**（卡 FIX4 / 0.11.40 修正）—— 把"压过头"改成**质量优先**。
 *
 * ## 为什么需要这个文件（一手事实，不是拍脑袋）
 * 真机对照（同场景，都是 4:2:0）：
 * · `FILMKIT_20261015-163839.jpg` JPEG = **5.1 MB** ↔ `…163849.heic` HEIF(8bit) = **198 KB**（1/25）
 * · `FILMKIT_20261015-200100.jpg` JPEG = **6.9 MB** ↔ `…200054.heic` HEIF(10bit) = **392 KB**（1/18）
 * 逐像素比过：影调色彩无偏（p50 146.0 vs 144.3），但**高频只剩 JPEG 的 32%**
 * （分块比值 p10 0.21 / p50 0.31 / p90 0.42 —— 全画面均匀损失）。HEVC 相对 JPEG 通常只省
 * 30~60% ⇒ 1/18 是**压过头**，不是 HEVC 该有的表现。
 *
 * ## 根因（算术，不是"格式不行"）—— 0.11.40 定案
 * ① 要 10bit 就不能用 `HeifWriter`（`useBitDepth10` 硬编码 false）⇒ 只能 `MediaCodec`；
 * ② `KEY_BIT_RATE` 的单位是 **bit/秒**，而静态图只编 **1 帧** ⇒ 单帧预算 =
 *    `bitrate / frame-rate`；`frame-rate` 曾照抄视频口径取 **30** ⇒ **报出去的码率被
 *    推过编码器的声明上限**：想要 2.45 MB/帧就得报 611 Mbps，而真机 X300 的
 *    `bitrateRange.upper` = **100 Mbps** ⇒ 被夹 ⇒ 单帧预算 100/30 = 3.33 Mbit = **416 KB**
 *    ⇒ 真机落盘 **392 KB**（= 预算的 94%，吻合）。
 * ⇒ 修法是 `FRAME_RATE = 1`（静态图的正确语义）：要报的码率降到 **20.4 Mbps**，低于上限、
 *   不再被夹。**这是"细节不行"的完整算术根因**（详见 [FRAME_RATE] 的表格）。
 *
 * ## 目标（任务书硬要求，**不许取舍**）
 * - 质量**接近无损**（高频能量 ≥ JPEG 的 0.7，影调分位数偏差 ≤1 LSB）；
 * - 体积仍在 JPEG 的 **40~60%**（≥60% 就失去 HEIF 的意义，≤40% 就是压过头）。
 *
 * ## 三条路（按直接程度排，本文件只产出**描述**，落键在 `Hevc10Encoder`）
 * ① `qp`：**最直接的质量驱动** —— `bitrate-mode=CQ` + **官方** I 帧 QP 区间键
 *   （`video-qp-i-min`/`-max`，静态图 all-intra ⇒ min==max==QP 即恒定质量）。
 *   ⚠ 编码器可**静默忽略**（不报错）⇒ 必须靠 [verdict] 判"设了不生效"。
 * ② `cq`：`bitrate-mode=CQ` + `quality`（标准键，语义由编码器解释）。
 * ③ `cbr`：`bitrate-mode=CBR` + 按目标体积反算的码率（兜底：只靠预算，质量随内容浮动）。
 *
 * ## 为什么本文件是纯 Kotlin（零 android import）
 * 判据要在 **JVM 单测**里跑（真机 `MediaCodec` 单测覆盖不到），所以 [Strategy] /
 * [verdict] / [bitrateFor] / [stepQp] / [pickBest] 全部是纯函数，android 相关的落键动作
 * 留在 `Hevc10Encoder.applyQuality`。`bitrate-mode`/`quality`/QP 的键名与取值在这里写成
 * 字面量常量（来源：本机 `javap -constants android-36 android.jar` 实测，见报告 §2）。
 *
 * ## 可标定
 * [JPEG_REFERENCE_BPP] 与 [QP_DEFAULT] 是**唯二**需要从真机回收的常量，其余都由它们派生。
 * 真机标定后只改这两处（改完跑 `HeifQualityTest`，它会锁住派生关系）。
 */
object HeifQuality {

    // ── 目标区间（任务书硬要求）───────────────────────────────────────────────

    /** 成片体积 / 同场景 JPEG 体积的**下界**（低于它 = 压过头）。 */
    const val TARGET_MIN_RATIO = 0.40f

    /** 成片体积 / 同场景 JPEG 体积的**上界**（高于它 = HEIF 白做了）。 */
    const val TARGET_MAX_RATIO = 0.60f

    /**
     * **同场景 JPEG 的 bit/像素（luma）**——目标体积的换算基准。
     *
     * 来历（真机一手）：`FILMKIT_20261015-163839.jpg` = 5,109,807 B @ 3072×4080
     * = 12,533,760 px ⇒ 5,109,807 × 8 / 12,533,760 = **3.26 bit/px**（JPEG q100）。
     * 取 3.25（保守取整，避免目标区间被四舍五入推高）。
     *
     * ⚠ 这是**模型**（同一档 JPEG 的体积随场景噪声涨跌，真机实测 7.5 MB / 37 MB 的片子
     * 都存在）。它的用途是给"压过头"一个**可执行的判据**，不是承诺精确命中；
     * 真机拿到同场景 JPEG 后用 `tools/heif-quality-gate.py` 按**真实** JPEG 体积复核。
     */
    const val JPEG_REFERENCE_BPP = 3.25f

    // ── 编码器键（字面量：本文件零 android import；来源见类注释）─────────────

    const val BITRATE_MODE_KEY = "bitrate-mode"   // MediaFormat.KEY_BITRATE_MODE
    const val QUALITY_KEY = "quality"             // MediaFormat.KEY_QUALITY（API 33 起对编码器有意义）

    /** `MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ`（javap 实测 = 0）。 */
    const val MODE_CQ = 0

    /** `MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR`（javap 实测 = 2）。 */
    const val MODE_CBR = 2

    /**
     * **官方** I 帧 QP 区间键（= `MediaFormat.KEY_VIDEO_QP_I_MIN` / `KEY_VIDEO_QP_I_MAX`；
     * 字面量取自本机 `javap -constants android-36 android.jar` 实测）。
     *
     * 静态图 = all-intra（只有 I 帧）⇒ `min == max == QP` 就是**恒定质量**，这才是
     * "质量驱动"的标准 API。此前用的 MTK vendor 键（`vendor.mtk.ext.venc.visual.quant`）
     * 真机**实测失败**：设了之后编码器连 `csd-0` 都拿不到（0.11.39 日志
     * `photo.heif_csd {len:0, ok:false}`）⇒ 该路 100% 走不通，已删除。
     *
     * ⚠ 编码器可以**静默忽略**这些键（不报错）⇒ 是否生效只认 [verdict]（产出体积），
     *   不认"设成功了"。
     */
    const val QP_I_MIN_KEY = "video-qp-i-min"
    const val QP_I_MAX_KEY = "video-qp-i-max"

    /**
     * 与编码器 `frame-rate` **同一个**值（`Hevc10Encoder.FRAME_RATE` / `Heif8Encoder`）。
     * 它参与 bitrate→单帧预算的折算 ⇒ 改一处必须两处一起改（[bitrateFor] 的注释）。
     *
     * ⚠ **必须是 1**：这是**静态图**，只编 1 帧。曾取 30（照抄视频口径）⇒ 换算出的码率被
     * 推过编码器的**声明上限**，单帧预算被砍 30 倍（0.11.39 真机实测的算术根因）：
     *
     * | | frame-rate=30（错） | frame-rate=1（对） |
     * |---|---|---|
     * | 想要 2.45 MB/帧 ⇒ 要报的码率 | 2.45 MB×8×30 = **611 Mbps** | 2.45 MB×8×1 = **20.4 Mbps** |
     * | 真机 `bitrateRange.upper`（X300） | **100 Mbps** | 同 |
     * | 被夹后的单帧预算 | 100/30 = **3.33 Mbit = 416 KB** | 20.4 Mbps ÷ 1 = **2.45 MB** ✓ |
     * | 真机落盘 | **392 KB**（= 预算的 94%，吻合）⇒ 高频仅 JPEG 的 32% | 目标 2.0~3.1 MB |
     *
     * ⇒ "细节不行"不是格式的锅，是**码率报错了 30 倍**（`KEY_BIT_RATE` 的单位是 bit / 秒，
     * 而静态图一帧只占 1/30 秒）。取 1 后 20.4 Mbps **低于**上限 ⇒ 不再被夹。
     */
    const val FRAME_RATE = 1

    // ── QP 路的参数（可标定）────────────────────────────────────────────────

    /**
     * QP 起始点。来历：目标 = [JPEG_REFERENCE_BPP] × 0.4~0.6 = **1.30~1.96 bit/px**；
     * HEVC Main10 **all-intra** 的经验 bpp-QP 曲线上，该 bpp 区间大致对应 **QP 14~20**
     * ⇒ 取中值 16。⚠ **待真机标定**：真机用 `tools/heif-quality-gate.py` 跑一次，
     * 若 `verdict=TOO_SMALL` 就往下调（降 QP），`TOO_BIG` 就往上调。
     */
    const val QP_DEFAULT = 16

    /** 一次自校正的 QP 步长（压过头 ⇒ 降 QP 提质量；太胖 ⇒ 升 QP 降体积）。 */
    const val QP_STEP = 4

    /** QP 下界：再低就是"接近无损但体积接近 JPEG"，违背目标区间。 */
    const val QP_MIN = 8

    /** QP 上界：再高就是重演 198 KB 的老毛病（实测 1/25 ≈ QP 35+ 的表现）。 */
    const val QP_MAX = 28

    /**
     * QP 路最多试几次（含首次）。2 次 = "起始点没标定好"时还能朝正确方向走一步
     * （`QP_DEFAULT` 未标定 ⇒ 16 → 12 或 20），同时给 cq / cbr 两路留出尝试次数
     * （[MAX_TOTAL_TRIES] = 3）：三路各留一个**可比较的数据点**比让一路独占全部次数
     * 更有价值 —— 0.11.40 的首要任务是把"哪条路真的能驱动质量"用一张照片测出来。
     */
    const val MAX_QP_TRIES = 2

    /**
     * **全部路合计**的编码尝试上限 —— 拍照后等待时间是用户可感的硬约束：
     * 单次 12MP 编码实测约 1.8 s ⇒ 没有这个上限时最坏 = qp×[MAX_QP_TRIES] + cq + cbr
     * = **5 次 ≈ 9 s**（多出来的每一次还要重建一次 `MediaCodec` 实例）。
     *
     * 正常路径 1 次即达标（[QP_DEFAULT] 标定合格时命中 [verdict]==OK 立刻返回）；
     * 3 次 ≈ 5.4 s 是**最坏**的落盘等待。超限时用 [pickBest] 留最接近目标区间的那份，
     * 并把 `verdict != OK` 与 `tries` 一起进日志（不许静默交付没达标的片子）。
     *
     * ⚠ 这是"防御性上限"，不是替用户做的质量取舍：真机标定后若发现 QP 一次就命中，
     * 可把它收到 2；若发现某平台需要更多次收敛，再放宽（改动只此一处）。
     */
    const val MAX_TOTAL_TRIES = 3

    /** CQ 路的 quality（0..100）—— 与用户 JPEG 质量默认档同档（PC 口径 100 / 用户默认 95）。 */
    const val CQ_QUALITY = 100

    // ── 码率（路 ③ 用）──────────────────────────────────────────────────────

    /** 码率下界（沿用旧公式的下夹：低于它连 480p 级别的静态图都不够）。 */
    const val BITRATE_MIN_BPS = 8_000_000

    /**
     * 拿不到编码器 `bitrateRange` 时的兜底上限。**2 Gbps**：远大于 12MP 在
     * [FRAME_RATE] = 1 下需要的 20.4 Mbps；真机若拒绝该值 ⇒ `configure` 抛异常 ⇒
     * 由回退链兜住照片。
     *
     * ⚠ 历史上这个上限被设成 **240 Mbps** —— 那是按 `w·h·30·2`（视频口径，含 30 fps）
     * 反推的"拍脑袋估算"：它把**已经错了 30 倍**的 frame-rate 又叠了一层，对 12MP 得到
     * 1 MB/帧的单帧预算 ⇒ 压过头。**上限本身不是病根，frame-rate 才是**。
     */
    const val BITRATE_FALLBACK_CAP_BPS = 2_000_000_000L

    // ── 纯函数（JVM 单测覆盖）───────────────────────────────────────────────

    /** 判定结果。 */
    enum class Verdict { OK, TOO_SMALL, TOO_BIG }

    /** 一次编码尝试的**描述**（不含 android 类型 ⇒ 可测）。 */
    data class Strategy(
        /** 路名（进日志：`qp` / `cq` / `cbr`）。 */
        val mode: String,
        /** `bitrate-mode` 取值（[MODE_CQ] / [MODE_CBR]）。 */
        val bitrateMode: Int,
        /** 码率（bit/s）。三路都给：CQ 下它仍是上界/预算，编码器要求它存在。 */
        val bitrate: Int,
        /** I 帧 QP（官方键 [QP_I_MIN_KEY]/[QP_I_MAX_KEY]，仅 [mode] == "qp" 时非空）。 */
        val qp: Int? = null,

        /** `quality`（仅 [mode] == "cq" 时非空）。 */
        val quality: Int? = null,
    )

    /** 一次实际编码的**结果统计**（进 `photo.heif_encoded` 的字段，让下一轮不用猜）。 */
    data class Stats(
        val mode: String,
        val qp: Int?,
        val bitrate: Int,
        val bytes: Int,
        val ms: Long,
        val verdict: Verdict,
        /** 本次交付走了几次编码尝试（>1 = 前几路"设了不生效"/没达标）。 */
        val tries: Int,
        /** 目标区间（字节），与 [bytes] 一起进日志便于真机核对模型。 */
        val targetMin: Long,
        val targetMax: Long,
        /**
         * 编码器声明的码率上限（`bitrateRange.upper`，实测值）。
         *
         * 进日志的理由：0.11.39 那次真机只看得到 `bitrate:100000000`，"这个 100 Mbps 是我们
         * 想要的还是被夹的结果"要靠**反推**（611 → 100 说明被夹）—— 一个字段就能省掉这步。
         */
        val cap: Int? = null,
    ) {
        /** `photo.heif_encoded` 的 kv 片段（字段名与任务书一致：`bitrateMode`/`qp`/`bitrate`/`bytes`/`ms`）。 */
        fun kv(): String =
            "{\"bitrateMode\":\"$mode\",\"qp\":${qp ?: -1},\"bitrate\":$bitrate,\"bytes\":$bytes," +
                "\"ms\":$ms,\"verdict\":\"$verdict\",\"tries\":$tries," +
                "\"frameRate\":$FRAME_RATE,\"cap\":${cap ?: -1}," +
                "\"targetMin\":$targetMin,\"targetMax\":$targetMax}"
    }

    /**
     * 目标体积区间（字节）= 同场景 JPEG 估算体积 × [TARGET_MIN_RATIO]..[TARGET_MAX_RATIO]。
     *
     * 12MP 自检：12,533,760 × 3.25 / 8 = 5.09 MB（= 真机 JPEG 的 5.1 MB ✅ 模型自洽）
     * ⇒ 区间 **2.04 ~ 3.06 MB**。
     */
    fun targetRange(w: Int, h: Int): LongRange {
        val jpegBytes = (w.toLong() * h * JPEG_REFERENCE_BPP / 8.0).toLong()
        return (jpegBytes * TARGET_MIN_RATIO).toLong()..(jpegBytes * TARGET_MAX_RATIO).toLong()
    }

    /**
     * 路 ③ 的码率（bit/s）= 目标区间**中值** × 8 × [FRAME_RATE]。
     *
     * ⚠ 这里的折算不是形式：`KEY_BIT_RATE` 的单位是 **bit/秒**，而静态图只编 **1 帧**
     * ⇒ 单帧预算 = `bitrate / frame-rate`。12MP 目标中值 2.45 MB = 19.6 Mbit：
     *   · [FRAME_RATE] = 1 ⇒ 报 **20.4 Mbps** ⇒ < 真机上限 100 Mbps ⇒ **拿到 2.45 MB 预算** ✓
     *   · [FRAME_RATE] = 30 ⇒ 报 611 Mbps ⇒ **撞上限被夹到 100** ⇒ 预算只剩 416 KB ✗
     * 后者就是 0.11.39 真机落盘 392 KB（"细节不行"）的完整算术。
     *
     * @param capUpper 编码器 `videoCapabilities.bitrateRange.upper`；`null` = 拿不到 ⇒ 用
     *   [BITRATE_FALLBACK_CAP_BPS]。**被夹小时不会报错** ⇒ 调用方要把 `capUpper` 与目标
     *   一起进日志（`Stats.cap`，真机由此一眼判定够不够，不用反推）。
     */
    fun bitrateFor(w: Int, h: Int, capUpper: Int?): Int {
        val r = targetRange(w, h)
        val midBytes = (r.first + r.last) / 2
        val want = midBytes * 8 * FRAME_RATE
        val cap = (capUpper?.toLong() ?: BITRATE_FALLBACK_CAP_BPS)
            .coerceAtMost(BITRATE_FALLBACK_CAP_BPS)
            .coerceAtLeast(BITRATE_MIN_BPS.toLong())
        return want.coerceIn(BITRATE_MIN_BPS.toLong(), cap).toInt()
    }

    /** 产出体积是否落在目标区间内。`TOO_SMALL` = 压过头（本卡要消灭的形态）。 */
    fun verdict(bytes: Int, w: Int, h: Int): Verdict {
        val r = targetRange(w, h)
        return when {
            bytes < r.first -> Verdict.TOO_SMALL
            bytes > r.last -> Verdict.TOO_BIG
            else -> Verdict.OK
        }
    }

    /** 距目标区间的距离（0 = 在区间内）。用于"多路都没达标时挑最接近的"。 */
    fun distance(bytes: Int, w: Int, h: Int): Long {
        val r = targetRange(w, h)
        return when {
            bytes < r.first -> r.first - bytes
            bytes > r.last -> bytes - r.last
            else -> 0L
        }
    }

    /**
     * QP 自校正：压过头 ⇒ 降 QP（提质量、涨体积）；太胖 ⇒ 升 QP。
     * @return 下一个 QP；`null` = 撞到 [QP_MIN]/[QP_MAX] ⇒ QP 路到此为止，该换路了。
     */
    fun stepQp(qp: Int, verdict: Verdict): Int? {
        if (verdict == Verdict.OK) return null
        val next = if (verdict == Verdict.TOO_SMALL) qp - QP_STEP else qp + QP_STEP
        return if (next in QP_MIN..QP_MAX) next else null
    }

    /**
     * **"这一路还要不要再试一次"** —— 多路降级 + QP 收敛的推进规则（纯函数 ⇒ JVM 可测）。
     *
     * @param plan 刚试过的策略
     * @param verdict 它产出的判定（[Verdict.OK] 时调用方本该已经停了）
     * @param qpTries 该路已试次数（不含本次）
     * @return `(新的 qpTries, 下一个策略)`；`null` = 这一路到此为止 ⇒ 换下一路。
     *   · `qp` 路：按 [stepQp] 移动 QP，最多 [MAX_QP_TRIES] 次；
     *   · `cq` / `cbr` 路：没有可自校正的旋钮 ⇒ 恒 `null`（试一次就换路）。
     */
    fun refine(plan: Strategy, verdict: Verdict, qpTries: Int): Pair<Int, Strategy>? {
        if (plan.mode != "qp") return null
        if (qpTries + 1 >= MAX_QP_TRIES) return null
        val next = stepQp(plan.qp ?: QP_DEFAULT, verdict) ?: return null
        return (qpTries + 1) to plan.copy(qp = next)
    }

    /**
     * 该分辨率下的**三路入口**（有序：qp → cq → cbr）。
     *
     * 只给每路的**起点**：qp 路往哪个方向走取决于实际产出（压过头降 QP、太胖升 QP）
     * ⇒ 迭代由调用方用 [stepQp] 推进（最多 [MAX_QP_TRIES] 次），本函数不预摊梯度。
     * 这样"起始点标定偏了"也能自己收敛，而不是一路撞到 [QP_MIN] 才发现方向错了。
     *
     * 三路都是**质量驱动**的三种说法，谁真的生效由 [verdict] 判（编码器可以静默忽略键）：
     * ① `qp`  = CQ + 官方 I 帧 QP 区间（最直接：直接指定量化步长）；
     * ② `cq`  = CQ + `quality`（次之：把质量交给编码器解释）；
     * ③ `cbr` = CBR + 目标反算码率（兜底：只靠码率预算，质量随内容浮动）。
     *
     * @param capUpper 编码器声明的码率上限（见 [bitrateFor]）。
     */
    fun strategies(w: Int, h: Int, capUpper: Int?): List<Strategy> {
        val br = bitrateFor(w, h, capUpper)
        return listOf(
            Strategy("qp", MODE_CQ, br, qp = QP_DEFAULT),
            Strategy("cq", MODE_CQ, br, quality = CQ_QUALITY),
            Strategy("cbr", MODE_CBR, br),
        )
    }

    /**
     * 所有路都没达标时挑**最接近目标区间**的那次
     * （不留"体积最大"的——那可能远超 60%；也不留最小的——那就是压过头）。
     *
     * @param candidates `(strategy, bytes, ms)` 三元组，按尝试顺序。
     */
    fun pickBest(candidates: List<Triple<Strategy, Int, Long>>, w: Int, h: Int): Triple<Strategy, Int, Long>? =
        candidates.minByOrNull { distance(it.second, w, h) }

    /**
     * **"这次该留还是该换"** —— 每试完一路就问一次（纯函数 ⇒ JVM 可测）。
     *
     * @param curBytes 当前最贴近目标区间那次的体积；`null` = 还没有任何成功产出
     * @param candBytes 刚试完这一路的体积
     * @return `true` = 换用刚试完的这次（它更接近目标区间）
     */
    fun isBetter(curBytes: Int?, candBytes: Int, w: Int, h: Int): Boolean =
        curBytes == null || distance(candBytes, w, h) < distance(curBytes, w, h)
}
