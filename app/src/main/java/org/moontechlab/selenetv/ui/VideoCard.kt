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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.moontechlab.selenetv.model.SourceSpeed
import org.moontechlab.selenetv.model.SpeedTestState
import org.moontechlab.selenetv.model.VideoInfo
import org.moontechlab.selenetv.util.getImageUrlSync
import kotlin.math.roundToInt

// 两个文本实例之间的间隔（dp）
private val TEXT_GAP_DP = 30.dp

/**
 * 左下角带圆角的三角形 Shape
 * @param cornerRadiusDp 左下角圆角半径 (dp)
 */
private class RoundedTriangleShape(private val cornerRadiusDp: Dp) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val cornerRadiusPx = with(density) { cornerRadiusDp.toPx() }
        val path = Path().apply {
            // 从左上角开始
            moveTo(0f, 0f)
            // 到左下角前的圆角起点
            lineTo(0f, size.height - cornerRadiusPx)
            // 左下角圆角
            quadraticTo(0f, size.height, cornerRadiusPx, size.height)
            // 到右下角
            lineTo(size.width, size.height)
            // 回到左上角
            close()
        }
        return Outline.Generic(path)
    }
}

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
 * 视频卡片尺寸样式
 */
enum class VideoCardStyle(
    val width: Dp,
    val height: Dp,
    val cornerRadius: Dp,
    val borderWidth: Dp,
    val titleFontSize: Int,
    val ratingBadgeSize: Dp,
    val ratingFontSize: Int,
    val ratingTranslation: Dp,
    val episodeFontSize: Int,
    val episodePaddingH: Dp,
    val episodePaddingV: Dp
) {
    MEDIUM(100.dp, 150.dp, 10.dp, 2.dp, 12, 30.dp, 12, 5.dp, 12, 6.dp, 3.dp),  // 中等尺寸
    LARGE(120.dp, 180.dp, 12.dp, 3.dp, 14, 36.dp, 15, 6.dp, 14, 8.dp, 4.dp)    // 大尺寸
}

/**
 * TV 专用视频卡片组件
 * 
 * @param videoInfo 视频信息
 * @param from 视频来源类型
 * @param onClick 点击回调
 * @param modifier 修饰符
 * @param isSelected 是否选中
 * @param style 卡片尺寸样式，默认 MEDIUM
 */
