# 双播放器后端（ExoPlayer / MPV）+ 解码模式设计

- 日期：2026-05-26
- 状态：已批准（待写实现计划）
- 影响范围：设置页、播放层抽象、构建配置

## 1. 目标

在设置页新增两个选项，并落地支撑它们的播放层抽象：

1. **播放器**：`ExoPlayer`（默认）/ `MPV`。选 ExoPlayer 时保持现有实现不变；选 MPV 时点播与直播均改用 libmpv 实现。
2. **解码**：`硬解`（默认）/ `软解`。解码模式对两种播放器都生效。

两个设置都复用项目既有的 `DoubanDataSource` 枚举 + `SourceSelectDialogContent` 选择弹窗模式，改动后在下一次起播时生效（无需重启）。

## 2. 非目标（明确不做）

- 不从源码自行编译 libmpv（直接用预编译 Maven 制品）。
- 不改动遥控/内嵌服务器层（`RemoteControlServer`）。
- 不改动自绘进度条（`PlayerScrubber`）与上拉面板（`PlayerPanel`）的交互/视觉。
- 不新增 x86 / x86_64 设备支持（仍仅 arm64-v8a + armeabi-v7a）。
- 不承诺 MPV 在 FLV/HEVC 直播上的稳定性——MPV 是为这类源提供"可能可放"的路径，而非保证。

## 3. 关键约束与已定决策

| 项 | 决策 | 理由 |
|---|---|---|
| MPV 库 | `dev.jdtech.mpv:libmpv:1.0.0`（Maven Central，仓库已配置） | 唯一成熟、含预编译 `.so` + `MPVLib` JNI 绑定的制品 |
| minSdk | **保持 23** | 库要求 minSdk 26；用 `tools:overrideLibrary` 强制合并 + 运行时门控，老设备不加载 `.so` |
| 运行时门控 | `Build.VERSION.SDK_INT < 26` 时强制 ExoPlayer，设置页隐藏 MPV 选项 | `.so` 仅在选中 MPV 时加载，<26 永不触碰原生代码，不会崩 |
| ABI | 仅 `arm64-v8a` / `armeabi-v7a`，由现有 `splits.abi` 限定 | splits 已只产 arm 包、自动排除 AAR 里 x86/x86_64 .so；**不能**再加 `ndk.abiFilters`（与 splits 冲突，AGP 报错） |
| 许可 | 接受 GPL 约束 | libmpv 制品的 `.so` 几乎确定为 GPL（Findroid 以 GPLv3 发布）；release APK 为签名分发 |
| 解码默认 | 硬解 | 与需求一致 |

### 3.1 许可义务（发布前必须完成）

在 App 内附带 mpv 与 FFmpeg 的许可证文本（作为可在设置/关于页查看的资源或随包文本）。这是接受 GPL 约束的前置交付项，发布前完成。

## 4. 架构

### 4.1 抽象层

引入统一接口 `VideoPlayer`，两套实现，通过工厂注入；点播与直播两个屏幕只依赖接口，不再直接持有 `ExoPlayer`。

```
 SettingsTab ──写──> AppDataService { playerType, decodeMode }
                          │ (sync getters)
                          ▼
 PlayerScreen ───────> PlayerFactory.create(ctx, dataSource/ua, decodeMode)
 LivePlayerScreen                 │  （SDK_INT<26 时强制 Exo）
                                  ▼
                      interface VideoPlayer
                      ┌───────────┴────────────┐
              ExoPlayerController        MpvPlayerController
              (现有逻辑 + 软解              (libmpv：MPVLib +
               MediaCodecSelector)         SurfaceView + hwdec)
```

### 4.2 `VideoPlayer` 接口（新增）

暴露当前两个屏幕已经在读的全部 Compose 状态与方法：

```kotlin
interface VideoPlayer {
    // 状态（Compose 可观测）
    val positionMs: Long
    val durationMs: Long
    val bufferedMs: Long
    val isPlaying: Boolean
    val isBuffering: Boolean
    val hasStarted: Boolean
    val error: String?            // 抽象后的错误：null=无错，非 null=有错（消息仅用于展示/日志）
    var onEnded: () -> Unit

    // 控制
    fun load(url: String, startMs: Long, playWhenReady: Boolean = true)
    fun seekTo(ms: Long)
    fun togglePlay()
    fun pause()              // 强制暂停（切后台用）
    fun seekToLiveEdge()     // 跳直播边缘；点播=seek 默认位置
    fun syncProgress()
    fun release()

    // 视频画面：各实现自行渲染（Exo→PlayerView；MPV→SurfaceView）
    @Composable fun VideoSurface(modifier: Modifier)
}
```

