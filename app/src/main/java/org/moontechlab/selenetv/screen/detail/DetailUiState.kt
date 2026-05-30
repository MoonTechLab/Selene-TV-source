package org.moontechlab.selenetv.screen.detail

import org.moontechlab.selenetv.model.BangumiDetails
import org.moontechlab.selenetv.model.DoubanMovieDetails
import org.moontechlab.selenetv.model.PlayRecord
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.model.SourceSpeed
import org.moontechlab.selenetv.model.TmdbArt
import org.moontechlab.selenetv.screen.DetailEntry
import org.moontechlab.selenetv.service.SearchProgress

/**
 * 详情页 UI 状态。单 Composable 内 mutableStateOf 持有。
 */
data class DetailUiState(
    val entry: DetailEntry,
    val details: DoubanMovieDetails? = null,
    val bangumiDetails: BangumiDetails? = null,
    val detailsLoading: Boolean = true,
    val detailsError: String? = null,
    val sources: List<SearchResult> = emptyList(),
    val sourcesProgress: SearchProgress? = null,
    val sourcesComplete: Boolean = false,
    val selectedSourceKey: String? = null,
    val playRecords: Map<String, PlayRecord> = emptyMap(),
    val favoriteKeys: Set<String> = emptySet(),
    val episodePickerVisible: Boolean = false,
    // TMDB 剧照/ logo（仅 hero 渲染）。非空且含 backdrop 时切到 Apple TV 式布局。
    val tmdbArt: TmdbArt? = null,
    // TMDB 媒体引用（mediaType/tmdbId/season/imdbId）。用于片头/片尾跳过等功能。
    val tmdbRef: org.moontechlab.selenetv.model.TmdbMediaRef? = null,
    // 测速优选：key 为 source+id。speedTesting 期间禁止选源、源卡片变暗；完成后按分排序并自动选中冠军。
    val speedResults: Map<String, SourceSpeed> = emptyMap(),
    val speedTesting: Boolean = false,
) {
    val selectedSource: SearchResult?
        get() = sources.firstOrNull { "${it.source}+${it.id}" == selectedSourceKey }

    val selectedPlayRecord: PlayRecord?
        get() = selectedSourceKey?.let { playRecords[it] }

    val isSelectedFavorite: Boolean
        get() = selectedSourceKey != null && selectedSourceKey in favoriteKeys

    /**
     * Hero 信息优先级（需求 5）：
     * - Douban 入口 → 豆瓣详情
     * - Bangumi 入口 → bangumi 详情
     * - History / SearchAggregate → 若已拉到 tier2 豆瓣详情(details)则用之，否则用选中源自身信息
     */
    val heroInfo: HeroInfo?
        get() {
            val base = when (entry) {
                is DetailEntry.Douban -> details?.toHeroInfo()
                is DetailEntry.Bangumi -> bangumiDetails?.toHeroInfo()
                is DetailEntry.History,
                is DetailEntry.SearchAggregate -> details?.toHeroInfo() ?: selectedSource?.toHeroInfo()
            }
            // 注入 TMDB 剧照/ logo，其余字段保持豆瓣/bangumi 数据不变
            return base?.copy(backdrop = tmdbArt?.backdropUrl, logo = tmdbArt?.logoUrl)
        }

    /** 是否使用 Apple TV 式新 hero：拿到 TMDB 剧照即启用，否则旧布局。 */
    val useHeroV2: Boolean
        get() = tmdbArt?.backdropUrl != null
}

fun sourceKey(result: SearchResult): String = "${result.source}+${result.id}"
