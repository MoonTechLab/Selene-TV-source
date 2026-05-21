# 影视详情页 + Navigation 迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 引入 Navigation Compose 库迁移现有 HomeScreen/LoginScreen，并新增独立的影视详情页 DetailScreen。详情页以豆瓣为元数据源、从 SearchService 聚合各源播放信息，UI 1:1 复刻已确认截图。

**Architecture:** 单 Activity Compose。`MainActivity → setContent { AppNavHost() }`。`NavHost` 三条类型化路由（`HomeRoute`/`LoginRoute`/`DetailRoute`），转场动画通过 `enterTransition/exitTransition` 提供。DetailScreen 内部用 `mutableStateOf + remember` 持有 UI 状态（不引入 ViewModel），四路数据并发加载（豆瓣详情、聚合源搜索、播放记录、收藏）。源筛选拆出为纯函数 `SourceFilter.matchesContent` 便于单元测试。

**Tech Stack:** Kotlin 2.2 · Compose BOM 2024.09 · androidx.tv.material3 1.1.0-alpha01 · androidx.navigation:navigation-compose 2.8.5 · kotlinx-serialization-json 1.7.3（已有）· Coil 2.7（已有）

**Spec：** [`docs/superpowers/specs/2026-05-22-detail-screen-design.md`](../specs/2026-05-22-detail-screen-design.md)

**Plan-vs-Spec 微调说明：**
- ApiService 中「取消收藏」方法名是 `unfavorite(source, id)`（spec 写的是 `removeFavorite`，按代码实际为准）
- 收藏数据键为 `"$source+$id"` 对，不包含 doubanId。因此 ♡ 按钮的可见条件改为 `selectedSourceKey != null`（spec 中「与 doubanId 绑定」的描述在实现上不成立，因为收藏 API 是 per source+id 的）；`isFavorite` 派生为 `selectedSourceKey ∈ favoritesKeys`

**File map:**
- 修改 `gradle/libs.versions.toml` — 新增 navigation-compose 版本与依赖坐标
- 修改 `app/build.gradle.kts` — 引入依赖
- 修改 `app/src/main/java/org/moontechlab/selenetv/MainActivity.kt` — `setContent { AppNavHost() }`
- 新增 `app/src/main/java/org/moontechlab/selenetv/screen/Routes.kt` — 类型化路由
- 新增 `app/src/main/java/org/moontechlab/selenetv/screen/AppNavHost.kt` — NavHost + 转场动画
- 修改 `app/src/main/java/org/moontechlab/selenetv/screen/HomeScreen.kt` — 去除 LoginScreen 覆盖状态，接 navController
- 修改 `app/src/main/java/org/moontechlab/selenetv/screen/LoginScreen.kt` — 去除 visible/animateIn，接 navController
- 修改 `app/src/main/java/org/moontechlab/selenetv/screen/tab/{HomeTab,MovieTab,TvTab,AnimeTab,ShowTab,SettingsTab}.kt` — onClick 接 navController
- 新增 `app/src/main/java/org/moontechlab/selenetv/screen/detail/SourceFilter.kt` — 纯函数源筛选
- 新增 `app/src/test/java/org/moontechlab/selenetv/screen/detail/SourceFilterTest.kt` — 单元测试
- 新增 `app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailUiState.kt` — UI 状态数据类
- 新增 `app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt` — 顶层 Composable + 数据编排
- 新增 `app/src/main/java/org/moontechlab/selenetv/screen/detail/MoonLoadingOverlay.kt` — 月牙加载覆盖层
- 新增 `app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailHero.kt` — Hero（左列 + 海报）
- 新增 `app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailActionButtons.kt` — 动作按钮行
- 新增 `app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailSourcesSection.kt` — 播放源 LazyRow
- 新增 `app/src/main/java/org/moontechlab/selenetv/screen/detail/EpisodePickerDialog.kt` — 剧集对话框

---

## Phase 1：Navigation 基础

### Task 1: Add navigation-compose dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add version & library to `libs.versions.toml`**

在 `[versions]` 块末尾追加：
```toml
navigationCompose = "2.8.5"
```

在 `[libraries]` 块末尾追加：
```toml
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
```

- [ ] **Step 2: Reference in `app/build.gradle.kts`**

在 `dependencies` 块中（紧跟 `implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")` 之后）追加：
```kotlin
implementation(libs.androidx.navigation.compose)
```

- [ ] **Step 3: Verify build**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "$(cat <<'EOF'
新增 androidx.navigation:navigation-compose 2.8.5 依赖

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Define type-safe routes

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/Routes.kt`

- [ ] **Step 1: Create Routes.kt**

```kotlin
package org.moontechlab.selenetv.screen

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object LoginRoute

@Serializable
data class DetailRoute(
    val doubanId: String,
    val title: String,
    val poster: String,
    val year: String,
)
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/Routes.kt
git commit -m "$(cat <<'EOF'
新增类型化 Navigation 路由定义

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Create AppNavHost with HomeRoute only

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/AppNavHost.kt`

本任务先只把 HomeRoute 接入，LoginRoute/DetailRoute 在后续任务里渐进添加，便于每步验证。

- [ ] **Step 1: Create AppNavHost.kt**

```kotlin
package org.moontechlab.selenetv.screen

