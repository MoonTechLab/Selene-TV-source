package org.moontechlab.selenetv.screen.tab

import android.graphics.BitmapFactory
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import org.moontechlab.selenetv.server.RemoteControlServer
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.service.DoubanCacheService
import org.moontechlab.selenetv.service.SearchCacheService
import qrcode.QRCode
import java.net.NetworkInterface

// ==================== 颜色常量 ====================
private val ItemBgDefault = Color(0x14FFFFFF) // rgba(255, 255, 255, 0.08)
private val ItemBgFocused = Color.White
private val TextDefault = Color.White
private val TextFocused = Color(0xFF0A0A0A) // 聚焦时深色
private val TextGray = Color(0xFF888888)   // 值文字灰色

private val RoleBadgeAdmin = Color(0xFFFFD700) // 管理 - 黄色
private val RoleBadgeOwner = Color(0xFF9B59B6) // 站长 - 紫色
private val RoleBadgeUser = Color(0xFF2ECC71)  // 用户 - 绿色

private val CacheButtonBg = Color(0x33F39C12) // 半透明橙黄
private val CacheButtonBgFocused = Color(0xFFF39C12) // 实色橙黄
private val LogoutButtonBg = Color(0x33E74C3C) // 半透明红
private val LogoutButtonBgFocused = Color(0xFFE74C3C) // 实色红

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

    // 用户状态
    var hasLogin by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var userRole by remember { mutableStateOf("user") }
    var serverUrl by remember { mutableStateOf("") }

    // 设置项状态
    var doubanDataSource by remember { mutableStateOf("") }
    var doubanImageSource by remember { mutableStateOf("") }
    var tmdbApiKey by remember { mutableStateOf("") }
    var m3u8ProxyUrl by remember { mutableStateOf("") }

    // IP 选择相关状态
    var showIpSelectDialog by remember { mutableStateOf(false) }
    var availableIps by remember { mutableStateOf<List<String>>(emptyList()) }
    // 初值留空，IP 在下方 LaunchedEffect 中于 IO 线程异步枚举，避免组合期阻塞
    var selectedIp by remember { mutableStateOf("") }

    // 豆瓣源选择弹框状态
    var showDoubanDataSourceDialog by remember { mutableStateOf(false) }
    var showDoubanImageSourceDialog by remember { mutableStateOf(false) }

    // 各设置项的 FocusRequester
    val ipSettingFocusRequester = remember { FocusRequester() }
    val doubanDataSourceFocusRequester = remember { FocusRequester() }
    val doubanImageSourceFocusRequester = remember { FocusRequester() }
    val tmdbApiKeyFocusRequester = remember { FocusRequester() }
    val m3u8ProxyFocusRequester = remember { FocusRequester() }

    // 输入弹框状态
    var showTmdbApiKeyDialog by remember { mutableStateOf(false) }
    var showM3u8ProxyDialog by remember { mutableStateOf(false) }

    // 缓存清理确认弹框状态
    var showClearDoubanCacheDialog by remember { mutableStateOf(false) }
    var showClearSearchCacheDialog by remember { mutableStateOf(false) }

    // 缓存按钮 FocusRequester
    val clearDoubanCacheFocusRequester = remember { FocusRequester() }
    val clearSearchCacheFocusRequester = remember { FocusRequester() }

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
        tmdbApiKey = AppDataService.getTmdbApiKey()
        m3u8ProxyUrl = AppDataService.getM3u8ProxyUrl()
        // 加载可用 IP 列表（网络接口枚举可能阻塞，放到 IO 线程）
        val ips = withContext(Dispatchers.IO) { getAllIpAddresses() }
        availableIps = ips
        if (selectedIp.isEmpty()) {
            selectedIp = ips.find { it.startsWith("192.168.") } ?: ips.firstOrNull() ?: ""
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 顶部留出 TabSwitcher 的空间
        Spacer(modifier = Modifier.height(68.dp))

        // 主内容区域 - 两栏布局（各占50%）
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 用户信息区（已登录时显示）
                if (hasLogin) {
                    UserInfoSection(username = username, role = userRole)
                    Spacer(modifier = Modifier.height(2.dp))
                } else {
                    Spacer(modifier = Modifier.height(42.dp))
                }

                // 设置项组
                SettingsGroup {
                    // 登录实例（已登录时显示，仅显示不可点击）
                    if (hasLogin) {
                        SettingItem(
                            label = "登录实例",
                            value = serverUrl.ifEmpty { "未设置" },
                            focusable = false,
                            transparentBg = true
                        )
                    }

                    // 登录按钮（未登录时显示）
                    if (!hasLogin) {
                        SettingButton(
                            text = "登录",
                            onClick = { onShowLoginScreen() },
                            modifier = Modifier.focusRequester(focusRequester)
                        )
                    }

                    // 调整遥控二维码 IP
                    SettingItem(
                        label = "调整遥控二维码 IP",
                        value = selectedIp.ifEmpty { "未知" },
                        onClick = { showIpSelectDialog = true },
                        modifier = Modifier
                            .focusRequester(ipSettingFocusRequester)
                            .then(if (hasLogin) Modifier.focusRequester(focusRequester) else Modifier)
                    )

                    // 豆瓣数据源
                    SettingItem(
                        label = "豆瓣数据源",
                        value = doubanDataSource.ifEmpty { "直连" },
                        onClick = { showDoubanDataSourceDialog = true },
                        modifier = Modifier.focusRequester(doubanDataSourceFocusRequester)
                    )

                    // 豆瓣图片源
                    SettingItem(
                        label = "豆瓣图片源",
                        value = doubanImageSource.ifEmpty { "直连" },
                        onClick = { showDoubanImageSourceDialog = true },
                        modifier = Modifier.focusRequester(doubanImageSourceFocusRequester)
                    )

                    // TMDB API Key（填写后详情页可拉取剧照/ logo 渲染 Apple TV 式 hero）
                    SettingItem(
                        label = "TMDB API Key",
                        value = if (tmdbApiKey.isEmpty()) "未设置" else "已设置",
                        onClick = { showTmdbApiKeyDialog = true },
                        modifier = Modifier.focusRequester(tmdbApiKeyFocusRequester)
                    )

                    // M3U8 代理
                    SettingItem(
                        label = "M3U8 代理",
                        value = m3u8ProxyUrl.ifEmpty { "未设置" },
                        onClick = { showM3u8ProxyDialog = true },
                        modifier = Modifier.focusRequester(m3u8ProxyFocusRequester)
                    )

                    // 缓存清理按钮组
                    CacheButtonsRow(
                        onClearDoubanCache = { showClearDoubanCacheDialog = true },
                        onClearSearchCache = { showClearSearchCacheDialog = true },
                        clearDoubanCacheFocusRequester = clearDoubanCacheFocusRequester,
                        clearSearchCacheFocusRequester = clearSearchCacheFocusRequester
                    )

                    // 退出登录按钮（已登录时显示）
                    if (hasLogin) {
                        LogoutButton(
                            onClick = {
                                coroutineScope.launch {
                                    AppDataService.clearPasswordAndCookies()
                                    hasLogin = false
                                    userRole = "user"
                                }
                            }
                        )
                    }
                }
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
            options = AppDataService.DoubanImageSource.entries.map { it.displayName },
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
}

