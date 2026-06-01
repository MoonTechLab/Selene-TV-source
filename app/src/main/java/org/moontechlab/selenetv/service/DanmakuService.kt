package org.moontechlab.selenetv.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.moontechlab.selenetv.model.DanmakuComment
import org.moontechlab.selenetv.service.danmaku.DanmakuSource
import org.moontechlab.selenetv.service.danmaku.RhinoDanmakuSource
import java.io.File

/**
 * 弹幕编排：标题+集号 → 弹幕列表。串 [DanmakuSource] 的 search→episodes→comments。
 *
 * 自带独立磁盘缓存（`cacheDir/danmaku_cache/`，与豆瓣缓存隔离，便于单独清空）。
 * **只缓存命中（非空）结果**——miss 不写缓存，下次进入仍会重试。
 *
 * best-effort：任何失败/无匹配返回空列表，绝不抛异常——弹幕缺席不可中断播放。
 * Phase 1：[source] 由 Rhino 脚本引擎驱动，脚本从 assets 加载（Phase 2 改远程清单）。
 */
object DanmakuService {
    @Volatile private var source: DanmakuSource? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val TTL_HIT = 7L * 24 * 60 * 60 * 1000   // 命中 7 天
    private const val CACHE_DIR = "danmaku_cache"

    @Serializable
    private data class Cached(val timestamp: Long, val list: List<DanmakuComment>)

    private fun source(context: Context): DanmakuSource =
        source ?: synchronized(this) {
            source ?: RhinoDanmakuSource(context, "danmaku/bilibili.js", "bilibili").also { source = it }
        }

    /**
     * 拉取某集弹幕。
     * @param title        影片标题（用于源内搜索匹配）
     * @param episode1Based 1 基集号；点播单集传 1
     */
    suspend fun fetch(
        context: Context,
        title: String,
        episode1Based: Int,
    ): List<DanmakuComment> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        val src = source(context)
        val file = cacheFile(context, "${src.providerName}_${title}_$episode1Based")

        // 读缓存（命中且未过期）
        if (file.exists()) {
            runCatching {
                val cached = json.decodeFromString<Cached>(file.readText())
                if (System.currentTimeMillis() - cached.timestamp < TTL_HIT) return@withContext cached.list
                file.delete()
            }.onFailure { file.delete() }
        }

        val result = runCatching {
            val media = src.search(title).firstOrNull() ?: return@runCatching emptyList()
            android.util.Log.i("DanmakuService", "match '$title' -> ${media.title} (${media.mediaId})")
            val episodes = src.getEpisodes(media.mediaId)
            val ep = episodes.getOrNull(episode1Based - 1) ?: episodes.firstOrNull()
            ?: return@runCatching emptyList()
            src.getComments(ep.episodeId)
        }.getOrDefault(emptyList())
        android.util.Log.i("DanmakuService", "fetch '$title' ep$episode1Based -> ${result.size} 条")

        // 只缓存命中（非空）；miss 不写，下次重试
        if (result.isNotEmpty()) {
            runCatching {
                file.writeText(json.encodeToString(Cached(System.currentTimeMillis(), result)))
            }
        }
        result
    }

    /** 清空弹幕缓存目录。 */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        runCatching { File(context.cacheDir, CACHE_DIR).deleteRecursively() }
        Unit
    }

    private fun cacheFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, CACHE_DIR).apply { if (!exists()) mkdirs() }
        val name = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$name.json")
    }
}
