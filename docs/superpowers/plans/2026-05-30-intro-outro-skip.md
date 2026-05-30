# 跳过片头/片尾 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在点播播放页提供手动「跳过片头/回顾/片尾」浮层按钮，时间戳来自 TIDB（theintrodb.org）+ IntroDB（introdb.app）双源。

**Architecture:** 复用 `TmdbService` 把豆瓣原名解析成 `(tmdbId, season, imdbId)`，经 `PlayerLaunch.tmdbRef` 传入播放页；新 `IntroSkipService`（best-effort，不抛异常）按集拉取并合并双源段，结果进 `DoubanCacheService`。UI 是 root Box 内的可聚焦单色按钮，焦点栈 `跳过键↕倍速↕进度条↕面板`，纯手动、无自动跳过、无设置开关。

**Tech Stack:** Kotlin / Compose for TV / kotlinx.serialization / 裸 `HttpURLConnection`（与 `TmdbService` 一致）/ JUnit4。

> 与 spec 的一处实现选择：网络层用裸 `HttpURLConnection`（对齐 `TmdbService`），而非 spec 措辞里的 OkHttp。两站均正规 HTTPS，无需 trust-all，效果等价。

参考 spec：`docs/superpowers/specs/2026-05-30-intro-outro-skip-design.md`

---

## 文件结构

| 文件 | 职责 | 新建/修改 |
|---|---|---|
| `model/SkipSegment.kt` | `SkipSegment` 数据类 + `SkipType` 枚举 | 新建 |
| `model/Tmdb.kt` | 追加 `TmdbMediaRef` | 修改 |
| `service/TmdbService.kt` | `resolveMediaRef` + `parseSeasonNumber` + `parseImdbId` + `fetchImdbId` | 修改 |
| `service/IntroSkipService.kt` | 双源拉取/解析/合并/缓存 + `pickActiveSegment` | 新建 |
| `screen/player/PlayerLaunch.kt` | 追加 `tmdbRef` 字段 | 修改 |
| `screen/DetailScreen.kt` | 解析并注入 `tmdbRef` | 修改 |
| `screen/player/PlaybackSpeed.kt` | `SpeedPill` 追加 `upTarget` | 修改 |
| `screen/player/IntroSkipOverlay.kt` | 跳过按钮组件 | 新建 |
| `screen/player/PlayerScreen.kt` | 取数 effect + 焦点借还 + 渲染 + 按键路由 | 修改 |
| `service/TmdbServiceTest.kt` | `parseSeasonNumber` / `parseImdbId` 测试 | 修改 |
| `service/IntroSkipServiceTest.kt` | 解析/合并/选段测试 | 新建 |

验证命令：`./gradlew testDebugUnitTest`（单测）、`./gradlew assembleDebug`（编译）、真机 E2E（Task 11）。

---

## Task 1: 模型 SkipSegment / SkipType / TmdbMediaRef

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/model/SkipSegment.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/model/Tmdb.kt`

- [ ] **Step 1: 创建 SkipSegment 模型**

`model/SkipSegment.kt`：
```kotlin
package org.moontechlab.selenetv.model

import kotlinx.serialization.Serializable

/** 跳过段类型。OUTRO 含片尾字幕 / 下集预告。 */
enum class SkipType { INTRO, RECAP, OUTRO }

/**
 * 一个可跳过区间。endMs == Long.MAX_VALUE 表示「到片尾」（消费时夹到 durationMs）。
 */
@Serializable
data class SkipSegment(
    val type: SkipType,
    val startMs: Long,
    val endMs: Long,
)
```

- [ ] **Step 2: 追加 TmdbMediaRef 到 Tmdb.kt**

在 `model/Tmdb.kt` 末尾追加：
```kotlin
/**
 * 解析后的 TMDB 媒体标识，供片头片尾取数用。
 * @param mediaType "movie" 或 "tv"
 * @param season 季号（从豆瓣原名后缀解析，无后缀=1）
 * @param imdbId 仅 TV 解析；供 introdb.app（仅吃 imdb）补充用
 */
