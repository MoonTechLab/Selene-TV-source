package org.moontechlab.selenetv.model

import kotlinx.serialization.Serializable

/**
 * 视频信息实体
 */
@Serializable
data class VideoInfo(
    /** 视频ID */
    val id: String,
    
    /** 视频源 */
    val source: String,
    
    /** 标题 */
    val title: String,
    
    /** 源名称 */
    val sourceName: String,
    
    /** 年份 */
    val year: String,
    
    /** 封面图片URL */
    val cover: String,
    
    /** 当前集数索引 */
    val index: Int,
    
    /** 总集数 */
    val totalEpisodes: Int,
    
    /** 播放时间（毫秒） */
    val playTime: Long,
    
    /** 总时长（毫秒） */
    val totalTime: Long,
    
    /** 保存时间（时间戳） */
    val saveTime: Long,
    
    /** 搜索标题 */
    val searchTitle: String,
    
    /** 豆瓣ID，用于豆瓣模式 */
    val doubanId: String? = null,
    
    /** Bangumi ID，用于Bangumi模式 */
    val bangumiId: Int? = null,
    
    /** 评分 */
    val rate: String? = null,
    
    /** 集数标题列表 */
    val episodesTitles: List<String>? = null
)
