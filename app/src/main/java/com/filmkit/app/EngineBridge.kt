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

    // ── M1 相机引擎（SurfaceTexture + OES 路径）──
    external fun nativeEngineStart(surface: Surface): Boolean
    external fun nativeEngineStop()
    external fun nativeCreateOesTexture(): Int
    external fun nativeEngineGetStats(): String?

    /** 渲染一帧：OES 纹理（Kotlin 侧已 updateTexImage）→ 胶片链 → 上屏。 */
    external fun nativeEngineSubmitST(
        st: FloatArray, srcW: Int, srcH: Int, rotation: Int,
        film: DoubleArray, fillTest: Boolean,
    ): Boolean

    /** 静拍：全分辨率 YUV_420_888 → CPU 转 RGB → 胶片链 → 读回 RGBA（旋转后尺寸）。 */
    external fun nativeEngineSubmitStill(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer?,
        width: Int, height: Int,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        rotation: Int, film: DoubleArray,
    ): ByteArray?

    /**
     * 静拍 RAW（M2-3）：u16 Bayer → 黑阶扣除 → 白阶归一 → WB（asShotNeutral 倒数）
     * → demosaic → CCM → 胶片链 → 读回 RGBA（旋转后尺寸）。
     * blackPhase 按相位 (0,0)(1,0)(0,1)(1,1)；ccm 为 sensor→sRGB 行主序 3×3（null=单位阵）。
     * demosaic：0 = bilinear 单 pass（速度档）；1 = RCD 三段管线（质量档，M3-1）。
     * noiseLevel：0 = 关；1 = 标准（M3-2：RAW 域 bilateral + 色度平滑，仅 RCD 生效）。
     * noiseSigma：归一化域噪声 σ（SENSOR_NOISE_PROFILE，≤0 时引擎内回退估计）。
     */
    external fun nativeEngineSubmitRaw(
        raw: ByteBuffer, width: Int, height: Int, rowStridePx: Int,
        cfa: Int, blackPhase: FloatArray, whiteLevel: Int,
        neutralR: Float, neutralG: Float, neutralB: Float,
        ccm: FloatArray?, rotation: Int, demosaic: Int,
        noiseLevel: Int, noiseSigma: Float, film: DoubleArray,
    ): ByteArray?

    /** 静拍 YUV 解码矩阵：0 = BT.601（默认），1 = BT.709（须在引擎启动后设置） */
    external fun nativeEngineSetYuvMatrix(m: Int)
}
