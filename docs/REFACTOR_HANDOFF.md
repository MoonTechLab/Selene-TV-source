# 代码库审查整改 · 交接文档

> 起因：`/check` 全代码库深度审查，发现 3 个安全 Hard Stop（S1–S3）、9 个高危、若干中低问题。
> 用户决策：**先修数据层(C) + 架构(A)，安全链路(S)暂时忽略后面再说**；且**所有修复不得改变原本体验**。
> A2（ViewModel 状态保留）和 A3（LazyVerticalGrid）经评估与「不改变体验」冲突/高回归风险，**用户已确认放弃**。

## 验证命令

```bash
./gradlew compileDebugKotlin testDebugUnitTest --console=plain
```

当前状态：**BUILD SUCCESSFUL，9 个单测全过**。

---

## ✅ 已完成（本次提交，8 个文件）

| 项 | 文件 | 改动要点 |
|----|------|----------|
| **C1** 搜索取消失效 | `service/SearchService.kt` | `SearchService` 改为自有 `serviceScope`，搜索 `launch` 后把 Job 存入 `_searchJob`，`stopSearch()`/重复搜索时 `cancelAndJoin()` 真正取消上一次，消除新旧结果交叉污染。`startSearch` 不再阻塞调用方 |
| **C2** 搜索并发竞态 | `service/SearchService.kt` | `_completedSources`→`AtomicInteger`，`_sourceErrors`→`ConcurrentHashMap`，`_isSearching/_currentQuery/_totalSources/_searchJob`→`@Volatile`；进度读自原子自增值。顺手修了 13s 超时的过期注释 |
| **C3** 缓存初始化竞态 | `service/DoubanService.kt`、`service/BangumiService.kt` | `cacheInitialized`→`@Volatile` + `cacheInitMutex.withLock`，并发请求下 `initCache()` 只跑一次 |
| **C4** 缓存非原子写 | `service/DoubanCacheService.kt` | `set()` 改为先写 `<file>.tmp` 再 `renameTo` 原子替换，杜绝读到半截 JSON 导致误删缓存 |
| **C5** 缓存键非法/碰撞 | `service/DoubanCacheService.kt` | 新增 `safeFileName()`：文件名用缓存键的 SHA-256，避免中文/空格/`&`/`/` 造成非法路径或键碰撞。**内存缓存仍以原始键索引**，调用点零改动 |
| **C6** Bangumi 解析崩溃 | `model/Bangumi.kt`、`service/BangumiService.kt` | `BangumiImages`/`BangumiDetails` 全字段加默认值 + `Json{ coerceInputValues=true }`，某字段 null/缺失时退化为默认而非整条详情解析失败。成功路径行为不变（已有 `BangumiDetailsParseTest` 仍过） |
| **C7** 直播源并发/抓取 | `service/LiveService.kt` | `channelsCache`→`ConcurrentHashMap`、`sourcesCache`→`@Volatile`；`fetchM3uContent` 手动跟随跨协议 301/302/303/307/308 重定向（修 http→https 源直接失败）、`readBytesCapped` 加 10MB 上限防 OOM。**编码逻辑保持原样**避免改变可见文本 |
| **A4** derivedStateOf | `screen/tab/SearchTab.kt` | `searchResults` 由 `LaunchedEffect` 写回改为 `derivedStateOf`，消除一帧旧数据与多余重组；`focusedRowIndex=0` 的副作用拆为独立 `LaunchedEffect`；删掉 3 处冗余 `searchResults = emptyList()`（已随 `allSearchResults` 派生） |
| **A5** IP 异步枚举 | `screen/tab/SettingsTab.kt` | `selectedIp` 初值留空，`NetworkInterface` 枚举移到 `LaunchedEffect` + `Dispatchers.IO`，不再阻塞组合线程；首选 192.168 网段逻辑保留 |

均为行为保持型修复：可见交互不变，仅修正并发/资源/崩溃/性能。

---

## 🚧 进行中 / 待续：A1 网格 Tab 抽公共组件

**目标**：把 `MovieTab` / `ShowTab` / `TvTab` / `AnimeTab` 四个文件（约 4000 行、近乎逐字复制）的网格骨架抽成一个泛型共享组件，消除重复。**纯重构，行为逐字保持**。
**进度**：尚未动笔（已完成全部 4 个文件的差异分析，设计如下）。

