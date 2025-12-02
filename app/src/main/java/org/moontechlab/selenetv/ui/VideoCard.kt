package org.moontechlab.selenetv.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import org.moontechlab.selenetv.model.VideoInfo
import kotlin.math.roundToInt

/**
 * 视频来源类型
 */
enum class VideoCardSource {
    PLAYRECORD,  // 播放记录
    FAVORITE,    // 收藏
    SEARCH,      // 搜索
    BANGUMI,     // 番剧
    DOUBAN,      // 豆瓣
    SOURCE       // 播放源
}

/**
 * TV 专用视频卡片组件
 */
@Composable
fun VideoCard(
    videoInfo: VideoInfo,
    from: VideoCardSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    var titleWidth by remember { mutableStateOf(0) }
    var containerWidth by remember { mutableStateOf(0) }
    
    // 计算播放进度
    val progress = if (videoInfo.totalTime > 0) {
        (videoInfo.playTime.toFloat() / videoInfo.totalTime.toFloat()).coerceIn(0f, 1f)
    } else 0f
    
    // 聚焦时的放大动画，使用弹簧效果（与 TabSwitcher 一致）
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "video_card_scale"
    )
    
    // 标题滚动动画
    val scrollAnimation = rememberInfiniteTransition(label = "titleScroll")
    val shouldScroll = isFocused && titleWidth > containerWidth && containerWidth > 0
    
    val scrollOffset by scrollAnimation.animateFloat(
        initialValue = 0f,
        targetValue = if (shouldScroll) -(titleWidth - containerWidth).toFloat() else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (shouldScroll) (titleWidth - containerWidth) * 20 else 0,
                delayMillis = 1000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "scrollOffset"
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(90.dp)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f), // 禁用默认缩放，使用自定义动画
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(6.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 封面区域
            Box(
                modifier = Modifier
                    .width(90.dp)
                    .height(135.dp)
            ) {
                // 封面图片
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2A2A2A))
                        .then(
                            if (isFocused) {
                                Modifier.border(
                                    width = 2.dp,
                                    color = Color.White,
                                    shape = RoundedCornerShape(6.dp)
                                )
                            } else Modifier
                        )
                ) {
                    AsyncImage(
                        model = videoInfo.cover,
                        contentDescription = videoInfo.title,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isFocused) 2.dp else 0.dp)
                            .clip(RoundedCornerShape(if (isFocused) 4.dp else 6.dp)),
                        contentScale = ContentScale.Crop
                    )
                }

                // 选中状态指示器
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50))
                            .border(1.dp, Color.Black, CircleShape)
                            .align(Alignment.TopStart),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                // 年份徽章（搜索场景）
                if (from == VideoCardSource.SEARCH && videoInfo.year.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .align(Alignment.TopStart)
                    ) {
                        Text(
                            text = videoInfo.year,
                            color = Color.White,
                            fontSize = 8.sp
                        )
                    }
                }

                // 集数徽章
                if ((from == VideoCardSource.PLAYRECORD || 
                     from == VideoCardSource.FAVORITE || 
                     from == VideoCardSource.SEARCH ||
                     from == VideoCardSource.SOURCE) &&
                    videoInfo.totalEpisodes > 0) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF4CAF50))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .align(Alignment.TopEnd)
                    ) {
                        Text(
                            text = "${videoInfo.index}/${videoInfo.totalEpisodes}",
                            color = Color.White,
                            fontSize = 8.sp
                        )
                    }
                }

                // 评分徽章（番剧/豆瓣场景）
                if ((from == VideoCardSource.BANGUMI || from == VideoCardSource.DOUBAN) && 
                    videoInfo.rate != null && videoInfo.rate != "0.0") {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE91E63))
                            .align(Alignment.TopEnd),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = videoInfo.rate,
                            color = Color.White,
                            fontSize = 9.sp
                        )
                    }
                }

                // 播放进度条
                if ((from == VideoCardSource.PLAYRECORD || from == VideoCardSource.SOURCE) && 
                    progress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomCenter)
                    ) {
                        // 背景
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.White.copy(alpha = 0.3f))
                        )
                        // 进度
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(Color(0xFF4CAF50))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 标题区域
            Box(
                modifier = Modifier
                    .width(90.dp)
                    .height(14.dp)
                    .onGloballyPositioned { coordinates ->
                        containerWidth = coordinates.size.width
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = videoInfo.title,
                    color = Color.White,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .offset { IntOffset(scrollOffset.roundToInt(), 0) }
                        .onGloballyPositioned { coordinates ->
                            titleWidth = coordinates.size.width
                        }
                )
            }

            // 来源标签
            if ((from == VideoCardSource.PLAYRECORD || 
                 from == VideoCardSource.FAVORITE || 
                 from == VideoCardSource.SOURCE) && 
                videoInfo.sourceName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .border(
                            width = 0.5.dp,
                            color = Color.White.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(2.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = videoInfo.sourceName,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 8.sp
                    )
                }
            }
        }
    }
}

/**
 * 扩展函数：从 VideoInfo 创建用于预览的示例数据
 */
fun createSampleVideoInfo(
    id: String = "1",
    title: String = "示例视频",
    cover: String = "",
    year: String = "2024",
    index: Int = 1,
    totalEpisodes: Int = 12,
    playTime: Long = 600000L,
    totalTime: Long = 1200000L,
    sourceName: String = "示例源",
    rate: String? = null,
    doubanId: String? = null,
    bangumiId: Int? = null
): VideoInfo {
    return VideoInfo(
        id = id,
        source = "sample_source",
        title = title,
        sourceName = sourceName,
        year = year,
        cover = cover,
        index = index,
        totalEpisodes = totalEpisodes,
        playTime = playTime,
        totalTime = totalTime,
        saveTime = System.currentTimeMillis(),
        searchTitle = title,
        doubanId = doubanId,
        bangumiId = bangumiId,
        rate = rate
    )
}

@Preview(device = Devices.TV_1080p)
@Composable
fun VideoCardPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(48.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 播放记录样式
            VideoCard(
                videoInfo = createSampleVideoInfo(
                    id = "1",
                    title = "这是一个很长的标题用来测试滚动效果",
                    index = 5,
                    totalEpisodes = 12,
                    playTime = 720000L,
                    totalTime = 1200000L,
                    sourceName = "爱奇艺"
                ),
                from = VideoCardSource.PLAYRECORD,
                onClick = {}
            )

            // 搜索样式
            VideoCard(
                videoInfo = createSampleVideoInfo(
                    id = "2",
                    title = "搜索结果",
                    year = "2024",
                    index = 1,
                    totalEpisodes = 24
                ),
                from = VideoCardSource.SEARCH,
                onClick = {}
            )

            // 番剧样式
            VideoCard(
                videoInfo = createSampleVideoInfo(
                    id = "3",
                    title = "番剧标题",
                    rate = "9.5",
                    bangumiId = 12345
                ),
                from = VideoCardSource.BANGUMI,
                onClick = {},
                isSelected = true
            )
        }
    }
}
