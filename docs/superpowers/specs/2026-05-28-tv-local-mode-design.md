# TV 本地模式（订阅模式）设计

**日期**：2026-05-28
**状态**：设计已批准，待写实施计划
**对齐参考**：`../mobile`（Flutter 端的本地模式实现已上线）

## 目标

为 Selene-TV 引入「本地模式」：用户无需服务端账号、仅通过一个订阅链接即可使用 App。订阅返回的搜索源 / 直播源直接写入本地存储；收藏 / 播放记录 / 搜索历史等原本由服务端承载的用户数据，全部迁到本地 SharedPreferences。模式判定贯穿启动链路、数据访问层与设置项 UI。

## 非目标

- 不替换服务端模式 —— 两种模式互斥共存，可来回切换。
- 不引入新的播放器后端、不改 ExoPlayer / mpv 选型。
- 不改远程遥控器（`RemoteControlServer`）与 `sessionToken` 体系。
- 不动豆瓣（rexxar）、TMDB、Bangumi 这些与服务端无关的数据通路。

## 关键决策（来自 brainstorming）

1. **数据范围**：搜索源 / 直播源 + 收藏 / 播放记录 / 搜索历史 全部本地化（对齐 mobile）。
2. **入口 UX**：登录页右侧表单原地切换，左侧二维码 + 品牌点暗门保留；品牌点长按 3 秒 toggle 两套表单。
3. **可达性**：HTTP 200 + Base58 解码成功 + JSON 解析成功 + `api_site.size + lives.size >= 1`。
4. **设置「账户」分区**：本地模式下整段替换为「本地模式」分区（订阅链接 / 立即刷新 / 退出本地模式）。
5. **服务端相关项屏蔽**：仅隐藏「搜索」分区的「搜索发起方」一行（连带分区标题整段隐藏）。
6. **启动刷新**：`SeleneApplication.onCreate` 起 fire-and-forget 后台刷新，**不阻塞启动**；失败 `MoonToast` 轻提示。
7. **URL 冲突**：登录页或设置页改 URL 时，若旧 URL ≠ 新 URL，弹框问是否清除全部本地数据（对齐 mobile）。
8. **退出本地模式**：二次确认 → 清本地数据 → 回账号登录页。
9. **路由架构**：抽 `UserDataRepository` / `SourcesRepository` 接口，工厂按 `isLocalModeSync()` 路由；`ApiService` 成为 `ServerXxxRepository` 内部细节。

---

## 1. 启动与模式判定

### AppDataService 扩展
新增持久态 `KEY_IS_LOCAL_MODE`（boolean，默认 false）：

```kotlin
// 写入（异步）
suspend fun saveIsLocalMode(isLocalMode: Boolean)
// 异步读
suspend fun isLocalMode(): Boolean
// 同步读 + 内存缓存（init() 时一次性读到内存）
fun isLocalModeSync(): Boolean
```

`isLocalModeSync()` 用 `@Volatile` 缓存，避免高频访问 SharedPreferences。`init(context)` 中初始化一次；`saveIsLocalMode` 同步更新缓存。

### 启动决策（AppNavHost.kt:28）
```kotlin
startDestination =
    if (AppDataService.isLoggedInSync() || AppDataService.isLocalModeSync()) HomeRoute
    else LoginRoute
```

### 启动后台刷新
`SeleneApplication.onCreate()` 现行通过 `thread { RemoteControlServer.start() }` 启动遥控器（`SeleneApplication.kt:37-43`）。该段不动；在 `super.onCreate()` 与既有 `startRemoteControlServer()` 之后新增一个**独立**的 fire-and-forget 后台分支：

```kotlin
// 类字段
private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

override fun onCreate() {
    super.onCreate()
    AppDataService.init(this)
    startRemoteControlServer()  // 既有，不动
    if (AppDataService.isLocalModeSync()) {
        applicationScope.launch {
            val ok = SubscriptionService.refreshAndPersist()
            if (!ok) MoonToast.show("订阅刷新失败")
        }
    }
}
```

