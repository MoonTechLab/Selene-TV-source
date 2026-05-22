package org.moontechlab.selenetv.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester

/**
 * 详情覆盖层关闭后，把焦点确定性地还原到“点进详情前的那张卡片”。
 *
 * focusRestorer 在网格多行结构下并不可靠（会退回到第一个），因此改用此方案：
 * 打开详情时记录来源卡片的 key，匹配的 VideoCard 会挂上 [requester]，关闭时精确聚焦它。
 */
class DetailReturnFocusState {
    /** 来源卡片 key（VideoCard 用 [matches] 判断是否挂载 requester）。null 表示无目标。 */
    var targetKey by mutableStateOf<String?>(null)
        private set

    val requester = FocusRequester()

    fun setTarget(key: String?) {
        targetKey = key
    }

    fun matches(key: String): Boolean = targetKey != null && targetKey == key
}

val LocalDetailReturnFocus = staticCompositionLocalOf<DetailReturnFocusState?> { null }
