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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.screen.detail.DetailSourceResolver
import org.moontechlab.selenetv.screen.detail.DetailUiState
import org.moontechlab.selenetv.screen.detail.SourceFilter
import org.moontechlab.selenetv.screen.detail.sourceKey
import org.moontechlab.selenetv.service.ApiResponse
import org.moontechlab.selenetv.service.ApiService
import org.moontechlab.selenetv.service.BangumiService
import org.moontechlab.selenetv.service.ContentFilterService
import org.moontechlab.selenetv.service.DoubanService
import org.moontechlab.selenetv.service.SearchEvent
import org.moontechlab.selenetv.service.SearchService

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(entry: DetailEntry, onBack: () -> Unit, onGoToSearch: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doubanService = remember { DoubanService.getInstance(context) }
    val bangumiService = remember { BangumiService.getInstance(context) }

    var state by remember(entry) { mutableStateOf(DetailUiState(entry = entry)) }
    // 搜索流累积的原始结果（Douban/Bangumi/History 用）
    var rawResults by remember(entry) { mutableStateOf<List<SearchResult>>(emptyList()) }
    // tier2 命中的 doubanId（>=2 相同）；一旦确定即稳定，供独立 effect 拉取豆瓣详情，
    // 避免把网络请求放在随 state.sources 变化而被取消的 effect 里（LeftCompositionCancellation）。
    var tier2Target by remember(entry) { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { SearchService.stopSearch() }
    }

    BackHandler { onBack() }

    // 入口接受规则
    fun accept(result: SearchResult): Boolean = when (entry) {
        is DetailEntry.Douban -> SourceFilter.matchesContent(result, entry.doubanId, entry.title, entry.year)
        is DetailEntry.Bangumi -> SourceFilter.matchesByTitleYear(result, entry.title, entry.year)
        is DetailEntry.History -> SourceFilter.matchesByTitleYear(result, entry.title, entry.year)
        is DetailEntry.SearchAggregate -> false
    }

    // 1) 源获取：SearchAggregate 直接快照；其余启动搜索累积
    LaunchedEffect(entry) {
        when (entry) {
            is DetailEntry.SearchAggregate -> {
                rawResults = entry.agg.originalResults.filter { !ContentFilterService.shouldFilter(it.typeName) }
                state = state.copy(sourcesComplete = true)
            }
            else -> {
                SearchService.stopSearch()
                val query = when (entry) {
                    is DetailEntry.History -> entry.searchTitle.ifBlank { entry.title }
                    else -> entry.title
                }
                if (query.isBlank()) {
                    // 没有可用的搜索关键词：直接标记完成，不调用会抛异常的 startSearch
                    state = state.copy(sourcesComplete = true)
                    return@LaunchedEffect
                }
                SearchService.eventFlow
                    .onSubscription {
                        scope.launch(Dispatchers.IO) { SearchService.startSearch(query) }
                    }
                    .collect { event ->
                        when (event) {
                            is SearchEvent.Results -> {
                                val accepted = event.results
                                    .asSequence()
                                    .filter { accept(it) }
                                    .filter { !ContentFilterService.shouldFilter(it.typeName) }
                                    .toList()
                                if (accepted.isNotEmpty()) {
                                    rawResults = SearchService.deduplicateResults(rawResults + accepted)
                                }
                            }
                            is SearchEvent.Progress -> state = state.copy(sourcesProgress = event.progress)
                            is SearchEvent.Complete -> state = state.copy(sourcesComplete = true)
                            is SearchEvent.Error -> {}
                        }
                    }
            }
        }
    }

    // 2) 由 rawResults 派生展示源：History 收敛到包含 source+id 的聚合组。
    //    组未识别到之前展示空（此时仍在加载页，不暴露会「广→窄」收缩的全集，避免跳变）；
    //    搜索结束仍未识别到组，则回退展示全部命中结果。
    LaunchedEffect(rawResults, state.sourcesComplete, entry) {
        val displayed = when (entry) {
            is DetailEntry.History ->
                DetailSourceResolver.historyGroupSources(rawResults, entry.source, entry.id)
                    ?: if (state.sourcesComplete) rawResults else emptyList()
            else -> rawResults
        }
        state = state.copy(sources = displayed)
    }

    // 3) 拉豆瓣详情（Douban 入口）
    LaunchedEffect(entry) {
        if (entry is DetailEntry.Douban) {
            state = state.copy(detailsLoading = true, detailsError = null)
            when (val resp = doubanService.getDoubanDetails(entry.doubanId)) {
                is ApiResponse.Success -> state = state.copy(details = resp.data, detailsLoading = false)
                is ApiResponse.Error -> state = state.copy(detailsError = resp.message, detailsLoading = false)
            }
        }
    }

    // 4) 拉 bangumi 详情（Bangumi 入口）
    LaunchedEffect(entry) {
        if (entry is DetailEntry.Bangumi) {
            state = state.copy(detailsLoading = true, detailsError = null)
            when (val resp = bangumiService.getBangumiDetail(entry.bangumiId.toString())) {
                is ApiResponse.Success -> state = state.copy(bangumiDetails = resp.data, detailsLoading = false)
                is ApiResponse.Error -> state = state.copy(detailsError = resp.message, detailsLoading = false)
            }
        }
    }

    // 5A) History/SearchAggregate 的结算判定（不发起网络请求，故可安全地随 state.sources 变化重算）：
    //    展示源中某 doubanId 出现 >= 2 次（tier2）→ 记下目标，交由 5B 拉取豆瓣详情（拉完前保持加载页）；
    //    搜索结束仍无 tier2 → 撤下加载页展示选中源自身信息；否则保持加载页等待后续结果。
    //    结算后锁定（!detailsLoading 直接返回），不因后续源流入而改变 hero，避免跳变。
    LaunchedEffect(state.sources, state.sourcesComplete, entry) {
        if (entry !is DetailEntry.History && entry !is DetailEntry.SearchAggregate) return@LaunchedEffect
        if (!state.detailsLoading) return@LaunchedEffect // 已结算，锁定，避免后续跳变
        val tier2 = DetailSourceResolver.tier2DoubanId(state.sources)
        when {
            tier2 != null -> tier2Target = tier2 // 触发 5B 拉取（稳定 key，不会被 sources 变化取消）
            state.sourcesComplete -> state = state.copy(details = null, detailsLoading = false)
            // else：仍在搜索且暂无 tier2 → 保持加载页
        }
    }

    // 5B) 拉取 tier2 豆瓣详情：仅依赖稳定的 tier2Target，避免在 state.sources 变化时被取消
    //    （否则慢速的豆瓣请求会抛 LeftCompositionCancellation → 误判为失败 → 兜底源信息）。
    LaunchedEffect(tier2Target) {
        val id = tier2Target ?: return@LaunchedEffect
        when (val resp = doubanService.getDoubanDetails(id)) {
            is ApiResponse.Success -> state = state.copy(details = resp.data, detailsLoading = false)
            is ApiResponse.Error -> state = state.copy(details = null, detailsLoading = false) // 失败兜底源信息
        }
    }

    // 拉播放记录
    LaunchedEffect(entry) {
        try {
            val records = withContext(Dispatchers.IO) { ApiService.getPlayRecords() }
            state = state.copy(playRecords = records)
        } catch (e: Exception) { android.util.Log.w("DetailScreen", "播放记录加载失败", e) }
    }

    // 拉收藏列表
    LaunchedEffect(entry) {
        try {
            val favs = withContext(Dispatchers.IO) { ApiService.getFavorites() }
            val keys = favs.map { "${it.source}+${it.id}" }.toSet()
            state = state.copy(favoriteKeys = keys)
        } catch (e: Exception) { android.util.Log.w("DetailScreen", "收藏列表加载失败", e) }
    }

    // 默认选中源：History 优先选中来源 source+id；否则最近播放记录，再否则第一个
    LaunchedEffect(state.sources, state.playRecords, entry) {
        val current = state.selectedSourceKey
        val stillValid = current != null && state.sources.any { sourceKey(it) == current }
        if (!stillValid && state.sources.isNotEmpty()) {
            val historyPick = (entry as? DetailEntry.History)?.let { h ->
                state.sources.firstOrNull { sourceKey(it) == "${h.source}+${h.id}" }
            }
            val recordPick = state.sources
                .mapNotNull { src -> state.playRecords[sourceKey(src)]?.let { rec -> src to rec.saveTime } }
                .maxByOrNull { it.second }
                ?.first
            val pick = historyPick ?: recordPick ?: state.sources.first()
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

    val primaryFocus = remember { FocusRequester() }
    val summaryFocus = remember { FocusRequester() }
    val searchCtaFocus = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    // 无可用播放源时，动作行为空，把焦点落到「去搜索」按钮，保证遥控器始终有落点
    val noSources = state.sourcesComplete && state.sources.isEmpty()
    LaunchedEffect(noSources, state.detailsLoading) {
        if (noSources && !state.detailsLoading) {
            kotlinx.coroutines.delay(280)
            try { searchCtaFocus.requestFocus() } catch (_: Exception) {}
        }
    }

    // 简介全文弹窗
    var summaryDialogVisible by remember { mutableStateOf(false) }
    var summaryEverOpened by remember { mutableStateOf(false) }
    val hasSummary = !state.heroInfo?.summary.isNullOrEmpty()
    // 关闭弹窗后把焦点交回简介块（首次进入不抢占播放按钮的初始焦点）
    LaunchedEffect(summaryDialogVisible) {
        if (!summaryDialogVisible && summaryEverOpened) {
            kotlinx.coroutines.delay(50)
            try { summaryFocus.requestFocus() } catch (_: Exception) {}
        }
    }

    val noVerticalAutoScroll = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
        }
    }

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
        val wasFav = state.isSelectedFavorite

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
                            "title" to (state.heroInfo?.title ?: state.entry.title),
                            "source_name" to source.sourceName,
                            "year" to state.entry.year,
                            "cover" to source.poster,
                            "total_episodes" to source.episodes.size,
                            "save_time" to System.currentTimeMillis(),
                            "origin" to "",
                        )
                        ApiService.addFavorite(source.source, source.id, favData)
                    }
                }
            } catch (e: Exception) {
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
            if (err != null && state.details == null && state.bangumiDetails == null) {
                HeroErrorState(
                    message = err,
                    onRetry = {
                        scope.launch {
                            state = state.copy(detailsLoading = true, detailsError = null)
                            when (val e = state.entry) {
                                is DetailEntry.Douban -> when (val resp = doubanService.getDoubanDetails(e.doubanId)) {
                                    is ApiResponse.Success -> state = state.copy(details = resp.data, detailsLoading = false)
                                    is ApiResponse.Error -> state = state.copy(detailsError = resp.message, detailsLoading = false)
                                }
                                is DetailEntry.Bangumi -> when (val resp = bangumiService.getBangumiDetail(e.bangumiId.toString())) {
                                    is ApiResponse.Success -> state = state.copy(bangumiDetails = resp.data, detailsLoading = false)
                                    is ApiResponse.Error -> state = state.copy(detailsError = resp.message, detailsLoading = false)
                                }
                                else -> state = state.copy(detailsLoading = false)
                            }
                        }
                    },
                )
            } else {
                CompositionLocalProvider(LocalBringIntoViewSpec provides noVerticalAutoScroll) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusGroup()
                        .focusProperties { exit = { FocusRequester.Cancel } }
                        .verticalScroll(scrollState),
                ) {
                    org.moontechlab.selenetv.screen.detail.DetailHero(
                        entry = state.entry,
                        info = state.heroInfo,
                        summaryFocusRequester = if (hasSummary) summaryFocus else null,
                        onSummaryClick = { summaryEverOpened = true; summaryDialogVisible = true },
                        actionsRow = {
                            val hasRecord = state.selectedPlayRecord != null
                            val hasSource = state.selectedSource != null
                            Box(
                                modifier = Modifier.onFocusChanged {
                                    if (it.hasFocus) scope.launch { scrollState.animateScrollTo(0) }
                                },
                            ) {
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
                                upTarget = if (hasSummary) summaryFocus else null,
                            )
                            }
                        },
                    )
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
                            onSearch = { onGoToSearch(state.heroInfo?.title?.takeIf { it.isNotBlank() } ?: state.entry.title) },
                            searchFocusRequester = searchCtaFocus,
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
            org.moontechlab.selenetv.screen.detail.MoonLoadingOverlay(title = state.entry.title)
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

        org.moontechlab.selenetv.screen.detail.SummaryDialog(
            visible = summaryDialogVisible,
            title = state.heroInfo?.title?.takeIf { it.isNotBlank() } ?: state.entry.title,
            summary = state.heroInfo?.summary.orEmpty(),
            onDismiss = { summaryDialogVisible = false },
        )
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
