# 影视详情页设计

**日期**：2026-05-22
**范围**：新增影视详情页 + 把现有 HomeScreen/LoginScreen 接入 Navigation Compose

## 目标

为豆瓣卡片（HomeTab 热门列表 + Movie/Tv/Anime/Show Tab 网格）增加点击入口，进入一个独立全屏的影视详情页，统一以豆瓣为元数据源，并从搜索流里聚合该作品在各源的可播放信息。

本次同时把现有 HomeScreen、LoginScreen 迁移到 Navigation Compose，为后续多层覆盖页（详情链式跳转、直播详情、设置子页面）提供正确的 back stack 语义。

## 范围

**在范围内**：
- 新增 `androidx.navigation:navigation-compose` 依赖与类型化路由
- `MainActivity` 顶层结构改为 `NavHost`
- `HomeScreen` 去除 LoginScreen 覆盖状态，改为 navigate 跳转
- `LoginScreen` 去除内置 visible/animateIn 状态，进出动画由 NavHost 提供
- 新增 `DetailScreen` 路由与实现：Hero + 播放源行 + 剧集选择对话框 + 月牙加载页
- 豆瓣 `VideoCardSource.DOUBAN` 卡片接入 `navigate(DetailRoute)`

**不在范围内**：
- 真实视频播放器接入（点「播放」/「继续播放」/某一集 → 当前仅 `Log.d`）
- 同类推荐区块
- 搜索/收藏/播放记录卡片的详情入口（仅豆瓣卡片本轮接入）
- `SearchService` 是否走 `SearchCacheService` 的优化
- 通过遥控服务把 m3u8 推到手机端

## 第 1 部分：Navigation 迁移

### 依赖

在 `gradle/libs.versions.toml` 增加：
```toml
[versions]
navigationCompose = "2.8.5"   # 与 Compose BOM 2024.09 + Kotlin 2.2 + AGP 9.1 兼容

[libraries]
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
```

在 `app/build.gradle.kts` 的 `dependencies` 块添加 `implementation(libs.androidx.navigation.compose)`。

项目已带 `kotlin("plugin.serialization")` 与 `kotlinx-serialization-json`，类型化路由无需额外配置。ProGuard 的 `kotlinx.serialization` 通配规则已覆盖。

### 路由定义（`screen/Routes.kt` 新建）

```kotlin
@Serializable data object HomeRoute
@Serializable data object LoginRoute
@Serializable data class DetailRoute(
    val doubanId: String,
    val title: String,
    val poster: String,
    val year: String,
)
```

`title`、`poster`、`year` 是兜底字段：`title` 在加载页期间显示给用户；`poster` 供月牙加载页与卡片缩略图衔接；`year` 用于源筛选的弱匹配兜底。豆瓣详情拉回后用 `details.title` 等覆盖显示。

### MainActivity

```kotlin
setContent {
    val navController = rememberNavController()
    AppNavHost(navController)
}
```

`adjustDensity` / `refreshLoginCookieIfNeeded` / `RemoteControlServer` 回调等不变。

### AppNavHost（`screen/AppNavHost.kt` 新建）

`NavHost(startDestination = HomeRoute)` 内三条 `composable<...>`：

- `HomeRoute` → `HomeScreen(navController)`
- `LoginRoute` → `LoginScreen(navController)`
- `DetailRoute` → `DetailScreen(navController, route = backStackEntry.toRoute<DetailRoute>())`

每条路由各自的进出动画统一为「淡入 + 0.95 → 1.0 缩放」的 spring（与现 LoginScreen 内的动效节奏对齐：`dampingRatio = 0.8f, stiffness = 400f` 入；`0.9f, 500f` 出）。HomeRoute 互相 pop 时不带动画。

### HomeScreen 改造

删除以下状态：
- `showLoginScreen: Boolean`
- `loginInitialServerUrl`, `loginInitialUsername`
- `settingsRefreshKey: Int`（替换为新机制，见下）

