# 双播放器后端（ExoPlayer / MPV）+ 解码模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在设置页新增「播放器」(ExoPlayer/MPV) 与「解码」(硬解/软解) 两个选项，并引入 `VideoPlayer` 抽象层，使点播与直播两屏可在两种播放器后端运行，解码模式对两者均生效。

**Architecture:** 抽出 `VideoPlayer` 接口，现有 `PlayerController` 改造为 `ExoPlayerController`，新增 `MpvPlayerController`（libmpv）。`PlayerFactory` 按设置 + `Build.VERSION.SDK_INT` 选择实现并注入解码模式。两屏只依赖接口，不再直接持有 `ExoPlayer`。

**Tech Stack:** Kotlin, Jetpack Compose for TV, Media3/ExoPlayer 1.5.1, `dev.jdtech.mpv:libmpv:1.0.0` (libmpv via JNI), JUnit4 单元测试。

**约定：每个 commit 结尾都带 trailer：**
```
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```
（下文 commit 步骤为简洁省略，执行时统一附加。所有 git 操作在分支 `feature/dual-player-mpv` 上进行。）

**Spec:** `docs/superpowers/specs/2026-05-26-dual-player-backend-decode-design.md`

---

## Task 1: AppDataService 新增 PlayerType / DecodeMode 偏好

**Files:**
- Test: `app/src/test/java/org/moontechlab/selenetv/service/PlayerPrefsEnumTest.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/AppDataService.kt`

枚举的 `fromKey/fromDisplayName` 是纯函数（不触碰 `lateinit prefs`），可直接单测。

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/org/moontechlab/selenetv/service/PlayerPrefsEnumTest.kt`:

```kotlin
package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.moontechlab.selenetv.service.AppDataService.PlayerType
import org.moontechlab.selenetv.service.AppDataService.DecodeMode

