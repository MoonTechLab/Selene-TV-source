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

private const val TAG = "MovieTab"

// ==================== 筛选选项数据 ====================

// 分类选项
val CATEGORY_OPTIONS = listOf(
    FilterOption("all", "全部"),
    FilterOption("popular", "热门电影"),
    FilterOption("latest", "最新电影"),
    FilterOption("douban", "豆瓣高分"),
    FilterOption("hidden", "冷门佳片")
)

// 地区选项 (非"全部"分类时使用)
val REGION_OPTIONS = listOf(
    FilterOption("all", "全部"),
    FilterOption("chinese", "华语"),
    FilterOption("western", "欧美"),
    FilterOption("korean", "韩国"),
    FilterOption("japanese", "日本")
)

// "全部"分类的筛选对话框选项数据

val MOVIE_TYPE_OPTIONS = listOf(
    SelectorOption("all", "全部"),
    SelectorOption("comedy", "喜剧"),
    SelectorOption("romance", "爱情"),
    SelectorOption("action", "动作"),
    SelectorOption("sci-fi", "科幻"),
    SelectorOption("suspense", "悬疑"),
    SelectorOption("crime", "犯罪"),
    SelectorOption("thriller", "惊悚"),
    SelectorOption("adventure", "冒险"),
    SelectorOption("music", "音乐"),
    SelectorOption("history", "历史"),
    SelectorOption("fantasy", "奇幻"),
    SelectorOption("horror", "恐怖"),
    SelectorOption("war", "战争"),
    SelectorOption("biography", "传记"),
    SelectorOption("musical", "歌舞"),
    SelectorOption("wuxia", "武侠"),
    SelectorOption("erotic", "情色"),
    SelectorOption("disaster", "灾难"),
    SelectorOption("western", "西部"),
    SelectorOption("documentary", "纪录片"),
    SelectorOption("short", "短片")
)

val MOVIE_REGION_OPTIONS = listOf(
    SelectorOption("all", "全部"),
    SelectorOption("chinese", "华语"),
    SelectorOption("western", "欧美"),
    SelectorOption("korean", "韩国"),
    SelectorOption("japanese", "日本"),
    SelectorOption("mainland_china", "中国大陆"),
    SelectorOption("usa", "美国"),
    SelectorOption("hong_kong", "中国香港"),
    SelectorOption("taiwan", "中国台湾"),
    SelectorOption("uk", "英国"),
    SelectorOption("france", "法国"),
    SelectorOption("germany", "德国"),
    SelectorOption("italy", "意大利"),
    SelectorOption("spain", "西班牙"),
    SelectorOption("india", "印度"),
    SelectorOption("thailand", "泰国"),
    SelectorOption("russia", "俄罗斯"),
    SelectorOption("canada", "加拿大"),
    SelectorOption("australia", "澳大利亚"),
    SelectorOption("ireland", "爱尔兰"),
    SelectorOption("sweden", "瑞典"),
    SelectorOption("brazil", "巴西"),
    SelectorOption("denmark", "丹麦")
)

