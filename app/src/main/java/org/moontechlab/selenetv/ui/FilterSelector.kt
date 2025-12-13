package org.moontechlab.selenetv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * 筛选选项数据类
 */
data class FilterOption(
    val key: String,
    val label: String
)

/**
 * TV 专用筛选选择器组件
 * 
 * 特性:
 * - 水平排列的可选选项列表
 * - 三种视觉状态：默认、选中(未聚焦)、聚焦
 * - 聚焦时有 1.05x 缩放弹簧动画
 * - 支持外部控制焦点请求
 */
@Composable
fun FilterSelector(
    options: List<FilterOption>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    // 为每个选项创建 FocusRequester
    val focusRequesters = remember(options) {
        options.associate { it.key to FocusRequester() }
    }
    
    // 追踪组件是否有焦点
    var hadFocus by remember { mutableStateOf(false) }
    
    Box(
        modifier = modifier
            .background(
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(19.dp)
            )
            .padding(2.2.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                // 当组件从无焦点变为有焦点时，请求焦点到当前选中项
                if (!hadFocus && focusState.hasFocus) {
                    focusRequesters[selectedKey]?.requestFocus()
                }
                hadFocus = focusState.hasFocus
            }
            .focusGroup()
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            // 为缩放动画留出空间，避免边缘裁切
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
        ) {
            items(
                items = options,
                key = { it.key }
            ) { option ->
                FilterOptionItem(
                    option = option,
                    isSelected = option.key == selectedKey,
                    focusRequester = focusRequesters[option.key]!!,
                    onSelect = { onSelect(option.key) }
                )
            }
        }
    }
}

/**
 * 单个筛选选项
 */
@Composable
private fun FilterOptionItem(
    option: FilterOption,
    isSelected: Boolean,
    focusRequester: FocusRequester,
    onSelect: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    // 聚焦时的缩放动画 (弹簧效果: friction=8, tension=100)
    // Spring 参数转换: dampingRatio ≈ friction/10, stiffness ≈ tension
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,  // 较高阻尼，减少回弹
            stiffness = 400f     // 较高刚度，更快响应
        ),
        label = "filter_option_scale"
    )
    
    // 背景颜色
    val backgroundColor = when {
        isFocused -> Color.White                      // 聚焦: 白色
        isSelected -> Color.White.copy(alpha = 0.2f) // 选中未聚焦: 半透明白
        else -> Color.Transparent                     // 默认: 透明
    }
    
    // 文字颜色
    val textColor = if (isFocused) Color(0xFF0A0A0A) else Color.White

    Surface(
        onClick = onSelect,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f), // 禁用默认缩放
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(16.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = Color.White
        )
    ) {
        Box(
            modifier = Modifier
                .height(30.dp)
                .padding(horizontal = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = option.label,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.W600
            )
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun FilterSelectorPreview() {
    val options = listOf(
        FilterOption("all", "全部"),
        FilterOption("movie", "电影"),
        FilterOption("tv", "电视剧"),
        FilterOption("anime", "动漫"),
        FilterOption("show", "综艺"),
        FilterOption("documentary", "纪录片")
    )
    
    var selectedKey by remember { mutableStateOf("all") }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(48.dp)
    ) {
        FilterSelector(
            options = options,
            selectedKey = selectedKey,
            onSelect = { selectedKey = it }
        )
    }
}
