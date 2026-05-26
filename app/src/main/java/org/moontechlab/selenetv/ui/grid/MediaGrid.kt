package org.moontechlab.selenetv.ui.grid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.moontechlab.selenetv.model.VideoInfo
import org.moontechlab.selenetv.ui.VideoCard
import org.moontechlab.selenetv.ui.VideoCardSource
import org.moontechlab.selenetv.ui.VideoCardStyle

// ==================== 布局常量 ====================
// 四个网格 Tab（电影/综艺/电视剧/动漫）共享的网格骨架。布局公式（horizontalPadding）
// 由各 Tab 自行计算后传入，其余行为逐字保持一致。

const val GRID_COLUMNS = 6              // 每行6列
const val GRID_CARD_WIDTH = 120        // 卡片宽度 120dp
const val GRID_CARD_HEIGHT = 180       // 卡片封面高度 180dp (2:3 比例)
const val GRID_ROW_GAP = 28            // 行间距 28dp
const val GRID_ROW_HEIGHT = 202        // 行高 = 180 + 22 (标题区域)
const val GRID_PAGE_LIMIT = 25         // 每页数据量
const val GRID_RENDER_BUFFER = 3       // 缓冲行数

/**
 * 网格脚手架：顶部留白 + 筛选区（测高）+ 内容区，并接管聚焦行/居中滚动/翻页触发。
 *
 * 布局差异（horizontalPadding）由各 Tab 计算后传入。cardGap 在历史实现中被传入但因
 * 行内使用 [Arrangement.SpaceBetween] 而从未生效，这里保留参数以保持调用点的语义一致。
 *
 * @param resetKey 通常传 filterState，变化时把聚焦行复位到 0（复刻原「筛选变化重置」）。
 * @param onLoadMore 聚焦到最后一行时触发，内部应自行 guard isLoading/hasMore。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> MediaGridScaffold(
    items: List<T>,
    isLoading: Boolean,
    errorMessage: String?,
    scrollState: ScrollState,
    horizontalPadding: Dp,
    @Suppress("UNUSED_PARAMETER") cardGap: Dp,
    contentFocusRequester: FocusRequester,
    resetKey: Any?,
    itemToVideoInfo: (T) -> VideoInfo,
    itemToSource: (T) -> VideoCardSource,
    onItemClick: (T) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onContentNavigateUp: () -> Unit,
    filterSection: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val screenHeightPx = with(density) { screenHeight.toPx() }

    // 底部 padding: (屏幕高度/2 - 67.5dp) 让最后一行能居中
    val bottomPadding = (screenHeight / 2) - 67.5.dp

    // 聚焦行索引
    var focusedRowIndex by remember { mutableIntStateOf(0) }
    // 筛选区域高度 (用于计算滚动位置)
    var filterSectionHeight by remember { mutableIntStateOf(0) }

    // 将内容列表分成行
    val rows = remember(items) { items.chunked(GRID_COLUMNS) }

    // 行高 (px)
    val rowHeightPx = with(density) { GRID_ROW_HEIGHT.dp.toPx() }
    val rowGapPx = with(density) { GRID_ROW_GAP.dp.toPx() }
    val topSpacerPx = with(density) { 64.dp.toPx() } // TabSwitcher 空间

    // 计算滚动到指定行使其居中的目标位置
    fun calculateScrollTarget(rowIndex: Int): Int {
        val rowTopPosition = topSpacerPx + filterSectionHeight + (rowIndex * (rowHeightPx + rowGapPx))
        val rowCenterPosition = rowTopPosition + (rowHeightPx / 2)
        val targetScroll = rowCenterPosition - (screenHeightPx / 2)
        return targetScroll.toInt().coerceAtLeast(0)
    }

    // 处理垂直导航的滚动
    fun handleVerticalScroll(targetRowIndex: Int) {
        if (targetRowIndex < 0 || targetRowIndex >= rows.size) return
        val targetScroll = calculateScrollTarget(targetRowIndex)
        coroutineScope.launch {
            scrollState.animateScrollTo(targetScroll)
        }
    }

    // 筛选条件变化时重置聚焦行
    LaunchedEffect(resetKey) {
        focusedRowIndex = 0
    }

    // 卡片聚焦回调
    fun onCardFocus(rowIndex: Int) {
        focusedRowIndex = rowIndex
        // 当聚焦到最后一行时触发加载更多 (hasMore/isLoading 由 onLoadMore 内部 guard)
        if (rowIndex == rows.lastIndex && !isLoading) {
            onLoadMore()
        }
    }

    // 禁用自动滚动的 BringIntoViewSpec
    val disabledBringIntoViewSpec = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                return 0f // 禁用自动滚动
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 使用 CompositionLocalProvider 禁用自动滚动
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides disabledBringIntoViewSpec
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .focusGroup()
                    .padding(horizontal = horizontalPadding)
                    .padding(bottom = bottomPadding)
            ) {
                // 顶部留出 TabSwitcher 的空间
                Spacer(modifier = Modifier.height(64.dp))

                // 筛选区域
                Box(
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        filterSectionHeight = coordinates.size.height
                    }
                ) {
                    filterSection()
                }

                // 筛选区域和内容区域之间的间距
                Spacer(modifier = Modifier.height(14.dp))

                // 内容区域 (6列网格)
                MediaGridContent(
                    rows = rows,
                    focusedRowIndex = focusedRowIndex,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    onRetry = onRetry,
                    totalRows = rows.size,
                    itemToVideoInfo = itemToVideoInfo,
                    itemToSource = itemToSource,
                    onVerticalNavigate = ::handleVerticalScroll,
                    onCardFocus = ::onCardFocus,
                    onItemClick = onItemClick,
                    focusRequester = contentFocusRequester,
                    onNavigateUp = onContentNavigateUp
                )
            }
        }
    }
}

/**
 * 筛选行 (标签 + 内容)
 */
