# TV 本地模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `docs/superpowers/specs/2026-05-28-tv-local-mode-design.md`

**Goal:** 引入「本地模式」—— 用户无需服务端账号、仅通过订阅链接即可使用 App；订阅返回的搜索源 / 直播源 + 收藏 / 播放记录 / 搜索历史 全部本地化。

**Architecture:** 抽 `UserDataRepository` / `SourcesRepository` 接口；工厂按 `AppDataService.isLocalModeSync()` 路由 Server（包裹 `ApiService`）/ Local（基于 `LocalStore` SharedPreferences）。`ApiService` 不动，成为 ServerRepository 内部细节。13 处调用点改走 `Repositories.X()`。登录页品牌点暗门 toggle 两套表单。设置页本地模式下「账户」分区改为「本地模式」分区，「搜索」分区整段隐藏。`SeleneApplication.onCreate` 起 fire-and-forget 后台刷新订阅。

**Tech Stack:** Kotlin 2.2、Compose for TV、JUnit 4（无 Robolectric，按项目风格抽纯逻辑 helper 测试）、kotlinx.serialization、`androidx.tv.material3`、SharedPreferences。新增依赖：**无**（Base58 内置实现，订阅 fetch 用 `HttpURLConnection` 沿用「信任所有证书」约定）。

**Branch:** 已切到 `feat/local-mode-design`，spec 已 commit。后续每个 task 一个 commit。

---

## File Structure

### 新建
| 路径 | 职责 |
|---|---|
| `app/src/main/java/org/moontechlab/selenetv/util/Base58.kt` | 纯函数 Base58 解码 |
| `app/src/main/java/org/moontechlab/selenetv/data/UserDataRepository.kt` | 接口：收藏/播放记录/搜索历史 |
| `app/src/main/java/org/moontechlab/selenetv/data/SourcesRepository.kt` | 接口：搜索源/直播源 |
| `app/src/main/java/org/moontechlab/selenetv/data/Repositories.kt` | 工厂：按 `isLocalModeSync()` 路由 |
| `app/src/main/java/org/moontechlab/selenetv/data/server/ServerUserDataRepository.kt` | 委托 `ApiService` |
| `app/src/main/java/org/moontechlab/selenetv/data/server/ServerSourcesRepository.kt` | 委托 `ApiService` |
| `app/src/main/java/org/moontechlab/selenetv/data/local/LocalStore.kt` | SharedPreferences `selene_tv_local` 包装 |
| `app/src/main/java/org/moontechlab/selenetv/data/local/LocalUserDataRepository.kt` | 本地用户数据 + 纯 upsert 助手 |
| `app/src/main/java/org/moontechlab/selenetv/data/local/LocalSourcesRepository.kt` | 本地源 |
| `app/src/main/java/org/moontechlab/selenetv/service/SubscriptionService.kt` | 订阅 fetch + parse + refresh |
| `app/src/main/java/org/moontechlab/selenetv/ui/SharedDialogs.kt` | `AnimatedSelectDialog` + `ConfirmDialogContent` + `DialogButton`（从 SettingsTab 抽出） |
| `app/src/test/java/org/moontechlab/selenetv/util/Base58Test.kt` | Base58 单测 |
| `app/src/test/java/org/moontechlab/selenetv/service/SubscriptionServiceTest.kt` | 解析单测 |
| `app/src/test/java/org/moontechlab/selenetv/data/local/LocalUserDataRepositoryHelpersTest.kt` | upsert 纯助手单测 |

### 修改
| 路径 | 改动要点 |
|---|---|
| `app/src/main/java/org/moontechlab/selenetv/service/AppDataService.kt` | `isLocalMode` 字段 + `getSearchOriginSync()` 兜底 |
| `app/src/main/java/org/moontechlab/selenetv/model/LiveSource.kt` | 加 `@Serializable` |
| `app/src/main/java/org/moontechlab/selenetv/SeleneApplication.kt` | 新增后台刷新订阅分支 |
| `app/src/main/java/org/moontechlab/selenetv/MainActivity.kt:122` | `refreshLoginCookieIfNeeded` 本地模式短路 |
| `app/src/main/java/org/moontechlab/selenetv/screen/AppNavHost.kt:28` | gate logic |
| `app/src/main/java/org/moontechlab/selenetv/service/SearchService.kt:118` | `Repositories.sources()` |
| `app/src/main/java/org/moontechlab/selenetv/service/DownstreamService.kt:332` | `Repositories.sources()` |
| `app/src/main/java/org/moontechlab/selenetv/service/LiveService.kt:55` | `Repositories.sources()` |
| `app/src/main/java/org/moontechlab/selenetv/screen/tab/HomeTab.kt` | 5 处 ApiService → Repository + `isLoggedIn` guards 加 `|| isLocalModeSync()` |
| `app/src/main/java/org/moontechlab/selenetv/screen/tab/SearchTab.kt` | 3 处 ApiService → Repository |
| `app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt` | 8 处 ApiService → Repository |
| `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt` | 2 处 ApiService → Repository |
| `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayProgressReporter.kt` | 1 处 ApiService → Repository |
| `app/src/main/java/org/moontechlab/selenetv/screen/tab/SettingsTab.kt` | 抽出共享弹框；新增「本地模式」分区；隐藏「搜索」分区 |
| `app/src/main/java/org/moontechlab/selenetv/screen/LoginScreen.kt` | `isLocalMode` 状态 + 本地模式表单 + `handleLocalModeLogin` |

---

## Tasks

### Task 1: `AppDataService` 加 `isLocalMode` 字段

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/AppDataService.kt`

- [ ] **Step 1: 加 prefs key + 内存缓存字段**

在 `AppDataService.kt` 现有 keys 段（约 18-31 行）加一行：
```kotlin
    private const val KEY_IS_LOCAL_MODE = "is_local_mode"
```
在 `prefs` 字段下、`sessionToken` 字段附近加：
```kotlin
    @Volatile
    private var isLocalModeCache: Boolean = false
```

- [ ] **Step 2: 在 `init()` 中预热缓存**

`init(context)` 现有代码（约 41-45 行）改为：
```kotlin
fun init(context: Context) {
    prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    isLocalModeCache = prefs.getBoolean(KEY_IS_LOCAL_MODE, false)
    generateSessionToken()
}
```

- [ ] **Step 3: 加 saveIsLocalMode / isLocalMode / isLocalModeSync**

在「用户数据相关」段末尾（紧跟 `hasAutoLoginData` 约 179 行后）加：
```kotlin
    // ==================== 本地模式 ====================

    /**
     * 保存本地模式开关；同步更新内存缓存。
     */
    suspend fun saveIsLocalMode(isLocalMode: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_IS_LOCAL_MODE, isLocalMode).apply()
        isLocalModeCache = isLocalMode
    }

    suspend fun isLocalMode(): Boolean = withContext(Dispatchers.IO) {
        prefs.getBoolean(KEY_IS_LOCAL_MODE, false)
    }

    /** 同步读，零阻塞；`init()` 时预热缓存。 */
    fun isLocalModeSync(): Boolean = isLocalModeCache
```

- [ ] **Step 4: `getSearchOriginSync` 加本地模式兜底**

替换现有 `getSearchOriginSync()`（约 441-442 行）：
```kotlin
    fun getSearchOriginSync(): SearchOrigin {
        if (isLocalModeCache) return SearchOrigin.TV_DIRECT
        return SearchOrigin.fromKey(prefs.getString(KEY_SEARCH_ORIGIN, SearchOrigin.TV_DIRECT.key) ?: SearchOrigin.TV_DIRECT.key)
    }
```

- [ ] **Step 5: 编译验证**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/AppDataService.kt
git commit -m "feat(local-mode): AppDataService.isLocalMode 持久态 + SearchOrigin 兜底"
```

---

