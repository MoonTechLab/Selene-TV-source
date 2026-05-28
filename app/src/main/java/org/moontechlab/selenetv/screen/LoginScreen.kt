package org.moontechlab.selenetv.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject
import org.moontechlab.selenetv.server.RemoteControlServer
import org.moontechlab.selenetv.service.AppDataService
import qrcode.QRCode
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import android.util.Log
import org.moontechlab.selenetv.service.SubscriptionService
import org.moontechlab.selenetv.ui.AnimatedSelectDialog
import org.moontechlab.selenetv.ui.ConfirmDialogContent

private const val TAG = "LoginScreen"

// ==================== 颜色常量 ====================
private val ItemBgDefault = Color(0x14FFFFFF)
private val ItemBgFocused = Color.White
private val TextDefault = Color.White
private val TextFocused = Color(0xFF0A0A0A)
private val TextGray = Color(0xFF888888)
private val ErrorColor = Color(0xFFE74C3C)
private val SuccessColor = Color(0xFF2ECC71)
private val LoginButtonBg = Color(0x334CAF50)
private val LoginButtonBgFocused = Color(0xFF4CAF50)

@Composable
fun LoginScreen(navController: androidx.navigation.NavController) {
    // 登录页若是返回栈根（启动即未登录的强制登录场景）则视为门禁；从设置进入则非门禁
    val isGate = remember { navController.previousBackStackEntry == null }

    // 离开登录页：门禁场景登录成功后进首页（并弹出登录页使首页成为根），
    // 非门禁场景返回上一页（保留存活的首页实例与其状态）
    fun leave() {
        if (isGate) {
            navController.navigate(HomeRoute) {
                popUpTo(LoginRoute) { inclusive = true }
            }
        } else {
            navController.popBackStack()
        }
    }

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
        LoginScreenContent(isGate = isGate, onLeave = { leave() })
    }
}