import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = HomeRoute,
    ) {
        composable<HomeRoute>(
            enterTransition = {
                fadeIn(animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f)) +
                        scaleIn(initialScale = 0.95f, animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f))
            },
            exitTransition = {
                fadeOut(animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f)) +
                        scaleOut(targetScale = 0.95f, animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f))
            },
        ) {
            HomeScreen(navController = navController)
        }
    }
}
```

注意：HomeScreen 此刻还没加 `navController` 参数，下一步会接（先让本步骤暂时编译失败，由 Task 4/5 修复）。

- [ ] **Step 2: Skip compile here**（HomeScreen 签名未更新；继续 Task 4 后再统一编译验证）

---

### Task 4: Switch MainActivity to AppNavHost and update HomeScreen signature

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/MainActivity.kt:65-67`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/HomeScreen.kt`

本任务把 HomeScreen 接 `navController` 但暂不移除 LoginScreen 覆盖逻辑（Login 迁移留到 Task 5/6）；目的是先让导航骨架跑起来。

- [ ] **Step 1: Update MainActivity setContent**

把 `MainActivity.kt` 的：
```kotlin
setContent {
    HomeScreen()
}
```
改为：
```kotlin
setContent {
    AppNavHost()
}
```

并把原来 `import org.moontechlab.selenetv.screen.HomeScreen` 改为 `import org.moontechlab.selenetv.screen.AppNavHost`。

- [ ] **Step 2: Add navController parameter to HomeScreen**

`HomeScreen.kt` 的签名：
```kotlin
@Composable
fun HomeScreen() {
```
改为：
```kotlin
@Composable
fun HomeScreen(navController: androidx.navigation.NavController) {
```

同时在 `HomeScreenPreview()` 内传入空实现：
```kotlin
@Preview(device = Devices.TV_1080p)
@Composable
fun HomeScreenPreview() {
    HomeScreen(navController = androidx.navigation.compose.rememberNavController())
}
```

`HomeScreen` body 暂时不调用 `navController`（保持现有 LoginScreen 覆盖逻辑），下个任务再迁移。

- [ ] **Step 3: Verify compile + install**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL

如果你能连接 TV 或模拟器：
```bash
./gradlew installDebug
```
启动 app，验证：首页正常显示，能切换 Tab，能进入设置点登录看到 LoginScreen（沿用旧覆盖逻辑）。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/MainActivity.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/HomeScreen.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/AppNavHost.kt
git commit -m "$(cat <<'EOF'
MainActivity 切换到 AppNavHost，HomeScreen 接入 NavController

LoginScreen 暂时保留旧的覆盖逻辑，下个任务迁移。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2：迁移 LoginScreen 到 Navigation

### Task 5: Refactor LoginScreen to use NavController

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/LoginScreen.kt`

- [ ] **Step 1: Update signature & drop visible/animateIn**

把 `LoginScreen.kt` 的：
```kotlin
@Composable
fun LoginScreen(
    visible: Boolean,
    initialServerUrl: String = "",
    initialUsername: String = "",
    onDismiss: () -> Unit
) {
    var showScreen by remember { mutableStateOf(false) }
    var animateIn by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            showScreen = true
            delay(16)
            animateIn = true
        } else {
            animateIn = false
            delay(150)
            showScreen = false
        }
    }

    if (showScreen) {
        LoginScreenWithAnimation(
            initialServerUrl = initialServerUrl,
            initialUsername = initialUsername,
            animateIn = animateIn,
            onDismiss = onDismiss
        )
    }
}
```

整段替换为：
```kotlin
@Composable
fun LoginScreen(navController: androidx.navigation.NavController) {
    val context = LocalContext.current
    var initialServerUrl by remember { mutableStateOf("") }
    var initialUsername by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        initialServerUrl = AppDataService.getServerUrl() ?: ""
        initialUsername = AppDataService.getUsername() ?: ""
    }

    BackHandler { navController.popBackStack() }

    val gradientBrush = Brush.verticalGradient(
        0.0f to Color(0xFF0a0a0a),
        0.4f to Color(0xFF1a1a1a),
        0.7f to Color(0xFF0f0f0f),
        1.0f to Color(0xFF050505)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = gradientBrush),
        contentAlignment = Alignment.Center,
    ) {
        LoginScreenContent(
            initialServerUrl = initialServerUrl,
            initialUsername = initialUsername,
            onDismiss = { navController.popBackStack() },
        )
    }
}
```

- [ ] **Step 2: Delete `LoginScreenWithAnimation` function entirely**

整个 `private fun LoginScreenWithAnimation(...)` 函数（约第 105-170 行）删除——动画交给 NavHost 转场。

- [ ] **Step 3: Verify compile**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL（HomeScreen 还引用旧签名，仍会报错——下个任务一起改）

如果只想验证 LoginScreen 本身：暂时把 HomeScreen 里调用 LoginScreen 的整块用 `// TODO` 注释掉，验证完恢复。或者直接跳到 Step 4，与 Task 6 一起编译验证。

- [ ] **Step 4: Don't commit yet**（与 Task 6 一起提交，避免编译断点）

---

### Task 6: Wire LoginRoute in NavHost and migrate HomeScreen

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/AppNavHost.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/HomeScreen.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/tab/SettingsTab.kt`（仅 onShowLoginScreen 调用点；signature 不变）

- [ ] **Step 1: Add LoginRoute composable to AppNavHost**

在 `AppNavHost.kt` 的 `NavHost { ... }` 内，紧跟 `composable<HomeRoute>` 之后追加：
```kotlin
composable<LoginRoute>(
    enterTransition = {
        fadeIn(animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)) +
                scaleIn(initialScale = 0.8f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f))
    },
    exitTransition = {
        fadeOut(animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f)) +
                scaleOut(targetScale = 0.8f, animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f))
    },
) {
    LoginScreen(navController = navController)
}
```

- [ ] **Step 2: Remove LoginScreen overlay state from HomeScreen**

在 `HomeScreen.kt` 内删除以下状态变量与赋值：
```kotlin
var showLoginScreen by remember { mutableStateOf(false) }
var loginInitialServerUrl by remember { mutableStateOf("") }
var loginInitialUsername by remember { mutableStateOf("") }
var settingsRefreshKey by remember { mutableIntStateOf(0) }
```

替换 `settingsRefreshKey` 为通用 `homeRefreshKey`：
```kotlin
var homeRefreshKey by remember { mutableIntStateOf(0) }

// 每次返回 HomeRoute 时自增，让 Tab 重拉数据
LaunchedEffect(navController) {
    navController.currentBackStackEntryFlow.collect { entry ->
        if (entry.destination.hasRoute<HomeRoute>()) {
            homeRefreshKey++
        }
    }
}
```

需要新增 import：
```kotlin
import androidx.navigation.NavDestination.Companion.hasRoute
```

删除 `LaunchedEffect(Unit) { loginInitialServerUrl = ...; loginInitialUsername = ... }` 块（这部分逻辑现在搬到 LoginScreen 内部）。

删除 HomeScreen 文件底部的 `LoginScreen(visible = showLoginScreen, ...)` 整段调用。

- [ ] **Step 3: Update SettingsTab usage in HomeScreen**

把 HomeScreen 里 SettingsTab 的渲染部分：
```kotlin
"settings" -> key(settingsRefreshKey) {
    SettingsTab(
        focusRequester = settingsFocusRequester,
        scrollState = scrollState,
        onShowLoginScreen = {
            coroutineScope.launch {
                loginInitialServerUrl = AppDataService.getServerUrl() ?: ""
                loginInitialUsername = AppDataService.getUsername() ?: ""
                showLoginScreen = true
            }
        }
    )
}
```

替换为：
```kotlin
"settings" -> key(homeRefreshKey) {
    SettingsTab(
        focusRequester = settingsFocusRequester,
        scrollState = scrollState,
        onShowLoginScreen = {
            navController.navigate(LoginRoute)
        }
    )
}
```

- [ ] **Step 4: Verify compile + manual test**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL

安装到设备：
```bash
./gradlew installDebug
```

验证：
- 首页正常显示，能切换 Tab
- 设置 Tab 点登录 → 进入 LoginScreen（NavHost 转场，淡入 + 0.8→1.0 缩放，时长约 200ms）
- 按返回键 → 回到首页设置 Tab
- 登录成功后 → 回到首页，SettingsTab 显示已登录状态（说明 homeRefreshKey 机制生效）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/AppNavHost.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/HomeScreen.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/LoginScreen.kt
git commit -m "$(cat <<'EOF'
LoginScreen 迁移到 Navigation 路由

去除 visible/animateIn 内置动画状态，进出转场交给 NavHost。
HomeScreen 用 homeRefreshKey 机制感知返回，触发 Tab 重拉。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 3：DetailScreen 基础

### Task 7: SourceFilter pure logic + unit tests

**Files:**
- Create: `app/src/test/java/org/moontechlab/selenetv/screen/detail/SourceFilterTest.kt`
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/detail/SourceFilter.kt`

TDD：先写测试，跑红，再实现。

- [ ] **Step 1: Create test directory if missing**

```bash
mkdir -p app/src/test/java/org/moontechlab/selenetv/screen/detail
```

- [ ] **Step 2: Write failing test**

