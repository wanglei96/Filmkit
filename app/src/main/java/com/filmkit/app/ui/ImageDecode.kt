package com.filmkit.app.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/**
 * 统一的方向解码（E2-18a，用户真机反馈："dng 文件在编辑器/相册里面的方向不对，要和成片一致"）。
 *
 * **为什么必须有这一层**：`BitmapFactory` 默认**不读** EXIF 方向（与 `ImageDecoder`、平台的
 * `loadThumbnail` 不同），而写入侧给两类文件写的是两种口径：
 *  - **DNG**：写真实的 sensor 方向（1/6/3/8，见 `CameraController` 的 `DngCreator.setOrientation`）；
 *  - **JPEG 成片**：像素已经转正 + `Orientation=1`（见 `EditorExport`）。
 * 所以 DNG 不在读取侧转正，就会与成片朝向不一致。
 *
 * 返回的位图**像素已转正**，调用侧可安全当作 `Orientation=1` 使用（不要再读一次方向）。
 *
 * 约定（IO 线程调用；任何失败都不抛）：
 *  - 方向标签缺失 / 非法 / 格式不支持 ⇒ 按 1（原样）处理，不猜不崩；
 *  - 解码失败（打不开 / 不是图片）⇒ null，由调用侧显示失败态；
 *  - OOM ⇒ `inSampleSize` 降一档（长边减半）重试一次，仍失败返回 null。
 *
 * @param reqW 目标宽（px）；`<= 0` 表示不采样（原尺寸解码，照片查看页用）
 * @param reqH 目标高（px）；`<= 0` 同上
 * @param knownSize 已知的原图尺寸（宽 to 高）—— 调用方刚读过就传进来，省一次流打开（E2-22）；
 *   默认 null 自己读。只用于算采样率，**不校验**。
 */
internal fun decodeOriented(
    context: Context,
    uri: Uri,
    reqW: Int,
    reqH: Int,
    knownSize: Pair<Int, Int>? = null,
): Bitmap? {
    val resolver = context.contentResolver

    // 尺寸预读只为算采样率（不分配像素）。读不到也继续试：按原尺寸解一次，失败由下面兜底。
    val src = knownSize ?: readBounds(resolver, uri)

    val (rotation, flip) = exifOrientationTransform(readOrientation(resolver, uri))
    // 第一趟按目标算出的采样率；OOM 后降一档（×2 = 长边减半）重试一次
    var sample = if (src != null) sampleSizeFor(src.first, src.second, reqW, reqH) else 1
    repeat(2) {
        var bmp: Bitmap? = null
        try {
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            bmp = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null // null = 打不开 / 不是图片（降档也救不回来）
            return orient(bmp, rotation, flip)
        } catch (_: OutOfMemoryError) {
            // 降档前先把这一份还回去，否则重试时两份同时在（12MP 每份 ≈50MB）
            bmp?.recycle()
            sample *= 2
        } catch (_: Throwable) {
            bmp?.recycle()
            return null
        }
    }
    return null
}

/** 读原图尺寸（IO 线程，只解析文件头不分配像素）。读不到 / 不是位图 / 格式不支持 ⇒ null。 */
private fun readBounds(resolver: ContentResolver, uri: Uri): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    return try {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    } catch (_: Throwable) {
        null
    }
}

/**
 * 查看页用：按**"完整放进 maxW×maxH"后的显示尺寸**解码（E2-22，照 PC「按显示尺寸解码」的做法）。
 *
 * 与 [decodeOriented] 的唯一区别是**先把原图 Fit 进容器再算采样率** —— 直接把容器尺寸当目标传进去
 * 会让 [sampleSizeFor] 卡在 sample=1（全分辨率 48MB），原因见 [fitSizeFor] 的 KDoc。
 * 方向转正（8 值含镜像）/ OOM 降档 / 失败返回 null 全部沿用 [decodeOriented]，**不退化**。
 *
 * 读不到原尺寸（非标准位图，如 DNG）或容器未测量（`maxW/maxH <= 0`）⇒ 退回全分辨率
 * （= 改动前的行为），不假装能按显示尺寸解。
 *
 * @param maxW 容器宽（px）；调用方传**显示区域的像素尺寸**（如 Compose 的 `viewSize`）
 * @param maxH 容器高（px）
 */
internal fun decodeFit(context: Context, uri: Uri, maxW: Int, maxH: Int): Bitmap? {
    val src = readBounds(context.contentResolver, uri)
    val (w, h) = if (src == null) 0 to 0 else fitSizeFor(src.first, src.second, maxW, maxH)
    if (w <= 0 || h <= 0) return decodeOriented(context, uri, 0, 0)
    return decodeOriented(context, uri, w, h, src)
}

/**
 * EXIF Orientation → **(顺时针旋转角, 是否水平镜像)**，纯函数（JVM 单测覆盖 8 个值）。
 *
 * 组合约定 **M = R(角度) · H(镜像)**：先水平镜像，再顺时针旋转 —— 与 [orient] 里
 * `postScale(-1,1)` 之后 `postRotate()` 的 `Matrix` 语义一致（`postConcat` 是 M' = other·M）。
 *
 * | EXIF | 含义 | 旋转 | 镜像 |
 * |---|---|---|---|
 * | 1 | 正常 | 0° | 否 |
 * | 2 | 水平镜像 | 0° | 是 |
 * | 3 | 旋转 180° | 180° | 否 |
 * | 4 | 垂直镜像 | 180° | 是 |
 * | 5 | 转置（主对角线） | 270° | 是 |
 * | 6 | 顺时针 90° | 90° | 否 |
 * | 7 | 反转置（副对角线） | 90° | 是 |
 * | 8 | 顺时针 270° | 270° | 否 |
 *
 * 4/5/7 三个镜像值**必须真处理**：只按"水平镜像"糊过去（不少库的简化做法）会把垂直镜像
 * 与两个对角转置搞错 —— 相机通常只写 1/3/6/8，但这三个值出现在导入图里就是错向。
 *
 * 其余取值（0 = undefined、越界、缺失）一律按 1 处理。
 */
