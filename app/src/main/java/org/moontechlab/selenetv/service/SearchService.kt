package org.moontechlab.selenetv.service

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.moontechlab.selenetv.model.SearchResource
import org.moontechlab.selenetv.model.SearchResult
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 搜索进度信息
 */
data class SearchProgress(
    val totalSources: Int,
    val completedSources: Int,
    val currentSource: String?,
    val isComplete: Boolean,
    val error: String? = null
)

/**
 * 搜索事件
 */
sealed class SearchEvent {
    data class Results(val results: List<SearchResult>) : SearchEvent()
    data class Progress(val progress: SearchProgress) : SearchEvent()
    data class Error(val message: String) : SearchEvent()
    data object Complete : SearchEvent()
}

/**
 * 流式搜索服务
 * 实现增量搜索结果推送
 */
object SearchService {
    private const val TAG = "SearchService"
    private const val SEARCH_TIMEOUT_MS = 15000L
    private const val SINGLE_SOURCE_TIMEOUT_MS = 13000L
    private const val SSE_CONNECT_TIMEOUT_MS = 15000

    // 搜索运行在服务自有的 scope 上，stopSearch 才能真正持有并取消 Job
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile private var _isSearching = false
    @Volatile private var _currentQuery: String? = null
    private val _completedSources = AtomicInteger(0)
    @Volatile private var _totalSources = 0
    private val _sourceErrors = ConcurrentHashMap<String, String>()
    @Volatile private var _searchJob: Job? = null
    // 服务端聚合搜索的 SSE 连接，stopSearch 时需主动 disconnect 才能让阻塞的 readLine 抛出
    @Volatile private var _serverConnection: HttpURLConnection? = null

    // 事件流
    private val _eventFlow = MutableSharedFlow<SearchEvent>(extraBufferCapacity = 64)
    val eventFlow: SharedFlow<SearchEvent> = _eventFlow.asSharedFlow()

    val isSearching: Boolean get() = _isSearching
    val currentQuery: String? get() = _currentQuery
    val sourceErrors: Map<String, String> get() = _sourceErrors.toMap()

