# 全部卡片可进入详情页 — 设计文档

日期：2026-05-24

## 背景与目标

目前详情页（`DetailScreen`，作为首页覆盖层渲染）只能从豆瓣卡片进入：

- `HomeScreen.openDetail(VideoInfo)` 要求 `video.doubanId != null`，否则静默跳过（仅打日志）。
- `PlayRecord` / `FavoriteItem` 不携带 `doubanId`/`bangumiId`，只有 `source`+`id`+`title`+`searchTitle`+`year`+`cover`。因此继续播放 / 收藏夹卡片无法进入。
- Bangumi 卡片携带 `bangumiId`（无 doubanId），目前也被跳过。
- 搜索页卡片全部是 `AggregatedSearchResult`（每个 `title_年份_类型` 一张卡，背后 `originalResults` 为该组的 `SearchResult` 列表）。其点击回调 `onResultClick` 目前是 `TODO`，搜索卡片完全不进入详情。

目标：让全部卡片都能进入详情页，并按入口类型决定播放源列表的获取方式、默认选中、以及 hero 信息的展示优先级。

### 范围（明确取舍）

- **不实现**「搜索页非聚合卡片」入口（原需求 3）。经确认，搜索页只渲染聚合卡片，暂不考虑单源卡片场景。
- 不改动播放器、剧集选择对话框、收藏/删除记录的网络逻辑、焦点还原与滚动行为——仅扩展入口与信息展示。

## 现有关键事实（代码参考）

- `HomeScreen.kt`：持有 `detailRoute: DetailRoute?` 覆盖层状态；`openDetail: (VideoInfo) -> Unit`；`DetailReturnFocusState` 通过 `"${source}+${id}"` 还原焦点；各 Tab 以 `onVideoSelected = openDetail` 接线。`SearchTab` 当前实例化为 `SearchTab(focusRequester, scrollState)`，未接入详情。
- `DetailScreen.kt`：接收 `DetailRoute(doubanId, title, poster, year)`，`LaunchedEffect(route.doubanId)` 拉豆瓣详情、启动搜索并用 `SourceFilter.matchesContent(...)` 过滤源、拉播放记录、拉收藏；默认选中「最近播放记录的源，否则第一个」。
- `DetailUiState.kt`：`sourceKey(result) = "${source}+${id}"`；`selectedSource`/`selectedPlayRecord`/`isSelectedFavorite` 派生属性。
- `SearchResult`（`model/SearchResult.kt`）：含 `id, source, sourceName, title, year, poster, episodes, episodesTitles, desc, typeName, doubanId: Long?`。
- `AggregatedSearchResult`：`key = "${title}_${year}_$type"`，`originalResults`、`doubanIds: MutableMap<Long, Int>`（id→出现次数）、`sourceNames`、`toVideoInfo()`（`id=key`、`source=originalResults.first().source`、`doubanId=getBestDoubanId()`）。`SearchResult.generateAggregateKey()` 与 `aggregateSearchResults(list)` 复用搜索页聚合逻辑。
- `BangumiService.getBangumiDetail(bangumiId: String): ApiResponse<BangumiDetails>` 已存在；`BangumiDetails` 含 `name/nameCn, summary, date, rating.score, tags, metaTags, eps, totalEpisodes, images`。
- `DoubanService.getDoubanDetails(doubanId): ApiResponse<DoubanMovieDetails>`。
- `DetailHero.kt`：目前签名为 `DetailHero(route, details: DoubanMovieDetails?, actionsRow)`，仅渲染豆瓣字段。
- `SourceFilter.matchesContent(result, doubanId, title, year)`：doubanId 强匹配；result 无 doubanId 时按 title/year 弱匹配；result 带不同 doubanId 则拒绝。

## 设计

### 1. 入口模型 `DetailEntry`

新增密封接口替换 `DetailRoute` 作为覆盖层状态载体。因详情是内存覆盖层（非真实导航参数），可直接携带对象，无需可序列化。

```kotlin
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

    // 继续播放 + 收藏夹
    data class History(
        val source: String,
        val id: String,
        val searchTitle: String,
        override val title: String,
        override val poster: String,
        override val year: String,
    ) : DetailEntry

    // 搜索页聚合卡片
    data class SearchAggregate(
        val agg: AggregatedSearchResult,
        override val title: String,
        override val poster: String,
        override val year: String,
    ) : DetailEntry
}
```

`DetailRoute` 可保留为内部「hero 兜底字段」轻量结构，或由 `DetailEntry` 的公共字段直接提供；实现时二选一，避免重复定义。本设计倾向：删除 `DetailRoute`，`DetailScreen(entry: DetailEntry, onBack)`，`DetailHero` 改用 `entry` 公共字段做兜底。

### 2. 接线（HomeScreen / SearchTab）

