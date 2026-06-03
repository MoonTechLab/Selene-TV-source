package org.moontechlab.selenetv.screen.player

import org.moontechlab.selenetv.model.DanmakuComment
import org.moontechlab.selenetv.service.danmaku.DanmakuRenderSettings
import org.moontechlab.selenetv.ui.danmaku.DanmakuEngineConfig
import org.moontechlab.selenetv.ui.danmaku.DanmakuItem
import org.moontechlab.selenetv.ui.danmaku.DanmakuMode

/** DanmakuComment → 引擎 DanmakuItem。color 取低 24 位；id = timeMs 与内容散列，与 PlayerScreen 去重口径一致。 */
fun DanmakuComment.toEngineItem(): DanmakuItem = DanmakuItem(
    id = timeMs * 31 + content.hashCode(),
    timeMs = timeMs,
    text = content,
    mode = when (mode) {
        DanmakuComment.MODE_TOP -> DanmakuMode.TOP
        DanmakuComment.MODE_BOTTOM -> DanmakuMode.BOTTOM
        else -> DanmakuMode.SCROLL
    },
    colorRgb = (color and 0xFFFFFFL).toInt(),
)

/** 渲染设置 + 屏幕密度 → 引擎配置。densityPct 在数据层抽样，不进 config。 */
fun buildEngineConfig(settings: DanmakuRenderSettings, density: Float, visible: Boolean): DanmakuEngineConfig =
    DanmakuEngineConfig(
        rollingDurationMs = (DanmakuRenderSettings.BASE_DURATION_MS / settings.speed).toLong(),
        textSizePx = 24f * density * settings.fontScale,
        opacity = settings.opacity,
        antiOverlap = settings.antiOverlap,
        visible = visible,
    )
