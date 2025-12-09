package org.moontechlab.selenetv

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.screen.HomeScreen
import org.moontechlab.selenetv.server.RemoteControlServer
import org.moontechlab.selenetv.service.AppDataService
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
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
            HomeScreen()
        }
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
}