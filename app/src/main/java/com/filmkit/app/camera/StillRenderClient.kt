package com.filmkit.app.camera

import android.os.Handler
import android.os.HandlerThread
import com.filmkit.app.EngineBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 拍照渲染线程（v0.10.83）：与预览渲染**完全隔离**的线程 + EGL 上下文。
 *
 * 起因（真机实测）：预览与拍照共用 RenderClient 的 render.looper / EGL context
 * 时，拍照那条 1.6~3.4s 的合成链（对齐 + DFT 合并 + RCD + 高光重建 + 降噪）
 * 会把预览帧的 updateTexImage 消息全部排在它后面 —— 用户"按完快门立刻挪手机，
 * 画面完全冻住 1~2 秒"。PhotonCamera 的做法是每个后处理器各自
 * eglCreateContext 建独立 context（GlesMgcRawSpatialStacker /
 * GlesMgcRawBaseFrameSelector / OglBokehProcessor 均可查证），预览线程因此
 * 不受影响。
 *
 * 我们不需要 PC 那套 context 共享：拍照链的输入是 CPU 侧 4 帧 ByteBuffer、
 * 输出是读回的 RGBA，**全程不碰任何预览纹理** —— 两侧天然隔离。
 *
 * 线程模型：本类的 handler 是唯一的执行线程，nativeStillStart/Stop 与每次
 * submitRawBurst 都在它上面串行执行；RenderClient（预览）那边照旧，互不排队。
 */
class StillRenderClient {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var ready = false

    /** 预热（不阻塞调用方）：预览起来后就把拍照 context/引擎建好，首次拍照
     *  不必等初始化。失败也不抛——首次拍照会走 ensureStarted 再试一次。 */
    fun prewarm() {
        synchronized(this) {
            if (ready) return
            val t = thread
            if (t == null || !t.isAlive) {
                val nt = HandlerThread("FilmStill").apply { start() }
                thread = nt
                handler = Handler(nt.looper)
            }
            handler?.post {
                val ok = EngineBridge.nativeStillStart()
                ready = ok
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "engine", "render.still_thread_start",
                    "{\"ok\":$ok,\"prewarm\":true}",
                )
            }
        }
    }

    /** 懒启动：首次拍照时才建 context 与引擎实例（常驻会白占一套纹理/程序）。
     *  启动完成后返回 true；失败时拍照调用方会退回预览线程（见 CameraController）。 */
    fun ensureStarted(): Boolean {
        synchronized(this) {
            if (ready) return true
            val t = thread
            if (t == null || !t.isAlive) {
                val nt = HandlerThread("FilmStill").apply { start() }
                thread = nt
                handler = Handler(nt.looper)
            }
            val h = handler ?: return false
            val latch = CountDownLatch(1)
            var ok = false
            h.post {
                ok = EngineBridge.nativeStillStart()
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "engine", "render.still_thread_start",
                    "{\"ok\":$ok}",
                )
                latch.countDown()
            }
            latch.await(5, TimeUnit.SECONDS)
            ready = ok
            return ok
        }
    }

    /** 在拍照线程上跑一次 MFNR 合成。onDone 在**拍照线程**回调（同 RenderClient
     *  的约定），调用方只做轻量收尾（计数、入队保存）。 */
    fun submitRawBurst(
        frames: List<java.nio.ByteBuffer>,
        strides: IntArray,
        width: Int,
        height: Int,
        meta: RenderClient.RawFrameMeta,
        rotation: Int,
        film: DoubleArray,
        demosaic: Int,
        noiseStrength: Float,
        refNoiseScale: Float,
        useDft: Boolean,
        alignL1: Boolean,
        alignAb: Boolean,
        p0Sub: Boolean,
        onDone: (ByteArray?, Int, Int) -> Unit,
    ) {
        val h = handler
        if (h == null || frames.size != 4) {
            onDone(null, 0, 0)
            return
        }
        h.post {
            // 与 submitRaw 一致：静拍实际旋转 = 界面方向 + 传感器旋转(+1)
            val effectiveRot = (rotation + 1) % 4
            val rotatedW = if (effectiveRot % 2 == 1) height else width
            val rotatedH = if (effectiveRot % 2 == 1) width else height
            var bytes: ByteArray? = null
            try {
                bytes = EngineBridge.nativeEngineSubmitRawBurst(
                    frames.toTypedArray(), frames.size, strides,
                    width, height,
                    meta.cfa, meta.blackPhase, meta.whiteLevel,
                    meta.neutral[0], meta.neutral[1], meta.neutral[2],
                    meta.ccm, meta.lsc, meta.lscCols, meta.lscRows,
                    rotation, demosaic, noiseStrength, meta.noiseModel,
                    meta.noiseSigma, refNoiseScale, useDft, alignL1, alignAb, p0Sub,
                    film, useStill = true,
                )
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "render.raw_burst_error",
                    com.filmkit.app.kv("error" to (t.message ?: t.toString())),
                )
            }
            onDone(bytes, rotatedW, rotatedH)
        }
    }

    fun stop() {
        synchronized(this) {
            val h = handler
            val t = thread
            if (h != null && ready) {
                val latch = CountDownLatch(1)
                h.post {
                    EngineBridge.nativeStillStop()
                    latch.countDown()
                }
                latch.await(2, TimeUnit.SECONDS)
            }
            ready = false
            thread?.quitSafely()
            thread = null
            handler = null
            t?.join(1000)
        }
    }
}
