# 跳过片头/片尾（Intro / Outro Skip）设计 Spec

- 日期：2026-05-30
- 状态：已批准（待实现）
- 适用范围：点播播放页（`PlayerScreen`）。直播不适用。

## 1. 目标与背景

在点播播放页提供「跳过片头 / 跳过回顾 / 跳过片尾」的**手动**浮层按钮：进入对应区间时出现，预聚焦，按 OK 一键跳过。时间戳数据来自两个社区众包数据库（TIDB / IntroDB）。

定位为**锦上添花、best-effort** 功能：主要惠及单季番剧与欧美剧；华语剧/国漫/综艺覆盖近零（社区数据现状），无数据时静默关闭，绝不打断或拖慢播放。

### 非目标（Not building）
- 自动跳过（任何形式的自动 seek）。
- 设置开关（不提供「启用/关闭自动跳过」等选项）。
- 直播频道支持。
- IntroDB（introdb.app）对电影的补充（该站仅 TV）。
- 季/集映射的高级纠偏（多季拍平等边角场景，见 §7 风险）。

## 2. 数据源调研结论（已 live 实测）

两个并行、独立、互不通的众包库，读取均**免鉴权**：

| | **TIDB（theintrodb.org）** | **IntroDB（introdb.app）** |
|---|---|---|
| 读取端点 | `GET https://api.theintrodb.org/v3/media?tmdb_id=&season=&episode=`（电影省 season/episode） | `GET https://api.introdb.app/segments?imdb_id=&season=&episode=` |
| 媒体标识 | **tmdb_id**（imdb 兜底） | **仅 imdb_id**（`^tt[0-9]{7,8}`） |
| 类型 | 电影 + 剧集 | **仅剧集** |
| 段类型 | `intro / recap / credits / preview`，每类**数组** | `intro / recap / outro`，每类单对象 |
| 段字段 | `start_ms / end_ms`（可 null） | `start_ms / end_ms / confidence / submission_count` |
| null 语义 | intro/recap `start=null→0`；credits/preview `end=null→到片尾` | 字段恒全 |
| 限流 | 头 `30 req/10s`（按 IP）+ usage 限额 | "fair usage"，不暴露头 |
| 无数据 | `{"error":"media not found"}` | 段字段为 `null` |

**选型**：TIDB 作主源（吃 tmdb_id，最贴合本项目；覆盖电影；多段），IntroDB 作 TV 补充源（要 imdb 多一跳，仅 TV，但有 confidence 可做质量门控）。

## 3. 项目侧已有的复用点

- **`TmdbService`**（`service/TmdbService.kt`）：已能用 `/search/multi`（original_title + year）解析出 `(mediaType, tmdbId)`（私有 `search()`，返回 `Pair<String, Long>`）。已用 `DoubanCacheService` 缓存。`stripSeasonSuffix()` 已在解析续作的「季」后缀（`Season 2`/`第二季`/`2nd Season`）。
- **`DetailScreen`**（`screen/DetailScreen.kt`）：`:498` 构建 `PlayerLaunch`；`:278/:299` 已用 `tmdbQueryTitle(originalTitle, title)` + year + apiKey + `doubanCacheService` 调 `resolveArt`。→ id 解析的全部原料已在此处就位。
- **`PlayerScreen`** 现状：`controller.seekTo(ms)`（`PlayerController:97`）、`goNext()`/`hasNext`（`PlayerScreen:229/141`）、500ms 位置轮询（`:250-255`）、自动隐藏 effect（`:307-313`，隐藏时把焦点 reseat 回 scrubber）、焦点链 `speedPill ↔ scrubber ↔ panel`（`scrubber.upTarget = speedPillFocus`，`SpeedPill.up = Cancel`，`scrubber` DOWN→panel、L/R→seek）。
- **前置改动（已落地）**：无控制条时按 OK 已从「播放/暂停」改为「唤起控制条」（commit `531d4bd`），消除 OK 重载。

## 4. 数据链路（端到端）

