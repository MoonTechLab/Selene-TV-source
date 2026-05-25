package org.moontechlab.selenetv.ui

import android.view.KeyEvent

/**
 * 「孤儿 OK 键」拦截门闩（纯逻辑，无 Compose 依赖，便于单测）。
 *
 * 背景：长按卡片（OK 键 repeatCount==1）触发 onLongClick 打开弹窗时，OK 键往往仍按住；
 * 待手指松开时那次 ACTION_UP 会落到弹窗内刚获得焦点的菜单项/按钮上。tv-material3 的
 * tvClickable 在 ACTION_UP 且 isLongClick==false 时即判定为点击，且不校验是否收到过对应的
 * ACTION_DOWN——于是这次「孤儿 key-up」被当成点击，使一次长按连锁触发下一级弹窗/确定。
 *
 * 门闩规则：只有在弹窗内见过一次全新按下（OK 键 ACTION_DOWN 且 repeatCount==0）之后，
 * 才放行随后的 ACTION_UP；否则吞掉孤儿 up。长按遗留的重复 ACTION_DOWN（repeatCount>=1）
 * 不算「全新按下」，所以不会误把孤儿 up 放行。
 */
class OkKeyClickGate {
    private var sawFreshDown = false

    private fun isOkKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> true
        else -> false
    }

    /**
     * @return true 表示应消费（吞掉）该事件，false 表示放行。
     */
    fun shouldConsume(action: Int, keyCode: Int, repeatCount: Int): Boolean {
        if (!isOkKey(keyCode)) return false
        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (repeatCount == 0) sawFreshDown = true
                false
            }
            KeyEvent.ACTION_UP -> {
                if (sawFreshDown) {
                    sawFreshDown = false
                    false
                } else {
                    true // 孤儿 up：吞掉，避免被弹窗内焦点项误判为点击
                }
            }
            else -> false
        }
    }
}
