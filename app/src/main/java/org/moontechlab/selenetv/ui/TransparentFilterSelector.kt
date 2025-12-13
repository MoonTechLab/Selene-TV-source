package org.moontechlab.selenetv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * 透明筛选选项数据类
 */
data class TransparentFilterOption(
    val key: String,
    val label: String,
    val selectedValue: String? = null  // 显示选中的值，为 null 或 "全部" 时显示 label
)

/**
 * 透明筛选选择器的状态持有者
 * 提供编程式聚焦能力
 */
class TransparentFilterSelectorState {
    internal val firstItemFocusRequester = FocusRequester()
    
    /**
     * 聚焦到第一个选项
     */
    fun focus() {
        try {
            firstItemFocusRequester.requestFocus()
        } catch (_: Exception) {
            // 忽略焦点请求失败
        }
    }
}

@Composable
fun rememberTransparentFilterSelectorState(): TransparentFilterSelectorState {
    return remember { TransparentFilterSelectorState() }
}

/**
 * TV 专用透明筛选选择器组件
 * 
 * 特性:
 * - 透明背景容器，水平排列选项
 * - 每个选项右侧带下拉箭头图标
 * - 两种视觉状态：默认(透明)、聚焦(白色背景)
 * - 聚焦时有 1.05x 缩放弹簧动画
 * - 支持显示已选中的值
 */
@Composable
fun TransparentFilterSelector(
    options: List<TransparentFilterOption>,
    focusedKey: String?,
    onFocus: (String) -> Unit,
    onBlur: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    state: TransparentFilterSelectorState = rememberTransparentFilterSelectorState()
) {
    // 为每个选项创建 FocusRequester
    val focusRequesters = remember(options) {
        options.mapIndexed { index, option ->
            option.key to if (index == 0) state.firstItemFocusRequester else FocusRequester()
        }.toMap()
    }

    Box(
        modifier = modifier
            .background(
                color = Color.Transparent,
                shape = RoundedCornerShape(19.dp)
            )
            .padding(2.2.dp)
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
        ) {
            items(
                items = options,
                key = { it.key }
            ) { option ->
                TransparentFilterOptionItem(
                    option = option,
                    isFocusedByParent = option.key == focusedKey,
                    focusRequester = focusRequesters[option.key]!!,
                    onFocus = { onFocus(option.key) },
                    onBlur = onBlur,
                    onSelect = { onSelect(option.key) }
                )
            }
        }
    }
}

/**
 * 单个透明筛选选项
 */
@Composable
private fun TransparentFilterOptionItem(
    option: TransparentFilterOption,
    isFocusedByParent: Boolean,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onBlur: () -> Unit,
    onSelect: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    // 聚焦时的缩放动画 (弹簧效果: friction=8, tension=100)
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,  // 较高阻尼，减少回弹
            stiffness = 400f     // 较高刚度，更快响应
        ),
        label = "transparent_filter_option_scale"
    )
    
    // 背景颜色
    val backgroundColor = if (isFocused) Color.White else Color.Transparent
    
    // 文字和图标颜色
    val contentColor = if (isFocused) Color(0xFF0A0A0A) else Color.White
    
    // 显示文本逻辑
    val displayText = if (option.selectedValue != null && option.selectedValue != "全部") {
        option.selectedValue
    } else {
        option.label
    }

    Surface(
        onClick = onSelect,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                val wasFocused = isFocused
                isFocused = focusState.isFocused
                if (focusState.isFocused && !wasFocused) {
                    onFocus()
                } else if (!focusState.isFocused && wasFocused) {
                    onBlur()
                }
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
        Row(
            modifier = Modifier
                .height(30.dp)
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayText,
                color = contentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.W600
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "展开",
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun TransparentFilterSelectorPreview() {
    val options = listOf(
        TransparentFilterOption("year", "年份", selectedValue = "2024"),
        TransparentFilterOption("genre", "类型", selectedValue = null),
        TransparentFilterOption("region", "地区", selectedValue = "全部"),
        TransparentFilterOption("sort", "排序", selectedValue = "热度")
    )
    
    var focusedKey by remember { mutableStateOf<String?>(null) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(48.dp)
    ) {
        TransparentFilterSelector(
            options = options,
            focusedKey = focusedKey,
            onFocus = { focusedKey = it },
            onBlur = { focusedKey = null },
            onSelect = { /* 打开下拉弹窗 */ }
        )
    }
}
