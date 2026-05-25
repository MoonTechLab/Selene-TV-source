# 播放器接入 ExoPlayer + 切源 / 切集 / 定时进度保存 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `PlayerScreen` 的占位舞台换成真实 Media3/ExoPlayer 播放，并补齐切源（带进度续播）、切集（自动下一集）、定时进度保存（10s + 暂停/切换/退出/后台）。

**Architecture:** 新增独立 `PlayerController`（封装 ExoPlayer，暴露 Compose `State`）与 `PlayProgressReporter`（节流 + 调 `ApiService.savePlayRecord`）。纯逻辑（URL 拼接、PlayRecord 映射、保存守卫）抽到无副作用的 `PlayProgress` 对象并做单元测试。`PlayerScreen` 仅替换舞台与 position/duration 来源，焦点/面板/OSD 模型不动。

**Tech Stack:** Kotlin, Jetpack Compose for TV, Media3 ExoPlayer + HLS + OkHttp DataSource, OkHttp（信任所有证书，复用现有姿态），JUnit4 单元测试。

规格：`docs/superpowers/specs/2026-05-25-player-exoplayer-integration-design.md`

---

## 文件结构

- 新增 `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayProgress.kt` — 纯函数：`buildPlayUrl` / `buildRecord` / `shouldSave`。
- 新增 `app/src/test/java/org/moontechlab/selenetv/screen/player/PlayProgressTest.kt` — 上面三个函数的单元测试。
- 新增 `app/src/main/java/org/moontechlab/selenetv/service/MediaHttp.kt` — 信任所有证书的 OkHttp `DataSource.Factory`。
- 新增 `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerController.kt` — 封装 ExoPlayer。
- 新增 `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayProgressReporter.kt` — 节流 + 上报。
- 修改 `gradle/libs.versions.toml`、`app/build.gradle.kts` — Media3 依赖。
- 修改 `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt` — 接线（Task 6–8）。

---

## Task 1: 添加 Media3 依赖

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts:72-94`（dependencies 块）

- [ ] **Step 1: 在 `libs.versions.toml` 的 `[versions]` 末尾加版本号**

在 `[versions]` 段（`navigationCompose = "2.8.5"` 这一行后）追加：

```toml
media3 = "1.5.1"
```

- [ ] **Step 2: 在 `[libraries]` 段追加 Media3 库定义**

在 `[libraries]` 段末尾（`androidx-navigation-compose = ...` 行后）追加：

```toml
androidx-media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
androidx-media3-exoplayer-hls = { group = "androidx.media3", name = "media3-exoplayer-hls", version.ref = "media3" }
androidx-media3-ui = { group = "androidx.media3", name = "media3-ui", version.ref = "media3" }
androidx-media3-datasource-okhttp = { group = "androidx.media3", name = "media3-datasource-okhttp", version.ref = "media3" }
```

- [ ] **Step 3: 在 `app/build.gradle.kts` 的 dependencies 块加 4 条 implementation**

在 `implementation(libs.androidx.navigation.compose)` 行（约 86 行）后追加：

```kotlin
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
```

- [ ] **Step 4: 验证依赖可解析并编译通过**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。若出现 Media3 版本与 AGP 9.1 / compileSdk 36 不兼容的解析或编译错误，把 `media3` 版本号上调到当前最新稳定版（如 `1.6.1` / `1.7.0`）后重跑。

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: 引入 Media3 ExoPlayer/HLS/UI/OkHttp 依赖"
```

---

## Task 2: 纯逻辑 `PlayProgress`（TDD）

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayProgress.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/screen/player/PlayProgressTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/org/moontechlab/selenetv/screen/player/PlayProgressTest.kt`：

```kotlin
package org.moontechlab.selenetv.screen.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.moontechlab.selenetv.model.SearchResult

class PlayProgressTest {

    private fun source() = SearchResult(
        id = "42",
        title = "示例剧",
        poster = "http://h/p.jpg",
        episodes = listOf("http://h/1.m3u8", "http://h/2.m3u8"),
        episodesTitles = listOf("第一集", "第二集"),
        source = "qq",
        sourceName = "企鹅源",
        year = "2024",
    )

    @Test
    fun buildPlayUrl_noProxy_returnsRaw() {
        assertEquals("http://h/1.m3u8", PlayProgress.buildPlayUrl("http://h/1.m3u8", ""))
    }