/**
 * 带动画的选择弹框容器
 */
@Composable
private fun AnimatedSelectDialog(
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
            // 只有之前显示过才触发 onClosed
            if (hasBeenVisible) {
                hasBeenVisible = false
                onClosed()
            }
        }
    }

    if (showDialog) {
        // 动画参数
        val showSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 400f)
        val hideSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 500f)

        val dialogScale by animateFloatAsState(
            targetValue = if (animateIn) 1f else 0.85f,
            animationSpec = if (animateIn) showSpring else hideSpring,
            label = "dialog_scale"
        )

        val dialogOpacity by animateFloatAsState(
            targetValue = if (animateIn) 1f else 0f,
            animationSpec = if (animateIn) showSpring else hideSpring,
            label = "dialog_opacity"
        )

        val overlayOpacity by animateFloatAsState(
            targetValue = if (animateIn) 1f else 0f,
            animationSpec = if (animateIn) showSpring else hideSpring,
            label = "overlay_opacity"
        )

        // 用独立 Dialog 窗口承载：弹框拥有独立焦点域，D-pad 上下键不会逃逸回背景设置项；
        // 也无需停用背景，避免打开瞬间焦点被甩到顶部 TabSwitcher 的闪烁。返回键由 onDismissRequest 处理。
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = overlayOpacity }
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.graphicsLayer {
                        scaleX = dialogScale
                        scaleY = dialogScale
                        alpha = dialogOpacity
                    }
                ) {
                    content(onDismiss)
                }
            }
        }
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
 * 弹框按钮
 */