### Task 2: `Base58` 解码工具 + 单测

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/util/Base58.kt`
- Create: `app/src/test/java/org/moontechlab/selenetv/util/Base58Test.kt`

- [ ] **Step 1: 写单测先（TDD）**

创建 `Base58Test.kt`：
```kotlin
package org.moontechlab.selenetv.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class Base58Test {
    @Test fun decodes_empty_string_to_empty_bytes() {
        assertArrayEquals(ByteArray(0), Base58.decode(""))
    }

    @Test fun decodes_single_zero_byte_from_one() {
        assertArrayEquals(byteArrayOf(0x00), Base58.decode("1"))
    }

    @Test fun decodes_two_leading_zero_bytes_from_double_one() {
        assertArrayEquals(byteArrayOf(0x00, 0x00), Base58.decode("11"))
    }

    @Test fun decodes_bitcoin_genesis_vector() {
        // Base58 字典: 数字 "z" 对应单字节 0x39
        assertArrayEquals(byteArrayOf(0x39), Base58.decode("z"))
    }

    @Test fun decodes_text_payload_to_known_bytes() {
        // "Hello World!" → Base58 "2NEpo7TZRRrLZSi2U"
        val decoded = Base58.decode("2NEpo7TZRRrLZSi2U")
        assertEquals("Hello World!", String(decoded, Charsets.UTF_8))
    }

    @Test fun decodes_json_object_payload() {
        // {"a":1} → Base58
        val src = """{"a":1}"""
        // 通过反向验证：手工实测 base58 编码 {"a":1} = "S94yEpkmQc"
        val decoded = Base58.decode("S94yEpkmQc")
        assertEquals(src, String(decoded, Charsets.UTF_8))
    }

    @Test fun throws_on_invalid_character() {
        try {
            Base58.decode("0OIl")  // '0','O','I','l' 都不在字典中
            fail("应当抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.util.Base58Test"
```
Expected: 编译失败（`Base58` 未定义）

- [ ] **Step 3: 写实现**

创建 `Base58.kt`：
```kotlin
package org.moontechlab.selenetv.util

/**
 * 纯函数 Base58 解码（无 checksum，对齐 mobile 端 `bs58check` 库的 `base58` 子模块语义）。
 *
 * 算法标准 Base58：字符集 `123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz`
 * （去除易混淆 0/O/I/l）。前导字符 '1' 表示前导零字节，按个数保留。
 */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEX = IntArray(128) { -1 }.also { arr ->
        ALPHABET.forEachIndexed { i, c -> arr[c.code] = i }
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        var zeros = 0
        while (zeros < input.length && input[zeros] == '1') zeros++
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

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.util.Base58Test"
```
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/util/Base58.kt \
        app/src/test/java/org/moontechlab/selenetv/util/Base58Test.kt
git commit -m "feat(local-mode): Base58 解码工具 + 单测"
```

---

### Task 3: Repository 接口

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/data/UserDataRepository.kt`
- Create: `app/src/main/java/org/moontechlab/selenetv/data/SourcesRepository.kt`

- [ ] **Step 1: 写 UserDataRepository.kt**

```kotlin
package org.moontechlab.selenetv.data

import org.moontechlab.selenetv.model.FavoriteItem
import org.moontechlab.selenetv.model.PlayRecord

/**
 * 用户数据仓库：收藏 / 播放记录 / 搜索历史。
 *
 * 调用约定（对齐既有 ApiService）：所有方法同步阻塞，必须在 Dispatchers.IO 调用；
 * Server 实现失败抛 ApiException；Local 实现数据缺失返回空集合，不抛错。
 */
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
```

- [ ] **Step 2: 写 SourcesRepository.kt**

```kotlin
package org.moontechlab.selenetv.data

import org.moontechlab.selenetv.model.LiveSource
import org.moontechlab.selenetv.model.SearchResource

/**
 * 源仓库：搜索源 / 直播源。
 */
interface SourcesRepository {
    fun getSearchResources(): List<SearchResource>
    fun getLiveSources(): List<LiveSource>
}
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/data/UserDataRepository.kt \
        app/src/main/java/org/moontechlab/selenetv/data/SourcesRepository.kt
git commit -m "feat(local-mode): Repository 接口（UserDataRepository / SourcesRepository）"
```

---

### Task 4: Server 实现（薄包装 ApiService）

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/data/server/ServerUserDataRepository.kt`
- Create: `app/src/main/java/org/moontechlab/selenetv/data/server/ServerSourcesRepository.kt`

- [ ] **Step 1: 写 ServerUserDataRepository.kt**

```kotlin
package org.moontechlab.selenetv.data.server

import org.moontechlab.selenetv.data.UserDataRepository
import org.moontechlab.selenetv.model.FavoriteItem
import org.moontechlab.selenetv.model.PlayRecord
import org.moontechlab.selenetv.service.ApiService

/**
 * 服务端模式的用户数据实现：直接委托 ApiService（保持 1:1 行为）。
 */
object ServerUserDataRepository : UserDataRepository {
    override fun getFavorites(): List<FavoriteItem> = ApiService.getFavorites()
    override fun addFavorite(source: String, id: String, favoriteData: Map<String, Any?>) =
        ApiService.addFavorite(source, id, favoriteData)
    override fun unfavorite(source: String, id: String) = ApiService.unfavorite(source, id)

    override fun getPlayRecords(): Map<String, PlayRecord> = ApiService.getPlayRecords()
    override fun savePlayRecord(record: PlayRecord) = ApiService.savePlayRecord(record)
    override fun deletePlayRecord(source: String, id: String) = ApiService.deletePlayRecord(source, id)
    override fun clearPlayRecords() = ApiService.clearPlayRecords()

    override fun getSearchHistory(): List<String> = ApiService.getSearchHistory()
    override fun addSearchHistory(query: String) = ApiService.addSearchHistory(query)
    override fun deleteSearchHistory(query: String) = ApiService.deleteSearchHistory(query)
    override fun clearSearchHistory() = ApiService.clearSearchHistory()
}
```

- [ ] **Step 2: 写 ServerSourcesRepository.kt**

```kotlin
package org.moontechlab.selenetv.data.server

import org.moontechlab.selenetv.data.SourcesRepository
import org.moontechlab.selenetv.model.LiveSource
import org.moontechlab.selenetv.model.SearchResource
import org.moontechlab.selenetv.service.ApiService

object ServerSourcesRepository : SourcesRepository {
    override fun getSearchResources(): List<SearchResource> = ApiService.getSearchResources()
    override fun getLiveSources(): List<LiveSource> = ApiService.getLiveSources()
}
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/data/server/
git commit -m "feat(local-mode): Server 仓库实现（包裹 ApiService）"
```

---

### Task 5: `LiveSource` 加 `@Serializable`

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/model/LiveSource.kt`

`LiveSource` 当前未加 `@Serializable`，但 LocalStore 需要 kotlinx.serialization JSON 来存它。`SearchResource` / `FavoriteItem` / `PlayRecord` 已加。

- [ ] **Step 1: 加注解**

```kotlin
package org.moontechlab.selenetv.model

import kotlinx.serialization.Serializable

/**
 * 直播源数据模型
 */
@Serializable
data class LiveSource(
    val key: String,
    val name: String,
    val url: String,
    val ua: String = "",
    val epg: String = "",
    val from: String = "",
    val disabled: Boolean = false
)
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/model/LiveSource.kt
git commit -m "feat(local-mode): LiveSource 加 @Serializable 以便本地持久化"
```

---

### Task 6: `LocalStore` SharedPreferences 包装

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/data/local/LocalStore.kt`

`LocalStore` 是单例，需要 `init(context)`，prefs 名 `selene_tv_local`，与 `selene_tv_prefs` 隔离。

- [ ] **Step 1: 写 LocalStore.kt**

```kotlin
package org.moontechlab.selenetv.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.moontechlab.selenetv.model.FavoriteItem
import org.moontechlab.selenetv.model.LiveSource
import org.moontechlab.selenetv.model.PlayRecord
import org.moontechlab.selenetv.model.SearchResource

/**
 * 本地模式存储：SharedPreferences `selene_tv_local`。
 *
 * 命名隔离原因：与「账号设置」`selene_tv_prefs` 分开，便于「退出本地模式」一键清掉而不动账号设置。
 *
 * 读：同步、不抛错（缺数据 → 空集合）。
 * 写：`apply()` 异步落盘。
 */
object LocalStore {
    private const val TAG = "LocalStore"
    private const val PREFS_NAME = "selene_tv_local"

    private const val KEY_SUBSCRIPTION_URL = "subscription_url"
    private const val KEY_SEARCH_SOURCES = "search_sources"
    private const val KEY_LIVE_SOURCES = "live_sources"
    private const val KEY_PLAY_RECORDS = "play_records"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_SEARCH_HISTORY = "search_history"

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Volatile
    private var favoritesCache: List<FavoriteItem>? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 解析失败兜底：日志 + 默认值；不抛错。 */
    private inline fun <T> safeDecode(label: String, default: T, block: () -> T): T =
        runCatching(block).getOrElse {
            Log.w(TAG, "$label 解析失败: ${it.message}")
            default
        }

    // ==================== 订阅 URL ====================

    fun getSubscriptionUrl(): String? = prefs.getString(KEY_SUBSCRIPTION_URL, null)
    fun saveSubscriptionUrl(url: String) {
        prefs.edit().putString(KEY_SUBSCRIPTION_URL, url).apply()
    }

    // ==================== 搜索源 ====================

    fun getSearchSources(): List<SearchResource> {
        val s = prefs.getString(KEY_SEARCH_SOURCES, null) ?: return emptyList()
        return safeDecode("搜索源", emptyList()) { json.decodeFromString<List<SearchResource>>(s) }
    }

    fun saveSearchSources(sources: List<SearchResource>) {
        prefs.edit().putString(KEY_SEARCH_SOURCES, json.encodeToString(sources)).apply()
    }

    // ==================== 直播源 ====================

    fun getLiveSources(): List<LiveSource> {
        val s = prefs.getString(KEY_LIVE_SOURCES, null) ?: return emptyList()
        return safeDecode("直播源", emptyList()) { json.decodeFromString<List<LiveSource>>(s) }
    }

    fun saveLiveSources(sources: List<LiveSource>) {
        prefs.edit().putString(KEY_LIVE_SOURCES, json.encodeToString(sources)).apply()
    }

    // ==================== 播放记录 ====================
    // 序列化形态：Map<key, PlayRecord>，key = "${source}+${id}"。

    fun getPlayRecords(): Map<String, PlayRecord> {
        val s = prefs.getString(KEY_PLAY_RECORDS, null) ?: return emptyMap()
        return safeDecode("播放记录", emptyMap()) { json.decodeFromString<Map<String, PlayRecord>>(s) }
    }

    fun savePlayRecords(records: Map<String, PlayRecord>) {
        prefs.edit().putString(KEY_PLAY_RECORDS, json.encodeToString(records)).apply()
    }

    // ==================== 收藏 ====================
    // 序列化形态：Map<key, FavoriteItem>，key = "${source}+${id}"。
    // 同步内存缓存以便高频读（DetailScreen 进退场反复读收藏列表）。

    fun getFavorites(): List<FavoriteItem> {
        favoritesCache?.let { return it }
        val s = prefs.getString(KEY_FAVORITES, null)
        val list = if (s.isNullOrBlank()) emptyList() else safeDecode("收藏夹", emptyList<FavoriteItem>()) {
            json.decodeFromString<Map<String, FavoriteItem>>(s).values
                .sortedByDescending { it.saveTime }
                .filter { it.origin != "live" }
        }
        favoritesCache = list
        return list
    }

    fun saveFavorites(favorites: List<FavoriteItem>) {
        val map = favorites.associateBy { "${it.source}+${it.id}" }
        prefs.edit().putString(KEY_FAVORITES, json.encodeToString(map)).apply()
        favoritesCache = favorites
    }

    // ==================== 搜索历史 ====================

    fun getSearchHistory(): List<String> {
        val s = prefs.getString(KEY_SEARCH_HISTORY, null) ?: return emptyList()
        return safeDecode("搜索历史", emptyList()) { json.decodeFromString<List<String>>(s) }
    }

    fun saveSearchHistory(history: List<String>) {
        prefs.edit().putString(KEY_SEARCH_HISTORY, json.encodeToString(history)).apply()
    }

    // ==================== 清空 ====================

    fun clearAll() {
        prefs.edit()
            .remove(KEY_SUBSCRIPTION_URL)
            .remove(KEY_SEARCH_SOURCES)
            .remove(KEY_LIVE_SOURCES)
            .remove(KEY_PLAY_RECORDS)
            .remove(KEY_FAVORITES)
            .remove(KEY_SEARCH_HISTORY)
            .apply()
        favoritesCache = null
    }
}
```

- [ ] **Step 2: 在 SeleneApplication 初始化**

修改 `app/src/main/java/org/moontechlab/selenetv/SeleneApplication.kt` 的 `onCreate`（约 27-35 行）：
```kotlin
override fun onCreate() {
    super.onCreate()
    AppDataService.init(this)
    LocalStore.init(this)                    // 新增
    Log.d("SeleneApplication", "Application initialized")
    startRemoteControlServer()
}
```
顶部加 import：
```kotlin
import org.moontechlab.selenetv.data.local.LocalStore
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/data/local/LocalStore.kt \
        app/src/main/java/org/moontechlab/selenetv/SeleneApplication.kt
git commit -m "feat(local-mode): LocalStore SharedPreferences 包装 + Application 初始化"
```

---

### Task 7: `LocalUserDataRepository` 助手 + 测试 + 实现

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/data/local/LocalUserDataRepository.kt`
- Create: `app/src/test/java/org/moontechlab/selenetv/data/local/LocalUserDataRepositoryHelpersTest.kt`

按项目风格抽纯助手做单测；SharedPreferences 走真机/AVD 集成验证。

- [ ] **Step 1: 写助手单测先**

```kotlin
package org.moontechlab.selenetv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import org.moontechlab.selenetv.model.FavoriteItem
import org.moontechlab.selenetv.model.PlayRecord

class LocalUserDataRepositoryHelpersTest {
    @Test fun upsertSearchHistory_trims_and_deduplicates_keeping_newest_first() {
        val existing = listOf("foo", "bar", "baz")
        assertEquals(listOf("bar", "foo", "baz"), LocalUserDataRepository.upsertSearchHistory(existing, " bar "))
    }

    @Test fun upsertSearchHistory_caps_at_20() {
        val existing = (1..20).map { "q$it" }  // q1..q20
        val out = LocalUserDataRepository.upsertSearchHistory(existing, "new")
        assertEquals(20, out.size)
        assertEquals("new", out.first())
        assertEquals("q19", out.last())  // q20 被踢出
    }

    @Test fun upsertSearchHistory_ignores_blank() {
        val existing = listOf("a")
        assertEquals(existing, LocalUserDataRepository.upsertSearchHistory(existing, "   "))
    }

    @Test fun upsertFavorite_inserts_new_at_head() {
        val existing = listOf(fav("s1", "id1", saveTime = 100L))
        val out = LocalUserDataRepository.upsertFavorite(existing, fav("s2", "id2", saveTime = 200L))
        assertEquals(listOf("s2+id2", "s1+id1"), out.map { "${it.source}+${it.id}" })
    }

    @Test fun upsertFavorite_replaces_existing_same_key() {
        val existing = listOf(fav("s1", "id1", saveTime = 100L, title = "old"))
        val out = LocalUserDataRepository.upsertFavorite(existing, fav("s1", "id1", saveTime = 200L, title = "new"))
        assertEquals(1, out.size)
        assertEquals("new", out[0].title)
        assertEquals(200L, out[0].saveTime)
    }

    @Test fun upsertPlayRecord_overwrites_same_key() {
        val k = "src+1"
        val existing = mapOf(k to record("src", "1", playTime = 10))
        val out = LocalUserDataRepository.upsertPlayRecord(existing, record("src", "1", playTime = 50))
        assertEquals(1, out.size)
        assertEquals(50L, out[k]!!.playTime)
    }

    @Test fun favoriteFromMap_builds_item_from_snake_case_keys() {
        val map: Map<String, Any?> = mapOf(
            "title" to "T",
            "source_name" to "Src",
            "year" to "2025",
            "cover" to "c.jpg",
            "total_episodes" to 12,
            "save_time" to 999L,
        )
        val item = LocalUserDataRepository.favoriteFromMap("s", "i", map)
        assertEquals("s", item.source)
        assertEquals("i", item.id)
        assertEquals("T", item.title)
        assertEquals("Src", item.sourceName)
        assertEquals("2025", item.year)
        assertEquals("c.jpg", item.cover)
        assertEquals(12, item.totalEpisodes)
        assertEquals(999L, item.saveTime)
    }

    private fun fav(source: String, id: String, saveTime: Long = 0L, title: String = "t") =
        FavoriteItem(id, source, title, "sn", "2025", "c", 1, saveTime, "")

    private fun record(source: String, id: String, playTime: Long = 0L) =
        PlayRecord(id, source, "t", "sn", "2025", "c", 0, 1, playTime, 0, 0, "")
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.data.local.LocalUserDataRepositoryHelpersTest"
```
Expected: 编译失败（helpers 未定义）

- [ ] **Step 3: 写 LocalUserDataRepository 实现**

```kotlin
package org.moontechlab.selenetv.data.local

import org.moontechlab.selenetv.data.UserDataRepository
import org.moontechlab.selenetv.model.FavoriteItem
import org.moontechlab.selenetv.model.PlayRecord

/**
 * 本地模式的用户数据实现：基于 LocalStore SharedPreferences。
 *
 * 失败语义：与 ApiService 抛 ApiException 不同，缺数据/解析失败一律返回空集合，不抛错。
 * 业务逻辑（去重 / 排序 / 20 条搜索历史上限）以 internal 纯函数实现并单测；I/O 部分走真机集成验证。
 */
object LocalUserDataRepository : UserDataRepository {

    // ============== 纯函数助手（可单测） ==============

    /** 添加搜索历史：trim → 去重 → 插队首 → 20 条上限。 */
    internal fun upsertSearchHistory(existing: List<String>, query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return existing
        val deduped = listOf(trimmed) + existing.filter { it != trimmed }
        return deduped.take(20)
    }

    /** 加入收藏：同 key 替换 + 插队首；空集合自动构造。 */
    internal fun upsertFavorite(existing: List<FavoriteItem>, item: FavoriteItem): List<FavoriteItem> {
        val key = "${item.source}+${item.id}"
        val rest = existing.filterNot { "${it.source}+${it.id}" == key }
        return listOf(item) + rest
    }

    internal fun upsertPlayRecord(existing: Map<String, PlayRecord>, record: PlayRecord): Map<String, PlayRecord> {
        val key = "${record.source}+${record.id}"
        return existing.toMutableMap().apply { this[key] = record }
    }

    /** ApiService.addFavorite 的 favoriteData Map 形态 → FavoriteItem。 */
    internal fun favoriteFromMap(source: String, id: String, data: Map<String, Any?>): FavoriteItem {
        return FavoriteItem(
            id = id,
            source = source,
            title = (data["title"] as? String).orEmpty(),
            sourceName = (data["source_name"] as? String).orEmpty(),
            year = (data["year"] as? String).orEmpty(),
            cover = (data["cover"] as? String).orEmpty(),
            totalEpisodes = (data["total_episodes"] as? Number)?.toInt() ?: 0,
            saveTime = (data["save_time"] as? Number)?.toLong() ?: 0L,
            origin = (data["origin"] as? String).orEmpty(),
        )
    }

    // ============== UserDataRepository 实现 ==============

    override fun getFavorites(): List<FavoriteItem> = LocalStore.getFavorites()

    override fun addFavorite(source: String, id: String, favoriteData: Map<String, Any?>) {
        val item = favoriteFromMap(source, id, favoriteData)
        LocalStore.saveFavorites(upsertFavorite(LocalStore.getFavorites(), item))
    }

    override fun unfavorite(source: String, id: String) {
        val key = "$source+$id"
        val next = LocalStore.getFavorites().filterNot { "${it.source}+${it.id}" == key }
        LocalStore.saveFavorites(next)
    }

    override fun getPlayRecords(): Map<String, PlayRecord> = LocalStore.getPlayRecords()

    override fun savePlayRecord(record: PlayRecord) {
        LocalStore.savePlayRecords(upsertPlayRecord(LocalStore.getPlayRecords(), record))
    }

    override fun deletePlayRecord(source: String, id: String) {
        val key = "$source+$id"
        LocalStore.savePlayRecords(LocalStore.getPlayRecords() - key)
    }

    override fun clearPlayRecords() {
        LocalStore.savePlayRecords(emptyMap())
    }

    override fun getSearchHistory(): List<String> = LocalStore.getSearchHistory()

    override fun addSearchHistory(query: String) {
        LocalStore.saveSearchHistory(upsertSearchHistory(LocalStore.getSearchHistory(), query))
    }

    override fun deleteSearchHistory(query: String) {
        LocalStore.saveSearchHistory(LocalStore.getSearchHistory() - query)
    }

    override fun clearSearchHistory() {
        LocalStore.saveSearchHistory(emptyList())
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.data.local.LocalUserDataRepositoryHelpersTest"
```
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/data/local/LocalUserDataRepository.kt \
        app/src/test/java/org/moontechlab/selenetv/data/local/LocalUserDataRepositoryHelpersTest.kt
git commit -m "feat(local-mode): LocalUserDataRepository + 助手单测"
```

---

### Task 8: `LocalSourcesRepository`

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/data/local/LocalSourcesRepository.kt`

- [ ] **Step 1: 写 LocalSourcesRepository.kt**

```kotlin
package org.moontechlab.selenetv.data.local

import org.moontechlab.selenetv.data.SourcesRepository
import org.moontechlab.selenetv.model.LiveSource
import org.moontechlab.selenetv.model.SearchResource

object LocalSourcesRepository : SourcesRepository {
    override fun getSearchResources(): List<SearchResource> = LocalStore.getSearchSources()
    override fun getLiveSources(): List<LiveSource> = LocalStore.getLiveSources()
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/data/local/LocalSourcesRepository.kt
git commit -m "feat(local-mode): LocalSourcesRepository"
```

---

### Task 9: `Repositories` 工厂

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/data/Repositories.kt`

- [ ] **Step 1: 写 Repositories.kt**

```kotlin
package org.moontechlab.selenetv.data

import org.moontechlab.selenetv.data.local.LocalSourcesRepository
import org.moontechlab.selenetv.data.local.LocalUserDataRepository
import org.moontechlab.selenetv.data.server.ServerSourcesRepository
import org.moontechlab.selenetv.data.server.ServerUserDataRepository
import org.moontechlab.selenetv.service.AppDataService

/**
 * 仓库工厂：每次调用按当前模式（AppDataService.isLocalModeSync）路由到对应实现。
 *
 * 不缓存实例 —— 模式切换后下次调用即得新实现，无须刷新调用方。
 */
object Repositories {
    fun userData(): UserDataRepository =
        if (AppDataService.isLocalModeSync()) LocalUserDataRepository else ServerUserDataRepository

    fun sources(): SourcesRepository =
        if (AppDataService.isLocalModeSync()) LocalSourcesRepository else ServerSourcesRepository
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/data/Repositories.kt
git commit -m "feat(local-mode): Repositories 工厂（按 isLocalModeSync 路由）"
```

---

### Task 10: `SubscriptionService` 解析 + 单测

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/service/SubscriptionService.kt`
- Create: `app/src/test/java/org/moontechlab/selenetv/service/SubscriptionServiceTest.kt`

仅解析逻辑放本 task；HTTP fetch + persist 留下一 task。

- [ ] **Step 1: 写解析单测先**

```kotlin
package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SubscriptionServiceTest {
    @Test fun parses_api_site_and_lives() {
        val payload = """
            {
              "api_site": {
                "dyttzy": {"key":"dyttzy","name":"电影天堂","api":"https://a.example/api","detail":"https://a.example/detail","from":"custom"},
                "heimuer": {"name":"黑木耳","api":"https://b.example/api"}
              },
              "lives": {
                "iptv1": {"key":"iptv1","name":"IPTV1","url":"https://l.example/iptv1.m3u","ua":"ua1","epg":"https://epg.example","from":"custom"}
              }
            }
        """.trimIndent()
        val parsed = SubscriptionService.parseSubscriptionJson(payload)
        assertEquals(2, parsed.searchResources.size)
        assertEquals(1, parsed.liveSources.size)
        // 缺 "key" 时用 map entry key 兜底
        val heimuer = parsed.searchResources.first { it.name == "黑木耳" }
        assertEquals("heimuer", heimuer.key)
    }

    @Test fun lone_api_site_is_accepted() {
        val payload = """{"api_site":{"a":{"name":"A","api":"https://x"}}}"""
        val parsed = SubscriptionService.parseSubscriptionJson(payload)
        assertEquals(1, parsed.searchResources.size)
        assertEquals(0, parsed.liveSources.size)
    }

    @Test fun lone_lives_is_accepted() {
        val payload = """{"lives":{"l":{"name":"L","url":"https://x"}}}"""
        val parsed = SubscriptionService.parseSubscriptionJson(payload)
        assertEquals(0, parsed.searchResources.size)
        assertEquals(1, parsed.liveSources.size)
    }

    @Test fun empty_payload_throws() {
        try {
            SubscriptionService.parseSubscriptionJson("""{"api_site":{},"lives":{}}""")
            fail("应当抛 SubscriptionException")
        } catch (e: SubscriptionService.SubscriptionException) {
            assertEquals("订阅内容无可用源", e.message)
        }
    }

    @Test fun malformed_json_throws() {
        try {
            SubscriptionService.parseSubscriptionJson("not json")
            fail("应当抛 SubscriptionException")
        } catch (_: SubscriptionService.SubscriptionException) {}
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.service.SubscriptionServiceTest"
```
Expected: 编译失败

- [ ] **Step 3: 写 SubscriptionService 解析实现**

```kotlin
package org.moontechlab.selenetv.service

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.moontechlab.selenetv.model.LiveSource
import org.moontechlab.selenetv.model.SearchResource

object SubscriptionService {
    private const val TAG = "SubscriptionService"

    class SubscriptionException(message: String) : Exception(message)

    data class ParsedSubscription(
        val searchResources: List<SearchResource>,
        val liveSources: List<LiveSource>,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 纯解析：Base58 解码后的 JSON 字符串 → ParsedSubscription。失败抛 SubscriptionException。 */
    fun parseSubscriptionJson(text: String): ParsedSubscription {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw SubscriptionException("订阅内容格式无效: ${e.message}")
        }

        val searchResources = (root["api_site"]?.jsonObject ?: emptyMap()).entries.mapNotNull { (mapKey, value) ->
            val obj = try { value.jsonObject } catch (_: Exception) { return@mapNotNull null }
            SearchResource(
                key = obj["key"]?.jsonPrimitive?.contentOrNull ?: mapKey,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                api = obj["api"]?.jsonPrimitive?.contentOrNull ?: "",
                detail = obj["detail"]?.jsonPrimitive?.contentOrNull,
                from = obj["from"]?.jsonPrimitive?.contentOrNull,
                disabled = false,
            )
        }

        val liveSources = (root["lives"]?.jsonObject ?: emptyMap()).entries.mapNotNull { (mapKey, value) ->
            val obj = try { value.jsonObject } catch (_: Exception) { return@mapNotNull null }
            LiveSource(
                key = obj["key"]?.jsonPrimitive?.contentOrNull ?: mapKey,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                ua = obj["ua"]?.jsonPrimitive?.contentOrNull ?: "",
                epg = obj["epg"]?.jsonPrimitive?.contentOrNull ?: "",
                from = obj["from"]?.jsonPrimitive?.contentOrNull ?: "",
                disabled = false,
            )
        }

        if (searchResources.isEmpty() && liveSources.isEmpty()) {
            throw SubscriptionException("订阅内容无可用源")
        }

        return ParsedSubscription(searchResources, liveSources)
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.service.SubscriptionServiceTest"
```
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/SubscriptionService.kt \
        app/src/test/java/org/moontechlab/selenetv/service/SubscriptionServiceTest.kt
git commit -m "feat(local-mode): SubscriptionService 解析 + 单测"
```

---

### Task 11: `SubscriptionService.fetchAndParse` + `refreshAndPersist`

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/SubscriptionService.kt`

HTTP fetch 沿用项目「信任所有证书 + 跳过 hostname 校验」约定。参考 `service/LiveService.kt` 现有 OkHttp/HttpsURLConnection 用法。

- [ ] **Step 1: 加 fetchAndParse 和 refreshAndPersist**

在 `SubscriptionService` 顶层（class 闭合前）追加：

```kotlin
    /**
     * 同步阻塞：HTTP GET 订阅 URL → Base58 解码 → JSON 解析 → 可达性校验。失败抛 SubscriptionException。
     * 必须在 Dispatchers.IO 调用。
     */
    fun fetchAndParse(url: String): ParsedSubscription {
        val body = httpGet(url)
        val decoded = try {
            org.moontechlab.selenetv.util.Base58.decode(body)
        } catch (e: Exception) {
            throw SubscriptionException("订阅内容格式无效: Base58 解码失败 (${e.message})")
        }
        val text = String(decoded, Charsets.UTF_8)
        return parseSubscriptionJson(text)
    }

    private fun httpGet(url: String): String {
        val u = java.net.URL(url)
        val conn = u.openConnection() as java.net.HttpURLConnection
        // 自签证书：沿用项目约定，HTTPS 时信任所有 + 跳过 hostname 校验
        if (conn is javax.net.ssl.HttpsURLConnection) {
            conn.sslSocketFactory = trustAllSslSocketFactory()
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        try {
            val code = conn.responseCode
            if (code != 200) throw SubscriptionException("获取订阅内容失败 ($code)")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun trustAllSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val tm = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, tm, java.security.SecureRandom())
        return ctx.socketFactory
    }

    /**
     * 后台刷新：使用已存订阅 URL 重新拉取并仅覆盖 sources（不动用户数据、不动订阅 URL）。
     * 返回 true=成功，false=失败（已记录日志）。
     */
    suspend fun refreshAndPersist(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val url = org.moontechlab.selenetv.data.local.LocalStore.getSubscriptionUrl() ?: return@withContext false
        runCatching { fetchAndParse(url) }.fold(
            onSuccess = { parsed ->
                org.moontechlab.selenetv.data.local.LocalStore.saveSearchSources(parsed.searchResources)
                org.moontechlab.selenetv.data.local.LocalStore.saveLiveSources(parsed.liveSources)
                true
            },
            onFailure = {
                Log.w(TAG, "刷新订阅失败: ${it.message}")
                false
            },
        )
    }
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.service.SubscriptionServiceTest"
```
Expected: BUILD SUCCESSFUL + 解析单测仍 PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/SubscriptionService.kt
git commit -m "feat(local-mode): SubscriptionService.fetchAndParse + refreshAndPersist"
```

---

### Task 12: 迁移 service 层调用点（3 处）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/SearchService.kt:118`
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/DownstreamService.kt:332`
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/LiveService.kt:55`

- [ ] **Step 1: SearchService**

`SearchService.kt:118` 现行：
```kotlin
            val allResources = ApiService.getSearchResources()
```
改为：
```kotlin
            val allResources = org.moontechlab.selenetv.data.Repositories.sources().getSearchResources()
```

- [ ] **Step 2: DownstreamService**

`DownstreamService.kt:332` 现行：
```kotlin
                val resource = ApiService.getSearchResources()
```
改为：
```kotlin
                val resource = org.moontechlab.selenetv.data.Repositories.sources().getSearchResources()
```

- [ ] **Step 3: LiveService**

`LiveService.kt:55` 现行：
```kotlin
            val sources = ApiService.getLiveSources()
```
改为：
```kotlin
            val sources = org.moontechlab.selenetv.data.Repositories.sources().getLiveSources()
```

- [ ] **Step 4: 编译验证**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/SearchService.kt \
        app/src/main/java/org/moontechlab/selenetv/service/DownstreamService.kt \
        app/src/main/java/org/moontechlab/selenetv/service/LiveService.kt
git commit -m "refactor(local-mode): service 层 3 处源访问改走 Repositories.sources()"
```

---

### Task 13: 迁移 HomeTab 调用点（5 处）+ 守卫

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/tab/HomeTab.kt`

5 处 ApiService 调用 + `isLoggedInSync()` 守卫加 `|| isLocalModeSync()`。

- [ ] **Step 1: 顶部加 import**

`HomeTab.kt` 顶部 import 段添加：
```kotlin
import org.moontechlab.selenetv.data.Repositories
```

- [ ] **Step 2: 替换 5 处 ApiService**

| 行（参考） | 现行 | 改为 |
|---|---|---|
| 115 | `ApiService.deletePlayRecord(video.source, video.id)` | `Repositories.userData().deletePlayRecord(video.source, video.id)` |
| 132 | `ApiService.addFavorite(...)` | `Repositories.userData().addFavorite(...)` |
| 158 | `ApiService.unfavorite(video.source, video.id)` | `Repositories.userData().unfavorite(video.source, video.id)` |
| 196 | `ApiService.getPlayRecords()` | `Repositories.userData().getPlayRecords()` |
| 214 | `ApiService.getFavorites()` | `Repositories.userData().getFavorites()` |

逐行 grep 替换：
```bash
grep -n "ApiService\." app/src/main/java/org/moontechlab/selenetv/screen/tab/HomeTab.kt
```
逐个 Edit 替换（保持原参数不变，只换前缀 `ApiService` → `Repositories.userData()`）。

- [ ] **Step 3: 守卫加 || isLocalModeSync()**

`grep -n "isLoggedInSync\|isLoggedIn" app/src/main/java/org/moontechlab/selenetv/screen/tab/HomeTab.kt`，对每个用作「user data 可读」语义的位置加 `|| AppDataService.isLocalModeSync()`。

- [ ] **Step 4: 编译验证**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/tab/HomeTab.kt
git commit -m "refactor(local-mode): HomeTab 5 处 ApiService → Repositories.userData() + 守卫"
```

---

### Task 14: 迁移 SearchTab 调用点（3 处）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/tab/SearchTab.kt`

- [ ] **Step 1: 顶部加 import**

```kotlin
import org.moontechlab.selenetv.data.Repositories
```

- [ ] **Step 2: 替换 3 处 ApiService**

| 行（参考） | 现行 | 改为 |
|---|---|---|
| 343 | `ApiService.getSearchHistory()` | `Repositories.userData().getSearchHistory()` |
| 398 | `ApiService.addSearchHistory(query)` | `Repositories.userData().addSearchHistory(query)` |
| 436 | `ApiService.addSearchHistory(keyword)` | `Repositories.userData().addSearchHistory(keyword)` |

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/tab/SearchTab.kt
git commit -m "refactor(local-mode): SearchTab 3 处 ApiService → Repositories.userData()"
```

---

### Task 15: 迁移 DetailScreen 调用点（8 处）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt`

- [ ] **Step 1: 顶部加 import**

```kotlin
import org.moontechlab.selenetv.data.Repositories
```

- [ ] **Step 2: 替换 8 处 ApiService**

| 行（参考） | 现行 | 改为 |
|---|---|---|
| 308 | `ApiService.getPlayRecords()` | `Repositories.userData().getPlayRecords()` |
| 316 | `ApiService.getFavorites()` | `Repositories.userData().getFavorites()` |
| 490 | `ApiService.getPlayRecords()` | `Repositories.userData().getPlayRecords()` |
| 521 | `ApiService.unfavorite(source.source, source.id)` | `Repositories.userData().unfavorite(source.source, source.id)` |
| 532 | `ApiService.addFavorite(source.source, source.id, favData)` | `Repositories.userData().addFavorite(source.source, source.id, favData)` |
| 560 | `ApiService.deletePlayRecord(source.source, source.id)` | `Repositories.userData().deletePlayRecord(source.source, source.id)` |
| 662 | `ApiService.savePlayRecord(migrated)` | `Repositories.userData().savePlayRecord(migrated)` |
| 687 | `ApiService.deletePlayRecord(rec.source, rec.id)` | `Repositories.userData().deletePlayRecord(rec.source, rec.id)` |

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/DetailScreen.kt
git commit -m "refactor(local-mode): DetailScreen 8 处 ApiService → Repositories.userData()"
```

---

### Task 16: 迁移 PlayerScreen + PlayProgressReporter（3 处）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/player/PlayProgressReporter.kt`

- [ ] **Step 1: PlayerScreen**

`PlayerScreen.kt:203`：
```kotlin
org.moontechlab.selenetv.service.ApiService.getPlayRecords()["${src.source}+${src.id}"]
```
改为：
```kotlin
org.moontechlab.selenetv.data.Repositories.userData().getPlayRecords()["${src.source}+${src.id}"]
```

`PlayerScreen.kt:596`：
```kotlin
org.moontechlab.selenetv.service.ApiService.deletePlayRecord(old.source, old.id)
```
改为：
```kotlin
org.moontechlab.selenetv.data.Repositories.userData().deletePlayRecord(old.source, old.id)
```

- [ ] **Step 2: PlayProgressReporter**

`PlayProgressReporter.kt:35` 顶部加 import：
```kotlin
import org.moontechlab.selenetv.data.Repositories
```
然后替换：
```kotlin
ApiService.savePlayRecord(record)
```
为：
```kotlin
Repositories.userData().savePlayRecord(record)
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/PlayerScreen.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/player/PlayProgressReporter.kt
git commit -m "refactor(local-mode): PlayerScreen + PlayProgressReporter ApiService → Repositories.userData()"
```

---

### Task 17: 全量校验：编译 + 所有测试

**Files:** （无修改）

确认前 16 个 task 没漏掉调用点 / 没引入回归。

- [ ] **Step 1: grep 残留**

```bash
grep -rn "ApiService\." app/src/main/java/org/moontechlab/selenetv | grep -v "data/server" | grep -v "ApiException"
```
Expected: 输出**仅**剩 `ApiService.kt` 自身定义；任何 screen / service / Repository 之外的 `ApiService.X(...)` 调用都应已迁移。如还有遗漏，逐个修。

- [ ] **Step 2: 全量编译 + 测试**

```bash
./gradlew compileDebugKotlin testDebugUnitTest
```
Expected: BUILD SUCCESSFUL，所有既有 + 新增单测通过

- [ ] **Step 3: lint**

```bash
./gradlew lintDebug
```
Expected: 没有新增 error

- [ ] **Step 4: Commit**

如有补漏修改：
```bash
git add -A && git commit -m "refactor(local-mode): 补齐遗漏的 ApiService 调用点迁移"
```
否则跳过。

---

### Task 18: `SeleneApplication.onCreate` 后台刷新订阅

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/SeleneApplication.kt`

- [ ] **Step 1: 加 applicationScope 与刷新分支**

修改 `onCreate`（约 27-35 行）：
```kotlin
class SeleneApplication : Application(), ImageLoaderFactory {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppDataService.init(this)
        LocalStore.init(this)
        Log.d("SeleneApplication", "Application initialized")

        startRemoteControlServer()  // 既有，不动

        if (AppDataService.isLocalModeSync()) {
            applicationScope.launch {
                val ok = SubscriptionService.refreshAndPersist()
                if (!ok) MoonToast.show("订阅刷新失败")
            }
        }
    }
```

顶部 import 段加：
```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.moontechlab.selenetv.data.local.LocalStore
import org.moontechlab.selenetv.service.SubscriptionService
import org.moontechlab.selenetv.ui.MoonToast
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/SeleneApplication.kt
git commit -m "feat(local-mode): SeleneApplication 启动后台刷新订阅（不阻塞 onCreate）"
```

---

### Task 19: `AppNavHost` gate 与 `MainActivity` 短路

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/AppNavHost.kt:28`
- Modify: `app/src/main/java/org/moontechlab/selenetv/MainActivity.kt:122`

- [ ] **Step 1: AppNavHost gate**

`AppNavHost.kt:28` 现行：
```kotlin
        if (AppDataService.isLoggedInSync()) HomeRoute else LoginRoute
```
改为：
```kotlin
        if (AppDataService.isLoggedInSync() || AppDataService.isLocalModeSync()) HomeRoute else LoginRoute
```

- [ ] **Step 2: MainActivity 短路**

`MainActivity.kt:122-128` 现行：
```kotlin
    private fun refreshLoginCookieIfNeeded() {
        activityScope.launch {
            if (AppDataService.isLoggedIn() && AppDataService.hasAutoLoginData()) {
                Log.d(TAG, "Auto refreshing login cookie...")
                refreshLoginCookie()
            }
        }
    }
```
改为：
```kotlin
    private fun refreshLoginCookieIfNeeded() {
        if (AppDataService.isLocalModeSync()) return  // 本地模式与服务端账号互斥
        activityScope.launch {
            if (AppDataService.isLoggedIn() && AppDataService.hasAutoLoginData()) {
                Log.d(TAG, "Auto refreshing login cookie...")
                refreshLoginCookie()
            }
        }
    }
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/AppNavHost.kt \
        app/src/main/java/org/moontechlab/selenetv/MainActivity.kt
git commit -m "feat(local-mode): AppNavHost gate + MainActivity refresh cookie 短路"
```

---

### Task 20: 抽出共享弹框到 `ui/SharedDialogs.kt`

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/ui/SharedDialogs.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/tab/SettingsTab.kt`

把 `SettingsTab.kt` 中 `AnimatedSelectDialog`（约 629-709 行）、`ConfirmDialogContent`（约 1506-1577 行）、`DialogButton`（约 945-990 行）三个 composable + 必要颜色常量抽出到共享文件。

- [ ] **Step 1: 创建 SharedDialogs.kt**

```kotlin
package org.moontechlab.selenetv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

// ==================== 颜色常量（设置页 / 登录页共享）====================
internal val DialogTextDefault = Color.White
internal val DialogTextFocused = Color(0xFF0A0A0A)
internal val DialogTextGray = Color(0xFF888888)
internal val DialogItemBgDefault = Color(0x14FFFFFF)
internal val DialogItemBgFocused = Color.White
internal val DialogDestructiveFocused = Color(0xFFE74C3C)

/**
 * 带 spring 进出动画 + 黑色遮罩 + 独立 Dialog 窗口的弹框容器。
 * 独立窗口保证 D-pad 上下键不逃出弹框焦点域。
 */
@Composable
fun AnimatedSelectDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onClosed: () -> Unit = {},
    content: @Composable (onClose: () -> Unit) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var animateIn by remember { mutableStateOf(false) }
    var hasBeenVisible by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            hasBeenVisible = true
            showDialog = true
            delay(16)
            animateIn = true
        } else {
            animateIn = false
            delay(150)
            showDialog = false
            if (hasBeenVisible) {
                hasBeenVisible = false
                onClosed()
            }
        }
    }

    if (showDialog) {
        val showSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 400f)
        val hideSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 500f)

        val dialogScale by animateFloatAsState(
            targetValue = if (animateIn) 1f else 0.85f,
            animationSpec = if (animateIn) showSpring else hideSpring,
            label = "dialog_scale",
        )
        val dialogOpacity by animateFloatAsState(
            targetValue = if (animateIn) 1f else 0f,
            animationSpec = if (animateIn) showSpring else hideSpring,
            label = "dialog_opacity",
        )
        val overlayOpacity by animateFloatAsState(
            targetValue = if (animateIn) 1f else 0f,
            animationSpec = if (animateIn) showSpring else hideSpring,
            label = "overlay_opacity",
        )

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = overlayOpacity }
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.graphicsLayer {
                        scaleX = dialogScale
                        scaleY = dialogScale
                        alpha = dialogOpacity
                    },
                ) {
                    content(onDismiss)
                }
            }
        }
    }
}

/**
 * 弹框按钮：default / primary / destructive 三种态。
 */
@Composable
fun DialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    isDestructive: Boolean = false,
) {
    var isFocused by remember { mutableStateOf(false) }

    val textColor = when {
        isFocused -> if (isDestructive) Color.White else DialogTextFocused
        isDestructive -> Color(0xFFE74C3C)
        isPrimary -> Color(0xFF4CAF50)
        else -> DialogTextDefault
    }

    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                isDestructive -> Color(0x33E74C3C)
                isPrimary -> Color(0x334CAF50)
                else -> DialogItemBgDefault
            },
            focusedContainerColor = if (isDestructive) DialogDestructiveFocused else DialogItemBgFocused,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
            )
        }
    }
}