    @Test
    fun buildPlayUrl_withProxy_encodesAndPrefixes() {
        val out = PlayProgress.buildPlayUrl("http://h/a b.m3u8", "http://proxy/?u=")
        assertEquals("http://proxy/?u=http%3A%2F%2Fh%2Fa+b.m3u8", out)
    }

    @Test
    fun buildRecord_mapsFieldsAndConvertsSeconds() {
        val r = PlayProgress.buildRecord(
            source = source(), title = "示例剧", episodeIndex = 1,
            positionMs = 65_000, durationMs = 2_700_000, nowMs = 1_700_000_000_000,
        )
        assertEquals("qq", r.source)
        assertEquals("42", r.id)
        assertEquals("企鹅源", r.sourceName)
        assertEquals("http://h/p.jpg", r.cover)
        assertEquals(2, r.index)              // 0 基 +1
        assertEquals(2, r.totalEpisodes)
        assertEquals(65L, r.playTime)         // ms→s
        assertEquals(2700L, r.totalTime)
        assertEquals(1_700_000_000_000, r.saveTime)
        assertEquals("示例剧", r.searchTitle)
    }

    @Test
    fun shouldSave_falseWhenUnderOneSecond() {
        assertFalse(PlayProgress.shouldSave(force = true, positionMs = 500, lastSavedPositionMs = -1, elapsedSinceLastSaveMs = 999_999))
    }

    @Test
    fun shouldSave_trueWhenForcedAndPastOneSecond() {
        assertTrue(PlayProgress.shouldSave(force = true, positionMs = 5_000, lastSavedPositionMs = 5_000, elapsedSinceLastSaveMs = 0))
    }

    @Test
    fun shouldSave_falseWhenWithinThrottleWindow() {
        assertFalse(PlayProgress.shouldSave(force = false, positionMs = 20_000, lastSavedPositionMs = 10_000, elapsedSinceLastSaveMs = 9_000))
    }

    @Test
    fun shouldSave_falseWhenPositionUnchanged() {
        assertFalse(PlayProgress.shouldSave(force = false, positionMs = 20_000, lastSavedPositionMs = 20_000, elapsedSinceLastSaveMs = 11_000))
    }

