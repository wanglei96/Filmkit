package com.filmkit.app.lut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv
import java.io.File
import kotlin.math.roundToInt

/**
 * LUT 卡片预览图（M4）。
 *
 * **省电的关键是「一次性生成 + 缓存」，不是「实时渲染」**：
 *  - 实时缩略图（当前取景帧 × 每套 LUT）每打开一次面板就要 N 次渲染 + GPU 读回，场景一变还得重算；
 *  - 本方案用一张**固定测试图**（`assets/lut-preview-src.png`，CC0 照片，见同名 LICENSE）
 *    × 该 LUT 采样**一次** → 写 `filesDir/lut-preview/<key>.png` → 之后只读缓存，运行时零渲染。
 *
 * **内置与用户导入的 LUT 走同一条路径**：导入时调用 [load] 生成一次即可，
 * 因此后续的导入功能不需要另造一套预览机制。
 */
object LutPreview {

    private const val SRC_ASSET = "lut-preview-src.png"
    private const val SRC_SIZE = 256
    private const val OUT_SIZE = 108

    private var srcPixels: IntArray? = null

    /** 取预览图：命中缓存直接解码；否则用测试图采样生成并落盘。IO 线程调用。 */
    fun load(context: Context, key: String, lut: CubeLut): Bitmap? {
        val f = File(File(context.filesDir, "lut-preview"), "$key.png")
        if (f.isFile) {
            runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()?.let { return it }
        }
        val src = sourcePixels(context) ?: return null
        val t0 = SystemClock.elapsedRealtime()
        val out = render(src, lut)
        runCatching {
            f.parentFile?.mkdirs()
            f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "engine", "lut.preview",
            kv("key" to key, "ms" to (SystemClock.elapsedRealtime() - t0), "cached" to f.isFile),
        )
        return out
    }

    /** 测试图像素（进程内只解码一次） */
    private fun sourcePixels(context: Context): IntArray? {
        srcPixels?.let { return it }
        return runCatching {
            val bm = context.assets.open(SRC_ASSET).use { BitmapFactory.decodeStream(it) }
                ?: return null
            val s = if (bm.width != SRC_SIZE || bm.height != SRC_SIZE) {
                Bitmap.createScaledBitmap(bm, SRC_SIZE, SRC_SIZE, true)
            } else {
                bm
            }
            IntArray(SRC_SIZE * SRC_SIZE).also {
                s.getPixels(it, 0, SRC_SIZE, 0, 0, SRC_SIZE, SRC_SIZE)
                srcPixels = it
            }
        }.getOrElse { null }
    }

    /** 测试图 × LUT → 预览（纯 CPU 三线性；与渲染侧四面体的差异远小于卡片所需精度） */
    private fun render(src: IntArray, lut: CubeLut): Bitmap {
        val n = lut.size
        val max = n - 1
        val d = lut.data
        val out = IntArray(src.size)
        for (i in src.indices) {
            val c = src[i]
            val pr = ((c shr 16) and 0xFF) / 255f * max
            val pg = ((c shr 8) and 0xFF) / 255f * max
            val pb = (c and 0xFF) / 255f * max
            val r0 = pr.toInt().coerceIn(0, max - 1)
            val g0 = pg.toInt().coerceIn(0, max - 1)
            val b0 = pb.toInt().coerceIn(0, max - 1)
            val fr = pr - r0
            val fg = pg - g0
            val fb = pb - b0
            var a0 = 0f
            var a1 = 0f
            var a2 = 0f
            for (db in 0..1) {
                val wb = if (db == 1) fb else 1f - fb
                for (dg in 0..1) {
                    val wg = wb * (if (dg == 1) fg else 1f - fg)
                    for (dr in 0..1) {
                        val w = wg * (if (dr == 1) fr else 1f - fr)
                        val k = (((b0 + db) * n + (g0 + dg)) * n + (r0 + dr)) * 3
                        a0 += d[k] * w
                        a1 += d[k + 1] * w
                        a2 += d[k + 2] * w
                    }
                }
            }
            out[i] = (0xFF shl 24) or (to255(a0) shl 16) or (to255(a1) shl 8) or to255(a2)
        }
        val big = Bitmap.createBitmap(SRC_SIZE, SRC_SIZE, Bitmap.Config.ARGB_8888)
        big.setPixels(out, 0, SRC_SIZE, 0, 0, SRC_SIZE, SRC_SIZE)
        val small = Bitmap.createScaledBitmap(big, OUT_SIZE, OUT_SIZE, true)
        if (small !== big) big.recycle()
        return small
    }

    private fun to255(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt()
}
