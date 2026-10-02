@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.filmkit.app.ui

import android.content.Context
import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import kotlinx.coroutines.launch
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
    private val onFftResult: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) : GLSurfaceView(context) {

    private class Renderer(
        val onCapabilities: (String) -> Unit,
        val onSpikeResult: (String) -> Unit,
        val onFftResult: (String) -> Unit,
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

        /** DFT 合并成本实测：3 变体（基线 / +twiddle 查表 / +寄存器 FFT）× 2 密度，
         *  同轮连跑 6 组以消除热状态差异。 */
        fun runFftSpike() {
            val w = 4096
            val h = 3072
            val iters = 6
            val names = arrayOf("基线", "+twiddle 查表", "+twiddle+寄存器 FFT")
            for (variant in 0..2) {
                for (overlap in listOf(false, true)) {
                    onStatus("DFT ${names[variant]}｜${if (overlap) "半重叠" else "非重叠"} ${w}×$h ×$iters …")
                    onFftResult(
                        EngineBridge.nativeRunFftSpike(w, h, iters, overlap, variant)
                            ?: "{\"ok\":false,\"error\":\"引擎返回空\"}",
                    )
                }
            }
            onStatus("DFT 合并 spike 完成（6 组）")
        }
    }

    private val renderer = Renderer(onCapabilities, onSpikeResult, onFftResult, onStatus)

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun rerun() {
        queueEvent { renderer.runSpike() }
    }

    fun rerunFft() {
        queueEvent { renderer.runFftSpike() }
    }
}

@Composable
fun SpikeScreen(onBack: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var capsJson by remember { mutableStateOf("{}") }
    var spikeJson by remember { mutableStateOf("") }
    var fftResults by remember { mutableStateOf(listOf<String>()) }
    var nnResults by remember { mutableStateOf(listOf<String>()) }
    var status by remember { mutableStateOf("等待 surface…") }
    var view by remember { mutableStateOf<SpikeGLView?>(null) }
    var auditPath by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(FilmTheme.Body)
            .statusBarsPadding()
            .padding(horizontal = 12.dp),
    ) {
        SubPageTopBar("能力 / Spike", onBack)
        Spacer(Modifier.height(4.dp))
        Text("状态：$status", color = FilmTheme.Cream, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        // 按钮用 FlowRow 自动换行:Row 不换行,四个按钮在窄屏会把后面的挤出屏幕外
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { view?.rerun() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = FilmTheme.Accent,
                    contentColor = FilmTheme.OnAccent,
                ),
            ) { Text("重新运行 spike") }
            Button(
                onClick = { view?.rerunFft() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = FilmTheme.Accent,
                    contentColor = FilmTheme.OnAccent,
                ),
            ) { Text("DFT 合并 spike（4096×3072）") }
            Button(
                onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        for (gpu in listOf(true, false)) {
                            status = "NN spike（${if (gpu) "GPU" else "CPU×4"}）运行中…"
                            val json = com.filmkit.app.NnSpike.run(context, gpu, 4)
                            EngineBridge.nativeAppendLog(
                                EngineBridge.LEVEL_INFO, "app", "spike.nn", json,
                            )
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                nnResults = nnResults + json
                                status = "NN spike（${if (gpu) "GPU" else "CPU×4"}）完成"
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = FilmTheme.Accent,
                    contentColor = FilmTheme.OnAccent,
                ),
            ) { Text("NN 降噪 spike（GPU+CPU）") }
            Button(
                onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        status = "NN 自检：启动拍照线程…"
                        val results = mutableListOf<String>()
                        if (!com.filmkit.app.camera.StillRenderClient.ensureStarted()) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                status = "NN 自检失败：拍照线程启动失败"
                            }
                            return@launch
                        }
                        status = "NN 自检运行中（域往返 + 真推理）…"
                        val latch = java.util.concurrent.CountDownLatch(1)
                        // 须在拍照线程（引擎 EGL context current）上跑 native 入口
                        com.filmkit.app.camera.StillRenderClient.runDiagnostic {
                            for (infer in listOf(false, true)) {
                                val json = EngineBridge.nativeRunNnRoundtrip(infer)
                                    ?: "{\"ok\":false,\"error\":\"引擎返回空\"}"
                                EngineBridge.nativeAppendLog(
                                    EngineBridge.LEVEL_INFO, "app", "spike.nn_roundtrip", json,
                                )
                                results += json
                            }
                            latch.countDown()
                        }
                        latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            nnResults = nnResults + results
                            status = "NN 自检完成（先域往返后真推理）"
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = FilmTheme.Accent,
                    contentColor = FilmTheme.OnAccent,
                ),
            ) { Text("NN 链路自检（域往返+推理）") }
            Button(
                onClick = {
                    auditPath = com.filmkit.app.camera.CameraAudit.export(context)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = FilmTheme.Accent,
                    contentColor = FilmTheme.OnAccent,
                ),
            ) { Text("导出相机审计") }
        }
        auditPath?.let {
            Text("审计文件：$it", color = FilmTheme.CreamDim, style = MaterialTheme.typography.labelSmall)
        }
        // GLSurfaceView 只承载 GL 上下文与离屏渲染，显示上占位即可
        AndroidView(
            factory = { ctx ->
                SpikeGLView(
                    ctx,
                    onCapabilities = { capsJson = it },
                    onSpikeResult = { spikeJson = it },
                    onFftResult = { fftResults = fftResults + it },
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
        fftResults.forEachIndexed { i, json ->
            val obj = JSONObject(json)
            val names = arrayOf("基线", "+twiddle 查表", "+twiddle+寄存器 FFT")
            val vn = names.getOrElse(obj.optInt("variant", 0)) { "变体${obj.optInt("variant", 0)}" }
            val density = if (obj.optBoolean("overlap")) "半重叠" else "非重叠"
            Spacer(Modifier.height(8.dp))
            Section("DFT #${i + 1}｜$vn｜$density", flatten(obj))
        }
        nnResults.forEachIndexed { i, json ->
            Spacer(Modifier.height(8.dp))
            Section("NN #${i + 1}", flatten(JSONObject(json)))
        }
    }
}

@Composable
private fun Section(title: String, lines: List<Pair<String, String>>) {
    Text(title, color = FilmTheme.Cream, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        lines.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(k, Modifier.width(170.dp), color = FilmTheme.CreamDim, style = MaterialTheme.typography.bodySmall)
                Text(v, color = FilmTheme.CreamDim, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
