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
import androidx.compose.ui.focus.focusRestorer
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
import org.moontechlab.selenetv.data.Repositories
import org.moontechlab.selenetv.service.ApiResponse
import org.moontechlab.selenetv.service.ApiService
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.service.BangumiService
import org.moontechlab.selenetv.service.DoubanService
import org.moontechlab.selenetv.ui.CardContextMenu
import org.moontechlab.selenetv.ui.CardMenuItem
import org.moontechlab.selenetv.ui.ConfirmDialog
import org.moontechlab.selenetv.ui.RecommendSection
import org.moontechlab.selenetv.ui.VideoCardSource

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeTab(
    focusRequester: FocusRequester = remember { FocusRequester() },
    scrollState: ScrollState = rememberScrollState(),
    onVideoSelected: (VideoInfo) -> Unit = {},
    homeRefreshKey: Int = 0,
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

    // 登录态：未登录时隐藏「继续观看」「我的收藏」并跳过其加载。
    // 随 homeRefreshKey 重新读取（登录后从其他页返回会刷新）。
    val isLoggedIn = remember(homeRefreshKey) { AppDataService.isLoggedInSync() || AppDataService.isLocalModeSync() }

    // 用于匹配的播放记录 Map（收藏夹需要）
    var playRecordsMap by remember { mutableStateOf<Map<String, org.moontechlab.selenetv.model.PlayRecord>>(emptyMap()) }

    // ===== 长按菜单 / 二次确认 状态 =====
    // 当前被长按的卡片及其来源；menuVisible 单独控制显隐，
    // 关闭动画期间需保持 target 不变（与 FilterDialog 一样常驻组合树）
    var menuVisible by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<VideoInfo?>(null) }
    var menuFrom by remember { mutableStateOf<VideoCardSource?>(null) }
    // 二次确认弹窗
    var confirmVisible by remember { mutableStateOf(false) }
    var confirmTitle by remember { mutableStateOf("") }
    var confirmMessage by remember { mutableStateOf("") }
    var confirmAction by remember { mutableStateOf<() -> Unit>({}) }

    // 当前收藏的 key 集合，用于判断「继续观看」卡片是否已收藏
    val favoriteKeys = remember(favorites) {
        favorites.map { "${it.source}+${it.id}" }.toSet()
    }

    // 删除播放记录：乐观移除 + 失败回滚
    fun deletePlayRecordAction(video: VideoInfo) {
        val prev = playRecords
        playRecords = playRecords.filterNot { it.source == video.source && it.id == video.id }
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) { Repositories.userData().deletePlayRecord(video.source, video.id) }
            } catch (e: Exception) {
                playRecords = prev
                android.util.Log.e("HomeTab", "删除播放记录失败: ${e.message}")
            }
        }
    }

    // 添加收藏：乐观插入到收藏列表头部 + 失败回滚
    fun addFavoriteAction(video: VideoInfo) {
        val key = "${video.source}+${video.id}"
        if (favoriteKeys.contains(key)) return
        val prev = favorites
        favorites = listOf(video.copy(saveTime = System.currentTimeMillis())) + favorites
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Repositories.userData().addFavorite(
                        video.source,
                        video.id,
                        mapOf(
                            "title" to video.title,
                            "source_name" to video.sourceName,
                            "year" to video.year,
                            "cover" to video.cover,
                            "total_episodes" to video.totalEpisodes,
                            "save_time" to System.currentTimeMillis(),
                        )
                    )
                }
            } catch (e: Exception) {
                favorites = prev
                android.util.Log.e("HomeTab", "添加收藏失败: ${e.message}")
            }
        }
    }

    // 取消收藏：乐观移除 + 失败回滚（favoriteKeys 随之更新，继续观看的收藏态自动同步）
    fun unfavoriteAction(video: VideoInfo) {
        val prev = favorites
        favorites = favorites.filterNot { it.source == video.source && it.id == video.id }
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) { Repositories.userData().unfavorite(video.source, video.id) }
            } catch (e: Exception) {
                favorites = prev
                android.util.Log.e("HomeTab", "取消收藏失败: ${e.message}")
            }
        }
    }

    // 弹出二次确认弹窗
    fun requestConfirm(title: String, message: String, action: () -> Unit) {
        confirmTitle = title
        confirmMessage = message
        confirmAction = action
        confirmVisible = true
    }

    // 加载播放历史 + 收藏夹（收藏夹依赖播放历史的 index）
    fun loadPlayRecordsAndFavorites() {
        // 未登录：不请求（会抛「未登录」异常），直接清空并置为非加载态，区块由 isLoggedIn 隐藏
        if (!(AppDataService.isLoggedInSync() || AppDataService.isLocalModeSync())) {
            playRecords = emptyList()
            favorites = emptyList()
            isLoadingPlayRecords = false
            isLoadingFavorites = false
            errorPlayRecords = null
            errorFavorites = null
            return
        }
        coroutineScope.launch {
            // 刷新时若已有数据，保持现有卡片、静默原地刷新；不要切到骨架屏。
            // 否则会销毁正在聚焦的卡片，导致从详情返回时焦点被 focusRestorer 重置到第一个。
            if (playRecords.isEmpty()) isLoadingPlayRecords = true
            errorPlayRecords = null
            if (favorites.isEmpty()) isLoadingFavorites = true
            errorFavorites = null

            try {
                val records = withContext(Dispatchers.IO) {
                    Repositories.userData().getPlayRecords()
                }
                playRecordsMap = records
                val newRecords = records.values
                    .sortedByDescending { it.saveTime }
                    .map { it.toVideoInfo() }
                // 数据未变则不重新赋值，避免触发 LazyRow 的 focusRestorer 把焦点重置到第一个
                // （从详情返回时若 homeRefreshKey 刷新恰好在焦点恢复后完成，会抢走焦点）
                if (newRecords != playRecords) playRecords = newRecords
                errorPlayRecords = null
            } catch (e: Exception) {
                errorPlayRecords = e.message ?: "加载失败"
                android.util.Log.e("HomeTab", "加载播放历史失败: ${e.message}")
            }
            isLoadingPlayRecords = false

            try {
                val favList = withContext(Dispatchers.IO) {
                    Repositories.userData().getFavorites()
                }
                val newFavs = favList.map { fav ->
                    val key = "${fav.source}+${fav.id}"
                    val matchedRecord = playRecordsMap[key]
                    val videoInfo = fav.toVideoInfo()
                    if (matchedRecord != null) {
                        videoInfo.copy(index = matchedRecord.index)
                    } else {
                        videoInfo
                    }
                }
                // 同上：数据未变则不重新赋值，避免焦点被 focusRestorer 重置
                if (newFavs != favorites) favorites = newFavs
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

    LaunchedEffect(homeRefreshKey) {
        if (homeRefreshKey > 0) {  // 跳过首次（已由 LaunchedEffect(Unit) 触发）
            loadPlayRecordsAndFavorites()
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
            // 顶部留出 TabSwitcher 的空间 + 额外间距
            Spacer(modifier = Modifier.height(64.dp))
            
            // 播放历史 - 仅登录后、且有数据/加载中/有错误时显示（#4 未登录隐藏）
            if (isLoggedIn && (playRecords.isNotEmpty() || isLoadingPlayRecords || errorPlayRecords != null)) {
                RecommendSection(
                    title = "继续观看",
                    videos = playRecords,
                    from = VideoCardSource.PLAYRECORD,
                    isLoading = isLoadingPlayRecords,
                    errorMessage = errorPlayRecords,
                    onRetry = { loadPlayRecordsAndFavorites() },
                    onVideoClick = onVideoSelected,
                    onVideoLongClick = { video ->
                        menuTarget = video
                        menuFrom = VideoCardSource.PLAYRECORD
                        menuVisible = true
                    }
                )
            }

            // 收藏夹 - 仅登录后、且有数据/加载中/有错误时显示（#4 未登录隐藏）
            if (isLoggedIn && (favorites.isNotEmpty() || isLoadingFavorites || errorFavorites != null)) {
                RecommendSection(
                    title = "我的收藏",
                    videos = favorites,
                    from = VideoCardSource.FAVORITE,
                    isLoading = isLoadingFavorites,
                    errorMessage = errorFavorites,
                    onRetry = { loadPlayRecordsAndFavorites() },
                    onVideoClick = onVideoSelected,
                    onVideoLongClick = { video ->
                        menuTarget = video
                        menuFrom = VideoCardSource.FAVORITE
                        menuVisible = true
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
                onVideoClick = onVideoSelected
            )

            RecommendSection(
                title = "热门剧集",
                videos = hotTvShows,
                from = VideoCardSource.DOUBAN,
                isLoading = isLoadingTvShows,
                errorMessage = errorTvShows,
                onRetry = { loadHotTvShows() },
                onVideoClick = onVideoSelected
            )

            RecommendSection(
                title = "新番放送",
                videos = bangumiCalendar,
                from = VideoCardSource.BANGUMI,
                isLoading = isLoadingBangumi,
                errorMessage = errorBangumi,
                onRetry = { loadBangumi() },
                onVideoClick = onVideoSelected
            )

            RecommendSection(
                title = "热门综艺",
                videos = hotShows,
                from = VideoCardSource.DOUBAN,
                isLoading = isLoadingShows,
                errorMessage = errorShows,
                onRetry = { loadHotShows() },
                onVideoClick = onVideoSelected
            )
        }

        // ===== 长按上下文菜单（常驻组合树，由 menuVisible 控制显隐）=====
        val target = menuTarget
        val from = menuFrom
        val menuItems: List<CardMenuItem> = if (target != null && from != null) {
            val isFav = favoriteKeys.contains("${target.source}+${target.id}")
            buildList {
                when (from) {
                    VideoCardSource.PLAYRECORD -> {
                        if (isFav) {
                            add(CardMenuItem("取消收藏", isDestructive = true) {
                                requestConfirm("取消收藏", "确定取消收藏「${target.title}」吗？") {
                                    unfavoriteAction(target)
                                }
                            })
                        } else {
                            add(CardMenuItem("收藏") { addFavoriteAction(target) })
                        }
                        add(CardMenuItem("删除记录", isDestructive = true) {
                            requestConfirm("删除播放记录", "确定从「继续观看」中删除「${target.title}」吗？") {
                                deletePlayRecordAction(target)
                            }
                        })
                    }
                    VideoCardSource.FAVORITE -> {
                        add(CardMenuItem("取消收藏", isDestructive = true) {
                            requestConfirm("取消收藏", "确定取消收藏「${target.title}」吗？") {
                                unfavoriteAction(target)
                            }
                        })
                    }
                    else -> {}
                }
            }
        } else emptyList()

        CardContextMenu(
            visible = menuVisible,
            title = target?.title ?: "",
            items = menuItems,
            onClose = { menuVisible = false }
        )

        // ===== 二次确认弹窗 =====
        ConfirmDialog(
            visible = confirmVisible,
            title = confirmTitle,
            message = confirmMessage,
            onConfirm = confirmAction,
            onCancel = { confirmVisible = false }
        )
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun HomeTabPreview() {
    HomeTab()
}
