# 播放页换源面板优选测速 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 VOD 播放页换源面板加入「测速优选」:点击后并发测速、实时按分重排面板、每源展示分辨率·速率;测完只重排不自动切源。

**Architecture:** 复用既有 `SpeedTestService` 测速引擎与 `SourceSpeed` 模型。先把详情页已有的排序键与角标文案抽成 `SpeedTestService` 公共函数、把测速胶囊抽成共享 UI;再给 `PlayerScreen` 加测速状态/控制(照搬详情页 `startSpeedTest`,去掉自动选源),给 `PlayerPanel` 的换源 tab 加胶囊 + 每源角标。

**Tech Stack:** Kotlin、Jetpack Compose for TV、kotlinx.coroutines、JUnit(JVM 单测纯逻辑)。

设计文档:`docs/superpowers/specs/2026-05-27-player-source-speedtest-design.md`

## 文件结构

- 修改 `service/SpeedTestService.kt` — 新增公共 `rank(...)` 与 `badgeLabel(...)`。
- 修改 `screen/detail/DetailScreen.kt` — 私有 `speedRank` 改为委托 `SpeedTestService.rank`。
- 修改 `ui/VideoCard.kt` — 角标文案改用 `SpeedTestService.badgeLabel`。
- 新增 `ui/SpeedTestPill.kt` — 把详情页的 `SpeedTestPill` + 图标 + 配色搬来,共享、尺寸/副标题可参数化。
- 修改 `screen/detail/DetailSourcesSection.kt` — 删除内联胶囊/图标,改用共享胶囊。
- 修改 `screen/player/PlayerPanel.kt` — 换源 tab 加胶囊 + `SourceTile` 角标/dim/测速中禁点。
- 修改 `screen/player/PlayerScreen.kt` — 测速状态、`startSpeedTest`/`stopSpeedTest`、本地可重排 `sources`、透传回调。
- 新增 `app/src/test/java/.../service/SpeedTestRankBadgeTest.kt` — `rank`/`badgeLabel` JVM 单测。

---

### Task 1: SpeedTestService 抽公共 `rank` 与 `badgeLabel`(TDD)

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/SpeedTestService.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailScreen.kt:1065-1069`
- Modify: `app/src/main/java/org/moontechlab/selenetv/ui/VideoCard.kt:326-335`
- Test: `app/src/test/java/org/moontechlab/selenetv/service/SpeedTestRankBadgeTest.kt`

- [ ] **Step 1: 写失败的 JVM 单测**

创建 `app/src/test/java/org/moontechlab/selenetv/service/SpeedTestRankBadgeTest.kt`:

```kotlin
package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.moontechlab.selenetv.model.SourceSpeed
import org.moontechlab.selenetv.model.SpeedTestState

class SpeedTestRankBadgeTest {

    @Test
    fun rank_orders_done_above_testing_above_failed() {
        val done = SourceSpeed(SpeedTestState.DONE, widthPx = 1920, speedKbps = 1000.0)
        val testing = SourceSpeed(SpeedTestState.TESTING)
        val failed = SourceSpeed(SpeedTestState.FAILED)
        assertTrue(SpeedTestService.rank(done, 1000.0) > SpeedTestService.rank(testing, 1000.0))
        assertTrue(SpeedTestService.rank(testing, 1000.0) > SpeedTestService.rank(failed, 1000.0))
        assertEquals(-1.0, SpeedTestService.rank(null, 1000.0), 0.0)
        assertEquals(-1.0, SpeedTestService.rank(testing, 1000.0), 0.0)
        assertEquals(-2.0, SpeedTestService.rank(failed, 1000.0), 0.0)
    }

    @Test
    fun rank_two_done_sorted_by_score() {
        val hi = SourceSpeed(SpeedTestState.DONE, widthPx = 1920, speedKbps = 1000.0)
        val lo = SourceSpeed(SpeedTestState.DONE, widthPx = 640, speedKbps = 100.0)
        assertTrue(SpeedTestService.rank(hi, 1000.0) > SpeedTestService.rank(lo, 1000.0))
    }

