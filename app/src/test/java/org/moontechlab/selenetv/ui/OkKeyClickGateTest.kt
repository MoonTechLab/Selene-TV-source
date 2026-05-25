package org.moontechlab.selenetv.ui

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：长按卡片打开弹窗后，那次长按松开的「孤儿 OK 键 ACTION_UP」必须被吞掉，
 * 否则会被弹窗内刚获焦的菜单项误判为点击，造成一次长按连锁触发下一级弹窗/确定。
 * 见 [OkKeyClickGate] 与 CardContextMenu.OverlayScaffold。
 */
class OkKeyClickGateTest {

    private val OK = KeyEvent.KEYCODE_DPAD_CENTER
    private val DOWN = KeyEvent.ACTION_DOWN
    private val UP = KeyEvent.ACTION_UP

    /** 长按打开弹窗后，弹窗只收到长按遗留的重复 down（repeatCount>=2）与最终的 up。 */
    @Test
    fun orphanUp_fromHeldLongPress_isConsumed() {
        val gate = OkKeyClickGate()
        // 长按已在卡片上消耗了 repeatCount 0、1；弹窗夺焦后只看到后续重复 down…
        assertFalse(gate.shouldConsume(DOWN, OK, 2))
        assertFalse(gate.shouldConsume(DOWN, OK, 3))
        // …以及松手时这次孤儿 up：必须吞掉。
        assertTrue("孤儿 up 必须被消费", gate.shouldConsume(UP, OK, 0))
    }

    /** 弹窗打开后用户的全新一次按下→松开，是真实点击，必须放行。 */
    @Test
    fun freshPressThenRelease_isAllowed() {
        val gate = OkKeyClickGate()
        // 先吞掉打开弹窗那次长按的孤儿 up
        assertTrue(gate.shouldConsume(UP, OK, 0))
        // 之后真实点击：repeatCount==0 的 down 起头
        assertFalse(gate.shouldConsume(DOWN, OK, 0))
        assertFalse("全新按下后的 up 是真实点击，必须放行", gate.shouldConsume(UP, OK, 0))
    }

    /** 连续两次孤儿 up（无全新按下）都应被吞掉，防止连锁。 */
    @Test
    fun consecutiveOrphanUps_areAllConsumed() {
        val gate = OkKeyClickGate()
        assertTrue(gate.shouldConsume(UP, OK, 0))
        assertTrue(gate.shouldConsume(UP, OK, 0))
    }

    /** 非 OK 键（如返回/方向键）一律放行，不受门闩影响。 */
    @Test
    fun nonOkKeys_arePassedThrough() {
        val gate = OkKeyClickGate()
        assertFalse(gate.shouldConsume(UP, KeyEvent.KEYCODE_BACK, 0))
        assertFalse(gate.shouldConsume(DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0))
        assertFalse(gate.shouldConsume(UP, KeyEvent.KEYCODE_DPAD_DOWN, 0))
    }

    /** ENTER / NUMPAD_ENTER 同样视为 OK 键。 */
    @Test
    fun enterKeys_areTreatedAsOk() {
        val gate = OkKeyClickGate()
        assertTrue(gate.shouldConsume(UP, KeyEvent.KEYCODE_ENTER, 0))
        val gate2 = OkKeyClickGate()
        assertTrue(gate2.shouldConsume(UP, KeyEvent.KEYCODE_NUMPAD_ENTER, 0))
    }
}
