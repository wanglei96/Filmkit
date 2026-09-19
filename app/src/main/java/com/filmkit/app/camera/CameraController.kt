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

    /** HAL 是否支持 COLOR_CORRECTION_MODE_TRANSFORM_MATRIX（不支持则手动 WB 退回 CCT 模式） */
    private var transformWbSupported = true

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
        if (!manualWb) {
            // 进手动瞬间冻结 HAL 估计增益与估计色温（此后 AWB 停更，锚点不漂移）
            val fallback = wbGains(if (estK > 0) estK else 6500)
            frozenGainR = if (estGainR > 0.05f) estGainR else fallback[0]
            frozenGainB = if (estGainB > 0.05f) estGainB else fallback[2]
            frozenK = if (estK > 0) estK else 6500
        }
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
        val lens = pool.filter { it.equivMm <= targetEquiv * 1.05f }.maxByOrNull { it.equivMm }
            ?: pool.minByOrNull { it.equivMm }
            ?: return
        val idx = backLenses.indexOf(lens)
        val newRatio = (targetEquiv / lens.equivMm).coerceIn(1f, maxZoom)
        if (idx != activeLensIdx) {
            zoomRatio = newRatio
            activeLensIdx = idx
            started = false
            stopInternalKeepClient()
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "zoom_equiv.lens_switch",
                kv("equiv" to "%.1f".format(targetEquiv), "lens" to lens.id,
                    "lensEquiv" to "%.0f".format(lens.equivMm), "zoom" to "%.2f".format(newRatio)),
            )
            openCamera(lens.id)
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
        val sig = "${evSteps}|${manualExposure}|${iso}|${exposureNs}|${wbMode}|${manualWb}|${wbK}|${zoomRatio}|${manualFocus}|${focusDiopter}|${focusSig}|${flashMode}"
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
    @Volatile var liveFocusDiopter = 0f
        private set

    // AWB 实时估计（仅 AWB AUTO 时更新）：来自结果变换矩阵对角线
    @Volatile private var estGainR = 0f
    @Volatile private var estGainB = 0f
    @Volatile private var estK = 0
    // 手动锚点：进手动瞬间冻结 HAL 估计增益与估计色温（PhotonCamera ManualWhiteBalanceAnchor 策略）
    private var frozenGainR = 0f
    private var frozenGainB = 0f
    private var frozenK = 0

    private val repeatCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult,
        ) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { liveIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { liveExposureNs = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { if (it > 0f) liveFocusDiopter = it }
            // X300 HAL 不报 COLOR_TEMPERATURE：从应用的 3x3 变换矩阵对角线反解色温
            var kEst = 0
            result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { t ->
                val r = t.getElement(0, 0).toFloat()
                val g = t.getElement(1, 1).toFloat()
                val b = t.getElement(2, 2).toFloat()
                if (g > 0.001f && r > 0.001f && b > 0.001f) {
                    estGainR = r / g
                    estGainB = b / g
                    kEst = kFromGains(estGainR, estGainB)
                }
            }
            if (kEst > 0) {
                liveK = kEst
                if (!manualWb) estK = kEst
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
        // WB：手动 K → AWB OFF + 3x3 变换矩阵做通道增益（API36 已移除 RB_GAINS，
        // 且 X300 HAL 实测忽略 CCT 直填——故以色温反演增益矩阵；不支持时退回 CCT 模式）
        if (manualWb) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            if (transformWbSupported) {
                // PhotonCamera ManualWhiteBalanceAnchor 策略：手动增益 = 冻结的 HAL 估计增益 ×
                // (目标色温增益 / 锚点色温增益)；±25K 死区内保持冻结增益，预览无跳变
                val g = wbGains(wbK)
                val ref = wbGains(frozenK.coerceAtLeast(2000))
                val nearAnchor = Math.abs(wbK - frozenK) <= 25
                val mulR = if (nearAnchor) 1f else g[0] / ref[0]
                val mulB = if (nearAnchor) 1f else g[2] / ref[2]
                fun r(f: Float) = android.util.Rational((f * 1000).toInt(), 1000)
                b.set(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
                )
                b.set(
                    CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                    android.hardware.camera2.params.ColorSpaceTransform(
                        arrayOf(
                            r((frozenGainR * mulR).coerceIn(0.25f, 4f)), r(0f), r(0f),
                            r(0f), r(1f), r(0f),
                            r(0f), r(0f), r((frozenGainB * mulB).coerceIn(0.25f, 4f)),
                        ),
                    ),
                )
            } else {
                b.set(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_CCT,
                )
                b.set(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, wbK)
                b.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, 0)
            }
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
        if (shutterSound) {
            shutterSoundPlayer.play(android.media.MediaActionSound.SHUTTER_CLICK)
        }
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
        transformWbSupported = ch.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)
            ?.contains(CameraCharacteristics.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX) == true
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
                        lastPhotoUri = uri
                        context.contentResolver.openOutputStream(uri)?.use {
                            outBmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
                        }
                    }
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FilmKit")
                dir.mkdirs()
                File(dir, name).outputStream().use {
                    outBmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
                }
            }
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "camera", "photo.saved",
                kv("name" to name, "w" to outW, "h" to outH, "avgLuma" to (sum / n.coerceAtLeast(1))),
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
}
