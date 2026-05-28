package org.moontechlab.selenetv.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.moontechlab.selenetv.model.FavoriteItem
import org.moontechlab.selenetv.model.LiveSource
import org.moontechlab.selenetv.model.PlayRecord
import org.moontechlab.selenetv.model.SearchResource

/**
 * 本地模式存储：SharedPreferences `selene_tv_local`。
 *
 * 命名隔离原因：与「账号设置」`selene_tv_prefs` 分开，便于「退出本地模式」一键清掉而不动账号设置。
 *
 * 读：同步、不抛错（缺数据 → 空集合）。
 * 写：`apply()` 异步落盘。
 */
object LocalStore {
    private const val TAG = "LocalStore"
    private const val PREFS_NAME = "selene_tv_local"

    private const val KEY_SUBSCRIPTION_URL = "subscription_url"
    private const val KEY_SEARCH_SOURCES = "search_sources"
    private const val KEY_LIVE_SOURCES = "live_sources"
    private const val KEY_PLAY_RECORDS = "play_records"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_SEARCH_HISTORY = "search_history"

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Volatile
    private var favoritesCache: List<FavoriteItem>? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 解析失败兜底：日志 + 默认值；不抛错。 */
    private inline fun <T> safeDecode(label: String, default: T, block: () -> T): T =
        runCatching(block).getOrElse {
            Log.w(TAG, "$label 解析失败: ${it.message}")
            default
        }

    // ==================== 订阅 URL ====================

    fun getSubscriptionUrl(): String? = prefs.getString(KEY_SUBSCRIPTION_URL, null)
    fun saveSubscriptionUrl(url: String) {
        prefs.edit().putString(KEY_SUBSCRIPTION_URL, url).apply()
    }

    // ==================== 搜索源 ====================

    fun getSearchSources(): List<SearchResource> {
        val s = prefs.getString(KEY_SEARCH_SOURCES, null) ?: return emptyList()
        return safeDecode("搜索源", emptyList()) { json.decodeFromString<List<SearchResource>>(s) }
    }

    fun saveSearchSources(sources: List<SearchResource>) {
        prefs.edit().putString(KEY_SEARCH_SOURCES, json.encodeToString(sources)).apply()
    }

    // ==================== 直播源 ====================

    fun getLiveSources(): List<LiveSource> {
        val s = prefs.getString(KEY_LIVE_SOURCES, null) ?: return emptyList()
        return safeDecode("直播源", emptyList()) { json.decodeFromString<List<LiveSource>>(s) }
    }

    fun saveLiveSources(sources: List<LiveSource>) {
        prefs.edit().putString(KEY_LIVE_SOURCES, json.encodeToString(sources)).apply()
    }

    /**
     * 原子写入两类源：合并 search + live 到单次 edit().apply()，避免 SIGKILL 留下部分更新。
     */
    fun saveSubscriptionSources(search: List<SearchResource>, live: List<LiveSource>) {
        prefs.edit()
            .putString(KEY_SEARCH_SOURCES, json.encodeToString(search))
            .putString(KEY_LIVE_SOURCES, json.encodeToString(live))
            .apply()
    }

    // ==================== 播放记录 ====================
    // 序列化形态：Map<key, PlayRecord>，key = "${source}+${id}"。

    fun getPlayRecords(): Map<String, PlayRecord> {
        val s = prefs.getString(KEY_PLAY_RECORDS, null) ?: return emptyMap()
        return safeDecode("播放记录", emptyMap()) { json.decodeFromString<Map<String, PlayRecord>>(s) }
    }

    fun savePlayRecords(records: Map<String, PlayRecord>) {
        prefs.edit().putString(KEY_PLAY_RECORDS, json.encodeToString(records)).apply()
    }

    // ==================== 收藏 ====================
    // 序列化形态：Map<key, FavoriteItem>，key = "${source}+${id}"。
    // 同步内存缓存以便高频读（DetailScreen 进退场反复读收藏列表）。

    fun getFavorites(): List<FavoriteItem> {
        favoritesCache?.let { return it }
        val s = prefs.getString(KEY_FAVORITES, null)
        val list = if (s.isNullOrBlank()) emptyList() else safeDecode("收藏夹", emptyList<FavoriteItem>()) {
            json.decodeFromString<Map<String, FavoriteItem>>(s).values
                .sortedByDescending { it.saveTime }
                .filter { it.origin != "live" }
        }
        favoritesCache = list
        return list
    }

    fun saveFavorites(favorites: List<FavoriteItem>) {
        val map = favorites.associateBy { "${it.source}+${it.id}" }
        prefs.edit().putString(KEY_FAVORITES, json.encodeToString(map)).apply()
        favoritesCache = favorites
            .filter { it.origin != "live" }
            .sortedByDescending { it.saveTime }
    }

    // ==================== 搜索历史 ====================

    fun getSearchHistory(): List<String> {
        val s = prefs.getString(KEY_SEARCH_HISTORY, null) ?: return emptyList()
        return safeDecode("搜索历史", emptyList()) { json.decodeFromString<List<String>>(s) }
    }

    fun saveSearchHistory(history: List<String>) {
        prefs.edit().putString(KEY_SEARCH_HISTORY, json.encodeToString(history)).apply()
    }

    // ==================== 清空 ====================

    fun clearAll() {
        prefs.edit()
            .remove(KEY_SUBSCRIPTION_URL)
            .remove(KEY_SEARCH_SOURCES)
            .remove(KEY_LIVE_SOURCES)
            .remove(KEY_PLAY_RECORDS)
            .remove(KEY_FAVORITES)
            .remove(KEY_SEARCH_HISTORY)
            .apply()
        favoritesCache = null
    }
}
