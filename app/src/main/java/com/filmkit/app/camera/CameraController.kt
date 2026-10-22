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
import com.filmkit.app.hdr.HdrDelivery
import com.filmkit.app.hdr.PlatformGainmapWriter
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
     *  由 HAL AE 决定、没有欠曝），故两侧中间调一致、成片高光更低。
     *  U-7：偏移已在 `filmParams[0]` 里（exposureEv()），这里只加欠曝量 —— 别再叠一次偏移。 */
    private fun filmParamsForStill(): DoubleArray {
        val u = burstHlUnderEv
        if (u <= 1e-4) return filmParams
        val q = filmParams.copyOf()
        q[0] = filmParams[0] + u
        return q
    }

    /**
     * 成片参数快照（E1-2 / ADR-4）：[film] 必须是**真正下发给引擎的那份数组**
     *（调用点就在下发语句旁边），这里立刻 `copyOf()` 冻结 —— 后台落盘线程执行时用户
     * 可能已经改了参数，晚读一秒就是另一张片子的参数。
     *
     * lut id 取 prefs `lut_id`（`LutLibrary` 的 id 命名空间，编辑器靠它反查资产）；
     * 引擎没收到 LUT 纹理（[currentLut] == null，如资产解析失败）时记空串 —— 快照必须
     * 描述"这张片子实际用了什么"，不能记一个没生效的 id。强度直接用下发数组的 [34]，
     * 不另立来源（applyLutStrengthPref 就是写这一槽）。
     */
    private fun snapshotOf(film: DoubleArray): StillSnapshot = StillSnapshot(
        params = film.copyOf(),
        lutId = if (currentLut != null) appPrefs.getString("lut_id", "").orEmpty() else "",
        lutK = if (film.size > 34) film[34] else 1.0,
    )

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
    /**
     * 拍照瞬间的**数码变焦**快照（1 = 不裁）。
     *
     * 为什么需要我们自己裁：M 档（`raw_mode`）的静拍流是 **RAW_SENSOR**，X300 的 HAL
     * 对 RAW 流不执行 `CONTROL_ZOOM_RATIO`（预览/YUV 都执行）⇒ 非原生焦段（例如 24mm
     * 镜头拍 46mm）预览是裁过的、成片却是传感器全幅 —— 用户 2026-10-22 报"非原生焦段
     * 裁切在 M 档从没生效过"。交付前按中心裁 `1/z` 补上，与预览视角对齐。
     *
     * 只在 RAW 路径生效（YUV 路径 HAL 已裁，再裁就是双裁）。
     * 与 [stillRot]/[stillAspect] 同型：渲染要 3~4s，用户期间改焦段不能影响已在飞的那张。
     */
    @Volatile private var stillZoom = 1f
    /**
     * 交付档的读取点（唯一）：prefs `photo_format`，缺失/脏值 = JPEG（默认档）。
     *
     * ⚠ 卡 FIX1（2026-10-14 P0）：调用方必须在**下发点**把返回值存进**局部 val**，并把它
     * 一路传进落盘回调 —— **不得**存进成员变量再在回调里读。成员会被"渲染期间的第二张拍摄 /
     * 用户改设置后的下一次冻结"覆盖，而回调要等 1~4s；本项目 [stillRot]/[stillAspect] 已经
     * 因为同型错误翻过一次车（见其注释）。曾经的 `stillTenBit` 成员即因此被删。
     */
    private fun tenBitForCapture(): Boolean =
        com.filmkit.app.heif.PhotoFormat.read(context.getSharedPreferences("filmkit", 0)) ==
            com.filmkit.app.heif.PhotoFormat.HEIF

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

    /**
     * 最近一张成片的 MediaStore URI（胶卷位缩略图用）；API<29 分支不记录。
     *
     * E1-2 补丁（勘察报告 D9）：**同时持久化到 prefs** —— 纯内存 var 冷启动即丢，
     * 胶卷位退化成占位符 ⇒ 编辑器入口点不开。冷启动没有内存值时回落到 prefs 里那条；
     * 失效 URI（照片被删/迁移）由消费侧的失败态兜住，这里不做存在性校验（避免阻塞 IO）。
     */
    @Volatile
    var lastPhotoUri: android.net.Uri? = null
        get() = field ?: restoredPhotoUri
        private set

    /** 冷启动回落值：prefs 里最后一张成片的 URI。用 lazy 是因为 [appPrefs] 声明在
     *  本属性**之后**（构造期读会踩未初始化）；首次访问发生在 UI 轮询里，只读一次。 */
    private val restoredPhotoUri: android.net.Uri? by lazy {
        appPrefs.getString("last_photo_uri", null)?.let {
            runCatching { android.net.Uri.parse(it) }.getOrNull()
        }
    }

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
        // U-7：再加**预设偏移**（方案 B）—— 用户调的是自己的基准，预设的曝光叠在上面，
        // 不因这次调整被冲掉（也不冲掉用户）。
        // v0.10.22：语义 = **filmic 前线性域曝光**（PC/Darktable BaselineExposure 语义，
        // 进曲线改工作点），与预览分支统一——此前链末乘性与预览分叉，且后置乘性
        // 只平移亮度、黑位/对比不动（−0.3EV 真机复拍仍灰）。auto 黑点已默认关，
        // v0.7.7"统计归一"的旧顾虑不再成立。
        filmParams[0] = exposureEv()
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
                    // v0.12.32：**基础曝光补偿**（全局设置，默认 0.7，与预设/界面 EV 都无关）。
                    // 此前日志里没有它 ⇒ "暗部偏亮"排查时无法区分"引擎提亮"与"用户基线"，
                    // 只能靠推断。它与 aeEv 是**相加**关系：实际提亮 = base_ev + 界面EV + AE。
                    "baseEv" to "%.2f".format(baseEvPref),
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

        /** bracket 实际曝光比（长帧/短帧）。**必须用 CaptureResult 读回值**：HAL 可能
         *  把短帧曝光 clamp 到下限，请求的 4× 不等于实际。返回 1.0 = 无短帧/无效
         *  （调用方据此回退到同曝光路径，设计 §3.5）。 */
        fun bracketRatio(shortNs: Long, longNs: Long): Float {
            if (shortNs <= 0 || longNs <= 0 || longNs <= shortNs) return 1f
            val r = longNs.toDouble() / shortNs
            return if (r in 1.5..16.0) r.toFloat() else 1f
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

        /**
         * 强边缘色度门 κ 的 prefs 兼容读取（v0.10.174 是 bool 开关 `chroma_gate`：
         * true→2.0 / false→0.0；v0.10.177 迁为 float 0..2、步进 0.1、默认 2.0，
         * 0 = 关）。SharedPreferences 的 getFloat 读到 Boolean 存储会抛
         * ClassCastException，故**所有读取端必须走本函数**：用 prefs.all 做类型
         * 分派（不触发类型转换），首次从设置页写入即为 float（同 key 覆盖类型）。
         * κ 的 shader 语义不变（[51]>0 的行为照旧）；κ 最优值未定（评估方向可能
         * 调低），由真机拖动观察定值。纯函数供单测。
         */
        fun chromaGatePrefValue(raw: Any?): Float = when (raw) {
            is Boolean -> if (raw) 2f else 0f
            is Number -> raw.toFloat().coerceIn(0f, 2f)
            else -> 2f
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

    /**
     * 套用一套完整参数（预设 / 冷启动恢复的唯一入口）。
     *
     * @param evOffset **U-7：`p[0]` 的相对偏移**（EV）—— 预设自带曝光时的语义（方案 B，
     *   `preset-DESIGN.md` §9.4）：`[0] = base_ev + 用户 EV + 本偏移`。调用侧用
     *   [presetEvOffset] 从预设的 slots 提取，标准 / 不含曝光的预设传 0.0。
     *   ⚠ **不从 `p[0]` 读偏移**：本函数随即把那一槽整槽覆盖，且 `filmParams = p` 是引用赋值
     *   （ADR §5.1 I-5）—— 从数组槽反推偏移会随"调用方复用/缓存数组"而静默漂移（第二次
     *   套用把上次算出的绝对值当成新偏移 = 曝光翻倍）。显式参数让来源只有一个。
     *   ⚠ **无默认值**是有意的：漏传 = 编译错误，而不是静默丢掉预设曝光。
     */
    fun setFilmParams(p: DoubleArray, evOffset: Double) {
        // E4-1 长度兜底（契约第四处，ADR-1）：短于 kCount 的数组**拒绝并记日志**，
        // 不抛异常 —— 本函数由预设切换（CameraScreen）直接调用，抛异常 = 相机页崩溃；
        // 保持旧参数只是"这次预设没生效"，可恢复（用户红线：失败不以丢照片为代价）。
        // 历史事故（v0.10.196）：kCount 与 neutral() 长度不同步时，越界写就发生在
        // 本函数下方那些 applyXxxPref() 的 filmParams[i] 裸写里，且崩在启动路径上。
        if (p.size < CameraPresets.COUNT) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "camera", "film_params.short",
                kv("got" to p.size, "want" to CameraPresets.COUNT),
            )
            return
        }
        // U-7：**无条件覆盖**（不是"有值才更新"）⇒ 切到不含曝光的预设或「标准」时偏移
        // 必然归零，不会残留上一条预设的曝光（evOffset 已由 presetEvOffset 归一为 0.0）。
        presetEvOffset = evOffset
        filmParams = p
        // [0] = 基准曝光 + 用户 EV + 预设偏移：预设基线里是 0，换数组后必须回填，否则初始化
        // 序列会把曝光静默清零（v0.7.8 教训）。applyBaseEvPref 先把 prefs 的基准值灌进
        // baseEvPref，再统一写入（v0.8.1 起含基准偏置；U-7 起含预设偏移）。
        applyBaseEvPref()
        filmParams[0] = exposureEv()
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

    /**
     * 拍照链的引擎档位（设置页三档，prefs `engine_override`）：[ENGINE_OVERRIDE_FOLLOW_PRESET]
     * = 跟随预设（默认）/ `0` = 强制 filmic / `1` = 强制 sigmoid。
     *
     * **热更新**：每次下发前读一次 prefs —— 不留缓存 ⇒ 没有陈旧档位，设置页改档
     * **下一次下发即生效**（预览帧 / 快门 / 连拍），无需重启 App。
     * （`SharedPreferences` 首次 load 后是内存读，且 UI 侧 `apply()` 会先更新内存再落盘，
     *  同进程立即可见。）
     *
     * ⚠ **只作用于拍照链**。编辑器链路（`EditorEngineSession.submit`）调 [engineScoped] 时不
     * 传 override —— 编辑一张照片要用它**拍摄时**的引擎（照片元数据里的 `[98]`），
     * 否则编辑旧照片会因设置页开关突然变样。
     */
    private fun engineScopedForCapture(p: DoubleArray): DoubleArray =
        engineScoped(p, appPrefs.getInt("engine_override", ENGINE_OVERRIDE_FOLLOW_PRESET))

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
    // 用户 EV 滑杆（evSteps）叠加在其上：[0] = base + user（U-7 起再加预设偏移，
    // 唯一算法在 exposureEv()）。
    // v0.10.22：作用点移到 filmic 前（曲线工作点语义，与 PC 的 Linear 域 EV 同构）。
    // v0.10.25：默认 +0.70 —— 同 DNG 双渲染实测（205833/205832，中性区净差 +0.57EV、
    // 红杯 C* 差 14.5%）标定：PC 链首恒乘 0.7EV 而我们为 0，工作点上移是色度差的主项。
    private var baseEvPref = 0.7

    /**
     * U-7：**当前预设带来的曝光偏移**（EV，`FilmParams[0]` 的相对量；标准 / 不含曝光的预设 = 0）。
     *
     * 语义（方案 B，`preset-DESIGN.md` §9.4，用户 2026-10-11 拍板）：预设的 `[0]` 不再被覆盖层
     * 丢掉、也不覆盖用户的曝光补偿，而是**叠在用户基准之上** —— 拍前能看到预设的曝光效果，
     * 用户继续拖 EV 时偏移保留（他调的是自己的基准）。
     *
     * 载体与冷启动：本字段由 [setFilmParams] 的 `evOffset` **无条件**赋值 —— 冷启动时
     * `CameraScreen` 由 prefs `camera_preset_id` 恢复选中预设（[resolveCameraPreset]），再用
     * [presetEvOffset] 从该预设的 slots 重算偏移 ⇒ 偏移跟着既有那条持久化路径一起回来，
     * 不需要新 key（也顺带避开"预设被删后 prefs 里残留偏移"这类脏状态）。
     */
    private var presetEvOffset = 0.0

    /** 拍照侧 `[0]` 的唯一算法（基准 + 用户 EV + 预设偏移）：写 `[0]` 与"将要施加的提亮"
     *  判据都必须读它 —— [effectiveExposureEv] 是本式唯一的定义点。 */
    private fun exposureEv(): Double =
        effectiveExposureEv(baseEvPref, (evSteps * evStep).toDouble(), presetEvOffset)

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
    // v0.10.178：RCD P2(G@RB)/G 位色度段回归原版（FilmParams[56]）。1 = 原版
    //（P2 无四邻 G 范围限幅；G 位轴向填充 ±1 近对 + 三项梯度反比、无限幅）；
    // 0 = 旧行为（v0.5.28/30 的 clamp/0.75·0.25 混合）。默认 0 = 旧行为逐位不变。
    private val RCD_G_ORIG = 1.0
    // v0.10.134：逆光边缘紫边抑制（FilmParams[47]，RawTherapee Defringe 同形态）
    private val DEFRINGE = 1.0
    // v0.10.169 引入，v0.12.4 转正为**算法选择**（FilmParams[49]）。
    // 1 = 自创档（kFsHlApply 的 G 三级池化比 + allSat 三通道改写，用满阱 luma 当锚）；
    // 0 = darktable 原版档（`MAX(inval, refavg + chrominance)`，只抬不压）。
    // 默认 0，见 applyHlGReconPref 的依据说明。
    private val HL_GRECON = 1.0
    // v0.10.179：高光重建·R/B 满阱反推（FilmParams[57]）。1 = 启用 kFsHlApply 内
    // 新支 —— clip 过渡带"本通道（R/B）满阱、其余未满"的 photosite（allSat 与
    // 普通 G 反推都够不着）按三级参考比反推替换（替换语义，与 allSat 分支哲学
    // 同源）；治亮暗交界 CCM 后的品红窄带。0 = 旧行为逐位不变（默认）。
    private val HL_RB_RECON = 1.0
    // v0.10.196：色差域 3×3 中值滤波（darktable color_smoothing 移植）的启用值，
    // 写 FilmParams[59]。1 = 开（默认），0 = 关（旧行为逐位不变）。
    private val COLOR_SMOOTH = 1.0
    // v0.10.180：RCD P3 拆双 pass（FilmParams[58]）。1 = 色度段拆 3a（对角填充）
    // → 3b（G 位轴向填充，交叉轴 ±1 读 3a 已填充色），恢复原版 STEP 4.3 四正邻
    // 双轴估计 —— 单 pass 版并行读不到对角段结果 ⇒ B 缓 G 陡错位放大 2×
    //（品红峰 +43.8 → +85.6 WB 域，docs/research/11 号 §8）。
    // 0 = 旧行为逐位不变（默认）。
    private val RCD_P3_SPLIT = 1.0
    // v0.10.170：镜头横向色差校正（FilmParams[50]）。作用在 D3（RCD 去马赛克后 /
    // CCM 前）相机 RGB 域：自动估计（1/8 降采样 + 径向 Lucas-Kanade）+ 径向位移
    // 重采样 R/B（G 逐位不动）。判据：角部位移 ≥ 0.5px 且内外半区拟合一致，
    // 否则该通道不动作；两通道都不动作 ⇒ 整段跳过（走原 tex_work_）。
    private val CA_CORR = 1.0
    // v0.10.176：PC ESD3D2 结构开关（FilmParams[54] / [55]，prefs `pc3d` /
    // `pc3d_msize`）—— 引擎侧 v0.10.175 已完整实现，但此前**没有设置页入口 ⇒
    // 用户无法打开**；本版只补 UI + 下发通路，不动引擎。
    // [54]：0 = 关（⇒ 与 v0.10.174 逐位相同）/ 1 = 替换档（旁路 P4/D-1）/
    //       2 = 叠加档（**默认**，v0.10.177：2026-10-05 新 KPI 重估 ⇒ 叠在
    //       现有色度降噪之上）。
    // [55]：0 = 自动（照抄 PC 公式）/ 7 / 11 / 15 / 21 = 强制核尺寸（奇数）。
    // 代价与离线数据见 FilmParams.h 的 [54]/[55] 注释；真机判据 = 日志字段
    // pc3dOn / pc3dM / pc3dK / pc3dSc / pc3dMs。
    private var pc3dPref = -1.0
    private var pc3dMsizePref = -1.0
    private var chromaGatePref = -1.0
    private var rcdCdFixPref = -1.0
    private var rcdGOrigPref = -1.0
    private var defringePref = -1.0
    private var caCorrPref = -1.0
    private var hlGreconPref = -1.0
    private var hlRbReconPref = -1.0
    private var colorSmoothPref = -1.0
    private var rcdP3SplitPref = -1.0
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
        applyRcdGOrigPref()
        applyHlRbReconPref()
        applyColorSmoothPref()
        applyRcdP3SplitPref()
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
        // ── v0.12.28：**默认关**（暗部过亮的元凶，已用 PC 源码核对）──────────────
        // 我们这一层是"暗部线性提亮 25%"，而 PC 的对应机制 ShadowsHighlightsParams
        // 默认是 (highlights=0, shadows=0)，即**它默认不做局部提亮**。
        // 真机同机位对比（185405 vs PC 184508）：两边 sigmoid 参数几乎逐位相同
        //（0.3597/0.00138/1.4909 vs 0.3544/0.00143/1.5），但暗部差 1.5~2EV
        //（p1 14.4 vs 3.6、p5 40.0 vs 26.9）—— 差异就在这层。
        // 想恢复旧观感可在设置里打开；默认跟 PC 一致。
        val v = if (appPrefs.getBoolean("local_tone", false)) LOCAL_TONE else 0.0
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
     * RCD P2(G@RB)/G 位色度段回归原版开关（v0.10.178）：写 FilmParams[56]。
     * 1 = 原版行为（P2 不做四邻 G 范围限幅；G 位轴向填充只用 ±1 近对 +
     * 三项梯度反比、无 ±3 远对混合、无限幅）；0 = 旧行为（v0.5.28/30 的
     * clamp/0.75·0.25 固定混合）。
     * 动机（2026-10-06 原版逐行对照）：与 [46] 同型的"clamp 限幅范围取自
     * 跨边界邻居"结构在 P2/G 位仍存在，候选解释台灯逆光暗侧品红窄峰
     * （+10@3px，宽度 = 邻域半径吻合）。默认 0 = 旧行为逐位不变，1 = A/B
     * 通路；改默认前必须真机数据说话。
     */
    private fun applyRcdGOrigPref() {
        val v = if (appPrefs.getBoolean("rcd_g_orig", false)) RCD_G_ORIG else 0.0
        if (v != rcdGOrigPref) {
            rcdGOrigPref = v
            filmParams[56] = v
        }
    }

    /**
     * 高光重建·R/B 满阱反推开关（v0.10.179）：写 FilmParams[57]。
     * kFsHlApply（RAW 域高光重建的 apply 支）里新增支的总开关：clip 过渡带
     * "本通道（R/B）满阱、其余通道未满"的 photosite —— allSat 三判据够不着、
     * 普通 G 反推只管 c==1、分支① 的 max 只抬不压 ⇒ 满阱比例（WB 增益伪影）
     * 原样保留，经 CCM 后成 4~6px 品红窄带（2026-10-06 台灯样张 RAW 域定案，
     * docs/research/11 号 §6）。开 = 按三级参考比由 G 与对侧通道反推**替换**
     * （直接替换，不做 max/软门控，与 allSat 分支 v0.10.161 哲学同源：满阱
     * 原值不含场景信息）；关 = 旧行为逐位不变（默认）。
     * 改默认前必须真机数据说话。prefs("hl_rb_recon")。
     */
    /**
     * 色差域中值滤波开关（v0.10.196）：写 FilmParams[59]。
     * 移植 darktable `demosaic` 模块的 color_smoothing（src/iop/demosaicing/basics.c）：
     * 在 (R−G)/(B−G) 色差域做 3×3 中值，G 不变。它是**唯一不依赖色相判据**的边缘
     * 伪彩解法 —— defringe 只治紫/品红（色相门 + 去饱和），蓝/青/绿向的窄带伪彩
     * 一直漏网（2026-10-08 真机实测：边缘蓝向强色度 23729 个像素，195 一个没动）。
     * 中值对阶跃保边，所以真实色度边界不受影响；代价是 1px 尺度的色度细节会被
     * 抹掉（darktable 因此默认关）。我们默认开（1 次），prefs("color_smooth")。
     */
    private fun applyColorSmoothPref() {
        val v = if (appPrefs.getBoolean("color_smooth", true)) COLOR_SMOOTH else 0.0
        if (v != colorSmoothPref) {
            colorSmoothPref = v
            // 长度保护（v0.10.196 事故复盘）：v0.10.196 首发时 CameraPresets.neutral()
            // 仍是 DoubleArray(59)，这里直接越界 ⇒ 应用启动即崩。数组长度与
            // FilmParams::kCount 必须同步；此处再兜一层，任何不匹配都不崩。
            if (filmParams.size > 59) filmParams[59] = v
        }
    }

    private fun applyHlRbReconPref() {
        val v = if (appPrefs.getBoolean("hl_rb_recon", false)) HL_RB_RECON else 0.0
        if (v != hlRbReconPref) {
            hlRbReconPref = v
            filmParams[57] = v
        }
    }

    /**
     * RCD P3 拆双 pass 开关（v0.10.180）：写 FilmParams[58]。
     * 1 = 色度段拆 3a（R/B 位对角填充 = 原版 STEP 4.2）→ 3b（G 位轴向填充，
     * 交叉轴 ±1 从 3a 读已填充的对侧色），恢复原版 STEP 4.3 的四正邻双轴
     * 估计 —— 单 pass 版并行读不到本 pass 内对角段结果 ⇒ 原版就有的 B 缓 G
     * 陡错位（品红峰 +43.8 WB 域）被放大 2 倍（+85.6），治亮暗交界品红窄带
     *（docs/research/11 号 §8）。拍照约慢 0.2 秒（日志 rcdAMs/rcdBMs）。
     * 0 = 旧行为逐位不变（默认）；1 = A/B 通路，改默认前必须真机数据说话。
     * prefs("rcd_p3_split")。
     */
    private fun applyRcdP3SplitPref() {
        val v = if (appPrefs.getBoolean("rcd_p3_split", false)) RCD_P3_SPLIT else 0.0
        if (v != rcdP3SplitPref) {
            rcdP3SplitPref = v
            filmParams[58] = v
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
     * 高光重建算法选择（v0.10.169 引入，v0.12.4 转正）：写 FilmParams[49]。
     * 0 = **darktable 原版档（默认）**：`hlreconstruct/opposed.c` 口径
     *     `output = MAX(inval, refavg + chrominance)` —— **只抬不压**，
     *     色比失衡由 ③ 统计出的加性 chrominance 补偿。
     * 1 = **自创档**（0.12.3 及以前的行为）：kFsHlApply 内 G 三级池化比 +
     *     allSat 三通道同步改写，用**满阱 luma** 当亮度锚。
     * v0.12.4 改默认为 0 的依据：真机 164553 量化光带亮核 chroma 2.17% vs 紧邻
     * 外圈 18.83%（同一束光差 8.7 倍），而自创 allSat 是唯一会**压低**已有值的支路
     *（opposed 的 MAX 语义不会）⇒ 灰斑 = 自创分支把 sat-fix 真值（可达 4.0）压回
     * 满阱（≈1.27）并去色。设置页开关写 prefs("hl_grecon")。
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
     * （详见 docs/reports/cacorr-REPORT.md）。
     */
    private fun applyCaCorrPref() {
        val v = if (appPrefs.getBoolean("ca_corr", true)) CA_CORR else 0.0
        if (v != caCorrPref) {
            caCorrPref = v
            filmParams[50] = v
        }
    }

    /**
     * 强边缘色度门 κ（v0.10.174 开关 → v0.10.177 可调滑杆）：prefs `chroma_gate`
     * （float 0..2、步进 0.1、默认 2.0；旧 bool true→2.0 / false→0.0 兼容见
     * [chromaGatePrefValue]）直写 FilmParams[51]，夹取 0..2。
     * 0 = 关 ⇒ P4/D-1 的门整段不执行，输出逐位回到 v0.10.172；κ=2 沿用 PC 的
     * 0.2 标定。κ 越大强边缘处色度平滑越强；代价 = 高饱和彩边去饱和（κ=2 时
     * 25~37%）。κ 最优值未定（评估方向可能调低），由真机拖动观察定值后再固化。
     * 真机 A/B 判据：同场景各 κ 一张，比 diag.stats 的强边缘紫边与日志字段
     * cgK/cgCap/cgScope/cgMean/cgP95/cgCov（**门控覆盖率**：健康区间 cgMean ≤ 0.15、
     * cgCov ≤ 0.05；v0.10.173 的旧口径下这两个数是 100%）。cgK 本就回读 [51]
     * 实际值，κ 任意值下语义不变。若真机仍见色块，拖回 0（关）即逐位回退（已验证）。
     */
    private fun applyChromaGatePref() {
        val v = chromaGatePrefValue(appPrefs.all["chroma_gate"]).toDouble().coerceIn(0.0, 2.0)
        if (v != chromaGatePref) {
            chromaGatePref = v
            filmParams[51] = v
        }
    }

    /**
     * PC 降噪结构（ESD3D2，v0.10.175 引擎 / v0.10.176 设置页入口）：prefs `pc3d`
     * （0 关 / 1 替换档 / 2 叠加档）写 FilmParams[54]，prefs `pc3d_msize`
     * （0 自动 / 7 / 11 / 15 / 21）写 FilmParams[55]。
     * ⚠ 默认 [54] = 2 = **叠加档**（v0.10.177 改，2026-10-05 新 KPI 重估
     * docs/reports/pc3d-reval-REPORT.md，待真机 pc3dMs/画质验证后定稿）、[55] = 0 =
     * 自动；[54] = 0 时与 v0.10.174 逐位相同。
     * ⚠ getInt 的 fallback 默认必须与 CameraPresets.neutral() 的 [54] 同步
     * （都 = 2）：prefs 无记录的升级用户落叠加档，"默认开"才不落空；
     * 已写 prefs 的用户保持其显式选择（含显式选"关"）。
     * 越界兜底：`pc3d` 不在 0..2、`pc3d_msize` 不是 0 或 7..21 的奇数 ⇒ 归 0
     * （防手改 prefs / 旧值把非法核尺寸喂给引擎；显式写入的非法值走安全侧 = 关，
     * 与"无记录用默认"是两回事）。
     * 真机判据：日志 pc3dOn=1 且 pc3dM∈[7,21]、pc3dSc=1..4 ⇒ 生效；pc3dOn=0 而
     * [54]>0.5 ⇒ shader 编译/链接或纹理分配失败（pc3d.compile_fail）；
     * pc3dMs 与 dnMs 同量级 ⇒ 成本正常，pc3dMs ≫ dnMs ⇒ 过强（把核尺寸调小）。
     */
    private fun applyPc3dPref() {
        val v = appPrefs.getInt("pc3d", 2).let { if (it in 0..2) it else 0 }.toDouble()
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
        // 数码变焦快照（见 stillZoom 注释）：只有 RAW 路径需要我们补裁
        stillZoom = if (rawMode) zoomRatio else 1f
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

    // ── bracket（期 B，docs/engine-bracket-design.md）────────────────────
    // 1 短 3 长：帧 0 = t_long / 2^k（ISO 与长帧相同，只改曝光时间），帧 1-3 = 长帧。
    // **第 1 步只采集 + 记录**：短帧不参与融合（融合用 3 长帧，出片零风险），
    // 归一与饱和感知权重在第 2 步落地（设计 §7 步骤 1/2）。
    // 2^k，k=2（设计 §3.1）。v0.11.87：改成**按场景自适应**（见 updateBracketDiv）——
    // 真机实测短帧在多个明亮场景里自己也饱和（08:27:35 p999 1.00、10:00:01 p995 1.00），
    // 那时 k=2 已经采不到更多高光，再融合只是猜。反馈来自上一张的 bracket.short p999。
    private val BRACKET_DIV_BASE = 4.0f
    private val BRACKET_DIV_MIN = 2.0f
    private val BRACKET_DIV_MAX = 8.0f
    @Volatile private var bracketDiv = BRACKET_DIV_BASE
    // 短帧在 burst 里的**唯一**索引来源（0.11.69：此前结果回调硬编码 secs[0]/secs[1]
    // 读 ratio，短帧移到帧 3 后读到两个长帧 ⇒ ratio=1.0 ⇒ bracket 静默失效，
    // 日志只剩 mfnr.bracket_fallback。改位置只改这一处 + 引擎的 frame_scale[3]）
    private val BRACKET_SHORT_IDX = MFNR_FRAMES - 1
    private var burstBracketOn = false    // 本次 burst 是否采了短帧
    private var burstShortRatio = 1.0f    // 实际 长/短 曝光比（1 = 无短帧/回退）

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
        //
        // ⚠ v0.11.62：自动曝光（渲染端、本张统计）开启时，这一层必须**旁路**。
        // 两层压的是同一件事：采集端欠曝（丢光子）→ 补偿 EV → 渲染端又按本张 P99.9
        // 再压一次 = 双重欠曝。真机 2026-10-20 实证：同场景第一张（无统计，只有渲染
        // 端）亮度可接受，第二张（采集欠 1.74EV + 渲染再压）明显更暗 —— 同场景两张
        // 不一致，正是这层叠加造成的。
        // 职责划分：ae_auto 开 ⇒ 采集不动（光子全收），曝光交给渲染端；
        //           ae_auto 关 ⇒ 回到旧口径，采集端高光保护可用。
        val aeAutoOn = runCatching {
            context.getSharedPreferences("filmkit", 0).getBoolean("ae_auto", true)
        }.getOrDefault(true)
        // bracket（期 B）：开关默认关（第 4 步才转默认开）。开启时短帧自带高光余量，
        // 采集端高光保护必须同时旁路，否则"短帧 + 欠曝"又是一层双重压。
        // 设计 §7 步骤 4：判据回归后转默认开（2026-10-20 0.11.77）
        val bracketPref = expPrefs.getBoolean("bracket", true)
        val hlOn = expPrefs.getInt("hl_protect", 1) >= 1 && !aeAutoOn && !bracketPref
        val hlSceneEv = if (hlOn) sceneHighlightEv() else Double.NaN
        // U-7：必须走 exposureEv()（含预设偏移）—— 这是"将要施加给成片的提亮"，漏掉预设
        // 那部分就会少算 EV，高光保护判据放行 → 成片高光裁切。
        val hlCompEv = exposureEv()
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

        // ── bracket 决策（期 B §3.1）────────────────────────────────────
        // 短帧 = t_long / 4，ISO 与长帧相同（曝光量只靠快门，噪声模型不动）。
        // 短帧放帧 0：它是最锐的帧（参考帧候选）。bracket 开时停用"参考帧压短"
        // （v0.10.1 的 isoRef 补偿）—— 两者都在改帧 0 的曝光，叠加会互相抵消。
        // 闪光灯场景禁用（频闪下各帧亮度不一致，设计 §6）。
        val expLower = curCharacteristics
            ?.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?.lower ?: 0L
        val shortSec = if (capSec > 0) maxOf(expLower, (capSec / bracketDiv).toLong()) else 0L
        val bracketActive = bracketPref && flashMode == 0 && capIso > 0 &&
            shortSec > 0 && capSec >= shortSec * 3 / 2
        burstBracketOn = bracketActive
        burstShortRatio = 1.0f
        if (bracketActive) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "mfnr.bracket",
                kv(
                    "on" to true,
                    "sec" to "%.4f".format(capSec / 1e9f),
                    "short" to "%.4f".format(shortSec / 1e9f),
                    "reqRatio" to "%.3f".format(capSec.toDouble() / shortSec),
                    "iso" to capIso, "k" to bracketDiv,
            "div" to "%.2f".format(bracketDiv),
                ),
            )
        }

        val isoTouchesTop = capIso >= isoUpperGate        // 触顶 = 暗光
        val isoHeadroomOk = capIso * 3 / 2 <= isoUpperGate  // isoRef 不会被 clamp
        val canShorten = !bracketActive && !longExposureOn && capIso > 0 && capSec > 0 &&
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
        // bracket 短帧请求（帧 0）：AE_OFF + 长帧 ISO + t_long/4
        val shortReq = if (bracketActive) {
            camera!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(stillReader!!.surface)
                applyControls(this, distort = true)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, capIso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, shortSec)
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
        // 短帧放**最后一帧**，长帧作参考帧（帧 0）。设计 §3.1 原写"短帧帧 0 作参考帧"，
        // 2026-10-20 真机实测否定：短帧归一后噪声 ×ratio，而参考帧在两条融合路径里都是
        // 基准（DFT 式 6/7 的 r0、mismatch 的权重恒 1）⇒ 暗部被短帧噪声主导（实测
        // 0.11.67 暗部彩噪明显）。短帧作 alt 时物理上自洽：暗部 d² 大 ⇒ 向参考帧（长帧）
        // 收缩，高光处短帧 SNR 高 ⇒ d² 小 ⇒ 采短帧。
        val reqs = List(MFNR_FRAMES) { i ->
            if (i == 0) {
                (refReq ?: altReq ?: b.build())
            } else if (i == BRACKET_SHORT_IDX && bracketActive) {
                (shortReq ?: altReq ?: b.build())
            } else {
                (altReq ?: b.build())
            }
        }
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "mfnr.burst_submit",
            kv(
                "frames" to MFNR_FRAMES,
                "refIso" to isoRef, "refSec" to "%.4f".format(secRefFinal / 1e9f),
                "altIso" to capIso, "altSec" to "%.4f".format(capSec / 1e9f),
                "rn" to "%.3f".format(burstRefNoiseScale),
                // bracket（期 B）：brk=1 时帧 0 是 t_long/4 短帧，brkSec = 请求短曝光
                "brk" to (if (bracketActive) 1 else 0),
                "brkSec" to "%.4f".format(shortSec / 1e9f),
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
        copyRaw(img, slot, close = true)
    }

    /** 拷 RAW plane 到 direct buffer；close=false 时保留 Image（DNG 底片帧要两样：
     *  Image 给 DngCreator，buffer 给融合 —— 只保留 Image 会让融合少一帧，
     *  0.11.66 真机"无成像"就是这条（frames.size=3 ⇒ submitRawBurst 入口直接失败）。 */
    private fun copyRaw(img: Image, slot: Int, close: Boolean) {
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
            if (close) runCatching { img.close() }
        }
    }

    /** 短帧 RAW 抽样统计（归一化到白点）：[mean, P99.5, P99.9, 饱和占比]。
     *  bracket 第 1 步的验证判据 —— 短帧的 P99.5 必须落回白点内（<1），否则说明
     *  k=2 不够（设计 §0/§3.1）。stride=16 ⇒ 12MP 约 790k 次读取（~5ms）。 */
    private fun rawSampleStats(
        buf: java.nio.ByteBuffer, w: Int, h: Int, stridePx: Int, white: Int,
    ): FloatArray {
        val sb = buf.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val wl = white.coerceAtLeast(1).toFloat()
        val bins = IntArray(256)
        var sum = 0.0
        var n = 0
        var y = 0
        while (y < h) {
            val row = y * stridePx
            var x = 0
            while (x < w) {
                val v = (sb.get(row + x).toInt() and 0xFFFF) / wl
                sum += v
                bins[(v * 255f).toInt().coerceIn(0, 255)]++
                n++
                x += 16
            }
            y += 16
        }
        if (n == 0) return floatArrayOf(0f, 0f, 0f, 0f)
        val quant = { f: Float ->
            val target = (n * f).toInt()
            var acc = 0
            var b = 0
            while (b < 256 && acc + bins[b] < target) {
                acc += bins[b]
                b++
            }
            b / 255f
        }
        return floatArrayOf(sum.toFloat() / n, quant(0.995f), quant(0.999f), bins[255].toFloat() / n)
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
        cancelBurstTimeout()
    }

    /** 摘掉 burst 超时兜底。**必须在收满 4 帧时也调用**（v0.11.64）——
     *  否则上一张的 5s 定时器会在下一张刚开拍时触发：`onBurstTimeout` 只看
     *  `burstActive`（两张共用），会把**下一张**的 burst 误杀成单帧回退。
     *  真机 2026-10-20 14:39 实证：第一张渲染 6.6s，第二张 45.26 开拍，第一张的
     *  定时器 45.32 到期 ⇒ `mfnr.timeout images:0 waitMs:278` ⇒ 第二张走单帧、
     *  无 MFNR/无 gainmap、画面明显更暗。 */
    private fun cancelBurstTimeout() {
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
        cancelBurstTimeout() // v0.11.64：收满即摘定时器，别让它误杀下一张
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
                                    // 帧 0 保留 Image（参考帧 = 长帧，DngCreator 只吃 Image）
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
                        0, engineScopedForCapture(filmParams), fillTest,
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

    /**
     * 成片交付的**唯一分派点**（卡 FIX1 收口）。
     *
     * 为什么必须是唯一：这个分派曾散落在各条拍照路径的收尾里，MFNR burst 的那份**漏了**
     * 交付档判断、硬编码 `queueJpegSave` ⇒ HEIF 档下 native 按
     * `GL_UNSIGNED_INT_2_10_10_10_REV` 读回的 **10bit packed** 缓冲被 `ARGB_8888` 按通道
     * 错读 ⇒ 霓虹伪彩 + 边缘浮雕 + 熵爆炸（真机 37MB / 43MB，同场景正常 6.4MB）。
     * 两种格式都是 **4 B/px**（50135040 B）⇒ 长度校验永远抓不到，只能靠"交付档随缓冲走"。
     *
     * ⚠ [tenBit] 必须是**下发点冻结的局部值**（由调用方传参），不许在这里读成员 —— 见
     * [tenBitForCapture] 的注释。
     */
    /**
     * 本张是否尝试 HDR 交付（prefs `hdr_gainmap`，默认关）。
     *
     * 每张都读 prefs 而不是缓存成成员：设置页改动要**下一张立即生效**，
     * 且拍照回调在 1~4s 后才跑，缓存值会与用户当时看到的不一致。
     */
    private fun hdrEnabled(): Boolean = runCatching {
        context.getSharedPreferences("filmkit", 0).getBoolean("hdr_gainmap", false)
    }.getOrDefault(false)

    /**
     * 在**渲染线程**读回本张的 gainmap（HDR 开启时）。失败返回 null ⇒ 该张回落普通 JPEG。
     *
     * 为什么必须在这个线程：`nativeReadGainmap` 读的是该线程 EGL 上下文里的
     * `tex_gainmap_`。在别的线程调用会读到空/错的纹理（且不报错）。
     */
    private fun readGainmapSnapshot(): GainmapSnapshot? {
        if (!HdrDelivery.jpegRSupported) return null
        return runCatching {
            val gw = EngineBridge.nativeGainmapWidth()
            val gh = EngineBridge.nativeGainmapHeight()
            if (gw <= 0 || gh <= 0) return null
            val data = EngineBridge.nativeReadGainmap() ?: return null
            if (data.size < gw * gh) return null
            GainmapSnapshot(data, gw, gh)
        }.onFailure {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "camera", "hdr.gainmap_read_failed",
                kv("error" to (it.message ?: it.toString())),
            )
        }.getOrNull()
    }

    /**
     * 按成片的裁剪区域同步裁 gainmap（1/2 坐标）。
     *
     * 越界/尺寸异常返回 null ⇒ 该张回落普通 JPEG（宁可没有 HDR，不可错位 HDR）。
     */
    /**
     * 本张成片还要不要按**数码变焦**裁切（v0.12.34）。
     *
     * M 档 RAW 的静拍流不走 HAL 的 `CONTROL_ZOOM_RATIO`（见 [stillZoom]），所以非原生
     * 焦段必须在交付前自己裁；YUV 路径的 zoom 由 HAL 做，传进来的 zoom 恒 1。
     *
     * 自愈判据：若 HAL 某天真的对 RAW 也做了裁切，输出宽度会掉到 `全幅/z` 附近 ——
     * 这时再裁就是双裁（成片比预览还窄）。所以先比一次尺寸，宁可少裁不可双裁。
     *
     * @return >1 = 需要裁的倍率；1 = 不裁
     */
    private fun zoomCropFactor(w: Int, zoom: Float): Float {
        if (!(zoom > 1.001f)) return 1f
        val aw = activeArray.width().coerceAtLeast(1)
        if (w <= (aw / zoom) * 1.05f) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "photo.zoom_hal_done",
                kv("w" to w, "activeW" to aw, "z" to "%.2f".format(zoom)),
            )
            return 1f
        }
        return zoom.coerceIn(1f, 8f)
    }

    /** [cropForZoom] 的产物（后台线程用，避免在渲染回调里拷全图）。 */
    private class ZoomCrop(
        val bytes: ByteArray, val w: Int, val h: Int, val gainmap: GainmapSnapshot?,
    )

    /**
     * 居中裁到 `1/z`，并**同步裁 gainmap**（两者都是居中裁 ⇒ 同一矩形）。
     * z <= 1 或 HAL 已裁时原样返回（零拷贝）。每像素恒 4B（8bit RGBA 与 10bit packed 同）。
     */
    private fun cropForZoom(
        bytes: ByteArray, w: Int, h: Int, gainmap: GainmapSnapshot?, zoom: Float,
    ): ZoomCrop {
        val z = zoomCropFactor(w, zoom)
        if (z <= 1.001f) return ZoomCrop(bytes, w, h, gainmap)
        val cw = (w / z).toInt().coerceIn(16, w)
        val ch = (h / z).toInt().coerceIn(16, h)
        val out = ByteArray(cw * ch * 4)
        val x0 = (w - cw) / 2
        val y0 = (h - ch) / 2
        for (y in 0 until ch) {
            System.arraycopy(bytes, ((y0 + y) * w + x0) * 4, out, y * cw * 4, cw * 4)
        }
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "photo.zoom_crop",
            kv("z" to "%.2f".format(z), "from" to "${w}x${h}", "to" to "${cw}x${ch}"),
        )
        return ZoomCrop(out, cw, ch, gainmap?.let { cropGainmap(it, w, h, cw, ch) })
    }

    private fun cropGainmap(
        gm: GainmapSnapshot, srcW: Int, srcH: Int, dstW: Int, dstH: Int,
    ): GainmapSnapshot? {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return null
        val gx0 = ((srcW - dstW) / 2) / 2
        val gy0 = ((srcH - dstH) / 2) / 2
        val gw = (dstW + 1) / 2
        val gh = (dstH + 1) / 2
        if (gx0 < 0 || gy0 < 0 || gx0 + gw > gm.w || gy0 + gh > gm.h) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "camera", "hdr.gainmap_crop_oob",
                kv("gm" to "${gm.w}x${gm.h}", "crop" to "${gx0},${gy0} ${gw}x${gh}",
                    "src" to "${srcW}x${srcH}", "dst" to "${dstW}x${dstH}"),
            )
            return null
        }
        val out = ByteArray(gw * gh)
        for (y in 0 until gh) {
            System.arraycopy(gm.data, (gy0 + y) * gm.w + gx0, out, y * gw, gw)
        }
        return GainmapSnapshot(out, gw, gh)
    }

    private fun deliverStill(
        bytes: ByteArray, w: Int, h: Int, snap: StillSnapshot, tenBit: Boolean,
        gainmap: GainmapSnapshot? = null,
        zoom: Float = 1f,
    ) {
        val fmt = DeliverFormat.of(tenBit)
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "photo.deliver",
            kv("bits" to fmt.bits, "path" to (if (tenBit) "heif" else "jpeg"),
                "bytes" to bytes.size, "gainmap" to (gainmap != null)),
        )
        if (tenBit) queueHeifSave(bytes, w, h, snap, fmt, "", gainmap, zoom)
        else queueJpegSave(bytes, w, h, snap, fmt, "", gainmap, zoom)
        logStillStats(bytes, w, h, tenBit)
    }

    /** 成片亮度统计（v0.11.59 曝光归因）：一次拍照一条，直接回答"base 比 PC 亮多少"。
     *  - `sw999`：成片域 P99.9（EV 前，log2 相对中灰）—— ExposurePlanner 的高光判据
     *  - `mean`：BT.601 亮度均值（口径同 saveJpeg 的 16×16 诊断）
     *  - `hi240`：亮度 ≥240 的像素占比（%）
     *  采样 step=4（1/16 像素），12MP ≈ 76 万点；对 1% 量级占比的标准误 <0.03%，
     *  足够区分"我们 3.38% vs PC 0.37%"。10bit packed 按 4B/px 解三通道是错的 ⇒ 只统计 8bit。 */
    private fun logStillStats(bytes: ByteArray, w: Int, h: Int, tenBit: Boolean) {
        val sw = try {
            org.json.JSONObject(EngineBridge.nativeEngineGetStats() ?: "{}")
                .optDouble("sw999", 0.0)
        } catch (_: Throwable) {
            0.0
        }
        var mean = -1.0
        var hi = -1.0
        if (!tenBit) {
            val step = 4
            var sum = 0L
            var n = 0L
            var hit = 0L
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val i = (y * w + x) * 4
                    if (i + 2 < bytes.size) {
                        val l = ((bytes[i].toInt() and 0xFF) * 299 +
                            (bytes[i + 1].toInt() and 0xFF) * 587 +
                            (bytes[i + 2].toInt() and 0xFF) * 114) / 1000
                        sum += l
                        if (l >= 240) hit++
                        n++
                    }
                    x += step
                }
                y += step
            }
            if (n > 0) {
                mean = sum.toDouble() / n
                hi = 100.0 * hit / n
            }
        }
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "photo.stats",
            kv("sw999" to "%.3f".format(sw), "mean" to "%.1f".format(mean),
                "hi240" to "%.2f".format(hi), "w" to w, "h" to h,
                "bits" to if (tenBit) 10 else 8),
        )
    }

    /** 单帧静拍统一收尾（YUV / 单帧 RAW 共用）：解锁 + 预览回升 + 落盘。
     *  回调可能在预览渲染线程（直拍/回退）或拍照线程（独立线程）上——只做
     *  轻量操作，与 v0.10.85 以来 "onDone 只收尾" 的约定一致。
     *  [snap] / [tenBit] 由下发点（submitStill / tryProcessRaw）冻结后传入：本函数在回调
     *  线程上（1~4s 后），不能再去读成员 filmParams（E1-2）/ 交付档（FIX1）。 */
    private fun finishStillCapture(
        bytes: ByteArray?, w: Int, h: Int, snap: StillSnapshot, tenBit: Boolean,
        zoom: Float,
    ) {
        capturing = false
        capturePhase = 0
        releasePreviewScaleGuard()
        if (bytes != null) {
            // 卡 H2：按**冻结的交付档**分派 —— 10bit 流走 HEIF（失败回退 JPEG），
            // 8bit 流走原 JPEG 链（一字不动）。卡 FIX1：分派收口到 [deliverStill]。
            deliverStill(bytes, w, h, snap, tenBit, zoom = zoom)
        }
    }

    private fun submitStill(img: Image) {
        // 卡 H2 / FIX1：本张的交付档在**下发点**冻结成局部 val，随回调传递（不落成员）
        val tenBit = tenBitForCapture()
        // 数码变焦裁切倍率同样在**下发点**冻结（见 stillZoom 注释）
        val zoom = stillZoom
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
        val film = engineScopedForCapture(filmParams)
        // E1-2：单帧路径下发的就是 filmParams（高光保护补偿是 burst 专属），快照同源；
        // 本函数在渲染线程（ImageReader 回调）上，与 filmParams 的写同线程，无竞态
        val snap = snapshotOf(film)
        val rot = stillRot   // 快门瞬间快照（不是此刻的 orientationStep，见其注释）
        // 拍照线程路由（v0.10.93，prefs still_thread）：**非阻塞**判断 ready ——
        // 这里在渲染线程上（ImageReader 回调），绝不干等 ensureStarted；
        // 预热在 capture() 入口已 startAsync，本张没赶上就走预览线程（下一张起生效）。
        val useStill = context.getSharedPreferences("filmkit", 0)
            .getBoolean("still_thread", false) && StillRenderClient.ready
        if (useStill) {
            StillRenderClient.submitStillBuf(
                y, u, v, w, h, yStride, uvStride, uvPix, rot, film,
                tenBit = tenBit,
            ) { bytes, rw, rh ->
                if (bytes != null) {
                    finishStillCapture(bytes, rw, rh, snap, tenBit, zoom)
                } else {
                    // 拍照线程失败：回退预览线程重放（红线：失败不以丢照片为代价）
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_WARN, "camera", "render.still_fallback",
                        kv("kind" to "yuv"),
                    )
                    EngineBridge.nativeFlushLogs()
                    render.submitStillBuf(
                        y, u, v, w, h, yStride, uvStride, uvPix, rot, film,
                        tenBit = tenBit,
                    ) { b2, w2, h2 -> finishStillCapture(b2, w2, h2, snap, tenBit, zoom) }
                }
            }
        } else {
            render.submitStillBuf(
                y, u, v, w, h, yStride, uvStride, uvPix, rot, film,
                tenBit = tenBit,
            ) { bytes, rw, rh -> finishStillCapture(bytes, rw, rh, snap, tenBit, zoom) }
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
                        // bracket（期 B）：实际 ratio 以读回值为准（HAL 可能 clamp 短帧）
                        if (burstBracketOn) {
                            val s = secs.getOrNull(BRACKET_SHORT_IDX) ?: 0L
                            val l = secs.getOrNull(0) ?: 0L
                            val r = bracketRatio(s, l)
                            burstShortRatio = r
                            val ok = r >= 1.5f
                            EngineBridge.nativeAppendLog(
                                if (ok) EngineBridge.LEVEL_INFO else EngineBridge.LEVEL_WARN,
                                "camera",
                                if (ok) "mfnr.bracket_actual" else "mfnr.bracket_fallback",
                                kv(
                                    "shortSec" to "%.4f".format(s / 1e9f),
                                    "longSec" to "%.4f".format(l / 1e9f),
                                    "ratio" to "%.3f".format(r),
                                    "n" to secs.size,
                                ),
                            )
                        }
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
        // 卡 H2 / FIX1：本张的交付档在**下发点**冻结成局部 val，随回调传递（不落成员）
        val tenBit = tenBitForCapture()
        // 数码变焦裁切倍率同样在**下发点**冻结（见 stillZoom 注释）
        val zoom = stillZoom
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
        // E1-2：快照必须在三个 applyXxxPref 之后取（它们会覆盖 filmParams 的槽位，
        // 这才是真正下发的值）；本函数在渲染线程上，与 filmParams 的写同线程，无竞态
        // 阶段 3：快照与下发必须是**同一份**数组（归零后）—— 否则快照记的是没生效的值
        val film = engineScopedForCapture(filmParams)
        val snap = snapshotOf(film)
        // 拍照线程路由（v0.10.93，prefs still_thread）：非阻塞判断 ready（本函数
        // 在渲染线程上，绝不干等）；预热在 capture() 入口 startAsync。
        val useStill = prefs.getBoolean("still_thread", false) && StillRenderClient.ready
        if (useStill) {
            StillRenderClient.submitRawBuf(
                rawBuf, rawW, rawH, rawStridePx, meta, stillRot, film,
                demosaic, noiseStrength, nnDn, tenBit = tenBit,
            ) { bytes, w, h ->
                if (bytes != null) {
                    finishStillCapture(bytes, w, h, snap, tenBit, zoom)
                } else {
                    // 拍照线程失败：回退预览线程重放（红线：失败不以丢照片为代价）
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_WARN, "camera", "render.still_fallback",
                        kv("kind" to "raw"),
                    )
                    EngineBridge.nativeFlushLogs()
                    render.submitRawBuf(
                        rawBuf, rawW, rawH, rawStridePx, meta, stillRot,
                        film, demosaic, noiseStrength, nnDn, tenBit = tenBit,
                    ) { b2, w2, h2 -> finishStillCapture(b2, w2, h2, snap, tenBit, zoom) }
                }
            }
        } else {
            render.submitRawBuf(
                rawBuf, rawW, rawH, rawStridePx, meta, stillRot, film,
                demosaic, noiseStrength, nnDn, tenBit = tenBit,
            ) { bytes, w, h -> finishStillCapture(bytes, w, h, snap, tenBit, zoom) }
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
        // 帧 0 恒为长帧（短帧放最后一帧，见 startBurst 的注释）⇒ DNG/元数据直接用帧 0。
        val bracketOn = burstBracketOn && burstShortRatio > 1.05f
        val dngIdx = 0
        val dngImg = ref
        val dngResult = refResult
        val meta = extractRawMeta(dngResult, ch)
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
                // bracket：brk=1 表示帧 0 是短帧、融合用长帧（第 1 步）
                "brk" to (if (bracketOn) 1 else 0),
                "ratio" to "%.3f".format(burstShortRatio),
                "dng" to dngIdx,
                // 畸变校正探测（v0.10.109-exp7）：burst 首帧实际生效的模式
                "distCorr" to (if (android.os.Build.VERSION.SDK_INT >= 28) {
                    results.firstOrNull()?.get(CaptureResult.DISTORTION_CORRECTION_MODE) ?: -1
                } else {
                    -1
                }),
            ),
        )
        logRawMeta(dngImg, dngResult, meta)
        if (dngOn && ch != null) {
            try {
                saveDng(dngImg, dngResult, ch)
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
        // bracket 第 1 步（设计 §7 步骤 1）：短帧**归一后参与融合**（引擎在扣黑阶后
        // ×ratio 并 clamp 到白点，见 passMfnrPyr/Fuse/Mismatch/Fft 的 uScale/
        // uFrameScale）；饱和感知权重（短帧暗部/饱和降权）在第 2 步落地。
        val shortRatio = if (bracketOn) burstShortRatio else 1f
        // 回退（设计 §3.5）：ratio 读回无效时短帧不能按长帧参与（不归一 = 4× 亮度差
        // 淹没 SAD）⇒ 用长帧（帧 1）顶替帧 0 位置，退回 4 帧同曝光域。
        // 护栏（0.11.67）：帧数不足以前是**静默失败**（StillRenderClient 入口直接
        // onDone(null)，日志只剩一条 render.still_fallback）—— 0.11.66 真机"无成像"
        // 的现场就是这样。现在必须留痕。
        if (buffers.size != MFNR_FRAMES) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "mfnr.buffers_missing",
                kv("have" to buffers.size, "want" to MFNR_FRAMES,
                   "brk" to (if (burstBracketOn) 1 else 0)),
            )
        }
        val canReplace = burstBracketOn && !bracketOn && buffers.size == MFNR_FRAMES
        val fuseBufs = if (canReplace) {
            listOf(buffers[0], buffers[1], buffers[2], buffers[0])
        } else {
            buffers
        }
        val fuseStrides = if (canReplace) {
            intArrayOf(strides[0], strides[1], strides[2], strides[0])
        } else {
            strides
        }
        // v0.11.87：短帧裁切比例透传给引擎的 AE —— bracket 下"融合后 >=0.98 白点"
        // 不是裁切（短帧救回的高光也算进去了），真丢信息的比例只有短帧自己的 sat
        // 能回答（长帧饱和 ∩ 短帧饱和 ⊆ 短帧饱和）。
        var shortSatFrac = -1f
        if (bracketOn) {
            val si = BRACKET_SHORT_IDX
            val st = rawSampleStats(buffers[si], refW, refH, strides[si], meta.whiteLevel)
            shortSatFrac = st[3]
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "bracket.short",
                kv(
                    "mean" to "%.4f".format(st[0]),
                    "p995" to "%.4f".format(st[1]),
                    "p999" to "%.4f".format(st[2]),
                    "sat" to "%.4f".format(st[3]),
                    "ratio" to "%.3f".format(burstShortRatio),
                ),
            )
            // v0.11.87 动态 k：上一张短帧的 P99.9 是"k 够不够"的直接证据。
            //   p999 > 0.85 ⇒ 短帧自己都快撞白，加大 div（短帧更短）再升 1.35×；
            //   p999 < 0.50 ⇒ 短帧余量很大，收回来（短帧更亮、SNR 更好），降 1.2×。
            // 夹在 [2, 8]（k ∈ [1, 3]）：再小短帧就失去意义，再大短帧只剩噪声。
            if (prefs.getBoolean("bracket_adaptive_k", true)) {
                // v0.11.95：**加死区 + 降步长**。真机 3118/3119（同场景连拍）实测
                // p999 恒 1.0 ⇒ 每张都 ×1.35，两张之间 div 从 4.0 跳到 5.4，短帧曝光
                // 差 0.44EV；虽然 AE 把亮度补平（mean 92.3 vs 91.7），但融合的高光
                // 信息量在连拍间不一致。现在：阈值 0.92、步长 1.2、上限 6.0 ——
                // 单张最多变 20%（0.26EV），且不会把短帧压到只剩噪声。
                val p999 = st[2]
                val next = when {
                    p999 > 0.92f -> bracketDiv * 1.20f
                    p999 < 0.45f -> bracketDiv / 1.15f
                    else -> bracketDiv
                }.coerceIn(BRACKET_DIV_MIN, 6.0f)
                if (next != bracketDiv) {
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "camera", "bracket.k",
                        kv("p999" to "%.4f".format(p999),
                           "from" to "%.2f".format(bracketDiv),
                           "to" to "%.2f".format(next)),
                    )
                    bracketDiv = next
                }
            }
        }
        // 降噪专项 A（2026-09-29）：prefs mfnr_dft = true 走 DFT 逐频收缩合并
        //（HDR+ 式 6/7），false 走 mismatch 加权融合（并联 A/B 口径）
        // 2026-10-22：默认改为 true —— 频域合并的细节明显优于空域（真机实拍对比），
        // 代价是亮部噪声略高；按"质量优先"取细节。设置页仍可关掉做 A/B。
        val useDft = prefs.getBoolean("mfnr_dft", true)
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
        // 高光保护（v0.10.110）：成片 EV 加上本次欠曝量做补偿（预览用原数组）——
        // 曝光欠 X + 提亮补 X ⇒ 中间调与预览一致，高光因为少收光子而不裁切。
        // ⚠ 定义在 done **之前**是 E1-2 的要求：下面的快照与下发必须是同一份数组。
        // 阶段 3：engine_slot != 0 时在此归零（EngineScope）；= 0 时返回同一实例，零回归
        val filmStill = engineScopedForCapture(filmParamsForStill())
        // 成片参数快照（E1-2 / ADR-4）：在此处取 —— 紧邻下发点；snapshotOf 内 copyOf()
        // 冻结（filmParamsForStill 在无欠曝补偿时返回的正是活跃数组 filmParams 本身，
        // 后台落盘线程执行时用户可能已改参数，不冻结就是假快照）。
        // 例外：A/B 诊断分支（still_ab 默认关）下发的是 submitStillAb 内部用同一函数
        // 算出的 film，那里另有一份同源快照。
        val snap = snapshotOf(filmStill)
        // 卡 H2 / FIX1：本张的交付档在**下发点**冻结成局部 val，随回调传递（不落成员）
        val tenBit = tenBitForCapture()
        // 数码变焦裁切倍率同样在**下发点**冻结（见 stillZoom 注释）
        val zoom = stillZoom
        val done: (ByteArray?, Int, Int) -> Unit = { bytes, w, h ->
            // ── HDR gainmap（v0.11.50）────────────────────────────────────
            // **必须在渲染线程、且在下一次 submit 之前**读：它读的是该线程 EGL
            // 上下文里的 tex_gainmap_，下一次拍照会把它覆盖。
            // 本 lambda 就是拍照线程的 onDone（StillRenderClient 保证），
            // 而渲染线程路径的回退（render.submitRawBurst）也是同一线程的 onDone。
            val gainmap = if (bytes != null && hdrEnabled()) readGainmapSnapshot() else null
            rendersInFlight.decrementAndGet()
            capturePhase = 0
            // 合成结束（无论成败）：预览降载回升（v0.10.93）。capturing 已在下面
            // 提前解锁，这里的恢复点才是"GPU 不再被合成占用"的时刻。
            releasePreviewScaleGuard()
            // 这里**不再清 burst* 共享数组**：capturing 已经提前解锁，用户此刻
            // 很可能已经开了下一张采集并在写这些数组，清空会把新一张的帧数据
            // 抹掉。数组统一由 startBurst() 在每张采集开始时重置。
            // ⚠ 卡 FIX1（2026-10-14 P0）：此处在修复前**硬编码 queueJpegSave**，
            //   完全绕过交付档 ⇒ HEIF 档下 10bit packed 缓冲被 ARGB_8888 错读，
            //   真机废掉两张照片（37MB/43MB）。必须走唯一分派点 [deliverStill]。
            if (bytes != null) deliverStill(bytes, w, h, snap, tenBit, gainmap, zoom)
        }
        // 拍照渲染线程（v0.10.85 重做，prefs still_thread **默认关**）：把这条
        // 1.6~3.4s 的合成链挪出预览线程 —— 否则预览的 updateTexImage 全排在它
        // 后面，"拍完挪手机画面完全冻住 1~2 秒"。启动失败即回退预览线程。
        val useStill = prefs.getBoolean("still_thread", false) &&
            StillRenderClient.ensureStarted()
        // NN 同帧 A/B（v0.10.102）：NN 路线已判死，入口随 nn_dn 一并删除，恒关
        val nnAb = false
        if (useStill && prefs.getBoolean("still_ab", false)) {
            submitStillAb(fuseBufs, fuseStrides, refW, refH, meta, demosaic, noiseStrength,
                useDft, alignL1, alignAb, p0Sub, nnDn, shortRatio, shortSatFrac,
                /*varyNn=*/false, zoom, done)
        } else if (nnAb) {
            // NN 同帧 A/B（v0.10.102）：第一遍 nn off（P0 基线）、第二遍 nn on，
            // 两张成片都落盘供离线对比（时间戳天然区分）
            submitStillAb(fuseBufs, fuseStrides, refW, refH, meta, demosaic, noiseStrength,
                useDft, alignL1, alignAb, p0Sub, nnDn, shortRatio, shortSatFrac,
                /*varyNn=*/true, zoom, done)
        } else if (useStill) {
            StillRenderClient.submitRawBurst(fuseBufs, fuseStrides, refW, refH, meta,
                stillRot, filmStill, demosaic, noiseStrength, burstRefNoiseScale,
                useDft, alignL1, alignAb, p0Sub, nnDn, shortRatio = shortRatio,
                shortSat = shortSatFrac, tenBit = tenBit) { bytes, w, h ->
                if (bytes != null) {
                    done(bytes, w, h)
                } else {
                    // 拍照线程合成失败（GL/OOM 等）：回退预览线程重跑一次 ——
                    // 用户红线：失败不以丢照片为代价（v0.10.83 就是这么丢的）
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_WARN, "camera", "render.still_fallback",
                        kv("kind" to "raw_burst"),
                    )
                    EngineBridge.nativeFlushLogs()
                    render.submitRawBurst(fuseBufs, fuseStrides, refW, refH, meta,
                        stillRot, filmStill, demosaic, noiseStrength,
                        burstRefNoiseScale, useDft, alignL1, alignAb, p0Sub, nnDn,
                        shortRatio = shortRatio, shortSat = shortSatFrac,
                        tenBit = tenBit, onDone = done)
                }
            }
        } else {
            render.submitRawBurst(fuseBufs, fuseStrides, refW, refH, meta,
                stillRot, filmStill, demosaic, noiseStrength, burstRefNoiseScale,
                useDft, alignL1, alignAb, p0Sub, nnDn,
                shortRatio = shortRatio, shortSat = shortSatFrac,
                tenBit = tenBit, onDone = done)
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
        shortRatio: Float,
        shortSat: Float,
        varyNn: Boolean,
        zoom: Float,
        done: (ByteArray?, Int, Int) -> Unit,
    ) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        // 参数快照：两遍相隔 1.6s+，中间用户可能转手机/改设置 —— 直接读成员会让
        // A/B 混进第二个变量（本项目"跨时刻对比"翻车过多次）
        val rot = stillRot   // 快门瞬间快照（同上）
        val film = engineScopedForCapture(filmParamsForStill())   // v0.10.110：A/B 两遍同样带高光保护补偿
        // E1-2：快照 = 本函数下发的这份数组（两遍共用），同样在提交前冻结
        val snap = snapshotOf(film)
        val rn = burstRefNoiseScale
        val nn1 = if (varyNn) false else nnDn
        val nn2 = if (varyNn) true else nnDn
        StillRenderClient.submitRawBurst(buffers, strides, width, height, meta,
            rot, film, demosaic, noiseStrength, rn,
            useDft, alignL1, alignAb, p0Sub, nn1, shortRatio = shortRatio,
            shortSat = shortSat) { b1, w1, h1 ->
            val d1 = if (b1 != null) outDigest(b1, w1, h1) else null
            render.submitRawBurst(buffers, strides, width, height, meta,
                rot, film, demosaic, noiseStrength, rn,
                useDft, alignL1, alignAb, p0Sub, nn2, shortRatio = shortRatio,
                shortSat = shortSat) { b2, w2, h2 ->
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
                    // 这两条 A/B 诊断路径（still_ab / nn_ab）下发时**不带 tenBit**
                    // （submitRawBurst 默认 false）⇒ native 交付的恒是 8bit RGBA。
                    if (b1 != null) queueJpegSave(b1, w1, h1, snap, DeliverFormat.RGBA8, "", null, zoom)
                    if (b2 != null) {
                        queueJpegSave(b2, w2, h2, snap, DeliverFormat.RGBA8, "_nn", null, zoom)
                    }
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
        // 期 B：HAL 的 COLOR_CORRECTION_TRANSFORM 终点是**线性 sRGB**，而标准链（下面的
        // stdCcm）终点已切 Rec.2020。两者是"优先 / 回退"关系（`stdCcm ?: halCcm`）⇒
        // 回退链必须补同一层域转换，否则"关闭标准色彩链"时 sRGB 线性数据被下游当 Rec.2020
        // 用（真机症状：偏饱和）。日志 `ccmHal`（:2896）仍记**原始** HAL 矩阵，便于诊断。
        val halCcm = StdColorMatrix.halToWorking(halCcmOf(result))
        // 色彩链（§3.2-7 / §3.4-4）：优先用 characteristics 标定矩阵自建标准链
        // （ColorMatrix/ForwardMatrix 双光源 + Bradford D50→Rec.2020，与 Adobe/libraw 完整链同源）；
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

    // ══ 卡 H2：10bit HEIF 落盘（含**回退链**与读头自检）════════════════════════════

    /** HEIF 落盘入队（与 [queueJpegSave] 同型：主线程只做快照 + 入队）。
     *
     * ⚠ 卡 FIX1：[fmt] 是交付格式断言 —— HEIF 侧只接受 10bit packed（[Heif10Pipeline]
     * 的 `unpackRgb10` 按 `GL_UNSIGNED_INT_2_10_10_10_REV` 位序解包，喂 8bit RGBA 会得到
     * 错色）。不匹配时留 ERROR 日志并**改走 8bit JPEG**（数据本来就是 8bit ⇒ 正确交付）。 */
    private fun queueHeifSave(
        bytes: ByteArray, w: Int, h: Int, snap: StillSnapshot, fmt: DeliverFormat,
        extraTag: String = "",
        gainmap: GainmapSnapshot? = null,
        zoom: Float = 1f,
    ) {
        if (!DeliverFormat.heifAccepts(fmt)) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "photo.deliver_format_mismatch",
                kv("path" to "heif", "expectBits" to DeliverFormat.RGB10A2_PACKED.bits,
                    "gotBits" to fmt.bits, "w" to w, "h" to h, "bytes" to bytes.size),
            )
            queueJpegSave(bytes, w, h, snap, DeliverFormat.RGBA8, extraTag, null, zoom)
            return
        }
        val iso = liveIso
        val secNs = liveExposureNs
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "photo.heif_queued",
            // 卡 FIX2：HEIF 恒 4:2:0（硬件 P010/YUV420）⇒ 「4:4:4」开关对它无效，日志明示
            // （用户曾因此困惑；UI 侧同档灰显 + 切档自动关/恢复见 SettingsScreen / HeifJpeg444）。
            kv("bytes" to bytes.size, "w" to w, "h" to h,
                "chroma" to com.filmkit.app.heif.HeifChroma.CHROMA, "jpeg444Ignored" to true),
        )
        saver.execute {
            // 数码变焦裁切（v0.12.34）：HEIF 路径不复用 saveJpeg 的画幅裁切，zoom 必须
            // 在这里补一次，否则 HEIC 成片仍是传感器全幅（与预览视角不符）。
            val c = cropForZoom(bytes, w, h, gainmap, zoom)
            saveHeif(c.bytes, c.w, c.h, iso, secNs, extraTag, snap, c.gainmap)
        }
    }

    /**
     * 后台线程：10bit 交付流 → HEVC Main10 编码 → 自研 HEIF 容器 → 落盘 → **读头自检**。
     *
     * 回退链（照片优先是项目红线，**任何**失败都不许丢片）：
     *   ① 编码/容器失败  ⇒ 降级 JPEG（用同一次 10bit readback 降位，**不重渲染**）；
     *   ② 落盘失败        ⇒ 降级 JPEG；
     *   ③ 读头自检不合格  ⇒ **删掉刚写的文件**再降级 JPEG（相册里绝不能留下错色/错位深的片子）。
     * 8bit HEIF 回退**本卡不做**（见报告 §5）：androidx `HeifWriter` 是另一条 API 与另一条
     * 验证路径，且它只能写 8bit（设计 §2.1）—— 在"默认 JPEG"的前提下没有收益。
     */
    /**
     * HEIC + HDR 落盘（v0.11.50）。
     *
     * 与常规 HEIF 落盘的区别：**不走** `HeifDeliverCheck` 自检 —— 那是为**自研容器**
     * （`ftyp(heic) + meta(hdlr/pitm/iinf/iloc/iprp) + mdat`，含 hvcC 10bit 判据）写的，
     * 而这里的数据来自平台 `HeifWriter`（容器结构不同，套用会误判失败）。
     * 改为轻量校验：文件非空 + 前 12 字节是 `ftyp` box（HEIF/MP4 家族的标志）。
     *
     * @return true = 已落盘；false = 失败（调用方回落三级链，绝不丢照片）
     */
    private fun persistHdrHeic(bytes: ByteArray, extraTag: String): Boolean {
        if (bytes.size < 12) return false
        // ftyp：ISO BMFF 第一个 box（"ftyp" 在偏移 4..8）
        if (bytes[4] != 'f'.code.toByte() || bytes[5] != 't'.code.toByte() ||
            bytes[6] != 'y'.code.toByte() || bytes[7] != 'p'.code.toByte()
        ) {
            return false
        }
        val now = Date()
        val name = "FILMKIT_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)}$extraTag.heic"
        var savedUri: android.net.Uri? = null
        var savedFile: File? = null
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/heic")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilmKit")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                context.contentResolver
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?.let { uri ->
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        cv.clear()
                        cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                        context.contentResolver.update(uri, cv, null, null)
                        savedUri = uri
                    }
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FilmKit")
                dir.mkdirs()
                val f = File(dir, name)
                f.outputStream().use { it.write(bytes) }
                savedFile = f
            }
        } catch (t: Throwable) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "photo.heif_hdr_write_failed",
                kv("error" to (t.message ?: t.toString())),
            )
            runCatching { savedUri?.let { context.contentResolver.delete(it, null, null) } }
            runCatching { savedFile?.delete() }
            return false
        }
        if (savedUri == null && savedFile == null) return false
        savedUri?.let { lastPhotoUri = it }
        runCatching { writeExif(savedUri, savedFile, now) }
        return true
    }

    private fun saveHeif(
        rgba10: ByteArray, w: Int, h: Int, iso: Int, secNs: Long,
        extraTag: String, snap: StillSnapshot,
        gainmap: GainmapSnapshot? = null,
    ) {
        val t0 = System.currentTimeMillis()
        // ── HEIC + HDR（v0.11.50，用户 2026-10-19 拍板：**接受 8bit 降位**）──────
        // 走平台 HeifWriter（8bit + gainmap），对齐 PhotonCamera 的 HeicExportEncoder。
        // 取舍已定：现有 HEIC 是自研 **10bit** 容器，开 HDR 会降到 8bit —— 用户判断
        // "10bit 收益很低"（HEIC 的 4:2:0 色度损失是主导短板，10bit 的量化精度被稀释），
        // 用可见的 HDR 动态范围换不可见的量化精度，划算。
        // 失败 ⇒ 继续走下面的三级链（10bit → 8bit → JPEG），绝不丢照片。
        if (gainmap != null && hdrEnabled() && HdrDelivery.platformGainmapSupported) {
            val hdrBytes = runCatching {
                val rgba8 = com.filmkit.app.heif.Heif10Pipeline.toRgba8(rgba10, w, h)
                val bmp = Bitmap.createBitmap(w, h, EngineBridge.OUTPUT_BITMAP_CONFIG)
                bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba8))
                val gm = HdrDelivery.buildGainmap(gainmap.data, gainmap.w, gainmap.h)
                val tmp = File(context.cacheDir, "hdr-heic$extraTag.heic")
                try {
                    if (gm != null &&
                        PlatformGainmapWriter.writeHeic(bmp, gm, tmp.absolutePath, 100)
                    ) {
                        tmp.readBytes().takeIf { it.size > 64 }
                    } else {
                        null
                    }
                } finally {
                    bmp.recycle()
                    tmp.delete()
                }
            }.getOrNull()
            if (hdrBytes != null && persistHdrHeic(hdrBytes, extraTag)) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "camera", "photo.heif_hdr",
                    kv("ms" to (System.currentTimeMillis() - t0), "bytes" to hdrBytes.size,
                        "w" to w, "h" to h, "bits" to 8, "gainmap" to "${gainmap.w}x${gainmap.h}"),
                )
                return
            }
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "camera", "photo.heif_hdr_failed",
                kv("fallback" to "3-level", "ms" to (System.currentTimeMillis() - t0)),
            )
        }
        // 三级回退（卡 FIX2）：10bit 自研容器 → 8bit HEIF（平台 muxer）→ JPEG。
        // 顺序、逐级日志与"拿不准就往下落"的语义由 HeifDelivery 固化（纯 JVM 单测覆盖三条路径）。
        var spec = com.filmkit.app.heif.HeifDeliverCheck.Spec.TEN_BIT
        var enc8: com.filmkit.app.heif.Heif8Encoder.Encoded? = null
        // 卡 FIX4：质量口径的统计出口（`bitrateMode`/`qp`/`bitrate`/`bytes`/`ms`/`verdict`）。
        // 10bit 那边逐路尝试，最终生效的那路由编码器回传 ⇒ 下一轮不用猜"走的哪条路"。
        var q10: com.filmkit.app.heif.HeifQuality.Stats? = null
        val deliv = com.filmkit.app.heif.HeifDelivery.run(
            attempt10 = {
                com.filmkit.app.heif.Heif10Pipeline.toHeifFile(rgba10, w, h) { st ->
                    q10 = st
                    // **每一路都留一条 `photo.heif_try`**（不只最终生效的那路）：0.11.39 的真机
                    // 日志只剩最后一路 ⇒ "哪条路真的驱动了质量"只能反推。多条 try 让**一张照片**
                    // 就能横向比较各路（mode/qp/bitrate/bytes/verdict/frameRate/cap 逐条对齐）。
                    EngineBridge.nativeAppendLog(
                        if (st.verdict == com.filmkit.app.heif.HeifQuality.Verdict.OK) {
                            EngineBridge.LEVEL_INFO
                        } else {
                            EngineBridge.LEVEL_WARN
                        },
                        "camera", "photo.heif_try", st.kv(),
                    )
                }
            },
            attempt8 = {
                // 8bit 级复用**同一次** 10bit readback 的降位结果（不重渲染、不重新 readback）
                val rgba8 = com.filmkit.app.heif.Heif10Pipeline.toRgba8(rgba10, w, h)
                com.filmkit.app.heif.Heif8Encoder.encodeToBytes(
                    rgba8, w, h, File(context.cacheDir, "filmkit-8bit$extraTag.heic"),
                ).also { enc8 = it }.bytes
            },
            log = { level, event, fields ->
                EngineBridge.nativeAppendLog(level, "camera", event, fields)
            },
        )
        val encMs = System.currentTimeMillis() - t0
        // HEIF 恒 4:2:0（10bit 档 = 硬件 P010；8bit 档 = YUV420）⇒ 设置页的「4:4:4」只作用于
        // JPEG。日志明示这一点（用户曾因此困惑）：chroma + jpeg444Ignored。
        val heif: ByteArray = when (deliv) {
            is com.filmkit.app.heif.HeifDelivery.Result.TenBit -> {
                // `verdict != OK` 直接升级成 WARN：体积没进 40~60% 目标区间 ⇒ 下一轮必须看见
                // （这是"不许静默压过头"的落点——198 KB 那次就是因为没人报）。
                val st = q10
                EngineBridge.nativeAppendLog(
                    if (st == null || st.verdict == com.filmkit.app.heif.HeifQuality.Verdict.OK) {
                        EngineBridge.LEVEL_INFO
                    } else {
                        EngineBridge.LEVEL_WARN
                    },
                    "camera", "photo.heif_encoded",
                    kv("ms" to encMs, "bytes" to deliv.bytes.size, "w" to w, "h" to h,
                        "bits" to 10, "chroma" to com.filmkit.app.heif.Heif8Encoder.CHROMA,
                        "jpeg444Ignored" to true,
                        "bitrateMode" to (st?.mode ?: "?"),
                        "qp" to (st?.qp ?: -1), "bitrate" to (st?.bitrate ?: -1),
                        "encMs" to (st?.ms ?: -1), "verdict" to (st?.verdict?.name ?: "?"),
                        "tries" to (st?.tries ?: -1),
                        // 0.11.40：把 frame-rate 与编码器声明的码率上限**一起**进日志 ——
                        // 0.11.39 那次只能从 `bitrate:100000000` 反推"被夹了"，一个字段省掉这步。
                        "frameRate" to com.filmkit.app.heif.HeifQuality.FRAME_RATE,
                        "cap" to (st?.cap ?: -1),
                        "targetMin" to (st?.targetMin ?: -1), "targetMax" to (st?.targetMax ?: -1)),
                )
                deliv.bytes
            }
            is com.filmkit.app.heif.HeifDelivery.Result.EightBit -> {
                spec = com.filmkit.app.heif.HeifDeliverCheck.Spec.EIGHT_BIT
                val st = enc8?.quality
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_WARN, "camera", "photo.heif8_encoded",
                    kv("ms" to encMs, "bytes" to deliv.bytes.size, "w" to w, "h" to h,
                        "bits" to com.filmkit.app.heif.Heif8Encoder.BIT_DEPTH,
                        "chroma" to com.filmkit.app.heif.Heif8Encoder.CHROMA,
                        "codec" to (enc8?.codec ?: "?"), "mime" to (enc8?.mime ?: "?"),
                        "jpeg444Ignored" to true,
                        "bitrateMode" to (st?.mode ?: "?"),
                        "qp" to (st?.qp ?: -1), "bitrate" to (st?.bitrate ?: -1),
                        "encMs" to (st?.ms ?: -1), "verdict" to (st?.verdict?.name ?: "?"),
                        "targetMin" to (st?.targetMin ?: -1), "targetMax" to (st?.targetMax ?: -1)),
                )
                deliv.bytes
            }
            is com.filmkit.app.heif.HeifDelivery.Result.Jpeg -> {
                fallbackToJpeg(
                    rgba10, w, h, iso, secNs, extraTag, snap,
                    from = deliv.from, reason = deliv.reason,
                )
                return
            }
        }

        val now = Date()
        val name = "FILMKIT_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)}$extraTag.heic"
        var savedUri: android.net.Uri? = null
        var savedFile: File? = null
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/heic")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilmKit")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                context.contentResolver
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?.let { uri ->
                        context.contentResolver.openOutputStream(uri)?.use { it.write(heif) }
                        cv.clear()
                        cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                        context.contentResolver.update(uri, cv, null, null)
                        savedUri = uri
                    }
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FilmKit")
                dir.mkdirs()
                val f = File(dir, name)
                f.outputStream().use { it.write(heif) }
                savedFile = f
            }
        } catch (t: Throwable) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "photo.heif_failed",
                kv("stage" to "write", "error" to (t.message ?: t.toString())),
            )
            runCatching { savedUri?.let { context.contentResolver.delete(it, null, null) } }
            fallbackToJpeg(
                rgba10, w, h, iso, secNs, extraTag, snap,
                from = if (spec == com.filmkit.app.heif.HeifDeliverCheck.Spec.TEN_BIT) "10bit" else "8bit",
                reason = "write",
            )
            return
        }

        // ── 运行时读头自检（设计 §4.5 判据 A；口径随交付档：10bit = colr 1-13-1-1 + hvcC 真 10bit；
        //    8bit = majorBrand heic + hvcC 真 8bit，不要求 colr/pixi —— 见 HeifDeliverCheck.Spec）
        val head = try {
            if (savedUri != null) {
                context.contentResolver.openInputStream(savedUri)?.use {
                    com.filmkit.app.heif.HeifDeliverCheck.check(it, spec)
                }
            } else {
                savedFile?.let { com.filmkit.app.heif.HeifDeliverCheck.check(it, spec) }
            }
        } catch (t: Throwable) {
            com.filmkit.app.heif.HeifDeliverCheck.Result(
                false, "{\"read\":false,\"err\":\"${t.message ?: t.toString()}\"}",
            )
        }
        if (head == null || !head.ok) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "photo.heif_head_check_failed",
                kv("name" to name, "detail" to (head?.detail ?: "{\"read\":false}")),
            )
            // 删掉刚写的文件：相册里绝不能留下不符合交付口径的片子
            val uriToDrop = savedUri
            val deleted = runCatching {
                if (uriToDrop != null) context.contentResolver.delete(uriToDrop, null, null) > 0
                else savedFile?.delete() == true
            }.getOrDefault(false)
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "camera", "photo.heif_discarded",
                kv("name" to name, "deleted" to deleted),
            )
            fallbackToJpeg(
                rgba10, w, h, iso, secNs, extraTag, snap,
                from = if (spec == com.filmkit.app.heif.HeifDeliverCheck.Spec.TEN_BIT) "10bit" else "8bit",
                reason = "head_check",
            )
            return
        }

        if (savedUri != null) {
            lastPhotoUri = savedUri
            runCatching {
                appPrefs.edit().putString("last_photo_uri", savedUri.toString()).apply()
            }
        }
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera",
            if (spec == com.filmkit.app.heif.HeifDeliverCheck.Spec.EIGHT_BIT) "photo.heif8_saved"
            else "photo.heif_saved",
            kv("name" to name, "w" to w, "h" to h, "iso" to iso,
                "sec" to "%.4f".format(secNs / 1e9f),
                "bits" to if (spec == com.filmkit.app.heif.HeifDeliverCheck.Spec.EIGHT_BIT) 8 else 10,
                "chroma" to com.filmkit.app.heif.Heif8Encoder.CHROMA,
                "detail" to head.detail),
        )
        // 成片参数快照（同 saveJpeg：**落盘成功后**才写，键 = MediaStore 的真实 DISPLAY_NAME）
        StillSnapshotStore.save(
            context,
            savedUri?.let { StillSnapshotStore.fileNameOf(context, it) } ?: name,
            snap.copy(timeMs = now.time),
        )
        // ⚠ 不写 EXIF：自研容器没写 Exif item，而 androidx ExifInterface 对 HEIC 的
        //   写入会重写文件 —— 在"照片优先"红线面前不值得为元数据冒这个险（报告 §5 待办）。
    }

    /** HEIF 失败 ⇒ 用**同一次 10bit readback** 降位成 RGBA8 走现有 JPEG 链（不重渲染）。 */
    private fun fallbackToJpeg(
        rgba10: ByteArray, w: Int, h: Int, iso: Int, secNs: Long,
        extraTag: String, snap: StillSnapshot, from: String, reason: String,
    ) {
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_WARN, "camera", "photo.heif_fallback_jpeg",
            kv("from" to from, "reason" to reason, "w" to w, "h" to h),
        )
        val rgba8 = try {
            com.filmkit.app.heif.Heif10Pipeline.toRgba8(rgba10, w, h)
        } catch (t: Throwable) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "photo.save_failed",
                kv("error" to "10bit→8bit 降位失败: ${t.message ?: t.toString()}"),
            )
            return
        }
        saveJpeg(rgba8, w, h, iso, secNs, extraTag, snap)
    }

    /** 把一帧成片投给后台保存队列。主线程在这里只做两件事：快照拍摄参数、
     *  入队 —— 返回后调用方（相机回调 / 渲染回调）立刻可以继续工作。
     *  日志 photo.save_queued 与 photo.saved 的时间差 = 后台实际耗时，用于验证
     *  主线程确实没被编码阻塞。
     *
     * ⚠ 卡 FIX1：[fmt] **故意不给默认值** —— 本次 P0 的成因正是"落盘点可以忘掉交付档"。
     * 编译期强制每个调用点显式声明它凭什么格式把缓冲交给 JPEG 编码器。 */
    private fun queueJpegSave(
        bytes: ByteArray, w: Int, h: Int, snap: StillSnapshot, fmt: DeliverFormat,
        extraTag: String = "",
        gainmap: GainmapSnapshot? = null,
        zoom: Float = 1f,
    ) {
        // ── 交付格式断言（照片优先红线：宁可失败/降位，绝不静默交付错数据）──────────
        // JPEG 编码器只能吃 8bit RGBA（`EngineBridge.OUTPUT_BITMAP_CONFIG` = ARGB_8888）。
        // 10bit packed 与它**同为 4 B/px** ⇒ 长度校验永远通过、位打包被按通道错读（FIX1）。
        // 走到这里说明上游分派漏了：留 ERROR 日志（`photo.deliver_format_mismatch`），
        // 并走 HEIF 回退链那条同样的降位路径把它变成真正的 RGBA8 —— **不丢片**。
        if (!DeliverFormat.jpegAccepts(fmt)) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "photo.deliver_format_mismatch",
                kv("path" to "jpeg", "expectBits" to DeliverFormat.RGBA8.bits,
                    "gotBits" to fmt.bits, "w" to w, "h" to h, "bytes" to bytes.size),
            )
            val iso8 = liveIso
            val sec8 = liveExposureNs
            val rgba8 = try {
                com.filmkit.app.heif.Heif10Pipeline.toRgba8(bytes, w, h)
            } catch (t: Throwable) {
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_ERROR, "camera", "photo.save_failed",
                    kv("error" to "10bit→8bit 降位失败: ${t.message ?: t.toString()}"),
                )
                return
            }
            // 10bit 降位路径不接 HDR（gainmap 是按 8bit 成片算的，降位后像素已变）
            saver.execute { saveJpeg(rgba8, w, h, iso8, sec8, extraTag, snap, null, zoom) }
            return
        }
        val iso = liveIso
        val secNs = liveExposureNs
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "photo.save_queued",
            kv("bytes" to bytes.size),
        )
        saver.execute { saveJpeg(bytes, w, h, iso, secNs, extraTag, snap, gainmap, zoom) }
    }

    /** 后台线程执行（saver 队列）。iso/secNs = 拍摄瞬间的快照，不能用成员变量。
     *  [snap] 同理：下发点冻结的参数快照（E1-2），落盘成功后写进 prefs。 */
    private fun saveJpeg(
        rgba: ByteArray, w: Int, h: Int, iso: Int, secNs: Long,
        extraTag: String = "", snap: StillSnapshot,
        gainmap: GainmapSnapshot? = null,
        zoom: Float = 1f,
    ) {
        val bmp = Bitmap.createBitmap(w, h, EngineBridge.OUTPUT_BITMAP_CONFIG)
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
        // 成片居中裁切：**数码变焦**（v0.12.34）与**画幅**两步都是居中的 ⇒ 合成一次
        // （两次居中裁 = 直接裁到更小的居中矩形），省一次全图拷贝。
        // 用**快门瞬间**的画幅快照（v0.10.110-fix）：渲染 3~4s 期间用户常把手机转回
        // 竖屏，读实时 captureAspect 会把横拍裁成竖幅（2026-10-03 真机实证）。
        val zc = zoomCropFactor(w, zoom)
        val asp = if (stillAspect > 0f) stillAspect else captureAspect
        // 尺寸计算收在纯函数里（投递时取整 + 上下界都在那儿测，见 StillCropTest）
        val crop = stillCropSize(w, h, zc, asp)
        val cw = crop[0]
        val ch = crop[1]
        var outBmp = bmp
        if (cw != w || ch != h) {
            outBmp = Bitmap.createBitmap(bmp, (w - cw) / 2, (h - ch) / 2, cw, ch)
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "photo.crop",
                kv("srcA" to "%.3f".format(w.toFloat() / h.toFloat()), "dstA" to "%.3f".format(asp),
                    "out" to "${outBmp.width}x${outBmp.height}",
                    "zoom" to "%.2f".format(zc), "snap" to (stillAspect > 0f), "rot" to stillRot),
            )
        }
        val outW = outBmp.width
        val outH = outBmp.height
        // ── gainmap 必须与成片**同步裁剪**（v0.11.50）───────────────────────
        // 上面的画幅居中裁切会让成片小于渲染尺寸，而 gainmap 是按渲染尺寸算的
        //（1/2 分辨率）⇒ 不裁就错位，HDR 显示时增益会落在错误的位置。
        // 坐标换算：源像素 (x,y) ↔ gainmap (x/2, y/2)；裁剪起点同样除 2 取整。
        // 取整误差 ≤1 个 gainmap 像素 = 2 个源像素，对低频增益图无可见影响。
        val useGainmap = if (gainmap != null && (outW != w || outH != h)) {
            cropGainmap(gainmap, w, h, outW, outH)
        } else {
            gainmap
        }
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
        // 依据与体积/耗时实测见 docs/reports/jpeg444-REPORT.md；false = 旧行为（4:2:0）。
        val jpeg444 = prefs.getBoolean("jpeg_444", true)
        // 编码一次（下面两个落盘分支只差写入目标）；native 失败返回 null ⇒ 回退
        // Bitmap.compress（宁可退到 4:2:0，不可丢成片）。
        val encT0 = System.nanoTime()
        // runCatching 兜住 UnsatisfiedLinkError（老 .so 被缓存等极端情形）：任何失败都退
        // 回 Bitmap.compress，绝不让编码环节把成片整张丢掉。
        // ── HDR 成片（v0.11.50）：优先走 JPEG/R（Ultra HDR）─────────────────
        // 三条路径的优先级（用户 2026-10-19 拍板：4:2:0 / 4:4:4 / HEIC 都要能出 HDR）：
        //   ① 4:4:4 开启 + 有 gainmap ⇒ **自研 JPEG/R**（base 仍是 4:4:4，保住质量红线）
        //   ② 4:4:4 关闭 + 有 gainmap + Android 14+ ⇒ 平台 Gainmap（base 是 4:2:0）
        //   ③ 其余 ⇒ 普通 SDR JPEG（原行为，一字不动）
        // 任何一步失败都落回 ③ —— 照片优先红线。
        var hdrBytes: ByteArray? = null
        var hdrPath = "none"
        if (useGainmap != null && jpeg444) {
            hdrBytes = HdrDelivery.encodeJpegR(outBmp, useGainmap.data, useGainmap.w,
                useGainmap.h, jpegQ)
            hdrPath = if (hdrBytes != null) "jpegR444" else "jpegR_failed"
        } else if (useGainmap != null && HdrDelivery.platformGainmapSupported) {
            // 平台路径：base 4:2:0，但 HDR 信息完整（对齐 PC 的 UltraHdrWriter）
            hdrBytes = runCatching {
                val gm = HdrDelivery.buildGainmap(useGainmap.data, useGainmap.w,
                    useGainmap.h)
                    ?: return@runCatching null
                val bos = java.io.ByteArrayOutputStream()
                if (PlatformGainmapWriter.writeJpeg420(outBmp, gm, bos, jpegQ)) {
                    bos.toByteArray().takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }.getOrNull()
            hdrPath = if (hdrBytes != null) "platform420" else "platform420_failed"
        }
        val native444: ByteArray? =
            if (jpeg444) runCatching {
                EngineBridge.nativeEncodeJpeg444(outBmp, jpegQ)
            }.getOrNull() else null
        // 卡 FIX2 可疑点：此前 4:2:0 分支的 `photo.encode{bytes:0,ms:0}` 是在 compress **之前**
        // 记录的、且只统计 native 分支 ⇒ 无法区分"444 关闭"/"native 失败"，也看不出 4:2:0 是否
        // 真编码。现在把 compress 提到日志之前，两条分支都在日志里报**真实字节数与耗时**。
        // 优先级：HDR（自带 base，已是完整成片）> 普通 4:4:4 > Bitmap.compress 4:2:0
        var jpegBytes: ByteArray? = hdrBytes ?: native444
        if (jpegBytes == null) {
            val bos = java.io.ByteArrayOutputStream()
            outBmp.compress(Bitmap.CompressFormat.JPEG, jpegQ, bos)   // Android 恒 4:2:0
            jpegBytes = bos.toByteArray().takeIf { it.isNotEmpty() }
        }
        val encMs = (System.nanoTime() - encT0) / 1_000_000
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "photo.encode",
            kv("chroma" to (if (native444 != null) "444" else "420"),
                "encoder" to (if (native444 != null) "native444" else "bitmap420"),
                "jpeg444" to jpeg444, "nativeFail" to (jpeg444 && native444 == null),
                "q" to jpegQ, "ms" to encMs, "bytes" to (jpegBytes?.size ?: 0),
                "hdr" to hdrPath),
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
        // 4:4:4 走 native 字节流；上面两步已保证 4:2:0 也拿到真实字节（含 compress 失败 ⇒ 落盘时再试一次）
        val jpegOut: ByteArray? = jpegBytes
        val writeJpeg: (java.io.OutputStream) -> Unit = { os ->
            if (jpegOut != null) os.write(jpegOut)
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
            // ── 胶卷位 URI **写完才发布**（v0.12.34，用户报"左下角缩略图有概率丢失"）──
            // 原来在 `insert` 之后、写字节之前就赋值 ⇒ UI 的 400ms 轮询可能正好在文件
            // 还是空的时候拿到新 URI，decode 失败一次就再也不会重试（LaunchedEffect 只在
            // URI 变化时跑）⇒ 缩略图永久空白。EXIF 也写完之后才发布，避免又一次"读到
            // 一半的文件"。写 prefs 失败只记日志 —— 不能影响照片落盘（用户红线）。
            savedUri?.let { uri ->
                lastPhotoUri = uri
                runCatching {
                    appPrefs.edit().putString("last_photo_uri", uri.toString()).apply()
                }.onFailure {
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_WARN, "camera", "photo.uri_persist_failed",
                        kv("error" to (it.message ?: it.toString())),
                    )
                }
            }
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "photo.saved",
                kv(
                    "name" to name, "w" to outW, "h" to outH,
                    "avgLuma" to (sum / n.coerceAtLeast(1)),
                    "iso" to iso, "sec" to "%.4f".format(secNs / 1e9f),
                ),
            )
            // 成片参数快照（E1-2 / ADR-4）：**落盘成功后**才写，键 = MediaStore 的真实
            // DISPLAY_NAME（同名时系统会自动加 "(1)"，落盘时反查一次才与编辑器日后查到的
            // 键一致）；API<29 的 File 分支没有 URI，用本地 name。写失败只记日志
            //（save 内部吞异常），绝不影响照片 —— 用户红线。
            StillSnapshotStore.save(
                context,
                savedUri?.let { StillSnapshotStore.fileNameOf(context, it) } ?: name,
                snap.copy(timeMs = now.time),
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

/**
 * HDR gainmap 快照（v0.11.50）。
 *
 * 为什么要单独一个类：gainmap 在**渲染线程**读出（读的是该线程 EGL 上下文里的
 * `tex_gainmap_`），成片在 **saver 线程**落盘 —— 两者跨线程，必须把数据 + 尺寸
 * 一起冻结传过去。不冻结的话，连拍时第二张的 gainmap 会覆盖第一张的。
 *
 * @param data 长度 = w*h 的单通道 8bit 归一化比值（0..255）
 */
internal class GainmapSnapshot(val data: ByteArray, val w: Int, val h: Int)

/**
 * 成片交付缓冲的**显式格式**（卡 FIX1）。
 *
 * 为什么必须显式：8bit RGBA 与 10bit packed（`GL_UNSIGNED_INT_2_10_10_10_REV`，
 * `GL_RGB10_A2` 附件）都是 **4 字节/像素** —— 3072×4080 时两者都是 50135040 B，
 * **长度校验永远抓不到**。唯一的判据是"这个缓冲是按哪个交付档从 native 读回来的"，
 * 而交付档只能来自下发点冻结的那一个 bool（见 `CameraController.tenBitForCapture`）。
 *
 * 成员顺序 = 位深升序；[bits] 只用于日志（真机判据见
 * `docs/reports/r-next-fix-heif-switch-REPORT.md` §7）。
 */
internal enum class DeliverFormat(val bits: Int) {
    /** 8bit RGBA（`EngineBridge.OUTPUT_BITMAP_CONFIG` = ARGB_8888 直通）。 */
    RGBA8(8),

    /** 10bit packed uint32（LSB-first：bit0..9 = R、10..19 = G、20..29 = B、30..31 = A）。 */
    RGB10A2_PACKED(10);

    companion object {
        /** 交付档 → 缓冲格式（**唯一真源**：native 侧同一 bool 决定 target 与 readback）。 */
        fun of(tenBit: Boolean): DeliverFormat = if (tenBit) RGB10A2_PACKED else RGBA8

        /**
         * JPEG 编码器（`EngineBridge.OUTPUT_BITMAP_CONFIG` 直通 + `nativeEncodeJpeg444`）能否吃这种
         * 缓冲。**只吃 8bit RGBA**：10bit packed 与它同为 4 B/px ⇒ 长度校验通过、位打包被
         * 按通道错读 ⇒ 霓虹伪彩 + 熵爆炸（FIX1 事故）。
         */
        fun jpegAccepts(fmt: DeliverFormat): Boolean = fmt == RGBA8

        /**
         * HEIF 10bit 链（`Heif10Pipeline.unpackRgb10` 按 `2_10_10_10_REV` 位序解包）能否吃
         * 这种缓冲。**只吃 10bit packed**：喂 8bit RGBA 会把通道字节当位段 ⇒ 错色。
         */
        fun heifAccepts(fmt: DeliverFormat): Boolean = fmt == RGB10A2_PACKED
    }
}
