package org.moontechlab.selenetv.screen.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.moontechlab.selenetv.model.LiveChannel
import org.moontechlab.selenetv.model.LiveChannelGroup

private val LiveAccent = Color(0xFF4CAF50)
private val LivePanelHeight = 300.dp
private const val AllTab = "全部"

internal fun livePanelChannelsForTab(groups: List<LiveChannelGroup>, tab: String): List<LiveChannel> =
    if (tab == AllTab) groups.flatMap { it.channels } else groups.find { it.name == tab }?.channels.orEmpty()

@Composable
fun LiveChannelPanel(
    groups: List<LiveChannelGroup>,
    currentChannelId: String,
    userAgent: String,
    onPickChannel: (LiveChannel) -> Unit,
    osdFocus: FocusRequester,
    activeTabFocus: FocusRequester,
    onContentExitUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = remember(groups) { listOf(AllTab) + groups.map { it.name } }
    var selectedTab by remember(tabs) { mutableStateOf(tabs.firstOrNull() ?: AllTab) }
    val contentFocus = remember { FocusRequester() }

    fun enterContent() {
        try { contentFocus.requestFocus() } catch (_: Exception) {}
    }

    LaunchedEffect(Unit) {
        repeat(6) {
            kotlinx.coroutines.delay(60)
            try { activeTabFocus.requestFocus(); return@LaunchedEffect } catch (_: Exception) {}
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(LivePanelHeight)
            .background(
                Brush.verticalGradient(
                    0f to Color(0xCC000000),
                    0.5f to Color(0xE6000000),
                    1f to Color(0xF2000000),
                ),
            )
            .padding(start = 56.dp, end = 56.dp, top = 12.dp, bottom = 14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEachIndexed { index, tab ->
                LivePanelTabItem(
                    label = tab,
                    selected = tab == selectedTab,
                    upTarget = osdFocus,
                    isFirst = index == 0,
                    isLast = index == tabs.lastIndex,
                    onEnterContent = ::enterContent,
                    onFocused = { selectedTab = tab },
                    modifier = if (tab == selectedTab) Modifier.focusRequester(activeTabFocus) else Modifier,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val contentHeight = maxHeight
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val dir = if (tabs.indexOf(targetState) >= tabs.indexOf(initialState)) 1 else -1
                    (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { it / 7 * dir }) togetherWith
                        (fadeOut(tween(150)) + slideOutHorizontally(tween(240)) { -it / 7 * dir })
                },
                label = "live_channel_tab",
            ) { tab ->
                LiveChannelGrid(
                    channels = livePanelChannelsForTab(groups, tab),
                    currentChannelId = currentChannelId,
                    userAgent = userAgent,
                    onPickChannel = onPickChannel,
                    contentHeight = contentHeight,
                    firstFocus = if (tab == selectedTab) contentFocus else null,
                    onExitUp = onContentExitUp,
                )
            }
        }
    }
}

@Composable
private fun LivePanelTabItem(
    label: String,
    selected: Boolean,
    upTarget: FocusRequester,
    isFirst: Boolean,
    isLast: Boolean,
    onEnterContent: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = modifier
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                // 边缘标签锁住左右，避免方向焦点搜索从首/尾标签溢出到下方频道网格丢焦
                .focusProperties {
                    up = upTarget
                    if (isFirst) left = FocusRequester.Cancel
                    if (isLast) right = FocusRequester.Cancel
                }
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                        onEnterContent()
                        true
                    } else {
                        false
                    }
                }
                .focusable()
                .clip(RoundedCornerShape(9.dp))
                // 焦点态 = 实心白底（全场最高对比，与内容卡白描边焦点同源）；选中但未聚焦时无底，靠下划线标记
                .background(if (focused) Color.White else Color.Transparent)
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            Text(
                text = label,
                color = when {
                    focused -> Color(0xFF0A0A0A)
                    selected -> Color.White
                    else -> Color.White.copy(alpha = 0.45f)
                },
                fontSize = 17.sp,
                fontWeight = if (selected || focused) FontWeight.W700 else FontWeight.W500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .width(22.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                // 聚焦时白底胶囊已是焦点，撤掉下划线避免重复；仅在「选中但焦点已下沉到频道网格」时用下划线标记当前分组
                .background(if (selected && !focused) Color.White else Color.Transparent),
        )
    }
}

