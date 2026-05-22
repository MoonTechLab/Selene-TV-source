package org.moontechlab.selenetv.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.screen.detail.DetailUiState
import org.moontechlab.selenetv.screen.detail.SourceFilter
import org.moontechlab.selenetv.screen.detail.sourceKey
import org.moontechlab.selenetv.service.ApiResponse
import org.moontechlab.selenetv.service.ApiService
import org.moontechlab.selenetv.service.ContentFilterService
import org.moontechlab.selenetv.service.DoubanService
import org.moontechlab.selenetv.service.SearchEvent
import org.moontechlab.selenetv.service.SearchService

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(route: DetailRoute, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doubanService = remember { DoubanService.getInstance(context) }

    var state by remember { mutableStateOf(DetailUiState(route = route)) }

    DisposableEffect(Unit) {
        onDispose { SearchService.stopSearch() }
    }

    BackHandler { onBack() }

    // 拉豆瓣详情
    LaunchedEffect(route.doubanId) {
        state = state.copy(detailsLoading = true, detailsError = null)
        when (val resp = doubanService.getDoubanDetails(route.doubanId)) {
            is ApiResponse.Success -> state = state.copy(details = resp.data, detailsLoading = false)
            is ApiResponse.Error   -> state = state.copy(detailsError = resp.message, detailsLoading = false)
        }
    }

    // 启动搜索 + 订阅事件流，按 doubanId/title/year 筛选
    LaunchedEffect(route.doubanId) {
        // Known limitation: SearchService is a global singleton, so opening DetailScreen while
        // SearchTab is actively searching will interrupt the SearchTab's search. Follow-up:
        // scope SearchService per-screen or route detail searches through a separate channel.
        SearchService.stopSearch()
        SearchService.eventFlow
            .onSubscription {
                // 订阅注册完成后再启动搜索，避免漏掉首批 Results
                scope.launch(Dispatchers.IO) {
                    SearchService.startSearch(route.title)
                }
            }
            .collect { event ->
                when (event) {
                    is SearchEvent.Results -> {
                        val accepted = event.results
                            .asSequence()
                            .filter { SourceFilter.matchesContent(it, route.doubanId, route.title, route.year) }
                            .filter { !ContentFilterService.shouldFilter(it.typeName) }
                            .toList()
                        if (accepted.isNotEmpty()) {
                            val merged = SearchService.deduplicateResults(state.sources + accepted)
                            state = state.copy(sources = merged)
                        }
                    }
                    is SearchEvent.Progress -> state = state.copy(sourcesProgress = event.progress)
                    is SearchEvent.Complete -> state = state.copy(sourcesComplete = true)
                    is SearchEvent.Error    -> {} // 单源错误已被 SearchService 内部记录
                }
            }
    }

    // 拉播放记录
    LaunchedEffect(route.doubanId) {
        try {
            val records = withContext(Dispatchers.IO) { ApiService.getPlayRecords() }
            state = state.copy(playRecords = records)
        } catch (e: Exception) { android.util.Log.w("DetailScreen", "播放记录加载失败", e) }
    }

    // 拉收藏列表
    LaunchedEffect(route.doubanId) {
        try {
            val favs = withContext(Dispatchers.IO) { ApiService.getFavorites() }
            val keys = favs.map { "${it.source}+${it.id}" }.toSet()
            state = state.copy(favoriteKeys = keys)
        } catch (e: Exception) { android.util.Log.w("DetailScreen", "收藏列表加载失败", e) }
    }

    // 默认选中源
    LaunchedEffect(state.sources, state.playRecords) {
        val current = state.selectedSourceKey
        val stillValid = current != null && state.sources.any { sourceKey(it) == current }
        if (!stillValid && state.sources.isNotEmpty()) {
            val withRecord = state.sources
                .mapNotNull { src ->
                    val key = sourceKey(src)
                    state.playRecords[key]?.let { rec -> src to rec.saveTime }
                }
                .maxByOrNull { it.second }
                ?.first
            val pick = withRecord ?: state.sources.first()
            state = state.copy(selectedSourceKey = sourceKey(pick))
        }
    }

    // 是否真正展示加载层（延迟 150ms，避免命中缓存时闪屏）
    var showMoonOverlay by remember { mutableStateOf(false) }
    LaunchedEffect(state.detailsLoading) {
        if (state.detailsLoading) {
            kotlinx.coroutines.delay(150)
            if (state.detailsLoading) showMoonOverlay = true
        } else {
            showMoonOverlay = false
        }
    }

    val primaryFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val scrollState = rememberScrollState()

    // 禁用详情内容（垂直）的自动 bringIntoView：聚焦按钮/播放源不再自动滚动，
    // 改为手动控制（按钮行→顶部，播放源→底部），消除进入时"先下滚再回顶"的抖动。
    val noVerticalAutoScroll = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
        }
    }

    // 详情就绪后请求初始焦点。等 selectedSource 就绪（播放按钮才会渲染）再请求，
    // 并把滚动复位到顶部——否则聚焦位于较长 hero 下方的播放按钮会触发 bringIntoView，
    // 把标题滚出视口（#3）。播放按钮在顶部视口内，复位后仍可见且保持焦点。
    LaunchedEffect(state.detailsLoading, state.selectedSource != null) {
        if (!state.detailsLoading && state.selectedSource != null) {
            kotlinx.coroutines.delay(260)
            try { primaryFocus.requestFocus() } catch (_: Exception) {}
            scrollState.scrollTo(0)
        }
    }

    fun showToast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    val onToggleFavorite: () -> Unit = lam@{
        val source = state.selectedSource ?: return@lam
        val key = sourceKey(source)
        val details = state.details
        val wasFav = state.isSelectedFavorite

        // 乐观更新
        state = state.copy(
            favoriteKeys = if (wasFav) state.favoriteKeys - key else state.favoriteKeys + key
        )
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (wasFav) {
                        ApiService.unfavorite(source.source, source.id)
                    } else {
                        val favData = mapOf<String, Any?>(
                            "title" to (details?.title ?: state.route.title),
                            "source_name" to source.sourceName,
                            "year" to (details?.year ?: state.route.year),
                            "cover" to source.poster,
                            "total_episodes" to source.episodes.size,
                            "save_time" to System.currentTimeMillis(),
                            "origin" to "",
                        )
                        ApiService.addFavorite(source.source, source.id, favData)
                    }
                }
            } catch (e: Exception) {
                // 回滚
                state = state.copy(
                    favoriteKeys = if (wasFav) state.favoriteKeys + key else state.favoriteKeys - key
                )
                showToast("收藏操作失败：${e.message ?: "未知错误"}")
            }
        }
    }

    val onDeleteRecord: () -> Unit = lam@{
        val source = state.selectedSource ?: return@lam
        val key = sourceKey(source)
        val backup = state.playRecords[key] ?: return@lam
        state = state.copy(playRecords = state.playRecords - key)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { ApiService.deletePlayRecord(source.source, source.id) }
                showToast("已删除播放记录")
            } catch (e: Exception) {
                state = state.copy(playRecords = state.playRecords + (key to backup))
                showToast("删除失败：${e.message ?: "未知错误"}")
            }
        }
    }

    val onContinue: () -> Unit = lam@{
        val source = state.selectedSource ?: return@lam
        val rec = state.selectedPlayRecord
        android.util.Log.d("DetailScreen", "continue: source=${source.source}+${source.id} ep=${rec?.index} pos=${rec?.playTime}")
    }

    val onPlay: () -> Unit = lam@{
        val source = state.selectedSource ?: return@lam
        val rec = state.selectedPlayRecord
        when {
            rec != null -> {
                android.util.Log.d("DetailScreen", "play (resume): source=${source.source}+${source.id} ep=${rec.index} pos=${rec.playTime}")
            }
            source.episodes.size > 1 -> {
                state = state.copy(episodePickerVisible = true)
            }
            else -> {
                android.util.Log.d("DetailScreen", "play (single): source=${source.source}+${source.id} ep=0")
            }
        }
    }

    val gradientBrush = Brush.verticalGradient(
        0.0f to Color(0xFF0a0a0a),
        0.4f to Color(0xFF1a1a1a),
        0.7f to Color(0xFF0f0f0f),
        1.0f to Color(0xFF050505),
    )

    Box(modifier = Modifier.fillMaxSize().background(brush = gradientBrush)) {
        androidx.compose.animation.AnimatedVisibility(
            visible = !state.detailsLoading,
            enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(0)),
        ) {
            val err = state.detailsError
            if (err != null && state.details == null) {
                HeroErrorState(
                    message = err,
                    onRetry = {
                        scope.launch {
                            state = state.copy(detailsLoading = true, detailsError = null)
                            when (val resp = doubanService.getDoubanDetails(state.route.doubanId)) {
                                is ApiResponse.Success -> state = state.copy(details = resp.data, detailsLoading = false)
                                is ApiResponse.Error -> state = state.copy(detailsError = resp.message, detailsLoading = false)
                            }
                        }
                    },
                )
            } else {
                CompositionLocalProvider(LocalBringIntoViewSpec provides noVerticalAutoScroll) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // 把焦点限制在详情覆盖层内：到达边界时取消方向搜索，
                        // 避免焦点逃逸到覆盖层下方的首页（#4，用户已确认此现象）
                        .focusGroup()
                        .focusProperties { exit = { FocusRequester.Cancel } }
                        .verticalScroll(scrollState),
                ) {
                    org.moontechlab.selenetv.screen.detail.DetailHero(
                        route = state.route,
                        details = state.details,
                        actionsRow = {
                            val hasRecord = state.selectedPlayRecord != null
                            val hasSource = state.selectedSource != null
                            // 焦点落在按钮行时把页面滚到顶部（垂直自动滚动已禁用，这里手动控制）
                            Box(
                                modifier = Modifier.onFocusChanged {
                                    if (it.hasFocus) scope.launch { scrollState.animateScrollTo(0) }
                                },
                            ) {
                            // 收藏与选中源绑定（按 source+id 存储，参考 plan 文档的 Plan-vs-Spec 微调）。
                            // 当 selectedSource 为空时整组按钮（含 ♡）隐藏；后续可重新评估是否引入"按 doubanId 收藏"概念。
                            org.moontechlab.selenetv.screen.detail.DetailActionButtons(
                                showContinue = hasSource && hasRecord,
                                showPlay = hasSource,
                                showDelete = hasSource && hasRecord,
                                showFavorite = hasSource,
                                isFavorite = state.isSelectedFavorite,
                                sourceName = state.selectedSource?.sourceName,
                                onContinue = onContinue,
                                onPlay = onPlay,
                                onDelete = onDeleteRecord,
                                onToggleFavorite = onToggleFavorite,
                                primaryFocusRequester = primaryFocus,
                            )
                            }
                        },
                    )
                    // 焦点进入播放源时把页面滚到底部以完整显示播放源（垂直自动滚动已禁用）
                    Box(
                        modifier = Modifier.onFocusChanged {
                            if (it.hasFocus) scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                        },
                    ) {
                        org.moontechlab.selenetv.screen.detail.DetailSourcesSection(
                            sources = state.sources,
                            selectedSourceKey = state.selectedSourceKey,
                            playRecords = state.playRecords,
                            progress = state.sourcesProgress,
                            complete = state.sourcesComplete,
                            onSelect = { key -> state = state.copy(selectedSourceKey = key) },
                        )
                    }
                }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showMoonOverlay,
            enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(200)),
        ) {
            org.moontechlab.selenetv.screen.detail.MoonLoadingOverlay(title = state.route.title)
        }

        state.selectedSource?.let { source ->
            org.moontechlab.selenetv.screen.detail.EpisodePickerDialog(
                visible = state.episodePickerVisible,
                sourceName = source.sourceName,
                episodes = source.episodes,
                episodesTitles = source.episodesTitles,
                onSelect = { idx ->
                    android.util.Log.d("DetailScreen", "play: source=${source.source}+${source.id} ep=$idx")
                    state = state.copy(episodePickerVisible = false)
                },
                onDismiss = { state = state.copy(episodePickerVisible = false) },
            )
        }
    }
}

@Composable
private fun HeroErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
            androidx.tv.material3.Text(
                text = "加载失败：$message",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
            )
            androidx.tv.material3.Surface(
                onClick = onRetry,
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = Color(0x14FFFFFF),
                    focusedContainerColor = Color(0xFF4CAF50),
                ),
                modifier = Modifier.onFocusChanged { focused = it.isFocused },
            ) {
                androidx.tv.material3.Text(
                    text = "重试",
                    color = if (focused) Color.White else Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp),
                )
            }
        }
    }
}