```
DetailScreen ──┬─ resolveArt（已有）──→ TMDB /search/multi ─→ (mediaType, tmdbId)
               └─ resolveMediaRef（新）─→ + season（原名后缀解析）+ imdbId（external_ids，仅 TV）
                          │
                   TmdbMediaRef ──→ PlayerLaunch.tmdbRef ──→ PlayerScreen
                                                                  │ 每集（IO）
                                            IntroSkipService.fetchSegments(ref, ep)
                                              ├─ TIDB  /v3/media?tmdb_id&season&episode   （主）
                                              └─ introdb.app /segments?imdb_id&season&ep  （补，confidence 门控）
                                                      │ 合并 + DoubanCache（30d）
                                                List<SkipSegment> ──→ 浮层 UI
```

### 4.1 `TmdbService` 扩展
- 新 `suspend fun resolveMediaRef(queryTitle, year, apiKey, cache): TmdbMediaRef?`：
  - 复用 `search()` 拿 `(mediaType, tmdbId)`。
  - **season**：从原名后缀解析成数字。新增 `internal fun parseSeasonNumber(title): Int`，识别与 `stripSeasonSuffix` 同套模式（`Season N` / `第N季`（含中文数字）/ `N(st|nd|rd|th) Season`），无后缀→`1`。
  - **imdbId**（仅 `mediaType=="tv"`）：调 `GET /{type}/{id}/external_ids?api_key=` 取 `imdb_id`，失败→null。
  - 结果进 `DoubanCacheService`，键前缀 `tmdb_ref`，TTL 命中 30d / 未命中 3d。
- 新 model `TmdbMediaRef(mediaType: String, tmdbId: Long, season: Int, imdbId: String?)` 放 `model/Tmdb.kt`。

### 4.2 新 `service/IntroSkipService.kt`（object）
- 调用约定：`Dispatchers.IO`、**best-effort**——任何失败/无数据一律 catch 后返回空列表，**不抛异常**（区别于 `ApiService` 的抛异常约定，因为播放不能被它中断）。标准 OkHttp（TIDB/IntroDB 均正规 HTTPS，**不用 trust-all**）。
- `suspend fun fetchSegments(ref: TmdbMediaRef, episode1Based: Int, cache: DoubanCacheService): List<SkipSegment>`：
  - **TIDB（主）**：`/v3/media?tmdb_id={tmdbId}&season={season}&episode={episode}`（`mediaType=="movie"` 时省 season/episode）。映射：`intro→INTRO`、`recap→RECAP`、`credits→OUTRO`、`preview→OUTRO`。`start_ms` null→`0`；`end_ms` null→哨兵 `Long.MAX_VALUE`（消费时夹到 `durationMs`）。每类数组全部取入。
  - **IntroDB（补）**：仅当 `mediaType=="tv" && imdbId != null`。`/segments?imdb_id={imdbId}&season={season}&episode={episode}`。映射 `intro→INTRO`、`recap→RECAP`、`outro→OUTRO`。**仅用于填 TIDB 缺失的类型**，且需 `confidence >= 0.5 || submission_count >= 2` 才采纳。
  - **合并**：按 type 归并，TIDB 优先；IntroDB 仅补 TIDB 没有的 type。
  - **缓存**：合并结果序列化进 `DoubanCacheService`，键 `intro_seg_{tmdbId}_{season}_{episode}`，TTL 命中 30d / 空结果 3d（数据后续可能被补充）。
- 新 model：`data class SkipSegment(val type: SkipType, val startMs: Long, val endMs: Long)` + `enum class SkipType { INTRO, RECAP, OUTRO }`（与 service 同包或 `model/`）。

### 4.3 Player 数据注入
- `PlayerLaunch` 加字段 `val tmdbRef: TmdbMediaRef? = null`；`DetailScreen:498` 构建时填入（在已有 `resolveArt` 旁顺带 `resolveMediaRef`，复用同一 query/year/apiKey/cache）。
- `PlayerScreen` 加 `LaunchedEffect(currentEpisode, currentSourceKey, tmdbRef)` → IO 调 `fetchSegments` → 写 `var segments by remember`。`tmdbRef == null` 或返回空 → `segments` 为空，功能静默关闭。