### 关键差异分析（务必遵守，否则会改变体验）

1. **布局公式不同，必须各自保留**：
   - `MovieTab.calculateGridLayout`：固定 `horizontalPadding = 58dp`，`cardGap=(屏宽-720-116)/5`。
   - `Show/Tv/Anime.calculateXxxGridLayout`：`horizontalPadding = max(20, 可用空间*15%)`，`cardGap = max(28, 可用空间*70%/5)`。
   - ⚠️ **`cardGap` 实际不影响布局**：所有 `GridRow` 用 `Arrangement.SpaceBetween`，cardGap 参数被传入但从未使用。真正影响视觉的只有 `horizontalPadding`。
2. **item 类型不同**：Movie/Show/Tv = `List<DoubanMovie>`（卡片源恒 `DOUBAN`，单一点击回调）；**AnimeTab = `List<AnimeContentItem>`**（sealed：`Bangumi(BangumiItem)` / `Douban(DoubanMovie)`，卡片源按变体，两个点击回调）。所以共享组件必须**泛型 `<T>`** + 三个映射回调。
3. **完全一致的部分**（可直接抽走）：布局常量（COLUMNS=6/CARD_WIDTH=120/CARD_HEIGHT=180/ROW_GAP=28/ROW_HEIGHT=202/PAGE_LIMIT=25/RENDER_BUFFER=3）、`isRowVisible`、`calculateScrollTarget`（行居中：topSpacer=64 + 筛选区实测高 + 行偏移，再减半屏）、`handleVerticalScroll`、`disabledBringIntoViewSpec`、`*CardSkeleton`、`*GridErrorState`、`*GridRow`（含上/下键焦点导航与翻页触发）、`*ContentSection`（窗口化 + 初始3行骨架 + 加载时尾部 1~2 行骨架）、`*FilterRow`（标签36dp+内容）、外层 `Box>CompositionLocalProvider>Column(verticalScroll+focusGroup+padding)` 脚手架、`bottomPadding=(屏高/2-67.5dp)`。
4. **各自保留**：筛选选项常量、`XxxFilterState`、`loadData`/`loadXxxData`/`loadInitialXxxData`、`XxxFilterSection`、dialog options/title/selected 映射。AnimeTab 的 `loadData` 还有 `"new"`（BangumiService 每日放送，`hasMore=false`）与 `"series"/"movie"`（DoubanService）两分支。

### 落地设计

新建 `app/src/main/java/org/moontechlab/selenetv/ui/grid/MediaGrid.kt`，`package org.moontechlab.selenetv.ui.grid`，含：

- 常量 `GRID_COLUMNS`/`GRID_CARD_WIDTH`/`GRID_CARD_HEIGHT`/`GRID_ROW_GAP`/`GRID_ROW_HEIGHT`/`GRID_PAGE_LIMIT`/`GRID_RENDER_BUFFER`
- `@Composable MediaCardSkeleton()`（= 原 `VideoCardSkeleton`）
- `@Composable MediaGridErrorState(message, onRetry)`（= 原 `GridErrorState`）
- `@Composable GridFilterRow(label, content)`（= 原 `FilterRow`）
- `private @Composable <T> MediaGridRow(items, rowIndex, isVisible, isLoading, totalRows, itemToVideoInfo, itemToSource, onVerticalNavigate, onCardFocus, onItemClick, onNavigateUp)` — 行内 `SpaceBetween` + 不满行透明 Spacer 补齐 + 骨架，逻辑照搬
- `private @Composable <T> MediaGridContent(...)` — 错误态/初始骨架/正常行+尾部骨架，照搬
- `@OptIn(ExperimentalFoundationApi) @Composable <T> MediaGridScaffold(items, isLoading, errorMessage, scrollState, horizontalPadding, cardGap, contentFocusRequester, resetKey, itemToVideoInfo, itemToSource, onItemClick, onRetry, onLoadMore, onContentNavigateUp, filterSection: @Composable ()->Unit)`：
  - 内部 own `focusedRowIndex`、`filterSectionHeight`，`rows = items.chunked(GRID_COLUMNS)`
  - `LaunchedEffect(resetKey){ focusedRowIndex = 0 }`（resetKey 传 filterState，复刻原「筛选变化重置」）
  - `onCardFocus(row)`：`focusedRowIndex=row`；`if (row==rows.lastIndex && !isLoading) onLoadMore()`（`onLoadMore` 即各 tab 的 `loadData()`，其内部已 guard `isLoading`/`hasMore`，故安全）
  - 组装 `Box > CompositionLocalProvider(LocalBringIntoViewSpec provides 禁用spec) > Column(verticalScroll+focusGroup+padding(horizontal=horizontalPadding, bottom=bottomPadding))`：64dp 顶 Spacer → 测高 Box{ filterSection() } → 14dp Spacer → MediaGridContent(...)

