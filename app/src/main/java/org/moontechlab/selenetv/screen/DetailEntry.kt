package org.moontechlab.selenetv.screen

import org.moontechlab.selenetv.model.AggregatedSearchResult
import org.moontechlab.selenetv.model.VideoInfo

/**
 * 详情页入口。详情是内存覆盖层（非导航参数），可直接携带对象，无需序列化。
 */
sealed interface DetailEntry {
    val title: String
    val poster: String
    val year: String

    data class Douban(
        val doubanId: String,
        override val title: String,
        override val poster: String,
        override val year: String,
    ) : DetailEntry

    data class Bangumi(
        val bangumiId: Int,
        override val title: String,
        override val poster: String,
        override val year: String,
    ) : DetailEntry

    /** 继续播放 + 收藏夹：携带具体源 source+id 与搜索用标题。 */
    data class History(
        val source: String,
        val id: String,
        /** 用于向聚合源发起搜索的关键词，可能与显示标题 [title] 不同。 */
        val searchTitle: String,
        override val title: String,
        override val poster: String,
        override val year: String,
    ) : DetailEntry

    /** 搜索页聚合卡片：直接携带聚合结果（含 originalResults 快照）。title/poster/year 委托给 agg。 */
    data class SearchAggregate(
        val agg: AggregatedSearchResult,
    ) : DetailEntry {
        override val title: String get() = agg.title
        override val poster: String get() = agg.cover
        override val year: String get() = agg.year
    }
}

/**
 * 按 VideoInfo 字段分流入口：doubanId > bangumiId > 否则按 source+id 当作历史/收藏。
 */
fun detailEntryFor(video: VideoInfo): DetailEntry {
    val doubanId = video.doubanId
    val bangumiId = video.bangumiId
    return when {
        doubanId != null -> DetailEntry.Douban(doubanId, video.title, video.cover, video.year)
        bangumiId != null -> DetailEntry.Bangumi(bangumiId, video.title, video.cover, video.year)
        else -> DetailEntry.History(
            source = video.source,
            id = video.id,
            // 部分播放记录的 searchTitle 为空，回退到 title，避免空查询
            searchTitle = video.searchTitle.ifBlank { video.title },
            title = video.title,
            poster = video.cover,
            year = video.year,
        )
    }
}
