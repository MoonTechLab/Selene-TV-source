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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.font.FontWeight
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
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.model.SourceSpeed
import org.moontechlab.selenetv.model.SpeedTestState
import org.moontechlab.selenetv.screen.detail.HeroInfo
import org.moontechlab.selenetv.screen.detail.sourceKey
import org.moontechlab.selenetv.service.SpeedTestService
import org.moontechlab.selenetv.ui.SpeedTestPill
import org.moontechlab.selenetv.util.getImageUrlSync

enum class PanelTab { Detail, Episodes, Sources }

private val Accent = Color(0xFF4CAF50)
// 面板高度：约屏高 47%（密度下 1dp≈1.77px）。越矮其顶边越靠下，进度条贴在上方也随之到屏幕中线附近。
// internal：PlayerScreen 的开合揭示容器需按此高度伸缩（替代 animateContentSize），故同包可见。
internal val PanelHeight = 256.dp

/**
 * 进度条下方上拉的标签面板（详情 / 选集 / 换源）。由进度条按「下」唤出、默认进入「详情」；
 * 详情页含「下一集」快捷入口。标签栏左右键切换标签（焦点即选中，切换带横向滑入动画），
 * 按「下」进入内容区，按「上」回到进度条（进度条获焦后面板自动收起，见 PlayerScreen）。
 *
 * 背景用从上到下逐渐加深的渐变（承接控制条的暗化），避免硬边块；内容区高度按可用空间精确填充。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerPanel(
    title: String,
    heroInfo: HeroInfo?,
    epCount: Int,
    episodes: List<String>,
    episodesTitles: List<String>?,
    currentEpisode: Int,
    sources: List<SearchResult>,
    currentSourceKey: String,
    hasNext: Boolean,
    tab: PanelTab,
    onTabChange: (PanelTab) -> Unit,
    onNextEpisode: () -> Unit,
    onPickEpisode: (Int) -> Unit,
    onPickSource: (String) -> Unit,
    scrubberFocus: FocusRequester,
    activeTabFocus: FocusRequester,
    onContentExitUp: () -> Unit,
    speedResults: Map<String, SourceSpeed> = emptyMap(),
    speedTesting: Boolean = false,
    onSpeedTest: () -> Unit = {},
    onStopSpeedTest: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tabs = remember(epCount, sources.size) {
        buildList {
            add(PanelTab.Detail)
            if (epCount > 1) add(PanelTab.Episodes)
            add(PanelTab.Sources)
        }
    }

    // 每个标签的内容区各持有独立 FocusRequester：切换时 AnimatedContent 短暂同时持有新旧内容，
    // 共用一个 requester 会触发「attached to multiple components」。标签按「下」请求当前标签对应的那个。
    val detailFocus = remember { FocusRequester() }
    val epFocus = remember { FocusRequester() }
    val srcFocus = remember { FocusRequester() }
    val enterContent: () -> Unit = {
        val fr = when (tab) {
            PanelTab.Detail -> detailFocus
            PanelTab.Episodes -> epFocus
            PanelTab.Sources -> srcFocus
        }
        try { fr.requestFocus() } catch (_: Exception) {}
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
            .height(PanelHeight)
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
            tabs.forEachIndexed { index, t ->
                PanelTabItem(
                    label = when (t) {
                        PanelTab.Detail -> "详情"
                        PanelTab.Episodes -> "选集"
                        PanelTab.Sources -> "换源"
                    },
                    selected = t == tab,
                    upTarget = scrubberFocus,
                    isFirst = index == 0,
                    isLast = index == tabs.lastIndex,
                    onEnterContent = enterContent,
                    onFocused = { onTabChange(t) },
                    modifier = if (t == tab) Modifier.focusRequester(activeTabFocus) else Modifier,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 内容区：按可用高度精确填充，避免底部留白；切换带横向滑入 + 淡入。
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val ch = maxHeight
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (tabs.indexOf(targetState) >= tabs.indexOf(initialState)) 1 else -1
                    (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { it / 7 * dir }) togetherWith
                        (fadeOut(tween(150)) + slideOutHorizontally(tween(240)) { -it / 7 * dir })
                },
                label = "panel_tab",
            ) { t ->
                when (t) {
                    PanelTab.Detail -> DetailTabContent(
                        title = title, info = heroInfo, hasNext = hasNext,
                        showUpNext = epCount > 1,
                        currentEpisode = currentEpisode, onNextEpisode = onNextEpisode,
                        contentHeight = ch, cardFocus = detailFocus, tabFocus = activeTabFocus,
                    )
                    PanelTab.Episodes -> EpisodesTabContent(
                        episodes = episodes, episodesTitles = episodesTitles, currentEpisode = currentEpisode,
                        onPick = onPickEpisode, contentHeight = ch, firstFocus = epFocus, onExitUp = onContentExitUp,
                    )
                    PanelTab.Sources -> SourcesTabContent(
                        sources = sources, currentSourceKey = currentSourceKey, onPick = onPickSource,
                        contentHeight = ch, firstFocus = srcFocus, onExitUp = onContentExitUp,
                        speedResults = speedResults, speedTesting = speedTesting,
                        onSpeedTest = onSpeedTest, onStopSpeedTest = onStopSpeedTest,
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelTabItem(
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
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
                // 边缘标签锁住左右，避免方向焦点搜索从首/尾标签溢出到下方内容网格丢焦
                .focusProperties {
                    up = upTarget
                    if (isFirst) left = FocusRequester.Cancel
                    if (isLast) right = FocusRequester.Cancel
                }
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { onEnterContent(); true } else false
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
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .width(22.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                // 聚焦时白底胶囊已是焦点，撤掉下划线避免重复；仅在「选中但焦点已下沉到内容区」时用下划线标记当前标签
                .background(if (selected && !focused) Color.White else Color.Transparent),
        )
    }
}

// ───────────────────────────── 详情标签 ─────────────────────────────

@Composable
private fun DetailTabContent(
    title: String,
    info: HeroInfo?,
    hasNext: Boolean,
    showUpNext: Boolean,
    currentEpisode: Int,
    onNextEpisode: () -> Unit,
    contentHeight: Dp,
    cardFocus: FocusRequester,
    tabFocus: FocusRequester,
) {
    val poster = info?.poster?.takeIf { it.isNotEmpty() }
    val displayTitle = info?.title?.takeIf { it.isNotBlank() } ?: title

    Row(modifier = Modifier.fillMaxWidth().height(contentHeight)) {
        Box(
            modifier = Modifier
                .size(width = contentHeight * 0.66f, height = contentHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF222222)),
        ) {
            if (poster != null) {
                // 面板展开动画期间外层高度从 0 渐变,会把内容约束压到极小;若让 AsyncImage 按布局约束
                // 定解码尺寸,首帧会解出极小图(上采样=模糊)且展开后不再重取。显式固定解码尺寸,
                // 与动画中的瞬时约束解耦,首次即按清晰度加载。
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(getImageUrlSync(poster))
                        .size(512)
                        .build(),
                    contentDescription = displayTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        Spacer(modifier = Modifier.width(24.dp))

        // 信息（左，纯展示）：顶部对齐封面顶端
        Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Top) {
            Text(text = displayTitle, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.W700, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                info?.genres?.take(3)?.forEach { g -> Text(text = g, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp) }
                info?.rate?.let { Text(text = "★ $it", color = Color(0xFFE91E63), fontSize = 14.sp, fontWeight = FontWeight.W700) }
            }
            info?.subLine?.takeIf { it.isNotEmpty() }?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = it, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = info?.summary?.takeIf { it.isNotEmpty() } ?: "暂无简介",
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 13.sp,
                lineHeight = 21.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 单集内容（电影等）不展示「下一集」卡片；多集即使在最后一集也保留（显示「已是最后一集」禁用态）
        if (showUpNext) {
            Spacer(modifier = Modifier.width(24.dp))

            UpNextCard(
                hasNext = hasNext,
                nextEpisode = currentEpisode + 2,
                heightDp = contentHeight,
                onClick = onNextEpisode,
                focusRequester = cardFocus,
                upTarget = tabFocus,
            )
        }
    }
}

@Composable
private fun UpNextCard(
    hasNext: Boolean,
    nextEpisode: Int,
    heightDp: Dp,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    upTarget: FocusRequester,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "upnext_scale",
    )
    val fg = if (focused) Color(0xFF0A0A0A) else Color.White
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        enabled = hasNext,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusProperties { up = upTarget; down = FocusRequester.Cancel }
            .width(184.dp)
            .height(heightDp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.05f),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (focused) Color(0xFF0A0A0A).copy(alpha = 0.10f) else Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (hasNext) fg else Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "下一集",
                color = if (hasNext) fg.copy(alpha = if (focused) 0.65f else 0.6f) else Color.White.copy(alpha = 0.3f),
                fontSize = 12.sp,
                fontWeight = FontWeight.W500,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = if (hasNext) "第 $nextEpisode 集" else "已是最后一集",
                color = if (hasNext) fg else Color.White.copy(alpha = 0.3f),
                fontSize = if (hasNext) 17.sp else 13.sp,
                fontWeight = FontWeight.W700,
            )
        }
    }
}

// ───────────────────────────── 选集 / 换源标签 ─────────────────────────────

@Composable
private fun EpisodesTabContent(
    episodes: List<String>,
    episodesTitles: List<String>?,
    currentEpisode: Int,
    onPick: (Int) -> Unit,
    contentHeight: Dp,
    firstFocus: FocusRequester,
    onExitUp: () -> Unit,
) {
    // 2 行密排横滚网格：填满内容区高度且不显空。列序：偶数 index = 上行、奇数 = 下行。
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = (currentEpisode - currentEpisode % 2).coerceAtLeast(0))
    val rowGap = 10.dp
    val chipH = (contentHeight - rowGap) / 2
    LazyHorizontalGrid(
        rows = GridCells.Fixed(2),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(rowGap),
        modifier = Modifier.fillMaxWidth().height(contentHeight),
    ) {
        gridItemsIndexed(episodes) { index, _ ->
            // 仅在有真实剧集标题时展示标题；纯集号（"1141"/"第1141集"/"第 1141 集"/"1141话"）一律按无标题处理，
            // 只居中显示集号，避免与集号重复且在窄芯片里被截断成「第\n114…」。
            val label = episodesTitles?.getOrNull(index)
                ?.takeIf { it.isNotEmpty() }
                ?.takeIf { t -> t.replace(Regex("[第集话話期\\s]"), "").any { !it.isDigit() } }
            EpisodeChip(
                number = index + 1,
                label = label,
                current = index == currentEpisode,
                topRow = index % 2 == 0,
                heightDp = chipH,
                onClick = { onPick(index) },
                onExitUp = onExitUp,
                focusRequester = if (index == currentEpisode.coerceIn(0, episodes.lastIndex.coerceAtLeast(0))) firstFocus else null,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourcesTabContent(
    sources: List<SearchResult>,
    currentSourceKey: String,
    onPick: (String) -> Unit,
    contentHeight: Dp,
    firstFocus: FocusRequester,
    onExitUp: () -> Unit,
    speedResults: Map<String, SourceSpeed> = emptyMap(),
    speedTesting: Boolean = false,
    onSpeedTest: () -> Unit = {},
    onStopSpeedTest: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val selectedIndex = sources.indexOfFirst { sourceKey(it) == currentSourceKey }.coerceAtLeast(0)
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        // 首/尾项贴边时聚焦放大(1.05x)会越过 LazyRow 裁剪边被切;留出横向 contentPadding 给放大留余量。
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.fillMaxWidth().height(contentHeight),
    ) {
        item(key = "__speedtest__") {
            SpeedTestPill(
                testing = speedTesting,
                testedCount = speedResults.count { it.value.state != SpeedTestState.TESTING },
                totalCount = speedResults.size,
                onClick = { if (speedTesting) onStopSpeedTest() else onSpeedTest() },
                width = 124.dp,
                height = contentHeight,
                idleSubtitle = "测速并排序",
                // 与 SourceTile 一致:LazyRow 困焦,按「上」交给 onExitUp 走清焦+回交标签栏;「下」锁住。
                modifier = Modifier
                    .animateItem()
                    .onKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { onExitUp(); true } else false
                    }
                    .focusProperties { down = FocusRequester.Cancel },
            )
        }
        itemsIndexed(sources, key = { _, s -> sourceKey(s) }) { index, source ->
            val key = sourceKey(source)
            val speed = speedResults[key]
            SourceTile(
                name = source.sourceName,
                episodeCount = source.episodes.size.coerceAtLeast(1),
                poster = source.poster,
                current = key == currentSourceKey,
                heightDp = contentHeight,
                speed = speed,
                dimmed = speedTesting && (speed == null || speed.state == SpeedTestState.TESTING),
                onClick = { if (!speedTesting) onPick(key) },
                onExitUp = onExitUp,
                focusRequester = if (index == selectedIndex) firstFocus else null,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/** 选集芯片（2 行网格内的紧凑项）：有自定义标题时「集号 + 标题」，否则仅居中集号；当前集绿框绿字 + 「● 在看」。 */
