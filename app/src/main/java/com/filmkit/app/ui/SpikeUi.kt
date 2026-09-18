package com.filmkit.app.ui

import android.content.Context
import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.filmkit.app.EngineBridge
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 JSONObject 展开成「key: value」行，用于 M0 调试界面；数组只显示条数。
 */
private fun flatten(json: JSONObject, skip: Set<String> = emptySet()): List<Pair<String, String>> {
    return json.keys().asSequence()
        .filter { it !in skip }
        .sorted()
        .map { key ->
            val v = json.get(key)
            val text = when {
                v is JSONObject -> "…(${v.length()} 项)"
                v is JSONArray -> "…(${v.length()} 项)"
                v == JSONObject.NULL -> "null"
                else -> v.toString()
            }
            key to text
        }
        .toList()
}

private fun spikeLines(json: JSONObject): List<Pair<String, String>> {
    val lines = flatten(json, skip = setOf("passes", "error")).toMutableList()
    json.optString("error").takeIf { it.isNotEmpty() && it != "null" }?.let { lines += ("error" to it) }
    val passes = json.optJSONArray("passes") ?: return lines
    for (i in 0 until passes.length()) {
        val p = passes.optJSONObject(i) ?: continue
        val gpu = p.optDouble("gpuMedianMs", -1.0)
        val gpuTxt = if (gpu >= 0) {
            "%.3f ms (p90 %.3f)".format(gpu, p.optDouble("gpuP90Ms", -1.0))
        } else {
            "无计时器"
        }
        lines += "pass ${p.optString("name")}" to "gpu $gpuTxt ｜ cpu %.3f ms".format(p.optDouble("cpuMedianMs", -1.0))
    }
    return lines
}

/**
 * M0 spike 视图：ES 3.x 上下文由 GLSurfaceView 管理，surface 创建后
 * 在 GL 线程（有当前上下文）跑能力探测与 spike。
 */
class SpikeGLView(
    context: Context,
    private val onCapabilities: (String) -> Unit,
    private val onSpikeResult: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) : GLSurfaceView(context) {

    private class Renderer(
        val onCapabilities: (String) -> Unit,
        val onSpikeResult: (String) -> Unit,
        val onStatus: (String) -> Unit,
    ) : GLSurfaceView.Renderer {

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            onStatus("检测 GL 能力…")
            val caps = EngineBridge.nativeDetectCapabilities() ?: "{}"
            onCapabilities(caps)
            runSpike()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = Unit

        override fun onDrawFrame(gl: GL10?) = Unit

        fun runSpike() {
            onStatus("运行 spike（1024² × 10 轮）…")
            val result = EngineBridge.nativeRunSpike(1024, 1024, 10, true)
            onSpikeResult(result ?: "{\"ok\":false,\"error\":\"引擎返回空\"}")
            onStatus(if (result != null && JSONObject(result).optBoolean("ok")) "完成" else "失败")
        }
    }

    private val renderer = Renderer(onCapabilities, onSpikeResult, onStatus)

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun rerun() {
        queueEvent { renderer.runSpike() }
    }
}

@Composable
fun SpikeScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var capsJson by remember { mutableStateOf("{}") }
    var spikeJson by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("等待 surface…") }
    var view by remember { mutableStateOf<SpikeGLView?>(null) }
    var auditPath by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        Text("状态：$status", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { view?.rerun() }) { Text("重新运行 spike") }
            Button(onClick = {
                auditPath = com.filmkit.app.camera.CameraAudit.export(context)
            }) { Text("导出相机审计") }
        }
        auditPath?.let {
            Text("审计文件：$it", style = MaterialTheme.typography.labelSmall)
        }
        // GLSurfaceView 只承载 GL 上下文与离屏渲染，显示上占位即可
        AndroidView(
            factory = { ctx ->
                SpikeGLView(
                    ctx,
                    onCapabilities = { capsJson = it },
                    onSpikeResult = { spikeJson = it },
                    onStatus = { status = it },
                ).also { view = it }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .padding(vertical = 8.dp),
        )
        Section("GL 能力", flatten(JSONObject(capsJson).optJSONObject("caps") ?: JSONObject(capsJson)))
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        if (spikeJson.isNotEmpty()) {
            Section("Spike 结果", spikeLines(JSONObject(spikeJson)))
        }
    }
}

@Composable
private fun Section(title: String, lines: List<Pair<String, String>>) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        lines.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(k, Modifier.width(170.dp), style = MaterialTheme.typography.bodySmall)
                Text(v, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
