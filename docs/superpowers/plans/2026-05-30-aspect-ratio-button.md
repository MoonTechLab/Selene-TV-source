# 画面比例按钮 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在点播播放器 `PlayerScreen` 的倍速胶囊**左侧**加一个「画面比例」胶囊，支持 FIT(默认/留黑边) / ZOOM(铺满裁剪) / FILL(拉伸变形) 三档缩放，ExoPlayer 与 MPV 两后端都生效。

**Architecture:** `VideoPlayer` 接口加 `var resizeMode: VideoResizeMode`，两实现各自映射到底层（ExoPlayer→`PlayerView.resizeMode`=`AspectRatioFrameLayout.RESIZE_MODE_*`；MPV→`keepaspect`+`panscan` 属性）。UI 层新建 `AspectRatio.kt`，**逐行镜像** `PlaybackSpeed.kt`（同一 `androidx.tv.material3.Surface` + `focusProperties` + 独立 `Popup` 套路），只改名字/文案/取值。`PlayerScreen` 加会话级状态、把胶囊放进标题行、接好横向焦点（两胶囊互通）与弹窗/自动隐藏/BACK 让位逻辑。`SpeedPill` 加一个 `leftTarget` 形参以便其左导航到新胶囊。

**Tech Stack:** Kotlin 2.2 / Jetpack Compose for TV (`androidx.tv.material3`) / Media3 ExoPlayer (`androidx.media3.ui.AspectRatioFrameLayout`) / libmpv (`dev.jdtech.mpv.MPVLib`，实例式 API)。构建 `./gradlew`，单元测试 JUnit（`app/src/test`）。

**Spec:** `docs/superpowers/specs/2026-05-30-aspect-ratio-button-design.md`

---

## 关键事实（核对真实源码后的修正，以本节为准，不要照搬 spec 细节）

1. **真实包/路径是 `screen/player/`**（包名 `org.moontechlab.selenetv.screen.player`）。`PlaybackSpeed.kt` / `PlayerScreen.kt` / `VideoPlayer.kt` / `PlayerController.kt` / `MpvPlayerController.kt` 全在 `app/src/main/java/org/moontechlab/selenetv/screen/player/` 下。新建的 `AspectRatio.kt` 放同目录、同包（spec 说的 `ui/components/player/` 是错的）。
2. **`PlaybackSpeed.kt` 用的是 `androidx.tv.material3.Surface` + `ClickableSurfaceDefaults` + `focusProperties { up/down/left/right }`**，导航靠 `FocusRequester`（左右默认 `FocusRequester.Cancel`），**不是** `Box`+`onKeyEvent`+`onNavigate*` lambda。镜像时照搬这套。
3. **`SpeedPopup` 是真有进出动画的**：独立 `Popup` window + 自定义 `PopupPositionProvider`（定位在胶囊**上方** 8dp、右端对齐胶囊右端）+ `MutableTransitionState`/`AnimatedVisibility`（fade+scale，`TransformOrigin(1f,1f)`）+ 打开时 `LaunchedEffect` 重试 8 次把焦点落到当前项。镜像照搬，**不要**简化。
4. **胶囊文案配色无染绿**（聚焦 `#0A0A0A`、否则 `White@0.85`）——spec 正确，且 `SpeedPill` KDoc 里「非 1× 染绿」是过时错注。但 **弹窗选中项有个绿点 `#4CAF50`**（`SpeedPopupItem` 的小圆点 background）。镜像 `AspectRatioPopupItem` **保留这个绿点**，与倍速弹窗一致；spec 验收的「无染绿」只针对胶囊本身。
5. **ExoPlayer 的 `PlayerView` 没存字段**，在 `VideoSurface` 里从布局 `R.layout.player_view_texture` inflate 出来再丢弃。必须新增 `private var playerView: PlayerView? = null`，在 factory 里存住并应用当前 `resizeMode`，`onRelease` 清空。
6. **MPV 用实例式 API `mpv.setPropertyString(name, value)`**（`mpv` 是 `MPVLib.create(context)` 的实例，不是 `MPVLib.xxx` 静态调用）。`speed` setter 的守卫是 `if (!released)`（**没有** `initialized` 字段）。镜像时用同一守卫。`keepaspect` 用 `"yes"/"no"`、`panscan` 用 `"0"/"1.0"`，都走 `setPropertyString`。
7. **`PlayerScreen` 的焦点请求器**只有 `scrubberFocus` / `panelTabFocus` / `speedPillFocus` / `skipFocus`（约 245-248 行），**没有 cancelButtonFocus / speedFirstItemFocus**（弹窗自己内部建 item 的 FocusRequester）。
8. **倍速状态**：`var playbackSpeed by mutableFloatStateOf(1f)`、`var speedPopupOpen by mutableStateOf(false)`、`LaunchedEffect(playbackSpeed) { controller.speed = playbackSpeed }`（约 129-131 行）。
9. **倍速弹窗的 BACK 让位**：`PlayerScreen` 有三个 `BackHandler`（约 396-398 行）全部 `&& !speedPopupOpen`，因为弹窗是独立 Popup 自带 `dismissOnBackPress`。新弹窗同理——只需把 `!aspectPopupOpen` 也加进这三个守卫，**不需要**为它单独写 `BackHandler`。
10. **标题行**（约 569-601 行）：`Row(verticalAlignment = Alignment.Bottom)` = `[Text(weight 1f)] [Spacer(16dp)] [Box{SpeedPill; SpeedPopup}]`。`SpeedPill` 调用点传 `currentSpeed / onClick(={speedPopupOpen=true; wake()}) / focusRequester=speedPillFocus / scrubberFocus / upTarget=if(activeSegment!=null) skipFocus else null`。有个 `wake()` 函数用于唤醒控制条——新胶囊 onClick 也要调。

