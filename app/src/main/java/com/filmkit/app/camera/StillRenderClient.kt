package com.filmkit.app.camera

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 拍照渲染线程（v0.10.85 重做）：与预览渲染**完全隔离**的线程 + EGL 上下文。
 *
 * 起因（真机实测）：预览与拍照共用 RenderClient 的 render.looper / EGL context
 * 时，拍照那条 1.6~3.4s 的合成链（对齐 + DFT 合并 + RCD + 高光重建 + 降噪）
 * 会把预览帧的 updateTexImage 消息全排在它后面 —— "按完快门立刻挪手机，画面
 * 完全冻住 1~2 秒"（不是掉帧，是停更）。PhotonCamera 的做法是每个后处理器各自
 * `eglCreateContext`（GlesMgcRawSpatialStacker / GlesMgcRawBaseFrameSelector /
 * OglBokehProcessor 均可查证），预览线程因此不受影响。
 *
 * 我们不需要 context 共享：拍照链输入是 CPU 侧 4 帧 ByteBuffer、输出是读回的
 * RGBA，**全程不碰任何预览纹理**。
 *
 * ## 生命周期（v0.10.83 崩溃的根因，本版钉死）
 *
 * v0.10.83 版本每次 `CameraController.stop()` 都 `quitSafely()` 销毁本线程并
 * 置空 handler，下次拍照再建一条新线程。问题：合成链 1.6~3.4s，stop 时旧线程
 * 往往**仍在跑 GL**，而新线程已经 `nativeStillStart()` —— 两个线程复用同一个
 * `g_renderer_still`（甚至旧线程随后 `delete` 它）→ 并发/释放后使用 → 只留
 * DNG、没有成像，随后闪退。日志被 fwrite 缓冲吞掉因此现场丢失。
 *
 * 本版：**只启动，不销毁**。
 *  - 线程与引擎实例**进程级常驻**，懒创建（首次真正拍照时才建，不预热）；
 *  - 所有 native 调用都 post 到同一条 Handler 队列 → 天然串行，不存在两个
 *    线程同时碰一个 context 的窗口；
 *  - 没有 stop 路径 = 不存在"杀掉正在跑 GL 的线程"，也不存在"新线程复用旧
 *    实例"的竞态。相机页退出时在飞的合成会照常跑完并落盘（照片不会丢）。
 *
 * 常驻代价见 handoff 实测（两套引擎实例的内存增量）；开启开关时才付出。
 * 首次拍照多付一次引擎 init（引擎侧日志 `engine.still_start` 的 initMs）。
 */
object StillRenderClient {

    private var handler: Handler? = null

    @Volatile
    private var ready = false

    /** 连续 init 失败计数与熔断（v0.10.85 审查建议）：失败若是显存/驱动这类
     *  持久原因，每张照片都白付一次 init（数百 ms~数秒）反而比原来的卡顿更糟。
     *  连续 3 次即本会话不再尝试，直接走预览线程。 */
    private var failStreak = 0

    @Volatile
    private var disabled = false

    /** 空闲释放（v0.10.88）：拍完这么久没有新任务就销毁拍照实例，把合成链留下的
     *  约 2GB 全分辨率中间纹理还给系统（实测常驻 2418MB，重建只要 ~60ms）。
     *  10s 是折中：连拍间隔通常 <10s 不会误释放，单张拍完能及时回收。 */
    private val idleReleaseMs = 10_000L

