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
import org.moontechlab.selenetv.screen.player.PlayerLaunch
import org.moontechlab.selenetv.screen.player.PlayerScreen
import org.moontechlab.selenetv.service.ApiResponse
import org.moontechlab.selenetv.service.ApiService
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.service.BangumiService
import org.moontechlab.selenetv.service.ContentFilterService
import org.moontechlab.selenetv.service.DoubanCacheService
import org.moontechlab.selenetv.service.DoubanService
import org.moontechlab.selenetv.service.SearchEvent
import org.moontechlab.selenetv.service.SearchService
import org.moontechlab.selenetv.service.TmdbService

/** TMDB 剧照解析的触发参数：标题（原名或中文回退）+ 年份消歧。 */
private data class TmdbArtQuery(val originalTitle: String, val year: String)

/**
 * TMDB 查询用标题：优先 original_title；为空时回退中文标题。
 * 国产内容豆瓣不返回 original_title，但 TMDB 以中文 original_name 收录，回退中文名可命中
 * （如「凡人修仙传」→ tv 106449）；外语片有 original_title 时仍优先原名，不受影响。两者皆空返回 null。
 */
private fun tmdbQueryTitle(originalTitle: String?, title: String?): String? =
    originalTitle?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() }

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(entry: DetailEntry, onBack: () -> Unit, onGoToSearch: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doubanService = remember { DoubanService.getInstance(context) }
    val bangumiService = remember { BangumiService.getInstance(context) }
    val doubanCacheService = remember { DoubanCacheService.getInstance(context) }

    var state by remember(entry) { mutableStateOf(DetailUiState(entry = entry)) }
    // 搜索流累积的原始结果（Douban/Bangumi/History 用）
    var rawResults by remember(entry) { mutableStateOf<List<SearchResult>>(emptyList()) }
    // tier2 命中的 doubanId（>=2 相同）；一旦确定即稳定，供独立 effect 拉取豆瓣详情，
    // 避免把网络请求放在随 state.sources 变化而被取消的 effect 里（LeftCompositionCancellation）。
    var tier2Target by remember(entry) { mutableStateOf<String?>(null) }
    // 已拿到豆瓣详情、且有 original_title 后，待解析 TMDB 剧照的查询。设置后保持加载页直到 TMDB
    // 结算（见下方 effect），避免新布局在内容露出后才弹入造成跳变。仅豆瓣数据页会触发。
    var tmdbTrigger by remember(entry) { mutableStateOf<TmdbArtQuery?>(null) }

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
            is DetailEntry.History -> {
                val group = DetailSourceResolver.historyGroupSources(rawResults, entry.source, entry.id)
                    ?: if (state.sourcesComplete) rawResults else emptyList()
                // 从播放记录/收藏夹进入：把入口对应的源(source+id)排到首位（稳定排序，其余顺序不变）
                val entryKey = "${entry.source}+${entry.id}"
                group.sortedByDescending { sourceKey(it) == entryKey }
            }
            else -> rawResults
        }
        state = state.copy(sources = displayed)
    }

    // 3) 拉豆瓣详情（Douban 入口）
    LaunchedEffect(entry) {
        if (entry is DetailEntry.Douban) {
            state = state.copy(detailsLoading = true, detailsError = null)
            when (val resp = doubanService.getDoubanDetails(entry.doubanId)) {
                is ApiResponse.Success -> {
                    val q = tmdbQueryTitle(resp.data.originalTitle, resp.data.title)
                    if (q != null) {
                        // 有可用标题 → 保持加载页，交由 TMDB effect 结算 detailsLoading
                        state = state.copy(details = resp.data)
                        tmdbTrigger = TmdbArtQuery(q, resp.data.year)
                    } else {
                        state = state.copy(details = resp.data, detailsLoading = false)
                    }
                }
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

    // 5A) History/SearchAggregate 的结算判定（不发起网络请求，故可安全地随结果变化重算）：
    //    在「全部已命中结果」rawResults（而非收敛后的展示源 state.sources）上判定 tier2，
    //    使详情 hero 能在「目标源被找到」之前就解析出来——只要任意 2 个源认同同一 doubanId 即可，
    //    从而尽早撤掉加载页；源列表是否展示由 effect 2 独立控制（目标源未找到前持续骨架屏）。
    //    某 doubanId 出现 >= 2 次（tier2）→ 记下目标，交由 5B 拉取豆瓣详情（拉完前保持加载页）；
    //    搜索结束仍无 tier2 → 撤下加载页展示选中源自身信息；否则保持加载页等待后续结果。
    //    结算后锁定（!detailsLoading 直接返回），不因后续源流入而改变 hero，避免跳变。
    LaunchedEffect(rawResults, state.sourcesComplete, entry) {
        if (entry !is DetailEntry.History && entry !is DetailEntry.SearchAggregate) return@LaunchedEffect
        if (!state.detailsLoading) return@LaunchedEffect // 已结算，锁定，避免后续跳变
        val tier2 = DetailSourceResolver.tier2DoubanId(rawResults)
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
            is ApiResponse.Success -> {
                val q = tmdbQueryTitle(resp.data.originalTitle, resp.data.title)
                if (q != null) {
                    state = state.copy(details = resp.data) // 保持加载页，等 TMDB 结算
                    tmdbTrigger = TmdbArtQuery(q, resp.data.year)
                } else {
                    state = state.copy(details = resp.data, detailsLoading = false)
                }
            }
            is ApiResponse.Error -> state = state.copy(details = null, detailsLoading = false) // 失败兜底源信息
        }
    }

    // 5C) 解析 TMDB 剧照/ logo（仅豆瓣数据页、且有 original_title 时触发）。
    //    在加载页背后完成：拿到结果（成功/失败/超时/未配 key）才撤加载页，故新旧布局一次性落定，无跳变。
    //    依赖稳定的 tmdbTrigger，不随 state.sources 变化被取消。失败一律回退旧布局。缓存见 TmdbService。
    LaunchedEffect(tmdbTrigger) {
        val q = tmdbTrigger ?: return@LaunchedEffect
        val key = AppDataService.getTmdbApiKey()
        val art = if (key.isNotBlank()) {
            kotlinx.coroutines.withTimeoutOrNull(4000) {
                TmdbService.resolveArt(q.originalTitle, q.year, key, doubanCacheService)
            }
        } else null
        state = state.copy(tmdbArt = art, detailsLoading = false)
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

    // 播放页覆盖层入口（内存覆盖层，不销毁详情）。launchPlayer 由选集/继续/播放触发。
    var playerLaunch by remember { mutableStateOf<PlayerLaunch?>(null) }
    var playerEverOpened by remember { mutableStateOf(false) }
    // 退出播放页后把焦点交回主动作按钮（播放页持有焦点，移除后详情页无落点会丢焦）。
    // 等淡出动画(240ms)结束、PlayerScreen 释放焦点后再请求。
    LaunchedEffect(playerLaunch == null) {
        if (playerLaunch == null && playerEverOpened) {
            kotlinx.coroutines.delay(300)
            try { primaryFocus.requestFocus() } catch (_: Exception) {}
        }
    }
    fun launchPlayer(source: SearchResult, episode: Int, startMs: Long, totalMs: Long) {
        playerEverOpened = true
        val epIndex = episode.coerceIn(0, source.episodes.lastIndex.coerceAtLeast(0))
        playerLaunch = PlayerLaunch(
            title = state.heroInfo?.title?.takeIf { it.isNotBlank() } ?: state.entry.title,
            heroInfo = state.heroInfo,
            sources = state.sources,
            selectedSourceKey = sourceKey(source),
            episodeIndex = epIndex,
            startPositionMs = startMs.coerceAtLeast(0),
            durationMs = if (totalMs > 0L) totalMs else 45 * 60 * 1000L,
        )
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
        // PlayRecord.index 为 1 基集号（与详情按钮/卡片 "N/总" 显示一致）；
        // 播放页 episodeIndex 为 0 基数组下标，故 -1。
        val epIndex = ((rec?.index ?: 1) - 1).coerceAtLeast(0)
        // PlayRecord.playTime/totalTime 单位为秒（MoonTV 约定），播放页用毫秒，故 ×1000。
        launchPlayer(source, epIndex, (rec?.playTime ?: 0L) * 1000, (rec?.totalTime ?: 0L) * 1000)
    }

    val onPlay: () -> Unit = lam@{
        val source = state.selectedSource ?: return@lam
        val rec = state.selectedPlayRecord
        when {
            // 有续看记录时「播放」语义为「从头播放」
            rec != null -> launchPlayer(source, 0, 0L, 0L)
            source.episodes.size > 1 -> state = state.copy(episodePickerVisible = true)
            else -> launchPlayer(source, 0, 0L, 0L)
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
            // 撤掉加载屏：内容从 0.96 轻微放大并淡入「落定」，与月牙层的淡出+放大形成柔和过渡
            enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(420)) +
                androidx.compose.animation.scaleIn(
                    initialScale = 0.96f,
                    animationSpec = androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                ),
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
                                    is ApiResponse.Success -> {
                                        val q = tmdbQueryTitle(resp.data.originalTitle, resp.data.title)
                                        if (q != null) {
                                            state = state.copy(details = resp.data)
                                            tmdbTrigger = TmdbArtQuery(q, resp.data.year)
                                        } else {
                                            state = state.copy(details = resp.data, detailsLoading = false)
                                        }
                                    }
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
                        // 平时禁止焦点逃逸到覆盖层下的首页；但播放页覆盖层打开时需放行，
                        // 否则播放页 requestFocus 无法把焦点从详情内容里夺走（焦点钉死、任意键失效）。
                        .focusProperties { exit = { if (playerLaunch != null) FocusRequester.Default else FocusRequester.Cancel } }
                        .verticalScroll(scrollState),
                ) {
                    // 操作按钮行：两种 hero 布局共用，故抽成 lambda 传入
                    val actionsRowContent: @Composable () -> Unit = {
                            val hasRecord = state.selectedPlayRecord != null
                            val hasSource = state.selectedSource != null
                            val record = state.selectedPlayRecord
                            val epCount = state.selectedSource?.episodes?.size
                                ?: record?.totalEpisodes ?: 1
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
                                // 新 hero 布局把源名并入副信息行（见 DetailHeroV2），故按钮区不再显示
                                sourceName = if (state.useHeroV2) null else state.selectedSource?.sourceName,
                                onContinue = onContinue,
                                onPlay = onPlay,
                                onDelete = onDeleteRecord,
                                onToggleFavorite = onToggleFavorite,
                                primaryFocusRequester = primaryFocus,
                                upTarget = if (hasSummary) summaryFocus else null,
                                // 续看：集数（总集>1 才显示）+ 进度，嵌进「继续播放」按钮
                                continueEpisode = if (record != null && epCount > 1 && record.index > 0) record.index else null,
                                continueProgress = if (record != null && record.totalTime > 0L)
                                    (record.playTime.toFloat() / record.totalTime.toFloat()).coerceIn(0f, 1f) else 0f,
                            )
                            }
                    }
                    // 拿到 TMDB 剧照 → Apple TV 式新布局；否则旧布局。两者参数一致。
                    if (state.useHeroV2) {
                        org.moontechlab.selenetv.screen.detail.DetailHeroV2(
                            entry = state.entry,
                            info = state.heroInfo,
                            sourceName = state.selectedSource?.sourceName,
                            summaryFocusRequester = if (hasSummary) summaryFocus else null,
                            onSummaryClick = { summaryEverOpened = true; summaryDialogVisible = true },
                            actionsRow = actionsRowContent,
                        )
                    } else {
                        org.moontechlab.selenetv.screen.detail.DetailHero(
                            entry = state.entry,
                            info = state.heroInfo,
                            summaryFocusRequester = if (hasSummary) summaryFocus else null,
                            onSummaryClick = { summaryEverOpened = true; summaryDialogVisible = true },
                            actionsRow = actionsRowContent,
                        )
                    }
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
            // 月牙层退场：淡出同时轻微放大「化开」，与内容淡入交叠
            exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(360)) +
                androidx.compose.animation.scaleOut(
                    targetScale = 1.06f,
                    animationSpec = androidx.compose.animation.core.tween(360, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                ),
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
                    state = state.copy(episodePickerVisible = false)
                    launchPlayer(source, idx, 0L, 0L)
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

        // 播放页覆盖层：淡入覆盖整个详情；退场期间保留最后一次入参，避免内容提前消失。
        val retainedLaunch = remember { mutableStateOf<PlayerLaunch?>(null) }
        playerLaunch?.let { retainedLaunch.value = it }
        androidx.compose.animation.AnimatedVisibility(
            visible = playerLaunch != null,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(240)),
        ) {
            retainedLaunch.value?.let { l ->
                PlayerScreen(launch = l, onExit = { playerLaunch = null })
            }
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
