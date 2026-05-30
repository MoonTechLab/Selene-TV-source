package org.moontechlab.selenetv.screen.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 单色矩形跳过按钮。未聚焦黑底白字+1dp白边，聚焦反色（白底黑字）。
 * 控制条隐藏时方向键交由 [onWakeControls]（唤控制条+还焦）；控制条可见时方向走 focusProperties。
 * OK 触发 [onSkip]。
 */
@Composable
fun IntroSkipButton(
    label: String,
    focusRequester: FocusRequester,
    speedPillFocus: FocusRequester,
    controlsVisible: Boolean,
    onSkip: () -> Unit,
    onWakeControls: () -> Unit,
    onFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 焦点视觉一律从 interactionSource 派生（collectIsFocusedAsState），而非裸 onFocusChanged——
    // 后者挂在本组件的 modifier 链上时不可靠（程序化 requestFocus 后 isFocused 不翻转，按钮恒显未聚焦态）。
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocusChanged(focused) }
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, label = "skip_scale")
    val bg = if (focused) Color.White else Color.Black
    val fg = if (focused) Color.Black else Color.White
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(focusRequester)
            .focusProperties {
                down = speedPillFocus
                up = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onSkip(); true }
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight ->
                        if (!controlsVisible) { onWakeControls(); true } else false
                    else -> false
                }
            }
            .focusable(interactionSource = interactionSource)
            .border(1.dp, if (focused) Color.Transparent else Color.White, RoundedCornerShape(6.dp))
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 15.sp,
            fontWeight = FontWeight.W600,
        )
    }
}