/**
 * 确认弹框：标题 + 消息 + 取消/确认两按钮。
 * 危险操作默认聚焦取消（focusConfirm=false），消除「确认→取消→确认」的焦点闪现。
 */
@Composable
fun ConfirmDialogContent(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    confirmDestructive: Boolean = false,
    focusConfirm: Boolean = true,
) {
    val confirmButtonFocusRequester = remember { FocusRequester() }
    val cancelButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        (if (focusConfirm) confirmButtonFocusRequester else cancelButtonFocusRequester).requestFocus()
    }

    Column(
        modifier = Modifier
            .width(400.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1A1A))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DialogTextDefault)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message, fontSize = 14.sp, color = DialogTextGray)
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DialogButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f).focusRequester(cancelButtonFocusRequester),
            )
            DialogButton(
                text = confirmText,
                onClick = onConfirm,
                modifier = Modifier.weight(1f).focusRequester(confirmButtonFocusRequester),
                isPrimary = !confirmDestructive,
                isDestructive = confirmDestructive,
            )
        }
    }
}
```

- [ ] **Step 2: 删 SettingsTab.kt 中的三个 composable**

在 `SettingsTab.kt` 中删除：
- `AnimatedSelectDialog` 整段（约 629-709 行）
- `DialogButton` 整段（约 945-990 行）
- `ConfirmDialogContent` 整段（约 1506-1577 行）

顶部加 import：
```kotlin
import org.moontechlab.selenetv.ui.AnimatedSelectDialog
import org.moontechlab.selenetv.ui.ConfirmDialogContent
import org.moontechlab.selenetv.ui.DialogButton
```

`SettingsTab.kt` 中其他保留的 dialog content（`SourceSelectDialogContent`、`IpSelectDialogContent`、`LogoutDialogContent`、`TextInputDialogContent`）继续用 `DialogButton`、`ConfirmDialogContent`、`AnimatedSelectDialog` —— 现在它们来自共享文件。

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/ui/SharedDialogs.kt \
        app/src/main/java/org/moontechlab/selenetv/screen/tab/SettingsTab.kt
git commit -m "refactor: 共享弹框抽到 ui/SharedDialogs.kt（登录页将复用）"
```

