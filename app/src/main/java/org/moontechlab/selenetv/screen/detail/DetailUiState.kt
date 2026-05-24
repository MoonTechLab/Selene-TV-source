package org.moontechlab.selenetv.screen.detail

import org.moontechlab.selenetv.model.BangumiDetails
import org.moontechlab.selenetv.model.DoubanMovieDetails
import org.moontechlab.selenetv.model.PlayRecord
import org.moontechlab.selenetv.model.SearchResult
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
        get() = when (entry) {
            is DetailEntry.Douban -> details?.toHeroInfo()
            is DetailEntry.Bangumi -> bangumiDetails?.toHeroInfo()
            is DetailEntry.History,
            is DetailEntry.SearchAggregate -> details?.toHeroInfo() ?: selectedSource?.toHeroInfo()
        }
}

fun sourceKey(result: SearchResult): String = "${result.source}+${result.id}"