@Composable
private fun EpisodeChip(
    number: Int,
    label: String?,
    current: Boolean,
    topRow: Boolean,
    heightDp: Dp,
    onClick: () -> Unit,
    onExitUp: () -> Unit,
    focusRequester: FocusRequester?,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "ep_chip_scale",
    )
    val shape = RoundedCornerShape(10.dp)
    val numColor = if (focused) Color(0xFF0A0A0A) else if (current) Accent else Color.White.copy(alpha = 0.95f)
    val titleColor = if (focused) Color(0xFF0A0A0A) else Color.White.copy(alpha = 0.82f)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            // 上行按「上」→ 离开网格回标签栏（Lazy 容器困焦，交给 onExitUp 走清焦+回交流程）；
            // 下行按「上」→ 走默认几何搜索上移到本列上行；下行按「下」→ 锁住（网格下方无目标）。
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && topRow) {
                    onExitUp(); true
                } else false
            }
            .focusProperties { if (!topRow) down = FocusRequester.Cancel }
            .width(124.dp)
            .height(heightDp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (current) Accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.07f),
            focusedContainerColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(androidx.compose.foundation.BorderStroke(1.5.dp, if (current) Accent else Color.Transparent), shape = shape),
        ),
    ) {
        if (label == null) {
            // 无自定义标题：集号居中，不再塞「第 N 集」造成重复与截断
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "$number", color = numColor, fontSize = 24.sp, fontWeight = FontWeight.W700, maxLines = 1)
                if (current) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = "● 在看", color = if (focused) Color(0xFF0A0A0A) else Accent, fontSize = 10.sp, fontWeight = FontWeight.W600)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(text = "$number", color = numColor, fontSize = 25.sp, fontWeight = FontWeight.W700)
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = label, color = titleColor, fontSize = 12.sp, fontWeight = FontWeight.W500, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                    if (current) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(text = "● 在看", color = if (focused) Color(0xFF0A0A0A) else Accent, fontSize = 10.sp, fontWeight = FontWeight.W600)
                    }
                }
            }
        }
    }
}