**约束**：
- 协程 fire-and-forget，**不 join、不阻塞 `onCreate` 返回**。Activity `onCreate` 完全不感知。
- `MoonToast.show()` 内部仅写 Compose state（`MoonToast.kt:41-44`，单 int + string），从 IO 协程直接调用无需切 Main —— 与项目内既有用法（`LoginScreen.kt:272`、`SettingsTab.kt` 等）一致。
- toast 在 home 已 attach `MoonToastHost` 之后才有可能弹出（`MoonToastHost` 在 `LaunchedEffect(trigger)` 里渲染）；若刷新极快、home 尚未挂载，trigger++ 仍被 host 重启动时拾取（host 启动 `if (trigger == 0) return`，trigger 已 > 0 则正常弹）。

### MainActivity.refreshLoginCookieIfNeeded() 短路
```kotlin
private fun refreshLoginCookieIfNeeded() {
    if (AppDataService.isLocalModeSync()) return  // 本地模式与服务端账号互斥
    activityScope.launch {
        if (AppDataService.isLoggedIn() && AppDataService.hasAutoLoginData()) {
            refreshLoginCookie()
        }
    }
}
```

### SearchOrigin sync getter 兜底
```kotlin
fun getSearchOriginSync(): SearchOrigin {
    if (isLocalModeSync()) return SearchOrigin.TV_DIRECT
    return SearchOrigin.fromKey(prefs.getString(KEY_SEARCH_ORIGIN, SearchOrigin.TV_DIRECT.key)
        ?: SearchOrigin.TV_DIRECT.key)
}
```
保证 `SearchService.startSearch` 在本地模式下永远走 `localSearch` 分支，即使历史 prefs 残留 `SERVER_AGGREGATED`。

---

## 2. 仓库层（Repository 抽象）

### 包结构
```
app/src/main/java/org/moontechlab/selenetv/data/
    UserDataRepository.kt          // 接口：收藏 / 播放记录 / 搜索历史
    SourcesRepository.kt           // 接口：搜索源 / 直播源
    Repositories.kt                // 工厂：按 isLocalModeSync() 路由
    server/
        ServerUserDataRepository.kt    // 委托 ApiService
        ServerSourcesRepository.kt     // 委托 ApiService
    local/
        LocalStore.kt                  // SharedPreferences "selene_tv_local"
        LocalUserDataRepository.kt
        LocalSourcesRepository.kt
```

### 接口
```kotlin
interface UserDataRepository {
    fun getFavorites(): List<FavoriteItem>
    fun addFavorite(source: String, id: String, favoriteData: Map<String, Any?>)
    fun unfavorite(source: String, id: String)
    fun getPlayRecords(): Map<String, PlayRecord>
    fun savePlayRecord(record: PlayRecord)
    fun deletePlayRecord(source: String, id: String)
    fun clearPlayRecords()
    fun getSearchHistory(): List<String>
    fun addSearchHistory(query: String)
    fun deleteSearchHistory(query: String)
    fun clearSearchHistory()
}

interface SourcesRepository {
    fun getSearchResources(): List<SearchResource>
    fun getLiveSources(): List<LiveSource>
}
```

调用约定与 `ApiService` 一致：同步阻塞、失败抛异常、必须在 `Dispatchers.IO` 调用。Server 实现抛 `ApiException`；Local 实现读 SharedPreferences 不抛错（数据缺失返回空集合）。

### 工厂
```kotlin
object Repositories {
    fun userData(): UserDataRepository =
        if (AppDataService.isLocalModeSync()) LocalUserDataRepository
        else ServerUserDataRepository
    fun sources(): SourcesRepository =
        if (AppDataService.isLocalModeSync()) LocalSourcesRepository
        else ServerSourcesRepository
}
```
每次调用拿对象（不是缓存）—— 切换模式后下次访问即得新实现，无须刷新调用方。