    /** 在拍照线程上执行销毁（与所有 native 调用同一条队列 → 必然排在在飞合成之后）。 */
    private val idleRelease = Runnable {
        if (!ready) return@Runnable
        try {
            EngineBridge.nativeStillDestroy()
        } catch (t: Throwable) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "engine", "render.still_destroy_error",
                kv("error" to (t.message ?: t.toString())),
            )
        }
        ready = false
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "engine", "render.still_idle_release", "{}",
        )
        EngineBridge.nativeFlushLogs()
    }

    private fun scheduleIdleRelease() {
        val h = handler ?: return
        h.removeCallbacks(idleRelease)
        h.postDelayed(idleRelease, idleReleaseMs)
    }

    private fun cancelIdleRelease() {
        handler?.removeCallbacks(idleRelease)
    }

    /** 懒启动（阻塞至多 5s）。失败返回 false → 调用方回退预览线程：
     *  "卡一下"总比"拍不了"好。已就绪时零开销直接返回。 */
    fun ensureStarted(): Boolean {
        synchronized(this) {
            if (ready) return true
            if (disabled) return false
            cancelIdleRelease()  // 别让到期释放和本次启动打架（同队列下只是白跑一趟）
            val h = handlerOrStart() ?: return false
            val latch = CountDownLatch(1)
            postStart(h, latch)
            latch.await(5, TimeUnit.SECONDS)
            return ready
        }
    }

    /** 异步预热（v0.10.85，不阻塞）：拍照入口调用，让引擎初始化与 burst 采集
     *  （≈0.5s）并行 —— 否则首次拍照时 ensureStarted 会在**预览渲染线程**上
     *  干等整段 init（编译全部 shader，实测数百 ms 起），那本身就是一次卡顿。
     *  init 幂等，重复调用只是多排一次队。 */
    fun startAsync() {
        synchronized(this) {
            if (ready || disabled) return
            cancelIdleRelease()
            val h = handlerOrStart() ?: return
            postStart(h, null)
        }
    }

    /** 取（必要时创建）拍照线程的 handler。 */
    private fun handlerOrStart(): Handler? {
        if (handler == null) {
            val t = HandlerThread("FilmStill").apply { start() }
            handler = Handler(t.looper)
        }
        return handler
    }

    /** 把一次 nativeStillStart 排进拍照线程队列；latch 非空时调用方等它完成。 */
    private fun postStart(h: Handler, latch: CountDownLatch?) {
        val t0 = SystemClock.elapsedRealtime()
        h.post {
            val ok = try {
                EngineBridge.nativeStillStart()
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "render.still_thread_error",
                    kv("error" to (t.message ?: t.toString())),
                )
                false
            }
            ready = ok
            if (ok) {
                failStreak = 0
            } else {
                failStreak++
                if (failStreak >= 3 && !disabled) {
                    disabled = true
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_ERROR, "engine", "render.still_disabled",
                        kv("failStreak" to failStreak),
                    )
                }
            }
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "engine", "render.still_thread_start",
                kv("ok" to ok, "initMs" to (SystemClock.elapsedRealtime() - t0)),
            )
            // 关键节点强制落盘：崩溃时 fwrite 缓冲会吞掉现场（v0.10.83 教训）
            EngineBridge.nativeFlushLogs()
            latch?.countDown()
        }
    }

    /** 在拍照线程上跑一次 MFNR 合成。onDone 在**拍照线程**回调（与 RenderClient
     *  同一约定），调用方只做轻量收尾（计数、入队保存）。 */
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
        if (h == null || !ready || frames.size != 4) {
            onDone(null, 0, 0)
            return
        }
        cancelIdleRelease()  // 新任务来了，取消待执行的空闲释放
        val tPost = SystemClock.elapsedRealtime()
        val posted = h.post {
            // 与 submitRaw 一致：静拍实际旋转 = 界面方向 + 传感器旋转(+1)
            val effectiveRot = (rotation + 1) % 4
            val rotatedW = if (effectiveRot % 2 == 1) height else width
            val rotatedH = if (effectiveRot % 2 == 1) width else height
            val t0 = SystemClock.elapsedRealtime()
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
                if (bytes == null) {
                    // 合成失败先自愈再谈回退（v0.10.89）：真机 0.10.88 出现过
                    // "空闲释放后重建，首次合成在纹理分配上失败"（raw.upload_failed），
                    // 直接回退预览线程会冻 3.4s。这里整体重建一次再试 —— 与首次启动
                    // 同构的路径实测总是成功；仍失败才交给调用方回退。
                    EngineBridge.nativeStillDestroy()
                    if (EngineBridge.nativeStillStart()) {
                        bytes = EngineBridge.nativeEngineSubmitRawBurst(
                            frames.toTypedArray(), frames.size, strides,
                            width, height,
                            meta.cfa, meta.blackPhase, meta.whiteLevel,
                            meta.neutral[0], meta.neutral[1], meta.neutral[2],
                            meta.ccm, meta.lsc, meta.lscCols, meta.lscRows,
                            rotation, demosaic, noiseStrength, meta.noiseModel,
                            meta.noiseSigma, refNoiseScale, useDft, alignL1, alignAb,
                            p0Sub, film, useStill = true,
                        )
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_WARN, "camera", "render.still_retry",
                            kv("ok" to (bytes != null)),
                        )
                    }
                }
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "render.raw_burst_error",
                    kv("error" to (t.message ?: t.toString())),
                )
            }
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "render.still_done",
                kv(
                    "ms" to (SystemClock.elapsedRealtime() - t0),
                    // 排队等待（post → 开始执行）：连拍时"第三张按不动"要靠它归因
                    "queuedMs" to (t0 - tPost),
                    "bytes" to (bytes?.size ?: -1),
                    "size" to "${rotatedW}x${rotatedH}",
                ),
            )
            // 失败节点立刻落盘：这条日志往往就是崩溃现场的最后一条
            if (bytes == null) EngineBridge.nativeFlushLogs()
            onDone(bytes, rotatedW, rotatedH)
            // 合成结束：起一段空闲计时，到期把 ~2GB 中间纹理还给系统
            scheduleIdleRelease()
        }
        if (!posted) onDone(null, 0, 0)
    }
}