## 5. 交互设计

### 5.1 显隐与状态
- 浮层按钮是 root `Box`（`PlayerScreen:398`）的**独立子项**，`align(Alignment.TopEnd)`，**只要 `activeSegment != null` 就显示**（控制条隐藏/可见两态都显示），自带 alpha（控制条隐藏时也可见）。
- `activeSegment` = 由 `positionMs` 命中的段：`positionMs in startMs until min(endMs, durationMs)`，取首个命中。
- 视觉位置：**右上，倍速键正上方**；两种控制条状态**同一位置，不跳位**。

### 5.2 三态行为

| 状态 | 按钮视觉 | 焦点所在 | OK | 方向键 |
|---|---|---|---|---|
| 段内 + 控制条隐藏 | **反色**（白底黑字），预聚焦 | 在按钮 | 跳过本段 | 任意方向 → `wake()` + 焦点回 scrubber |
| 段内 + 控制条可见 | **黑底白字**（静息），可被导航到 | scrubber 等 | 在按钮上时跳过本段 | 走 focusProperties（见 5.3） |
| 不在段内 | 隐藏 | scrubber | （现状：唤起控制条） | （现状） |

- 控制条可见时，用户从 scrubber 按「上」到倍速键，再「上」到跳过键（反色）。

### 5.3 焦点链（控制条可见 + 段内）
```
跳过键 ↕ 倍速 ↕ 进度条 ↕ 面板   （自上而下）
```
- `scrubber.upTarget = speedPillFocus` —— **不变**（scrubber 路由零改动）。
- `SpeedPill` 新增可选参数 `upTarget: FocusRequester? = null`，内部 `up = upTarget ?: FocusRequester.Cancel`。`PlayerScreen` 在 `activeSegment != null` 时传 `skipFocus`，否则传 `null`（维持现状的 `Cancel`）。
- 跳过键 `focusProperties { down = speedPillFocus; up = FocusRequester.Cancel }`（栈顶）；左右 = `Cancel`。

### 5.4 按键路由（跳过键自身 `onKeyEvent`）
- **OK / Center / Enter** → `controller.seekTo(activeSegment.endMs.coerceAtMost(durationMs))`；若 `type==OUTRO`：`hasNext` 则 `goNext()`，否则 seek 到末。跳过后 `positionMs` 跳出该段 → 按钮消失；**显式 `scrubberFocus.requestFocus()` reseat 焦点**（控制条保持隐藏，无缝）。
- **方向键**：`controlsVisible` 为隐藏时 → `wake()` + `scrubberFocus.requestFocus()`；可见时 → 不在此拦截，交给 focusProperties（5.3）。
- **BACK** → 不拦截，落到现有 BackHandler（二次确认退出）。

### 5.5 自动隐藏 effect 改动
- `PlayerScreen:307-313`：控制条隐藏那一刻，若 `activeSegment != null` 焦点落 `skipFocus`，否则仍落 `scrubberFocus`。
- 段结束而用户未按下时：`activeSegment` 变 null → 按钮移出 composition；若按钮当时持焦，需 reseat 焦点回 `scrubberFocus`（复刻仓库既有「面板移除 → 重试 requestFocus 回 scrubber」的套路，带小重试以越过清焦延迟）。

### 5.6 按钮组件 `screen/player/IntroSkipOverlay.kt`
- 可聚焦 `Box`（与 scrubber 同样用 `focusRequester + onFocusChanged + focusable`，不套 `Surface`，与本页既有写法一致）。
- 视觉：6dp 圆角矩形；未聚焦黑底白字 + 1dp 白边；聚焦反色（白底黑字）；~1.03x 轻缩放。
- 标签：`INTRO→跳过片头`、`RECAP→跳过回顾`、`OUTRO→ hasNext ? "下一集" : "跳过片尾"`。
- 位置：`TopEnd` + padding，落在倍速键正上方右侧；具体 dp 真机截图微调，避开倍速弹窗等。

## 6. 受影响文件清单

