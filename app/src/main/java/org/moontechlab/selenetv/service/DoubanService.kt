package org.moontechlab.selenetv.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.moontechlab.selenetv.model.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * API 响应包装类
 */
sealed class ApiResponse<out T> {
    data class Success<T>(val data: T, val statusCode: Int = 200) : ApiResponse<T>()
    data class Error(val message: String, val statusCode: Int? = null) : ApiResponse<Nothing>()
}

/**
 * 豆瓣服务类
 */
class DoubanService private constructor(private val context: Context) {
    
    companion object {
        @Volatile
        private var instance: DoubanService? = null
        
        fun getInstance(context: Context): DoubanService {
            return instance ?: synchronized(this) {
                instance ?: DoubanService(context.applicationContext).also { instance = it }
            }
        }
        
        private const val TIMEOUT_MS = 30000
    }
    
    private val cacheService by lazy { DoubanCacheService.getInstance(context) }
    @Volatile private var cacheInitialized = false
    private val cacheInitMutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
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
     * 生成唯一的 Origin 头（用于 CORS 代理）
     */
    private fun getUniqueOrigin(): String {
        val timestamp = System.currentTimeMillis()
        return "https://origin-$timestamp.example.com"
    }
    
    /**
     * 根据数据源构建 API URL
     */
    private fun buildApiUrl(
        dataSourceKey: DoubanDataSourceKey,
        kind: DoubanKind,
        category: String,
        type: String,
        start: Int,
        limit: Int
    ): String {
        val baseUrl = when (dataSourceKey) {
            DoubanDataSourceKey.CDN_TENCENT -> "https://m.douban.cmliussss.net"
            DoubanDataSourceKey.CDN_ALIYUN -> "https://m.douban.cmliussss.com"
            else -> "https://m.douban.com"
        }
        
        val kindStr = kind.name.lowercase()
        val apiUrl = "$baseUrl/rexxar/api/v2/subject/recent_hot/$kindStr?start=$start&limit=$limit&category=$category&type=$type"
        
        return if (dataSourceKey == DoubanDataSourceKey.CORS_PROXY) {
            "https://ciao-cors.is-an.org/${URLEncoder.encode(apiUrl, "UTF-8")}"
        } else {
            apiUrl
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
     * 获取首页热门电影
     */
    suspend fun getHotMovies(
        pageLimit: Int = 25,
        page: Int = 0,
        forceSource: Boolean = false
    ): ApiResponse<List<DoubanMovie>> {
        val dataSourceKey = getDoubanDataSourceKey()
        return getCategoryData(
            kind = DoubanKind.MOVIE,
            category = "热门",
            type = "全部",
            pageLimit = pageLimit,
            page = page,
            dataSourceKey = dataSourceKey,
            forceSource = forceSource
        )
    }
    
    /**
     * 获取热门剧集数据
     */
    suspend fun getHotTvShows(
        pageLimit: Int = 25,
        page: Int = 0,
        forceSource: Boolean = false
    ): ApiResponse<List<DoubanMovie>> {
        val dataSourceKey = getDoubanDataSourceKey()
        return getCategoryData(
            kind = DoubanKind.TV,
            category = "最近热门",
            type = "tv",
            pageLimit = pageLimit,
            page = page,
            dataSourceKey = dataSourceKey,
            forceSource = forceSource
        )
    }
    
    /**
     * 获取热门综艺数据
     */
    suspend fun getHotShows(
        pageLimit: Int = 25,
        page: Int = 0,
        forceSource: Boolean = false
    ): ApiResponse<List<DoubanMovie>> {
        val dataSourceKey = getDoubanDataSourceKey()
        return getCategoryData(
            kind = DoubanKind.TV,
            category = "show",
            type = "show",
            pageLimit = pageLimit,
            page = page,
            dataSourceKey = dataSourceKey,
            forceSource = forceSource
        )
    }

    /**
     * 获取豆瓣分类数据
     */
    suspend fun getCategoryData(
        kind: DoubanKind,
        category: String,
        type: String,
        pageLimit: Int = 25,
        page: Int = 0,
        dataSourceKey: DoubanDataSourceKey = DoubanDataSourceKey.DIRECT,
        forceSource: Boolean = false
    ): ApiResponse<List<DoubanMovie>> {
        initCache()
        
        val cacheKey = cacheService.generateDoubanCategoryCacheKey(
            kind = kind.name.lowercase(),
            category = category,
            type = type,
            pageLimit = pageLimit,
            page = page
        )
        
        // 如果不强制从源获取，尝试从缓存获取数据
        if (!forceSource) {
            try {
                val cachedData = cacheService.get(cacheKey) { raw ->
                    json.decodeFromString<List<DoubanMovie>>(raw)
                }
                if (cachedData != null) {
                    return ApiResponse.Success(cachedData)
                }
            } catch (e: Exception) {
                // 缓存读取失败，继续执行网络请求
                android.util.Log.d("DoubanService", "读取缓存失败: ${e.message}")
            }
        }
        
        val start = page * pageLimit
        val apiUrl = buildApiUrl(dataSourceKey, kind, category, type, start, pageLimit)
        
        return try {
            val headers = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                "Referer" to "https://movie.douban.com/",
                "Accept" to "application/json, text/plain, */*"
            )
            
            if (dataSourceKey == DoubanDataSourceKey.CORS_PROXY) {
                headers["Origin"] = getUniqueOrigin()
            }
            
            val (statusCode, responseBody) = httpGet(apiUrl, headers)
            
            if (statusCode == HttpURLConnection.HTTP_OK) {
                try {
                    val data = json.decodeFromString<DoubanResponse>(responseBody)
                    
                    if (data.items.isEmpty()) {
                        return ApiResponse.Error("豆瓣数据格式错误: items 为空")
                    }
                    
                    val movies = data.items.mapNotNull { it.toDoubanMovie() }
                    
                    // 缓存成功的结果，缓存时间为 6 小时
                    try {
                        val jsonData = json.encodeToString(movies)
                        cacheService.set(cacheKey, jsonData, 6 * 60 * 60 * 1000L)
                    } catch (cacheError: Exception) {
                        android.util.Log.d("DoubanService", "缓存数据失败: ${cacheError.message}")
                    }
                    
                    ApiResponse.Success(movies, statusCode)
                } catch (parseError: Exception) {
                    ApiResponse.Error("豆瓣数据解析失败: ${parseError.message}")
                }
            } else {
                android.util.Log.d("DoubanService", "获取豆瓣数据失败: $statusCode")
                ApiResponse.Error("获取豆瓣数据失败: $statusCode", statusCode)
            }
        } catch (error: Exception) {
            android.util.Log.d("DoubanService", "豆瓣数据请求异常: ${error.message}")
            ApiResponse.Error("豆瓣数据请求异常: ${error.message}")
        }
    }
    
    /**
     * 获取豆瓣推荐数据（新版筛选逻辑）
     */
    suspend fun fetchDoubanRecommends(
        params: DoubanRecommendsParams
    ): ApiResponse<List<DoubanMovie>> {
        val dataSourceKey = getDoubanDataSourceKey()
        initCache()
        
        val cacheKey = cacheService.generateDoubanRecommendsCacheKey(
            kind = params.kind,
            category = params.category,
            format = params.format,
            region = params.region,
            year = params.year,
            platform = params.platform,
            sort = params.sort,
            label = params.label,
            pageLimit = params.pageLimit,
            page = params.page
        )
        
        // 尝试从缓存获取数据
        try {
            val cachedData = cacheService.get(cacheKey) { raw ->
                json.decodeFromString<List<DoubanMovie>>(raw)
            }
            if (cachedData != null) {
                return ApiResponse.Success(cachedData)
            }
        } catch (e: Exception) {
            android.util.Log.d("DoubanService", "读取缓存失败: ${e.message}")
        }
        
        // 处理筛选参数，将 'all' 转换为空字符串
        val categoryParam = if (params.category == "all") "" else params.category
        val formatParam = if (params.format == "all") "" else params.format
        val regionParam = if (params.region == "all") "" else params.region
        val yearParam = if (params.year == "all") "" else params.year
        val platformParam = if (params.platform == "all") "" else params.platform
        val labelParam = if (params.label == "all") "" else params.label
        val sortParam = if (params.sort == "T") "" else params.sort
        
        // 构建 selected_categories
        val selectedCategories = mutableMapOf("类型" to categoryParam)
        if (formatParam.isNotEmpty()) {
            selectedCategories["形式"] = formatParam
        }
        if (regionParam.isNotEmpty()) {
            selectedCategories["地区"] = regionParam
        }
        
        // 构建 tags 数组
        val tags = mutableListOf<String>()
        if (categoryParam.isNotEmpty()) {
            tags.add(categoryParam)
        }
        if (categoryParam.isEmpty() && formatParam.isNotEmpty()) {
            tags.add(formatParam)
        }
        if (labelParam.isNotEmpty()) {
            tags.add(labelParam)
        }
        if (regionParam.isNotEmpty()) {
            tags.add(regionParam)
        }
        if (yearParam.isNotEmpty()) {
            tags.add(yearParam)
        }
        if (platformParam.isNotEmpty()) {
            tags.add(platformParam)
        }
        
        // 根据数据源选项构建不同的基础URL
        val baseUrl = when (dataSourceKey) {
            DoubanDataSourceKey.CDN_TENCENT -> "https://m.douban.cmliussss.net/rexxar/api/v2/${params.kind}/recommend"
            DoubanDataSourceKey.CDN_ALIYUN -> "https://m.douban.cmliussss.com/rexxar/api/v2/${params.kind}/recommend"
            else -> "https://m.douban.com/rexxar/api/v2/${params.kind}/recommend"
        }
        
        // 构建查询参数
        val selectedCategoriesJson = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            selectedCategories
        )
        
        val queryParams = mutableMapOf(
            "refresh" to "0",
            "start" to (params.page * params.pageLimit).toString(),
            "count" to params.pageLimit.toString(),
            "selected_categories" to selectedCategoriesJson,
            "uncollect" to "false",
            "score_range" to "0,10",
            "tags" to tags.joinToString(",")
        )
        
        if (sortParam.isNotEmpty()) {
            queryParams["sort"] = sortParam
        }
        
        val urlParams = queryParams.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
        
        var targetUrl = "$baseUrl?$urlParams"
        
        if (dataSourceKey == DoubanDataSourceKey.CORS_PROXY) {
            targetUrl = "https://ciao-cors.is-an.org/${URLEncoder.encode(targetUrl, "UTF-8")}"
        }
        
        return try {
            val headers = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                "Referer" to "https://movie.douban.com/",
                "Accept" to "application/json, text/plain, */*"
            )
            
            if (dataSourceKey == DoubanDataSourceKey.CORS_PROXY) {
                headers["Origin"] = getUniqueOrigin()
            }
            
            val (statusCode, responseBody) = httpGet(targetUrl, headers)
            
            if (statusCode == HttpURLConnection.HTTP_OK) {
                try {
                    val data = json.decodeFromString<DoubanResponse>(responseBody)
                    
                    val filteredItems = data.items
                        .filter { it.type == "movie" || it.type == "tv" }
                        .mapNotNull { it.toDoubanMovie() }
                    
                    // 缓存成功的结果，缓存时间为 6 小时
                    try {
                        val jsonData = json.encodeToString(filteredItems)
                        cacheService.set(cacheKey, jsonData, 6 * 60 * 60 * 1000L)
                    } catch (cacheError: Exception) {
                        android.util.Log.d("DoubanService", "缓存数据失败: ${cacheError.message}")
                    }
                    
                    ApiResponse.Success(filteredItems, statusCode)
                } catch (parseError: Exception) {
                    ApiResponse.Error("豆瓣推荐数据解析失败: ${parseError.message}")
                }
            } else {
                android.util.Log.d("DoubanService", "获取豆瓣推荐数据失败: $statusCode")
                ApiResponse.Error("获取豆瓣推荐数据失败: $statusCode", statusCode)
            }
        } catch (error: Exception) {
            android.util.Log.d("DoubanService", "豆瓣推荐数据请求异常: ${error.message}")
            ApiResponse.Error("豆瓣推荐数据请求异常: ${error.message}")
        }
    }

    /**
     * 获取豆瓣详情数据
     */
    suspend fun getDoubanDetails(doubanId: String): ApiResponse<DoubanMovieDetails> {
        initCache()
        
        val cacheKey = cacheService.generateDoubanDetailsCacheKey(doubanId)
        
        // 尝试从缓存获取数据
        try {
            val cachedData = cacheService.get(cacheKey) { raw ->
                json.decodeFromString<DoubanMovieDetails>(raw)
            }
            if (cachedData != null) {
                return ApiResponse.Success(cachedData)
            }
        } catch (e: Exception) {
            android.util.Log.d("DoubanService", "读取豆瓣详情缓存失败: ${e.message}")
        }
        
        val dataSourceKey = getDoubanDataSourceKey()

        // 改用 rexxar JSON 接口替代抓取 HTML 网页：
        // HTML 详情页 movie.douban.com/subject/{id} 会被豆瓣反爬 302 跳到 sec.douban.com 校验页（按 IP 拦截，
        // 改 cookie/UA 都绕不过）；而 rexxar JSON 接口与首页推荐同源、未被拦截，且字段更全。
        val rexxarUrl = when (dataSourceKey) {
            DoubanDataSourceKey.CDN_TENCENT -> "https://m.douban.cmliussss.net/rexxar/api/v2/subject/$doubanId"
            DoubanDataSourceKey.CDN_ALIYUN -> "https://m.douban.cmliussss.com/rexxar/api/v2/subject/$doubanId"
            else -> "https://m.douban.com/rexxar/api/v2/subject/$doubanId"
        }
        val apiUrl = if (dataSourceKey == DoubanDataSourceKey.CORS_PROXY) {
            "https://ciao-cors.is-an.org/${URLEncoder.encode(rexxarUrl, "UTF-8")}"
        } else {
            rexxarUrl
        }

        return try {
            val headers = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                "Referer" to "https://movie.douban.com/",
                "Accept" to "application/json, text/plain, */*"
            )

            if (dataSourceKey == DoubanDataSourceKey.CORS_PROXY) {
                headers["Origin"] = getUniqueOrigin()
            }

            val (statusCode, responseBody) = httpGet(apiUrl, headers)

            if (statusCode == HttpURLConnection.HTTP_OK) {
                try {
                    val details = parseDoubanRexxarDetails(responseBody, doubanId)

                    // 仅在解析出有效标题时缓存，避免把异常响应缓存 3 天
                    if (details.title.isNotBlank()) {
                        try {
                            val jsonData = json.encodeToString(details)
                            cacheService.set(cacheKey, jsonData, 3 * 24 * 60 * 60 * 1000L)
                        } catch (cacheError: Exception) {
                            android.util.Log.d("DoubanService", "缓存豆瓣详情数据失败: ${cacheError.message}")
                        }
                    } else {
                        android.util.Log.d("DoubanService", "豆瓣详情解析为空，跳过缓存: $doubanId")
                    }

                    ApiResponse.Success(details, statusCode)
                } catch (parseError: Exception) {
                    ApiResponse.Error("豆瓣详情数据解析失败: ${parseError.message}")
                }
            } else {
                ApiResponse.Error("获取豆瓣详情数据失败: $statusCode", statusCode)
            }
        } catch (e: Exception) {
            ApiResponse.Error("豆瓣详情数据请求异常: ${e.message}")
        }
    }

    /**
     * 解析豆瓣 rexxar JSON 详情（m.douban.com/rexxar/api/v2/subject/{id}）。
     * 字段映射到 DoubanMovieDetails。
     */
    private fun parseDoubanRexxarDetails(body: String, id: String): DoubanMovieDetails {
        val root = json.parseToJsonElement(body).jsonObject

        fun str(key: String): String? =
            root[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

        fun strList(key: String): List<String> =
            (root[key] as? JsonArray)?.mapNotNull {
                it.jsonPrimitive.contentOrNull?.takeIf { s -> s.isNotBlank() }
            } ?: emptyList()

        // directors / actors 是对象数组，取其 name 字段
        fun nameList(key: String): List<String> =
            (root[key] as? JsonArray)?.mapNotNull { el ->
                (el as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            } ?: emptyList()

        val poster = str("cover_url")
            ?: (root["pic"] as? JsonObject)?.get("large")?.jsonPrimitive?.contentOrNull
            ?: ""
        val rate = (root["rating"] as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it != "0" && it != "0.0" }

        return DoubanMovieDetails(
            id = str("id") ?: id,
            title = str("title") ?: "",
            poster = poster,
            rate = rate,
            year = str("year") ?: "",
            summary = str("intro"),
            genres = strList("genres"),
            directors = nameList("directors"),
            screenwriters = emptyList(),
            actors = nameList("actors"),
            duration = strList("durations").firstOrNull(),
            countries = strList("countries"),
            languages = strList("languages"),
            releaseDate = strList("pubdate").firstOrNull(),
            originalTitle = str("original_title"),
            imdbId = str("imdb"),
            totalEpisodes = root["episodes_count"]?.jsonPrimitive?.intOrNull,
            recommends = emptyList(),
            backdrop = null,
            logo = null,
        )
    }
    /**
     * 获取豆瓣数据源配置
     */
    private fun getDoubanDataSourceKey(): DoubanDataSourceKey {
        val key = AppDataService.getDoubanDataSourceKeySync()
        return when (key) {
            "cors_proxy" -> DoubanDataSourceKey.CORS_PROXY
            "cdn_tencent" -> DoubanDataSourceKey.CDN_TENCENT
            "cdn_aliyun" -> DoubanDataSourceKey.CDN_ALIYUN
            else -> DoubanDataSourceKey.DIRECT
        }
    }
}
