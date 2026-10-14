package com.filmkit.app.heif

import com.filmkit.app.EngineBridge

/**
 * HEIF 交付的**三级回退状态机**：10bit 自研容器 → 8bit HEIF → JPEG。
 *
 * ## 为什么要独立一个纯 JVM 状态机
 * 三级回退的"顺序"与"每级失败都要留痕"是**语义**（红线：绝不交付可疑文件、绝不静默少一级），
 * 而两级的编码本体只能在真机跑（`MediaCodec`）。把顺序提出来 + 两个 attempt 由调用方注入
 * ⇒ 三条路径（10bit 成功 / 10bit 失败→8bit 成功 / 两级都失败→JPEG）在 JVM 单测里逐条可验。
 *
 * ## 语义（卡 FIX2 追加块）
 * - **严格顺序**：10bit 失败才试 8bit；10bit 成功**不**碰 8bit。
 * - 一级"失败"的三种形态一视同仁：抛异常 / 返回空数组 / 返回 null（`attempt` 内部归一化）
 *   —— 拿不准就不交付，往下落。
 * - 每级都发日志：`photo.heif_failed`（10bit）→ `photo.heif8_queued`（进入 8bit）→
 *   `photo.heif8_failed`（8bit）。真机判据：**不允许**出现"10bit 失败后只有 JPEG 而
 *   没有任何 8bit 尝试"。
 * - 最终 JPEG 那一级的落盘由调用方执行（`photo.heif_fallback_jpeg` 带 `from` 字段标明
 *   从哪一级落下来的）。
 */
object HeifDelivery {

    /** 状态机结果。[EightBit] 的字节 = 平台 muxer 产出的 8bit HEIF 文件。 */
    sealed class Result {
        class TenBit(val bytes: ByteArray) : Result()
        class EightBit(val bytes: ByteArray) : Result()

        /** 前两级都没成 ⇒ 调用方用**同一次**降位结果走 JPEG（[from] = 从哪一级落下来）。 */
        class Jpeg(val from: String, val reason: String) : Result()
    }

    /** 事件名（与既有 `photo.*` 系列同风格）。 */
    const val EVENT_10_FAILED = "photo.heif_failed"
    const val EVENT_8_QUEUED = "photo.heif8_queued"
    const val EVENT_8_FAILED = "photo.heif8_failed"

    /**
     * 逐级尝试。[log] = `(level, event, kvJson)`，生产 = `EngineBridge.nativeAppendLog`。
     */
    fun run(
        attempt10: () -> ByteArray,
        attempt8: () -> ByteArray,
        log: (Int, String, String) -> Unit,
    ): Result {
        val ten = attempt(attempt10)
        if (ten.bytes != null) return Result.TenBit(ten.bytes)

        log(
            EngineBridge.LEVEL_ERROR, EVENT_10_FAILED,
            kv("stage" to "encode", "error" to ten.reason),
        )
        log(
            EngineBridge.LEVEL_WARN, EVENT_8_QUEUED,
            kv("from" to "10bit", "reason" to ten.reason),
        )
        val eight = attempt(attempt8)
        if (eight.bytes != null) return Result.EightBit(eight.bytes)

        log(
            EngineBridge.LEVEL_ERROR, EVENT_8_FAILED,
            kv("from" to "8bit", "reason" to eight.reason),
        )
        return Result.Jpeg(from = "8bit", reason = eight.reason)
    }

    private class Attempt(val bytes: ByteArray?, val reason: String)

    /** 把"抛异常 / 空数组"都归一成失败（含异常文本，铁律：异常文本必须进日志）。 */
    private fun attempt(block: () -> ByteArray): Attempt = try {
        val b = block()
        if (b.isEmpty()) Attempt(null, "empty") else Attempt(b, "")
    } catch (t: Throwable) {
        Attempt(null, t.message ?: t.toString())
    }

    /** kv JSON 片段（与 `EngineBridge.nativeAppendLog` 的 kvJson 形参同格式）。 */
    private fun kv(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(",", "{", "}") { (k, v) ->
            val s = when (v) {
                null -> "null"
                is Number, is Boolean -> v.toString()
                else -> "\"${v.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }
            "\"$k\":$s"
        }
}

/**
 * HEIF 交付的**色度口径**与「4:4:4 色度」开关的关系 —— **唯一判定入口**。
 *
 * 事实（真机探针 + 编码器能力）：10bit 档喂的是 P010（`c2.mtk.hevc.encoder` 连 4:2:2 都
 * 不声明，`p210:false`），8bit 档是 `COLOR_FormatYUV420Flexible` ⇒ HEIF **恒 4:2:0**，
 * 硬件做不到 4:4:4。设置页的 `jpeg_444` 只作用于 JPEG（`nativeEncodeJpeg444`）。
 *
 * 本项目此前把这条事实**静默**掉了（UI 无联动、日志无提示，用户已因此困惑）⇒ 现在：
 * UI 灰显 + 说明、日志带 `chroma`/`jpeg444Ignored`、切档时按用户实机行为对齐地自动关/恢复
 * 该开关（`CameraController.setPhotoFormat`），判定统一走本对象。
 *
 * 单测守卫（变异点）：把 `honorsJpeg444` 改成 `= jpeg444` ⇒ 测试必须红
 *（见 `HeifDeliveryTest`，报告 §5 变异检验 ③）。
 */
object HeifChroma {

    /** HEIF 交付恒 4:2:0（单一真源；[Heif8Encoder.CHROMA] 引用它）。 */
    const val CHROMA = "420"

    /**
     * HEIF 交付是否受 `jpeg_444` 影响 —— **恒 `false`**。
     * 参数保留是为了让"读了该开关就会红"这件事可测（而不是靠注释保证）。
     */
    @Suppress("UNUSED_PARAMETER")
    fun honorsJpeg444(jpeg444: Boolean): Boolean = false
}