    @Test
    fun badgeLabel_states() {
        assertEquals("测速中", SpeedTestService.badgeLabel(SourceSpeed(SpeedTestState.TESTING)))
        assertEquals("超时", SpeedTestService.badgeLabel(SourceSpeed(SpeedTestState.FAILED)))
        assertEquals(
            "1080p · 2.1MB/s",
            SpeedTestService.badgeLabel(SourceSpeed(SpeedTestState.DONE, quality = "1080p", speedLabel = "2.1MB/s")),
        )
        assertEquals(
            "2.1MB/s",
            SpeedTestService.badgeLabel(SourceSpeed(SpeedTestState.DONE, quality = "", speedLabel = "2.1MB/s")),
        )
        assertEquals("未知", SpeedTestService.badgeLabel(SourceSpeed(SpeedTestState.DONE, quality = "", speedLabel = "")))
    }
}
```

- [ ] **Step 2: 运行,确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.moontechlab.selenetv.service.SpeedTestRankBadgeTest"`
Expected: FAIL — `rank` / `badgeLabel` 未定义。

- [ ] **Step 3: 在 SpeedTestService 新增两个公共函数**

在 `SpeedTestService.kt` 的 `score(...)` 函数之后插入(`SpeedTestState` 已 import):

```kotlin
    /** 排序键:DONE=score(0..100)、TESTING/null=-1、FAILED=-2。详情页/播放页统一重排用。 */
    fun rank(result: SourceSpeed?, maxSpeed: Double): Double = when {
        result == null || result.state == SpeedTestState.TESTING -> -1.0
        result.state == SpeedTestState.FAILED -> -2.0
        else -> score(result, maxSpeed)
    }

    /** 角标文案:TESTING→「测速中」、FAILED→「超时」、DONE→「quality · speedLabel」(皆空→「未知」)。 */
    fun badgeLabel(speed: SourceSpeed): String = when (speed.state) {
        SpeedTestState.TESTING -> "测速中"
        SpeedTestState.FAILED -> "超时"
        SpeedTestState.DONE ->
            listOf(speed.quality, speed.speedLabel).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "未知" }
    }
```

- [ ] **Step 4: 运行,确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "org.moontechlab.selenetv.service.SpeedTestRankBadgeTest"`
Expected: PASS(3 个用例)。

- [ ] **Step 5: 详情页改为复用 `rank`**

在 `DetailScreen.kt` 把私有 `speedRank`(约 1065-1069 行)整体替换为委托:

```kotlin
private fun speedRank(result: SourceSpeed?, maxSpeed: Double): Double =
    SpeedTestService.rank(result, maxSpeed)
```
(`SpeedTestService`、`SourceSpeed` 已在该文件 import;若 `SpeedTestState` 现因此不再被引用而报未使用,删掉其 import。)

- [ ] **Step 6: VideoCard 角标改用 `badgeLabel`**

在 `ui/VideoCard.kt` 把测速角标的 `label` 计算(约 328-335 行的 `val label = when (speed.state) { ... }`)替换为:

```kotlin
                    val label = org.moontechlab.selenetv.service.SpeedTestService.badgeLabel(speed)
```
保留其后的 `labelColor` 的 `when (speed.state) { ... }` 不变。

- [ ] **Step 7: 全量单测 + 编译**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL,所有测试通过。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/SpeedTestService.kt app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailScreen.kt app/src/main/java/org/moontechlab/selenetv/ui/VideoCard.kt app/src/test/java/org/moontechlab/selenetv/service/SpeedTestRankBadgeTest.kt
git commit -m "refactor: SpeedTestService 抽公共 rank/badgeLabel,详情页与 VideoCard 复用

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: 把 SpeedTestPill 抽成共享 UI(尺寸/副标题可参数化)

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/ui/SpeedTestPill.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailSourcesSection.kt`

这是「移动 + 参数化」,无新行为。详情页调用保持默认尺寸,行为不变。

- [ ] **Step 1: 新建共享文件,移动胶囊与其图标/配色**