`HomeScreen`：
- 状态 `detailRoute: DetailRoute?` → `detailEntry: DetailEntry?`；覆盖层 `AnimatedVisibility(visible = detailEntry != null)` 渲染 `DetailScreen(entry, onBack = closeDetail)`，保留 `retainedDetailEntry` 退出动画期间留存。
- `openDetail(video: VideoInfo)` 分流：
  - `video.doubanId != null` → `DetailEntry.Douban(doubanId, title, cover, year)`
  - 否则 `video.bangumiId != null` → `DetailEntry.Bangumi(bangumiId, title, cover, year)`
  - 否则 → `DetailEntry.History(source, id, searchTitle = video.searchTitle, title, cover, year)`
  - 还原焦点目标：`detailReturn.setTarget("${video.source}+${video.id}")`（不变）。
- 新增 `openDetailFromSearch(agg: AggregatedSearchResult)`：
  - 构造 `DetailEntry.SearchAggregate(agg, title = agg.title, poster = agg.cover, year = agg.year)`。
  - 还原焦点目标：`"${agg.originalResults.firstOrNull()?.source ?: ""}+${agg.key}"`（与搜索卡片 `VideoCard` 的 `returnKey = "${videoInfo.source}+${videoInfo.id}"` 一致，因 `agg.toVideoInfo()` 的 `source=first().source`、`id=key`）。
- `SearchTab(...)` 增传 `onOpenDetail = openDetailFromSearch`。

`SearchTab`：
- 新增参数 `onOpenDetail: (AggregatedSearchResult) -> Unit = {}`。
- 将 `SearchResultsGrid(... onResultClick = { /* TODO */ })` 改为 `onResultClick = onOpenDetail`（沿现有 `onResultClick: (AggregatedSearchResult) -> Unit` 传递链，已具备）。

继续播放 / 收藏夹 / bangumi / 各豆瓣 Tab 无需改动——它们已传 `onVideoSelected = openDetail`，由上面的分流逻辑覆盖。

### 3. 各入口的播放源获取

详情页用 `when (entry)` 决定源列表来源。`DisposableEffect { SearchService.stopSearch() }` 与现有一致。

- **Douban**（不变）：`SearchService.startSearch(entry.title)`，事件流中保留 `SourceFilter.matchesContent(it, doubanId, title, year)` 且 `!ContentFilterService.shouldFilter(typeName)` 的源，`deduplicateResults` 合并。
- **Bangumi**（新增）：按 `entry.title` 搜索，保留 `SourceFilter.matchesByTitleYear(it, title, year)` 命中的源（新增方法，见下）。同样过滤 `ContentFilterService`。
- **History**（继续播放/收藏夹）：
  1. 按 `entry.searchTitle` 搜索，事件流累积原始 `SearchResult`（`deduplicateResults`）。
  2. 对累积结果跑 `aggregateSearchResults(...)`，找到 `originalResults` 中存在 `sourceKey(r) == "${entry.source}+${entry.id}"` 的聚合组。
  3. 源列表 = 该组 `originalResults`；默认**自动选中** `source`+`id` 那个源（`selectedSourceKey = "${entry.source}+${entry.id}"`）。
  4. 兜底：若搜索结束仍未找到精确源，则退化为按 `title/year` 弱匹配（`SourceFilter.matchesByTitleYear`）收集源，默认选中逻辑用「最近播放记录，否则第一个」。
- **SearchAggregate**（搜索页聚合卡片）：
  - 源列表 = `entry.agg.originalResults` 直接快照，**不重新搜索**（不调用 `SearchService.startSearch`）。
  - 仍可过滤 `ContentFilterService.shouldFilter(typeName)`，保持与搜索页一致的可见性。
  - 默认选中沿用「最近播放记录，否则第一个」。
  - 进度/完成态：直接置 `sourcesComplete = true`，不显示搜索进度。

新增 `SourceFilter.matchesByTitleYear(result, title, year)`：

```kotlin
fun matchesByTitleYear(result: SearchResult, title: String, year: String): Boolean {
    val titleOk = result.title == title
    val yearOk = result.year == year || year.isEmpty() || result.year.isEmpty()
    return titleOk && yearOk
}
```

### 4. Hero 信息优先级（需求 5）

新增统一视图模型与适配器：

```kotlin
data class HeroInfo(
    val title: String,
    val poster: String,
    val rate: String? = null,          // "★" 评分文本，null 不显示
    val genres: List<String> = emptyList(),
    val subLine: String? = null,       // 副信息行（年份/国家/时长/日期等，· 连接）
    val summary: String? = null,
)

fun DoubanMovieDetails.toHeroInfo(): HeroInfo   // 复用现 DetailHero 内字段拼装逻辑
fun BangumiDetails.toHeroInfo(): HeroInfo       // title=nameCn?:name, rate=rating.score(>0), genres=metaTags/tags 前若干, subLine=date, summary
fun SearchResult.toHeroInfo(): HeroInfo         // title, poster, rate=null, genres=listOfNotNull(typeName), subLine=year, summary=desc
```

