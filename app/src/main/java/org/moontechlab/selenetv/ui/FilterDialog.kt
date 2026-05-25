package org.moontechlab.selenetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 筛选选项数据类
 * @param value 选项的唯一标识值 (key)
 * @param label 选项的显示文本
 */
data class SelectorOption(
    val value: String,
    val label: String
)

/**
 * TV 专用筛选对话框组件
 *
 * 特性:
 * - 全屏半透明遮罩
 * - 弹簧动画打开/关闭效果
 * - FlowRow 网格布局自动换行
 * - D-Pad 导航支持
 * - 自动聚焦到已选中项
 * - 聚焦项放大动画
 * - 滚动居中
 */
@Composable
fun FilterDialog(
    visible: Boolean,
    title: String,
    options: List<SelectorOption>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    onClose: () -> Unit
) {
    // 控制动画状态
    var showDialog by remember { mutableStateOf(false) }
    var animateIn by remember { mutableStateOf(false) }
    
    // 待选择的值（用于延迟触发 onSelect）
    var pendingSelectValue by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // 处理显示/隐藏逻辑
    LaunchedEffect(visible) {
        if (visible) {
            showDialog = true
            delay(16) // 等待一帧让 Dialog 渲染
            animateIn = true
        } else {
            animateIn = false
            delay(150) // 等待关闭动画完成
            showDialog = false
            // 动画完成后触发选择回调
            pendingSelectValue?.let { value ->
                onSelect(value)
                pendingSelectValue = null
            }
        }
    }

    if (showDialog) {
        // 使用 Popup 替代 Dialog 以完全控制动画
        androidx.compose.ui.window.Popup(
            onDismissRequest = onClose,
            properties = androidx.compose.ui.window.PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            FilterDialogContent(
                title = title,
                options = options,
                selectedValue = selectedValue,
                animateIn = animateIn,
                onSelect = { value ->
                    // 保存待选择的值，先关闭对话框
                    pendingSelectValue = value
                    onClose()
                },
                onClose = onClose
            )
        }
    }
}

