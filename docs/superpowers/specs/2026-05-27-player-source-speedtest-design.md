# 播放页换源面板的优选测速

- 日期:2026-05-27
- 状态:已确认设计,待实现计划
- 涉及模块:`screen/player/PlayerScreen.kt`、`screen/player/PlayerPanel.kt`、`service/SpeedTestService.kt`(抽公共)、`ui/VideoCard.kt`(共用角标文案)

## 背景与目标

详情页的「测速优选」已经能并发测速、实时按分重排、并展示每源分辨率·速率(见
`screen/detail/DetailScreen.kt` 的 `startSpeedTest`/`stopSpeedTest` 与 `DetailSourcesSection`)。
VOD 播放页的换源面板(`PlayerPanel.SourcesTabContent`)目前没有这套能力。

目标:在播放页换源面板加入同款优选测速——点「测速优选」后并发测速、实时按打分重排面板顺序、
每个源展示分辨率与速率。复用既有测速引擎 `SpeedTestService` 与 `SourceSpeed` 模型。

## 关键决策

1. **完成后只重排,不自动切源**。详情页测完会自动选中最优源(彼时还没开播);播放页是正在观看的
   状态,自动切换会打断画面。故测完仅重排面板 + 展示分辨率/速率,由用户自行决定是否换源。
2. **测速链接复用 `SpeedTestService.testUrl`**(优先第 2 集的代表性分片),与详情页同口径,
   保证跨源可比、逻辑统一;不针对「当前正在看的集」单独测。
3. **测速进行中禁止点选换源**(与详情页一致),避免边测边切的竞态。

## 非目标

- 不改测速算法、评分权重、`SourceSpeed` 模型。
- 不对直播播放器(live)做改动,仅 VOD 播放器。
- 不做与本目标无关的重构。

## 方案

### 1. 播放页状态(`PlayerScreen.kt`)

播放页现读不可变的 `launch.sources`。新增本地可重排副本与测速状态:

```kotlin
var sources by remember { mutableStateOf(launch.sources) }   // 面板展示用,可被测速重排
var speedTesting by remember { mutableStateOf(false) }
var speedResults by remember { mutableStateOf<Map<String, SourceSpeed>>(emptyMap()) }
var speedTestJob by remember { mutableStateOf<Job?>(null) }
```

面板改用 `sources` 渲染。其余按 `sourceKey` 的查找(`currentSource` 等)无需改动:重排只改顺序、
不改成员,key 查找不受影响。

### 2. 控制逻辑(`PlayerScreen.kt`)

照搬详情页 `startSpeedTest`/`stopSpeedTest`,差异在于**完成后不修改 `currentSourceKey`、不切播放**:

- 启动:可测源(`SpeedTestService.testUrl(it) != null`)种子化为 `SpeedTestState.TESTING`,
  `speedTesting=true`。
- 每出一个结果(`SpeedTestService.testAll` 回调):累加 → 主线程快照 → 用 `speedRank` 对 `sources`
  降序重排 → 更新 `speedResults`。
- 完成:最终重排 + `speedTesting=false`(**不 auto-select**)。
- 取消(`stopSpeedTest` → `speedTestJob?.cancel()`):保留非 TESTING 的已结算结果、清掉 TESTING、
  `speedTesting=false`。
- 测速中,换源点击短路(不触发 `onPickSource`)。

### 3. UI(`PlayerPanel.kt`)

`SourcesTabContent` 增参:`speedResults / speedTesting / onSpeedTest / onStopSpeedTest /
speedTestFocusRequester`,并由 `PlayerPanel` 透传。

- **测速触发 pill**:在 sources `LazyRow` 最前插一个**适配播放页 tile 尺寸(124dp × contentHeight)**
  的 pill:空闲显示「测速优选」,测速中显示「测速中 N/M(OK 停止)」。详情页 `SpeedTestPill` 为
  120×180,尺寸不同,故为播放页做一个尺寸适配版本(文案/交互一致)。
- **每源角标**:`SourceTile` 按 `sourceKey(source)` 取 `speedResults[key]`,渲染状态角标:
  - `DONE` → `quality · speedLabel`(如「1080p · 2.1MB/s」;宽度未知则只显速率;皆空则「未知」)
  - `TESTING` → 「测速中」
  - `FAILED` → 「超时」
  并在测速中对未完成(null 或 TESTING)的 tile 做 dim。规则与 `VideoCard` 角标完全一致。
- **焦点**:仍聚焦当前在用源;因 pill 前插,其行内索引相应 +1 偏移。

### 4. 去重(targeted)

为保证两处行为严格一致,抽两处公共逻辑(服务于本目标,非无关重构):

- 将详情页私有的排序函数抽到 `SpeedTestService`:
  ```kotlin
  /** 排序键:TESTING=-1,FAILED=-2,DONE=score。供详情页/播放页统一重排。 */
  fun rank(result: SourceSpeed?, maxSpeed: Double): Double
  ```
  详情页 `speedRank` 改为调用它;播放页同样调用。
- 将测速角标文案抽为共享 helper(挂在 `SpeedTestService` 或模型上),`VideoCard` 与新 `SourceTile`
  共用,避免两份拼接逻辑漂移:
  ```kotlin
  /** 角标文案:TESTING→「测速中」、FAILED→「超时」、DONE→「quality · speedLabel」(皆空→「未知」)。 */
  fun badgeLabel(speed: SourceSpeed): String
  ```

### 5. 错误处理

- 测速失败/超时 → 该源记 `FAILED`,角标「超时」,排序沉底,不影响其他源(沿用 `testAll` 既有行为)。
- 取消(关面板不取消;离开播放页时随 `PlayerScreen` 作用域自然取消)→ 保留已结算结果。
- 无可测源(全部 `testUrl == null`)→ 点击 pill 不启动(`testable.isEmpty()` 早退)。

## 测试

- JVM 单测:
  - `SpeedTestService.rank`:TESTING(-1)/FAILED(-2)/DONE(score) 的相对次序;多个 DONE 按 score 降序。
  - `SpeedTestService.badgeLabel`:各状态 + 宽度 0 + 速率 0 的文案。
- 重排/面板属 Compose UI,沿用既有模式,不编脆弱 UI 测试;真机验收覆盖。
- 真机:在播放页换源面板点「测速优选」,确认实时重排、每源显示分辨率·速率、当前源不被自动切走。

## 影响面

- 修改:`PlayerScreen.kt`(状态 + start/stop + 透传)、`PlayerPanel.kt`(pill + tile 角标 + dim + 禁点)。
- 抽公共:`SpeedTestService.kt`(`rank` / `badgeLabel`),`DetailScreen.kt` 与 `VideoCard.kt` 改为调用。
- 不改:`SpeedTestService` 测速算法、`SourceSpeed` 模型、直播播放器。