### 调用点迁移（13 处）
| 文件 | 行 | 当前调用 | 改为 |
|---|---|---|---|
| `screen/tab/HomeTab.kt` | 115, 132, 158, 196, 214 | `ApiService.X` | `Repositories.userData().X` |
| `screen/tab/SearchTab.kt` | 343, 398, 436 | 同上 | 同上 |
| `screen/DetailScreen.kt` | 308, 316, 490, 521, 532, 560, 662, 687 | 同上 | 同上 |
| `screen/player/PlayerScreen.kt` | 203, 596 | 同上 | 同上 |
| `screen/player/PlayProgressReporter.kt` | 35 | 同上 | 同上 |
| `service/SearchService.kt` | 118 | `ApiService.getSearchResources()` | `Repositories.sources().getSearchResources()` |
| `service/DownstreamService.kt` | 332 | 同上 | 同上 |
| `service/LiveService.kt` | 55 | `ApiService.getLiveSources()` | `Repositories.sources().getLiveSources()` |

`HomeTab.kt:196` / `214` 等处的 `AppDataService.isLoggedInSync()` 守卫改为 `isLoggedInSync() || isLocalModeSync()` —— 语义是「user data 可读」。

### LocalStore 设计
- prefs 名 `selene_tv_local`，与 `selene_tv_prefs`（账号设置）隔离
- 收藏夹内存缓存 `@Volatile var favoritesCache: List<FavoriteItem>?`，对齐 `ApiService.getFavorites()` 的同步语义和 `DetailScreen` 高频读
- 序列化用 `kotlinx.serialization`（已在项目中）；现有 `FavoriteItem` / `PlayRecord` / `SearchResource` / `LiveSource` 均已 `@Serializable`，复用即可
- key 命名：
    - `subscription_url: String`
    - `search_sources: String`（JSON 数组）
    - `live_sources: String`（JSON 数组）
    - `play_records: String`（JSON 对象，key=`${source}+${id}`）
    - `favorites: String`（JSON 对象，key=`${source}+${id}`）
    - `search_history: String`（JSON 数组，去重，最多 20 条 —— 对齐 mobile）
- 所有写入 `apply()`（异步落盘），所有读不阻塞
- `clearAll()`：一次性清掉以上所有 key

---

## 3. 订阅服务

### SubscriptionService（新文件 `service/SubscriptionService.kt`）
```kotlin
object SubscriptionService {
    class SubscriptionException(message: String) : Exception(message)

    data class ParsedSubscription(
        val searchResources: List<SearchResource>,
        val liveSources: List<LiveSource>,
    )

    /** 同步阻塞，必须在 Dispatchers.IO 调用。失败抛 SubscriptionException。 */
    fun fetchAndParse(url: String): ParsedSubscription

    /** 后台刷新订阅：fetchAndParse + 仅覆盖 LocalStore 的 sources，不动用户数据。返回 true=成功。 */
    suspend fun refreshAndPersist(): Boolean
}
```

### fetchAndParse 流程
1. `HttpURLConnection.GET(url)`，沿用项目「信任所有证书 + 跳过 hostname 校验」约定（与 `Coil`、`SearchService` 一致）。
   - connectTimeout = 10000，readTimeout = 10000
2. HTTP 非 200 → 抛 `SubscriptionException("获取订阅内容失败 ($code)")`
3. response body (text) → `Base58.decode(text)` → `String(decoded, Charsets.UTF_8)`
4. `Json { ignoreUnknownKeys = true; isLenient = true }.parseToJsonElement(...)` 得到 root JsonObject
5. 解析 `root["api_site"]` 为 `JsonObject?`，遍历 entries：每个 value 是 `{ key?, name, api, detail?, from? }` —— 缺 `key` 时用 map 的 entry key 兜底（对齐 mobile）
6. 解析 `root["lives"]` 同理 → `{ key?, name, url, ua?, epg?, from? }`
7. **可达性校验**：`searchResources.size + liveSources.size >= 1`，否则抛 `SubscriptionException("订阅内容无可用源")`
8. 任意环节异常 catch → 抛 `SubscriptionException("订阅内容格式无效: ${e.message}")`

