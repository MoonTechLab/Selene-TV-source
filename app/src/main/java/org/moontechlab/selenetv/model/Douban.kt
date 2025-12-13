package org.moontechlab.selenetv.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 豆瓣电影数据类型
 */
@Serializable
data class DoubanMovie(
    val id: String,
    val title: String,
    val poster: String,
    val rate: String? = null,
    val year: String,
    val totalEpisodes: Int? = null
)

/**
 * 将豆瓣电影数据转换为 VideoInfo
 */
fun DoubanMovie.toVideoInfo(): VideoInfo {
    return VideoInfo(
        id = id,
        source = "douban",
        title = title,
        sourceName = "豆瓣",
        year = year,
        cover = poster,
        index = 0,
        totalEpisodes = totalEpisodes ?: 1,
        playTime = 0,
        totalTime = 0,
        saveTime = System.currentTimeMillis(),
        searchTitle = title,
        doubanId = id,
        rate = rate
    )
}

/**
 * 豆瓣 API 响应类型
 */
@Serializable
data class DoubanResponse(
    val items: List<DoubanItem> = emptyList()
)



/**
 * 豆瓣 API 返回的原始数据结构
 */
@Serializable
data class DoubanItem(
    val id: String? = null,
    val title: String? = null,
    val type: String? = null,
    @SerialName("card_subtitle")
    val cardSubtitle: String? = null,
    @SerialName("episodes_info")
    val episodesInfo: String? = null,
    @SerialName("is_new")
    val isNew: Boolean? = null,
    val uri: String? = null,
    val pic: DoubanPic? = null,
    val rating: DoubanRating? = null
)

/**
 * 豆瓣图片信息
 */
@Serializable
data class DoubanPic(
    val large: String? = null,
    val normal: String? = null,
    val small: String? = null
)

/**
 * 豆瓣评分信息
 */
@Serializable
data class DoubanRating(
    val count: Int? = null,
    val max: Int? = null,
    @SerialName("star_count")
    val starCount: Double? = null,
    val value: Double? = null
)

/**
 * 数据源类型
 */
enum class DoubanDataSourceKey {
    @SerialName("direct")
    DIRECT,
    
    @SerialName("cdn_tencent")
    CDN_TENCENT,
    
    @SerialName("cdn_aliyun")
    CDN_ALIYUN,
    
    @SerialName("cors_proxy")
    CORS_PROXY
}

/**
 * 内容类型
 */
enum class DoubanKind {
    @SerialName("movie")
    MOVIE,
    
    @SerialName("tv")
    TV
}

/**
 * 豆瓣推荐数据请求参数
 */
@Serializable
data class DoubanRecommendsParams(
    val kind: String,
    val category: String = "",
    val format: String = "",
    val region: String = "",
    val year: String = "",
    val platform: String = "",
    val sort: String = "",
    val label: String = "",
    val pageLimit: Int = 20,
    val page: Int = 1,
    val dataSourceKey: String = "direct"
)

/**
 * 豆瓣推荐项目
 */
@Serializable
data class DoubanRecommendItem(
    val id: String,
    val title: String,
    val poster: String,
    val rate: String? = null
)

/**
 * 豆瓣电影详情
 */
@Serializable
data class DoubanMovieDetails(
    val id: String,
    val title: String,
    val poster: String,
    val rate: String? = null,
    val year: String,
    val summary: String? = null,
    val genres: List<String>,
    val directors: List<String>,
    val screenwriters: List<String>,
    val actors: List<String>,
    val duration: String? = null,
    val countries: List<String>,
    val languages: List<String>,
    @SerialName("release_date")
    val releaseDate: String? = null,
    @SerialName("original_title")
    val originalTitle: String? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    @SerialName("total_episodes")
    val totalEpisodes: Int? = null,
    val recommends: List<DoubanRecommendItem>,
    val backdrop: String? = null,
    val logo: String? = null
)

/**
 * 扩展函数：将 DoubanItem 转换为 DoubanMovie
 * 返回 null 如果缺少必要字段
 */
fun DoubanItem.toDoubanMovie(): DoubanMovie? {
    val itemId = id ?: return null
    val itemTitle = title ?: return null
    return DoubanMovie(
        id = itemId,
        title = itemTitle,
        poster = pic?.large ?: pic?.normal ?: pic?.small ?: "",
        rate = rating?.value?.takeIf { it > 0.0 }?.toString(),
        year = extractYear(cardSubtitle),
        totalEpisodes = extractTotalEpisodes(episodesInfo)
    )
}

/**
 * 扩展函数：将 DoubanItem 转换为 VideoInfo
 * 返回 null 如果缺少必要字段
 */
fun DoubanItem.toVideoInfo(): VideoInfo? {
    val itemId = id ?: return null
    val itemTitle = title ?: return null
    return VideoInfo(
        id = itemId,
        source = "douban",
        title = itemTitle,
        sourceName = "豆瓣",
        year = extractYear(cardSubtitle),
        cover = pic?.large ?: pic?.normal ?: pic?.small ?: "",
        index = 0,
        totalEpisodes = extractTotalEpisodes(episodesInfo) ?: 1,
        playTime = 0,
        totalTime = 0,
        saveTime = System.currentTimeMillis(),
        searchTitle = itemTitle,
        doubanId = itemId,
        rate = rating?.value?.takeIf { it > 0.0 }?.toString()
    )
}

/**
 * 扩展函数：将 DoubanRecommendItem 转换为 VideoInfo
 */
fun DoubanRecommendItem.toVideoInfo(): VideoInfo {
    return VideoInfo(
        id = id,
        source = "douban",
        title = title,
        sourceName = "豆瓣",
        year = "",
        cover = poster,
        index = 0,
        totalEpisodes = 1,
        playTime = 0,
        totalTime = 0,
        saveTime = System.currentTimeMillis(),
        searchTitle = title,
        doubanId = id,
        rate = rate
    )
}

/**
 * 从字幕中提取年份
 * 例如: "2024 / 中国大陆 / 剧情" -> "2024"
 */
private fun extractYear(subtitle: String?): String {
    if (subtitle.isNullOrEmpty()) return ""
    
    val yearRegex = Regex("""(\d{4})""")
    val matchResult = yearRegex.find(subtitle)
    return matchResult?.value ?: ""
}

/**
 * 从集数信息中提取总集数
 * 例如: "共12集" -> 12, "更新至24集" -> 24
 */
private fun extractTotalEpisodes(episodesInfo: String?): Int? {
    if (episodesInfo.isNullOrEmpty()) return null
    
    val episodeRegex = Regex("""(\d+)集""")
    val matchResult = episodeRegex.find(episodesInfo)
    return matchResult?.groupValues?.get(1)?.toIntOrNull()
}