新增 `homeRefreshKey: Int`，通过监听 `navController.currentBackStackEntry` 在回到 home 时自增，触发对应 Tab 的数据重拉。SettingsTab 用 `key(homeRefreshKey) { ... }` 包裹；其余 Tab 在 `LaunchedEffect(homeRefreshKey) { reload() }` 中重新拉数据。

`SettingsTab.onShowLoginScreen` 改为 `{ navController.navigate(LoginRoute) }`。

豆瓣卡片接线：HomeTab / MovieTab / TvTab / AnimeTab / ShowTab 中 `onVideoClick`/`onMovieClick` 改为：

```kotlin
{ video ->
    val doubanId = video.doubanId ?: return@VideoCard  // 没有豆瓣 ID 的卡片本轮不接
    navController.navigate(DetailRoute(
        doubanId = doubanId,
        title = video.title,
        poster = video.cover,
        year = video.year,
    ))
}
```

返回键链路保持现状：焦点不在 TabSwitcher 时滚顶 + 回收焦点；TabSwitcher + 非 home → 切回 home；home 上 → `(context as Activity).finish()`。

### LoginScreen 改造

签名变为：
```kotlin
@Composable
fun LoginScreen(navController: NavController)
```

内部初始数据从 `AppDataService.getServerUrl()/getUsername()` 直接读（移除 `initialServerUrl`/`initialUsername` 参数）。`onDismiss` 改为 `navController.popBackStack()`。

删除以下内部状态：
- `visible: Boolean` 参数
- `showScreen: Boolean`
- `animateIn: Boolean`
- `LaunchedEffect(visible)` 块
- `LoginScreenWithAnimation` 包装层（动画交给 NavHost 的 `enterTransition`/`exitTransition`）

`BackHandler(enabled = true) { onDismiss() }` 改为 `BackHandler { navController.popBackStack() }`（语义不变）。

登录成功后调 `AppDataService.saveUserData(...)` 后 `popBackStack()`，HomeScreen 的 `homeRefreshKey` 自动让 SettingsTab 刷新。

## 第 2 部分：DetailScreen 数据层

### UI 状态

```kotlin
data class DetailUiState(
    val route: DetailRoute,
    val details: DoubanMovieDetails? = null,
    val detailsLoading: Boolean = true,
    val detailsError: String? = null,
    val sources: List<SearchResult> = emptyList(),
    val sourcesProgress: SearchProgress? = null,
    val sourcesComplete: Boolean = false,
    val selectedSourceKey: String? = null,        // "$source+$id"
    val playRecords: Map<String, PlayRecord> = emptyMap(),
    val isFavorite: Boolean = false,
    val episodePickerVisible: Boolean = false,
)
```

状态用 `mutableStateOf` + `remember`（与现有 Tab 一致，不引入 ViewModel）。

### 并发加载（`LaunchedEffect(route.doubanId)`）

四件事并行：

1. **豆瓣详情**：`doubanService.getDoubanDetails(doubanId)` → 写 `details` / `detailsError`，置 `detailsLoading = false`
2. **聚合源搜索**：
   - 调 `SearchService.startSearch(route.title)`，同时 `SearchService.eventFlow.collect`
   - 每条增量 `SearchResult` 经过以下筛选：
     - **强匹配**：`result.doubanId?.toString() == route.doubanId` → 通过
     - **弱匹配兜底**：`result.doubanId == null && result.title == route.title && (result.year == route.year || 任一为空)` → 通过
     - **明确不匹配**：`result.doubanId != null && result.doubanId.toString() != route.doubanId` → 丢弃
   - 通过的 result 再过：`ContentFilterService.shouldFilter(result.typeName)` 命中丢弃
   - 通过的 result 再过：`SearchService.deduplicateResults` 去重
   - 累计到 `sources`，`SearchEvent.Progress` 写入 `sourcesProgress`，`SearchEvent.Complete` 置 `sourcesComplete = true`
3. **播放记录**：`ApiService.getPlayRecords()` → `Map<sourceKey, PlayRecord>` 写入 `playRecords`
4. **收藏状态**：`ApiService.getFavorites()` → 检查 `route.doubanId` 是否在列表，写入 `isFavorite`