@Composable
private fun FilterDialogContent(
    title: String,
    options: List<SelectorOption>,
    selectedValue: String,
    animateIn: Boolean,
    onSelect: (String) -> Unit,
    onClose: () -> Unit
) {
    // 返回键处理
    BackHandler(enabled = true) {
        onClose()
    }

    // 动画参数 - 展示: 更快更少回弹; 隐藏: 快速收起
    // dampingRatio 越高回弹越少 (1.0 = 无回弹), stiffness 越高动画越快
    val showSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 400f)
    val hideSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 500f)
    
    // 对话框缩放动画: 0.8 <-> 1.0
    val dialogScale by animateFloatAsState(
        targetValue = if (animateIn) 1f else 0.8f,
        animationSpec = if (animateIn) showSpring else hideSpring,
        label = "dialog_scale"
    )

    // 对话框透明度动画: 0 <-> 1
    val dialogOpacity by animateFloatAsState(
        targetValue = if (animateIn) 1f else 0f,
        animationSpec = if (animateIn) showSpring else hideSpring,
        label = "dialog_opacity"
    )
    
    // 遮罩层透明度动画: 0 <-> 1
    val overlayOpacity by animateFloatAsState(
        targetValue = if (animateIn) 1f else 0f,
        animationSpec = if (animateIn) showSpring else hideSpring,
        label = "overlay_opacity"
    )

    // 屏幕尺寸
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp

    // 对话框尺寸
    val dialogWidth = screenWidth * 0.7f
    val minHeight = screenHeight * 0.4f
    val maxHeight = screenHeight * 0.8f

    // 滚动状态
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // 为每个选项创建 FocusRequester
    val focusRequesters = remember(options) {
        options.associate { it.value to FocusRequester() }
    }

    // 记录每个选项的位置
    val itemPositions = remember { mutableStateMapOf<String, Float>() }
    val itemHeights = remember { mutableStateMapOf<String, Float>() }

    // 容器高度
    var containerHeight by remember { mutableFloatStateOf(0f) }

    // 自动聚焦到已选中项或第一项
    LaunchedEffect(animateIn) {
        if (animateIn) {
            delay(100) // 等待布局完成
            val targetValue = if (options.any { it.value == selectedValue }) selectedValue else options.firstOrNull()?.value
            targetValue?.let { focusRequesters[it]?.requestFocus() }
        }
    }

    // 全屏遮罩
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = overlayOpacity
            }
            .background(Color.Black.copy(alpha = 0.7f))
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
        // 对话框容器
        Box(
            modifier = Modifier
                .width(dialogWidth)
                .heightIn(min = minHeight, max = maxHeight)
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
            Column {
                // 标题
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.W600,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 选项网格 (可滚动)
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .onGloballyPositioned { coordinates ->
                            containerHeight = coordinates.size.height.toFloat()
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(scrollState)
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            options.forEach { option ->
                                FilterDialogOptionItem(
                                    option = option,
                                    isSelected = option.value == selectedValue,
                                    focusRequester = focusRequesters[option.value]!!,
                                    onSelect = { onSelect(option.value) },
                                    onPositioned = { y, height ->
                                        itemPositions[option.value] = y
                                        itemHeights[option.value] = height
                                    },
                                    onFocused = { value ->
                                        // 滚动使聚焦项居中
                                        val itemY = itemPositions[value] ?: return@FilterDialogOptionItem
                                        val itemHeight = itemHeights[value] ?: return@FilterDialogOptionItem
                                        val targetScroll = (itemY + itemHeight / 2 - containerHeight / 2).toInt()
                                            .coerceIn(0, scrollState.maxValue)
                                        coroutineScope.launch {
                                            scrollState.animateScrollTo(targetScroll)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个筛选选项
 */
@Composable
private fun FilterDialogOptionItem(
    option: SelectorOption,
    isSelected: Boolean,
    focusRequester: FocusRequester,
    onSelect: () -> Unit,
    onPositioned: (y: Float, height: Float) -> Unit,
    onFocused: (String) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    // 聚焦时的缩放动画
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 400f
        ),
        label = "option_scale"
    )

    // 背景颜色
    val backgroundColor = when {
        isFocused -> Color.White                           // 聚焦: 白色
        isSelected -> Color.White.copy(alpha = 0.2f)       // 选中未聚焦: rgba(255,255,255,0.2)
        else -> Color.White.copy(alpha = 0.08f)            // 默认: rgba(255,255,255,0.08)
    }

    // 文字颜色
    val textColor = if (isFocused) Color(0xFF0A0A0A) else Color.White

    Surface(
        onClick = onSelect,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (focusState.isFocused) {
                    onFocused(option.value)
                }
            }
            .onGloballyPositioned { coordinates ->
                onPositioned(
                    coordinates.positionInParent().y,
                    coordinates.size.height.toFloat()
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f), // 禁用默认缩放
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(8.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = Color.White
        )
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = option.label,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.W600
            )
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun FilterDialogPreview() {
    val options = listOf(
        SelectorOption("all", "全部"),
        SelectorOption("2024", "2024"),
        SelectorOption("2023", "2023"),
        SelectorOption("2022", "2022"),
        SelectorOption("2021", "2021"),
        SelectorOption("2020", "2020"),
        SelectorOption("2019", "2019"),
        SelectorOption("2018", "2018"),
        SelectorOption("2017", "2017"),
        SelectorOption("2016", "2016"),
        SelectorOption("2015", "2015"),
        SelectorOption("earlier", "更早")
    )

    var visible by remember { mutableStateOf(true) }
    var selectedValue by remember { mutableStateOf("2023") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        FilterDialog(
            visible = visible,
            title = "选择年份",
            options = options,
            selectedValue = selectedValue,
            onSelect = { selectedValue = it },
            onClose = { visible = false }
        )
    }
}
