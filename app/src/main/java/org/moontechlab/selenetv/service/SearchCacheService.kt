package org.moontechlab.selenetv.service

import org.moontechlab.selenetv.model.SearchResult
import java.util.concurrent.ConcurrentHashMap

/**
 * 缓存页面状态枚举
 */
enum class CachedPageStatus {
    OK,
    TIMEOUT,
    FORBIDDEN
}

/**
 * 缓存条目类
 */
data class CachedPageEntry(
    val expiresAt: Long,
    val status: CachedPageStatus,
    val data: List<SearchResult>,
    val pageCount: Int? = null // 仅第一页可选存储
)

/**
 * 本地搜索缓存服务
 * 实现搜索结果的分页缓存管理
 */
object SearchCacheService {
    private const val TAG = "SearchCacheService"
    
    // 缓存配置
    private const val SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L // 10分钟
    private const val CACHE_CLEANUP_INTERVAL_MS = 5 * 60 * 1000L // 5分钟清理一次
    private const val MAX_CACHE_SIZE = 1000 // 最大缓存条目数量

    private val searchCache = ConcurrentHashMap<String, CachedPageEntry>()
    
    @Volatile
    private var cleanupTimer: java.util.Timer? = null
    
    @Volatile
    private var lastCleanupTime: Long = 0L

    /**
     * 生成搜索缓存键：source + query + page
     */
    private fun makeSearchCacheKey(sourceKey: String, query: String, page: Int): String {
        return "${sourceKey}::${query.trim()}::${page}"
    }

    /**
     * 获取缓存的搜索页面数据
     */
    fun getCachedSearchPage(
        sourceKey: String,
        query: String,
        page: Int
    ): CachedPageEntry? {
        val key = makeSearchCacheKey(sourceKey, query, page)
        val entry = searchCache[key] ?: return null

        // 检查是否过期
        if (entry.expiresAt <= System.currentTimeMillis()) {
            searchCache.remove(key)
            return null
        }

        return entry
    }

    /**
     * 设置缓存的搜索页面数据
     */
    fun setCachedSearchPage(
        sourceKey: String,
        query: String,
        page: Int,
        status: CachedPageStatus,
        data: List<SearchResult>,
        pageCount: Int? = null
    ) {
        // 惰性启动自动清理
        ensureAutoCleanupStarted()

        // 惰性清理：每次写入时检查是否需要清理
        val now = System.currentTimeMillis()
        if (now - lastCleanupTime > CACHE_CLEANUP_INTERVAL_MS) {
            performCacheCleanup()
        }

        val key = makeSearchCacheKey(sourceKey, query, page)
        searchCache[key] = CachedPageEntry(
            expiresAt = now + SEARCH_CACHE_TTL_MS,
            status = status,
            data = data,
            pageCount = pageCount
        )
    }

    /**
     * 确保自动清理已启动（惰性初始化）
     */
    @Synchronized
    private fun ensureAutoCleanupStarted() {
        if (cleanupTimer == null) {
            startAutoCleanup()
        }
    }

    /**
     * 智能清理过期的缓存条目
     */
    private fun performCacheCleanup(): CleanupResult {
        val now = System.currentTimeMillis()
        val keysToDelete = mutableListOf<String>()
        var sizeLimitedDeleted = 0

        // 1. 清理过期条目
        searchCache.forEach { (key, entry) ->
            if (entry.expiresAt <= now) {
                keysToDelete.add(key)
            }
        }

        val expiredCount = keysToDelete.size
        keysToDelete.forEach { key ->
            searchCache.remove(key)
        }

        // 2. 如果缓存大小超限，清理最老的条目（LRU策略）
        if (searchCache.size > MAX_CACHE_SIZE) {
            val entries = searchCache.entries.toList()
            // 按照过期时间排序，最早过期的在前面
            val sortedEntries = entries.sortedBy { it.value.expiresAt }
            
            val toRemove = searchCache.size - MAX_CACHE_SIZE
            for (i in 0 until toRemove) {
                searchCache.remove(sortedEntries[i].key)
                sizeLimitedDeleted++
            }
        }

        lastCleanupTime = now

        return CleanupResult(
            expired = expiredCount,
            total = searchCache.size,
            sizeLimited = sizeLimitedDeleted
        )
    }

    /**
     * 启动自动清理定时器
     */
    @Synchronized
    private fun startAutoCleanup() {
        if (cleanupTimer != null) return // 避免重复启动

        cleanupTimer = java.util.Timer("SearchCacheCleanup", true).apply {
            scheduleAtFixedRate(
                object : java.util.TimerTask() {
                    override fun run() {
                        performCacheCleanup()
                    }
                },
                CACHE_CLEANUP_INTERVAL_MS,
                CACHE_CLEANUP_INTERVAL_MS
            )
        }
    }

    /**
     * 停止自动清理（用于资源清理）
     */
    @Synchronized
    fun dispose() {
        cleanupTimer?.cancel()
        cleanupTimer = null
        searchCache.clear()
    }

    /**
     * 清空所有缓存
     */
    fun clearCache() {
        searchCache.clear()
    }

    /**
     * 获取当前缓存大小
     */
    val cacheSize: Int
        get() = searchCache.size

    /**
     * 清理结果数据类
     */
    private data class CleanupResult(
        val expired: Int,
        val total: Int,
        val sizeLimited: Int
    )
}