打开 `DetailSourcesSection.kt`,把以下符号**原样剪切**到新文件 `app/src/main/java/org/moontechlab/selenetv/ui/SpeedTestPill.kt`:
- `private fun SpeedTestPill(...)`(约 267-324 行)
- 它用到的私有图标 composable:`LightningBolt(...)` 与 `MoonCrescentSpinner(...)`
- 它用到的私有配色:`PillUnfocusedBg`、`PillFocusedBg`、`PillFocusedFg`(约 258-260 行)

新文件 package 为 `org.moontechlab.selenetv.ui`,补齐被移动代码所需的 import(`androidx.compose.*`、`androidx.tv.material3.*` 等,照 DetailSourcesSection 现有 import 拷贝相关项)。把 `SpeedTestPill`、`LightningBolt`、`MoonCrescentSpinner`、三个颜色 `val` 的可见性从 `private` 改为 `internal`。

给 `SpeedTestPill` 增加三个带默认值的参数,并在内部用上:

```kotlin
@Composable
internal fun SpeedTestPill(
    testing: Boolean,
    testedCount: Int,
    totalCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 120.dp,
    height: Dp = 180.dp,
    idleSubtitle: String = "自动选最优源",
) {
    // ... 原 body,但把 .width(120.dp).height(180.dp) 改为 .width(width).height(height),
    //     把待命态副标题 Text("自动选最优源") 改为 Text(idleSubtitle)
}
```
(需要 `import androidx.compose.ui.unit.Dp`、`androidx.compose.ui.unit.dp`。)

- [ ] **Step 2: DetailSourcesSection 改用共享胶囊**

在 `DetailSourcesSection.kt`:
- 删除已移走的 `SpeedTestPill`/`LightningBolt`/`MoonCrescentSpinner`/三个颜色 `val`。
- 添加 import:`import org.moontechlab.selenetv.ui.SpeedTestPill`。
- 原调用处(约 162-170 行)保持不变(用默认 120×180 与默认副标题)。

- [ ] **Step 3: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL(纯移动,无行为变化)。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/ui/SpeedTestPill.kt app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailSourcesSection.kt
git commit -m "refactor: SpeedTestPill 抽到 ui/ 共享并参数化尺寸/副标题

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: PlayerPanel 换源 tab 加胶囊与每源角标

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerPanel.kt`

加默认值参数,使本任务单独可编译、且在 PlayerScreen 未接线前播放页行为不变(`speedTesting=false`、`speedResults` 空、回调 no-op)。

- [ ] **Step 1: PlayerPanel 增参并透传**

在 `PlayerPanel(...)` 形参列表(约 94-113 行)的 `onContentExitUp: () -> Unit,` 之后、`modifier` 之前加入:

```kotlin
    speedResults: Map<String, org.moontechlab.selenetv.model.SourceSpeed> = emptyMap(),
    speedTesting: Boolean = false,
    onSpeedTest: () -> Unit = {},
    onStopSpeedTest: () -> Unit = {},
```

在 `when (t)` 的 `PanelTab.Sources -> SourcesTabContent(...)`(约 200-203 行)调用改为传入新参数:

```kotlin
                    PanelTab.Sources -> SourcesTabContent(
                        sources = sources, currentSourceKey = currentSourceKey, onPick = onPickSource,
                        contentHeight = ch, firstFocus = srcFocus, onExitUp = onContentExitUp,
                        speedResults = speedResults, speedTesting = speedTesting,
                        onSpeedTest = onSpeedTest, onStopSpeedTest = onStopSpeedTest,
                    )