    @Test
    fun shouldSave_trueAfterWindowWithMovement() {
        assertTrue(PlayProgress.shouldSave(force = false, positionMs = 30_000, lastSavedPositionMs = 20_000, elapsedSinceLastSaveMs = 11_000))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.screen.player.PlayProgressTest"`
Expected: FAIL（`PlayProgress` 未定义，编译错误）。

- [ ] **Step 3: 实现 `PlayProgress`**

创建 `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayProgress.kt`：

```kotlin
package org.moontechlab.selenetv.screen.player

import org.moontechlab.selenetv.model.PlayRecord
import org.moontechlab.selenetv.model.SearchResult
import java.net.URLEncoder

/**
 * 播放进度相关的纯逻辑（无副作用、可单元测试）：
 * URL 代理拼接、PlayRecord 映射、定时保存守卫。
 */
object PlayProgress {

    /** m3u8 代理拼接：代理为空返回原 URL，否则 URL-encode 后加前缀。 */
    fun buildPlayUrl(rawUrl: String, proxyUrl: String): String {
        if (proxyUrl.isBlank()) return rawUrl
        return proxyUrl + URLEncoder.encode(rawUrl, "UTF-8")
    }

    /** 由当前源 + 进度构造播放记录。play_time/total_time 单位为秒（MoonTV 约定）。 */
    fun buildRecord(
        source: SearchResult,
        title: String,
        episodeIndex: Int,
        positionMs: Long,
        durationMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): PlayRecord = PlayRecord(
        id = source.id,
        source = source.source,
        title = title,
        sourceName = source.sourceName,
        year = source.year,
        cover = source.poster,
        index = episodeIndex + 1,
        totalEpisodes = source.episodes.size.coerceAtLeast(1),
        playTime = positionMs / 1000,
        totalTime = durationMs / 1000,
        saveTime = nowMs,
        searchTitle = title,
    )

    /** 是否应保存：<1s 一律不存；强制则存；否则需距上次≥10s 且位置有变化。 */
    fun shouldSave(
        force: Boolean,
        positionMs: Long,
        lastSavedPositionMs: Long,
        elapsedSinceLastSaveMs: Long,
    ): Boolean {
        if (positionMs < 1000) return false
        if (force) return true
        if (elapsedSinceLastSaveMs < 10_000) return false
        if (positionMs == lastSavedPositionMs) return false
        return true
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.screen.player.PlayProgressTest"`
Expected: PASS（8 个测试全过）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayProgress.kt app/src/test/java/org/moontechlab/selenetv/screen/player/PlayProgressTest.kt
git commit -m "feat(player): 新增 PlayProgress 纯逻辑（URL/记录/保存守卫）+ 单测"
```

---

## Task 3: `MediaHttp` 信任所有证书的 DataSource.Factory

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/service/MediaHttp.kt`

- [ ] **Step 1: 实现 `MediaHttp`**

创建 `app/src/main/java/org/moontechlab/selenetv/service/MediaHttp.kt`。该类复用 `SeleneApplication.createUnsafeOkHttpClient()` 的同款「信任所有证书 + 跳过 hostname 校验」姿态，包成 Media3 的 `OkHttpDataSource.Factory`：

```kotlin
package org.moontechlab.selenetv.service

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 供 ExoPlayer 使用的 HTTP DataSource 工厂。
 * 与 ImageLoader / SearchService 一致，信任所有证书并跳过 hostname 校验，
 * 以适配用户自签名 / 自托管的视频源。
 */
object MediaHttp {

    private val client: OkHttpClient by lazy { createUnsafeOkHttpClient() }

    /** ExoPlayer DefaultMediaSourceFactory 使用的 DataSource 工厂。 */
    val dataSourceFactory: DataSource.Factory by lazy {
        OkHttpDataSource.Factory(client)
    }

    private fun createUnsafeOkHttpClient(): OkHttpClient {
        return try {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val ssl = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
            OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder().build()
        }
    }
}
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/MediaHttp.kt
git commit -m "feat(player): 新增 MediaHttp 信任所有证书的 OkHttp DataSource 工厂"
```

---

## Task 4: `PlayerController` 封装 ExoPlayer

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerController.kt`

- [ ] **Step 1: 实现 `PlayerController`**

创建 `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerController.kt`：

```kotlin
package org.moontechlab.selenetv.screen.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.moontechlab.selenetv.service.MediaHttp

/**
 * 封装 ExoPlayer，把播放状态暴露为 Compose State 供 PlayerScreen 驱动现有控制层。
 * 位置/时长用毫秒，与外壳进度条约定一致。位置由调用方周期 [syncProgress] 拉取。
 */
class PlayerController(context: Context) {

    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(MediaHttp.dataSourceFactory))
        .build()

    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var bufferedMs by mutableLongStateOf(0L); private set
    var isPlaying by mutableStateOf(false); private set
    var isBuffering by mutableStateOf(false); private set
    var error by mutableStateOf<PlaybackException?>(null); private set

    /** 播放自然结束（STATE_ENDED）回调，宿主用于自动下一集。 */
    var onEnded: () -> Unit = {}

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }

        override fun onPlaybackStateChanged(state: Int) {
            isBuffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) durationMs = exo.duration.coerceAtLeast(0)
            if (state == Player.STATE_ENDED) onEnded()
        }

        override fun onPlayerError(e: PlaybackException) {
            error = e
        }
    }

    init {
        exo.addListener(listener)
    }

    /** 载入并播放一个 URL；startMs>0 时从该处续播。error 清零。 */
    fun load(url: String, startMs: Long, playWhenReady: Boolean = true) {
        error = null
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        if (startMs > 0) exo.seekTo(startMs)
        exo.playWhenReady = playWhenReady
        syncProgress()
    }

    fun seekTo(ms: Long) {
        exo.seekTo(ms.coerceAtLeast(0))
        syncProgress()
    }

    fun togglePlay() {
        exo.playWhenReady = !exo.playWhenReady
    }

    /** 把 ExoPlayer 的当前位置/缓冲/时长同步到 State；由宿主轮询调用。 */
    fun syncProgress() {
        positionMs = exo.currentPosition.coerceAtLeast(0)
        bufferedMs = exo.bufferedPosition.coerceAtLeast(0)
        val d = exo.duration
        if (d > 0) durationMs = d
    }

    fun release() {
        exo.removeListener(listener)
        exo.release()
    }
}
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerController.kt
git commit -m "feat(player): 新增 PlayerController 封装 ExoPlayer 状态"
```

---

## Task 5: `PlayProgressReporter` 节流上报

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayProgressReporter.kt`