`app/src/test/java/org/moontechlab/selenetv/screen/detail/SourceFilterTest.kt`:
```kotlin
package org.moontechlab.selenetv.screen.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.moontechlab.selenetv.model.SearchResult

class SourceFilterTest {
    private fun result(
        title: String = "长安的荔枝",
        year: String = "2025",
        doubanId: Long? = null,
        source: String = "src",
        id: String = "1",
        typeName: String? = null,
    ) = SearchResult(
        id = id,
        title = title,
        poster = "",
        episodes = listOf("ep1"),
        episodesTitles = listOf("第1集"),
        source = source,
        sourceName = source,
        year = year,
        typeName = typeName,
        doubanId = doubanId,
    )

    @Test
    fun `strong match by douban id passes`() {
        val r = result(doubanId = 12345)
        assertTrue(SourceFilter.matchesContent(r, doubanId = "12345", title = "长安的荔枝", year = "2025"))
    }

    @Test
    fun `wrong douban id is rejected even if title matches`() {
        val r = result(doubanId = 99999, title = "长安的荔枝", year = "2025")
        assertFalse(SourceFilter.matchesContent(r, doubanId = "12345", title = "长安的荔枝", year = "2025"))
    }

    @Test
    fun `null douban id falls back to title and year match`() {
        val r = result(doubanId = null, title = "长安的荔枝", year = "2025")
        assertTrue(SourceFilter.matchesContent(r, doubanId = "12345", title = "长安的荔枝", year = "2025"))
    }

    @Test
    fun `null douban id with different title is rejected`() {
        val r = result(doubanId = null, title = "其他作品", year = "2025")
        assertFalse(SourceFilter.matchesContent(r, doubanId = "12345", title = "长安的荔枝", year = "2025"))
    }

    @Test
    fun `null douban id with empty route year accepts any year`() {
        val r = result(doubanId = null, title = "长安的荔枝", year = "2024")
        assertTrue(SourceFilter.matchesContent(r, doubanId = "12345", title = "长安的荔枝", year = ""))
    }

    @Test
    fun `null douban id with empty result year accepts any route year`() {
        val r = result(doubanId = null, title = "长安的荔枝", year = "")
        assertTrue(SourceFilter.matchesContent(r, doubanId = "12345", title = "长安的荔枝", year = "2025"))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.screen.detail.SourceFilterTest"
```
Expected: FAIL — `SourceFilter` 类不存在（compilation error）

- [ ] **Step 4: Implement SourceFilter**

`app/src/main/java/org/moontechlab/selenetv/screen/detail/SourceFilter.kt`:
```kotlin
package org.moontechlab.selenetv.screen.detail

import org.moontechlab.selenetv.model.SearchResult

/**
 * 详情页源筛选规则：
 * 1. 强匹配：result.doubanId == route.doubanId
 * 2. 弱匹配兜底：result 没带 doubanId 时，看 title 与 year
 * 3. 明确不匹配：result 带 doubanId 但与 route 不等 → 拒绝
 */
object SourceFilter {
    fun matchesContent(
        result: SearchResult,
        doubanId: String,
        title: String,
        year: String,
    ): Boolean {
        val resultDoubanId = result.doubanId?.toString()
        return when {
            resultDoubanId == doubanId -> true
            resultDoubanId == null -> {
                val titleOk = result.title == title
                val yearOk = result.year == year || year.isEmpty() || result.year.isEmpty()
                titleOk && yearOk
            }
            else -> false
        }
    }
}
```

- [ ] **Step 5: Run test to verify pass**

```bash
./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.screen.detail.SourceFilterTest"
```
Expected: PASS — 6 tests passed

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/detail/SourceFilter.kt \
        app/src/test/java/org/moontechlab/selenetv/screen/detail/SourceFilterTest.kt
git commit -m "$(cat <<'EOF'
新增 SourceFilter 源筛选纯函数 + 单元测试

强匹配 doubanId，弱匹配兜底 title+year，明确不一致拒绝。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: DetailUiState data class

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailUiState.kt`

- [ ] **Step 1: Create file**

```kotlin
package org.moontechlab.selenetv.screen.detail

import org.moontechlab.selenetv.model.DoubanMovieDetails
import org.moontechlab.selenetv.model.PlayRecord
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.screen.DetailRoute
import org.moontechlab.selenetv.service.SearchProgress

/**
 * 详情页 UI 状态。单 Composable 内 mutableStateOf 持有。
 */
data class DetailUiState(
    val route: DetailRoute,
    val details: DoubanMovieDetails? = null,
    val detailsLoading: Boolean = true,
    val detailsError: String? = null,
    val sources: List<SearchResult> = emptyList(),
    val sourcesProgress: SearchProgress? = null,
    val sourcesComplete: Boolean = false,
    val selectedSourceKey: String? = null,
    val playRecords: Map<String, PlayRecord> = emptyMap(),
    val favoriteKeys: Set<String> = emptySet(),
    val episodePickerVisible: Boolean = false,
) {
    val selectedSource: SearchResult?
        get() = sources.firstOrNull { "${it.source}+${it.id}" == selectedSourceKey }

    val selectedPlayRecord: PlayRecord?
        get() = selectedSourceKey?.let { playRecords[it] }

    val isSelectedFavorite: Boolean
        get() = selectedSourceKey != null && selectedSourceKey in favoriteKeys
}

fun sourceKey(result: SearchResult): String = "${result.source}+${result.id}"
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailUiState.kt
git commit -m "$(cat <<'EOF'
新增 DetailUiState 数据类

包含派生属性 selectedSource / selectedPlayRecord / isSelectedFavorite。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: DetailScreen scaffold + DetailRoute composable

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/AppNavHost.kt`

本任务先建最小化 DetailScreen，仅显示路由参数（doubanId / title），用来打通点击 → 导航 → 返回链路。后续任务再渐进加内容。

- [ ] **Step 1: Create DetailScreen.kt minimal scaffold**

```kotlin
package org.moontechlab.selenetv.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.tv.material3.Text

