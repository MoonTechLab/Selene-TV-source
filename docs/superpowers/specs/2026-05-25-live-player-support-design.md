# 直播播放页 + 直播页跳转对接 设计

日期：2026-05-25
状态：已确认设计，待评审进入实现计划

## 背景

应用已有完整的点播（VOD）播放链路：`PlayerScreen`（Compose 外壳）+ `PlayerController`（封装 ExoPlayer）+ `MediaHttp`（信任所有证书的 OkHttp DataSource）+ `PlayProgressReporter`（进度保存）。

直播侧目前只有浏览：`LiveTab` 拉取直播源（`LiveSource`）与频道（`LiveChannel`），分组展示频道卡片。`LiveTab` 已暴露 `onChannelClick: (LiveChannel) -> Unit`，但 `HomeScreen` 调用时**未传该回调**，因此点击频道无任何反应——直播播放完全未接通。

本次目标：新增直播播放页并把直播页的频道点击接通到它。

参考实现：Flutter `../mobile/lib/screens/live_player_screen.dart`（含频道列表、切台、EPG、源 UA）。

### 直播与点播的关键差异

| 维度 | 点播 VOD | 直播 LIVE |
|---|---|---|
| 时长/进度 | 有，进度条可 seek | 无（连续流，duration=TIME_UNSET） |
| 进度保存 | 10s/暂停/退出 写 `/api/playrecords` | 无 |
| 续看 | 有 | 无 |
| 列表 | 选集 + 换源 | 分组频道列表 |
| HTTP 头 | 默认 | 常需源的 UA（如 `AptvPlayer/1.4.10`） |
| 自动下一集 | 有（STATE_ENDED） | 无 |

## 已确认的决策

1. **架构**：新建独立 `LivePlayerScreen`，复用 `PlayerController` + `MediaHttp` 播放引擎；控制层（频道切换/面板/LIVE 标识）独立。不污染已 400+ 行、焦点逻辑复杂的 VOD `PlayerScreen`。
2. **EPG**：v1 不做。
3. **切频道**：只做「唤出分组频道列表面板」选台，不做上下键直切。
4. **UA 注入**：给 `PlayerController` 构造加可选 `dataSourceFactory` 参数（默认即现有 `MediaHttp.dataSourceFactory`），`MediaHttp` 加 `dataSourceFactory(ua)` 构造器。直播用 UA 工厂构造 controller，**VOD 路径不变**。
5. **频道数据传递**：在跳转参数里携带（`LiveTab` 内存已有 `channelGroups`/`currentSource`），不重新拉取。
6. **无进度保存/续看**：直播不写 `/api/playrecords`。

## 架构与新增/改动组件

### `screen/player/LiveLaunch.kt`（新）

内存覆盖层参数（对标 `PlayerLaunch`）：

```kotlin
data class LiveLaunch(
    val channel: LiveChannel,                 // 起播频道
    val source: LiveSource,                   // 提供 UA + 归属
    val groups: List<LiveChannelGroup>,       // 分组频道列表（面板用）
)
```

### `screen/player/LivePlayerScreen.kt`（新）

直播播放外壳：
- `AndroidView { PlayerView(useController=false, player=controller.exo) }` 全屏画面，模糊底图作缓冲背景。
- 极简 OSD：频道名 + `● LIVE`（绿点 `#4CAF50`）徽标；播放中静止 4s 自动隐藏（复用 VOD 节奏）。
- 缓冲转圈（`controller.isBuffering`）、错误横幅（`controller.error != null`）覆盖层。
- 下键唤出 `LiveChannelPanel`；双层 BackHandler（先关面板，再退出覆盖层）。
- 首帧 `controller.load(channel.url, 0)`；选台 `controller.load(newChannel.url, 0)` + 更新当前频道 + 关面板。
- 周期 `controller.syncProgress()` 仅用于驱动 isPlaying/buffering 状态（直播无进度条）。
- `DisposableEffect` 退出释放 controller。

### `screen/player/LiveChannelPanel.kt`（新）