### 各 Tab 改造步骤（逐个改 + 每个改完 `compileDebugKotlin`）

1. 保留 `calculateXxxGridLayout` + `XxxFilterState` + `loadData`/`loadXxx*` + `XxxFilterSection`（其内部 `FilterRow` 换成共享 `GridFilterRow`）+ dialog 映射。
2. 删除该 tab 内的：布局常量、`isRowVisible`/`calculateScrollTarget`/`handleVerticalScroll`/`disabledBringIntoViewSpec`、`XxxContentSection`/`XxxGridRow`/`XxxCardSkeleton`/`XxxGridErrorState`/`FilterRow`、`focusedRowIndex`/`filterSectionHeight`/相关 px 计算。
3. Tab 主体改为：算 `gridLayout` → `MediaGridScaffold(items=dataState.xxx, isLoading=..., errorMessage=..., scrollState, horizontalPadding=gridLayout.horizontalPadding, cardGap=gridLayout.cardGap, contentFocusRequester, resetKey=filterState, itemToVideoInfo={...}, itemToSource={ VideoCardSource.DOUBAN }（Anime 按变体）, onItemClick={ onVideoSelected(it.toVideoInfo()) }, onRetry={loadData(true)}, onLoadMore={loadData()}, onContentNavigateUp={ regionFocusRequester.requestFocus() }, filterSection={ XxxFilterSection(...) })`。
   - 注意焦点接线：`XxxFilterSection` 的 `onNavigateDown` 仍 `contentFocusRequester.requestFocus()`；scaffold 的 `onContentNavigateUp` 接 region/type FocusRequester（即原 `onNavigateUp`）。
4. 建议先做 MovieTab，编译/真机验证手感后再套 Show/Tv/Anime。
5. 全部完成后跑验证命令；最好真机/模拟器跑一遍：四个 Tab 的筛选切换、上下 D-pad 行居中滚动、翻页加载、加载骨架、加载失败重试、详情返回焦点。

### 风险提示
- 项目**没有 UI/焦点测试**，行为保持只能靠人工真机验证。AVD 名 `Selene_TV`（会话中曾启动）。
- 不要引入 ViewModel（A2）或 LazyVerticalGrid（A3）——用户已明确放弃，会改变体验/有回归风险。

---

## ❌ 本次未做（按用户决策）

- **A2** ViewModel 状态保留：会改变「切 Tab 重新加载」的现有体验，放弃。
- **A3** LazyVerticalGrid：四个 Tab 的「聚焦行自动居中」是手写非默认滚动（禁用 BringIntoViewSpec + 手动 animateScrollTo 居中），换 Lazy 难在无测试下保证手感零回归，放弃。
- **安全 S1–S8（暂缓，后面再说）**，其中 3 个为 Hard Stop，建议尽快单独处理：
  - **S1** 遥控 HTTP 服务 `RemoteControlServer` 监听 `0.0.0.0:28256` 且 `/api/key`、`/api/text` **零鉴权**，App 启动即自启 → 同网段任意设备可完全控制电视。二维码里的 sessionToken 服务端从未校验。
  - **S2** `DownstreamService` 与 `SeleneApplication` 全局 `trustAllCerts` + `hostnameVerifier{true}` → 关闭 TLS 校验，中间人可篡改播放地址。
  - **S3** 密码明文存 SharedPreferences；登录请求体用字符串插值拼 JSON（注入）。
  - 其余：S4 明文 HTTP 全局放行 + 登录默认补 http、S5 token/输入文本被 Log 打印、S6 `key.jks` 入库、S7 `allowBackup=true` 可导出明文凭据、S8 搜索 base URL 无白名单(SSRF)。
