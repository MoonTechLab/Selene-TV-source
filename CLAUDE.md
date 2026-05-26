# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

Selene-TV 是一款 Android TV（Leanback）媒体中心应用，作为 MoonTV 类自托管服务的 TV 端客户端。主要职责是：浏览影视/动漫/综艺/直播元数据、聚合搜索、调度后端服务，并通过内嵌 Web 服务器接收手机遥控指令。视频播放采用 **ExoPlayer (Media3)**，详见「视频播放（选型决策）」一节。

`applicationId` 为 `org.moontechlab.selenetv`，单 Activity（`MainActivity`）+ Compose for TV 架构。

## 构建与开发命令

```bash
# 构建
./gradlew assembleDebug                # Debug APK（含 ABI 分包）
./gradlew assembleRelease              # Release APK（R8 + 资源压缩 + key.jks 签名）

# 安装到已连接的 TV/模拟器
./gradlew installDebug

# 测试
./gradlew test                         # 所有单元测试
./gradlew testDebugUnitTest            # Debug variant 单元测试
./gradlew connectedAndroidTest         # Instrumented 测试（需要设备）
./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.ExampleUnitTest.addition_isCorrect"

# 代码检查
./gradlew lint
./gradlew lintDebug

# 清理
./gradlew clean
```

工具链版本固定在 `gradle/libs.versions.toml` 与 `app/build.gradle.kts`：AGP 9.1 / Kotlin 2.2 / JDK 11 / minSdk 23 / target/compileSdk 36。Release 构建打开了 R8 与资源压缩，新增 model 类时需要在 `app/proguard-rules.pro` 的 `kotlinx.serialization` 段保持 `org.moontechlab.selenetv.model.**` 通配。Release APK 通过仓库根目录的 `key.jks` 签名，注意此文件已被提交，本地修改时不要替换。

ABI 分包仅产出 `arm64-v8a` 与 `armeabi-v7a` 两个 APK，不生成 universal 包；安装到设备时确认匹配的架构。

## 高层架构

### 启动链路
1. `SeleneApplication.onCreate()` 初始化 `AppDataService`（SharedPreferences），并在后台线程启动 `RemoteControlServer`（Ktor Netty）。
2. `SeleneApplication.newImageLoader()` 提供全局 Coil `ImageLoader`，使用一个**信任所有证书**的 OkHttp 客户端，并在 `ImageHeaderInterceptor` 中强行注入豆瓣的 `Referer` 头以绕过防盗链。豆瓣图片相关问题先看这里。
3. `MainActivity.onCreate()` 在 `super.onCreate` **之前**调用 `adjustDensity()`，以 1080p（1920px）为基准重写 `DisplayMetrics.density`，使 1dp 在 1080p 上等于 1px、在 4K 上等于 2px。**所有 UI 尺寸都按 1080p 设计稿写 dp**，不要为不同分辨率单独适配。
4. `MainActivity` 注册 `RemoteControlServer` 的按键 / 文本输入回调：按键被翻译成 `KeyEvent` 通过 `dispatchKeyEvent` 注入主线程，文本通过当前聚焦视图的 `InputConnection.commitText` 写入。
5. `setContent { HomeScreen() }` 是唯一的 Compose 入口。

### 屏幕结构（无 Navigation 库）
应用没有使用 Navigation Compose，整套界面通过 `HomeScreen` 内的 `selectedTabId` 状态机切换。`screen/tab/` 下的 8 个 Tab（`search/home/movies/series/anime/show/live/settings`）各自负责一个垂直滚动页面，`HomeScreen` 统筹：
- 为每个 Tab 维护独立 `FocusRequester`，并在 `TabSwitcher.onNavigateDown` 中根据 `currentTab` 把焦点显式下发到对应 Tab。新增 Tab 必须同时在 `HomeScreen` 添加 `FocusRequester`、`when` 分支与下发逻辑。
- 通过两层 `BackHandler` 处理返回键：焦点不在 TabSwitcher 时滚顶并回收焦点；在 TabSwitcher 且非 home 时切回 home；在 home 上才退出 Activity。这是 TV 体验的核心约定，修改返回行为前先理解这条链。
- 共享一个 `ScrollState` 给所有 Tab，使 `TabSwitcher` 能随内容滚动。
- `LoginScreen` 以全屏覆盖的方式绘制在 `TabSwitcher` 上方，登录关闭后通过自增 `settingsRefreshKey` 强制重建 `SettingsTab`。

