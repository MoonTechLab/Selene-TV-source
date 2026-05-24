package org.moontechlab.selenetv.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Bangumi 评分数据模型
 */
@Serializable
data class BangumiRating(
    val total: Int = 0,
    val count: Map<String, Int> = emptyMap(),
    val score: Double = 0.0
)

/**
 * Bangumi 图片数据模型
 */
@Serializable
data class BangumiImages(
    val large: String,
    val common: String,
    val medium: String,
    val small: String,
    val grid: String
)

/**
 * Bangumi 收藏数据模型
 */
@Serializable
data class BangumiCollection(
    val doing: Int = 0,
    @SerialName("on_hold")
    val onHold: Int = 0,
    val dropped: Int = 0,
    val wish: Int = 0,
    val collect: Int = 0
)

/**
 * Bangumi 星期数据模型
 */
@Serializable
data class BangumiWeekday(
    val en: String,
    val cn: String,
    val ja: String,
    val id: Int
)

/**
 * Bangumi 项目数据模型
 */
@Serializable
data class BangumiItem(
    val id: Int,
    val url: String = "",
    val type: Int = 0,
    val name: String,
    @SerialName("name_cn")
    val nameCn: String? = null,
    val summary: String = "",
    @SerialName("air_date")
    val airDate: String = "",
    @SerialName("air_weekday")
    val airWeekday: Int = 0,
    val rating: BangumiRating = BangumiRating(),
    val rank: Int = 0,
    // 个别条目接口会返回 images: null，须可空，否则整页日历解析失败
    val images: BangumiImages? = null,
    val collection: BangumiCollection = BangumiCollection()
)

/**
 * Bangumi 日历响应数据模型
 */
@Serializable
data class BangumiCalendarResponse(
    val weekday: BangumiWeekday,
    val items: List<BangumiItem>
)

/**
 * Bangumi 标签数据模型（详情接口 tags 为对象数组，而非字符串数组）
 */
@Serializable
data class BangumiTag(
    val name: String,
    val count: Int = 0,
    @SerialName("total_cont")
    val totalCont: Int = 0
)

/**
 * Bangumi infobox 条目：value 可能是字符串，也可能是 [{v:...}] 对象数组，
 * 故用 JsonElement 容纳，避免解析失败。
 */
@Serializable
data class BangumiInfoboxItem(
    val key: String,
    val value: JsonElement
)

/**
 * Bangumi 详情数据模型
 */
@Serializable
data class BangumiDetails(
    val id: Int,
    val type: Int,
    val name: String,
    @SerialName("name_cn")
    val nameCn: String? = null,
    val summary: String,
    val nsfw: Boolean,
    val locked: Boolean,
    val date: String? = null,
    val platform: String? = null,
    val images: BangumiImages,
    val infobox: List<BangumiInfoboxItem> = emptyList(),
    val volumes: Int,
    val eps: Int,
    @SerialName("total_episodes")
    val totalEpisodes: Int,
    val rating: BangumiRating,
    val collection: BangumiCollection,
    val tags: List<BangumiTag> = emptyList(),
    @SerialName("meta_tags")
    val metaTags: List<String>,
    val series: Boolean
)

/**
 * 获取最佳图片 URL
 */
fun BangumiImages.getBestImageUrl(): String {
    return large.ifEmpty { 
        common.ifEmpty { 
            medium.ifEmpty { 
                small.ifEmpty { grid }
            }
        }
    }
}

/**
 * 将 Bangumi 项目数据转换为 VideoInfo
 */
fun BangumiItem.toVideoInfo(): VideoInfo {
    val title = nameCn?.takeIf { it.isNotEmpty() } ?: name
    val year = airDate.split('-').firstOrNull() ?: ""
    val rate = if (rating.score > 0) String.format("%.1f", rating.score) else null

    return VideoInfo(
        id = id.toString(),
        source = "bangumi",
        title = title,
        sourceName = "Bangumi",
        year = year,
        cover = images?.getBestImageUrl() ?: "",
        index = 0,
        totalEpisodes = 1,
        playTime = 0,
        totalTime = 0,
        saveTime = System.currentTimeMillis(),
        searchTitle = title,
        bangumiId = id,
        rate = rate
    )
}
