package org.moontechlab.selenetv.screen.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

data class SpeedOption(val value: Float, val label: String)

// 0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 2.0：Netflix(5 档) 与 YouTube(8 档) 的中位数，单行排开不超宽。
val DefaultSpeedOptions: List<SpeedOption> = listOf(
    SpeedOption(0.5f, "0.5×"),
    SpeedOption(0.75f, "0.75×"),
    SpeedOption(1.0f, "1.0×"),
    SpeedOption(1.25f, "1.25×"),
    SpeedOption(1.5f, "1.5×"),
    SpeedOption(2.0f, "2.0×"),
)

fun speedLabel(value: Float): String =
    DefaultSpeedOptions.firstOrNull { it.value == value }?.label ?: "${value}×"

/**
 * 控制条顶部的倍速胶囊。文案 = 当前速率（1.0× / 1.5× …），非 1× 时文案染绿。
 * 焦点模型：左右取消（避免在标题上跳焦），下交回 scrubber，上取消。OK 唤出 [SpeedPopup]。
 */
@Composable
fun SpeedPill(
    currentSpeed: Float,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    scrubberFocus: FocusRequester,
    upTarget: FocusRequester? = null,
    leftTarget: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "speed_pill_scale",
    )
    val shape = RoundedCornerShape(percent = 50)
    Surface(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusProperties {
                down = scrubberFocus
                up = upTarget ?: FocusRequester.Cancel
                left = leftTarget ?: FocusRequester.Cancel
                right = FocusRequester.Cancel
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                shape = shape,
            ),
            focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 11.dp, vertical = 3.dp)) {
            Text(
                text = speedLabel(currentSpeed),
                color = if (focused) Color(0xFF0A0A0A) else Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
            )
        }
    }
}

/**
 * 倍速选择 Popup：独立 Android window，自管焦点 + BACK。位置 = 胶囊上方 8dp 处，右端对齐胶囊右端。
 * 打开时焦点落到当前速率项；按 OK 立即生效并关闭。
 *
 * 动画：用 MutableTransitionState 跟踪进出态——退场期间 Popup 仍保持挂载，让 AnimatedVisibility
 * 跑完 fade+scale 再卸载。缩放原点 TransformOrigin(1f, 1f) 即右下角，视觉上从 Pill 方向"长出来"。
 */
@Composable
fun SpeedPopup(
    open: Boolean,
    currentSpeed: Float,
    options: List<SpeedOption> = DefaultSpeedOptions,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = open

    // Popup 保持挂载条件：当前可见 OR 目标可见——退场动画期间 currentState=true 仍续命
    if (transitionState.currentState || transitionState.targetState) {
        val density = LocalDensity.current
        val gapPx = with(density) { 8.dp.roundToPx() }
        val positionProvider = remember(gapPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x = (anchorBounds.right - popupContentSize.width).coerceAtLeast(0)
                    val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
                    return IntOffset(x, y)
                }
            }
        }
        Popup(
            onDismissRequest = onDismiss,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
            popupPositionProvider = positionProvider,
        ) {
            val focusRequesters = remember(options) { options.map { FocusRequester() } }
            val currentIndex = remember(options, currentSpeed) {
                options.indexOfFirst { it.value == currentSpeed }.coerceAtLeast(0)
            }
            LaunchedEffect(Unit) {
                repeat(8) {
                    try {
                        focusRequesters[currentIndex].requestFocus()
                        return@LaunchedEffect
                    } catch (_: Exception) {
                        kotlinx.coroutines.delay(40)
                    }
                }
            }
            AnimatedVisibility(
                visibleState = transitionState,
                enter = fadeIn(animationSpec = tween(180)) +
                    scaleIn(
                        initialScale = 0.92f,
                        animationSpec = tween(220),
                        transformOrigin = TransformOrigin(1f, 1f),
                    ),
                exit = fadeOut(animationSpec = tween(140)) +
                    scaleOut(
                        targetScale = 0.95f,
                        animationSpec = tween(160),
                        transformOrigin = TransformOrigin(1f, 1f),
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xF20A0A0A))
                        .padding(vertical = 6.dp, horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    options.forEachIndexed { index, opt ->
                        SpeedPopupItem(
                            label = opt.label,
                            selected = opt.value == currentSpeed,
                            onClick = { onSelect(opt.value) },
                            focusRequester = focusRequesters[index],
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedPopupItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .width(120.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            if (focused) Color(0xFF0A0A0A) else Color(0xFF4CAF50)
                        } else Color.Transparent
                    ),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                color = when {
                    focused -> Color(0xFF0A0A0A)
                    selected -> Color.White
                    else -> Color.White.copy(alpha = 0.65f)
                },
                fontSize = 15.sp,
                fontWeight = if (focused || selected) FontWeight.W700 else FontWeight.W500,
            )
        }
    }
}
