package com.filmkit.app.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
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

/**
 * 查看页的 **HDR 解码**（v0.11.50）：按显示尺寸解码，且**保留 gainmap**。
 *
 * 为什么不能用 [decodeFit]：那条路径走 `BitmapFactory` + `inSampleSize`，而
 * **`inSampleSize > 1` 时 gainmap 会被丢弃** —— 查看页 12MP 按 3MP 解码（sample=2），
 * 于是 HDR 成片在 App 内看起来永远是 SDR（真机反馈"app相册貌似还是sdr显示"）。
 *
 * `ImageDecoder.setTargetSize` 会按比例缩放**主图与 gainmap**，两者保持对齐 ——
 * 这是 Android 官方推荐的 HDR 解码路径（API 28+）。
 *
 * 方向：`ImageDecoder` **自己读 EXIF 方向**（与 `BitmapFactory` 相反），所以本函数
 * **不再**走 [exifOrientationTransform]（再转一次会双旋）。
 *
 * 失败（API < 28 / 不是图片 / OOM）⇒ 回落 [decodeFit]（退化成 SDR，但不黑屏）。
 *
 * @param maxW/maxH 容器尺寸（px）；`<= 0` ⇒ 全尺寸解码
 */
internal fun decodeFitHdr(context: Context, uri: Uri, maxW: Int, maxH: Int): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return decodeFit(context, uri, maxW, maxH)   // <14 无 gainmap 支持
    }
    // ⚠ **必须 inSampleSize = 1**：Android 的 BitmapFactory 在 inSampleSize > 1 时
    // **丢弃 gainmap**（查看页 12MP ⇒ 3MP 会走 sample=2 ⇒ HDR 图永远显示成 SDR）。
    // 真机实测（2026-10-20）：用户反馈"app相册还是没有 HDR 显示"就是这个。
    //
    // 为什么不用 ImageDecoder：它**不解析 MPF 多图**（Ultra HDR 的 gainmap 是第二张
    // JPEG），只能解出 base —— 用 ImageDecoder 反而更彻底地丢 gainmap。
    // BitmapFactory（API 34+）专门补了 MPF/gainmap 解析，这是唯一正确的 API。
    //
    // 内存代价：12MP × 4B = 48MB（+转正一份）。查看页单张、且项目开了 largeHeap，
    // 可接受；OOM 时回落 [decodeFit]（丢 gainmap 但不黑屏）。
    val resolver = context.contentResolver
    val src = readBounds(resolver, uri)
    val (rotation, flip) = exifOrientationTransform(readOrientation(resolver, uri))
    try {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 1
            // ⚠ **不设 inPreferredColorSpace**（v0.11.56 实测：设了 EXTENDED_SRGB 也没生效，
            // 解出来仍是 sRGB）。Ultra HDR 的 base 本来就是 sRGB、HDR 增益由 gainmap 承担，
            // 官方示例也只 `BitmapFactory.decodeStream` 不设色彩空间。
            // 强制广色域反而可能让解码器做一次色彩转换（多一次拷贝且无益）。
        }
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return decodeFit(context, uri, maxW, maxH)
        // 诊断：确认 gainmap 真的解出来了（日志 `photo.view.hdr`）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // 诊断（v0.11.55）：把「解码出来的 HDR 能力」与「窗口的 HDR 能力」一起打出来，
            // 才能把"gainmap 没解出来"和"解出来了但显示层不应用"分开。
            // 真机实测（2026-10-20）：hasGainmap=True 但看不到效果 ⇒ 问题在显示层。
            val act = context.findActivity()
            val cfg = context.resources.configuration
            com.filmkit.app.EngineBridge.nativeAppendLog(
                com.filmkit.app.EngineBridge.LEVEL_INFO, "app", "photo.view.hdr",
                "{\"hasGainmap\":" + bmp.hasGainmap() +
                    ",\"w\":" + bmp.width + ",\"h\":" + bmp.height +
                    ",\"sample\":1" +
                    ",\"bmCs\":\"" + (bmp.colorSpace?.name ?: "?") + "\"" +
                    ",\"bmExt\":" + (bmp.colorSpace?.isWideGamut ?: false) +
                    // screenHdr 仅作参考 —— 它在用户设备上恒 false 但系统相册能显示 HDR，
                    // 故**不作为**是否切彩色模式的条件（见 PhotoViewScreen 的注释）
                    ",\"screenHdr\":" + (android.os.Build.VERSION.SDK_INT >= 34 && cfg.isScreenHdr) +
                    ",\"colorMode\":" + (act?.window?.attributes?.colorMode ?: -999) + "}",
            )
        }
        return orient(bmp, rotation, flip)
    } catch (e: OutOfMemoryError) {
        // ── v0.12.22：**catch 里不再二次解码**（相册滑动偶发闪退的根因）─────────────
        // 旧实现 catch 到 OOM 后又调 `decodeFit` 再解一次，而那一刻堆恰好见底：
        // 12MP 全尺寸 48MB + `orient` 的转正副本 48MB = 96MB，第一次 OOM 说明已经到顶，
        // 紧接着的 decodeFit 必然二次 OOM —— 而它在 catch 块里，**没有任何捕获** ⇒ 闪退。
        // 真机现象："相册左右滑动偶发闪退、日志里什么都没有"，与这条完全吻合。
        // 现在：记录日志 + 用小尺寸重试一次；仍失败则返回 null（查看页走占位）。
        com.filmkit.app.EngineBridge.nativeAppendLog(
            com.filmkit.app.EngineBridge.LEVEL_ERROR, "app", "photo.view.oom",
            "{\"w\":" + (maxW) + ",\"h\":" + (maxH) +
                ",\"detail\":\"" + (e.message ?: "oom") + "\"}",
        )
        // 用**小得多的目标尺寸**重试一次（1080×1920 ⇒ 2MP ≈ 8MB，而不是 12MP 的 48MB），
        // 并且用 runCatching 兜住一切（含再次 OOM）—— 保证这个 catch 里不会再有异常逃逸。
        // 代价：这一张失去 gainmap（显示 SDR），但**不闪退**。
        return runCatching { decodeFit(context, uri, 1080, 1920) }.getOrNull()
    } catch (e: Throwable) {
        // 同一条理由：catch 里不再做任何可能分配的动作。
        com.filmkit.app.EngineBridge.nativeAppendLog(
            com.filmkit.app.EngineBridge.LEVEL_ERROR, "app", "photo.view.decode_failed",
            "{\"error\":\"" + (e.message ?: e.toString()).replace("\"", "'") + "\"}",
        )
        return null
    }
}