---

### Task 21: 登录页本地模式表单

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/LoginScreen.kt`

按 spec §4 改造。涉及修改面较大，分步：

- [ ] **Step 1: 加状态字段**

在 `LoginScreenContent` 函数开头（约 121 行下方）加：
```kotlin
    // ============ 本地模式状态 ============
    var isLocalMode by remember { mutableStateOf(false) }
    var subscriptionUrl by remember { mutableStateOf("") }

    var pendingParsed by remember { mutableStateOf<SubscriptionService.ParsedSubscription?>(null) }
    var pendingUrl by remember { mutableStateOf("") }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val subscriptionUrlFocusRequester = remember { FocusRequester() }
    val localLoginButtonFocusRequester = remember { FocusRequester() }
```

顶部 import 段加：
```kotlin
import org.moontechlab.selenetv.data.local.LocalStore
import org.moontechlab.selenetv.service.SubscriptionService
import org.moontechlab.selenetv.ui.AnimatedSelectDialog
import org.moontechlab.selenetv.ui.ConfirmDialogContent
```

- [ ] **Step 2: 暗门激活回调改为 toggle**

找到 `LoginScreenContent` 中调用 `LoginQrCodeSection` 的位置（约 264-274 行），把 `onSecretActivate` 改为：
```kotlin
            onSecretActivate = {
                isLocalMode = !isLocalMode
                org.moontechlab.selenetv.ui.MoonToast.show(
                    if (isLocalMode) "已进入本地模式" else "已切回账号登录"
                )
            }
