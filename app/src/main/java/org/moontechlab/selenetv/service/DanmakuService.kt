package org.moontechlab.selenetv.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.moontechlab.selenetv.model.DanmakuComment
import org.moontechlab.selenetv.service.danmaku.BilibiliDanmakuSource
import org.moontechlab.selenetv.service.danmaku.DanmakuSource

/**
 * 弹幕编排：标题+集号 → 弹幕列表。串 [DanmakuSource] 的 search→episodes→comments，
 * 结果进 [DoubanCacheService] 磁盘缓存。
 *
 * best-effort：任何失败/无匹配返回空列表，绝不抛异常——弹幕缺席不可中断播放。
 * Phase 0 固定单源（B站）；Phase 1 起 [source] 改由脚本引擎按远程清单提供。
 */
object DanmakuService {
    private val source: DanmakuSource = BilibiliDanmakuSource()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val TTL_HIT = 7L * 24 * 60 * 60 * 1000   // 命中 7 天
    private const val TTL_MISS = 12L * 60 * 60 * 1000        // 未命中 12 小时（数据后续可能补）

    /**
     * 拉取某集弹幕。
     * @param title        影片标题（用于源内搜索匹配）
     * @param episode1Based 1 基集号；点播单集传 1
     */
    suspend fun fetch(
        title: String,
        episode1Based: Int,
        cache: DoubanCacheService,
    ): List<DanmakuComment> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        val cacheKey = "danmaku_${source.providerName}_${title}_$episode1Based"
        runCatching { cache.get(cacheKey) { json.decodeFromString<List<DanmakuComment>>(it) } }
            .getOrNull()?.let { return@withContext it }

        val result = runCatching {
            val media = source.search(title).firstOrNull() ?: return@runCatching emptyList()
            android.util.Log.i("DanmakuService", "match '$title' -> ${media.title} (${media.mediaId})")
            val episodes = source.getEpisodes(media.mediaId)
            val ep = episodes.getOrNull(episode1Based - 1) ?: episodes.firstOrNull()
            ?: return@runCatching emptyList()
            source.getComments(ep.episodeId)
        }.getOrDefault(emptyList())
        android.util.Log.i("DanmakuService", "fetch '$title' ep$episode1Based -> ${result.size} 条")

        runCatching {
            cache.set(cacheKey, json.encodeToString(result), if (result.isEmpty()) TTL_MISS else TTL_HIT)
        }
        result
    }
}
