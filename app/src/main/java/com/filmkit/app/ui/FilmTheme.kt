package com.filmkit.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * 胶片机体质感主题（UI 专项定稿方向 C：PhotonCamera 结构骨架 + DAZZ 质感点缀）。
 * 色板：炭黑机身 × 奶油白文字 × 可配置强调色；实测读数用等宽字，胶片型号用衬线字。
 *
 * 强调色（Accent）是全局唯一强调 token：按钮选中、刻度、开关选中轨道、滑杆活动轨等。
 * 默认琥珀橙；设置-系统颜色 可换，经 prefs(accent_argb) 持久化，MainActivity 启动时载入。
 * 全部 UI 只允许引用这里声明的颜色，禁止新造颜色值（UI 设计规范见 docs/开发计划.md）。
 */
object FilmTheme {
    val DEFAULT_ACCENT = Color(0xFFE8842C) // 琥珀橙

    private val accentState = mutableStateOf(DEFAULT_ACCENT)

    /** 全局强调色（可配置，默认琥珀橙） */
    val Accent: Color get() = accentState.value

    fun setAccent(c: Color) {
        accentState.value = c
    }

    val Body = Color(0xFF0D0D0D) // 机身炭黑（整屏底）
    val Panel = Color(0xE6141414) // 面板半透明炭黑（叠加预览之上）
    val ChipBg = Color(0xFF1C1C1C) // 未选中控件底
    val ChipBorder = Color(0xFF333333) // 控件描边
    val Cream = Color(0xFFF2EAD9) // 奶油白（主文字 / 快门）
    val CreamDim = Color(0x99F2EAD9) // 奶油白次级
    val OnAccent = Color(0xFF141414) // 强调色底上的深字

    val Mono = FontFamily.Monospace // 实测数字（ISO/快门/EV/K）
    val Serif = FontFamily.Serif // 胶片型号名
}

/**
 * 统一浮层动效（规范：面板/菜单出入场只用这里定义的转场，禁止各自另配 tween/spring）。
 * 滑入用 spring（有生命感），滑出用短 tween（干脆），淡入快、淡出更短。
 */
object FilmMotion {
    val panelEnter = androidx.compose.animation.slideInVertically(
        androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 380f),
    ) { it / 2 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(90))
    val panelExit = androidx.compose.animation.slideOutVertically(
        androidx.compose.animation.core.tween(150),
    ) { it / 2 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(110))
    val dropEnter = androidx.compose.animation.slideInVertically(
        androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 380f),
    ) { -it / 3 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(90))
    val dropExit = androidx.compose.animation.slideOutVertically(
        androidx.compose.animation.core.tween(150),
    ) { -it / 3 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(110))
}
