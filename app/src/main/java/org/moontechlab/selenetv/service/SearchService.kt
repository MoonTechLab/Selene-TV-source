package org.moontechlab.selenetv.service

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.moontechlab.selenetv.model.SearchResource
import org.moontechlab.selenetv.model.SearchResult

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

    private var _isSearching = false
    private var _currentQuery: String? = null
    private var _completedSources = 0
    private var _totalSources = 0
    private val _sourceErrors = mutableMapOf<String, String>()
    private var _searchJob: Job? = null

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

        // 如果已有搜索，先停止
        if (_isSearching) {
            stopSearch()
        }

        _currentQuery = trimmedQuery
        _isSearching = true
        _completedSources = 0
        _totalSources = 0
        _sourceErrors.clear()

        try {
            withContext(Dispatchers.IO) {
                localSearch(trimmedQuery)
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "搜索被取消")
        } catch (e: Exception) {
            _eventFlow.emit(SearchEvent.Error("搜索失败: ${e.message}"))
            _isSearching = false
        }
    }

    /**
     * 本地搜索（并发搜索所有资源）
     */
    private suspend fun localSearch(query: String) = coroutineScope {
        try {
            // 获取搜索资源列表
            val allResources = ApiService.getSearchResources()
            val resources = allResources.filter { !it.disabled }

            if (resources.isEmpty()) {
                _eventFlow.emit(SearchEvent.Error("没有可用的搜索资源"))
                _isSearching = false
                return@coroutineScope
            }

            _totalSources = resources.size
            _completedSources = 0

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
            // 单个资源 20 秒超时
            val results = withTimeout(SINGLE_SOURCE_TIMEOUT_MS) {
                DownstreamService.searchFromApi(resource, query)
            }

            _completedSources++

            // 发送结果事件（只发送增量结果）
            if (results.isNotEmpty()) {
                _eventFlow.emit(SearchEvent.Results(results))
            }

            // 发送进度更新
            _eventFlow.emit(
                SearchEvent.Progress(
                    SearchProgress(
                        totalSources = _totalSources,
                        completedSources = _completedSources,
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
        _completedSources++
        _sourceErrors[resource.key] = errorMessage

        _eventFlow.emit(
            SearchEvent.Progress(
                SearchProgress(
                    totalSources = _totalSources,
                    completedSources = _completedSources,
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
        if (_completedSources < _totalSources) {
            _completedSources = _totalSources
        }

        _eventFlow.emit(
            SearchEvent.Progress(
                SearchProgress(
                    totalSources = _totalSources,
                    completedSources = _completedSources,
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
     * 停止搜索
     */
    fun stopSearch() {
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
