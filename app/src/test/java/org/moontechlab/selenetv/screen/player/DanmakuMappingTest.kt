package org.moontechlab.selenetv.screen.player

import org.junit.Assert.assertEquals
import org.junit.Test
import org.moontechlab.selenetv.model.DanmakuComment
import org.moontechlab.selenetv.service.danmaku.DanmakuRenderSettings
import org.moontechlab.selenetv.ui.danmaku.DanmakuMode

class DanmakuMappingTest {

    @Test fun mapsModeAndColor() {
        val s = DanmakuComment(timeMs = 1000, mode = DanmakuComment.MODE_SCROLL, color = 0xFF0000L, content = "hi")
        val item = s.toEngineItem()
        assertEquals(DanmakuMode.SCROLL, item.mode)
        assertEquals(0xFF0000, item.colorRgb)
        assertEquals(1000L, item.timeMs)
        assertEquals("hi", item.text)
        assertEquals(1000L * 31 + "hi".hashCode(), item.id)

        assertEquals(DanmakuMode.TOP,
            DanmakuComment(0, DanmakuComment.MODE_TOP, 0xFFFFFFL, "x").toEngineItem().mode)
        assertEquals(DanmakuMode.BOTTOM,
            DanmakuComment(0, DanmakuComment.MODE_BOTTOM, 0xFFFFFFL, "x").toEngineItem().mode)
    }

    @Test fun colorTakesLow24Bits() {
        val s = DanmakuComment(0, DanmakuComment.MODE_SCROLL, 0xFFFFFFFFL, "x")
        assertEquals(0xFFFFFF, s.toEngineItem().colorRgb)
    }

    @Test fun buildsConfigFromSettings() {
        val settings = DanmakuRenderSettings(opacity = 0.7f, speed = 2.0f, fontScale = 1.2f, antiOverlap = true)
        val cfg = buildEngineConfig(settings, density = 2f, visible = true, areaFraction = 0.5f)
        assertEquals((DanmakuRenderSettings.BASE_DURATION_MS / 2.0f).toLong(), cfg.rollingDurationMs)
        assertEquals(18f * 2f * 1.2f, cfg.textSizePx, 0.001f)
        assertEquals(0.7f, cfg.opacity, 0.001f)
        assertEquals(true, cfg.antiOverlap)
        assertEquals(true, cfg.visible)
        assertEquals(0.5f, cfg.areaFraction, 0.001f)
    }
}
