package org.moontechlab.selenetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.moontechlab.selenetv.model.VideoInfo

/**
 * 推荐区块组件
 * 
 * @param title 区块标题
 * @param videos 视频列表数据
 * @param from 视频来源类型
 * @param isLoading 是否处于加载状态
 * @param onVideoClick 视频卡片点击回调
 * @param modifier 修饰符
 */
@Composable
fun RecommendSection(
    title: String,
    videos: List<VideoInfo>,
    from: VideoCardSource,
    isLoading: Boolean = false,
    onVideoClick: (VideoInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        // 区块标题 - 纯展示，不可获得焦点
        Text(
            text = title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 48.dp, bottom = 8.dp)
        )
        
        // 卡片列表
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(horizontal = 48.dp),
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { keyEvent ->
                    // 处理返回键 - 重置滚动位置
                    if (keyEvent.key == Key.Back && keyEvent.type == KeyEventType.KeyDown) {
                        coroutineScope.launch {
                            listState.scrollToItem(0)
                        }
                        false // 不阻止默认行为
                    } else {
                        false
                    }
                }
        ) {
            if (isLoading || videos.isEmpty()) {
                // 加载状态或无数据 - 显示 8 个骨架屏占位卡片
                items(8) { _ ->
                    SkeletonVideoCard(
                        modifier = Modifier.onPreviewKeyEvent { keyEvent ->
                            handleVerticalNavigation(keyEvent, listState, coroutineScope)
                        }
                    )
                }
            } else {
                // 实际视频卡片
                items(videos, key = { it.id }) { video ->
                    VideoCard(
                        videoInfo = video,
                        from = from,
                        onClick = { onVideoClick(video) },
                        modifier = Modifier.onPreviewKeyEvent { keyEvent ->
                            handleVerticalNavigation(keyEvent, listState, coroutineScope)
                        }
                    )
                }
            }
        }
        
        // 区块底部间距
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * 处理垂直导航 - 重置水平滚动位置
 */
private fun handleVerticalNavigation(
    keyEvent: KeyEvent,
    listState: LazyListState,
    coroutineScope: kotlinx.coroutines.CoroutineScope
): Boolean {
    if (keyEvent.type == KeyEventType.KeyDown) {
        when (keyEvent.key) {
            Key.DirectionUp, Key.DirectionDown -> {
                // 垂直导航时重置水平滚动位置
                coroutineScope.launch {
                    listState.scrollToItem(0)
                }
            }
        }
    }
    return false // 不阻止默认导航行为
}

/**
 * 骨架屏占位卡片
 */
@Composable
private fun SkeletonVideoCard(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.width(90.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 封面占位
        Box(
            modifier = Modifier
                .width(90.dp)
                .height(135.dp)
                .background(
                    color = Color(0xFF2A2A2A),
                    shape = RoundedCornerShape(6.dp)
                )
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // 标题占位
        Box(
            modifier = Modifier
                .width(70.dp)
                .height(14.dp)
                .background(
                    color = Color(0xFF2A2A2A),
                    shape = RoundedCornerShape(2.dp)
                )
        )
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun RecommendSectionPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(vertical = 48.dp)
    ) {
        Column {
            // 加载状态预览
            RecommendSection(
                title = "正在加载",
                videos = emptyList(),
                from = VideoCardSource.BANGUMI,
                isLoading = true,
                onVideoClick = {}
            )
            
            // 实际内容预览
            RecommendSection(
                title = "热门推荐",
                videos = listOf(
                    createSampleVideoInfo(
                        id = "1",
                        title = "示例视频 1",
                        rate = "9.2",
                        bangumiId = 1001
                    ),
                    createSampleVideoInfo(
                        id = "2",
                        title = "示例视频 2",
                        rate = "8.5",
                        bangumiId = 1002
                    ),
                    createSampleVideoInfo(
                        id = "3",
                        title = "示例视频 3",
                        rate = "9.0",
                        bangumiId = 1003
                    ),
                    createSampleVideoInfo(
                        id = "4",
                        title = "示例视频 4",
                        rate = "8.8",
                        bangumiId = 1004
                    )
                ),
                from = VideoCardSource.BANGUMI,
                isLoading = false,
                onVideoClick = {}
            )
        }
    }
}
