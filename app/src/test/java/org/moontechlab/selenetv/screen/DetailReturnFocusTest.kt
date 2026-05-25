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

    // 还原协程靠 targetFocused 判断 requestFocus 是否生效，从而在覆盖层滑出的时间窗口内
    // 重试到成功为止（修复搜索卡片返回焦点偶发失败、掉到筛选行的竞态）。
    @Test
    fun `markFocused only fires for the matching card`() {
        val state = DetailReturnFocusState()
        state.setTarget(detailReturnKey("", "src", "agg-key"))

        // 非目标卡片获得焦点不应标记
        state.markFocused(detailReturnKey("", "other", "agg-key"))
        assert(!state.targetFocused)

        // 目标卡片获得焦点才标记成功
        state.markFocused(detailReturnKey("", "src", "agg-key"))
        assert(state.targetFocused)
    }

    @Test
    fun `markFocused does nothing when no target set`() {
        val state = DetailReturnFocusState()
        state.markFocused(detailReturnKey("", "src", "agg-key"))
        assert(!state.targetFocused)
    }
}
