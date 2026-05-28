package org.moontechlab.selenetv.data.local

import org.moontechlab.selenetv.data.UserDataRepository
import org.moontechlab.selenetv.model.FavoriteItem
import org.moontechlab.selenetv.model.PlayRecord

/**
 * 本地模式的用户数据实现：基于 LocalStore SharedPreferences。
 *
 * 失败语义：与 ApiService 抛 ApiException 不同，缺数据/解析失败一律返回空集合，不抛错。
 * 业务逻辑（去重 / 排序 / 20 条搜索历史上限）以 internal 纯函数实现并单测；I/O 部分走真机集成验证。
 */
object LocalUserDataRepository : UserDataRepository {

    // ============== 纯函数助手（可单测） ==============

    /** 添加搜索历史：trim → 去重 → 插队首 → 20 条上限。 */
    internal fun upsertSearchHistory(existing: List<String>, query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return existing
        val deduped = listOf(trimmed) + existing.filter { it != trimmed }
        return deduped.take(20)
    }

    /** 加入收藏：同 key 替换 + 插队首；空集合自动构造。 */
    internal fun upsertFavorite(existing: List<FavoriteItem>, item: FavoriteItem): List<FavoriteItem> {
        val key = "${item.source}+${item.id}"
        val rest = existing.filterNot { "${it.source}+${it.id}" == key }
        return listOf(item) + rest
    }

    internal fun upsertPlayRecord(existing: Map<String, PlayRecord>, record: PlayRecord): Map<String, PlayRecord> {
        val key = "${record.source}+${record.id}"
        return existing.toMutableMap().apply { this[key] = record }
    }

    /** ApiService.addFavorite 的 favoriteData Map 形态 → FavoriteItem。 */
    internal fun favoriteFromMap(source: String, id: String, data: Map<String, Any?>): FavoriteItem {
        return FavoriteItem(
            id = id,
            source = source,
            title = (data["title"] as? String).orEmpty(),
            sourceName = (data["source_name"] as? String).orEmpty(),
            year = (data["year"] as? String).orEmpty(),
            cover = (data["cover"] as? String).orEmpty(),
            totalEpisodes = (data["total_episodes"] as? Number)?.toInt() ?: 0,
            saveTime = (data["save_time"] as? Number)?.toLong() ?: 0L,
            origin = (data["origin"] as? String).orEmpty(),
        )
    }

    // ============== UserDataRepository 实现 ==============

    override fun getFavorites(): List<FavoriteItem> = LocalStore.getFavorites()

    override fun addFavorite(source: String, id: String, favoriteData: Map<String, Any?>) {
        val item = favoriteFromMap(source, id, favoriteData)
        LocalStore.saveFavorites(upsertFavorite(LocalStore.getFavorites(), item))
    }

    override fun unfavorite(source: String, id: String) {
        val key = "$source+$id"
        val next = LocalStore.getFavorites().filterNot { "${it.source}+${it.id}" == key }
        LocalStore.saveFavorites(next)
    }

    override fun getPlayRecords(): Map<String, PlayRecord> = LocalStore.getPlayRecords()

    override fun savePlayRecord(record: PlayRecord) {
        LocalStore.savePlayRecords(upsertPlayRecord(LocalStore.getPlayRecords(), record))
    }

    override fun deletePlayRecord(source: String, id: String) {
        val key = "$source+$id"
        LocalStore.savePlayRecords(LocalStore.getPlayRecords() - key)
    }

    override fun clearPlayRecords() {
        LocalStore.savePlayRecords(emptyMap())
    }

    override fun getSearchHistory(): List<String> = LocalStore.getSearchHistory()

    override fun addSearchHistory(query: String) {
        LocalStore.saveSearchHistory(upsertSearchHistory(LocalStore.getSearchHistory(), query))
    }

    override fun deleteSearchHistory(query: String) {
        LocalStore.saveSearchHistory(LocalStore.getSearchHistory() - query)
    }

    override fun clearSearchHistory() {
        LocalStore.saveSearchHistory(emptyList())
    }
}
