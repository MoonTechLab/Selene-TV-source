package org.moontechlab.selenetv.screen.detail

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import android.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * 月牙加载覆盖层：6s 匀速旋转 + 3s 柔光呼吸。Canvas 绘月牙形状。
 */
@Composable
fun MoonLoadingOverlay(
    title: String,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "moon")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )
    val glow by infinite.animateFloat(
        initialValue = 8f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .size(64.dp)
                    .graphicsLayer { rotationZ = rotation },
            ) {
                val w = size.width
                val h = size.height
                // 外圆半径 45%，内椭圆半径 32%×45%
                val outerR = w * 0.45f
                val innerRx = w * 0.32f
                val innerRy = h * 0.45f
                val cx = w / 2f
                val cy = h / 2f

                val path = Path().apply {
                    // 外半圆（从顶部顺时针走到底部）
                    arcTo(
                        rect = Rect(
                            offset = Offset(cx - outerR, cy - outerR),
                            size = androidx.compose.ui.geometry.Size(outerR * 2, outerR * 2),
                        ),
                        startAngleDegrees = -90f,
                        sweepAngleDegrees = 180f,
                        forceMoveTo = true,
                    )
                    // 内半椭圆（回到顶部，留出月牙的"缺口"）
                    arcTo(
                        rect = Rect(
                            offset = Offset(cx - innerRx, cy - innerRy),
                            size = androidx.compose.ui.geometry.Size(innerRx * 2, innerRy * 2),
                        ),
                        startAngleDegrees = 90f,
                        sweepAngleDegrees = -180f,
                        forceMoveTo = false,
                    )
                    close()
                }

                drawIntoCanvas { canvas ->
                    val paint = Paint().apply {
                        color = Color(0xFFFAFAFA)
                        asFrameworkPaint().apply {
                            isAntiAlias = true
                            setShadowLayer(glow, 0f, 0f, android.graphics.Color.argb(120, 255, 255, 255))
                            maskFilter = BlurMaskFilter(glow * 0.3f, BlurMaskFilter.Blur.NORMAL)
                        }
                    }
                    canvas.drawPath(path, paint)
                }
            }

            Text(
                text = title,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 16.sp,
                fontWeight = FontWeight.W600,
            )
            Text(
                text = "SELENE · LOADING",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
            )
        }
    }
}