```

- [ ] **Step 2: SourcesTabContent 增参 + 前置胶囊 + 角标/dim/测速中禁点**

把 `SourcesTabContent`(约 454-484 行)整体替换为:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourcesTabContent(
    sources: List<SearchResult>,
    currentSourceKey: String,
    onPick: (String) -> Unit,
    contentHeight: Dp,
    firstFocus: FocusRequester,
    onExitUp: () -> Unit,
    speedResults: Map<String, org.moontechlab.selenetv.model.SourceSpeed> = emptyMap(),
    speedTesting: Boolean = false,
    onSpeedTest: () -> Unit = {},
    onStopSpeedTest: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val selectedIndex = sources.indexOfFirst { sourceKey(it) == currentSourceKey }.coerceAtLeast(0)
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().height(contentHeight),
    ) {
        item(key = "__speedtest__") {
            org.moontechlab.selenetv.ui.SpeedTestPill(
                testing = speedTesting,
                testedCount = speedResults.count { it.value.state != org.moontechlab.selenetv.model.SpeedTestState.TESTING },
                totalCount = speedResults.size,
                onClick = { if (speedTesting) onStopSpeedTest() else onSpeedTest() },
                width = 124.dp,
                height = contentHeight,
                idleSubtitle = "测速并排序",
                modifier = Modifier.animateItem(),
            )
        }
        itemsIndexed(sources, key = { _, s -> sourceKey(s) }) { index, source ->
            val key = sourceKey(source)
            val speed = speedResults[key]
            SourceTile(
                name = source.sourceName,
                episodeCount = source.episodes.size.coerceAtLeast(1),
                poster = source.poster,
                current = key == currentSourceKey,
                heightDp = contentHeight,
                speed = speed,
                dimmed = speedTesting && (speed == null || speed.state == org.moontechlab.selenetv.model.SpeedTestState.TESTING),
                onClick = { if (!speedTesting) onPick(key) },
                onExitUp = onExitUp,
                focusRequester = if (index == selectedIndex) firstFocus else null,
                modifier = Modifier.animateItem(),
            )
        }
    }
}
```

- [ ] **Step 3: SourceTile 增 speed/dimmed/modifier 参数并渲染角标**

把 `SourceTile`(约 566-575 行的签名)替换为新签名(增 `speed`、`dimmed`、`modifier`):

```kotlin
@Composable
private fun SourceTile(
    name: String,
    episodeCount: Int,
    poster: String,
    current: Boolean,
    heightDp: Dp,
    onClick: () -> Unit,
    onExitUp: () -> Unit,
    focusRequester: FocusRequester?,
    speed: org.moontechlab.selenetv.model.SourceSpeed? = null,
    dimmed: Boolean = false,
    modifier: Modifier = Modifier,
) {
```

把 Surface 的 `modifier =` 起始处(约 586 行)由 `Modifier.then(...)` 改为接入外部 `modifier`:把
```kotlin
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
```
改为
```kotlin
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
```

在 Surface 内容 `Box(modifier = Modifier.fillMaxSize())` 里,把测速角标和 dim 蒙层加进去——放在 `if (current) { ... 在用 ... }`(约 635-646 行)之后、`Box` 闭合 `}`(约 647 行)之前插入:

```kotlin
            if (speed != null) {
                val txt = org.moontechlab.selenetv.service.SpeedTestService.badgeLabel(speed)
                val txtColor = when (speed.state) {
                    org.moontechlab.selenetv.model.SpeedTestState.FAILED -> Color(0xFFE57373)
                    org.moontechlab.selenetv.model.SpeedTestState.TESTING -> Color.White.copy(alpha = 0.75f)
                    org.moontechlab.selenetv.model.SpeedTestState.DONE -> Color.White
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(text = txt, color = txtColor, fontSize = 10.sp, fontWeight = FontWeight.W700, maxLines = 1)
                }
            }
            if (dimmed) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            }
```

- [ ] **Step 4: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。(此时播放页换源 tab 会显示测速胶囊,但 onClick 还是 no-op,Task 4 接线后生效。)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerPanel.kt
git commit -m "feat: 播放页换源 tab 加测速胶囊与每源分辨率/速率角标

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: PlayerScreen 接线测速(状态 + start/stop,不自动切源)

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt`

- [ ] **Step 1: 新增状态**

在 `PlayerScreen.kt` 现有状态声明区(约 113-123 行,`var currentSourceKey ...` 附近)之后加入:

```kotlin
    var sources by remember { mutableStateOf(launch.sources) }
    var speedTesting by remember { mutableStateOf(false) }
    var speedResults by remember { mutableStateOf<Map<String, org.moontechlab.selenetv.model.SourceSpeed>>(emptyMap()) }
    var speedTestJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
