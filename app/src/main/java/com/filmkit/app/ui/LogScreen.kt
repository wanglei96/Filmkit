package com.filmkit.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmkit.app.EngineBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen() {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(emptyList<String>()) }

    fun load() {
        val blob = EngineBridge.nativeRecentLogs(500) ?: ""
        lines = blob.lineSequence().filter { it.isNotBlank() }.toList()
    }

    LaunchedEffect(Unit) { load() }

    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.bufferedWriter().use { w -> lines.forEach { w.appendLine(it) } }
            }
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_INFO, "app", "log.exported",
                "\"lines\":${lines.size}",
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(FilmTheme.Body)
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { load() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = FilmTheme.Amber,
                    contentColor = FilmTheme.OnAccent,
                ),
            ) { Text("刷新") }
            Button(
                onClick = {
                    val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    export.launch("filmkit-logs-$ts.jsonl")
                },
                enabled = lines.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FilmTheme.Amber,
                    contentColor = FilmTheme.OnAccent,
                    disabledContainerColor = FilmTheme.ChipBg,
                    disabledContentColor = FilmTheme.CreamDim,
                ),
            ) { Text("导出 JSONL") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "最近 ${lines.size} 条（环形缓冲，完整文件在应用 logs 目录）",
            color = FilmTheme.CreamDim,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(lines) { line ->
                Text(line, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = FilmTheme.CreamDim)
            }
        }
    }
}
