package org.moontechlab.selenetv.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.moontechlab.selenetv.server.RemoteControlServer
import org.moontechlab.selenetv.service.AppDataService
import qrcode.QRCode
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL

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

/**
 * 登录屏幕 - 全屏页面，带动画效果
 */
@Composable
fun LoginScreen(
    visible: Boolean,
    initialServerUrl: String = "",
    initialUsername: String = "",
    onDismiss: () -> Unit
) {
    // 控制动画状态
    var showScreen by remember { mutableStateOf(false) }
    var animateIn by remember { mutableStateOf(false) }

    // 处理显示/隐藏逻辑
    LaunchedEffect(visible) {
        if (visible) {
            showScreen = true
            delay(16) // 等待一帧让内容渲染
            animateIn = true
        } else {
            animateIn = false
            delay(150) // 等待关闭动画完成
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

@Composable
private fun LoginScreenWithAnimation(
    initialServerUrl: String,
    initialUsername: String,
    animateIn: Boolean,
    onDismiss: () -> Unit
) {
    // 返回键处理
    BackHandler(enabled = true) {
        onDismiss()
    }

    // 动画参数 - 展示: 更快更少回弹; 隐藏: 快速收起
    val showSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 400f)
    val hideSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 500f)

    // 内容缩放动画: 0.8 <-> 1.0
    val contentScale by animateFloatAsState(
        targetValue = if (animateIn) 1f else 0.8f,
        animationSpec = if (animateIn) showSpring else hideSpring,
        label = "content_scale"
    )

    // 内容透明度动画: 0 <-> 1
    val contentOpacity by animateFloatAsState(
        targetValue = if (animateIn) 1f else 0f,
        animationSpec = if (animateIn) showSpring else hideSpring,
        label = "content_opacity"
    )

    // 背景透明度动画: 0 <-> 1
    val bgOpacity by animateFloatAsState(
        targetValue = if (animateIn) 1f else 0f,
        animationSpec = if (animateIn) showSpring else hideSpring,
        label = "bg_opacity"
    )

    // 与 HomeScreen 一致的渐变背景
    val gradientBrush = Brush.verticalGradient(
        0.0f to Color(0xFF0a0a0a),
        0.4f to Color(0xFF1a1a1a),
        0.7f to Color(0xFF0f0f0f),
        1.0f to Color(0xFF050505)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = bgOpacity }
            .background(brush = gradientBrush),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = contentScale
                scaleY = contentScale
                alpha = contentOpacity
            }
        ) {
            LoginScreenContent(
                initialServerUrl = initialServerUrl,
                initialUsername = initialUsername,
                onDismiss = onDismiss
            )
        }
    }
}


@Composable
private fun LoginScreenContent(
    initialServerUrl: String,
    initialUsername: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 表单状态
    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    // Toast 显示函数
    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // FocusRequester
    val serverUrlFocusRequester = remember { FocusRequester() }
    val usernameFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val loginButtonFocusRequester = remember { FocusRequester() }

    // 初始聚焦
    LaunchedEffect(Unit) {
        delay(100)
        serverUrlFocusRequester.requestFocus()
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
                        onDismiss()
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
                .fillMaxHeight()
        )

        // 右侧表单区域
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            // 标题
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
                onNext = { usernameFocusRequester.requestFocus() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 用户名
            LoginTextField(
                label = "用户名",
                value = username,
                onValueChange = { username = it },
                placeholder = "请输入用户名",
                focusRequester = usernameFocusRequester,
                onNext = { passwordFocusRequester.requestFocus() }
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
                onNext = { loginButtonFocusRequester.requestFocus() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 登录按钮
            LoginButton(
                text = if (isLoading) "登录中..." else "登录",
                enabled = !isLoading,
                onClick = { handleLogin() },
                modifier = Modifier.focusRequester(loginButtonFocusRequester)
            )
        }
    }
}


/**
 * 登录表单输入框
 */
@Composable
private fun LoginTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false,
    focusRequester: FocusRequester,
    onNext: () -> Unit = {}
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
                .onFocusChanged { isFocused = it.isFocused }
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
    modifier: Modifier = Modifier
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
    modifier: Modifier = Modifier
) {
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // 生成二维码
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val ips = getAllIpAddresses()
                val port = RemoteControlServer.getPort()
                val token = AppDataService.getSessionToken()

                // 构建 JSON
                val json = JSONObject().apply {
                    put("ips", JSONArray(ips))
                    put("port", port)
                    put("token", token)
                }

                val qrContent = json.toString()
                val qrBytes = QRCode.ofSquares()
                    .build(qrContent)
                    .renderToBytes()
                qrBitmap = BitmapFactory.decodeByteArray(qrBytes, 0, qrBytes.size)
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
                contentDescription = "登录二维码",
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

        Text(
            text = "使用 Selene 移动 App 扫描二维码登录",
            fontSize = 11.sp,
            color = TextGray
        )

        Spacer(modifier = Modifier.height(6.dp))
            
        Text(
            text = "Selene Mobile Ver. ≥ v1.7.0",
            fontSize = 10.sp,
            color = TextGray.copy(alpha = 0.7f)
        )
    }
}
