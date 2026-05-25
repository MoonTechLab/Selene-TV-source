package org.moontechlab.selenetv.screen.detail

import org.moontechlab.selenetv.model.BangumiDetails
import org.moontechlab.selenetv.model.DoubanMovieDetails
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.model.getBestImageUrl

/**
 * 详情 hero 统一视图模型。可由豆瓣详情 / bangumi 详情 / 搜索源派生。
 */
data class HeroInfo(
    val title: String,
    val poster: String,
    val rate: String? = null,       // 评分文本，null 不显示
    val genres: List<String> = emptyList(),
    val subLine: String? = null,    // 副信息行（· 连接）
    val summary: String? = null,
    // 以下两项来自 TMDB（仅渲染用），由 DetailUiState 在派生时注入；其余字段始终取豆瓣/bangumi。
    val backdrop: String? = null,   // TMDB 剧照，作 hero 背景；非空时启用 Apple TV 式新布局
    val logo: String? = null,       // TMDB 标题 logo，作艺术字标题；为空回退文字标题
)

fun DoubanMovieDetails.toHeroInfo(): HeroInfo {
    val subParts = buildList {
        releaseDate?.takeIf { it.isNotEmpty() }?.let { rd ->
            val country = countries.firstOrNull()?.takeIf { it.isNotEmpty() }
            if (country != null) add("$rd($country)") else add(rd)
        }
        countries.takeIf { it.isNotEmpty() }?.let { add(it.joinToString("/")) }
        duration?.takeIf { it.isNotEmpty() }?.let { add(it) }
    }
    return HeroInfo(
        title = title,
        poster = poster,
        rate = rate?.takeIf { it.isNotEmpty() && it != "0.0" },
        genres = genres,
        subLine = subParts.takeIf { it.isNotEmpty() }?.joinToString(" · "),
        summary = summary?.takeIf { it.isNotEmpty() },
    )
}

fun BangumiDetails.toHeroInfo(): HeroInfo = HeroInfo(
    title = nameCn?.takeIf { it.isNotEmpty() } ?: name,
    poster = images.getBestImageUrl(),
    rate = rating.score.takeIf { it > 0 }?.let { String.format("%.1f", it) },
    genres = metaTags.ifEmpty { tags.map { it.name } }.take(4),
    subLine = date?.takeIf { it.isNotEmpty() },
    summary = summary.takeIf { it.isNotEmpty() },
)

fun SearchResult.toHeroInfo(): HeroInfo = HeroInfo(
    title = title,
    poster = poster,
    rate = null,
    genres = listOfNotNull(typeName?.takeIf { it.isNotEmpty() }),
    subLine = year.takeIf { it.isNotEmpty() },
    summary = desc?.takeIf { it.isNotEmpty() },
)
