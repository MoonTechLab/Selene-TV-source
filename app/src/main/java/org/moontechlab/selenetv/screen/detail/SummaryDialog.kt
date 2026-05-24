package org.moontechlab.selenetv.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

private val CardColor = Color(0xFF1A1A1A)

/**
 * 简介全文阅读弹窗：玻璃面板，正文超出一页时方向键上/下翻页滚动，上下边缘渐隐提示更多内容，
 * 返回键关闭。用 Dialog 建立独立焦点域，避免详情页 focusGroup(exit=Cancel) 阻挡焦点进入；
 * 正文无内部可聚焦项，故 onPreviewKeyEvent 直接驱动滚动。
 */
@Composable
fun SummaryDialog(
    visible: Boolean,
    title: String,
    summary: String,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val bodyFocus = remember { FocusRequester() }
    var viewportPx by remember { mutableStateOf(0) }
    // 弹窗最高约占屏幕 75%，正文短时收缩贴合内容
    val maxCardHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(80)
            try { bodyFocus.requestFocus() } catch (_: Exception) {}
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .heightIn(max = maxCardHeight)
                    .background(CardColor.copy(alpha = 0.97f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 28.dp, vertical = 24.dp),
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.W700,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "简介",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    letterSpacing = 4.sp,
                )
                Spacer(modifier = Modifier.height(18.dp))

                // 正文滚动区：可聚焦 Surface 承接方向键，onPreviewKeyEvent 翻页滚动；点击/确认无操作
                Surface(
                    onClick = {},
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .focusRequester(bodyFocus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val step = (viewportPx.takeIf { it > 0 }?.toFloat() ?: 600f) * 0.85f
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (scroll.canScrollForward) scope.launch { scroll.animateScrollBy(step) }
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (scroll.canScrollBackward) scope.launch { scroll.animateScrollBy(-step) }
                                    true
                                }
                                else -> false
                            }
                        },
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    shape = ClickableSurfaceDefaults.shape(shape = RectangleShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                    ),
                ) {
                    Box {
                        Column(
                            modifier = Modifier
                                .onSizeChanged { viewportPx = it.height }
                                .verticalScroll(scroll),
                        ) {
                            Text(
                                text = summary,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 15.sp,
                                lineHeight = 26.sp,
                            )
                        }
                        // 顶部渐隐
                        if (scroll.canScrollBackward) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .background(Brush.verticalGradient(listOf(CardColor, Color.Transparent))),
                            )
                        }
                        // 底部渐隐
                        if (scroll.canScrollForward) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, CardColor))),
                            )
                        }
                    }
                }

            }
        }
    }
}
