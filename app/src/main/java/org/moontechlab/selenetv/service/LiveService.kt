package org.moontechlab.selenetv.service

import android.util.Log
import org.moontechlab.selenetv.model.LiveChannel
import org.moontechlab.selenetv.model.LiveChannelGroup
import org.moontechlab.selenetv.model.LiveSource
import java.net.HttpURLConnection
import java.net.URL

/**
 * 直播服务
 * 管理直播源、频道的获取和缓存
 */
object LiveService {
    private const val TAG = "LiveService"
    private const val TIMEOUT = 30000
    private const val CACHE_DURATION_MS = 2 * 60 * 60 * 1000L // 2小时

    // 预编译正则
    private val TVG_URL_REGEX = Regex("""(?:x-tvg-url|url-tvg)="([^"]*)"""")
    private val TVG_ID_REGEX = Regex("""tvg-id="([^"]*)"""")
    private val TVG_NAME_REGEX = Regex("""tvg-name="([^"]*)"""")
    private val TVG_LOGO_REGEX = Regex("""tvg-logo="([^"]*)"""")
    private val GROUP_TITLE_REGEX = Regex("""group-title="([^"]*)"""")

    // 缓存
    private var sourcesCache: CacheItem<List<LiveSource>>? = null
    private val channelsCache = mutableMapOf<String, CacheItem<List<LiveChannel>>>()

    private data class CacheItem<T>(
        val data: T,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - timestamp > CACHE_DURATION_MS
    }

    /**
     * 获取直播源列表
     * @param forceRefresh 是否强制刷新缓存
     */
    fun getLiveSources(forceRefresh: Boolean = false): List<LiveSource> {
        if (!forceRefresh) {
            sourcesCache?.let { cache ->
                if (!cache.isExpired()) return cache.data
            }
        }

        return try {
            val sources = ApiService.getLiveSources()
            sourcesCache = CacheItem(sources)
            sources
        } catch (e: Exception) {
            Log.e(TAG, "获取直播源失败: ${e.message}")
            sourcesCache?.data ?: throw e
        }
    }

    /**
     * 获取直播频道列表
     * 从直播源 URL 获取 M3U 内容并解析
     */
    fun getLiveChannels(source: LiveSource, forceRefresh: Boolean = false): List<LiveChannel> {
        if (!forceRefresh) {
            channelsCache[source.key]?.let { cache ->
                if (!cache.isExpired()) return cache.data
            }
        }

        return try {
            val m3uContent = fetchM3uContent(source)
            val channels = parseM3U(source.key, m3uContent)
            channelsCache[source.key] = CacheItem(channels)
            channels
        } catch (e: Exception) {
            Log.e(TAG, "获取直播频道失败: ${e.message}")
            channelsCache[source.key]?.data ?: throw e
        }
    }

    /**
     * 将频道列表按分组归类
     */
    fun groupChannels(channels: List<LiveChannel>): List<LiveChannelGroup> {
        val grouped = linkedMapOf<String, MutableList<LiveChannel>>()
        for (channel in channels) {
            val groupName = channel.group.ifEmpty { "未分组" }
            grouped.getOrPut(groupName) { mutableListOf() }.add(channel)
        }
        return grouped.map { (name, chs) -> LiveChannelGroup(name, chs) }
    }

    /**
     * 获取 HTTP 请求 M3U 内容
     */
    private fun fetchM3uContent(source: LiveSource): String {
        val connection = URL(source.url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT
        connection.readTimeout = TIMEOUT
        connection.setRequestProperty(
            "User-Agent",
            source.ua.ifEmpty { "AptvPlayer/1.4.10" }
        )

        try {
            val statusCode = connection.responseCode
            if (statusCode != 200) {
                throw Exception("请求失败: $statusCode")
            }

            val bytes = connection.inputStream.readBytes()
            return decodeResponse(bytes)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 解码响应字节（支持 UTF-8 和 Latin-1）
     */
    private fun decodeResponse(bytes: ByteArray): String {
        return try {
            val text = String(bytes, Charsets.UTF_8)
            if (!text.contains('\uFFFD')) text
            else String(bytes, Charsets.ISO_8859_1)
        } catch (_: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    /**
     * 解析 M3U 内容为频道列表
     */
    private fun parseM3U(sourceKey: String, content: String): List<LiveChannel> {
        val channels = mutableListOf<LiveChannel>()
        val lines = content.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        var channelIndex = 0

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            if (line.startsWith("#EXTM3U")) {
                i++
                continue
            }

            if (line.startsWith("#EXTINF:")) {
                val tvgId = TVG_ID_REGEX.find(line)?.groupValues?.get(1) ?: ""
                val tvgName = TVG_NAME_REGEX.find(line)?.groupValues?.get(1) ?: ""
                val logo = TVG_LOGO_REGEX.find(line)?.groupValues?.get(1) ?: ""
                val group = GROUP_TITLE_REGEX.find(line)?.groupValues?.get(1) ?: "未分组"

                val commaIndex = line.lastIndexOf(',')
                val title = if (commaIndex != -1 && commaIndex < line.length - 1) {
                    line.substring(commaIndex + 1).trim()
                } else ""

                val name = title.ifEmpty { tvgName }

                if (i + 1 < lines.size && !lines[i + 1].startsWith("#")) {
                    val url = lines[i + 1]
                    if (name.isNotEmpty() && url.isNotEmpty()) {
                        try {
                            URL(url) // 验证 URL 格式
                            channels.add(
                                LiveChannel(
                                    id = "$sourceKey-$channelIndex",
                                    tvgId = tvgId,
                                    name = name,
                                    logo = logo,
                                    group = group,
                                    url = url
                                )
                            )
                            channelIndex++
                        } catch (_: Exception) {
                            // 无效 URL，跳过
                        }
                    }
                    i += 2
                    continue
                }
            }
            i++
        }

        return channels
    }

    /**
     * 清除所有缓存
     */
    fun clearAllCache() {
        sourcesCache = null
        channelsCache.clear()
    }

    /**
     * 清除频道缓存
     */
    fun clearChannelsCache() {
        channelsCache.clear()
    }
}