- [ ] **Step 1: 实现 `PlayProgressReporter`**

创建 `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayProgressReporter.kt`：

```kotlin
package org.moontechlab.selenetv.screen.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.service.ApiService

/**
 * 播放记录上报：内置节流（见 [PlayProgress.shouldSave]）。
 * 用独立 IO scope 发起 fire-and-forget 保存，使退出/切换时的保存不被 composition 取消打断。
 */
class PlayProgressReporter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSavedPositionMs = -1L
    private var lastSaveAtMs = 0L

    fun save(
        source: SearchResult,
        title: String,
        episodeIndex: Int,
        positionMs: Long,
        durationMs: Long,
        force: Boolean,
    ) {
        val now = System.currentTimeMillis()
        if (!PlayProgress.shouldSave(force, positionMs, lastSavedPositionMs, now - lastSaveAtMs)) return
        lastSavedPositionMs = positionMs
        lastSaveAtMs = now
        val record = PlayProgress.buildRecord(source, title, episodeIndex, positionMs, durationMs, now)
        scope.launch {
            try {
                ApiService.savePlayRecord(record)
            } catch (_: Exception) {
                // 进度保存失败不打断播放
            }
        }
    }

    /** 切集 / 切源后调用，清空节流，使下一次保存不被 10s 窗口拦住。 */
    fun resetThrottle() {
        lastSavedPositionMs = -1L
        lastSaveAtMs = 0L
    }
}
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayProgressReporter.kt
git commit -m "feat(player): 新增 PlayProgressReporter 节流上报播放记录"
```

---

## Task 6: PlayerScreen 接入真实播放（舞台 + 进度来源）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt`

本任务把模拟播放替换为 ExoPlayer，但暂不处理自动下一集 / 错误 / 后台保存（留到 Task 7、8）。

- [ ] **Step 1: 新增 import**

在 `PlayerScreen.kt` 顶部 import 区加入：

```kotlin
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
```

- [ ] **Step 2: 在 `PlayerScreen` 顶部创建 controller / reporter / scope，替换模拟状态来源**

把现有这段（约 82–93 行）：

```kotlin
    var controlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(launch.startPositionMs.coerceIn(0, launch.durationMs)) }
    var currentEpisode by remember { mutableIntStateOf(launch.episodeIndex) }
    var currentSourceKey by remember { mutableStateOf(launch.selectedSourceKey) }
    var panelOpen by remember { mutableStateOf(false) }
    var panelTab by remember { mutableStateOf(PanelTab.Detail) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var flashTick by remember { mutableIntStateOf(0) }
    var flashVisible by remember { mutableStateOf(false) }

    val durationMs = launch.durationMs
```

替换为：

```kotlin
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { PlayerController(context) }
    val reporter = remember { PlayProgressReporter() }

    var controlsVisible by remember { mutableStateOf(true) }
    var currentEpisode by remember { mutableIntStateOf(launch.episodeIndex) }
    var currentSourceKey by remember { mutableStateOf(launch.selectedSourceKey) }
    var panelOpen by remember { mutableStateOf(false) }
    var panelTab by remember { mutableStateOf(PanelTab.Detail) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var flashTick by remember { mutableIntStateOf(0) }
    var flashVisible by remember { mutableStateOf(false) }

    // 播放状态全部来自 ExoPlayer
    val isPlaying = controller.isPlaying
    val positionMs = controller.positionMs
    val durationMs = if (controller.durationMs > 0) controller.durationMs else launch.durationMs
```

- [ ] **Step 3: 改写 `togglePlay` / `goNext` 并去掉模拟推进，加入首帧载入 + 轮询 + 释放**

把现有 `fun wake()` / `fun togglePlay()` / `fun openPanel()` / `fun goNext()`（约 100–103 行）替换为：

