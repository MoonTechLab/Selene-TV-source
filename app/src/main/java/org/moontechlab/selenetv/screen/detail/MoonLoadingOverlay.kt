package org.moontechlab.selenetv.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.moontechlab.selenetv.ui.MoonSpinner

/**
 * 月神国风加载层：月牙 6s 旋转 + 3s 柔光呼吸，外环星点反向缓转、一颗亮星巡天，
 * 衬以电影感暗角，主信息为片名 + 国风字距「正在载入」。
 */
@Composable
fun MoonLoadingOverlay(
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // 电影感暗角：中心微亮聚焦，边缘压暗遮蔽背后繁杂海报
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.50f),
                        Color.Black.copy(alpha = 0.82f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            MoonSpinner(size = 132.dp)

            Text(
                text = title,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 16.sp,
                fontWeight = FontWeight.W600,
            )
            Text(
                text = "正在载入",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp,
                letterSpacing = 6.sp,
            )
        }
    }
}
