# 启动时应用内更新检查 + 下载安装 — 设计文档

- 日期：2026-05-29
- 状态：已确认设计，待写实现计划
- 发布源：GitHub Releases `MoonTechLab/Selene-TV`

## 1. 目标

App 启动时自动检查 GitHub 上是否有更新版本，若有则弹窗提示用户，用户确认后**在应用内下载正确架构的 APK 并拉起系统安装器**。同时在「设置」Tab 提供手动「检查更新」入口。

非目标：后台静默自动安装、增量/差分更新、回滚、多渠道分发。

## 2. 发布源事实（已核实）

`GET https://api.github.com/repos/MoonTechLab/Selene-TV/releases/latest` 返回结构：

- `tag_name`：形如 `v1.2.2`
- `name`：版本标题
- `body`：Markdown 更新日志
- `published_at`
- `assets[]`：每个含 `name` / `browser_download_url` / `size` / `content_type`

资产命名固定为：

- `SeleneTV-v1.2.2-arm64-v8a.apk`
- `SeleneTV-v1.2.2-armeabi-v7a.apk`

`content_type` 为 `application/vnd.android.package-archive`。

`/releases/latest` 端点天然排除 draft 与 pre-release，无需额外过滤。

本地版本事实：`versionCode=11` / `versionName="1.2.2"`；`splits.abi` 仅产出 `arm64-v8a` 与 `armeabi-v7a`，无 universal 包。

## 3. 组件划分

| 组件 | 位置 | 职责 |
|---|---|---|
| `UpdateModels`（数据类） | `model/UpdateModels.kt` | kotlinx.serialization 映射 Releases API JSON |
| `UpdateService`（object 单例） | `service/UpdateService.kt` | 拉取 latest release、版本比较、ABI 选 APK、流式下载并回调进度 |
| `InstallHelper` | `util/InstallHelper.kt` | FileProvider 取 uri、未知来源安装权限引导、触发系统安装器 Intent |
| `UpdateDialog`（Composable） | `screen/UpdateDialog.kt` | 弹窗 UI（信息态 / 下载态 / 错误态），复用 `DialogButton` 与焦点约定 |
| AppDataService 扩展 | 既有 `service/AppDataService.kt` | 新增 `skippedVersion`、`updatePostponedUntil` 两个 SharedPreferences 项 |
| 启动触发 + 设置入口 | `MainActivity`/`HomeScreen`、`SettingsTab` | 启动自动检查的判定链；设置里的手动检查按钮 |

## 4. UpdateService 设计

约定与既有服务一致：**阻塞 + 在 `Dispatchers.IO` 调用 + 失败抛 `ApiException`**。

### 4.1 拉取
- 复用 `MediaHttp.httpClient`（trust-all + 跳过 hostname 校验）。GitHub 用真实证书，trust-all 无副作用，且符合项目「所有网络调用统一信任所有证书」的约定，避免再造客户端。
- 请求 `https://api.github.com/repos/MoonTechLab/Selene-TV/releases/latest`，解析 JSON 为 `UpdateModels`。

### 4.2 当前版本来源
- 用 `PackageManager.getPackageInfo(packageName, 0).versionName`，与 `SettingsTab.VersionColophon` 保持一致，不依赖 `BuildConfig`。

### 4.3 版本比较
- 取 `tag_name`，去掉前导 `v`，按 `.` 分段做数值比较。
- 远端 > 本地 才视为「有更新」。段数不一致时缺位补 0（如 `1.2` 视作 `1.2.0`）。
- 解析失败（非预期 tag 格式）按「无更新」处理，仅 log。

### 4.4 ABI 选择（关键）
release 按架构拆包，装错架构会安装失败，必须按当前设备选包：

1. 读取 `Build.SUPPORTED_ABIS`——系统返回的、**按设备优先级排序**的可运行 ABI 列表（64 位设备形如 `[arm64-v8a, armeabi-v7a, armeabi]`，32 位设备形如 `[armeabi-v7a, armeabi]`）。
2. 按该优先级顺序，对每个 ABI 在 `assets` 中查找文件名包含该 ABI 串的 APK，命中即选用。
3. 当前已知映射：`arm64-v8a` → `*-arm64-v8a.apk`；`armeabi-v7a` → `*-armeabi-v7a.apk`。
4. 实现上以「设备 ABI 串 ↔ asset 文件名包含匹配」为通用规则，使未来 `splits` 增加架构（如 `x86_64`）时无需改匹配逻辑。
5. **遍历完仍无匹配 → 返回「无可用更新」**（见 4.5）。

### 4.5 CI 构建中 / 无匹配资产的保护
- 若远端版本 > 本地，但 `assets` 中**找不到匹配当前 ABI 的 APK**（release 还在 CI 构建、APK 未上传；或本机架构罕见无对应包），则 `checkForUpdate` 返回 `null`，等同「无更新」，等下次启动再查。
- 对用户表现统一为「本次不提示」。