四件事失败互不影响（任一可独立失败/重试）。

### 默认选中源

每次 `sources` 增长时计算一次（用 `LaunchedEffect(sources)`）：
1. `selectedSourceKey != null && key 仍在 sources 中` → 不动
2. 否则：若 `sources` 中任一在 `playRecords` 中存在 → 选 `saveTime` 最大的
3. 否则：选 `sources[0]`
4. `sources` 空 → `selectedSourceKey = null`

### 加载页（月牙）显示规则

- `detailsLoading == true && entered ≥ 150ms` → 显示月牙覆盖整页
- `detailsLoading == false` → 月牙淡出 200ms，正式 Hero 同步淡入
- `sources` 是独立流，与月牙无关。Hero 已就绪、源列表仍在加载 → 「播放源」区块内部显示骨架 + 进度
- **月牙实现**：`Canvas(Modifier.size(64.dp))`
  - `Path` 描出月牙形状（外半径 45 的半圆 - 内半径 32 的椭圆半弧）
  - `rememberInfiniteTransition` + `animateFloat` 0→360°，6s `LinearEasing`，应用到 `Modifier.graphicsLayer { rotationZ = it }`
  - 另一组 `animateFloat` 0→1→0，3s `EaseInOut`，驱动 `Modifier.drawWithCache` 中的柔光半径（8dp→20dp 投影）
  - 下方两行：`route.title`（16sp W600 opacity 0.9）+ `"SELENE · LOADING"`（10sp opacity 0.4 letterSpacing 2.5sp）

### 错误态

- `detailsError != null` && `details == null`：Hero 区域显示「加载失败」+ 重试按钮（沿用 `MovieTab.GridErrorState` 样式）。播放源链路不受影响
- `sourcesComplete == true && sources.isEmpty()`：「播放源」区显示「没有可用的播放源」（11sp opacity 0.5）
- 单源搜索错误：内部由 `SearchService.sourceErrors` 收集，详情页不单独展示（只汇总进度）

### 缓存

- `DoubanService.getDoubanDetails` 已带 3 天磁盘缓存（无需改）
- `ApiService.getPlayRecords` / `getFavorites` 每次进入重拉（保证刚关播放器进入时状态新鲜）
- `SearchService` 的缓存状况在本次范围外，不优化

### 状态回写（详情 → 列表）

详情页中的收藏变更、播放记录删除 → 返回 HomeScreen 时通过 `homeRefreshKey` 机制让对应 Tab 重拉。详情页本身的 favorite / playRecord 状态在操作时乐观更新，API 失败回滚 + Toast 提示。

## 第 3 部分：DetailScreen UI 与行为

### 入口签名

```kotlin
@Composable
fun DetailScreen(navController: NavController, route: DetailRoute)
```

### 整体结构

```
Box(fillMaxSize, gradientBackground)
  ├─ if (detailsLoading): MoonLoadingOverlay(route.title)
  └─ AnimatedVisibility(visible = !detailsLoading, fade 200ms):
       Column(verticalScroll):
         ├─ Hero(details, route, ...)
         └─ SourcesSection(sources, selectedSourceKey, ...)
  └─ EpisodePickerDialog(visible = episodePickerVisible, ...)
```

背景沿用 HomeScreen 同款渐变 `#0a0a0a → #1a1a1a → #0f0f0f → #050505`。

### Hero 布局（与参考截图 1:1）

`Row(modifier = padding(start = 58.dp, top = 60.dp, end = 48.dp, bottom = 40.dp))`：

**左列**（`Column(Modifier.weight(1f).padding(end = 36.dp))`）：

| 元素 | 字号 | 颜色/透明度 | 说明 |
|---|---|---|---|
| 标题 | 32sp W700 | 白 | `details.title` |
| chips + rating | 11sp / 14sp | 白 0.85 / 粉#E91E63 W700 | genres 横向 spacedBy 8dp 后接「★ ${rate}」 |
| sub 行 | 11sp | 白 0.55 | `${releaseDate(country)} · ${countries.join("/")} · ${duration}`，缺失字段跳过对应段，分隔符不重复 |
| 简介 | 12sp | 白 0.75 | line-height 1.6，**完整显示**（不截断） |
| 动作按钮行 | — | — | 见下 |

