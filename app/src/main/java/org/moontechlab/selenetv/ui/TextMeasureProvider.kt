package org.moontechlab.selenetv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * 文本测量请求
 */
data class MeasureRequest(
    val id: String,
    val text: String,
    val fontSize: Float,  // sp 值
    val onMeasured: (width: Int) -> Unit
)

/**
 * 文本测量上下文
 * 
 * 使用队列机制处理测量请求，避免快速切换焦点时请求被覆盖
 */
class TextMeasureContext {
    // 待处理的测量请求队列
    private var pendingRequests = mutableStateMapOf<String, MeasureRequest>()
    
    // 当前正在测量的请求
    private var currentMeasuring by mutableStateOf<MeasureRequest?>(null)
    
    /**
     * 请求测量文本宽度
     * 如果已有相同 id 的请求，会更新回调
     * @param fontSize 字体大小（sp 值）
     */
    fun measureText(id: String, text: String, fontSize: Float = 14f, onMeasured: (width: Int) -> Unit) {
        pendingRequests[id] = MeasureRequest(id, text, fontSize, onMeasured)
        // 如果当前没有正在测量的请求，立即开始
        if (currentMeasuring == null) {
            processNextRequest()
        }
    }
    
    /**
     * 清除指定 id 的测量请求
     */
    fun clearMeasure(id: String) {
        pendingRequests.remove(id)
    }
    
    /**
     * 处理下一个待测量请求
     */
    private fun processNextRequest() {
        currentMeasuring = pendingRequests.values.firstOrNull()
    }
    
    /**
     * 完成当前测量，处理下一个
     */
    private fun onMeasureComplete(id: String, width: Int) {
        val request = pendingRequests.remove(id)
        request?.onMeasured?.invoke(width)
        currentMeasuring = null
        processNextRequest()
    }
    
    @Composable
    internal fun MeasureElement() {
        val request = currentMeasuring
        if (request != null) {
            key(request.id, request.text, request.fontSize) {
                Text(
                    text = request.text,
                    color = Color.Transparent,
                    fontSize = request.fontSize.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        onMeasureComplete(request.id, coordinates.size.width)
                    }
                )
            }
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
