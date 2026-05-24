package org.moontechlab.selenetv.screen.detail

import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.model.aggregateSearchResults

/**
 * 详情页源列表的纯逻辑辅助。
 */
object DetailSourceResolver {
    /**
     * 用与搜索页相同的聚合口径，返回 originalResults 中含有 source+id 的那个聚合组的源列表。
     * 找不到返回 null。
     */
    fun historyGroupSources(
        raw: List<SearchResult>,
        source: String,
        id: String,
    ): List<SearchResult>? {
        val group = aggregateSearchResults(raw).firstOrNull { g ->
            g.originalResults.any { it.source == source && it.id == id }
        }
        return group?.originalResults?.toList()
    }

    /**
     * 已展示源中出现 >= 2 次、且出现次数最多的 doubanId（字符串）。无则 null。
     */
    fun tier2DoubanId(sources: List<SearchResult>): String? {
        return sources.mapNotNull { it.doubanId }
            .groupingBy { it }
            .eachCount()
            .filter { it.value >= 2 }
            .maxByOrNull { it.value }
            ?.key
            ?.toString()
    }
}