新增 2 文件、改 5 文件（含 `model/Tmdb.kt`），1 个新 service：

1. **新** `service/IntroSkipService.kt`（+ `SkipSegment`/`SkipType`）。
2. **新** `screen/player/IntroSkipOverlay.kt`（按钮组件）。
3. **改** `service/TmdbService.kt`（`resolveMediaRef` + `parseSeasonNumber` + external_ids；+ model `TmdbMediaRef` 于 `model/Tmdb.kt`）。
4. **改** `screen/player/PlayerLaunch.kt`（加 `tmdbRef` 字段）。
5. **改** `screen/DetailScreen.kt`（`:498` 构建处填 `tmdbRef`，旁路 `resolveMediaRef`）。
6. **改** `screen/player/PlayerScreen.kt`（fetch effect + `skipFocus` + `activeSegment` + 渲染浮层 + 自动隐藏 effect 守卫 + reseat）；**改** `screen/player/PlaybackSpeed.kt`（`SpeedPill` 加 `upTarget`）。

## 7. 风险与回退（premise collapse）

1. **（数据·主导风险）季/集映射**：`season = 后缀解析、episode = 源 index + 1` 要对得上 TMDB 的分季编号。MoonTV 源常把多季拍平成连续编号（源 1–24 = TMDB S1+S2），则算出的 season/episode 错位 → TIDB 查空或查错。**后果仅是「不显示按钮」，不破坏播放**。叠加华语内容覆盖近零 → 真正受益的是单季番剧 + 欧美剧。这是功能价值上限，定位锦上添花。
2. **（交互风险）借焦不致 d-pad 失焦**：本仓库屡被「焦点孤儿」坑（Lazy 容器困焦、面板移除失焦）。新按钮借焦/还焦若不稳，可能短暂 d-pad 失灵或焦点穿透。**回退方案**：不借焦，改在 scrubber `onKeyEvent` 里 `activeSegment != null && OK → 跳过` 拦截，焦点始终留 scrubber，反色态由 `activeSegment` 驱动而非真实焦点——零孤儿，代价是失去「可上达 / 真实焦点反色」。先按推荐（借焦）实现，真机一旦发现失焦即切回退。

## 8. 依赖

- **TMDB v3 API Key**：用户设置页已有（hero 剧照在用）。**未填 → 无 `tmdbRef` → 功能静默关闭**（与剧照同款降级）。
- TIDB / IntroDB 读取：**免鉴权**，无新增凭证。

## 9. 测试路径

**数据层（可 curl / 单测）**
- 有 key + 单季番剧（如进击的巨人）：解析出 ref → TIDB 命中 intro/credits。
- 无 key：无 ref → 空 segments（回归安全：零渲染、零行为变化）。
- 华语剧（庆余年）：TIDB/IntroDB 均空 → 空 segments。
- TIDB 命中而 IntroDB 低 confidence：不重复采纳。
- 同集第二次进入：命中缓存，不打网络。
- `parseSeasonNumber`：`第二季→2`、`Season 3→3`、`2nd Season→2`、无后缀→1。

**交互层（真机 E2E）**
- 片头（控制条隐藏）：反色预聚焦 → OK → seek 到段尾继续播。
- 回顾段：标签「跳过回顾」。
- 片尾有下一集：标签「下一集」→ `goNext()`；末集：标签「跳过片尾」→ seek 末。
- 控制条可见时：scrubber ↑ → 倍速 ↑ → 跳过键（反色）→ OK 跳过。
- 段内按方向键（隐藏态）：控制条起、焦点回 scrubber。
- 段过未按：按钮自隐、焦点 reseat 回 scrubber、d-pad 正常。
- 空 segments：零渲染、零行为变化。
- 跳过键持焦按 BACK：二次确认退出（不变）。

## 10. 回滚

纯增量：删 2 新文件 + 还原 5 处 edit（`PlayerLaunch` 字段、`DetailScreen` 填充、`TmdbService`/`Tmdb.kt` 扩展、`PlayerScreen` effect+UI、`SpeedPill.upTarget`）。无数据迁移、无 schema 变更，可无痛回滚。