```

- [ ] **Step 3: 切换时重新聚焦 + 回填**

在 `LoginScreenContent` 中找到现有的初始聚焦 `LaunchedEffect`（约 149 行），改为：
```kotlin
    LaunchedEffect(isLocalMode) {
        delay(100)
        if (isLocalMode) {
            subscriptionUrl = withContext(Dispatchers.IO) { LocalStore.getSubscriptionUrl() }.orEmpty()
            subscriptionUrlFocusRequester.requestFocus()
        } else {
            serverUrlFocusRequester.requestFocus()
        }
    }
```

- [ ] **Step 4: handleLocalModeLogin + commitLocalMode**

在 `handleLogin()` 之后（约 256 行后）加：
```kotlin
    fun commitLocalMode(parsed: SubscriptionService.ParsedSubscription, url: String, clearAll: Boolean) {
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

    fun handleLocalModeLogin() {
        val url = subscriptionUrl.trim()
        if (url.isEmpty()) { showToast("请输入订阅链接"); return }

        isLoading = true
        coroutineScope.launch {
            val parsed = try {
                withContext(Dispatchers.IO) { SubscriptionService.fetchAndParse(url) }
            } catch (e: SubscriptionService.SubscriptionException) {
                isLoading = false
                showToast(e.message ?: "订阅校验失败")
                return@launch
            } catch (e: Exception) {
                isLoading = false
                showToast("网络异常: ${e.message ?: "未知错误"}")
                return@launch
            }
            isLoading = false

            val existing = withContext(Dispatchers.IO) { LocalStore.getSubscriptionUrl() }
            if (!existing.isNullOrBlank() && existing != url) {
                pendingParsed = parsed
                pendingUrl = url
                showClearConfirmDialog = true
            } else {
                commitLocalMode(parsed, url, clearAll = false)
            }
        }
    }
```

- [ ] **Step 5: 右侧表单分支渲染**

现有右侧 `Column`（约 277-343 行）整段替换为按 `isLocalMode` 二选一：

```kotlin
        // 右侧表单区域
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            if (isLocalMode) {
                Text(text = "本地模式", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextDefault)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "无需服务端账号，输入订阅链接即可",
                    fontSize = 13.sp,
                    color = TextGray,
                )
                Spacer(modifier = Modifier.height(24.dp))

                LoginTextField(
                    label = "订阅链接",
                    value = subscriptionUrl,
                    onValueChange = { subscriptionUrl = it },
                    placeholder = "例如: https://example.com/sub/xxx",
                    focusRequester = subscriptionUrlFocusRequester,
                    downFocusRequester = localLoginButtonFocusRequester,
                )

                Spacer(modifier = Modifier.height(24.dp))

                LoginButton(
                    text = if (isLoading) "校验中..." else "进入本地模式",
                    enabled = !isLoading,
                    onClick = { handleLocalModeLogin() },
                    modifier = Modifier.focusRequester(localLoginButtonFocusRequester),
                    upFocusRequester = subscriptionUrlFocusRequester,
                    leftFocusRequester = brandDotFocusRequester,
                )
            } else {
                // 既有账号表单原样保留
                Text(text = "账号登录", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextDefault)
                Spacer(modifier = Modifier.height(24.dp))
                LoginTextField(
                    label = "服务器地址",
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    placeholder = "例如: 192.168.1.100:3000",
                    focusRequester = serverUrlFocusRequester,
                    downFocusRequester = usernameFocusRequester,
                )
                Spacer(modifier = Modifier.height(16.dp))
                LoginTextField(
                    label = "用户名",
                    value = username,
                    onValueChange = { username = it },
                    placeholder = "请输入用户名",
                    focusRequester = usernameFocusRequester,
                    upFocusRequester = serverUrlFocusRequester,
                    downFocusRequester = passwordFocusRequester,
                )
                Spacer(modifier = Modifier.height(16.dp))
                LoginTextField(
                    label = "密码",
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "请输入密码",
                    isPassword = true,
                    focusRequester = passwordFocusRequester,
                    upFocusRequester = usernameFocusRequester,
                    downFocusRequester = loginButtonFocusRequester,
                )
                Spacer(modifier = Modifier.height(24.dp))
                LoginButton(
                    text = if (isLoading) "登录中..." else "登录",
                    enabled = !isLoading,
                    onClick = { handleLogin() },
                    modifier = Modifier.focusRequester(loginButtonFocusRequester),
                    upFocusRequester = passwordFocusRequester,
                    leftFocusRequester = brandDotFocusRequester,
                )
            }
        }