**错误抽象说明**：现有代码（`PlayerScreen` 行 200/201/357、`LivePlayerScreen` 行 122/123/133/251/269）只对 `controller.error` 做"是否为 null"判断，从不读取 `PlaybackException` 内容。因此把类型从 `PlaybackException?` 收敛为 `String?` 安全无损。

### 4.3 `ExoPlayerController`（由现有 `PlayerController` 重构而来）

- 保留现有全部逻辑（`exo`、监听器、`load/seekTo/togglePlay/syncProgress/release`、`hasStarted` 落记录判定）。
- `error`：在 `onPlayerError` 里赋值为 `e.errorCodeName`（或 `e.message`），`load()` 时清空——对外类型即接口的 `String?`。
- `VideoSurface`：把当前 `PlayerScreen` 内联的 `AndroidView { inflate(player_view_texture.xml) ... player = exo }` 搬进来。
- 解码：
  - 硬解 → 现有 `ExoPlayer.Builder`（不设 RenderersFactory）。
  - 软解 → `ExoPlayer.Builder(ctx).setRenderersFactory(DefaultRenderersFactory(ctx))`，并通过自定义 `MediaCodecSelector` 把 Google 软件解码器（名称以 `OMX.google.` 或 `c2.android.` 开头）排到候选列表最前。**无需任何 FFmpeg `.so`**，纯 Media3 即可。

### 4.4 `MpvPlayerController`（新增）

- 生命周期：`MPVLib.create(ctx)` → `setOptionString`（init 前选项）→ `MPVLib.init()` → `observeProperty` → 在 `SurfaceHolder.Callback.surfaceCreated` 里 `attachSurface` + `force-window=yes`；`surfaceDestroyed` 里 `vo=null` / `force-window=no` / `detachSurface`；`release()` 调用 `MPVLib.destroy()`。
- 状态映射：通过 mpv 的 `time-pos` / `duration` / `pause` / `paused-for-cache` / `eof-reached` 等属性观察回调，更新接口的 `positionMs/durationMs/isPlaying/isBuffering/hasStarted`；`MPVLib.eventProperty` 在工作线程回调，更新 Compose 状态须切回主线程。
- 自签证书与 UA（沿用本项目"信任所有证书 + 直播 UA"约定）：
  - `setOptionString("tls-verify", "no")`
  - 直播：`setOptionString("user-agent", resolveLiveUa(ua))`（与 `MediaHttp.resolveLiveUa` 同源，空 UA 回退 `AptvPlayer/1.4.10`）。
- `VideoSurface`：`AndroidView` 包一个 `SurfaceView`（参考 mpv-android `BaseMPVView` 模式，按本项目需要内联实现，不整包引入）。
- 解码：
  - 硬解 → init 前 `setOptionString("hwdec", "mediacodec-copy")`（**不用** direct `mediacodec`：调研指出直出表面在部分 TV 盒子上易坏）+ `setOptionString("hwdec-codecs", "h264,hevc,vp9,av1")`。
  - 软解 → `setOptionString("hwdec", "no")`。

### 4.5 `PlayerFactory`（新增）

```kotlin
object PlayerFactory {
    fun create(ctx: Context, dataSource/ua…, ): VideoPlayer {
        val decode = AppDataService.getDecodeModeSync()
        val type = AppDataService.getPlayerTypeSync()
        val effective = if (type == MPV && Build.VERSION.SDK_INT >= 26) MPV else EXOPLAYER
        return when (effective) {
            MPV -> MpvPlayerController(ctx, ua, decode)
            EXOPLAYER -> ExoPlayerController(ctx, dataSource, decode)
        }
    }
}
```

点播与直播屏幕从 `remember { PlayerController(...) }` 改为 `remember { PlayerFactory.create(...) }`，并把原先内联绑定 `controller.exo` 的 `AndroidView` 替换为 `controller.VideoSurface(Modifier.fillMaxSize())`。

## 5. 设置层

### 5.1 `AppDataService`（镜像 `DoubanDataSource` 模式）

```kotlin
enum class PlayerType(val key: String, val displayName: String) {
    EXOPLAYER("exoplayer", "ExoPlayer"), MPV("mpv", "MPV");
    companion object { fun fromKey/fromDisplayName(...) = ... ?: EXOPLAYER }
}
enum class DecodeMode(val key: String, val displayName: String) {
    HARDWARE("hardware", "硬解"), SOFTWARE("software", "软解");
    companion object { fun fromKey/fromDisplayName(...) = ... ?: HARDWARE }
}
```

