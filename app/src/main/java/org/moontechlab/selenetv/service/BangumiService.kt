package org.moontechlab.selenetv.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.moontechlab.selenetv.model.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

/**
 * Bangumi 服务类
 */
class BangumiService private constructor(private val context: Context) {
    
    companion object {
        @Volatile
        private var instance: BangumiService? = null
        
        fun getInstance(context: Context): BangumiService {
            return instance ?: synchronized(this) {
                instance ?: BangumiService(context.applicationContext).also { instance = it }
            }
        }
        
        private const val USER_AGENT = "moontechlab/selene/1.0.0 (Android) (http://github.com/moontechlab/selene)"
        private const val TIMEOUT_MS = 30000
        private const val TAG = "BangumiService"
    }
    
    private val cacheService by lazy { DoubanCacheService.getInstance(context) }
    @Volatile private var cacheInitialized = false
    private val cacheInitMutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * 按数据源解析 Bangumi API host；缓存键不带 host，切源后旧缓存仍可命中。
     */
    private fun bangumiHost(): String = when (AppDataService.getBangumiDataSourceKeySync()) {
        "cdn_tencent" -> "img.doubanio.cmliussss.net"
        "cdn_aliyun"  -> "img.doubanio.cmliussss.com"
        "cdn_shinya"  -> "api-bgm-tv.shinya.click"
        else          -> "api.bgm.tv"
    }

    /**
     * 初始化缓存服务（加锁保证并发请求下只初始化一次）
     */
    private suspend fun initCache() {
        if (cacheInitialized) return
        cacheInitMutex.withLock {
            if (!cacheInitialized) {
                cacheService.init()
                cacheInitialized = true
            }
        }
    }
    
    /**
     * 执行 HTTP GET 请求
     */
    private suspend fun httpGet(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
            
            val responseCode = connection.responseCode
            val responseBody = if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            
            responseCode to responseBody
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 获取当天的新番放送（根据当前星期几）
     * @param forceSource - 是否强制从源获取，默认false
     */
    suspend fun getTodayCalendar(forceSource: Boolean = false): ApiResponse<List<BangumiItem>> {
        val calendar = Calendar.getInstance()
        // Java Calendar: 1 (Sunday) - 7 (Saturday)
        // Bangumi API: 1 (Monday) - 7 (Sunday)
        val weekday = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> 7
            else -> calendar.get(Calendar.DAY_OF_WEEK) - 1
        }
        return getCalendarByWeekday(weekday, forceSource)
    }
    
    /**
     * 获取指定星期的新番放送
     * @param weekday - 星期几 (1..7, Monday..Sunday)
     * @param forceSource - 是否强制从源获取，默认false
     */
    suspend fun getCalendarByWeekday(
        weekday: Int,
        forceSource: Boolean = false
    ): ApiResponse<List<BangumiItem>> {
        initCache()
        
        // 接口级缓存：缓存原始 API 数组，固定键，不含参数
        val cacheKey = "bangumi_calendar_raw_v1"
        
        // 如果不强制从源获取，先尝试读取原始数组缓存
        if (!forceSource) {
            try {
                val cachedRaw = cacheService.get(cacheKey) { raw ->
                    json.decodeFromString<List<BangumiCalendarResponse>>(raw)
                }
                
                if (cachedRaw != null && cachedRaw.isNotEmpty()) {
                    // 查找目标星期的数据
                    val targetDay = cachedRaw.find { it.weekday.id == weekday }
                    val items = targetDay?.items ?: emptyList()
                    return ApiResponse.Success(items)
                }
            } catch (e: Exception) {
                // 缓存读取失败，继续执行网络请求
                Log.d(TAG, "读取缓存失败: ${e.message}")
            }
        }
        
        return try {
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json"
            )
            
            val (statusCode, responseBody) = httpGet("https://${bangumiHost()}/calendar", headers)
            
            if (statusCode == HttpURLConnection.HTTP_OK) {
                try {
                    val responseData = json.decodeFromString<List<BangumiCalendarResponse>>(responseBody)
                    
                    // 缓存原始 API 数组（缓存一天）
                    try {
                        val jsonData = json.encodeToString(responseData)
                        cacheService.set(cacheKey, jsonData, 24 * 60 * 60 * 1000L)
                    } catch (cacheError: Exception) {
                        Log.d(TAG, "缓存 Bangumi 数据失败: ${cacheError.message}")
                    }
                    
                    // 查找目标星期的数据
                    val targetDay = responseData.find { it.weekday.id == weekday }
                    val items = targetDay?.items ?: emptyList()
                    
                    ApiResponse.Success(items, statusCode)
                } catch (parseError: Exception) {
                    ApiResponse.Error("Bangumi 数据解析失败: ${parseError.message}")
                }
            } else {
                ApiResponse.Error("获取 Bangumi 日历失败: $statusCode", statusCode)
            }
        } catch (error: Exception) {
            if (error is java.net.SocketTimeoutException) {
                ApiResponse.Error("Bangumi 数据请求超时")
            } else {
                ApiResponse.Error("Bangumi 数据请求异常: ${error.message}")
            }
        }
    }
    
    /**
     * 获取 Bangumi 详情
     * @param bangumiId - Bangumi ID
     */
    suspend fun getBangumiDetail(bangumiId: String): ApiResponse<BangumiDetails> {
        initCache()
        
        // 生成缓存键
        val cacheKey = cacheService.generateBangumiDetailsCacheKey(bangumiId)
        
        // 尝试从缓存获取数据
        try {
            val cachedData = cacheService.get(cacheKey) { raw ->
                json.decodeFromString<BangumiDetails>(raw)
            }
            if (cachedData != null) {
                return ApiResponse.Success(cachedData)
            }
        } catch (e: Exception) {
            // 缓存读取失败，清理可能损坏的缓存，继续执行网络请求
            Log.d(TAG, "读取 Bangumi 详情缓存失败: ${e.message}")
            try {
                cacheService.delete(cacheKey)
            } catch (_: Exception) {
                // 静默处理缓存清理错误
            }
        }
        
        return try {
            val apiUrl = "https://${bangumiHost()}/v0/subjects/$bangumiId"
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json"
            )
            
            val (statusCode, responseBody) = httpGet(apiUrl, headers)
            
            if (statusCode == HttpURLConnection.HTTP_OK) {
                try {
                    val details = json.decodeFromString<BangumiDetails>(responseBody)
                    
                    // 缓存成功的结果，缓存时间为3天
                    try {
                        val jsonData = json.encodeToString(details)
                        cacheService.set(cacheKey, jsonData, 3 * 24 * 60 * 60 * 1000L)
                    } catch (cacheError: Exception) {
                        // 静默处理缓存错误
                        Log.d(TAG, "缓存 Bangumi 详情数据失败: ${cacheError.message}")
                    }
                    
                    ApiResponse.Success(details, statusCode)
                } catch (parseError: Exception) {
                    ApiResponse.Error("Bangumi 详情数据解析失败: ${parseError.message}")
                }
            } else {
                ApiResponse.Error("获取 Bangumi 详情数据失败: $statusCode", statusCode)
            }
        } catch (error: Exception) {
            if (error is java.net.SocketTimeoutException) {
                ApiResponse.Error("Bangumi 详情数据请求超时")
            } else {
                ApiResponse.Error("Bangumi 详情数据请求异常: ${error.message}")
            }
        }
    }
}
