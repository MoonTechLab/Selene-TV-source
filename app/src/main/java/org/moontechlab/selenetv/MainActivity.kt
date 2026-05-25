package org.moontechlab.selenetv

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.screen.AppNavHost
import org.moontechlab.selenetv.server.RemoteControlServer
import org.moontechlab.selenetv.service.AppDataService
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MainActivity"

// TV 设计基准：1080p 分辨率，density = 1.0（即 1dp = 1px）
// 这样在 4K 电视上 1dp = 2px，保持视觉一致性
private const val DESIGN_WIDTH_PX = 1080f

class MainActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(Dispatchers.Main)

    @SuppressLint("GestureBackNavigation", "RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        // 在 super.onCreate 之前调整 density
        adjustDensity()
        super.onCreate(savedInstanceState)
        
        // 启动时自动刷新登录 cookie
        refreshLoginCookieIfNeeded()

        // 设置遥控器按键回调
        RemoteControlServer.setKeyEventCallback { keyCode ->
            mainHandler.post {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    // 返回键使用 onBackPressedDispatcher
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                }
            }
        }

        // 设置文本输入回调
        RemoteControlServer.setTextInputCallback { text ->
            mainHandler.post {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val focusedView = currentFocus
                if (focusedView != null) {
                    val inputConnection = focusedView.onCreateInputConnection(android.view.inputmethod.EditorInfo())
                    inputConnection?.commitText(text, 1)
                }
            }
        }

        setContent {
            AppNavHost()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 解除单例 RemoteControlServer 对本 Activity 的持有（回调 lambda 捕获了 this），避免泄漏。
        // 配置变更重建时不清：新实例的 onCreate 会重新注册，避免清掉新回调。
        if (!isChangingConfigurations) {
            RemoteControlServer.clearCallbacks()
        }
        // 取消未随生命周期取消的协程作用域（在飞的登录刷新会持有 Activity 直到完成）
        activityScope.cancel()
    }

    /**
     * 如果已登录，自动刷新 cookie
     */
    private fun refreshLoginCookieIfNeeded() {
        activityScope.launch {
            if (AppDataService.isLoggedIn() && AppDataService.hasAutoLoginData()) {
                Log.d(TAG, "Auto refreshing login cookie...")
                refreshLoginCookie()
            }
        }
    }
    
    /**
     * 刷新登录 cookie
     */
    private suspend fun refreshLoginCookie() {
        try {
            val serverUrl = AppDataService.getServerUrl() ?: return
            val username = AppDataService.getUsername() ?: return
            val password = AppDataService.getPassword() ?: return
            
            val loginUrl = "$serverUrl/api/login"
            
            val result = withContext(Dispatchers.IO) {
                val url = URL(loginUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val jsonBody = """{"username":"$username","password":"$password"}"""
                connection.outputStream.use { os ->
                    os.write(jsonBody.toByteArray())
                }

                val responseCode = connection.responseCode
                val cookies = parseCookies(connection)
                
                Pair(responseCode, cookies)
            }
            
            if (result.first == 200 && result.second.isNotEmpty()) {
                AppDataService.saveUserData(
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    cookies = result.second
                )
                Log.d(TAG, "Login cookie refreshed successfully")
            } else {
                Log.w(TAG, "Failed to refresh login cookie, response code: ${result.first}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing login cookie: ${e.message}")
        }
    }
    
    /**
     * 解析响应中的 Cookies
     */
    private fun parseCookies(connection: HttpURLConnection): String {
        val cookies = mutableListOf<String>()
        connection.headerFields["Set-Cookie"]?.forEach { cookie ->
            val cookiePart = cookie.split(";").firstOrNull()
            if (cookiePart != null) {
                cookies.add(cookiePart)
            }
        }
        return cookies.joinToString("; ")
    }
    
    /**
     * 调整屏幕密度，确保在不同 TV 设备上保持一致的视觉效果
     * 
     * 原理：以 1920px 宽度为基准，计算出合适的 density
     * - 1080p (1920x1080): density = 1.0, 1dp = 1px
     * - 4K (3840x2160): density = 2.0, 1dp = 2px
     * 
     * 这样无论电视报告什么 density，UI 元素的物理大小都会保持一致
     */
    private fun adjustDensity() {
        val displayMetrics = resources.displayMetrics
        val screenWidthPx = displayMetrics.widthPixels.coerceAtLeast(displayMetrics.heightPixels)
        
        // 计算目标 density：屏幕宽度 / 设计基准宽度
        val targetDensity = screenWidthPx / DESIGN_WIDTH_PX
        val targetScaledDensity = targetDensity * (displayMetrics.scaledDensity / displayMetrics.density)
        val targetDensityDpi = (targetDensity * 160).toInt()
        
        Log.d(TAG, "Screen: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
        Log.d(TAG, "Original density: ${displayMetrics.density}, dpi: ${displayMetrics.densityDpi}")
        Log.d(TAG, "Target density: $targetDensity, dpi: $targetDensityDpi")
        
        // 应用新的 density
        displayMetrics.density = targetDensity
        displayMetrics.scaledDensity = targetScaledDensity
        displayMetrics.densityDpi = targetDensityDpi
        
        // 同时更新 Configuration 中的 densityDpi
        val configuration = resources.configuration
        configuration.densityDpi = targetDensityDpi
        resources.updateConfiguration(configuration, displayMetrics)
    }
}
