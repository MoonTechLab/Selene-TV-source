package org.moontechlab.selenetv.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.render.SimpleRenderer
import com.kuaishou.akdanmaku.ui.DanmakuPlayer
import com.kuaishou.akdanmaku.ui.DanmakuView
import kotlinx.coroutines.delay
import org.moontechlab.selenetv.model.DanmakuComment
import org.moontechlab.selenetv.service.danmaku.DanmakuRenderSettings

/**
 * akdanmaku 弹幕叠加层。叠在 [VideoPlayer.VideoSurface] 之上、控制条之下。
 *
 * akdanmaku 播放器有自己的时钟：start 后内部按帧推进。本层用宿主 [positionMs] 做漂移校正
 * （差值过大才 seekTo），用 [isPlaying] 同步暂停/恢复，[speed] 同步倍速——避免每帧 seek 抖动。
 *
 * [settings] 控制外观/行为：不透明度→alpha、滚动速度→durationMs、字号→textSizeScale、防重叠→allowOverlap，
 * 均经 [DanmakuConfig.update*]+updateConfig 实时生效；显示密度在数据层抽样（防刷屏）。
 */
@Composable
fun DanmakuOverlay(
    comments: List<DanmakuComment>,
    positionMs: Long,
    isPlaying: Boolean,
    speed: Float,
    visible: Boolean,
    settings: DanmakuRenderSettings,
    previewMode: Boolean = false,   // 设置弹窗打开时：视频暂停但弹幕继续飞，做实时预览（速度/密度才看得见）
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val player = remember { DanmakuPlayer(SimpleRenderer()) }
    // 弹幕是否运转：真正在播 或 预览中。akdanmaku 滚动速度由 rollingDurationMs 决定（durationMs 仅顶/底停留时长）。
    val animating = isPlaying || previewMode

    val config = remember {
        DanmakuConfig(
            textSizeScale = settings.fontScale,
            alpha = settings.opacity,
            rollingDurationMs = (DanmakuRenderSettings.BASE_DURATION_MS / settings.speed).toLong(),
            allowOverlap = !settings.antiOverlap,
            visibility = visible,
        )
    }

    AndroidView(
        factory = { ctx -> DanmakuView(ctx).also { player.bindView(it) } },
        modifier = modifier,
    )

    // 显示密度：数据层按稳定散列抽样上屏（100% 全显；其余保留 densityPct% 的弹幕，防刷屏）。
    val items = remember(comments, settings.densityPct) {
        val pct = settings.densityPct
        comments
            .filter { pct >= 100 || (((it.timeMs * 31 + it.content.hashCode()).toInt() and 0x7fffffff) % 100) < pct }
            .map { it.toItemData(density) }
    }

    // 区分「全新数据集」(换集/换源/首次) 与「流式追加」(同集分段陆续补来)：
    // 前者要 start+seek 对齐当前位置；后者仅 updateData 增量上屏，避免每段重置造成跳帧。
    val prevFirstId = remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    val prevSize = remember { androidx.compose.runtime.mutableStateOf(0) }
    LaunchedEffect(items) {
        if (items.isEmpty()) { prevFirstId.value = null; prevSize.value = 0; return@LaunchedEffect }
        val firstId = items.first().danmakuId
        val fresh = prevFirstId.value != firstId || items.size < prevSize.value
        player.updateData(if (animating) items else items.filter { it.position <= positionMs })
        if (fresh) {
            player.start(config)
            player.seekTo(positionMs)
        }
        prevFirstId.value = firstId
        prevSize.value = items.size
    }

    // 运转/停：运转时投喂全量并起播（含预览）；停时裁到 ≤当前位置——屏上的飞完即清，不再发射。
    LaunchedEffect(animating) {
        if (animating) {
            player.updateData(items)
            player.seekTo(positionMs)
            player.start()
        } else {
            player.updateData(items.filter { it.position <= positionMs })
        }
    }

    // 视频倍速同步（与弹幕滚动速度设置无关：前者是播放速率，后者是 rollingDurationMs）。
    LaunchedEffect(speed) { player.updatePlaySpeed(speed) }

    // 渲染设置实时生效：改对应字段 → 触发对应 generation → updateConfig。
    LaunchedEffect(settings, visible) {
        config.alpha = settings.opacity; config.updateRender()
        config.textSizeScale = settings.fontScale; config.updateMeasure()
        config.rollingDurationMs = (DanmakuRenderSettings.BASE_DURATION_MS / settings.speed).toLong(); config.updateLayout()
        config.allowOverlap = !settings.antiOverlap; config.updateLayout()
        config.visibility = visible; config.updateVisibility()
        player.updateConfig(config)
    }

    // 漂移校正：每 2s 比对宿主位置与弹幕时钟，差值 >1.2s 才 seek。预览中（视频定格、弹幕自由飞）不校正，避免每 2s 拽回。
    val pos by rememberUpdatedState(positionMs)
    val playing by rememberUpdatedState(isPlaying)
    val preview by rememberUpdatedState(previewMode)
    LaunchedEffect(player) {
        while (true) {
            delay(2000)
            if (!preview && playing && kotlin.math.abs(player.getCurrentTimeMs() - pos) > 1200) {
                player.seekTo(pos)
            }
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }
}

/** 统一弹幕 → akdanmaku item。mode 1/4/5 与 akdanmaku 常量一一对应。color 十进制 RGB → ARGB。 */
private fun DanmakuComment.toItemData(density: Float): DanmakuItemData {
    val safeMode = when (mode) {
        DanmakuComment.MODE_BOTTOM -> DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM
        DanmakuComment.MODE_TOP -> DanmakuItemData.DANMAKU_MODE_CENTER_TOP
        else -> DanmakuItemData.DANMAKU_MODE_ROLLING
    }
    return DanmakuItemData(
        danmakuId = (timeMs * 31 + content.hashCode()).toLong(),
        position = timeMs,
        content = content,
        mode = safeMode,
        textSize = (24 * density).toInt().coerceAtLeast(18),
        textColor = 0xFF000000.toInt() or (color and 0xFFFFFFL).toInt(),
        score = 0,
        danmakuStyle = DanmakuItemData.DANMAKU_STYLE_NONE,
    )
}
