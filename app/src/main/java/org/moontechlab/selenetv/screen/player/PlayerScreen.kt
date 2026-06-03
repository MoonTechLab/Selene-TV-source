package org.moontechlab.selenetv.screen.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import org.moontechlab.selenetv.screen.detail.sourceKey

private const val SEEK_STEP_MS = 10_000L
private const val AUTO_HIDE_MS = 5_000L
// 二次返回退出的确认窗口：首按弹 toast，窗口内再按才退出
private const val BACK_EXIT_WINDOW_MS = 2_000L

/**
 * 全屏播放页（UI 外壳），布局参照 Apple TV 播放器：
 * 全幅画面 + 底部极简控制（大标题、纯白细进度条、左侧已播时间、右侧「结束时刻 · 剩余」）。
 *
 * 选集 / 换源 / 详情统一收进「进度条按下键唤出」的上拉标签面板（默认详情，详情含「下一集」快捷入口）。
 * 面板上滑时把进度条整体上顶；焦点向上回到进度条时面板自动收起。
 *
 * 舞台为可插拔的播放器后端（[VideoPlayer]），position/duration/播放态由 [PlayerController] 驱动；
 * 控制层 / 面板 / 焦点模型保持不变。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(launch: PlayerLaunch, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // 播放页现处于独立 Dialog 窗口（DetailScreen 用 Dialog 隔离焦点域，见 a54b86b），其按键不经
    // MainActivity.dispatchKeyEvent，故 5021ed4 的 Activity 级 BACK 自管对这里失效。BackHandler 绑定的是
    // Dialog 自身的 onBackPressedDispatcher，下方 root 直接拦截 BACK 手动驱动它（见 onPreviewKeyEvent 注释）。
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val controller: VideoPlayer = remember { PlayerFactory.create(context) }
    val reporter = remember { PlayProgressReporter() }

    // 播放页处于独立 Dialog 窗口（见 DetailScreen 用 Dialog 隔离焦点域）。Compose Dialog 在
    // usePlatformDefaultWidth=false 下只把窗口宽度设为 MATCH_PARENT，高度仍按内容测量——真机上该高度会
    // 短于屏幕（窗口顶对齐），底部留出一条空隙，透出下层详情页内容（模拟器恰好把窗口测满，故难复现）。
    // 这里把 Dialog 窗口高度强制为 MATCH_PARENT，让其铺满整屏、消除底部空隙，从根上杜绝漏出。
    val dialogView = LocalView.current
    DisposableEffect(dialogView) {
        (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )
        onDispose {}
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var currentEpisode by remember { mutableIntStateOf(launch.episodeIndex) }
    var currentSourceKey by remember { mutableStateOf(launch.selectedSourceKey) }
    var panelOpen by remember { mutableStateOf(false) }
    var panelTab by remember { mutableStateOf(PanelTab.Detail) }
    // 选集/换源 内容区按「上」回标签栏的中转标志。选集网格 = LazyHorizontalGrid、换源 = LazyRow，
    // Lazy 容器会困住其子项的焦点：子项内 requestFocus / moveFocus / focusProperties(up,exit) 一律失败
    // （向外的 requestFocus 恒返回 false），唯独 clearFocus 能解困、但会让焦点回落到进度条而收起面板。
    // 故：清焦解困 → 期间抑制进度条的「收起面板」→ 再从网格外把焦点确定交回标签栏（与面板首次打开同理）。
    var routeContentUpToTab by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var flashTick by remember { mutableIntStateOf(0) }
    var flashVisible by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(launch.sources) }
    var speedTesting by remember { mutableStateOf(false) }
    var speedResults by remember { mutableStateOf<Map<String, org.moontechlab.selenetv.model.SourceSpeed>>(emptyMap()) }
    var speedTestJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 播放倍速 + 唤出弹窗状态。speed 变化 → 推到 controller（ExoPlayer/MPV 各自原生 API）。
    var playbackSpeed by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var speedPopupOpen by remember { mutableStateOf(false) }
    LaunchedEffect(playbackSpeed) { controller.speed = playbackSpeed }

    var resizeMode by remember { mutableStateOf(VideoResizeMode.FIT) }
    var aspectPopupOpen by remember { mutableStateOf(false) }
    LaunchedEffect(resizeMode) { controller.resizeMode = resizeMode }

    // 播放状态全部来自播放器后端（VideoPlayer）
    val isPlaying = controller.isPlaying
    val positionMs = controller.positionMs
    val durationMs = if (controller.durationMs > 0) controller.durationMs else launch.durationMs
    val currentSource = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
    val episodes = currentSource?.episodes ?: emptyList()
    val episodesTitles = currentSource?.episodesTitles
    val epCount = episodes.size.coerceAtLeast(1)
    val hasNext = currentEpisode < epCount - 1

    var segments by remember { mutableStateOf<List<org.moontechlab.selenetv.model.SkipSegment>>(emptyList()) }
    val activeSegment = remember(segments, positionMs, durationMs) {
        org.moontechlab.selenetv.service.IntroSkipService.pickActiveSegment(segments, positionMs, durationMs)
    }

    // 弹幕（Phase 2：多源聚合，形态 C）。开关由设置项 danmakuEnabled 控制。
    // 进页/切集只并发跑 search 得命中源（danmakuMatches），弹幕正文按选中源（selectedDanmakuKey）按需拉取，
    // 已拉取的按 key 存内存（danmakuCommentsByKey，每集重建），切源零延迟。
    // 弹幕默认模式（off/manual/auto，设置页配）：决定是否 probe/选源/显示弹幕键。播放期间不变。
    val danmakuMode = remember { org.moontechlab.selenetv.service.AppDataService.getDanmakuModeSync() }
    val danmakuActive = danmakuMode != "off"           // 模式非 off：跑 probe、显示弹幕键
    // 弹幕显示区域（off/third/half/full，播放页弹窗里可改）；off=暂时关闭。区域决定弹幕层占屏高度。
    var danmakuArea by remember { mutableStateOf(org.moontechlab.selenetv.service.AppDataService.getDanmakuAreaSync()) }
    // 上次非 off 区域：弹幕「开关」一键开启时恢复到此（记忆用户偏好）。
    var danmakuLastArea by remember { mutableStateOf(org.moontechlab.selenetv.service.AppDataService.getDanmakuLastAreaSync()) }
    val danmakuEnabled = danmakuActive && danmakuArea != "off"   // 真正上屏：模式开 且 区域非 off
    var danmakuMatches by remember {
        mutableStateOf<List<org.moontechlab.selenetv.service.DanmakuService.SourceMatch>>(emptyList())
    }
    var selectedDanmakuKey by remember { mutableStateOf<String?>(null) }
    // 每集重建：key → 该源已拉取的弹幕列表
    val danmakuCommentsByKey = remember(currentEpisode) {
        androidx.compose.runtime.mutableStateMapOf<String, List<org.moontechlab.selenetv.model.DanmakuComment>>()
    }
    // 每集重建：源 key → 该源当前生效剧集 mediaId（默认最匹配；用户可在二级菜单改用其它候选）
    val danmakuActiveMediaByKey = remember(currentEpisode) {
        androidx.compose.runtime.mutableStateMapOf<String, String>()
    }
    // 每集重建：分片滑动加载状态——源 key → 已加载块下标集合 / 去重键集合（跟随播放进度连续加载相邻块）
    val danmakuLoadedChunks = remember(currentEpisode) { mutableMapOf<String, MutableSet<Int>>() }
    val danmakuSeen = remember(currentEpisode) { mutableMapOf<String, HashSet<Long>>() }
    // 每集重建：一键统计填充——"源key|mediaId" → 该段抓到的条数（仅统计，不影响渲染源）
    val danmakuCandidateCounts = remember(currentEpisode) {
        androidx.compose.runtime.mutableStateMapOf<String, Int>()
    }
    val danmakuComments = danmakuCommentsByKey[selectedDanmakuKey] ?: emptyList()
    // 弹幕渲染设置（不透明度/速度/字号/密度/防重叠）；弹窗内调，实时应用 + 持久化。
    var danmakuSettings by remember {
        mutableStateOf(org.moontechlab.selenetv.service.AppDataService.getDanmakuRenderSettingsSync())
    }
    // 打开弹窗前是否在播放——关闭弹窗时据此恢复播放。
    var danmakuWasPlaying by remember { mutableStateOf(false) }
    // 弹幕统一菜单（区域 + 源）开合状态。
    var danmakuMenuOpen by remember { mutableStateOf(false) }

    fun wake() { controlsVisible = true; interactionTick++ }

    // 统一保存入口：仅在本源成功起播过（hasStarted）时才落记录——死链/未起播不存。
    fun saveProgress(force: Boolean) {
        if (!controller.hasStarted) return
        val src = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
        val dur = if (controller.durationMs > 0) controller.durationMs else launch.durationMs
        src?.let { reporter.save(it, launch.title, currentEpisode, controller.positionMs, dur, force = force) }
    }

    fun togglePlay() {
        // 即将从「播放」切到「暂停」时强制保存一次（spec：暂停为保存时机）
        if (controller.isPlaying) saveProgress(force = true)
        controller.togglePlay(); flashTick++; wake()
    }
    fun openPanel() { controlsVisible = true; panelTab = PanelTab.Detail; panelOpen = true }

    // 优选测速:并发测速 → 实时按分重排面板 sources → 每源出分;完成「只重排不切源」(不动 currentSourceKey)。
    val startSpeedTest: () -> Unit = handler@{
        if (speedTesting) return@handler
        val testable = sources.filter { org.moontechlab.selenetv.service.SpeedTestService.testUrl(it) != null }
        if (testable.isEmpty()) return@handler
        val seed = testable.associate {
            sourceKey(it) to org.moontechlab.selenetv.model.SourceSpeed(org.moontechlab.selenetv.model.SpeedTestState.TESTING)
        }
        speedResults = seed
        speedTesting = true
        speedTestJob = scope.launch {
            val jobScope = this
            val acc = java.util.concurrent.ConcurrentHashMap(seed)
            try {
                org.moontechlab.selenetv.service.SpeedTestService.testAll(testable) { key, result ->
                    acc[key] = result
                    jobScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        val snap = HashMap(acc)
                        val maxSpeed = org.moontechlab.selenetv.service.SpeedTestService.maxSpeed(snap.values)
                        sources = sources.sortedByDescending {
                            org.moontechlab.selenetv.service.SpeedTestService.rank(snap[sourceKey(it)], maxSpeed)
                        }
                        speedResults = snap
                    }
                }
                val maxSpeed = org.moontechlab.selenetv.service.SpeedTestService.maxSpeed(acc.values)
                sources = sources.sortedByDescending {
                    org.moontechlab.selenetv.service.SpeedTestService.rank(acc[sourceKey(it)], maxSpeed)
                }
                speedResults = HashMap(acc)
                speedTesting = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                speedResults = HashMap(acc.filterValues { it.state != org.moontechlab.selenetv.model.SpeedTestState.TESTING })
                speedTesting = false
                throw e
            }
        }
    }
    val stopSpeedTest: () -> Unit = { speedTestJob?.cancel() }

    // 该 source+id 的播放记录（用于逐集续看：记录的 index 与目标集吻合时恢复其位置）
    var sourceRecord by remember { mutableStateOf<org.moontechlab.selenetv.model.PlayRecord?>(null) }
    LaunchedEffect(currentSourceKey) {
        val src = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
        sourceRecord = if (src != null) {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    org.moontechlab.selenetv.data.Repositories.userData().getPlayRecords()["${src.source}+${src.id}"]
                }
            } catch (_: Exception) { null }
        } else null
    }

    // 目标集的续看起点（毫秒）：记录 index 命中该集才用，否则 0
    fun resumeForEpisode(index: Int): Long {
        val rec = sourceRecord ?: return 0L
        return if (rec.index - 1 == index) rec.playTime * 1000 else 0L
    }

    // 载入指定集；startMs 为续播位置（切集默认 0，进入时用 launch.startPositionMs）
    fun playEpisode(index: Int, startMs: Long) {
        val src = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
        val raw = src?.episodes?.getOrNull(index) ?: return
        scope.launch {
            val proxy = org.moontechlab.selenetv.service.AppDataService.getM3u8ProxyUrl()
            controller.load(PlayProgress.buildPlayUrl(raw, proxy), startMs)
        }
    }

    fun goNext() {
        if (hasNext) {
            saveProgress(force = true)
            reporter.resetThrottle()
            currentEpisode++
            panelOpen = false
            playEpisode(currentEpisode, 0)
            wake()
        }
    }

    val scrubberFocus = remember { FocusRequester() }
    val panelTabFocus = remember { FocusRequester() }
    val speedPillFocus = remember { FocusRequester() }
    val aspectPillFocus = remember { FocusRequester() }
    val danmakuPillFocus = remember { FocusRequester() }   // 弹幕开关段（主）
    val danmakuGearFocus = remember { FocusRequester() }   // 弹幕齿轮段（进设置）
    val skipFocus = remember { FocusRequester() }
    var skipFocused by remember { mutableStateOf(false) }
    // 焦点是否落在按钮行（弹幕/比例/倍速 等 pill）：为真时抑制 OSD 自动隐藏，避免操作途中控制条消失。
    var controlsRowHasFocus by remember { mutableStateOf(false) }

    // 首帧载入起播集（带进入时的续看位置）
    LaunchedEffect(Unit) {
        playEpisode(currentEpisode, launch.startPositionMs.coerceAtLeast(0))
    }

    // 周期把播放器后端位置同步到 State
    LaunchedEffect(Unit) {
        while (true) {
            controller.syncProgress()
            kotlinx.coroutines.delay(500)
        }
    }
    // 每集拉取跳过段（best-effort，失败空列表）
    LaunchedEffect(currentEpisode, currentSourceKey, launch.tmdbRef) {
        val ref = launch.tmdbRef
        segments = if (ref == null) emptyList()
        else runCatching {
            org.moontechlab.selenetv.service.IntroSkipService.fetchSegments(
                ref,
                currentEpisode + 1,
                org.moontechlab.selenetv.service.DoubanCacheService.getInstance(context),
            )
        }.getOrDefault(emptyList())
    }
    // 加载某源「当前播放进度附近」尚未加载的分块（当前块 + 下一块），增量并入累积列表（去重）。
    suspend fun loadNearChunks(key: String) {
        val m = danmakuMatches.firstOrNull { it.key == key } ?: return
        val mediaId = danmakuActiveMediaByKey[key] ?: m.activeMediaId
        val cur = org.moontechlab.selenetv.service.DanmakuService.chunkOf((controller.positionMs / 1000).toInt())
        val loaded = danmakuLoadedChunks.getOrPut(key) { mutableSetOf() }
        val seen = danmakuSeen.getOrPut(key) { HashSet() }
        for (c in intArrayOf(cur, cur + 1)) {
            if (c < 0 || !loaded.add(c)) continue   // add 返回 false = 已加载，跳过
            val win = runCatching {
                org.moontechlab.selenetv.service.DanmakuService.fetchChunk(context, key, mediaId, currentEpisode + 1, c)
            }.getOrDefault(emptyList())
            if (win.isEmpty()) continue
            val acc = (danmakuCommentsByKey[key] ?: emptyList()).toMutableList()
            for (cm in win) if (seen.add(cm.timeMs * 31 + cm.content.hashCode().toLong())) acc.add(cm)
            danmakuCommentsByKey[key] = acc
        }
    }
    // 统计各源各候选「当前块」条数（源间并发、源内串行），填充 danmakuCandidateCounts。仅统计，不改选中源/生效剧集。
    suspend fun countAllSources() {
        val ep = currentEpisode + 1
        val chunk = org.moontechlab.selenetv.service.DanmakuService.chunkOf((controller.positionMs / 1000).toInt())
        coroutineScope {
            danmakuMatches.map { m ->
                async {   // 源间并发
                    for (cand in m.candidates) {   // 源内串行
                        val n = runCatching {
                            org.moontechlab.selenetv.service.DanmakuService.fetchChunk(context, m.key, cand.mediaId, ep, chunk)
                        }.getOrDefault(emptyList()).size
                        danmakuCandidateCounts["${m.key}|${cand.mediaId}"] = n
                    }
                }
            }.awaitAll()
        }
    }
    // 每集：并发跑 search 得命中源（轻量），自动模式按弹幕量选源（统计各源后取最多）；
    // 分片源弹幕**跟随播放进度连续加载附近分块**（不再一次性拉全集）。不依赖 currentSourceKey——弹幕认的是剧名。
    LaunchedEffect(currentEpisode, danmakuActive) {
        if (!danmakuActive) {   // 模式 off：完全不请求，不留任何状态
            danmakuMatches = emptyList()
            selectedDanmakuKey = null
            danmakuCommentsByKey.clear(); danmakuActiveMediaByKey.clear()
            danmakuLoadedChunks.clear(); danmakuSeen.clear()
            return@LaunchedEffect
        }
        val matches = runCatching {
            org.moontechlab.selenetv.service.DanmakuService.probe(context, launch.title)
        }.getOrDefault(emptyList())
        danmakuMatches = matches
        matches.forEach { danmakuActiveMediaByKey[it.key] = it.activeMediaId }
        // 手动模式：probe 完不自动选源，等用户在弹窗里挑。
        if (danmakuMode != "auto" || matches.isEmpty()) { selectedDanmakuKey = null }
        else {
            // 自动：先统计各源各候选「当前块」条数，再选**弹幕最多**的源+剧集（全 0 则回落标题匹配分最高的首源）。
            countAllSources()
            val best = matches
                .flatMap { m -> m.candidates.map { c -> Triple(m.key, c.mediaId, danmakuCandidateCounts["${m.key}|${c.mediaId}"] ?: 0) } }
                .maxByOrNull { it.third }
            if (best != null && best.third > 0) {
                danmakuActiveMediaByKey[best.first] = best.second
                selectedDanmakuKey = best.first
                loadNearChunks(best.first)
            } else {
                selectedDanmakuKey = matches.first().key
            }
        }
        // 持续加载：跟随播放进度，定时补齐当前选中源进度附近的分块（手动模式下选中前为空，不拉）。
        while (isActive) {
            kotlinx.coroutines.delay(4000)
            selectedDanmakuKey?.let { loadNearChunks(it) }
        }
    }
    // 切换弹幕源：选中并立即加载该源进度附近分块（持续加载循环随后接管）。
    fun selectDanmakuSource(key: String) {
        selectedDanmakuKey = key
        scope.launch { loadNearChunks(key) }
    }
    // 二级菜单换剧集：改该源生效候选 → 清该源已加载/累积 → 选中并立即加载附近分块。
    fun selectDanmakuCandidate(key: String, mediaId: String) {
        danmakuActiveMediaByKey[key] = mediaId
        danmakuLoadedChunks.remove(key); danmakuSeen.remove(key); danmakuCommentsByKey.remove(key)
        selectedDanmakuKey = key
        scope.launch { loadNearChunks(key) }
    }
    // 10s 定时保存
    LaunchedEffect(currentEpisode, currentSourceKey) {
        while (true) {
            kotlinx.coroutines.delay(10_000)
            saveProgress(force = false)
        }
    }

    // 播放自然结束 → 自动下一集（最后一集则停止）
    DisposableEffect(currentEpisode, currentSourceKey, hasNext) {
        controller.onEnded = { if (hasNext) goNext() }
        onDispose { controller.onEnded = {} }
    }

    // 播放出错：唤出换源面板让用户改选其他源
    LaunchedEffect(controller.error) {
        if (controller.error != null) {
            controlsVisible = true
            panelTab = PanelTab.Sources
            panelOpen = true
        }
    }

    // App 退到后台：强制保存 + 暂停播放
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                saveProgress(force = true)
                controller.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 退出 / 离开时释放播放器
    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    // 播放/缓冲期间保持屏幕常亮，防止机器进入休眠/屏保；暂停或离开播放页后恢复系统息屏超时
    val keepScreenOnView = LocalView.current
    val keepScreenOn = controller.isPlaying || controller.isBuffering
    DisposableEffect(keepScreenOn) {
        keepScreenOnView.keepScreenOn = keepScreenOn
        onDispose { keepScreenOnView.keepScreenOn = false }
    }

    // OSD 自动隐藏：播放中、面板未开、倍速弹窗未开、静止满 4s 即隐去
    // 隐藏时把焦点收回进度条——避免焦点遗留在 pill 等隐藏元素上，下次按键时唤起路径不可预测
    LaunchedEffect(controlsVisible, isPlaying, panelOpen, speedPopupOpen, aspectPopupOpen, danmakuMenuOpen, controlsRowHasFocus, interactionTick) {
        // 焦点在按钮行（pill）时不自动隐藏——用户正在操作，控制条应常驻。
        if (controlsVisible && isPlaying && !panelOpen && !speedPopupOpen && !aspectPopupOpen && !danmakuMenuOpen && !controlsRowHasFocus) {
            kotlinx.coroutines.delay(AUTO_HIDE_MS)
            controlsVisible = false
            try { (if (activeSegment != null) skipFocus else scrubberFocus).requestFocus() } catch (_: Exception) {}
        }
    }

    // 控制条隐藏期间：段内→焦点借给跳过键，段外→还给进度条（重试以越过清焦延迟）
    LaunchedEffect(activeSegment != null, controlsVisible) {
        if (!controlsVisible) {
            val target = if (activeSegment != null) skipFocus else scrubberFocus
            repeat(5) {
                try { target.requestFocus() } catch (_: Exception) {}
                kotlinx.coroutines.delay(40)
            }
        } else if (activeSegment == null && skipFocused) {
            // 控制条可见时段刚结束：焦点仍滞留在已移出的跳过键上 → 显式还给进度条
            try { scrubberFocus.requestFocus() } catch (_: Exception) {}
        }
    }

    // 播放/暂停中央闪现
    LaunchedEffect(flashTick) {
        if (flashTick == 0) return@LaunchedEffect
        flashVisible = true
        kotlinx.coroutines.delay(650)
        flashVisible = false
    }

    // 挂载时把焦点落到进度条（覆盖层淡入期间需重试若干次；落定后用户接管，不再抢焦）
    LaunchedEffect(Unit) {
        repeat(8) {
            kotlinx.coroutines.delay(70)
            try { scrubberFocus.requestFocus() } catch (_: Exception) {}
        }
    }

    // 面板每次收起（BACK / 选集 / 换源 等任意路径）后，确定性把焦点交回进度条。
    // 防止「面板焦点项被移除→焦点孤立→下层详情内容抢焦」导致焦点穿透到覆盖层下方。
    LaunchedEffect(panelOpen) {
        if (panelOpen) return@LaunchedEffect
        repeat(6) {
            try { scrubberFocus.requestFocus() } catch (_: Exception) {}
            kotlinx.coroutines.delay(40)
        }
    }

    // 选集/换源 内容区按「上」：清焦已让焦点离开 Lazy 容器（落到进度条），此时进度条「收面板」被抑制；
    // 从容器外把焦点交回标签栏（重试以越过 clearFocus 的延迟清焦），落定后解除抑制。
    LaunchedEffect(routeContentUpToTab) {
        if (!routeContentUpToTab) return@LaunchedEffect
        var landed = false
        repeat(5) {
            kotlinx.coroutines.delay(16)
            if (!landed && runCatching { panelTabFocus.requestFocus() }.getOrDefault(false)) landed = true
        }
        routeContentUpToTab = false
    }

    // BACK 优先级（互斥）：① 面板开 → 收面板；② 控制条可见 → 仅隐藏控制条；
    // ③ 控制条已隐藏 → 二次确认退出（首按弹自绘提示，窗口内再按才退出）。
    var backExitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(backExitArmed) {
        if (backExitArmed) {
            kotlinx.coroutines.delay(BACK_EXIT_WINDOW_MS)
            backExitArmed = false
        }
    }
    // 倍速/比例/弹幕弹窗自有 dismissOnBackPress（独立 Popup window），这里全线让位
    BackHandler(enabled = panelOpen && !speedPopupOpen && !aspectPopupOpen && !danmakuMenuOpen) { panelOpen = false }
    BackHandler(enabled = !panelOpen && controlsVisible && !speedPopupOpen && !aspectPopupOpen && !danmakuMenuOpen) { controlsVisible = false }
    BackHandler(enabled = !panelOpen && !controlsVisible && !speedPopupOpen && !aspectPopupOpen && !danmakuMenuOpen) {
        if (backExitArmed) {
            saveProgress(force = true)
            onExit()
        } else {
            backExitArmed = true
        }
    }

    // 面板开合 / 控制条淡入淡出 / 压暗：全部改用手动 Animatable，并在 scaleFactor=1 的 MotionDurationScale 下驱动。
    // 原因：本页处于独立 Dialog 窗口（DetailScreen 用 Dialog 隔离焦点域，见 a54b86b），真机上该 Dialog 窗口会把
    // Compose 动画时长整体拉伸（同款 animateContentSize/tween 在 Activity 窗口的直播页正常、在本页极慢，暂停亦然），
    // 而默认动画走的是窗口自带的 MotionDurationScale。强制套用 scaleFactor=1 即可绕开拉伸，让开合恢复 280ms。
    val fixedMotionScale = remember {
        object : androidx.compose.ui.MotionDurationScale { override val scaleFactor: Float get() = 1f }
    }
    val panelReveal = remember { androidx.compose.animation.core.Animatable(0f) } // 0=收起 1=展开，替代 animateContentSize
    val dimAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    val ctrlAlphaAnim = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(panelOpen) {
        kotlinx.coroutines.withContext(fixedMotionScale) {
            kotlinx.coroutines.coroutineScope {
                launch { panelReveal.animateTo(if (panelOpen) 1f else 0f, tween(280)) }
                launch { dimAnim.animateTo(if (panelOpen) 0.5f else 0f, tween(280)) }
            }
        }
    }
    LaunchedEffect(controlsVisible) {
        kotlinx.coroutines.withContext(fixedMotionScale) {
            ctrlAlphaAnim.animateTo(if (controlsVisible) 1f else 0f, tween(if (controlsVisible) 220 else 280))
        }
    }
    // 跳过按钮的显隐淡入淡出：与控制条同理走 fixedMotionScale 手动 Animatable（Dialog 窗口会拉伸默认动画时长）。
    // 退场期间 activeSegment 已置空，故把上次的文案/段末位置记下来，撑住渲染直到 alpha 归零再消失。
    val skipAlphaAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    var lastSkipLabel by remember { mutableStateOf("") }
    var lastSkipEndMs by remember { mutableStateOf(0L) }
    LaunchedEffect(activeSegment) {
        val s = activeSegment
        if (s != null) {
            lastSkipLabel = when (s.type) {
                org.moontechlab.selenetv.model.SkipType.INTRO -> "跳过片头"
                org.moontechlab.selenetv.model.SkipType.RECAP -> "跳过回顾"
                org.moontechlab.selenetv.model.SkipType.OUTRO -> "跳过片尾"
            }
            lastSkipEndMs = s.endMs
        }
    }
    LaunchedEffect(activeSegment != null) {
        kotlinx.coroutines.withContext(fixedMotionScale) {
            skipAlphaAnim.animateTo(if (activeSegment != null) 1f else 0f, tween(if (activeSegment != null) 220 else 200))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // 返回键自管（修复真机播放页 Dialog 返回失效，复刻 5021ed4 的 Activity 级方案）：
            // 真机上播放页 Compose 子树会吞掉 BACK 的 ACTION_DOWN，致 Dialog 窗口未 startTracking、
            // 抬起不触发 onBackPressed → 三个 BackHandler 全收不到（模拟器分发不同，复现不了）。
            // 故在此直接拦截：吞掉 DOWN，UP 时手动调 Dialog 的 onBackPressedDispatcher，仍由下方分层
            // BackHandler（收面板/隐控制条/二次确认退出）调度——绕开对 startTracking 的依赖。
            .onPreviewKeyEvent { e ->
                if (e.key != Key.Back) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyUp && !e.nativeKeyEvent.isCanceled) {
                    backDispatcher?.onBackPressed()
                }
                true
            }
            .focusGroup()
            // 焦点陷阱：禁止任何方向把焦点带出播放页覆盖层、穿透到下层详情内容
            .focusProperties { exit = { FocusRequester.Cancel } },
    ) {

        controller.VideoSurface(Modifier.fillMaxSize())

        // 弹幕层：叠在画面之上、压暗/控制条之下。区域决定占屏高度（上对齐）：1/3 / 半屏 / 全屏。
        // 只要弹幕开启就常驻挂载（不再按 comments 是否为空卸载/重建）——避免每集开头空窗、切换间隙频繁
        // create/release DanmakuPlayer（accompanying #27 ActionThread 泄漏累积）。播放器按 datasetKey 重建：
        // 源/集/剧集变 → 旧的 release 干净回收 + 新的空数据集，天然不跨数据集累积。
        if (danmakuEnabled) {
            DanmakuOverlay(
                comments = danmakuComments,
                positionMs = positionMs,
                isPlaying = isPlaying,
                speed = playbackSpeed,
                visible = danmakuEnabled,
                settings = danmakuSettings,
                previewMode = danmakuMenuOpen,
                datasetKey = "${selectedDanmakuKey}|${currentEpisode}|${danmakuActiveMediaByKey[selectedDanmakuKey]}",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(danmakuAreaFraction(danmakuArea)),
            )
        }

        // 面板打开时压暗画面（手动 Animatable，scale=1，见上方说明）
        val dim = dimAnim.value
        if (dim > 0f) Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))

        PlayPauseFlash(visible = flashVisible, isPlaying = isPlaying)

        // 仅「载入新内容」（首播/切集/切源，hasStarted=false）时盖全屏月牙；
        // 拖进度/同片重缓冲（hasStarted=true）不遮挡画面，让用户看着原画面 seek。
        if (controller.isBuffering && !controller.hasStarted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 比详情页全屏加载更轻的径向压暗：播放画面在后，短暂缓冲不喧宾夺主
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.25f),
                                Color.Black.copy(alpha = 0.50f),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    org.moontechlab.selenetv.ui.MoonSpinner(size = 104.dp)
                    Text(
                        text = "正在缓冲",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 12.sp,
                        letterSpacing = 6.sp,
                    )
                }
            }
        } else if (controller.isBuffering) {
            // 拖进度 / 同片重缓冲（已起播）：仅居中一个轻量小月牙，不压暗、不遮挡画面，
            // 让用户看着原画面 seek，同时有「正在加载」的明确反馈。
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                org.moontechlab.selenetv.ui.MoonSpinner(size = 52.dp)
            }
        }
        if (controller.error != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(text = "此源播放失败，请尝试换源", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.W600)
            }
        }

        // ── 底部：控制条 + 上拉面板。面板出现时整列变高、底部对齐 → 把控制条上顶。 ──
        // ctrlAlpha：手动 Animatable，scale=1（见上方说明）。整列不再用 animateContentSize，
        // 改由面板容器高度按 panelReveal 逐帧伸缩，底部对齐使控制条随之上顶——同样走 scale=1 的驱动。
        val ctrlAlpha = ctrlAlphaAnim.value
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(),
        ) {
            // 控制条 + 跳过按钮叠放层：跳过按钮浮在控制条之上（不再作为独立行把控制条整体顶高到半屏），
            // 因而它不随控制条的 ctrlAlpha 淡出、常驻可见；垂直位置见下方浮层，随控制条显隐平滑迁移。
            Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = ctrlAlpha }
                    .background(
                        // 底边收到 0xCC，与面板顶端同值 → 衔接无硬边（面板背景 0xCC→0xF2 续接加深）
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to Color(0x99000000),
                            1f to Color(0xCC000000),
                        ),
                    )
                    // 折叠态底距更小，让控制条整体下沉、指示文案更贴近底部。
                    // 底距随 panelReveal 连续插值（开 2dp → 收 10dp），避免在动画端点处突跳。
                    .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 2.dp + 8.dp * (1f - panelReveal.value)),
            ) {
                Column {
                    // Alignment.Bottom：中文标题几乎无下伸部，字框底边 ≈ 基线。
                    // pill 底边对齐字框底边 → 视觉上贴在中文字符的下缘，且 pill 整体下沉。
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.onFocusChanged { controlsRowHasFocus = it.hasFocus },
                    ) {
                        Text(
                            text = launch.title + (if (epCount > 1) "  ·  第 ${currentEpisode + 1} 集" else ""),
                            color = Color.White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.W700,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        // 弹幕胶囊（仅在模式非 off 时显示）：源 + 区域 + 外观都在弹窗里。
                        if (danmakuActive) {
                            val danmakuPillLabel = when {
                                danmakuArea == "off" -> "弹幕 · 关"   // 暂时关闭
                                selectedDanmakuKey != null ->
                                    "弹幕 · " + org.moontechlab.selenetv.service.danmaku.DanmakuSources.displayOf(selectedDanmakuKey!!)
                                else -> "弹幕"   // 尚无选中源（手动/探测中/无源）
                            }
                            Box {
                                DanmakuPillDuo(
                                    label = danmakuPillLabel,
                                    enabled = danmakuArea != "off",
                                    // 开关段 OK：即时开/关弹幕，不暂停不弹窗。关→记住当前区域后置 off；开→恢复上次区域。
                                    onToggle = {
                                        if (danmakuArea == "off") {
                                            val restore = danmakuLastArea.takeIf { it != "off" } ?: "full"
                                            danmakuArea = restore
                                            scope.launch { org.moontechlab.selenetv.service.AppDataService.setDanmakuArea(restore) }
                                        } else {
                                            danmakuLastArea = danmakuArea
                                            val prev = danmakuArea
                                            danmakuArea = "off"
                                            scope.launch {
                                                org.moontechlab.selenetv.service.AppDataService.setDanmakuLastArea(prev)
                                                org.moontechlab.selenetv.service.AppDataService.setDanmakuArea("off")
                                            }
                                        }
                                        wake()
                                    },
                                    // 齿轮段 OK：打开设置弹窗 = 暂停播放（定格做预览底）；关闭时若原本在播放则恢复（见 onDismiss）。
                                    onOpenSettings = {
                                        speedPopupOpen = false; aspectPopupOpen = false
                                        danmakuWasPlaying = controller.isPlaying
                                        if (controller.isPlaying) controller.pause()
                                        danmakuMenuOpen = true; wake()
                                    },
                                    toggleFocus = danmakuPillFocus,
                                    gearFocus = danmakuGearFocus,
                                    scrubberFocus = scrubberFocus,
                                    rightTarget = aspectPillFocus,
                                    upTarget = if (activeSegment != null) skipFocus else null,
                                )
                                DanmakuSettingsDialog(
                                    open = danmakuMenuOpen,
                                    areaKey = danmakuArea,
                                    matches = danmakuMatches,
                                    selectedSourceKey = selectedDanmakuKey,
                                    counts = danmakuMatches.associate { it.key to danmakuCommentsByKey[it.key]?.size },
                                    activeMediaByKey = danmakuActiveMediaByKey,
                                    candidateCounts = danmakuCandidateCounts,
                                    settings = danmakuSettings,
                                    onFetchAllCounts = { scope.launch { countAllSources() } },
                                    // 选项均在弹窗内即时生效、不关窗（弹窗是配置中心，BACK 才关）。
                                    onSelectArea = {
                                        danmakuArea = it
                                        scope.launch {
                                            org.moontechlab.selenetv.service.AppDataService.setDanmakuArea(it)
                                            // 选到具体区域时同步记忆，供「开关」一键恢复。
                                            if (it != "off") {
                                                danmakuLastArea = it
                                                org.moontechlab.selenetv.service.AppDataService.setDanmakuLastArea(it)
                                            }
                                        }
                                    },
                                    onSelectCandidate = { key, mediaId -> selectDanmakuCandidate(key, mediaId) },
                                    onChangeSettings = { s ->
                                        danmakuSettings = s
                                        scope.launch { org.moontechlab.selenetv.service.AppDataService.setDanmakuRenderSettings(s) }
                                    },
                                    // 关闭弹窗：若打开前在播放则恢复播放。
                                    onDismiss = {
                                        danmakuMenuOpen = false
                                        if (danmakuWasPlaying && !controller.isPlaying) controller.togglePlay()
                                        wake()
                                    },
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Box {
                            AspectRatioPill(
                                currentMode = resizeMode,
                                onClick = { speedPopupOpen = false; danmakuMenuOpen = false; aspectPopupOpen = true; wake() },
                                focusRequester = aspectPillFocus,
                                scrubberFocus = scrubberFocus,
                                rightTarget = speedPillFocus,
                                leftTarget = if (danmakuActive) danmakuGearFocus else null,
                                upTarget = if (activeSegment != null) skipFocus else null,
                            )
                            AspectRatioPopup(
                                open = aspectPopupOpen,
                                currentMode = resizeMode,
                                onSelect = {
                                    resizeMode = it
                                    aspectPopupOpen = false
                                    wake()
                                },
                                onDismiss = { aspectPopupOpen = false },
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Box {
                            SpeedPill(
                                currentSpeed = playbackSpeed,
                                onClick = { aspectPopupOpen = false; danmakuMenuOpen = false; speedPopupOpen = true; wake() },
                                focusRequester = speedPillFocus,
                                scrubberFocus = scrubberFocus,
                                upTarget = if (activeSegment != null) skipFocus else null,
                                leftTarget = aspectPillFocus,
                            )
                            // SpeedPopup 内部用 MutableTransitionState 跟踪进出态，
                            // 退场动画期间自行续命挂载——不再用外层 if 直接拆装
                            SpeedPopup(
                                open = speedPopupOpen,
                                currentSpeed = playbackSpeed,
                                onSelect = {
                                    playbackSpeed = it
                                    speedPopupOpen = false
                                    wake()
                                },
                                onDismiss = { speedPopupOpen = false },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    PlayerScrubber(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        controlsVisible = controlsVisible,
                        onSeek = { controller.seekTo(it); wake() },
                        onToggle = { togglePlay() },
                        onWake = { wake() },
                        onOpenPanel = { openPanel() },
                        // 内容区「上」回标签栏的中转期间，进度条可能短暂获焦——此时不收面板，
                        // 且抑制进度条自身的「绽放」高亮，避免焦点在交回标签栏前肉眼可见地闪过进度条。
                        onFocused = { if (!routeContentUpToTab) panelOpen = false },
                        suppressActive = routeContentUpToTab,
                        focusRequester = scrubberFocus,
                        upTarget = speedPillFocus,   // 进度条「上」默认落到倍速按钮
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatTime(positionMs),
                            color = Color.White.copy(alpha = 0.95f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.W600,
                            modifier = Modifier.weight(1f),
                        )
                        val remaining = (durationMs - positionMs).coerceAtLeast(0)
                        Text(text = "结束 ${endClock(remaining)}", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, fontWeight = FontWeight.W500)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(text = "-${formatTime(remaining)}", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.W600)
                    }
                    // 下拉提示：高度随面板收起进度反向伸缩（reveal 1→0 时 0→满高 22dp），与收起动画严格同步、
                    // 自顶向下裁剪揭示，不在动画末尾突然撑高控制条造成跳变。控制条整体已套 ctrlAlpha 淡入淡出。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp * (1f - panelReveal.value))
                            .clipToBounds(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.35f),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "详情 · 选集 · 换源", color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp)
                        }
                    }
                }
            }
                // 跳过按钮浮层：垂直位置在「进度条高度（无控制条）↔ 略高于倍速按钮（有控制条）」间插值。
                // 用与控制条淡入淡出同源的 ctrlAlpha 驱动 → 迁移的时长/曲线与控制条出现/消失完全一致。
                // 面板展开时控制条内部整体下沉（底距 10→2、下拉提示 22→0，合计 30dp），故再按 panelReveal
                // 同步下移，使按钮在面板开合时仍贴齐进度条/倍速按钮。两个端点常量按 1080p 设计稿可微调。
                // 显隐走 skipAlphaAnim（fixedMotionScale 手动 Animatable）淡入淡出；退场期间用记住的文案/段末撑住渲染。
                if (activeSegment != null || skipAlphaAnim.value > 0.001f) {
                    val skipBottom =
                        androidx.compose.ui.unit.lerp(54.dp, 156.dp, ctrlAlpha) - 30.dp * panelReveal.value
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .graphicsLayer { alpha = skipAlphaAnim.value }
                            .padding(end = 56.dp, bottom = skipBottom),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        IntroSkipButton(
                            label = lastSkipLabel,
                            focusRequester = skipFocus,
                            speedPillFocus = speedPillFocus,
                            controlsVisible = controlsVisible,
                            onFocusChanged = { skipFocused = it },
                            onSkip = {
                                // 片头/回顾/片尾一律 seek 到本段末——片尾不在此跳下一集，
                                // 是否续集交回「本集自然播完 onEnded→goNext」既有逻辑。
                                controller.seekTo(minOf(lastSkipEndMs, durationMs))
                                wake()
                                try { scrubberFocus.requestFocus() } catch (_: Exception) {}
                            },
                            onWakeControls = {
                                wake()
                                try { scrubberFocus.requestFocus() } catch (_: Exception) {}
                            },
                        )
                    }
                }
            }

            // 收起动画期间仍需保留面板以便高度从满高平滑收到 0；故 panelOpen 或 reveal 未归零时都渲染。
            // 容器高度 = 满高 × reveal 并裁剪，顶部对齐 → 自顶向下揭示，与原 animateContentSize 的裁剪方向一致。
            if (panelOpen || panelReveal.value > 0.001f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PanelHeight * panelReveal.value)
                        .clipToBounds(),
                ) {
                PlayerPanel(
                    title = launch.title,
                    heroInfo = launch.heroInfo,
                    epCount = epCount,
                    episodes = episodes,
                    episodesTitles = episodesTitles,
                    currentEpisode = currentEpisode,
                    sources = sources,
                    currentSourceKey = currentSourceKey,
                    hasNext = hasNext,
                    tab = panelTab,
                    onTabChange = { panelTab = it },
                    onNextEpisode = { goNext() },
                    onPickEpisode = { idx ->
                        saveProgress(force = true)
                        reporter.resetThrottle()
                        currentEpisode = idx
                        panelOpen = false
                        playEpisode(idx, resumeForEpisode(idx))
                        wake()
                    },
                    onPickSource = { key ->
                        if (key != currentSourceKey) {
                            val oldSrc = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
                            // 与 mobile 一致：切源删除旧源记录，避免同一内容残留多条续看（进度带到新源继续）
                            oldSrc?.let { old ->
                                scope.launch {
                                    try {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            org.moontechlab.selenetv.data.Repositories.userData().deletePlayRecord(old.source, old.id)
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                            reporter.resetThrottle()
                            val carriedMs = controller.positionMs
                            val newSrc = launch.sources.firstOrNull { sourceKey(it) == key }
                            // 带进度续播：新源集数足够则保持集号与位置，否则回落到第 1 集 0 秒
                            val newEpisode = currentEpisode.coerceIn(0, (newSrc?.episodes?.lastIndex ?: 0).coerceAtLeast(0))
                            val startMs = if (newEpisode == currentEpisode) carriedMs else 0L
                            currentSourceKey = key
                            currentEpisode = newEpisode
                            playEpisode(newEpisode, startMs)
                        }
                        panelOpen = false; wake()
                    },
                    scrubberFocus = scrubberFocus,
                    activeTabFocus = panelTabFocus,
                    onContentExitUp = {
                        // 顺序要紧：先抬起抑制标志，再清焦。clearFocus 会同步把焦点丢给进度条并触发其
                        // onFocused，若此时标志未置位，进度条会立刻收起面板。置位在前 → onFocused 读到 true → 不收。
                        routeContentUpToTab = true
                        focusManager.clearFocus(force = true)
                    },
                    speedResults = speedResults,
                    speedTesting = speedTesting,
                    onSpeedTest = startSpeedTest,
                    onStopSpeedTest = stopSpeedTest,
                )
                }
            }
        }

        // 二次返回退出提示（自绘，不用系统 Toast）
        BackExitHint(
            visible = backExitArmed,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 130.dp),
        )
    }
}

// ───────────────────────────── 二次返回退出提示 ─────────────────────────────

/** 播放页通用的「再按一次返回退出」自绘提示（替代系统 Toast，风格与播放器一致）。 */
@Composable
internal fun BackExitHint(visible: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(180), label = "back_exit_hint")
    if (alpha <= 0f) return
    Box(modifier = modifier.graphicsLayer { this.alpha = alpha }) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xE6000000))
                .padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Text(text = "再按一次返回键退出", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.W600)
        }
    }
}

