package org.moontechlab.selenetv.data.server

import org.moontechlab.selenetv.data.UserDataRepository
import org.moontechlab.selenetv.model.FavoriteItem
import org.moontechlab.selenetv.model.PlayRecord
import org.moontechlab.selenetv.service.ApiService

/**
 * 服务端模式的用户数据实现：直接委托 ApiService（保持 1:1 行为）。
 */
object ServerUserDataRepository : UserDataRepository {
    override fun getFavorites(): List<FavoriteItem> = ApiService.getFavorites()
    override fun addFavorite(source: String, id: String, favoriteData: Map<String, Any?>) =
        ApiService.addFavorite(source, id, favoriteData)
    override fun unfavorite(source: String, id: String) = ApiService.unfavorite(source, id)

    override fun getPlayRecords(): Map<String, PlayRecord> = ApiService.getPlayRecords()
    override fun savePlayRecord(record: PlayRecord) = ApiService.savePlayRecord(record)
    override fun deletePlayRecord(source: String, id: String) = ApiService.deletePlayRecord(source, id)
    override fun clearPlayRecords() = ApiService.clearPlayRecords()

    override fun getSearchHistory(): List<String> = ApiService.getSearchHistory()
    override fun addSearchHistory(query: String) = ApiService.addSearchHistory(query)
    override fun deleteSearchHistory(query: String) = ApiService.deleteSearchHistory(query)
    override fun clearSearchHistory() = ApiService.clearSearchHistory()
}