@Composable
private fun LoginScreenContent(
    isGate: Boolean,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val activity = context as? android.app.Activity

    // ============ 共享状态 ============
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    // ============ 本地模式状态 ============
    var isLocalMode by remember { mutableStateOf(false) }
    var subscriptionUrl by remember { mutableStateOf("") }
    var pendingParsed by remember { mutableStateOf<SubscriptionService.ParsedSubscription?>(null) }
    var pendingUrl by remember { mutableStateOf("") }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var fetchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    LaunchedEffect(Unit) {
        serverUrl = AppDataService.getServerUrl() ?: ""
        username = AppDataService.getUsername() ?: ""
    }

    // Toast 显示函数（统一走自绘 MoonToast）
    fun showToast(message: String) {
        org.moontechlab.selenetv.ui.MoonToast.show(message)
    }

    // FocusRequester
    val serverUrlFocusRequester = remember { FocusRequester() }
    val usernameFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val loginButtonFocusRequester = remember { FocusRequester() }
    val subscriptionUrlFocusRequester = remember { FocusRequester() }
    val localLoginButtonFocusRequester = remember { FocusRequester() }
    // 「品牌点」隐秘焦点：登录按钮 down 进入；长按 OK 3 秒触发隐藏模式
    val brandDotFocusRequester = remember { FocusRequester() }

    // 初始聚焦 + 订阅链接预填（响应 isLocalMode 切换）
    LaunchedEffect(isLocalMode) {
        delay(100)
        if (isLocalMode) {
            subscriptionUrl = withContext(Dispatchers.IO) {
                org.moontechlab.selenetv.data.local.LocalStore.getSubscriptionUrl()
            }.orEmpty()
            subscriptionUrlFocusRequester.requestFocus()
        } else {
            serverUrlFocusRequester.requestFocus()
        }
    }

    BackHandler {
        when {
            isLocalMode -> {
                fetchJob?.cancel()
                fetchJob = null
                isLocalMode = false
                isLoading = false
            }
            isGate -> activity?.finish()
            else -> onLeave()
        }
    }

    // 表单验证
    fun isFormValid(): Boolean {
        return serverUrl.trim().isNotEmpty() &&
                username.trim().isNotEmpty() &&
                password.trim().isNotEmpty()
    }

    // 处理 URL
    fun processUrl(url: String): String {
        var processed = url.trim()
        // 移除末尾斜杠
        while (processed.endsWith("/")) {
            processed = processed.dropLast(1)
        }
        // 添加协议
        if (!processed.startsWith("http://") && !processed.startsWith("https://")) {
            processed = "http://$processed"
        }
        return processed
    }

    // 解析 Cookies
    fun parseCookies(connection: HttpURLConnection): String {
        val cookies = mutableListOf<String>()
        connection.headerFields["Set-Cookie"]?.forEach { cookie ->
            // 只取 cookie 的 name=value 部分
            val cookiePart = cookie.split(";").firstOrNull()
            if (cookiePart != null) {
                cookies.add(cookiePart)
            }
        }
        return cookies.joinToString("; ")
    }

    // 登录处理
    fun handleLogin() {
        if (!isFormValid()) {
            showToast("请填写完整的登录信息")
            return
        }

        isLoading = true

        coroutineScope.launch {
            try {
                val baseUrl = processUrl(serverUrl)
                val loginUrl = "$baseUrl/api/login"

                val result = withContext(Dispatchers.IO) {
                    val url = URL(loginUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000

                    val jsonBody = """{"username":"${username.trim()}","password":"${password.trim()}"}"""
                    connection.outputStream.use { os ->
                        os.write(jsonBody.toByteArray())
                    }

                    val responseCode = connection.responseCode
                    val cookies = parseCookies(connection)

                    Triple(responseCode, cookies, baseUrl)
                }

                isLoading = false

                when (result.first) {
                    200 -> {
                        val cookies = result.second
                        val baseUrl = result.third

                        // 保存用户数据
                        AppDataService.saveUserData(
                            serverUrl = baseUrl,
                            username = username.trim(),
                            password = password.trim(),
                            cookies = cookies
                        )

                        // 直接关闭
                        onLeave()
                    }
                    401 -> {
                        showToast("用户名或密码错误")
                    }
                    500 -> {
                        showToast("服务器错误")
                    }
                    else -> {
                        showToast("网络异常 (${result.first})")
                    }
                }
            } catch (e: Exception) {
                isLoading = false
                showToast("网络异常: ${e.message ?: "未知错误"}")
            }
        }
    }

    fun commitLocalMode(parsed: SubscriptionService.ParsedSubscription, url: String, clearAll: Boolean) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                if (clearAll) org.moontechlab.selenetv.data.local.LocalStore.clearAll()
                org.moontechlab.selenetv.data.local.LocalStore.saveSubscriptionUrl(url)
                org.moontechlab.selenetv.data.local.LocalStore.saveSubscriptionSources(
                    parsed.searchResources,
                    parsed.liveSources,
                )
                AppDataService.saveIsLocalMode(true)
            }
            onLeave()
        }
    }

    fun handleLocalModeLogin() {
        val url = subscriptionUrl.trim()
        if (url.isEmpty()) {
            showToast("请输入订阅链接")
            return
        }

        isLoading = true
        fetchJob = coroutineScope.launch {
            val parsed = try {
                withContext(Dispatchers.IO) { SubscriptionService.fetchAndParse(url) }
            } catch (e: SubscriptionService.SubscriptionException) {
                isLoading = false
                showToast(e.message ?: "订阅校验失败")
                return@launch
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程被取消（用户长按品牌点切表单/按 BACK）：静默退出，不弹「网络异常」toast
                throw e
            } catch (e: Exception) {
                isLoading = false
                showToast("网络异常: ${e.message ?: "未知错误"}")
                return@launch
            }
            isLoading = false

            val existing = withContext(Dispatchers.IO) {
                org.moontechlab.selenetv.data.local.LocalStore.getSubscriptionUrl()
            }
            if (!existing.isNullOrBlank() && existing != url) {
                pendingParsed = parsed
                pendingUrl = url
                showClearConfirmDialog = true
            } else {
                commitLocalMode(parsed, url, clearAll = false)
            }
        }
    }

    // 主布局
    Row(
        modifier = Modifier
            .width(900.dp)
            .padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // 左侧二维码区域
        LoginQrCodeSection(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            brandDotFocusRequester = brandDotFocusRequester,
            // 上键目标随当前表单切换：账号表单 → 登录按钮；本地模式 → 进入本地模式按钮
            brandDotUpFocusRequester = if (isLocalMode) localLoginButtonFocusRequester else loginButtonFocusRequester,
            onSecretActivate = {
                // 切换表单前先取消任何在飞的订阅校验，避免用户取消后仍被提交到 home
                fetchJob?.cancel()
                fetchJob = null
                isLocalMode = !isLocalMode
                isLoading = false  // 重置加载态：避免切表单后看到对方的「登录中...」/「校验中...」
                org.moontechlab.selenetv.ui.MoonToast.show(
                    if (isLocalMode) "已进入本地模式" else "已切回账号登录"
                )
            }
        )

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
                // 账号登录表单
                Text(
                    text = "账号登录",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDefault
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 服务器地址
                LoginTextField(
                    label = "服务器地址",
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    placeholder = "例如: 192.168.1.100:3000",
                    focusRequester = serverUrlFocusRequester,
                    downFocusRequester = usernameFocusRequester
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 用户名
                LoginTextField(
                    label = "用户名",
                    value = username,
                    onValueChange = { username = it },
                    placeholder = "请输入用户名",
                    focusRequester = usernameFocusRequester,
                    upFocusRequester = serverUrlFocusRequester,
                    downFocusRequester = passwordFocusRequester
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 密码
                LoginTextField(
                    label = "密码",
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "请输入密码",
                    isPassword = true,
                    focusRequester = passwordFocusRequester,
                    upFocusRequester = usernameFocusRequester,
                    downFocusRequester = loginButtonFocusRequester
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 登录按钮
                LoginButton(
                    text = if (isLoading) "登录中..." else "登录",
                    enabled = !isLoading,
                    onClick = { handleLogin() },
                    modifier = Modifier.focusRequester(loginButtonFocusRequester),
                    upFocusRequester = passwordFocusRequester,
                    // 暗门：登录按钮 ← 跳到左下角的品牌点（按钮在右、点在左，方向语义匹配）
                    leftFocusRequester = brandDotFocusRequester
                )
            }
        }
    }

    AnimatedSelectDialog(
        visible = showClearConfirmDialog,
        onDismiss = {
            // 与 ConfirmDialogContent 的「取消」语义一致：保留本地数据，仍保存新订阅（clearAll=false）
            val p = pendingParsed
            val u = pendingUrl
            showClearConfirmDialog = false
            if (p != null) commitLocalMode(p, u, clearAll = false)
        },
    ) { onClose ->
        ConfirmDialogContent(
            title = "已存在不同的订阅",
            message = "检测到已有本地模式内容且订阅链接不一致，是否清空全部本地模式存储？",
            confirmText = "清空并保存",
            confirmDestructive = true,
            focusConfirm = false,
            onConfirm = {
                val p = pendingParsed
                val u = pendingUrl
                onClose()
                if (p != null) commitLocalMode(p, u, clearAll = true)
            },
            onDismiss = {
                val p = pendingParsed
                val u = pendingUrl
                onClose()
                if (p != null) commitLocalMode(p, u, clearAll = false)
            },
        )
    }
}


/**
 * 登录表单输入框 - 使用 focusProperties 控制焦点导航
 */
@Composable
private fun LoginTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    Column {
        Text(
            text = label,
            fontSize = 12.sp,
            color = TextGray,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .focusProperties {
                    // 指定上下导航的目标
                    if (upFocusRequester != null) up = upFocusRequester
                    if (downFocusRequester != null) down = downFocusRequester
                    // 禁用左右方向键导航
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                }
                .onFocusChanged { focusState ->
                    Log.d(TAG, "[$label] TextField onFocusChanged: isFocused=${focusState.isFocused}")
                    isFocused = focusState.isFocused
                }
                .clip(RoundedCornerShape(12.dp))
                .background(if (isFocused) Color(0xFF3A3A3A) else Color(0xFF2A2A2A))
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) Color(0xFF4CAF50) else Color(0xFF444444),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            textStyle = TextStyle(
                color = TextDefault,
                fontSize = 14.sp
            ),
            cursorBrush = SolidColor(Color.White),
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = TextGray.copy(alpha = 0.6f),
                            fontSize = 14.sp
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

/**
 * 登录按钮
 */
@Composable
private fun LoginButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    val textColor = when {
        !enabled -> TextGray
        isFocused -> Color.White
        else -> Color(0xFF4CAF50)
    }

    Surface(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .focusProperties {
                if (upFocusRequester != null) up = upFocusRequester
                if (downFocusRequester != null) down = downFocusRequester
                // 暗门：登录按钮 left → 左下角的品牌点（相对布局上确实在左边）
                if (leftFocusRequester != null) left = leftFocusRequester
                else left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (enabled) LoginButtonBg else Color(0x22FFFFFF),
            focusedContainerColor = if (enabled) LoginButtonBgFocused else Color(0x33FFFFFF)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
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
 * 登录二维码区域
 */
@Composable
private fun LoginQrCodeSection(
    modifier: Modifier = Modifier,
    brandDotFocusRequester: FocusRequester? = null,
    brandDotUpFocusRequester: FocusRequester? = null,
    onSecretActivate: (() -> Unit)? = null
) {
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var remoteUrl by remember { mutableStateOf("") }

    // 与设置页 Web 遥控器一致：二维码内容为 http://IP:端口/remote_control。
    // 手机同局域网用浏览器扫码/打开即可远程往登录框输入（D-pad 打字繁琐，引导走 Web 遥控）。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val ips = getAllIpAddresses()
                val ip = ips.find { it.startsWith("192.168.") } ?: ips.firstOrNull()
                val port = RemoteControlServer.getPort()
                if (ip != null) {
                    val url = "http://$ip:$port/remote_control"
                    remoteUrl = url
                    val qrBytes = QRCode.ofSquares()
                        .build(url)
                        .renderToBytes()
                    qrBitmap = BitmapFactory.decodeByteArray(qrBytes, 0, qrBytes.size)
                }
            } catch (_: Exception) {
            }
        }
    }

    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 标题
        Text(
            text = "扫码登录",
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
                color = TextGray,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        VersionColophon(
            dotFocusRequester = brandDotFocusRequester,
            dotUpFocusRequester = brandDotUpFocusRequester,
            onSecretActivate = onSecretActivate
        )
    }
}

/**
 * 版本落款
 * 与设置页左栏同一视觉：细分隔线 + 绿色品牌点 + 字距拉开的字标 + 绿色版本号。
 * 版本号取自 PackageManager（不依赖 BuildConfig）。
 */
@Composable
private fun VersionColophon(
    modifier: Modifier = Modifier,
    dotFocusRequester: FocusRequester? = null,
    dotUpFocusRequester: FocusRequester? = null,
    onSecretActivate: (() -> Unit)? = null
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
            // 绿色品牌点（唯一点缀色，同时是隐藏模式的暗门）
            BrandDotTrigger(
                focusRequester = dotFocusRequester,
                upFocusRequester = dotUpFocusRequester,
                onActivate = onSecretActivate
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
 * 品牌点 · 隐藏模式暗门。
 *
 * 焦点路径：登录按钮 down → 此处；up → 登录按钮。
 * 视觉：仅在聚焦时缩放到 1.2x（约 6dp）作为微弱提示；按住 OK 在 3 秒内
 * 平滑增长到 2.2x（约 11dp），到达即触发 [onActivate]、短暂闪烁到 2.8x 后回弹；
 * 中途松手则回到聚焦态 1.2x。缩放走 graphicsLayer，不影响 Row 的实际布局。
 *
 * 当未传入 [focusRequester] 或 [onActivate] 时退化成纯展示用 5dp 静态圆点，
 * 保持与历史调用点兼容。
 */
@Composable
private fun BrandDotTrigger(
    focusRequester: FocusRequester?,
    upFocusRequester: FocusRequester?,
    onActivate: (() -> Unit)?
) {
    if (focusRequester == null || onActivate == null) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(Color(0xFF4CAF50))
        )
        return
    }

    val scope = rememberCoroutineScope()
    var isFocused by remember { mutableStateOf(false) }
    var keyDown by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    val scale = remember { Animatable(1f) }

    Box(
        modifier = Modifier
            .size(5.dp)
            .focusRequester(focusRequester)
            .focusable()
            .focusProperties {
                // 退出路径走相对布局：← 进来，→ 出去（同时保留 ↑ 作为冗余出口）
                if (upFocusRequester != null) {
                    up = upFocusRequester
                    right = upFocusRequester
                } else {
                    right = FocusRequester.Cancel
                }
                down = FocusRequester.Cancel
                left = FocusRequester.Cancel
            }
            .onFocusChanged { state ->
                isFocused = state.isFocused
                holdJob?.cancel()
                holdJob = null
                keyDown = false
                scope.launch {
                    scale.animateTo(if (state.isFocused) 1.2f else 1f, tween(180))
                }
            }
            .onKeyEvent { event ->
                if (event.key != Key.DirectionCenter &&
                    event.key != Key.Enter &&
                    event.key != Key.NumPadEnter
                ) return@onKeyEvent false

                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (!keyDown) {
                            keyDown = true
                            holdJob = scope.launch {
                                try {
                                    scale.animateTo(2.2f, tween(3000))
                                    // 3 秒整满 → 激活
                                    scale.animateTo(2.8f, tween(140))
                                    onActivate()
                                    scale.animateTo(
                                        if (isFocused) 1.2f else 1f,
                                        tween(280)
                                    )
                                } catch (_: kotlinx.coroutines.CancellationException) {
                                    // 中途松手或失焦 → 由对应处理器收尾
                                }
                            }
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (keyDown) {
                            keyDown = false
                            val wasHolding = holdJob?.isActive == true
                            holdJob?.cancel()
                            holdJob = null
                            if (wasHolding) {
                                scope.launch {
                                    scale.animateTo(
                                        if (isFocused) 1.2f else 1f,
                                        tween(220)
                                    )
                                }
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(CircleShape)
            .background(Color(0xFF4CAF50))
    )
}