val MOVIE_YEAR_OPTIONS = listOf(
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

val MOVIE_SORT_OPTIONS = listOf(
    SelectorOption("T", "综合排序"),
    SelectorOption("U", "近期热度"),
    SelectorOption("R", "首映时间"),
    SelectorOption("S", "高分优先")
)

// ==================== 布局常量 ====================

private const val COLUMNS = GRID_COLUMNS            // 每行6列
private const val PAGE_LIMIT = GRID_PAGE_LIMIT      // 每页数据量
private const val HORIZONTAL_PADDING = 58           // 固定左右边距 58dp

// ==================== 布局计算 ====================

data class GridLayout(
    val horizontalPadding: Dp,
    val cardGap: Dp
)

/**
 * 动态计算网格布局参数
 * 保持固定左右 padding，卡片间距根据剩余空间均匀分配
 */
fun calculateGridLayout(screenWidthDp: Float): GridLayout {
    val totalCardWidth = GRID_CARD_WIDTH * COLUMNS  // 720dp
    val totalPadding = HORIZONTAL_PADDING * 2  // 116dp

    // 可用于间距的空间 = 屏幕宽度 - 卡片总宽度 - 左右边距
    val availableForGaps = screenWidthDp - totalCardWidth - totalPadding

    // 卡片间距 = 剩余空间 / 间隙数量 (6列有5个间隙)
    val cardGap = availableForGaps / (COLUMNS - 1)

    return GridLayout(
        horizontalPadding = HORIZONTAL_PADDING.dp,
        cardGap = cardGap.dp
    )
}

// ==================== 筛选状态 ====================

/**
 * 电影筛选状态
 */
data class MovieFilterState(
    val category: String = "popular",      // 默认: 热门电影
    val region: String = "all",            // 地区 (非"全部"分类时)
    val movieType: String = "all",         // 类型 ("全部"分类时)
    val movieRegion: String = "all",       // 地区 ("全部"分类时)
    val movieYear: String = "all",         // 年代 ("全部"分类时)
    val movieSort: String = "T"            // 排序 ("全部"分类时)
)

/**
 * 电影Tab数据状态
 */
data class MovieTabState(
    val movies: List<DoubanMovie> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val page: Int = 1,
    val hasMore: Boolean = true
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MovieTab(
    categoryFocusRequester: FocusRequester = remember { FocusRequester() },
    scrollState: ScrollState = rememberScrollState(),
    onVideoSelected: (VideoInfo) -> Unit = {}
) {
    val context = LocalContext.current
    val doubanService = remember { DoubanService.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    // 筛选状态
    var filterState by remember { mutableStateOf(MovieFilterState()) }

    // 数据状态
    var dataState by remember { mutableStateOf(MovieTabState()) }

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
    val gridLayout = remember(screenWidthDp) { calculateGridLayout(screenWidthDp) }

    // 加载数据函数
    fun loadData(isInitial: Boolean = false) {
        if (dataState.isLoading) return
        if (!isInitial && !dataState.hasMore) return

        coroutineScope.launch {
            dataState = dataState.copy(isLoading = true, errorMessage = null)

            try {
                val result = if (filterState.category == "all") {
                    // "全部"分类使用 fetchDoubanRecommends API
                    if (isInitial) {
                        // 首次加载并行请求两页数据
                        loadInitialRecommendsData(doubanService, filterState)
                    } else {
                        loadRecommendsData(doubanService, filterState, dataState.page)
                    }
                } else {
                    // 其他分类使用 getCategoryData API
                    loadCategoryData(doubanService, filterState, if (isInitial) 0 else dataState.page)
                }
                
                when (result) {
                    is ApiResponse.Success -> {
                        val newMovies = result.data
                        val hasMore = newMovies.size >= PAGE_LIMIT
                        
                        dataState = if (isInitial) {
                            dataState.copy(
                                movies = newMovies,
                                isLoading = false,
                                page = if (filterState.category == "all") 2 else 1,
                                hasMore = hasMore
                            )
                        } else {
                            dataState.copy(
                                movies = dataState.movies + newMovies,
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
        dataState = MovieTabState() // 重置数据状态
        loadData(isInitial = true)
    }

    // 根据 dialogKey 获取对应的选项列表、标题和当前选中值
    val dialogOptions = when (dialogKey) {
        "type" -> MOVIE_TYPE_OPTIONS
        "region" -> MOVIE_REGION_OPTIONS
        "year" -> MOVIE_YEAR_OPTIONS
        "sort" -> MOVIE_SORT_OPTIONS
        else -> emptyList()
    }
    
    val dialogTitle = when (dialogKey) {
        "type" -> "选择类型"
        "region" -> "选择地区"
        "year" -> "选择年代"
        "sort" -> "选择排序"
        else -> ""
    }
    
    val dialogSelectedValue = when (dialogKey) {
        "type" -> filterState.movieType
        "region" -> filterState.movieRegion
        "year" -> filterState.movieYear
        "sort" -> filterState.movieSort
        else -> ""
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MediaGridScaffold(
            items = dataState.movies,
            isLoading = dataState.isLoading,
            errorMessage = dataState.errorMessage,
            scrollState = scrollState,
            horizontalPadding = gridLayout.horizontalPadding,
            cardGap = gridLayout.cardGap,
            contentFocusRequester = contentFocusRequester,
            resetKey = filterState,
            itemToVideoInfo = { it.toVideoInfo() },
            itemToSource = { VideoCardSource.DOUBAN },
            onItemClick = { movie -> onVideoSelected(movie.toVideoInfo()) },
            onRetry = { loadData(isInitial = true) },
            onLoadMore = { loadData() },
            onContentNavigateUp = { regionFocusRequester.requestFocus() },
            filterSection = {
                FilterSection(
                    filterState = filterState,
                    onFilterStateChange = { newState ->
                        filterState = newState
                    },
                    transparentFocusedKey = transparentFocusedKey,
                    onTransparentFocus = { transparentFocusedKey = it },
                    onTransparentBlur = { transparentFocusedKey = null },
                    onTransparentSelect = { key ->
                        dialogKey = key
                        dialogVisible = true
                    },
                    categoryFocusRequester = categoryFocusRequester,
                    regionFocusRequester = regionFocusRequester,
                    onNavigateDown = {
                        contentFocusRequester.requestFocus()
                    }
                )
            }
        )

        // 筛选对话框
        FilterDialog(
            visible = dialogVisible,
            title = dialogTitle,
            options = dialogOptions,
            selectedValue = dialogSelectedValue,
            onSelect = { value ->
                when (dialogKey) {
                    "type" -> filterState = filterState.copy(movieType = value)
                    "region" -> filterState = filterState.copy(movieRegion = value)
                    "year" -> filterState = filterState.copy(movieYear = value)
                    "sort" -> filterState = filterState.copy(movieSort = value)
                }
            },
            onClose = { dialogVisible = false }
        )
    }
}

/**
 * 加载分类数据 (热门/最新/豆瓣高分/冷门佳片)
 */
private suspend fun loadCategoryData(
    service: DoubanService,
    filterState: MovieFilterState,
    page: Int
): ApiResponse<List<DoubanMovie>> {
    val categoryValueMap = mapOf(
        "popular" to "热门",
        "latest" to "最新",
        "douban" to "豆瓣高分",
        "hidden" to "冷门佳片"
    )
    
    val regionValueMap = mapOf(
        "all" to "全部",
        "chinese" to "华语",
        "western" to "欧美",
        "korean" to "韩国",
        "japanese" to "日本"
    )
    
    return service.getCategoryData(
        kind = DoubanKind.MOVIE,
        category = categoryValueMap[filterState.category] ?: "热门",
        type = regionValueMap[filterState.region] ?: "全部",
        pageLimit = PAGE_LIMIT,
        page = page
    )
}

/**
 * 加载推荐数据 ("全部"分类)
 */
private suspend fun loadRecommendsData(
    service: DoubanService,
    filterState: MovieFilterState,
    page: Int
): ApiResponse<List<DoubanMovie>> {
    val typeLabel = MOVIE_TYPE_OPTIONS.find { it.value == filterState.movieType }?.label
    val regionLabel = MOVIE_REGION_OPTIONS.find { it.value == filterState.movieRegion }?.label
    val yearLabel = MOVIE_YEAR_OPTIONS.find { it.value == filterState.movieYear }?.label
    
    return service.fetchDoubanRecommends(
        DoubanRecommendsParams(
            kind = "movie",
            category = if (filterState.movieType == "all") "all" else typeLabel ?: "",
            region = if (filterState.movieRegion == "all") "all" else regionLabel ?: "",
            year = if (filterState.movieYear == "all") "all" else yearLabel ?: "",
            sort = filterState.movieSort,
            pageLimit = PAGE_LIMIT,
            page = page
        )
    )
}

/**
 * 首次加载推荐数据 (并行请求两页)
 */
private suspend fun loadInitialRecommendsData(
    service: DoubanService,
    filterState: MovieFilterState
): ApiResponse<List<DoubanMovie>> = coroutineScope {
    val deferred1 = async { loadRecommendsData(service, filterState, 0) }
    val deferred2 = async { loadRecommendsData(service, filterState, 1) }
    
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
private fun FilterSection(
    filterState: MovieFilterState,
    onFilterStateChange: (MovieFilterState) -> Unit,
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
                    options = CATEGORY_OPTIONS,
                    selectedKey = filterState.category,
                    onSelect = { key ->
                        if (key == "all") {
                            onFilterStateChange(
                                filterState.copy(
                                    category = key,
                                    movieType = "all",
                                    movieRegion = "all",
                                    movieYear = "all",
                                    movieSort = "T"
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
                            selectedValue = MOVIE_TYPE_OPTIONS.find { it.value == filterState.movieType }?.label
                        ),
                        TransparentFilterOption(
                            key = "region",
                            label = "地区",
                            selectedValue = MOVIE_REGION_OPTIONS.find { it.value == filterState.movieRegion }?.label
                        ),
                        TransparentFilterOption(
                            key = "year",
                            label = "年代",
                            selectedValue = MOVIE_YEAR_OPTIONS.find { it.value == filterState.movieYear }?.label
                        ),
                        TransparentFilterOption(
                            key = "sort",
                            label = "排序",
                            selectedValue = MOVIE_SORT_OPTIONS.find { it.value == filterState.movieSort }?.label
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
                        options = REGION_OPTIONS,
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
fun MovieTabPreview() {
    MovieTab()
}
