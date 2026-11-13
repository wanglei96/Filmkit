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

    /** 连续 init 失败计数与熔断（v0.10.85 审查建议）：失败若是显存/驱动这类
     *  持久原因，每张照片都白付一次 init（数百 ms~数秒）反而比原来的卡顿更糟。
     *  连续 3 次即本会话不再尝试，直接走预览线程。 */
    private var failStreak = 0

    @Volatile
    private var disabled = false

    /** 0.13.21：**取消按时间释放**，改为生命周期驱动（相机页可见 + 亮屏保活）。
     *
     *  理由（真机日志 22:58~23:00）：定时器每 10s 销毁一次，重建后的那张
     *  totalMs 1615→2858ms、fps 掉到 19/23 —— 时间释放本身在制造"冷启动第一张"。
     *  显存/纹理驻留**不耗电**（UMA 下就是系统内存，2.4GB 对 16GB 机型可接受），
     *  真正耗电且卡顿的是重复 init + 2.4GB 重新分配。
     *  释放时机 = 相机页不可见 / 熄屏 / 系统内存压力（由 CameraScreen 生命周期调用
     *  [releaseNow]）；用户在用相机就一直保活，并在 resume 后预热。 */
    fun releaseNow() {
        val h = handler ?: return
        h.post {
            if (!ready_) return@post
            try {
                EngineBridge.nativeStillDestroy()
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "render.still_destroy_error",
                    kv("error" to (t.message ?: t.toString())),
                )
            }
            ready_ = false
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "engine", "render.still_release",
                "{\"why\":\"lifecycle\"}",
            )
            EngineBridge.nativeFlushLogs()
        }
    }

    /** 懒启动（阻塞至多 5s）。失败返回 false → 调用方回退预览线程：
     *  "卡一下"总比"拍不了"好。已就绪时零开销直接返回。 */
    fun ensureStarted(): Boolean {
        synchronized(this) {
            if (ready_) return true
            if (disabled) return false
            val h = handlerOrStart() ?: return false
            val latch = CountDownLatch(1)
            postStart(h, latch)
            latch.await(5, TimeUnit.SECONDS)
            return ready_
        }
    }

    /** 异步预热（v0.10.85，不阻塞）：拍照入口调用，让引擎初始化与 burst 采集
     *  （≈0.5s）并行 —— 否则首次拍照时 ensureStarted 会在**预览渲染线程**上
     *  干等整段 init（编译全部 shader，实测数百 ms 起），那本身就是一次卡顿。
     *  init 幂等，重复调用只是多排一次队。 */
    fun startAsync() {
        synchronized(this) {
            if (ready_ || disabled) return
            val h = handlerOrStart() ?: return
            postStart(h, null)
        }
    }

    /** 取（必要时创建）拍照线程的 handler。 */
    private fun handlerOrStart(): Handler? {
        if (handler == null) {
            // 0.13.11：线程优先级回到默认。0.13.10 降到 BACKGROUND（照 PC
            // lowerCurrentThreadPriority）后，GPU 段已由 still context 的 LOW 优先级
            // 让路，但 CPU 段（对齐/编码/打包）也被 UI 抢占 —— 实测 totalMs 多
            // 700~1200ms。预览本来就只吃一点 CPU，BACKGROUND 是重复让路。
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
            ready_ = ok
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

    /** 0.13.21 预热：把大纹理与 satmask 缓冲先分配好（见 nativeStillWarmup）。
     *  未就绪时静默跳过 —— 调用方（相机页可见 2s 后）已经先 startAsync，队列串行
     *  保证 warmup 排在 start 之后。失败只记日志，不影响拍照路径。 */
    fun warmup(w: Int, h: Int, rot: Int) {
        if (w <= 0 || h <= 0) return
        // 不在**调用线程**上判 ready_：startAsync 只是排队，ready_ 要等 start 执行完
        // 才变 true。这里同样只排队，真正的就绪判断放到队列内（start 必然先执行）。
        val hd = handler ?: return
        hd.post {
            if (!ready_) return@post
            val t0 = SystemClock.elapsedRealtime()
            val ok = try {
                EngineBridge.nativeStillWarmup(w, h, rot)
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_WARN, "engine", "render.still_warmup_error",
                    kv("error" to (t.message ?: t.toString())),
                )
                false
            }
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "engine", "render.still_warmup",
                kv("ok" to ok, "ms" to (SystemClock.elapsedRealtime() - t0),
                   "size" to "${w}x$h", "rot" to rot),
            )
        }
    }

    /** 是否就绪（v0.10.93 单帧路由用）：调用方（渲染线程上的 tryProcessRaw）只做
     *  非阻塞判断 —— 不 ready 就直接走预览线程，**绝不**在预览渲染线程上干等
     *  ensureStarted（那本身就是一次卡顿，0.10.85 的教训）。预热由相机页可见后的
     *  startAsync + warmup 负责，本张没赶上就下一张。 */
    val ready: Boolean
        get() = ready_

    @Volatile
    private var ready_ = false

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
        nnDn: Boolean,
        shortRatio: Float = 1f,
        shortSat: Float = -1f,
        tenBit: Boolean = false,
        hdrEv: FloatArray? = null,
        hdrGainArr: FloatArray? = null,
        hdrHighIdx: Int = -1,
        onDone: (ByteArray?, Int, Int) -> Unit,
    ) {
        val h = handler
        if (h == null || !ready_ || frames.size != 4) {
            onDone(null, 0, 0)
            return
        }
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
                    nnDn, shortRatio = shortRatio, shortSat = shortSat,
                    film = film, useStill = true, tenBit = tenBit,
                    hdrEv = hdrEv, hdrGainArr = hdrGainArr, hdrHighIdx = hdrHighIdx,
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
                            p0Sub, nnDn, shortRatio = shortRatio,
                            shortSat = shortSat, film = film,
                            useStill = true, tenBit = tenBit,
                            hdrEv = hdrEv, hdrGainArr = hdrGainArr,
                            hdrHighIdx = hdrHighIdx,
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
        }
        if (!posted) onDone(null, 0, 0)
    }

    /** 在拍照线程上跑一次单帧 RAW 合成（v0.10.93：单帧路径迁出预览线程 ——
     *  亮光下单帧 RCD passMs 610~766ms 会把预览渲染线程占住，实测预览 gap
     *  877~1038ms、fps 掉到 2，比暗光 MFNR（独立线程保 20fps+）更糟）。
     *  失败先自愈重建重试一次（与 submitRawBurst 同构），仍失败由调用方回退
     *  预览线程重放（buffer 语义：img 已拷出 close，可重复提交）。
     *  onDone 在**拍照线程**回调。 */
    fun submitRawBuf(
        buf: java.nio.ByteBuffer, width: Int, height: Int, rowStridePx: Int,
        meta: RenderClient.RawFrameMeta, rotation: Int, film: DoubleArray,
        demosaic: Int, noiseStrength: Float, nnDn: Boolean,
        tenBit: Boolean = false,
        onDone: (ByteArray?, Int, Int) -> Unit,
    ) {
        val h = handler
        if (h == null || !ready_) {
            onDone(null, 0, 0)
            return
        }
        val tPost = SystemClock.elapsedRealtime()
        val posted = h.post {
            val effectiveRot = (rotation + 1) % 4
            val rotatedW = if (effectiveRot % 2 == 1) height else width
            val rotatedH = if (effectiveRot % 2 == 1) width else height
            val t0 = SystemClock.elapsedRealtime()
            var bytes: ByteArray? = null
            try {
                bytes = EngineBridge.nativeEngineSubmitRaw(
                    buf, width, height, rowStridePx,
                    meta.cfa, meta.blackPhase, meta.whiteLevel,
                    meta.neutral[0], meta.neutral[1], meta.neutral[2],
                    meta.ccm, meta.lsc, meta.lscCols, meta.lscRows,
                    rotation, demosaic, noiseStrength, meta.noiseModel,
                    meta.noiseSigma, nnDn, film, useStill = true, tenBit = tenBit,
                )
                if (bytes == null) {
                    // 自愈一次（v0.10.89 同款："空闲释放后重建，首次合成纹理分配
                    // 失败"）；重建与首次启动同构，实测总是成功
                    EngineBridge.nativeStillDestroy()
                    if (EngineBridge.nativeStillStart()) {
                        bytes = EngineBridge.nativeEngineSubmitRaw(
                            buf, width, height, rowStridePx,
                            meta.cfa, meta.blackPhase, meta.whiteLevel,
                            meta.neutral[0], meta.neutral[1], meta.neutral[2],
                            meta.ccm, meta.lsc, meta.lscCols, meta.lscRows,
                            rotation, demosaic, noiseStrength, meta.noiseModel,
                            meta.noiseSigma, nnDn, film, useStill = true, tenBit = tenBit,
                        )
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_WARN, "camera", "render.still_retry",
                            kv("ok" to (bytes != null), "kind" to "raw"),
                        )
                    }
                }
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "render.raw_error",
                    kv("error" to (t.message ?: t.toString())),
                )
            }
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "render.still_done",
                kv(
                    "kind" to "raw",
                    "ms" to (SystemClock.elapsedRealtime() - t0),
                    // 排队等待（post → 开始执行）：与 MFNR 同口径
                    "queuedMs" to (t0 - tPost),
                    "bytes" to (bytes?.size ?: -1),
                    "size" to "${rotatedW}x${rotatedH}",
                ),
            )
            // 失败节点立刻落盘：这条日志往往就是崩溃现场的最后一条
            if (bytes == null) EngineBridge.nativeFlushLogs()
            onDone(bytes, rotatedW, rotatedH)
        }
        if (!posted) onDone(null, 0, 0)
    }

    /** 在拍照线程上跑一次单帧 YUV 合成（v0.10.93，与 submitRawBuf 同构）。
     *  调用前提：YUV 矩阵快照已由 nativeStillStart 下发（v0.10.93 补齐——此前
     *  只补 LUT，BT.709 会被按 BT.601 解码）。onDone 在**拍照线程**回调。 */
    fun submitStillBuf(
        y: java.nio.ByteBuffer, u: java.nio.ByteBuffer, v: java.nio.ByteBuffer?,
        width: Int, height: Int, yStride: Int, uvStride: Int, uvPix: Int,
        rotation: Int, film: DoubleArray,
        tenBit: Boolean = false,
        onDone: (ByteArray?, Int, Int) -> Unit,
    ) {
        val h = handler
        if (h == null || !ready_) {
            onDone(null, 0, 0)
            return
        }
        val tPost = SystemClock.elapsedRealtime()
        val posted = h.post {
            val effectiveRot = (rotation + 1) % 4
            val rotatedW = if (effectiveRot % 2 == 1) height else width
            val rotatedH = if (effectiveRot % 2 == 1) width else height
            val t0 = SystemClock.elapsedRealtime()
            var bytes: ByteArray? = null
            try {
                bytes = EngineBridge.nativeEngineSubmitStill(
                    y, u, v, width, height, yStride, uvStride, uvPix,
                    rotation, film, useStill = true, tenBit = tenBit,
                )
                if (bytes == null) {
                    EngineBridge.nativeStillDestroy()
                    if (EngineBridge.nativeStillStart()) {
                        bytes = EngineBridge.nativeEngineSubmitStill(
                            y, u, v, width, height, yStride, uvStride, uvPix,
                            rotation, film, useStill = true, tenBit = tenBit,
                        )
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_WARN, "camera", "render.still_retry",
                            kv("ok" to (bytes != null), "kind" to "yuv"),
                        )
                    }
                }
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "render.still_error",
                    kv("error" to (t.message ?: t.toString())),
                )
            }
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "render.still_done",
                kv(
                    "kind" to "yuv",
                    "ms" to (SystemClock.elapsedRealtime() - t0),
                    "queuedMs" to (t0 - tPost),
                    "bytes" to (bytes?.size ?: -1),
                    "size" to "${rotatedW}x${rotatedH}",
                ),
            )
            if (bytes == null) EngineBridge.nativeFlushLogs()
            onDone(bytes, rotatedW, rotatedH)
        }
        if (!posted) onDone(null, 0, 0)
    }
}
