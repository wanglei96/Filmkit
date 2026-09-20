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

    /** 静拍：全分辨率 YUV → 胶片链 → 读回，回调在渲染线程。 */
    fun submitStill(img: android.media.Image, rotation: Int, film: DoubleArray, onDone: (ByteArray?, Int, Int) -> Unit) {
        val h = handler
        if (h == null) {
            img.close()
            onDone(null, 0, 0)
            return
        }
        h.post {
            // 与 C++ submitStill 一致：静拍实际旋转 = 界面方向 + 传感器旋转(+1)
            val effectiveRot = (rotation + 1) % 4
            val rotatedW = if (effectiveRot % 2 == 1) img.height else img.width
            val rotatedH = if (effectiveRot % 2 == 1) img.width else img.height
            var bytes: ByteArray? = null
            try {
                val pY = img.planes[0]
                val pU = img.planes[1]
                val pV = img.planes[2]
                val semi = pU.pixelStride >= 2
                bytes = EngineBridge.nativeEngineSubmitStill(
                    pY.buffer, pU.buffer, if (semi) null else pV.buffer,
                    img.width, img.height,
                    pY.rowStride, pU.rowStride, pU.pixelStride,
                    rotation, film,
                )
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "render.still_error",
                    com.filmkit.app.kv("error" to (t.message ?: t.toString())),
                )
            }
            img.close()
            onDone(bytes, rotatedW, rotatedH)
        }
    }

    /** RAW 静拍元数据（CaptureResult 提取，M2-3）。 */
    class RawFrameMeta(
        val cfa: Int,
        val blackPhase: FloatArray, // 相位 (0,0)(1,0)(0,1)(1,1)
        val whiteLevel: Int,
        val neutral: FloatArray,    // asShotNeutral (r,g,b)
        val ccm: FloatArray,        // sensor→sRGB 行主序 3×3
    )

    /** 静拍 RAW：u16 Bayer → 引擎 demosaic+胶片链 → 读回，回调在渲染线程。
     *  img 在本函数消费后关闭；DNG 原片须在本调用前写出（依赖 image 与 result）。 */
    fun submitRaw(img: android.media.Image, meta: RawFrameMeta, rotation: Int, film: DoubleArray, onDone: (ByteArray?, Int, Int) -> Unit) {
        val h = handler
        if (h == null) {
            img.close()
            onDone(null, 0, 0)
            return
        }
        h.post {
            // 与 submitStill 一致：静拍实际旋转 = 界面方向 + 传感器旋转(+1)
            val effectiveRot = (rotation + 1) % 4
            val rotatedW = if (effectiveRot % 2 == 1) img.height else img.width
            val rotatedH = if (effectiveRot % 2 == 1) img.width else img.height
            var bytes: ByteArray? = null
            try {
                val p = img.planes[0]
                bytes = EngineBridge.nativeEngineSubmitRaw(
                    p.buffer, img.width, img.height, p.rowStride / 2,
                    meta.cfa, meta.blackPhase, meta.whiteLevel,
                    meta.neutral[0], meta.neutral[1], meta.neutral[2],
                    meta.ccm, rotation, film,
                )
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "render.raw_error",
                    com.filmkit.app.kv("error" to (t.message ?: t.toString())),
                )
            }
            img.close()
            onDone(bytes, rotatedW, rotatedH)
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