### 服务层（`service/`）
绝大多数服务是 Kotlin `object` 单例，调用约定一致：
- **同步阻塞 + 抛 `ApiException`**：`ApiService` 的所有方法都是阻塞的，必须在 `withContext(Dispatchers.IO)` 里调用；失败抛 `ApiException(statusCode = ...)`，由调用方决定 UI 反馈。`DoubanService`、`BangumiService`、`SearchService`、`LiveService` 同样模式。
- **认证依赖 `AppDataService`**：`ApiService.buildUrl()` 会通过 `AppDataService.getCookiesSync()` 隐式校验登录状态，未登录直接抛 `ApiException`。修改鉴权逻辑要同时检查 `MainActivity.refreshLoginCookie()` 的启动自动刷新流程。
- **会话 token**：`AppDataService.init()` 每次启动生成一个新的 UUID 作为 `sessionToken`，被 `RemoteControlServer` 用于校验遥控指令的合法性（防止局域网内陌生设备劫持遥控）。
- **DoubanCacheService** 是少数非 object 的服务，采用双重检查锁的单例（持有 `Context`），实现内存 + 磁盘双层缓存与过期管理。豆瓣相关请求都应走它。
- **ContentFilterService** 负责关键词/分类过滤；**DownstreamService** 负责并发跨源聚合搜索（多 source 并行 `async`/`awaitAll`）。

### 远程遥控（`server/RemoteControlServer.kt`）
Ktor Netty 嵌入式 HTTP 服务器，从 `28256` 起逐端口探测可用端口，绑定 `0.0.0.0`，对外暴露 `/remote_control` 网页和 `/api/key`、`/api/text` 接口。手机扫描设备 IP + 端口的二维码（`SettingsTab` 使用 `qrcode-kotlin` 生成）后即可遥控。调试遥控相关问题时注意：
- 按键回调是异步的，最终在主线程通过 `dispatchKeyEvent` 注入，所以会和真实遥控走完全相同的焦点系统。
- 端口被占用时会自动向上探测最多 100 个端口，不要假定固定 28256。

### TV 焦点与 D-pad 约束（**必读**）
1. **不要使用** `androidx.tv.foundation.lazy.*`（`TvLazyRow`/`TvLazyColumn`/`TvLazyGrid`/`pivotOffsets`/`rememberTvLazyListState`）——这些 API 已弃用。一律使用 `androidx.compose.foundation` 的标准 `LazyRow` / `LazyColumn` / `LazyVerticalGrid`。
2. 需要让聚焦项停留在屏幕特定位置（例如水平居中、垂直 30% 偏移），用 `CompositionLocalProvider(LocalBringIntoViewSpec provides ...)` 包裹列表，自定义 `calculateScrollDistance`。
3. 可聚焦卡片使用 `androidx.tv.material3.Surface` 处理 focus 高亮（自动缩放/边框），**不要**用 `Box + clickable` 模拟。焦点缩放约定：`1.05x scale`、`0.6 damping` 的 spring 动画。
4. 设计稿基准 1080p，最小字号 11sp，主题色 `#4CAF50`，背景渐变 `#0a0a0a → #1a1a1a → #0f0f0f → #050505`，玻璃拟态层透明度 0.08。

### 文本测量
`TextMeasureProvider` + `TextMeasureContext` 提供异步文本宽度测量队列，用于支撑 `VideoCard` 等焦点时根据文字宽度伸缩的卡片。新增类似动态宽度组件时复用这套机制，不要直接在 composable 中反复 `Layout` 测量。

### 视频播放（选型决策）
**已采用 ExoPlayer (Media3)**，依赖见 `app/build.gradle.kts`（`media3-exoplayer` / `-exoplayer-hls` / `-ui` / `-datasource-okhttp`）。

