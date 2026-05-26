package org.moontechlab.selenetv.screen.tab

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.moontechlab.selenetv.model.DoubanKind
import org.moontechlab.selenetv.model.DoubanMovie
import org.moontechlab.selenetv.model.DoubanRecommendsParams
import org.moontechlab.selenetv.model.VideoInfo
import org.moontechlab.selenetv.model.toVideoInfo
import org.moontechlab.selenetv.service.ApiResponse
import org.moontechlab.selenetv.service.DoubanService
import org.moontechlab.selenetv.ui.FilterDialog
import org.moontechlab.selenetv.ui.FilterOption
import org.moontechlab.selenetv.ui.FilterSelector
import org.moontechlab.selenetv.ui.SelectorOption
import org.moontechlab.selenetv.ui.TransparentFilterOption
import org.moontechlab.selenetv.ui.TransparentFilterSelector
import org.moontechlab.selenetv.ui.VideoCardSource
import org.moontechlab.selenetv.ui.grid.GRID_CARD_WIDTH
import org.moontechlab.selenetv.ui.grid.GRID_COLUMNS
import org.moontechlab.selenetv.ui.grid.GRID_PAGE_LIMIT
import org.moontechlab.selenetv.ui.grid.GridFilterRow
import org.moontechlab.selenetv.ui.grid.MediaGridScaffold

private const val TAG = "ShowTab"

// ==================== 筛选选项数据 ====================

// 分类选项
val SHOW_CATEGORY_OPTIONS = listOf(
    FilterOption("all", "全部"),
    FilterOption("recent", "最近热门")
)

// 地区选项 (非"全部"分类时使用)
val SHOW_REGION_OPTIONS = listOf(
    FilterOption("all", "全部"),
    FilterOption("domestic", "国内"),
    FilterOption("foreign", "国外")
)

// "全部"分类的筛选对话框选项数据

// 类型选项
val SHOW_FILTER_TYPE_OPTIONS = listOf(
    SelectorOption("all", "全部"),
    SelectorOption("reality", "真人秀"),
    SelectorOption("talkshow", "脱口秀"),
    SelectorOption("music", "音乐"),
    SelectorOption("musical", "歌舞")
)

// 地区选项
val SHOW_FILTER_REGION_OPTIONS = listOf(
    SelectorOption("all", "全部"),
    SelectorOption("chinese", "华语"),
    SelectorOption("western", "欧美"),
    SelectorOption("foreign", "国外"),
    SelectorOption("korean", "韩国"),
    SelectorOption("japanese", "日本"),
    SelectorOption("mainland_china", "中国大陆"),
    SelectorOption("hong_kong", "中国香港"),
    SelectorOption("usa", "美国"),
    SelectorOption("uk", "英国"),
    SelectorOption("thailand", "泰国"),
    SelectorOption("taiwan", "中国台湾"),
    SelectorOption("italy", "意大利"),
    SelectorOption("france", "法国"),
    SelectorOption("germany", "德国"),
    SelectorOption("spain", "西班牙"),
    SelectorOption("russia", "俄罗斯"),
    SelectorOption("sweden", "瑞典"),
    SelectorOption("brazil", "巴西"),
    SelectorOption("denmark", "丹麦"),
    SelectorOption("india", "印度"),
    SelectorOption("canada", "加拿大"),
    SelectorOption("ireland", "爱尔兰"),
    SelectorOption("australia", "澳大利亚")
)

// 年代选项
val SHOW_FILTER_YEAR_OPTIONS = listOf(
    SelectorOption("all", "全部"),
    SelectorOption("2020s", "2020年代"),
    SelectorOption("2026", "2026"),
    SelectorOption("2025", "2025"),
    SelectorOption("2024", "2024"),
    SelectorOption("2023", "2023"),
    SelectorOption("2022", "2022"),
    SelectorOption("2021", "2021"),
    SelectorOption("2020", "2020"),
    SelectorOption("2019", "2019"),
    SelectorOption("2010s", "2010年代"),
    SelectorOption("2000s", "2000年代"),
    SelectorOption("1990s", "90年代"),
    SelectorOption("1980s", "80年代"),
    SelectorOption("1970s", "70年代"),
    SelectorOption("1960s", "60年代"),
    SelectorOption("earlier", "更早")
)

// 平台选项
val SHOW_FILTER_PLATFORM_OPTIONS = listOf(
    SelectorOption("all", "全部"),
    SelectorOption("tencent", "腾讯视频"),
    SelectorOption("iqiyi", "爱奇艺"),
    SelectorOption("youku", "优酷"),
    SelectorOption("hunan_tv", "湖南卫视"),
    SelectorOption("netflix", "Netflix"),
    SelectorOption("hbo", "HBO"),
    SelectorOption("bbc", "BBC"),
    SelectorOption("nhk", "NHK"),
    SelectorOption("cbs", "CBS"),
    SelectorOption("nbc", "NBC"),
    SelectorOption("tvn", "tvN")
)

