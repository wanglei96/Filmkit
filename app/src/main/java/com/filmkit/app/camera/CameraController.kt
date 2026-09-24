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

    @Volatile
    private var capturing = false

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

    var maxAnalogIso = 3200
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

    fun setCaptureAspect(a: Float) {
        captureAspect = a.coerceIn(0.2f, 5f)
    }

    fun setIso(v: Int) {
        iso = v.coerceIn(100, maxAnalogIso)
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

    private fun applyControls(b: CaptureRequest.Builder) {
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
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps)
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
        presetWbBase = doubleArrayOf(p[1], p[2], p[3])
        tonePref = -1.0  // 换了新数组，强制重写 [28]
        lookPref = -1.0  // 同上，[31]
        hlPref = -1.0    // 同上，[29]
        zebraPref = -1.0 // 同上，[30]
        autoTonePref = -1.0 // 同上，[35]
        lutStrengthPref = -1.0 // 同上，[34]
        applyTonePref()
        applyHlPref()
        applyZebraPref()
        applyLookPref()
        applyLutStrengthPref()
    }

    private val appPrefs by lazy { context.getSharedPreferences("filmkit", 0) }
    private var tonePref = -1.0
    private var hlPref = -1.0
    private var zebraPref = -1.0
    private var lookPref = -1.0
    private var lutStrengthPref = -1.0
    private var autoTonePref = -1.0
    // filmic 黑白点（v0.7.6）：哨兵 = 未初始化（用户可配，默认 −7.65/+4.56 EV）
    private var filmicBlackPref = -999.0
    private var filmicWhitePref = -999.0
    // 局部色调映射（M4-7）：强度与 CameraPresets[36] 默认值一致；0 = 关（A/B 通路）。
    // v0.6.33：0.6 → 0.25（roadmap 立项定的上限就是 ≤0.2–0.3）。
    private val LOCAL_TONE = 0.25
    private var localTonePref = -1.0
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

    /** 高光重建开关（M3 §3.2 阶段 4）：写 FilmParams[29]，只走 RCD 路径。 */
    private fun applyHlPref() {
        val v = if (appPrefs.getBoolean("hl_recon", true)) 1.0 else 0.0
        if (v != hlPref) {
            hlPref = v
            filmParams[29] = v
        }
    }

    /** 色彩外观层开关（M3 P1，治"寡淡"）：写 FilmParams[31]（0 = 恒等/A-B 关）。 */
    private fun applyLookPref() {
        val v = if (appPrefs.getBoolean("color_look", true)) 1.0 else 0.0
        if (v != lookPref) {
            lookPref = v
            filmParams[31] = v
        }
    }

    /**
     * LUT 强度（M4 §6 节点 opacity）：写 FilmParams[34]（0 = 旁路，1 = 全量，
     * 1.5 = 150% —— 沿 LUT 色差外推，与 PhotonCamera 的 >100% 强度语义一致）。
     * 与上述开关同款「偏好覆盖」：UI 只写 prefs，提交前统一回写数组 ——
     * 预览 / YUV 静拍 / RAW 静拍三条路径共用同一数组，故所见即所得。
     */
    private fun applyLutStrengthPref() {
        val v = appPrefs.getFloat("lut_strength", 1f).toDouble().coerceIn(0.0, 1.5)
        if (v != lutStrengthPref) {
            lutStrengthPref = v
            filmParams[34] = v
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
        applyFilmicRangePrefs()
    }

    /**
     * filmic 黑白点（v0.7.6）：写 FilmParams[37]/[38]，单位 = 相对中灰 18.45% 的 EV。
     * 默认 −7.65/+4.56（darktable 默认 = PhotonCamera 固化值）。用户可在设置页自由调整。
     * auto_tone 开时黑点由引擎按场景分位自动（[37] 被忽略）。
     */
    private fun applyFilmicRangePrefs() {
        val b = appPrefs.getFloat("filmic_black_ev", -7.65f).toDouble()
        val w = appPrefs.getFloat("filmic_white_ev", 4.56f).toDouble()
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
        val v = if (appPrefs.getBoolean("auto_black_ev", false)) 1.0 else 0.0
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
        // 静拍 YUV 解码矩阵（设置页开关，实拍 A/B 定夺；须在引擎启动后设置）。
        // 防御性包裹：JNI/任何异常不得阻断相机启动（started=false = 全黑+焦段丢失）
        runCatching {
            EngineBridge.nativeEngineSetYuvMatrix(
                if (context.getSharedPreferences("filmkit", 0).getBoolean("yuv_709", false)) 1 else 0,
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
            }
        started = true
        openCamera()
    }

    fun capture() {
        val s = session ?: return
        if (capturing) return
        capturing = true
        if (shutterSound) {
            shutterSoundPlayer.play(android.media.MediaActionSound.SHUTTER_CLICK)
        }
        try {
            val b = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(stillReader!!.surface)
                applyControls(this) // 静拍继承 M 档控制（WB/EV/OIS/变焦/对焦区域）
            }
            if (rawMode) {
                // RAW 路径需要 TotalCaptureResult（DngCreator/黑阶/WB/CCM 元数据）：
                // 回调暂存 result，与 reader listener 的 Image 在渲染线程汇合
                pendingResult = null
                rawImageAtMs = 0L
                s.capture(b.build(), rawCaptureCb, render.looper?.let { android.os.Handler(it) })
            } else {
                s.capture(b.build(), null, render.looper?.let { android.os.Handler(it) })
            }
        } catch (t: Throwable) {
            capturing = false
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "capture.submit_failed",
                kv("error" to (t.message ?: t.toString())),
            )
        }
    }

    fun stop() {
        started = false
        pendingOpenId = null
        orientationListener.disable()
        (context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager)
            ?.unregisterListener(gravityListener)
        render.post {
            try { session?.stopRepeating() } catch (_: Throwable) {}
            camera?.close()
            camera = null
            session = null
            stillReader?.close()
            stillReader = null
            rawReader = null
            pendingRawImage = null
            pendingResult = null
            surfaceTexture?.release()
            surfaceTexture = null
            previewSurface?.release()
            previewSurface = null
        }
        render.stop()
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
        maxZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        colorFilterArrangement = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        maxAnalogIso = ch.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY) ?: 3200
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
                stillSize.width, stillSize.height, stillFormat, 2,
            ).apply {
                setOnImageAvailableListener({ r ->
                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    if (img.format == ImageFormat.RAW_SENSOR) {
                        pendingRawImage?.close()
                        pendingRawImage = img
                        rawImageAtMs = android.os.SystemClock.elapsedRealtime()
                        tryProcessRaw()
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
                    applyHlPref()
                    applyZebraPref()
                    applyLookPref()
                    applyLutStrengthPref()
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

    private fun submitStill(img: Image) {
        // 静拍与预览共用「方向」设定（用户在界面选定；默认 0）
        applyTonePref()
        applyHlPref()
        applyZebraPref()
        applyLookPref()
        applyLutStrengthPref()
        render.submitStill(img, orientationStep, filmParams) { bytes, w, h ->
            capturing = false
            if (bytes != null) saveJpeg(bytes, w, h)
        }
    }

    // ── RAW 静拍（M2-3）：Image 与 TotalCaptureResult 汇合后处理 ──

    private val rawCaptureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult,
        ) {
            pendingResult = result
            tryProcessRaw()
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
        val demosaic = if (prefs.getBoolean("demosaic_quality", true)) 1 else 0
        // 降噪（M3-2）：0 = 关；1 = 标准；2 = 强（P0 RAW bilateral + P4 色度平滑，
        // 仅 RCD 生效）。v0.5.33 起两档；旧 bool 开关为 false 时视为关（设置页写入
        // 时会同步这两个 key，故两者始终一致）。
        val noiseLevel = if (prefs.getBoolean("noise_reduction", true)) {
            prefs.getInt("noise_reduction_level", 1)
        } else {
            0
        }
        val meta = extractRawMeta(result, ch)
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "raw.meta",
            kv(
                "cfa" to meta.cfa, "white" to meta.whiteLevel,
                "black" to meta.blackPhase.joinToString(",") { "%.0f".format(it) },
                "neutral" to meta.neutral.joinToString(",") { "%.4f".format(it) },
                "ccm" to meta.ccm.joinToString(",") { "%.3f".format(it) },
                "ccmSrc" to if (meta.ccmStd) "std" else "hal",
                "noise" to "%.4f".format(meta.noiseSigma),
                "in" to "${img.width}x${img.height}",
                "lens" to currentLensId,
                // ── v0.6.34：预览实际曝光 vs 拍照实际曝光 ──────────────────
                // 用户反馈"预览与成片差很多"。此前这两个量在日志里**无法对比**：
                // controls.applied 的 iso/sec 是**滑杆存量值**（AUTO 档下不代表实际，
                // 曾被误读成"预览用 ISO 100"），而预览的实际值只回填给 UI 的
                // liveIso/liveExposureNs、从不落盘。这里一次记全，一次真机即可定位。
                "pvIso" to liveIso,
                "pvSec" to "%.4f".format(liveExposureNs / 1e9f),
                "stIso" to (result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0),
                "stSec" to "%.4f".format(
                    (result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) / 1e9f,
                ),
            ),
        )
        // DNG 标准链探针（P2）：标定矩阵是 **CameraCharacteristics 静态键**（不在
        // CaptureResult —— v0.5.45 初版记错归属，编译期 Unresolved 才暴露）。
        // 每个 cameraId 各记一条（v0.6.20）：主摄与长焦的标定矩阵是否相同，
        // 是"主摄色彩明显差"的头号嫌疑，必须能从日志直接对比。
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
        applyTonePref()
        applyHlPref()
        applyZebraPref()
        applyLookPref()
        applyLutStrengthPref()
        render.submitRaw(img, meta, orientationStep, filmParams, demosaic, noiseLevel) { bytes, w, h ->
            capturing = false
            if (bytes != null) saveJpeg(bytes, w, h)
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
        val halCcm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { t ->
            // 构造收行主序、读取 getElement(col,row)——遍历须 (row, col) 双重序防转置
            for (row in 0..2) for (col in 0..2) halCcm[row * 3 + col] = t.getElement(col, row).toFloat()
        }
        // 色彩链（§3.2-7 / §3.4-4）：优先用 characteristics 标定矩阵自建标准链
        // （ColorMatrix/ForwardMatrix 双光源 + Bradford D50→sRGB，与 Adobe/libraw 完整链同源）；
        // 开关关或标定数据缺失 → 回退 HAL 的 COLOR_CORRECTION_TRANSFORM（跨机型兜底）
        val stdCcm = if (appPrefs.getBoolean("ccm_std", true)) {
            StdColorMatrix.compute(ch, floatArrayOf(nR, nG, nB))
        } else {
            null
        }
        val ccm = stdCcm ?: halCcm
        // 噪声 σ（M3-2）：SENSOR_NOISE_PROFILE 按当前增益取最近条目，归一化到
        // [0,1] 线性域（σ_data / whiteLevel）；HAL 不回填时回退 0（引擎内估计）
        var noiseSigma = 0f
        if (Build.VERSION.SDK_INT >= 28) {
            // SENSOR_NOISE_PROFILE 的类型是 Pair<Double, Double[]>[]（Java 数组，不是
            // List）——v0.5.32 误写成 as? List<Any?>，该 cast 永远失败、σ 自适应从未生效
            //（编译器 "This cast can never succeed" 警告即此）。按数组 + 防御性解包；
            // 取增益最接近当前感度的一组，σ 数据域 → 归一化（/whiteLevel）
            @Suppress("UNCHECKED_CAST")
            val profile = result.get(CaptureResult.SENSOR_NOISE_PROFILE) as? Array<*>
            val wantGain = maxOf(result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100, 100) / 100.0
            var bestGain = Double.MAX_VALUE
            var bestSigma = 0.0
            profile?.forEach { item ->
                val pair = item as? android.util.Pair<*, *> ?: return@forEach
                val g = (pair.first as? Number)?.toDouble() ?: return@forEach
                val s = pair.second as? DoubleArray ?: return@forEach
                if (s.size < 3) return@forEach
                if (noiseSigma == 0f || kotlin.math.abs(g - wantGain) < bestGain) {
                    val avg = (s[0] + s[1] + s[2]) / 3.0
                    if (avg > 0.0 && whiteLevel > 0) {
                        bestGain = kotlin.math.abs(g - wantGain)
                        bestSigma = avg
                        noiseSigma = (avg / whiteLevel).toFloat()
                    }
                }
            }
        }
        // HAL 不回填时的回退：**按 ISO 估算**（v0.6.30）。
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
        if (noiseSigma == 0f) {
            val iso = (result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 200).coerceAtLeast(50)
            noiseSigma = (0.012 + 0.000008 * iso).toFloat()
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
                    // Gr/Gb 折成均值（两平面增益差异 <1%，色偏主因在 R/B 与 G 之间）
                    val buf = FloatArray(rows * cols * 3)
                    var i = 0
                    for (y in 0 until rows) for (x in 0 until cols) {
                        buf[i++] = m.getGainFactor(0, x, y)
                        buf[i++] = 0.5f * (m.getGainFactor(1, x, y) + m.getGainFactor(2, x, y))
                        buf[i++] = m.getGainFactor(3, x, y)
                    }
                    lsc = buf
                    lscCols = cols
                    lscRows = rows
                }
            }
        }
        return RenderClient.RawFrameMeta(
            colorFilterArrangement, black4, whiteLevel,
            floatArrayOf(nR, nG, nB), ccm, noiseSigma, lsc, lscCols, lscRows,
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
            when ((sensorOrientation / 90 + orientationStep) % 4) {
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

    private fun saveJpeg(rgba: ByteArray, w: Int, h: Int) {
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
        if (captureAspect > 0f) {
            val curA = w.toFloat() / h.toFloat()
            if (Math.abs(curA - captureAspect) > 0.01f) {
                val cw = if (curA > captureAspect) (h * captureAspect).toInt() else w
                val ch = if (curA > captureAspect) h else (w / captureAspect).toInt()
                outBmp = Bitmap.createBitmap(bmp, (w - cw) / 2, (h - ch) / 2, cw, ch)
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "camera", "photo.crop",
                    kv("srcA" to "%.3f".format(curA), "dstA" to "%.3f".format(captureAspect),
                        "out" to "${outBmp.width}x${outBmp.height}"),
                )
            }
        }
        val outW = outBmp.width
        val outH = outBmp.height
        val now = Date()
        val prefs = context.getSharedPreferences("filmkit", 0)
        // 成像质量：设置页可选 95（标准）/ 100（极高），prefs jpeg_quality
        val jpegQ = prefs.getInt("jpeg_quality", 95)
        // LUT 名写入文件名（设置页开关，prefs lut_in_name，默认开）：便于事后分辨哪张用了哪套风格；
        // 未选 LUT 时无后缀。只保留字母/数字/下划线/连字符（中文名照常保留），避免非法路径字符。
        val lutTag = currentLut?.title
            ?.takeIf { prefs.getBoolean("lut_in_name", true) }
            ?.replace(Regex("[^\\p{L}\\p{N}_-]"), "")
        val suffix = if (lutTag.isNullOrEmpty()) "" else "_$lutTag"
        val name = "FILMKIT_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)}$suffix.jpg"
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
                            outBmp.compress(Bitmap.CompressFormat.JPEG, jpegQ, it)
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
                    outBmp.compress(Bitmap.CompressFormat.JPEG, jpegQ, it)
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
                    "iso" to liveIso, "sec" to "%.4f".format(liveExposureNs / 1e9f),
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