- 新增 key 常量：`KEY_PLAYER_TYPE = "player_type"`、`KEY_DECODE_MODE = "decode_mode"`。
- 各自提供 `suspend save…(displayName)` / `suspend get…DisplayName()` 与 `get…KeySync()`（供 `PlayerFactory` 同步读取）。
- 默认：PlayerType=EXOPLAYER，DecodeMode=HARDWARE。

### 5.2 `SettingsTab`

- 在合适分组下（新增 `SectionLabel("播放")` 或并入现有相关分组）加两个 `SettingRow`：
  - `label="播放器"`，`value=playerType`，点击弹 `AnimatedSelectDialog` + `SourceSelectDialogContent`。
  - `label="解码"`，`value=decodeMode`，同上。
- 运行时门控：`Build.VERSION.SDK_INT < 26` 时，播放器选项的可选项只给 `[ExoPlayer]`（隐藏 MPV）；若已存读到 MPV 也按 ExoPlayer 显示。
- 状态加载沿用现有 `LaunchedEffect` + `homeRefreshKey` 重建机制。

## 6. 构建配置

- `gradle/libs.versions.toml`：新增 `libmpv = "1.0.0"` 及 library 别名。
- `app/build.gradle.kts`：
  - `dependencies { implementation(libs.libmpv) }`
  - `defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } }`
  - minSdk 维持 23。
- `AndroidManifest.xml`：`<uses-sdk tools:overrideLibrary="dev.jdtech.mpv"/>`（并确保引入 `tools` 命名空间）。
- `app/proguard-rules.pro`：保留 MPV JNI 绑定不被 R8 裁剪/混淆，例如 `-keep class dev.jdtech.mpv.** { *; }`（含 native 方法所在类）。
- 许可证文本资源随包。

## 7. 数据流与生效时机

1. 用户在设置页改"播放器/解码" → `AppDataService` 写 SharedPreferences。
2. 下次进入播放（点播 `launchPlayer` / 直播频道选择）时，屏幕通过 `PlayerFactory.create` 读取同步值并构造对应实现。
3. 已在播放中的会话不受影响（与现有"改设置下次生效"一致）。

## 8. 测试路径

- **Happy path**：ExoPlayer 硬解播 HLS（回归现状）；切 MPV 硬解播同一 HLS；切软解两端各播一次。
- **解码切换**：ExoPlayer 软解确认走 `c2.android.*`/`OMX.google.*`；MPV 软解确认 `hwdec=no`（mpv 日志 `Using hardware decoding` 不出现）。
- **直播 + UA**：MPV 播带自定义 UA 的直播源，确认 UA 注入；自签证书源确认 `tls-verify=no` 生效。
- **错误路径**：死链 → 接口 `error` 非 null，错误覆盖层正常出现（两端一致）。
- **门控/兼容**：API 23–25 设备设置页无 MPV 选项；即便 prefs 残留 MPV，工厂回退 ExoPlayer 且不加载 `.so`、不崩。
- **续播/落记录**：MPV 起播后 `hasStarted=true`、退出/暂停落进度，死链不落记录（与 Exo 行为对齐）。

## 9. 回滚

- 设置默认 ExoPlayer + 硬解，等于现状；用户不主动选 MPV 即零行为变化。
- 代码回滚：抽象层为新增/重构，回退到单一 `PlayerController` 即可；无数据迁移（仅多两个 SharedPreferences key，留存无害）。

## 10. 风险与缓解

| 风险 | 缓解 |
|---|---|
| MPV 硬解在特定 TV 盒子不稳 | 硬解默认用 `mediacodec-copy` 而非 direct；用户可切软解；真机验证 |
| `.so` 让所有设备 APK 增大 ~8–12MB/ABI | 仅 arm 两个 ABI；接受体积代价（已与"无 .so"取舍权衡） |
| `MPVLib` 回调线程与 Compose 状态 | 属性回调切主线程后再更新 State |
| GPL 合规 | 随包附许可证文本，发布前完成 |

## 11. 影响文件清单（约 12 个 + 1 原生依赖）

新增：`VideoPlayer.kt`、`PlayerFactory.kt`、`MpvPlayerController.kt`、许可证文本资源。
重构：`PlayerController.kt` → `ExoPlayerController.kt`（实现接口 + 软解 selector）。
修改：`PlayerScreen.kt`、`LivePlayerScreen.kt`（工厂 + `VideoSurface` + 抽象 error）、`AppDataService.kt`、`SettingsTab.kt`、`app/build.gradle.kts`、`gradle/libs.versions.toml`、`AndroidManifest.xml`、`app/proguard-rules.pro`。