// 排序选项
val SHOW_FILTER_SORT_OPTIONS = listOf(
    SelectorOption("T", "综合排序"),
    SelectorOption("U", "近期热度"),
    SelectorOption("R", "首播时间"),
    SelectorOption("S", "高分优先")
)

// ==================== 布局常量 ====================

private const val SHOW_COLUMNS = GRID_COLUMNS          // 每行6列
private const val SHOW_PAGE_LIMIT = GRID_PAGE_LIMIT    // 每页数据量

// ==================== 布局计算 ====================

data class ShowGridLayout(
    val horizontalPadding: Dp,
    val cardGap: Dp
)

/**
 * 动态计算网格布局参数
 */
fun calculateShowGridLayout(screenWidthDp: Float): ShowGridLayout {
    val totalCardWidth = GRID_CARD_WIDTH * SHOW_COLUMNS  // 720dp
    val availableSpace = screenWidthDp - totalCardWidth

    // 左右边距: 剩余空间的 15%, 最小 20dp
    val horizontalPadding = maxOf(20f, availableSpace * 0.15f)

    // 卡片间距: 剩余空间的 70% 分给 5 个间隙, 最小 28dp
    val cardGap = maxOf(28f, (availableSpace * 0.7f) / (SHOW_COLUMNS - 1))

    return ShowGridLayout(
        horizontalPadding = horizontalPadding.dp,
        cardGap = cardGap.dp
    )
}

// ==================== 筛选状态 ====================

/**
 * 综艺筛选状态
 */
data class ShowFilterState(
    val category: String = "recent",       // 默认: 最近热门
    val region: String = "all",            // 地区 (非"全部"分类时)
    val showType: String = "all",          // 类型 ("全部"分类时)
    val showRegion: String = "all",        // 地区 ("全部"分类时)
    val showYear: String = "all",          // 年代 ("全部"分类时)
    val showPlatform: String = "all",      // 平台 ("全部"分类时)
    val showSort: String = "T"             // 排序 ("全部"分类时)
)

/**
 * 综艺Tab数据状态
 */
