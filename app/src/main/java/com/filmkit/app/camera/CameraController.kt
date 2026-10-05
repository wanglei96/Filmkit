package com.filmkit.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.BlackLevelPattern
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv
import com.filmkit.app.ui.CameraPresets
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Camera2 控制器（M1，SurfaceTexture 预览 + ImageReader 全分辨率静拍）：
 * 预览 SurfaceTexture(4:3 ≤1440 级) → 渲染线程胶片链 → 上屏；
 * 拍照走 YUV_420_888 最大分辨率（X300 主摄 4096×3072）→ CPU 转 RGB →
 * 同一胶片链全分辨率渲染 → JPEG（MediaStore Pictures/FilmKit）。
 */
class CameraController(private val context: Context, private val render: RenderClient) {

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var stillReader: ImageReader? = null

    /** RAW 静拍模式（M2-3）：DNG 开关开启且当前镜头支持 RAW_SENSOR 时生效，
     *  openCamera 时决定；false 走 YUV_420_888 原路径 */
    @Volatile
    private var rawMode = false

    /** 当前镜头 characteristics（DngCreator 必需；openCamera 时暂存） */
    private var curCharacteristics: CameraCharacteristics? = null

    /** RAW 静拍汇合：Image（reader listener）与 TotalCaptureResult（capture 回调）
     *  都就绪才处理；两者回调同绑渲染线程 looper，天然串行无锁 */
    private var pendingRawImage: Image? = null
    private var pendingResult: TotalCaptureResult? = null
    /** Image 到达时刻（elapsedRealtime），用于量化"等 result"的真实耗时（v0.6.34） */
    private var rawImageAtMs = 0L

    /** 当前激活 reader 的格式标记（ImageReader.getFormat 需 API29，用引用比对替代） */
    private var rawReader: ImageReader? = null

    var sensorOrientation = 90
        private set
    var previewSize = Size(1440, 1080)
        private set
    var stillSize = Size(4096, 3072)
        private set

    @Volatile
    private var filmParams: DoubleArray = CameraPresets.standard()

    @Volatile
    private var fillTest = false

    /** 拍摄方向步进（0..3）＝ 设备**物理**顺时针旋转角 / 90：
     *  0=竖、1=顺时针 90°（左侧朝上）、2=倒竖、3=逆时针 90°（右侧朝上）。
     *  **语义必须与 PhotonCamera 的 `captureRotationDegrees/90` 一致** —— 引擎与 focusAt 都按
     *  `(sensorOrientation/90 + step) % 4` 使用它，等价于 PC 的 `(sensorOrientation + deviceRotation) % 360`。
     *  ⚠️ 历史坑：v0.6.37 之前这里喂的是 `Display.getRotation()`，而它的定义是"**屏幕内容**要转的角度"
     *  = **与设备物理旋转相反**。竖拍(0)与倒竖(2)下两者恰好相同，所以一直没暴露；横拍下差 180°
     *  → 成片倒置（v0.6.40 真机复现）。**改这个值时务必对照 PC 公式验算四个姿态。**
     *  Compose state：**画幅比例（取景框与成片裁切）依赖它** —— 不能用 Activity 的
     *  `LocalConfiguration.orientation`，因为系统关闭"自动旋转"时 Activity 会一直自认竖屏，
     *  横拿拍就被裁成竖幅（v0.6.42 真机日志 `photo.crop srcA:1.328 dstA:0.750`）。 */
    var orientationStep by mutableStateOf(0)
        private set