@Composable
fun GridFilterRow(
    label: String,
    content: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // 标签 (固定宽度 36dp)
        Box(
            modifier = Modifier.width(36.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 内容
        content()
    }
}

/**
 * 内容区域 (6列网格)
 */
@Composable
private fun <T> MediaGridContent(
    rows: List<List<T>>,
    focusedRowIndex: Int,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    totalRows: Int,
    itemToVideoInfo: (T) -> VideoInfo,
    itemToSource: (T) -> VideoCardSource,
    onVerticalNavigate: (Int) -> Unit,
    onCardFocus: (Int) -> Unit,
    onItemClick: (T) -> Unit,
    focusRequester: FocusRequester,
    onNavigateUp: () -> Unit
) {
    // 判断行是否可见 (前3行始终渲染 + 聚焦点附近的行)
    fun isRowVisible(rowIndex: Int): Boolean {
        return rowIndex < 3 || rowIndex in (focusedRowIndex - GRID_RENDER_BUFFER)..(focusedRowIndex + GRID_RENDER_BUFFER)
    }

    Column(
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(GRID_ROW_GAP.dp)
    ) {
        if (!isLoading && errorMessage != null && rows.isEmpty()) {
            // 初始加载失败：显示错误提示 + 重试按钮
            MediaGridErrorState(
                message = errorMessage,
                onRetry = onRetry
            )
        } else if (isLoading && rows.isEmpty()) {
            // 初始加载: 显示 3 行骨架屏
            repeat(3) {
                MediaGridRow<T>(
                    items = emptyList(),
                    rowIndex = it,
                    isVisible = true,
                    isLoading = true,
                    totalRows = 0,
                    itemToVideoInfo = itemToVideoInfo,
                    itemToSource = itemToSource,
                    onVerticalNavigate = {},
                    onCardFocus = {},
                    onItemClick = {}
                )
            }
        } else {
            // 正常内容
            rows.forEachIndexed { rowIndex, rowItems ->
                val isVisible = isRowVisible(rowIndex)
                val isLastRow = rowIndex == rows.lastIndex

                MediaGridRow(
                    items = rowItems,
                    rowIndex = rowIndex,
                    isVisible = isVisible,
                    isLoading = isLastRow && isLoading,
                    totalRows = totalRows,
                    itemToVideoInfo = itemToVideoInfo,
                    itemToSource = itemToSource,
                    onVerticalNavigate = onVerticalNavigate,
                    onCardFocus = onCardFocus,
                    onItemClick = onItemClick,
                    onNavigateUp = onNavigateUp
                )
            }

            // 正在加载时，显示额外骨架行
            if (isLoading && rows.isNotEmpty()) {
                // 最后一行是满行时显示2行骨架，不满时显示1行骨架
                val skeletonRows = if (rows.lastOrNull()?.size == GRID_COLUMNS) 2 else 1
                repeat(skeletonRows) {
                    MediaGridRow<T>(
                        items = emptyList(),
                        rowIndex = rows.size + it,
                        isVisible = true,
                        isLoading = true,
                        totalRows = totalRows,
                        itemToVideoInfo = itemToVideoInfo,
                        itemToSource = itemToSource,
                        onVerticalNavigate = {},
                        onCardFocus = {},
                        onItemClick = {}
                    )
                }
            }
        }
    }
}

/**
 * 网格行
 */
@Composable
private fun <T> MediaGridRow(
    items: List<T>,
    rowIndex: Int,
    isVisible: Boolean,
    isLoading: Boolean,
    totalRows: Int,
    itemToVideoInfo: (T) -> VideoInfo,
    itemToSource: (T) -> VideoCardSource,
    onVerticalNavigate: (Int) -> Unit,
    onCardFocus: (Int) -> Unit,
    onItemClick: (T) -> Unit,
    onNavigateUp: () -> Unit = {}
) {
    if (!isVisible && items.isNotEmpty()) {
        // 不可见行使用占位符保持高度
        Spacer(modifier = Modifier.height(GRID_ROW_HEIGHT.dp))
        return
    }

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .height(GRID_ROW_HEIGHT.dp)
    ) {
        // 渲染实际卡片
        items.forEach { item ->
            VideoCard(
                videoInfo = itemToVideoInfo(item),
                from = itemToSource(item),
                onClick = { onItemClick(item) },
                style = VideoCardStyle.LARGE,
                modifier = Modifier
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
                                        // 第一行按上键，导航到筛选器
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
                        } else {
                            false
                        }
                    }
            )
        }

        // 最后一行且正在加载时，补充骨架卡片
        if (isLoading && items.isNotEmpty() && items.size < GRID_COLUMNS) {
            repeat(GRID_COLUMNS - items.size) {
                MediaCardSkeleton()
            }
        }

        // 纯骨架行
        if (items.isEmpty() && isLoading) {
            repeat(GRID_COLUMNS) {
                MediaCardSkeleton()
            }
        }

        // 非加载态的不满行：用透明占位符补齐到整行列数，避免 SpaceBetween 把少量卡片
        // 拉开占满整行宽度；补齐后卡片按列对齐、尾部留空。
        if (!isLoading && items.isNotEmpty() && items.size < GRID_COLUMNS) {
            repeat(GRID_COLUMNS - items.size) {
                Spacer(modifier = Modifier.width(GRID_CARD_WIDTH.dp))
            }
        }
    }
}

/**
 * 视频卡片骨架屏
 */
@Composable
fun MediaCardSkeleton() {
    // 静态占位（取原呼吸动画 0.3~0.6 的中点）：骨架成屏出现、显示短暂，shimmer 几乎无人察觉；
    // 而原先每个占位项各自跑无限动画、又把动画值读进 background → 每帧重组数十个占位项，去掉换静态值省下持续开销。
    val alpha = 0.45f

    Column(
        modifier = Modifier.width(GRID_CARD_WIDTH.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 封面骨架
        Box(
            modifier = Modifier
                .width(GRID_CARD_WIDTH.dp)
                .height(GRID_CARD_HEIGHT.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = alpha))
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 标题骨架
        Box(
            modifier = Modifier
                .width(60.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = alpha * 0.7f))
        )
    }
}

/**
 * 网格内容区错误状态 UI
 */
@Composable
fun MediaGridErrorState(
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
                text = "加载失败",
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