```kotlin
    fun wake() { controlsVisible = true; interactionTick++ }
    fun togglePlay() {
        // 即将从「播放」切到「暂停」时强制保存一次（spec：暂停为保存时机）
        if (controller.isPlaying) {
            val src = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
            src?.let { reporter.save(it, launch.title, currentEpisode, controller.positionMs, controller.durationMs, force = true) }
        }
        controller.togglePlay(); flashTick++; wake()
    }
    fun openPanel() { controlsVisible = true; panelTab = PanelTab.Detail; panelOpen = true }

    // 载入指定集；startMs 为续播位置（切集默认 0，进入时用 launch.startPositionMs）
    fun playEpisode(index: Int, startMs: Long) {
        val src = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
        val raw = src?.episodes?.getOrNull(index) ?: return
        scope.launch {
            val proxy = org.moontechlab.selenetv.service.AppDataService.getM3u8ProxyUrl()
            controller.load(PlayProgress.buildPlayUrl(raw, proxy), startMs)
        }
    }

    fun goNext() {
        if (hasNext) {
            val src = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
            src?.let { reporter.save(it, launch.title, currentEpisode, positionMs, durationMs, force = true) }
            reporter.resetThrottle()
            currentEpisode++
            panelOpen = false
            playEpisode(currentEpisode, 0)
            wake()
        }
    }
```

接着把现有「模拟播放推进」的 `LaunchedEffect`（约 108–114 行）整段：

```kotlin
    // 模拟播放推进
    LaunchedEffect(isPlaying, currentEpisode, currentSourceKey) {
        while (isPlaying && positionMs < durationMs) {
            kotlinx.coroutines.delay(1000)
            positionMs = (positionMs + 1000).coerceAtMost(durationMs)
        }
    }
```

替换为：

```kotlin
    // 首帧载入起播集（带进入时的续看位置）
    LaunchedEffect(Unit) {
        playEpisode(currentEpisode, launch.startPositionMs.coerceAtLeast(0))
    }

    // 周期把 ExoPlayer 位置同步到 State，并驱动 10s 定时保存
    LaunchedEffect(Unit) {
        while (true) {
            controller.syncProgress()
            kotlinx.coroutines.delay(500)
        }
    }
    LaunchedEffect(currentEpisode, currentSourceKey) {
        while (true) {
            kotlinx.coroutines.delay(10_000)
            val src = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
            src?.let { reporter.save(it, launch.title, currentEpisode, positionMs, durationMs, force = false) }
        }
    }

    // 退出 / 离开时释放播放器
    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }
```

- [ ] **Step 4: 进度条 `onToggle`/`onSeek` 接 controller，并在 BACK 退出时强制保存**

把现有 `PlayerScrubber(...)` 调用里的 `onSeek` 改为走 controller（约 195 行）：

```kotlin
                        onSeek = { controller.seekTo(it); wake() },
```

把现有 BACK 处理（约 141–142 行）：

```kotlin
    BackHandler(enabled = panelOpen) { panelOpen = false }
    BackHandler(enabled = !panelOpen) { onExit() }
```

替换为：

```kotlin
    BackHandler(enabled = panelOpen) { panelOpen = false }
    BackHandler(enabled = !panelOpen) {
        val src = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
        src?.let { reporter.save(it, launch.title, currentEpisode, positionMs, durationMs, force = true) }
        onExit()
    }
```

- [ ] **Step 5: 用 PlayerView 替换占位舞台**

把 `StagePlaceholder(...)` 调用（约 146 行）替换为真实视频 + 海报背景兜底：

```kotlin
        // 真实视频画面：海报背景垫底，PlayerView 叠在上层
        StagePlaceholder(poster = launch.heroInfo?.poster?.takeIf { it.isNotEmpty() } ?: launch.sources.firstOrNull()?.poster)
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = controller.exo
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
```

（保留 `StagePlaceholder` 函数本身，它现在作为载入/缓冲时的背景层。）

- [ ] **Step 6: 验证编译通过**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 设备手动验证**

Run: `./gradlew installDebug`，在 TV/模拟器上打开任一影片进入播放页。
Expected：能看到真实视频播放；进度条随播放前进；左右键可快退/快进 10s；OK 键暂停/继续；从详情页带续看记录进入时从上次位置起播。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt
git commit -m "feat(player): 接入 ExoPlayer 真实播放，替换模拟舞台与进度，10s 定时保存"
```

---

## Task 7: 切集 / 切源（带进度续播 + 逐集续看）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt`

