package org.moontechlab.selenetv.screen.tab

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
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
    var selectedIp by remember { mutableStateOf(getPreferredIpAddress() ?: "") }

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
        // 加载可用 IP 列表
        availableIps = getAllIpAddresses()
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 顶部留出 TabSwitcher 的空间
        Spacer(modifier = Modifier.height(64.dp))

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

                    // TMDB API Key
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
                        onClearDoubanCache = {
                            coroutineScope.launch {
                                doubanCacheService.clearAll()
                            }
                        },
                        onClearSearchCache = {
                            // TODO: 实现搜索缓存清理
                        },
                        onClearUserDataCache = {
                            // TODO: 实现用户数据缓存清理
                        }
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
        // 返回键处理
        BackHandler(enabled = true) {
            onDismiss()
        }

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

    LaunchedEffect(Unit) {
        delay(100)
        confirmButtonFocusRequester.requestFocus()
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
        Spacer(modifier = Modifier.height(36.dp))

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
                fontSize = 11.sp,
                color = TextGray
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = "若 IP 非本机 IP，可在右侧选项调整",
                fontSize = 10.sp,
                color = TextGray.copy(alpha = 0.7f)
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
            fontSize = 11.sp,
            color = TextGray
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 用户名 + 角色徽章
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = username,
                fontSize = 18.sp,
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
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = badgeText,
            fontSize = 11.sp,
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
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextDefault
            )
            Text(
                text = value,
                fontSize = 11.sp,
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
            .onFocusChanged { isFocused = it.isFocused },
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
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = labelColor
            )
            Text(
                text = value,
                fontSize = 11.sp,
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
            .onFocusChanged { isFocused = it.isFocused },
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
                .padding(horizontal = 18.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = textColor
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
    onClearUserDataCache: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CacheButton(
            text = "清空豆瓣缓存",
            modifier = Modifier.weight(1f),
            onClick = onClearDoubanCache
        )
        CacheButton(
            text = "清空搜索缓存",
            modifier = Modifier.weight(1f),
            onClick = onClearSearchCache
        )
        CacheButton(
            text = "清空用户数据缓存",
            modifier = Modifier.weight(1f),
            onClick = onClearUserDataCache
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
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CacheButtonBg,
            focusedContainerColor = CacheButtonBgFocused
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 12.sp,
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
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(40.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = LogoutButtonBg,
            focusedContainerColor = LogoutButtonBgFocused
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "退出登录",
                fontSize = 13.sp,
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
