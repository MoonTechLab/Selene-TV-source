package org.moontechlab.selenetv.screen

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.model.SourceSpeed
import org.moontechlab.selenetv.model.SpeedTestState
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
import org.moontechlab.selenetv.service.DownstreamService
import org.moontechlab.selenetv.service.SearchEvent
import org.moontechlab.selenetv.service.SearchService
import org.moontechlab.selenetv.service.SpeedTestService
import org.moontechlab.selenetv.service.TmdbService

/** TMDB 剧照解析的触发参数：标题（原名或中文回退）+ 年份消歧。 */
private data class TmdbArtQuery(val originalTitle: String, val year: String)

/**
 * 内容（豆瓣/TMDB hero）入场动画时长：内容从 0.96 放大并淡入「落定」所需时间。
 * 加载 overlay 的撤场据此延后——数据就绪后，内容先在 overlay 之下跑完这段入场动画，overlay 再淡出
 * 揭示，确保揭示出来的是已落定的成品，而非动画中途/半成品（盖住→渲染完→揭示）。两处共用此常量，
 * 改动画时长时撤场等待自动同步。
 */
private const val CONTENT_ENTER_MS = 420

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
    // Douban 入口拿到豆瓣详情、且有 original_title 后，待解析 TMDB 剧照的查询（仅 Douban 入口及其重试用）。
    // 设置后保持加载页直到下方 5C 结算，避免新布局在内容露出后才弹入造成跳变。
    // 注意：tier2/SearchAggregate 路径不走这里——它在 5B 内联解析 TMDB 并原子落定，
    // 以规避「5B 连跑两遍 + 命中缓存」时 tmdbTrigger null→Q 抖动被 5C 漏掉、加载页卡死的问题。
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
                            is SearchEvent.Complete -> {
                                // 播放记录/收藏夹（带 source+id）：标题搜索未命中该具体源时，
                                // 用 source+id 精确补齐这一条加入结果集，保证保存的源始终可播。
                                if (entry is DetailEntry.History &&
                                    rawResults.none { it.source == entry.source && it.id == entry.id }
                                ) {
                                    val detail = DownstreamService.getDetailBySourceId(entry.source, entry.id)
                                    Log.d("DetailFallback", "补齐 source=${entry.source} id=${entry.id} -> ${detail?.let { "${it.title}(${it.episodes.size}集)" } ?: "null"}")
                                    if (detail != null && detail.episodes.isNotEmpty()) {
                                        rawResults = SearchService.deduplicateResults(rawResults + detail)
                                    }
                                }
                                state = state.copy(sourcesComplete = true)
                            }
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
        // 统一按 source+id 去重：SearchAggregate 快照与 History 回退未经搜索流的去重，
        // 同一源可能重复出现；distinctBy 幂等，对已去重的搜索结果无副作用。
        state = state.copy(sources = displayed.distinctBy { sourceKey(it) })
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

    // 5B) 拉取 tier2 豆瓣详情，并在同一协程内顺带解析 TMDB 剧照，最后把 details + tmdbArt + detailsLoading=false
    //    一次性原子落定。仅依赖稳定的 tier2Target（不随 state.sources 变化被取消；tier2 winner 随流式结果
    //    改判 X→Y 时，本 effect 按新 target 重入，旧协程连同其 TMDB 请求一并取消，无残留写入）。
    //
    //    历史教训（务必保留合并结构）：曾把 TMDB 拆成独立的 LaunchedEffect(tmdbTrigger)，并在本 effect 开头
    //    重置 tmdbTrigger=null。当豆瓣详情命中缓存（同步返回）且本 effect 因上游 5A 抖动连跑两遍时，
    //    tmdbTrigger 会在一个重组窗口内 null→Q→null→Q 抖动，LaunchedEffect(tmdbTrigger) 只观察到中间的
    //    null 便提前返回，最终的 Q 不再触发它 → TMDB 那步永不执行 → detailsLoading 永远为 true → 加载页
    //    卡死（仅「第二次打开、豆瓣已缓存」时复现）。合并为单协程后，detailsLoading=false 必随每次 5B 末尾
    //    原子落定，最后一次写入即终态；且 tmdbArt 与 details 同帧写入，避免「先露豆瓣布局、后弹 TMDB 布局」
    //    的跳变与焦点丢失。
    LaunchedEffect(tier2Target) {
        val id = tier2Target ?: return@LaunchedEffect
        val resp = doubanService.getDoubanDetails(id)
        // tier2Target 改判/离开组合会取消本协程，但 getDoubanDetails 的 catch(Exception) 会把
        // CancellationException 吞成 ApiResponse.Error。若不显式校验，被取消的旧协程会继续走下面的
        // 分支抢写 state（提前撤加载页、details=null），紧接着胜出的新协程再写真数据 → 布局跳变 + 焦点丢失。
        // 故在写 state 前断言协程仍存活，被取消则在此抛出 CancellationException 干净退出、绝不写 state。
        ensureActive()
        when (resp) {
            is ApiResponse.Success -> {
                val q = tmdbQueryTitle(resp.data.originalTitle, resp.data.title)
                if (q != null) {
                    // 加载页背后解析 TMDB 剧照（成功/失败/超时/未配 key 都接受），与豆瓣详情同帧落定
                    val key = AppDataService.getTmdbApiKey()
                    val art = if (key.isNotBlank()) {
                        kotlinx.coroutines.withTimeoutOrNull(4000) {
                            TmdbService.resolveArt(q, resp.data.year, key, doubanCacheService)
                        }
                    } else null
                    ensureActive()
                    state = state.copy(details = resp.data, tmdbArt = art, detailsLoading = false)
                } else {
                    state = state.copy(details = resp.data, detailsLoading = false)
                }
            }
            is ApiResponse.Error -> state = state.copy(details = null, detailsLoading = false) // 失败兜底源信息
        }
    }

    // 5C) 解析 TMDB 剧照/logo —— 仅服务 Douban 入口（及其重试）设置的 tmdbTrigger。
    //    在加载页背后完成：拿到结果（成功/失败/超时/未配 key）才撤加载页，故新旧布局一次性落定，无跳变。
    //    Douban 入口 effect 按稳定的 entry 单次运行、不重置 tmdbTrigger，故无 tier2 路径那种抖动竞态。
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
            // 数据就绪：内容已开始在 overlay 之下渲染并播放入场动画。若加载页正在显示，先继续盖住，
            // 等内容入场动画跑完(CONTENT_ENTER_MS)再淡出揭示成品，避免露出动画中途/半成品（盖住→渲染完→揭示）。
            // 加载页未显示（命中缓存的快路径）则不引入任何等待，内容入场动画照常可见。
            if (showMoonOverlay) {
                kotlinx.coroutines.delay(CONTENT_ENTER_MS.toLong())
            }
            showMoonOverlay = false
        }
    }

    val primaryFocus = remember { FocusRequester() }
    val summaryFocus = remember { FocusRequester() }
    val searchCtaFocus = remember { FocusRequester() }
    val speedTestFocus = remember { FocusRequester() }

    // 测速优选：并发测全部源 → 实时出分 → 全部完成后按分排序、自动选中冠军。
    var speedTestJob by remember { mutableStateOf<Job?>(null) }
    val startSpeedTest: () -> Unit = handler@{
        if (state.speedTesting) return@handler
        // 仅测有试播链接的源；其余跳过（不计入分母、不参与排序打分）
        val testable = state.sources.filter { SpeedTestService.testUrl(it) != null }
        if (testable.isEmpty()) return@handler
        val seed = testable.associate { sourceKey(it) to SourceSpeed(SpeedTestState.TESTING) }
        state = state.copy(speedTesting = true, speedResults = seed)
        speedTestJob = scope.launch {
            val jobScope = this
            // ConcurrentHashMap 承接 IO 线程并发回调，主线程仅用其快照刷新 UI，避免读改写竞态。
            // UI 刷新挂在 job 自身 scope 下，随取消一并停止，避免迟到刷新覆盖最终态。
            val acc = java.util.concurrent.ConcurrentHashMap<String, SourceSpeed>(seed)
            try {
                SpeedTestService.testAll(testable) { key, result ->
                    acc[key] = result
                    jobScope.launch(Dispatchers.Main) { state = state.copy(speedResults = HashMap(acc)) }
                }
                // 全部完成：按综合分降序重排，自动选中并标记冠军（失败源沉底）
                val maxSpeed = SpeedTestService.maxSpeed(acc.values)
                val ranked = state.sources.sortedByDescending { src ->
                    acc[sourceKey(src)]?.let { SpeedTestService.score(it, maxSpeed) } ?: -1.0
                }
                val winnerKey = ranked
                    .firstOrNull { acc[sourceKey(it)]?.state == SpeedTestState.DONE }
                    ?.let { sourceKey(it) }
                state = state.copy(
                    sources = ranked,
                    speedResults = HashMap(acc),
                    selectedSourceKey = winnerKey ?: state.selectedSourceKey,
                    speedTesting = false,
                )
            } catch (e: CancellationException) {
                // 用户打断：清掉未完成项（避免「测速中」角标冻结），保留已出结果，不重排、不改选中
                val settled = acc.filterValues { it.state != SpeedTestState.TESTING }
                state = state.copy(speedResults = HashMap(settled), speedTesting = false)
                throw e
            }
        }
    }
    val stopSpeedTest: () -> Unit = { speedTestJob?.cancel() }
    // 覆盖层根焦点捕获器：详情页绘制在仍存活的首页之上（见 HomeScreen 详情覆盖层），加载 overlay 期间
    // 或布局已出但播放源未解析完（无播放按钮可吸收焦点）时，覆盖层内没有任何可聚焦元素，焦点会留在
    // 背后的首页上，方向键便去驱动首页网格。入场即把焦点抓进这个零尺寸捕获器，并在根部统一 exit=Cancel，
    // 使焦点在任何加载阶段都钉在覆盖层内；真正内容就绪后由 primaryFocus/searchCta 接管，焦点移入内容后
    // 被内容 Column 自身的 exit=Cancel 留住，不再回到捕获器。
    val rootFocus = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    // 入场抓焦：覆盖层随滑入动画挂载，requester 可能尚未就位，重试数帧直到成功。
    LaunchedEffect(Unit) {
        repeat(10) {
            kotlinx.coroutines.delay(30)
            try { rootFocus.requestFocus(); return@LaunchedEffect } catch (_: Exception) {}
        }
    }

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

    // hero 出现后把焦点落到主按钮槽位：有源→播放按钮；源仍在加载→加载占位胶囊（两者都挂 primaryFocus）。
    // 源就绪、占位换成真实按钮时本 effect 因 selectedSource 变化重跑，把焦点原位移交给播放按钮。
    LaunchedEffect(state.detailsLoading, state.selectedSource != null, noSources) {
        if (!state.detailsLoading && !noSources) {
            kotlinx.coroutines.delay(260)
            try { primaryFocus.requestFocus() } catch (_: Exception) {}
            scrollState.scrollTo(0)
        }
    }

    fun showToast(msg: String) {
        org.moontechlab.selenetv.ui.MoonToast.show(msg)
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
            // 退出播放页后整体重拉播放记录，使详情页「继续播放」进度与服务端一致。
            // 播放页的保存/换源删记录均为 fire-and-forget（PlayProgressReporter），
            // 故再略等其落库后重拉；整体替换天然覆盖多次换源（新源记录 + 被删旧源记录）。
            kotlinx.coroutines.delay(400)
            try {
                val records = withContext(Dispatchers.IO) { ApiService.getPlayRecords() }
                state = state.copy(playRecords = records)
            } catch (e: Exception) { android.util.Log.w("DetailScreen", "退出播放页刷新播放记录失败", e) }
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
        // 删除后 hasRecord=false →「继续播放」「删除」两个按钮一并移除，承载焦点的「删除」按钮离开组合
        // 会让焦点悬空。删除后「播放」按钮接管 primaryFocus（见 DetailActionButtons），故重组稳定后把焦点
        // 改派给它；requester 在新布局挂载前 requestFocus 会抛异常，重试数帧直到成功。
        scope.launch {
            repeat(8) {
                kotlinx.coroutines.delay(30)
                try { primaryFocus.requestFocus(); return@launch } catch (_: Exception) {}
            }
        }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = gradientBrush)
            // 覆盖层根焦点组：始终禁止焦点逃逸到背后的首页（加载/源解析的空窗期亦然）。
            // 内部的播放页 / 选集弹窗 / 简介弹窗都是本组子节点，焦点在它们之间移动属组内移动，
            // 不触发 exit，故不受影响。
            .focusGroup()
            .focusProperties { exit = { FocusRequester.Cancel } },
    ) {
        // 零尺寸焦点捕获器：入场即承接焦点，使方向键在内容就绪前不外漏到首页。
        // 零尺寸不参与方向键的 2D 焦点搜索，故不干扰内容出现后的正常导航。
        Box(modifier = Modifier.focusRequester(rootFocus).focusable())

        androidx.compose.animation.AnimatedVisibility(
            visible = !state.detailsLoading,
            // 内容从 0.96 轻微放大并淡入「落定」。数据就绪后这段动画先在加载 overlay 之下跑完
            // （见上方 showMoonOverlay 控制），overlay 再淡出揭示已落定的成品。
            enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(CONTENT_ENTER_MS)) +
                androidx.compose.animation.scaleIn(
                    initialScale = 0.96f,
                    animationSpec = androidx.compose.animation.core.tween(CONTENT_ENTER_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
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
                        // 平时禁止焦点逃逸到覆盖层下的首页；但任意会抢焦点的覆盖层（播放页 /
                        // 选集弹窗 / 简介弹窗）打开时需放行，否则其 requestFocus 无法把焦点从详情
                        // 内容里夺走，焦点钉死在播放按钮、弹窗收不到方向键。
                        .focusProperties {
                            exit = {
                                if (playerLaunch != null || state.episodePickerVisible || summaryDialogVisible) {
                                    FocusRequester.Default
                                } else {
                                    FocusRequester.Cancel
                                }
                            }
                        }
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
                                // hero 已出、但播放源仍在解析（非「搜完且无源」）→ 显示加载占位胶囊
                                sourcesLoading = !hasSource && !noSources,
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
                            // 已是当前源时再按 OK：与主操作按钮一致——有记录则继续观看，否则播放（多集无记录走选集）
                            onPlaySelected = { if (state.selectedPlayRecord != null) onContinue() else onPlay() },
                            onSearch = { onGoToSearch(state.heroInfo?.title?.takeIf { it.isNotBlank() } ?: state.entry.title) },
                            searchFocusRequester = searchCtaFocus,
                            speedResults = state.speedResults,
                            speedTesting = state.speedTesting,
                            onSpeedTest = startSpeedTest,
                            onStopSpeedTest = stopSpeedTest,
                            speedTestFocusRequester = speedTestFocus,
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
                onDismiss = {
                    state = state.copy(episodePickerVisible = false)
                    // 弹窗里被聚焦的剧集 chip 随之销毁，详情页会丢焦；把焦点交回主操作按钮。
                    scope.launch {
                        kotlinx.coroutines.delay(50)
                        try { primaryFocus.requestFocus() } catch (_: Exception) {}
                    }
                },
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