---

## File Structure

| 文件 | 操作 | 职责 |
|---|---|---|
| `screen/player/VideoPlayer.kt` | Modify | 加 `var resizeMode` 接口属性 + 文件末尾 `enum class VideoResizeMode` |
| `screen/player/PlayerController.kt` | Modify | ExoPlayer：`playerView` 字段 + `resizeMode` setter + `exoResizeMode()` 映射 + factory 内存住/应用、`onRelease` 清空 |
| `screen/player/MpvPlayerController.kt` | Modify | MPV：`resizeMode` setter 下发 `keepaspect`/`panscan` |
| `screen/player/AspectRatio.kt` | Create | 逐行镜像 `PlaybackSpeed.kt`：`AspectOption` / `DefaultAspectOptions` / `aspectLabel` / `AspectRatioPill` / `AspectRatioPopup` / `AspectRatioPopupItem` |
| `screen/player/PlaybackSpeed.kt` | Modify | `SpeedPill` 加 `leftTarget: FocusRequester? = null` 形参（`left = leftTarget ?: Cancel`），其余不动 |
| `screen/player/PlayerScreen.kt` | Modify | 会话级状态、`LaunchedEffect` 下发、把胶囊放标题行、横向焦点、自动隐藏/BACK 纳入 `aspectPopupOpen` |
| `app/src/test/java/org/moontechlab/selenetv/AspectRatioTest.kt` | Create | 纯逻辑单测：`aspectLabel` 文案 + `DefaultAspectOptions` 顺序 |

> 路径前缀：上表 `screen/player/...` 实为 `app/src/main/java/org/moontechlab/selenetv/screen/player/...`。

**范围**：仅点播 `PlayerScreen`；直播页 `LivePlayerScreen` 本次不加按钮（接口属性两后端已实现，将来可直接用）。不做固定比例（16:9/4:3），不做设置项，不写 SharedPreferences。

---

## Task 1: 接口层 — `VideoResizeMode` 枚举 + 接口属性

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/VideoPlayer.kt`

- [ ] **Step 1: 在 `VideoPlayer` 接口里加 `resizeMode`（紧跟 `var speed: Float`）**

找到：

```kotlin
    /** 播放倍速（1f=原速）。直播实现可忽略。 */
    var speed: Float
```

在其**下方**插入：

```kotlin
    /** 画面缩放模式。直播实现可忽略，默认 FIT。 */
    var resizeMode: VideoResizeMode
```

- [ ] **Step 2: 在文件末尾（接口右括号 `}` 之后）加枚举**

```kotlin

enum class VideoResizeMode {
    FIT,   // 适应：保持比例，留黑边（默认）
    ZOOM,  // 铺满：保持比例，裁剪填满
    FILL,  // 拉伸：变形填满
}
```

- [ ] **Step 3: 编译确认会失败（两实现还没实现新成员）**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL —— `PlayerController` 与 `MpvPlayerController` 报「is not abstract and does not implement abstract member ... resizeMode」。预期内，Task 2/3 修复。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/VideoPlayer.kt
git commit -m "feat(player): add VideoResizeMode + resizeMode to VideoPlayer interface"
```

