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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.moontechlab.selenetv.model.VideoInfo
import org.moontechlab.selenetv.model.toVideoInfo
import org.moontechlab.selenetv.service.ApiResponse
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
    
    // 热门电影数据状态
    var hotMovies by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var isLoadingMovies by remember { mutableStateOf(true) }
    
    // 热门剧集数据状态
    var hotTvShows by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var isLoadingTvShows by remember { mutableStateOf(true) }
    
    // 新番放送数据状态
    var bangumiCalendar by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var isLoadingBangumi by remember { mutableStateOf(true) }
    
    // 热门综艺数据状态
    var hotShows by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var isLoadingShows by remember { mutableStateOf(true) }
    
    // 加载数据
    LaunchedEffect(Unit) {
        val doubanService = DoubanService.getInstance(context)
        val bangumiService = BangumiService.getInstance(context)
        
        // 加载热门电影
        coroutineScope.launch {
            isLoadingMovies = true
            when (val response = doubanService.getHotMovies(pageLimit = 20, page = 0)) {
                is ApiResponse.Success -> {
                    hotMovies = response.data.map { it.toVideoInfo() }
                }
                is ApiResponse.Error -> {
                    android.util.Log.e("HomeTab", "加载热门电影失败: ${response.message}")
                }
            }
            isLoadingMovies = false
        }
        
        // 加载热门剧集
        coroutineScope.launch {
            isLoadingTvShows = true
            when (val response = doubanService.getHotTvShows(pageLimit = 20, page = 0)) {
                is ApiResponse.Success -> {
                    hotTvShows = response.data.map { it.toVideoInfo() }
                }
                is ApiResponse.Error -> {
                    android.util.Log.e("HomeTab", "加载热门剧集失败: ${response.message}")
                }
            }
            isLoadingTvShows = false
        }
        
        // 加载新番放送
        coroutineScope.launch {
            isLoadingBangumi = true
            when (val response = bangumiService.getTodayCalendar()) {
                is ApiResponse.Success -> {
                    bangumiCalendar = response.data.map { it.toVideoInfo() }
                }
                is ApiResponse.Error -> {
                    android.util.Log.e("HomeTab", "加载新番放送失败: ${response.message}")
                }
            }
            isLoadingBangumi = false
        }
        
        // 加载热门综艺
        coroutineScope.launch {
            isLoadingShows = true
            when (val response = doubanService.getHotShows(pageLimit = 20, page = 0)) {
                is ApiResponse.Success -> {
                    hotShows = response.data.map { it.toVideoInfo() }
                }
                is ApiResponse.Error -> {
                    android.util.Log.e("HomeTab", "加载热门综艺失败: ${response.message}")
                }
            }
            isLoadingShows = false
        }
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
            // 顶部留出 TabSwitcher 的空间
            Spacer(modifier = Modifier.height(46.dp))
            
            RecommendSection(
            title = "热门电影",
            videos = hotMovies,
            from = VideoCardSource.DOUBAN,
            isLoading = isLoadingMovies,
            onVideoClick = { video ->
                // TODO: 处理视频点击事件
                android.util.Log.d("HomeTab", "点击视频: ${video.title}")
            }
        )
        
        RecommendSection(
            title = "热门剧集",
            videos = hotTvShows,
            from = VideoCardSource.DOUBAN,
            isLoading = isLoadingTvShows,
            onVideoClick = { video ->
                // TODO: 处理视频点击事件
                android.util.Log.d("HomeTab", "点击视频: ${video.title}")
            }
        )
        
        RecommendSection(
            title = "新番放送",
            videos = bangumiCalendar,
            from = VideoCardSource.BANGUMI,
            isLoading = isLoadingBangumi,
            onVideoClick = { video ->
                // TODO: 处理视频点击事件
                android.util.Log.d("HomeTab", "点击视频: ${video.title}")
            }
        )
        
        RecommendSection(
            title = "热门综艺",
            videos = hotShows,
            from = VideoCardSource.DOUBAN,
            isLoading = isLoadingShows,
            onVideoClick = { video ->
                // TODO: 处理视频点击事件
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
