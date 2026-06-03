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

/**
 * 弹窗判定门：把「host 只响应自身挂载之后的新触发」这条规则抽成纯逻辑，便于单测。
 *
 * [MoonToast] 是进程级单例，[MoonToast.trigger] 在进程存活期间一直保留。返回键退出 MainActivity 后
 * 进程常被系统缓存，再次启动时 Activity 重建、Compose 树重组，但 trigger 仍是上一次操作的非零值——
 * 若直接以 trigger!=0 判定就会把上一条旧消息（「豆瓣缓存已清空」等）重弹一次（见 issue #4）。
 * 本门以「挂载时已存在的 trigger」为基线，仅当 trigger 越过基线递增时才放行。
 */
class MoonToastGate(initialTrigger: Int) {
    private var lastSeen = initialTrigger

    /** trigger 相对上次未变化（含挂载时的初始值）→ 不弹；否则记录并放行一次。 */
    fun shouldShow(trigger: Int): Boolean {
        if (trigger == lastSeen) return false
        lastSeen = trigger
        return true
    }
}

private const val TOAST_DURATION_MS = 2200L

/** 全局 Toast 宿主：在 AppNavHost 顶层渲染一次即可，覆盖所有页面。 */
@Composable
fun MoonToastHost(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    val trigger = MoonToast.trigger
    // 以挂载时已有的 trigger 为基线，仅响应其后的新触发：避免进程被缓存、Activity 重建后重弹旧消息。
    val gate = remember { MoonToastGate(trigger) }
    LaunchedEffect(trigger) {
        if (!gate.shouldShow(trigger)) return@LaunchedEffect
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