@Composable
private fun DialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }

    val textColor = when {
        isFocused -> TextFocused
        isPrimary -> Color(0xFF4CAF50)
        else -> TextDefault
    }

    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isPrimary) Color(0x334CAF50) else ItemBgDefault,
            focusedContainerColor = ItemBgFocused
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
        }
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
 * 用户信息区
 */
@Composable
private fun UserInfoSection(
    username: String,
    role: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 当前用户标签
        Text(
            text = "当前用户",
            fontSize = 13.sp,
            color = TextGray
        )

        Spacer(modifier = Modifier.height(6.dp))

        // 用户名 + 角色徽章
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
 * 设置项组容器
 */
@Composable
private fun SettingsGroup(
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content
    )
}

/**
 * 设置项
 * 胶囊形状，固定宽度480dp，聚焦时缩放1.02倍
 */
@Composable
private fun SettingItem(
    label: String,
    value: String,
    focusable: Boolean = true,
    transparentBg: Boolean = false,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 不可聚焦时使用普通 Row
    if (!focusable) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (!transparentBg) Modifier
                        .clip(RoundedCornerShape(40.dp))
                        .background(ItemBgDefault)
                    else Modifier
                )
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = TextDefault
            )
            Text(
                text = value,
                fontSize = 13.sp,
                color = TextGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp)
            )
        }
        return
    }

    var isFocused by remember { mutableStateOf(false) }

    val labelColor = if (isFocused) TextFocused else TextDefault
    val valueColor = if (isFocused) TextFocused else TextGray

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { event ->
                // 拦截左右方向键，阻止焦点移动
                event.key == Key.DirectionLeft || event.key == Key.DirectionRight
            },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(40.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (transparentBg) Color.Transparent else ItemBgDefault,
            focusedContainerColor = ItemBgFocused
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = labelColor
            )
            Text(
                text = value,
                fontSize = 13.sp,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp)
            )
        }
    }
}

/**
 * 设置按钮（如登录按钮）
 * 胶囊形状，固定宽度480dp，聚焦时缩放1.02倍
 */
@Composable
private fun SettingButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val textColor = if (isFocused) TextFocused else TextDefault

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { event ->
                // 拦截左右方向键，阻止焦点移动
                event.key == Key.DirectionLeft || event.key == Key.DirectionRight
            },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(40.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ItemBgDefault,
            focusedContainerColor = ItemBgFocused
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
        }
    }
}

/**
 * 确认弹框内容
 */
@Composable
private fun ConfirmDialogContent(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
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
            text = message,
            fontSize = 14.sp,
            color = TextGray
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
                text = "确定",
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(confirmButtonFocusRequester),
                isPrimary = true
            )
        }
    }
}

/**
 * 缓存清理按钮组
 */
@Composable
private fun CacheButtonsRow(
    onClearDoubanCache: () -> Unit,
    onClearSearchCache: () -> Unit,
    clearDoubanCacheFocusRequester: FocusRequester = remember { FocusRequester() },
    clearSearchCacheFocusRequester: FocusRequester = remember { FocusRequester() }
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CacheButton(
            text = "清空豆瓣缓存",
            modifier = Modifier
                .weight(1f)
                .focusRequester(clearDoubanCacheFocusRequester),
            onClick = onClearDoubanCache
        )
        CacheButton(
            text = "清空搜索缓存",
            modifier = Modifier
                .weight(1f)
                .focusRequester(clearSearchCacheFocusRequester),
            onClick = onClearSearchCache
        )
    }
}

/**
 * 缓存清理按钮
 */
@Composable
private fun CacheButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val textColor = if (isFocused) Color.White else Color(0xFFF39C12)

    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(40.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CacheButtonBg,
            focusedContainerColor = CacheButtonBgFocused
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
        }
    }
}

/**
 * 退出登录按钮
 */
@Composable
private fun LogoutButton(
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val textColor = if (isFocused) Color.White else Color(0xFFE74C3C)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { event ->
                // 拦截左右方向键，阻止焦点移动
                event.key == Key.DirectionLeft || event.key == Key.DirectionRight
            },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(40.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = LogoutButtonBg,
            focusedContainerColor = LogoutButtonBgFocused
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "退出登录",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun SettingsTabPreview() {
    SettingsTab()
}