```

- [ ] **Step 6: 二次确认弹框**

`LoginScreenContent` 最后（return 之前），加：
```kotlin
    AnimatedSelectDialog(
        visible = showClearConfirmDialog,
        onDismiss = { showClearConfirmDialog = false },
    ) { onClose ->
        ConfirmDialogContent(
            title = "已存在不同的订阅",
            message = "检测到已有本地模式内容且订阅链接不一致，是否清空全部本地模式存储？",
            confirmText = "清空并保存",
            confirmDestructive = true,
            focusConfirm = false,
            onConfirm = {
                val p = pendingParsed; val u = pendingUrl
                onClose()
                if (p != null) commitLocalMode(p, u, clearAll = true)
            },
            onDismiss = {
                val p = pendingParsed; val u = pendingUrl
                onClose()
                if (p != null) commitLocalMode(p, u, clearAll = false)
            },
        )
    }
```

- [ ] **Step 7: BackHandler 链调整**

`LoginScreen` 函数中的 BackHandler（约 91-97 行）改为：
```kotlin
    BackHandler {
        if (false) {  // placeholder：isLocalMode 在 Content 内部维护，外层 BackHandler 不直接感知
        } else if (isGate) {
            (context as? android.app.Activity)?.finish()
        } else {
            navController.popBackStack()
        }
    }
