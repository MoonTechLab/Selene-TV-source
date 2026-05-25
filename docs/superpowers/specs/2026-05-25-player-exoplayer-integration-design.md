# 播放器接入 ExoPlayer + 切源 / 切集 / 定时进度保存 设计

日期：2026-05-25
状态：已确认设计，待评审进入实现计划

## 背景

`screen/player/` 下的 `PlayerScreen` 目前是 UI 外壳：舞台为 `StagePlaceholder` 占位，进度由 `LaunchedEffect` 模拟推进。所有控制层钩子（`positionMs`/`durationMs`/`isPlaying`、`onSeek`、`onToggle`、`onPickEpisode`、`onPickSource`、换源面板、OSD、BackHandler）均已就位。本次目标是把空壳接上真实播放器，并补齐切源、切集、定时进度保存。

参考实现：Flutter `../mobile`（`lib/screens/player_screen.dart` 等）。mobile 用 `media_kit`；TV 端改用 Media3/ExoPlayer。

### 已存在、本次无需新建的基础设施

- `model/SearchResult.kt`：`episodes` 为**可直接播放的 URL 列表**（m3u8/mp4），无需二次解析。
- `service/ApiService.kt`：`getPlayRecords()` / `savePlayRecord(PlayRecord)` / `deletePlayRecord(source, id)` 已实现，对应 MoonTV `/api/playrecords`。key = `source+id`；`play_time`/`total_time` 单位为**秒**。方法为阻塞式，需在 `Dispatchers.IO` 调用。
- `service/AppDataService.kt`：`getM3u8ProxyUrl()` 已存在。
- `screen/DetailScreen.kt`：已读取播放记录并把续看位置经 `PlayerLaunch.startPositionMs`（毫秒）/`durationMs` 传入播放页（记录的秒值已 ×1000）。

## 已确认的行为决策

1. **切源**：沿用 mobile —— 带当前进度续播（新源集数足够时保持集号与位置）。
2. **结束**：一集播完自动播放下一集；最后一集则停止。
3. **加载失败**：提示用户 + 自动唤出换源面板，由用户改选其他源。不做静默重试。
4. **DataSource**：使用与 ImageLoader / `SearchService` 同姿态的「信任所有证书」OkHttp 客户端（适配用户自签名/自托管视频源）。
5. **架构**：采用独立 `PlayerController` 持有类方案（非内联、非 ViewModel），与项目「Compose state + object 服务」约定一致。

## 架构与新增组件

### 依赖（`gradle/libs.versions.toml` + `app/build.gradle.kts`）

新增 Media3：
- `androidx.media3:media3-exoplayer`
- `androidx.media3:media3-exoplayer-hls`（剧集为 `.m3u8`）
- `androidx.media3:media3-ui`（`PlayerView`）
- `androidx.media3:media3-datasource-okhttp`

版本在实现计划中固定一个与 AGP 9.1 / compileSdk 36 / minSdk 23 兼容的近期稳定版。Release 走 R8，如有需要在 `proguard-rules.pro` 补 keep（Media3 一般无需，OkHttp datasource 同理；实现时验证 release 构建）。

### `MediaHttp.kt`（新增）

- 提供共享的「信任所有证书 + 跳过 hostname 校验」`OkHttpClient`（复用或对齐现有 trust-all 客户端的构造）。
- 包成 `OkHttpDataSource.Factory`，供 ExoPlayer `DefaultMediaSourceFactory` 使用。

### `screen/player/PlayerController.kt`（新增）

封装 `ExoPlayer`：
- 暴露为 Compose `State`：`positionMs`、`durationMs`、`bufferedMs`、`isPlaying`、`isBuffering`、`error`。
- 方法：`load(url, startMs, playWhenReady = true)`、`seekTo(ms)`、`seekBy(deltaMs)`、`togglePlay()`、`release()`。
- `load` 内通过 `AppDataService.getM3u8ProxyUrl()` 拼接最终 URL：proxy 非空时 `proxy + URLEncoder.encode(rawUrl)`，否则用原始 URL。
- 监听 `Player.Listener`：
  - `STATE_ENDED` → 回调宿主触发自动下一集。
  - `onPlayerError` → 置 `error` 态并回调宿主。
  - 位置/时长/播放态变化 → 刷新对应 `State`（位置用周期轮询或 `onEvents` 拉取 `currentPosition`）。

