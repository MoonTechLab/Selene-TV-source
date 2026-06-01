package org.moontechlab.selenetv.screen.tab

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.data.local.LocalStore
import org.moontechlab.selenetv.server.RemoteControlServer
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.service.DoubanCacheService
import org.moontechlab.selenetv.service.SearchCacheService
import org.moontechlab.selenetv.service.SubscriptionService
import org.moontechlab.selenetv.service.UpdateService
import qrcode.QRCode
import java.net.NetworkInterface
import org.moontechlab.selenetv.ui.AnimatedSelectDialog
import org.moontechlab.selenetv.ui.ConfirmDialogContent
import org.moontechlab.selenetv.ui.DialogButton

// ==================== 颜色常量 ====================
private val ItemBgDefault = Color(0x14FFFFFF) // rgba(255, 255, 255, 0.08)
private val ItemBgFocused = Color.White
private val TextDefault = Color.White
private val TextFocused = Color(0xFF0A0A0A) // 聚焦时深色
private val TextGray = Color(0xFF888888)   // 值文字灰色

private val RoleBadgeAdmin = Color(0xFFFFD700) // 管理 - 黄色
private val RoleBadgeOwner = Color(0xFF9B59B6) // 站长 - 紫色
private val RoleBadgeUser = Color(0xFF2ECC71)  // 用户 - 绿色

private val CacheButtonBgFocused = Color(0xFFF39C12) // 缓存行聚焦填充：实色橙黄
private val LogoutButtonBgFocused = Color(0xFFE74C3C) // 退出登录聚焦填充：实色红

/**
 * 获取设备所有 IPv4 地址（除回环 IP）
 */
private fun getAllIpAddresses(): List<String> {
    val allIps = mutableListOf<String>()
    try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
            if (networkInterface.isUp && !networkInterface.isLoopback) {
                networkInterface.inetAddresses?.toList()?.forEach { address ->
                    if (!address.isLoopbackAddress && address.hostAddress?.contains('.') == true) {
                        address.hostAddress?.let { allIps.add(it) }
                    }
                }
            }
        }
    } catch (_: Exception) {}
    return allIps
}

/**
 * 获取设备所有 IPv4 地址，优先返回 192.168.x.x 网段
 */
private fun getPreferredIpAddress(): String? {
    val allIps = getAllIpAddresses()
    // 优先选择 192.168.x.x 网段
    return allIps.find { it.startsWith("192.168.") } ?: allIps.firstOrNull()
}

