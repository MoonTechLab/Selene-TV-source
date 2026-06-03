package org.moontechlab.selenetv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MoonToastGate 回归测试，覆盖 issue #4：返回键退出后进程被系统缓存，再次启动时
 * 单例 trigger 仍为上一次操作的非零值，不应重弹旧消息。
 */
class MoonToastGateTest {

    @Test
    fun `cold start with zero trigger does not show`() {
        val gate = MoonToastGate(0)
        assertFalse(gate.shouldShow(0))
    }

    @Test
    fun `new trigger after mount shows once`() {
        val gate = MoonToastGate(0)
        assertTrue(gate.shouldShow(1))
        // 同一 trigger 重复观察（重组）不重弹
        assertFalse(gate.shouldShow(1))
    }

    @Test
    fun `warm restart with retained nonzero trigger does not re-show stale toast`() {
        // host 重新挂载时基线 = 上一次操作留下的 trigger=1；不应放行旧消息
        val gate = MoonToastGate(1)
        assertFalse(gate.shouldShow(1))
    }

    @Test
    fun `new show after warm restart still works`() {
        val gate = MoonToastGate(1)
        assertFalse(gate.shouldShow(1))
        assertTrue(gate.shouldShow(2))
    }
}
