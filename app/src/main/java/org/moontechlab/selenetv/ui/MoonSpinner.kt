package org.moontechlab.selenetv.ui

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 月牙加载动画：月牙 6s 旋转 + 3s 柔光呼吸，外环星点反向缓转、一颗亮星巡天。
 */
@Composable
fun MoonSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
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
    // 外环反向缓转：14s 一圈，与月牙形成视差
    val halo by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "halo",
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        // 外环：星点 + 一颗巡天的亮星，反向旋转
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = -halo },
        ) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val ringR = this.size.minDimension / 2f * 0.92f
            val dotCount = 42
            // 静态星环（对称，旋转时看不出转动，仅作底纹）
            for (i in 0 until dotCount) {
                val a = (2.0 * Math.PI * i / dotCount).toFloat()
                drawCircle(
                    color = Color.White.copy(alpha = 0.10f),
                    radius = 1.1.dp.toPx(),
                    center = Offset(cx + ringR * cos(a), cy + ringR * sin(a)),
                )
            }
            // 巡天亮星（位于环顶，随画布旋转沿环游走）
            val starCenter = Offset(cx, cy - ringR)
            drawCircle(
                color = Color.White.copy(alpha = 0.22f),
                radius = 5.dp.toPx(),
                center = starCenter,
            )
            drawCircle(
                color = Color(0xFFFAFAFA),
                radius = 2.dp.toPx(),
                center = starCenter,
            )
        }

        // 月牙：旋转 + 柔光呼吸
        Canvas(
            modifier = Modifier
                .size(size * (64f / 132f))
                .graphicsLayer { rotationZ = rotation },
        ) {
            val w = this.size.width
            val h = this.size.height
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
                        size = Size(outerR * 2, outerR * 2),
                    ),
                    startAngleDegrees = -90f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = true,
                )
                // 内半椭圆（回到顶部，留出月牙的"缺口"）
                arcTo(
                    rect = Rect(
                        offset = Offset(cx - innerRx, cy - innerRy),
                        size = Size(innerRx * 2, innerRy * 2),
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
    }
}
