package com.filmkit.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/** 半圆弧变焦轮盘：指针固定在弧顶，刻度盘横向滚动（参照系统相机轮盘样式）。 */
private class Tick(val equiv: Float, val label: String, val sub: String?)

/** 等效焦段 → 轮盘对数位置 0..1（与 CameraScreen 拖动映射同式） */
private fun fracOf(e: Float, minE: Float, maxE: Float): Float =
    ((ln(e.coerceAtLeast(1f)) - ln(minE)) / (ln(maxE) - ln(minE))).coerceIn(0f, 1f)

/** 倍率标签：0.625→"0.6"、1→"1"、1.5→"1.5"、2.9→"2.9"、10→"10" */
private fun multLabel(v: Float): String {
    val one = (v * 10).roundToInt()
    return if (one % 10 == 0) "${one / 10}" else "%.1f".format(one / 10f)
}

/**
 * 弧形变焦刻度盘：弧顶白色指针为当前值，横向滚动刻度选择等效焦段。
 * 主刻度 = 原生焦段（倍率主标签 + mm 副标签）+ 数码档（1.5/2/3/5/10x），
 * 对数位置过密的档位自动跳过；跨过主刻度时轻触感。
 *
 * @param frac      当前对数位置 0..1（长焦=1）
 * @param equiv     当前等效焦段 mm（弧顶读数）
 * @param minE/maxE 轮盘端点（最小镜头 / 最大镜头×maxZoom）
 * @param mainE     倍率基线 = 主摄等效焦距
 * @param natives   原生镜头等效焦段（带 mm 副标签）
 */
@Composable
fun ZoomWheel(
    frac: Float,
    equiv: Float,
    minE: Float,
    maxE: Float,
    mainE: Float,
    natives: List<Int>,
    modifier: Modifier = Modifier,
) {
    val ticks = remember(minE, maxE, mainE, natives) {
        val digital = listOf(1.5f, 2f, 3f, 5f, 10f).map { it * mainE }.filter { it in minE..maxE }
        val all = (natives.map { it.toFloat() } + digital).sorted().distinct()
        val out = mutableListOf<Tick>()
        var prev = -1f
        for (e in all) {
            val f = fracOf(e, minE, maxE)
            if (prev >= 0f && f - prev < 0.055f) continue
            prev = f
            val isNative = natives.any { abs(it - e) < 0.6f }
            out += Tick(e, multLabel(e / mainE), if (isNative) "${e.roundToInt()} mm" else null)
        }
        out.toList()
    }

    // 跨过主刻度时轻触感（对齐系统相机轮盘）
    val haptic = LocalHapticFeedback.current
    var prevTick by remember { mutableStateOf<Float?>(null) }
    val nearest = ticks.minByOrNull { abs(fracOf(it.equiv, minE, maxE) - frac) }?.equiv
    LaunchedEffect(nearest) {
        if (nearest != null && prevTick != null && nearest != prevTick) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        prevTick = nearest
    }

    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
    }
    val subPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Column(modifier) {
        // 弧顶读数：当前倍率大字 + 等效焦段小字
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                multLabel(equiv / mainE) + "x",
                color = FilmTheme.Accent,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${equiv.roundToInt()} mm",
                color = FilmTheme.CreamDim,
                fontSize = 10.sp,
                fontFamily = FilmTheme.Mono,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp),
        ) {
            val w = size.width
            val h = size.height
            val r = w * 0.62f
            val cx = w / 2f
            val cy = h + r * 0.42f
            val arc = 115f // frac 0..1 对应的弧角
            val vis = 55f // 指针两侧可见范围
            val u = 4.dp.toPx()

            // 底部渐暗提高刻度可读性（叠预览）
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                    startY = h * 0.30f,
                    endY = h,
                ),
                topLeft = Offset(0f, h * 0.30f),
                size = Size(w, h * 0.70f),
            )

            // 细分刻度：对数均匀
            var t = 0f
            while (t <= 1.0001f) {
                val d = (t - frac) * arc
                if (abs(d) <= vis) {
                    val rad = Math.toRadians((90f - d).toDouble())
                    val c = cos(rad).toFloat()
                    val s = sin(rad).toFloat()
                    val a = ((vis - abs(d)) / 10f).coerceIn(0f, 1f) * 0.35f
                    drawLine(
                        Color.White.copy(alpha = a),
                        Offset(cx + r * c, cy - r * s),
                        Offset(cx + (r - u * 1.8f) * c, cy - (r - u * 1.8f) * s),
                        1.2f,
                        StrokeCap.Round,
                    )
                }
                t += 0.02f
            }

            // 主刻度 + 标签（沿弧切线倾斜，边缘渐隐）
            val near = ticks.minByOrNull { abs(fracOf(it.equiv, minE, maxE) - frac) }
            val nc = drawContext.canvas.nativeCanvas
            for (tick in ticks) {
                val tf = fracOf(tick.equiv, minE, maxE)
                val d = (tf - frac) * arc
                if (abs(d) > vis) continue
                val rad = Math.toRadians((90f - d).toDouble())
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                val fade = ((vis - abs(d)) / 12f).coerceIn(0f, 1f)
                val hot = tick === near
                drawLine(
                    (if (hot) FilmTheme.Accent else FilmTheme.Cream).copy(alpha = if (hot) fade else fade * 0.85f),
                    Offset(cx + r * c, cy - r * s),
                    Offset(cx + (r - u * 4f) * c, cy - (r - u * 4f) * s),
                    if (hot) u * 0.8f else u * 0.55f,
                    StrokeCap.Round,
                )
                val rl = r - u * 8f
                val x = cx + rl * c
                val y = cy - rl * s
                labelPaint.textSize = 13.sp.toPx()
                labelPaint.color = (if (hot) FilmTheme.Accent else Color.White).toArgb()
                labelPaint.alpha = (fade * 255).roundToInt().coerceAtMost(255)
                subPaint.textSize = 8.sp.toPx()
                subPaint.color = FilmTheme.CreamDim.toArgb()
                subPaint.alpha = (fade * 220).roundToInt().coerceAtMost(255)
                nc.save()
                nc.rotate(d, x, y)
                nc.drawText(tick.label, x, y + labelPaint.textSize * 0.35f, labelPaint)
                tick.sub?.let { nc.drawText(it, x, y + labelPaint.textSize * 1.7f, subPaint) }
                nc.restore()
            }

            // 弧顶指针（固定）
            drawLine(
                Color.White.copy(alpha = 0.95f),
                Offset(cx, cy - r + u * 1.5f),
                Offset(cx, cy - r - u * 3f),
                u * 0.8f,
                StrokeCap.Round,
            )
        }
    }
}
