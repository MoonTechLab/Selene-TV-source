package org.moontechlab.selenetv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

/**
 * 带动画的标签页内容切换器
 * 
 * 动画分为两个阶段：
 * 1. 退出动画（100ms）：当前内容淡出 + 水平位移 50px
 * 2. 进入动画（100ms）：新内容淡入 + 从相反方向滑入
 * 
 * @param selectedTabId 当前选中的标签页 ID
 * @param modifier 修饰符
 * @param content 内容渲染函数，接收当前标签 ID
 */
@Composable
fun AnimatedTabContent(
    selectedTabId: String,
    modifier: Modifier = Modifier,
    content: @Composable (String) -> Unit
) {
    // 当前显示的内容 ID（在动画完成后才更新）
    var displayedTabId by remember { mutableStateOf(selectedTabId) }
    
    // 上一次的标签 ID，用于判断切换方向
    var previousTabId by remember { mutableStateOf(selectedTabId) }
    
    // 动画状态
    val alpha = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    
    val coroutineScope = rememberCoroutineScope()
    
    // 监听标签切换
    LaunchedEffect(selectedTabId) {
        if (selectedTabId != displayedTabId) {
            // 判断切换方向（简化版：根据 ID 顺序判断）
            val tabOrder = listOf("search", "home", "movies", "series", "anime", "variety", "live", "settings")
            val currentIndex = tabOrder.indexOf(displayedTabId)
            val newIndex = tabOrder.indexOf(selectedTabId)
            val isMovingRight = newIndex > currentIndex
            
            coroutineScope.launch {
                // 第一阶段：退出动画（100ms）
                // 透明度 1 → 0，位移 0 → ±50
                val exitOffset = if (isMovingRight) -50f else 50f
                launch {
                    alpha.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 100)
                    )
                }
                launch {
                    offsetX.animateTo(
                        targetValue = exitOffset,
                        animationSpec = tween(durationMillis = 100)
                    )
                }
                
                // 等待退出动画完成
                kotlinx.coroutines.delay(100)
                
                // 切换内容
                previousTabId = displayedTabId
                displayedTabId = selectedTabId
                
                // 设置新内容的初始位置（相反方向）
                val enterOffset = if (isMovingRight) 50f else -50f
                offsetX.snapTo(enterOffset)
                
                // 第二阶段：进入动画（100ms）
                // 透明度 0 → 1，位移 ±50 → 0
                launch {
                    alpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 100)
                    )
                }
                launch {
                    offsetX.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 100)
                    )
                }
            }
        }
    }
    
    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha.value
            translationX = offsetX.value
        }
    ) {
        content(displayedTabId)
    }
}
