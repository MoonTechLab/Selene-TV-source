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
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.moontechlab.selenetv.service.DanmakuService.SourceMatch

/**
 * 控制条上的「弹幕源」胶囊。文案 = 当前弹幕源显示名（如 哔哩哔哩）。
 * 仅当有命中源时由调用方挂载。焦点模型：左取消、右交给画面比例(rightTarget)、下交回 scrubber、上交给 skip 或取消。
 * OK 唤出 [DanmakuSourcePopup]。镜像 [AspectRatioPill]。
 */
@Composable
fun DanmakuSourcePill(
    currentLabel: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    scrubberFocus: FocusRequester,
    rightTarget: FocusRequester,
    upTarget: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "danmaku_pill_scale",
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
                left = FocusRequester.Cancel
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
                text = currentLabel,
                color = if (focused) Color(0xFF0A0A0A) else Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
            )
        }
    }
}

/**
 * 弹幕源选择 Popup：列出 search 命中的候选源；当前源高亮（绿点）。位置 = 胶囊上方 8dp，左端对齐胶囊左端。
 * 每项可显示该源弹幕条数：已拉取显示数字（0 → 无），未拉取留空。按 OK 选中并关闭。镜像 [AspectRatioPopup]。
 *
 * @param counts key → 已拉取条数；null = 尚未拉取（切到该源时才会拉）。
 */
@Composable
fun DanmakuSourcePopup(
    open: Boolean,
    matches: List<SourceMatch>,
    currentKey: String?,
    counts: Map<String, Int?>,
    onSelect: (String) -> Unit,
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
                    val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
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
            val focusRequesters = remember(matches) { matches.map { FocusRequester() } }
            val currentIndex = remember(matches, currentKey) {
                matches.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
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
                        transformOrigin = TransformOrigin(0f, 1f),
                    ),
                exit = fadeOut(animationSpec = tween(140)) +
                    scaleOut(
                        targetScale = 0.95f,
                        animationSpec = tween(160),
                        transformOrigin = TransformOrigin(0f, 1f),
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xF20A0A0A))
                        .padding(vertical = 6.dp, horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    matches.forEachIndexed { index, m ->
                        DanmakuSourcePopupItem(
                            label = m.display,
                            count = counts[m.key],
                            selected = m.key == currentKey,
                            onClick = { onSelect(m.key) },
                            focusRequester = focusRequesters[index],
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DanmakuSourcePopupItem(
    label: String,
    count: Int?,
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
            .width(168.dp),
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
                modifier = Modifier.weight(1f),
            )
            // 条数：已拉取显示（0 → 无），未拉取留空
            val countText = when {
                count == null -> ""
                count <= 0 -> "无"
                else -> count.toString()
            }
            if (countText.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = countText,
                    color = when {
                        focused -> Color(0xFF0A0A0A).copy(alpha = 0.6f)
                        else -> Color.White.copy(alpha = 0.4f)
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W600,
                )
            }
        }
    }
}