### `screen/player/PlayProgressReporter.kt`（新增）

- 输入：当前播放快照提供者（position/duration + 当前 source/episode 元信息）。
- 在 `Dispatchers.IO` 调用 `ApiService.savePlayRecord(...)`。
- 节奏：播放中每 10s。
- 守卫：`position < 1s`、距上次保存 `< 10s`、或位置未变 → 跳过；`force = true` 时无视守卫。

## PlayerScreen 接线改造（UI / 焦点模型不变）

- 用 `AndroidView { PlayerView(useController = false) }` 替换 `StagePlaceholder`；保留模糊海报背景作为缓冲/占位层垫在画面下方。
- 删除模拟推进的 `LaunchedEffect`；`positionMs`/`durationMs`/`isPlaying` 改为读 `PlayerController` 的 state。
- `onSeek` → `controller.seekTo`；`onToggle` → `controller.togglePlay`；进度条、面板、OSD、双层 BackHandler 全部保持原样。
- `onPickEpisode(idx)` → 切集后 `controller.load(该集 URL, startMs = 该集续看位置, playWhenReady = true)`。
- `onPickSource(key)` → 带进度：先取当前 `positionMs`，切源后 `controller.load(新源对应集 URL, startMs = 带过去的位置)`。
- 由 `controller.isBuffering` 驱动缓冲转圈覆盖层。
- `onPlayerError` → 暂停进度上报；居中提示「此源播放失败，请尝试换源」；`panelOpen = true; panelTab = PanelTab.Sources`。
- `STATE_ENDED` → 若 `hasNext` 则等价于 `goNext()`（强制保存后切下一集）；否则停在结尾。

## 数据流：切集 / 切源 / 进度

- **URL 解析**：`currentSource.episodes[index]` → 可选 m3u8-proxy 包装 → `OkHttpDataSource`。播放器内不发额外网络请求。
- **进入时续看**：由 `PlayerLaunch.startPositionMs` 提供，首次 `load` 时 seek。
- **逐集续看**：切集默认从 0 开始；若该 source+id 有播放记录且 `index` 与目标集吻合，则恢复其位置（记录按 source+id 存储，实际只恢复该源最近一集，与 mobile 一致）。
- **PlayRecord 字段映射**（全部可从当前 `SearchResult` + `heroInfo` 推出）：
  - `title` ← 标题
  - `source` ← `currentSource.source`
  - `id` ← `currentSource.id`
  - `source_name` ← `currentSource.sourceName`
  - `year` ← `currentSource.year`
  - `cover` ← `currentSource.poster`
  - `index` ← `currentEpisode + 1`（0 基转 1 基）
  - `total_episodes` ← `episodes.size`
  - `play_time` ← `positionMs / 1000`（秒）
  - `total_time` ← `durationMs / 1000`（秒）
  - `save_time` ← `System.currentTimeMillis()`（毫秒）
  - `search_title` ← 标题

## 进度保存时机与生命周期

- **定时**：播放中每 10s 保存。
- **强制保存**：暂停、切集、切源、`STATE_ENDED`/自动下一集、退出播放页（`DisposableEffect` 清理）、App 退到后台（`Lifecycle.Event.ON_PAUSE` 观察者）。
- **切源记录清洁**：与 mobile 一致，切源时删除旧源记录（`ApiService.deletePlayRecord(oldSource, oldId)`），进度带到新源继续，新源进度随后由定时/退出保存写入。避免同一内容在「继续观看」残留多条。

## 错误处理

- `onPlayerError`：停止进度上报 → 居中提示 → 自动唤出换源面板（Sources tab）。无静默重试。

## 不在本次范围（YAGNI）

- 倍速、清晰度切换、音轨/字幕选择。
- 弹幕、投屏（DLNA）。
- 广告过滤 / m3u8 片段级处理（仅做 proxy URL 包装）。
- 「偏好源」持久化。

## 验证要点

- Debug 与 Release（R8）均能构建并播放 m3u8。
- 续看位置准确恢复；切源带进度；切集从 0（或该源已看位置）。
- 10s 定时保存生效；暂停/切换/退出/后台均触发保存（可通过 `/api/playrecords` 记录或日志验证）。
- 死链触发提示 + 自动换源面板。