    /** 平放检测（抄 PhotonCamera `OrientationObserver`：z 轴重力分量 > 7.5 视为平放）。
     *  平放（俯拍/放桌面）时 OrientationEventListener 的角度会随轻微晃动在区间边界跳变，
     *  必须在平放期间**冻结**方向、保持上一次的有效值。 */
    @Volatile
    private var isNearFlat = false
    private val gravityListener by lazy {
        object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                val v = event?.values ?: return
                isNearFlat = kotlin.math.abs(v[2]) > FLAT_AXIS_THRESHOLD
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }
    }

    // ── 曝光决策专项（v0.10.107）：motion metering ─────────────────────
    // Night Sight §2.3：按运动量决定每帧曝光时长（手持 ≤333ms / 稳定 ≤1s）。
    // 离线实测（test_temp/exposure_lever/findings.md）：曝光 ×N 的噪声收益 = √N，
    // 代价是帧内模糊 d 对纹理的衰减（tex_keep 只与 d 有关）；细节优先口径
    // （tex_keep ≥ 0.95）⇒ 帧内模糊预算 2.2 mosaic px，换算即
    //   t_max = budget · RAD_PER_PX / ω     （ω = 采集前 1s 角速度 RMS）
    // 取三轴模长（含 roll，偏保守）；样本不足返回 -1，规划器视为无运动限制。
    private class GyroSample(val tNs: Long, val x: Float, val y: Float, val z: Float)

    private val gyroLock = Any()
    private val gyroSamples = ArrayDeque<GyroSample>()

    private val gyroListener by lazy {
        object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                val v = event?.values ?: return
                synchronized(gyroLock) {
                    gyroSamples.addLast(GyroSample(event.timestamp, v[0], v[1], v[2]))
                    val cut = event.timestamp - GYRO_WINDOW_NS
                    while (gyroSamples.isNotEmpty() && gyroSamples.first().tNs < cut) {
                        gyroSamples.removeFirst()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }
    }

    /** 最近 GYRO_WINDOW_NS 的**矢量平均**角速度模长（rad/s）—— motion metering 判据。
     *
     * Night Sight §2.3 用"时间平滑（平均）后的角速度"预测曝光窗口内的转动量：手持的
     * 高频抖动在窗口内正负抵消，只有**低频漂移**会在曝光窗口内累积成帧内模糊。
     *
     * v0.10.107 实测教训：首版用 RMS（保留高频）⇒ 真机 `gyro:0.030 rad/s` 把运动预算
     * 压到 22ms（`mtnLim:"0.022"`），比预览 AE 的 40ms 还短 ⇒ 长曝光**永久不生效**
     * （expN 恒 1.00 / expWhy 恒 none），功能等于不存在。 */
    private fun gyroMeanRadPerSec(): Double {
        synchronized(gyroLock) {
            if (gyroSamples.size < 4) return -1.0
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            for (s in gyroSamples) {
                sx += s.x
                sy += s.y
                sz += s.z
            }
            val n = gyroSamples.size.toDouble()
            sx /= n
            sy /= n
            sz /= n
            return kotlin.math.sqrt(sx * sx + sy * sy + sz * sz)
        }
    }

    /**
     * 高光保护补偿（v0.10.110）：本次 burst 的欠曝量（EV，≥0）。
     *
     * 曝光欠多少，**提亮就补多少** —— 这是 HDR+ 的完整做法（故意欠曝 → 后处理提亮）：
     * 中间调与预览保持一致（WYSIWYG），而高光因为少收了光子不再裁切。若只欠曝不补偿，
     * 成片会整体变暗，那就只是把"高光爆"换成了"画面暗"，不是解。
     */
    @Volatile
    private var burstHlUnderEv = 0.0

    /** 成片用 filmParams：把高光保护的欠曝量加到 EV（[0]）。预览用原始数组（预览曝光
     *  由 HAL AE 决定、没有欠曝），故两侧中间调一致、成片高光更低。 */
    private fun filmParamsForStill(): DoubleArray {
        val u = burstHlUnderEv
        if (u <= 1e-4) return filmParams
        val q = filmParams.copyOf()
        q[0] = filmParams[0] + u
        return q
    }

    /** 总抖动 RMS（rad/s）—— 仅诊断，不参与决策（含高频，外推会严重高估帧内模糊）。 */
    private fun gyroRmsRadPerSec(): Double {
        synchronized(gyroLock) {
            if (gyroSamples.size < 4) return -1.0
            var acc = 0.0
            for (s in gyroSamples) {
                acc += (s.x * s.x + s.y * s.y + s.z * s.z).toDouble()
            }
            return kotlin.math.sqrt(acc / gyroSamples.size)
        }
    }

    /**
     * 场景高光分位（log2 相对中灰 0.1845，**测于提亮之前**）—— 高光保护判据（v0.10.110）。
     *
     * 取引擎每帧写入的 `w999`（P99.9）。为什么不用 `wmax`：max 是单点，受坏点与孤立
     * 点光源影响；P99.9 = 0.1% 像素，正是"高光会不会成片裁切"的量级。返回值 NaN 表示
     * **无可用统计**（引擎未出帧 / 解析失败）⇒ 规划器不做高光保护，行为与历史一致。
     */
    private fun sceneHighlightEv(): Double = try {
        val s = EngineBridge.nativeEngineGetStats()
        if (s == null) {
            Double.NaN
        } else {
            // v0.10.113：只认**成片链**的 P99.9（sw999，RAW 归一化域）。
            // 原实现读的是 `w999`——那是"最后渲染过的那条链"的值，拍照前基本就是
            // **预览链**（走 OES / ISP 已 tonemap），与 RAW 域**不是一个尺度**：同场景
            // 实测预览 2.09 / 成片 2.674，差 0.58EV 且逐次不同 ⇒ 判据既不准也不稳
            // （2026-10-03 真机教训）。无成片历史（会话第一张）⇒ NaN ⇒ 不欠曝。
            val v = org.json.JSONObject(s).optDouble("sw999", 0.0)
            if (v.isFinite() && v > 0.0) v else Double.NaN
        }
    } catch (_: Throwable) {
        Double.NaN
    }

    /** 设备物理方向监听（v0.6.39 引入 → v0.6.41 对齐 PhotonCamera）。
     *  原实现是 CameraScreen 里读 `winMgr.defaultDisplay.rotation` 快照 + `LaunchedEffect(displayRot)`，
     *  但**设备旋转不会触发 Compose 重组** → displayRot 永远停在初值 → orientationStep 恒为 0。
     *  后果：横拍成片按"竖"旋转、输出 3072×4080 且内容躺倒（EXIF 又恒 1），focusAt 的区域映射也转置。
     *  改用平台原生的 OrientationEventListener：主动回调，与重组无关。
     *  角度→step 的对应关系取自 Android 官方文档（`onOrientationChanged`：90 = 左侧朝上、270 = 右侧朝上）
     *  —— 这两个区间**最容易写反**，写反的后果正是横拍成片倒置。 */
    private val orientationListener by lazy {
        object : android.view.OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                if (isNearFlat) return // 平放：角度不可靠，保持上次值
                updateOrientationStep(orientationToStep(orientation))
            }
        }
    }

    @Volatile
    private var started = false

    /** 打开代次：快速连点焦段/镜头时会叠加多次 openCamera，过期回调按代次丢弃 */
    @Volatile
    private var openGen = 0

    @Volatile
    private var firstFrameLogged = false

    // @Volatile：capturing 现在由相机回调线程**提前**置 false（v0.10.81 起不再等
    // 渲染回调），UI 线程在 capture() 里读 —— 跨线程可见性必须显式声明。
    @Volatile
    private var capturing = false

    // 在飞渲染数（v0.10.81）。渲染线程是串行的，而每张照片的 4 帧数据是独立的
    // direct buffer（≈100MB）要活到渲染完成 —— 不设上限连拍会把内存堆爆。
    // 上限 2（用户拍板）：1 张在渲染 + 1 张排队，第 3 张按下时忽略并记 capture.busy。
    private val rendersInFlight = java.util.concurrent.atomic.AtomicInteger(0)
    private val maxRendersInFlight = 2

    // ── 后台保存队列（2026-09-30 性能收口）────────────────────────────────
    // JPEG 编码 + 落盘实测 0.34~0.40s（q100 的 Bitmap.compress 是大头），
    // DNG 落盘 0.13~0.17s。原来它们都在拍照回调里同步跑 —— 渲染完成后主线程
    // 还要被占住 0.4s，表现就是"按完快门后画面卡住、按不动"。
    // 这里把**数据已经独立**的 JPEG 保存挪到后台（ByteArray 与相机缓冲无关，
    // 无生命周期问题）；单线程串行 = 保持照片顺序、连拍时也不会堆积多份
    // 25MB 位图。DNG 不能这样挪：DngCreator 必须持有 Camera2 的 Image，
    // 而 Image 由渲染路径消费后立即 close（见 RenderClient 的 img.close()），
    // 跨线程延后保存会读到已释放的缓冲。
    private val saver = Executors.newSingleThreadExecutor { r ->
        Thread(r, "filmkit-save").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    // ── MFNR 多帧融合（v0.9.0 阶段一）────────────────────────────────
    /** 拍摄阶段（Compose state，取景框内提示用）：0 = 空闲、1 = 采集/处理中、
     *  2 = 多帧合成中（burst 4 帧到齐后、融合渲染期间） */
    var capturePhase by mutableStateOf(0)
        private set

    /** burst 采集中的帧桶（同渲染 looper 串行，无锁）。v0.9.1 改架构：
     *  **帧 1-3 拷贝到 direct buffer 后立即 close**（HAL 槽位 ~50ms 内释放，
     *  maxImages=4 够 4 帧循环），**仅参考帧（帧 0）保留 Image**——DngCreator
     *  只吃 Image，DNG 底片写出后关闭。v0.9.0 的 maxImages=5 + 4 Image 全程
     *  持有让 HAL 一次预配 125MB graphic buffer 且全程占满，是本次闪退的头号
     *  嫌疑（session_ready/预览正常、Activity 随后被系统销毁）。 */
    private val burstImages = arrayOfNulls<Image>(MFNR_FRAMES)
    private val burstBuffers = arrayOfNulls<java.nio.ByteBuffer>(MFNR_FRAMES)
    private val burstStrides = IntArray(MFNR_FRAMES)
    private val burstResults = arrayOfNulls<TotalCaptureResult>(MFNR_FRAMES)
    private var burstImageCount = 0
    private var burstResultCount = 0
    private var burstActive = false
    private var burstStartMs = 0L

    /** 传感器 CFA 排布（M2-4 RAW 去马赛克相位用） */
    @Volatile
    var colorFilterArrangement = 0
        private set

    // ── 采集控制状态（M 档，§0-D6）──
    private var backLensIds: List<String> = emptyList()
    private var activeLensIdx = 0

    /** 镜头信息（含暴力探测发现的隐藏物理镜头，参考 PhotonCamera） */
    data class LensInfo(
        val id: String,
        val focalMm: Float,
        val equivMm: Float, // 35mm 等效焦距
        val rawSupported: Boolean,
        val enabled: Boolean = true, // 用户可在设置页按镜头启停
    ) {
        fun label(): String = "${equivMm.roundToInt()}MM"
    }

    object LensDiscovery {
        /** 镜头发现（参考 PhotonCamera）：公开 ID + 隐藏物理镜头暴力探测 */
        fun discoverBackLenses(cm: CameraManager, prefs: android.content.SharedPreferences): List<LensInfo> {
        val ids = sortedSetOf<Int>()
        cm.cameraIdList.forEach { it.toIntOrNull()?.let(ids::add) }
        if (android.os.Build.MANUFACTURER.lowercase() !in setOf("huawei", "honor")) {
            for (i in 0..6) ids.add(i) // 隐藏物理镜头探测（参考 PhotonCamera）
        }
        val out = mutableListOf<LensInfo>()
        val seen = mutableSetOf<String>()
        for (n in ids.sorted()) {
            val id = n.toString()
            if (id in seen) continue
            try {
                val ch = cm.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                if (ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) != true
                ) continue
                seen.add(id)
                val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: continue
                val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val equiv = if (phys != null && phys.width > 0f && phys.height > 0f) {
                    val diag = kotlin.math.sqrt(phys.width * phys.width + phys.height * phys.height)
                    (focal * 43.27f / diag)
                } else focal
                val raw = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)?.isNotEmpty() == true
                val enabled = prefs.getBoolean("lens_on_$id", true)
                out += LensInfo(id, focal, equiv, raw, enabled)
            } catch (_: Exception) {
            }
        }
        return out.sortedBy { it.equivMm }
        }
    }

    var backLenses: List<LensInfo> = emptyList()
        private set

    private fun enabledLenses(): List<LensInfo> = backLenses.filter { it.enabled }.ifEmpty { backLenses }

    fun lenses(): List<String> = enabledLenses().map { it.label() }

    /** 焦段条数据：启用镜头的等效焦距（去重取整、升序）——不写死标准档位 */
    fun lensEquivs(): List<Int> =
        enabledLenses().map { it.equivMm.roundToInt() }.distinct().sorted()

    fun currentLensEquiv(): Float {
        val active = backLenses.getOrNull(activeLensIdx)
        return if (active?.enabled != false) active?.equivMm ?: 24f
        else enabledLenses().minByOrNull { kotlin.math.abs(it.equivMm - 24f) }?.equivMm ?: 24f
    }

    /** 变焦倍率基线 = 主摄（等效最接近 24mm）的等效焦距：70mm 显示 2.9x、15mm 显示 0.6x */
    fun mainEquiv(): Float =
        enabledLenses().minByOrNull { kotlin.math.abs(it.equivMm - 24f) }?.equivMm ?: 24f

    @Volatile
    private var zoomRatio = 1f

    @Volatile
    private var evSteps = 0
    private var evStep = 6f // 1/6 EV 兜底，open 时以 characteristics 为准
    private var evRange = android.util.Range(-24, 24)

    @Volatile
    private var wbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO

    @Volatile
    private var oisSupported = false

    /** 畸变校正可用模式（v0.10.109-exp7 探测；null = 本机 HAL 未声明该能力） */
    @Volatile
    private var distCorrectionModes: IntArray? = null

    @Volatile
    private var focusRect: android.hardware.camera2.params.MeteringRectangle? = null

    // ── 手动曝光 / 手动白平衡（M 档专业参数，§0-D6 扩展）──
    @Volatile
    private var manualExposure = false

    @Volatile
    private var iso = 100

    @Volatile
    private var exposureNs = 10_000_000L // 1/100s

    @Volatile
    private var wbK = 5000

    // 手动 ISO 滑杆上限 = SENSOR_INFO_SENSITIVITY_RANGE.upper（规范保证"设下去
    // 有效"的范围）。v0.10.11 修正（对齐 PhotonCamera 语义）：此前误用
    // SENSOR_MAX_ANALOG_SENSITIVITY（X300 报 11467）——那是"模拟增益上限"
    // （用于"别进数字增益"类判断），不是手动可设范围；真机 AE 实测能到 3687+
    // 属 HAL 的 AE 扩展，不代表手动设置有效。
    var isoRangeUpper = 3200
        private set
    var baseFocalMm = 23f
        private set
    private var apertureF = 1.68f // 最大光圈（EXIF 写入）

    fun setManualExposure(on: Boolean) {
        manualExposure = on
        rebuildRepeating()
    }

    /** 闪光灯（AE 联动）：0 关 / 1 自动 / 2 常闪；手动 AE（AE OFF）下闪光不生效，回 A 后应用 */
    @Volatile
    private var flashMode = 0

    fun setFlash(mode: Int) {
        flashMode = mode.coerceIn(0, 2)
        rebuildRepeating()
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "flash.set",
            kv("mode" to flashMode),
        )
    }

    /** 快门音：camera2 本身不发声，捕获提交时经 MediaActionSound 播放 */
    @Volatile
    private var shutterSound = true

    private val shutterSoundPlayer by lazy {
        android.media.MediaActionSound().also {
            it.load(android.media.MediaActionSound.SHUTTER_CLICK)
        }
    }

    fun setShutterSound(on: Boolean) {
        shutterSound = on
    }

    /** 手动对焦量程上限（屈光度）：HAL 上报 0 时用 10D 试值 */
    val maxDiopter: Float
        get() = minFocusDiopter.coerceAtLeast(1f)

    /** 成片裁切宽高比（与取景画幅引导一致，WYSIWYG；0 = 不裁切） */
    @Volatile var captureAspect = 0f
        private set

    /**
     * 拍照瞬间的**方向/画幅快照**（v0.10.110-fix，2026-10-03 真机）。
     *
     * 为什么必须快照：MFNR 渲染要 3~4s，用户按完快门常立刻把手机转回竖屏 —— 日志实证
     * （09:15:58 拍照 step=3 → 09:16:01 step=0 → 09:16:03 `photo.crop srcA:1.328
     * dstA:0.750`）渲染输出方向正确（4080×3072 横），但保存时读到的 `captureAspect`
     * 已被姿态变化改成竖版 0.750，于是**横拍成片被裁成 2304×3072 竖幅**（丢 44% 画面）。
     * 与项目既有教训同型：「参数快照 —— 相隔数秒的两段链路直接读成员会混进第二个变量」。
     */
    @Volatile private var stillRot = 0
    @Volatile private var stillAspect = 0f

    fun setCaptureAspect(a: Float) {
        captureAspect = a.coerceIn(0.2f, 5f)
    }

    fun setIso(v: Int) {
        iso = v.coerceIn(100, isoRangeUpper)
        if (manualExposure) rebuildRepeating()
    }

    fun setExposureNs(ns: Long) {
        exposureNs = ns.coerceIn(100_000L, 1_000_000_000L)
        if (manualExposure) rebuildRepeating()
    }

    /** 手动白平衡只存 UI 目标值：实际生效走引擎侧补偿（updateWbCompensation），不再动 HAL */
    fun setWbK(k: Int) {
        wbK = k.coerceIn(2000, 8000)
    }

    fun setAutoWb() {
        // 自动白平衡：清引擎补偿即可（HAL 恒 AWB AUTO）
        clearWbCompensation()
    }

    private var activeArray = android.graphics.Rect(0, 0, 4096, 3072)
    var maxZoom = 1f
        private set

    /** 最近一张成片的 MediaStore URI（胶卷位缩略图用）；API<29 分支不记录 */
    @Volatile
    var lastPhotoUri: android.net.Uri? = null
        private set

    /** 等效 EV 范围与步进（UI 滑杆用） */
    fun evInfo(): Triple<Float, Float, Float> =
        Triple(evRange.lower * evStep, evRange.upper * evStep, evStep)

    /** 焦段切换：等效焦段 → 自动选择镜头 + 变焦；焦段条由发现镜头的等效焦距生成 */
    fun setFocal(fEquiv: Int) {
        if (backLenses.size < 2) {
            backLenses = LensDiscovery.discoverBackLenses(
                context.getSystemService(Context.CAMERA_SERVICE) as CameraManager,
                context.getSharedPreferences("filmkit", 0),
            )
        }
        // 重新探测仍只有 1 镜头（HAL 未就绪的隐藏镜头暂不可见）也继续，后续会话再试
        val target = fEquiv.toFloat()
        // 选镜头：在用户启用的镜头里，选等效焦距 ≤ 目标中最大的那颗
        val pool = enabledLenses().ifEmpty { backLenses }
        val lens = pool.filter { it.equivMm <= target * 1.05f }.maxByOrNull { it.equivMm }
            ?: pool.minByOrNull { it.equivMm }
            ?: return
        val idx = backLenses.indexOf(lens)
        zoomRatio = (target / lens.equivMm).coerceIn(1f, maxZoom)
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "focal.set",
            kv("f" to fEquiv, "lens" to lens.id, "lensEquiv" to "%.0f".format(lens.equivMm), "zoom" to "%.2f".format(zoomRatio)),
        )
        if (idx != activeLensIdx) {
            activeLensIdx = idx
            started = false
            stopInternalKeepClient(lens.id)
        } else {
            rebuildRepeating()
        }
    }

    fun setLens(idx: Int) {
        val pool = enabledLenses()
        val lens = pool.getOrNull(idx)
        if (lens == null || lens.id == backLenses.getOrNull(activeLensIdx)?.id) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "camera", "lens.switch_ignored",
                kv("idx" to idx, "total" to pool.size),
            )
            return
        }
        activeLensIdx = backLenses.indexOf(lens).coerceAtLeast(0)
        started = false
        stopInternalKeepClient(lens.id)
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "lens.switch",
            kv("to" to idx, "id" to lens.id, "equiv" to "%.0fMM".format(lens.equivMm)),
        )
    }

    fun setZoom(ratio: Float) {
        zoomRatio = ratio.coerceIn(1f, maxZoom)
        rebuildRepeating()
    }

    /**
     * 无缝变焦：目标 35mm 等效焦段。等效焦段跨过其它镜头的等效焦距时自动切换物理镜头
     * （选法与 setFocal 一致），zoomRatio 按目标镜头归一（参考 PhotonCamera）。
     */
    fun setZoomEquiv(targetEquiv: Float) {
        if (backLenses.size < 2) {
            backLenses = LensDiscovery.discoverBackLenses(
                context.getSystemService(Context.CAMERA_SERVICE) as CameraManager,
                context.getSharedPreferences("filmkit", 0),
            )
        }
        val pool = enabledLenses().ifEmpty { backLenses }
        if (pool.isEmpty()) return
        // 画质优先：光学镜头可覆盖即选（选法与 setFocal 一致），不做数码延展；
        // 仅在切换点 ±3% 迟滞带内保持当前镜头（zoomRatio 顶到边界），
        // 避免拖动在切换点附近抖动时反复重开会话；出带立即切到光学镜头
        val cur = backLenses.getOrNull(activeLensIdx)?.takeIf { pool.contains(it) }
        var lens = pool.filter { it.equivMm <= targetEquiv * 1.05f }.maxByOrNull { it.equivMm }
            ?: pool.minByOrNull { it.equivMm }
            ?: return
        if (cur != null && lens.id != cur.id) {
            val sp = maxOf(cur.equivMm, lens.equivMm) / 1.05f
            if (targetEquiv >= sp * 0.97f && targetEquiv <= sp * 1.03f) lens = cur
        }
        val idx = backLenses.indexOf(lens)
        val newRatio = (targetEquiv / lens.equivMm).coerceIn(1f, maxZoom)
        if (idx != activeLensIdx) {
            zoomRatio = newRatio
            activeLensIdx = idx
            started = false
            stopInternalKeepClient(lens.id)
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "zoom_equiv.lens_switch",
                kv("equiv" to "%.1f".format(targetEquiv), "lens" to lens.id,
                    "lensEquiv" to "%.0f".format(lens.equivMm), "zoom" to "%.2f".format(newRatio)),
            )
        } else if (Math.abs(newRatio - zoomRatio) >= 0.01f) {
            zoomRatio = newRatio
            rebuildRepeating()
        }
    }

    fun setEv(ev: Float) {
        evSteps = (ev / evStep).toInt().coerceIn(evRange.lower, evRange.upper)
        // v0.7.8：EV 同步进引擎（FilmParams[0] = exposure_ev）。原先只写 HAL 的
        // CONTROL_AE_EXPOSURE_COMPENSATION，而 X300 在 RAW 静拍上对它几乎不执行
        // （+0.7EV 实测只给 +0.1EV：sec 0.0014→0.0015s），RAW 成片纹丝不动。
        // v0.8.1：[0] = 基准曝光（base_ev，机型校准常量）+ 用户 EV。
        // v0.10.22：语义 = **filmic 前线性域曝光**（PC/Darktable BaselineExposure 语义，
        // 进曲线改工作点），与预览分支统一——此前链末乘性与预览分叉，且后置乘性
        // 只平移亮度、黑位/对比不动（−0.3EV 真机复拍仍灰）。auto 黑点已默认关，
        // v0.7.7"统计归一"的旧顾虑不再成立。
        filmParams[0] = baseEvPref + (evSteps * evStep).toDouble()
        rebuildRepeating()
    }

    /** 色温 → 通道增益（R/G/B，G 归一）：黑体色度取反即白平衡增益，钳制防过冲 */
    private fun wbGains(kelvin: Int): FloatArray {
        val t = kelvin.coerceIn(1500, 12000) / 100.0
        val r: Double
        val g: Double
        val b: Double
        if (t <= 66) {
            r = 255.0
            g = 99.4708025861 * Math.log(t) - 161.1195681661
        } else {
            r = 329.698727446 * Math.pow(t - 60, -0.1332047592)
            g = 288.1221695283 * Math.pow(t - 60, -0.0755148492)
        }
        b = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> 138.5177312231 * Math.log(t - 10) - 305.0447927307
        }
        val rn = r.coerceIn(0.0, 255.0)
        val gn = g.coerceIn(0.0, 255.0)
        val bn = b.coerceIn(0.0, 255.0).coerceAtLeast(1.0)
        return floatArrayOf(
            (gn / rn).toFloat().coerceIn(0.3f, 3f),
            1f,
            (gn / bn).toFloat().coerceIn(0.3f, 3f),
        )
    }

    fun setWb(mode: Int) {
        wbMode = mode
        rebuildRepeating()
    }

    /** 触屏对焦：视图归一化坐标 → 传感器 active array 测光/对焦区域。
     *  传感器是横向的（如 X300 sensorOrientation=90），显示图 = 传感器顺时针旋转；
     *  视图坐标必须按总旋转（传感器方向 + 界面方向步进）反推回传感器坐标，
     *  否则 AF/AE 区域落点是转置的——点哪儿对焦都不在哪儿。 */
    fun focusAt(nx: Float, ny: Float) {
        val r = activeArray
        val k = (sensorOrientation / 90 + orientationStep) % 4
        val (snx, sny) = when (k) {
            1 -> ny to 1f - nx
            2 -> 1f - nx to 1f - ny
            3 -> 1f - ny to nx
            else -> nx to ny
        }
        val cx = r.left + (snx.coerceIn(0f, 1f) * r.width()).toInt()
        val cy = r.top + (sny.coerceIn(0f, 1f) * r.height()).toInt()
        val half = (minOf(r.width(), r.height()) * 0.15f).toInt().coerceAtLeast(32)
        focusRect = android.hardware.camera2.params.MeteringRectangle(
            (cx - half).coerceAtLeast(r.left), (cy - half).coerceAtLeast(r.top),
            half * 2, half * 2, 1000,
        )
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "focus.set",
            kv(
                "nx" to "%.2f".format(nx), "ny" to "%.2f".format(ny),
                "rot" to k, "sx" to "%.2f".format(snx), "sy" to "%.2f".format(sny),
            ),
        )
        rebuildRepeating()
    }

    private fun rebuildRepeating() {
        val s = session ?: return
        val d = camera ?: return
        val out = previewSurface ?: return
        // 签名未变化时不重建（防 UI 重组导致的每帧重复合并刷屏）；
        // 对焦区域要含坐标——否则第二次点击不同位置会被当作未变化而跳过
        val fr = focusRect
        val focusSig = if (fr != null) "${fr.x},${fr.y},${fr.width}" else "null"
        val sig = "${evSteps}|${manualExposure}|${iso}|${exposureNs}|${wbMode}|${wbK}|${zoomRatio}|${manualFocus}|${focusDiopter}|${focusSig}|${flashMode}"
        if (sig == lastApplied) return
        lastApplied = sig
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(out)
                applyControls(this)
            }
            s.setRepeatingRequest(b.build(), repeatCb, render.looper?.let { android.os.Handler(it) })
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "controls.applied",
                kv(
                    "evSteps" to evSteps, "ae" to (if (manualExposure) "OFF" else "AUTO"),
                    // 注意：这两个是**滑杆存量值**，AUTO 档下不代表实际生效曝光（曾被
                    // 误读成"预览用 ISO 100"）。预览的实际曝光见 raw.meta 的 pvIso/pvSec。
                    "iso" to iso, "sec" to "%.4f".format(exposureNs / 1e9f),
                    "wb" to "AUTO+eng",
                    "zoom" to "%.2f".format(zoomRatio), "focus" to (focusSig != "null"),
                ),
            )
        } catch (t: Throwable) {
            lastApplied = "" // 失败允许重试
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "rebuild_repeating_failed",
                kv("error" to (t.message ?: t.toString())),
            )
        }
    }

    private var lastApplied = ""

    // ── 实时曝光读数（重复请求回调回填，UI 500ms 轮询）──
    @Volatile var liveIso = 0
        private set
    @Volatile var liveExposureNs = 0L
        private set
    @Volatile var liveK = 0
        private set
    @Volatile var liveFocusDiopter = 0f
        private set

    // AWB 实时估计（仅 AWB AUTO 时更新）：优先 GAINS 纯增益，兜底矩阵对角线（带设备校准）
    // AWB 实时估计：仅用于显示与引擎补偿基准（GAINS 结果键优先，X300 实测不回填 →
    // 矩阵对角线 + 设备校准系数兜底）
    @Volatile private var estGainR = 0f
    @Volatile private var estGainB = 0f
    @Volatile private var estK = 0

    companion object {
        // 变换矩阵对角线的 CCM 常量偏差校准（X300）：由真机日光数据点推得——
        // 矩阵估计 3400K 的场景，常识/他机 ≈ 4800K → cR = 0.84/0.91 ≈ 0.923，cB = 1.30/1.05 ≈ 1.238。
        // M3 用灰卡在两三个标准光源下复核。
        private const val WB_CAL_R = 0.923f
        private const val WB_CAL_B = 1.238f

        /** 平放判定的重力 z 分量阈值（m/s²），抄 PhotonCamera `OrientationObserver.FLAT_AXIS_THRESHOLD` */
        private const val FLAT_AXIS_THRESHOLD = 7.5f

        /** `OrientationEventListener` 角度 → 设备**物理**旋转步进（0=竖 / 1=顺时针 90° / 2=倒竖 / 3=逆时针 90°）。
         *  角度语义取自 Android 官方文档：90 = 左侧朝上、270 = 右侧朝上（**这两个区间最容易写反**）。
         *  抽成 internal 是为了让单测能直接对照 PhotonCamera 的
         *  `(sensorOrientation + deviceRotation) % 360` 验算四个姿态 —— 写反的后果是横拍成片倒置。 */
        internal fun orientationToStep(orientation: Int): Int = when (orientation) {
            in 45..134 -> 1  // 左侧朝上 = 设备顺时针 90°
            in 135..224 -> 2 // 倒竖
            in 225..314 -> 3 // 右侧朝上 = 设备逆时针 90°
            else -> 0        // 竖（含 ORIENTATION_UNKNOWN 之外的所有残角）
        }

        /** motion metering 的角速度统计窗口（Night Sight：快门按下前 ~1.5s 平滑） */
        const val GYRO_WINDOW_NS = 1_000_000_000L

        /** 采集端长曝光目标：同机 Filmix 实证 1/4s 可用（ISO166+1/4s 样片）；
         *  Night Sight 标准手持上限 333ms 待真机确认后再放开 —— 保守起步，照片优先。 */
        const val EXP_TARGET_EXPOSURE_NS = 250_000_000L

        /** MFNR 阶段一固定帧数（融合 shader 固定 4 输入） */
        const val MFNR_FRAMES = 4

        /** burst 集齐兜底超时：4 帧全分辨率 RAW 正常 ≤2s，5s 仍不齐按失败释放 */
        const val MFNR_TIMEOUT_MS = 5000L

        /** 自动档暗光触发阈值：预览实时 ISO 或曝光任一超过即触发（Night Sight
         *  手持单帧上限 333ms 的量级之下；ISO 阈值对应用户暗光 A/B 场景） */
        const val MFNR_ISO_THRESHOLD = 600
        const val MFNR_EXPOSURE_THRESHOLD_NS = 25_000_000L

        /** MFNR 触发判定（纯函数供单测）：
         *  mode 0 = 关（永不）；1 = 自动（RAW+RCD 质量档 + 暗光阈值）；
         *  2 = 强制（RAW+RCD 即触发，白天也 4 帧，便于 A/B）。 */
        fun mfnrShouldTrigger(mode: Int, rawMode: Boolean, demosaicRcd: Boolean,
                              liveIso: Int, liveExposureNs: Long): Boolean {
            if (mode <= 0) return false
            if (!rawMode || !demosaicRcd) return false
            if (mode >= 2) return true
            return liveIso >= MFNR_ISO_THRESHOLD ||
                liveExposureNs >= MFNR_EXPOSURE_THRESHOLD_NS
        }

        /**
         * 降噪强度（C 专项 2026-09-29：滑杆 0–2、步长 0.01；0 = 关、1 = 真实 σ、
         * 2 = 2×σ）。含旧三档 prefs 的一次性迁移（level 0/1/2 → 0/1/2；旧 bool
         * 开关 false 视为 0）。纯函数供单测。
         */
        fun denoiseStrength(
            hasNewKey: Boolean,
            newValue: Float,
            oldEnabled: Boolean,
            oldLevel: Int,
        ): Float {
            if (hasNewKey) return newValue.coerceIn(0f, 2f)
            if (!oldEnabled) return 0f
            return when (oldLevel) {
                0 -> 0f
                1 -> 1f
                else -> 2f
            }
        }

        /** 成片输出摘要（同帧 A/B 用，v0.10.85）：CRC32 全量（逐字节等价的硬证据）
         *  + 16×16 网格采样均值（诊断"整张黑 / 亮度漂移"）。只在 still_ab 打开时
         *  计算，纯函数供单测。bytes 为 RGBA（与 Bitmap ARGB_8888 内存序一致）。 */
        class OutDigest(val crc: Long, val mean: Double)

        fun outDigest(bytes: ByteArray, w: Int, h: Int): OutDigest {
            val c = java.util.zip.CRC32()
            c.update(bytes)
            val step = maxOf(1, minOf(w, h) / 16)
            var sum = 0L
            var n = 0
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val i = (y * w + x) * 4
                    if (i + 2 < bytes.size) {
                        sum += (bytes[i].toInt() and 0xFF) +
                            (bytes[i + 1].toInt() and 0xFF) +
                            (bytes[i + 2].toInt() and 0xFF)
                        n += 3
                    }
                    x += step
                }
                y += step
            }
            return OutDigest(c.value, if (n > 0) sum.toDouble() / n else -1.0)
        }
    }

    private val repeatCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult,
        ) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { liveIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { liveExposureNs = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { if (it > 0f) liveFocusDiopter = it }
            // 色温估计：优先读 COLOR_CORRECTION_GAINS 纯增益结果键（API36 作为结果键仍在，
            // 与 PhotonCamera 同源；X300 实测不回填 → 走矩阵对角线兜底 + 设备校准）
            var kEst = 0
            var gR = 0f
            var gB = 0f
            result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { gg ->
                val green = ((gg.greenEven + gg.greenOdd) / 2f).coerceAtLeast(1e-3f)
                val r = gg.red / green
                val b = gg.blue / green
                if (r > 0.001f && b > 0.001f) {
                    gR = r
                    gB = b
                    kEst = kFromGains(gR, gB)
                }
            }
            result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { t ->
                if (kEst == 0 && gR == 0f) {
                    val dr = t.getElement(0, 0).toFloat() / t.getElement(1, 1).toFloat().coerceAtLeast(1e-3f)
                    val db = t.getElement(2, 2).toFloat() / t.getElement(1, 1).toFloat().coerceAtLeast(1e-3f)
                    if (dr > 0.001f && db > 0.001f) {
                        // 对角线混有 CCM 常量偏差 → 用 X300 实测校准系数修正
                        gR = dr / WB_CAL_R
                        gB = db / WB_CAL_B
                        kEst = kFromGains(gR, gB)
                    }
                }
            }
            if (kEst > 0) {
                liveK = kEst
                if (gR > 0f) {
                    estGainR = gR
                    estGainB = gB
                    estK = kEst
                }
            } else {
                result.get(CaptureResult.COLOR_CORRECTION_COLOR_TEMPERATURE)?.let { liveK = it }
            }
        }
    }

    /** 增益比 → 色温：50K 步进扫描 + 组合对数距离（PhotonCamera estimateKelvinFromRggbGains 同款） */
    private fun kFromGains(gR: Float, gB: Float): Int {
        var bestK = 2000
        var bestDist = Double.MAX_VALUE
        var k = 2000
        while (k <= 8000) {
            val g = wbGains(k)
            val d = Math.abs(Math.log((g[0] / gR.coerceAtLeast(1e-3f)).toDouble())) +
                Math.abs(Math.log((g[2] / gB.coerceAtLeast(1e-3f)).toDouble()))
            if (d < bestDist) {
                bestDist = d
                bestK = k
            }
            k += 50
        }
        return bestK
    }

    // ── 手动对焦（屈光度）──
    @Volatile
    private var manualFocus = false

    @Volatile
    private var focusDiopter = 0f
    var minFocusDiopter = 0f
        private set

    fun setManualFocus(on: Boolean) {
        manualFocus = on
        rebuildRepeating()
    }

    fun setFocusDiopter(d: Float) {
        focusDiopter = d.coerceIn(0f, minFocusDiopter)
        if (manualFocus) rebuildRepeating()
    }

    private fun applyControls(b: CaptureRequest.Builder, distort: Boolean = false) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        // AF：手动对焦 OFF + 屈光度；否则连续对焦
        if (manualFocus && minFocusDiopter > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopter)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        // AE：手动模式 OFF + 直填 ISO/快门；自动模式 ON + EV 补偿
        if (manualExposure) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        } else {
            // 自动 AE：闪光灯档位联动（0 关=纯 ON / 1 自动闪光 / 2 常闪）
            b.set(
                CaptureRequest.CONTROL_AE_MODE,
                when (flashMode) {
                    2 -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    1 -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                    else -> CaptureRequest.CONTROL_AE_MODE_ON
                },
            )
            // v0.7.8：不再写 CONTROL_AE_EXPOSURE_COMPENSATION —— EV 滑杆改走引擎
            // 显示亮度补偿（filmParams[0]）。X300 对 RAW 静拍流的 HAL 补偿几乎不执行
            // （+0.7EV 实测只给 +0.1EV），且 HAL 补偿会改变 DNG 底片本体；引擎补偿
            // 只动渲染、底片保持原始曝光（符合 RAW 语义）。预览由 kFsOes 的
            // uExposure 承担同一值，预览/成片一致。
        }
        // WB 恒为 AWB AUTO：手动白平衡走引擎侧补偿（updateWbCompensation），
        // 不再 AWB OFF + 矩阵（X300 实测 CCT 忽略、矩阵路径观感不可控）
        b.set(CaptureRequest.CONTROL_AWB_MODE, wbMode)
        if (oisSupported) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        focusRect?.let {
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
            b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
        }
        if (Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        } else {
            // ponytail: API<30 用 crop region 近似变焦，M3 视兼容矩阵需要再精化
            val r = activeArray
            val w = (r.width() / zoomRatio).toInt()
            val hgt = (r.height() / zoomRatio).toInt()
            b.set(CaptureRequest.SCALER_CROP_REGION,
                android.graphics.Rect(r.left + (r.width() - w) / 2, r.top + (r.height() - hgt) / 2,
                    r.left + (r.width() + w) / 2, r.top + (r.height() + hgt) / 2))
        }
        // ── 畸变校正探测（v0.10.109-exp7）────────────────────────────────
        // 只下发给**静拍**请求（distort=true）：若对预览下发而 HAL 只对 YUV 生效，
        // 会出现"预览被校正、成片(RAW)未校正"的 WYSIWYG 破坏。
        // HAL 未声明支持则不发（未声明模式下发请求会被拒/忽略）。
        val dm = distCorrectionModes
        if (distort && android.os.Build.VERSION.SDK_INT >= 28 && dm != null &&
            dm.contains(android.hardware.camera2.CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
        ) {
            b.set(
                CaptureRequest.DISTORTION_CORRECTION_MODE,
                android.hardware.camera2.CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
            )
        }
    }

    /** 切镜头的待重开镜头 ID（onClosed 里消费；跨线程访问，volatile） */
    @Volatile
    private var pendingOpenId: String? = null

    /**
     * 切镜头：关闭当前设备，重开经 onClosed 串行触发（旧 Surface 释放完成后再 open）。
     * stillReader/surfaceTexture/previewSurface 保留复用：切换期间渲染端持续输出最后一帧
     * （冻结而非黑屏），尺寸不匹配时在 openCamera 重建
     */
    private fun stopInternalKeepClient(pendingLensId: String? = null) {
        pendingOpenId = pendingLensId
        val cam = camera
        val ses = session
        camera = null
        session = null
        render.post {
            try { ses?.stopRepeating() } catch (_: Throwable) {}
            cam?.close()
        }
    }

    /** 静拍格式切换（M 档 RAW 开关）后重建会话：走切镜头同款串行重开，
     *  openCamera 会重读 raw_mode prefs 决定新格式 */
    fun restartSession() {
        val id = backLenses.getOrNull(activeLensIdx)?.id ?: return
        started = false
        stopInternalKeepClient(id)
    }

    fun setFilmParams(p: DoubleArray) {
        filmParams = p
        // [0] = 基准曝光 + 用户 EV：预设基线里是 0，换数组后必须回填，否则初始化序列
        // 会把曝光静默清零（v0.7.8 教训）。applyBaseEvPref 先把 prefs 的基准值灌进
        // baseEvPref，再统一写入（v0.8.1 起含基准偏置）
        applyBaseEvPref()
        p[0] = baseEvPref + (evSteps * evStep).toDouble()
        presetWbBase = doubleArrayOf(p[1], p[2], p[3])
        tonePref = -1.0  // 换了新数组，强制重写 [28]
        zebraPref = -1.0 // 同上，[30]
        autoTonePref = -1.0 // 同上，[35]
        lutStrengthFromPrefs = Float.NaN // 同上，[34]：换预设后强制重读 prefs
        applyTonePref()
        applyZebraPref()
        applyLutStrengthPref()
    }

    private val appPrefs by lazy { context.getSharedPreferences("filmkit", 0) }
    private var tonePref = -1.0
    private var zebraPref = -1.0
    private var lutStrengthPref = -1.0
    private var lutStrengthFromPrefs = Float.NaN // 上次从 prefs 读到的强度（变化检测；NaN = 未读）
    private var autoTonePref = -1.0
    // filmic 黑白点（v0.7.6）：哨兵 = 未初始化（用户可配，默认 −7.65/+4.56 EV）
    private var filmicBlackPref = -999.0
    private var filmicWhitePref = -999.0
    // 基准曝光偏置（v0.8.1，FilmParams[0] 的常量部分）：校正机型 AE 基准差。
    // 实测本机（X300）HAL 的 AE 比 PhotonCamera 策略暗 0.5–0.9 EV（室外 0.67 / 室内
    // 逆光 ~0.9）—— 影调引擎（filmic）只做映射、不产生曝光，这个差只能在校准层补。
    // 与 Lightroom/DNG 的 BaselineExposure 同语义；范围 −2..+2 覆盖机型间差异，
    // 用户 EV 滑杆（evSteps）叠加在其上：[0] = base + user。
    // v0.10.22：作用点移到 filmic 前（曲线工作点语义，与 PC 的 Linear 域 EV 同构）。
    // v0.10.25：默认 +0.70 —— 同 DNG 双渲染实测（205833/205832，中性区净差 +0.57EV、
    // 红杯 C* 差 14.5%）标定：PC 链首恒乘 0.7EV 而我们为 0，工作点上移是色度差的主项。
    private var baseEvPref = 0.7

    private fun applyBaseEvPref() {
        val v = appPrefs.getFloat("base_ev", 0.7f).toDouble().coerceIn(-2.0, 2.0)
        if (v != baseEvPref) {
            baseEvPref = v
        }
    }
    // 局部色调映射（M4-7）：强度与 CameraPresets[36] 默认值一致；0 = 关（A/B 通路）。
    // v0.6.33：0.6 → 0.25（roadmap 立项定的上限就是 ≤0.2–0.3）。
    private val LOCAL_TONE = 0.25
    private var localTonePref = -1.0
    // 轻度锐化（v0.9.6）：强度与 CameraPresets[39] 默认值一致；0 = 关（A/B 通路）。
    private val SHARPEN = 0.5
    private var sharpenPref = -1.0
    // darktable filmic v5 gamut mapping（v0.10.13）：标准算法组成部分，默认开。
    private val GAMUT = 1.0
    private var gamutPref = -1.0
    // darktable filmic 高光重建（v0.10.15）：原生算法完整实现，默认开。
    private val HR_WAVELET = 1.0
    // v0.10.27：白点高光感知（FilmParams[42]，auto_tone 开时生效）
    private val WHITE_HL = 1.0
    // v0.10.115：RAW 域高光重建（FilmParams[45]，darktable highlights "inpaint
    // opposed"）。去马赛克前对裁剪通道做对立外推 + 全图色度校正。
    private val HR_RAW = 1.0
    // v0.10.117：RCD 色差段回归 RawTherapee 原版（FilmParams[46]）
    private val RCD_CDFIX = 1.0
    // v0.10.134：逆光边缘紫边抑制（FilmParams[47]，RawTherapee Defringe 同形态）
    private val DEFRINGE = 1.0
    // v0.10.169：高光重建·G 通道重建（FilmParams[49]）。1 = 开 = 现有行为
    // （kFsHlApply 的 apply 支含自创的 G 三级池化比 + allSat 三通道改写）；
    // 0 = 关（apply 只留分支① 的 darktable opposed 外推 = PhotonCamera 口径）。
    private val HL_GRECON = 1.0
    // v0.10.170：镜头横向色差校正（FilmParams[50]）。作用在 D3（RCD 去马赛克后 /
    // CCM 前）相机 RGB 域：自动估计（1/8 降采样 + 径向 Lucas-Kanade）+ 径向位移
    // 重采样 R/B（G 逐位不动）。判据：角部位移 ≥ 0.5px 且内外半区拟合一致，
    // 否则该通道不动作；两通道都不动作 ⇒ 整段跳过（走原 tex_work_）。
    private val CA_CORR = 1.0
    // v0.10.173：强边缘色度梯度门（FilmParams[51]，照抄 PhotonCamera 的 sigZ 架构）。
    // PC 依据 = esd3d2.glsl:78-82（chromaDiff *= max(|Δx|,|Δy|) 与
    // sigZ = max(sigY, min(chromaNoise·MOIRE, 0.2))，色度权重只由 sigZ 定）。
    // κ=2 的标定与离线实测见 core/engine/src/film/FilmParams.h 的 [51] 注释。
    // 0 = 关（A/B 通路 ⇒ P4/D-1 逐位回到 v0.10.172）。
    private val CHROMA_GATE = 2.0
    // v0.10.176：PC ESD3D2 结构开关（FilmParams[54] / [55]，prefs `pc3d` /
    // `pc3d_msize`）—— 引擎侧 v0.10.175 已完整实现，但此前**没有设置页入口 ⇒
    // 用户无法打开**；本版只补 UI + 下发通路，不动引擎。
    // [54]：0 = 关（默认 ⇒ 与 v0.10.174 逐位相同）/ 1 = 替换档（旁路 P4/D-1）/
    //       2 = 叠加档（叠在现有色度降噪之上）。
    // [55]：0 = 自动（照抄 PC 公式）/ 7 / 11 / 15 / 21 = 强制核尺寸（奇数）。
    // 代价与离线数据见 FilmParams.h 的 [54]/[55] 注释；真机判据 = 日志字段
    // pc3dOn / pc3dM / pc3dK / pc3dSc / pc3dMs。
    private var pc3dPref = -1.0
    private var pc3dMsizePref = -1.0
    private var chromaGatePref = -1.0
    private var rcdCdFixPref = -1.0
    private var defringePref = -1.0
    private var caCorrPref = -1.0
    private var hlGreconPref = -1.0
    private var hrRawPref = -1.0
    private var hrPref = -1.0
    private var whiteHlPref = -1.0
    // v0.10.111：白点下限边距（FilmParams[43]；0 = 旧行为）
    private var whiteFloorPref = -1.0
    // v0.10.111：白点上探幅度上限（FilmParams[44]；旧硬编码 1.5）
    private var whiteProbePref = -1.0
    // 饱和补偿标定旋钮（v0.10.21）：原生 gamut 色度 min 的低饱和副作用由**风格层**
    // （uSat 分区饱和，FilmParams[20..22]）标定补偿——分层架构对应 darktable 的
    // filmic → color balance、PC 的 filmic → LUT。只作用阴影/中间调，高光不动。
    private var satCompPref = Double.NaN
    // 标定探针按 **cameraId** 各记一次（v0.6.20）：原来整个会话只记一次，导致无法
    // 区分"主摄/长焦是否拿到不同的色彩矩阵"—— 真机反馈长焦色彩正、主摄明显差，
    // 必须先用日志确认厂商有没有给两个镜头写同一套 ColorMatrix/ForwardMatrix。
    private var calibLoggedId: String? = null
    private var currentLensId = ""

    /**
     * 基准影调开关（M3 色彩内核 v1）：写 FilmParams[28]（0 = 恒等/旧行为）。
     * 预览每帧传同一数组、成片经 submitStill/submitRaw 传，故三处同步即"所见即所得"。
     * 默认开；设置页「基准影调」可关（用于同场景 A/B 对照）。
     */
    private fun applyTonePref() {
        val v = if (appPrefs.getBoolean("base_tone", true)) 1.0 else 0.0
        if (v != tonePref) {
            tonePref = v
            filmParams[28] = v
        }
    }

    /**
     * 基础影调模式（v0.10.112）：`tone_mode` 0 = **标准**（固定 darktable 默认黑白点
     * −8/+4 EV，关掉全部自动机制）；1 = 自适应（旧行为，保留 A/B）。
     *
     * 为什么收成一个开关：影调层此前堆了 5 个互相干扰的自动机制（自适应影调 / 高光
     * 自适应白点 / 白点下限边距 / 白点上探幅度 / contrast 补偿），每一个都是为修某个
     * 场景加的，结果是"修一个坏一个"且设置爆炸。标准做法（darktable filmic、AgX、
     * ACES）的影调映射都是**一套固定参数**——场景差异由**曝光**吸收，不由曲线逐场景变。
     */
    private fun toneStandard(): Boolean = true   // v0.10.113：标准为唯一行为（无 UI 开关）
    // 注：原先给过 `tone_mode` 开关，但它与既有的「基准影调」（base_tone，FilmParams[28]）
    // 名字撞车，设置页出现两个"基础/基准影调"——已撤掉开关，标准模式成为唯一行为；
    // 自适应分支保留在代码里仅供离线回退，不再暴露给用户。

    /**
     * LUT 强度（M4 §6 节点 opacity）：写 FilmParams[34]（0 = 旁路，1 = 全量，
     * 1.5 = 150% —— 沿 LUT 色差外推，与 PhotonCamera 的 >100% 强度语义一致）。
     * v0.10.97：拖动直通（[setLutStrength]）与 prefs 同步双通道并存 ——
     * [lutStrengthFromPrefs] 记录上次读到的 prefs 值，**只有 prefs 真变了才写数组**：
     * 拖动中帧回调（本函数每帧被调）不会用 prefs 旧值把直通值拉回去；
     * 拖动结束写 prefs 后这里幂等同步一次。
     */
    private fun applyLutStrengthPref() {
        val p = appPrefs.getFloat("lut_strength", 1f)
        if (p != lutStrengthFromPrefs) {
            lutStrengthFromPrefs = p
            lutStrengthPref = p.toDouble().coerceIn(0.0, 1.5)
            filmParams[34] = lutStrengthPref
        }
    }

    /**
     * LUT 强度直通（v0.10.97）：滑杆**拖动中**的实时通道 —— 直接更新 filmParams[34]，
     * **不写 prefs**。拖动主线程路径只剩 post（非阻塞），SharedPrefs 的 apply 从
     * 60 次/秒的高频路径移除（QueuedWork 锁竞争在低端机上有已知的主线程抖动）；
     * prefs 只在拖动结束（onValueChangeFinished）写一次，作为持久化事实源。
     * lutStrengthPref 同步更新，拖动结束后的 applyLutStrengthPref 幂等。
     * 必须排队到渲染线程：filmParams 的元素写一直在 render.looper 上，跨线程写
     * DoubleArray 元素有撕裂风险（数组整体替换才原子）。
     */
    fun setLutStrength(v: Float) {
        render.post {
            lutStrengthPref = v.toDouble().coerceIn(0.0, 1.5)
            filmParams[34] = lutStrengthPref
        }
    }

    /** 过曝提示开关（斑马纹，仅预览）：写 FilmParams[30]（0 = 关，0.95 ≈ 242/255）。 */
    private fun applyZebraPref() {
        val v = if (appPrefs.getBoolean("zebra", true)) 0.95 else 0.0
        if (v != zebraPref) {
            zebraPref = v
            filmParams[30] = v
        }
        // 自适应影调（M4-6）与局部色调映射（M4-7）同属"输出域开关"，
        // 搭同一批刷新入口（共 4 处调用点）
        applyAutoTonePref()
        applyLocalTonePref()
        applySharpenPref()
        applyGamutPref()
        applyHrWaveletPref()
        applyHrRawPref()
        applyRcdCdFixPref()
        applyDefringePref()
        applyHlGReconPref()
        applyCaCorrPref()
        applyChromaGatePref()
        applyPc3dPref()
        applyWhiteHlPref()
        applyWhiteFloorPref()
        applyWhiteProbePref()
        applySatCompPref()
        applyFilmicRangePrefs()
    }

    /**
     * filmic 黑白点（v0.7.6）：写 FilmParams[37]/[38]，单位 = 相对中灰 18.45% 的 EV。
     * 默认 −7.65/+4.56（darktable 默认 = PhotonCamera 固化值）。用户可在设置页自由调整。
     * auto_tone 开时黑点由引擎按场景分位自动（[37] 被忽略）。
     */
    private fun applyFilmicRangePrefs() {
        // v0.10.118 修复：默认 = darktable filmic 出厂默认（black −8.0 / white +4.0 EV
        // 相对中灰，一手出处 docs/research/01-darktable.md），但**用户滑杆必须能覆盖**。
        // 旧实现（v0.10.113 起）在标准模式下把两者硬编码成常量，设置页的「白点（多亮
        // 算白）」滑杆写了 prefs 却永远进不了引擎 —— 2026-10-03 真机 A/B 因此把
        // "影调层无影响"当成了结论（日志 whiteEv 恒 4.0，与滑杆值无关）。
        val b = appPrefs.getFloat("filmic_black_ev", -8.0f).toDouble()
        val w = appPrefs.getFloat("filmic_white_ev", 4.0f).toDouble()
        if (b != filmicBlackPref) {
            filmicBlackPref = b
            filmParams[37] = b
        }
        if (w != filmicWhitePref) {
            filmicWhitePref = w
            filmParams[38] = w
        }
    }

    /**
     * 自适应影调开关（v0.7.6 语义）：写 FilmParams[35]。
     * 1.0 = **黑点**由引擎按场景分位自动标定（白点仍用 [38] 的用户配置）；
     * 0.0 = 黑白点都用用户配置（[37]/[38]）—— **默认**，即 PC 的做法：曲线固定、曝光交给 AE。
     * 旧的"按场景中位算曝光增益"（kTarget 中位锚定）已在 v0.7.3 整体废除。
     */
    private fun applyAutoTonePref() {
        // v0.7.6：prefs 键改名 auto_tone → **auto_black_ev** —— 语义已从"曝光增益"
        // 变成"黑点是否自动"，沿用旧键会让老用户残留的 true 覆盖新默认值（关）。
        // v0.10.19：黑白点自动**默认关**（三版真机证伪后撤销：0.10.16 中间调低/
        // 水壶过曝/天空过饱和、0.10.17 全面糟糕化、0.10.18 power 5.17 无改善——
        // 自动分位定曲线端点会把 power 推离固定曲线的 3.62 设计值。PC 的 SDR 链
        // 实锤无场景归一：固定 filmic 默认参数 + DngBaselineExposure + AE 曝光决策；
        // sceneExposureGain 只是 HDR 参考链的坐标增益）。HR 裁剪阈值已与曲线解耦
        // （恒 1.0），黑白点自动降级为可选工具（darktable 的 autodetect 本就是
        // 手动拾取器），亮度校准回归 base_ev 机制。
        val v = if (!toneStandard() && appPrefs.getBoolean("auto_black_ev", false)) 1.0 else 0.0
        if (v != autoTonePref) {
            autoTonePref = v
            filmParams[35] = v
        }
    }

    /**
     * 局部色调映射开关（M4-7）：写 FilmParams[36]。
     * >0 = 暗部线性提亮比例（[LOCAL_TONE] 与 CameraPresets 默认值一致），
     * 0.0 = 关（A/B 通路）。只让暗区吃到提亮、亮区强度归零，故与自适应影调不冲突。
     */
    private fun applyLocalTonePref() {
        val v = if (appPrefs.getBoolean("local_tone", true)) LOCAL_TONE else 0.0
        if (v != localTonePref) {
            localTonePref = v
            filmParams[36] = v
        }
    }

    /**
     * 轻度锐化开关（v0.9.6）：写 FilmParams[39]。
     * luma USM，kFsOut 内高光滚降后、OETF 前的线性显示域（管线顺序与用户定稿一致）。
     * [SHARPEN] 与 CameraPresets 默认值一致，0.0 = 关（A/B 通路）。
     */
    private fun applySharpenPref() {
        val v = if (appPrefs.getBoolean("sharpen", true)) SHARPEN else 0.0
        if (v != sharpenPref) {
            sharpenPref = v
            filmParams[39] = v
        }
    }

    /**
     * darktable filmic v5 gamut mapping 开关（v0.10.13）：写 FilmParams[40]。
     * tone mapping 后的色度收缩 + Yrg 检查 + clip chroma（sRGB 色域映射），
     * 标准算法的组成部分（非风格）；0 = 关（A/B 通路）。
     */
    private fun applyGamutPref() {
        val v = if (appPrefs.getBoolean("gamut", true)) GAMUT else 0.0
        if (v != gamutPref) {
            gamutPref = v
            filmParams[40] = v
        }
    }

    /**
     * darktable filmic 高光重建开关（v0.10.15）：写 FilmParams[41]。
     * à trous 小波重建部分通道裁剪的高光（线性 RGB 域、filmic 前），原生算法
     * 完整实现（mask/inpaint/小波/RGB+ratios 两轮），参数 = darktable 默认。
     * 0 = 关（A/B 通路）。
     * ── v0.10.162：默认值 true → **false（默认关）**────────────────────────
     * 本层是"下发给引擎的值"的**唯一决定层**（`hrPref` 初值 −1.0 ⇒ 首次调用必
     * 写入），所以默认关只需改这里；prefs 里已显式存过的值不受影响（老用户不变）。
     * 依据：PC（对标对象）没有小波高光重建这一模块，它的高光处理只有 OpposedGL
     * 一套（我们的 [45] hr_raw），本模块与之职责重复 ⇒ 按 PC 路子本该退场，
     * 先默认关、不删链（保留开关供 A/B 复核）。回退细节见 settings 侧同款注释。
     */
    private fun applyHrWaveletPref() {
        val v = if (appPrefs.getBoolean("hr_wavelet", false)) HR_WAVELET else 0.0
        if (v != hrPref) {
            hrPref = v
            filmParams[41] = v
        }
    }

    /**
     * RAW 域高光重建开关（v0.10.115）：写 FilmParams[45]。
     * darktable highlights 模块 "inpaint opposed"（该模块对 Bayer RAW 的默认模式）：
     * 去马赛克**前**在 3×3 photosite 块上对被裁剪通道做"对立通道"外推，再用紧邻
     * 裁剪区的未裁剪像素统计全局色度校正。补 [41] hr_wavelet 够不到的一环 ——
     * 后者在 film 域修影调，恢复不了 RAW 饱和时丢掉的通道信息。
     * 0 = 关（A/B 通路，行为同 exp12）。
     */
    private fun applyHrRawPref() {
        val v = if (appPrefs.getBoolean("hr_raw", true)) HR_RAW else 0.0
        if (v != hrRawPref) {
            hrRawPref = v
            filmParams[45] = v
        }
    }

    /**
     * RCD 色差段回归原版开关（v0.10.117）：写 FilmParams[46]。
     * 1 = RawTherapee RCD 原版行为（对角梯度三项 + **无限幅**，边缘感知靠梯度
     * 反比加权）；0 = 旧行为（梯度两项 + clamp 到 4 对角色差范围）。
     * 真机实测根因：clamp 的限幅范围取自**跨边界**的 4 对角邻居 ⇒ 红色扩散进
     * 白瓷与过曝区（杯口白瓷 Cr +4.3 → 边界 +20~26）。
     * 0 = A/B 通路（复现旧色块用）。
     */
    private fun applyRcdCdFixPref() {
        val v = if (appPrefs.getBoolean("rcd_cdfix", true)) RCD_CDFIX else 0.0
        if (v != rcdCdFixPref) {
            rcdCdFixPref = v
            filmParams[46] = v
        }
    }

    /**
     * 逆光边缘紫边抑制开关（v0.10.134）：写 FilmParams[47]。
     * RawTherapee Defringe 同形态（Lab 域"色度相对局部基线偏离"检测 + Lab a
     * 通道色相门 + 低偏离邻居加权替换，L 零损失），作用在 P4 之后、D-1 之前。
     * 对症 2026-10-04 样片定量：品红伪影 56~66% 贴边，P4/D-1 保边判据恰好
     * 保护它，整链近零压制。0 = 关（A/B 通路）。
     */
    private fun applyDefringePref() {
        val v = if (appPrefs.getBoolean("defringe", true)) DEFRINGE else 0.0
        if (v != defringePref) {
            defringePref = v
            filmParams[47] = v
        }
    }

    /**
     * 高光重建·G 通道重建开关（v0.10.169）：写 FilmParams[49]。
     * kFsHlApply（RAW 域高光重建的 apply 支）里"自创机制"两支的总开关 ——
     * ① 普通 G 的三级池化比重建（`else if (v >= uHlClip && c == 1)`）；
     * ② allSat 三通道同步改写。与 PhotonCamera 的 OpposedGL 逐行对照后，这两支
     * 贡献了"过曝边缘品红"B/G 偏差的 71%（离线逐位仿真：G 池化比 +0.0384、
     * allSat 改写 +0.0255；被强制 0 的 chroma 反向抵消 −0.0218）。
     * 0 = 关 ⇒ shader 内 `if (uGRecon > 0.5)` 整段跳过，apply 支只剩分支① 的
     * opposed 外推（= PhotonCamera OpposedGL 口径；离线高光区 B/G 1.0856 → 1.0217、
     * R/G 0.9773 → 1.0051）。
     * ⚠ 默认 1.0（**不改变默认画质**）：allSat 在基准片占 G 饱和像素 44.15%，
     * 台灯类场景可能退化 ⇒ 仅供真机同帧 A/B，改默认前必须真机数据说话。
     */
    private fun applyHlGReconPref() {
        val v = if (appPrefs.getBoolean("hl_grecon", true)) HL_GRECON else 0.0
        if (v != hlGreconPref) {
            hlGreconPref = v
            filmParams[49] = v
        }
    }

    /**
     * 镜头横向色差校正开关（v0.10.170）：写 FilmParams[50]。
     * 作用在 D3（RCD 去马赛克后、passCcm 之前）的相机 RGB 域 —— 色差是纯几何
     * 位移，此域通道尚未被 CCM 混合；只重采样 R/B，G 逐位不动（G 为参考通道）。
     * 自动估计（无镜头配置文件）：1/8 box 降采样 → readPixels → CPU 逐环带一维
     * 径向 Lucas-Kanade，解 d·r̂ = k·r 的过原点最小二乘。
     * 0 = 关（A/B 通路，不跑任何 pass）。日志字段 caK / caShiftR / caShiftB /
     * caApplied 给出实际估计与是否动作。
     * ⚠ 默认 1.0 的依据：双重判据（角部位移 ≥ 0.5px **且** 内外半区拟合一致）
     * 保证对**无横向色差**的场景零动作；对 2026-10-05 逆光基准样片实测原始估计
     * 仅 0.13/0.41px 且内外半区不相容 ⇒ 判据全部否决、模块不动作
     * （详见 /tmp/cacorr/REPORT.md）。
     */
    private fun applyCaCorrPref() {
        val v = if (appPrefs.getBoolean("ca_corr", true)) CA_CORR else 0.0
        if (v != caCorrPref) {
            caCorrPref = v
            filmParams[50] = v
        }
    }

    /**
     * 强边缘色度门开关（v0.10.174）：prefs `chroma_gate`（默认开）写 FilmParams[51]。
     * 关（false）⇒ [51]=0 ⇒ P4/D-1 的门整段不执行，输出逐位回到 v0.10.172。
     * 真机 A/B 判据：同场景开/关各一张，比 diag.stats 的强边缘紫边与日志字段
     * cgK/cgCap/cgScope/cgMean/cgP95/cgCov（**门控覆盖率**：健康区间 cgMean ≤ 0.15、
     * cgCov ≤ 0.05；v0.10.173 的旧口径下这两个数是 100%）。若真机仍见色块，
     * 关掉本开关即逐位回退（已验证），无需改代码。
     */
    private fun applyChromaGatePref() {
        val v = if (appPrefs.getBoolean("chroma_gate", true)) CHROMA_GATE else 0.0
        if (v != chromaGatePref) {
            chromaGatePref = v
            filmParams[51] = v
        }
    }

    /**
     * PC 降噪结构（ESD3D2，v0.10.175 引擎 / v0.10.176 设置页入口）：prefs `pc3d`
     * （0 关 / 1 替换档 / 2 叠加档）写 FilmParams[54]，prefs `pc3d_msize`
     * （0 自动 / 7 / 11 / 15 / 21）写 FilmParams[55]。
     * ⚠ 默认两者都是 0 = **关 + 自动** ⇒ 与 v0.10.174 逐位相同，不改变默认画质；
     * 本通路存在的唯一目的是真机同场景 A/B。改默认前必须真机数据说话。
     * 越界兜底：`pc3d` 不在 0..2、`pc3d_msize` 不是 0 或 7..21 的奇数 ⇒ 归 0
     * （防手改 prefs / 旧值把非法核尺寸喂给引擎）。
     * 真机判据：日志 pc3dOn=1 且 pc3dM∈[7,21]、pc3dSc=1..4 ⇒ 生效；pc3dOn=0 而
     * [54]>0.5 ⇒ shader 编译/链接或纹理分配失败（pc3d.compile_fail）；
     * pc3dMs 与 dnMs 同量级 ⇒ 成本正常，pc3dMs ≫ dnMs ⇒ 过强（把核尺寸调小）。
     */
    private fun applyPc3dPref() {
        val v = appPrefs.getInt("pc3d", 0).let { if (it in 0..2) it else 0 }.toDouble()
        if (v != pc3dPref) {
            pc3dPref = v
            filmParams[54] = v
        }
        val m = appPrefs.getInt("pc3d_msize", 0)
            .let { if (it == 0 || (it in 7..21 && it % 2 == 1)) it else 0 }
            .toDouble()
        if (m != pc3dMsizePref) {
            pc3dMsizePref = m
            filmParams[55] = m
        }
    }

    /**
     * 白点高光感知开关（v0.10.27）：写 FilmParams[42]。
     * auto_tone 开时，对数域统计的 P99.9 驱动白点上探（≤+1.5EV、物理钳制不变）+
     * darktable contrast 参数补偿中间调回退，治"高光太白压不住"；
     * 0 = A/B 通路（v0.10.18 行为：P99 判据、恒 2.438 下限）。
     */
    private fun applyWhiteHlPref() {
        val v = if (!toneStandard() && appPrefs.getBoolean("white_hl", true)) WHITE_HL else 0.0
        if (v != whiteHlPref) {
            whiteHlPref = v
            filmParams[42] = v
        }
    }

    /**
     * 白点下限边距（v0.10.111 标定旋钮）：prefs `white_floor`（EV，0..1.5），写 FilmParams[43]。
     *
     * 旧行为（0）= filmic 白点下限恒为 RAW 饱和数据白点 2.438；新行为 = 下限改为
     * `min(2.438, w99 + 边距)` —— 场景高光远低于饱和点时白点跟着降，高光才进肩部被压缩。
     * 治的是"密集高光点变白斑"（2026-10-03 真机红杯：w99 仅 1.03EV 而白点 2.53EV，
     * 比场景高光高 1.5EV ⇒ 1% 以上的高光全落在肩部以下不被压缩）。
     * 中间调对比由引擎已有的 cp 补偿兜住。
     */
    private fun applyWhiteFloorPref() {
        val v = if (toneStandard()) 0.0
        else appPrefs.getFloat("white_floor", 0.4f).toDouble().coerceIn(0.0, 1.5)
        if (v != whiteFloorPref) {
            whiteFloorPref = v
            filmParams[43] = v
        }
    }

    /**
     * 白点上探幅度上限（v0.10.111 标定旋钮）：prefs `white_probe`（EV，0..1.5），
     * 写 FilmParams[44]。旧硬编码 1.5 —— 真机实测每次都满额（红杯 w99=1.03 → 白点
     * 2.53，比场景高光高 1.5EV ⇒ 1% 以上的高光全在肩部以下不被压缩 ⇒ 白斑）。
     * 降到 0.5 保留"P99 看不见的近饱和高光"上探能力（白壶案）而不再推白整段高光。
     */
    private fun applyWhiteProbePref() {
        val v = if (toneStandard()) 1.5
        else appPrefs.getFloat("white_probe", 0.5f).toDouble().coerceIn(0.0, 1.5)
        if (v != whiteProbePref) {
            whiteProbePref = v
            filmParams[44] = v
        }
    }

    /**
     * 饱和补偿（v0.10.21 标定旋钮）：prefs "sat_comp" 0..0.30，写 FilmParams[20]/[21]
     * （阴影/中间调分区饱和 = 1+v；[22] 高光恒 1.0 防过饱和）。原生 gamut 色度 min
     * 的低饱和由风格层补偿——对标 darktable color balance / PC 的 LUT 职责。
     */
    private fun applySatCompPref() {
        val v = appPrefs.getFloat("sat_comp", 0f).toDouble().coerceIn(0.0, 0.30)
        if (v != satCompPref) {
            satCompPref = v
            filmParams[20] = 1.0 + v
            filmParams[21] = 1.0 + v
        }
    }

    /** 预览实时直方图（M3）：渲染线程取值（阻塞 ≤500ms），null = 尚无可用帧。 */
    fun pollHistogram(): IntArray? = render.pollHistogram()

    // 预设自带的白平衡基线（引擎补偿在其上相乘）
    private var presetWbBase = doubleArrayOf(1.0, 1.0, 1.0)

    /**
     * 手动白平衡 = 引擎侧补偿（预览/成片同链路，行为与 HAL 解耦）：
     * 补偿 = wbGains(目标K) / wbGains(当前自动估计K)，目标=估计时恒为 1（与自动一致）。
     */
    fun updateWbCompensation(targetK: Int, estK: Int) {
        if (estK <= 0) return
        val g = wbGains(targetK.coerceIn(2000, 8000))
        val r = wbGains(estK.coerceIn(2000, 8000))
        setWbCompensation(g[0] / r[0], g[2] / r[2])
    }

    fun setWbCompensation(r: Float, b: Float) {
        filmParams[1] = (presetWbBase[0] * r.coerceIn(0.25f, 4f))
        filmParams[2] = presetWbBase[1]
        filmParams[3] = (presetWbBase[2] * b.coerceIn(0.25f, 4f))
    }

    fun clearWbCompensation() {
        filmParams[1] = presetWbBase[0]
        filmParams[2] = presetWbBase[1]
        filmParams[3] = presetWbBase[2]
    }

    /** 真机二分定位开关：true 时 pass1 用梯度填充替代相机采样。 */
    fun setFillTest(v: Boolean) {
        fillTest = v
    }

    fun updateOrientationStep(step: Int) {
        val s = ((step % 4) + 4) % 4
        if (s == orientationStep) return // OrientationEventListener 高频回调：只记变化
        orientationStep = s
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "orientation.set",
            kv("step" to orientationStep),
        )
    }

    fun orientationStep(): Int = orientationStep

    // ── M4：内置 3D LUT（display 域；引擎接在输出 OETF 之后，四面体插值）──────
    /** 当前选中的 LUT（null = 无）。会话重建后由 start() 自动重传纹理。 */
    @Volatile
    private var currentLut: com.filmkit.app.lut.CubeLut? = null

    /** 选择内置 LUT：null = 关闭（A/B 通路）。解析由调用方在 IO 线程完成，这里只下发。 */
    fun setLut(lut: com.filmkit.app.lut.CubeLut?) {
        currentLut = lut
        render.setLut(lut)
    }

    fun start(surface: Surface) {
        if (started) return  // surfaceCreated 可能多次回调，防重入
        if (!render.start(surface)) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "render.start_failed", "{}",
            )
            return
        }
        // 静拍 YUV 解码矩阵（2026-09-20 实拍 A/B 已定案 601 正确，设置入口已删，恒 601）。
        // 防御性包裹：JNI/任何异常不得阻断相机启动（started=false = 全黑+焦段丢失）
        runCatching {
            EngineBridge.nativeEngineSetYuvMatrix(0)
        }
        // 诊断统计（v0.10.166）：prefs `diag_stats`（**默认开**）= 成片链各阶段的色度
        // 统计量在 GPU 内归约后只读回 64 字节，逐阶段写一条 diag.stats 日志事件
        //（sumR/sumG/sumB/n/nan/rgByBand + 开关快照），用于定位"全图显示域偏红"
        // 产生在哪一步。每次进相机界面读一次 ⇒ 改 prefs 后重进相机界面生效。
        runCatching {
            EngineBridge.nativeEngineSetDiagStats(
                context.getSharedPreferences("filmkit", 0).getBoolean("diag_stats", true),
            )
        }
        // M4：切镜头/重进相机后 EGL 上下文已重建（引擎纹理被释放），重传当前 3D LUT
        currentLut?.let { render.setLut(it) }
        orientationListener.enable() // 设备方向 → orientationStep（成片旋转 + 对焦区域映射）
        // 平放检测（TYPE_GRAVITY 可能缺失，取不到就退化为"永不平放"）
        (context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager)
            ?.let { sm ->
                sm.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)?.let {
                    sm.registerListener(gravityListener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
                }
                // 曝光决策专项：陀螺仪（motion metering）；缺失时规划器自动退化为无运动限制
                sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)?.let {
                    sm.registerListener(gyroListener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
                }
            }
        started = true
        openCamera()
    }

    fun capture() {
        val s = session ?: return
        // 快门瞬间快照方向与画幅（见 stillRot/stillAspect 注释）：渲染/保存链路必须用
        // 拍照那一刻的姿态，否则用户"拍完就转回竖屏"会把横拍裁成竖幅。
        stillRot = orientationStep
        // 画幅比例按**快门瞬间的姿态**定向 —— 不依赖 Compose 的异步更新：CameraScreen 的
        // `LaunchedEffect(captureAspectValue)` 要等下一次组合才执行，用户横拿后立刻按快门
        // 会拿到上一姿态的值。Compose 传进来的是"当前姿态"比例，这里只做转置校正
        // （竖 → <1，横 → >1），两种滞后情形都自愈。
        val ca = captureAspect
        stillAspect = when {
            ca <= 0f -> 0f
            stillRot % 2 == 0 -> if (ca > 1f) 1f / ca else ca
            else -> if (ca < 1f) 1f / ca else ca
        }
        if (capturing) {
            // v0.10.107：原来这里是**静默 return** —— 真机实测（2026-10-02 日志）
            // MFNR 渲染 ~4.3s，用户在渲染期按快门"没反应"却无任何记录，表现为
            // "连按两下只有一张"。记一条便于诊断（与下面的 inFlight 分支对齐）。
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "camera", "capture.busy",
                kv("reason" to "capturing", "phase" to capturePhase),
            )
            return
        }
        if (rendersInFlight.get() >= maxRendersInFlight) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "camera", "capture.busy",
                kv("inFlight" to rendersInFlight.get()),
            )
            return
        }
        capturing = true
        // 预览降载（v0.10.93 方案 A）：快门即降（0.5×），合成结束回升。实验判读
        //（2026-10-01 四组开关）：拍照期掉帧主因 = MFNR 链路 GPU 负载 + 预览自身
        // 争抢份额，降噪已排除。同时预热拍照线程（单帧 RAW/YUV 也迁入独立线程了，
        // burst 与单帧都需要）。
        armPreviewScaleGuard()
        if (context.getSharedPreferences("filmkit", 0)
                .getBoolean("still_thread", false)
        ) {
            StillRenderClient.startAsync()
        }
        if (shutterSound) {
            shutterSoundPlayer.play(android.media.MediaActionSound.SHUTTER_CLICK)
        }
        try {
            val b = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(stillReader!!.surface)
                applyControls(this, distort = true) // 静拍继承 M 档控制（WB/EV/OIS/变焦/对焦区域）
            }
            if (rawMode) {
                // MFNR（v0.9.0）：三档 prefs mfnr（0 关 / 1 自动暗光 / 2 强制），
                // 触发时 4 帧同参 captureBurst（HDR+：恒曝光使对齐鲁棒）。
                val prefs = context.getSharedPreferences("filmkit", 0)
                val mode = prefs.getInt("mfnr", 1)
                val demosaic = prefs.getInt("demosaic_algo", -1)
            .let { if (it in 0..2) it else if (prefs.getBoolean("demosaic_quality", true)) 1 else 0 }
                val trigger = mfnrShouldTrigger(
                    mode, rawMode, demosaic == 1, liveIso, liveExposureNs,
                )
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "camera", "mfnr.trigger",
                    kv(
                        "mode" to mode, "trigger" to trigger,
                        "iso" to liveIso, "sec" to "%.4f".format(liveExposureNs / 1e9f),
                    ),
                )
                // fwrite 缓冲在崩溃时丢内容：拍照入口日志立即落盘（v0.9.1）
                EngineBridge.nativeFlushLogs()
                if (trigger) {
                    startBurst(s, b)
                    return
                }
                // RAW 路径需要 TotalCaptureResult（DngCreator/黑阶/WB/CCM 元数据）：
                // 回调暂存 result，与 reader listener 的 Image 在渲染线程汇合
                capturePhase = 1
                pendingResult = null
                rawImageAtMs = 0L
                s.capture(b.build(), rawCaptureCb, render.looper?.let { android.os.Handler(it) })
            } else {
                capturePhase = 1
                s.capture(b.build(), null, render.looper?.let { android.os.Handler(it) })
            }
        } catch (t: Throwable) {
            capturing = false
            capturePhase = 0
            releasePreviewScaleGuard()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "capture.submit_failed",
                kv("error" to (t.message ?: t.toString())),
            )
        }
    }

    // ── 预览降载（v0.10.93 方案 A）──────────────────────────────────────
    // 快门即 0.5×（像素 1/4，pass1/2/3 全部受益），合成结束立即回升 —— 帧级
    // 切换、无需重建 session。成片链不经预览路径，画质零影响（用户红线）。
    // 15s 兜底只防"完成回调永远不来"把预览糊死到重进相机页；正常路径都会先到，
    // 每次拍照重新武装（连拍时旧兜底被移除，不会中途把新一张升回去）。
    private val previewScaleCapture = 0.5f
    private var previewScaleGuard: Runnable? = null

    private fun armPreviewScaleGuard() {
        render.setPreviewScale(previewScaleCapture)
        previewScaleGuard?.let { r ->
            render.looper?.let { lo -> android.os.Handler(lo).removeCallbacks(r) }
        }
        val r = Runnable { render.setPreviewScale(1f) }
        previewScaleGuard = r
        render.looper?.let { lo -> android.os.Handler(lo).postDelayed(r, 15_000L) }
    }

    private fun releasePreviewScaleGuard() {
        previewScaleGuard?.let { r ->
            render.looper?.let { lo -> android.os.Handler(lo).removeCallbacks(r) }
        }
        previewScaleGuard = null
        render.setPreviewScale(1f)
    }

    // ── MFNR burst 采集（v0.9.0 阶段一）─────────────────────────────────

    private var burstTimeout: Runnable? = null

    // 短参考帧（v0.10.1）：参考帧噪声相对典型帧的倍率 √(AE_sec/secRef)，1.0 = 未压短。
    // 经 submitRawBurst 传引擎：mismatch 的 σ 用参考帧域（×rn），P0/P4 折减用典型帧域。
    private var burstRefNoiseScale = 1.0f
    private val MFNR_REF_MAX_EXPOSURE_NS = 15_000_000L  // 快门锚 15ms（拖影减半）

    // 曝光决策专项护栏：本次 burst 请求的曝光/ISO（供结果回调核对 HAL 是否 clamp）
    @Volatile
    private var expPlanSec = 0L
    @Volatile
    private var expPlanIso = 0

    /** 4 帧同参 captureBurst（恒曝光）。Image/Result 桶按回调序配对——
     *  burst 的 onCaptureCompleted 与提交顺序一致且同 looper 串行。
     *  v0.10.1 短参考帧：帧 0（参考帧 + DNG 底片）压短快门（min(AE, 15ms)），
     *  ISO 等比补偿曝光量守恒（AE_MODE_OFF 手动下发，M 档滑杆同路径）——
     *  单帧运动模糊 ∝ 快门，参考帧清晰则运动 tile 由 mismatch 自动回退到它
     *  （融合结构里参考帧权重恒 1，它糊了谁也救不了）。帧 1-3 保持 AE 曝光
     *  承担降噪量。光线好时 AE ≤15ms，secRef == AE，结构自动退化为等权。 */
    private fun startBurst(s: CameraCaptureSession, b: CaptureRequest.Builder) {
        capturePhase = 1
        burstActive = true
        burstImageCount = 0
        burstResultCount = 0
        burstImages.fill(null)
        burstBuffers.fill(null)
        burstStrides.fill(0)
        burstResults.fill(null)
        burstStartMs = android.os.SystemClock.elapsedRealtime()
        // v0.10.10 门控修正：X300 的 SENSOR_MAX_ANALOG_SENSITIVITY 报 11467（无
        // 参考价值），SENSOR_INFO_SENSITIVITY_RANGE.upper=3200 才是真实 ISO 顶。
        // 压短条件 = ① liveIso **未触 ISO 顶**（触顶=暗光，压短代价失控）且
        // ② isoRef=1.5×liveIso 不被 clamp（否则守恒破坏 → rn<1 的"慢参考"怪相）。
        // v0.10.4 三重约束：
        // ① 暗光门控：liveIso 超过**模拟增益上限**（SENSOR_MAX_ANALOG_SENSITIVITY）
        //   时不压短 —— 数字增益区压快门 = 光子更少 + 纯数字放大，噪声双重恶化
        //   （实测 ISO 11467 时参考帧 σ=0.104 = alt 的 1.63 倍，运动区域回退后
        //   "噪点恐怖"）。暗光拖影由 mismatch 压重影，压短留给帧数档位（8 帧的
        //   噪声预算才付得起）。
        // ② 压缩比上限 1.5×：secRef = min(sec, max(15ms, sec×2/3)) —— 长曝光
        //   场景的压缩比失控（40ms→15ms = 2.67×）让 rn 冲到 1.63。
        // ③ isoRef clamp 到 min(range.upper, maxAnalog)：参考帧不进纯数字增益区。
        var isoUpperGate = Int.MAX_VALUE
        curCharacteristics?.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?.let { isoUpperGate = it.upper }

        // ── 曝光决策专项（v0.10.107）：长曝光规划 ────────────────────────
        // 不变式：**曝光 ×N + ISO ÷N ⇒ 输出亮度与高光裁切完全不变，纯赚 SNR**
        // （离线实测收益 = √N，N=8.66 → 2.95×，与物理上限逐位吻合）。
        // 约束三条：ISO 下限 / 快门上限 / 运动预算（细节优先 2.2px，findings §4）。
        // prefs `exp_plan`：0 关 / 1 开（默认开）。**用设置页开关手动做 A/B**，不做自动
        // 交替 —— 真机实测（2026-10-02 日志）MFNR 渲染耗时 ~4.3s，快门在渲染期间被
        // `capturing` 静默吞掉，"连按两次"根本不成立。
        // 曝光上限档（prefs `exp_max_ms`）：0=自动（陀螺仪运动预算，细节优先口径）
        // / 100 / 250 / 500ms = 固定上限，**跳过运动限制**（仍记录 blurPx 供核对）。
        // 为什么要手动档：Filmix 同机用 ISO166+1/4s（250ms）拍出远超我们的暗部纹理，
        // 而"陀螺仪角速度 × 时间"模型未计 OIS（实测实际像面位移仅为机身转动的 0.71）
        // ⇒ 模型过于保守、与实证冲突。实证 > 模型：由用户直接试上限。
        val expPrefs = context.getSharedPreferences("filmkit", 0)
        val expOn = expPrefs.getInt("exp_plan", 1) >= 1
        val expMaxMs = expPrefs.getInt("exp_max_ms", 0)
        val expTargetNs = if (expMaxMs > 0) expMaxMs * 1_000_000L else EXP_TARGET_EXPOSURE_NS
        val expBudgetPx = if (expMaxMs > 0) 1e6 else ExposurePlanner.MOTION_BUDGET_PX
        val gyroMean = gyroMeanRadPerSec()
        val gyroRms = gyroRmsRadPerSec()
        // ── 高光保护（v0.10.110，2026-10-02 用户拍板"从曝光策略上解决"）─────────
        // 曝光的第一目标是"刚好不烧高光"（HDR+ §4），不是"亮度达标"。真机实锤：
        // 预览 w999 = 2.4383 恰为 RAW 饱和数据白点，引擎再乘 2^(base_ev+用户EV) 就把
        // 0.1% 的饱和像素扩成大片裁切（成片 clipB 711/3072 = 23% 的块命中高光裁剪）。
        // 判据必须把**将要施加的提亮**算进去，否则"曝光贴顶 + 后处理提亮"仍会撞白点。
        // prefs `hl_protect`：0 关（A/B 通路）/ 1 开（默认）。
        // v0.10.113：默认**关**（曝光给足）。理由：场景高光（渲染域 3.02）本就超过白点
        // （2.438）0.58EV，"不欠曝"与"高光不爆"物理上不可兼得；而用户诉求是噪声小
        // （欠曝让全图光子 -25%~-45% ⇒ 相对噪声上升 ⇒ 红杯那类中高亮表面冒白斑）。
        // 需要高光保护时可在设置页打开（判据已改用成片域 sw999，比旧版准）。
        val hlOn = expPrefs.getInt("hl_protect", 0) >= 1
        val hlSceneEv = if (hlOn) sceneHighlightEv() else Double.NaN
        val hlCompEv = baseEvPref + (evSteps * evStep).toDouble()
        var expPlan: ExposurePlanner.Plan? = null
        var capIso = liveIso
        var capSec = liveExposureNs
        if (expOn && liveIso > 0 && liveExposureNs > 0) {
            val secRange = curCharacteristics
                ?.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val isoRange = curCharacteristics
                ?.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val p = ExposurePlanner.plan(
                baseIso = liveIso,
                baseExposureNs = liveExposureNs,
                isoLower = isoRange?.lower ?: 50,
                exposureUpperNs = secRange?.upper ?: 1_000_000_000L,
                targetExposureNs = expTargetNs,
                motionRadPerSec = gyroMean,
                motionBudgetPx = expBudgetPx,
                // 高光保护：提亮后高光必须留余量（NaN = 无统计 ⇒ 不介入，与历史一致）
                sceneHighlightEv = hlSceneEv,
                exposureCompEv = hlCompEv,
            )
            expPlan = p
            capIso = p.iso
            capSec = p.exposureNs
            // 高光保护欠曝量（EV）：由 processRawBurst 加到成片 EV 上补偿（见
            // filmParamsForStill）—— 只欠曝不补偿 = 把"高光爆"换成"画面暗"。
            burstHlUnderEv = if (p.n < 1.0) -p.deltaEv else 0.0
        }
        // 长曝光生效时**取消参考帧压短**：压短的初衷是"参考帧更锐以防拖影"，而长
        // 曝光下所有帧 SNR 同时提高，压短反而让参考帧噪声变差（对齐/mismatch 判据
        // 一起退化）；拖影风险已由运动预算统一约束（Night Sight：参考帧取最锐帧）。
        val longExposureOn = expPlan != null && expPlan.n > 1.0001

        val isoTouchesTop = capIso >= isoUpperGate        // 触顶 = 暗光
        val isoHeadroomOk = capIso * 3 / 2 <= isoUpperGate  // isoRef 不会被 clamp
        val canShorten = !longExposureOn && capIso > 0 && capSec > 0 &&
            !isoTouchesTop && isoHeadroomOk
        val secRef = if (!canShorten) {
            capSec
        } else {
            minOf(
                capSec,
                maxOf(MFNR_REF_MAX_EXPOSURE_NS, capSec * 2 / 3),
            )
        }
        // v0.10.9：**曝光量守恒优先** —— iso 被 clamp（range/模拟上限）后必须反算
        // 快门，否则 ref 欠曝（实测 ISO 2544/30ms → 想要 3816/20ms 但 ISO clamp
        // 到 3200 后仍是 20ms = 曝光量 64000 vs alt 的 76680，ref 系统性欠 0.26EV，
        // 给残差判据引入 18% 的全图亮度差）。
        var isoRef = if (capIso > 0 && secRef > 0) {
            Math.round(capIso.toDouble() * capSec / secRef).toInt()
                .coerceAtMost(isoUpperGate)
        } else {
            capIso
        }
        isoRef = isoRef.coerceAtLeast(1)
        // 守恒反算（canShorten 已保证 isoRef 不被 clamp，此处精确守恒）
        val secRefFinal = if (capIso > 0 && capSec > 0 && isoRef > 0) {
            Math.round(capIso.toDouble() * capSec / isoRef)
        } else {
            secRef
        }
        burstRefNoiseScale = if (capIso > 0 && capSec > 0 && secRefFinal > 0) {
            kotlin.math.sqrt(capSec.toDouble() / secRefFinal).toFloat()
        } else {
            1.0f
        }
        val refReq = if (secRefFinal < capSec && capIso > 0 && secRefFinal > 0) {
            camera!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(stillReader!!.surface)
                applyControls(this, distort = true)
                // AE_OFF + 手动传感器参数（与 M 档手动滑杆同一路径，非 AE 补偿通道）
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, isoRef)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, secRefFinal)
            }.build()
        } else {
            null
        }
        // v0.10.35（2026-09-29 真机取证）：**alt 帧同样固定 AE_OFF + 预览收敛的
        // ISO/曝光** —— 原实现帧 1-3 直接复用预览的 repeating 请求（AE AUTO），
        // HAL 在拍摄时重新收敛曝光：70MM 实测预期 1070 → 实际 1179（+10%），
        // ref/alt 曝光量不守恒（42.8 vs 47.2）→ 对齐 SAD 残差被亮度差淹没
        // （confMed=0 → 位移场全零 → neff=1.45，MFNR 整体失效）。主摄/15MM 因
        // AE 恰好稳定而侥幸守恒（±0.7%）。
        val altReq = if (capIso > 0 && capSec > 0) {
            expPlanSec = capSec
            expPlanIso = capIso
            camera!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(stillReader!!.surface)
                applyControls(this, distort = true)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                // 长曝光规划后的 ISO/曝光（未启用时 == 预览收敛值，行为与历史一致）
                set(CaptureRequest.SENSOR_SENSITIVITY, capIso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, capSec)
            }.build()
        } else {
            null
        }
        val reqs = List(MFNR_FRAMES) { i ->
            if (i == 0) (refReq ?: altReq ?: b.build()) else (altReq ?: b.build())
        }
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "mfnr.burst_submit",
            kv(
                "frames" to MFNR_FRAMES,
                "refIso" to isoRef, "refSec" to "%.4f".format(secRefFinal / 1e9f),
                "altIso" to capIso, "altSec" to "%.4f".format(capSec / 1e9f),
                "rn" to "%.3f".format(burstRefNoiseScale),
                // 曝光决策专项：规划结果 + 运动判据（expN=1 表示未启用/未生效）
                "expN" to "%.2f".format(expPlan?.n ?: 1.0),
                "expWhy" to (expPlan?.limitedBy ?: "off"),
                "expDv" to "%.2f".format(expPlan?.deltaEv ?: 0.0),
                "gyroM" to "%.4f".format(gyroMean),
                "gyroR" to "%.4f".format(gyroRms),
                "mtnLim" to "%.3f".format((expPlan?.motionLimitNs ?: 0L) / 1e9f),
                "blurPx" to "%.2f".format(expPlan?.blurPx ?: 0.0),
                "expCap" to (if (expMaxMs > 0) "${expMaxMs}ms" else "auto"),
                "baseIso" to liveIso, "baseSec" to "%.4f".format(liveExposureNs / 1e9f),
                // 高光保护（v0.10.110）：hlEv = 场景高光分位（提亮之前，log2 相对中灰；
                // -99 = 无统计/关闭）；hlCmp = 本次将要施加的提亮 EV；限流原因见 expWhy
                // （"highlight" = 为留高光余量欠曝）
                "hlOn" to hlOn,
                "hlEv" to "%.3f".format(if (hlSceneEv.isFinite()) hlSceneEv else -99.0),
                "hlCmp" to "%.3f".format(hlCompEv),
                // 实际欠曝量（EV，0 = 未触发）；成片 EV 会加上同一个量做补偿
                "hlUnder" to "%.3f".format(if (expPlan != null && expPlan.n < 1.0) -expPlan.deltaEv else 0.0),
            ),
        )
        EngineBridge.nativeFlushLogs()
        s.captureBurst(reqs, rawCaptureCb, render.looper?.let { android.os.Handler(it) })
        val timeout = Runnable { onBurstTimeout() }
        burstTimeout = timeout
        render.looper?.let { lo ->
            android.os.Handler(lo).postDelayed(timeout, MFNR_TIMEOUT_MS)
        }
    }

    /** 帧 1-3：RAW plane 拷贝到 direct buffer 后立即 close（HAL 槽位快速释放）。
     *  rowStride 可能含对齐填充，整 capacity 拷贝（多拷 padding 无害），引擎侧按
     *  rowStride/2 跳行上传。 */
    private fun copyRawAndClose(img: Image, slot: Int) {
        try {
            val p = img.planes[0]
            val buf = java.nio.ByteBuffer.allocateDirect(p.buffer.capacity())
            val src = p.buffer.duplicate()
            src.position(0)
            buf.put(src)
            buf.position(0)
            burstBuffers[slot] = buf
            burstStrides[slot] = p.rowStride / 2
        } finally {
            runCatching { img.close() }
        }
    }

    private fun onBurstTimeout() {
        if (!burstActive) return
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_ERROR, "camera", "mfnr.timeout",
            kv(
                "images" to burstImageCount, "results" to burstResultCount,
                "waitMs" to (android.os.SystemClock.elapsedRealtime() - burstStartMs),
            ),
        )
        releaseBurst()
        capturing = false
        capturePhase = 0
        releasePreviewScaleGuard()
    }

    /** 关闭已收帧并复位桶（超时路径；正常路径由 processRawBurst/RenderClient 收尾） */
    private fun releaseBurst() {
        burstActive = false
        burstImages.forEach { runCatching { it?.close() } }
        burstImages.fill(null)
        burstBuffers.fill(null)
        burstStrides.fill(0)
        burstResults.fill(null)
        burstImageCount = 0
        burstResultCount = 0
        burstTimeout?.let { r ->
            render.looper?.let { lo -> android.os.Handler(lo).removeCallbacks(r) }
        }
        burstTimeout = null
    }

    /** Image/Result 双桶汇合：4+4 齐 → processRawBurst（同渲染线程串行，无锁） */
    private fun tryProcessBurst() {
        if (!burstActive) return
        if (burstImageCount < MFNR_FRAMES || burstResultCount < MFNR_FRAMES) return
        val imgs = burstImages.mapNotNull { it }
        val results = burstResults.mapNotNull { it }
        burstActive = false  // 摘标志：超时兜底不再触发；数组在 onDone 清引用
        capturePhase = 2
        processRawBurst(imgs, results)
    }

    fun stop() {
        started = false
        pendingOpenId = null
        orientationListener.disable()
        (context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager)
            ?.let { sm ->
                sm.unregisterListener(gravityListener)
                sm.unregisterListener(gyroListener)
            }
        render.post {
            try { session?.stopRepeating() } catch (_: Throwable) {}
            camera?.close()
            camera = null
            session = null
            stillReader?.close()
            stillReader = null
            rawReader = null
            releaseBurst()  // MFNR burst 在途帧：close 并复位桶（stop 竞态兜底）
            pendingRawImage = null
            pendingResult = null
            capturing = false
            capturePhase = 0
            releasePreviewScaleGuard()  // 退出相机页：预览降载必须复位
            surfaceTexture?.release()
            surfaceTexture = null
            previewSurface?.release()
            previewSurface = null
        }
        render.stop()
        // 拍照线程（StillRenderClient）**故意不在这里销毁**（v0.10.85）：它可能
        // 正跑着 1.6~3.4s 的合成，quitSafely/delete 会杀掉正在跑 GL 的线程
        // （v0.10.83 就是这样丢照片 + 闪退的）。线程与引擎实例进程级常驻，
        // 在飞的合成照常跑完并落盘 —— 相机页退出不该以照片为代价。
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(forcedId: String? = null) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (render.looper == null) return // 渲染线程未启动（预览未开始），等 start(surface) 再开
        // 镜头发现：公开 ID + 隐藏物理镜头暴力探测（§0-D6，参考 PhotonCamera）
        // HAL 未就绪时可能漏检：不足 2 镜头则强制重探测
        if (backLenses.size < 2) {
            backLenses = LensDiscovery.discoverBackLenses(
                cm, context.getSharedPreferences("filmkit", 0),
            )
        }
        val lens = forcedId?.let { id -> backLenses.firstOrNull { it.id == id && it.enabled } }
            ?: enabledLenses().minByOrNull { kotlin.math.abs(it.equivMm - 24f) } // 主摄 = 等效最接近 24mm
        if (lens == null) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "camera.not_found", "{}",
            )
            return
        }
        var targetId: String = lens.id
        val ch = cm.getCameraCharacteristics(targetId)
        curCharacteristics = ch
        currentLensId = targetId
        activeLensIdx = backLenses.indexOf(lens).coerceAtLeast(0)
        // 手动对焦：部分 HAL（X300）不上报屈光度上限，给 10D 试值（≈10cm）
        minFocusDiopter = (ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f)
            .coerceAtLeast(0f)
        if (minFocusDiopter <= 0f) minFocusDiopter = 10f
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { evRange = it }
        ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let { evStep = it.toFloat().coerceAtLeast(0.1f) }
        ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { activeArray = it }
        ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.let {
            oisSupported = it.contains(1) // 1 = ON
        }
        // 畸变校正能力（v0.10.109-exp7 探测）：仅当 HAL 声明支持 HIGH_QUALITY 时才下发
        // 请求（未声明的模式下发会被 HAL 拒绝/忽略，甚至导致请求构建失败）
        distCorrectionModes = if (android.os.Build.VERSION.SDK_INT >= 28) {
            ch.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
        } else {
            null
        }
        maxZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        colorFilterArrangement = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        isoRangeUpper = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?.upper ?: 3200
        // 一次性能力探针（v0.10.11/12）：**遍历全部 cameraId** —— 用户实测
        // PhotonCamera 滑杆到 19200，其 isoRange 取"当前活动相机"特性，疑似
        // 物理相机（非逻辑 id0）报的范围不同（我们 id0 读到 3200）。
        try {
            val mgr = context.getSystemService(android.content.Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            for (cid in mgr.cameraIdList) {
                val c = mgr.getCameraCharacteristics(cid)
                val r = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val m = c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
                val b = c.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "camera", "camera.iso_caps_id",
                    com.filmkit.app.kv(
                        "id" to cid,
                        "range" to (if (r != null) "${r.lower}..${r.upper}" else "none"),
                        "maxAnalog" to (m ?: -1),
                        "boost" to (if (b != null) "${b.lower}..${b.upper}" else "none"),
                    ),
                )
                // 几何/畸变能力探测（v0.10.109-exp7）：一次打开相机即可判定"超广畸变能否
                // 交给 HAL（RAW 流是否可能被几何校正）"还是必须引擎 GPU 自校正。
                // AOSP 原文（CaptureRequest#DISTORTION_CORRECTION_MODE，已取证）：
                //   "The correction only applies to processed outputs such as YUV, Y8,
                //    JPEG, or DEPTH16; it is **not applied to any RAW output**."
                //   "This control will be on by default on devices that support this control."
                // ⇒ ① 我们的 RAW 链**永远拿不到** HAL 校正（探测只为确认，不为指望）；
                //   ② YUV 链若本机支持，本来就已经在校正（默认 ON）。
                // distModes 空 = HAL 未声明畸变校正能力；lensDistortion 有值 = HAL 带标定
                // 系数（可直接喂我们自己的 GPU 反向映射，省掉自拍棋盘格标定）。
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "camera", "camera.geom_caps_id",
                        com.filmkit.app.kv(
                            "id" to cid,
                            "distModes" to (
                                c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                                    ?.joinToString(",") ?: "none"
                                ),
                            "lensDistortion" to (
                                c.get(CameraCharacteristics.LENS_DISTORTION)
                                    ?.joinToString(",") { "%.5f".format(it) } ?: "none"
                                ),
                            "radial" to (
                                c.get(CameraCharacteristics.LENS_RADIAL_DISTORTION)
                                    ?.joinToString(",") { "%.5f".format(it) } ?: "none"
                                ),
                            "intrinsic" to (
                                c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                                    ?.joinToString(",") { "%.2f".format(it) } ?: "none"
                                ),
                            "active" to (
                                c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                                    ?.let { "${it.width()}x${it.height()}" } ?: "none"
                                ),
                            // 校正前有效阵列 vs 有效阵列的差值 = **HAL 几何校正的裁边量**
                            // （AOSP：校正 OFF 时坐标系用 preCorrectionActiveArray，ON 时用
                            // activeArray）。两者相同 ⇒ HAL 校正不损失视场。
                            "preCorr" to (
                                c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                                    ?.let { "${it.width()}x${it.height()}" } ?: "none"
                                ),
                        ),
                    )
                }
            }
        } catch (_: Throwable) {
        }
        ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.let {
            baseFocalMm = it
        }
        ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.let {
            apertureF = it
        }
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            .sortedByDescending { it.width.toLong() * it.height }

        // sizes 为降序：取不超过上限的最大 4:3 尺寸。
        // v0.6.37：上限 1440 → **2160**（真机反馈"预览分辨率很低"）。低在哪可查：
        // 引擎的渲染目标是预览区尺寸（日志 engine.start 的 w/h ≈ 1216×1621），而
        // 1440×1080 的流旋转后只有 1080×1440 —— **比渲染目标还小 1.13 倍**，预览
        // 一直在放大显示，所以发糊；1920×1440 / 2160×1620 才留出降采样余量。
        // 两级回退保留：HAL 给不出大尺寸时自动降级，实际选中的尺寸记在日志
        // camera.session_ready.preview，可据此核对。
        previewSize = sizes.filter { it.width.toLong() * 3 == it.height.toLong() * 4 }
            .firstOrNull { it.width <= 2160 }
            ?: sizes.filter { it.width.toLong() * 3 == it.height.toLong() * 4 }
                .firstOrNull { it.width <= 1440 }
            ?: sizes.firstOrNull { it.width.toLong() * it.height <= 1920L * 1080 }
            ?: sizes.last()

        // 静拍流：M 档（raw_mode，快捷开关）+ 镜头 RAW 能力决定走 RAW_SENSOR 或 YUV_420_888；
        // DNG 开关只控制是否额外保留底片文件（与采集格式解耦）。
        // 尺寸一致且格式未变时复用现有 reader
        val wantRaw = context.getSharedPreferences("filmkit", 0)
            .getBoolean("raw_mode", false) && lens.rawSupported
        val rawSizes = if (lens.rawSupported) {
            map.getOutputSizes(ImageFormat.RAW_SENSOR)
                .sortedByDescending { it.width.toLong() * it.height }
        } else emptyList()
        rawMode = wantRaw && rawSizes.isNotEmpty()
        if (context.getSharedPreferences("filmkit", 0).getBoolean("raw_mode", false) && rawSizes.isEmpty()) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "camera", "raw.fallback_yuv",
                kv("lens" to lens.id, "reason" to "raw_unsupported"),
            )
        }
        val stillFormat = if (rawMode) ImageFormat.RAW_SENSOR else ImageFormat.YUV_420_888
        stillSize = if (rawMode) rawSizes.first()
        else map.getOutputSizes(ImageFormat.YUV_420_888)
            .sortedByDescending { it.width.toLong() * it.height }.first()
        val oldReader = stillReader
        stillReader = if (oldReader != null &&
            oldReader.width == stillSize.width && oldReader.height == stillSize.height &&
            rawMode == (oldReader === rawReader)
        ) {
            oldReader
        } else {
            oldReader?.close()
            rawReader = null
            pendingRawImage?.close()
            pendingRawImage = null
            pendingResult = null
            ImageReader.newInstance(
                // maxImages=4（v0.9.1）：MFNR 4 帧循环 + 回调内拷贝即 close 释放
                // 槽位。v0.9.0 用 5 且全程持有 4 张 Image —— HAL 一次预配 125MB
                // graphic buffer，是本次闪退头号嫌疑，回退保守值。
                stillSize.width, stillSize.height, stillFormat, 4,
            ).apply {
                setOnImageAvailableListener({ r ->
                    // burst 期间 acquireLatestImage 的"丢旧帧"语义会吃掉 4 帧中的
                    // 3 帧 —— 必须按队列逐帧 acquireNextImage
                    val img =
                        if (burstActive) r.acquireNextImage() else r.acquireLatestImage()
                        ?: return@setOnImageAvailableListener
                    if (img.format == ImageFormat.RAW_SENSOR) {
                        if (burstActive) {
                            if (burstImageCount < MFNR_FRAMES) {
                                val idx = burstImageCount++
                                if (idx == 0) {
                                    // 参考帧保留 Image（DngCreator 只吃 Image）
                                    burstImages[0] = img
                                    burstStrides[0] = img.planes[0].rowStride / 2
                                } else {
                                    copyRawAndClose(img, idx)
                                }
                                tryProcessBurst()
                            } else {
                                runCatching { img.close() }  // 溢出帧（正常不发生）
                            }
                        } else {
                            pendingRawImage?.close()
                            pendingRawImage = img
                            rawImageAtMs = android.os.SystemClock.elapsedRealtime()
                            tryProcessRaw()
                        }
                    } else {
                        submitStill(img)
                    }
                }, render.looper?.let { android.os.Handler(it) })
            }
        }
        if (rawMode) rawReader = stillReader

        // OES 纹理与 SurfaceTexture 跨镜头复用（渲染端纹理不变，新首帧一到即恢复画面）
        val st = surfaceTexture ?: run {
            val texId = render.createOesTexture()
            if (texId == 0) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "camera", "oes.create_failed", "{}",
                )
                return
            }
            SurfaceTexture(texId).apply {
                setOnFrameAvailableListener({ texture ->
                    if (!firstFrameLogged) {
                        firstFrameLogged = true
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "camera", "camera.first_frame", "{}",
                        )
                    }
                    applyTonePref()
                    applyZebraPref()
                    applyLutStrengthPref()
                    applySatCompPref()
                    // 预览旋转恒 0（v0.6.39 事故修复）：
                    // SurfaceTexture 的 STMatrix 已含传感器侧的变换，**显示方向由 Activity
                    // 跟随系统旋转处理**（本 Activity 未锁 screenOrientation）。所以预览
                    // 不需要也不应该再叠加一次旋转 —— 而 v0.6.39 把 orientationStep 接上来
                    // 之后，设备一转预览就被多转 90°，方向与手机相反、完全无法取景。
                    // 注意 orientationStep 与静拍的旋转量**语义不同**：静拍走 CPU 纹理，
                    // 需要引擎自己补传感器 90°（RenderClient 里 effectiveRot = (rot+1)%4），
                    // 预览走 STMatrix 则不需要。PhotonCamera 同样把"像素方向"与"显示方向"
                    // 解耦（成片像素转正 + EXIF 恒 ORIENTATION_NORMAL）。
                    render.frameAvailable(
                        texture, previewSize.width, previewSize.height,
                        0, filmParams, fillTest,
                    )
                }, render.looper?.let { android.os.Handler(it) })
            }
        }
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        surfaceTexture = st
        previewSurface = previewSurface ?: Surface(st)

        // 切镜头路径（setFocal/setLens）把 started 置 false 后进入这里：
        // 必须恢复，否则 onConfigured 的 !started 守卫会放弃建 session → 黑屏
        started = true
        firstFrameLogged = false
        val gen = ++openGen

        cm.openCamera(targetId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (gen != openGen) { d.close(); return } // 已被更新的打开请求取代
                camera = d
                createSession(d)
            }

            // 切镜头链：close 完成（Surface 已释放）后才重开 pending 镜头
            override fun onClosed(d: CameraDevice) {
                val p = pendingOpenId
                if (p != null) {
                    pendingOpenId = null
                    openCamera(p)
                }
            }

            override fun onDisconnected(d: CameraDevice) = d.close()

            override fun onError(d: CameraDevice, error: Int) {
                if (gen != openGen) { d.close(); return }
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "camera", "camera.open_error",
                    kv("code" to error),
                )
                d.close()
            }
        }, render.looper?.let { android.os.Handler(it) })
    }

    @Suppress("DEPRECATION")
    private fun createSession(d: CameraDevice) {
        val output = previewSurface ?: return
        val stillOutput = stillReader?.surface
        val outputs = if (stillOutput != null) listOf(output, stillOutput) else listOf(output)
        d.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (!started || camera !== d) return // 已停止或已被更新的打开取代
                session = s
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(output)
                    applyControls(this) // M 档控制集（WB/EV/OIS/变焦/对焦区域）
                }
                s.setRepeatingRequest(b.build(), repeatCb, render.looper?.let { android.os.Handler(it) })
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "camera", "camera.session_ready",
                    kv(
                        "preview" to "${previewSize.width}x${previewSize.height}",
                        "still" to "${stillSize.width}x${stillSize.height}",
                        "stillFormat" to (if (rawMode) "RAW_SENSOR" else "YUV"),
                        "sensorOrientation" to sensorOrientation,
                        "ois" to oisSupported,
                        "path" to "surfacetexture+still",
                        "evRange" to "${evRange.lower}..${evRange.upper}",
                        "evStep" to "%.4f".format(evStep),
                    ),
                )
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "camera", "camera.session_failed", "{}",
                )
            }
        }, render.looper?.let { android.os.Handler(it) })
    }

    private fun rotation(): Int = (sensorOrientation / 90) % 4

    /** 单帧静拍统一收尾（YUV / 单帧 RAW 共用）：解锁 + 预览回升 + 落盘。
     *  回调可能在预览渲染线程（直拍/回退）或拍照线程（独立线程）上——只做
     *  轻量操作，与 v0.10.85 以来 "onDone 只收尾" 的约定一致。 */
    private fun finishStillCapture(bytes: ByteArray?, w: Int, h: Int) {
        capturing = false
        capturePhase = 0
        releasePreviewScaleGuard()
        if (bytes != null) queueJpegSave(bytes, w, h)
    }

    private fun submitStill(img: Image) {
        applyTonePref()
        applyZebraPref()
        applyLutStrengthPref()
        // v0.10.93：三平面先拷出、随即 close（HAL 槽位即释），提交改走 buffer
        // 语义 —— 拍照线程失败时可回退预览线程重放（img 已 close 不能二次消费）。
        // YUV 全平面 ≈18MB、拷贝 ~15ms，采集期内可接受。
        val pY = img.planes[0]
        val pU = img.planes[1]
        val pV = img.planes[2]
        fun copyPlane(p: Image.Plane): java.nio.ByteBuffer {
            val b = java.nio.ByteBuffer.allocateDirect(p.buffer.capacity())
            val src = p.buffer.duplicate()
            src.position(0)
            b.put(src)
            b.position(0)
            return b
        }
        val semiPlanar = pU.pixelStride >= 2
        val y = copyPlane(pY)
        val u = copyPlane(pU)
        val v = if (semiPlanar) null else copyPlane(pV)
        val w = img.width
        val h = img.height
        val yStride = pY.rowStride
        val uvStride = pU.rowStride
        val uvPix = pU.pixelStride
        runCatching { img.close() }
        val film = filmParams
        val rot = stillRot   // 快门瞬间快照（不是此刻的 orientationStep，见其注释）
        // 拍照线程路由（v0.10.93，prefs still_thread）：**非阻塞**判断 ready ——
        // 这里在渲染线程上（ImageReader 回调），绝不干等 ensureStarted；
        // 预热在 capture() 入口已 startAsync，本张没赶上就走预览线程（下一张起生效）。
        val useStill = context.getSharedPreferences("filmkit", 0)
            .getBoolean("still_thread", false) && StillRenderClient.ready
        if (useStill) {
            StillRenderClient.submitStillBuf(
                y, u, v, w, h, yStride, uvStride, uvPix, rot, film,
            ) { bytes, rw, rh ->
                if (bytes != null) {
                    finishStillCapture(bytes, rw, rh)
                } else {
                    // 拍照线程失败：回退预览线程重放（红线：失败不以丢照片为代价）
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_WARN, "camera", "render.still_fallback",
                        kv("kind" to "yuv"),
                    )
                    EngineBridge.nativeFlushLogs()
                    render.submitStillBuf(
                        y, u, v, w, h, yStride, uvStride, uvPix, rot, film,
                    ) { b2, w2, h2 -> finishStillCapture(b2, w2, h2) }
                }
            }
        } else {
            render.submitStillBuf(
                y, u, v, w, h, yStride, uvStride, uvPix, rot, film,
            ) { bytes, rw, rh -> finishStillCapture(bytes, rw, rh) }
        }
    }

    // ── RAW 静拍（M2-3）：Image 与 TotalCaptureResult 汇合后处理 ──

    private val rawCaptureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult,
        ) {
            if (burstActive) {
                // burst：按回调序入桶（同 looper 串行保证与提交顺序一致）
                if (burstResultCount < MFNR_FRAMES) {
                    burstResults[burstResultCount++] = result
                    // 曝光决策专项护栏：记录**实际**曝光/ISO —— 请求被 HAL clamp 时
                    // 画面会偏暗（ISO 已按规划值下调），必须能从日志一眼发现。
                    if (burstResultCount == MFNR_FRAMES) {
                        val secs = burstResults.mapNotNull {
                            it?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        }
                        val isos = burstResults.mapNotNull {
                            it?.get(CaptureResult.SENSOR_SENSITIVITY)
                        }
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "camera", "mfnr.burst_actual",
                            kv(
                                "secs" to secs.joinToString(",") { "%.4f".format(it / 1e9f) },
                                "isos" to isos.joinToString(","),
                                "reqSec" to "%.4f".format(expPlanSec / 1e9f),
                                "reqIso" to expPlanIso,
                            ),
                        )
                    }
                    tryProcessBurst()
                }
            } else {
                pendingResult = result
                tryProcessRaw()
            }
        }
    }

    /** Image/result 双事件汇合（均在渲染线程串行回调，无锁）；缺 result 不处理等回调 */
    // v0.6.34：原先把"Image 先到、TotalCaptureResult 后到"这个**正常竞态**记成 WARN
    // 且字段为空 {} —— 每次拍照都误报一条、还看不出等了多久，反而掩盖真正的异常。
    // 改为静默等待（回调到齐会自然再进来），等 result 到达后按**实际耗时**决定是否记，
    // >50ms 才记一条并带上 waitMs。
    private fun tryProcessRaw() {
        val img = pendingRawImage ?: return
        val result = pendingResult ?: return
        val waitMs = if (rawImageAtMs > 0L) {
            android.os.SystemClock.elapsedRealtime() - rawImageAtMs
        } else {
            0L
        }
        rawImageAtMs = 0L
        pendingRawImage = null
        pendingResult = null
        if (waitMs > 50L) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "raw.result_late",
                kv("waitMs" to waitMs),
            )
        }
        processRaw(img, result)
    }

    /** RAW 端到端：提取元数据 → DNG 原片（HAL 元数据，不含引擎 WB 补偿——口径 §0-D6）→
     *  引擎 submitRaw 胶片成片 JPEG。img 生命周期：saveDng 与 submitRaw 都在渲染线程，
     *  submitRaw 内部消费后关闭。 */
    private fun processRaw(img: Image, result: TotalCaptureResult) {
        val ch = curCharacteristics
        val prefs = context.getSharedPreferences("filmkit", 0)
        val dngOn = prefs.getBoolean("dng_enabled", false)
        // 去马赛克质量档：1 = RCD 三段管线（默认，M3-1）；0 = bilinear 速度档
        val demosaic = prefs.getInt("demosaic_algo", -1)
            .let { if (it in 0..2) it else if (prefs.getBoolean("demosaic_quality", true)) 1 else 0 }
        // 降噪强度（C 专项 2026-09-29：滑杆 0–2、步长 0.01；0 = 关、1 = 真实 σ、
        // 2 = 2×σ；仅 RCD 生效）。旧三档 key 自动迁移（见 denoiseStrength）。
        val noiseStrength = denoiseStrength(
            prefs.contains("noise_reduction_strength"),
            prefs.getFloat("noise_reduction_strength", 1f),
            prefs.getBoolean("noise_reduction", true),
            prefs.getInt("noise_reduction_level", 1),
        )
        // NN 降噪（v0.10.102）：PMRID 接拍照链 —— NN 路线已判死（2026-10-02，
        // 跨域模型不可直接用、微调全过拟合），设置入口已删，恒关；代码路径保留
        val nnDn = false
        val meta = extractRawMeta(result, ch)
        logRawMeta(img, result, meta)
        if (dngOn && ch != null) {
            try {
                saveDng(img, result, ch)
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "camera", "dng.save_failed",
                    kv("error" to (t.message ?: t.toString())),
                )
            }
        }
        // v0.10.93：Image 先拷成 direct buffer 再 close（HAL 槽位即释），提交走
        // buffer 语义 —— 拍照线程失败可回退预览线程重放；与 MFNR 的 copyRawAndClose
        // 同构（25MB、~30ms）。saveDng 在拷贝前（DngCreator 只吃 Image 本体）。
        val p0 = img.planes[0]
        val rawBuf = java.nio.ByteBuffer.allocateDirect(p0.buffer.capacity())
        val rawSrc = p0.buffer.duplicate()
        rawSrc.position(0)
        rawBuf.put(rawSrc)
        rawBuf.position(0)
        val rawStridePx = p0.rowStride / 2
        val rawW = img.width
        val rawH = img.height
        runCatching { img.close() }
        applyTonePref()
        applyZebraPref()
        applyLutStrengthPref()
        // 拍照线程路由（v0.10.93，prefs still_thread）：非阻塞判断 ready（本函数
        // 在渲染线程上，绝不干等）；预热在 capture() 入口 startAsync。
        val useStill = prefs.getBoolean("still_thread", false) && StillRenderClient.ready
        if (useStill) {
            StillRenderClient.submitRawBuf(
                rawBuf, rawW, rawH, rawStridePx, meta, stillRot, filmParams,
                demosaic, noiseStrength, nnDn,
            ) { bytes, w, h ->
                if (bytes != null) {
                    finishStillCapture(bytes, w, h)
                } else {
                    // 拍照线程失败：回退预览线程重放（红线：失败不以丢照片为代价）
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_WARN, "camera", "render.still_fallback",
                        kv("kind" to "raw"),
                    )
                    EngineBridge.nativeFlushLogs()
                    render.submitRawBuf(
                        rawBuf, rawW, rawH, rawStridePx, meta, stillRot,
                        filmParams, demosaic, noiseStrength, nnDn,
                    ) { b2, w2, h2 -> finishStillCapture(b2, w2, h2) }
                }
            }
        } else {
            render.submitRawBuf(
                rawBuf, rawW, rawH, rawStridePx, meta, stillRot, filmParams,
                demosaic, noiseStrength, nnDn,
            ) { bytes, w, h -> finishStillCapture(bytes, w, h) }
        }
    }

    /**
     * HAL 原生色彩矩阵（`COLOR_CORRECTION_TRANSFORM`，行主序 9 元素）。
     * v0.10.24 从内联块抽出：raw.meta 日志与 ccm 选择两处复用。
     * 构造收行主序、读取 getElement(col,row)——遍历须 (row, col) 双重序防转置（v0.5.20 教训）。
     */
    private fun halCcmOf(result: CaptureResult): FloatArray {
        val m = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { t ->
            for (row in 0..2) for (col in 0..2) m[row * 3 + col] = t.getElement(col, row).toFloat()
        }
        return m
    }

    /** raw.meta / raw.calib 日志（单帧与 MFNR burst 共用；原 processRaw 内联块抽出） */
    private fun logRawMeta(img: Image, result: TotalCaptureResult, meta: RenderClient.RawFrameMeta) {
        val ch = curCharacteristics
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "raw.meta",
            kv(
                "cfa" to meta.cfa, "white" to meta.whiteLevel,
                "black" to meta.blackPhase.joinToString(",") { "%.0f".format(it) },
                "neutral" to meta.neutral.joinToString(",") { "%.4f".format(it) },
                "ccm" to meta.ccm.joinToString(",") { "%.3f".format(it) },
                "ccmSrc" to if (meta.ccmStd) "std" else "hal",
                // ── v0.10.24（诊断）：两条色彩链的矩阵**同时**入日志 ──────────────
                // 为什么：色度差距定位到 filmic 之前的色彩变换后（大色块 C* 低 17%，
                // 已排除降噪/LSC/去马赛克/工作域），需要区分"StdColorMatrix 的
                // ForwardMatrix 双光源插值链"与"HAL 原生 COLOR_CORRECTION_TRANSFORM"
                // 各自的饱和度传递。此前只能来回切 ccm_std 开关靠两张照片比 —— 现在
                // 一张照片就能离线对拍两条链。
                // std 链**无条件**计算（纯 3×3 矩阵运算，开销可忽略），即使当前用的是 HAL。
                "ccmStd" to (ch?.let { StdColorMatrix.compute(it, meta.neutral) }
                    ?.joinToString(",") { "%.4f".format(it) } ?: "null"),
                "ccmHal" to halCcmOf(result).joinToString(",") { "%.4f".format(it) },
                "noise" to "%.4f".format(meta.noiseSigma),
                "in" to "${img.width}x${img.height}",
                "lens" to currentLensId,
                // ── v0.6.34：预览实际曝光 vs 拍照实际曝光 ──────────────────
                // controls.applied 的 iso/sec 是滑杆存量值（AUTO 档下不代表实际），
                // 预览实际值只回填 UI；这里一次记全，一次真机即可定位。
                "pvIso" to liveIso,
                "pvSec" to "%.4f".format(liveExposureNs / 1e9f),
                "stIso" to (result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0),
                "stSec" to "%.4f".format(
                    (result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) / 1e9f,
                ),
                // 畸变校正探测（v0.10.109-exp7）：本次静拍 HAL 实际生效的模式
                // （1=OFF / 2=FAST / 3=HIGH_QUALITY；-1 = 本流未上报）
                "distCorr" to (if (android.os.Build.VERSION.SDK_INT >= 28) {
                    result.get(CaptureResult.DISTORTION_CORRECTION_MODE) ?: -1
                } else {
                    -1
                }),
            ),
        )
        // DNG 标准链探针（P2）：标定矩阵是 **CameraCharacteristics 静态键**（不在
        // CaptureResult —— v0.5.45 初版记错归属，编译期 Unresolved 才暴露）。
        // 每个 cameraId 各记一条（v0.6.20）。
        if (calibLoggedId != currentLensId && ch != null) {
            calibLoggedId = currentLensId
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "raw.calib",
                kv(
                    "cameraId" to currentLensId,
                    "ct1" to cst9(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)),
                    "ct2" to cst9(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)),
                    "cal1" to cst9(ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)),
                    "cal2" to cst9(ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)),
                    "fm1" to cst9(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)),
                    "fm2" to cst9(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)),
                    "illum1" to (ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: -1),
                    "illum2" to (ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) ?: -1),
                ),
            )
        }
    }

    /** MFNR burst 端到端：4+4 帧齐后处理（帧 1-3 已拷贝 close，帧 0 仍持 Image）。
     *  元数据/DNG 全用帧 0（参考帧，恒曝光）；mfnr.capture 记录逐帧实际曝光
     *  （HAL 是否逐帧重 AE 用日志核对——若漂移大，二步修复是锁定首帧曝光）。
     *  流程：DNG 用帧 0 Image 写出 → 帧 0 拷贝并 close（4 个 HAL 槽位全部释放）
     *  → 4 buffer 提交引擎融合。 */
    private fun processRawBurst(images: List<Image>, results: List<TotalCaptureResult>) {
        val ch = curCharacteristics
        val prefs = context.getSharedPreferences("filmkit", 0)
        val dngOn = prefs.getBoolean("dng_enabled", false)
        val demosaic = prefs.getInt("demosaic_algo", -1)
            .let { if (it in 0..2) it else if (prefs.getBoolean("demosaic_quality", true)) 1 else 0 }
        // 降噪强度（C 专项：滑杆 0–2、步长 0.01；0 = 关、1 = 真实 σ、2 = 2×σ）
        val noiseStrength = denoiseStrength(
            prefs.contains("noise_reduction_strength"),
            prefs.getFloat("noise_reduction_strength", 1f),
            prefs.getBoolean("noise_reduction", true),
            prefs.getInt("noise_reduction_level", 1),
        )
        val ref = images[0]
        val refResult = results[0]
        val refW = ref.width
        val refH = ref.height
        val meta = extractRawMeta(refResult, ch)
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "mfnr.capture",
            kv(
                "frames" to results.size,
                "iso" to results.joinToString(",") { (it.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0).toString() },
                "sec" to results.joinToString(",") {
                    "%.4f".format((it.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) / 1e9f)
                },
                "ms" to (android.os.SystemClock.elapsedRealtime() - burstStartMs),
                "lens" to currentLensId,
                // 畸变校正探测（v0.10.109-exp7）：burst 首帧实际生效的模式
                "distCorr" to (if (android.os.Build.VERSION.SDK_INT >= 28) {
                    results.firstOrNull()?.get(CaptureResult.DISTORTION_CORRECTION_MODE) ?: -1
                } else {
                    -1
                }),
            ),
        )
        logRawMeta(ref, refResult, meta)
        if (dngOn && ch != null) {
            try {
                saveDng(ref, refResult, ch)
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "camera", "dng.save_failed",
                    kv("error" to (t.message ?: t.toString())),
                )
            }
        }
        // 帧 0 拷贝并 close：此时 HAL 槽位全部释放，只剩 4 个 direct buffer
        copyRawAndClose(ref, 0)
        burstImages.fill(null)
        EngineBridge.nativeFlushLogs()
        applyTonePref()
        applyZebraPref()
        applyLutStrengthPref()
        val buffers = burstBuffers.mapNotNull { it }
        val strides = burstStrides.copyOf()
        // 降噪专项 A（2026-09-29）：prefs mfnr_dft = true 走 DFT 逐频收缩合并
        //（HDR+ 式 6/7），false 走 mismatch 加权融合（并联 A/B 口径）
        val useDft = prefs.getBoolean("mfnr_dft", false)
        // D-2 对齐精度 A/B（2026-09-30）：prefs align_l1 = true 时最细对齐层用
        // 1/2 域（新，需 readback 1/2 域），false 退回 1/4 域旧路径
        val alignL1 = prefs.getBoolean("align_l1", true)
        // 同帧 A/B 诊断（v0.10.74）：prefs align_ab = true 时同一帧再跑一遍旧层
        val alignAb = prefs.getBoolean("align_ab", false)
        // D-2 杠杆 B（v0.10.75，0.10.76 起默认开）：P0 时域采样走亚像素。
        // 真机同帧 A/B 实测收益 +43.7%/+46.5%（残余噪声降到基线的 70%），
        // dnMs 未见显著上升，画质验收（DNG 自参照 100% 裁剪）无细节损失。
        val p0Sub = prefs.getBoolean("p0_sub", true)
        // NN 降噪（v0.10.102）：NN 路线已判死（2026-10-02），设置入口已删，恒关
        val nnDn = false
        // 先占在飞名额再解锁，避免"解锁瞬间用户又按一次"导致计数漏加
        rendersInFlight.incrementAndGet()
        val done: (ByteArray?, Int, Int) -> Unit = { bytes, w, h ->
            rendersInFlight.decrementAndGet()
            capturePhase = 0
            // 合成结束（无论成败）：预览降载回升（v0.10.93）。capturing 已在下面
            // 提前解锁，这里的恢复点才是"GPU 不再被合成占用"的时刻。
            releasePreviewScaleGuard()
            // 这里**不再清 burst* 共享数组**：capturing 已经提前解锁，用户此刻
            // 很可能已经开了下一张采集并在写这些数组，清空会把新一张的帧数据
            // 抹掉。数组统一由 startBurst() 在每张采集开始时重置。
            if (bytes != null) queueJpegSave(bytes, w, h)
        }
        // 拍照渲染线程（v0.10.85 重做，prefs still_thread **默认关**）：把这条
        // 1.6~3.4s 的合成链挪出预览线程 —— 否则预览的 updateTexImage 全排在它
        // 后面，"拍完挪手机画面完全冻住 1~2 秒"。启动失败即回退预览线程。
        val useStill = prefs.getBoolean("still_thread", false) &&
            StillRenderClient.ensureStarted()
        // NN 同帧 A/B（v0.10.102）：NN 路线已判死，入口随 nn_dn 一并删除，恒关
        val nnAb = false
        // 高光保护（v0.10.110）：成片 EV 加上本次欠曝量做补偿（预览用原数组）——
        // 曝光欠 X + 提亮补 X ⇒ 中间调与预览一致，高光因为少收光子而不裁切。
        val filmStill = filmParamsForStill()
        if (useStill && prefs.getBoolean("still_ab", false)) {
            submitStillAb(buffers, strides, refW, refH, meta, demosaic, noiseStrength,
                useDft, alignL1, alignAb, p0Sub, nnDn, /*varyNn=*/false, done)
        } else if (nnAb) {
            // NN 同帧 A/B（v0.10.102）：第一遍 nn off（P0 基线）、第二遍 nn on，
            // 两张成片都落盘供离线对比（时间戳天然区分）
            submitStillAb(buffers, strides, refW, refH, meta, demosaic, noiseStrength,
                useDft, alignL1, alignAb, p0Sub, nnDn, /*varyNn=*/true, done)
        } else if (useStill) {
            StillRenderClient.submitRawBurst(buffers, strides, refW, refH, meta,
                stillRot, filmStill, demosaic, noiseStrength, burstRefNoiseScale,
                useDft, alignL1, alignAb, p0Sub, nnDn) { bytes, w, h ->
                if (bytes != null) {
                    done(bytes, w, h)
                } else {
                    // 拍照线程合成失败（GL/OOM 等）：回退预览线程重跑一次 ——
                    // 用户红线：失败不以丢照片为代价（v0.10.83 就是这么丢的）
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_WARN, "camera", "render.still_fallback", "{}",
                    )
                    EngineBridge.nativeFlushLogs()
                    render.submitRawBurst(buffers, strides, refW, refH, meta,
                        stillRot, filmStill, demosaic, noiseStrength,
                        burstRefNoiseScale, useDft, alignL1, alignAb, p0Sub, nnDn, done)
                }
            }
        } else {
            render.submitRawBurst(buffers, strides, refW, refH, meta,
                stillRot, filmStill, demosaic, noiseStrength, burstRefNoiseScale,
                useDft, alignL1, alignAb, p0Sub, nnDn, done)
        }
        // 提前解锁（v0.10.81，学 PhotonCamera 的"快门即撒手"）：上面
        // copyRawAndClose 之后 HAL 槽位已全部释放，4 帧数据也已各自独立
        //（buffers 是局部 List，捕获的是当次采集的 direct buffer 引用），
        // 渲染完全在后台。原来把 capturing 拖到渲染回调（约 1.6s 后）才置
        // false，等于把"能不能再拍"绑在整条渲染链上 —— 这是"按完快门卡 2 秒"
        // 的主因。现在解锁点提前到"采集+DNG+拷贝"完成（约 0.5s）。
        capturing = false
    }

    /** 同帧 A/B（v0.10.85，诊断用 prefs still_ab）：**同一帧数据**先走拍照线程
     *  （独立 context）、再走预览线程，比较两遍的输出摘要 —— 唯一无混杂地验证
     *  "换执行路径不改画质"（项目口径：跨拍摄的 A/B 会被场景/曝光/手抖污染）。
     *  落盘第一遍（拍照线程）的成片，第二遍只做对照。
     *  代价：多一遍完整合成（第二遍期间预览会卡，这正是要修的那个现象）+
     *  峰值多一张 RGBA（≈50MB）—— 只在验收时打开。
     *
     *  v0.10.102 扩展（varyNn=true，prefs nn_ab）：NN 质量 A/B —— 第一遍 nn off
     *  （P0 基线）、第二遍 nn on，两张成片**都落盘**（文件名时间戳天然区分），
     *  日志 render.nn_ab 记两遍摘要。 */
    private fun submitStillAb(
        buffers: List<ByteBuffer>,
        strides: IntArray,
        width: Int,
        height: Int,
        meta: RenderClient.RawFrameMeta,
        demosaic: Int,
        noiseStrength: Float,
        useDft: Boolean,
        alignL1: Boolean,
        alignAb: Boolean,
        p0Sub: Boolean,
        nnDn: Boolean,
        varyNn: Boolean,
        done: (ByteArray?, Int, Int) -> Unit,
    ) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        // 参数快照：两遍相隔 1.6s+，中间用户可能转手机/改设置 —— 直接读成员会让
        // A/B 混进第二个变量（本项目"跨时刻对比"翻车过多次）
        val rot = stillRot   // 快门瞬间快照（同上）
        val film = filmParamsForStill()   // v0.10.110：A/B 两遍同样带高光保护补偿
        val rn = burstRefNoiseScale
        val nn1 = if (varyNn) false else nnDn
        val nn2 = if (varyNn) true else nnDn
        StillRenderClient.submitRawBurst(buffers, strides, width, height, meta,
            rot, film, demosaic, noiseStrength, rn,
            useDft, alignL1, alignAb, p0Sub, nn1) { b1, w1, h1 ->
            val d1 = if (b1 != null) outDigest(b1, w1, h1) else null
            render.submitRawBurst(buffers, strides, width, height, meta,
                rot, film, demosaic, noiseStrength, rn,
                useDft, alignL1, alignAb, p0Sub, nn2) { b2, w2, h2 ->
                val d2 = if (b2 != null) outDigest(b2, w2, h2) else null
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "camera",
                    if (varyNn) "render.nn_ab" else "render.still_ab",
                    kv(
                        "crcStill" to (d1?.crc ?: -1L),
                        "crcPreview" to (d2?.crc ?: -1L),
                        "same" to (d1 != null && d2 != null && d1.crc == d2.crc),
                        "meanStill" to "%.3f".format(d1?.mean ?: -1.0),
                        "meanPreview" to "%.3f".format(d2?.mean ?: -1.0),
                        "sizeStill" to "${w1}x${h1}",
                        "sizePreview" to "${w2}x${h2}",
                        "nn1" to nn1, "nn2" to nn2,
                        "abMs" to (android.os.SystemClock.elapsedRealtime() - t0),
                    ),
                )
                EngineBridge.nativeFlushLogs()
                if (varyNn) {
                    // NN A/B：两张成片都落盘（基线在前、NN 在后加 _nn 后缀区分）；
                    // done 传 null 避免外部收尾把 b2 再存一遍（0.10.102 真机实测
                    // 三张重复：b1 + b2 + done(b2)）
                    if (b1 != null) queueJpegSave(b1, w1, h1)
                    if (b2 != null) queueJpegSave(b2, w2, h2, "_nn")
                    done(null, 0, 0)
                } else {
                    // 第二遍失败（含相机页退出导致 post 失败）时仍要落第一遍的成片 ——
                    // 否则"照片已算出却不落盘"（v0.10.85 审查 R1）
                    if (b1 != null) done(b1, w1, h1) else done(b2, w2, h2)
                }
            }
        }
    }

    /** ColorSpaceTransform → 行主序 9 元素字符串；null → "none"（DNG 标准链回填探针）。
     *  读取必须 (row, col) 双循环：构造收行主序、getElement(col, row)（v0.5.20 转置教训）。 */
    private fun cst9(t: android.hardware.camera2.params.ColorSpaceTransform?): String {
        t ?: return "none"
        val v = FloatArray(9)
        for (row in 0..2) for (col in 0..2) v[row * 3 + col] = t.getElement(col, row).toFloat()
        return v.joinToString(",") { "%.4f".format(it) }
    }

    /** CFA 相位 → 通道（0=R 1=G 2=B）；与引擎 shader chanAt 同一映射（Camera2: 0=RGGB 1=GRBG 2=GBRG 3=BGGR） */
    private fun cfaChan(cfa: Int, x: Int, y: Int): Int {
        val px = x and 1
        val py = y and 1
        return when (cfa) {
            0 -> if (py == 0) (if (px == 0) 0 else 1) else (if (px == 0) 1 else 2)
            1 -> if (py == 0) (if (px == 0) 1 else 0) else (if (px == 0) 2 else 1)
            2 -> if (py == 0) (if (px == 0) 1 else 2) else (if (px == 0) 0 else 1)
            else -> if (py == 0) (if (px == 0) 2 else 1) else (if (px == 0) 1 else 0)
        }
    }

    /** CaptureResult → 引擎 RAW 参数。黑阶：dynamic 优先（[R,Ge,Go,B] 顺序）、pattern 兜底，
     *  统一重排为按相位 (0,0)(1,0)(0,1)(1,1)；白阶：dynamic → characteristics；
     *  WB：SENSOR_NEUTRAL_COLOR_POINT → 实时增益估计 → 中性 */
    private fun extractRawMeta(
        result: TotalCaptureResult,
        ch: CameraCharacteristics?,
    ): RenderClient.RawFrameMeta {
        val black4 = FloatArray(4)
        val dynBl: FloatArray? = if (Build.VERSION.SDK_INT >= 28) {
            result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        } else null
        if (dynBl != null && dynBl.size >= 4) {
            // dynamic 顺序 [R, Geven, Godd, B]：G 通道按行奇偶取下标 1/2
            var i = 0
            for (y in 0..1) for (x in 0..1) {
                val c = cfaChan(colorFilterArrangement, x, y)
                black4[i++] = dynBl[if (c == 0) 0 else if (c == 2) 3 else y]
            }
        } else {
            val blp: BlackLevelPattern? =
                ch?.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            if (blp != null) {
                val vals = IntArray(BlackLevelPattern.COUNT)
                blp.copyTo(vals, 0)
                var i = 0
                for (y in 0..1) for (x in 0..1) {
                    black4[i++] = vals[blp.getOffsetForIndex(x, y)].toFloat()
                }
            }
        }
        val whiteLevel = (if (Build.VERSION.SDK_INT >= 28) {
            result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
        } else null)
            ?: ch?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: 1023

        var nR = 0f
        var nG = 0f
        var nB = 0f
        val neutral: Array<android.util.Rational>? =
            result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        neutral?.let { n ->
            val r = n[0].toFloat()
            val g = n[1].toFloat()
            val b = n[2].toFloat()
            if (r > 0f && g > 0f && b > 0f) {
                nR = r; nG = g; nB = b
            }
        }
        if (nR <= 0f || nG <= 0f || nB <= 0f) {
            // 兜底：AWB 实时增益估计（neutral = 1/gain，G 归一）；再兜底全中性
            if (estGainR > 0.001f && estGainB > 0.001f) {
                nR = 1f / estGainR; nG = 1f; nB = 1f / estGainB
            } else {
                nR = 1f; nG = 1f; nB = 1f
            }
        }
        val halCcm = halCcmOf(result)
        // 色彩链（§3.2-7 / §3.4-4）：优先用 characteristics 标定矩阵自建标准链
        // （ColorMatrix/ForwardMatrix 双光源 + Bradford D50→sRGB，与 Adobe/libraw 完整链同源）；
        // 开关关或标定数据缺失 → 回退 HAL 的 COLOR_CORRECTION_TRANSFORM（跨机型兜底）
        val stdCcm = if (appPrefs.getBoolean("ccm_std", true)) {
            StdColorMatrix.compute(ch, floatArrayOf(nR, nG, nB))
        } else {
            null
        }
        val ccm = stdCcm ?: halCcm
        // ── 噪声模型（C 专项，2026-09-29；来源可切换：设置页「噪声模型」）─────
        // 优先级：用户选定的标定（GCam .c，NoiseCalibStore）> Camera2
        // SENSOR_NOISE_PROFILE（auto，默认）> ISO 估算 legacy。
        // 模型域 = [0,1] 归一化 raw（与渲染域/DNG NoiseProfile 同源），**不除
        // whiteLevel**。旧实现取 (s[0]+s[1]+s[2])/3 是 S/O 混合垃圾值、再除
        // whiteLevel 后被引擎 clamp 到 0.004 —— σ 自适应从未生效；且 2026-09-29
        // 真机实锤：`as? DoubleArray` 对装箱数组（Camera2 泛型是 Double[]）**永远
        // 失败**，模型一直无效、mismatch 噪声底为零（neff=1 融合失效）。
        var noiseModel: FloatArray? = null
        var noiseSigma = 0f
        var nzSrc = "legacy"
        val isoNow = (result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100).coerceAtLeast(50)
        val nmSel = appPrefs.getString("noise_model_src", NoiseCalibStore.AUTO)
            ?: NoiseCalibStore.AUTO
        if (nmSel != NoiseCalibStore.AUTO) {
            val m = NoiseCalibStore.load(context, nmSel)?.evaluate(isoNow)
            if (m != null) {
                noiseModel = m
                nzSrc = "calib"
            }
        }
        // 内置实测标定（v0.10.35）：按设备型号 + 当前镜头等效焦距匹配（X300 的
        // 70MM/15MM —— HAL 对隐藏物理镜头返回主摄模型，实测低估 3.56×/2.16×，
        // 见 NoiseCalibStore.BUILT_IN）。优先级：用户选定 > 内置实测 > Camera2。
        if (noiseModel == null) {
            val lens = backLenses.firstOrNull { it.id == currentLensId }
            val bi = lens?.let {
                NoiseCalibStore.builtInFor(context, android.os.Build.MODEL, it.equivMm)
            }
            val m = bi?.evaluate(isoNow)
            if (m != null) {
                noiseModel = m
                nzSrc = "builtin"
            }
        }
        var npDiag: NoiseProfile.Result? = null
        var camPairsTxt = "-"
        var camLen = 0
        if (noiseModel == null && Build.VERSION.SDK_INT >= 28) {
            @Suppress("UNCHECKED_CAST")
            val profile = result.get(CaptureResult.SENSOR_NOISE_PROFILE) as? Array<*>
            camLen = profile?.size ?: 0
            // 原始全量 dump（值 | 类型一次到位，防布局再猜错）
            camPairsTxt = profile?.joinToString(";") { item ->
                val p = item as? android.util.Pair<*, *>
                if (p == null) "?" else "${p.first}|${p.second}"
            } ?: "-"
            val firstSecond = (profile?.firstOrNull() as? android.util.Pair<*, *>)?.second
            when {
                // 结构 A（真机实测 X300，PC 同款语义）：4 个 (S,O) 标量对 =
                // 4 个 CFA 相位（per-frame，HAL 已按当前增益给，无 gain 字段）
                firstSecond is Number -> {
                    val pairs = profile!!.mapNotNull { item ->
                        val p = item as? android.util.Pair<*, *> ?: return@mapNotNull null
                        val sh = (p.first as? Number)?.toDouble() ?: return@mapNotNull null
                        val rd = (p.second as? Number)?.toDouble() ?: return@mapNotNull null
                        sh to rd
                    }
                    noiseModel = NoiseProfile.fromScalarPairs(pairs, colorFilterArrangement)
                    nzSrc = if (noiseModel != null) "cam2" else "cam2bad"
                }
                // 结构 B（SDK 文档形态）：(gain, Double[])，按 ISO 选择 + 相位映射
                firstSecond is Array<*> || firstSecond is List<*> -> {
                    val elemType = firstSecond.javaClass.name
                    val entries = profile!!.mapNotNull { item ->
                        val pair = item as? android.util.Pair<*, *> ?: return@mapNotNull null
                        val g = (pair.first as? Number)?.toDouble() ?: return@mapNotNull null
                        val s = NoiseProfile.asDoubleArray(pair.second) ?: return@mapNotNull null
                        g to s
                    }
                    val np = NoiseProfile.parse(entries, colorFilterArrangement, isoNow, elemType)
                    npDiag = np
                    noiseModel = np.model
                    if (noiseModel != null) nzSrc = "cam2b"
                }
                else -> nzSrc = "cam2none"
            }
        }
        // 诊断日志（每次拍照；来源/布局/量级真机核对）
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "raw.noise",
            kv(
                "src" to nzSrc, "sel" to nmSel, "iso" to isoNow,
                "cfa" to colorFilterArrangement,
                "len" to camLen,
                "pairs" to camPairsTxt,
                "gain" to "%.2f".format(npDiag?.gain ?: 0.0),
                "raw" to (npDiag?.raw?.joinToString(",") { "%.3e".format(it) } ?: "-"),
                "S" to (noiseModel?.let { "%.3e,%.3e,%.3e".format(it[0], it[1], it[2]) } ?: "-"),
                "O" to (noiseModel?.let { "%.3e,%.3e,%.3e".format(it[3], it[4], it[5]) } ?: "-"),
            ),
        )
        // HAL 不回填 / 布局不可识别时的回退：**按 ISO 估算**（v0.6.30；模型无效
        // 才走这条 legacy 路径，引擎侧等效旧经验式 —— 见 denoiseParams）。
        // 旧实现是固定 0.030，而 DNG 平坦块法实测 ISO 50–767 的 RAW σ 只有
        // 0.0013–0.0033 —— 高估约 4~10 倍，于是 P4 把**真实色度**当噪声一起平滑掉了：
        // 真机对照 PhotonCamera 同机位，暗部（桌面）C*/L* 只有对方的 54%，而 libraw
        // 原始数据里暗部色度是完好的（rel 0.285 vs 我们 0.093）。
        // v0.6.31 折中：0.008 太小 —— 真机反馈**彩噪爆炸**。平坦块法测的是**亮度**
        // 噪声，而 P4 的 σc 要压的是**色度**噪声（WB 增益把 R/B 通道差放大 2~3 倍，
        // 再叠加曝光增益）。取 0.015 + 8e-6·ISO：ISO 50 → 0.0154、ISO 3200 → 0.041
        // （≈旧值，高 ISO 不放松），即比旧的固定 0.030 保守一半。
        // v0.6.32：0.015 → 0.012，**同时把 σc 的地板 0.015 → 0.008、P4 半径 2 → 3**。
        // 三者是一套：降噪职责从"值域阈值"移到"空间半径"（半径只压高频噪声，不动
        // 真实色度；值域阈值大则连真实色度一起吞）。离线评估的净效果（暗部彩噪
        // lag-1 残余 / 真实色度边缘保留）：0.0352σc+25tap = 28.5%/0.624 →
        // 0.0242σc+49tap = 20.0%/0.506。σc 变小不是放松降噪，是把降噪换成更强的
        // 空间平均；若真机彩噪仍偏高，把设置页 RAW 降噪切"强"档（sc_k 3.25，σc +18%）。
        if (noiseModel == null) {
            noiseSigma = (0.012 + 0.000008 * isoNow).toFloat()
        }
        // 镜头阴影校正（LSC，M3 P0）：HAL 的 cols×rows×4 增益图（R/Gr/Gb/B 平面，
        // X300 实测 17×13、中心 1.000、角落 6.236 = 未校正时角落原始亮度仅中心 ~16%）。
        // 探针日志保留原始口径；prefs "lsc" 关 → lsc = null（引擎用恒等增益，A/B 对照）。
        var lsc: FloatArray? = null
        var lscCols = 1
        var lscRows = 1
        val lscOn = appPrefs.getBoolean("lsc", true)
        result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { m ->
            val rows = m.rowCount
            val cols = m.columnCount
            if (rows > 1 && cols > 1) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "camera", "raw.lsc",
                    kv(
                        "grid" to "${cols}x${rows}",
                        "mode" to (result.get(CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE) ?: -1),
                        "center" to "%.3f".format(m.getGainFactor(0, cols / 2, rows / 2)),
                        "corner" to "%.3f".format(m.getGainFactor(0, 0, 0)),
                        "cornerBR" to "%.3f".format(m.getGainFactor(0, cols - 1, rows - 1)),
                        "on" to lscOn,
                    ),
                )
                if (lscOn) {
                    // Gr/Gb 折成均值（两平面增益差异 <1%，色偏主因在 R/B 与 G 之间）。
                    // v0.10.25 边缘软限幅（prefs lsc_damp，默认开）：只压高增益区
                    // （knee 2.5 以上），中心 1.0 与 knee 内线性段不动。
                    // 实现 = 通道共享阻尼因子：每网格点按四平面的最大增益算压缩比，
                    // 三通道同乘 —— 严格保持通道比（否则各通道压缩比略异会在边缘
                    // 引入 a* ±0.9 的色度漂移，v0.10.25 首版实测）。
                    // 依据：① 平面间角落增益差（R 6.31 vs B 5.85）的色度漂移被抑制；
                    // ② 与渲染 EV +0.7 叠加后边缘净亮度 +0.3EV，避免外圈过曝。
                    // 代价：保留 ~0.4EV 的边缘晕影（阻尼后校正不足），温和暗角。
                    val damp = appPrefs.getBoolean("lsc_damp", true)
                    val buf = FloatArray(rows * cols * 3)
                    var i = 0
                    for (y in 0 until rows) for (x in 0 until cols) {
                        val g0 = m.getGainFactor(0, x, y)
                        val g1 = 0.5f * (m.getGainFactor(1, x, y) + m.getGainFactor(2, x, y))
                        val g2 = m.getGainFactor(3, x, y)
                        val gMax = maxOf(g0, g1, g2)
                        // v0.10.69：硬 knee → 平滑正部软 knee（修用户实测的暗光竖线）。
                        // 硬拐点在 gMax 跨过 2.5 的格点处造成增益**斜率突变**，离线实测
                        // 二阶差 0.1438 vs 曲线本底 0.0898，位置 = 第 1 列 = W/17
                        // = 原图 x≈181 —— 与用户实测竖线位置精确吻合（插值后成马赫带，
                        // 暗光下拐点两侧噪声放大差异使其显形；开灯噪声被压过则不可见）。
                        // 软 knee 用平滑正部 (d+√(d²+w²))/2，w=0.3：二阶差回落到 0.0894
                        // （= 本底，人为跳变完全消失），角落增益仍压到 3.179
                        //（硬版本 3.187），阻尼意图不变。
                        val f = if (!damp) 1f else {
                            val d = gMax - 2.5f
                            val w = 0.3f
                            (gMax - 0.25f * (d + kotlin.math.sqrt(d * d + w * w))) / gMax
                        }
                        buf[i++] = g0 * f
                        buf[i++] = g1 * f
                        buf[i++] = g2 * f
                    }
                    lsc = buf
                    lscCols = cols
                    lscRows = rows
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "camera", "raw.lsc.damp",
                        kv("on" to damp, "knee" to "2.5", "ratio" to "0.5", "softW" to "0.3",
                            "shared" to "per-pixel-max"),
                    )
                }
            }
        }
        return RenderClient.RawFrameMeta(
            colorFilterArrangement, black4, whiteLevel,
            floatArrayOf(nR, nG, nB), ccm, noiseModel, noiseSigma,
            lsc, lscCols, lscRows,
            ccmStd = stdCcm != null,
        )
    }

    /** DNG 原片保存：DngCreator 按 HAL 元数据写（asShotNeutral/黑阶/CCM 全在其中），
     *  用户手动 WB 的引擎侧补偿不进 DNG（底片口径，v0.5.9 决策）。 */
    private fun saveDng(
        img: Image,
        result: TotalCaptureResult,
        ch: CameraCharacteristics,
    ) {
        val creator = android.hardware.camera2.DngCreator(ch, result)
        // DNG 存的是**传感器原始方向**的像素（DngCreator 不旋转），所以方向必须由 EXIF 标签表达，
        // 且要**随拍摄方向变** —— 与 JPG 路径同源：显示所需旋转 = (sensorOrientation/90 + step) % 4，
        // 再映射到 EXIF 的 1/6/3/8。
        // v0.6.42 修复：此前只按 sensorOrientation 恒写 6，横拍时像素明明是横的、
        // 相册却按"顺时针转 90°"显示 → 方向反了（真机 2026-09-24 复现）。
        creator.setOrientation(
            when ((sensorOrientation / 90 + stillRot) % 4) {   // 快门瞬间快照
                1 -> 6 // 需顺时针 90°
                2 -> 3 // 需 180°
                3 -> 8 // 需逆时针 90°
                else -> 1
            },
        )
        val now = Date()
        val name = "FILMKIT_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)}.dng"
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilmKit")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                ?.let { uri ->
                    context.contentResolver.openOutputStream(uri)?.use {
                        creator.writeImage(it, img)
                    }
                    cv.clear()
                    cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, cv, null, null)
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "camera", "dng.saved",
                        kv("name" to name, "out" to "${img.width}x${img.height}"),
                    )
                }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FilmKit")
            dir.mkdirs()
            File(dir, name).outputStream().use {
                creator.writeImage(it, img)
            }
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "dng.saved",
                kv("name" to name, "out" to "${img.width}x${img.height}"),
            )
        }
        creator.close()
    }

    /** 把一帧成片投给后台保存队列。主线程在这里只做两件事：快照拍摄参数、
     *  入队 —— 返回后调用方（相机回调 / 渲染回调）立刻可以继续工作。
     *  日志 photo.save_queued 与 photo.saved 的时间差 = 后台实际耗时，用于验证
     *  主线程确实没被编码阻塞。 */
    private fun queueJpegSave(bytes: ByteArray, w: Int, h: Int, extraTag: String = "") {
        val iso = liveIso
        val secNs = liveExposureNs
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "photo.save_queued",
            kv("bytes" to bytes.size),
        )
        saver.execute { saveJpeg(bytes, w, h, iso, secNs, extraTag) }
    }

    /** 后台线程执行（saver 队列）。iso/secNs = 拍摄瞬间的快照，不能用成员变量。 */
    private fun saveJpeg(
        rgba: ByteArray, w: Int, h: Int, iso: Int, secNs: Long,
        extraTag: String = "",
    ) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        // 16×16 网格采样平均亮度：诊断「管线输出是否全黑」
        var sum = 0L
        var n = 0
        val step = maxOf(1, minOf(w, h) / 16)
        var yy = 0
        while (yy < h) {
            var xx = 0
            while (xx < w) {
                val c = bmp.getPixel(xx, yy)
                sum += (android.graphics.Color.red(c) * 299 +
                    android.graphics.Color.green(c) * 587 +
                    android.graphics.Color.blue(c) * 114) / 1000  // 权重和=1000，结果 0..255
                n++
                xx += step
            }
            yy += step
        }
        // 成片按画幅居中裁切（与取景窗口一致，WYSIWYG）
        var outBmp = bmp
        // 用**快门瞬间**的画幅快照（v0.10.110-fix）：渲染 3~4s 期间用户常把手机转回
        // 竖屏，读实时 captureAspect 会把横拍裁成竖幅（2026-10-03 真机实证）。
        val asp = if (stillAspect > 0f) stillAspect else captureAspect
        if (asp > 0f) {
            val curA = w.toFloat() / h.toFloat()
            if (Math.abs(curA - asp) > 0.01f) {
                val cw = if (curA > asp) (h * asp).toInt() else w
                val ch = if (curA > asp) h else (w / asp).toInt()
                outBmp = Bitmap.createBitmap(bmp, (w - cw) / 2, (h - ch) / 2, cw, ch)
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "camera", "photo.crop",
                    kv("srcA" to "%.3f".format(curA), "dstA" to "%.3f".format(asp),
                        "out" to "${outBmp.width}x${outBmp.height}",
                        "snap" to (stillAspect > 0f), "rot" to stillRot),
                )
            }
        }
        val outW = outBmp.width
        val outH = outBmp.height
        val now = Date()
        val prefs = context.getSharedPreferences("filmkit", 0)
        // 成像质量：设置页可选 95（标准）/ 100（极高），prefs jpeg_quality
        // v0.10.68：默认 95 → 100。真机实测（平坦渐变块，相邻像素相同占比）：
        //   TPDF dither → PNG 26.9%  → JPEG q95 **31.2%**  → JPEG q100 **24.9%**
        // JPEG 的 DCT 量化把 dither 当高频噪声滤掉，且**加大 dither 幅度无效**
        //（±2/±3 LSB 在 q95 下都饱和在 28.8%）—— 只有提高编码质量能解决。
        // 这是用户报的"边缘色带"的最后一环：降噪抹平噪声 → 8bit 量化台阶
        // → dither 打散 → JPEG q95 又把 dither 滤掉。
        val jpegQ = prefs.getInt("jpeg_quality", 100)
        // ── 成片 JPEG 色度采样（v0.10.171）─────────────────────────────────
        // 默认 **4:4:4**。Bitmap.compress 无采样因子参数、Android 上恒 4:2:0，
        // 而 4:2:0 的色度下采样会把强亮度边缘两侧的色度互相平均 ⇒ 中性边缘（窗框/
        // 晾衣杆/枝条 vs 过曝天空）出现紫/绿镶边。离线对照（同一渲染源、只改编码，
        // 12.5MP RAW → DCB 去马赛克 → filmic → 8bit）在中性强亮度边缘 11.6 万像素上
        // 实测编码引入的色度误差：4:4:4 p99 1.36 / max 1.94，4:2:0 p99 9.77 /
        // max 49.17（ΔC 相对无损 PNG）⇒ 边缘串色误差的 ~86–96% 由 4:2:0 贡献。
        // 侧证：同机同量化表的 PhotonCamera 成片 SOF0 为 4:4:4（FilmKit 为 4:2:0）。
        // 依据与体积/耗时实测见 /tmp/jpeg444/REPORT.md；false = 旧行为（4:2:0）。
        val jpeg444 = prefs.getBoolean("jpeg_444", true)
        // 编码一次（下面两个落盘分支只差写入目标）；native 失败返回 null ⇒ 回退
        // Bitmap.compress（宁可退到 4:2:0，不可丢成片）。
        val encT0 = System.nanoTime()
        // runCatching 兜住 UnsatisfiedLinkError（老 .so 被缓存等极端情形）：任何失败都退
        // 回 Bitmap.compress，绝不让编码环节把成片整张丢掉。
        val jpegBytes: ByteArray? =
            if (jpeg444) runCatching {
                EngineBridge.nativeEncodeJpeg444(outBmp, jpegQ)
            }.getOrNull() else null
        val encMs = (System.nanoTime() - encT0) / 1_000_000
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "photo.encode",
            kv("chroma" to (if (jpegBytes != null) "444" else "420"),
                "q" to jpegQ, "ms" to encMs, "bytes" to (jpegBytes?.size ?: 0)),
        )
        // LUT 名写入文件名（设置页开关，prefs lut_in_name，默认开）：便于事后分辨哪张用了哪套风格；
        // 未选 LUT 时无后缀。只保留字母/数字/下划线/连字符（中文名照常保留），避免非法路径字符。
        val lutTag = currentLut?.title
            ?.takeIf { prefs.getBoolean("lut_in_name", true) }
            ?.replace(Regex("[^\\p{L}\\p{N}_-]"), "")
        // extraTag = 诊断标签前置（如 nn_ab 第二遍的 "_nn"）——同秒落盘的两张
        // A/B 成片靠它区分文件名（MediaStore 同名会自动加 (1)，靠后缀更可读）
        val suffix = extraTag + if (lutTag.isNullOrEmpty()) "" else "_$lutTag"
        val name = "FILMKIT_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)}$suffix.jpg"
        // 4:4:4 走 native 字节流，4:2:0 / native 失败走 Bitmap.compress
        val writeJpeg: (java.io.OutputStream) -> Unit = { os ->
            if (jpegBytes != null) os.write(jpegBytes)
            else outBmp.compress(Bitmap.CompressFormat.JPEG, jpegQ, os)
        }
        try {
            var savedUri: android.net.Uri? = null
            var savedFile: File? = null
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilmKit")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                context.contentResolver
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?.let { uri ->
                        lastPhotoUri = uri
                        context.contentResolver.openOutputStream(uri)?.use {
                            writeJpeg(it)
                        }
                        cv.clear()
                        cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                        context.contentResolver.update(uri, cv, null, null)
                        savedUri = uri
                    }
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FilmKit")
                dir.mkdirs()
                File(dir, name).outputStream().use {
                    writeJpeg(it)
                }
                savedFile = File(dir, name)
            }
            // Bitmap.compress 不写任何元数据——用 ExifInterface 补写拍摄参数
            writeExif(savedUri, savedFile, now)
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "photo.saved",
                kv(
                    "name" to name, "w" to outW, "h" to outH,
                    "avgLuma" to (sum / n.coerceAtLeast(1)),
                    "iso" to iso, "sec" to "%.4f".format(secNs / 1e9f),
                ),
            )
        } catch (t: Throwable) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "photo.save_failed",
                kv("error" to (t.message ?: t.toString())),
            )
        } finally {
            bmp.recycle()
            if (outBmp !== bmp) outBmp.recycle()
        }
    }

    /** 补写 EXIF：机型/时间/ISO/快门/光圈/焦距（35mm 等效）/白平衡模式 */
    private fun writeExif(uri: android.net.Uri?, file: File?, whenShot: Date) {
        runCatching {
            // 可写 FileDescriptor 构造的 ExifInterface 才支持 saveAttributes
            val pfd = uri?.let { context.contentResolver.openFileDescriptor(it, "rw") }
            val exif = when {
                pfd != null -> androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                file != null -> androidx.exifinterface.media.ExifInterface(file.absolutePath)
                else -> return
            }
            val dt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(whenShot)
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME, dt)
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, dt)
            // 成片像素在渲染端已转正，方向恒为标准值 1（默认 0 是非法定义值）
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, "1")
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL, Build.MODEL)
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                liveIso.coerceAtLeast(0).toString(),
            )
            // ExposureTime 属 ExifInterface 的「兼容标签」：setAttribute 内部走 Double.parseDouble，
            // 传 "1/125" 这类分数串会抛 NumberFormatException 被**静默丢弃**（Log.w 后 return，成片由此一直缺 33434）。
            // 只能传十进制秒数，由 ExifInterface 自行转 Rational；取不到实测值回退用户设定值。
            val nsForExif = if (liveExposureNs > 0L) liveExposureNs else exposureNs
            if (nsForExif > 0L) {
                exif.setAttribute(
                    androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME,
                    "%.6f".format(Locale.US, nsForExif / 1e9),
                )
            }
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER,
                "%.1f".format(apertureF.coerceAtLeast(1f)),
            )
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH,
                "%.1f/1".format(baseFocalMm * zoomRatio),
            )
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                (currentLensEquiv() * zoomRatio).roundToInt().toString(),
            )
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_WHITE_BALANCE,
                if (wbMode == CaptureRequest.CONTROL_AWB_MODE_AUTO) "0" else "1", // EXIF: 0=Auto 1=Manual
            )
            exif.saveAttributes()
            pfd?.close()
        }.onFailure {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "camera", "photo.exif_failed",
                kv("error" to (it.message ?: it.toString())),
            )
        }
    }
}
