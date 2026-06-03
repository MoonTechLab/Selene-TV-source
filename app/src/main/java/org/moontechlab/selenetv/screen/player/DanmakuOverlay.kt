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
    datasetKey: Any = Unit,         // 数据集标识(源|集|剧集)：变则整块重建播放器(旧的 release 干净回收)，避免跨数据集累积；
                                    // 不变则同一播放器贯穿(含「空→有弹幕」的加载空窗)，杜绝频繁 create/release churn 与泄漏累积。
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.key(datasetKey) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    // 自定义 renderer：补齐 akdanmaku 缓存未命中（兜底直绘）时丢失的整体透明度（见 AlphaDanmakuRenderer）。
    val player = remember { DanmakuPlayer(AlphaDanmakuRenderer()) }
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

    // 增量装填（关键，闪退根因修复）：akdanmaku 的 updateData 是「追加」而非「替换」，且 DanmakuItem/Data
    // 未重写 equals，其内部 removeAll+addAll 去重按 identity 失效——若每次把整段（越来越长）重喂，sortedData 会
    // 无限累积 + 每帧 removeAll/sort 越来越重，单集持续播放几分钟后 OOM/ANR 闪退。
    // 故这里**每条弹幕只喂一次**：仅把尚未喂过的 id 交给 updateData（idSet 本就按 id 去重渲染，增量喂正合适）。
    val fedIds = remember { HashSet<Long>() }
    val started = remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(items) {
        if (items.isEmpty()) return@LaunchedEffect
        if (!started.value) {
            player.start(config)
            player.seekTo(positionMs)
            if (!animating) player.pause()   // 挂载时若非播放态：起播后立即暂停
            started.value = true
        }
        val newItems = items.filter { fedIds.add(it.danmakuId) }   // add 返回 true = 首次出现，只喂新增
        if (newItems.isNotEmpty()) player.updateData(newItems)
    }

    // 播放/暂停走引擎自身时钟（pause/start），不再用 updateData 重喂数据（重喂正是闪退根因）。
    LaunchedEffect(animating) {
        if (!started.value) return@LaunchedEffect
        if (animating) { player.seekTo(positionMs); player.start() } else { player.pause() }
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
        // 反射兜底 release，修正 akdanmaku 1.0.3 的 ActionThread 泄漏（见 DanmakuPlayerReleaser）。
        onDispose { DanmakuPlayerReleaser.release(player) }
    }
    } // key(datasetKey)
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
