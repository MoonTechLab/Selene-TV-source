package org.moontechlab.selenetv.screen.tab

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.model.LiveChannel
import org.moontechlab.selenetv.model.LiveChannelGroup
import org.moontechlab.selenetv.model.LiveSource
import org.moontechlab.selenetv.screen.player.LiveLaunch
import org.moontechlab.selenetv.service.LiveService
import org.moontechlab.selenetv.ui.FilterOption
import org.moontechlab.selenetv.ui.FilterSelector

private const val TAG = "LiveTab"
private const val DEFAULT_UA = "AptvPlayer/1.4.10"

// ==================== 布局常量 ====================

private const val COLUMNS = 4             // 每行4列
private const val CARD_GAP = 24           // 卡片间距 24dp
private const val ROW_GAP = 28            // 行间距 28dp
private const val RENDER_BUFFER = 3       // 缓冲行数
private const val HORIZONTAL_PADDING = 58 // 固定左右边距 58dp

// ==================== LiveTab ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveTab(
    focusRequester: FocusRequester = remember { FocusRequester() },
    scrollState: ScrollState = rememberScrollState(),
    onChannelClick: (LiveLaunch) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 数据状态
    var liveSources by remember { mutableStateOf<List<LiveSource>>(emptyList()) }
    var channelGroups by remember { mutableStateOf<List<LiveChannelGroup>>(emptyList()) }
    var currentSource by remember { mutableStateOf<LiveSource?>(null) }
    var selectedGroup by remember { mutableStateOf("all") }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 聚焦行索引
    var focusedRowIndex by remember { mutableIntStateOf(0) }

    // 焦点管理
    val sourceFocusRequester = remember { FocusRequester() }
    val groupFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }

    // 布局计算
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.toFloat()
    val screenHeightDp = configuration.screenHeightDp
    val screenHeight = screenHeightDp.dp
    val screenHeightPx = with(density) { screenHeight.toPx() }
    // 动态计算卡片宽度
    val availableWidth = screenWidthDp - HORIZONTAL_PADDING * 2
    val cardWidthDp = ((availableWidth - CARD_GAP * (COLUMNS - 1)) / COLUMNS).dp
    val logoHeightDp = cardWidthDp / 2  // 2:1 比例
    val rowHeightDp = logoHeightDp + 8.dp + 18.dp + 10.dp
    val bottomPadding = (screenHeight / 2) - 67.5.dp

    // 筛选后的频道列表
    val filteredChannels = remember(channelGroups, selectedGroup) {
        if (selectedGroup == "all") {
            channelGroups.flatMap { it.channels }
        } else {
            channelGroups.find { it.name == selectedGroup }?.channels ?: emptyList()
        }
    }

    // 分成行
    val rows = remember(filteredChannels) {
        filteredChannels.chunked(COLUMNS)
    }

    // 判断行是否可见
    fun isRowVisible(rowIndex: Int): Boolean {
        return rowIndex < 3 || rowIndex in (focusedRowIndex - RENDER_BUFFER)..(focusedRowIndex + RENDER_BUFFER)
    }

    // 筛选区域高度
    var filterSectionHeight by remember { mutableIntStateOf(0) }

    // 行高 (px)
    val rowHeightPx = with(density) { rowHeightDp.toPx() }
    val rowGapPx = with(density) { ROW_GAP.dp.toPx() }
    val topSpacerPx = with(density) { 64.dp.toPx() }

    // 计算滚动到指定行使其居中的目标位置
    fun calculateScrollTarget(rowIndex: Int): Int {
        val rowTopPosition = topSpacerPx + filterSectionHeight + (rowIndex * (rowHeightPx + rowGapPx))
        val rowCenterPosition = rowTopPosition + (rowHeightPx / 2)
        val targetScroll = rowCenterPosition - (screenHeightPx / 2)
        return targetScroll.toInt().coerceAtLeast(0)
    }

    fun handleVerticalScroll(targetRowIndex: Int) {
        if (targetRowIndex < 0 || targetRowIndex >= rows.size) return
        val targetScroll = calculateScrollTarget(targetRowIndex)
        coroutineScope.launch {
            scrollState.animateScrollTo(targetScroll)
        }
    }

    // 禁用自动滚动
    val disabledBringIntoViewSpec = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                return 0f
            }
        }
    }

    // 加载频道数据
    fun loadChannels(source: LiveSource) {
        coroutineScope.launch {
            // 清空旧数据以显示骨架屏
            channelGroups = emptyList()
            selectedGroup = "all"
            isLoading = true
            errorMessage = null
            try {
                val channels = withContext(Dispatchers.IO) {
                    LiveService.getLiveChannels(source)
                }
                if (channels.isEmpty()) {
                    errorMessage = "该直播源暂无频道"
                } else {
                    channelGroups = LiveService.groupChannels(channels)
                }
            } catch (e: Exception) {
                errorMessage = "加载频道失败: ${e.message}"
                Log.e(TAG, "加载频道失败", e)
            }
            isLoading = false
        }
    }

    // 加载直播源
    fun loadSources(forceRefresh: Boolean = false) {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val sources = withContext(Dispatchers.IO) {
                    LiveService.getLiveSources(forceRefresh)
                }
                if (sources.isEmpty()) {
                    errorMessage = "暂无直播源"
                    isLoading = false
                    return@launch
                }
                liveSources = sources
                val target = currentSource?.let { cur ->
                    sources.find { it.key == cur.key }
                } ?: sources.first()
                currentSource = target
                loadChannels(target)
            } catch (e: Exception) {
                errorMessage = "加载直播源失败: ${e.message}"
                isLoading = false
                Log.e(TAG, "加载直播源失败", e)
            }
        }
    }

    // 刷新
    fun refresh() {
        LiveService.clearAllCache()
        focusedRowIndex = 0
        loadSources(forceRefresh = true)
    }

    // 初始加载
    LaunchedEffect(Unit) {
        loadSources()
    }

    // 分组筛选选项
    val groupOptions = remember(channelGroups) {
        listOf(FilterOption("all", "全部")) +
                channelGroups.map { FilterOption(it.name, it.name) }
    }

    // 直播源筛选选项
    val sourceOptions = remember(liveSources) {
        liveSources.map { FilterOption(it.key, it.name) }
    }

    fun onCardFocus(rowIndex: Int) {
        focusedRowIndex = rowIndex
    }

    // 保存默认的 BringIntoViewSpec，用于筛选区域恢复 LazyRow 自动滚动
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current

    Box(modifier = Modifier.fillMaxSize()) {
        // 在 verticalScroll 外层禁用自动滚动，防止同行水平导航时页面抖动
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides disabledBringIntoViewSpec
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .focusGroup()
                    .padding(horizontal = HORIZONTAL_PADDING.dp)
                    .padding(bottom = bottomPadding)
            ) {
                // 顶部留出 TabSwitcher 的空间
                Spacer(modifier = Modifier.height(64.dp))

                // 筛选区域：恢复默认 BringIntoViewSpec，让 FilterSelector 的 LazyRow 能自动滚动
                CompositionLocalProvider(
                    LocalBringIntoViewSpec provides defaultBringIntoViewSpec
                ) {
                    Box(
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            filterSectionHeight = coordinates.size.height
                        }
                    ) {
                        LiveFilterSection(
                            sourceOptions = sourceOptions,
                            groupOptions = groupOptions,
                            selectedSourceKey = currentSource?.key ?: "",
                            selectedGroup = selectedGroup,
                            showSourceFilter = liveSources.size > 1,
                            showGroupFilter = channelGroups.isNotEmpty() && !isLoading,
                            // 加载中先占位「分组」骨架行，避免数据落地后分组行突然插入把卡片整体下推、与骨架对不上
                            showGroupSkeleton = isLoading,
                            onSourceSelect = { key ->
                                val source = liveSources.find { it.key == key }
                                if (source != null && source.key != currentSource?.key) {
                                    currentSource = source
                                    focusedRowIndex = 0
                                    loadChannels(source)
                                }
                            },
                            onGroupSelect = { key ->
                                selectedGroup = key
                                focusedRowIndex = 0
                                coroutineScope.launch {
                                    scrollState.animateScrollTo(0)
                                }
                            },
                            sourceFocusRequester = sourceFocusRequester,
                            groupFocusRequester = groupFocusRequester,
                            focusRequester = focusRequester,
                            onNavigateDown = {
                                contentFocusRequester.requestFocus()
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 内容区域：保持禁用（继承外层的 disabledBringIntoViewSpec）
                LiveContentSection(
                    rows = rows,
                    isRowVisible = ::isRowVisible,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    onRetry = { refresh() },
                    cardWidth = cardWidthDp,
                    logoHeight = logoHeightDp,
                    rowHeight = rowHeightDp,
                    userAgent = currentSource?.ua?.ifEmpty { DEFAULT_UA } ?: DEFAULT_UA,
                    totalRows = rows.size,
                    onVerticalNavigate = ::handleVerticalScroll,
                    onCardFocus = ::onCardFocus,
                    onChannelClick = { channel ->
                        currentSource?.let { source ->
                            onChannelClick(LiveLaunch(channel, source, channelGroups))
                        }
                    },
                    focusRequester = contentFocusRequester,
                    onNavigateUp = {
                        if (channelGroups.isNotEmpty()) {
                            groupFocusRequester.requestFocus()
                        } else if (liveSources.size > 1) {
                            sourceFocusRequester.requestFocus()
                        }
                    }
                )
            }
        }
    }
}

