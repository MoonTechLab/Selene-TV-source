package org.moontechlab.selenetv.screen.tab

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
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
 * 获取设备所有 IPv4 地址，优先返回 192.168.x.x 网段
 */
private fun getPreferredIpAddress(): String? {
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
    
    // 优先选择 192.168.x.x 网段
    return allIps.find { it.startsWith("192.168.") } ?: allIps.firstOrNull()
}

@Composable
fun SettingsTab(
    focusRequester: FocusRequester = remember { FocusRequester() },
    scrollState: ScrollState = rememberScrollState()
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
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        // 顶部留出 TabSwitcher 的空间
        Spacer(modifier = Modifier.height(78.dp))

        // 主内容区域 - 两栏布局（各占50%）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp)
        ) {
            // 左侧二维码区域 - 占50%
            RemoteControlQrCode(
                modifier = Modifier
                    .weight(1f)
            )

            // 右侧设置区域 - 占50%
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                // 用户信息区（已登录时显示）
                if (hasLogin) {
                    UserInfoSection(username = username, role = userRole)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 设置项组
                SettingsGroup {
                    // 登录实例（已登录时显示，仅显示不可点击）
                    if (hasLogin) {
                        SettingItem(
                            label = "登录实例",
                            value = serverUrl.ifEmpty { "未设置" },
                            focusable = false
                        )
                    }

                    // 登录按钮（未登录时显示）
                    if (!hasLogin) {
                        SettingButton(
                            text = "登录",
                            onClick = { /* TODO: 实现登录逻辑 */ },
                            modifier = Modifier.focusRequester(focusRequester)
                        )
                    }

                    // 调整遥控二维码 IP
                    SettingItem(
                        label = "调整遥控二维码 IP",
                        value = "自动",
                        onClick = { /* TODO: 实现编辑逻辑 */ },
                        modifier = if (hasLogin) Modifier.focusRequester(focusRequester) else Modifier
                    )

                    // 豆瓣数据源
                    SettingItem(
                        label = "豆瓣数据源",
                        value = doubanDataSource.ifEmpty { "直连" },
                        onClick = { /* TODO: 实现选择逻辑 */ }
                    )

                    // 豆瓣图片源
                    SettingItem(
                        label = "豆瓣图片源",
                        value = doubanImageSource.ifEmpty { "直连" },
                        onClick = { /* TODO: 实现选择逻辑 */ }
                    )

                    // TMDB API Key
                    SettingItem(
                        label = "TMDB API Key",
                        value = if (tmdbApiKey.isEmpty()) "未设置" else "已设置",
                        onClick = { /* TODO: 实现编辑逻辑 */ }
                    )

                    // M3U8 代理
                    SettingItem(
                        label = "M3U8 代理",
                        value = m3u8ProxyUrl.ifEmpty { "未设置" },
                        onClick = { /* TODO: 实现编辑逻辑 */ }
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
                            coroutineScope.launch {
                                AppDataService.clearUserData()
                                hasLogin = false
                                username = ""
                                serverUrl = ""
                                userRole = "user"
                            }
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
}

/**
 * 遥控器二维码组件
 */
@Composable
private fun RemoteControlQrCode(modifier: Modifier = Modifier) {
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var remoteUrl by remember { mutableStateOf("") }
    
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val ip = getPreferredIpAddress()
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
        verticalArrangement = Arrangement.Center
    ) {
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
                text = "或浏览器打开：$remoteUrl",
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
        "admin" -> RoleBadgeOwner to "站长"
        "manager" -> RoleBadgeAdmin to "管理"
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
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val labelColor = if (isFocused) TextFocused else TextDefault
    val valueColor = if (isFocused) TextFocused else TextGray

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .then(if (!focusable) Modifier.focusable(false) else Modifier),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(40.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ItemBgDefault,
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