@Composable
fun DetailScreen(navController: NavController, route: DetailRoute) {
    BackHandler { navController.popBackStack() }

    val gradientBrush = Brush.verticalGradient(
        0.0f to Color(0xFF0a0a0a),
        0.4f to Color(0xFF1a1a1a),
        0.7f to Color(0xFF0f0f0f),
        1.0f to Color(0xFF050505),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = gradientBrush),
        contentAlignment = Alignment.Center,
    ) {
        Column {
            Text("DetailScreen scaffold", color = Color.White, fontSize = 24.sp)
            Text("doubanId = ${route.doubanId}", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Text("title = ${route.title}", color = Color.White, fontSize = 14.sp)
            Text("year = ${route.year}", color = Color.White, fontSize = 14.sp)
        }
    }
}
```

- [ ] **Step 2: Add DetailRoute composable to AppNavHost**

在 `AppNavHost.kt` 的 `NavHost { ... }` 内，紧跟 `composable<LoginRoute>` 之后追加：
```kotlin
composable<DetailRoute>(
    enterTransition = {
        fadeIn(animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)) +
                scaleIn(initialScale = 0.95f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f))
    },
    exitTransition = {
        fadeOut(animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f)) +
                scaleOut(targetScale = 0.95f, animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f))
    },
) { backStackEntry ->
    val route = backStackEntry.toRoute<DetailRoute>()
    DetailScreen(navController = navController, route = route)
}
```

需要新增 import：
```kotlin
import androidx.navigation.toRoute
```

- [ ] **Step 3: Verify compile**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/AppNavHost.kt
git commit -m "$(cat <<'EOF'
新增 DetailScreen 最小骨架与 DetailRoute 路由

仅显示路由参数，后续任务渐进添内容。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Wire Douban card click handlers across all Tabs

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/HomeScreen.kt`（把 navController 传给各 Tab）
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/tab/HomeTab.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/tab/MovieTab.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/tab/TvTab.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/tab/AnimeTab.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/tab/ShowTab.kt`

策略：每个 Tab 接受一个 `onVideoSelected: (VideoInfo) -> Unit` 或 `onDoubanClick: (DoubanMovie) -> Unit` 回调；具体跳转逻辑放在 HomeScreen 内组装。这样 Tab 自身不直接持有 navController，便于预览。

- [ ] **Step 1: Add navigate helper to HomeScreen**

在 `HomeScreen.kt` 的 composable 内（在 `homeRefreshKey` 那段之后）添加：
```kotlin
val openDetail: (VideoInfo) -> Unit = remember(navController) {
    { video ->
        val doubanId = video.doubanId
        if (doubanId != null) {
            navController.navigate(
                DetailRoute(
                    doubanId = doubanId,
                    title = video.title,
                    poster = video.cover,
                    year = video.year,
                )
            )
        }
    }
}
```

需要的 imports（在文件顶部加）：
```kotlin
import org.moontechlab.selenetv.model.VideoInfo
```

- [ ] **Step 2: Update HomeTab signature & onVideoClick**

`HomeTab.kt`：
- 新增参数 `onVideoSelected: (VideoInfo) -> Unit = {}`（紧跟 `scrollState` 之后）
- 把所有 `onVideoClick = { video -> android.util.Log.d(...) }` 改为 `onVideoClick = onVideoSelected`

HomeScreen 内调用处：
```kotlin
"home" -> HomeTab(
    focusRequester = homeTabFocusRequester,
    scrollState = scrollState,
    onVideoSelected = openDetail,
)
```

- [ ] **Step 3: Update MovieTab signature**

`MovieTab.kt`：参数 `onMovieClick: (DoubanMovie) -> Unit = {}` 已存在。把它从 `DoubanMovie` 改为 `(VideoInfo) -> Unit`：
- 签名改为 `onVideoSelected: (VideoInfo) -> Unit = {}`
- 内部 `MovieGridRow` 中 `onClick = { onMovieClick(movie) }` 改为 `onClick = { onVideoSelected(movie.toVideoInfo()) }`

HomeScreen 内调用：
```kotlin
"movies" -> MovieTab(
    categoryFocusRequester = movieCategoryFocusRequester,
    scrollState = scrollState,
    onVideoSelected = openDetail,
)
```

- [ ] **Step 4: Same treatment for TvTab/AnimeTab/ShowTab**

每个 Tab：
1. 把现有 `onMovieClick: (DoubanMovie) -> Unit` 改为 `onVideoSelected: (VideoInfo) -> Unit`
2. 卡片 `onClick = { onVideoSelected(movie.toVideoInfo()) }`
3. HomeScreen 调用处传 `openDetail`

如果某个 Tab 现在没有 onMovieClick 参数（比如 LiveTab/SettingsTab），跳过；只改有豆瓣卡片的 Tab：HomeTab/MovieTab/TvTab/AnimeTab/ShowTab。

- [ ] **Step 5: Refresh data on homeRefreshKey for HomeTab**

在 HomeTab.kt 内增加 `homeRefreshKey: Int = 0` 参数并：
```kotlin
LaunchedEffect(homeRefreshKey) {
    if (homeRefreshKey > 0) {  // 跳过首次（已由原有 LaunchedEffect(Unit) 触发）
        loadPlayRecordsAndFavorites()
    }
}
```

HomeScreen 传 `homeRefreshKey = homeRefreshKey`。

Movie/Tv/Anime/Show Tab 的 Tab 数据是分类列表（不随收藏/播放记录变），无需 homeRefreshKey 接入（保持简洁）。

- [ ] **Step 6: Verify compile + manual test**

```bash
./gradlew assembleDebug
./gradlew installDebug
```

验证：
- 首页热门电影/剧集/综艺中点任一卡片 → 进入 DetailScreen scaffold，显示 doubanId/title/year
- 在 movies/series/anime/show Tab 点卡片 → 同样进入 scaffold
- 按返回键 → 回到原 Tab，焦点正确
- 收藏夹/播放历史卡片暂时只 log（doubanId 为 null 的不接，按设计）

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/HomeScreen.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/tab/HomeTab.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/tab/MovieTab.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/tab/TvTab.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/tab/AnimeTab.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/tab/ShowTab.kt
git commit -m "$(cat <<'EOF'
豆瓣卡片接入 DetailScreen 跳转

各 Tab 改用 onVideoSelected(VideoInfo) 统一回调，HomeScreen 内
组装 navigate(DetailRoute)，doubanId 为 null 的卡片本轮不接。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 4：DetailScreen 数据层

### Task 11: Data loading orchestration in DetailScreen

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt`

本任务在 scaffold 基础上接入四路并发数据加载，结果暂时通过简单的文本展示（下个阶段才有真正 UI）。

- [ ] **Step 1: Rewrite DetailScreen with data layer**

整个文件替换为：
```kotlin
package org.moontechlab.selenetv.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.screen.detail.DetailUiState
import org.moontechlab.selenetv.screen.detail.SourceFilter
import org.moontechlab.selenetv.screen.detail.sourceKey
import org.moontechlab.selenetv.service.ApiResponse
import org.moontechlab.selenetv.service.ApiService
import org.moontechlab.selenetv.service.ContentFilterService
import org.moontechlab.selenetv.service.DoubanService
import org.moontechlab.selenetv.service.SearchEvent
import org.moontechlab.selenetv.service.SearchService

@Composable
fun DetailScreen(navController: NavController, route: DetailRoute) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doubanService = remember { DoubanService.getInstance(context) }

    var state by remember { mutableStateOf(DetailUiState(route = route)) }

    BackHandler { navController.popBackStack() }

    // 拉豆瓣详情
    LaunchedEffect(route.doubanId) {
        state = state.copy(detailsLoading = true, detailsError = null)
        when (val resp = doubanService.getDoubanDetails(route.doubanId)) {
            is ApiResponse.Success -> state = state.copy(details = resp.data, detailsLoading = false)
            is ApiResponse.Error   -> state = state.copy(detailsError = resp.message, detailsLoading = false)
        }
    }

    // 启动搜索 + 订阅事件流，按 doubanId/title/year 筛选
    LaunchedEffect(route.doubanId) {
        SearchService.stopSearch() // 清掉上一次的状态
        // 与事件流的订阅必须在 startSearch 之前先挂上，避免漏掉 Results
        val collectorJob = scope.launch {
            SearchService.eventFlow.collect { event ->
                when (event) {
                    is SearchEvent.Results -> {
                        val accepted = event.results
                            .asSequence()
                            .filter { SourceFilter.matchesContent(it, route.doubanId, route.title, route.year) }
                            .filter { !ContentFilterService.shouldFilter(it.typeName) }
                            .toList()
                        if (accepted.isNotEmpty()) {
                            val merged = SearchService.deduplicateResults(state.sources + accepted)
                            state = state.copy(sources = merged)
                        }
                    }
                    is SearchEvent.Progress -> state = state.copy(sourcesProgress = event.progress)
                    is SearchEvent.Complete -> state = state.copy(sourcesComplete = true)
                    is SearchEvent.Error    -> {} // 单源错误已被 SearchService 内部记录
                }
            }
        }
        try {
            withContext(Dispatchers.IO) {
                SearchService.startSearch(route.title)
            }
        } finally {
            // collectorJob 会在 composable 离开时随 scope 自动取消
        }
    }

    // 拉播放记录
    LaunchedEffect(route.doubanId) {
        try {
            val records = withContext(Dispatchers.IO) { ApiService.getPlayRecords() }
            state = state.copy(playRecords = records)
        } catch (_: Exception) { /* 失败不阻塞 UI */ }
    }

    // 拉收藏列表
    LaunchedEffect(route.doubanId) {
        try {
            val favs = withContext(Dispatchers.IO) { ApiService.getFavorites() }
            val keys = favs.map { "${it.source}+${it.id}" }.toSet()
            state = state.copy(favoriteKeys = keys)
        } catch (_: Exception) { /* 失败不阻塞 UI */ }
    }

    // 默认选中源
    LaunchedEffect(state.sources, state.playRecords) {
        val current = state.selectedSourceKey
        val stillValid = current != null && state.sources.any { sourceKey(it) == current }
        if (!stillValid && state.sources.isNotEmpty()) {
            val withRecord = state.sources
                .mapNotNull { src ->
                    val key = sourceKey(src)
                    state.playRecords[key]?.let { rec -> src to rec.saveTime }
                }
                .maxByOrNull { it.second }
                ?.first
            val pick = withRecord ?: state.sources.first()
            state = state.copy(selectedSourceKey = sourceKey(pick))
        }
    }

    // 临时调试展示（下一个阶段替换为真正 UI）
    val gradientBrush = Brush.verticalGradient(
        0.0f to Color(0xFF0a0a0a),
        0.4f to Color(0xFF1a1a1a),
        0.7f to Color(0xFF0f0f0f),
        1.0f to Color(0xFF050505),
    )
    Box(
        modifier = Modifier.fillMaxSize().background(brush = gradientBrush),
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.padding(32.dp)) {
            Text("loading=${state.detailsLoading}", color = Color.White, fontSize = 14.sp)
            Text("title=${state.details?.title ?: route.title}", color = Color.White, fontSize = 14.sp)
            Text("year=${state.details?.year ?: route.year}", color = Color.White, fontSize = 14.sp)
            Text("sources=${state.sources.size} / complete=${state.sourcesComplete}", color = Color.White, fontSize = 14.sp)
            Text("selected=${state.selectedSourceKey}", color = Color.White, fontSize = 14.sp)
            Text("hasRecord=${state.selectedPlayRecord != null}", color = Color.White, fontSize = 14.sp)
            Text("isFav=${state.isSelectedFavorite}", color = Color.White, fontSize = 14.sp)
            state.detailsError?.let { Text("err=$it", color = Color.Red, fontSize = 14.sp) }
        }
    }
}
```

