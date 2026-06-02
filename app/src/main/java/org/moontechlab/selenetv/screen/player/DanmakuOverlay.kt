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

/**
 * akdanmaku 弹幕叠加层。叠在 [VideoPlayer.VideoSurface] 之上、控制条之下。
 *
 * akdanmaku 播放器有自己的时钟：start 后内部按帧推进。本层用宿主 [positionMs] 做漂移校正
 * （差值过大才 seekTo），用 [isPlaying] 同步暂停/恢复，[speed] 同步倍速——避免每帧 seek 抖动。
 */
@Composable
fun DanmakuOverlay(
    comments: List<DanmakuComment>,
    positionMs: Long,
    isPlaying: Boolean,
    speed: Float,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val player = remember { DanmakuPlayer(SimpleRenderer()) }

    // 当前配置（可见性可变，故用 remember 持有可重赋的引用）。
    val baseConfig = remember {
        DanmakuConfig(
            textSizeScale = 1f,
            alpha = 0.85f,
            durationMs = 8000L,   // 滚动弹幕飞过屏幕的时长
            visibility = visible,
        )
    }

    AndroidView(
        factory = { ctx -> DanmakuView(ctx).also { player.bindView(it) } },
        modifier = modifier,
    )

    // 全量 item（按 comments 缓存）。暂停时只投喂 ≤当前位置 的子集。
    val items = remember(comments) { comments.map { it.toItemData(density) } }

    // 数据变化：重载并从当前位置起播。暂停态下只投喂「已到点」的弹幕（见下）。
    LaunchedEffect(items) {
        if (items.isNotEmpty()) {
            player.updateData(if (isPlaying) items else items.filter { it.position <= positionMs })
            player.start(baseConfig)
            player.seekTo(positionMs)
        }
    }

    // 暂停：把数据裁到 ≤当前位置——屏上/刚到点的弹幕继续飞完，后续（未来时间）的不装填、不发射；
    //       不暂停弹幕时钟，故在飞的能自然飞出屏幕后清空。
    // 恢复：恢复全量数据并对齐视频位置，后续弹幕随播放到点再发射。
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            player.updateData(items)
            player.seekTo(positionMs)
            player.start()
        } else {
            player.updateData(items.filter { it.position <= positionMs })
        }
    }

    // 倍速同步。
    LaunchedEffect(speed) { player.updatePlaySpeed(speed) }

    // 可见性同步（设置里关弹幕 / 临时隐藏）。
    LaunchedEffect(visible) {
        player.updateConfig(baseConfig.copy(visibility = visible))
    }

    // 漂移校正：每 2s 比对宿主位置与弹幕时钟，差值 >1.2s 才 seek，避免抖动。
    val pos by rememberUpdatedState(positionMs)
    val playing by rememberUpdatedState(isPlaying)
    LaunchedEffect(player) {
        while (true) {
            delay(2000)
            if (playing && kotlin.math.abs(player.getCurrentTimeMs() - pos) > 1200) {
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
