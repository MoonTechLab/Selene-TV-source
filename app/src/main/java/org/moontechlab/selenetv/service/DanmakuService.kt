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
import org.moontechlab.selenetv.service.danmaku.DanmakuEpisode
import org.moontechlab.selenetv.service.danmaku.DanmakuMedia
import org.moontechlab.selenetv.service.danmaku.DanmakuSource
import org.moontechlab.selenetv.service.danmaku.DanmakuSources
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

    /** 某源 search 命中的一条候选剧集。score = matchScore（仅供排序/调试）。 */
    data class Candidate(val mediaId: String, val title: String, val score: Int)

    /**
     * search 命中的候选源：携带该源 **top-3** 候选剧集（按分降序）。
     * activeMediaId = 当前生效剧集（默认最高分那条；用户可在二级菜单改用其它候选）。
     * mediaId 留待 [fetchComments] 取分集用。
     */
    data class SourceMatch(
        val key: String,
        val display: String,
        val candidates: List<Candidate>,
        val activeMediaId: String = candidates.firstOrNull()?.mediaId ?: "",
    ) {
        val activeTitle: String get() = candidates.firstOrNull { it.mediaId == activeMediaId }?.title ?: ""
    }

    @Serializable
    private data class Cached(val timestamp: Long, val list: List<DanmakuComment>)

    private fun sourceFor(context: Context, def: DanmakuSources.Def): DanmakuSource =
        sources.getOrPut(def.key) { def.factory() }

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
                        val cands = pickTopMatches(title, sourceFor(context, def).search(title), 3)
                        if (cands.isEmpty()) null else SourceMatch(def.key, def.display, cands)
                    }.getOrNull()
                }
            }.awaitAll()
        }
        // 按「最佳候选的匹配分」降序 —— 选源/菜单都认标题匹配质量，不再按注册表优先级。
        defs.mapNotNull { def -> matched.firstOrNull { it?.key == def.key } }
            .sortedByDescending { it.candidates.firstOrNull()?.score ?: 0 }
    }

    // 标题归一：去书名号/括号/分隔符/空白并转小写，用于跨源标题比对。
    private fun normalizeTitle(s: String): String =
        s.replace(Regex("[《》「」『』\\[\\]()（）·•・:：·\\-—_~!！?？,，.。\\s]"), "").lowercase()

    private val CN_NUM = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10,
        '壹' to 1, '贰' to 2, '叁' to 3, '肆' to 4, '伍' to 5, '陆' to 6, '柒' to 7, '捌' to 8, '玖' to 9, '拾' to 10,
    )
    private fun romanToInt(s: String): Int {
        val m = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var r = 0; var i = 0; val u = s.uppercase()
        while (i < u.length) {
            val cur = m[u[i]] ?: return 0
            if (i + 1 < u.length && cur < (m[u[i + 1]] ?: 0)) { r += (m[u[i + 1]] ?: 0) - cur; i += 2 }
            else { r += cur; i++ }
        }
        return r
    }

    // 季度尾标记的匹配/剥离正则（移植自 misaka get_season_from_title 的模式集）。
    private val SEASON_PATTERNS: List<Pair<Regex, (MatchResult) -> Int?>> = listOf(
        Regex("(?:S|Season)\\s*(\\d+)", RegexOption.IGNORE_CASE) to { m -> m.groupValues[1].toIntOrNull() },
        Regex("第\\s*([一二三四五六七八九十壹贰叁肆伍陆柒捌玖拾\\d])\\s*[季部幕]") to { m ->
            val g = m.groupValues[1]; g.toIntOrNull() ?: CN_NUM[g[0]]
        },
        Regex("([一二三四五六七八九十壹贰叁肆伍陆柒捌玖拾])\\s*之\\s*章") to { m -> CN_NUM[m.groupValues[1][0]] },
        Regex("\\s+([Ⅰ-Ⅻ])(?=\\s|$)") to { m -> "ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ".indexOf(m.groupValues[1][0]).let { if (it < 0) null else it + 1 } },
        Regex("\\s+([IVXLCDM]+)\\b", RegexOption.IGNORE_CASE) to { m -> romanToInt(m.groupValues[1]).takeIf { it > 0 } },
        Regex("[^\\d](\\d{1,2})\\s*$") to { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 1..20 } },
    )

    /** 从标题解析季度，无则默认 1（移植自 misaka get_season_from_title）。 */
    internal fun seasonFromTitle(title: String): Int {
        if (title.isBlank()) return 1
        for ((re, handler) in SEASON_PATTERNS) {
            re.find(title)?.let { mr -> handler(mr)?.let { return it } }
        }
        return 1
    }

    // 归一标题里剥掉季度尾标记，得到「基础剧名」用于跨季比对。
    private fun stripSeason(norm: String): String =
        norm.replace(Regex("第[一二三四五六七八九十壹贰叁肆伍陆柒捌玖拾\\d]+[季部幕]"), "")
            .replace(Regex("[一二三四五六七八九十]之章"), "")
            .replace(Regex("season\\d+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(.*[^\\d])\\d{1,2}$")) { it.groupValues[1] }
            .ifEmpty { norm }

    /** Levenshtein 相似度（0~100），近似 thefuzz.ratio，用于标题模糊匹配。 */
    private fun ratio(a: String, b: String): Int {
        if (a.isEmpty() && b.isEmpty()) return 100
        if (a.isEmpty() || b.isEmpty()) return 0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val cur = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev else minOf(prev, dp[j], dp[j - 1]) + 1
                prev = cur
            }
        }
        val maxLen = maxOf(a.length, b.length)
        return Math.round(100.0 * (1.0 - dp[b.length].toDouble() / maxLen)).toInt()
    }

    // 标题相似度评分（仿 misaka 加权，但只算「标题+季度」维度；不命中返回 0）。
    private fun matchScore(query: String, candTitle: String): Int {
        val nq = normalizeTitle(query); val nc = normalizeTitle(candTitle)
        if (nq.isEmpty() || nc.isEmpty()) return 0
        if (nq == nc) return 10000                       // 去标点完全一致（含季度）
        val qBase = stripSeason(nq); val cBase = stripSeason(nc)
        val sameSeason = seasonFromTitle(query) == seasonFromTitle(candTitle)
        // 同分桶内再用 ratio(0~100) 做细排，使「同档」的源/候选也按真实接近度排序（桶间不重叠：5000 段 > 3000 段 > 模糊≤100）。
        if (qBase.isNotEmpty() && qBase == cBase) return if (sameSeason) 5000 + ratio(nq, nc) else 0  // 同剧，仅同季算命中
        // 跨平台命名差异：一方在基础剧名后接篇章/篇名（爱奇艺「凡人修仙传-风起天南」、腾讯「凡人修仙传仙界篇」
        // vs B站「凡人修仙传」）。较短基础名是较长名的前缀、且足够长（≥4，避免「主角」误中「女主角失格」之类）、
        // 同季 → 判命中（中等分，弱于完全/同名同季，强于纯模糊）。
        val shortB = if (qBase.length <= cBase.length) qBase else cBase
        val longB = if (qBase.length <= cBase.length) cBase else qBase
        if (shortB.length >= 4 && longB.startsWith(shortB) && sameSeason) return 3000 + ratio(qBase, cBase)
        val r = ratio(qBase, cBase)                      // 基础剧名模糊
        return if (r >= 85 && sameSeason) r else 0       // 阈值 85，且季度一致
    }

    /**
     * 从某源 search 候选里挑与剧名最匹配的一个（misaka 思路：黑名单滤派生 → 相似度加权打分 → 取最高）。
     * 设相关性下限（matchScore>0：完全/去标点/同剧同季/模糊≥85），低于则视为未命中——
     * 避免「主角」误中「《主角》原著超前看/超前大赏」这类派生或别的剧。
     */
    internal fun pickBestMatch(query: String, candidates: List<DanmakuMedia>): DanmakuMedia? {
        if (candidates.isEmpty() || normalizeTitle(query).isEmpty()) return null
        return candidates
            .filterNot { isJunkTitle(it.title) }                 // ① 搜索结果标题黑名单
            .map { it to matchScore(query, it.title) }           // ② 相似度打分
            .filter { it.second > 0 }                            // 下限：低于阈值不算命中
            .maxByOrNull { it.second }?.first                    // 取最高（同分保留 search 相关性首个）
    }

    /**
     * 取某源 search 候选里相关性 **top-n**（降序），供二级菜单列出「前几匹配剧集」。
     * 同 [pickBestMatch] 的黑名单 + 打分 + 下限；稳定排序保留同分时的 search 相关性顺序。
     */
    internal fun pickTopMatches(query: String, candidates: List<DanmakuMedia>, n: Int): List<Candidate> {
        if (candidates.isEmpty() || normalizeTitle(query).isEmpty()) return emptyList()
        return candidates
            .filterNot { isJunkTitle(it.title) }
            .map { Candidate(it.mediaId, it.title, matchScore(query, it.title)) }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(n)
    }

    // 垃圾分集黑名单（移植自 misaka search_result_global_blacklist）。中文表逐项照搬；
    // 英文表收紧为词边界匹配，剔除 CD/Disc/Scan/Logo/Info 等易误伤正片标题的短词。
    private val JUNK_CN = Regex(
        "特典|预告|广告|菜单|花絮|特辑|速看|资讯|彩蛋|直拍|直播回顾|片头|片尾|幕后|映像|番外篇|" +
            "纪录片|访谈|番外|短片|加更|走心|解忧|纯享|解读|揭秘|赏析|抢先看|超前看|名场面|" +
            // 派生/二创：解说、有声剧/广播剧、宣传片 PV、单集切片（注：用「单集」而非「第N集」——
            // 后者是正常分集名，isJunkTitle 同时给 filterJunkEpisodes 用，会误杀正片分集）
            "解说|有声剧|广播剧|单集|PV"
    )
    private val JUNK_EN = Regex(
        "\\b(NCOP|NCED|NC|OP|ED|SP|OVA|OAD|PV|MV|Menu|Bonus|Recap|Teaser|Trailer|Preview|Sample|EDPV|SongSpot|BDSpot)\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun isJunkTitle(t: String): Boolean = JUNK_CN.containsMatchIn(t) || JUNK_EN.containsMatchIn(t)

    /** 剔除预告/花絮等垃圾分集（避免错位拾到非正片）。全被判垃圾时保留原列表，防误杀清空。 */
    internal fun filterJunkEpisodes(episodes: List<DanmakuEpisode>): List<DanmakuEpisode> {
        if (episodes.isEmpty()) return episodes
        val kept = episodes.filterNot { isJunkTitle(it.title) }
        return if (kept.isEmpty()) episodes else kept
    }

    /** 分片滑动加载的块大小（秒）：分片源按播放进度附近「块」连续加载，不再一次性拉全集。 */
    const val CHUNK_SEC = 300

    /** 秒位置 → 块下标。 */
    fun chunkOf(sec: Int): Int = if (sec <= 0) 0 else sec / CHUNK_SEC

    /** 读某窗缓存（命中且未过期）；坏文件/过期删除并返回 null。 */
    private fun readCache(file: File): List<DanmakuComment>? {
        if (!file.exists()) return null
        return runCatching {
            val c = json.decodeFromString<Cached>(file.readText())
            if (System.currentTimeMillis() - c.timestamp < TTL_HIT) c.list else { file.delete(); null }
        }.onFailure { file.delete() }.getOrNull()
    }

    // 已解析的 episodeId 备忘：getEpisodes 是网络调用，分块连续加载时只解析一次。
    private val epIdMemo = ConcurrentHashMap<String, String>()

    private fun resolveEpisodeId(context: Context, sourceKey: String, mediaId: String, episode1Based: Int): String? {
        val memoKey = "$sourceKey|$mediaId|$episode1Based"
        epIdMemo[memoKey]?.let { return it }
        val def = DanmakuSources.byKey[sourceKey] ?: return null
        val src = sourceFor(context, def)
        val episodes = runCatching { filterJunkEpisodes(src.getEpisodes(mediaId)) }.getOrDefault(emptyList())
        val epId = (episodes.getOrNull(episode1Based - 1) ?: episodes.firstOrNull())?.episodeId ?: return null
        epIdMemo[memoKey] = epId
        return epId
    }

    /**
     * 拉取单个已命中源某集 **第 [chunk] 块**（时间窗 `[chunk*CHUNK_SEC, (chunk+1)*CHUNK_SEC)`）的弹幕。
     * 含按 (源,媒体,集,块) 独立磁盘缓存——分片源由播放页按播放进度连续拉相邻块（滑动加载），
     * 拉过的块复用缓存、不重拉。失败/空 → 空。
     */
    suspend fun fetchChunk(
        context: Context,
        sourceKey: String,
        mediaId: String,
        episode1Based: Int,
        chunk: Int,
    ): List<DanmakuComment> = withContext(Dispatchers.IO) {
        if (DanmakuSources.byKey[sourceKey] == null) return@withContext emptyList()
        val fromSec = chunk * CHUNK_SEC
        val toSec = fromSec + CHUNK_SEC
        val file = cacheFile(context, "${sourceKey}_${mediaId}_${episode1Based}_c$chunk")
        readCache(file)?.let { return@withContext it }
        val epId = resolveEpisodeId(context, sourceKey, mediaId, episode1Based) ?: return@withContext emptyList()
        val src = sourceFor(context, DanmakuSources.byKey[sourceKey]!!)
        val list = runCatching { src.getComments(epId, fromSec, toSec) }.getOrDefault(emptyList())
        android.util.Log.i("DanmakuService", "[$sourceKey] chunk$chunk[$fromSec,$toSec) -> ${list.size} 条")
        if (list.isNotEmpty()) runCatching { file.writeText(json.encodeToString(Cached(System.currentTimeMillis(), list))) }
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