    /**
     * 开始搜索
     */
    suspend fun startSearch(query: String) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            throw IllegalArgumentException("搜索查询不能为空")
        }

        // 取消上一次仍在进行的搜索并等待其结束，避免新旧结果交叉污染
        _searchJob?.cancelAndJoin()

        _currentQuery = trimmedQuery
        _isSearching = true
        _completedSources.set(0)
        _totalSources = 0
        _sourceErrors.clear()

        val origin = AppDataService.getSearchOriginSync()
        Log.d(TAG, "startSearch query=\"$trimmedQuery\" origin=${origin.key}")
        _searchJob = serviceScope.launch {
            try {
                when (origin) {
                    AppDataService.SearchOrigin.TV_DIRECT -> localSearch(trimmedQuery)
                    AppDataService.SearchOrigin.SERVER_AGGREGATED -> serverAggregatedSearch(trimmedQuery)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "搜索被取消")
            } catch (e: Exception) {
                _eventFlow.emit(SearchEvent.Error("搜索失败: ${e.message}"))
                _isSearching = false
            }
        }
    }

    /**
     * 本地搜索（并发搜索所有资源）
     */
    private suspend fun localSearch(query: String) = coroutineScope {
        try {
            // 获取搜索资源列表
            val allResources = org.moontechlab.selenetv.data.Repositories.sources().getSearchResources()
            val resources = allResources.filter { !it.disabled }

            if (resources.isEmpty()) {
                _eventFlow.emit(SearchEvent.Error("没有可用的搜索资源"))
                _isSearching = false
                return@coroutineScope
            }

            _totalSources = resources.size
            _completedSources.set(0)

            // 发送初始进度
            _eventFlow.emit(
                SearchEvent.Progress(
                    SearchProgress(
                        totalSources = _totalSources,
                        completedSources = 0,
                        currentSource = null,
                        isComplete = false
                    )
                )
            )

            // 设置超时
            val searchWithTimeout = async {
                withTimeout(SEARCH_TIMEOUT_MS) {
                    // 并发搜索所有资源
                    resources.map { resource ->
                        async {
                            searchSingleResource(resource, query)
                        }
                    }.awaitAll()
                }
            }

            try {
                searchWithTimeout.await()
            } catch (e: TimeoutCancellationException) {
                handleTimeout()
                return@coroutineScope
            }

            // 发送完成事件
            _eventFlow.emit(
                SearchEvent.Progress(
                    SearchProgress(
                        totalSources = _totalSources,
                        completedSources = _totalSources,
                        currentSource = null,
                        isComplete = true
                    )
                )
            )
            _eventFlow.emit(SearchEvent.Complete)
            _isSearching = false

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _eventFlow.emit(SearchEvent.Error("本地搜索异常: ${e.message}"))
            _isSearching = false
        }
    }

    /**
     * 搜索单个资源
     */
    private suspend fun searchSingleResource(resource: SearchResource, query: String) {
        try {
            // 单个资源 13 秒超时
            val results = withTimeout(SINGLE_SOURCE_TIMEOUT_MS) {
                DownstreamService.searchFromApi(resource, query)
            }

            val completed = _completedSources.incrementAndGet()

            // 发送结果事件（只发送增量结果）
            if (results.isNotEmpty()) {
                _eventFlow.emit(SearchEvent.Results(results))
            }

            // 发送进度更新
            _eventFlow.emit(
                SearchEvent.Progress(
                    SearchProgress(
                        totalSources = _totalSources,
                        completedSources = completed,
                        currentSource = resource.name,
                        isComplete = false
                    )
                )
            )
        } catch (e: TimeoutCancellationException) {
            handleSourceError(resource, "搜索超时（13秒）")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleSourceError(resource, e.message ?: "未知错误")
        }
    }

    /**
     * 处理单个资源搜索错误
     */
    private suspend fun handleSourceError(resource: SearchResource, errorMessage: String) {
        val completed = _completedSources.incrementAndGet()
        _sourceErrors[resource.key] = errorMessage

        _eventFlow.emit(
            SearchEvent.Progress(
                SearchProgress(
                    totalSources = _totalSources,
                    completedSources = completed,
                    currentSource = resource.name,
                    isComplete = false,
                    error = errorMessage
                )
            )
        )
    }

    /**
     * 处理超时
     */
    private suspend fun handleTimeout() {
        if (_completedSources.get() < _totalSources) {
            _completedSources.set(_totalSources)
        }

        _eventFlow.emit(
            SearchEvent.Progress(
                SearchProgress(
                    totalSources = _totalSources,
                    completedSources = _completedSources.get(),
                    currentSource = null,
                    isComplete = true
                )
            )
        )
        _eventFlow.emit(SearchEvent.Error("搜索超时（15秒）"))
        _eventFlow.emit(SearchEvent.Complete)
        _isSearching = false
    }

    /**
     * 服务端聚合搜索（SSE 流式）
     * 命中条件由调用方在 startSearch 中读 SearchOrigin 决定
     */
    private suspend fun serverAggregatedSearch(query: String) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val baseUrl = AppDataService.getServerUrlSync()
                ?: throw IllegalStateException("未配置服务器地址")
            val cookies = AppDataService.getCookiesSync()
                ?: throw IllegalStateException("未登录，请先登录")
            val cleanBase = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
            val url = "$cleanBase/api/search/ws?q=${URLEncoder.encode(query, "UTF-8")}"

            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = SSE_CONNECT_TIMEOUT_MS
                // SSE 长连接：读超时设为 0（无限），靠 stopSearch.disconnect() 终止
                readTimeout = 0
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Cookie", cookies)
            }
            _serverConnection = connection

            val code = connection.responseCode
            if (code == 401) {
                _eventFlow.emit(SearchEvent.Error("登录状态失效，请重新登录"))
                _eventFlow.emit(SearchEvent.Complete)
                _isSearching = false
                return@withContext
            }
            if (code < 200 || code >= 300) {
                val msg = if (code == 404)
                    "服务端不支持流式聚合搜索，请检查后端版本"
                else
                    "服务端搜索失败 ($code)"
                _eventFlow.emit(SearchEvent.Error(msg))
                _eventFlow.emit(SearchEvent.Complete)
                _isSearching = false
                return@withContext
            }

            Log.d(TAG, "服务端 SSE 已连接 code=$code url=$url")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var completedNormally = false
                while (isActive) {
                    val line = try {
                        reader.readLine()
                    } catch (_: IOException) {
                        null
                    } ?: break

                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue

                    val obj = try {
                        json.parseToJsonElement(payload).jsonObject
                    } catch (_: Exception) {
                        continue
                    }

                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "start" -> {
                            _totalSources = obj["totalSources"]?.jsonPrimitive?.intOrNull ?: 0
                            _completedSources.set(0)
                            _eventFlow.emit(SearchEvent.Progress(SearchProgress(
                                totalSources = _totalSources,
                                completedSources = 0,
                                currentSource = null,
                                isComplete = false
                            )))
                        }
                        "source_result" -> {
                            val sourceName = obj["sourceName"]?.jsonPrimitive?.contentOrNull
                            val arr = obj["results"]?.jsonArray
                            if (arr != null && arr.isNotEmpty()) {
                                val parsed = arr.mapNotNull { el ->
                                    try {
                                        json.decodeFromJsonElement(SearchResult.serializer(), el)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "服务端搜索条目解析失败: ${e.message} payload=$el")
                                        null
                                    }
                                }
                                if (parsed.isNotEmpty()) {
                                    _eventFlow.emit(SearchEvent.Results(parsed))
                                }
                            }
                            val completed = _completedSources.incrementAndGet()
                            _eventFlow.emit(SearchEvent.Progress(SearchProgress(
                                totalSources = _totalSources,
                                completedSources = completed,
                                currentSource = sourceName,
                                isComplete = false
                            )))
                        }
                        "source_error" -> {
                            val sourceKey = obj["source"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val sourceName = obj["sourceName"]?.jsonPrimitive?.contentOrNull ?: sourceKey
                            val errMsg = obj["error"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
                            if (sourceKey.isNotEmpty()) _sourceErrors[sourceKey] = errMsg
                            val completed = _completedSources.incrementAndGet()
                            _eventFlow.emit(SearchEvent.Progress(SearchProgress(
                                totalSources = _totalSources,
                                completedSources = completed,
                                currentSource = sourceName,
                                isComplete = false,
                                error = errMsg
                            )))
                        }
                        "complete" -> {
                            _eventFlow.emit(SearchEvent.Progress(SearchProgress(
                                totalSources = _totalSources,
                                completedSources = _totalSources,
                                currentSource = null,
                                isComplete = true
                            )))
                            _eventFlow.emit(SearchEvent.Complete)
                            _isSearching = false
                            completedNormally = true
                            return@withContext
                        }
                    }
                }
                if (!completedNormally) {
                    // 流被外部关闭或自然 EOF，未收到 complete：补一个收尾
                    _eventFlow.emit(SearchEvent.Progress(SearchProgress(
                        totalSources = _totalSources,
                        completedSources = _completedSources.get(),
                        currentSource = null,
                        isComplete = true
                    )))
                    _eventFlow.emit(SearchEvent.Complete)
                    _isSearching = false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _eventFlow.emit(SearchEvent.Error("服务端搜索异常: ${e.message}"))
            _eventFlow.emit(SearchEvent.Complete)
            _isSearching = false
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
            _serverConnection = null
        }
    }

    /**
     * 停止搜索
     */
    fun stopSearch() {
        // 先关连接才能让阻塞中的 readLine 立即抛 IOException 返回
        try { _serverConnection?.disconnect() } catch (_: Exception) {}
        _serverConnection = null
        _searchJob?.cancel()
        _searchJob = null
        _isSearching = false
        _currentQuery = null
    }

    /**
     * 合并和去重搜索结果
     */
    fun deduplicateResults(results: List<SearchResult>): List<SearchResult> {
        val seen = mutableSetOf<String>()
        return results.filter { result ->
            val key = "${result.source}+${result.id}"
            if (seen.contains(key)) {
                false
            } else {
                seen.add(key)
                true
            }
        }
    }

    /**
     * 清理资源
     */
    fun dispose() {
        stopSearch()
    }
}
