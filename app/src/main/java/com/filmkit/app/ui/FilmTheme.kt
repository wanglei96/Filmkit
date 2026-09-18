package com.filmkit.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * 胶片机体质感主题（UI 专项定稿方向 C：PhotonCamera 结构骨架 + DAZZ 质感点缀）。
 * 色板：炭黑机身 × 奶油白文字 × 琥珀橙强调；实测读数用等宽字，胶片型号用衬线字。
 */
object FilmTheme {
    val Body = Color(0xFF0D0D0D) // 机身炭黑（整屏底）
    val Panel = Color(0xE6141414) // 面板半透明炭黑（叠加预览之上）
    val ChipBg = Color(0xFF1C1C1C) // 未选中控件底
    val ChipBorder = Color(0xFF333333) // 控件描边
    val Cream = Color(0xFFF2EAD9) // 奶油白（主文字 / 快门）
    val CreamDim = Color(0x99F2EAD9) // 奶油白次级
    val Amber = Color(0xFFE8842C) // 琥珀橙（选中 / 强调 / 标签）
    val OnAccent = Color(0xFF141414) // 琥珀底上的深字

    val Mono = FontFamily.Monospace // 实测数字（ISO/快门/EV/K）
    val Serif = FontFamily.Serif // 胶片型号名
}
