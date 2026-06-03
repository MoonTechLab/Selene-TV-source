package org.moontechlab.selenetv.ui.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** 固定宽度的假测量：宽度 = 字数 × 字号 × 0.5，与设备无关、可断言。 */
class FakeMeasurer : TextMeasurer {
    override fun measure(text: String, textSizePx: Float): Float = text.length * textSizePx * 0.5f
}

private fun scroll(id: Long, t: Long, text: String = "abc") =
    DanmakuItem(id, t, text, DanmakuMode.SCROLL, 0xFFFFFF)

class DanmakuEngineTest {

    private fun engine(): DanmakuEngine = DanmakuEngine(FakeMeasurer()).apply {
        setViewport(1920, 1080)
        setConfig(DanmakuEngineConfig(rollingDurationMs = 8000L, textSizePx = 40f))
    }

    @Test fun spawnsOnlyDueItems() {
        val e = engine()
        e.setItems(listOf(scroll(1, 1000), scroll(2, 2000), scroll(3, 3000)))
        e.advance(1500)
        assertEquals(listOf(1L), e.active.map { it.id })
        e.advance(2500)
        assertEquals(setOf(1L, 2L), e.active.map { it.id }.toSet())
    }

    @Test fun retiresAfterRollingDuration() {
        val e = engine()
        e.setItems(listOf(scroll(1, 0)))
        e.advance(0)
        assertTrue(e.active.any { it.id == 1L })
        e.advance(8001)
        assertFalse(e.active.any { it.id == 1L })
    }

    @Test fun seekClearsActiveAndRepositionsCursor() {
        val e = engine()
        e.setItems((1..10).map { scroll(it.toLong(), it * 1000L) })
        e.advance(2500)
        assertTrue(e.active.isNotEmpty())
        e.seekTo(5000)
        assertTrue(e.active.isEmpty())
        e.advance(5000)
        assertEquals(listOf(5L), e.active.map { it.id })
    }

    @Test fun seekBackwardDoesNotReplayFromZero() {
        val e = engine()
        e.setItems((1..10).map { scroll(it.toLong(), it * 1000L) })
        e.advance(9000)
        e.seekTo(3000)
        e.advance(3000)
        assertEquals(listOf(3L), e.active.map { it.id })
    }

    @Test fun scrollSpreadsAcrossLanesWhenOverlapAllowed() {
        val e = DanmakuEngine(FakeMeasurer()).apply {
            setViewport(1920, 1080)
            setConfig(DanmakuEngineConfig(rollingDurationMs = 8000L, textSizePx = 40f, antiOverlap = false))
        }
        e.setItems(listOf(scroll(1, 0), scroll(2, 0), scroll(3, 0)))
        e.advance(0)
        assertEquals(3, e.active.map { it.lane }.toSet().size)
    }

    @Test fun antiOverlapDropsWhenAllLanesBusy() {
        val e = DanmakuEngine(FakeMeasurer()).apply {
            setViewport(1920, 56)
            setConfig(DanmakuEngineConfig(rollingDurationMs = 8000L, textSizePx = 40f, antiOverlap = true))
        }
        e.setItems(listOf(scroll(1, 0), scroll(2, 0)))
        e.advance(0)
        assertEquals(listOf(1L), e.active.map { it.id })
    }

    @Test fun antiOverlapAdmitsAfterPreviousFullyEntered() {
        val e = DanmakuEngine(FakeMeasurer()).apply {
            setViewport(1920, 56)
            setConfig(DanmakuEngineConfig(rollingDurationMs = 8000L, textSizePx = 40f, antiOverlap = true))
        }
        e.setItems(listOf(scroll(1, 0), scroll(2, 7999)))
        e.advance(0)
        e.advance(7999)
        assertTrue(e.active.any { it.id == 2L })
    }
}