data class ShowTabState(
    val shows: List<DoubanMovie> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val page: Int = 1,
    val hasMore: Boolean = true
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShowTab(
    categoryFocusRequester: FocusRequester = remember { FocusRequester() },
    scrollState: ScrollState = rememberScrollState(),
    onVideoSelected: (VideoInfo) -> Unit = {}
) {
    val context = LocalContext.current
    val doubanService = remember { DoubanService.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    // 筛选状态
    var filterState by remember { mutableStateOf(ShowFilterState()) }

    // 数据状态
    var dataState by remember { mutableStateOf(ShowTabState()) }

    // 透明筛选器聚焦状态
    var transparentFocusedKey by remember { mutableStateOf<String?>(null) }

    // FilterDialog 状态
    var dialogVisible by remember { mutableStateOf(false) }
    var dialogKey by remember { mutableStateOf<String?>(null) }

    // 焦点管理器
    val regionFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }

    // 计算布局参数
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.toFloat()
    val gridLayout = remember(screenWidthDp) { calculateShowGridLayout(screenWidthDp) }

    // 加载数据函数
    fun loadData(isInitial: Boolean = false) {
        if (dataState.isLoading) return
        if (!isInitial && !dataState.hasMore) return
        
        coroutineScope.launch {
            dataState = dataState.copy(isLoading = true, errorMessage = null)
            
            try {
                val result = if (filterState.category == "all") {
                    if (isInitial) {
                        loadInitialShowRecommendsData(doubanService, filterState)
                    } else {
                        loadShowRecommendsData(doubanService, filterState, dataState.page)
                    }
                } else {
                    loadShowCategoryData(doubanService, filterState, if (isInitial) 0 else dataState.page)
                }

                when (result) {
                    is ApiResponse.Success -> {
                        val newShows = result.data
                        val hasMore = newShows.size >= SHOW_PAGE_LIMIT
                        
                        dataState = if (isInitial) {
                            dataState.copy(
                                shows = newShows,
                                isLoading = false,
                                page = if (filterState.category == "all") 2 else 1,
                                hasMore = hasMore
                            )
                        } else {
                            dataState.copy(
                                shows = dataState.shows + newShows,
                                isLoading = false,
                                page = dataState.page + 1,
                                hasMore = hasMore
                            )
                        }
                    }
                    is ApiResponse.Error -> {
                        dataState = dataState.copy(
                            isLoading = false,
                            errorMessage = result.message
                        )
                        Log.e(TAG, "加载数据失败: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                dataState = dataState.copy(
                    isLoading = false,
                    errorMessage = e.message
                )
                Log.e(TAG, "加载数据异常", e)
            }
        }
    }
    
    // 筛选条件变化时重新加载
    LaunchedEffect(filterState) {
        dataState = ShowTabState()
        loadData(isInitial = true)
    }

    // 根据 dialogKey 获取对应的选项列表、标题和当前选中值
    val dialogOptions = when (dialogKey) {
        "type" -> SHOW_FILTER_TYPE_OPTIONS
        "region" -> SHOW_FILTER_REGION_OPTIONS
        "year" -> SHOW_FILTER_YEAR_OPTIONS
        "platform" -> SHOW_FILTER_PLATFORM_OPTIONS
        "sort" -> SHOW_FILTER_SORT_OPTIONS
        else -> emptyList()
    }
    
    val dialogTitle = when (dialogKey) {
        "type" -> "选择类型"
        "region" -> "选择地区"
        "year" -> "选择年代"
        "platform" -> "选择平台"
        "sort" -> "选择排序"
        else -> ""
    }
    
    val dialogSelectedValue = when (dialogKey) {
        "type" -> filterState.showType
        "region" -> filterState.showRegion
        "year" -> filterState.showYear
        "platform" -> filterState.showPlatform
        "sort" -> filterState.showSort
        else -> ""
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MediaGridScaffold(
            items = dataState.shows,
            isLoading = dataState.isLoading,
            errorMessage = dataState.errorMessage,
            scrollState = scrollState,
            horizontalPadding = gridLayout.horizontalPadding,
            cardGap = gridLayout.cardGap,
            contentFocusRequester = contentFocusRequester,
            resetKey = filterState,
            itemToVideoInfo = { it.toVideoInfo() },
            itemToSource = { VideoCardSource.DOUBAN },
            onItemClick = { show -> onVideoSelected(show.toVideoInfo()) },
            onRetry = { loadData(isInitial = true) },
            onLoadMore = { loadData() },
            onContentNavigateUp = { regionFocusRequester.requestFocus() },
            filterSection = {
                ShowFilterSection(
                    filterState = filterState,
                    onFilterStateChange = { newState -> filterState = newState },
                    transparentFocusedKey = transparentFocusedKey,
                    onTransparentFocus = { transparentFocusedKey = it },
                    onTransparentBlur = { transparentFocusedKey = null },
                    onTransparentSelect = { key ->
                        dialogKey = key
                        dialogVisible = true
                    },
                    categoryFocusRequester = categoryFocusRequester,
                    regionFocusRequester = regionFocusRequester,
                    onNavigateDown = { contentFocusRequester.requestFocus() }
                )
            }
        )

        FilterDialog(
            visible = dialogVisible,
            title = dialogTitle,
            options = dialogOptions,
            selectedValue = dialogSelectedValue,
            onSelect = { value ->
                when (dialogKey) {
                    "type" -> filterState = filterState.copy(showType = value)
                    "region" -> filterState = filterState.copy(showRegion = value)
                    "year" -> filterState = filterState.copy(showYear = value)
                    "platform" -> filterState = filterState.copy(showPlatform = value)
                    "sort" -> filterState = filterState.copy(showSort = value)
                }
            },
            onClose = { dialogVisible = false }
        )
    }
}

/**
 * 加载分类数据 (最近热门)
 */
private suspend fun loadShowCategoryData(
    service: DoubanService,
    filterState: ShowFilterState,
    page: Int
): ApiResponse<List<DoubanMovie>> {
    val regionValueMap = mapOf(
        "all" to "show",
        "domestic" to "show_domestic",
        "foreign" to "show_foreign"
    )
    
    return service.getCategoryData(
        kind = DoubanKind.TV,
        category = "最近热门",
        type = regionValueMap[filterState.region] ?: "show",
        pageLimit = SHOW_PAGE_LIMIT,
        page = page
    )
}

/**
 * 加载推荐数据 ("全部"分类)
 */
private suspend fun loadShowRecommendsData(
    service: DoubanService,
    filterState: ShowFilterState,
    page: Int
): ApiResponse<List<DoubanMovie>> {
    val typeLabel = SHOW_FILTER_TYPE_OPTIONS.find { it.value == filterState.showType }?.label
    val regionLabel = SHOW_FILTER_REGION_OPTIONS.find { it.value == filterState.showRegion }?.label
    val yearLabel = SHOW_FILTER_YEAR_OPTIONS.find { it.value == filterState.showYear }?.label
    val platformLabel = SHOW_FILTER_PLATFORM_OPTIONS.find { it.value == filterState.showPlatform }?.label
    
    return service.fetchDoubanRecommends(
        DoubanRecommendsParams(
            kind = "tv",
            format = "综艺",
            category = if (filterState.showType == "all") "all" else typeLabel ?: "",
            region = if (filterState.showRegion == "all") "all" else regionLabel ?: "",
            year = if (filterState.showYear == "all") "all" else yearLabel ?: "",
            platform = if (filterState.showPlatform == "all") "all" else platformLabel ?: "",
            sort = filterState.showSort,
            pageLimit = SHOW_PAGE_LIMIT,
            page = page
        )
    )
}

/**
 * 首次加载推荐数据 (并行请求两页)
 */
private suspend fun loadInitialShowRecommendsData(
    service: DoubanService,
    filterState: ShowFilterState
): ApiResponse<List<DoubanMovie>> = coroutineScope {
    val deferred1 = async { loadShowRecommendsData(service, filterState, 0) }
    val deferred2 = async { loadShowRecommendsData(service, filterState, 1) }
    
    val result1 = deferred1.await()
    val result2 = deferred2.await()
    
    when {
        result1 is ApiResponse.Success && result2 is ApiResponse.Success -> {
            ApiResponse.Success(result1.data + result2.data)
        }
        result1 is ApiResponse.Success -> result1
        result2 is ApiResponse.Success -> result2
        result1 is ApiResponse.Error -> result1
        else -> ApiResponse.Error("加载失败")
    }
}

/**
 * 筛选区域
 */
@Composable
private fun ShowFilterSection(
    filterState: ShowFilterState,
    onFilterStateChange: (ShowFilterState) -> Unit,
    transparentFocusedKey: String?,
    onTransparentFocus: (String) -> Unit,
    onTransparentBlur: () -> Unit,
    onTransparentSelect: (String) -> Unit,
    categoryFocusRequester: FocusRequester,
    regionFocusRequester: FocusRequester,
    onNavigateDown: () -> Unit
) {
    val isAllCategory = filterState.category == "all"
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 11.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 分类行
        GridFilterRow(
            label = "分类",
            content = {
                FilterSelector(
                    options = SHOW_CATEGORY_OPTIONS,
                    selectedKey = filterState.category,
                    onSelect = { key ->
                        if (key == "all") {
                            onFilterStateChange(
                                filterState.copy(
                                    category = key,
                                    showType = "all",
                                    showRegion = "all",
                                    showYear = "all",
                                    showPlatform = "all",
                                    showSort = "T"
                                )
                            )
                        } else {
                            onFilterStateChange(filterState.copy(category = key))
                        }
                    },
                    focusRequester = categoryFocusRequester,
                    modifier = Modifier.onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionDown) {
                            regionFocusRequester.requestFocus()
                            true
                        } else false
                    }
                )
            }
        )

        // 第二行 (根据分类选择动态切换)
        if (isAllCategory) {
            GridFilterRow(
                label = "筛选",
                content = {
                    val transparentOptions = listOf(
                        TransparentFilterOption(
                            key = "type",
                            label = "类型",
                            selectedValue = SHOW_FILTER_TYPE_OPTIONS.find { it.value == filterState.showType }?.label
                        ),
                        TransparentFilterOption(
                            key = "region",
                            label = "地区",
                            selectedValue = SHOW_FILTER_REGION_OPTIONS.find { it.value == filterState.showRegion }?.label
                        ),
                        TransparentFilterOption(
                            key = "year",
                            label = "年代",
                            selectedValue = SHOW_FILTER_YEAR_OPTIONS.find { it.value == filterState.showYear }?.label
                        ),
                        TransparentFilterOption(
                            key = "platform",
                            label = "平台",
                            selectedValue = SHOW_FILTER_PLATFORM_OPTIONS.find { it.value == filterState.showPlatform }?.label
                        ),
                        TransparentFilterOption(
                            key = "sort",
                            label = "排序",
                            selectedValue = SHOW_FILTER_SORT_OPTIONS.find { it.value == filterState.showSort }?.label
                        )
                    )
                    
                    TransparentFilterSelector(
                        options = transparentOptions,
                        focusedKey = transparentFocusedKey,
                        onFocus = onTransparentFocus,
                        onBlur = onTransparentBlur,
                        onSelect = onTransparentSelect,
                        modifier = Modifier
                            .focusRequester(regionFocusRequester)
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.key) {
                                        Key.DirectionUp -> {
                                            categoryFocusRequester.requestFocus()
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
            )

        } else {
            GridFilterRow(
                label = "地区",
                content = {
                    FilterSelector(
                        options = SHOW_REGION_OPTIONS,
                        selectedKey = filterState.region,
                        onSelect = { key ->
                            onFilterStateChange(filterState.copy(region = key))
                        },
                        focusRequester = regionFocusRequester,
                        modifier = Modifier.onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.key) {
                                    Key.DirectionUp -> {
                                        categoryFocusRequester.requestFocus()
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
            )
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun ShowTabPreview() {
    ShowTab()
}