/** 读原图尺寸（IO 线程，只解析文件头不分配像素）。读不到 / 不是位图 / 格式不支持 ⇒ null。 *//** 读原图尺寸（IO 线程，只解析文件头不分配像素）。读不到 / 不是位图 / 格式不支持 ⇒ null。 */
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

/**
 * 把纯 SDR JPEG 字节落盘到相册（v0.11.54，「导出 SDR 版」的落点）。
 *
 * 为什么单独一个函数：导出发生在查看页（Compose），而 MediaStore 的 pending 流程
 * 有固定套路（insert → IS_PENDING=1 → 写入 → IS_PENDING=0），写错了会在相册里留
 * 半截文件或不可见。集中一处便于以后复用。
 *
 * @return true = 已落盘；false = 失败（调用方提示用户）
 */
internal fun saveSdrJpeg(context: Context, jpeg: ByteArray): Boolean {
    if (jpeg.size < 4) return false
    val name = "FILMKIT_${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        .format(java.util.Date())}_SDR.jpg"
    return try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilmKit")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv,
            ) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
            cv.clear()
            cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, cv, null, null)
            true
        } else {
            val dir = java.io.File(
                context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "FilmKit",
            )
            dir.mkdirs()
            java.io.File(dir, name).outputStream().use { it.write(jpeg) }
            true
        }
    } catch (_: Throwable) {
        false
    }
}
