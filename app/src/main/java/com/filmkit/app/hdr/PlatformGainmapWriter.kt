package com.filmkit.app.hdr

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.heifwriter.HeifWriter
import java.io.OutputStream

/**
 * 平台 Gainmap 路径：**JPEG 4:2:0 + HDR** 与 **HEIC + HDR**。
 *
 * 对齐 PhotonCamera 的 `UltraHdrWriter` / `HeicExportEncoder`（一手源码 2026-10-19 对照）：
 * PC 对这两条格式都用**平台 API**（`Bitmap.setGainmap` + `Bitmap.compress` / `HeifWriter`），
 * 只有 4:4:4 JPEG 才走自研 native + libultrahdr。
 *
 * ## 为什么平台路径只出 4:2:0
 * `Bitmap.compress(JPEG)` 底层 Skia → libjpeg-turbo，`jpeg_set_defaults()` 后不再触碰采样因子，
 * 公开 API 无任何参数可改（`YuvImage.compressToJpeg` 同样固定 4:2:0）。想 4:4:4 必须自研编码器
 * （见 `JpegEncoder.cpp` / `UltraHdrEncoder.cpp`）。
 *
 * ## gainmap 来源
 * 平台要求传入 `android.graphics.Gainmap` 对象（含增益图 Bitmap + 元数据）。本项目的 gainmap
 * 由 native 侧按官方/PC 公式算好后，在这里包成 `Gainmap` 交给平台。
 *
 * ⚠ 平台 `Gainmap` 的元数据字段语义（ratioMin/ratioMax/gamma/epsilon）与 libultrahdr 一致，
 * 但**必须显式设置**，否则平台用默认值（0.25~4.0、gamma 1.0）—— 与我们的 gainmap 不匹配时
 * 显示会偏亮/偏暗。
 */
object PlatformGainmapWriter {

    private const val TAG = "PlatformGainmapWriter"

    /** 平台路径是否可用（Android 14+）。 */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * JPEG 4:2:0 + HDR：`bitmap.setGainmap(gm)` 后 `compress(JPEG)`。
     *
     * @return true = 写入成功；false = 失败或不可用（调用方回退普通 SDR JPEG）
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun writeJpeg420(
        bitmap: Bitmap,
        gainmap: Gainmap,
        out: OutputStream,
        quality: Int,
    ): Boolean {
        if (!isSupported) return false
        return runCatching {
            bitmap.gainmap = gainmap
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
            // 清掉 gainmap，避免这张 Bitmap 在后续 SDR 落盘时被误带上（同一对象可能复用）
            bitmap.gainmap = null
            ok
        }.getOrElse {
            Log.w(TAG, "JPEG 4:2:0 + HDR 写入失败: ${it.message}")
            runCatching { bitmap.gainmap = null }
            false
        }
    }

    /**
     * HEIC + HDR：`bitmap.setGainmap(gm)` 后交给 `HeifWriter`。
     *
     * 与 PC `HeicExportEncoder` 同做法（`INPUT_MODE_BITMAP` + `setGridEnabled(true)`）。
     * HEIC 的色度恒 4:2:0（硬件编码器 P010/YUV420），这是格式本身的限制，不是本实现的取舍。
     *
     * @return true = 写入成功；false = 失败/不可用/设备无 HEIC 编码器
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun writeHeic(
        bitmap: Bitmap,
        gainmap: Gainmap,
        outputPath: String,
        quality: Int,
        exifData: ByteArray? = null,
    ): Boolean {
        if (!isSupported || !hasHeicEncoder()) return false
        return runCatching {
            bitmap.gainmap = gainmap
            HeifWriter.Builder(
                outputPath,
                bitmap.width,
                bitmap.height,
                HeifWriter.INPUT_MODE_BITMAP,
            )
                .setQuality(quality.coerceIn(0, 100))
                .setMaxImages(1)
                .setPrimaryIndex(0)
                .setGridEnabled(true)
                .build()
                .use { writer ->
                    writer.start()
                    if (exifData != null) writer.addExifData(0, exifData, 0, exifData.size)
                    writer.addBitmap(bitmap)
                    writer.stop(HEIC_TIMEOUT_MS)
                }
            bitmap.gainmap = null
            java.io.File(outputPath).let { it.exists() && it.length() > 0L }
        }.getOrElse {
            Log.w(TAG, "HEIC + HDR 写入失败: ${it.message}")
            runCatching { bitmap.gainmap = null }
            runCatching { java.io.File(outputPath).delete() }
            false
        }
    }

    /** 设备是否有 HEIC 编码器（`MediaCodecList` 里找 `image/heic` 编码器）。 */
    fun hasHeicEncoder(): Boolean = runCatching {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { c ->
            c.isEncoder && c.supportedTypes.any { it.equals("image/heic", ignoreCase = true) }
        }
    }.getOrDefault(false)

    /** HEIC 编码超时（与 PC `ENCODE_TIMEOUT_MS` 一致）。 */
    private const val HEIC_TIMEOUT_MS = 10_000L
}