@Composable
fun SettingsTab(
    focusRequester: FocusRequester = remember { FocusRequester() },
    scrollState: ScrollState = rememberScrollState(),
    onShowLoginScreen: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val doubanCacheService = remember { DoubanCacheService.getInstance(context) }

    // 右侧设置列的滚动状态：内容超出视口时由 bringIntoView 跟随焦点自动滚动
    val rightScrollState = rememberScrollState()

    // 用户状态
    var hasLogin by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var userRole by remember { mutableStateOf("user") }
    var serverUrl by remember { mutableStateOf("") }

    // 设置项状态
    var doubanDataSource by remember { mutableStateOf("") }
    var doubanImageSource by remember { mutableStateOf("") }
    var bangumiDataSource by remember { mutableStateOf("") }
    var bangumiImageSource by remember { mutableStateOf("") }
    var tmdbApiKey by remember { mutableStateOf("") }
    var m3u8ProxyUrl by remember { mutableStateOf("") }
    var playerType by remember { mutableStateOf("") }
    var decodeMode by remember { mutableStateOf("") }
    var searchOrigin by remember { mutableStateOf("") }
    val mpvSupported = android.os.Build.VERSION.SDK_INT >= org.moontechlab.selenetv.screen.player.MPV_MIN_SDK

    // IP 选择相关状态
    var showIpSelectDialog by remember { mutableStateOf(false) }
    var availableIps by remember { mutableStateOf<List<String>>(emptyList()) }
    // 初值留空，IP 在下方 LaunchedEffect 中于 IO 线程异步枚举，避免组合期阻塞
    var selectedIp by remember { mutableStateOf("") }

    // 豆瓣源选择弹框状态
    var showDoubanDataSourceDialog by remember { mutableStateOf(false) }
    var showDoubanImageSourceDialog by remember { mutableStateOf(false) }
    var showBangumiDataSourceDialog by remember { mutableStateOf(false) }
    var showBangumiImageSourceDialog by remember { mutableStateOf(false) }
    var showPlayerTypeDialog by remember { mutableStateOf(false) }
    var showDecodeModeDialog by remember { mutableStateOf(false) }
    var showSearchOriginDialog by remember { mutableStateOf(false) }

    // 退出登录两步弹框（均由「登录实例」项触发）：
    // A 登录实例弹框（含单个退出登录按钮）→ B 二次确认弹框（取消 / 确认）
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }
    // A→B 过渡标记：A 关闭后才开 B，期间不回收焦点
    var logoutChainToConfirm by remember { mutableStateOf(false) }
    // B 已确认登出标记：确认后已跳转登录页，B 关闭时不回收设置焦点
    var logoutConfirmed by remember { mutableStateOf(false) }

    // 各设置项的 FocusRequester
    val loginInstanceFocusRequester = remember { FocusRequester() }
    val ipSettingFocusRequester = remember { FocusRequester() }
    val doubanDataSourceFocusRequester = remember { FocusRequester() }
    val playerTypeFocusRequester = remember { FocusRequester() }
    val decodeModeFocusRequester = remember { FocusRequester() }
    val searchOriginFocusRequester = remember { FocusRequester() }
    val doubanImageSourceFocusRequester = remember { FocusRequester() }
    val bangumiDataSourceFocusRequester = remember { FocusRequester() }
    val bangumiImageSourceFocusRequester = remember { FocusRequester() }
    val tmdbApiKeyFocusRequester = remember { FocusRequester() }
    val m3u8ProxyFocusRequester = remember { FocusRequester() }

    // 输入弹框状态
    var showTmdbApiKeyDialog by remember { mutableStateOf(false) }
    var showM3u8ProxyDialog by remember { mutableStateOf(false) }

    // 缓存清理确认弹框状态
    var showClearDoubanCacheDialog by remember { mutableStateOf(false) }
    var showClearSearchCacheDialog by remember { mutableStateOf(false) }
    var showClearDanmakuCacheDialog by remember { mutableStateOf(false) }

    // 缓存按钮 FocusRequester
    val clearDoubanCacheFocusRequester = remember { FocusRequester() }
    val clearSearchCacheFocusRequester = remember { FocusRequester() }
    val clearDanmakuCacheFocusRequester = remember { FocusRequester() }

    // 手动检查更新状态
    var manualUpdateInfo by remember { mutableStateOf<org.moontechlab.selenetv.model.UpdateInfo?>(null) }
    var showManualUpdateDialog by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    val checkUpdateFocusRequester = remember { FocusRequester() }

    var isLocalMode by remember { mutableStateOf(AppDataService.isLocalModeSync()) }
    var danmakuEnabled by remember { mutableStateOf(AppDataService.isDanmakuEnabledSync()) }
    var subscriptionUrl by remember { mutableStateOf("") }

    // 本地模式分区相关弹框状态
    var showSubscriptionUrlDialog by remember { mutableStateOf(false) }
    var showExitLocalModeDialog by remember { mutableStateOf(false) }
    var showExitLocalModeConfirmDialog by remember { mutableStateOf(false) }
    var isRefreshingSubscription by remember { mutableStateOf(false) }
    var exitLocalModeChainToConfirm by remember { mutableStateOf(false) }
    var exitLocalModeConfirmed by remember { mutableStateOf(false) }

    // 本地模式分区的 FocusRequester
    val subscriptionUrlFocusRequester = remember { FocusRequester() }
    val refreshSubscriptionFocusRequester = remember { FocusRequester() }
    val exitLocalModeFocusRequester = remember { FocusRequester() }

    // 不在组合时自动抢焦点：与其他 Tab 一致，切到设置后焦点应停留在顶部 TabSwitcher，
    // 由 TabSwitcher.onNavigateDown → settingsFocusRequester 在按「下」键时再进入内容。

    // 加载数据
    LaunchedEffect(Unit) {
        hasLogin = AppDataService.isLoggedIn()
        username = AppDataService.getUsername() ?: ""
        serverUrl = AppDataService.getServerUrl() ?: ""
        val cookies = AppDataService.getCookies()
        userRole = AppDataService.parseRoleFromCookies(cookies)
        doubanDataSource = AppDataService.getDoubanDataSourceDisplayName()
        doubanImageSource = AppDataService.getDoubanImageSourceDisplayName()
        bangumiDataSource = AppDataService.getBangumiDataSourceDisplayName()
        bangumiImageSource = AppDataService.getBangumiImageSourceDisplayName()
        tmdbApiKey = AppDataService.getTmdbApiKey()
        m3u8ProxyUrl = AppDataService.getM3u8ProxyUrl()
        playerType = AppDataService.getPlayerTypeDisplayName()
        decodeMode = AppDataService.getDecodeModeDisplayName()
        searchOrigin = AppDataService.getSearchOriginDisplayName()
        // 加载可用 IP 列表（网络接口枚举可能阻塞，放到 IO 线程）
        val ips = withContext(Dispatchers.IO) { getAllIpAddresses() }
        availableIps = ips
        if (selectedIp.isEmpty()) {
            selectedIp = ips.find { it.startsWith("192.168.") } ?: ips.firstOrNull() ?: ""
        }
        isLocalMode = AppDataService.isLocalMode()
        if (isLocalMode) {
            subscriptionUrl = LocalStore.getSubscriptionUrl().orEmpty()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 顶部留出 TabSwitcher 的空间
        Spacer(modifier = Modifier.height(68.dp))

        // 主内容区域 - 两栏布局（各占50%）
        // 用 weight(1f) 占满 TabSwitcher 与底部留白之间的全部高度，给右侧列一个确定的视口高度，
        // 否则两列都 fillMaxHeight 时 Row 会塌缩、滚动视口短于内容导致内容被裁。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp)
        ) {
            // 左侧二维码区域 - 占50%
            RemoteControlQrCode(
                selectedIp = selectedIp,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            // 右侧设置区域 - 占50%
            // tvOS 风格的紧凑分组列表：安静的分组标题 + hairline 分隔的细行，
            // 去掉原先的胶囊背景（每项约省一半高度），并接上垂直滚动作为未来扩容的兜底。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rightScrollState)
            ) {
                // 顶部留白，与左侧二维码标题对齐
                Spacer(modifier = Modifier.height(48.dp))

                // 用户头部（已登录时显示）
                if (hasLogin && !isLocalMode) {
                    CompactUserHeader(username = username, role = userRole)
                    Spacer(modifier = Modifier.height(20.dp))
                }

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
                        label = if (isRefreshingSubscription) "刷新中..." else "立即刷新订阅",
                        onClick = {
                            if (!isRefreshingSubscription) {
                                isRefreshingSubscription = true
                                coroutineScope.launch {
                                    val ok = SubscriptionService.refreshAndPersist()
                                    isRefreshingSubscription = false
                                    org.moontechlab.selenetv.ui.MoonToast.show(
                                        if (ok) "订阅已刷新" else "订阅刷新失败"
                                    )
                                }
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

                Spacer(modifier = Modifier.height(18.dp))

                // —— 遥控器 ——
                SectionLabel("遥控器")
                SettingRow(
                    label = "调整遥控二维码 IP",
                    value = selectedIp.ifEmpty { "未知" },
                    showDivider = false,
                    onClick = { showIpSelectDialog = true },
                    modifier = Modifier.focusRequester(ipSettingFocusRequester)
                )

                Spacer(modifier = Modifier.height(18.dp))

                // —— 数据源 ——
                SectionLabel("数据源")
                SettingRow(
                    label = "豆瓣数据源",
                    value = doubanDataSource.ifEmpty { "直连" },
                    onClick = { showDoubanDataSourceDialog = true },
                    modifier = Modifier.focusRequester(doubanDataSourceFocusRequester)
                )
                SettingRow(
                    label = "豆瓣图片源",
                    value = doubanImageSource.ifEmpty { "直连" },
                    onClick = { showDoubanImageSourceDialog = true },
                    modifier = Modifier.focusRequester(doubanImageSourceFocusRequester)
                )
                SettingRow(
                    label = "Bangumi 数据源",
                    value = bangumiDataSource.ifEmpty { "直连" },
                    onClick = { showBangumiDataSourceDialog = true },
                    modifier = Modifier.focusRequester(bangumiDataSourceFocusRequester)
                )
                SettingRow(
                    label = "Bangumi 图片源",
                    value = bangumiImageSource.ifEmpty { "直连" },
                    onClick = { showBangumiImageSourceDialog = true },
                    modifier = Modifier.focusRequester(bangumiImageSourceFocusRequester)
                )
                // TMDB API Key（填写后详情页可拉取剧照/ logo 渲染 Apple TV 式 hero）
                SettingRow(
                    label = "TMDB API Key",
                    value = if (tmdbApiKey.isEmpty()) "未设置" else "已设置",
                    onClick = { showTmdbApiKeyDialog = true },
                    modifier = Modifier.focusRequester(tmdbApiKeyFocusRequester)
                )
                SettingRow(
                    label = "M3U8 代理",
                    value = m3u8ProxyUrl.ifEmpty { "未设置" },
                    showDivider = false,
                    onClick = { showM3u8ProxyDialog = true },
                    modifier = Modifier.focusRequester(m3u8ProxyFocusRequester)
                )

                if (!isLocalMode) {
                    Spacer(modifier = Modifier.height(18.dp))

                    // —— 搜索 ——
                    SectionLabel("搜索")
                    SettingRow(
                        label = "搜索发起方",
                        value = searchOrigin.ifEmpty { AppDataService.SearchOrigin.TV_DIRECT.displayName },
                        showDivider = false,
                        onClick = { showSearchOriginDialog = true },
                        modifier = Modifier.focusRequester(searchOriginFocusRequester),
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // —— 播放 ——
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
                // 弹幕开关（Phase 0：B站源，点播生效）
                SettingRow(
                    label = "弹幕",
                    value = if (danmakuEnabled) "开" else "关",
                    showDivider = false,
                    onClick = {
                        val next = !danmakuEnabled
                        danmakuEnabled = next
                        coroutineScope.launch { AppDataService.setDanmakuEnabled(next) }
                    },
                )

                Spacer(modifier = Modifier.height(18.dp))

                // —— 缓存 ——
                SectionLabel("缓存")
                SettingRow(
                    label = "清空豆瓣缓存",
                    accent = RowAccent.Orange,
                    onClick = { showClearDoubanCacheDialog = true },
                    modifier = Modifier.focusRequester(clearDoubanCacheFocusRequester)
                )
                SettingRow(
                    label = "清空搜索缓存",
                    accent = RowAccent.Orange,
                    onClick = { showClearSearchCacheDialog = true },
                    modifier = Modifier.focusRequester(clearSearchCacheFocusRequester)
                )
                SettingRow(
                    label = "清空弹幕缓存",
                    accent = RowAccent.Orange,
                    showDivider = false,
                    onClick = { showClearDanmakuCacheDialog = true },
                    modifier = Modifier.focusRequester(clearDanmakuCacheFocusRequester)
                )

                Spacer(modifier = Modifier.height(18.dp))

                // —— 关于 ——
                SectionLabel("关于")
                SettingRow(
                    label = "检查更新",
                    value = if (checkingUpdate) "检查中…" else null,
                    showDivider = false,
                    onClick = {
                        if (checkingUpdate) return@SettingRow
                        checkingUpdate = true
                        coroutineScope.launch {
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
                                        org.moontechlab.selenetv.ui.MoonToast.show("已是最新版本")
                                    }
                                },
                                onFailure = { org.moontechlab.selenetv.ui.MoonToast.show("检查更新失败") },
                            )
                        }
                    },
                    modifier = Modifier.focusRequester(checkUpdateFocusRequester),
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
    }

    // IP 选择弹框
    AnimatedSelectDialog(
        visible = showIpSelectDialog,
        onDismiss = { showIpSelectDialog = false },
        onClosed = { ipSettingFocusRequester.requestFocus() }
    ) { onClose ->
        IpSelectDialogContent(
            availableIps = availableIps,
            currentIp = selectedIp,
            onIpSelected = { ip ->
                selectedIp = ip
                onClose()
            },
            onDismiss = onClose
        )
    }

    // 豆瓣数据源选择弹框
    AnimatedSelectDialog(
        visible = showDoubanDataSourceDialog,
        onDismiss = { showDoubanDataSourceDialog = false },
        onClosed = { doubanDataSourceFocusRequester.requestFocus() }
    ) { onClose ->
        SourceSelectDialogContent(
            title = "选择豆瓣数据源",
            options = AppDataService.DoubanDataSource.entries.map { it.displayName },
            currentValue = doubanDataSource,
            onSelected = { displayName ->
                coroutineScope.launch {
                    AppDataService.saveDoubanDataSource(displayName)
                    doubanDataSource = displayName
                }
                onClose()
            },
            onDismiss = onClose
        )
    }

    // 豆瓣图片源选择弹框
    AnimatedSelectDialog(
        visible = showDoubanImageSourceDialog,
        onDismiss = { showDoubanImageSourceDialog = false },
        onClosed = { doubanImageSourceFocusRequester.requestFocus() }
    ) { onClose ->
        SourceSelectDialogContent(
            title = "选择豆瓣图片源",
            // 本地模式无服务端，过滤掉「服务器代理」选项
            options = AppDataService.DoubanImageSource.entries
                .filter { !isLocalMode || it != AppDataService.DoubanImageSource.SERVER_PROXY }
                .map { it.displayName },
            currentValue = doubanImageSource,
            onSelected = { displayName ->
                coroutineScope.launch {
                    AppDataService.saveDoubanImageSource(displayName)
                    doubanImageSource = displayName
                }
                onClose()
            },
            onDismiss = onClose
        )
    }

    // Bangumi 数据源选择弹框
    AnimatedSelectDialog(
        visible = showBangumiDataSourceDialog,
        onDismiss = { showBangumiDataSourceDialog = false },
        onClosed = { bangumiDataSourceFocusRequester.requestFocus() }
    ) { onClose ->
        SourceSelectDialogContent(
            title = "选择 Bangumi 数据源",
            options = AppDataService.BangumiDataSource.entries.map { it.displayName },
            currentValue = bangumiDataSource,
            onSelected = { displayName ->
                coroutineScope.launch {
                    AppDataService.saveBangumiDataSource(displayName)
                    bangumiDataSource = displayName
                }
                onClose()
            },
            onDismiss = onClose
        )
    }

    // Bangumi 图片源选择弹框（本地模式过滤「服务器代理」）
    AnimatedSelectDialog(
        visible = showBangumiImageSourceDialog,
        onDismiss = { showBangumiImageSourceDialog = false },
        onClosed = { bangumiImageSourceFocusRequester.requestFocus() }
    ) { onClose ->
        SourceSelectDialogContent(
            title = "选择 Bangumi 图片源",
            options = AppDataService.BangumiImageSource.entries
                .filter { !isLocalMode || it != AppDataService.BangumiImageSource.SERVER_PROXY }
                .map { it.displayName },
            currentValue = bangumiImageSource,
            onSelected = { displayName ->
                coroutineScope.launch {
                    AppDataService.saveBangumiImageSource(displayName)
                    bangumiImageSource = displayName
                }
                onClose()
            },
            onDismiss = onClose
        )
    }

    // 播放器选择弹框
    AnimatedSelectDialog(
        visible = showPlayerTypeDialog,
        onDismiss = { showPlayerTypeDialog = false },
        onClosed = { playerTypeFocusRequester.requestFocus() }
    ) { onClose ->
        SourceSelectDialogContent(
            title = "选择播放器",
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

    // 解码方式选择弹框
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

    // 搜索发起方选择弹框
    AnimatedSelectDialog(
        visible = showSearchOriginDialog,
        onDismiss = { showSearchOriginDialog = false },
        onClosed = { searchOriginFocusRequester.requestFocus() }
    ) { onClose ->
        SourceSelectDialogContent(
            title = "选择搜索发起方",
            options = AppDataService.SearchOrigin.entries.map { it.displayName },
            currentValue = searchOrigin,
            onSelected = { displayName ->
                coroutineScope.launch {
                    AppDataService.saveSearchOrigin(displayName)
                    searchOrigin = displayName
                }
                onClose()
            },
            onDismiss = onClose
        )
    }

    // TMDB API Key 输入弹框
    AnimatedSelectDialog(
        visible = showTmdbApiKeyDialog,
        onDismiss = { showTmdbApiKeyDialog = false },
        onClosed = { tmdbApiKeyFocusRequester.requestFocus() }
    ) { onClose ->
        TextInputDialogContent(
            title = "TMDB API Key",
            placeholder = "请输入 TMDB API Key",
            initialValue = tmdbApiKey,
            onConfirm = { value ->
                coroutineScope.launch {
                    AppDataService.saveTmdbApiKey(value)
                    tmdbApiKey = value
                }
                onClose()
            },
            onDismiss = onClose
        )
    }

    // M3U8 代理输入弹框
    AnimatedSelectDialog(
        visible = showM3u8ProxyDialog,
        onDismiss = { showM3u8ProxyDialog = false },
        onClosed = { m3u8ProxyFocusRequester.requestFocus() }
    ) { onClose ->
        TextInputDialogContent(
            title = "M3U8 代理",
            placeholder = "请输入 M3U8 代理地址",
            initialValue = m3u8ProxyUrl,
            onConfirm = { value ->
                coroutineScope.launch {
                    AppDataService.saveM3u8ProxyUrl(value)
                    m3u8ProxyUrl = value
                }
                onClose()
            },
            onDismiss = onClose
        )
    }

    // 清空豆瓣缓存确认弹框
    AnimatedSelectDialog(
        visible = showClearDoubanCacheDialog,
        onDismiss = { showClearDoubanCacheDialog = false },
        onClosed = { clearDoubanCacheFocusRequester.requestFocus() }
    ) { onClose ->
        ConfirmDialogContent(
            title = "清空豆瓣缓存",
            message = "确定要清空所有豆瓣缓存数据吗？",
            onConfirm = {
                coroutineScope.launch {
                    doubanCacheService.clearAll()
                    org.moontechlab.selenetv.ui.MoonToast.show("豆瓣缓存已清空")
                }
                onClose()
            },
            onDismiss = onClose
        )
    }

    // 清空搜索缓存确认弹框
    AnimatedSelectDialog(
        visible = showClearSearchCacheDialog,
        onDismiss = { showClearSearchCacheDialog = false },
        onClosed = { clearSearchCacheFocusRequester.requestFocus() }
    ) { onClose ->
        ConfirmDialogContent(
            title = "清空搜索缓存",
            message = "确定要清空所有搜索缓存数据吗？",
            onConfirm = {
                coroutineScope.launch {
                    SearchCacheService.clearCache()
                    org.moontechlab.selenetv.ui.MoonToast.show("搜索缓存已清空")
                }
                onClose()
            },
            onDismiss = onClose
        )
    }

    // 清空弹幕缓存确认弹框
    AnimatedSelectDialog(
        visible = showClearDanmakuCacheDialog,
        onDismiss = { showClearDanmakuCacheDialog = false },
        onClosed = { clearDanmakuCacheFocusRequester.requestFocus() }
    ) { onClose ->
        ConfirmDialogContent(
            title = "清空弹幕缓存",
            message = "确定要清空所有弹幕缓存数据吗？",
            onConfirm = {
                coroutineScope.launch {
                    org.moontechlab.selenetv.service.DanmakuService.clearCache(context)
                    org.moontechlab.selenetv.ui.MoonToast.show("弹幕缓存已清空")
                }
                onClose()
            },
            onDismiss = onClose
        )
    }

    // 退出登录弹框 A（由「登录实例」项触发，仅含一个退出登录按钮）
    // 焦点处理参考 TMDB 弹框：关闭后把焦点交还触发项；点退出登录则进入二次确认弹框 B，
    // 此时不回收焦点，待 A 关闭动画结束再开 B，保证两个独立 Dialog 窗口的焦点顺序交接。
    AnimatedSelectDialog(
        visible = showLogoutDialog,
        onDismiss = { showLogoutDialog = false },
        onClosed = {
            if (logoutChainToConfirm) {
                logoutChainToConfirm = false
                showLogoutConfirmDialog = true
            } else {
                try { loginInstanceFocusRequester.requestFocus() } catch (_: Exception) {}
            }
        }
    ) { onClose ->
        LogoutDialogContent(
            serverUrl = serverUrl,
            onConfirm = {
                logoutChainToConfirm = true
                onClose()
            }
        )
    }

    // 退出登录弹框 B（二次确认，取消 / 确认）
    // 确认即清除登录态并跳转登录页，焦点交给登录页，故关闭时不回收设置焦点；
    // 取消则把焦点交还「登录实例」项（此时尚未登出，该项仍在）。
    AnimatedSelectDialog(
        visible = showLogoutConfirmDialog,
        onDismiss = { showLogoutConfirmDialog = false },
        onClosed = {
            if (logoutConfirmed) {
                logoutConfirmed = false
            } else {
                try { loginInstanceFocusRequester.requestFocus() } catch (_: Exception) {}
            }
        }
    ) { onClose ->
        ConfirmDialogContent(
            title = "退出登录",
            message = "确定要退出登录吗？",
            confirmText = "确认",
            confirmDestructive = true,
            focusConfirm = false,
            onConfirm = {
                logoutConfirmed = true
                hasLogin = false
                userRole = "user"
                coroutineScope.launch { AppDataService.clearPasswordAndCookies() }
                // 登出后回到登录页
                onShowLoginScreen()
                onClose()
            },
            onDismiss = onClose
        )
    }

    // 订阅链接重填弹框
    AnimatedSelectDialog(
        visible = showSubscriptionUrlDialog,
        onDismiss = { showSubscriptionUrlDialog = false },
        onClosed = { try { subscriptionUrlFocusRequester.requestFocus() } catch (_: Exception) {} },
    ) { onClose ->
        TextInputDialogContent(
            title = "订阅链接",
            placeholder = "请输入订阅链接",
            initialValue = subscriptionUrl,
            onConfirm = { newUrl ->
                val trimmed = newUrl.trim()
                if (trimmed.isEmpty()) {
                    org.moontechlab.selenetv.ui.MoonToast.show("请输入订阅链接")
                    return@TextInputDialogContent
                }
                onClose()
                coroutineScope.launch {
                    val parsed = try {
                        withContext(Dispatchers.IO) { SubscriptionService.fetchAndParse(trimmed) }
                    } catch (e: SubscriptionService.SubscriptionException) {
                        org.moontechlab.selenetv.ui.MoonToast.show(e.message ?: "订阅校验失败")
                        return@launch
                    } catch (e: Exception) {
                        org.moontechlab.selenetv.ui.MoonToast.show("订阅校验失败：网络异常: ${e.message}")
                        return@launch
                    }
                    val existing = withContext(Dispatchers.IO) { LocalStore.getSubscriptionUrl() }
                    val shouldClearAll = !existing.isNullOrBlank() && existing != trimmed
                    withContext(Dispatchers.IO) {
                        if (shouldClearAll) LocalStore.clearAll()
                        LocalStore.saveSubscriptionUrl(trimmed)
                        LocalStore.saveSubscriptionSources(parsed.searchResources, parsed.liveSources)
                        org.moontechlab.selenetv.service.LiveService.clearAllCache()
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

    // 退出本地模式弹框 A（按钮触发二次确认）
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
            title = "本地模式订阅",
            confirmText = "退出本地模式",
            onConfirm = {
                exitLocalModeChainToConfirm = true
                onClose()
            },
        )
    }

    // 退出本地模式二次确认弹框
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
                        org.moontechlab.selenetv.service.LiveService.clearAllCache()
                    }
                    // 必须等 IO 落盘完成再导航：否则 SettingsTab 离开 composition 时 rememberCoroutineScope 取消正在进行的写入
                    onShowLoginScreen()
                    onClose()
                }
            },
            onDismiss = onClose,
        )
    }

    // 手动检查更新弹框
    manualUpdateInfo?.let { info ->
        org.moontechlab.selenetv.screen.UpdateDialog(
            info = info,
            visible = showManualUpdateDialog,
            onDismiss = { showManualUpdateDialog = false },
            onPostpone = {
                AppDataService.setUpdatePostponedUntilSync(
                    System.currentTimeMillis() + org.moontechlab.selenetv.service.UpdateLogic.COOLDOWN_MILLIS,
                )
            },
            onSkip = { AppDataService.setSkippedVersionSync(info.version) },
        )
    }
}


/**
 * IP 选择弹框内容
 */
@Composable
private fun IpSelectDialogContent(
    availableIps: List<String>,
    currentIp: String,
    onIpSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        firstItemFocusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .width(400.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1A1A))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "选择二维码 IP",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextDefault
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "选择用于生成遥控器二维码的 IP 地址",
            fontSize = 12.sp,
            color = TextGray
        )

        Spacer(modifier = Modifier.height(20.dp))

        availableIps.forEachIndexed { index, ip ->
            IpSelectItem(
                ip = ip,
                isSelected = currentIp == ip,
                onClick = { onIpSelected(ip) },
                modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (availableIps.isEmpty()) {
            Text(
                text = "未检测到可用的网络接口",
                fontSize = 12.sp,
                color = TextGray
            )
        }
    }
}

/**
 * 通用源选择弹框内容
 */
@Composable
private fun SourceSelectDialogContent(
    title: String,
    options: List<String>,
    currentValue: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        firstItemFocusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .width(450.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1A1A))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextDefault
        )

        Spacer(modifier = Modifier.height(20.dp))

        options.forEachIndexed { index, option ->
            SourceSelectItem(
                text = option,
                isSelected = currentValue == option,
                onClick = { onSelected(option) },
                modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
            )
            if (index < options.lastIndex) {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

/**
 * 文本输入弹框内容
 */
@Composable
private fun TextInputDialogContent(
    title: String,
    placeholder: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var inputValue by remember { mutableStateOf(initialValue) }
    val confirmButtonFocusRequester = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(100)
        confirmButtonFocusRequester.requestFocus()
    }

    // 弹框是独立 Dialog 窗口，输入框无法被 Activity.currentFocus 命中、收不到网络遥控的 commitText。
    // 展示期间注册文本接收器，网络遥控文本直接写入本地状态（与 commitText 在光标处插入同义的追加）；
    // 弹框销毁即注销，主窗口输入框（登录页）不受影响。
    DisposableEffect(Unit) {
        org.moontechlab.selenetv.RemoteTextSink.sink = { incoming -> inputValue += incoming }
        onDispose { org.moontechlab.selenetv.RemoteTextSink.sink = null }
    }

    // IME 重弹死循环修复：弹框是独立窗口，输入框聚焦时弹 IME（另一个窗口）会抢走窗口焦点；
    // 收起键盘后弹框窗口重获焦点，Compose 默认把焦点还给输入框 → IME 又弹出 → 死循环。
    // 监听 IME 由「显示→隐藏」，若此刻输入框仍聚焦（含遥控 BACK 收键盘的路径），主动把焦点
    // 移到确认按钮：输入框失焦后既不会触发回弹，焦点也仍留在弹框内、不丢落点。
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val imeVisible = imeBottom > 0
    LaunchedEffect(imeVisible) {
        if (!imeVisible && fieldFocused) {
            try { confirmButtonFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    Column(
        modifier = Modifier
            .width(450.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1A1A))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextDefault
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 输入框
        BasicTextField(
            value = inputValue,
            onValueChange = { inputValue = it },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { fieldFocused = it.isFocused }
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2A2A2A))
                .border(1.dp, Color(0xFF444444), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            textStyle = TextStyle(
                color = TextDefault,
                fontSize = 14.sp
            ),
            cursorBrush = SolidColor(Color.White),
            singleLine = true,
            // 软键盘「完成」=确认动作：完成时把焦点移到确认按钮 → 输入框失焦、IME 关闭且不回弹
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                try { confirmButtonFocusRequester.requestFocus() } catch (_: Exception) {}
            }),
            decorationBox = { innerTextField ->
                Box {
                    if (inputValue.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = TextGray,
                            fontSize = 14.sp
                        )
                    }
                    innerTextField()
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 取消按钮
            DialogButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            )

            // 确认按钮
            DialogButton(
                text = "确认",
                onClick = { onConfirm(inputValue) },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(confirmButtonFocusRequester),
                isPrimary = true
            )
        }
    }
}


/**
 * 退出登录确认弹框内容
 * 展示登录实例地址 + 单个红色「退出登录」按钮（无取消按钮，BACK 即关闭）。
 * 焦点处理参考 TMDB 弹框：弹出后延迟聚焦到按钮，避免独立 Dialog 窗口下的焦点丢失。
 */
@Composable
private fun LogoutDialogContent(
    serverUrl: String,
    onConfirm: () -> Unit,
    title: String = "登录实例",
    confirmText: String = "退出登录",
) {
    val confirmButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        confirmButtonFocusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .width(400.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1A1A))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextDefault
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = serverUrl.ifEmpty { "未设置" },
            fontSize = 14.sp,
            color = TextGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(24.dp))

        DialogButton(
            text = confirmText,
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(confirmButtonFocusRequester),
            isDestructive = true
        )
    }
}

