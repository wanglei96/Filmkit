package com.filmkit.app

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
// CompatibilityList 静态检查对 Mali-G1 误拒(实测),已改为强制创建
// import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * NN 降噪 spike（v0.10.100）：PMRID（ECCV20, Megvii 官方开源权重）TFLite FP16，
 * 在本机 X300 上实测「等效 12MP RAW 分块推理」的时延 / 吞吐 / 电池温度。
 *
 * 口径（与引擎 raw.render 一致的等效换算）：
 *  - PMRID 输入 = packed Bayer 4 通道，空间尺寸减半：4080×3072 → 2040×1536×4ch；
 *  - tile 512、输入重叠 16px（盲点网络边界伪影的标准对策）→ 步进 496，
 *    覆盖 2040×1536 需 ceil(2040/496)×ceil(1536/496) = 5×4 = 20 块；
 *  - warmup 3 块不计时（首块含 shader 编译/分配），正式跑 20 块取中位/P90。
 *
 * 委托：GPU delegate（GL ES compute）优先，兼容性检查不过或抛异常时明确报错不静默。
 * 数据：固定 seed 的合成输入（吞吐与数值无关，排除 denormal 干扰）。
 * 温度：BatteryManager 电池温度（0.1°C），只看增量（热质量大，短跑反映不了稳态）。
 */
object NnSpike {

    private const val MODEL = "pmrid_512_fp16.tflite"
    private const val TILE = 512
    private const val OVERLAP = 16
    private const val WARMUP = 3

    fun run(context: Context, preferGpu: Boolean, cpuThreads: Int): String {
        val model = loadModel(context) ?: return kv(
            "event" to "spike.nn", "ok" to false, "error" to "assets/$MODEL 缺失",
        )
        val batt0 = batteryTemp(context)
        var gpuDelegate: GpuDelegate? = null
        var interpreter: Interpreter? = null
        try {
            val options = Interpreter.Options()
            var delegate = "cpu"
            if (preferGpu) {
                // CompatibilityList 静态检查过保守(X300 Mali-G1 ES3.2 扩展全绿仍被拒,
                // 09:13:42 spike.nn 实测):改为强制创建,delegate 真跑不动时
                // Interpreter.run 会抛错,由外层 catch 记录 —— 拿真机实测说话。
                try {
                    gpuDelegate = GpuDelegate()
                    options.addDelegate(gpuDelegate)
                    delegate = "gpu"
                } catch (e: Throwable) {
                    gpuDelegate = null
                    options.numThreads = cpuThreads
                    delegate = "cpu-fallback"
                }
            } else {
                options.numThreads = cpuThreads
            }
            interpreter = Interpreter(model, options)
            return runTiles(interpreter, context, delegate, cpuThreads, batt0)
        } catch (e: Throwable) {
            return kv(
                "event" to "spike.nn", "ok" to false,
                "delegate" to if (gpuDelegate != null) "gpu" else "cpu",
                "threads" to cpuThreads,
                "error" to "${e.javaClass.simpleName}: ${e.message}",
            )
        } finally {
            interpreter?.close()
            gpuDelegate?.close()
        }
    }

    private fun runTiles(
        interpreter: Interpreter,
        context: Context,
        delegate: String,
        cpuThreads: Int,
        batt0: Int?,
    ): String {
        val inBuf = directInput(TILE)
        val outBuf = directInput(TILE)
        val tilesX = 5 // ceil(2040/496)
        val tilesY = 4 // ceil(1536/496)
        val total = tilesX * tilesY

        val times = ArrayList<Long>(total)
        for (i in 0 until total + WARMUP) {
            inBuf.rewind(); outBuf.rewind()
            val t0 = SystemClock.elapsedRealtimeNanos()
            interpreter.run(inBuf, outBuf)
            val t1 = SystemClock.elapsedRealtimeNanos()
            if (i >= WARMUP) times += (t1 - t0) / 1_000_000
        }
        times.sort()
        val med = times[times.size / 2].toDouble()
        val p90 = times[(times.size * 9) / 10].toDouble()
        val sum = times.sum()
        val batt1 = batteryTemp(context)
        // 等效全图：12MP RAW（4080×3072 Bayer），吞吐 = 12.58MP / 20 块总耗时
        val mpTotal = 12.58
        val mpPerS = mpTotal / (sum / 1000.0)

        return kv(
            "event" to "spike.nn", "ok" to true,
            "delegate" to delegate, "threads" to cpuThreads,
            "model" to MODEL, "tile" to TILE, "overlap" to OVERLAP,
            "tiles" to total, "mp" to "%.2f".format(mpTotal),
            "tileMedMs" to "%.1f".format(med), "tileP90Ms" to "%.1f".format(p90),
            "totalMs" to sum, "mpPerS" to "%.2f".format(mpPerS),
            "battT0" to (batt0 ?: -1), "battT1" to (batt1 ?: -1),
        )
    }

    private fun directInput(tile: Int): ByteBuffer {
        // onnx2tf 产物是 NHWC([1,H,W,4],TF 惯例)——按像素交错 4 通道填充
        val b = ByteBuffer.allocateDirect(1 * tile * tile * 4 * 4)
        b.order(ByteOrder.nativeOrder())
        var x = 2026u
        for (i in 0 until tile * tile * 4) {
            // xorshift 固定 seed:值域 [0,1),接近扣黑阶后的 RAW 归一区间
            x = x xor (x shl 13); x = x xor (x shr 17); x = x xor (x shl 5)
            b.putFloat((x.toFloat() / 4294967296f).coerceIn(0f, 1f))
        }
        b.rewind()
        return b
    }

    private fun loadModel(context: Context): MappedByteBuffer? = try {
        context.assets.openFd(MODEL).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength,
            )
        }
    } catch (_: Throwable) {
        null
    }

    private fun batteryTemp(context: Context): Int? = try {
        // javap 实锤:BatteryManager 没有 BATTERY_PROPERTY_TEMPERATURE 常量;
        // 电池温度在 sticky 广播 ACTION_BATTERY_CHANGED 的 EXTRA_TEMPERATURE(0.1°C)
        val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }
    } catch (_: Throwable) {
        null
    }
}
