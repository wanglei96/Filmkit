package com.filmkit.app

import android.view.Surface
import java.nio.ByteBuffer

/**
 * JNI 桥：
 *  - 日志/能力/spike（M0）：nativeDetectCapabilities / nativeRunSpike 需在持有 GL 上下文的线程；
 *  - engine*（M1）：必须全部在 RenderClient 渲染线程调用（EGL 上下文 per-thread current）。
 */
object EngineBridge {

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("filmengine")
                    loaded = true
                }
            }
        }
    }

    // 与 C++ LogLevel 枚举保持一致
    const val LEVEL_DEBUG = 0
    const val LEVEL_INFO = 1
    const val LEVEL_WARN = 2
    const val LEVEL_ERROR = 3

    // ── M0 ──
    external fun nativeInitLogger(dir: String): Boolean
    external fun nativeAppendLog(level: Int, module: String, event: String, kvJson: String)
    external fun nativeRecentLogs(maxLines: Int): String?
    external fun nativeFlushLogs(): Boolean
    external fun nativeDetectCapabilities(): String?
    external fun nativeRunSpike(width: Int, height: Int, iterations: Int, useCompute: Boolean): String?

    /** 频域降噪专项（2026-09-29）：HDR+ §5 式 6/7 DFT 合并内核的 GPU 成本实测。
     *  variant：0=基线 1=+twiddle 查表 2=+twiddle 查表+寄存器 FFT。 */
    external fun nativeRunFftSpike(width: Int, height: Int, iterations: Int, overlap: Boolean, variant: Int): String?

    // ── M1 相机引擎（SurfaceTexture + OES 路径）──
    external fun nativeEngineStart(surface: Surface): Boolean
    external fun nativeEngineStop()

    /**
     * 拍照侧引擎初始化（v0.10.85）：必须在**拍照渲染线程**上调用 —— EGL context
     * 是 per-thread current 的。建独立 context + 1×1 pbuffer + 独立 FrameRenderer
     * 实例，让 1.6~3.4s 的合成链不再占用预览渲染线程（"拍完挪手机画面冻住"的
     * 根因）。幂等；**没有对应的 stop** —— 线程与实例进程级常驻，从根上消除
     * "新线程复用旧实例 / stop 杀掉正在跑 GL 的线程"这类竞态（v0.10.83 教训）。
     * 返回 false 时调用方回退预览线程。
     */
    external fun nativeStillStart(): Boolean

    /**
     * 空闲释放（v0.10.88）：销毁拍照侧 FrameRenderer（**保留 EGL context**），
     * 释放合成链留下的约 2GB 全分辨率中间纹理。必须在拍照线程上调用；因为所有
     * GL 调用都排在同一条 Handler 队列上串行，它天然排在在飞合成之后，安全。
     * 下次拍照 nativeStillStart 会重建（~60ms，LUT 由 start 里的快照补齐）。
     */
    external fun nativeStillDestroy()

    external fun nativeCreateOesTexture(): Int
    external fun nativeEngineGetStats(): String?

    /** 渲染一帧：OES 纹理（Kotlin 侧已 updateTexImage）→ 胶片链 → 上屏。 */
    external fun nativeEngineSubmitST(
        st: FloatArray, srcW: Int, srcH: Int, rotation: Int,
        film: DoubleArray, fillTest: Boolean,
    ): Boolean

    /**
     * 静拍：全分辨率 YUV_420_888 → CPU 转 RGB → 胶片链 → 读回 RGBA（旋转后尺寸）。
     * useStill = true（v0.10.93）走拍照侧实例（独立线程 + 独立 EGL context）：
     * 单帧 YUV 合成不再占住预览渲染线程；调用前必须 nativeStillStart 成功。
     */
    external fun nativeEngineSubmitStill(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer?,
        width: Int, height: Int,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        rotation: Int, film: DoubleArray,
        useStill: Boolean = false,
    ): ByteArray?

    /**
     * 静拍 RAW（M2-3）：u16 Bayer → 黑阶扣除 → 白阶归一 → WB（asShotNeutral 倒数）
     * → demosaic → CCM → 胶片链 → 读回 RGBA（旋转后尺寸）。
     * blackPhase 按相位 (0,0)(1,0)(0,1)(1,1)；ccm 为 sensor→sRGB 行主序 3×3（null=单位阵）。
     * demosaic：0 = bilinear 单 pass（速度档）；1 = RCD 三段管线（质量档，M3-1）。
     * noiseStrength：降噪强度 0–2（C 专项：0 = 关、1 = 真实 σ、2 = 2×σ；仅 RCD 生效）。
     * noiseModel：6 float [S_r,S_g,S_b,O_r,O_g,O_b]（C 专项：Camera2 SENSOR_NOISE_PROFILE，
     *   var(x)=S·x+O，x = [0,1] 归一化 raw 域；null = 无效 → noiseSigma legacy 回退）。
     * noiseSigma：归一化域噪声 σ（legacy 回退；≤0 时引擎内估计）。
     * lsc：镜头阴影增益网格（RGB 交错，cols×rows，(0,0)=图像左上），null = 关闭/无数据（恒等）。
     * nnDn = true（v0.10.102，prefs nn_dn）启用 NN 降噪 pass（PMRID，融合后/RAW
     *   域、去马赛克前）：成功时 P0 空域降噪被替代（直读展开），推理失败自动
     *   回退 P0 原链（照片优先）。
     * useStill = true（v0.10.93）走拍照侧实例（独立线程 + 独立 EGL context）：
     *   单帧 RAW 合成（实测 passMs 610~766ms）不再占住预览渲染线程；调用前必须
     *   nativeStillStart 成功，且必须在拍照线程上调用（与 nativeEngineSubmitRawBurst 同构）。
     */
    external fun nativeEngineSubmitRaw(
        raw: ByteBuffer, width: Int, height: Int, rowStridePx: Int,
        cfa: Int, blackPhase: FloatArray, whiteLevel: Int,
        neutralR: Float, neutralG: Float, neutralB: Float,
        ccm: FloatArray?, lsc: FloatArray?, lscCols: Int, lscRows: Int,
        rotation: Int, demosaic: Int,
        noiseStrength: Float, noiseModel: FloatArray?, noiseSigma: Float,
        nnDn: Boolean, film: DoubleArray,
        useStill: Boolean = false,
    ): ByteArray?

    /**
     * 多帧融合（MFNR 阶段一，v0.9.0）：count 帧 u16 Bayer → Bayer 域 tile 对齐 +
     * 置信度加权时域均值（引擎内）→ 与 nativeEngineSubmitRaw 相同的成片链。
     * frames[0] = 参考帧（DNG 底片同一帧），count 必须 = 4；其余元数据语义同
     * nativeEngineSubmitRaw，统一取帧 0（burst 恒曝光，逐帧黑阶/WB 视为一致）。
     * useDft = true 走降噪专项 A 的 DFT 逐频收缩合并（HDR+ 式 6/7），false 走
     * mismatch 加权融合（并联 A/B 口径，prefs mfnr_dft）。
     * alignL1 = true 时最细对齐层用 1/2 域灰度（D-2 专项，prefs align_l1；需
     * 多读回 1/2 域 ≈3MB/帧），false 退回 1/4 域旧路径。
     * alignAb = true 时**同一帧数据**再跑一遍旧层对齐 + P0，两组降噪倍数都写进
     * 同一条 raw.render（abNzPostMed/abP0w）—— 唯一无混杂的 A/B（prefs align_ab）。
     * 诊断那遍固定 = v0.10.69 基线（1/4 域最细层 + 整数采样），所以一次只改一个
     * 设置（align_l1 或 p0_sub）就是单变量对照。
     * p0Sub = true 时 P0 的时域采样走逐通道网格亚像素双线性（prefs p0_sub）。
     * nnDn = true（v0.10.102，prefs nn_dn）启用 NN 降噪 pass（PMRID）：融合后
     *   RAW 域、去马赛克前；成功时 P0 空域降噪被替代，推理失败自动回退 P0 原链。
     * useStill = true（v0.10.85，prefs still_thread）时走**拍照侧实例**（独立线程
     *  + 独立 EGL context），预览线程不再被这条链阻塞；调用前必须
     *  nativeStillStart 成功，且必须在拍照线程上调用。
     */
    external fun nativeEngineSubmitRawBurst(
        frames: Array<ByteBuffer>, count: Int, rowStridesPx: IntArray,
        width: Int, height: Int,
        cfa: Int, blackPhase: FloatArray, whiteLevel: Int,
        neutralR: Float, neutralG: Float, neutralB: Float,
        ccm: FloatArray?, lsc: FloatArray?, lscCols: Int, lscRows: Int,
        rotation: Int, demosaic: Int,
        noiseStrength: Float, noiseModel: FloatArray?, noiseSigma: Float,
        refNoiseScale: Float, useDft: Boolean, alignL1: Boolean,
        alignAb: Boolean, p0Sub: Boolean, nnDn: Boolean, film: DoubleArray,
        useStill: Boolean = false,
    ): ByteArray?

    /** 预览实时直方图（M3）：填充显示域亮度分布（建议 256 桶）；false = 暂无可用帧 */
    external fun nativeEngineHistogram(out: IntArray): Boolean

    /** 静拍 YUV 解码矩阵：0 = BT.601（默认），1 = BT.709（须在引擎启动后设置） */
    external fun nativeEngineSetYuvMatrix(m: Int)

    /**
     * 预览降载（v0.10.93）：拍照期间把预览链的中间渲染缩到 scale×（每维 0.25–1.0），
     * 输出 pass 在低分辨率 RGBA8 上完成后由 glBlitFramebuffer 线性升采到全窗口。
     * 帧级生效（下一帧预览即按新比例渲染），成片链不经预览路径、画质不受影响。
     * 任意线程可调（引擎侧进程级 atomic）；拍照结束必须设回 1.0。
     */
    external fun nativeEngineSetPreviewScale(scale: Float)

    /**
     * 3D LUT（M4）：display 域 LUT，引擎把它接在输出 OETF 之后（kFsOut/kFsHist，四面体插值）。
     * data = RGB 交错、顺序与 .cube 一致（红最快），长度 size³×3；size ≤ 1 或 data = null = 关闭。
     * 可在任意线程调用（引擎内暂存，渲染线程上传纹理）；强度走 FilmParams[34]。
     */
    external fun nativeEngineSetLut(size: Int, data: FloatArray?, domainMin: FloatArray, domainMax: FloatArray)
}