/** 换源瓦片：源封面为底 + 底部源名/集数；当前源绿框 + 「在用」徽标，聚焦白描边。 */
@Composable
private fun SourceTile(
    name: String,
    episodeCount: Int,
    poster: String,
    current: Boolean,
    heightDp: Dp,
    onClick: () -> Unit,
    onExitUp: () -> Unit,
    focusRequester: FocusRequester?,
    speed: SourceSpeed? = null,
    dimmed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "src_tile_scale",
    )
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            // 换源是 LazyRow，同样困焦：按「上」交给 onExitUp 走清焦+回交标签栏流程；「下」锁住。
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { onExitUp(); true } else false
            }
            .focusProperties { down = FocusRequester.Cancel }
            .width(124.dp)
            .height(heightDp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF1A1A1A),
            focusedContainerColor = Color(0xFF1A1A1A),
        ),
        // 焦点白圈是唯一的整圈描边（专属焦点语义）；当前源不再用绿圈（会和白焦点环撞形态、
        // 被误读成焦点），改由右上角「在用」绿色角标承载——与直播换台卡保持一致。
        border = ClickableSurfaceDefaults.border(
            border = Border(androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent), shape = shape),
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.5.dp, Color.White), shape = shape),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            org.moontechlab.selenetv.ui.CoverImage(
                rawUrl = poster,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
            )
            // 底部压暗确保文字可读
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color(0x33000000),
                        0.78f to Color(0xC0000000),
                        1f to Color(0xF0000000),
                    ),
                ),
            )
            Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp)) {
                // 元数据行(名称上方):测速后显示分辨率·速率(取代集数),否则显示集数(单集省略)。
                // 124dp 窄卡放不下「集数 + 分辨率 + 速率」一行,故测速后用结果取代集数,始终单行。
                val metaText: String? = when {
                    speed != null -> SpeedTestService.badgeLabel(speed)
                    episodeCount > 1 -> "$episodeCount 集"
                    else -> null
                }
                if (metaText != null) {
                    val metaColor = when (speed?.state) {
                        SpeedTestState.FAILED -> Color(0xFFE57373)
                        SpeedTestState.TESTING -> Color.White.copy(alpha = 0.6f)
                        SpeedTestState.DONE -> Color.White
                        else -> Color.White.copy(alpha = 0.7f) // 集数
                    }
                    Text(text = metaText, color = metaColor, fontSize = 11.sp, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(3.dp))
                }
                Text(text = name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.W700, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (current) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Accent)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(text = "在用", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.W700)
                }
            }
            if (dimmed) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            }
        }
    }
}
