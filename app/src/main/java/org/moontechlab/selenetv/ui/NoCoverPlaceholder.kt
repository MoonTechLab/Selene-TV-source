package org.moontechlab.selenetv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * 封面缺失 / 加载失败时的兜底占位:自适应填满父级,衬在卡片现有深色背景上居中显示一个手绘
 * 「图片」图标(圆角框 + 太阳 + 山)+ 「暂无封面」文字。单色克制、手绘不引依赖,适配任意卡片尺寸/比例。
 * 自身不画背景,沿用调用方卡片底色。
 */
@Composable
fun NoCoverPlaceholder(modifier: Modifier = Modifier) {
    val tint = Color.White.copy(alpha = 0.32f)
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(modifier = Modifier.size(34.dp)) {
            val w = size.width
            val h = size.height
            val sw = w * 0.06f
            // 外框圆角矩形
            drawRoundRect(
                color = tint,
                topLeft = Offset(sw, sw),
                size = Size(w - 2 * sw, h - 2 * sw),
                cornerRadius = CornerRadius(w * 0.12f),
                style = Stroke(width = sw),
            )
            // 太阳(小圆)
            drawCircle(color = tint, radius = w * 0.10f, center = Offset(w * 0.34f, h * 0.36f))
            // 山(折线三角)
            val mountain = Path().apply {
                moveTo(w * 0.20f, h * 0.74f)
                lineTo(w * 0.46f, h * 0.50f)
                lineTo(w * 0.62f, h * 0.66f)
                lineTo(w * 0.80f, h * 0.46f)
                lineTo(w * 0.80f, h * 0.74f)
                close()
            }
            drawPath(mountain, color = tint)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "暂无封面", color = tint, fontSize = 12.sp, fontWeight = FontWeight.W500)
    }
}
