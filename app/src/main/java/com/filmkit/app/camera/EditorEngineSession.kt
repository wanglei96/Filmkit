package com.filmkit.app.camera

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv
import com.filmkit.app.lut.CubeLut
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 编辑器引擎会话（E1-4）：把成片的 RGBA 像素送进引擎后段（`kFsStyle`：LUT → 颗粒 →
 * TPDF dither）并读回，全程在**本会话自己的渲染线程**上执行。
 *
 * **为什么编辑器要自建渲染线程**（本卡最先必须解决的结构问题）：
 * [EngineBridge.nativeEngineSubmitDisplay] 走的是**预览实例** `g_renderer`
 * （`core/engine/src/jni/Bridge.cpp:659`），而 `g_renderer` 只存在于相机预览渲染线程上。
 * 编辑器是从相机页 `when (screen)` 切过去的 —— `CameraScreen` 的 `DisposableEffect`
 * 一 onDispose 就 `controller.stop()` → `nativeEngineStop` → `g_renderer` 释放 + 渲染线程
 * `quitSafely` 退出。也就是说进编辑器时**相机那条渲染线程已经不存在了**，没有现成的
 * `RenderClient` 可用。
 *
 * 所以这里复用相机侧同款线程管理（[RenderClient]：EGL context per-thread current、
 * 阻塞式 start/stop）+ 一个 1×1 的 [SurfaceTexture] 充当 EGL window surface 的载体。
 * 为什么不改引擎去加一个"编辑器实例"：E1-3 的产物已验收通过，本卡不动
 * `core/engine/` 一行；`submitDisplay` 只渲染到自己的 FBO 再 `glReadPixels`，**从不上屏**，
 * 所以这个窗口面多大、有没有像素都无所谓。
 *
 * **线程互斥**（关键安全约束）：同一时刻只能有一条线程持有 `g_renderer`。相机页
 * onDispose 里的 [RenderClient.stop] 是同步 join 的，本会话 [close] 同样同步 join ——
 * 两条线程并发持有引擎实例 = UAF，不允许出现。
 *
 * **参数**：调用方传进来的 `film` 是完整 FilmParams（长度 = `CameraPresets.COUNT`）的**副本**
 *（主线程可能同时在改滑杆值，不能共享同一数组）。引擎的 `passStyle` 只读
 * [26] grain_amount / [27] grain_size / [34] lut_strength / [66][67][68] 暗角三条，
 * 其余槽位不影响输出（ADR §5.1 I-2，白名单由 `tools/check-style-passthrough.py`
 * 第 ④ 项精确匹配守着）。
 */
internal class EditorEngineSession {

    /**
     * 引擎渲染的载体线程。编辑器只用到它的 start/stop/post/setLut 四个能力 ——
     * [RenderClient] 的帧闸门、直方图、静拍等相机侧逻辑一概不碰。
     */
    private val render = RenderClient()

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    /** 启动成功前不接受渲染请求（也用于 close 后拒绝：否则 scheduled 会永久卡 true） */
    @Volatile
    private var started = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private class Request(
        val rgba: ByteBuffer,
        val w: Int,
        val h: Int,
        val film: DoubleArray,
        val onDone: (Bitmap?) -> Unit,
    )

    /**
     * 预览请求槽：**只保留最后一次**（见 [requestPreview]）。
     * 用 AtomicReference 而非锁 —— 写方是 UI 线程，读方是渲染线程。
     */
    private val pending = AtomicReference<Request?>(null)

    /** 已有一条 drain 排在渲染队列上（合并闸门，防止请求堆积成队列） */
    private val scheduled = AtomicBoolean(false)

