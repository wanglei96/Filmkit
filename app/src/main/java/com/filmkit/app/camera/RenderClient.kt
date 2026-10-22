package com.filmkit.app.camera

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 渲染线程：独占 EGL 上下文（native 侧）。
 * 帧流：Camera2 → SurfaceTexture（系统 YUV→RGB，参照 PhotonCamera 路径）→
 * updateTexImage → 胶片链 → 上屏。相机回调与本线程 looper 绑定，天然串行。
 */
class RenderClient {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var startedSurface: Surface? = null

    private val pending = AtomicBoolean(false)
    private val stMat = FloatArray(16)

    // 预览停更量化（v0.10.85）：渲染线程被长链阻塞时，相邻两次渲染的间隔会拉大
    // ——"拍完挪手机画面冻住 1~2 秒"的验收口径 = 这条日志的最大 gapMs
    //（改前 ≈1600~3400ms，独立线程后应不再出现；采集期 150~250ms 的正常停顿
    //  被 400ms 阈值挡在外面）。
    private var lastPresentMs = 0L

    // 预览帧率曲线（v0.10.87）：preview_gap 只抓 >400ms 的"停更"，抓不到"降帧"
    // ——拍照时 still 线程跑 DFT/RCD 与预览争同一颗 GPU，30fps 掉到十几 fps 时
    // 没有任何一条日志会响。这里按秒统计**实际渲染帧数**（pending 闸门丢掉的帧
    // 不算），每 5 秒输出一条 render.fps，分析时与 mfnr.stage / render.burst_done
    // 的时间轴对齐，就能把"轻微卡顿"变成可比的数字（拍照 vs 空闲）。
    private var fpsSecStart = 0L
    private var fpsInSec = 0
    private val fpsWindow = ArrayList<Int>(5)

    @Volatile
    private var acceptingFrames = false

    val looper: android.os.Looper?
        get() = thread?.looper

