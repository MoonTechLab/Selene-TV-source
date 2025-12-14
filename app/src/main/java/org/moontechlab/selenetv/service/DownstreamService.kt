package org.moontechlab.selenetv.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.moontechlab.selenetv.model.SearchResource
import org.moontechlab.selenetv.model.SearchResult
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 分页搜索结果
 */
data class SearchPageResult(
    val results: List<SearchResult>,
    val pageCount: Int
)

/**
 * 下游搜索服务
 * 用于从第三方API搜索视频资源
 */
object DownstreamService {
    private const val TAG = "DownstreamService"
    private const val MAX_SEARCH_PAGES = 5
    private const val REQUEST_TIMEOUT = 8000 // 8秒超时

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    // 信任所有证书的 TrustManager
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    
    // 信任所有证书的 SSLContext
    private val trustAllSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
    }

    /**
     * 从指定的搜索资源API搜索
     */
    suspend fun searchFromApi(
        resource: SearchResource,
        query: String
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val apiBaseUrl = resource.api
            val apiUrl = "$apiBaseUrl?ac=videolist&wd=${URLEncoder.encode(query, "UTF-8")}"

            // 搜索第一页
            val firstPageResult = searchPage(resource, query, 1, apiUrl)
            val results = firstPageResult.results.toMutableList()
            val pageCountFromFirst = firstPageResult.pageCount

            // 计算需要获取的页数
            val pagesToFetch = minOf(pageCountFromFirst - 1, MAX_SEARCH_PAGES - 1)

            // 并发获取其他页面
            if (pagesToFetch > 0) {
                val additionalResults = (2..pagesToFetch + 1).map { page ->
                    async {
                        val pageUrl = "$apiBaseUrl?ac=videolist&wd=${URLEncoder.encode(query, "UTF-8")}&pg=$page"
                        searchPage(resource, query, page, pageUrl).results
                    }
                }.awaitAll()

                additionalResults.forEach { pageResults ->
                    if (pageResults.isNotEmpty()) {
                        results.addAll(pageResults)
                    }
                }
            }

            // 过滤包含黄色关键词的结果
            results.filter { result ->
                !ContentFilterService.shouldFilter(result.typeName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "搜索失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 清理 HTML 标签
     */
    private fun cleanHtmlTags(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text
            .replace(Regex("<[^>]+>"), "\n")
            .replace(Regex("\n+"), "\n")
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("^\n+|\n+$"), "")
            .trim()
            .let { decodeHtmlEntities(it) }
    }

    /**
     * 解码 HTML 实体
     */
    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }

    /**
     * 分页搜索
     */
    private fun searchPage(
        resource: SearchResource,
        query: String,
        page: Int,
        url: String
    ): SearchPageResult {
        // 先查缓存
        val cached = SearchCacheService.getCachedSearchPage(resource.key, query, page)
        if (cached != null) {
            return if (cached.status == CachedPageStatus.OK) {
                SearchPageResult(cached.data, cached.pageCount ?: 1)
            } else {
                SearchPageResult(emptyList(), 1)
            }
        }

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            
            // 对 HTTPS 连接设置信任所有证书
            if (connection is HttpsURLConnection) {
                connection.sslSocketFactory = trustAllSslContext.socketFactory
                connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            
            connection.requestMethod = "GET"
            connection.connectTimeout = REQUEST_TIMEOUT
            connection.readTimeout = REQUEST_TIMEOUT
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            )
            connection.setRequestProperty("Accept", "application/json")

            // 检查 403 状态码，缓存 forbidden
            if (connection.responseCode == 403) {
                SearchCacheService.setCachedSearchPage(
                    resource.key, query, page,
                    CachedPageStatus.FORBIDDEN, emptyList()
                )
                return SearchPageResult(emptyList(), 1)
            }

            if (connection.responseCode !in 200..299) {
                return SearchPageResult(emptyList(), 1)
            }

            // 尝试从响应头获取编码
            val contentType = connection.contentType
            val charset = contentType?.let {
                Regex("charset=([^;]+)").find(it)?.groupValues?.get(1)?.lowercase()?.trim()
            }

            // 获取响应体
            val responseBody = connection.inputStream.use { inputStream ->
                val bytes = inputStream.readBytes()
                when (charset) {
                    "gbk", "gb2312" -> {
                        try {
                            String(bytes, Charset.forName("GBK"))
                        } catch (e: Exception) {
                            String(bytes, Charsets.UTF_8)
                        }
                    }
                    else -> {
                        try {
                            String(bytes, Charsets.UTF_8)
                        } catch (e: Exception) {
                            try {
                                String(bytes, Charset.forName("GBK"))
                            } catch (e2: Exception) {
                                String(bytes)
                            }
                        }
                    }
                }
            }

            connection.disconnect()

            val data = json.parseToJsonElement(responseBody).jsonObject
            val list = data["list"]?.jsonArray

            if (list == null || list.isEmpty()) {
                return SearchPageResult(emptyList(), 1)
            }

            val allResults = list.mapNotNull { itemElement ->
                parseSearchResultItem(itemElement.jsonObject, resource)
            }

            // 过滤出有剧集的结果
            val results = allResults.filter { it.episodes.isNotEmpty() }
            val pageCount = if (page == 1) {
                data["pagecount"]?.jsonPrimitive?.intOrNull ?: 1
            } else 1

            // 缓存成功的搜索结果
            SearchCacheService.setCachedSearchPage(
                resource.key, query, page,
                CachedPageStatus.OK, results,
                if (page == 1) pageCount else null
            )

            return SearchPageResult(results, pageCount)
        } catch (e: java.net.SocketTimeoutException) {
            // 超时缓存 timeout 状态
            SearchCacheService.setCachedSearchPage(
                resource.key, query, page,
                CachedPageStatus.TIMEOUT, emptyList()
            )
            return SearchPageResult(emptyList(), 1)
        } catch (e: Exception) {
            // 其他异常不缓存，直接返回空结果
            Log.e(TAG, "搜索页面失败: ${e.message}", e)
            return SearchPageResult(emptyList(), 1)
        }
    }


    /**
     * 解析单个搜索结果项
     */
    private fun parseSearchResultItem(
        item: JsonObject,
        resource: SearchResource
    ): SearchResult? {
        var episodes = mutableListOf<String>()
        var titles = mutableListOf<String>()

        val vodPlayUrl = item["vod_play_url"]?.jsonPrimitive?.contentOrNull
        if (!vodPlayUrl.isNullOrBlank()) {
            val vodPlayUrlArray = vodPlayUrl.split("$$$")
            for (url in vodPlayUrlArray) {
                val matchEpisodes = mutableListOf<String>()
                val matchTitles = mutableListOf<String>()
                val titleUrlArray = url.split("#")
                
                for (titleUrl in titleUrlArray) {
                    val episodeTitleUrl = titleUrl.split("$")
                    if (episodeTitleUrl.size == 2 && episodeTitleUrl[1].endsWith(".m3u8")) {
                        matchTitles.add(episodeTitleUrl[0])
                        matchEpisodes.add(episodeTitleUrl[1])
                    }
                }
                
                if (matchEpisodes.size > episodes.size) {
                    episodes = matchEpisodes
                    titles = matchTitles
                }
            }
        }

        val vodYear = item["vod_year"]?.jsonPrimitive?.contentOrNull
        val year = if (!vodYear.isNullOrBlank()) {
            Regex("\\d{4}").find(vodYear)?.value ?: "unknown"
        } else "unknown"

        val vodId = item["vod_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val vodName = item["vod_name"]?.jsonPrimitive?.contentOrNull ?: return null

        return SearchResult(
            id = vodId,
            title = vodName.trim().replace(Regex("\\s+"), " "),
            poster = item["vod_pic"]?.jsonPrimitive?.contentOrNull ?: "",
            episodes = episodes,
            episodesTitles = titles,
            source = resource.key,
            sourceName = resource.name,
            classType = item["vod_class"]?.jsonPrimitive?.contentOrNull,
            year = year,
            desc = cleanHtmlTags(item["vod_content"]?.jsonPrimitive?.contentOrNull),
            typeName = item["type_name"]?.jsonPrimitive?.contentOrNull,
            doubanId = item["vod_douban_id"]?.jsonPrimitive?.longOrNull
        )
    }
}
