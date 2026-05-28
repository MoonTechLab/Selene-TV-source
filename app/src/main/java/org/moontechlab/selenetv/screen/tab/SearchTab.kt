package org.moontechlab.selenetv.screen.tab

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.data.Repositories
import org.moontechlab.selenetv.model.AggregatedSearchResult
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.model.aggregateSearchResults
import org.moontechlab.selenetv.model.titlesMatch
import org.moontechlab.selenetv.service.SearchEvent
import org.moontechlab.selenetv.service.SearchProgress
import org.moontechlab.selenetv.service.SearchService
import org.moontechlab.selenetv.ui.FilterDialog
import org.moontechlab.selenetv.ui.SelectorOption
import org.moontechlab.selenetv.ui.TransparentFilterOption
import org.moontechlab.selenetv.ui.TransparentFilterSelector
import org.moontechlab.selenetv.ui.VideoCard
import org.moontechlab.selenetv.ui.VideoCardSource
import org.moontechlab.selenetv.ui.VideoCardStyle

/**
 * 年份排序状态
 */
enum class YearSortState {
    NONE,       // 无排序
    DESCENDING, // 降序（新->旧）
    ASCENDING   // 升序（旧->新）
}

/**
 * 搜索筛选状态
 */
data class SearchFilterState(
    val source: String = "全部",
    val title: String = "全部",
    val year: String = "全部",
    val yearSort: YearSortState = YearSortState.NONE
)

private const val TAG = "SearchTab"

// ==================== 布局常量 ====================
private const val SEARCH_COLUMNS = 6
private const val SEARCH_CARD_WIDTH = 120
private const val SEARCH_CARD_HEIGHT = 180
private const val SEARCH_ROW_GAP = 28
private const val SEARCH_ROW_HEIGHT = 202
private const val SEARCH_RENDER_BUFFER = 3
private const val SEARCH_HORIZONTAL_PADDING = 58

data class SearchGridLayout(
    val horizontalPadding: Dp,
    val cardGap: Dp
)