    /**
     * 起引擎（阻塞最多 3s，调用方应在 IO 线程）。返回 false = 引擎不可用，
     * 调用方必须显示失败态而不是白屏。
     */
    fun start(): Boolean {
        if (started) return true
        return try {
            // 1×1 的 detached SurfaceTexture：只用来给 eglCreateWindowSurface 一个
            // ANativeWindow。submitDisplay 不 swap，所以不会有 buffer 真正流动。
            // 用 64 而非 1：个别驱动的窗口面有最小尺寸假设，1×1 的收益为零（不 swap
            // 就没有像素分配），没必要去踩边界。
            val st = SurfaceTexture(false)
            st.setDefaultBufferSize(WIN_SIZE, WIN_SIZE)
            val s = Surface(st)
            surfaceTexture = st
            surface = s
            started = render.start(s)
            if (started) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "engine", "editor.engine_start",
                    kv("ok" to true),
                )
            } else {
                releaseSurfaces()
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "engine", "editor.engine_start",
                    kv("ok" to false),
                )
            }
            started
        } catch (t: Throwable) {
            releaseSurfaces()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "engine", "editor.engine_start",
                kv("ok" to false, "error" to (t.message ?: t.toString())),
            )
            false
        }
    }

    /**
     * 上传 3D LUT（null = 关闭）。任意线程可调 —— 内部排到渲染线程，与帧渲染串行。
     *
     * ⚠ 引擎侧是**进程级**快照（`Bridge.cpp` 的 `g_lut_*`）：这里改了，拍照侧的
     * 风格层也一起变。安全性来自相机页的行为 —— 每次进相机页都会按 prefs `lut_id`
     * 重新下发（`CameraScreen` 的 `LaunchedEffect(Unit) { lutId?.let { … lutPending = it } }`），
     * 所以编辑器残留的 LUT 不会带进下一张成片。
     */
    fun setLut(lut: CubeLut?) {
        render.setLut(lut)
    }

    /**
     * 请求一次**预览**渲染（任意线程）。
     *
     * **E2-8 起预览也是全分辨率**（用户 2026-10-08 拍板："拖完松手再渲染"）：调用频率从
     * 拖动中的 ≈60 次/秒降到一次交互 1 次，单帧耗时从预览档的 10~30ms 升到百毫秒量级
     *（12MP，含 50MB 读回）。下面的 latest-wins 合并**保留**：它现在挡的是"连续快速松手 /
     * 连点卡片"造成的堆积，成本为零，去掉反而要重新论证。
     *
     * **合并/节流语义**：新请求直接**覆盖**还没被取走的旧请求，渲染线程一次只取最新一份
     *（latest-wins）。所以任意时刻最多"1 个在跑 + 1 个待跑"，队列不会堆积；被覆盖掉的
     * 请求 [onDone] 不会被调用（调用方只关心最新结果，无需自行丢弃过期回调）。
     *
     * 注意调用方**自己**要管好输出位图的回收：全分辨率下一张输出 ≈50MB，替换时必须把
     * 上一张还回去（见 EditorScreen.rerender）。
     *
     * [onDone] 在**主线程**回调（null = 渲染失败/被关闭丢弃）。
     */
    fun requestPreview(
        rgba: ByteBuffer, w: Int, h: Int, film: DoubleArray, onDone: (Bitmap?) -> Unit,
    ) {
        pending.set(Request(rgba, w, h, film, onDone))
        schedule()
    }

    /**
     * 请求一次**导出**渲染（不合并，必须完成）。与预览共用 `g_renderer`，所以调用方
     * 必须在导出期间停止发预览请求（E2-8 起两者同为全分辨率，纹理尺寸一致、不会来回
     * 重建，但导出必须独占引擎 —— 让预览插进去排队只会把导出拖长）。
     *
     * [onDone] 在**主线程**回调（null = 渲染失败）。
     */
    fun requestFull(
        rgba: ByteBuffer, w: Int, h: Int, film: DoubleArray, onDone: (Bitmap?) -> Unit,
    ) {
        if (!started) {
            postMain { onDone(null) }
            return
        }
        render.post {
            val out = submit(rgba, w, h, film)
            postMain { onDone(out) }
        }
    }

    /**
     * 关闭（阻塞，同步等 `nativeEngineStop` 跑完）。**必须在离开编辑器时调用**：
     * 不释放的话，回到相机页新起的渲染线程会同它并发持有 `g_renderer`（UAF），
     * 而且全分辨率纹理（两张 12MP 纹理 ≈ 88MB）会常驻。
     */
    fun close() {
        started = false
        // 排队中的预览请求不会再被渲染：回调 null，避免调用方永久等在 loading 态
        pending.getAndSet(null)?.let { r -> postMain { r.onDone(null) } }
        render.stop()
        releaseSurfaces()
    }

    private fun releaseSurfaces() {
        runCatching { surface?.release() }
        runCatching { surfaceTexture?.release() }
        surface = null
        surfaceTexture = null
    }

    private fun schedule() {
        if (!started) {
            // 未启动/已关闭：立刻以失败回调收尾（[requestPreview] 的调用方据此显示失败态）
            pending.getAndSet(null)?.let { r -> postMain { r.onDone(null) } }
            return
        }
        if (scheduled.compareAndSet(false, true)) render.post { drain() }
    }

    /** 渲染线程：把待跑的预览请求全部跑到只剩最新一份（合并见 [requestPreview]） */
    private fun drain() {
        while (true) {
            val r = pending.getAndSet(null) ?: break
            val out = submit(r.rgba, r.w, r.h, r.film)
            postMain { r.onDone(out) }
        }
        scheduled.set(false)
        // 清标志与检查之间可能有新请求进来（那一刻 scheduled 还是 true，schedule 不会
        // 再排 drain）—— 补一次排程，否则该请求永远等不到渲染。
        if (pending.get() != null) schedule()
    }

    /** 渲染线程：一次提交 + 读回转 Bitmap（失败返回 null，引擎侧已记 render.style） */
    private fun submit(rgba: ByteBuffer, w: Int, h: Int, film: DoubleArray): Bitmap? {
        val bytes = try {
            EngineBridge.nativeEngineSubmitDisplay(rgba, w, h, film)
        } catch (t: Throwable) {
            // UnsatisfiedLinkError / 引擎内部异常都算失败：宁可显示原图，不许崩
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "engine", "editor.submit_failed",
                kv("error" to (t.message ?: t.toString()), "w" to w, "h" to h),
            )
            null
        } ?: return null
        if (bytes.size < w * h * 4) return null
        return try {
            // 输出是显示域 RGBA8（顶行在前，与输入同序）—— 直接按 ARGB_8888 的内存
            // 布局（R,G,B,A）灌进去，不做任何翻转/转换（ADR-7：不插 OETF/EOTF）
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            }
        } catch (t: Throwable) {
            // 全分辨率（预览与导出）时这里可能 OOM：失败 = 本次渲染失败 —— 预览退回显示
            // 原图、导出不动原片（调用方各自处理）
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "engine", "editor.bitmap_failed",
                kv("error" to (t.message ?: t.toString()), "w" to w, "h" to h),
            )
            null
        }
    }

    private fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        /** EGL window surface 的载体尺寸（不 swap ⇒ 不产生像素分配，尺寸无关紧要） */
        const val WIN_SIZE = 64
    }
}