// ==================== 筛选区域 ====================

@Composable
private fun LiveFilterSection(
    sourceOptions: List<FilterOption>,
    groupOptions: List<FilterOption>,
    selectedSourceKey: String,
    selectedGroup: String,
    showSourceFilter: Boolean,
    showGroupFilter: Boolean,
    showGroupSkeleton: Boolean,
    onSourceSelect: (String) -> Unit,
    onGroupSelect: (String) -> Unit,
    sourceFocusRequester: FocusRequester,
    groupFocusRequester: FocusRequester,
    focusRequester: FocusRequester,
    onNavigateDown: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 11.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (showSourceFilter) {
            LiveFilterRow(label = "直播源") {
                FilterSelector(
                    options = sourceOptions,
                    selectedKey = selectedSourceKey,
                    onSelect = onSourceSelect,
                    focusRequester = sourceFocusRequester,
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionDown) {
                                if (showGroupFilter) {
                                    groupFocusRequester.requestFocus()
                                } else {
                                    onNavigateDown()
                                }
                                true
                            } else false
                        }
                )
            }
        }

        if (showGroupFilter) {
            LiveFilterRow(label = "分组") {
                FilterSelector(
                    options = groupOptions,
                    selectedKey = selectedGroup,
                    onSelect = onGroupSelect,
                    // 始终绑定 groupFocusRequester，使 onNavigateUp 在单/多直播源下都能聚焦到分组行
                    focusRequester = groupFocusRequester,
                    modifier = Modifier
                        .then(
                            // 单一直播源时分组是首行，额外绑定外部 focusRequester 作为 TabSwitcher 下发入口
                            if (!showSourceFilter) Modifier.focusRequester(focusRequester) else Modifier
                        )
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.key) {
                                    Key.DirectionUp -> {
                                        if (showSourceFilter) {
                                            sourceFocusRequester.requestFocus()
                                            true
                                        } else false
                                    }
                                    Key.DirectionDown -> {
                                        onNavigateDown()
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                )
            }
        } else if (showGroupSkeleton) {
            // 真实分组未就绪时用骨架占位，保持与加载完成后一致的高度，卡片网格不再下移
            LiveFilterRow(label = "分组") {
                GroupFilterSkeleton()
            }
        }
    }
}

