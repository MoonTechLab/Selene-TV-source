package org.moontechlab.selenetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

// 破坏性操作的强调色（红）
private val DestructiveColor = Color(0xFFE5484D)

/**
 * 卡片长按菜单项
 * @param label 显示文案
 * @param isDestructive 是否破坏性操作（删除/取消收藏），用红色强调
 * @param onClick 选中后的回调（在菜单关闭动画结束后触发）
 */
data class CardMenuItem(
    val label: String,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * TV 专用卡片长按上下文菜单
 *
 * 特性:
 * - Popup 全屏半透明遮罩 + 中央弹窗
 * - 弹簧动画打开/关闭，沿用 FilterDialog 作法
 * - 竖排菜单项，D-Pad 上下导航，自动聚焦首项
 * - 返回键关闭
 * - 选中项的 onClick 在关闭动画结束后触发，避免与后续弹窗视觉重叠
 */
@Composable
fun CardContextMenu(
    visible: Boolean,
    title: String,
    items: List<CardMenuItem>,
    onClose: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var animateIn by remember { mutableStateOf(false) }
    // 待触发的回调（延迟到关闭动画完成）
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(visible) {
        if (visible) {
            showDialog = true
            delay(16)
            animateIn = true
        } else {
            animateIn = false
            delay(150)
            showDialog = false
            pendingAction?.let { it() }
            pendingAction = null
        }
    }

    if (showDialog) {
        Popup(
            onDismissRequest = onClose,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            OverlayScaffold(
                animateIn = animateIn,
                onClose = onClose,
                dialogWidth = 320.dp
            ) {
                CardContextMenuContent(
                    title = title,
                    items = items,
                    onSelect = { action ->
                        pendingAction = action
                        onClose()
                    }
                )
            }
        }
    }
}

@Composable
private fun CardContextMenuContent(
    title: String,
    items: List<CardMenuItem>,
    onSelect: (() -> Unit) -> Unit
) {
    val firstItemFocusRequester = remember { FocusRequester() }

    // 弹窗渲染后自动聚焦首项
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { firstItemFocusRequester.requestFocus() }
    }

    Column {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )

        items.forEachIndexed { index, item ->
            MenuRow(
                item = item,
                modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier,
                onClick = { onSelect(item.onClick) }
            )
            if (index != items.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun MenuRow(
    item: CardMenuItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "menu_row_scale"
    )

    // 文字颜色：聚焦时反色为深色；未聚焦时破坏性为红、普通为白
    val textColor = when {
        isFocused -> Color(0xFF0A0A0A)
        item.isDestructive -> DestructiveColor
        else -> Color.White
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = if (item.isDestructive) DestructiveColor else Color.White
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = item.label,
                color = if (isFocused && item.isDestructive) Color.White else textColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.W600
            )
        }
    }
}

/**
 * 二次确认弹窗：标题 + 描述 + 取消/确定两个按钮。
 * 默认聚焦「取消」以防误操作。
 */
@Composable
fun ConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String = "确定",
    cancelLabel: String = "取消",
    isDestructive: Boolean = true,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var animateIn by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(visible) {
        if (visible) {
            showDialog = true
            delay(16)
            animateIn = true
        } else {
            animateIn = false
            delay(150)
            showDialog = false
            pendingAction?.let { it() }
            pendingAction = null
        }
    }

    if (showDialog) {
        Popup(
            onDismissRequest = onCancel,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            OverlayScaffold(
                animateIn = animateIn,
                onClose = onCancel,
                dialogWidth = 380.dp
            ) {
                ConfirmDialogContent(
                    title = title,
                    message = message,
                    confirmLabel = confirmLabel,
                    cancelLabel = cancelLabel,
                    isDestructive = isDestructive,
                    onConfirm = {
                        pendingAction = onConfirm
                        onCancel() // 复用关闭流程：先收起动画，再触发 pending
                    },
                    onCancel = onCancel
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialogContent(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    isDestructive: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { cancelFocusRequester.requestFocus() }
    }

    Column {
        Text(
            text = title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.W600,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ConfirmButton(
                label = cancelLabel,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(cancelFocusRequester),
                isDestructive = false,
                onClick = onCancel
            )
            ConfirmButton(
                label = confirmLabel,
                modifier = Modifier.weight(1f),
                isDestructive = isDestructive,
                onClick = onConfirm
            )
        }
    }
}

@Composable
private fun ConfirmButton(
    label: String,
    modifier: Modifier = Modifier,
    isDestructive: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "confirm_btn_scale"
    )

    val textColor = when {
        isFocused -> Color(0xFF0A0A0A)
        isDestructive -> DestructiveColor
        else -> Color.White
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = if (isDestructive) DestructiveColor else Color.White
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (isFocused && isDestructive) Color.White else textColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.W600,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 弹窗通用脚手架：全屏遮罩 + 中央弹簧动画卡片。
 * 抽出 CardContextMenu 与 ConfirmDialog 共用的遮罩/动画/返回键逻辑。
 */
@Composable
private fun OverlayScaffold(
    animateIn: Boolean,
    onClose: () -> Unit,
    dialogWidth: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit
) {
    BackHandler(enabled = true) { onClose() }

    // 长按卡片（OK 键 repeatCount==1）触发 onLongClick 打开本弹窗时，OK 键往往仍按住；
    // 待手指松开时那次 ACTION_UP 会落到弹窗内刚获得焦点的菜单项/按钮上。tv Surface 的
    // tvClickable 在 ACTION_UP 且自身 isLongClick==false 时即判定为点击，且不校验是否收到过
    // 对应的 ACTION_DOWN，于是这次「孤儿 key-up」被当成点击，导致一次长按连锁触发下一级弹窗/确定。
    // 这里在弹窗根部用 preview 阶段拦截：只有在弹窗内见过一次全新按下（repeatCount==0）后，
    // 才放行随后的 ACTION_UP；否则吞掉孤儿 up。
    val okKeyGate = remember { OkKeyClickGate() }

    val showSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 400f)
    val hideSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 500f)

    val dialogScale by animateFloatAsState(
        targetValue = if (animateIn) 1f else 0.8f,
        animationSpec = if (animateIn) showSpring else hideSpring,
        label = "overlay_scale"
    )
    val dialogOpacity by animateFloatAsState(
        targetValue = if (animateIn) 1f else 0f,
        animationSpec = if (animateIn) showSpring else hideSpring,
        label = "overlay_opacity"
    )
    val overlayOpacity by animateFloatAsState(
        targetValue = if (animateIn) 1f else 0f,
        animationSpec = if (animateIn) showSpring else hideSpring,
        label = "overlay_mask_opacity"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayOpacity }
            .background(Color.Black.copy(alpha = 0.7f))
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                okKeyGate.shouldConsume(native.action, native.keyCode, native.repeatCount)
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onClose()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(dialogWidth)
                .graphicsLayer {
                    scaleX = dialogScale
                    scaleY = dialogScale
                    alpha = dialogOpacity
                }
                .background(
                    color = Color(0xFF1A1A1A),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(24.dp)
        ) {
            content()
        }
    }
}
