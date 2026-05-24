# 全部卡片可进入详情页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让继续播放 / 收藏夹 / 搜索聚合 / bangumi / 豆瓣全部卡片都能进入详情页，并按入口类型决定播放源列表与 hero 信息展示优先级。

**Architecture:** 引入密封类 `DetailEntry` 作为详情覆盖层的入口载体（替换 `DetailRoute`）。`HomeScreen.openDetail` 按 `VideoInfo` 字段分流为 Douban/Bangumi/History 入口；搜索页新增 `onOpenDetail` 回调走 SearchAggregate 入口。详情页用 `when(entry)` 决定源获取方式与信息解析。把纯逻辑（源过滤、聚合组定位、tier2 豆瓣 ID、HeroInfo 适配、入口分流）抽成可单测的函数。

**Tech Stack:** Kotlin、Jetpack Compose for TV、JUnit4 单元测试（`app/src/test`）。设计文档见 `docs/superpowers/specs/2026-05-24-all-cards-open-detail-design.md`。

---

## 文件结构

新增：
- `app/src/main/java/org/moontechlab/selenetv/screen/detail/HeroInfo.kt` — hero 视图模型 + 3 个适配器
- `app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailSourceResolver.kt` — `historyGroupSources`、`tier2DoubanId` 纯函数
- `app/src/main/java/org/moontechlab/selenetv/screen/DetailEntry.kt` — 密封类 + `detailEntryFor(VideoInfo)`
- 对应测试：`HeroInfoTest.kt`、`DetailSourceResolverTest.kt`、`DetailEntryTest.kt`、`DetailUiStateHeroInfoTest.kt`

修改：
- `screen/detail/SourceFilter.kt` — 新增 `matchesByTitleYear`
- `screen/detail/DetailUiState.kt` — `route→entry`、`bangumiDetails`、`heroInfo`
- `screen/detail/DetailHero.kt` — 改用 `HeroInfo`
- `screen/DetailScreen.kt` — 按 `entry` 驱动的源获取/信息解析/加载态
- `screen/HomeScreen.kt` — `detailEntry` 状态、`openDetail` 分流、`openDetailFromSearch`、SearchTab 接线
- `screen/tab/SearchTab.kt` — `onOpenDetail` 参数 + `onResultClick` 接线
- `screen/Routes.kt` — 删除 `DetailRoute`

测试运行命令统一为：`./gradlew :app:testDebugUnitTest`（可加 `--tests "<FQN>"` 只跑单个类）。

---

