# 画面比例按钮（点播播放器）设计

## 目标

在点播播放器 `PlayerScreen` 的倍速胶囊旁增加一个「画面比例」按钮，让用户在遇到黑边过宽、画面被裁、或源比例标错时快速调整画面缩放方式。

## 选项调研结论

常见 App 的画面比例选项跨度很大：

| App | 选项 |
|---|---|
| Netflix / YouTube / Apple TV | 2 态：适应(Fit) ↔ 铺满裁剪(Zoom) |
| 爱奇艺 / 腾讯 / B站 TV | 默认、铺满(裁剪)、16:9、4:3（部分加「拉伸」） |
| Infuse / IINA | Fit / Fill / Original + 16:9 / 4:3 / 2.35:1 |
| VLC / MX Player | 默认、适应、裁剪、拉伸、16:9、4:3、16:10、2.21/2.35/2.39:1、1:1、原始 |

**取舍**：FIT/ZOOM/FILL（缩放模式）在 ExoPlayer 与 MPV 两后端都只需改一行/下发属性，简单可靠；强制固定比例（16:9/4:3）要对抗 ExoPlayer `PlayerView` 的自动宽高比，成本高且现代流基本自带正确比例元数据。结合「premium restraint」设计原则，**采用 3 档缩放**：

- **默认**（FIT）— 保持比例，留黑边
- **铺满**（ZOOM）— 保持比例，裁剪填满屏幕
- **拉伸**（FILL）— 变形填满屏幕

## 实现设计

### 1. 接口层 — `VideoPlayer` 加缩放属性

```kotlin
enum class VideoResizeMode { FIT, ZOOM, FILL }   // 适应 / 裁剪铺满 / 拉伸

interface VideoPlayer {
    /** 画面缩放模式。直播实现可忽略，默认 FIT。 */
    var resizeMode: VideoResizeMode
    // ...既有成员
}
```

- **ExoPlayer (`PlayerController`)**：
  - `VideoSurface` 的 `AndroidView.factory` 里把创建的 `PlayerView` 存到 `private var playerView: PlayerView?`，创建后立即按当前 `resizeMode` 应用。
  - `resizeMode` setter（仿 `speed` setter）：`playerView?.resizeMode = mapToExo(value)`，映射
    `FIT→RESIZE_MODE_FIT`、`ZOOM→RESIZE_MODE_ZOOM`、`FILL→RESIZE_MODE_FILL`（`androidx.media3.ui.AspectRatioFrameLayout`）。
- **MPV (`MpvPlayerController`)**：
  - `resizeMode` setter：未 `released` 时下发属性
    - `FIT`：`keepaspect=yes`，`panscan=0`
    - `ZOOM`：`keepaspect=yes`，`panscan=1.0`
    - `FILL`：`keepaspect=no`，`panscan=0`
  - 与 `speed` setter 同样做 `released` 守卫；surface 未附着时无需特殊处理（属性会在播放时生效）。

### 2. UI 层 — 新增 `AspectRatio.kt`（镜像 `PlaybackSpeed.kt`）

新建文件，完全镜像 `PlaybackSpeed.kt` 结构，**不改动现有倍速代码**（已确认：镜像一份，零回归风险，代价是 ~80 行弹窗脚手架重复）：

- `data class AspectOption(val mode: VideoResizeMode, val label: String)`
- `DefaultAspectOptions = [默认(FIT), 铺满(ZOOM), 拉伸(FILL)]`
- `fun aspectLabel(mode): String`
- `AspectRatioPill(...)`：与 `SpeedPill` 同样的圆角胶囊、`1.04x`/`0.6 damping` 焦点缩放、聚焦反白。
  文案 = 当前模式名。**文案配色完全照搬倍速胶囊实际行为**：聚焦时 `#0A0A0A`、否则
  `White @0.85`，**不做染绿/强调色**（倍速胶囊源码并无染绿逻辑，其 `:72` 注释「非 1× 染绿」是过时错注，不要据此实现）。
- `AspectRatioPopup(...)`：镜像 `SpeedPopup` —— `Popup` 独立 window、`MutableTransitionState` 续命退场动画、
  右下角 `TransformOrigin(1f,1f)` 长出、打开时焦点落到当前项、OK 即生效并关闭。
- `AspectRatioPopupItem(...)`：镜像 `SpeedPopupItem`。

### 3. 布局 + 焦点 — 放在倍速胶囊左侧

`PlayerScreen` 标题行（约 `PlayerScreen.kt:569` 的 `Row`）从
`[标题(weight 1f)] [倍速]` 改为 `[标题(weight 1f)] [画面比例] [Spacer 8dp] [倍速]`。

焦点左右互通（新增 `aspectPillFocus = remember { FocusRequester() }`）：

| 胶囊 | left | right | down | up |
|---|---|---|---|---|
| 画面比例 | Cancel | 倍速(`speedPillFocus`) | scrubber | skip 或 Cancel |
| 倍速 | 画面比例(`aspectPillFocus`) | Cancel | scrubber | skip 或 Cancel（不变） |

`SpeedPill` 新增 `leftTarget: FocusRequester? = null` 形参；为 null 时保持 `left = Cancel`（其他调用点不受影响）。

### 4. 状态 + 持久化

照搬 `playbackSpeed` 的写法：

```kotlin
var resizeMode by remember { mutableStateOf(VideoResizeMode.FIT) }
var aspectPopupOpen by remember { mutableStateOf(false) }
LaunchedEffect(resizeMode) { controller.resizeMode = resizeMode }
```

- 播放会话内保持（切集、换源不丢，因状态在 `PlayerScreen` 作用域）。
- 关闭播放页重置为默认（`remember` 随屏销毁），**不写 SharedPreferences**。
- 自动隐藏控制条逻辑要把 `aspectPopupOpen` 一并纳入（仿现有 `speedPopupOpen`：弹窗开时不自动隐藏、BACK 优先关弹窗）。
  涉及 `PlayerScreen.kt` 中所有引用 `speedPopupOpen` 的 `LaunchedEffect`/`BackHandler`（约 326、396–398 行）。

### 5. 范围

- **仅点播 `PlayerScreen`**。直播页 `LivePlayerScreen` 无标题/倍速行，本次不加按钮；但 `VideoPlayer.resizeMode`
  两后端都已实现，将来直播页要加是现成的。
- 不做固定比例（16:9 / 4:3）。
- 不做设置项 / 不持久化。

## 验收

- 倍速胶囊左侧出现「画面比例」胶囊，D-pad 左右可在两胶囊间移动，上下与现有一致。
- OK 唤出弹窗，三项「默认/铺满/拉伸」，当前项落焦并有选中点，选择即生效并关闭。
- ExoPlayer 后端：默认留黑边、铺满裁剪填满、拉伸变形填满，肉眼可辨。
- MPV 后端：三档同样生效。
- 胶囊文案配色与倍速胶囊一致（聚焦反白），无染绿/强调色。
- 弹窗打开时控制条不自动隐藏；BACK 先关弹窗。
- 切集 / 换源后所选比例保持；退出再进重置为默认。