    /**
     * 创建 EGL 上下文并绑定窗口面；阻塞等待就绪。
     * 旧线程必须完全退出（join）后才能建新上下文——EGL 上下文 per-thread current。
     */
    fun start(surface: Surface): Boolean {
        synchronized(this) {
            if (startedSurface == surface && thread?.isAlive == true) return true
            stopInternal()
            val t = HandlerThread("FilmRender").apply { start() }
            thread = t
            val h = Handler(t.looper)
            handler = h

            val latch = CountDownLatch(1)
            var ok = false
            h.post {
                ok = EngineBridge.nativeEngineStart(surface)
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "engine", "render.thread_start",
                    "{\"ok\":$ok}",
                )
                latch.countDown()
            }
            latch.await(3, TimeUnit.SECONDS)
            if (ok) {
                startedSurface = surface
                acceptingFrames = true
            }
            return ok
        }
    }

    /** 在渲染上下文里创建 OES 外部纹理（SurfaceTexture 需要其 id）。阻塞返回。 */
    fun createOesTexture(): Int {
        val latch = CountDownLatch(1)
        var id = 0
        handler?.post {
            id = EngineBridge.nativeCreateOesTexture()
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return id
    }

    /**
     * 预览实时直方图（M3）：阻塞取一次（统计在渲染线程执行），null = 尚无可用帧。
     * UI 侧节流轮询（~150ms）；24KB 读回 + 24K 像素统计，单次开销远小于一帧渲染。
     */
    fun pollHistogram(): IntArray? {
        val h = handler ?: return null
        val latch = CountDownLatch(1)
        var res: IntArray? = null
        val posted = h.post {
            val a = IntArray(256)
            res = if (EngineBridge.nativeEngineHistogram(a)) a else null
            latch.countDown()
        }
        if (!posted) return null
        latch.await(500, TimeUnit.MILLISECONDS)
        return res
    }

    /**
     * M4：上传 3D LUT（渲染线程执行 —— 431KB 一次性纹理上传）。null = 关闭 LUT。
     * 引擎侧按 pending 处理（任意线程安全），这里统一排队以保证与帧渲染串行。
     */
    fun setLut(lut: com.filmkit.app.lut.CubeLut?) {
        val h = handler ?: return
        h.post {
            if (lut == null) {
                EngineBridge.nativeEngineSetLut(0, null, FloatArray(3), FloatArray(3))
            } else {
                EngineBridge.nativeEngineSetLut(lut.size, lut.data, lut.domainMin, lut.domainMax)
            }
        }
    }

    /**
     * 相机新帧就绪。最多 1 帧排队（pending 丢弃多余帧），保帧率不积压。
     * srcW/H 为 SurfaceTexture 默认缓冲尺寸。
     */
    fun frameAvailable(
        st: SurfaceTexture,
        srcW: Int,
        srcH: Int,
        rotation: Int,
        film: DoubleArray,
        fillTest: Boolean,
    ) {
        val h = handler
        if (h == null) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "engine", "render.dropped_no_handler", "{}",
            )
            return  // pending 未置位，无需回滚
        }
        if (!pending.compareAndSet(false, true)) return
        val posted = h.post {
            try {
                if (acceptingFrames) {  // stop 后到达的迟到帧直接丢弃（防 updateTexImage 崩溃）
                    st.updateTexImage()
                    st.getTransformMatrix(stMat)
                    EngineBridge.nativeEngineSubmitST(
                        stMat, srcW, srcH, rotation, film, fillTest,
                    )
                }
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "render.frame_error",
                    com.filmkit.app.kv("error" to (t.message ?: t.toString())),
                )
            }
            // 渲染完成时刻：与上一次的间隔 > 400ms 说明预览停更过一次。
            // 阈值取 400 而非 250：拍照采集期本身有 DNG 落盘 + 4 帧拷贝
            //（≈150~250ms，走独立线程时也照旧发生在预览线程上），别把它误报成"冻住"。
            // 上限 10s：切后台/切镜头造成的会话级空档不算（那是重建预览，不是被链阻塞）。
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastPresentMs > 0) {
                val gap = (now - lastPresentMs).toInt()
                if (gap in 401..10_000) {
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_WARN, "engine", "render.preview_gap",
                        com.filmkit.app.kv("gapMs" to gap),
                    )
                }
            }
            lastPresentMs = now
            // 帧率统计（见字段处说明）：按秒计数，攒够 5 秒发一条
            if (fpsSecStart == 0L) fpsSecStart = now
            fpsInSec++
            if (now - fpsSecStart >= 1000L) {
                fpsWindow.add(fpsInSec)
                fpsInSec = 0
                fpsSecStart = now
                if (fpsWindow.size >= 5) {
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "engine", "render.fps",
                        com.filmkit.app.kv("fps" to fpsWindow.joinToString(",")),
                    )
                    fpsWindow.clear()
                }
            }
            pending.set(false)
        }
        // post 失败（线程正在退出）：必须回滚 pending，否则永久卡 true，
        // 后续会话所有帧被丢弃 → 后台恢复黑屏（X300 实测竞态）
        if (!posted) pending.set(false)
    }

    /** 拍照读回后的统计辅助（帧计数/尺寸），供 UI 轮询。 */
    fun post(r: Runnable) {
        handler?.post(r)
    }

    /**
     * 预览降载（v0.10.93）：拍照期间把预览链中间渲染缩到 scale×，结束设回 1.0。
     * 引擎侧是进程级 atomic（下一帧生效），**任意线程直接调用** —— 与渲染队列
     * 无耦合，拍照线程的合成完成回调里也能安全调用。恢复缺失的兜底见
     * CameraController 的 previewScaleGuard（15s 强制回升）。
     */
    fun setPreviewScale(scale: Float) {
        EngineBridge.nativeEngineSetPreviewScale(scale)
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "engine", "render.preview_scale",
            kv("scale" to "%.2f".format(scale)),
        )
    }

    /**
     * 静拍 YUV（buffer 语义，v0.10.93 改造）：三平面已由调用方拷出并 close Image
     * —— buffer 可在拍照线程失败后回退重放（红线：失败不以丢照片为代价），
     * 与 MFNR 的 direct buffer 语义对齐。
     * yPlanar = false 时 v 为 null（UV 交错）。回调在渲染线程。
     */
    fun submitStillBuf(
        y: java.nio.ByteBuffer, u: java.nio.ByteBuffer, v: java.nio.ByteBuffer?,
        w: Int, h: Int, yStride: Int, uvStride: Int, uvPix: Int,
        rotation: Int, film: DoubleArray,
        tenBit: Boolean = false,
        onDone: (ByteArray?, Int, Int) -> Unit,
    ) {
        val h0 = handler
        if (h0 == null) {
            onDone(null, 0, 0)
            return
        }
        h0.post {
            // 与 C++ submitStill 一致：静拍实际旋转 = 界面方向 + 传感器旋转(+1)
            val effectiveRot = (rotation + 1) % 4
            val rotatedW = if (effectiveRot % 2 == 1) h else w
            val rotatedH = if (effectiveRot % 2 == 1) w else h
            var bytes: ByteArray? = null
            try {
                bytes = EngineBridge.nativeEngineSubmitStill(
                    y, u, v, w, h, yStride, uvStride, uvPix, rotation, film,
                    useStill = false, tenBit = tenBit,
                )
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "render.still_error",
                    com.filmkit.app.kv("error" to (t.message ?: t.toString())),
                )
            }
            if (bytes == null) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "render.still_failed", "{}",
                )
            }
            onDone(bytes, rotatedW, rotatedH)
        }.let { posted ->
            // post 失败（线程正在退出）：必须回调，否则调用方永久等待（v0.10.85 审查 R1）
            if (!posted) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_WARN, "engine", "render.still_dropped", "{}",
                )
                onDone(null, 0, 0)
            }
        }
    }

    /** RAW 静拍元数据（CaptureResult 提取，M2-3）。 */
    class RawFrameMeta(
        val cfa: Int,
        val blackPhase: FloatArray, // 相位 (0,0)(1,0)(0,1)(1,1)
        val whiteLevel: Int,
        val neutral: FloatArray,    // asShotNeutral (r,g,b)
        val ccm: FloatArray,        // sensor→sRGB 行主序 3×3
        // C 专项（2026-09-29）：噪声模型 [S_r,S_g,S_b,O_r,O_g,O_b]（var(x)=S·x+O，
        // x = [0,1] 归一化 raw 域，SENSOR_NOISE_PROFILE 按 ISO 最近组）；null = 无
        val noiseModel: FloatArray? = null,
        val noiseSigma: Float = 0f, // legacy 回退 σ（模型无效时；0=引擎回退估计）
        // 镜头阴影增益网格（M3 P0）：RGB 交错，cols×rows，(0,0)=图像左上；
        // null = 关闭或无数据（引擎用恒等增益）
        val lsc: FloatArray? = null,
        val lscCols: Int = 1,
        val lscRows: Int = 1,
        // true = ccm 来自 characteristics 自建标准链（DNG 路径）；false = HAL CCM
        val ccmStd: Boolean = false,
    )

    /** 静拍 RAW（buffer 语义，v0.10.93 改造）：u16 Bayer 已由调用方拷出并 close
     *  Image（HAL 槽位即释），buffer 可在拍照线程失败后回退重放。
     *  demosaic：0 = bilinear（速度档）1 = RCD 三段管线（质量档）。
     *  noiseStrength：降噪强度滑杆 0–2（C 专项：0 = 关、1 = 真实 σ、2 = 2×σ；仅 RCD 生效）。
     *  回调在渲染线程。 */
    fun submitRawBuf(
        buf: java.nio.ByteBuffer, width: Int, height: Int, rowStridePx: Int,
        meta: RawFrameMeta, rotation: Int, film: DoubleArray,
        demosaic: Int, noiseStrength: Float, nnDn: Boolean,
        tenBit: Boolean = false,
        onDone: (ByteArray?, Int, Int) -> Unit,
    ) {
        val h = handler
        if (h == null) {
            onDone(null, 0, 0)
            return
        }
        h.post {
            // 与 submitStill 一致：静拍实际旋转 = 界面方向 + 传感器旋转(+1)
            val effectiveRot = (rotation + 1) % 4
            val rotatedW = if (effectiveRot % 2 == 1) height else width
            val rotatedH = if (effectiveRot % 2 == 1) width else height
            var bytes: ByteArray? = null
            try {
                bytes = EngineBridge.nativeEngineSubmitRaw(
                    buf, width, height, rowStridePx,
                    meta.cfa, meta.blackPhase, meta.whiteLevel,
                    meta.neutral[0], meta.neutral[1], meta.neutral[2],
                    meta.ccm, meta.lsc, meta.lscCols, meta.lscRows,
                    rotation, demosaic, noiseStrength, meta.noiseModel,
                    meta.noiseSigma, nnDn, film, useStill = false, tenBit = tenBit,
                )
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "render.raw_error",
                    com.filmkit.app.kv("error" to (t.message ?: t.toString())),
                )
            }
            if (bytes == null) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "render.raw_failed", "{}",
                )
            }
            onDone(bytes, rotatedW, rotatedH)
        }.let { posted ->
            if (!posted) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_WARN, "engine", "render.raw_dropped", "{}",
                )
                onDone(null, 0, 0)
            }
        }
    }

    /** 多帧融合 RAW（MFNR 阶段一，v0.9.0/9.1）：4 帧已拷贝的 Bayer direct buffer
     *  （含 stride）→ 引擎对齐+均值+成片链，回调在渲染线程。buffer 由调用方分配
     *  生命周期（Image 已在采集回调中 close，HAL 槽位已释放）。 */
    fun submitRawBurst(
        buffers: List<java.nio.ByteBuffer>,
        strides: IntArray,
        width: Int,
        height: Int,
        meta: RawFrameMeta,
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
        onDone: (ByteArray?, Int, Int) -> Unit,
    ) {
        val h = handler
        if (h == null || buffers.size != 4) {
            onDone(null, 0, 0)
            return
        }
        val tPost = android.os.SystemClock.elapsedRealtime()
        val posted = h.post {
            // 与 submitRaw 一致：静拍实际旋转 = 界面方向 + 传感器旋转(+1)
            val effectiveRot = (rotation + 1) % 4
            val rotatedW = if (effectiveRot % 2 == 1) height else width
            val rotatedH = if (effectiveRot % 2 == 1) width else height
            var bytes: ByteArray? = null
            try {
                bytes = EngineBridge.nativeEngineSubmitRawBurst(
                    buffers.toTypedArray(), buffers.size, strides,
                    width, height,
                    meta.cfa, meta.blackPhase, meta.whiteLevel,
                    meta.neutral[0], meta.neutral[1], meta.neutral[2],
                    meta.ccm, meta.lsc, meta.lscCols, meta.lscRows,
                    rotation, demosaic, noiseStrength, meta.noiseModel,
                    meta.noiseSigma, refNoiseScale, useDft, alignL1, alignAb, p0Sub,
                    nnDn, shortRatio = shortRatio, shortSat = shortSat,
                    film = film, tenBit = tenBit,
                )
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "render.raw_burst_error",
                    com.filmkit.app.kv("error" to (t.message ?: t.toString())),
                )
            }
            if (bytes == null) {
                // 失败必须留痕：上一版"只留 DNG、没有成像"最难查的洞就是这里静默
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "render.raw_burst_failed",
                    com.filmkit.app.kv(
                        "queuedMs" to (android.os.SystemClock.elapsedRealtime() - tPost),
                    ),
                )
            }
            onDone(bytes, rotatedW, rotatedH)
        }
        // post 失败（相机页退出后 looper 已退出）：必须回调，否则调用方永久等待、
        // 在飞计数泄漏（v0.10.85 审查 R1 —— A/B 第二遍正是从拍照线程 post 过来的）
        if (!posted) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "engine", "render.burst_dropped", "{}",
            )
            onDone(null, 0, 0)
        }
    }

    fun stop() {
        synchronized(this) { stopInternal() }
    }

    private fun stopInternal() {
        acceptingFrames = false
        val t = thread ?: return
        val h = handler ?: return
        h.post { EngineBridge.nativeEngineStop() }
        h.post { t.quitSafely() }  // 处理完已排队消息后退出
        try {
            t.join(2000)  // 等待 engineStop 真正执行完（EGL 资源已释放）
        } catch (_: InterruptedException) {
        }
        thread = null
        handler = null
        startedSurface = null
        // 线程已死：强制复位排队闸，防止上一会话的竞态卡死新会话
        pending.set(false)
    }

    private fun kvOf(vararg pairs: Pair<String, Any?>): String =
        com.filmkit.app.kv(*pairs)}