@Composable
private fun LiveChannelGrid(
    channels: List<LiveChannel>,
    currentChannelId: String,
    userAgent: String,
    onPickChannel: (LiveChannel) -> Unit,
    contentHeight: Dp,
    firstFocus: FocusRequester?,
    onExitUp: () -> Unit,
) {
    val currentIndex = channels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = currentIndex - currentIndex % 2)
    val rowGap = 10.dp
    val tileHeight = (contentHeight - rowGap) / 2

    if (channels.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(contentHeight), contentAlignment = Alignment.Center) {
            Text(text = "暂无频道", color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)
        }
        return
    }

    LazyHorizontalGrid(
        rows = GridCells.Fixed(2),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(rowGap),
        modifier = Modifier.fillMaxWidth().height(contentHeight),
    ) {
        itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
            LiveChannelTile(
                channel = channel,
                current = channel.id == currentChannelId,
                topRow = index % 2 == 0,
                heightDp = tileHeight,
                userAgent = userAgent,
                onClick = { onPickChannel(channel) },
                onExitUp = onExitUp,
                focusRequester = if (index == currentIndex) firstFocus else null,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LiveChannelTile(
    channel: LiveChannel,
    current: Boolean,
    topRow: Boolean,
    heightDp: Dp,
    userAgent: String,
    onClick: () -> Unit,
    onExitUp: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "live_channel_scale",
    )
    val shape = RoundedCornerShape(12.dp)
    val nameHeight = 20.dp
    val logoBoxHeight = heightDp - nameHeight - 4.dp
    val logoBoxWidth = logoBoxHeight * 2f

    // 频道名是否超出卡片宽度（按聚焦时的加粗字重测量）。仅超宽时才跑马灯；
    // 否则文本居中显示——避免短名在聚焦时因 basicMarquee 取固有宽度而靠左。
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val nameOverflows = remember(channel.name, logoBoxWidth) {
        val w = textMeasurer.measure(
            text = channel.name,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.W700),
            softWrap = false,
        ).size.width
        with(density) { w.toDp() } > logoBoxWidth
    }
    // 焦点环只在聚焦时出现（白圈），是全卡唯一的「整圈描边」——专属焦点语义。
    // 「在看」不再用绿框（会和白焦点环撞形态、被误读成焦点），改为 logo 右上角绿圆点角标。
    val logoBorderColor = if (focused) Color.White else null

    Surface(
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            // 上行按「上」→ 离开网格回标签栏（Lazy 困焦，交 onExitUp 走清焦+回交流程）；
            // 下行按「上」→ 默认几何上移到本列上行；下行按「下」→ 锁住（网格下方无目标）。
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && topRow) {
                    onExitUp(); true
                } else false
            }
            .focusProperties { if (!topRow) down = FocusRequester.Cancel }
            .width(logoBoxWidth)
            .height(heightDp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
        ),
        // 整卡不描边，避免「logo+文本」外面整体一圈绿/白
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
            focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(logoBoxHeight)
                    .clip(shape)
                    .background(Color(0xFF1E1E1E))
                    .then(
                        // 焦点白圈：全卡唯一的整圈描边，专属焦点语义
                        if (logoBorderColor != null) {
                            Modifier.border(3.dp, logoBorderColor, shape)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(channel.logo)
                            .addHeader("User-Agent", userAgent)
                            .crossfade(true)
                            .build(),
                        contentDescription = channel.name,
                        modifier = Modifier.fillMaxSize().padding(if (focused) 5.dp else 7.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        tint = Color(0xFF555555),
                        modifier = Modifier.size(40.dp),
                    )
                }

                // 「在看」角标：右上角绿圆点（带深色描边在亮 logo 上也可见），不做成整圈避免冒充焦点环
                if (current) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0A0A0A))
                            .padding(1.5.dp)
                            .clip(CircleShape)
                            .background(LiveAccent),
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Column(
                modifier = Modifier.fillMaxWidth().height(nameHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                // 聚焦且名字超宽 → 跑马灯（靠左滚动展示全名）；否则一律居中
                val marquee = focused && nameOverflows
                Text(
                    text = channel.name,
                    // 绿色专留给「在看」；聚焦靠白圈+缩放表达，名字不再染绿，避免和「在看」撞语义
                    color = if (current) LiveAccent else Color.White,
                    fontSize = 12.sp,
                    fontWeight = if (current || focused) FontWeight.W700 else FontWeight.W500,
                    maxLines = 1,
                    softWrap = false,
                    lineHeight = 14.sp,
                    textAlign = TextAlign.Center,
                    overflow = if (marquee) TextOverflow.Visible else TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (marquee) Modifier.basicMarquee() else Modifier),
                )
                if (current) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = "● 在看",
                        color = LiveAccent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.W600,
                        lineHeight = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
