package com.filmkit.app.heif

import android.content.SharedPreferences

/**
 * 照片格式（用户拍板 P3，2026-10-14）：**默认 JPEG**（现有链一字不动），HEIF 为可选增强档。
 *
 * ## 为什么默认 JPEG
 * 照片优先是项目红线：JPEG 链是唯一"经过全部真机验收"的交付路径，HEIF（10bit + HEVC Main10
 * + 自研容器）是**新增**路径 —— 默认必须落在已验证的那条上。HEIF 失败时也一律回退 JPEG
 * （见 `CameraController.saveHeif` 的回退链），任何情况下**不丢照片**。
 *
 * ## prefs 键
 * `photo_format`（沿用既有命名风格：`jpeg_quality` / `jpeg_444` / `dng_enabled`…）。
 * 值与 [PhotoFormat.of] 的解析：**缺失或任何非 "heif" 的值 = JPEG**（向后兼容：老用户
 * 的 prefs 里没有这个键 ⇒ 行为与升级前完全一致）。
 */
enum class PhotoFormat {
    JPEG,
    HEIF;

    companion object {
        const val PREFS_KEY = "photo_format"
        const val VALUE_HEIF = "heif"
        const val VALUE_JPEG = "jpeg"

        /** 宽松解析：非 "heif" 一律 JPEG（含 null / 空 / 脏值）。 */
        fun of(raw: String?): PhotoFormat = if (raw == VALUE_HEIF) HEIF else JPEG

        fun read(prefs: SharedPreferences): PhotoFormat = of(prefs.getString(PREFS_KEY, null))

        fun write(prefs: SharedPreferences, f: PhotoFormat) {
            prefs.edit()
                .putString(PREFS_KEY, if (f == HEIF) VALUE_HEIF else VALUE_JPEG)
                .apply()
        }

        fun label(f: PhotoFormat): String = if (f == HEIF) "HEIF（10bit）" else "JPEG"
    }
}
