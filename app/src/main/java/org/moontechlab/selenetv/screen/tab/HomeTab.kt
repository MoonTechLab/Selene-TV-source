package org.moontechlab.selenetv.screen.tab

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.model.VideoInfo
import org.moontechlab.selenetv.model.toVideoInfo
import org.moontechlab.selenetv.service.ApiResponse
import org.moontechlab.selenetv.service.ApiService
import org.moontechlab.selenetv.service.BangumiService
import org.moontechlab.selenetv.service.DoubanService
import org.moontechlab.selenetv.ui.RecommendSection
import org.moontechlab.selenetv.ui.VideoCardSource

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeTab(
    focusRequester: FocusRequester = remember { FocusRequester() },
    scrollState: ScrollState = rememberScrollState()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // 播放历史数据状态
    var playRecords by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var isLoadingPlayRecords by remember { mutableStateOf(true) }
    var errorPlayRecords by remember { mutableStateOf<String?>(null) }

    // 收藏夹数据状态
    var favorites by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var isLoadingFavorites by remember { mutableStateOf(true) }
    var errorFavorites by remember { mutableStateOf<String?>(null) }

    // 热门电影数据状态
    var hotMovies by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var isLoadingMovies by remember { mutableStateOf(true) }
    var errorMovies by remember { mutableStateOf<String?>(null) }

    // 热门剧集数据状态
    var hotTvShows by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var isLoadingTvShows by remember { mutableStateOf(true) }
    var errorTvShows by remember { mutableStateOf<String?>(null) }

    // 新番放送数据状态
    var bangumiCalendar by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var isLoadingBangumi by remember { mutableStateOf(true) }
    var errorBangumi by remember { mutableStateOf<String?>(null) }

    // 热门综艺数据状态
    var hotShows by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var isLoadingShows by remember { mutableStateOf(true) }
    var errorShows by remember { mutableStateOf<String?>(null) }

    val doubanService = remember { DoubanService.getInstance(context) }
    val bangumiService = remember { BangumiService.getInstance(context) }

    // 用于匹配的播放记录 Map（收藏夹需要）
    var playRecordsMap by remember { mutableStateOf<Map<String, org.moontechlab.selenetv.model.PlayRecord>>(emptyMap()) }

    // 加载播放历史 + 收藏夹（收藏夹依赖播放历史的 index）
    fun loadPlayRecordsAndFavorites() {
        coroutineScope.launch {
            isLoadingPlayRecords = true
            errorPlayRecords = null
            isLoadingFavorites = true
            errorFavorites = null

            try {
                val records = withContext(Dispatchers.IO) {
                    ApiService.getPlayRecords()
                }
                playRecordsMap = records
                playRecords = records.values
                    .sortedByDescending { it.saveTime }
                    .map { it.toVideoInfo() }
                errorPlayRecords = null
            } catch (e: Exception) {
                errorPlayRecords = e.message ?: "加载失败"
                android.util.Log.e("HomeTab", "加载播放历史失败: ${e.message}")
            }
            isLoadingPlayRecords = false

            try {
                val favList = withContext(Dispatchers.IO) {
                    ApiService.getFavorites()
                }
                favorites = favList.map { fav ->
                    val key = "${fav.source}+${fav.id}"
                    val matchedRecord = playRecordsMap[key]
                    val videoInfo = fav.toVideoInfo()
                    if (matchedRecord != null) {
                        videoInfo.copy(index = matchedRecord.index)
                    } else {
                        videoInfo
                    }
                }
                errorFavorites = null
            } catch (e: Exception) {
                errorFavorites = e.message ?: "加载失败"
                android.util.Log.e("HomeTab", "加载收藏夹失败: ${e.message}")
            }
            isLoadingFavorites = false
        }
    }

    fun loadHotMovies() {
        coroutineScope.launch {
            isLoadingMovies = true
            errorMovies = null
            when (val response = doubanService.getHotMovies(pageLimit = 20, page = 0)) {
                is ApiResponse.Success -> {
                    hotMovies = response.data.map { it.toVideoInfo() }
                    errorMovies = null
                }
                is ApiResponse.Error -> {
                    errorMovies = response.message
                    android.util.Log.e("HomeTab", "加载热门电影失败: ${response.message}")
                }
            }
            isLoadingMovies = false
        }
    }

    fun loadHotTvShows() {
        coroutineScope.launch {
            isLoadingTvShows = true
            errorTvShows = null
            when (val response = doubanService.getHotTvShows(pageLimit = 20, page = 0)) {
                is ApiResponse.Success -> {
                    hotTvShows = response.data.map { it.toVideoInfo() }
                    errorTvShows = null
                }
                is ApiResponse.Error -> {
                    errorTvShows = response.message
                    android.util.Log.e("HomeTab", "加载热门剧集失败: ${response.message}")
                }
            }
            isLoadingTvShows = false
        }
    }

    fun loadBangumi() {
        coroutineScope.launch {
            isLoadingBangumi = true
            errorBangumi = null
            when (val response = bangumiService.getTodayCalendar()) {
                is ApiResponse.Success -> {
                    bangumiCalendar = response.data.map { it.toVideoInfo() }
                    errorBangumi = null
                }
                is ApiResponse.Error -> {
                    errorBangumi = response.message
                    android.util.Log.e("HomeTab", "加载新番放送失败: ${response.message}")
                }
            }
            isLoadingBangumi = false
        }
    }

    fun loadHotShows() {
        coroutineScope.launch {
            isLoadingShows = true
            errorShows = null
            when (val response = doubanService.getHotShows(pageLimit = 20, page = 0)) {
                is ApiResponse.Success -> {
                    hotShows = response.data.map { it.toVideoInfo() }
                    errorShows = null
                }
                is ApiResponse.Error -> {
                    errorShows = response.message
                    android.util.Log.e("HomeTab", "加载热门综艺失败: ${response.message}")
                }
            }
            isLoadingShows = false
        }
    }

    // 初始加载
    LaunchedEffect(Unit) {
        loadPlayRecordsAndFavorites()
        loadHotMovies()
        loadHotTvShows()
        loadBangumi()
        loadHotShows()
    }
    
    // 追踪是否是垂直方向的按键
    var isVerticalNavigation by remember { mutableStateOf(false) }
    
    // 根据按键方向决定是否允许垂直滚动
    val directionalBringIntoViewSpec = remember(isVerticalNavigation) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                // 只有垂直导航时才允许滚动
                if (!isVerticalNavigation) return 0f
                
                // 将聚焦项滚动到正中位置
                return offset + (size / 2f) - (containerSize / 2f)
            }
        }
    }
    
    CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides directionalBringIntoViewSpec
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .focusRequester(focusRequester)
                .focusGroup()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.key) {
                            Key.DirectionUp, Key.DirectionDown -> {
                                isVerticalNavigation = true
                            }
                            Key.DirectionLeft, Key.DirectionRight -> {
                                isVerticalNavigation = false
                            }
                        }
                    }
                    false // 不消费事件
                }
        ) {
            // 顶部留出 TabSwitcher 的空间 + 额外间距
            Spacer(modifier = Modifier.height(64.dp))
            
            // 播放历史 - 仅在有数据或加载中或有错误时显示
            if (playRecords.isNotEmpty() || isLoadingPlayRecords || errorPlayRecords != null) {
                RecommendSection(
                    title = "继续观看",
                    videos = playRecords,
                    from = VideoCardSource.PLAYRECORD,
                    isLoading = isLoadingPlayRecords,
                    errorMessage = errorPlayRecords,
                    onRetry = { loadPlayRecordsAndFavorites() },
                    onVideoClick = { video ->
                        android.util.Log.d("HomeTab", "点击播放历史: ${video.title}")
                    }
                )
            }

            // 收藏夹 - 仅在有数据或加载中或有错误时显示
            if (favorites.isNotEmpty() || isLoadingFavorites || errorFavorites != null) {
                RecommendSection(
                    title = "我的收藏",
                    videos = favorites,
                    from = VideoCardSource.FAVORITE,
                    isLoading = isLoadingFavorites,
                    errorMessage = errorFavorites,
                    onRetry = { loadPlayRecordsAndFavorites() },
                    onVideoClick = { video ->
                        android.util.Log.d("HomeTab", "点击收藏: ${video.title}")
                    }
                )
            }

            RecommendSection(
                title = "热门电影",
                videos = hotMovies,
                from = VideoCardSource.DOUBAN,
                isLoading = isLoadingMovies,
                errorMessage = errorMovies,
                onRetry = { loadHotMovies() },
                onVideoClick = { video ->
                    android.util.Log.d("HomeTab", "点击视频: ${video.title}")
                }
            )

            RecommendSection(
                title = "热门剧集",
                videos = hotTvShows,
                from = VideoCardSource.DOUBAN,
                isLoading = isLoadingTvShows,
                errorMessage = errorTvShows,
                onRetry = { loadHotTvShows() },
                onVideoClick = { video ->
                    android.util.Log.d("HomeTab", "点击视频: ${video.title}")
                }
            )

            RecommendSection(
                title = "新番放送",
                videos = bangumiCalendar,
                from = VideoCardSource.BANGUMI,
                isLoading = isLoadingBangumi,
                errorMessage = errorBangumi,
                onRetry = { loadBangumi() },
                onVideoClick = { video ->
                    android.util.Log.d("HomeTab", "点击视频: ${video.title}")
                }
            )

            RecommendSection(
                title = "热门综艺",
                videos = hotShows,
                from = VideoCardSource.DOUBAN,
                isLoading = isLoadingShows,
                errorMessage = errorShows,
                onRetry = { loadHotShows() },
                onVideoClick = { video ->
                    android.util.Log.d("HomeTab", "点击视频: ${video.title}")
                }
            )
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun HomeTabPreview() {
    HomeTab()
}
