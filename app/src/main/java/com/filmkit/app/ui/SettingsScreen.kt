package com.filmkit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmkit.app.EngineBridge
import com.filmkit.app.camera.CameraAudit
import com.filmkit.app.camera.CameraController

/** 设置页（§0-D3/D15）：DNG 存储开关、能力审计入口、版本信息。 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }
    var dngEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean("dng_enabled", false)) }
    var auditPath by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
    ) {
        Text("设置", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("DNG 原始格式存储", color = Color.White)
                Text(
                    "快门同时保存 DNG 底片 + 胶片 JPEG（单张约 10–20MB）",
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Switch(
                checked = dngEnabled,
                onCheckedChange = { on ->
                    dngEnabled = on
                    prefs.edit().putBoolean("dng_enabled", on).apply()
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_INFO, "app", "settings.dng",
                        "{\"enabled\":$on}",
                    )
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        Divider(color = Color(0xFF333333))
        Spacer(Modifier.height(8.dp))

        Text("镜头配置", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(
            "探测到的物理镜头；部分厂商隐藏镜头可能输出异常，可在此关闭",
            color = Color.Gray,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(4.dp))
        val cm = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val prefs = remember { context.getSharedPreferences("filmkit", 0) }
        val lenses = remember { CameraController.LensDiscovery.discoverBackLenses(cm, prefs) }
        if (lenses.isEmpty()) {
            Text("（未探测到后置镜头）", color = Color.Gray, fontSize = 11.sp)
        }
        lenses.forEach { lens ->
            val key = "lens_on_${lens.id}"
            var on by rememberSaveable(lens.id) { mutableStateOf(prefs.getBoolean(key, true)) }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "ID ${lens.id} · ${lens.label()}",
                        color = Color.White,
                    )
                    Text(
                        "物理焦距 ${lens.focalMm}mm${if (lens.rawSupported) " · RAW" else ""}",
                        color = Color.Gray,
                        fontSize = 10.sp,
                    )
                }
                Switch(
                    checked = on,
                    onCheckedChange = { v ->
                        on = v
                        prefs.edit().putBoolean(key, v).apply()
                        EngineBridge.nativeAppendLog(
                            EngineBridge.LEVEL_INFO, "camera", "lens.enabled_set",
                            com.filmkit.app.kv("id" to lens.id, "enabled" to v),
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Divider(color = Color(0xFF333333))
        Spacer(Modifier.height(8.dp))

        Text("诊断", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { auditPath = CameraAudit.export(context) }) {
            Text("导出相机能力审计")
        }
        auditPath?.let {
            Spacer(Modifier.height(4.dp))
            Text("已导出：$it", color = Color.Gray, fontSize = 10.sp)
        }
        Text(
            "完整日志在「日志」页导出（JSONL）",
            color = Color.Gray,
            style = MaterialTheme.typography.labelSmall,
        )

        Spacer(Modifier.height(16.dp))
        Divider(color = Color(0xFF333333))
        Spacer(Modifier.height(8.dp))
        Text("FilmKit · 胶片相机", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        Text("M2 开发中 · vivo X300 专项适配", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
    }
}
