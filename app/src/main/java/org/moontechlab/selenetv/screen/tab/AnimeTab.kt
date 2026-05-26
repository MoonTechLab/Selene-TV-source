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
import org.moontechlab.selenetv.model.BangumiItem
import org.moontechlab.selenetv.model.DoubanMovie
import org.moontechlab.selenetv.model.DoubanRecommendsParams
import org.moontechlab.selenetv.model.VideoInfo
import org.moontechlab.selenetv.model.toVideoInfo
import org.moontechlab.selenetv.service.ApiResponse
import org.moontechlab.selenetv.service.BangumiService
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
import java.util.Calendar

private const val TAG = "AnimeTab"

// ==================== 筛选选项数据 ====================

// 分类选项
val ANIME_CATEGORY_OPTIONS = listOf(
    FilterOption("new", "每日放送"),
    FilterOption("series", "番剧"),
    FilterOption("movie", "剧场版")
)

// 星期选项 (每日放送)
val WEEKDAY_OPTIONS = listOf(
    FilterOption("1", "周一"),
    FilterOption("2", "周二"),
    FilterOption("3", "周三"),
    FilterOption("4", "周四"),
    FilterOption("5", "周五"),
    FilterOption("6", "周六"),
    FilterOption("7", "周日")
)

// 番剧筛选选项
val SERIES_FILTER_OPTIONS = listOf(
    TransparentFilterOption("type", "类型"),
    TransparentFilterOption("region", "地区"),
    TransparentFilterOption("year", "年代"),
    TransparentFilterOption("platform", "平台"),
    TransparentFilterOption("sort", "排序")
)

// 剧场版筛选选项
val MOVIE_FILTER_OPTIONS = listOf(
    TransparentFilterOption("type", "类型"),
    TransparentFilterOption("region", "地区"),
    TransparentFilterOption("year", "年代"),
    TransparentFilterOption("sort", "排序")
)

// 番剧类型选项
val ANIME_TYPE_OPTIONS = listOf(
    SelectorOption("all", "全部"),
    SelectorOption("dark_humor", "黑色幽默"),
    SelectorOption("history", "历史"),
    SelectorOption("musical", "歌舞"),
    SelectorOption("inspirational", "励志"),
    SelectorOption("parody", "恶搞"),
    SelectorOption("healing", "治愈"),
    SelectorOption("sports", "运动"),
    SelectorOption("harem", "后宫"),
    SelectorOption("erotic", "情色"),
    SelectorOption("chinese_anime", "国漫"),
    SelectorOption("human_nature", "人性"),
    SelectorOption("suspense", "悬疑"),
    SelectorOption("love", "恋爱"),
    SelectorOption("fantasy", "魔幻"),
    SelectorOption("sci_fi", "科幻")
)

// 剧场版类型选项
val ANIME_MOVIE_TYPE_OPTIONS = listOf(
    SelectorOption("all", "全部"),
    SelectorOption("stop_motion", "定格动画"),
    SelectorOption("biography", "传记"),
    SelectorOption("us_animation", "美国动画"),
    SelectorOption("romance", "爱情"),
    SelectorOption("dark_humor", "黑色幽默"),
    SelectorOption("musical", "歌舞"),
    SelectorOption("children", "儿童"),
    SelectorOption("anime", "二次元"),
    SelectorOption("animal", "动物"),
    SelectorOption("youth", "青春"),
    SelectorOption("history", "历史"),
    SelectorOption("inspirational", "励志"),
    SelectorOption("parody", "恶搞"),
    SelectorOption("healing", "治愈"),
    SelectorOption("sports", "运动"),
    SelectorOption("harem", "后宫"),
    SelectorOption("erotic", "情色"),
    SelectorOption("human_nature", "人性"),
    SelectorOption("suspense", "悬疑"),
    SelectorOption("love", "恋爱"),
    SelectorOption("fantasy", "魔幻"),
    SelectorOption("sci_fi", "科幻")
)

