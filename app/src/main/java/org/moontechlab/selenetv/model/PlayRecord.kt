package org.moontechlab.selenetv.model

import kotlinx.serialization.Serializable

/**
 * 播放记录数据模型
 */
@Serializable
data class PlayRecord(
    val id: String,
    val source: String,
    val title: String,
    val sourceName: String,
    val year: String,
    val cover: String,
    val index: Int,
    val totalEpisodes: Int,
    val playTime: Long,
    val totalTime: Long,
    val saveTime: Long,
    val searchTitle: String
) {
    /**
     * 将播放记录转换为 VideoInfo
     */
    fun toVideoInfo(): VideoInfo {
        return VideoInfo(
            id = id,
            source = source,
            title = title,
            sourceName = sourceName,
            year = year,
            cover = cover,
            index = index,
            totalEpisodes = totalEpisodes,
            playTime = playTime,
            totalTime = totalTime,
            saveTime = saveTime,
            searchTitle = searchTitle,
        )
    }
}
