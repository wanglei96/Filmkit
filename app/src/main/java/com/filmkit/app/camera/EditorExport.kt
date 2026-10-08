package com.filmkit.app.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv
import com.filmkit.app.ui.CameraPresets
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * 编辑器导出（E1-4 / ADR-3）：参数起点、编码、**另存新片**与**覆盖原片**。
 *
 * 这里只做"参数 → 编码 → 落盘"的收尾；渲染必须先成功（调用方拿到成片 Bitmap 才进来）。
 */

/**
 * 编辑器参数数组（集成点 I-3）：以 [CameraPresets.standard]（78 槽，ADR-1 单一参数源）
 * 为底，把参数快照里**存在且有限**的槽位覆盖上去。
 *
 * 为什么不是"直接返回快照数组"：
 *  - 快照来自历史版本时长度可能少于当前契约（60 → 78 这类契约升级），缺的槽位必须是
 *    基线值而不是 0（0 在 [5..10] latitude 语义下是"极端 S 曲线"，不是"无效果"）；
 *  - 快照里的 NaN/Inf 是坏数据（JSON 写入侧已净化，读取侧仍防一手）：当作"没记录"。
 *
 * @param raw 快照参数；null = 这张没有参数记录（ADR-4：调用方要提示"已用标准基线"）
 */
internal fun editorParams(raw: DoubleArray?): DoubleArray {
    val base = CameraPresets.standard()
    if (raw == null) return base
    val n = min(raw.size, base.size)
    for (i in 0 until n) {
        val v = raw[i]
        if (v.isFinite()) base[i] = v
    }
    return base
}

/** 另存文件名：`FILMKIT_20261008-213000_edit.jpg`（同拍摄链路的命名规则，`_edit` 区分原片） */
internal fun editorExportName(timeMs: Long): String =
    "FILMKIT_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timeMs))}_edit.jpg"

/**
 * 编码成片 JPEG。与拍摄链路同口径：native **4:4:4** 优先（4:2:0 的色度下采样会在强
 * 亮度边缘两侧串色），native 不可用/失败时回退 [Bitmap.compress]（宁可退到 4:2:0，
 * 不可丢成片）。
 */
internal fun encodeEditorJpeg(bmp: Bitmap, quality: Int): ByteArray? {
    runCatching { EngineBridge.nativeEncodeJpeg444(bmp, quality) }.getOrNull()?.let {
        if (it.isNotEmpty()) return it
    }
    return runCatching {
        ByteArrayOutputStream().also { bos ->
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)) return null
        }.toByteArray()
    }.getOrNull()
}

/**
 * 另存为新片（`Pictures/FilmKit`，MediaStore `image/jpeg`）。
 *
 * 与原片完全无关：新建条目 + `IS_PENDING` 写完再放开可见（写一半被系统杀进程也不会
 * 在相册里留下半张图）。失败只记日志并返回 null —— 用户的原片不受任何影响。
 */
internal fun saveEditorCopy(context: Context, jpeg: ByteArray, name: String, timeMs: Long): Uri? {
    var inserted: Uri? = null
    try {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilmKit")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            inserted = uri
            context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: return null
            cv.clear()
            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, cv, null, null)
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FilmKit")
            dir.mkdirs()
            val f = File(dir, name)
            f.outputStream().use { it.write(jpeg) }
            inserted = Uri.fromFile(f)
        }
        writeEditorExif(context, inserted, timeMs, null)
        return inserted
    } catch (t: Throwable) {
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_ERROR, "camera", "photo.edit_save_failed",
            kv("name" to name, "error" to (t.message ?: t.toString())),
        )
        // 写失败时把刚插入的 pending 条目删掉，别在相册里留垃圾（删除失败也无所谓）
        inserted?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        return null
    }
}

/** 覆盖原片的结果：三种状态对应三句**必须不同**的用户文案（不许糊成一句"失败"） */
internal sealed interface OverwriteResult {
    /** 已覆盖成功 */
    data object Ok : OverwriteResult

    /** 覆盖失败，**原片完好**（没写进去，或写坏了但已用备份回写成功） */
    data object FailedOriginalIntact : OverwriteResult

    /** 覆盖失败且**原片可能已损坏**（必须如实告知用户，不能假装没事） */
    data object FailedOriginalDamaged : OverwriteResult
}