---

## Task 2: ExoPlayer 实现 — `PlayerController.resizeMode`

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerController.kt`

- [ ] **Step 1: 加 `AspectRatioFrameLayout` 导入**

在 import 区 `import androidx.media3.ui.PlayerView` 那行**上方**加：

```kotlin
import androidx.media3.ui.AspectRatioFrameLayout
```

- [ ] **Step 2: 加 `playerView` 字段（放在状态字段区，紧跟 `override var speed` 块之后）**

找到 `speed` 块：

```kotlin
    override var speed: Float = 1f
        set(value) {
            field = value
            exo.setPlaybackSpeed(value)
        }
```

在其**下方**加：

```kotlin
    private var playerView: PlayerView? = null

    override var resizeMode: VideoResizeMode = VideoResizeMode.FIT
        set(value) {
            field = value
            playerView?.resizeMode = exoResizeMode(value)
        }

    private fun exoResizeMode(mode: VideoResizeMode): Int = when (mode) {
        VideoResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
```

- [ ] **Step 3: 在 `VideoSurface` 的 factory 里存住 PlayerView、应用当前模式，并在 `onRelease` 清空**

找到现状（约 122-137 行）：

```kotlin
    @Composable
    override fun VideoSurface(modifier: Modifier) {
        // texture_view 背景的 PlayerView：视频随覆盖层一起淡出（见 R.layout.player_view_texture）。
        AndroidView(
            factory = { ctx ->
                (android.view.LayoutInflater.from(ctx)
                    .inflate(org.moontechlab.selenetv.R.layout.player_view_texture, null) as PlayerView)
                    .apply {
                        useController = false
                        player = exo
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
            },
            modifier = modifier,
        )
    }
```

整体替换为：

```kotlin
    @Composable
    override fun VideoSurface(modifier: Modifier) {
        // texture_view 背景的 PlayerView：视频随覆盖层一起淡出（见 R.layout.player_view_texture）。
        AndroidView(
            factory = { ctx ->
                (android.view.LayoutInflater.from(ctx)
                    .inflate(org.moontechlab.selenetv.R.layout.player_view_texture, null) as PlayerView)
                    .apply {
                        useController = false
                        player = exo
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        resizeMode = exoResizeMode(this@PlayerController.resizeMode)
                    }
                    .also { playerView = it }
            },
            onRelease = { playerView = null },
            modifier = modifier,
        )
    }
```

说明：`apply { ... resizeMode = ... }` 里的 `resizeMode` 是 `PlayerView` 自带的 `Int` 属性；`this@PlayerController.resizeMode` 明确取控制器枚举值，保证 surface 创建时即应用一次。`onRelease` 在 surface 离开组合时断开字段引用。

- [ ] **Step 4: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: 仍会因 `MpvPlayerController` 未实现 `resizeMode` 而 FAIL，但报错应只剩 MPV 一处；若仍报 `PlayerController` 相关错误，先修本任务。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerController.kt
git commit -m "feat(player): implement resizeMode on ExoPlayer PlayerController"
```

---

## Task 3: MPV 实现 — `MpvPlayerController.resizeMode`

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/MpvPlayerController.kt`

- [ ] **Step 1: 加 `resizeMode` 属性（镜像 `speed` 的 `if (!released)` 守卫与实例式 API）**

找到现状（约 57-61 行）：

```kotlin
    override var speed: Float = 1f
        set(value) {
            field = value
            if (!released) mpv.setPropertyString("speed", value.toString())
        }
```

在其**下方**加：

```kotlin
    override var resizeMode: VideoResizeMode = VideoResizeMode.FIT
        set(value) {
            field = value
            if (released) return
            when (value) {
                VideoResizeMode.FIT -> {
                    mpv.setPropertyString("keepaspect", "yes")
                    mpv.setPropertyString("panscan", "0")
                }
                VideoResizeMode.ZOOM -> {
                    mpv.setPropertyString("keepaspect", "yes")
                    mpv.setPropertyString("panscan", "1.0")
                }
                VideoResizeMode.FILL -> {
                    mpv.setPropertyString("keepaspect", "no")
                    mpv.setPropertyString("panscan", "0")
                }
            }
        }
```

> 注：`keepaspect`/`panscan` 在 surface 未附着时设置也没关系——mpv 会缓存属性、附着后生效（与 `speed` 同理）。`mpv` 是构造里 `MPVLib.create(context)!!` 的实例，必须用 `mpv.setPropertyString(...)`，不要写成 `MPVLib.setPropertyString(...)`。

- [ ] **Step 2: 编译确认整模块通过**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS（BUILD SUCCESSFUL）。接口与两实现齐全。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/MpvPlayerController.kt
git commit -m "feat(player): implement resizeMode on MpvPlayerController (keepaspect/panscan)"
```

---

## Task 4: UI 组件 — 新建 `AspectRatio.kt`（逐行镜像 `PlaybackSpeed.kt`）+ 单测

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/player/AspectRatio.kt`
- Create: `app/src/test/java/org/moontechlab/selenetv/AspectRatioTest.kt`

- [ ] **Step 1: 先写失败单测（纯逻辑：文案 + 选项顺序）**

新建 `app/src/test/java/org/moontechlab/selenetv/AspectRatioTest.kt`：

```kotlin
package org.moontechlab.selenetv

import org.junit.Assert.assertEquals
import org.junit.Test
import org.moontechlab.selenetv.screen.player.DefaultAspectOptions
import org.moontechlab.selenetv.screen.player.VideoResizeMode
import org.moontechlab.selenetv.screen.player.aspectLabel

class AspectRatioTest {

    @Test
    fun aspectLabel_returns_chinese_names() {
        assertEquals("默认", aspectLabel(VideoResizeMode.FIT))
        assertEquals("铺满", aspectLabel(VideoResizeMode.ZOOM))
        assertEquals("拉伸", aspectLabel(VideoResizeMode.FILL))
    }

    @Test
    fun defaultAspectOptions_are_fit_zoom_fill_in_order() {
        assertEquals(
            listOf(VideoResizeMode.FIT, VideoResizeMode.ZOOM, VideoResizeMode.FILL),
            DefaultAspectOptions.map { it.mode },
        )
    }
}
```

- [ ] **Step 2: 跑单测确认失败（编译不过：符号还不存在）**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.AspectRatioTest"`
Expected: FAIL —— `unresolved reference: aspectLabel` / `DefaultAspectOptions` / `VideoResizeMode`。（`VideoResizeMode` 已在 Task 1 定义，故主要报 `aspectLabel`/`DefaultAspectOptions`。）

- [ ] **Step 3: 新建 `AspectRatio.kt`（逐行镜像 `PlaybackSpeed.kt`，只改名字/文案/取值）**

新建 `app/src/main/java/org/moontechlab/selenetv/screen/player/AspectRatio.kt`，内容如下。对应关系：`AspectOption`↔`SpeedOption`、`DefaultAspectOptions`↔`DefaultSpeedOptions`、`aspectLabel`↔`speedLabel`、`AspectRatioPill`↔`SpeedPill`（多一个 `rightTarget` 指向倍速胶囊）、`AspectRatioPopup`↔`SpeedPopup`、`AspectRatioPopupItem`↔`SpeedPopupItem`。`VideoResizeMode` 同包无需 import。

```kotlin
package org.moontechlab.selenetv.screen.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

data class AspectOption(val mode: VideoResizeMode, val label: String)

// FIT(默认/留黑边) / ZOOM(铺满裁剪) / FILL(拉伸变形)——3 档缩放，详见画面比例按钮设计 spec。
val DefaultAspectOptions: List<AspectOption> = listOf(
    AspectOption(VideoResizeMode.FIT, "默认"),
    AspectOption(VideoResizeMode.ZOOM, "铺满"),
    AspectOption(VideoResizeMode.FILL, "拉伸"),
)

fun aspectLabel(mode: VideoResizeMode): String =
    DefaultAspectOptions.firstOrNull { it.mode == mode }?.label ?: "默认"

/**
 * 控制条顶部的画面比例胶囊。文案 = 当前模式名（默认 / 铺满 / 拉伸）。
 * 焦点模型：左取消、右交给倍速胶囊(rightTarget)、下交回 scrubber、上交给 skip 或取消。OK 唤出 [AspectRatioPopup]。
 * 镜像 [SpeedPill]；胶囊文案配色无染绿（弹窗选中项保留绿点，与倍速一致）。
 */
@Composable
fun AspectRatioPill(
    currentMode: VideoResizeMode,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    scrubberFocus: FocusRequester,
    rightTarget: FocusRequester,
    upTarget: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "aspect_pill_scale",
    )
    val shape = RoundedCornerShape(percent = 50)
    Surface(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusProperties {
                down = scrubberFocus
                up = upTarget ?: FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = rightTarget
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                shape = shape,
            ),
            focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 11.dp, vertical = 3.dp)) {
            Text(
                text = aspectLabel(currentMode),
                color = if (focused) Color(0xFF0A0A0A) else Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
            )
        }
    }
}

/**
 * 画面比例选择 Popup：独立 Android window，自管焦点 + BACK。位置 = 胶囊上方 8dp，右端对齐胶囊右端。
 * 打开时焦点落到当前模式项；按 OK 立即生效并关闭。镜像 [SpeedPopup]。
 */
@Composable
fun AspectRatioPopup(
    open: Boolean,
    currentMode: VideoResizeMode,
    options: List<AspectOption> = DefaultAspectOptions,
    onSelect: (VideoResizeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = open

    if (transitionState.currentState || transitionState.targetState) {
        val density = LocalDensity.current
        val gapPx = with(density) { 8.dp.roundToPx() }
        val positionProvider = remember(gapPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x = (anchorBounds.right - popupContentSize.width).coerceAtLeast(0)
                    val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
                    return IntOffset(x, y)
                }
            }
        }
        Popup(
            onDismissRequest = onDismiss,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
            popupPositionProvider = positionProvider,
        ) {
            val focusRequesters = remember(options) { options.map { FocusRequester() } }
            val currentIndex = remember(options, currentMode) {
                options.indexOfFirst { it.mode == currentMode }.coerceAtLeast(0)
            }
            LaunchedEffect(Unit) {
                repeat(8) {
                    try {
                        focusRequesters[currentIndex].requestFocus()
                        return@LaunchedEffect
                    } catch (_: Exception) {
                        kotlinx.coroutines.delay(40)
                    }
                }
            }
            AnimatedVisibility(
                visibleState = transitionState,
                enter = fadeIn(animationSpec = tween(180)) +
                    scaleIn(
                        initialScale = 0.92f,
                        animationSpec = tween(220),
                        transformOrigin = TransformOrigin(1f, 1f),
                    ),
                exit = fadeOut(animationSpec = tween(140)) +
                    scaleOut(
                        targetScale = 0.95f,
                        animationSpec = tween(160),
                        transformOrigin = TransformOrigin(1f, 1f),
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xF20A0A0A))
                        .padding(vertical = 6.dp, horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    options.forEachIndexed { index, opt ->
                        AspectRatioPopupItem(
                            label = opt.label,
                            selected = opt.mode == currentMode,
                            onClick = { onSelect(opt.mode) },
                            focusRequester = focusRequesters[index],
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AspectRatioPopupItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .width(120.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            if (focused) Color(0xFF0A0A0A) else Color(0xFF4CAF50)
                        } else Color.Transparent
                    ),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                color = when {
                    focused -> Color(0xFF0A0A0A)
                    selected -> Color.White
                    else -> Color.White.copy(alpha = 0.65f)
                },
                fontSize = 15.sp,
                fontWeight = if (focused || selected) FontWeight.W700 else FontWeight.W500,
            )
        }
    }
}
```

> 若编译报某 import 未使用，对照真实 `PlaybackSpeed.kt`（两文件用到的 Compose/TV API 完全相同）；正常情况下上面这份 import 与倍速文件一致。

- [ ] **Step 4: 跑单测确认通过**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.AspectRatioTest"`
Expected: PASS（2 个测试通过）。

- [ ] **Step 5: 编译确认整模块通过**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/AspectRatio.kt app/src/test/java/org/moontechlab/selenetv/AspectRatioTest.kt
git commit -m "feat(player): add AspectRatio pill/popup UI mirroring PlaybackSpeed"
```

---

## Task 5: `SpeedPill` 加 `leftTarget` 形参（让倍速胶囊左导航能到画面比例胶囊）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlaybackSpeed.kt`

- [ ] **Step 1: 给 `SpeedPill` 加 `leftTarget` 形参**

找到 `SpeedPill` 的形参列表：

```kotlin
fun SpeedPill(
    currentSpeed: Float,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    scrubberFocus: FocusRequester,
    upTarget: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
```

把它改为（在 `upTarget` 后、`modifier` 前插入 `leftTarget`）：

```kotlin
fun SpeedPill(
    currentSpeed: Float,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    scrubberFocus: FocusRequester,
    upTarget: FocusRequester? = null,
    leftTarget: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
```

- [ ] **Step 2: 在 `focusProperties` 里用 `leftTarget`**

找到：

```kotlin
            .focusProperties {
                down = scrubberFocus
                up = upTarget ?: FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            },
```

把 `left` 一行改为：

```kotlin
            .focusProperties {
                down = scrubberFocus
                up = upTarget ?: FocusRequester.Cancel
                left = leftTarget ?: FocusRequester.Cancel
                right = FocusRequester.Cancel
            },
```

> `leftTarget` 默认 `null` ⇒ `left = Cancel`，其余调用点（若有）行为不变；只有 `PlayerScreen` 这一个调用点会传入新胶囊的 FocusRequester。

- [ ] **Step 3: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlaybackSpeed.kt
git commit -m "feat(player): add optional leftTarget to SpeedPill for dual-pill nav"
```

---

## Task 6: `PlayerScreen` 接线 — 状态 + 下发 + 自动隐藏/BACK 让位

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt`

> 本任务只加状态/逻辑、不动标题行布局；编译应通过（新状态/焦点暂未被 UI 用，会有「未使用」警告，Task 7 解决）。行号为参考，**以代码内容定位为准**。`VideoResizeMode` 与 `PlayerScreen` 同包，无需 import。

- [ ] **Step 1: 加会话级状态 + 下发 `LaunchedEffect`（紧跟倍速三行，约 129-131 行）**

找到：

```kotlin
    var playbackSpeed by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var speedPopupOpen by remember { mutableStateOf(false) }
    LaunchedEffect(playbackSpeed) { controller.speed = playbackSpeed }
```

在其**下方**加：

```kotlin
    var resizeMode by remember { mutableStateOf(VideoResizeMode.FIT) }
    var aspectPopupOpen by remember { mutableStateOf(false) }
    LaunchedEffect(resizeMode) { controller.resizeMode = resizeMode }
```

- [ ] **Step 2: 加 `aspectPillFocus`（紧跟 `speedPillFocus`，约 247 行）**

找到：

```kotlin
    val speedPillFocus = remember { FocusRequester() }
```

在其**下方**加：

```kotlin
    val aspectPillFocus = remember { FocusRequester() }
```

- [ ] **Step 3: 把 `aspectPopupOpen` 纳入自动隐藏 `LaunchedEffect`（约 326-332 行）**

找到：

```kotlin
    LaunchedEffect(controlsVisible, isPlaying, panelOpen, speedPopupOpen, interactionTick) {
        if (controlsVisible && isPlaying && !panelOpen && !speedPopupOpen) {
            kotlinx.coroutines.delay(AUTO_HIDE_MS)
            controlsVisible = false
            try { (if (activeSegment != null) skipFocus else scrubberFocus).requestFocus() } catch (_: Exception) {}
        }
    }
```

替换为（key 加 `aspectPopupOpen`，条件加 `&& !aspectPopupOpen`）：

```kotlin
    LaunchedEffect(controlsVisible, isPlaying, panelOpen, speedPopupOpen, aspectPopupOpen, interactionTick) {
        if (controlsVisible && isPlaying && !panelOpen && !speedPopupOpen && !aspectPopupOpen) {
            kotlinx.coroutines.delay(AUTO_HIDE_MS)
            controlsVisible = false
            try { (if (activeSegment != null) skipFocus else scrubberFocus).requestFocus() } catch (_: Exception) {}
        }
    }
```

- [ ] **Step 4: 把 `!aspectPopupOpen` 加进三个 `BackHandler` 的 enabled（约 396-398 行）**

找到：

```kotlin
    BackHandler(enabled = panelOpen && !speedPopupOpen) { panelOpen = false }
    BackHandler(enabled = !panelOpen && controlsVisible && !speedPopupOpen) { controlsVisible = false }
    BackHandler(enabled = !panelOpen && !controlsVisible && !speedPopupOpen) {
```

替换为：

```kotlin
    BackHandler(enabled = panelOpen && !speedPopupOpen && !aspectPopupOpen) { panelOpen = false }
    BackHandler(enabled = !panelOpen && controlsVisible && !speedPopupOpen && !aspectPopupOpen) { controlsVisible = false }
    BackHandler(enabled = !panelOpen && !controlsVisible && !speedPopupOpen && !aspectPopupOpen) {
```

（第三个 `BackHandler` 的函数体不变，只改 `enabled`。新弹窗是独立 `Popup` 自带 `dismissOnBackPress`，故无需新增 `BackHandler`。）

- [ ] **Step 5: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS（`aspectPillFocus`/`aspectPopupOpen` 未使用警告可忽略，Task 7 用上）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt
git commit -m "feat(player): wire aspect-ratio session state, controller push, auto-hide & back yield"
```

---

## Task 7: `PlayerScreen` 布局 — 标题行放胶囊 + 弹窗 + 横向焦点

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt`

- [ ] **Step 1: 在标题行倍速胶囊左侧插入画面比例胶囊+弹窗，并给倍速胶囊接 `leftTarget`**

找到标题 `Row`（约 569-601 行）：

```kotlin
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = launch.title + (if (epCount > 1) "  ·  第 ${currentEpisode + 1} 集" else ""),
                            color = Color.White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.W700,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Box {
                            SpeedPill(
                                currentSpeed = playbackSpeed,
                                onClick = { speedPopupOpen = true; wake() },
                                focusRequester = speedPillFocus,
                                scrubberFocus = scrubberFocus,
                                upTarget = if (activeSegment != null) skipFocus else null,
                            )
                            // SpeedPopup 内部用 MutableTransitionState 跟踪进出态，
                            // 退场动画期间自行续命挂载——不再用外层 if 直接拆装
                            SpeedPopup(
                                open = speedPopupOpen,
                                currentSpeed = playbackSpeed,
                                onSelect = {
                                    playbackSpeed = it
                                    speedPopupOpen = false
                                    wake()
                                },
                                onDismiss = { speedPopupOpen = false },
                            )
                        }
                    }
```

整体替换为（在 `Spacer(16dp)` 之后、倍速 `Box` 之前插入画面比例 `Box` + `Spacer(8dp)`；倍速胶囊加 `leftTarget = aspectPillFocus`）：

```kotlin
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = launch.title + (if (epCount > 1) "  ·  第 ${currentEpisode + 1} 集" else ""),
                            color = Color.White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.W700,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Box {
                            AspectRatioPill(
                                currentMode = resizeMode,
                                onClick = { aspectPopupOpen = true; wake() },
                                focusRequester = aspectPillFocus,
                                scrubberFocus = scrubberFocus,
                                rightTarget = speedPillFocus,
                                upTarget = if (activeSegment != null) skipFocus else null,
                            )
                            AspectRatioPopup(
                                open = aspectPopupOpen,
                                currentMode = resizeMode,
                                onSelect = {
                                    resizeMode = it
                                    aspectPopupOpen = false
                                    wake()
                                },
                                onDismiss = { aspectPopupOpen = false },
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Box {
                            SpeedPill(
                                currentSpeed = playbackSpeed,
                                onClick = { speedPopupOpen = true; wake() },
                                focusRequester = speedPillFocus,
                                scrubberFocus = scrubberFocus,
                                upTarget = if (activeSegment != null) skipFocus else null,
                                leftTarget = aspectPillFocus,
                            )
                            // SpeedPopup 内部用 MutableTransitionState 跟踪进出态，
                            // 退场动画期间自行续命挂载——不再用外层 if 直接拆装
                            SpeedPopup(
                                open = speedPopupOpen,
                                currentSpeed = playbackSpeed,
                                onSelect = {
                                    playbackSpeed = it
                                    speedPopupOpen = false
                                    wake()
                                },
                                onDismiss = { speedPopupOpen = false },
                            )
                        }
                    }
```

焦点链路：`scrubber ←下/上→ 画面比例/倍速`；横向 `Cancel ←左 [画面比例] 右→ [倍速] 右→ Cancel`。每个弹窗各自锚定到自己的 `Box`（`PopupPositionProvider` 用 `anchorBounds`），不会相互错位。`AspectRatioPill`/`AspectRatioPopup` 与本文件同包（`screen.player`），无需 import。

- [ ] **Step 2: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS，且 Task 6 的「未使用」警告消失。

- [ ] **Step 3: 跑全部单测 + lint（确保没碰坏别处）**

Run: `./gradlew testDebugUnitTest`
Expected: PASS（含 `AspectRatioTest` 与既有 `ExampleUnitTest`）。

- [ ] **Step 4: 构建 Debug APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL，产出 `arm64-v8a` / `armeabi-v7a` 两个 APK。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt
git commit -m "feat(player): place aspect-ratio pill + popup in title row with dual-pill focus"
```

---

## Task 8: 真机/模拟器验收（对照 spec 验收清单）

**Files:** 无（仅运行与人工核对）

> 画面缩放、焦点、弹窗位置无法用单测覆盖，需在 TV 模拟器上肉眼验收。模拟器套路见记忆 `tv-emulator-e2e`（AVD `Selene_TV`、adb 全路径、LEANBACK 启动、默认已登录有数据；长按 OK = `keyevent --longpress 23`）。

- [ ] **Step 1: 安装到模拟器/设备**

Run: `./gradlew installDebug`（或对匹配架构的 APK `adb install -r`）
Expected: Success。

- [ ] **Step 2: 进入任意点播剧集播放页，逐条核对 spec「验收」**

- [ ] 倍速胶囊**左侧**出现「画面比例」胶囊，文案为当前模式名（默认/铺满/拉伸）。
- [ ] D-pad **左右**可在「画面比例 ↔ 倍速」间移动；从「画面比例」再按左被 Cancel 拦住（不跳到标题）；上下与原先一致（下→进度条；上→skip 段存在时到跳过按钮）。
- [ ] OK 唤出弹窗，三项「默认 / 铺满 / 拉伸」，**当前项落焦**且左侧绿点；选择某项**即生效并关闭**，焦点回到胶囊（倍速弹窗同款进出动画）。
- [ ] 弹窗显示在「画面比例」胶囊**上方、右端对齐胶囊右端**（与倍速弹窗同款定位，不重叠）。
- [ ] **ExoPlayer 后端**：默认留黑边、铺满裁剪填满、拉伸变形填满，三态肉眼可辨。
- [ ] **MPV 后端**：切到 MPV 解码的源，三档同样生效（FILL 变形、ZOOM 裁剪、FIT 复原）。
- [ ] 胶囊文案配色与倍速胶囊一致（聚焦反白 `#0A0A0A`、未聚焦 `White@0.85`），胶囊本身无染绿。
- [ ] 弹窗打开时控制条**不自动隐藏**；按 BACK **先关弹窗**（不退出播放、不收起控制条）。
- [ ] **切集 / 换源**后所选比例保持；**退出播放页再进**重置为「默认(FIT)」。

- [ ] **Step 3: 如有视觉/行为微调，提交**

```bash
git add -A
git commit -m "fix(player): tune aspect-ratio button per device verification"
```

（若无需微调则跳过。）

---

## Self-Review（对照 spec 自查）

**Spec 覆盖度：**
- 目标（倍速旁加画面比例按钮）→ Task 7 ✅
- 3 档 FIT/ZOOM/FILL → Task 1 枚举 + Task 2/3 两后端映射 ✅
- 接口层 `var resizeMode` → Task 1 ✅
- ExoPlayer `playerView` 字段 + `RESIZE_MODE_*` 映射 + factory 应用/onRelease → Task 2 ✅
- MPV `keepaspect`/`panscan` + `if(released)` 守卫 + 实例式 `mpv.setPropertyString` → Task 3 ✅
- UI 镜像 `PlaybackSpeed.kt`（Pill/Popup/Item + label + options + 进出动画 + 自定位 Popup）→ Task 4 ✅
- 胶囊无染绿 / 弹窗选中绿点 → Task 4 按真实源码处理 ✅
- `SpeedPill` 新增 `leftTarget`（spec 明确要求）→ Task 5 ✅
- 布局放倍速左侧 + 焦点左右互通 → Task 7（链路 Cancel ↔ 画面比例 ↔ 倍速）✅
- 状态会话内保持、退出重置、不持久化 → Task 6（`remember` 随屏销毁）✅
- 自动隐藏纳入 `aspectPopupOpen` + BACK 让位 → Task 6（弹窗自带 `dismissOnBackPress`，三个 BackHandler 加 `!aspectPopupOpen`）✅
- 仅点播、不做固定比例、不做设置项 → 范围已锁定 ✅

**类型/命名一致性：** `VideoResizeMode`(FIT/ZOOM/FILL)、`resizeMode`、`aspectPopupOpen`、`aspectPillFocus`、`aspectLabel`、`AspectOption`/`DefaultAspectOptions`、`AspectRatioPill`(含 `rightTarget`)/`AspectRatioPopup`/`AspectRatioPopupItem`、`exoResizeMode`、`SpeedPill.leftTarget` 各 Task 间一致 ✅

**与 spec 的有意偏差（已在「关键事实」说明）：** 路径 `screen/player/`（非 `ui/components/player/`）；UI 套路是 `Surface`+`focusProperties`+独立 `Popup`（非 `Box`+`onKeyEvent`）；弹窗沿用倍速的「上方右对齐自定位 + 进出动画」（spec 误以为无动画/需手算偏移）；MPV 用实例式 API 且守卫只有 `released`（无 `initialized`）。