- **场景**：以 m3u8 (HLS) 为主，少量 mp4，偶尔 FLV 直播；希望「给链接自动判别格式」。
- **选 ExoPlayer 的理由**：HLS/MP4 原生支持最强、HLS 自适应码率与低延迟直播调校成熟；冷启动快、内存基线低、APK 体积增量小（纯 Kotlin/Java 依赖，无 .so）；与 Compose for TV 集成有官方 `androidx.media3.ui.compose`（`PlayerSurface`）。
- **已知代价（实现时必须正视）**：ExoPlayer 的 FLV 是「二等公民」——**不支持 seek、不支持 HEVC-in-FLV（仅 H.264/AAC/MP3）、HTTP-FLV 直播不稳定**。MoonTV 系直播频道常为 HTTP-FLV 且可能是 HEVC，遇到放不了的 FLV 源属预期内的局限，不要当 bug 排查；若后续 FLV 直播成为强需求，需重新评估 mpv (libmpv)。
- **自动格式判别**：用 `DefaultMediaSourceFactory` 由 URI/Content-Type 推测；自托管服务返回 `application/octet-stream` 或无扩展名直播 URL 时可能判错，必要时按已知频道类型显式指定 `HlsMediaSource` 等。
- **自签证书**：播放器的 `DataSource`（HttpDataSource/OkHttp）必须沿用本项目「信任所有证书 + 跳过 hostname 校验」的约定，否则自签后端的流会拉取失败。

### 网络与安全
- `network_security_config.xml` 允许明文 HTTP（用户的自托管服务可能没有 TLS）。
- Coil/ImageLoader 与 `SearchService` 都使用「信任所有证书 + 跳过 hostname 校验」的 OkHttp/HttpsURLConnection，理由是用户后端常用自签证书。新增网络调用请遵循这一约定，否则用户的自签部署会请求失败。

## 设计上下文

### Users
Casual home viewers using a TV remote (D-pad navigation only). They want a relaxed, lean-back experience for browsing movies, TV shows, anime, documentaries, and live streams. The interface should feel effortless — content discovery should be smooth and intuitive without requiring technical knowledge.

### Brand Personality
**Premium, sleek, modern** — a high-end media center experience. Selene-TV should feel like a polished, self-hosted alternative to commercial streaming apps, combining the organizational power of Plex/Jellyfin with the visual refinement of Infuse/IINA.

### Aesthetic Direction
- **Visual tone:** Dark, cinematic, content-first. Let artwork and media shine against deep black backgrounds.
- **References:** Plex/Jellyfin (organized, functional media server feel) and Infuse/IINA (minimal, elegant with refined typography).
- **Anti-references:** Avoid looking cheap, cluttered, or overly colorful. No busy gradients, no aggressive branding, no information overload.
- **Theme:** Dark mode only. Premium glass-morphism with semi-transparent layers (white at 0.08 alpha). Vertical gradient backgrounds (#0a0a0a → #1a1a1a → #0f0f0f → #050505).

### Design Principles
1. **Content is king** — The UI exists to showcase media. Minimize chrome, maximize artwork and metadata visibility. Every element should serve content discovery.
2. **Effortless navigation** — D-pad interactions must feel natural and predictable. Focus states should be immediately obvious with smooth spring animations (1.05x scale, 0.6 damping).
3. **Premium restraint** — Use whitespace, subtle transparency, and refined typography rather than bold colors or decorative elements. The palette is essentially monochrome (white on black) with green (#4CAF50) as the sole accent.
4. **Consistency over cleverness** — Reuse the established component library (TabSwitcher, VideoCard, FilterSelector, RecommendSection). New UI should feel like it belongs, not stand out.
5. **Accessibility by default** — Maintain strong contrast ratios (white text on dark backgrounds), clear focus indicators, and readable font sizes (minimum 11sp) for comfortable 10-foot viewing.

### Technical Constraints
- **Platform:** Android TV (Leanback), minSdk 23, targetSdk 36
- **UI Framework:** Jetpack Compose for TV (androidx.tv.material3)
- **DO NOT** use deprecated `androidx.tv.foundation.lazy.*` components (TvLazyRow, TvLazyColumn). Use standard `LazyRow`, `LazyColumn` from `androidx.compose.foundation`.
- **Design base:** 1080p (1920×1080), density-independent pixels scale to 4K automatically.
- **Input:** D-pad remote only — no touch, no mouse. All interactions must work with directional navigation.
