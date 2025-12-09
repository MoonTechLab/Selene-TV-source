package org.moontechlab.selenetv.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    private var cacheInitialized = false
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    /**
     * 初始化缓存服务
     */
    private suspend fun initCache() {
        if (!cacheInitialized) {
            cacheService.init()
            cacheInitialized = true
        }
    }
    
    /**
     * 将豆瓣 API 返回的 Item 转换为 DoubanMovie
     * 返回 null 如果缺少必要字段
     */
    private fun parseItemToMovie(item: DoubanItem): DoubanMovie? {
        val id = item.id ?: return null
        val title = item.title ?: return null
        val poster = item.pic?.large ?: item.pic?.normal ?: item.pic?.small ?: ""
        val rate = item.rating?.value?.toString() ?: item.rating?.starCount?.toString()
        val year = item.cardSubtitle?.split("/")?.firstOrNull()?.trim() ?: ""
        
        return DoubanMovie(
            id = id,
            title = title,
            poster = poster,
            rate = rate,
            year = year
        )
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
                    
                    val movies = data.items.mapNotNull { parseItemToMovie(it) }
                    
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
                        .mapNotNull { parseItemToMovie(it) }
                    
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
        
        val apiUrl = when (dataSourceKey) {
            DoubanDataSourceKey.CDN_TENCENT -> "https://movie.douban.cmliussss.net/subject/$doubanId"
            DoubanDataSourceKey.CDN_ALIYUN -> "https://movie.douban.cmliussss.com/subject/$doubanId"
            DoubanDataSourceKey.CORS_PROXY -> "https://ciao-cors.is-an.org/${URLEncoder.encode("https://movie.douban.com/subject/$doubanId", "UTF-8")}"
            else -> "https://movie.douban.com/subject/$doubanId"
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
                    val details = parseDoubanHtmlDetails(responseBody, doubanId)
                    
                    // 缓存成功的结果，缓存时间为3天
                    try {
                        val jsonData = json.encodeToString(details)
                        cacheService.set(cacheKey, jsonData, 3 * 24 * 60 * 60 * 1000L)
                    } catch (cacheError: Exception) {
                        android.util.Log.d("DoubanService", "缓存豆瓣详情数据失败: ${cacheError.message}")
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
     * 解析豆瓣HTML详情页面
     */
    private fun parseDoubanHtmlDetails(html: String, id: String): DoubanMovieDetails {
        return try {
            // 提取标题
            val titleRegex = Regex("""<h1[^>]*>[\s\S]*?<span[^>]*property="v:itemreviewed"[^>]*>([^<]+)</span>""")
            val title = titleRegex.find(html)?.groupValues?.get(1)?.trim() ?: ""
            
            // 提取海报
            val posterRegex = Regex("""<a[^>]*class="nbgnbg"[^>]*>[\s\S]*?<img[^>]*src="([^"]+)"""")
            val poster = posterRegex.find(html)?.groupValues?.get(1) ?: ""
            
            // 提取评分
            val ratingRegex = Regex("""<strong[^>]*class="ll rating_num"[^>]*property="v:average">([^<]+)</strong>""")
            val rate = ratingRegex.find(html)?.groupValues?.get(1)
            
            // 提取年份
            val yearRegex = Regex("""<span[^>]*class="year">[(]([^)]+)[)]</span>""")
            val year = yearRegex.find(html)?.groupValues?.get(1) ?: ""
            
            // 提取导演
            val directors = mutableListOf<String>()
            val directorRegex = Regex("""<span class=["']pl["']>导演</span>:\s*<span class=["']attrs["']>(.*?)</span>""")
            directorRegex.find(html)?.groupValues?.get(1)?.let { directorHtml ->
                val directorLinks = Regex("""<a[^>]*>([^<]+)</a>""").findAll(directorHtml)
                directors.addAll(directorLinks.map { it.groupValues[1].trim() }.filter { it.isNotEmpty() })
            }
            
            // 提取编剧
            val screenwriters = mutableListOf<String>()
            val writerRegex = Regex("""<span class=["']pl["']>编剧</span>:\s*<span class=["']attrs["']>(.*?)</span>""")
            writerRegex.find(html)?.groupValues?.get(1)?.let { writerHtml ->
                val writerLinks = Regex("""<a[^>]*>([^<]+)</a>""").findAll(writerHtml)
                screenwriters.addAll(writerLinks.map { it.groupValues[1].trim() }.filter { it.isNotEmpty() })
            }
            
            // 提取主演
            val actors = mutableListOf<String>()
            val castRegex = Regex("""<span class=["']pl["']>主演</span>:\s*<span class=["']attrs["']>(.*?)</span>""")
            castRegex.find(html)?.groupValues?.get(1)?.let { castHtml ->
                val castLinks = Regex("""<a[^>]*>([^<]+)</a>""").findAll(castHtml)
                actors.addAll(castLinks.map { it.groupValues[1].trim() }.filter { it.isNotEmpty() })
            }
            
            // 提取类型
            val genreRegex = Regex("""<span[^>]*property="v:genre">([^<]+)</span>""")
            val genres = genreRegex.findAll(html).map { it.groupValues[1] }.filter { it.isNotEmpty() }.toList()
            
            // 提取制片国家/地区
            val countryRegex = Regex("""<span[^>]*class="pl">制片国家/地区:</span>([^<]+)""")
            val countries = countryRegex.find(html)?.groupValues?.get(1)?.trim()
                ?.split("/")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            
            // 提取语言
            val languageRegex = Regex("""<span[^>]*class="pl">语言:</span>([^<]+)""")
            val languages = languageRegex.find(html)?.groupValues?.get(1)?.trim()
                ?.split("/")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            
            // 提取首播/上映日期
            var releaseDate: String? = null
            val firstAiredRegex = Regex("""<span class="pl">首播:</span>\s*<span[^>]*property="v:initialReleaseDate"[^>]*content="([^"]*)"[^>]*>([^<]*)</span>""")
            firstAiredRegex.find(html)?.let {
                releaseDate = it.groupValues[1]
            }
            if (releaseDate == null) {
                val releaseDateRegex = Regex("""<span class="pl">上映日期:</span>\s*<span[^>]*property="v:initialReleaseDate"[^>]*content="([^"]*)"[^>]*>([^<]*)</span>""")
                releaseDateRegex.find(html)?.let {
                    releaseDate = it.groupValues[1]
                }
            }
            
            // 提取集数
            var episodes: Int? = null
            val episodesRegex = Regex("""<span[^>]*class="pl">集数:</span>([^<]+)""")
            episodesRegex.find(html)?.groupValues?.get(1)?.trim()?.let { episodesStr ->
                episodes = episodesStr.toIntOrNull()
            }
            
            // 提取时长
            var duration: String? = null
            val singleEpisodeDurationRegex = Regex("""<span[^>]*class="pl">单集片长:</span>([^<]+)""")
            singleEpisodeDurationRegex.find(html)?.groupValues?.get(1)?.trim()?.let { lenStr ->
                lenStr.toIntOrNull()?.let { duration = "${it}分钟" }
            }
            if (duration == null) {
                val movieDurationRegex = Regex("""<span[^>]*class="pl">片长:</span>([^<]+)""")
                movieDurationRegex.find(html)?.groupValues?.get(1)?.trim()?.let { durStr ->
                    durStr.toIntOrNull()?.let { duration = "${it}分钟" }
                }
            }
            
            // 提取剧情简介
            var summary: String? = null
            val summaryRegex1 = Regex("""<span[^>]*class="all hidden">([\s\S]*?)</span>""")
            val summary1 = summaryRegex1.find(html)?.groupValues?.get(1)
                ?.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "|||LINEBREAK|||")
                ?.replace(Regex("""<[^>]*>"""), "")
                ?.replace(Regex("""\s+"""), " ")
                ?.replace("|||LINEBREAK|||", "\n")
                ?.trim()
            
            val summaryRegex2 = Regex("""<span[^>]*property="v:summary"[^>]*>([\s\S]*?)</span>""")
            val summary2 = summaryRegex2.find(html)?.groupValues?.get(1)
                ?.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "|||LINEBREAK|||")
                ?.replace(Regex("""<[^>]*>"""), "")
                ?.replace(Regex("""\s+"""), " ")
                ?.replace("|||LINEBREAK|||", "\n")
                ?.trim()
            
            summary = when {
                summary1 != null && summary2 != null -> if (summary1.length >= summary2.length) summary1 else summary2
                summary1 != null -> summary1
                summary2 != null -> summary2
                else -> null
            }
            
            // 提取 IMDb ID
            var imdbId: String? = null
            val imdbRegex = Regex("""<span[^>]*class="pl">IMDb:</span>([^<]+)""")
            imdbRegex.find(html)?.groupValues?.get(1)?.trim()?.let {
                imdbId = it
            }
            
            // 提取推荐
            val recommends = mutableListOf<DoubanRecommendItem>()
            try {
                val recommendationsRegex = Regex("""<div[^>]*id="recommendations"[^>]*>([\s\S]*?)</div>""")
                recommendationsRegex.find(html)?.groupValues?.get(1)?.let { recommendationsContent ->
                    val dlRegex = Regex("""<dl>([\s\S]*?)</dl>""")
                    dlRegex.findAll(recommendationsContent).forEach { dlMatch ->
                        val dlContent = dlMatch.groupValues[1]
                        
                        val linkRegex = Regex("""<a[^>]*href="https://movie\.douban\.com/subject/(\d+)/[^"]*"[^>]*>""")
                        val imgRegex = Regex("""<img[^>]*src="([^"]+)"[^>]*alt="([^"]*)"""")
                        val rateRegex = Regex("""<span[^>]*class="subject-rate"[^>]*>([^<]*)</span>""")
                        
                        val recommendId = linkRegex.find(dlContent)?.groupValues?.get(1)
                        val imgMatch = imgRegex.find(dlContent)
                        val posterUrl = imgMatch?.groupValues?.get(1)
                        val recommendTitle = imgMatch?.groupValues?.get(2)
                        val recommendRate = rateRegex.find(dlContent)?.groupValues?.get(1)?.trim()
                        
                        if (recommendId != null && recommendTitle != null && posterUrl != null) {
                            recommends.add(
                                DoubanRecommendItem(
                                    id = recommendId,
                                    title = recommendTitle,
                                    poster = posterUrl,
                                    rate = if (recommendRate.isNullOrEmpty()) null else recommendRate
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.d("DoubanService", "解析推荐区域失败: ${e.message}")
            }
            
            DoubanMovieDetails(
                id = id,
                title = title,
                poster = poster,
                rate = rate,
                year = year,
                summary = summary,
                genres = genres,
                directors = directors,
                screenwriters = screenwriters,
                actors = actors,
                duration = duration,
                countries = countries,
                languages = languages,
                releaseDate = releaseDate,
                originalTitle = null,
                imdbId = imdbId,
                recommends = recommends,
                totalEpisodes = episodes,
                backdrop = null,
                logo = null
            )
        } catch (e: Exception) {
            DoubanMovieDetails(
                id = id,
                title = "解析失败",
                poster = "",
                year = "",
                genres = emptyList(),
                directors = emptyList(),
                screenwriters = emptyList(),
                actors = emptyList(),
                countries = emptyList(),
                languages = emptyList(),
                recommends = emptyList()
            )
        }
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
