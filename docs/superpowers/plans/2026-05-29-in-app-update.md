# 启动时应用内更新检查 + 下载安装 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** App 启动时检查 GitHub `MoonTechLab/Selene-TV` 是否有新版本，弹窗提示后在应用内下载匹配当前 CPU 架构的 APK 并拉起系统安装器；设置页提供手动检查入口。

**Architecture:** 纯逻辑（版本比较、ABI 选包、判定链）抽到无 Android 依赖的 `UpdateLogic` 便于单元测试；网络/IO 在 `UpdateService`（阻塞、`Dispatchers.IO`、抛 `ApiException`）；安装走 `InstallHelper`（FileProvider + 系统安装器 Intent）；UI 复用既有 `AnimatedSelectDialog` / `DialogButton`；启动检查挂在全局根 `AppNavHost`，与 `MoonToastHost` 同级渲染在所有页面之上。

**Tech Stack:** Kotlin、OkHttp（复用 `MediaHttp.httpClient`，trust-all）、kotlinx.serialization、Jetpack Compose for TV、AndroidX `FileProvider`、JUnit4。

---

## 设计参考（已核实的事实）

- Release API：`GET https://api.github.com/repos/MoonTechLab/Selene-TV/releases/latest`，字段 `tag_name`(如 `v1.2.2`)、`name`、`body`、`assets[].{name, browser_download_url, size, content_type}`。
- 资产命名：`SeleneTV-v1.2.2-arm64-v8a.apk`、`SeleneTV-v1.2.2-armeabi-v7a.apk`。
- 本地：`versionName="1.2.2"`、ABI splits 仅 `arm64-v8a` + `armeabi-v7a`。
- 既有可复用件：`ApiException(message, statusCode=-1)` (`service/ApiService.kt`)、`MediaHttp.httpClient` (`service/MediaHttp.kt`)、`AppDataService` 同步 prefs（`getXxxSync`/`prefs.edit().putXxx().apply()`）、`AnimatedSelectDialog` + `DialogButton` (`ui/SharedDialogs.kt`)、`MoonToast.show(msg)` (`ui/MoonToast.kt`)、`SettingRow(label, value?, accent, onClick, ...)` (`screen/tab/SettingsTab.kt`)、全局根 `AppNavHost`（`screen/AppNavHost.kt`，内含 `MoonToastHost`）。

## 文件结构

- Create `app/src/main/java/org/moontechlab/selenetv/model/UpdateModels.kt` — JSON DTO + `UpdateInfo` 领域模型
- Create `app/src/main/java/org/moontechlab/selenetv/service/UpdateLogic.kt` — 纯逻辑（版本比较 / ABI 选包 / 判定链）
- Create `app/src/main/java/org/moontechlab/selenetv/service/UpdateService.kt` — 网络拉取 + 下载
- Create `app/src/main/java/org/moontechlab/selenetv/util/InstallHelper.kt` — FileProvider 安装
- Create `app/src/main/java/org/moontechlab/selenetv/screen/UpdateDialog.kt` — 弹窗 UI + `UpdateGate`
- Create `app/src/main/res/xml/file_paths.xml` — FileProvider 路径
- Create `app/src/test/java/org/moontechlab/selenetv/UpdateLogicTest.kt` — 纯逻辑单测
- Modify `app/src/main/java/org/moontechlab/selenetv/service/AppDataService.kt` — 新增 2 个 pref
- Modify `app/src/main/AndroidManifest.xml` — 权限 + FileProvider
- Modify `app/src/main/java/org/moontechlab/selenetv/screen/AppNavHost.kt` — 挂载 `UpdateGate`
- Modify `app/src/main/java/org/moontechlab/selenetv/screen/tab/SettingsTab.kt` — 手动「检查更新」行

---

## Task 1: 数据模型 + JSON 解析

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/model/UpdateModels.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/UpdateLogicTest.kt`

- [ ] **Step 1: Write the failing test**

创建 `app/src/test/java/org/moontechlab/selenetv/UpdateLogicTest.kt`:

```kotlin
package org.moontechlab.selenetv

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.moontechlab.selenetv.model.GithubRelease

class UpdateLogicTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val sampleJson = """
        {
          "tag_name": "v1.2.2",
          "name": "v1.2.2",
          "body": "更新日志内容",
          "published_at": "2026-05-28T17:36:10Z",
          "assets": [
            {"name":"SeleneTV-v1.2.2-arm64-v8a.apk","browser_download_url":"https://example.com/a.apk","size":30870406,"content_type":"application/vnd.android.package-archive"},
            {"name":"SeleneTV-v1.2.2-armeabi-v7a.apk","browser_download_url":"https://example.com/b.apk","size":28551167,"content_type":"application/vnd.android.package-archive"}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesReleaseJson() {
        val release = json.decodeFromString<GithubRelease>(sampleJson)
        assertEquals("v1.2.2", release.tagName)
        assertEquals("更新日志内容", release.body)
        assertEquals(2, release.assets.size)
        assertEquals("SeleneTV-v1.2.2-arm64-v8a.apk", release.assets[0].name)
        assertEquals("https://example.com/a.apk", release.assets[0].browserDownloadUrl)
        assertEquals(30870406L, release.assets[0].size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.UpdateLogicTest.parsesReleaseJson"`
Expected: 编译失败，`GithubRelease` 未定义。

- [ ] **Step 3: Create the models**

创建 `app/src/main/java/org/moontechlab/selenetv/model/UpdateModels.kt`:

```kotlin
package org.moontechlab.selenetv.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GitHub Release API 资产。 */
@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
    @SerialName("content_type") val contentType: String = "",
)

/** GitHub Release API 顶层对象（/releases/latest）。 */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String = "",
    val body: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

/** 判定后得出的领域更新信息：已确认有更新且选好了匹配当前架构的 APK。 */
data class UpdateInfo(
    val version: String,      // 去掉前导 v，如 "1.2.3"
    val title: String,        // release 标题，空则回退到 tag
    val changelog: String,    // release body
    val apkUrl: String,
    val apkSize: Long,
    val abi: String,          // 命中的设备 ABI，如 "arm64-v8a"
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.UpdateLogicTest.parsesReleaseJson"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/model/UpdateModels.kt app/src/test/java/org/moontechlab/selenetv/UpdateLogicTest.kt
git commit -m "feat(update): add release DTO + UpdateInfo models with JSON parse test"
```

---

## Task 2: 版本比较（UpdateLogic.compareVersions）

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/service/UpdateLogic.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/UpdateLogicTest.kt`

- [ ] **Step 1: Write the failing tests**

在 `UpdateLogicTest.kt` 顶部增加 import：

```kotlin
import org.junit.Assert.assertTrue
import org.moontechlab.selenetv.service.UpdateLogic
```

在类内追加：

```kotlin
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(UpdateLogic.compareVersions("1.2.10", "1.2.2") > 0)   // 数值比较，非字典序
        assertTrue(UpdateLogic.compareVersions("v1.3.0", "1.2.9") > 0)   // 去前导 v
        assertTrue(UpdateLogic.compareVersions("1.2", "1.2.0") == 0)     // 缺位补 0
        assertTrue(UpdateLogic.compareVersions("1.2.2", "1.2.3") < 0)
        assertEquals(0, UpdateLogic.compareVersions("bad", "1.2.2"))     // 非法→0（按无更新处理）
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.UpdateLogicTest.comparesVersionsNumerically"`
Expected: 编译失败，`UpdateLogic` 未定义。

- [ ] **Step 3: Create UpdateLogic with version compare**

创建 `app/src/main/java/org/moontechlab/selenetv/service/UpdateLogic.kt`:

```kotlin
package org.moontechlab.selenetv.service

import org.moontechlab.selenetv.model.GithubAsset
import org.moontechlab.selenetv.model.GithubRelease
import org.moontechlab.selenetv.model.UpdateInfo

/**
 * 更新相关的纯逻辑：无 Android 依赖，便于单元测试。
 * 负责版本比较、按设备 ABI 选包、以及是否弹窗的判定链。
 */
object UpdateLogic {

    /** 解析版本号为数值段；去掉前导 v/V；任一段非纯数字返回 null。 */
    fun parseVersion(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(".")
        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** 数值比较版本；a>b 返回正，a<b 返回负，相等或任一非法返回 0。 */
    fun compareVersions(a: String, b: String): Int {
        val pa = parseVersion(a) ?: return 0
        val pb = parseVersion(b) ?: return 0
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.UpdateLogicTest.comparesVersionsNumerically"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/UpdateLogic.kt app/src/test/java/org/moontechlab/selenetv/UpdateLogicTest.kt
git commit -m "feat(update): numeric version comparison"
```

---

## Task 3: ABI 选包（UpdateLogic.selectAsset）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/UpdateLogic.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/UpdateLogicTest.kt`

- [ ] **Step 1: Write the failing tests**

在 `UpdateLogicTest.kt` 增加 import：

```kotlin
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.moontechlab.selenetv.model.GithubAsset
```

追加测试：

```kotlin
    private val assets = listOf(
        GithubAsset("SeleneTV-v1.2.2-arm64-v8a.apk", "https://example.com/a.apk", 1, ""),
        GithubAsset("SeleneTV-v1.2.2-armeabi-v7a.apk", "https://example.com/b.apk", 2, ""),
    )

    @Test
    fun selectsAssetByDeviceAbiPriority() {
        // 64 位设备：列表里 arm64 在前 → 选 arm64 包
        val r64 = UpdateLogic.selectAsset(listOf("arm64-v8a", "armeabi-v7a"), assets)
        assertEquals("arm64-v8a", r64?.first)
        assertEquals("https://example.com/a.apk", r64?.second?.browserDownloadUrl)

        // 32 位设备：无 arm64 → 选 v7a 包
        val r32 = UpdateLogic.selectAsset(listOf("armeabi-v7a", "armeabi"), assets)
        assertEquals("armeabi-v7a", r32?.first)

        // 无任何匹配（如 x86 模拟器）→ null
        assertNull(UpdateLogic.selectAsset(listOf("x86_64", "x86"), assets))

        // 资产为空（CI 构建中）→ null
        assertNull(UpdateLogic.selectAsset(listOf("arm64-v8a"), emptyList()))
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.UpdateLogicTest.selectsAssetByDeviceAbiPriority"`
Expected: 编译失败，`selectAsset` 未定义。

- [ ] **Step 3: Add selectAsset to UpdateLogic**

在 `UpdateLogic` 内（`compareVersions` 之后）追加：

```kotlin
    /**
     * 按设备 ABI 优先级（Build.SUPPORTED_ABIS 顺序）选匹配的 APK 资产。
     * 规则：资产文件名以 .apk 结尾且包含该 ABI 串即命中；按设备优先级取第一个命中。
     * 无任何匹配（资产缺失/CI 构建中/罕见架构）返回 null。
     */
    fun selectAsset(supportedAbis: List<String>, assets: List<GithubAsset>): Pair<String, GithubAsset>? {
        for (abi in supportedAbis) {
            val match = assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true) && it.name.contains(abi, ignoreCase = true)
            }
            if (match != null) return abi to match
        }
        return null
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.UpdateLogicTest.selectsAssetByDeviceAbiPriority"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/UpdateLogic.kt app/src/test/java/org/moontechlab/selenetv/UpdateLogicTest.kt
git commit -m "feat(update): select APK asset by device ABI priority"
```

---

## Task 4: 组合判定 buildUpdateInfo（含 CI 构建中保护）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/UpdateLogic.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/UpdateLogicTest.kt`

- [ ] **Step 1: Write the failing tests**

追加测试（复用 Task 3 的 `assets`）：

```kotlin
    private fun release(tag: String, assets: List<GithubAsset>) =
        GithubRelease(tagName = tag, name = tag, body = "log", assets = assets)

    @Test
    fun buildsUpdateInfoOnlyWhenNewerAndAssetPresent() {
        val abis = listOf("arm64-v8a", "armeabi-v7a")

        // 远端更高 + 有匹配资产 → 有更新
        val info = UpdateLogic.buildUpdateInfo(release("v1.3.0", assets), "1.2.2", abis)
        assertNotNull(info)
        assertEquals("1.3.0", info?.version)
        assertEquals("arm64-v8a", info?.abi)
        assertEquals("https://example.com/a.apk", info?.apkUrl)

        // 远端不高于本地 → null
        assertNull(UpdateLogic.buildUpdateInfo(release("v1.2.2", assets), "1.2.2", abis))

        // 远端更高但无资产（CI 构建中）→ null（等下次启动）
        assertNull(UpdateLogic.buildUpdateInfo(release("v1.3.0", emptyList()), "1.2.2", abis))
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.UpdateLogicTest.buildsUpdateInfoOnlyWhenNewerAndAssetPresent"`
Expected: 编译失败，`buildUpdateInfo` 未定义。

- [ ] **Step 3: Add buildUpdateInfo to UpdateLogic**

在 `UpdateLogic` 内追加：

```kotlin
    /**
     * 组合判定：远端版本须严格高于本地，且能为当前设备选到 APK，才返回 UpdateInfo；
     * 否则（不高于本地 / CI 构建中无资产 / 无匹配架构）返回 null。
     */
    fun buildUpdateInfo(
        release: GithubRelease,
        currentVersion: String,
        supportedAbis: List<String>,
    ): UpdateInfo? {
        if (compareVersions(release.tagName, currentVersion) <= 0) return null
        val (abi, asset) = selectAsset(supportedAbis, release.assets) ?: return null
        val cleanVersion = release.tagName.trim().removePrefix("v").removePrefix("V")
        return UpdateInfo(
            version = cleanVersion,
            title = release.name.ifBlank { release.tagName },
            changelog = release.body,
            apkUrl = asset.browserDownloadUrl,
            apkSize = asset.size,
            abi = abi,
        )
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.UpdateLogicTest.buildsUpdateInfoOnlyWhenNewerAndAssetPresent"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/UpdateLogic.kt app/src/test/java/org/moontechlab/selenetv/UpdateLogicTest.kt
git commit -m "feat(update): buildUpdateInfo with CI-building guard"
```

---

## Task 5: 弹窗判定链 shouldPrompt（skip + 冷却 + 手动绕过）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/UpdateLogic.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/UpdateLogicTest.kt`

- [ ] **Step 1: Write the failing tests**

追加测试：

```kotlin
    private val sampleInfo = UpdateInfo(
        version = "1.3.0", title = "v1.3.0", changelog = "log",
        apkUrl = "https://example.com/a.apk", apkSize = 1, abi = "arm64-v8a",
    )

    @Test
    fun shouldPromptHonorsSkipAndCooldownExceptManual() {
        val now = 1_000_000L
        val sixHours = 6 * 60 * 60 * 1000L

        // 正常：未跳过、无冷却 → 提示
        assertTrue(UpdateLogic.shouldPrompt(sampleInfo, null, 0L, now, manual = false))

        // 跳过了该版本 → 不提示
        assertTrue(!UpdateLogic.shouldPrompt(sampleInfo, "1.3.0", 0L, now, manual = false))

        // 冷却期内 → 不提示
        assertTrue(!UpdateLogic.shouldPrompt(sampleInfo, null, now + sixHours, now, manual = false))

        // 手动检查：跳过 + 冷却都绕过 → 仍提示
        assertTrue(UpdateLogic.shouldPrompt(sampleInfo, "1.3.0", now + sixHours, now, manual = true))
    }

    @Test
    fun cooldownConstantIsSixHours() {
        assertEquals(6 * 60 * 60 * 1000L, UpdateLogic.COOLDOWN_MILLIS)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.UpdateLogicTest.shouldPromptHonorsSkipAndCooldownExceptManual"`
Expected: 编译失败，`shouldPrompt` / `COOLDOWN_MILLIS` 未定义。

- [ ] **Step 3: Add shouldPrompt + cooldown constant**

在 `UpdateLogic` 内追加：

```kotlin
    /** 「稍后」冷却时长：6 小时。 */
    const val COOLDOWN_MILLIS = 6L * 60 * 60 * 1000

    /**
     * 是否应弹窗。手动检查（manual=true）绕过 skip 与冷却。
     * 自动检查：跳过了该版本、或仍在冷却期内 → 不弹。
     */
    fun shouldPrompt(
        info: UpdateInfo,
        skippedVersion: String?,
        postponedUntil: Long,
        now: Long,
        manual: Boolean,
    ): Boolean {
        if (manual) return true
        if (info.version == skippedVersion) return false
        if (now < postponedUntil) return false
        return true
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.UpdateLogicTest"`
Expected: 全部 PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/UpdateLogic.kt app/src/test/java/org/moontechlab/selenetv/UpdateLogicTest.kt
git commit -m "feat(update): shouldPrompt judgment chain with 6h cooldown"
```

---

## Task 6: AppDataService 新增更新偏好

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/AppDataService.kt`

- [ ] **Step 1: Add pref keys**

在 `AppDataService` 的 keys 段（`KEY_IS_LOCAL_MODE` 行之后）追加：

```kotlin
    private const val KEY_SKIPPED_VERSION = "skipped_version"
    private const val KEY_UPDATE_POSTPONED_UNTIL = "update_postponed_until"
```

- [ ] **Step 2: Add sync getters/setters**

在 `AppDataService` 内（与其它 `getXxxSync` 同区）追加：

```kotlin
    // ==================== 应用更新偏好 ====================

    /** 用户「跳过此版本」记下的版本号（去 v，如 "1.3.0"）。 */
    fun getSkippedVersionSync(): String? = prefs.getString(KEY_SKIPPED_VERSION, null)

    fun setSkippedVersionSync(version: String) {
        prefs.edit().putString(KEY_SKIPPED_VERSION, version).apply()
    }

    /** 「稍后」写入的冷却截止时间戳（毫秒）；0 表示无冷却。 */
    fun getUpdatePostponedUntilSync(): Long = prefs.getLong(KEY_UPDATE_POSTPONED_UNTIL, 0L)

    fun setUpdatePostponedUntilSync(until: Long) {
        prefs.edit().putLong(KEY_UPDATE_POSTPONED_UNTIL, until).apply()
    }
```

- [ ] **Step 3: Verify build**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/AppDataService.kt
git commit -m "feat(update): persist skippedVersion + postponedUntil prefs"
```

---

## Task 7: UpdateService 网络拉取 + 下载

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/service/UpdateService.kt`

- [ ] **Step 1: Create UpdateService**

创建 `app/src/main/java/org/moontechlab/selenetv/service/UpdateService.kt`:

```kotlin
package org.moontechlab.selenetv.service

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.moontechlab.selenetv.model.GithubRelease
import org.moontechlab.selenetv.model.UpdateInfo
import java.io.File

/**
 * 应用更新服务。阻塞方法，必须在 Dispatchers.IO 调用；失败抛 ApiException。
 * 复用 MediaHttp.httpClient（trust-all），与项目网络约定一致。
 */
object UpdateService {
    private const val TAG = "UpdateService"
    private const val LATEST_URL =
        "https://api.github.com/repos/MoonTechLab/Selene-TV/releases/latest"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 当前 versionName（与 SettingsTab.VersionColophon 一致，不依赖 BuildConfig）。 */
    fun currentVersionName(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""

    /**
     * 检查更新。返回 UpdateInfo，或 null（无更新 / CI 构建中无资产 / 无匹配架构）。
     * 网络或解析失败抛 ApiException。
     */
    fun checkForUpdate(context: Context): UpdateInfo? {
        val release = fetchLatestRelease()
        val current = currentVersionName(context)
        val abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList()
        return UpdateLogic.buildUpdateInfo(release, current, abis)
    }

    private fun fetchLatestRelease(): GithubRelease {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SeleneTV")
            .build()
        try {
            MediaHttp.httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException("检查更新失败 (${resp.code})", resp.code)
                val body = resp.body?.string() ?: throw ApiException("检查更新失败：空响应")
                return json.decodeFromString<GithubRelease>(body)
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fetchLatestRelease failed: ${e.message}")
            throw ApiException("检查更新失败：${e.message}")
        }
    }

    /**
     * 下载 APK 到 cacheDir/update/。下载前清空该目录。
     * onProgress 回调 0f..1f；总长未知时回调 -1f。失败抛 ApiException 并删除残文件。
     */
    fun downloadApk(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val outFile = File(dir, "SeleneTV-${info.version}-${info.abi}.apk")
        val request = Request.Builder().url(info.apkUrl).header("User-Agent", "SeleneTV").build()
        try {
            MediaHttp.httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException("下载失败 (${resp.code})", resp.code)
                val body = resp.body ?: throw ApiException("下载失败：空响应")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(8 * 1024)
                        var downloaded = 0L
                        var read = input.read(buf)
                        while (read != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            onProgress(if (total > 0) downloaded.toFloat() / total else -1f)
                            read = input.read(buf)
                        }
                    }
                }
            }
        } catch (e: ApiException) {
            outFile.delete()
            throw e
        } catch (e: Exception) {
            outFile.delete()
            Log.w(TAG, "downloadApk failed: ${e.message}")
            throw ApiException("下载失败：${e.message}")
        }
        return outFile
    }
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/UpdateService.kt
git commit -m "feat(update): UpdateService fetch latest release + download APK"
```

---

## Task 8: Manifest 权限 + FileProvider + file_paths

**Files:**
- Create: `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create file_paths.xml**

创建 `app/src/main/res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!-- 对应 UpdateService 下载目录 cacheDir/update/ -->
    <cache-path name="update" path="update/" />
</paths>
```

- [ ] **Step 2: Add permission**

在 `AndroidManifest.xml` 既有 `<uses-permission android:name="android.permission.INTERNET" />` 后追加：

```xml
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

- [ ] **Step 3: Register FileProvider**

在 `AndroidManifest.xml` 的 `<application>` 元素内（任意 `<activity>` 旁）追加：

```xml
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [ ] **Step 4: Verify build**

Run: `./gradlew processDebugManifest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/file_paths.xml
git commit -m "feat(update): add install permission + FileProvider"
```

---

## Task 9: InstallHelper（FileProvider 安装 + 未知来源引导）

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/util/InstallHelper.kt`

- [ ] **Step 1: Create InstallHelper**

创建 `app/src/main/java/org/moontechlab/selenetv/util/InstallHelper.kt`:

```kotlin
package org.moontechlab.selenetv.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.moontechlab.selenetv.ui.MoonToast
import java.io.File

/**
 * 通过 FileProvider 拉起系统安装器安装 APK。
 * Android 8.0+ 需「允许安装未知应用」权限：未授予时引导用户去系统设置开启，返回后再次点击安装即可。
 */
object InstallHelper {
    private const val TAG = "InstallHelper"

    /** @return true=已拉起安装器；false=已跳转去授予未知来源权限（或失败），需用户处理后重试。 */
    fun installApk(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure {
                    Log.w(TAG, "open unknown-source settings failed: ${it.message}")
                    MoonToast.show("请在系统设置中允许本应用安装应用")
                }
            return false
        }

        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true }
            .getOrElse {
                Log.w(TAG, "start installer failed: ${it.message}")
                MoonToast.show("无法启动安装器")
                false
            }
    }
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL（若报 `androidx.core.content.FileProvider` 未解析，确认 `app/build.gradle.kts` 含 `androidx.core:core-ktx`——Compose 项目默认存在；缺失则补依赖后重试。）

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/util/InstallHelper.kt
git commit -m "feat(update): InstallHelper with FileProvider + unknown-source guide"
```

---

## Task 10: UpdateDialog 弹窗 UI + UpdateGate

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/UpdateDialog.kt`

- [ ] **Step 1: Create UpdateDialog.kt（含 UpdateGate）**

创建 `app/src/main/java/org/moontechlab/selenetv/screen/UpdateDialog.kt`:

```kotlin
package org.moontechlab.selenetv.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.model.UpdateInfo
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.service.UpdateLogic
import org.moontechlab.selenetv.service.UpdateService
import org.moontechlab.selenetv.ui.AnimatedSelectDialog
import org.moontechlab.selenetv.ui.DialogButton
import org.moontechlab.selenetv.util.InstallHelper
import java.io.File

private enum class Phase { INFO, DOWNLOADING, DONE, ERROR }

/**
 * 启动时挂在全局根（AppNavHost）的更新闸门：拉取→判定→（命中则）弹窗。
 * 自动检查的失败一律静默（仅日志）。每个进程组合一次。
 */
@Composable
fun UpdateGate() {
    val context = LocalContext.current
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            runCatching { UpdateService.checkForUpdate(context) }.getOrNull()
        } ?: return@LaunchedEffect
        val shouldPrompt = UpdateLogic.shouldPrompt(
            info = result,
            skippedVersion = AppDataService.getSkippedVersionSync(),
            postponedUntil = AppDataService.getUpdatePostponedUntilSync(),
            now = System.currentTimeMillis(),
            manual = false,
        )
        if (shouldPrompt) {
            info = result
            visible = true
        }
    }

    info?.let { i ->
        UpdateDialog(
            info = i,
            visible = visible,
            onDismiss = { visible = false },
            onPostpone = {
                AppDataService.setUpdatePostponedUntilSync(
                    System.currentTimeMillis() + UpdateLogic.COOLDOWN_MILLIS,
                )
            },
            onSkip = { AppDataService.setSkippedVersionSync(i.version) },
        )
    }
}

/**
 * 更新弹窗。三态：信息 / 下载中 / 完成 / 错误。
 * 下载中禁止返回键关闭；下载完成自动拉起安装器（未授予未知来源权限时转为「安装」按钮等用户授权后重试）。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    visible: Boolean,
    onDismiss: () -> Unit,
    onPostpone: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = remember { UpdateService.currentVersionName(context) }

    var phase by remember { mutableStateOf(Phase.INFO) }
    var progress by remember { mutableFloatStateOf(0f) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var errorMsg by remember { mutableStateOf("") }

    fun startDownload() {
        phase = Phase.DOWNLOADING
        progress = 0f
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    UpdateService.downloadApk(context, info) { p ->
                        // Compose 快照状态写入对后台线程安全，recomposition 会在主线程拾取
                        progress = p.coerceAtLeast(0f)
                    }
                }
                downloadedFile = file
                phase = Phase.DONE
                InstallHelper.installApk(context, file)
            } catch (e: Exception) {
                errorMsg = e.message ?: "下载失败"
                phase = Phase.ERROR
            }
        }
    }

    AnimatedSelectDialog(
        visible = visible,
        onDismiss = { if (phase != Phase.DOWNLOADING) onDismiss() },
    ) { onClose ->
        Box(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1A1A1A))
                .padding(28.dp),
        ) {
            when (phase) {
                Phase.INFO -> Column {
                    Text("发现新版本 ${info.version}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.padding(top = 4.dp))
                    Text("当前 $currentVersion", color = Color(0xFF888888), fontSize = 13.sp)
                    Spacer(Modifier.padding(top = 16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            info.changelog.ifBlank { "（无更新说明）" },
                            color = Color(0xFFCCCCCC),
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.padding(top = 20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DialogButton(
                            text = "立即更新",
                            onClick = { startDownload() },
                            isPrimary = true,
                            modifier = Modifier.weight(1f),
                        )
                        DialogButton(
                            text = "稍后",
                            onClick = { onPostpone(); onClose() },
                            modifier = Modifier.weight(1f),
                        )
                        DialogButton(
                            text = "跳过此版本",
                            onClick = { onSkip(); onClose() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Phase.DOWNLOADING -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("正在下载更新…", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.padding(top = 16.dp))
                    val pct = if (progress >= 0f) "${(progress * 100).toInt()}%" else "下载中…"
                    Text(pct, color = Color(0xFF4CAF50), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    // 进度条
                    Spacer(Modifier.padding(top = 16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x22FFFFFF)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (progress >= 0f) progress else 0f)
                                .padding(vertical = 4.dp)
                                .background(Color(0xFF4CAF50)),
                        )
                    }
                }

                Phase.DONE -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("下载完成", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.padding(top = 16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DialogButton(
                            text = "安装",
                            onClick = { downloadedFile?.let { InstallHelper.installApk(context, it) } },
                            isPrimary = true,
                            modifier = Modifier.width(160.dp),
                        )
                        DialogButton(
                            text = "关闭",
                            onClick = onClose,
                            modifier = Modifier.width(160.dp),
                        )
                    }
                }

                Phase.ERROR -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("更新失败", color = Color(0xFFE74C3C), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.padding(top = 12.dp))
                    Text(errorMsg, color = Color(0xFFCCCCCC), fontSize = 13.sp)
                    Spacer(Modifier.padding(top = 20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DialogButton(
                            text = "重试",
                            onClick = { startDownload() },
                            isPrimary = true,
                            modifier = Modifier.width(160.dp),
                        )
                        DialogButton(
                            text = "关闭",
                            onClick = onClose,
                            modifier = Modifier.width(160.dp),
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/UpdateDialog.kt
git commit -m "feat(update): UpdateDialog UI (info/download/done/error) + UpdateGate"
```

---

## Task 11: 在 AppNavHost 挂载 UpdateGate（启动检查）

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/AppNavHost.kt`

- [ ] **Step 1: Mount UpdateGate**

在 `AppNavHost.kt` 的根 `Box` 内、`MoonToastHost(...)` 调用**之前**插入：

```kotlin
        // 启动时检查应用更新（命中且通过判定链才弹窗；失败静默）
        UpdateGate()
```

`UpdateGate` 与 `AppNavHost` 同包（`org.moontechlab.selenetv.screen`），无需额外 import。

- [ ] **Step 2: Verify build**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manual smoke test**

Run: `./gradlew installDebug`
在设备/模拟器启动 app。因当前 `versionName=1.2.2` 等于线上 latest，**预期不弹窗**（无更新路径正常、无崩溃）。
临时验证弹窗：将 `app/build.gradle.kts` 的 `versionName` 改为 `"1.0.0"`，重装后启动应弹出「发现新版本」。验证后**改回 `1.2.2`**。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/AppNavHost.kt
git commit -m "feat(update): run startup update check in AppNavHost"
```

---

## Task 12: 设置页手动「检查更新」

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/screen/tab/SettingsTab.kt`

- [ ] **Step 1: Add state + manual-check dialog hosting**

在 `SettingsTab` composable 的状态声明区（其它 `var showXxxDialog by remember { mutableStateOf(false) }` 附近）追加：

```kotlin
    var manualUpdateInfo by remember { mutableStateOf<org.moontechlab.selenetv.model.UpdateInfo?>(null) }
    var showManualUpdateDialog by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
```

确认该 composable 顶部已有 `val context = LocalContext.current` 与 `val scope = rememberCoroutineScope()`（SettingsTab 已使用协程；若缺 `scope` 则补 `val scope = rememberCoroutineScope()`）。

- [ ] **Step 2: Add the SettingRow**

在设置项所在 Column 中（靠近版本/关于区域）添加一行：

```kotlin
                SettingRow(
                    label = "检查更新",
                    value = if (checkingUpdate) "检查中…" else null,
                    onClick = {
                        if (checkingUpdate) return@SettingRow
                        checkingUpdate = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { UpdateService.checkForUpdate(context) }
                            }
                            checkingUpdate = false
                            result.fold(
                                onSuccess = { info ->
                                    if (info != null) {
                                        manualUpdateInfo = info
                                        showManualUpdateDialog = true
                                    } else {
                                        MoonToast.show("已是最新版本")
                                    }
                                },
                                onFailure = { MoonToast.show("检查更新失败") },
                            )
                        }
                    },
                )
```

- [ ] **Step 3: Host the dialog**

在 `SettingsTab` 内渲染对话框的区域（其它 `AnimatedSelectDialog` / dialog 调用附近）添加：

```kotlin
    manualUpdateInfo?.let { info ->
        org.moontechlab.selenetv.screen.UpdateDialog(
            info = info,
            visible = showManualUpdateDialog,
            onDismiss = { showManualUpdateDialog = false },
            // 手动检查：用户主动来查，「稍后」「跳过」仍可记录，行为与启动一致
            onPostpone = {
                AppDataService.setUpdatePostponedUntilSync(
                    System.currentTimeMillis() + org.moontechlab.selenetv.service.UpdateLogic.COOLDOWN_MILLIS,
                )
            },
            onSkip = { AppDataService.setSkippedVersionSync(info.version) },
        )
    }
```

- [ ] **Step 4: Add imports**

确认 `SettingsTab.kt` 顶部含以下 import（缺则补）：

```kotlin
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.service.UpdateService
import org.moontechlab.selenetv.ui.MoonToast
```

- [ ] **Step 5: Verify build**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/tab/SettingsTab.kt
git commit -m "feat(update): manual check-for-update entry in Settings"
```

---

## Task 13: 全量验证

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL，`UpdateLogicTest` 全绿。

- [ ] **Step 2: Full debug build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL（arm64-v8a / armeabi-v7a 两个 APK 产出）。

- [ ] **Step 3: Lint**

Run: `./gradlew lintDebug`
Expected: 无新增 error（关注 FileProvider / 权限相关告警）。

- [ ] **Step 4: 设备端冒烟（可选，需设备）**

按 Task 11 Step 3 的临时降版本法验证完整链路：弹窗→立即更新→进度→（首次）跳转开启未知来源→返回→安装→升级成功。验证后确认 `versionName` 已改回 `1.2.2`。

---

## 备注

- 自动检查全程静默失败（仅 `Log.w`），不打扰用户；手动检查与下载阶段才有 toast / 错误态。
- model 类落在 `org.moontechlab.selenetv.model.**`，已被 `proguard-rules.pro` 既有通配覆盖，Release 构建无需额外配置。
- `UpdateLogic` 是纯逻辑且全部被单测覆盖；`UpdateService`/`InstallHelper`/UI 依赖 Android framework，靠 `compileDebugKotlin` + 设备冒烟验证。
