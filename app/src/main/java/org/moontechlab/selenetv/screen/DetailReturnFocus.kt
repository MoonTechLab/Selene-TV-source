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

    /**
     * 详情返回焦点还原进行中。期间各行（RecommendSection）的「获得焦点即跳到第一张卡片」
     * 行为需暂停，否则会把我们精确还原到的卡片弹回第一张。还原窗口结束后置回 false，
     * 不影响后续从其他行纵向进入时「自动落到第一张」的正常行为。
     */
    var restoreInProgress: Boolean = false

    /**
     * 还原是否已生效：来源卡片真正拿到焦点时由 [markFocused] 置 true。
     * HomeScreen 的还原协程据此在覆盖层滑出的时间窗口内重试 requestFocus，直到成功为止
     * （详情仍占用焦点时 requestFocus 会被拒，单次固定延时会偶发失败、焦点掉到筛选行）。
     * 在协程里轮询读取（非组合内读取），故用普通 var 即可，与 [restoreInProgress] 一致。
     */
    var targetFocused: Boolean = false

    fun setTarget(key: String?) {
        targetKey = key
        targetFocused = false
    }

    fun matches(key: String): Boolean = targetKey != null && targetKey == key

    /** 卡片获得焦点时上报；仅当其 key 与还原目标一致才视为还原成功，停止重试。 */
    fun markFocused(key: String) {
        if (matches(key)) targetFocused = true
    }
}

/**
 * 返回焦点的唯一 key。带上区块标识，避免同一内容（同 source+id）出现在多行时
 * （如「继续观看」与「我的收藏」）相互冲突、返回后焦点落到错误那张卡片。
 */
fun detailReturnKey(sectionKey: String, source: String, id: String): String =
    if (sectionKey.isNotEmpty()) "$sectionKey|$source+$id" else "$source+$id"

val LocalDetailReturnFocus = staticCompositionLocalOf<DetailReturnFocusState?> { null }