- [ ] **Step 2: Verify compile + manual test**

```bash
./gradlew assembleDebug
./gradlew installDebug
```

验证：
- 点豆瓣卡片 → 调试文本依次：`loading=true` → `loading=false`，sources 数字从 0 涨到几，`complete=true`
- selected 自动选中第一个或带 PlayRecord 的源
- 切换不同卡片进入，状态正确刷新

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt
git commit -m "$(cat <<'EOF'
DetailScreen 接入四路并发数据加载

豆瓣详情、聚合源搜索（带 doubanId/title/year 筛选 + 内容过滤）、
播放记录、收藏列表。默认源按 PlayRecord 最近优先。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 5：月牙加载页

### Task 12: MoonLoadingOverlay composable

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/detail/MoonLoadingOverlay.kt`

- [ ] **Step 1: Create file**

```kotlin
package org.moontechlab.selenetv.screen.detail

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * 月牙加载覆盖层：6s 匀速旋转 + 3s 柔光呼吸。Canvas 绘月牙形状。
 */
@Composable
fun MoonLoadingOverlay(
    title: String,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "moon")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )
    val glow by infinite.animateFloat(
        initialValue = 8f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .size(64.dp)
                    .graphicsLayer { rotationZ = rotation },
            ) {
                val w = size.width
                val h = size.height
                // 外圆半径 45%，内椭圆半径 32%×45%
                val outerR = w * 0.45f
                val innerRx = w * 0.32f
                val innerRy = h * 0.45f
                val cx = w / 2f
                val cy = h / 2f

                val path = Path().apply {
                    // 外半圆（从顶部顺时针走到底部）
                    arcTo(
                        rect = Rect(
                            offset = Offset(cx - outerR, cy - outerR),
                            size = androidx.compose.ui.geometry.Size(outerR * 2, outerR * 2),
                        ),
                        startAngleDegrees = -90f,
                        sweepAngleDegrees = 180f,
                        forceMoveTo = true,
                    )
                    // 内半椭圆（回到顶部，留出月牙的"缺口"）
                    arcTo(
                        rect = Rect(
                            offset = Offset(cx - innerRx, cy - innerRy),
                            size = androidx.compose.ui.geometry.Size(innerRx * 2, innerRy * 2),
                        ),
                        startAngleDegrees = 90f,
                        sweepAngleDegrees = -180f,
                        forceMoveTo = false,
                    )
                    close()
                }

                drawIntoCanvas { canvas ->
                    val paint = Paint().apply {
                        color = Color(0xFFFAFAFA)
                        asFrameworkPaint().apply {
                            isAntiAlias = true
                            setShadowLayer(glow, 0f, 0f, android.graphics.Color.argb(120, 255, 255, 255))
                            maskFilter = BlurMaskFilter(glow * 0.3f, BlurMaskFilter.Blur.NORMAL)
                        }
                    }
                    canvas.drawPath(path, paint)
                }
            }

            Text(
                text = title,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 16.sp,
                fontWeight = FontWeight.W600,
            )
            Text(
                text = "SELENE · LOADING",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
            )
        }
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/detail/MoonLoadingOverlay.kt
git commit -m "$(cat <<'EOF'
新增 MoonLoadingOverlay：Canvas 月牙旋转 + 柔光呼吸

外半圆 - 内半椭圆构造月牙；6s 360° 旋转，3s 柔光呼吸。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: Wire 150ms delay + cross-fade in DetailScreen

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt`

- [ ] **Step 1: Replace debug UI with loading overlay + placeholder content**

把 DetailScreen 文件底部的「临时调试展示」整段（从 `val gradientBrush = ...` 开始的 Box）替换为：
```kotlin
// 是否真正展示加载层（延迟 150ms，避免命中缓存时闪屏）
var showMoonOverlay by remember { mutableStateOf(false) }
LaunchedEffect(state.detailsLoading) {
    if (state.detailsLoading) {
        kotlinx.coroutines.delay(150)
        if (state.detailsLoading) showMoonOverlay = true
    } else {
        showMoonOverlay = false
    }
}

val gradientBrush = Brush.verticalGradient(
    0.0f to Color(0xFF0a0a0a),
    0.4f to Color(0xFF1a1a1a),
    0.7f to Color(0xFF0f0f0f),
    1.0f to Color(0xFF050505),
)

Box(modifier = Modifier.fillMaxSize().background(brush = gradientBrush)) {
    androidx.compose.animation.AnimatedVisibility(
        visible = !state.detailsLoading,
        enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
        exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(0)),
    ) {
        // 占位：下个阶段塞入 DetailHero + DetailSourcesSection
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "details ready: ${state.details?.title ?: state.route.title}",
                color = Color.White,
                fontSize = 18.sp,
            )
        }
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = showMoonOverlay,
        enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
        exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(200)),
    ) {
        org.moontechlab.selenetv.screen.detail.MoonLoadingOverlay(title = state.route.title)
    }
}
```

- [ ] **Step 2: Verify compile + manual test**

```bash
./gradlew assembleDebug
./gradlew installDebug
```

验证：
- 首次进入未缓存的豆瓣作品 → 看到月牙加载（约 1-3s）→ 加载完成后 200ms 淡出，露出占位 Text
- 第二次进入同作品（缓存命中）→ 几乎不见月牙（150ms 延迟把它挡住了），直接进内容
- 月牙明显旋转 + 柔光呼吸

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt
git commit -m "$(cat <<'EOF'
DetailScreen 接入月牙加载页

150ms 延迟避免缓存命中时闪屏，加载完成 200ms cross-fade。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 6：Hero UI

### Task 14: DetailHero composable

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailHero.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt`

- [ ] **Step 1: Create DetailHero.kt**

