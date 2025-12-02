package org.moontechlab.selenetv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
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
@OptIn(ExperimentalFoundationApi::class)
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
    val firstItemFocusRequester = remember { FocusRequester() }
    var isRowFocused by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)  // container 底部 margin: 24
    ) {
        // 区块标题 - 纯展示，不可获得焦点
        // titleContainer: paddingHorizontal 16, paddingVertical 6, marginLeft 19
        Text(
            text = title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(start = 16.dp + 19.dp)  // paddingHorizontal 16 + marginLeft 19 = 35
                .padding(vertical = 6.dp)         // paddingVertical 6
        )
        
        // LazyRow 专用的 BringIntoViewSpec，保持水平滚动功能
        val rowBringIntoViewSpec = remember {
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                    // 将聚焦项滚动到 30% 位置（TV 标准行为）
                    val targetOffset = containerSize * 0.35f
                    return offset - targetOffset + (size / 2f)
                }
            }
        }
        
        // 卡片列表
        // cardsContainer: paddingHorizontal 16, paddingVertical 8
        CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides rowBringIntoViewSpec
        ) {
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusGroup()
                    .focusRestorer(firstItemFocusRequester)
                    .onFocusChanged { focusState ->
                        // 当 LazyRow 获得焦点但内部没有聚焦项时，自动聚焦第一个元素
                        if (focusState.hasFocus && !isRowFocused) {
                            isRowFocused = true
                            try {
                                firstItemFocusRequester.requestFocus()
                            } catch (_: Exception) {
                                // 忽略焦点请求失败
                            }
                        } else if (!focusState.hasFocus) {
                            isRowFocused = false
                        }
                    }
                    .onPreviewKeyEvent { keyEvent ->
                        // 处理返回键 - 重置滚动位置
                        if (keyEvent.key == Key.Back && keyEvent.type == KeyEventType.KeyDown) {
                            coroutineScope.launch {
                                listState.animateScrollToItem(0)
                            }
                            false // 不阻止默认行为
                        } else {
                            false
                        }
                    }
            ) {
            if (isLoading || videos.isEmpty()) {
                // 加载状态或无数据 - 显示 10 个骨架屏占位卡片
                items(10) { index ->
                    SkeletonVideoCard(
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                            .onPreviewKeyEvent { keyEvent ->
                                handleVerticalNavigation(keyEvent, listState, coroutineScope)
                            }
                    )
                }
            } else {
                // 实际视频卡片
                itemsIndexed(videos, key = { _, video -> video.id }) { index, video ->
                    VideoCard(
                        videoInfo = video,
                        from = from,
                        onClick = { onVideoClick(video) },
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                            .onPreviewKeyEvent { keyEvent ->
                                handleVerticalNavigation(keyEvent, listState, coroutineScope)
                            }
                    )
                }
            }
            }
        }
        
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
                    listState.animateScrollToItem(0)
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