/**
 * 源选择项
 */
@Composable
private fun SourceSelectItem(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val textColor = if (isFocused) TextFocused else TextDefault

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ItemBgDefault,
            focusedContainerColor = ItemBgFocused
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                color = textColor
            )
            if (isSelected) {
                Text(
                    text = "✓",
                    fontSize = 16.sp,
                    color = if (isFocused) TextFocused else TextDefault
                )
            }
        }
    }
}

/**
 * IP 选择项
 */
@Composable
private fun IpSelectItem(
    ip: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val textColor = if (isFocused) TextFocused else TextDefault

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ItemBgDefault,
            focusedContainerColor = ItemBgFocused
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = ip,
                fontSize = 14.sp,
                color = textColor
            )
            if (isSelected) {
                Text(
                    text = "✓",
                    fontSize = 16.sp,
                    color = if (isFocused) TextFocused else TextDefault
                )
            }
        }
    }
}

/**
 * 遥控器二维码组件
 */
@Composable
private fun RemoteControlQrCode(
    selectedIp: String,
    modifier: Modifier = Modifier
) {
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var remoteUrl by remember { mutableStateOf("") }
    
    // 当 selectedIp 变化时重新生成二维码
    LaunchedEffect(selectedIp) {
        withContext(Dispatchers.IO) {
            val ip = selectedIp.ifEmpty { getPreferredIpAddress() }
            val port = RemoteControlServer.getPort()
            if (ip != null) {
                val url = "http://$ip:$port/remote_control"
                remoteUrl = url
                try {
                    val qrBytes = QRCode.ofSquares()
                        .build(url)
                        .renderToBytes()
                    qrBitmap = BitmapFactory.decodeByteArray(qrBytes, 0, qrBytes.size)
                } catch (_: Exception) {}
            }
        }
    }
    
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // verticalArrangement = Arrangement.Center
    ) { 
        Spacer(modifier = Modifier.height(48.dp))

        // 标题
        Text(
            text = "Web 遥控器",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextDefault
        )

        Spacer(modifier = Modifier.height(16.dp))
        
        qrBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "遥控器二维码",
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE8E8E8))
                    .padding(8.dp)
            )
        } ?: Box(
            modifier = Modifier.size(240.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "生成中...",
                fontSize = 12.sp,
                color = TextGray
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (remoteUrl.isNotEmpty()) {
            Text(
                text = "同局域网下浏览器打开：$remoteUrl",
                fontSize = 13.sp,
                color = TextGray
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = "若 IP 非本机 IP，可在右侧选项调整",
                fontSize = 13.sp,
                color = TextGray.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        VersionColophon()
    }
}

/**
 * 版本落款
 * 安静的品牌落款：细分隔线 + 绿色品牌点 + 字距拉开的字标 + 绿色版本号。
 * 不可聚焦、不参与 D-pad，仅作品牌与版本展示。版本号取自 PackageManager（不依赖 BuildConfig）。
 */
@Composable
private fun VersionColophon(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    if (versionName.isEmpty()) return

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 细分隔线（与玻璃拟态层同一 0.08 透明度白）
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.08f))
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 绿色品牌点（唯一点缀色）
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50))
            )
            Text(
                text = "SELENE TV",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
                color = TextGray
            )
            Text(
                text = "v$versionName",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF4CAF50).copy(alpha = 0.9f)
            )
        }
    }
}