```
但更干净的做法是把 BackHandler 下沉到 `LoginScreenContent`，能读到 `isLocalMode`。修改如下：

把 `LoginScreen` 中的 BackHandler 删除，把它移到 `LoginScreenContent` 内（在 import 区下方、状态字段之后）：
```kotlin
    val activity = LocalContext.current as? android.app.Activity
    BackHandler {
        when {
            isLocalMode -> { isLocalMode = false }
            isGate -> activity?.finish()
            else -> onDismiss()
        }
    }
```
然后 `LoginScreenContent` 签名加 `isGate: Boolean` 参数；`LoginScreen` 调用 `LoginScreenContent(isGate = isGate, onDismiss = { leave() })`。

- [ ] **Step 8: 编译 + 单测验证**

```bash
./gradlew compileDebugKotlin testDebugUnitTest
```
Expected: BUILD SUCCESSFUL，所有单测仍通过

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/LoginScreen.kt
git commit -m "feat(local-mode): LoginScreen 本地模式表单 + 二次确认弹框"
```

---

### Task 22: 设置页本地模式分区 + 隐藏「搜索」分区

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/tab/SettingsTab.kt`

按 spec §5 改造。

- [ ] **Step 1: 加状态字段**

`SettingsTab` 函数（约 99-105 行下方）加：
```kotlin
    var isLocalMode by remember { mutableStateOf(AppDataService.isLocalModeSync()) }
    var subscriptionUrl by remember { mutableStateOf("") }

    // 本地模式分区的弹框状态
    var showSubscriptionUrlDialog by remember { mutableStateOf(false) }
    var showExitLocalModeDialog by remember { mutableStateOf(false) }
    var showExitLocalModeConfirmDialog by remember { mutableStateOf(false) }
    var exitLocalModeChainToConfirm by remember { mutableStateOf(false) }
    var exitLocalModeConfirmed by remember { mutableStateOf(false) }

    // 本地模式分区的 FocusRequester
    val subscriptionUrlFocusRequester = remember { FocusRequester() }
    val refreshSubscriptionFocusRequester = remember { FocusRequester() }
    val exitLocalModeFocusRequester = remember { FocusRequester() }
```

顶部 import 段加：
```kotlin
import org.moontechlab.selenetv.data.local.LocalStore
import org.moontechlab.selenetv.service.SubscriptionService
```

- [ ] **Step 2: 在 LaunchedEffect 加载**

修改 `LaunchedEffect(Unit)`（约 177-196 行），加：
```kotlin
        isLocalMode = AppDataService.isLocalMode()
        if (isLocalMode) {
            subscriptionUrl = LocalStore.getSubscriptionUrl().orEmpty()
        }
```

- [ ] **Step 3: 右栏「账户」分区分支**

找到现有「账户」分区代码（约 240-262 行 `SectionLabel("账户")` 至下一个 `Spacer(18.dp)` 之前），整段替换为：
```kotlin
                // —— 账户 / 本地模式 ——
                if (isLocalMode) {
                    SectionLabel("本地模式")
                    SettingRow(
                        label = "订阅链接",
                        value = subscriptionUrl.ifEmpty { "未设置" },
                        onClick = { showSubscriptionUrlDialog = true },
                        modifier = Modifier
                            .focusRequester(subscriptionUrlFocusRequester)
                            .focusRequester(focusRequester),
                    )
                    SettingRow(
                        label = "立即刷新订阅",
                        onClick = {
                            coroutineScope.launch {
                                val ok = withContext(Dispatchers.IO) { SubscriptionService.refreshAndPersist() }
                                org.moontechlab.selenetv.ui.MoonToast.show(
                                    if (ok) "订阅已刷新" else "订阅刷新失败"
                                )
                            }
                        },
                        modifier = Modifier.focusRequester(refreshSubscriptionFocusRequester),
                    )
                    SettingRow(
                        label = "退出本地模式",
                        accent = RowAccent.Red,
                        showDivider = false,
                        onClick = { showExitLocalModeDialog = true },
                        modifier = Modifier.focusRequester(exitLocalModeFocusRequester),
                    )
                } else if (hasLogin) {
                    SectionLabel("账户")
                    SettingRow(
                        label = "登录实例",
                        value = serverUrl.ifEmpty { "未设置" },
                        showDivider = false,
                        onClick = { showLogoutDialog = true },
                        modifier = Modifier
                            .focusRequester(loginInstanceFocusRequester)
                            .focusRequester(focusRequester),
                    )
                } else {
                    SectionLabel("账户")
                    SettingRow(
                        label = "登录",
                        showDivider = false,
                        onClick = { onShowLoginScreen() },
                        modifier = Modifier.focusRequester(focusRequester),
                    )
                }
