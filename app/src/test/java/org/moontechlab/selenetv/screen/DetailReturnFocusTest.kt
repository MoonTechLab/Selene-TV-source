package org.moontechlab.selenetv.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 回归测试：同一内容（同 source+id）出现在多行时，返回焦点 key 必须按区块区分，
 * 否则从「继续观看」点进详情、返回后焦点会落到「我的收藏」里同名卡片。
 */
class DetailReturnFocusTest {
    @Test
    fun `same content in different sections yields different keys`() {
        val inContinue = detailReturnKey("继续观看", "dyttzy", "52700")
        val inFavorites = detailReturnKey("我的收藏", "dyttzy", "52700")
        assertNotEquals(inContinue, inFavorites)
    }

    @Test
    fun `matches is section-aware`() {
        val state = DetailReturnFocusState()
        state.setTarget(detailReturnKey("继续观看", "dyttzy", "52700"))
        assert(state.matches(detailReturnKey("继续观看", "dyttzy", "52700")))
        assert(!state.matches(detailReturnKey("我的收藏", "dyttzy", "52700")))
    }

    @Test
    fun `empty section falls back to source+id`() {
        assertEquals("dyttzy+52700", detailReturnKey("", "dyttzy", "52700"))
    }
}
