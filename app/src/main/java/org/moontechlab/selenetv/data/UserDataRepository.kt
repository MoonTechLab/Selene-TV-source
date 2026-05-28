package org.moontechlab.selenetv.data

import org.moontechlab.selenetv.model.FavoriteItem
import org.moontechlab.selenetv.model.PlayRecord

/**
 * 用户数据仓库：收藏 / 播放记录 / 搜索历史。
 *
 * 调用约定（对齐既有 ApiService）：所有方法同步阻塞，必须在 Dispatchers.IO 调用；
 * Server 实现失败抛 ApiException；Local 实现数据缺失返回空集合，不抛错。
 */
interface UserDataRepository {
    fun getFavorites(): List<FavoriteItem>
    fun addFavorite(source: String, id: String, favoriteData: Map<String, Any?>)
    fun unfavorite(source: String, id: String)

    fun getPlayRecords(): Map<String, PlayRecord>
    fun savePlayRecord(record: PlayRecord)
    fun deletePlayRecord(source: String, id: String)
    fun clearPlayRecords()

    fun getSearchHistory(): List<String>
    fun addSearchHistory(query: String)
    fun deleteSearchHistory(query: String)
    fun clearSearchHistory()
}