fun calculateSearchGridLayout(screenWidthDp: Float): SearchGridLayout {
    val totalCardWidth = SEARCH_CARD_WIDTH * SEARCH_COLUMNS
    val totalPadding = SEARCH_HORIZONTAL_PADDING * 2
    val availableForGaps = screenWidthDp - totalCardWidth - totalPadding
    val cardGap = availableForGaps / (SEARCH_COLUMNS - 1)
    
    return SearchGridLayout(
        horizontalPadding = SEARCH_HORIZONTAL_PADDING.dp,
        cardGap = cardGap.dp
    )
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchTab(
    focusRequester: FocusRequester = remember { FocusRequester() },
    scrollState: ScrollState = rememberScrollState(),
    onOpenDetail: (AggregatedSearchResult) -> Unit = {},
    initialQuery: String? = null,
    onInitialQueryConsumed: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.toFloat()
    val screenHeightDp = configuration.screenHeightDp
    val screenHeight = screenHeightDp.dp
    val screenHeightPx = with(density) { screenHeight.toPx() }
    val gridLayout = remember(screenWidthDp) { calculateSearchGridLayout(screenWidthDp) }
    
    // 搜索关键词
    var searchQuery by remember { mutableStateOf("") }
    
    // 搜索历史
    var searchHistory by remember { mutableStateOf<List<String>>(emptyList()) }
    
    // 是否显示搜索历史区域
    var showHistory by remember { mutableStateOf(true) }
    
    // 原始搜索结果（用于聚合）
    var rawSearchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    
    // 聚合后的搜索结果（未筛选）
    var allSearchResults by remember { mutableStateOf<List<AggregatedSearchResult>>(emptyList()) }
    
    // 搜索进度
    var searchProgress by remember { mutableStateOf<SearchProgress?>(null) }
    
    // 是否正在搜索
    var isSearching by remember { mutableStateOf(false) }
    
    // 聚焦行索引
    var focusedRowIndex by remember { mutableIntStateOf(0) }
    
    // 筛选状态
    var filterState by remember { mutableStateOf(SearchFilterState()) }
    
    // 透明筛选器聚焦状态
    var transparentFocusedKey by remember { mutableStateOf<String?>(null) }
    
    // FilterDialog 状态
    var dialogVisible by remember { mutableStateOf(false) }
    var dialogKey by remember { mutableStateOf<String?>(null) }
    
    // 焦点管理
    val searchInputFocusRequester = focusRequester
    val searchButtonFocusRequester = remember { FocusRequester() }
    val clearButtonFocusRequester = remember { FocusRequester() }
    val historyFocusRequester = remember { FocusRequester() }
    val filterFocusRequester = remember { FocusRequester() }
    val resultsFocusRequester = remember { FocusRequester() }
    
    // 搜索区域顶部间距动画：96dp -> 0dp
    val topSpacing by animateDpAsState(
        targetValue = if (showHistory) 96.dp else 0.dp,
        animationSpec = tween(durationMillis = 300),
        label = "top_spacing"
    )
    
    // 底部 padding
    val bottomPadding = (screenHeight / 2) - 67.5.dp
    
    // 动态生成筛选选项
    val sourceOptions = remember(allSearchResults) {
        val sources = allSearchResults.flatMap { it.sourceNames }.distinct().sorted()
        listOf(SelectorOption("全部", "全部")) + sources.map { SelectorOption(it, it) }
    }
    
    val titleOptions = remember(allSearchResults) {
        val titles = allSearchResults.map { it.title }.distinct().sorted()
        listOf(SelectorOption("全部", "全部")) + titles.map { SelectorOption(it, it) }
    }
    
    val yearOptions = remember(allSearchResults) {
        val years = allSearchResults.map { it.year }.distinct()
            .sortedWith(compareBy<String> { if (it == "unknown") 1 else 0 }.thenByDescending { it })
        listOf(SelectorOption("全部", "全部")) + years.map { SelectorOption(it, it) }
    }
    
    // 筛选和排序逻辑
    fun applyFilterAndSort(results: List<AggregatedSearchResult>, filter: SearchFilterState): List<AggregatedSearchResult> {
        var filtered = results
        
        // 来源筛选
        if (filter.source != "全部") {
            filtered = filtered.filter { it.sourceNames.contains(filter.source) }
        }
        
        // 标题筛选
        if (filter.title != "全部") {
            filtered = filtered.filter { titlesMatch(it.title, filter.title) }
        }
        
        // 年份筛选
        if (filter.year != "全部") {
            filtered = filtered.filter { it.year == filter.year }
        }
        
        // 年份排序
        filtered = when (filter.yearSort) {
            YearSortState.NONE -> filtered
            YearSortState.DESCENDING -> filtered.sortedWith(
                compareBy<AggregatedSearchResult> { if (it.year == "unknown") 1 else 0 }
                    .thenByDescending { it.year }
            )
            YearSortState.ASCENDING -> filtered.sortedWith(
                compareBy<AggregatedSearchResult> { if (it.year == "unknown") 1 else 0 }
                    .thenBy { it.year }
            )
        }
        
        return filtered
    }
    
    // 筛选后的搜索结果：随原始结果/筛选状态自动派生，避免 LaunchedEffect 往返与一帧旧数据
    val searchResults by remember {
        derivedStateOf { applyFilterAndSort(allSearchResults, filterState) }
    }

    // 结果或筛选变化时，焦点回到首行（保持原有行为）
    LaunchedEffect(allSearchResults, filterState) {
        focusedRowIndex = 0
    }

    // 将搜索结果分成行
    val rows = remember(searchResults) {
        searchResults.chunked(SEARCH_COLUMNS)
    }
    
    // 判断行是否可见
    fun isRowVisible(rowIndex: Int): Boolean {
        return rowIndex < 3 || rowIndex in (focusedRowIndex - SEARCH_RENDER_BUFFER)..(focusedRowIndex + SEARCH_RENDER_BUFFER)
    }
    
    // FilterDialog 相关数据
    val dialogTitle = when (dialogKey) {
        "source" -> "选择来源"
        "title" -> "选择标题"
        "year" -> "选择年份"
        else -> ""
    }
    
    val dialogOptions = when (dialogKey) {
        "source" -> sourceOptions
        "title" -> titleOptions
        "year" -> yearOptions
        else -> emptyList()
    }
    
    val dialogSelectedValue = when (dialogKey) {
        "source" -> filterState.source
        "title" -> filterState.title
        "year" -> filterState.year
        else -> ""
    }
    
    // 行高 (px)
    val rowHeightPx = with(density) { SEARCH_ROW_HEIGHT.dp.toPx() }
    val rowGapPx = with(density) { SEARCH_ROW_GAP.dp.toPx() }
    val topSpacerPx = with(density) { 84.dp.toPx() }
    val searchSectionHeight = with(density) { 42.dp.toPx() }
    
    // 计算滚动到指定行使其居中的目标位置
    fun calculateScrollTarget(rowIndex: Int): Int {
        val rowTopPosition = topSpacerPx + searchSectionHeight + 24.dp.value * density.density + (rowIndex * (rowHeightPx + rowGapPx))
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
    
    // 禁用自动滚动的 BringIntoViewSpec
    val disabledBringIntoViewSpec = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                return 0f
            }
        }
    }
    
    // 加载搜索历史
    LaunchedEffect(Unit) {
        try {
            val history = withContext(Dispatchers.IO) {
                Repositories.userData().getSearchHistory()
            }
            searchHistory = history
        } catch (e: Exception) {
            Log.e(TAG, "加载搜索历史失败: ${e.message}")
        }
    }
    
    // 监听搜索事件
    LaunchedEffect(Unit) {
        SearchService.eventFlow.collect { event ->
            when (event) {
                is SearchEvent.Results -> {
                    // 去重并更新原始结果
                    val newRawResults = SearchService.deduplicateResults(rawSearchResults + event.results)
                    rawSearchResults = newRawResults
                    // 实时聚合
                    allSearchResults = aggregateSearchResults(newRawResults)
                }
                is SearchEvent.Progress -> {
                    searchProgress = event.progress
                }
                is SearchEvent.Error -> {
                    Log.e(TAG, "搜索错误: ${event.message}")
                }
                is SearchEvent.Complete -> {
                    isSearching = false
                }
            }
        }
    }
    
    // 清理
    DisposableEffect(Unit) {
        onDispose {
            SearchService.stopSearch()
        }
    }
    
    // 更新本地搜索历史：如果关键词已存在则移到第一个，否则添加到第一个
    fun updateLocalSearchHistory(keyword: String) {
        searchHistory = listOf(keyword) + searchHistory.filter { it != keyword }
    }
    
    // 执行搜索
    val performSearch: (String) -> Unit = { query ->
        if (query.isNotBlank()) {
            showHistory = false
            searchButtonFocusRequester.requestFocus()
            // 更新本地搜索历史
            updateLocalSearchHistory(query)
            coroutineScope.launch {
                // 同步到服务器
                try {
                    withContext(Dispatchers.IO) {
                        Repositories.userData().addSearchHistory(query)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "添加搜索历史失败: ${e.message}")
                }
                delay(300) // 等待动画完成
                rawSearchResults = emptyList()
                allSearchResults = emptyList()
                searchProgress = null
                isSearching = true
                focusedRowIndex = 0
                filterState = SearchFilterState() // 重置筛选状态
                SearchService.startSearch(query)
            }
        }
    }
    
    // 来自详情页「去搜索」的初始查询：填入搜索框并立即检索，随后清空避免重复触发
    LaunchedEffect(initialQuery) {
        val q = initialQuery
        if (!q.isNullOrBlank()) {
            searchQuery = q
            performSearch(q)
            onInitialQueryConsumed()
        }
    }

    // 点击历史标签
    val onHistoryTagClick: (String) -> Unit = { keyword ->
        searchQuery = keyword
        showHistory = false
        searchButtonFocusRequester.requestFocus()
        // 更新本地搜索历史
        updateLocalSearchHistory(keyword)
        coroutineScope.launch {
            // 同步到服务器
            try {
                withContext(Dispatchers.IO) {
                    Repositories.userData().addSearchHistory(keyword)
                }
            } catch (e: Exception) {
                Log.e(TAG, "添加搜索历史失败: ${e.message}")
            }
            delay(300) // 等待动画完成
            rawSearchResults = emptyList()
            allSearchResults = emptyList()
            searchProgress = null
            isSearching = true
            focusedRowIndex = 0
            filterState = SearchFilterState() // 重置筛选状态
            SearchService.startSearch(keyword)
        }
    }
    
    // 卡片聚焦回调
    fun onCardFocus(rowIndex: Int) {
        focusedRowIndex = rowIndex
    }
    
    // 透明筛选器回调
    val onTransparentFocus: (String) -> Unit = { key -> transparentFocusedKey = key }
    val onTransparentBlur: () -> Unit = { transparentFocusedKey = null }
    val onTransparentSelect: (String) -> Unit = { key ->
        dialogKey = key
        dialogVisible = true
    }
    
    // 年份排序切换
    val onYearSortToggle: () -> Unit = {
        filterState = filterState.copy(
            yearSort = when (filterState.yearSort) {
                YearSortState.NONE -> YearSortState.DESCENDING
                YearSortState.DESCENDING -> YearSortState.ASCENDING
                YearSortState.ASCENDING -> YearSortState.NONE
            }
        )
    }
    
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides disabledBringIntoViewSpec
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = gridLayout.horizontalPadding)
                .padding(bottom = bottomPadding)
        ) {
            // 顶部留出 TabSwitcher 的空间
            Spacer(modifier = Modifier.height(84.dp))
            
            // 搜索区域顶部间距动画
            Spacer(modifier = Modifier.height(topSpacing))
            
            // 搜索框和搜索按钮
            SearchInputSection(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { performSearch(searchQuery) },
                onClear = {
                    // 清空搜索结果，回到初始状态
                    SearchService.stopSearch()
                    rawSearchResults = emptyList()
                    allSearchResults = emptyList()
                    searchProgress = null
                    isSearching = false
                    focusedRowIndex = 0
                    filterState = SearchFilterState()
                    showHistory = true
                    searchQuery = ""
                    coroutineScope.launch {
                        scrollState.animateScrollTo(0)
                        searchInputFocusRequester.requestFocus()
                    }
                },
                showClearButton = !showHistory,
                inputFocusRequester = searchInputFocusRequester,
                buttonFocusRequester = searchButtonFocusRequester,
                clearButtonFocusRequester = clearButtonFocusRequester,
                onNavigateDown = { 
                    if (showHistory && searchHistory.isNotEmpty()) {
                        historyFocusRequester.requestFocus() 
                    } else if (allSearchResults.isNotEmpty()) {
                        filterFocusRequester.requestFocus()
                    } else if (rows.isNotEmpty()) {
                        resultsFocusRequester.requestFocus()
                    }
                }
            )
            
            // 搜索历史区域 - 带动画隐藏
            AnimatedVisibility(
                visible = showHistory && searchHistory.isNotEmpty(),
                exit = fadeOut(animationSpec = tween(300)) + shrinkVertically(animationSpec = tween(300))
            ) {
                Column {
                    Spacer(modifier = Modifier.height(32.dp))
                    SearchHistorySection(
                        history = searchHistory,
                        onTagClick = onHistoryTagClick,
                        focusRequester = historyFocusRequester,
                        onNavigateUp = { searchInputFocusRequester.requestFocus() }
                    )
                }
            }
            
            // 搜索结果区域
            if (!showHistory) {
                Spacer(modifier = Modifier.height(24.dp))
                
                // 搜索进度和筛选区域
                if (searchProgress != null || allSearchResults.isNotEmpty()) {
                    SearchFilterSection(
                        progress = searchProgress,
                        filterState = filterState,
                        onTransparentFocus = onTransparentFocus,
                        onTransparentBlur = onTransparentBlur,
                        onTransparentSelect = onTransparentSelect,
                        onYearSortToggle = onYearSortToggle,
                        transparentFocusedKey = transparentFocusedKey,
                        focusRequester = filterFocusRequester,
                        onNavigateUp = { searchButtonFocusRequester.requestFocus() },
                        onNavigateDown = { 
                            if (rows.isNotEmpty()) {
                                resultsFocusRequester.requestFocus()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // 结果网格
                if (rows.isNotEmpty()) {
                    SearchResultsGrid(
                        rows = rows,
                        isRowVisible = ::isRowVisible,
                        cardGap = gridLayout.cardGap,
                        onVerticalNavigate = ::handleVerticalScroll,
                        onCardFocus = ::onCardFocus,
                        onResultClick = onOpenDetail,
                        focusRequester = resultsFocusRequester,
                        onNavigateUp = { filterFocusRequester.requestFocus() }
                    )
                }
            }
        }
        
        // 筛选对话框
        FilterDialog(
            visible = dialogVisible,
            title = dialogTitle,
            options = dialogOptions,
            selectedValue = dialogSelectedValue,
            onSelect = { value ->
                when (dialogKey) {
                    "source" -> filterState = filterState.copy(source = value)
                    "title" -> filterState = filterState.copy(title = value)
                    "year" -> filterState = filterState.copy(year = value)
                }
            },
            onClose = { dialogVisible = false }
        )
    }
}


/**
 * 搜索筛选区域
 */
@Composable
private fun SearchFilterSection(
    progress: SearchProgress?,
    filterState: SearchFilterState,
    onTransparentFocus: (String) -> Unit,
    onTransparentBlur: () -> Unit,
    onTransparentSelect: (String) -> Unit,
    onYearSortToggle: () -> Unit,
    transparentFocusedKey: String?,
    focusRequester: FocusRequester,
    onNavigateUp: () -> Unit,
    onNavigateDown: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 第一行：标题和进度
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "搜索结果",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            if (progress != null) {
                Text(
                    text = "${progress.completedSources}/${progress.totalSources}",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }
        }
        
        // 第二行：筛选器和排序按钮
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 筛选选项
            val transparentOptions = listOf(
                TransparentFilterOption(
                    key = "source",
                    label = "来源",
                    selectedValue = if (filterState.source == "全部") null else filterState.source
                ),
                TransparentFilterOption(
                    key = "title",
                    label = "标题",
                    selectedValue = if (filterState.title == "全部") null else filterState.title
                ),
                TransparentFilterOption(
                    key = "year",
                    label = "年份",
                    selectedValue = if (filterState.year == "全部") null else filterState.year
                )
            )
            
            TransparentFilterSelector(
                options = transparentOptions,
                focusedKey = transparentFocusedKey,
                onFocus = onTransparentFocus,
                onBlur = onTransparentBlur,
                onSelect = onTransparentSelect,
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when (keyEvent.key) {
                                Key.DirectionUp -> {
                                    onNavigateUp()
                                    true
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
            
            // 年份排序按钮
            YearSortButton(
                sortState = filterState.yearSort,
                onClick = onYearSortToggle,
                modifier = Modifier.onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.key) {
                            Key.DirectionUp -> {
                                onNavigateUp()
                                true
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
    }
}

/**
 * 年份排序按钮
 */
@Composable
private fun YearSortButton(
    sortState: YearSortState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 400f
        ),
        label = "year_sort_button_scale"
    )
    
    val backgroundColor = if (isFocused) Color.White else Color.Transparent
    val contentColor = if (isFocused) Color(0xFF0A0A0A) else Color.White
    
    // 显示文本和图标
    val displayText = when (sortState) {
        YearSortState.NONE -> "排序"
        YearSortState.DESCENDING -> "年份"
        YearSortState.ASCENDING -> "年份"
    }
    
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(16.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = Color.White
        )
    ) {
        Row(
            modifier = Modifier
                .height(30.dp)
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayText,
                color = contentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.W600
            )
            Spacer(modifier = Modifier.width(4.dp))
            when (sortState) {
                YearSortState.NONE -> {
                    // 无排序：显示上下箭头
                    Column {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = contentColor.copy(alpha = 0.5f),
                            modifier = Modifier.size(10.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = contentColor.copy(alpha = 0.5f),
                            modifier = Modifier.size(10.dp)
                        )
                    }
                }
                YearSortState.DESCENDING -> {
                    // 降序：显示向下箭头
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "降序",
                        tint = contentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                YearSortState.ASCENDING -> {
                    // 升序：显示向上箭头
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "升序",
                        tint = contentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * 搜索结果网格
 */
@Composable
private fun SearchResultsGrid(
    rows: List<List<AggregatedSearchResult>>,
    isRowVisible: (Int) -> Boolean,
    cardGap: Dp,
    onVerticalNavigate: (Int) -> Unit,
    onCardFocus: (Int) -> Unit,
    onResultClick: (AggregatedSearchResult) -> Unit,
    focusRequester: FocusRequester,
    onNavigateUp: () -> Unit
) {
    Column(
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(SEARCH_ROW_GAP.dp)
    ) {
        rows.forEachIndexed { rowIndex, rowResults ->
            val isVisible = isRowVisible(rowIndex)
            
            SearchGridRow(
                results = rowResults,
                rowIndex = rowIndex,
                cardGap = cardGap,
                isVisible = isVisible,
                totalRows = rows.size,
                onVerticalNavigate = onVerticalNavigate,
                onCardFocus = onCardFocus,
                onResultClick = onResultClick,
                onNavigateUp = if (rowIndex == 0) onNavigateUp else ({})
            )
        }
    }
}

/**
 * 搜索结果网格行
 */
@Composable
private fun SearchGridRow(
    results: List<AggregatedSearchResult>,
    rowIndex: Int,
    cardGap: Dp,
    isVisible: Boolean,
    totalRows: Int,
    onVerticalNavigate: (Int) -> Unit,
    onCardFocus: (Int) -> Unit,
    onResultClick: (AggregatedSearchResult) -> Unit,
    onNavigateUp: () -> Unit = {}
) {
    if (!isVisible && results.isNotEmpty()) {
        Spacer(modifier = Modifier.height(SEARCH_ROW_HEIGHT.dp))
        return
    }
    
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .height(SEARCH_ROW_HEIGHT.dp)
    ) {
        results.forEach { result ->
            VideoCard(
                videoInfo = result.toVideoInfo(),
                from = VideoCardSource.SEARCH,
                onClick = { onResultClick(result) },
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
        
        // 不满一行时用透明占位符填充
        if (results.size < SEARCH_COLUMNS) {
            repeat(SEARCH_COLUMNS - results.size) {
                Spacer(modifier = Modifier.width(SEARCH_CARD_WIDTH.dp))
            }
        }
    }
}

/**
 * 搜索输入区域
 */
@Composable
private fun SearchInputSection(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    showClearButton: Boolean,
    inputFocusRequester: FocusRequester,
    buttonFocusRequester: FocusRequester,
    clearButtonFocusRequester: FocusRequester,
    onNavigateDown: () -> Unit
) {
    // 跟踪清空按钮的焦点状态
    var isClearButtonFocused by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchInputField(
            value = query,
            onValueChange = onQueryChange,
            onClear = onClear,
            showClearButton = showClearButton,
            focusRequester = inputFocusRequester,
            clearButtonFocusRequester = clearButtonFocusRequester,
            onClearButtonFocusChanged = { isClearButtonFocused = it },
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 600.dp)
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.key) {
                            Key.DirectionRight -> {
                                if (showClearButton) {
                                    clearButtonFocusRequester.requestFocus()
                                } else {
                                    buttonFocusRequester.requestFocus()
                                }
                                true
                            }
                            Key.DirectionDown -> {
                                onNavigateDown()
                                true
                            }
                            Key.Enter -> {
                                // 如果清空按钮获得焦点，不处理 Enter 键，让子组件处理
                                if (isClearButtonFocused) {
                                    false
                                } else {
                                    onSearch()
                                    true
                                }
                            }
                            else -> false
                        }
                    } else false
                }
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        SearchButton(
            onClick = onSearch,
            focusRequester = buttonFocusRequester,
            modifier = Modifier.onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.DirectionLeft -> {
                            if (showClearButton) {
                                clearButtonFocusRequester.requestFocus()
                            } else {
                                inputFocusRequester.requestFocus()
                            }
                            true
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
}

/**
 * 搜索输入框
 */
@Composable
private fun SearchInputField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    showClearButton: Boolean,
    focusRequester: FocusRequester,
    clearButtonFocusRequester: FocusRequester,
    onClearButtonFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    
    Box(
        modifier = modifier
            .height(42.dp)
            .background(
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(21.dp)
            )
            .then(
                if (isFocused) {
                    Modifier.background(
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(21.dp)
                    )
                } else Modifier
            )
            .padding(start = 18.dp, end = if (showClearButton) 8.dp else 18.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        isFocused = focusState.isFocused
                    },
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(Color.White),
                singleLine = true,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = "输入关键词搜索",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 16.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
            
            // 清空按钮 - X 图标
            if (showClearButton) {
                var clearFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = { onClear() },
                    modifier = Modifier
                        .focusRequester(clearButtonFocusRequester)
                        .onFocusChanged { 
                            clearFocused = it.isFocused
                            onClearButtonFocusChanged(it.isFocused)
                        }
                        .size(28.dp)
                        .onPreviewKeyEvent { keyEvent ->
                            // 阻止 Enter 键冒泡到父级触发搜索
                            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter) {
                                onClear()
                                true
                            } else false
                        },
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(14.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = Color.White.copy(alpha = 0.2f)
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "清空",
                            tint = if (clearFocused) Color.White else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 搜索按钮
 */
@Composable
private fun SearchButton(
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 400f
        ),
        label = "search_button_scale"
    )
    
    val backgroundColor = if (isFocused) Color.White else Color.White.copy(alpha = 0.2f)
    val textColor = if (isFocused) Color(0xFF0A0A0A) else Color.White
    
    Surface(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(21.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = Color.White
        )
    ) {
        Box(
            modifier = Modifier
                .height(42.dp)
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "搜索",
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.W600
            )
        }
    }
}


/**
 * 搜索历史区域
 */
@Composable
private fun SearchHistorySection(
    history: List<String>,
    onTagClick: (String) -> Unit,
    focusRequester: FocusRequester,
    onNavigateUp: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp)
    ) {
        Text(
            text = "搜索历史",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        FlowLayout(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .focusGroup(),
            horizontalSpacing = 12.dp,
            verticalSpacing = 12.dp
        ) {
            history.forEach { keyword ->
                HistoryTag(
                    keyword = keyword,
                    onClick = { onTagClick(keyword) },
                    modifier = Modifier.onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionUp) {
                            onNavigateUp()
                            true
                        } else false
                    }
                )
            }
        }
    }
}

/**
 * 历史标签
 */
@Composable
private fun HistoryTag(
    keyword: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = 100f
        ),
        label = "history_tag_scale"
    )
    
    val backgroundColor = if (isFocused) Color.White else Color.White.copy(alpha = 0.08f)
    val textColor = if (isFocused) Color(0xFF0A0A0A) else Color(0xFFCCCCCC)
    
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(16.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = Color.White
        )
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = keyword,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 流式布局 (FlowLayout)
 */
@Composable
private fun FlowLayout(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 0.dp,
    verticalSpacing: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    androidx.compose.ui.layout.Layout(
        content = content,
        modifier = modifier
    ) { measurables, constraints ->
        val horizontalSpacingPx = horizontalSpacing.roundToPx()
        val verticalSpacingPx = verticalSpacing.roundToPx()
        
        val placeables = measurables.map { measurable ->
            measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        }
        
        var currentX = 0
        var currentY = 0
        var rowHeight = 0
        
        val positions = mutableListOf<Pair<Int, Int>>()
        
        placeables.forEach { placeable ->
            if (currentX + placeable.width > constraints.maxWidth && currentX > 0) {
                currentX = 0
                currentY += rowHeight + verticalSpacingPx
                rowHeight = 0
            }
            
            positions.add(currentX to currentY)
            currentX += placeable.width + horizontalSpacingPx
            rowHeight = maxOf(rowHeight, placeable.height)
        }
        
        val totalHeight = if (placeables.isEmpty()) 0 else currentY + rowHeight
        
        layout(constraints.maxWidth, totalHeight) {
            placeables.forEachIndexed { index, placeable ->
                val (x, y) = positions[index]
                placeable.placeRelative(x, y)
            }
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun SearchTabPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        SearchTab()
    }
}