/** 「分组」筛选行的骨架占位：尺寸与 FilterSelector 一致，避免数据落地后布局抖动。 */
@Composable
private fun GroupFilterSkeleton() {
    // 静态占位（取原呼吸动画 0.18~0.4 的中点）：去掉无限动画 + 每帧重组，详见 MediaCardSkeleton
    val alpha = 0.29f
    Row(
        modifier = Modifier
            .background(
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(19.dp)
            )
            .padding(horizontal = 4.2.dp, vertical = 2.2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(40.dp, 56.dp, 48.dp).forEach { w ->
            Box(
                modifier = Modifier
                    .width(w)
                    .height(30.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = alpha))
            )
        }
    }
}

@Composable
private fun LiveFilterRow(
    label: String,
    content: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier.width(48.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        content()
    }
}

// ==================== 内容区域 ====================

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun LiveContentSection(
    rows: List<List<LiveChannel>>,
    isRowVisible: (Int) -> Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    cardWidth: Dp,
    logoHeight: Dp,
    rowHeight: Dp,
    userAgent: String,
    totalRows: Int,
    onVerticalNavigate: (Int) -> Unit,
    onCardFocus: (Int) -> Unit,
    onChannelClick: (LiveChannel) -> Unit,
    focusRequester: FocusRequester,
    onNavigateUp: () -> Unit
) {
    // 关闭直播播放页返回网格时，让焦点恢复直接落到「进入播放页的那张频道卡片」（挂了
    // returnFocus.requester），而不是 focusGroup 默认的第一张卡片——消除「先落第一张再跳到目标」的抖动。
    val returnFocus = org.moontechlab.selenetv.screen.LocalDetailReturnFocus.current
    Column(
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusRestorer {
                if (returnFocus?.targetKey?.startsWith("live|") == true) returnFocus.requester
                else FocusRequester.Default
            }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp)
    ) {
        if (!isLoading && errorMessage != null && rows.isEmpty()) {
            LiveErrorState(message = errorMessage, onRetry = onRetry)
        } else if (isLoading && rows.isEmpty()) {
            repeat(3) {
                ChannelGridRow(
                    channels = emptyList(),
                    rowIndex = it,
                    isVisible = true,
                    isLoading = true,
                    rowHeight = rowHeight,
                    logoHeight = logoHeight,
                    userAgent = userAgent,
                    totalRows = 0,
                    onVerticalNavigate = {},
                    onCardFocus = {},
                    onChannelClick = {}
                )
            }
        } else if (!isLoading && rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无频道",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp
                )
            }
        } else {
            rows.forEachIndexed { rowIndex, rowChannels ->
                ChannelGridRow(
                    channels = rowChannels,
                    rowIndex = rowIndex,
                    isVisible = isRowVisible(rowIndex),
                    isLoading = false,
                    rowHeight = rowHeight,
                    logoHeight = logoHeight,
                    userAgent = userAgent,
                    totalRows = totalRows,
                    onVerticalNavigate = onVerticalNavigate,
                    onCardFocus = onCardFocus,
                    onChannelClick = onChannelClick,
                    onNavigateUp = onNavigateUp
                )
            }
        }
    }
}

