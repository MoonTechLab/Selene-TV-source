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
import org.moontechlab.selenetv.service.AuthHelper

private const val TAG = "MainActivity"

/**
 * 网络遥控文本输入的「活动接收器」。
 * 独立窗口（如设置里的输入弹框走 androidx.compose.ui.window.Dialog）里的输入框无法被
 * Activity.currentFocus 命中，commitText 送不达。这类输入框在展示期间注册 sink，网络遥控文本
 * 优先写入它；主窗口输入框（如登录页）不注册，sink 为 null，仍走 currentFocus + InputConnection。
 */
object RemoteTextSink {
    var sink: ((String) -> Unit)? = null
}

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

        // Compose 内容铺满整屏并绘制不透明渐变背景 → DecorView 的窗口背景被完全遮挡，是每屏一层
        // 白白浪费的全屏过度绘制（顶部标签栏等处实测达 4×）。运行期置空窗口背景 → 全 App 各屏 -1×。
        // 启动那一帧仍由主题里的深色 windowBackground 兜底，避免白闪。
        window.setBackgroundDrawable(null)

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
                // 独立窗口输入框（如设置输入弹框）注册了 sink → 优先写入；否则走主窗口焦点视图。
                val sink = RemoteTextSink.sink
                if (sink != null) {
                    sink(text)
                } else {
                    val focusedView = currentFocus
                    if (focusedView != null) {
                        val inputConnection = focusedView.onCreateInputConnection(android.view.inputmethod.EditorInfo())
                        inputConnection?.commitText(text, 1)
                    }
                }
            }
        }

        setContent {
            AppNavHost()
        }
    }

    // 返回键完全自管（修复真机播放页返回失效，同时杜绝重复分发）：
    // 历史上播放页覆盖层会吞掉 BACK 的 ACTION_DOWN，致使 Activity 未 startTracking、抬起时系统不触发
    // onBackPressed，BackHandler 收不到返回。早期改为「super 未处理(返回 false)则补发 onBackPressed」，
    // 但部分平台(实测 API 36)super.dispatchKeyEvent 对 BACK-UP 其实已经走 onBackPressedDispatcher
    // 触发了 BackHandler，却仍返回 false → 补发再触发一次，单次物理返回被分发两次。
    // 由于 Compose MutableState 是同步写入，第二次分发会读到第一次刚置位的状态(如 backExitArmed=true)
    // 立即执行下一步，把直播/点播播放页「按两次才退出」的二次确认压成一次。
    // 故此处对 BACK 一律自管：吞掉 DOWN，抬起时只触发一次 onBackPressed；绝不交给 super 的返回处理，
    // 从根上避免「触发 + 补发」的双分发。BackHandler 仍由 onBackPressedDispatcher 正常调度；
    // IME 弹出时 BACK 由输入法窗口先行消费、根本不会进到这里，故不影响收起键盘。
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                onBackPressedDispatcher.onBackPressed()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
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
        if (AppDataService.isLocalModeSync()) return  // 本地模式与服务端账号互斥
        activityScope.launch {
            if (AppDataService.isLoggedIn() && AppDataService.hasAutoLoginData()) {
                Log.d(TAG, "Auto refreshing login cookie...")
                refreshLoginCookie()
            }
        }
    }
    
    /**
     * 刷新登录 cookie。
     * POST /api/login 由 [AuthHelper] 接管，会手动跟随 3xx 重定向（含跨 http/https），
     * 重定向后的真实 baseUrl 会被回写到 [AppDataService]，下次起所有请求直接打到真实地址。
     */
    private suspend fun refreshLoginCookie() {
        try {
            val serverUrl = AppDataService.getServerUrl() ?: return
            val username = AppDataService.getUsername() ?: return
            val password = AppDataService.getPassword() ?: return

            val result = withContext(Dispatchers.IO) {
                AuthHelper.postLogin(serverUrl, username, password)
            }

            if (result.responseCode == 200 && result.cookies.isNotEmpty()) {
                AppDataService.saveUserData(
                    serverUrl = result.baseUrl,
                    username = username,
                    password = password,
                    cookies = result.cookies,
                )
                Log.d(TAG, "Login cookie refreshed successfully (baseUrl=${result.baseUrl})")
            } else {
                Log.w(TAG, "Failed to refresh login cookie, response code: ${result.responseCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing login cookie: ${e.message}")
        }
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