### 4.6 下载
- 流式写入 `cacheDir/update/SeleneTV-<version>-<abi>.apk`。
- 下载前清理 `cacheDir/update/` 旧文件。
- 按响应 `contentLength` 回调 `0f..1f` 进度（`contentLength` 缺失时回调 indeterminate）。
- 失败抛 `ApiException`，由弹窗转入错误态。

## 5. 更新偏好（AppDataService 扩展）

新增两项 SharedPreferences：

- `skippedVersion: String?`——用户「跳过此版本」记下的版本号。
- `updatePostponedUntil: Long`——「稍后」写入的冷却截止时间戳（毫秒）。

配套同步/挂起读写方法，风格与既有 `getXxxSync` / `saveXxx` 一致。

## 6. 启动触发与展示判定

在 `MainActivity`/`HomeScreen` 首帧后起协程调用 `UpdateService.checkForUpdate()`，按下列顺序决定是否弹窗：

1. 返回 `null`（无更新 / CI 构建中 / 无匹配 ABI）→ 不弹。
2. 网络/解析失败 → 自动检查**静默**（仅 log），不弹。
3. 版本 == `skippedVersion` → 不弹（**手动检查时忽略此条**）。
4. `now < updatePostponedUntil`（6 小时冷却内）→ 不弹（**手动检查时忽略此条**）。
5. 以上都不命中 → 弹窗。

冷却时长固定 **6 小时**。

### 设置里的手动「检查更新」
- 在 `SettingsTab` 增加一行「检查更新」（复用 `SettingRow`）。
- 走同一 `UpdateService`，但**绕过 skip + 冷却**。
- 结果用 toast 反馈：「已是最新」/「检查失败」；有更新则弹同一 `UpdateDialog`。

## 7. 弹窗 UI（UpdateDialog）

以 overlay 方式盖在上层（参照 `LoginScreen` 在 `TabSwitcher` 之上的绘制方式），遵循 TV 焦点与设计约定（`1.05x` / `0.6 damping` 焦点缩放、绿色 `#4CAF50` 主题、最小 11sp、玻璃拟态）。

三种状态：

1. **信息态**：标题「发现新版本 `<新版本号>`」、副行「当前 `<当前版本号>`」、可滚动的 release `body` 更新日志。
   - 三个 `DialogButton`：
     - **立即更新** → 进入下载态
     - **稍后** → 写入 `updatePostponedUntil = now + 6h`，关闭
     - **跳过此版本** → 写入 `skippedVersion = <新版本号>`，关闭
2. **下载态**：进度条（百分比）+「取消」。完成后调 `InstallHelper` 拉起安装器。
3. **错误态**：错误提示 +「重试」/「关闭」。

D-pad 焦点初始落在「立即更新」；注意 Lazy/滚动容器困焦问题参照既有约定处理。

## 8. Manifest 与安装

### 8.1 权限
- 新增 `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`。

### 8.2 FileProvider
- Manifest 注册 `androidx.core.content.FileProvider`，authority = `${applicationId}.fileprovider`。
- 新增 `res/xml/file_paths.xml`，暴露 `cache-path`（对应 `cacheDir/update/`）。

### 8.3 安装流程（InstallHelper）
- **API 26+**：先查 `packageManager.canRequestPackageInstalls()`；为 false 则用 `Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, package:uri)` 引导用户开启「允许安装未知应用」，返回后再安装。
- 安装：`Intent(ACTION_VIEW)` + FileProvider `content://` uri + `setDataAndType(uri, "application/vnd.android.package-archive")` + `FLAG_GRANT_READ_URI_PERMISSION` + `FLAG_ACTIVITY_NEW_TASK`，拉起系统安装器。
- **API 23–25**：直接走 FileProvider 安装 Intent（这些版本无未知来源运行时开关）。
- TV 上引导文案需明确「需在系统设置中允许本应用安装应用」。

### 8.4 ProGuard
- model 类落在 `org.moontechlab.selenetv.model.**`，已被既有通配规则覆盖，无需新增。

## 9. 错误处理总则

- **自动检查**：全程静默失败，不打扰用户（仅 log）。
- **手动检查**：检查阶段失败给 toast。
- **下载阶段**：失败转弹窗错误态，提供重试。
- **ABI 无匹配 / 无 asset**：一律静默跳过，等下次启动。

## 10. 测试要点

- 版本比较：`1.2.2` vs `1.2.10`、段数不齐、非法 tag。
- ABI 选择：64 位设备选 arm64、32 位设备选 v7a、无匹配返回 null。
- CI 构建中：远端版本更高但无 asset → null。
- 判定链：skipped / 冷却内 / 手动绕过。

可单元测试的纯逻辑（版本比较、ABI 匹配、判定链）应抽成不依赖 Android framework 的纯函数以便 `testDebugUnitTest` 覆盖。
