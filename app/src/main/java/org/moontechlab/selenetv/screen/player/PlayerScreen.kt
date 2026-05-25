package org.moontechlab.selenetv.screen.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import org.moontechlab.selenetv.screen.detail.sourceKey
import org.moontechlab.selenetv.util.getImageUrlSync

private const val SEEK_STEP_MS = 10_000L
private const val AUTO_HIDE_MS = 4_000L

/**
 * 全屏播放页（UI 外壳），布局参照 Apple TV 播放器：
 * 全幅画面 + 底部极简控制（大标题、纯白细进度条、左侧已播时间、右侧「结束时刻 · 剩余」）。
 *
 * 选集 / 换源 / 详情统一收进「进度条按下键唤出」的上拉标签面板（默认详情，详情含「下一集」快捷入口）。
 * 面板上滑时把进度条整体上顶；焦点向上回到进度条时面板自动收起。
 *
 * 舞台为 Media3 ExoPlayer（PlayerView），position/duration/播放态由 [PlayerController] 驱动；
 * 控制层 / 面板 / 焦点模型保持不变。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(launch: PlayerLaunch, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { PlayerController(context) }
    val reporter = remember { PlayProgressReporter() }

    var controlsVisible by remember { mutableStateOf(true) }
    var currentEpisode by remember { mutableIntStateOf(launch.episodeIndex) }
    var currentSourceKey by remember { mutableStateOf(launch.selectedSourceKey) }
    var panelOpen by remember { mutableStateOf(false) }
    var panelTab by remember { mutableStateOf(PanelTab.Detail) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var flashTick by remember { mutableIntStateOf(0) }
    var flashVisible by remember { mutableStateOf(false) }

    // 播放状态全部来自 ExoPlayer
    val isPlaying = controller.isPlaying
    val positionMs = controller.positionMs
    val durationMs = if (controller.durationMs > 0) controller.durationMs else launch.durationMs
    val currentSource = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
    val episodes = currentSource?.episodes ?: emptyList()
    val episodesTitles = currentSource?.episodesTitles
    val epCount = episodes.size.coerceAtLeast(1)
    val hasNext = currentEpisode < epCount - 1

    fun wake() { controlsVisible = true; interactionTick++ }
    fun togglePlay() {
        // 即将从「播放」切到「暂停」时强制保存一次（spec：暂停为保存时机）
        if (controller.isPlaying) {
            val src = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
            src?.let { reporter.save(it, launch.title, currentEpisode, controller.positionMs, controller.durationMs, force = true) }
        }
        controller.togglePlay(); flashTick++; wake()
    }
    fun openPanel() { controlsVisible = true; panelTab = PanelTab.Detail; panelOpen = true }

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
            val src = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
            src?.let { reporter.save(it, launch.title, currentEpisode, positionMs, durationMs, force = true) }
            reporter.resetThrottle()
            currentEpisode++
            panelOpen = false
            playEpisode(currentEpisode, 0)
            wake()
        }
    }

    val scrubberFocus = remember { FocusRequester() }
    val panelTabFocus = remember { FocusRequester() }

    // 首帧载入起播集（带进入时的续看位置）
    LaunchedEffect(Unit) {
        playEpisode(currentEpisode, launch.startPositionMs.coerceAtLeast(0))
    }

    // 周期把 ExoPlayer 位置同步到 State
    LaunchedEffect(Unit) {
        while (true) {
            controller.syncProgress()
            kotlinx.coroutines.delay(500)
        }
    }
    // 10s 定时保存
    LaunchedEffect(currentEpisode, currentSourceKey) {
        while (true) {
            kotlinx.coroutines.delay(10_000)
            val src = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
            val liveDuration = if (controller.durationMs > 0) controller.durationMs else launch.durationMs
            src?.let { reporter.save(it, launch.title, currentEpisode, controller.positionMs, liveDuration, force = false) }
        }
    }

    // 退出 / 离开时释放播放器
    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    // OSD 自动隐藏：播放中、面板未开、静止满 4s 即隐去
    LaunchedEffect(controlsVisible, isPlaying, panelOpen, interactionTick) {
        if (controlsVisible && isPlaying && !panelOpen) {
            kotlinx.coroutines.delay(AUTO_HIDE_MS)
            controlsVisible = false
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

    // BACK：先关面板，再退出播放页
    BackHandler(enabled = panelOpen) { panelOpen = false }
    BackHandler(enabled = !panelOpen) {
        val src = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
        src?.let { reporter.save(it, launch.title, currentEpisode, positionMs, durationMs, force = true) }
        onExit()
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black).focusGroup()) {

        // 真实视频画面：海报背景垫底，PlayerView 叠在上层
        StagePlaceholder(poster = launch.heroInfo?.poster?.takeIf { it.isNotEmpty() } ?: launch.sources.firstOrNull()?.poster)
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = controller.exo
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 面板打开时压暗画面
        val dim by animateFloatAsState(if (panelOpen) 0.5f else 0f, tween(280), label = "dim")
        if (dim > 0f) Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))

        PlayPauseFlash(visible = flashVisible, isPlaying = isPlaying)

        // ── 底部：控制条 + 上拉面板。面板出现时整列变高、底部对齐 → 把控制条上顶。 ──
        val ctrlAlpha by animateFloatAsState(
            targetValue = if (controlsVisible) 1f else 0f,
            animationSpec = tween(if (controlsVisible) 220 else 280),
            label = "ctrl_alpha",
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(280)),
        ) {
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
                    // 折叠态底距更小，让控制条整体下沉、指示文案更贴近底部
                    .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = if (panelOpen) 2.dp else 10.dp),
            ) {
                Column {
                    Text(
                        text = launch.title + (if (epCount > 1) "  ·  第 ${currentEpisode + 1} 集" else ""),
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.W700,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    PlayerScrubber(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        controlsVisible = controlsVisible,
                        onSeek = { controller.seekTo(it); wake() },
                        onToggle = { togglePlay() },
                        onWake = { wake() },
                        onOpenPanel = { openPanel() },
                        onFocused = { panelOpen = false },
                        focusRequester = scrubberFocus,
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
                    // 下拉提示（面板未开时）
                    if (!panelOpen) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
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

            if (panelOpen) {
                PlayerPanel(
                    title = launch.title,
                    heroInfo = launch.heroInfo,
                    epCount = epCount,
                    episodes = episodes,
                    episodesTitles = episodesTitles,
                    currentEpisode = currentEpisode,
                    sources = launch.sources,
                    currentSourceKey = currentSourceKey,
                    hasNext = hasNext,
                    tab = panelTab,
                    onTabChange = { panelTab = it },
                    onNextEpisode = { goNext() },
                    onPickEpisode = { idx -> currentEpisode = idx; panelOpen = false; wake() },
                    onPickSource = { key ->
                        if (key != currentSourceKey) { currentSourceKey = key; currentEpisode = 0 }
                        panelOpen = false; wake()
                    },
                    scrubberFocus = scrubberFocus,
                    activeTabFocus = panelTabFocus,
                )
            }
        }
    }
}

// ───────────────────────────── 舞台占位 ─────────────────────────────

@Composable
private fun StagePlaceholder(poster: String?) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF050505))) {
        if (poster != null) {
            AsyncImage(
                model = getImageUrlSync(poster),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(40.dp).graphicsLayer { alpha = 0.32f },
                contentScale = ContentScale.Crop,
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
    }
}

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
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val active = focused && controlsVisible
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
            .focusProperties { up = FocusRequester.Cancel }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (!controlsVisible) {
                    if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) onToggle() else onWake()
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