分组频道列表面板（沿用 `PlayerPanel` 玻璃视觉与焦点约定）：
- 顶部分组 tab（左右键切组，焦点即选中）。
- 频道网格/横滚列表；当前频道绿框 + 「● 在看」。
- 上键回到 OSD（面板自动收起），选台回调 `onPickChannel(LiveChannel)`。

### `screen/player/PlayerController.kt`（改）

- 构造加 `dataSourceFactory: DataSource.Factory = MediaHttp.dataSourceFactory`，用于 `ExoPlayer.Builder(...).setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))`。VOD 默认值不变。
- 其余不动：直播下 `durationMs` 恒 0（`syncProgress`/READY 分支已对 `d>0` 保护），`onEnded` 不绑定。

### `service/MediaHttp.kt`（改）

```kotlin
private const val DEFAULT_LIVE_UA = "AptvPlayer/1.4.10"
fun dataSourceFactory(userAgent: String): DataSource.Factory =
    OkHttpDataSource.Factory(client).setUserAgent(userAgent.ifBlank { DEFAULT_LIVE_UA })
```

复用同一信任所有证书的 `client`。UA 空时回退 `AptvPlayer/1.4.10`（与 `LiveService.fetchM3uContent` 一致）。

### `screen/HomeScreen.kt`（改）

- 新增 `var liveLaunch by remember { mutableStateOf<LiveLaunch?>(null) }`。
- `LiveTab(... onChannelClick = { liveLaunch = it })`。
- 在 `TabSwitcher`/内容之上、以全屏覆盖方式绘制 `LivePlayerScreen(launch = it, onExit = { liveLaunch = null })`（参考 VOD 在 DetailScreen 的覆盖层做法：`AnimatedVisibility` + 退出后把焦点交回 `liveFocusRequester`）。

### `screen/tab/LiveTab.kt`（改）

- `onChannelClick` 签名改为回传 `LiveLaunch`（`(LiveLaunch) -> Unit`）；在频道卡 `onClick` 处用当前 `currentSource` + `channelGroups` 组装 `LiveLaunch(channel, currentSource, channelGroups)`。
- 若 `currentSource` 为空（理论上有频道就有源）防御性跳过。

## 跳转与数据流

```
LiveTab 频道卡点击
  → 组装 LiveLaunch(channel, currentSource, channelGroups)
  → HomeScreen.liveLaunch = it
  → 绘制 LivePlayerScreen 覆盖层
  → remember { PlayerController(context, MediaHttp.dataSourceFactory(source.ua)) }
  → 首帧 controller.load(channel.url, 0)
频道面板选台 → controller.load(newChannel.url, 0) + currentChannel=新台 + 关面板
BACK：面板开 → 关面板；否则 onExit() → liveLaunch=null → 焦点回 LiveTab
```

无任何网络写入（不调 `/api/playrecords`）。频道 URL 直接交给 ExoPlayer（HLS/TS 由 `DefaultMediaSourceFactory` 自动识别）。

## 直播控制 UX

- 全屏画面；OSD = 频道名 + `● LIVE`。
- OK：播放/暂停。暂停恢复时 `seekToDefaultPosition()` 跳回直播边缘（避免落后）。
- 下键：唤出分组频道面板。
- 无进度条、无选集、无换源、无自动下一集。

## 错误与缓冲

- `controller.isBuffering` → 居中 `CircularProgressIndicator`（复用 VOD）。
- `controller.error != null` → 顶部横幅「频道播放失败，请选择其他频道」+ 自动唤出频道面板（对标 VOD 死链→换源）。无静默重试。

## 不在本次范围（YAGNI）

- EPG / 节目单。
- 上下键直切频道。
- 直播源（m3u 提供方）在播放页内切换（只切当前源的频道）。
- 「上次观看频道」持久化、收藏频道。
- 倍速、清晰度、画中画、投屏。

## 验证要点

- `assembleDebug` / `assembleRelease`(R8) 通过。
- `MediaHttp.dataSourceFactory(ua)` 的 UA 回退单测通过。
- 真机：直播页点频道进入播放、画面正常；切频道生效；死链频道弹横幅 + 频道面板；带 UA 的源能播；退出焦点回直播页。
