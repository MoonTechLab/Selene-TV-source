package org.moontechlab.selenetv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/**
 * 带动画的标签页内容切换器
 * 
 * 动画分为两个阶段：
 * 1. 退出动画（100ms）：当前内容淡出 + 水平位移 50px
 * 2. 进入动画（100ms）：新内容淡入 + 从相反方向滑入
 * 
 * @param selectedTabId 当前选中的标签页 ID
 * @param scrollState 滚动状态，用于让 header 跟随滚动
 * @param modifier 修饰符
 * @param header 不参与动画的头部内容（如 TabSwitcher），会跟随滚动
 * @param content 内容渲染函数，接收当前标签 ID
 */
@Composable
fun AnimatedTabContent(
    selectedTabId: String,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable (String) -> Unit
) {
    // 当前显示的内容 ID（在动画完成后才更新）
    var displayedTabId by remember { mutableStateOf(selectedTabId) }
    
    // 动画状态 - 只应用于内容区域
    val alpha = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    
    val coroutineScope = rememberCoroutineScope()
    
    // 监听标签切换
    LaunchedEffect(selectedTabId) {
        if (selectedTabId != displayedTabId) {
            // 判断切换方向
            val tabOrder = listOf("search", "home", "movies", "series", "anime", "show", "live", "settings")
            val currentIndex = tabOrder.indexOf(displayedTabId)
            val newIndex = tabOrder.indexOf(selectedTabId)
            val isMovingRight = newIndex > currentIndex
            
            coroutineScope.launch {
                // 第一阶段：退出动画（100ms）
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
                
                kotlinx.coroutines.delay(100)
                
                // 切换内容
                displayedTabId = selectedTabId
                
                // 设置新内容的初始位置
                val enterOffset = if (isMovingRight) 50f else -50f
                offsetX.snapTo(enterOffset)
                
                // 第二阶段：进入动画（100ms）
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
    
    Box(modifier = modifier) {
        // Header 在动画层外部，不参与切换动画
        // 使用 offset 跟随滚动状态
        Box(
            modifier = Modifier
                .zIndex(1f)
                .offset { IntOffset(0, -scrollState.value) }
        ) {
            header()
        }
        
        // 内容区域参与动画
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = alpha.value
                    translationX = offsetX.value
                    // 关键：alpha<1 默认会把整页内容先渲染到离屏缓冲再整体调透（满屏 saveLayer，弱 GPU 每帧重负，
                    // 切 tab 掉帧主因之一）。整页主要是不透明、互不重叠的卡片 → 用 ModulateAlpha 逐绘制调透，
                    // 免掉离屏缓冲，淡入淡出观感不变。
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
        ) {
            content(displayedTabId)
        }
    }
}
