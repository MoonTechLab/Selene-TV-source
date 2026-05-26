package org.moontechlab.selenetv.screen.detail

import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.model.titlesMatch

/**
 * 详情页源筛选规则：
 * 1. 强匹配：result.doubanId == route.doubanId
 * 2. 弱匹配兜底：result 没带 doubanId 时，看 title 与 year
 * 3. 明确不匹配：result 带 doubanId 但与 route 不等 → 拒绝
 */
object SourceFilter {
    fun matchesContent(
        result: SearchResult,
        doubanId: String,
        title: String,
        year: String,
    ): Boolean {
        val resultDoubanId = result.doubanId?.toString()
        return when {
            resultDoubanId == doubanId -> true
            resultDoubanId == null -> {
                val titleOk = titlesMatch(result.title, title)
                val yearOk = result.year == year || year.isEmpty() || result.year.isEmpty()
                titleOk && yearOk
            }
            else -> false
        }
    }

    /**
     * 仅按标题/年份匹配（忽略 doubanId）。用于 bangumi / 继续播放 / 收藏夹入口。
     */
    fun matchesByTitleYear(
        result: SearchResult,
        title: String,
        year: String,
    ): Boolean {
        val titleOk = titlesMatch(result.title, title)
        val yearOk = result.year == year || year.isEmpty() || result.year.isEmpty()
        return titleOk && yearOk
    }
}
