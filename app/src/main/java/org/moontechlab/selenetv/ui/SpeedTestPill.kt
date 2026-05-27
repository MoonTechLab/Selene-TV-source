package org.moontechlab.selenetv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.moontechlab.selenetv.screen.detail.MoonCrescentSpinner

// 焦点态黑白反色（与 DetailActionButtons 的动作按钮同一约定）：聚焦白底深字，非聚焦半透明白底白字。
internal val PillUnfocusedBg = Color.White.copy(alpha = 0.15f)
internal val PillFocusedBg = Color.White
internal val PillFocusedFg = Color(0xFF0A0A0A)

/**
 * 源 rail 的前导测速胶囊：卡片尺寸的瓦片（与 VideoCard LARGE 封面同宽高，顶部对齐）。
 * 待命态：自绘闪电 + 「测速优选」；测速中：月牙 spinner + 进度 + 「按 OK 停止」。
 * 内容随焦点黑白反色，不使用任何强调色。
 */
@Composable
internal fun SpeedTestPill(
    testing: Boolean,
    testedCount: Int,
    totalCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 120.dp,
    height: Dp = 180.dp,
    idleSubtitle: String = "自动选最优源",
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "speedtest_pill_scale",
    )
    val fg = if (focused) PillFocusedFg else Color.White
    val subFg = fg.copy(alpha = 0.55f)
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .width(width)
            .height(height),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = PillUnfocusedBg,
            focusedContainerColor = PillFocusedBg,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (testing) {
                MoonCrescentSpinner(modifier = Modifier.size(34.dp), color = fg)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "测速中", color = fg, fontSize = 15.sp, fontWeight = FontWeight.W700)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$testedCount/$totalCount",
                    color = subFg,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W600,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = "按 OK 停止", color = subFg, fontSize = 11.sp)
            } else {
                LightningBolt(color = fg, modifier = Modifier.size(width = 24.dp, height = 36.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "测速优选", color = fg, fontSize = 15.sp, fontWeight = FontWeight.W700)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = idleSubtitle, color = subFg, fontSize = 11.sp)
            }
        }
    }
}

/**
 * 自绘闪电图标（矢量 Path，随父级颜色反色），替代系统 emoji。
 * 七点折线勾出经典闪电轮廓，在 [0,1] 归一坐标下按容器尺寸缩放。
 */
@Composable
internal fun LightningBolt(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0.60f * w, 0f)
            lineTo(0.14f * w, 0.56f * h)
            lineTo(0.44f * w, 0.56f * h)
            lineTo(0.32f * w, 1f * h)
            lineTo(0.86f * w, 0.40f * h)
            lineTo(0.56f * w, 0.40f * h)
            lineTo(0.72f * w, 0f)
            close()
        }
        drawPath(path = path, color = color)
    }
}
