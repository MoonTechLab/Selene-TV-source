package org.moontechlab.selenetv.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.moontechlab.selenetv.model.TmdbArt
import org.moontechlab.selenetv.model.TmdbMediaRef
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * TMDB 服务：把豆瓣的 original_title + 年份解析成 TMDB 剧照(backdrop)与标题 logo。
 *
 * 豆瓣 rexxar 不返回 IMDb/TMDB id，故只能按 original_title 搜索（仅原名，不用中文标题，
 * 避免译名歧义）。年份用于消歧，命中后按热度取最佳。
 *
 * 调用约定：同步阻塞、运行在 [Dispatchers.IO]，失败返回 null（不抛异常，由详情页回退旧布局）。
 * TMDB 为正规 TLS，无需 trust-all，沿用裸 HttpURLConnection。
 *
 * 缓存：复用 [DoubanCacheService]（内存+磁盘+TTL），键前缀 `tmdb_art`。命中有图缓存 30 天，
 * 无图（负缓存）3 天，以便后续补图能较快生效。注意「清空豆瓣缓存」会一并清掉此缓存。
 */
object TmdbService {
    private const val API_BASE = "https://api.themoviedb.org/3"
    private const val IMG_BASE = "https://image.tmdb.org/t/p"
    private const val BACKDROP_SIZE = "w1280"
    private const val LOGO_SIZE = "w500"
    private const val TIMEOUT_MS = 15000

    private const val CACHE_PREFIX = "tmdb_art"
    private const val REF_PREFIX = "tmdb_ref"
    private const val TTL_HIT = 30L * 24 * 60 * 60 * 1000   // 有图：30 天
    private const val TTL_MISS = 3L * 24 * 60 * 60 * 1000   // 无图：3 天

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 按 original_title + year 解析 TMDB 剧照/ logo。任一环节失败或无图返回 null。
     * @param apiKey TMDB v3 API Key（用户在设置页填写）。为空直接返回 null。
     * @param cache 复用的磁盘缓存；需已 init（详情页在拉豆瓣详情时已初始化）。
     */
    suspend fun resolveArt(
        originalTitle: String,
        year: String,
        apiKey: String,
        cache: DoubanCacheService,
    ): TmdbArt? = withContext(Dispatchers.IO) {
        if (originalTitle.isBlank() || apiKey.isBlank()) return@withContext null

        val cacheKey = "${CACHE_PREFIX}_${originalTitle}_$year"

        // 命中缓存（含负缓存）：两字段皆 null 表示「查过没图」，直接返回 null 不再请求
        val cached = try {
            cache.get(cacheKey) { json.decodeFromString<TmdbArt>(it) }
        } catch (_: Exception) {
            null
        }
        if (cached != null) {
            return@withContext cached.takeIf { it.backdropUrl != null || it.logoUrl != null }
        }

        val art = try {
            val (mediaType, tmdbId) = search(originalTitle, year, apiKey) ?: return@withContext run {
                cache.set(cacheKey, json.encodeToString(TmdbArt()), TTL_MISS)
                null
            }
            fetchImages(mediaType, tmdbId, apiKey)
        } catch (e: Exception) {
            android.util.Log.d("TmdbService", "resolveArt 失败: ${e.message}")
            null
        }

        // 写缓存：有图 30 天，无图 3 天（负缓存避免每次进详情都打网络）
        cache.set(
            cacheKey,
            json.encodeToString(art ?: TmdbArt()),
            if (art?.backdropUrl != null || art?.logoUrl != null) TTL_HIT else TTL_MISS,
        )
        art
    }

    /**
     * 先用原名搜索；命中不到再去掉「季」后缀重试一次。
     *
     * 豆瓣对续作返回的 original_title 形如 "ぐらんぶる Season 2"/"某剧 第二季"，而 TMDB 以基础剧名
     * （"ぐらんぶる"）整体收录、按季归在同一条目下，带季后缀直接搜不到 → 旧布局。
     */
    private fun search(originalTitle: String, year: String, apiKey: String): Pair<String, Long>? {
        searchOnce(originalTitle, year, apiKey)?.let { return it }
        val stripped = stripSeasonSuffix(originalTitle)
        if (stripped.isNotBlank() && stripped != originalTitle) {
            return searchOnce(stripped, year, apiKey)
        }
        return null
    }

