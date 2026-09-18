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
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
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

    var sensorOrientation = 90
        private set
    var previewSize = Size(1440, 1080)
        private set
    var stillSize = Size(4096, 3072)
        private set

    @Volatile
    private var filmParams: DoubleArray = CameraPresets.negative400()

    @Volatile
    private var fillTest = false

    /** 显示方向步进（0..3）。X300 实测 0 为正确方向（用户已验证），界面可循环切换 */
    @Volatile
    private var orientationStep = 0

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

    @Volatile
    private var manualWb = false

    var maxAnalogIso = 3200
        private set
    var baseFocalMm = 23f
        private set

    fun setManualExposure(on: Boolean) {
        manualExposure = on
        rebuildRepeating()
    }

    fun setIso(v: Int) {
        iso = v.coerceIn(100, maxAnalogIso)
        if (manualExposure) rebuildRepeating()
    }

    fun setExposureNs(ns: Long) {
        exposureNs = ns.coerceIn(100_000L, 1_000_000_000L)
        if (manualExposure) rebuildRepeating()
    }

    /** 手动白平衡：K 色温直填（API36 走 COLOR_TEMPERATURE 键） */
    fun setWbK(k: Int) {
        wbK = k.coerceIn(2000, 8000)
        manualWb = true
        rebuildRepeating()
    }

    fun setAutoWb() {
        manualWb = false
        rebuildRepeating()
    }

    private var activeArray = android.graphics.Rect(0, 0, 4096, 3072)
    var maxZoom = 1f
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
            stopInternalKeepClient()
            openCamera(lens.id)
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
        started = false // 复用 start() 重新打开指定镜头
        stopInternalKeepClient()
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "lens.switch",
            kv("to" to idx, "id" to lens.id, "equiv" to "%.0fMM".format(lens.equivMm)),
        )
        openCamera(lens.id)
    }

    fun setZoom(ratio: Float) {
        zoomRatio = ratio.coerceIn(1f, maxZoom)
        rebuildRepeating()
    }

    fun setEv(ev: Float) {
        evSteps = (ev / evStep).toInt().coerceIn(evRange.lower, evRange.upper)
        rebuildRepeating()
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
        val sig = "${evSteps}|${manualExposure}|${iso}|${exposureNs}|${wbMode}|${manualWb}|${wbK}|${zoomRatio}|${manualFocus}|${focusDiopter}|${focusSig}"
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
                    "iso" to iso, "sec" to "%.4f".format(exposureNs / 1e9f),
                    "wb" to (if (manualWb) "${wbK}K" else "AUTO"),
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

    private val repeatCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult,
        ) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { liveIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { liveExposureNs = it }
            result.get(CaptureResult.COLOR_CORRECTION_COLOR_TEMPERATURE)?.let { liveK = it }
        }
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
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps)
        }
        // WB：手动 K → 色温直填（API36 已移除 RB_GAINS 键）；否则 AWB 场景模式
        if (manualWb) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            b.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_CCT,
            )
            b.set(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, wbK)
            b.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, 0)
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, wbMode)
        }
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

    private fun stopInternalKeepClient() {
        // 先在调用线程捕获旧引用并摘下字段：openCamera 紧随其后会赋新值，
        // 清理必须针对旧实例，否则可能把新 SurfaceTexture/Reader 释放掉
        val cam = camera
        val ses = session
        val reader = stillReader
        val st = surfaceTexture
        val ps = previewSurface
        camera = null
        session = null
        stillReader = null
        surfaceTexture = null
        previewSurface = null
        render.post {
            try { ses?.stopRepeating() } catch (_: Throwable) {}
            cam?.close()
            reader?.close()
            // 切镜头只换采集源：旧 SurfaceTexture/Surface 必须释放，否则每次切换泄漏一套
            st?.release()
            ps?.release()
        }
    }

    fun setFilmParams(p: DoubleArray) {
        filmParams = p
    }

    /** 真机二分定位开关：true 时 pass1 用梯度填充替代相机采样。 */
    fun setFillTest(v: Boolean) {
        fillTest = v
    }

    fun setOrientationStep(step: Int) {
        orientationStep = ((step % 4) + 4) % 4
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "orientation.set",
            kv("step" to orientationStep),
        )
    }

    fun orientationStep(): Int = orientationStep

    fun start(surface: Surface) {
        if (started) return  // surfaceCreated 可能多次回调，防重入
        if (!render.start(surface)) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "render.start_failed", "{}",
            )
            return
        }
        started = true
        openCamera()
    }

    fun capture() {
        val s = session ?: return
        if (capturing) return
        capturing = true
        try {
            val b = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(stillReader!!.surface)
                applyControls(this) // 静拍继承 M 档控制（WB/EV/OIS/变焦/对焦区域）
            }
            s.capture(b.build(), null, render.looper?.let { android.os.Handler(it) })
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
        render.post {
            try { session?.stopRepeating() } catch (_: Throwable) {}
            camera?.close()
            camera = null
            session = null
            stillReader?.close()
            stillReader = null
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
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            .sortedByDescending { it.width.toLong() * it.height }

        // sizes 为降序：firstOrNull{ ≤1440 } = 不超过上限的最大 4:3 尺寸
        previewSize = sizes.filter { it.width.toLong() * 3 == it.height.toLong() * 4 }
            .firstOrNull { it.width <= 1440 }
            ?: sizes.firstOrNull { it.width.toLong() * it.height <= 1920L * 1080 }
            ?: sizes.last()

        // 静拍流：YUV_420_888 最大分辨率（X300 主摄 4096×3072）
        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            .sortedByDescending { it.width.toLong() * it.height }
        stillSize = yuvSizes.first()

        stillReader = ImageReader.newInstance(
            stillSize.width, stillSize.height, ImageFormat.YUV_420_888, 2,
        ).apply {
            setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.let { img -> submitStill(img) }
            }, render.looper?.let { android.os.Handler(it) })
        }

        // OES 纹理在渲染上下文创建（SurfaceTexture 绑定它）
        val texId = render.createOesTexture()
        if (texId == 0) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "oes.create_failed", "{}",
            )
            return
        }
        val st = SurfaceTexture(texId)
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        st.setOnFrameAvailableListener({ texture ->
            if (!firstFrameLogged) {
                firstFrameLogged = true
                EngineBridge.nativeAppendLog(
                    EngineBridge.LEVEL_INFO, "camera", "camera.first_frame", "{}",
                )
            }
            render.frameAvailable(
                texture, previewSize.width, previewSize.height,
                orientationStep, filmParams, fillTest,
            )
        }, render.looper?.let { android.os.Handler(it) })
        surfaceTexture = st
        previewSurface = Surface(st)

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
                        "sensorOrientation" to sensorOrientation,
                        "ois" to oisSupported,
                        "path" to "surfacetexture+still",
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
        render.submitStill(img, orientationStep, filmParams) { bytes, w, h ->
            capturing = false
            if (bytes != null) saveJpeg(bytes, w, h)
        }
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
        val name = "FILMKIT_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.jpg"
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilmKit")
                }
                context.contentResolver
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?.let { uri ->
                        context.contentResolver.openOutputStream(uri)?.use {
                            bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
                        }
                    }
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FilmKit")
                dir.mkdirs()
                File(dir, name).outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
                }
            }
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "photo.saved",
                kv("name" to name, "w" to w, "h" to h, "avgLuma" to (sum / n.coerceAtLeast(1))),
            )
        } catch (t: Throwable) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_ERROR, "camera", "photo.save_failed",
                kv("error" to (t.message ?: t.toString())),
            )
        } finally {
            bmp.recycle()
        }
    }
}
