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

data class AspectOption(val mode: VideoResizeMode, val label: String)

// FIT(默认/留黑边) / ZOOM(铺满裁剪) / FILL(拉伸变形)——3 档缩放，详见画面比例按钮设计 spec。
val DefaultAspectOptions: List<AspectOption> = listOf(
    AspectOption(VideoResizeMode.FIT, "默认"),
    AspectOption(VideoResizeMode.ZOOM, "铺满"),
    AspectOption(VideoResizeMode.FILL, "拉伸"),
)

fun aspectLabel(mode: VideoResizeMode): String =
    DefaultAspectOptions.firstOrNull { it.mode == mode }?.label ?: "默认"

/**
 * 胶囊外显文案：默认(FIT) 态显示功能名「画面比例」，让按钮用途一目了然；
 * 非默认态显示当前模式名（铺满/拉伸）。弹窗内仍用 [aspectLabel]（默认/铺满/拉伸）。
 */
fun aspectPillLabel(mode: VideoResizeMode): String =
    if (mode == VideoResizeMode.FIT) "画面比例" else aspectLabel(mode)

/**
 * 控制条顶部的画面比例胶囊。文案 = 当前模式名（默认 / 铺满 / 拉伸）。
 * 焦点模型：左取消、右交给倍速胶囊(rightTarget)、下交回 scrubber、上交给 skip 或取消。OK 唤出 [AspectRatioPopup]。
 * 镜像 [SpeedPill]；胶囊文案配色无染绿（弹窗选中项保留绿点，与倍速一致）。
 */
@Composable
fun AspectRatioPill(
    currentMode: VideoResizeMode,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    scrubberFocus: FocusRequester,
    rightTarget: FocusRequester,
    leftTarget: FocusRequester? = null,
    upTarget: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "aspect_pill_scale",
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
                right = rightTarget
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
                text = aspectPillLabel(currentMode),
                color = if (focused) Color(0xFF0A0A0A) else Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
            )
        }
    }
}

/**
 * 画面比例选择 Popup：独立 Android window，自管焦点 + BACK。位置 = 胶囊上方 8dp，右端对齐胶囊右端。
 * 打开时焦点落到当前模式项；按 OK 立即生效并关闭。镜像 [SpeedPopup]。
 */
@Composable
fun AspectRatioPopup(
    open: Boolean,
    currentMode: VideoResizeMode,
    options: List<AspectOption> = DefaultAspectOptions,
    onSelect: (VideoResizeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = open

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
            val currentIndex = remember(options, currentMode) {
                options.indexOfFirst { it.mode == currentMode }.coerceAtLeast(0)
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
                        AspectRatioPopupItem(
                            label = opt.label,
                            selected = opt.mode == currentMode,
                            onClick = { onSelect(opt.mode) },
                            focusRequester = focusRequesters[index],
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AspectRatioPopupItem(
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
