package org.moontechlab.selenetv.screen.player

import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.screen.detail.HeroInfo
import org.moontechlab.selenetv.screen.detail.sourceKey

/**
 * 播放页入口。与 [org.moontechlab.selenetv.screen.DetailEntry] 一样是内存覆盖层参数，
 * 直接携带详情页已解析好的对象，播放页本身不再发起任何网络请求。
 *
 * 注意：当前项目无内置播放器（无 Media3/ExoPlayer），舞台为占位实现、进度为模拟。
 * 真正接入播放器时，只需把 [PlayerScreen] 的占位舞台替换为 PlayerView/SurfaceView，
 * 并用真实的 position/duration 驱动现有 controls 即可，UI 与焦点模型无需改动。
 */
data class PlayerLaunch(
    /** 作品标题（用于顶部标题条与详情面板回退）。 */
    val title: String,
    /** 详情面板要展示的统一视图模型，可为空（回退到 title）。 */
    val heroInfo: HeroInfo?,
    /** 全部可用播放源（用于「换源」托盘）。 */
    val sources: List<SearchResult>,
    /** 当前选中源 key（"source+id"）。 */
    val selectedSourceKey: String,
    /** 起播集索引（0 基）。 */
    val episodeIndex: Int,
    /** 续看起播位置（毫秒）。 */
    val startPositionMs: Long = 0L,
    /** 模拟时长（毫秒）。接入真实播放器后由播放器回填。 */
    val durationMs: Long = 45 * 60 * 1000L,
    /** TMDB 媒体引用（用于片头/片尾跳过等需要 TMDB ID 的功能）。可为空（未配 key 或解析失败）。 */
    val tmdbRef: org.moontechlab.selenetv.model.TmdbMediaRef? = null,
) {
    val selectedSource: SearchResult?
        get() = sources.firstOrNull { sourceKey(it) == selectedSourceKey }
}