/**
 * 覆盖原片（危险操作，ADR-3）。**安全顺序**（本函数的全部意义所在）：
 *  1. 调用方已经拿到"渲染成功 + 编码成功"的 JPEG 字节 —— 本函数只负责写；
 *  2. 写之前先把原片字节读进内存做**备份**（成片 JPEG 通常 3~8MB）；备份失败 = 直接
 *     放弃覆盖（拿不到退路就不开这一枪），原片必然完好；
 *  3. `openOutputStream(uri, "wt")` 覆盖写；
 *  4. 任何异常 → 用备份回写原片，并**如实回报**恢复是否成功。
 *
 * [backup] 是调用方**已经读出来**的原片字节（覆盖成功后要拿它拷 EXIF，所以无论如何
 * 都得在覆盖前读一次）；传 null 时本函数自己读一次 —— 同一个文件不为同一件事读两遍。
 *
 * 为什么不用 `IS_PENDING`：它是"**新插入**条目的可见性开关"，对已存在条目没有原子
 * 替换语义；MediaStore 也不提供原地原子替换。`"wt"`（truncate）是唯一能改写同一 URI
 * 内容的模式 —— 所以原子性只能靠"先渲染编码成功 + 写前备份 + 失败回滚"来兜，
 * 绝不允许"先删后写/先截断再想办法"。
 */
internal fun overwriteOriginalPhoto(
    context: Context, uri: Uri, jpeg: ByteArray, backup: ByteArray?,
): OverwriteResult {
    val bak = backup ?: runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()
    if (bak == null || bak.isEmpty()) {
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_WARN, "camera", "photo.edit_overwrite_backup_failed", "{}",
        )
        return OverwriteResult.FailedOriginalIntact // 一个字节都没写 = 原片必然完好
    }

    val wrote = runCatching {
        val os = context.contentResolver.openOutputStream(uri, "wt")
        if (os == null) false else {
            os.use { it.write(jpeg) }
            true
        }
    }.getOrDefault(false)
    if (wrote) return OverwriteResult.Ok

    // 写失败：回写备份（尽力恢复原片）
    val restored = runCatching {
        val os = context.contentResolver.openOutputStream(uri, "wt")
        if (os == null) false else {
            os.use { it.write(bak) }
            true
        }
    }.getOrDefault(false)
    EngineBridge.nativeAppendLog(
        if (restored) EngineBridge.LEVEL_WARN else EngineBridge.LEVEL_ERROR,
        "camera", "photo.edit_overwrite_failed",
        kv("restored" to restored, "backupBytes" to bak.size),
    )
    return if (restored) OverwriteResult.FailedOriginalIntact else OverwriteResult.FailedOriginalDamaged
}

/** 覆盖成功后刷新的元数据：时间 + 方向（像素已转正 ⇒ 恒为 1） */
internal fun refreshOverwrittenExif(context: Context, uri: Uri, timeMs: Long, srcJpeg: ByteArray?) {
    writeEditorExif(context, uri, timeMs, srcJpeg)
}

/**
 * 补写 EXIF：时间 + `Orientation=1`（成片像素在渲染端已转正，方向恒为标准值）。
 * [srcJpeg] 非空时（覆盖原片）把**原片的拍摄信息**一并带过来 —— 覆盖只该改像素，
 * 不该让用户丢掉 ISO/机型/光圈这些拍摄记录。
 */
private fun writeEditorExif(context: Context, uri: Uri?, timeMs: Long, srcJpeg: ByteArray?) {
    if (uri == null) return
    runCatching {
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: return
        val exif = ExifInterface(pfd.fileDescriptor)
        val dt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(timeMs))
        exif.setAttribute(ExifInterface.TAG_DATETIME, dt)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dt)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
        if (srcJpeg != null) {
            // 逐个 tag 拷贝：单个字段格式不被 ExifInterface 接受时只丢那一个（runCatching），
            // 不影响其它字段与整张图。ExposureTime 这类"兼容标签"必须是十进制秒数字符串，
            // 从源 EXIF 读出来的可能是 "1/125" ⇒ setAttribute 内部 parse 失败静默丢弃，
            // 属可接受损失（不为了一个字段把整段拷贝搞崩）。
            val src = runCatching {
                ExifInterface(java.io.ByteArrayInputStream(srcJpeg))
            }.getOrNull()
            src?.let { s ->
                KEEP_EXIF_TAGS.forEach { tag ->
                    runCatching {
                        s.getAttribute(tag)?.let { v -> exif.setAttribute(tag, v) }
                    }
                }
                // 方向必须是我们写的 1（上面的循环可能把原方向又拷回来）
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
            }
        }
        exif.saveAttributes()
    }.onFailure {
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_WARN, "camera", "photo.edit_exif_failed",
            kv("error" to (it.message ?: it.toString())),
        )
    }
}

/** 覆盖时保留的拍摄元数据（像素变了，拍摄记录不该跟着丢） */
private val KEEP_EXIF_TAGS = listOf(
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_LENS_MODEL,
    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_WHITE_BALANCE,
    ExifInterface.TAG_EXPOSURE_PROGRAM,
    ExifInterface.TAG_FLASH,
    ExifInterface.TAG_SOFTWARE,
)
