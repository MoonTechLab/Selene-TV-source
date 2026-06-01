package org.moontechlab.selenetv.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.moontechlab.selenetv.model.DanmakuComment
import org.moontechlab.selenetv.service.danmaku.DanmakuSource
import org.moontechlab.selenetv.service.danmaku.DanmakuSources
import org.moontechlab.selenetv.service.danmaku.RhinoDanmakuSource
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 弹幕编排：标题+集号 → 各源弹幕。串每个 [DanmakuSource] 的 search→episodes→comments。
 *
 * Phase 2 多源聚合（产品形态 C）：
 * - 进播放页/切集时**只并发跑 search**（[probe]，轻量）确定哪些源命中该剧；命中源进「弹幕源」chip。
 * - **弹幕正文按需拉**（[fetchComments]）：默认渲染优先级最高的命中源；用户切到某源时才拉那个源的弹幕。
 * 这样上屏只一个源（时间轴自洽），且不会一进页就把所有源的分段弹幕全拉下来压垮带宽。
 *
 * 自带独立磁盘缓存（`cacheDir/danmaku_cache/`，键含 provider，便于按源命中/隔离清空）。
 * **只缓存命中（非空）结果**——miss 不写缓存，下次进入仍会重试。
 * best-effort：任何失败/无匹配 → 空，绝不抛异常（弹幕缺席不可中断播放）。
 */
object DanmakuService {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val sources = ConcurrentHashMap<String, DanmakuSource>()

    private const val TTL_HIT = 7L * 24 * 60 * 60 * 1000   // 命中 7 天
    private const val CACHE_DIR = "danmaku_cache"

    /** search 命中的候选源（尚未拉弹幕）。mediaId 留待 [fetchComments] 取分集用。 */
    data class SourceMatch(val key: String, val display: String, val mediaId: String)

    @Serializable
    private data class Cached(val timestamp: Long, val list: List<DanmakuComment>)

    private fun sourceFor(context: Context, def: DanmakuSources.Def): DanmakuSource =
        sources.getOrPut(def.key) {
            RhinoDanmakuSource(context.applicationContext, def.asset, def.key)
        }

    /**
     * 并发对所有启用源跑 **search**，返回命中该剧的候选源（按注册表优先级顺序，首项即默认渲染源）。
     * 只调 search，不拉弹幕正文 —— 轻量，进页/切集即可全量跑。
     */
    suspend fun probe(context: Context, title: String): List<SourceMatch> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        val enabled = AppDataService.getEnabledDanmakuSourcesSync()
        val defs = DanmakuSources.ALL.filter { it.key in enabled }
        if (defs.isEmpty()) return@withContext emptyList()

        val matched = coroutineScope {
            defs.map { def ->
                async {
                    runCatching {
                        val media = sourceFor(context, def).search(title).firstOrNull()
                        media?.let { SourceMatch(def.key, def.display, it.mediaId) }
                    }.getOrNull()
                }
            }.awaitAll()
        }
        // 保持 defs（注册表）顺序 = 优先级顺序
        defs.mapNotNull { def -> matched.firstOrNull { it?.key == def.key } }
    }

    /**
     * 拉取**单个已命中源**某集的弹幕正文（getEpisodes→getComments），含磁盘缓存。失败/无匹配 → 空。
     * 仅在该源被选中（默认源或用户切换）时调用。
     */
    suspend fun fetchComments(
        context: Context,
        match: SourceMatch,
        episode1Based: Int,
    ): List<DanmakuComment> = withContext(Dispatchers.IO) {
        val def = DanmakuSources.byKey[match.key] ?: return@withContext emptyList()
        val file = cacheFile(context, "${match.key}_${match.mediaId}_$episode1Based")

        // 读缓存（命中且未过期）
        if (file.exists()) {
            val cached = runCatching {
                val c = json.decodeFromString<Cached>(file.readText())
                if (System.currentTimeMillis() - c.timestamp < TTL_HIT) c.list else { file.delete(); null }
            }.onFailure { file.delete() }.getOrNull()
            if (cached != null) return@withContext cached
        }

        val list = runCatching {
            val src = sourceFor(context, def)
            val episodes = src.getEpisodes(match.mediaId)
            val ep = episodes.getOrNull(episode1Based - 1) ?: episodes.firstOrNull()
            ?: return@runCatching emptyList()
            src.getComments(ep.episodeId)
        }.getOrDefault(emptyList())
        android.util.Log.i("DanmakuService", "[${match.key}] ep$episode1Based -> ${list.size} 条")

        // 只缓存命中（非空）；miss 不写，下次重试
        if (list.isNotEmpty()) {
            runCatching { file.writeText(json.encodeToString(Cached(System.currentTimeMillis(), list))) }
        }
        list
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
