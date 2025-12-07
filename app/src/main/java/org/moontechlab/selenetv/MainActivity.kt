package org.moontechlab.selenetv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.moontechlab.selenetv.screen.HomeScreen
import org.moontechlab.selenetv.server.RemoteControlServer

class MainActivity : ComponentActivity() {
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
        
        setContent {
            HomeScreen()
        }
    }
}