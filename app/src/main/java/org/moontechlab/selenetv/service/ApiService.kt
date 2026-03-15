package org.moontechlab.selenetv.service

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import org.moontechlab.selenetv.model.FavoriteItem
import org.moontechlab.selenetv.model.LiveSource
import org.moontechlab.selenetv.model.PlayRecord
import org.moontechlab.selenetv.model.SearchResource
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * API 异常类
 */
class ApiException(
    message: String,
    val statusCode: Int = -1
) : Exception(message)

/**
 * API 服务
 * 处理与后端服务器的所有 HTTP 通信
 * 注意：所有方法都是阻塞方法，需要在后台线程调用
 * 如果请求失败会抛出 ApiException
 */
object ApiService {
    private const val TAG = "ApiService"
    private const val TIMEOUT = 30000 // 30 seconds

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 获取基础URL
     */
    private fun getBaseUrl(): String {
        return AppDataService.getServerUrlSync()
            ?: throw ApiException("服务器地址未配置，请先登录")
    }

    /**
     * 获取认证cookies
     */
    private fun getCookies(): String {
        return AppDataService.getCookiesSync()
            ?: throw ApiException("未登录，请先登录")
    }

    /**
     * 构建完整URL
     */
    private fun buildUrl(endpoint: String): String {
        val baseUrl = getBaseUrl()
        getCookies() // 验证登录状态

        val cleanBaseUrl = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        val cleanEndpoint = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
        return "$cleanBaseUrl$cleanEndpoint"
    }

    /**
     * 创建 HTTP 连接
     */
    private fun createConnection(
        url: String,
        method: String,
        includeAuth: Boolean = true
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = TIMEOUT
        connection.readTimeout = TIMEOUT
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")

        if (includeAuth) {
            val cookies = getCookies()
            connection.setRequestProperty("Cookie", cookies)
        }

        return connection
    }

    /**
     * 处理响应，失败时抛出 ApiException
     */
    private fun <T> handleResponse(
        connection: HttpURLConnection,
        transform: ((JsonObject) -> T)? = null
    ): T {
        val statusCode = connection.responseCode

        // 处理401未授权
        if (statusCode == 401) {
            throw ApiException("登录状态失效，请重新登录", statusCode)
        }

        // 处理其他错误状态码
        if (statusCode < 200 || statusCode >= 300) {
            val errorMessage = when (statusCode) {
                400 -> "请求参数错误"
                403 -> "没有权限访问"
                404 -> "请求的资源不存在"
                500 -> "服务器内部错误"
                else -> "网络请求失败 ($statusCode)"
            }
            throw ApiException(errorMessage, statusCode)
        }

        // 处理成功响应
        return try {
            val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            if (transform != null) {
                val jsonObject = json.parseToJsonElement(responseText).jsonObject
                transform(jsonObject)
            } else {
                @Suppress("UNCHECKED_CAST")
                responseText as T
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("响应数据解析失败: ${e.message}", statusCode)
        } finally {
            connection.disconnect()
        }
    }

    // ==================== HTTP 方法 ====================

    /**
     * GET 请求
     */
    private fun <T> get(
        endpoint: String,
        queryParameters: Map<String, String>? = null,
        transform: ((JsonObject) -> T)? = null
    ): T {
        var url = buildUrl(endpoint)
        if (!queryParameters.isNullOrEmpty()) {
            val params = queryParameters.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
            url = "$url?$params"
        }

        val connection = createConnection(url, "GET")
        return handleResponse(connection, transform)
    }

    /**
     * POST 请求
     */
    private fun <T> post(
        endpoint: String,
        body: Map<String, Any?>? = null,
        transform: ((JsonObject) -> T)? = null
    ): T {
        val url = buildUrl(endpoint)
        val connection = createConnection(url, "POST")
        connection.doOutput = true

        if (body != null) {
            val jsonBody = json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject(body)
            )
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }
        }

