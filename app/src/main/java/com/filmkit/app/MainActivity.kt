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
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        PrimaryTabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> CameraScreen()
            1 -> SettingsScreen()
            2 -> SpikeScreen()
            else -> LogScreen()
        }
    }
}
