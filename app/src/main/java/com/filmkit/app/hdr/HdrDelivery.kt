package com.filmkit.app.hdr

import android.graphics.Bitmap
import android.os.Build
import com.filmkit.app.EngineBridge

/**
 * HDR 成片交付（Ultra HDR / JPEG-R + HEIC gainmap），对齐 PhotonCamera 的做法。
 *
 * ## 三条路径（用户 2026-10-19 拍板：**三种都要能出 HDR**）
 *
 * | 格式 | 色度 | HDR 实现 | 依赖 |
 * |---|---|---|---|
 * | JPEG 4:4:4 | 4:4:4 | 自研 stb base + libultrahdr `packageJpegR` | 无（本项目 native） |
 * | JPEG 4:2:0 | 4:2:0 | 平台 `Bitmap.setGainmap` + `compress` | Android 14+ |
 * | HEIC | 4:2:0（硬件） | 平台 `Bitmap.setGainmap` + `HeifWriter` | Android 14+ + HEIC 编码器 |
 *
 * 4:4:4 走自研链的理由：平台 `Bitmap.compress` 恒 4:2:0（公开 API 无采样因子参数），
 * 而项目把"成片 4:4:4"当质量红线（见 `JpegEncoder.cpp` 头注与 docs/reports/jpeg444-REPORT.md）。
 *
 * ## 回退（照片优先红线）
 * 任何一步失败都**返回 null**，由调用方落普通 SDR 成片 —— 绝不因为 HDR 丢照片。
 * Android 14 以下无平台 Gainmap API ⇒ 只有 4:4:4 路径可用（自研封装不依赖平台版本）。
 */
object HdrDelivery {

    /** 平台 Gainmap API 起点（`Bitmap.setGainmap` / `HeifWriter` 带 gainmap）。 */
    private val PLATFORM_GAINMAP_MIN_SDK = Build.VERSION_CODES.UPSIDE_DOWN_CAKE  // 34

    /** 当前设备能否走**平台** Gainmap 路径（4:2:0 JPEG / HEIC）。 */
    val platformGainmapSupported: Boolean
        get() = Build.VERSION.SDK_INT >= PLATFORM_GAINMAP_MIN_SDK

    /** 当前设备能否走**自研** 4:4:4 JPEG/R 路径（libultrahdr，不依赖平台版本）。 */
    val jpegRSupported: Boolean
        get() = EngineBridge.hdrNativeAvailable

    /**
     * 三条路径的可用性摘要（设置页文案 + 日志用）。
     * 返回的字符串直接给人看，不做解析。
     */
    fun capabilitySummary(): String {
        val jr = if (jpegRSupported) "4:4:4 ✓" else "4:4:4 ✗"
        val p = if (platformGainmapSupported) "4:2:0 ✓ · HEIC ✓" else "4:2:0 ✗ · HEIC ✗（需 Android 14+）"
        return "$jr · $p"
    }

    /**
     * 编码 HDR 成片（JPEG 4:4:4 路径）。
     *
     * @param bitmap 已成片的 SDR 显示域 RGBA_8888（= 落盘的那张）
     * @param gainmap 8bit 归一化比值图（1/2 分辨率单通道），由 `nativeReadGainmap()` 取回
     * @param quality JPEG 质量（1..100，4:4:4 路径内部钳到 ≥91）
     * @return JPEG/R 字节流；null = 失败或不可用（调用方回退普通 JPEG）
     */
    fun encodeJpegR(
        bitmap: Bitmap,
        gainmap: ByteArray,
        gainmapW: Int,
        gainmapH: Int,
        quality: Int,
    ): ByteArray? {
        if (!jpegRSupported) return null
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        if (gainmapW <= 0 || gainmapH <= 0 || gainmap.size < gainmapW * gainmapH) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "hdr", "hdr.gainmap_size_mismatch",
                "{\"expect\":${gainmapW * gainmapH},\"got\":${gainmap.size}," +
                    "\"gw\":$gainmapW,\"gh\":$gainmapH}",
            )
            return null
        }
        return runCatching {
            EngineBridge.nativeEncodeUltraHdr(bitmap, gainmap, gainmapW, gainmapH, quality)
        }.getOrNull()
    }

    /**
     * 把 8bit gainmap 包成平台的 `Gainmap` 对象（4:2:0 JPEG 与 HEIC 两条路径共用）。
     *
     * 平台 `Gainmap` 的元数据字段语义与 libultrahdr 一致，**必须与 kFsGainmap 的常量
     * 逐位一致**（kMinBoost=0.25 / kMaxBoost=4.0 / offset=1e-4），否则显示整体偏亮/偏暗
     * 且不报错 —— 这是本文件最容易踩的坑。
     *
     * @param gray 长度 = w*h 的单通道 8bit 数据（R=G=B 写成 ARGB_8888 交给平台）
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun buildGainmap(gray: ByteArray, w: Int, h: Int): android.graphics.Gainmap? {
        if (w <= 0 || h <= 0 || gray.size < w * h) return null
        return runCatching {
            // 平台要求 ARGB_8888；单通道值复制到三通道（灰度）——
            // 单通道 gainmap 的语义就是"三通道共用同一增益"，与 use_base_cg=1 一致。
            val pixels = IntArray(w * h)
            for (i in 0 until w * h) {
                val v = gray[i].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            android.graphics.Gainmap(bmp).apply {
                setRatioMin(MIN_BOOST, MIN_BOOST, MIN_BOOST)
                setRatioMax(MAX_BOOST, MAX_BOOST, MAX_BOOST)
                setGamma(1f, 1f, 1f)
                setEpsilonSdr(GAINMAP_OFFSET, GAINMAP_OFFSET, GAINMAP_OFFSET)
                setEpsilonHdr(GAINMAP_OFFSET, GAINMAP_OFFSET, GAINMAP_OFFSET)
                // 显示容量端点：1.0（SDR 显示）→ 4.0（HDR 显示全量应用）
                setMinDisplayRatioForHdrTransition(1f)
                setDisplayRatioForFullHdr(MAX_BOOST)
            }
        }.getOrNull()
    }

    /** 与 `kFsGainmap` / `UltraHdrEncoder.cpp` 的常量**必须逐位一致**。 */
    private const val MIN_BOOST = 0.25f
    private const val MAX_BOOST = 4.0f
    private const val GAINMAP_OFFSET = 1e-4f
}
