package org.moontechlab.selenetv

import org.junit.Assert.assertEquals
import org.junit.Test
import org.moontechlab.selenetv.screen.player.DefaultAspectOptions
import org.moontechlab.selenetv.screen.player.VideoResizeMode
import org.moontechlab.selenetv.screen.player.aspectLabel
import org.moontechlab.selenetv.screen.player.aspectPillLabel

class AspectRatioTest {

    @Test
    fun aspectLabel_returns_chinese_names() {
        assertEquals("默认", aspectLabel(VideoResizeMode.FIT))
        assertEquals("铺满", aspectLabel(VideoResizeMode.ZOOM))
        assertEquals("拉伸", aspectLabel(VideoResizeMode.FILL))
    }

    @Test
    fun aspectPillLabel_shows_feature_name_when_default() {
        assertEquals("画面比例", aspectPillLabel(VideoResizeMode.FIT))
        assertEquals("铺满", aspectPillLabel(VideoResizeMode.ZOOM))
        assertEquals("拉伸", aspectPillLabel(VideoResizeMode.FILL))
    }

    @Test
    fun defaultAspectOptions_are_fit_zoom_fill_in_order() {
        assertEquals(
            listOf(VideoResizeMode.FIT, VideoResizeMode.ZOOM, VideoResizeMode.FILL),
            DefaultAspectOptions.map { it.mode },
        )
    }
}
