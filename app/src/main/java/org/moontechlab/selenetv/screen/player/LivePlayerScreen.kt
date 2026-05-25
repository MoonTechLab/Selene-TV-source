package org.moontechlab.selenetv.screen.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.moontechlab.selenetv.model.LiveChannel
import org.moontechlab.selenetv.service.MediaHttp

private const val LIVE_AUTO_HIDE_MS = 4_000L
private val LiveGreen = Color(0xFF4CAF50)

@Composable
fun LivePlayerScreen(launch: LiveLaunch, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val controller = remember { PlayerController(context, MediaHttp.dataSourceFactory(launch.source.ua)) }

    var currentChannel by remember { mutableStateOf(launch.channel) }
    var controlsVisible by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }

    val osdFocus = remember { FocusRequester() }
    val panelTabFocus = remember { FocusRequester() }
    val isPlaying = controller.isPlaying

    fun wake() {
        controlsVisible = true
        interactionTick++
    }

    fun togglePlay() {
        if (!controller.isPlaying) {
            controller.exo.seekToDefaultPosition()
        }
        controller.togglePlay()
        wake()
    }

    fun openPanel() {
        controlsVisible = true
        panelOpen = true
    }

    fun pickChannel(channel: LiveChannel) {
        currentChannel = channel
        controller.load(channel.url, 0)
        panelOpen = false
        wake()
    }

    LaunchedEffect(Unit) {
        controller.load(currentChannel.url, 0)
    }

    LaunchedEffect(Unit) {
        while (true) {
            controller.syncProgress()
            delay(500)
        }
    }

    LaunchedEffect(controller.error) {
        if (controller.error != null) {
            controlsVisible = true
            panelOpen = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    LaunchedEffect(controlsVisible, isPlaying, panelOpen, interactionTick) {
        if (controlsVisible && isPlaying && !panelOpen) {
            delay(LIVE_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    LaunchedEffect(Unit) {
        repeat(8) {
            delay(70)
            try { osdFocus.requestFocus() } catch (_: Exception) {}
        }
    }

    BackHandler(enabled = panelOpen) { panelOpen = false }
    BackHandler(enabled = !panelOpen) { onExit() }

    Box(modifier = modifier.fillMaxSize().background(Color.Black).focusGroup()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = controller.exo
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val dim by animateFloatAsState(if (panelOpen) 0.5f else 0f, tween(280), label = "live_dim")
        if (dim > 0f) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        }

        if (controller.isBuffering) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = Color.White)
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
                Text(
                    text = "频道播放失败，请选择其他频道",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W600,
                )
            }
        }

        val ctrlAlpha by animateFloatAsState(
            targetValue = if (controlsVisible) 1f else 0f,
            animationSpec = tween(if (controlsVisible) 220 else 280),
            label = "live_ctrl_alpha",
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
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to Color(0x99000000),
                            1f to Color(0xCC000000),
                        ),
                    )
                    .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = if (panelOpen) 2.dp else 18.dp),
            ) {
                LiveOsd(
                    channelName = currentChannel.name,
                    controlsVisible = controlsVisible,
                    focusRequester = osdFocus,
                    onFocused = { panelOpen = false },
                    onWake = { wake() },
                    onToggle = { togglePlay() },
                    onOpenPanel = { openPanel() },
                )
            }

            if (panelOpen) {
                LiveChannelPanel(
                    groups = launch.groups,
                    currentChannelId = currentChannel.id,
                    userAgent = MediaHttp.resolveLiveUa(launch.source.ua),
                    onPickChannel = ::pickChannel,
                    osdFocus = osdFocus,
                    activeTabFocus = panelTabFocus,
                )
            }
        }
    }
}

@Composable
private fun LiveOsd(
    channelName: String,
    controlsVisible: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onWake: () -> Unit,
    onToggle: () -> Unit,
    onOpenPanel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .focusProperties { up = FocusRequester.Cancel }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (!controlsVisible) {
                    if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) {
                        onToggle()
                    } else {
                        onWake()
                    }
                    return@onKeyEvent true
                }
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        onToggle()
                        true
                    }
                    Key.DirectionDown -> {
                        onOpenPanel()
                        true
                    }
                    else -> {
                        onWake()
                        false
                    }
                }
            }
            .focusable(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = channelName,
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.W700,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(22.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.10f))
                .padding(horizontal = 13.dp, vertical = 7.dp),
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(LiveGreen))
            Text(text = "LIVE", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W800)
        }
    }
}