```
(`remember`/`mutableStateOf`/`getValue`/`setValue` 已在该文件 import;`sourceKey` 已 import。)

- [ ] **Step 2: 新增 start/stop 控制(放在 `fun openPanel()` 之后,约 150 行附近)**

```kotlin
    // 优选测速:并发测速 → 实时按分重排面板 sources → 每源出分;完成「只重排不切源」(不动 currentSourceKey)。
    val startSpeedTest: () -> Unit = handler@{
        if (speedTesting) return@handler
        val testable = sources.filter { org.moontechlab.selenetv.service.SpeedTestService.testUrl(it) != null }
        if (testable.isEmpty()) return@handler
        val seed = testable.associate {
            sourceKey(it) to org.moontechlab.selenetv.model.SourceSpeed(org.moontechlab.selenetv.model.SpeedTestState.TESTING)
        }
        speedResults = seed
        speedTesting = true
        speedTestJob = scope.launch {
            val jobScope = this
            val acc = java.util.concurrent.ConcurrentHashMap(seed)
            try {
                org.moontechlab.selenetv.service.SpeedTestService.testAll(testable) { key, result ->
                    acc[key] = result
                    jobScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        val snap = HashMap(acc)
                        val maxSpeed = org.moontechlab.selenetv.service.SpeedTestService.maxSpeed(snap.values)
                        sources = sources.sortedByDescending {
                            org.moontechlab.selenetv.service.SpeedTestService.rank(snap[sourceKey(it)], maxSpeed)
                        }
                        speedResults = snap
                    }
                }
                val maxSpeed = org.moontechlab.selenetv.service.SpeedTestService.maxSpeed(acc.values)
                sources = sources.sortedByDescending {
                    org.moontechlab.selenetv.service.SpeedTestService.rank(acc[sourceKey(it)], maxSpeed)
                }
                speedResults = HashMap(acc)
                speedTesting = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                speedResults = HashMap(acc.filterValues { it.state != org.moontechlab.selenetv.model.SpeedTestState.TESTING })
                speedTesting = false
                throw e
            }
        }
    }
    val stopSpeedTest: () -> Unit = { speedTestJob?.cancel() }
```

- [ ] **Step 3: 面板用本地 `sources` 并透传回调**

在 `PlayerPanel(...)` 调用处(约 523 行起):
- 把 `sources = launch.sources,`(530 行)改为 `sources = sources,`。
- 在 `onContentExitUp = { ... },`(571-576 行)之后加入:
```kotlin
                    speedResults = speedResults,
                    speedTesting = speedTesting,
                    onSpeedTest = startSpeedTest,
                    onStopSpeedTest = stopSpeedTest,
```

- [ ] **Step 4: 全量单测 + 编译**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL,所有测试通过。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt
git commit -m "feat: 播放页换源面板接入优选测速(实时重排,不自动切源)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: 整体构建与设备验收

**Files:** 无(验证)

- [ ] **Step 1: 构建**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。
(`lintDebug` 在本仓库 master 上已有既存 error,只需确认本次改动不新增针对所改文件的 lint error。)

- [ ] **Step 2: 安装并启动**

Run: `./gradlew installDebug`
然后 `adb shell am start -n org.moontechlab.selenetv/.MainActivity`。

- [ ] **Step 3: 真机验收(交回用户)**

进入任意 VOD 播放,按「下」唤出面板 → 「换源」tab → 焦点左移到「测速优选」胶囊 → OK:
- 各源角标依次出现分辨率·速率,面板实时按分重排;
- 测速中再次 OK 可停止;
- 测速完成后**当前播放不被自动切走**,由用户自行选源。

---

## 备注

- 测速链接用 `SpeedTestService.testUrl`(优先第 2 集),与详情页同口径,不针对当前集。
- 重排只改 `sources` 顺序、不改成员;`PlayerScreen` 其余按 `sourceKey` 的查找(`currentSource` 等)仍用 `launch.sources`,内容一致不受影响。
- 重排/面板属 Compose UI,沿用既有模式,不编脆弱 UI 测试;`rank`/`badgeLabel` 已有 JVM 单测。