### Base58（新文件 `util/Base58.kt`）
不引新依赖，单文件实现：
```kotlin
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEX = IntArray(128) { -1 }.also { arr ->
        ALPHABET.forEachIndexed { i, c -> arr[c.code] = i }
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        // 1) 前导 '1' 计数 → 前导零字节
        var zeros = 0
        while (zeros < input.length && input[zeros] == '1') zeros++
        // 2) base58 → base256
        val input58 = IntArray(input.length - zeros)
        for ((i, c) in input.substring(zeros).withIndex()) {
            val digit = if (c.code < 128) INDEX[c.code] else -1
            require(digit >= 0) { "Invalid Base58 character: $c" }
            input58[i] = digit
        }
        val decoded = ByteArray(input.length)
        var outputStart = decoded.size
        var inputStart = 0
        while (inputStart < input58.size) {
            decoded[--outputStart] = divmod(input58, inputStart, 58, 256)
            if (input58[inputStart] == 0) inputStart++
        }
        // 3) 跳过解码过程产生的前导 0、再补回输入的前导零
        while (outputStart < decoded.size && decoded[outputStart] == 0.toByte()) outputStart++
        return ByteArray(zeros) + decoded.copyOfRange(outputStart, decoded.size)
    }

    private fun divmod(number: IntArray, firstDigit: Int, base: Int, divisor: Int): Byte {
        var remainder = 0
        for (i in firstDigit until number.size) {
            val digit = number[i]
            val temp = remainder * base + digit
            number[i] = temp / divisor
            remainder = temp % divisor
        }
        return remainder.toByte()
    }
}
```
覆盖 mobile `bs58check` 库的 `base58` 子模块语义（无 checksum）。

### refreshAndPersist
```kotlin
suspend fun refreshAndPersist(): Boolean = withContext(Dispatchers.IO) {
    val url = LocalStore.getSubscriptionUrl() ?: return@withContext false
    runCatching { fetchAndParse(url) }.fold(
        onSuccess = { parsed ->
            LocalStore.saveSearchSources(parsed.searchResources)
            LocalStore.saveLiveSources(parsed.liveSources)
            true
        },
        onFailure = {
            Log.w(TAG, "刷新订阅失败: ${it.message}")
            false
        }
    )
}
```
**仅覆盖 sources，不动 subscription URL 也不动用户数据**。

---

## 4. 登录页改造

### `LoginScreenContent` 新增状态
```kotlin
var isLocalMode by remember { mutableStateOf(false) }
```
品牌点暗门 `onSecretActivate` 改为 `{ isLocalMode = !isLocalMode; MoonToast.show(if (isLocalMode) "已进入本地模式" else "已切回账号登录") }`。

### 右侧表单二选一
**账号表单**（不变）—— 服务器地址 / 用户名 / 密码 / 登录按钮。
**本地模式表单**（新）：
```
Text "本地模式"        (22sp Bold)
Text "无需服务端账号，输入订阅链接即可"  (13sp TextGray)
Spacer(24.dp)
LoginTextField "订阅链接" placeholder="例如: https://example.com/sub/xxx"
                       focusRequester=subUrlFocusRequester
                       downFocusRequester=localLoginButtonFocusRequester
Spacer(24.dp)
LoginButton "进入本地模式" (loading 时 "校验中...")
            focusRequester=localLoginButtonFocusRequester
            upFocusRequester=subUrlFocusRequester
            leftFocusRequester=brandDotFocusRequester
```

`LaunchedEffect(isLocalMode)`：每次切换重新 `requestFocus` 到该表单的第一个输入框，避免切换瞬间焦点丢失。

`LaunchedEffect(isLocalMode)` 同时回填订阅链接：
```kotlin
if (isLocalMode) {
    subscriptionUrl = LocalStore.getSubscriptionUrlSync() ?: ""
}
```

### handleLocalModeLogin
分两段：①校验 + 冲突检测、②保存 + leave。中间用一个 `pendingParsed` Compose state 把流程一分为二，让二次确认弹框作为独立 composable 渲染，避免「suspend 等待用户选择」这种 Compose 里不自然的写法。