// ───────────────────────────── 舞台占位 ─────────────────────────────

// ───────────────────────────── 中央播放/暂停闪现 ─────────────────────────────

@Composable
private fun PlayPauseFlash(visible: Boolean, isPlaying: Boolean) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 120 else 420), label = "flash_a")
    val scale by animateFloatAsState(if (visible) 1f else 0.7f, tween(if (visible) 160 else 420), label = "flash_s")
    if (alpha <= 0f) return
    Box(modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .size(96.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(44.dp),
            )
        }
    }
}

// ───────────────────────────── 进度条（纯白） ─────────────────────────────

/**
 * Apple TV 风纯白进度条：细圆角轨道，已播放为白色，聚焦时播放头绽放并浮现时间气泡。
 * 左右键拖动，OK 播放/暂停，「下」唤出标签面板，「上」无目标（保持）。
 */
@Composable
private fun PlayerScrubber(
    positionMs: Long,
    durationMs: Long,
    controlsVisible: Boolean,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onWake: () -> Unit,
    onOpenPanel: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester,
    upTarget: FocusRequester = FocusRequester.Cancel,
    modifier: Modifier = Modifier,
    suppressActive: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    // suppressActive：内容区按「上」回标签栏的中转期间进度条会短暂获焦，此时不绽放高亮（见 onFocused 注释）。
    val active = focused && controlsVisible && !suppressActive
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val trackH by animateDpAsState(if (active) 6.dp else 4.dp, tween(160), label = "track_h")
    val knob by animateDpAsState(if (active) 18.dp else 0.dp, spring(dampingRatio = 0.55f, stiffness = 420f), label = "knob")
    val glow by animateFloatAsState(if (active) 1f else 0f, tween(180), label = "glow")

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .focusProperties { up = upTarget }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                // 返回键一律交给 BackHandler 处理：不在此唤醒控制条，否则会与「隐藏/退出」逻辑打架
                if (e.key == Key.Back) return@onKeyEvent false
                if (!controlsVisible) {
                    // 与 Netflix 一致：无控制条时任意键（含 OK）只唤起控制条，不直接暂停，
                    // 避免 OK 被「暂停」占用，为片头按钮预聚焦后的「OK=跳过」让路
                    onWake()
                    return@onKeyEvent true
                }
                when (e.key) {
                    Key.DirectionLeft -> { onSeek((positionMs - SEEK_STEP_MS).coerceAtLeast(0)); true }
                    Key.DirectionRight -> { onSeek((positionMs + SEEK_STEP_MS).coerceAtMost(durationMs)); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onToggle(); true }
                    Key.DirectionDown -> { onOpenPanel(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        val w = maxWidth
        Box(modifier = Modifier.fillMaxWidth().height(trackH).align(Alignment.Center).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
        Box(modifier = Modifier.width(w * fraction).height(trackH).align(Alignment.CenterStart).clip(CircleShape).background(Color.White))
        if (glow > 0f) {
            Box(modifier = Modifier.align(Alignment.CenterStart).offset(x = w * fraction - 14.dp).size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f * glow)))
        }
        if (knob > 0.dp) {
            Box(modifier = Modifier.align(Alignment.CenterStart).offset(x = w * fraction - knob / 2).size(knob).clip(CircleShape).background(Color.White))
        }
        if (active) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = w * fraction - 30.dp, y = (-26).dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.White),
            ) {
                Text(text = formatTime(positionMs), color = Color(0xFF0A0A0A), fontSize = 12.sp, fontWeight = FontWeight.W700, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
            }
        }
    }
}

// ───────────────────────────── 工具 ─────────────────────────────

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 由剩余时长推算播完的钟点时刻（如「结束 21:45」）。 */
private fun endClock(remainingMs: Long): String {
    val end = System.currentTimeMillis() + remainingMs
    return java.text.SimpleDateFormat("H:mm", java.util.Locale.getDefault()).format(java.util.Date(end))
}
