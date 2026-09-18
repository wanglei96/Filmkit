package com.filmkit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.filmkit.app.ui.CameraScreen
import com.filmkit.app.ui.LogScreen
import com.filmkit.app.ui.SettingsScreen
import com.filmkit.app.ui.SpikeScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineBridge.ensureLoaded()
        setContent {
            MaterialTheme {
                M0App()
            }
        }
    }
}

@Composable
private fun M0App() {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("相机", "设置", "能力 / Spike", "日志")
    Column(
        Modifier
            .fillMaxSize()
            // 相机页自己只做 statusBarsPadding（底部延伸到手势条之下，不留白）；
            // 其他页维持全安全区 padding
            .then(
                if (tab != 0) {
                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                } else {
                    Modifier
                },
            ),
    ) {
        // 相机页沉浸化（UI 专项方向 C）：隐藏调试 Tab 条，经相机页 ⚙ 进入设置页
        if (tab != 0) {
            PrimaryTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
        }
        when (tab) {
            0 -> CameraScreen(onOpenSettings = { tab = 1 })
            1 -> SettingsScreen()
            2 -> SpikeScreen()
            else -> LogScreen()
        }
    }
}