- [ ] **Step 1: 进入时读取本源各集续看位置**

在 Task 6 创建的 `controller`/`reporter` 之后、`playEpisode` 之前，加入按 source+id 取记录的辅助状态。在 `val controller = remember { ... }` 与 `val reporter = remember { ... }` 之后加入：

```kotlin
    // 该 source+id 的播放记录（用于逐集续看：记录的 index 与目标集吻合时恢复其位置）
    var sourceRecord by remember { mutableStateOf<org.moontechlab.selenetv.model.PlayRecord?>(null) }
    LaunchedEffect(currentSourceKey) {
        val src = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
        sourceRecord = if (src != null) {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    org.moontechlab.selenetv.service.ApiService.getPlayRecords()["${src.source}+${src.id}"]
                }
            } catch (_: Exception) { null }
        } else null
    }

    // 目标集的续看起点（毫秒）：记录 index 命中该集才用，否则 0
    fun resumeForEpisode(index: Int): Long {
        val rec = sourceRecord ?: return 0L
        return if (rec.index - 1 == index) rec.playTime * 1000 else 0L
    }
```

- [ ] **Step 2: `onPickEpisode` 接 playEpisode（逐集续看）**

把现有 `onPickEpisode`（约 247 行）：

```kotlin
                    onPickEpisode = { idx -> currentEpisode = idx; positionMs = 0; isPlaying = true; panelOpen = false; wake() },
```

替换为：

```kotlin
                    onPickEpisode = { idx ->
                        val src = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
                        src?.let { reporter.save(it, launch.title, currentEpisode, positionMs, durationMs, force = true) }
                        reporter.resetThrottle()
                        currentEpisode = idx
                        panelOpen = false
                        playEpisode(idx, resumeForEpisode(idx))
                        wake()
                    },
```

- [ ] **Step 3: `onPickSource` 带进度续播**

把现有 `onPickSource`（约 248–251 行）：

```kotlin
                    onPickSource = { key ->
                        if (key != currentSourceKey) { currentSourceKey = key; currentEpisode = 0; positionMs = 0; isPlaying = true }
                        panelOpen = false; wake()
                    },
```

替换为：

```kotlin
                    onPickSource = { key ->
                        if (key != currentSourceKey) {
                            val oldSrc = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
                            oldSrc?.let { reporter.save(it, launch.title, currentEpisode, positionMs, durationMs, force = true) }
                            reporter.resetThrottle()
                            val carriedMs = positionMs
                            val newSrc = launch.sources.firstOrNull { sourceKey(it) == key }
                            // 带进度续播：新源集数足够则保持集号与位置，否则回落到第 1 集 0 秒
                            val newEpisode = currentEpisode.coerceIn(0, (newSrc?.episodes?.lastIndex ?: 0).coerceAtLeast(0))
                            val startMs = if (newEpisode == currentEpisode) carriedMs else 0L
                            currentSourceKey = key
                            currentEpisode = newEpisode
                            playEpisode(newEpisode, startMs)
                        }
                        panelOpen = false; wake()
                    },
```

注意：`playEpisode` 内部用 `currentSourceKey` 解析源，而本步骤先把 `currentSourceKey = key` 再调用 `playEpisode`，故新源 URL 正确。

- [ ] **Step 4: 验证编译通过**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 设备手动验证**

Run: `./gradlew installDebug`
Expected：
- 多集剧切集 → 从该集 0 秒开始（若该集即上次续看集，则从记录位置起）。
- 换源 → 新源同集从换源前的进度继续；新源集数不足时回落到第 1 集。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt
git commit -m "feat(player): 切集逐集续看 + 切源带进度续播，并在切换时强制保存"
```

---

## Task 8: 自动下一集 + 错误换源 + 后台保存

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt`

- [ ] **Step 1: 新增 import（生命周期 + 错误提示 UI）**

在 import 区加入：

```kotlin
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
```

- [ ] **Step 2: 自然结束自动下一集**

在 Task 6 的 `DisposableEffect(Unit) { onDispose { controller.release() } }` 之前，加入 `onEnded` 绑定：