```kotlin
// LoginScreenContent 顶层状态
var pendingParsed by remember { mutableStateOf<SubscriptionService.ParsedSubscription?>(null) }
var pendingUrl by remember { mutableStateOf("") }
var showClearConfirmDialog by remember { mutableStateOf(false) }

fun handleLocalModeLogin() {
    val url = subscriptionUrl.trim()
    if (url.isEmpty()) { showToast("请输入订阅链接"); return }

    isLoading = true
    coroutineScope.launch {
        val parsed = try {
            withContext(Dispatchers.IO) { SubscriptionService.fetchAndParse(url) }
        } catch (e: SubscriptionService.SubscriptionException) {
            isLoading = false; showToast(e.message ?: "订阅校验失败"); return@launch
        } catch (e: Exception) {
            isLoading = false; showToast("网络异常: ${e.message ?: "未知错误"}"); return@launch
        }
        isLoading = false

        val existing = withContext(Dispatchers.IO) { LocalStore.getSubscriptionUrl() }
        if (!existing.isNullOrBlank() && existing != url) {
            // 缓存解析结果，弹二次确认；用户选择由弹框 onConfirm/onDismiss 触发 commitLocalMode
            pendingParsed = parsed
            pendingUrl = url
            showClearConfirmDialog = true
        } else {
            commitLocalMode(parsed, url, clearAll = false)
        }
    }
}

fun commitLocalMode(parsed: ParsedSubscription, url: String, clearAll: Boolean) {
    coroutineScope.launch {
        withContext(Dispatchers.IO) {
            if (clearAll) LocalStore.clearAll()
            LocalStore.saveSubscriptionUrl(url)
            LocalStore.saveSearchSources(parsed.searchResources)
            LocalStore.saveLiveSources(parsed.liveSources)
            AppDataService.saveIsLocalMode(true)
        }
        onDismiss()
    }
}

// 在 LoginScreenContent 末尾渲染：
AnimatedSelectDialog(
    visible = showClearConfirmDialog,
    onDismiss = { showClearConfirmDialog = false },
) { onClose ->
    ConfirmDialogContent(
        title = "已存在不同的订阅",
        message = "检测到已有本地模式内容且订阅链接不一致，是否清空全部本地模式存储？",
        confirmText = "清空并保存",
        confirmDestructive = true,
        focusConfirm = false,  // 危险操作默认聚焦取消
        onConfirm = {
            val p = pendingParsed; val u = pendingUrl
            onClose()
            if (p != null) commitLocalMode(p, u, clearAll = true)
        },
        onDismiss = {
            val p = pendingParsed; val u = pendingUrl
            onClose()
            if (p != null) commitLocalMode(p, u, clearAll = false)  // 选「否」=保留本地数据，仍保存新订阅
        },
    )
}
```

**焦点回收**：弹框 `onClosed` 不显式回收 —— `onDismiss()` 已离开 LoginScreen 进入 Home。仅当 commit 失败留在登录页的情况（理论上不会，因为写入都是 SharedPreferences `apply()`），焦点保留在登录按钮上。

### 共享弹框组件
`SettingsTab` 已有 `AnimatedSelectDialog` + `ConfirmDialogContent` + `DialogButton` —— **抽到 `ui/` 共享**（轻重构），登录页与设置页共用一套视觉。这是本设计内允许的、与本任务直接相关的小重构。

### BackHandler 链
现有：
```
isGate=true  → finish()
isGate=false → popBackStack()
```
改为：
```
isLocalMode=true → isLocalMode = false                  // 优先退内部状态
else if isGate   → finish()
else             → popBackStack()
```
与 TV 全局「先收回内部状态再退出」一致（参考 HomeScreen 的两层 BackHandler）。

---

## 5. 设置页改造

### `SettingsTab` 状态扩展
```kotlin
var isLocalMode by remember { mutableStateOf(AppDataService.isLocalModeSync()) }

LaunchedEffect(Unit) {
    isLocalMode = AppDataService.isLocalMode()   // 异步刷新，与现有 hasLogin/username 同套路
    // ...其他既有读取...
}
```

### 右栏「账户」分区分支