class PlayerPrefsEnumTest {
    @Test fun playerType_fromKey_roundTrip() {
        assertEquals(PlayerType.MPV, PlayerType.fromKey("mpv"))
        assertEquals(PlayerType.EXOPLAYER, PlayerType.fromKey("exoplayer"))
    }
    @Test fun playerType_unknownKey_fallsBackToExo() {
        assertEquals(PlayerType.EXOPLAYER, PlayerType.fromKey("garbage"))
    }
    @Test fun playerType_fromDisplayName() {
        assertEquals(PlayerType.MPV, PlayerType.fromDisplayName("MPV"))
        assertEquals(PlayerType.EXOPLAYER, PlayerType.fromDisplayName("不存在"))
    }
    @Test fun decodeMode_fromKey_roundTrip() {
        assertEquals(DecodeMode.SOFTWARE, DecodeMode.fromKey("software"))
        assertEquals(DecodeMode.HARDWARE, DecodeMode.fromKey("hardware"))
    }
    @Test fun decodeMode_unknownKey_fallsBackToHardware() {
        assertEquals(DecodeMode.HARDWARE, DecodeMode.fromKey("garbage"))
    }
    @Test fun decodeMode_fromDisplayName() {
        assertEquals(DecodeMode.SOFTWARE, DecodeMode.fromDisplayName("软解"))
        assertEquals(DecodeMode.HARDWARE, DecodeMode.fromDisplayName("硬解"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.service.PlayerPrefsEnumTest"`
Expected: 编译失败（`PlayerType` / `DecodeMode` 未定义）。

- [ ] **Step 3: 加枚举 + key 常量**

In `AppDataService.kt`，在 key 常量区（`KEY_SESSION_TOKEN = "session_token"` 之后）加：

```kotlin
    private const val KEY_PLAYER_TYPE = "player_type"
    private const val KEY_DECODE_MODE = "decode_mode"
```

在 `DoubanImageSource` 枚举之后加两个枚举：

```kotlin
    /** 播放器后端 */
    enum class PlayerType(val key: String, val displayName: String) {
        EXOPLAYER("exoplayer", "ExoPlayer"),
        MPV("mpv", "MPV");

        companion object {
            fun fromKey(key: String): PlayerType = entries.find { it.key == key } ?: EXOPLAYER
            fun fromDisplayName(displayName: String): PlayerType =
                entries.find { it.displayName == displayName } ?: EXOPLAYER
        }
    }

    /** 解码方式 */
    enum class DecodeMode(val key: String, val displayName: String) {
        HARDWARE("hardware", "硬解"),
        SOFTWARE("software", "软解");

        companion object {
            fun fromKey(key: String): DecodeMode = entries.find { it.key == key } ?: HARDWARE
            fun fromDisplayName(displayName: String): DecodeMode =
                entries.find { it.displayName == displayName } ?: HARDWARE
        }
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.service.PlayerPrefsEnumTest"`
Expected: PASS（6 个测试）。

- [ ] **Step 5: 加 save / get / sync 方法**

In `AppDataService.kt`，在 `getDoubanImageSourceKeySync()`（文件末尾的同步 getter 区）之前或之后加：

```kotlin
    // ---- 播放器 ----
    suspend fun savePlayerType(displayName: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_PLAYER_TYPE, PlayerType.fromDisplayName(displayName).key).apply()
    }
    suspend fun getPlayerTypeDisplayName(): String = withContext(Dispatchers.IO) {
        PlayerType.fromKey(prefs.getString(KEY_PLAYER_TYPE, PlayerType.EXOPLAYER.key) ?: PlayerType.EXOPLAYER.key).displayName
    }
    fun getPlayerTypeSync(): PlayerType =
        PlayerType.fromKey(prefs.getString(KEY_PLAYER_TYPE, PlayerType.EXOPLAYER.key) ?: PlayerType.EXOPLAYER.key)

    // ---- 解码 ----
    suspend fun saveDecodeMode(displayName: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_DECODE_MODE, DecodeMode.fromDisplayName(displayName).key).apply()
    }
    suspend fun getDecodeModeDisplayName(): String = withContext(Dispatchers.IO) {
        DecodeMode.fromKey(prefs.getString(KEY_DECODE_MODE, DecodeMode.HARDWARE.key) ?: DecodeMode.HARDWARE.key).displayName
    }
    fun getDecodeModeSync(): DecodeMode =
        DecodeMode.fromKey(prefs.getString(KEY_DECODE_MODE, DecodeMode.HARDWARE.key) ?: DecodeMode.HARDWARE.key)
```

- [ ] **Step 6: 编译 + 提交**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/AppDataService.kt \
        app/src/test/java/org/moontechlab/selenetv/service/PlayerPrefsEnumTest.kt
git commit -m "feat: AppDataService 新增 PlayerType/DecodeMode 偏好"
```

---

## Task 2: 定义 VideoPlayer 接口

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/player/VideoPlayer.kt`

仅新增文件，不接线，编译应通过（暂无使用者）。

- [ ] **Step 1: 创建接口**

```kotlin
package org.moontechlab.selenetv.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 播放器后端统一抽象。两屏（PlayerScreen / LivePlayerScreen）只依赖本接口。
 * 状态字段须为 Compose 可观测（实现用 mutableStateOf 支撑）。位置/时长单位毫秒。
 */
interface VideoPlayer {
    val positionMs: Long
    val durationMs: Long
    val bufferedMs: Long
    val isPlaying: Boolean
    val isBuffering: Boolean
    /** 本次媒体是否成功起播过；每次 load 重置为 false。用于落播放记录判定。 */
    val hasStarted: Boolean
    /** 抽象错误：null=无错；非 null=有错（字符串仅用于展示/日志，调用方只做 null 判定）。 */
    val error: String?
    /** 播放自然结束回调，宿主用于自动下一集。 */
    var onEnded: () -> Unit

    fun load(url: String, startMs: Long, playWhenReady: Boolean = true)
    fun seekTo(ms: Long)
    fun togglePlay()
    /** 强制暂停（不是切换）。宿主在 App 切后台时调用。 */
    fun pause()
    /** 跳到直播边缘；点播实现等价为 seek 到默认位置。直播 togglePlay 恢复前调用。 */
    fun seekToLiveEdge()
    /** 把后端当前位置/缓冲/时长同步到状态；由宿主周期调用。 */
    fun syncProgress()
    fun release()

    /** 视频画面：各实现自行渲染（Exo→PlayerView；MPV→SurfaceView）。 */
    @Composable
    fun VideoSurface(modifier: Modifier)
}
```

- [ ] **Step 2: 编译 + 提交**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/VideoPlayer.kt
git commit -m "feat: 新增 VideoPlayer 播放器抽象接口"
```

---

## Task 3: 软解码器选择器（纯逻辑 + 单测）

**Files:**
- Test: `app/src/test/java/org/moontechlab/selenetv/screen/player/SoftwareCodecTest.kt`
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/player/SoftwareCodec.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package org.moontechlab.selenetv.screen.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareCodecTest {
    @Test fun googleAndC2_areSoftware() {
        assertTrue(isSoftwareCodec("OMX.google.h264.decoder"))
        assertTrue(isSoftwareCodec("c2.android.avc.decoder"))
        assertTrue(isSoftwareCodec("C2.Android.HEVC.Decoder")) // 大小写不敏感
    }
    @Test fun vendorHardware_isNotSoftware() {
        assertFalse(isSoftwareCodec("OMX.qcom.video.decoder.avc"))
        assertFalse(isSoftwareCodec("c2.mtk.avc.decoder"))
        assertFalse(isSoftwareCodec("OMX.MTK.VIDEO.DECODER.HEVC"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.screen.player.SoftwareCodecTest"`
Expected: 编译失败（`isSoftwareCodec` 未定义）。

- [ ] **Step 3: 实现 predicate + selector**

```kotlin
package org.moontechlab.selenetv.screen.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** 名称以 OMX.google. 或 c2.android. 开头者为系统软件解码器（大小写不敏感）。 */
fun isSoftwareCodec(name: String): Boolean {
    val n = name.lowercase()
    return n.startsWith("omx.google.") || n.startsWith("c2.android.")
}

/** 把系统软件解码器排到候选列表最前的选择器；其余顺序由 DEFAULT 决定（稳定排序）。 */
@OptIn(UnstableApi::class)
val SoftwareFirstSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
    MediaCodecSelector.DEFAULT
        .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        .sortedByDescending { isSoftwareCodec(it.name) }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.screen.player.SoftwareCodecTest"`
Expected: PASS（2 个测试）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/SoftwareCodec.kt \
        app/src/test/java/org/moontechlab/selenetv/screen/player/SoftwareCodecTest.kt
git commit -m "feat: 软解码器选择器 SoftwareFirstSelector + isSoftwareCodec"
```

---

## Task 4: PlayerController 实现 VideoPlayer（VideoSurface + error 抽象 + 解码）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerController.kt`

本任务保持类名 `PlayerController` 与 `exo` 公开可见，避免破坏两屏；仅让它实现接口、改 error 类型、内置 VideoSurface、接收解码模式。两屏暂不改动仍可编译（error 仅被 null 判定）。

- [ ] **Step 1: 重写 PlayerController.kt**

完整替换文件内容为：

```kotlin
package org.moontechlab.selenetv.screen.player

import android.content.Context
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.service.MediaHttp

/**
 * 封装 ExoPlayer，实现 VideoPlayer。位置/时长用毫秒。位置由调用方周期 [syncProgress] 拉取。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerController(
    context: Context,
    dataSourceFactory: androidx.media3.datasource.DataSource.Factory = MediaHttp.dataSourceFactory,
    decodeMode: AppDataService.DecodeMode = AppDataService.DecodeMode.HARDWARE,
) : VideoPlayer {

    private val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .apply {
            // 软解：用把系统软件解码器排前的 RenderersFactory；硬解保持默认。
            if (decodeMode == AppDataService.DecodeMode.SOFTWARE) {
                setRenderersFactory(object : DefaultRenderersFactory(context) {
                    override fun buildVideoRenderers(
                        ctx: Context,
                        extensionRendererMode: Int,
                        mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                        enableDecoderFallback: Boolean,
                        eventHandler: android.os.Handler,
                        eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                        allowedVideoJoiningTimeMs: Long,
                        out: ArrayList<androidx.media3.exoplayer.Renderer>,
                    ) {
                        super.buildVideoRenderers(
                            ctx, extensionRendererMode, SoftwareFirstSelector,
                            enableDecoderFallback, eventHandler, eventListener,
                            allowedVideoJoiningTimeMs, out,
                        )
                    }
                })
            }
        }
        .build()

    override var positionMs by mutableLongStateOf(0L); private set
    override var durationMs by mutableLongStateOf(0L); private set
    override var bufferedMs by mutableLongStateOf(0L); private set
    override var isPlaying by mutableStateOf(false); private set
    override var isBuffering by mutableStateOf(false); private set
    override var error by mutableStateOf<String?>(null); private set
    override var hasStarted by mutableStateOf(false); private set
    override var onEnded: () -> Unit = {}

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        override fun onPlaybackStateChanged(state: Int) {
            isBuffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) { hasStarted = true; val d = exo.duration; if (d > 0) durationMs = d }
            if (state == Player.STATE_ENDED) onEnded()
        }
        override fun onPlayerError(e: PlaybackException) { error = e.errorCodeName }
    }

    init { exo.addListener(listener) }

    @MainThread
    override fun load(url: String, startMs: Long, playWhenReady: Boolean) {
        error = null
        hasStarted = false
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        if (startMs > 0) exo.seekTo(startMs)
        exo.playWhenReady = playWhenReady
        syncProgress()
    }

    @MainThread
    override fun seekTo(ms: Long) { exo.seekTo(ms.coerceAtLeast(0)); syncProgress() }

    @MainThread
    override fun togglePlay() { exo.playWhenReady = !exo.playWhenReady }

    @MainThread
    override fun pause() { exo.playWhenReady = false }

    @MainThread
    override fun seekToLiveEdge() { exo.seekToDefaultPosition() }

    @MainThread
    override fun syncProgress() {
        positionMs = exo.currentPosition.coerceAtLeast(0)
        bufferedMs = exo.bufferedPosition.coerceAtLeast(0)
        val d = exo.duration
        if (d > 0) durationMs = d
    }

    @MainThread
    override fun release() {
        exo.removeListener(listener)
        exo.release()
    }

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
}
```

- [ ] **Step 2: 编译确认通过**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（两屏仍引用 `PlayerController`、`controller.exo` 仍被 AndroidView 使用；本步未改两屏，但 `exo` 已变 `private` → 编译会报错）。

> ⚠️ 注意：`exo` 改为 private 后，两屏中 `player = controller.exo` 会编译失败。本任务**必须连同两屏的 AndroidView 一起改**，否则不绿。因此把 Task 5 的两屏改造合并到本步——见 Step 3、Step 4。

- [ ] **Step 3: PlayerScreen 改用 VideoSurface**

In `PlayerScreen.kt`：删除 `295` 行起的整段 `AndroidView(factory = { ... player = controller.exo ... }, modifier = Modifier.fillMaxSize())`（含其上方关于 texture_view 的注释块），替换为：

```kotlin
        controller.VideoSurface(Modifier.fillMaxSize())
```

确认顶部不再需要 `import androidx.media3.ui.PlayerView` 与 `import androidx.compose.ui.viewinterop.AndroidView`（若文件其它处无用则删除，避免未用 import 警告）。

另外（`exo` 转 private 后必须改）：`PlayerScreen.kt` 中 `ON_PAUSE` 处的 `controller.exo.playWhenReady = false` 改为 `controller.pause()`。

- [ ] **Step 4: LivePlayerScreen 改用 VideoSurface**

In `LivePlayerScreen.kt`：同样找到绑定 `player = controller.exo` 的 `AndroidView` 块，替换为 `controller.VideoSurface(Modifier.fillMaxSize())`，并清理多余 import。

另外（`exo` 转 private 后必须改）：`LivePlayerScreen.kt` `togglePlay()` 中的 `controller.exo.seekToDefaultPosition()` 改为 `controller.seekToLiveEdge()`。

- [ ] **Step 5: 编译确认通过**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerController.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/player/LivePlayerScreen.kt
git commit -m "refactor: PlayerController 实现 VideoPlayer，两屏改用 VideoSurface/抽象 error"
```

---

## Task 5: PlayerFactory（运行时门控，纯逻辑单测）

**Files:**
- Test: `app/src/test/java/org/moontechlab/selenetv/screen/player/PlayerFactoryGateTest.kt`
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerFactory.kt`

门控决策抽成纯函数 `resolveEffectivePlayerType(requested, sdkInt)` 以便单测。

- [ ] **Step 1: 写失败测试**

```kotlin
package org.moontechlab.selenetv.screen.player

import org.junit.Assert.assertEquals
import org.junit.Test
import org.moontechlab.selenetv.service.AppDataService.PlayerType

class PlayerFactoryGateTest {
    @Test fun mpv_allowedOnApi26AndAbove() {
        assertEquals(PlayerType.MPV, resolveEffectivePlayerType(PlayerType.MPV, 26))
        assertEquals(PlayerType.MPV, resolveEffectivePlayerType(PlayerType.MPV, 34))
    }
    @Test fun mpv_downgradedBelowApi26() {
        assertEquals(PlayerType.EXOPLAYER, resolveEffectivePlayerType(PlayerType.MPV, 25))
        assertEquals(PlayerType.EXOPLAYER, resolveEffectivePlayerType(PlayerType.MPV, 23))
    }
    @Test fun exo_alwaysExo() {
        assertEquals(PlayerType.EXOPLAYER, resolveEffectivePlayerType(PlayerType.EXOPLAYER, 23))
        assertEquals(PlayerType.EXOPLAYER, resolveEffectivePlayerType(PlayerType.EXOPLAYER, 34))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.screen.player.PlayerFactoryGateTest"`
Expected: 编译失败（`resolveEffectivePlayerType` 未定义）。

- [ ] **Step 3: 实现工厂 + 纯门控函数**

```kotlin
package org.moontechlab.selenetv.screen.player

import android.content.Context
import android.os.Build
import androidx.media3.datasource.DataSource
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.service.AppDataService.PlayerType
import org.moontechlab.selenetv.service.MediaHttp

/** libmpv 要求 API 26+；低于 26 时把 MPV 降级为 ExoPlayer（.so 永不加载）。 */
const val MPV_MIN_SDK = 26

fun resolveEffectivePlayerType(requested: PlayerType, sdkInt: Int): PlayerType =
    if (requested == PlayerType.MPV && sdkInt < MPV_MIN_SDK) PlayerType.EXOPLAYER else requested

/**
 * 按设置 + 系统版本创建播放器后端。
 * @param userAgent 直播源 UA；点播传 null（用默认 DataSource）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PlayerFactory {
    fun create(context: Context, userAgent: String? = null): VideoPlayer {
        val decode = AppDataService.getDecodeModeSync()
        val type = resolveEffectivePlayerType(AppDataService.getPlayerTypeSync(), Build.VERSION.SDK_INT)
        return when (type) {
            PlayerType.MPV -> MpvPlayerController(context, userAgent, decode)
            PlayerType.EXOPLAYER -> {
                val ds: DataSource.Factory =
                    if (userAgent != null) MediaHttp.dataSourceFactory(userAgent) else MediaHttp.dataSourceFactory
                PlayerController(context, ds, decode)
            }
        }
    }
}
```

> ⚠️ 此文件引用 `MpvPlayerController`（Task 7 才创建）。为保持本任务可编译，本步**先注释掉** `PlayerType.MPV -> MpvPlayerController(...)` 这一行并临时改为 `PlayerType.MPV -> { val ds = ...; PlayerController(context, ds, decode) }`（即 MPV 暂回退 Exo）。Task 7 再恢复为 `MpvPlayerController`。在该行上方留注释 `// TODO(Task7): 接入 MpvPlayerController` —— 这是任务间的临时桩，Task 7 必须移除。

修正后的 `when` 块（本任务实际写入）：

```kotlin
        return when (type) {
            // TODO(Task7): 接入 MpvPlayerController（当前 MPV 暂回退 Exo 以保持可编译）
            PlayerType.MPV, PlayerType.EXOPLAYER -> {
                val ds: DataSource.Factory =
                    if (userAgent != null) MediaHttp.dataSourceFactory(userAgent) else MediaHttp.dataSourceFactory
                PlayerController(context, ds, decode)
            }
        }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.screen.player.PlayerFactoryGateTest"`
Expected: PASS（3 个测试）。

- [ ] **Step 5: 两屏改用工厂构造**

In `PlayerScreen.kt:91`：
```kotlin
    val controller = remember { PlayerController(context) }
```
改为：
```kotlin
    val controller: VideoPlayer = remember { PlayerFactory.create(context) }
```

In `LivePlayerScreen.kt:73`：
```kotlin
    val controller = remember { PlayerController(context, MediaHttp.dataSourceFactory(launch.source.ua)) }
```
改为：
```kotlin
    val controller: VideoPlayer = remember { PlayerFactory.create(context, launch.source.ua) }
```
（`LivePlayerScreen` 若因此不再直接用到 `MediaHttp`，清理其 import。）

- [ ] **Step 6: 编译 + 全部单测**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL；所有单测 PASS。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerFactory.kt \
        app/src/test/java/org/moontechlab/selenetv/screen/player/PlayerFactoryGateTest.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/player/LivePlayerScreen.kt
git commit -m "feat: PlayerFactory 按设置+SDK 门控选择播放器后端，两屏接入"
```

---

## Task 6: 引入 libmpv 依赖与构建配置

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/proguard-rules.pro`

- [ ] **Step 1: 版本目录加 libmpv**

In `gradle/libs.versions.toml`，`[versions]` 区（`media3 = "1.5.1"` 之后）加：
```toml
libmpv = "1.0.0"
```
`[libraries]` 区（media3 几行之后）加：
```toml
libmpv = { group = "dev.jdtech.mpv", name = "libmpv", version.ref = "libmpv" }
```

- [ ] **Step 2: build.gradle.kts 加依赖 + abiFilters**

ABI 限定：本项目 `app/build.gradle.kts` 已有 `splits { abi { include("arm64-v8a", "armeabi-v7a") } }`，已把构建限定到这两个 arm ABI（libmpv AAR 里的 x86/x86_64 .so 不会进 APK）。**不要**再加 `defaultConfig.ndk.abiFilters`——AGP 在 `splits.abi` 与 `ndk.abiFilters` 同时存在时会报 "Conflicting configuration" 错误。

In `dependencies { ... }`（media3 几行之后）加：
```kotlin
    implementation(libs.libmpv)
```

- [ ] **Step 3: Manifest 加 overrideLibrary**

In `app/src/main/AndroidManifest.xml`：确认根 `<manifest>` 标签含 `xmlns:tools="http://schemas.android.com/tools"`（缺则补）。在 `<manifest>` 内、`<application>` 之前加：
```xml
    <uses-sdk tools:overrideLibrary="dev.jdtech.mpv" />
```

- [ ] **Step 4: proguard 保留 MPV JNI**

In `app/proguard-rules.pro` 末尾加：
```proguard
# libmpv JNI 绑定：native 方法所在类不可裁剪/混淆
-keep class dev.jdtech.mpv.** { *; }
```

- [ ] **Step 5: 同步 + 编译验证依赖可解析**

Run: `./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -i libmpv`
Expected: 输出含 `dev.jdtech.mpv:libmpv:1.0.0`。

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（manifest 合并不再因 minSdk 26>23 报错）。

- [ ] **Step 6: 提交**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/AndroidManifest.xml app/proguard-rules.pro
git commit -m "build: 引入 dev.jdtech.mpv:libmpv:1.0.0 + overrideLibrary 门控 + arm-only abiFilters"
```

---

## Task 7: MpvPlayerController 实现

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/player/MpvPlayerController.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerFactory.kt`

> 本任务无单元测试（依赖原生 + Surface，仅能真机验证，见 Task 10）。

- [ ] **Step 1: 核对 MPVLib API**

打开依赖里的 `dev.jdtech.mpv.MPVLib` 类（IDE 里 `Cmd+点击` 或反编译 AAR），确认以下方法签名（与 mpv-android 同源）：`create(Context)`、`init()`、`destroy()`、`attachSurface(Surface)`、`detachSurface()`、`setOptionString(String,String)`、`setPropertyString(String,String)`、`command(Array<String>)`、`observeProperty(String, Int)`、`addObserver(EventObserver)`、`removeObserver(EventObserver)`，以及 `EventObserver` 接口的回调方法名（`eventProperty(property, value)` 的各重载）与格式常量（`MPV_FORMAT_NONE/FLAG/INT64/DOUBLE/STRING`）。下一步代码以这些名字为准；若实际命名不同，按实际调整（仅名称差异，结构不变）。

- [ ] **Step 2: 实现 MpvPlayerController**

> **实测 API（1.0.0，已落地）：** `MPVLib` 是**实例式**而非静态——`val mpv = MPVLib.create(context)!!`（`create` 返回平台可空），其余 `init/destroy/attachSurface/command/setOptionString/setPropertyString/observeProperty/addObserver` 都在实例 `mpv.` 上调用。格式/事件常量在嵌套对象：`MPVLib.MpvFormat.MPV_FORMAT_DOUBLE/FLAG`、`MPVLib.MpvEvent.MPV_EVENT_END_FILE`。`EventObserver` 6 个抽象方法与下方一致。下方为结构示意，实际用实例调用。

```kotlin
package org.moontechlab.selenetv.screen.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.jdtech.mpv.MPVLib
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.service.MediaHttp

/**
 * 基于 libmpv 的 VideoPlayer 实现。仅在 API>=26 由 PlayerFactory 构造。
 * 状态通过 MPVLib 属性观察回调（工作线程）切回主线程更新。
 */
class MpvPlayerController(
    context: Context,
    userAgent: String?,
    decodeMode: AppDataService.DecodeMode,
) : VideoPlayer, MPVLib.EventObserver {

    private val main = Handler(Looper.getMainLooper())
    private var pendingUrl: String? = null
    private var pendingStartMs: Long = 0
    private var surfaceAttached = false

    override var positionMs by mutableLongStateOf(0L); private set
    override var durationMs by mutableLongStateOf(0L); private set
    override var bufferedMs by mutableLongStateOf(0L); private set
    override var isPlaying by mutableStateOf(false); private set
    override var isBuffering by mutableStateOf(false); private set
    override var error by mutableStateOf<String?>(null); private set
    override var hasStarted by mutableStateOf(false); private set
    override var onEnded: () -> Unit = {}

    init {
        MPVLib.create(context)
        // init 前选项
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("tls-verify", "no")           // 自签证书：沿用本项目信任所有证书约定
        MPVLib.setOptionString("ytdl", "no")
        when (decodeMode) {
            AppDataService.DecodeMode.HARDWARE -> {
                MPVLib.setOptionString("hwdec", "mediacodec-copy")  // 不用 direct mediacodec：TV 盒子上易坏
                MPVLib.setOptionString("hwdec-codecs", "h264,hevc,vp9,av1")
            }
            AppDataService.DecodeMode.SOFTWARE -> MPVLib.setOptionString("hwdec", "no")
        }
        userAgent?.let { MPVLib.setOptionString("user-agent", MediaHttp.resolveLiveUa(it)) }
        MPVLib.init()
        // 观察属性
        MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("demuxer-cache-time", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("paused-for-cache", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("eof-reached", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.addObserver(this)
    }

    override fun load(url: String, startMs: Long, playWhenReady: Boolean) {
        error = null
        hasStarted = false
        positionMs = 0; durationMs = 0; bufferedMs = 0
        if (!surfaceAttached) {                       // surface 未就绪：暂存，surfaceCreated 后再 loadfile
            pendingUrl = url; pendingStartMs = startMs
            return
        }
        doLoad(url, startMs, playWhenReady)
    }

    private fun doLoad(url: String, startMs: Long, playWhenReady: Boolean) {
        val startSec = (startMs / 1000.0).coerceAtLeast(0.0)
        MPVLib.command(arrayOf("loadfile", url, "replace", "start=$startSec"))
        MPVLib.setPropertyString("pause", if (playWhenReady) "no" else "yes")
    }

    override fun seekTo(ms: Long) {
        MPVLib.command(arrayOf("seek", (ms / 1000.0).coerceAtLeast(0.0).toString(), "absolute"))
    }

    override fun togglePlay() {
        MPVLib.setPropertyString("pause", if (isPlaying) "yes" else "no")
    }

    override fun pause() { MPVLib.setPropertyString("pause", "yes") }

    // mpv 直播流通常不可 seek，解暂停后自然回到边缘；此处 no-op 即可。
    override fun seekToLiveEdge() {}

    override fun syncProgress() { /* MPV 走属性回调推送，无需主动拉取 */ }

    override fun release() {
        MPVLib.removeObserver(this)
        MPVLib.command(arrayOf("stop"))
        MPVLib.destroy()
    }

    // ---- MPVLib.EventObserver（工作线程回调，切主线程更新 Compose 状态）----
    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: Boolean) = main.post {
        when (property) {
            "pause" -> isPlaying = !value
            "paused-for-cache" -> isBuffering = value
            "eof-reached" -> if (value) onEnded()
        }
    }.let {}
    override fun eventProperty(property: String, value: Double) = main.post {
        when (property) {
            "time-pos" -> { positionMs = (value * 1000).toLong().coerceAtLeast(0); if (!hasStarted && value > 0) hasStarted = true }
            "duration" -> durationMs = (value * 1000).toLong().coerceAtLeast(0)
            "demuxer-cache-time" -> bufferedMs = (value * 1000).toLong().coerceAtLeast(0)
        }
    }.let {}
    override fun eventProperty(property: String, value: String) {}
    override fun event(eventId: Int) {
        if (eventId == MPVLib.MPV_EVENT_END_FILE) main.post {
            // 起播前就 END_FILE（死链/无法解码）视为错误
            if (!hasStarted) error = "mpv: playback failed"
        }
    }

    @Composable
    override fun VideoSurface(modifier: Modifier) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            MPVLib.attachSurface(holder.surface)
                            MPVLib.setOptionString("force-window", "yes")
                            surfaceAttached = true
                            pendingUrl?.let { doLoad(it, pendingStartMs, true); pendingUrl = null }
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
                            MPVLib.setPropertyString("android-surface-size", "${w}x$ht")
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            surfaceAttached = false
                            MPVLib.setPropertyString("vo", "null")
                            MPVLib.setOptionString("force-window", "no")
                            MPVLib.detachSurface()
                        }
                    })
                }
            },
            modifier = modifier,
        )
    }
}
```

> 注：`MPVLib.EventObserver` 的回调方法名/重载、`event`/`eventProperty` 的确切签名以 Step 1 核对结果为准；上面按 mpv-android 约定书写。`MPV_EVENT_END_FILE` 常量名同样以实际为准。

- [ ] **Step 3: PlayerFactory 接入 MpvPlayerController**

In `PlayerFactory.kt`，把 Task 5 的临时桩 `when` 块恢复为正式分支：
```kotlin
        return when (type) {
            PlayerType.MPV -> MpvPlayerController(context, userAgent, decode)
            PlayerType.EXOPLAYER -> {
                val ds: DataSource.Factory =
                    if (userAgent != null) MediaHttp.dataSourceFactory(userAgent) else MediaHttp.dataSourceFactory
                PlayerController(context, ds, decode)
            }
        }
```
确认 `// TODO(Task7)` 注释已删除。

- [ ] **Step 4: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。若报 MPVLib 方法名不符，按 Step 1 核对结果修正。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/MpvPlayerController.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerFactory.kt
git commit -m "feat: MpvPlayerController（libmpv）实现并接入工厂"
```

---

## Task 8: 设置页加「播放器」「解码」两行

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/tab/SettingsTab.kt`

参照既有「豆瓣数据源」行（状态 + LaunchedEffect 加载 + SettingRow + AnimatedSelectDialog + SourceSelectDialogContent）。

- [ ] **Step 1: 加状态变量**

In `SettingsTab.kt`「设置项状态」区（`var m3u8ProxyUrl by ...` 之后）加：
```kotlin
    var playerType by remember { mutableStateOf("") }
    var decodeMode by remember { mutableStateOf("") }
```
「豆瓣源选择弹框状态」区附近加：
```kotlin
    var showPlayerTypeDialog by remember { mutableStateOf(false) }
    var showDecodeModeDialog by remember { mutableStateOf(false) }
```
FocusRequester 区（`doubanDataSourceFocusRequester` 附近）加：
```kotlin
    val playerTypeFocusRequester = remember { FocusRequester() }
    val decodeModeFocusRequester = remember { FocusRequester() }
```
另外取一次系统版本用于门控：
```kotlin
    val mpvSupported = android.os.Build.VERSION.SDK_INT >= org.moontechlab.selenetv.screen.player.MPV_MIN_SDK
```

- [ ] **Step 2: LaunchedEffect 加载当前值**

In 加载设置的 `LaunchedEffect`（`doubanDataSource = AppDataService.getDoubanDataSourceDisplayName()` 那一段）内追加：
```kotlin
        playerType = AppDataService.getPlayerTypeDisplayName()
        decodeMode = AppDataService.getDecodeModeDisplayName()
```

- [ ] **Step 3: 加 SectionLabel + 两个 SettingRow**

In 列表中合适位置（建议「数据源」分组之后、「缓存」分组之前）加：
```kotlin
                SectionLabel("播放")
                SettingRow(
                    label = "播放器",
                    value = playerType.ifEmpty { "ExoPlayer" },
                    onClick = { showPlayerTypeDialog = true },
                    modifier = Modifier.focusRequester(playerTypeFocusRequester)
                )
                SettingRow(
                    label = "解码",
                    value = decodeMode.ifEmpty { "硬解" },
                    onClick = { showDecodeModeDialog = true },
                    modifier = Modifier.focusRequester(decodeModeFocusRequester)
                )
```

- [ ] **Step 4: 加两个选择弹框**

In 其它 `AnimatedSelectDialog` 旁（如 `showDoubanDataSourceDialog` 那个之后）加：
```kotlin
    AnimatedSelectDialog(
        visible = showPlayerTypeDialog,
        onDismiss = { showPlayerTypeDialog = false },
        onClosed = { playerTypeFocusRequester.requestFocus() }
    ) { onClose ->
        SourceSelectDialogContent(
            title = "选择播放器",
            // API<26 不支持 MPV：只给 ExoPlayer
            options = if (mpvSupported)
                AppDataService.PlayerType.entries.map { it.displayName }
            else listOf(AppDataService.PlayerType.EXOPLAYER.displayName),
            currentValue = playerType,
            onSelected = { displayName ->
                coroutineScope.launch {
                    AppDataService.savePlayerType(displayName)
                    playerType = displayName
                }
                onClose()
            },
            onDismiss = onClose
        )
    }
    AnimatedSelectDialog(
        visible = showDecodeModeDialog,
        onDismiss = { showDecodeModeDialog = false },
        onClosed = { decodeModeFocusRequester.requestFocus() }
    ) { onClose ->
        SourceSelectDialogContent(
            title = "选择解码方式",
            options = AppDataService.DecodeMode.entries.map { it.displayName },
            currentValue = decodeMode,
            onSelected = { displayName ->
                coroutineScope.launch {
                    AppDataService.saveDecodeMode(displayName)
                    decodeMode = displayName
                }
                onClose()
            },
            onDismiss = onClose
        )
    }
```

> 若 `coroutineScope` 在本文件的变量名不同，按文件实际命名（参考豆瓣数据源 onSelected 用的同一个 scope）。

- [ ] **Step 5: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/tab/SettingsTab.kt
git commit -m "feat: 设置页新增「播放器」「解码」两行（MPV 按 SDK 门控）"
```

---

## Task 9: 随包附带 mpv / FFmpeg 许可证文本

**Files:**
- Create: `app/src/main/assets/licenses/mpv-LICENSE.txt`
- Create: `app/src/main/assets/licenses/ffmpeg-LICENSE.txt`
- Create: `app/src/main/assets/licenses/README.txt`

接受 GPL 约束的前置交付项：分发包内必须含被引用库的许可证文本。

- [ ] **Step 1: 放入许可证文本**

从上游获取并保存：
- mpv 许可证（`https://github.com/mpv-player/mpv/blob/master/LICENSE.GPL` 与 `Copyright`）→ `mpv-LICENSE.txt`
- FFmpeg 许可证（`https://www.ffmpeg.org/legal.html` 对应的 GPL/LGPL 文本）→ `ffmpeg-LICENSE.txt`
- `README.txt` 写明：本应用通过 `dev.jdtech.mpv:libmpv` 动态加载 mpv/FFmpeg 原生库，遵循其许可证；列出版本号 `libmpv 1.0.0`。

- [ ] **Step 2: 编译确认 assets 打包**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL（产物含 assets/licenses）。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/assets/licenses/
git commit -m "chore: 随包附带 mpv/FFmpeg 许可证文本（GPL 合规）"
```

---

## Task 10: 整体构建 + 真机验证矩阵

**Files:** 无（验证任务）

> 单元测试覆盖纯逻辑；播放/Compose/MPV 原生只能真机验证。需一台 API>=26 的 Android TV/模拟器，外加（若可）一台 API 23–25 设备验证门控。

- [ ] **Step 1: 全量构建 + 单测**

Run: `./gradlew clean testDebugUnitTest assembleDebug`
Expected: 所有单测 PASS；BUILD SUCCESSFUL；产出 arm64-v8a / armeabi-v7a 两个 APK。

- [ ] **Step 2: 安装到 API>=26 设备**

Run: `./gradlew installDebug`
Expected: 安装成功。

- [ ] **Step 3: 回归 ExoPlayer（默认）**

设置→播放器=ExoPlayer，解码=硬解。播一个 HLS 点播 + 一路直播。
Expected: 行为与改造前一致（起播、拖进度、续播、换源、自动下一集均正常）。

- [ ] **Step 4: 切 MPV 硬解**

设置→播放器=MPV，解码=硬解。重新进入播放同一 HLS 点播 + 直播。
Expected: 画面出图；进度条随 `time-pos` 走；暂停/播放、拖进度生效；死链触发错误覆盖层（换源面板弹出）。

- [ ] **Step 5: 软解验证（两端各一次）**

- ExoPlayer 软解：`adb logcat | grep -i "c2.android\|OMX.google"` 应见软件解码器被选中。
- MPV 软解：`adb logcat | grep -i hwdec` 应见 `hwdec=no` / 不出现硬件解码日志。

- [ ] **Step 6: 直播 UA + 自签证书**

MPV 播一个带自定义 UA 的直播源、一个自签证书源。
Expected：UA 注入生效、自签源能拉流（`tls-verify=no`）。

- [ ] **Step 7: SDK 门控（若有 API 23–25 设备/模拟器）**

在 API 23–25 设备安装并打开设置页。
Expected：播放器选项只显示 ExoPlayer；即便 prefs 残留 "mpv"，播放走 ExoPlayer，不加载 `.so`、不崩溃。

- [ ] **Step 8: 完成确认**

记录验证结果。全部通过即实现完成。

---

## 自检（写完计划后）

- **Spec 覆盖**：播放器选项→Task 1/8；解码选项→Task 1/8；抽象层→Task 2/4；ExoPlayer 解码→Task 3/4；MPV 实现+解码→Task 7；运行时门控→Task 5/8；构建/override/proguard→Task 6；许可→Task 9；两屏接线→Task 4/5；测试路径→Task 10。无遗漏。
- **占位符**：纯逻辑步骤均含完整代码与命令；MPVLib/EventObserver 的「以实际签名为准」是显式核对步骤（Task 7 Step 1）而非待办占位。
- **类型一致**：`VideoPlayer` 字段（`error: String?`、`hasStarted`、`onEnded`、`VideoSurface`）在 Task 2 定义，Task 4/7 两实现签名一致；`getPlayerTypeSync(): PlayerType` / `getDecodeModeSync(): DecodeMode`（Task 1）与 `PlayerFactory`（Task 5）一致；`resolveEffectivePlayerType` / `MPV_MIN_SDK` 在 Task 5 定义、Task 8 复用。
- **构建可绿**：Task 4 把「exo 转 private」与两屏 VideoSurface 改造合并在同一任务完成；Task 5 用临时桩保证 MPV 分支在 MpvPlayerController 存在前可编译，Task 7 移除桩。