@Serializable
data class TmdbMediaRef(
    val mediaType: String,
    val tmdbId: Long,
    val season: Int,
    val imdbId: String?,
)
```
> `Tmdb.kt` 顶部应已 `import kotlinx.serialization.Serializable`（既有 `TmdbArt` 也用）。若没有则补上。

- [ ] **Step 3: 编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/model/SkipSegment.kt app/src/main/java/org/moontechlab/selenetv/model/Tmdb.kt
git commit -m "feat(model): add SkipSegment/SkipType and TmdbMediaRef"
```

---

## Task 2: TmdbService.parseSeasonNumber（TDD）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/TmdbService.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/service/TmdbServiceTest.kt`

- [ ] **Step 1: 写失败测试**

在 `TmdbServiceTest.kt` 类内追加：
```kotlin
@Test
fun parseSeasonNumber_extractsSeason() {
    assertEquals(2, TmdbService.parseSeasonNumber("ぐらんぶる Season 2"))
    assertEquals(2, TmdbService.parseSeasonNumber("某剧 第二季"))
    assertEquals(10, TmdbService.parseSeasonNumber("某剧第十期"))
    assertEquals(23, TmdbService.parseSeasonNumber("某剧 第二十三季"))
    assertEquals(2, TmdbService.parseSeasonNumber("Attack on Titan 2nd Season"))
    assertEquals(3, TmdbService.parseSeasonNumber("Foo Season III"))
}

@Test
fun parseSeasonNumber_defaultsToOne() {
    assertEquals(1, TmdbService.parseSeasonNumber("玲珑"))
    assertEquals(1, TmdbService.parseSeasonNumber("Blade Runner 2049"))
    assertEquals(1, TmdbService.parseSeasonNumber("ぐらんぶる"))
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.service.TmdbServiceTest"`
Expected: 编译失败 / `parseSeasonNumber` 未定义

- [ ] **Step 3: 实现 parseSeasonNumber 及辅助**

在 `TmdbService` object 内（紧邻 `stripSeasonSuffix` 之后）追加：
```kotlin
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
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.service.TmdbServiceTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/TmdbService.kt app/src/test/java/org/moontechlab/selenetv/service/TmdbServiceTest.kt
git commit -m "feat(tmdb): parse season number from title suffix"
```

---

## Task 3: TmdbService.parseImdbId + resolveMediaRef

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/TmdbService.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/service/TmdbServiceTest.kt`

- [ ] **Step 1: 写失败测试（parseImdbId）**

在 `TmdbServiceTest.kt` 追加：
```kotlin
@Test
fun parseImdbId_extractsId() {
    assertEquals("tt0944947", TmdbService.parseImdbId("""{"imdb_id":"tt0944947","tvdb_id":121361}"""))
}

