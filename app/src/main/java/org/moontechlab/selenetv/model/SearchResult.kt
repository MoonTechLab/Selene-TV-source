package org.moontechlab.selenetv.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 搜索结果数据模型
 */
@Serializable
data class SearchResult(
    val id: String,
    val title: String,
    val poster: String,
    val episodes: List<String>,
    val episodesTitles: List<String>,
    val source: String,
    val sourceName: String,
    @SerialName("class")
    val classType: String? = null,
    val year: String,
    val desc: String? = null,
    val typeName: String? = null,
    val doubanId: Long? = null
)
