package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.moontechlab.selenetv.model.SourceSpeed
import org.moontechlab.selenetv.model.SpeedTestState

class SpeedTestRankBadgeTest {

    @Test
    fun rank_orders_done_above_testing_above_failed() {
        val done = SourceSpeed(SpeedTestState.DONE, widthPx = 1920, speedKbps = 1000.0)
        val testing = SourceSpeed(SpeedTestState.TESTING)
        val failed = SourceSpeed(SpeedTestState.FAILED)
        assertTrue(SpeedTestService.rank(done, 1000.0) > SpeedTestService.rank(testing, 1000.0))
        assertTrue(SpeedTestService.rank(testing, 1000.0) > SpeedTestService.rank(failed, 1000.0))
        assertEquals(-1.0, SpeedTestService.rank(null, 1000.0), 0.0)
        assertEquals(-1.0, SpeedTestService.rank(testing, 1000.0), 0.0)
        assertEquals(-2.0, SpeedTestService.rank(failed, 1000.0), 0.0)
    }

    @Test
    fun rank_two_done_sorted_by_score() {
        val hi = SourceSpeed(SpeedTestState.DONE, widthPx = 1920, speedKbps = 1000.0)
        val lo = SourceSpeed(SpeedTestState.DONE, widthPx = 640, speedKbps = 100.0)
        assertTrue(SpeedTestService.rank(hi, 1000.0) > SpeedTestService.rank(lo, 1000.0))
    }

    @Test
    fun badgeLabel_states() {
        assertEquals("测速中", SpeedTestService.badgeLabel(SourceSpeed(SpeedTestState.TESTING)))
        assertEquals("超时", SpeedTestService.badgeLabel(SourceSpeed(SpeedTestState.FAILED)))
        assertEquals(
            "1080p · 2.1MB/s",
            SpeedTestService.badgeLabel(SourceSpeed(SpeedTestState.DONE, quality = "1080p", speedLabel = "2.1MB/s")),
        )
        assertEquals(
            "2.1MB/s",
            SpeedTestService.badgeLabel(SourceSpeed(SpeedTestState.DONE, quality = "", speedLabel = "2.1MB/s")),
        )
        assertEquals("未知", SpeedTestService.badgeLabel(SourceSpeed(SpeedTestState.DONE, quality = "", speedLabel = "")))
    }
}