```kotlin
package org.moontechlab.selenetv.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import org.moontechlab.selenetv.model.DoubanMovieDetails
import org.moontechlab.selenetv.screen.DetailRoute
import org.moontechlab.selenetv.util.getImageUrlSync

@Composable
fun DetailHero(
    route: DetailRoute,
    details: DoubanMovieDetails?,
    actionsRow: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = details?.title ?: route.title
    val poster = details?.poster?.takeIf { it.isNotEmpty() } ?: route.poster

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 58.dp, top = 60.dp, end = 48.dp, bottom = 40.dp),
    ) {
        // 左列
        Column(modifier = Modifier.weight(1f).padding(end = 36.dp)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.W700,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // chips + rating
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                details?.genres?.forEach { genre ->
                    Text(
                        text = genre,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                    )
                }
                details?.rate?.let { rate ->
                    if (rate.isNotEmpty() && rate != "0.0") {
                        Text(
                            text = "★ $rate",
                            color = Color(0xFFE91E63),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.W700,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // sub 行
            val subParts = buildList {
                details?.releaseDate?.takeIf { it.isNotEmpty() }?.let { rd ->
                    val country = details.countries.firstOrNull()?.takeIf { it.isNotEmpty() }
                    if (country != null) add("$rd($country)") else add(rd)
                }
                details?.countries?.takeIf { it.isNotEmpty() }?.let { add(it.joinToString("/")) }
                details?.duration?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
            if (subParts.isNotEmpty()) {
                Text(
                    text = subParts.joinToString(" · "),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            details?.summary?.takeIf { it.isNotEmpty() }?.let { summary ->
                Text(
                    text = summary,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            actionsRow()
        }

        // 右列：海报
        Box(
            modifier = Modifier
                .size(width = 200.dp, height = 300.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(8.dp),
                    ambientColor = Color.Black.copy(alpha = 0.5f),
                    spotColor = Color.Black.copy(alpha = 0.5f),
                )
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2A2A2A)),
        ) {
            AsyncImage(
                model = getImageUrlSync(poster),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
```

- [ ] **Step 2: Wire DetailHero into DetailScreen**

把 DetailScreen 文件中「占位 Text」那段（`Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("details ready: ...", ...) }`）替换为：
```kotlin
androidx.compose.foundation.layout.Column(
    modifier = Modifier
        .fillMaxSize()
        .androidx.compose.foundation.verticalScroll(androidx.compose.foundation.rememberScrollState()),
) {
    org.moontechlab.selenetv.screen.detail.DetailHero(
        route = state.route,
        details = state.details,
        actionsRow = {
            // 下个任务塞入真实按钮行
            androidx.tv.material3.Text(
                text = "[actions placeholder]",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
            )
        },
    )
}
```

注意 imports 需要补齐：
```kotlin
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
```

- [ ] **Step 3: Verify compile + manual test**

```bash
./gradlew assembleDebug
./gradlew installDebug
```

验证：
- 点豆瓣卡片进入 → 月牙加载后 → Hero 显示：大标题、genres chips、★评分、上映/产地/时长行、完整简介、海报
- 海报正常加载（豆瓣 referer 处理已生效）
- 长简介完整展示，可整页滚动

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailHero.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt
git commit -m "$(cat <<'EOF'
新增 DetailHero：标题/chips/评分/sub/简介 + 海报

按设计稿排版，actions 槽位先占位。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 7：动作按钮

### Task 15: DetailActionButtons composable

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailActionButtons.kt`

- [ ] **Step 1: Create file**

```kotlin
package org.moontechlab.selenetv.screen.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

@Composable
fun DetailActionButtons(
    showContinue: Boolean,
    showPlay: Boolean,
    showDelete: Boolean,
    showFavorite: Boolean,
    isFavorite: Boolean,
    sourceName: String?,
    onContinue: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    primaryFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showContinue) {
            PrimaryActionButton(
                text = "继续播放",
                onClick = onContinue,
                modifier = Modifier.focusRequester(primaryFocusRequester),
            )
        }
        if (showPlay) {
            SecondaryActionButton(
                text = "播放",
                onClick = onPlay,
                modifier = if (showContinue) Modifier else Modifier.focusRequester(primaryFocusRequester),
            )
        }
        if (showDelete) {
            IconActionButton(
                icon = Icons.Default.Delete,
                contentDescription = "删除播放记录",
                onClick = onDelete,
            )
        }
        if (showFavorite) {
            IconActionButton(
                icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) "取消收藏" else "收藏",
                onClick = onToggleFavorite,
                modifier = if (!showContinue && !showPlay) Modifier.focusRequester(primaryFocusRequester) else Modifier,
            )
        }

        if (sourceName != null) {
            Spacer(modifier = Modifier.weight(1f))
            SourceNameLabel(name = sourceName)
        }
    }
}

@Composable
private fun PrimaryActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "primary_scale",
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(38.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(19.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White,
            focusedContainerColor = Color.White,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color(0xFF0A0A0A),
                modifier = Modifier.size(18.dp),
            )
            Text(text = text, color = Color(0xFF0A0A0A), fontSize = 13.sp, fontWeight = FontWeight.W600)
        }
    }
}

@Composable
private fun SecondaryActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "secondary_scale",
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(38.dp)
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(19.dp)) else Modifier),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(19.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.15f),
            focusedContainerColor = Color.White.copy(alpha = 0.15f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Text(text = text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W600)
        }
    }
}

