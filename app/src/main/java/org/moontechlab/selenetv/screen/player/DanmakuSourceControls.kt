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

/** 弹幕显示区域选项。key 与 AppDataService 持久化值一致（off/third/half/full）。 */
data class DanmakuAreaOption(val key: String, val label: String)

val DanmakuAreaOptions: List<DanmakuAreaOption> = listOf(
    DanmakuAreaOption("off", "关闭"),
    DanmakuAreaOption("third", "1/3 屏"),
    DanmakuAreaOption("half", "半屏"),
    DanmakuAreaOption("full", "全屏"),
)

fun danmakuAreaLabel(key: String): String = DanmakuAreaOptions.firstOrNull { it.key == key }?.label ?: "关闭"

/** 区域 → 弹幕层占屏高度比例（off=0 不显示）。 */
fun danmakuAreaFraction(key: String): Float = when (key) {
    "third" -> 0.34f
    "half" -> 0.5f
    "full" -> 1f
    else -> 0f
}

/**
 * 控制条上的「弹幕」胶囊（单一入口）。OK 唤出 [DanmakuMenuPopup]——区域 + 弹幕源都在一个菜单里。
 * 文案：关闭态「弹幕 · 关闭」，开启态「弹幕 · {当前源}」。焦点：左 leftTarget、右 rightTarget、下 scrubber。
 */
@Composable
fun DanmakuPill(
    label: String,
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
            border = Border(border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)), shape = shape),
            focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 11.dp, vertical = 3.dp)) {
            Text(
                text = label,
                color = if (focused) Color(0xFF0A0A0A) else Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
            )
        }
    }
}

/**
 * 弹幕统一菜单：上半「显示区域」（关闭/1/3/半屏/全屏），下半「弹幕源」（命中源 + 条数，开启时才显示）。
 * 一个独立 Popup window，自管焦点 + BACK。位置 = 胶囊上方 8dp，左端对齐。选中即生效并关闭。
 *
 * @param counts key → 已拉取条数；null = 尚未拉取（切到该源时才会拉）。
 */
@Composable
fun DanmakuMenuPopup(
    open: Boolean,
    areaKey: String,
    matches: List<SourceMatch>,
    selectedSourceKey: String?,
    counts: Map<String, Int?>,
    onSelectArea: (String) -> Unit,
    onSelectSource: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = open
    if (!transitionState.currentState && !transitionState.targetState) return

    val showSources = areaKey != "off"
    // 焦点目标：4 个区域项 + （开启时）N 个源项。打开时落到当前区域项。
    val focusCount = DanmakuAreaOptions.size + if (showSources) matches.size else 0
    val focusRequesters = remember(focusCount) { List(focusCount) { FocusRequester() } }
    val currentIndex = remember(areaKey) {
        DanmakuAreaOptions.indexOfFirst { it.key == areaKey }.coerceAtLeast(0)
    }

    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.roundToPx() }
    val positionProvider = remember(gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset {
                val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
        popupPositionProvider = positionProvider,
    ) {
        LaunchedEffect(focusCount) {
            repeat(8) {
                try { focusRequesters[currentIndex].requestFocus(); return@LaunchedEffect }
                catch (_: Exception) { kotlinx.coroutines.delay(40) }
            }
        }
        AnimatedVisibility(
            visibleState = transitionState,
            enter = fadeIn(animationSpec = tween(180)) +
                scaleIn(initialScale = 0.92f, animationSpec = tween(220), transformOrigin = TransformOrigin(0f, 1f)),
            exit = fadeOut(animationSpec = tween(140)) +
                scaleOut(targetScale = 0.95f, animationSpec = tween(160), transformOrigin = TransformOrigin(0f, 1f)),
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xF20A0A0A))
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                DanmakuSectionLabel("显示区域")
                DanmakuAreaOptions.forEachIndexed { i, opt ->
                    DanmakuMenuItem(
                        label = opt.label,
                        trailing = null,
                        selected = opt.key == areaKey,
                        onClick = { onSelectArea(opt.key) },
                        focusRequester = focusRequesters[i],
                    )
                }
                if (showSources) {
                    Spacer(modifier = Modifier.size(6.dp))
                    DanmakuSectionLabel("弹幕源")
                    if (matches.isEmpty()) {
                        Text(
                            text = "无可用源",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    } else {
                        matches.forEachIndexed { j, m ->
                            val c = counts[m.key]
                            DanmakuMenuItem(
                                label = m.display,
                                trailing = when {
                                    c == null -> null
                                    c <= 0 -> "无"
                                    else -> c.toString()
                                },
                                selected = m.key == selectedSourceKey,
                                onClick = { onSelectSource(m.key) },
                                focusRequester = focusRequesters[DanmakuAreaOptions.size + j],
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DanmakuSectionLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 11.sp,
        fontWeight = FontWeight.W600,
        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun DanmakuMenuItem(
    label: String,
    trailing: String?,
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
            .width(184.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(6.dp).clip(CircleShape).background(
                    if (selected) { if (focused) Color(0xFF0A0A0A) else Color(0xFF4CAF50) } else Color.Transparent
                ),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                color = when { focused -> Color(0xFF0A0A0A); selected -> Color.White; else -> Color.White.copy(alpha = 0.65f) },
                fontSize = 15.sp,
                fontWeight = if (focused || selected) FontWeight.W700 else FontWeight.W500,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = trailing,
                    color = if (focused) Color(0xFF0A0A0A).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W600,
                )
            }
        }
    }
}