// ==================== 频道网格行 ====================

@Composable
private fun ChannelGridRow(
    channels: List<LiveChannel>,
    rowIndex: Int,
    isVisible: Boolean,
    isLoading: Boolean,
    rowHeight: Dp,
    logoHeight: Dp,
    userAgent: String,
    totalRows: Int,
    onVerticalNavigate: (Int) -> Unit,
    onCardFocus: (Int) -> Unit,
    onChannelClick: (LiveChannel) -> Unit,
    onNavigateUp: () -> Unit = {}
) {
    if (!isVisible && channels.isNotEmpty()) {
        Spacer(modifier = Modifier.height(rowHeight))
        return
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(CARD_GAP.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
    ) {
        if (channels.isNotEmpty()) {
            channels.forEach { channel ->
                ChannelCard(
                    channel = channel,
                    logoHeight = logoHeight,
                    userAgent = userAgent,
                    onClick = { onChannelClick(channel) },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                onCardFocus(rowIndex)
                            }
                        }
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.key) {
                                    Key.DirectionUp -> {
                                        if (rowIndex == 0) {
                                            onNavigateUp()
                                            true
                                        } else {
                                            onVerticalNavigate(rowIndex - 1)
                                            false
                                        }
                                    }
                                    Key.DirectionDown -> {
                                        if (rowIndex < totalRows - 1) {
                                            onVerticalNavigate(rowIndex + 1)
                                        }
                                        false
                                    }
                                    else -> false
                                }
                            } else false
                        }
                )
            }
            // 不满一行时用不可见占位保持对齐
            if (channels.size < COLUMNS) {
                repeat(COLUMNS - channels.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        } else if (isLoading) {
            repeat(COLUMNS) {
                ChannelCardSkeleton(
                    logoHeight = logoHeight,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ==================== 频道卡片 ====================

@Composable
private fun ChannelCard(
    channel: LiveChannel,
    logoHeight: Dp,
    userAgent: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isFocused by remember { mutableStateOf(false) }
    val cornerRadius = 12.dp

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 400f
        ),
        label = "channel_card_scale"
    )

    // 进入直播播放页后返回时把焦点还原到这张卡片：点击时登记目标，匹配的卡片挂上还原 requester
    val returnFocus = org.moontechlab.selenetv.screen.LocalDetailReturnFocus.current
    val returnKey = "live|${channel.id}"

    Surface(
        onClick = {
            returnFocus?.setTarget(returnKey)
            onClick()
        },
        modifier = modifier
            .then(
                if (returnFocus != null && returnFocus.matches(returnKey))
                    Modifier.focusRequester(returnFocus.requester)
                else Modifier
            )
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(cornerRadius)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo 区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(logoHeight)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFF1e1e1e))
                    .then(
                        if (isFocused) {
                            Modifier.border(
                                width = 3.dp,
                                color = Color.White,
                                shape = RoundedCornerShape(cornerRadius)
                            )
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (channel.logo.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(channel.logo)
                            .addHeader("User-Agent", userAgent)
                            .crossfade(true)
                            .build(),
                        contentDescription = channel.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isFocused) 3.dp else 0.dp)
                            .clip(
                                RoundedCornerShape(
                                    if (isFocused) (cornerRadius.value * 0.67f).dp else cornerRadius
                                )
                            )
                            .padding(16.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        tint = Color(0xFF555555),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 频道名称
            Text(
                text = channel.name,
                color = if (isFocused) Color(0xFF4CAF50) else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.W500,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ==================== 骨架屏 ====================

@Composable
private fun ChannelCardSkeleton(
    logoHeight: Dp,
    modifier: Modifier = Modifier
) {
    // 静态占位（取原呼吸动画 0.3~0.6 的中点）：去掉每项各自的无限动画 + 每帧重组，详见 MediaCardSkeleton
    val alpha = 0.45f

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(logoHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = alpha))
        )

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .width(80.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = alpha * 0.7f))
        )
    }
}

// ==================== 错误状态 ====================

@Composable
private fun LiveErrorState(
    message: String,
    onRetry: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp
            )

            Surface(
                onClick = onRetry,
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(0x14FFFFFF),
                    focusedContainerColor = Color(0xFF4CAF50)
                ),
                modifier = Modifier.onFocusChanged { isFocused = it.isFocused }
            ) {
                Text(
                    text = "重试",
                    color = if (isFocused) Color.White else Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun LiveTabPreview() {
    LiveTab()
}