        return handleResponse(connection, transform)
    }

    /**
     * DELETE 请求
     */
    private fun <T> delete(
        endpoint: String,
        transform: ((JsonObject) -> T)? = null
    ): T {
        val url = buildUrl(endpoint)
        val connection = createConnection(url, "DELETE")
        return handleResponse(connection, transform)
    }

    /**
     * 构建 JsonObject
     */
    private fun buildJsonObject(map: Map<String, Any?>): JsonObject {
        return buildJsonObject {
            map.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, JsonPrimitive(value))
                    is Number -> put(key, JsonPrimitive(value))
                    is Boolean -> put(key, JsonPrimitive(value))
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        put(key, buildJsonObject(value as Map<String, Any?>))
                    }
                    null -> put(key, JsonNull)
                    else -> put(key, JsonPrimitive(value.toString()))
                }
            }
        }
    }

    // ==================== 收藏夹 API ====================

    /**
     * 获取收藏夹列表
     * @throws ApiException 请求失败时抛出
     */
    fun getFavorites(): List<FavoriteItem> {
        val data = get<JsonObject>("/api/favorites") { it }
        val favorites = mutableListOf<FavoriteItem>()
        data.forEach { (key, itemElement) ->
            val itemData = itemElement.jsonObject
            val parts = key.split("+")
            val source = if (parts.size > 1) parts[0] else ""
            val id = if (parts.size > 1) parts[1] else key

            favorites.add(
                FavoriteItem(
                    id = id,
                    source = source,
                    title = itemData["title"]?.jsonPrimitive?.contentOrNull ?: "",
                    sourceName = itemData["source_name"]?.jsonPrimitive?.contentOrNull ?: "",
                    year = itemData["year"]?.jsonPrimitive?.contentOrNull ?: "",
                    cover = itemData["cover"]?.jsonPrimitive?.contentOrNull ?: "",
                    totalEpisodes = itemData["total_episodes"]?.jsonPrimitive?.intOrNull ?: 0,
                    saveTime = itemData["save_time"]?.jsonPrimitive?.longOrNull ?: 0L,
                    origin = itemData["origin"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            )
        }
        favorites.sortByDescending { it.saveTime }
        return favorites.filter { it.origin != "live" }
    }

    /**
     * 添加收藏
     * @throws ApiException 请求失败时抛出
     */
    fun addFavorite(source: String, id: String, favoriteData: Map<String, Any?>) {
        val key = "$source+$id"
        val body = mapOf("key" to key, "favorite" to favoriteData)
        post<JsonObject>("/api/favorites", body) { it }
    }

    /**
     * 取消收藏
     * @throws ApiException 请求失败时抛出
     */
    fun unfavorite(source: String, id: String) {
        val key = "$source+$id"
        val encodedKey = URLEncoder.encode(key, "UTF-8")
        delete<JsonObject>("/api/favorites?key=$encodedKey") { it }
    }

    // ==================== 搜索历史 API ====================

    /**
     * 获取搜索历史
     * @throws ApiException 请求失败时抛出
     */
    fun getSearchHistory(): List<String> {
        val url = buildUrl("/api/searchhistory")
        val connection = createConnection(url, "GET")
        
        val statusCode = connection.responseCode
        if (statusCode == 401) {
            throw ApiException("登录状态失效，请重新登录", statusCode)
        }
        if (statusCode < 200 || statusCode >= 300) {
            throw ApiException("网络请求失败 ($statusCode)", statusCode)
        }
        
        return try {
            val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            json.decodeFromString<List<String>>(responseText)
        } catch (e: Exception) {
            throw ApiException("响应数据解析失败: ${e.message}", statusCode)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 添加搜索历史
     * @throws ApiException 请求失败时抛出
     */
    fun addSearchHistory(query: String) {
        val url = buildUrl("/api/searchhistory")
        val connection = createConnection(url, "POST")
        connection.doOutput = true
        
        val jsonBody = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject(mapOf("keyword" to query))
        )
        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(jsonBody)
            writer.flush()
        }
        
        val statusCode = connection.responseCode
        if (statusCode == 401) {
            throw ApiException("登录状态失效，请重新登录", statusCode)
        }
        if (statusCode < 200 || statusCode >= 300) {
            throw ApiException("网络请求失败 ($statusCode)", statusCode)
        }
        connection.disconnect()
    }

    /**
     * 清空搜索历史
     * @throws ApiException 请求失败时抛出
     */
    fun clearSearchHistory() {
        delete<JsonObject>("/api/searchhistory") { it }
    }

    /**
     * 删除单个搜索历史
     * @throws ApiException 请求失败时抛出
     */
    fun deleteSearchHistory(query: String) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        delete<JsonObject>("/api/searchhistory?keyword=$encodedQuery") { it }
    }

    // ==================== 播放记录 API ====================

    /**
     * 获取播放记录
     * @throws ApiException 请求失败时抛出
     */
    fun getPlayRecords(): Map<String, PlayRecord> {
        val data = get<JsonObject>("/api/playrecords") { it }
        val playRecords = mutableMapOf<String, PlayRecord>()
        data.forEach { (key, itemElement) ->
            val itemData = itemElement.jsonObject
            val parts = key.split("+")
            val source = if (parts.size > 1) parts[0] else ""
            val id = if (parts.size > 1) parts[1] else key

            playRecords[key] = PlayRecord(
                id = id,
                source = source,
                title = itemData["title"]?.jsonPrimitive?.contentOrNull ?: "",
                sourceName = itemData["source_name"]?.jsonPrimitive?.contentOrNull ?: "",
                year = itemData["year"]?.jsonPrimitive?.contentOrNull ?: "",
                cover = itemData["cover"]?.jsonPrimitive?.contentOrNull ?: "",
                index = itemData["index"]?.jsonPrimitive?.intOrNull ?: 0,
                totalEpisodes = itemData["total_episodes"]?.jsonPrimitive?.intOrNull ?: 0,
                playTime = itemData["play_time"]?.jsonPrimitive?.longOrNull ?: 0L,
                totalTime = itemData["total_time"]?.jsonPrimitive?.longOrNull ?: 0L,
                saveTime = itemData["save_time"]?.jsonPrimitive?.longOrNull ?: 0L,
                searchTitle = itemData["search_title"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }
        return playRecords
    }

    /**
     * 保存播放记录
     * @throws ApiException 请求失败时抛出
     */
    fun savePlayRecord(playRecord: PlayRecord) {
        val key = "${playRecord.source}+${playRecord.id}"
        val body = mapOf(
            "key" to key,
            "record" to mapOf(
                "id" to playRecord.id,
                "source" to playRecord.source,
                "title" to playRecord.title,
                "source_name" to playRecord.sourceName,
                "year" to playRecord.year,
                "cover" to playRecord.cover,
                "index" to playRecord.index,
                "total_episodes" to playRecord.totalEpisodes,
                "play_time" to playRecord.playTime,
                "total_time" to playRecord.totalTime,
                "save_time" to playRecord.saveTime,
                "search_title" to playRecord.searchTitle
            )
        )
        post<JsonObject>("/api/playrecords", body) { it }
    }

    /**
     * 删除播放记录
     * @throws ApiException 请求失败时抛出
     */
    fun deletePlayRecord(source: String, id: String) {
        val key = "$source+$id"
        val encodedKey = URLEncoder.encode(key, "UTF-8")
        delete<JsonObject>("/api/playrecords?key=$encodedKey") { it }
    }

    /**
     * 清空播放记录
     * @throws ApiException 请求失败时抛出
     */
    fun clearPlayRecords() {
        delete<JsonObject>("/api/playrecords") { it }
    }

    // ==================== 直播源 API ====================

    /**
     * 获取直播源列表
     * @throws ApiException 请求失败时抛出
     */
    fun getLiveSources(): List<LiveSource> {
        val url = buildUrl("/api/live/sources")
        val connection = createConnection(url, "GET")

        val statusCode = connection.responseCode
        if (statusCode == 401) {
            throw ApiException("登录状态失效，请重新登录", statusCode)
        }
        if (statusCode < 200 || statusCode >= 300) {
            throw ApiException("网络请求失败 ($statusCode)", statusCode)
        }

        return try {
            val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            val rootObj = json.parseToJsonElement(responseText).jsonObject
            val dataArray = rootObj["data"]?.jsonArray ?: return emptyList()

            dataArray.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val disabled = obj["disabled"]?.jsonPrimitive?.booleanOrNull ?: false
                    if (disabled) return@mapNotNull null
                    LiveSource(
                        key = obj["key"]?.jsonPrimitive?.contentOrNull ?: "",
                        name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                        ua = obj["ua"]?.jsonPrimitive?.contentOrNull ?: "",
                        epg = obj["epg"]?.jsonPrimitive?.contentOrNull ?: "",
                        from = obj["from"]?.jsonPrimitive?.contentOrNull ?: "",
                        disabled = disabled
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "解析直播源条目失败: ${e.message}")
                    null
                }
            }.filter { it.key.isNotEmpty() && it.url.isNotEmpty() }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("响应数据解析失败: ${e.message}", statusCode)
        } finally {
            connection.disconnect()
        }
    }

    // ==================== 搜索资源 API ====================

    /**
     * 获取搜索资源列表
     * @throws ApiException 请求失败时抛出
     */
    fun getSearchResources(): List<SearchResource> {
        val url = buildUrl("/api/search/resources")
        val connection = createConnection(url, "GET")
        
        val statusCode = connection.responseCode
        if (statusCode == 401) {
            throw ApiException("登录状态失效，请重新登录", statusCode)
        }
        if (statusCode < 200 || statusCode >= 300) {
            throw ApiException("网络请求失败 ($statusCode)", statusCode)
        }
        
        return try {
            val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            json.decodeFromString<List<SearchResource>>(responseText)
        } catch (e: Exception) {
            throw ApiException("响应数据解析失败: ${e.message}", statusCode)
        } finally {
            connection.disconnect()
        }
    }
}