`DetailHero` 签名改为 `DetailHero(entry: DetailEntry, info: HeroInfo?, actionsRow)`：用 `info` 渲染，空字段回退到 `entry.title/poster`。源自身精简 hero 即 `SearchResult.toHeroInfo()`：展示标题、年份（subLine）、类型（genres）、集数（来自选中源 `episodes.size`，可在按钮区/标题旁体现，沿用现有集数徽章口径）、`desc`（summary，若有），不含评分与演职员。

**解析优先级**（派生于 `DetailUiState`）：

1. `entry is Douban` → `state.details?.toHeroInfo()`（拉 `getDoubanDetails(doubanId)`）。
2. `entry is Bangumi` → `state.bangumiDetails?.toHeroInfo()`（拉 `getBangumiDetail(bangumiId)`）。
3. `entry is History | SearchAggregate`：
   a. 统计**已展示源**的 `doubanId` 次数（History 用找到组的源；SearchAggregate 直接用 `agg.doubanIds`）。取次数 `>= 2` 中出现最多者 `tier2DoubanId`。
   b. 若 `tier2DoubanId != null` → 拉 `getDoubanDetails(tier2DoubanId)` 存入 `state.details` → `state.details?.toHeroInfo()`。
   c. 否则 → `state.selectedSource?.toHeroInfo()`（随选中源变化而更新）。

当 tier2 豆瓣信息已就绪时，hero 固定为该豆瓣信息，不随选中源变动；未命中 tier2 时 hero 跟随选中源。

### 5. 状态与加载

`DetailUiState` 调整：
- `route: DetailRoute` → `entry: DetailEntry`。
- 新增 `bangumiDetails: BangumiDetails? = null`。
- 新增派生 `heroInfo: HeroInfo?`（按第 4 节优先级计算；依赖 `entry, details, bangumiDetails, sources, selectedSource`）。
- 新增 `tier2DoubanId: String?` 的计算（或内联在 effect 中），用于触发豆瓣详情拉取。
- `selectedSource`/`selectedPlayRecord`/`isSelectedFavorite`/`sourceKey` 不变。

加载层（`MoonLoadingOverlay`，150ms 延迟）显示条件：内容未就绪。「内容就绪」按入口：
- Douban / Bangumi：详情拉取完成（成功或错误）。
- History：首批源到达，或搜索结束（`sourcesComplete`）。
- SearchAggregate：立即就绪。

错误态：Douban/Bangumi 详情拉取失败且无数据时，复用现有 `HeroErrorState`（带重试）。History/SearchAggregate 无独立详情请求，tier2 豆瓣拉取失败时静默回退到源自身信息（不报错）。

各入口的 `LaunchedEffect` key 由 `route.doubanId` 改为 `entry`（或入口稳定标识），确保切换入口重新加载。

## 涉及文件

- `screen/DetailEntry.kt`（新增）
- `screen/Routes.kt`（删除/精简 `DetailRoute`）
- `screen/HomeScreen.kt`（状态、`openDetail` 分流、`openDetailFromSearch`、SearchTab 接线、覆盖层渲染）
- `screen/tab/SearchTab.kt`（`onOpenDetail` 参数 + `onResultClick` 接线）
- `screen/DetailScreen.kt`（按 `entry` 分流的源获取、信息解析、加载态）
- `screen/detail/DetailUiState.kt`（`entry`、`bangumiDetails`、`heroInfo`、tier2 计算）
- `screen/detail/DetailHero.kt`（改用 `HeroInfo`）
- `screen/detail/SourceFilter.kt`（新增 `matchesByTitleYear`）
- `screen/detail/HeroInfo.kt`（新增视图模型 + 适配器）

## 测试与验证

- 编译通过（`./gradlew :app:assembleDebug` 或等效）。
- 手动验证（D-pad）：
  1. 继续播放卡片进入 → 自动选中原源、播放源为该聚合组；若该剧多源含同一 doubanID≥2 次则展示豆瓣信息，否则展示源自身信息。
  2. 收藏夹卡片进入 → 同上。
  3. 搜索聚合卡片进入 → 直接展示该聚合 `originalResults`，不触发新搜索；信息按 doubanID≥2 / 源自身规则。
  4. bangumi 卡片进入 → 展示 bangumi 详情；播放源按标题/年份匹配。
  5. 豆瓣卡片进入 → 行为与现状一致。
  6. 返回后焦点还原到来源卡片（含搜索卡片）。

## 风险与已知限制

- `SearchService` 为全局单例：History/Bangumi/Douban 入口仍会与 `SearchTab` 的搜索互相打断（沿用现有已知限制注释）。SearchAggregate 入口因不重搜而规避此问题。
- History 入口依赖搜索能召回原源：源失效时走标题/年份兜底，可能选中非原始源。
- bangumi 标题匹配较弱（番剧译名/季度命名差异），可能召回不全或误召；按 `title/year` 已是现有弱匹配口径。
