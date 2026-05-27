package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTestChooseWidthTest {

    @Test
    fun declaredWidthWinsAndSkipsProbe() {
        var probed = false
        val width = SpeedTestService.chooseWidth(1920, byteArrayOf(1, 2, 3)) { probed = true; 720 }
        assertEquals(1920, width)
        assertEquals(false, probed)
    }

    @Test
    fun fallsBackToProbeWhenDeclaredZero() {
        val width = SpeedTestService.chooseWidth(0, byteArrayOf(1, 2, 3)) { 1280 }
        assertEquals(1280, width)
    }

    @Test
    fun returnsZeroWhenNoCapturedBytes() {
        var probed = false
        val width = SpeedTestService.chooseWidth(0, null) { probed = true; 1280 }
        assertEquals(0, width)
        assertEquals(false, probed)
    }
}
