package org.moontechlab.selenetv.ui

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

private const val TAG = "TextMeasure"

/**
 * 文本测量请求
 */
data class MeasureRequest(
    val id: String,
    val text: String,
    val onMeasured: (width: Int) -> Unit
)

/**
 * 文本测量上下文
 */
class TextMeasureContext {
    private var currentRequest by mutableStateOf<MeasureRequest?>(null)
    
    /**
     * 请求测量文本宽度（懒测量：只在需要时调用）
     */
    fun measureText(id: String, text: String, onMeasured: (width: Int) -> Unit) {
        Log.d(TAG, "📝 Measure requested: id=$id, text=${text.take(15)}...")
        currentRequest = MeasureRequest(id, text, onMeasured)
    }
    
    /**
     * 清除指定 id 的测量请求（只有当前请求匹配时才清除）
     */
    fun clearMeasure(id: String) {
        if (currentRequest?.id == id) {
            Log.d(TAG, "🧹 Measure cleared: id=$id")
            currentRequest = null
        } else {
            Log.d(TAG, "⏭️ Skip clear: id=$id, current=${currentRequest?.id}")
        }
    }
    
    @Composable
    internal fun MeasureElement() {
        val request = currentRequest
        if (request != null) {
            Text(
                text = request.text,
                color = Color.Transparent,
                fontSize = 11.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    val width = coordinates.size.width
                    Log.d(TAG, "📏 Measured [${request.id}]: ${request.text.take(15)}... = $width px")
                    request.onMeasured(width)
                }
            )
        }
    }
}

val LocalTextMeasure = compositionLocalOf<TextMeasureContext> {
    error("TextMeasureContext not provided. Wrap your content with TextMeasureProvider.")
}

/**
 * 全局文本测量容器
 * 
 * 在页面最外层包裹，提供一个不受宽度限制的隐藏测量区域。
 * 子组件通过 LocalTextMeasure 获取测量能力。
 */
@Composable
fun TextMeasureProvider(
    content: @Composable BoxScope.() -> Unit
) {
    val measureContext = remember { TextMeasureContext() }
    
    CompositionLocalProvider(LocalTextMeasure provides measureContext) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 主内容
            content()
            
            // 全局测量容器 - 不受任何父容器宽度限制
            Box(
                modifier = Modifier
                    // 放在屏幕外，不可见但可测量
                    .fillMaxSize()
            ) {
                measureContext.MeasureElement()
            }
        }
    }
}
