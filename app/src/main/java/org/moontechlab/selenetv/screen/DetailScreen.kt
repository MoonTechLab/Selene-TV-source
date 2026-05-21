package org.moontechlab.selenetv.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

@Composable
fun DetailScreen(navController: NavController, route: DetailRoute) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doubanService = remember { DoubanService.getInstance(context) }

    var state by remember { mutableStateOf(DetailUiState(route = route)) }

    DisposableEffect(Unit) {
        onDispose { SearchService.stopSearch() }
    }

    BackHandler { navController.popBackStack() }

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
        SearchService.stopSearch() // 清掉上一次的状态
        // 与事件流的订阅必须在 startSearch 之前先挂上，避免漏掉 Results
        scope.launch {
            SearchService.eventFlow.collect { event ->
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
        withContext(Dispatchers.IO) {
            SearchService.startSearch(route.title)
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

    // 详情就绪后请求初始焦点（等 cross-fade 结束）
    LaunchedEffect(state.detailsLoading, state.sources.isNotEmpty()) {
        if (!state.detailsLoading) {
            kotlinx.coroutines.delay(220) // 等 cross-fade 结束
            try { primaryFocus.requestFocus() } catch (_: Exception) {}
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                org.moontechlab.selenetv.screen.detail.DetailHero(
                    route = state.route,
                    details = state.details,
                    actionsRow = {
                        val hasRecord = state.selectedPlayRecord != null
                        val hasSource = state.selectedSource != null
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
                    },
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showMoonOverlay,
            enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(200)),
        ) {
            org.moontechlab.selenetv.screen.detail.MoonLoadingOverlay(title = state.route.title)
        }
    }
}