行间距：标题→chips 8dp；chips→sub 8dp；sub→简介 14dp；简介→动作行 24dp。

**右列**：海报，`Modifier.size(width = 200.dp, height = 300.dp)`，`clip(RoundedCornerShape(8.dp))`，`shadow(elevation = 16.dp, ambientColor = Color.Black.copy(alpha = 0.5f))`。`AsyncImage(model = getImageUrlSync(details.poster ?: route.poster))`。

### 动作按钮行

`Row(horizontalArrangement = spacedBy(10.dp), verticalAlignment = CenterVertically)`：

| 槽位 | 显示条件 | 样式 | 点击行为 |
|---|---|---|---|
| **继续播放** | `selectedSource != null && playRecords[key] != null` | 白底深字，38dp 高，圆角 19dp，padding 28dp h，13sp W600，▶ 图标 | `Log.d("Detail", "continue: ep=${record.index} pos=${record.playTime}")` |
| **播放** | `selectedSource != null` | 半透明白底白字（0.15 alpha），同尺寸，13sp W600，▶ 图标 | 有 record → 同「继续播放」；无 record && totalEpisodes>1 → 弹剧集选择对话框；totalEpisodes==1 → log 直接播放 |
| **🗑** | `playRecords[key] != null` | 38×38 圆形按钮，0.12 alpha，trash 图标 | 调 `ApiService.deletePlayRecord(source, id)`（若不存在该方法则在实现阶段补），成功后乐观清除 `playRecords[key]`，Toast「已删除播放记录」，焦点落到 [播放] |
| **♡/♥** | `details != null`（与 doubanId 绑定，与选中源无关） | 38×38 圆形按钮，0.12 alpha，heart-outline / heart-filled 图标 | toggle `isFavorite`；调 `ApiService.addFavorite` 或 `removeFavorite`，乐观更新失败回滚 |
| **当前源标签** | `selectedSource != null` | 右靠齐，半透明 chip（11sp W500 opacity 0.7 + 圆角 14dp + 内边距 12×6 + 边框 0.5dp alpha 0.3），**不可聚焦** | 仅展示 `selectedSource.sourceName` |

`selectedSourceKey == null`（搜索完成但 sources 为空）时，动作按钮行仍渲染：仅 `♡` 可见；左侧三个槽位与当前源标签不显示。

**焦点表现**：所有按钮统一用 `androidx.tv.material3.Surface`；focused → 1.05 scale + 0.6 damping spring + 白色边框（沿用 VideoCard / SettingsTab 现有约定）。白底按钮 focused 时保持白底。

**初始焦点**：`detailsLoading` 变 false + 200ms cross-fade 完成后请求焦点。优先级降级：[继续播放] > [播放] > [♡]。源尚未到达时只能落 [♡]，源到位后**不**自动重抢焦点（避免抢走用户已建立的焦点位置）。

### 「播放源」区

```kotlin
Column(Modifier.padding(top = 16.dp)):
  Row(start = 58.dp, end = 58.dp, alignment = Bottom, spacing = 12.dp):
    Text("播放源", 18sp W700)
    if (!sourcesComplete) Text("${progress.completedSources}/${progress.totalSources}", 12sp opacity .5)
  Spacer(8.dp)
  LazyRow(spacedBy 20.dp, contentPadding = 58.dp h, focusGroup, focusRestorer):
    if (sources.isEmpty() && !sourcesComplete) → 6 张 SkeletonVideoCard（沿用现有）
    if (sources.isEmpty() && sourcesComplete) → Text("没有可用的播放源", 11sp opacity .5)
    else → sources.forEach { result -> SourceCard(...) }
```

**SourceCard** 用 `VideoCard` LARGE 直接复用：

