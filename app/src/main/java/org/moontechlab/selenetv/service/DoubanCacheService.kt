package org.moontechlab.selenetv.service

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 缓存项数据结构
 */
@Serializable
data class CacheItem<T>(
    val data: T,
    val timestamp: Long,
    val expiration: Long
)

/**
 * 豆瓣缓存服务类
 * 提供双层缓存（内存+磁盘）、过期管理、自动清理等功能
 */
class DoubanCacheService private constructor(private val context: Context) {
    
    companion object {
        @Volatile
        private var instance: DoubanCacheService? = null
        
        fun getInstance(context: Context): DoubanCacheService {
            return instance ?: synchronized(this) {
                instance ?: DoubanCacheService(context.applicationContext).also { instance = it }
            }
        }
    }
    
    /** 内存缓存（线程安全） */
    private val memoryCache = ConcurrentHashMap<String, CacheItem<String>>()
    
    /** 缓存文件目录 */
    private var cacheDir: File? = null
    
    /** 定期清理任务 */
    private var cleanupJob: Job? = null
    
    /** 协程作用域 */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /** JSON 序列化器 */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    /**
     * 初始化缓存服务
     */
    suspend fun init() {
        withContext(Dispatchers.IO) {
            try {
                cacheDir = File(context.cacheDir, "douban_cache").apply {
                    if (!exists()) {
                        mkdirs()
                    }
                }
                // 启动时清理过期缓存
                cleanExpiredCache()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 生成缓存键
     */
    private fun generateCacheKey(prefix: String, params: Map<String, Any>): String {
        val sortedParams = params.toSortedMap()
        val paramString = sortedParams.entries.joinToString("&") { "${it.key}=${it.value}" }
        return "${prefix}_${paramString}"
    }
    
    /**
     * 检查缓存是否过期
     */
    private fun isExpired(cacheItem: CacheItem<*>): Boolean {
        return System.currentTimeMillis() - cacheItem.timestamp > cacheItem.expiration
    }
    
    /**
     * 获取缓存
     * @param key 缓存键
     * @param decode 解码器函数，将 JSON 字符串转换为目标类型 T
     */
    suspend fun <T> get(key: String, decode: (String) -> T): T? {
        return withContext(Dispatchers.IO) {
            try {
                // 先检查内存缓存
                memoryCache[key]?.let { cacheItem ->
                    if (!isExpired(cacheItem)) {
                        try {
                            // 检查是否为旧格式（包含 "items" 字段）
                            if (cacheItem.data.contains("\"items\"")) {
                                memoryCache.remove(key)
                                getCacheFile(key)?.delete()
                                return@withContext null
                            }
                            return@withContext decode(cacheItem.data)
                        } catch (e: Exception) {
                            // 解码失败，删除缓存
                            memoryCache.remove(key)
                            getCacheFile(key)?.delete()
                            throw e
                        }
                    } else {
                        // 过期则删除
                        memoryCache.remove(key)
                    }
                }
                
                // 检查磁盘缓存
                getCacheFile(key)?.let { file ->
                    if (file.exists()) {
                        try {
                            val jsonString = file.readText()
                            val jsonData = json.parseToJsonElement(jsonString)
                            
                            // 验证必需字段
                            if (jsonData !is kotlinx.serialization.json.JsonObject ||
                                !jsonData.containsKey("data") ||
                                !jsonData.containsKey("timestamp") ||
                                !jsonData.containsKey("expiration")
                            ) {
                                file.delete()
                                return@withContext null
                            }
                            
                            val data = jsonData["data"].toString()
                            val timestamp = jsonData["timestamp"]?.toString()?.toLongOrNull() ?: 0L
                            val expiration = jsonData["expiration"]?.toString()?.toLongOrNull() ?: 0L
                            
                            val cacheItem = CacheItem(data, timestamp, expiration)
                            
                            if (!isExpired(cacheItem)) {
                                // 检查是否为旧格式
                                if (data.contains("\"items\"")) {
                                    file.delete()
                                    return@withContext null
                                }
                                
                                // 重新加载到内存缓存
                                memoryCache[key] = cacheItem
                                try {
                                    return@withContext decode(data)
                                } catch (e: Exception) {
                                    memoryCache.remove(key)
                                    file.delete()
                                    throw e
                                }
                            } else {
                                // 过期则删除文件
                                file.delete()
                            }
                        } catch (e: Exception) {
                            // 文件内容不合法，直接删除
                            file.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                // 静默处理缓存读取错误
            }
            null
        }
    }

    /**
     * 设置缓存
     * @param key 缓存键
     * @param data 要缓存的数据（JSON 字符串）
     * @param expiration 过期时间（毫秒）
     */
    suspend fun set(key: String, data: String, expiration: Long) {
        withContext(Dispatchers.IO) {
            try {
                val cacheItem = CacheItem(
                    data = data,
                    timestamp = System.currentTimeMillis(),
                    expiration = expiration
                )
                
                // 保存到内存缓存
                memoryCache[key] = cacheItem
                
                // 保存到磁盘缓存：先写临时文件再原子 rename，避免并发读到半截内容
                getCacheFile(key)?.let { file ->
                    val jsonData = json.encodeToString(cacheItem)
                    val tmpFile = File(file.parentFile, "${file.name}.tmp")
                    tmpFile.writeText(jsonData)
                    if (!tmpFile.renameTo(file)) {
                        // 极少数文件系统 rename 失败时回退为直接覆写
                        file.writeText(jsonData)
                        tmpFile.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 删除缓存
     */
    suspend fun delete(key: String) {
        withContext(Dispatchers.IO) {
            try {
                // 从内存缓存删除
                memoryCache.remove(key)
                
                // 从磁盘缓存删除
                getCacheFile(key)?.let { file ->
                    if (file.exists()) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 清理过期缓存
     */
    private suspend fun cleanExpiredCache() {
        withContext(Dispatchers.IO) {
            try {
                // 清理内存缓存
                val expiredKeys = memoryCache.entries
                    .filter { isExpired(it.value) }
                    .map { it.key }
                
                expiredKeys.forEach { memoryCache.remove(it) }
                
                // 清理磁盘缓存
                cacheDir?.let { dir ->
                    if (dir.exists()) {
                        dir.listFiles()?.forEach { file ->
                            if (file.isFile && file.name.endsWith(".json")) {
                                try {
                                    val jsonString = file.readText()
                                    val jsonData = json.parseToJsonElement(jsonString)
                                    
                                    if (jsonData is kotlinx.serialization.json.JsonObject) {
                                        val timestamp = jsonData["timestamp"]?.toString()?.toLongOrNull() ?: 0L
                                        val expiration = jsonData["expiration"]?.toString()?.toLongOrNull() ?: 0L
                                        
                                        if (System.currentTimeMillis() - timestamp > expiration) {
                                            file.delete()
                                        }
                                    }
                                } catch (e: Exception) {
                                    // 如果文件损坏，直接删除
                                    file.delete()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 清空所有缓存
     */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            try {
                // 清空内存缓存
                memoryCache.clear()
                
                // 清空磁盘缓存
                cacheDir?.let { dir ->
                    if (dir.exists()) {
                        dir.listFiles()?.forEach { file ->
                            if (file.isFile) {
                                file.delete()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 定期清理过期缓存（建议在应用启动时调用）
     */
    fun startPeriodicCleanup() {
        // 停止现有任务
        cleanupJob?.cancel()
        
        // 每小时清理一次过期缓存
        cleanupJob = scope.launch {
            while (isActive) {
                delay(60 * 60 * 1000L) // 1 hour
                cleanExpiredCache()
            }
        }
    }
    
    /**
     * 停止定期清理
     */
    fun stopPeriodicCleanup() {
        cleanupJob?.cancel()
        cleanupJob = null
    }
    
    /**
     * 获取缓存文件
     *
     * 文件名用缓存键的 SHA-256 摘要，避免键中的中文/空格/`&`/`/` 等字符
     * 造成非法路径、子目录或键碰撞。内存缓存仍以原始键索引。
     */
    private fun getCacheFile(key: String): File? {
        return cacheDir?.let { File(it, "${safeFileName(key)}.json") }
    }

    /** 将任意缓存键转换为文件系统安全的文件名（SHA-256 十六进制） */
    private fun safeFileName(key: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    
    // ==================== 豆瓣服务专用缓存方法 ====================
    
    /**
     * 为豆瓣分类数据生成缓存键
     */
    fun generateDoubanCategoryCacheKey(
        kind: String,
        category: String,
        type: String,
        pageLimit: Int,
        page: Int
    ): String {
        return generateCacheKey("douban_category", mapOf(
            "kind" to kind,
            "category" to category,
            "type" to type,
            "pageLimit" to pageLimit,
            "page" to page
        ))
    }
    
    /**
     * 为豆瓣推荐数据生成缓存键
     */
    fun generateDoubanRecommendsCacheKey(
        kind: String,
        category: String,
        format: String,
        region: String,
        year: String,
        platform: String,
        sort: String,
        label: String,
        pageLimit: Int,
        page: Int
    ): String {
        return generateCacheKey("douban_recommends", mapOf(
            "kind" to kind,
            "category" to category,
            "format" to format,
            "region" to region,
            "year" to year,
            "platform" to platform,
            "sort" to sort,
            "label" to label,
            "pageLimit" to pageLimit,
            "page" to page
        ))
    }
    
    /**
     * 为豆瓣详情数据生成缓存键
     */
    fun generateDoubanDetailsCacheKey(doubanId: String): String {
        return generateCacheKey("douban_details", mapOf("doubanId" to doubanId))
    }
    
    /**
     * 为 Bangumi 详情数据生成缓存键
     */
    fun generateBangumiDetailsCacheKey(bangumiId: String): String {
        return generateCacheKey("bangumi_details", mapOf("bangumiId" to bangumiId))
    }
    
    /**
     * 清理所有缓存（用于调试）
     */
    suspend fun clearAllCache() {
        withContext(Dispatchers.IO) {
            try {
                memoryCache.clear()
                cacheDir?.let { dir ->
                    if (dir.exists()) {
                        dir.deleteRecursively()
                        dir.mkdirs()
                    }
                }
            } catch (e: Exception) {
                // 静默处理清理错误
            }
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stopPeriodicCleanup()
        scope.cancel()
    }
}