#### 本地模式分支（替换原账户分区整段）
```
SectionLabel("本地模式")

SettingRow "订阅链接"  value=subscriptionUrl.ifEmpty { "未设置" }
                    onClick = { showSubscriptionUrlDialog = true }
                    modifier = .focusRequester(subscriptionUrlFocusRequester)
                              .focusRequester(focusRequester)  // 顶部入口焦点承接
SettingRow "立即刷新订阅"  value=null
                       onClick = { coroutineScope.launch { handleRefreshSubscription() } }
                       modifier = .focusRequester(refreshSubscriptionFocusRequester)
SettingRow "退出本地模式"  accent=RowAccent.Red, showDivider=false
                       onClick = { showExitLocalModeDialog = true }
                       modifier = .focusRequester(exitLocalModeFocusRequester)
```

- **「订阅链接」弹框**：复用 `TextInputDialogContent`（已支持 Web 遥控器 `RemoteTextSink` 文本注入）。确认时走 `handleLocalModeLogin` 完全相同的流程（抽公共 helper）。
- **「立即刷新订阅」**：`handleRefreshSubscription`：
  ```kotlin
  suspend fun handleRefreshSubscription() {
      val ok = withContext(Dispatchers.IO) { SubscriptionService.refreshAndPersist() }
      MoonToast.show(if (ok) "订阅已刷新" else "订阅刷新失败")
  }
  ```
  无 loading 弹框、无焦点锁定 —— 刷新通常 < 2s，toast 即反馈。
- **「退出本地模式」**：复用现有两步弹框模式（`LogoutDialogContent` + `ConfirmDialogContent` destructive）。`onConfirm`：
  ```kotlin
  coroutineScope.launch {
      LocalStore.clearAll()
      AppDataService.saveIsLocalMode(false)
  }
  onShowLoginScreen()   // 沿用现有「退出登录」的回登录页通路
  ```

#### 服务端模式分支
保持现状（登录实例 / 登录 行）。

### 「搜索」分区
本地模式下 **整段不渲染**（`SectionLabel("搜索")` + `SettingRow("搜索发起方")` + 周围 `Spacer(18.dp)`）。

### 其他分区
不变：遥控器、数据源（豆瓣源 / 豆瓣图片源 / TMDB / M3U8 代理）、播放（播放器 / 解码）、缓存。

### HomeTab 已就绪守卫
`HomeTab.kt` 现行 `AppDataService.isLoggedInSync()` 用于判定是否拉收藏 / 播放记录。改为：
```kotlin
val ready = AppDataService.isLoggedInSync() || AppDataService.isLocalModeSync()
```
等价定义「user data 可读」。

---

## 6. 错误处理、边界、测试

### 错误处理
| 触发 | 表现 |
|---|---|
| 登录页 HTTP 失败 | toast `"获取订阅内容失败 ($code)"` |
| 登录页 Base58 / JSON 解析失败 | toast `"订阅内容格式无效"` |
| 登录页 api_site + lives 全空 | toast `"订阅内容无可用源"` |
| 登录页网络异常 | toast `"网络异常: ${e.message}"` |
| 启动后台刷新失败 | `MoonToast.show("订阅刷新失败")` |
| 设置页「立即刷新订阅」失败 | toast `"订阅刷新失败"`，URL / sources 不动 |
| 设置页改 URL 失败 | toast 同登录页；弹框保持打开 |

### 边界
- **首次启动直入本地模式**：`isLocalMode=true` 是流程最后一步写入，理论上不会读到「已 local mode 但无 sources」的中间态。`LocalSourcesRepository.getSearchResources()` 缺数据返回空 list，`SearchService.localSearch` 已有「没有可用的搜索资源」分支兜底。
- **历史 prefs 残留 `SearchOrigin.SERVER_AGGREGATED`**：`getSearchOriginSync()` 在本地模式下强制返回 `TV_DIRECT`；UI 隐藏选择项，看不到也修不了；切回服务端模式时历史值仍在（用户偏好不丢）。
- **本地模式期间的 `RemoteControlServer` / `sessionToken`**：完全不动。Web 遥控器、文本注入照常。
- **本地模式期间访问 LoginScreen**（从设置「退出本地模式」回来）：`isLocalMode=false` 已清，登录页默认账号表单；品牌点暗门仍可再切到本地模式。
- **防御：`ApiService.buildUrl()` 在本地模式下因 cookies 为空抛 `ApiException`** —— 万一漏迁移一个调用点，会立即失败、便于发现，胜过静默错误。
- **R8 / ProGuard**：新增 LocalStore 只复用既有 `model.**` 类，已在 keep 规则内（`app/proguard-rules.pro` 的 `kotlinx.serialization` 段），无需新增。