```kotlin
VideoCard(
    videoInfo = VideoInfo(
        id = result.id,
        source = result.source,
        title = result.sourceName,       // 卡片标题 = 源名（而非内容名）
        sourceName = result.sourceName,
        year = result.year,
        cover = result.poster,
        index = playRecords["${result.source}+${result.id}"]?.index ?: 0,
        totalEpisodes = result.episodes.size.coerceAtLeast(1),
        playTime = 0, totalTime = 0,
        saveTime = 0,
        searchTitle = result.title,
        doubanId = result.doubanId?.toString(),
    ),
    from = VideoCardSource.SOURCE,
    onClick = { selectedSourceKey = "${result.source}+${result.id}" },
    isSelected = (selectedSourceKey == "${result.source}+${result.id}"),
)
```

VideoCard 的现有逻辑天然吃到：
- 当 `index > 0` → 集数徽章显示 `${index}/${totalEpisodes}`（即 `15/35`）；否则 `${totalEpisodes}`
- `isSelected` → 左上角绿色 ✓
- 来源标签 → 卡片底部小字「茅台资源」（VideoCardSource.SOURCE 已开启该标签）

**点击源卡片**：仅切换 `selectedSourceKey`；Hero 动作按钮基于新选源 reactive 更新；焦点留在卡片，用户可继续左右浏览或上键回到动作按钮。

### 剧集选择对话框

触发条件：`selectedSource && playRecords[key] == null && episodes.size > 1 && 点击 [播放]`。

```kotlin
EpisodePickerDialog(
    visible = episodePickerVisible,
    sourceName = selectedSource.sourceName,
    episodes = selectedSource.episodes,
    episodeTitles = selectedSource.episodesTitles,
    onSelect = { idx ->
        Log.d("Detail", "play: source=${selectedSource.source} ep=$idx")
        episodePickerVisible = false
    },
    onDismiss = { episodePickerVisible = false },
)
```

样式：复用 `FilterDialog` 的玻璃容器（半透明黑底 + 模糊 + 圆角 16dp + padding 32dp）。

内容：
- 标题：`选择剧集 · ${sourceName}`（18sp W700）
- `LazyVerticalGrid(columns = Fixed(8), spacedBy 12dp)`
- 每格胶囊：`Surface` 圆角 8dp，padding 14×8dp，文案 `episodeTitles?.getOrNull(i) ?: "第 ${i+1} 集"`（11sp W500）
- 焦点：进入时初始落在第 1 集；focused 时 1.05 scale + 白底深字
- 返回键关闭

### 焦点全局规则

| 按键 | Hero 动作行 | 播放源 LazyRow | 剧集对话框 |
|---|---|---|---|
| ↑ | 不响应（顶部） | 上跳到 Hero 动作行 | 不响应（顶部） |
| ↓ | 下跳到播放源 | 不响应（底部）| 不响应（底部）|
| ←/→ | 行内左右切按钮 | 行内左右滚卡片 | 网格内左右切剧集 |
| Enter | 触发按钮行为 | 切换选中源 | 选剧集 + 关闭对话框 |
| Back | `popBackStack()` | `popBackStack()` | 关闭对话框 |

### 渐变背景与字号约束

- 整页背景沿用 HomeScreen 同款 verticalGradient
- 最小字号 10sp（剧集胶囊和 caption），其余 ≥ 11sp（符合 CLAUDE.md 「最小 11sp」可读性约束，10sp 仅用于不重要的辅助文本）
- 主题强调色 `#4CAF50` 用在选中标记 + 加载完成的隐式确认；`#E91E63` 用在评分星

## 开放项 / 后续工作

- 真实播放器接入：定义 `PlayCoordinator` 接口，把 Log.d 替换为真实 dispatch（启动 Activity / 推送遥控）
- 同类推荐区块（DoubanMovieDetails.recommends）
- 搜索/收藏/播放记录卡片接入详情页（凡 `VideoInfo.doubanId != null` 的卡片）
- `SearchService` 缓存优化（详情页反复进出时避免重复搜索）
- Navigation 转场动画微调（首版用通用淡入缩放，后续可按目的地差异化）
- Hero 的「当前源标签」可考虑改为可聚焦下拉，与「播放源」LazyRow 互补（本轮不做）