@Composable
fun VideoCard(
    videoInfo: VideoInfo,
    from: VideoCardSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    style: VideoCardStyle = VideoCardStyle.LARGE,
    // 长按（遥控 OK 键长按）回调，为 null 时不响应长按
    onLongClick: (() -> Unit)? = null,
    // 所属区块标识：同一内容可能同时出现在多行（如「继续观看」与「我的收藏」），
    // 仅靠 source+id 无法区分，返回详情时焦点会落到错误那张卡片。带上区块标识即可唯一。
    sectionKey: String = "",
    // 测速结果：非 null 时在封面底部叠加测速角标（分辨率·速度 / 测速中 / 超时）。仅 SOURCE 卡片使用。
    speed: SourceSpeed? = null,
    // 测速进行中、当前综合分最高 → 绿色描边「领先」（未聚焦时生效，聚焦白边优先）。
    leading: Boolean = false,
    // 测速进行中、本卡尚未出分 → 封面变暗，提示当前不可选。
    dimmed: Boolean = false
) {
    val cardWidth = style.width
    val cardHeight = style.height
    val cornerRadius = style.cornerRadius
    val innerCornerRadius = (cornerRadius.value * 0.67f).dp  // 聚焦时内部圆角
    val titleFontSize = style.titleFontSize.sp
    var isFocused by remember { mutableStateOf(false) }
    var titleWidth by remember { mutableStateOf(0) }
    var containerWidth by remember { mutableStateOf(0) }
    var isMeasured by remember { mutableStateOf(false) }
    
    // 获取全局测量上下文（可选，如果没有 Provider 则使用本地测量）
    val textMeasure = runCatching { LocalTextMeasure.current }.getOrNull()
    
    // 将 dp 转换为 px
    val density = androidx.compose.ui.platform.LocalDensity.current
    val textGapPx = with(density) { TEXT_GAP_DP.roundToPx() }
    
    // 计算播放进度
    val progress = if (videoInfo.totalTime > 0) {
        (videoInfo.playTime.toFloat() / videoInfo.totalTime.toFloat()).coerceIn(0f, 1f)
    } else 0f
    
    // 聚焦时的放大动画，使用弹簧效果
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,  // 较高阻尼，减少回弹
            stiffness = 400f     // 较高刚度，更快响应
        ),
        label = "video_card_scale"
    )
    
    // 无缝循环滚动动画
    val scrollAnim = remember { Animatable(0f) }
    
    // 判断是否需要滚动：聚焦 + 已测量 + 标题宽度 > 容器宽度
    val needsScroll by remember(isFocused, isMeasured, titleWidth, containerWidth) {
        derivedStateOf { isFocused && isMeasured && titleWidth > containerWidth && containerWidth > 0 }
    }
    
    // 懒测量：只在聚焦时才请求测量
    LaunchedEffect(isFocused, isMeasured) {
        if (isFocused && !isMeasured && textMeasure != null) {
            textMeasure.measureText(
                id = videoInfo.id,
                text = videoInfo.title,
                fontSize = style.titleFontSize.toFloat()
            ) { width ->
                titleWidth = width
                isMeasured = true
            }
        }
    }
    
    // 组件销毁时清除测量请求
    DisposableEffect(videoInfo.id) {
        onDispose {
            textMeasure?.clearMeasure(videoInfo.id)
        }
    }
    
    // 滚动动画控制
    LaunchedEffect(needsScroll) {
        if (needsScroll) {
            val loopDistance = titleWidth + textGapPx
            delay(1000) // 先停顿1秒
            try {
                while (true) {
                    scrollAnim.animateTo(
                        targetValue = -loopDistance.toFloat(),
                        animationSpec = tween(
                            durationMillis = loopDistance * 8, // 8ms/px，更快的滚动速度
                            easing = LinearEasing
                        )
                    )
                    scrollAnim.snapTo(0f)
                }
            } finally {
                // 协程取消时（组件销毁/失焦），重置滚动位置
                scrollAnim.snapTo(0f)
            }
        } else {
            // 失焦时平滑回弹
            scrollAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 300)
            )
        }
    }

    // 详情关闭后用于把焦点还原到来源卡片：匹配的卡片挂上还原 requester
    val returnFocus = org.moontechlab.selenetv.screen.LocalDetailReturnFocus.current
    // 区块标识 + source+id 组成唯一 key（详情覆盖层内 returnFocus 为 null，播放源卡片不参与）
    val returnKey = org.moontechlab.selenetv.screen.detailReturnKey(
        sectionKey, videoInfo.source, videoInfo.id,
    )

    Surface(
        onClick = {
            // 由卡片自身记录返回目标，确保「记录的 key」与「匹配的 key」永远一致
            returnFocus?.setTarget(returnKey)
            onClick()
        },
        onLongClick = onLongClick?.let { { it() } },
        modifier = modifier
            .then(
                if (returnFocus != null && returnFocus.matches(returnKey))
                    Modifier.focusRequester(returnFocus.requester)
                else Modifier
            )
            .width(cardWidth)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                // 还原到来源卡片时上报，让 HomeScreen 的还原协程知道 requestFocus 已生效、停止重试
                if (focusState.isFocused) returnFocus?.markFocused(returnKey)
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f), // 禁用默认缩放，使用自定义动画
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius, bottomStart = 0.dp, bottomEnd = 0.dp)
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
                    .width(cardWidth)
                    .height(cardHeight)
            ) {
                // 封面图片
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (dimmed) 0.4f else 1f }
                        .clip(RoundedCornerShape(cornerRadius))
                        .background(Color(0xFF2A2A2A))
                        .then(
                            // 聚焦白边优先；否则测速领先时显示绿边
                            when {
                                isFocused -> Modifier.border(
                                    width = style.borderWidth,
                                    color = Color.White,
                                    shape = RoundedCornerShape(cornerRadius)
                                )
                                leading -> Modifier.border(
                                    width = style.borderWidth,
                                    color = Color(0xFF4CAF50),
                                    shape = RoundedCornerShape(cornerRadius)
                                )
                                else -> Modifier
                            }
                        )
                ) {
                    AsyncImage(
                        model = getImageUrlSync(videoInfo.cover),
                        contentDescription = videoInfo.title,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isFocused || leading) 2.dp else 0.dp)
                            .clip(RoundedCornerShape(if (isFocused || leading) innerCornerRadius else cornerRadius)),
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

                // 测速角标（封面底部条）：测速中 → 「测速中」；DONE → 「1080p · 2.1MB/s」；FAILED → 「超时」
                if (speed != null) {
                    val label = when (speed.state) {
                        SpeedTestState.TESTING -> "测速中"
                        SpeedTestState.FAILED -> "超时"
                        SpeedTestState.DONE -> listOf(speed.quality, speed.speedLabel)
                            .filter { it.isNotEmpty() }
                            .joinToString(" · ")
                            .ifEmpty { "未知" }
                    }
                    val labelColor = when (speed.state) {
                        SpeedTestState.FAILED -> Color(0xFFE57373)
                        SpeedTestState.TESTING -> Color.White.copy(alpha = 0.7f)
                        SpeedTestState.DONE -> Color.White
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.62f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = labelColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.W600,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 年份徽章（搜索场景）
                if (from == VideoCardSource.SEARCH && videoInfo.year.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = style.episodePaddingH, vertical = style.episodePaddingV)
                            .align(Alignment.TopStart)
                    ) {
                        Text(
                            text = videoInfo.year,
                            color = Color.White,
                            fontSize = style.episodeFontSize.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 集数徽章
                if ((from == VideoCardSource.PLAYRECORD || 
                     from == VideoCardSource.FAVORITE || 
                     from == VideoCardSource.SEARCH ||
                     from == VideoCardSource.SOURCE ||
                     from == VideoCardSource.DOUBAN) &&
                    videoInfo.totalEpisodes > 1) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF4CAF50))
                            .padding(horizontal = style.episodePaddingH, vertical = style.episodePaddingV)
                            .align(Alignment.TopEnd)
                    ) {
                        Text(
                            text = if (videoInfo.index == 0) "${videoInfo.totalEpisodes}" else "${videoInfo.index}/${videoInfo.totalEpisodes}",
                            color = Color.White,
                            fontSize = style.episodeFontSize.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 评分徽章（番剧/豆瓣场景）- 左下角粉红色三角形
                if ((from == VideoCardSource.BANGUMI || from == VideoCardSource.DOUBAN) && 
                    videoInfo.rate != null && videoInfo.rate != "0.0") {
                    Box(
                        modifier = Modifier
                            .size(style.ratingBadgeSize)
                            .align(Alignment.BottomStart)
                            .background(
                                color = Color(0xFFE91E63),
                                shape = RoundedTriangleShape(cornerRadius)
                            )
                    ) {
                        // 文字沿三角形斜边居中
                        Text(
                            text = videoInfo.rate,
                            color = Color.White,
                            fontSize = style.ratingFontSize.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .graphicsLayer {
                                    rotationZ = 45f
                                    translationX = -style.ratingTranslation.toPx()
                                    translationY = style.ratingTranslation.toPx()
                                }
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
                    .width(cardWidth)
                    .height(18.dp)
                    .clipToBounds()
                    .onGloballyPositioned { coordinates ->
                        val newContainerWidth = coordinates.size.width
                        if (newContainerWidth != containerWidth) {
                            containerWidth = newContainerWidth
                        }
                    }
            ) {
                if (needsScroll) {
                    // 双文本无缝循环滚动
                    // 使用 Row 让两个文本自然排列，不受父容器宽度限制
                    Row(
                        modifier = Modifier
                            .wrapContentWidth(unbounded = true, align = Alignment.Start)
                            .align(Alignment.CenterStart)
                            .offset { IntOffset(scrollAnim.value.roundToInt(), 0) }
                    ) {
                        // 第一个文本实例
                        Text(
                            text = videoInfo.title,
                            color = Color.White,
                            fontSize = titleFontSize,
                            maxLines = 1,
                            softWrap = false
                        )
                        // 间隔
                        Spacer(modifier = Modifier.width(TEXT_GAP_DP))
                        // 第二个文本实例
                        Text(
                            text = videoInfo.title,
                            color = Color.White,
                            fontSize = titleFontSize,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                } else {
                    // 未聚焦或不需要滚动：单行显示，超长裁剪
                    Text(
                        text = videoInfo.title,
                        color = Color.White,
                        fontSize = titleFontSize,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                    )
                }
            }

            // 来源标签（SOURCE 卡片的主标题已是源名，无需重复渲染来源 chip）
            if ((from == VideoCardSource.PLAYRECORD ||
                 from == VideoCardSource.FAVORITE) &&
                videoInfo.sourceName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
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
                        fontSize = 11.sp
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