internal fun exifOrientationTransform(orientation: Int): Pair<Int, Boolean> = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0 to true
    ExifInterface.ORIENTATION_ROTATE_180 -> 180 to false
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180 to true
    ExifInterface.ORIENTATION_TRANSPOSE -> 270 to true
    ExifInterface.ORIENTATION_ROTATE_90 -> 90 to false
    ExifInterface.ORIENTATION_TRANSVERSE -> 90 to true
    ExifInterface.ORIENTATION_ROTATE_270 -> 270 to false
    else -> 0 to false // 1 = normal，以及 0/非法值
}

/**
 * 目标尺寸 → `inSampleSize`（**2 的幂**，与 `EditorScreen.loadPreview` 的降档口径同源）。
 * 判据：再降一档（×2）仍不小于目标就继续降 —— 保证结果不小于目标，宁可多解一点不糊。
 * 网格缩略图靠它把 12MP 原图压到几十万像素（采样率大 ⇒ 解码快、内存小）。
 */
internal fun sampleSizeFor(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
    if (srcW <= 0 || srcH <= 0 || reqW <= 0 || reqH <= 0) return 1
    var s = 1
    while (srcW / (s * 2) >= reqW && srcH / (s * 2) >= reqH) s *= 2
    return s
}

/**
 * 目标尺寸 = 原图**完整放进 `maxW×maxH`** 后的尺寸（与 Compose `ContentScale.Fit` 同口径），纯函数。
 *
 * **为什么必须单独算这一步**（E2-22 的核心陷阱，调研用脚本按真实判据验证过）：[sampleSizeFor]
 * 要求**两个维度同时满足**才降档，把容器尺寸直接当目标传进去会**完全失效** ——
 * 竖图 3000×4000 传 `(1260, 2800)` 时 `4000/2 = 2000 < 2800` ⇒ 返回 sample=1（全分辨率 48MB）。
 * 先 Fit 成 1260×1680 再当目标 ⇒ sample=2 ⇒ 1500×2000（3MP）。三种画幅（横/竖/全景）同理。
 *
 * 实现用 **Long 交叉相乘**判"宽受限还是高受限"、整数向上取整算另一维：浮点比值会在临界点偏 1px，
 * 而 [sampleSizeFor] 的降档判据是"源/2 ≥ 目标"这种**整数悬崖** —— 1px 就能把 sample 从 2 打回 1。
 *
 * 原图比容器小 ⇒ 返回**放大后**的目标尺寸（100×100 进 1260×2800 ⇒ 1260×1260）：不是要放大解码，
 * 而是让 [sampleSizeFor] 如实返回 1（解原尺寸，BitmapFactory 也不会放大）。
 *
 * 任一维度非正（容器未测量 / 尺寸读不到）⇒ `(0, 0)`，调用侧据此走兜底，**不要拿 0 去解码**。
 */
internal fun fitSizeFor(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
    if (srcW <= 0 || srcH <= 0 || maxW <= 0 || maxH <= 0) return 0 to 0
    // 宽受限（maxW/srcW <= maxH/srcH，交叉相乘避免除法误差）⇒ 宽铺满；否则高铺满
    return if (maxW.toLong() * srcH <= maxH.toLong() * srcW) {
        maxW to ceilDiv(srcH.toLong() * maxW, srcW.toLong())
    } else {
        ceilDiv(srcW.toLong() * maxH, srcH.toLong()) to maxH
    }
}

/** 正整数向上取整除法（`a`、`b` 都 > 0；结果夹进 Int 值域，病态输入不给负数） */
private fun ceilDiv(a: Long, b: Long): Int =
    ((a + b - 1) / b).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

/** 读 EXIF 方向（IO 线程）。读不到 / 格式不支持 / 流打不开一律返回 [ExifInterface.ORIENTATION_UNDEFINED]。 */
private fun readOrientation(resolver: ContentResolver, uri: Uri): Int = try {
    resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED,
        )
    } ?: ExifInterface.ORIENTATION_UNDEFINED
} catch (_: Throwable) {
    ExifInterface.ORIENTATION_UNDEFINED
}

/**
 * 按 (旋转角, 镜像) 转正。`Bitmap.createBitmap(..., matrix, true)` 返回的是变换后的包围盒
 * （90°/270° 时宽高自动交换，平移也由它自己对齐），原图随后立刻回收 —— 不做这一步，
 * 12MP 全尺寸下两份同时在（≈100MB）。
 */
private fun orient(src: Bitmap, rotationDegrees: Int, flip: Boolean): Bitmap {
    if (rotationDegrees == 0 && !flip) return src
    val matrix = Matrix().apply {
        if (flip) postScale(-1f, 1f) // 先水平镜像
        if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat()) // 再顺时针旋转
    }
    val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    if (out !== src) src.recycle()
    return out
}
