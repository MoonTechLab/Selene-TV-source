package org.moontechlab.selenetv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

// ==================== 颜色常量（设置页 / 登录页共享）====================
internal val DialogTextDefault = Color.White
internal val DialogTextFocused = Color(0xFF0A0A0A)
internal val DialogTextGray = Color(0xFF888888)
internal val DialogItemBgDefault = Color(0x14FFFFFF)
internal val DialogItemBgFocused = Color.White
internal val DialogDestructiveFocused = Color(0xFFE74C3C)

/**
 * 带 spring 进出动画 + 黑色遮罩 + 独立 Dialog 窗口的弹框容器。
 * 独立窗口保证 D-pad 上下键不逃出弹框焦点域。
 */
@Composable
fun AnimatedSelectDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onClosed: () -> Unit = {},
    content: @Composable (onClose: () -> Unit) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var animateIn by remember { mutableStateOf(false) }
    var hasBeenVisible by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            hasBeenVisible = true
            showDialog = true
            delay(16)
            animateIn = true
        } else {
            animateIn = false
            delay(150)
            showDialog = false
            // 只有之前显示过才触发 onClosed
            if (hasBeenVisible) {
                hasBeenVisible = false
                onClosed()
            }
        }
    }

    if (showDialog) {
        // 动画参数
        val showSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 400f)
        val hideSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 500f)

        val dialogScale by animateFloatAsState(
            targetValue = if (animateIn) 1f else 0.85f,
            animationSpec = if (animateIn) showSpring else hideSpring,
            label = "dialog_scale",
        )
        val dialogOpacity by animateFloatAsState(
            targetValue = if (animateIn) 1f else 0f,
            animationSpec = if (animateIn) showSpring else hideSpring,
            label = "dialog_opacity",
        )
        val overlayOpacity by animateFloatAsState(
            targetValue = if (animateIn) 1f else 0f,
            animationSpec = if (animateIn) showSpring else hideSpring,
            label = "overlay_opacity",
        )

        // 用独立 Dialog 窗口承载：弹框拥有独立焦点域，D-pad 上下键不会逃逸回背景设置项；
        // 也无需停用背景，避免打开瞬间焦点被甩到顶部 TabSwitcher 的闪烁。返回键由 onDismissRequest 处理。
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = overlayOpacity }
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.graphicsLayer {
                        scaleX = dialogScale
                        scaleY = dialogScale
                        alpha = dialogOpacity
                    },
                ) {
                    content(onDismiss)
                }
            }
        }
    }
}

/**
 * 弹框按钮：default / primary / destructive 三种态。
 */
@Composable
fun DialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    isDestructive: Boolean = false,
) {
    var isFocused by remember { mutableStateOf(false) }

    val textColor = when {
        isFocused -> if (isDestructive) Color.White else DialogTextFocused
        isDestructive -> Color(0xFFE74C3C)
        isPrimary -> Color(0xFF4CAF50)
        else -> DialogTextDefault
    }

    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            // 容器底色也跟随 isFocused（onFocusChanged）：tv Surface 的 focusedContainerColor
            // 仅在「经 D-pad 导航获焦」时渲染，程序化 requestFocus 获焦不触发；故这里用 isFocused
            // 兜底，让程序化种焦的按钮同样显示高亮白底（D-pad 获焦时 focusedContainerColor 仍生效，等价）。
            containerColor = if (isFocused) {
                if (isDestructive) DialogDestructiveFocused else DialogItemBgFocused
            } else when {
                isDestructive -> Color(0x33E74C3C)
                isPrimary -> Color(0x334CAF50)
                else -> DialogItemBgDefault
            },
            focusedContainerColor = if (isDestructive) DialogDestructiveFocused else DialogItemBgFocused,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
            )
        }
    }
}

/**
 * 确认弹框：标题 + 消息 + 取消/确认两按钮。
 * 危险操作默认聚焦取消（focusConfirm=false），消除「确认→取消→确认」的焦点闪现。
 */
@Composable
fun ConfirmDialogContent(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    confirmDestructive: Boolean = false,
    // 默认聚焦确认；危险操作聚焦取消——取消是首个可聚焦项，与弹框窗口的默认落焦一致，
    // 不产生焦点位移，既消除「确认→取消→确认」的闪现，也是危险操作更安全的默认项。
    focusConfirm: Boolean = true,
) {
    val confirmButtonFocusRequester = remember { FocusRequester() }
    val cancelButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        (if (focusConfirm) confirmButtonFocusRequester else cancelButtonFocusRequester).requestFocus()
    }

    Column(
        modifier = Modifier
            .width(400.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1A1A))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = DialogTextDefault,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            fontSize = 14.sp,
            color = DialogTextGray,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 取消按钮
            DialogButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(cancelButtonFocusRequester),
            )

            // 确认按钮
            DialogButton(
                text = confirmText,
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(confirmButtonFocusRequester),
                isPrimary = !confirmDestructive,
                isDestructive = confirmDestructive,
            )
        }
    }
}