@Test
fun parseImdbId_nullWhenMissingOrBlank() {
    assertEquals(null, TmdbService.parseImdbId("""{"imdb_id":null}"""))
    assertEquals(null, TmdbService.parseImdbId("""{"imdb_id":""}"""))
    assertEquals(null, TmdbService.parseImdbId("""{}"""))
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.service.TmdbServiceTest"`
Expected: 编译失败 / `parseImdbId` 未定义

- [ ] **Step 3: 实现 parseImdbId / fetchImdbId / resolveMediaRef**

在 `TmdbService` object 内追加常量（与既有 `CACHE_PREFIX` 同处）：
```kotlin
private const val REF_PREFIX = "tmdb_ref"
```
追加方法（注意：顶部需 `import kotlinx.serialization.json.contentOrNull`，已存在；`import org.moontechlab.selenetv.model.TmdbMediaRef`；`import org.moontechlab.selenetv.service.DoubanCacheService` 同包无需 import）：
```kotlin
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
```
> `search()` 已是 `TmdbService` 私有方法，同 object 内可直接调；`httpGet` / `API_BASE` / `TTL_HIT` / `TTL_MISS` / `json` 均已存在。

- [ ] **Step 4: 运行测试确认通过 + 编译**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.service.TmdbServiceTest" && ./gradlew assembleDebug`
Expected: 测试 PASS，BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/TmdbService.kt app/src/test/java/org/moontechlab/selenetv/service/TmdbServiceTest.kt
git commit -m "feat(tmdb): resolveMediaRef (tmdbId+season+imdb) with cache"
```

---

## Task 4: IntroSkipService 解析（parseTidb / parseIntroDb）（TDD）

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/service/IntroSkipService.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/service/IntroSkipServiceTest.kt`

- [ ] **Step 1: 写失败测试（用 live 实测 JSON 作 fixture）**

`service/IntroSkipServiceTest.kt`：
```kotlin
package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.moontechlab.selenetv.model.SkipType

class IntroSkipServiceTest {

    // 实测 fixture（GoT s1e1）
    private val tidbGot = """{"tmdb_id":1399,"type":"tv","season":1,"episode":1,"intro":[{"start_ms":437018,"end_ms":536972}],"credits":[{"start_ms":3615033,"end_ms":null}]}"""

    @Test
    fun parseTidb_mapsTypesAndNullEnd() {
        val segs = IntroSkipService.parseTidb(tidbGot)
        assertEquals(2, segs.size)
        val intro = segs.first { it.type == SkipType.INTRO }
        assertEquals(437018L, intro.startMs)
        assertEquals(536972L, intro.endMs)
        val outro = segs.first { it.type == SkipType.OUTRO }   // credits→OUTRO
        assertEquals(3615033L, outro.startMs)
        assertEquals(Long.MAX_VALUE, outro.endMs)              // end_ms:null → 哨兵
    }

    @Test
    fun parseTidb_emptyOnError() {
        assertEquals(emptyList<Any>(), IntroSkipService.parseTidb("""{"error":"media not found"}"""))
        assertEquals(emptyList<Any>(), IntroSkipService.parseTidb("garbage"))
    }

    // 实测 fixture（IntroDB GoT s1e1）
    private val introdbGot = """{"imdb_id":"tt0944947","season":1,"episode":1,"intro":{"start_ms":437000,"end_ms":531000,"confidence":1,"submission_count":1},"recap":null,"outro":{"start_ms":3631500,"end_ms":3699500,"confidence":1,"submission_count":2}}"""

    @Test
    fun parseIntroDb_parsesScoredCandidates() {
        val cands = IntroSkipService.parseIntroDb(introdbGot)
        assertEquals(2, cands.size)   // recap=null 跳过
        val intro = cands.first { it.type == SkipType.INTRO }
        assertEquals(437000L, intro.startMs)
        assertEquals(1, intro.submissionCount)
        val outro = cands.first { it.type == SkipType.OUTRO }
        assertEquals(2, outro.submissionCount)
    }

    @Test
    fun parseIntroDb_emptyOnAllNull() {
        assertEquals(emptyList<Any>(), IntroSkipService.parseIntroDb("""{"imdb_id":"tt1","season":1,"episode":1,"intro":null,"recap":null,"outro":null}"""))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.service.IntroSkipServiceTest"`
Expected: 编译失败 / `IntroSkipService` 未定义

- [ ] **Step 3: 创建 IntroSkipService 与解析方法**

`service/IntroSkipService.kt`：
```kotlin
package org.moontechlab.selenetv.service

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

/**
 * 片头片尾时间戳：TIDB（主）+ IntroDB（补）双源。
 *
 * best-effort：任何失败/无数据返回空列表，绝不抛异常——播放不可被它中断。
 * 网络用裸 HttpURLConnection（对齐 [TmdbService]）。两站正规 HTTPS，无需 trust-all。
 */
object IntroSkipService {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.service.IntroSkipServiceTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/IntroSkipService.kt app/src/test/java/org/moontechlab/selenetv/service/IntroSkipServiceTest.kt
git commit -m "feat(introskip): parse TIDB and IntroDB responses"
```

---

## Task 5: mergeSegments + pickActiveSegment（TDD）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/IntroSkipService.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/service/IntroSkipServiceTest.kt`

- [ ] **Step 1: 写失败测试**

在 `IntroSkipServiceTest.kt` 追加：
```kotlin
@Test
fun mergeSegments_fillsMissingTypesGatedByConfidence() {
    val tidb = listOf(org.moontechlab.selenetv.model.SkipSegment(SkipType.INTRO, 0, 90000))
    val cands = listOf(
        IntroSkipService.IntroDbCandidate(SkipType.INTRO, 1, 2, 1.0, 5),   // 重复类型→忽略
        IntroSkipService.IntroDbCandidate(SkipType.OUTRO, 100, 200, 0.6, 1), // conf≥0.5→采纳
        IntroSkipService.IntroDbCandidate(SkipType.RECAP, 5, 6, 0.2, 1),     // 低分→丢弃
    )
    val merged = IntroSkipService.mergeSegments(tidb, cands)
    assertEquals(2, merged.size)                          // INTRO(原) + OUTRO(补)
    assertEquals(SkipType.OUTRO, merged[1].type)
    assertEquals(100L, merged[1].startMs)
}

@Test
fun mergeSegments_acceptsBySubmissionCount() {
    val cands = listOf(IntroSkipService.IntroDbCandidate(SkipType.OUTRO, 1, 2, 0.1, 2)) // subs≥2→采纳
    assertEquals(1, IntroSkipService.mergeSegments(emptyList(), cands).size)
}

@Test
fun pickActiveSegment_matchesPositionAndClampsSentinel() {
    val segs = listOf(
        org.moontechlab.selenetv.model.SkipSegment(SkipType.INTRO, 1000, 5000),
        org.moontechlab.selenetv.model.SkipSegment(SkipType.OUTRO, 60000, Long.MAX_VALUE),
    )
    assertEquals(SkipType.INTRO, IntroSkipService.pickActiveSegment(segs, 2000, 90000)?.type)
    assertEquals(null, IntroSkipService.pickActiveSegment(segs, 5000, 90000))        // end 排他
    assertEquals(SkipType.OUTRO, IntroSkipService.pickActiveSegment(segs, 80000, 90000)?.type) // 哨兵夹到 dur
    assertEquals(null, IntroSkipService.pickActiveSegment(segs, 95000, 90000))       // 超过 dur
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.service.IntroSkipServiceTest"`
Expected: 编译失败 / `mergeSegments` `pickActiveSegment` 未定义

- [ ] **Step 3: 实现 mergeSegments / pickActiveSegment**

在 `IntroSkipService` object 内追加：
```kotlin
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
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.service.IntroSkipServiceTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/IntroSkipService.kt app/src/test/java/org/moontechlab/selenetv/service/IntroSkipServiceTest.kt
git commit -m "feat(introskip): merge dual-source segments and pick active segment"
```

---

## Task 6: IntroSkipService.fetchSegments（网络 + 缓存）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/IntroSkipService.kt`

- [ ] **Step 1: 实现 fetchSegments + httpGet**

在 `IntroSkipService` object 内追加 import 与常量、方法。顶部追加 import：
```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.moontechlab.selenetv.model.TmdbMediaRef
import java.net.HttpURLConnection
import java.net.URL
```
object 内追加：
```kotlin
private const val TIDB_BASE = "https://api.theintrodb.org/v3/media"
private const val INTRODB_BASE = "https://api.introdb.app/segments"
private const val TIMEOUT_MS = 12000
private const val TTL_HIT = 30L * 24 * 60 * 60 * 1000   // 有段 30 天
private const val TTL_MISS = 3L * 24 * 60 * 60 * 1000    // 空 3 天（数据后续可能被补）

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
```

- [ ] **Step 2: 编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 手动冒烟（可选，确认线上接口仍可用）**

Run:
```bash
curl -s "https://api.theintrodb.org/v3/media?tmdb_id=1399&season=1&episode=1"
```
Expected: 返回含 `intro`/`credits` 的 JSON（非 `media not found`）

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/IntroSkipService.kt
git commit -m "feat(introskip): fetchSegments with dual-source merge and disk cache"
```

---

## Task 7: PlayerLaunch.tmdbRef + DetailScreen 注入

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerLaunch.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt`

- [ ] **Step 1: PlayerLaunch 加字段**

`PlayerLaunch.kt`，在 data class 末尾加字段（保持默认值，不破坏其它构造点）：
```kotlin
    val durationMs: Long = 45 * 60 * 1000L,
    val tmdbRef: org.moontechlab.selenetv.model.TmdbMediaRef? = null,
)
```

- [ ] **Step 2: DetailUiState 加 tmdbRef + 解析注入**

在 `DetailScreen.kt` 找到 `DetailUiState`（含 `tmdbArt` 字段的 data class），追加：
```kotlin
    val tmdbRef: org.moontechlab.selenetv.model.TmdbMediaRef? = null,
```
在 TMDB 解析 effect（`:294` 起，含 `resolveArt` 那段）内，把 art 解析旁路出 ref：
```kotlin
    LaunchedEffect(tmdbTrigger) {
        val q = tmdbTrigger ?: return@LaunchedEffect
        val key = AppDataService.getTmdbApiKey()
        val art = if (key.isNotBlank()) {
            kotlinx.coroutines.withTimeoutOrNull(4000) {
                TmdbService.resolveArt(q.originalTitle, q.year, key, doubanCacheService)
            }
        } else null
        val ref = if (key.isNotBlank()) {
            kotlinx.coroutines.withTimeoutOrNull(4000) {
                TmdbService.resolveMediaRef(q.originalTitle, q.year, key, doubanCacheService)
            }
        } else null
        state = state.copy(tmdbArt = art, tmdbRef = ref, detailsLoading = false)
    }
```

- [ ] **Step 3: launchPlayer 传入 tmdbRef**

在 `launchPlayer`（`:498` 的 `PlayerLaunch(...)`）末尾加：
```kotlin
            durationMs = if (totalMs > 0L) totalMs else 45 * 60 * 1000L,
            tmdbRef = state.tmdbRef,
        )
```

- [ ] **Step 4: 编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerLaunch.kt app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt
git commit -m "feat(detail): resolve and pass TmdbMediaRef into PlayerLaunch"
```

---

## Task 8: SpeedPill 加可选 upTarget

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlaybackSpeed.kt`

- [ ] **Step 1: 加参数并接到 focusProperties.up**

`PlaybackSpeed.kt` `fun SpeedPill(` 签名加参数（放在 `scrubberFocus` 之后）：
```kotlin
    scrubberFocus: FocusRequester,
    upTarget: FocusRequester? = null,
```
把现有 `.focusProperties { down = scrubberFocus; up = FocusRequester.Cancel }`（约 `:96-98`）改为：
```kotlin
            .focusProperties {
                down = scrubberFocus
                up = upTarget ?: FocusRequester.Cancel
            }
```

- [ ] **Step 2: 编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL（现有调用点未传 `upTarget`，用默认 null，行为不变）

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlaybackSpeed.kt
git commit -m "feat(player): SpeedPill optional upTarget for focus stack"
```

---

## Task 9: IntroSkipButton 组件

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/player/IntroSkipOverlay.kt`

- [ ] **Step 1: 创建组件**

`screen/player/IntroSkipOverlay.kt`：
```kotlin
package org.moontechlab.selenetv.screen.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 单色矩形跳过按钮。未聚焦黑底白字+1dp白边，聚焦反色（白底黑字）。
 * 控制条隐藏时方向键交由 [onWakeControls]（唤控制条+还焦）；控制条可见时方向走 focusProperties。
 * OK 触发 [onSkip]。
 */
@Composable
fun IntroSkipButton(
    label: String,
    focusRequester: FocusRequester,
    speedPillFocus: FocusRequester,
    controlsVisible: Boolean,
    onSkip: () -> Unit,
    onWakeControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, label = "skip_scale")
    val bg = if (focused) Color.White else Color.Black
    val fg = if (focused) Color.Black else Color.White
    Text(
        text = label,
        color = fg,
        fontSize = 15.sp,
        fontWeight = FontWeight.W600,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .focusProperties {
                down = speedPillFocus
                up = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onSkip(); true }
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight ->
                        if (!controlsVisible) { onWakeControls(); true } else false
                    else -> false
                }
            }
            .focusable()
            .border(1.dp, if (focused) Color.Transparent else Color.White, RoundedCornerShape(6.dp))
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}
```

- [ ] **Step 2: 编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/IntroSkipOverlay.kt
git commit -m "feat(player): IntroSkipButton monochrome focus-invert component"
```

---

## Task 10: PlayerScreen 集成（取数 + 焦点借还 + 渲染 + 按键）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt`

- [ ] **Step 1: 加 state、skipFocus、segments、activeSegment**

在 `scrubberFocus`/`speedPillFocus` 声明附近（`:240-242`）加：
```kotlin
    val skipFocus = remember { FocusRequester() }
```
在 `hasNext`（`:141`）之后加段状态：
```kotlin
    var segments by remember { mutableStateOf<List<org.moontechlab.selenetv.model.SkipSegment>>(emptyList()) }
    val activeSegment = remember(segments, positionMs, durationMs) {
        org.moontechlab.selenetv.service.IntroSkipService.pickActiveSegment(segments, positionMs, durationMs)
    }
```

- [ ] **Step 2: 加取数 effect**

紧邻 500ms 同步 effect（`:250-255`）之后加：
```kotlin
    // 每集拉取跳过段（best-effort，失败空列表）
    LaunchedEffect(currentEpisode, currentSourceKey, launch.tmdbRef) {
        val ref = launch.tmdbRef
        segments = if (ref == null) emptyList()
        else runCatching {
            org.moontechlab.selenetv.service.IntroSkipService.fetchSegments(
                ref,
                currentEpisode + 1,
                org.moontechlab.selenetv.service.DoubanCacheService.getInstance(context),
            )
        }.getOrDefault(emptyList())
    }
```

- [ ] **Step 3: 焦点借还**

把自动隐藏 effect（`:307-313`）里隐藏后的还焦行改为按段路由——将该 effect 内的
```kotlin
            try { scrubberFocus.requestFocus() } catch (_: Exception) {}
```
改为
```kotlin
            try { (if (activeSegment != null) skipFocus else scrubberFocus).requestFocus() } catch (_: Exception) {}
```
并在其后新增一个 effect（处理「控制条已隐藏时段开始/结束」的借还）：
```kotlin
    // 控制条隐藏期间：段内→焦点借给跳过键，段外→还给进度条（带重试，越过清焦延迟）
    LaunchedEffect(activeSegment != null, controlsVisible) {
        if (!controlsVisible) {
            val target = if (activeSegment != null) skipFocus else scrubberFocus
            repeat(5) {
                try { target.requestFocus() } catch (_: Exception) {}
                kotlinx.coroutines.delay(40)
            }
        }
    }
```

- [ ] **Step 4: SpeedPill 传 upTarget**

在 `SpeedPill(...)` 调用（`:517-522`）加参数：
```kotlin
                                scrubberFocus = scrubberFocus,
                                upTarget = if (activeSegment != null) skipFocus else null,
```

- [ ] **Step 5: 渲染按钮（控制条 Column 内、alpha Box 之上）**

在底部控制 `Column`（`:481`，`align(BottomStart)`）内、第一个子 `Box(... graphicsLayer alpha ...)` 之前插入：
```kotlin
            if (activeSegment != null) {
                val seg = activeSegment
                val label = when (seg.type) {
                    org.moontechlab.selenetv.model.SkipType.INTRO -> "跳过片头"
                    org.moontechlab.selenetv.model.SkipType.RECAP -> "跳过回顾"
                    org.moontechlab.selenetv.model.SkipType.OUTRO -> if (hasNext) "下一集" else "跳过片尾"
                }
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 56.dp, bottom = 12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                ) {
                    IntroSkipButton(
                        label = label,
                        focusRequester = skipFocus,
                        speedPillFocus = speedPillFocus,
                        controlsVisible = controlsVisible,
                        onSkip = {
                            if (seg.type == org.moontechlab.selenetv.model.SkipType.OUTRO && hasNext) {
                                goNext()
                            } else {
                                controller.seekTo(minOf(seg.endMs, durationMs))
                                wake()
                            }
                            try { scrubberFocus.requestFocus() } catch (_: Exception) {}
                        },
                        onWakeControls = {
                            wake()
                            try { scrubberFocus.requestFocus() } catch (_: Exception) {}
                        },
                    )
                }
            }
```
> 该 Row 不在 `graphicsLayer { alpha = ctrlAlpha }` 的 Box 内，故控制条隐藏时仍可见。它是 `Column` 第一个子项 → 渲染在控制条上方（倍速键之上），`Column` 为 BottomStart 锚定，出现时向上扩展、不挤动下方控制条。

- [ ] **Step 6: 编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt
git commit -m "feat(player): wire IntroSkip overlay (fetch, focus, render, keys)"
```

---

## Task 11: 真机 E2E 验收（手动）

**Files:** 无（行为验收）

> 套路见记忆 `tv-emulator-e2e`：AVD `Selene_TV`、`adb=~/Library/Android/sdk/platform-tools/adb`、`am start -n org.moontechlab.selenetv/.MainActivity`、**OK/选择在播放页用 `input keyevent 66`（ENTER），`23`(DPAD_CENTER) 注入收不到**。设置页需已填 TMDB API Key。

- [ ] **Step 1: 安装运行**

Run:
```bash
./gradlew assembleDebug
~/Library/Android/sdk/platform-tools/adb install -r -d app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
~/Library/Android/sdk/platform-tools/adb shell am start -n org.moontechlab.selenetv/.MainActivity
```

- [ ] **Step 2: 验收清单（逐项截图确认）**

- 进入一个有 TMDB 数据的单季番剧/欧美剧，跳到片头区间：**反色按钮出现并预聚焦**，按 ENTER → seek 到片头尾继续播。
- 片尾区间：有下一集显示「下一集」→ ENTER 切集；末集显示「跳过片尾」→ ENTER seek 到末。
- 控制条可见时：scrubber 按「上」→ 倍速→ 再「上」到跳过键（反色）→ ENTER 跳过。
- 段内（控制条隐藏）按方向键：控制条唤起、焦点回进度条、按钮转黑底。
- 段过未按：按钮自隐，D-pad 仍正常（无失焦）。
- 无 TMDB key / 华语剧：无按钮、播放与改动前一致。
- 跳过键持焦按 BACK：仍是二次确认退出。

- [ ] **Step 3: 若出现 d-pad 失焦（premise collapse #2）**

切回退方案：移除独立 `skipFocus` 借焦，改在 `PlayerScrubber.onKeyEvent` 内 `activeSegment != null && OK → onSkip` 拦截，按钮反色态由 `activeSegment` 驱动。记录现象后再改。

- [ ] **Step 4: 用 /check 复核后合并**

---

## Self-Review 记录

- **Spec 覆盖**：数据源(§2)→T4/T6；TmdbService 复用(§3,§4.1)→T2/T3；IntroSkipService(§4.2)→T4/T5/T6；PlayerLaunch+DetailScreen(§4.3)→T7；交互三态/焦点链/按键(§5)→T8/T9/T10；组件(§5.6)→T9；依赖 TMDB key(§8)→T7 处理空 key；测试(§9)→各 TDD 任务 + T11；回滚(§10)→纯增量。无遗漏。
- **占位扫描**：无 TBD/TODO；网络/Compose 任务给出完整代码。
- **类型一致**：`SkipSegment`/`SkipType`/`TmdbMediaRef`/`IntroDbCandidate`/`parseTidb`/`parseIntroDb`/`mergeSegments`/`pickActiveSegment`/`fetchSegments`/`resolveMediaRef`/`parseSeasonNumber`/`parseImdbId`/`IntroSkipButton`(参数 `label/focusRequester/speedPillFocus/controlsVisible/onSkip/onWakeControls`) 全任务一致。
