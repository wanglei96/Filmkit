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
 *（主线程可能同时在改滑杆值，不能共享同一数组）。引擎的 `passStyle` 读**下面这 45 个槽位**，
 * 其余槽位不影响输出（ADR §5.1 I-2；白名单由 `tools/check-style-passthrough.py` 第 ④ 项
 * 精确匹配守着，本清单与它逐项对齐，由 `EditorStyleWhitelistTest` 钉住三方一致）：
 *
 * ```
 * [0] exposure_ev        [1] wb_r  [2] wb_g  [3] wb_b      [4] contrast
 * [5] shoulder  [6] shoulder  [7] shoulder    [8] toe  [9] toe  [10] toe
 * [20] sat_shadow  [21] sat_mid  [22] sat_high
 * [23] split_warm  [24] split_cool  [25] monochrome
 * [26] grain_amount      [27] grain_size                   [34] lut_strength
 * [37] filmic_black_ev   [38] filmic_white_ev
 * [60] grain_midtone_bias  [61] grain_pivot
 * [62] halation_strength
 * [66] vignette_strength [67] vignette_radius  [68] vignette_feather
 * [78] clarity
 * [79] local_type  [80] local_center_x  [81] local_center_y  [82] local_radius
 * [83] local_feather  [84] local_angle  [85] local_width  [86] local_ev  [87] local_sat
 * [88] temp_mired  [89] tint  [90] highlights  [91] shadows  [92] whites  [93] blacks
 * [94] fade
 * ```
 *
 * ⚠️ 本清单曾长期停在 E2-11 之前的旧版（"只读 [26]/[27]/[34]/[66..68]"）—— E2-21 的
 * "打开编辑器把成片已烘焙的参数**二次应用**"bug 的认知根源就是它：照旧注释判断，会以为
 * [0]/[1..3]/[4]/[78]/[88..94] 这些槽位"无所谓"，于是从快照恢复它们看着无害。**改这段前先跑
 * 脚本第 ④ 项，不要手抄**（`[95]` reserved 确实零引用，所以不在清单里）。
 * U-1（2026-10-11）从 31 → 34：[23][24] 分离色调 + [25] 单色接线（此前 passStyle 零引用
 * ⇒ 含 [25] 的"黑白预设"在编辑器里不变黑白）。
 * ★ U-2（2026-10-11）从 34 → 44（参数统一方案的**最后一批**，§9.2 的 9 项缺口至此清零）：
 *  · `[5..10]` 趾肩 latitude + `[37][38]` filmic 黑白点 → **显示域胶片曲线**
 *    （`computeEditorCurve` 算系数、kFsStyle 端点重映射 + 差值多项式；设计依据见该函数注释）；
 *  · `[20][22]` 分区饱和补上（此前只读 [21]，现在是三档加权、与 kFsFilm 同口径）。
 *  ⚠️ `[5][6][7]` 与 `[8][9][10]` 用的是**数组字段** `shoulder[3]`/`toe[3]`（同一个字段
 *  覆盖三个槽位）—— 这是本清单里唯一允许"字段名重复"的地方，`EditorStyleWhitelistTest`
 *  按"FilmParams.h 里是否为数组字段"校验。
 * ★ E4-5（2026-10-11）从 44 → 45：`[62]` 光晕强度。passStyle 里只读 `[62]`（门控 = 是否
 *  跑金字塔链）；`[63]` 阈值 / `[64]` 半径 / `[65]` 暖度在它调用的 `runHalation` 里读 ——
 *  那是**两条链路共享**的函数（`passOutput` 也调它），四参数的生效性由
 *  `tools/check-halation.py` 覆盖。光晕链落在 kFsStyle 的 **LUT 之前**（与拍照链路同序）。
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
        val crop: CropSpec,
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
        rgba: ByteBuffer, w: Int, h: Int, film: DoubleArray,
        crop: CropSpec = CropSpec.FULL, onDone: (Bitmap?) -> Unit,
    ) {
        pending.set(Request(rgba, w, h, film, crop, onDone))
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
        rgba: ByteBuffer, w: Int, h: Int, film: DoubleArray,
        crop: CropSpec = CropSpec.FULL, onDone: (Bitmap?) -> Unit,
    ) {
        if (!started) {
            postMain { onDone(null) }
            return
        }
        render.post {
            val out = submit(rgba, w, h, film, crop)
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
            val out = submit(r.rgba, r.w, r.h, r.film, r.crop)
            postMain { r.onDone(out) }
        }
        scheduled.set(false)
        // 清标志与检查之间可能有新请求进来（那一刻 scheduled 还是 true，schedule 不会
        // 再排 drain）—— 补一次排程，否则该请求永远等不到渲染。
        if (pending.get() != null) schedule()
    }

    /** 渲染线程：一次提交 + 读回转 Bitmap（失败返回 null，引擎侧已记 render.style） */
    private fun submit(
        rgba: ByteBuffer, w: Int, h: Int, film: DoubleArray, crop: CropSpec,
    ): Bitmap? {
        // E2-12：裁剪只改**输出**尺寸（输入纹理仍是整幅位图）；不裁剪时 == (w, h)
        val (outW, outH) = crop.outputSize(w, h)
        val bytes = try {
            // ★ E2-12c：下发的 rot 必须与 outputSize 用**同一个**归一化值 —— 否则
            // outputSize 交换了宽高、引擎却按 rot=0 分支渲染 ⇒ 尺寸与内容对不上。
            EngineBridge.nativeEngineSubmitDisplay(
                rgba, w, h, outW, outH, crop.uvOrNull(), normalizeRot(crop.rot),
                film,
            )
        } catch (t: Throwable) {
            // UnsatisfiedLinkError / 引擎内部异常都算失败：宁可显示原图，不许崩
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "engine", "editor.submit_failed",
                kv("error" to (t.message ?: t.toString()), "w" to w, "h" to h),
            )
            null
        } ?: return null
        if (bytes.size < outW * outH * 4) return null
        return try {
            // 输出是显示域 RGBA8（顶行在前，与输入同序）—— 直接按 ARGB_8888 的内存
            // 布局（R,G,B,A）灌进去，不做任何翻转/转换（ADR-7：不插 OETF/EOTF）
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            }
        } catch (t: Throwable) {
            // 全分辨率（预览与导出）时这里可能 OOM：失败 = 本次渲染失败 —— 预览退回显示
            // 原图、导出不动原片（调用方各自处理）
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "engine", "editor.bitmap_failed",
                kv("error" to (t.message ?: t.toString()), "w" to outW, "h" to outH),
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

/**
 * E2-12 裁剪参数（ADR-16）：**几何变换**，与色彩参数无关 ⇒ **不进 `FilmParams`**
 * 契约（避免为几何量再扩一次契约），走 [EditorEngineSession] 的独立参数通道。
 *
 * [x0]/[y0]/[cw]/[ch] = **原图**归一化 UV 子矩形（原点左上、y 与 Bitmap 行序同向
 * = 与 UI 裁剪框同向）；[rot] = 顺时针 90° 步进（0..3），作用于**裁剪后**的画面。
 *
 * ⚠ 与直通（E2-9 `paramsUnchanged`）的交互：直通比的是 **FilmParams 数组** ⇒ 裁剪
 * 不在比较范围内。**调用方必须显式把"非全幅裁剪"当作"有修改"**（`!isFull`），否则
 * 裁了图却走直通 = 导出原片字节（裁剪被静默丢弃）。见 EditorScreen 的 `dirty` 推导。
 */
internal data class CropSpec(
    val x0: Float = 0f,
    val y0: Float = 0f,
    val cw: Float = 1f,
    val ch: Float = 1f,
    val rot: Int = 0,
) {
    /** 全幅（不裁剪 + 不旋转）⇒ 引擎走恒等 UV 变换（输出与输入 max|Δ| ≤ 1 LSB） */
    val isFull: Boolean
        get() = rot == 0 && x0 == 0f && y0 == 0f && cw == 1f && ch == 1f

    /**
     * 输出尺寸（ADR-16）：按裁剪矩形的**像素**尺寸算，旋转奇数步则交换宽高。
     * 4080×3072 裁中间 50% ⇒ 2040×1536；再转 90° ⇒ 1536×2040。
     *
     * ★ E2-12c：判奇偶前必须 [normalizeRot]（Kotlin `%` 保号 ⇒ `-1 % 2 == -1` 会被判成
     * 偶数）。以前这里漏了，而 [cropSpecOf] 归一化了 ⇒ 两处判据不一致。
     */
    fun outputSize(srcW: Int, srcH: Int): Pair<Int, Int> {
        val pw = Math.round(cw * srcW).coerceIn(1, maxOf(srcW, 1))
        val ph = Math.round(ch * srcH).coerceIn(1, maxOf(srcH, 1))
        return if (normalizeRot(rot) % 2 == 1) ph to pw else pw to ph
    }

    /** JNI 入参：全幅传 null（引擎侧 nullptr = origin(0,0) / size(1,1) / rot 0） */
    fun uvOrNull(): FloatArray? =
        if (isFull) null else floatArrayOf(x0, y0, cw, ch)

    companion object {
        /** 不裁剪（默认）：零回归路径 */
        val FULL = CropSpec()
    }
}