```

- [ ] **Step 4: 隐藏「搜索」分区**

找到 `SectionLabel("搜索")` 之前的 `Spacer(18.dp)` 起、到下一个 `Spacer(18.dp)` 止的整段（约 308-318 行），包在 `if (!isLocalMode) { ... }` 内：
```kotlin
                if (!isLocalMode) {
                    Spacer(modifier = Modifier.height(18.dp))
                    SectionLabel("搜索")
                    SettingRow(
                        label = "搜索发起方",
                        value = searchOrigin.ifEmpty { AppDataService.SearchOrigin.TV_DIRECT.displayName },
                        showDivider = false,
                        onClick = { showSearchOriginDialog = true },
                        modifier = Modifier.focusRequester(searchOriginFocusRequester),
                    )
                }
```

- [ ] **Step 5: 订阅 URL 弹框**

在文件末尾（其他弹框之后）加：
```kotlin
    AnimatedSelectDialog(
        visible = showSubscriptionUrlDialog,
        onDismiss = { showSubscriptionUrlDialog = false },
        onClosed = { subscriptionUrlFocusRequester.requestFocus() },
    ) { onClose ->
        TextInputDialogContent(
            title = "订阅链接",
            placeholder = "请输入订阅链接",
            initialValue = subscriptionUrl,
            onConfirm = { newUrl ->
                onClose()
                val trimmed = newUrl.trim()
                if (trimmed.isEmpty()) {
                    org.moontechlab.selenetv.ui.MoonToast.show("请输入订阅链接")
                    return@TextInputDialogContent
                }
                coroutineScope.launch {
                    val parsed = try {
                        withContext(Dispatchers.IO) { SubscriptionService.fetchAndParse(trimmed) }
                    } catch (e: SubscriptionService.SubscriptionException) {
                        org.moontechlab.selenetv.ui.MoonToast.show(e.message ?: "订阅校验失败")
                        return@launch
                    } catch (e: Exception) {
                        org.moontechlab.selenetv.ui.MoonToast.show("网络异常: ${e.message}")
                        return@launch
                    }
                    val existing = withContext(Dispatchers.IO) { LocalStore.getSubscriptionUrl() }
                    val shouldClearAll = !existing.isNullOrBlank() && existing != trimmed
                    withContext(Dispatchers.IO) {
                        if (shouldClearAll) LocalStore.clearAll()
                        LocalStore.saveSubscriptionUrl(trimmed)
                        LocalStore.saveSearchSources(parsed.searchResources)
                        LocalStore.saveLiveSources(parsed.liveSources)
                    }
                    subscriptionUrl = trimmed
                    org.moontechlab.selenetv.ui.MoonToast.show(
                        if (shouldClearAll) "已切换订阅并清空旧数据" else "订阅已更新"
                    )
                }
            },
            onDismiss = onClose,
        )
    }
```

- [ ] **Step 6: 退出本地模式两步弹框**

在文件末尾继续加：
```kotlin
    // A: 单独的「退出本地模式」按钮弹框
    AnimatedSelectDialog(
        visible = showExitLocalModeDialog,
        onDismiss = { showExitLocalModeDialog = false },
        onClosed = {
            if (exitLocalModeChainToConfirm) {
                exitLocalModeChainToConfirm = false
                showExitLocalModeConfirmDialog = true
            } else {
                try { exitLocalModeFocusRequester.requestFocus() } catch (_: Exception) {}
            }
        },
    ) { onClose ->
        LogoutDialogContent(
            serverUrl = subscriptionUrl,
            onConfirm = {
                exitLocalModeChainToConfirm = true
                onClose()
            },
        )
    }

    // B: 二次确认
    AnimatedSelectDialog(
        visible = showExitLocalModeConfirmDialog,
        onDismiss = { showExitLocalModeConfirmDialog = false },
        onClosed = {
            if (exitLocalModeConfirmed) {
                exitLocalModeConfirmed = false
            } else {
                try { exitLocalModeFocusRequester.requestFocus() } catch (_: Exception) {}
            }
        },
    ) { onClose ->
        ConfirmDialogContent(
            title = "退出本地模式",
            message = "退出后将清空本地订阅、收藏与播放记录。确定继续？",
            confirmText = "确认",
            confirmDestructive = true,
            focusConfirm = false,
            onConfirm = {
                exitLocalModeConfirmed = true
                isLocalMode = false
                subscriptionUrl = ""
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        LocalStore.clearAll()
                        AppDataService.saveIsLocalMode(false)
                    }
                }
                onShowLoginScreen()
                onClose()
            },
            onDismiss = onClose,
        )
    }
```

- [ ] **Step 7: 编译 + 单测验证**

```bash
./gradlew compileDebugKotlin testDebugUnitTest
```
Expected: BUILD SUCCESSFUL，所有单测仍通过

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/tab/SettingsTab.kt
git commit -m "feat(local-mode): SettingsTab 本地模式分区 + 隐藏搜索发起方"
```

---

### Task 23: 全量校验 + 真机/AVD 安装

**Files:** （无修改）

- [ ] **Step 1: clean build**

```bash
./gradlew clean assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: lint**

```bash
./gradlew lintDebug
```
Expected: 无新增 error

- [ ] **Step 3: 全量单测**

```bash
./gradlew testDebugUnitTest
```
Expected: 全部通过

- [ ] **Step 4: 安装到 Selene_TV AVD**

参考 memory `tv-emulator-e2e`：
```bash
# 假设 AVD 名为 Selene_TV
~/Library/Android/sdk/emulator/emulator -avd Selene_TV -no-snapshot-load &
~/Library/Android/sdk/platform-tools/adb wait-for-device
./gradlew installDebug
~/Library/Android/sdk/platform-tools/adb shell am start -c android.intent.category.LEANBACK_LAUNCHER \
    -n org.moontechlab.selenetv/.MainActivity
```
Expected: 应用启动至首页（已有服务端登录态时）

- [ ] **Step 5: 不 commit**（仅验证）

---

### Task 24: 手动 E2E 走查清单

**Files:** （无修改，纯人工验证）

参考 spec §6 测试清单，在 AVD 上逐项走查：

- [ ] **Step 1: 服务端模式回归不破**

冷启动 → 已有服务端账号登录态时直接进首页 → 搜索 / 直播 / 详情 / 收藏 / 播放进度，行为与改造前一致。

- [ ] **Step 2: 进入本地模式流程**

退出登录到 LoginScreen 账号表单 → D-pad 焦点到品牌点 → `adb shell input keyevent --longpress 23` → toast「已进入本地模式」+ 表单切换。

- [ ] **Step 3: 订阅校验失败**

输入无效 URL → toast 显示对应错误：`获取订阅内容失败` / `订阅内容格式无效` / `订阅内容无可用源` / `网络异常`。

- [ ] **Step 4: 订阅校验成功 → 进首页**

通过 Web 遥控器（设置页二维码 / 登录页二维码）输入测试订阅 URL → 「进入本地模式」 → 进首页。

- [ ] **Step 5: 搜索 Tab 来自订阅**

搜索关键词 → 结果来自订阅 api_site；设置 → 搜索分区已隐藏。

- [ ] **Step 6: 直播 Tab 来自订阅**

直播 Tab 频道列表来自订阅 lives。

- [ ] **Step 7: 用户数据本地持久化**

详情页 → 收藏 → 播放保存进度 → `adb shell am force-stop org.moontechlab.selenetv` → 冷重启 → HomeTab 「我的收藏」「继续观看」保留。

- [ ] **Step 8: 启动后台刷新**

设置 → 「立即刷新订阅」成功 toast。停 emulator 网络 → 重启 app → toast「订阅刷新失败」，本地原有数据仍在、可正常浏览。

- [ ] **Step 9: 订阅 URL 切换冲突弹框**

设置 → 「订阅链接」改为不同 URL → 触发清除提示 toast「已切换订阅并清空旧数据」 → 「我的收藏」「继续观看」清空。

- [ ] **Step 10: 退出本地模式**

设置 → 「退出本地模式」两步弹框 → 确认 → 回 LoginScreen 账号表单 → 再冷启动确认 `isLocalMode=false`、`LocalStore` 已清。

- [ ] **Step 11: 修复发现的问题**

逐项修复并 commit 每个修复，commit 信息：`fix(local-mode): <具体问题>`。

---

## Self-Review

**Spec coverage:**
- §1 启动与模式判定 → Task 1, 18, 19
- §2 仓库层 → Task 3, 4, 6, 7, 8, 9, 12-16
- §3 订阅服务 → Task 2, 10, 11
- §4 登录页改造 → Task 20, 21
- §5 设置页改造 → Task 20, 22
- §6 错误处理 + 边界 + 测试 → Task 17, 23, 24
- Spec §「实施顺序」9 步 → 全覆盖

**Placeholder scan:** 已剔除所有「TBD」「TODO」「按需」字样；每个 Step 都有具体代码或具体命令。

**Type / 命名一致性:**
- `Repositories.userData()` / `Repositories.sources()` 全文一致
- `AppDataService.isLocalModeSync()` / `saveIsLocalMode()` / `isLocalMode()` 一致
- `SubscriptionService.fetchAndParse` / `refreshAndPersist` / `parseSubscriptionJson` / `ParsedSubscription` / `SubscriptionException` 一致
- `LocalStore.getSubscriptionUrl/saveSubscriptionUrl/getSearchSources/saveSearchSources/getLiveSources/saveLiveSources/getFavorites/saveFavorites/getPlayRecords/savePlayRecords/getSearchHistory/saveSearchHistory/clearAll` 一致
- `LocalUserDataRepository.upsertSearchHistory/upsertFavorite/upsertPlayRecord/favoriteFromMap` 在测试与实现签名一致

**Scope:** 单一 feature，单一 spec，单一 plan。可作为一个完整 PR。
