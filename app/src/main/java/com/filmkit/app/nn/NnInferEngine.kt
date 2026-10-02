package com.filmkit.app.nn

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.tensorflow.lite.Interpreter
// CompatibilityList 静态检查对 Mali-G1 误拒（v0.10.100 真机实锤：扩展全绿仍被拒），
// 已改为强制创建 —— import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate

/**
 * NN 降噪推理引擎（v0.10.102）：PMRID（ECCV20, Megvii 官方权重）TFLite FP16，
 * 供拍照链的 C++ 侧（FrameRenderer::runNnPass）逐 tile 同步回调。
 *
 * 线程模型（与拍照线程「只启动不销毁」同哲学）：
 *  - 常驻 HandlerThread「NnInfer」，Interpreter + GpuDelegate 惰性创建、只建不销毁
 *    （GPU delegate 创建有数百 ms 编译成本，反复重建不可接受）；
 *  - GPU delegate 自管 EGL context：**绝不在拍照线程上跑推理** —— delegate 内部
 *    makeCurrent 自己的 context 会顶掉拍照线程的引擎 current（恢复行为不保证），
 *    拍照线程随后的一切 GL 调用都会落到错误的 context 上。C++ 侧调用 inferTile
 *    时本方法阻塞在拍照线程，推理 post 到 NnInfer 线程、latch 同步等待；
 *  - inferTile 由 JNI 直接调用（拍照合成调用栈内，JNIEnv 有效），不是 Kotlin 入口。
 *
 * 数值口径（与 tools/nn/quality_eval.py 一致）：输入输出均为 NHWC
 * [1,512,512,4] float32 packed Bayer（2×2 块空间序），C++ 侧已完成
 * 除/乘回 WB 与 ×/÷(959/256) 的 VST 域变换，本类只做 tile 级推理。
 */
object NnInferEngine {

    private const val MODEL = "pmrid_512_fp16.tflite"
    const val TILE = 512

    /** 单 tile direct buffer 字节数：512×512×4ch×float32。 */
    const val TILE_BYTES = TILE * TILE * 4 * 4
    private const val CPU_THREADS = 4

    @Volatile
    private var appContext: Context? = null

    private var handler: Handler? = null
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    @Volatile
    private var delegate = "" // "gpu" / "cpu"（"" = 未初始化）

    @Volatile
    private var initError = ""

    // tile 缓冲（getBufs 交给 C++ 拿地址后由 C++ 直接 memcpy，本对象常驻持有）
    private var inBuf: ByteBuffer? = null
    private var outBuf: ByteBuffer? = null

    /** App 启动时注入 application context（JNI 调用栈里拿不到 context）。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    private fun handlerOrStart(): Handler =
        handler ?: Handler(HandlerThread("NnInfer").apply { start() }.looper)
            .also { handler = it }

    /** C++ 回桥首次连接时取 tile 缓冲（direct buffer 地址随对象常驻保持稳定）。 */
    @JvmStatic
    fun getBufs(): Array<ByteBuffer> {
        if (inBuf == null) {
            inBuf = ByteBuffer.allocateDirect(TILE_BYTES).order(ByteOrder.nativeOrder())
        }
        if (outBuf == null) {
            outBuf = ByteBuffer.allocateDirect(TILE_BYTES).order(ByteOrder.nativeOrder())
        }
        return arrayOf(inBuf!!, outBuf!!)
    }

    /** C++ 回桥读取当前 delegate（"gpu"/"cpu"/"pending"），进 raw.render 的 nnDel。 */
    @JvmStatic
    fun delegateInfo(): String = if (delegate.isEmpty()) "pending" else delegate

    /**
     * JNI 推理入口（仅 C++ 调用）：inBuf 已由 C++ 填好，推理完成后 C++ 从 outBuf 取。
     * 返回 0 = 成功；<0 = 失败原因码（C++ 收到即整张回退 P0 原链 —— 照片优先）。
     * 阻塞等待在调用线程（拍照合成栈内），推理在 NnInfer 线程执行。
     */
    @JvmStatic
    fun inferTile(): Int {
        val ctx = appContext ?: return -1
        if (initError.isNotEmpty()) return -2
        val h = handlerOrStart()
        if (!ensureInit(h, ctx)) return -2
        val latch = CountDownLatch(1)
        val err = intArrayOf(0)
        h.post {
            err[0] = runTile()
            latch.countDown()
        }
        // 首块含 GPU shader 编译（数百 ms 级），常态 26ms —— 超时只可能是环境坏了
        return if (latch.await(30, TimeUnit.SECONDS)) err[0] else -5
    }

    private fun runTile(): Int = try {
        val i = interpreter ?: return -3
        val ib = inBuf ?: return -3
        val ob = outBuf ?: return -3
        ib.rewind()
        ob.rewind()
        i.run(ib, ob)
        0
    } catch (t: Throwable) {
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_ERROR, "engine", "nn.run_fail",
            kv("delegate" to delegate, "error" to (t.message ?: t.toString())),
        )
        // GPU 半坏状态不能持续烧每张照片：运行期失败即销毁重建为 CPU（下张生效）
        if (delegate == "gpu") rebuildAsCpu()
        -4
    }

    private fun rebuildAsCpu() {
        handler?.post {
            try {
                interpreter?.close()
            } catch (_: Throwable) {
            }
            try {
                gpuDelegate?.close()
            } catch (_: Throwable) {
            }
            interpreter = null
            gpuDelegate = null
            initError = ""
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "engine", "nn.gpu_circuit",
                kv("action" to "rebuild_as_cpu"),
            )
        }
    }

    /** 惰性初始化（在 NnInfer 线程上创建 Interpreter；同步等待结果）。 */
    private fun ensureInit(h: Handler, ctx: Context): Boolean {
        if (interpreter != null) return true
        synchronized(this) {
            if (interpreter != null) return true
            if (initError.isNotEmpty()) return false
        }
        val latch = CountDownLatch(1)
        val ok = booleanArrayOf(false)
        h.post {
            ok[0] = try {
                val model = loadModel(ctx)
                    ?: throw IllegalStateException("assets/$MODEL 缺失")
                val opts = Interpreter.Options()
                // GPU 强制创建（CompatibilityList 误拒 Mali-G1，v0.10.100 实锤）；
                // 创建失败降 CPU（XNNPACK×4，3.45MP/s 兜底），真跑不动由
                // interpreter.run 抛错 → runTile 熔断重建 CPU。
                try {
                    gpuDelegate = GpuDelegate()
                    opts.addDelegate(gpuDelegate)
                    delegate = "gpu"
                } catch (e: Throwable) {
                    gpuDelegate = null
                    opts.numThreads = CPU_THREADS
                    delegate = "cpu"
                }
                interpreter = Interpreter(model, opts)
                true
            } catch (t: Throwable) {
                initError = "${t.javaClass.simpleName}: ${t.message}"
                false
            }
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "engine", "nn.init",
                kv("delegate" to delegate, "ok" to ok[0], "error" to initError),
            )
            latch.countDown()
        }
        latch.await(60, TimeUnit.SECONDS)
        return ok[0]
    }

    private fun loadModel(ctx: Context): ByteBuffer? = try {
        ctx.assets.openFd(MODEL).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY,
                fd.startOffset, fd.declaredLength,
            )
        }
    } catch (_: Throwable) {
        null
    }
}
