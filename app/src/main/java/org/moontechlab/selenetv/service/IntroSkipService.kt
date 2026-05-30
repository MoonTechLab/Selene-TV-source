package org.moontechlab.selenetv.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.moontechlab.selenetv.model.SkipSegment
import org.moontechlab.selenetv.model.SkipType
import org.moontechlab.selenetv.model.TmdbMediaRef
import java.net.HttpURLConnection
import java.net.URL

/**
 * 片头片尾时间戳：TIDB（主）+ IntroDB（补）双源。
 *
 * best-effort：任何失败/无数据返回空列表，绝不抛异常——播放不可被它中断。
 * 网络用裸 HttpURLConnection（对齐 [TmdbService]）。两站正规 HTTPS，无需 trust-all。
 */
object IntroSkipService {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val TIDB_BASE = "https://api.theintrodb.org/v3/media"
    private const val INTRODB_BASE = "https://api.introdb.app/segments"
    private const val TIMEOUT_MS = 12000
    private const val TTL_HIT = 30L * 24 * 60 * 60 * 1000   // 有段 30 天
    private const val TTL_MISS = 3L * 24 * 60 * 60 * 1000    // 空 3 天（数据后续可能被补）

    internal data class IntroDbCandidate(
        val type: SkipType,
        val startMs: Long,
        val endMs: Long,
        val confidence: Double,
        val submissionCount: Int,
    )

    /** TIDB /v3/media：intro/recap→同名，credits/preview→OUTRO。start null→0，end null→Long.MAX。 */
    internal fun parseTidb(body: String): List<SkipSegment> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        if (root["error"] != null) return emptyList()
        val out = mutableListOf<SkipSegment>()
        fun add(key: String, type: SkipType) {
            (root[key] as? JsonArray)?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val start = (o["start_ms"] as? JsonPrimitive)?.longOrNull ?: 0L
                val end = (o["end_ms"] as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE
                out += SkipSegment(type, start, end)
            }
        }
        add("intro", SkipType.INTRO)
        add("recap", SkipType.RECAP)
        add("credits", SkipType.OUTRO)
        add("preview", SkipType.OUTRO)
        return out
    }

    /** IntroDB /segments：intro/recap/outro 各单对象（null 跳过），带 confidence/submission_count。 */
    internal fun parseIntroDb(body: String): List<IntroDbCandidate> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<IntroDbCandidate>()
        fun add(key: String, type: SkipType) {
            val o = root[key] as? JsonObject ?: return
            val start = (o["start_ms"] as? JsonPrimitive)?.longOrNull ?: return
            val end = (o["end_ms"] as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE
            val conf = (o["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            val subs = (o["submission_count"] as? JsonPrimitive)?.intOrNull ?: 0
            out += IntroDbCandidate(type, start, end, conf, subs)
        }
        add("intro", SkipType.INTRO)
        add("recap", SkipType.RECAP)
        add("outro", SkipType.OUTRO)
        return out
    }

    /** TIDB 优先；IntroDB 仅补 TIDB 没有的类型，且 confidence≥0.5 或 submission_count≥2。 */
    internal fun mergeSegments(tidb: List<SkipSegment>, candidates: List<IntroDbCandidate>): List<SkipSegment> {
        val present = tidb.map { it.type }.toSet()
        val extra = candidates
            .filter { it.type !in present }
            .filter { it.confidence >= 0.5 || it.submissionCount >= 2 }
            .map { SkipSegment(it.type, it.startMs, it.endMs) }
        return tidb + extra
    }

    /** 命中 positionMs 的首个段；哨兵 endMs 夹到 durationMs；end 排他。 */
    internal fun pickActiveSegment(segments: List<SkipSegment>, positionMs: Long, durationMs: Long): SkipSegment? =
        segments.firstOrNull { seg ->
            val end = if (seg.endMs == Long.MAX_VALUE) durationMs else minOf(seg.endMs, durationMs)
            positionMs in seg.startMs until end
        }

    /**
     * 拉取某集的合并跳过段。best-effort：失败一律空列表。结果进 [DoubanCacheService]。
     * @param episode1Based 1 基集号（电影忽略）
     */
    suspend fun fetchSegments(
        ref: TmdbMediaRef,
        episode1Based: Int,
        cache: DoubanCacheService,
    ): List<SkipSegment> = withContext(Dispatchers.IO) {
        val cacheKey = "intro_seg_${ref.tmdbId}_${ref.season}_$episode1Based"
        runCatching { cache.get(cacheKey) { json.decodeFromString<List<SkipSegment>>(it) } }.getOrNull()
            ?.let { return@withContext it }

        val isTv = ref.mediaType == "tv"
        val tidb = runCatching {
            val url = if (isTv) "$TIDB_BASE?tmdb_id=${ref.tmdbId}&season=${ref.season}&episode=$episode1Based"
            else "$TIDB_BASE?tmdb_id=${ref.tmdbId}"
            parseTidb(httpGet(url))
        }.getOrDefault(emptyList())

        val merged = if (isTv && ref.imdbId != null) {
            val cands = runCatching {
                parseIntroDb(httpGet("$INTRODB_BASE?imdb_id=${ref.imdbId}&season=${ref.season}&episode=$episode1Based"))
            }.getOrDefault(emptyList())
            mergeSegments(tidb, cands)
        } else {
            tidb
        }

        runCatching { cache.set(cacheKey, json.encodeToString(merged), if (merged.isEmpty()) TTL_MISS else TTL_HIT) }
        merged
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else ""
        } finally {
            conn.disconnect()
        }
    }
}