// 番剧地区选项
val ANIME_REGION_OPTIONS = listOf(
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

// 剧场版地区选项
val ANIME_MOVIE_REGION_OPTIONS = listOf(
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

// 年代选项
val ANIME_YEAR_OPTIONS = listOf(
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
val ANIME_PLATFORM_OPTIONS = listOf(
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
val ANIME_SORT_OPTIONS = listOf(
    SelectorOption("T", "综合排序"),
    SelectorOption("U", "近期热度"),
    SelectorOption("R", "首映时间"),
    SelectorOption("S", "高分优先")
)

// ==================== 布局常量 ====================

private const val ANIME_COLUMNS = GRID_COLUMNS          // 每行6列
private const val ANIME_PAGE_LIMIT = GRID_PAGE_LIMIT    // 每页数据量

// ==================== 布局计算 ====================

data class AnimeGridLayout(
    val horizontalPadding: Dp,
    val cardGap: Dp
)

fun calculateAnimeGridLayout(screenWidthDp: Float): AnimeGridLayout {
    val totalCardWidth = GRID_CARD_WIDTH * ANIME_COLUMNS  // 720dp
    val availableSpace = screenWidthDp - totalCardWidth
    
    // 左右边距: 剩余空间的 15%, 最小 20dp
    val horizontalPadding = maxOf(20f, availableSpace * 0.15f)
    
    // 卡片间距: 剩余空间的 70% 分给 5 个间隙, 最小 28dp
    val cardGap = maxOf(28f, (availableSpace * 0.7f) / (ANIME_COLUMNS - 1))
    
    return AnimeGridLayout(
        horizontalPadding = horizontalPadding.dp,
        cardGap = cardGap.dp
    )
}

// ==================== 筛选状态 ====================

data class AnimeFilterState(
    val category: String = "new",
    val weekday: String = getCurrentWeekday().toString(),
    val type: String = "all",
    val region: String = "all",
    val year: String = "all",
    val platform: String = "all",
    val sort: String = "T"
)

private fun getCurrentWeekday(): Int {
    val calendar = Calendar.getInstance()
    return when (calendar.get(Calendar.DAY_OF_WEEK)) {
        Calendar.SUNDAY -> 7
        else -> calendar.get(Calendar.DAY_OF_WEEK) - 1
    }
}

// 统一的内容项类型
sealed class AnimeContentItem {
    data class Bangumi(val item: BangumiItem) : AnimeContentItem()
    data class Douban(val item: DoubanMovie) : AnimeContentItem()
}

data class AnimeTabState(
    val items: List<AnimeContentItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val page: Int = 1,
    val hasMore: Boolean = true
)


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnimeTab(
    categoryFocusRequester: FocusRequester = remember { FocusRequester() },
    scrollState: ScrollState = rememberScrollState(),
    onVideoSelected: (VideoInfo) -> Unit = {}
) {
    val context = LocalContext.current
    val bangumiService = remember { BangumiService.getInstance(context) }
    val doubanService = remember { DoubanService.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    var filterState by remember { mutableStateOf(AnimeFilterState()) }
    var dataState by remember { mutableStateOf(AnimeTabState()) }
    var transparentFocusedKey by remember { mutableStateOf<String?>(null) }
    var dialogVisible by remember { mutableStateOf(false) }
    var dialogKey by remember { mutableStateOf<String?>(null) }

    val typeFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.toFloat()
    val gridLayout = remember(screenWidthDp) { calculateAnimeGridLayout(screenWidthDp) }

    fun loadData(isInitial: Boolean = false) {
        if (dataState.isLoading) return
        if (!isInitial && !dataState.hasMore) return
        
        coroutineScope.launch {
            dataState = dataState.copy(isLoading = true, errorMessage = null)
            
            try {
                when (filterState.category) {
                    "new" -> {
                        // 每日放送 - 使用 BangumiService
                        val weekday = filterState.weekday.toIntOrNull() ?: 1
                        val result = bangumiService.getCalendarByWeekday(weekday)
                        when (result) {
                            is ApiResponse.Success -> {
                                val items = result.data.map { AnimeContentItem.Bangumi(it) }
                                dataState = dataState.copy(
                                    items = items,
                                    isLoading = false,
                                    hasMore = false // 每日放送不支持翻页
                                )
                            }
                            is ApiResponse.Error -> {
                                dataState = dataState.copy(
                                    isLoading = false,
                                    errorMessage = result.message
                                )
                                Log.e(TAG, "加载每日放送失败: ${result.message}")
                            }
                        }
                    }
                    "series", "movie" -> {
                        // 番剧/剧场版 - 使用 DoubanService
                        val result = if (isInitial) {
                            loadInitialAnimeData(doubanService, filterState)
                        } else {
                            loadAnimeData(doubanService, filterState, dataState.page)
                        }
                        when (result) {
                            is ApiResponse.Success -> {
                                val newItems = result.data.map { AnimeContentItem.Douban(it) }
                                val hasMore = result.data.size >= ANIME_PAGE_LIMIT
                                dataState = if (isInitial) {
                                    dataState.copy(
                                        items = newItems,
                                        isLoading = false,
                                        page = 2,
                                        hasMore = hasMore
                                    )
                                } else {
                                    dataState.copy(
                                        items = dataState.items + newItems,
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
    
    LaunchedEffect(filterState) {
        dataState = AnimeTabState()
        loadData(isInitial = true)
    }

    // 根据分类和 dialogKey 获取对应的选项列表
    val typeOptions = if (filterState.category == "series") ANIME_TYPE_OPTIONS else ANIME_MOVIE_TYPE_OPTIONS
    val regionOptions = if (filterState.category == "series") ANIME_REGION_OPTIONS else ANIME_MOVIE_REGION_OPTIONS
    
    val dialogOptions = when (dialogKey) {
        "type" -> typeOptions
        "region" -> regionOptions
        "year" -> ANIME_YEAR_OPTIONS
        "platform" -> ANIME_PLATFORM_OPTIONS
        "sort" -> ANIME_SORT_OPTIONS
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
        "type" -> filterState.type
        "region" -> filterState.region
        "year" -> filterState.year
        "platform" -> filterState.platform
        "sort" -> filterState.sort
        else -> ""
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MediaGridScaffold(
            items = dataState.items,
            isLoading = dataState.isLoading,
            errorMessage = dataState.errorMessage,
            scrollState = scrollState,
            horizontalPadding = gridLayout.horizontalPadding,
            cardGap = gridLayout.cardGap,
            contentFocusRequester = contentFocusRequester,
            resetKey = filterState,
            itemToVideoInfo = { item ->
                when (item) {
                    is AnimeContentItem.Bangumi -> item.item.toVideoInfo()
                    is AnimeContentItem.Douban -> item.item.toVideoInfo()
                }
            },
            itemToSource = { item ->
                when (item) {
                    is AnimeContentItem.Bangumi -> VideoCardSource.BANGUMI
                    is AnimeContentItem.Douban -> VideoCardSource.DOUBAN
                }
            },
            onItemClick = { item ->
                when (item) {
                    is AnimeContentItem.Bangumi -> onVideoSelected(item.item.toVideoInfo())
                    is AnimeContentItem.Douban -> onVideoSelected(item.item.toVideoInfo())
                }
            },
            onRetry = { loadData(isInitial = true) },
            onLoadMore = { loadData() },
            onContentNavigateUp = { typeFocusRequester.requestFocus() },
            filterSection = {
                AnimeFilterSection(
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
                    typeFocusRequester = typeFocusRequester,
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
                    "type" -> filterState = filterState.copy(type = value)
                    "region" -> filterState = filterState.copy(region = value)
                    "year" -> filterState = filterState.copy(year = value)
                    "platform" -> filterState = filterState.copy(platform = value)
                    "sort" -> filterState = filterState.copy(sort = value)
                }
            },
            onClose = { dialogVisible = false }
        )
    }
}


/**
 * 加载番剧/剧场版数据
 */
private suspend fun loadAnimeData(
    service: DoubanService,
    filterState: AnimeFilterState,
    page: Int
): ApiResponse<List<DoubanMovie>> {
    val typeOptions = if (filterState.category == "series") ANIME_TYPE_OPTIONS else ANIME_MOVIE_TYPE_OPTIONS
    val regionOptions = if (filterState.category == "series") ANIME_REGION_OPTIONS else ANIME_MOVIE_REGION_OPTIONS
    
    val typeLabel = typeOptions.find { it.value == filterState.type }?.label
    val regionLabel = regionOptions.find { it.value == filterState.region }?.label
    val yearLabel = ANIME_YEAR_OPTIONS.find { it.value == filterState.year }?.label
    val platformLabel = ANIME_PLATFORM_OPTIONS.find { it.value == filterState.platform }?.label
    
    val params = if (filterState.category == "series") {
        DoubanRecommendsParams(
            kind = "tv",
            category = "动画",
            label = if (filterState.type == "all") "all" else typeLabel ?: "",
            format = "电视剧",
            region = if (filterState.region == "all") "all" else regionLabel ?: "",
            year = if (filterState.year == "all") "all" else yearLabel ?: "",
            platform = if (filterState.platform == "all") "all" else platformLabel ?: "",
            sort = filterState.sort,
            pageLimit = ANIME_PAGE_LIMIT,
            page = page
        )
    } else {
        DoubanRecommendsParams(
            kind = "movie",
            category = "动画",
            label = if (filterState.type == "all") "all" else typeLabel ?: "",
            format = "电影",
            region = if (filterState.region == "all") "all" else regionLabel ?: "",
            year = if (filterState.year == "all") "all" else yearLabel ?: "",
            sort = filterState.sort,
            pageLimit = ANIME_PAGE_LIMIT,
            page = page
        )
    }
    
    return service.fetchDoubanRecommends(params)
}

/**
 * 首次加载数据 (并行请求两页)
 */
private suspend fun loadInitialAnimeData(
    service: DoubanService,
    filterState: AnimeFilterState
): ApiResponse<List<DoubanMovie>> = coroutineScope {
    val deferred1 = async { loadAnimeData(service, filterState, 0) }
    val deferred2 = async { loadAnimeData(service, filterState, 1) }
    
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
private fun AnimeFilterSection(
    filterState: AnimeFilterState,
    onFilterStateChange: (AnimeFilterState) -> Unit,
    transparentFocusedKey: String?,
    onTransparentFocus: (String) -> Unit,
    onTransparentBlur: () -> Unit,
    onTransparentSelect: (String) -> Unit,
    categoryFocusRequester: FocusRequester,
    typeFocusRequester: FocusRequester,
    onNavigateDown: () -> Unit
) {
    val isNewCategory = filterState.category == "new"
    val typeOptions = if (filterState.category == "series") ANIME_TYPE_OPTIONS else ANIME_MOVIE_TYPE_OPTIONS
    val regionOptions = if (filterState.category == "series") ANIME_REGION_OPTIONS else ANIME_MOVIE_REGION_OPTIONS
    
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
                    options = ANIME_CATEGORY_OPTIONS,
                    selectedKey = filterState.category,
                    onSelect = { key ->
                        when (key) {
                            "new" -> onFilterStateChange(
                                filterState.copy(
                                    category = key,
                                    weekday = getCurrentWeekday().toString()
                                )
                            )
                            else -> onFilterStateChange(
                                filterState.copy(
                                    category = key,
                                    type = "all",
                                    region = "all",
                                    year = "all",
                                    platform = "all",
                                    sort = "T"
                                )
                            )
                        }
                    },
                    focusRequester = categoryFocusRequester,
                    modifier = Modifier.onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionDown) {
                            typeFocusRequester.requestFocus()
                            true
                        } else false
                    }
                )
            }
        )
        
        // 第二行 (根据分类动态切换)
        if (isNewCategory) {
            // 每日放送 - 星期选择
            GridFilterRow(
                label = "星期",
                content = {
                    FilterSelector(
                        options = WEEKDAY_OPTIONS,
                        selectedKey = filterState.weekday,
                        onSelect = { key ->
                            onFilterStateChange(filterState.copy(weekday = key))
                        },
                        focusRequester = typeFocusRequester,
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
        } else {
            // 番剧/剧场版 - 透明筛选器
            val filterOptions = if (filterState.category == "series") {
                listOf(
                    TransparentFilterOption("type", "类型", typeOptions.find { it.value == filterState.type }?.label),
                    TransparentFilterOption("region", "地区", regionOptions.find { it.value == filterState.region }?.label),
                    TransparentFilterOption("year", "年代", ANIME_YEAR_OPTIONS.find { it.value == filterState.year }?.label),
                    TransparentFilterOption("platform", "平台", ANIME_PLATFORM_OPTIONS.find { it.value == filterState.platform }?.label),
                    TransparentFilterOption("sort", "排序", ANIME_SORT_OPTIONS.find { it.value == filterState.sort }?.label)
                )
            } else {
                listOf(
                    TransparentFilterOption("type", "类型", typeOptions.find { it.value == filterState.type }?.label),
                    TransparentFilterOption("region", "地区", regionOptions.find { it.value == filterState.region }?.label),
                    TransparentFilterOption("year", "年代", ANIME_YEAR_OPTIONS.find { it.value == filterState.year }?.label),
                    TransparentFilterOption("sort", "排序", ANIME_SORT_OPTIONS.find { it.value == filterState.sort }?.label)
                )
            }
            
            GridFilterRow(
                label = "筛选",
                content = {
                    TransparentFilterSelector(
                        options = filterOptions,
                        focusedKey = transparentFocusedKey,
                        onFocus = onTransparentFocus,
                        onBlur = onTransparentBlur,
                        onSelect = onTransparentSelect,
                        modifier = Modifier
                            .focusRequester(typeFocusRequester)
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
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun AnimeTabPreview() {
    AnimeTab()
}