@Composable
private fun IconActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "icon_scale",
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(38.dp)
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(19.dp)) else Modifier),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(19.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            focusedContainerColor = Color.White.copy(alpha = 0.12f),
        ),
    ) {
        Box(modifier = Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SourceNameLabel(name: String) {
    Box(
        modifier = Modifier
            .border(0.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = name,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontWeight = FontWeight.W500,
        )
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailActionButtons.kt
git commit -m "$(cat <<'EOF'
新增 DetailActionButtons：继续播放/播放/删除/收藏 + 当前源标签

按 spec 三种按钮样式（白底主、半透深、图标圆）和焦点动画约定。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 16: Wire action button handlers in DetailScreen

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt`

- [ ] **Step 1: Add favorite + delete + episode logic helpers**

在 DetailScreen 顶层（`@Composable fun DetailScreen` 内、`var state by remember ...` 之后）增加（注意 lambda 用命名标签 `lam@` 以便内部使用 `return@lam` 早退）：
```kotlin
val primaryFocus = remember { androidx.compose.ui.focus.FocusRequester() }

fun showToast(msg: String) {
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
}

val onToggleFavorite: () -> Unit = lam@{
    val source = state.selectedSource ?: return@lam
    val key = sourceKey(source)
    val details = state.details
    val wasFav = state.isSelectedFavorite

    // 乐观更新
    state = state.copy(
        favoriteKeys = if (wasFav) state.favoriteKeys - key else state.favoriteKeys + key
    )
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                if (wasFav) {
                    ApiService.unfavorite(source.source, source.id)
                } else {
                    val favData = mapOf<String, Any?>(
                        "title" to (details?.title ?: state.route.title),
                        "source_name" to source.sourceName,
                        "year" to (details?.year ?: state.route.year),
                        "cover" to source.poster,
                        "total_episodes" to source.episodes.size,
                        "save_time" to System.currentTimeMillis(),
                        "origin" to "",
                    )
                    ApiService.addFavorite(source.source, source.id, favData)
                }
            }
        } catch (e: Exception) {
            // 回滚
            state = state.copy(
                favoriteKeys = if (wasFav) state.favoriteKeys + key else state.favoriteKeys - key
            )
            showToast("收藏操作失败：${e.message ?: "未知错误"}")
        }
    }
}

val onDeleteRecord: () -> Unit = lam@{
    val source = state.selectedSource ?: return@lam
    val key = sourceKey(source)
    val backup = state.playRecords[key] ?: return@lam
    state = state.copy(playRecords = state.playRecords - key)
    scope.launch {
        try {
            withContext(Dispatchers.IO) { ApiService.deletePlayRecord(source.source, source.id) }
            showToast("已删除播放记录")
        } catch (e: Exception) {
            state = state.copy(playRecords = state.playRecords + (key to backup))
            showToast("删除失败：${e.message ?: "未知错误"}")
        }
    }
}

val onContinue: () -> Unit = lam@{
    val source = state.selectedSource ?: return@lam
    val rec = state.selectedPlayRecord
    android.util.Log.d("DetailScreen", "continue: source=${source.source}+${source.id} ep=${rec?.index} pos=${rec?.playTime}")
}

val onPlay: () -> Unit = lam@{
    val source = state.selectedSource ?: return@lam
    val rec = state.selectedPlayRecord
    when {
        rec != null -> {
            android.util.Log.d("DetailScreen", "play (resume): source=${source.source}+${source.id} ep=${rec.index} pos=${rec.playTime}")
        }
        source.episodes.size > 1 -> {
            state = state.copy(episodePickerVisible = true)
        }
        else -> {
            android.util.Log.d("DetailScreen", "play (single): source=${source.source}+${source.id} ep=0")
        }
    }
}
```

- [ ] **Step 2: Replace actionsRow placeholder in Hero call**

把 DetailScreen 内 `DetailHero(..., actionsRow = { Text("[actions placeholder]" ...) })` 中的 actionsRow 改为：
```kotlin
actionsRow = {
    val hasRecord = state.selectedPlayRecord != null
    val hasSource = state.selectedSource != null
    org.moontechlab.selenetv.screen.detail.DetailActionButtons(
        showContinue = hasSource && hasRecord,
        showPlay = hasSource,
        showDelete = hasSource && hasRecord,
        showFavorite = hasSource,
        isFavorite = state.isSelectedFavorite,
        sourceName = state.selectedSource?.sourceName,
        onContinue = onContinue,
        onPlay = onPlay,
        onDelete = onDeleteRecord,
        onToggleFavorite = onToggleFavorite,
        primaryFocusRequester = primaryFocus,
    )
}
```

- [ ] **Step 3: Request focus when details ready**

在 DetailScreen 内（紧跟 `LaunchedEffect(state.detailsLoading)` 之后）追加：
```kotlin
LaunchedEffect(state.detailsLoading, state.sources.isNotEmpty()) {
    if (!state.detailsLoading) {
        kotlinx.coroutines.delay(220) // 等 cross-fade 结束
        try { primaryFocus.requestFocus() } catch (_: Exception) {}
    }
}
```

- [ ] **Step 4: Verify compile + manual test**

```bash
./gradlew assembleDebug
./gradlew installDebug
```

验证：
- 进入详情页，月牙加载消失后焦点落在白底「继续播放」按钮（如果有 record）或「播放」按钮
- 按 ♡ → 切到 ♥（红色填充图标），按一次再变回 ♡
- 按 🗑（有 record 时显示）→ Toast「已删除播放记录」，继续播放 + 🗑 按钮消失
- 按 播放 没 record + 多集时不报错（下个任务接 dialog）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt
git commit -m "$(cat <<'EOF'
DetailScreen 接入动作按钮：收藏 toggle、删除播放记录、播放/继续播放

收藏 + 删除走乐观更新；失败回滚 + Toast。
播放 + 继续播放暂以 Log.d 替代真实播放器接入。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 8：播放源行

### Task 17: DetailSourcesSection composable

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailSourcesSection.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt`

- [ ] **Step 1: Create DetailSourcesSection.kt**

```kotlin
package org.moontechlab.selenetv.screen.detail

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.moontechlab.selenetv.model.PlayRecord
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.model.VideoInfo
import org.moontechlab.selenetv.service.SearchProgress
import org.moontechlab.selenetv.ui.VideoCard
import org.moontechlab.selenetv.ui.VideoCardSource

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailSourcesSection(
    sources: List<SearchResult>,
    selectedSourceKey: String?,
    playRecords: Map<String, PlayRecord>,
    progress: SearchProgress?,
    complete: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val firstItemFocus = remember { FocusRequester() }

    Column(modifier = modifier.fillMaxWidth().padding(top = 16.dp, bottom = 40.dp)) {
        // 标题行
        Row(
            modifier = Modifier.padding(start = 58.dp, end = 58.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "播放源",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.W700,
            )
            if (!complete && progress != null && progress.totalSources > 0) {
                Text(
                    text = "${progress.completedSources}/${progress.totalSources}",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }

        when {
            sources.isEmpty() && complete -> {
                Text(
                    text = "没有可用的播放源",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 58.dp, top = 16.dp),
                )
            }
            sources.isEmpty() -> {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(horizontal = 58.dp),
                ) {
                    itemsIndexed(List(6) { it }) { _, _ -> SourceCardSkeleton() }
                }
            }
            else -> {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(horizontal = 58.dp),
                    modifier = Modifier
                        .focusGroup()
                        .focusRestorer(firstItemFocus),
                ) {
                    itemsIndexed(sources, key = { _, s -> "${s.source}+${s.id}" }) { index, result ->
                        val key = "${result.source}+${result.id}"
                        val record = playRecords[key]
                        val isSelected = selectedSourceKey == key
                        VideoCard(
                            videoInfo = VideoInfo(
                                id = result.id,
                                source = result.source,
                                title = result.sourceName,
                                sourceName = result.sourceName,
                                year = result.year,
                                cover = result.poster,
                                index = record?.index ?: 0,
                                totalEpisodes = result.episodes.size.coerceAtLeast(1),
                                playTime = 0L,
                                totalTime = 0L,
                                saveTime = 0L,
                                searchTitle = result.title,
                                doubanId = result.doubanId?.toString(),
                            ),
                            from = VideoCardSource.SOURCE,
                            onClick = { onSelect(key) },
                            isSelected = isSelected,
                            modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCardSkeleton() {
    val infinite = rememberInfiniteTransition(label = "src_skel")
    val alpha by infinite.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "src_skel_alpha",
    )
    Column(modifier = Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = alpha)),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(60.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = alpha * 0.7f)),
        )
    }
}
```

注意 `Modifier.focusRequester(firstItemFocus)` 需要 import `androidx.compose.ui.focus.focusRequester`。

- [ ] **Step 2: Wire into DetailScreen**

在 DetailScreen 内 `DetailHero(...)` 调用之后追加：
```kotlin
org.moontechlab.selenetv.screen.detail.DetailSourcesSection(
    sources = state.sources,
    selectedSourceKey = state.selectedSourceKey,
    playRecords = state.playRecords,
    progress = state.sourcesProgress,
    complete = state.sourcesComplete,
    onSelect = { key -> state = state.copy(selectedSourceKey = key) },
)
```

- [ ] **Step 3: Verify compile + manual test**

```bash
./gradlew assembleDebug
./gradlew installDebug
```

验证：
- Hero 下方出现「播放源」标题 + 进度文案（搜索中）
- 各源卡片陆续到位，每张显示源的封面 + 集数徽章
- 当前选中源左上角绿勾
- 点别的源卡片 → 绿勾迁移，Hero 动作按钮根据新源的 PlayRecord 重新计算
- 已有播放记录的源显示 `${index}/${total}` 徽章

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/detail/DetailSourcesSection.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt
git commit -m "$(cat <<'EOF'
新增 DetailSourcesSection 播放源横向列表

复用 VideoCard LARGE，集数徽章 + isSelected 绿勾天然吃到。
加载中显示骨架，搜索完成无结果显示空态文案。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 9：剧集选择对话框

### Task 18: EpisodePickerDialog

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/detail/EpisodePickerDialog.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt`

- [ ] **Step 1: Create EpisodePickerDialog.kt**

```kotlin
package org.moontechlab.selenetv.screen.detail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

@Composable
fun EpisodePickerDialog(
    visible: Boolean,
    sourceName: String,
    episodes: List<String>,
    episodesTitles: List<String>?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (visible) {
            kotlinx.coroutines.delay(50)
            try { firstFocus.requestFocus() } catch (_: Exception) {}
        }
    }

    BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 900.dp)
                .background(Color(0xFF1A1A1A).copy(alpha = 0.95f), RoundedCornerShape(16.dp))
                .padding(32.dp),
        ) {
            Text(
                text = "选择剧集 · $sourceName",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.W700,
            )
            Spacer(modifier = Modifier.height(16.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
            ) {
                itemsIndexed(episodes) { index, _ ->
                    val label = episodesTitles?.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "第 ${index + 1} 集"
                    EpisodeChip(
                        label = label,
                        onClick = { onSelect(index) },
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "ep_scale",
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusable(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White,
        ),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (focused) Color(0xFF0A0A0A) else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.W500,
            )
        }
    }
}
```

- [ ] **Step 2: Wire dialog into DetailScreen**

在 DetailScreen 的最外层 `Box(...)` 内（在 AnimatedVisibility 之后、闭合大括号之前）追加：
```kotlin
state.selectedSource?.let { source ->
    org.moontechlab.selenetv.screen.detail.EpisodePickerDialog(
        visible = state.episodePickerVisible,
        sourceName = source.sourceName,
        episodes = source.episodes,
        episodesTitles = source.episodesTitles,
        onSelect = { idx ->
            android.util.Log.d("DetailScreen", "play: source=${source.source}+${source.id} ep=$idx")
            state = state.copy(episodePickerVisible = false)
        },
        onDismiss = { state = state.copy(episodePickerVisible = false) },
    )
}
```

- [ ] **Step 3: Verify compile + manual test**

```bash
./gradlew assembleDebug
./gradlew installDebug
```

验证：
- 选一个**没有播放记录**的源 → 按「播放」→ 弹出剧集对话框，焦点落在「第 1 集」
- 方向键移动 + Enter 选集 → 对话框关闭，logcat 看到 `play: source=... ep=N`
- 按返回键 → 对话框关闭
- 选有记录的源 → 按「播放」直接 log（不弹对话框）

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/detail/EpisodePickerDialog.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt
git commit -m "$(cat <<'EOF'
新增 EpisodePickerDialog 剧集选择对话框

复用 FilterDialog 玻璃质感，8 列网格，焦点动效一致。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 10：错误态 + 收尾

### Task 19: Hero error state + final polish

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt`

- [ ] **Step 1: Add Hero error state composable inside DetailScreen**

在 DetailScreen 文件底部增加：
```kotlin
@Composable
private fun HeroErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
            androidx.tv.material3.Text(
                text = "加载失败：$message",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
            )
            androidx.tv.material3.Surface(
                onClick = onRetry,
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = Color(0x14FFFFFF),
                    focusedContainerColor = Color(0xFF4CAF50),
                ),
                modifier = Modifier.onFocusChanged { focused = it.isFocused },
            ) {
                androidx.tv.material3.Text(
                    text = "重试",
                    color = if (focused) Color.White else Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp),
                )
            }
        }
    }
}
```

- [ ] **Step 2: Conditional rendering in main content**

把 DetailScreen 内的 `AnimatedVisibility(visible = !state.detailsLoading, ...) { Column(...) { ... } }` 中的内容块（DetailHero + DetailSourcesSection）包一层判断：
```kotlin
androidx.compose.animation.AnimatedVisibility(
    visible = !state.detailsLoading,
    enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
    exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(0)),
) {
    val err = state.detailsError
    if (err != null && state.details == null) {
        HeroErrorState(
            message = err,
            onRetry = {
                scope.launch {
                    state = state.copy(detailsLoading = true, detailsError = null)
                    when (val resp = doubanService.getDoubanDetails(state.route.doubanId)) {
                        is ApiResponse.Success -> state = state.copy(details = resp.data, detailsLoading = false)
                        is ApiResponse.Error -> state = state.copy(detailsError = resp.message, detailsLoading = false)
                    }
                }
            },
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            org.moontechlab.selenetv.screen.detail.DetailHero(
                route = state.route,
                details = state.details,
                actionsRow = { /* 见 Task 16 现有内容 */ },
            )
            org.moontechlab.selenetv.screen.detail.DetailSourcesSection(
                sources = state.sources,
                selectedSourceKey = state.selectedSourceKey,
                playRecords = state.playRecords,
                progress = state.sourcesProgress,
                complete = state.sourcesComplete,
                onSelect = { key -> state = state.copy(selectedSourceKey = key) },
            )
        }
    }
}
```

注意保留 actionsRow lambda 现有完整内容（Task 16 已建立的 onContinue/onPlay/onDelete/onToggleFavorite）。

- [ ] **Step 3: Cleanup SearchService 副作用**

在 DetailScreen 最外层加 `DisposableEffect`，确保离开页面时停止搜索：
```kotlin
androidx.compose.runtime.DisposableEffect(Unit) {
    onDispose { SearchService.stopSearch() }
}
```

放在 `var state by remember { ... }` 之后即可。

- [ ] **Step 4: Verify compile + manual test**

```bash
./gradlew assembleDebug
./gradlew installDebug
```

验证：
- 断网或选一个不存在的 doubanId（手动调试时改路由参数）→ Hero 区域显示「加载失败：…」+ 重试按钮，重试焦点显示绿底
- 离开详情页（返回键），用 logcat 看 SearchService 是否停止（不应再有新搜索结果发出）
- 一切正常流程仍工作

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt
git commit -m "$(cat <<'EOF'
DetailScreen 增加详情错误态与重试，离开页面时停止搜索

Hero 区显示「加载失败 + 重试」，复用 MovieTab GridErrorState 风格。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 20: Final integration test pass

**Files:** （无新增/修改，仅手动验证 + 必要时小修）

- [ ] **Step 1: Full app build + lint**

```bash
./gradlew clean assembleDebug lintDebug
```
Expected: BUILD SUCCESSFUL，lint 没有新增 error。

- [ ] **Step 2: Run all unit tests**

```bash
./gradlew testDebugUnitTest
```
Expected: All tests pass，SourceFilterTest 6/6。

- [ ] **Step 3: 端到端手动验证清单（建议在 TV / 模拟器上跑）**

- [ ] 启动 app，登录态：首页热门电影/剧集/综艺正常加载
- [ ] 点首页热门电影第一张卡片 → 月牙加载（首次） → Hero 显示
- [ ] 焦点初始在「继续播放」或「播放」按钮
- [ ] 简介长内容向下滚动正常露出「播放源」区
- [ ] 「播放源」加载完成，每张卡片显示对应源的封面，当前源左上绿勾
- [ ] 选不同源 → 绿勾迁移，Hero 动作按钮根据新源 PlayRecord 重算
- [ ] 选无记录的源，按「播放」 → 弹剧集对话框；选一集 → 关闭 + log
- [ ] 选有记录的源，按「播放」/「继续播放」 → 直接 log
- [ ] 按 ♡ → 切到 ♥；返回首页收藏夹应能见到该源（homeRefreshKey 刷新）
- [ ] 按 🗑（有记录时） → Toast 删除成功，继续播放 + 🗑 消失
- [ ] 按返回 → 回原 Tab，焦点正确
- [ ] 第二次点同一张卡片进入 → 月牙几乎不出现（缓存命中 + 150ms 阀值）
- [ ] 设置 Tab 点登录 → LoginScreen NavHost 转场动画顺滑

- [ ] **Step 4: 若发现回归 → 修正后追加一个 commit**

```bash
git add <fixed-files>
git commit -m "$(cat <<'EOF'
DetailScreen 集成测试修复：<描述>

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: 完成**

详情页 + Navigation 迁移落地完毕。下一个 PR 起可以接入真实播放器（替换 `Log.d` 处的 `PlayCoordinator`）。
