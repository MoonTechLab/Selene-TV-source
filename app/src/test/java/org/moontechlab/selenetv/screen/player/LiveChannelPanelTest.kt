package org.moontechlab.selenetv.screen.player

import org.junit.Assert.assertEquals
import org.junit.Test
import org.moontechlab.selenetv.model.LiveChannel
import org.moontechlab.selenetv.model.LiveChannelGroup

class LiveChannelPanelTest {

    private fun channel(id: String, name: String) = LiveChannel(
        id = id,
        name = name,
        url = "http://example.com/$id.m3u8",
    )

    @Test
    fun livePanelChannelsForTab_flattensAllGroupsForAllTab() {
        val cctv1 = channel("cctv-1", "CCTV-1")
        val cctv5 = channel("cctv-5", "CCTV-5")
        val local = channel("local", "本地台")
        val groups = listOf(
            LiveChannelGroup("央视", listOf(cctv1, cctv5)),
            LiveChannelGroup("地方", listOf(local)),
        )

        assertEquals(listOf(cctv1, cctv5, local), livePanelChannelsForTab(groups, "全部"))
    }

    @Test
    fun livePanelChannelsForTab_returnsOnlySelectedGroupChannels() {
        val cctv1 = channel("cctv-1", "CCTV-1")
        val local = channel("local", "本地台")
        val groups = listOf(
            LiveChannelGroup("央视", listOf(cctv1)),
            LiveChannelGroup("地方", listOf(local)),
        )

        assertEquals(listOf(local), livePanelChannelsForTab(groups, "地方"))
    }
}
