package org.moontechlab.selenetv.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 搜索结果数据模型
 */
@Serializable
data class SearchResult(
    val id: String = "",
    val title: String = "",
    val poster: String = "",
    val episodes: List<String> = emptyList(),
    @SerialName("episodes_titles")
    val episodesTitles: List<String> = emptyList(),
    val source: String = "",
    @SerialName("source_name")
    val sourceName: String = "",
    @SerialName("class")
    val classType: String? = null,
    val year: String = "",
    val desc: String? = null,
    @SerialName("type_name")
    val typeName: String? = null,
    @SerialName("douban_id")
    val doubanId: Long? = null
)

/**
 * 聚合搜索结果
 * 将相同标题、年份、类型的结果聚合到一起
 */
data class AggregatedSearchResult(
    val key: String,
    val title: String,
    val year: String,
    val type: String,  // "movie" 或 "tv"
    val cover: String,
    val episodeCounts: MutableMap<String, Int> = mutableMapOf(),  // 源名称 -> 集数
    val doubanIds: MutableMap<Long, Int> = mutableMapOf(),        // 豆瓣ID -> 出现次数
    val sourceNames: MutableList<String> = mutableListOf(),       // 所有来源
    val originalResults: MutableList<SearchResult> = mutableListOf()  // 原始结果
) {
    /**
     * 获取众数集数（出现最多的集数）
     */
    fun getModeEpisodeCount(): Int {
        if (episodeCounts.isEmpty()) return 1
        return episodeCounts.values
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: 1
    }
    
    /**
     * 获取最可能的豆瓣 ID（出现次数最多的）
     */
    fun getBestDoubanId(): Long? {
        return doubanIds.maxByOrNull { it.value }?.key
    }
    
    /**
     * 转换为 VideoInfo 用于显示
     */
    fun toVideoInfo(): VideoInfo {
        return VideoInfo(
            id = key,
            source = originalResults.firstOrNull()?.source ?: "",
            title = title,
            sourceName = sourceNames.joinToString(", "),
            year = year,
            cover = cover,
            index = 0,
            totalEpisodes = getModeEpisodeCount(),
            playTime = 0,
            totalTime = 0,
            saveTime = System.currentTimeMillis(),
            searchTitle = title,
            doubanId = getBestDoubanId()?.toString()
        )
    }
}

/**
 * 标题匹配归一化：删去全部空白字符（含半角/全角空格）后比较。
 * 使「钢铁侠」与「钢铁 侠」视为同一标题。所有标题相等判断都应走这里。
 */
fun normalizeTitleForMatch(title: String): String =
    title.replace(Regex("[\\s\\u3000]+"), "")

/**
 * 两个标题删去全部空格后是否相同。
 */
fun titlesMatch(a: String, b: String): Boolean =
    normalizeTitleForMatch(a) == normalizeTitleForMatch(b)

/**
 * 生成聚合键
 */
fun SearchResult.generateAggregateKey(): String {
    val episodeCount = episodes.size.coerceAtLeast(1)
    val type = if (episodeCount > 1) "tv" else "movie"
    // 标题去空格后参与聚合键，使仅空格不同的标题聚合到同一组（展示仍用原始标题）
    return "${normalizeTitleForMatch(title)}_${year}_$type"
}

/**
 * 聚合搜索结果
 */
fun aggregateSearchResults(results: List<SearchResult>): List<AggregatedSearchResult> {
    val aggregatedMap = mutableMapOf<String, AggregatedSearchResult>()
    
    for (result in results) {
        val key = result.generateAggregateKey()
        val episodeCount = result.episodes.size.coerceAtLeast(1)
        val type = if (episodeCount > 1) "tv" else "movie"
        
        val aggregated = aggregatedMap.getOrPut(key) {
            AggregatedSearchResult(
                key = key,
                title = result.title,
                year = result.year,
                type = type,
                cover = result.poster
            )
        }
        
        // 更新集数统计
        aggregated.episodeCounts[result.sourceName] = episodeCount
        
        // 更新豆瓣 ID 统计
        result.doubanId?.let { id ->
            aggregated.doubanIds[id] = (aggregated.doubanIds[id] ?: 0) + 1
        }
        
        // 添加来源（去重）
        if (result.sourceName !in aggregated.sourceNames) {
            aggregated.sourceNames.add(result.sourceName)
        }
        
        // 保存原始结果
        aggregated.originalResults.add(result)
        
        // 更新封面（优先使用有豆瓣 ID 的结果的封面）
        if (result.doubanId != null && aggregated.cover.isEmpty()) {
            aggregatedMap[key] = aggregated.copy(cover = result.poster)
        }
    }
    
    return aggregatedMap.values.toList()
}

/**
 * 将搜索结果转换为 VideoInfo
 */
fun SearchResult.toVideoInfo(): VideoInfo {
    return VideoInfo(
        id = id,
        source = source,
        title = title,
        sourceName = sourceName,
        year = year,
        cover = poster,
        index = 0,
        totalEpisodes = episodes.size.coerceAtLeast(1),
        playTime = 0,
        totalTime = 0,
        saveTime = System.currentTimeMillis(),
        searchTitle = title,
        doubanId = doubanId?.toString(),
        episodesTitles = episodesTitles
    )
}
