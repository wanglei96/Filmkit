package com.filmkit.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.filmkit.app.EngineBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** M2 能力审计：逐相机 dump 关键特性为 JSON，存应用目录（§9.2-1）。 */
object CameraAudit {

    fun export(context: Context): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        // 公开 ID + 隐藏物理镜头暴力探测（参考 PhotonCamera；vivo/小米普遍隐藏超广/长焦）
        val ids = sortedSetOf<Int>()
        cm.cameraIdList.forEach { it.toIntOrNull()?.let(ids::add) }
        if (android.os.Build.MANUFACTURER.lowercase() !in setOf("huawei", "honor")) {
            for (i in 0..6) ids.add(i)
        }
        val root = JSONArray()
        for (n in ids.sorted()) {
            try {
                root.put(cameraJson(cm, n.toString()))
            } catch (_: Exception) {
            }
        }
        val out = JSONObject()
        out.put("cameras", root)
        val text = out.toString(2)
        val dir = File(context.getExternalFilesDir(null), "audit").apply { mkdirs() }
        val f = File(dir, "camera-audit-${System.currentTimeMillis()}.json")
        f.writeText(text)
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "camera", "audit.exported",
            com.filmkit.app.kv("path" to f.absolutePath),
        )
        return f.absolutePath
    }

    private fun intJson(a: IntArray?): JSONArray {
        val j = JSONArray()
        a?.forEach { j.put(it) }
        return j
    }

    private fun strJson(l: List<String>): JSONArray {
        val j = JSONArray()
        l.forEach { j.put(it) }
        return j
    }

    private fun cameraJson(cm: CameraManager, id: String): JSONObject {
        val ch = cm.getCameraCharacteristics(id)
        val o = JSONObject()
        o.put("id", id)
        o.put("facing", ch.get(CameraCharacteristics.LENS_FACING) ?: -1)
        o.put("hardwareLevel", ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1)
        o.put(
            "capabilities",
            strJson(
                ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.map { it.toString() } ?: emptyList(),
            ),
        )
        ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let {
            o.put("pixelArray", "${it.width}x${it.height}")
        }
        o.put("sensorOrientation", ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1)
        o.put(
            "colorFilterArrangement",
            ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1,
        )
        o.put("blackLevelPattern", ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.toString() ?: "null")
        o.put("maxAnalogSensitivity", ch.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY) ?: -1)
        o.put(
            "oisAvailable",
            intJson(ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)),
        )
        o.put("maxSyncLatency", ch.get(CameraCharacteristics.SYNC_MAX_LATENCY) ?: -1)
        // 几何/畸变能力（v0.10.109-exp7 探测）：定"超广角畸变校正走 HAL 还是引擎 GPU"。
        // LENS_DISTORTION = HAL 提供的畸变标定系数（有它就不必自拍棋盘格标定）。
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            o.put(
                "distortionCorrectionModes",
                intJson(ch.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)),
            )
            o.put("lensDistortion", ch.get(CameraCharacteristics.LENS_DISTORTION)?.joinToString(",") ?: "null")
        }
        o.put(
            "lensIntrinsicCalibration",
            ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.joinToString(",") ?: "null",
        )

        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        o.put(
            "rawOutputSizes",
            strJson(map?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
                ?.map { "${it.width}x${it.height}" } ?: emptyList()),
        )
        o.put(
            "yuvOutputSizes",
            strJson(map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                ?.take(8)?.map { "${it.width}x${it.height}" } ?: emptyList()),
        )
        o.put(
            "availResultKeyCount",
            ch.availableCaptureResultKeys.size,
        )
        return o
    }
}