### 测试

#### 单元（`./gradlew testDebugUnitTest`）
- **`Base58Test`**
  - mobile 实测订阅样本字符串解码 → 比对预期 UTF-8 JSON
  - 边界：空串、单字符、含前导 `1`（前导零字节）
  - 错误：含非字母表字符 → throw `IllegalArgumentException`
- **`SubscriptionServiceTest`**
  - 解析含 `api_site` + `lives` 的样本 JSON → 校验 list 完整
  - 单边 `api_site` 缺失 / `lives` 缺失 → 另一边正常解析
  - 两边都空 → 抛 `SubscriptionException("订阅内容无可用源")`
  - source 缺 `key` 字段时用 map 的 entry key 兜底
- **`LocalUserDataRepositoryTest`**（Robolectric）
  - 收藏 add / isFavorite / unfavorite / clear 闭环
  - 播放记录覆盖（同 source+id 重复 save 后只有一条，最新覆盖旧）
  - 搜索历史去重 + 20 条上限（添加第 21 条，最旧的被踢出）
- **`RepositoriesTest`**
  - `isLocalMode=true` → 工厂返回 `LocalXxxRepository`
  - `isLocalMode=false` → 工厂返回 `ServerXxxRepository`
  - 模式切换后下次调用立即生效

#### 手动 E2E（AVD `Selene_TV`，adb 全路径，参考 memory `tv-emulator-e2e`）
1. 冷启动 → LoginScreen 账号表单
2. D-pad 焦点 → 品牌点 → `adb shell input keyevent --longpress 23` → toast 切表单
3. Web 遥控器输入订阅 URL → 「进入本地模式」 → 进首页
4. 搜索 Tab：搜索关键词 → 结果来自订阅 `api_site`、不走 SSE
5. 直播 Tab：频道列表来自订阅 `lives`
6. 详情页 → 收藏 → 播放 → 杀 app → 重启 → 收藏 + 「继续观看」保留
7. 设置 → 「立即刷新订阅」 → 成功 toast
8. 设置 → 「订阅链接」改为不同 URL → 触发清除确认弹框 → 选「是」 → 收藏与历史清空
9. 设置 → 「退出本地模式」 → 二次确认 → 回 LoginScreen 账号表单 + `LocalStore` 已清

---

## 实施顺序（建议）

1. **基础设施**：`AppDataService.isLocalMode*` + `Base58` + `Base58Test`
2. **存储层**：`LocalStore` + `LocalUserDataRepository` + `LocalSourcesRepository` + 各自单测
3. **接口层**：`UserDataRepository` / `SourcesRepository` 接口 + `Server*Repository`（薄包装）+ `Repositories` 工厂 + `RepositoriesTest`
4. **订阅服务**：`SubscriptionService.fetchAndParse` + `refreshAndPersist` + `SubscriptionServiceTest`
5. **调用点迁移**：13 处 `ApiService` 调用 → `Repositories.X().Y()`；`isLoggedInSync()` 守卫 → `|| isLocalModeSync()`
6. **启动链路**：`SeleneApplication.onCreate` 后台刷新 + `AppNavHost` 起点判定 + `MainActivity.refreshLoginCookieIfNeeded` 短路 + `getSearchOriginSync` 兜底
7. **登录页**：`isLocalMode` 状态机 + 本地模式表单 + `handleLocalModeLogin` + 二次确认弹框抽共享
8. **设置页**：「本地模式」分区 + 隐藏「搜索」分区 + 「立即刷新」 + 「退出本地模式」
9. **E2E 演练**：按 §6 测试清单走一遍

每步均可独立编译、独立验证；步骤 5 是触达面最广的批量改动，但接口形状与 `ApiService` 1:1，机械迁移。