```kotlin
    // 播放自然结束 → 自动下一集（最后一集则停止）
    DisposableEffect(currentEpisode, currentSourceKey, hasNext) {
        controller.onEnded = { if (hasNext) goNext() }
        onDispose { controller.onEnded = {} }
    }
```

- [ ] **Step 3: 播放错误 → 提示 + 自动唤出换源面板**

在上面之后加入：

```kotlin
    // 播放出错：唤出换源面板让用户改选其他源
    LaunchedEffect(controller.error) {
        if (controller.error != null) {
            controlsVisible = true
            panelTab = PanelTab.Sources
            panelOpen = true
        }
    }
```

然后在 `Box(modifier = modifier.fillMaxSize()...)` 内、`PlayPauseFlash(...)` 之后，加入错误横幅与缓冲指示：

```kotlin
        if (controller.isBuffering) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = Color.White)
            }
        }
        if (controller.error != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(text = "此源播放失败，请尝试换源", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.W600)
            }
        }
```

注意：`androidx.compose.material3.CircularProgressIndicator` 用全限定名调用，避免与 `androidx.tv.material3.Text` 的 import 冲突。

- [ ] **Step 4: App 退到后台时强制保存并暂停**

在 Task 6 的 `DisposableEffect(Unit) { onDispose { controller.release() } }` 之前，加入生命周期观察：

```kotlin
    // App 退到后台：强制保存 + 暂停播放
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                val src = launch.sources.firstOrNull { sourceKey(it) == currentSourceKey } ?: launch.selectedSource
                src?.let { reporter.save(it, launch.title, currentEpisode, controller.positionMs, controller.durationMs, force = true) }
                controller.exo.playWhenReady = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
```

- [ ] **Step 5: 验证编译通过**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 设备手动验证**

Run: `./gradlew installDebug`
Expected：
- 一集播完自动进入下一集；最后一集停止。
- 故意进入死链源（或断网）→ 居中提示「此源播放失败，请尝试换源」且换源面板自动弹出。
- 缓冲时显示转圈。
- 按 HOME 退后台再回来 → 已暂停，且 `/api/playrecords` 有最新记录。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt
git commit -m "feat(player): 自动下一集 + 错误自动换源 + 后台保存/暂停"
```

---

## Task 9: 全量测试与 Release 构建验证

**Files:** 无新增

- [ ] **Step 1: 跑全部单元测试**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL，含 `PlayProgressTest` 全过，无回归。

- [ ] **Step 2: Lint**

Run: `./gradlew lintDebug`
Expected: 无新增 error（Media3 相关的 newApi/警告可接受，但不应有阻断性 error）。

- [ ] **Step 3: Release 构建（验证 R8 + 资源压缩不破坏 Media3）**

Run: `./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL。若 R8 报 Media3 相关 missing class/反射问题，在 `app/proguard-rules.pro` 增加：

```proguard
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
```

加完重跑 `./gradlew assembleRelease` 确认通过。

- [ ] **Step 4: Commit（仅当改了 proguard）**

```bash
git add app/proguard-rules.pro
git commit -m "build: 为 Media3 补 R8 keep 规则"
```

---

## Self-Review 记录

- **Spec 覆盖**：依赖(Task1)、MediaHttp 信任证书 DataSource(Task3)、PlayerController(Task4)、PlayProgressReporter+10s+守卫(Task2/5/6)、舞台替换与进度来源(Task6)、m3u8 proxy(Task2 buildPlayUrl + Task6 playEpisode)、续看进入(Task6)、逐集续看(Task7)、切源带进度(Task7)、自动下一集(Task8)、错误自动换源(Task8)、各保存时机(暂停隐含于 ON_PAUSE/退出/切换；定时;结束;后台 Task6/7/8)、PlayRecord 字段映射(Task2)，均有对应任务。
- **占位符**：无 TBD/TODO，代码均完整给出。
- **类型一致性**：`PlayProgress.buildPlayUrl/buildRecord/shouldSave`、`PlayerController.load/seekTo/togglePlay/syncProgress/release/onEnded/isBuffering/error`、`PlayProgressReporter.save/resetThrottle` 在各任务中签名一致。
- **暂停保存**：`togglePlay` 在由播放转暂停时强制保存（Task6 Step3），覆盖 spec 的「暂停」时机。