    /**
     * 从续作原名后缀提取季号；无明确季标记返回 1。与 [stripSeasonSuffix] 同套模式。
     */
    internal fun parseSeasonNumber(title: String): Int {
        val t = title.trim()
        Regex("""\s+season\s+(\d+)$""", RegexOption.IGNORE_CASE).find(t)?.let { return it.groupValues[1].toInt() }
        Regex("""\s+(\d+)(st|nd|rd|th)\s+season$""", RegexOption.IGNORE_CASE).find(t)?.let { return it.groupValues[1].toInt() }
        Regex("""\s+season\s+([ivxlcdm]+)$""", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            romanToInt(m.groupValues[1])?.let { return it }
        }
        Regex("""第\s*([0-9一二三四五六七八九十百]+)\s*[季期]$""").find(t)?.let { m ->
            cnNumberToInt(m.groupValues[1])?.let { return it }
        }
        return 1
    }

    private fun romanToInt(s: String): Int? {
        val map = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100, 'd' to 500, 'm' to 1000)
        var total = 0
        var prev = 0
        for (c in s.lowercase().reversed()) {
            val v = map[c] ?: return null
            if (v < prev) total -= v else { total += v; prev = v }
        }
        return total.takeIf { it > 0 }
    }

    /** 覆盖季号常见范围 1–99（阿拉伯 + 简单中文数字）。 */
    private fun cnNumberToInt(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        val d = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        return when {
            s == "十" -> 10
            s.length == 1 -> d[s[0]]
            s.startsWith("十") -> 10 + (d[s[1]] ?: return null)
            s.contains("十") -> {
                val parts = s.split("十")
                val tens = d[parts[0][0]] ?: return null
                val ones = if (parts.size > 1 && parts[1].isNotEmpty()) (d[parts[1][0]] ?: return null) else 0
                tens * 10 + ones
            }
            else -> null
        }
    }

    /**
     * 去掉续作的「季」后缀，便于用基础剧名命中 TMDB。仅识别明确的季标记，避免误删正片名里的数字/词。
     * 例：「ぐらんぶる Season 2」→「ぐらんぶる」、「某剧 第二季」→「某剧」、「Foo 2nd Season」→「Foo」。
     */
    internal fun stripSeasonSuffix(title: String): String {
        val t = title.trim()
        val patterns = listOf(
            Regex("""\s+season\s+\d+$""", RegexOption.IGNORE_CASE),
            Regex("""\s+season\s+[ivxlcdm]+$""", RegexOption.IGNORE_CASE),
            Regex("""\s+\d+(st|nd|rd|th)\s+season$""", RegexOption.IGNORE_CASE),
            Regex("""\s*第\s*[0-9一二三四五六七八九十百]+\s*[季期]$"""),
        )
        for (p in patterns) {
            val r = p.replace(t, "")
            if (r != t) return r.trim()
        }
        return t
    }

    /** /search/multi?query=original_title → 过滤 movie/tv，年份命中优先、再按热度取最佳。 */
    private fun searchOnce(originalTitle: String, year: String, apiKey: String): Pair<String, Long>? {
        val q = URLEncoder.encode(originalTitle, "UTF-8")
        val url = "$API_BASE/search/multi?api_key=$apiKey&include_adult=false&query=$q"
        val (code, body) = httpGet(url)
        if (code != HttpURLConnection.HTTP_OK) return null

        val results = (json.parseToJsonElement(body).jsonObject["results"] as? JsonArray)
            .orEmpty().mapNotNull { it as? JsonObject }

        val candidates = results.filter {
            val mt = it["media_type"]?.jsonPrimitive?.contentOrNull
            mt == "movie" || mt == "tv"
        }
        if (candidates.isEmpty()) return null

        fun popularity(o: JsonObject) = o["popularity"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        fun yearOf(o: JsonObject): String? {
            val date = (o["release_date"] ?: o["first_air_date"])?.jsonPrimitive?.contentOrNull
            return date?.takeIf { it.length >= 4 }?.substring(0, 4)
        }

        // 年份一致的优先；无年份信息或无匹配则全体按热度
        val targetYear = year.takeIf { it.isNotBlank() }
        val pool = if (targetYear != null) {
            candidates.filter { yearOf(it) == targetYear }.ifEmpty { candidates }
        } else {
            candidates
        }
        val best = pool.maxByOrNull { popularity(it) } ?: return null

        val mediaType = best["media_type"]?.jsonPrimitive?.contentOrNull ?: return null
        val id = best["id"]?.jsonPrimitive?.longOrNull ?: return null
        return mediaType to id
    }

    /** /{movie|tv}/{id}/images → 选 backdrop + logo，拼成完整 URL。 */
    private fun fetchImages(mediaType: String, tmdbId: Long, apiKey: String): TmdbArt? {
        val url = "$API_BASE/$mediaType/$tmdbId/images?api_key=$apiKey"
        val (code, body) = httpGet(url)
        if (code != HttpURLConnection.HTTP_OK) return null
        val root = json.parseToJsonElement(body).jsonObject

        val backdrops = (root["backdrops"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val logos = (root["logos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

        val backdropUrl = pickBackdrop(backdrops)?.let { "$IMG_BASE/$BACKDROP_SIZE$it" }
        val logoUrl = pickLogo(logos)?.let { "$IMG_BASE/$LOGO_SIZE$it" }

        if (backdropUrl == null && logoUrl == null) return null
        return TmdbArt(backdropUrl = backdropUrl, logoUrl = logoUrl)
    }

    private fun path(o: JsonObject) = o["file_path"]?.jsonPrimitive?.contentOrNull
    private fun vote(o: JsonObject) = o["vote_average"]?.jsonPrimitive?.doubleOrNull ?: 0.0
    private fun lang(o: JsonObject) = o["iso_639_1"]?.jsonPrimitive?.contentOrNull

    /** 剧照：优先无文字版（iso 为 null）取最高分；否则全体取最高分。 */
    private fun pickBackdrop(items: List<JsonObject>): String? {
        val textless = items.filter { lang(it) == null }.maxByOrNull { vote(it) }
        return (textless ?: items.maxByOrNull { vote(it) })?.let { path(it) }
    }

    /** logo：中文优先 → 英文 → 其它，各组内取最高分。跳过 .svg（Coil 无法渲染）。 */
    private fun pickLogo(items: List<JsonObject>): String? {
        val png = items.filter { path(it)?.endsWith(".png") == true }
        val zh = png.filter { lang(it) == "zh" }.maxByOrNull { vote(it) }
        val en = png.filter { lang(it) == "en" }.maxByOrNull { vote(it) }
        val any = png.maxByOrNull { vote(it) }
        return (zh ?: en ?: any)?.let { path(it) }
    }

    internal fun parseImdbId(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["imdb_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun fetchImdbId(mediaType: String, tmdbId: Long, apiKey: String): String? {
        val (code, body) = httpGet("$API_BASE/$mediaType/$tmdbId/external_ids?api_key=$apiKey")
        if (code != HttpURLConnection.HTTP_OK) return null
        return parseImdbId(body)
    }

    /**
     * 解析片头片尾取数所需的 TMDB 标识。复用 [search]（original_title+year）拿 (mediaType,tmdbId)，
     * 季号取自 [parseSeasonNumber]，TV 再取 imdb（introdb.app 用）。失败/无配 key 返回 null。
     * 复用 [DoubanCacheService]，键前缀 tmdb_ref，命中 30d / 未命中 3d。
     */
    suspend fun resolveMediaRef(
        queryTitle: String,
        year: String,
        apiKey: String,
        cache: DoubanCacheService,
    ): TmdbMediaRef? = withContext(Dispatchers.IO) {
        if (queryTitle.isBlank() || apiKey.isBlank()) return@withContext null
        val cacheKey = "${REF_PREFIX}_${queryTitle}_$year"
        val cached = runCatching { cache.get(cacheKey) { json.decodeFromString<TmdbMediaRef>(it) } }.getOrNull()
        if (cached != null) return@withContext cached.takeIf { it.tmdbId > 0 }

        val ref = try {
            val (mediaType, tmdbId) = search(queryTitle, year, apiKey) ?: return@withContext run {
                cache.set(cacheKey, json.encodeToString(TmdbMediaRef("", 0, 1, null)), TTL_MISS)
                null
            }
            TmdbMediaRef(
                mediaType = mediaType,
                tmdbId = tmdbId,
                season = parseSeasonNumber(queryTitle),
                imdbId = if (mediaType == "tv") fetchImdbId(mediaType, tmdbId, apiKey) else null,
            )
        } catch (e: Exception) {
            android.util.Log.d("TmdbService", "resolveMediaRef 失败: ${e.message}")
            null
        }
        if (ref != null) cache.set(cacheKey, json.encodeToString(ref), TTL_HIT)
        ref
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val bodyText = if (code == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            code to bodyText
        } finally {
            connection.disconnect()
        }
    }
}