## Task 1: SourceFilter.matchesByTitleYear

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/detail/SourceFilter.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/screen/detail/SourceFilterTest.kt`

- [ ] **Step 1: 追加失败测试**

在 `SourceFilterTest.kt` 末尾、最后一个 `}` 之前追加：

```kotlin
    @Test
    fun `matchesByTitleYear matches same title and year`() {
        val r = result(title = "长安的荔枝", year = "2025", doubanId = 999)
        assertTrue(SourceFilter.matchesByTitleYear(r, title = "长安的荔枝", year = "2025"))
    }

    @Test
    fun `matchesByTitleYear ignores douban id`() {
        val r = result(title = "长安的荔枝", year = "2025", doubanId = 12345)
        assertTrue(SourceFilter.matchesByTitleYear(r, title = "长安的荔枝", year = "2025"))
    }

    @Test
    fun `matchesByTitleYear rejects different title`() {
        val r = result(title = "其他作品", year = "2025")
        assertFalse(SourceFilter.matchesByTitleYear(r, title = "长安的荔枝", year = "2025"))
    }

    @Test
    fun `matchesByTitleYear accepts when route year empty`() {
        val r = result(title = "长安的荔枝", year = "2024")
        assertTrue(SourceFilter.matchesByTitleYear(r, title = "长安的荔枝", year = ""))
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.moontechlab.selenetv.screen.detail.SourceFilterTest"`
Expected: 编译失败 / `matchesByTitleYear` 未定义。

- [ ] **Step 3: 实现 matchesByTitleYear**

在 `SourceFilter.kt` 的 `object SourceFilter {` 内、`matchesContent` 之后追加：

```kotlin
    /**
     * 仅按标题/年份匹配（忽略 doubanId）。用于 bangumi / 继续播放 / 收藏夹入口。
     */
    fun matchesByTitleYear(
        result: SearchResult,
        title: String,
        year: String,
    ): Boolean {
        val titleOk = result.title == title
        val yearOk = result.year == year || year.isEmpty() || result.year.isEmpty()
        return titleOk && yearOk
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "org.moontechlab.selenetv.screen.detail.SourceFilterTest"`
Expected: PASS（全部测试）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/detail/SourceFilter.kt app/src/test/java/org/moontechlab/selenetv/screen/detail/SourceFilterTest.kt
git commit -m "新增 SourceFilter.matchesByTitleYear（按标题年份匹配，忽略 doubanId）"
```

---

## Task 2: HeroInfo 视图模型与适配器

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/detail/HeroInfo.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/screen/detail/HeroInfoTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/org/moontechlab/selenetv/screen/detail/HeroInfoTest.kt`：

```kotlin
package org.moontechlab.selenetv.screen.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.moontechlab.selenetv.model.BangumiImages
import org.moontechlab.selenetv.model.BangumiRating
import org.moontechlab.selenetv.model.DoubanMovieDetails
import org.moontechlab.selenetv.model.SearchResult

class HeroInfoTest {
    @Test
    fun `searchResult maps lean fields`() {
        val r = SearchResult(
            id = "1", title = "长安的荔枝", poster = "p.jpg",
            episodes = listOf("ep1", "ep2"), episodesTitles = listOf("第1集", "第2集"),
            source = "src", sourceName = "源A", year = "2025",
            desc = "简介文本", typeName = "电视剧", doubanId = null,
        )
        val h = r.toHeroInfo()
        assertEquals("长安的荔枝", h.title)
        assertEquals("p.jpg", h.poster)
        assertNull(h.rate)
        assertEquals(listOf("电视剧"), h.genres)
        assertEquals("2025", h.subLine)
        assertEquals("简介文本", h.summary)
    }

    @Test
    fun `doubanDetails maps rate and sub line`() {
        val d = DoubanMovieDetails(
            id = "123", title = "电影名", poster = "dp.jpg", rate = "8.5", year = "2024",
            summary = "豆瓣简介", genres = listOf("剧情", "悬疑"),
            directors = emptyList(), screenwriters = emptyList(), actors = emptyList(),
            duration = "120分钟", countries = listOf("中国"), languages = emptyList(),
            releaseDate = "2024-01-01", recommends = emptyList(),
        )
        val h = d.toHeroInfo()
        assertEquals("电影名", h.title)
        assertEquals("8.5", h.rate)
        assertEquals(listOf("剧情", "悬疑"), h.genres)
        assertEquals("2024-01-01(中国) · 中国 · 120分钟", h.subLine)
        assertEquals("豆瓣简介", h.summary)
    }

    @Test
    fun `bangumiDetails maps name and score`() {
        val images = BangumiImages(large = "l.jpg", common = "", medium = "", small = "", grid = "")
        val b = org.moontechlab.selenetv.model.BangumiDetails(
            id = 9, type = 2, name = "EN Name", nameCn = "中文名", summary = "番剧简介",
            nsfw = false, locked = false, date = "2025-04-01", platform = "TV", images = images,
            infobox = emptyList(), volumes = 0, eps = 12, totalEpisodes = 12,
            rating = BangumiRating(total = 100, count = emptyMap(), score = 7.8),
            collection = org.moontechlab.selenetv.model.BangumiCollection(),
            tags = listOf("奇幻"), metaTags = listOf("TV", "原创"), series = false,
        )
        val h = b.toHeroInfo()
        assertEquals("中文名", h.title)
        assertEquals("l.jpg", h.poster)
        assertEquals("7.8", h.rate)
        assertEquals(listOf("TV", "原创"), h.genres)
        assertEquals("2025-04-01", h.subLine)
        assertEquals("番剧简介", h.summary)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.moontechlab.selenetv.screen.detail.HeroInfoTest"`
Expected: 编译失败 / `toHeroInfo` 未定义。

- [ ] **Step 3: 创建 HeroInfo.kt**

创建 `app/src/main/java/org/moontechlab/selenetv/screen/detail/HeroInfo.kt`：

```kotlin
package org.moontechlab.selenetv.screen.detail

import org.moontechlab.selenetv.model.BangumiDetails
import org.moontechlab.selenetv.model.DoubanMovieDetails
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.model.getBestImageUrl

/**
 * 详情 hero 统一视图模型。可由豆瓣详情 / bangumi 详情 / 搜索源派生。
 */
data class HeroInfo(
    val title: String,
    val poster: String,
    val rate: String? = null,       // 评分文本，null 不显示
    val genres: List<String> = emptyList(),
    val subLine: String? = null,    // 副信息行（· 连接）
    val summary: String? = null,
)

fun DoubanMovieDetails.toHeroInfo(): HeroInfo {
    val subParts = buildList {
        releaseDate?.takeIf { it.isNotEmpty() }?.let { rd ->
            val country = countries.firstOrNull()?.takeIf { it.isNotEmpty() }
            if (country != null) add("$rd($country)") else add(rd)
        }
        countries.takeIf { it.isNotEmpty() }?.let { add(it.joinToString("/")) }
        duration?.takeIf { it.isNotEmpty() }?.let { add(it) }
    }
    return HeroInfo(
        title = title,
        poster = poster,
        rate = rate?.takeIf { it.isNotEmpty() && it != "0.0" },
        genres = genres,
        subLine = subParts.takeIf { it.isNotEmpty() }?.joinToString(" · "),
        summary = summary?.takeIf { it.isNotEmpty() },
    )
}

fun BangumiDetails.toHeroInfo(): HeroInfo = HeroInfo(
    title = nameCn?.takeIf { it.isNotEmpty() } ?: name,
    poster = images.getBestImageUrl(),
    rate = rating.score.takeIf { it > 0 }?.let { String.format("%.1f", it) },
    genres = metaTags.ifEmpty { tags }.take(4),
    subLine = date?.takeIf { it.isNotEmpty() },
    summary = summary.takeIf { it.isNotEmpty() },
)

fun SearchResult.toHeroInfo(): HeroInfo = HeroInfo(
    title = title,
    poster = poster,
    rate = null,
    genres = listOfNotNull(typeName?.takeIf { it.isNotEmpty() }),
    subLine = year.takeIf { it.isNotEmpty() },
    summary = desc?.takeIf { it.isNotEmpty() },
)
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "org.moontechlab.selenetv.screen.detail.HeroInfoTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/detail/HeroInfo.kt app/src/test/java/org/moontechlab/selenetv/screen/detail/HeroInfoTest.kt
git commit -m "新增 HeroInfo 视图模型与豆瓣/bangumi/源适配器"
```

---

## Task 3: DetailSourceResolver 纯逻辑

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailSourceResolver.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/screen/detail/DetailSourceResolverTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/org/moontechlab/selenetv/screen/detail/DetailSourceResolverTest.kt`：

```kotlin
package org.moontechlab.selenetv.screen.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.moontechlab.selenetv.model.SearchResult

class DetailSourceResolverTest {
    private fun result(
        id: String, source: String, title: String = "长安的荔枝",
        year: String = "2025", episodes: Int = 10, doubanId: Long? = null,
    ) = SearchResult(
        id = id, title = title, poster = "", episodes = List(episodes) { "ep$it" },
        episodesTitles = List(episodes) { "第${it + 1}集" }, source = source,
        sourceName = source, year = year, doubanId = doubanId,
    )

    @Test
    fun `historyGroupSources returns group containing source and id`() {
        val raw = listOf(
            result(id = "a", source = "s1"),
            result(id = "b", source = "s2"),
            result(id = "x", source = "s3", title = "别的剧", year = "2020", episodes = 1),
        )
        val group = DetailSourceResolver.historyGroupSources(raw, source = "s1", id = "a")
        assertTrue(group != null)
        assertEquals(2, group!!.size) // s1/a 与 s2/b 同 title_year_type 聚合
        assertTrue(group.any { it.source == "s1" && it.id == "a" })
    }

    @Test
    fun `historyGroupSources returns null when source not found`() {
        val raw = listOf(result(id = "a", source = "s1"))
        assertNull(DetailSourceResolver.historyGroupSources(raw, source = "sX", id = "zzz"))
    }

    @Test
    fun `tier2DoubanId picks id appearing at least twice`() {
        val sources = listOf(
            result(id = "a", source = "s1", doubanId = 111),
            result(id = "b", source = "s2", doubanId = 111),
            result(id = "c", source = "s3", doubanId = 222),
        )
        assertEquals("111", DetailSourceResolver.tier2DoubanId(sources))
    }

    @Test
    fun `tier2DoubanId returns null when no id repeats`() {
        val sources = listOf(
            result(id = "a", source = "s1", doubanId = 111),
            result(id = "b", source = "s2", doubanId = 222),
            result(id = "c", source = "s3", doubanId = null),
        )
        assertNull(DetailSourceResolver.tier2DoubanId(sources))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.moontechlab.selenetv.screen.detail.DetailSourceResolverTest"`
Expected: 编译失败 / `DetailSourceResolver` 未定义。

- [ ] **Step 3: 创建 DetailSourceResolver.kt**

创建 `app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailSourceResolver.kt`：

```kotlin
package org.moontechlab.selenetv.screen.detail

import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.model.aggregateSearchResults

/**
 * 详情页源列表的纯逻辑辅助。
 */
object DetailSourceResolver {
    /**
     * 用与搜索页相同的聚合口径，返回 originalResults 中含有 source+id 的那个聚合组的源列表。
     * 找不到返回 null。
     */
    fun historyGroupSources(
        raw: List<SearchResult>,
        source: String,
        id: String,
    ): List<SearchResult>? {
        val group = aggregateSearchResults(raw).firstOrNull { g ->
            g.originalResults.any { it.source == source && it.id == id }
        }
        return group?.originalResults?.toList()
    }

    /**
     * 已展示源中出现 >= 2 次、且出现次数最多的 doubanId（字符串）。无则 null。
     */
    fun tier2DoubanId(sources: List<SearchResult>): String? {
        return sources.mapNotNull { it.doubanId }
            .groupingBy { it }
            .eachCount()
            .filter { it.value >= 2 }
            .maxByOrNull { it.value }
            ?.key
            ?.toString()
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "org.moontechlab.selenetv.screen.detail.DetailSourceResolverTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailSourceResolver.kt app/src/test/java/org/moontechlab/selenetv/screen/detail/DetailSourceResolverTest.kt
git commit -m "新增 DetailSourceResolver：聚合组定位与 tier2 doubanId"
```

---

## Task 4: DetailEntry 入口模型与分流

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/DetailEntry.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/screen/DetailEntryTest.kt`

> 注意：此任务**先不删除** `Routes.kt` 的 `DetailRoute`（删除放在 Task 6，保持中间状态可编译）。

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/org/moontechlab/selenetv/screen/DetailEntryTest.kt`：

```kotlin
package org.moontechlab.selenetv.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.moontechlab.selenetv.model.VideoInfo

class DetailEntryTest {
    private fun video(
        doubanId: String? = null, bangumiId: Int? = null,
        source: String = "src", id: String = "1", searchTitle: String = "搜索名",
    ) = VideoInfo(
        id = id, source = source, title = "标题", sourceName = "源", year = "2025",
        cover = "c.jpg", index = 0, totalEpisodes = 1, playTime = 0, totalTime = 0,
        saveTime = 0, searchTitle = searchTitle, doubanId = doubanId, bangumiId = bangumiId,
    )

    @Test
    fun `douban id maps to Douban entry`() {
        val e = detailEntryFor(video(doubanId = "123"))
        assertTrue(e is DetailEntry.Douban)
        assertEquals("123", (e as DetailEntry.Douban).doubanId)
    }

    @Test
    fun `bangumi id maps to Bangumi entry`() {
        val e = detailEntryFor(video(bangumiId = 9))
        assertTrue(e is DetailEntry.Bangumi)
        assertEquals(9, (e as DetailEntry.Bangumi).bangumiId)
    }

    @Test
    fun `douban id takes priority over bangumi id`() {
        val e = detailEntryFor(video(doubanId = "123", bangumiId = 9))
        assertTrue(e is DetailEntry.Douban)
    }

    @Test
    fun `no id maps to History entry`() {
        val e = detailEntryFor(video(source = "s2", id = "42", searchTitle = "搜索名"))
        assertTrue(e is DetailEntry.History)
        e as DetailEntry.History
        assertEquals("s2", e.source)
        assertEquals("42", e.id)
        assertEquals("搜索名", e.searchTitle)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.moontechlab.selenetv.screen.DetailEntryTest"`
Expected: 编译失败 / `DetailEntry`、`detailEntryFor` 未定义。

- [ ] **Step 3: 创建 DetailEntry.kt**

创建 `app/src/main/java/org/moontechlab/selenetv/screen/DetailEntry.kt`：

```kotlin
package org.moontechlab.selenetv.screen

import org.moontechlab.selenetv.model.AggregatedSearchResult
import org.moontechlab.selenetv.model.VideoInfo

/**
 * 详情页入口。详情是内存覆盖层（非导航参数），可直接携带对象，无需序列化。
 */
sealed interface DetailEntry {
    val title: String
    val poster: String
    val year: String

    data class Douban(
        val doubanId: String,
        override val title: String,
        override val poster: String,
        override val year: String,
    ) : DetailEntry

    data class Bangumi(
        val bangumiId: Int,
        override val title: String,
        override val poster: String,
        override val year: String,
    ) : DetailEntry

    /** 继续播放 + 收藏夹：携带具体源 source+id 与搜索用标题。 */
    data class History(
        val source: String,
        val id: String,
        val searchTitle: String,
        override val title: String,
        override val poster: String,
        override val year: String,
    ) : DetailEntry

    /** 搜索页聚合卡片：直接携带聚合结果（含 originalResults 快照）。 */
    data class SearchAggregate(
        val agg: AggregatedSearchResult,
        override val title: String,
        override val poster: String,
        override val year: String,
    ) : DetailEntry
}

/**
 * 按 VideoInfo 字段分流入口：doubanId > bangumiId > 否则按 source+id 当作历史/收藏。
 */
fun detailEntryFor(video: VideoInfo): DetailEntry {
    val doubanId = video.doubanId
    val bangumiId = video.bangumiId
    return when {
        doubanId != null -> DetailEntry.Douban(doubanId, video.title, video.cover, video.year)
        bangumiId != null -> DetailEntry.Bangumi(bangumiId, video.title, video.cover, video.year)
        else -> DetailEntry.History(
            source = video.source,
            id = video.id,
            searchTitle = video.searchTitle,
            title = video.title,
            poster = video.cover,
            year = video.year,
        )
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "org.moontechlab.selenetv.screen.DetailEntryTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/DetailEntry.kt app/src/test/java/org/moontechlab/selenetv/screen/DetailEntryTest.kt
git commit -m "新增 DetailEntry 入口模型与 detailEntryFor 分流"
```

---

## Task 5: DetailUiState 改为 entry 驱动 + heroInfo 优先级

> 这是集成改造的第一步。`DetailUiState`、`DetailHero`、`DetailScreen`、`HomeScreen`、`SearchTab`、`Routes` 互相耦合，必须在 **本任务到 Task 6 之间一次性改完才能编译通过**。因此 Task 5 与 Task 6 各自的「运行测试」放在 Task 6 末尾统一执行；Task 5 末尾**不单独提交**，与 Task 6 合并提交。

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailUiState.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailHero.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/screen/detail/DetailUiStateHeroInfoTest.kt`

- [ ] **Step 1: 重写 DetailUiState.kt**

整文件替换为：

```kotlin
package org.moontechlab.selenetv.screen.detail

import org.moontechlab.selenetv.model.BangumiDetails
import org.moontechlab.selenetv.model.DoubanMovieDetails
import org.moontechlab.selenetv.model.PlayRecord
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.screen.DetailEntry
import org.moontechlab.selenetv.service.SearchProgress

/**
 * 详情页 UI 状态。单 Composable 内 mutableStateOf 持有。
 */
data class DetailUiState(
    val entry: DetailEntry,
    val details: DoubanMovieDetails? = null,
    val bangumiDetails: BangumiDetails? = null,
    val detailsLoading: Boolean = true,
    val detailsError: String? = null,
    val sources: List<SearchResult> = emptyList(),
    val sourcesProgress: SearchProgress? = null,
    val sourcesComplete: Boolean = false,
    val selectedSourceKey: String? = null,
    val playRecords: Map<String, PlayRecord> = emptyMap(),
    val favoriteKeys: Set<String> = emptySet(),
    val episodePickerVisible: Boolean = false,
) {
    val selectedSource: SearchResult?
        get() = sources.firstOrNull { "${it.source}+${it.id}" == selectedSourceKey }

    val selectedPlayRecord: PlayRecord?
        get() = selectedSourceKey?.let { playRecords[it] }

    val isSelectedFavorite: Boolean
        get() = selectedSourceKey != null && selectedSourceKey in favoriteKeys

    /**
     * Hero 信息优先级（需求 5）：
     * - Douban 入口 → 豆瓣详情
     * - Bangumi 入口 → bangumi 详情
     * - History / SearchAggregate → 若已拉到 tier2 豆瓣详情(details)则用之，否则用选中源自身信息
     */
    val heroInfo: HeroInfo?
        get() = when (entry) {
            is DetailEntry.Douban -> details?.toHeroInfo()
            is DetailEntry.Bangumi -> bangumiDetails?.toHeroInfo()
            is DetailEntry.History,
            is DetailEntry.SearchAggregate -> details?.toHeroInfo() ?: selectedSource?.toHeroInfo()
        }
}

fun sourceKey(result: SearchResult): String = "${result.source}+${result.id}"
```

- [ ] **Step 2: 重写 DetailHero.kt 使用 HeroInfo**

整文件替换为：

```kotlin
package org.moontechlab.selenetv.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import org.moontechlab.selenetv.screen.DetailEntry
import org.moontechlab.selenetv.util.getImageUrlSync

@Composable
fun DetailHero(
    entry: DetailEntry,
    info: HeroInfo?,
    actionsRow: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // info 可能字段为空，标题/海报回退到 entry
    val title = info?.title?.takeIf { it.isNotBlank() } ?: entry.title
    val poster = info?.poster?.takeIf { it.isNotEmpty() } ?: entry.poster

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = 58.dp, top = 60.dp, end = 48.dp, bottom = 40.dp),
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 36.dp)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.W700,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                info?.genres?.forEach { genre ->
                    Text(
                        text = genre,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                    )
                }
                info?.rate?.let { rate ->
                    Text(
                        text = "★ $rate",
                        color = Color(0xFFE91E63),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.W700,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            info?.subLine?.takeIf { it.isNotEmpty() }?.let { sub ->
                Text(
                    text = sub,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            info?.summary?.takeIf { it.isNotEmpty() }?.let { summary ->
                Text(
                    text = summary,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    maxLines = 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.weight(1f).height(24.dp))

            actionsRow()
        }

        Box(
            modifier = Modifier
                .size(width = 200.dp, height = 300.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(8.dp),
                    ambientColor = Color.Black.copy(alpha = 0.5f),
                    spotColor = Color.Black.copy(alpha = 0.5f),
                )
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2A2A2A)),
        ) {
            AsyncImage(
                model = getImageUrlSync(poster),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
```

- [ ] **Step 3: 写 heroInfo 优先级测试**

创建 `app/src/test/java/org/moontechlab/selenetv/screen/detail/DetailUiStateHeroInfoTest.kt`：

```kotlin
package org.moontechlab.selenetv.screen.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.moontechlab.selenetv.model.AggregatedSearchResult
import org.moontechlab.selenetv.model.DoubanMovieDetails
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.screen.DetailEntry

class DetailUiStateHeroInfoTest {
    private fun source(id: String, doubanId: Long? = null) = SearchResult(
        id = id, title = "标题", poster = "p.jpg", episodes = listOf("ep1"),
        episodesTitles = listOf("第1集"), source = "s$id", sourceName = "源$id",
        year = "2025", desc = "源简介", typeName = "电视剧", doubanId = doubanId,
    )

    private val douban = DoubanMovieDetails(
        id = "111", title = "豆瓣标题", poster = "dp.jpg", rate = "8.0", year = "2025",
        summary = "豆瓣简介", genres = listOf("剧情"), directors = emptyList(),
        screenwriters = emptyList(), actors = emptyList(), countries = emptyList(),
        languages = emptyList(), recommends = emptyList(),
    )

    @Test
    fun `history shows selected source info when no tier2 douban`() {
        val src = source("a")
        val state = DetailUiState(
            entry = DetailEntry.History("sa", "a", "搜索名", "标题", "p.jpg", "2025"),
            sources = listOf(src),
            selectedSourceKey = "sa+a",
        )
        assertEquals("源简介", state.heroInfo?.summary)
    }

    @Test
    fun `history shows douban info when tier2 douban fetched`() {
        val src = source("a")
        val state = DetailUiState(
            entry = DetailEntry.History("sa", "a", "搜索名", "标题", "p.jpg", "2025"),
            sources = listOf(src),
            selectedSourceKey = "sa+a",
            details = douban, // tier2 已拉到
        )
        assertEquals("豆瓣标题", state.heroInfo?.title)
        assertEquals("8.0", state.heroInfo?.rate)
    }

    @Test
    fun `douban entry uses douban details only`() {
        val state = DetailUiState(
            entry = DetailEntry.Douban("111", "标题", "p.jpg", "2025"),
            details = douban,
        )
        assertEquals("豆瓣标题", state.heroInfo?.title)
    }

    @Test
    fun `search aggregate with no selection and no douban yields null hero`() {
        val agg = AggregatedSearchResult(
            key = "标题_2025_tv", title = "标题", year = "2025", type = "tv", cover = "p.jpg",
        )
        val state = DetailUiState(
            entry = DetailEntry.SearchAggregate(agg, "标题", "p.jpg", "2025"),
        )
        assertNull(state.heroInfo)
    }
}
```

> 本任务不运行测试、不提交。继续 Task 6 完成集成后统一编译/测试/提交。

---

## Task 6: DetailScreen / HomeScreen / SearchTab 集成 + 删除 DetailRoute

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/HomeScreen.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/tab/SearchTab.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/Routes.kt`

- [ ] **Step 1: 重写 DetailScreen.kt**

整文件替换为（按 entry 驱动；保留焦点/滚动/收藏/删除/播放逻辑）：

```kotlin
package org.moontechlab.selenetv.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.screen.detail.DetailSourceResolver
import org.moontechlab.selenetv.screen.detail.DetailUiState
import org.moontechlab.selenetv.screen.detail.SourceFilter
import org.moontechlab.selenetv.screen.detail.sourceKey
import org.moontechlab.selenetv.service.ApiResponse
import org.moontechlab.selenetv.service.ApiService
import org.moontechlab.selenetv.service.BangumiService
import org.moontechlab.selenetv.service.ContentFilterService
import org.moontechlab.selenetv.service.DoubanService
import org.moontechlab.selenetv.service.SearchEvent
import org.moontechlab.selenetv.service.SearchService

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(entry: DetailEntry, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doubanService = remember { DoubanService.getInstance(context) }
    val bangumiService = remember { BangumiService.getInstance(context) }

    var state by remember(entry) { mutableStateOf(DetailUiState(entry = entry)) }
    // 搜索流累积的原始结果（Douban/Bangumi/History 用）
    var rawResults by remember(entry) { mutableStateOf<List<SearchResult>>(emptyList()) }

    DisposableEffect(Unit) {
        onDispose { SearchService.stopSearch() }
    }

    BackHandler { onBack() }

    // 入口接受规则
    fun accept(result: SearchResult): Boolean = when (entry) {
        is DetailEntry.Douban -> SourceFilter.matchesContent(result, entry.doubanId, entry.title, entry.year)
        is DetailEntry.Bangumi -> SourceFilter.matchesByTitleYear(result, entry.title, entry.year)
        is DetailEntry.History -> SourceFilter.matchesByTitleYear(result, entry.title, entry.year)
        is DetailEntry.SearchAggregate -> false
    }

    // 1) 源获取：SearchAggregate 直接快照；其余启动搜索累积
    LaunchedEffect(entry) {
        when (entry) {
            is DetailEntry.SearchAggregate -> {
                rawResults = entry.agg.originalResults.filter { !ContentFilterService.shouldFilter(it.typeName) }
                state = state.copy(sourcesComplete = true)
            }
            else -> {
                SearchService.stopSearch()
                val query = when (entry) {
                    is DetailEntry.History -> entry.searchTitle
                    else -> entry.title
                }
                SearchService.eventFlow
                    .onSubscription {
                        scope.launch(Dispatchers.IO) { SearchService.startSearch(query) }
                    }
                    .collect { event ->
                        when (event) {
                            is SearchEvent.Results -> {
                                val accepted = event.results
                                    .asSequence()
                                    .filter { accept(it) }
                                    .filter { !ContentFilterService.shouldFilter(it.typeName) }
                                    .toList()
                                if (accepted.isNotEmpty()) {
                                    rawResults = SearchService.deduplicateResults(rawResults + accepted)
                                }
                            }
                            is SearchEvent.Progress -> state = state.copy(sourcesProgress = event.progress)
                            is SearchEvent.Complete -> state = state.copy(sourcesComplete = true)
                            is SearchEvent.Error -> {}
                        }
                    }
            }
        }
    }

    // 2) 由 rawResults 派生展示源：History 收敛到包含 source+id 的聚合组
    LaunchedEffect(rawResults, entry) {
        val displayed = when (entry) {
            is DetailEntry.History ->
                DetailSourceResolver.historyGroupSources(rawResults, entry.source, entry.id) ?: rawResults
            else -> rawResults
        }
        state = state.copy(sources = displayed)
    }

    // 3) 拉豆瓣详情（Douban 入口）
    LaunchedEffect(entry) {
        if (entry is DetailEntry.Douban) {
            state = state.copy(detailsLoading = true, detailsError = null)
            when (val resp = doubanService.getDoubanDetails(entry.doubanId)) {
                is ApiResponse.Success -> state = state.copy(details = resp.data, detailsLoading = false)
                is ApiResponse.Error -> state = state.copy(detailsError = resp.message, detailsLoading = false)
            }
        }
    }

    // 4) 拉 bangumi 详情（Bangumi 入口）
    LaunchedEffect(entry) {
        if (entry is DetailEntry.Bangumi) {
            state = state.copy(detailsLoading = true, detailsError = null)
            when (val resp = bangumiService.getBangumiDetail(entry.bangumiId.toString())) {
                is ApiResponse.Success -> state = state.copy(bangumiDetails = resp.data, detailsLoading = false)
                is ApiResponse.Error -> state = state.copy(detailsError = resp.message, detailsLoading = false)
            }
        }
    }

    // 5) tier2 豆瓣：History/SearchAggregate 下，展示源中某 doubanId 出现≥2 次则拉其豆瓣详情
    LaunchedEffect(state.sources, entry) {
        if (entry is DetailEntry.History || entry is DetailEntry.SearchAggregate) {
            val id = DetailSourceResolver.tier2DoubanId(state.sources)
            if (id != null) {
                if (state.details?.id != id) {
                    when (val resp = doubanService.getDoubanDetails(id)) {
                        is ApiResponse.Success -> state = state.copy(details = resp.data)
                        is ApiResponse.Error -> {} // 静默回退到源自身信息
                    }
                }
            } else if (state.details != null) {
                state = state.copy(details = null)
            }
        }
    }

    // 6) History/SearchAggregate 的内容就绪 → 关闭加载态
    LaunchedEffect(entry, state.sources, state.sourcesComplete) {
        if (entry is DetailEntry.History || entry is DetailEntry.SearchAggregate) {
            if ((state.sources.isNotEmpty() || state.sourcesComplete) && state.detailsLoading) {
                state = state.copy(detailsLoading = false)
            }
        }
    }

    // 拉播放记录
    LaunchedEffect(entry) {
        try {
            val records = withContext(Dispatchers.IO) { ApiService.getPlayRecords() }
            state = state.copy(playRecords = records)
        } catch (e: Exception) { android.util.Log.w("DetailScreen", "播放记录加载失败", e) }
    }

    // 拉收藏列表
    LaunchedEffect(entry) {
        try {
            val favs = withContext(Dispatchers.IO) { ApiService.getFavorites() }
            val keys = favs.map { "${it.source}+${it.id}" }.toSet()
            state = state.copy(favoriteKeys = keys)
        } catch (e: Exception) { android.util.Log.w("DetailScreen", "收藏列表加载失败", e) }
    }

    // 默认选中源：History 优先选中来源 source+id；否则最近播放记录，再否则第一个
    LaunchedEffect(state.sources, state.playRecords, entry) {
        val current = state.selectedSourceKey
        val stillValid = current != null && state.sources.any { sourceKey(it) == current }
        if (!stillValid && state.sources.isNotEmpty()) {
            val historyPick = (entry as? DetailEntry.History)?.let { h ->
                state.sources.firstOrNull { sourceKey(it) == "${h.source}+${h.id}" }
            }
            val recordPick = state.sources
                .mapNotNull { src -> state.playRecords[sourceKey(src)]?.let { rec -> src to rec.saveTime } }
                .maxByOrNull { it.second }
                ?.first
            val pick = historyPick ?: recordPick ?: state.sources.first()
            state = state.copy(selectedSourceKey = sourceKey(pick))
        }
    }

    // 是否真正展示加载层（延迟 150ms，避免命中缓存时闪屏）
    var showMoonOverlay by remember { mutableStateOf(false) }
    LaunchedEffect(state.detailsLoading) {
        if (state.detailsLoading) {
            kotlinx.coroutines.delay(150)
            if (state.detailsLoading) showMoonOverlay = true
        } else {
            showMoonOverlay = false
        }
    }

    val primaryFocus = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    val noVerticalAutoScroll = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
        }
    }

    LaunchedEffect(state.detailsLoading, state.selectedSource != null) {
        if (!state.detailsLoading && state.selectedSource != null) {
            kotlinx.coroutines.delay(260)
            try { primaryFocus.requestFocus() } catch (_: Exception) {}
            scrollState.scrollTo(0)
        }
    }

    fun showToast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    val onToggleFavorite: () -> Unit = lam@{
        val source = state.selectedSource ?: return@lam
        val key = sourceKey(source)
        val wasFav = state.isSelectedFavorite

        state = state.copy(
            favoriteKeys = if (wasFav) state.favoriteKeys - key else state.favoriteKeys + key
        )
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (wasFav) {
                        ApiService.unfavorite(source.source, source.id)
                    } else {
                        val favData = mapOf<String, Any?>(
                            "title" to (state.heroInfo?.title ?: state.entry.title),
                            "source_name" to source.sourceName,
                            "year" to (state.heroInfo?.subLine ?: state.entry.year),
                            "cover" to source.poster,
                            "total_episodes" to source.episodes.size,
                            "save_time" to System.currentTimeMillis(),
                            "origin" to "",
                        )
                        ApiService.addFavorite(source.source, source.id, favData)
                    }
                }
            } catch (e: Exception) {
                state = state.copy(
                    favoriteKeys = if (wasFav) state.favoriteKeys + key else state.favoriteKeys - key
                )
                showToast("收藏操作失败：${e.message ?: "未知错误"}")
            }
        }
    }

    val onDeleteRecord: () -> Unit = lam@{
        val source = state.selectedSource ?: return@lam
        val key = sourceKey(source)
        val backup = state.playRecords[key] ?: return@lam
        state = state.copy(playRecords = state.playRecords - key)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { ApiService.deletePlayRecord(source.source, source.id) }
                showToast("已删除播放记录")
            } catch (e: Exception) {
                state = state.copy(playRecords = state.playRecords + (key to backup))
                showToast("删除失败：${e.message ?: "未知错误"}")
            }
        }
    }

    val onContinue: () -> Unit = lam@{
        val source = state.selectedSource ?: return@lam
        val rec = state.selectedPlayRecord
        android.util.Log.d("DetailScreen", "continue: source=${source.source}+${source.id} ep=${rec?.index} pos=${rec?.playTime}")
    }

    val onPlay: () -> Unit = lam@{
        val source = state.selectedSource ?: return@lam
        val rec = state.selectedPlayRecord
        when {
            rec != null -> {
                android.util.Log.d("DetailScreen", "play (resume): source=${source.source}+${source.id} ep=${rec.index} pos=${rec.playTime}")
            }
            source.episodes.size > 1 -> {
                state = state.copy(episodePickerVisible = true)
            }
            else -> {
                android.util.Log.d("DetailScreen", "play (single): source=${source.source}+${source.id} ep=0")
            }
        }
    }

    val gradientBrush = Brush.verticalGradient(
        0.0f to Color(0xFF0a0a0a),
        0.4f to Color(0xFF1a1a1a),
        0.7f to Color(0xFF0f0f0f),
        1.0f to Color(0xFF050505),
    )

    Box(modifier = Modifier.fillMaxSize().background(brush = gradientBrush)) {
        androidx.compose.animation.AnimatedVisibility(
            visible = !state.detailsLoading,
            enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(0)),
        ) {
            val err = state.detailsError
            if (err != null && state.details == null && state.bangumiDetails == null) {
                HeroErrorState(
                    message = err,
                    onRetry = {
                        scope.launch {
                            state = state.copy(detailsLoading = true, detailsError = null)
                            when (val e = state.entry) {
                                is DetailEntry.Douban -> when (val resp = doubanService.getDoubanDetails(e.doubanId)) {
                                    is ApiResponse.Success -> state = state.copy(details = resp.data, detailsLoading = false)
                                    is ApiResponse.Error -> state = state.copy(detailsError = resp.message, detailsLoading = false)
                                }
                                is DetailEntry.Bangumi -> when (val resp = bangumiService.getBangumiDetail(e.bangumiId.toString())) {
                                    is ApiResponse.Success -> state = state.copy(bangumiDetails = resp.data, detailsLoading = false)
                                    is ApiResponse.Error -> state = state.copy(detailsError = resp.message, detailsLoading = false)
                                }
                                else -> state = state.copy(detailsLoading = false)
                            }
                        }
                    },
                )
            } else {
                CompositionLocalProvider(LocalBringIntoViewSpec provides noVerticalAutoScroll) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusGroup()
                        .focusProperties { exit = { FocusRequester.Cancel } }
                        .verticalScroll(scrollState),
                ) {
                    org.moontechlab.selenetv.screen.detail.DetailHero(
                        entry = state.entry,
                        info = state.heroInfo,
                        actionsRow = {
                            val hasRecord = state.selectedPlayRecord != null
                            val hasSource = state.selectedSource != null
                            Box(
                                modifier = Modifier.onFocusChanged {
                                    if (it.hasFocus) scope.launch { scrollState.animateScrollTo(0) }
                                },
                            ) {
                            org.moontechlab.selenetv.screen.detail.DetailActionButtons(
                                showContinue = hasSource && hasRecord,
                                showPlay = hasSource,
                                showDelete = hasSource && hasRecord,
                                showFavorite = hasSource,
                                isFavorite = state.isSelectedFavorite,
                                sourceName = state.selectedSource?.sourceName,
                                onContinue = onContinue,
                                onPlay = onPlay,
                                onDelete = onDeleteRecord,
                                onToggleFavorite = onToggleFavorite,
                                primaryFocusRequester = primaryFocus,
                            )
                            }
                        },
                    )
                    Box(
                        modifier = Modifier.onFocusChanged {
                            if (it.hasFocus) scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                        },
                    ) {
                        org.moontechlab.selenetv.screen.detail.DetailSourcesSection(
                            sources = state.sources,
                            selectedSourceKey = state.selectedSourceKey,
                            playRecords = state.playRecords,
                            progress = state.sourcesProgress,
                            complete = state.sourcesComplete,
                            onSelect = { key -> state = state.copy(selectedSourceKey = key) },
                        )
                    }
                }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showMoonOverlay,
            enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(200)),
        ) {
            org.moontechlab.selenetv.screen.detail.MoonLoadingOverlay(title = state.entry.title)
        }

        state.selectedSource?.let { source ->
            org.moontechlab.selenetv.screen.detail.EpisodePickerDialog(
                visible = state.episodePickerVisible,
                sourceName = source.sourceName,
                episodes = source.episodes,
                episodesTitles = source.episodesTitles,
                onSelect = { idx ->
                    android.util.Log.d("DetailScreen", "play: source=${source.source}+${source.id} ep=$idx")
                    state = state.copy(episodePickerVisible = false)
                },
                onDismiss = { state = state.copy(episodePickerVisible = false) },
            )
        }
    }
}

@Composable
private fun HeroErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
            androidx.tv.material3.Text(
                text = "加载失败：$message",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
            )
            androidx.tv.material3.Surface(
                onClick = onRetry,
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = Color(0x14FFFFFF),
                    focusedContainerColor = Color(0xFF4CAF50),
                ),
                modifier = Modifier.onFocusChanged { focused = it.isFocused },
            ) {
                androidx.tv.material3.Text(
                    text = "重试",
                    color = if (focused) Color.White else Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp),
                )
            }
        }
    }
}
```

> 说明：收藏写入的 `title`/`year` 改为优先用 `state.heroInfo`（兜底 `entry`），等价于原先的 `details ?: route`。`MoonLoadingOverlay(title = state.entry.title)` 取代原 `route.title`。

- [ ] **Step 2: 修改 SearchTab.kt 接入点击回调**

在 `SearchTab` 函数签名增加参数（在 `scrollState` 后）：

```kotlin
fun SearchTab(
    focusRequester: FocusRequester = remember { FocusRequester() },
    scrollState: ScrollState = rememberScrollState(),
    onOpenDetail: (AggregatedSearchResult) -> Unit = {},
) {
```

把结果网格的点击 TODO 改为回调。将：

```kotlin
                        onResultClick = { /* TODO: 处理点击 */ },
```

替换为：

```kotlin
                        onResultClick = onOpenDetail,
```

（`AggregatedSearchResult` 已在 SearchTab 顶部 import。）

- [ ] **Step 3: 修改 HomeScreen.kt 使用 DetailEntry**

3a. 把 `import org.moontechlab.selenetv.model.VideoInfo` 保留，并确认 `DetailEntry`/`detailEntryFor` 同包（`org.moontechlab.selenetv.screen`）无需 import。新增需要的 import：

```kotlin
import org.moontechlab.selenetv.model.AggregatedSearchResult
```

3b. 替换覆盖层状态声明：

将
```kotlin
    // 详情页作为覆盖层渲染（而非导航目的地），首页组合保持存活 → 数据与焦点不丢失。
    var detailRoute by remember { mutableStateOf<DetailRoute?>(null) }
```
替换为
```kotlin
    // 详情页作为覆盖层渲染（而非导航目的地），首页组合保持存活 → 数据与焦点不丢失。
    var detailEntry by remember { mutableStateOf<DetailEntry?>(null) }
```

3c. 替换 `openDetail`，并新增 `openDetailFromSearch`：

将
```kotlin
    val openDetail: (VideoInfo) -> Unit = remember {
        { video ->
            val doubanId = video.doubanId
            if (doubanId != null) {
                detailReturn.setTarget("${video.source}+${video.id}")
                detailRoute = DetailRoute(
                    doubanId = doubanId,
                    title = video.title,
                    poster = video.cover,
                    year = video.year,
                )
            } else {
                android.util.Log.d("HomeScreen", "openDetail skipped: no doubanId for '${video.title}' (source=${video.source})")
            }
        }
    }
```
替换为
```kotlin
    val openDetail: (VideoInfo) -> Unit = remember {
        { video ->
            detailReturn.setTarget("${video.source}+${video.id}")
            detailEntry = detailEntryFor(video)
        }
    }

    val openDetailFromSearch: (AggregatedSearchResult) -> Unit = remember {
        { agg ->
            val firstSource = agg.originalResults.firstOrNull()?.source ?: ""
            detailReturn.setTarget("$firstSource+${agg.key}")
            detailEntry = DetailEntry.SearchAggregate(
                agg = agg,
                title = agg.title,
                poster = agg.cover,
                year = agg.year,
            )
        }
    }
```

3d. `closeDetail`：将 `detailRoute = null` 改为 `detailEntry = null`：
```kotlin
    val closeDetail: () -> Unit = {
        detailEntry = null
        homeRefreshKey++
    }
```

3e. BackHandler 两处 `detailRoute == null` → `detailEntry == null`（共 2 处）。

3f. `LaunchedEffect(detailRoute)` 块整体把 `detailRoute` 替换为 `detailEntry`：
```kotlin
    var hadDetailOpen by remember { mutableStateOf(false) }
    LaunchedEffect(detailEntry) {
        if (detailEntry != null) {
            hadDetailOpen = true
        } else if (hadDetailOpen) {
            hadDetailOpen = false
            delay(300)
            restoreActiveTabFocus()
        }
    }
```

3g. SearchTab 调用处接线：
将
```kotlin
                "search" -> SearchTab(
                    focusRequester = searchFocusRequester,
                    scrollState = scrollState
                )
```
替换为
```kotlin
                "search" -> SearchTab(
                    focusRequester = searchFocusRequester,
                    scrollState = scrollState,
                    onOpenDetail = openDetailFromSearch,
                )
```

3h. 覆盖层渲染：将
```kotlin
        val retainedDetailRoute = remember { mutableStateOf<DetailRoute?>(null) }
        detailRoute?.let { retainedDetailRoute.value = it }
        AnimatedVisibility(
            visible = detailRoute != null,
            enter = slideInHorizontally(
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                initialOffsetX = { fullWidth -> fullWidth },
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                targetOffsetX = { fullWidth -> fullWidth },
            ),
        ) {
            retainedDetailRoute.value?.let { route ->
                DetailScreen(route = route, onBack = closeDetail)
            }
        }
```
替换为
```kotlin
        val retainedDetailEntry = remember { mutableStateOf<DetailEntry?>(null) }
        detailEntry?.let { retainedDetailEntry.value = it }
        AnimatedVisibility(
            visible = detailEntry != null,
            enter = slideInHorizontally(
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                initialOffsetX = { fullWidth -> fullWidth },
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                targetOffsetX = { fullWidth -> fullWidth },
            ),
        ) {
            retainedDetailEntry.value?.let { entry ->
                DetailScreen(entry = entry, onBack = closeDetail)
            }
        }
```

- [ ] **Step 4: 删除 Routes.kt 中的 DetailRoute**

编辑 `app/src/main/java/org/moontechlab/selenetv/screen/Routes.kt`，删除：
```kotlin
@Serializable
data class DetailRoute(
    val doubanId: String,
    val title: String,
    val poster: String,
    val year: String,
)
```
若删除后 `import kotlinx.serialization.Serializable` 仍被 `HomeRoute`/`LoginRoute` 使用，则保留该 import。

- [ ] **Step 5: 确认无残留 DetailRoute 引用**

Run: `grep -rn "DetailRoute" app/src/main/java`
Expected: 无输出（全部已移除）。

- [ ] **Step 6: 编译整个 app**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。若失败按报错修正 import / 残留引用。

- [ ] **Step 7: 运行全部单元测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，含 `DetailUiStateHeroInfoTest`、`DetailEntryTest`、`HeroInfoTest`、`DetailSourceResolverTest`、`SourceFilterTest` 全通过。

- [ ] **Step 8: 提交（合并 Task 5 + Task 6）**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/ app/src/test/java/org/moontechlab/selenetv/screen/
git commit -m "全部卡片可进入详情页：entry 驱动的源获取与 hero 信息优先级"
```

---

## Task 7: 真机/模拟器手动验证

**Files:** 无（验证）。

- [ ] **Step 1: 构建 debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 安装并逐项验证（D-pad）**

按以下清单核对：
1. 首页「继续播放」卡片 → 进入详情：自动选中原源，播放源为其聚合组；若组内同一 doubanID ≥2 次则展示豆瓣信息，否则展示选中源自身信息（标题/年份/类型/集数/简介）。
2. 首页「收藏夹」卡片 → 同上。
3. 搜索页聚合卡片 → 进入详情：播放源直接为该聚合 `originalResults`（不触发新搜索、无搜索进度），信息按 doubanID≥2 / 源自身规则。
4. 「番剧」卡片 → 展示 bangumi 详情；播放源按标题/年份匹配。
5. 豆瓣卡片（首页推荐 / 电影 / 剧集 / 综艺 Tab）→ 行为与改造前一致。
6. 每种入口返回后，焦点还原到来源卡片（含搜索卡片）。
7. 详情详情拉取失败（断网测试豆瓣/番剧入口）→ 显示「重试」错误态；History/SearchAggregate 入口 tier2 失败时静默回退到源自身信息、不报错。

- [ ] **Step 3: 记录验证结果**

在 PR / 提交说明中记录各项实际表现（含截图，如可）。

---

## 自查清单（写计划后核对，已完成）

- **Spec 覆盖**：需求 1（继续/收藏→聚合组+自动选中）= Task 3+6；需求 2（搜索聚合→快照源）= Task 4+6；需求 4（bangumi）= Task 1+6；需求 5（信息优先级）= Task 2+5；全部卡片可点击 = Task 4+6（`detailEntryFor` 不再返回 null）。需求 3（非聚合卡片）已确认排除。
- **占位符**：无 TODO/TBD；每个代码步骤含完整代码。
- **类型一致**：`DetailEntry`/`detailEntryFor`/`HeroInfo`/`toHeroInfo`/`DetailSourceResolver.historyGroupSources`/`tier2DoubanId`/`SourceFilter.matchesByTitleYear`/`DetailUiState.entry|heroInfo`/`DetailHero(entry, info, actionsRow)`/`DetailScreen(entry, onBack)`/`SearchTab(..., onOpenDetail)` 在各任务间签名一致。
- **编译次序**：Task 1–4 为新增叶子（各自可编译、可单测、可提交）；Task 5–6 为耦合改造，统一在 Task 6 末尾编译+测试+提交。
