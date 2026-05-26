package org.moontechlab.selenetv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * 全局自绘 Toast，替代系统 [android.widget.Toast]：风格与播放页的 BackExitHint 一致（深色圆角胶囊 +
 * 白字），确保 10 尺观看下清晰、且不受系统 Toast 在 TV 上位置/样式不可控的影响。
 *
 * 用法：任意位置（含非 Composable 的事件回调）直接调用 [MoonToast.show]；UI 由 AppNavHost 顶层唯一的
 * [MoonToastHost] 渲染在所有页面/覆盖层之上。
 */
object MoonToast {
    internal var message by mutableStateOf("")
        private set

    // 自增触发器：即便相邻两次消息文本相同，也能重新计时并再次弹出。
    internal var trigger by mutableIntStateOf(0)
        private set

    fun show(message: String) {
        this.message = message
        trigger++
    }
}

private const val TOAST_DURATION_MS = 2200L

/** 全局 Toast 宿主：在 AppNavHost 顶层渲染一次即可，覆盖所有页面。 */
@Composable
fun MoonToastHost(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    val trigger = MoonToast.trigger
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect // 启动时未触发过，不弹
        visible = true
        delay(TOAST_DURATION_MS)
        visible = false
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(180), label = "moon_toast")
    if (alpha <= 0f) return
    Box(modifier = modifier.graphicsLayer { this.alpha = alpha }) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xE6000000))
                .padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Text(text = MoonToast.message, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.W600)
        }
    }
}
