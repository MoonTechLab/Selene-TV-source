package org.moontechlab.selenetv.model

import kotlinx.serialization.Serializable

/**
 * 收藏项数据模型
 */
@Serializable
data class FavoriteItem(
    /** 视频ID */
    val id: String,
    
    /** 来源标识 */
    val source: String,
    
    /** 标题 */
    val title: String,
    
    /** 源名称 */
    val sourceName: String,
    
    /** 年份 */
    val year: String,
    
    /** 封面图片URL */
    val cover: String,
    
    /** 总集数 */
    val totalEpisodes: Int,
    
    /** 保存时间（时间戳） */
    val saveTime: Long,
    
    /** 来源origin */
    val origin: String
) {
    /**
     * 将收藏项转换为 VideoInfo
     */
    fun toVideoInfo(): VideoInfo {
        return VideoInfo(
            id = id,
            source = source,
            title = title,
            sourceName = sourceName,
            year = year,
            cover = cover,
            index = 0,
            totalEpisodes = totalEpisodes,
            playTime = 0L,
            totalTime = 0L,
            saveTime = saveTime,
            searchTitle = title,
        )
    }
}
