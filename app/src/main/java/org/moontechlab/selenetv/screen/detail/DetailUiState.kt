package org.moontechlab.selenetv.screen.detail

import org.moontechlab.selenetv.model.DoubanMovieDetails
import org.moontechlab.selenetv.model.PlayRecord
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.screen.DetailRoute
import org.moontechlab.selenetv.service.SearchProgress

/**
 * 详情页 UI 状态。单 Composable 内 mutableStateOf 持有。
 */
data class DetailUiState(
    val route: DetailRoute,
    val details: DoubanMovieDetails? = null,
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
}

fun sourceKey(result: SearchResult): String = "${result.source}+${result.id}"