/**
 * 用户头部（居中）
 * 「当前用户」标签 + 用户名 + 角色徽章，整体水平居中于右侧栏。
 */
@Composable
private fun CompactUserHeader(
    username: String,
    role: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "当前用户",
            fontSize = 12.sp,
            color = TextGray
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = username,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextDefault
            )

            RoleBadge(role = role)
        }
    }
}

/**
 * 分组标题：安静的灰色小字标，作为 tvOS 风格分组的分隔。
 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = TextGray.copy(alpha = 0.9f),
        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
    )
}

/**
 * 角色徽章
 */
@Composable
private fun RoleBadge(role: String) {
    val (badgeColor, badgeText) = when (role) {
        "owner" -> RoleBadgeOwner to "站长"
        "admin" -> RoleBadgeAdmin to "管理"
        else -> RoleBadgeUser to "用户"
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(badgeColor)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            text = badgeText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}

/**
 * 设置行的强调色：默认 / 橙（缓存）/ 红（退出登录）。
 */
private enum class RowAccent { Default, Orange, Red }

/**
 * tvOS 风格设置行
 * 无胶囊背景、紧凑高度（约为原胶囊的一半），靠 hairline 分隔线分组；
 * 聚焦时整行填充（默认白底深字，强调色行填充对应色 + 白字），不缩放，像干净的列表高亮。
 */
@Composable
private fun SettingRow(
    label: String,
    value: String? = null,
    accent: RowAccent = RowAccent.Default,
    focusable: Boolean = true,
    showDivider: Boolean = true,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 不可聚焦：纯展示行
    if (!focusable) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextDefault
                )
                if (value != null) {
                    Text(
                        text = value,
                        fontSize = 13.sp,
                        color = TextGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 240.dp)
                    )
                }
            }
            if (showDivider) RowDivider()
        }
        return
    }

    var isFocused by remember { mutableStateOf(false) }

    val idleLabelColor = when (accent) {
        RowAccent.Default -> TextDefault
        RowAccent.Orange -> Color(0xFFF39C12)
        RowAccent.Red -> Color(0xFFE74C3C)
    }
    val focusFill = when (accent) {
        RowAccent.Default -> ItemBgFocused
        RowAccent.Orange -> CacheButtonBgFocused
        RowAccent.Red -> LogoutButtonBgFocused
    }
    val focusTextColor = if (accent == RowAccent.Default) TextFocused else Color.White

    val labelColor = if (isFocused) focusTextColor else idleLabelColor
    val valueColor = if (isFocused) focusTextColor else TextGray

    Column {
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
                .onKeyEvent { event ->
                    // 拦截左右方向键，阻止焦点移动
                    event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                },
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = focusFill
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = labelColor
                )
                if (value != null) {
                    Text(
                        text = value,
                        fontSize = 13.sp,
                        color = valueColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 240.dp)
                    )
                }
            }
        }
        // 聚焦行隐藏自身底部分隔线，避免与白色填充叠在一起；高度保持 1dp 不抖动
        if (showDivider) RowDivider(visible = !isFocused)
    }
}

/**
 * 行间 hairline 分隔线（与玻璃拟态层同源的低透明度白），左右内缩与行内容对齐。
 */
@Composable
private fun RowDivider(visible: Boolean = true) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(if (visible) Color.White.copy(alpha = 0.07f) else Color.Transparent)
    )
}


@Preview(device = Devices.TV_1080p)
@Composable
fun SettingsTabPreview() {
    SettingsTab()
}
